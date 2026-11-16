package com.cartographer.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Kld42StartupControllerTest {
    @Test fun doesNotOverlapImuEnableWithMotorStartup() {
        val c = Kld42StartupController()
        assertEquals(listOf(Kld42StartupController.Command.START_LIDAR), c.start(0))
        assertTrue(c.onTimer(200).isEmpty())
        assertEquals(listOf(Kld42StartupController.Command.ENABLE_IMU), c.onControlReply(1, 0x90, 700))
        assertEquals(Kld42StartupController.Stage.WAITING_IMU, c.stage)
    }

    @Test fun requiresRealImuDataAfterSuccessfulEnableReply() {
        val c = Kld42StartupController()
        c.start(0)
        c.onControlReply(1, 0x90, 700)
        c.onControlReply(3, 0x90, 800)
        assertEquals(Kld42StartupController.Stage.WAITING_IMU, c.stage)
        c.onImuData()
        assertEquals(Kld42StartupController.Stage.READY, c.stage)
    }

    @Test fun pointCloudCanConfirmStartupWhenMotorReplyWasLost() {
        val c = Kld42StartupController()
        c.start(0)
        assertEquals(listOf(Kld42StartupController.Command.ENABLE_IMU), c.onLidarData(700))
        assertTrue(c.onLidarData(710).isEmpty())
    }

    @Test fun unrelatedStopReplyDoesNotEnableImu() {
        val c = Kld42StartupController()
        c.start(0)
        assertTrue(c.onControlReply(0, 0x90, 100).isEmpty())
        assertEquals(Kld42StartupController.Stage.WAITING_LIDAR, c.stage)
    }

    @Test fun retriesMissingEnableAndReportsTimeout() {
        val c = Kld42StartupController()
        c.start(0)
        c.onLidarData(700)
        assertEquals(listOf(Kld42StartupController.Command.ENABLE_IMU), c.onTimer(2700))
        assertEquals(listOf(Kld42StartupController.Command.ENABLE_IMU), c.onTimer(4700))
        assertTrue(c.onTimer(6700).isEmpty())
        assertEquals(Kld42StartupController.Stage.FAILED, c.stage)
        assertTrue(c.statusText.contains("超时"))
    }

    @Test fun reportsInitializationFailureInsteadOfClaimingReady() {
        val c = Kld42StartupController()
        c.start(0)
        c.onLidarData(700)
        repeat(3) { attempt ->
            val now = 800L + attempt * 600L
            assertTrue(c.onControlReply(3, 0x41, now).isEmpty())
            c.onTimer(now + 500)
        }
        assertEquals(Kld42StartupController.Stage.FAILED, c.stage)
        assertTrue(c.statusText.contains("0x41"))
    }

    @Test fun successAckWithoutSamplesDoesNotWaitForever() {
        val c = Kld42StartupController()
        c.start(0)
        c.onLidarData(700)
        c.onControlReply(3, 0x90, 800)
        c.onTimer(2800)
        c.onTimer(4800)
        c.onTimer(6800)
        assertEquals(Kld42StartupController.Stage.FAILED, c.stage)
        assertTrue(c.statusText.contains("没有板载 IMU 数据"))
    }

    @Test fun acceptsLegacyReplyWithoutOperationEcho() {
        val c = Kld42StartupController()
        c.start(0)
        assertEquals(listOf(Kld42StartupController.Command.ENABLE_IMU), c.onControlReply(null, 0x90, 700))
    }

    @Test fun alreadyStreamingImuStillNeedsEvidenceThatLidarStarted() {
        val c = Kld42StartupController()
        c.start(0)
        c.onImuData()
        assertEquals(Kld42StartupController.Stage.WAITING_LIDAR, c.stage)
        assertTrue(c.onLidarData(700).isEmpty())
        assertEquals(Kld42StartupController.Stage.READY, c.stage)
    }
}
