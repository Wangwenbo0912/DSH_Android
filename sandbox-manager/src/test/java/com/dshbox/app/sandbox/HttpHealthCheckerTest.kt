package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the DSH version metadata parsing in [HttpHealthChecker].
 *
 * The parser extracts `window.__DSH_BOOT__` from a real DSH index page and
 * reads the composed-graph `rev` fingerprint (used as the version when the
 * server does not serve an explicit semver), plus explicit
 * `version`/`pluginApiVersion` fields when a future server provides them.
 */
class HttpHealthCheckerTest {

    private val checker = HttpHealthChecker()

    /** Body shaped like the live DSH index page (bundle graph injected). */
    private val bootBody = """
        <!doctype html>
        <html lang="zh-CN">
          <head><script>window.__DSH_BOOT__ = {"rev":"86fe00183588","entries":[{"id":"@deepseek-ai/dsh-typert-registry","url":"/plugins/@deepseek-ai/dsh-typert-registry/client.js?rev=f41d56e0b747","rev":"f41d56e0b747","inject":[],"immediately":true},{"id":"@deepseek-ai/dsh-api-gateway","url":"/plugins/@deepseek-ai/dsh-api-gateway/client.js?rev=9e83e9d9c076","rev":"9e83e9d9c076","inject":["@deepseek-ai/dsh-typert-registry","@deepseek-ai/dsh-client-connection"],"immediately":true}]}</script>
        <script type="module" crossorigin src="/assets/index-C-1AiF3k.js"></script>
          </head>
          <body><div id="root"></div></body>
        </html>
    """.trimIndent()

    @Test
    fun `parses boot graph rev as version when no explicit version is served`() {
        val result = checker.parseVersionInfo(bootBody)

        assertEquals("86fe00183588", result.dshVersion)
        assertNull(result.pluginApiVersion)
    }

    @Test
    fun `explicit version fields win over the graph rev`() {
        val body = bootBody.replace(
            "\"rev\":\"86fe00183588\",",
            "\"rev\":\"86fe00183588\",\"version\":\"0.2.0-rc.1\",\"pluginApiVersion\":\"3\",",
        )

        val result = checker.parseVersionInfo(body)

        assertEquals("0.2.0-rc.1", result.dshVersion)
        assertEquals("3", result.pluginApiVersion)
    }

    @Test
    fun `returns nulls when body does not contain a boot manifest`() {
        val result = checker.parseVersionInfo("<html><body>maintenance page</body></html>")

        assertNull(result.dshVersion)
        assertNull(result.pluginApiVersion)
    }

    @Test
    fun `returns nulls for empty or null body`() {
        assertNull(checker.parseVersionInfo(null).dshVersion)
        assertNull(checker.parseVersionInfo("").dshVersion)
        assertNull(checker.parseVersionInfo("   ").dshVersion)
    }

    @Test
    fun `truncated boot manifest is handled gracefully`() {
        val truncated = "window.__DSH_BOOT__ = {\"rev\":\"86fe00183588\""
        val result = checker.parseVersionInfo(truncated)

        // Unbalanced braces -> no reliable JSON boundary; rev is not extracted
        // from a malformed document.
        assertNull(result.dshVersion)
        assertNull(result.pluginApiVersion)
    }
}