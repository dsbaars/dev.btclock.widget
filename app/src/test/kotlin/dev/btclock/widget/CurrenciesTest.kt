package dev.btclock.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CurrenciesTest {
    @Test
    fun `USD maps to dollar sign`() {
        assertEquals("$", Currencies.symbol("USD"))
    }

    @Test
    fun `lookup is case-insensitive`() {
        assertEquals("€", Currencies.symbol("eur"))
        assertEquals("€", Currencies.symbol("Eur"))
    }

    @Test
    fun `unknown code returns null so callers fall back to stacked ISO`() {
        assertNull(Currencies.symbol("XYZ"))
        assertNull(Currencies.symbol("KRW"))
    }

    @Test
    fun `every offered currency is unique`() {
        assertEquals(Currencies.OFFERED.size, Currencies.OFFERED.toSet().size)
    }

    @Test
    fun `every currency symbol is exactly one user-perceivable glyph`() {
        for (code in Currencies.OFFERED) {
            val symbol = Currencies.symbol(code) ?: continue
            assertEquals(
                "Symbol for $code should be a single char",
                1,
                symbol.length,
            )
        }
    }

    @Test
    fun `default USD currency has a registered symbol`() {
        assertNotNull(Currencies.symbol(Currencies.OFFERED.first()))
    }
}
