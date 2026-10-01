package com.example.healthmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import java.util.List;
import java.util.Locale;

/**
 * UserDashboardFragment v5
 *
 * GIỮ TẤT CẢ FIX TRƯỚC (v4).
 *
 * FIX MỚI (v5):
 *  - tvDeviceId LUÔN lấy từ server (thiết bị admin đăng ký), không đọc cache
 *    local để tránh hiển thị thiết bị cũ sau khi admin unpair/đổi thiết bị.
 *  - Nếu server không có thiết bị nào active → hiển thị "--" (chưa có thiết bị).
 *  - Không lưu deviceId vào SharedPrefs khi nhận data nữa vì server là nguồn sự thật.
 *    SharedPrefs chỉ dùng để cache tạm để không bị trắng trước khi gọi xong API.
 */
public class UserDashboardFragment extends Fragment {

    private static final String TAG = "UserDashboard";

    private TextView tvDeviceId, tvHR, tvSpo2, tvTemp, tvBleStatus;
    private TextView tvHrStatus, tvSpo2Status, tvTempStatus;
    private TextView btnMeasure;
    private ProgressBar progressMeasuring;
    private ProgressBar gaugeHR, gaugeSpo2, gaugeTemp;
    private View statusDot;

    private boolean isMeasuring = false;
    private String  myUserId    = "0";

    private static final long MEASURE_TIMEOUT_MS = 60_000L;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_user_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvDeviceId        = view.findViewById(R.id.tv_device_id);
        tvHR              = view.findViewById(R.id.tv_heart_rate);
        tvSpo2            = view.findViewById(R.id.tv_spo2);
        tvTemp            = view.findViewById(R.id.tv_temperature);
        tvBleStatus       = view.findViewById(R.id.tv_ble_status);
        tvHrStatus        = view.findViewById(R.id.tv_hr_status);
        tvSpo2Status      = view.findViewById(R.id.tv_spo2_status);
        tvTempStatus      = view.findViewById(R.id.tv_temp_status);
        btnMeasure        = view.findViewById(R.id.btn_measure);
        progressMeasuring = view.findViewById(R.id.progress_connecting);
        gaugeHR           = view.findViewById(R.id.gauge_hr);
        gaugeSpo2         = view.findViewById(R.id.gauge_spo2);
        gaugeTemp         = view.findViewById(R.id.gauge_temp);
        statusDot         = view.findViewById(R.id.status_dot);

        AppCore.AppHelper helper = AppCore.AppHelper.getInstance(requireContext());
        myUserId = helper.getUserId();
        Log.d(TAG, "onViewCreated for userId=" + myUserId);

        // Hiển thị "--" trước, rồi gọi server để lấy thiết bị admin đăng ký
        tvDeviceId.setText("--");

        // Khôi phục giá trị đo lần cuối từ prefs (HR/SpO2/Temp)
        float lastHR = helper.getLastHR(), lastSpo2 = helper.getLastSpo2(), lastTemp = helper.getLastTemp();
        if (lastHR > 0 || lastSpo2 > 0 || lastTemp > 0) {
            AppModel.SensorData cached = new AppModel.SensorData(
                    lastHR, lastSpo2, lastTemp, "", helper.getUserId(), "CACHE");
            updateUI(cached);
        }

        // Luôn lấy thiết bị từ server — server là nguồn sự thật duy nhất
        loadDeviceFromServer(helper);

        // ── FIX BUG 1: observer filter userId ─────────────────────
        AppCore.DataManager.getInstance().liveData.observe(getViewLifecycleOwner(), data -> {
            if (data == null) return;
            if (data.userId != null && !data.userId.equals(myUserId)) {
                Log.d(TAG, "Bỏ qua data của user khác: " + data.userId + " (tôi=" + myUserId + ")");
                return;
            }
            updateUI(data);
            helper.saveLastHR(data.heartRate);
            helper.saveLastSpo2(data.spo2);
            helper.saveLastTemp(data.temperature);
            // KHÔNG saveDeviceId ở đây nữa — server là nguồn sự thật
            resetMeasureUI();
        });

        // ── Status messages từ ESP32 trong lúc đo ─────────────────
        AppCore.DataManager.getInstance().statusLiveData.observe(getViewLifecycleOwner(), status -> {
            if (status == null) return;
            if (isMeasuring) {
                tvBleStatus.setText(humanizeStatus(status));
                tvBleStatus.setTextColor(0xFF2196F3);
            }
            if (isFailStatus(status)) {
                resetMeasureUI();
                tvBleStatus.setText(humanizeStatus(status));
                tvBleStatus.setTextColor(0xFFFF1744);
                tvBleStatus.postDelayed(this::updateConnectionIndicator, 3000);
            }
        });

        // ── Observe ConnectionState để cập nhật indicator BLE/MQTT ──
        AppCore.ConnectionState cs = AppCore.ConnectionState.getInstance();
        cs.bleState.observe(getViewLifecycleOwner(), s -> updateConnectionIndicator());
        cs.mqttState.observe(getViewLifecycleOwner(), s -> updateConnectionIndicator());

        updateConnectionIndicator();
        btnMeasure.setOnClickListener(v -> onMeasureClicked());
    }

    /**
     * Luôn gọi server để lấy thiết bị admin đã đăng ký.
     * Không dùng cache local để tránh nhớ thiết bị cũ sau khi admin unpair.
     *
     * Nếu server trả về danh sách rỗng → hiện "--" (admin chưa đăng ký thiết bị).
     * Nếu server có thiết bị → hiển thị + lưu vào SharedPrefs để làm default.
     */
    private void loadDeviceFromServer(AppCore.AppHelper helper) {
        AppNetwork.RetrofitHelper.getInstance().getMyDevices()
                .enqueue(new retrofit2.Callback<List<AppModel.Device>>() {
                    @Override
                    public void onResponse(retrofit2.Call<List<AppModel.Device>> call,
                                           retrofit2.Response<List<AppModel.Device>> response) {
                        if (!isAdded()) return;
                        if (response.isSuccessful() && response.body() != null
                                && !response.body().isEmpty()) {
                            AppModel.Device first = response.body().get(0);
                            String devId = (first.deviceId != null && !first.deviceId.isEmpty())
                                    ? first.deviceId : "--";
                            helper.saveDeviceId(devId);
                            requireActivity().runOnUiThread(() -> tvDeviceId.setText(devId));
                        } else {
                            // Admin chưa đăng ký thiết bị → xóa cache cũ, hiện "--"
                            helper.saveDeviceId("Chưa kết nối");
                            requireActivity().runOnUiThread(() -> tvDeviceId.setText("--"));
                        }
                    }
                    @Override
                    public void onFailure(retrofit2.Call<List<AppModel.Device>> c, Throwable t) {
                        // Mất mạng → giữ nguyên text "--" (đã set ở onViewCreated)
                        Log.w(TAG, "loadDeviceFromServer failed: " + t.getMessage());
                    }
                });
    }

    private void updateConnectionIndicator() {
        if (!isAdded() || statusDot == null) return;

        AppCore.ConnectionState cs = AppCore.ConnectionState.getInstance();
        AppCore.ConnectionState.Status ble  = cs.bleState.getValue();
        AppCore.ConnectionState.Status mqtt = cs.mqttState.getValue();

        boolean bleOn  = ble  == AppCore.ConnectionState.Status.CONNECTED;
        boolean mqttOn = mqtt == AppCore.ConnectionState.Status.CONNECTED;

        int    dotColor;
        String text;
        int    textColor;

        if (bleOn && mqttOn) {
            dotColor  = 0xFF00C853;
            text      = "Đã kết nối (BLE + MQTT)";
            textColor = 0xFF00C853;
        } else if (bleOn) {
            dotColor  = 0xFF00C853;
            text      = "Đã kết nối qua BLE";
            textColor = 0xFF00C853;
        } else if (mqttOn) {
            dotColor  = 0xFF00C853;
            text      = "Đã kết nối qua MQTT";
            textColor = 0xFF00C853;
        } else if (ble == AppCore.ConnectionState.Status.CONNECTING
                || mqtt == AppCore.ConnectionState.Status.CONNECTING) {
            dotColor  = 0xFFFFD600;
            text      = "Đang kết nối...";
            textColor = 0xFFFFB300;
        } else {
            dotColor  = 0xFFFF1744;
            text      = "Chưa kết nối";
            textColor = 0xFFFF1744;
        }

        final int dc = dotColor, tc = textColor;
        final String t = text;
        statusDot.post(() ->
                statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dc)));

        if (!isMeasuring) {
            tvBleStatus.post(() -> {
                tvBleStatus.setText(t);
                tvBleStatus.setTextColor(tc);
            });
        }
    }

    private void onMeasureClicked() {
        if (isMeasuring) {
            Toast.makeText(getContext(), "Đang đo, vui lòng đợi...", Toast.LENGTH_SHORT).show();
            return;
        }

        AppCore.AppHelper helper = AppCore.AppHelper.getInstance(requireContext());
        String deviceId = helper.getDeviceId();
        if (deviceId == null || deviceId.isEmpty() || "Chưa kết nối".equals(deviceId)) {
            Toast.makeText(getContext(), "Chưa có thiết bị. Vui lòng nhờ admin đăng ký.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Intent svc = new Intent(requireContext(), AppService.MonitoringService.class);
        requireActivity().startForegroundService(svc);
        helper.saveServiceState(true);

        isMeasuring = true;
        progressMeasuring.setVisibility(View.VISIBLE);
        btnMeasure.setEnabled(false);
        btnMeasure.setText("ĐANG ĐO...");
        tvBleStatus.setText("Đang gửi lệnh đo...");
        tvBleStatus.setTextColor(0xFF2196F3);

        // ── FIX BUG 2: gửi lệnh MQTT + BLE song song ──
        int myUid = helper.getUserIdInt();
        String cmd = "{\"cmd\":\"START_MEASURE\",\"uid\":" + myUid + "}";

        boolean mqttSent = AppNetwork.MqttHelper.getInstance().publishCommand(cmd);

        boolean bleSent = false;
        AppNetwork.BleHelper ble = AppNetwork.BleHelper.getInstance(requireContext());
        if (ble.isConnected()) {
            try { ble.writeCommand(cmd); bleSent = true; }
            catch (SecurityException e) { Log.w(TAG, "BLE permission lost", e); }
            catch (Exception e) { Log.w(TAG, "BLE write error", e); }
        }

        Log.d(TAG, "Gửi lệnh đo: MQTT=" + mqttSent + " BLE=" + bleSent + " uid=" + myUid);

        if (mqttSent && bleSent) {
            Toast.makeText(getContext(), "Đã gửi lệnh đo (MQTT + BLE)", Toast.LENGTH_SHORT).show();
        } else if (mqttSent) {
            Toast.makeText(getContext(), "Đã gửi lệnh đo (MQTT)", Toast.LENGTH_SHORT).show();
        } else if (bleSent) {
            Toast.makeText(getContext(), "Đã gửi lệnh đo (BLE, MQTT đang chờ)", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(),
                    "Đang chờ kết nối — lệnh sẽ tự gửi khi sẵn sàng",
                    Toast.LENGTH_LONG).show();
        }

        cancelTimeout();
        timeoutRunnable = () -> {
            if (!isMeasuring) return;
            resetMeasureUI();
            tvBleStatus.setText("Đo timeout — không nhận được kết quả từ thiết bị");
            tvBleStatus.setTextColor(0xFFFF1744);
            Toast.makeText(getContext(),
                    "Đo timeout. Kiểm tra ESP32 / WiFi / MQTT rồi thử lại.",
                    Toast.LENGTH_LONG).show();
            tvBleStatus.postDelayed(this::updateConnectionIndicator, 3000);
        };
        timeoutHandler.postDelayed(timeoutRunnable, MEASURE_TIMEOUT_MS);
    }

    private void resetMeasureUI() {
        isMeasuring = false;
        cancelTimeout();
        progressMeasuring.post(() -> progressMeasuring.setVisibility(View.GONE));
        btnMeasure.post(() -> {
            btnMeasure.setEnabled(true);
            btnMeasure.setText("ĐO NGAY");
        });
        tvBleStatus.postDelayed(this::updateConnectionIndicator, 1500);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private boolean isFailStatus(String code) {
        return "TIMEOUT_FINGER".equals(code) || "TIMEOUT_TEMP".equals(code)
                || "ERROR_HR".equals(code) || "ERROR_TEMP".equals(code)
                || "MEASURE_FAILED".equals(code) || "CANCELLED".equals(code);
    }

    private String humanizeStatus(String code) {
        if (code == null) return "";
        switch (code) {
            case "MEASURE_START":    return "Bắt đầu đo...";
            case "WAITING_FINGER":   return "Đặt ngón tay lên cảm biến";
            case "MEASURING_HR":     return "Đang đo nhịp tim... giữ tay yên";
            case "WAITING_FOREHEAD": return "Đưa trán lại gần cảm biến nhiệt (2–5 cm)";
            case "MEASURING_TEMP":   return "Đang đo nhiệt độ...";
            case "MEASURE_DONE":     return "Đo xong, đang xử lý...";
            case "TIMEOUT_FINGER":   return "Quá thời gian — không phát hiện ngón tay";
            case "TIMEOUT_TEMP":     return "Quá thời gian — không phát hiện trán";
            case "ERROR_HR":         return "Lỗi đo nhịp tim, thử lại";
            case "ERROR_TEMP":       return "Lỗi đo nhiệt độ";
            case "MEASURE_FAILED":   return "Đo thất bại";
            case "CANCELLED":        return "Đã hủy đo";
            default:                 return code;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelTimeout();
    }

    private void updateUI(AppModel.SensorData data) {
        requireActivity().runOnUiThread(() -> {
            tvHR.setText(data.heartRate   > 0 ? String.format(Locale.getDefault(), "%.0f", data.heartRate)   : "--");
            tvSpo2.setText(data.spo2      > 0 ? String.format(Locale.getDefault(), "%.1f", data.spo2)        : "--");
            tvTemp.setText(data.temperature > 0 ? String.format(Locale.getDefault(), "%.1f", data.temperature) : "--");
            gaugeHR.setProgress((int) data.heartRate);
            gaugeSpo2.setProgress((int) data.spo2);
            gaugeTemp.setProgress((int) (data.temperature * 10));
            updateHeartStatus(data.heartRate);
            updateSpo2Status(data.spo2);
            updateTempStatus(data.temperature);
        });
    }

    private void updateHeartStatus(float hr) {
        if (hr <= 0) { tvHrStatus.setText("Chưa đo"); tvHrStatus.setTextColor(0xFF757575); return; }
        if (hr < 60) { tvHrStatus.setText("Thấp ⚠"); tvHrStatus.setTextColor(0xFFFFD600); tvHR.setTextColor(0xFFFFD600); }
        else if (hr > 100) { tvHrStatus.setText("Cao ⚠"); tvHrStatus.setTextColor(0xFFFF1744); tvHR.setTextColor(0xFFFF1744); }
        else { tvHrStatus.setText("Bình thường ✓"); tvHrStatus.setTextColor(0xFF00C853); tvHR.setTextColor(0xFFFF1744); }
    }

    private void updateSpo2Status(float spo2) {
        if (spo2 <= 0) { tvSpo2Status.setText("Chưa đo"); tvSpo2Status.setTextColor(0xFF757575); return; }
        if (spo2 < 90) { tvSpo2Status.setText("Nguy hiểm ⚠"); tvSpo2Status.setTextColor(0xFFFF1744); tvSpo2.setTextColor(0xFFFF1744); }
        else if (spo2 < 95) { tvSpo2Status.setText("Hơi thấp"); tvSpo2Status.setTextColor(0xFFFFD600); tvSpo2.setTextColor(0xFFFFD600); }
        else { tvSpo2Status.setText("Bình thường ✓"); tvSpo2Status.setTextColor(0xFF00C853); tvSpo2.setTextColor(0xFF2979FF); }
    }

    private void updateTempStatus(float temp) {
        if (temp <= 0) { tvTempStatus.setText("Chưa đo"); tvTempStatus.setTextColor(0xFF757575); return; }
        if (temp < 36.1f) { tvTempStatus.setText("Hạ thân nhiệt"); tvTempStatus.setTextColor(0xFF2979FF); tvTemp.setTextColor(0xFF2979FF); }
        else if (temp > 37.5f) { tvTempStatus.setText("Sốt ⚠"); tvTempStatus.setTextColor(0xFFFF1744); tvTemp.setTextColor(0xFFFF1744); }
        else { tvTempStatus.setText("Bình thường ✓"); tvTempStatus.setTextColor(0xFF00C853); tvTemp.setTextColor(0xFFFF9100); }
    }
}