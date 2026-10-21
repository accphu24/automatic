package com.starclan.starhub

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.starclan.starhub.databinding.ActivityMainBinding

/**
 * Khung chinh: chi dieu khien 2 tab duoi cung (Hub / Cai dat; da bo tab Tu dong), moi tab la 1 Fragment rieng.
 *  - navHub       -> HubFragment       (xem du lieu OwO tu owo-tracker)
 *  - navSettings  -> SettingsFragment  (quyen Accessibility + ket noi owo-tracker)
 * Mac dinh mo tab Hub. Chuyen tab bang replace() nen moi Fragment tu khoi dong lai
 * (Hub tu lay du lieu moi, danh sach kich ban tu doc lai) — dung y, khong phai loi.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.navHub -> HubFragment()
                R.id.navSettings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit()
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.navHub
        }
    }
}
