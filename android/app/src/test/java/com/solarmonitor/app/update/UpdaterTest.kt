package com.solarmonitor.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions() {
        assertTrue(Updater.newer("1.5.10", "1.5.9"))
        assertTrue(Updater.newer("1.6", "1.5.9"))
        assertTrue(Updater.newer("2.0.0", "1.9.9"))
        assertFalse(Updater.newer("1.5.7", "1.5.7"))
        assertFalse(Updater.newer("1.5.7", "1.5.7.0"))
        assertFalse(Updater.newer("1.5.6", "1.5.7"))
        assertFalse(Updater.newer("1.5.7-beta", "1.5.7"))
    }

    private fun rel(tag: String, apk: String? = "solar-monitor.apk", draft: Boolean = false, pre: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"notes $tag","assets":[${apk?.let { """{"name":"$it","browser_download_url":"https://x/$tag.apk","size":123,"digest":"sha256:ab12"}""" } ?: ""}]}"""

    @Test fun picksNewestAndroidRelease() {
        val json = "[" + listOf(rel("v1.5.8"), rel("v1.5.10"), rel("ui-classic-1.5.6"), rel("v1.6.0", draft = true),
            rel("v1.6.1", pre = true), rel("v1.9.0", apk = null)).joinToString(",") + "]"
        val r = Updater.pick(json, "1.5.7")!!
        assertEquals("1.5.10", r.version)
        assertEquals("https://x/v1.5.10.apk", r.apkUrl)
        assertEquals("ab12", r.sha256)
        assertEquals("notes v1.5.10", r.notes)
    }

    @Test fun nothingNewer() {
        assertNull(Updater.pick("[" + rel("v1.5.7") + "]", "1.5.7"))
        assertNull(Updater.pick("[]", "1.5.7"))
    }
}
