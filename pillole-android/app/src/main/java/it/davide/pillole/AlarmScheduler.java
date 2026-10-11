package it.davide.pillole;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Programma le sveglie di sistema per ogni pillola.
 *
 * Il "piano" arriva dalla pagina dell'app:
 *   { "date": "2026-10-11",
 *     "today": { "<id>": minutiDallaMezzanotte, ... }   // solo le pillole NON ancora assunte, con l'orario previsto aggiornato
 *     "base":  [ { "id": "...", "name": "...", "minute": minutiProgrammati }, ... ] }
 *
 * Per ogni pillola si programma UNA sveglia, la prossima che deve suonare:
 *  - se il piano è di oggi e la pillola è da assumere con orario futuro -> quell'orario;
 *  - altrimenti (già assunta, orario passato, piano di un giorno precedente) -> orario programmato del giorno dopo
 *    (o di oggi, se il piano è vecchio e l'orario di oggi non è ancora passato).
 * Dopo ogni sveglia il calendario viene ricalcolato, quindi le sveglie continuano ogni giorno
 * anche se l'app non viene aperta.
 */
public final class AlarmScheduler {

    static final String PREFS = "pillole_sveglie";
    static final String KEY_PLAN = "piano";
    static final String KEY_IDS = "ids";
    static final String CHANNEL_ID = "sveglie_pillole_v1";

    static final String ACTION_FIRE = "it.davide.pillole.SUONA";
    static final String ACTION_STOP = "it.davide.pillole.FERMA";
    static final String ACTION_SNOOZE = "it.davide.pillole.POSTICIPA";

    static final int SNOOZE_MINUTES = 10;

    private AlarmScheduler() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void savePlan(Context c, String json) {
        prefs(c).edit().putString(KEY_PLAN, json).apply();
    }

    public static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            return am != null && am.canScheduleExactAlarms();
        }
        return true;
    }

    /** Istante (ms) del giorno corrente + dayOffset, al minuto indicato (anche oltre le 24:00). */
    static long at(int dayOffset, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, dayOffset + minute / 1440);
        int m = ((minute % 1440) + 1440) % 1440;
        cal.set(Calendar.HOUR_OF_DAY, m / 60);
        cal.set(Calendar.MINUTE, m % 60);
        return cal.getTimeInMillis();
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Calcola l'istante della prossima sveglia di una pillola. */
    static long nextTrigger(String planDate, String todayDate, JSONObject todayMap, String id, int baseMinute, long now) {
        if (planDate.equals(todayDate)) {
            if (todayMap != null && todayMap.has(id)) {
                long t = at(0, todayMap.optInt(id, baseMinute));
                if (t > now) return t;
            }
            return at(1, baseMinute);
        }
        long t = at(0, baseMinute);
        return t > now ? t : at(1, baseMinute);
    }

    static PendingIntent firePending(Context c, String id, String name, boolean snooze) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION_FIRE);
        i.setData(Uri.parse("pillola://" + (snooze ? "posticipo/" : "sveglia/") + Uri.encode(id)));
        i.putExtra("id", id);
        i.putExtra("name", name);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setAlarm(Context c, long trigger, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent show = new Intent(c, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(c, 1, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (canExact(c)) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, showPi), pi);
                return;
            }
        } catch (SecurityException ignored) { }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
    }

    /** Cancella e riprogramma tutte le sveglie secondo il piano salvato. */
    public static synchronized void scheduleAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        SharedPreferences p = prefs(c);

        for (String old : p.getString(KEY_IDS, "").split("\n")) {
            if (!old.isEmpty()) am.cancel(firePending(c, old, "", false));
        }

        StringBuilder ids = new StringBuilder();
        try {
            JSONObject plan = new JSONObject(p.getString(KEY_PLAN, "{}"));
            JSONArray base = plan.optJSONArray("base");
            if (base != null) {
                String planDate = plan.optString("date", "");
                String todayDate = today();
                JSONObject todayMap = plan.optJSONObject("today");
                long now = System.currentTimeMillis();
                for (int i = 0; i < base.length(); i++) {
                    JSONObject b = base.getJSONObject(i);
                    String id = b.getString("id");
                    String name = b.optString("name", "Pillola");
                    int minute = b.getInt("minute");
                    long trigger = nextTrigger(planDate, todayDate, todayMap, id, minute, now);
                    setAlarm(c, trigger, firePending(c, id, name, false));
                    ids.append(id).append('\n');
                }
            }
        } catch (Exception ignored) { }
        p.edit().putString(KEY_IDS, ids.toString()).apply();
    }

    /** Annulla i posticipi delle pillole che nel piano risultano già assunte. */
    public static void cancelSnoozesForTaken(Context c, String json) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            JSONObject plan = new JSONObject(json);
            JSONArray base = plan.optJSONArray("base");
            JSONObject todayMap = plan.optJSONObject("today");
            if (base == null) return;
            for (int i = 0; i < base.length(); i++) {
                String id = base.getJSONObject(i).getString("id");
                if (todayMap == null || !todayMap.has(id)) {
                    am.cancel(firePending(c, id, "", true));
                    cancelNotification(c, id);
                }
            }
        } catch (Exception ignored) { }
    }

    public static void snooze(Context c, String id, String name) {
        long t = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L;
        setAlarm(c, t, firePending(c, id, name, true));
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Sveglie pillole", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Suona all'orario di ogni pillola");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(sound, aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    static int notifId(String id) {
        return ("pillola:" + id).hashCode();
    }

    static void cancelNotification(Context c, String id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(notifId(id));
    }

    static PendingIntent actionPending(Context c, String action, String id, String name) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(action);
        i.setData(Uri.parse("pillola://" + action + "/" + Uri.encode(id)));
        i.putExtra("id", id);
        i.putExtra("name", name);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Mostra la sveglia: notifica che suona finché non la fermi, più schermata a tutto schermo. */
    static void ring(Context c, String id, String name) {
        ensureChannel(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent full = new Intent(c, AlarmActivity.class);
        full.setData(Uri.parse("pillola://schermo/" + Uri.encode(id)));
        full.putExtra("id", id);
        full.putExtra("name", name);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(c, 0, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("È ora della pillola")
                .setContentText(name)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(new Notification.Action.Builder(null, "Ferma", actionPending(c, ACTION_STOP, id, name)).build())
                .addAction(new Notification.Action.Builder(null, "Posticipa " + SNOOZE_MINUTES + " min", actionPending(c, ACTION_SNOOZE, id, name)).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT; // il suono si ripete finché non la fermi
        nm.notify(notifId(id), n);
    }
}
