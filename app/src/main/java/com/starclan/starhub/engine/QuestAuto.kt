package com.starclan.starhub.engine

import com.starclan.starhub.data.HubQuestItem
import java.util.Locale
import kotlin.random.Random

/**
 * Tu lam cac quest "tu lam duoc" (theo danh sach Ruby chot): Say owo, Gamble, Action (hug/kiss/pat...).
 * Hunt / Battle / Gain XP / mo Box-Crate da nam trong vong farm + viec vat san co.
 * KHONG lam: quest "Receive ..." (can nguoi khac gui cho), Boss, Vote (can lam tay).
 *
 * Doc danh sach quest tu du lieu Hub bot da ghi (title + description + tien do), moi quest chua xong ma khop 1 loai
 * thi tinh con thieu bao nhieu lan. Moi lan chay viec vat chi go 1 dot nho (BURST lenh) roi doi vong sau.
 */
object QuestAuto {
    /** Discord ID nguoi nhan khi lam quest Action (Ruby chot). */
    const val ACTION_TARGET_ID = "1238687916176183409"
    /** So cowoncy cuoc moi lan gamble (Ruby chot: luon cuoc 1). */
    const val GAMBLE_BET = 1
    /** Lenh gamble dung de lam quest. */
    const val GAMBLE_COMMAND = "owo cf"
    /** Say owo co cooldown toi thieu 10 giay giua 2 lan -> khoang cach giua 2 lenh `owo` (ms). */
    const val SAY_GAP_MIN_MS = 10_500L
    const val SAY_GAP_MAX_MS = 13_000L
    /** Toi da bao nhieu lenh moi dot viec vat (tranh go dai 1 mach). */
    const val BURST = 4

    private val ACTIONS = listOf("hug", "kiss", "pat", "poke", "cuddle")
    private val ACTION_WORDS = Regex("\\b(actions?|hug|kiss|pat|poke|cuddle|slap|bite|lick|nom|punch|highfive)\\b")

    enum class Kind(val key: String) { SAY("say"), GAMBLE("gamble"), ACTION("action") }

    class Job(val kind: Kind, val remaining: Long)

    /** Quest nay thuoc loai nao app tu lam duoc? null = khong lam (hoac da co cach khac / can nguoi khac). */
    fun classify(q: HubQuestItem): Kind? {
        val text = ((q.title ?: "") + " " + (q.description ?: "")).lowercase(Locale.US)
        if (text.contains("receive") || text.contains("receiv")) return null          // can nguoi khac gui cho
        if (text.contains("say") && (text.contains("owo") || text.contains("uwu"))) return Kind.SAY
        if (text.contains("gamble") || text.contains("slots") || text.contains("coinflip") || text.contains("coin flip")) return Kind.GAMBLE
        if (ACTION_WORDS.containsMatchIn(text)) return Kind.ACTION
        return null
    }

    /**
     * Viec can lam: moi loai 1 muc, so lan con thieu = lon nhat trong cac quest cung loai (1 hanh dong tinh cho
     * moi quest cung loai) tru so lenh da go tu luc bot ghi nhan tien do lan cuoi (sentSince).
     */
    fun jobs(quests: List<HubQuestItem>?, sentSince: Map<Kind, Int>): List<Job> {
        if (quests.isNullOrEmpty()) return emptyList()
        val byKind = HashMap<Kind, Long>()
        for (q in quests) {
            if (q.done == true) continue
            val cur = q.current ?: continue
            val max = q.max ?: continue
            val left = max - cur
            if (left <= 0L) continue
            val kind = classify(q) ?: continue
            byKind[kind] = maxOf(byKind[kind] ?: 0L, left)
        }
        return byKind.mapNotNull { (kind, left) ->
            val remaining = left - (sentSince[kind] ?: 0)
            if (remaining > 0L) Job(kind, remaining) else null
        }
    }

    fun commandsFor(kind: Kind, count: Int): List<String> = List(count.coerceAtLeast(1)) {
        when (kind) {
            Kind.SAY -> "owo"
            Kind.GAMBLE -> "$GAMBLE_COMMAND $GAMBLE_BET"
            Kind.ACTION -> "owo ${ACTIONS[Random.nextInt(ACTIONS.size)]} <@$ACTION_TARGET_ID>"
        }
    }
}
