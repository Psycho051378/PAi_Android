package com.pai.android.agent.skills.home.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Юнит-тесты чистой логики слоя идентичности.
 *
 * Покрывают то, что можно проверить без телефона и без сети:
 * нормализацию MAC и сравнение состояния с целевым действием
 * (на нём держится и идемпотентность, и верификация).
 */
class IdentityTest {

    // ==================== MAC ====================

    @Test
    fun normalize_accepts_12_hex_chars() {
        assertEquals("A8:BB:50:AA:BB:CC", MacUtil.normalize("a8bb50aabbcc"))
        assertEquals("A8:BB:50:AA:BB:CC", MacUtil.normalize("A8BB50AABBCC"))
    }

    @Test
    fun normalize_accepts_colon_and_dash_forms() {
        assertEquals("AA:BB:CC:DD:EE:FF", MacUtil.normalize("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", MacUtil.normalize("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun normalize_rejects_garbage() {
        assertNull(MacUtil.normalize(null))
        assertNull(MacUtil.normalize(""))
        assertNull(MacUtil.normalize("zz:zz:zz:zz:zz:zz"))
        assertNull(MacUtil.normalize("12345"))
    }

    @Test
    fun normalize_rejects_zero_and_broadcast() {
        assertNull(MacUtil.normalize("000000000000"))
        assertNull(MacUtil.normalize("ffffffffffff"))
    }

    @Test
    fun normalize_rejects_multicast_bit() {
        // Первый октет 01 → групповой адрес, это не MAC устройства
        assertNull(MacUtil.normalize("011b44113ab7"))
        // Первый октет 02 → unicast, это нормальный локально-администрируемый MAC
        assertEquals("02:1B:44:11:3A:B7", MacUtil.normalize("021b44113ab7"))
    }

    // ==================== Состояние vs команда ====================

    @Test
    fun power_matches() {
        val on = DeviceState(power = true)
        assertEquals(true, on.matches("turn_on"))
        assertEquals(false, on.matches("turn_off"))

        val off = DeviceState(power = false)
        assertEquals(false, off.matches("turn_on"))
        assertEquals(true, off.matches("turn_off"))
    }

    @Test
    fun unknown_power_means_cannot_decide() {
        assertNull(DeviceState(brightness = 40).matches("turn_on"))
    }

    @Test
    fun brightness_tolerance() {
        val state = DeviceState(brightness = 50)
        assertEquals(true, state.matches("set_brightness", mapOf("level" to 51)))
        assertEquals(true, state.matches("set_brightness", mapOf("level" to 47)))
        assertEquals(false, state.matches("set_brightness", mapOf("level" to 70)))
        assertNull(state.matches("set_brightness", emptyMap()))
    }

    @Test
    fun rgb_tolerance() {
        val state = DeviceState(rgb = Triple(255, 0, 0))
        assertEquals(true, state.matches("set_rgb", mapOf("r" to 250, "g" to 5, "b" to 0)))
        assertEquals(false, state.matches("set_rgb", mapOf("r" to 0, "g" to 255, "b" to 0)))
        // Имя цвета вместо r/g/b — проверить нельзя
        assertNull(state.matches("set_rgb", mapOf("color" to "green")))
    }

    @Test
    fun color_temp_tolerance() {
        val state = DeviceState(colorTempKelvin = 3500)
        assertEquals(true, state.matches("set_color_temp", mapOf("temp" to 3600)))
        assertEquals(false, state.matches("set_color_temp", mapOf("temp" to 2000)))
        // Протокол не отдаёт Кельвины — сравнивать нельзя
        assertNull(DeviceState().matches("set_color_temp", mapOf("temp" to 3500)))
    }

    @Test
    fun identity_key_prefers_mac() {
        val withMac = IdentityHit(ip = "192.168.1.10", protocol = "SHELLY", port = 80, mac = "AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", withMac.identityKey)

        val nativeOnly = IdentityHit(ip = "192.168.1.11", protocol = "YEELIGHT", port = 55443, nativeId = "0x1a2b3c")
        assertEquals("yeelight:0x1a2b3c", nativeOnly.identityKey)

        val nothing = IdentityHit(ip = "192.168.1.12", protocol = "WLED", port = 80)
        assertNull(nothing.identityKey)
        assertEquals("ip:192.168.1.12", nothing.stableId)
        assertEquals(false, nothing.hasStableIdentity)
    }
}
