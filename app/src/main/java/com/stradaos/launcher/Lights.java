package com.stradaos.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Svetla kola naučena iz sistemskih podešavanja, za ekrane koji nisu FYT.
 *
 * Android nema standardni signal za farove: svaki ekran (wwc2, SYU, SRNUBI…) upisuje stanje svetala u neko
 * sistemsko podešavanje sa svojim imenom. StradaOS ga nauči: zapamti sva podešavanja sa ugašenim svetlima,
 * pa sa upaljenim, i ono koje se promenilo je ono za farove. Posle samo prati to jedno podešavanje
 * (čitanje sistemskih podešavanja ne traži dozvolu).
 *
 * Ideja i filteri (LIGHT_WORDS, NOISE_WORDS) preuzeti iz LiteDash-a (CarLights.kt, GPL-3.0,
 * github.com/IrvingGuerra/litedash), prepisano u Javu.
 */
final class Lights {
    static final String P_KEY = "lights_key";   // "tabela/ime", npr. "system/car_light"
    static final String P_ON = "lights_on";     // vrednost tog podešavanja sa upaljenim svetlima

    /** Imena koja obično imaju podešavanja svetala na ekranima za kola. */
    private static final Pattern LIGHT_WORDS = Pattern.compile("illum|headl|lamp|light|dimm|night|backlight|ill_|acc_", Pattern.CASE_INSENSITIVE);
    /** Podešavanja koja se menjaju sama i nikad nisu farovi (osvetljenost, alarmi, vremena, Wi-Fi/BT brojači…). */
    private static final Pattern NOISE_WORDS = Pattern.compile(
        "^screen_brightness|^next_alarm|timestamp|^wifi_|^bluetooth_|^ble_|notification_light_pulse|^autofill|_time$|^time_",
        Pattern.CASE_INSENSITIVE);

    private Lights() { }

    static boolean learned(SharedPreferences p) {
        return p.getString(P_KEY, "").indexOf('/') > 0 && p.contains(P_ON);
    }

    static void forget(SharedPreferences p) {
        p.edit().remove(P_KEY).remove(P_ON).apply();
    }

    static void save(SharedPreferences p, String key, String onValue) {
        p.edit().putString(P_KEY, key).putString(P_ON, onValue).apply();
    }

    /** 1 upaljena, 0 ugašena, -1 ako nisu naučena ili podešavanje ne može da se pročita. */
    static int state(Context c, SharedPreferences p) {
        if (!learned(p)) return -1;
        String v = read(c, p.getString(P_KEY, ""));
        if (v == null) return -1;
        return v.equals(p.getString(P_ON, null)) ? 1 : 0;
    }

    /** Uri naučenog podešavanja (za ContentObserver) ili null. */
    static Uri uri(SharedPreferences p) {
        String k = p.getString(P_KEY, "");
        int i = k.indexOf('/');
        if (i <= 0) return null;
        String t = k.substring(0, i), n = k.substring(i + 1);
        try {
            if ("global".equals(t) && android.os.Build.VERSION.SDK_INT >= 17) return Settings.Global.getUriFor(n);
            if ("secure".equals(t)) return Settings.Secure.getUriFor(n);
            return Settings.System.getUriFor(n);
        } catch (Throwable e) { return null; }
    }

    private static String read(Context c, String key) {
        int i = key.indexOf('/');
        if (i <= 0) return null;
        String t = key.substring(0, i), n = key.substring(i + 1);
        ContentResolver r = c.getContentResolver();
        try {
            if ("global".equals(t) && android.os.Build.VERSION.SDK_INT >= 17) return Settings.Global.getString(r, n);
            if ("secure".equals(t)) return Settings.Secure.getString(r, n);
            return Settings.System.getString(r, n);
        } catch (Throwable e) { return null; }
    }

    /** Sva podešavanja ekrana („tabela/ime" → vrednost). Stotine redova preko IPC-a: ne na glavnoj niti. */
    static Map<String, String> snapshot(Context c) {
        Map<String, String> all = new LinkedHashMap<String, String>();
        List<Object[]> tables = new ArrayList<Object[]>();
        tables.add(new Object[]{"system", Settings.System.CONTENT_URI});
        if (android.os.Build.VERSION.SDK_INT >= 17) tables.add(new Object[]{"global", Settings.Global.CONTENT_URI});
        tables.add(new Object[]{"secure", Settings.Secure.CONTENT_URI});
        for (Object[] t : tables) {
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query((Uri) t[1], new String[]{"name", "value"}, null, null, null);
                while (cur != null && cur.moveToNext()) {
                    String n = cur.getString(0);
                    if (n != null) all.put(t[0] + "/" + n, cur.getString(1) == null ? "" : cur.getString(1));
                }
            } catch (Throwable ignored) {   // tabela koju Android ne da da se čita se preskače
            } finally {
                if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
            }
        }
        return all;
    }

    private static String name(String key) { return key.substring(key.indexOf('/') + 1); }

    static boolean soundsLikeLights(String key) {
        String n = name(key);
        return LIGHT_WORDS.matcher(n).find() && !NOISE_WORDS.matcher(n).find();
    }

    /**
     * Šta se promenilo od [off] (ugašena) do [on] (upaljena), bez šuma; prvo ono što zvuči kao svetla.
     * Svaki element je {ključ, vrednost sa upaljenim svetlima}.
     */
    static List<String[]> changed(Map<String, String> off, Map<String, String> on) {
        List<String[]> like = new ArrayList<String[]>(), other = new ArrayList<String[]>();
        for (Map.Entry<String, String> e : on.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            String before = off.get(k);
            if (v.equals(before) || NOISE_WORDS.matcher(name(k)).find()) continue;
            if (before == null && !soundsLikeLights(k)) continue;   // novo podešavanje: samo ako zvuči kao svetla
            (soundsLikeLights(k) ? like : other).add(new String[]{k, v});
        }
        like.addAll(other);
        return like;
    }
}
