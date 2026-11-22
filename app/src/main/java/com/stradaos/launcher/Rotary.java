package com.stradaos.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.AdapterView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Upravljanje točkićem i tasterima (kontroler u kolima, strelice na volanu, tastatura): svako dugme na početnom
 * ekranu može da se izabere posebno i vidi se koje je izabrano.
 *
 * Ranije su kartice koje se dodiruju cele (plejer otvara muzičku aplikaciju) dobijale fokus i pokrivale sopstvenu
 * dugmad: strelicama se birala samo cela kartica, nikad ⏮ ⏯ ⏭. Isto i mapa, koja zauzima celu svoju karticu i
 * pokrivala je Android Auto i CarPlay. Zato se posle svake promene rasporeda:
 *  - element koji se dodiruje, a sadrži ili pokriva drugi takav element, više ne dobija fokus (njegova dugmad ga
 *    dobijaju); ako ima „zamenika" ({@link #proxy}), fokus dobija on i pritisak radi isto što i kartica;
 *  - ono što je iza Podešavanja ili fioke aplikacija ne dobija fokus.
 *
 * Točkić: uči se koje tastere šalje pri okretanju na obe strane i pri pritisku (Podešavanja → Sistem). Okretanje
 * ide redom čitanja (red po red, sleva nadesno, a unutar kartice njenim redom), nezavisno od geometrije kao kod
 * Android strelica. Strelice koje nisu naučene rade kao i ranije.
 *
 * Okvir u boji akcenta crta se iznad svega (ViewOverlay, Android 4.3+) oko izabranog dugmeta.
 */
final class Rotary {
    static final String P_NEXT = "rot_next", P_PREV = "rot_prev", P_OK = "rot_ok";
    /** Naučeni tasteri (KeyEvent kod), -1 = nije naučen. */
    static int next = -1, prev = -1, ok = -1;

    /** Koji delovi ekrana mogu sada da se obiđu (početni ekran, ili Podešavanja / fioka ako su otvoreni). */
    interface Scope {
        View[] roots();
        /** true na početnom ekranu: točkić ide po karticama i redovima (vidi {@link #order}). */
        boolean home();
    }

    private static final WeakHashMap<View, View> PROXY = new WeakHashMap<View, View>();

    static void load(SharedPreferences p) {
        next = p.getInt(P_NEXT, -1);
        prev = p.getInt(P_PREV, -1);
        ok = p.getInt(P_OK, -1);
    }

    static boolean learned() { return next > 0 || prev > 0 || ok > 0; }

    /**
     * `stand` dobija fokus umesto `target` (kartice sa dugmadima unutra) i pritisak na njega radi isto što i
     * kartica: kratko = klik, držanje = dugi klik. Ne postaje dodirljiv, pa se prstom ništa ne menja (prevlačenje
     * preko omota u plejeru i dalje radi).
     */
    static void proxy(final View stand, final View target) {
        if (stand == null || target == null) return;
        PROXY.put(stand, target);
        stand.setOnKeyListener(new View.OnKeyListener() {
            long down;
            public boolean onKey(View v, int code, KeyEvent e) {
                if (!isConfirm(code)) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) { if (e.getRepeatCount() == 0) down = e.getEventTime(); return true; }
                if (e.getAction() == KeyEvent.ACTION_UP) { press(v, e.getEventTime() - down >= ViewConfiguration.getLongPressTimeout()); return true; }
                return false;
            }
        });
    }

    static boolean isConfirm(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Tasteri koji pomeraju ili biraju: dok je screensaver uključen, prvi ga samo gasi. */
    static boolean isNavKey(int code) {
        return code == next || code == prev || code == ok || isConfirm(code) || code == KeyEvent.KEYCODE_TAB
            || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    static String keyName(int code) {
        String n = KeyEvent.keyCodeToString(code);
        if (n.startsWith("KEYCODE_")) n = n.substring(8);
        return n + " (" + code + ")";
    }

    // ───────────────────────── instanca: početni ekran ─────────────────────────

    private final Activity act;
    private final ViewGroup root;
    private final Scope scope;
    private final GradientDrawable ring = new GradientDrawable();
    private final Rect last = new Rect(), tmp = new Rect();
    private final int[] loc = new int[2];
    private float s = 1f;
    private boolean overlayOn;

    Rotary(Activity a, ViewGroup root, Scope scope) {
        this.act = a;
        this.root = root;
        this.scope = scope;
        ring.setColor(0);
        ViewTreeObserver vto = root.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() { try { apply(); } catch (Throwable ignored) { } }
        });
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() { try { moveRing(); } catch (Throwable ignored) { } return true; }
        });
    }

    void style(int color, float scale) {
        s = scale > 0 ? scale : 1f;
        ring.setStroke(Math.max(2, (int) (3 * s)), color);
        last.setEmpty();
    }

    /** Ko može da dobije fokus: računa se posle svake promene rasporeda (jeftino je, nekoliko desetina view-ova). */
    void apply() {
        View[] roots = scope.roots();
        List<View> tap = new ArrayList<View>();
        collect(root, tap);
        int n = tap.size();
        Rect[] r = new Rect[n];
        boolean[] in = new boolean[n];
        for (int i = 0; i < n; i++) {
            View v = tap.get(i);
            in[i] = v.isShown() && under(v, roots);
            r[i] = in[i] ? screenRect(v, new Rect()) : null;
        }
        for (int i = 0; i < n; i++) {
            View v = tap.get(i);
            boolean want = in[i] && !r[i].isEmpty();
            if (want && !(v instanceof AdapterView)) {
                for (int j = 0; j < n && want; j++) {
                    if (j == i || !in[j] || r[j].isEmpty()) continue;
                    View o = tap.get(j);
                    if (isAncestor(v, o)) want = false;                       // ima dugmad unutra
                    else if (!isAncestor(o, v) && r[i].contains(r[j])) {
                        // pokriva drugo dugme (mapa ispod Android Auto): prepušta mu fokus, osim ako su isti i ovaj je gore
                        want = !r[i].equals(r[j]) ? false : i > j;
                    }
                }
            }
            if (v.isFocusable() != want) v.setFocusable(want);
            noHighlight(v);
        }
        for (java.util.Map.Entry<View, View> e : new ArrayList<java.util.Map.Entry<View, View>>(PROXY.entrySet())) {
            View stand = e.getKey(), target = e.getValue();
            boolean want = stand.getWindowToken() != null && target.isShown() && stand.isShown() && under(stand, roots);
            if (stand.isFocusable() != want) stand.setFocusable(want);
            noHighlight(stand);
        }
        // ako je fokus ostao na nečemu što više ne može da se izabere (iza Podešavanja), pušta se
        View f = act.getCurrentFocus();
        if (f != null && !f.isFocusable() && f != root) { try { f.clearFocus(); } catch (Throwable ignored) { } }
    }

    /** Naučeni taster točkića. true = iskorišćen. */
    boolean key(KeyEvent e) { return handle(e, scope.roots(), act.getCurrentFocus(), scope.home()); }

    // ───────────────────────── obilazak ─────────────────────────

    /** Za prozore (Dlg): točkić obilazi i njihovu dugmad i liste. */
    static boolean keyIn(KeyEvent e, View decor) {
        if (decor == null) return false;
        return handle(e, new View[]{decor}, decor.findFocus(), false);
    }

    private static long okDown;

    private static boolean handle(KeyEvent e, View[] roots, View focus, boolean rows) {
        int code = e.getKeyCode();
        if (code <= 0) return false;
        boolean isNext = code == next, isPrev = code == prev, isOk = code == ok && !isConfirm(code);
        if (!isNext && !isPrev && !isOk) return false;
        if (isOk) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { if (e.getRepeatCount() == 0) okDown = e.getEventTime(); return true; }
            if (e.getAction() == KeyEvent.ACTION_UP && focus != null)
                press(focus, e.getEventTime() - okDown >= ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        move(roots, focus, isNext ? 1 : -1, rows);
        return true;
    }

    private static void move(View[] roots, View focus, int dir, boolean rows) {
        // unutar liste ili mreže (fioka aplikacija, liste u prozorima) ide se element po element
        AbsListView lv = listOf(focus);
        if (lv != null && lv.getCount() > 0) {
            int pos = lv.getSelectedItemPosition() + dir;
            if (lv.getSelectedItemPosition() == AdapterView.INVALID_POSITION) pos = dir > 0 ? 0 : lv.getCount() - 1;
            if (pos >= 0 && pos < lv.getCount()) { lv.setSelection(pos); return; }
        }
        List<View> order = order(roots, rows);
        if (order.isEmpty()) return;
        int i = -1;
        for (View f = focus; f != null && i < 0; f = f.getParent() instanceof View ? (View) f.getParent() : null) i = order.indexOf(f);
        int k = i < 0 ? (dir > 0 ? 0 : order.size() - 1) : (i + dir + order.size()) % order.size();
        View t = order.get(k);
        t.requestFocusFromTouch();
        if (t instanceof AbsListView) {
            AbsListView l = (AbsListView) t;
            if (l.getCount() > 0) l.setSelection(dir > 0 ? 0 : l.getCount() - 1);
        }
    }

    private static AbsListView listOf(View f) {
        for (View v = f; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null)
            if (v instanceof AbsListView) return (AbsListView) v;
        return null;
    }

    /** Pritisak na izabrano: zamenik radi ono što i njegova kartica; u listi, izabrani element. */
    private static void press(View f, boolean longPress) {
        View t = PROXY.get(f);
        if (t != null) { if (longPress) t.performLongClick(); else t.performClick(); return; }
        AbsListView lv = listOf(f);
        if (lv != null) {
            View sel = lv.getSelectedView();
            int pos = lv.getSelectedItemPosition();
            if (sel != null && pos != AdapterView.INVALID_POSITION) {
                if (!longPress) lv.performItemClick(sel, pos, lv.getItemIdAtPosition(pos));
                return;
            }
        }
        if (longPress) f.performLongClick(); else f.performClick();
    }

    /**
     * Redosled točkića. Na početnom ekranu (`rows`): svaka kartica je grupa; grupe idu po redovima (odozgo nadole), a
     * u redu sleva nadesno; unutar grupe redom kojim su napravljene (omot, ⏮, ⏯, ⏭). U Podešavanjima i prozorima:
     * redosled stabla, koji je već redosled čitanja.
     */
    private static List<View> order(View[] roots, boolean rows) {
        final List<View> out = new ArrayList<View>();
        for (View r : roots) {
            if (r == null || !r.isShown()) continue;
            if (!rows || !(r instanceof ViewGroup)) { focusables(r, out); continue; }
            ViewGroup g = (ViewGroup) r;
            List<Grp> groups = new ArrayList<Grp>();
            for (int i = 0; i < g.getChildCount(); i++) {
                Grp gr = new Grp();
                focusables(g.getChildAt(i), gr.items);
                if (gr.items.isEmpty()) continue;
                gr.r = screenRect(g.getChildAt(i), new Rect());
                gr.idx = i;
                groups.add(gr);
            }
            Collections.sort(groups, new Comparator<Grp>() {
                public int compare(Grp a, Grp b) { return a.r.top != b.r.top ? (a.r.top < b.r.top ? -1 : 1) : a.idx - b.idx; }
            });
            int i = 0;
            while (i < groups.size()) {
                Grp first = groups.get(i);
                int end = i + 1;
                while (end < groups.size() && groups.get(end).r.top < first.r.top + first.r.height() / 2) end++;
                List<Grp> row = new ArrayList<Grp>(groups.subList(i, end));
                Collections.sort(row, new Comparator<Grp>() {
                    public int compare(Grp a, Grp b) { return a.r.left != b.r.left ? (a.r.left < b.r.left ? -1 : 1) : a.idx - b.idx; }
                });
                for (Grp gr : row) out.addAll(gr.items);
                i = end;
            }
        }
        return out;
    }

    private static final class Grp { final List<View> items = new ArrayList<View>(); Rect r; int idx; }

    private static void focusables(View v, List<View> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v.isFocusable() && v.isEnabled() && v.getWidth() > 0 && v.getHeight() > 0 && v.isShown()) {
            out.add(v);
            if (v instanceof AdapterView) return;
        }
        if (v instanceof ViewGroup && !(v instanceof AdapterView)) {
            ViewGroup g = (ViewGroup) v;
            if (g.getDescendantFocusability() == ViewGroup.FOCUS_BLOCK_DESCENDANTS) return;
            for (int i = 0; i < g.getChildCount(); i++) focusables(g.getChildAt(i), out);
        }
    }

    // ───────────────────────── okvir ─────────────────────────

    private void moveRing() {
        if (Build.VERSION.SDK_INT < 18) return;
        View decor = act.getWindow().getDecorView();
        if (!overlayOn) { ((ViewGroup) decor).getOverlay().add(ring); overlayOn = true; }
        View f = act.getCurrentFocus();
        View show = f;
        AbsListView lv = listOf(f);
        if (lv != null && lv.getSelectedView() != null) show = lv.getSelectedView();
        boolean on = show != null && !show.isInTouchMode() && show != root && show != decor && show.isShown()
            && show.getWidth() > 0 && show.getWidth() < decor.getWidth() * 0.9f;
        if (on) {
            show.getLocationInWindow(loc);
            int pad = (int) (4 * s);
            tmp.set(loc[0] - pad, loc[1] - pad, loc[0] + show.getWidth() + pad, loc[1] + show.getHeight() + pad);
        } else tmp.setEmpty();
        if (tmp.equals(last)) return;
        last.set(tmp);
        int w = tmp.width(), hh = tmp.height();
        // okrugla dugmad: okrugao okvir; ostalo: blago zaobljeni uglovi
        ring.setCornerRadius(Math.abs(w - hh) < Math.min(w, hh) * 0.15f ? Math.min(w, hh) / 2f : 16 * s);
        ring.setBounds(tmp);
        ring.invalidateSelf();   // sloj okvira ima sopstveni crtež: bez ovoga je ostajao stari okvir
        decor.invalidate();
    }

    // ───────────────────────── pomoćne ─────────────────────────

    /** View-ovi koji se dodiruju, redom stabla (bez ulaska u liste: one same vode svoje elemente). */
    private static void collect(View v, List<View> out) {
        if (v.getVisibility() == View.GONE) return;
        if (v instanceof AdapterView) { out.add(v); return; }
        if (v.isClickable() || v.isLongClickable()) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static boolean under(View v, View[] roots) {
        for (View r : roots) if (r != null && (r == v || isAncestor(r, v))) return true;
        return false;
    }

    private static boolean isAncestor(View a, View v) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) if (p == a) return true;
        return false;
    }

    private static Rect screenRect(View v, Rect out) {
        int[] l = new int[2];
        v.getLocationInWindow(l);
        out.set(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
        return out;
    }

    private static void noHighlight(View v) {
        // sivo isticanje iz Androida 8+ dodaje se na naš okvir i jedva se vidi: gasi se
        if (Build.VERSION.SDK_INT >= 26) { try { v.setDefaultFocusHighlightEnabled(false); } catch (Throwable ignored) { } }
    }
}
