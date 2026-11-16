package com.cartographer.demo

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlamDiagnosticArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun exportContainsConfigurationMetadataAndCompletedSensorStream() {
        val config = temp.newFolder("config")
        File(config, "android_2d.lua").writeText("use_imu_data=true")
        val directory = File(temp.root, "turn")
        val recorder = SlamSessionRecorder(directory, config)
        recorder.imu(100L, floatArrayOf(0f, 0f, 9.81f), FloatArray(3))
        recorder.stop()
        assertTrue(recorder.awaitClosed(2000))
        File(directory, "session.json").writeText("{\"source\":\"BOARD\"}")
        val archive = SlamDiagnosticArchive.create(directory)
        ZipFile(archive).use { zip ->
            assertEquals(setOf("android_2d.lua", "session.json", "sensors.bin"),
                zip.entries().asSequence().map { it.name }.toSet())
            DataInputStream(zip.getInputStream(zip.getEntry("sensors.bin"))).use { input ->
                input.skipBytes(8)
                val firstSize = input.readInt()
                input.skipBytes(firstSize)
                assertEquals(18, input.readInt())
                assertEquals(127, input.readUnsignedByte())
            }
        }
        assertFalse(File(directory.parentFile, "turn.zip.part").exists())
    }
}
