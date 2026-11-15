package app.ylite

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Process-wide setup. Runs before any Activity or Service.
 */
class YApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Graph.setLive { it.copy(model = Graph.settings.scooterModel) }

        // A remembered CCU is reconnected on its own: with the ignition off there is
        // nothing to connect to, and the retry loop waits for it to come back.
        runCatching { Graph.ble.autoConnect() }

        // osmdroid identifies your app to the tile server with this user agent.
        // OSM's public tiles are meant for light personal use only.
        val config = Configuration.getInstance()
        config.userAgentValue = packageName
        config.osmdroidBasePath = File(cacheDir, "osmdroid")
        config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles")
    }
}
