# H 클러스터 part7 — H-12(공통 컴포넌트/에러/상태) + H-13(접근성 a11y) 검증 결과

> 대상: `docs/test-cases/H-frontend-e2e.md` `## H-12`(16건: TC-FE-178~188, TC-FE-214~218) + `## H-13`(15건: TC-A11Y-001~015) = **31건**
> 회차: 2026-08-02 2차 · 검증 방식: 실동작(Playwright, frontend `localhost:13000`, REVIEWER/WORKER dev-login) + 정적 대조 + 테스트 커버 대조
> 코드 기준: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801`
> baseline: frontend vitest **1,951/1,951 PASS**(`docs/검증결과/2026-08-01/1차/_raw/test-baseline.md`)
> ⚠ frontend(:13000)는 다른 검증 에이전트와 **동일 브라우저 세션을 공유**하고 있어(동시에 여러 탭이 `/label/*`·`/augment/result/*` 등으로 이동) 일부 케이스는 실동작 스냅샷 대신 정적 코드 대조 + 기존 vitest 단정(assert) 인용으로 판정 근거를 보강했다. 표에 `[실동작]`/`[정적]`을 구분 표기.

## 1. H-12. 공통 컴포넌트/에러/상태 (16건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-FE-178 | PASS | [정적] | `ErrorBoundary.tsx:16-19,21-25,27-38` — `getDerivedStateFromError` 는 사용자 노출 message만 state에 담고, `componentDidCatch`는 `console.error`로만 스택 출력(사용자 미노출). fallback 없으면 `role="alert"` + "오류가 발생했습니다" 렌더. `App.tsx:4,26-36`에 앱 루트로 배선 확인(dead-code 아님). 전용 단위테스트 파일은 없으나(`ErrorBoundary.test.tsx` 부재) 실사용 경로·코드 확인으로 충분 |
| TC-FE-179 | PASS | [정적] | `ErrorBoundary.tsx:29-30` — `this.props.fallback ?? (기본 UI)` — custom fallback prop 있으면 그대로 렌더 |
| TC-FE-180 | PASS | [실동작] | `http://localhost:13000/xyz-nonexistent-route` 진입 → 실제 렌더 `role=alert`, `heading "404"`, `"페이지를 찾을 수 없습니다"`, `link "메인으로 이동" → /`. `AppErrorPage.tsx:11-15` TITLES 맵(403/404/500) 정합 |
| TC-FE-181 | PASS | [정적] | `EmptyState.tsx:19-49` — `role="status"`, 아이콘 `aria-hidden="true"`, message 기본값 "데이터가 없습니다". `DataTable.tsx:192-201`·`NoticeListPage.tsx:189` 등 실사용처에서 rows=0 분기에 렌더되는 것 확인 |
| TC-FE-182 | PASS | [정적]+[테스트] | `AuthImage.tsx:60-121` — `srcSn` 지정 시 `/frames/{srcSn}/image` blob 요청(60-92), 로딩/에러 폴백 모두 `<div>` + `toFallbackProps(rest)`로 `data-*`/`aria-*` 유지(94-119, `IMG_ONLY_PROPS`만 제거). 테스트: `AuthImage.test.tsx:49`(srcSn blob 요청), `:299`(로딩 폴백 data-testid 유지) |
| TC-FE-183 | PASS | [실동작] | `/manage/users` 진입 스냅샷 실측 — `navigation "페이지네이션"` `aria-label="페이지네이션"`, 첫/이전/1/다음/마지막 버튼(전체 5건·1페이지라 이동버튼 전부 disabled 확인). `Pagination.tsx:46-108` 코드와 일치 |
| TC-FE-184 | PASS | [실동작]+[정적] | `DataTable.tsx:128-172` `col.sortable`인 컬럼만 `<button>` 헤더 + `aria-sort` 부여, 클릭 시 `handleSort`(68-76)가 asc↔desc 토글. 실사용처 `ReviewListPage.tsx:271`(`sortable: true`, BE allowlist `submittedAt→UPD_DT`)에서 실제 배선 확인(283 라인 주석: sortable 미부착 컬럼은 의도적 제외) |
| TC-FE-185 | PASS | [정적]+[테스트] | `Toast.tsx:45-58` — `role="alert" aria-live="polite"`, variant별 아이콘(success/error/warning/info) + `sr-only` variant 라벨 텍스트 병기(색상 단독 구분 금지 준수). `Toast.test.tsx` 존재 |
| TC-FE-186 | PASS | [정적] | `NoticeListPage.tsx`(목록, 검색/페이지네이션/REVIEWER 작성 버튼) + `NoticeDetailPage.tsx`(상세, 첨부파일/발행토글/삭제) 양쪽 구현 확인. `NoticeListPage.test.tsx`·`NoticeDetailPage.test.tsx` 존재 |
| TC-FE-187 | PASS | [정적] | `RoleClaimPage.tsx:28-115` — WORKER만 자가부여 가능(`ClaimableRole='WORKER'`), 패스워드 `type=password` + `autoComplete=new-password`(24-26 주석·92-95), 401/403/409/429 별 사용자 메시지 분기(126-143). `RoleClaimPage.test.tsx` 존재 |
| TC-FE-188 | PASS | [정적] | `WorkerStatPage.tsx`·`OverallStatPage.tsx`가 `SimpleBarChart`/`SimplePieChart`/`DailyCompletionChart`를 사용하며 그 내부는 실제 `recharts`(Bar/BarChart/Pie/PieChart, grep 확인). `router/index.tsx:86-92` 주석 "Phase 12 — 통계 + 프리셋 lazy 로드 (recharts 별도 청크)"대로 `lazyWithRetry`로 분리 로드. `OverallStatPage.test.tsx`·`WorkerStatPage.test.tsx` 존재 |
| TC-FE-214 | PASS | [정적]+[테스트] | `imagePath.ts:11-21` — `ALLOWED_IMAGE_PATH = /^\/v1\/frames\/[0-9]{1,19}\/(?:deid-)?image$/` 화이트리스트만 통과, 외부호스트·`..`·쿼리스트링 전부 null(요청 자체 미발생, `AuthImage.tsx:74-78` fail-closed). 테스트: `AuthImage.test.tsx:84`(허용 안된 path 미요청), `:102`(상위경로 순회 차단) |
| TC-FE-215 | PASS | [정적]+[테스트] | `authImageStore.ts:97-105` `acquireAuthImage` — 기존 엔트리 있으면 `refCount+=1`하고 동일 promise 재사용(중복 XHR 없음), 상한/대기열 로직 없음(주석 11-17 정책 명시). 테스트: `AuthImage.test.tsx:136`(1회만 요청), `:187`(대기열 없이 즉시 발사), `:226`(언마운트돼도 새 페이지 지연없이 시작) |
| TC-FE-216 | PASS | [정적]+[테스트] | `authImageStore.ts:108-118` `releaseAuthImage` — `refCount`가 0이 되면 즉시 `URL.revokeObjectURL` + `entries.delete`(영속 캐시 없음, 신고 게이트 CWE-359 우회 방지 주석 19-24). 테스트: `AuthImage.test.tsx:119`(unmount revoke), `:158`(마지막 소비자 시점 revoke), `:348`(path 변경시 이전 objectURL revoke) |
| TC-FE-217 | PASS | [정적]+[테스트] | `authImageStore.ts:97-105` — 실패한 엔트리(`failed=true`)에 새 소비자가 붙는 **acquire 시점에만** `startFetch` 재호출(자동 재시도 루프 없음). 테스트: `AuthImage.test.tsx:279`(재요청) |
| TC-FE-218 | PASS | [정적]+[테스트] | `AuthImage.tsx:27-47` `IMG_ONLY_PROPS`(width/height/loading/decoding/srcSet/sizes/crossOrigin/referrerPolicy/useMap/fetchPriority) 제거 후 `toFallbackProps`로 div에 전개. 테스트: `AuthImage.test.tsx:322`(무효 DOM 속성 경고 없음) |

## 2. H-13. 접근성 (a11y) (15건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-A11Y-001 | PASS | [정적] | `LabelingPage.tsx:1381`(`role="tablist"`), `:1386-1389`(objects tab `role=tab` `aria-selected` `aria-controls`), `:1403-1406`(meta tab), `:1421-1424`(issues tab), `:1452,1462`(`role="tabpanel"`). ⚠ 카탈로그 근거 라인(1205-1310)은 **드리프트** — 실제 위치는 1381~1470대(하단 근거 드리프트 참조) |
| TC-A11Y-002 | PASS | [정적] | `AiToolModal.tsx:196-217`(형태 라디오 `htmlFor=id` 매칭: `ai-tool-shape-bbox`/`ai-tool-shape-polygon`), `:246-260`(라벨 후보 checkbox `htmlFor={inputId}`). `ObjectAttributePanel.tsx:147-157`(`ai-segment-immediate` label↔input htmlFor/id 매칭 확인) |
| TC-A11Y-003 | PASS | [정적]+[테스트] | `Modal.tsx:47-57`(ESC→`onClose`), `:60-93`(포커스 트랩: 열릴 때 `lastActiveRef` 저장→첫 focusable에 focus, Tab 순환 트랩, 닫힐 때 `lastActiveRef.current?.focus()` 복귀), `role="dialog" aria-modal="true"`(110-111). 테스트: `Modal.test.tsx` — `Modal_ESC_키로_닫기`, `Modal_포커스_트랩_Tab_순환` |
| TC-A11Y-004 | PASS | [정적]+[테스트] | `MarkingPage.tsx:129-146` — MANUAL 모드에서 Space(마킹 추가)/Delete·Backspace(삭제)/Enter(제출) 키보드만으로 완결, input/textarea/select 포커스 시 무시(133). `MarkingPage.test.tsx:272`(Enter 재호출 방지 등 커버) |
| TC-A11Y-005 | PASS | [정적]+[테스트] | `useLabelingShortcuts.ts`(신규 위치, `SHORTCUT_KEYMAP` 단일소스) — W/A/S/D 프레임 이동, T 표시토글, F/Q 폴리곤 점추가/자동완료, `?`(Shift+/) 치트시트 토글, Ctrl+C/V 복붙. `LabelingPage.tsx:998`에서 훅 호출. IME 견고성(물리키 `e.code` 우선) + input/textarea 포커스 시 무시 주석 확인. 테스트: `useLabelingShortcuts.test.tsx`·`useLabelingShortcuts.cheatsheet.test.tsx`·`labelingKeymap.test.ts` 다수. ⚠ 카탈로그 근거(`LabelingPage.tsx:846-901`)는 **드리프트** — 그 라인은 현재 트랙 rename/삭제 핸들러로 무관한 코드다(하단 근거 드리프트 참조) |
| TC-A11Y-006 | PASS | [정적] | `LabelingPage.tsx:1234-1243`(실제 위치, 카탈로그 `1079-1090`은 드리프트) — 잠금 배너 `role="status" aria-live="polite"`, "비식별 재처리 중인 영상입니다..." 문구 |
| TC-A11Y-007 | PASS | [정적] | `PortalUploadPage.tsx:200-208` — `role="progressbar" aria-valuenow={videoPercent} aria-valuemin={0} aria-valuemax={100}` |
| TC-A11Y-008 | PASS | [정적] | `LabelingPage.tsx:1431-1435`(실제 위치, 카탈로그 `1264`는 근사) — `aria-label={`미해소 문의 ${unresolvedInquiries}건`}`, `unresolvedInquiries > 0`일 때만 렌더 |
| TC-A11Y-009 | PASS | [정적]+[테스트] | `PortalUploadPage.tsx:312` — `aria-label={`${upload.orgnlFileNm} 삭제`}`. 테스트: `PortalUploadPage.test.tsx:126-175`(삭제/취소/PROCESSING비활성/실패alert 커버) |
| TC-A11Y-010 | PASS | [정적] | `BatchStageIndicator.tsx:34,41,48` — DONE/PROGRESS/FAIL 아이콘 전부 `aria-hidden`(중복 낭독 방지). 텍스트 라벨(`STAGE_LABEL`)이 별도로 병기돼 색상만으로 상태 구분하지 않음 |
| TC-A11Y-011 | PASS | [정적] | `lib/focusRing.ts:11-12` `KRDS_FOCUS` — `focus-visible:ring-[3px] ring-offset-2 ring-primary-500`. `Pagination`/`Modal`/`DataTable`/`KpiCard`/`AppErrorPage`/`TaskBoardTable` 등 grep상 폭넓게 재사용 확인(단일 소스, 인라인 개별 스타일 없음) |
| TC-A11Y-012 | PASS | [정적] | `ReviewPage.tsx:294-297` — `<main data-testid="review-canvas-readonly" aria-label="검수 캔버스 (읽기 전용)">` |
| TC-A11Y-013 | PASS | [실동작]+[테스트] | REVIEWER로 `/task` 진입 실측 스냅샷 — KPI 필터 카드가 `button [pressed]`(선택된 "전체 작업")와 `button`(미선택, aria-pressed 없음 상태 아닌 false로 렌더)로 실제 구분됨. `KpiCard.tsx:48-62` `onClick && selected!==undefined ? selected : undefined` 로직과 `border-2 border-primary-600` 테두리 강조(색상 단독 아님) 확인. WORKER 화면(`/task`)의 KPI 카드는 `onClick` 미부여라 `generic`(button 아님)으로 렌더 — "onClick 없으면 aria-pressed 자체가 안 붙는다" 기대결과와 일치. 테스트: `TaskListPage.board.test.tsx:320,327,333`(aria-pressed 단정) |
| TC-A11Y-014 | PASS | [실동작]+[테스트] | REVIEWER `/task` 실측 — `document.querySelectorAll('th')` DOM 조회로 "영상 ID"·"촬영일시" `<th aria-sort="none">` + 내부 `<button>` 확인(초기 상태). 정렬 클릭 후 URL 파라미터가 `sort=rawSn,desc&sort=regDt,desc`로 실제 반영됨(`TaskBoardTable.tsx:63-84` `SortableHeader`). 브라우저가 다른 검증 에이전트와 세션 공유 중이라 클릭 직후 DOM 재조회 타이밍이 불안정해 `aria-sort` 값 전환 자체는 실측 대신 테스트로 보강: `TaskListPage.board.test.tsx:669`(descending), `:693,729`(ascending), `:999`(초기화 시 none 복귀) |
| TC-A11Y-015 | PASS | [정적]+[정책확인] | `BusyOverlay.tsx:109-131` — `role="status" aria-live="polite" aria-busy="true"`(109-114), 취소버튼 `ref=cancelRef`로 표시 시 focus 이동(77-79) **단 `hasOpenModalDialog()`이면 focus skip**(모달 뒤 숨은 버튼 오눌림 방지, `busyPolicy.ts:107-110`), 포커스 트랩 없음(주석 70-71 "Tab으로 계속 빠져나갈 수 있어야 한다"), 경과초는 `aria-hidden="true"`(126-131, 매초 낭독 방지), 스피너 `aria-hidden="true"`(122). 코드가 케이스 기대결과의 5개 세부조건(role/live/busy·포커스이동·모달시 미탈취·경과숨김·스피너장식) 전부와 1:1 매칭 |

## 3. 근거 드리프트 (카탈로그 file:line 정합성 결함 — 별도 이슈로 미등록, 사실만 기록)

`LabelingPage.tsx`가 07-30 최신화 이후 추가 변경(Phase 3 busy overlay 등)으로 라인이 대거 밀렸다. 기능 자체는 전부 실측·정합하므로 FAIL로 잡지 않고 드리프트로만 기록한다.

| 케이스 | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-A11Y-001 | `LabelingPage.tsx:1205-1310` | `:1381-1470`대 (tablist/tab/tabpanel) |
| TC-A11Y-005 | `LabelingPage.tsx:846-901` | 단축키 로직 자체가 `features/label/hooks/useLabelingShortcuts.ts`로 **파일이 이동**됨. `LabelingPage.tsx:846-901`은 현재 `handleRenameTrack`/`handleDeleteTrack`(트랙 편집) 코드로 무관 |
| TC-A11Y-006 | `LabelingPage.tsx:1079-1090` | `:1234-1243` |
| TC-A11Y-008 | `LabelingPage.tsx:1264` | `:1431-1435` |

## 4. 이슈

없음 — 31건 전부 PASS. FAIL/PARTIAL/확인필요 해당 없음.

## 5. 검증 메모

- 프론트 role별 실측: dev-login(`/dev/login`)으로 REVIEWER(1001,김검수)/WORKER(2001,최라벨) 양쪽 전환 확인. `/manage/users`(Pagination), `/task`(KpiCard aria-pressed, TaskBoardTable aria-sort), `xyz-nonexistent-route`(AppErrorPage 404) 실동작 스냅샷 확보.
- 다른 검증 에이전트와 브라우저(:13000) 세션이 공유되어(동시 다중 탭 `/label/*`·`/augment/result/*`·`/review/*` 관찰됨) 저작 흐름 중간에 탭 전환이 발생 — 영향받은 항목(TC-A11Y-014 등)은 vitest 단정으로 보강해 판정 신뢰도를 유지했다.
- H-12/H-13 전건 REVIEWER/WORKER 양쪽 코드 경로 확인, self-fill(외부 미경유 자체 채움) 해당 없음(순수 FE 컴포넌트/a11y 계층이라 외부 연동 케이스 없음).
