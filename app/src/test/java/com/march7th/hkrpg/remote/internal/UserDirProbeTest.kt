package com.march7th.hkrpg.remote.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AsstSetUserDir 前的探测：不可访问只能返回原因，绝不能把进程带崩 */
class UserDirProbeTest {

    @Test
    fun probe_passesOnWritableDirectory_andLeavesNoProbeFile() {
        val dir = Files.createTempDirectory("user-dir-probe").toFile()
        try {
            assertNull(UserDirProbe.probe(dir))
            assertTrue(File(dir, "debug").isDirectory)
            assertFalse(File(dir, "debug/.probe").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun probe_createsMissingUserDir() {
        val parent = Files.createTempDirectory("user-dir-probe").toFile()
        try {
            val dir = File(parent, "Maa")
            assertNull(UserDirProbe.probe(dir))
            assertTrue(dir.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun probe_failsWhenPathIsAFile() {
        val file = Files.createTempFile("user-dir-probe", ".txt").toFile()
        try {
            assertNotNull(UserDirProbe.probe(file))
        } finally {
            file.delete()
        }
    }
}
