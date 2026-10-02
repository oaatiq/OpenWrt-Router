package io.wrtpilot.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val activeRouterId: Long = 0,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** foreground refresh interval of live screens */
    val pollSeconds: Int = 3,
    val notifyNewDevices: Boolean = true,
    val notifyQuota: Boolean = true,
    /** background sync interval (WorkManager minimum is 15) */
    val backgroundMinutes: Int = 15,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val activeRouter = longPreferencesKey("active_router")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val poll = intPreferencesKey("poll_seconds")
        val notifyNew = booleanPreferencesKey("notify_new_devices")
        val notifyQuota = booleanPreferencesKey("notify_quota")
        val background = intPreferencesKey("background_minutes")
    }

    val settings: Flow<Settings> = context.settingsStore.data.map { p ->
        Settings(
            activeRouterId = p[Keys.activeRouter] ?: 0,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamicColor] ?: true,
            pollSeconds = (p[Keys.poll] ?: 3).coerceIn(1, 60),
            notifyNewDevices = p[Keys.notifyNew] ?: true,
            notifyQuota = p[Keys.notifyQuota] ?: true,
            backgroundMinutes = (p[Keys.background] ?: 15).coerceAtLeast(15),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setActiveRouter(id: Long) = context.settingsStore.edit { it[Keys.activeRouter] = id }

    suspend fun setTheme(mode: ThemeMode) = context.settingsStore.edit { it[Keys.theme] = mode.name }

    suspend fun setDynamicColor(on: Boolean) = context.settingsStore.edit { it[Keys.dynamicColor] = on }

    suspend fun setPollSeconds(s: Int) = context.settingsStore.edit { it[Keys.poll] = s }

    suspend fun setNotifyNewDevices(on: Boolean) = context.settingsStore.edit { it[Keys.notifyNew] = on }

    suspend fun setNotifyQuota(on: Boolean) = context.settingsStore.edit { it[Keys.notifyQuota] = on }

    suspend fun setBackgroundMinutes(m: Int) = context.settingsStore.edit { it[Keys.background] = m }
}
