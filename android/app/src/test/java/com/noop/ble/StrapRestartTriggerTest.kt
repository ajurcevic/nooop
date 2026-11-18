package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrapRestartTriggerTest {

    @Test
    fun bootActionsAreAPhoneRestart() {
        assertEquals(StrapRestartTrigger.PHONE_RESTART, strapRestartTrigger("android.intent.action.BOOT_COMPLETED"))
        assertEquals(StrapRestartTrigger.PHONE_RESTART, strapRestartTrigger("android.intent.action.QUICKBOOT_POWERON"))
        assertEquals(StrapRestartTrigger.PHONE_RESTART, strapRestartTrigger("com.htc.intent.action.QUICKBOOT_POWERON"))
    }

    @Test
    fun ownPackageReplacedIsAnAppUpdate() {
        assertEquals(StrapRestartTrigger.APP_UPDATE, strapRestartTrigger("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test
    fun otherActionsAreIgnored() {
        assertNull(strapRestartTrigger(null))
        assertNull(strapRestartTrigger("android.intent.action.PACKAGE_REPLACED"))
        assertNull(strapRestartTrigger("android.intent.action.LOCKED_BOOT_COMPLETED"))
    }
}
