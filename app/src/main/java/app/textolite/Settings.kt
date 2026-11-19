package app.textolite

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Engine(val shortName: String) {
    OFFLINE("Offline"),
    GEMINI("Gemini"),
    COMPAT("Outra IA")
}

/** Guarda as escolhas do usuário. As chaves de API ficam criptografadas no aparelho. */
class Settings(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "textolite_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("textolite_fallback", Context.MODE_PRIVATE)
    }

    private fun get(key: String, def: String): String = prefs.getString(key, def) ?: def
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    var engine: Engine
        get() = try { Engine.valueOf(get("engine", "OFFLINE")) } catch (e: Exception) { Engine.OFFLINE }
        set(v) = put("engine", v.name)

    var geminiKey: String
        get() = get("gemini_key", "")
        set(v) = put("gemini_key", v)

    var geminiModel: String
        get() = get("gemini_model", "gemini-2.5-flash")
        set(v) = put("gemini_model", v)

    var compatUrl: String
        get() = get("compat_url", "https://openrouter.ai/api/v1")
        set(v) = put("compat_url", v)

    var compatKey: String
        get() = get("compat_key", "")
        set(v) = put("compat_key", v)

    var compatModel: String
        get() = get("compat_model", "")
        set(v) = put("compat_model", v)

    fun isReady(e: Engine): Boolean = when (e) {
        Engine.OFFLINE -> true
        Engine.GEMINI -> geminiKey.isNotBlank() && geminiModel.isNotBlank()
        Engine.COMPAT -> compatKey.isNotBlank() && compatUrl.isNotBlank() && compatModel.isNotBlank()
    }
}
