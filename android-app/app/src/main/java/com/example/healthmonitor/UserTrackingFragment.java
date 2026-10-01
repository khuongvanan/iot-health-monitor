package com.example.healthmonitor;

import android.app.NotificationManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.*;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.*;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import java.text.SimpleDateFormat;
import java.util.*;
import retrofit2.*;
import retrofit2.Response;

/**
 * UserTrackingFragment  (PATCHED)
 *
 * THAY ĐỔI so với bản ZIP gốc:
 *   - MyMedicineFragment.loadMedicines:
 *       + Sau khi load từ server thành công → sync xuống local Room
 *       + Khi network FAIL → load từ local Room làm fallback (offline mode)
 *   - Tab Biểu đồ (MyChartFragment) GIỮ NGUYÊN HOÀN TOÀN.
 */
public class UserTrackingFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_admin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TabLayout  tabLayout = view.findViewById(R.id.tab_layout);
        ViewPager2 viewPager = view.findViewById(R.id.view_pager);
        viewPager.setAdapter(new UserTrackingPager(requireActivity()));
        new TabLayoutMediator(tabLayout, viewPager, (tab, pos) ->
                tab.setText(pos == 0 ? "Biểu đồ" : "Lịch thuốc")
        ).attach();
        tabLayout.setTabTextColors(0xAAFFFFFF, 0xFFFFFFFF);
        tabLayout.setSelectedTabIndicatorColor(0xFFFFFFFF);
    }

    static class UserTrackingPager extends FragmentStateAdapter {
        UserTrackingPager(FragmentActivity fa) { super(fa); }
        @NonNull @Override
        public Fragment createFragment(int pos) {
            return pos == 0 ? new MyChartFragment() : new MyMedicineFragment();
        }
        @Override public int getItemCount() { return 2; }
    }

    // ============================================================
    //  Tab BIỂU ĐỒ — GIỮ NGUYÊN HOÀN TOÀN
    // ============================================================
    public static class MyChartFragment extends Fragment {

        private LineChart chartHR, chartSpo2, chartTemp;
        private String currentRange = "today";
        private int userId;

        private final List<AppModel.SensorData> chartData = new ArrayList<>();
        private final List<Long> sortedTimestamps = new ArrayList<>();
        private long lastSeenTimestamp = 0L;

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle b) {
            return i.inflate(R.layout.fragment_admin_tracking, c, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            chartHR   = view.findViewById(R.id.chart_hr);
            chartSpo2 = view.findViewById(R.id.chart_spo2);
            chartTemp = view.findViewById(R.id.chart_temp);

            View spinnerUser = view.findViewById(R.id.spinner_user);
            if (spinnerUser != null) spinnerUser.setVisibility(View.GONE);

            setupChart(chartHR,   "Nhịp Tim (bpm)");
            setupChart(chartSpo2, "SpO₂ (%)");
            setupChart(chartTemp, "Nhiệt Độ (°C)");

            userId = AppCore.AppHelper.getInstance(requireContext()).getUserIdInt();

            AppCore.DataManager.getInstance().liveData.observe(getViewLifecycleOwner(), newData -> {
                if (newData == null) return;
                if (newData.heartRate <= 0 && newData.spo2 <= 0 && newData.temperature <= 0) return;
                long ts = newData.timestamp > 0 ? newData.timestamp : System.currentTimeMillis();
                if (ts <= lastSeenTimestamp) return;
                lastSeenTimestamp = ts;

                AppModel.SensorData clone = new AppModel.SensorData();
                clone.heartRate   = newData.heartRate;
                clone.spo2        = newData.spo2;
                clone.temperature = newData.temperature;
                clone.deviceId    = newData.deviceId;
                clone.userId      = newData.userId;
                clone.source      = newData.source;
                clone.timestamp   = ts;
                chartData.add(clone);
                refreshCharts();
            });

            RadioGroup rg = view.findViewById(R.id.rg_range);
            if (rg != null) rg.setOnCheckedChangeListener((g, id) -> {
                if (id == R.id.rb_week) currentRange = "week";
                else if (id == R.id.rb_month) currentRange = "month";
                else currentRange = "today";
                fetchHistory();
            });
        }

        @Override
        public void onResume() {
            super.onResume();
            fetchHistory();
        }

        private void fetchHistory() {
            setNoDataText("Đang tải dữ liệu...");
            AppNetwork.RetrofitHelper.getInstance().getSensorHistory(userId, currentRange)
                    .enqueue(new Callback<List<AppModel.SensorData>>() {
                        @Override
                        public void onResponse(Call<List<AppModel.SensorData>> call,
                                               Response<List<AppModel.SensorData>> response) {
                            if (!isAdded()) return;
                            if (response.isSuccessful() && response.body() != null) {
                                List<AppModel.SensorData> fetched = response.body();
                                chartData.clear();
                                chartData.addAll(fetched);
                                long max = 0L;
                                for (AppModel.SensorData s : fetched) {
                                    if (s.timestamp > max) max = s.timestamp;
                                }
                                if (max > 0) lastSeenTimestamp = max;
                                refreshCharts();
                                if (fetched.isEmpty()) {
                                    setNoDataText("Chưa có dữ liệu đo trong khoảng này");
                                }
                            } else {
                                String msg = (response.code() == 401)
                                        ? "Phiên đăng nhập hết hạn, vui lòng đăng xuất rồi đăng nhập lại"
                                        : "Lỗi server: " + response.code();
                                setNoDataText("Không tải được dữ liệu");
                                requireActivity().runOnUiThread(() ->
                                        Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show());
                            }
                        }
                        @Override
                        public void onFailure(Call<List<AppModel.SensorData>> c, Throwable t) {
                            if (!isAdded()) return;
                            setNoDataText("Không kết nối được server");
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(getContext(),
                                            "Lỗi kết nối: " + t.getMessage(),
                                            Toast.LENGTH_LONG).show());
                        }
                    });
        }

        private void refreshCharts() {
            List<AppModel.SensorData> sorted = new ArrayList<>(chartData);
            Collections.sort(sorted, (a, b) -> Long.compare(a.timestamp, b.timestamp));
            sortedTimestamps.clear();
            for (AppModel.SensorData d : sorted) sortedTimestamps.add(d.timestamp);
            plot(chartHR,   sorted, "heartRate");
            plot(chartSpo2, sorted, "spo2");
            plot(chartTemp, sorted, "temperature");
        }

        private void plot(LineChart chart, List<AppModel.SensorData> data, String field) {
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                float v = 0;
                switch (field) {
                    case "heartRate":   v = data.get(i).heartRate;   break;
                    case "spo2":        v = data.get(i).spo2;        break;
                    case "temperature": v = data.get(i).temperature; break;
                }
                if (v > 0) entries.add(new Entry(i, v));
            }
            String label; int color;
            switch (field) {
                case "heartRate":   label = "Nhịp Tim";  color = 0xFFE53935; break;
                case "spo2":        label = "SpO₂";      color = 0xFF1565C0; break;
                default:            label = "Nhiệt Độ";  color = 0xFFFF6F00; break;
            }
            if (entries.isEmpty()) {
                chart.clear(); chart.invalidate(); return;
            }
            LineDataSet ds = new LineDataSet(entries, label);
            ds.setMode(LineDataSet.Mode.LINEAR);
            ds.setLineWidth(2f);
            ds.setColor(color);
            ds.setDrawCircles(entries.size() <= 60);
            ds.setCircleRadius(3.5f);
            ds.setCircleColor(color);
            ds.setDrawValues(false);
            ds.setDrawFilled(false);

            String timeFmt = "week".equals(currentRange) || "month".equals(currentRange)
                    ? "dd/MM HH:mm" : "HH:mm";
            SimpleDateFormat sdf = new SimpleDateFormat(timeFmt, Locale.getDefault());
            final List<Long> tsCopy = new ArrayList<>(sortedTimestamps);

            XAxis xAxis = chart.getXAxis();
            xAxis.setValueFormatter(new ValueFormatter() {
                @Override public String getFormattedValue(float value) {
                    int idx = Math.round(value);
                    if (idx >= 0 && idx < tsCopy.size()) {
                        return sdf.format(new Date(tsCopy.get(idx)));
                    }
                    return "";
                }
            });
            xAxis.setGranularity(1f);
            xAxis.setLabelCount(Math.min(entries.size(), 5), false);
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setLabelRotationAngle(-30f);

            chart.setData(new LineData(ds));
            chart.animateX(300);
            chart.invalidate();
        }

        private void setupChart(LineChart c, String lbl) {
            Description d = new Description();
            d.setText(lbl);
            d.setTextSize(11f);
            c.setDescription(d);
            c.getAxisRight().setEnabled(false);
            c.setTouchEnabled(true);
            c.setScaleEnabled(true);
            c.setPinchZoom(true);
            c.setNoDataText("Đang tải dữ liệu...");
            c.setNoDataTextColor(0xFF888888);
        }

        private void setNoDataText(String msg) {
            if (chartHR   != null) { chartHR.setNoDataText(msg);   chartHR.invalidate(); }
            if (chartSpo2 != null) { chartSpo2.setNoDataText(msg); chartSpo2.invalidate(); }
            if (chartTemp != null) { chartTemp.setNoDataText(msg); chartTemp.invalidate(); }
        }
    }

    // ============================================================
    //  Tab LỊCH THUỐC — PATCHED (sync local + offline fallback)
    // ============================================================
    public static class MyMedicineFragment extends Fragment {

        private LinearLayout llContainer;
        private List<AppModel.MedicineSchedule> list = new ArrayList<>();

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            ScrollView sv = new ScrollView(requireContext());
            llContainer = new LinearLayout(requireContext());
            llContainer.setOrientation(LinearLayout.VERTICAL);
            int pad = dpToPx(8);
            llContainer.setPadding(pad, pad, pad, pad);
            sv.addView(llContainer);
            return sv;
        }

        @Override
        public void onResume() {
            super.onResume();
            loadMedicines();
        }

        // ── Load lịch thuốc: server trước, fallback local nếu fail ─────
        private void loadMedicines() {
            int userId = AppCore.AppHelper.getInstance(requireContext()).getUserIdInt();
            AppNetwork.RetrofitHelper.getInstance().getMedicineList(userId)
                    .enqueue(new Callback<List<AppModel.MedicineSchedule>>() {
                        @Override
                        public void onResponse(Call<List<AppModel.MedicineSchedule>> call,
                                               Response<List<AppModel.MedicineSchedule>> response) {
                            if (!isAdded()) return;
                            if (response.isSuccessful() && response.body() != null) {
                                list.clear();
                                list.addAll(response.body());
                                requireActivity().runOnUiThread(() -> renderList());

                                // ★★ MỚI: sync xuống local Room → alarm tự khôi phục được khi offline/reboot
                                MedicineLocalSync.syncToLocal(
                                        requireContext().getApplicationContext(),
                                        userId,
                                        response.body());
                            } else {
                                // Server trả về lỗi (vd 500) → fallback local
                                loadFromLocal(userId);
                            }
                        }

                        @Override
                        public void onFailure(Call<List<AppModel.MedicineSchedule>> c, Throwable t) {
                            if (!isAdded()) return;
                            // ★★ MỚI: mạng fail → load list từ local Room, UI vẫn render được
                            loadFromLocal(userId);
                        }
                    });
        }

        /** Fallback offline: lấy list từ local DB và render lên UI. */
        private void loadFromLocal(int userId) {
            if (!isAdded()) return;
            new Thread(() -> {
                List<AppModel.MedicineSchedule> localList =
                        MedicineLocalSync.loadLocalSync(requireContext(), userId);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    list.clear();
                    list.addAll(localList);
                    renderList();
                    if (!localList.isEmpty()) {
                        Toast.makeText(getContext(),
                                "Offline — hiển thị lịch đã lưu",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        }

        private void renderList() {
            if (!isAdded() || llContainer == null) return;
            llContainer.removeAllViews();

            if (list.isEmpty()) {
                TextView empty = new TextView(requireContext());
                empty.setText("Chưa có lịch uống thuốc nào");
                empty.setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(8));
                empty.setTextSize(15);
                llContainer.addView(empty);
                return;
            }

            for (AppModel.MedicineSchedule s : list) {
                llContainer.addView(buildMedicineCard(s));
            }
        }

        private View buildMedicineCard(AppModel.MedicineSchedule s) {
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = dpToPx(12);
            card.setPadding(pad, pad, pad, pad);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, dpToPx(12));
            card.setLayoutParams(cardLp);
            card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);

            TextView tvHeader = new TextView(requireContext());
            tvHeader.setText(s.scheduleTime + "  💊  " + s.medicineName);
            tvHeader.setTextSize(16);
            tvHeader.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            card.addView(tvHeader);

            if (s.note != null && !s.note.isEmpty()) {
                TextView tvNote = new TextView(requireContext());
                tvNote.setText(s.note);
                tvNote.setTextSize(13);
                tvNote.setTextColor(0xFF757575);
                card.addView(tvNote);
            }

            TextView tvRepeat = new TextView(requireContext());
            tvRepeat.setText(s.repeatDaily ? "🔁 Lặp hàng ngày" : "1️⃣ Chỉ một lần");
            tvRepeat.setTextSize(12);
            tvRepeat.setTextColor(s.repeatDaily ? 0xFF1565C0 : 0xFF6A1B9A);
            tvRepeat.setPadding(0, dpToPx(4), 0, 0);
            card.addView(tvRepeat);

            if (s.lastActionAt != null && s.lastAction != null) {
                TextView tvLast = new TextView(requireContext());
                String icon = "taken".equals(s.lastAction) ? " Đã uống" : "⏭ Đã bỏ qua";
                tvLast.setText(icon + " lúc " + s.lastActionAt);
                tvLast.setTextSize(12);
                tvLast.setTextColor("taken".equals(s.lastAction) ? 0xFF00C853 : 0xFFFF6F00);
                tvLast.setPadding(0, dpToPx(2), 0, 0);
                card.addView(tvLast);
            }

            if (AppService.AlarmHelper.isPendingConfirm(requireContext(), s.id)) {
                card.addView(buildConfirmRow(s));
            }

            return card;
        }

        private View buildConfirmRow(AppModel.MedicineSchedule s) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dpToPx(10), 0, 0);

            Button btnTaken = new Button(requireContext());
            btnTaken.setText(" Đã uống");
            btnTaken.setTextColor(0xFFFFFFFF);
            btnTaken.setBackgroundColor(0xFF00C853);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, dpToPx(8), 0);
            btnTaken.setLayoutParams(lp);
            btnTaken.setOnClickListener(v -> confirmAction(s, "taken"));

            Button btnSkipped = new Button(requireContext());
            btnSkipped.setText("⏭ Bỏ qua");
            btnSkipped.setTextColor(0xFFFFFFFF);
            btnSkipped.setBackgroundColor(0xFFFF6F00);
            btnSkipped.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            btnSkipped.setOnClickListener(v -> confirmAction(s, "skipped"));

            row.addView(btnTaken);
            row.addView(btnSkipped);
            return row;
        }

        private void confirmAction(AppModel.MedicineSchedule s, String action) {
            if (!isAdded()) return;

            // 1. Cancel snooze
            AppService.AlarmHelper.cancelAllSnooze(requireContext(), s.id);
            // 2. Xoá cờ pending
            AppService.AlarmHelper.setPendingConfirm(requireContext(), s.id, false);
            // 3. Dismiss notification
            NotificationManager nm = (NotificationManager)
                    requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            nm.cancel(s.id);

            // 4. Đặt LUÔN alarm ngày mai TRƯỚC khi gọi server — đảm bảo dù
            //    POST có fail (offline) thì alarm hôm sau vẫn được đặt.
            if (s.repeatDaily) {
                AppService.AlarmHelper.setDailyAlarm(requireContext(), s);
            }

            // 5. POST confirm
            AppNetwork.RetrofitHelper.getInstance()
                    .confirmMedicine(new AppModel.ConfirmRequest(s.id, action))
                    .enqueue(new Callback<okhttp3.ResponseBody>() {
                        @Override
                        public void onResponse(Call<okhttp3.ResponseBody> call,
                                               Response<okhttp3.ResponseBody> response) {
                            if (!isAdded()) return;
                            String msg = "taken".equals(action)
                                    ? "Đã ghi nhận uống thuốc!"
                                    : "⏭ Đã bỏ qua lịch này";
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show());
                            loadMedicines();
                        }
                        @Override
                        public void onFailure(Call<okhttp3.ResponseBody> call, Throwable t) {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(getContext(),
                                            "Mạng lỗi — confirm chưa lên server, alarm hôm sau vẫn được đặt",
                                            Toast.LENGTH_LONG).show());
                        }
                    });
        }

        private int dpToPx(int dp) {
            float density = requireContext().getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }
    }
}