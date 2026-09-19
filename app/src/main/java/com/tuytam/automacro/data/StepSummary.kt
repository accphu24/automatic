package com.tuytam.automacro.data

/**
 * Mo ta 1 buoc bang 1 dong chu de nguoi doc hieu ngay - dung chung o ca
 * man hinh tao kich ban va o bang tren bong bong khi dang ghi, de 2 noi
 * luon hien thi giong nhau.
 */
fun summarizeStep(step: ScriptStep): String {
    return when (step.type) {
        StepType.OPEN_APP -> "Mở app ${step.params["packageName"]}"
        StepType.WAIT -> "Đợi ${step.params["seconds"]} giây"
        StepType.TAP -> "Bấm \"${step.params["value"]}\""
        StepType.SWIPE -> "Vuốt (${step.params["fromX"]},${step.params["fromY"]}) → (${step.params["toX"]},${step.params["toY"]})"
        StepType.CHECK_TEXT -> "Kiểm tra \"${step.params["value"]}\" = \"${step.params["expected"]}\""
        StepType.CHECK_EXISTS -> "Kiểm tra có \"${step.params["value"]}\" trên màn hình"
        StepType.NOTIFY -> "Báo động: ${step.params["message"]}"
    }
}
