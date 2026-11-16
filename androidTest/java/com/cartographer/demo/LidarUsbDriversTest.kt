package com.cartographer.demo

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import org.junit.Assert.assertEquals
import org.junit.Test

class LidarUsbDriversTest {
    @Test
    fun customRadarIdUsesCdcDriver() {
        assertEquals(CdcAcmSerialDriver::class.java,
            LidarUsbDrivers.probeTable().findDriver(0x2015, 0x0008))
    }

    @Test
    fun legacyCh340StillUsesItsOwnDriver() {
        assertEquals(Ch34xSerialDriver::class.java,
            LidarUsbDrivers.probeTable().findDriver(0x1A86, 0x7523))
    }
}
