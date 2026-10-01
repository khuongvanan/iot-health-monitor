package com.example.healthmonitor;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ConnectionStatusFragment v3
 *
 * FIX:
 *  1. "Kiểm tra tất cả kết nối" KHÔNG tự reconnect BLE — chỉ đọc trạng thái,
 *     không gọi scanDevices() hay connect() ngầm.
 *  2. Nút "Quét & Kết nối" chỉ hoạt động khi admin đã đăng ký thiết bị
 *     (deviceId trong SharedPrefs != "Chưa kết nối" và != rỗng).
 *     Nếu chưa đăng ký → hiện Toast hướng dẫn.
 *  3. Nút "Unpair" và trạng thái thứ 2 (Ngắt & Unpair) chỉ dành cho admin
 *     (role == "admin"). Guest chỉ thấy nút "Quét & Kết nối".
 */
public class ConnectionStatusFragment extends Fragment {

    private static final String TAG = "ConnStatusFrag";

    private View     dotEsp, dotServer, dotMqtt, dotBle;
    private TextView tvEspState,    tvEspTime,    tvEspSensors;
    private TextView tvServerState, tvServerTime;
    private TextView tvMqttState,   tvMqttTime;
    private TextView tvBleState,    tvBleTime;

    private Button   btnCheckAll;
    private Button   btnUnpair, btnScan;
    private TextView tvBleActionStatus;

    private boolean isScanning = false;

    private final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss  dd/MM/yyyy", Locale.getDefault());

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_connection_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dotEsp    = view.findViewById(R.id.dot_esp);
        dotServer = view.findViewById(R.id.dot_server);
        dotMqtt   = view.findViewById(R.id.dot_mqtt);
        dotBle    = view.findViewById(R.id.dot_ble);

        tvEspState    = view.findViewById(R.id.tv_esp_state);
        tvEspTime     = view.findViewById(R.id.tv_esp_time);
        tvEspSensors  = view.findViewById(R.id.tv_esp_sensors);
        tvServerState = view.findViewById(R.id.tv_server_state);
        tvServerTime  = view.findViewById(R.id.tv_server_time);
        tvMqttState   = view.findViewById(R.id.tv_mqtt_state);
        tvMqttTime    = view.findViewById(R.id.tv_mqtt_time);
        tvBleState    = view.findViewById(R.id.tv_ble_state);
        tvBleTime     = view.findViewById(R.id.tv_ble_time);

        btnCheckAll       = view.findViewById(R.id.btn_check_all);
        btnUnpair         = view.findViewById(R.id.btn_unpair);
        btnScan           = view.findViewById(R.id.btn_scan);
        tvBleActionStatus = view.findViewById(R.id.tv_ble_action_status);

        AppCore.ConnectionState cs = AppCore.ConnectionState.getInstance();

        // ── Observe 4 LiveData state ──
        cs.bleState.observe(getViewLifecycleOwner(), s ->
                renderCard(dotBle, tvBleState, tvBleTime, s, cs.timeBle));
        cs.mqttState.observe(getViewLifecycleOwner(), s ->
                renderCard(dotMqtt, tvMqttState, tvMqttTime, s, cs.timeMqtt));
        cs.serverState.observe(getViewLifecycleOwner(), s ->
                renderCard(dotServer, tvServerState, tvServerTime, s, cs.timeServer));
        cs.espState.observe(getViewLifecycleOwner(), s ->
                renderCard(dotEsp, tvEspState, tvEspTime, s, cs.timeEsp));

        // ── Observe sensor status ──
        cs.sensorMax.observe(getViewLifecycleOwner(), v -> updateSensorRow());
        cs.sensorMlx.observe(getViewLifecycleOwner(), v -> updateSensorRow());

        // ── Observe BLE state để hiện/ẩn nút theo role ──
        cs.bleState.observe(getViewLifecycleOwner(), this::updateBleButtons);

        // ── Observe checking flag để disable nút khi đang check ──
        cs.checking.observe(getViewLifecycleOwner(), checking -> {
            if (checking != null && checking) {
                btnCheckAll.setEnabled(false);
                btnCheckAll.setText("Đang kiểm tra...");
            } else {
                btnCheckAll.setEnabled(true);
                btnCheckAll.setText("Kiểm tra tất cả kết nối");
            }
        });

        // Render lần đầu với state hiện tại trong ConnectionState
        renderCard(dotBle,    tvBleState,    tvBleTime,    cs.bleState.getValue(),    cs.timeBle);
        renderCard(dotMqtt,   tvMqttState,   tvMqttTime,   cs.mqttState.getValue(),   cs.timeMqtt);
        renderCard(dotServer, tvServerState, tvServerTime, cs.serverState.getValue(), cs.timeServer);
        renderCard(dotEsp,    tvEspState,    tvEspTime,    cs.espState.getValue(),    cs.timeEsp);
        updateSensorRow();
        updateBleButtons(cs.bleState.getValue());

        // ── Buttons ──
        btnCheckAll.setOnClickListener(v -> performHealthCheck());
        btnUnpair.setOnClickListener(v -> showUnpairConfirmDialog());
        btnScan.setOnClickListener(v -> startScan());
    }

    // =========================================================
    //  Comprehensive check — CHỈ kiểm tra, KHÔNG tự kết nối BLE
    //  và KHÔNG start MonitoringService (vì service.onStartCommand
    //  sẽ gọi startBleIfNeeded → tự scan + connect ngầm).
    // =========================================================
    private void performHealthCheck() {
        if (!isAdded()) return;
        runHealthCheckLogic();
        Toast.makeText(getContext(), "Đang kiểm tra kết nối...", Toast.LENGTH_SHORT).show();
    }

    /**
     * Kiểm tra 4 kênh — semantics gắn với "đã đăng ký thiết bị" trên server.
     *
     * Logic:
     *   B1. Ping server lấy danh sách thiết bị active (getMyDevices).
     *   B2a. Server fail → Server = ERROR/DISCONNECTED, dừng.
     *   B2b. Server OK + list rỗng → coi như chưa có thiết bị nào để theo dõi:
     *        BLE/MQTT/ESP32 = DISCONNECTED, Server = CONNECTED, Toast nhắc đăng ký.
     *        Đồng thời sync cache local về "Chưa kết nối" để các fragment khác
     *        (Dashboard, UserDashboard) không hiển thị device cũ.
     *   B2c. Server OK + có thiết bị → check 3 kênh còn lại như cũ (MQTT, BLE, ESP32).
     *
     * BLE chỉ đọc trạng thái — không scan/connect tự động.
     */
    @SuppressLint("MissingPermission")
    private void runHealthCheckLogic() {
        AppCore.ConnectionState cs = AppCore.ConnectionState.getInstance();
        cs.setChecking(true);

        // Bước 1: Hỏi server xem có thiết bị nào đang active không
        cs.setServer(AppCore.ConnectionState.Status.CONNECTING);
        AppNetwork.RetrofitHelper.getInstance().getMyDevices()
                .enqueue(new retrofit2.Callback<List<AppModel.Device>>() {
                    @Override
                    public void onResponse(retrofit2.Call<List<AppModel.Device>> call,
                                           retrofit2.Response<List<AppModel.Device>> response) {
                        if (!isAdded()) return;

                        // Server không reachable hoặc lỗi (trừ 401 vẫn coi là server sống)
                        if (!response.isSuccessful() && response.code() != 401) {
                            cs.setServer(AppCore.ConnectionState.Status.ERROR);
                            cs.setBle(AppCore.ConnectionState.Status.DISCONNECTED);
                            cs.setMqtt(AppCore.ConnectionState.Status.DISCONNECTED);
                            cs.setEsp(AppCore.ConnectionState.Status.DISCONNECTED);
                            cs.setChecking(false);
                            return;
                        }
                        cs.setServer(AppCore.ConnectionState.Status.CONNECTED);

                        boolean hasDevice = response.body() != null
                                && !response.body().isEmpty();

                        if (!hasDevice) {
                            // Chưa có thiết bị đăng ký → các kênh thiết bị đều DISCONNECTED.
                            // Đồng bộ cache local để các nơi khác biết.
                            AppCore.AppHelper.getInstance(requireContext())
                                    .saveDeviceId("Chưa kết nối");
                            cs.setBle(AppCore.ConnectionState.Status.DISCONNECTED);
                            cs.setMqtt(AppCore.ConnectionState.Status.DISCONNECTED);
                            cs.setEsp(AppCore.ConnectionState.Status.DISCONNECTED);
                            cs.setChecking(false);

                            String role = AppCore.AppHelper.getInstance(requireContext()).getRole();
                            String msg = "admin".equals(role)
                                    ? "Chưa có thiết bị nào được đăng ký. Vào tab Dashboard để đăng ký."
                                    : "Chưa có thiết bị nào được đăng ký. Vui lòng liên hệ admin.";
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show());
                            return;
                        }

                        // Có thiết bị active → check 3 kênh còn lại
                        checkDeviceChannels();
                    }
                    @Override
                    public void onFailure(retrofit2.Call<List<AppModel.Device>> call, Throwable t) {
                        if (!isAdded()) return;
                        // Mất mạng / server down → tất cả disconnected
                        cs.setServer(AppCore.ConnectionState.Status.DISCONNECTED);
                        cs.setBle(AppCore.ConnectionState.Status.DISCONNECTED);
                        cs.setMqtt(AppCore.ConnectionState.Status.DISCONNECTED);
                        cs.setEsp(AppCore.ConnectionState.Status.DISCONNECTED);
                        cs.setChecking(false);
                    }
                });
    }

    /** Check MQTT broker, BLE, và ESP32 vật lý — chỉ chạy khi đã xác nhận có thiết bị. */
    @SuppressLint("MissingPermission")
    private void checkDeviceChannels() {
        AppCore.ConnectionState cs = AppCore.ConnectionState.getInstance();

        // 1. MQTT broker
        if (AppNetwork.MqttHelper.getInstance().isConnected()) {
            cs.setMqtt(AppCore.ConnectionState.Status.CONNECTED);
        } else {
            cs.setMqtt(AppCore.ConnectionState.Status.CONNECTING);
            AppNetwork.MqttHelper.getInstance().connect();
        }

        // 2. BLE — chỉ đọc trạng thái, KHÔNG scan/connect
        if (AppNetwork.BleHelper.getInstance(requireContext()).isConnected()) {
            cs.setBle(AppCore.ConnectionState.Status.CONNECTED);
        } else {
            cs.setBle(AppCore.ConnectionState.Status.DISCONNECTED);
        }

        // 3. ESP32 — gửi STATUS_CHECK, chờ reply
        cs.setEsp(AppCore.ConnectionState.Status.CONNECTING);
        String cmd = "{\"cmd\":\"STATUS_CHECK\"}";
        AppNetwork.MqttHelper.getInstance().publishCommand(cmd);
        AppNetwork.BleHelper ble = AppNetwork.BleHelper.getInstance(requireContext());
        if (ble.isConnected()) {
            try { ble.writeCommand(cmd); } catch (Exception ignored) {}
        }

        final long sensorTsBefore = cs.lastSensorCheckMs;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (cs.lastSensorCheckMs <= sensorTsBefore) {
                cs.setEsp(AppCore.ConnectionState.Status.DISCONNECTED);
            }
            cs.setChecking(false);
        }, 5000);
    }

    // =========================================================
    //  Render card
    // =========================================================
    private void renderCard(View dot, TextView tvState, TextView tvTime,
                            AppCore.ConnectionState.Status state, long sinceMs) {
        if (!isAdded() || state == null) return;
        int dotColor; String stateText; int stateColor; String timePrefix;
        switch (state) {
            case CONNECTED:
                dotColor = 0xFF00C853; stateText = "Đã kết nối";
                stateColor = 0xFF00C853; timePrefix = "Kết nối lúc: "; break;
            case CONNECTING:
                dotColor = 0xFFFFD600; stateText = "Đang kết nối...";
                stateColor = 0xFFFFB300; timePrefix = "Bắt đầu lúc: "; break;
            case ERROR:
                dotColor = 0xFFFF1744; stateText = "Lỗi kết nối";
                stateColor = 0xFFFF1744; timePrefix = "Lỗi từ: "; break;
            case DISCONNECTED:
                dotColor = 0xFF9E9E9E; stateText = "Chưa kết nối";
                stateColor = 0xFF9E9E9E; timePrefix = "Mất kết nối lúc: "; break;
            default: // UNKNOWN
                dotColor = 0xFFBDBDBD; stateText = "Chưa kiểm tra";
                stateColor = 0xFFBDBDBD; timePrefix = ""; break;
        }
        final String timeText = sinceMs > 0 ? timePrefix + sdf.format(new Date(sinceMs)) : "–";
        final int dc = dotColor, sc = stateColor;
        final String st = stateText;
        requireActivity().runOnUiThread(() -> {
            dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dc));
            tvState.setText(st);
            tvState.setTextColor(sc);
            tvTime.setText(timeText);
        });
    }

    private void updateSensorRow() {
        if (!isAdded() || tvEspSensors == null) return;
        AppCore.ConnectionState cs = AppCore.ConnectionState.getInstance();
        String max = cs.sensorMax.getValue();
        String mlx = cs.sensorMlx.getValue();
        if (max == null) max = "--";
        if (mlx == null) mlx = "--";

        String maxIcon = "OK".equals(max) ? "✓" : ("ERR".equals(max) ? "✗" : "–");
        String mlxIcon = "OK".equals(mlx) ? "✓" : ("ERR".equals(mlx) ? "✗" : "–");
        int maxColor = "OK".equals(max) ? 0xFF00C853 : ("ERR".equals(max) ? 0xFFFF1744 : 0xFF9E9E9E);
        int mlxColor = "OK".equals(mlx) ? 0xFF00C853 : ("ERR".equals(mlx) ? 0xFFFF1744 : 0xFF9E9E9E);

        String text = "Cảm biến: MAX30102 " + maxIcon + "  |  GY-906 " + mlxIcon;
        android.text.SpannableString ss = new android.text.SpannableString(text);
        int idxMax = text.indexOf("MAX30102");
        int idxMlx = text.indexOf("GY-906");
        if (idxMax >= 0) {
            ss.setSpan(new android.text.style.ForegroundColorSpan(maxColor),
                    idxMax, idxMax + "MAX30102 ".length() + maxIcon.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (idxMlx >= 0) {
            ss.setSpan(new android.text.style.ForegroundColorSpan(mlxColor),
                    idxMlx, idxMlx + "GY-906 ".length() + mlxIcon.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        final android.text.SpannableString finalSs = ss;
        requireActivity().runOnUiThread(() -> {
            tvEspSensors.setText(finalSs);
            tvEspSensors.setVisibility(View.VISIBLE);
        });
    }

    /**
     * Hiện/ẩn nút theo role + trạng thái BLE:
     *
     * Admin:
     *   - BLE CONNECTED/CONNECTING → hiện "Ngắt & Unpair" (đỏ), ẩn "Quét & Kết nối"
     *   - BLE DISCONNECTED/ERROR/UNKNOWN → hiện "Quét & Kết nối" (xanh), ẩn "Ngắt & Unpair"
     *
     * Guest:
     *   - LUÔN chỉ hiện "Quét & Kết nối", KHÔNG bao giờ hiện "Ngắt & Unpair"
     *   - Nút "Quét & Kết nối" luôn visible (enabled/disabled tùy isScanning)
     */
    private void updateBleButtons(AppCore.ConnectionState.Status bleStatus) {
        if (!isAdded() || bleStatus == null) return;
        String role = AppCore.AppHelper.getInstance(requireContext()).getRole();
        boolean isAdmin = "admin".equals(role);

        requireActivity().runOnUiThread(() -> {
            if (isAdmin) {
                switch (bleStatus) {
                    case CONNECTED:
                    case CONNECTING:
                        btnUnpair.setVisibility(View.VISIBLE);
                        btnScan.setVisibility(View.GONE);
                        break;
                    default: // DISCONNECTED / ERROR / UNKNOWN
                        btnUnpair.setVisibility(View.GONE);
                        btnScan.setVisibility(View.VISIBLE);
                        btnScan.setEnabled(!isScanning);
                        break;
                }
            } else {
                // Guest: chỉ nút Quét & Kết nối, không bao giờ thấy Unpair
                btnUnpair.setVisibility(View.GONE);
                btnScan.setVisibility(View.VISIBLE);
                btnScan.setEnabled(!isScanning);
            }
        });
    }

    // =========================================================
    //  Unpair flow — CHỈ dành cho admin (được bảo vệ bởi updateBleButtons)
    // =========================================================
    @SuppressLint("MissingPermission")
    private void showUnpairConfirmDialog() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Unpair thiết bị?")
                .setMessage("Ngắt kết nối và xóa ghép đôi với ESP32_H1.\n\n" +
                        "Dữ liệu trên server vẫn được giữ nguyên.\n" +
                        "Bạn có thể kết nối lại bất cứ lúc nào.")
                .setPositiveButton("Unpair", (d, w) -> doUnpair())
                .setNegativeButton("Hủy", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void doUnpair() {
        if (!isAdded()) return;
        tvBleActionStatus.setText("Đang ngắt kết nối & xóa đăng ký...");
        tvBleActionStatus.setTextColor(0xFF2196F3);
        tvBleActionStatus.setVisibility(View.VISIBLE);

        AppCore.AppHelper helper = AppCore.AppHelper.getInstance(requireContext());
        String currentDeviceId = helper.getDeviceId();

        // ── B1: Ngắt BLE + removeBond ngay (local) ──
        boolean bondOk = AppNetwork.BleHelper.getInstance(requireContext()).unpairDevice();

        // ── B2: Clear local cache NGAY để service.startBleIfNeeded
        //       không reconnect ngầm vì còn thấy deviceId. ──
        helper.saveDeviceId("Chưa kết nối");
        AppCore.ConnectionState.getInstance()
                .setBle(AppCore.ConnectionState.Status.DISCONNECTED);

        // ── B3: Gọi server mark active=FALSE ──
        // Nếu bỏ qua bước này thì guest vẫn thấy thiết bị qua devices_my.php
        // và có thể tự kết nối — đây là bug "paired ảo".
        if (currentDeviceId != null && !currentDeviceId.isEmpty()
                && !"Chưa kết nối".equals(currentDeviceId)) {
            AppNetwork.RetrofitHelper.getInstance().unregisterDevice(currentDeviceId)
                    .enqueue(new retrofit2.Callback<okhttp3.ResponseBody>() {
                        @Override
                        public void onResponse(retrofit2.Call<okhttp3.ResponseBody> call,
                                               retrofit2.Response<okhttp3.ResponseBody> response) {
                            if (!isAdded()) return;
                            // Đọc body để biết server trả về gì khi fail
                            String bodySnippet = "";
                            try {
                                if (response.isSuccessful() && response.body() != null) {
                                    bodySnippet = response.body().string();
                                } else if (response.errorBody() != null) {
                                    bodySnippet = response.errorBody().string();
                                }
                            } catch (Exception ignored) {}
                            final String body = bodySnippet.length() > 200
                                    ? bodySnippet.substring(0, 200) : bodySnippet;
                            final int code = response.code();
                            android.util.Log.d("Unpair",
                                    "unregisterDevice → HTTP " + code + " body=" + body);

                            requireActivity().runOnUiThread(() -> {
                                if (response.isSuccessful()) {
                                    tvBleActionStatus.setText(bondOk
                                            ? "Đã unpair và xóa đăng ký trên server."
                                            : "Đã xóa đăng ký (bond OS thất bại nhưng OK).");
                                    tvBleActionStatus.setTextColor(0xFF00C853);
                                    Toast.makeText(getContext(),
                                            "Đã unpair " + currentDeviceId,
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    String hint;
                                    switch (code) {
                                        case 404: hint = " — file PHP chưa lên server?"; break;
                                        case 405: hint = " — server không cho DELETE"; break;
                                        case 401: hint = " — token hết hạn, login lại"; break;
                                        case 403: hint = " — không có quyền admin"; break;
                                        default:  hint = "";
                                    }
                                    tvBleActionStatus.setText(
                                            "Đã ngắt local. Server HTTP " + code + hint
                                                    + (body.isEmpty() ? "" : "\n" + body));
                                    tvBleActionStatus.setTextColor(0xFFFF6F00);
                                }
                            });
                        }
                        @Override
                        public void onFailure(retrofit2.Call<okhttp3.ResponseBody> call, Throwable t) {
                            if (!isAdded()) return;
                            android.util.Log.e("Unpair", "unregisterDevice network fail", t);
                            requireActivity().runOnUiThread(() -> {
                                tvBleActionStatus.setText(
                                        "Đã ngắt local nhưng không gọi được server: "
                                                + t.getMessage());
                                tvBleActionStatus.setTextColor(0xFFFF6F00);
                            });
                        }
                    });
        } else {
            tvBleActionStatus.setText("Không có thiết bị để unpair.");
            tvBleActionStatus.setTextColor(0xFF9E9E9E);
        }
    }

    // =========================================================
    //  Scan & kết nối — Guard: admin phải đăng ký thiết bị trước
    // =========================================================
    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!isAdded() || isScanning) return;

        // ── Guard: kiểm tra admin đã đăng ký thiết bị chưa ──
        String registeredDeviceId = AppCore.AppHelper.getInstance(requireContext()).getDeviceId();
        boolean deviceRegistered = registeredDeviceId != null
                && !registeredDeviceId.isEmpty()
                && !"Chưa kết nối".equals(registeredDeviceId);

        if (!deviceRegistered) {
            // Admin chưa đăng ký → không cho phép quét
            String role = AppCore.AppHelper.getInstance(requireContext()).getRole();
            if ("admin".equals(role)) {
                Toast.makeText(getContext(),
                        "Vui lòng quét & đăng ký thiết bị ở tab Dashboard trước.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(),
                        "Admin chưa đăng ký thiết bị. Vui lòng liên hệ admin.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        // ── Kiểm tra quyền Bluetooth ──
        if (androidx.core.app.ActivityCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.BLUETOOTH_SCAN)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
            }, 201);
            return;
        }

        isScanning = true;
        btnScan.setEnabled(false);
        tvBleActionStatus.setText("Đang quét tìm " + registeredDeviceId + "...");
        tvBleActionStatus.setTextColor(0xFF2196F3);
        tvBleActionStatus.setVisibility(View.VISIBLE);
        AppCore.ConnectionState.getInstance()
                .setBle(AppCore.ConnectionState.Status.CONNECTING);

        AppNetwork.BleHelper ble = AppNetwork.BleHelper.getInstance(requireContext());
        ble.setListener(new AppNetwork.BleListener() {
            @Override public void onDeviceFound(android.bluetooth.BluetoothDevice device) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() ->
                        tvBleActionStatus.setText("Tìm thấy thiết bị, đang kết nối..."));
                try { ble.connect(device); }
                catch (SecurityException e) { onError("Lỗi quyền Bluetooth: " + e.getMessage()); }
            }
            @Override public void onConnected(String deviceId) {
                if (!isAdded()) return;
                isScanning = false;
                AppCore.AppHelper.getInstance(requireContext()).saveDeviceId(deviceId);
                requireActivity().runOnUiThread(() -> {
                    btnScan.setEnabled(true);
                    tvBleActionStatus.setText("✓ Đã kết nối: " + deviceId);
                    tvBleActionStatus.setTextColor(0xFF00C853);
                    Toast.makeText(getContext(),
                            "Đã kết nối " + deviceId, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String message) {
                if (!isAdded()) return;
                isScanning = false;
                AppCore.ConnectionState.getInstance()
                        .setBle(AppCore.ConnectionState.Status.ERROR);
                requireActivity().runOnUiThread(() -> {
                    btnScan.setEnabled(true);
                    tvBleActionStatus.setText("Lỗi: " + message);
                    tvBleActionStatus.setTextColor(0xFFFF1744);
                    Toast.makeText(getContext(),
                            "Không tìm thấy thiết bị.", Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onDisconnected() {
                if (!isAdded()) return;
                isScanning = false;
                requireActivity().runOnUiThread(() -> btnScan.setEnabled(true));
            }
            @Override public void onDataReceived(AppModel.SensorData data) {}
            @Override public void onStatusReceived(String code, String message) {}
            @Override public void onDeviceIdRead(String deviceId) {}
        });
        ble.scanDevices();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == 201) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    allGranted = false; break;
                }
            }
            if (allGranted) startScan();
            else Toast.makeText(getContext(),
                    "Cần cấp quyền Bluetooth để quét thiết bị", Toast.LENGTH_LONG).show();
        }
    }
}