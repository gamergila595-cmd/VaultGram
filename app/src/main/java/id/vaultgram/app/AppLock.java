package id.vaultgram.app;

import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** A separate full-screen lock activity protects both the gallery and player. */
public final class AppLock extends Application implements Application.ActivityLifecycleCallbacks {
    private static boolean unlocked;
    private static boolean showing;
    private static int started;
    private static long backgroundAt = -1L;
    private static int attempts;
    private static long blockedUntil;

    @Override public void onCreate() { super.onCreate(); registerActivityLifecycleCallbacks(this); }
    @Override public void onActivityStarted(Activity a) { started++; }
    @Override public void onActivityStopped(Activity a) {
        started = Math.max(0,started-1);
        if (started == 0) backgroundAt = SystemClock.elapsedRealtime();
    }
    @Override public void onActivityCreated(Activity a, Bundle state) { privacy(a); }
    @Override public void onActivityResumed(Activity a) { privacy(a); }
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityDestroyed(Activity a) {
        if (a instanceof LockActivity) showing=false;
    }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

    static void privacy(Activity a) {
        if (a instanceof LockActivity || !"off".equals(new SecurePrefs(a).get("block_screenshots")))
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(false);
    }
    static long timeout(Activity a) {
        String n=new SecurePrefs(a).get("lock_timeout_ms");
        try { return n.isEmpty() ? 60000L : Math.max(0,Math.min(300000L,Long.parseLong(n))); }
        catch (Exception e) { return 60000L; }
    }
    static void gate(Activity a) {
        if (a instanceof LockActivity || a.isFinishing()) return;
        if (unlocked && (backgroundAt < 0 || SystemClock.elapsedRealtime()-backgroundAt < timeout(a))) return;
        unlocked=false;
        if (!showing) {
            showing=true;
            a.startActivity(new android.content.Intent(a,LockActivity.class));
        }
    }
    static boolean isUnlocked() { return unlocked; }
    static void unlocked() {
        unlocked=true;showing=false;backgroundAt=-1L;attempts=0;blockedUntil=0;
    }
    static void lock(Activity a) {
        unlocked=false;backgroundAt=-1L;gate(a);
    }
    static boolean canTry() { return SystemClock.elapsedRealtime() >= blockedUntil; }
    static long waitSeconds() { return Math.max(0,(blockedUntil-SystemClock.elapsedRealtime()+999)/1000); }
    static boolean check(Activity a,String p) {
        if (!canTry()) return false;
        if (matches(new SecurePrefs(a).get("app_lock_record"),p)) { unlocked();return true; }
        if (++attempts >= 5) { attempts=0;blockedUntil=SystemClock.elapsedRealtime()+30000; }
        return false;
    }
    static boolean hasPassword(Activity a) { return !new SecurePrefs(a).get("app_lock_record").isEmpty(); }
    static boolean saveNew(Activity a,String p) {
        if(p.length()<4 || p.length()>128) return false;
        try { new SecurePrefs(a).put("app_lock_record",createRecord(p)); unlocked(); return true; }
        catch(Exception e){ return false; }
    }
    static boolean biometricEnabled(Activity a) { return !"off".equals(new SecurePrefs(a).get("fingerprint")); }

    static void change(Activity a) {
        LinearLayout box=new LinearLayout(a);
        box.setPadding(50,12,50,12); box.setOrientation(LinearLayout.VERTICAL);
        EditText old=passwordInput(a,"Password saat ini");
        EditText next=passwordInput(a,"Password baru (min. 4 karakter)");
        EditText confirm=passwordInput(a,"Ulangi password baru");
        box.addView(old);box.addView(next);box.addView(confirm);
        AlertDialog d=new AlertDialog.Builder(a).setTitle("Ganti password VaultGram")
            .setView(box).setNegativeButton("Batal",null).setPositiveButton("Simpan",null).create();
        d.setOnShowListener(v -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            if (!checkWithoutLock(a,old.getText().toString())) {old.setError("Password lama salah");return;}
            if (!next.getText().toString().equals(confirm.getText().toString())) {confirm.setError("Tidak cocok");return;}
            if (!saveNew(a,next.getText().toString())) {next.setError("Minimal 4 karakter");return;}
            d.dismiss();Toast.makeText(a,"Password diperbarui",Toast.LENGTH_SHORT).show();
        }));d.show();
    }
    private static EditText passwordInput(Activity a,String hint) {
        EditText e=new EditText(a);e.setSingleLine(true);e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;
    }
    private static boolean checkWithoutLock(Activity a,String p){return matches(new SecurePrefs(a).get("app_lock_record"),p);}
    private static byte[] derive(String p,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(p.toCharArray(),salt,120000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    private static String createRecord(String p)throws Exception{
        byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt,Base64.NO_WRAP)+":"+Base64.encodeToString(derive(p,salt),Base64.NO_WRAP);
    }
    private static boolean matches(String record,String p){
        try{String[] a=record.split(":",-1);
            return a.length==2 && MessageDigest.isEqual(Base64.decode(a[1],Base64.NO_WRAP),derive(p,Base64.decode(a[0],Base64.NO_WRAP)));
        }catch(Exception e){return false;}
    }
}
