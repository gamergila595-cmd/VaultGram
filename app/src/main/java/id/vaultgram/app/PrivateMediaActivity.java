package id.vaultgram.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Video imports and captured frames stay in the app-private directory. */
public final class PrivateMediaActivity extends Activity {
    private static final int IMPORT_VIDEO=71;
    private FrameLayout root;
    private VideoView playing;
    private List<File> current;
    private int currentPosition=-1;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        root=new FrameLayout(this);root.setBackgroundColor(0xFF0A1020);setContentView(root);
        renderList();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);
        t.setTextSize(size);t.setPadding(dp(13),dp(12),dp(13),dp(12));return t;
    }
    private void renderList(){
        if(playing!=null){playing.stopPlayback();playing=null;}
        currentPosition=-1;root.removeAllViews();
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14),dp(26),dp(14),dp(36));scroll.addView(list);
        TextView title=text("‹   File Pribadi VaultGram",23);
        title.setOnClickListener(v->finish());list.addView(title);
        TextView note=text("File hanya ada di ruang privat VaultGram, bukan Galeri HP. " +
            "Menghapus data aplikasi juga menghapus file yang belum dibackup.",12);
        note.setTextColor(0xFF98A5B8);list.addView(note);
        TextView importButton=text("+ Impor video dari HP",16);
        importButton.setBackgroundColor(0xFF23463F);
        importButton.setOnClickListener(v->pickImport());list.addView(importButton);
        List<File> imports=ViewerExtras.files(ViewerExtras.imports(this));
        List<File> shots=ViewerExtras.files(ViewerExtras.captures(this));
        list.addView(text("VIDEO PRIVAT  ·  "+imports.size(),13));
        if(imports.isEmpty())list.addView(text("Belum ada video yang diimpor",12));
        for(File f:imports)list.addView(row(f,true));
        list.addView(text("POTRET VIDEO  ·  "+shots.size(),13));
        if(shots.isEmpty())list.addView(text("Potret video belum ada",12));
        for(File f:shots)list.addView(row(f,false));
    }
    private View row(File f,boolean isVideo){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xFF16263A);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(7);row.setLayoutParams(lp);
        String name=f.getName();
        TextView item=text((isVideo?"▶ ":"▣ ")+name+"\n"+
            String.format(Locale.US,"%.1f MB",f.length()/1048576.0),12);
        item.setMaxLines(2);item.setOnClickListener(v->{
            if(isVideo)openVideo(f);else openImage(f);
        });
        row.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        TextView action=text("⋮",23);action.setOnClickListener(v->fileActions(f,isVideo));
        row.addView(action);return row;
    }
    private void fileActions(File f,boolean video){
        new AlertDialog.Builder(this).setTitle(f.getName())
            .setItems(new String[]{video?"Putar video":"Lihat potret", "Bagikan sementara", "Hapus file privat"},(d,which)->{
                if(which==0){if(video)openVideo(f);else openImage(f);}
                else if(which==1)VaultShareProvider.share(this,Uri.fromFile(f),video?"video/*":"image/jpeg");
                else new AlertDialog.Builder(this).setTitle("Hapus file privat?")
                    .setMessage("Tindakan ini tidak bisa dibatalkan kecuali file sudah dibackup.")
                    .setNegativeButton("Batal",null).setPositiveButton("Hapus",(dlg,w)->{
                        if(f.delete())renderList();else Toast.makeText(this,"Gagal menghapus file",Toast.LENGTH_SHORT).show();
                    }).show();
            }).show();
    }
    private void pickImport(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("video/*");
        startActivityForResult(i,IMPORT_VIDEO);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_VIDEO||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        String name="video.mp4";
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())name=c.getString(0);
        }catch(Exception ignored){}
        String safe=ViewerExtras.safeName(name);
        if(!safe.matches("(?i).*\\.(mp4|mkv|webm|mov|m4v|avi|3gp)$"))safe+=".mp4";
        File output=new File(ViewerExtras.imports(this),System.currentTimeMillis()+"_"+safe);
        Toast.makeText(this,"Mengimpor video ke ruang privat...",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try(InputStream in=getContentResolver().openInputStream(uri);
                FileOutputStream out=new FileOutputStream(output)){
                if(in==null)throw new IllegalArgumentException("File tidak bisa dibaca");
                byte[] block=new byte[65536];int count;
                while((count=in.read(block))!=-1)out.write(block,0,count);
                runOnUiThread(()->{Toast.makeText(this,"Video diimpor. File sumber tetap utuh.",Toast.LENGTH_LONG).show();renderList();});
            }catch(Exception e){output.delete();runOnUiThread(()->Toast.makeText(this,"Gagal impor: "+e.getMessage(),Toast.LENGTH_LONG).show());}
        },"VaultGram-PrivateImport").start();
    }
    private void openVideo(File f){
        current=ViewerExtras.files(ViewerExtras.imports(this));currentPosition=current.indexOf(f);
        if(currentPosition<0)return;
        showVideo(currentPosition);
    }
    private void showVideo(int index){
        if(current==null||index<0||index>=current.size())return;
        File file=current.get(index);
        if(!file.isFile()){renderList();return;}
        currentPosition=index;
        if(playing!=null)playing.stopPlayback();root.removeAllViews();
        playing=new VideoView(this);
        root.addView(playing,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        MediaController controller=new MediaController(this);
        controller.setAnchorView(playing);playing.setMediaController(controller);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xAA000000);
        TextView back=text("‹ Kembali",14);back.setOnClickListener(v->renderList());
        TextView prev=text("‹",23);prev.setOnClickListener(v->showVideo(currentPosition-1));
        TextView count=text((index+1)+" / "+current.size(),13);
        TextView next=text("›",23);next.setOnClickListener(v->showVideo(currentPosition+1));
        bar.addView(back,new LinearLayout.LayoutParams(0,-2,2));
        bar.addView(prev);bar.addView(count);bar.addView(next);
        root.addView(bar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        playing.setVideoPath(file.getAbsolutePath());playing.setOnPreparedListener(mp->playing.start());
        playing.setOnCompletionListener(mp->{if(currentPosition+1<current.size())showVideo(currentPosition+1);});
        playing.requestFocus();
    }
    private void openImage(File f){
        BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;
        Bitmap b=BitmapFactory.decodeFile(f.getAbsolutePath(),o);
        if(b==null){Toast.makeText(this,"Potret tidak dapat dibaca",Toast.LENGTH_SHORT).show();return;}
        ImageView view=new ImageView(this);view.setImageBitmap(b);
        view.setAdjustViewBounds(true);view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        new AlertDialog.Builder(this).setTitle("Potret privat").setView(view)
            .setPositiveButton("Tutup",null).show();
    }
    @Override public void onBackPressed(){
        if(currentPosition>=0)renderList();else super.onBackPressed();
    }
    @Override protected void onResume(){super.onResume();AppLock.gate(this);}
    @Override protected void onPause(){if(playing!=null)playing.pause();super.onPause();}
    @Override protected void onDestroy(){if(playing!=null)playing.stopPlayback();super.onDestroy();}
}
