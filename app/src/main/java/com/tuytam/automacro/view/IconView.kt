package com.tuytam.automacro.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.tuytam.automacro.data.IconSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 1 o vuong hien icon, uu tien theo IconSpec: anh that cua Discord (tai qua
 * mang) > ky tu Unicode to > chu cai dau ten > cham tron trung tinh.
 *
 * Dung lai duoc: moi lan bind() se huy ket qua tai anh cua lan bind() truoc
 * (qua bindToken), nen an toan khi View nay duoc tai su dung cho du lieu khac
 * (vd cuon danh sach) ma khong hien nham anh cua dong cu.
 */
class IconView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = INVISIBLE
    }
    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        includeFontPadding = false
    }
    private var bindToken = 0

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** [scope] la lifecycleScope cua Activity/Fragment goi ham nay — IconView tu no khong giu scope rieng. */
    fun bind(scope: CoroutineScope, spec: IconSpec, unicodeSp: Float = 20f, letterSp: Float = 14f) {
        val token = ++bindToken
        image.visibility = INVISIBLE
        image.setImageDrawable(null)
        when {
            spec.unicode != null -> {
                label.text = spec.unicode
                label.textSize = unicodeSp
                label.visibility = VISIBLE
            }
            spec.imageUrl != null -> {
                label.text = spec.fallbackLetter ?: "•"
                label.textSize = letterSp
                label.visibility = VISIBLE
                val url = spec.imageUrl!!
                scope.launch {
                    val bmp = EmojiImageLoader.load(url)
                    if (bindToken != token) return@launch  // View da nhan du lieu khac trong luc cho
                    if (bmp != null) {
                        image.setImageBitmap(bmp)
                        image.visibility = VISIBLE
                        label.visibility = INVISIBLE
                    }
                }
            }
            spec.fallbackLetter != null -> {
                label.text = spec.fallbackLetter
                label.textSize = letterSp
                label.visibility = VISIBLE
            }
            else -> {
                label.text = "•"
                label.textSize = unicodeSp
                label.visibility = VISIBLE
            }
        }
    }
}
