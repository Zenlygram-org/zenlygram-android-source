/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.appearance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

/**
 * "Lightning" holiday effect: Zenlygram glyphs drifting down over the chat wallpaper (iOS SGNY WallpaperNYNode with
 * the SwiftgramContextMenu glyph painted white).
 *
 * <p>Like iOS: white at 58 % opacity, sizes from tiny to about 26dp (iOS scale 0.15 +/- 0.25 of the 24pt glyph at
 * 3x), a slow fall with a little drift to the right and a slow spin either way, a third of the particles when the
 * system turns animations off (iOS: Low Power / Reduce Motion; Android's battery saver already turns the effect off
 * through Lite Mode). One white bitmap of the glyph is drawn for every particle, so a frame allocates nothing.
 * Drawn by SizeNotifierFrameLayout on the chat background, the same place as Telegram's snow.
 */
public class ZgLightningEffect {

    private static final int MAX_PARTICLES = 45;
    private static final float MAX_ALPHA = 0.58f;
    private static final long FADE_IN_MS = 600;
    private static final long FADE_OUT_MS = 1500;
    private static final long ANIMATOR_CHECK_MS = 5000;

    private static final class Particle {
        float x;
        float y;
        /** dp per second */
        float vx;
        float vy;
        float angle;
        /** degrees per second */
        float spin;
        float size;
        long age;
        long lifeTime;
    }

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Particle> freeParticles = new ArrayList<>();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private Bitmap glyph;
    private boolean glyphFailed;
    private long lastFrameTime;
    private long animatorCheckedAt;
    private boolean animationsOff;

    public void onDraw(View parent, Canvas canvas) {
        if (parent == null || canvas == null || !LiteMode.isEnabled(LiteMode.FLAG_CHAT_BACKGROUND)) {
            return;
        }
        final Bitmap bitmap = getGlyph(parent.getContext());
        if (bitmap == null) {
            return;
        }
        final int width = parent.getMeasuredWidth();
        final int height = parent.getMeasuredHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final long dt = lastFrameTime == 0 ? 0 : Math.max(0, Math.min(17, now - lastFrameTime));
        lastFrameTime = now;
        if (now - animatorCheckedAt >= ANIMATOR_CHECK_MS || animatorCheckedAt == 0) {
            animatorCheckedAt = now;
            animationsOff = areAnimationsOff(parent.getContext());
        }

        update(dt, width, height);

        for (int i = 0, n = particles.size(); i < n; i++) {
            final Particle p = particles.get(i);
            final float alpha = alphaOf(p);
            if (alpha <= 0f) {
                continue;
            }
            bitmapPaint.setAlpha((int) (255 * alpha));
            final float half = p.size / 2f;
            rect.set(-half, -half, half, half);
            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.rotate(p.angle);
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint);
            canvas.restore();
        }

        spawn(width, height);
        parent.invalidate();
    }

    private void update(long dt, int width, int height) {
        final float seconds = dt / 1000f;
        final float density = AndroidUtilities.density;
        for (int i = 0; i < particles.size(); i++) {
            final Particle p = particles.get(i);
            p.age += dt;
            p.x += p.vx * density * seconds;
            p.y += p.vy * density * seconds;
            p.angle += p.spin * seconds;
            if (p.age >= p.lifeTime || p.y - p.size > height || p.x - p.size > width) {
                particles.remove(i);
                i--;
                if (freeParticles.size() < MAX_PARTICLES) {
                    freeParticles.add(p);
                }
            }
        }
    }

    private void spawn(int width, int height) {
        final int max = animationsOff ? MAX_PARTICLES / 3 : MAX_PARTICLES;
        // About four new glyphs a second at 60 fps, as long as there is room.
        if (particles.size() >= max || Utilities.random.nextFloat() > 0.07f) {
            return;
        }
        final Particle p = freeParticles.isEmpty() ? new Particle() : freeParticles.remove(freeParticles.size() - 1);
        p.size = dp(8) + Utilities.random.nextFloat() * dp(18);
        p.x = -dp(16) + Utilities.random.nextFloat() * (width + dp(16));
        p.y = -p.size + Utilities.random.nextFloat() * (height + p.size);
        p.vx = Utilities.random.nextFloat() * 12f;
        p.vy = 25f + Utilities.random.nextFloat() * 30f;
        p.angle = Utilities.random.nextFloat() * 360f;
        p.spin = -28.6f + Utilities.random.nextFloat() * 57.2f;
        p.age = 0;
        p.lifeTime = 9000 + Utilities.random.nextInt(5000);
        particles.add(p);
    }

    private static float alphaOf(Particle p) {
        float alpha = MAX_ALPHA;
        if (p.age < FADE_IN_MS) {
            alpha *= p.age / (float) FADE_IN_MS;
        }
        final long left = p.lifeTime - p.age;
        if (left < FADE_OUT_MS) {
            alpha *= Math.max(0, left) / (float) FADE_OUT_MS;
        }
        return alpha;
    }

    private Bitmap getGlyph(Context context) {
        if (glyph != null || glyphFailed) {
            return glyph;
        }
        try {
            final Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            final Drawable drawable = ContextCompat.getDrawable(ctx, R.drawable.zg_glyph);
            if (drawable == null) {
                glyphFailed = true;
                return null;
            }
            final int size = dp(48);
            final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            final Drawable white = drawable.mutate();
            white.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            white.setBounds(0, 0, size, size);
            white.draw(new Canvas(bitmap));
            glyph = bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            glyphFailed = true;
        }
        return glyph;
    }

    private static boolean areAnimationsOff(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return !ValueAnimator.areAnimatorsEnabled();
            }
            final Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            return Settings.Global.getFloat(ctx.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable e) {
            return false;
        }
    }
}
