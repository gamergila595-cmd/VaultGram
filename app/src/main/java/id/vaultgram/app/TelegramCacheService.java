package id.vaultgram.app;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Executes ONLY in Shizuku's shell-identity UserService process.
 * No writes, copies, shell commands, Telegram database access or destructive operations.
 */
public final class TelegramCacheService extends ITelegramCacheService.Stub {
    private static final String BASE = "/storage/emulated/0/Android/data/org.telegram.messenger";
    private static final String VIDEO = BASE + "/files/Telegram/Telegram Video/";
    private static final String CACHE = BASE + "/cache/";
    private final List<File> files = new ArrayList<>();
    private long indexedAt;

    public TelegramCacheService() {
        android.util.Log.i("VaultGramCacheService", "UserService constructor entered");
    }

    private boolean isVideo(File f) {
        if (!f.isFile() || f.length() <= 0) return false;
        String name = f.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")
                || name.endsWith(".m4v") || name.endsWith(".mov");
    }

    private void addFolder(String path) {
        File[] entries = new File(path).listFiles();
        if (entries == null) return;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File f : entries) if (isVideo(f)) files.add(f);
    }

    private synchronized void refresh() {
        if (indexedAt != 0 && System.currentTimeMillis() - indexedAt < 60000) return;
        files.clear();
        addFolder(VIDEO);
        addFolder(CACHE);
        indexedAt = System.currentTimeMillis();
    }

    @Override public synchronized String[] listVideoFiles(int offset, int count) {
        refresh();
        if (offset < 0 || count < 1 || count > 250 || offset >= files.size()) return new String[0];
        int end = Math.min(files.size(), offset + count);
        String[] result = new String[end - offset];
        for (int i = offset; i < end; i++) {
            File f = files.get(i);
            result[i - offset] = f.length() + "|" + f.getAbsolutePath();
        }
        return result;
    }

    @Override public ParcelFileDescriptor openVideo(String path) throws RemoteException {
        try {
            if (path == null) throw new IOException("Empty path");
            File file = new File(path).getCanonicalFile();
            String canonical = file.getPath();
            if (!(canonical.startsWith(VIDEO) || canonical.startsWith(CACHE))
                    || !isVideo(file))
                throw new IOException("File is outside allowed Telegram video folders");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | SecurityException e) {
            throw new RemoteException("Telegram cache not readable: " + e.getMessage());
        }
    }

    @Override public void destroy() {
        System.exit(0);
    }
}
