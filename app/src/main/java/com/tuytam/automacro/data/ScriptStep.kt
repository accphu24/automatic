package com.tuytam.automacro.data

/**
 * Cac loai "buoc" ma 1 kich ban co the co.
 * Them loai moi sau nay: chi can them 1 gia tri enum + xu ly trong ScriptEngine,
 * khong can sua lai cau truc du lieu.
 */
enum class StepType {
    OPEN_APP,     // mo 1 app theo package name
    WAIT,         // dung vai giay
    TAP,          // bam vao 1 phan tu (theo chu/id) hoac toa do du phong
    SWIPE,        // vuot tu 1 toa do den 1 toa do khac
    CHECK_TEXT,   // doc chu trong 1 o, so voi gia tri mong doi
    CHECK_EXISTS, // tim 1 phan tu (chu/id) co xuat hien tren man hinh khong
    NOTIFY        // bao dong: rung / chuong / am thanh
}

/**
 * 1 buoc trong kich ban.
 *
 * params: tham so rieng cho tung loai buoc (vi du TAP can "by" va "value").
 * onSuccess / onFail: id cua buoc se nhay toi neu buoc nay dung/sai.
 *   - de trong (null) o onSuccess -> mac dinh chay buoc ke tiep trong danh sach
 *   - de trong (null) o onFail    -> neu buoc that bai thi kich ban DUNG LAI
 *     tai do (khong tu lam tiep buoc sau, de tranh lam sai them)
 *
 * Cac tham so (params) dung chung theo tung loai buoc:
 *   OPEN_APP:     packageName
 *   WAIT:         seconds
 *   TAP:          by ("text" hoac "viewId"), value, fallbackX, fallbackY (tuy chon)
 *   SWIPE:        fromX, fromY, toX, toY, durationMs (tuy chon)
 *   CHECK_TEXT:   by, value (o can doc), expected (gia tri mong doi), timeoutSeconds (tuy chon)
 *   CHECK_EXISTS: by, value, timeoutSeconds (tuy chon, mac dinh 5 giay)
 *   NOTIFY:       vibrate ("true"/"false"), sound ("true"/"false"), message
 */
data class ScriptStep(
    val id: String,
    val type: StepType,
    val params: Map<String, String> = emptyMap(),
    val onSuccess: String? = null,
    val onFail: String? = null
)
