package dev.jaimin.auraorbit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * BackgroundStore.java — Custom Background Image Persistence
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Handles saving, querying, and clearing the optional custom background image
 * that replaces the system wallpaper as the sphere's background plane.
 *
 * ─── Storage Layout ─────────────────────────────────────────────────────────
 *
 * The background is stored as {@code <filesDir>/background.jpg}. A version
 * counter ({@link #PREF_BACKGROUND_VERSION}) in the default SharedPreferences
 * is incremented on every write or clear so that the SphereEngine knows to
 * reload the texture on the next frame.
 *
 * ─── Image Decoding Strategy ────────────────────────────────────────────────
 *
 * A two-pass BitmapFactory strategy keeps peak heap memory low:
 *
 * 1. {@code inJustDecodeBounds = true} — reads dimensions without allocating
 *    pixel memory.
 * 2. Compute a power-of-2 {@code inSampleSize} so the decoded image fits
 *    within 2048 × 2048 pixels.
 * 3. Full decode with the computed sample size.
 *
 * The result is compressed to JPEG quality 90 into a temporary file
 * ({@code background.jpg.tmp}), then atomically renamed to {@code background.jpg}.
 * On any exception the temporary file is deleted and {@code false} is returned.
 *
 * ─── Thread Safety ──────────────────────────────────────────────────────────
 *
 * {@link #saveFromUri} performs I/O and must NOT be called on the main thread.
 * All other methods are lightweight and can be called from any thread.
 */
public final class BackgroundStore {

    /** File name for the saved background image inside {@code filesDir}. */
    public static final String FILE_NAME = "background.jpg";

    /**
     * SharedPreferences key for the background version counter.
     * SphereEngine reads this value and reloads the background texture whenever
     * it changes (i.e., after a save or clear).
     */
    public static final String PREF_BACKGROUND_VERSION = "pref_background_version";

    /**
     * Maximum pixel dimension (width or height) after down-sampling.
     * Keeps GPU texture memory and JPEG file size reasonable.
     */
    private static final int MAX_DIMENSION = 2048;

    /** JPEG compression quality (0–100). 90 gives excellent quality at ~200–400 KB. */
    private static final int JPEG_QUALITY = 90;

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns the {@link File} object for the stored background image.
     *
     * The file may not exist; use {@link #exists(Context)} to check before reading.
     *
     * @param ctx  Android context (used for {@link Context#getFilesDir()})
     * @return File reference to {@code <filesDir>/background.jpg}
     */
    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /**
     * Returns {@code true} if a custom background image has been saved.
     *
     * @param ctx  Android context
     * @return {@code true} if {@code background.jpg} exists and is non-empty
     */
    public static boolean exists(Context ctx) {
        File f = file(ctx);
        return f.exists() && f.length() > 0;
    }

    /**
     * Decodes the image at the given {@link Uri}, down-samples it so its maximum
     * dimension is at most 2048 pixels, compresses it as JPEG quality 90, and
     * atomically replaces {@code background.jpg}.
     *
     * ─── Steps ──────────────────────────────────────────────────────────
     * 1. Open a {@link ContentResolver} stream and read just the bounds.
     * 2. Compute {@code inSampleSize} (next power of 2 ≥ max(w,h) / 2048).
     * 3. Re-open the stream and fully decode with the computed sample size.
     * 4. Compress JPEG quality 90 into {@code background.jpg.tmp}.
     * 5. Rename tmp → final file.
     * 6. Increment the background version pref so the engine reloads.
     *
     * MUST be called off the main thread.
     *
     * @param ctx  Android context (for ContentResolver and filesDir)
     * @param uri  Content URI of the source image (e.g., from image picker)
     * @return {@code true} on success; {@code false} if any step fails (tmp deleted)
     */
    public static boolean saveFromUri(Context ctx, Uri uri) {
        File tmpFile = new File(ctx.getFilesDir(), FILE_NAME + ".tmp");
        try {
            // ─── Pass 1: read image dimensions ─────────────────────────────
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;

            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
            }

            int srcWidth  = opts.outWidth;
            int srcHeight = opts.outHeight;

            // ─── Compute power-of-2 sample size ────────────────────────────
            opts.inSampleSize = computeSampleSize(srcWidth, srcHeight, MAX_DIMENSION);
            opts.inJustDecodeBounds = false;

            // ─── Pass 2: full decode ────────────────────────────────────────
            Bitmap bitmap;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }

            if (bitmap == null) return false;

            // ─── Compress to tmp file ───────────────────────────────────────
            try (OutputStream out = new FileOutputStream(tmpFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            } finally {
                bitmap.recycle();
            }

            // ─── Atomic rename tmp → final ──────────────────────────────────
            File dest = file(ctx);
            if (dest.exists()) dest.delete();
            if (!tmpFile.renameTo(dest)) {
                // renameTo can fail across filesystems; fall back to copy+delete.
                try (InputStream in  = new java.io.FileInputStream(tmpFile);
                     OutputStream os = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                }
                tmpFile.delete();
            }

            // ─── Bump version so engine knows to reload ─────────────────────
            bumpVersion(ctx);
            return true;

        } catch (Exception e) {
            // Clean up tmp on failure to avoid stale partial writes.
            if (tmpFile.exists()) tmpFile.delete();
            return false;
        }
    }

    /**
     * Deletes the stored background image and bumps the version counter so the
     * SphereEngine falls back to the built-in dark gradient on the next frame.
     *
     * @param ctx  Android context
     */
    public static void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) f.delete();
        bumpVersion(ctx);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Computes the smallest power-of-2 {@code inSampleSize} such that the
     * decoded image's maximum dimension is ≤ {@code maxDimension}.
     *
     * Returns 1 (no sub-sampling) if the image already fits.
     *
     * @param width       Source image width in pixels
     * @param height      Source image height in pixels
     * @param maxDimension  Maximum allowed dimension after sampling
     * @return Power-of-2 sample size ≥ 1
     */
    /** Package-private for unit tests. */
    static int computeSampleSize(int width, int height, int maxDimension) {
        int inSampleSize = 1;
        int maxSrc = Math.max(width, height);
        while (maxSrc / inSampleSize > maxDimension) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    /**
     * Increments the background version counter in the default SharedPreferences.
     * The SphereEngine polls this value to decide when to reload the background
     * texture.
     *
     * @param ctx  Android context
     */
    private static void bumpVersion(Context ctx) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putInt(PREF_BACKGROUND_VERSION,
                        androidx.preference.PreferenceManager
                                .getDefaultSharedPreferences(ctx)
                                .getInt(PREF_BACKGROUND_VERSION, 0) + 1)
                .apply();
    }
}
