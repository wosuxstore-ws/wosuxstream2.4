package com.freetv.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * Skeleton placeholder (Phase 23): a rounded rectangle (radius = @dimen/card_radius) with a soft highlight that sweeps
 * from left to right (gradient surface -> surface_focus -> surface).
 * The ValueAnimator runs ONLY while the view is attached, shown (it and all its parents visible) and its window is visible;
 * it is cancelled when any of that stops being true and always in onDetachedFromWindow.
 * Size comes from the layout (width / height), there are no custom attributes.
 */
class ShimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private val radius = resources.getDimension(R.dimen.card_radius)
    private val colors = intArrayOf(
        ContextCompat.getColor(context, R.color.surface),
        ContextCompat.getColor(context, R.color.surface_focus),
        ContextCompat.getColor(context, R.color.surface)
    )
    private val positions = floatArrayOf(0f, 0.5f, 1f)

    private var shader: LinearGradient? = null
    private var animator: ValueAnimator? = null
    private var progress = 0f

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        if (w > 0) {
            // The gradient is as wide as the view; moving it from one view width to the left to one view width to the right
            // (CLAMP, both ends are `surface`) makes a highlight that crosses the view.
            val s = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, positions, Shader.TileMode.CLAMP)
            shader = s
            paint.shader = s
            applyProgress()
        } else {
            shader = null
            paint.shader = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (shader == null) return
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    // Called when this view or one of its parents changes visibility (the skeleton is hidden by hiding its root)
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        if (isAttachedToWindow && isShown && windowVisibility == View.VISIBLE) startAnimation() else stopAnimation()
    }

    private fun startAnimation() {
        if (animator != null) return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = SWEEP_MS
        a.interpolator = LinearInterpolator()
        a.repeatCount = ValueAnimator.INFINITE
        a.addUpdateListener { va ->
            progress = va.animatedValue as Float
            applyProgress()
            invalidate()
        }
        animator = a
        a.start()
    }

    private fun stopAnimation() {
        val a = animator ?: return
        animator = null
        a.removeAllUpdateListeners()
        a.cancel()
    }

    private fun applyProgress() {
        val s = shader ?: return
        shaderMatrix.setTranslate((progress * 2f - 1f) * width, 0f)
        s.setLocalMatrix(shaderMatrix)
    }

    private companion object {
        const val SWEEP_MS = 1300L
    }
}
