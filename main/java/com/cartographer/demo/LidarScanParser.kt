package com.cartographer.demo

/**
 * 雷达扫描解析器的统一契约。
 *
 * 新旧两款雷达（裸 AA55 流 / A 类协议包裹的 AA55 流）都实现本接口，
 * 上层（MainActivity / LidarScanParserRouter）无需感知具体协议，
 * 只通过本接口取扫描、开关数据与统计信息。
 */
interface LidarScanParser {

    /** USB 串口原始字节回调入口。 */
    fun onDataReceived(data: ByteArray, length: Int)

    /** Final USB read's arrival time in elapsedRealtimeNanos; preserve through decoding. */
    fun onDataReceived(data: ByteArray, length: Int, receivedAtNs: Long)

    /** 允许解析器把组装好的扫描帧喂给 SLAM（在 IMU 就绪后调用）。 */
    fun enableLidar()

    /** 清空组帧缓冲与最新扫描，用于暂停/切换场景。 */
    fun clearLatestScanAndPendingData()

    /** 最近一帧完整扫描（供 UI 实时预览），无则返回 null。 */
    fun getLatestScan(): LidarScan?

    /** 当前生效的协议描述，用于调试面板展示。 */
    val activeProtocol: String

    // ---- 调试统计（由各解析器透传） ----
    val lastValidPointCount: Int
    val lastShowDistance: Float
    val lastShowAngle: Float
    val aa55PacketCount: Long
    val invalidAa55PacketCount: Long
    val droppedIncompleteScanCount: Long
    val droppedByteCount: Long
    val rejectedOutlierPointCount: Long
}
