package app.unbloatmytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Run by UnbloatMyTV over ADB, never installed:
 *   CLASSPATH=/data/local/tmp/umtv-helper.dex app_process / app.unbloatmytv.AppIcons HEIGHT PKG...
 * Prints one line per package: "package TAB label TAB base64 PNG" (empty fields when not found).
 * Android itself loads the name in the TV's language and draws the picture, as the home screen would:
 * the app's banner (the 16:9 tile on the TV's home screen) when it has one, else its icon, centred.
 * The PNG is HEIGHT pixels high and 16:9 wide.
 */
public final class AppIcons {
    public static void main(String[] args) throws Exception {
        int height = Integer.parseInt(args[0]);
        PackageManager pm = systemContext().getPackageManager();
        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        for (int i = 1; i < args.length; i++) {
            String pkg = args[i], label = "", png = "";
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString().replaceAll("[\\t\\r\\n]+", " ").trim();
                png = picture(pm, ai, height);
            } catch (Throwable ignored) {
                // Not installed for this user, or a picture Android can't draw here: keep what we have.
            }
            out.print(pkg + "\t" + label + "\t" + png + "\n");
        }
        out.flush();
    }

    private static String picture(PackageManager pm, ApplicationInfo ai, int height) throws Exception {
        int width = height * 16 / 9;
        Intent tv = pm.getLeanbackLaunchIntentForPackage(ai.packageName);
        Drawable banner = null;
        try {
            banner = pm.getApplicationBanner(ai);
            if (banner == null && tv != null) banner = pm.getActivityBanner(tv);
        } catch (Throwable ignored) {
        }
        if (banner != null) return render(banner, width, height, width, height);
        // Many TV apps have only a banner: asking for a missing icon makes Android fail on its default one.
        Drawable icon = ai.icon != 0 ? pm.getApplicationIcon(ai) : tv != null ? pm.getActivityIcon(tv) : null;
        return icon == null ? "" : render(icon, width, height, height, height);
    }

    /** Draws d to fit a boxW x boxH box (keeping its shape) in the middle of a width x height picture. */
    private static String render(Drawable d, int width, int height, int boxW, int boxH) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w > 0 && h > 0) {
            float scale = Math.min((float) boxW / w, (float) boxH / h);
            boxW = Math.round(w * scale);
            boxH = Math.round(h * scale);
        }
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int left = (width - boxW) / 2, top = (height - boxH) / 2;
        d.setBounds(left, top, left + boxW, top + boxH);
        d.draw(new Canvas(b));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, png);
        return Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
    }

    /** The system context, the way screen-mirroring tools get one outside an app. */
    private static Context systemContext() throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> c = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        Field current = c.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        Method get = c.getDeclaredMethod("getSystemContext");
        return (Context) get.invoke(thread);
    }
}
