package me.lingci.lib.base.storage.smb

import android.util.Base64
import org.json.JSONObject

object SmbAuthToken {

    private const val PREFIX = "smb:"

    data class Credentials(
        val username: String?,
        val password: String?,
        val domain: String?
    )

    fun encode(username: String?, password: String?, domain: String? = null): String {
        val credentials = normalize(username, password, domain)
        val json = JSONObject()
            .put("username", credentials.username.orEmpty())
            .put("password", credentials.password.orEmpty())
            .put("domain", credentials.domain.orEmpty())
            .toString()
        val payload = Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return PREFIX + payload
    }

    fun decode(token: String?): Credentials? {
        val value = token?.trim()?.takeIf { it.startsWith(PREFIX) } ?: return null
        return try {
            val json = String(
                Base64.decode(value.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val obj = JSONObject(json)
            Credentials(
                username = obj.optString("username").takeIf { it.isNotBlank() },
                password = obj.optString("password").takeIf { it.isNotBlank() },
                domain = obj.optString("domain").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun normalize(username: String?, password: String?, domain: String? = null): Credentials {
        val normalizedPassword = password?.takeIf { it.isNotBlank() }
        val normalizedDomain = domain?.trim()?.takeIf { it.isNotBlank() }
        val rawUsername = username?.trim()?.takeIf { it.isNotBlank() }
            ?: return Credentials(null, normalizedPassword, normalizedDomain)
        if (normalizedDomain != null) {
            return Credentials(rawUsername, normalizedPassword, normalizedDomain)
        }

        val backslashIndex = rawUsername.indexOf('\\')
        if (backslashIndex > 0 && backslashIndex < rawUsername.lastIndex) {
            return Credentials(
                username = rawUsername.substring(backslashIndex + 1),
                password = normalizedPassword,
                domain = rawUsername.substring(0, backslashIndex)
            )
        }

        val semicolonIndex = rawUsername.indexOf(';')
        if (semicolonIndex > 0 && semicolonIndex < rawUsername.lastIndex) {
            return Credentials(
                username = rawUsername.substring(semicolonIndex + 1),
                password = normalizedPassword,
                domain = rawUsername.substring(0, semicolonIndex)
            )
        }

        return Credentials(rawUsername, normalizedPassword, null)
    }
}
