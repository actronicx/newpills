package it.davide.pillole;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Dopo un riavvio, un aggiornamento o un cambio d'ora le sveglie vanno riprogrammate. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        AlarmScheduler.scheduleAll(c);
    }
}
