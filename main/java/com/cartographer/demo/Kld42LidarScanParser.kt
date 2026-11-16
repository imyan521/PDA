package com.cartographer.demo

import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 新雷达（A 类协议）解析器。
 *
 * 协议帧结构（与 slam 模块 UsbReadClient 一致）：
 * ```
 * AA AA AA 96 | type(0x69) | len(2 字节大端，含校验) | data | xor 校验
 * ```
 * data 段内部按子命令分发：
 * - `09 4A 90 + 9×float(小端)`：六轴 + 地磁传感器数据（雷达自带 IMU）
 * - `09 4B 90 + AA55 数据流`：雷达点云，内部仍是旧雷达 AA55 帧，
 *   因此直接复用 [LidarParser] 的解析逻辑（原代码不动）。
 * - `09 49 90 + 电机码`：电机码查询（09 49 02）应答，回传原始 hex 与可打印文本
 * - `09 50 90 + DES/CBC 密文`：加密握手应答，解密后与下发的当前时间比对
 *
 * 相比 slam 原实现，这里有两处健壮性改进：
 * 1. 消费完一帧后保留剩余字节继续解析，不再整包清空（避免连续多帧时丢帧头）；
 * 2. 按协议对 len 与 data 做 xor 校验，校验失败整帧丢弃，降低误帧率。
 */
class Kld42LidarScanParser(private val carto: CartographerNative) : LidarScanParser {

    /** 雷达自带 IMU（09 4A）回调，供设备无 IMU 时接入 carto.addImuData。 */
    interface ImuListener {
        fun onImuData(timestampNs: Long, acceleration: FloatArray, gyroscope: FloatArray)
    }

    /** 雷达固件版本号（09 01）回调。 */
    interface VersionListener {
        fun onVersion(version: String)
    }

    /** 准备升级（09 03 01）回复回调：confirmed=是否收到设备应答，reply=应答内容。 */
    interface PrepareUpgradeListener {
        fun onReply(confirmed: Boolean, reply: String)
    }

    /** 电机码（09 49 02 查询）回调：rawHex=原始字节十六进制，text=按可打印字符解析的文本。 */
    interface MotorCodeListener {
        fun onMotorCode(rawHex: String, text: String)
    }

    @Volatile
    var imuListener: ImuListener? = null

    /** 09 49 control reply: optional echoed operation and actual status byte. */
    @Volatile
    var controlReplyListener: ((Int?, Int) -> Unit)? = null

    @Volatile
    var versionListener: VersionListener? = null

    @Volatile
    var prepareUpgradeListener: PrepareUpgradeListener? = null

    @Volatile
    var motorCodeListener: MotorCodeListener? = null

    /** 命令写入通道，由上层在打开串口后注入（用于雷达上电/下电控制）。 */
    @Volatile
    var commandSink: ((ByteArray) -> Unit)? = null

    /** 加密握手（09 50）等待锁：应答在 [onDataReceived] 线程解析后置位。 */
    @Volatile
    private var authLatch: CountDownLatch? = null

    /** 本次握手的期望内容：时间数字按数值打包后的 8 字节（'9'→0x09）。 */
    @Volatile
    private var authExpected: ByteArray? = null

    /** 本次插拔是否已通过加密握手；雷达拔出后由 [resetAuth] 清除。 */
    @Volatile
    private var authPassed = false

    /** 本次握手应答解密后是否与明文一致。 */
    @Volatile
    private var authMatched = false

    /** 是否已下发 09 49 02 电机码查询：只在请求之后解析应答，避免把 09 49 上下电应答当成电机码。 */
    @Volatile
    private var motorCodePending = false

    // 09 4B 载荷仍是 AA55 帧，复用原解析器（组合，不改动原类）
    private val aa55Parser = LidarParser(carto)

    private val frameDecoder = Kld42FrameDecoder()
    private val imuBatchClock = Kld42ImuBatchClock()
    val batchedImuSampleCount: Long get() = imuBatchClock.batchedSampleCount
    val compressedImuBatchCount: Long get() = imuBatchClock.compressedBatchCount
    val protocolFrameCount: Long get() = frameDecoder.validFrameCount
    val protocolChecksumErrorCount: Long get() = frameDecoder.checksumErrorCount
    val protocolDiscardedByteCount: Long get() = frameDecoder.discardedByteCount
    @Volatile var imuPacketCount = 0L
        private set
    @Volatile var latestImuTimestampNs = 0L
        private set
    @Volatile var latestAcceleration = FloatArray(3)
        private set
    @Volatile var latestAngularVelocity = FloatArray(3)
        private set
    @Volatile var latestRawGyroscope = FloatArray(3)
        private set

    private val accelerometer = FloatArray(3)
    private val gyroscope = FloatArray(3)
    private var lastRawImuLogNs = 0L

    override fun onDataReceived(data: ByteArray, length: Int) {
        onDataReceived(data, length, SystemClock.elapsedRealtimeNanos())
    }

    override fun onDataReceived(data: ByteArray, length: Int, receivedAtNs: Long) {
        synchronized(this) {
            // Decode the complete USB read before invoking potentially expensive SLAM.
            val payloads = ArrayList<ByteArray>()
            frameDecoder.feed(data, length, payloads::add)
            fun isImu(payload: ByteArray): Boolean = payload.size >= 40 &&
                payload[0] == 0x09.toByte() && payload[1] == 0x4A.toByte() && payload[2] == 0x90.toByte()
            val imuTimes = imuBatchClock.timestamps(payloads.count(::isImu), receivedAtNs)
            var imuIndex = 0
            for (payload in payloads) {
                val timestamp = if (isImu(payload)) imuTimes[imuIndex++] else receivedAtNs
                handlePayload(payload, timestamp)
            }
        }
    }

    override fun enableLidar() {
        aa55Parser.enableLidar()
    }

    override fun clearLatestScanAndPendingData() {
        synchronized(this) {
            frameDecoder.clear()
        }
        aa55Parser.clearLatestScanAndPendingData()
    }

    override fun getLatestScan(): LidarScan? = aa55Parser.getLatestScan()

    override val activeProtocol: String get() = "A类协议(AA55)"

    override val lastValidPointCount: Int get() = aa55Parser.lastValidPointCount
    override val lastShowDistance: Float get() = aa55Parser.lastShowDistance
    override val lastShowAngle: Float get() = aa55Parser.lastShowAngle
    override val aa55PacketCount: Long get() = aa55Parser.aa55PacketCount
    override val invalidAa55PacketCount: Long get() = aa55Parser.invalidAa55PacketCount
    override val droppedIncompleteScanCount: Long get() = aa55Parser.droppedIncompleteScanCount
    override val droppedByteCount: Long get() = aa55Parser.droppedByteCount
    override val rejectedOutlierPointCount: Long get() = aa55Parser.rejectedOutlierPointCount

    /** 雷达上电/下电（data:01 上电, 00 下电）。 */
    fun controlSlamPower(open: Boolean) {
//        Log.i(TAG, "controlSlamPower ${open}")
        commandSink?.invoke(buildCommand(byteArrayOf(0x09, 0x49, if (open) 0x01 else 0x00)))
    }

    /** 传感器上电/下电（data:03 上电, 04 下电）。 */
    fun controlSensorPower(open: Boolean) {
        Log.d(TAG, "controlSensorPower ${open}")
        commandSink?.invoke(buildCommand(byteArrayOf(0x09, 0x49, if (open) 0x03 else 0x04)))
    }

    /** 获取雷达固件版本号（命令 09 01），结果通过 [versionListener] 回调。 */
    fun requestVersion() {
        commandSink?.invoke(buildCommand(byteArrayOf(0x09, 0x01)))
    }

    /** 通知雷达进入准备升级状态（命令 09 03 01），应答通过 [prepareUpgradeListener] 回调。 */
    fun requestPrepareUpgrade() {
        commandSink?.invoke(buildCommand(byteArrayOf(0x09, 0x03, 0x01)))
    }

    /**
     * 获取雷达电机码（命令 09 49 02），设备应答 `09 49 90 + 电机码`，
     * 结果通过 [motorCodeListener] 回调（原始 hex + 可打印文本）。
     */
    fun requestMotorCode() {
        motorCodePending = true
        commandSink?.invoke(buildCommand(byteArrayOf(0x09, 0x49, 0x02)))
    }

    /**
     * 加密握手（命令 09 50）：下发当前时间（MMddHHmm，8 字节 ASCII），设备回传 DES/CBC 密文，
     * 解密后与下发内容比对，一致视为校验通过。阻塞等待应答，必须在非主线程调用。
     *
     * 校验结果按"一次插拔"缓存：通过后再次调用直接返回 true，不再下发指令；
     * 页面切换导致的重新上下电因此不会重复握手，只有 [resetAuth]（雷达拔出）后才重新校验。
     *
     * 注意：设备把收到的 8 个 ASCII 数字按数值重新打包（'9'→0x09）后再加密，
     * 所以本地比对用数值形式 [authExpected]，下发到串口的仍是 ASCII 字节。
     *
     * @return true=校验通过（含已缓存）；false=命令通道未就绪 / 超时未应答 / 解密失败 / 内容不一致
     */
    fun requestAuth(timeoutMs: Long = AUTH_TIMEOUT_MS): Boolean {
        if (authPassed) {
            return true
        }
        val sink = commandSink
        if (sink == null) {
            Log.w(TAG, "requestAuth 失败")
            return false
        }
        val plain = SimpleDateFormat(AUTH_TIME_PATTERN, Locale.US).format(Date())
//        val plain = "00000000"
        val payload = plain.toByteArray(Charsets.US_ASCII)
//        val expected = ByteArray(payload.size) { (payload[it].toInt() - '0'.code).toByte() }
        val expected = payload.copyOf()
        val latch = CountDownLatch(1)
        authExpected = expected
        authMatched = false
        authLatch = latch
        sink(buildCommand(byteArrayOf(0x09, 0x50) + payload))
//        Log.d(TAG, "requestAuth 下发明文=$plain")
        val replied = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        authLatch = null
        authExpected = null
        val passed = replied && authMatched
        authPassed = passed
        Log.d(TAG, "requestAuth ${if (passed) "校验通过" else "校验失败"}")
        return passed
    }

    /** 雷达拔出（重新插入）时清除校验缓存，下次连接需重新执行加密握手。 */
    fun resetAuth() {
        authPassed = false
        Log.d(TAG, "resetAuth 校验状态已清除")
    }

    // ---- 私有：A 类协议解析 ----

    private fun handlePayload(payload: ByteArray, receivedAtNs: Long) {
        if (payload.size < 4) {
//            Log.d(TAG, "handlePayload 载荷过短 len=${payload.size} hex=${toHex(payload)}")
            return
        }
        val cmd1 = payload[0].toInt() and 0xFF
        val cmd2 = payload[1].toInt() and 0xFF
        val sub = payload[2].toInt() and 0xFF
//        Log.d(TAG, String.format(
//            "handlePayload len=%d hex=%s cmd1=0x%02X cmd2=0x%02X sub=0x%02X",
//            payload.size, toHex(payload), cmd1, cmd2, sub))
        if (cmd1 == 0x09 && cmd2 == 0x49) {
            val operation = if (payload.size >= 5) payload[3].toInt() and 0xFF else null
            controlReplyListener?.invoke(operation, sub)
        }
        if (sub != 0x90) {
            return
        }
        // 去掉尾部内部校验字节
        val inner = payload.copyOfRange(3, payload.size - 1)
        when {
            cmd1 == 0x09 && cmd2 == 0x01 -> parseVersion(inner)
            cmd1 == 0x09 && cmd2 == 0x03 -> parsePrepareUpgrade(inner)
            cmd1 == 0x09 && cmd2 == 0x49 -> parseMotorCode(inner)
            cmd1 == 0x09 && cmd2 == 0x4A -> parseImu(inner, receivedAtNs)
            cmd1 == 0x09 && cmd2 == 0x50 -> parseAuthReply(inner)
            cmd1 == 0x09 && cmd2 == 0x4B -> {
                // 09 4B 载荷仍是 AA55 帧流，复用原解析器
                aa55Parser.onDataReceived(inner, inner.size, receivedAtNs)
            }
        }
    }

    private fun parseVersion(inner: ByteArray) {
        // 版本号按 ASCII 文本解析（过滤非可打印字符）
        val text = StringBuilder(inner.size)
        for (byte in inner) {
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) text.append(value.toChar())
        }
        versionListener?.onVersion(text.toString())
    }

    private fun parsePrepareUpgrade(inner: ByteArray) {
        prepareUpgradeListener?.onReply(true, "")
    }

    /**
     * 09 49 02 应答：定长电机码字节，同时回传原始 hex 与按可打印字符解析的文本
     * （现场可用 hex 与设备标签/二维码直接比对，text 仅作辅助）。
     */
    private fun parseMotorCode(inner: ByteArray) {
        // 只在已下发查询后解析：09 49 这组还有上下电命令，其应答同样是 09 49 90
        if (!motorCodePending) return
        if (inner.isEmpty() || inner.size < 10) {
            return
        }
        motorCodePending = false
        val data = inner.copyOfRange(4, inner.size)
        val text = StringBuilder(data.size)
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) text.append(value.toChar())
        }
        val rawHex = toHex(data)
//        Log.d(TAG, "电机码 len=${data.size} hex=$rawHex text=$text")
        motorCodeListener?.onMotorCode(rawHex, "KE$text")
    }

    private fun parseImu(inner: ByteArray, receivedAtNs: Long) {
        if (inner.size < IMU_FLOAT_COUNT * 4) return
        val reader = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 3) {
            accelerometer[i] = reader.float
        }
        // 固件 gyro 单位为 mdps（0.001°/s），转 Cartographer 需要的 rad/s
        val rawGyroscope = FloatArray(3) { reader.float }
        latestRawGyroscope = rawGyroscope
        imuPacketCount++
        latestImuTimestampNs = receivedAtNs
        for (i in 0 until 3) {
            gyroscope[i] = rawGyroscope[i] * GYRO_MDPS_TO_RAD_PER_SEC
        }
        latestAcceleration = accelerometer.copyOf()
        latestAngularVelocity = gyroscope.copyOf()
        logRawImu(inner)
        imuListener?.onImuData(receivedAtNs, accelerometer, gyroscope)
    }

    /**
     * 09 50 应答：DES/CBC 解密后与本次下发的期望内容逐字节比对（允许密文末尾带填充字节），
     * 期望内容的构造见 [requestAuth]，比对结果由 [requestAuth] 判定。
     */
    private fun parseAuthReply(cipherBytes: ByteArray) {
        val expected = authExpected ?: return
        val latch = authLatch ?: return
        val decrypted = decryptDesCbc(cipherBytes)
        val matched = decrypted != null && decrypted.size >= expected.size &&
            expected.indices.all { decrypted[it] == expected[it] }
//        Log.d(
//            TAG,
//            "parseAuthReply 密文=${toHex(cipherBytes)} " +
//                "解出=${decrypted?.let { toHex(it) }} 期望=${toHex(expected)} matched=$matched"
//        )
        authMatched = matched
        latch.countDown()
    }

    /** DES/CBC/NoPadding 解密（key=kaer0631, iv=12345678）；长度非 8 的整数倍或解密异常返回 null。 */
    private fun decryptDesCbc(cipherBytes: ByteArray): ByteArray? {
        if (cipherBytes.isEmpty() || cipherBytes.size % DES_BLOCK_SIZE != 0) {
            Log.w(TAG, "09 50 密文长度非法 len=${cipherBytes.size}")
            return null
        }
        return runCatching {
            val cipher = Cipher.getInstance(DES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(AUTH_KEY.toByteArray(Charsets.US_ASCII), "DES"),
                IvParameterSpec(AUTH_IV.toByteArray(Charsets.US_ASCII))
            )
            cipher.doFinal(cipherBytes)
        }.getOrElse {
            Log.w(TAG, "09 50 解密失败", it)
            null
        }
    }

    /**
     * 调试：按秒打印 09 4A 载荷的全部 9 个 float（acc 3 + gyro 3 + 地磁 3）。
     * 旋转设备时哪个字段跟着动，就能确认字段位置是否正确、以及 gyro 是否真的在输出。
     */
    private fun logRawImu(inner: ByteArray) {
        if (!IMU_RAW_DEBUG_LOG) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs - lastRawImuLogNs < 1_000_000_000L) return
        lastRawImuLogNs = nowNs
        val raw = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN)
        val text = StringBuilder()
        for (i in 0 until IMU_FLOAT_COUNT) {
            if (i > 0) text.append(", ")
            text.append("%.3f".format(raw.float))
        }
        Log.d(IMU_RAW_TAG, "len=${inner.size} floats=[$text] hex=${toHex(inner)}")
    }

    private fun buildCommand(data: ByteArray): ByteArray {
        val length = data.size + 1 // +1 为末尾校验和
        val command = ByteArray(HEAD_LENGTH + data.size + 1)
        FRAME_HEAD.copyInto(command, 0)
        command[4] = 0x69
        command[5] = (length ushr 8).toByte()
        command[6] = (length and 0xFF).toByte()
        data.copyInto(command, HEAD_LENGTH)
        var sum = 0
        for (i in 5 until command.size - 1) {
            sum = sum xor (command[i].toInt() and 0xFF)
        }
        command[command.size - 1] = sum.toByte()
        return command
    }

    private companion object {
        private const val TAG = "Kld42LidarScanParser"
        const val HEAD_LENGTH = 7
        val FRAME_HEAD = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0x96.toByte())
        const val IMU_FLOAT_COUNT = 9
        /** 09 4A 原始载荷打印开关：确认 acc/gyro/地磁 9 个 float 的字段位置与是否有效。 */
        const val IMU_RAW_DEBUG_LOG = true
        const val IMU_RAW_TAG = "ImuRaw"

        /** 转空格分隔的大写十六进制，便于打印调试。 */
        fun toHex(bytes: ByteArray): String =
            bytes.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

        /** 09 4A gyro 单位换算：mdps（0.001°/s）→ rad/s。= 0.001 * π/180 */
        private const val GYRO_MDPS_TO_RAD_PER_SEC = 1.7453292519943295E-5f

        /** 09 50 加密握手：明文为当前时间 MMddHHmm（8 字节），应答用 DES/CBC 解密。 */
        private const val AUTH_TIME_PATTERN = "MMddHHmm"
        private const val AUTH_TIMEOUT_MS = 1000L
        private const val DES_TRANSFORMATION = "DES/CBC/NoPadding"
        private const val AUTH_KEY = "MXvWZ&p!"
        private const val AUTH_IV = "wQ0>hA6|"
        private const val DES_BLOCK_SIZE = 8
    }
}
