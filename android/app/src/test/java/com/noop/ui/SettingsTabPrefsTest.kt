package com.noop.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Settings tab persistence: a fresh install opens on the first tab, a written tab round-trips,
 * and a stored token no tab carries (an older or newer build's value) falls back instead of throwing.
 * Runs the REAL [SettingsTabPrefs] over an in-memory SharedPreferences, as SettingsDisclosurePrefsTest does.
 */
class SettingsTabPrefsTest {

    @Test
    fun freshInstall_opensOnYou() {
        assertEquals(SettingsTab.YOU, SettingsTabPrefs.read(FakeSharedPreferences()))
    }

    @Test
    fun write_thenRead_roundTripsEveryTab() {
        val prefs = FakeSharedPreferences()
        for (tab in SettingsTab.entries) {
            SettingsTabPrefs.write(prefs, tab)
            assertEquals(tab, SettingsTabPrefs.read(prefs))
        }
    }

    @Test
    fun unknownStoredValue_fallsBackToDefault() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(SettingsTabPrefs.KEY, "coach").apply()
        assertEquals(SettingsTabPrefs.DEFAULT, SettingsTabPrefs.read(prefs))
    }

    @Test
    fun prefValues_areDistinct() {
        val values = SettingsTab.entries.map { it.prefValue }
        assertEquals(values.size, values.toSet().size)
    }

    /** A minimal in-memory SharedPreferences: enough of the read/write contract for the helper above. */
    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
            override fun clear(): SharedPreferences.Editor { prefs.map.clear(); return this }
            override fun commit(): Boolean { flush(); return true }
            override fun apply() { flush() }
            private fun flush() {
                for (k in removals) prefs.map.remove(k)
                prefs.map.putAll(pending)
            }
        }
    }
}
