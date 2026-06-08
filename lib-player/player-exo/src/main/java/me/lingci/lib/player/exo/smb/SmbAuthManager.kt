package me.lingci.lib.player.exo.smb

import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import me.lingci.lib.base.storage.smb.SmbAuthToken

object SmbAuthManager {

    // 获取基础上下文
    private val baseContext = SingletonContext.getInstance()

    /**
     * 生成认证上下文
     * @param username 用户名，匿名登录传 null
     * @param password 密码，匿名登录传 null
     * @param domain 域名/工作组，通常传 null 或 "WORKGROUP"
     */
    fun getContext(username: String?, password: String?, domain: String? = null): CIFSContext {
        val credentials = SmbAuthToken.normalize(username, password, domain)
        return if (credentials.username.isNullOrBlank()) {
            // 匿名登录
            baseContext.withAnonymousCredentials()
        } else {
            // 账号密码登录
            val auth = NtlmPasswordAuthenticator(
                credentials.domain,
                credentials.username,
                credentials.password.orEmpty()
            )
            baseContext.withCredentials(auth)
        }
    }

}
