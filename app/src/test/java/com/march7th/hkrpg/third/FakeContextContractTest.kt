package com.march7th.hkrpg.third

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeContextContractTest {

    @Test
    fun namedResolverDeclaresAcquireProvider() {
        val src = resolve("src/main/java/com/march7th/hkrpg/third/FakeContext.java").readText()
        assertTrue(src.contains("class ShellContentResolver"))
        assertTrue(src.contains("acquireProvider(Context"))
    }

    private fun resolve(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "File not found: $relativePath" }
        return file
    }
}
