package com.pypath.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pypath.app.domain.DailyGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "pypath_settings")

/** App preferences, kept apart from learning progress so "Reset progress" doesn't touch them. */
interface SettingsRepository {
    val soundEffects: Flow<Boolean>
    suspend fun setSoundEffects(enabled: Boolean)
    /** Name printed on the course certificate. Kept on the phone only. */
    val certificateName: Flow<String>
    suspend fun setCertificateName(name: String)
    /** Batch 3: daily XP goal (default Regular, 40 XP). A preference, so Reset progress keeps it. */
    val dailyGoal: Flow<DailyGoal>
    suspend fun setDailyGoal(goal: DailyGoal)
}

class LocalSettingsRepository(private val context: Context) : SettingsRepository {
    private val soundKey = booleanPreferencesKey("sound_effects")

    override val soundEffects: Flow<Boolean> = context.settingsStore.data.map { it[soundKey] ?: true }

    override suspend fun setSoundEffects(enabled: Boolean) {
        context.settingsStore.edit { it[soundKey] = enabled }
    }

    private val nameKey = stringPreferencesKey("certificate_name")

    override val certificateName: Flow<String> = context.settingsStore.data.map { it[nameKey] ?: "" }

    override suspend fun setCertificateName(name: String) {
        context.settingsStore.edit { it[nameKey] = name }
    }

    private val goalKey = stringPreferencesKey("daily_goal")

    override val dailyGoal: Flow<DailyGoal> = context.settingsStore.data.map { DailyGoal.fromName(it[goalKey]) }

    override suspend fun setDailyGoal(goal: DailyGoal) {
        context.settingsStore.edit { it[goalKey] = goal.name }
    }
}
