package com.tuytam.automacro.data

/**
 * 1 icon co the hien theo 3 cach, thu tu uu tien:
 *  1. Anh that tu Discord (imageUrl) — khi co ID emoji tuy chinh.
 *  2. Ky tu Unicode co san (unicode) — khi la emoji chuan (vd con vat thuong trong zoo).
 *  3. Chu cai dau cua ten (fallbackLetter) — khi chua biet gi ca.
 */
data class IconSpec(val emojiId: String? = null, val animated: Boolean? = null,
                     val unicode: String? = null, val fallbackLetter: String? = null) {
    val imageUrl: String?
        get() = emojiId?.let { "https://cdn.discordapp.com/emojis/$it.png?size=48&quality=lossless" }
}

/** Anh xa 3 truong emoji_id/emoji_animated/emoji (tra ve tu owo-tracker) + ten sang 1 IconSpec. */
fun iconOf(emojiId: String?, animated: Boolean?, unicode: String?, name: String?): IconSpec =
    IconSpec(emojiId, animated, unicode, name?.trim()?.firstOrNull()?.uppercaseChar()?.toString())

val NO_ICON = IconSpec()

// ---- Icon cho 6 o tom tat o dau man hinh Hub ----

fun dailyTileIcon(d: HubDaily?): IconSpec =
    if (d != null) iconOf(d.emojiId, d.emojiAnimated, d.emoji, "$") else IconSpec(unicode = "⏰")

fun huntbotTileIcon(): IconSpec = IconSpec(unicode = "🤖")

fun cowoncyTileIcon(c: HubCowoncy?): IconSpec =
    if (c != null) iconOf(c.emojiId, c.emojiAnimated, c.emoji, "$") else IconSpec(unicode = "💰")

fun questTileIcon(q: HubQuest?): IconSpec {
    val first = q?.quests?.firstOrNull { it.done != true } ?: q?.quests?.firstOrNull()
    return if (first != null) iconOf(first.emojiId, first.emojiAnimated, first.emoji, first.title)
    else IconSpec(unicode = "📋")
}

fun gemsTileIcon(g: HubGems?): IconSpec {
    val lowest = g?.equipped.orEmpty().minByOrNull { it.percent ?: 100 }
    return if (lowest != null) iconOf(lowest.emojiId, lowest.emojiAnimated, lowest.emoji, lowest.tier)
    else IconSpec(unicode = "💎")
}

// ---- Icon cho tieu de cac the chi tiet ben duoi ----

fun teamCardIcon(t: HubTeam?): IconSpec {
    val first = t?.members.orEmpty().firstOrNull()
    return if (first != null) iconOf(first.emojiId, first.emojiAnimated, first.emoji, first.name)
    else IconSpec(unicode = "⚔️")
}

fun weaponsCardIcon(): IconSpec = IconSpec(unicode = "🗡️")

fun inventoryCardIcon(): IconSpec = IconSpec(unicode = "🎒")

fun zooTileIcon(z: HubZoo?): IconSpec {
    // Uu tien hien icon cua tier HIEM NHAT ma nguoi choi dang co (it loai nhat), an tuong hon la tier thuong
    val rarest = z?.pets.orEmpty().filter { (it.count ?: 0) > 0 }.minByOrNull { it.count ?: Long.MAX_VALUE }
    return if (rarest != null) iconOf(rarest.emojiId, rarest.emojiAnimated, rarest.emoji, rarest.name)
    else IconSpec(unicode = "🐾")
}
