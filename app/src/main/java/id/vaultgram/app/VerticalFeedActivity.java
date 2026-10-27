package id.vaultgram.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Genuine vertical paging for the VaultGram video feed.
 * ViewPager2 physically scrolls whole video pages under the finger.
 * There is deliberately no horizontal transition or fake page animation.
 */
public final class VerticalFeedActivity extends Activity implements TelegramRepository.Listener {
    private final ArrayList<VideoEntry> queue = new ArrayList<>();
    private final SparseArray<FeedHolder> visible = new SparseArray<>();
    private final Set<Long> requested = new HashSet<>();
    private final Set<Long> pendingThumbnails = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private final LruCache<Long, Bitmap> previewCache = new LruCache<Long, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(Long id, Bitmap image) {
            return Math.max(1, image.getByteCount() / 1024);
        }
    };

    private TelegramRepository repo;
    private ViewPager2 pager;
    private FeedAdapter adapter;
    private FeedHolder active;
    private TextView counter;
    private TextView aspect;
    private int selected;
    private int pagerState = ViewPager2.SCROLL_STATE_IDLE;
    private boolean resumed;
    private boolean fill;
    private boolean userPaused;
    private long lastSave;

    private final Runnable playbackTicker = new Runnable() {
        @Override public void run() {
            if (resumed && active != null && active.boundIndex == selected) {
                VideoView player = active.player;
                int duration = player.getDuration();
                int current = player.getCurrentPosition();
                if (duration > 0) {
                    active.timeline.setProgress((int) Math.min(1000, Math.max(0, 1000L * current / duration)));
                    active.time.setText(time(current) + " / " + time(duration));
                }
                if (player.isPlaying() && android.os.SystemClock.elapsedRealtime() - lastSave >= 8000) {
                    savePosition(active);
                    lastSave = android.os.SystemClock.elapsedRealtime();
                }
            }
            ui.postDelayed(this, 350);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repo = TelegramRepository.get(getApplicationContext());
        queue.addAll(repo.playback());
        long desired = getIntent().getLongExtra("message_id", 0L);
        if (state != null) desired = state.getLong("message_id", desired);
        selected = find(desired);
        if (selected < 0) {
            queue.clear();
            queue.addAll(repo.snapshot());
            selected = find(desired);
        }
        if (queue.isEmpty()) {
            Toast.makeText(this, "Belum ada video untuk feed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (selected < 0) selected = 0;
        SharedPreferences settings = getSharedPreferences("vaultgram_feed_settings", MODE_PRIVATE);
        fill = settings.getBoolean("fill", false);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        pager.setUserInputEnabled(true);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // Minimal controls float over the pager. Only these small touch areas intercept swipes.
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(9), dp(31), dp(9), dp(8));
        TextView back = chip("‹", 27);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView brand = label("VAULTGRAM  /  FEED", 13);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, -2, 1);
        brandLp.leftMargin = dp(10);
        toolbar.addView(brand, brandLp);
        counter = label("", 12);
        toolbar.addView(counter);
        aspect = chip(fill ? "Penuh" : "Asli", 12);
        LinearLayout.LayoutParams aspectLp = new LinearLayout.LayoutParams(dp(67), dp(43));
        aspectLp.leftMargin = dp(9);
        toolbar.addView(aspect, aspectLp);
        aspect.setOnClickListener(v -> toggleAspect());
        TextView menu = chip("⋮", 24);
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(44), dp(43));
        menuLp.leftMargin = dp(5);
        toolbar.addView(menu, menuLp);
        menu.setOnClickListener(v -> openMenu());
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        setContentView(root);
        adapter = new FeedAdapter();
        pager.setAdapter(adapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                if (position != selected) userPaused = false;
                selected = position;
                updateCounter();
                if (pagerState == ViewPager2.SCROLL_STATE_IDLE) pager.post(() -> activateCurrent());
            }
            @Override public void onPageScrollStateChanged(int state) {
                pagerState = state;
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    if (active != null) {
                        savePosition(active);
                        active.player.pause();
                    }
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    pager.post(() -> activateCurrent());
                }
            }
        });
        pager.setCurrentItem(selected, false);
        updateCounter();
        ui.postDelayed(playbackTicker, 350);
    }

    private int find(long id) {
        for (int i = 0; i < queue.size(); i++)
            if (queue.get(i).messageId == id) return i;
        return -1;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }
    private TextView label(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        return view;
    }
    private TextView chip(String text, int sp) {
        TextView view = label(text, sp);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(0xB3121824, 14));
        return view;
    }
    private static String time(int milliseconds) {
        int seconds = Math.max(0, milliseconds) / 1000;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private void updateCounter() {
        if (counter != null) counter.setText((selected + 1) + " / " + queue.size());
    }

    private void toggleAspect() {
        fill = !fill;
        getSharedPreferences("vaultgram_feed_settings", MODE_PRIVATE).edit()
                .putBoolean("fill", fill).apply();
        aspect.setText(fill ? "Penuh" : "Asli");
        for (int i = 0; i < visible.size(); i++) {
            FeedHolder holder = visible.valueAt(i);
            holder.cover.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP
                                           : ImageView.ScaleType.FIT_CENTER);
            adjustAspect(holder);
        }
    }

    private void openMenu() {
        if (selected < 0 || selected >= queue.size()) return;
        VideoEntry entry = queue.get(selected);
        String favorite = entry.favorite ? "Hapus dari favorit" : "Tambahkan ke favorit";
        new AlertDialog.Builder(this).setTitle("Aksi video")
                .setItems(new String[] {
                    "Buka pemutar lengkap", favorite, fill ? "Tampilan asli" : "Penuhi layar",
                    "Kunci VaultGram"
                }, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            Intent intent = new Intent(this, PlayerActivity.class);
                            intent.putExtra("message_id", entry.messageId);
                            intent.putExtra("feed_mode", false);
                            startActivity(intent);
                            break;
                        case 1:
                            repo.setFavorite(entry, !entry.favorite);
                            Toast.makeText(this, entry.favorite ? "Ditambahkan ke favorit"
                                    : "Dihapus dari favorit", Toast.LENGTH_SHORT).show();
                            break;
                        case 2: toggleAspect(); break;
                        case 3: AppLock.lock(this); break;
                    }
                }).show();
    }

    private FeedHolder at(int index) { return visible.get(index); }

    /**
     * Called only when a page has settled, so the new video doesn't start halfway through a drag.
     */
    private void activateCurrent() {
        if (!resumed || pager == null || pagerState != ViewPager2.SCROLL_STATE_IDLE
                || !AppLock.isUnlocked() || selected < 0 || selected >= queue.size()) return;
        FeedHolder holder = at(selected);
        if (holder == null) return;
        if (active != holder) {
            stopActive();
            active = holder;
            userPaused = false;
            VideoEntry entry = queue.get(selected);
            repo.setWatched(entry, true);
            ViewerExtras.viewed(this, repo.chat(), entry.messageId);
            repo.requestThumbnail(entry);
            if (selected + 1 < queue.size()) {
                repo.requestThumbnail(queue.get(selected + 1));
                if (repo.wifiAvailable() && !ViewerExtras.dataSaver(this))
                    repo.prefetchNeighbor(queue.get(selected + 1));
            }
            if (selected > 0) repo.requestThumbnail(queue.get(selected - 1));
        }
        playSelected(holder);
    }

    private void playSelected(FeedHolder holder) {
        if (active != holder || !resumed || pagerState != ViewPager2.SCROLL_STATE_IDLE) return;
        VideoEntry item = queue.get(selected);
        Uri uri = item.playableUri();
        if (uri == null) {
            holder.showStatus(item.downloading() || item.progress() > 0
                    ? "Mengunduh video... " + item.progress() + "%"
                    : "Menyiapkan video untuk diputar...");
            if (requested.add(item.messageId)) repo.download(item);
            return;
        }
        String target = uri.toString();
        if (target.equals(holder.playingUri)) {
            if (!userPaused && !holder.player.isPlaying() && holder.prepared != null)
                holder.player.start();
            return;
        }
        holder.playingUri = target;
        holder.prepared = null;
        holder.showStatus("Membuka video...");
        holder.player.setVisibility(View.VISIBLE);
        holder.cover.setVisibility(View.VISIBLE);
        holder.player.setVideoURI(uri);
        final long expected = item.messageId;
        holder.player.setOnPreparedListener(mp -> {
            if (active != holder || holder.boundId != expected
                    || selected != holder.boundIndex || !resumed) return;
            holder.prepared = mp;
            holder.videoWidth = mp.getVideoWidth();
            holder.videoHeight = mp.getVideoHeight();
            adjustAspect(holder);
            holder.cover.setVisibility(View.GONE);
            holder.loading.setVisibility(View.GONE);
            mp.setLooping(false);
            int duration = holder.player.getDuration();
            if (item.positionMs > 3000 && (duration <= 0 || item.positionMs < duration - 3000))
                holder.player.seekTo(item.positionMs);
            if (!userPaused) holder.player.start();
        });
        holder.player.setOnCompletionListener(mp -> {
            if (active != holder || selected != holder.boundIndex) return;
            repo.savePosition(queue.get(selected), 0);
            if (selected + 1 < queue.size()) pager.setCurrentItem(selected + 1, true);
            else {
                holder.showStatus("Semua video selesai");
                userPaused = true;
            }
        });
        holder.player.setOnErrorListener((mp, what, extra) -> {
            if (holder == active) holder.showStatus(
                    "Format video tidak didukung. Buka pemutar lengkap untuk opsi lain.");
            return true;
        });
        holder.player.requestFocus();
    }

    private void adjustAspect(FeedHolder holder) {
        if (holder.videoWidth <= 0 || holder.videoHeight <= 0) return;
        holder.root.post(() -> {
            if (holder.videoWidth <= 0 || holder.videoHeight <= 0) return;
            int availableW = holder.root.getWidth();
            int availableH = holder.root.getHeight();
            if (availableW <= 0 || availableH <= 0) return;
            double scale = fill
                    ? Math.max((double) availableW / holder.videoWidth,
                               (double) availableH / holder.videoHeight)
                    : Math.min((double) availableW / holder.videoWidth,
                               (double) availableH / holder.videoHeight);
            int w = Math.max(1, (int) Math.round(holder.videoWidth * scale));
            int h = Math.max(1, (int) Math.round(holder.videoHeight * scale));
            holder.player.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        });
    }

    private void savePosition(FeedHolder holder) {
        if (holder == null || holder.boundIndex < 0 || holder.boundIndex >= queue.size()) return;
        int current = holder.player.getCurrentPosition();
        if (current > 0) repo.savePosition(queue.get(holder.boundIndex), current);
    }

    private void stopActive() {
        if (active == null) return;
        savePosition(active);
        active.player.stopPlayback();
        active.player.setVisibility(View.GONE);
        active.cover.setVisibility(View.VISIBLE);
        active.prepared = null;
        active.playingUri = "";
        active.longSpeed = false;
        active = null;
    }

    private void togglePlay(FeedHolder holder) {
        if (holder != active || holder.prepared == null) return;
        userPaused = holder.player.isPlaying();
        if (userPaused) {
            holder.player.pause();
            savePosition(holder);
        } else holder.player.start();
    }
    private void seek(FeedHolder holder, int by) {
        if (holder != active || holder.prepared == null) return;
        int duration = holder.player.getDuration();
        int desired = Math.max(0, holder.player.getCurrentPosition() + by);
        if (duration > 0) desired = Math.min(duration, desired);
        holder.player.seekTo(desired);
    }
    private void longSpeed(FeedHolder holder, boolean enable) {
        if (holder != active || holder.prepared == null || holder.longSpeed == enable) return;
        holder.longSpeed = enable;
        try {
            holder.prepared.setPlaybackParams(new PlaybackParams().setSpeed(enable ? 2f : 1f));
        } catch (RuntimeException ignored) {
            holder.longSpeed = false;
        }
    }

    private void loadPreview(FeedHolder holder) {
        final long messageId = holder.boundId;
        Bitmap cached = previewCache.get(messageId);
        if (cached != null) {
            holder.cover.setImageBitmap(cached);
            return;
        }
        VideoEntry entry = queue.get(holder.boundIndex);
        if (entry.preview != null && entry.preview.length > 0) {
            try {
                Bitmap mini = BitmapFactory.decodeByteArray(entry.preview, 0, entry.preview.length);
                if (mini != null) holder.cover.setImageBitmap(mini);
            } catch (RuntimeException ignored) { }
        }
        if (pendingThumbnails.contains(messageId)) return;
        pendingThumbnails.add(messageId);
        final String thumbPath = entry.thumbPath();
        final String localPath = entry.localPath();
        thumbnails.execute(() -> {
            Bitmap decoded = null;
            try {
                if (!thumbPath.isEmpty()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    decoded = BitmapFactory.decodeFile(thumbPath, options);
                }
                if (decoded == null && !localPath.isEmpty()) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(localPath);
                        decoded = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } finally {
                        try { retriever.release(); } catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) { }
            final Bitmap result = decoded;
            ui.post(() -> {
                pendingThumbnails.remove(messageId);
                if (result != null) previewCache.put(messageId, result);
                if (result != null && holder.boundId == messageId && holder.cover.getVisibility() == View.VISIBLE)
                    holder.cover.setImageBitmap(result);
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (selected >= 0 && selected < queue.size())
            out.putLong("message_id", queue.get(selected).messageId);
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        repo.bind(this);
        AppLock.gate(this);
        pager.post(this::activateCurrent);
    }
    @Override protected void onPause() {
        resumed = false;
        if (active != null) {
            savePosition(active);
            active.player.pause();
            longSpeed(active, false);
        }
        repo.unbind(this);
        super.onPause();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(playbackTicker);
        stopActive();
        thumbnails.shutdownNow();
        super.onDestroy();
    }
    @Override public void onStage(TelegramRepository.Stage stage, String message) {}
    @Override public void onAccount(String account) {}
    @Override public void onBot(String username) {}
    @Override public void onStatus(String message) {}
    @Override public void onVideosChanged() {
        if (!resumed || pager == null) return;
        FeedHolder current = at(selected);
        if (current != null && current == active) playSelected(current);
        for (int i = 0; i < visible.size(); i++) {
            FeedHolder holder = visible.valueAt(i);
            if (holder != null && holder.boundIndex >= 0 && holder.boundIndex < queue.size()) {
                VideoEntry entry = queue.get(holder.boundIndex);
                if (entry.thumbPath() != null && !entry.thumbPath().isEmpty()
                        && previewCache.get(entry.messageId) == null
                        && !pendingThumbnails.contains(entry.messageId))
                    loadPreview(holder);
            }
        }
    }
    @Override public void onError(String message) {
        if (active != null) active.showStatus(message == null ? "Gagal mengunduh video" : message);
    }

    private final class FeedAdapter extends RecyclerView.Adapter<FeedHolder> {
        FeedAdapter() { setHasStableIds(true); }
        @Override public long getItemId(int position) { return queue.get(position).messageId; }
        @Override public int getItemCount() { return queue.size(); }

        @NonNull @Override public FeedHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            FrameLayout page = new FrameLayout(VerticalFeedActivity.this);
            page.setBackgroundColor(Color.BLACK);
            page.setClipChildren(true);
            page.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            return new FeedHolder(page);
        }

        @Override public void onBindViewHolder(@NonNull FeedHolder holder, int position) {
            if (active == holder) stopActive();
            holder.player.stopPlayback();
            holder.player.setVisibility(View.GONE);
            holder.cover.setVisibility(View.VISIBLE);
            holder.cover.setImageDrawable(null);
            holder.boundIndex = position;
            holder.boundId = queue.get(position).messageId;
            holder.prepared = null;
            holder.playingUri = "";
            holder.videoWidth = 0;
            holder.videoHeight = 0;
            holder.longSpeed = false;
            holder.caption.setText(queue.get(position).title);
            holder.time.setText("00:00 / " + queue.get(position).durationLabel());
            holder.cover.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP
                                           : ImageView.ScaleType.FIT_CENTER);
            holder.showStatus("Geser ke atas untuk video berikutnya");
            loadPreview(holder);
        }

        @Override public void onViewAttachedToWindow(@NonNull FeedHolder holder) {
            super.onViewAttachedToWindow(holder);
            visible.put(holder.boundIndex, holder);
            if (holder.boundIndex == selected && pagerState == ViewPager2.SCROLL_STATE_IDLE)
                pager.post(() -> activateCurrent());
        }
        @Override public void onViewDetachedFromWindow(@NonNull FeedHolder holder) {
            visible.remove(holder.boundIndex);
            if (active == holder) stopActive();
            holder.player.stopPlayback();
            super.onViewDetachedFromWindow(holder);
        }
        @Override public void onViewRecycled(@NonNull FeedHolder holder) {
            if (active == holder) stopActive();
            holder.player.stopPlayback();
            super.onViewRecycled(holder);
        }
    }

    private final class FeedHolder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final ImageView cover;
        final VideoView player;
        final TextView caption;
        final TextView loading;
        final TextView time;
        final SeekBar timeline;
        final GestureDetector gestures;
        MediaPlayer prepared;
        int boundIndex = -1;
        long boundId = Long.MIN_VALUE;
        int videoWidth, videoHeight;
        String playingUri = "";
        boolean longSpeed;
        boolean tracking;

        FeedHolder(FrameLayout page) {
            super(page);
            root = page;
            player = new VideoView(VerticalFeedActivity.this);
            root.addView(player, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            cover = new ImageView(VerticalFeedActivity.this);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(cover, new FrameLayout.LayoutParams(-1, -1));

            loading = label("Menyiapkan video...", 13);
            loading.setGravity(Gravity.CENTER);
            loading.setPadding(dp(17), dp(11), dp(17), dp(11));
            loading.setBackground(rounded(0xC9141824, 14));
            root.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            LinearLayout footer = new LinearLayout(VerticalFeedActivity.this);
            footer.setOrientation(LinearLayout.VERTICAL);
            footer.setPadding(dp(18), dp(12), dp(18), dp(39));
            footer.setBackgroundColor(0x77000000);
            caption = label("", 16);
            caption.setMaxLines(2);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            caption.setTypeface(null, android.graphics.Typeface.BOLD);
            footer.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            TextView hint = label("↑ Berikutnya      ↓ Sebelumnya     Tahan: 2×", 11);
            hint.setTextColor(0xFFD0DAE7);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
            hintLp.topMargin = dp(6);
            footer.addView(hint, hintLp);
            time = label("00:00 / 00:00", 11);
            LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(-1, -2);
            timeLp.topMargin = dp(6);
            footer.addView(time, timeLp);
            timeline = new SeekBar(VerticalFeedActivity.this);
            timeline.setMax(1000);
            footer.addView(timeline, new LinearLayout.LayoutParams(-1, dp(29)));
            root.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar seek) { tracking = true; }
                @Override public void onProgressChanged(SeekBar seek, int progress, boolean user) {
                    if (user && prepared != null && player.getDuration() > 0)
                        time.setText(time((int) (player.getDuration() * (progress / 1000f)))
                                + " / " + time(player.getDuration()));
                }
                @Override public void onStopTrackingTouch(SeekBar seek) {
                    tracking = false;
                    if (active == FeedHolder.this && prepared != null && player.getDuration() > 0)
                        player.seekTo((int) (player.getDuration() * (seek.getProgress() / 1000f)));
                }
            });

            gestures = new GestureDetector(VerticalFeedActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (active != FeedHolder.this) return false;
                    seek(FeedHolder.this, e.getX() < root.getWidth() / 2f ? -10000 : 10000);
                    return true;
                }
                @Override public void onLongPress(MotionEvent e) {
                    if (active == FeedHolder.this && player.isPlaying()) longSpeed(FeedHolder.this, true);
                }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    togglePlay(FeedHolder.this);
                    return true;
                }
            });
            View.OnTouchListener touch = (view, motion) -> {
                gestures.onTouchEvent(motion);
                if (motion.getActionMasked() == MotionEvent.ACTION_UP
                        || motion.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    longSpeed(FeedHolder.this, false);
                // Do not requestDisallowInterceptTouchEvent: ViewPager2 owns vertical drags.
                return true;
            };
            player.setOnTouchListener(touch);
            cover.setOnTouchListener(touch);
            root.setOnTouchListener(touch);
        }

        void showStatus(String message) {
            loading.setText(message);
            loading.setVisibility(View.VISIBLE);
        }
    }
}
