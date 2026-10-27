package id.vaultgram.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local-only viewing preferences, bookmarks, history and private media folders. */
final class ViewerExtras {
    private ViewerExtras() {}
    private static SharedPreferences preferences(Context c) {
        return c.getApplicationContext().getSharedPreferences("vaultgram_viewer_v13", Context.MODE_PRIVATE);
    }
    private static String key(long chat, long message) { return chat + "_" + message; }
    static boolean hidden(Context c, long chat, long message) {
        return preferences(c).getBoolean("hidden_" + key(chat,message), false);
    }
    static void setHidden(Context c, long chat, long message, boolean value) {
        preferences(c).edit().putBoolean("hidden_" + key(chat,message), value).apply();
    }
    static long viewedAt(Context c, long chat, long message) {
        return preferences(c).getLong("view_" + key(chat,message), 0);
    }
    static void setViewedAt(Context c, long chat, long message, long time) {
        preferences(c).edit().putLong("view_" + key(chat,message), Math.max(0,time)).apply();
    }
    static void viewed(Context c, long chat, long message) {
        setViewedAt(c,chat,message,System.currentTimeMillis());
    }
    static void clearHistory(Context c, long chat, List<VideoEntry> videos) {
        SharedPreferences.Editor e=preferences(c).edit();
        for(VideoEntry v:videos)e.remove("view_"+key(chat,v.messageId));
        e.apply();
    }
    static List<Integer> bookmarks(Context c, long chat, long message) {
        String encoded=preferences(c).getString("book_"+key(chat,message),"");
        ArrayList<Integer> out=new ArrayList<>();
        for(String part:encoded.split(","))try{
            int ms=Integer.parseInt(part);if(ms>=0)out.add(ms);
        }catch(NumberFormatException ignored){}
        Collections.sort(out);return out;
    }
    static void setBookmarks(Context c, long chat, long message, List<Integer> ms) {
        StringBuilder b=new StringBuilder();
        for(int v:ms){if(v<0)continue;if(b.length()>0)b.append(',');b.append(v);}
        preferences(c).edit().putString("book_"+key(chat,message),b.toString()).apply();
    }
    static void addBookmark(Context c,long chat,long message,int ms) {
        List<Integer> times=bookmarks(c,chat,message);
        for(int old:times)if(Math.abs(old-ms)<1800)return;
        times.add(ms);setBookmarks(c,chat,message,times);
    }
    static boolean dataSaver(Context c) {return preferences(c).getBoolean("data_saver",false);}
    static void setDataSaver(Context c,boolean value){preferences(c).edit().putBoolean("data_saver",value).apply();}
    static File captures(Context c) {
        File f=new File(c.getFilesDir(),"vaultgram_captures");
        if(!f.exists())f.mkdirs();return f;
    }
    static File imports(Context c) {
        File f=new File(c.getFilesDir(),"vaultgram_imports");
        if(!f.exists())f.mkdirs();return f;
    }
    static List<File> files(File dir) {
        File[] files=dir.listFiles(f->f.isFile());
        if(files==null)return Collections.emptyList();
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        return new ArrayList<>(Arrays.asList(files));
    }
    static String safeName(String name) {
        String s=name==null?"video":name.replaceAll("[^a-zA-Z0-9._ -]","_");
        if(s.isEmpty())s="video";
        return s.length()>80?s.substring(s.length()-80):s;
    }
}
