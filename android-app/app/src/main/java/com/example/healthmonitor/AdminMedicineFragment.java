package com.example.healthmonitor;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import java.util.*;
import retrofit2.*;
import retrofit2.Response;

/**
 * AdminMedicineFragment  (PATCHED)
 *
 * THAY ĐỔI so với bản ZIP gốc:
 *  - loadMedicines: sync xuống local Room sau khi load thành công
 *  - btnSave onResponse: upsert local + sync
 *  - deleteMedicine onResponse: xoá local
 *
 * Toàn bộ phần khác giữ nguyên 100%.
 */
public class AdminMedicineFragment extends Fragment {

    private Spinner spinnerUser;
    private MedicineAdapter adapter;
    private List<AppModel.MedicineSchedule> medList  = new ArrayList<>();
    private List<AppModel.User>             userList = new ArrayList<>();
    private int selectedUserId = -1;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_admin_medicine, container, false);

        spinnerUser = root.findViewById(R.id.spinner_user_med);
        RecyclerView rv             = root.findViewById(R.id.rv_medicine);
        FloatingActionButton fab    = root.findViewById(R.id.fab_add_medicine);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new MedicineAdapter(medList, this::showEditSheet, this::deleteMedicine);
        rv.setAdapter(adapter);

        fab.setOnClickListener(v -> showAddSheet());
        loadUsers();

        spinnerUser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < userList.size()) {
                    selectedUserId = userList.get(pos).id;
                    loadMedicines(selectedUserId);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        return root;
    }

    private void loadUsers() {
        AppNetwork.RetrofitHelper.getInstance().listUsers()
                .enqueue(new Callback<List<AppModel.User>>() {
                    @Override public void onResponse(Call<List<AppModel.User>> call,
                                                     Response<List<AppModel.User>> response) {
                        if (response.isSuccessful() && response.body() != null && getContext() != null) {
                            userList.clear(); userList.addAll(response.body());
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

    private void loadMedicines(int userId) {
        AppNetwork.RetrofitHelper.getInstance().getMedicineList(userId)
                .enqueue(new Callback<List<AppModel.MedicineSchedule>>() {
                    @Override public void onResponse(Call<List<AppModel.MedicineSchedule>> call,
                                                     Response<List<AppModel.MedicineSchedule>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            medList.clear(); medList.addAll(response.body());
                            if (adapter != null) adapter.notifyDataSetChanged();

                            // ★★ MỚI: sync xuống local DB
                            if (getContext() != null) {
                                MedicineLocalSync.syncToLocal(
                                        getContext().getApplicationContext(),
                                        userId,
                                        response.body());
                            }
                        }
                    }
                    @Override public void onFailure(Call<List<AppModel.MedicineSchedule>> call, Throwable t) {}
                });
    }

    private void showAddSheet()                              { showMedicineSheet(null); }
    private void showEditSheet(AppModel.MedicineSchedule s)  { showMedicineSheet(s);    }

    private void showMedicineSheet(@Nullable AppModel.MedicineSchedule existing) {
        if (!isAdded() || getContext() == null) return;
        if (selectedUserId == -1) {
            Toast.makeText(getContext(), "Chọn người dùng trước", Toast.LENGTH_SHORT).show();
            return;
        }
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_add_medicine, null);
        sheet.setContentView(v);

        TextInputEditText etMed    = v.findViewById(R.id.et_medicine_name);
        TextInputEditText etTime   = v.findViewById(R.id.et_schedule_time);
        TextInputEditText etNote   = v.findViewById(R.id.et_note);
        SwitchMaterial swRepeat    = v.findViewById(R.id.sw_repeat_daily);
        Button btnSave             = v.findViewById(R.id.btn_save_medicine);

        if (existing != null) {
            etMed.setText(existing.medicineName);
            etTime.setText(existing.scheduleTime);
            etNote.setText(existing.note);
            if (swRepeat != null) swRepeat.setChecked(existing.repeatDaily);
        } else {
            if (swRepeat != null) swRepeat.setChecked(true);
        }

        etTime.setOnClickListener(x -> {
            Calendar now = Calendar.getInstance();
            new TimePickerDialog(getContext(), (tp, h, m) ->
                    etTime.setText(String.format(Locale.getDefault(), "%02d:%02d", h, m)),
                    now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true
            ).show();
        });

        btnSave.setOnClickListener(x -> {
            String name   = etMed.getText()  != null ? etMed.getText().toString().trim()  : "";
            String time   = etTime.getText() != null ? etTime.getText().toString().trim()  : "";
            String note   = etNote.getText() != null ? etNote.getText().toString().trim()  : "";
            boolean repeat = swRepeat != null && swRepeat.isChecked();

            if (name.isEmpty() || time.isEmpty()) {
                Toast.makeText(getContext(), "Nhập tên thuốc và giờ", Toast.LENGTH_SHORT).show();
                return;
            }

            AppModel.MedicineSchedule s = new AppModel.MedicineSchedule();
            if (existing != null) s.id = existing.id;
            s.userId       = selectedUserId;
            s.medicineName = name;
            s.scheduleTime = time;
            s.note         = note;
            s.repeatDaily  = repeat;

            if (existing != null) {
                AppService.AlarmHelper.cancelAlarm(requireContext(), existing.id);
                AppService.AlarmHelper.cancelAllSnooze(requireContext(), existing.id);
            }

            AppNetwork.RetrofitHelper.getInstance().saveMedicine(s)
                    .enqueue(new Callback<AppModel.MedicineSchedule>() {
                        @Override public void onResponse(Call<AppModel.MedicineSchedule> call,
                                                         Response<AppModel.MedicineSchedule> response) {
                            sheet.dismiss();
                            if (response.isSuccessful() && response.body() != null) {
                                AppModel.MedicineSchedule saved = response.body();
                                AppService.AlarmHelper.setAlarm(requireContext(), saved);

                                // ★★ MỚI: upsert local ngay
                                MedicineLocalSync.upsertLocal(
                                        requireContext().getApplicationContext(), saved);

                                String msg = repeat ? "Đã lưu lịch (🔁 hàng ngày)"
                                        : "Đã lưu lịch (1️⃣ một lần)";
                                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                                loadMedicines(selectedUserId);
                            }
                        }
                        @Override public void onFailure(Call<AppModel.MedicineSchedule> call, Throwable t) {}
                    });
        });
        sheet.show();
    }

    private void deleteMedicine(AppModel.MedicineSchedule s) {
        AppService.AlarmHelper.cancelAlarm(requireContext(), s.id);
        AppService.AlarmHelper.cancelAllSnooze(requireContext(), s.id);
        AppNetwork.RetrofitHelper.getInstance().deleteMedicine(s.id)
                .enqueue(new Callback<okhttp3.ResponseBody>() {
                    @Override public void onResponse(Call<okhttp3.ResponseBody> call,
                                                     Response<okhttp3.ResponseBody> response) {
                        if (response.isSuccessful()) {
                            // ★★ MỚI: xoá local luôn
                            MedicineLocalSync.deleteLocal(
                                    requireContext().getApplicationContext(), s.id);
                            Toast.makeText(getContext(), "Đã xóa", Toast.LENGTH_SHORT).show();
                            loadMedicines(selectedUserId);
                        }
                    }
                    @Override public void onFailure(Call<okhttp3.ResponseBody> call, Throwable t) {}
                });
    }

    // ── MedicineAdapter ───────────────────────────────────────
    static class MedicineAdapter extends RecyclerView.Adapter<MedicineAdapter.VH> {
        interface OnEdit   { void edit(AppModel.MedicineSchedule s); }
        interface OnDelete { void delete(AppModel.MedicineSchedule s); }

        private final List<AppModel.MedicineSchedule> list;
        private final OnEdit onEdit;
        private final OnDelete onDelete;

        MedicineAdapter(List<AppModel.MedicineSchedule> list, OnEdit e, OnDelete d) {
            this.list = list; onEdit = e; onDelete = d;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_medicine, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            AppModel.MedicineSchedule s = list.get(pos);
            h.tvName.setText(s.medicineName);
            h.tvTime.setText(s.scheduleTime);
            h.tvNote.setText(s.note != null ? s.note : "");

            if (h.tvRepeat != null) {
                h.tvRepeat.setText(s.repeatDaily ? "🔁 Hàng ngày" : "1️⃣ Một lần");
            }

            if (h.tvLastAction != null) {
                if (s.lastActionAt != null && s.lastAction != null) {
                    String icon = "taken".equals(s.lastAction) ? "ok" : "⏭";
                    h.tvLastAction.setText(icon + " " + s.lastActionAt);
                    h.tvLastAction.setVisibility(View.VISIBLE);
                } else {
                    h.tvLastAction.setVisibility(View.GONE);
                }
            }

            h.btnEdit.setOnClickListener(v -> onEdit.edit(s));
            h.btnDel.setOnClickListener(v  -> onDelete.delete(s));
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvTime, tvNote, tvRepeat, tvLastAction;
            Button   btnEdit, btnDel;
            VH(View v) {
                super(v);
                tvName       = v.findViewById(R.id.tv_medicine_name);
                tvTime       = v.findViewById(R.id.tv_medicine_time);
                tvNote       = v.findViewById(R.id.tv_medicine_note);
                tvRepeat     = v.findViewById(R.id.tv_medicine_repeat);
                tvLastAction = v.findViewById(R.id.tv_medicine_last_action);
                btnEdit      = v.findViewById(R.id.btn_edit_medicine);
                btnDel       = v.findViewById(R.id.btn_delete_medicine);
            }
        }
    }
}