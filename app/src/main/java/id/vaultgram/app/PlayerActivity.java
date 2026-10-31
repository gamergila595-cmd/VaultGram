package id.vaultgram.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Interactive video feed: the current page follows the finger and snaps to its neighbor. */
public final class PlayerActivity extends Activity implements TelegramRepository.Listener {
    private final ArrayList<VideoEntry> queue = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService frames = Executors.newSingleThreadExecutor();
    private TelegramRepository repo;
    private FrameLayout stage, currentPage, incomingPage;
    private VideoView video;
    private ImageView incomingImage, seekImage;
    private TextView incomingTitle, title, status, position, backButton, nextButton;
    private TextView aspectButton, speedButton, timerLabel, timeLabel;
    private LinearLayout top, bottom;
    private SeekBar timeline;
    private GestureDetector gestures;
    private MediaPlayer prepared;
    private int index = -1, playerWidth, playerHeight, fitMode, videoWidth, videoHeight;
    private int previewGeneration;
    private long requestedId = -1, sleepAt, lastFrameTime, touchStartTime;
    private String playingUri = "";
    private boolean feedMode, scrubbing, dragging, animating, longSpeed, sleepTriggered, controlsVisible = true;
    private float speed = 1f, priorSpeed = 1f, startX, startY, pageOffset;
    private int dragDirection, loopA = -1, loopB = -1;
    private ValueAnimator settle;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (video != null && index >= 0 && index < queue.size()) {
                if (video.isPlaying()) {
                    repo.savePosition(queue.get(index), video.getCurrentPosition());
                    if (loopA >= 0 && loopB > loopA && video.getCurrentPosition() >= loopB)
                        video.seekTo(loopA);
                }
                updateTimeline();
                if (!sleepTriggered && sleepAt > 0 && SystemClock.elapsedRealtime() >= sleepAt) {
                    sleepTriggered = true;
                    video.pause();
                    status.setText("Timer selesai - pemutaran dijeda");
                    status.setVisibility(View.VISIBLE);
                }
            }
            handler.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getIntent().getBooleanExtra("feed_mode", false)) {
            Intent directFeed = new Intent(this, VerticalFeedActivity.class);
            directFeed.putExtra("message_id", getIntent().getLongExtra("message_id", 0));
            startActivity(directFeed);
            finish();
            return;
        }
        repo = TelegramRepository.get(getApplicationContext());
        feedMode = getIntent().getBooleanExtra("feed_mode", false);
        queue.addAll(repo.playback());
        long messageId = getIntent().getLongExtra("message_id", 0);
        index = find(messageId);
        if (index < 0) {
            queue.clear(); queue.addAll(repo.snapshot());
            index = find(messageId);
        }
        if (index < 0) {
            Toast.makeText(this, "Video tidak ditemukan", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildPlayer();
        bindGestures();
        showItem(index);
        handler.postDelayed(tick, 400);
    }

    private int find(long message) {
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).messageId == message) return i;
        return -1;
    }
    private int dp(float px) { return Math.round(px * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String s, int sp) {
        TextView t = new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);return t;
    }
    private TextView button(String s,int sp) {
        TextView t=text(s,sp);t.setGravity(Gravity.CENTER);
        t.setPadding(dp(7),dp(8),dp(7),dp(8));
        t.setBackground(shape(0x990D1220,10));return t;
    }
    private static String stamp(int ms) {
        int sec=Math.max(0,ms)/1000;
        return String.format(Locale.US,"%02d:%02d",sec/60,sec%60);
    }
    private void buildPlayer() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        stage.setClipChildren(true);
        root.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        currentPage = new FrameLayout(this);
        currentPage.setBackgroundColor(Color.BLACK);
        stage.addView(currentPage,new FrameLayout.LayoutParams(-1,-1));
        video = new VideoView(this);
        currentPage.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        status = text("Menyiapkan video...",14);
        status.setBackground(shape(0xDC141824,14));
        status.setPadding(dp(16),dp(12),dp(16),dp(12));
        status.setGravity(Gravity.CENTER);
        currentPage.addView(status,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        incomingPage = new FrameLayout(this);
        incomingPage.setBackgroundColor(0xFF05080E);
        incomingPage.setVisibility(View.GONE);
        incomingImage = new ImageView(this);
        incomingImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        incomingPage.addView(incomingImage,new FrameLayout.LayoutParams(-1,-1));
        incomingTitle = text("",17);
        incomingTitle.setPadding(dp(20),dp(20),dp(20),dp(28));
        incomingTitle.setBackgroundColor(0x88000000);
        incomingPage.addView(incomingTitle,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        stage.addView(incomingPage,new FrameLayout.LayoutParams(-1,-1));

        top = new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10),dp(8),dp(10),dp(8));top.setBackgroundColor(0x8C000000);
        TextView back=button("‹",25);back.setOnClickListener(v->finish());
        top.addView(back,new LinearLayout.LayoutParams(dp(43),dp(44)));
        title=text("Video",14);title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tt=new LinearLayout.LayoutParams(0,-2,1);tt.leftMargin=dp(8);
        top.addView(title,tt);
        aspectButton=button("Asli",12);
        aspectButton.setOnClickListener(v->chooseAspect());
        top.addView(aspectButton,new LinearLayout.LayoutParams(dp(65),dp(42)));
        TextView menu=button("⋮",24);menu.setOnClickListener(v->playerMenu());
        LinearLayout.LayoutParams ml=new LinearLayout.LayoutParams(dp(43),dp(42));ml.leftMargin=dp(5);
        top.addView(menu,ml);
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(9),dp(8),dp(9),dp(18));bottom.setBackgroundColor(0x9E000000);
        timeLabel=text("00:00 / 00:00",12);timeLabel.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(timeLabel,new LinearLayout.LayoutParams(-1,dp(24)));
        timeline = new SeekBar(this);timeline.setMax(1000);
        bottom.addView(timeline,new LinearLayout.LayoutParams(-1,dp(42)));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){scrubbing=true;seekImage.setVisibility(View.VISIBLE);}
            @Override public void onStopTrackingTouch(SeekBar bar){
                scrubbing=false;seekImage.setVisibility(View.GONE);
                if(video.getDuration()>0)video.seekTo((int)((long)video.getDuration()*bar.getProgress()/1000));
            }
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user){
                if(user && video.getDuration()>0){
                    int desired=(int)((long)video.getDuration()*progress/1000);
                    timeLabel.setText(stamp(desired)+" / "+stamp(video.getDuration()));
                    seekFrame(desired);
                }
            }
        });
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        backButton=button("‹",19);backButton.setOnClickListener(v->showItem(index-1));
        row.addView(backButton,new LinearLayout.LayoutParams(0,dp(43),1));
        TextView minus=button("-10s",12);minus.setOnClickListener(v->seekBy(-10000));
        row.addView(minus,new LinearLayout.LayoutParams(0,dp(43),1));
        speedButton=button("1×",13);speedButton.setOnClickListener(v->cycleSpeed());
        row.addView(speedButton,new LinearLayout.LayoutParams(0,dp(43),1));
        TextView plus=button("+10s",12);plus.setOnClickListener(v->seekBy(10000));
        row.addView(plus,new LinearLayout.LayoutParams(0,dp(43),1));
        nextButton=button("›",19);nextButton.setOnClickListener(v->showItem(index+1));
        row.addView(nextButton,new LinearLayout.LayoutParams(0,dp(43),1));
        bottom.addView(row);
        LinearLayout hints=new LinearLayout(this);hints.setGravity(Gravity.CENTER_VERTICAL);
        position=text("",12);position.setGravity(Gravity.CENTER_VERTICAL);
        hints.addView(position,new LinearLayout.LayoutParams(0,dp(25),1));
        timerLabel=text(feedMode?"Geser vertikal untuk pindah":"Ketuk layar untuk jeda",11);
        timerLabel.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        hints.addView(timerLabel,new LinearLayout.LayoutParams(0,dp(25),1));
        bottom.addView(hints);
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));

        seekImage=new ImageView(this);seekImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        seekImage.setBackground(shape(0xDD000000,10));seekImage.setVisibility(View.GONE);
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(155),dp(95),Gravity.CENTER);
        root.addView(seekImage,ip);
        setContentView(root);
        video.setOnCompletionListener(mp->{
            if(index>=0 && index<queue.size())repo.savePosition(queue.get(index),0);
            if(index+1<queue.size())showItem(index+1);
            else {status.setText("Semua video selesai");status.setVisibility(View.VISIBLE);}
        });
        video.setOnErrorListener((mp,what,extra)->{
            status.setText("Format video tidak didukung pemutar HP");status.setVisibility(View.VISIBLE);return true;
        });
    }
    private void updateTimeline(){
        if(video==null || scrubbing)return;
        int duration=video.getDuration(),current=video.getCurrentPosition();
        if(duration>0){timeline.setProgress((int)Math.min(1000,1000L*Math.max(0,current)/duration));
            timeLabel.setText(stamp(current)+" / "+stamp(duration));}
    }
    private void toggleControls(){
        controlsVisible=!controlsVisible;
        top.setVisibility(controlsVisible?View.VISIBLE:View.GONE);
        bottom.setVisibility(controlsVisible?View.VISIBLE:View.GONE);
    }
    private void cycleSpeed(){
        speed=speed==1f?1.25f:speed==1.25f?1.5f:speed==1.5f?2f:1f;
        speedButton.setText(speed+"×");applySpeed();
    }
    private void applySpeed(){
        if(prepared!=null && video.isPlaying())try{
            prepared.setPlaybackParams(new PlaybackParams().setSpeed(speed));
        }catch(Exception ignored){}
    }
    private void chooseAspect(){
        new AlertDialog.Builder(this).setTitle("Ukuran video (manual)")
            .setMessage("Default mengikuti proporsi asli seperti feed video. Mode layar penuh hanya aktif jika lu pilih.")
            .setItems(new String[]{"Asli - video utuh", "Penuh - potong tepi bila perlu"},(d,which)->{
                fitMode=which;aspectButton.setText(which==0?"Asli":"Penuh");adjustVideo();
            }).show();
    }
    private void adjustVideo(){
        if(video==null || videoWidth<=0 || videoHeight<=0)return;
        stage.post(()->{
            if(stage==null||videoWidth<=0||videoHeight<=0)return;
            int w=stage.getWidth(),h=stage.getHeight();if(w<=0||h<=0)return;
            double factor=fitMode==0?Math.min((double)w/videoWidth,(double)h/videoHeight):
                Math.max((double)w/videoWidth,(double)h/videoHeight);
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(
                Math.max(1,(int)Math.round(videoWidth*factor)),
                Math.max(1,(int)Math.round(videoHeight*factor)),Gravity.CENTER);
            video.setLayoutParams(p);
        });
    }
    private void showItem(int target){
        if(target<0||target>=queue.size())return;
        if(index>=0&&index<queue.size()&&video!=null&&video.getCurrentPosition()>0)
            repo.savePosition(queue.get(index),video.getCurrentPosition());
        index=target;requestedId=-1;playingUri="";prepared=null;
        loopA=-1;loopB=-1;videoWidth=0;videoHeight=0;
        video.stopPlayback();
        VideoEntry item=queue.get(index);
        title.setText(item.title);position.setText((index+1)+" / "+queue.size());
        backButton.setAlpha(index==0?0.4f:1f);nextButton.setAlpha(index==queue.size()-1?0.4f:1f);
        status.setVisibility(View.VISIBLE);status.setText("Menyiapkan video...");
        repo.setWatched(item,true);
        ViewerExtras.viewed(this,repo.chat(),item.messageId);
        updatePlayback();preloadNeighbors();
    }
    private void preloadNeighbors(){
        if(index>0)repo.requestThumbnail(queue.get(index-1));
        if(index+1<queue.size())repo.requestThumbnail(queue.get(index+1));
        // Feed/player must not prefetch video bytes without user consent.
    }
    private void updatePlayback(){
        if(index<0||index>=queue.size())return;
        VideoEntry item=queue.get(index);Uri uri=item.playableUri();
        if(uri==null)uri=TelegramCacheBridge.uri(item);
        if(uri==null){
            status.setText(item.downloading()||item.progress()>0?
                "Unduhan manual " + item.progress()+"%":"Video tidak ada di cache. Menu ⋮ untuk mengunduh.");
            status.setVisibility(View.VISIBLE);
            return;
        }
        if(uri.toString().equals(playingUri))return;
        playingUri=uri.toString();status.setText("Membuka video...");status.setVisibility(View.VISIBLE);
        video.setVideoURI(uri);
        video.setOnPreparedListener(mp->{
            if(index<0||index>=queue.size()||queue.get(index).messageId!=item.messageId)return;
            prepared=mp;videoWidth=mp.getVideoWidth();videoHeight=mp.getVideoHeight();adjustVideo();
            status.setVisibility(View.GONE);mp.setLooping(false);video.start();
            if(item.positionMs>3000&&item.positionMs<Math.max(0,video.getDuration()-3000))video.seekTo(item.positionMs);
            applySpeed();
        });
        video.requestFocus();
    }
    private void seekBy(int amount){
        if(video!=null && !playingUri.isEmpty())
            video.seekTo(Math.max(0,Math.min(video.getDuration(),video.getCurrentPosition()+amount)));
    }
    private void bindGestures(){
        gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){
                if(dragging)return false;
                if(e.getX()<stage.getWidth()/2f)seekBy(-10000);else seekBy(10000);
                return true;
            }
            @Override public void onLongPress(MotionEvent e){
                if(!dragging && !animating){
                    longSpeed=true;priorSpeed=speed;speed=2f;applySpeed();
                    Toast.makeText(PlayerActivity.this,"Tahan: 2×",Toast.LENGTH_SHORT).show();
                }
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e){
                if(video!=null){if(video.isPlaying())video.pause();else video.start();}
                return true;
            }
        });
        View.OnTouchListener touch=(v,e)->handleTouch(e);
        video.setOnTouchListener(touch);
        currentPage.setOnTouchListener(touch);
    }
    // Gallery videos now open VerticalFeedActivity. The full player keeps
    // only tap/double-tap/hold controls; swipes do NOT adjust volume/brightness.
    private boolean handleTouch(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount()>=3){
            video.pause();AppLock.lock(this);return true;
        }
        gestures.onTouchEvent(e);
        if(longSpeed && (e.getActionMasked()==MotionEvent.ACTION_UP ||
                e.getActionMasked()==MotionEvent.ACTION_CANCEL)){
            longSpeed=false;speed=priorSpeed;applySpeed();
        }
        return true;
    }
    private int pageHeight(){return Math.max(1,stage.getHeight()>0?stage.getHeight():getResources().getDisplayMetrics().heightPixels);}
    private void loadIncoming(int dir){
        int target=index+dir;
        if(target<0||target>=queue.size())return;
        VideoEntry next=queue.get(target);
        incomingTitle.setText(next.title);
        Bitmap thumb=null;
        String path=next.thumbPath();
        if(!path.isEmpty()){
            BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;
            thumb=BitmapFactory.decodeFile(path,o);
        }
        if(thumb==null && next.preview!=null)thumb=BitmapFactory.decodeByteArray(next.preview,0,next.preview.length);
        incomingImage.setImageBitmap(thumb);
        incomingPage.setVisibility(View.VISIBLE);
        incomingPage.setTranslationY(pageHeight()*dir);
    }
    private void animateSnap(int direction){
        float from=currentPage.getTranslationY();int h=pageHeight();
        float to=direction==0?0f:-direction*h;
        animating=true;
        settle=ValueAnimator.ofFloat(from,to);settle.setDuration(220);settle.setInterpolator(new DecelerateInterpolator());
        int initial=dragDirection;
        settle.addUpdateListener(a->{
            float y=(float)a.getAnimatedValue();currentPage.setTranslationY(y);
            if(initial!=0)incomingPage.setTranslationY(y+initial*h);
        });
        settle.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animation){
                currentPage.setTranslationY(0);
                incomingPage.setVisibility(View.GONE);
                incomingPage.setTranslationY(0);
                animating=false;dragDirection=0;pageOffset=0;
                if(direction!=0&&!isFinishing())showItem(index+direction);
            }
        });settle.start();
    }
    private void seekFrame(int wanted){
        if(index<0||index>=queue.size() || SystemClock.elapsedRealtime()-lastFrameTime<320)return;
        lastFrameTime=SystemClock.elapsedRealtime();
        Uri cachedUri=queue.get(index).playableUri();
        final Uri uri=cachedUri!=null?cachedUri:TelegramCacheBridge.uri(queue.get(index));
        if(uri==null)return;
        int token=++previewGeneration;
        frames.execute(()->{
            Bitmap thumb=null;MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try{retriever.setDataSource(PlayerActivity.this,uri);
                Bitmap full=retriever.getFrameAtTime(wanted*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if(full!=null){thumb=Bitmap.createScaledBitmap(full,dp(150),dp(85),true);
                    if(thumb!=full)full.recycle();}
            }catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
            Bitmap result=thumb;runOnUiThread(()->{
                if(token==previewGeneration && scrubbing && result!=null)seekImage.setImageBitmap(result);
                else if(result!=null)result.recycle();
            });
        });
    }
    private void captureFrame(){
        if(index<0||index>=queue.size())return;
        VideoEntry item=queue.get(index);Uri cachedUri=item.playableUri();
        final Uri uri=cachedUri!=null?cachedUri:TelegramCacheBridge.uri(item);
        if(uri==null){Toast.makeText(this,"Video belum tersedia offline",Toast.LENGTH_SHORT).show();return;}
        int ms=video.getCurrentPosition();
        frames.execute(()->{
            MediaMetadataRetriever r=new MediaMetadataRetriever();
            try{
                r.setDataSource(PlayerActivity.this,uri);
                Bitmap b=r.getFrameAtTime(ms*1000L,MediaMetadataRetriever.OPTION_CLOSEST);
                if(b==null)throw new IllegalStateException("Frame belum tersedia");
                String name=repo.chat()+"_"+item.messageId+"_"+ms+".jpg";
                File dest=new File(ViewerExtras.captures(this),name);
                try(FileOutputStream out=new FileOutputStream(dest)){b.compress(Bitmap.CompressFormat.JPEG,90,out);}
                b.recycle();runOnUiThread(()->Toast.makeText(this,"Frame tersimpan privat di VaultGram",Toast.LENGTH_LONG).show());
            }catch(Exception ex){runOnUiThread(()->Toast.makeText(this,"Tidak dapat mengambil frame: "+ex.getMessage(),Toast.LENGTH_SHORT).show());}
            finally{try{r.release();}catch(Exception ignored){}}
        });
    }
    private void bookmarkMenu(){
        VideoEntry item=queue.get(index);
        List<Integer> marks=ViewerExtras.bookmarks(this,repo.chat(),item.messageId);
        String[] choices=new String[marks.size()+1];choices[0]="+ Tandai detik " + stamp(video.getCurrentPosition());
        for(int i=0;i<marks.size();i++)choices[i+1]="Lompat ke "+stamp(marks.get(i));
        new AlertDialog.Builder(this).setTitle("Bookmark video").setItems(choices,(d,which)->{
            if(which==0){ViewerExtras.addBookmark(this,repo.chat(),item.messageId,video.getCurrentPosition());
                Toast.makeText(this,"Bookmark ditambahkan",Toast.LENGTH_SHORT).show();}
            else video.seekTo(marks.get(which-1));
        }).setNeutralButton("Hapus semua",(d,w)->ViewerExtras.setBookmarks(this,repo.chat(),item.messageId,new ArrayList<>()))
        .setNegativeButton("Tutup",null).show();
    }
    private void loopMenu(){
        String a=loopA<0?"belum":stamp(loopA),b=loopB<0?"belum":stamp(loopB);
        new AlertDialog.Builder(this).setTitle("Ulang bagian video (A-B)")
            .setItems(new String[]{"Tentukan A: "+a,"Tentukan B: "+b,"Hapus A-B loop"},(d,which)->{
                if(which==0){loopA=video.getCurrentPosition();loopB=-1;}
                else if(which==1){int current=video.getCurrentPosition();
                    if(loopA<0||current<=loopA+800)Toast.makeText(this,"Set A dulu, lalu pilih B setelah A",Toast.LENGTH_LONG).show();
                    else {loopB=current;video.seekTo(loopA);}}
                else{loopA=-1;loopB=-1;}
            }).show();
    }
    private void sleepMenu(){
        new AlertDialog.Builder(this).setTitle("Sleep timer")
            .setItems(new String[]{"Mati","15 menit","30 menit","60 menit"},(d,which)->{
                sleepAt=which==0?0:SystemClock.elapsedRealtime()+which*15L*60_000L;
                sleepTriggered=false;timerLabel.setText(which==0?"Timer mati":"Timer "+(which*15)+" menit");
            }).show();
    }
    private void playerMenu(){
        VideoEntry item=queue.get(index);
        new AlertDialog.Builder(this).setTitle("Aksi Video")
            .setItems(new String[]{"Bookmark detik video","Ulang bagian A-B","Sleep timer",
                "Simpan potret ke Vault privat","Lihat potret & file pribadi",
                "Sembunyikan video dari galeri","Kunci darurat","Hemat data: "+(ViewerExtras.dataSaver(this)?"Aktif":"Mati"),"Unduh video (pakai internet)"},(d,which)->{
                switch(which){
                    case 0:bookmarkMenu();break;
                    case 1:loopMenu();break;
                    case 2:sleepMenu();break;
                    case 3:captureFrame();break;
                    case 4:startActivity(new Intent(this,PrivateMediaActivity.class));break;
                    case 5:ViewerExtras.setHidden(this,repo.chat(),item.messageId,true);
                        Toast.makeText(this,"Video disembunyikan dari galeri utama",Toast.LENGTH_SHORT).show();break;
                    case 6:video.pause();AppLock.lock(this);break;
                    case 7:ViewerExtras.setDataSaver(this,!ViewerExtras.dataSaver(this));break;
                    case 8:new AlertDialog.Builder(this)
                        .setTitle("Unduh video dari Telegram?")
                        .setMessage("Menggunakan internet dan mungkin kuota seluler.")
                        .setNegativeButton("Batal",null)
                        .setPositiveButton("Unduh",(dialog,choice)->repo.download(item))
                        .show();break;
                }
            }).show();
    }
    @Override protected void onResume(){super.onResume();if(repo!=null)repo.bind(this);AppLock.gate(this);if(!AppLock.isUnlocked()&&video!=null)video.pause();}
    @Override protected void onPause(){
        if(video!=null){if(index>=0&&index<queue.size()&&video.getCurrentPosition()>0)
            repo.savePosition(queue.get(index),video.getCurrentPosition());video.pause();}
        if(repo!=null)repo.unbind(this);super.onPause();
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(tick);
        if(settle!=null)settle.cancel();
        frames.shutdownNow();
        if(video!=null)video.stopPlayback();
        super.onDestroy();
    }
    @Override public void onStage(TelegramRepository.Stage stage,String message){}
    @Override public void onAccount(String account){}
    @Override public void onBot(String username){}
    @Override public void onVideosChanged(){updatePlayback();}
    @Override public void onStatus(String message){}
    @Override public void onError(String message){status.setText(message);status.setVisibility(View.VISIBLE);}
}
