package com.dshbox.app.bridge.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyTest {
    @Test
    fun publicWebDeniesAll() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.PUBLIC_WEB,
            grantedCapabilities = setOf(BridgeCapability.WORKSPACE),
            userAuthorizedHighRisk = true,
        )
        assertFalse(policy.evaluate(BridgeCapability.WORKSPACE).allowed)
    }

    @Test
    fun highRiskRequiresUserAuthorization() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.TRUSTED_DSH_WEBUI,
            grantedCapabilities = setOf(BridgeCapability.COMMAND),
            userAuthorizedHighRisk = false,
        )
        assertFalse(policy.evaluate(BridgeCapability.COMMAND).allowed)
    }

    @Test
    fun clipboardIsHighRiskAndRequiresAuthorization() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.TRUSTED_DSH_WEBUI,
            grantedCapabilities = setOf(BridgeCapability.ANDROID_CLIPBOARD),
            userAuthorizedHighRisk = false,
        )
        assertFalse(policy.evaluate(BridgeCapability.ANDROID_CLIPBOARD).allowed)
    }

    @Test
    fun clipboardAllowedWhenAuthorized() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.TRUSTED_DSH_WEBUI,
            grantedCapabilities = setOf(BridgeCapability.ANDROID_CLIPBOARD),
            userAuthorizedHighRisk = true,
        )
        assertTrue(policy.evaluate(BridgeCapability.ANDROID_CLIPBOARD).allowed)
    }

    @Test
    fun ungrantedCapabilityIsDeniedEvenWhenAuthorized() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.TRUSTED_DSH_WEBUI,
            grantedCapabilities = setOf(BridgeCapability.FILESYSTEM_READ),
            userAuthorizedHighRisk = true,
        )
        // COMMAND is high risk and NOT granted: must be denied as ungranted.
        assertFalse(policy.evaluate(BridgeCapability.COMMAND).allowed)
    }

    @Test
    fun localWebDeniesHighRisk() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.LOCAL_WEB,
            grantedCapabilities = setOf(BridgeCapability.ANDROID_CLIPBOARD),
            userAuthorizedHighRisk = true,
        )
        assertFalse(policy.evaluate(BridgeCapability.ANDROID_CLIPBOARD).allowed)
    }

    @Test
    fun trustedAndAuthorizedAllows() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.TRUSTED_DSH_WEBUI,
            grantedCapabilities = setOf(BridgeCapability.FILESYSTEM_READ),
            userAuthorizedHighRisk = true,
        )
        assertTrue(policy.evaluate(BridgeCapability.FILESYSTEM_READ).allowed)
    }
}
