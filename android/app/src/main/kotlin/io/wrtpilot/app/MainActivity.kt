package io.wrtpilot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import io.wrtpilot.app.ui.WrtPilotRoot
import kotlinx.coroutines.flow.MutableStateFlow

/** A notification tap: open this device on this router. */
data class DeepLink(val routerId: Long, val mac: String?)

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val deepLinks = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLinks.value = parse(intent)
        setContent {
            WrtPilotRoot(
                deepLinks = deepLinks,
                onDeepLinkHandled = { deepLinks.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parse(intent)?.let { deepLinks.value = it }
    }

    private fun parse(intent: Intent?): DeepLink? {
        val routerId = intent?.getLongExtra(EXTRA_ROUTER_ID, 0L) ?: 0L
        if (routerId == 0L) return null
        return DeepLink(routerId, intent?.getStringExtra(EXTRA_MAC))
    }

    companion object {
        const val EXTRA_ROUTER_ID = "io.wrtpilot.app.ROUTER_ID"
        const val EXTRA_MAC = "io.wrtpilot.app.MAC"
    }
}
