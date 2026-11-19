package com.adekarya.ipynbrunner.data

import android.content.Context
import com.adekarya.ipynbrunner.data.model.Backend
import com.adekarya.ipynbrunner.data.model.ColabSpecKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val kaggleUsername: String = "",
    val hasKaggleKey: Boolean = false,
    val colabClientId: String = "",
    val hasColabClientSecret: Boolean = false,
    val colabQuotaProject: String = "",
    val colabSignedIn: Boolean = false,
    val colabAccount: String? = null,
    val colabRuntimeName: String? = null,
    val colabRuntimeLabel: String? = null,
    val colabSpec: ColabSpecKey = ColabSpecKey(),
    val lastBackend: Backend = Backend.COLAB,
    val keepAliveCell: Boolean = true,
    val keepAliveAfterRun: Boolean = true,
    val kaggleUseGpu: Boolean = false,
    val kaggleInternet: Boolean = true,
    val kagglePrivate: Boolean = true,
)

/**
 * Non-secret preferences live in the regular SharedPreferences file. API
 * keys, client secrets and OAuth tokens are stored through [SecureStore].
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun load(): AppSettings = AppSettings(
        kaggleUsername = prefs.getString(KEY_KAGGLE_USER, "").orEmpty(),
        hasKaggleKey = !secure.get(SECURE_KAGGLE_KEY).isNullOrBlank(),
        colabClientId = prefs.getString(KEY_COLAB_CLIENT_ID, "").orEmpty(),
        hasColabClientSecret = !secure.get(SECURE_COLAB_CLIENT_SECRET).isNullOrBlank(),
        colabQuotaProject = prefs.getString(KEY_COLAB_QUOTA_PROJECT, "").orEmpty(),
        colabSignedIn = !getColabRefreshToken().isNullOrBlank() || !getColabAccessToken().isNullOrBlank(),
        colabAccount = prefs.getString(KEY_COLAB_ACCOUNT, null),
        colabRuntimeName = prefs.getString(KEY_COLAB_RUNTIME, null),
        colabRuntimeLabel = prefs.getString(KEY_COLAB_RUNTIME_LABEL, null),
        colabSpec = ColabSpecKey(
            variant = prefs.getString(KEY_COLAB_VARIANT, "VARIANT_CPU").orEmpty(),
            accelerator = prefs.getString(KEY_COLAB_ACCELERATOR, "NONE").orEmpty(),
            shape = prefs.getString(KEY_COLAB_SHAPE, "SHAPE_STANDARD").orEmpty(),
        ),
        lastBackend = Backend.fromName(prefs.getString(KEY_LAST_BACKEND, null)),
        keepAliveCell = prefs.getBoolean(KEY_KEEPALIVE_CELL, true),
        keepAliveAfterRun = prefs.getBoolean(KEY_KEEPALIVE_AFTER, true),
        kaggleUseGpu = prefs.getBoolean(KEY_KAGGLE_GPU, false),
        kaggleInternet = prefs.getBoolean(KEY_KAGGLE_INTERNET, true),
        kagglePrivate = prefs.getBoolean(KEY_KAGGLE_PRIVATE, true),
    )

    fun refresh(): AppSettings {
        val newValue = load()
        _state.value = newValue
        return newValue
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val current = load()
        val updated = transform(current)
        prefs.edit()
            .putString(KEY_KAGGLE_USER, updated.kaggleUsername)
            .putString(KEY_COLAB_CLIENT_ID, updated.colabClientId)
            .putString(KEY_COLAB_QUOTA_PROJECT, updated.colabQuotaProject)
            .putString(KEY_COLAB_ACCOUNT, updated.colabAccount)
            .putString(KEY_COLAB_RUNTIME, updated.colabRuntimeName)
            .putString(KEY_COLAB_RUNTIME_LABEL, updated.colabRuntimeLabel)
            .putString(KEY_COLAB_VARIANT, updated.colabSpec.variant)
            .putString(KEY_COLAB_ACCELERATOR, updated.colabSpec.accelerator)
            .putString(KEY_COLAB_SHAPE, updated.colabSpec.shape)
            .putString(KEY_LAST_BACKEND, updated.lastBackend.name)
            .putBoolean(KEY_KEEPALIVE_CELL, updated.keepAliveCell)
            .putBoolean(KEY_KEEPALIVE_AFTER, updated.keepAliveAfterRun)
            .putBoolean(KEY_KAGGLE_GPU, updated.kaggleUseGpu)
            .putBoolean(KEY_KAGGLE_INTERNET, updated.kaggleInternet)
            .putBoolean(KEY_KAGGLE_PRIVATE, updated.kagglePrivate)
            .apply()
        _state.value = updated
        return updated
    }

    fun setKaggleKey(key: String?) {
        secure.put(SECURE_KAGGLE_KEY, key?.takeIf { it.isNotBlank() })
        refresh()
    }

    fun kaggleKey(): String? = secure.get(SECURE_KAGGLE_KEY)

    fun setColabClientSecret(secret: String?) {
        secure.put(SECURE_COLAB_CLIENT_SECRET, secret?.takeIf { it.isNotBlank() })
        refresh()
    }

    fun colabClientSecret(): String? = secure.get(SECURE_COLAB_CLIENT_SECRET)

    fun setColabTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAtEpochMillis: Long,
        account: String?,
    ) {
        secure.put(SECURE_COLAB_ACCESS, accessToken)
        if (!refreshToken.isNullOrBlank()) secure.put(SECURE_COLAB_REFRESH, refreshToken)
        prefs.edit()
            .putLong(KEY_COLAB_EXPIRY, expiresAtEpochMillis)
            .putString(KEY_COLAB_ACCOUNT, account)
            .apply()
        refresh()
    }

    fun getColabAccessToken(): String? = secure.get(SECURE_COLAB_ACCESS)
    fun getColabRefreshToken(): String? = secure.get(SECURE_COLAB_REFRESH)
    fun colabExpiry(): Long = prefs.getLong(KEY_COLAB_EXPIRY, 0L)

    fun clearColabTokens() {
        secure.remove(SECURE_COLAB_ACCESS)
        secure.remove(SECURE_COLAB_REFRESH)
        prefs.edit()
            .remove(KEY_COLAB_EXPIRY)
            .remove(KEY_COLAB_ACCOUNT)
            .remove(KEY_COLAB_RUNTIME)
            .remove(KEY_COLAB_RUNTIME_LABEL)
            .apply()
        refresh()
    }

    fun setKaggleUsername(value: String) {
        prefs.edit().putString(KEY_KAGGLE_USER, value.trim()).apply()
        refresh()
    }

    fun setColabClientId(value: String) {
        prefs.edit().putString(KEY_COLAB_CLIENT_ID, value.trim()).apply()
        refresh()
    }

    fun setColabQuotaProject(value: String) {
        prefs.edit().putString(KEY_COLAB_QUOTA_PROJECT, value.trim()).apply()
        refresh()
    }

    fun setColabRuntime(name: String?, label: String?) {
        prefs.edit()
            .putString(KEY_COLAB_RUNTIME, name)
            .putString(KEY_COLAB_RUNTIME_LABEL, label)
            .apply()
        refresh()
    }

    fun updateRunDefaults(
        backend: Backend? = null,
        spec: ColabSpecKey? = null,
        keepAliveCell: Boolean? = null,
        keepAliveAfterRun: Boolean? = null,
        kaggleGpu: Boolean? = null,
        kaggleInternet: Boolean? = null,
        kagglePrivate: Boolean? = null,
    ) {
        prefs.edit().apply {
            if (backend != null) putString(KEY_LAST_BACKEND, backend.name)
            if (spec != null) {
                putString(KEY_COLAB_VARIANT, spec.variant)
                putString(KEY_COLAB_ACCELERATOR, spec.accelerator)
                putString(KEY_COLAB_SHAPE, spec.shape)
            }
            if (keepAliveCell != null) putBoolean(KEY_KEEPALIVE_CELL, keepAliveCell)
            if (keepAliveAfterRun != null) putBoolean(KEY_KEEPALIVE_AFTER, keepAliveAfterRun)
            if (kaggleGpu != null) putBoolean(KEY_KAGGLE_GPU, kaggleGpu)
            if (kaggleInternet != null) putBoolean(KEY_KAGGLE_INTERNET, kaggleInternet)
            if (kagglePrivate != null) putBoolean(KEY_KAGGLE_PRIVATE, kagglePrivate)
        }.apply()
        refresh()
    }

    private companion object {
        const val KEY_KAGGLE_USER = "kaggle_username"
        const val KEY_COLAB_CLIENT_ID = "colab_client_id"
        const val KEY_COLAB_QUOTA_PROJECT = "colab_quota_project"
        const val KEY_COLAB_ACCOUNT = "colab_account"
        const val KEY_COLAB_RUNTIME = "colab_runtime"
        const val KEY_COLAB_RUNTIME_LABEL = "colab_runtime_label"
        const val KEY_COLAB_VARIANT = "colab_variant"
        const val KEY_COLAB_ACCELERATOR = "colab_accelerator"
        const val KEY_COLAB_SHAPE = "colab_shape"
        const val KEY_COLAB_EXPIRY = "colab_expiry"
        const val KEY_LAST_BACKEND = "last_backend"
        const val KEY_KEEPALIVE_CELL = "keepalive_cell"
        const val KEY_KEEPALIVE_AFTER = "keepalive_after"
        const val KEY_KAGGLE_GPU = "kaggle_gpu"
        const val KEY_KAGGLE_INTERNET = "kaggle_internet"
        const val KEY_KAGGLE_PRIVATE = "kaggle_private"

        const val SECURE_KAGGLE_KEY = "kaggle_api_key"
        const val SECURE_COLAB_CLIENT_SECRET = "colab_client_secret"
        const val SECURE_COLAB_ACCESS = "colab_access_token"
        const val SECURE_COLAB_REFRESH = "colab_refresh_token"
    }
}
