package com.example.healthmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import java.util.*;
import retrofit2.*;
import retrofit2.Response;

/**
 * UserProfileFragment
 *
 * FIX BUG 1: doSignOut() gọi DataManager.clear() để reset LiveData,
 * ngăn user mới nhận data của user cũ qua LiveData replay.
 */
public class UserProfileFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_user_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        AppCore.AppHelper helper = AppCore.AppHelper.getInstance(requireContext());

        TextView tvUsername  = view.findViewById(R.id.tv_profile_username);
        TextView tvRole      = view.findViewById(R.id.tv_profile_role);
        EditText etOld       = view.findViewById(R.id.et_old_password);
        EditText etNew       = view.findViewById(R.id.et_new_password);
        EditText etConf      = view.findViewById(R.id.et_confirm_password);
        View     btnChange   = view.findViewById(R.id.btn_change_pass);
        View     btnLogout   = view.findViewById(R.id.btn_logout);

        tvUsername.setText("User ID: " + helper.getUserId());
        tvRole.setText("Role: " + helper.getRole());

        btnChange.setOnClickListener(v -> {
            String oldPass  = etOld.getText().toString().trim();
            String newPass  = etNew.getText().toString().trim();
            String confPass = etConf.getText().toString().trim();

            if (oldPass.isEmpty() || newPass.isEmpty() || confPass.isEmpty()) {
                Toast.makeText(getContext(), "Nhập đủ 3 ô", Toast.LENGTH_SHORT).show(); return;
            }
            if (newPass.length() < 6) {
                Toast.makeText(getContext(), "Mật khẩu mới tối thiểu 6 ký tự", Toast.LENGTH_SHORT).show(); return;
            }
            if (!newPass.equals(confPass)) {
                Toast.makeText(getContext(), "Xác nhận mật khẩu không khớp", Toast.LENGTH_SHORT).show(); return;
            }

            Map<String, String> body = new HashMap<>();
            body.put("oldPassword", oldPass);
            body.put("newPassword", newPass);
            body.put("userId", helper.getUserId());

            AppNetwork.RetrofitHelper.getInstance().changePassword(body)
                    .enqueue(new Callback<okhttp3.ResponseBody>() {
                        @Override public void onResponse(retrofit2.Call<okhttp3.ResponseBody> call,
                                                         Response<okhttp3.ResponseBody> response) {
                            if (response.isSuccessful()) {
                                Toast.makeText(getContext(), "Đổi mật khẩu thành công!",
                                        Toast.LENGTH_LONG).show();
                                doSignOut();
                            } else {
                                Toast.makeText(getContext(), "Mật khẩu cũ không đúng", Toast.LENGTH_SHORT).show();
                            }
                        }
                        @Override public void onFailure(retrofit2.Call<okhttp3.ResponseBody> call, Throwable t) {
                            Toast.makeText(getContext(), "Lỗi mạng", Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        btnLogout.setOnClickListener(v ->
                new AlertDialog.Builder(requireContext())
                        .setTitle("Đăng xuất")
                        .setMessage("Bạn có chắc muốn đăng xuất?")
                        .setPositiveButton("Đăng xuất", (d, w) -> doSignOut())
                        .setNegativeButton("Hủy", null)
                        .show()
        );
    }

    private void doSignOut() {
        if (getActivity() == null) return;
        try {
            requireActivity().stopService(
                    new Intent(requireActivity(), AppService.MonitoringService.class));
        } catch (Exception ignored) {}
        // ── FIX BUG 1: reset LiveData + dedup state TRƯỚC khi navigate ──
        AppCore.DataManager.getInstance().clear();
        AppCore.AppHelper.getInstance(requireContext()).clearAll();
        AppNetwork.RetrofitHelper.setToken("");
        startActivity(new Intent(requireActivity(), LoginActivity.class));
        requireActivity().finishAffinity();
    }
}