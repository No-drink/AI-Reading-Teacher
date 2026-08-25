package com.joey.aireadingteacher.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.tutorSettingsDataStore by preferencesDataStore(name = "tutor_settings")

data class TutorSettings(
    val provider: String = DEFAULT_PROVIDER,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val globalInstructions: String = DEFAULT_GLOBAL_INSTRUCTIONS,
    val floatingSubtitlesEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_PROVIDER = "OpenAI"
        const val DEFAULT_MODEL = "gpt-realtime-2.1"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_GLOBAL_INSTRUCTIONS =
            "Talk like a concise, capable colleague in a natural two-person conversation. " +
                "Use the latest screen and conversation context; do not make the user repeat it. " +
                "Answer the current question directly without restating it. Start with the answer, " +
                "not a preamble. Omit greetings, acknowledgements, filler, repetition, obvious " +
                "background, offers to help, and unnecessary summaries or disclaimers. Do not say " +
                "phrases such as 'Great question', 'Certainly', 'I understand', 'If you would like', " +
                "or 'Let me know'. Default to one to three short sentences. Expand only when the " +
                "answer genuinely requires it or the user asks for detail."
    }
}

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.tutorSettingsDataStore

    val settings: Flow<TutorSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw exception
        }
        .map { preferences -> preferences.toTutorSettings() }

    suspend fun save(settings: TutorSettings) {
        settings.validate()
        dataStore.edit { preferences ->
            preferences[PROVIDER] = settings.provider
            preferences[MODEL] = settings.model.trim()
            preferences[BASE_URL] = settings.baseUrl.trim().trimEnd('/')
            preferences[GLOBAL_INSTRUCTIONS] = settings.globalInstructions.trim()
            preferences[FLOATING_SUBTITLES_ENABLED] = settings.floatingSubtitlesEnabled
            preferences[SETTINGS_SCHEMA_VERSION] = CURRENT_SETTINGS_SCHEMA_VERSION
        }
    }

    private fun Preferences.toTutorSettings(): TutorSettings {
        val schemaVersion = this[SETTINGS_SCHEMA_VERSION] ?: 0
        val storedModel = this[MODEL]
        val storedInstructions = this[GLOBAL_INSTRUCTIONS]
        return TutorSettings(
            provider = this[PROVIDER] ?: TutorSettings.DEFAULT_PROVIDER,
            model = if (
                schemaVersion < CURRENT_SETTINGS_SCHEMA_VERSION &&
                storedModel == LEGACY_MINI_MODEL
            ) {
                TutorSettings.DEFAULT_MODEL
            } else {
                storedModel ?: TutorSettings.DEFAULT_MODEL
            },
            baseUrl = this[BASE_URL] ?: TutorSettings.DEFAULT_BASE_URL,
            globalInstructions = if (
                schemaVersion < CURRENT_SETTINGS_SCHEMA_VERSION &&
                storedInstructions == LEGACY_GLOBAL_INSTRUCTIONS
            ) {
                TutorSettings.DEFAULT_GLOBAL_INSTRUCTIONS
            } else {
                storedInstructions ?: TutorSettings.DEFAULT_GLOBAL_INSTRUCTIONS
            },
            floatingSubtitlesEnabled = this[FLOATING_SUBTITLES_ENABLED] ?: false,
        )
    }

    companion object {
        private val PROVIDER = stringPreferencesKey("provider")
        private val MODEL = stringPreferencesKey("model")
        private val BASE_URL = stringPreferencesKey("base_url")
        private val GLOBAL_INSTRUCTIONS = stringPreferencesKey("global_instructions")
        private val FLOATING_SUBTITLES_ENABLED =
            booleanPreferencesKey("floating_subtitles_enabled")
        private val SETTINGS_SCHEMA_VERSION = intPreferencesKey("settings_schema_version")
        private const val CURRENT_SETTINGS_SCHEMA_VERSION = 1
        private const val LEGACY_MINI_MODEL = "gpt-realtime-2.1-mini"
        private const val LEGACY_GLOBAL_INSTRUCTIONS =
            "Treat the user as a professional. Reply in the user's language. " +
                "Lead with the conclusion and the most important reasoning. Be concise and direct. " +
                "Skip greetings, repetition, and beginner-level background unless requested."
    }
}

internal fun TutorSettings.validate() {
    require(provider == TutorSettings.DEFAULT_PROVIDER) {
        "Only OpenAI is implemented in this version"
    }
    require(model.isNotBlank()) { "Model is required" }
    require(globalInstructions.length <= 4_000) {
        "Global instructions must be 4,000 characters or fewer"
    }
    require(baseUrl.isNotBlank()) { "Base URL is required" }
    val uri = runCatching { URI(baseUrl.trim()) }
        .getOrElse { throw IllegalArgumentException("Base URL is invalid", it) }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "Base URL must be a valid HTTPS address"
    }
}
