package id.vaultgram.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import android.util.Base64;

/** App-only password gate. Telegram's separate two-factor password is unaffected. */
public final class AppLock extends Application implements Application.ActivityLifecycleCallbacks {
    private static boolean unlocked;
    private static boolean showing;
    private static Activity owner;
    private static int attempts;
    private static long blockedUntil;
    private int started;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityStarted(Activity a) { started++; }
    @Override public void onActivityStopped(Activity a) {
        started = Math.max(0, started - 1);
        if (started == 0) unlocked = false;
    }
    @Override public void onActivityDestroyed(Activity a) {
        if (owner == a) { showing = false; owner = null; }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityResumed(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

    static void gate(Activity activity) {
        if (unlocked || showing || activity.isFinishing()) return;
        String record = new SecurePrefs(activity).get("app_lock_record");
        if (record.isEmpty()) showSet(activity, true);
        else showUnlock(activity, record);
    }

    private static EditText passwordInput(Activity a, String hint) {
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private static LinearLayout form(Activity a, EditText... inputs) {
        LinearLayout box = new LinearLayout(a);
        box.setPadding(dp(a, 22), dp(a, 4), dp(a, 22), 0);
        box.setOrientation(LinearLayout.VERTICAL);
        for (EditText input : inputs) box.addView(input);
        return box;
    }

    private static int dp(Activity a, float n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }

    private static void showSet(Activity a, boolean firstRun) {
        EditText one = passwordInput(a, "Password baru (minimal 4 karakter)");
        EditText two = passwordInput(a, "Ulangi password baru");
        AlertDialog dialog = new AlertDialog.Builder(a)
            .setTitle(firstRun ? "Buat password VaultGram" : "Ganti password VaultGram")
            .setMessage("Password ini dipakai setiap aplikasi dibuka, terpisah dari password Telegram.")
            .setView(form(a, one, two))
            .setCancelable(false)
            .setPositiveButton("Simpan", null)
            .setNegativeButton(firstRun ? "Keluar" : "Batal", (d, w) -> { if (firstRun) a.finish(); })
            .create();
        register(dialog, a);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p = one.getText().toString();
            if (p.length() < 4 || p.length() > 128) { one.setError("Gunakan 4–128 karakter"); return; }
            if (!p.equals(two.getText().toString())) { two.setError("Konfirmasi tidak cocok"); return; }
            try {
                new SecurePrefs(a).put("app_lock_record", createRecord(p));
                unlocked = true;
                attempts = 0;
                dialog.dismiss();
                Toast.makeText(a, "Password VaultGram aktif", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { one.setError("Gagal menyimpan password"); }
        }));
        dialog.show();
    }

    private static void showUnlock(Activity a, String record) {
        EditText password = passwordInput(a, "Password VaultGram");
        AlertDialog dialog = new AlertDialog.Builder(a)
            .setTitle("VaultGram terkunci")
            .setView(form(a, password))
            .setCancelable(false)
            .setPositiveButton("Buka", null)
            .setNegativeButton("Keluar", (d, w) -> a.finish())
            .create();
        register(dialog, a);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (SystemClock.elapsedRealtime() < blockedUntil) {
                password.setError("Terlalu banyak percobaan. Coba lagi dalam 30 detik."); return;
            }
            if (!matches(record, password.getText().toString())) {
                password.setText("");
                password.setError("Password salah");
                if (++attempts >= 5) { blockedUntil = SystemClock.elapsedRealtime() + 30000; attempts = 0; }
                return;
            }
            attempts = 0;
            unlocked = true;
            dialog.dismiss();
        }));
        dialog.show();
    }

    static void change(Activity a) {
        String record = new SecurePrefs(a).get("app_lock_record");
        if (record.isEmpty()) { showSet(a, false); return; }
        EditText old = passwordInput(a, "Password saat ini");
        EditText one = passwordInput(a, "Password baru");
        EditText two = passwordInput(a, "Ulangi password baru");
        AlertDialog dialog = new AlertDialog.Builder(a)
            .setTitle("Ubah password VaultGram")
            .setView(form(a, old, one, two))
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!matches(record, old.getText().toString())) { old.setError("Password lama salah"); return; }
            String p = one.getText().toString();
            if (p.length() < 4 || p.length() > 128) { one.setError("Gunakan 4–128 karakter"); return; }
            if (!p.equals(two.getText().toString())) { two.setError("Konfirmasi tidak cocok"); return; }
            try {
                new SecurePrefs(a).put("app_lock_record", createRecord(p));
                dialog.dismiss();
                Toast.makeText(a, "Password berhasil diubah", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { one.setError("Gagal menyimpan password"); }
        }));
        dialog.show();
    }

    private static void register(AlertDialog dialog, Activity a) {
        showing = true;
        owner = a;
        dialog.setOnDismissListener(d -> { if (owner == a) { showing = false; owner = null; } });
    }

    private static byte[] derive(String pass, byte[] salt) throws Exception {
        PBEKeySpec key = new PBEKeySpec(pass.toCharArray(), salt, 120000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded(); }
        finally { key.clearPassword(); }
    }

    private static String createRecord(String password) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
               Base64.encodeToString(derive(password, salt), Base64.NO_WRAP);
    }

    private static boolean matches(String record, String password) {
        try {
            String[] parts = record.split(":", -1);
            return parts.length == 2 && MessageDigest.isEqual(
                Base64.decode(parts[1], Base64.NO_WRAP), derive(password, Base64.decode(parts[0], Base64.NO_WRAP)));
        } catch (Exception e) { return false; }
    }
}
