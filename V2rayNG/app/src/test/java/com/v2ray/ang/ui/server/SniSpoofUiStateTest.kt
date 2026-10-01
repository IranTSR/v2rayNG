package com.v2ray.ang.ui.server

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SniSpoofUiStateTest {

    @Test
    fun sniSpoofingDefaultsToOffAndStoresNothingUntilTouched() {
        val profile = ProfileItem.create(EConfigType.VLESS)
        val state = ServerUiState.from(profile)

        assertEquals(false, state.sniSpoofEnabled)
        assertEquals("", state.sniSpoofFakeSni)
        assertEquals(AppConfig.SNI_SPOOF_DEFAULT_UTLS, state.sniSpoofUtls)
        assertEquals(AppConfig.SNI_SPOOF_DEFAULT_INJECTOR, state.sniSpoofInjector)
        assertEquals("", state.sniSpoofConnect)

        val stored = state.toProfileItem(profile)
        assertNull(stored.sniSpoofEnabled)
        assertNull(stored.sniSpoofFakeSni)
        assertNull(stored.sniSpoofUtls)
        assertNull(stored.sniSpoofInjector)
        assertNull(stored.sniSpoofConnect)
    }

    @Test
    fun sniSpoofingSettingsRoundTripThroughTheProfile() {
        val profile = ProfileItem.create(EConfigType.TROJAN)
        val state = ServerUiState.from(profile).apply {
            sniSpoofEnabled = true
            sniSpoofFakeSni = "hcaptcha.com"
            sniSpoofUtls = "firefox"
            sniSpoofInjector = "passive"
            sniSpoofConnect = "104.19.229.21:443"
        }

        val stored = state.toProfileItem(profile)
        assertEquals(true, stored.sniSpoofEnabled)
        assertEquals("hcaptcha.com", stored.sniSpoofFakeSni)
        assertEquals("firefox", stored.sniSpoofUtls)
        assertEquals("passive", stored.sniSpoofInjector)
        assertEquals("104.19.229.21:443", stored.sniSpoofConnect)

        val restored = ServerUiState.from(stored)
        assertEquals(true, restored.sniSpoofEnabled)
        assertEquals("hcaptcha.com", restored.sniSpoofFakeSni)
        assertEquals("firefox", restored.sniSpoofUtls)
        assertEquals("passive", restored.sniSpoofInjector)
        assertEquals("104.19.229.21:443", restored.sniSpoofConnect)
    }

    @Test
    fun sniSpoofingFlagIsDroppedForProtocolsTheSidecarCannotFront() {
        val profile = ProfileItem.create(EConfigType.WIREGUARD)
        val state = ServerUiState.from(profile).apply { sniSpoofEnabled = true }

        assertNull(state.toProfileItem(profile).sniSpoofEnabled)
    }
}
