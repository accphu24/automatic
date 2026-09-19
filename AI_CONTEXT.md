# AI_CONTEXT.md — AutoMacro (TuyTam)

## Du an la gi
App Android tu dong hoa thao tac tren may (bam, vuot, mo app) - kieu Tasker/MacroDroid.
Nguoi dung chi quan sat, app tu thuc hien cac buoc theo "kich ban" da tao.
Repo GitHub: accphu24/automatic.

## Trang thai hien tai (da lam duoc)
- Khung project: Gradle, AndroidManifest, build qua GitHub Actions (khong dung gradlew,
  Gradle 8.6 duoc cai truc tiep tren runner).
- Bo may chay kich ban (ScriptEngine): doc tung buoc, tim phan tu theo chu/ID (uu tien)
  hoac toa do du phong, ho tro re nhanh "neu loi thi nhay toi buoc X".
- Man hinh chinh: danh sach kich ban da luu (Room), nut Chay/Xoa moi kich ban,
  nut chay kich ban mau de test.
- Man hinh tao kich ban (ScriptEditorActivity): dat ten, "+ Them buoc" mo hop thoai
  theo tung loai (Mo app / Doi / Bam / Kiem tra noi dung / Kiem tra xuat hien / Bao dong),
  bam lai vao 1 buoc da them de SUA (ke ca chon "neu loi" tro toi 1 buoc them SAU).
  Luu kich ban serialize sang JSON (Gson) vao Room.
- CHE DO GHI (record): bam nut "Ghi kich ban moi" -> hien bong bong do noi tren man hinh
  (TYPE_ACCESSIBILITY_OVERLAY, khong can xin quyen rieng) -> AutoMacro tu quan sat luc
  Ruby mo app va bam nut that, tu sinh buoc OPEN_APP/TAP + tu chen WAIT theo khoang cach
  thoi gian giua cac thao tac -> bam lai vao bong bong (khong keo) de dung ghi, mo thang
  sang man hinh sua kich ban voi cac buoc da ghi san.
  GIOI HAN: ban ghi CHUA bat duoc vuot (swipe), go chu vao o nhap, va khong tu them
  buoc Kiem tra/Bao dong - Ruby van can tu them tay sau khi ghi.

## Chua co (con thieu)
- Sua lai 1 kich ban DA LUU tu Room (hien chi xoa lam lai duoc, chua mo lai de sua).
- Doi thu tu buoc bang keo-tha.
- Bat/tat kich ban chay tu dong theo dieu kien (hien chi chay khi tu bam nut ▶).
- Ghi lai thao tac vuot va go chu.

## Cong nghe
- Kotlin, Android SDK (minSdk 26, targetSdk/compileSdk 34)
- AccessibilityService: dispatchGesture (bam/vuot theo toa do), performAction ACTION_CLICK
  (bam truc tiep vao node tim duoc), findAccessibilityNodeInfosByText/ByViewId (tim phan tu)
- TYPE_ACCESSIBILITY_OVERLAY (WindowManager) cho bong bong ghi kich ban
- Room (SQLite) luu kich ban, Gson chuyen doi List<ScriptStep> <-> JSON
- Kotlin Coroutines cho buoc WAIT va chay kich ban trong nen khong lam dung
- Build qua GitHub Actions (.github/workflows/build.yml) — chay `gradle assembleDebug`

## Cau truc thu muc chinh
app/src/main/java/com/tuytam/automacro/
  MainActivity.kt                         - danh sach kich ban, nut chay mau, nut ghi
  ScriptEditorActivity.kt                  - tao/sua kich ban, nap buoc tu ban ghi
  ScriptListAdapter.kt                     - hien danh sach kich ban da luu
  service/AutoAccessibilityService.kt      - chay kich ban + che do ghi + bong bong
  engine/ScriptEngine.kt                   - bo may thuc thi tung buoc
  data/                                    - ScriptStep, ScriptEntity/Dao/Database,
                                              ScriptJson, ScriptRepository, SampleScripts

## Ghi chu quan trong
- Nguoi dung phai tu vao Cai dat may bat quyen Accessibility (khong the tu bat bang code)
- Moi lan cai de APK moi tu build, Android co the khoa lai "Cai dat han che" - phai vao
  Thong tin ung dung -> menu 3 cham (hoac tim "cai dat han che" trong Cai dat) -> Cho phep
  cai dat han che -> roi moi bat lai duoc quyen Accessibility
- Neu dinh dang Google Play sau nay: can khai bao ro muc dich dung quyen Accessibility
  va QUERY_ALL_PACKAGES, Google xet kha ky cac quyen nay — dung rieng/cai APK thu cong
  thi khong van de gi
