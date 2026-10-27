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
import android.widget.HorizontalScrollView;
import android.widget.VideoView;
import android.os.StatFs;
import android.os.Environment;
import android.os.Handler;
import android.content.DialogInterface;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;
import android.content.ActivityNotFoundException;

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
    private static final int BACKUP_CREATE = 2111;
    private static final int BACKUP_OPEN = 2112;
    private boolean backupWithVideos;
    private String backupPassword;
    private int sortBy;
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
            if (repo.hasCachedGallery()) renderGallery();
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
        if (stage == TelegramRepository.Stage.READY ||
            (repo != null && repo.hasCachedGallery() && (stage == TelegramRepository.Stage.CONNECTING || stage == TelegramRepository.Stage.SETUP))) {
            if (!galleryVisible) renderGallery();
            else if (statusText != null) statusText.setText(stage == TelegramRepository.Stage.READY ? "Tersambung" : "Galeri offline · menghubungkan…");
        } else renderAuth(stage, message);
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
        TextView settings = label("⚙", 25, ACCENT, true);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(round(PANEL, 14));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lockLp.rightMargin = dp(8);
        header.addView(settings, lockLp);
        settings.setOnClickListener(v -> openSettings());
        TextView refresh = label("↻", 30, TEXT, true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(round(PANEL, 14));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        header.addView(refresh, refreshLp);
        refresh.setOnClickListener(v -> {
            if (repo.hasBot()) repo.syncRecent(); else promptBot();
        });
        refresh.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Sinkronisasi penuh?")
                .setMessage("Baca ulang seluruh riwayat. Galeri lama tetap ditampilkan dan status unduhan tidak dihapus.")
                .setNegativeButton("Batal",null).setPositiveButton("Mulai",(d,w)->repo.fullSync()).show();
            return true;
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
        HorizontalScrollView shortcuts = new HorizontalScrollView(this);
        shortcuts.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = row();
        actions.setPadding(0,dp(12),0,dp(2));
        addChip(actions,"▶ Feed",()->openFeed(false));
        addChip(actions,"⇄ Acak",()->openFeed(true));
        addChip(actions,"★ Favorit",()->setExtraFilter(3));
        addChip(actions,"◷ Belum dilihat",()->setExtraFilter(4));
        addChip(actions,"◫ Sampah",()->setExtraFilter(5));
        addChip(actions,"◉ Tersembunyi",()->setExtraFilter(6));
        addChip(actions,"◷ Riwayat",this::showHistory);
        addChip(actions,"▣ Vault privat",()->startActivity(new Intent(this,PrivateMediaActivity.class)));
        addChip(actions,"↓ Massal",this::downloadBatch);
        addChip(actions,"↓ Antrean",this::downloadManager);
        shortcuts.addView(actions);
        page.addView(shortcuts);

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
        int trash=0,favorites=0;
        for(VideoEntry v:videos){if(v.trashedAt>0)trash++;if(v.favorite)favorites++;}
        statsText.setText((videos.size()-trash) + " video · " + downloaded + " diunduh · " + favorites + " favorit"+
                (repo.newVideos()>0 ? " · "+repo.newVideos()+" baru" : "")+
                (repo.syncing() ? " · Cek terbaru…" : ""));
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

    private void addChip(LinearLayout parent,String title,Runnable action){
        TextView chip=label(title,12,TEXT,true);
        chip.setPadding(dp(13),dp(10),dp(13),dp(10));chip.setBackground(round(PANEL2,14));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(8);
        parent.addView(chip,lp);chip.setOnClickListener(v->action.run());
    }
    private void setExtraFilter(int f){
        filter=f;updateTabs();refreshGrid();
        Toast.makeText(this,f==3?"Filter favorit":f==4?"Filter belum dilihat":f==5?"Sampah (7 hari)":"Video tersembunyi",Toast.LENGTH_SHORT).show();
    }
    private void openFeed(boolean random){
        if(adapter==null || adapter.getCount()==0){showFailure("Belum ada video untuk diputar");return;}
        List<VideoEntry> play=adapter.displayed();
        if(random)Collections.shuffle(play);
        repo.setPlayback(play);
        Intent intent=new Intent(this,PlayerActivity.class);
        intent.putExtra("message_id",play.get(0).messageId);
        intent.putExtra("feed_mode",true);
        startActivity(intent);
    }
    private void showHistory(){
        List<VideoEntry> history=new ArrayList<>();
        for(VideoEntry v:repo.snapshot())if(ViewerExtras.viewedAt(this,repo.chat(),v.messageId)>0)
            history.add(v);
        history.sort((a,b)->Long.compare(ViewerExtras.viewedAt(this,repo.chat(),b.messageId),
            ViewerExtras.viewedAt(this,repo.chat(),a.messageId)));
        if(history.isEmpty()) {showFailure("Belum ada riwayat menonton");return;}
        int count=Math.min(100,history.size());String[] choices=new String[count];
        for(int i=0;i<count;i++)choices[i]=history.get(i).title;
        new AlertDialog.Builder(this).setTitle("Riwayat video ("+history.size()+")")
            .setItems(choices,(d,w)->onVideoPressed(history.get(w)))
            .setNeutralButton("Hapus riwayat",(d,w)->new AlertDialog.Builder(this)
                .setTitle("Hapus riwayat menonton?")
                .setNegativeButton("Batal",null)
                .setPositiveButton("Hapus",(dialog,which)->ViewerExtras.clearHistory(this,repo.chat(),repo.snapshot()))
                .show())
            .setNegativeButton("Tutup",null).show();
    }
    private void showPreview(VideoEntry e){
        Uri uri=e.playableUri();
        if(uri==null){
            new AlertDialog.Builder(this).setTitle(e.title).setMessage("Preview video memerlukan file yang sudah tersimpan. Thumbnail tetap bisa dilihat di galeri.")
                .setPositiveButton("Unduh",(d,w)->repo.download(e)).setNegativeButton("Batal",null).show();
            return;
        }
        VideoView v=new VideoView(this);
        LinearLayout box=column();box.setPadding(dp(8),dp(4),dp(8),dp(4));
        box.addView(v,new LinearLayout.LayoutParams(-1,dp(260)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Preview · "+e.title)
            .setView(box).setPositiveButton("Putar penuh",(d,w)->onVideoPressed(e))
            .setNegativeButton("Tutup",null).create();
        dialog.setOnDismissListener(d->v.stopPlayback());
        dialog.show();v.setVideoURI(uri);
        v.setOnPreparedListener(mp->{mp.setLooping(false);v.start();});
        v.setOnErrorListener((mp,a,b)->{showFailure("Preview tidak didukung untuk format ini");return true;});
        new Handler().postDelayed(()->{if(dialog.isShowing() && v.isPlaying())v.pause();},8500);
    }
    private void showVideoMenu(VideoEntry e){
        String[] choices={"Preview 8 detik","Detail video",e.favorite?"Hapus favorit":"Tambahkan favorit",
            e.watched?"Tandai belum dilihat":"Tandai sudah dilihat",
            e.trashedAt>0?"Pulihkan dari sampah":"Pindahkan ke sampah",
            "Unduh / Lanjutkan","Jeda download","Simpan ke Downloads HP",
            ViewerExtras.hidden(this,repo.chat(),e.messageId)?"Tampilkan dari tersembunyi":"Sembunyikan dari galeri",
            "Bagikan video sementara"};
        new AlertDialog.Builder(this).setTitle(e.title).setItems(choices,(d,which)->{
            switch(which){
                case 0:showPreview(e);break;
                case 1:showDetails(e);break;
                case 2:repo.setFavorite(e,!e.favorite);break;
                case 3:repo.setWatched(e,!e.watched);break;
                case 4:if(e.trashedAt>0)repo.restore(e);else repo.trash(e);break;
                case 5:repo.download(e);break;
                case 6:repo.pauseDownload(e);break;
                case 7:if(!e.localPath().isEmpty())saveToPhone(e);
                    else showFailure("Video belum tersimpan di cache aplikasi.");break;
                case 8:ViewerExtras.setHidden(this,repo.chat(),e.messageId,
                    !ViewerExtras.hidden(this,repo.chat(),e.messageId));refreshGrid();break;
                case 9:if(e.playableUri()!=null)VaultShareProvider.share(this,e.playableUri(),"video/*");
                    else showFailure("Video harus tersedia secara lokal sebelum dibagikan");break;
            }
        }).show();
    }
    private void showDetails(VideoEntry e){
        int duplicates=0;
        for(VideoEntry entry:repo.snapshot())if(entry.fileId==e.fileId)duplicates++;
        String s=e.summary()+"\nDurasi: "+e.durationLabel()+
            "\nUkuran: "+String.format(Locale.US,"%.2f MB",e.size()/1048576.0)+
            "\nID pesan: "+e.messageId+"\nID berkas: "+e.fileId+
            "\nStatus: "+(e.complete()?"Tersimpan di perangkat":"Belum diunduh")+
            "\nDuplikat ID file dalam bot: "+duplicates+
            "\nTerakhir ditonton: "+(e.watched?"Sudah":"Belum")+
            "\nPosisi nonton: "+(e.positionMs/1000)+" detik";
        new AlertDialog.Builder(this).setTitle(e.title).setMessage(s).setPositiveButton("Tutup",null).show();
    }
    private void downloadBatch(){
        if(adapter==null)return;
        List<VideoEntry> filtered=adapter.displayed();
        if(filtered.isEmpty()){showFailure("Tidak ada video untuk diunduh");return;}
        String[] limits={"10 video pertama","50 video pertama","100 video pertama","Semua yang belum diunduh"};
        new AlertDialog.Builder(this).setTitle("Download massal")
            .setItems(limits,(d,choice)->{
                int max=choice==0?10:choice==1?50:choice==2?100:Integer.MAX_VALUE;
                int n=0;
                for(VideoEntry e:filtered){
                    if(n>=max)break;
                    if(!e.complete() && e.trashedAt==0){repo.download(e);n++;}
                }
                Toast.makeText(this,n+" video masuk antrean manual",Toast.LENGTH_LONG).show();
            }).setNegativeButton("Batal",null).show();
    }
    private void downloadManager(){
        new AlertDialog.Builder(this).setTitle("Download Manager")
            .setMessage(repo.monitor()+"\n\nDownload otomatis hanya melalui Wi-Fi. Download manual bisa memakai data seluler.")
            .setPositiveButton("Download massal",(d,w)->downloadBatch())
            .setNeutralButton(repo.autoWifiEnabled()?"Jeda otomatis":"Lanjut otomatis",(d,w)->{
                repo.setAutoWifiEnabled(!repo.autoWifiEnabled());updateAutoWifi();
            }).setNegativeButton("Tutup",null).show();
    }
    private void showStorage(){
        long downloaded=repo.estimatedBytes();
        StatFs fs=new StatFs(getFilesDir().getAbsolutePath());
        long free=fs.getAvailableBytes();
        String info=String.format(Locale.US,"Cache video terdeteksi: %.2f GB\nRuang kosong: %.2f GB\nBatas auto-download: %s",
            downloaded/(1024.0*1024*1024),free/(1024.0*1024*1024),
            repo.prefs.get("storage_limit_gb").isEmpty()?"Tanpa batas":repo.prefs.get("storage_limit_gb")+" GB");
        new AlertDialog.Builder(this).setTitle("Penyimpanan VaultGram").setMessage(info)
            .setPositiveButton("Atur batas",(d,w)->setStorageLimit()).setNegativeButton("Tutup",null).show();
    }
    private void setStorageLimit(){
        String[] options={"Tanpa batas","5 GB","10 GB","20 GB","50 GB"};String[] vals={"","5","10","20","50"};
        new AlertDialog.Builder(this).setTitle("Batas auto-download")
            .setItems(options,(d,w)->repo.prefs.put("storage_limit_gb",vals[w])).show();
    }
    private void lockTimeout(){
        String[] titles={"Langsung","30 detik","1 menit (disarankan)","5 menit"};
        String[] values={"0","30000","60000","300000"};
        new AlertDialog.Builder(this).setTitle("Kunci setelah aplikasi ditinggalkan")
            .setItems(titles,(d,w)->repo.prefs.put("lock_timeout_ms",values[w])).show();
    }
    private void sortDialog(){
        String[] options={"Terbaru","Terlama","Terbesar","Terkecil","Acak"};
        new AlertDialog.Builder(this).setTitle("Urutkan galeri")
            .setItems(options,(d,w)->{sortBy=w;refreshGrid();}).show();
    }
    private void openSettings(){
        String[] choices={"Kunci VaultGram sekarang","Ganti password","Fingerprint: "+(AppLock.biometricEnabled(this)?"Aktif":"Mati"),
            "Waktu kunci otomatis","Blokir screenshot: "+(!"off".equals(repo.prefs.get("block_screenshots"))?"Aktif":"Mati"),
            "Sinkronisasi terbaru","Sinkronisasi penuh","Pengurutan galeri","Download Manager",
            "Download hanya malam (23–06): "+(repo.nightOnly()?"Aktif":"Mati"),
            "Hanya saat dicas: "+(repo.chargingOnly()?"Aktif":"Mati"),
            "Penyimpanan & batas cache","Backup ringan (terenkripsi)","Backup lengkap + video","Pulihkan backup",
            "Status jaringan & sinkronisasi",
            "Hemat data (preload): "+(ViewerExtras.dataSaver(this)?"Aktif":"Mati"),
            "Lihat Vault privat & potret"};
        new AlertDialog.Builder(this).setTitle("Pengaturan VaultGram")
            .setItems(choices,(d,w)->{
                switch(w){
                    case 0:AppLock.lock(this);break;
                    case 1:AppLock.change(this);break;
                    case 2:repo.prefs.put("fingerprint",AppLock.biometricEnabled(this)?"off":"on");break;
                    case 3:lockTimeout();break;
                    case 4:repo.prefs.put("block_screenshots","off".equals(repo.prefs.get("block_screenshots"))?"on":"off");AppLock.privacy(this);break;
                    case 5:repo.syncRecent();break;
                    case 6:repo.fullSync();break;
                    case 7:sortDialog();break;
                    case 8:downloadManager();break;
                    case 9:repo.setNightOnly(!repo.nightOnly());break;
                    case 10:repo.setChargingOnly(!repo.chargingOnly());break;
                    case 11:showStorage();break;
                    case 12:chooseBackup(false);break;
                    case 13:chooseBackup(true);break;
                    case 14:chooseRestore();break;
                    case 15:new AlertDialog.Builder(this).setTitle("Monitor Telegram").setMessage(repo.monitor())
                        .setPositiveButton("Tutup",null).show();break;
                    case 16:ViewerExtras.setDataSaver(this,!ViewerExtras.dataSaver(this));break;
                    case 17:startActivity(new Intent(this,PrivateMediaActivity.class));break;
                }
            }).setNegativeButton("Tutup",null).show();
    }
    private void chooseBackup(boolean videos){
        if(repo.snapshot().isEmpty()){showFailure("Belum ada katalog untuk dibackup");return;}
        EditText field=input("Password backup (min. 8 karakter)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,"");
        LinearLayout box=column();box.setPadding(dp(22),dp(8),dp(22),0);box.addView(field);
        new AlertDialog.Builder(this).setTitle(videos?"Backup lengkap terenkripsi":"Backup katalog terenkripsi")
            .setMessage(videos?"File backup dapat besar karena termasuk video lokal. Sesi login Telegram tidak ikut dicadangkan.":"Menyimpan daftar video, favorit, dan riwayat tontonan tanpa file video atau sesi Telegram.")
            .setView(box).setNegativeButton("Batal",null).setPositiveButton("Pilih lokasi",(d,w)->{
                if(field.getText().length()<8){showFailure("Password backup minimal 8 karakter");return;}
                backupPassword=field.getText().toString();backupWithVideos=videos;
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");
                i.putExtra(Intent.EXTRA_TITLE,"VaultGram-"+(videos?"lengkap":"katalog")+".vgb");
                startActivityForResult(i,BACKUP_CREATE);
            }).show();
    }
    private void chooseRestore(){
        EditText field=input("Password backup",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,"");
        LinearLayout box=column();box.setPadding(dp(22),dp(8),dp(22),0);box.addView(field);
        new AlertDialog.Builder(this).setTitle("Pulihkan backup terenkripsi")
            .setMessage("Memulihkan daftar dan video, tetapi tidak memulihkan login Telegram.")
            .setView(box).setNegativeButton("Batal",null).setPositiveButton("Pilih backup",(d,w)->{
                if(field.getText().length()==0){showFailure("Password backup wajib diisi");return;}
                backupPassword=field.getText().toString();
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");startActivityForResult(i,BACKUP_OPEN);
            }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        String password=backupPassword;backupPassword=null;
        if(password==null)return;
        PrivateBackup.Callback cb=(message,ok)->new AlertDialog.Builder(this)
            .setTitle(ok?"VaultGram":"Terjadi masalah")
            .setMessage(message).setPositiveButton("Tutup",null).show();
        if(request==BACKUP_CREATE)PrivateBackup.export(this,data.getData(),password,backupWithVideos,repo,cb);
        else if(request==BACKUP_OPEN)PrivateBackup.restore(this,data.getData(),password,repo,cb);
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
                boolean hidden=ViewerExtras.hidden(MainActivity.this,repo.chat(),v.messageId);
                if (filter == 6 && !hidden) continue;
                if (filter != 6 && hidden) continue;
                if (filter == 5 && v.trashedAt == 0) continue;
                if (filter != 5 && filter != 6 && v.trashedAt != 0) continue;
                if (filter == 1 && !v.complete()) continue;
                if (filter == 2 && v.complete()) continue;
                if (filter == 3 && !v.favorite) continue;
                if (filter == 4 && v.watched) continue;
                if (!q.isEmpty() && !v.title.toLowerCase(Locale.ROOT).contains(q) &&
                    !v.summary().toLowerCase(Locale.ROOT).contains(q)) continue;
                shown.add(v);
            }
            if(sortBy==1)Collections.reverse(shown);
            else if(sortBy==2)shown.sort((a,b)->Long.compare(b.size(),a.size()));
            else if(sortBy==3)shown.sort(Comparator.comparingLong(VideoEntry::size));
            else if(sortBy==4)Collections.shuffle(shown,new Random(42));
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
                finally { try { retriever.release(); } catch (Exception ignored) { } }
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
                TextView options = label("⋮", 24, Color.WHITE, true);
                options.setGravity(Gravity.CENTER);
                options.setBackground(round(Color.argb(195,10,16,32),12));
                FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(dp(34),dp(34),Gravity.TOP|Gravity.RIGHT);
                menuLp.setMargins(0,dp(5),dp(5),0);
                preview.addView(options,menuLp);
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
                holder = new Holder(cover, duration, title, status, options);
                card.setTag(holder);
                convertView = card;
            } else { holder = (Holder) convertView.getTag(); }
            VideoEntry e = getItem(position);
            holder.title.setText((e.favorite ? "★ " : "")+e.title);
            holder.duration.setText(e.durationLabel());
            if (e.trashedAt != 0) {
                holder.status.setText("◫ Di sampah · Pulihkan");
                holder.status.setTextColor(MUTED);
            } else if (e.complete()) {
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
            if(bitmap == null && !e.thumbPath().isEmpty()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                bitmap = BitmapFactory.decodeFile(e.thumbPath(), options);
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
            if (cache.get(highKey) == null && e.complete() && e.thumbPath().isEmpty())
                requestLocalFrame(e, highKey);
            convertView.setOnClickListener(v -> {
                if(e.trashedAt>0)showVideoMenu(e);
                else onVideoPressed(e);
            });
            convertView.setOnLongClickListener(v -> { showPreview(e); return true; });
            holder.options.setOnClickListener(v -> showVideoMenu(e));
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView image;
        final TextView duration, title, status, options;
        Holder(ImageView image, TextView duration, TextView title, TextView status, TextView options) {
            this.image = image; this.duration = duration; this.title = title; this.status = status; this.options=options;
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
