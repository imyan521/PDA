package com.cartographer.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * 将标注点渲染为透明叠加图层（第 5 层 markers_overlay.png），
 * 坐标系与户型图各图层一致：由 geometry 将世界坐标换算为像素。
 * 图标与文字样式与 LidarMapView 实时绘制保持一致（图标 + 白字深底圆角标签）。
 */
object MarkersOverlayRenderer {

    fun render(
        width: Int,
        height: Int,
        markers: List<LidarMapView.MapMarker>,
        geometry: FloorPlanMapExporter.ExportGeometry,
        dotIcon: Bitmap?,
        routerIcon: Bitmap?
    ): Bitmap? {
        if (width <= 0 || height <= 0 || markers.isEmpty()) return null

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val shortSide = min(width, height).toFloat().coerceAtLeast(1f)

        // 图标大小与文字大小随图幅缩放，保证小图不大、大图不糊
        val iconTarget = (shortSide * 0.03f).coerceIn(16f, 48f)
        val textSize = (shortSide * 0.022f).coerceIn(10f, 26f)
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
        }
        val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 28, 33, 40)
            style = Paint.Style.FILL
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val gapPx = 4f
        val paddingX = textSize * 0.4f
        val paddingY = textSize * 0.18f
        for (marker in markers) {
            if (!marker.worldX.isFinite() || !marker.worldY.isFinite()) continue
            val x = geometry.worldToPixelX(marker.worldX)
            val y = geometry.worldToPixelY(marker.worldY)
            if (!x.isFinite() || !y.isFinite()) continue
            if (x < -iconTarget || y < -iconTarget ||
                x > width + iconTarget || y > height + iconTarget) {
                continue
            }

            val icon = when (marker.type) {
                LidarMapView.MarkerType.DOT -> dotIcon
                LidarMapView.MarkerType.ROUTER -> routerIcon
            }
            if (icon != null && !icon.isRecycled) {
                val scale = iconTarget / max(icon.width, icon.height).toFloat()
                val drawW = (icon.width * scale).toFloat()
                val drawH = (icon.height * scale).toFloat()
                canvas.drawBitmap(
                    icon,
                    null,
                    RectF(x - drawW / 2f, y - drawH / 2f, x + drawW / 2f, y + drawH / 2f),
                    iconPaint
                )
            } else {
                // 无图标资源时以纯色圆点兜底：DOT=绿，ROUTER=蓝
                dotPaint.color = when (marker.type) {
                    LidarMapView.MarkerType.DOT -> Color.rgb(35, 190, 80)
                    LidarMapView.MarkerType.ROUTER -> Color.rgb(40, 120, 220)
                }
                canvas.drawCircle(x, y, iconTarget / 2f, dotPaint)
            }

            val labelY = y - iconTarget / 2f - gapPx
            val textWidth = textPaint.measureText(marker.label)
            val metrics = textPaint.fontMetrics
            val background = RectF(
                x - textWidth * 0.5f - paddingX,
                labelY + metrics.ascent - paddingY,
                x + textWidth * 0.5f + paddingX,
                labelY + metrics.descent + paddingY
            )
            canvas.drawRoundRect(background, 5f, 5f, textBackgroundPaint)
            canvas.drawText(marker.label, x, labelY, textPaint)
        }
        return output
    }
}
