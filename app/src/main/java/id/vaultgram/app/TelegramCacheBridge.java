package id.vaultgram.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

/** Opt-in, read-only bridge. Never guesses matches when file metadata is ambiguous. */
final class TelegramCacheBridge {
    private static final int REQ = 39017;
    private static volatile ITelegramCacheService service;
    private static volatile boolean binding;
    private static volatile long bindStartedAt;
    private static volatile String bindError = "";
    private static final Map<Long, String> matched = Collections.synchronizedMap(new HashMap<>());
    private static volatile int indexedCount;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ITelegramCacheService.Stub.asInterface(binder);
            binding = false;
            bindError = "";
            android.util.Log.i("VaultGramShizuku", "UserService connected: " + (binder != null));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            matched.clear();
            bindError = "Layanan Shizuku terputus.";
            android.util.Log.w("VaultGramShizuku", bindError);
        }
        @Override public void onBindingDied(ComponentName name) {
            service = null;
            binding = false;
            bindError = "Layanan Shizuku berhenti saat menghubungkan.";
        }
        @Override public void onNullBinding(ComponentName name) {
            service = null;
            binding = false;
            bindError = "Layanan Shizuku tidak mengembalikan Binder.";
        }
    };

    static boolean running() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable e) { return false; }
    }

    static boolean allowed() {
        try { return running() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED; }
        catch (Throwable e) { return false; }
    }

    static boolean connected() { return service != null; }

    static String start(Activity activity) {
        if (!running()) return "Shizuku belum berjalan. Buka Shizuku dan tekan Start.";
        if (!allowed()) {
            try {
                Shizuku.requestPermission(REQ);
                return "Izinkan VaultGram di dialog Shizuku, lalu ketuk tombol ini lagi.";
            } catch (Throwable e) { return "Permintaan izin Shizuku gagal: " + e.getMessage(); }
        }
        if (service != null) return "Telegram Cache Bridge sudah terhubung.";
        if (binding && System.currentTimeMillis() - bindStartedAt < 35000)
            return "Menghubungkan layanan Shizuku...";
        if (binding) {
            binding = false;
            bindError = "UserService belum siap dalam 35 detik; periksa log Shizuku.";
        }
        try {
            android.util.Log.i("VaultGramShizuku", "Starting UserService bind; permission granted");
            binding = true;
            bindStartedAt = System.currentTimeMillis();
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(activity, TelegramCacheService.class))
                    .processNameSuffix("telegram_cache")
                    .daemon(false)
                    .debuggable(true)
                    .version(3);
            Shizuku.bindUserService(args, CONNECTION);
            return "Memulai jembatan cache. " + bindError + " Tunggu sebentar lalu coba lagi.";
        } catch (Throwable e) {
            binding = false;
            android.util.Log.e("VaultGramShizuku", "bindUserService failed", e);
            return "Gagal menghubungkan Shizuku: " + e.getMessage();
        }
    }

    /** Used by the private content provider. Never writes the source file. */
    static ParcelFileDescriptor open(String path) throws FileNotFoundException {
        ITelegramCacheService s = service;
        if (s == null) throw new FileNotFoundException("Shizuku bridge disconnected");
        try {
            ParcelFileDescriptor fd = s.openVideo(path);
            if (fd == null) throw new FileNotFoundException("Telegram cache file missing");
            return fd;
        } catch (RemoteException e) {
            throw new FileNotFoundException("Telegram cache unavailable: " + e.getMessage());
        }
    }

    static void scan(Context context, List<VideoEntry> entries, Runnable finished) {
        ITelegramCacheService s = service;
        if (s == null) { finished.run(); return; }
        new Thread(() -> {
            Map<Long, List<String>> bySize = new HashMap<>();
            int total = 0;
            try {
                for (int start = 0; start < 100000; start += 200) {
                    String[] batch = s.listVideoFiles(start, 200);
                    if (batch == null || batch.length == 0) break;
                    for (String item : batch) {
                        int bar = item.indexOf('|');
                        if (bar <= 0) continue;
                        long size = Long.parseLong(item.substring(0, bar));
                        String path = item.substring(bar + 1);
                        bySize.computeIfAbsent(size, k -> new ArrayList<>()).add(path);
                        total++;
                    }
                    if (batch.length < 200) break;
                }
                Map<Long, String> verified = new HashMap<>();
                for (VideoEntry entry : entries) {
                    if (entry.size() <= 0) continue;
                    List<String> candidates = bySize.get(entry.size());
                    // Exact byte length + one and only one candidate are required.
                    // Equal sizes can still occur; do not claim cryptographic identity.
                    if (candidates != null && candidates.size() == 1)
                        verified.put(entry.messageId, candidates.get(0));
                }
                matched.clear();
                matched.putAll(verified);
            } catch (Exception ignored) {
                matched.clear();
            }
            indexedCount = total;
            ((Activity) context).runOnUiThread(finished);
        }, "VaultGram-TelegramCacheIndex").start();
    }

    static int count() { return indexedCount; }
    static int matches() { return matched.size(); }

    static Uri uri(VideoEntry entry) {
        if (service == null) return null;
        String path = matched.get(entry.messageId);
        if (path == null) return null;
        return new Uri.Builder().scheme("content")
                .authority(TelegramCacheProvider.AUTHORITY)
                .appendPath("video").appendQueryParameter("path", path).build();
    }
}
