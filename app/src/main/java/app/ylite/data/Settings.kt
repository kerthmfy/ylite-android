package app.ylite.data

import android.content.Context

/**
 * The scooter itself, as opposed to the CCU that reports on it.
 *
 * The BLE name (`YSCCU_00000005221269`) identifies the connectivity unit, not the
 * model. Nothing in the GATT table carries a model code either — the only place a
 * model could come from is the advertisement (see ScanHit.advertised) or an NUS
 * frame, and neither is known yet. So the model is a setting you choose once,
 * and it is attached to every ride you record.
 */
object Scooters {
    val models = listOf(
        "Yamaha Mio Gravis",
        "Yamaha Mio i125",
        "Yamaha NMAX 155",
        "Yamaha Aerox 155",
        "Yamaha XMAX 300",
        "Yamaha Lexi 125",
        "Yamaha Fazzio 125",
        "Yamaha Freego 125",
        "Yamaha Gear 125",
        "Yamaha Grand Filano",
        "Other scooter",
    )
}

/** Tiny key/value store. SharedPreferences is enough for one string. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("ylite", Context.MODE_PRIVATE)

    var scooterModel: String?
        get() = prefs.getString(KEY_MODEL, null)
        set(value) {
            prefs.edit().putString(KEY_MODEL, value).apply()
        }

    private companion object {
        const val KEY_MODEL = "scooter_model"
    }
}
