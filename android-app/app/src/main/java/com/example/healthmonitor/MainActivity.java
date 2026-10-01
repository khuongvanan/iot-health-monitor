package com.example.healthmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.work.*;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.util.concurrent.TimeUnit;

/**
 * FILE 7: MainActivity.java
 * Khung chứa Fragment. Phân luồng admin/user.
 *
 * FIX BUG 1: signOut() gọi DataManager.clear() để reset LiveData,
 * ngăn user mới nhận data của user cũ qua LiveData replay.
 */
public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;
    private String role;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        bottomNav = findViewById(R.id.bottom_nav);

        AppCore.AppHelper helper = AppCore.AppHelper.getInstance(this);
        role = helper.getRole();

        // Cấu hình menu và Fragment đầu theo role
        if ("admin".equals(role)) {
            bottomNav.inflateMenu(R.menu.menu_admin);
            loadFragment(new AdminFragment());
            if (getSupportActionBar() != null)
                getSupportActionBar().setTitle("Admin Panel");
        } else {
            bottomNav.inflateMenu(R.menu.menu_user);
            loadFragment(new UserDashboardFragment());
            if (getSupportActionBar() != null)
                getSupportActionBar().setTitle("Dashboard");
        }

        // Xử lý chọn tab
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if ("admin".equals(role)) {
                if (id == R.id.nav_manage)   { loadFragment(new AdminFragment()); return true; }
                if (id == R.id.nav_chart)    { loadFragment(new AdminTrackingFragment()); return true; }
                if (id == R.id.nav_medicine) { loadFragment(new AdminMedicineFragment()); return true; }
            } else {
                if (id == R.id.nav_dashboard){ loadFragment(new UserDashboardFragment()); return true; }
                if (id == R.id.nav_history)  { loadFragment(new UserTrackingFragment()); return true; }
                if (id == R.id.nav_profile)  { loadFragment(new UserProfileFragment()); return true; }
            }
            return false;
        });

        // Bật service nếu trạng thái đang ON
        if (helper.getServiceState()) startMonitoringService();

        // Đăng ký SyncWorker chạy định kỳ mỗi 15 phút
        registerSyncWorker();
    }

    private void registerSyncWorker() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest syncWork = new PeriodicWorkRequest.Builder(
                AppService.SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "HealthSyncWork", ExistingPeriodicWorkPolicy.KEEP, syncWork);
    }

    public void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main_options, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_signout) {
            new AlertDialog.Builder(this)
                    .setTitle("Đăng xuất")
                    .setMessage("Bạn có chắc muốn đăng xuất không?")
                    .setPositiveButton("Đăng xuất", (d, w) -> signOut())
                    .setNegativeButton("Hủy", null)
                    .show();
            return true;
        }

        // THÊM MỚI: mở màn hình trạng thái kết nối
        if (id == R.id.action_connection_status) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new ConnectionStatusFragment())
                    .addToBackStack("connection_status")  // back button quay lại fragment trước
                    .commit();
            if (getSupportActionBar() != null)
                getSupportActionBar().setTitle("Trạng thái kết nối");
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void signOut() {
        stopMonitoringService();
        // ── FIX BUG 1: reset LiveData + dedup state TRƯỚC khi navigate ──
        // Nếu không gọi, user kế tiếp sẽ nhận SensorData của user vừa logout
        // qua MutableLiveData replay.
        AppCore.DataManager.getInstance().clear();
        AppCore.AppHelper.getInstance(this).clearAll();
        AppNetwork.RetrofitHelper.setToken("");
        startActivity(new Intent(this, LoginActivity.class));
        finishAffinity();
    }

    public void startMonitoringService() {
        Intent intent = new Intent(this, AppService.MonitoringService.class);
        startForegroundService(intent);
        AppCore.AppHelper.getInstance(this).saveServiceState(true);
    }

    public void stopMonitoringService() {
        stopService(new Intent(this, AppService.MonitoringService.class));
        AppCore.AppHelper.getInstance(this).saveServiceState(false);
    }
}