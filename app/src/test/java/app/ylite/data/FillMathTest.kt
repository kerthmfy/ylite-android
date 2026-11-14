package app.ylite.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fuelio's "type any two, get the third" behaviour, pinned down. */
class FillMathTest {

    @Test
    fun priceAndTotalGiveLitres() {
        val amounts = FillAmounts()
            .with(FillField.PRICE, "86")
            .with(FillField.TOTAL, "200")

        // 200 / 86 = 2.32558..., shown to three decimals like Fuelio does.
        assertEquals("2.326", amounts.litres)
        assertEquals("86", amounts.price)
        assertEquals("200", amounts.total)
    }

    @Test
    fun litresAndPriceGiveTotal() {
        val amounts = FillAmounts()
            .with(FillField.LITRES, "3.5")
            .with(FillField.PRICE, "57.5")

        assertEquals("201.25", amounts.total)
    }

    @Test
    fun litresAndTotalGivePrice() {
        val amounts = FillAmounts()
            .with(FillField.LITRES, "4")
            .with(FillField.TOTAL, "220")

        assertEquals("55.00", amounts.price)
    }

    @Test
    fun correctingTheTotalRecomputesLitresNotTheOtherWayRound() {
        val amounts = FillAmounts()
            .with(FillField.PRICE, "57")
            .with(FillField.LITRES, "3")
            .with(FillField.TOTAL, "180")

        // The total was touched last, so litres is the stale field and gets re-derived.
        assertEquals("3.158", amounts.litres)
        assertEquals("57", amounts.price)
    }

    @Test
    fun halfTypedNumbersDoNotWipeAnything() {
        val amounts = FillAmounts()
            .with(FillField.PRICE, "57")
            .with(FillField.TOTAL, "12")

        // Litres is the derived field, and 12 / 57 is valid, so it fills.
        assertEquals("0.211", amounts.litres)

        val broken = amounts.with(FillField.TOTAL, "")
        assertEquals("", broken.total)
        // Litres keeps its last good value instead of blanking mid-edit.
        assertEquals("0.211", broken.litres)
    }

    @Test
    fun zeroPriceCannotDivide() {
        val amounts = FillAmounts()
            .with(FillField.PRICE, "0")
            .with(FillField.TOTAL, "200")

        assertEquals("", amounts.litres)
    }

    @Test
    fun commasAreAcceptedAsDecimalSeparators() {
        val amounts = FillAmounts()
            .with(FillField.LITRES, "2,5")
            .with(FillField.PRICE, "60")

        assertEquals("150.00", amounts.total)
    }
}
