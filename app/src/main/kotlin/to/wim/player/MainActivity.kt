package to.wim.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.wim.player.data.model.AppSettings
import to.wim.player.ui.AppNavigation
import to.wim.player.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as PlayerApp).graph

    /** True when the notification was tapped: show the queue. */
    private val openQueue = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // App Remote asks for permission from a screen the first time it connects.
        graph.player.useActivity(this)
        if (savedInstanceState == null) receive(intent)
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            val current = settings ?: return@setContent
            AppTheme(themeMode = current.themeMode, dynamicColor = current.dynamicColor) {
                AppNavigation(openQueue = openQueue.value, onQueueOpened = { openQueue.value = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_QUEUE) openQueue.value = true
    }

    override fun onStop() {
        super.onStop()
        graph.flushCaches()
    }

    companion object {
        const val ACTION_SHOW_QUEUE = "to.wim.player.SHOW_QUEUE"
    }
}
