package com.cartographer.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.jvm.JvmOverloads
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class KaerMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class OrientationMode {
        FOLLOW_ROBOT,
        MAGNETIC_NORTH_OVERVIEW
    }

    private enum class MapMode {
        LIVE,
        PREVIEW
    }

    private val trajectory = ArrayDeque<PointF>()
    private val optimizedTrajectory = ArrayList<PointF>()
    private val liveScanPoints = ArrayList<PointF>(720)
    private var liveScanTimestampNs = 0L
    private var liveScanVisible = true
    private val trajectoryPath = Path()
    private var latestPose = Pose2D(0.0, 0.0, 0.0)
    private var orientationMode = OrientationMode.FOLLOW_ROBOT
    private var overviewCenterX = 0f
    private var overviewCenterY = 0f
    private var magneticNorthYawRadians = 0f
    private var mapMeasurement: MapMeasurement? = null
    private val submaps = LinkedHashMap<String, SubmapTexture>()
    private val liveSubmapOverlays = LinkedHashMap<String, LiveSubmapOverlay>()
    private var fusionExecutor: ExecutorService? = newFusionExecutor()
    private var fusedMap: FusedMapRenderer.Result? = null
    private var fusionGeneration = 0L
    private var fusionRunning = false
    private var fusionPending = false

    private fun newFusionExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FusedMapRenderer").apply { isDaemon = true }
        }

    /** 获取可用渲染线程池；若在 detach 时被关闭，则重建以支持 View 复用重新 attach。 */
    private fun obtainFusionExecutor(): ExecutorService {
        var executor = fusionExecutor
        if (executor == null || executor.isShutdown) {
            executor = newFusionExecutor()
            fusionExecutor = executor
        }
        return executor
    }

    private val submapMatrix = Matrix()
    private val submapMatrixValues = FloatArray(9)
    private val scratchPoint = PointF()
    private var userZoom = 1.2f
    private var previewZoom = 0f
    private var panOffsetX = 0f
    private var panOffsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userZoom = (userZoom * detector.scaleFactor).coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
                clampPanOffset()
                invalidate()
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
    )

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
//        color = Color.rgb(18, 22, 28)
        color = Color.parseColor("#70B3FE")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 56, 66)
        strokeWidth = 1f
    }
    private val submapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 255
    }
    private val liveScanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
//        color = Color.rgb(100, 235, 255)
        color = Color.parseColor("#03dac5")
        alpha = 190
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 203, 92)
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val robotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val robotBitmap: Bitmap by lazy {
        val source = BitmapFactory.decodeResource(resources, R.drawable.robot)
            ?: return@lazy Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val target = (32 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val maxSide = maxOf(source.width, source.height)
        if (maxSide == target) {
            source
        } else {
            val ratio = target.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            ).also { scaled ->
                if (scaled !== source) source.recycle()
            }
        }
    }
    private val measurementPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 90)
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val measurementGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 214, 90)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val measurementTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            10f,//13f
            resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val measurementTextBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 28, 33, 40)
        style = Paint.Style.FILL
    }

    // ---- 预览模式指北针 ----
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
    private val compassTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }

    // ---- 测试点标注 ----
    private val markers = ArrayList<MapMarker>()
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 测试点图标（小圆点）与路由点图标（路由器），懒加载并缩放到目标尺寸。 */
    private val dotIcon: Bitmap? by lazy {
        loadMarkerIcon(R.drawable.icon_dot, 24f)
    }
    private val routerIcon: Bitmap? by lazy {
        loadMarkerIcon(R.drawable.icon_router, 24f)
    }

    private fun loadMarkerIcon(resId: Int, sizeDp: Float): Bitmap? {
        val drawable = context.getDrawable(resId) ?: return null
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val target = (sizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val ratio = target.toFloat() / maxOf(width, height)
        val scaled = Bitmap.createBitmap(
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(scaled)
        drawable.setBounds(0, 0, scaled.width, scaled.height)
        drawable.draw(canvas)
        return scaled
    }

    /** 标注点类型：DOT=测试点，ROUTER=路由点。 */
    enum class MarkerType { DOT, ROUTER }

    /** 测试点标注：世界坐标 + 文字 + 类型。绘制在旋转坐标系中，随地图平移/缩放/旋转。 */
    data class MapMarker @JvmOverloads constructor(
        val worldX: Float,
        val worldY: Float,
        val label: String,
        val type: MarkerType = MarkerType.DOT
    )

    fun setLiveScan(scan: LidarScan, pose: Pose2D?) {
        if (scan.timestampNs <= liveScanTimestampNs) return
        liveScanTimestampNs = scan.timestampNs
        val p = pose ?: latestPose
        latestPose = p

        val poseX = p.x.toFloat()
        val poseY = p.y.toFloat()
        val pathPoint = PointF(poseX, poseY)
        val lastPathPoint = trajectory.lastOrNull()
        if (lastPathPoint == null ||
            squaredDistance(lastPathPoint, pathPoint) > 0.0025f) {
            trajectory.addLast(pathPoint)
            while (trajectory.size > 2000) trajectory.removeFirst()
        }

        liveScanPoints.clear()
        val count = minOf(scan.ranges.size, scan.anglesDeg.size)
        for (i in 0 until count) {
            val range = scan.ranges[i]
            if (!range.isFinite() || range < 0.05f || range > 8.0f) continue
            val angle = Math.toRadians(scan.anglesDeg[i].toDouble())
            liveScanPoints.add(
                PointF(
                    (range * cos(angle)).toFloat(),
                    (range * sin(angle)).toFloat()
                )
            )
        }

        invalidate()
    }

    fun clearLiveScan() {
        if (liveScanPoints.isEmpty() && liveScanTimestampNs == 0L) return
        liveScanPoints.clear()
        liveScanTimestampNs = 0L
        invalidate()
    }

    fun setLiveScanVisible(visible: Boolean) {
        liveScanVisible = visible
        if (!visible) {
            liveScanPoints.clear()
            liveScanTimestampNs = 0L
        }
        invalidate()
    }

    fun setSubmapTexture(texture: SubmapTexture?) {
        if (texture == null) return
        setSubmapTextures(listOf(texture))
    }

    fun setSubmapTextures(textures: List<SubmapTexture>) {
        if (textures.isEmpty()) {
            // 空列表意味着 native 已无任何 submap（例如 reset 后新轨迹尚未产出）。
            // 必须清掉 View 上残留的旧地图，否则旧图会一直显示直到新 submap 覆盖。
            submaps.clear()
            recycleLiveSubmapOverlays()
            fusionGeneration++
            fusionPending = false
            fusedMap?.bitmap?.recycle()
            fusedMap = null
            lastFinalizedTextures = emptyList()
            invalidate()
            return
        }
        var changed = false
        val changedTextures = ArrayList<SubmapTexture>(textures.size)
        for (texture in textures) {
            val current = submaps[texture.key]
            if (current != null && current.version == texture.version &&
                current.samePoseAs(texture)) {
                continue
            }
            submaps[texture.key] = texture
            changedTextures += texture
            changed = true
        }
        if (!changed) return
        // A periodic pose-graph refresh can move many historical submaps at
        // once. Only the newest two can still receive scan data, so limiting
        // the immediate layer avoids allocating dozens of Bitmaps on the UI
        // thread while the background fusion already handles the full list.
        val newestTrajectoryId = changedTextures.maxOf { it.trajectoryId }
        val liveTextures = changedTextures
            .asSequence()
            .filter { it.trajectoryId == newestTrajectoryId }
            .sortedByDescending { it.submapIndex }
            .take(2)
            .toList()
        val liveKeys = liveTextures.mapTo(HashSet()) { it.key }
        val staleOverlays = liveSubmapOverlays.entries.iterator()
        while (staleOverlays.hasNext()) {
            val entry = staleOverlays.next()
            if (entry.key !in liveKeys) {
                entry.value.bitmap.recycle()
                staleOverlays.remove()
            }
        }
        for (texture in liveTextures) {
            val bitmap = Bitmap.createBitmap(
                texture.pixels,
                texture.width,
                texture.height,
                Bitmap.Config.ARGB_8888
            )
            liveSubmapOverlays.put(
                texture.key,
                LiveSubmapOverlay(texture, bitmap)
            )?.bitmap?.recycle()
        }
        requestFusedMapRender()
        // The active texture is visible immediately. The more expensive global
        // confidence fusion continues on the renderer thread and replaces it.
        invalidate()
    }

    fun setFinalizedSubmapTextures(textures: List<SubmapTexture>) {
        if (textures.isEmpty()) return
        recycleLiveSubmapOverlays()
        fusionGeneration++
        fusionPending = false
        lastFinalizedTextures = textures.toList()
        val generation = fusionGeneration
        val snapshot = lastFinalizedTextures
        val useDarkWalls = darkWalls
        obtainFusionExecutor().execute {
            val rendered = try {
                FusedMapRenderer.render(snapshot, finalized = true)
            } catch (_: Throwable) {
                null
            }
            post {
                if (generation == fusionGeneration && rendered != null) {
                    val previous = fusedMap
                    fusedMap = rendered
                    if (previous?.bitmap !== rendered.bitmap) {
                        previous?.bitmap?.recycle()
                    }
                    notifyContentLoadedIfReady()
                    // 预览模式下首次拿到完整地图后自动居中适配
                    if (mapMode == MapMode.PREVIEW && !previewInitialized) {
                        previewInitialized = true
                        fitMapToView()
                    } else {
                        invalidate()
                    }
                } else {
                    rendered?.bitmap?.recycle()
                }
            }
        }
    }

    fun setOptimizedTrajectory(points: List<PointF>) {
        optimizedTrajectory.clear()
        optimizedTrajectory.addAll(points)
        invalidate()
    }

    fun setMapMeasurement(measurement: MapMeasurement?) {
        mapMeasurement = measurement
        invalidate()
    }

    /** 在地图上（世界坐标处）标注一个带 icon 和文字的测试点（DOT 类型）。 */
    fun addMarker(worldX: Float, worldY: Float, label: String) {
        addMarker(worldX, worldY, label, MarkerType.DOT)
    }

    /** 在地图上（世界坐标处）标注一个指定类型的点（DOT=测试点，ROUTER=路由点）。 */
    fun addMarker(worldX: Float, worldY: Float, label: String, type: MarkerType) {
        markers.add(MapMarker(worldX, worldY, label, type))
        invalidate()
    }

    /** 清除全部测试点标注。 */
    fun clearMarkers() {
        if (markers.isEmpty()) return
        markers.clear()
        invalidate()
    }

    /** 导出全部标注点（世界坐标 + 文字），用于持久化。 */
    fun exportMarkers(): List<MapMarker> = markers.toList()

    /** 恢复持久化的标注点。 */
    fun importMarkers(restored: List<MapMarker>) {
        markers.clear()
        markers.addAll(restored)
        invalidate()
    }

    /**
     * 将当前渲染的地图内容（地图栅格、点云、轨迹、标注点；不含机器人与指北针等
     *  UI 元素）导出为 PNG 文件。不通过截屏，而是在后台线程重新渲染数据层，
     *  避免把指北针等叠加层带入图片。
     *  输出固定尺寸 1280×800，缩放取预览模式下的默认缩放（fitMapToView 设定的值），
     *  地图居中、正北朝上。回调在主线程执行。
     */
    fun saveMapSnapshotToPng(outputFile: File, callback: (Boolean) -> Unit) {
        val snapshotW = 1280
        val snapshotH = 800
        val fused = fusedMap
        if (fused == null || fused.bitmap.isRecycled || fused.resolutionMetersPerPixel <= 0f) {
            callback(false)
            return
        }
        // 优先用地图内容边界（非空区域）做适配，更饱满；内容为空时回退到整张位图范围
        val hasContent = fused.contentMaxX > fused.contentMinX && fused.contentMaxY > fused.contentMinY
        val worldMinX = if (hasContent) fused.contentMinX else fused.worldMinX
        val worldMaxX = if (hasContent) fused.contentMaxX
                        else fused.worldMinX + fused.bitmap.width * fused.resolutionMetersPerPixel
        val worldMinY = if (hasContent) fused.contentMinY
                        else fused.worldMaxY - fused.bitmap.height * fused.resolutionMetersPerPixel
        val worldMaxY = if (hasContent) fused.contentMaxY else fused.worldMaxY
        val spanX = (worldMaxX - worldMinX).coerceAtLeast(0.01f)
        val spanY = (worldMaxY - worldMinY).coerceAtLeast(0.01f)

        val poseX = currentReferenceX()
        val poseY = currentReferenceY()
        val centerX = snapshotW / 2f
        val centerY = snapshotH / 2f
        val rotation = currentMapRotationDegrees()
        // 旋转后地图在画面上的外接框尺寸：|W·cosθ|+|H·sinθ| × |W·sinθ|+|H·cosθ|
        val cosR = abs(cos(Math.toRadians(rotation.toDouble())).toFloat())
        val sinR = abs(sin(Math.toRadians(rotation.toDouble())).toFloat())
        val rotatedSpanX = spanX * cosR + spanY * sinR
        val rotatedSpanY = spanX * sinR + spanY * cosR
        // 完整适配缩放：按旋转后的外接框留少量边距，保证任意旋转角下整图放得下且饱满
        val paddingPx = 24f
        val fitScale = min(
            (snapshotW - paddingPx * 2f) / rotatedSpanX,
            (snapshotH - paddingPx * 2f) / rotatedSpanY
        )
        // 旋转角下外接框四角是空白（45° 时最大），按 |sin(2θ)| 轻微放大填补空白，
        // 0°/90°/180° 时外接框与实体重合不放大，因此不会裁到地图实体
        val cornerSlack = abs(sin(Math.toRadians(2 * rotation.toDouble()))).toFloat()
        val scale = fitScale * (1f + 0.06f * cornerSlack)

        val background = if (mapMode == MapMode.PREVIEW) Color.WHITE else LIVE_BACKGROUND_COLOR
        // UI 线程捕获全部数据快照，后台线程只读快照，避免并发修改
        val overlays = ArrayList(liveSubmapOverlays.values)
        val markersSnapshot = ArrayList(markers)
        val trajectorySnapshot = if (optimizedTrajectory.size > 1) {
            ArrayList(optimizedTrajectory)
        } else {
            ArrayList(trajectory)
        }

        obtainFusionExecutor().execute {
            var ok = false
            val bitmap = Bitmap.createBitmap(snapshotW, snapshotH, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(background)
                canvas.save()
                canvas.scale(MAP_MIRROR_X, 1f, centerX, centerY)
                canvas.rotate(rotation, centerX, centerY)
                drawSubmaps(canvas, centerX, centerY, scale, fused)
                drawLiveSubmapOverlays(canvas, centerX, centerY, scale, overlays)
                drawTrajectorySnapshot(canvas, centerX, centerY, scale, trajectorySnapshot, poseX, poseY)
                drawMarkersList(canvas, centerX, centerY, scale, markersSnapshot, poseX, poseY, rotation)
                canvas.restore()
                ok = writePng(bitmap, outputFile)
            } catch (t: Throwable) {
                Log.e("LidarMapView", "保存地图快照失败", t)
            } finally {
                bitmap.recycle()
            }
            val success = ok
            post { callback(success) }
        }
    }

    /** 用给定快照数据绘制轨迹折线。 */
    private fun drawTrajectorySnapshot(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        scale: Float,
        points: List<PointF>,
        poseX: Float,
        poseY: Float
    ) {
        if (points.size <= 1) return
        val path = Path()
        var first = true
        for (point in points) {
            val x = centerX + (point.x - poseX) * scale
            val y = centerY - (point.y - poseY) * scale
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, pathPaint)
    }

    /** 将 Bitmap 编码为 PNG 写入文件。 */
    private fun writePng(bitmap: Bitmap, file: File): Boolean {
        return try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (e: IOException) {
            Log.e("LidarMapView", "写入 PNG 失败", e)
            false
        }
    }

    // ---- 预览模式 ----
    private var mapMode = MapMode.LIVE
    private var manualRotationDegrees = 0f
    private var previewCenterX = 0f
    private var previewCenterY = 0f
    private var previewInitialized = false
    private var darkWalls = false
    private var lastFinalizedTextures: List<SubmapTexture> = emptyList()
    private var downX = 0f
    private var downY = 0f
    private var lastFingerAngle = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val compassBounds = RectF()
    private val compassRadius = 24f * resources.displayMetrics.density

    /** 切换预览模式。默认是实时绘制模式；预览模式下支持旋转手势、
     *  平移边界约束、加载地图自动居中，不绘制机器人，且底图为白色、墙体为黑色。 */
    fun setPreviewMode(enabled: Boolean) {
        if (enabled == (mapMode == MapMode.PREVIEW)) return
        mapMode = if (enabled) MapMode.PREVIEW else MapMode.LIVE
        darkWalls = enabled
        if (enabled) {
            previewInitialized = false
            manualRotationDegrees = 0f
        } else {
            manualRotationDegrees = 0f
            orientationMode = OrientationMode.FOLLOW_ROBOT
            panOffsetX = 0f
            panOffsetY = 0f
        }
        rerenderMap()
        invalidate()
    }

    /** 按当前模式重新渲染融合地图（墙体配色随预览模式切换）。 */
    private fun rerenderMap() {
        if (lastFinalizedTextures.isNotEmpty()) {
            setFinalizedSubmapTextures(lastFinalizedTextures)
        } else if (submaps.isNotEmpty()) {
            requestFusedMapRender()
        }
    }

    fun isPreviewMode(): Boolean = mapMode == MapMode.PREVIEW

    /** 预览模式下将整张地图适配到视口居中显示（原始朝向，旋转归零）。 */
    fun fitMapToView(): Boolean {
        if (mapMode != MapMode.PREVIEW) return false
        val fused = fusedMap ?: return false
        if (fused.bitmap.isRecycled || fused.resolutionMetersPerPixel <= 0f) return false
        val worldMinX = fused.worldMinX
        val worldMaxX = fused.worldMinX + fused.bitmap.width * fused.resolutionMetersPerPixel
        val worldMinY = fused.worldMaxY - fused.bitmap.height * fused.resolutionMetersPerPixel
        val worldMaxY = fused.worldMaxY
        previewCenterX = (worldMinX + worldMaxX) * 0.5f
        previewCenterY = (worldMinY + worldMaxY) * 0.5f
        manualRotationDegrees = 0f
        val padding = 40f * resources.displayMetrics.density
        val spanX = (worldMaxX - worldMinX).coerceAtLeast(0.01f)
        val spanY = (worldMaxY - worldMinY).coerceAtLeast(0.01f)
        val availableWidth = (width - padding * 2f).coerceAtLeast(1f)
        val availableHeight = (height - padding * 2f).coerceAtLeast(1f)
        val fittedScale = min(availableWidth / spanX, availableHeight / spanY)
        userZoom = (fittedScale / BASE_SCALE).coerceIn(1f, MAX_USER_ZOOM)
        previewZoom = userZoom
        panOffsetX = 0f
        panOffsetY = 0f
        clampPanOffset()
        invalidate()
        return true
    }

    /** 预览模式下约束平移量，保证地图内容不会完全移出界面。 */
    private fun clampPanOffset() {
        if (mapMode != MapMode.PREVIEW) return
        val fused = fusedMap ?: return
        if (fused.bitmap.isRecycled || fused.resolutionMetersPerPixel <= 0f) return
        val scale = BASE_SCALE * userZoom
        val margin = 88f * resources.displayMetrics.density
        val refX = currentReferenceX()
        val refY = currentReferenceY()
        val worldMinX = fused.worldMinX
        val worldMaxX = fused.worldMinX + fused.bitmap.width * fused.resolutionMetersPerPixel
        val worldMinY = fused.worldMaxY - fused.bitmap.height * fused.resolutionMetersPerPixel
        val worldMaxY = fused.worldMaxY
        val contentMinX = width / 2f + (worldMinX - refX) * scale
        val contentMaxX = width / 2f + (worldMaxX - refX) * scale
        val contentMinY = height / 2f - (worldMaxY - refY) * scale
        val contentMaxY = height / 2f - (worldMinY - refY) * scale
        val minPanX = margin - contentMinX
        val maxPanX = width - margin - contentMaxX
        val minPanY = margin - contentMinY
        val maxPanY = height - margin - contentMaxY
        panOffsetX = if (minPanX > maxPanX) {
            panOffsetX.coerceIn(maxPanX, minPanX)
        } else {
            (minPanX + maxPanX) * 0.5f
        }
        panOffsetY = if (minPanY > maxPanY) {
            panOffsetY.coerceIn(maxPanY, minPanY)
        } else {
            (minPanY + maxPanY) * 0.5f
        }
    }

    /** 复原地图原始朝向（指北针点击）。 */
    fun resetMapRotation() {
        if (mapMode != MapMode.PREVIEW) return
        manualRotationDegrees = 0f
        invalidate()
    }

    // ---- 地图内容加载完成回调 ----
    private var mapContentReady = false
    private var onMapContentLoaded: (() -> Unit)? = null

    /** 设置「地图内容首次渲染完成」监听（用于隐藏加载提示等）。 */
    fun setOnMapContentLoaded(listener: (() -> Unit)?) {
        onMapContentLoaded = listener
        // 每次设置监听都重新武装，保证同一 View 实例多次加载地图时回调仍会触发
        mapContentReady = false
    }

    private fun notifyContentLoadedIfReady() {
        if (mapContentReady) return
        mapContentReady = true
        onMapContentLoaded?.invoke()
    }

    /** 每次调用将地图旋转增加 90°，满 360° 后归零。 */
    fun changeMapRotation() {
        if (mapMode != MapMode.PREVIEW) return
        manualRotationDegrees = (manualRotationDegrees + 90f) % 360f
        invalidate()
    }


    fun resetToRobotFollowing() {
        orientationMode = OrientationMode.FOLLOW_ROBOT
        panOffsetX = 0f
        panOffsetY = 0f
        invalidate()
    }

    fun showMagneticNorthOverview(
        measurement: MapMeasurement,
        northYawRadians: Float
    ): Boolean {
        if (width <= 0 || height <= 0 || measurement.xSizeMeters <= 0f ||
            measurement.ySizeMeters <= 0f || !northYawRadians.isFinite()) {
            return false
        }
        overviewCenterX = (measurement.minX + measurement.maxX) * 0.5f
        overviewCenterY = (measurement.minY + measurement.maxY) * 0.5f
        magneticNorthYawRadians = northYawRadians

        val cosRotation = cos(northYawRadians).toFloat()
        val sinRotation = sin(northYawRadians).toFloat()
        var minScreenX = Float.POSITIVE_INFINITY
        var maxScreenX = Float.NEGATIVE_INFINITY
        var minScreenY = Float.POSITIVE_INFINITY
        var maxScreenY = Float.NEGATIVE_INFINITY
        val corners = arrayOf(
            measurement.minX to measurement.minY,
            measurement.minX to measurement.maxY,
            measurement.maxX to measurement.minY,
            measurement.maxX to measurement.maxY
        )
        for ((worldX, worldY) in corners) {
            val x = worldX - overviewCenterX
            val y = -(worldY - overviewCenterY)
            val rotatedX = cosRotation * x - sinRotation * y
            val rotatedY = sinRotation * x + cosRotation * y
            minScreenX = min(minScreenX, rotatedX)
            maxScreenX = max(maxScreenX, rotatedX)
            minScreenY = min(minScreenY, rotatedY)
            maxScreenY = max(maxScreenY, rotatedY)
        }
        val spanX = (maxScreenX - minScreenX).coerceAtLeast(0.01f)
        val spanY = (maxScreenY - minScreenY).coerceAtLeast(0.01f)
        val padding = 58f * resources.displayMetrics.density
        val availableWidth = (width - padding * 2f).coerceAtLeast(1f)
        val availableHeight = (height - padding * 2f).coerceAtLeast(1f)
        val fittedScale = min(availableWidth / spanX, availableHeight / spanY)
        userZoom = (fittedScale / BASE_SCALE).coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
        panOffsetX = 0f
        panOffsetY = 0f
        orientationMode = OrientationMode.MAGNETIC_NORTH_OVERVIEW
        invalidate()
        return true
    }

    fun clearMap() {
        trajectory.clear()
        optimizedTrajectory.clear()
        liveScanPoints.clear()
        liveScanTimestampNs = 0L
        submaps.clear()
        recycleLiveSubmapOverlays()
        fusionGeneration++
        fusionPending = false
        fusedMap?.bitmap?.recycle()
        fusedMap = null
        mapMeasurement = null
        markers.clear()
        latestPose = Pose2D(0.0, 0.0, 0.0)
        orientationMode = OrientationMode.FOLLOW_ROBOT
        overviewCenterX = 0f
        overviewCenterY = 0f
        magneticNorthYawRadians = 0f
        userZoom = 1.2f
        panOffsetX = 0f
        panOffsetY = 0f
        manualRotationDegrees = 0f
        previewCenterX = 0f
        previewCenterY = 0f
        previewInitialized = false
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isDragging = false
                if (event.pointerCount >= 2 && mapMode == MapMode.PREVIEW) {
                    lastFingerAngle = fingerAngle(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mapMode == MapMode.PREVIEW && event.pointerCount >= 2) {
                    val currentAngle = fingerAngle(event)
                    var delta = currentAngle - lastFingerAngle
                    while (delta > 180f) delta -= 360f
                    while (delta < -180f) delta += 360f
                    lastFingerAngle = currentAngle
                    manualRotationDegrees += delta
                    invalidate()
                } else if (isDragging && event.pointerCount == 1 &&
                    !scaleGestureDetector.isInProgress) {
                    panOffsetX += event.x - lastTouchX
                    panOffsetY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    clampPanOffset()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy <= touchSlop * touchSlop &&
                        compassBounds.contains(event.x, event.y)) {
                        resetMapRotation()
                    }
                }
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun fingerAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    override fun onDetachedFromWindow() {
        fusionGeneration++
        fusionPending = false
        fusionRunning = false
        fusionExecutor?.shutdownNow()
        fusionExecutor = null
        recycleLiveSubmapOverlays()
        fusedMap?.bitmap?.recycle()
        fusedMap = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 预览模式白色底图，实时模式浅蓝底图
        if (backgroundPaint.color != (if (mapMode == MapMode.PREVIEW) Color.WHITE
            else LIVE_BACKGROUND_COLOR)) {
            backgroundPaint.color = if (mapMode == MapMode.PREVIEW) Color.WHITE
            else LIVE_BACKGROUND_COLOR
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val scale = BASE_SCALE * userZoom
        val centerX = width / 2f + panOffsetX
        val centerY = height / 2f + panOffsetY

        canvas.save()
        canvas.scale(MAP_MIRROR_X, 1f, centerX, centerY)
        canvas.rotate(currentMapRotationDegrees(), centerX, centerY)
        drawSubmaps(canvas, centerX, centerY, scale)
        drawLiveSubmapOverlays(canvas, centerX, centerY, scale)
        if (liveScanVisible) {
            drawLiveScan(canvas, centerX, centerY, scale)
        }

        val pathPoints: Iterable<PointF> =
            if (optimizedTrajectory.size > 1) optimizedTrajectory else trajectory
        val pathSize = (pathPoints as? Collection<PointF>)?.size ?: trajectory.size
        if (pathSize > 1) {
            val referenceX = currentReferenceX()
            val referenceY = currentReferenceY()
            trajectoryPath.reset()
            var first = true
            for (point in pathPoints) {
                val x = centerX + (point.x - referenceX) * scale
                val y = centerY - (point.y - referenceY) * scale
                if (first) {
                    trajectoryPath.moveTo(x, y)
                    first = false
                } else {
                    trajectoryPath.lineTo(x, y)
                }
            }
            canvas.drawPath(trajectoryPath, pathPaint)
        }

        // 绘制箭头（预览模式不显示机器人）
        val robotX = centerX + (latestPose.x.toFloat() - currentReferenceX()) * scale
        val robotY = centerY - (latestPose.y.toFloat() - currentReferenceY()) * scale
        if (mapMode != MapMode.PREVIEW) {
            drawRobot(canvas, robotX, robotY)
        }
        drawMarkers(canvas, centerX, centerY, scale)
        canvas.restore()

        // 预览模式：屏幕坐标系绘制右上角指北针
        drawCompass(canvas)
    }

    /** 以 robot 资源图绘制机器人。图标默认朝上（屏幕上方），
     *  绕机器人位置旋转 (90° - θ) 后，经外层地图旋转合成，最终屏幕朝向
     *  与朝向线 (cosθ, -sinθ) 一致：FOLLOW_ROBOT 下恒定朝上，
     *  MAGNETIC_NORTH_OVERVIEW 下图标随 θ 相对地图旋转。 */
    private fun drawRobot(canvas: Canvas, robotX: Float, robotY: Float) {
        val bitmap = robotBitmap
        val halfW = bitmap.width / 2f
        val halfH = bitmap.height / 2f
        canvas.save()
        canvas.translate(robotX, robotY)
        canvas.rotate(90f - Math.toDegrees(latestPose.theta.toDouble()).toFloat())
        canvas.drawBitmap(bitmap, -halfW, -halfH, robotPaint)
        canvas.restore()
    }

    /** 绘制标注点：图标中心对准世界坐标点，文字带底色显示在图标上方。
     *  图标与文字通过反向旋转抵消地图旋转，在屏幕上始终正立（位置仍锚定在世界坐标）。
     *  裁剪基于「距旋转中心的距离」：旋转不改变距离，屏幕内可见点距中心
     *  必然 ≤ 视口半径，因此旋转/平移/缩放下不会误裁剪。 */
    private fun drawMarkers(canvas: Canvas, centerX: Float, centerY: Float, scale: Float) {
        drawMarkersList(
            canvas, centerX, centerY, scale,
            markers, currentReferenceX(), currentReferenceY(), currentMapRotationDegrees()
        )
    }

    private fun drawMarkersList(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        scale: Float,
        markers: List<MapMarker>,
        poseX: Float,
        poseY: Float,
        rotationDegrees: Float
    ) {
        if (markers.isEmpty()) return
        val gapPx = 4f * resources.displayMetrics.density
        val paddingX = 6f * resources.displayMetrics.density
        val paddingY = 3f * resources.displayMetrics.density
        // 视口半径 + 图标/文字余量，作为保守裁剪阈值
        val cutoff = hypot(width.toFloat(), height.toFloat()) * 0.5f +
            maxOf(dotIcon?.height ?: 0, routerIcon?.height ?: 0) + 60f
        // 抵消当前地图旋转，使图标/文字在屏幕上始终正立（位置仍锚定在世界坐标）
        val counterRotation = -rotationDegrees
        for (marker in markers) {
            val icon = when (marker.type) {
                MarkerType.DOT -> dotIcon
                MarkerType.ROUTER -> routerIcon
            } ?: continue
            val x = centerX + (marker.worldX - poseX) * scale
            val y = centerY - (marker.worldY - poseY) * scale
            val dx = x - centerX
            val dy = y - centerY
            if (dx * dx + dy * dy > cutoff * cutoff) {
//                continue
            }
            // 普通图标以中心对准世界点（区别于图钉的尖端对准）
            val iconLeft = x - icon.width / 2f
            val iconTop = y - icon.height / 2f
            canvas.save()
            canvas.rotate(counterRotation, x, y)
            canvas.drawBitmap(icon, iconLeft, iconTop, markerPaint)

            val labelY = iconTop - gapPx
            val textWidth = measurementTextPaint.measureText(marker.label)
            val metrics = measurementTextPaint.fontMetrics
            val background = RectF(
                x - textWidth * 0.5f - paddingX,
                labelY + metrics.ascent - paddingY,
                x + textWidth * 0.5f + paddingX,
                labelY + metrics.descent + paddingY
            )
            canvas.drawRoundRect(background, 5f, 5f, measurementTextBackgroundPaint)
            canvas.drawText(marker.label, x, labelY, measurementTextPaint)
            canvas.restore()
        }
    }

    /** 预览模式右上角指北针：完全复刻 ic_map_compass 造型——深灰细环、
     *  红北箭头/蓝南箭头（尖端+尾翼分叉+中央凹角）随手动旋转角反向转动，
     *  保持指向世界北。点击指北针区域（[resetMapRotation]）可将地图复原。 */
    private fun drawCompass(canvas: Canvas) {
        if (mapMode != MapMode.PREVIEW) return
        val density = resources.displayMetrics.density
        val margin = 14f * density
        val cx = width - margin - compassRadius
        val cy = margin + compassRadius
        compassBounds.set(
            cx - compassRadius, cy - compassRadius,
            cx + compassRadius, cy + compassRadius
        )
        // 透明底盘 + 深灰环（#263238，同 ic_map_compass）
        compassBgPaint.color = Color.TRANSPARENT
        canvas.drawCircle(cx, cy, compassRadius, compassBgPaint)
        compassRingPaint.color = Color.rgb(38, 50, 56)
        compassRingPaint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cy, compassRadius, compassRingPaint)

        canvas.save()
        canvas.rotate(manualRotationDegrees, cx, cy)

        val u = compassRadius / 28f

        // 北箭头（红 #D32F2F）：尖端朝世界北
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

        // 南箭头（蓝 #1976D2）：尖端朝世界南
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

        // N/S 小字（同 ic_map_compass 内部小字，随针旋转指示世界方向）
//        compassTextPaint.color = Color.rgb(183, 28, 28)
//        compassTextPaint.textSize = 9f * density
//        canvas.drawText("N", cx, cy - 9f * u, compassTextPaint)
//        compassTextPaint.color = Color.rgb(13, 71, 161)
//        canvas.drawText("S", cx, cy + 10f * u, compassTextPaint)

        canvas.restore()
    }

    private fun currentMapRotationDegrees(): Float {
        if (mapMode == MapMode.PREVIEW) return manualRotationDegrees
        return when (orientationMode) {
            OrientationMode.FOLLOW_ROBOT -> Math.toDegrees(latestPose.theta).toFloat() - 90f
            OrientationMode.MAGNETIC_NORTH_OVERVIEW ->
                Math.toDegrees(magneticNorthYawRadians.toDouble()).toFloat()
        }
    }

    private fun currentReferenceX(): Float = when {
        mapMode == MapMode.PREVIEW -> previewCenterX
        orientationMode == OrientationMode.FOLLOW_ROBOT -> latestPose.x.toFloat()
        else -> overviewCenterX
    }

    private fun currentReferenceY(): Float = when {
        mapMode == MapMode.PREVIEW -> previewCenterY
        orientationMode == OrientationMode.FOLLOW_ROBOT -> latestPose.y.toFloat()
        else -> overviewCenterY
    }

    private fun drawLiveScan(canvas: Canvas, centerX: Float, centerY: Float, scale: Float) {
        val cosYaw = cos(latestPose.theta).toFloat()
        val sinYaw = sin(latestPose.theta).toFloat()
        for (point in liveScanPoints) {
            val worldDx = cosYaw * point.x - sinYaw * point.y
            val worldDy = sinYaw * point.x + cosYaw * point.y
            val worldX = latestPose.x.toFloat() + worldDx
            val worldY = latestPose.y.toFloat() + worldDy
            val x = centerX + (worldX - currentReferenceX()) * scale
            val y = centerY - (worldY - currentReferenceY()) * scale
            if (x in 0f..width.toFloat() && y in 0f..height.toFloat()) {
                canvas.drawPoint(x, y, liveScanPaint)
            }
        }
    }

    private fun drawSubmaps(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        scale: Float,
        fused: FusedMapRenderer.Result? = null,
        poseX: Float? = null,
        poseY: Float? = null
    ) {
        val fused = fused ?: fusedMap ?: return
        val px = poseX ?: currentReferenceX()
        val py = poseY ?: currentReferenceY()
        val bitmapScale = fused.resolutionMetersPerPixel * scale
        if (bitmapScale <= 0f || fused.bitmap.isRecycled) return
        submapMatrixValues[Matrix.MSCALE_X] = bitmapScale
        submapMatrixValues[Matrix.MSKEW_X] = 0f
        submapMatrixValues[Matrix.MTRANS_X] =
            centerX + (fused.worldMinX - px) * scale
        submapMatrixValues[Matrix.MSKEW_Y] = 0f
        submapMatrixValues[Matrix.MSCALE_Y] = bitmapScale
        submapMatrixValues[Matrix.MTRANS_Y] =
            centerY - (fused.worldMaxY - py) * scale
        submapMatrixValues[Matrix.MPERSP_0] = 0f
        submapMatrixValues[Matrix.MPERSP_1] = 0f
        submapMatrixValues[Matrix.MPERSP_2] = 1f
        submapMatrix.setValues(submapMatrixValues)
        canvas.drawBitmap(fused.bitmap, submapMatrix, submapPaint)
    }

    private fun drawLiveSubmapOverlays(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        scale: Float,
        overlays: Collection<LiveSubmapOverlay>? = null,
        poseX: Float? = null,
        poseY: Float? = null
    ) {
        val px = poseX ?: currentReferenceX()
        val py = poseY ?: currentReferenceY()
        val source = overlays ?: liveSubmapOverlays.values
        for (overlay in source) {
            val texture = overlay.texture
            if (overlay.bitmap.isRecycled) continue
            val bitmapScale = texture.resolution * scale
            val cosTheta = cos(texture.theta).toFloat()
            val sinTheta = sin(texture.theta).toFloat()
            submapMatrixValues[Matrix.MSCALE_X] = sinTheta * bitmapScale
            submapMatrixValues[Matrix.MSKEW_X] = -cosTheta * bitmapScale
            submapMatrixValues[Matrix.MTRANS_X] =
                centerX + (texture.originX - px) * scale
            submapMatrixValues[Matrix.MSKEW_Y] = cosTheta * bitmapScale
            submapMatrixValues[Matrix.MSCALE_Y] = sinTheta * bitmapScale
            submapMatrixValues[Matrix.MTRANS_Y] =
                centerY - (texture.originY - py) * scale
            submapMatrixValues[Matrix.MPERSP_0] = 0f
            submapMatrixValues[Matrix.MPERSP_1] = 0f
            submapMatrixValues[Matrix.MPERSP_2] = 1f
            submapMatrix.setValues(submapMatrixValues)
            canvas.drawBitmap(overlay.bitmap, submapMatrix, submapPaint)
        }
    }

    private fun requestFusedMapRender() {
        fusionGeneration++
        fusionPending = true
        startNextFusedMapRender()
    }

    private fun startNextFusedMapRender() {
        if (fusionRunning || !fusionPending || submaps.isEmpty()) return
        fusionPending = false
        fusionRunning = true
        val generation = fusionGeneration
        val snapshot = submaps.values.toList()
        val useDarkWalls = darkWalls
        obtainFusionExecutor().execute {
            val rendered = try {
                FusedMapRenderer.render(snapshot)
            } catch (_: Throwable) {
                null
            }
            post {
                fusionRunning = false
                if (generation == fusionGeneration && rendered != null) {
                    val previous = fusedMap
                    fusedMap = rendered
                    if (previous?.bitmap !== rendered.bitmap) {
                        previous?.bitmap?.recycle()
                    }
                    notifyContentLoadedIfReady()
                    // Keep the two active textures as a stable foreground.
                    // Removing them here made the view alternate between the
                    // direct and fused render styles on every update, which
                    // was perceived as continuous flashing.
                    invalidate()
                } else {
                    rendered?.bitmap?.recycle()
                }
                startNextFusedMapRender()
            }
        }
    }

    private fun recycleLiveSubmapOverlays() {
        liveSubmapOverlays.values.forEach { it.bitmap.recycle() }
        liveSubmapOverlays.clear()
    }

    private data class LiveSubmapOverlay(
        val texture: SubmapTexture,
        val bitmap: Bitmap
    )

    private fun drawMapMeasurement(canvas: Canvas, centerX: Float, centerY: Float, scale: Float) {
        val measurement = mapMeasurement ?: return
        if (measurement.xSizeMeters <= 0f || measurement.ySizeMeters <= 0f) return

        if (measurement.hasSharedFloorPlanGeometry()) {
            drawSharedFloorPlanMeasurement(canvas, centerX, centerY, scale, measurement)
            return
        }

        val poseX = currentReferenceX()
        val poseY = currentReferenceY()
        val left = centerX + (measurement.minX - poseX) * scale
        val right = centerX + (measurement.maxX - poseX) * scale
        val top = centerY - (measurement.maxY - poseY) * scale
        val bottom = centerY - (measurement.minY - poseY) * scale
        val offsetPx = 30f * resources.displayMetrics.density
        val extensionPx = 7f * resources.displayMetrics.density

        canvas.drawRect(left, top, right, bottom, measurementGuidePaint)

        val xDimensionY = bottom + offsetPx
        canvas.drawLine(left, bottom, left, xDimensionY + extensionPx, measurementGuidePaint)
        canvas.drawLine(right, bottom, right, xDimensionY + extensionPx, measurementGuidePaint)
        val xName = if (measurement.xSizeMeters >= measurement.ySizeMeters) "长" else "宽"
        drawDoubleArrow(
            canvas,
            left,
            xDimensionY,
            right,
            xDimensionY,
            "$xName：${formatMeters(measurement.xSizeMeters)} m"
        )

        val yDimensionX = right + offsetPx
        canvas.drawLine(right, top, yDimensionX + extensionPx, top, measurementGuidePaint)
        canvas.drawLine(right, bottom, yDimensionX + extensionPx, bottom, measurementGuidePaint)
        val yName = if (measurement.ySizeMeters > measurement.xSizeMeters) "长" else "宽"
        drawDoubleArrow(
            canvas,
            yDimensionX,
            bottom,
            yDimensionX,
            top,
            "$yName：${formatMeters(measurement.ySizeMeters)} m"
        )
    }

    private fun drawSharedFloorPlanMeasurement(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        scale: Float,
        measurement: MapMeasurement
    ) {
        val poseX = currentReferenceX()
        val poseY = currentReferenceY()
        fun screen(worldX: Float, worldY: Float): PointF = PointF(
            centerX + (worldX - poseX) * scale,
            centerY - (worldY - poseY) * scale
        )

        // The yellow outline on the live/global map is the exact same closed
        // polygon that C++ draws in green in the exported floor plan.
        val outlinePath = Path()
        measurement.outlineWorld.forEachIndexed { index, point ->
            val screenPoint = screen(point.x, point.y)
            if (index == 0) outlinePath.moveTo(screenPoint.x, screenPoint.y)
            else outlinePath.lineTo(screenPoint.x, screenPoint.y)
        }
        outlinePath.close()
        canvas.drawPath(outlinePath, measurementGuidePaint)

        val halfLong = measurement.orientedLongSizeMeters * 0.5f
        val halfShort = measurement.orientedShortSizeMeters * 0.5f
        val dimensionOffsetMeters =
            (30f * resources.displayMetrics.density) / scale.coerceAtLeast(1f)
        val extensionMeters =
            (7f * resources.displayMetrics.density) / scale.coerceAtLeast(1f)

        fun point(longOffset: Float, shortOffset: Float): PointF = screen(
            measurement.orientedCenterX + measurement.longAxisX * longOffset +
                measurement.shortAxisX * shortOffset,
            measurement.orientedCenterY + measurement.longAxisY * longOffset +
                measurement.shortAxisY * shortOffset
        )

        val longLabel = "长：${formatMeters(measurement.lengthMeters)} m"
        val shortLabel = "宽：${formatMeters(measurement.widthMeters)} m"
        for (side in floatArrayOf(-1f, 1f)) {
            val edgeShort = side * halfShort
            val arrowShort = side * (halfShort + dimensionOffsetMeters)
            val guideShort = side * (halfShort + dimensionOffsetMeters + extensionMeters)
            val edgeStart = point(-halfLong, edgeShort)
            val edgeEnd = point(halfLong, edgeShort)
            val guideStart = point(-halfLong, guideShort)
            val guideEnd = point(halfLong, guideShort)
            val arrowStart = point(-halfLong, arrowShort)
            val arrowEnd = point(halfLong, arrowShort)
            canvas.drawLine(edgeStart.x, edgeStart.y, guideStart.x, guideStart.y,
                measurementGuidePaint)
            canvas.drawLine(edgeEnd.x, edgeEnd.y, guideEnd.x, guideEnd.y,
                measurementGuidePaint)
            drawDoubleArrow(canvas, arrowStart.x, arrowStart.y, arrowEnd.x, arrowEnd.y,
                longLabel)
        }
        for (side in floatArrayOf(-1f, 1f)) {
            val edgeLong = side * halfLong
            val arrowLong = side * (halfLong + dimensionOffsetMeters)
            val guideLong = side * (halfLong + dimensionOffsetMeters + extensionMeters)
            val edgeStart = point(edgeLong, -halfShort)
            val edgeEnd = point(edgeLong, halfShort)
            val guideStart = point(guideLong, -halfShort)
            val guideEnd = point(guideLong, halfShort)
            val arrowStart = point(arrowLong, -halfShort)
            val arrowEnd = point(arrowLong, halfShort)
            canvas.drawLine(edgeStart.x, edgeStart.y, guideStart.x, guideStart.y,
                measurementGuidePaint)
            canvas.drawLine(edgeEnd.x, edgeEnd.y, guideEnd.x, guideEnd.y,
                measurementGuidePaint)
            drawDoubleArrow(canvas, arrowStart.x, arrowStart.y, arrowEnd.x, arrowEnd.y,
                shortLabel)
        }
    }

    private fun drawDoubleArrow(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        label: String
    ) {
        canvas.drawLine(startX, startY, endX, endY, measurementPaint)
        val length = hypot(endX - startX, endY - startY)
        if (length <= 1f) return
        val ux = (endX - startX) / length
        val uy = (endY - startY) / length
        val px = -uy
        val py = ux
        val arrowLength = 11f * resources.displayMetrics.density
        val arrowHalfWidth = 5f * resources.displayMetrics.density

        canvas.drawLine(
            startX,
            startY,
            startX + ux * arrowLength + px * arrowHalfWidth,
            startY + uy * arrowLength + py * arrowHalfWidth,
            measurementPaint
        )
        canvas.drawLine(
            startX,
            startY,
            startX + ux * arrowLength - px * arrowHalfWidth,
            startY + uy * arrowLength - py * arrowHalfWidth,
            measurementPaint
        )
        canvas.drawLine(
            endX,
            endY,
            endX - ux * arrowLength + px * arrowHalfWidth,
            endY - uy * arrowLength + py * arrowHalfWidth,
            measurementPaint
        )
        canvas.drawLine(
            endX,
            endY,
            endX - ux * arrowLength - px * arrowHalfWidth,
            endY - uy * arrowLength - py * arrowHalfWidth,
            measurementPaint
        )

        val midX = (startX + endX) * 0.5f
        val midY = (startY + endY) * 0.5f
        var labelAngle = Math.toDegrees(
            atan2(endY - startY, endX - startX).toDouble()
        ).toFloat()
        while (labelAngle > 90f) labelAngle -= 180f
        while (labelAngle <= -90f) labelAngle += 180f
        canvas.save()
        canvas.rotate(labelAngle, midX, midY)
        val textWidth = measurementTextPaint.measureText(label)
        val metrics = measurementTextPaint.fontMetrics
        val paddingX = 6f * resources.displayMetrics.density
        val paddingY = 3f * resources.displayMetrics.density
        val background = RectF(
            midX - textWidth * 0.5f - paddingX,
            midY + metrics.ascent - paddingY,
            midX + textWidth * 0.5f + paddingX,
            midY + metrics.descent + paddingY
        )
        canvas.drawRoundRect(background, 5f, 5f, measurementTextBackgroundPaint)
        canvas.drawText(label, midX, midY, measurementTextPaint)
        canvas.restore()
    }

    private fun formatMeters(value: Float): String = String.format(Locale.US, "%.2f", value)


    private fun squaredDistance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun SubmapTexture.samePoseAs(other: SubmapTexture): Boolean {
        return abs(originX - other.originX) < 1e-4f &&
            abs(originY - other.originY) < 1e-4f &&
            abs(theta - other.theta) < 1e-4f
    }

    companion object {
        private const val BASE_SCALE = 65f
        private const val MIN_USER_ZOOM = 0.5f
        private const val MAX_USER_ZOOM = 3f
        private const val MAP_MIRROR_X = 1f
        // 实时模式浅蓝底图；预览模式为白色
        private const val LIVE_BACKGROUND_COLOR = 0xFF70B3FE.toInt()
    }
}
