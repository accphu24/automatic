# AI_CONTEXT.md — AutoMacro (TuyTam)

## Du an la gi
App Android tu dong hoa thao tac tren may (bam, vuot, go chu, mo app) - kieu
Tasker/MacroDroid. Muc dich chinh: ho tro choi OwO Bot tren Discord.
Nguoi dung chi quan sat, app tu thuc hien cac buoc theo "kich ban" da tao.
Repo GitHub: accphu24/automatic.

Dang ket hop voi du an rieng "owo-tracker" (bot Discord theo doi choi OwO,
repo Python/discord.py rieng) de tao 1 he thong tap trung: owo-tracker la
"bo nao" (biet luc nao can lam gi), AutoMacro la "tay chan" (thuc thi tren
dien thoai). Xem muc "Ket noi voi owo-tracker" ben duoi.

## Trang thai hien tai (da lam duoc)
- Khung project: Gradle, AndroidManifest, build qua GitHub Actions (khong dung gradlew,
  Gradle 8.6 duoc cai truc tiep tren runner). Ky bang debug.keystore CO DINH (commit
  trong repo o keystore/) de cac ban build khong xung dot chu ky khi cai de.
- Bo may chay kich ban (ScriptEngine): doc tung buoc, tim phan tu theo chu/ID (uu tien)
  hoac toa do du phong, ho tro re nhanh "neu loi thi nhay toi buoc X". run() tra ve
  true/false bao co chay het kich ban khong (dung de bao ket qua ve owo-tracker).
- 7 loai buoc: OPEN_APP, WAIT, TAP, SWIPE, TYPE_TEXT (go vao o dang focus, ho tro
  placeholder {{ten}}), CHECK_TEXT, CHECK_EXISTS, NOTIFY.
- Man hinh chinh: danh sach kich ban da luu (Room), nut Chay/Xoa moi kich ban,
  nut chay kich ban mau de test, nut Cai dat ket noi owo-tracker.
- Man hinh tao kich ban (ScriptEditorActivity): dat ten, "+ Them buoc" mo hop thoai
  theo tung loai, bam lai vao 1 buoc da them de SUA (ke ca chon "neu loi" tro toi
  1 buoc them SAU). Luu kich ban serialize sang JSON (Gson) vao Room.
- CHE DO GHI (record): bam nut "Ghi kich ban moi" -> bong bong noi co bang danh sach
  hanh dong danh so, cap nhat ngay khi ghi duoc (TYPE_ACCESSIBILITY_OVERLAY). Tu dong
  bat TAP (theo chu/ID) va OPEN_APP (loc bo ban phim/systemui). 2 nut thu cong tren
  bong bong: 👆 (danh dau 1 diem de Bam) va ➕ (cham 2 diem dau/cuoi de tao buoc Vuot
  chinh xac) - ca 2 co hien cham mau xac nhan vi tri vua cham.
  GIOI HAN: van CHUA tu ghi duoc go chu (phai dung TYPE_TEXT thu cong + 👆 cho nut Gui).
- Ket noi owo-tracker: man hinh Cai dat (nut ⚙️) luu URL API + token (SharedPreferences),
  va vi tri (toa do) O NHAP TIN NHAN + NUT GUI tren Discord - cai 1 LAN DUY NHAT qua
  wizard cham 2 diem. Khi bat, service cu 15 giay goi GET /commands/pending 1 lan;
  MOI lenh nhan duoc (bat ky "action" nao, khong can khop ten kich ban) deu chay dung
  3 buoc: bam o nhap -> go params.command_text -> bam Gui, roi POST /commands/{id}/ack
  bao done/failed. Don gian hoa tu ban dau (bo phan tao kich ban rieng + khop ten,
  vi Ruby thay qua phuc tap) - gio KHONG can Room/kich ban cho phan nay nua.

- Man hinh HUB (nut "Mo Hub OwO" o man hinh chinh -> HubActivity): hien du lieu moi
  nhat owo-tracker da doc duoc tu tin nhan OwO (HuntBot co dong ho dem nguoc, gem dang
  dung + gem du phong, quest, doi hinh, tran dau, pet/zoo, vu khi, kho do). Goi
  GET /hub (cung token voi /commands/pending), tu lam moi moi 30 giay khi dang mo, moi
  giay chi cap nhat dong ho + chu "X phut truoc". Cham the Zoo/Vu khi/Kho do de mo rong.
  Muc nao bot chua tung thay thi hien "Chua co du lieu". HubFormat.kt la ham thuan (khong
  dung Android) chuyen JSON thanh chu; HubModels.kt la cac lop Gson khop JSON cua /hub.
  Co them the Daily (dem nguoc gio nhan + streak), Cowoncy, va dem nguoc quest ke tiep.
  KHONG co profile: `owo profile` cua OwO la 1 tam ANH (khong co chu) nen bot khong doc duoc.

## Chua co (con thieu)
- Sua lai 1 kich ban DA LUU tu Room (hien chi xoa lam lai duoc, chua mo lai de sua).
- Doi thu tu buoc bang keo-tha.
- Ghi tu dong thao tac go chu (phai lam thu cong).
- Moi chi lam xong case dau tien voi owo-tracker (swap_gem) - hunt/battle tu dong
  CHUA lam (rui ro OwO phat hien cao hon, can ban owo-tracker biet cooldown ngan truoc).
  Nhung ve mat AutoMacro thi KHONG can lam gi them de ho tro action moi - chi can
  owo-tracker gui "command_text" dung, AutoMacro tu chay duoc ngay.

## Ket noi voi owo-tracker (repo rieng)
- owo-tracker (Python, chay Railway) co API rieng: GET /commands/pending,
  POST /commands/{id}/ack, GET /hub (du lieu cho man hinh Hub), xac thuc bang header Authorization: Bearer <token>.
  Token nay KHAC MONGODB_URI - dien thoai khong bao gio cam chuoi ket noi Mongo that.
- Ca 2 phia phai dat CUNG 1 token: owo-tracker qua bien moi truong AUTOMACRO_API_TOKEN,
  AutoMacro qua man hinh Cai dat trong app (luu SharedPreferences, khong phai Room).
- AutoMacro chi can biet 2 toa do (o nhap tin nhan + nut Gui Discord), cai 1 lan qua
  nut "📍 Cai vi tri..." trong man hinh Cai dat. TU DONG dung cho MOI lenh owo-tracker
  gui toi sau nay (khong can tao/dat ten kich ban rieng cho tung loai lenh nua).
  Lenh tu server chi can co field "command_text" trong params la chay duoc.
- Case dau tien da lam: "swap_gem" - owo-tracker tu phat hien gem het do ben, ghi lenh
  kem item_code/gem_tier/command_text da chon san (tier thap nhat con lai).

## Cong nghe
- Kotlin, Android SDK (minSdk 26, targetSdk/compileSdk 34)
- AccessibilityService: dispatchGesture (bam/vuot theo toa do), performAction ACTION_CLICK
  va ACTION_SET_TEXT (bam/go truc tiep vao node), findAccessibilityNodeInfosByText/ByViewId,
  findFocus(FOCUS_INPUT) cho buoc go chu
- TYPE_ACCESSIBILITY_OVERLAY (WindowManager) cho bong bong ghi kich ban + man hinh chon diem
- Room (SQLite) luu kich ban, Gson chuyen doi List<ScriptStep> <-> JSON
- Kotlin Coroutines cho buoc WAIT, chay kich ban, va vong lap dong bo owo-tracker
- HttpURLConnection (co san trong Android, khong them thu vien) de goi API owo-tracker
- Build qua GitHub Actions (.github/workflows/build.yml) — chay `gradle assembleDebug`

## Cau truc thu muc chinh
app/src/main/java/com/tuytam/automacro/
  MainActivity.kt                         - danh sach kich ban, nut chay mau, ghi, cai dat
  HubActivity.kt                          - man hinh Hub (xem du lieu OwO tu owo-tracker)
  ScriptEditorActivity.kt                  - tao/sua kich ban, nap buoc tu ban ghi
  ScriptListAdapter.kt                     - hien danh sach kich ban da luu
  service/AutoAccessibilityService.kt      - chay kich ban + che do ghi + bong bong + dong bo
  engine/ScriptEngine.kt                   - bo may thuc thi tung buoc
  data/                                    - ScriptStep, ScriptEntity/Dao/Database, ScriptJson,
                                              ScriptRepository, SampleScripts, StepSummary,
                                              OwoTrackerApi, OwoTrackerPrefs, HubModels, HubFormat

## Ghi chu quan trong
- Nguoi dung phai tu vao Cai dat may bat quyen Accessibility (khong the tu bat bang code)
- Moi lan cai de APK moi tu build, Android co the khoa lai "Cai dat han che" - phai vao
  Thong tin ung dung -> menu 3 cham (hoac tim "cai dat han che" trong Cai dat) -> Cho phep
  cai dat han che -> roi moi bat lai duoc quyen Accessibility
- Neu dinh dang Google Play sau nay: can khai bao ro muc dich dung quyen Accessibility
  va QUERY_ALL_PACKAGES, Google xet kha ky cac quyen nay — dung rieng/cai APK thu cong
  thi khong van de gi
- RUI RO CAN NHO: OwO Bot co he thong chong macro (bat sus/captcha), tu dong choi ho
  co the vi pham dieu khoan Discord - can than khi mo rong sang hunt/battle tu dong,
  nen them do tre ngau nhien (jitter) thay vi canh dung giay khi lam phan do.
