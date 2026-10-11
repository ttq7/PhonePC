package com.phonepc.explorer.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * ImageView with pinch-to-zoom, drag-to-pan and double-tap zoom.
 *
 * While zoomed in it asks its parent to stop intercepting touch, so the
 * surrounding pager does not steal a pan gesture.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val m = Matrix()
    private var userScale = 1f
    private var dx = 0f
    private var dy = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (userScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (target == userScale) return true
                // keep the focus point roughly stationary
                val k = target / userScale
                dx = detector.focusX - (detector.focusX - dx) * k
                dy = detector.focusY - (detector.focusY - dy) * k
                userScale = target
                if (userScale <= MIN_SCALE) {
                    dx = 0f
                    dy = 0f
                }
                applyTransform()
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (userScale > 1.05f) {
                    userScale = 1f
                    dx = 0f
                    dy = 0f
                } else {
                    userScale = 2.5f
                    dx = 0f
                    dy = 0f
                }
                animateTransform()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        }
    )

    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(userScale > 1.05f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && userScale > 1.05f) {
                    dx += event.x - lastX
                    dy += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    clamp()
                    applyTransform()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                parent?.requestDisallowInterceptTouchEvent(userScale > 1.05f)
            }
        }
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetToFit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    fun resetToFit() {
        userScale = 1f
        dx = 0f
        dy = 0f
        applyTransform()
    }

    private fun fitScale(): Float {
        val d = drawable ?: return 1f
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        if (bw <= 0f || bh <= 0f || width == 0 || height == 0) return 1f
        return min(width / bw, height / bh)
    }

    private fun currentBounds(): FloatArray? {
        val d = drawable ?: return null
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        if (bw <= 0f || bh <= 0f) return null
        val s = fitScale() * userScale
        return floatArrayOf(bw * s, bh * s)
    }

    private fun clamp() {
        val b = currentBounds() ?: return
        val maxX = maxOf(0f, (b[0] - width) / 2f)
        val maxY = maxOf(0f, (b[1] - height) / 2f)
        dx = dx.coerceIn(-maxX, maxX)
        dy = dy.coerceIn(-maxY, maxY)
    }

    private fun applyTransform() {
        val d = drawable
        if (d == null || width == 0 || height == 0) {
            m.reset()
            imageMatrix = m
            return
        }
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        val s = fitScale() * userScale
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - bw * s) / 2f + dx, (height - bh * s) / 2f + dy)
        imageMatrix = m
    }

    /** Small animation so double-tap zoom does not snap. */
    private fun animateTransform() {
        val startDx = dx
        val startDy = dy
        val startScale = 1f
        val steps = 10
        var i = 0
        val runner = object : Runnable {
            override fun run() {
                i++
                val t = i.toFloat() / steps
                dx = startDx * (1 - t)
                dy = startDy * (1 - t)
                applyTransform()
                if (i < steps) postDelayed(this, 12)
            }
        }
        // userScale is already the target; just ease the translation back
        if (abs(startDx) < 0.5f && abs(startDy) < 0.5f) {
            applyTransform()
            return
        }
        post(runner)
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
    }
}
