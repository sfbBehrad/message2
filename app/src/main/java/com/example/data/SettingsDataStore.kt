package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_bridge_settings")

data class AppSettings(
    val passphrase: String = "NRF24_SECURE_KEY_2026_TACTICAL",
    val targetNode: String = "ESP_Node_1",
    val tcpRetryLimit: Int = 10, // 10, 100, or Int.MAX_VALUE for infinite
    val pingIntervalSec: Int = 5,
    val resendIntervalMs: Long = 1000L,
    val defaultImageQuality: Int = 50,
    val transportMode: String = "HARDWARE_BLUETOOTH",
    val simulatedPacketLoss: Int = 0
)

class SettingsDataStore(private val context: Context) {

    companion object {
        val KEY_PASSPHRASE = stringPreferencesKey("key_passphrase")
        val KEY_TARGET_NODE = stringPreferencesKey("key_target_node")
        val KEY_TCP_RETRY_LIMIT = intPreferencesKey("key_tcp_retry_limit")
        val KEY_PING_INTERVAL_SEC = intPreferencesKey("key_ping_interval_sec")
        val KEY_RESEND_INTERVAL_MS = longPreferencesKey("key_resend_interval_ms")
        val KEY_IMAGE_QUALITY = intPreferencesKey("key_image_quality")
        val KEY_TRANSPORT_MODE = stringPreferencesKey("key_transport_mode")
        val KEY_SIMULATED_PACKET_LOSS = intPreferencesKey("key_simulated_packet_loss")

        @Volatile
        private var INSTANCE: SettingsDataStore? = null

        fun getInstance(context: Context): SettingsDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                passphrase = preferences[KEY_PASSPHRASE] ?: "NRF24_SECURE_KEY_2026_TACTICAL",
                targetNode = preferences[KEY_TARGET_NODE] ?: "ESP_Node_1",
                tcpRetryLimit = preferences[KEY_TCP_RETRY_LIMIT] ?: 10,
                pingIntervalSec = preferences[KEY_PING_INTERVAL_SEC] ?: 5,
                resendIntervalMs = preferences[KEY_RESEND_INTERVAL_MS] ?: 1000L,
                defaultImageQuality = preferences[KEY_IMAGE_QUALITY] ?: 50,
                transportMode = preferences[KEY_TRANSPORT_MODE] ?: "HARDWARE_BLUETOOTH",
                simulatedPacketLoss = preferences[KEY_SIMULATED_PACKET_LOSS] ?: 0
            )
        }

    suspend fun updatePassphrase(passphrase: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PASSPHRASE] = passphrase
        }
    }

    suspend fun updateTargetNode(nodeName: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TARGET_NODE] = nodeName
        }
    }

    suspend fun updateTcpRetryLimit(limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TCP_RETRY_LIMIT] = limit
        }
    }

    suspend fun updatePingInterval(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PING_INTERVAL_SEC] = seconds
        }
    }

    suspend fun updateResendInterval(milliseconds: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RESEND_INTERVAL_MS] = milliseconds
        }
    }

    suspend fun updateDefaultImageQuality(quality: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IMAGE_QUALITY] = quality.coerceIn(10, 100)
        }
    }

    suspend fun updateTransportMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TRANSPORT_MODE] = mode
        }
    }

    suspend fun updateSimulatedPacketLoss(loss: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SIMULATED_PACKET_LOSS] = loss.coerceIn(0, 100)
        }
    }
}
