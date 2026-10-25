package id.vaultgram.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores TDLib API credentials + persistent DB key encrypted by Android Keystore. */
final class SecurePrefs {
    private static final String ALIAS = "vaultgram.settings.aes.v1";
    private final SharedPreferences prefs;

    SecurePrefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("vaultgram_safe", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return gen.generateKey();
    }

    synchronized void put(String name, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(enc, 0, combined, iv.length, enc.length);
            prefs.edit().putString(name, Base64.encodeToString(combined, Base64.NO_WRAP)).apply();
        } catch (Exception e) { throw new IllegalStateException("Tidak dapat menyimpan data aman: " + e.getMessage(), e); }
    }

    synchronized String get(String name) {
        String value = prefs.getString(name, null);
        if (value == null) return "";
        try {
            byte[] combined = Base64.decode(value, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Data aman tidak dapat dibaca. Hapus data aplikasi untuk login ulang.", e); }
    }

    synchronized byte[] dbKey() {
        String stored = get("db_key");
        if (stored.isEmpty()) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            stored = Base64.encodeToString(b, Base64.NO_WRAP);
            put("db_key", stored);
        }
        return Base64.decode(stored, Base64.NO_WRAP);
    }
}
