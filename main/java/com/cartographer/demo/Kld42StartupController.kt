package com.cartographer.demo

/** One command at a time: MCU motor startup blocks before it can enable its IMU. */
class Kld42StartupController {
    enum class Stage { STOPPED, WAITING_LIDAR, WAITING_IMU, READY, FAILED }
    enum class Command { START_LIDAR, ENABLE_IMU }

    var stage = Stage.STOPPED
        private set
    var attempts = 0
        private set
    var lastReply = "尚未收到控制应答"
        private set
    private var deadlineMs = 0L
    private var imuSeen = false
    private var imuAcknowledged = false
    private var lastStatus: Int? = null
    private var failure = ""

    val statusText: String get() = when (stage) {
        Stage.STOPPED -> "尚未开启板载 IMU"
        Stage.WAITING_LIDAR -> "等待雷达上电应答（$attempts/$MAX_ATTEMPTS）"
        Stage.WAITING_IMU -> if (imuAcknowledged) "设备已应答，等待板载 IMU 数据"
            else "等待板载 IMU 上报（$attempts/$MAX_ATTEMPTS）"
        Stage.READY -> "已收到板载 IMU 数据"
        Stage.FAILED -> failure
    }

    fun start(nowMs: Long): List<Command> {
        stage = Stage.WAITING_LIDAR
        attempts = 1
        deadlineMs = nowMs + TIMEOUT_MS
        imuSeen = false
        imuAcknowledged = false
        lastStatus = null
        lastReply = "尚未收到控制应答"
        failure = ""
        return listOf(Command.START_LIDAR)
    }

    fun onControlReply(operation: Int?, status: Int, nowMs: Long): List<Command> {
        lastReply = "操作=${operation?.let { "0x%02X".format(it) } ?: "未回显"}，状态=0x%02X".format(status)
        val expected = when (stage) {
            Stage.WAITING_LIDAR -> 0x01
            Stage.WAITING_IMU -> 0x03
            else -> return emptyList()
        }
        if (operation != null && operation != expected) return emptyList()
        lastStatus = status
        if (status != 0x90) {
            // A failed initialization can be transient; retry on a timer, not
            // immediately inside the receive callback or in a tight command loop.
            deadlineMs = minOf(deadlineMs, nowMs + RETRY_DELAY_MS)
            return emptyList()
        }
        if (stage == Stage.WAITING_LIDAR) return enableImu(nowMs)
        if (!imuAcknowledged) {
            imuAcknowledged = true
            deadlineMs = nowMs + TIMEOUT_MS
        }
        return emptyList()
    }

    fun onLidarData(nowMs: Long): List<Command> =
        if (stage == Stage.WAITING_LIDAR) enableImu(nowMs) else emptyList()

    fun onImuData() {
        if (stage == Stage.STOPPED || stage == Stage.FAILED) return
        imuSeen = true
        if (stage == Stage.WAITING_IMU) stage = Stage.READY
    }

    fun onTimer(nowMs: Long): List<Command> {
        if (stage != Stage.WAITING_LIDAR && stage != Stage.WAITING_IMU) return emptyList()
        if (nowMs < deadlineMs) return emptyList()
        if (attempts >= MAX_ATTEMPTS) {
            failure = when {
                stage == Stage.WAITING_LIDAR -> "雷达未确认上电，请重新点击开始采集"
                lastStatus == 0x41 -> "板载传感器开启失败（0x41），请检查 MIC6200/MMC3630 初始化"
                imuAcknowledged -> "设备已应答开启，但没有板载 IMU 数据，请检查固件上报"
                else -> "板载 IMU 开启超时，请重新点击开始采集"
            }
            stage = Stage.FAILED
            return emptyList()
        }
        attempts++
        deadlineMs = nowMs + TIMEOUT_MS
        return listOf(if (stage == Stage.WAITING_LIDAR) Command.START_LIDAR else Command.ENABLE_IMU)
    }

    private fun enableImu(nowMs: Long): List<Command> {
        if (imuSeen) {
            stage = Stage.READY
            return emptyList()
        }
        stage = Stage.WAITING_IMU
        attempts = 1
        lastStatus = null
        deadlineMs = nowMs + TIMEOUT_MS
        return listOf(Command.ENABLE_IMU)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val TIMEOUT_MS = 2000L
        const val RETRY_DELAY_MS = 500L
    }
}
