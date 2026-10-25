package id.vaultgram.app;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.MediaController;
import android.widget.VideoView;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.view.Gravity;
import java.io.File;

/** Play downloaded files in-app (no unsafe file:// URI exposed to other apps). */
public final class PlayerActivity extends Activity {
    private VideoView player;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        String path = getIntent().getStringExtra("local_path");
        File file = path == null ? null : new File(path);
        File home = getFilesDir();
        try {
            if (file == null || !file.isFile() || !file.getCanonicalPath().startsWith(home.getCanonicalPath() + File.separator)) {
                Toast.makeText(this, "Video tidak tersedia", Toast.LENGTH_LONG).show();
                finish(); return;
            }
        } catch (Exception e) { finish(); return; }
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        player = new VideoView(this);
        frame.addView(player, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(frame);
        MediaController media = new MediaController(this);
        media.setAnchorView(player);
        player.setMediaController(media);
        player.setVideoPath(file.getAbsolutePath());
        player.setOnPreparedListener(mp -> { mp.setLooping(false); player.start(); });
        player.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Format video tidak didukung pemutar sistem", Toast.LENGTH_LONG).show();
            return false;
        });
        player.requestFocus();
    }

    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { if (player != null) player.stopPlayback(); super.onDestroy(); }
}
