package dev.btclock.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class DigitLayoutTest {
    @Test
    fun `genesis block has subsidy of fifty BTC`() {
        assertEquals(50.0, DigitLayout.bitcoinSupplyAt(0), 0.0)
    }

    @Test
    fun `supply at end of first epoch is 50 times 210000`() {
        assertEquals(50.0 * 210_000, DigitLayout.bitcoinSupplyAt(209_999), 0.0)
    }

    @Test
    fun `supply at end of second epoch combines two halving subsidies`() {
        val expected = 50.0 * 210_000 + 25.0 * 210_000
        assertEquals(expected, DigitLayout.bitcoinSupplyAt(419_999), 0.0)
    }

    @Test
    fun `supply converges to the 21 million cap at far-future heights`() {
        val supplyFarFuture = DigitLayout.bitcoinSupplyAt(34_000_000)
        assertEquals(21_000_000.0, supplyFarFuture, 1.0)
    }

    @Test
    fun `supply for negative height is zero`() {
        assertEquals(0.0, DigitLayout.bitcoinSupplyAt(-1), 0.0)
    }
}
