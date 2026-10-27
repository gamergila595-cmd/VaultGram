package id.vaultgram.app;

import android.app.Activity;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** User-password encrypted, portable backup. Never exports Telegram API credentials or the TDLib session. */
final class PrivateBackup {
    interface Callback { void done(String message, boolean ok); }
    private static final int MAX_META=32*1024*1024;
    private static final byte[] HEADER=new byte[]{'V','G','B','2'};
    private static final SecureRandom RNG=new SecureRandom();
    private static SecretKeySpec key(String password,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,210000,256);
        try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).getEncoded(),"AES");}
        finally{spec.clearPassword();}
    }
    private static void copy(InputStream in,OutputStream out)throws Exception{
        byte[] buf=new byte[131072];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    private static void inform(Activity a,Callback cb,String message,boolean ok){
        a.runOnUiThread(()->cb.done(message,ok));
    }
    static void export(Activity a,Uri target,String password,boolean withVideos,TelegramRepository repo,Callback cb){
        List<VideoEntry> items=repo.snapshot();
        String bot=repo.bot(),account=repo.prefs.get("account_id");
        long chat=repo.chat();
        new Thread(()->{
            try(OutputStream dest=a.getContentResolver().openOutputStream(target,"w")){
                if(dest==null)throw new IllegalStateException("Tidak bisa membuka file tujuan");
                byte[] salt=new byte[16],iv=new byte[12];RNG.nextBytes(salt);RNG.nextBytes(iv);
                dest.write(HEADER);dest.write(salt);dest.write(iv);
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,key(password,salt),new GCMParameterSpec(128,iv));
                int copied=0;
                try(ZipOutputStream zip=new ZipOutputStream(new CipherOutputStream(dest,cipher))){
                    JSONObject json=new JSONObject();json.put("format",2);json.put("chat",Long.toString(chat));
                    json.put("bot",bot);json.put("account",account);
                    JSONArray videoList=new JSONArray();Set<Integer> ids=new HashSet<>();
                    for(VideoEntry e:items){
                        JSONObject v=new JSONObject();
                        v.put("id",Long.toString(e.messageId));v.put("file",e.fileId);
                        v.put("date",e.date);v.put("name",e.title);v.put("duration",e.duration);
                        v.put("size",e.size());v.put("favorite",e.favorite);
                        v.put("watched",e.watched);v.put("position",e.positionMs);v.put("trashed",e.trashedAt);
                        if(e.preview!=null && e.preview.length<65536)
                            v.put("preview",Base64.encodeToString(e.preview,Base64.NO_WRAP));
                        if(withVideos && e.complete() && ids.add(e.fileId)){
                            v.put("backup_file",e.fileId);
                        }
                        videoList.put(v);
                    }
                    json.put("videos",videoList);
                    zip.putNextEntry(new ZipEntry("catalog.json"));
                    zip.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                    if(withVideos){
                        Set<Integer> written=new HashSet<>();
                        for(VideoEntry e:items){
                            if(!e.complete() || !ids.contains(e.fileId) || !written.add(e.fileId))continue;
                            try(InputStream in=a.getContentResolver().openInputStream(e.playableUri())){
                                if(in==null)continue;
                                zip.putNextEntry(new ZipEntry("videos/"+e.fileId+".bin"));
                                copy(in,zip);zip.closeEntry();copied++;
                            }catch(Exception ignored){ /* One inaccessible file must not destroy the catalog backup. */ }
                        }
                    }
                }
                inform(a,cb,"Backup terenkripsi tersimpan · "+items.size()+" catatan, "+copied+" file video.",true);
            }catch(Exception e){inform(a,cb,"Backup gagal: "+e.getMessage(),false);}
        },"VaultGram-Backup").start();
    }
    static void restore(Activity a,Uri source,String password,TelegramRepository repo,Callback cb){
        new Thread(()->{
            File staging=null;
            try(InputStream from=a.getContentResolver().openInputStream(source)){
                if(from==null)throw new IllegalStateException("File tidak bisa dibaca");
                byte[] header=new byte[4],salt=new byte[16],iv=new byte[12];
                readFully(from,header);readFully(from,salt);readFully(from,iv);
                if(!java.util.Arrays.equals(header,HEADER))throw new IllegalArgumentException("Format backup tidak dikenal");
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,key(password,salt),new GCMParameterSpec(128,iv));
                staging=File.createTempFile("vaultgram-import-",".zip",a.getCacheDir());
                // Fully authenticate GCM before importing any catalog state or video.
                try(CipherInputStream decrypted=new CipherInputStream(from,cipher);
                    FileOutputStream tmp=new FileOutputStream(staging)){copy(decrypted,tmp);}
                JSONObject json=null;
                Map<Integer,File> videoFiles=new HashMap<>();
                File privateFolder=new File(a.getFilesDir(),"restored_videos");
                if(!privateFolder.exists() && !privateFolder.mkdirs())throw new IllegalStateException("Folder privat tidak tersedia");
                try(ZipInputStream zip=new ZipInputStream(new FileInputStream(staging))){
                    ZipEntry entry;
                    while((entry=zip.getNextEntry())!=null){
                        String name=entry.getName();
                        if(name.equals("catalog.json")){
                            ByteArrayOutputStream buf=new ByteArrayOutputStream();
                            byte[] block=new byte[16384];int n;
                            while((n=zip.read(block))!=-1){
                                if(buf.size()+n>MAX_META)throw new IllegalArgumentException("Katalog terlalu besar");
                                buf.write(block,0,n);
                            }
                            json=new JSONObject(buf.toString("UTF-8"));
                        }else if(name.matches("videos/[0-9]+\\.bin")){
                            int id=Integer.parseInt(name.substring(7,name.length()-4));
                            File dest=new File(privateFolder,id+".bin");
                            try(FileOutputStream out=new FileOutputStream(dest)){copy(zip,out);}
                            videoFiles.put(id,dest);
                        }
                        zip.closeEntry();
                    }
                }
                if(json==null || json.optInt("format")!=2)throw new IllegalArgumentException("Katalog backup tidak valid");
                String account=json.optString("account"),current=repo.prefs.get("account_id");
                if(!current.isEmpty() && !current.equals(account))
                    throw new IllegalArgumentException("Backup dari akun Telegram berbeda");
                long chat=Long.parseLong(json.getString("chat"));String bot=json.getString("bot");
                JSONArray list=json.getJSONArray("videos");
                ArrayList<VideoEntry> imported=new ArrayList<>();
                for(int i=0;i<list.length();i++){
                    JSONObject v=list.getJSONObject(i);
                    int fid=v.getInt("file");File f=videoFiles.get(fid);
                    byte[] preview=null;
                    if(v.has("preview"))preview=Base64.decode(v.getString("preview"),Base64.NO_WRAP);
                    VideoEntry e=new VideoEntry(Long.parseLong(v.getString("id")),v.optInt("date"),v.optString("name"),
                        v.optInt("duration"),fid,v.optLong("size"),f==null?"":f.getAbsolutePath(),"",preview);
                    e.favorite=v.optBoolean("favorite");e.watched=v.optBoolean("watched");
                    e.positionMs=v.optInt("position");e.trashedAt=v.optLong("trashed");
                    imported.add(e);
                }
                a.runOnUiThread(()->{
                    try{repo.importCatalog(chat,bot,account,imported);
                        cb.done("Pulih "+imported.size()+" video (file lokal: "+videoFiles.size()+").",true);
                    }catch(Exception e){cb.done("Pemulihan gagal: "+e.getMessage(),false);}
                });
            }catch(Exception e){inform(a,cb,"Restore gagal: "+e.getMessage(),false);}
            finally{if(staging!=null)staging.delete();}
        },"VaultGram-Restore").start();
    }
    private static void readFully(InputStream input,byte[] into)throws Exception{
        int off=0;while(off<into.length){int n=input.read(into,off,into.length-off);
            if(n==-1)throw new IllegalArgumentException("Backup terpotong");off+=n;}
    }
}
