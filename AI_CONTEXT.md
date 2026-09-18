# AI_CONTEXT.md — AutoMacro (TuyTam)

## Du an la gi
App Android tu dong hoa thao tac tren may (bam, vuot, mo app) - kieu Tasker/MacroDroid.
Nguoi dung chi quan sat, app tu thuc hien cac buoc theo "kich ban" da tao.

## Trang thai hien tai
- Da dung khung project: Gradle, AndroidManifest, Accessibility Service rong, man hinh chinh,
  Room DB rong (chua co man hinh tao kich ban).
- CHUA co: man hinh tao kich ban, bo may doc & chay kich ban, logic noi UI <-> AccessibilityService.

## Cong nghe
- Kotlin, Android SDK (minSdk 26, targetSdk/compileSdk 34)
- AccessibilityService (android.accessibilityservice) de "nhin" man hinh va thuc hien thao tac
  qua dispatchGesture (performClick / performSwipe da co san trong AutoAccessibilityService.kt)
- Room (SQLite) de luu kich ban tren may (ScriptEntity / ScriptDao / AppDatabase)
- Build qua GitHub Actions (.github/workflows/build.yml) — chay `gradle assembleDebug`,
  KHONG dung gradlew nen khong can PC hay wrapper jar

## Cau truc thu muc chinh
app/src/main/java/com/tuytam/automacro/
  MainActivity.kt                        - man hinh chinh, kiem tra & bat quyen Accessibility
  service/AutoAccessibilityService.kt     - service nen, co san performClick()/performSwipe()
  data/                                   - Room: ScriptEntity, ScriptDao, AppDatabase

## Viec can lam tiep (goi y thu tu)
1. Thiet ke cau truc 1 "kich ban": trigger (khi nao chay) + danh sach action (lam gi)
2. Man hinh tao/sua kich ban (UI)
3. Bo may doc kich ban tu Room va goi performClick/performSwipe trong AutoAccessibilityService
4. Test tren may that qua APK build tu GitHub Actions (tab Actions -> Artifacts)

## Ghi chu quan trong
- Nguoi dung phai tu vao Cai dat may bat quyen Accessibility (khong the tu bat bang code)
- Neu dinh dang Google Play sau nay: can khai bao ro muc dich dung quyen Accessibility,
  Google xet kha ky muc nay — dung rieng/cai APK thu cong thi khong van de gi
