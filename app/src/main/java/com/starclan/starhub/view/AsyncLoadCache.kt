package com.starclan.starhub.view

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * Bo nho dem cho viec tai du lieu theo khoa (vd link anh), co 3 viec:
 *  1. NHIEU noi cung xin 1 khoa -> chi tai 1 lan, tat ca cung nhan ket qua.
 *  2. Tai LOI -> nho lai, khong thu lai ngay (tranh don dap khi mang yeu) NHUNG cho thu
 *     lai sau [retryAfterMs] (truoc day loi 1 lan la hong het ca phien lam viec).
 *  3. Nguoi xin bi huy (vd roi man hinh) -> viec tai van chay tiep trong [scope] rieng,
 *     nen lan sau mo lai da co san.
 *
 * Lop thuan Kotlin, khong dung Android -> chay thu duoc tren may tinh.
 * [now] la dong ho (mili giay) — truyen vao de test duoc ma khong phai cho that.
 */
class AsyncLoadCache<V : Any>(
    private val scope: CoroutineScope,
    private val retryAfterMs: Long,
    private val now: () -> Long,
    private val loader: suspend (String) -> V?
) {
    private val values = ConcurrentHashMap<String, V>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Deferred<V?>>()

    suspend fun get(key: String): V? {
        values[key]?.let { return it }
        val failTime = failedAt[key]
        if (failTime != null && now() - failTime < retryAfterMs) return null

        val created = scope.async(start = CoroutineStart.LAZY) {
            // Phong truong hop khoa vua duoc tai xong ngay truoc khi ta kip bat dau
            values[key]?.let { return@async it }
            try {
                val v = loader(key)
                if (v != null) {
                    values[key] = v
                    failedAt.remove(key)
                } else {
                    failedAt[key] = now()
                }
                v
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedAt[key] = now()
                null
            } finally {
                inFlight.remove(key)
            }
        }
        val existing = inFlight.putIfAbsent(key, created)
        if (existing != null) {
            created.cancel()          // da co nguoi dang tai khoa nay -> cho ho
            return existing.await()
        }
        created.start()
        return created.await()
    }

    /** Xoa danh sach "da loi" de moi khoa duoc thu lai ngay (vd khi nguoi dung bam Lam moi). */
    fun retryFailed() = failedAt.clear()
}
