# H. FE 화면/컴포넌트 + E2E — 테스트 케이스

> 337 케이스 · 계층: component / e2e / a11y / security · [← README](README.md)
> ID: TC-FE(컴포넌트/상태) · TC-E2E(시나리오) · TC-A11Y(접근성)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 224건<br>(기대결과 실질 변경 21건) | 69건 | 0건 | 07-25 이후 `frontend/src` 73파일 변경 반영. ①**작업목록 전면 개편**(b23b8cbd — 상태 우선순위 정렬 폐기→등록일 최신순 시간축 단일, 필터·KPI 서버 이관, KPI 5카드 토글, 컬럼 헤더 정렬, 체크박스 페이지 이월 차단, `TaskBoardTable` 추출) → **H-16 신설** ②**검수목록 개편**(7ecbc66e — 진입 기본값 검수요청·FIFO 를 명시 전송+URL 기록, 상태 코드 역매핑 `Record` 강제, 상태 컬럼 정렬 제외, 300ms debounce) → **H-17 신설** ③**인증 이미지 blob 전환**(f4f2d9fe — `AuthImage` `srcSn\|path` 유니온 + 경로 화이트리스트 fail-closed, `authImageStore` refcount 공유) ④증강 결과 해상도 파생 비교 이미지·프레임 페이저·`reviewable` ⑤412 `PRECONDITION_FAILED` 매핑 + 파생영상 신고 버튼 사전 비활성 ⑥라벨 저장 409 충돌 다이얼로그·`labelVersion` 낙관적 토큰 ⑦검수 승인 `REVIEW_NO_LABEL` 확인 ⑧마킹 fps 서버 위임·`batchTriggered=false` 안내 ⑨`cot` 배열/객체 양형 정규화. 근거(file:line) 전면 재확인 + 파일 경로를 `src/` 기준 상대경로로 정규화(구 파일명만 표기 → 실제 경로). UNCERTAINTIES #23·#25 는 코드로 확정 가능(하단 참조) |

| 2 | 2026-07-31 | 0건 | 16건 | 0건 | **라벨링 화면 장시간 작업(busy) 배타 실행** 반영 — ①편집 차단(캔버스·툴바·프레임 이동·실행 버튼·단축키·되돌리기·롤백·신고), **차단은 입력 단계에서** ②진행 오버레이(300ms 초과, 작업명+경과 초+취소, 모델명 미노출) ③**취소 = 클라이언트 결과 폐기이며 서버 처리 중단 아님**(마우스·Enter·Space·ESC) ④단축키 판정 fail-closed(렌더 값 OR 실시간 store) ⑤ESC 취소 시 AI 분할 확정 큐도 비움 ⑥메타 편집 5종은 **의도적 미차단** ⑦크로스탭 동시성은 범위 밖(서버 409 담당) ⑧**포털 업로드 라벨링에도 오버레이·취소 대칭 배선**. H-3 하위 절 + H-11(TC-FE-271~275) + H-13(TC-A11Y-015) |

| 3 | 2026-08-03 | 4건 | 28건 | 0건 | **2026-08-03 사용자 확정 5건** 반영(커밋 `80171828`·`b27b3108`·`d8a7a2cc`·`e58aa086` + 영상 목록 필터 미커밋분). ①**결정1 라벨링 '메타' 탭 시계열 메타 검토 블록 제거**(상태 배지·승인/반려·반려사유 삭제, 텍스트 수정·저장만) → 회귀 가드 3건 신설(H-3 하위 절) + TC-FE-079·268 정정 ②**결정2 영상 상세 '버전관리로 이동'·라이트박스 '라벨링 편집' 버튼 제거 + `/history/:videoId`(SC-010) 페이지·라우트 삭제** → H-18 신설(기능은 라벨링 인라인 `HistoryPanel` 이 전부 제공하므로 **버전·롤백 기능 케이스는 폐기하지 않고 전제만 정정**) ③**결정3 도형 도구 클릭 → 라벨 선택 모달 → 드로잉** 흐름 신설 + 좌측 상시 라벨 패널(`LabelSidebar`) 폐지 + **전역 1~9 단축키 제거**(모달 전용) + `KeypointGuide` 우측 패널 최상단 이동 → 14건 신설 ④**결정4 라벨명 = 라벨 마스터 등록명 그대로**(코드 사전 치환 폐지 — ③이 도입한 "한글 우선 표시"를 다음 커밋에서 **되돌린 것**) → 4건 신설 ⑤**결정5 영상 목록 검색 필터 4종 + `capturedAt` 축 정정** FE 분 → H-18. BE 분은 [B-18](B-batch-deidentify.md) |

| 4 | 2026-08-03 | 116건 | 0건 | 0건 | **근거 `file:line` 전수 재확인 회차** — `LabelingPage.tsx` 가 라운드2(busy, `81813bd1`)·라운드3(label picker, `d8a7a2cc`) 삽입으로 최대 +170줄 밀려 H-3 원본 절(TC-FE-033~087,197,199~201) 55건 라인 정정. 라운드3 자체가 신설한 시계열메타 검토제거 회귀가드(TC-FE-276~278)도 같은 날 후속 커밋(`5c10c0cd` — 세그먼트별 편집으로 재구현)에 밀려 3건 추가 정정(단일 textarea→세그먼트별 textarea 구조 변경 반영). `AugmentResultPage.tsx` 는 항목축 페이징 신설로 `FrameGrid12`/`SideBySideCompare`/`DecisionCard`/프레임페이저가 신규 `AugmentResultPanel.tsx`·`AugmentVideoSection.tsx` 로 전량 위임돼 TC-FE-159~161·209~211·213 7건 재작성(TC-FE-213 은 "총 처리 이미지=페이징 전 전체" 기대결과가 **반대로** 정정됨 — 코드 주석이 페이지 스코프임을 명시). `ReviewPage.tsx`(4건)·`router/index.tsx`(9건, `/history` 라우트 삭제로 라인 이동)·`useLabelStore.ts`(3건, `setPan` 은 clamp 를 안 하고 `CanvasShell` 이 호출측에서 클램프)·H-13 a11y(6건, 경로 오탈자 `components/ObjectAttributePanel.tsx`→`features/label/components/...` 1건 포함)도 정정. H-5·H-6·H-7·H-18 은 전수 대조 후 **드리프트 없음 확인**(round1 이후 미변경 파일) + H-1·11·12·15·16·17 절 보완 재확인(정정 27건 — H-11 2건·H-12 1건·H-15 6건·H-16 18건, H-1·H-17 은 18~26건 전건 정확 확인). **직전 담당의 "H-15·16·17 은 소스 미변경이라 행단위 재대조 생략" 판단이 틀렸음이 재확인됨**(PM 이 `eace1213`/`1bf06ce5`/`dcdbb827`/`d8a7a2cc`/`e58aa086`/`3f60bd3b` 6개 커밋이 실제로 인용 대상 14파일을 건드렸음을 `git log` 로 지적) — 핵심 발견 4건: ①H-11 TC-FE-275 "포털 라벨링(`/portal/label/:id`)은 AI 분할·추적 제공" 전제가 `dcdbb827`(SAM2 제거)로 이미 폐기됐는데 미반영 — `PORTAL_HIDDEN_TOOLS=[SAM_SEGMENT,TRACK,KEYPOINT]`(types.ts:215-219)가 포털 라벨링·업로드 라벨링 양쪽에 적용돼 BBOX/POLYGON만 제공 ②H-12 TC-FE-188 "통계 화면=recharts 별도 청크" 통칭이 부정확 — `OverallStatPage` 는 `f902e3d1`(07-25 이전) 이후 recharts 미사용(커스텀 `SimpleBarChart`/`SimplePieChart`), recharts 는 `WorkerStatPage`(`DailyCompletionChart`)에만 잔존(라인 인용이 없는 행이라 이전 라운드들이 전부 검증을 건너뜀) ③H-16 TC-FE-242 "WORKER 시각은 클라이언트 필터" 전제가 `eace1213`(필터·정렬·KPI 서버 이관) 이후 무효 — `buildAssignmentParams`(boardParams.ts:206-218)가 이미 검색어/상태/이벤트유형을 `/v1/assignments` 서버로 위임하고 `TaskListPage.tsx` 에는 `.filter(` 재필터 코드가 0건 ④H-15 TC-E2E-019 "COMPLETED 전이" 표현이 실제 최종 단언(`readStatus()` 가 `dataSttsCd==='APPROVED'` 확인)과 불일치. H-16 은 `boardParams.ts`/`TaskListPage.tsx`/`types.ts` 의 대규모 주석·함수 삽입(`eace1213`)으로 인용 라인이 완전히 다른 함수를 가리키는 드리프트가 17건(예: TC-FE-233 이 `buildBoardParams` 를 가리켜야 하는데 `buildBoardSummaryParams` 를 가리킴). H-17(`reviewListParams.ts`/`api.ts`/`ReviewListPage.tsx`/`ReviewKpiCards.tsx`/`ReviewListFilters.tsx`)은 `1bf06ce5` 변경에도 불구하고 18건 전건 정확 — 파일이 바뀌었다고 반드시 드리프트가 나는 것은 아님(대조 없이 넘기면 안 되는 이유이자, 대조 결과 자체는 케이스바이케이스) |

| 5 | 2026-08-03 | 55건 | 0건 | 0건 | **테스트케이스 전수 검증 3차 회차**(`docs/검증결과/2026-08-03/3차/`, 8파트 병렬 실동작 검증(브라우저 Playwright + 실 API 왕복 + DB 실측) 후 병합, 실행 2026-08-03~08-04 KST) — **2차 HIGH 5건 중 4건 해소 확인**(#7 세션만료 복귀URL 미주입·#9 낙관적동시성 labelVersion·#10 E2E 픽스처 부재·#11 포털 라벨링 AI도구 노출), **#8(`?token=` URL 인계 활성, CWE-598)만 3회차 연속 미해소**(`H-ISSUE-01`). **신규 HIGH 2건**: `H-ISSUE-41`(서버 잠금상태 문자열 `LOCKED` vs FE 판정값 `LOCKED_FOR_REDEIDENT` 불일치로 잠금 배너 미표시 — BE 409 최종차단으로 데이터유실은 없음) · `H-ISSUE-81`(포털 업로드 E2E `portal-upload.spec.ts`가 업로드 UI 없는 `/portal` 홈을 겨냥해 실질 커버리지 0). 근거 `file:line` 드리프트 + 기대결과 오류 정정 **55건**(H-1 3·H-3 앞 26·H-3 중 5·H-5 2·H-9 1·H-11 7·H-16 1, `git diff` 실측 55행과 일치). 신규/폐기 케이스 0건. 이슈 전문은 `docs/검증결과/2026-08-03/3차/ISSUES.md` "## H클러스터" 참조 |

> **ID 부여 규칙(이번 회차)**: 신규 케이스는 섹션 위치와 무관하게 **문서 전체 마지막 번호 다음**부터 이어서 부여했다(1회차 TC-FE-194~260, TC-A11Y-013~014 / 3회차 TC-FE-276~303). 섹션별로 이어 붙이면 뒤 섹션의 기존 ID 와 충돌하기 때문이다.
> **기준선**: FE 테스트 **338 files / 2,038 tests**(2026-08-03, 커밋 `e58aa086` 전체 회귀). 07-30 시점 1,674 → 07-25 시점 ~1,5xx. "기존 테스트 부분 커버" 서술은 이 수치로 읽는다.

## H-1. 인증/라우팅 가드

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-001 | RoleGuard 하이드레이션 대기 | 토큰 복원 전 | isHydrated=false | "인증 확인 중" 스피너 | component | High | router/guards.tsx:34-40 |
| TC-FE-002 | RoleGuard claims 없음 → ingress | hydrated, claims=null | 보호 경로 | /ingress Navigate | component | High | router/guards.tsx:41-43 |
| TC-FE-003 | RoleGuard exp 만료 → 상위 redirect | exp 과거 | 진입 | clear()+redirectToUpstream | security | High | router/guards.tsx:27-32,44-51 |
| TC-FE-004 | RoleGuard role=null → role-claim | INTERNAL, role 미부여 | 진입 | /role-claim Navigate | component | High | router/guards.tsx:52-55 |
| TC-FE-005 | RoleGuard 역할 불일치 → forbidden | WORKER, allow=[REVIEWER] | 진입 | /forbidden | security | High | router/guards.tsx:57-58 |
| TC-FE-006 | RoleGuard 역할 일치 통과 | role∈allow | 진입 | children 렌더 | component | High | router/guards.tsx:60 |
| TC-FE-007 | ChannelGuard 채널 불일치 → forbidden | PORTAL, INTERNAL 요구 | 진입 | /forbidden | security | High | router/guards.tsx:88-89 |
| TC-FE-008 | AuthenticatedGuard role=null 통과 | 인증만 | /role-claim | children(무한 redirect 없음) | component | High | router/guards.tsx:103-144 |
| TC-FE-009 | isExpired: exp=0/미지정은 만료 아님 | exp undefined/0 | 판정 | false | component | Med | router/guards.tsx:94-97 |
| TC-FE-010 | JWT payload role 화이트리스트 | role="ADMIN" | setToken | claims=null | security | High | stores/useAuthStore.ts:47-48 |
| TC-FE-011 | JWT role 빈값 허용(자가부여 대기) | role="" | setToken | role=null claims 유효 | component | Med | stores/useAuthStore.ts:47-49 |
| TC-FE-012 | JWT channel/sub/exp 누락 무효 | 필수 부재 | decode | null | security | High | stores/useAuthStore.ts:54 |
| TC-FE-013 | JWT 한글 name UTF-8 디코드 | 한글 name | decode | TextDecoder 정상 | component | Med | stores/useAuthStore.ts:29-40 |
| TC-FE-014 | JWT parts≠3 무효 | 형식 오류 | decode | null | security | Med | stores/useAuthStore.ts:24 |
| TC-FE-015 | hydrate: 만료 토큰 sessionStorage 제거 | exp 과거 | hydrate() | 제거+token=null | security | High | stores/useAuthStore.ts:89-102 |
| TC-FE-016 | 토큰 저장소=sessionStorage | setToken | 저장 | sessionStorage만(XSS 노출면 축소) | security | High | stores/useAuthStore.ts:76,82,86,90 |
| TC-FE-017 | axios 요청 인터셉터 Bearer 주입 | store token | 요청 | Authorization 헤더 | component | High | lib/api/client.ts:32-38 |
| TC-FE-018 | ApiResponse 언랩 — data 추출 | 정상 응답 | 응답 | res.data=body.data, message 보존 | component | High | lib/api/client.ts:41-49 |
| TC-FE-019 | ApiResponse success=false → ApiError | body.success=false | 응답 | ApiError.fromBody throw | component | High | lib/api/client.ts:43-45 |
| TC-FE-020 | 401 토큰 레이스 1회 재시도 | 헤더 없이+토큰 존재 | 401 | Authorization 붙여 1회 재요청 | security | High | lib/api/client.ts:54-71 |
| TC-FE-021 | 정상 401 → clear+상위 로그인 | 재시도 대상 아님 | 401 | clear()+redirectToUpstreamLogin() | security | High | lib/api/client.ts:72-75 |
| TC-FE-022 | baseURL 환경변수만(Open Redirect 방어) | VITE_API_BASE_URL | 클라 생성 | env 값만 | security | High | lib/api/client.ts:23-30 |
| TC-FE-194 | 412 → errorCode PRECONDITION_FAILED 매핑 (신규) | 본문 없는 412(blob 등) | ApiError.fromStatus(412) | errorCode='PRECONDITION_FAILED', 기본문구 "현재 상태에서는 수행할 수 없는 요청입니다."(구 INTERNAL_ERROR 대체 아님) | security | High | lib/api/errors.ts:37,47 · lib/api/__tests__/errors.test.ts |
| TC-FE-195 | resolveApiMessage — 400/409/412만 서버 문구 노출 (신규) | ApiError | status 별 호출 | 400/409/412=userMessage, 401/403/5xx·비-ApiError=fallback(내부정보 미노출) | security | High | lib/api/resolveApiMessage.ts:4,16-19 |
| TC-FE-196 | compactParams 빈 값 키 제거 · 0/false 보존 (신규) | 필터 조립 | `{status:'', page:0, sort:[]}` | status·sort 키 삭제, page=0 유지(→ `status=` 400 미발생) | component | High | lib/compactParams.ts:12-21 · lib/__tests__/compactParams.test.ts |
| TC-E2E-001 | 토큰 없이 보호 경로 → 상위 로그인 | 미인증 | 보호 URL | 상위 시스템 로그인 | e2e | High | e2e/specs/login-redirect.spec.ts:4 |

## H-2. 라우터 구조/코드스플리팅

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-023 | `/` → `/dashboard` redirect | 인증됨 | `/` | Navigate replace | component | Med | router/index.tsx:228 |
| TC-FE-024 | `/label/:id`는 풀스크린 | WORKER/REVIEWER | 진입 | LNB/GNB 없음 | component | Med | router/index.tsx:213-219 |
| TC-FE-025 | REVIEWER 전용 라우트 게이팅 | review/manage/augment/overall | WORKER 진입 | forbidden | security | High | router/index.tsx:280,330,340,370 |
| TC-FE-026 | 알 수 없는 내부 경로 → 404 | 인증됨 | /xyz | AppErrorPage 404 | component | Med | router/index.tsx:446 |
| TC-FE-027 | manage/* placeholder REVIEWER 게이팅 | 미정의 하위 | 진입 | REVIEWER만 | component | Low | router/index.tsx:412-419 |
| TC-FE-028 | dev 라우트 플래그 OFF dead-code 제거 | VITE_DEV_LOGIN_ENABLED 미설정 | 빌드 | /dev/login 청크 미포함 | security | Med | router/index.tsx:163-176 |
| TC-FE-029 | devUpload 라우트 REVIEWER 제한 | VITE_DEV_UPLOAD_ENABLED=true | 진입 | REVIEWER만(`dev/autolabel-test`) | security | Med | router/index.tsx:178-198 |
| TC-FE-030 | PortalRoute 채널+역할 이중가드 | /portal/* | 진입 | ChannelGuard+RoleGuard | security | High | router/index.tsx:155-161 |
| TC-FE-031 | lazyWithRetry 청크 로드 실패 재시도 | fetch 실패 | 재진입 | 재시도(Suspense fallback) | component | Med | router/lazyWithRetry.ts |
| TC-FE-032 | Suspense PageFallback 스피너 | 로딩 중 | 진입 | "페이지 로딩" | component | Low | router/index.tsx:122-128 |
| TC-E2E-002 | 라우트 가드 스위트(deepLink/manage/portal/review/augment) | 각 역할 | 딥링크 | 정책대로 통과/차단 | e2e | High | router/__tests__/{deepLinkHydrationGuard,manageGuard,portalGuard,reviewGuard,augmentExportGuard}.test.tsx |

## H-3. LabelingPage (라벨링 캔버스)

> ★ 이번 회차 변경: 저장 실패 처리에 **409 충돌 다이얼로그**가 추가되고(기존 단일 에러 토스트에서 분기), 저장 요청이 **`labelVersion` 낙관적 토큰**을 싣는다. 비식별 신고 버튼은 **파생영상이면 사전 비활성**된다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-033 | 잘못된 ID(NaN) 다크 에러 | id 비숫자 | 진입 | "잘못된 프레임 ID"+뒤로가기 | component | High | pages/label/LabelingPage.tsx:1076-1095 |
| TC-FE-034 | 로딩 상태 스피너 | isLoading | 진입 | `Spinner label="라벨 로딩"` → `role=status`+`aria-live=polite`+`aria-label="라벨 로딩"`+sr-only 텍스트 (구 "data-testid" 표기는 오류 — 스피너에 data-testid 는 없고 페이지 컨테이너만 `data-testid="labeling-page"`) + "라벨 로딩 중..." 문구 | component | Med | pages/label/LabelingPage.tsx:1097-1110 · components/common/Spinner.tsx:9-23 |
| TC-FE-035 | 포털 403 → graceful 차단화면 | portalMode+403 | 진입 | "접근할 수 없는 영상입니다" | security | High | pages/label/LabelingPage.tsx:1115-1142 |
| TC-FE-036 | 일반 에러 → "라벨 조회 실패" | error | 진입 | 에러 문구(=BE message)+뒤로가기 | component | Med | pages/label/LabelingPage.tsx:1144-1164 |
| TC-FE-037 | siblings 비면 현재 프레임 단건 폴백 | siblings=[] | 렌더 | 현재 1건 | component | Med | pages/label/LabelingPage.tsx:236-263 |
| TC-FE-038 | 저장 성공 토스트 "저장됨" | dirty 라벨 | handleSave | updateLabels+clearDirty+'저장됨' | component | High | pages/label/LabelingPage.tsx:559-597 |
| TC-FE-039 | 저장 중복 제출 차단 | saving in-flight | Ctrl+S 연타 | 두번째 무시 | component | High | pages/label/LabelingPage.tsx:563 |
| TC-FE-040 | 잠금 영상 저장 차단 | isLocked | handleSave | 에러 토스트, PUT 미발생 | security | High | pages/label/LabelingPage.tsx:565-571 |
| TC-FE-041 | 저장 실패 에러 토스트(BE 문구 우선) | 409 외 reject | handleSave | `extractBeMessage(e,'저장 실패')` 토스트 (구 `e.message` 직접 노출 아님) | component | Med | pages/label/LabelingPage.tsx:592-595 |
| TC-FE-042 | 포털 모드 저장 경로 분기 | portalMode | handleSave | savePortalLabels(원본 미수정) | security | High | pages/label/LabelingPage.tsx:552-558 |
| TC-FE-043 | 프레임 이동 dirty 가드 모달 | dirtyCount>0 | requestJumpTo | FrameNavGuardModal | component | High | pages/label/LabelingPage.tsx:340-352 |
| TC-FE-044 | 같은 프레임 이동 no-op | target===현재 | requestJumpTo | 무시 | component | Med | pages/label/LabelingPage.tsx:346 |
| TC-FE-045 | 저장 후 이동 — 실패 시 취소 | 저장 실패 | handleNavSaveAndMove | 에러+현 프레임 유지 | component | High | pages/label/LabelingPage.tsx:353-382 |
| TC-FE-046 | 저장 안 함 이동 — dirty 폐기 | navGuardTarget | handleNavDiscardAndMove | clearDirty 후 이동 | component | Med | pages/label/LabelingPage.tsx:383-400 |
| TC-FE-047 | 프레임 전환 시 setLabels 전체 교체 | srcSn 변경 | data effect | setLabels+dirty 초기화 | component | High | pages/label/LabelingPage.tsx:404-427 |
| TC-FE-048 | 같은 프레임 refetch는 dirty 있으면 미덮음 | dirty>0, 백그라운드 | data effect | 서버 라벨로 안 덮음 | component | High | pages/label/LabelingPage.tsx:423-426 |
| TC-FE-049 | 보류 추적 drain 병합 | pendingTracks | srcSn effect | mergeAutoLabels + info 토스트 **"보류된 AI 추적 N건 적용됨"** | component | High | pages/label/LabelingPage.tsx:433-446 |
| TC-FE-050 | 언마운트 시 store reset | 이동 | unmount | reset() | component | Med | pages/label/LabelingPage.tsx:449-453 |
| TC-FE-051 | AI 탐지 팝업 매핑 라벨만 선택 | detectCandidates 혼합 | AiToolModal | 미매핑 disabled+"미매핑", canRun=mappedCount>0 | component | High | features/label/components/AiToolModal.tsx:147,183,250-272 |
| TC-FE-052 | AI 탐지 mock 응답 자동적용 차단 | res.message 존재 | runAiTool detect | 경고 토스트, 병합 안 함 | security | High | pages/label/LabelingPage.tsx:695-698 |
| TC-FE-053 | AI 탐지 결과 작업본 병합(중복 스킵) | 정상 응답 | runAiTool | mergeAutoLabels+"N건 적용됨" | component | High | pages/label/LabelingPage.tsx:700-707 |
| TC-FE-054 | 폴리곤 shape → "AI 분할" 라벨 | POLYGON | runAiTool | 토스트 kind="AI 분할" | component | Med | pages/label/LabelingPage.tsx:706 |
| TC-FE-055 | 트랙 모드 — 객체 선택 유도 | mode=track | runAiTool | TRACK 도구+안내 | component | Med | pages/label/LabelingPage.tsx:818-836 |
| TC-FE-056 | 추적 결과 현재/미래 프레임 분리 | tracked 혼합 | handleTracked | 현재=즉시병합, 미래=stash | component | High | pages/label/LabelingPage.tsx:764-807 |
| TC-FE-057 | 부분 추적 실패 경고 | partial=true | handleTracked | warning 토스트 `` `${applied}/${total} 프레임만 추적됨 (일부 실패)` `` (total=`nextSrcSns.length \|\| tracked.length`) | component | Med | pages/label/LabelingPage.tsx:788-796 |
| TC-FE-058 | nextSrcSns 계산(현재 이후) | frameIdx | useMemo | slice(frameIdx+1) | component | Med | pages/label/LabelingPage.tsx:753-757 |
| TC-FE-059 | SAM2 추적 청크 50개 상한 정합 | nextSrcSns>50 | sam2TrackAllChunks | 50개 이하 분할(BE @Size max=50) | security | High | features/label/api.ts:sam2TrackAllChunks |
| TC-FE-060 | SAM2 추적 stale 가드(프레임 전환) | 응답 전 전환 | 병합 직전 | requestedSrcSn≠현재면 폐기 (구현은 `onSuccess` 비교가 아니라 busy 토큰 `isAlive()` 로 판정 — 정정) | component | High | features/label/hooks/useSam2Track.ts:61,72-73,80-81 · features/label/hooks/useBusyTask.ts:113-118,127-139 |
| TC-FE-061 | SAM2 부분 실패 성공분만 병합 | ChunkError.partial | catch | 성공분 onTracked(partial=true) | component | Med | features/label/hooks/useSam2Track.ts:78-84 |
| TC-FE-062 | BBOX 추적 시드 외접박스 4점 확장 | BBOX 청크 | seedPolygon | 2점→4점(@Size min=3) | component | High | features/label/api.ts:toSeedPolygon |
| TC-FE-063 | 트랙 rename/삭제/분할 포털 차단 | portalMode | handleRename/Delete/Split | 조기 return(403 방어) | security | High | pages/label/LabelingPage.tsx:859,881,906 |
| TC-FE-064 | 잠금 영상 트랙 편집 차단 | isLocked | 각 핸들러 | 에러 토스트+미실행 | security | High | pages/label/LabelingPage.tsx:861-864,883-886,908-911 |
| TC-FE-065 | 트랙 rename 성공 후 invalidate | 정상 | mergeTracks | byVideo invalidate+토스트 | component | Med | pages/label/LabelingPage.tsx:866-868 |
| TC-FE-066 | 비식별 신고 성공 → 잠금+reset+무효화 | 신고 성공 | handleDeidentReportSuccess | reportedLock=true, store reset, `LABEL_KEYS.byVideo(srcSn)` invalidate. **★BE 는 라벨을 삭제하지 않는다(2026-07-27 보존 정책 반전)** — 재조회는 신고 게이트로 **412** 가 되어 "라벨 조회 실패"+서버 안내문 화면이 뜬다(빈 라벨 화면 아님) | security | High | pages/label/LabelingPage.tsx:529-537,1143-1163 · CLAUDE.md 비식별 누락 신고 |
| TC-FE-067 | 잠금 배너 노출 | LOCKED_FOR_REDEIDENT/reportedLock | 렌더 | role=status 배너. ⚠**3차 실측(2026-08-03)**: `reportedLock` 경로만 실제로 배너가 뜬다 — BE `LabelService:200` 은 잠금 시 `lockSttsCd="LOCKED"` 를 내려보내는데 FE 는 `'LOCKED_FOR_REDEIDENT'` 와 비교하므로(`LabelingPage.tsx:516`) **서버 잠금 경로에서는 배너·isLocked 가 영영 발화하지 않는다**(H-ISSUE-41). 응답을 `LOCKED_FOR_REDEIDENT` 로 바꿔 넣으면 배너가 정상 표시됨(대조군 확인) | component | Med | pages/label/LabelingPage.tsx:1246-1255,516 · backend LabelService.java:200 |
| TC-FE-068 | 비식별 신고 버튼 — RAW 프레임 disabled | frameImageType='RAW' | 렌더 | disabled | security | Med | pages/label/LabelingPage.tsx:1195-1206 |
| TC-FE-069 | 비식별 신고 버튼 포털 미노출 | portalMode | 렌더 | canReportDeident=false → null | security | High | pages/label/LabelingPage.tsx:130,1195-1196 |
| TC-FE-070 | 검수제출 버튼 WORKER만 | isWorker+data | 렌더 | submitButton | component | High | pages/label/LabelingPage.tsx:1207-1242 |
| TC-FE-071 | 상태별 제출 차단(REVIEW_PENDING/REVIEWING) | 비제출가능 | 렌더 | disabled+hint title | component | High | pages/label/LabelingPage.tsx:182-214 |
| TC-FE-072 | APPROVED 재검수 라벨 | COMPLETED | 렌더 | "재검수 제출" 문구 | component | Med | pages/label/LabelingPage.tsx:203-204 |
| TC-FE-073 | 제출 취소 버튼 REVIEW_PENDING만 | canCancelSubmit | 렌더 | "제출 취소" | component | Med | pages/label/LabelingPage.tsx:201,1210-1222 |
| TC-FE-074 | 검수제출 성공 → /task 이동+토스트 | submitForReview | onSuccess | "검수 제출 완료"+navigate | component | High | pages/label/LabelingPage.tsx:169-175 |
| TC-FE-075 | X 닫기 dirty 시 3옵션 모달 | dirtyCount>0 | handleClose | closeConfirm 모달 | component | High | pages/label/LabelingPage.tsx:942-948 |
| TC-FE-076 | beforeunload dirty 경고 | dirtyCount>0 | 탭 닫기 | native 경고 | component | Med | pages/label/LabelingPage.tsx:985-994 |
| TC-FE-077 | 우측 탭 — 메타/이슈 내부 채널만 | portalMode | 렌더 | 메타·이슈 탭 미노출 | security | High | pages/label/LabelingPage.tsx:475-478,1405-1473 |
| TC-FE-078 | 이슈 탭 미해소 배지 카운트 | unresolvedInquiries>0 | 렌더 | danger 배지+aria-label | component | Med | pages/label/LabelingPage.tsx:1461-1469 |
| TC-FE-079 | 메타 탭 — 촬영환경/개인정보(영상)/개인정보(프레임)/설명/시계열메타/이벤트 패널 | rightTab=meta, 내부 | 렌더 | **6개 패널**(순서: `EnvironmentMetaPanel`→`VideoPrivacyMetaPanel`→`FramePrivacyMetaPanel`→`FrameDescriptionPanel`→`TimeseriesSidePanel`→`EventAnnotationPanel`). *구 기대값 "5개 패널"은 폐기 — 커밋 `0d290c4e`(영상 단위 개인정보 메타 화면)로 `VideoPrivacyMetaPanel` 이 2번째에 신설됨. 3차 실측 헤딩: 촬영환경 / 개인정보(영상) / 개인정보(프레임) / 프레임 설명 / 시계열 메타 / 이벤트 어노테이션.* ★**시계열 메타 패널은 텍스트 수정·저장 전용**이며 검토(승인/반려) 표면이 없다(2026-08-03 확정, TC-FE-276~278). 승인/반려 UI 가 있는 것은 **이벤트 어노테이션 패널뿐** | component | High | pages/label/LabelingPage.tsx:1494-1505 |
| TC-FE-080 | 뷰(zoom/pan) 유지 vs 리셋 | 동일영상+동일해상도 | handleImageSize | shouldResetView false → 유지 | component | Med | pages/label/LabelingPage.tsx:307-319 |
| TC-FE-081 | 붙여넣기 실측 dims clamp | frameNaturalSize | onPasteLabels | imageWidth/Height clamp | component | Med | pages/label/LabelingPage.tsx:1029-1047 |
| TC-FE-082 | 복사 — 빈 선택 no-op 토스트 | 라벨 없음 | onCopyLabels | "복사할 라벨이 없습니다." | component | Low | pages/label/LabelingPage.tsx:1020-1027 |
| TC-FE-083 | 잠금 영상 붙여넣기 차단 | isLocked | onPasteLabels | 에러+미실행 | security | Med | pages/label/LabelingPage.tsx:1030-1033 |
| TC-FE-084 | 저장 되돌리기 확인 모달 | 히스토리 카드 | handleRevertRequest | ConfirmDialog | component | Med | pages/label/LabelingPage.tsx:645-647 |
| TC-FE-085 | 되돌릴 항목 없음 경고 | reverted=0 | confirmRevert | warning 토스트 **"되돌릴 항목이 현재 작업본에 없습니다."** *(구 기대문구 "되돌릴 항목이 없습니다" 는 실제 문자열과 불일치 — 3차 실측 정정)* | component | Low | pages/label/LabelingPage.tsx:651-680 |
| TC-FE-086 | 캔버스 lazy 마운트(konva 분리) | currentFrame | 렌더 | CanvasShell Suspense | component | Med | pages/label/LabelingPage.tsx:89-91,1352-1372 |
| TC-FE-087 | 히스토리 인라인 패널 내부만 | historyOpen+!portalMode+srcSn 존재 | 렌더 | `inline-history-panel` 안에 `HistoryPanel`(변경이력·버전 탭). ★2026-08-03 부로 **버전·diff·롤백의 유일한 진입점**이다(구 전용 페이지 `/history/:videoId` 삭제 — TC-FE-299) | component | **High** | pages/label/LabelingPage.tsx:1574-1586 |
| TC-FE-197 | 저장 409 → 충돌 다이얼로그(작업 보존) (신규) | 다른 사용자가 먼저 저장 | handleSave → ApiError status=409 | 에러 토스트가 아니라 **"다른 사용자가 먼저 저장했습니다"** ConfirmDialog. **dirty 유지**(내 작업 미폐기), 확인=최신 라벨 재조회(clearDirty+refetch), 취소="내 작업 유지" | component | High | pages/label/LabelingPage.tsx:586-591,1590-1599 |
| TC-FE-198 | 저장 요청에 labelVersion 동봉 (신규) | 조회 응답 labelVersion 존재 | PUT /frames/{srcSn}/labels | body 에 `labelVersion` 포함(값 없으면 필드 자체 생략 → BE 하위호환 skip 경로) | security | High | features/label/api.ts:putLabels · features/label/types.ts:labelVersion |
| TC-FE-199 | 연속 저장 시 캐시 버전 우선(자기 409 방지) (신규) | 1회차 저장 성공 직후 2회차 | handleSave 연속 2회 | 성공 콜백이 `setQueryData` 로 캐시 버전을 동기 갱신 → 2회차는 **최신 버전** 전송(렌더 클로저 값 아님), 409 미발생 | component | High | features/label/hooks/useUpdateLabels.ts:56-68,79-84 · features/label/hooks/__tests__/useUpdateLabels.test.tsx |
| TC-FE-200 | 파생영상 — 비식별 신고 버튼 사전 비활성 (신규) | `VideoDetailResponse.derivative=true` | 라벨링 진입 | 버튼 disabled + title/aria-label 에 "증강·해상도 변환으로 만든 파생영상이라 …" 사유. **원본으로 유도하지 않고 부모 rawSn 도 표시하지 않는다** | security | High | pages/label/LabelingPage.tsx:138-147,1202 · features/label/components/DeidentReportButton.tsx:41,50,145-148 |
| TC-FE-201 | 신고 412 — 서버 안내문 그대로 노출 (신규) | 화면이 파생 여부를 모름(구 응답) | 신고 제출 → 412 | `resolveApiMessage` 로 **BE 안내문**을 폼 내 role=alert 에 표시(구: INTERNAL_ERROR 일반문구로 대체됨) | security | High | features/label/components/DeidentReportButton.tsx:118-125,179-186 · features/label/__tests__/DeidentReportButton.test.tsx |
| TC-FE-202 | 이벤트 어노테이션 cot 객체형 정규화 (신규) | `cot={"1단계":"…","2단계":"…"}` | toCaptionRows | 배열/객체 양형 모두 3단계 배열로 정규화(크래시 없음, 키 순서 유지) | component | High | features/label/api/eventAnnotation.ts:normalizeCot · features/label/components/eventAnnotationForm.ts:toCaptionRows |

### 장시간 작업(busy) 편집 차단 · 진행 표시 · 취소 — 2026-07-31 신설

> 대상: AI 탐지 / AI 분할 / AI 추적 / 저장 / 불러오기 5종이 **단일 배타 축**. 규칙 전문 → [v2-wiki 10 §10.6](../v2-wiki/10-labeling.md). **차단은 요청 거부가 아니라 입력 차단**이며, **취소는 클라이언트 결과 폐기이지 서버 중단이 아니다**.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-261 | busy 중 캔버스·툴바·프레임이동·실행버튼 차단 (신규) | busy(SAVE/AI\*) 진행 중 | 캔버스 그리기·선택·삭제 / 슬라이더·필름스트립·버튼 / 저장·검수제출·AI 실행 | 전부 무반응(캔버스 readOnly, 버튼 disabled). **입력 단계에서** 막혀 드래그가 시작되지 않는다 | component | High | features/label/__tests__/editBlocking.test.tsx · stores/useLabelStore.ts:useIsEditBlocked |
| TC-FE-262 | 되돌리기·버전 롤백·비식별 신고도 차단 (신규) | busy 진행 중 | 각 버튼 | 실행되지 않음(신고 성공 시 `reset()` 이 진행 작업을 조용히 취소하던 경로 차단) | security | High | features/label/__tests__/editBlocking.test.tsx:273(신고),222·254(ESC·취소) · features/label/components/LabelHistoryPanel.tsx:104,207-211(버전 롤백 — ⚠ 롤백 축은 자동 테스트 0건, 정적+실동작만) |
| TC-FE-263 | 단축키 차단은 fail-closed (신규) | busy 시작 커밋과 리렌더 **사이**(렌더 값은 아직 blocked=false) | `D`/`B`/`R`/`Ctrl+S` keydown | 전부 무시. 판정 = 렌더 값 **OR 실시간 store**(`isEditBlockedNow`) — 렌더 값 단독 판정이던 창을 닫음 | security | High | features/label/hooks/useLabelingShortcuts.ts:196(fail-closed OR 판정),203(ESC),227(키맵 차단) · features/label/__tests__/shortcutsFailClosed.test.tsx |
| TC-FE-264 | 300ms 초과부터 진행 오버레이 (신규) | 저장/AI 작업 진행 | 지연 창 안 / 초과 | <300ms 미표시(즉시 그리기 깜빡임 방지), 초과 시 **작업명 + 경과 초 + 취소 버튼**. 문구에 모델명(YOLO/SAM/SAM2)·식별자·경로 없음 | component | High | features/label/components/BusyOverlay.tsx:48-67,125-131 · features/label/busyPolicy.ts:20-49 |
| TC-FE-265 | 취소 = 결과 폐기(서버 중단 아님) (신규) | 오버레이 표시 중 | 취소 버튼 클릭 / Enter·Space / ESC | busy 즉시 해제 + 편집 복귀. **취소 후 도착한 응답은 같은 프레임이어도 미반영**(세대 토큰), dirty 유지 | component | High | features/label/hooks/useBusyTask.ts:120-165 · features/label/busyPolicy.ts:124-136 |
| TC-FE-266 | ESC 취소는 AI 분할 확정 큐도 비운다 (신규) | 지연 창에서 Enter 로 확정 큐잉 후 ESC | busy 해제 | 큐잉된 확정이 **자동 발사되지 않는다**(취소와 정반대 동작 차단). 누적점은 보존 | component | High | features/label/canvas/layers/OverlayLayer.tsx:354-392(ESC 분기 373-391 · `setPendingConfirm(false)` 387) · .../__tests__/OverlayLayerSegmentBusy.test.tsx:251 |
| TC-FE-267 | busy 5분 fail-safe 자동 해제 (신규) | 응답 누락 | 5분 경과 | busy 자동 해제(화면 영구 잠금 방지). 뒤늦게 도착한 결과는 토큰 사망으로 폐기 | component | Med | features/label/hooks/useBusyTask.ts:13,145-151 |
| TC-FE-268 | 메타 편집은 busy 와 독립(의도 고정) | busy 진행 중 | 프레임 설명·촬영환경·개인정보 메타·이벤트 어노테이션·**시계열 메타 텍스트 수정·저장** | **차단되지 않고 편집·저장된다**. 라벨 작업본과 공유 상태가 없다 — 깨지면 회귀가 아니라 정책 변경. ⚠ 5번째 항목은 구 "시계열 메타 **검수 승인**"에서 **텍스트 수정·저장**으로 정정됐다(2026-08-03 검토 UI 제거, `POST /meta/{sn}/approve` 호출 자체가 사라짐) | component | High | features/label/__tests__/metaEditBusyIndependence.test.tsx:46,71,102,135,173 |
| TC-FE-269 | 툴바 버튼 포커스 중 Space 는 팬이 아니다(표준 동작 고정) (신규) | 툴바 버튼 클릭 직후(포커스 유지) | Space | 팬 홀드 미발동 + **그 버튼이 활성화**된다(APG). 활성화가 포커스를 훔치지 않으며, 포커스가 버튼을 떠나면 Space 팬이 정상 복귀 | a11y | Med | features/label/canvas/CanvasShell.tsx:66-99(활성화 대상 판정),180-187(Space keydown 가드) · features/label/canvas/__tests__/CanvasShellSpaceActivation.test.tsx:153,169 |
| TC-FE-270 | 크로스탭 동시성은 busy 범위 밖 (신규) | 다른 탭/사용자가 먼저 저장 | 저장 | FE busy 는 **같은 탭 한정**. 교차 수정은 서버 낙관적 잠금 409 → 충돌 다이얼로그(TC-FE-197)가 담당 | security | High | pages/label/LabelingPage.tsx 저장 catch(409 → setSaveConflictMessage) · docs/v2-wiki/10-labeling.md §10.6 |

### 메타 탭 시계열 메타 — 검토(승인/반려) UI 제거 — 2026-08-03 신설

> **결정 1 (2026-08-03 사용자 확정, 커밋 `80171828`)**: 라벨링 화면(SC-005) 우측 '메타' 탭의 시계열 메타 패널(`TimeseriesSidePanel`)에서
> **검토 상태 배지 · 승인/반려 버튼 · 반려 사유 입력을 제거**했다. 남은 것은 **텍스트 수정·저장뿐**이다.
>
> - **구 정책 → 폐기**: "REVIEWER 가 라벨링 화면에서 시계열 메타를 승인/반려한다"는 동선은 폐기됐다. 실사용상 승인 완료 영상은 검토행이 전부 `APPROVED` 라
>   "검토 상태 승인됨" 줄만 메타 개수만큼 반복됐고, `metaKey` 미표시로 어느 메타의 상태인지 식별조차 불가능했으며, 상태 배지에 역할 가드가 없어 WORKER 에게도 노출됐다.
> - **BE 는 존치 — 케이스를 지우지 말 것**: `POST /v1/meta/{metaReviewSn}/approve|reject`(`MetaController.java:81-106`)와 `LS_DATA_META_REVIEW` 는 그대로다. **FE 진입점만 없다.**
>   FE 클라이언트(`approveMetaReview`/`rejectMetaReview`)와 훅(`useMetaReview`)은 제거됐다.
> - 검토 상태 확정의 **유일한 경로는 영상 검수 승인 시 BE 자동 동결**(`MetaService.autoApproveOnVideoApproval` → [TC-REVIEW-016](D-review-version-notify.md))이며, 데이터마트 `V_COMPLETED_META` 의 `RVW_STTS_CD='APPROVED'` 게이트는 불변이다.
> - **검수 화면(SC-019)의 읽기 전용 `ReviewMetaPanel` 상태 배지는 유지**된다 — 이번 제거 대상이 아니다.
> - 규칙 전문 → [v2-wiki 09 §9.3](../v2-wiki/09-vlm-timeseries.md)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-276 | REVIEWER + PENDING 검토행이어도 검토 UI 미노출 (신규) | REVIEWER·INTERNAL, `dataMetaReviewSn` + `reviewStatus='PENDING'` 인 메타 | 메타 탭 렌더 | textarea·저장 버튼은 있고 `ts-review-actions`/`ts-review-status-*`/`ts-approve-*`/`ts-reject-*`/`ts-reject-reason-*`·"검토 상태"·"승인"·"반려" 는 **전부 없음**. 깨지면 회귀가 아니라 정책 변경. ⚠ 2026-08-03 후속 커밋(`5c10c0cd`)에서 패널이 **세그먼트(metaKey)별 textarea** 로 재구현됐으나(구 단일 textarea 폐기) 검토 UI 부재 결론은 불변 | component | High | features/label/components/TimeseriesSidePanel.tsx:55-58,124-177 · features/label/components/\_\_tests\_\_/TimeseriesSidePanel.test.tsx:360-378 |
| TC-FE-277 | APPROVED 검토행이어도 "승인됨" 배지 미노출 (신규) | 승인 완료 영상(실사용 대다수) | 메타 탭 렌더 | 배지 없음. 값은 그대로 편집 가능 — 배지만 메타 개수만큼 반복되던 표면 제거 | component | High | TimeseriesSidePanel.tsx:124-177 · \_\_tests\_\_/TimeseriesSidePanel.test.tsx:381-409 |
| TC-FE-278 | 검토행이 붙어 있어도 텍스트 수정·저장은 회귀 없음 (신규) | 검토행 보유 메타 | 텍스트 수정 → 저장 | `PUT /frames/{srcSn}/meta` 1회. **편집한 세그먼트(metaKey)만** 전송(0건이면 `manual-timeseries` 신규 슬롯), 원본과 같거나 공백만인 세그먼트는 저장 대상에서 제외 | component | High | TimeseriesSidePanel.tsx:79-122 · \_\_tests\_\_/TimeseriesSidePanel.test.tsx:412-430 |

### 도구 클릭 → 라벨 선택 모달 → 드로잉 · 좌측 라벨 패널 폐지 — 2026-08-03 신설

> **결정 3 (2026-08-03 사용자 확정, 커밋 `d8a7a2cc`)**: 라벨링 조작 흐름을 **"도형 도구 클릭 → 라벨 선택 모달 → 라벨 확정 후 드로잉"** 으로 바꿨다.
>
> - **구 UI → 폐기**: 좌측 **상시 라벨 패널(`LabelSidebar`)** 은 컴포넌트·테스트째로 삭제됐고, 거기 있던 **전역 1~9 라벨 선택 단축키도 `SHORTCUT_KEYMAP` 에서 제거**됐다(단축키 도움말에서도 빠짐).
>   패널이 없으면 전역 1~9 는 아무 시각 피드백 없이 "다음 도형의 라벨"을 바꾸는 조용한 상태 변경이 되기 때문이다. 1~9 는 **모달 안에서만** 동작한다.
> - **라벨 선택 목록의 출처는 라벨 마스터(`LS_LABEL`) 전체**다. **프리셋(`LS_LABEL_PRESET_CODE`)은 오토라벨링 전용**이라 이 목록에 쓰지 않는다 — 프리셋을 소스로 되돌리지 말 것.
> - **판정은 `useToolLabelPicker` 한 곳**(도구 전이 감시)이다. 툴바 클릭·키보드 단축키가 각자 모달을 띄우게 배선하면 한쪽이 반드시 뒤처진다(이 저장소의 "진입점마다 정책 복제" 결함 패턴 → [state-gate 단일 진입점 규칙]).
> - 규칙 전문 → [v2-wiki 10 §10.2.1](../v2-wiki/10-labeling.md) · [04 SC-005](../v2-wiki/04-screens-ia.md)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-279 | 도형 도구 클릭 시 라벨 선택 모달 노출 (신규) | 라벨링 진입(SELECT 활성) | 툴바에서 BBOX/POLYGON/AI분할/스켈레톤 클릭 | `LabelPickerModal` 오픈 + 안내 문구에 도구명. **라벨을 고르기 전에는 캔버스 드로잉이 시작되지 않는다** | component | High | features/label/hooks/useToolLabelPicker.ts:64-84 · pages/label/LabelingPage.tsx:1329-1335 · features/label/\_\_tests\_\_/LabelingPageLabelPicker.test.tsx:124 |
| TC-FE-280 | 취소 시 도구 미활성 + 이전 도구 복귀 (신규) | 모달 열림 | 취소 버튼/ESC/닫기 | `activeTool` 이 직전 도구로 되돌아가고, **복귀 전이는 모달을 다시 띄우지 않는다**(`suppressRef` 1회 억제 — 이전 도구도 라벨 필요 도구일 수 있어 없으면 무한 재노출) | component | High | useToolLabelPicker.ts:111-119,69-73 · LabelingPageLabelPicker.test.tsx:134 |
| TC-FE-281 | 라벨 확정 시 도구 활성 + activeLabelId 기록 (신규) | 모달에서 라벨 클릭 | confirm | `setActiveLabelId(labelId)` + 모달 닫힘 + 해당 도구로 드로잉 가능 | component | High | useToolLabelPicker.ts:102-109 · LabelingPageLabelPicker.test.tsx:148 |
| TC-FE-282 | 같은 도구로 연속 드로잉 시 모달 재노출 없음 (신규) | 도구 유지 상태 | 도형을 여러 개 연속 작성 | 재노출 조건 = **도구 전이 1회**. 도형마다 뜨지 않고 마지막 선택 라벨이 유지된다 | component | High | useToolLabelPicker.ts:11-12,64-84 · LabelingPageLabelPicker.test.tsx:163 |
| TC-FE-283 | 같은 도구 **재클릭** 은 라벨 교체 동선 (신규) | 이미 활성인 도형 도구 | 그 툴바 버튼을 다시 클릭 | 전이가 없어 감시로는 못 잡히므로 `requestTool` 이 직접 모달을 연다(라벨을 바꿀 유일한 동선). 라벨 불필요 도구 재클릭은 no-op | component | High | useToolLabelPicker.ts:86-100 · pages/label/LabelingPage.tsx:1344 |
| TC-FE-284 | 라벨을 만들지 않는 도구는 모달 미노출 (신규) | 선택/이동(팬)/AI 추적/마스크 브러시·지우개/삭제/실행취소 | 도구 전환·버튼 클릭 | 모달 없음. 대상은 `LABEL_REQUIRED_TOOLS` 4종(BBOX·POLYGON·SAM_SEGMENT·KEYPOINT)뿐 — `OverlayLayer` 가 `resolveDefaultLabel` 로 새 라벨 classId/className 을 확정하는 도구 집합과 동일 | component | High | useToolLabelPicker.ts:27-36,75-79 · LabelingPageLabelPicker.test.tsx:187 |
| TC-FE-285 | 진입 경로 무관 단일 판정(툴바 = 단축키) (신규) | 단축키 B/P/G/K 로 도구 전환 | keydown | 툴바 클릭과 **동일한 모달**이 뜬다. 판정은 `activeTool` 전이 감시 1곳이라 진입점이 늘어도 정책이 갈리지 않는다 | security | High | useToolLabelPicker.ts:6-9,64-84 · LabelingPageLabelPicker.test.tsx:196 |
| TC-FE-286 | 목록 = 활성 라벨 마스터 전체(프리셋 아님) (신규) | 활성 마스터 N건 + 프리셋 존재 | 모달 렌더 | `useLabelMasters`(`GET /v1/manage/labels`)만 조회하고 **프리셋 API 는 호출하지 않는다**. `useYn='Y'` 만, 정렬 `sortNo asc → labelId asc` | component | High | features/label/components/LabelPickerModal.tsx:6-8,52-63 · components/\_\_tests\_\_/LabelPickerModal.test.tsx:52,65 |
| TC-FE-287 | 이름 검색 필터 + 결과 0건 안내 + 재오픈 초기화 (신규) | 마스터 다수 | 검색어 입력 / 재오픈 | 표시명 부분일치(대소문자 무시) 필터, 0건이면 "검색 결과가 없습니다"(`aria-live`), 모달을 다시 열면 검색어 초기화(이전 검색어로 빈 목록처럼 보이는 것 방지) | component | Med | LabelPickerModal.tsx:47-50,65-69,141-145 · LabelPickerModal.test.tsx:74 |
| TC-FE-288 | 1~9 는 모달 전용 — 전역 키맵에서 제거 (신규) | 라벨링 화면(모달 닫힘) | 전역 `1` keydown | `activeLabelId` **불변**(전역 미발화). 전역 `SHORTCUT_KEYMAP` 에 숫자 바인딩이 0건이고, 순번 선택은 모달 자체 리스너가 처리 | security | High | features/label/hooks/labelingKeymap.ts:34-37 · LabelPickerModal.tsx:72-91 · features/label/\_\_tests\_\_/useLabelingShortcuts.numberKeys.test.tsx:61,66 |
| TC-FE-289 | 모달 검색창 입력 중 숫자키는 선택으로 동작하지 않음 (신규) | 검색 input 포커스 | `1` 입력 / 범위 밖 숫자 | 검색어에 입력될 뿐 선택 미발화(INPUT/TEXTAREA/contentEditable 가드). 목록 범위 밖 숫자는 무시 | component | Med | LabelPickerModal.tsx:74-88 · LabelPickerModal.test.tsx:114,123 |
| TC-FE-290 | 마스터 색상은 `#RRGGBB` 검증 후에만 inline style 주입 (신규) | 색상값이 미검증 문자열 | 모달 렌더 | `safeHexColor` 통과 값만 `backgroundColor` 로 넘어간다(원문 문자열 직접 주입 없음). 검색어도 텍스트 노드/필터 값으로만 사용 — `dangerouslySetInnerHTML` 없음 | security | High | LabelPickerModal.tsx:14-16,59,165-170 · features/label/utils/labelColor.ts · LabelPickerModal.test.tsx:133 |
| TC-FE-291 | 좌측 상시 라벨 패널 폐지 (신규) | 라벨링 진입 | 화면 렌더 | 구 `LabelSidebar` 영역 없음(컴포넌트·테스트 파일 삭제). 라벨 선택 표면은 모달 하나 | component | High | pages/label/LabelingPage.tsx:1337-1345 · LabelingPageLabelPicker.test.tsx:118 |
| TC-FE-292 | KeypointGuide 는 우측 패널 최상단(탭 바깥) (신규) | 스켈레톤 배치 중 | 우측 탭 전환(객체↔메타↔이슈) | `keypoint-guide-slot` 이 탭 바 위 상시 영역이라 **어느 탭에서도 배치 가이드가 계속 보인다**(구 좌측 패널에서 이전) | component | Med | pages/label/LabelingPage.tsx:1401-1405 · LabelingPageLabelPicker.test.tsx:207 |

### 라벨명 표시 = 라벨 마스터 등록명 그대로 — 2026-08-03 신설

> **결정 4 (2026-08-03 사용자 재확정, 커밋 `e58aa086`)**: 라벨명은 **라벨 마스터(`LS_LABEL`)에 등록된 이름을 그대로** 표시한다.
>
> - ⚠ **이것은 결정 3의 일부를 뒤집은 것이다.** 직전 커밋 `d8a7a2cc` 는 `resolveLabelDisplayName` 이 **COCO 한글 사전(`COCO_LABEL_KO` 14건) + 레거시 `LABEL_CLASS_DEFS`** 로
>   라벨명을 한글로 치환하게 만들었으나, 다음 커밋에서 **치환 로직을 전부 걷어냈다**. 코드 사전은 마스터와 어긋나는 **두 번째 진실원**이 되고, 사전에 있는 라벨만 한글이라 화면이 오히려 뒤섞이기 때문.
>   한글로 보이길 원하면 **라벨 관리(SC-036)에서 마스터 이름을 한글로 등록**한다.
> - **구 정책 → 폐기**: "`person` 이 화면에 `사람` 으로 보인다" 류 기대결과는 무효다. `car`·`VEHICLE` 로 등록된 라벨이 **화면에도 그대로 보이는 것이 의도된 동작**이며, 결함으로 되돌려 사전 치환을 되살리지 말 것.
> - `COCO_LABEL_KO` 는 삭제하지 않았지만 **모듈 private 로 좁혀졌다** — 라벨 관리 화면의 COCO 매핑 select 옵션 표시 전용이다.
> - 함수 자체는 얇게 남겼다(지우면 표시 지점들이 각자 폴백·trim·필드 선택을 다시 정해 드리프트가 되살아남).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-293 | 마스터 등록명 그대로 표시(사전 치환 없음) (신규) | 마스터 이름이 `car`/`person`/`bus`/`traffic light` | 표시 | 화면에도 **동일 문자열**. 한글 사전·`LABEL_CLASS_DEFS`(`PERSON`→'사람' 등) 치환 **미적용**. 마스터에 한글로 등록된 이름은 그대로 한글 | component | High | features/label/utils/labelDisplayName.ts:1-29 · features/label/utils/\_\_tests\_\_/labelDisplayName.test.ts:15,21,29 |
| TC-FE-294 | 표시 지점 6곳이 같은 값을 보여준다 (신규) | 같은 라벨 | 각 화면 | 라벨 선택 모달·우측 '객체' 목록·객체 속성(드롭다운/읽기 필드)·AI 탐지 후보·라벨 변경 이력·포털 업로드 라벨링이 모두 `resolveLabelDisplayName` 경유 — 화면마다 다른 이름이 나오지 않는다. ⚠ 7번째 호출부 `components/LabelPanel.tsx:44` 가 있으나 **어디서도 import 되지 않는 사(死)코드**(구 `LabelSidebar` 잔재)라 화면 표시 지점 집계에서 제외한다 | component | High | LabelPickerModal.tsx:58 · ObjectClassTree.tsx:153 · ObjectAttributePanel.tsx:258,268 · AiToolModal.tsx:269 · LabelChangeDetail.tsx:127 · pages/portal/PortalUploadLabelingPage.tsx:328 |
| TC-FE-295 | 표시명이 저장·전송 payload 에 섞이지 않는다 (신규) | 라벨 저장 / SAM2 추적 요청 / 이벤트 어노테이션 | 요청 body | `obj_label`·Sam2Track `className` 등은 **원문 그대로**. 표시 전용 경계 유지(원래부터 치환 대상이 아니었고 이번에도 불변) | security | High | features/label/\_\_tests\_\_/labelDisplayNameNoPayloadLeak.test.tsx:51,55 |
| TC-FE-296 | 빈 값만 `-` 로 표시 — 그 외는 임의 대체 없음 (신규) | null/undefined/공백 문자열 / `__proto__`·`constructor` 같은 이름 | resolve | 빈 값 → `-`, 앞뒤 공백은 trim. 프로토타입 속성명이어도 **원문 문자열만 반환**(사전 조회가 없어 프로토타입 오염 경로 자체가 소멸) | component | Med | labelDisplayName.ts:17-29 · labelDisplayName.test.ts:41,45,51 |

## H-4. useLabelStore (Zustand)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-088 | setLabels 시 dirty/undo/redo/선택 초기화 | 세팅 | setLabels | 전부 초기화 | component | High | stores/useLabelStore.ts:486-495 |
| TC-FE-089 | addLabel dirty 마킹 | 추가 | addLabel | dirtyLabels 추가 | component | High | stores/useLabelStore.ts:497-503 |
| TC-FE-090 | 좌표 이동 shape별 | dx/dy | offsetShape | 타입별 shift(BBOX/POLYGON/KEYPOINT) | component | Med | stores/useLabelStore.ts:66-89 |
| TC-FE-091 | clampShape 경계 [0,w]/[0,h] | w/h | clampShape | clamp, 미지정 시 하한 0만 적용 | component | Med | stores/useLabelStore.ts:92-117 |
| TC-FE-092 | mergeAutoLabels 중복 스킵+개수 | 기존 존재 | mergeAutoLabels | 신규만 병합, added 반환 | component | High | features/label/__tests__/useLabelStore.mergeAuto.test.ts |
| TC-FE-093 | revertSaveEvent 역적용 | 변경 이력 | revertSaveEvent | 역적용 개수 반환 | component | Med | features/label/__tests__/useLabelStore.revert.test.ts |
| TC-FE-094 | pendingTracks stash/drain | 미래 프레임 | stash→drain | 진입 시 drain 병합 | component | High | features/label/__tests__/useLabelStore.pendingTracks.test.ts |
| TC-FE-095 | 라벨 표시/숨김 토글(세션) | selected | toggleLabelVisibility | hiddenLabelIds(dirty 무영향) | component | Med | features/label/__tests__/useLabelStore.visibility.test.ts |
| TC-FE-096 | 라벨 잠금 편집 no-op | lockedLabelIds | update | dirty/undo 미변화 | component | Med | features/label/__tests__/useLabelStore.lock.test.ts |
| TC-FE-097 | 클립보드 copy/paste(sourceRawSn) | 복사 | copy/pasteLabels | 개수 반환 | component | Med | features/label/__tests__/useLabelStore.clipboard.test.ts |
| TC-FE-098 | 키포인트 17종 배치/편집 | KEYPOINT | 배치 | keypoints 삼중값 | component | Med | features/label/__tests__/useLabelStore.keypoint.test.ts |
| TC-FE-099 | imageAdjust 밝기/대비/투명도(세션) | 조절 | setImageAdjust | 영속 안 함 | component | Low | features/label/__tests__/useLabelStore.imageAdjust.test.ts |
| TC-FE-100 | activeLabel 파생 프리셋 기본 | 활성 라벨 | activeLabelId | resolveDefaultLabel | component | Low | features/label/__tests__/useLabelStore.activeLabel.test.ts |
| TC-FE-101 | clampPan 스케일 기반 뷰 제한 | zoom/pan 드래그 | CanvasShell 드래그→setPan | canvasGeometry clampPan 위임(클램프는 CanvasShell 이 store 유틸 `clampPan` 으로 계산해 `setPan` 에 결과를 넘긴다 — `setPan` 자체는 raw setter) | component | Med | stores/useLabelStore.ts:4,386-406 · features/label/canvas/CanvasShell.tsx:301-320 |

## H-5. 좌표 변환/캔버스 유틸 (canvas scale 함정)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-102 | translateToCanvas 이미지→캔버스 | geom.scale/left/top | 변환 | x*scale+left | component | High | features/label/canvas/utils/coordinateTransformer.ts:43-51 |
| TC-FE-103 | translateFromCanvas 왕복 정합 | 동일 geom | to→from | 원 좌표 복원(오차 내) | component | High | features/label/canvas/utils/coordinateTransformer.ts:57-65(3차 정정 — 구 `:57-61`은 회전역변환·return 문 누락) |
| TC-FE-104 | clampToImage 경계 clamp | 캔버스 밖 | clampToImage | 경계로 clamp | component | Med | features/label/canvas/utils/coordinateTransformer.ts:132 |
| TC-FE-105 | computeWrappingBox 외접박스 | points | compute | min/max 박스 | component | Med | features/label/canvas/utils/coordinateTransformer.ts:106 |
| TC-FE-106 | rotate2DPoints 회전 유틸 | 각도 | rotate | 회전 좌표 | component | Low | features/label/canvas/utils/coordinateTransformer.ts:71 |
| TC-FE-107 | maskRleConverter MASK↔RLE 변환(3차 정정 — 구 "MASK↔RLE↔Polygon"은 실제 미구현 기능을 표제에 포함한 카탈로그 오류. 실제로는 MASK↔RLE·imageData↔RLE 왕복만 제공, Polygon 변환 함수 없음 — 테스트 파일 자체 설명도 "MASK ↔ RLE 변환"이며 Polygon 언급 0건) | 마스크 | 변환 | 왕복 정합 | component | Med | features/label/canvas/utils/maskRleConverter.ts |
| TC-FE-108 | trackInterpolation 트랙 보간 | 두 키프레임 | interpolate | 중간 보간 | component | Med | features/label/canvas/utils/trackInterpolation.ts |
| TC-FE-109 | 캔버스 좌표 실측 naturalW/H 기준(하드코딩 제거) | 이미지 로드 | geometry | 실측 dims, 스트레치 없음 | component | High | pages/label/LabelingPage.tsx:287-319 |

## H-6. MarkingPage (마킹 화면)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-110 | 잘못된 rawSn 에러 | NaN | 진입 | "잘못된 영상 ID입니다." | component | Med | pages/MarkingPage.tsx:154-156 |
| TC-FE-111 | 비식별 미완료 진입 차단(백스톱) | isMarkingBlocked | 진입 | "비식별 완료 후 마킹이 가능합니다." role=alert | security | High | pages/MarkingPage.tsx:158-173 |
| TC-FE-112 | 스트림 항상 비식별본('N'/'F' 차단) | deIdntfYn≠Y | 판정 | 마킹 차단 | security | High | features/video/types.ts:isMarkingBlocked |
| TC-FE-113 | Space → 현재시각 마킹 | MANUAL | Space | addMark | component | High | pages/MarkingPage.tsx:139-141 |
| TC-FE-114 | Del/Backspace → 선택 마크 삭제 | 선택 마크 | Delete | removeSelectedMark | component | Med | pages/MarkingPage.tsx:142-144 |
| TC-FE-115 | Enter → 제출 | MANUAL | Enter | handleSubmit | component | High | pages/MarkingPage.tsx:145-147 |
| TC-FE-116 | INPUT/TEXTAREA/**SELECT** 포커스 시 단축키 억제 | 포커스 | 키 | 무시(SELECT 포함 — 구 2종에서 확장) | component | Med | pages/MarkingPage.tsx:136-137 |
| TC-FE-117 | AUTO intervalFrames<1 제출 차단 | 무효 | handleSubmit | mutate 미발생 | component | Med | pages/MarkingPage.tsx:118-119 |
| TC-FE-118 | MANUAL 마크 0건 제출 차단 | localMarks=[] | handleSubmit | mutate 미발생 | component | Med | pages/MarkingPage.tsx:124-125 |
| TC-FE-119 | 제출 중복 방지(Enter 연타) | isPending | handleSubmit | 두번째 무시 | component | High | pages/MarkingPage.tsx:116 |
| TC-FE-120 | 제출 성공 → clearMarks+/task | 성공(batchTriggered≠false) | onSuccess | "마킹이 제출되었습니다. 배치 처리가 시작됩니다."+navigate | component | High | pages/MarkingPage.tsx:61-77 |
| TC-FE-121 | 이벤트 유형 없는 영상 실패 토스트 | BE 400 | onError | extractBeMessage 에러 | component | Med | pages/MarkingPage.tsx:81-89 |
| TC-FE-122 | 스트림 401 만료 1회 재발급 | src 에러 | onSrcError | refetchStreamUrl 1회(무한루프 방지) | security | High | pages/MarkingPage.tsx:49-58 |
| TC-FE-123 | 영상 변경 시 마크 reset+duration fallback | rawSn 변경 | effect | reset()+durationSec=60 | component | Med | pages/MarkingPage.tsx:92-97 |
| TC-FE-124 | 배치단계 인디케이터 stages 있으면 노출 | videoDetail.stages | 렌더 | BatchStageIndicator | component | Med | pages/MarkingPage.tsx:181-187 |
| TC-FE-125 | 마크 목록 칩 선택 하이라이트 | localMarks | 클릭 | selectedMarkIndex | component | Low | pages/MarkingPage.tsx:221-241 |
| TC-FE-203 | frameIndex 는 서버 fps 로 계산 (신규) | `VideoDetail.fps=25` | Space 마킹(55초 지점) | `round(55×25)=1375` 전송 (구 30fps 하드코딩 시 1650 → BE 상한 초과 400). fps 없으면 폴백 30(BE `VideoFpsResolver.DEFAULT_FPS` 와 동일) | component | High | features/marking/markingFps.ts:21-31 · features/marking/__tests__/markingFps.test.ts · features/marking/components/VideoPlayer.tsx:13,54-55 |
| TC-FE-204 | 마킹 저장됐으나 배치 미시작 안내 (신규) | 응답 `batchTriggered=false` | 제출 성공 | **error 토스트** "마킹은 저장되었으나 배치가 시작되지 않았습니다. {batchSkipReason}" + /task 이동 (성공 문구로 위장 금지) | component | High | pages/MarkingPage.tsx:63-75 |
| TC-FE-205 | 배속 6단(0.25/0.5/1/1.5/2/4) 이산 선택 (신규) | 마킹 재생 | 배속 버튼 클릭 | `video.playbackRate` 반영 + 활성 버튼 강조. **자유 입력이 없어 클램프 불필요**(UNCERTAINTIES #23 확정), 기본 1x, 각 버튼 min-h-11(KRDS 터치 최소) | component | Med | features/marking/components/VideoPlayer.tsx:34,46-49,125-140 |
| TC-E2E-003 | MarkingPage 통합(스트림/배속/단축키) | reviewer/worker | 마킹 플로우 | 정상 | e2e | Med | pages/__tests__/MarkingPage.test.tsx |

## H-7. BatchStageIndicator (STAGE_ORDER 드리프트 함정)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-126 | stages 비면 null 렌더(하위호환) | stages=[] | 렌더 | null(상위 배지 폴백) | component | Med | components/common/BatchStageIndicator.tsx:66 |
| TC-FE-127 | 7종 단계명 매핑(DEIDENTIFY~INTERPOLATE) | canonical | 렌더 | 비식별/마킹/VLM/프레임추출/AI 탐지/AI 분할/보간 | component | High | components/common/BatchStageIndicator.tsx:16-24 |
| TC-FE-128 | 미지 코드 폴백 "처리중"(기술코드 미노출) | 매핑 없음 | 렌더 | "처리중"(YOLO/SAM2 미노출) | security | High | components/common/BatchStageIndicator.tsx:26-28,81 |
| TC-FE-129 | BE 배열 순서 그대로(FE 순서 가정 없음) | stages 순서 | 렌더 | 배열 순, name→라벨 매핑만 | component | High | components/common/BatchStageIndicator.tsx:12-15,70 |
| TC-FE-130 | 상태별 아이콘(DONE/PROGRESS/FAIL/PENDING) | status | 렌더 | 아이콘 aria-hidden | a11y | Med | components/common/BatchStageIndicator.tsx:30-57 |

## H-8. ReviewPage (검수 화면)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-131 | 잘못된 검수 ID 에러 | id NaN | 진입 | "잘못된 검수 ID" | component | Med | pages/ReviewPage.tsx:229-238 |
| TC-FE-132 | 로딩 상태 | isLoading | 진입 | "검수 로딩" | component | Low | pages/ReviewPage.tsx:240-255 |
| TC-FE-133 | 에러/review 없음 | error/!review | 진입 | "검수 정보를 불러올 수 없습니다" | component | Med | pages/ReviewPage.tsx:257-266 |
| TC-FE-134 | 진입 시 자동 startReview | REVIEW_PENDING | mount effect | doStart(→REVIEWING) | component | High | pages/ReviewPage.tsx:179-183 |
| TC-FE-135 | 캔버스 읽기 전용(좌표 마커 미사용) | 렌더 | 캔버스 | "읽기 전용" 배지 | component | High | pages/ReviewPage.tsx:294-311 |
| TC-FE-136 | 프레임 로딩 중 스피너 | framesLoading | 렌더 | loading prop 스피너 | component | Med | pages/ReviewPage.tsx:306 |
| TC-FE-137 | 승인 확인 다이얼로그 | 승인 클릭 | handleApproveClick | ConfirmDialog | component | High | pages/ReviewPage.tsx:206-208 |
| TC-FE-138 | 승인 성공 → /review 이동+토스트 | 성공 | onSuccess | noLabelConfirm 닫힘+"승인 완료"+navigate | component | High | pages/ReviewPage.tsx:155-160 |
| TC-FE-139 | 승인 실패 토스트(BE 문구) | REVIEW_NO_LABEL 아닌 실패 | onError | `extractBeMessage(err,'승인 실패')` 토스트 (구 고정문구 '승인 실패' 아님) | component | Med | pages/ReviewPage.tsx:174 |
| TC-FE-140 | 반려 사유 합성(의견+이슈) | reviewComment+pendingIssues | composeRejectReason | 입력+[전체의견]+[이슈N건] | component | High | pages/ReviewPage.tsx:65-84 |
| TC-FE-141 | 반려 성공 → /review 이동 | onSuccess | 반려 | navigate('/review') | component | Med | pages/ReviewPage.tsx:380 |
| TC-FE-142 | 프레임 상태색(미해소 문의=빨강) | INQUIRY 미해소 | 렌더 | inquirySrcSns → 빨강 | component | Med | pages/ReviewPage.tsx:126-139 |
| TC-FE-143 | 저장 프레임=연두(labels>0) | frames labels | 렌더 | savedSrcSns → 연두 | component | Low | pages/ReviewPage.tsx:141-149 |
| TC-FE-144 | currentFrameIdx 범위 밖 → 0 reset | idx 초과 | effect | setCurrentFrameIdx(0) | component | Med | pages/ReviewPage.tsx:186-192 |
| TC-FE-145 | 언마운트 store reset | 이탈 | unmount | clearSelection+idx=0 | component | Med | pages/ReviewPage.tsx:194-200 |
| TC-FE-146 | 증강여부/종류 표시 | 증강 파생 | 렌더 | ORGNL_RAW_SN/VMS_CLIP_ID 파생 | component | Med | features/review/components/ReviewMetaPanel.tsx · features/review/components/ReviewHeader.tsx |
| TC-FE-147 | 이슈 스레드 검수자 모드 | reviewer | 렌더 | IssueThreadPanel mode=reviewer | component | Low | pages/ReviewPage.tsx:355 |
| TC-FE-206 | 라벨 0건 승인 — 명시 확인 다이얼로그 (신규) | BE 409 `errorCode=REVIEW_NO_LABEL` | 승인 클릭 | "라벨이 없는 영상입니다" ConfirmDialog → 확인 시에만 `noLabelConfirmed:true` 로 재요청(평시 미전송) | component | High | pages/ReviewPage.tsx:170-172,224-227,387-397 |
| TC-FE-207 | 409 라도 사유가 다르면 다이얼로그 미노출 (신규) | 동시 승인 충돌 409(코드≠REVIEW_NO_LABEL) | 승인 클릭 | 확인 다이얼로그 **미노출**, BE 문구 토스트만. **상태코드가 아니라 `errorCode` 로 분기**(문자열 매칭 금지) | security | High | pages/ReviewPage.tsx:165-175 |
| TC-FE-208 | 검수 메타 cot 객체형 렌더 크래시 없음 (신규) | `cot` 가 `{"1단계":…}` 객체 | 메타 패널 렌더 | `normalizeCot` 로 배열화 후 필터 — 구 `(cand.cot ?? []).filter` TypeError 크래시 재발 없음 | component | High | features/review/components/ReviewMetaPanel.tsx:CaptionReadonly · features/label/api/eventAnnotation.ts:normalizeCot |
| TC-E2E-004 | 검수 플로우: 대기목록→시작→승인 | REVIEWER | serial | 진입→승인 완료 | e2e | High | e2e/specs/review-flow.spec.ts:5,12 |

## H-9. 관리 화면 (/manage/*)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-148 | 라벨 마스터 COCO 매핑 등록 | REVIEWER | dtctTypeCd 입력 | LabelMaster 매핑 반영 | component | High | pages/manage/LabelMasterManagePage.tsx |
| TC-FE-149 | 사용자/권한 관리 REVIEWER 전용 | REVIEWER | /manage/users | 목록/권한 관리 | security | High | router/__tests__/manageGuard.test.tsx:45-71(역할 분기 실검증. 구 근거 `UserManagePage.test.tsx` 는 REVIEWER 고정 검색필터 테스트라 접근제어 미검증 — H-ISSUE-82 반영 정정) |
| TC-FE-150 | 시스템 설정 CRUD(정밀도/YOLO conf·iou) | REVIEWER | /manage/settings | 설정 카드 | component | Med | features/sysconfig/components/{YoloConfigCard,PrecisionConfigCard}.tsx |
| TC-FE-151 | 프리셋 목록/편집(마스터 join) | REVIEWER | /manage/presets | 코드↔labelId | component | Med | pages/manage/PresetListPage.tsx |
| TC-FE-152 | 비식별 신고 관리 목록 | REVIEWER | /manage/deident-reports | OPEN/RESOLVED 목록 | component | Med | pages/manage/DeidentReportListPage.tsx |
| TC-FE-153 | 작업 배정/재배정 모달 | REVIEWER | AssignModal | WORKER 배정+이력 | component | High | features/task/components/AssignModal.tsx |
| TC-FE-154 | 재배정 이력 드로어 | REVIEWER | HistoryDrawer | 배정 이력 | component | Low | features/task/components/HistoryDrawer.tsx |
| TC-FE-155 | 대시보드 KPI 3카드+이벤트 분포 | 인증됨 | /dashboard | KpiCard 3종+차트 | component | Med | pages/DashboardPage.tsx |
| TC-FE-156 | 영상 현황 목록 검색/필터/URL 동기화 | 인증됨 | 검색어 | URL 쿼리 갱신. ★필터 값의 **실제 적용(서버 전송·결과 반영)** 은 2026-08-03 신설 → **[H-18](#h-18-영상-목록--영상-상세-sc-007--sc-009--2026-08-03-신설)** 참조(그 전에는 화면이 보내도 컨트롤러가 받지 않아 조용히 버려졌다) | component | Med | features/video/parseVideoListParams.ts |
| TC-E2E-005 | REVIEWER 워크플로우 전체 | REVIEWER | 각 진입 | 헤더/컨테이너 노출 | e2e | High | e2e/specs/reviewer-workflow.spec.ts:14-47 |
| TC-E2E-006 | 영상 목록 검색어 URL 동기화 | WORKER | 검색 | URL `cctvNameKeyword=` 갱신 | e2e | Med | e2e/specs/video-list.spec.ts:5,15 |

## H-10. 증강/해상도 파생 화면

> ★ 이번 회차: `GET /v1/augments/{jobId}/result` 가 **해상도 파생(`RESL_*`)에 한해** `results[]`·`framePairs[]` 를 채운다(좌=부모 비식별 / 우=파생 리스케일, 양쪽 비식별본). 이미지는 **API 경로 문자열**이라 인증 blob 로딩이 필수다. WINTER/NIGHT/RAIN 은 여전히 빈 `results`.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-157 | 증강 요청 화면(WINTER/NIGHT/RAIN+해상도) | REVIEWER | /augment | ProcessKindCard 4종(단일 선택) | component | Med | features/augment/types.ts:24-46 · pages/AugmentRequestPage.tsx |
| TC-FE-158 | 해상도 파생 요청 mutation | rawSn | changeResolution | 파생영상+VIDEO_KEYS invalidate | component | High | features/video/hooks/useResolutionDerivative.ts:17-27 |
| TC-FE-159 | 해상도 파생 증강 이력 배지 | resolutionTypes | JobCard | RESL 배지(info, 한글 해상도 라벨) | component | Med | features/augment/components/JobCard.tsx:81-89 |
| TC-FE-160 | 해상도 파생 accept/reject 없음(비검수) | `result.reviewable=false` | AugmentResultPanel(항목 패널, `AugmentResultPage`→`AugmentVideoSection`→`AugmentResultPanel` 로 위임) | **DecisionCard 미렌더**(채택/거부 버튼 부재, `showDecision=!isResolution && (...)`). 잡 이력 카드(JobCard)에는 원래 결정 액션이 없다 — 판정 지점은 결과 항목 패널의 `reviewable` | component | High | features/augment/components/AugmentResultPanel.tsx:123-128,237-252 |
| TC-FE-161 | 증강만 없고 파생만 → "파생" 태그 | types=[]+hasResolution | JobCard | 파생 구분자 | component | Med | features/augment/components/JobCard.tsx:90-98 |
| TC-FE-162 | 증강 채택 → ACCEPTED | PENDING | DecisionCard accept | ACCEPTED | component | High | features/augment/components/DecisionCard.tsx |
| TC-FE-163 | 증강 반려 사유 모달 | reject | RejectReasonModal | 사유+거부 | component | Med | features/augment/components/RejectReasonModal.tsx |
| TC-FE-164 | 거부 사유 표시(XSS escape) | rejectReason | DecisionCard | React 자동 escape | security | Med | features/augment/components/DecisionCard.tsx |
| TC-FE-209 | 해상도 파생 비교 이미지 인증 blob 렌더 (신규) | `RESL_*` 결과 + framePairs | 결과 항목 패널 진입 | FrameGrid12·SideBySideCompare 가 `authImages` 로 **Bearer blob** 요청(raw `<img src>` 아님) → 401 로 전부 깨지던 경로 제거. `AugmentResultPage`→`AugmentVideoSection`→`AugmentResultPanel` 로 렌더 위임됨(구 `AugmentResultPage` 직접 렌더 아님) | security | High | features/augment/components/AugmentResultPanel.tsx:168-197 · features/deident/components/FrameGrid12.tsx · features/deident/components/SideBySideCompare.tsx · features/augment/__tests__/AugmentResultPanel.*.test.tsx |
| TC-FE-210 | 12쌍 초과 시 프레임 페이저 노출 (신규) | `totalFramePairs>12` | 결과 항목 패널 | `augment-frame-pager` 노출, 다음 페이지로 나머지 쌍 접근(접근 불가 프레임 0). page/size 는 쿼리 파라미터로 전송(기본 `FRAME_PAGE_SIZE=12`) | component | High | features/augment/components/AugmentResultPanel.tsx:29-30,110-113,177-186 · features/augment/api.ts:getAugmentResult |
| TC-FE-211 | 탭(항목) 전환 시 프레임 페이지 리셋 (신규) | 항목 A(15쌍) 2페이지 → 항목 B(10쌍) 전환 | 항목 탭 클릭(구 "종류별 탭"에서 **항목(id) 단위 탭**으로 변경 — 같은 종류 중복요청도 각각 별개 탭) | framePage=0 리셋 → 빈 그리드에 갇히지 않음. 마운트 시점에는 리셋 안 함(직전 activeId 와 비교). 안전망으로 `framePage>0` 이면 페이저 유지 | component | High | features/augment/components/AugmentVideoSection.tsx:45-64 · features/augment/components/AugmentResultPanel.tsx:110-113 · features/augment/__tests__/AugmentResultPage.tabPaging.test.tsx |
| TC-FE-212 | 해상도 타입 라벨 매핑(undefined 해소) (신규) | `type='RESL_720P'` | 탭·슬롯 라벨 | "해상도 720p" 등 사람이 읽는 문구. 미지 코드는 `'증강'`, `RESL_` 접두는 `resolutionDerivativeLabel` 폴백 — 기술코드 노출 0 | component | High | features/augment/augTypeLabel.ts:30-36 |
| TC-FE-213 | 결과 본문 있으면 "외부연동 대기" 안내 미표시 (신규) | COMPLETED + results 존재 | 결과 화면 | `augment-result-completed-empty` 미노출, "증강 이미지 생성률" 카드 표시(구 "라벨 무결성" 명칭에서 변경 — 라벨을 검사한 값이 아님을 명확화). ⚠ **정정**: "비교 프레임 쌍"·생성률은 **현재 항목/프레임 페이지 스코프 값**이며 "페이징 전 전체 쌍 수"가 아니다(구 기대결과 반대로 정정 — `summary.pagePairs`/`loadedPairs` 는 페이지 스코프임을 코드 주석이 명시) | component | Med | pages/AugmentResultPage.tsx:263-296,339-421 |
| TC-E2E-007 | 증강 결과 PENDING 채택 → ACCEPTED | REVIEWER | 채택 | 상태 변경 | e2e | High | e2e/specs/augment-decision.spec.ts:4 |

## H-11. 포털 화면 (자산 업로드+수동 라벨링)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-165 | 이미지 확장자 검증(jpg/jpeg/png) | 비허용 | validateImageFiles | 제외+정책 안내 | security | High | features/portal/uploads/validation.ts:33-48(정정, `validateImageFiles` 본문 33-57 중 확장자 분기 38-42) |
| TC-FE-166 | 이미지 20MB 초과 제외 | 대용량 | validate | "20MB 초과" | component | Med | features/portal/uploads/validation.ts:10,44(정정 — `MAX_IMAGE_BYTES` 는 :10, 구 `:9` 는 주석 줄) |
| TC-FE-167 | 이미지 50장 상한 초과 잘라냄 | 초과 | validate | slice(50)+안내 | component | Med | features/portal/uploads/validation.ts:12,50-56 |
| TC-FE-168 | 이미지 업로드 성공 후 초기화 | 유효 | onUploadImages | selected/errors/input 초기화 | component | Med | pages/portal/PortalUploadPage.tsx:77-87 |
| TC-FE-169 | 영상 TUS 재개 업로드(mp4/mov/avi) | 영상 | onVideoStart | tus.start(`/portal/uploads/tus`) | component | Med | pages/portal/PortalUploadPage.tsx:89-100 · features/upload/hooks/useTusUpload.ts:36(정정 — `endpointBase` 옵션은 :36, 구 `:30` 은 JSDoc 줄) |
| TC-FE-170 | 삭제 확인+PROCESSING 버튼 비활성 | PROCESSING | 렌더 | disabled+title | component | Med | pages/portal/PortalUploadPage.tsx:103-104,310-311(정정 — `window.confirm` 은 :104, `disabled`/`title` 은 :310-311) |
| TC-FE-171 | 삭제 409 처리중 안내(내부 미노출) | BE 409 | deleteErrorMessage | "처리 중 자산 삭제 불가" | security | Med | pages/portal/PortalUploadPage.tsx:48-53 |
| TC-FE-172 | READY 자산만 라벨링 링크 | READY | 렌더 | /portal/uploads/:uldSn/label | component | Med | pages/portal/PortalUploadPage.tsx:296-306 |
| TC-FE-173 | FAILED 자산 실패 사유 표시 | FAILED | 렌더 | failRsnCn | component | Low | pages/portal/PortalUploadPage.tsx:288-292 |
| TC-FE-174 | 사용자 파일명 텍스트노드 렌더(XSS 방어) | 파일명 | 렌더 | 자동 escape | security | High | pages/portal/PortalUploadPage.tsx:280-281 |
| TC-FE-175 | 포털 업로드 라벨링 BBOX/POLYGON만 | READY | 진입 | CanvasShell, 오토라벨 없음 | component | Med | pages/portal/PortalUploadLabelingPage.tsx |
| TC-FE-176 | 포털 홈 데이터마트 영상 선택 | PORTAL_USER | /portal | 영상 목록+선택 | component | Med | pages/portal/PortalHomePage.tsx |
| TC-FE-177 | 포털 라벨링 저장(원본 미수정, 본인 적재) | 저장 | useSavePortalLabels | LS_PORTAL_USER_LABEL | security | High | features/portal/hooks/useSavePortalLabels.ts |
| TC-FE-271 | 포털 업로드 — 저장 중 진행 오버레이 + 취소 (신규) | 저장 PUT in-flight 300ms 초과 | 렌더 / 취소 버튼 | "저장 중" 오버레이(경과 초 + 취소). 취소 시 즉시 편집 복귀 + 오버레이 소멸 + **dirty 유지**(저장됨으로 취급하지 않음). 내부 라벨링과 **대칭** | component | High | pages/portal/PortalUploadLabelingPage.tsx(BusyOverlay 배선) · pages/portal/__tests__/PortalUploadLabelingBusy.test.tsx |
| TC-FE-272 | 포털 업로드 — 취소 후 도착 응답 미반영 (신규) | 취소 후 PUT 응답 도착 | 응답 처리 | `clearDirty` 등 성공 후처리 미실행(클라이언트 폐기 — 서버 처리 중단 아님) | security | High | features/portal/uploads/hooks/useSaveUploadLabels.ts:85-98 |
| TC-FE-273 | 포털 업로드 — 짧은 저장엔 오버레이 미표시 (신규) | 저장 <300ms | 렌더 | 오버레이 없음(지연 창) | component | Med | features/label/busyPolicy.ts:69 · features/label/components/BusyOverlay.tsx:48-67 |
| TC-FE-274 | 포털 업로드에는 AI busy 가 없다 (신규) | ADR-013 별도 경로(`LS_PORTAL_*`) | 화면 전체 | AI 탐지/분할/추적 진입점 자체가 없어 관측되는 busy 종류는 **SAVE 뿐**. 오버레이 문구도 "저장 중" | component | High | pages/portal/PortalUploadLabelingPage.tsx:44-50(UPLOAD_TOOLS) · pages/portal/__tests__/PortalUploadLabelingBusy.test.tsx |
| TC-FE-275 | "포털 라벨링" ≠ "포털 업로드 라벨링" 경계 (정정) | 두 화면 | 경로·데이터 | 전자=`/portal/label/:id`(데이터마트 영상), 후자=`/portal/uploads/:uldSn/label`(본인 업로드 자산) — **경로·데이터 출처는 다르지만 도구 구성은 둘 다 BBOX/POLYGON만**이다. ⚠ 구 기대결과("전자는 AI 분할·추적 제공")는 폐기: `PORTAL_HIDDEN_TOOLS`(SAM_SEGMENT/TRACK/KEYPOINT)가 `dcdbb827`(SAM2 제거) 이후 `/portal/label/:id` 에도 적용되어 AI 분할·추적·탐지 전부 미제공이다(ADR-013, `PortalLabelingPage.tsx` 주석 "포털 오토라벨링 미제공"). **차단·오버레이·취소는 양쪽 모두 적용** | component | High | features/label/types.ts:215-219 · features/label/components/DarkToolbar.tsx:110-112,148-153(정정 — 필터 블록은 148-153) · pages/portal/PortalLabelingPage.tsx:2-4 · pages/portal/PortalUploadLabelingPage.tsx |
| TC-E2E-008 | 포털 홈 정상 진입 | PORTAL_USER | /portal | 홈 렌더 | e2e | High | e2e/specs/portal-channel-guard.spec.ts:10 |
| TC-E2E-009 | 포털→내부 대시보드 차단 | PORTAL_USER | /dashboard | forbidden | security | High | e2e/specs/portal-channel-guard.spec.ts:15 |
| TC-E2E-010 | 포털→내부 관리 화면 차단 | PORTAL_USER | /manage/* | 차단 | security | High | e2e/specs/portal-channel-guard.spec.ts:26 |
| TC-E2E-011 | 포털 업로드 dropzone 노출+업로드 | PORTAL_USER | /portal/uploads | dropzone+mock 업로드 | e2e | Med | e2e/specs/portal-upload.spec.ts:10,20 |

## H-12. 공통 컴포넌트/에러/상태

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-178 | ErrorBoundary 스택 콘솔만/사용자 미노출 | 자식 throw | catch | "오류가 발생했습니다" role=alert | security | High | components/common/ErrorBoundary.tsx:21-38 |
| TC-FE-179 | ErrorBoundary custom fallback | fallback prop | throw | fallback 렌더 | component | Low | components/common/ErrorBoundary.tsx:30 |
| TC-FE-180 | AppErrorPage 404/500 | status | 렌더 | 상태별 페이지 | component | Med | components/common/AppErrorPage.tsx |
| TC-FE-181 | EmptyState 빈 목록 | data=[] | 렌더 | 빈 상태 안내 | component | Low | components/common/EmptyState.tsx |
| TC-FE-182 | AuthImage — Bearer blob 로딩(401 회피) | `srcSn` 지정 | 렌더 | `/frames/{srcSn}/image` blob 요청 → objectURL `<img>`. 로딩/에러는 `<div>` 폴백이며 **data-\*/aria-\* 는 유지**(슬롯 식별 끊김 방지) | security | High | components/common/AuthImage.tsx:60-122 · components/common/__tests__/AuthImage.test.tsx:49,299 |
| TC-FE-183 | Pagination 페이지 이동 | totalPages | 클릭 | 페이지 변경 | component | Low | components/common/Pagination.tsx |
| TC-FE-184 | DataTable 정렬/렌더 | rows | 렌더 | 컬럼 정렬(sortable 컬럼만) | component | Low | components/common/DataTable.tsx |
| TC-FE-185 | Toast variant별 표시 | pushToast | 발화 | success/error/warning/info | component | Med | components/common/Toast.tsx |
| TC-FE-186 | 게시판(공지) 목록/상세 | 인증됨 | /notice | 목록/상세 | component | Low | pages/NoticeListPage.tsx · pages/NoticeDetailPage.tsx |
| TC-FE-187 | RoleClaimPage 권한 자가부여 | role=null | /role-claim | 권한 요청 폼 | component | Med | pages/RoleClaimPage.tsx |
| TC-FE-188 | 통계 화면 차트 (정정) | REVIEWER/WORKER | /stat | `WorkerStatPage`=recharts 기반 `DailyCompletionChart`(Bar만), `OverallStatPage`=**recharts 미사용** 커스텀 SVG `SimpleBarChart`/`SimplePieChart`. ⚠ 구 기대결과("recharts 별도 청크"로 두 화면 통칭)는 부정확 — recharts 는 WorkerStatPage 경로에만 남아 있고 OverallStatPage 는 자체 SVG 컴포넌트로 대체됨(`f902e3d1`, 07-25 이전부터) | component | Low | pages/WorkerStatPage.tsx:7,143 · features/stat/components/DailyCompletionChart.tsx:1,21-27 · pages/OverallStatPage.tsx:12-13,225,236 · components/charts/SimpleBarChart.tsx · components/charts/SimplePieChart.tsx |
| TC-FE-214 | AuthImage `path` 화이트리스트 fail-closed (신규) | `path='https://evil/x'` · `'/v1/frames/../../x'` · 쿼리스트링 포함 | 렌더 | **요청을 보내지 않고** "이미지 없음" 폴백. 허용은 `^/v1/frames/[0-9]{1,19}/(deid-)?image$` 뿐 (CWE-918/22) | security | High | lib/api/imagePath.ts:11-21 · components/common/__tests__/AuthImage.test.tsx:84,102 |
| TC-FE-215 | authImageStore refcount 중복 페치 제거 (신규) | 같은 경로를 그리드 슬롯+좌우비교가 동시 사용 | 마운트 | XHR **1회**, objectURL 공유. 동시 실행 상한·대기열 없음(head-of-line blocking 제거 — 페이저 넘겨도 새 페이지가 즉시 발사) | component | High | lib/api/authImageStore.ts:97-105 · components/common/__tests__/AuthImage.test.tsx:136,187,226 |
| TC-FE-216 | 마지막 소비자 unmount 시 즉시 revoke (신규) | 공유 중 소비자 순차 unmount | refCount 0 | `URL.revokeObjectURL` 호출 + 엔트리 삭제. **영속 캐시 없음** → 재진입 시 BE 재호출(비식별 신고 412 게이트가 그대로 적용, CWE-359) | security | High | lib/api/authImageStore.ts:19-24,107-118 · components/common/__tests__/AuthImage.test.tsx:119,158,348 |
| TC-FE-217 | 실패 경로는 새 소비자 마운트 시 1회 재요청 (신규) | 첫 요청 실패 후 재마운트 | acquire | `failed` 엔트리를 재사용해 **acquire 시점에만** 재요청(자동 재시도 루프 없음, 세대 교차로 인한 조기 revoke 없음) | component | Med | lib/api/authImageStore.ts:51-105 · components/common/__tests__/AuthImage.test.tsx:279 |
| TC-FE-218 | 폴백 `<div>` 에 img 전용 속성 미전개 (신규) | width/height/loading 등 전달 | 로딩·에러 폴백 | React 무효 DOM 속성 경고 없음(IMG_ONLY_PROPS 제거 후 전개) | component | Low | components/common/AuthImage.tsx:27-47 · components/common/__tests__/AuthImage.test.tsx:322 |

## H-13. 접근성 (a11y)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-A11Y-001 | 우측 패널 탭 role=tab/tabpanel | 라벨링 | 렌더 | role=tablist/tab, aria-selected/controls | a11y | High | pages/label/LabelingPage.tsx:1405-1473 |
| TC-A11Y-002 | AiToolModal 라디오/체크박스 label 연결 + AI 분할 정밀도 "즉시 그리기" 체크박스 label 연결 | 팝업 / AI 분할 도구 활성 | 렌더 | htmlFor↔id | a11y | High | features/label/components/AiToolModal.tsx:198,209,255 · features/label/components/ObjectAttributePanel.tsx:145,149 |
| TC-A11Y-003 | Modal 포커스 트랩+ESC+포커스 복귀 | 모달 열림 | ESC | 닫힘+포커스 복귀 | a11y | High | components/common/Modal.tsx |
| TC-A11Y-004 | 마킹 키보드 전 조작(Space/Del/Enter) | MANUAL | 키보드 | 마우스 없이 완결 | a11y | High | pages/MarkingPage.tsx:133-152 |
| TC-A11Y-005 | 라벨링 단축키(W/S/F/Q/T/?/Ctrl+C/V) | 라벨링 | 키 | 이동/폴리곤/표시토글/치트시트/복붙 | a11y | High | pages/label/LabelingPage.tsx:1000-1059 |
| TC-A11Y-006 | 잠금 배너 aria-live=polite | isLocked | 렌더 | role=status aria-live | a11y | Med | pages/label/LabelingPage.tsx:1246-1255 |
| TC-A11Y-007 | 진행률 progressbar aria-valuenow | 업로드 | 렌더 | role=progressbar+aria-value* | a11y | Med | pages/portal/PortalUploadPage.tsx:202-210 |
| TC-A11Y-008 | 이슈 배지 aria-label 카운트 | 미해소>0 | 렌더 | aria-label="미해소 문의 N건" | a11y | Med | pages/label/LabelingPage.tsx:1465 |
| TC-A11Y-009 | 삭제 버튼 aria-label(파일명) | 자산 목록 | 렌더 | aria-label="{파일명} 삭제" | a11y | Low | pages/portal/PortalUploadPage.tsx:312 |
| TC-A11Y-010 | 아이콘 aria-hidden(중복 낭독 방지) | 아이콘 | 렌더 | aria-hidden | a11y | Low | components/common/BatchStageIndicator.tsx:34,41,48 |
| TC-A11Y-011 | KRDS 포커스링 키보드 초점 | Tab | 포커스 | KRDS_FOCUS 가시 초점 | a11y | Med | lib/focusRing.ts |
| TC-A11Y-012 | 검수 캔버스 aria-label 읽기전용 | 검수 | 렌더 | aria-label="검수 캔버스 (읽기 전용)" | a11y | Low | pages/ReviewPage.tsx:297 |
| TC-A11Y-013 | KPI 필터 카드 aria-pressed 토글 시맨틱 (신규) | 클릭형 KPI 카드 | 카드 선택/해제 | `<button aria-pressed>` 로 선택 상태 전달 + **테두리 두께**로도 구분(색상 단독 금지). `onClick` 없는 카드는 `aria-pressed` 자체가 붙지 않음 | a11y | High | components/common/KpiCard.tsx:19-29,48-62 |
| TC-A11Y-014 | 정렬 가능 헤더 aria-sort + button (신규) | 작업목록 헤더 | 촬영일시/영상 ID 클릭 | `<th aria-sort=ascending\|descending\|none>` + 내부 `<button>`. 정렬 상태를 아이콘·색이 아니라 aria-sort 로 전달 | a11y | High | features/task/components/TaskBoardTable.tsx:58-84 |
| TC-A11Y-015 | 진행 오버레이 상태 전달·포커스 정책 (신규) | busy 300ms 초과(내부·포털 업로드 공통) | 오버레이 표시 | `role="status" aria-live="polite" aria-busy="true"` + 취소 버튼으로 포커스 이동(**포커스 트랩 없음** — Tab 으로 빠져나갈 수 있어야 한다). **경과 초는 라이브 리전에서 제외**(`aria-hidden` — 매초 낭독 방지), 스피너는 장식. **모달이 열려 있으면 포커스를 가져가지 않는다**(모달 뒤 보이지 않는 버튼이 Enter/Space 로 눌리는 것 방지) | a11y | High | features/label/components/BusyOverlay.tsx:69-79,108-131 · features/label/busyPolicy.ts:107-110 |

## H-14. 보안 (XSS/토큰/용어정책)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-189 | 프레임 설명 script 입력 텍스트 렌더(XSS) | `<script>` | PUT 후 표시 | 텍스트 escape | security | High | e2e/specs/frame-description.spec.ts:191 |
| TC-FE-190 | dangerouslySetInnerHTML 미사용 전수 | 전 컴포넌트 | 정적 검사 | **JSX 속성으로서의 사용 0건**. ⚠ 단순 grep 은 25건(2026-08-03 실측, 구 표기 22건) 매치되나 전부 "미사용" 을 명시한 **주석·테스트 문자열**이다 — `dangerouslySetInnerHTML={` 패턴으로 검사할 것 | security | High | (grep: `dangerouslySetInnerHTML={`) |
| TC-FE-191 | FE 문구에 YOLO/SAM2 금지 | AI 도구/배치단계 | 렌더 | "AI 탐지/AI 분할/AI 추적", 미지 단계는 "처리중". ⚠ 예외: 시스템 설정의 설정 **키 이름**(`YOLO_CONF_THRESHOLD` 등)은 코드 식별자로 화면 문구가 아님 | security | High | features/label/components/AiToolModal.tsx:4-13 · components/common/BatchStageIndicator.tsx:26-28 |
| TC-FE-192 | 에러 메시지 내부경로/스택 미노출 | BE 에러 | 렌더 | 사용자 문구만(CWE-209) | security | High | components/common/ErrorBoundary.tsx:22 · lib/api/resolveApiMessage.ts:4 |
| TC-FE-193 | 경로 파라미터 인코딩(IDOR/Path 방어) | id 경로 | 요청 | ⚠ **axios 는 템플릿 보간된 경로 세그먼트를 자동 인코딩하지 않는다**(구 기대결과 "axios 인코딩" 은 오류 — 2026-08-03 정정). 실제 방어 3층: ①숫자 ID(`srcSn`/`rawSn`/`id`)는 `Number()` 로 좁혀 보간 ②**문자열 ID 는 호출부에서 명시 `encodeURIComponent`**(`label/api.ts:986,1017` trackId · `sysconfig/api.ts:26` config key) ③BE `LabelAccessGuard` 소유권 재검증 | security | Med | features/label/api.ts:986,1017 · features/sysconfig/api.ts:26 · features/review/api.ts:84,93,122,131 |
| TC-E2E-012 | 프레임 설명 저장 실패 에러 표시 | PUT 실패 | 저장 | 에러 표시 | e2e | Med | e2e/specs/frame-description.spec.ts:158 |
| TC-E2E-013 | 프레임 설명 기존값 표시+PUT 반영 | 프레임 선택 | 입력/저장 | 기존 표시+PUT | e2e | Med | e2e/specs/frame-description.spec.ts:94,113 |

## H-15. E2E 전체 사용자 시나리오

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-E2E-014 | WORKER 라벨링: 목록→캔버스→BBox 저장 | WORKER | serial | 저장 토스트 | e2e | High | e2e/specs/labeling-flow.spec.ts:9-44(정정, describe.serial 전체 범위) |
| TC-E2E-015 | WORKER 라벨링 진입 도구바 렌더 | WORKER | 진입 | 바운딩박스 버튼 | e2e | Med | e2e/specs/worker-labeling.spec.ts:11-36(정정, describe 전체 범위) |
| TC-E2E-016 | 전체 워크플로우: 라벨링→저장→제출→반려→롤백→재제출→승인 | WORKER(userNo=2001 `labelerPage`)+REVIEWER | serial | 각 단계 통과. ⚠ **정정(2026-08-03 3차)**: 대상 영상·프레임은 더 이상 하드코딩되지 않는다 — `resolveWorkflowFixture()`(`e2e/fixtures/test-data.ts:116-128`)가 실행 시점에 공개 API 로 (배정 영상, 첫 프레임)을 해석하고 제출 가능 상태 정규화·롤백용 커밋 2건 적층까지 수행한다. 구 상수 `WORKFLOW_VIDEO_ID=9035`/`WORKFLOW_SRC_SN=241` 은 **삭제**됨(H-ISSUE-143 해소). 스펙 테스트 수도 4→9(픽스처 유효성 단언 + 최종 완주 단언 추가) | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts:52-278 · e2e/fixtures/test-data.ts:61-64,116-128 |
| TC-E2E-017 | WORKER 이력 패널 오픈 후 롤백 | 반려 후 | 롤백 | 버전 롤백 | e2e | Med | e2e/specs/labeling-review-full-flow.spec.ts:186-216 |
| TC-E2E-018 | REVIEWER 반려 처리 | 검수 진입 | 반려 | 반려 상태 전이 | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts:150-184 |
| TC-E2E-019 | REVIEWER 최종 승인 | 재제출 후 | 승인 | **APPROVED** 전이(구 "COMPLETED 전이" 정정 — 최종 단언은 `readStatus()` 가 `/v1/reviews/{videoId}` 의 `dataSttsCd` 를 읽어 `APPROVED` 를 확인한다) | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts:239-272(승인 단계),274-277(최종 `APPROVED` 단언) |

## H-16. 작업목록 필터·정렬·KPI (SCR-TASK-001, 서버 이관) — 신설

> ★ 구속 정책(루트 CLAUDE.md "목록 화면 정렬·필터 정책"): **정렬은 시간축 단일**(상태 우선순위 `ORDER BY CASE` 금지), **필터·집계는 BE 에서 전체 기준**, **미등록 정렬 키는 이 엔드포인트에서 strict 400**. 검수목록(H-17)의 lenient 정책과 **통일하지 말 것**.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-219 | 진입 기본 정렬 = 등록일 최신순 명시 전송 (신규) | REVIEWER, URL 에 sort 없음 | 목록 진입 | 요청 `sort=regDt,desc` **명시 전송**(BE 기본값 의존 금지). 상태 우선순위 정렬은 어디에도 없다 | component | High | features/task/boardSort.ts:38-40 · features/task/boardParams.ts:151-165(정정) |
| TC-FE-220 | 정렬 키는 allowlist 매핑으로만 해석 (신규) | `?sort=priority,desc` 등 미등록 키 | 진입 | `parseBoardSort` 가 **조용히 제거** → BE 400 미발생. 매핑 대상은 regDt/shtDt(capturedAt)/rawSn(videoId) 뿐 | security | High | features/task/boardSort.ts:18-24,102-114 · features/task/__tests__/boardSort.test.ts |
| TC-FE-221 | 배치 상태 축은 URL 로 못 바꾼다(COMPLETED 고정) (신규) | `?status=UNASSIGNED` (구 북마크) | 진입 | 요청 `status=COMPLETED` 고정. 구 URL 이 파이프라인 미완료 영상을 노출하고 부제("처리 완료된 영상만 표시")를 거짓으로 만들던 문제 차단 | security | High | features/task/boardParams.ts:18-21,67,156(정정) |
| TC-FE-222 | URL 키 `status` 는 워크플로 축으로 해석(하위호환) (신규) | `?status=REVIEW_PENDING` | 진입 | `workStatus=REVIEW_PENDING` 로 요청, 상태 select 도 동일 값 표시 | component | High | features/task/boardParams.ts:230-239(정정, `searchParamsToFilters`) · features/task/__tests__/boardParams.test.ts |
| TC-FE-223 | allowlist 밖 워크플로 값은 무필터로 폴백 (신규) | `?status=BOGUS` | 진입 | 필터 미적용(빈 문자열) — 400 없이 전체 표시 | security | Med | features/task/boardParams.ts:80-96 |
| TC-FE-224 | WORKER 축 IN_PROGRESS — select 유지 + 서버로도 전송 (정정 2026-08-04) | WORKER 시각 URL `?status=IN_PROGRESS` | 진입 | select 값 유지(`asAssignmentWorkStatusParam` 허용값이라 `TaskListPage.tsx:93-101` 정규화에서 제거되지 않음) + 요청 `workStatus=IN_PROGRESS` **전송**(`asWorkStatusParam` 이 아니라 `asAssignmentWorkStatusParam` 이 처리 — REVIEWER 축(TC-FE-225)과 반대 방향). ⚠ 구 기대결과("서버 파라미터에서는 제외")는 REVIEWER 축 서술이 잘못 섞인 것이었다 — 폐기(3차 검증, H-ISSUE-144 후속). 실측: `GET /v1/assignments?workerId=2001&workStatus=IN_PROGRESS` → 200, totalElements 49→1(필터링 확인) | component | High | features/task/boardParams.ts:100-121(`asWorkStatusParam` vs `asAssignmentWorkStatusParam`) · pages/TaskListPage.tsx:93-101,135,212 |
| TC-FE-225 | REVIEWER 진입 시 IN_PROGRESS 필터 제거 (신규) | REVIEWER + `?status=IN_PROGRESS` | 진입 | 상태 select 는 빈칸인데 목록만 전체인 어긋난 화면 방지 — 초기 state 에서 `workStatus=''` 로 정규화 | component | Med | pages/TaskListPage.tsx:93-102(정정) |
| TC-FE-226 | 정렬 키 3개 상한 + 서버키 중복 제거 (신규) | 헤더 4회 연속 클릭 / `videoId`+`rawSn` 동시 지정 | 정렬 | 최대 3개(가장 오래된 키 버림), 같은 서버 키는 1개만 → BE 400(개수 상한·중복) 미발생 | security | High | features/task/boardSort.ts:35,54-65,120-130 |
| TC-FE-227 | 헤더 클릭 = 1순위 승격 + desc→asc 토글 (신규) | 기본 정렬 regDt,desc | 촬영일시 헤더 1회/2회 클릭 | 1회=`shtDt,desc` 가 **맨 앞**, 2회=`shtDt,asc`. 뒤에 붙이면 regDt 가 지배해 무효 클릭이 된다 | component | High | features/task/boardSort.ts:46-65,86-93 |
| TC-FE-228 | URL 왕복 후에도 aria-sort 유지(서버키 비교) (신규) | `?sort=rawSn,desc` 로 재진입 | 영상 ID 헤더 | `aria-sort=descending` 유지(컬럼명이 videoId→rawSn 으로 바뀌어도 서버 키로 비교) | a11y | Med | features/task/boardSort.ts:73-80 · features/task/components/TaskBoardTable.tsx:63-67 |
| TC-FE-229 | 기본 정렬은 URL 에 기록하지 않음 (신규) | 기본 정렬 상태 | URL 동기화 | `sort` 파라미터 없음(왕복 시 기본값 복원 — 멱등). 비기본 정렬만 기록 | component | Med | features/task/boardParams.ts:251-265(정정, `filtersToSearchParams`) |
| TC-FE-230 | 초기화가 정렬까지 기본값 복원 (신규) | `?sort=regDt,asc` (헤더 없는 축) | "초기화" 클릭 | 필터 + **정렬** 모두 기본값. regDt 는 컬럼 헤더가 없어 토글로 되돌릴 수 없으므로 이 버튼이 유일한 복구 경로 | component | High | pages/TaskListPage.tsx:377-384(정정, `handleFiltersReset`) |
| TC-FE-231 | KPI 5카드 = 서버 집계 + 클릭 토글 (신규) | REVIEWER | 카드 클릭/재클릭 | 전체/미배정/작업중/검수요청/반려 5카드. 클릭=해당 `workStatus` 필터, 재클릭=해제(전체). 숫자는 `/v1/tasks/board/summary` 전체 기준(현재 페이지 20건 아님) | component | High | features/task/components/TaskBoardKpiCards.tsx:30-121 · pages/TaskListPage.tsx:386-394,541-551(정정) |
| TC-FE-232 | KPI '작업중' = PENDING 축 (신규) | 배정됐고 검수 미제출인 영상 존재 | KPI 확인 | '작업중' 카드가 `summary.inProgress`(=BoardWorkStatus.PENDING 집계)를 표시하고 클릭 시 `workStatus=PENDING` 전송. **`IN_PROGRESS` 는 BE 가 반환하지도 허용하지도 않는다** → 구 결함(REVIEWER 시각 0 고정) 재발 없음 | component | High | features/task/components/TaskBoardKpiCards.tsx:92-100 · features/task/types.ts:164-180,213-226(정정) |
| TC-FE-233 | KPI 집계 요청에서 workStatus 제외 (신규) | 카드 선택된 상태 | summary 요청 | `workStatus` 미전송(카드 자체가 선택지). 검색어/이벤트/작업자 필터는 반영 | component | High | features/task/boardParams.ts:173-182(정정, `buildBoardSummaryParams`) |
| TC-FE-234 | KPI 로딩/실패/정상 3상태 구분 (신규) | summary 로딩 · 실패 | 렌더 | 로딩=스켈레톤(`kpi-loading`), 실패=`kpi-error` "집계 정보를 불러오지 못했습니다. 목록은 정상 표시됩니다."(role=status). **페이지 레벨 에러는 목록 쿼리만 결정** — KPI 실패가 목록을 가리지 않음 | component | High | features/task/components/TaskBoardKpiCards.tsx:37-66 · pages/TaskListPage.tsx:188-191(정정) |
| TC-FE-235 | REVIEWER 시각 클라이언트 재필터 금지 (신규) | 서버 필터 적용된 20건 | 렌더 | `visibleRows === allRows` (재필터 없음). 헤더 "전체 N건"=서버 `totalElements` — 목록/총건수/KPI 가 같은 집합을 말한다 | component | High | pages/TaskListPage.tsx:321-334(정정) |
| TC-FE-236 | 체크박스 선택이 페이지 전환 후 잔존하지 않음 (신규) | 1페이지에서 3건 선택 → 2페이지 이동 | 일괄 배정 | 선택 초기화(`clearSelection`) + 화면에 없는 선택은 effect 가 제거 → **두 페이지 영상이 섞여 배정되던 결함** 재발 없음 | security | High | pages/TaskListPage.tsx:356-365,405-411(정정) |
| TC-FE-237 | 필터·KPI·정렬 변경 시 page 0 + 선택 해제 (신규) | 3페이지에서 필터 변경 | 조회 | page=0 으로 리셋(같은 이벤트에서 처리 — 직전 페이지로 요청이 한 번 더 나가지 않음), 선택 해제 | component | High | pages/TaskListPage.tsx:368-375,386-394,396-403(정정) |
| TC-FE-238 | 총 페이지 축소 시 범위 복귀 (신규) | 5페이지 보다 결과가 2페이지로 감소 | 재조회 | `page`→마지막 페이지로 되돌림(빈 목록+페이지네이션 소실로 복구 불가해지는 상태 방지) | component | Med | pages/TaskListPage.tsx:342-348(정정) |
| TC-FE-239 | 이벤트유형 옵션 서버 조회 + truncated 안내 (신규) | 옵션 상한 초과 | 필터 렌더 | `{items,truncated}` **객체** 응답을 훅이 분해(배열 오인 `.map` 금지). `truncated=true` 면 "옵션이 많아 일부만 표시됩니다". 조회 실패해도 빈 옵션으로 폴백(목록은 유지) | component | Med | features/task/hooks/useTaskBoardEventTypes.ts:19-36 · features/task/components/TaskFilters.tsx:157-164 |
| TC-FE-240 | 목록 조회 실패 시 배정 액션 잠금 (신규) | board 쿼리 실패 | 렌더 | ErrorState + 체크박스/배정 버튼 disabled + title 안내(최신 아닌 목록으로 배정 방지). WORKER 에게는 "배정 기능" 안내 미노출 | security | High | pages/TaskListPage.tsx:526-538,601(정정) · features/task/components/TaskBoardTable.tsx:149-163,320-340 |
| TC-FE-241 | 검색어 100자 상한(400 왕복 방지) (신규) | 101자 입력 | 조회 | input `maxLength=100` + 파라미터 `slice(100)` (BE `@Size(max=100)` 정합) | component | Med | features/task/boardParams.ts:51,61,131-134(정정) · features/task/components/TaskFilters.tsx:131(정정) |
| TC-FE-242 | WORKER 시각도 서버 필터(정렬만 미지원) + 4카드 유지 (정정) | WORKER | 검색/상태/이벤트 필터 | `buildAssignmentParams` 가 검색어/상태/이벤트유형을 `/v1/assignments` 로 위임(REVIEWER 와 동일 원칙) — **서버 미지원은 정렬뿐**(정렬 UI 자체가 없어 전송하지 않음). ⚠ 구 기대결과("클라이언트 필터")는 폐기: `TaskListPage.tsx` 에 `.filter(` 재필터 코드가 없고 `tasks` 는 `tasksPage?.content` 를 그대로 쓴다. 헤더 "전체 N건"=서버 `totalElements`. KPI 는 표시 전용 4카드(WORKER 는 `/v1/tasks/board/summary` 가 403 이라 **목록 결과를 클라이언트에서 집계**, 클릭 필터 없음) | component | Med | pages/TaskListPage.tsx:134-137,212 · features/task/boardParams.ts:197-218 · features/task/components/TaskWorkerKpiCards.tsx:12-53 |

## H-17. 검수목록 진입 기본값·필터·정렬·KPI (SCR-REVIEW-001) — 신설

> ★ 이 엔드포인트는 **lenient** 다 — 미등록 정렬 키는 400 이 아니라 기본 정렬 폴백(200), 화이트리스트 밖 `status` 는 **빈 결과 200**. 그래서 잘못된 값이 에러가 아니라 "검수 대상 없음" 으로 위장한다. FE 가 allowlist·역매핑을 스스로 지키는 것이 유일한 방어다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-243 | 진입 기본값 = 검수요청 + 제출일 오래된순(FIFO) (신규) | REVIEWER, 파라미터 없는 진입 | 목록 요청 | `status=PENDING`(BE 코드) + `sort=submittedAt,asc` **명시 전송**. BE 기본 정렬은 최신순이라 의존하면 정반대가 된다 | component | High | features/review/reviewListParams.ts:80-95,147-159 · features/review/api.ts:40-64 |
| TC-FE-244 | 진입 기본값을 URL 에도 기록 (신규) | 진입 직후 | 주소창/새로고침/북마크 | `?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20` 이 URL 에 남고 새로고침해도 같은 화면. 내부 state 로만 들면 "필터 없는 전체 목록" 으로 조용히 되돌아간다 | component | High | features/review/reviewListParams.ts:201-220 · pages/ReviewListPage.tsx:112-124 |
| TC-FE-245 | URL 정규화는 값까지 교정하며 멱등 (신규) | `?status=BOGUS&sort=labelPayload,asc` | 진입 | 요청·표시뿐 아니라 **주소도** 정규값으로 `replace` 교정(히스토리 미오염). 정규값 재정규화는 no-op → 루프 없음 | component | High | pages/ReviewListPage.tsx:38-45,120-124 |
| TC-FE-246 | "전체" 는 키 삭제가 아니라 `status=ALL` (신규) | 상태 select 에서 전체 선택 | 새로고침 | 전체가 유지된다. 키를 지우면 "기본값 미적용" 과 구분 불가라 검수요청으로 되돌아간다 | component | High | features/review/reviewListParams.ts:30-37,112-119,215 |
| TC-FE-247 | FE→BE 상태 역매핑 Record 강제 (신규) | 정적 검사 | `REVIEW_STATUS_TO_BE` | `Record<ReviewStatus, ReviewStatusParam>` 이라 **FE 상태가 늘면 키 누락 = 컴파일 에러**. 매핑: REVIEW_PENDING→PENDING / REVIEWING→IN_REVIEW / COMPLETED→APPROVED / REJECTED→REJECTED | security | High | features/review/api.ts:30-45 · features/review/__tests__/api.statusMapping.test.ts |
| TC-FE-248 | 매핑에 없는 status 는 필터 생략 (신규) | 수기 URL 조작 등 | listReviews | 잘못된 코드를 보내 **빈 결과 200 으로 위장**하는 대신 status 를 빼고 요청(목록이 사라지지 않는다) | security | High | features/review/api.ts:55-64 |
| TC-FE-249 | 미등록 정렬 키는 기본 정렬로 정규화 (신규) | `?sort=labelCount,desc` | 진입 | `submittedAt,asc` 로 정규화 후 전송·표시(BE lenient 폴백에 화면이 끌려가 "눌렀는데 순서 그대로" 가 되지 않게) | component | High | features/review/reviewListParams.ts:55-68,121-126 · features/review/__tests__/reviewListParams.test.ts |
| TC-FE-250 | 상태 컬럼은 정렬 대상에서 제외 (신규) | 진입 기본 화면 | 상태 헤더 | 정렬 버튼 없음(`sortable` 미부여). BE allowlist 엔 있지만 단일 상태로 수렴한 화면에서 1차 정렬이 무효가 되고 tie-break(영상 ID 역순)가 실질 정렬이 되어 **FIFO 가 조용히 뒤집힌다** | component | High | pages/ReviewListPage.tsx:280-289 · features/review/reviewListParams.ts:55-67 |
| TC-FE-251 | 제출일만 정렬 가능 | 목록 | 제출일 헤더 클릭 | `sort=submittedAt,{asc\|desc}` 로 URL·요청 갱신 + page 0 복귀 | component | Med | pages/ReviewListPage.tsx:268-274,198-206 |
| TC-FE-252 | 검색 300ms debounce + 즉시 적용/취소 (신규) | 검색어 연속 입력 | 입력 → 300ms 대기 / 조회 / 초기화 | 입력이 멎은 뒤 1회만 요청. "조회"(Enter)는 대기 타이머를 취소하고 즉시 적용. **초기화는 대기 타이머를 명시 취소**(안 하면 초기화 직후 이전 검색어가 되살아난다) | component | High | features/review/components/ReviewListFilters.tsx:16,71-109 |
| TC-FE-253 | KPI 4카드 서버 집계 + summary 에 status 미전송 (신규) | REVIEWER | 카드 클릭/재클릭 | 검수요청/검수중/승인/반려 4카드, 클릭=필터·재클릭=해제. `buildReviewSummaryParams` 는 **`q` 만** 허용(타입상 status 를 넣는 것 자체가 컴파일 에러) | component | High | features/review/components/ReviewKpiCards.tsx:32-116 · features/review/reviewListParams.ts:161-174 |
| TC-FE-254 | summary 실패가 목록을 막지 않음 (신규) | `/reviews/summary` 500 | 렌더 | 카드 영역만 `kpi-error`, 표는 정상. 페이지 레벨 error 는 목록 쿼리만 본다. 비 REVIEWER 는 `enabled=false` 로 403 스팸 차단 | component | High | pages/ReviewListPage.tsx:161-165,340 · features/review/hooks/useReviewSummary.ts |
| TC-FE-255 | 목록 실패와 0건을 구분해 말한다 (신규) | 목록 쿼리 실패 | 렌더 | ErrorState 만 표시(표·"총 0건" 미표시). 실패를 "대상 0건" 으로 오독하는 화면 제거 | component | High | pages/ReviewListPage.tsx:334-345 |
| TC-FE-256 | 재조회 중 "이전 조건 결과" 고지 (신규) | 필터 전환 왕복 중 | 렌더 | `aria-busy` + role=status "갱신 중… (아래 목록은 이전 조건의 결과입니다)". keepPreviousData 로 KPI·배지는 새 조건인데 행은 옛 조건인 구간을 알린다 | a11y | Med | pages/ReviewListPage.tsx:134-141,346-358 |
| TC-FE-257 | page 범위 초과 복귀(응답 전 미판단) (신규) | 보던 중 총건수 감소 | 재조회 | 마지막 페이지로 되돌림. **응답이 없으면(로딩·실패) 판단하지 않는다** — 첫 로딩에 0페이지로 튕기지 않음 | component | Med | pages/ReviewListPage.tsx:143-154 |
| TC-FE-258 | 초기화 = 진입 기본값 복귀 + 이미 기본이면 비활성 (신규) | 정렬만 바꾼 상태 | 초기화 버튼 | 활성(정렬도 판정에 포함) → 검수요청·오래된순으로 복귀. 필터만 보면 정렬 복구 경로가 사라진다 | component | High | pages/ReviewListPage.tsx:188-216 · features/review/components/ReviewListFilters.tsx:25-31 |
| TC-FE-259 | 행 액션 접근성 이름 = 표시 문구 (신규) | 상태별 행 | 액션 버튼 | 보이는 문구("검수시작"/"이어서 검수"/"결과보기")가 그대로 accessible name(WCAG 2.5.3). 장식 `▶` 는 `aria-hidden` 으로 이름에서 제외 | a11y | High | pages/ReviewListPage.tsx:218-235,294-304 |
| TC-FE-260 | 빈 목록 문구가 현재 상태 필터를 반영 (신규) | status=REVIEW_PENDING, 결과 0건 | 렌더 | "검수요청 항목이 없습니다"(전체일 때만 "검수 항목이 없습니다") + 활성 필터 배지 "검수요청 상태만 표시 중" — 기본값이 필터임을 알려 "전체 중 0건" 오인 차단 | component | Med | pages/ReviewListPage.tsx:366-372 · features/review/components/ReviewListFilters.tsx:171-180 |

## H-18. 영상 목록 · 영상 상세 (SC-007 / SC-009) — 2026-08-03 신설

> **결정 2 (커밋 `b27b3108`) + 결정 5(FE 분, 커밋 대기)** 를 담는다. BE 검색·필터 계약은 [B-18](B-batch-deidentify.md) 이 소관이다.
>
> **결정 2 — 진입 버튼 제거 + `/history/:videoId` 페이지·라우트 삭제 (SC-010 폐지)**
> - 영상 상세(SC-009)에서 **"버전관리로 이동"** 버튼과 프레임 미리보기 라이트박스의 **"라벨링 편집"** 버튼을 제거했다. 라이트박스 푸터는 **"닫기" 단일 버튼**이다.
> - 버전관리 버튼은 `/history/:videoId` 의 **유일한 실사용 진입점**이었다. 버튼만 지우면 진입점 없는 orphan 페이지가 남으므로(SC-015 전례) `HistoryPage`·라우트를 함께 제거했고, `LabelHeader` 의 죽은 `/history` `Link` 폴백도 없앴다.
> - ⚠ **기능 손실 없음 — 버전·diff·롤백 기능 케이스를 폐기하지 말 것.** 라벨링 화면 인라인 `HistoryPanel`(TC-FE-087)이 변경이력·버전 탭을 모두 제공하고 `features/version/**` 은 전부 유지된다.
>   폐기된 것은 **"별도 페이지로 진입한다"는 전제**뿐이다 → [D-4/D-5 TC-VERSION·TC-DIFF](D-review-version-notify.md) 는 그대로 유효하며 진입 경로만 인라인 패널로 읽는다.
>
> **결정 5(FE) — 검색 필터 전송 + `capturedAt` 축 정정**
> - 화면은 예전부터 `cctvNameKeyword`/`eventTypeCd`/`from`/`to` 를 URL·요청에 실었지만 **BE 컨트롤러 시그니처에 없어 조용히 버려졌다**(상태 필터만 동작). 이번에 BE 가 받으면서 실제로 적용된다.
> - `capturedAt` 은 **촬영 시각(`SHT_DT`)** 이며 **수신 시각(`regDt`) 폴백을 FE·BE 양쪽에서 제거**했다. 화면 컬럼('녹화일')·정렬 키(`capturedAt→shtDt`)·기간 필터가 한 축이 된다.
> - 규칙 전문 → [v2-wiki 05 §5.5.3](../v2-wiki/05-video-management.md) · [04 화면 IA](../v2-wiki/04-screens-ia.md)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-297 | 영상 상세에 "버전관리로 이동" 버튼 미노출 (신규) | 정상 영상 상세 진입 | 렌더 | 해당 버튼·링크 모두 없음(회귀 가드). 버전 이력 확인은 라벨링 화면 히스토리 패널로 | component | High | pages/VideoDetailPage.tsx · features/video/\_\_tests\_\_/VideoDetailPage.test.tsx:108-140 |
| TC-FE-298 | 프레임 라이트박스 푸터 = "닫기" 단일 버튼 (신규) | 프레임 미리보기 클릭 | 라이트박스 렌더 | "라벨링 편집" 버튼 없음, 푸터는 닫기만. `onNavigateLabel` prop 체인 제거 | component | High | pages/VideoDetailPage.tsx:126-135 · VideoDetailPage.test.tsx:142-181 |
| TC-FE-299 | `/history/:videoId` 라우트·페이지 제거 (신규) | 인증된 내부 사용자 | `/history/1` 직접 진입 | 라우트 미정의 → 404(AppErrorPage). `pages/HistoryPage.tsx` 및 그 테스트 2파일 삭제됨 | component | Med | router/index.tsx(=`/history` 라우트 부재) · [v2-wiki 04 deprecated 정리](../v2-wiki/04-screens-ia.md) |
| TC-FE-300 | `onHistoryClick` 미지정 시 히스토리 버튼 자체가 안 뜬다 (신규) | `LabelHeader` 에 `onHistoryClick` 미주입 | 렌더 | 버튼 미렌더(구 `/history` `Link` 폴백 제거 — 남겨두면 **죽은 링크**가 된다). 주입 시에는 토글 버튼 + `aria-expanded` | component | High | features/label/components/LabelHeader.tsx:133-150 · features/label/\_\_tests\_\_/LabelHeader.test.tsx:22-30 |
| TC-FE-301 | 검색·필터 5종이 목록 요청에 실린다 (신규) | 영상 현황 목록 | 조회 버튼 | `cctvNameKeyword`(trim, 빈값이면 미전송)·`dataSttsCd`·`eventTypeCd`(카테고리 키)·`from`·`to` 전송 + `page=0` 복귀. 초기화는 5종 전부 비우고 `page=0,size` 만 남긴다 | component | High | features/video/components/VideoFilters.tsx:44-64 · features/video/api.ts:listVideos |
| TC-FE-302 | 상태 드롭다운 = BE 배치 단계 5종 (신규) | 필터 렌더 | 상태 select | 전체/완료(`COMPLETED`)/처리중(`PROCESSING`)/**마킹 대기(`MARKING_READY`)**/대기(`PENDING`)/실패(`FAILED`). `MARKING_READY` 누락 시 적재~마킹 구간 영상을 상태로 좁힐 수 없다(그 상태 영상은 실제로 존재) | component | High | VideoFilters.tsx:15-29 · features/video/\_\_tests\_\_/VideoFilters.test.tsx:12 |
| TC-FE-303 | `capturedAt` 은 `regDt` 로 폴백하지 않는다 (신규) | 응답 `capturedAt=null`, `regDt` 존재 | `normalizeVideo` | `capturedAt=''` → 화면 `-`. **수신 시각으로 몰래 채우지 않는다**(구 `v.capturedAt ?? v.regDt` 폴백 제거). 수신 시각은 별도 필드로 계속 노출 | component | High | features/video/api.ts:24-26,52 · features/video/\_\_tests\_\_/api.test.ts:53 |

---

> **불확실 항목 갱신 (2026-07-30)**
> - **#23 VideoPlayer 배속 — 코드로 확정**: `SPEED_OPTIONS=[0.25,0.5,1,1.5,2,4]` 이산 6버튼 + 기본 1x. **자유 수치 입력이 없어 클램프 로직 자체가 불필요**하다 → TC-FE-205 로 케이스화. (`features/marking/components/VideoPlayer.tsx:34,46-49,125-140`)
> - **#25 Dev 페이지/내부 TUS — 코드로 확정**: `pages/dev/DevAutolabelTestPage.tsx` 는 **존재**하며 `isDevUploadEnabled()` 빌드 플래그 안에서만 라우팅된다(`router/index.tsx:183-203`). 내부 TUS(`features/upload/*`, 기본 엔드포인트 `/uploads`)의 **유일한 소비자가 이 dev 페이지**이므로, 플래그 OFF 인 prod 빌드에서는 두 청크 모두 산출물에서 제거된다 → "dead-code 여부" 는 **prod 빌드 기준 dead-code 맞음 / dev 빌드에서는 살아있음**. 별도 폐지 작업 없이 TC-FE-028·029 의 플래그 게이팅만 검증하면 된다. (포털 TUS `/portal/uploads/tus` 는 별개이며 운영 경로다 — TC-FE-169)
> - **#22 캔버스 드로잉 픽셀 정확도** · **#24 반응형/WCAG 색대비**: 미확정 유지(런타임 브라우저 도구 필요).
> 상세는 [UNCERTAINTIES.md](UNCERTAINTIES.md)
