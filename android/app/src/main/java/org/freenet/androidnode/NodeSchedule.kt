package org.freenet.androidnode

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.DateFormat
import java.util.Date

internal object ConnectionSchedule {
    const val MinHours = 1
    const val MaxHours = 24
    const val DefaultHours = 1
    const val MinMinutes = 5
    const val MaxMinutes = 30
    const val DefaultMinutes = 5

    fun coerceHours(value: Int): Int = value.coerceIn(MinHours, MaxHours)

    fun coerceMinutes(value: Int): Int = value.coerceIn(MinMinutes, MaxMinutes)
}

internal enum class SchedulePhase {
    StartWindow,
    StayOn,
    Wait,
}

internal data class ScheduleView(
    val phase: SchedulePhase,
    val transitionAtEpochMs: Long,
)

internal fun schedulePhase(
    nowMs: Long,
    everyHours: Int,
    onMinutes: Int,
    windowStartedMs: Long,
    windowEndedMs: Long,
): ScheduleView {
    val onMs = ConnectionSchedule.coerceMinutes(onMinutes) * 60_000L
    val offMs = ConnectionSchedule.coerceHours(everyHours) * 3_600_000L
    if (windowStartedMs > 0L) {
        val onUntil = windowStartedMs + onMs
        if (nowMs < onUntil) return ScheduleView(SchedulePhase.StayOn, onUntil)
        val ended = if (windowEndedMs >= onUntil) windowEndedMs else onUntil
        val next = ended + offMs
        if (nowMs < next) return ScheduleView(SchedulePhase.Wait, next)
        return ScheduleView(SchedulePhase.StartWindow, nowMs + onMs)
    }
    if (windowEndedMs > 0L) {
        val next = windowEndedMs + offMs
        if (nowMs < next) return ScheduleView(SchedulePhase.Wait, next)
    }
    return ScheduleView(SchedulePhase.StartWindow, nowMs + onMs)
}

internal fun scheduleMenuLine(nowMs: Long, policy: NodePolicyState): String? {
    if (policy.power != NodePowerPolicy.Schedule || policy.suspendedByUser) return null
    val phase = schedulePhase(
        nowMs,
        policy.scheduleEveryHours,
        policy.scheduleOnMinutes,
        policy.scheduleWindowStartedEpochMs,
        policy.scheduleWindowEndedEpochMs,
    )
    val whenText = scheduleClockText(nowMs, phase.transitionAtEpochMs)
    return when (phase.phase) {
        SchedulePhase.StartWindow -> null
        SchedulePhase.StayOn ->
            "The node stays on until $whenText, then waits ${hourLabel(policy.scheduleEveryHours)}."
        SchedulePhase.Wait ->
            "The node is waiting until $whenText to connect again."
    }
}

internal fun hourLabel(hours: Int): String {
    val value = ConnectionSchedule.coerceHours(hours)
    return if (value == 1) "1 hour" else "$value hours"
}

internal fun minuteLabel(minutes: Int): String {
    val value = ConnectionSchedule.coerceMinutes(minutes)
    return "$value minutes"
}

internal fun scheduleClockText(nowMs: Long, atMs: Long): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(atMs))
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val at = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
    val sameDay = now.get(java.util.Calendar.YEAR) == at.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == at.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return time
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs))
    return "$date $time"
}

internal object NodeScheduleAlarm {
    private const val REQUEST_CODE = 41

    fun set(context: Context, triggerAtEpochMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        val trigger = triggerAtEpochMs.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NodePolicyReceiver::class.java)
            .setAction(NodePolicyReceiver.ACTION_SCHEDULE_TICK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
