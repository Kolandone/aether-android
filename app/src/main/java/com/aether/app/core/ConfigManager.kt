package com.aether.app.core

import android.content.Context
import android.content.SharedPreferences
import com.aether.app.model.AetherConfig
import com.google.gson.Gson

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("aether_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_CONFIG = "config_v2"
    }

    fun saveConfig(config: AetherConfig) {
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
    }

    fun loadConfig(): AetherConfig {
        val json = prefs.getString(KEY_CONFIG, null) ?: return AetherConfig()
        return try {
            gson.fromJson(json, AetherConfig::class.java)
        } catch (e: Exception) {
            AetherConfig()
        }
    }
}
