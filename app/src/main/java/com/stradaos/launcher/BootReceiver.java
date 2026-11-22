package com.stradaos.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * «Abrir StradaOS al encender»: para pantallas que siempre arrancan en su menú de fábrica (funciona en Android 4 a 9).
 *
 * Pored običnog paljenja (BOOT_COMPLETED) sluša i buđenje MediaTek jedinica iz sna (IPO) i signale paljenja kontakta
 * (ACC) kod nekih proizvođača, po listi iz LiteDash-a (BootLaunch.kt, GPL-3.0, github.com/IrvingGuerra/litedash).
 * Čeka 3 s da sistem otvori svoj launcher pa izlazi ispred njega; više signala zaredom otvara samo jednom.
 * Pamti poslednji signal (Podešavanja → Sistem → „Poslednje paljenje") da se vidi šta ekran šalje.
 * Lista mora da se poklapa sa intent-filterom u AndroidManifest.xml.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String P_ACTION = "boot_last_action", P_AT = "boot_last_at", P_OK = "boot_last_ok";
    private static final String P_UP = "boot_last_up";
    private static final long DELAY_MS = 3000, DEBOUNCE_MS = 30000;

    @Override public void onReceive(Context c, Intent i) {
        final String action = i == null ? null : i.getAction();
        if (action == null) return;
        final Context app = c.getApplicationContext();
        final SharedPreferences p = app.getSharedPreferences("launcher", 0);
        long up = SystemClock.elapsedRealtime();
        long last = p.getLong(P_UP, -1);
        // vreme od paljenja, ne sat: mnoge jedinice nemaju bateriju za sat i pale se u 1970.
        if (last >= 0 && last <= up && up - last < DEBOUNCE_MS) return;
        p.edit().putLong(P_UP, up).putString(P_ACTION, action).putLong(P_AT, System.currentTimeMillis()).remove(P_OK).apply();
        if (!p.getBoolean("boot_open", false)) return;
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { public void run() {
            boolean ok = false;
            try {
                app.startActivity(new Intent(app, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                ok = true;
            } catch (Throwable ignored) { }
            p.edit().putBoolean(P_OK, ok).apply();
            try { pending.finish(); } catch (Throwable ignored) { }
        }}, DELAY_MS);
    }
}
