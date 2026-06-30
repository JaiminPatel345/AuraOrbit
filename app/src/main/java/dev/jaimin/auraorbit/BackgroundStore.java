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

    public static boolean exists(Context c) {
        return file(c).exists();
    }
    
    public static void clear(Context c) {
        File f = file(c);
        if (f.exists()) {
            f.delete();
        }
        bumpVersion(c);
    }
    
    public static boolean saveFromUri(Context context, Uri uri) {
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

            File f = file(context);
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
    
    public static File file(Context context) {
        return new File(context.getFilesDir(), DEFAULT_FILE_NAME);
    }
}
