package com.cartographer.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val contentMatrix = Matrix()
    private val imageBounds = RectF()
    private var fitScale = 1f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    /** 当前图像旋转角（顺时针为正，单位度），由外层手势驱动。 */
    var rotationDegrees: Float = 0f
        private set

    /**
     * 整图适配屏幕时是否也允许单指拖动（默认 false：图小于视图时强制居中）。
     * 置 true 时图像可在视图内自由平移，便于只用单点触摸的场景。
     */
    var freePan: Boolean = false

    /** 当前缩放倍数（相对初始适配缩放 fitScale，1f 表示适配原始比例）。 */
    val currentZoomFactor: Float
        get() = if (fitScale > 0f) currentScale / fitScale else 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        setBackgroundColor(0xFFFFFFFF.toInt())
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> {
                if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    contentMatrix.postTranslate(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                    constrainToView()
                    imageMatrix = contentMatrix
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun resetZoom() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return

        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        fitScale = min(
            availableWidth / drawable.intrinsicWidth.toFloat(),
            availableHeight / drawable.intrinsicHeight.toFloat()
        ).coerceAtLeast(0.001f)
        currentScale = fitScale
        rotationDegrees = 0f

        val dx = paddingLeft + (availableWidth - drawable.intrinsicWidth * fitScale) * 0.5f
        val dy = paddingTop + (availableHeight - drawable.intrinsicHeight * fitScale) * 0.5f
        contentMatrix.reset()
        contentMatrix.postScale(fitScale, fitScale)
        contentMatrix.postTranslate(dx, dy)
        imageMatrix = contentMatrix
    }

    /** 以焦点为中心缩放（倍数相对当前缩放，内部按 [MAX_ZOOM] 限制）。
     * @return 是否真正发生了缩放；若倍数被缩放边界钳制（实际未变化）返回 false。 */
    fun scaleBy(factor: Float, focusX: Float, focusY: Float): Boolean {
        val maxScale = fitScale * MAX_ZOOM
        val desired = (currentScale * factor).coerceIn(fitScale, maxScale)
        val f = desired / currentScale
        if (f.isFinite() && f > 0f && f != 1f) {
            contentMatrix.postScale(f, f, focusX, focusY)
            currentScale = desired
            constrainToView()
            imageMatrix = contentMatrix
            return true
        }
        return false
    }

    /** 以焦点为中心旋转图像，degrees 为顺时针正值。 */
    fun rotateBy(degrees: Float, focusX: Float, focusY: Float) {
        if (!degrees.isFinite() || degrees == 0f) return
        contentMatrix.postRotate(degrees, focusX, focusY)
        rotationDegrees += degrees
        constrainToView()
        imageMatrix = contentMatrix
    }

    /** 平移图像（约束在可视区域内）。 */
    fun translateBy(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return
        contentMatrix.postTranslate(dx, dy)
        constrainToView()
        imageMatrix = contentMatrix
    }

    /** 导出当前完整变换矩阵（缩放 + 旋转 + 平移，含适配与居中）。 */
    fun getContentTransform(): Matrix = Matrix(contentMatrix)

    /**
     * 直接应用一组外部变换矩阵，并同步旋转角与缩放倍数。
     * 用于换图后恢复用户视角（旋转、缩放、平移）。矩阵需为均匀缩放 + 旋转 + 平移。
     */
    fun setContentTransform(matrix: Matrix) {
        contentMatrix.set(matrix)
        val values = FloatArray(9)
        contentMatrix.getValues(values)
        val a = values[Matrix.MSCALE_X]
        val b = values[Matrix.MSKEW_Y]
        rotationDegrees = Math.toDegrees(Math.atan2(b.toDouble(), a.toDouble())).toFloat()
        val scale = Math.hypot(a.toDouble(), b.toDouble()).toFloat()
        if (fitScale > 0f) {
            currentScale = scale.coerceAtLeast(fitScale)
        }
        imageMatrix = contentMatrix
    }

    private fun constrainToView() {
        val drawable = drawable ?: return
        imageBounds.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        contentMatrix.mapRect(imageBounds)

        val leftLimit = paddingLeft.toFloat()
        val topLimit = paddingTop.toFloat()
        val rightLimit = (width - paddingRight).toFloat()
        val bottomLimit = (height - paddingBottom).toFloat()
        val margin = PAN_OVERSCROLL_DP * resources.displayMetrics.density

        val dx = when {
            imageBounds.width() <= rightLimit - leftLimit ->
                if (freePan) {
                    when {
                        imageBounds.left < leftLimit - margin -> leftLimit - margin - imageBounds.left
                        imageBounds.right > rightLimit + margin -> rightLimit + margin - imageBounds.right
                        else -> 0f
                    }
                } else {
                    val center = (leftLimit + rightLimit) * 0.5f
                    val offset = (imageBounds.centerX() - center).coerceIn(-margin, margin)
                    center + offset - imageBounds.centerX()
                }
            imageBounds.left > leftLimit + margin -> leftLimit + margin - imageBounds.left
            imageBounds.right < rightLimit - margin -> rightLimit - margin - imageBounds.right
            else -> 0f
        }
        val dy = when {
            imageBounds.height() <= bottomLimit - topLimit ->
                if (freePan) {
                    when {
                        imageBounds.top < topLimit - margin -> topLimit - margin - imageBounds.top
                        imageBounds.bottom > bottomLimit + margin -> bottomLimit + margin - imageBounds.bottom
                        else -> 0f
                    }
                } else {
                    val center = (topLimit + bottomLimit) * 0.5f
                    val offset = (imageBounds.centerY() - center).coerceIn(-margin, margin)
                    center + offset - imageBounds.centerY()
                }
            imageBounds.top > topLimit + margin -> topLimit + margin - imageBounds.top
            imageBounds.bottom < bottomLimit - margin -> bottomLimit - margin - imageBounds.bottom
            else -> 0f
        }
        contentMatrix.postTranslate(dx, dy)
    }

    companion object {
        private const val MAX_ZOOM = 8f

        /**
         * 上下左右各允许越出视图边缘的额外可拖距离（dp）：值越大可移范围越大，
         * 0 表示恢复原有的严格贴边（图像边缘不得越过视图边缘）。
         */
        private const val PAN_OVERSCROLL_DP = 48f
    }
}
