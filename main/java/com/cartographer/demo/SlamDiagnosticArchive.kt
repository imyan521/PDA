package com.cartographer.demo

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SlamDiagnosticArchive {
    fun create(directory: File): File {
        require(File(directory, "sensors.bin").isFile) { "诊断记录不存在" }
        val destination = File(directory.parentFile, directory.name + ".zip")
        val temporary = File(directory.parentFile, directory.name + ".zip.part")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    output.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("无法完成诊断包")
            return destination
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }
}
