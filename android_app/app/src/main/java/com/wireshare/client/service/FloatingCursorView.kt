package com.wireshare.client.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

class FloatingCursorView(context: Context) : View(context) {

    private val arrowPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Sky Blue desktop pointer
        style = Paint.Style.FILL
        setShadowLayer(8f, 2f, 4f, Color.parseColor("#80000000"))
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    var cursorX: Float = 0f
    var cursorY: Float = 0f

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, fillPaint)
        buildCursorArrow()
        visibility = INVISIBLE
    }

    private fun buildCursorArrow() {
        arrowPath.reset()
        arrowPath.moveTo(0f, 0f)
        arrowPath.lineTo(0f, 48f)
        arrowPath.lineTo(14f, 36f)
        arrowPath.lineTo(26f, 62f)
        arrowPath.lineTo(33f, 59f)
        arrowPath.lineTo(21f, 34f)
        arrowPath.lineTo(36f, 34f)
        arrowPath.close()
    }

    fun initScreenDimensions(wm: WindowManager, params: WindowManager.LayoutParams) {
        this.windowManager = wm
        this.layoutParams = params

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        recenterCursor()
    }

    fun recenterCursor(): Pair<Float, Float> {
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
        updateLayout()
        return Pair(cursorX, cursorY)
    }

    fun setCursorVisible(visible: Boolean) {
        val targetVis = if (visible) VISIBLE else INVISIBLE
        if (visibility != targetVis) {
            visibility = targetVis
            invalidate()
        }
    }

    fun calculateNextTarget(currentTargetX: Float, currentTargetY: Float, dx: Int, dy: Int, sensitivity: Float): Pair<Float, Float> {
        val nX = max(0f, min((screenWidth - 10).toFloat(), currentTargetX + (dx * sensitivity)))
        val nY = max(0f, min((screenHeight - 10).toFloat(), currentTargetY + (dy * sensitivity)))
        return Pair(nX, nY)
    }

    fun setPositionSmooth(newX: Float, newY: Float) {
        cursorX = newX
        cursorY = newY
        updateLayout()
    }

    private fun updateLayout() {
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        params.x = cursorX.toInt()
        params.y = cursorY.toInt()
        try {
            wm.updateViewLayout(this, params)
        } catch (e: Exception) {
            // View not attached yet
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, borderPaint)
    }
}
