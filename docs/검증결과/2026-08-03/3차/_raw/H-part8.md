# H클러스터 Part8 — H-12/H-13/H-16/H-17/H-18 (2026-08-03 3차)

담당 범위: `docs/test-cases/H-frontend-e2e.md` H-12(374-393) · H-13(395-413) · H-16(438-467) · H-17(469-492) · H-18(494-517) — 총 80건.
방법: 정적 대조(Read) 전건 + 라이브 API 확인(backend :18081, REVIEWER/WORKER dev 토큰) 일부 + 이전 회차(2026-08-02 2차) 이슈 대조.
빌드/테스트 미실행(지시 준수). 스택은 `_raw/stack-bringup.md` 기준 기동·배선 OK 확인된 상태를 전제.

---

## 카탈로그 정정 (완료, 1건)

`docs/test-cases/H-frontend-e2e.md` **TC-FE-224**를 Edit로 직접 정정함(내 담당 라인범위 내, 프로덕션 코드 미수정).

- **정정 전**: "WORKER 시각 URL `?status=IN_PROGRESS`" 를 전제로 하면서 기대결과는 "select 값 유지, 서버 파라미터에서는 제외(`asWorkStatusParam`→undefined)" — 이는 실제로는 **REVIEWER 축**(`asWorkStatusParam`)의 동작 서술이며, WORKER 축은 다른 함수(`asAssignmentWorkStatusParam`)를 쓰고 결과도 반대(서버로 전송됨)다. 2차 `H-ISSUE-144`가 이미 이 불일치를 지적했으나 후속 정정(`H-ISSUE-147`)은 file:line만 고치고 본문 텍스트는 그대로 남아 있었다.
- **실측(라이브)**: `GET /api/v1/assignments?workerId=2001&workStatus=IN_PROGRESS` (WORKER 토큰) → `200`, `totalElements` 49(무필터) → 1(필터 적용) — **서버로 전송되고 실제로 필터링됨**을 확인. 반대로 `GET /api/v1/tasks/board?...&workStatus=IN_PROGRESS` (REVIEWER 토큰) → `400 INVALID_INPUT "board.workStatus: 허용되지 않은 workStatus 값"` — REVIEWER 축은 BE 가 거부.
- **정정 후**: TC-FE-224 를 WORKER 축 전용 케이스로 재서술(select 유지 + 서버 전송 확인, REVIEWER 축은 TC-FE-225 가 별도로 다룸을 명시), 근거 file:line 도 `asWorkStatusParam` vs `asAssignmentWorkStatusParam` 두 함수 모두를 가리키도록 보정.

---

## 이전 회차(2차) 이슈 해소 여부 대조

| 2차 이슈 | 내용 | 이번 회차 상태 |
|---|---|---|
| H-ISSUE-144 | TC-FE-224 기대결과가 stale(카탈로그 정합성) | **부분 해소 → 이번에 완전 정정.** file:line 은 2차 후속(H-ISSUE-147)에서 고쳤으나 본문 텍스트가 REVIEWER/WORKER 축을 혼동한 채 남아 있어 이번에 재정정함(위 "카탈로그 정정" 참조) |
| H-ISSUE-145 | TC-FE-242 기대결과가 stale("클라이언트 필터") | **해소 확인.** 현재 카탈로그는 "서버 필터(정렬만 미지원)"으로 이미 정정되어 있고 코드(`TaskListPage.tsx`에 `.filter(` 재필터 없음, `buildAssignmentParams`가 q/workStatus/eventTypeCd를 서버로 위임)와 일치 |
| H-ISSUE-146 | WORKER KPI 4카드가 전체 기준이 아니라 현재 페이지 20행만 집계(정책 위반, MEDIUM) | **미해소 — 이번 회차에도 재확인.** `TaskWorkerKpiCards.tsx:20-21` 이 `rowStatuses.filter()`로 페이지 내 카운트, `TaskListPage.tsx:484-487` `workerRowStatuses = pagedRows.map(...)`. 아래 H-ISSUE-141 로 이월 |
| H-ISSUE-147 | H-16 근거 file:line 광범위 드리프트(14건 표) | **거의 전건 해소.** 표의 14개 TC 중 13건은 현재 카탈로그가 정확한 위치(또는 정확한 위치를 포함하는 약간 넓은 범위)를 가리킴. TC-FE-223 하나만 `boardParams.ts:80-96`(원래 드리프트 값)로 **미수정 상태였으나, 실제 `asUiWorkStatus`(90-93)가 이 범위 안에 포함되어 있어 추적성에 실질적 지장은 없음** — 별도 이슈로 올리지 않음(경미) |

2차 H-ISSUE 중 H-12/H-13/H-18 관련 항목은 없었음(2차에서 이 세 섹션은 이슈 없이 통과).

---

## 판정 결과표

### H-12. 공통 컴포넌트/에러/상태 (16건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-178 | PASS | [정적] `ErrorBoundary.tsx:21-38` — `getDerivedStateFromError`가 사용자 메시지만 state에 담고, `componentDidCatch`는 `console.error`로만 스택 출력. 렌더는 `role="alert"` + "오류가 발생했습니다" 고정 문구 |
| TC-FE-179 | PASS | [정적] `ErrorBoundary.tsx:30` `this.props.fallback ?? (...)` — fallback prop 우선 렌더 |
| TC-FE-180 | PASS | [정적] `AppErrorPage.tsx` — 403/404/500 `TITLES` 매핑, `role="alert"` |
| TC-FE-181 | PASS | [정적] `EmptyState.tsx` — `role="status"`, 기본 메시지 "데이터가 없습니다" |
| TC-FE-182 | PASS | [정적] `AuthImage.tsx:60-122` — `srcSn`→`/frames/{srcSn}/image`, blob→objectURL, 로딩/에러 `<div>` 폴백에 `toFallbackProps`로 `data-*/aria-*` 유지(43-47행) |
| TC-FE-183 | PASS | [정적] `Pagination.tsx` — `onPageChange` 클릭 핸들러 전건 배선 |
| TC-FE-184 | PASS | [정적] `DataTable.tsx:60-77` — `handleSort`, `col.sortable`인 컬럼만 정렬 버튼 렌더 |
| TC-FE-185 | PASS | [정적] `Toast.tsx` — `variantClass`/`variantIcon` 4종(success/error/warning/info) 매핑 |
| TC-FE-186 | PASS | [정적] `pages/NoticeListPage.tsx`·`pages/NoticeDetailPage.tsx` 존재 확인 |
| TC-FE-187 | PASS | [정적] `pages/RoleClaimPage.tsx` 존재 확인 |
| TC-FE-188 | PASS | [정적] `WorkerStatPage.tsx:7,143`(recharts `DailyCompletionChart`) vs `OverallStatPage.tsx:12-13`(자체 `SimpleBarChart`/`SimplePieChart`, recharts 미사용) — 카탈로그 "(정정)" 서술과 일치 |
| TC-FE-214 | PASS | [정적] `lib/api/imagePath.ts:11` `ALLOWED_IMAGE_PATH = /^\/v1\/frames\/[0-9]{1,19}\/(?:deid-)?image$/` — 화이트리스트 밖 경로는 `toApiImagePath`가 `null` 반환 → `AuthImage.tsx:74-78`에서 요청 자체를 보내지 않고 즉시 에러 폴백(fail-closed) |
| TC-FE-215 | PASS | [정적] `authImageStore.ts:97-105` `acquireAuthImage` — 기존 엔트리 있으면 `refCount+=1`로 같은 promise 재사용(재요청 없음) |
| TC-FE-216 | PASS | [정적] `authImageStore.ts:108-118` `releaseAuthImage` — `refCount`가 0이 되는 즉시 `entries.delete` + `URL.revokeObjectURL`. 영속 캐시(localStorage 등) 코드 없음 |
| TC-FE-217 | PASS | [정적] `authImageStore.ts:97-105` — `existing.failed`일 때만 `startFetch(path, existing)` 재요청, acquire 시점에만 발생(자동 재시도 루프 없음) |
| TC-FE-218 | PASS | [정적] `AuthImage.tsx:27-47` `IMG_ONLY_PROPS` 목록 정의 후 `toFallbackProps`가 이를 제거하고 나머지만 div에 전개 |

테스트 커버: `components/common/__tests__/AuthImage.test.tsx` 14개 케이스 존재 확인(TC-FE-182,214~218 전부 커버).

### H-13. 접근성 (a11y) (15건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-A11Y-001 | PASS | [정적] `LabelingPage.tsx:1406-1408` `role="tablist"`, 각 탭 `role="tab"` + `aria-selected`/`aria-controls`(1414-1418 등) |
| TC-A11Y-002 | PASS | [정적] `AiToolModal.tsx:197,208` `label htmlFor` ↔ `input id` 라디오 2종. `ObjectAttributePanel.tsx:144-150` "즉시 그리기" 체크박스도 `htmlFor="ai-segment-immediate"` ↔ `id` 연결 확인 |
| TC-A11Y-003 | PASS | [정적] `Modal.tsx:47-93` — ESC(`keydown` Escape→`onClose`), 포커스 트랩(Tab 순환, 47-93행), `lastActiveRef`로 닫힘 시 포커스 복귀(91행) |
| TC-A11Y-004 | PASS | [정적] `MarkingPage.tsx:132-150` — `Space`(마킹 추가)/`Delete·Backspace`(삭제)/`Enter`(제출), `INPUT/TEXTAREA/SELECT` 포커스 시 무시 가드 |
| TC-A11Y-005 | PASS | [정적] `LabelingPage.tsx:1000-1046` `useLabelingShortcuts` — W/S(첫/끝 프레임)·F/Q(폴리곤)·T(표시토글)·`?`(치트시트)·Ctrl+C/V(복붙) 전부 배선 |
| TC-A11Y-006 | PASS | [정적] `LabelingPage.tsx:1246-1255` `isLocked` 배너 — `role="status" aria-live="polite"` |
| TC-A11Y-007 | PASS | [정적] `PortalUploadPage.tsx:201-210` `role="progressbar" aria-valuenow/valuemin/valuemax` |
| TC-A11Y-008 | PASS | [정적] `LabelingPage.tsx:1461-1467`(구 근거 1465와 근접) 미해소 배지 `aria-label={\`미해소 문의 ${n}건\`}` |
| TC-A11Y-009 | PASS | [정적] `PortalUploadPage.tsx:312` 삭제 버튼 `aria-label={\`${upload.orgnlFileNm} 삭제\`}` |
| TC-A11Y-010 | PASS | [정적] `BatchStageIndicator.tsx` `StageIcon` — Check/Loader2/X 아이콘 전부 `aria-hidden` |
| TC-A11Y-011 | PASS | [정적] `lib/focusRing.ts` `KRDS_FOCUS` — `focus-visible:ring-[3px] ring-offset-2 ring-primary-500` |
| TC-A11Y-012 | PASS | [정적] `ReviewPage.tsx:296-298` `aria-label="검수 캔버스 (읽기 전용)"` |
| TC-A11Y-013 | PASS | [정적] `KpiCard.tsx:48-62` — `onClick && selected!==undefined`일 때만 `aria-pressed` 부여(19-29행 주석대로 미전달 시 속성 자체 미부여), 테두리 두께(`border-2`, 60행)로도 구분 |
| TC-A11Y-014 | PASS | [정적] `TaskBoardTable.tsx:58-84` `SortableHeader` — `<th aria-sort={ascending\|descending\|none}>` + 내부 `<button>` |
| TC-A11Y-015 | PASS | [정적] `BusyOverlay.tsx:108-131` — `role="status" aria-live="polite" aria-busy="true"`(109-114), 취소 버튼 포커스 이동(77-79, `hasOpenModalDialog()` 가드 포함), 포커스 트랩 없음(70행 주석), 경과 초 `aria-hidden="true"`(129행), 스피너 `aria-hidden`(122행), 모달 열림 시 포커스 미탈취(72-79행) — 6개 세부 요구사항 전부 실제 코드에서 확인 |

### H-16. 작업목록 필터·정렬·KPI (24건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-219 | PASS | [정적] `boardSort.ts:38-40` `DEFAULT_BOARD_SORT=[{regDt,desc}]`, `boardParams.ts:163` `toBoardSortParams(sort.length?sort:DEFAULT_BOARD_SORT)` 항상 명시 전송 |
| TC-FE-220 | PASS | [정적] `boardSort.ts:102-114` `parseBoardSort` — `isSortColumn` 통과 못하면 조용히 스킵(무시), BE 400 유발 안 함 |
| TC-FE-221 | PASS | [정적] `boardParams.ts:67` `BOARD_BATCH_STATUS=BATCH_STATUS_PARAMS.COMPLETED` 고정, `searchParamsToFilters`(231행)는 `status` 를 읽지 않음(워크플로 축만 읽음) |
| TC-FE-222 | PASS | [정적] `boardParams.ts:234-235` `searchParamsToFilters` — `workStatus: asUiWorkStatus(sp.get('status'))` (URL `status` 키를 워크플로 축으로 해석) |
| TC-FE-223 | PASS | [정적] `boardParams.ts:90-93` `asUiWorkStatus` — allowlist 밖이면 빈 문자열. (카탈로그 근거는 `:80-96`으로 다소 넓게 표기돼 있으나 해당 함수를 포함해 추적에 지장 없음) |
| TC-FE-224 | PASS | [실동작+정적, 카탈로그 정정 완료] 위 "카탈로그 정정" 절 참조. `GET /v1/assignments?workerId=2001&workStatus=IN_PROGRESS` 실측 200/필터링 확인(49→1) |
| TC-FE-225 | PASS | [정적] `TaskListPage.tsx:93-102` — REVIEWER 축 `asWorkStatusParam(parsed.workStatus)` falsy 면 `workStatus:''` 로 정규화(select 도 빈칸) |
| TC-FE-226 | PASS | [정적] `boardSort.ts:35` `MAX_BOARD_SORT_KEYS=3`, `applyBoardSort:54-65` 서버 키 중복 제거 + `slice(0,3)`로 최고령 키 제거 |
| TC-FE-227 | PASS | [정적] `boardSort.ts:86-93` `toggleBoardSort` — 클릭 컬럼을 배열 맨 앞(`[{column,direction}, ...kept]`)에 배치, 첫 클릭 desc/재클릭 asc |
| TC-FE-228 | PASS | [정적] `boardSort.ts:73-80` `sortDirectionOf` — `BOARD_SORT_KEY_BY_COLUMN[e.column]===serverKey` 서버 키 기준 비교(컬럼명 videoId↔rawSn 전환에도 유지) |
| TC-FE-229 | PASS | [정적] `boardParams.ts:256-264` `filtersToSearchParams` — `isDefaultSort`면 `sort:[]`(URL 미기록) |
| TC-FE-230 | PASS | [정적] `TaskListPage.tsx:377-384` `handleFiltersReset` — 필터 초기화 + `setSort([...DEFAULT_BOARD_SORT])` 동시 수행 |
| TC-FE-231 | PASS | [정적] `TaskBoardKpiCards.tsx` 전체(5카드) + `TaskListPage.tsx:387-393` `handleKpiSelect`(같은 값 재클릭 시 `undefined`로 해제) |
| TC-FE-232 | PASS | [실동작+정적] `TaskBoardKpiCards.tsx:92-98` '작업중' 카드 = `WORK_STATUS_PARAMS.PENDING`. 실측: `GET /v1/tasks/board?...&workStatus=IN_PROGRESS`(REVIEWER) → `400 INVALID_INPUT`으로 BE 가 실제로 거부함을 확인 |
| TC-FE-233 | PASS | [정적] `boardParams.ts:176-181` `buildBoardSummaryParams` — `workStatus` 필드 자체가 조립 객체에 없음 |
| TC-FE-234 | PASS | [정적] `TaskBoardKpiCards.tsx:37-66` — `isLoading`→`data-testid="kpi-loading"` 스켈레톤, `isError`→`kpi-error` role=status, `TaskListPage.tsx:190` `error = isReviewer ? boardError : tasksError`(summary 에러 미포함) |
| TC-FE-235 | PASS | [정적] `TaskListPage.tsx:327` `const pagedRows = allRows;`(재필터 없음) + `:334` `totalElements = listPage?.totalElements` |
| TC-FE-236 | PASS | [정적] `TaskListPage.tsx:356-363` 화면에 없는 선택 항목 제거 effect + `:365` `clearSelection` |
| TC-FE-237 | PASS | [정적] `TaskListPage.tsx:368-374`(`handleFiltersChange`)·`387-393`(`handleKpiSelect`)·`396-403`(`handleSort`) 전부 같은 콜백에서 `setPage(0)`+`clearSelection()` 동시 처리 |
| TC-FE-238 | PASS | [정적] `TaskListPage.tsx:344-348` `page > totalPages-1` 이면 되돌림 effect |
| TC-FE-239 | PASS | [정적] `useTaskBoardEventTypes.ts:11,19-34` `EMPTY_OPTIONS={items:[],truncated:false}` 폴백, `{items,truncated}` 객체 분해(배열 오인 없음) |
| TC-FE-240 | PASS | [정적] `TaskListPage.tsx:526-538` `hasListError` 시 `ErrorState`+역할별 다른 안내 문구(WORKER 에게 배정 언급 안 함), `:575` 배정 버튼 `disabled={hasListError}` |
| TC-FE-241 | PASS | [정적] `boardParams.ts:61` `MAX_SEARCH_KEYWORD_LENGTH=100`, `:131-134` `asKeyword`가 `slice(0,100)`. `TaskFilters.tsx:131` `maxLength={MAX_SEARCH_KEYWORD_LENGTH}` |
| TC-FE-242 | PASS | [정적, 카탈로그 자체가 이미 실제 동작과 일치] `TaskListPage.tsx:134-137` `buildAssignmentParams` 로 검색/상태/이벤트 서버 위임, `.filter(` 재필터 코드 없음(`:212` `tasks=tasksPage?.content`). KPI는 `TaskWorkerKpiCards.tsx`가 `pagedRows` 기반 페이지 단위 집계임을 카탈로그가 명시 — 단, 이 페이지 단위 집계 자체는 정책 위반 소지가 있어 별도 이슈(H-ISSUE-141)로 이월 |

테스트 커버: `features/task/__tests__/boardSort.test.ts`, `boardParams.test.ts` 존재 확인.

### H-17. 검수목록 진입 기본값·필터·정렬·KPI (18건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-243 | PASS | [정적] `reviewListParams.ts:80-83` `DEFAULT_REVIEW_SORT={submittedAt,asc}`, `:92-95` `DEFAULT_REVIEW_FILTERS.status='REVIEW_PENDING'` |
| TC-FE-244 | PASS | [정적] `reviewListParams.ts:208-220` `toReviewSearchParams` — status/sort 기본값도 항상 기록(`compactParams` 대상에서 제외되지 않음, 208-219행에 조건부 생략 없음) |
| TC-FE-245 | PASS | [정적] `reviewListParams.ts:112-119` `asUiReviewStatus` — allowlist 밖이면 `DEFAULT_REVIEW_FILTERS.status`로 정규화. 재정규화는 같은 값 반환(멱등) |
| TC-FE-246 | PASS | [정적] `reviewListParams.ts:37` `REVIEW_STATUS_ALL='ALL'` — 전체 선택 시 키 삭제 아닌 `status=ALL` 유지(`:215` `toReviewSearchParams`) |
| TC-FE-247 | PASS | [정적] `features/review/api.ts:38-43` `REVIEW_STATUS_TO_BE: Record<ReviewStatus, ReviewStatusParam>` — 타입상 키 누락이 컴파일 에러. 매핑 값 확인(PENDING/IN_REVIEW/APPROVED/REJECTED) |
| TC-FE-248 | PASS | [정적] `api.ts:55-64` `listReviews` — `status ? REVIEW_STATUS_TO_BE[status] : undefined`(매핑 실패 시 필터 생략, 빈 결과 위장 없음) |
| TC-FE-249 | PASS | [정적] `reviewListParams.ts:122-125` `parseReviewSort` — allowlist 밖 컬럼이면 `{...DEFAULT_REVIEW_SORT}` 반환 |
| TC-FE-250 | PASS | [정적] `ReviewListPage.tsx` 컬럼 정의부에 `status` 컬럼 `sortable` 미부여(코드 주석에도 tie-break 역전 사유 명시) |
| TC-FE-251 | PASS | [정적] `ReviewListPage.tsx:268-274` 제출일 헤더 클릭 → `handleSortChange` → `writeSearchParams({sort:...})` |
| TC-FE-252 | PASS | [정적] `ReviewListFilters.tsx:16` `SEARCH_DEBOUNCE_MS=300`, `:83-91` debounce effect, `:95-98` 조회(Enter) 즉시 적용, `:107-111` 초기화 시 `cancelPendingSearch()` 명시 취소 |
| TC-FE-253 | PASS | [정적] `ReviewKpiCards`(제목상 `features/review/components/ReviewKpiCards.tsx`) 4카드 + `reviewListParams.ts:168-174` `buildReviewSummaryParams` — 타입 `ReviewSummaryParams`가 `q`만 허용 |
| TC-FE-254 | PASS | [정적] `ReviewListPage.tsx:161-165`(유사) `useReviewSummary({enabled:isReviewer})` — 비REVIEWER 는 조회 자체 안 함, summary 에러는 카드 영역만 영향 |
| TC-FE-255 | PASS | [정적] `ReviewListPage.tsx:334-345` `error` 이면 `ErrorState`만 렌더(표·총건수 미표시), 정상/에러 분기 명확 |
| TC-FE-256 | PASS | [정적] `ReviewListPage.tsx:349-358` `isRefreshing=isFetching&&!isLoading` → `aria-busy` + `role="status"` "갱신 중… (아래 목록은 이전 조건의 결과입니다)" |
| TC-FE-257 | PASS | [정적] `ReviewListPage.tsx:143-154` `totalPages===undefined`면 판단 보류(early return), 응답 도착 후에만 페이지 복귀 |
| TC-FE-258 | PASS | [정적] `ReviewListPage.tsx:188-216`(유사) `isDefaultView` 판정에 `sort.column`/`sort.direction` 포함 — 정렬만 바꿔도 초기화 버튼 활성 |
| TC-FE-259 | PASS | [정적] `ReviewListPage.tsx:218-235` `actionLabel` — 표시 문구("검수시작"/"이어서 검수"/"결과보기")를 그대로 버튼 accessible name 으로 사용, 장식 화살표는 `aria-hidden` |
| TC-FE-260 | PASS | [정적] `ReviewListPage.tsx:366-372`(유사, emptyMessage 분기) + `ReviewListFilters.tsx:178` `"${REVIEW_STATUS_LABEL[values.status]} 상태만 표시 중"` 배지 문구 |

테스트 커버: `features/review/__tests__/reviewListParams.test.ts`, `api.statusMapping.test.ts` 존재 확인.

### H-18. 영상 목록·영상 상세 (7건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-297 | PASS | [정적] `pages/VideoDetailPage.tsx` 전체 grep — "버전관리"/"history"/`onNavigateLabel` 문자열 0건. `pages/HistoryPage.tsx` 파일 자체가 존재하지 않음(삭제 확인) |
| TC-FE-298 | PASS | [정적] 위와 동일 grep — 라이트박스 관련 "라벨링 편집" 텍스트 없음, `onNavigateLabel` prop 체인 부재 확인 |
| TC-FE-299 | PASS | [정적] `router/index.tsx` grep — `history`/`HistoryPage` 매치 0건(라우트 자체가 정의돼 있지 않음) → `/history/1` 진입 시 catch-all 404 경로로 귀결 |
| TC-FE-300 | PASS | [정적] `LabelHeader.tsx:133-149` — `showHistory && videoId!==undefined && onHistoryClick` 조건부 렌더(구 `/history` Link 폴백 없음), 주입 시 `aria-expanded={historyOpen}` 토글 버튼 |
| TC-FE-301 | PASS | [실동작+정적] `VideoFilters.tsx:44-64` `handleSubmit`이 `cctvNameKeyword`(trim)·`eventTypeCd`·`from`·`to`·`dataSttsCd` 조립, `video/api.ts:76-83` `listVideos`가 params 그대로 전달. 실측: `GET /v1/videos?cctvNameKeyword=zzz_no_such_cctv` → `200`/`totalElements:0`(필터 실제 반영), `eventTypeCd=BOGUS_XYZ` → `200`/0건(★5 그대로), `from>to` → `400 INVALID_INPUT`(★5 그대로) — BE 계약과 FE 조립이 일치 |
| TC-FE-302 | PASS | [정적] `VideoFilters.tsx:15-29` `STATUS_OPTIONS` — 전체/완료(COMPLETED)/처리중(PROCESSING)/마킹 대기(MARKING_READY)/대기(PENDING)/실패(FAILED) 6항목(전체 포함) 확인, `MARKING_READY` 존재 |
| TC-FE-303 | PASS | [실동작+정적] `video/api.ts:44` `capturedAt: (v.capturedAt ?? '') as string`(regDt 폴백 없음). 실측: `GET /v1/videos` 응답에서 `"capturedAt":null` 이면서 `"regDt":"2026-08-04T..."` 존재하는 행 확인(BE 도 폴백하지 않고 null 그대로 반환) — FE 가 이를 몰래 채우지 않음을 라이브로 재확인 |

---

## 판정 집계

| 구분 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-12 | 16 | 16 | 0 | 0 | 0 | 0 | 0 |
| H-13 | 15 | 15 | 0 | 0 | 0 | 0 | 0 |
| H-16 | 24 | 24 | 0 | 0 | 0 | 0 | 0 |
| H-17 | 18 | 18 | 0 | 0 | 0 | 0 | 0 |
| H-18 | 7 | 7 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **80** | **80** | **0** | **0** | **0** | **0** | **0** |

담당 범위 80건 전건 PASS. FAIL/PARTIAL 없음 — 단, 아래 정책 위반성 이슈 1건(H-ISSUE-141, 2차 H-ISSUE-146 carry-forward)은 카탈로그의 명시적 기대결과 자체가 이미 이 동작을 "구 동작 그대로" 유지한다고 밝히고 있어 TC-FE-242 개별 판정은 PASS로 유지하되, 근본 동작이 루트 CLAUDE.md 정책과 어긋나므로 이슈로 별도 기록한다.

---

## 이슈 기록

### [H-ISSUE-141] TC-FE-242 인접 — WORKER KPI 4카드가 "전체 기준"이 아니라 현재 페이지 20행만 집계한다 (2차 H-ISSUE-146 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "목록 화면 정렬·필터 정책" 구속 규칙 — "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다." REVIEWER 5카드(`TaskBoardKpiCards`)는 `/v1/tasks/board/summary` 서버 집계를 쓰므로 이 규칙을 지킨다. WORKER 4카드도 헤더 "전체 N건"과 같은 집합을 말해야 사용자가 잔여 작업량을 정확히 인지한다.
- **현재 동작(이슈 내용)**:
  ```tsx
  // frontend/src/features/task/components/TaskWorkerKpiCards.tsx:20-21
  export function TaskWorkerKpiCards({ rowStatuses }: TaskWorkerKpiCardsProps) {
    const count = (status: RowStatus) =>
      rowStatuses.filter((s) => s === status).length;
  ```
  ```tsx
  // frontend/src/pages/TaskListPage.tsx:484-487
  const workerRowStatuses = useMemo(
    () => pagedRows.map((r) => r.rowStatus),
    [pagedRows],
  );
  ```
  `pagedRows`는 현재 페이지(최대 20건)만 담는다(`:327` `const pagedRows = allRows;`, `allRows`는 `tasksPage?.content`). "전체 작업" 카드는 21건 이상 배정된 WORKER 에게 항상 페이지 크기(20)로 고정되고, 헤더의 "전체 N건"(서버 `totalElements`)과 어긋난다. 소스 주석(`TaskWorkerKpiCards.tsx:12-17`)은 "REVIEWER 전용 집계 API(403)라 구 동작 그대로"라고 사유를 밝히고 있으나 정책이 금지한 페이지 단위 집계다.
- **재현/확인 경로**:
  ```
  # 배정 21건 이상인 WORKER 로 /task 진입 시 재현. 현재 시드는 worker 2001 배정 49건(실측)이라 이미 재현 조건을 충족한다.
  curl -H "Authorization: Bearer $WORKER_TOKEN" "http://localhost:18081/api/v1/assignments?workerId=2001&page=0&size=20" → totalElements 49 (헤더 "전체 49건")
  화면: KPI "전체 작업" 카드는 pagedRows.length = 20 으로 표시 → 헤더와 불일치
  ```
- **영향**: 데이터 정합/사용자 오판(보안 영향 없음). 작업자가 본인 잔여 작업량을 실제보다 적게 인식할 수 있다.
- **수정 방향(제안)**: ①BE 에 WORKER 도 호출 가능한 배정 집계 엔드포인트(예: `GET /v1/assignments/summary`, 서버 인가로 본인 범위 고정) 추가 후 REVIEWER 5카드와 동일 "전체 기준" 축으로 통일하거나, ②카드 라벨을 "이 페이지 기준"으로 명시하고 "전체 작업" 값만 서버 `totalElements` 로 대체. **본 검증에서는 수정하지 않음.**

---

## 비고

- H-12/H-13/H-16/H-17/H-18 담당 범위 안에서는 위 TC-FE-224 1건 외에 카탈로그 file:line 드리프트나 기대결과 stale 이 추가로 발견되지 않았다(2차에서 지적된 H-ISSUE-144/145/147 은 위 표에서 대조 완료).
- H-16/H-18 일부 케이스(TC-FE-232, TC-FE-301, TC-FE-303)는 라이브 API 호출(REVIEWER/WORKER dev 토큰 발급 후 `/v1/tasks/board`, `/v1/assignments`, `/v1/videos` 직접 조회)로 실동작까지 확인했다. 나머지는 정적 대조(코드 인용) 기반이며, 관련 자동 테스트 파일(`boardSort.test.ts`, `boardParams.test.ts`, `reviewListParams.test.ts`, `api.statusMapping.test.ts`, `AuthImage.test.tsx`, `LabelHeader.test.tsx`, `PortalUploadLabelingBusy.test.tsx`) 존재를 확인해 커버리지 공백은 없었다.
- 빌드/테스트는 실행하지 않았으므로(지시 준수) 위 테스트 파일들의 **통과 여부** 자체는 `_raw/test-baseline.md`(baseline 담당)를 참조할 것.
