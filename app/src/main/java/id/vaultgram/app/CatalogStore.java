package id.vaultgram.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Local-first per-bot catalog. Does not store Telegram credentials or session cookies. */
final class CatalogStore extends SQLiteOpenHelper {
    private static final int VERSION = 1;
    CatalogStore(Context context) { super(context, "vaultgram_catalog.db", null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE videos (chat_id TEXT NOT NULL, message_id TEXT NOT NULL, file_id INTEGER NOT NULL,"+
            "sent_at INTEGER NOT NULL, title TEXT NOT NULL, duration INTEGER NOT NULL, size_bytes INTEGER NOT NULL,"+
            "local_path TEXT, thumb_path TEXT, preview TEXT, exported_uri TEXT, favorite INTEGER NOT NULL DEFAULT 0,"+
            "watched INTEGER NOT NULL DEFAULT 0, position_ms INTEGER NOT NULL DEFAULT 0,"+
            "trashed_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat_id,message_id))");
        db.execSQL("CREATE INDEX idx_videos_date ON videos(chat_id,sent_at DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrations are additive; never drop the catalog.
    }

    synchronized List<VideoEntry> load(long chatId) {
        ArrayList<VideoEntry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("videos", null, "chat_id=?",
                new String[]{Long.toString(chatId)}, null, null, "sent_at DESC, message_id DESC")) {
            while (c.moveToNext()) {
                int id = c.getInt(c.getColumnIndexOrThrow("file_id"));
                long messageId = Long.parseLong(c.getString(c.getColumnIndexOrThrow("message_id")));
                byte[] preview = null;
                String encoded = c.getString(c.getColumnIndexOrThrow("preview"));
                if (encoded != null && !encoded.isEmpty()) {
                    try { preview = Base64.decode(encoded, Base64.NO_WRAP); }
                    catch (IllegalArgumentException ignored) { }
                }
                VideoEntry e = new VideoEntry(messageId, c.getInt(c.getColumnIndexOrThrow("sent_at")),
                    c.getString(c.getColumnIndexOrThrow("title")), c.getInt(c.getColumnIndexOrThrow("duration")),
                    id, c.getLong(c.getColumnIndexOrThrow("size_bytes")),
                    c.getString(c.getColumnIndexOrThrow("local_path")),
                    c.getString(c.getColumnIndexOrThrow("thumb_path")), preview);
                e.exportedUri = c.getString(c.getColumnIndexOrThrow("exported_uri"));
                e.favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1;
                e.watched = c.getInt(c.getColumnIndexOrThrow("watched")) == 1;
                e.positionMs = c.getInt(c.getColumnIndexOrThrow("position_ms"));
                e.trashedAt = c.getLong(c.getColumnIndexOrThrow("trashed_at"));
                result.add(e);
            }
        }
        return result;
    }

    synchronized void saveBatch(long chatId, List<VideoEntry> videos) {
        if (videos.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (VideoEntry v : videos) {
                ContentValues data = values(v);
                data.put("chat_id", Long.toString(chatId));
                data.put("message_id", Long.toString(v.messageId));
                db.insertWithOnConflict("videos", null, data, SQLiteDatabase.CONFLICT_IGNORE);
                // Avoid overwriting user's favorite/seen/trash state during a network sync.
                ContentValues remote = new ContentValues();
                remote.put("file_id", v.fileId);
                remote.put("sent_at", v.date);
                remote.put("title", v.title);
                remote.put("duration", v.duration);
                remote.put("size_bytes", v.size());
                if (v.localPath() != null && !v.localPath().isEmpty()) remote.put("local_path", v.localPath());
                if (v.thumbPath() != null && !v.thumbPath().isEmpty()) remote.put("thumb_path", v.thumbPath());
                if (v.preview != null && v.preview.length > 0) remote.put("preview", Base64.encodeToString(v.preview, Base64.NO_WRAP));
                if (v.exportedUri != null && !v.exportedUri.isEmpty()) remote.put("exported_uri", v.exportedUri);
                db.update("videos", remote, "chat_id=? AND message_id=?",
                    new String[]{Long.toString(chatId),Long.toString(v.messageId)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void saveUser(long chatId, VideoEntry v) {
        ContentValues data = new ContentValues();
        data.put("favorite", v.favorite ? 1 : 0);
        data.put("watched", v.watched ? 1 : 0);
        data.put("position_ms", v.positionMs);
        data.put("trashed_at", v.trashedAt);
        if (v.exportedUri != null) data.put("exported_uri", v.exportedUri);
        getWritableDatabase().update("videos", data, "chat_id=? AND message_id=?",
            new String[]{Long.toString(chatId), Long.toString(v.messageId)});
    }

    private static ContentValues values(VideoEntry v) {
        ContentValues data = new ContentValues();
        data.put("file_id", v.fileId);
        data.put("sent_at", v.date);
        data.put("title", v.title);
        data.put("duration", v.duration);
        data.put("size_bytes", v.size());
        data.put("local_path", v.localPath());
        data.put("thumb_path", v.thumbPath());
        if (v.preview != null) data.put("preview", Base64.encodeToString(v.preview, Base64.NO_WRAP));
        if (v.exportedUri != null) data.put("exported_uri", v.exportedUri);
        data.put("favorite", v.favorite ? 1 : 0);
        data.put("watched", v.watched ? 1 : 0);
        data.put("position_ms", v.positionMs);
        data.put("trashed_at", v.trashedAt);
        return data;
    }

    synchronized long cachedBytes(long chatId) {
        long size = 0;
        for (VideoEntry e : load(chatId)) {
            String p = e.localPath();
            if (!p.isEmpty()) size += new File(p).length();
        }
        return size;
    }
}
