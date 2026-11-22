package com.stradaos.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * „3D" auto na kartici vozila: niz slika renderovanih unapred iz 3D modela (assets/car3d/NN.webp; kadar 0 je auto
 * spreda, a svaki sledeći kamera ide za isti ugao oko auta), koje se menjaju dok se prevlači prstom. Nema OpenGL-a: radi i na Mali-400 sa 1 GB RAM-a,
 * jer se u memoriji drži samo nekoliko kadrova. Ideja iz LiteDash-a (CarImage.kt, GPL-3.0).
 *
 * Model: „2008 Mercedes-Benz C63 AMG", autor 007 (sketchfab.com/tangwang920), CC BY 4.0;
 * kadrove pravi .github/scripts/car3d_render.py (Blender).
 */
final class CarSpin extends View {
    static final String DIR = "car3d";
    static final String P_ON = "car3d", P_FRAME = "car3d_frame";
    /** Koliko širina kartice treba prevući za ceo krug. */
    private static final float WIDTHS_PER_TURN = 0.9f;
    private static final int CACHE = 8;

    private static String[] names;

    private final SharedPreferences prefs;
    private final String[] frames;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Rect src = new Rect();
    private final int slop;
    private final Map<Integer, Bitmap> cache = new LinkedHashMap<Integer, Bitmap>(CACHE + 2, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> e) { return size() > CACHE; }
    };
    private int frame, downFrame, sample = 1;
    private float downX;
    private boolean dragging, longDone;
    private final Runnable longPress = new Runnable() { public void run() {
        if (!dragging) { longDone = true; performLongClick(); }
    }};

    /** Kadrovi u APK-u, po imenu; prazno ako ih nema. */
    static synchronized String[] frames(Context c) {
        if (names == null) {
            try {
                String[] l = c.getAssets().list(DIR);
                names = l == null ? new String[0] : l;
                Arrays.sort(names);
            } catch (Throwable e) { names = new String[0]; }
        }
        return names;
    }

    static boolean available(Context c) { return frames(c).length > 0; }

    /** Uključen na kartici vozila (umesto fotografije). */
    static boolean on(Context c, SharedPreferences p) { return p.getBoolean(P_ON, false) && available(c); }

    CarSpin(Context c, SharedPreferences p) {
        super(c);
        prefs = p;
        frames = frames(c);
        int n = Math.max(1, frames.length);
        // početni kadar: tri četvrtine spreda, prednja strana levo (kadar 0 je auto spreda)
        frame = ((p.getInt(P_FRAME, n / 8) % n) + n) % n;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
        setLongClickable(true);
    }

    private Bitmap bitmap(int i) {
        Bitmap b = cache.get(i);
        if (b != null) return b;
        InputStream in = null;
        try {
            in = getContext().getAssets().open(DIR + "/" + frames[i]);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            b = BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable e) {
            b = null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
        if (b != null) cache.put(i, b);
        return b;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // smanji pri dekodiranju ako je kartica mnogo manja od kadra (manje memorije)
        int fw = 0;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContext().getAssets().open(DIR + "/" + frames[0]);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            fw = o.outWidth;
        } catch (Throwable ignored) { }
        int s = 1;
        while (fw > 0 && w > 0 && fw / (s * 2) >= w) s *= 2;
        if (s != sample) { sample = s; cache.clear(); }
    }

    @Override protected void onDraw(Canvas c) {
        if (frames.length == 0) return;
        Bitmap b = bitmap(frame);
        if (b == null) return;
        float vw = getWidth() - getPaddingLeft() - getPaddingRight(), vh = getHeight() - getPaddingTop() - getPaddingBottom();
        float k = Math.min(vw / b.getWidth(), vh / b.getHeight());
        float dw = b.getWidth() * k, dh = b.getHeight() * k;
        float x = getPaddingLeft() + (vw - dw) / 2, y = getPaddingTop() + (vh - dh) / 2;
        src.set(0, 0, b.getWidth(), b.getHeight());
        dst.set(x, y, x + dw, y + dh);
        c.drawBitmap(b, src, dst, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (frames.length == 0) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downFrame = frame;
                dragging = false;
                longDone = false;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!dragging && Math.abs(dx) > slop) {
                    dragging = true;
                    removeCallbacks(longPress);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    int n = frames.length;
                    // prevlačenje nadesno okreće auto nadesno (kao gramofon)
                    int step = Math.round(-dx / (Math.max(1, getWidth()) * WIDTHS_PER_TURN) * n);
                    int f = (((downFrame + step) % n) + n) % n;
                    if (f != frame) { frame = f; invalidate(); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (dragging) prefs.edit().putInt(P_FRAME, frame).apply();
                else if (!longDone) performClick();
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (dragging) prefs.edit().putInt(P_FRAME, frame).apply();
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPress);
        cache.clear();   // bitmape oslobađa GC; recycle() ne, jer ih možda još crta drugi prikaz
    }
}
