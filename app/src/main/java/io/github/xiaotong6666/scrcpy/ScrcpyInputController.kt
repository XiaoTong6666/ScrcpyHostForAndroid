package io.github.xiaotong6666.scrcpy

import android.graphics.RectF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

class ScrcpyInputController(
    private val controlClient: ScrcpyControlClient,
) {
    enum class DisplayMode {
        FIT_CENTER,
        CENTER_CROP,
        STRETCH_FILL,
    }

    @Volatile
    private var videoWidth: Int = 0

    @Volatile
    private var videoHeight: Int = 0

    @Volatile
    private var displayMode: DisplayMode = DisplayMode.FIT_CENTER

    fun updateVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    fun setDisplayMode(mode: DisplayMode) {
        displayMode = mode
    }

    fun handleMotionEvent(view: View, event: MotionEvent): Boolean {
        if (!hasVideoSize()) {
            return false
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    if (!sendTouchEventForPointer(view, event, MotionEvent.ACTION_MOVE, pointerIndex)) {
                        return false
                    }
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    if (!sendTouchEventForPointer(view, event, MotionEvent.ACTION_UP, pointerIndex)) {
                        return false
                    }
                }
                true
            }

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                sendTouchEventForPointer(view, event, MotionEvent.ACTION_DOWN, event.actionIndex)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                sendTouchEventForPointer(view, event, MotionEvent.ACTION_UP, event.actionIndex)
            }

            else -> false
        }
    }

    fun handleGenericMotionEvent(view: View, event: MotionEvent): Boolean {
        if (!hasVideoSize()) {
            return false
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            -> {
                sendMouseEventForPointer(view, event, event.actionMasked, event.actionIndex)
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                sendMouseEventForPointer(view, event, MotionEvent.ACTION_DOWN, event.actionIndex)
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                sendMouseEventForPointer(view, event, MotionEvent.ACTION_UP, event.actionIndex)
            }

            MotionEvent.ACTION_SCROLL -> {
                sendScrollEvent(view, event)
            }

            else -> false
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean = controlClient.sendKeyEvent(event)

    private fun sendTouchEventForPointer(
        view: View,
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
    ): Boolean {
        val position = getAbsoluteVideoPosition(view, event, pointerIndex) ?: return false
        val pointerId = event.getPointerId(pointerIndex).toLong()
        return controlClient.sendTouchEvent(
            action = action,
            pointerId = pointerId,
            x = position.x,
            y = position.y,
            videoWidth = position.videoWidth,
            videoHeight = position.videoHeight,
            pressure = getPressureOrDistance(event, pointerIndex),
            actionButton = 0,
            buttons = 0,
        )
    }

    private fun sendMouseEventForPointer(
        view: View,
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
    ): Boolean {
        val position = getAbsoluteVideoPosition(view, event, pointerIndex) ?: return false
        return controlClient.sendTouchEvent(
            action = action,
            pointerId = POINTER_ID_MOUSE,
            x = position.x,
            y = position.y,
            videoWidth = position.videoWidth,
            videoHeight = position.videoHeight,
            pressure = getPressureOrDistance(event, pointerIndex),
            actionButton = event.actionButton,
            buttons = event.buttonState,
        )
    }

    private fun sendScrollEvent(view: View, event: MotionEvent): Boolean {
        val position = getAbsoluteVideoPosition(view, event, event.actionIndex) ?: return false
        val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        return controlClient.sendScrollEvent(
            x = position.x,
            y = position.y,
            videoWidth = position.videoWidth,
            videoHeight = position.videoHeight,
            hScroll = hScroll,
            vScroll = vScroll,
            buttons = event.buttonState,
        )
    }

    private fun getAbsoluteVideoPosition(
        view: View,
        event: MotionEvent,
        pointerIndex: Int,
    ): VideoPosition? {
        val normalized = getStreamViewRelativeNormalizedXY(view, event, pointerIndex) ?: return null
        val currentVideoWidth = videoWidth
        val currentVideoHeight = videoHeight
        if (currentVideoWidth <= 0 || currentVideoHeight <= 0) {
            return null
        }

        val absoluteX = (normalized[0] * currentVideoWidth)
            .toInt()
            .coerceIn(0, currentVideoWidth.saturatingSubtract(1))
        val absoluteY = (normalized[1] * currentVideoHeight)
            .toInt()
            .coerceIn(0, currentVideoHeight.saturatingSubtract(1))
        return VideoPosition(
            x = absoluteX,
            y = absoluteY,
            videoWidth = currentVideoWidth,
            videoHeight = currentVideoHeight,
        )
    }

    private fun getStreamViewRelativeNormalizedXY(
        view: View,
        event: MotionEvent,
        pointerIndex: Int,
    ): FloatArray? {
        return when (displayMode) {
            DisplayMode.FIT_CENTER -> {
                val contentRect = getRenderedVideoRect(view) ?: return null
                val normalizedX = ((event.getX(pointerIndex) - contentRect.left) / contentRect.width()).coerceIn(0f, 1f)
                val normalizedY = ((event.getY(pointerIndex) - contentRect.top) / contentRect.height()).coerceIn(0f, 1f)
                floatArrayOf(normalizedX, normalizedY)
            }

            DisplayMode.CENTER_CROP -> {
                getCenterCropNormalizedXY(view, event, pointerIndex)
            }

            DisplayMode.STRETCH_FILL -> {
                val width = view.width.toFloat()
                val height = view.height.toFloat()
                if (width <= 0f || height <= 0f) {
                    return null
                }
                floatArrayOf(
                    (event.getX(pointerIndex) / width).coerceIn(0f, 1f),
                    (event.getY(pointerIndex) / height).coerceIn(0f, 1f),
                )
            }
        }
    }

    private fun getRenderedVideoRect(view: View): RectF? {
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val currentVideoWidth = videoWidth.toFloat()
        val currentVideoHeight = videoHeight.toFloat()
        if (width <= 0f || height <= 0f || currentVideoWidth <= 0f || currentVideoHeight <= 0f) {
            return null
        }

        val videoAspect = currentVideoWidth / currentVideoHeight
        val surfaceAspect = width / height
        return if (videoAspect > surfaceAspect) {
            val contentHeight = width / videoAspect
            val top = (height - contentHeight) * 0.5f
            RectF(0f, top, width, top + contentHeight)
        } else {
            val contentWidth = height * videoAspect
            val left = (width - contentWidth) * 0.5f
            RectF(left, 0f, left + contentWidth, height)
        }
    }

    private fun getCenterCropNormalizedXY(
        view: View,
        event: MotionEvent,
        pointerIndex: Int,
    ): FloatArray? {
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val currentVideoWidth = videoWidth.toFloat()
        val currentVideoHeight = videoHeight.toFloat()
        if (width <= 0f || height <= 0f || currentVideoWidth <= 0f || currentVideoHeight <= 0f) {
            return null
        }

        val videoAspect = currentVideoWidth / currentVideoHeight
        val surfaceAspect = width / height
        val rawX = (event.getX(pointerIndex) / width).coerceIn(0f, 1f)
        val rawY = (event.getY(pointerIndex) / height).coerceIn(0f, 1f)

        return if (videoAspect > surfaceAspect) {
            val visibleFraction = surfaceAspect / videoAspect
            val cropOffset = (1f - visibleFraction) * 0.5f
            floatArrayOf(
                (cropOffset + rawX * visibleFraction).coerceIn(0f, 1f),
                rawY,
            )
        } else {
            val visibleFraction = videoAspect / surfaceAspect
            val cropOffset = (1f - visibleFraction) * 0.5f
            floatArrayOf(
                rawX,
                (cropOffset + rawY * visibleFraction).coerceIn(0f, 1f),
            )
        }
    }

    private fun getPressureOrDistance(event: MotionEvent, pointerIndex: Int): Float = when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_MOVE,
        MotionEvent.ACTION_HOVER_EXIT,
        -> {
            val range = event.device?.getMotionRange(MotionEvent.AXIS_DISTANCE, event.source)
            if (range != null && range.range > 0f) {
                ((event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex) - range.min) / range.range)
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
        }

        else -> event.getPressure(pointerIndex).coerceIn(0f, 1f)
    }

    fun shouldHandleRemotely(event: MotionEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_CLASS_POINTER != 0 ||
            source and InputDevice.SOURCE_CLASS_POSITION != 0
    }

    private fun hasVideoSize(): Boolean = videoWidth > 0 && videoHeight > 0

    private fun Int.saturatingSubtract(value: Int): Int = (this - value).coerceAtLeast(0)

    private data class VideoPosition(
        val x: Int,
        val y: Int,
        val videoWidth: Int,
        val videoHeight: Int,
    )

    companion object {
        private const val POINTER_ID_MOUSE = -1L
    }
}
