package com.cartographer.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * 户型图结果容器：内部包含 [ZoomableImageView]，在其右上角绘制指北针
 * （复刻 KaerMapView 造型），并支持双指缩放 + 旋转（指北针随图像旋转同步指示地图北向）。
 *
 * 触摸策略：全部触摸由本容器统一处理——单指拖动、双指缩放 + 旋转。
 */
class CompassFloorPlanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val imageView = ZoomableImageView(context)

    // ---- 添加房间：蓝色实心矩形叠加层（图像坐标，随地图缩放/旋转并随保存导出）----
    private val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4EA0FE")
        style = Paint.Style.FILL
    }
    private val roomRects = ArrayList<RectF>()
    /** 每个房间自身的旋转角（度，围绕房间中心顺时针为正），与 [roomRects] 下标一一对应。 */
    private val roomRotations = ArrayList<Float>()

    // ---- 房间编辑（长按房间激活）：四角直角句柄缩放 / 按住房间内部拖动平移 ----
    private var editingIndex = -1            // -1 = 未进入编辑
    private var roomDragMode = 0             // 0 无 / 1 平移 / 2 缩放
    private var dragCorner = 0               // 被拖动的角：0 左上 / 1 右上 / 2 右下 / 3 左下
    private val dragOriginal = RectF()       // 拖动开始时房间的原始矩形（图像坐标）
    private var dragFixedX = 0f              // 缩放时固定不动的对角（图像坐标）
    private var dragFixedY = 0f
    private var dragDownImageX = 0f          // 按下点在图像坐标的位置
    private var dragDownImageY = 0f
    private var longPressDownX = 0f
    private var longPressDownY = 0f
    private val longPressHandler = Handler(Looper.getMainLooper())

    private val roomSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#1E5CFF")
    }

    // 顶部旋转手柄（白圆 + 蓝色环形箭头）：仅图标暗示可旋转
    private val rotateFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val rotateRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#1E5CFF")
    }
    private val rotateArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E5CFF")
        style = Paint.Style.FILL
    }

    private val density: Float
        get() = resources.displayMetrics.density

    /** 是否绘制右上角指北针（部分页面只需静态展示地图，不需要指北针）。 */
    private var compassVisible = true

    /**
     * 图内北向相对「图像上方」的顺时针偏置（度），取值来自磁北对齐结果
     * （SDK 的 MagneticNorthAlignment.floorPlanRotationDegrees：把原图顺时针旋转该角度后即北上）。
     * 0 表示图像上方就是地图北；未设置或传入非有限值时按 0 处理，指北针保持原有显示。
     */
    private var mapNorthRotationDegrees = 0f

    // ---- 右上角指北针（复刻 KaerMapView 造型：深灰细环 + 红北箭头/蓝南箭头）----
    private val compassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 30, 34, 42)
        style = Paint.Style.FILL
    }
    private val compassRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val compassNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 82, 82)
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val compassNeedleTailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 205, 205)
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val compassRadius: Float
        get() = 24f * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        // 全部手势由本容器在 onTouchEvent 里处理，标记可点击让触摸目标更明确
        isClickable = true
        addView(
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /**
     * 是否显示右上角指北针（默认显示，与信号覆盖页一致）。
     * 关闭后同时停用该区域的“轻点复位视角”热区。
     */
    fun setCompassVisible(visible: Boolean) {
        if (compassVisible == visible) return
        compassVisible = visible
        invalidate()
    }

    /**
     * 设置图内北向相对图像上方的顺时针偏置（度）：把原图顺时针旋转该角度后即「北朝上」。
     * 传 0 或不传（默认）时图像上方即北，指北针行为与之前完全一致；
     * 传 NaN/Infinity 视作未取得磁北，同样按 0 处理。
     */
    fun setMapNorthRotationDegrees(degrees: Float) {
        val normalized = if (degrees.isFinite()) degrees else 0f
        if (mapNorthRotationDegrees == normalized) return
        mapNorthRotationDegrees = normalized
        invalidate()
    }

    /**
     * 轻点指北针：先做视角复位（缩放/平移/地图角度归零），再把图旋转到
     * 「图内北朝右」的标准姿态（左南右北），此时指北针水平、红北针朝右。
     *
     * 指北针当前总旋转角 = mapNorthRotationDegrees - imageView.rotationDegrees，
     * 要让红针朝右（顺时针 90°），即把地图旋转到 mapNorthRotationDegrees - 90°。
     */
    private fun resetViewWithNorthOnRight() {
        resetZoom()   // 同步执行：恢复适配缩放、居中，并把 rotationDegrees 归零
        if (width <= 0 || height <= 0) return
        // 未取得磁北（偏置为 0）时保持原先的「纯视角复位」行为，不做额外旋转，
        // 避免影响老图（无 metadata）与未接入磁北的页面
        if (mapNorthRotationDegrees == 0f) {
            invalidate()
            return
        }
        val target = ((mapNorthRotationDegrees - 90f) % 360f + 360f) % 360f
        // 取最短旋转路径，避免绕远路转大半圈
        val delta = if (target > 180f) target - 360f else target
        if (delta == 0f) {
            invalidate()
            return
        }
        imageView.rotateBy(delta, width / 2f, height / 2f)
        invalidate()
    }

    /**
     * 是否允许整图适配屏幕时也单指拖动（默认 false：图小于视图时锁定居中，同信号覆盖页）。
     * 置 true 后单指拖动在任何缩放下都能移动地图，单点触摸设备也能平移。
     */
    fun setFreePanEnabled(enabled: Boolean) {
        imageView.freePan = enabled
    }

    // ==================== 对外接口（委托给内部 imageView） ====================

    fun setImageBitmap(bm: Bitmap?) {
        // 仅当此前已显示过图像时保留并恢复视角；首次加载走内部 resetZoom 适配居中，
        // 避免把初始 identity 矩阵覆盖到新图上导致图像显示在左上角
        val hasPreviousImage = imageView.drawable != null
        val transform = imageView.getContentTransform()
        imageView.setImageBitmap(bm)
        if (bm != null && width > 0 && hasPreviousImage) {
            post {
                imageView.setContentTransform(transform)
                invalidate()
            }
        } else {
            invalidate()
        }
    }

    fun resetZoom() {
        imageView.resetZoom()
        invalidate()
    }

    fun changeMapRotation() {
        if (width <= 0 || height <= 0) return
        val target = ((imageView.rotationDegrees + 90f) % 360f + 360f) % 360f
        imageView.rotateBy(target - imageView.rotationDegrees, width / 2f, height / 2f)
        invalidate()
    }

    /**
     * 在当前画布（视图可见区域）的中心添加一个蓝色实心矩形房间：
     * 把画布中心点（宽/2, 高/2）经当前图像矩阵逆变换映射为图像坐标作为房间中心，
     * 因此平移地图后点击，房间会落在当前视野正中央对应的地图位置上。
     * 尺寸规则：房间在屏幕上保持固定大小（约 [ROOM_SCREEN_WIDTH_DP]×[ROOM_SCREEN_HEIGHT_DP] dp），
     * 图像坐标尺寸 = 固定屏幕尺寸 ÷ 当前整图显示缩放（适配缩放 × 用户缩放因子）。
     * 多次点击全部保留；房间存于图像坐标，随地图缩放/旋转/平移，
     * 并随 [saveDiyMap] / [saveDiyMapViewport] 一起导出。
     * @return 是否添加成功（需已加载地图位图）
     */
    fun addRoomAtCenter(): Boolean {
        val drawable = imageView.drawable ?: return false
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw <= 0 || ih <= 0) return false
        // 画布中心屏幕坐标 → 图像坐标（当前画布正中央对应的地图点）
        val inverse = Matrix()
        if (!imageView.imageMatrix.invert(inverse)) return false
        val center = floatArrayOf(width / 2f, height / 2f)
        inverse.mapPoints(center)
        // 当前整图显示缩放 = 适配缩放(fit) × 用户缩放因子(currentZoomFactor)
        val scale = displayScale()
        val density = resources.displayMetrics.density
        // 把固定屏幕尺寸换算成图像坐标（缩放越大，图上占位越小）
        val w = (ROOM_SCREEN_WIDTH_DP * density / scale).coerceIn(1f, iw.toFloat())
        val h = (ROOM_SCREEN_HEIGHT_DP * density / scale).coerceIn(1f, ih.toFloat())
        // 房间中心 = 画布中心对应图像坐标，并夹取在地图范围内保证房间完整可见
        val cx = center[0].coerceIn(w / 2f, iw - w / 2f)
        val cy = center[1].coerceIn(h / 2f, ih - h / 2f)
        roomRects.add(RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f))
        roomRotations.add(0f)
        invalidate()
        return true
    }

    // ==================== 房间编辑：长按激活，拖动/缩放 ====================

    /** 当前整图显示缩放 = 适配缩放(fit) × 用户缩放因子(currentZoomFactor)。 */
    private fun displayScale(): Float {
        val drawable = imageView.drawable ?: return 1f
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return 1f
        val fit = if (width > 0 && height > 0)
            min(width / drawable.intrinsicWidth.toFloat(), height / drawable.intrinsicHeight.toFloat()) else 1f
        return (fit * imageView.currentZoomFactor).coerceAtLeast(0.001f)
    }

    private fun imageWidth(): Float {
        val d = imageView.drawable ?: return 0f
        return d.intrinsicWidth.toFloat()
    }

    private fun imageHeight(): Float {
        val d = imageView.drawable ?: return 0f
        return d.intrinsicHeight.toFloat()
    }

    /** 屏幕坐标 → 图像坐标（当前显示矩阵的逆变换）。 */
    private fun screenToImage(x: Float, y: Float): FloatArray {
        val inverse = Matrix()
        if (!imageView.imageMatrix.invert(inverse)) return floatArrayOf(x, y)
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        return p
    }

    /**
     * 绕房间中心把单个图像坐标点旋转 [rot] 度（y 轴向下、顺时针为正）。
     * 直接三角函数计算，规避 Matrix.postRotate 轴心所在坐标系的歧义，
     * 保证旋转轴严格是房间图像中心。
     */
    private fun rotateImagePoint(cx: Float, cy: Float, rot: Float, x: Float, y: Float): FloatArray {
        val dx = x - cx
        val dy = y - cy
        if (rot == 0f) return floatArrayOf(x, y)
        val rad = Math.toRadians(rot.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        return floatArrayOf(
            cx + dx * cos - dy * sin,
            cy + dx * sin + dy * cos)
    }

    /** 房间四角（图像坐标，左上/右上/右下/左下）绕房间中心旋转 [rot] 度后的图像坐标点。 */
    private fun rotatedCornersImage(rect: RectF, rot: Float): FloatArray {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val pts = floatArrayOf(
            rect.left, rect.top,
            rect.right, rect.top,
            rect.right, rect.bottom,
            rect.left, rect.bottom)
        if (rot == 0f) return pts
        val rad = Math.toRadians(rot.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        for (i in 0 until 4) {
            val dx = pts[i * 2] - cx
            val dy = pts[i * 2 + 1] - cy
            pts[i * 2] = cx + dx * cos - dy * sin
            pts[i * 2 + 1] = cy + dx * sin + dy * cos
        }
        return pts
    }

    /** 屏幕点是否落在（可旋转的）房间内：反旋转回房间局部坐标后做轴对齐判断。 */
    private fun pointInsideRoom(rect: RectF, rot: Float, x: Float, y: Float): Boolean {
        val img = screenToImage(x, y)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val dx = img[0] - cx
        val dy = img[1] - cy
        val rad = Math.toRadians((-rot).toDouble())
        val lx = (dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat()
        val ly = (dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat()
        return Math.abs(lx) <= rect.width() / 2f && Math.abs(ly) <= rect.height() / 2f
    }

    /** 命中检测：屏幕(x,y)命中的最上层房间下标（支持旋转房间），未命中返回 -1。 */
    private fun hitRoomIndex(x: Float, y: Float): Int {
        for (i in roomRects.indices.reversed()) {
            if (pointInsideRoom(roomRects[i], roomRotations[i], x, y)) return i
        }
        return -1
    }

    /** 命中检测：屏幕点是否落在房间某个角（旋转后位置）的编辑句柄热区，返回 0..3（-1 未命中）。 */
    private fun cornerHitIndex(corners: FloatArray, x: Float, y: Float): Int {
        val hit = HANDLE_HIT_DP * density / 2f
        for (c in 0 until 4) {
            if (Math.abs(x - corners[c * 2]) <= hit && Math.abs(y - corners[c * 2 + 1]) <= hit) return c
        }
        return -1
    }

    /** 命中检测：屏幕点是否落在房间顶部旋转手柄上。 */
    private fun rotateHandleHit(hx: Float, hy: Float, x: Float, y: Float): Boolean {
        val hit = ROTATE_HANDLE_HIT_DP * density
        val dx = x - hx
        val dy = y - hy
        return dx * dx + dy * dy <= hit * hit
    }

    /** 退出房间编辑模式（并取消待触发的长按）。 */
    private fun exitRoomEdit() {
        cancelRoomLongPress()
        if (editingIndex != -1 || roomDragMode != 0) {
            editingIndex = -1
            roomDragMode = 0
            invalidate()
        }
    }

    private fun cancelRoomLongPress() {
        longPressHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 安排长按检测：手指未移动超过阈值且长按时长到点，则命中的房间进入编辑模式。
     * 长按成立后不抬指继续拖动即平移该房间。
     */
    private fun scheduleLongPress(x: Float, y: Float) {
        cancelRoomLongPress()
        longPressDownX = x
        longPressDownY = y
        longPressHandler.postDelayed({
            if (!dragging || editingIndex != -1) return@postDelayed
            if (Math.abs(lastX - downX) > touchSlop || Math.abs(lastY - downY) > touchSlop) {
                return@postDelayed
            }
            val idx = hitRoomIndex(longPressDownX, longPressDownY)
            if (idx >= 0) {
                editingIndex = idx
                roomDragMode = 1
                dragOriginal.set(roomRects[idx])
                val down = screenToImage(longPressDownX, longPressDownY)
                dragDownImageX = down[0]
                dragDownImageY = down[1]
                invalidate()
            }
        }, ViewConfiguration.getLongPressTimeout().toLong())
    }

    /**
     * DOWN 时刻的房间编辑分派。返回 true 表示本次按下已被房间编辑消费
     * （拖动句柄缩放/房间平移），false 表示继续走地图手势（拖动/双指）。
     */
    private fun onActionDownForRoom(x: Float, y: Float): Boolean {
        cancelRoomLongPress()
        if (editingIndex >= 0) {
            val rect = roomRects[editingIndex]
            val rot = roomRotations[editingIndex]
            val base = Matrix(imageView.imageMatrix)
            val corners = FloatArray(8)
            base.mapPoints(corners, rotatedCornersImage(rect, rot))
            // 房间中心屏幕坐标（旋转/缩放都围绕该中心）
            val centerPt = floatArrayOf(rect.centerX(), rect.centerY())
            base.mapPoints(centerPt)
            // 顶部旋转手柄屏幕坐标（房间局部上沿外侧固定屏幕间距处，随房间旋转）
            val gapImg = ROTATE_HANDLE_GAP_DP * density / displayScale()
            val anchor = rotateImagePoint(
                rect.centerX(), rect.centerY(), rot,
                rect.centerX(), rect.top - gapImg)
            base.mapPoints(anchor)

            // 1) 旋转手柄 → 旋转（MOVE 时按绝对角解析，这里无需记录起始角）
            if (rotateHandleHit(anchor[0], anchor[1], x, y)) {
                roomDragMode = 3
                return true
            }
            // 2) 四角直角句柄 → 缩放（对角固定）
            val corner = cornerHitIndex(corners, x, y)
            if (corner >= 0) {
                roomDragMode = 2
                dragCorner = corner
                dragOriginal.set(roomRects[editingIndex])
                val opposite = (corner + 2) % 4
                dragFixedX = if (opposite == 0 || opposite == 3) dragOriginal.left else dragOriginal.right
                dragFixedY = if (opposite == 0 || opposite == 1) dragOriginal.top else dragOriginal.bottom
                val down = screenToImage(x, y)
                dragDownImageX = down[0]
                dragDownImageY = down[1]
                return true
            }
            // 3) 按住当前房间内部 → 平移
            if (pointInsideRoom(rect, rot, x, y)) {
                roomDragMode = 1
                dragOriginal.set(roomRects[editingIndex])
                val down = screenToImage(x, y)
                dragDownImageX = down[0]
                dragDownImageY = down[1]
                return true
            }
            val other = hitRoomIndex(x, y)
            if (other >= 0) {
                // 点中其它房间：切换编辑目标并直接进入平移待命
                editingIndex = other
                roomDragMode = 1
                dragOriginal.set(roomRects[other])
                val down = screenToImage(x, y)
                dragDownImageX = down[0]
                dragDownImageY = down[1]
                invalidate()
                return true
            }
            // 点空白：退出编辑，本次手势退化为地图拖动
            exitRoomEdit()
            return false
        }
        // 未在编辑：挂长按检测（长按房间才进入编辑），普通拖动仍为平移地图
        scheduleLongPress(x, y)
        return false
    }

    /** MOVE 时刻的房间编辑处理。返回 true 表示已被编辑手势消费。 */
    private fun onRoomMove(x: Float, y: Float): Boolean {
        if (roomDragMode == 0 || editingIndex !in roomRects.indices) return false
        val cur = screenToImage(x, y)
        val rect = roomRects[editingIndex]
        when (roomDragMode) {
            1 -> {
                // 平移：整体 offset，限制在地图图像范围内
                val dx = cur[0] - dragDownImageX
                val dy = cur[1] - dragDownImageY
                val w = dragOriginal.width()
                val h = dragOriginal.height()
                var l = dragOriginal.left + dx
                var t = dragOriginal.top + dy
                val iw = imageWidth()
                val ih = imageHeight()
                l = l.coerceIn(0f, (iw - w).coerceAtLeast(0f))
                t = t.coerceIn(0f, (ih - h).coerceAtLeast(0f))
                rect.set(l, t, l + w, t + h)
            }
            2 -> {
                val rot = roomRotations[editingIndex]
                if (rot != 0f) {
                    // 旋转房间的缩放：绕拖动开始时的房间中心缩放（旋转对象若仍用对角固定
                    // 语义，固定角会在屏幕上漂移），把手指相对中心反旋到房间局部坐标，
                    // 新宽/高 = 手指局部位移的 2 倍，最小尺寸限制不变。
                    val cx = dragOriginal.centerX()
                    val cy = dragOriginal.centerY()
                    val dx = cur[0] - cx
                    val dy = cur[1] - cy
                    val rad = Math.toRadians((-rot).toDouble())
                    val cos = Math.cos(rad)
                    val sin = Math.sin(rad)
                    val lx = (dx * cos - dy * sin).toFloat()
                    val ly = (dx * sin + dy * cos).toFloat()
                    val minW = ROOM_MIN_WIDTH_DP * density / displayScale()
                    val minH = ROOM_MIN_HEIGHT_DP * density / displayScale()
                    val w = (Math.abs(lx) * 2f).coerceAtLeast(minW)
                    val h = (Math.abs(ly) * 2f).coerceAtLeast(minH)
                    rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                } else {
                    // 缩放：被拖角跟随手指，对角固定，限制最小尺寸与地图边界
                    val minW = ROOM_MIN_WIDTH_DP * density / displayScale()
                    val minH = ROOM_MIN_HEIGHT_DP * density / displayScale()
                    val fx = dragFixedX
                    val fy = dragFixedY
                    val iw = imageWidth()
                    val ih = imageHeight()
                    var l = rect.left
                    var t = rect.top
                    var r = rect.right
                    var b = rect.bottom
                    when (dragCorner) {
                        0 -> { l = cur[0].coerceIn(0f, fx - minW); t = cur[1].coerceIn(0f, fy - minH) }
                        1 -> { r = cur[0].coerceIn(fx + minW, iw); t = cur[1].coerceIn(0f, fy - minH) }
                        2 -> { r = cur[0].coerceIn(fx + minW, iw); b = cur[1].coerceIn(fy + minH, ih) }
                        3 -> { l = cur[0].coerceIn(0f, fx - minW); b = cur[1].coerceIn(fy + minH, ih) }
                    }
                    rect.set(l, t, r, b)
                }
            }
            3 -> {
                // 旋转（绝对角）：手柄位于房间局部上沿外侧（局部方向角 -90°），
                // 把手指映射回图像坐标、求其相对房间中心的方位角，一次解出房间旋转角，
                // 使手柄始终跟随手指。旋转轴恒为房间图像中心，无增量累积误差。
                val dx = cur[0] - rect.centerX()
                val dy = cur[1] - rect.centerY()
                var rot = Math.toDegrees(
                    Math.atan2(dy.toDouble(), dx.toDouble())
                ).toFloat() + 90f
                rot %= 360f
                if (rot < 0f) rot += 360f
                roomRotations[editingIndex] = rot
            }
        }
        invalidate()
        return true
    }

    /**
     * 保存用户 DIY 结果：
     * - 旋转角度 + 缩放倍数存入 SharedPreferences("diy_map")
     * - 地图显示内容（应用旋转/缩放，不含指北针）保存为沙盒目录下 new.png
     * @return 保存成功返回输出文件，失败返回 null
     */
    fun saveDiyMap(reportId: String, path: String, index: Int): File? {
        val bitmap = exportMapBitmap() ?: return null
        return saveBitmapAndState(reportId, path, index, bitmap)
    }

    /**
     * 所见即所得版保存：导出当前视图可见区域（超出视图边界的部分直接裁掉），
     * 输出固定为视图尺寸，比 [saveDiyMap] 省内存、省写盘时间。
     */
    fun saveDiyMapViewport(reportId: String, path: String, index: Int): File? {
        val bitmap = exportMapBitmapViewport() ?: return null
        val output = saveBitmapAndState(reportId, path, index, bitmap)
        // DIY 房间单独存一张透明底图层（与地图原图同尺寸），供外部 getDiyBitmap 叠加；
        // 无房间时删除旧文件，避免残留上一次的房间层
        saveRoomsLayerIfAny(File(path).parentFile)
        return output
    }

    /** 把房间图层写入 [dir]/rooms_overlay.png（透明底 + 房间色块）；无房间时清理旧文件。 */
    private fun saveRoomsLayerIfAny(dir: File?) {
        val target = if (dir != null) File(dir, ROOMS_OVERLAY_NAME) else null
        val overlay = exportRoomsOverlay()
        if (overlay == null) {
            if (target != null && target.exists()) target.delete()
            return
        }
        try {
            target?.parentFile?.mkdirs()
            if (target != null) {
                FileOutputStream(target).use { out ->
                    overlay.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        } catch (e: Exception) {
            // 房间层保存失败不影响主图落盘
        } finally {
            overlay.recycle()
        }
    }

    /**
     * 导出房间透明层位图：尺寸与地图原图（drawable）一致，透明底，仅房间色块
     * （无编辑高亮/白边），可直接与其它 overlay 图层按 0,0 对齐叠加。
     */
    private fun exportRoomsOverlay(): Bitmap? {
        if (roomRects.isEmpty()) return null
        val drawable = imageView.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        // 房间矩形/旋转角以地图图像坐标存储，identity 矩阵直接落到画布；
        // 绘制期间临时退出编辑态，避免把编辑白边一并画进图层
        val editing = editingIndex
        editingIndex = -1
        drawRooms(canvas, Matrix())
        editingIndex = editing
        return bitmap
    }

    /** 写 PNG 落盘 + 记录视角状态到 SharedPreferences，两种导出共用。 */
    private fun saveBitmapAndState(
        reportId: String,
        path: String,
        index: Int,
        bitmap: Bitmap
    ): File? {
        val output = File(path)
        try {
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            bitmap.recycle()
            return null
        }
        bitmap.recycle()

        val rotationDegrees = imageView.rotationDegrees
        val currentZoomFactor = imageView.currentZoomFactor
        // 视角 + 房间列表一起持久化：";" 分隔房间段，每段 L,T,R,B,旋转角；
        // index 仍在字符串最前，外部 parseInt(savedState.split(",")[0]) 不受影响
        val state = StringBuilder("$index,$rotationDegrees,$currentZoomFactor")
        for (i in roomRects.indices) {
            val r = roomRects[i]
            val rot = if (i < roomRotations.size) roomRotations[i] else 0f
            state.append(';')
                .append(r.left).append(',').append(r.top).append(',')
                .append(r.right).append(',').append(r.bottom).append(',')
                .append(rot)
        }
        context.getSharedPreferences("kk.slam", Context.MODE_PRIVATE).edit()
            .putString(reportId, state.toString())
            .apply()
        return output
    }

    /** 导出当前地图内容位图：应用缩放 + 旋转，白底，不含指北针叠加层。 */
    private fun exportMapBitmap(): Bitmap? {
        val drawable = imageView.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null

        // 用当前 imageMatrix 映射原始图像边界，得到旋转/缩放后的完整范围
        val bounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        val matrix = Matrix(imageView.imageMatrix)
        matrix.mapRect(bounds)

        val width = bounds.width().toInt().coerceAtLeast(1)
        val height = bounds.height().toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // 绘制矩阵：原始 imageMatrix + 平移到画布原点（去掉左上角偏移）
        val drawMatrix = Matrix(matrix)
        drawMatrix.postTranslate(-bounds.left, -bounds.top)
        if (drawable is BitmapDrawable) {
            canvas.drawBitmap(drawable.bitmap, drawMatrix, null)
        } else {
            val tmp = Bitmap.createBitmap(
                drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            drawable.draw(Canvas(tmp))
            canvas.drawBitmap(tmp, drawMatrix, null)
            tmp.recycle()
        }
//        drawRooms(canvas, drawMatrix)
        return bitmap
    }

    /**
     * 所见即所得导出（激进版）：以当前视图尺寸为画布、直接按 imageMatrix 绘制，
     * 超出视图边界的图像部分自动裁掉——不做外接矩形适配、不做边界约束，
     * 屏幕上看到什么就导出什么（不含指北针叠加层）。
     */
    private fun exportMapBitmapViewport(): Bitmap? {
        val drawable = imageView.drawable ?: return null
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // 直接使用当前 imageMatrix（已含适配/缩放/旋转/平移），无需外接矩形偏移
        val drawMatrix = Matrix(imageView.imageMatrix)
        if (drawable is BitmapDrawable) {
            canvas.drawBitmap(drawable.bitmap, drawMatrix, null)
        } else {
            val tmp = Bitmap.createBitmap(
                drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            drawable.draw(Canvas(tmp))
            canvas.drawBitmap(tmp, drawMatrix, null)
            tmp.recycle()
        }
        drawRooms(canvas, drawMatrix)
        return bitmap
    }

    /**
     * 加载 DIY 结果：先让内部 resetZoom 完成适配居中，若 savedState 记录了
     * 旋转角度与缩放倍数（格式 "index,rotation,zoom"）则随后恢复该视角；
     * 否则按默认（适配原始比例、角度归零）显示。
     */
    fun loadDiyMap(bitmap: Bitmap, savedState: String) {
        var rotation = 0f
        var zoom = 1f
        // 先清空再恢复房间列表，避免重复加载导致房间叠加/重复
        roomRects.clear()
        roomRotations.clear()
        if (!TextUtils.isEmpty(savedState)) {
            // 视角与房间同存：第一段 "index,rotation,zoom"，其后 ";" 分隔的房间段
            val segments = savedState.split(";")
            val view = segments[0].split(",")
            if (width > 0 && view.size >= 3) {
                rotation = view[1].toFloat()
                zoom = view[2].toFloat()
            }
            // 房间段格式 L,T,R,B,旋转角（地图图像坐标），坏数据跳过不影响加载
            for (i in 1 until segments.size) {
                try {
                    val p = segments[i].split(",")
                    if (p.size >= 5) {
                        roomRects.add(RectF(
                            p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                        roomRotations.add(p[4].toFloat())
                    }
                } catch (e: Exception) {
                    // 忽略异常房间段
                }
            }
        }
        imageView.setImageBitmap(bitmap)
        if (rotation != 0f || zoom != 1f) {
            // 等待 imageView 内部 post 的 resetZoom 完成后，再以视图中心恢复视角
            post {
                imageView.rotateBy(rotation, width / 2f, height / 2f)
                imageView.scaleBy(zoom, width / 2f, height / 2f)
                invalidate()
            }
        }
    }

    /** 当前图像旋转角（顺时针为正，度），指北针据此反向旋转。 */
    val rotationDegrees: Float
        get() = imageView.rotationDegrees

    // ==================== 手势：单指拖动 + 双指缩放/旋转 ====================

    private var dragging = false
    private var multiTouch = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastDegrees = 0f
    private var downX = 0f
    private var downY = 0f

    /** 指北针点击热区（与绘制位置同步）。 */
    private val compassBounds = RectF()
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // 全部触摸由本容器统一处理：从 DOWN 起即拦截所有事件，
    // 避免子 imageView 消费 DOWN 后设置 disallow 标志导致双指手势无法被接管。
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                multiTouch = false
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                onActionDownForRoom(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    // 双指进入地图缩放/旋转手势：退出房间编辑
                    exitRoomEdit()
                    multiTouch = true
                    lastSpan = spacing(event)
                    lastDegrees = angle(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // 房间编辑（平移/缩放）优先
                if (onRoomMove(event.x, event.y)) return true
                if (multiTouch && event.pointerCount >= 2) {
                    val span = spacing(event)
                    val degrees = angle(event)
                    val focusX = (event.getX(0) + event.getX(1)) * 0.5f
                    val focusY = (event.getY(0) + event.getY(1)) * 0.5f
                    if (lastSpan > 0f && span > 0f) {
                        val factor = span / lastSpan
                        val scaled = imageView.scaleBy(factor, focusX, focusY)
                        // 捏/张到缩放边界后缩放不再变化：此时手指角度抖动若继续驱动
                        // rotateBy，地图会在最小/最大缩放处被捏合手势反复带着旋转。
                        // 仅当缩放真实生效、或两指间距基本不变（纯旋转手势）时才允许旋转。
                        if (scaled || Math.abs(factor - 1f) <= 0.02f) {
                            imageView.rotateBy(degrees - lastDegrees, focusX, focusY)
                        }
                    }
                    lastSpan = span
                    lastDegrees = degrees
                    invalidate()
                } else if (dragging) {
                    // 只有位移超过触摸阈值才取消长按：长按时手指的微小抖动
                    // 不能中断长按检测，否则房间长按永远无法进入编辑
                    if (Math.abs(event.x - downX) > touchSlop ||
                        Math.abs(event.y - downY) > touchSlop) {
                        cancelRoomLongPress()
                    }
                    imageView.translateBy(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                    // 拖动只让内部 imageView 重绘了地图位图，必须同时刷新本容器，
                    // 房间/指北针等叠加层才会与地图一起逐帧平移
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    // 双指抬起一指，回到单指拖动：以剩余手指为基准继续拖动
                    val idx = if (event.actionIndex == 0) 1 else 0
                    multiTouch = false
                    dragging = true
                    lastX = event.getX(idx)
                    lastY = event.getY(idx)
                } else {
                    multiTouch = false
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelRoomLongPress()
                // 结束一次房间拖动（平移/缩放），保持编辑模式（句柄继续显示）
                roomDragMode = 0
                // 轻点指北针区域：视角复位 + 把图内北摆到右侧（指北针水平、左南右北）；
                // 指北针不显示时该热区失效
                val dx = event.x - downX
                val dy = event.y - downY
                if (compassVisible && dx * dx + dy * dy <= touchSlop * touchSlop &&
                    compassBounds.contains(event.x, event.y)) {
                    resetViewWithNorthOnRight()
                }
                dragging = false
                multiTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // 收尾刷新一次，保证最后一次拖动后的叠加层与地图位置一致
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelRoomLongPress()
                roomDragMode = 0
                dragging = false
                multiTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    // ==================== 右上角指北针 ====================

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // 在子视图（imageView）绘制完成之后再画，避免被其白色背景覆盖
        drawRooms(canvas, Matrix(imageView.imageMatrix))
        drawRoomEditHandles(canvas, Matrix(imageView.imageMatrix))
        drawCompass(canvas)
    }

    /**
     * 把已添加的房间（图像坐标）按指定矩阵（屏幕/导出画布）变换后绘制，
     * 支持每个房间自身的旋转角：按 4 个角点绘制四边形（而非 AABB）。
     */
    private fun drawRooms(canvas: Canvas, matrix: Matrix) {
        if (roomRects.isEmpty()) return
        for ((i, rect) in roomRects.withIndex()) {
            val path = Path()
            val c = FloatArray(8)
            matrix.mapPoints(c, rotatedCornersImage(rect, roomRotations[i]))
            path.moveTo(c[0], c[1])
            path.lineTo(c[2], c[3])
            path.lineTo(c[4], c[5])
            path.lineTo(c[6], c[7])
            path.close()
            canvas.drawPath(path, roomPaint)
            if (i == editingIndex) {
                // 编辑中的房间描一圈白边高亮
                canvas.drawPath(path, roomSelectPaint)
            }
        }
    }

    /**
     * 编辑模式下绘制：四角直角句柄（缩放）+ 顶部旋转手柄（白圆 + 环形箭头），
     * 屏幕尺寸固定、不随地图缩放，均跟随房间旋转。
     */
    private fun drawRoomEditHandles(canvas: Canvas, matrix: Matrix) {
        if (editingIndex !in roomRects.indices) return
        val rect = roomRects[editingIndex]
        val rot = roomRotations[editingIndex]
        val size = HANDLE_SIZE_DP * density
        val c = FloatArray(8)
        matrix.mapPoints(c, rotatedCornersImage(rect, rot))
        // 四角直角句柄
        for (i in 0 until 4) {
            val block = RectF(
                c[i * 2] - size / 2f, c[i * 2 + 1] - size / 2f,
                c[i * 2] + size / 2f, c[i * 2 + 1] + size / 2f)
            canvas.drawRect(block, handleFillPaint)
            canvas.drawRect(block, handleStrokePaint)
        }
        // 顶部旋转手柄
        drawRotateHandle(canvas, matrix, rect, rot)
    }

    /** 绘制顶部旋转手柄：房间局部上沿外侧固定间距处，白圆 + 蓝色环形箭头（缺角 + 双箭头）。 */
    private fun drawRotateHandle(canvas: Canvas, m: Matrix, rect: RectF, rot: Float) {
        val gapImg = ROTATE_HANDLE_GAP_DP * density / displayScale()
        val anchor = rotateImagePoint(
            rect.centerX(), rect.centerY(), rot,
            rect.centerX(), rect.top - gapImg)
        m.mapPoints(anchor)
        val hx = anchor[0]
        val hy = anchor[1]
        val r = ROTATE_HANDLE_RADIUS_DP * density
        val ringW = 3f * density
        canvas.drawCircle(hx, hy, r, rotateFillPaint)
        // 蓝环留约 60° 缺口，两端画实心三角箭头 → 顺时针旋转暗示
        val ringRect = RectF(hx - r + ringW / 2f, hy - r + ringW / 2f,
            hx + r - ringW / 2f, hy + r - ringW / 2f)
        canvas.drawArc(ringRect, -60f, 300f, false, rotateRingPaint)
        val arrowSize = 5f * density
        val armR = r - ringW
        fun tipAt(deg: Float) {
            val rad = Math.toRadians(deg.toDouble())
            val dirX = Math.cos(rad).toFloat()
            val dirY = Math.sin(rad).toFloat()
            val px = hx + dirX * armR
            val py = hy + dirY * armR
            val tx = -dirY
            val ty = dirX
            val path = Path()
            path.moveTo(px + dirX * arrowSize * 1.6f, py + dirY * arrowSize * 1.6f)
            path.lineTo(px - dirX * arrowSize * 0.5f + tx * arrowSize * 1.1f,
                py - dirY * arrowSize * 0.5f + ty * arrowSize * 1.1f)
            path.lineTo(px - dirX * arrowSize * 0.5f - tx * arrowSize * 1.1f,
                py - dirY * arrowSize * 0.5f - ty * arrowSize * 1.1f)
            path.close()
            canvas.drawPath(path, rotateArrowPaint)
        }
        tipAt(20f)
        tipAt(-75f)
    }

    /**
     * 右上角指北针：复刻 KaerMapView 造型——透明底盘 + 深灰细环 + 红北箭头/蓝南箭头
     * （尖端 + 尾翼分叉 + 中央凹角），随图像旋转角同步转动，指示地图中的北向。
     */
    private fun drawCompass(canvas: Canvas) {
        if (!compassVisible) return
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val radius = compassRadius
        val margin = 14f * density
        val cx = width - margin - radius
        val cy = margin + radius

        // 点击热区与绘制位置同步（供 ACTION_UP 检测轻点复位）
        compassBounds.set(
            cx - radius, cy - radius,
            cx + radius, cy + radius
        )

        // 透明底盘 + 深灰环（#263238，同 ic_map_compass）
        compassBgPaint.color = Color.TRANSPARENT
        canvas.drawCircle(cx, cy, radius, compassBgPaint)
        compassRingPaint.color = Color.rgb(38, 50, 56)
        compassRingPaint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cy, radius, compassRingPaint)

        canvas.save()
        // 红针指向「图中的北」：在原跟随地图旋转角的基础上，叠加图内北相对图像上方的偏置
        canvas.rotate(mapNorthRotationDegrees - imageView.rotationDegrees, cx, cy)

        val u = radius / 28f

        // 北箭头（红 #D32F2F）：尖端朝地图北
        val north = Path().apply {
            moveTo(cx, cy - 24f * u)
            lineTo(cx + 8f * u, cy - u)
            lineTo(cx, cy - 5f * u)
            lineTo(cx - 8f * u, cy - u)
            close()
        }
        compassNeedlePaint.color = Color.rgb(211, 47, 47)
        compassNeedlePaint.style = Paint.Style.FILL
        canvas.drawPath(north, compassNeedlePaint)

        // 南箭头（蓝 #1976D2）：尖端朝地图南
        val south = Path().apply {
            moveTo(cx, cy + 24f * u)
            lineTo(cx + 8f * u, cy + u)
            lineTo(cx, cy + 5f * u)
            lineTo(cx - 8f * u, cy + u)
            close()
        }
        compassNeedleTailPaint.color = Color.rgb(25, 118, 210)
        compassNeedleTailPaint.style = Paint.Style.FILL
        canvas.drawPath(south, compassNeedleTailPaint)

        canvas.restore()
    }

    private fun spacing(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun angle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private companion object {
        /** DIY 房间单独落盘的图层文件名（与其它 *_overlay.png 同目录，透明底 + 房间色块）。 */
        const val ROOMS_OVERLAY_NAME = "rooms_overlay.png"

        /** 房间在屏幕上的固定宽度（dp）：任意缩放下新加房间的屏幕观感一致。 */
        const val ROOM_SCREEN_WIDTH_DP = 90f

        /** 房间在屏幕上的固定高度（dp）。 */
        const val ROOM_SCREEN_HEIGHT_DP = 56f

        /** 缩放编辑时房间可缩到的最小屏幕宽度（dp）。 */
        const val ROOM_MIN_WIDTH_DP = 40f

        /** 缩放编辑时房间可缩到的最小屏幕高度（dp）。 */
        const val ROOM_MIN_HEIGHT_DP = 32f

        /** 编辑模式下四角句柄的绘制尺寸（dp）。 */
        const val HANDLE_SIZE_DP = 14f

        /** 编辑模式下四角句柄的可点击命中尺寸（dp）。 */
        const val HANDLE_HIT_DP = 28f

        /** 顶部旋转手柄的可点击命中半径（dp）。 */
        const val ROTATE_HANDLE_HIT_DP = 30f

        /** 旋转手柄中心到房间局部上沿的距离（dp，屏幕尺寸，不随地图缩放）。 */
        const val ROTATE_HANDLE_GAP_DP = 30f

        /** 旋转手柄的绘制半径（dp）。 */
        const val ROTATE_HANDLE_RADIUS_DP = 13f
    }
}
