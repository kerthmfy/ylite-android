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

        // osmdroid identifies your app to the tile server with this user agent.
        // OSM's public tiles are meant for light personal use only.
        val config = Configuration.getInstance()
        config.userAgentValue = packageName
        config.osmdroidBasePath = File(cacheDir, "osmdroid")
        config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles")
    }
}
