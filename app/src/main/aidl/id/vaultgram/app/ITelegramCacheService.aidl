package id.vaultgram.app;

import android.os.ParcelFileDescriptor;

// Read-only bridge. Runs as ADB shell via Shizuku UserService.
interface ITelegramCacheService {
    String[] listVideoFiles(int offset, int count) = 1;
    ParcelFileDescriptor openVideo(String path) = 2;
    void destroy() = 16777114;
}
