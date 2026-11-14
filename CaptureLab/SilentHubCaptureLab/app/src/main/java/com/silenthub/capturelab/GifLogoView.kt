package com.silenthub.capturelab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.os.SystemClock
import android.view.View

/**
 * Plays the original bundled GIF without converting its frames or changing timing.
 * The animation is active only while this view is attached to a visible window.
 */
@Suppress("DEPRECATION")
internal class GifLogoView(
    context: Context,
    resourceId: Int
) : View(context) {
    private val movie: Movie? = runCatching {
        context.resources.openRawResource(resourceId).use { Movie.decodeStream(it) }
    }.getOrNull()

    private var startedAt = 0L

    init {
        contentDescription = "SilentHub animated logo"
        setBackgroundColor(Color.BLACK)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animation = movie ?: return
        val duration = animation.duration().takeIf { it > 0 } ?: 1000
        val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
        animation.setTime((elapsed % duration).toInt())

        val scale = minOf(
            width.toFloat() / animation.width().coerceAtLeast(1),
            height.toFloat() / animation.height().coerceAtLeast(1)
        )
        val drawWidth = animation.width() * scale
        val drawHeight = animation.height() * scale
        val save = canvas.save()
        canvas.translate((width - drawWidth) / 2f, (height - drawHeight) / 2f)
        canvas.scale(scale, scale)
        animation.draw(canvas, 0f, 0f)
        canvas.restoreToCount(save)

        if (isAttachedToWindow && visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }
}
