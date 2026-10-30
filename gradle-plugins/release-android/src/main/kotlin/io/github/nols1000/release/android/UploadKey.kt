package io.github.nols1000.release.android

import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** The keystore holding the Play upload key, together with its passwords. */
data class UploadKey(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    fun writeProperties() {
        val properties = Properties().apply {
            setProperty("storePassword", storePassword)
            setProperty("keyAlias", keyAlias)
            setProperty("keyPassword", keyPassword)
        }
        val file = propertiesFile(storeFile)
        file.writer().use { properties.store(it, "Upload key passwords, keep next to ${storeFile.name} and back up both") }
        restrictToOwner(file)
        restrictToOwner(storeFile)
    }

    companion object {
        private fun restrictToOwner(file: File) {
            runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }

        fun propertiesFile(storeFile: File) = File(storeFile.path + ".properties")

        fun parse(storeFile: File, propertiesText: String): UploadKey {
            val properties = Properties().apply { load(StringReader(propertiesText)) }
            return UploadKey(
                storeFile = storeFile,
                storePassword = properties.getProperty("storePassword"),
                keyAlias = properties.getProperty("keyAlias"),
                keyPassword = properties.getProperty("keyPassword"),
            )
        }
    }
}
