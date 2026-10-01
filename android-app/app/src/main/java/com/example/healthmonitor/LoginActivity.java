package com.example.healthmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputEditText;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * FILE 6: LoginActivity.java
 * Màn hình đầu tiên. Kiểm tra token → bỏ qua login nếu còn session.
 */
public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etUsername, etPassword;
    private com.google.android.material.button.MaterialButton btnLogin;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Kiểm tra session trước — nếu còn token → vào thẳng
        AppCore.AppHelper helper = AppCore.AppHelper.getInstance(this);
        if (helper.isSessionValid()) {
            // Khôi phục token vào Retrofit
            AppNetwork.RetrofitHelper.setToken(helper.getToken());
            goToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        etUsername  = findViewById(R.id.et_username);
        etPassword  = findViewById(R.id.et_password);
        btnLogin    = findViewById(R.id.btn_login);
        progressBar = findViewById(R.id.progress_bar);

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String username = getText(etUsername);
        String password = getText(etPassword);

        if (username.isEmpty()) {
            etUsername.setError("Nhập tên đăng nhập");
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("Nhập mật khẩu");
            return;
        }

        // Ẩn button, hiện loading
        btnLogin.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);

        AppNetwork.RetrofitHelper.getInstance()
                .login(new AppModel.LoginRequest(username, password))
                .enqueue(new Callback<AppModel.LoginResponse>() {
                    @Override
                    public void onResponse(Call<AppModel.LoginResponse> call,
                                           Response<AppModel.LoginResponse> response) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            btnLogin.setEnabled(true);
                        });

                        if (response.isSuccessful() && response.body() != null) {
                            AppModel.LoginResponse body = response.body();
                            AppCore.AppHelper h = AppCore.AppHelper.getInstance(LoginActivity.this);
                            h.saveToken(body.token);
                            h.saveRole(body.role);
                            h.saveUserId(String.valueOf(body.userId));
                            AppNetwork.RetrofitHelper.setToken(body.token);
                            goToMain();
                        } else if (response.code() == 401) {
                            showToast("Sai tên đăng nhập hoặc mật khẩu");
                        } else {
                            showToast("Lỗi server: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<AppModel.LoginResponse> call, Throwable t) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            btnLogin.setEnabled(true);
                            showToast("Không kết nối được server. Kiểm tra WiFi.");
                        });
                    }
                });
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private String getText(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}