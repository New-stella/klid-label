# 배치 C — 작업·검수 축 (SCREEN-012/018/019)

## 요약
- 발견: ERR 7 / STALE 3 / GAP 8 / CONFLICT 1 (총 19건)
- SCREEN-012(작업 목록, v11, stale=false): 실사용 정책(정렬·필터 축 분리, 이벤트유형 그룹핑, strict/lenient 비대칭)은 CLAUDE.md·백엔드와 **정확히 일치**했다. 결함은 세부 UX 메커니즘(조회=draft/apply, 컬럼 누락, 라우트 오타) 수준.
- SCREEN-018(검수 목록, v6, stale=true): 2026-07-30 정정으로 핵심 정책(서버 집계·정렬 비대칭)은 정확. 헤더 섹션 누락 + 컬럼 정렬 방향 오기술만 남음.
- SCREEN-019(검수 상세, v5, stale=true, **최종 수정 2026-06-19**): 가장 심각. 우측 패널이 3탭(객체/메타/이슈) 구조로 전면 재설계됐고, **라벨 0건 승인 경로**(2026-08-06 추가, `fe68b49`)가 통째로 누락됐다. "다크 헤더"는 폐지된 정책 잔존. 이 화면정의서는 **현재 배포된 화면의 사양서로 쓸 수 없다** — 부분 정정이 아니라 섹션 2/3/4/5/6 재작성이 필요하다.

## 액션 배선 판별표 (SCREEN-012)

| 컴포넌트 | 서술된 동작 | API 필요? | 판정 |
|---|---|---|---|
| 새로고침 (헤더) | refetch + assignments 쿼리 무효화 | 아니오 — 신규 API 아닌 기존 쿼리 재실행 | 순수 UI, 문서화 적정(note 有) |
| 조회 (필터 제출) | draft 필터를 적용해 서버 재조회 유발 | 간접 — Table(API-072/073)의 재호출 트리거 | 순수 UI 액션이나 draft/apply 메커니즘 자체가 미서술 → **[GAP-C01]** |
| 초기화 (필터) | DEFAULT_TASK_FILTERS 복원 | 아니오 | 순수 UI, 문서화 적정(note 有) |
| 선택 해제 | setSelectedVideoIds(new Set()) | 아니오 | 순수 UI, 문서화 적정(note 有) |
| N개 일괄 배정 | AssignModal bulk 모드 오픈 | 아니오 — 모달 오픈만 | 순수 UI, 문서화 적정(note 有) |
| 배정 | AssignModal assign 모드 오픈 | 아니오 | 순수 UI, 문서화 적정(note 有) |
| 재배정 | AssignModal reassign 모드 오픈 | 아니오 | 순수 UI, 문서화 적정(note 有) |
| 작업 | navigate(/label/{srcSn}) | 아니오 — 네비게이션 | 순수 UI이나 **경로 자체가 오기재** → **[ERR-C01]** |
| 마킹 | navigate(/marking/{videoId}) | 아니오 | 순수 UI, 실제 코드와 일치 |
| 이력 | HistoryDrawer 오픈 | 아니오 — 모달을 여는 행위(내부에서 Timeline이 API-072 별도 트리거) | 순수 UI, 문서화 적정(note 有) |
| 취소 (모달) | 모달 닫기 | 아니오 | 순수 UI, 자명 |
| 저장 / N건 일괄 배정 (모달) | assign=POST /assignments, reassign=PATCH /assignments/{id} | 예 — assign/bulk=API-070, **reassign=API-071** | note 는 API-071을 서술하나 `triggers_api` 필드는 API-070만 표기 → **[CONFLICT-C01]** |
| 닫기 (Drawer) | Drawer 닫기 | 아니오 | 순수 UI, 자명 |

결론: 12/13 액션이 `triggers_api` 미표기이나, 그중 **11개는 순수 UI 액션(모달 열기·닫기·네비게이션·로컬 상태 변경)이라 API 배선이 필요 없고 실제로 note 에 정확히 서술돼 있다.** 진짜 문제는 "미배선"이 아니라 ① 저장 버튼의 `triggers_api` 필드가 두 API(070/071) 중 하나만 표기(CONFLICT-C01), ② "조회" 버튼의 draft/apply 메커니즘 자체가 어디에도 서술되지 않은 것(GAP-C01)이다.

---

## SCREEN-012

### [ERR-C01] "작업" 버튼 네비게이션 경로가 실제 라우트와 다름
- **위치**: sections[4] "작업 목록 테이블 + 페이지네이션" > components[4] "작업"
- **정의서 서술**: `note`: "WORKER firstSrcSn 있음 — navigate(/label/{srcSn})"
- **실제**: 라우트는 `/labeling/:srcSn` 이고, 컬럼 클릭 핸들러도 `navigate(`/labeling/${row.firstSrcSn}`)` 를 호출한다. `/label/{srcSn}` 경로는 라우터에 존재하지 않는다.
- **근거**: `routes/index.tsx` (라우트 테이블 `{ path: "/labeling/:srcSn", element: ... }`) · `TaskListPanel.tsx`(`onWork: (row) => navigate(`/labeling/${row.firstSrcSn}`)`)
- **조치 제안**: note 문구를 `navigate(/labeling/{srcSn})` 로 정정.
- **확신도**: high

### [ERR-C02] "현재 역할" 배지 컴포넌트가 실제로 존재하지 않음
- **위치**: sections[0] "헤더" > components[1] "현재 역할: 검수자/작업자" (Badge)
- **정의서 서술**: "좌측 '작업 목록' 제목 + 현재 역할 칩(검수자/작업자, claims.role 기반) + '처리 완료된 영상만 표시' 부제"
- **실제**: 헤더는 `PageTitle`(title="작업 목록" + 역할별 분기된 `description` 문자열)과 `RefreshButton` 만 렌더한다. "현재 역할" 배지 컴포넌트는 코드베이스 전체에서 검색되지 않는다(`grep "현재 역할"` 0건). 이 배지는 테스트베드(`upload-ui/frontend/src/pages/TaskListPage.tsx:505`)에만 존재한다.
- **근거**: `TaskListPage.tsx`(`TaskListPage`) — 납품 FE
- **조치 제안**: Badge 컴포넌트 서술을 제거하거나, 역할별 `description` 문자열 분기로 대체 서술.
- **확신도**: high

### [GAP-C01] "조회" 버튼의 draft/apply 필터 메커니즘이 어디에도 서술되지 않음
- **위치**: sections[1] "검색·필터 폼" > components[0~4] (Input/Select×3/Button "조회")
- **정의서 서술**: 각 필터 컴포넌트 note 는 필터 값이 즉시 서버로 위임되는 것처럼 서술("서버(GET /v1/tasks/board)로 위임한다", "서버 필터(eventTypeCd 위임)")할 뿐, "조회" 버튼을 눌러야 적용된다는 draft 상태 개념이 전혀 없다.
- **실제**: `TaskFilters` 는 4개 입력 모두 로컬 `draft` state 를 거치고, `FilterBar onSubmit={() => onApply(draft)}` — 즉 **"조회" 버튼(또는 Enter 제출)을 눌러야만** draft 가 적용값으로 커밋되어 서버 재조회가 일어난다. 입력마다 즉시 재조회되지 않는다. KPI 카드 클릭만 draft 를 우회해 즉시 적용된다(그 순간 `key` 로 TaskFilters 를 리셋).
- **근거**: `TaskFilters.tsx`(TaskFilters 컴포넌트 상단 주석 "네 입력 모두 draft 를 거쳐 '검색' 제출 시 함께 적용한다")
- **조치 제안**: 필터 섹션 description 에 "4개 입력은 로컬 draft 상태이며 '조회' 클릭 시에만 일괄 적용된다(입력마다 재조회하지 않음). KPI 카드 클릭은 draft 를 우회해 즉시 적용된다" 를 명시.
- **확신도**: high

### [GAP-C02] 촬영일시(capturedAt) 컬럼이 컬럼 목록에서 누락
- **위치**: sections[4] "작업 목록 테이블 + 페이지네이션" description
- **정의서 서술**: "컬럼: (REVIEWER)선택 체크박스, 영상명+video-ID, 이벤트(EventTypeBadge), 상태(StatusBadge), 작업자, 검수자, 액션."
- **실제**: REVIEWER 시각에는 **촬영일시(capturedAt) 컬럼**이 존재하며, 헤더 클릭으로 정렬 방향 토글이 가능한 서버 정렬 컬럼이다(WORKER 배정 목록엔 값이 없어 컬럼째 미노출).
- **근거**: `columns/index.tsx`(`createTaskColumns` — `capturedAt` accessorKey, `onToggleCapturedSort` 핸들러)
- **조치 제안**: 컬럼 목록에 "(REVIEWER) 촬영일시 — 서버 정렬 가능, WORKER 시각엔 컬럼 자체 미노출" 추가.
- **확신도**: high

### [GAP-C03] 증강·해상도 파생 뱃지(AugTypeBadge)가 서술되지 않음
- **위치**: sections[4] > components[1] "Table"
- **정의서 서술**: 이벤트 컬럼은 EventTypeBadge만 언급.
- **실제**: 이벤트 컬럼 아래에 `augmented===true` 인 행에 한해 `AugTypeBadge`(WINTER/NIGHT/RAIN 또는 RESL_*)가 추가로 렌더된다.
- **근거**: `columns/index.tsx`(eventName accessorKey cell — `{row.original.augmented && <AugTypeBadge .../>}`)
- **조치 제안**: 이벤트 컬럼 서술에 "파생 영상(증강·해상도)은 이벤트 뱃지 아래 AugTypeBadge 를 추가 표시(원본은 미표시)" 추가.
- **확신도**: high

### [GAP-C04] 일괄 선택 체크박스가 "미배정 행"에만 활성화되는 제약이 미서술
- **위치**: sections[4] > components[0] "현재 페이지 전체 선택" / sections[3] "일괄 배정 액션바"
- **정의서 서술**: "REVIEWER 가 테이블에서 영상 1건 이상 선택하면" — 선택 가능 행 범위에 대한 제약 없음.
- **실제**: `DataTable enableRowSelection={isReviewer && ((row) => row.original.workerId == null)}` — **작업자가 이미 배정된 행은 체크박스 자체가 비활성**이다. 배정 완료 영상은 일괄 배정 대상에서 애초에 선택 불가.
- **근거**: `TaskListPanel.tsx`(`enableRowSelection` prop)
- **조치 제안**: 체크박스 note 에 "workerId 존재(이미 배정된) 행은 선택 불가 — 일괄 배정은 미배정 행 전용" 추가.
- **확신도**: high

### [GAP-C05] 이벤트유형 옵션 절단 안내 문구 누락
- **위치**: sections[1] > components[1] "이벤트: 전체/동적 이벤트 유형"
- **정의서 서술**: 절단(truncated) 판정 로직은 상세 서술되어 있으나, 절단 시 사용자에게 노출되는 안내 UI 요소(문구)가 컴포넌트 목록에 없음.
- **실제**: 이벤트유형 옵션이 서버 상한으로 잘렸을 때 `role="status"` 안내문 "이벤트유형이 많아 일부만 표시됩니다." 가 필터 바로 아래 렌더된다.
- **근거**: `TaskFilters.tsx`(`isEventTypesTruncated` 조건부 렌더 블록)
- **조치 제안**: sections[1] components 에 안내 텍스트(Custom/Text, role=status) 컴포넌트 추가.
- **확신도**: high

### [CONFLICT-C01] 저장 버튼의 `triggers_api` 필드가 note 서술과 모순
- **위치**: sections[5] "작업 배정 모달" > components[4] "저장 / N건 일괄 배정"
- **정의서 서술**: note: "assign=POST /assignments(API-070), reassign=PATCH /assignments/{id}(API-071)" — 두 API를 명시. 그러나 `triggers_api` 필드값은 `"API-070"` 하나뿐.
- **실제**: 재배정 시 `useTaskAssignForm` 은 PATCH `/assignments/{id}` (API-071)를 호출한다 — note 서술이 실제 구현과 일치하고, `triggers_api` 필드가 그 사실을 절반만 반영해 필드 간 자기모순이 생겼다.
- **근거**: SCREEN-012.json(components[5][4].note vs .triggers_api 필드 자체 비교)
- **조치 제안**: `triggers_api` 를 배열/문자열로 `["API-070","API-071"]` 또는 모드별 필드로 분리 표기(스키마가 단일 문자열만 허용한다면 최소한 대표값 대신 "모드별 상이 — note 참조" 로 표기).
- **확신도**: high

---

## SCREEN-018

### [GAP-C06] 헤더(제목 + 새로고침) 섹션 누락
- **위치**: sections 전체(3개: KPI/필터/테이블) — 헤더 섹션 없음
- **정의서 서술**: 없음 (SCREEN-012는 헤더 섹션을 별도로 문서화하는데 SCREEN-018엔 대응 섹션이 없음)
- **실제**: `ReviewPage`는 `PageTitle`(title="검수 목록", description="작업자가 제출한 라벨링 결과를 검수합니다.") + `RefreshButton`(queryKey=reviewQueries.all())을 렌더한다.
- **근거**: `ReviewPage.tsx`(ReviewPage 컴포넌트)
- **조치 제안**: SCREEN-012 sections[0]과 동형의 헤더 섹션(제목+새로고침) 신설.
- **확신도**: high

### [ERR-C03] "라벨 수" 컬럼 정렬 방향이 실제와 반대로 서술
- **위치**: sections[2] "검수 목록 테이블" description
- **정의서 서술**: "라벨 수(우정렬)"
- **실제**: 헤더·셀 모두 `text-left` 클래스로 좌측 정렬되어 있다.
- **근거**: `columns/index.tsx`(labelCount accessorKey — header `<div className="text-left">`, cell `<div className="text-left tabular-nums">`) — 납품 FE `components/review/columns`
- **조치 제안**: "라벨 수(좌정렬)" 로 정정, 또는 코드를 우정렬로 바꿔 정합.
- **확신도**: high

### [GAP-C07] 행 액션 라벨의 화살표(▶) 표기가 실제 텍스트와 다름(경미)
- **위치**: sections[2] > components[3] "검수시작 ▶ / 이어서 검수 / 결과보기 ▶"
- **정의서 서술**: 화살표 문자 포함.
- **실제**: 버튼 텍스트는 "검수 시작"/"이어서 검수"/"결과 보기"이며 화살표 문자 없이 아이콘(Play/ChevronRight)으로 방향을 표현한다. 또한 `ACTION_META`에 문서에 없는 `ASSIGNED` 상태 키도 "결과 보기"로 매핑돼 있다(도달 가능성 미확인).
- **근거**: `columns/index.tsx`(ACTION_META)
- **조치 제안**: 라벨 문구를 실제 텍스트로 정정, ASSIGNED 매핑 포함 여부는 BE `ReviewResponse.status` 실측값과 대조 필요(미확인).
- **확신도**: medium (라벨 문구는 high, ASSIGNED 도달성은 low — 미확인)

---

## SCREEN-019

### [GAP-C08] ★ 라벨 0건 영상 승인 경로(negative sample 승인)가 전혀 서술되지 않음 — 최중대
- **위치**: sections[6] "검수 액션 바 (승인·반려)"
- **정의서 서술**: "승인 클릭 → ConfirmDialog('승인 확정', 작업 COMPLETED 전이) → approveReview → 토스트 후 /review 복귀." — 이게 전부이며 라벨 0건 분기 언급 없음.
- **실제**: BE `POST /v1/reviews/{videoId}/approve` 는 라벨 0건 영상을 확인 없이 승인하면 **409(REVIEW_NO_LABEL)** 로 거부한다. FE는 이 409를 별도 토스트가 아니라 `AlertDialog`("라벨이 없는 영상입니다" — "객체가 실제로 없는 정상 영상이면 그대로 승인할 수 있습니다. … 확인 승인 시 '라벨 없음 확인' 사실이 작업 이력에 기록됩니다")로 받아 `approveWithoutLabels()`(`noLabelConfirmed:true` 재요청)로 재승인시킨다. 라벨이 있는 영상에 `noLabelConfirmed=true`를 보내면 400.
- **근거**: `ReviewController.java`(approve — `@ApiResponses` 400/409 설명, `ApproveRequest`) · `useReviewActions.ts`(approveMutation onError — `error.code === "REVIEW_NO_LABEL"`, `approveWithoutLabels`) · `ReviewWorkspace.tsx`(`AlertDialog open={actions.isNoLabelBlocked}`) — 납품 FE 커밋 `fe68b49 feat(review): 라벨 0건 영상 승인 경로 열기`(2026-08-06, SCREEN-019 최종 수정일 2026-06-19보다 이후)
- **조치 제안**: sections[6]에 노드 추가 — "승인이 409(REVIEW_NO_LABEL)로 거부되면(라벨 0건 영상) '라벨이 없는 영상입니다' 확인 다이얼로그를 띄우고, 검수자가 명시 확인하면 noLabelConfirmed=true 로 재승인 요청한다. 라벨이 있는 영상에 noLabelConfirmed=true를 보내면 400."
- **확신도**: high

### [GAP-C09] ★ 메타 탭(이벤트 어노테이션 + VLM 시계열 메타 검토)이 통째로 누락 — 최중대
- **위치**: sections[3] "객체 속성 패널" 부근 (대응 섹션 없음)
- **정의서 서술**: 없음 — 이벤트 어노테이션·VLM 시계열 메타 검토에 대한 어떤 섹션도 존재하지 않음.
- **실제**: 우측 패널은 `Tabs`(객체/메타/이슈) 구조이며, "메타" 탭은 `EventAnnotationReviewPanel`(이벤트 어노테이션) + `TimeseriesMetaReviewPanel`(외부 VLM 시계열 메타, srcSn 단위, 검수자는 확인만)을 렌더한다. CLAUDE.md의 "★외부 VLM 위탁은 verify다"·"metaKey 규격" 절이 정의하는 `vlm.description`/`vlm.accuracy` 검토 기능이 검수 화면에 실재하는데도 이 화면정의서엔 아예 없다.
- **근거**: `ReviewSidePanel.tsx`(TabsContent value="meta" — `EventAnnotationReviewPanel`, `TimeseriesMetaReviewPanel`)
- **조치 제안**: 신규 섹션 "검수 우측 패널 — 메타 탭" 추가, 참조 API는 이벤트 어노테이션 조회/시계열 메타 조회 엔드포인트로 연결.
- **확신도**: high

### [ERR-C04] 우측 패널 구조(객체목록/객체속성/이슈) 전체가 3탭 구조와 불일치
- **위치**: sections[2] "객체 목록", sections[3] "객체 속성 패널", sections[4] "검수 메모 패널" (3개 섹션)
- **정의서 서술**: 세 섹션이 독립된 aside 블록으로 항상 동시 노출되는 것처럼 서술.
- **실제**: 셋은 `Tabs`(객체/메타/이슈) 중 **"객체" 탭 하나**에 몰려 있다 — 객체 목록(`ObjectClassTree`) + 선택 객체 속성(`SelectedAttributes`, 객체 선택 시에만) + 검수 메모(`ReviewMemoPanel`, **객체 선택 시에만** children 으로 렌더). "이슈"는 별도 탭(`IssueThreadPanel`)이며 검수 메모와 무관한 별개 기능이다.
- **근거**: `ReviewSidePanel.tsx`(TabsList 3종 + TabsContent value="objects" 내부 구조)
- **조치 제안**: sections[2]~[4]를 "객체 탭" 하위 서브섹션으로 재구성하고, "메타"·"이슈" 탭을 별도 최상위 섹션으로 승격.
- **확신도**: high

### [ERR-C05] "검수 메모 패널"의 이슈 목록 서술이 실제 구조와 다름
- **위치**: sections[4] "검수 메모 패널 (이슈·의견)" > components[1] "이슈 목록(BE+pending)"
- **정의서 서술**: "이슈 목록 = BE 등록 이슈(useReviewIssues) + 로컬 pending 이슈(미저장 카드, textarea 수정·삭제 가능)"
- **실제**: `useReviewIssues` 훅은 코드베이스에 존재하지 않는다(검색 0건). 실제로는 ①완전히 로컬(BE 미연결)인 프레임/객체별 메모 리스트(`useReviewMemos`, `ReviewMemoPanel`)와 ②별개 탭의 BE 연동 문의 스레드(`useIssueThread`, `IssueThreadPanel` — 등록/댓글/해결 뮤테이션 보유)로 완전히 분리되어 있다. 문서가 서술하는 "BE 이슈 + 로컬 pending 이 한 목록에 섞인" 구조는 존재하지 않는다.
- **근거**: `ReviewMemoPanel.tsx`(파일 전체 — 로컬 draft만 다룸) · `useIssueThread.ts`(createInquiry/addComment/resolveThread — 별개 BE 연동 스레드) · `ReviewSidePanel.tsx`(두 기능이 "객체" 탭 vs "이슈" 탭으로 분리)
- **조치 제안**: 두 기능을 별개 섹션으로 분리 서술 — "검수 메모(로컬, BE 미연결, 반려 사유 초안 합성용)" / "이슈 스레드(BE 연동, 문의 등록·댓글·해결)".
- **확신도**: high

### [ERR-C06] "검수 의견 textarea(0/200)"가 실제 메모 구조와 다름
- **위치**: sections[4] > components[3] "검수 의견" (Textarea)
- **정의서 서술**: "검수 의견 textarea(0/200 카운터, store.reviewComment)" — 단일 텍스트영역, 200자 상한.
- **실제**: 단일 textarea 가 아니라 **프레임/선택객체별 메모를 누적하는 리스트**(각 메모 최대 1000자, `MEMO_MAX=1000`)다. 메모는 추가 시점의 현재 프레임 번호 + 선택 객체 태그가 자동 태깅되며, `store.reviewComment` 같은 단일 상태가 아니라 `ReviewMemo[]` 배열이다.
- **근거**: `ReviewMemoPanel.tsx`(MEMO_MAX=1000, memos 배열 렌더) · `useReviewMemos`(반환 `items`)
- **조치 제안**: "검수 의견" 단일 필드 서술을 "프레임/객체별 메모 리스트(각 최대 1000자, 로컬 전용)"로 교체.
- **확신도**: high

### [STALE-C01] "다크 헤더" 서술 — 다크 테마 폐지 정책과 불일치
- **위치**: sections[0] "검수 헤더" description
- **정의서 서술**: "전체 화면 상단 다크 헤더."
- **실제**: `<header className="... border-b border-neutral-200 bg-white px-4">` — 라이트 배경. 2026-08-06 KRDS 정합 커밋으로 다크 테마가 프로젝트 전역에서 폐지됐다(CLAUDE.md 미기재이나 커밋 `3349bd7e refactor(ui): KRDS 정합 — 공통 컴포넌트 적용·다크 폐지·도구바 결함 수정` 확인). SCREEN-019 최종 수정일(2026-06-19)이 이 정책보다 앞선다.
- **근거**: `ReviewDetailHeader.tsx`(header className `bg-white`) — 납품 FE
- **조치 제안**: "다크 헤더" → "라이트 헤더(border-b, bg-white)"로 정정.
- **확신도**: high

### [STALE-C02] "이슈 추가 모드" 토글(Switch)이 코드에서 명시적으로 제거됨
- **위치**: sections[4] > components[0] "이슈 추가 모드 토글" (Switch, aria-pressed, store.issueMode)
- **정의서 서술**: 별도 모드 토글 스위치 존재.
- **실제**: `ReviewMemoPanel` 코드 주석: "참고 프로젝트의 '이슈 추가 모드' 토글을 제거하고, 추가 시점의 프레임/선택 객체를 자동으로 태깅한다." — 즉 과거(테스트베드 계열) 존재하던 이 토글이 납품 FE에서 의도적으로 삭제됐고 대신 항상-태깅 방식으로 교체됐다.
- **근거**: `ReviewMemoPanel.tsx`(파일 상단 주석)
- **조치 제안**: Switch 컴포넌트 서술 삭제.
- **확신도**: high

### [STALE-C03] "첨부파일(준비 중)" placeholder가 코드에 존재하지 않음
- **위치**: sections[4] > components[2] "첨부파일 (준비 중)" (Custom, state=disabled)
- **정의서 서술**: 비활성 버튼으로 존재.
- **실제**: `ReviewMemoPanel.tsx` 전체를 검토했으나 첨부파일 관련 UI 요소가 없다(Input/Textarea/List/버튼 2개만 존재 — 추가입력·삭제).
- **근거**: `ReviewMemoPanel.tsx`(컴포넌트 전체 구조)
- **조치 제안**: 컴포넌트 목록에서 제거하거나, 재도입 계획이 있다면 "미구현(계획)"으로 명시 구분.
- **확신도**: medium — 코드 전역 재검색(`grep -ri "첨부"`)은 수행하지 않아 다른 위치 존재 가능성은 낮지만 배제 못함.

### [ERR-C07] 프레임 카운터 위치가 헤더가 아니라 별도 상단바로 이관됨
- **위치**: sections[0] "검수 헤더" > components[2] "Frame N/total" (FrameCounter)
- **정의서 서술**: 헤더 섹션 내부 컴포넌트로 서술.
- **실제**: `ReviewDetailHeader`에는 프레임 카운터가 없다. 프레임 이동 컨트롤(처음/이전/입력/다음/마지막)과 슬라이더는 헤더 **아래의 별도 컴포넌트 `ReviewTopBar`**(캔버스+우측 패널 폭 전체를 덮는 두 번째 바)로 이관됐다 — 코드 주석: "프레임 위치·이동은 하단 ReviewTopBar 로 이관".
- **근거**: `ReviewDetailHeader.tsx`(FrameCounter 없음) · `ReviewWorkspace.tsx`(주석 "프레임 위치·이동은 하단 ReviewTopBar 로 이관") · `ReviewTopBar.tsx`(FrameNavControls + FrameSlider)
- **조치 제안**: FrameCounter 컴포넌트를 sections[0]에서 제거하고, 신규 섹션 "상단 프레임 이동 바(ReviewTopBar)"를 header 바로 아래 섹션으로 추가.
- **확신도**: high

### [ERR-C08] "프레임 타임라인" footer 서술이 실제 레이아웃과 다름
- **위치**: sections[5] "프레임 타임라인" (role=footer)
- **정의서 서술**: "하단 footer. 진행률 바(progressbar) + 'N / total 타임코드' 텍스트, 가로 스크롤 썸네일 스트립."
- **실제**: 진행률 바 + 타임코드 텍스트로 구성된 별도 footer 요소는 존재하지 않는다. 대신 ① 프레임 이동 컨트롤+슬라이더가 담긴 **상단바**(`ReviewTopBar`, 헤더 바로 아래)와 ② 캔버스 **위쪽**에 위치하며 `FrameStripToggle`로 접고 펼 수 있는 썸네일 스트립(`FrameStrip`)으로 나뉘어 있다. 둘 다 화면 하단(footer)이 아니다.
- **근거**: `ReviewWorkspace.tsx`(JSX 구조 — ReviewTopBar는 헤더 다음, FrameStrip은 canvas 앞(위))
- **조치 제안**: sections[5]를 role=footer가 아닌 위치(헤더 하단 상단바 섹션 + 캔버스 상단 썸네일 스트립 섹션)로 분리 재작성.
- **확신도**: high

---

## 미확인/추가 확인 필요 항목
- SCREEN-018 `ACTION_META`의 `ASSIGNED` 상태 키가 실제 BE 응답에서 도달 가능한지(=검수 목록에 ASSIGNED 상태 행이 실제로 노출될 수 있는지)는 `ReviewService`/`ReviewResponse.status` 매핑까지 확인하지 못했다. **확신도: low**
- SCREEN-019의 "첨부파일 준비중" 잔존 여부는 `ReviewMemoPanel.tsx` 1개 파일 기준이며, 프로젝트 전역 `grep`은 수행하지 않았다. **확신도: medium**
