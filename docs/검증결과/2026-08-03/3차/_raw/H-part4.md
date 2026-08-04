# H 클러스터 part4 — H-3. LabelingPage 뒷부분(카탈로그 143~216행, 30건)

> 3차 회차 · 2026-08-03 · 대상 파일 `docs/test-cases/H-frontend-e2e.md`
> 담당 범위: **TC-FE-262~270 · 276~278 · 279~292 · 293~296 (총 30건)**
> (TC-FE-261 은 142행이라 part3 범위 — 미검증)

## 0. 검증 환경 / 방법

| 항목 | 값 |
|---|---|
| 스택 | `_raw/stack-bringup.md` 의 재빌드 스택 그대로 (frontend :13000, backend :18081, mock :9400, PG :5432) |
| 인증 | `POST /api/v1/dev/tokens` 로 REVIEWER(userNo=1001) JWT 발급 → `sessionStorage['klid_jwt']` 주입 |
| 대상 데이터 | `_raw/pipeline-drive.md` 의 **rawSn=101 / srcSn=468**(프레임 0, 라벨 1건, `labelVersion=4`) |
| 실동작 도구 | 프로젝트 `frontend/node_modules/playwright-core@1.59.1` 로 **독립 headless Chromium** 구동 (스크립트 `h4a~h4f.mjs`). ⚠ Playwright **MCP 브라우저는 다른 part 가 동시에 조작 중**이라 탭이 `/portal/uploads` 로 튀는 간섭이 실제 발생 → 격리 실행으로 전환함 |
| 금지 준수 | 빌드·테스트(`./gradlew`, `npm test`) 미실행. 프로덕션 코드 무수정 |

라벨 마스터 실측(`GET /v1/manage/labels`): `person·car·bicycle·motorbike·bus·truck·fire·smoke·water·c2a-race-lbl` **10건 전부 영문/코드명** — ★4(마스터 등록명 그대로) 반증에 최적 조건.

---

## 1. 판정 요약

| 판정 | 건수 |
|---|---|
| PASS | 30 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A · 확인필요 | 0 |

**신규 기능 결함 0건.** 결함은 전부 **카탈로그 정합성(근거 file:line 드리프트 8건)** 과 **테스트 커버리지 갭 1건**, **사(死)코드 잔재 1건** → `H-ISSUE-61~63`.

### ★ 2차 HIGH #9(낙관적 동시성) 소재 확인 — **내 구간 아님, 해소 확인됨**
- 2차 `H-ISSUE-41`(TC-FE-198 저장 PUT 이 `labelVersion` 미전송) / `H-ISSUE-42`(TC-FE-199) 는 **카탈로그 129~131행 = part3 범위**다.
- 다만 내 담당 **TC-FE-270 이 그 전제("교차 수정은 서버 409 + 충돌 다이얼로그가 담당")를 검증**하므로 실동작으로 해소 여부를 확인했다 → **해소됨**:
  - BE 실동작: 같은 `labelVersion=4` 로 2회 PUT → 1회차 200(`labelVersion:5`), **2회차 409 `CONFLICT` "다른 사용자가 먼저 저장했습니다…"**
  - FE 실동작: 409 수신 시 토스트가 아니라 **ConfirmDialog**(`내 작업 유지` / `최신 라벨 불러오기`) 노출
  - FE 코드: `features/label/api.ts:311-316`(`labelVersion` 동봉·null 이면 필드 생략), `useUpdateLabels.ts:63-84`(캐시 버전 우선 + 성공 시 `setQueryData` 동기 갱신), `LabelingPage.tsx:586`(409 → `setSaveConflictMessage`)

---

## 2. 케이스별 결과

### 2-1. busy(장시간 작업) 배타 실행 — TC-FE-262~270

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-262 | PASS | [실동작] busy(저장 in-flight) 중 **되돌리기 버튼 11개 전부 `disabled=true`**, 해제 후 12개 전부 `false` 복귀. **비식별 누락 신고 `disabled=true`**, 툴바 실행취소 `disabled=true`. 정적: `LabelHistoryPanel.tsx:104,207-211`, `editBlocking.test.tsx:273` |
| TC-FE-263 | PASS | [실동작] busy 중 `d`(다음 프레임)·`b`(BBOX) keydown → `Frame 1 / 10` 불변·모달 0·도구 불변. [정적] fail-closed OR 판정 `useLabelingShortcuts.ts:196` `const editBlocked = blocked \|\| isEditBlockedNow();` + `227` `if (editBlocked) continue;`. ⚠ **근거 라인 드리프트**(→ H-ISSUE-61) |
| TC-FE-264 | PASS | [실동작] 저장 응답 4s 지연 주입 → **150ms 시점 오버레이 0개**, 850ms 시점 노출. 내용 = `저장 중` + `0초 경과` + `작업 취소` + "서버 처리가 즉시 중단되지는 않습니다". `role=status` `aria-live=polite`, 경과초 `aria-hidden=true`, 포커스가 취소 버튼으로 이동. **`/YOLO\|SAM2?\|sam/` 정규식 매칭 false = 모델명 미노출** |
| TC-FE-265 | PASS | [실동작] 취소 클릭 → 오버레이 즉시 소멸 + 툴바 재활성. **4s 뒤 지연 응답이 실제 도착(PUT 1건 서버 도달 확인)했음에도 헤더는 `● 편집 중` 유지**(= 결과 미반영 · dirty 보존). 오버레이 표시 중 취소라 안내 토스트 없음 — `busyPolicy.ts:129-133` 정책대로 |
| TC-FE-266 | PASS | [정적+단위] `OverlayLayer.tsx:386-389` — ESC 시 `setPendingConfirm(false)` 를 `handleBusyEscape()` **앞에** 실행(반환값 판정 안 함). 단위 `OverlayLayerSegmentBusy.test.tsx:251 ESC로_취소하면_확정_큐도_비워져_자동_발사되지_않는다`. [실동작] AI 분할 클릭 후 Enter→ESC 시 객체 수 3→3(자동 발사 없음). ⚠ 근거 라인 드리프트(→ H-ISSUE-61) |
| TC-FE-267 | PASS | [정적] `useBusyTask.ts:13` `BUSY_MAX_DURATION_MS = 5*60*1000`, `145-151` 타이머가 `isTokenAlive` 확인 후 `cancelBusy()`. 훅 생명주기와 분리(주석 142-144). **5분 대기는 실동작 미수행**(회차 시간 예산) — 라인 인용 정확 |
| TC-FE-268 | PASS | [실동작] **오버레이가 떠 있는 상태(overlay=1)** 에서 메타 탭 `#frame-description-input` 편집 가능(`disabled:false, readOnly:false`) → 저장 버튼 `disabled:false` → 클릭 시 **`PUT /frames/468/description` → 200** 실제 발생. 시계열/촬영환경/개인정보/이벤트어노테이션 textarea 4종도 전부 편집 가능. 정적으로도 메타 패널 5종에 `useIsEditBlocked` 참조 0건 |
| TC-FE-269 | PASS | [실동작] ① `AI 추적` 버튼 포커스 → Space → **`aria-pressed` false→true(버튼 활성화)**, 포커스는 그 버튼에 유지, 캔버스 cursor 변화 없음(팬 미발동) ② 포커스 blur 후 Space hold → 캔버스 컨테이너 `computed cursor: grab`, release → `auto` (**팬 정상 복귀**). ⚠ 근거 라인 드리프트(→ H-ISSUE-61) |
| TC-FE-270 | PASS | [실동작] 위 §1 ★ 참조. BE 409 실측 + FE 충돌 다이얼로그 실측. wiki 근거 `docs/v2-wiki/10-labeling.md:168` 존재 확인 |

### 2-2. 메타 탭 시계열 메타 검토 UI 제거 — TC-FE-276~278

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-276 | PASS | [정적] `frontend/src` 전역 grep 결과 `ts-review-*` 문자열이 **테스트 파일의 부재 단언 3줄에만** 존재하고 프로덕션 코드 0건. `approveMetaReview`/`rejectMetaReview`/`useMetaReview` **전부 0건**. `TimeseriesSidePanel.tsx:55-58` 주석이 정책 명시. 파일 총 177행 = 인용 `124-177` 이 렌더 끝까지 정확 |
| TC-FE-277 | PASS | [정적] 위와 동일. `승인`/`반려`/`검토 상태` 문자열이 렌더에 0건(159행 placeholder 의 "검토 후 수정할 수 있습니다" 안내문만 존재 — 배지 아님) |
| TC-FE-278 | PASS | [정적] `TimeseriesSidePanel.tsx:108-113` `dirtyItems` = `draft !== original && draft.trim().length > 0` 인 슬롯만, `92-102` 슬롯 0건이면 `MANUAL_TIMESERIES_META_KEY` 단일 슬롯, `121` `updateMutation.mutate({items: dirtyItems})` 1회. 라인 인용 `79-122` 정확 |

### 2-3. 도구 클릭 → 라벨 선택 모달 — TC-FE-279~292

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-279 | PASS | [실동작] `바운딩 박스` 클릭 → `role=dialog aria-modal=true` 오픈, 안내 문구 **"바운딩 박스 도구로 그릴 라벨을 선택하세요…"**(도구명 포함). **반증**: 모달 상태에서 캔버스 드래그 → 객체 수 2→2(드로잉 미시작). ⚠ 근거 라인 -1 드리프트 |
| TC-FE-280 | PASS | [실동작] ESC → 모달 0 + `activeTool` 이 **`선택`(직전 도구)으로 복귀** + 복귀 전이로 모달이 다시 뜨지 않음(`suppressRef`). 정적 `useToolLabelPicker.ts:111-119,69-73` 라인 정확 |
| TC-FE-281 | PASS | [실동작] 모달에서 `car` 클릭 → 모달 0 + `바운딩 박스` `aria-pressed=true`. 정적 `102-109` 정확 |
| TC-FE-282 | PASS | [실동작] 확정 후 캔버스 드래그 2회 → **모달 재노출 0**, 객체 `car #1`·`car #2` 2건 생성(마지막 선택 라벨 유지). 정적 `11-12,64-84` 정확 |
| TC-FE-283 | PASS | [실동작] 이미 활성인 `바운딩 박스` 재클릭 → 모달 1개 오픈. 정적 `86-100`(전이 없을 때 `requestTool` 이 직접 오픈, 라벨 불필요 도구는 no-op) 정확. ⚠ LabelingPage 라인 -1 드리프트 |
| TC-FE-284 | PASS | [실동작] `AI 추적` 클릭 → 모달 0, `선택` 클릭 → 모달 0. 정적 `LABEL_REQUIRED_TOOLS`(27-32) = BBOX·POLYGON·SAM_SEGMENT·KEYPOINT 4종 |
| TC-FE-285 | PASS | [실동작] 단축키 `p` → **동일 모달**(문구 "폴리곤 도구로 그릴 라벨을…"). 판정이 `activeTool` 전이 감시 1곳이라 진입점 무관 확인 |
| TC-FE-286 | PASS | [실동작] 네트워크 캡처 결과 **`/preset` 패턴 요청 0건**, `GET /api/v1/manage/labels` 1건만. 목록 = 활성(`useYn='Y'`) 10건이 `sortNo`(1,2,…,10,77) 순 정렬 |
| TC-FE-287 | PASS | [실동작] `CA` 입력(대문자) → `car` 1건만(대소문자 무시 부분일치). `zzz` → **"검색 결과가 없습니다" + `aria-live=polite`**, 목록 0건. ESC 후 재오픈 → **검색어 `''` 초기화 + 10건 복귀** |
| TC-FE-288 | PASS | [실동작 **반증**] 모달 닫힌 상태에서 전역 `1` keydown 후 도형을 그림 → 새 객체가 **`car #2`**(직전 선택 라벨 유지). 전역 1 이 발화했다면 마스터 1번=`person` 이 됐어야 함 → **미발화 확정**. 정적 `SHORTCUT_KEYMAP` 숫자 바인딩 grep 0건. ⚠ keymap 라인 -1 드리프트 |
| TC-FE-289 | PASS | [실동작] 검색 input 포커스 상태에서 `1` → `keyword='1'` 로 입력만 되고 선택(`aria-pressed=true`) 0건. 정적 `LabelPickerModal.tsx:74-88` INPUT/TEXTAREA/contentEditable 가드 + `if (!picked) return`(범위 밖 무시) |
| TC-FE-290 | PASS | [실동작 **반증**] 마스터 응답을 가로채 색상에 `red; background-image:url(javascript:alert(1))` / `javascript:alert(1)` 주입 → 칩 inline style 이 **`background-color: rgb(148, 163, 184)`(=`FALLBACK_LABEL_COLOR #94A3B8`)** 로 정규화, `document.body.innerHTML` 내 `javascript:alert` **0건**. `safeHexColor`(labelColor.ts) 가 `^#[0-9A-Fa-f]{6}$` 만 통과 |
| TC-FE-291 | PASS | [실동작] `[data-testid="label-sidebar"]` 0건, `aside[aria-label="라벨 목록"]` 0건, `role=toolbar` 의 다음 형제가 곧바로 캔버스 컨테이너. ⚠ 라인 -1 드리프트 |
| TC-FE-292 | PASS | [실동작] `keypoint-guide-slot` 존재 · **부모가 `labeling-right-panel` 직계** · `role=tablist` **앞** · `role=tabpanel` **바깥**(`closest('[role=tabpanel]')` null) → 어느 탭에서도 상시 노출. ⚠ 라인 -1 드리프트 |

### 2-4. 라벨명 = 마스터 등록명 그대로(★4) — TC-FE-293~296

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-293 | PASS | [실동작] 마스터가 `person`/`car`/`bus`/`bicycle` 등 **영문**인 상태에서 라벨 선택 모달·우측 객체 목록 모두 **동일 영문 문자열**. `사람`·`자동차` 등 한글 치환 **0건**. 정적 `labelDisplayName.ts:26-29` = trim + 빈값 `-` 뿐(사전 조회 코드 자체 부재) |
| TC-FE-294 | PASS | [정적] 인용 6곳 라인 **전건 정확**(ObjectClassTree:153 / ObjectAttributePanel:258,268 / AiToolModal:269 / LabelChangeDetail:127 / PortalUploadLabelingPage:328 / LabelPickerModal:58). ⚠ 7번째 호출부 `LabelPanel.tsx:44` 발견 — **사(死)코드**(→ H-ISSUE-63), 카탈로그에 주석 추가함 |
| TC-FE-295 | PASS | [정적] `putLabels`(api.ts:301-316) body 는 `label` 원문 그대로 전송. BE 실측에서도 `label` 누락 시 400(`items[0].label: must not be blank`) → 표시명 경유 필드가 아님이 확인됨. 회귀 가드 `labelDisplayNameNoPayloadLeak.test.tsx:51,55` 라인 정확 |
| TC-FE-296 | PASS | [정적] `labelDisplayName.ts:17-29` — `(rawName ?? '').trim()`, 빈값이면 `'-'`. 사전/맵 조회가 없어 `__proto__`·`constructor` 도 원문 반환(프로토타입 오염 경로 부재). 테스트 41,45,51 라인 정확 |

---

## 3. PM 지정 ★ 반증 항목 처리

| 지시 | 결과 |
|---|---|
| **mock 응답(SAM2/YOLO)의 FE 자동적용 차단 + 안내** | **내 담당 30건에 해당 케이스 없음**(카탈로그상 mock 자동적용 차단은 H-3 앞부분/AiToolModal 절 소관 = part3). 부수 확인만: 이 구간의 AI 경로는 `busy` 배타 실행으로만 관여하며, 취소 후 도착한 AI/저장 결과가 세대 토큰(`isTokenAlive`)으로 폐기되는 것은 TC-FE-265 로 **실동작 확인**함. **mock 판별 기반 자동적용 차단 로직 자체는 이 구간 밖이라 미검증** — part3 결과와 대조 필요 |
| **좌표 검증 2축(★3) 사용자 저장=400 거부의 FE 반영** | [실동작] `PUT /v1/frames/468/labels` 에 `points=[[-50,-50],[99999,99999]]` → **HTTP 400 `INVALID_INPUT` "좌표는 0 이상이어야 합니다 (x=-50.0, y=-50.0)"** — 클램프 아님을 실측 확인. FE 는 `resolveApiMessage` 정책상 400 을 사용자 문구 그대로 노출(TC-FE-195, part1/3 소관). **이 구간에는 좌표 검증 케이스가 없어 판정 대상 아님**(참고 기록) |

---

## 4. 이슈

### [H-ISSUE-61] TC-FE-263 / 266 / 269 / 279 / 283 / 288 / 291 / 292 — 근거 `file:line` 드리프트 8건 (카탈로그 정합성)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그의 `근거` 컬럼은 다음 회차 검증자가 **그 줄을 열어 기대결과와 대조**하는 유일한 좌표다. 어긋나면 검증자가 무관한 함수를 읽고 "코드가 없다/다르다"로 오판하거나, 대조 자체를 포기한다(4회차 변경이력이 지적한 바로 그 실패 모드).
- **현재 동작(이슈 내용)**:

  | TC | 카탈로그 인용 | 실제 위치 | 성격 |
  |----|---|---|---|
  | TC-FE-263 | `useLabelingShortcuts.ts:216,223,247` | `196`(fail-closed OR 판정) · `203`(ESC) · `227`(키맵 차단). 247 은 파일 말미 재-export | **-20행급, 인용 3개 전부 무관한 줄** |
  | TC-FE-266 | `OverlayLayer.tsx:357-381` | 핵심 코드(`setPendingConfirm(false)`)는 **387행** — 인용 범위 **밖** | 범위 부족 |
  | TC-FE-269 | `CanvasShell.tsx:71-104,185-193` | `66-99`(`isKeyboardActivatableTarget`) · `180-187`(Space keydown 가드) | -5행 |
  | TC-FE-279 | `LabelingPage.tsx:1328-1334` | `1329-1335` | -1행 |
  | TC-FE-283 | `LabelingPage.tsx:1343` | `1344` | -1행 |
  | TC-FE-288 | `labelingKeymap.ts:33-36` | `34-37` | -1행 |
  | TC-FE-291 | `LabelingPage.tsx:1336-1344` | `1337-1345` | -1행 |
  | TC-FE-292 | `LabelingPage.tsx:1400-1404` | `1401-1405` | -1행 |

- **재현/확인 경로**: `sed -n '196p;203p;227p' frontend/src/features/label/hooks/useLabelingShortcuts.ts` / `git merge-base --is-ancestor 890894c5 9f99db50` → **not ancestor**
- **영향**: 기능 영향 없음(카탈로그 품질). **원인이 둘로 갈린다**:
  ① `LabelingPage.tsx` 계열 -1행 = 카탈로그 4회차(`9f99db50`) **이후** `0d290c4e`/`890894c5`(영상 개인정보 메타 화면)가 파일을 건드림 → **불가피한 사후 드리프트**
  ② `useLabelingShortcuts.ts`(-20행)·`CanvasShell.tsx`(-5행)·`labelingKeymap.ts`(-1행) = 인용 대상 커밋(`dcdbb827`·`d8a7a2cc`)이 4회차 **이전**인데도 어긋남 → **4회차가 "H-3 원본 절 TC-FE-033~087,197,199~201" 만 재확인하고 라운드2·3 신설분(261~296)은 대조 대상에서 누락**시킨 것. 4회차 변경이력이 스스로 경고한 "파일이 안 바뀐 것 같아 대조를 생략" 패턴의 변형이다.
- **수정 방향(제안)**: **본 회차에서 8건 전부 Edit 로 정정 완료**(담당 라인범위 143~216 내부). 후속으로 회차 운영 규칙에 **"신설 케이스도 다음 회차 근거 재확인 대상"** 을 명시할 것.

### [H-ISSUE-62] TC-FE-262 — 근거가 '되돌리기·버전 롤백' 축을 커버하지 않고, 롤백 busy 차단에 자동 테스트가 0건이다
- **심각도**: LOW
- **기대 동작(기대효과)**: TC-FE-262 는 **되돌리기 · 버전 롤백 · 비식별 신고 3축**의 busy 차단을 보장한다. 롤백은 작업본(`labels`/dirty)을 바꾸는 편집이라, 저장 in-flight 중 실행되면 저장 성공의 `clearDirty()` 가 되돌린 분의 미저장 표식까지 지워 **무음 소실**이 난다(`LabelHistoryPanel.tsx:101-103` 주석이 직접 명시).
- **현재 동작(이슈 내용)**: 구 인용 `editBlocking.test.tsx:259,280` 중
  - `280` → `busy_중에는_비식별_누락_신고를_시작할_수_없다`(273행 시작) ✅ 신고 축은 커버
  - `259` → `busy_중_캔버스_위에_진행_오버레이가_뜨고_취소로_즉시_편집에_복귀한다`(254행 시작) — **되돌리기·롤백 축이 아니다**
  - `editBlocking.test.tsx` 13개 `it()` 어디에도 롤백/되돌리기 케이스 없음. `LabelHistoryPanel.revert.test.tsx` 는 2개 `it()` 모두 busy 를 세우지 않음(`grep -n 'busy' → 0건`).
  → 즉 **"버전 롤백은 busy 중 차단된다"에 자동 회귀 가드가 없다.** 구현(`LabelHistoryPanel.tsx:104,207-211 disabled={editBlocked}`)은 정상이나, 누가 `disabled` 를 떼도 테스트가 잡지 못한다.
- **재현/확인 경로**: 본 회차 실동작으로 정상 확인함 — busy 중 되돌리기 버튼 11개 `disabled=true`, 해제 후 12개 `false`(스크립트 `h4f.mjs`).
- **영향**: 회귀 방어 공백(기능 결함 아님).
- **수정 방향(제안)**: ① 카탈로그 근거를 실제 축으로 교정(**본 회차 정정 완료** — `LabelHistoryPanel.tsx:104,207-211` + 테스트 공백 경고 명시) ② `LabelHistoryPanel.revert.test.tsx` 에 `beginBusy` 후 되돌리기 버튼 `disabled` 단언 1건 추가.

### [H-ISSUE-63] TC-FE-294 인접 — `LabelPanel.tsx` 가 어디서도 import 되지 않는 사(死)코드로 남았다 (`LabelSidebar` 폐지 잔재)
- **심각도**: LOW
- **기대 동작(기대효과)**: 결정 3(2026-08-03, `d8a7a2cc`)은 좌측 상시 라벨 패널을 **컴포넌트·테스트째로 삭제**했다. 라벨 선택 표면은 모달 하나여야 하고, 표시명 경유 지점도 "6곳"으로 카탈로그가 못 박혀 있다.
- **현재 동작(이슈 내용)**: `features/label/components/LabelPanel.tsx:44` 가 `resolveLabelDisplayName(cls)` 를 호출하는 **7번째 표시 지점**으로 살아 있다. 그러나 전역 grep 결과 자기 테스트(`__tests__/LabelPanel.test.tsx`) 외 **import 0건** — 렌더 경로가 없다.
  ```
  $ grep -rn 'LabelPanel' --include='*.tsx' src/ | grep -v __tests__ | grep -v 'components/LabelPanel.tsx'
  (출력 없음)
  ```
- **재현/확인 경로**: 위 grep. 라벨링 화면 실동작 DOM 에도 해당 패널 없음(TC-FE-291 실측).
- **영향**: 기능 영향 0. 다만 ①번들에 잔존(트리셰이킹 여부 미확인) ②다음 회차 검증자가 "표시 지점 7곳인데 카탈로그는 6곳"으로 재발견해 같은 논의를 반복 ③테스트가 살아 있어 "쓰이는 컴포넌트"로 오인.
- **수정 방향(제안)**: `LabelPanel.tsx` + `__tests__/LabelPanel.test.tsx` 삭제(구 `LabelSidebar` 삭제와 동일 처리). 삭제 전까지는 카탈로그 주석(**본 회차 추가 완료**)으로 오인을 막는다.

---

## 5. 카탈로그 정정 내역 (담당 라인범위 143~216 내부에서만 수행)

| # | 대상 | 변경 |
|:--:|---|---|
| 1 | TC-FE-262 근거 | `editBlocking.test.tsx:259,280` → `editBlocking.test.tsx:273(신고),222·254(ESC·취소)` + `LabelHistoryPanel.tsx:104,207-211(버전 롤백 — ⚠ 롤백 축은 자동 테스트 0건)` |
| 2 | TC-FE-263 근거 | `useLabelingShortcuts.ts:216,223,247` → `:196(fail-closed OR 판정),203(ESC),227(키맵 차단)` |
| 3 | TC-FE-266 근거 | `OverlayLayer.tsx:357-381` → `:354-392(ESC 분기 373-391 · setPendingConfirm(false) 387)` + 테스트 라인 `:251` 명시 |
| 4 | TC-FE-269 근거 | `CanvasShell.tsx:71-104,185-193` → `:66-99(활성화 대상 판정),180-187(Space keydown 가드)` + 테스트 경로 풀패스·라인 `:153,169` 명시 |
| 5 | TC-FE-279 근거 | `LabelingPage.tsx:1328-1334` → `:1329-1335` |
| 6 | TC-FE-283 근거 | `LabelingPage.tsx:1343` → `:1344` |
| 7 | TC-FE-288 근거 | `labelingKeymap.ts:33-36` → `:34-37` |
| 8 | TC-FE-291 근거 | `LabelingPage.tsx:1336-1344` → `:1337-1345` |
| 9 | TC-FE-292 근거 | `LabelingPage.tsx:1400-1404` → `:1401-1405` |
| 10 | TC-FE-294 기대결과 | 7번째 호출부 `LabelPanel.tsx:44` 가 **사코드**라 집계 제외임을 명시(⚠ 주석 추가) |

**폐기 처리한 케이스 없음**(30건 전부 현행 구현과 정합).
**변경 이력 표(파일 8~16행)는 담당 범위 밖이라 손대지 않았다** — 회차 행 추가는 H 클러스터 통합 담당이 수행해야 한다.

---

## 6. 미검증·한계

| 항목 | 사유 |
|---|---|
| TC-FE-267 의 5분 fail-safe 실경과 | 회차 시간 예산. 코드(`useBusyTask.ts:13,145-151`)·타이머 분리 구조는 정적 확인 |
| TC-FE-263 의 "커밋과 리렌더 **사이**" 실제 경합 창 | 브라우저에서 결정론적으로 재현 불가. `isEditBlockedNow()` OR 판정(196행)과 `shortcutsFailClosed.test.tsx` 로 대체 |
| TC-FE-266 의 지연 창 Enter 큐잉 정확 재현 | SAM2 응답 지연 주입 시 분할 결과 자체가 비어 큐잉 여부를 외부에서 분리 관측하기 어려움. 코드+전용 단위테스트로 판정 |
| mock 응답 자동적용 차단(★ PM 지시) | 해당 케이스가 담당 30건에 없음 — part3 소관 |
