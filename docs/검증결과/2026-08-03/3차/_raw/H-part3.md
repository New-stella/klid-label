# H 클러스터 part3 — H-3 LabelingPage 중간부(카탈로그 105~142행, 31건) 검증 결과

- 회차: **3차** / 일자: 2026-08-03(실행 시각 기준 2026-08-04 03:00~03:30 KST)
- 대상 파일/범위: `docs/test-cases/H-frontend-e2e.md` **105~142행** — TC-FE-064~087(24건) + TC-FE-197~202(6건) + TC-FE-261(1건) = **31건**
- 검증 방식: **실브라우저(Playwright/Chromium headless, 로컬 `playwright-core` 직접 구동) + 실 API 왕복 + DB 실측**. 빌드/테스트는 실행하지 않음.
  - ⚠ MCP 공용 브라우저는 **다른 part 에이전트와 세션(localStorage·탭)이 공유되어** 검증 도중 토큰이 PORTAL_USER 로 바뀌고 `/forbidden` 으로 튀는 간섭이 재현됨. 그래서 본 검증은 **독립 브라우저 컨텍스트**(스크립트 구동)로 수행했다. 이후 회차에서도 동일 간섭이 예상되므로 참고.
- 스택: `_raw/stack-bringup.md` 3차 재빌드 형상 그대로(backend `localhost:18081/api`, FE `localhost:13000`, mock-server 9400). 배선 OK.
- 사용 데이터: rawSn **101**(srcSn 468~477, APPROVED, WORKER 2001 배정) · rawSn **115**(srcSn 508~513) · rawSn **158**(파생영상, `ORGNL_RAW_SN=101`, srcSn 567) · rawSn **900**(`DE_IDENT_YN='F'`, srcSn 429)

---

## 0. 집계

| 판정 | 건수 | ID |
|---|---:|---|
| **PASS** | 27 | 066, 068, 069, 070, 071, 072, 073, 074, 075, 076, 077, 078, 079, 080, 082, 083, 084, 085, 086, 087, 197, 198, 199, 200, 201, 202, 261 |
| **조건부 PASS**(코드 경로는 성립하나 실동작 진입 조건이 성립하지 않음) | 2 | 064, 067 |
| **부분 PASS / 확인필요** | 2 | 065(트랙 rename API 미도달), 081(clamp 경계값 미검증) |
| FAIL | 0 | — |
| 신규 이슈 | 2 | **H-ISSUE-41**(HIGH), **H-ISSUE-42**(MED) |
| 카탈로그 정정 | **5행** | 067 · 079 · 085 · 087 · 197 |

### ★ 2차 HIGH #9 (낙관적 동시성) — **해소 확인**

2차 `ISSUES.md` 의 **[H-ISSUE-41] TC-FE-198 (HIGH, 라벨 저장 PUT 이 `labelVersion` 을 싣지 않아 lost update 성립)** 및 그 파생 **[H-ISSUE-42] TC-FE-199 (MED)** 는 **이번 회차에 실동작으로 해소 확인**되었다. 상세는 §2.

---

## 1. 케이스별 판정

| ID | 판정 | 실측 근거(요약) |
|----|:--:|---|
| TC-FE-064 잠금 영상 트랙 편집 차단 | **조건부 PASS** | `LabelingPage.tsx:861-864/883-886/908-911` 에 `isLocked` 가드 + 토스트 존재(근거 라인 정확). 단 **서버 잠금이 `isLocked` 로 전달되지 않아**(H-ISSUE-41) 실운영에서 이 분기는 `reportedLock` 경로로만 도달한다. 그 경로는 `reset()` 이 라벨을 비워 트랙 UI 자체가 사라져 UI 재현 불가 |
| TC-FE-065 트랙 rename 후 invalidate | **확인필요** | `866-868`(mergeTracks → `LABEL_KEYS.byVideo` invalidate + 토스트) 코드 확인. 실동작: 대상 프레임 라벨이 전부 **트랙 미부여(T:—)** 라 `ObjectClassTree:120 if (current != null)` 에서 걸려 `onRenameTrack` 자체가 호출되지 않음 → API 왕복 미도달. 트랙 보유 데이터 확보 후 재검증 필요 |
| TC-FE-066 신고 성공 → 잠금+reset+무효화 | **PASS** | rawSn 115/srcSn 508 실신고: `POST /v1/labels/508/deident-report` → **201**, 직후 `GET /v1/frames/508/labels` **412** ×2 → 화면 = "라벨 조회 실패" + BE 안내문("비식별 재처리 대기 중인 영상입니다…") + 성공 토스트. **빈 라벨 화면이 아님** — 카탈로그 ★서술(2026-07-27 보존 정책 반전)과 정확히 일치 |
| TC-FE-067 잠금 배너 노출 | **조건부 PASS** | 신고 POST 만 목 201 로 가로챈 순수 FE 경로에서 `deident-locked-banner` **1건**, `role=status`, 문구 "비식별 재처리 중인 영상입니다…" 정상. **그러나 서버 경로는 미발화** — BE 는 `lockSttsCd="LOCKED"` 를 내리고 FE 는 `'LOCKED_FOR_REDEIDENT'` 와 비교 → **H-ISSUE-41**. 응답만 `LOCKED_FOR_REDEIDENT` 로 치환한 대조군에서는 배너 1건 정상 표시(원인 확정) |
| TC-FE-068 신고 버튼 RAW disabled | **PASS** | 응답 `frameImageType='RAW'` 주입 → 뱃지 RAW, 신고 버튼 `disabled=true`(DEID 일 때는 `false`) |
| TC-FE-069 신고 버튼 포털 미노출 | **PASS** | PORTAL_USER `/portal/label/468` — 신고 버튼 count **0** |
| TC-FE-070 검수제출 버튼 WORKER만 | **PASS** | WORKER `/label/468` 에 `submit-review-button` 1건 / PORTAL 0건 |
| TC-FE-071 상태별 제출 차단 | **PASS** | rawSn 115 를 REVIEW_PENDING 으로 만든 뒤: 버튼 `disabled=true`, `title="이미 검수 제출되어 검수 대기 중입니다."` |
| TC-FE-072 APPROVED 재검수 라벨 | **PASS** | rawSn 101(APPROVED/COMPLETED) → 버튼 문구 **"재검수 제출"**, `title="검수 완료된 영상을 재검수에 다시 제출합니다."` |
| TC-FE-073 제출 취소 REVIEW_PENDING만 | **PASS** | REVIEW_PENDING 시 `cancel-submit-review-button` 1건("제출 취소", title="검수 시작 전이라…"), 취소 후 0건 |
| TC-FE-074 제출 성공 → /task 이동 | **PASS** | UI 제출 후 최종 URL `http://localhost:13000/task`. (성공 토스트는 navigate 직후라 이동 후 DOM 에서 미포착 — 코드 `169-175`) |
| TC-FE-075 X 닫기 dirty 3옵션 모달 | **PASS** | dirty 상태에서 `aria-label="뒤로가기"` 클릭 → "저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이 있습니다. 어떻게 하시겠습니까?" + 버튼 **취소 · 저장 없이 닫기 · 저장 후 닫기** |
| TC-FE-076 beforeunload dirty 경고 | **PASS** | dirty 상태에서 `beforeunload` 디스패치 → `defaultPrevented=true`. 클린 상태에서는 리스너 미등록(`dirtyCount===0` early return) |
| TC-FE-077 메타/이슈 탭 내부만 | **PASS** | 내부: objects/meta/issues 3탭 존재 / 포털: **탭 자체 0건**(`hasTabs=false`) |
| TC-FE-078 이슈 탭 미해소 배지 | **PASS** | rawSn 101 에 `INQUIRY`/`OPEN` 1건 주입 → 배지 텍스트 **"1"**, `aria-label="미해소 문의 1건"` (검증 후 행 삭제 완료) |
| TC-FE-079 메타 탭 패널 구성 | **PASS**(+카탈로그 정정) | 실측 헤딩 6개: 촬영환경 / **개인정보(영상)** / 개인정보(프레임) / 프레임 설명 / 시계열 메타 / 이벤트 어노테이션. 카탈로그의 "5개 패널"은 커밋 `0d290c4e` 의 `VideoPrivacyMetaPanel` 신설 미반영 → **행 정정함**. 시계열 패널에 승인/반려 표면 없음(★서술 일치) |
| TC-FE-080 뷰(zoom/pan) 유지 | **PASS** | Konva Image 노드 실측 — 초기 960×720@(160,0) → 휠 줌 후 **1056×792@(112,-36)** → 동일 영상·동일 해상도 프레임 전환 후에도 **1056×792@(112,-36) 그대로 유지**(리셋 없음) |
| TC-FE-081 붙여넣기 실측 dims clamp | **부분 PASS(확인필요)** | 붙여넣기 자체는 정상("라벨 2건 붙여넣음"), `1029-1047` 에서 `frameNaturalSize` 를 `imageWidth/Height` 로 전달하는 배선 확인. 다만 **경계를 넘는 좌표가 실제로 clamp 되는지**(이미지 밖 라벨 복사 → 붙여넣기)는 이번 회차에서 좌표 검증까지 수행하지 못함 |
| TC-FE-082 복사 빈 선택 no-op | **PASS** | 라벨 0건 프레임(frame#1)에서 Ctrl+C → warning 토스트 **"복사할 라벨이 없습니다."**. (라벨이 있으면 선택 없어도 전량 복사 — `useLabelStore:582-591` 설계상 정상) |
| TC-FE-083 잠금 영상 붙여넣기 차단 | **PASS** | `reportedLock` 상태에서 Ctrl+V → **"비식별 재처리 중인 영상은 붙여넣을 수 없습니다."** 토스트, 붙여넣기 미실행 |
| TC-FE-084 저장 되돌리기 확인 모달 | **PASS** | 인라인 히스토리 카드 "되돌리기" → ConfirmDialog "이 저장으로 되돌리기" + "이 저장의 변경을 현재 작업본에 되돌립니다. 저장해야 확정됩니다." |
| TC-FE-085 되돌릴 항목 없음 경고 | **PASS**(+카탈로그 정정) | 오래된 저장 이벤트 4건 되돌리기 → 전부 warning **"되돌릴 항목이 현재 작업본에 없습니다."**. 카탈로그 기대문구("되돌릴 항목이 없습니다")와 불일치 → **행 정정함** |
| TC-FE-086 캔버스 lazy 마운트 | **PASS** | 라벨링 진입 시 `react-konva.js` · `konva_lib_filters_*.js` 가 **별도 청크로 지연 로드**됨(초기 번들 분리), `Suspense` fallback(`Spinner label="캔버스 로딩"`) 배선 확인 |
| TC-FE-087 히스토리 인라인 패널 | **PASS**(+근거 정정) | 내부 채널에서 `inline-history-panel` 렌더, 패널 내 **"변경 이력" / "버전" 탭** 확인. 포털에서는 히스토리 버튼 자체 0건. 근거 라인 `1570-1582`→`1574-1586` 정정 |
| **TC-FE-197 저장 409 → 충돌 다이얼로그** | **PASS** | §2 참조 — 409 수신 시 **다이얼로그**(제목 "다른 사용자가 먼저 저장했습니다", 확인="최신 라벨 불러오기", 취소="내 작업 유지"), **dirty 유지**(● 편집 중 그대로), 취소 후에도 내 작업 보존 |
| **TC-FE-198 저장 요청에 labelVersion 동봉** | **PASS** ★2차 HIGH 해소 | §2 참조 — PUT body 실측 `{"items":[…],"labelVersion":5}` |
| **TC-FE-199 연속 저장 캐시 버전 우선** | **PASS** ★2차 MED 해소 | §2 참조 — 1회차 `labelVersion:5` → 서버 6 승격 → 2회차가 **6** 을 전송, 자기 409 미발생 |
| TC-FE-200 파생영상 신고버튼 사전 비활성 | **PASS** | 파생 srcSn 567(rawSn 158): 버튼 `disabled=true`, `title`/`aria-label` = "증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다." — **원본 유도·부모 rawSn 노출 없음**(본문 전체에 부모 번호 문자열 부재 확인) |
| TC-FE-201 신고 412 서버 안내문 노출 | **PASS** | 412 + BE message 주입 → 폼 내 `role=alert` 에 **BE 안내문 원문 그대로** 표시(일반 INTERNAL_ERROR 문구로 대체되지 않음), 모달 유지 |
| TC-FE-202 cot 객체형 정규화 | **PASS** | `cot={"1단계":"접근","2단계":"몸싸움","3단계":"분리"}`(객체형)과 `["A1","A2","A3"]`(배열형)을 같은 응답에 섞어 주입 → 두 후보 모두 3단계 입력값으로 **키 순서 유지** 정규화, pageerror 0건 |
| TC-FE-261 busy 중 편집·버튼 차단 | **PASS** | autolabel 응답 8초 지연 주입 후 실행: 오버레이 "AI 탐지 진행 중 / 1초 경과 / 작업 취소" 표시, **저장·검수제출·비식별신고 전부 `disabled=true`**, 프레임 썸네일 option `disabled=true`, `B` 단축키 눌러도 BBOX 도구 `aria-pressed=false`(단축키 무시) |

---

## 2. ★ 2차 HIGH #9(낙관적 동시성) 재현·해소 확인 상세

### 2-1. 수정 확인 (H-ISSUE-41 / 2차)

`frontend/src/features/label/api.ts` `getLabels()` 가 응답의 `labelVersion` 을 반환 객체에 매핑하도록 수정됨(정수·0 이상만 채택, 그 외 `null` → BE 하위호환 skip). 커밋 `dcdbb827 fix(security): 2차 검증 신규 HIGH 11건 수정 (…FE동시성…)`.

### 2-2. 실동작 재현 (브라우저 PUT payload 캡처)

```
# BE 조회 응답
GET /api/v1/frames/468/labels → 200 {"labelVersion":3, "items":[…]}

# FE 저장 1회차 (실브라우저 Ctrl+S 상당 — 헤더 저장 버튼)
PUT /api/v1/frames/468/labels
  body = {"items":[{"id":1288,…}],"labelVersion":5}     ← ★ labelVersion 실려 나감 (2차 결함 해소)
  → 200
```

### 2-3. 연속 저장 시 캐시 버전 우선 (TC-FE-199)

내용을 실제로 바꿔 서버 버전이 오르게 한 뒤 연속 저장:

```
SEQ PUT#1  body={"items":[],"labelVersion":5}   → 200   (서버 버전 5→6 승격)
SEQ PUT#2  body={"items":[],"labelVersion":6}   → 200   ← 렌더 클로저(5)가 아니라 캐시 최신값(6) 전송, 자기 409 없음
화면 문구: "저장됨"
```

### 2-4. lost update 반증 — 경고 없이 덮어쓰는가? → **아니오, 409 로 차단됨** (TC-FE-197)

화면이 버전 N 을 들고 있는 동안 **다른 사용자가 먼저 저장**해 서버 버전을 N+1 로 올린 뒤, 화면에서 저장:

```
외부 선저장 status=200  newVersion=8   (화면 캐시 버전=7)
PUT /api/v1/frames/468/labels body={"items":[…],"labelVersion":7} → 409
화면: "다른 사용자가 먼저 저장했습니다"
      "… 최신 라벨을 불러오면 저장하지 않은 변경은 사라집니다. 작업 내용을 남기려면 '내 작업 유지'를 …"
      버튼: [최신 라벨 불러오기] [내 작업 유지]
편집표식(409 후): ● 편집 중 = true      ← dirty 유지, 내 작업 미폐기
'내 작업 유지' 클릭 후에도: ● 편집 중 = true
```

BE 측 대조(순수 API):

```
PUT labelVersion=1(stale) → 409 {"errorCode":"CONFLICT","message":"다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요."}
PUT labelVersion=3(최신)  → 200, 내용 변경 시에만 버전 승격(3→4). 동일 내용 재저장은 버전 미승격(정상)
```

**결론: 2차 HIGH #9(H-ISSUE-41) 및 파생 H-ISSUE-42 모두 해소.** 2차에서 "도달 불가능한 죽은 경로"였던 TC-FE-197 충돌 다이얼로그·TC-FE-199 캐시 우선 로직이 이제 실사용 경로에서 실제로 동작한다.

---

## 3. 신규 이슈

### [H-ISSUE-41] TC-FE-067 / 064 / 083 — BE 가 내리는 잠금 코드(`LOCKED`)와 FE 판정값(`LOCKED_FOR_REDEIDENT`)이 달라 **서버 잠금이 화면에 전혀 반영되지 않는다**
- **심각도**: **HIGH**
- **기대 동작**: 영상이 재비식별 잠금 상태면 라벨링 화면 진입 시 `lockSttsCd` 로 이를 인지해 ①잠금 배너(role=status) 노출 ②저장·트랙편집·붙여넣기·되돌리기·신고 버튼 비활성 — `LabelResponse.java:29` 주석("`null`/빈 문자열 = 잠금 없음, `\"LOCKED_FOR_REDEIDENT\"` = 비식별 재처리 중")이 이 계약을 명시한다.
- **현재 동작**: BE 는 **`"LOCKED"`** 문자열을 내려보낸다.
  ```java
  // backend/.../label/service/LabelService.java:200
  String lockSttsCd = workLockService.isRawLocked(current.getRawSn()) ? "LOCKED" : null;
  ```
  FE 는 계약대로 `LOCKED_FOR_REDEIDENT` 와만 비교한다.
  ```ts
  // frontend/src/pages/label/LabelingPage.tsx:516
  const isLocked = data?.lockSttsCd === 'LOCKED_FOR_REDEIDENT' || reportedLock;
  ```
  → **서버 잠금만 걸린 상태(비식별 신고 없이 잠금, 또는 다른 세션이 잠근 경우)에서는 `isLocked` 가 영영 false** 다.
- **재현/확인 경로** (rawSn 115, `DE_IDENT_YN='Y'` 유지한 채 작업락만 LOCKED 로):
  ```sql
  UPDATE ls_auth_work_lock SET lck_stts_cd='LOCKED' WHERE work_lock_sn=41;  -- data_raw_sn=115
  ```
  ```
  GET /api/v1/frames/508/labels → 200, lockSttsCd = 'LOCKED'
  화면 /label/508 : 잠금 배너 0건 · 저장 버튼 enabled · 비식별 신고 버튼 enabled  ← 잠금 인지 실패
  PUT /api/v1/frames/508/labels → 409 {"message":"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."}  ← BE 는 정상 차단
  ```
  **대조군**(응답의 `lockSttsCd` 만 `LOCKED_FOR_REDEIDENT` 로 치환): 배너 1건 정상 표시 → 원인이 값 불일치임이 확정됨.
- **영향**: 데이터 유실은 없다(BE 가 409 로 최종 차단). 그러나 ①작업자는 잠긴 영상인 줄 모르고 **편집을 계속하다 저장 시점에야 거부**당해 작업이 낭비되고 ②`isLocked` 에 걸린 FE 가드(TC-FE-064 트랙편집·TC-FE-083 붙여넣기·되돌리기·신고 버튼)가 **서버 경로에서 전부 무력**하며 ③잠금 배너가 사실상 `reportedLock`(신고 직후 클라이언트 표식) 전용이 되는데, 그 경로는 곧바로 412 에러 화면으로 대체되어 배너를 볼 수 있는 창이 거의 없다.
- **참고**: 이 값은 최근 회귀가 아니라 `6c40e02c`(테이블 분리 리팩터) 시점부터 이어진 장기 드리프트다.
- **수정 방향(제안)**: BE `LabelService:200` 의 리터럴을 `"LOCKED_FOR_REDEIDENT"` 로 맞추는 것이 최소 변경(FE 타입 `LockSttsCd` 와 `LabelResponse` javadoc 이 이미 그 값을 정본으로 선언). 반대로 FE 를 넓히면(둘 다 허용) 계약 문서와 어긋난 값이 고착된다. 어느 쪽이든 **BE↔FE 값 동치를 고정하는 회귀 테스트**(응답 DTO 상수 + FE 판정 상수 동일성)를 함께 둘 것. ⚠ 구현은 하지 않았다.

### [H-ISSUE-42] TC-FE-197 — 잠금(409)까지 "다른 사용자가 먼저 저장했습니다" 충돌 다이얼로그로 안내된다
- **심각도**: MEDIUM
- **기대 동작**: 409 라도 사유가 다르면 안내가 달라야 한다. 낙관적 잠금 충돌(다른 사용자 선저장)은 "최신 라벨 불러오기"가 해법이지만, **재비식별 잠금 409** 는 최신 라벨을 불러와도 저장할 수 없다.
- **현재 동작**: `LabelingPage.tsx:586` 이 `status === 409` 만 보고 분기한다. 잠금 사유 409(`"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."`)도 제목 **"다른 사용자가 먼저 저장했습니다"** + 확인 버튼 **"최신 라벨 불러오기"** 의 다이얼로그로 뜬다(설명문에는 BE 메시지가 `extractBeMessage` 로 들어가므로 본문만 사유가 맞고 제목·행동유도는 어긋난다).
- **재현/확인 경로**: H-ISSUE-41 재현으로 영상을 LOCKED 로 만든 뒤 화면에서 저장(현재는 H-ISSUE-41 때문에 FE 가 잠금을 몰라 저장이 시도됨) → 409 → 위 다이얼로그.
- **영향**: 사용자가 "최신 라벨 불러오기"를 눌러 **미저장 작업만 잃고** 여전히 저장하지 못한다. H-ISSUE-41 을 고치면 저장 버튼이 애초에 비활성화돼 노출 빈도는 크게 줄지만, 진입 직후 다른 세션이 잠그는 경우는 남는다.
- **수정 방향(제안)**: 409 를 `errorCode` 또는 BE 메시지가 아닌 **명시적 사유 코드**로 분기(예: `CONFLICT_STALE_VERSION` vs `CONFLICT_LOCKED`)해 잠금 사유는 "불러오기" 유도 없이 안내 전용 토스트/다이얼로그로 처리. ⚠ 구현은 하지 않았다.

---

## 4. 카탈로그 정정 (담당 범위 105~142행 안에서만 수행, 5행)

| 행 | ID | 정정 내용 |
|---|---|---|
| 108 | TC-FE-067 | 3차 실측 주석 추가 — BE 는 `LOCKED` 를 내려 서버 경로에서 배너·`isLocked` 미발화(H-ISSUE-41), `reportedLock` 경로만 성립. 근거에 `LabelingPage.tsx:516` · `LabelService.java:200` 추가 |
| 120 | TC-FE-079 | 기대결과 **5개 패널 → 6개 패널** (`VideoPrivacyMetaPanel` 2번째 신설, 커밋 `0d290c4e`). 케이스명·근거 라인 `1493-1502 → 1494-1505` 정정. 구 기대값은 "폐기" 명시 |
| 126 | TC-FE-085 | 기대문구 `"되돌릴 항목이 없습니다"` → **`"되돌릴 항목이 현재 작업본에 없습니다."`** (실제 문자열), 근거 `650-680 → 651-680` |
| 128 | TC-FE-087 | 근거 라인 `1570-1582 → 1574-1586` |
| 129 | TC-FE-197 | 근거 라인 `585-590,1586-1596 → 586-591,1590-1599` |

> ⚠ 파일 상단 `## 변경 이력` 표 회차 행 추가는 **담당 라인 범위 밖**이라 하지 않았다. H 클러스터 취합 담당이 part1~partN 정정을 합산해 1행으로 기록할 것.

근거 라인 무드리프트 확인(정정 불필요): TC-FE-064(861-864/883-886/908-911) · 065(866-868) · 066(529-537,1143-1163) · 068(1195-1206) · 069(130) · 070(1207-1242) · 071(182-214) · 072(203-204) · 073(201,1210-1222) · 074(169-175) · 075(942-948) · 076(985-994) · 077(475-478,1405-1473) · 078(1461-1469) · 080(307-319) · 081(1029-1047) · 082(1020-1027) · 083(1030-1033) · 084(645-647) · 086(89-91,1352-1372) · 198~202 · 261.

---

## 5. 검증 중 변경한 데이터와 원복 내역 (다른 part 참고 필수)

| 대상 | 변경 | 원복 |
|---|---|---|
| `srcSn 468`(rawSn 101) 라벨 | 저장/삭제/복원 반복으로 **`LBL_SN` 이 729 → 1293 으로 바뀜**(저장이 full-replace 계약이라 PK 재발급) | 내용은 원상(**BBOX person `[[60,60],[210,210]]` 1건**)으로 정규화 완료. `labelVersion` 은 13. `pipeline-drive.md` 가 적어둔 `labelSn=729` 는 더 이상 유효하지 않음 |
| `rawSn 115` 비식별 신고 | TC-FE-066 실검증으로 `DE_IDENT_YN='F'` + 작업락 LOCKED + 신고행 생성 | API `resolve` 는 "비식별 산출물 미확인"으로 409 → **DB 직접 원복**: `de_ident_yn='Y'`, 신고행(37) `RESOLVED`, 작업락(41) `RELEASED` |
| `rawSn 115` 워크플로 상태 | 제출/취소 검증으로 APPROVED → PENDING → ASSIGNED | DB 로 **APPROVED 원복** |
| `ls_data_issue` | 배지 검증용 INQUIRY/OPEN 1행(sn=23) 삽입 | **삭제 완료** (현재 OPEN 이슈 0건) |
| 프로덕션 코드 | 변경 없음 | — |

## 6. 남은 확인 항목

1. **TC-FE-065** — 트랙 ID 가 부여된 라벨이 있는 프레임에서 rename→`mergeTracks` API 왕복 + `byVideo` invalidate 재검증 필요.
2. **TC-FE-081** — 이미지 경계를 벗어나는 좌표로 복사→붙여넣기 시 `imageWidth/Height` clamp 실동작 확인 필요(★3 좌표검증 2축 정책과 무관한 별개 축).
3. **TC-FE-064** — H-ISSUE-41 수정 후 서버 잠금 경로에서 트랙 편집 차단 토스트를 재검증할 것.
