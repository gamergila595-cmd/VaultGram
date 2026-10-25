package id.vaultgram.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Matches exported/public videos; Telegram's private cache is not readable by other apps. */
final class LocalVideos {
    private static final String PREFERENCES = "vaultgram_exported_files";

    private static String key(long chatId, long messageId) { return chatId + ":" + messageId; }

    static void remember(Context context, long chatId, VideoEntry video, Uri uri) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(key(chatId, video.messageId), uri.toString()).apply();
    }

    static Map<Long, String> find(Context context, long chatId, List<VideoEntry> videos) {
        Map<String, String> byNameAndSize = new HashMap<>();
        collect(context.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, byNameAndSize);
        if (Build.VERSION.SDK_INT >= 29)
            collect(context.getContentResolver(), MediaStore.Downloads.EXTERNAL_CONTENT_URI, byNameAndSize);
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        Map<Long, String> matches = new HashMap<>();
        for (VideoEntry video : videos) {
            String uri = prefs.getString(key(chatId, video.messageId), null);
            if (exists(context, uri)) { matches.put(video.messageId, uri); continue; }
            long size = video.video == null ? 0 :
                (video.video.size > 0 ? video.video.size : video.video.expectedSize);
            if (size <= 0 || video.title.startsWith("Video ") || video.title.equals("Pesan video")) continue;
            String result = byNameAndSize.get(indexKey(video.title, size));
            if (result == null && video.title.lastIndexOf('.') == -1)
                result = byNameAndSize.get(indexKey(video.title + ".mp4", size));
            if (result != null) matches.put(video.messageId, result);
        }
        return matches;
    }

    private static String indexKey(String name, long size) {
        String fixed = name.replaceAll("[^a-zA-Z0-9._ -]", "_").trim().toLowerCase(Locale.US);
        return fixed + "|" + size;
    }

    private static boolean exists(Context context, String url) {
        if (url == null || !url.startsWith("content://media/")) return false;
        try (android.content.res.AssetFileDescriptor fd =
                 context.getContentResolver().openAssetFileDescriptor(Uri.parse(url), "r")) {
            return fd != null && fd.getLength() != 0;
        } catch (Exception e) { return false; }
    }

    private static void collect(ContentResolver resolver, Uri collection, Map<String, String> result) {
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE};
        try (Cursor cursor = resolver.query(collection, columns, null, null, null)) {
            if (cursor == null) return;
            int id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            while (cursor.moveToNext()) {
                if (cursor.isNull(name) || cursor.isNull(size) || cursor.getLong(size) <= 0) continue;
                String uri = ContentUris.withAppendedId(collection, cursor.getLong(id)).toString();
                result.put(indexKey(cursor.getString(name), cursor.getLong(size)), uri);
            }
        } catch (Exception ignored) {
            // Android restricts reading other apps' media without the user's permission.
        }
    }
}
