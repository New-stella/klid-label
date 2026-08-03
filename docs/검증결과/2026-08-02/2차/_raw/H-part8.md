# H-part8 — H-15 E2E 전체 사용자 시나리오 · H-16 작업목록 필터·정렬·KPI · H-17 검수목록 진입 기본값·필터·정렬·KPI

> 담당 범위: `docs/test-cases/H-frontend-e2e.md` 의 **H-15(6건) + H-16(24건) + H-17(18건) = 48건**
> 검증일: 2026-08-02 (2차) · 대상 코드: worktree `qa-0801`
> 스택: frontend `http://localhost:13000` · backend `http://localhost:18081/api` (context-path `/api`) — 둘 다 기동 확인

## 0. 검증 환경·방법 실측

| 항목 | 실측 |
|------|------|
| backend | `GET /api/actuator/health` → 200 (`/actuator/health` 는 404 — context-path `/api`) |
| frontend | `GET /` → 200 |
| 인증 | `POST /api/v1/dev/tokens` 로 REVIEWER(userNo=1001) JWT 발급 → 세션스토리지 `klid_jwt` 주입 후 화면 진입 |
| 실동작 관측 | Playwright MCP + 페이지 내 `performance.getEntriesByType('resource')` 로 **실제 XHR 쿼리스트링** 캡처(네트워크 패널 등가) |
| 정적 대조 | `frontend/src/features/task/*` · `features/review/*` · `pages/TaskListPage.tsx` · `pages/ReviewListPage.tsx` · backend `common/util/SortAllowlist.java` |
| 테스트 커버 | FE 단위테스트 실측 파일: `TaskListPage.board.test.tsx`(39) · `TaskListPage.worker.filters.test.tsx`(14) · `ReviewListPage.filters.test.tsx`(26) · `boardSort.test.ts`(11) · `boardParams.test.ts`(16) · `reviewListParams.test.ts`(9) · `api.statusMapping.test.ts`(3). `_raw/test-baseline.md`(2026-08-01 1차) 기준 **frontend 1,951 tests / 실패 0** |
| ⚠ 관측 제약 | Playwright MCP 브라우저를 **다른 검증 에이전트와 공유**하고 있어 current-tab 이 수시로 탈취됐다(=`/augment/*`·`/portal`·`/forbidden` 로 튐). 그래서 일부 상호작용 케이스는 정적+단위테스트 근거로 판정했고 판정 셀 옆 `근거 확인` 에 `[정적]` 으로 명시했다. **환경 미기동이 아니라 동시 사용 경합**이다. |

### ★2 확정 정책(정렬 strict/lenient 비대칭) — 실측 재확인 (결함 아님)

```
GET /api/v1/tasks/board?page=0&size=3&sort=priority,desc
 → HTTP 400 {"success":false,"message":"지원하지 않는 정렬 기준입니다.","errorCode":"INVALID_INPUT"}   (strict)

GET /api/v1/reviews?page=0&size=3&sort=labelPayload,asc
 → HTTP 200 {"success":true,"data":{"totalElements":23,...}}                                        (lenient 폴백)
```
`SortAllowlist.java:29-53`(모드 표) 주석과 실동작이 정확히 일치. **비일관으로 보고하지 않음.**

### 서버 집계 = 전체 기준 (BE 실측 vs 화면 실측 대조)

```
GET /api/v1/tasks/board/summary?status=COMPLETED
 → {"total":42,"unassigned":30,"inProgress":3,"reviewPending":2,"completed":6,"rejected":1}   (합 42 = total)
화면 KPI 5카드          → 전체 42 / 미배정 30 / 작업중 3 / 검수요청 2 / 반려 1   (일치)
화면 표                 → 20행(page size) · 헤더 "전체 42건"                     (현재 페이지 집계 아님)

GET /api/v1/reviews/summary → {"total":23,"pending":1,"inReview":1,"approved":20,"rejected":1}
GET /api/v1/reviews?status=PENDING|IN_REVIEW|APPROVED|REJECTED 각 totalElements = 1 / 1 / 20 / 1  (KPI와 완전 일치)
화면 KPI 4카드          → 검수요청 1 / 검수중 1 / 승인 20 / 반려 1
```

---

## 1. 판정 결과표

### H-15. E2E 전체 사용자 시나리오 (6건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:----:|------|------|
| TC-E2E-014 | PARTIAL | [정적] | `e2e/specs/labeling-flow.spec.ts:26-42` — 시나리오(목록→캔버스→BBox→저장)는 존재하나 **도구/캔버스/저장 버튼 단언이 전부 `if ((await …count()) > 0)` 로 감싸져** 있어, 요소가 렌더되지 않으면 아무것도 검증하지 않고 통과한다. 토스트 단언도 `getByText(/저장|완료/)` 로 느슨. → H-ISSUE-141 |
| TC-E2E-015 | FAIL | [정적] | `e2e/specs/worker-labeling.spec.ts:29-36` — 기대결과는 "바운딩박스 버튼 렌더" 인데 단언이 `expect(cnt).toBeGreaterThanOrEqual(0)` **항상 참인 공허한 단언**이다. 버튼이 0개여도 통과 → 케이스가 보장하려는 것을 전혀 보장하지 않음. → H-ISSUE-142 |
| TC-E2E-016 | FAIL | [실동작] | 스펙 전제 `WORKFLOW_VIDEO_ID = 9035`(`e2e/fixtures/test-data.ts:53`)가 **현재 DB에 없다**: `GET /api/v1/videos/9035` → 404 `NOT_FOUND`, `GET /api/v1/reviews/9035` → 404. 영상 PK 최대치는 906(=`/v1/videos?sort=rawSn,desc` 실측). 제출/반려/승인 단계가 전부 `/reviews/9035/*` 를 호출하므로 시나리오 완주 불가. → H-ISSUE-143 |
| TC-E2E-017 | FAIL | [실동작] | 동일 원인(H-ISSUE-143). `describe.serial` 이라 앞 단계(제출·반려) 실패 시 이후 자동 스킵되고, "반려 후 롤백" 전제 자체가 성립하지 않는다. 참고: `WORKFLOW_SRC_SN=241` 은 실재하나 **`videoId=24`** 에 속해(`GET /v1/frames/241/labels` → `"videoId":24`) 스펙이 가정한 9035 와 불일치 |
| TC-E2E-018 | FAIL | [실동작] | 동일 원인. `POST /reviews/9035/start`·`/reject` 대상 부재(404) |
| TC-E2E-019 | FAIL | [실동작] | 동일 원인. `POST /reviews/9035/approve` 대상 부재(404) |

> 참고: 016~019 의 **스펙 코드 자체 품질은 양호**하다(응답 status·토스트 문구를 실제로 단언). 결함은 **픽스처 시드 드리프트**다.
> 검수목록 화면(`data-testid="review-list-page"`)은 실동작으로 정상 렌더 확인 — 016의 "REVIEWER 검수목록 진입" 단계만은 화면 레벨에서 성립한다.

### H-16. 작업목록 필터·정렬·KPI (SCR-TASK-001) (24건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:----:|------|------|
| TC-FE-219 | PASS | [실동작] | URL 에 `sort` 없이 `/task` 진입 → 실제 요청 `GET /api/v1/tasks/board?status=COMPLETED&page=0&size=20&sort=regDt,desc` — **명시 전송**. 상태 우선순위(`ORDER BY CASE`) 정렬 흔적 없음. (`boardSort.ts:38-40`, `boardParams.ts:151-165`) |
| TC-FE-220 | PASS | [실동작] | `/task?status=BOGUS&sort=priority,desc` → 요청 `…&sort=regDt,desc` (미등록 키 **조용히 제거**, BE 400 미발생). FE 매핑 `BOARD_SORT_KEY_BY_COLUMN`(regDt/capturedAt→shtDt/shtDt/rawSn/videoId→rawSn)이 BE `SortAllowlist.TASK_BOARD`(`SortAllowlist.java:73-78`)와 **완전 동일** |
| TC-FE-221 | PASS | [실동작] | `/task?status=UNASSIGNED` → 요청 `status=COMPLETED&workStatus=UNASSIGNED&…` — 배치 축은 COMPLETED 고정, URL 로 못 바꾼다 |
| TC-FE-222 | PASS | [실동작] | URL 키 `status` 가 워크플로 축으로 해석됨(위 UNASSIGNED 실측 + KPI 클릭 시 URL `?status=PENDING` ↔ 요청 `workStatus=PENDING` 왕복 실측). 단위테스트 `boardParams.test.ts` "구_URL_status_값은_워크플로_축으로_해석된다" |
| TC-FE-223 | PASS | [실동작] | `?status=BOGUS` → `workStatus` 파라미터 자체가 빠짐(전체 표시), 400 없음. URL 도 `/task` 로 정리 |
| TC-FE-224 | PARTIAL | [실동작] | **기대결과가 현행 구현과 불일치**. REVIEWER: `?status=IN_PROGRESS` 진입 시 select 값도 `''`, 서버 파라미터도 없음(= "select 값은 유지" 미성립, 대신 TC-FE-225 동작). WORKER: `asAssignmentWorkStatusParam` 이 `IN_PROGRESS` 를 **허용**해 `/v1/assignments?workStatus=IN_PROGRESS` 로 **서버 전송**된다(BE 실측: 전체 16건 → 필터 2건). 어느 역할에서도 "select 유지 + 서버 미전송" 조합은 존재하지 않음 → H-ISSUE-144 |
| TC-FE-225 | PASS | [실동작] | REVIEWER + `?status=IN_PROGRESS` → 상태 select `value=""`, 요청에 `workStatus` 없음, URL `/task` 로 정규화. select 빈칸-목록 전체가 **서로 일치**(어긋난 화면 아님) (`TaskListPage.tsx:93-102`) |
| TC-FE-226 | PASS | [실동작] | 입력 `?sort=videoId,desc&sort=rawSn,asc&sort=shtDt,asc&sort=regDt,asc`(4개, videoId·rawSn 동일 서버키) → 요청 `sort=rawSn,desc&sort=shtDt,asc&sort=regDt,asc` — **서버키 중복 제거 + 3개 상한**, BE 400 미발생. BE 상한도 allowlist 고유 필드수(regDt/shtDt/rawSn=3)로 동일 |
| TC-FE-227 | PASS | [실동작] | 기준 `?sort=regDt,asc` 상태에서 촬영일시 헤더 1회 클릭 → `sort=shtDt,desc&sort=regDt,asc` (**맨 앞** 배치), 2회 클릭 → `sort=shtDt,asc&sort=regDt,asc`. aria-sort 도 descending→ascending |
| TC-FE-228 | PASS | [실동작] | `?sort=videoId,desc` 로 진입 → 서버 키 `rawSn,desc` 로 왕복되어도 **영상 ID 헤더 `aria-sort="descending"` 유지** |
| TC-FE-229 | PASS | [실동작] | 기본 정렬 상태의 URL 은 `/task` (파라미터 없음). 비기본 정렬만 `?sort=…` 기록 |
| TC-FE-230 | PASS | [실동작] | `?sort=regDt,asc` 진입 → "초기화" 클릭 → URL `/task`, 요청 `sort=regDt,desc`(기본), 촬영일시 aria-sort 제거. regDt 는 헤더가 없어 초기화가 유일 복구 경로임이 실동작으로 확인 |
| TC-FE-231 | PASS | [실동작] | KPI 5카드(전체42/미배정30/작업중3/검수요청2/반려1) = `/tasks/board/summary` 응답과 완전 일치(현재 페이지 20행 아님). 카드 클릭 → `workStatus` 필터 적용 + `aria-pressed=true`, 재클릭 → 해제(전체) |
| TC-FE-232 | PASS | [실동작] | '작업중' 카드 = `summary.inProgress`(3) 표시, 클릭 시 **`workStatus=PENDING`** 전송(`IN_PROGRESS` 아님). BE 는 `IN_PROGRESS` 를 board 축에서 반환·허용하지 않음(`types.ts:164-180`) → 구 결함(REVIEWER 시각 0 고정) 재발 없음 |
| TC-FE-233 | PASS | [실동작] | summary 요청은 `GET /tasks/board/summary?status=COMPLETED` — **`workStatus` 미포함**. 카드 선택 후에도 동일. 검색어/이벤트/작업자는 반영(`boardParams.ts:173-182`) |
| TC-FE-234 | PASS | [정적] | `TaskBoardKpiCards.tsx:37-52` `data-testid="kpi-loading"` 스켈레톤 · `:55-66` `kpi-error` + `role="status"` + 문구 "집계 정보를 불러오지 못했습니다. 목록은 정상 표시됩니다." 정확 일치. 페이지 레벨 error 는 `TaskListPage.tsx:188-191` 에서 **목록 쿼리만** 사용. 단위테스트 'KPI_조회_실패가_목록_표시를_막지_않는다'·'KPI_카드_영역은_로딩중_스켈레톤을_표시한다' |
| TC-FE-235 | PASS | [실동작] | `pagedRows = allRows`(`TaskListPage.tsx:327`, 재필터 없음). 헤더 "전체 42건" = 서버 `totalElements`(BE 실측 42), 미배정 카드 적용 후 "전체 30건"(BE unassigned=30) — 목록/총건수/KPI 가 같은 집합 |
| TC-FE-236 | PASS | [정적] | `TaskListPage.tsx:356-363`(화면에 없는 선택 제거 effect) + `:365 clearSelection` + `:405-411 handlePageChange` 에서 페이지 전환 시 선택 초기화. 단위테스트 '페이지_전환시_선택된_체크박스가_초기화된다'·'갱신_후_화면에_없는_선택은_해제된다' |
| TC-FE-237 | PASS | [실동작] | KPI 카드 클릭 시 URL/요청이 `page=0` 으로 즉시 리셋됨을 실측(직전 페이지로의 추가 요청 없음). 필터·정렬도 동일 핸들러 구조(`:367-403` 에서 `setPage(0)`+`clearSelection()` 동반) |
| TC-FE-238 | PASS | [정적] | `TaskListPage.tsx:344-348` — `!isLoading && page > totalPages-1` 이면 마지막 페이지로 복귀. 단위테스트 '총_페이지_수가_줄면_현재_페이지가_범위_안으로_되돌아온다' |
| TC-FE-239 | PASS | [실동작] | BE 응답이 **객체**임을 실측: `GET /tasks/board/event-types?status=COMPLETED` → `{"items":["EV02000201","INTRUSION","LOITERING"],"truncated":false}` (assignments 쪽도 동형). 훅이 `items`/`truncated` 로 분해(`useTaskBoardEventTypes.ts:29-34`), 실패 시 `EMPTY_OPTIONS` 폴백, 절단 안내 문구 "옵션이 많아 일부만 표시됩니다"(`TaskFilters.tsx:164-167`) |
| TC-FE-240 | PASS | [정적] | `TaskListPage.tsx:526-538` ErrorState + `:601 actionsDisabled={hasListError}` → `TaskBoardTable.tsx:155,246,325-330` 체크박스·배정 버튼 disabled + title 안내. WORKER 에겐 "배정 기능" 문구 미노출(`:531-535` 역할 분기). 단위테스트 '목록_조회_실패시_배정_액션이_잠긴다'·'목록_실패_배너는_WORKER_에게_배정_기능을_안내하지_않는다' |
| TC-FE-241 | PASS | [실동작] | 검색 input `name="task-filter-q"` 의 `maxLength=100` 실측. 파라미터 조립도 `slice(100)`(`boardParams.ts:131-134`, BE `@Size(max=100)` 정합) |
| TC-FE-242 | PARTIAL | [실동작] | **기대결과가 현행 구현과 불일치 + 별개 잠재결함 1건**. ①`/v1/assignments` 는 `q`/`workStatus`/`eventTypeCd` 를 **서버에서 지원**한다(BE 실측: 전체 16 → `workStatus=IN_PROGRESS` 2건, `q=zzzz` 0건). 화면도 서버 위임이며(`TaskListPage.tsx:322-327` "클라이언트 재필터 금지 — 역할 무관") 헤더 "전체 N건"은 서버 `totalElements` 다 → "화면에서 거른다 / 거른 행 수" 는 stale. ②"KPI 표시 전용 4카드(클릭 필터 없음)" 는 성립하나, 그 4카드가 **현재 페이지 행만 집계**한다(`TaskWorkerKpiCards.tsx:20-30` `rowStatuses`=pagedRows) → 21건 이상 배정 시 "전체 작업" 카드가 헤더 총건수와 어긋난다 → H-ISSUE-145 / H-ISSUE-146 |

### H-17. 검수목록 진입 기본값·필터·정렬·KPI (SCR-REVIEW-001) (18건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:----:|------|------|
| TC-FE-243 | PASS | [실동작] | 파라미터 없는 `/review` 진입 → 실제 요청 `GET /api/v1/reviews?page=0&size=20&sort=submittedAt,asc&status=PENDING` — FE 코드 `REVIEW_PENDING` 이 BE 코드 `PENDING` 으로 역매핑되고 **정렬을 명시 전송**(BE 기본 최신순에 의존하지 않음) |
| TC-FE-244 | PASS | [실동작] | 진입 직후 주소창 = `/review?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20` — 케이스 기대 문자열과 **완전 일치**. 새로고침해도 동일 요청 재현 |
| TC-FE-245 | PASS | [실동작] | `?status=BOGUS&sort=labelPayload,asc` → 주소가 `?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20` 으로 **교정**(replace, 히스토리 미오염) + 요청도 `status=PENDING&sort=submittedAt,asc`. 정규값 재진입은 no-op(루프 없음 — `ReviewListPage.tsx:38-45,120-124` `isSameSearch` 키 개수까지 비교) |
| TC-FE-246 | PASS | [실동작] | `?status=ALL` → URL 에 `status=ALL` 유지 + 요청에서 `status` **키 자체 생략**(`/reviews?page=0&size=20&sort=submittedAt,asc`). 키 삭제 방식이 아니라 명시 값이라 새로고침에도 "전체" 가 보존됨 |
| TC-FE-247 | PASS | [정적] | `features/review/api.ts:40-45` `REVIEW_STATUS_TO_BE: Record<ReviewStatus, ReviewStatusParam>` — 매핑 4종(REVIEW_PENDING→PENDING / REVIEWING→IN_REVIEW / COMPLETED→APPROVED / REJECTED→REJECTED). `Record` 라 FE 상태 추가 시 키 누락 = 컴파일 에러. 단위테스트 `api.statusMapping.test.ts` '매핑_상수는_FE_상태_4종을_모두_덮는다' |
| TC-FE-248 | PASS | [실동작] | 위장 위험을 BE 실측으로 확인: `status=REVIEW_PENDING`·`COMPLETED`·`BOGUS` 는 **400 이 아니라 200 + totalElements 0**(빈 결과 위장). FE 는 `api.ts:55-64` 에서 매핑 없는 값이면 `status` 키를 빼고 요청 → 목록이 사라지지 않음 |
| TC-FE-249 | PASS | [실동작] | `?sort=labelPayload,desc`(미등록) → `submittedAt,asc` 로 정규화되어 전송·표시. BE lenient 폴백에 화면이 끌려가지 않음. FE allowlist `REVIEW_SORT_COLUMNS=['submittedAt','videoId','status']` ⊂ BE `SortAllowlist.REVIEW`(`SortAllowlist.java:115-119`) |
| TC-FE-250 | PASS | [실동작] | 실제 DOM `th` 실측 — 제출일만 정렬 버튼 보유, **상태 헤더는 버튼 없음**: `영상명::nobtn / 이벤트::nobtn / 작업자::nobtn / 제출일::ascending::btn / 라벨 수::nobtn / 상태::nobtn / 액션::nobtn`. BE allowlist 엔 `status` 가 있으나 화면에 노출 안 함(`ReviewListPage.tsx:280-289` 주석 근거와 일치) |
| TC-FE-251 | PASS | [실동작] | `page=1` 상태에서 제출일 헤더 클릭 → URL `?status=ALL&sort=submittedAt,desc&page=0&size=20` + 요청 `sort=submittedAt,desc` (page 0 복귀), 재클릭 → `asc`. aria-sort 도 descending→ascending 토글 |
| TC-FE-252 | PASS | [정적] | `ReviewListFilters.tsx:16` `SEARCH_DEBOUNCE_MS=300`, `:81-92` debounce effect, `:94-99` submit(조회/Enter) 시 `cancelPendingSearch()` 후 즉시 적용, `:105-109` 초기화 시 **대기 타이머 명시 취소** + 입력값 기본값 복귀. 단위테스트 '초기화_직후_대기중이던_검색어가_되살아나지_않는다' |
| TC-FE-253 | PASS | [실동작] | KPI 4카드(검수요청1/검수중1/승인20/반려1) = `/reviews/summary` 응답과 일치하며 각 상태별 목록 `totalElements` 와도 정확히 일치. summary 요청은 파라미터 **없음**(`status` 미전송). `ReviewSummaryParams` 가 `q` 만 허용해 타입상 차단(`reviewListParams.ts:161-174`) |
| TC-FE-254 | PASS | [정적] | `ReviewListPage.tsx:161-165` `useReviewSummary(..., { enabled: isReviewer })`(비 REVIEWER 403 스팸 차단) + `:340` 페이지 레벨 `error` 는 **목록 쿼리만**. 카드 영역만 `kpi-error`(`ReviewKpiCards.tsx:57-67`) |
| TC-FE-255 | PASS | [정적] | `ReviewListPage.tsx:334-345` — `error` 면 `ErrorState` 만 렌더하고 `DataTable`(표·"총 N건")은 아예 그리지 않음. 실패를 "대상 0건" 으로 오독할 화면이 없음 |
| TC-FE-256 | PASS | [정적] | `ReviewListPage.tsx:134-141` `isRefreshing = isFetching && !isLoading`, `:346-358` `aria-busy` + `role="status"` "갱신 중… (아래 목록은 이전 조건의 결과입니다)" |
| TC-FE-257 | PASS | [정적] | `ReviewListPage.tsx:143-154` — `totalPages === undefined`(로딩·실패)면 **판단하지 않고 return**, 응답이 있을 때만 `page > lastPage` 에서 마지막 페이지로 replace 복귀(루프 없음) |
| TC-FE-258 | PASS | [실동작] | `status=ALL`(비기본) 상태에서 "초기화" 버튼 **활성** 실측. 활성 판정 `isDefaultView`(`:212-216`)가 q·status 뿐 아니라 **sort.column/direction 까지** 포함 → 정렬만 바꾼 사용자도 복구 가능. 초기화 동작은 `DEFAULT_REVIEW_FILTERS`+`DEFAULT_REVIEW_SORT` 로 복귀(`:189-196`) |
| TC-FE-259 | PASS | [실동작] | 행 액션 버튼 실측 — 보이는 문구 "결과보기" / "이어서 검수" 가 그대로 accessible name 접두: `aria-label="결과보기 CCTV-강남구-001"`, `aria-label="이어서 검수 CCTV-QA1"`. 장식 `▶` 는 `aria-hidden`(`:302`)이라 이름에서 제외 → WCAG 2.5.3 충족 |
| TC-FE-260 | PASS | [정적] | `ReviewListPage.tsx:366-372` — `status===''` 면 "검수 항목이 없습니다", 아니면 `${REVIEW_STATUS_LABEL[status]} 항목이 없습니다`(= "검수요청 항목이 없습니다"). 활성 필터 배지 `ReviewListFilters.tsx:171-180` → "검수요청 상태만 표시 중". 라벨은 `REVIEW_STATUS_LABEL` 단일 상수 공유 |

---

## 2. 집계

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| H-15 | 6 | 0 | 4 | 1 | 0 | 0 | 0 |
| H-16 | 24 | 22 | 0 | 2 | 0 | 0 | 0 |
| H-17 | 18 | 18 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **48** | **40** | **4** | **3** | **0** | **0** | **0** |

PASS율 40/48 = **83.3%** · 실동작 근거 판정 **31건** / 정적 근거 **17건**

### 확증편향 반증 시도 결과 (반증에 성공한 지점)
1. **"서버 이관됐다"는 주장 자체를 반증 시도** → 작업목록·검수목록 모두 실제 XHR 로 서버 필터·서버 집계 확인. **클라이언트 재필터는 존재하지 않음**(`pagedRows = allRows`, `rows = data?.content`). 다만 **WORKER KPI 4카드만은 여전히 현재 페이지 집계**라는 예외를 찾아냄(H-ISSUE-146).
2. **strict/lenient 비대칭이 정말 다른지** → 400 vs 200 실측 확인(정책대로, 결함 아님).
3. **E2E 스펙이 정말 그 기대결과를 단언하는지** → `expect(cnt).toBeGreaterThanOrEqual(0)` 공허 단언(H-ISSUE-142)과 조건부 단언(H-ISSUE-141) 발견 = **테스트가 통과해도 아무것도 보장하지 않는 구간**.
4. **E2E 전제 데이터가 실재하는지** → `rawSn=9035` 부재(404) 확인 = 전체 워크플로 E2E 4건 완주 불가(H-ISSUE-143).

---

## 3. 이슈 대장 (H-ISSUE-141 ~ 147)

### [H-ISSUE-141] TC-E2E-014 — 라벨링 플로우 E2E 의 핵심 단언이 전부 조건부라 미렌더 시 조용히 통과
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: "목록→캔버스→BBox 작성→저장→저장 토스트" 가 실제로 동작함을 E2E 가 보장해야 한다. 라벨링은 이 제품의 핵심 작업이라, 도구바·캔버스·저장 버튼 중 하나라도 사라지면 E2E 가 **반드시 빨간불**이어야 한다.
- **현재 동작(이슈 내용)**: `frontend/e2e/specs/labeling-flow.spec.ts:26-42`
  ```ts
  if ((await labeling.bboxToolBtn.count()) > 0) { await labeling.bboxToolBtn.first().click(); }
  if ((await labeling.canvas.count()) > 0) { await labeling.drawBoundingBox(...); }
  if ((await labeling.saveBtn.count()) > 0) {
    await labeling.save();
    await expect(workerPage.getByText(/저장|완료/).first()).toBeVisible({ timeout: 5000 });
  }
  ```
  세 요소가 모두 0개면 테스트는 **아무 단언 없이 PASS** 한다. 토스트 매처도 `/저장|완료/` 라 "저장 실패"·"저장하시겠습니까" 같은 무관한 텍스트에도 매칭될 수 있다.
- **재현/확인 경로**: 라벨링 화면에서 BBox 도구 버튼의 selector 가 바뀌면(리팩터링) 이 스펙은 계속 초록불을 유지한다. 대조군: 같은 저장소의 `labeling-review-full-flow.spec.ts:40-55` 는 `await expect(labeling.bboxToolBtn).toBeVisible()` + `waitForResponse(PUT /frames/{srcSn}/labels)` + `getByText('저장됨 · 버전 기록됨')` 으로 무조건 단언한다.
- **영향**: 기능 회귀 감지 실패(거짓 PASS). 보안 영향 없음.
- **수정 방향(제안)**: `labeling-flow.spec.ts` 의 `if (count() > 0)` 가드를 제거하고 `full-flow` 스펙과 동일하게 `toBeVisible()` + API 응답 + 정확한 토스트 문구로 단언한다. 요소가 조건부로만 존재한다면 그 조건을 전제(fixture)로 명시한다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-142] TC-E2E-015 — 바운딩박스 버튼 렌더 단언이 항상 참(`>= 0`)인 공허한 단언
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WORKER 라벨링 진입 시 **바운딩박스 도구 버튼이 렌더**됨을 보장해야 한다(케이스 기대결과 원문).
- **현재 동작(이슈 내용)**: `frontend/e2e/specs/worker-labeling.spec.ts:29-36`
  ```ts
  test('라벨링_도구_바운딩박스_버튼_렌더', async ({ workerPage }) => {
    const labeling = new LabelingPage(workerPage);
    await labeling.goto(TEST_VIDEO_WITH_LABEL);
    const cnt = await labeling.bboxToolBtn.count();
    expect(cnt).toBeGreaterThanOrEqual(0);   // ← 개수가 0이어도 통과. 항상 참.
  });
  ```
  `count()` 는 음수가 될 수 없으므로 이 단언은 **어떤 상황에서도 실패하지 않는다**. 테스트 이름만 "버튼 렌더" 이고 실제로는 아무것도 검증하지 않는다.
- **재현/확인 경로**: 도구 패널을 통째로 제거해도 이 테스트는 통과한다.
- **영향**: 기능 회귀 감지 실패(거짓 PASS). 통과율 통계를 왜곡한다.
- **수정 방향(제안)**: `await expect(labeling.bboxToolBtn.first()).toBeVisible({ timeout: 5000 })` 로 교체하거나, 풀스크린 UI 문구 변동이 우려되면 `data-testid` 를 부여해 안정 selector 로 단언한다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-143] TC-E2E-016/017/018/019 — 전체 워크플로 E2E 픽스처(`WORKFLOW_VIDEO_ID=9035`)가 현재 DB에 존재하지 않아 완주 불가
- **심각도**: HIGH
- **기대 동작(기대효과)**: 라벨링→저장→검수제출→반려→롤백→재제출→승인 전 구간이 실제 스택에서 완주되어야 한다. 이 4건은 H 클러스터에서 유일하게 **작업 종결 워크플로 전체를 검증**하는 자산이다.
- **현재 동작(이슈 내용)**: `frontend/e2e/fixtures/test-data.ts:53-54`
  ```ts
  export const WORKFLOW_VIDEO_ID = 9035;
  export const WORKFLOW_SRC_SN = 241;
  ```
  현재 스택 실측:
  ```
  GET /api/v1/videos/9035  → 404 {"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}
  GET /api/v1/reviews/9035 → 404 {"errorCode":"NOT_FOUND","message":"검수 대상 영상을 찾을 수 없습니다."}
  GET /api/v1/videos?page=0&size=3&sort=rawSn,desc → 최대 id 906 (9035 는 범위 밖)
  GET /api/v1/frames/241/labels → {"srcSn":241,"frameNo":5,"videoId":24, ...}   ← 241 은 9035 가 아니라 24 소속
  ```
  스펙은 `/reviews/9035/submit`·`/start`·`/reject`·`/approve` 를 `waitForResponse` 로 기다리므로 제출 단계에서 타임아웃/404 로 실패하고, `describe.serial` 이라 이후 롤백·재제출·승인 3건이 연쇄 스킵된다. 스펙 상단 주석의 DB 전제(`RAW_DATA_ID=9035`, `LS_TASK_ASSIGNMENT` LABELER(2001)/REVIEWER(1001))도 현 시드와 어긋난다.
- **재현/확인 경로**: 위 curl 4줄. (E2E 실행은 본 검증 범위상 금지라 미실행 — 데이터 부재만으로 실패가 확정된다.)
- **영향**: 기능. 검수 워크플로 종결 경로의 E2E 커버리지가 **사실상 0** 이며, "E2E 스펙 11개 보유" 라는 자산 통계가 실제 보장과 어긋난다.
- **수정 방향(제안)**: ①E2E 전용 시드(Flyway `test` 프로파일 또는 `e2e/fixtures` 의 setup 스크립트)로 `rawSn=9035` + 배정 + 라벨 버전 2건을 **테스트가 스스로 만들도록** 바꾸거나, ②픽스처를 현 시드의 실재 값(예: 배정·제출 가능한 rawSn 과 그 첫 프레임 srcSn 을 API 로 조회해 주입)으로 동적 해석한다. 하드코딩 상수는 시드가 바뀔 때마다 같은 방식으로 다시 깨진다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-144] TC-FE-224 — "IN_PROGRESS 는 UI 값이되 서버 미전송" 기대결과가 현행 구현과 불일치(카탈로그 stale)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 원문은 "WORKER 시각 URL `?status=IN_PROGRESS` → select 값은 유지(클라이언트 필터), 서버 파라미터에서는 제외(`asWorkStatusParam`→undefined)" 를 기대한다.
- **현재 동작(이슈 내용)**: 축이 둘로 분리되면서 이 조합이 사라졌다.
  - REVIEWER 축(`boardParams.ts:100-106 asWorkStatusParam`): `IN_PROGRESS` → `undefined`(서버 미전송)인 것은 맞으나, `TaskListPage.tsx:93-102` 가 초기 state 에서 `workStatus=''` 로 정규화하므로 **select 값도 유지되지 않는다**(= TC-FE-225 가 기술하는 동작). 실측: select `task-filter-status` value `""`.
  - WORKER 축(`boardParams.ts:115-121 asAssignmentWorkStatusParam`): `IN_PROGRESS` 는 **허용값**이라 `/v1/assignments?workStatus=IN_PROGRESS` 로 **서버 전송**된다. BE 도 지원 — 실측 `workerId=2001` 전체 16건 → `workStatus=IN_PROGRESS` 필터 시 2건.
- **재현/확인 경로**:
  ```
  화면: /task?status=IN_PROGRESS (REVIEWER) → 요청 status=COMPLETED&page=0&size=20&sort=regDt,desc (workStatus 없음), select value=""
  BE  : curl -H "$AUTH" "/api/v1/assignments?workerId=2001&workStatus=IN_PROGRESS&page=0&size=2" → totalElements 2
  ```
- **영향**: 기능 영향 없음(현행 동작이 정책상 더 옳다 — 클라이언트 필터 금지). **카탈로그 기대결과의 정합성 결함**이며, 그대로 두면 다음 회차에서 "구현이 틀렸다" 는 오판을 부른다.
- **수정 방향(제안)**: TC-FE-224 를 폐기(`~~취소선~~` + `[폐기 2026-08-02]`)하고, 두 축 각각의 실제 계약으로 케이스를 재작성한다 — ①REVIEWER: URL `IN_PROGRESS` 는 select·요청 양쪽에서 제거(TC-FE-225 와 통합) ②WORKER: `IN_PROGRESS` 는 select 유지 + `/v1/assignments` 로 **서버 전송**. **본 검증에서는 카탈로그를 수정하지 않음.**

### [H-ISSUE-145] TC-FE-242 — "WORKER 시각은 클라이언트 필터" 기대결과가 현행 구현과 불일치(카탈로그 stale)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 원문은 "`/v1/assignments` 가 필터·정렬을 지원하지 않아 화면에서 거른다. 헤더 '전체 N건'=거른 행 수" 를 기대한다.
- **현재 동작(이슈 내용)**: `/v1/assignments` 는 `q`/`workStatus`/`eventTypeCd` 를 **서버에서 지원**하고 화면도 서버에 위임한다.
  ```
  BE 실측: /assignments?workerId=2001            → totalElements 16
           /assignments?workerId=2001&q=zzzz     → totalElements 0
           /assignments?workerId=2001&workStatus=IN_PROGRESS → totalElements 2
           /assignments/event-types              → {"items":[...],"truncated":false}
  ```
  `TaskListPage.tsx:322-327` — "화면에 그릴 행 = 서버가 이미 거른 결과 그대로다 … 클라이언트 재필터 금지 — **역할 무관**", `pagedRows = allRows`(:327), 총건수 `listPage?.totalElements`(:334). `features/task/types.ts:79-88` 도 서버사이드 필터임을 명시.
- **재현/확인 경로**: 위 curl 4줄 + `TaskListPage.worker.filters.test.tsx` 의 '작업목록_클라이언트_재필터가_적용되지_않는다' / '전체건수는_서버_totalElements_를_표시한다'.
- **영향**: 기능 영향 없음(현행이 루트 `CLAUDE.md` "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다" 구속 정책에 부합). 카탈로그 정합성 결함.
- **수정 방향(제안)**: TC-FE-242 기대결과를 "WORKER 도 `/v1/assignments` 서버 필터를 사용하고 '전체 N건'=서버 `totalElements`. KPI 는 클릭 필터 없는 표시 전용 4카드" 로 정정한다. **본 검증에서는 카탈로그를 수정하지 않음.**

### [H-ISSUE-146] TC-FE-242 — WORKER KPI 4카드가 "전체 기준" 이 아니라 **현재 페이지 20행**만 집계한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "목록 화면 정렬·필터 정책" 구속 규칙 — **"필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다."** REVIEWER 5카드는 이 규칙대로 서버 집계(`/v1/tasks/board/summary`)를 쓴다. WORKER 4카드도 같은 기준이어야 헤더 "전체 N건" 과 카드가 같은 집합을 말한다.
- **현재 동작(이슈 내용)**: `frontend/src/features/task/components/TaskWorkerKpiCards.tsx:20-30`
  ```tsx
  export function TaskWorkerKpiCards({ rowStatuses }: TaskWorkerKpiCardsProps) {
    const count = (status: RowStatus) => rowStatuses.filter((s) => s === status).length;
    …
    <KpiCard data-testid="kpi-total" label="전체 작업" value={rowStatuses.length} … />
  ```
  `rowStatuses` 는 `TaskListPage.tsx:484-487` 의 `pagedRows.map(r => r.rowStatus)` = **현재 페이지 행(최대 20)**. 즉 "전체 작업" 카드는 21건 이상 배정된 WORKER 에게 항상 `20` 으로 고정되고, 헤더의 "전체 N건"(서버 `totalElements`)과 어긋난다. 소스 주석은 "REVIEWER 전용 집계 API(403)라 구 동작 그대로" 라고 사유를 밝히고 있으나, 정책이 금지한 페이지 단위 집계다.
- **재현/확인 경로**: 배정 21건 이상인 WORKER 로 `/task` 진입 → 헤더 "전체 21건" vs KPI "전체 작업 20". 현재 시드에서는 worker 2001 의 배정이 16건(`/assignments?workerId=2001` → `totalElements:16`)이라 **아직 드러나지 않는 잠재 결함**이다.
- **영향**: 데이터 정합/사용자 오판. 작업자가 자신의 잔여 작업량을 실제보다 적게 인식한다. 보안 영향 없음.
- **수정 방향(제안)**: ①BE 에 WORKER 도 호출 가능한 배정 집계 엔드포인트(예: `GET /v1/assignments/summary`, 서버가 인가로 본인 범위 고정)를 추가해 REVIEWER 5카드와 같은 "전체 기준" 축으로 통일하거나, ②당장 어렵다면 카드 라벨을 "이 페이지 기준" 으로 명시하고 "전체 작업" 값만 서버 `totalElements` 로 대체한다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-147] H-16 근거 `file:line` 광범위 드리프트 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 근거 `file:line` 은 클릭 시 해당 로직에 닿아야 한다(추적성).
- **현재 동작(이슈 내용)**: H-16 의 `boardParams.ts` / `TaskListPage.tsx` 근거가 일괄 어긋나 있다(H-15 의 `labeling-review-full-flow.spec.ts` 와 H-17 전체는 **정확**).
  | TC | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | TC-FE-219 | `boardParams.ts:126-140` | `boardParams.ts:151-165`(`buildBoardParams`) |
  | TC-FE-221 | `boardParams.ts:14-17,59,131` | `:18-21`(주석), `:67`(`BOARD_BATCH_STATUS`), `:156` |
  | TC-FE-222 | `boardParams.ts:168-176` | `:231-239`(`searchParamsToFilters`) |
  | TC-FE-223 | `boardParams.ts:80-96` | `:90-93`(`asUiWorkStatus`) |
  | TC-FE-224 | `boardParams.ts:74-96` | `:100-106`(`asWorkStatusParam`) |
  | TC-FE-229 | `boardParams.ts:188-202` | `:251-265`(`filtersToSearchParams`) |
  | TC-FE-233 | `boardParams.ts:142-157` | `:173-182`(`buildBoardSummaryParams`) |
  | TC-FE-241 | `boardParams.ts:53,106-109` | `:61`, `:131-134` |
  | TC-FE-225 | `TaskListPage.tsx:85-92` | `:93-102` |
  | TC-FE-230 | `TaskListPage.tsx:395-402` | `:377-384`(`handleFiltersReset`) |
  | TC-FE-231 | `TaskListPage.tsx:404-412` | `:387-394`(`handleKpiSelect`) |
  | TC-FE-234 | `TaskListPage.tsx:174-176` | `:188-191` |
  | TC-FE-235 | `TaskListPage.tsx:316-317,340-352` | `:327`, `:331-334` |
  | TC-FE-236 | `TaskListPage.tsx:368-381,423-429` | `:356-365`, `:405-411` |
  | TC-FE-237 | `TaskListPage.tsx:385-421` | `:367-403` |
  | TC-FE-238 | `TaskListPage.tsx:362-366` | `:344-348` |
  | TC-FE-240 | `TaskListPage.tsx:542-554,460-465` / `TaskBoardTable.tsx:149-163,320-340` | `:526-538`, `:601` / `TaskBoardTable.tsx:155,246,325-330` |
  | TC-FE-242 | `TaskListPage.tsx:319-334,350-352` | `:322-334`, `:482-487` |
  | TC-E2E-014 | `labeling-flow.spec.ts:10-25` | `:26-42`(BBox 저장 테스트) |
  | TC-E2E-015 | `worker-labeling.spec.ts:12-28` | `:29-36`(도구 버튼 테스트) |
  (`boardSort.ts` 근거 6건 — TC-FE-220/226/227/228 — 은 **전부 정확**)
- **재현/확인 경로**: 위 표의 좌우 대조.
- **영향**: 추적성. 다음 회차 검증자가 잘못된 라인을 읽고 "구현 없음" 으로 오판할 위험.
- **수정 방향(제안)**: H-16 표의 근거 컬럼을 위 실제 위치로 일괄 갱신. **본 검증에서는 카탈로그를 수정하지 않음.**

---

## 4. 판정 근거 원문 (실동작 로그 발췌)

### 작업목록 — 진입/URL 정규화/정렬
```
/task                                   → GET /v1/tasks/board?status=COMPLETED&page=0&size=20&sort=regDt,desc
                                          GET /v1/tasks/board/summary?status=COMPLETED
                                          GET /v1/tasks/board/event-types?status=COMPLETED
/task?status=UNASSIGNED                 → status=COMPLETED&workStatus=UNASSIGNED&page=0&size=20&sort=regDt,desc   (URL 유지)
/task?status=BOGUS&sort=priority,desc   → status=COMPLETED&page=0&size=20&sort=regDt,desc                          (URL → /task)
/task?status=IN_PROGRESS (REVIEWER)     → status=COMPLETED&page=0&size=20&sort=regDt,desc, select value=""         (URL → /task)
/task?sort=videoId,desc&sort=rawSn,asc&sort=shtDt,asc&sort=regDt,asc
                                        → sort=rawSn,desc&sort=shtDt,asc&sort=regDt,asc   (dedup + cap3)
                                          영상 ID aria-sort=descending / 촬영일시 aria-sort=ascending
촬영일시 헤더 1클릭(기준 sort=regDt,asc) → sort=shtDt,desc&sort=regDt,asc   (URL ?sort=shtDt,desc&sort=regDt,asc)
촬영일시 헤더 2클릭                       → sort=shtDt,asc&sort=regDt,asc
"초기화" 클릭                            → URL /task , sort=regDt,desc , 촬영일시 aria-sort 제거
검색 input                               → name="task-filter-q" maxLength=100
KPI 카드                                 → 전체 작업42 / 미배정30 / 작업중3 / 검수요청2 / 반려1 (aria-pressed 토글)
'작업중' 클릭                            → URL ?status=PENDING , 요청 workStatus=PENDING
'미배정' 클릭                            → URL ?status=UNASSIGNED , 요청 workStatus=UNASSIGNED , 헤더 "전체 30건" , 행 20
```

### 검수목록 — 진입/정규화/정렬/액션
```
/review                                        → GET /v1/reviews?page=0&size=20&sort=submittedAt,asc&status=PENDING
                                                 GET /v1/reviews/summary        (파라미터 없음)
                                                 URL = /review?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20
?status=BOGUS&sort=labelPayload,asc            → URL 교정 ?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20
                                                 요청 sort=submittedAt,asc&status=PENDING
?status=ALL&sort=submittedAt,asc&page=0&size=20 → 요청 page=0&size=20&sort=submittedAt,asc     (status 키 생략, URL 은 ALL 유지)
?status=REJECTED&sort=submittedAt,desc         → 요청 sort=submittedAt,desc&status=REJECTED
제출일 헤더 클릭 (page=1 상태)                  → URL ?status=ALL&sort=submittedAt,desc&page=0&size=20 (page 0 복귀)
제출일 헤더 재클릭                              → sort=submittedAt,asc
th 정렬 버튼 유무                               → 영상명 X / 이벤트 X / 작업자 X / 제출일 O(ascending) / 라벨수 X / 상태 X / 액션 X
행 액션 접근성 이름                             → "결과보기 CCTV-강남구-001" , "이어서 검수 CCTV-QA1"
KPI 4카드                                      → 검수요청1 / 검수중1 / 승인20 / 반려1
"초기화" 버튼(status=ALL 상태)                  → 활성(disabled=false)
```

### BE 계약 실측
```
GET /v1/tasks/board?sort=priority,desc  → 400 INVALID_INPUT "지원하지 않는 정렬 기준입니다."   (strict)
GET /v1/reviews?sort=labelPayload,asc   → 200 totalElements 23                                (lenient 폴백)
GET /v1/reviews?status=REVIEW_PENDING   → 200 totalElements 0   ← FE 코드 그대로 보내면 "빈 결과 위장"
GET /v1/reviews?status=BOGUS            → 200 totalElements 0   ← 동일
GET /v1/tasks/board/event-types?status=COMPLETED → {"items":["EV02000201","INTRUSION","LOITERING"],"truncated":false}
GET /v1/assignments/event-types                  → 동형 객체
GET /v1/assignments?workerId=2001                → 16 / &workStatus=IN_PROGRESS → 2 / &q=zzzz → 0
SortAllowlist.TASK_BOARD = {regDt, capturedAt→shtDt, shtDt, rawSn, videoId→rawSn}   (FE 매핑과 1:1 동일)
SortAllowlist.REVIEW     = {submittedAt→updDt, updDt, videoId→rawDataId, status→dataSttsCd}
```
