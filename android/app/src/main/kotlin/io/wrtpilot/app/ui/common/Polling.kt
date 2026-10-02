package io.wrtpilot.app.ui.common

import io.wrtpilot.core.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Refresh loop that only runs while the screen collects it (merged into a
 * StateFlow started WhileSubscribed): foreground polling stops as soon as
 * the screen is not visible. [tick] receives the iteration number and may
 * launch parallel refreshes; the next tick waits for all of them.
 */
fun pollingFlow(
    settings: SettingsRepository,
    before: suspend () -> Unit = {},
    tick: suspend CoroutineScope.(Int) -> Unit,
): Flow<Nothing> = flow<Nothing> {
    before()
    var i = 0
    while (true) {
        coroutineScope { tick(i) }
        i++
        delay(settings.current().pollSeconds * 1000L)
    }
}
