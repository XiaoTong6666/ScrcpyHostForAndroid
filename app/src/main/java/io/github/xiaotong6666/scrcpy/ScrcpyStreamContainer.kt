package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

class ScrcpyStreamContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    enum class ScaleMode {
        FIT,
        FILL,
        STRETCH,
    }

    private var desiredAspectRatio: Double = 0.0
    private var scaleMode: ScaleMode = ScaleMode.FILL

    fun setVideoAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            desiredAspectRatio = 0.0
        } else {
            desiredAspectRatio = width.toDouble() / height.toDouble()
        }
        requestLayout()
    }

    fun setScaleMode(mode: ScaleMode) {
        if (scaleMode == mode) {
            return
        }
        scaleMode = mode
        requestLayout()
    }

    fun getScaleMode(): ScaleMode = scaleMode

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        if (scaleMode == ScaleMode.STRETCH || desiredAspectRatio <= 0.0) {
            if (widthSize > 0 && heightSize > 0) {
                setMeasuredDimension(widthSize, heightSize)
                val childWidthSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
                val childHeightSpec = MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
                measureChildren(childWidthSpec, childHeightSpec)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
            return
        }

        if (widthSize <= 0 || heightSize <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val fillDisplay = scaleMode == ScaleMode.FILL
        val measuredWidth: Int
        val measuredHeight: Int

        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize
                measuredWidth = (heightSize * desiredAspectRatio).roundToInt()
            } else {
                measuredWidth = widthSize
                measuredHeight = (widthSize / desiredAspectRatio).roundToInt()
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize
                measuredWidth = (measuredHeight * desiredAspectRatio).roundToInt()
            } else {
                measuredWidth = widthSize
                measuredHeight = (measuredWidth / desiredAspectRatio).roundToInt()
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        measureChildren(childWidthSpec, childHeightSpec)
    }
}
