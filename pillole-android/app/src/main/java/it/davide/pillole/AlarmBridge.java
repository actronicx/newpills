package it.davide.pillole;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

/** Collegamento tra la pagina dell'app (window.AndroidAlarm) e le sveglie di Android. */
public class AlarmBridge {

    private final Context c;

    AlarmBridge(Context c) {
        this.c = c.getApplicationContext();
    }

    @JavascriptInterface
    public void setPlan(String json) {
        AlarmScheduler.savePlan(c, json);
        AlarmScheduler.cancelSnoozesForTaken(c, json);
        AlarmScheduler.scheduleAll(c);
    }

    /** Assunzioni fatte dalla schermata della sveglia, da applicare nell'app (e poi cancellate). */
    @JavascriptInterface
    public String takePending() {
        return AlarmScheduler.takePending(c);
    }

    @JavascriptInterface
    public boolean hasPending() {
        return AlarmScheduler.hasPending(c);
    }

    /** "ok", oppure il permesso che manca: "notifiche", "esatti", "schermo". */
    @JavascriptInterface
    public String status() {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && !nm.areNotificationsEnabled()) return "notifiche";
        if (!AlarmScheduler.canExact(c)) return "esatti";
        if (Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent()) return "schermo";
        return "ok";
    }

    @JavascriptInterface
    public void openSettings(String what) {
        Intent i;
        Uri pkg = Uri.parse("package:" + c.getPackageName());
        if ("notifiche".equals(what)) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        } else if ("esatti".equals(what) && Build.VERSION.SDK_INT >= 31) {
            i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg);
        } else if ("schermo".equals(what) && Build.VERSION.SDK_INT >= 34) {
            i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
        } else if ("batteria".equals(what)) {
            i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
            d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(d); } catch (Exception ignored) { }
        }
    }

    /** Sveglia di prova tra 10 secondi, per verificare suono e schermo bloccato. */
    @JavascriptInterface
    public void test() {
        long t = System.currentTimeMillis() + 10_000L;
        AlarmScheduler.setAlarm(c, t, AlarmScheduler.firePending(c, "prova", "Sveglia di prova", true));
    }
}
