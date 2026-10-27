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
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.media.AudioManager;
import android.media.PlaybackParams;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import java.util.Random;

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
    private boolean feedMode;
    private float speed=1f;
    private android.media.MediaPlayer prepared;
    private GestureDetector gestures;
    private AudioManager audio;
    private final Handler ticker=new Handler(Looper.getMainLooper());
    private final Runnable saveTicker=new Runnable(){
        @Override public void run(){
            if(video!=null && index>=0 && index<queue.size() && video.isPlaying())
                repo.savePosition(queue.get(index),video.getCurrentPosition());
            ticker.postDelayed(this,10000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repo = TelegramRepository.get(getApplicationContext());
        feedMode = getIntent().getBooleanExtra("feed_mode",false);
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
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
        configureGestures();
        video.setOnTouchListener((v,event)->gestures.onTouchEvent(event));
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
        LinearLayout quickControls=new LinearLayout(this);
        quickControls.setGravity(Gravity.CENTER);
        quickControls.setBackgroundColor(0xAB000000);
        TextView rewind=label("−10 s",13,Color.WHITE);
        rewind.setGravity(Gravity.CENTER);
        rewind.setOnClickListener(v -> seekBy(-10000));
        quickControls.addView(rewind,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView speedButton=label("1×",13,Color.WHITE);
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setOnClickListener(v->{
            speed=speed==1f?1.25f:speed==1.25f?1.5f:speed==1.5f?2f:1f;
            speedButton.setText(speed+"×");applySpeed();
        });
        quickControls.addView(speedButton,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView forward=label("+10 s",13,Color.WHITE);
        forward.setGravity(Gravity.CENTER);
        forward.setOnClickListener(v -> seekBy(10000));
        quickControls.addView(forward,new LinearLayout.LayoutParams(0,dp(42),1));
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(quickControls);bottom.addView(controls);
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        setContentView(root);

        video.setOnCompletionListener(mp -> {
            if(index>=0 && index<queue.size()) repo.savePosition(queue.get(index),0);
            if (index + 1 < queue.size()) showItem(index + 1);
            else { status.setText("Semua video selesai"); status.setVisibility(View.VISIBLE); }
        });
        video.setOnErrorListener((mp, what, extra) -> {
            status.setText("Format video tidak didukung pemutar HP. Coba video lainnya.");
            status.setVisibility(View.VISIBLE);
            return true;
        });
        showItem(index);
        ticker.postDelayed(saveTicker,10000);
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
        if(index>=0 && index<queue.size() && video!=null && video.isPlaying())
            repo.savePosition(queue.get(index),video.getCurrentPosition());
        index = target;
        requestedId = -1;
        playingUri = "";
        prepared=null;
        video.stopPlayback();
        VideoEntry item = queue.get(index);
        title.setText(item.title);
        repo.setWatched(item,true);
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
            prepared=mp;
            video.start();
            if(item.positionMs>3000 && item.positionMs<Math.max(0,video.getDuration()-3000))
                video.seekTo(item.positionMs);
            applySpeed();
        });
        video.requestFocus();
    }

    private void seekBy(int delta){
        if(video!=null && playingUri!=null && !playingUri.isEmpty())
            video.seekTo(Math.max(0, Math.min(video.getDuration(),video.getCurrentPosition()+delta)));
    }
    private void applySpeed(){
        if(video==null || !video.isPlaying())return;
        if(prepared!=null)try{prepared.setPlaybackParams(new PlaybackParams().setSpeed(speed));}
        catch(Exception ignored){}
    }
    private void configureGestures(){
        gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){
                if(e.getX() < getResources().getDisplayMetrics().widthPixels/2)seekBy(-10000);
                else seekBy(10000);
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e){
                if(video!=null){if(video.isPlaying()) video.pause(); else video.start();}
                return true;
            }
            @Override public boolean onFling(MotionEvent first,MotionEvent last,float vx,float vy){
                if(first==null || last==null)return false;
                float dx=last.getX()-first.getX(),dy=last.getY()-first.getY();
                if(Math.abs(dy)>dp(95) && Math.abs(dy)>Math.abs(dx)){
                    if(dy<0)showItem(index+1);else showItem(index-1);
                    return true;
                }
                if(Math.abs(dx)>dp(95) && Math.abs(dx)>Math.abs(dy)){
                    seekBy(dx>0?10000:-10000);return true;
                }
                return false;
            }
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){
                if(a==null || b==null)return false;
                float horizontal=Math.abs(b.getX()-a.getX());
                float vertical=Math.abs(b.getY()-a.getY());
                if(horizontal>vertical)return true;
                boolean right=a.getX()>getResources().getDisplayMetrics().widthPixels/2f;
                if(right && audio!=null){
                    int cur=audio.getStreamVolume(AudioManager.STREAM_MUSIC),max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.max(0,Math.min(max,cur+(dy>0?-1:1))),0);
                } else if(!right){
                    WindowManager.LayoutParams p=getWindow().getAttributes();
                    float current=p.screenBrightness<0?0.5f:p.screenBrightness;
                    p.screenBrightness=Math.max(0.05f,Math.min(1f,current+(dy>0?-0.01f:0.01f)));
                    getWindow().setAttributes(p);
                }
                return true;
            }
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if (repo != null) repo.bind(this);
        AppLock.gate(this);
    }
    @Override protected void onPause() {
        if (video != null) {
            if(index>=0 && index<queue.size() && video.getCurrentPosition()>0)
                repo.savePosition(queue.get(index),video.getCurrentPosition());
            video.pause();
        }
        if (repo != null) repo.unbind(this);
        super.onPause();
    }
    @Override protected void onDestroy() {
        ticker.removeCallbacks(saveTicker);
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
