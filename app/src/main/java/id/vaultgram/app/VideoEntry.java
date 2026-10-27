package id.vaultgram.app;

import org.drinkless.tdlib.TdApi;
import java.io.File;
import android.net.Uri;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** One video-bearing Telegram message, never merged across chats. */
final class VideoEntry {
    final long messageId;
    final int fileId;
    final int date;
    final String title;
    final int duration;
    final byte[] preview;
    TdApi.File video;
    TdApi.File thumb;
    String exportedUri;
    String cachedPath = "";
    String cachedThumbPath = "";
    long cachedSize;
    boolean favorite;
    boolean watched;
    int positionMs;
    long trashedAt;


    VideoEntry(long messageId, int date, String name, int duration, TdApi.File video,
               TdApi.File thumb, byte[] preview) {
        this.messageId = messageId;
        this.date = date;
        this.video = video;
        this.fileId = video.id;
        this.title = (name == null || name.trim().isEmpty()) ? "Video " + dateLabel(date) : name;
        this.duration = duration;
        this.thumb = thumb;
        this.preview = preview;
    }

    VideoEntry(long messageId, int date, String title, int duration, int fileId,
               long size, String path, String thumbPath, byte[] preview) {
        this.messageId = messageId;
        this.date = date;
        this.title = title;
        this.duration = duration;
        this.fileId = fileId;
        this.video = null;
        this.thumb = null;
        this.cachedSize = size;
        this.cachedPath = path == null ? "" : path;
        this.cachedThumbPath = thumbPath == null ? "" : thumbPath;
        this.preview = preview;
    }

    void preserve(VideoEntry existing) {
        this.exportedUri = existing.exportedUri;
        this.cachedPath = existing.cachedPath;
        this.cachedThumbPath = existing.cachedThumbPath;
        this.cachedSize = existing.cachedSize;
        this.favorite = existing.favorite;
        this.watched = existing.watched;
        this.positionMs = existing.positionMs;
        this.trashedAt = existing.trashedAt;
    }

    long size() {
        return video == null ? cachedSize : Math.max(video.size, video.expectedSize);
    }

    String thumbPath() {
        if (thumb != null && thumb.local != null && thumb.local.isDownloadingCompleted &&
                thumb.local.path != null && new File(thumb.local.path).isFile())
            return thumb.local.path;
        return cachedThumbPath != null && new File(cachedThumbPath).isFile() ? cachedThumbPath : "";
    }

    boolean fileComplete() {
        return video != null && video.local != null && video.local.isDownloadingCompleted
            && video.local.path != null && !video.local.path.isEmpty()
            && new File(video.local.path).isFile();
    }

    boolean complete() { return !localPath().isEmpty() || (exportedUri != null && !exportedUri.isEmpty()); }

    String localPath() {
        if (fileComplete()) return video.local.path;
        return cachedPath != null && !cachedPath.isEmpty() && new File(cachedPath).isFile() ? cachedPath : "";
    }

    Uri playableUri() {
        if (!localPath().isEmpty()) return Uri.fromFile(new File(localPath()));
        return exportedUri == null ? null : Uri.parse(exportedUri);
    }

    int progress() {
        if (complete()) return 100;
        if (video == null || video.local == null) return 0;
        long size = video.expectedSize > 0 ? video.expectedSize : video.size;
        return size <= 0 ? 0 : (int) Math.max(0, Math.min(99, video.local.downloadedSize * 100L / size));
    }

    boolean downloading() { return video != null && video.local != null && video.local.isDownloadingActive; }

    String summary() {
        long size = size();
        String readable = size > 0 ? String.format(Locale.US, "%.1f MB", size / 1048576.0) : "Ukuran tidak diketahui";
        return dateLabel(date) + "  ·  " + readable;
    }

    String durationLabel() {
        if (duration <= 0) return "VIDEO";
        return String.format(Locale.US, "%02d:%02d", duration / 60, duration % 60);
    }

    private static String dateLabel(int seconds) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM, new Locale("id", "ID"))
            .format(new Date(seconds * 1000L));
    }
}
