package com.example.healthmonitor;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.data.*;
import java.util.*;
import retrofit2.*;
import retrofit2.Response;

/**
 * FILE 9: AdminTrackingFragment.java
 * Biểu đồ lịch sử 3 chỉ số — chọn user + chọn range.
 */
public class AdminTrackingFragment extends Fragment {

    private Spinner  spinnerUser;
    private RadioGroup rgRange;
    private LineChart chartHR, chartSpo2, chartTemp;
    private List<AppModel.User> userList = new ArrayList<>();
    private int selectedUserId = -1;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_admin_tracking, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spinnerUser = view.findViewById(R.id.spinner_user);
        rgRange     = view.findViewById(R.id.rg_range);
        chartHR     = view.findViewById(R.id.chart_hr);
        chartSpo2   = view.findViewById(R.id.chart_spo2);
        chartTemp   = view.findViewById(R.id.chart_temp);

        setupChart(chartHR,   "Nhịp Tim (bpm)");
        setupChart(chartSpo2, "SpO₂ (%)");
        setupChart(chartTemp, "Nhiệt Độ (°C)");

        loadUsers();

        spinnerUser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < userList.size()) {
                    selectedUserId = userList.get(pos).id;
                    loadChartData(selectedUserId, getSelectedRange());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        rgRange.setOnCheckedChangeListener((group, checkedId) -> {
            if (selectedUserId != -1)
                loadChartData(selectedUserId, getSelectedRange());
        });
    }

    private String getSelectedRange() {
        int id = rgRange.getCheckedRadioButtonId();
        if (id == R.id.rb_week)  return "week";
        if (id == R.id.rb_month) return "month";
        return "today";
    }

    private void loadUsers() {
        AppNetwork.RetrofitHelper.getInstance().listUsers()
                .enqueue(new Callback<List<AppModel.User>>() {
                    @Override public void onResponse(Call<List<AppModel.User>> call,
                                                     Response<List<AppModel.User>> response) {
                        if (response.isSuccessful() && response.body() != null && getContext() != null) {
                            userList.clear();
                            userList.addAll(response.body());
                            List<String> names = new ArrayList<>();
                            for (AppModel.User u : userList) names.add(u.username);
                            ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
                                    android.R.layout.simple_spinner_item, names);
                            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerUser.setAdapter(a);
                        }
                    }
                    @Override public void onFailure(Call<List<AppModel.User>> call, Throwable t) {}
                });
    }

    private void loadChartData(int userId, String range) {
        AppNetwork.RetrofitHelper.getInstance().getSensorHistory(userId, range)
                .enqueue(new Callback<List<AppModel.SensorData>>() {
                    @Override public void onResponse(Call<List<AppModel.SensorData>> call,
                                                     Response<List<AppModel.SensorData>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            List<AppModel.SensorData> data = response.body();
                            plotChart(chartHR,   data, "heartRate");
                            plotChart(chartSpo2, data, "spo2");
                            plotChart(chartTemp, data, "temperature");
                        }
                    }
                    @Override public void onFailure(Call<List<AppModel.SensorData>> call, Throwable t) {}
                });
    }

    private void plotChart(LineChart chart, List<AppModel.SensorData> data, String field) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            float val = 0;
            AppModel.SensorData d = data.get(i);
            switch (field) {
                case "heartRate":   val = d.heartRate;   break;
                case "spo2":        val = d.spo2;         break;
                case "temperature": val = d.temperature;  break;
            }
            entries.add(new Entry(i, val));
        }
        LineDataSet dataSet = new LineDataSet(entries, field);
        dataSet.setDrawCircles(false);
        dataSet.setLineWidth(2f);
        chart.setData(new LineData(dataSet));
        chart.invalidate();
    }

    private void setupChart(LineChart chart, String label) {
        Description desc = new Description();
        desc.setText(label);
        chart.setDescription(desc);
        chart.setTouchEnabled(true);
        chart.setScaleEnabled(true);
        chart.getAxisRight().setEnabled(false);
    }
}