package com.dshbox.app.bridge

import com.dshbox.app.bridge.api.BridgeApi
import com.dshbox.app.bridge.security.BridgeCapability
import com.dshbox.app.bridge.security.BridgePolicy
import com.dshbox.app.bridge.security.OriginVerifier
import com.dshbox.app.bridge.security.TrustLevel

/**
 * Entry point for WebView JS bridge calls. Every capability call must pass
 * through this router. The router does not trust localhost by itself.
 *
 * Token enforcement: a call is only treated as [TrustLevel.TRUSTED_DSH_WEBUI]
 * when the URL is localhost/127.0.0.1 AND the provided capability token matches
 * [expectedDshToken]. Without a valid token the URL is classified as
 * [TrustLevel.LOCAL_WEB] (no bridge access), even though it is local.
 * This prevents any malicious local web page from using the bridge.
 */
class BridgeRouter(
    private val delegate: BridgeApi,
    private val expectedDshToken: String,
) {
    private val sessionCapabilities = mutableSetOf<BridgeCapability>()
    private var userAuthorizedHighRisk = false

    fun classify(url: String): TrustLevel = OriginVerifier.classify(url)

    /**
     * Build a policy for a bridge call. [capabilityToken] is the token from
     * the WebView's JS bridge handshake. If the token does not match
     * [expectedDshToken], the trust level is downgraded to [TrustLevel.LOCAL_WEB]
     * even when the URL is localhost, which disables all bridge capabilities.
     */
    fun resolvePolicy(url: String, capabilityToken: String?): BridgePolicy {
        val isTrusted = OriginVerifier.isTrustedDshWebUi(url, capabilityToken, expectedDshToken)
        // If the token is missing or invalid, fall back to the pure-URL
        // classification. LOCAL_WEB has no bridge access; PUBLIC_WEB has none.
        val trust = if (isTrusted) TrustLevel.TRUSTED_DSH_WEBUI else classify(url)
        return BridgePolicy(
            trustLevel = trust,
            grantedCapabilities = sessionCapabilities.toSet(),
            userAuthorizedHighRisk = userAuthorizedHighRisk,
        )
    }

    /**
     * Legacy builder — kept for backward compatibility. Does NOT enforce the
     * capability token. Prefer [resolvePolicy] for new code.
     */
    fun buildPolicy(url: String): BridgePolicy {
        val trust = classify(url)
        return BridgePolicy(
            trustLevel = trust,
            grantedCapabilities = sessionCapabilities.toSet(),
            userAuthorizedHighRisk = userAuthorizedHighRisk,
        )
    }

    fun grant(capabilities: Set<BridgeCapability>, userAuthorizedHighRisk: Boolean) {
        sessionCapabilities += capabilities
        this.userAuthorizedHighRisk = userAuthorizedHighRisk
    }

    fun revokeAll() {
        sessionCapabilities.clear()
        userAuthorizedHighRisk = false
    }

    val api: BridgeApi = delegate
}
