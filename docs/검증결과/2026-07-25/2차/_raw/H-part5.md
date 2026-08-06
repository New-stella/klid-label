# H 클러스터 part5 (H-13~H-17) 2차 검증 결과

> 대상: H-13(접근성 14) · H-14(보안 7) · H-15(E2E 6) · H-16(작업목록 24) · H-17(검수목록 18) = 69건
> 환경: backend `localhost:18081`(HEAD `ca3c712b`, 2차 stack-bringup 기준) · frontend 소스 `frontend/src`(정적) · DB 조회 없이 BE API 실호출 위주
> 방법: H-16·H-17 은 BE API 실호출(curl, dev 토큰) + FE 소스 대조. H-13/H-14 는 FE 소스 구조 대조([정적], JSX 속성·핸들러 실재 확인 + 관련 vitest 파일 존재로 baseline GREEN 커버 확인). H-15 는 Playwright 브라우저 자동화가 없어 실행 불가 → BLOCKED(스펙 파일 존재·구조는 대조).
> 1차(2026-07-25) 는 H 클러스터를 검증하지 않았음(1차 SUMMARY/ISSUES 에 H 없음) — 본 회차가 H-13~H-17 최초 검증이라 이전 이슈 대조 대상 없음.
> ⚠ 아래 표의 판정 셀은 여섯 토큰만 사용. 실동작/정적 구분은 "근거 확인" 컬럼 접두로 표기.

## 집계

| 클러스터 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-13 접근성 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| H-14 보안 | 7 | 5 | 0 | 0 | 2 | 0 | 0 |
| H-15 E2E | 6 | 0 | 0 | 0 | 6 | 0 | 0 |
| H-16 작업목록 | 24 | 23 | 0 | 1 | 0 | 0 | 0 |
| H-17 검수목록 | 18 | 18 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **69** | **60** | **0** | **1** | **8** | **0** | **0** |

이슈: 0건(FAIL) + 1건(PARTIAL, LOW — 카탈로그 기대결과 자체가 최신 구현과 어긋남). CRITICAL/HIGH 없음.

## ★목록 API 실측 (H-16·H-17)

REVIEWER 토큰(userNo 1001) 기준. `GET /api/v1/dev/tokens` 로 발급, 이하 전부 실제 HTTP 왕복.

| 엔드포인트 | 조건 | size | 상태코드 | totalElements/응답 | 전체기준 여부 |
|---|---|---|---|---|---|
| `GET /v1/tasks/board` | 정렬 없음(기본) | 5 | 200 | 79 | - |
| `GET /v1/tasks/board` | `sort=priority,desc`(미등록 키) | 5 | **400** `INVALID_INPUT` "지원하지 않는 정렬 기준입니다" | - | strict 확인 |
| `GET /v1/tasks/board` | `sort=regDt,desc&sort=shtDt,asc&sort=rawSn,desc&sort=videoId,asc`(4항목, 상한 3) | - | **400** "정렬 기준이 너무 많습니다" | - | 상한 확인 |
| `GET /v1/tasks/board` | `sort=videoId,desc&sort=rawSn,asc`(동일 서버키 중복) | 3 | 200 | - | dedup 통과(에러 없음) |
| `GET /v1/tasks/board` | `status=UNASSIGNED` | 5 | 200 | 30(배치축 필터로 별도 집합) | 배치축=워크플로축과 별개 확인 |
| `GET /v1/tasks/board` | `workStatus=PENDING` | 1 | 200 | **36** | KPI `inProgress`=36 과 일치(★下) |
| `GET /v1/tasks/board` | `workStatus=IN_PROGRESS` | 1 | **400** "허용되지 않은 workStatus 값" | - | BE 가 반환·허용 모두 안 함 확인 |
| `GET /v1/tasks/board` | `size=5` vs `size=50`(필터 동일) | 5 / 50 | 200 / 200 | **79 / 79**(동일) | **전체 기준 집계 확인**(페이지 흔들림 없음) |
| `GET /v1/tasks/board/summary` | 기본(status=COMPLETED) | - | 200 | `{total:79,unassigned:27,inProgress:36,reviewPending:1,completed:14,rejected:1}` | 불변식 27+36+1+14+1=79 **성립** |
| `GET /v1/tasks/board` | `q=`(101자) | - | **400** "검색어는 100자 이하여야 합니다" | - | `@Size(max=100)` 확인 |
| `GET /v1/tasks/board/event-types` | 기본 | - | 200 | `{items:[FALLDOWN,FIRE,INTRUSION,LOITERING],truncated:false}` | `{items,truncated}` 계약 확인 |
| `GET /v1/reviews` | 정렬 없음(기본) | 3 | 200 | 20, `submittedAt` **내림차순**(최신 179→178→173) | BE 기본=최신순(FE asc 오버라이드 필요성 실증) |
| `GET /v1/reviews` | `sort=labelPayload,asc`(미등록 키) | 3 | **200**(lenient 폴백) | 위와 동일 결과(기본 정렬로 폴백) | lenient 확인(strict 아님) |
| `GET /v1/reviews` | `status=BOGUS`(화이트리스트 밖) | 3 | **200** | `totalElements:0`(빈 결과로 위장, 에러 아님) | H-17 서두 경고 그대로 실증 |
| `GET /v1/reviews` | `status=PENDING&sort=submittedAt,asc` | 3 | 200 | 2건, `submittedAt` 오름차순(20011 07-30 11:23 → 128 07-31 03:22) | FIFO 진입 기본값 실증 |
| `GET /v1/assignments`(WORKER) | `q=강남`(URL 인코딩) | 3 | 200 | 50→**22** | 서버측 필터 확인(★TC-FE-242 참조) |

## H-13 접근성 (a11y) 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-A11Y-001 | 우측 패널 탭 role=tab/tabpanel | PASS | [정적] `pages/label/LabelingPage.tsx:1229-1310` role="tablist"/role="tab"/aria-selected/aria-controls, tabpanel role="tabpanel" aria-labelledby 실재 확인 | |
| TC-A11Y-002 | AiToolModal 라디오/체크박스 label 연결 | PASS | [정적] `features/label/components/AiToolModal.tsx` 헤더 주석 "라디오/체크박스는 label 연결(htmlFor)" + `AiToolModal.test.tsx` 존재(baseline GREEN) | |
| TC-A11Y-003 | Modal 포커스 트랩+ESC+포커스 복귀 | PASS | [정적] `components/common/Modal.tsx:47-93` ESC keydown(document), Tab 트랩(focusables 순환), `lastActiveRef.current?.focus()` 복귀. `Modal.test.tsx`에 `Escape`·Tab 순환 테스트 실재(baseline 1691 GREEN 포함) | |
| TC-A11Y-004 | 마킹 키보드 전 조작(Space/Del/Enter) | PASS | [정적] `pages/MarkingPage.tsx:133-152` Space=추가/Delete·Backspace=삭제/Enter=제출, INPUT/TEXTAREA/SELECT 포커스 시 제외 가드 | |
| TC-A11Y-005 | 라벨링 단축키(W/S/F/Q/T/?/Ctrl+C/V) | PASS | [정적] `pages/label/LabelingPage.tsx:856-905` `useLabelingShortcuts` 훅에 전 단축키 콜백 배선. `useLabelingShortcuts.test.tsx`·`.cheatsheet.test.tsx`·`.numberKeys.test.tsx` 3파일 존재 | |
| TC-A11Y-006 | 잠금 배너 aria-live=polite | PASS | [정적] `pages/label/LabelingPage.tsx:1095-1099` `role="status" aria-live="polite"` 실재 | 근거 드리프트: 카탈로그 1079-1090 → 실제 1095-1099 |
| TC-A11Y-007 | 진행률 progressbar aria-valuenow | PASS | [정적] `pages/portal/PortalUploadPage.tsx:201-209` `role="progressbar"` + `aria-valuenow/min/max` 실재 | |
| TC-A11Y-008 | 이슈 배지 aria-label 카운트 | PASS | [정적] `pages/label/LabelingPage.tsx:1291-1298` `aria-label="미해소 문의 {n}건"` 실재 | |
| TC-A11Y-009 | 삭제 버튼 aria-label(파일명) | PASS | [정적] `pages/portal/PortalUploadPage.tsx:312` `aria-label={"${upload.orgnlFileNm} 삭제"}` 실재 | |
| TC-A11Y-010 | 아이콘 aria-hidden(중복 낭독 방지) | PASS | [정적] `components/common/BatchStageIndicator.tsx:35,41,48` 3개 아이콘 전부 `aria-hidden` 실재 | |
| TC-A11Y-011 | KRDS 포커스링 키보드 초점 | PASS | [정적] `lib/focusRing.ts` `KRDS_FOCUS` = `focus-visible:` 계열 클래스(마우스 클릭 시 미표시, 키보드만) 정의 확인, 다수 컴포넌트가 재사용 | |
| TC-A11Y-012 | 검수 캔버스 aria-label 읽기전용 | PASS | [정적] `pages/ReviewPage.tsx:296-298` `aria-label="검수 캔버스 (읽기 전용)"` 실재 | |
| TC-A11Y-013 | KPI 필터 카드 aria-pressed 토글 시맨틱 (신규) | PASS | [정적] `components/common/KpiCard.tsx:48-62` `aria-pressed={onClick && selected!==undefined ? selected : undefined}` + `selected && 'border-2 border-primary-600'`(색상 단독 아닌 테두리 병행). `KpiCard.test.tsx` 존재(baseline GREEN) | |
| TC-A11Y-014 | 정렬 가능 헤더 aria-sort + button (신규) | PASS | [정적]+[실동작] `features/task/components/TaskBoardTable.tsx:63-83` `<th aria-sort={ascending\|descending\|none}>` 내부 `<button>`. `boardSort.ts`의 `sortDirectionOf` 로 서버키 비교 확인(실 API 로 `sort=rawSn,desc` 등 정렬 왕복 확인, ★목록 API 실측 참조) | |

## H-14 보안 (XSS/토큰/용어정책) 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-FE-189 | 프레임 설명 script 입력 텍스트 렌더(XSS) | PASS | [정적] React JSX 텍스트 노드는 기본 escape(프레임워크 보증) + `dangerouslySetInnerHTML={` 사용 0건(아래 190 근거와 동일 grep) — 텍스트 삽입 경로가 escape 우회할 수단이 없음 | e2e/specs/frame-description.spec.ts 원 근거는 브라우저 실행 필요(BLOCKED 아님, 정적 보증으로 대체 가능) |
| TC-FE-190 | dangerouslySetInnerHTML 미사용 전수 | PASS | [실동작] `grep -rn "dangerouslySetInnerHTML={" frontend/src/` → **0건**. `grep -rln "dangerouslySetInnerHTML"` (주석/문자열 포함) → 22개 파일, 전부 "미사용" 명시 주석/테스트 문자열로 확인 | |
| TC-FE-191 | FE 문구에 YOLO/SAM2 금지 | PASS | [정적] `features/label/components/AiToolModal.tsx:10-13` "용어 정책... 모델명(YOLO/SAM/SAM2) 금지" 명시 + `components/common/BatchStageIndicator.tsx:19-23` `STAGE_LABEL={YOLO:'AI 탐지', SAM2:'AI 분할'}`, 미매핑 폴백 "처리중"(`STAGE_LABEL_FALLBACK`) 실재 | |
| TC-FE-192 | 에러 메시지 내부경로/스택 미노출 | PASS | [정적] `components/common/ErrorBoundary.tsx:22` `componentDidCatch` 는 `console.error`(콘솔만) — 렌더 경로는 고정 문구("오류가 발생했습니다"/`fallback` prop)만 사용, `state.message`는 렌더에 쓰이지 않음. `resolveApiMessage.ts:4,16-19` `USER_FACING_STATUSES={400,409,412}` 외엔 항상 fallback | |
| TC-FE-193 | 사용자 ID axios URL 인코딩(IDOR/Path 방어) | PASS | [정적] `features/review/api.ts` 등 path 파라미터가 전부 타입 `number`(경로순회 문자 주입 불가) + axios 템플릿 리터럴 삽입, BE 측 `@PreAuthorize`/본인배정 검증 별도 존재 | |
| TC-E2E-012 | 프레임 설명 저장 실패 에러 표시 | BLOCKED | 브라우저 자동화 필요(Playwright) — 스펙 파일 `e2e/specs/frame-description.spec.ts` 존재만 확인, 미실행 | |
| TC-E2E-013 | 프레임 설명 기존값 표시+PUT 반영 | BLOCKED | 브라우저 자동화 필요(Playwright) — 스펙 파일 존재만 확인, 미실행 | |

## H-15 E2E 전체 사용자 시나리오 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-E2E-014 | WORKER 라벨링: 목록→캔버스→BBox 저장 | BLOCKED | 브라우저 자동화 필요 — `e2e/specs/labeling-flow.spec.ts`(44줄) 존재·구조 확인(`test.describe.serial`, 3단계 테스트), 미실행 | |
| TC-E2E-015 | WORKER 라벨링 진입 도구바 렌더 | BLOCKED | 브라우저 자동화 필요 — `e2e/specs/worker-labeling.spec.ts`(36줄) 존재 확인, 미실행 | |
| TC-E2E-016 | 전체 워크플로우: 라벨링→저장→제출→반려→롤백→재제출→승인 | BLOCKED | 브라우저 자동화 필요 — `e2e/specs/labeling-review-full-flow.spec.ts`(210줄) 존재 확인, 미실행 | |
| TC-E2E-017 | WORKER 이력 패널 오픈 후 롤백 | BLOCKED | 브라우저 자동화 필요(동일 spec 파일 내), 미실행 | |
| TC-E2E-018 | REVIEWER 반려 처리 | BLOCKED | 브라우저 자동화 필요(동일 spec 파일 내), 미실행 | |
| TC-E2E-019 | REVIEWER 최종 승인 | BLOCKED | 브라우저 자동화 필요(동일 spec 파일 내), 미실행 | |

## H-16 작업목록 필터·정렬·KPI 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-FE-219 | 진입 기본 정렬 = 등록일 최신순 명시 전송 | PASS | [정적] `features/task/boardSort.ts:38-40` `DEFAULT_BOARD_SORT=[{regDt,desc}]`, `boardParams.ts:163` `toBoardSortParams(sort.length?sort:DEFAULT_BOARD_SORT)` 항상 명시 전송 | |
| TC-FE-220 | 정렬 키는 allowlist 매핑으로만 해석 | PASS | [실동작]+[정적] `boardSort.ts:102-114` `parseBoardSort` 미등록 키 조용히 제거. BE `sort=priority,desc` 직접 호출 → 400(★목록 API 실측) — FE 가 걸러 BE 도달 자체를 차단, BE 도 이중 방어 | |
| TC-FE-221 | 배치 상태 축은 URL 로 못 바꾼다(COMPLETED 고정) | PASS | [정적] `boardParams.ts:67` `BOARD_BATCH_STATUS=BATCH_STATUS_PARAMS.COMPLETED`(고정 상수), `buildBoardParams`가 `status`를 URL 이 아닌 이 상수로만 채움(18-21행 주석 근거 명시) | |
| TC-FE-222 | URL 키 status 는 워크플로 축으로 해석(하위호환) | PASS | [정적] `boardParams.ts:230-239` `searchParamsToFilters`가 `sp.get('status')`를 `workStatus`로 매핑(`asUiWorkStatus`) | |
| TC-FE-223 | allowlist 밖 워크플로 값은 무필터로 폴백 | PASS | [정적] `boardParams.ts:90-93` `asUiWorkStatus` allowlist 밖이면 `''`(필터 미적용) | |
| TC-FE-224 | IN_PROGRESS 는 UI 값이되 서버 미전송 | PASS | [실동작]+[정적] `boardParams.ts:100-106` `asWorkStatusParam`가 `IN_PROGRESS`를 WORK_STATUS_PARAMS 밖으로 처리(undefined). BE 직접 호출 `workStatus=IN_PROGRESS` → 400(★목록 API 실측, "허용되지 않은 workStatus 값") — FE·BE 이중 확인 | |
| TC-FE-225 | REVIEWER 진입 시 IN_PROGRESS 필터 제거 | PASS | [정적] `pages/TaskListPage.tsx:93-102` 초기 state 계산 시 `asWorkStatusParam`/`asAssignmentWorkStatusParam` 로 역할별 selectable 아니면 `workStatus:''`로 정규화 | |
| TC-FE-226 | 정렬 키 3개 상한 + 서버키 중복 제거 | PASS | [실동작]+[정적] `boardSort.ts:35` `MAX_BOARD_SORT_KEYS=3`. BE 직접 호출 4항목 정렬 → 400 "정렬 기준이 너무 많습니다"(★목록 API 실측), `videoId`+`rawSn` 동시 지정(동일 서버키) → 200(에러 없음, dedup 확인) | |
| TC-FE-227 | 헤더 클릭 = 1순위 승격 + desc→asc 토글 | PASS | [정적] `boardSort.ts:54-93` `applyBoardSort`가 클릭 컬럼을 배열 맨 앞에 두고 동일 서버키 기존 항목 제거, `toggleBoardSort`가 desc↔asc 토글 | |
| TC-FE-228 | URL 왕복 후에도 aria-sort 유지(서버키 비교) | PASS | [정적] `boardSort.ts:73-80` `sortDirectionOf` 비교 기준이 컬럼명이 아닌 서버키(`BOARD_SORT_KEY_BY_COLUMN[e.column]`) | |
| TC-FE-229 | 기본 정렬은 URL 에 기록하지 않음 | PASS | [정적] `boardParams.ts:251-265` `filtersToSearchParams`가 `isDefaultSort`면 `sort:[]`로 compactParams 가 키 자체를 제거 | |
| TC-FE-230 | 초기화가 정렬까지 기본값 복원 | PASS | [정적] `pages/TaskListPage.tsx:377-384` `handleFiltersReset`가 필터 초기화와 함께 `setSort([...DEFAULT_BOARD_SORT])` 호출 | |
| TC-FE-231 | KPI 5카드 = 서버 집계 + 클릭 토글 | PASS | [실동작] `GET /v1/tasks/board/summary` → `{total:79,unassigned:27,inProgress:36,reviewPending:1,completed:14,rejected:1}`, 불변식 성립(★목록 API 실측). `TaskBoardKpiCards.tsx` 클릭=필터/재클릭=해제 소스 확인 | |
| TC-FE-232 | KPI '작업중' = PENDING 축 | PASS | [실동작] `workStatus=PENDING` 필터 결과 `totalElements=36` = summary `inProgress:36`과 일치. `workStatus=IN_PROGRESS` 는 BE 400(반환·허용 모두 안 함, ★목록 API 실측) | |
| TC-FE-233 | KPI 집계 요청에서 workStatus 제외 | PASS | [정적] `boardParams.ts:173-182` `buildBoardSummaryParams`에 `workStatus` 필드 자체가 없음. BE `TaskBoardController.java:166-168` summary 메서드도 `workStatus`를 조건 객체에 담지 않음(코드 실측) | |
| TC-FE-234 | KPI 로딩/실패/정상 3상태 구분 | PASS | [정적] `features/task/components/TaskBoardKpiCards.tsx:39,59-63` `data-testid="kpi-loading"`/`"kpi-error"` + `role="status"` + "집계 정보를 불러오지 못했습니다. 목록은 정상 표시됩니다." 문구 실재 | |
| TC-FE-235 | REVIEWER 시각 클라이언트 재필터 금지 | PASS | [정적] `pages/TaskListPage.tsx:327` `pagedRows = allRows`(추가 필터 없음), `totalElements`는 `listPage?.totalElements`(서버값) 사용(334행) | |
| TC-FE-236 | 체크박스 선택이 페이지 전환 후 잔존하지 않음 | PASS | [정적] `pages/TaskListPage.tsx:356-363` `pagedVideoIds` 변경 시 `selectedVideoIds`에서 화면에 없는 선택 제거하는 effect 실재 | |
| TC-FE-237 | 필터·KPI·정렬 변경 시 page 0 + 선택 해제 | PASS | [정적] `pages/TaskListPage.tsx:368-403` `handleFiltersChange`/`handleKpiSelect`/`handleSort` 모두 같은 핸들러 안에서 `setPage(0)` + `clearSelection()` 동시 처리 | |
| TC-FE-238 | 총 페이지 축소 시 범위 복귀 | PASS | [정적] `pages/TaskListPage.tsx:344-348` `page > totalPages-1` 이면 `setPage(Math.max(0,totalPages-1))` | |
| TC-FE-239 | 이벤트유형 옵션 서버 조회 + truncated 안내 | PASS | [실동작]+[정적] `GET /v1/tasks/board/event-types` → `{items:[...],truncated:false}` 객체 확인(★목록 API 실측). `useTaskBoardEventTypes.ts:19-36` 훅이 `items`/`truncated` 분해, 실패 시 `EMPTY_OPTIONS` 폴백 | |
| TC-FE-240 | 목록 조회 실패 시 배정 액션 잠금 | PASS | [정적] `pages/TaskListPage.tsx:526-538` `hasListError` 시 `ErrorState` + 역할별 다른 안내 문구(REVIEWER="배정 기능은 새로고침 후" / 그 외="새로고침 후 다시 확인", WORKER 에 "배정 기능" 문구 미노출) | |
| TC-FE-241 | 검색어 100자 상한(400 왕복 방지) | PASS | [실동작]+[정적] `boardParams.ts:61,131-134` `MAX_SEARCH_KEYWORD_LENGTH=100` + `asKeyword`가 slice(100). BE 직접 호출 101자 → 400 "검색어는 100자 이하여야 합니다"(★목록 API 실측) — FE 사전 차단 + BE 재검증 이중 확인 | |
| TC-FE-242 | WORKER 시각은 클라이언트 필터 + 4카드 유지 | **PARTIAL** | [실동작]+[정적] `boardParams.ts:206-218` 주석은 "필터는 **서버**가 전체 배정 기준으로 적용한다(화면 재필터 금지)"라 카탈로그 서술("`/v1/assignments`가 필터·정렬을 지원하지 않아 화면에서 거른다")과 **반대**. BE `AssignmentController.java` `GET /v1/assignments`에 `q`/`workStatus`/`eventTypeCd` 파라미터 실재, 실호출 `q=강남` → totalElements 50→22로 서버측 필터 동작 확인. `TaskListPage.tsx`에도 WORKER 행에 대한 추가 클라이언트 필터 로직 없음(`pagedRows=allRows` 그대로). **카탈로그의 필터링 매커니즘 서술이 현재 구현과 불일치**(기능 자체는 정상 — 오히려 서버 필터로 개선된 상태). 4카드(`TaskWorkerKpiCards`)는 표시 전용(클릭 필터 없음)이라는 부분은 일치 | H-ISSUE-88 참조(LOW, 카탈로그 정정 필요) |

## H-17 검수목록 진입 기본값·필터·정렬·KPI 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-FE-243 | 진입 기본값 = 검수요청 + 제출일 오래된순(FIFO) | PASS | [실동작]+[정적] `reviewListParams.ts:80-95` `DEFAULT_REVIEW_SORT={submittedAt,asc}`, `DEFAULT_REVIEW_FILTERS.status='REVIEW_PENDING'`. `api.ts:40-45` `REVIEW_STATUS_TO_BE.REVIEW_PENDING='PENDING'`. 실호출 `status=PENDING&sort=submittedAt,asc` → 오름차순 확인(★목록 API 실측). BE 기본(무파라미터)은 최신순(내림차순) 확인 — FE 명시 전송의 필요성 실증 | |
| TC-FE-244 | 진입 기본값을 URL 에도 기록 | PASS | [정적] `pages/ReviewListPage.tsx:112-124` 진입 시 `toReviewSearchParams` 로 canonical 계산 후 `isSameSearch` 다르면 `setSearchParams(canonical,{replace:true})` | |
| TC-FE-245 | URL 정규화는 값까지 교정하며 멱등 | PASS | [정적] `pages/ReviewListPage.tsx:112-124` 값까지 비교(`isSameSearch`가 키/값 모두 비교), 정규값 재정규화 시 `isSameSearch`가 true → no-op(루프 없음) | |
| TC-FE-246 | "전체" 는 키 삭제가 아니라 status=ALL | PASS | [정적] `reviewListParams.ts:37,215` `REVIEW_STATUS_ALL='ALL'`, `toReviewSearchParams`가 `filters.status===''`이면 `REVIEW_STATUS_ALL`로 기록(키 삭제 아님) | |
| TC-FE-247 | FE→BE 상태 역매핑 Record 강제 | PASS | [정적] `api.ts:40-45` `REVIEW_STATUS_TO_BE: Record<ReviewStatus,ReviewStatusParam>` — REVIEW_PENDING→PENDING/REVIEWING→IN_REVIEW/COMPLETED→APPROVED/REJECTED→REJECTED 정확히 일치. `api.statusMapping.test.ts` 존재(baseline GREEN) | |
| TC-FE-248 | 매핑에 없는 status 는 필터 생략 | PASS | [정적] `api.ts:55-64` `listReviews`가 `status ? REVIEW_STATUS_TO_BE[status] : undefined` — 매핑 없으면 undefined(필터 생략, 빈결과 위장 방지) | |
| TC-FE-249 | 미등록 정렬 키는 기본 정렬로 정규화 | PASS | [정적] `reviewListParams.ts:122-126` `parseReviewSort`가 `isReviewSortColumn` 밖이면 `{...DEFAULT_REVIEW_SORT}` 반환 | |
| TC-FE-250 | 상태 컬럼은 정렬 대상에서 제외 | PASS | [정적] `pages/ReviewListPage.tsx:280-289` `status` 컬럼 정의에 `sortable` 미부여(주석으로 FIFO 뒤집힘 사유 명시) | |
| TC-FE-251 | 제출일만 정렬 가능 | PASS | [정적] `pages/ReviewListPage.tsx:268-273` `submittedAt` 컬럼만 `sortable:true` | |
| TC-FE-252 | 검색 300ms debounce + 즉시 적용/취소 | PASS | [정적] `features/review/components/ReviewListFilters.tsx:16,81-92` `SEARCH_DEBOUNCE_MS=300`, `handleSubmit`(조회 버튼)이 `cancelPendingSearch()` 즉시 적용, `handleReset`도 명시적으로 `cancelPendingSearch()` 호출(주석에 되살아남 방지 사유 명시) | |
| TC-FE-253 | KPI 4카드 서버 집계 + summary 에 status 미전송 | PASS | [정적] `reviewListParams.ts:168-174` `buildReviewSummaryParams`가 `q`만 반환(타입 `ReviewSummaryParams`가 status 자체를 허용 안 함) | |
| TC-FE-254 | summary 실패가 목록을 막지 않음 | PASS | [정적] `pages/ReviewListPage.tsx:161-165,340` 카드 영역만 `summaryIsError`, `error`(목록)는 별도 상태. `useReviewSummary(..., {enabled:isReviewer})` 비REVIEWER 403 스팸 차단 | |
| TC-FE-255 | 목록 실패와 0건을 구분해 말한다 | PASS | [정적] `pages/ReviewListPage.tsx:340-345` `error`면 `ErrorState`만 렌더(표·"총 0건" 미표시), else 분기에서만 DataTable 렌더 | |
| TC-FE-256 | 재조회 중 "이전 조건 결과" 고지 | PASS | [정적] `pages/ReviewListPage.tsx:141,349-358` `isRefreshing=isFetching&&!isLoading`, `aria-busy` + `role="status"` "갱신 중… (아래 목록은 이전 조건의 결과입니다)" | |
| TC-FE-257 | page 범위 초과 복귀(응답 전 미판단) | PASS | [정적] `pages/ReviewListPage.tsx:148-154` `if(totalPages===undefined) return;`(응답 없으면 판단 안 함) 이후 `page>lastPage`면 복귀 | |
| TC-FE-258 | 초기화 = 진입 기본값 복귀 + 이미 기본이면 비활성 | PASS | [정적] `pages/ReviewListPage.tsx:188-216` `isDefaultView`가 필터+정렬 모두 비교, `handleReset`이 `DEFAULT_REVIEW_FILTERS`+`DEFAULT_REVIEW_SORT`로 복귀 | |
| TC-FE-259 | 행 액션 접근성 이름 = 표시 문구 | PASS | [정적] `pages/ReviewListPage.tsx:225-229,293-304` `aria-label={actionLabel(r.status)+' '+r.cctvName}`(보이는 문구와 동일), 장식 `▶`는 `aria-hidden` | |
| TC-FE-260 | 빈 목록 문구가 현재 상태 필터를 반영 | PASS | [정적] `pages/ReviewListPage.tsx:368-372` `emptyMessage` 분기(status별) + `ReviewListFilters.tsx:172-180` 활성 필터 배지 "{라벨} 상태만 표시 중" 실재 | |

## 근거 드리프트

| TC-ID | 카탈로그 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-A11Y-006 | `pages/label/LabelingPage.tsx:1079-1090` | `pages/label/LabelingPage.tsx:1095-1099` | 라인 오프셋 약 15줄 어긋남(내용은 일치) |
| TC-A11Y-001 | `pages/label/LabelingPage.tsx:1205-1310` | `pages/label/LabelingPage.tsx:1229-1310` | 시작 라인 약 24줄 어긋남(내용은 일치) |

## 브라우저 자동화 필요 케이스 목록 (BLOCKED 사유별)

### 사유: Playwright 브라우저 자동화 필요(본 세션에 브라우저 도구 미제공)

- TC-E2E-012, TC-E2E-013 (H-14) — `e2e/specs/frame-description.spec.ts` 존재·구조만 정적 확인
- TC-E2E-014, TC-E2E-015, TC-E2E-016, TC-E2E-017, TC-E2E-018, TC-E2E-019 (H-15) — `e2e/specs/{labeling-flow,worker-labeling,labeling-review-full-flow}.spec.ts` 존재·구조만 정적 확인. 이 spec 들은 `_raw/test-baseline.md`(vitest/gradle/pytest) 범위 밖(Playwright 는 별도 실행 체계)이라 baseline GREEN 도 이 케이스들의 실행 근거가 되지 못함 — 다음 회차에 Playwright 실행 가능한 환경(브라우저 MCP 등)에서 재검증 필요

## 이슈 상세

### [H-ISSUE-88] TC-FE-242 — 카탈로그가 서술하는 WORKER 목록 필터링 매커니즘이 현재 구현과 불일치(클라이언트 필터 → 서버 필터로 개선됨, 문서 미갱신)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그(H-frontend-e2e.md TC-FE-242)는 "`/v1/assignments` 가 필터·정렬을 지원하지 않아 화면에서 거른다"(WORKER 시각 클라이언트 재필터)로 기술한다. 카탈로그가 실제 구현을 정확히 서술해야 향후 회귀 판정·유지보수 시 잘못된 전제로 판단하지 않는다.
- **현재 동작(이슈 내용)**: 실제로는 BE `GET /v1/assignments` 가 `q`/`workStatus`/`eventTypeCd` 서버 필터를 지원하며(`backend/src/main/java/kr/co/cudo/authoring/assignment/controller/AssignmentController.java:112-137` `@RequestParam` 3종 실재), FE `frontend/src/features/task/boardParams.ts:197-205` 의 `buildAssignmentParams` 주석도 "필터는 **서버**가 전체 배정 기준으로 적용한다(화면 재필터 금지)"로 명시하며, `pages/TaskListPage.tsx:327` `pagedRows = allRows`(WORKER 행에 대한 추가 클라이언트 필터 로직 없음)를 그대로 사용한다. 실 호출로 확인:
  ```
  GET /v1/assignments?page=0&size=3 (WORKER 토큰)                 → 200, totalElements=50
  GET /v1/assignments?q=%EA%B0%95%EB%82%A8&page=0&size=3          → 200, totalElements=22
  ```
  q=강남 필터가 BE 단에서 즉시 반영돼(50→22) 서버 필터가 실제로 동작함을 확인.
- **재현/확인 경로**:
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H "Content-Type: application/json" -d '{"role":"WORKER","channel":"INTERNAL"}')
  curl -s -H "Authorization: Bearer <token>" "http://localhost:18081/api/v1/assignments?page=0&size=3"
  curl -s -G -H "Authorization: Bearer <token>" --data-urlencode "q=강남" "http://localhost:18081/api/v1/assignments"
  ```
- **영향**: 기능적 영향 없음(오히려 서버 필터로 확장 페이지에서도 정확한 결과를 주는 개선). 다만 카탈로그가 "지원하지 않는다"고 잘못 서술하면 이후 검증자·개발자가 실제로는 존재하는 서버 필터 기능을 인지하지 못하고 중복 구현하거나, 반대로 실제 서버 계약이 바뀐 걸 놓칠 위험(회귀 감지 오탐/누락).
- **수정 방향(제안)**: `docs/test-cases/H-frontend-e2e.md` TC-FE-242 기대결과를 "WORKER 시각도 서버 필터(`/v1/assignments` 의 q/workStatus/eventTypeCd)로 위임하며 클라이언트 재필터는 없다. KPI 4카드는 표시 전용(클릭 필터 없음)"으로 정정. ⚠ 본 세션은 카탈로그 수정 권한 밖(검증 전용) — 실제 정정은 사용자 지시로 별도 진행.
