package id.vaultgram.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.Executor;

/** Full-screen authentication, separate from the Telegram login flow. */
public final class LockActivity extends Activity {
    private static final int BG=0xFF0A1020, PANEL=0xFF172236, ACCENT=0xFF6BE0CA, TEXT=0xFFF0F4FB, MUTED=0xFF94A3B8;
    private EditText pass, confirm;
    private TextView hint;
    private boolean setup;
    private CancellationSignal signal;
    private boolean biometricOffered;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);if(bold)t.setTypeface(null,1);return t;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setup=!AppLock.hasPassword(this);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(BG);setContentView(sc);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28),dp(36),dp(28),dp(32));page.setGravity(Gravity.CENTER_VERTICAL);sc.addView(page);
        TextView icon=text("◆",36,BG,true);icon.setGravity(Gravity.CENTER);icon.setBackground(shape(ACCENT,24));
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(78),dp(78));ip.gravity=Gravity.CENTER_HORIZONTAL;ip.bottomMargin=dp(24);page.addView(icon,ip);
        TextView brand=text("VaultGram",32,TEXT,true);brand.setGravity(Gravity.CENTER);page.addView(brand);
        TextView subtitle=text(setup?"Buat kunci galeri pribadi":"Galeri pribadi lu terkunci",15,MUTED,false);
        subtitle.setGravity(Gravity.CENTER);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(9);sp.bottomMargin=dp(32);page.addView(subtitle,sp);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22),dp(25),dp(22),dp(25));card.setBackground(shape(PANEL,24));page.addView(card);
        card.addView(text(setup?"Buat password VaultGram":"Buka VaultGram",21,TEXT,true));
        hint=text(setup?"Password ini hanya mengunci aplikasi, bukan password Telegram.":"Masukkan password atau gunakan sidik jari.",13,MUTED,false);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(10);hp.bottomMargin=dp(22);card.addView(hint,hp);
        pass=new EditText(this);pass.setSingleLine(true);pass.setHint(setup?"Password baru (min. 4 karakter)":"Password VaultGram");
        pass.setTextColor(TEXT);pass.setHintTextColor(MUTED);pass.setTextSize(16);
        pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setPadding(dp(14),dp(8),dp(14),dp(8));pass.setBackground(shape(0xFF23334B,12));
        card.addView(pass,new LinearLayout.LayoutParams(-1,dp(55)));
        if(setup){confirm=new EditText(this);confirm.setSingleLine(true);confirm.setHint("Ulangi password");
            confirm.setTextColor(TEXT);confirm.setHintTextColor(MUTED);confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
            confirm.setPadding(dp(14),dp(8),dp(14),dp(8));confirm.setBackground(shape(0xFF23334B,12));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(55));cp.topMargin=dp(12);card.addView(confirm,cp);}
        TextView open=text(setup?"Simpan dan buka galeri":"Buka galeri",15,BG,true);
        open.setGravity(Gravity.CENTER);open.setBackground(shape(ACCENT,13));
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(54));op.topMargin=dp(24);card.addView(open,op);
        open.setOnClickListener(v->submit());
        pass.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pass.setOnEditorActionListener((v,action,event)->{submit();return true;});
        if(!setup && AppLock.biometricEnabled(this) && Build.VERSION.SDK_INT>=28){
            TextView finger=text("◉   Buka dengan sidik jari",14,ACCENT,true);finger.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(58));fp.topMargin=dp(8);card.addView(finger,fp);
            finger.setOnClickListener(v->fingerprint());
        }
        TextView footer=text("Akun Telegram dan video tidak terhapus saat aplikasi terkunci.",12,MUTED,false);
        footer.setGravity(Gravity.CENTER);LinearLayout.LayoutParams foot=new LinearLayout.LayoutParams(-1,-2);
        foot.topMargin=dp(24);page.addView(footer,foot);
    }
    @Override protected void onResume(){super.onResume();if(!setup && !biometricOffered){biometricOffered=true;fingerprint();}}
    private void submit(){
        String value=pass.getText().toString();
        if(setup){
            if(value.length()<4){pass.setError("Minimal 4 karakter");return;}
            if(!value.equals(confirm.getText().toString())){confirm.setError("Konfirmasi tidak sama");return;}
            if(!AppLock.saveNew(this,value)){pass.setError("Gagal menyimpan password");return;}
            finish();return;
        }
        if(!AppLock.canTry()){hint.setText("Terlalu banyak percobaan. Coba lagi dalam "+AppLock.waitSeconds()+" detik.");return;}
        if(AppLock.check(this,value)){finish();return;}
        pass.setText("");pass.setError("Password salah");
        if(!AppLock.canTry())hint.setText("Terlalu banyak percobaan. Coba lagi dalam 30 detik.");
    }
    private void fingerprint(){
        if(Build.VERSION.SDK_INT<28 || !AppLock.biometricEnabled(this))return;
        try {
            if(Build.VERSION.SDK_INT>=29){
                BiometricManager mgr=(BiometricManager)getSystemService(BIOMETRIC_SERVICE);
                if(mgr==null || mgr.canAuthenticate()!=BiometricManager.BIOMETRIC_SUCCESS)return;
            } else {
                android.hardware.fingerprint.FingerprintManager mgr=(android.hardware.fingerprint.FingerprintManager)getSystemService(FINGERPRINT_SERVICE);
                if(mgr==null || !mgr.isHardwareDetected() || !mgr.hasEnrolledFingerprints())return;
            }
            signal=new CancellationSignal();
            Executor ex=getMainExecutor();
            BiometricPrompt dialog=new BiometricPrompt.Builder(this).setTitle("Buka VaultGram")
                .setSubtitle("Verifikasi sidik jari di perangkat ini")
                .setNegativeButton("Gunakan password",ex,(d,w)->{}).build();
            dialog.authenticate(signal,ex,new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
                    AppLock.unlocked();finish();
                }
                @Override public void onAuthenticationError(int code,CharSequence message){
                    if(hint!=null)hint.setText("Gunakan password jika sidik jari tidak tersedia.");
                }
            });
        }catch(Exception ignored){ /* Unsupported or not enrolled: password still works. */ }
    }
    @Override protected void onDestroy(){if(signal!=null)signal.cancel();super.onDestroy();}
    @Override public void onBackPressed(){finishAffinity();}
}
