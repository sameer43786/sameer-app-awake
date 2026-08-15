package com.sameerali.appawake;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Permission and package-label helpers shared by the activity and service. */
public final class PermissionUtils {

    private PermissionUtils() {
        // Utility class. Instances are unnecessary.
    }

    /**
     * Checks the AppOps state behind Android's special Usage Access permission.
     */
    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Opens this app's Usage Access page, with a generic settings fallback. */
    public static void openUsageAccessSettings(Activity activity) {
        Intent direct = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        direct.setData(Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(direct);
        } catch (ActivityNotFoundException ignored) {
            activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    public static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Returns a human-readable label without exposing or reading any app content. */
    public static String appLabel(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return "None";
        }
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
