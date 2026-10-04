package com.pypath.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "pypath_settings")

/** App preferences, kept apart from learning progress so "Reset progress" doesn't touch them. */
interface SettingsRepository {
    val soundEffects: Flow<Boolean>
    suspend fun setSoundEffects(enabled: Boolean)
}

class LocalSettingsRepository(private val context: Context) : SettingsRepository {
    private val soundKey = booleanPreferencesKey("sound_effects")

    override val soundEffects: Flow<Boolean> = context.settingsStore.data.map { it[soundKey] ?: true }

    override suspend fun setSoundEffects(enabled: Boolean) {
        context.settingsStore.edit { it[soundKey] = enabled }
    }
}
