package me.rerere.rikkahub.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UsageRecordDao {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Query("SELECT * FROM usage_records ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE created_at_ms >= :sinceMs ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun since(sinceMs: Long, limit: Int): List<UsageRecordEntity>

    @Query("SELECT COUNT(*) FROM usage_records")
    suspend fun count(): Int

    /**
     * Everything billed under one orchestration root, so that P2-13 can refuse a dispatch
     * before it happens instead of noticing afterwards.
     */
    @Query(
        "SELECT COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0) " +
            "FROM usage_records WHERE parent_run_id = :parentRunId"
    )
    suspend fun tokensForParentRun(parentRunId: String): Long

    @Query(
        "SELECT COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0) " +
            "FROM usage_records WHERE created_at_ms >= :sinceMs"
    )
    suspend fun tokensSince(sinceMs: Long): Long

    /**
     * Tokens billed under one conversation — the mission token breaker's input.
     *
     * A mission owns exactly one execution conversation, so summing by conversation id is
     * the per-mission total without adding a mission column (and without a migration).
     * [inputTokens] + [outputTokens] is used instead of `total_tokens` because some
     * providers fill only the two halves; the sum is what the ledger itself bills on.
     */
    @Query(
        "SELECT COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0) " +
            "FROM usage_records WHERE conversation_id = :conversationId"
    )
    suspend fun tokensForConversation(conversationId: String): Long

    /**
     * 批 H — 净消耗：命中缓存的输入 token 不计入任务预算。
     *
     * 原来的 [tokensForConversation] 把每一步重发的整段上下文都算一次「消耗」，于是一个
     * 90 步的任务在第一轮就把 500k 预算烧光——而它真正新增的工作量可能只有十分之一。
     * 缓存命中部分按折扣计费，甚至免费（视供应商而定），把它算满额等于惩罚「长任务本来就
     * 需要长上下文」这件事，任务系统因此永远跑不到 8 小时。
     *
     * 只对**明确上报过缓存字段**的行做扣减（`cached_tokens_reported = 1`）：没上报的供应商
     * 里 `cached_tokens` 恒为 0，不能当作「没有缓存」也不能当作「全命中」。
     * 输出 token 全额计入——它是真正的新工作。
     */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN cached_tokens_reported = 1 " +
            "THEN MAX(input_tokens - cached_tokens, 0) ELSE input_tokens END), 0) " +
            "+ COALESCE(SUM(output_tokens), 0) " +
            "FROM usage_records WHERE conversation_id = :conversationId"
    )
    suspend fun billableTokensForConversation(conversationId: String): Long

    /**
     * P2-12b — every row for one conversation, oldest first, for the turn view. Bounded by
     * [UsageLedgerDefaults.QUERY_LIMIT] so a long conversation cannot load an unbounded list.
     */
    @Query(
        "SELECT * FROM usage_records WHERE conversation_id = :conversationId " +
            "ORDER BY created_at_ms ASC LIMIT :limit"
    )
    suspend fun forConversation(conversationId: String, limit: Int): List<UsageRecordEntity>

    /** Retention sweep; returns the number of rows removed. */
    @Query("DELETE FROM usage_records WHERE created_at_ms < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
