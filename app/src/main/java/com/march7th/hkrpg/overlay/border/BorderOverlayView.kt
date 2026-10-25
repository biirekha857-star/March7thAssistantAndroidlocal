package com.march7th.hkrpg.overlay.border

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

class BorderOverlayView(context: Context, private val style: BorderStyle = BorderStyle()) :
    View(context) {


    private val borderWidthPx: Float = style.widthDp * resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }

    private val borderPath = Path()
    private val borderRect = RectF()
    private val gradientMatrix = Matrix()

    private var rotationAngle = 0f
    private var sweepGradient: SweepGradient? = null

    private var cornerRadiusTopLeft = 0f
    private var cornerRadiusTopRight = 0f
    private var cornerRadiusBottomLeft = 0f
    private var cornerRadiusBottomRight = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = style.animationDurationMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            rotationAngle = animation.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 可以获取屏幕圆角
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let {
                cornerRadiusTopLeft = it.radius.toFloat()
            }
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.let {
                cornerRadiusTopRight = it.radius.toFloat()
            }
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.let {
                cornerRadiusBottomLeft = it.radius.toFloat()
            }
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.let {
                cornerRadiusBottomRight = it.radius.toFloat()
            }
            updateBorderPath()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val halfBorder = borderWidthPx / 2
        borderRect.set(halfBorder, halfBorder, w - halfBorder, h - halfBorder)

        // 创建渐变
        sweepGradient = SweepGradient(
            w / 2f, h / 2f,
            style.colors,
            null
        )
        paint.shader = sweepGradient

        // 如果 Android 12 以下，使用默认圆角
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val defaultRadius = getDefaultCornerRadius()
            cornerRadiusTopLeft = defaultRadius
            cornerRadiusTopRight = defaultRadius
            cornerRadiusBottomLeft = defaultRadius
            cornerRadiusBottomRight = defaultRadius
        }

        updateBorderPath()
    }

    /**
     * 获取默认圆角半径（用于 Android 12 以下）
     *
     * 读取系统框架隐藏 dimen `android:rounded_corner_radius`,用于 API 31 以下匹配设备真实圆角。
     * 该资源为 com.android.internal.R 的 @hide 资源,无公开 R 常量可引用,且内部 id 跨版本/OEM 不稳定,
     * 只能按名 getIdentifier 解析 —— 这正是 DiscouragedApi 承认的合法例外,故在此抑制。
     */
    @SuppressLint("DiscouragedApi")
    private fun getDefaultCornerRadius(): Float {
        // 尝试从系统属性获取
        return try {
            val resId = resources.getIdentifier(
                "rounded_corner_radius", "dimen", "android"
            )
            if (resId > 0) {
                resources.getDimension(resId)
            } else {
                0f
            }
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 更新边框路径
     */
    private fun updateBorderPath() {
        borderPath.reset()

        val radii = floatArrayOf(
            cornerRadiusTopLeft, cornerRadiusTopLeft,
            cornerRadiusTopRight, cornerRadiusTopRight,
            cornerRadiusBottomRight, cornerRadiusBottomRight,
            cornerRadiusBottomLeft, cornerRadiusBottomLeft
        )

        borderPath.addRoundRect(borderRect, radii, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 应用旋转动画
        sweepGradient?.let { gradient ->
            gradientMatrix.setRotate(rotationAngle, width / 2f, height / 2f)
            gradient.setLocalMatrix(gradientMatrix)
        }

        canvas.drawPath(borderPath, paint)
    }
}
