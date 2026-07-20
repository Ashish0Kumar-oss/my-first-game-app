package com.example.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "temple_escape_3d_prefs")

class GameDataStore(private val context: Context) {

    companion object {
        val HIGH_SCORE = intPreferencesKey("high_score")
        val TOTAL_COINS = intPreferencesKey("total_coins")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val MUSIC_ENABLED = booleanPreferencesKey("music_enabled")
        val UNLOCKED_GOLDEN_SKIN = booleanPreferencesKey("unlocked_golden_skin")
        val PLAYER_LEVEL = intPreferencesKey("player_level")
    }

    val highScoreFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { prefs -> prefs[HIGH_SCORE] ?: 0 }

    val totalCoinsFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { prefs -> prefs[TOTAL_COINS] ?: 0 }

    val soundEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { prefs -> prefs[SOUND_ENABLED] ?: true }

    val musicEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { prefs -> prefs[MUSIC_ENABLED] ?: true }

    val unlockedGoldenSkinFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { prefs -> prefs[UNLOCKED_GOLDEN_SKIN] ?: false }

    val playerLevelFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { prefs -> prefs[PLAYER_LEVEL] ?: 1 }

    suspend fun saveHighScore(score: Int) {
        context.dataStore.edit { prefs ->
            val current = prefs[HIGH_SCORE] ?: 0
            if (score > current) {
                prefs[HIGH_SCORE] = score
            }
        }
    }

    suspend fun addCoins(coins: Int) {
        context.dataStore.edit { prefs ->
            val current = prefs[TOTAL_COINS] ?: 0
            prefs[TOTAL_COINS] = current + coins
        }
    }

    suspend fun spendCoins(amount: Int): Boolean {
        var success = false
        context.dataStore.edit { prefs ->
            val current = prefs[TOTAL_COINS] ?: 0
            if (current >= amount) {
                prefs[TOTAL_COINS] = current - amount
                success = true
            }
        }
        return success
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SOUND_ENABLED] = enabled
        }
    }

    suspend fun setMusicEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[MUSIC_ENABLED] = enabled
        }
    }

    suspend fun unlockGoldenSkin() {
        context.dataStore.edit { prefs ->
            prefs[UNLOCKED_GOLDEN_SKIN] = true
        }
    }

    suspend fun incrementPlayerLevel() {
        context.dataStore.edit { prefs ->
            val current = prefs[PLAYER_LEVEL] ?: 1
            prefs[PLAYER_LEVEL] = current + 1
        }
    }
}
