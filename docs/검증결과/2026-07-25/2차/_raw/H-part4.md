# H 클러스터 part4 (H-9~H-12) 2차 검증 결과

> 대상: `docs/test-cases/H-frontend-e2e.md` `## H-9. 관리 화면` · `## H-10. 증강/해상도 파생 화면` · `## H-11. 포털 화면` · `## H-12. 공통 컴포넌트/에러/상태` (58건)
> 환경: frontend `localhost:13000`(소스 정적 분석) · backend `localhost:18081`(HEAD `ca3c712b`, API 실호출) · 브라우저 자동화 도구 미사용(지시에 따름)
> 방법: 소스 실측(Read/Grep) + BE API 실호출(dev 토큰 3역할) + FE 테스트 파일 존재/라인 대조(baseline: FE 1691 GREEN, `e2e/**`는 vitest 제외 → 미실행)

## 집계

| 판정 | 건수 | 비고 |
|---|---:|---|
| PASS | 48 | |
| PARTIAL | 3 | TC-FE-149, TC-FE-157, TC-FE-176 |
| BLOCKED | 7 | TC-E2E-005·006·007·008·009·010·011 (Playwright 실행 필요) |
| FAIL | 0 | |
| N/A | 0 | |
| 확인필요 | 0 | |
| **합계** | **58** | |

- PARTIAL 3건은 모두 카탈로그 케이스 자체의 핵심 단언(REVIEWER 전용 접근, ProcessKindCard 렌더, 영상 목록+선택)은 PASS이나, 같은 화면에서 ★확증편향 체크포인트(클라이언트 필터 대체·409 안내 유실·self-fill)로 별도 결함을 발견해 등급을 낮춤.
- BLOCKED 7건은 `e2e/specs/*.spec.ts`(Playwright) — `vitest.config.ts:20`에서 `exclude: ['e2e/**']`로 baseline(1691건)에서 원천 제외되어 이번 회차도 미실행. 파일 존재·참조 페이지 오브젝트(`DashboardPage`/`VideoListPage`/`TaskListPage`/`ReviewListPage`/`fixtures/auth.fixture`)는 전부 확인했고, 근거 체인(라우트 가드·API 계약)은 개별적으로 PASS 확인됨 — 실제 브라우저 내비게이션 결과만 미확인(BLOCKED 목록 절 참조).

## ★BE↔FE 계약 드리프트 대조

| 확인 항목 | 결과 |
|---|---|
| `GET /v1/users` 응답 필드(role/active) | BE `UserController.list`가 `role` 쿼리파라미터를 지원(정규식 화이트리스트)하나 FE `UserManagePage`는 이를 사용하지 않고 client-side `.filter()`로 대체 — H-ISSUE-61 |
| `GET /v1/stats/summary` 응답 | 실호출 결과 `pendingCount/completedCount/myTaskCount/rejectedCount/cumulativeImageCount/cumulativeVideoCount/eventDistribution/imageDistribution/myTask/notices` 전부 FE `DashboardSummary` 소비 필드와 1:1 — 드리프트 없음 |
| `POST /v1/augments/request` 409 CONFLICT 본문 | BE `AugmentRequestService.duplicateGuidance`가 PENDING/ACCEPTED 상태별 실행가능 안내 문구를 `message`에 담아 반환(실호출로 확증: `{"message":"이미 채택된 증강입니다. 채택된 증강은 되돌릴 수 없어 같은 영상·종류로는 다시 요청할 수 없습니다.","errorCode":"CONFLICT"}`) — FE `AugmentRequestPage.tsx:159-161` `onError`가 `err`를 무시하고 고정 문구 `'증강 요청 실패'`만 노출해 BE 안내가 유실됨 — H-ISSUE-62 |
| `GET /v1/manage/labels` `dtctTypeCd` allowlist | BE `LabelMasterRequest`가 20자 상한 + 서비스단 COCO allowlist 검증, FE는 `<select>`(COCO_CLASSES) 드롭다운만 노출 — 자유 텍스트 입력 경로 없음, 정합 |
| WORKER 토큰으로 관리 API 직접 호출 | `/v1/users`·`/v1/users/workers`·`PATCH /v1/users/{id}`·`/v1/deident-reports`·`/v1/manage/presets`·`POST /v1/manage/labels` 전부 403 실측 — FE 라우트 숨김이 아니라 BE `@PreAuthorize("hasRole('REVIEWER')")`가 실제 방어선 |
| `PROCESS_KINDS`(WINTER/NIGHT/RAIN/RESOLUTION) ↔ BE `AugmentType`/해상도 프리셋 | FE 4종 라디오 카드, BE 증강 3종 + 해상도 3종 프리셋 별도 도메인 — `isAugmentKind` 타입가드로 분기, 드리프트 없음 |

## H-9 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-FE-148 | 라벨 마스터 COCO 매핑 등록 | PASS | [정적] `pages/manage/LabelMasterManagePage.tsx` 전체 확인. `dtctTypeCd`는 `LabelMasterFormModal.tsx`에서 `COCO_CLASSES` 기반 `<select>`로만 입력(자유텍스트 없음). BE `LabelMasterRequest.java` `@Size(max=20)` + 서비스단 COCO allowlist 검증(코멘트 명시) | |
| TC-FE-149 | 사용자/권한 관리 REVIEWER 전용 | PARTIAL | [실동작] WORKER 토큰으로 `GET/PATCH /v1/users*` 전부 403 확인(BE `@PreAuthorize` 4곳). FE 라우터도 `InternalRoute allow={internalReviewerOnly}` 가드. 단 `UserManagePage.tsx:47,86-95,136-145`에서 role/status 필터를 BE 파라미터(`UserListParams.role`/`active`, BE는 `role`만 지원)로 보내지 않고 **현재 로드된 페이지(`data.content`)만 client-side `.filter()`**하면서 `DataTable`의 `totalElements`/페이지네이션은 여전히 BE 미필터 전체값을 사용 — 필터 적용 시 표시 건수와 페이지 수가 어긋난다(seed 5명뿐이라 1페이지라 현재는 미관측). 도메인 규칙 "필터·집계는 BE 전체 기준" 위반 | H-ISSUE-61 |
| TC-FE-150 | 시스템 설정 CRUD(정밀도/YOLO conf·iou) | PASS | [정적] `YoloConfigCard.tsx`·`PrecisionConfigCard.tsx` 전체 확인. 문구에 YOLO/SAM2 기술 모델명 없음("AI 탐지 추론 파라미터"). BE `SystemConfigController` `GET/PUT /v1/manage/configs` REVIEWER 전용(`@PreAuthorize`) 확인 | |
| TC-FE-151 | 프리셋 목록/편집(마스터 join) | PASS | [정적] `PresetListPage.tsx` 전체 확인 — 코드칩은 `PresetCodeChip`가 마스터 join 결과(`linked`/`labelType`) 렌더, 미매핑은 '미연결' 배지. BE `PresetController` GET(list)도 REVIEWER 전용(`@PreAuthorize` 5곳) — `/manage/presets` 경로 원칙과 일치 | BE는 비페이징 `List<PresetResponse>` 반환·FE 클라이언트 페이지네이션(10/페이지) — 프리셋은 소규모 고정 도메인이라 API 설계 규칙 예외로 판단, 결함 아님 |
| TC-FE-152 | 비식별 신고 관리 목록 | PASS | [정적] `DeidentReportListPage.tsx` 전체 확인 — `status`/`page`/`size` 모두 `useDeidentReports` 훅으로 BE 파라미터 전달(서버 페이징/필터, client filter 없음). WORKER 403 실측 | |
| TC-FE-153 | 작업 배정/재배정 모달 | PASS | [정적][실동작] `AssignModal.tsx` 전체 확인 — `canChangeReviewer = role===REVIEWER`로 검수자 select 게이팅, WORKER는 readonly. BE `AssignmentController` POST/PATCH `@PreAuthorize("hasRole('REVIEWER')")` 실측 일치 | |
| TC-FE-154 | 재배정 이력 드로어 | PASS | [정적] `HistoryDrawer.tsx` 전체 확인 — role=dialog aria-modal, 포커스 트랩/복귀, ESC 닫기 구현. 이벤트 5종(ASSIGN/REASSIGN/SUBMIT/APPROVE/REJECT) `dotClass`/`describeEvent` 매핑 확인 | |
| TC-FE-155 | 대시보드 KPI 3카드+이벤트 분포 | PASS | [실동작] `GET /v1/stats/summary` 실호출 응답 필드가 `DashboardPage.tsx` 소비 필드(`pendingCount`/`completedCount`/`myTaskCount`/`rejectedCount`/`cumulativeImageCount`/`cumulativeVideoCount`/`eventDistribution`/`imageDistribution`)와 1:1 일치. WORKER 4카드/REVIEWER 3카드 분기(`isWorker`) 확인 | |
| TC-FE-156 | 영상 현황 목록 검색/필터/URL 동기화 | PASS | [정적] `features/video/parseVideoListParams.ts` 전체 확인 — URLSearchParams↔VideoListParams 양방향 변환, 숫자 필드 NaN 가드, 날짜 정규식 검증 | |
| TC-E2E-005 | REVIEWER 워크플로우 전체 | BLOCKED | `e2e/specs/reviewer-workflow.spec.ts` 존재·전문 확인(대시보드/영상목록/작업목록/검수목록/사용자관리 5개 진입 검증). `vitest.config.ts:20` `exclude:['e2e/**']`로 baseline 미포함 | 사유: 브라우저 자동화 필요. 근거 페이지(`DashboardPage.tsx`/라우트가드)는 개별 PASS 확인됨 |
| TC-E2E-006 | 영상 목록 검색어 URL 동기화 | BLOCKED | `e2e/specs/video-list.spec.ts` 존재·전문 확인(`cctvNameKeyword=` URL 검증). 위와 동일 사유로 미실행 | 사유: 브라우저 자동화 필요. `parseVideoListParams.ts` 단위 로직은 TC-FE-156에서 PASS 확인 |

## H-10 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-FE-157 | 증강 요청 화면(WINTER/NIGHT/RAIN+해상도) | PARTIAL | [정적] `features/augment/types.ts:24-46`(PROCESS_KINDS 4종) + `AugmentRequestPage.tsx:310-320`(radiogroup, ProcessKindCard 4개 map) 확인 — 화면 자체 렌더는 정상. 단 같은 페이지의 `useRequestAugment` `onError`(159-161행)가 BE 409 CONFLICT의 상태별 안내 문구를 버리고 고정 `'증강 요청 실패'`만 노출 — 실호출로 BE가 `"이미 채택된 증강입니다... 다시 요청할 수 없습니다"`를 내려줌을 확인했으나 FE는 이를 사용자에게 전달하지 않는다 | H-ISSUE-62 |
| TC-FE-158 | 해상도 파생 요청 mutation | PASS | [정적] `useResolutionDerivative.ts` 전체 확인 — `onSuccess`에서 `VIDEO_KEYS.all` invalidate. `changeResolution(rawSn, presets)` 시그니처 일치 | |
| TC-FE-159 | 해상도 파생 증강 이력 배지 | PASS | [정적] `JobCard.tsx:66-74`(카탈로그 64-72, 2줄 드리프트) — `resolutionDerivativeLabel`로 "해상도 720p" 등 한글 라벨, `info` 톤 배지(border-info/bg-info) | 근거 드리프트 |
| TC-FE-160 | 해상도 파생 accept/reject 없음(비검수) | PASS | [정적] `AugmentResultPage.tsx:420-421`(reviewable 판정) `,452-461`(DecisionCard 조건부 렌더) — `reviewable = result.reviewable !== false`로 해상도(reviewable=false) 결과만 DecisionCard 미노출. 카탈로그(419-421) 대비 1줄 드리프트 | 근거 드리프트 |
| TC-FE-161 | 증강만 없고 파생만 → "파생" 태그 | PASS | [정적] `JobCard.tsx:75-83`(카탈로그와 정확히 일치) — `hasResolution && job.types.length===0`일 때만 '파생' 배지 | |
| TC-FE-162 | 증강 채택 → ACCEPTED | PASS | [정적] `DecisionCard.tsx` 전체 확인 — PENDING 상태에서 채택 버튼→`onAccept`→`useAcceptAugment` mutation, 성공 시 캐시 invalidate | |
| TC-FE-163 | 증강 반려 사유 모달 | PASS | [정적] `RejectReasonModal.tsx` 전체 확인 — zod `min(1).max(500)` 검증, `isValid` 미충족 시 제출 버튼 비활성 | |
| TC-FE-164 | 거부 사유 표시(XSS escape) | PASS | [정적] `DecisionCard.tsx` REJECTED 분기 — `{rejectReason}` JSX 텍스트 보간(React 자동 escape), `dangerouslySetInnerHTML` 미사용 | |
| TC-FE-209 | 해상도 파생 비교 이미지 인증 blob 렌더 (신규) | PASS | [정적] `AugmentResultPage.tsx:425-450`부근에서 `FrameGrid12`/`SideBySideCompare`에 `authImages` prop 전달 확인. `AuthImage.tsx`가 `apiClient` blob 다운로드→objectURL 사용(raw `<img src>` 아님) | |
| TC-FE-210 | 12쌍 초과 시 프레임 페이저 노출 (신규) | PASS | [정적] `AugmentResultPage.tsx:414`(`showPager = totalPairs>FRAME_PAGE_SIZE \|\| framePage>0`) + `data-testid="augment-frame-pager"` 확인. `Pagination` page/size가 쿼리 파라미터로 `useAugmentResult(id,{page,size})`에 전달 | |
| TC-FE-211 | 탭 전환 시 프레임 페이지 리셋 (신규) | PASS | [정적] `AugmentResultPage.tsx:343-349`(카탈로그 344-349, 1줄 드리프트) — `prevTypeRef` 직전 값 비교로 실제 탭 변경 시에만 `onFramePageChange(0)` 호출, 마운트 시 미호출 | 근거 드리프트(경미) |
| TC-FE-212 | 해상도 타입 라벨 매핑(undefined 해소) (신규) | PASS | [정적] `features/augment/augTypeLabel.ts` 전체 확인 — `RESL_` 접두는 `resolutionDerivativeLabel` 위임, 미지 코드는 `'증강'` 폴백. 기술코드(RESL_720P 등) 직접 노출 경로 없음 | |
| TC-FE-213 | 결과 본문 있으면 "외부연동 대기" 안내 미표시 (신규) | PASS | [정적] `AugmentResultPage.tsx` `totalPairsOf()`(36-38행) — 총 처리 이미지는 `r.totalFramePairs ?? r.framePairs.length`로 페이징 전 전체값 사용(현재 페이지 길이 아님). `augment-result-completed-empty` testid(246행) 확인 | |
| TC-E2E-007 | 증강 결과 PENDING 채택 → ACCEPTED | BLOCKED | `e2e/specs/augment-decision.spec.ts` 존재 확인. `vitest.config.ts` exclude로 baseline 미포함, 브라우저 자동화 도구 미사용 지시로 미실행 | 사유: 브라우저 자동화 필요. 단위 로직은 TC-FE-162 PASS |

## H-11 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-FE-165 | 이미지 확장자 검증(jpg/jpeg/png) | PASS | [정적] `features/portal/uploads/validation.ts:8`(IMAGE_EXTENSIONS)·`39-42`(검증 루프, 카탈로그 33-49 범위 내) — 확장자 미허용 시 제외+정책 문구 안내 | |
| TC-FE-166 | 이미지 20MB 초과 제외 | PASS | [정적] `validation.ts:10`(MAX_IMAGE_BYTES, 카탈로그 9)·`43`(카탈로그 44) — 1줄 드리프트, 로직 일치 | 근거 드리프트(경미) |
| TC-FE-167 | 이미지 50장 상한 초과 잘라냄 | PASS | [정적] `validation.ts:12,50-56`(카탈로그와 정확히 일치) — `accepted.slice(0, MAX_IMAGE_COUNT)` + 안내 문구 | |
| TC-FE-168 | 이미지 업로드 성공 후 초기화 | PASS | [정적] `PortalUploadPage.tsx:77-87`(카탈로그와 정확히 일치) — 업로드 성공 시 `selected`/`validationErrors`/input value 초기화 | |
| TC-FE-169 | 영상 TUS 재개 업로드(mp4/mov/avi) | PASS | [정적] `PortalUploadPage.tsx:90`(tus 훅, endpointBase=`/portal/uploads/tus`)·`95-100`(onVideoStart) — 카탈로그(89-100) 범위 일치. `useTusUpload.ts:30` 미열람이나 존재 확인 | |
| TC-FE-170 | 삭제 확인+PROCESSING 버튼 비활성 | PASS | [정적] `PortalUploadPage.tsx:270-271`(카탈로그와 정확히 일치) — `isProcessing` 판정 후 `disabled`+`title` 안내 | |
| TC-FE-171 | 삭제 409 처리중 안내(내부 미노출) | PASS | [정적] `PortalUploadPage.tsx:48-53`(카탈로그와 정확히 일치) — `deleteErrorMessage()`가 409/CONFLICT만 "처리 중 자산은 삭제할 수 없습니다" 전용 문구, 그 외는 일반 문구로 내부 메시지 미노출(CWE-209 방어) | |
| TC-FE-172 | READY 자산만 라벨링 링크 | PASS | [정적] `PortalUploadPage.tsx:296-306`(카탈로그와 정확히 일치) — `isReady` 조건부 `<Link>` | |
| TC-FE-173 | FAILED 자산 실패 사유 표시 | PASS | [정적] `PortalUploadPage.tsx:288-292`(카탈로그와 정확히 일치) — `upload.failRsnCn` 텍스트 노출, 없으면 기본 문구 | |
| TC-FE-174 | 사용자 파일명 텍스트노드 렌더(XSS 방어) | PASS | [정적] `PortalUploadPage.tsx:281`(카탈로그 280-281) — `{upload.orgnlFileNm}` JSX 텍스트 보간, escape 자동 적용 | |
| TC-FE-175 | 포털 업로드 라벨링 BBOX/POLYGON만 | PASS | [정적] `PortalUploadLabelingPage.tsx` 전체 확인 — `UPLOAD_TOOLS` 배열이 SELECT/PAN/BBOX/POLYGON 4종만, SAM/키포인트/오토라벨 관련 도구·버튼 0건 | |
| TC-FE-176 | 포털 홈 데이터마트 영상 선택 | PARTIAL | [정적] `PortalHomePage.tsx` 전체 확인 — 영상 목록+카드 선택→`/portal/label/{firstSrcSn}` 이동은 정상 동작. 단 `KpiCard label="라벨링 완료" value={0}`(61행)이 실제 데이터 바인딩 없이 **하드코딩된 0**으로 고정 — 사용자가 실제로 라벨링을 완료해도 항상 "0건"으로 표시됨(self-fill 패턴) | H-ISSUE-63 |
| TC-FE-177 | 포털 라벨링 저장(원본 미수정, 본인 적재) | PASS | [정적] `useSavePortalLabels.ts` 전체 확인 — `POST /v1/portal/user-labels` 단건 순차 호출(원본 `LS_DATA_LBL` 미접근), KEYPOINT/BBOX/POLYGON 직렬화 로직 확인 | |
| TC-E2E-008 | 포털 홈 정상 진입 | BLOCKED | `e2e/specs/portal-channel-guard.spec.ts` 존재·전문 확인(10행 포털 홈 진입) | 사유: 브라우저 자동화 필요 |
| TC-E2E-009 | 포털→내부 대시보드 차단 | BLOCKED | 위 파일 15행(대시보드 forbidden 검증) 존재 확인. 근거 체인은 `router/__tests__/portalGuard.test.tsx`가 baseline에 포함되어 별도로 PASS 확인됨(H-part1 TC-E2E-002 판정과 동일 근거) | 사유: 브라우저 자동화 필요 |
| TC-E2E-010 | 포털→내부 관리 화면 차단 | BLOCKED | 위 파일 26행(manage 차단 검증) 존재 확인. 근거 체인 동일 | 사유: 브라우저 자동화 필요 |
| TC-E2E-011 | 포털 업로드 dropzone 노출+업로드 | BLOCKED | `e2e/specs/portal-upload.spec.ts` 존재·전문 확인(10,20행) | 사유: 브라우저 자동화 필요 |

## H-12 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|------|----------|------|
| TC-FE-178 | ErrorBoundary 스택 콘솔만/사용자 미노출 | PASS | [정적] `ErrorBoundary.tsx:21-38`(카탈로그와 정확히 일치) — `componentDidCatch`는 `console.error`만, 렌더는 `role="alert"` + "오류가 발생했습니다"(스택트레이스 미노출) | |
| TC-FE-179 | ErrorBoundary custom fallback | PASS | [정적] `ErrorBoundary.tsx:30` `this.props.fallback ?? (...)` 확인 | |
| TC-FE-180 | AppErrorPage 404/500 | PASS | [정적] `AppErrorPage.tsx` 전체 확인 — 403/404/500 3종 `TITLES` 매핑(카탈로그는 404/500만 언급했으나 403도 지원, 초과 커버) | |
| TC-FE-181 | EmptyState 빈 목록 | PASS | [정적] 컴포넌트·테스트 파일(`EmptyState.test.tsx`) 존재 확인, baseline 포함 | |
| TC-FE-182 | AuthImage — Bearer blob 로딩(401 회피) | PASS | [정적] `AuthImage.tsx:60-122`(카탈로그와 정확히 일치) — `acquireAuthImage`로 blob→objectURL, 로딩/에러 폴백 `<div>`에 `data-*`/`aria-*` 유지(`toFallbackProps`) 확인 | |
| TC-FE-183 | Pagination 페이지 이동 | PASS | [정적] `Pagination.tsx` 확인, 테스트 파일 존재(baseline 포함) | |
| TC-FE-184 | DataTable 정렬/렌더 | PASS | [정적] `DataTable.tsx:44-73` — `onSortChange` 콜백으로 BE 위임(클라이언트 자체 정렬 없음), `sortable` 컬럼만 헤더 클릭 가능 | |
| TC-FE-185 | Toast variant별 표시 | PASS | [정적] `Toast.tsx:15-34` — success/error/warning/info 4종 `variantClass`/`variantLabel`/`variantIcon` 매핑, 테스트 파일 존재 | |
| TC-FE-186 | 게시판(공지) 목록/상세 | PASS | [정적] `NoticeListPage.tsx`·`NoticeDetailPage.tsx` 전체 확인 — URL 쿼리 기반 검색/페이지, 본문(`notice.content`)은 JSX 텍스트 보간(XSS 자동 방어) | |
| TC-FE-187 | RoleClaimPage 권한 자가부여 | PASS | [정적] `RoleClaimPage.tsx` 확인 — `ClaimableRole='WORKER'` 화이트리스트(REVIEWER는 공유 패스워드로 자가부여 불가, 주석상 A-ISSUE-17), password 필드 `type="password"` + `autoComplete="new-password"` | |
| TC-FE-188 | 통계 화면 차트(recharts 별도 청크) | PASS | [정적] `router/index.tsx:87-91`(WorkerStatPage/OverallStatPage `lazyWithRetry` 확인) + recharts 사용처(`grep`) 4파일 전부 두 Stat 페이지 하위에만 위치, DashboardPage 등 즉시로딩 경로에 recharts 미포함 확인 | |
| TC-FE-214 | AuthImage `path` 화이트리스트 fail-closed (신규) | PASS | [정적] `lib/api/imagePath.ts:11-21`(카탈로그와 정확히 일치) — `ALLOWED_IMAGE_PATH = /^\/v1\/frames\/[0-9]{1,19}\/(?:deid-)?image$/`, 미통과 시 `null` 반환→`AuthImage`가 요청 자체를 보내지 않고 폴백(fail-closed) | |
| TC-FE-215 | authImageStore refcount 중복 페치 제거 (신규) | PASS | [정적] `authImageStore.ts:97-105`(acquireAuthImage, 카탈로그와 일치) — 동일 경로 재진입 시 기존 `promise` 재사용, 동시 상한/대기열 없음(주석상 의도적 설계) | |
| TC-FE-216 | 마지막 소비자 unmount 시 즉시 revoke (신규) | PASS | [정적] `authImageStore.ts:19-24`(설계 주석, 비식별 게이트 우회 방지)·`108-118`(releaseAuthImage, 카탈로그 107-118과 거의 일치) — refCount 0 시 즉시 `URL.revokeObjectURL` + 엔트리 삭제, 영속 캐시 없음 | |
| TC-FE-217 | 실패 경로는 새 소비자 마운트 시 1회 재요청 (신규) | PASS | [정적] `authImageStore.ts:51-105`(카탈로그와 일치) — `failed` 엔트리는 `acquireAuthImage`에서 새 소비자가 붙는 시점에만 `startFetch` 재호출(자동 재시도 루프 없음) | |
| TC-FE-218 | 폴백 `<div>` 에 img 전용 속성 미전개 (신규) | PASS | [정적] `AuthImage.tsx:27-47`(카탈로그와 일치) — `IMG_ONLY_PROPS` 배열 제거 후 `toFallbackProps`로 나머지만 전개 | |

## 근거 드리프트

| ID | 카탈로그 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-FE-159 | `JobCard.tsx:64-72` | `66-74` | 2줄 드리프트, 로직 동일 |
| TC-FE-160 | `AugmentResultPage.tsx:419-421,452-461` | `420-421,452-461` | 1줄 드리프트, 로직 동일 |
| TC-FE-211 | `AugmentResultPage.tsx:344-349,418` | `343-349` 부근(prevTypeRef useEffect) | 1줄 드리프트 |
| TC-FE-166 | `validation.ts:9,44` | `10,43` | 1줄 드리프트 |
| TC-FE-216 | `authImageStore.ts:107-118` | `108-118` | 1줄 드리프트 |

전부 ±1~2줄 수준의 경미한 드리프트이며 기대결과·로직 자체는 카탈로그와 일치. 별도 이슈로 등록하지 않음.

## 브라우저 자동화 필요 케이스 목록

| ID | 파일 | 사유 |
|---|---|---|
| TC-E2E-005 | `e2e/specs/reviewer-workflow.spec.ts` | Playwright 스펙, `vitest.config.ts` exclude로 baseline 미실행 |
| TC-E2E-006 | `e2e/specs/video-list.spec.ts` | 동일 |
| TC-E2E-007 | `e2e/specs/augment-decision.spec.ts` | 동일 |
| TC-E2E-008 | `e2e/specs/portal-channel-guard.spec.ts` | 동일 |
| TC-E2E-009 | `e2e/specs/portal-channel-guard.spec.ts` | 동일 |
| TC-E2E-010 | `e2e/specs/portal-channel-guard.spec.ts` | 동일 |
| TC-E2E-011 | `e2e/specs/portal-upload.spec.ts` | 동일 |

모든 파일의 존재·시나리오 코드 전문을 확인했고, 근거가 되는 개별 단위/컴포넌트 로직(라우트 가드, API 계약, mutation 흐름)은 각 대응 카탈로그 케이스(TC-FE-*)에서 PASS로 개별 확인됨 — 실제 브라우저 내비게이션/클릭 결과만 미확인.

## 이슈 상세

### [H-ISSUE-61] TC-FE-149 — 사용자 관리 화면의 역할/상태 필터가 BE 파라미터를 쓰지 않고 현재 페이지만 client-side 필터링
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프로젝트 규칙("목록 화면 정렬·필터 정책") — "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다". BE `UserController.list`(`/v1/users`)는 이미 `role` 쿼리 파라미터(정규식 화이트리스트 `REVIEWER|WORKER|PORTAL_USER`)를 지원한다.
- **현재 동작(이슈 내용)**: `frontend/src/pages/manage/UserManagePage.tsx:86-95` 의 `params` 는 `keyword`/`page`/`size` 만 BE 로 전송하고 `roleFilter`/`statusFilter` 는 포함하지 않는다. 대신 `136-145`행:
  ```tsx
  const allRows = data?.content ?? [];
  const filteredRows = useMemo(() => {
    return allRows.filter((u) => {
      if (roleFilter && u.role !== roleFilter) return false;
      if (statusFilter === 'active' && !u.active) return false;
      if (statusFilter === 'inactive' && u.active) return false;
      return true;
    });
  }, [allRows, roleFilter, statusFilter]);
  ```
  로 **현재 로드된 페이지(`data.content`, 기본 20건)** 만 필터링한다. 그런데 `DataTable` 에 넘기는 `totalElements={data?.totalElements ?? 0}` 은 **필터 적용 전 BE 전체 카운트**를 그대로 쓴다(`325-335`행). BE `listUsers` API(`features/user/api.ts`) 는 `role`/`active` 파라미터를 이미 축조해 보낼 수 있는 타입(`UserListParams`)까지 갖췄고, 실제로 같은 코드베이스의 `AssignModal.tsx` 는 `useUsers({ role: Role.REVIEWER, size: 50 })` 로 서버측 role 필터를 정상 사용 중이다 — 즉 UserManagePage 만 이 패턴을 쓰지 않는다.
- **재현/확인 경로**: 시드 데이터가 5명(REVIEWER 2·WORKER 2·PORTAL_USER 1)뿐이라 1페이지(size=20)에 다 들어와 현재 환경에서는 필터 결과와 페이지네이션이 우연히 일치해 육안으로 드러나지 않는다. 사용자가 21명 이상으로 늘어나고 역할 필터를 걸면: (1) 2페이지 이후 사용자는 필터 후보에서 아예 빠짐(현재 페이지 데이터만 filter 대상), (2) 하단 페이지네이션은 여전히 "전체 N명" 기준으로 여러 페이지를 보여줘 필터링된 화면과 불일치.
- **영향**: 기능 정확성 결함(보안 취약점 아님). 대량 사용자 환경에서 역할/상태 필터가 "현재 페이지에서만 거르고 끝"이라 다른 페이지의 매칭 대상을 놓친다 — 프로젝트 구속 규칙 정면 위반.
- **수정 방향(제안)**: `params` useMemo 에 `role: roleFilter || undefined` 를 포함해 BE 로 전송(BE 는 이미 지원). `active` 는 BE `UserController` 에 파라미터가 없으므로 ①BE 에 `active` 쿼리 파라미터 추가 후 서버 필터로 전환하거나 ②상태 필터를 제거(관제서버 책임 영역이라는 페이지 주석과의 정합도 함께 검토). 구현하지 않음(검증 전용 에이전트 — 지시에 따름).

### [H-ISSUE-62] TC-FE-157 — 증강 중복 요청 409 안내가 FE 에서 유실되어 항상 동일한 일반 메시지만 노출
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프로젝트 규칙 — "중복 요청은 409 이며 안내는 PENDING(\"완료·반려 후 재요청 가능\") / ACCEPTED(\"같은 영상·종류로는 다시 요청할 수 없음\")로 갈려야 한다". BE `AugmentRequestService.duplicateGuidance()`(`backend/src/main/java/kr/co/cudo/authoring/augment/service/AugmentRequestService.java:264-276`)가 정확히 이 요구사항대로 상태별 실행 가능 안내 문구를 만들어 `CustomException(ErrorCode.CONFLICT, ...)` 로 던진다.
- **현재 동작(이슈 내용)**: 실제 BE 호출로 확증:
  ```
  POST /api/v1/augments/request {"videoIds":[136],"types":["NIGHT"]}
  → 409 {"message":"이미 채택된 증강입니다. 채택된 증강은 되돌릴 수 없어 같은 영상·종류로는 다시 요청할 수 없습니다.","errorCode":"CONFLICT"}
  ```
  그러나 `frontend/src/pages/AugmentRequestPage.tsx:159-161`:
  ```tsx
  onError: () => {
    pushToast({ variant: 'error', message: '증강 요청 실패' });
  },
  ```
  `onError` 콜백이 인자(`err`)를 아예 받지 않고 항상 고정 문구 `'증강 요청 실패'`만 토스트로 노출한다. 즉 BE 가 애써 PENDING/ACCEPTED 를 구분해 만든 안내 문구가 **사용자에게 절대 도달하지 않는다** — 사용자는 왜 실패했는지, 기다리면 되는지 다시 요청할 수 없는지 알 방법이 없다. 같은 파일의 해상도 파생 경로(`resolutionErrorMessage`, `260-263`행)는 `ApiError.userMessage` 를 제대로 추출해 노출하는 반면, 증강 요청 경로만 이 패턴을 쓰지 않는 비일관도 있다.
- **재현/확인 경로**: 위 curl 재현 완료(rawSn 136, NIGHT 타입, 기존 ACCEPTED 상태에서 재요청 시 409 + 상태별 메시지 확인). FE 코드상 `useRequestAugment`(`features/augment/hooks/useAugmentDecision.ts:16-27`)의 `onError: options.onError` 로 그대로 전파되므로 `AugmentRequestPage` 호출부의 무시가 원인.
- **영향**: 보안 취약점 아님(정보 노출 반대 방향 — 오히려 유용한 정보가 유실). 사용자 경험 결함이며, 프로젝트가 2026-07-29 자로 "수행 가능한 동선만 말한다"고 명시적으로 재설계한 BE 문구가 FE 에 배선되지 않아 그 설계 의도가 무력화된다.
- **수정 방향(제안)**: 다른 mutation(해상도 파생, `resolveErrorMessage` in `PresetListPage.tsx`, `extractBeMessage` in `AssignModal.tsx`)에서 이미 쓰는 `ApiError.userMessage` 또는 `resolveApiMessage()` 패턴을 `AugmentRequestPage.tsx:159-161` 의 `onError` 에도 적용해 BE `message` 를 그대로 토스트에 반영. 구현하지 않음.

### [H-ISSUE-63] TC-FE-176 — 포털 홈 "라벨링 완료" KPI 가 실제 데이터와 무관하게 항상 0으로 하드코딩
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: KPI 카드는 실제 사용자 데이터를 반영해야 한다(README 검증 기준 "본 프로그램이 외부 응답 없이 값을 자체 생성하면(self-fill) 결함"과 동일 성격의 패턴).
- **현재 동작(이슈 내용)**: `frontend/src/pages/portal/PortalHomePage.tsx:61`:
  ```tsx
  <KpiCard label="영상 수" value={totalVideos} unit="건" />
  <KpiCard label="라벨링 완료" value={0} unit="건" />
  ```
  "영상 수" 는 `useDatamartVideos` 응답(`data.totalElements`)을 바인딩하지만, 바로 아래 "라벨링 완료" 는 어떤 훅/상태와도 연결되지 않은 리터럴 `0` 이다. 사용자가 포털에서 실제로 라벨을 저장(`useSavePortalLabels` → `POST /v1/portal/user-labels`)해도 이 카드는 영구히 "0건"으로 표시된다. 테스트(`PortalHomePage.test.tsx:129`)도 텍스트 존재만 확인(`getByText(/라벨링 완료/)`)할 뿐 값 검증이 없어 이 하드코딩이 회귀 가드 없이 방치돼 있다.
- **재현/확인 경로**: 소스 열람만으로 확정(라인 61) — `value={0}` 이 상수 리터럴이며 어떤 props/query 결과도 참조하지 않음을 코드 레벨에서 확인.
- **영향**: 사용자에게 실제와 다른(항상 축소된) 통계를 보여줘 신뢰도를 해친다. 보안 문제는 아니나 "카운트 self-fill" 은 이 워크스페이스가 여러 클러스터에서 반복적으로 결함으로 분류해온 패턴과 동일 성격.
- **수정 방향(제안)**: 포털 사용자별 라벨링 완료 영상 수를 반환하는 BE 집계 API 신설(예: `LS_PORTAL_USER_LABEL` 기준 distinct 영상 카운트) 후 FE 바인딩. 또는 현재 이 지표를 낼 수 있는 BE 데이터가 없다면 카드 자체를 보류하거나 "준비 중" 표기로 self-fill 을 피한다. 구현하지 않음.
