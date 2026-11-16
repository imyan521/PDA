package com.cartographer.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class RssiSample(
    val worldX: Float,
    val worldY: Float,
    val rssiDbm: Float,
    val timestampMillis: Long = 0L
)

/** Renders a transparent RSSI layer in the exact floor-plan export frame. */
object HeatMapRenderer {
    private const val GRID_SIZE = 128
    private const val MAX_INTERPOLATION_SAMPLES = 600
    private const val MIN_DISTANCE_SQUARED = 0.0025
    private const val LAYER_ALPHA = 155
    /** 较差信号阈值：低于该值的栅格计入「较差区域占比」。 */
    private const val POOR_RSSI_THRESHOLD_DBM = -67f

    /** 户型轮廓内的栅格统计：栅格等面积，两者之比即面积占比。 */
    data class HeatMapStats(val validCellCount: Int, val poorCellCount: Int) {
        /** 较差区域占比 0~1；无有效栅格时为 0。 */
        val poorRatio: Float
            get() = if (validCellCount <= 0) 0f
            else poorCellCount.toFloat() / validCellCount
    }

    /** [renderWithStats] 的返回：热力图层与统计；采样不足时两者均为 null。 */
    data class HeatMapResult(val bitmap: Bitmap?, val stats: HeatMapStats?)

    fun render(
        samples: List<RssiSample>,
        geometry: FloorPlanMapExporter.ExportGeometry,
        outlinePixels: List<FloorPlanPixelPoint>,
	    supportMask: Bitmap? = null
    ): Bitmap? = renderWithStats(samples, geometry, outlinePixels, supportMask).bitmap

    /** 与 [render] 相同，但额外返回户型轮廓内的较差区域统计。 */
    fun renderWithStats(
        samples: List<RssiSample>,
        geometry: FloorPlanMapExporter.ExportGeometry,
        outlinePixels: List<FloorPlanPixelPoint>,
        supportMask: Bitmap? = null
    ): HeatMapResult {
        val allValidSamples = samples.filter {
            it.worldX.isFinite() && it.worldY.isFinite() &&
                it.rssiDbm.isFinite() && it.rssiDbm in -150f..0f
        }
        if (allValidSamples.size < 3 || outlinePixels.size < 3 ||
            geometry.widthPx <= 0 || geometry.heightPx <= 0) {
            return HeatMapResult(null, null)
        }

        // Keep interpolation time bounded on long mapping sessions while
        // retaining samples uniformly across the complete acquisition.
        val sampleStep = max(
            1,
            ceil(allValidSamples.size.toDouble() / MAX_INTERPOLATION_SAMPLES).toInt()
        )
        val validSamples = allValidSamples.filterIndexed { index, _ ->
            index % sampleStep == 0 || index == allValidSamples.lastIndex
        }

        val gridWidth = min(GRID_SIZE, geometry.widthPx)
        val gridHeight = min(
            GRID_SIZE,
            max(
                1,
                ceil(
                    gridWidth * geometry.heightPx.toDouble() /
                        geometry.widthPx.toDouble()
                ).toInt()
            )
        )
        val outlinePath = Path().apply {
            outlinePixels.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        // android.graphics.Path 无 contains()，用 Region 栅格化后判断点是否在轮廓内
        val outlineRegion = Region()
        outlineRegion.setPath(
            outlinePath,
            Region(0, 0, geometry.widthPx, geometry.heightPx)
        )

        val pixels = IntArray(gridWidth * gridHeight)
        val supportDistanceSquared =
            HeatMapCoverageCalculator.SUPPORT_RADIUS_METERS *
                HeatMapCoverageCalculator.SUPPORT_RADIUS_METERS
        var validCellCount = 0
        var poorCellCount = 0
        for (gridY in 0 until gridHeight) {
            val pixelY = (gridY + 0.5f) * geometry.heightPx / gridHeight
            val worldY = geometry.worldMaxY -
                pixelY * geometry.resolutionMetersPerPixel
            for (gridX in 0 until gridWidth) {
                val pixelX = (gridX + 0.5f) * geometry.widthPx / gridWidth
                val worldX = geometry.worldMinX +
                    pixelX * geometry.resolutionMetersPerPixel
                var weightedRssi = 0.0
                var weightSum = 0.0
                var nearestDistanceSquared = Double.POSITIVE_INFINITY
                var nearestRssi = 0f
                for (sample in validSamples) {
                    val dx = worldX - sample.worldX
                    val dy = worldY - sample.worldY
                    val distanceSquared = (dx * dx + dy * dy).toDouble()
                    if (distanceSquared < nearestDistanceSquared) {
                        nearestDistanceSquared = distanceSquared
                        nearestRssi = sample.rssiDbm
                    }
                    val weight = 1.0 / distanceSquared.coerceAtLeast(MIN_DISTANCE_SQUARED)
                    weightedRssi += sample.rssiDbm * weight
                    weightSum += weight
                }
                val rssi =
                    if (nearestDistanceSquared <= supportDistanceSquared && weightSum > 0.0) {
                        // 支撑域内：IDW 反距离平方插值，近处数值精确
                        (weightedRssi / weightSum).toFloat()
                    } else {
                        // 户型内远离所有采样点的空洞：取最近单个样本值直填，
                        // 避免大房间/未走到的区域在热力图上留白
                        nearestRssi
                    }
                pixels[gridY * gridWidth + gridX] = rssiColor(rssi)
                // 只在户型轮廓内统计：栅格等面积，计数之比即面积占比。
                // 阈值 -67 无法从热力图色带（仅 6 档）反查，必须在此处取数值。
                if (outlineRegion.contains(pixelX.toInt(), pixelY.toInt())) {
                    validCellCount++
                    if (rssi < POOR_RSSI_THRESHOLD_DBM) poorCellCount++
                }
            }
        }

        val coarse = Bitmap.createBitmap(
            pixels,
            gridWidth,
            gridHeight,
            Bitmap.Config.ARGB_8888
        )
        val output = Bitmap.createBitmap(
            geometry.widthPx,
            geometry.heightPx,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        canvas.save()
        canvas.clipPath(outlinePath)
        canvas.drawBitmap(
            coarse,
            Rect(0, 0, coarse.width, coarse.height),
            RectF(0f, 0f, output.width.toFloat(), output.height.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        canvas.restore()
        if (supportMask != null && !supportMask.isRecycled &&
            supportMask.width == output.width && supportMask.height == output.height) {
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            canvas.drawBitmap(supportMask, 0f, 0f, maskPaint)
            maskPaint.xfermode = null
        }
        coarse.recycle()
        return HeatMapResult(output, HeatMapStats(validCellCount, poorCellCount))
    }

    private fun rssiColor(rssi: Float): Int {
        val stops = arrayOf(
            -90f to Color.rgb(35, 80, 210),
            -75f to Color.rgb(30, 190, 210),
            -65f to Color.rgb(45, 205, 85),
            -55f to Color.rgb(245, 215, 35),
            -45f to Color.rgb(245, 125, 25),
            -30f to Color.rgb(225, 45, 35)
        )
        val clamped = rssi.coerceIn(stops.first().first, stops.last().first)
        for (index in 0 until stops.lastIndex) {
            val (lowValue, lowColor) = stops[index]
            val (highValue, highColor) = stops[index + 1]
            if (clamped <= highValue) {
                val fraction = (clamped - lowValue) / (highValue - lowValue)
                fun channel(low: Int, high: Int): Int =
                    (low + (high - low) * fraction).toInt().coerceIn(0, 255)
                return Color.argb(
                    LAYER_ALPHA,
                    channel(Color.red(lowColor), Color.red(highColor)),
                    channel(Color.green(lowColor), Color.green(highColor)),
                    channel(Color.blue(lowColor), Color.blue(highColor))
                )
            }
        }
        return Color.argb(LAYER_ALPHA, 225, 45, 35)
    }
}
