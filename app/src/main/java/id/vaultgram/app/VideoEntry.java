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

    boolean fileComplete() {
        return video != null && video.local != null && video.local.isDownloadingCompleted
            && video.local.path != null && !video.local.path.isEmpty()
            && new File(video.local.path).isFile();
    }

    boolean complete() { return fileComplete() || (exportedUri != null && !exportedUri.isEmpty()); }

    String localPath() { return fileComplete() ? video.local.path : ""; }

    Uri playableUri() {
        if (fileComplete()) return Uri.fromFile(new File(video.local.path));
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
        long size = video != null ? Math.max(video.expectedSize, video.size) : 0;
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
