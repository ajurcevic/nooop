package com.noop.ui

/**
 * "Buzz again after 5 min" for the strap alarm.
 *
 * The strap's firmware alarm is one absolute instant with no snooze or repeat field that this app knows
 * of, and one slot. So the second buzz is the same alarm armed a second time: when the strap reports it
 * fired (STRAP_DRIVEN_ALARM_EXECUTED, live only), the slot is re-armed [DELAY_SEC] later. Once that one
 * fires, the slot goes back to the next scheduled morning. After the re-arm the strap buzzes on its own;
 * only the moment of the first buzz needs the phone connected, because that is when the fire is reported.
 *
 * Pure so the fire sequence (first buzz, duplicate report, second buzz, missed second buzz) is testable
 * without the BLE stack.
 */
internal object AlarmRebuzz {

    /** Delay between the first buzz and the second. */
    const val DELAY_SEC: Long = 5 * 60L

    /** How early a fire report may arrive and still count as the second buzz. */
    private const val EARLY_SLACK_SEC: Long = 2 * 60L

    /** How late a fire report may arrive and still count as the second buzz. */
    private const val LATE_SLACK_SEC: Long = 10 * 60L

    /**
     * The pending second-buzz instant (epoch seconds) after the strap reports a fire at [nowSec], given the
     * one currently stored ([pendingSec], 0 = none). Returns 0 when nothing should be pending.
     *
     *  - Off: nothing pending.
     *  - A second buzz still well ahead: this report repeats the first buzz, so keep the pending one rather
     *    than pushing it later.
     *  - Around the pending instant: this IS the second buzz, so clear it and never chain a third.
     *  - Otherwise (no pending one, or a stale one from a morning whose second buzz was missed): this is a
     *    first buzz, so schedule the second.
     */
    fun pendingAfterFire(nowSec: Long, pendingSec: Long, enabled: Boolean): Long {
        if (!enabled) return 0L
        if (pendingSec > 0L) {
            if (nowSec < pendingSec - EARLY_SLACK_SEC) return pendingSec
            if (nowSec <= pendingSec + LATE_SLACK_SEC) return 0L
        }
        return nowSec + DELAY_SEC
    }

    /**
     * What the strap slot should hold: a pending second buzz while it is still ahead, else the next
     * scheduled alarm ([scheduledSec], null = none). The second buzz is minutes away, so it always comes
     * before the next scheduled morning; the min only guards a clock that moved.
     */
    fun strapTarget(scheduledSec: Long?, pendingSec: Long, nowSec: Long): Long? {
        if (pendingSec <= nowSec) return scheduledSec
        return if (scheduledSec == null) pendingSec else minOf(pendingSec, scheduledSec)
    }
}
