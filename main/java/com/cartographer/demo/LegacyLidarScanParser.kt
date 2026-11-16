package com.cartographer.demo

/**
 * 旧雷达（裸 AA55 协议流）适配器。
 *
 * 原 [LidarParser] 代码保持不动，这里以组合方式包装它并实现 [LidarScanParser]，
 * 使新旧雷达可以在 [LidarScanParserRouter] 中统一切换。
 */
class LegacyLidarScanParser(private val carto: CartographerNative) : LidarScanParser {

    private val delegate = LidarParser(carto)

    override fun onDataReceived(data: ByteArray, length: Int) {
        delegate.onDataReceived(data, length)
    }

    override fun onDataReceived(data: ByteArray, length: Int, receivedAtNs: Long) {
        delegate.onDataReceived(data, length, receivedAtNs)
    }

    override fun enableLidar() {
        delegate.enableLidar()
    }

    override fun clearLatestScanAndPendingData() {
        delegate.clearLatestScanAndPendingData()
    }

    override fun getLatestScan(): LidarScan? = delegate.getLatestScan()

    override val activeProtocol: String get() = delegate.activeProtocol
    override val lastValidPointCount: Int get() = delegate.lastValidPointCount
    override val lastShowDistance: Float get() = delegate.lastShowDistance
    override val lastShowAngle: Float get() = delegate.lastShowAngle
    override val aa55PacketCount: Long get() = delegate.aa55PacketCount
    override val invalidAa55PacketCount: Long get() = delegate.invalidAa55PacketCount
    override val droppedIncompleteScanCount: Long get() = delegate.droppedIncompleteScanCount
    override val droppedByteCount: Long get() = delegate.droppedByteCount
    override val rejectedOutlierPointCount: Long get() = delegate.rejectedOutlierPointCount
}
