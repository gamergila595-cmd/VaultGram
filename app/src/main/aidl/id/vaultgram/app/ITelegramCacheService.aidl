package id.vaultgram.app;

import android.os.ParcelFileDescriptor;

// Read-only bridge. Runs as ADB shell via Shizuku UserService.
// Let AIDL assign transaction IDs consistently for all methods.
interface ITelegramCacheService {
    String[] listVideoFiles(int offset, int count);
    ParcelFileDescriptor openVideo(String path);
    void destroy();
}
