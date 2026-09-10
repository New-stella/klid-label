# 진행 상태 — CBD 최종본 + 증적 재촬영 (2026-09-09)

> 이 파일은 세션 압축·교체에도 살아남는 상태 기록이다. 값은 전부 실측이다.

## 1. 끝난 것

### CBD 산출물 3벌 — 인계 폴더 교체 완료
- 위치: `deploy/klid-at-인계-20260907/04-설계산출물/{포털포함,포털제외,포털향}` (각 44파일)
- 생성본 원본: `~/Desktop/CBD-생성본-20260909b/`
- 기준: LogiCraft 09-09 스냅샷(`20260909b`) · md_uml `ad80e95`
- 세 벌 전부 14/14 변환 · 실패 0 · **편집 흔적 0건**(분모 88,046 / 82,958 / 40,298칸)
- 옛 판은 `~/Desktop/CBD-구판보관-인계20260907/` 으로 이동(경고문 동봉)
- 옛 스냅샷/생성본: `~/Desktop/CBD-생성본-20260909/`(오전 판) · `~/Desktop/CBD-최종-20260907g-*`(직전 판)

### LogiCraft 원장 3자리 수정 (편집 흔적 유출 차단)
`UC-019` v33 · `UC-027` v35 · `SCREEN-044` v14 — 바이트 대조 3/3, 리프 대조 69,357 중 3건만 변경.

### 246 dev 배포 (관제 채널)
- WAR `96be5be2d` · context `/label-studio` · health 200 · **Flyway v36**
- FE 정적 `96be5be2d` — 서빙 번들 `index-DJUUiwDP.js` = 빌드 산출물 동일 확인
- 절차 정본: `deploy/dev-server-246/README.md` (JBoss WAR + apache — docker compose 스택 아님)

### 246 에 교통사고 영상 재인입
- **`raw_sn=26`** · `vms_clip_id=hs003-traffic-1788920356` · `rcptn_sn=15`
- 이벤트: **`EV03000101`(교통사고)** · `evnt_clsf_cd=03` · `evnt_ctgry_cd=0001` · `vrfc_evnt_type_cd=car_accident`
- 원본: `/nas-storage/data/upload/v2/CLP-41590-NBY-20260728-00051/crop/body_crop_305.4-450.0.mp4`
  (CCTV `uid_hs003` · 640x480 · 144.6s · 자막·그래픽 없는 실제 도로 CCTV — 오토라벨 증적에 적합)
- 상태: 비식별 자동 완주 → **`MARKING_READY`** · 배정 `assignment_id=19` → worker 2001(최라벨)
- DB 접속: `nt ssh cudo_246` → `docker exec -e PGPASSWORD=$(docker exec klid-authoring-jboss printenv CONTROL_DB_PASSWORD) postgis-klid psql -U cudo -d klid`

## 2. 결함 — 설계 확정 완료, 구현 진행 중 (CO-20260909)

**베이스 경로 배포에서 마킹 영상이 재생되지 않는다. 온프렘도 같은 조건.**
- `backend/.../video/service/VideoStreamService.java:208` 이 `"/api/v1/videos/" + rawSn + "/stream?exp="` 로 **하드코딩 절대경로** 생성
- `frontend/src/pages/MarkingPage.tsx:348` 이 그 값을 `<video src>` 에 그대로 사용
- 결과: `http://192.168.102.246:8088/api/v1/videos/26/stream?…` → **404** (base `/label-studio` 누락)
- 재시도 **150회+/7초** 폭주(매 요청 새 서명 발급)
- 로컬(base 없음)에서는 증상이 안 드러남 → 회귀 시험은 **base 있는 조건**으로 걸어야 함
- 함께 볼 것: `frontend/src/features/portal/uploads/markingApi.ts` 의 포털 업로드 stream-url

### 설계 확정 ✅ (2026-09-09 · Phase 3.6)
`API-114` v2→**v3** · `SCREEN-006` v52→**v53** · `SEQ-036` v3→**v4** — 셋 다 순수 삽입 기록.
확정 요지: **응답 `url` 은 API 기준 경로(배포 접두 미포함) · 접두 결합은 소비 측 책임 · 재시도 상한.**
⇒ **백엔드 계약은 그대로 두고 프론트만 고친다.** 상세·잔여 판단은 CO §6.
⚠ `SEQ-036` 은 이미 이 사실을 갖고 있었고 `SCREEN-006` 만 뒤처져 있었다 — 층 단위 정합의 실증 사례.
⚠ `SCREEN-006.covered_by_acceptances` 가 **빈 배열**이라 `AC-1013`·`AC-1015` 가 `analyze_impact` 에 안 뜬다(설계 결손).

### ★246을 passthrough 향으로 정렬 (2026-09-09 · 사용자 판단)

두 번째 결함이 있었다 — FE 접두 결합만으로는 **404 가 401 로 바뀔 뿐**이었다. `StreamNonceCookie` 가
쿠키 Path 를 **앱 내부 contextPath** 로 만드는데 246 프록시가 접두를 벗겨 브라우저 경로와 갈렸다.

**원인은 향 불일치였다** — 246 배포 WAR 이 `<context-root>/label-studio</context-root>`(**strip**)인데
현장 반입 산출물은 `/label-studio/api`(**passthrough**)다. 246 은 현장을 본뜬 환경인데 이 축이
안 맞아 있었고, 그래서 **246 에서만 나는 결함을 현장 결함으로 오판할 뻔했다.**

⇒ 프록시로 덧대는 임시조치를 걷어내고 **246을 passthrough 로 정렬**했다(WAR `jboss-web.xml`
context-root + apache 전달 대상, **반드시 함께**). 재빌드 없이 배포본의 그 파일만 교체.
**실측 4축 통과** — health 200 · `/v1/me` 401 · 쿠키 Path `/label-studio/api/v1/videos` ·
스트림 **206** · 정적 화면 200.

**온프렘은 passthrough 이고 이 결함이 없다** — 반입본을 올렸고 API 가 도는 것이 근거다
(strip 이었다면 컨텍스트 불일치로 API 전건 404). 상세·되돌리기 백업은 CO §6-2.

### 구현 🔄 진행 중
`klid-web-implementer` — 접두 결합 유틸 단일화 · 포털 경로 동일 결함 확인 · 재시도 상한 ·
**베이스 경로 있는 조건의 회귀 가드 + 뮤테이션 확인**.

## 3. 남은 일 (순서)

1. 위 결함 수정 → 246 재배포 → `/marking/26` 에서 영상 재생 확인(readyState>0, videoWidth>0)
2. **증적 25컷 전량 재촬영** — 하네스 `frontend/capture-evidence.mjs` (**하네스 개조 ✅ 완료**)
   - ⚠ `frontend/` 안에서 실행해야 playwright resolve 됨
   - 원장: `~/Desktop/CBD-증적캡처-20260905/capture-manifest.json` · 루트 `~/Desktop/CBD-캡처루트-20260906`
   - 케이스 15건 / 컷 25 / 파일 23(003-01↔003-02-cut2, 007-01↔023-01-cut2 공유)

   **개조한 것**
   - 대상 ID 를 **환경변수로 뺐다**(구 하드코딩 `marking/1`·`review/1`·`openLabel(13/1/5)`):
     `CAPTURE_RAW_SN` / `CAPTURE_REVIEW_ID` / `CAPTURE_FRAME_MAIN` / `CAPTURE_FRAME_AI` / `CAPTURE_FRAME_META`
     ⚠ `/review/:id` 는 **영상 번호가 아니라 검수 번호**다 — 246 에서 따로 조회해야 한다
   - `waitForVideoReady()` 신설 — 영상이 `readyState>=1 && videoWidth>0` 이 아니면 **진단값 담아 던진다**.
     이것이 없어서 안 뜬 영상을 조용히 찍었다
   - `019-01` 이 **실제로 마크를 찍는다** — 차단 화면이면 던지고, 수동 모드(`Digit1`)로 전환해
     지정 시점마다 `seekVideo` → `Space`, 마지막에 `현재 마킹 (N건)` 을 단정한 뒤 촬영

   **246 실행 설정 (영상 26 기준)**
   ```
   CAPTURE_APP=http://192.168.102.246:8088/label-studio
   CAPTURE_RAW_SN=26
   CAPTURE_MARK_SECONDS=96,100,104,108,112,116
   # CAPTURE_FRAME_* · CAPTURE_REVIEW_ID 는 마킹 완료 후 실제 값 조회해서 지정
   ```
   ★**마킹 시점 96~116초의 근거는 영상 실측이다**(임의값 아님). 이 영상은 **사고 장면이 없는 정상
   통행 도로 CCTV** 이고 `교통사고` 는 인입 시 붙인 이벤트 유형일 뿐이다. 마킹은 프레임 추출 위치를
   정하므로 **대상이 드문 구간에 찍으면 빈 캔버스가 라벨링 증적으로 남는다.** 24~80초는 차가
   한두 대뿐이고 **92~122초가 정체 구간**이라 근거리 차량이 크고 또렷하며, 천천히 움직여
   4초 간격 프레임에 **같은 차량이 이어진다**(추적·보간 증적 성립 조건).
   확인 자료: `<scratchpad>/vid/sheet.png`(8초 간격 전구간) · `near.png`(근거리) · `fine.png`(92~122초 2초 간격)
3. ~~**D11 단위시험 케이스 7건 제거**~~ ✅ **완료(2026-09-09)** — 58 → 51케이스 · 35 → 31블록
   - 파일: `/Users/ck/Documents/workspace/klid/docs/design/D11-R1-단위시험절차서-기능정상-컴포넌트기준-UI절차-정합수정.md`
   - 제거 그룹 4종 통째: `UT-035`(산출물 폴더검사·적재 2건) · `UT-036`(이관 이력 1건) · `UT-037`(마킹 폴더검사·적재 2건) · `UT-042`(파일 업로드·나눠올리기 2건)
   - **줄 단위 diff `delete` opcode 둘뿐** — `replace`·`insert` 0 (순수 삭제 증명). 잔재 참조 0건
   - 제·개정 이력 5.7 행 추가(문자 단위 diff `insert` 1건 — 순수 삽입)
   - ⚠ 원본 백업: `<scratchpad>/d11-backup/D11.orig.md` (sha256 `3131d3bf…`). **이 폴더는 git 밖이라 되돌릴 안전망이 그 사본뿐이다**
   - ⚠ 말미 「활성 R1 UC 18종 전부 정상 케이스 보유」는 **재검증하지 않았다**(개정 4.6이 본문 UC 참조를 걷어내 이 파일만으론 대조 불가). 그 문장은 이력 5.6(26케이스) 시점 것이고 제거한 4블록은 그 뒤 추가분이라 주장 자체는 건드리지 않는다
   - ⚠ 캡처 13케이스(`001~023`)와 **겹치지 않는다** — I2 증적 영향 0
4. CBD 3벌 재생성(새 캡처·새 D11 반영) → 검증 → **오늘 날짜 배포판 폴더 + 압축** (사용자 지시)

## 4. 잊으면 다시 밟는 함정

- `grep` 이 소스를 바이너리로 판정해 **조용히 0건** → `-a` 필수. md_uml 쪽은 NUL 원인 제거됨(`4edb30b`)
- `git ls-files` / `git status --porcelain` 은 **한글 경로를 8진 이스케이프**로 냄 → `-z` 필수(안 쓰면 파일을 못 열고 조용히 건너뜀)
- zsh 은 **큰따옴표 없는 변수를 단어 분리하지 않음** → 인자 묶음 변수 금지
- 컨테이너 `Mounts` 가 **있어도** 구운 이미지일 수 있음(데이터 볼륨) → 이미지 Created 시각을 커밋과 대조
- `--captures` 는 **포털포함·포털제외 둘 다** 준다. 포털향만 안 준다(원장 케이스가 범위 밖 → 게이트가 막는 게 정상)
- I2 hwpx 가 **10MB 근처**인지로 캡처 실림을 판정(74KB = 캡처 없음)
- 0건을 낼 때는 **양성 대조**를 옆에 붙인다 — 이 세션에서 여섯 번 다 그것이 잡았다
