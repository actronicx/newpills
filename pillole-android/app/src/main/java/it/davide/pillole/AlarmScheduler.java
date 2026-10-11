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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    static final String ACTION_TAKE = "it.davide.pillole.ASSUMI";
    static final String KEY_PENDING = "assunte_da_sveglia";

    static final int SNOOZE_MINUTES = 5;

    private AlarmScheduler() {}

    // ===== Assunzione direttamente dalla sveglia (senza aprire l'app) =====

    /**
     * Stesso calcolo della pagina (computeTimes): per ogni categoria, in ordine di orario programmato,
     * l'ultima pillola assunta fa da ancora; le successive vanno ad ancora + k*intervallo,
     * oppure (intervallo 0) slittano dello stesso ritardo. Restituisce solo le pillole non assunte.
     */
    static Map<String, Integer> computeTimes(List<String> ids, Map<String, Integer> base,
                                             Map<String, String> cat, Map<String, Double> ivHours,
                                             Map<String, Integer> taken) {
        Map<String, List<String>> byCat = new LinkedHashMap<>();
        for (String id : ids) {
            String k = cat.containsKey(id) ? cat.get(id) : "";
            if (!byCat.containsKey(k)) byCat.put(k, new ArrayList<>());
            byCat.get(k).add(id);
        }
        Map<String, Integer> out = new HashMap<>();
        for (List<String> list : byCat.values()) {
            list.sort((a, b) -> {
                int d = Integer.compare(base.get(a), base.get(b));
                if (d != 0) return d;
                try { return Long.compare(Long.parseLong(a), Long.parseLong(b)); }
                catch (NumberFormatException e) { return a.compareTo(b); }
            });
            Integer anchor = null;
            int delay = 0, k = 0;
            for (String id : list) {
                int s = base.get(id);
                Double ivh = ivHours.get(id);
                int iv = (int) Math.round((ivh == null ? 0 : ivh) * 60);
                if (taken.containsKey(id)) {
                    anchor = taken.get(id); delay = anchor - s; k = 0;
                    continue;
                }
                if (anchor == null) { out.put(id, s); continue; }
                k++;
                out.put(id, iv > 0 ? anchor + k * iv : s + delay);
            }
        }
        return out;
    }

    static String hhmm(int minute) {
        int m = ((minute % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    /** Segna la pillola come assunta adesso, ricalcola le sveglie e lascia il dato all'app. */
    public static synchronized void takeNow(Context c, String id) {
        SharedPreferences p = prefs(c);
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String todayDate = today();
        try {
            JSONObject plan = new JSONObject(p.getString(KEY_PLAN, "{}"));
            JSONArray baseArr = plan.optJSONArray("base");
            if (baseArr == null) return;

            List<String> ids = new ArrayList<>();
            Map<String, Integer> base = new HashMap<>();
            Map<String, String> cat = new HashMap<>();
            Map<String, Double> iv = new HashMap<>();
            for (int i = 0; i < baseArr.length(); i++) {
                JSONObject b = baseArr.getJSONObject(i);
                String bid = b.getString("id");
                ids.add(bid);
                base.put(bid, b.getInt("minute"));
                cat.put(bid, b.optString("cat", ""));
                iv.put(bid, b.optDouble("iv", 0));
            }
            if (!base.containsKey(id)) return;

            Map<String, Integer> taken = new HashMap<>();
            JSONObject todayMap = plan.optJSONObject("today");
            boolean sameDay = todayDate.equals(plan.optString("date", ""));
            if (sameDay) {
                JSONObject t = plan.optJSONObject("taken");
                if (t != null) {
                    Iterator<String> it = t.keys();
                    while (it.hasNext()) { String k = it.next(); taken.put(k, t.getInt(k)); }
                }
            }
            if (taken.containsKey(id)) return; // già assunta

            int due = (sameDay && todayMap != null && todayMap.has(id)) ? todayMap.getInt(id) : base.get(id);
            taken.put(id, nowMin);

            Map<String, Integer> times = computeTimes(ids, base, cat, iv, taken);
            JSONObject newToday = new JSONObject();
            for (Map.Entry<String, Integer> e : times.entrySet()) newToday.put(e.getKey(), e.getValue());
            JSONObject newTaken = new JSONObject();
            for (Map.Entry<String, Integer> e : taken.entrySet()) newTaken.put(e.getKey(), e.getValue());
            plan.put("date", todayDate);
            plan.put("today", newToday);
            plan.put("taken", newTaken);

            JSONArray pending = new JSONArray(p.getString(KEY_PENDING, "[]"));
            JSONObject rec = new JSONObject();
            rec.put("id", id);
            rec.put("date", todayDate);
            rec.put("time", hhmm(nowMin));
            rec.put("due", hhmm(due));
            pending.put(rec);

            p.edit().putString(KEY_PLAN, plan.toString()).putString(KEY_PENDING, pending.toString()).commit();
        } catch (Exception ignored) { }

        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(firePending(c, id, "", true)); // annulla un eventuale posticipo
        cancelNotification(c, id);
        scheduleAll(c);
    }

    /** Restituisce all'app le assunzioni fatte dalla sveglia e le cancella. */
    public static synchronized String takePending(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString(KEY_PENDING, "[]");
        p.edit().remove(KEY_PENDING).commit();
        return s;
    }

    public static boolean hasPending(Context c) {
        String s = prefs(c).getString(KEY_PENDING, "[]");
        return s.length() > 2;
    }

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
                .addAction(new Notification.Action.Builder(null, "Assumi", actionPending(c, ACTION_TAKE, id, name)).build())
                .addAction(new Notification.Action.Builder(null, "Posticipa " + SNOOZE_MINUTES + " min", actionPending(c, ACTION_SNOOZE, id, name)).build())
                .addAction(new Notification.Action.Builder(null, "Ferma", actionPending(c, ACTION_STOP, id, name)).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT; // il suono si ripete finché non la fermi
        nm.notify(notifId(id), n);
    }
}
