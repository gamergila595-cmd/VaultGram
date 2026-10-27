package id.vaultgram.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.BatteryManager;
import android.content.Intent;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

/** One TDLib session, one account, one selected bot's message history. */
final class TelegramRepository {
    enum Stage { SETUP, CONNECTING, PHONE, CODE, PASSWORD, EMAIL, EMAIL_CODE, OTHER, READY }

    interface Listener {
        void onStage(Stage stage, String message);
        void onAccount(String account);
        void onBot(String username);
        void onVideosChanged();
        void onStatus(String message);
        void onError(String message);
    }

    private static TelegramRepository instance;
    static synchronized TelegramRepository get(Context context) {
        if (instance == null) instance = new TelegramRepository(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    final SecurePrefs prefs;
    private final CatalogStore catalog;
    private final ExecutorService catalogIo = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<Long, VideoEntry> dirtyCatalog = new LinkedHashMap<>();
    private final Runnable flushTask = this::flushCatalog;
    private boolean cacheLoaded;
    private long lastRecentAt;
    private int newCount;
    private Client client;
    private Listener listener;
    private Stage stage = Stage.SETUP;
    private String stageMessage = "";
    private String account = "";
    private String botName = "";
    private String status = "";
    private String oneShotPhone = "";
    private long chatId;
    private int generation;
    private int localScanGeneration;
    private final ArrayList<VideoEntry> playback = new ArrayList<>();
    private boolean isSyncing;
    private boolean profileLoaded;
    private boolean didRequestPhone;
    private final ArrayList<VideoEntry> items = new ArrayList<>();
    private final Set<Long> messagesSeen = new HashSet<>();
    private final Map<Integer, List<VideoEntry>> fileById = new HashMap<>();
    private final Map<Integer, List<VideoEntry>> thumbById = new HashMap<>();
    private final Set<Integer> thumbnailsRequested = new HashSet<>();
    // Only messages newer than the initial bot history are auto-downloaded.
    // Pending IDs persist across process restarts and network changes.
    private final LinkedHashSet<Long> pendingAutoIds = new LinkedHashSet<>();
    private final LinkedHashMap<Long, VideoEntry> autoQueue = new LinkedHashMap<>();
    private final Set<Integer> autoActiveFiles = new HashSet<>();
    private final Set<Integer> manuallyRequested = new HashSet<>();
    private final ConnectivityManager network;
    private long seenId;
    private long syncHighWater;
    private long historyHighWater;
    private boolean checkpointSet;
    private boolean wifi = false;
    private final ConnectivityManager.NetworkCallback wifiCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(() -> onNetworkChanged()); }
        @Override public void onLost(Network n) { main.post(() -> onNetworkChanged()); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            main.post(() -> onNetworkChanged());
        }
    };

    private TelegramRepository(Context context) {
        this.context = context;
        prefs = new SecurePrefs(context);
        catalog = new CatalogStore(context);
        botName = prefs.get("bot");
        chatId = parseLong(prefs.get("bot_chat_id"));
        if (chatId != 0 && !botName.isEmpty()) {
            checkpointSet = !prefs.get(checkpointKey()).isEmpty();
            seenId = parseLong(prefs.get(checkpointKey()));
            loadCached(chatId);
            loadPending();
        }
        network = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = activeWifi();
        if (network != null) {
            try { network.registerDefaultNetworkCallback(wifiCallback); }
            catch (RuntimeException ignored) { /* If unavailable, keep auto-download disabled. */ }
        }
    }

    void bind(Listener listener) {
        this.listener = listener;
        if (listener == null) return;
        listener.onStage(stage, stageMessage);
        if (!account.isEmpty()) listener.onAccount(account);
        if (!botName.isEmpty()) listener.onBot(botName);
        listener.onVideosChanged();
        if (!status.isEmpty()) listener.onStatus(status);
    }

    void unbind(Listener old) { if (listener == old) listener = null; }

    boolean hasApiCredentials() {
        return !prefs.get("api_id").isEmpty() && !prefs.get("api_hash").isEmpty();
    }

    void configureAndConnect(String id, String hash, String phone, String bot) {
        prefs.put("api_id", id.trim());
        prefs.put("api_hash", hash.trim());
        if (!phone.isEmpty()) prefs.put("phone", phone.trim());
        if (!bot.isEmpty()) prefs.put("pending_bot", cleanUsername(bot));
        oneShotPhone = phone.trim();
        connect();
    }

    void connect() {
        if (client != null) return;
        if (!hasApiCredentials()) { setStage(Stage.SETUP, "Masukkan API ID dan API Hash."); return; }
        try {
            Integer.parseInt(prefs.get("api_id"));
            prefs.dbKey();
            setStage(Stage.CONNECTING, "Membuka koneksi Telegram…");
            Client.setLogMessageHandler(1, (level, message) -> {});
            client = Client.create(this::onTdUpdate, throwable -> postError(throwable.getMessage()),
                    throwable -> postError(throwable.getMessage()));
        } catch (Exception e) { postError("Tidak bisa memulai TDLib: " + e.getMessage()); }
    }

    private void onTdUpdate(TdApi.Object update) {
        if (update instanceof TdApi.UpdateAuthorizationState) {
            TdApi.AuthorizationState next = ((TdApi.UpdateAuthorizationState) update).authorizationState;
            main.post(() -> onAuthorization(next));
        } else if (update instanceof TdApi.UpdateFile) {
            TdApi.File file = ((TdApi.UpdateFile) update).file;
            main.post(() -> onFileUpdate(file));
        } else if (update instanceof TdApi.UpdateNewMessage) {
            TdApi.Message message = ((TdApi.UpdateNewMessage) update).message;
            main.post(() -> {
                if (chatId == 0 || message.chatId != chatId) return;
                boolean added = addMessage(message, true);
                // When history sync is running, capture all new messages at its end.
                if (!isSyncing && checkpointSet && message.id > seenId) {
                    seenId = message.id;
                    prefs.put(checkpointKey(), Long.toString(seenId));
                    if (added) { newCount++; queueAuto(findMessage(message.id)); }
                }
                if (added) notifyVideos();
            });
        }
    }

    private void onAuthorization(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            setStage(Stage.CONNECTING, "Menyiapkan database Telegram…");
            try {
                TdApi.SetTdlibParameters p = new TdApi.SetTdlibParameters();
                p.useTestDc = false;
                p.databaseDirectory = new File(context.getFilesDir(), "telegram/database").getAbsolutePath();
                p.filesDirectory = new File(context.getFilesDir(), "telegram/files").getAbsolutePath();
                p.databaseEncryptionKey = prefs.dbKey();
                p.useFileDatabase = true;
                p.useChatInfoDatabase = true;
                p.useMessageDatabase = true;
                p.useSecretChats = false;
                p.apiId = Integer.parseInt(prefs.get("api_id"));
                p.apiHash = prefs.get("api_hash");
                p.systemLanguageCode = "id";
                p.deviceModel = Build.MODEL;
                p.systemVersion = Build.VERSION.RELEASE;
                p.applicationVersion = "1.2.0";
                client.send(p, this::checkError);
            } catch (Exception e) { postError("Konfigurasi Telegram gagal: " + e.getMessage()); }
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            profileLoaded = false;
            setStage(Stage.PHONE, "Masukkan nomor Telegram dengan kode negara, misalnya +62…");
            if (!oneShotPhone.isEmpty() && !didRequestPhone) {
                didRequestPhone = true;
                sendPhone(oneShotPhone);
                oneShotPhone = "";
            }
        } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
            setStage(Stage.CODE, "Masukkan kode login yang dikirim Telegram.");
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            setStage(Stage.PASSWORD, "Masukkan password verifikasi dua langkah Telegram.");
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            setStage(Stage.EMAIL, "Telegram meminta email verifikasi akun.");
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailCode) {
            setStage(Stage.EMAIL_CODE, "Masukkan kode verifikasi yang dikirim ke email.");
        } else if (state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            setStage(Stage.OTHER, "Konfirmasikan login dari aplikasi Telegram yang sudah aktif.");
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            setStage(Stage.READY, "Login berhasil.");
            if (!profileLoaded) {
                profileLoaded = true;
                requestMe();
            }
        } else if (state instanceof TdApi.AuthorizationStateWaitRegistration) {
            setStage(Stage.OTHER, "Nomor ini belum terdaftar. Daftarkan terlebih dahulu di Telegram resmi.");
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            client = null;
            profileLoaded = false;
            setStage(Stage.OTHER, "Sesi ditutup. Buka ulang aplikasi untuk login.");
        }
    }

    private void requestMe() {
        client.send(new TdApi.GetMe(), result -> main.post(() -> {
            if (!(result instanceof TdApi.User)) { checkError(result); return; }
            TdApi.User user = (TdApi.User) result;
            String locked = prefs.get("account_id");
            if (!locked.isEmpty() && !locked.equals(String.valueOf(user.id))) {
                postError("Akun Telegram berbeda dari akun yang sudah dikunci di aplikasi. Hapus data aplikasi jika ingin mengganti akun.");
                client.send(new TdApi.LogOut(), this::checkError);
                return;
            }
            if (locked.isEmpty()) prefs.put("account_id", String.valueOf(user.id));
            account = user.firstName + "  ·  +" + user.phoneNumber;
            if (listener != null) listener.onAccount(account);
            String bot = prefs.get("bot");
            if (bot.isEmpty()) bot = prefs.get("pending_bot");
            if (!bot.isEmpty()) {
                // A saved chat ID is sufficient for incremental history reads.
                if (chatId != 0 && bot.equalsIgnoreCase(botName)) syncRecent();
                else selectBot(bot);
            }
        }));
    }

    void sendPhone(String phone) {
        String number = phone.trim().replace(" ", "");
        if (client == null) return;
        prefs.put("phone", number);
        setStatus("Meminta kode login…");
        client.send(new TdApi.SetAuthenticationPhoneNumber(number, null), this::checkError);
    }
    void sendCode(String code) {
        if (client != null) client.send(new TdApi.CheckAuthenticationCode(code.trim()), this::checkError);
    }
    void sendPassword(String password) {
        if (client != null) client.send(new TdApi.CheckAuthenticationPassword(password), this::checkError);
    }
    void sendEmail(String email) {
        if (client != null) client.send(new TdApi.SetAuthenticationEmailAddress(email.trim()), this::checkError);
    }
    void sendEmailCode(String code) {
        if (client != null) client.send(new TdApi.CheckAuthenticationEmailCode(
                new TdApi.EmailAddressAuthenticationCode(code.trim())), this::checkError);
    }

    void selectBot(String raw) {
        if (client == null || !profileLoaded) return;
        String username = cleanUsername(raw);
        if (!username.matches("[A-Za-z0-9_]{5,32}")) {
            postError("Username bot tidak valid. Contoh: @contoh_bot"); return;
        }
        final int token = ++generation;
        setStatus("Memverifikasi @" + username + "…");
        client.send(new TdApi.SearchPublicChat(username), result -> main.post(() -> {
            if (token != generation) return;
            if (!(result instanceof TdApi.Chat)) { checkError(result); return; }
            TdApi.Chat chat = (TdApi.Chat) result;
            if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
                postError("Username ini bukan bot Telegram pribadi."); return;
            }
            long userId = ((TdApi.ChatTypePrivate) chat.type).userId;
            client.send(new TdApi.GetUser(userId), userResult -> main.post(() -> {
                if (token != generation) return;
                if (!(userResult instanceof TdApi.User)) { checkError(userResult); return; }
                TdApi.User user = (TdApi.User) userResult;
                if (!(user.type instanceof TdApi.UserTypeBot)) {
                    postError("@" + username + " bukan akun bot Telegram."); return;
                }
                if (chatId != chat.id) {
                    cancelAutoTransfers();
                    pendingAutoIds.clear();
                    autoQueue.clear();
                    autoActiveFiles.clear();
                    manuallyRequested.clear();
                }
                boolean changedBot = chatId != chat.id;
                chatId = chat.id;
                botName = username;
                prefs.put("bot_chat_id", Long.toString(chatId));
                if (changedBot || !cacheLoaded) loadCached(chatId);
                String stored = prefs.get(checkpointKey());
                checkpointSet = !stored.isEmpty();
                seenId = parseLong(stored);
                loadPending();
                prefs.put("bot", username);
                prefs.put("pending_bot", "");
                if (listener != null) listener.onBot(username);
                if (!checkpointSet || items.isEmpty()) fullSync();
                else syncRecent();
            }));
        }));
    }

    private static String cleanUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (value.startsWith("https://t.me/")) value = value.substring(13);
        if (value.startsWith("@")) value = value.substring(1);
        int question = value.indexOf('?');
        return (question >= 0 ? value.substring(0, question) : value).trim();
    }

    private void loadCached(long id) {
        items.clear();
        playback.clear();
        messagesSeen.clear();
        fileById.clear();
        thumbById.clear();
        thumbnailsRequested.clear();
        try {
            List<VideoEntry> cached = catalog.load(id);
            long expiry = System.currentTimeMillis() - 7L * 86400000L;
            for (VideoEntry e : cached) {
                if (e.trashedAt > 0 && e.trashedAt < expiry) continue;
                items.add(e);
                messagesSeen.add(e.messageId);
                fileById.computeIfAbsent(e.fileId, key -> new ArrayList<>()).add(e);
            }
            cacheLoaded = true;
        } catch (Exception e) { postError("Database lokal tidak dapat dibaca: " + e.getMessage()); }
        notifyVideos();
    }

    private void markDirty(VideoEntry entry) {
        if (entry == null || chatId == 0) return;
        dirtyCatalog.put(entry.messageId, entry);
        main.removeCallbacks(flushTask);
        if (dirtyCatalog.size() >= 250) flushCatalog();
        else main.postDelayed(flushTask, 350);
    }
    private void flushCatalog() {
        if (dirtyCatalog.isEmpty() || chatId == 0) return;
        final long selected = chatId;
        List<VideoEntry> values = new ArrayList<>(dirtyCatalog.values());
        dirtyCatalog.clear();
        catalogIo.execute(() -> {
            try { catalog.saveBatch(selected, values); }
            catch (Exception ignored) { /* Cached view remains usable; next sync will retry. */ }
        });
    }

    // Normal refresh never clears the gallery or re-reads older Telegram history.
    void sync() { syncRecent(); }
    void syncRecent() {
        if (chatId == 0 || client == null || isSyncing) return;
        if (System.currentTimeMillis() - lastRecentAt < 12000) return;
        lastRecentAt = System.currentTimeMillis();
        if (!checkpointSet || items.isEmpty()) { fullSync(); return; }
        final int token = ++generation;
        syncHighWater = seenId;
        historyHighWater = seenId;
        isSyncing = true;
        setStatus("Memeriksa video terbaru…");
        nextRecentPage(0, token, 0);
    }
    private void nextRecentPage(long fromId, int token, int page) {
        if (token != generation || client == null || !isSyncing) return;
        if (page >= 100) { endSync(token, "Batas pengecekan video baru tercapai."); return; }
        client.send(new TdApi.GetChatHistory(chatId, fromId, 0, 100, false), result -> main.post(() -> {
            if (token != generation || !isSyncing) return;
            if (result instanceof TdApi.Error) {
                endSync(token, "Cek video baru gagal: " + ((TdApi.Error) result).message); return;
            }
            if (!(result instanceof TdApi.Messages)) { endSync(token, "Riwayat tidak tersedia."); return; }
            TdApi.Message[] messages = ((TdApi.Messages) result).messages;
            if (messages == null || messages.length == 0) { endSync(token, "Galeri terbaru."); return; }
            long last = fromId;
            boolean reached = false;
            for (TdApi.Message message : messages) {
                if (message == null) continue;
                if (last == 0 || message.id < last) last = message.id;
                if (message.id <= syncHighWater) { reached = true; continue; }
                historyHighWater = Math.max(historyHighWater, message.id);
                if (addMessage(message, true)) { newCount++; queueAuto(findMessage(message.id)); }
            }
            items.sort((a,b) -> Long.compare(b.messageId,a.messageId));
            notifyVideos();
            if (reached || last == fromId || last == 0) endSync(token, "Galeri terbaru.");
            else { long next=last; main.postDelayed(() -> nextRecentPage(next,token,page+1),40); }
        }));
    }

    void fullSync() {
        if (chatId == 0 || client == null) return;
        final int token = ++generation;
        syncHighWater = seenId;
        historyHighWater = seenId;
        // Preserve cached data and downloaded state while historical pagination runs.
        isSyncing = true;
        notifyVideos();
        setStatus("Sinkronisasi penuh dari @" + botName + "…");
        nextPage(0, token, 0);
    }

    private void nextPage(long fromId, int token, int page) {
        if (token != generation || !isSyncing || client == null) return;
        if (page > 4000) { endSync(token, "Batas 400.000 pesan tercapai. Coba sinkronkan ulang."); return; }
        client.send(new TdApi.GetChatHistory(chatId, fromId, 0, 100, false), result -> main.post(() -> {
            if (token != generation) return;
            if (result instanceof TdApi.Error) {
                endSync(token, "Sinkronisasi terhenti: " + ((TdApi.Error) result).message); return;
            }
            if (!(result instanceof TdApi.Messages)) { endSync(token, "Riwayat tidak tersedia."); return; }
            TdApi.Message[] list = ((TdApi.Messages) result).messages;
            if (list == null || list.length == 0) { endSync(token, "Sinkronisasi selesai."); return; }
            long last = fromId;
            int added = 0;
            for (TdApi.Message message : list) {
                if (message == null) continue;
                if (message.id > historyHighWater) historyHighWater = message.id;
                if (last == 0 || message.id < last) last = message.id;
                if (addMessage(message, false)) added++;
            }
            if (added > 0 || page % 4 == 0) {
                notifyVideos();
                setStatus("Memeriksa riwayat… " + items.size() + " video ditemukan");
            }
            if (last == fromId || last == 0) {
                endSync(token, "Sinkronisasi selesai.");
            } else {
                long next = last;
                main.postDelayed(() -> nextPage(next, token, page + 1), 35);
            }
        }));
    }

    private void endSync(int token, String message) {
        if (token != generation) return;
        isSyncing = false;
        // On the first sync, record a baseline, never auto-download the whole archive.
        if (checkpointSet) {
            for (VideoEntry entry : items) {
                if (entry.messageId > syncHighWater || pendingAutoIds.contains(entry.messageId))
                    queueAuto(entry);
            }
        }
        seenId = Math.max(seenId, historyHighWater);
        checkpointSet = true;
        prefs.put(checkpointKey(), Long.toString(seenId));
        items.sort((a,b) -> Long.compare(b.messageId,a.messageId));
        flushCatalog();
        notifyVideos();
        setStatus(message + " " + items.size() + " video.");
        if (!message.startsWith("Galeri")) scanDownloads();
        pumpAuto();
    }

    private boolean addMessage(TdApi.Message message, boolean atFront) {
        if (messagesSeen.contains(message.id)) {
            VideoEntry old = findMessage(message.id);
            if (old != null && old.video == null) {
                VideoEntry restored = toVideo(message);
                if (restored == null) return false;
                restored.preserve(old);
                int index = items.indexOf(old);
                if (index >= 0) items.set(index, restored);
                List<VideoEntry> same = fileById.get(old.fileId);
                if (same != null) same.remove(old);
                fileById.computeIfAbsent(restored.fileId,id -> new ArrayList<>()).add(restored);
                if (restored.thumb != null)
                    thumbById.computeIfAbsent(restored.thumb.id,id -> new ArrayList<>()).add(restored);
                markDirty(restored);
            }
            return false;
        }
        VideoEntry entry = toVideo(message);
        messagesSeen.add(message.id);
        if (entry == null) return false;
        if (atFront) items.add(0, entry); else items.add(entry);
        fileById.computeIfAbsent(entry.fileId, id -> new ArrayList<>()).add(entry);
        if (entry.thumb != null) thumbById.computeIfAbsent(entry.thumb.id, id -> new ArrayList<>()).add(entry);
        markDirty(entry);
        return true;
    }

    private static byte[] bytes(TdApi.Minithumbnail thumb) { return thumb == null ? null : thumb.data; }
    private static TdApi.File thumbFile(TdApi.Thumbnail thumbnail) {
        return thumbnail == null ? null : thumbnail.file;
    }

    private VideoEntry toVideo(TdApi.Message m) {
        if (m.content instanceof TdApi.MessageVideo) {
            TdApi.Video v = ((TdApi.MessageVideo) m.content).video;
            if (v != null && v.video != null)
                return new VideoEntry(m.id, m.date, v.fileName, v.duration,
                        v.video, thumbFile(v.thumbnail), bytes(v.minithumbnail));
        } else if (m.content instanceof TdApi.MessageDocument) {
            TdApi.Document d = ((TdApi.MessageDocument) m.content).document;
            if (d != null && d.document != null &&
                    (d.mimeType != null && d.mimeType.toLowerCase(Locale.US).startsWith("video/") ||
                     d.fileName != null && d.fileName.toLowerCase(Locale.US)
                       .matches(".*\\.(mp4|mkv|mov|webm|avi|m4v)$"))) {
                return new VideoEntry(m.id, m.date, d.fileName, 0,
                        d.document, thumbFile(d.thumbnail), bytes(d.minithumbnail));
            }
        } else if (m.content instanceof TdApi.MessageAnimation) {
            TdApi.Animation a = ((TdApi.MessageAnimation) m.content).animation;
            if (a != null && a.animation != null)
                return new VideoEntry(m.id, m.date, a.fileName, a.duration,
                        a.animation, thumbFile(a.thumbnail), bytes(a.minithumbnail));
        } else if (m.content instanceof TdApi.MessageVideoNote) {
            TdApi.VideoNote v = ((TdApi.MessageVideoNote) m.content).videoNote;
            if (v != null && v.video != null)
                return new VideoEntry(m.id, m.date, "Pesan video", v.duration,
                        v.video, thumbFile(v.thumbnail), bytes(v.minithumbnail));
        }
        return null;
    }

    void requestThumbnail(VideoEntry video) {
        if (client == null || video.thumb == null) return;
        if (video.thumb.local != null && video.thumb.local.isDownloadingCompleted &&
                video.thumb.local.path != null && new File(video.thumb.local.path).isFile()) return;
        int id = video.thumb.id;
        if (thumbnailsRequested.add(id))
            client.send(new TdApi.DownloadFile(id, 4, 0, 0, false), result -> {
                if (result instanceof TdApi.File) main.post(() -> onFileUpdate((TdApi.File) result));
                else checkError(result);
            });
    }

    void pauseDownload(VideoEntry video) {
        if(client==null || video==null)return;
        manuallyRequested.remove(video.fileId);
        autoActiveFiles.remove(video.fileId);
        client.send(new TdApi.CancelDownloadFile(video.fileId,false),this::checkError);
    }
    void download(VideoEntry video) {
        if (client == null || chatId == 0 || video == null || video.complete()) return;
        // A tap is an explicit manual download: cellular use is permitted by user choice.
        manuallyRequested.add(video.fileId);
        autoActiveFiles.remove(video.fileId);
        autoQueue.remove(video.messageId);
        pendingAutoIds.remove(video.messageId);
        savePending();
        client.send(new TdApi.AddFileToDownloads(video.fileId, chatId, video.messageId, 16), this::checkError);
        client.send(new TdApi.DownloadFile(video.fileId, 32, 0, 0, false), result -> {
            if (result instanceof TdApi.File) main.post(() -> onFileUpdate((TdApi.File) result));
            else checkError(result);
        });
    }

    private void onFileUpdate(TdApi.File updated) {
        if (updated == null) return;
        boolean changed = false;
        List<VideoEntry> tracked = fileById.get(updated.id);
        if (tracked != null) {
            for (VideoEntry e : tracked) { e.video = updated; markDirty(e); }
            changed = true;
        }
        List<VideoEntry> thumbs = thumbById.get(updated.id);
        if (thumbs != null) {
            for (VideoEntry e : thumbs) { e.thumb = updated; markDirty(e); }
            changed = true;
        }
        if (autoActiveFiles.contains(updated.id) && updated.local != null &&
                (updated.local.isDownloadingCompleted || !updated.local.isDownloadingActive)) {
            autoActiveFiles.remove(updated.id);
            if (updated.local.isDownloadingCompleted) {
                for (Iterator<Map.Entry<Long, VideoEntry>> it = autoQueue.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<Long, VideoEntry> entry = it.next();
                    if (entry.getValue().fileId == updated.id) {
                        pendingAutoIds.remove(entry.getKey());
                        it.remove();
                    }
                }
                savePending();
            }
            // Do not instantly retry a failed transfer; a future Wi-Fi transition or sync will retry.
            if (updated.local.isDownloadingCompleted) pumpAuto();
        }
        if (changed) notifyVideos();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
    private String checkpointKey() { return "newest_" + chatId; }
    private String pendingKey() { return "auto_pending_" + chatId; }
    private void savePending() {
        if (chatId == 0) return;
        StringBuilder b = new StringBuilder();
        for (Long id : pendingAutoIds) {
            if (b.length() > 0) b.append(',');
            b.append(id);
        }
        prefs.put(pendingKey(), b.toString());
    }
    private void loadPending() {
        pendingAutoIds.clear();
        for (String value : prefs.get(pendingKey()).split(",")) {
            long id = parseLong(value);
            if (id > 0) pendingAutoIds.add(id);
        }
    }
    private VideoEntry findMessage(long messageId) {
        for (VideoEntry v : items) if (v.messageId == messageId) return v;
        return null;
    }
    boolean autoWifiEnabled() { return !"off".equals(prefs.get("auto_wifi")); }
    boolean wifiAvailable() { return wifi; }
    private final Set<Integer> segmentsPrefetched = new HashSet<>();
    // Preload only an initial segment; never prefetch an entire video on cellular.
    void prefetchNeighbor(VideoEntry candidate) {
        if (!wifi || client == null || candidate == null || candidate.complete() ||
            candidate.downloading() || manuallyRequested.contains(candidate.fileId)) return;
        if (!segmentsPrefetched.add(candidate.fileId)) return;
        client.send(new TdApi.DownloadFile(candidate.fileId, 1, 0, 262144, false), result -> {
            if (result instanceof TdApi.File) main.post(() -> onFileUpdate((TdApi.File) result));
        });
    }
    void setAutoWifiEnabled(boolean enable) {
        prefs.put("auto_wifi", enable ? "on" : "off");
        if (!enable) cancelAutoTransfers();
        else pumpAuto();
    }
    private boolean activeWifi() {
        if (network == null) return false;
        try {
            NetworkCapabilities c = network.getNetworkCapabilities(network.getActiveNetwork());
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return false; }
    }
    private void onNetworkChanged() {
        boolean now = activeWifi();
        if (wifi == now) return;
        wifi = now;
        if (!wifi) {
            for (Integer fileId : new HashSet<>(segmentsPrefetched)) {
                if (!manuallyRequested.contains(fileId) && !autoActiveFiles.contains(fileId))
                    client.send(new TdApi.CancelDownloadFile(fileId, false), this::checkError);
            }
            segmentsPrefetched.clear();
            cancelAutoTransfers();
        }
        else pumpAuto();
        notifyVideos();
    }
    private void cancelAutoTransfers() {
        if (client != null) for (Integer fileId : new HashSet<>(autoActiveFiles)) {
            if (!manuallyRequested.contains(fileId))
                client.send(new TdApi.CancelDownloadFile(fileId, false), this::checkError);
        }
        autoActiveFiles.clear();
    }
    private void queueAuto(VideoEntry v) {
        if (v == null || v.complete() || manuallyRequested.contains(v.fileId)) return;
        pendingAutoIds.add(v.messageId);
        autoQueue.put(v.messageId, v);
        savePending();
        pumpAuto();
    }
    private void pumpAuto() {
        if (!autoWifiEnabled() || !wifi || client == null || chatId == 0 || !allowedSchedule()) return;
        try {
            String lim=prefs.get("storage_limit_gb");
            if(!lim.isEmpty() && estimatedBytes()>=Long.parseLong(lim)*1073741824L){
                setStatus("Batas penyimpanan auto-download tercapai");return;
            }
        } catch(Exception ignored) {}
        for (Map.Entry<Long, VideoEntry> item : new ArrayList<>(autoQueue.entrySet())) {
            if (autoActiveFiles.size() >= 2) break;
            VideoEntry video = item.getValue();
            if (video.complete()) {
                autoQueue.remove(item.getKey());
                pendingAutoIds.remove(item.getKey());
                savePending();
                continue;
            }
            if (manuallyRequested.contains(video.fileId) || autoActiveFiles.contains(video.fileId)) continue;
            autoActiveFiles.add(video.fileId);
            client.send(new TdApi.DownloadFile(video.fileId, 12, 0, 0, false), result -> {
                if (result instanceof TdApi.File) main.post(() -> onFileUpdate((TdApi.File) result));
                else if (result instanceof TdApi.Error) main.post(() -> {
                    autoActiveFiles.remove(video.fileId);
                    checkError(result);
                });
            });
        }
    }

    void setPlayback(List<VideoEntry> shown) {
        playback.clear();
        playback.addAll(shown);
    }
    List<VideoEntry> playback() {
        return playback.isEmpty() ? snapshot() : new ArrayList<>(playback);
    }
    void rememberExport(VideoEntry video, android.net.Uri uri) {
        LocalVideos.remember(context, chatId, video, uri);
        video.exportedUri = uri.toString();
        markDirty(video);
        notifyVideos();
    }
    void scanDownloads() {
        if (chatId == 0 || items.isEmpty()) return;
        int token = ++localScanGeneration;
        long selectedChat = chatId;
        List<VideoEntry> current = snapshot();
        new Thread(() -> {
            Map<Long, String> known = LocalVideos.find(context, selectedChat, current);
            main.post(() -> {
                if (token != localScanGeneration || chatId != selectedChat) return;
                for (VideoEntry video : current) {
                    if (known.containsKey(video.messageId)) {
                        video.exportedUri = known.get(video.messageId);
                        markDirty(video);
                    }
                }
                notifyVideos();
                setStatus("Pemeriksaan unduhan selesai · " + current.size() + " video");
            });
        }, "VaultGram-ScanDownloads").start();
    }
    List<VideoEntry> snapshot() { return new ArrayList<>(items); }
    boolean hasCachedGallery() { return cacheLoaded && chatId != 0 && !items.isEmpty(); }
    int newVideos() { return newCount; }
    void markGallerySeen() { newCount = 0; }
    void setFavorite(VideoEntry e, boolean value) {
        e.favorite=value; saveUser(e); notifyVideos();
    }
    void setWatched(VideoEntry e, boolean value) {
        e.watched=value; saveUser(e); notifyVideos();
    }
    void savePosition(VideoEntry e, int milliseconds) {
        e.positionMs=Math.max(0,milliseconds); e.watched=true; saveUser(e);
    }
    void trash(VideoEntry e) { e.trashedAt=System.currentTimeMillis(); saveUser(e); notifyVideos(); }
    void restore(VideoEntry e) { e.trashedAt=0; saveUser(e); notifyVideos(); }
    private void saveUser(VideoEntry e) {
        long selected=chatId;
        catalogIo.execute(() -> catalog.saveUser(selected,e));
    }
    long chat() { return chatId; }
    void importCatalog(long chat,String name,String account,List<VideoEntry> imported) {
        if(!prefs.get("account_id").isEmpty() && !prefs.get("account_id").equals(account))
            throw new IllegalArgumentException("Akun Telegram tidak cocok dengan backup");
        if(prefs.get("account_id").isEmpty() && !account.isEmpty()) prefs.put("account_id",account);
        prefs.put("bot",name);
        prefs.put("bot_chat_id",Long.toString(chat));
        chatId=chat;botName=name;
        catalogIo.execute(()->{
            catalog.saveBatch(chat,imported);
            for(VideoEntry e:imported)catalog.saveUser(chat,e);
        });
        items.clear();messagesSeen.clear();fileById.clear();thumbById.clear();
        for(VideoEntry e:imported){
            items.add(e);messagesSeen.add(e.messageId);
            fileById.computeIfAbsent(e.fileId,k->new ArrayList<>()).add(e);
        }
        items.sort((a,b)->Long.compare(b.messageId,a.messageId));
        cacheLoaded=true;
        if(listener!=null)listener.onBot(name);
        notifyVideos();
    }
    long estimatedBytes() {
        long n=0;
        Set<String> paths = new HashSet<>();
        for(VideoEntry v:items) {
            String p=v.localPath();
            if(!p.isEmpty() && paths.add(p)) n += new File(p).length();
        }
        return n;
    }
    String monitor() {
        return "Koneksi: " + (client == null ? "Offline" : "Telegram aktif") +
            "\nWi-Fi: " + (wifi ? "Aktif" : "Tidak aktif") +
            "\nVideo baru: " + newCount +
            "\nAntrean auto-download: " + pendingAutoIds.size() +
            "\nDownload aktif: " + autoActiveFiles.size() +
            "\nSinkronisasi: " + (isSyncing ? "Berjalan" : "Siap");
    }
    boolean nightOnly() { return "on".equals(prefs.get("night_only")); }
    void setNightOnly(boolean yes) { prefs.put("night_only",yes ? "on":"off"); pumpAuto(); }
    boolean chargingOnly() { return "on".equals(prefs.get("charging_only")); }
    void setChargingOnly(boolean yes) { prefs.put("charging_only",yes ? "on":"off"); pumpAuto(); }
    private boolean allowedSchedule() {
        if (nightOnly()) {
            int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (hour >= 6 && hour < 23) return false;
        }
        if (chargingOnly()) {
            Intent i=context.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return false;
            int state=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
            if (state != BatteryManager.BATTERY_STATUS_CHARGING && state != BatteryManager.BATTERY_STATUS_FULL) return false;
        }
        return true;
    }
    boolean syncing() { return isSyncing; }
    boolean hasBot() { return chatId != 0; }
    String bot() { return botName; }

    private void setStage(Stage value, String message) {
        stage = value;
        stageMessage = message;
        if (listener != null) listener.onStage(value, message);
    }
    private void setStatus(String message) { status = message; if (listener != null) listener.onStatus(message); }
    private void notifyVideos() { if (listener != null) listener.onVideosChanged(); }
    private void checkError(TdApi.Object result) {
        if (result instanceof TdApi.Error) postError(((TdApi.Error) result).message);
    }
    private void postError(String message) {
        main.post(() -> {
            if (listener != null) listener.onError(message == null ? "Terjadi kesalahan Telegram." : message);
        });
    }
}
