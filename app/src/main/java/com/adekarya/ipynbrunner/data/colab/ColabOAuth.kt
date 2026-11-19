package com.adekarya.ipynbrunner.data.colab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.adekarya.ipynbrunner.data.SettingsStore
import com.adekarya.ipynbrunner.data.network.ApiException
import com.adekarya.ipynbrunner.data.network.DefaultHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class ColabAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OAuth 2.0 authorization-code + PKCE flow for the official Colab API.
 *
 * The user creates an OAuth client of type "Desktop app" in their own Google
 * Cloud project. Google allows loopback redirects with arbitrary ports for
 * this client type, so the app opens a short-lived local HTTP listener on
 * 127.0.0.1, opens the consent page in a Custom Tab, and exchanges the
 * returned code for access + refresh tokens. Tokens are encrypted at rest.
 */
class ColabOAuth(
    private val settings: SettingsStore,
    private val http: OkHttpClient = DefaultHttpClient,
) {
    suspend fun authenticate(context: Context): String = withContext(Dispatchers.IO) {
        val current = settings.load()
        val clientId = current.colabClientId.trim()
        if (clientId.isBlank()) {
            throw ColabAuthException("Isi OAuth Client ID terlebih dahulu di Pengaturan.")
        }
        val clientSecret = settings.colabClientSecret()
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(24)

        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val redirectUri = "http://127.0.0.1:$port/"
        try {
            val result = coroutineScope {
                val receiver = async(Dispatchers.IO) { receiveCode(server, state) }
                withContext(Dispatchers.Main) {
                    launchConsent(context, buildAuthUrl(clientId, redirectUri, challenge, state))
                }
                try {
                    withTimeout(TOKEN_TIMEOUT_MS) { receiver.await() }
                } catch (e: Exception) {
                    receiver.cancel()
                    throw e
                }
            }

            val token = exchangeCode(
                clientId = clientId,
                clientSecret = clientSecret,
                code = result,
                redirectUri = redirectUri,
                verifier = verifier,
            )
            settings.setColabTokens(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAtEpochMillis = token.expiresAt,
                account = token.email ?: current.colabAccount,
            )
            token.accessToken
        } finally {
            runCatching { server.close() }
        }
    }

    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val current = settings.load()
        val access = settings.getColabAccessToken()
        val expiry = settings.colabExpiry()
        if (!access.isNullOrBlank() && expiry - EXPIRY_SKEW_MS > System.currentTimeMillis()) {
            return@withContext access
        }
        val refresh = settings.getColabRefreshToken()
        if (refresh.isNullOrBlank()) {
            throw ColabAuthException("Belum login ke Colab. Buka Pengaturan lalu hubungkan akun Google.")
        }
        val clientId = current.colabClientId
        if (clientId.isBlank()) {
            throw ColabAuthException("OAuth Client ID kosong. Login ulang dari Pengaturan.")
        }
        val refreshed = refreshToken(
            clientId = clientId,
            clientSecret = settings.colabClientSecret(),
            refreshToken = refresh,
        )
        settings.setColabTokens(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken ?: refresh,
            expiresAtEpochMillis = refreshed.expiresAt,
            account = current.colabAccount,
        )
        refreshed.accessToken
    }

    fun quotaProject(): String = settings.load().colabQuotaProject.trim()

    private suspend fun exchangeCode(
        clientId: String,
        clientSecret: String?,
        code: String,
        redirectUri: String,
        verifier: String,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", clientId)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .apply { if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret) }
            .build()
        return requestToken(form)
    }

    private suspend fun refreshToken(
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .apply { if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret) }
            .build()
        return requestToken(form)
    }

    private suspend fun requestToken(form: FormBody): TokenResponse {
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(form)
            .header("Accept", "application/json")
            .build()
        val response = http.newCall(request).awaitTokenResponse()
        val body = response.use { it.body?.string().orEmpty() }
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw ColabAuthException("Respons token Google tidak valid.", it)
        }
        val error = json.string("error")
        if (!error.isNullOrBlank()) {
            val description = json.string("error_description") ?: error
            throw ColabAuthException(
                when (error) {
                    "access_denied" -> "Akses Google ditolak. Pastikan email Anda terdaftar sebagai test user pada OAuth consent screen."
                    "invalid_grant" -> "Kode/token OAuth sudah tidak berlaku. Silakan login ulang."
                    "invalid_client" -> "OAuth Client ID/secret salah. Periksa konfigurasi Google Cloud."
                    else -> "OAuth gagal: $description"
                },
            )
        }
        val access = json.string("access_token")
            ?: throw ColabAuthException("Google tidak mengembalikan access token.")
        val expiresIn = json.string("expires_in")?.toLongOrNull() ?: 3600L
        val refresh = json.string("refresh_token")
        val email = json.string("id_token")?.let(::emailFromIdToken)
        return TokenResponse(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            email = email,
        )
    }

    private fun buildAuthUrl(
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
    ): String {
        val scope = "openid email profile https://www.googleapis.com/auth/colaboratory"
        return Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", scope)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("include_granted_scopes", "true")
            .build()
            .toString()
    }

    private fun launchConsent(context: Context, url: String) {
        val customTabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .build()
        val intent: Intent = customTabs.intent.apply {
            data = url.toUri()
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw ColabAuthException("Tidak bisa membuka browser untuk login Google.", e)
        }
    }

    private fun receiveCode(server: ServerSocket, expectedState: String): String {
        server.soTimeout = TOKEN_TIMEOUT_MS.toInt()
        val socket = server.accept()
        return socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            val target = requestLine.split(" ").getOrNull(1).orEmpty()
            val parsed = Uri.parse("http://127.0.0.1$target")
            val error = parsed.getQueryParameter("error")
            val code = parsed.getQueryParameter("code")
            val state = parsed.getQueryParameter("state")
            val message = when {
                !error.isNullOrBlank() -> {
                    val desc = parsed.getQueryParameter("error_description") ?: error
                    "Login dibatalkan atau ditolak: $desc"
                }
                state != expectedState -> "State OAuth tidak cocok. Proses login dibatalkan."
                code.isNullOrBlank() -> "Google tidak mengirim authorization code."
                else -> null
            }
            val html = if (message == null) {
                "<h1>Login berhasil</h1><p>Kembali ke aplikasi Notebook Runner.</p>"
            } else {
                "<h1>Login gagal</h1><p>${escapeHtml(message)}</p>"
            }
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Connection: close\r\n" +
                "Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                PAGE_HTML.format(html)
            client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            if (message != null) throw ColabAuthException(message)
            code!!
        }
    }

    private suspend fun okhttp3.Call.awaitTokenResponse(): okhttp3.Response =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ColabAuthException("Tidak bisa menghubungi server Google: ${e.message}", e),
                            )
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (continuation.isActive) continuation.resume(response) else response.close()
                    }
                },
            )
            continuation.invokeOnCancellation { runCatching { cancel() } }
        }

    private fun emailFromIdToken(idToken: String): String? {
        return runCatching {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                .get("email")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return base64Url(data)
    }

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long,
        val email: String?,
    )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val TOKEN_TIMEOUT_MS = 180_000L
        const val EXPIRY_SKEW_MS = 60_000L
        val PAGE_HTML = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:system-ui,sans-serif;background:#12111c;color:#f4f1ff;display:grid;place-items:center;min-height:100vh;margin:0;padding:24px}
            div{max-width:420px;background:#1d1b2b;border:1px solid #302c45;border-radius:20px;padding:28px}
            h1{font-size:22px;margin:0 0 8px}p{color:#c9c3df;line-height:1.5}</style></head>
            <body><div>%s</div></body></html>
        """.trimIndent()
    }
}
