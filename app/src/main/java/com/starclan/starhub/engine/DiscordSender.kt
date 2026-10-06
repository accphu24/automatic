package com.starclan.starhub.engine

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.starclan.starhub.data.OwoTrackerPrefs
import com.starclan.starhub.service.AutoAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Ket qua 1 lan gui lenh vao Discord */
enum class SendResult {
    OK,
    DISCORD_NOT_OPEN,   // khong mo duoc Discord / man hinh tat / dang o app khac
    NO_INPUT,           // khong thay o nhap tin nhan
    TYPE_FAILED,        // go chu nhung doc lai khong thay chu trong o
    SEND_FAILED,        // da go nhung bam Gui xong chu van nam trong o
    CAPTCHA             // OwO dang doi giai captcha -> DUNG, de Ruby tu giai
}

/** Ket qua 1 lan MO Discord (dung cho nut "Thu mo Discord" va cho buoc mo truoc khi gui lenh) */
enum class OpenResult {
    OK_CHANNEL,          // da mo Discord va vao kenh theo link
    OK_APP_ONLY,         // da mo Discord (khong co link kenh nen chi mo app)
    OK_LINK_FAILED,      // mo duoc Discord nhung link kenh loi -> chi mo app, chua vao dung kenh
    NOT_INSTALLED,       // may khong co app Discord (com.discord)
    LAUNCH_ERROR,        // Android khong cho mo
    NOT_FOREGROUND       // da ra lenh mo nhung Discord khong len man hinh sau ~10 giay
}

/**
 * Gui 1 lenh (VD: "owo use 52") vao Discord, tung buoc co kiem tra:
 *  1. Dam bao Discord dang o truoc (mo dung kenh neu Ruby da dan link kenh).
 *  2. Neu man hinh dang hien captcha cua OwO -> dung ngay, khong gui gi them.
 *  3. Tim o nhap (theo cay giao dien, khong co thi cham theo toa do da luu).
 *  4. Go chu va DOC LAI de chac chu da vao o.
 *  5. Bam nut Gui (tim theo nhan "Send"/"Gui", khong co thi cham theo toa do da luu).
 *  6. Doc lai xem chu da roi khoi o nhap chua -> moi bao thanh cong.
 * Cac khoang nghi giua buoc la ngau nhien, de khong deu nhu may.
 */
class DiscordSender(private val service: AutoAccessibilityService) {

    companion object {
        private const val TAG = "DiscordSender"
        const val DISCORD_PACKAGE = "com.discord"

        /** Chi 1 lenh duoc go vao Discord tai 1 thoi diem (farm va lenh thay gem khong go chong cheo) */
        val sendMutex = Mutex()
    }

    private fun foregroundPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    private suspend fun humanPause(minMs: Long, maxMs: Long) = delay(Random.nextLong(minMs, maxMs))

    /**
     * Mo Discord (vao dung kenh neu co link), doi toi da ~10 giay cho Discord len man hinh.
     * Tra ve ly do cu the de nut "Thu mo Discord" bao duoc loi o dau.
     */
    suspend fun openDiscordDetailed(channelLink: String): OpenResult {
        val pm = service.packageManager
        val installed = try {
            pm.getPackageInfo(DISCORD_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
        if (!installed) {
            Log.w(TAG, "Khong tim thay app Discord tren may")
            return OpenResult.NOT_INSTALLED
        }

        val link = channelLink.trim()
        var started = false
        var usedLink = false
        if (link.isNotEmpty()) {
            try {
                val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .setPackage(DISCORD_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(linkIntent)
                started = true
                usedLink = true
            } catch (e: Exception) {
                Log.w(TAG, "Mo link kenh loi (${e.message}) - thu mo app Discord khong kem link")
            }
        }
        if (!started) {
            val launchIntent = pm.getLaunchIntentForPackage(DISCORD_PACKAGE)
            if (launchIntent == null) {
                Log.w(TAG, "Khong lay duoc intent mo Discord")
                return OpenResult.LAUNCH_ERROR
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                service.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Mo Discord loi: ${e.message}")
                return OpenResult.LAUNCH_ERROR
            }
        }

        for (i in 0 until 25) {
            delay(400)
            if (foregroundPackage() == DISCORD_PACKAGE) {
                delay(1500) // cho kenh tai xong
                return when {
                    usedLink -> OpenResult.OK_CHANNEL
                    link.isNotEmpty() -> OpenResult.OK_LINK_FAILED
                    else -> OpenResult.OK_APP_ONLY
                }
            }
        }
        return OpenResult.NOT_FOREGROUND
    }

    /** Chi mo Discord (khong gui gi) - dung cho nut "Thu mo Discord". */
    suspend fun openOnly(channelLink: String): OpenResult = openDiscordDetailed(channelLink)

    private suspend fun openDiscord(channelLink: String): Boolean {
        val r = openDiscordDetailed(channelLink)
        return r == OpenResult.OK_CHANNEL || r == OpenResult.OK_APP_ONLY || r == OpenResult.OK_LINK_FAILED
    }

    /**
     * @param reopenChannel true -> mo lai Discord/kenh truoc khi gui (dung cho lenh DAU TIEN cua moi dot).
     */
    suspend fun send(text: String, settings: OwoTrackerPrefs.Settings, reopenChannel: Boolean): SendResult =
        sendMutex.withLock { sendWithoutLock(text, settings, reopenChannel) }

    /** Giong send() nhung KHONG tu lay khoa: chi goi khi da giu DiscordSender.sendMutex (vd go lenh roi bam nut lien tiep) */
    suspend fun sendWithoutLock(text: String, settings: OwoTrackerPrefs.Settings, reopenChannel: Boolean): SendResult {
        if (reopenChannel || foregroundPackage() != DISCORD_PACKAGE) {
            if (!openDiscord(settings.discordLink)) return SendResult.DISCORD_NOT_OPEN
        }

        if (NodeTools.looksLikeCaptcha(service.rootInActiveWindow)) return SendResult.CAPTCHA

        // --- O nhap ---
        var input = NodeTools.findInput(service)
        if (input != null) {
            NodeTools.clickNode(service, input)
        } else if (settings.hasDiscordTargets) {
            service.performClickAwait(settings.messageBoxX, settings.messageBoxY)
        }
        humanPause(500, 900)
        input = NodeTools.waitForInput(service, 2500)
        if (input == null) return SendResult.NO_INPUT

        // --- Go chu (co doc lai de chac) ---
        if (!NodeTools.typeInto(service, text)) return SendResult.TYPE_FAILED
        humanPause(400, 1100)

        // --- Bam Gui ---
        var clicked = false
        val sendButton = NodeTools.findSendButton(service.rootInActiveWindow)
        if (sendButton != null) clicked = NodeTools.clickNode(service, sendButton)
        if (!clicked && settings.hasDiscordTargets) {
            clicked = service.performClickAwait(settings.sendButtonX, settings.sendButtonY)
        }
        if (!clicked) return SendResult.SEND_FAILED

        // --- Kiem tra lai: chu da roi khoi o nhap chua ---
        delay(1000)
        val after = NodeTools.findInput(service)
        if (after != null && NodeTools.inputContains(after, text)) return SendResult.SEND_FAILED
        return SendResult.OK
    }
}
