package id.vaultgram.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

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
                    if (added) queueAuto(findMessage(message.id));
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
                p.applicationVersion = "1.1.0";
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
            if (!bot.isEmpty()) selectBot(bot);
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
                chatId = chat.id;
                botName = username;
                String stored = prefs.get(checkpointKey());
                checkpointSet = !stored.isEmpty();
                seenId = parseLong(stored);
                loadPending();
                prefs.put("bot", username);
                prefs.put("pending_bot", "");
                if (listener != null) listener.onBot(username);
                sync();
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

    void sync() {
        if (chatId == 0 || client == null) return;
        final int token = ++generation;
        syncHighWater = seenId;
        historyHighWater = seenId;
        items.clear();
        playback.clear();
        localScanGeneration++;
        messagesSeen.clear();
        fileById.clear();
        thumbById.clear();
        thumbnailsRequested.clear();
        isSyncing = true;
        notifyVideos();
        setStatus("Membaca riwayat video dari @" + botName + "…");
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
        notifyVideos();
        setStatus(message + " " + items.size() + " video.");
        scanDownloads();
        pumpAuto();
    }

    private boolean addMessage(TdApi.Message message, boolean atFront) {
        if (messagesSeen.contains(message.id)) return false;
        VideoEntry entry = toVideo(message);
        messagesSeen.add(message.id);
        if (entry == null) return false;
        if (atFront) items.add(0, entry); else items.add(entry);
        fileById.computeIfAbsent(entry.fileId, id -> new ArrayList<>()).add(entry);
        if (entry.thumb != null) thumbById.computeIfAbsent(entry.thumb.id, id -> new ArrayList<>()).add(entry);
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
            for (VideoEntry e : tracked) e.video = updated;
            changed = true;
        }
        List<VideoEntry> thumbs = thumbById.get(updated.id);
        if (thumbs != null) {
            for (VideoEntry e : thumbs) e.thumb = updated;
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
        if (!wifi) cancelAutoTransfers();
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
        if (!autoWifiEnabled() || !wifi || client == null || chatId == 0) return;
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
                for (VideoEntry video : current) video.exportedUri = known.get(video.messageId);
                notifyVideos();
                setStatus("Pemeriksaan unduhan selesai · " + current.size() + " video");
            });
        }, "VaultGram-ScanDownloads").start();
    }
    List<VideoEntry> snapshot() { return new ArrayList<>(items); }
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
