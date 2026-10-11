package it.davide.pillole;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        String id = intent.getStringExtra("id");
        String name = intent.getStringExtra("name");
        if (id == null) return;
        if (name == null) name = "Pillola";

        if (AlarmScheduler.ACTION_STOP.equals(action)) {
            AlarmScheduler.cancelNotification(c, id);
            return;
        }
        if (AlarmScheduler.ACTION_SNOOZE.equals(action)) {
            AlarmScheduler.cancelNotification(c, id);
            AlarmScheduler.snooze(c, id, name);
            return;
        }
        AlarmScheduler.ring(c, id, name);
        AlarmScheduler.scheduleAll(c); // prepara già la prossima sveglia
    }
}
