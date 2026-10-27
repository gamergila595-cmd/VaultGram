package id.vaultgram.app;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** Temporary, read-only URI grant; no permanent copy in the public gallery. */
public final class VaultShareProvider extends ContentProvider {
    private static final String AUTHORITY="id.vaultgram.app.private_share";
    private static SharedPreferences settings(Context context){
        return context.getSharedPreferences("vaultgram_share_grants",Context.MODE_PRIVATE);
    }
    static void share(Activity activity,Uri localOrContent,String type){
        if(localOrContent==null)return;
        try{
            Uri stream=localOrContent;
            if("file".equalsIgnoreCase(localOrContent.getScheme())){
                File f=new File(localOrContent.getPath());
                String root=activity.getFilesDir().getCanonicalPath()+File.separator;
                String path=f.getCanonicalPath();
                if(!path.startsWith(root)||!f.isFile())throw new SecurityException("Bukan file privat VaultGram");
                String token=UUID.randomUUID().toString().replace("-","");
                settings(activity).edit().putString("path_"+token,path)
                    .putLong("expires_"+token,System.currentTimeMillis()+30L*60_000L).apply();
                stream=new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build();
            }
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType(type);send.putExtra(Intent.EXTRA_STREAM,stream);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newUri(activity.getContentResolver(),"VaultGram",stream));
            activity.startActivity(Intent.createChooser(send,"Bagikan video dengan akses sementara"));
        }catch(Exception e){android.widget.Toast.makeText(activity,"Gagal membagikan: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
    }
    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode)||uri==null||uri.getPathSegments().size()!=1)throw new FileNotFoundException("Tidak diizinkan");
        Context c=getContext();if(c==null)throw new FileNotFoundException();
        String token=uri.getLastPathSegment();
        if(token==null||!token.matches("[a-f0-9]{32}"))throw new FileNotFoundException();
        SharedPreferences p=settings(c);
        if(System.currentTimeMillis()>p.getLong("expires_"+token,0))throw new FileNotFoundException("Tautan kedaluwarsa");
        String path=p.getString("path_"+token,"");
        try{
            File file=new File(path);
            if(!file.isFile()||!file.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))
                throw new FileNotFoundException("Berkas tidak tersedia");
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        }catch(IOException e){throw new FileNotFoundException("File tidak dapat diakses");}
    }
    @Override public String getType(Uri uri){
        Context c=getContext();if(c==null)return "application/octet-stream";
        String token=uri.getLastPathSegment();
        String path=settings(c).getString("path_"+token,"");
        int dot=path.lastIndexOf('.');String ext=dot<0?"":path.substring(dot+1).toLowerCase(java.util.Locale.US);
        String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime==null?"application/octet-stream":mime;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
}
