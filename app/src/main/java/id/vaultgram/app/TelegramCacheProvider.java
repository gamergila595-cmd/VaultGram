package id.vaultgram.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;

/**
 * Private read-only content URI gateway for VideoView/MediaPlayer. The returned
 * file descriptor points to the ORIGINAL Telegram media; no intermediate copy.
 */
public final class TelegramCacheProvider extends ContentProvider {
    static final String AUTHORITY = "id.vaultgram.app.telegram_cache";

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "video/mp4"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode) || uri == null || !"video".equals(uri.getLastPathSegment()))
            throw new FileNotFoundException("Read-only video provider");
        String encoded = uri.getQueryParameter("path");
        if (encoded == null) throw new FileNotFoundException("Missing file path");
        return TelegramCacheBridge.open(encoded);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
