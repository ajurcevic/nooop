package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.util.concurrent.ConcurrentHashMap

/**
 * The last value each Today read-out loaded, kept on the ViewModel so a tab switch does not blank them.
 *
 * The bottom-tab NavHost disposes the Today composable when another tab opens, and every plain
 * `remember` inside it starts again from its empty default on return. Each read-out then painted its
 * empty state (heart rate "—", Rest "—") for a frame or two until its LaunchedEffect finished the
 * reload. Seeding from here shows the last-known value at once; the reload still runs on every visit
 * and replaces it in place.
 *
 * Each entry carries the day it was loaded for ([scope]). A value is only handed back for the same
 * day, so a cached Tuesday never paints on Wednesday after midnight or after the day selector moves.
 * Pure Kotlin so the rule is pinned by a JVM test.
 */
class TodayUiCache {
    private class Entry(val scope: String, val value: Any?)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Whether a value for [key] was stored for [scope]. A stored null counts. */
    fun has(key: String, scope: String): Boolean = entries[key]?.scope == scope

    /** The value stored for [key] under [scope], or null when there is none (see [has]). */
    fun get(key: String, scope: String): Any? = entries[key]?.takeIf { it.scope == scope }?.value

    /** Remember [value] for [key], loaded for [scope]. Replaces whatever was stored for any day. */
    fun put(key: String, scope: String, value: Any?) {
        entries[key] = Entry(scope, value)
    }

    fun clear() = entries.clear()
}

/**
 * A `remember { mutableStateOf(initial) }` that starts from the last value [cache] holds for [key] on
 * [scope] (the selected day), and writes every new value back.
 *
 * The state itself is remembered without keys, so a day change inside one visit behaves exactly as the
 * plain `remember` did: the old value stays until that day's reload lands. Only the first composition
 * of a visit is seeded. [scope] is re-read on every composition so a later write is filed under the
 * day on screen at that time.
 */
@Composable
internal fun <T> rememberTodayCached(cache: TodayUiCache, key: String, scope: String, initial: T): MutableState<T> {
    val state = remember {
        @Suppress("UNCHECKED_CAST")
        val seed = if (cache.has(key, scope)) cache.get(key, scope) as T else initial
        CachedState(mutableStateOf(seed), cache, key, scope)
    }
    SideEffect { state.scope = scope }
    return state
}

private class CachedState<T>(
    private val inner: MutableState<T>,
    private val cache: TodayUiCache,
    private val key: String,
    @Volatile var scope: String,
) : MutableState<T> {
    override var value: T
        get() = inner.value
        set(v) {
            inner.value = v
            cache.put(key, scope, v)
        }

    override fun component1(): T = value

    override fun component2(): (T) -> Unit = { value = it }
}
