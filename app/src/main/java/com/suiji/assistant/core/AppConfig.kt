package com.suiji.assistant.core

/**
 * 全局阈值与开关。所有魔法数字集中在此，便于调参与测试。
 */
object AppConfig {

    // ---- AI 分拣 ----
    const val CONFIDENCE_THRESHOLD = 0.55f
    const val LLM_TIMEOUT_MS = 20_000
    const val LLM_CONNECT_TIMEOUT_MS = 10_000
    const val LLM_MAX_RETRY = 1
    const val MAX_INPUT_CHARS = 2000
    const val TEMPERATURE = 0.1f
    const val MAX_TOKENS = 800

    // ---- 闲时判定 ----
    // 阈值只用来区分「要不要在通知里加一句催促」，不再作为是否发通知的硬门槛。
    const val IDLE_SCORE_THRESHOLD = 60
    const val SEMI_IDLE_THRESHOLD = 35
    const val IDLE_MIN_DURATION_MS = 90_000L

    /** 兜底巡检间隔：精确闹钟缺失/被系统丢弃时靠它补救 */
    const val ASSESS_INTERVAL_MS = 15 * 60_000L

    /** 到点后多久还没被处理就补一次（毫秒） */
    const val OVERDUE_CATCHUP_MS = 10 * 60_000L

    /** 同一条待办两次提醒之间的最小间隔：太短会连发，太长等于漏提 */
    const val REMIND_REPEAT_MS = 20 * 60_000L

    // ---- 提醒频控 ----
    const val QUIET_START_MIN = 22 * 60
    const val QUIET_END_MIN = 7 * 60 + 30
    const val MAX_FIRES_PER_MEMO = 2
    const val MIN_GAP_BETWEEN_ANY_MS = 45 * 60_000L
    const val REMIND_EXPIRE_DAYS = 3

    /** 通知「稍后」默认推迟分钟数 */
    const val SNOOZE_MINUTES = 10

    /** 单条待办闹钟的 requestCode 基数，避免与巡检闹钟（0/1）冲突 */
    const val ALARM_REQ_BASE = 10_000

    // ---- 日历同步 ----
    const val CAL_LEAD_MINUTES = 5

    // ---- 录音 ----
    const val MAX_RECORD_MS = 120_000L

    // ---- 通知渠道 ----
    const val CH_SOFT = "suiji_soft_v2"
    const val CH_SYSTEM = "suiji_system"

    const val NOTIFY_ID_SOFT = 1001
    const val NOTIFY_ID_TEST = 1999
}
