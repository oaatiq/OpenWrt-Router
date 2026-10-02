package io.wrtpilot.core.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wrtpilot.core.domain.OuiTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** MAC vendor names from the bundled IEEE table (looked up on the phone, not the router). */
@Singleton
class VendorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val _table = MutableStateFlow<OuiTable?>(null)

    /** Emits once the table is loaded; collect it to re-render vendor names. */
    val table: StateFlow<OuiTable?> = _table.asStateFlow()

    suspend fun load(): OuiTable = mutex.withLock {
        _table.value ?: withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("oui.tsv").bufferedReader().use { OuiTable.parse(it) }
            }.getOrElse { OuiTable.empty() }
        }.also { _table.value = it }
    }

    fun vendor(mac: String): String? = _table.value?.lookup(mac)
}
