package com.cartographer.demo

/**
 * 新旧雷达解析器路由。
 *
 * 上层只与本类交互，通过 [protocol] 在旧雷达（裸 AA55 流）与新雷达
 * （A 类协议）之间切换，对上层暴露统一的 [LidarScanParser] 行为。
 */
class LidarScanParserRouter(private val carto: CartographerNative) : LidarScanParser {

    enum class Protocol {
        /** 旧雷达：裸 AA55 协议流。 */
        LEGACY_AA55,

        /** 新雷达：A 类协议（AA AA AA 96 帧头）包裹的 AA55 流。 */
        KAER_PROTOCOL,
    }

    val legacy: LegacyLidarScanParser = LegacyLidarScanParser(carto)
    val aaProtocol: Kld42LidarScanParser = Kld42LidarScanParser(carto)

    @Volatile
    var protocol: Protocol = Protocol.LEGACY_AA55

    val current: LidarScanParser
        get() = if (protocol == Protocol.KAER_PROTOCOL) aaProtocol else legacy

    // ---- 统一转发 ----

    override fun onDataReceived(data: ByteArray, length: Int) {
        current.onDataReceived(data, length)
    }

    override fun onDataReceived(data: ByteArray, length: Int, receivedAtNs: Long) {
        current.onDataReceived(data, length, receivedAtNs)
    }

    override fun enableLidar() {
        // 两个解析器都放开发送许可，切换协议后无需重新启用
        legacy.enableLidar()
        aaProtocol.enableLidar()
    }

    override fun clearLatestScanAndPendingData() {
        current.clearLatestScanAndPendingData()
    }

    override fun getLatestScan(): LidarScan? = current.getLatestScan()

    override val activeProtocol: String get() = current.activeProtocol
    override val lastValidPointCount: Int get() = current.lastValidPointCount
    override val lastShowDistance: Float get() = current.lastShowDistance
    override val lastShowAngle: Float get() = current.lastShowAngle
    override val aa55PacketCount: Long get() = current.aa55PacketCount
    override val invalidAa55PacketCount: Long get() = current.invalidAa55PacketCount
    override val droppedIncompleteScanCount: Long get() = current.droppedIncompleteScanCount
    override val droppedByteCount: Long get() = current.droppedByteCount
    override val rejectedOutlierPointCount: Long get() = current.rejectedOutlierPointCount
}
