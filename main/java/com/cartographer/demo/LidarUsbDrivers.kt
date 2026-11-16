package com.cartographer.demo

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber

/** KLD42 uses a custom USB ID that usb-serial-for-android 3.4.6 does not register. */
object LidarUsbDrivers {
    const val SLAM_VID = 0x2015
    const val SLAM_PID = 0x0008

    fun probeTable(): ProbeTable = UsbSerialProber.getDefaultProbeTable().apply {
        addProduct(SLAM_VID, SLAM_PID, CdcAcmSerialDriver::class.java)
    }

    fun createProber(): UsbSerialProber = UsbSerialProber(probeTable())
}
