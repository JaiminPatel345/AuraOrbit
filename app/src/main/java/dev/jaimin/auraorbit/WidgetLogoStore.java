package dev.jaimin.auraorbit;

import android.content.Context;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class WidgetLogoStore {
    private static final String DEFAULT_FILE_NAME = "widget_logo.png";

    public static boolean exists(Context context, String widgetName) {
        return file(context, widgetName).exists();
    }
    
    public static boolean exists(Context context) {
        return exists(context, null);
    }

    public static void clear(Context context, String widgetName) {
        File f = file(context, widgetName);
        if (f.exists()) {
            f.delete();
        }
    }
    
    public static void clear(Context context) {
        clear(context, null);
    }

    public static boolean saveFromUri(Context context, Uri uri, String widgetName) {
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, options);
            in.close();
            
            int maxSize = 512;
            int scale = 1;
            while ((options.outWidth / scale) > maxSize || (options.outHeight / scale) > maxSize) {
                scale *= 2;
            }

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

            File f = file(context, widgetName);
            FileOutputStream out = new FileOutputStream(f);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            bitmap.recycle();
            
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean saveFromUri(Context context, Uri uri) {
        return saveFromUri(context, uri, null);
    }

    public static File file(Context context, String widgetName) {
        String fileName = (widgetName == null || widgetName.isEmpty()) 
                ? DEFAULT_FILE_NAME 
                : "widget_logo_" + widgetName.replaceAll("[^a-zA-Z0-9_-]", "") + ".png";
        return new File(context.getFilesDir(), fileName);
    }
    
    public static File file(Context context) {
        return file(context, null);
    }
}
