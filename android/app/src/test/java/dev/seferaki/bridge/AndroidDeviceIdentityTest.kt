package dev.seferaki.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceIdentityTest {
    @Test
    fun includesManufacturerForModelsFromOtherAndroidVendors() {
        assertEquals("Google Pixel 9", AndroidDeviceIdentity.displayName("google", "Pixel 9"))
        assertEquals("Xiaomi 15", AndroidDeviceIdentity.displayName("Xiaomi", "15"))
    }

    @Test
    fun doesNotRepeatManufacturerAlreadyPresentInModel() {
        assertEquals("Samsung Galaxy S25", AndroidDeviceIdentity.displayName("Samsung", "Samsung Galaxy S25"))
    }

    @Test
    fun usesNeutralFallbackWhenModelIsMissing() {
        assertEquals("Телефон Android", AndroidDeviceIdentity.displayName("Samsung", "  "))
        assertEquals("Android", AndroidDeviceIdentity.networkLabel("Samsung", "  "))
    }

    @Test
    fun networkLabelIsSafeForAnHttpHeader() {
        assertEquals("Google Pixel 9", AndroidDeviceIdentity.networkLabel("Google", "Pixel\n9"))
        assertEquals("Android", AndroidDeviceIdentity.networkLabel("制造商", "📱"))
    }
}
