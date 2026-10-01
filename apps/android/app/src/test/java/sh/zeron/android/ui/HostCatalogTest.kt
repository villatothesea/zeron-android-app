package sh.zeron.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.zeron_core.CatalogSource

class HostCatalogTest {
    private fun c(h: String, id: String) = ModelChoice(h, h, id, id, emptyList())

    private val base = HostCatalog(
        models = listOf(c("claude-code", "opus"), c("codex", "gpt-6-astra"), c("codex", "gpt-5.5"), c("pi", "pi-1")),
        stale = mapOf("codex" to CatalogSource.SAVED),
    )

    @Test
    fun aLiveRefreshReplacesOneHarnessInPlaceAndClearsItsStaleMark() {
        val fresh = listOf(c("codex", "gpt-6.1-sol"), c("codex", "gpt-6-astra"))
        val next = base.with("codex", fresh, CatalogSource.LIVE)
        assertEquals(listOf("opus", "gpt-6.1-sol", "gpt-6-astra", "pi-1"), next.models.map { it.id })
        assertEquals(emptyMap<String, CatalogSource>(), next.stale)
    }

    @Test
    fun aFailedRefreshKeepsTheMark() {
        val next = base.with("pi", listOf(c("pi", "pi-0")), CatalogSource.STATIC)
        assertEquals(listOf("opus", "gpt-6-astra", "gpt-5.5", "pi-0"), next.models.map { it.id })
        assertEquals(mapOf("codex" to CatalogSource.SAVED, "pi" to CatalogSource.STATIC), next.stale)
    }

    @Test
    fun aHarnessNotListedYetGoesLast() {
        val next = base.with("devin", listOf(c("devin", "swe-1")), CatalogSource.LIVE)
        assertEquals("swe-1", next.models.last().id)
    }
}
