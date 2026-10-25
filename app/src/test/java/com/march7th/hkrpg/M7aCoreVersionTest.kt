package com.march7th.hkrpg

import com.march7th.hkrpg.data.resource.CoreVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaaCoreVersionTest {

    @Test
    fun meetsMinimumRequired_basicComparison() {
        assertTrue(CoreVersion.meetsMinimumRequired("v6.0.0", current = "v6.12.0"))
        assertFalse(CoreVersion.meetsMinimumRequired("v6.12.0", current = "v6.0.0"))
        assertFalse(CoreVersion.meetsMinimumRequired("v7.0.0", current = "v6.99.99"))
        assertFalse(CoreVersion.meetsMinimumRequired("v6.12.1", current = "v6.12.0"))
    }

    @Test
    fun meetsMinimumRequired_equalVersions() {
        assertTrue(CoreVersion.meetsMinimumRequired("v6.12.0-beta.2", current = "v6.12.0-beta.2"))
        assertTrue(CoreVersion.meetsMinimumRequired("6.12.0", current = "v6.12.0"))
    }

    @Test
    fun meetsMinimumRequired_releaseIsNewerThanPreRelease() {
        assertTrue(CoreVersion.meetsMinimumRequired("v6.12.0-beta.2", current = "v6.12.0"))
        assertFalse(CoreVersion.meetsMinimumRequired("v6.12.0", current = "v6.12.0-beta.2"))
    }

    @Test
    fun meetsMinimumRequired_numericPreReleaseSegments_compareNumerically() {
        // 字典序会得出 beta.10 < beta.2，必须按数值比较
        assertTrue(CoreVersion.meetsMinimumRequired("v6.12.0-beta.2", current = "v6.12.0-beta.10"))
        assertFalse(CoreVersion.meetsMinimumRequired("v6.12.0-beta.10", current = "v6.12.0-beta.2"))
    }

    @Test
    fun meetsMinimumRequired_alphaIsOlderThanBeta() {
        assertFalse(CoreVersion.meetsMinimumRequired("v6.12.0-beta.1", current = "v6.12.0-alpha.5"))
        assertTrue(CoreVersion.meetsMinimumRequired("v6.12.0-alpha.5", current = "v6.12.0-beta.1"))
    }

    @Test
    fun meetsMinimumRequired_shorterPreReleaseIsOlder() {
        assertFalse(CoreVersion.meetsMinimumRequired("v6.12.0-beta.1", current = "v6.12.0-beta"))
        assertTrue(CoreVersion.meetsMinimumRequired("v6.12.0-beta", current = "v6.12.0-beta.1"))
    }

    @Test
    fun meetsMinimumRequired_noRequirement_passes() {
        assertTrue(CoreVersion.meetsMinimumRequired(null, current = "v6.0.0"))
        assertTrue(CoreVersion.meetsMinimumRequired("", current = "v6.0.0"))
        assertTrue(CoreVersion.meetsMinimumRequired("  ", current = "v6.0.0"))
    }

    @Test
    fun meetsMinimumRequired_unknownCurrentVersion_passes() {
        // 本地未运行 setup_maa_core.py 时 BuildConfig.MAA_CORE_VERSION 为空串，宽松放行
        assertTrue(CoreVersion.meetsMinimumRequired("v99.0.0", current = ""))
        assertTrue(CoreVersion.meetsMinimumRequired("v99.0.0", current = "  "))
    }

    @Test
    fun meetsMinimumRequired_malformedOrPartialVersions_areLenient() {
        // 畸形版本号解析为 0.0.0，不抛异常
        assertTrue(CoreVersion.meetsMinimumRequired("garbage", current = "v6.0.0"))
        // 缺失 patch 位补 0
        assertTrue(CoreVersion.meetsMinimumRequired("v6.12", current = "v6.12.0"))
    }
}
