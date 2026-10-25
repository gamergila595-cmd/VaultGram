package id.vaultgram.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;

/** Tap opens the player immediately; next/previous and autoplay use the gallery order. */
public final class PlayerActivity extends Activity implements TelegramRepository.Listener {
    private TelegramRepository repo;
    private final ArrayList<VideoEntry> queue = new ArrayList<>();
    private VideoView video;
    private TextView title, status, backButton, nextButton, position;
    private int index = -1;
    private long requestedId = -1;
    private String playingUri = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repo = TelegramRepository.get(getApplicationContext());
        List<VideoEntry> selected = repo.playback();
        queue.addAll(selected);
        long messageId = getIntent().getLongExtra("message_id", 0);
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).messageId == messageId) { index = i; break; }
        }
        if (index < 0) {
            queue.clear(); queue.addAll(repo.snapshot());
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).messageId == messageId) { index = i; break; }
            }
        }
        if (index < 0) { Toast.makeText(this, "Video tidak ditemukan", Toast.LENGTH_LONG).show(); finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        MediaController media = new MediaController(this);
        media.setAnchorView(video);
        video.setMediaController(media);

        status = label("Menyiapkan video…", 15, Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setBackground(tint(0xD9000000, 12));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        status.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.addView(status, statusLp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(14), dp(8));
        top.setBackgroundColor(0xBB000000);
        TextView close = label("‹  Kembali", 15, Color.WHITE);
        close.setPadding(dp(8), dp(10), dp(15), dp(10));
        close.setOnClickListener(v -> finish());
        top.addView(close);
        title = label("Video", 15, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(dp(8), dp(12), dp(8), dp(12));
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(0xCB000000);
        backButton = label("◀  Sebelumnya", 14, Color.WHITE);
        backButton.setGravity(Gravity.CENTER);
        controls.addView(backButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        backButton.setOnClickListener(v -> showItem(index - 1));
        position = label("1/1", 12, 0xFFBCC6D4);
        position.setGravity(Gravity.CENTER);
        controls.addView(position, new LinearLayout.LayoutParams(dp(56), dp(50)));
        nextButton = label("Berikutnya  ▶", 14, Color.WHITE);
        nextButton.setGravity(Gravity.CENTER);
        controls.addView(nextButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        nextButton.setOnClickListener(v -> showItem(index + 1));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        video.setOnCompletionListener(mp -> {
            if (index + 1 < queue.size()) showItem(index + 1);
            else { status.setText("Semua video selesai"); status.setVisibility(View.VISIBLE); }
        });
        video.setOnErrorListener((mp, what, extra) -> {
            status.setText("Format video tidak didukung pemutar HP. Coba video lainnya.");
            status.setVisibility(View.VISIBLE);
            return true;
        });
        showItem(index);
    }

    private int dp(int px) { return Math.round(px * getResources().getDisplayMetrics().density); }
    private GradientDrawable tint(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        return shape;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    private void showItem(int target) {
        if (target < 0 || target >= queue.size()) return;
        index = target;
        requestedId = -1;
        playingUri = "";
        video.stopPlayback();
        VideoEntry item = queue.get(index);
        title.setText(item.title);
        position.setText((index + 1) + "/" + queue.size());
        backButton.setAlpha(index == 0 ? 0.35f : 1f);
        nextButton.setAlpha(index == queue.size() - 1 ? 0.35f : 1f);
        status.setVisibility(View.VISIBLE);
        updatePlayback();
    }

    private void updatePlayback() {
        if (index < 0 || index >= queue.size()) return;
        VideoEntry item = queue.get(index);
        Uri uri = item.playableUri();
        if (uri == null) {
            status.setText(item.downloading() || item.progress() > 0
                ? "Mengunduh video… " + item.progress() + "%"
                : "Mengunduh video sebelum diputar…");
            status.setVisibility(View.VISIBLE);
            if (requestedId != item.messageId) {
                requestedId = item.messageId;
                repo.download(item);
            }
            return;
        }
        if (uri.toString().equals(playingUri)) return;
        playingUri = uri.toString();
        status.setText("Membuka video…");
        status.setVisibility(View.VISIBLE);
        video.setVideoURI(uri);
        video.setOnPreparedListener(mp -> {
            status.setVisibility(View.GONE);
            mp.setLooping(false);
            video.start();
        });
        video.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (repo != null) repo.bind(this);
        AppLock.gate(this);
    }
    @Override protected void onPause() {
        if (video != null) video.pause();
        if (repo != null) repo.unbind(this);
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
    @Override public void onStage(TelegramRepository.Stage stage, String message) {}
    @Override public void onAccount(String account) {}
    @Override public void onBot(String username) {}
    @Override public void onVideosChanged() { updatePlayback(); }
    @Override public void onStatus(String message) {}
    @Override public void onError(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }
}
