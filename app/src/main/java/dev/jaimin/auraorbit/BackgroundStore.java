package dev.jaimin.auraorbit;

import android.content.Context;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class BackgroundStore {
    public static final String PREF_BACKGROUND_VERSION = "bg_ver";
    private static final String DEFAULT_FILE_NAME = "background.png";

    public static boolean exists(Context c, String groupName) {
        return file(c, groupName).exists();
    }
    
    public static boolean exists(Context c) {
        return exists(c, null);
    }
    
    public static void clear(Context c, String groupName) {
        File f = file(c, groupName);
        if (f.exists()) {
            f.delete();
        }
        bumpVersion(c);
    }

    public static void clear(Context c) {
        clear(c, null);
    }
    
    public static boolean saveFromUri(Context context, Uri uri, String groupName) {
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, options);
            in.close();
            
            int maxSize = 2048;
            int scale = computeSampleSize(options.outWidth, options.outHeight, maxSize);

            BitmapFactory.Options options2 = new BitmapFactory.Options();
            options2.inSampleSize = scale;
            in = context.getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options2);
            in.close();

            if (bitmap == null) return false;

            // Scale precisely
            if (bitmap.getWidth() > maxSize || bitmap.getHeight() > maxSize) {
                float ratio = Math.min((float) maxSize / bitmap.getWidth(), (float) maxSize / bitmap.getHeight());
                int width = Math.round((float) ratio * bitmap.getWidth());
                int height = Math.round((float) ratio * bitmap.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            File f = file(context, groupName);
            FileOutputStream out = new FileOutputStream(f);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            bitmap.recycle();
            
            bumpVersion(context);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean saveFromUri(Context context, Uri uri) {
        return saveFromUri(context, uri, null);
    }
    
    public static int computeSampleSize(int width, int height, int maxSize) {
        int scale = 1;
        while ((width / scale) > maxSize || (height / scale) > maxSize) {
            scale *= 2;
        }
        return scale;
    }
    
    private static void bumpVersion(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int v = prefs.getInt(PREF_BACKGROUND_VERSION, 0);
        prefs.edit().putInt(PREF_BACKGROUND_VERSION, v + 1).apply();
    }
    
    public static File file(Context context, String groupName) {
        String fileName = (groupName == null || groupName.isEmpty()) 
                ? DEFAULT_FILE_NAME 
                : "background_" + groupName.replaceAll("[^a-zA-Z0-9_-]", "") + ".png";
        return new File(context.getFilesDir(), fileName);
    }

    public static File file(Context context) {
        return file(context, null);
    }
}
