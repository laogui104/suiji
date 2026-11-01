package com.suiji.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.CaptureSource
import com.suiji.assistant.core.DuePrecision
import com.suiji.assistant.core.EngineKind
import com.suiji.assistant.core.ExpandState
import com.suiji.assistant.core.InsightVersion
import com.suiji.assistant.core.Memo
import com.suiji.assistant.core.MemoStatus
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.core.TodoStatus
import org.json.JSONArray

/**
 * 单表 + 提醒日志表 + 深化历史表。
 *
 * v2 迁移（本次改造）：
 *  - memo 增列 tags / classifyReason / needConfirm / segmentIndex
 *      分拣结果要能「解释给用户看」，还要能挂一个「待确认」状态；
 *  - 新增 insight_version 表
 *      深化从「只留最新一次」变成保留历史版本，可回看、可对比。
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "suiji.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memo (
              id TEXT PRIMARY KEY,
              type TEXT NOT NULL,
              title TEXT NOT NULL,
              contentRaw TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL,
              status TEXT NOT NULL,
              source TEXT NOT NULL,
              confidence REAL NOT NULL DEFAULT 0,
              engine TEXT NOT NULL DEFAULT 'RULE',
              dueAt INTEGER,
              duePrecision TEXT NOT NULL DEFAULT 'NONE',
              counterparty TEXT,
              todoStatus TEXT NOT NULL DEFAULT 'PENDING',
              remindCount INTEGER NOT NULL DEFAULT 0,
              lastRemindAt INTEGER,
              expanded TEXT,
              expandState TEXT NOT NULL DEFAULT 'NONE',
              deletedAt INTEGER,
              tags TEXT,
              classifyReason TEXT,
              needConfirm INTEGER NOT NULL DEFAULT 0,
              segmentIndex INTEGER NOT NULL DEFAULT 0,
              snoozedUntil INTEGER,
              calendarEventId INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memo_created ON memo(createdAt DESC)")
        db.execSQL("CREATE INDEX idx_memo_type ON memo(type, status)")
        db.execSQL(
            """
            CREATE TABLE reminder_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              memoId TEXT NOT NULL,
              firedAt INTEGER NOT NULL,
              idleScore INTEGER NOT NULL,
              surface TEXT NOT NULL,
              result TEXT NOT NULL,
              reasons TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_log_fired ON reminder_log(firedAt DESC)")
        createInsightTable(db)
    }

    private fun createInsightTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS insight_version (
              id TEXT PRIMARY KEY,
              memoId TEXT NOT NULL,
              mode TEXT NOT NULL,
              content TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              latencyMs INTEGER NOT NULL DEFAULT 0,
              ok INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_insight_memo ON insight_version(memoId, createdAt DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE memo ADD COLUMN tags TEXT") }
            runCatching { db.execSQL("ALTER TABLE memo ADD COLUMN classifyReason TEXT") }
            runCatching { db.execSQL("ALTER TABLE memo ADD COLUMN needConfirm INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE memo ADD COLUMN segmentIndex INTEGER NOT NULL DEFAULT 0") }
            createInsightTable(db)
        }
        if (oldVersion < 3) {
            // 提醒落地：稍后推迟时间 + 系统日历事件 id
            runCatching { db.execSQL("ALTER TABLE memo ADD COLUMN snoozedUntil INTEGER") }
            runCatching { db.execSQL("ALTER TABLE memo ADD COLUMN calendarEventId INTEGER") }
        }
    }

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(ctx.applicationContext).also { instance = it }
        }
    }
}

class MemoRepo(ctx: Context) {

    private val helper = Db.get(ctx)

    private fun db(): SQLiteDatabase = helper.writableDatabase

    fun list(type: MemoType?, limit: Int = 400): List<Memo> {
        val where = StringBuilder("status != 'TRASHED'")
        val args = mutableListOf<String>()
        if (type != null) {
            where.append(" AND type = ?")
            args += type.name
        }
        return db().query(
            "memo", null, where.toString(), args.toTypedArray(),
            null, null, "createdAt DESC", limit.toString()
        ).use { c -> c.readAll() }
    }

    fun search(keyword: String): List<Memo> {
        val kw = "%${keyword.trim()}%"
        return db().query(
            "memo", null,
            "status != 'TRASHED' AND (title LIKE ? OR contentRaw LIKE ?)",
            arrayOf(kw, kw), null, null, "createdAt DESC", "80"
        ).use { c -> c.readAll() }
    }

    fun get(id: String): Memo? = db().query(
        "memo", null, "id = ?", arrayOf(id), null, null, null, "1"
    ).use { c -> if (c.moveToFirst()) c.toMemo() else null }

    fun pendingConfirm(): List<Memo> = db().query(
        "memo", null,
        "status = 'ACTIVE' AND needConfirm = 1 AND type != 'UNCLASSIFIED'",
        null, null, null, "createdAt DESC", "20"
    ).use { c -> c.readAll() }

    fun countByType(type: MemoType): Int = db().rawQuery(
        "SELECT COUNT(*) FROM memo WHERE status = 'ACTIVE' AND type = ?",
        arrayOf(type.name)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun insert(memo: Memo) {
        val cv = ContentValues().apply {
            put("id", memo.id)
            put("type", memo.type.name)
            put("title", memo.title)
            put("contentRaw", memo.contentRaw)
            put("createdAt", memo.createdAt)
            put("updatedAt", memo.updatedAt)
            put("status", memo.status.name)
            put("source", memo.source.name)
            put("confidence", memo.confidence)
            put("engine", memo.engine.name)
            if (memo.dueAt != null) put("dueAt", memo.dueAt) else putNull("dueAt")
            put("duePrecision", memo.duePrecision.name)
            if (memo.counterparty != null) put("counterparty", memo.counterparty) else putNull("counterparty")
            put("todoStatus", memo.todoStatus.name)
            put("remindCount", memo.remindCount)
            if (memo.lastRemindAt != null) put("lastRemindAt", memo.lastRemindAt) else putNull("lastRemindAt")
            if (memo.expanded != null) put("expanded", memo.expanded) else putNull("expanded")
            put("expandState", memo.expandState.name)
            if (memo.deletedAt != null) put("deletedAt", memo.deletedAt) else putNull("deletedAt")
            put("tags", JSONArray(memo.tags).toString())
            put("classifyReason", memo.classifyReason)
            put("needConfirm", if (memo.needConfirm) 1 else 0)
            put("segmentIndex", memo.segmentIndex)
            if (memo.snoozedUntil != null) put("snoozedUntil", memo.snoozedUntil) else putNull("snoozedUntil")
            if (memo.calendarEventId != null) put("calendarEventId", memo.calendarEventId) else putNull("calendarEventId")
        }
        db().insertWithOnConflict("memo", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun update(memo: Memo) {
        insert(memo.copy(updatedAt = System.currentTimeMillis()))
    }

    fun setType(id: String, type: MemoType) = updateFields(id) {
        put("type", type.name)
        put("engine", EngineKind.MANUAL.name)
        put("confidence", 1.0f)
        put("needConfirm", 0)
    }

    /** 用户确认了 AI 的归类：清掉待确认标记，置信度抬满 */
    fun confirmType(id: String, type: MemoType) = updateFields(id) {
        put("type", type.name)
        put("confidence", 1.0f)
        put("needConfirm", 0)
    }

    fun setNeedConfirm(id: String, need: Boolean) = updateFields(id) {
        put("needConfirm", if (need) 1 else 0)
    }

    fun setTitle(id: String, title: String) = updateFields(id) {
        put("title", title)
        put("engine", EngineKind.MANUAL.name)
    }

    fun setTodoStatus(id: String, status: TodoStatus) = updateFields(id) {
        put("todoStatus", status.name)
        if (status == TodoStatus.DONE) put("needConfirm", 0)
    }

    fun setExpanded(id: String, text: String?, state: ExpandState) = updateFields(id) {
        if (text != null) put("expanded", text) else putNull("expanded")
        put("expandState", state.name)
    }

    fun trash(id: String) = updateFields(id) {
        put("status", MemoStatus.TRASHED.name)
        put("deletedAt", System.currentTimeMillis())
    }

    fun restore(id: String) = updateFields(id) {
        put("status", MemoStatus.ACTIVE.name)
        putNull("deletedAt")
    }

    fun purge(before: Long): Int = db().delete(
        "memo", "status = 'TRASHED' AND deletedAt IS NOT NULL AND deletedAt < ?",
        arrayOf(before.toString())
    )

    fun countTrash(): Int = db().rawQuery(
        "SELECT COUNT(*) FROM memo WHERE status = 'TRASHED'", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 到点且未完成、未超提醒次数、不在「稍后」窗口内的待办 */
    fun duePending(now: Long): List<Memo> = db().query(
        "memo", null,
        "status = 'ACTIVE' AND type = 'TODO' AND todoStatus = 'PENDING' " +
                "AND dueAt IS NOT NULL AND dueAt <= ? " +
                "AND (snoozedUntil IS NULL OR snoozedUntil <= ?)",
        arrayOf(now.toString(), now.toString()), null, null, "dueAt ASC", "30"
    ).use { c -> c.readAll() }

    /**
     * 还需要排闹钟的待办：未完成、有时间、还没提醒够次数。
     * 包含未来的（用于排精确闹钟）与已经过点的（用于补发）。
     */
    fun schedulable(now: Long, limit: Int = 60): List<Memo> = db().query(
        "memo", null,
        "status = 'ACTIVE' AND type = 'TODO' AND todoStatus = 'PENDING' " +
                "AND dueAt IS NOT NULL AND remindCount < ? AND dueAt > ?",
        arrayOf(AppConfig.MAX_FIRES_PER_MEMO.toString(), now.toString()),
        null, null, "dueAt ASC", limit.toString()
    ).use { c -> c.readAll() }

    /** 已经过点但还没提醒的（含被打断、错过闹钟的） */
    fun overdueUnreminded(now: Long): List<Memo> = db().query(
        "memo", null,
        "status = 'ACTIVE' AND type = 'TODO' AND todoStatus = 'PENDING' " +
                "AND dueAt IS NOT NULL AND dueAt <= ? AND remindCount < ? " +
                "AND (snoozedUntil IS NULL OR snoozedUntil <= ?)",
        arrayOf(
            now.toString(),
            AppConfig.MAX_FIRES_PER_MEMO.toString(),
            now.toString()
        ),
        null, null, "dueAt ASC", "30"
    ).use { c -> c.readAll() }

    /** 「稍后」到期的最近时刻，用来排精确唤醒，不让 10 分钟变成 15+ 分钟 */
    fun nextSnoozeWake(now: Long): Long? = db().rawQuery(
        "SELECT MIN(snoozedUntil) FROM memo WHERE status = 'ACTIVE' AND type = 'TODO' " +
                "AND todoStatus = 'PENDING' AND snoozedUntil IS NOT NULL AND snoozedUntil > ?",
        arrayOf(now.toString())
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun setSnooze(id: String, until: Long) = updateFields(id) {
        put("snoozedUntil", until)
    }

    fun clearSnooze(id: String) = updateFields(id) {
        putNull("snoozedUntil")
    }

    fun setCalendarEvent(id: String, eventId: Long?) = updateFields(id) {
        if (eventId != null) put("calendarEventId", eventId) else putNull("calendarEventId")
    }

    fun markReminded(id: String, count: Int, at: Long) = updateFields(id) {
        put("remindCount", count)
        put("lastRemindAt", at)
    }

    fun logReminder(memoId: String, firedAt: Long, score: Int, surface: String, result: String, reasons: String) {
        db().insert("reminder_log", null, ContentValues().apply {
            put("memoId", memoId)
            put("firedAt", firedAt)
            put("idleScore", score)
            put("surface", surface)
            put("result", result)
            put("reasons", reasons)
        })
    }

    fun lastFiredAt(): Long = db().rawQuery(
        "SELECT MAX(firedAt) FROM reminder_log", null
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    fun recentDuplicate(content: String, since: Long): Memo? = db().query(
        "memo", null, "status = 'ACTIVE' AND createdAt > ? AND contentRaw = ?",
        arrayOf(since.toString(), content), null, null, "createdAt DESC", "1"
    ).use { c -> if (c.moveToFirst()) c.toMemo() else null }

    // ============================================================
    // 灵感深化历史
    // ============================================================

    fun insertInsight(v: InsightVersion) {
        db().insertWithOnConflict("insight_version", null, ContentValues().apply {
            put("id", v.id)
            put("memoId", v.memoId)
            put("mode", v.mode)
            put("content", v.content)
            put("createdAt", v.createdAt)
            put("latencyMs", v.latencyMs)
            put("ok", if (v.ok) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insightsOf(memoId: String, limit: Int = 12): List<InsightVersion> = db().query(
        "insight_version", null, "memoId = ?", arrayOf(memoId),
        null, null, "createdAt DESC", limit.toString()
    ).use { c ->
        val out = ArrayList<InsightVersion>(c.count)
        while (c.moveToNext()) {
            out += InsightVersion(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                memoId = c.getString(c.getColumnIndexOrThrow("memoId")),
                mode = c.getString(c.getColumnIndexOrThrow("mode")),
                content = c.getString(c.getColumnIndexOrThrow("content")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
                latencyMs = c.getLong(c.getColumnIndexOrThrow("latencyMs")),
                ok = c.getInt(c.getColumnIndexOrThrow("ok")) == 1
            )
        }
        out
    }

    fun countInsights(): Int = db().rawQuery(
        "SELECT COUNT(*) FROM insight_version", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun pruneInsights(keepPerMemo: Int = 6) {
        runCatching {
            db().execSQL(
                """
                DELETE FROM insight_version WHERE id NOT IN (
                  SELECT id FROM insight_version iv2 WHERE iv2.memoId = insight_version.memoId
                  ORDER BY createdAt DESC LIMIT $keepPerMemo
                )
                """.trimIndent()
            )
        }
    }

    // ============================================================

    private fun updateFields(id: String, block: ContentValues.() -> Unit) {
        val cv = ContentValues().apply {
            put("updatedAt", System.currentTimeMillis())
        }
        cv.block()
        db().update("memo", cv, "id = ?", arrayOf(id))
    }

    private fun Cursor.readAll(): List<Memo> {
        val out = ArrayList<Memo>(count)
        while (moveToNext()) out += toMemo()
        return out
    }

    private fun Cursor.strOrNull(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.intOr(col: String, def: Int): Int {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) def else getInt(i)
    }

    private fun Cursor.toMemo(): Memo = Memo(
        id = getString(getColumnIndexOrThrow("id")),
        type = MemoType.valueOf(getString(getColumnIndexOrThrow("type"))),
        title = getString(getColumnIndexOrThrow("title")),
        contentRaw = getString(getColumnIndexOrThrow("contentRaw")),
        createdAt = getLong(getColumnIndexOrThrow("createdAt")),
        updatedAt = getLong(getColumnIndexOrThrow("updatedAt")),
        status = MemoStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        source = runCatching {
            CaptureSource.valueOf(getString(getColumnIndexOrThrow("source")))
        }.getOrDefault(CaptureSource.TEXT),
        confidence = getFloat(getColumnIndexOrThrow("confidence")),
        engine = runCatching {
            EngineKind.valueOf(getString(getColumnIndexOrThrow("engine")))
        }.getOrDefault(EngineKind.RULE),
        dueAt = getColumnIndexOrThrow("dueAt").let { if (isNull(it)) null else getLong(it) },
        duePrecision = runCatching {
            DuePrecision.valueOf(getString(getColumnIndexOrThrow("duePrecision")))
        }.getOrDefault(DuePrecision.NONE),
        counterparty = strOrNull("counterparty"),
        todoStatus = runCatching {
            TodoStatus.valueOf(getString(getColumnIndexOrThrow("todoStatus")))
        }.getOrDefault(TodoStatus.PENDING),
        remindCount = getInt(getColumnIndexOrThrow("remindCount")),
        lastRemindAt = getColumnIndexOrThrow("lastRemindAt").let { if (isNull(it)) null else getLong(it) },
        expanded = strOrNull("expanded"),
        expandState = runCatching {
            ExpandState.valueOf(getString(getColumnIndexOrThrow("expandState")))
        }.getOrDefault(ExpandState.NONE),
        deletedAt = getColumnIndexOrThrow("deletedAt").let { if (isNull(it)) null else getLong(it) },
        tags = parseTags(strOrNull("tags")),
        classifyReason = strOrNull("classifyReason").orEmpty(),
        needConfirm = intOr("needConfirm", 0) == 1,
        segmentIndex = intOr("segmentIndex", 0),
        snoozedUntil = getColumnIndex("snoozedUntil").let {
            if (it < 0 || isNull(it)) null else getLong(it)
        },
        calendarEventId = getColumnIndex("calendarEventId").let {
            if (it < 0 || isNull(it)) null else getLong(it)
        }
    )

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
        }.getOrDefault(emptyList())
    }
}
