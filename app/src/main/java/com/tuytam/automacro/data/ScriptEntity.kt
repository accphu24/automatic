package com.tuytam.automacro.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 1 "kich ban" tu dong hoa: khi nao chay, va lam gi.
 * `actionsJson` tam thoi luu danh sach hanh dong duoi dang chuoi JSON
 * de linh hoat - se co man hinh tao kich ban chi tiet o buoc sau.
 */
@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val triggerApp: String? = null,
    val actionsJson: String = "[]",
    val enabled: Boolean = true
)
