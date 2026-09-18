package com.tuytam.automacro.data

/**
 * Kich ban MAU de test bo may - ghep dung theo vi du cua Ruby:
 * mo app -> doi 2s -> bam nut A -> kiem tra o noi dung dung -> bam Gui
 * -> kiem tra gui thanh cong -> neu sai o buoc nao thi bao dong (rung + am thanh).
 *
 * QUAN TRONG: cac gia tri co ghi "TODO_" ben duoi la gia tri gia, PHAI SUA
 * lai cho dung app/nut that truoc khi bam nut "Chay kich ban mau" trong app,
 * neu khong buoc do se tim khong thay va bao that bai (dung, khong phai loi code).
 */
object SampleScripts {
    val sendMessageExample = listOf(
        ScriptStep(
            id = "s1",
            type = StepType.OPEN_APP,
            params = mapOf("packageName" to "com.example.TODO_target_app")
        ),
        ScriptStep(
            id = "s2",
            type = StepType.WAIT,
            params = mapOf("seconds" to "2")
        ),
        ScriptStep(
            id = "s3",
            type = StepType.TAP,
            params = mapOf("by" to "text", "value" to "TODO_Nut_A")
        ),
        ScriptStep(
            id = "s4",
            type = StepType.CHECK_TEXT,
            params = mapOf(
                "by" to "viewId",
                "value" to "com.example.TODO_target_app:id/TODO_content_field",
                "expected" to "TODO_gia_tri_mong_doi"
            ),
            onFail = "s7"
        ),
        ScriptStep(
            id = "s5",
            type = StepType.TAP,
            params = mapOf("by" to "text", "value" to "Gửi")
        ),
        ScriptStep(
            id = "s6",
            type = StepType.CHECK_EXISTS,
            params = mapOf("by" to "text", "value" to "Gửi thành công", "timeoutSeconds" to "5"),
            onFail = "s7"
        ),
        ScriptStep(
            id = "s7",
            type = StepType.NOTIFY,
            params = mapOf(
                "vibrate" to "true",
                "sound" to "true",
                "message" to "Kịch bản gửi thất bại, cần kiểm tra lại"
            )
        )
    )
}
