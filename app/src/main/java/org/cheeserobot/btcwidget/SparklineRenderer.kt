package org.cheeserobot.btcwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Draws a faint 7-day sparkline to a bitmap suitable for use as the
 * widget's background image. The bitmap is the full size of the widget;
 * the line is intentionally low-contrast so the price text remains the
 * star of the show.
 *
 * Colour is chosen by the caller based on the 7-day performance:
 * green if the last value is above the first, red otherwise. We don't
 * reuse the change_indicator colours directly — those are tuned for
 * legible text, whereas the sparkline reads better with the same hues
 * but at much lower alpha.
 *
 * Why not draw it on the device with a custom View? Home-screen widgets
 * use `RemoteViews`, which doesn't support custom views. The standard
 * trick is to render to a bitmap on the CPU and push it onto an
 * `ImageView` via `setImageViewBitmap` — exactly what this does.
 */
object SparklineRenderer {

    /** ARGB colour for an "up" 7-day series. Green-ish. */
    const val COLOR_UP = 0xFF1B7F2E.toInt()

    /** ARGB colour for a "down" 7-day series. Red-ish. */
    const val COLOR_DOWN = 0xFFC02828.toInt()

    /**
     * Render the line to a fresh ARGB bitmap.
     *
     * @param values   chronological price samples. Values <= 1 are skipped (no line drawn).
     * @param widthPx  bitmap width in pixels (>0)
     * @param heightPx bitmap height in pixels (>0)
     * @param color    base ARGB colour for the line — alpha is ignored,
     *                 we apply [lineAlpha] ourselves so callers don't
     *                 have to think about pre-multiplication.
     * @param lineAlpha 0..255 alpha applied to the stroke. Defaults to a
     *                 deliberately faint value so the price text
     *                 remains readable.
     * @param strokePx stroke width in pixels.
     *
     * @return the bitmap, or null when the input can't sensibly be drawn
     *         (≤1 sample, all values equal, or any non-positive size).
     *         Callers should treat null as "use the static background".
     */
    fun render(
        values: DoubleArray,
        widthPx: Int,
        heightPx: Int,
        color: Int,
        lineAlpha: Int = DEFAULT_LINE_ALPHA,
        strokePx: Float = DEFAULT_STROKE_PX,
        backgroundFill: Int? = null,
        cornerRadiusPx: Float = 0f,
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        if (values.size < 2) return null

        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (v in values) {
            if (!v.isFinite()) return null
            if (v < min) min = v
            if (v > max) max = v
        }
        if (min == max) return null

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Optional rounded panel underneath the line. We paint this here
        // (rather than letting the layout's static widget_background show
        // through) because setImageViewBitmap REPLACES the ImageView's
        // src — there's no compositing with the XML drawable.
        if (backgroundFill != null) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = backgroundFill
            }
            val rect = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
            if (cornerRadiusPx > 0f) {
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, bgPaint)
            } else {
                canvas.drawRect(rect, bgPaint)
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = (color and 0x00FFFFFF) or (lineAlpha.coerceIn(0, 255) shl 24)
        }

        // Leave a small vertical breathing room so peaks/troughs aren't
        // clipped against the widget edges. Horizontal padding matches
        // the widget's 12dp content padding visually.
        val padTop = (heightPx * 0.10f).coerceAtLeast(2f)
        val padBottom = (heightPx * 0.10f).coerceAtLeast(2f)
        val padHoriz = strokePx
        val plotW = (widthPx - padHoriz * 2f).coerceAtLeast(1f)
        val plotH = (heightPx - padTop - padBottom).coerceAtLeast(1f)
        val range = (max - min).toFloat()

        val n = values.size
        val stepX = if (n > 1) plotW / (n - 1).toFloat() else 0f

        val path = Path()
        for (i in 0 until n) {
            val x = padHoriz + stepX * i
            // Y is inverted: highest price near the top.
            val norm = ((values[i] - min) / range).toFloat()
            val y = padTop + (1f - norm) * plotH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    /**
     * Convenience wrapper: pick the right colour based on whether the
     * 7-day series is up or down. First and last values define the
     * direction; intermediate movement doesn't affect the colour.
     */
    fun colorFor(values: DoubleArray): Int {
        if (values.size < 2) return COLOR_UP
        return if (values.last() >= values.first()) COLOR_UP else COLOR_DOWN
    }

    /** Default stroke width in pixels. Caller can scale by display density. */
    const val DEFAULT_STROKE_PX: Float = 3f

    /**
     * Default line alpha. ~28 % opacity reads as a soft tint behind the
     * price text on both light and dark themes without requiring
     * theme-specific tuning.
     */
    const val DEFAULT_LINE_ALPHA: Int = 72

    /** Helper for callers that just want an opaque ARGB int. */
    @Suppress("unused")
    fun toOpaque(argb: Int): Int = argb or Color.BLACK
}
