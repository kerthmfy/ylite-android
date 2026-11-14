package app.ylite.data

import java.util.Locale

/** The three numbers on a fuel receipt. Any two of them determine the third. */
enum class FillField { LITRES, PRICE, TOTAL }

/**
 * Editable text for the refuel form, with the other values filled in as you type.
 *
 * The rule is the one Fuelio follows, and it is the one that matches what a
 * receipt actually tells you:
 *
 * - type **litres** with a price → the total appears;
 * - type **price** with litres → the total appears;
 * - type **total** with a price → the litres appear;
 * - type **total** with litres → the price appears.
 *
 * So the number you just typed is always trusted, and the *derived* field is the
 * one you have not touched. Nothing is ever blanked for a half-typed number.
 */
data class FillAmounts(
    val litres: String = "",
    val price: String = "",
    val total: String = "",
) {
    fun with(field: FillField, value: String): FillAmounts {
        val litresValue = parseAmount(litres)
        val priceValue = parseAmount(price)
        val totalValue = parseAmount(total)

        return when (field) {
            FillField.LITRES -> {
                val edited = copy(litres = value)
                val typed = parseAmount(value)
                when {
                    typed == null -> edited
                    priceValue != null -> edited.copy(total = formatMoney(typed * priceValue))
                    totalValue != null && typed > 0.0 ->
                        edited.copy(price = formatMoney(totalValue / typed))
                    else -> edited
                }
            }

            FillField.PRICE -> {
                val edited = copy(price = value)
                val typed = parseAmount(value)
                when {
                    typed == null -> edited
                    litresValue != null && typed > 0.0 ->
                        edited.copy(total = formatMoney(litresValue * typed))
                    totalValue != null -> edited.copy(litres = formatLitres(totalValue / typed))
                    else -> edited
                }
            }

            FillField.TOTAL -> {
                val edited = copy(total = value)
                val typed = parseAmount(value)
                when {
                    typed == null -> edited
                    priceValue != null && priceValue > 0.0 ->
                        edited.copy(litres = formatLitres(typed / priceValue))
                    litresValue != null && litresValue > 0.0 ->
                        edited.copy(price = formatMoney(typed / litresValue))
                    else -> edited
                }
            }
        }
    }

    val litresValue: Double? get() = parseAmount(litres)
    val priceValue: Double? get() = parseAmount(price)
    val totalValue: Double? get() = parseAmount(total)
}

/** Accepts "2,326" as well as "2.326": both keyboards happen here. */
internal fun parseAmount(raw: String): Double? =
    raw.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()

private fun formatLitres(litres: Double) = "%.3f".format(Locale.US, litres)

private fun formatMoney(amount: Double) = "%.2f".format(Locale.US, amount)
