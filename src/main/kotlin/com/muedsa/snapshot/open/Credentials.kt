package com.muedsa.snapshot.open

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import java.security.MessageDigest

/** 管理令牌对应的身份名称，出现在日志与指标标签中。 */
internal const val ADMIN_TOKEN_IDENTITY = "admin-token"

/** 匿名身份的指标标签，不包含 IP，避免指标基数与隐私问题。 */
internal const val ANONYMOUS_IDENTITY = "anonymous"

/** 单个 API Key 凭据。 */
internal data class ApiCredential(
    val name: String,
    val key: String,
    val admin: Boolean = false,
)

/** 请求身份：匿名、具名凭据（含管理凭据）。 */
internal sealed interface CallIdentity {
    val isAuthenticated: Boolean
    val isAdmin: Boolean

    /** 用于指标标签的稳定名称。 */
    val label: String

    data class Anonymous(val remoteHost: String) : CallIdentity {
        override val isAuthenticated: Boolean = false
        override val isAdmin: Boolean = false
        override val label: String = ANONYMOUS_IDENTITY
    }

    data class Credential(val name: String, override val isAdmin: Boolean) : CallIdentity {
        override val isAuthenticated: Boolean = true
        override val label: String = name
    }
}

/**
 * 凭据存储：按 API Key 的 SHA-256 摘要查找身份。
 *
 * 存摘要而不是明文，查找不逐字符比较密钥；管理令牌使用定长比较。
 */
internal class CredentialStore(
    credentials: List<ApiCredential>,
    private val adminToken: String?,
) {
    private val byDigest: Map<String, ApiCredential> = credentials.associateBy { sha256Hex(it.key) }
    private val adminTokenDigest: String? = adminToken?.let(::sha256Hex)

    /** 是否配置了至少一个客户端凭据；决定开放接口是否强制鉴权。 */
    val requiresCredential: Boolean = credentials.isNotEmpty()

    val credentialNames: Set<String> = credentials.map { it.name }.toSet()

    fun resolve(suppliedKey: String?): CallIdentity? {
        if (suppliedKey.isNullOrBlank()) return null
        val digest = sha256Hex(suppliedKey)
        byDigest[digest]?.let { return CallIdentity.Credential(it.name, it.admin) }
        if (adminTokenDigest != null && MessageDigest.isEqual(
                adminTokenDigest.toByteArray(Charsets.UTF_8),
                digest.toByteArray(Charsets.UTF_8),
            )
        ) {
            return CallIdentity.Credential(ADMIN_TOKEN_IDENTITY, isAdmin = true)
        }
        return null
    }
}

private val CredentialStoreKey = AttributeKey<CredentialStore>("SnapshotCredentialStore")
private val IdentityKey = AttributeKey<CallIdentity>("SnapshotCallIdentity")

/** 每个应用只构建一次凭据存储；测试可通过预先写入该属性注入管理令牌。 */
internal fun Application.credentialStore(): CredentialStore {
    attributes.getOrNull(CredentialStoreKey)?.let { return it }
    val config = snapshotConfig()
    return CredentialStore(config.credentials, config.admin.token).also { attributes.put(CredentialStoreKey, it) }
}

internal fun Application.installCredentialStore(store: CredentialStore) {
    attributes.put(CredentialStoreKey, store)
}

/** 解析并缓存本次请求的身份。 */
internal fun ApplicationCall.identity(): CallIdentity {
    attributes.getOrNull(IdentityKey)?.let { return it }
    val identity = application.credentialStore().resolve(suppliedCredential())
        ?: CallIdentity.Anonymous(remoteHostLabel())
    attributes.put(IdentityKey, identity)
    return identity
}

/** 支持 `X-API-Key: <key>` 与 `Authorization: Bearer <key>` 两种携带方式。 */
internal fun ApplicationCall.suppliedCredential(): String? =
    request.header(API_KEY_HEADER)?.takeIf(String::isNotBlank)
        ?: request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.takeIf(String::isNotBlank)

internal fun ApplicationCall.remoteHostLabel(): String = request.origin.remoteHost

/** 分层限流的桶键：不同身份类别互不影响。 */
internal fun ApplicationCall.rateLimitIdentity(scope: String): String = when (val identity = identity()) {
    is CallIdentity.Anonymous -> "$scope:ip:${identity.remoteHost}"
    is CallIdentity.Credential -> "$scope:key:${identity.name}"
}

/** 管理接口限流的桶键：已认证管理员按凭据分桶，其余按来源 IP。 */
internal fun ApplicationCall.adminRateLimitIdentity(): String = when (val identity = identity()) {
    is CallIdentity.Anonymous -> "admin:ip:${identity.remoteHost}"
    is CallIdentity.Credential -> "admin:key:${identity.name}"
}

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
