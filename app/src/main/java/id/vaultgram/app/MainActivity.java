package id.vaultgram.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android UI built with the SDK view system; only one TDLib bot conversation appears. */
public final class MainActivity extends Activity implements TelegramRepository.Listener {
    private static final int BG = Color.rgb(10, 16, 32);
    private static final int PANEL = Color.rgb(23, 34, 54);
    private static final int PANEL2 = Color.rgb(30, 43, 64);
    private static final int TEXT = Color.rgb(240, 244, 251);
    private static final int MUTED = Color.rgb(148, 163, 184);
    private static final int ACCENT = Color.rgb(107, 224, 202);
    private static final int SCAN_PERMISSION = 614;
    private final int[] filters = {0, 1, 2};
    private FrameLayout root;
    private TelegramRepository repo;
    private TelegramRepository.Stage currentStage;
    private boolean galleryVisible;
    private TextView botText, accountText, statsText, statusText, autoWifiText;
    private TextView[] filterTabs;
    private GalleryAdapter adapter;
    private int filter;
    private String searchText = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        repo = TelegramRepository.get(getApplicationContext());
        try {
            repo.bind(this);
            if (repo.hasApiCredentials()) repo.connect();
            else if (currentStage == null) renderAuth(TelegramRepository.Stage.SETUP, "");
        } catch (Exception e) {
            showFailure(e.getMessage());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (repo != null) {
            repo.bind(this);
            if (galleryVisible) refreshGrid();
        }
        AppLock.gate(this);
    }

    @Override protected void onDestroy() {
        if (repo != null) repo.unbind(this);
        if (adapter != null) adapter.close();
        super.onDestroy();
    }

    @Override public void onStage(TelegramRepository.Stage stage, String message) {
        if (stage == currentStage && (galleryVisible == (stage == TelegramRepository.Stage.READY))) return;
        currentStage = stage;
        if (stage == TelegramRepository.Stage.READY) renderGallery();
        else renderAuth(stage, message);
    }
    @Override public void onAccount(String account) {
        if (accountText != null) accountText.setText(account);
    }
    @Override public void onBot(String username) {
        if (botText != null) botText.setText("@" + username);
    }
    @Override public void onVideosChanged() { refreshGrid(); }
    @Override public void onStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }
    @Override public void onError(String message) { showFailure(message); }

    private void showFailure(String message) {
        if (message == null) message = "Terjadi kesalahan";
        if (statusText != null && galleryVisible) statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void renderAuth(TelegramRepository.Stage stage, String detail) {
        galleryVisible = false;
        botText = accountText = statsText = statusText = autoWifiText = null;
        if (adapter != null) adapter.close();
        adapter = null;
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = column();
        page.setPadding(dp(26), dp(36), dp(26), dp(30));
        scroll.addView(page);
        TextView icon = label("▶", 31, BG, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(ACCENT, 21));
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(66), dp(66));
        iconSize.bottomMargin = dp(18);
        page.addView(icon, iconSize);
        page.addView(label("VaultGram", 34, TEXT, true));
        TextView sub = label("Galeri Telegram untuk satu bot pilihan", 14, MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(8);
        subLp.bottomMargin = dp(36);
        page.addView(sub, subLp);

        LinearLayout card = column();
        card.setPadding(dp(22), dp(24), dp(22), dp(25));
        card.setBackground(round(PANEL, 24));
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));
        String title;
        switch (stage) {
            case SETUP: title = "Hubungkan Telegram"; break;
            case PHONE: title = "Nomor Telegram"; break;
            case CODE: title = "Kode verifikasi"; break;
            case PASSWORD: title = "Verifikasi 2 langkah"; break;
            case EMAIL: title = "Verifikasi email"; break;
            case EMAIL_CODE: title = "Kode dari email"; break;
            case OTHER: title = "Konfirmasi diperlukan"; break;
            default: title = "Menghubungkan…";
        }
        card.addView(label(title, 22, TEXT, true));
        TextView desc = label(detail.isEmpty() ? "Login aman ke akun Telegram pribadi lu." : detail,
                13, MUTED, false);
        desc.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = dp(9);
        descLp.bottomMargin = dp(19);
        card.addView(desc, descLp);

        if (stage == TelegramRepository.Stage.SETUP) {
            EditText id = input("API ID (angka)", InputType.TYPE_CLASS_NUMBER, repo.prefs.get("api_id"));
            EditText hash = input("API Hash", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    repo.prefs.get("api_hash"));
            EditText phone = input("Nomor, contoh +628xxxxxxxx", InputType.TYPE_CLASS_PHONE,
                    repo.prefs.get("phone"));
            EditText bot = input("Username bot, contoh @video_bot", InputType.TYPE_CLASS_TEXT,
                    repo.prefs.get("bot"));
            addInput(card, "TELEGRAM API ID", id);
            addInput(card, "TELEGRAM API HASH", hash);
            addInput(card, "NOMOR TELEGRAM", phone);
            addInput(card, "BOT PILIHAN", bot);
            action(card, "Hubungkan akun", () -> {
                if (id.getText().toString().trim().isEmpty() || hash.getText().toString().trim().isEmpty()
                        || phone.getText().toString().trim().isEmpty()) {
                    showFailure("API ID, API Hash, dan nomor wajib diisi."); return;
                }
                try {
                    Integer.parseInt(id.getText().toString().trim());
                    repo.configureAndConnect(id.getText().toString(), hash.getText().toString(),
                            phone.getText().toString(), bot.getText().toString());
                } catch (Exception e) { showFailure("API ID harus angka yang valid."); }
            });
        } else if (stage == TelegramRepository.Stage.PHONE) {
            EditText field = input("+628xxxxxxxx", InputType.TYPE_CLASS_PHONE, repo.prefs.get("phone"));
            addInput(card, "NOMOR TELEGRAM", field);
            action(card, "Kirim kode login", () -> repo.sendPhone(field.getText().toString()));
        } else if (stage == TelegramRepository.Stage.CODE) {
            EditText field = input("Kode login", InputType.TYPE_CLASS_NUMBER, "");
            addInput(card, "KODE VERIFIKASI", field);
            action(card, "Verifikasi kode", () -> repo.sendCode(field.getText().toString()));
        } else if (stage == TelegramRepository.Stage.PASSWORD) {
            EditText field = input("Password Telegram", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
            addInput(card, "PASSWORD 2FA", field);
            action(card, "Lanjutkan", () -> repo.sendPassword(field.getText().toString()));
        } else if (stage == TelegramRepository.Stage.EMAIL) {
            EditText field = input("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "");
            addInput(card, "EMAIL", field);
            action(card, "Kirim kode email", () -> repo.sendEmail(field.getText().toString()));
        } else if (stage == TelegramRepository.Stage.EMAIL_CODE) {
            EditText field = input("Kode email", InputType.TYPE_CLASS_TEXT, "");
            addInput(card, "KODE EMAIL", field);
            action(card, "Verifikasi email", () -> repo.sendEmailCode(field.getText().toString()));
        } else if (stage == TelegramRepository.Stage.CONNECTING) {
            ProgressBar progress = new ProgressBar(this);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(42), dp(42));
            l.topMargin = dp(10);
            card.addView(progress, l);
        }
        TextView hint = label("API ID & API Hash diambil dari my.telegram.org. Kode OTP dan password tidak dikirim ke server lain oleh aplikasi ini.",
                12, MUTED, false);
        hint.setLineSpacing(dp(4), 1);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(25);
        page.addView(hint, hintLp);
    }

    private void renderGallery() {
        if (adapter != null) adapter.close();
        galleryVisible = true;
        root.removeAllViews();
        LinearLayout page = column();
        page.setBackgroundColor(BG);
        page.setPadding(dp(17), dp(12), dp(17), dp(10));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView glyph = label("▶", 19, BG, true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(round(ACCENT, 13));
        header.addView(glyph, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout brand = column();
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, -2, 1);
        brandLp.leftMargin = dp(12);
        header.addView(brand, brandLp);
        brand.addView(label("VaultGram", 24, TEXT, true));
        brand.addView(label("GALERI VIDEO BOT", 10, ACCENT, true));
        TextView lock = label("Kunci", 12, ACCENT, true);
        lock.setGravity(Gravity.CENTER);
        lock.setBackground(round(PANEL, 14));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(56), dp(44));
        lockLp.rightMargin = dp(8);
        header.addView(lock, lockLp);
        lock.setOnClickListener(v -> AppLock.change(this));
        TextView refresh = label("↻", 30, TEXT, true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(round(PANEL, 14));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        header.addView(refresh, refreshLp);
        refresh.setOnClickListener(v -> {
            if (repo.hasBot()) repo.sync(); else promptBot();
        });
        page.addView(header);

        LinearLayout source = row();
        source.setGravity(Gravity.CENTER_VERTICAL);
        source.setPadding(dp(17), dp(17), dp(15), dp(17));
        source.setBackground(round(PANEL, 21));
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(-1, -2);
        srcLp.topMargin = dp(24);
        page.addView(source, srcLp);
        LinearLayout left = column();
        source.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(label("SUMBER VIDEO", 11, ACCENT, true));
        botText = label(repo.bot().isEmpty() ? "Pilih bot Telegram" : "@" + repo.bot(), 19, TEXT, true);
        botText.setSingleLine(true);
        botText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(-1, -2);
        botLp.topMargin = dp(6);
        left.addView(botText, botLp);
        accountText = label("1 akun Telegram terhubung", 12, MUTED, false);
        LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(-1, -2);
        accLp.topMargin = dp(5);
        left.addView(accountText, accLp);
        TextView choose = label("UBAH  ›", 11, ACCENT, true);
        source.addView(choose);
        source.setOnClickListener(v -> promptBot());

        LinearLayout autoRow = row();
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(dp(13), dp(11), dp(13), dp(11));
        autoRow.setBackground(round(PANEL, 14));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(-1, -2);
        autoLp.topMargin = dp(11);
        page.addView(autoRow, autoLp);
        TextView description = label("Auto-download video baru", 12, TEXT, true);
        autoRow.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        autoWifiText = label("", 11, ACCENT, true);
        autoRow.addView(autoWifiText);
        autoRow.setOnClickListener(v -> {
            repo.setAutoWifiEnabled(!repo.autoWifiEnabled());
            updateAutoWifi();
        });
        updateAutoWifi();

        LinearLayout scanRow = row();
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        statsText = label("0 video", 13, MUTED, false);
        scanRow.addView(statsText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView scan = label("Pindai unduhan", 12, ACCENT, true);
        scan.setPadding(dp(8), dp(12), dp(3), dp(12));
        scanRow.addView(scan);
        scan.setOnClickListener(v -> scanDownloads());
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(-1, -2);
        statsLp.topMargin = dp(15);
        statsLp.bottomMargin = dp(10);
        page.addView(scanRow, statsLp);

        LinearLayout tabs = row();
        filterTabs = new TextView[3];
        String[] labels = {"Semua", "Diunduh", "Belum"};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            TextView tab = label(labels[i], 13, MUTED, true);
            tab.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            if (i > 0) lp.leftMargin = dp(8);
            tabs.addView(tab, lp);
            filterTabs[i] = tab;
            tab.setOnClickListener(v -> { filter = filters[f]; updateTabs(); refreshGrid(); });
        }
        page.addView(tabs);
        updateTabs();

        EditText search = input("Cari nama video…", InputType.TYPE_CLASS_TEXT, searchText);
        search.setSingleLine(true);
        search.setCompoundDrawablePadding(dp(9));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(47));
        searchLp.topMargin = dp(15);
        searchLp.bottomMargin = dp(9);
        page.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchText = s.toString(); refreshGrid();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });

        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setVerticalSpacing(dp(14));
        grid.setHorizontalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(5), 0, dp(5));
        grid.setSelector(android.R.color.transparent);
        adapter = new GalleryAdapter();
        grid.setAdapter(adapter);
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        statusText = label("Siap", 11, MUTED, false);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, dp(27));
        stLp.topMargin = dp(3);
        page.addView(statusText, stLp);
        refreshGrid();
    }

    private void refreshGrid() {
        if (repo == null || adapter == null || !galleryVisible) return;
        List<VideoEntry> videos = repo.snapshot();
        int downloaded = 0;
        for (VideoEntry v : videos) if (v.complete()) downloaded++;
        statsText.setText(videos.size() + " video   ·   " + downloaded + " diunduh   ·   " +
                (videos.size() - downloaded) + " belum" + (repo.syncing() ? "   ·   Menyinkronkan…" : ""));
        adapter.update(videos, filter, searchText);
        updateAutoWifi();
    }

    private void updateAutoWifi() {
        if (autoWifiText == null || repo == null) return;
        autoWifiText.setText(!repo.autoWifiEnabled() ? "MATI · Ketuk" :
                repo.wifiAvailable() ? "Wi-Fi saja · AKTIF" : "Wi-Fi saja · MENUNGGU");
        autoWifiText.setTextColor(repo.autoWifiEnabled() ? ACCENT : MUTED);
    }

    private void updateTabs() {
        if (filterTabs == null) return;
        for (int i = 0; i < filterTabs.length; i++) {
            boolean selected = filter == i;
            filterTabs[i].setTextColor(selected ? BG : MUTED);
            filterTabs[i].setBackground(round(selected ? ACCENT : PANEL, 14));
        }
    }

    private void promptBot() {
        EditText field = input("@username_bot", InputType.TYPE_CLASS_TEXT, repo.bot());
        LinearLayout box = column();
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(field);
        new AlertDialog.Builder(this)
            .setTitle("Pilih 1 bot Telegram")
            .setMessage("Masukkan username bot yang sudah pernah lu chat di Telegram.")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Tampilkan video", (d, w) -> repo.selectBot(field.getText().toString()))
            .show();
    }

    private void onVideoPressed(VideoEntry entry) {
        if (adapter == null) return;
        repo.setPlayback(adapter.displayed());
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("message_id", entry.messageId);
        startActivity(intent);
    }

    private void scanDownloads() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                          : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                .setTitle("Deteksi video yang sudah diunduh")
                .setMessage("Izinkan akses video HP supaya VaultGram bisa menemukan salinan di Downloads atau folder Telegram. Cache privat Telegram tidak dapat dibaca.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Izinkan", (d, w) -> requestPermissions(new String[]{permission}, SCAN_PERMISSION))
                .show();
            return;
        }
        repo.scanDownloads();
        Toast.makeText(this, "Memindai video yang tersimpan di HP…", Toast.LENGTH_SHORT).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != SCAN_PERMISSION) return;
        repo.scanDownloads();
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Izin belum diberikan; hanya file yang bisa diakses yang akan ditemukan.", Toast.LENGTH_LONG).show();
    }

    private void saveToPhone(VideoEntry entry) {
        final String path = entry.localPath();
        if (path.isEmpty()) return;
        if (Build.VERSION.SDK_INT < 29) {
            showFailure("Ekspor ke Downloads memerlukan Android 10 atau lebih baru."); return;
        }
        Toast.makeText(this, "Menyimpan ke Downloads…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Uri uri = null;
            try {
                String base = entry.title.replaceAll("[^a-zA-Z0-9._ -]", "_");
                if (base.isEmpty()) base = "video_" + entry.messageId + ".mp4";
                if (!base.matches("(?i).*\\.(mp4|mkv|mov|webm|avi|m4v|gif)$")) base += ".mp4";
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, base);
                String ext = base.substring(base.lastIndexOf('.') + 1).toLowerCase(Locale.US);
                values.put(MediaStore.MediaColumns.MIME_TYPE,
                        ext.equals("mkv") ? "video/x-matroska" : ext.equals("webm") ? "video/webm" : "video/mp4");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VaultGram");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore gagal membuat berkas");
                try (FileInputStream in = new FileInputStream(path);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Tidak bisa menulis ke Downloads");
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, complete, null, null);
                Uri savedUri = uri;
                runOnUiThread(() -> {
                    repo.rememberExport(entry, savedUri);
                    Toast.makeText(this, "Tersimpan di Downloads/VaultGram", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                String msg = e.getMessage();
                runOnUiThread(() -> showFailure("Gagal menyimpan: " + msg));
            }
        }, "VaultGram-SaveToPhone").start();
    }

    private final class GalleryAdapter extends BaseAdapter {
        private final ArrayList<VideoEntry> shown = new ArrayList<>();
        private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
        private final Set<Integer> frameRequests = new HashSet<>();
        List<VideoEntry> displayed() { return new ArrayList<>(shown); }
        void close() { thumbs.shutdownNow(); }
        private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(24 * 1024) {
            @Override protected int sizeOf(Integer key, Bitmap bm) { return Math.max(1, bm.getByteCount() / 1024); }
        };

        void update(List<VideoEntry> originals, int filter, String text) {
            shown.clear();
            String q = text.trim().toLowerCase(Locale.ROOT);
            for (VideoEntry v : originals) {
                if (filter == 1 && !v.complete()) continue;
                if (filter == 2 && v.complete()) continue;
                if (!q.isEmpty() && !v.title.toLowerCase(Locale.ROOT).contains(q)) continue;
                shown.add(v);
            }
            notifyDataSetChanged();
        }
        private void requestLocalFrame(VideoEntry e, int cacheKey) {
            if (!frameRequests.add(cacheKey)) return;
            String path = e.localPath();
            Uri uri = e.playableUri();
            thumbs.execute(() -> {
                Bitmap bitmap = null;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    if (!path.isEmpty()) retriever.setDataSource(path);
                    else if (uri != null) retriever.setDataSource(MainActivity.this, uri);
                    Bitmap full = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (full != null) {
                        bitmap = Bitmap.createScaledBitmap(full, dp(240), dp(145), true);
                        if (bitmap != full) full.recycle();
                    }
                } catch (Exception ignored) { }
                finally { retriever.release(); }
                Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> {
                    if (adapter != this || !galleryVisible) return;
                    if (finalBitmap != null) { cache.put(cacheKey, finalBitmap); notifyDataSetChanged(); }
                });
            });
        }

        @Override public int getCount() { return shown.size(); }
        @Override public VideoEntry getItem(int n) { return shown.get(n); }
        @Override public long getItemId(int n) { return getItem(n).messageId; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout card = column();
                card.setBackground(round(PANEL, 17));
                card.setClipToOutline(true);
                card.setLayoutParams(new GridView.LayoutParams(-1, dp(231)));
                FrameLayout preview = new FrameLayout(MainActivity.this);
                preview.setBackgroundColor(PANEL2);
                card.addView(preview, new LinearLayout.LayoutParams(-1, dp(149)));
                ImageView cover = new ImageView(MainActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.addView(cover, new FrameLayout.LayoutParams(-1, -1));
                TextView play = label("▶", 17, Color.WHITE, true);
                play.setGravity(Gravity.CENTER);
                play.setBackground(round(Color.argb(175, 10, 16, 32), 50));
                FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(43), dp(43), Gravity.CENTER);
                preview.addView(play, playLp);
                TextView duration = label("00:00", 11, TEXT, true);
                duration.setPadding(dp(7), dp(3), dp(7), dp(3));
                duration.setBackground(round(Color.argb(210, 10, 16, 32), 7));
                FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
                durLp.setMargins(0, 0, dp(8), dp(8));
                preview.addView(duration, durLp);
                LinearLayout caption = column();
                caption.setPadding(dp(11), dp(9), dp(11), dp(7));
                card.addView(caption, new LinearLayout.LayoutParams(-1, -2));
                TextView title = label("Video", 12, TEXT, true);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                caption.addView(title);
                TextView status = label("◌ Belum diunduh", 11, MUTED, false);
                LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
                statusLp.topMargin = dp(8);
                caption.addView(status, statusLp);
                holder = new Holder(cover, duration, title, status);
                card.setTag(holder);
                convertView = card;
            } else { holder = (Holder) convertView.getTag(); }
            VideoEntry e = getItem(position);
            holder.title.setText(e.title);
            holder.duration.setText(e.durationLabel());
            if (e.complete()) {
                holder.status.setText("✓ Diunduh · Putar");
                holder.status.setTextColor(ACCENT);
            } else if (e.downloading() || e.progress() > 0) {
                holder.status.setText("↓ " + e.progress() + "% · Mengunduh");
                holder.status.setTextColor(Color.rgb(255, 205, 112));
            } else {
                holder.status.setText("↓ Belum diunduh");
                holder.status.setTextColor(MUTED);
            }
            int highKey = e.thumb != null ? e.thumb.id : e.fileId;
            Bitmap bitmap = cache.get(highKey);
            if (bitmap == null && e.thumb != null && e.thumb.local != null &&
                    e.thumb.local.isDownloadingCompleted && e.thumb.local.path != null &&
                    new File(e.thumb.local.path).isFile()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                bitmap = BitmapFactory.decodeFile(e.thumb.local.path, options);
                if (bitmap != null) cache.put(highKey, bitmap);
            }
            if (bitmap == null && e.preview != null && e.preview.length > 0) {
                int previewKey = -Math.abs(highKey);
                bitmap = cache.get(previewKey);
                if (bitmap == null) {
                    bitmap = BitmapFactory.decodeByteArray(e.preview, 0, e.preview.length);
                    if (bitmap != null) cache.put(previewKey, bitmap);
                }
            }
            holder.image.setImageBitmap(bitmap);
            // Even when a low-res minithumbnail exists, download its full-resolution replacement.
            repo.requestThumbnail(e);
            if (cache.get(highKey) == null && e.complete() &&
                    (e.thumb == null || e.thumb.local == null || !e.thumb.local.isDownloadingCompleted))
                requestLocalFrame(e, highKey);
            convertView.setOnClickListener(v -> onVideoPressed(e));
            convertView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(MainActivity.this).setTitle(e.title)
                    .setItems(new String[]{"Simpan ke Downloads HP", "Putar"}, (d, choice) -> {
                        if (choice == 0) {
                            if (!e.localPath().isEmpty()) saveToPhone(e);
                            else if (e.complete()) Toast.makeText(MainActivity.this,
                                "Video sudah ada di penyimpanan HP", Toast.LENGTH_SHORT).show();
                            else { repo.download(e); Toast.makeText(MainActivity.this,
                                "Mengunduh video…", Toast.LENGTH_SHORT).show(); }
                        } else onVideoPressed(e);
                    }).show();
                return true;
            });
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView image;
        final TextView duration, title, status;
        Holder(ImageView image, TextView duration, TextView title, TextView status) {
            this.image = image; this.duration = duration; this.title = title; this.status = status;
        }
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(radius));
        return bg;
    }
    private EditText input(String hint, int type, String initial) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setInputType(type);
        field.setHint(hint);
        field.setText(initial);
        field.setPadding(dp(13), dp(9), dp(13), dp(9));
        field.setBackground(round(PANEL2, 11));
        return field;
    }
    private void addInput(LinearLayout box, String hint, EditText field) {
        TextView title = label(hint, 11, MUTED, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(8);
        titleLp.bottomMargin = dp(6);
        box.addView(title, titleLp);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.bottomMargin = dp(12);
        box.addView(field, fieldLp);
    }
    private void action(LinearLayout box, String title, Runnable click) {
        TextView b = label(title, 15, BG, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(ACCENT, 13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(53));
        lp.topMargin = dp(12);
        box.addView(b, lp);
        b.setOnClickListener(v -> click.run());
    }
}
