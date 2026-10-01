package com.example.healthmonitor;

import androidx.viewpager2.adapter.FragmentStateAdapter;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.*;
import androidx.recyclerview.widget.*;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.textfield.TextInputEditText;
import java.util.*;
import retrofit2.*;
import retrofit2.Response;

/**
 * AdminFragment.java
 * 2 Tab: Users + Dashboard (đăng ký thiết bị BLE).
 */
public class AdminFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_admin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TabLayout  tabLayout  = view.findViewById(R.id.tab_layout);
        ViewPager2 viewPager  = view.findViewById(R.id.view_pager);

        viewPager.setAdapter(new AdminPagerAdapter(requireActivity()));
        new TabLayoutMediator(tabLayout, viewPager, (tab, pos) ->
                tab.setText(pos == 0 ? "Người dùng" : "Dashboard")
        ).attach();
    }

    static class AdminPagerAdapter extends FragmentStateAdapter {
        AdminPagerAdapter(FragmentActivity fa) { super(fa); }
        @NonNull @Override
        public Fragment createFragment(int position) {
            return position == 0 ? new UsersTabFragment() : new DashboardTabFragment();
        }
        @Override public int getItemCount() { return 2; }
    }

    // =========================================================
    //  TAB 1 — Users: danh sách + thêm + xóa
    // =========================================================
    public static class UsersTabFragment extends Fragment {
        private UserAdapter adapter;
        private List<AppModel.User> userList = new ArrayList<>();

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            View root = inflater.inflate(R.layout.tab_admin_users, container, false);

            RecyclerView rv  = root.findViewById(R.id.rv_users);
            FloatingActionButton fab = root.findViewById(R.id.fab_add_user);

            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            adapter = new UserAdapter(userList, this::showDeleteDialog);
            rv.setAdapter(adapter);

            fab.setOnClickListener(v -> showAddUserSheet());
            loadUsers();
            return root;
        }

        private void loadUsers() {
            AppNetwork.RetrofitHelper.getInstance().listUsers()
                    .enqueue(new Callback<List<AppModel.User>>() {
                        @Override public void onResponse(Call<List<AppModel.User>> call,
                                                         Response<List<AppModel.User>> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                userList.clear();
                                userList.addAll(response.body());
                                if (adapter != null) adapter.notifyDataSetChanged();
                            }
                        }
                        @Override public void onFailure(Call<List<AppModel.User>> call, Throwable t) {
                            showToast("Lỗi tải danh sách: " + t.getMessage());
                        }
                    });
        }

        private void showAddUserSheet() {
            if (getContext() == null) return;
            BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
            View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_add_user, null);
            sheet.setContentView(v);

            TextInputEditText etUser = v.findViewById(R.id.et_new_username);
            TextInputEditText etPass = v.findViewById(R.id.et_new_password);
            Spinner spRole           = v.findViewById(R.id.sp_role);
            Button btnCreate         = v.findViewById(R.id.btn_create_user);

            ArrayAdapter<String> roles = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_item, new String[]{"guest", "admin"});
            roles.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spRole.setAdapter(roles);

            btnCreate.setOnClickListener(x -> {
                String username = etUser.getText() != null ? etUser.getText().toString().trim() : "";
                String password = etPass.getText() != null ? etPass.getText().toString().trim() : "";
                if (username.isEmpty() || password.isEmpty()) {
                    showToast("Nhập đủ thông tin"); return;
                }
                AppModel.User newUser = new AppModel.User();
                newUser.username     = username;
                newUser.passwordHash = password;
                newUser.role         = spRole.getSelectedItem().toString();
                newUser.status       = "active";

                AppNetwork.RetrofitHelper.getInstance().createUser(newUser)
                        .enqueue(new Callback<AppModel.User>() {
                            @Override public void onResponse(Call<AppModel.User> call,
                                                             Response<AppModel.User> response) {
                                sheet.dismiss();
                                if (response.isSuccessful()) {
                                    showToast("Tạo user thành công");
                                    loadUsers();
                                } else {
                                    showToast("Lỗi: " + response.code());
                                }
                            }
                            @Override public void onFailure(Call<AppModel.User> call, Throwable t) {
                                showToast("Lỗi mạng: " + t.getMessage());
                            }
                        });
            });
            sheet.show();
        }

        private void showDeleteDialog(AppModel.User user) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Xóa người dùng")
                    .setMessage("Xóa \"" + user.username + "\"?")
                    .setPositiveButton("Xóa", (d, w) -> {
                        AppNetwork.RetrofitHelper.getInstance().deleteUser(user.id)
                                .enqueue(new Callback<okhttp3.ResponseBody>() {
                                    @Override public void onResponse(Call<okhttp3.ResponseBody> call,
                                                                     Response<okhttp3.ResponseBody> response) {
                                        if (response.isSuccessful()) {
                                            showToast("Đã xóa");
                                            loadUsers();
                                        }
                                    }
                                    @Override public void onFailure(Call<okhttp3.ResponseBody> call, Throwable t) {
                                        showToast("Lỗi xóa: " + t.getMessage());
                                    }
                                });
                    })
                    .setNegativeButton("Hủy", null).show();
        }

        private void showToast(String msg) {
            if (getContext() != null)
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    static class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {
        interface OnLongClick { void onDelete(AppModel.User user); }
        private final List<AppModel.User> list;
        private final OnLongClick         onLongClick;

        UserAdapter(List<AppModel.User> list, OnLongClick onLongClick) {
            this.list        = list;
            this.onLongClick = onLongClick;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            AppModel.User u = list.get(pos);
            h.tvUsername.setText(u.username);
            h.tvRole.setText("[" + u.role + "]");
            h.tvStatus.setText(u.status);
            h.itemView.setOnLongClickListener(v -> { onLongClick.onDelete(u); return true; });
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvUsername, tvRole, tvStatus;
            VH(View v) {
                super(v);
                tvUsername = v.findViewById(R.id.tv_username);
                tvRole     = v.findViewById(R.id.tv_role);
                tvStatus   = v.findViewById(R.id.tv_status);
            }
        }
    }

    // =========================================================
    //  TAB 2 — Dashboard: đăng ký thiết bị BLE
    // =========================================================
    public static class DashboardTabFragment extends Fragment {

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            View root = inflater.inflate(R.layout.tab_admin_dashboard, container, false);

            TextView tvDeviceId = root.findViewById(R.id.tv_admin_device_id);
            Switch   swService  = root.findViewById(R.id.sw_service);

            AppCore.AppHelper helper = AppCore.AppHelper.getInstance(requireContext());
            // Hiện cache local trước (để không bị trắng), sau đó gọi server để
            // lấy danh sách thiết bị thực sự đang active. Server là nguồn sự thật:
            // nếu admin đã unpair (active=FALSE) thì server sẽ trả rỗng và
            // tvDeviceId hiện "--" — đúng với trạng thái thực.
            String cachedId = helper.getDeviceId();
            tvDeviceId.setText("Device: " + (cachedId != null && !cachedId.isEmpty()
                    && !"Chưa kết nối".equals(cachedId) ? cachedId : "--"));
            loadDeviceFromServer(tvDeviceId, helper);

            swService.setChecked(helper.getServiceState());

            swService.setOnCheckedChangeListener((btn, isOn) -> {
                MainActivity activity = (MainActivity) requireActivity();
                if (isOn) {
                    activity.startMonitoringService();
                    Toast.makeText(getContext(), "Service đã bật", Toast.LENGTH_SHORT).show();
                } else {
                    activity.stopMonitoringService();
                    Toast.makeText(getContext(), "Service đã tắt", Toast.LENGTH_SHORT).show();
                }
            });

            TextView btnRegister = root.findViewById(R.id.btn_register_device);
            TextView tvRegStatus = root.findViewById(R.id.tv_register_status);

            btnRegister.setOnClickListener(v -> {
                if (androidx.core.app.ActivityCompat.checkSelfPermission(requireContext(),
                        android.Manifest.permission.BLUETOOTH_SCAN)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                    }, 300);
                    return;
                }
                scanAndRegister(tvRegStatus);
            });

            return root;
        }

        private void scanAndRegister(TextView statusView) {
            statusView.setText("Đang quét BLE...");
            AppNetwork.BleHelper ble = AppNetwork.BleHelper.getInstance(requireContext());

            ble.setListener(new AppNetwork.BleListener() {
                @Override public void onDeviceFound(android.bluetooth.BluetoothDevice device) {
                    statusView.post(() -> statusView.setText("Tìm thấy, đang kết nối..."));
                    if (androidx.core.app.ActivityCompat.checkSelfPermission(requireContext(),
                            android.Manifest.permission.BLUETOOTH_CONNECT)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        ble.connect(device);
                    }
                }
                @Override public void onConnected(String deviceId) {
                    // Bug 2 fix: bỏ bước writeCommand("ID") và chờ onDeviceIdRead.
                    // Firmware hiện tại KHÔNG có handler cho lệnh "ID" → app sẽ
                    // chờ vô tận. Tên BLE đã chính là deviceId rồi (= TARGET_NAME
                    // hoặc tên thực tế nếu sau này có nhiều ESP32 khác nhau), nên
                    // dùng luôn deviceId từ callback này — register thẳng lên server.
                    requireActivity().runOnUiThread(() ->
                            statusView.setText("Đăng ký lên server: " + deviceId + "..."));
                    registerToServer(deviceId, statusView);
                }
                @Override public void onDeviceIdRead(String deviceId) {
                    // Không dùng nữa — giữ stub để không lỗi compile.
                    // Nếu sau này firmware có thêm handler "ID" trả về tên khác
                    // với tên BLE thì có thể bật lại flow này.
                }
                @Override public void onDataReceived(AppModel.SensorData data) { }
                @Override public void onStatusReceived(String code, String message) { }
                @Override public void onDisconnected() { }
                @Override public void onError(String message) {
                    requireActivity().runOnUiThread(() -> statusView.setText("Lỗi: " + message));
                }
            });

            if (androidx.core.app.ActivityCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.BLUETOOTH_SCAN)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ble.scanDevices();
            }
        }

        private void registerToServer(String deviceId, TextView statusView) {
            AppModel.Device device = new AppModel.Device();
            device.deviceId = deviceId;
            device.active   = true;

            AppNetwork.RetrofitHelper.getInstance().registerDevice(device)
                    .enqueue(new retrofit2.Callback<AppModel.Device>() {
                        @Override public void onResponse(retrofit2.Call<AppModel.Device> call,
                                                         retrofit2.Response<AppModel.Device> response) {
                            requireActivity().runOnUiThread(() -> {
                                if (response.isSuccessful()) {
                                    statusView.setText(" Đã đăng ký: " + deviceId);
                                    AppCore.AppHelper.getInstance(requireContext()).saveDeviceId(deviceId);

                                    // Cập nhật "Device:" trên dashboard
                                    View root = getView();
                                    if (root != null) {
                                        TextView tvDev = root.findViewById(R.id.tv_admin_device_id);
                                        if (tvDev != null) tvDev.setText("Device: " + deviceId);
                                    }

                                    // NGẮT BLE để guest có thể kết nối
                                    if (androidx.core.app.ActivityCompat.checkSelfPermission(requireContext(),
                                            android.Manifest.permission.BLUETOOTH_CONNECT)
                                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        AppNetwork.BleHelper.getInstance(requireContext()).disconnect();
                                    }
                                } else {
                                    statusView.setText(" Lỗi server: " + response.code());
                                }
                            });
                        }
                        @Override public void onFailure(retrofit2.Call<AppModel.Device> call, Throwable t) {
                            requireActivity().runOnUiThread(() ->
                                    statusView.setText(" Lỗi mạng: " + t.getMessage()));
                        }
                    });
        }

        /**
         * Hỏi server xem thiết bị nào đang active (admin đã đăng ký).
         * Server là single source of truth — tránh tình trạng cache local sai
         * sau khi logout/clear-data hoặc sau khi unpair từ máy khác.
         *
         *  - Có thiết bị → hiển thị + lưu vào cache cho lần sau.
         *  - Rỗng       → hiển thị "--" và set cache về "Chưa kết nối"
         *                 (để các nút Quét trên ConnectionStatus biết là chưa đăng ký).
         */
        private void loadDeviceFromServer(TextView tvDeviceId, AppCore.AppHelper helper) {
            AppNetwork.RetrofitHelper.getInstance().getMyDevices()
                    .enqueue(new retrofit2.Callback<java.util.List<AppModel.Device>>() {
                        @Override
                        public void onResponse(retrofit2.Call<java.util.List<AppModel.Device>> call,
                                               retrofit2.Response<java.util.List<AppModel.Device>> response) {
                            if (!isAdded()) return;
                            if (response.isSuccessful() && response.body() != null
                                    && !response.body().isEmpty()) {
                                AppModel.Device first = response.body().get(0);
                                String devId = (first.deviceId != null && !first.deviceId.isEmpty())
                                        ? first.deviceId : "";
                                if (!devId.isEmpty()) {
                                    helper.saveDeviceId(devId);
                                    requireActivity().runOnUiThread(() ->
                                            tvDeviceId.setText("Device: " + devId));
                                }
                            } else {
                                // Server không có thiết bị active → đồng bộ cache về trạng thái chưa
                                // đăng ký để guard "Quét & Kết nối" hoạt động đúng.
                                helper.saveDeviceId("Chưa kết nối");
                                requireActivity().runOnUiThread(() ->
                                        tvDeviceId.setText("Device: --"));
                            }
                        }
                        @Override
                        public void onFailure(retrofit2.Call<java.util.List<AppModel.Device>> c, Throwable t) {
                            // Mất mạng → giữ nguyên cache (đã set ở onCreateView)
                        }
                    });
        }
    }
}