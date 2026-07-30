# H. FE 화면/컴포넌트 + E2E — 테스트 케이스

> 293 케이스 · 계층: component / e2e / a11y / security · [← README](README.md)
> ID: TC-FE(컴포넌트/상태) · TC-E2E(시나리오) · TC-A11Y(접근성)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 224건<br>(기대결과 실질 변경 21건) | 69건 | 0건 | 07-25 이후 `frontend/src` 73파일 변경 반영. ①**작업목록 전면 개편**(b23b8cbd — 상태 우선순위 정렬 폐기→등록일 최신순 시간축 단일, 필터·KPI 서버 이관, KPI 5카드 토글, 컬럼 헤더 정렬, 체크박스 페이지 이월 차단, `TaskBoardTable` 추출) → **H-16 신설** ②**검수목록 개편**(7ecbc66e — 진입 기본값 검수요청·FIFO 를 명시 전송+URL 기록, 상태 코드 역매핑 `Record` 강제, 상태 컬럼 정렬 제외, 300ms debounce) → **H-17 신설** ③**인증 이미지 blob 전환**(f4f2d9fe — `AuthImage` `srcSn\|path` 유니온 + 경로 화이트리스트 fail-closed, `authImageStore` refcount 공유) ④증강 결과 해상도 파생 비교 이미지·프레임 페이저·`reviewable` ⑤412 `PRECONDITION_FAILED` 매핑 + 파생영상 신고 버튼 사전 비활성 ⑥라벨 저장 409 충돌 다이얼로그·`labelVersion` 낙관적 토큰 ⑦검수 승인 `REVIEW_NO_LABEL` 확인 ⑧마킹 fps 서버 위임·`batchTriggered=false` 안내 ⑨`cot` 배열/객체 양형 정규화. 근거(file:line) 전면 재확인 + 파일 경로를 `src/` 기준 상대경로로 정규화(구 파일명만 표기 → 실제 경로). UNCERTAINTIES #23·#25 는 코드로 확정 가능(하단 참조) |

> **ID 부여 규칙(이번 회차)**: 신규 케이스는 섹션 위치와 무관하게 **문서 전체 마지막 번호 다음**부터 이어서 부여했다(TC-FE-194~260, TC-A11Y-013~014). 섹션별로 이어 붙이면 뒤 섹션의 기존 ID 와 충돌하기 때문이다.
> **기준선**: FE 테스트 **1,674 tests**(2026-07-30). 07-25 시점 ~1,5xx → "기존 테스트 부분 커버" 서술은 이 수치로 읽는다.

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
| TC-FE-008 | AuthenticatedGuard role=null 통과 | 인증만 | /role-claim | children(무한 redirect 없음) | component | High | router/guards.tsx:104-143 |
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
| TC-FE-023 | `/` → `/dashboard` redirect | 인증됨 | `/` | Navigate replace | component | Med | router/index.tsx:233 |
| TC-FE-024 | `/label/:id`는 풀스크린 | WORKER/REVIEWER | 진입 | LNB/GNB 없음 | component | Med | router/index.tsx:216-224 |
| TC-FE-025 | REVIEWER 전용 라우트 게이팅 | review/manage/augment/overall | WORKER 진입 | forbidden | security | High | router/index.tsx:290,337,350,380 |
| TC-FE-026 | 알 수 없는 내부 경로 → 404 | 인증됨 | /xyz | AppErrorPage 404 | component | Med | router/index.tsx:459 |
| TC-FE-027 | manage/* placeholder REVIEWER 게이팅 | 미정의 하위 | 진입 | REVIEWER만 | component | Low | router/index.tsx:417-424 |
| TC-FE-028 | dev 라우트 플래그 OFF dead-code 제거 | VITE_DEV_LOGIN_ENABLED 미설정 | 빌드 | /dev/login 청크 미포함 | security | Med | router/index.tsx:167-181 |
| TC-FE-029 | devUpload 라우트 REVIEWER 제한 | VITE_DEV_UPLOAD_ENABLED=true | 진입 | REVIEWER만(`dev/autolabel-test`) | security | Med | router/index.tsx:183-203 |
| TC-FE-030 | PortalRoute 채널+역할 이중가드 | /portal/* | 진입 | ChannelGuard+RoleGuard | security | High | router/index.tsx:160-166 |
| TC-FE-031 | lazyWithRetry 청크 로드 실패 재시도 | fetch 실패 | 재진입 | 재시도(Suspense fallback) | component | Med | router/lazyWithRetry.ts |
| TC-FE-032 | Suspense PageFallback 스피너 | 로딩 중 | 진입 | "페이지 로딩" | component | Low | router/index.tsx:127-137 |
| TC-E2E-002 | 라우트 가드 스위트(deepLink/manage/portal/review/augment) | 각 역할 | 딥링크 | 정책대로 통과/차단 | e2e | High | router/__tests__/{deepLinkHydrationGuard,manageGuard,portalGuard,reviewGuard,augmentExportGuard}.test.tsx |

## H-3. LabelingPage (라벨링 캔버스)

> ★ 이번 회차 변경: 저장 실패 처리에 **409 충돌 다이얼로그**가 추가되고(기존 단일 에러 토스트에서 분기), 저장 요청이 **`labelVersion` 낙관적 토큰**을 싣는다. 비식별 신고 버튼은 **파생영상이면 사전 비활성**된다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-033 | 잘못된 ID(NaN) 다크 에러 | id 비숫자 | 진입 | "잘못된 프레임 ID"+뒤로가기 | component | High | pages/label/LabelingPage.tsx:911-930 |
| TC-FE-034 | 로딩 상태 스피너 | isLoading | 진입 | "라벨 로딩" data-testid | component | Med | pages/label/LabelingPage.tsx:932-945 |
| TC-FE-035 | 포털 403 → graceful 차단화면 | portalMode+403 | 진입 | "접근할 수 없는 영상입니다" | security | High | pages/label/LabelingPage.tsx:950-977 |
| TC-FE-036 | 일반 에러 → "라벨 조회 실패" | error | 진입 | 에러 문구(=BE message)+뒤로가기 | component | Med | pages/label/LabelingPage.tsx:979-999 |
| TC-FE-037 | siblings 비면 현재 프레임 단건 폴백 | siblings=[] | 렌더 | 현재 1건 | component | Med | pages/label/LabelingPage.tsx:202-219 |
| TC-FE-038 | 저장 성공 토스트 "저장됨" | dirty 라벨 | handleSave | updateLabels+clearDirty+'저장됨' | component | High | pages/label/LabelingPage.tsx:492-510 |
| TC-FE-039 | 저장 중복 제출 차단 | saving in-flight | Ctrl+S 연타 | 두번째 무시 | component | High | pages/label/LabelingPage.tsx:496 |
| TC-FE-040 | 잠금 영상 저장 차단 | isLocked | handleSave | 에러 토스트, PUT 미발생 | security | High | pages/label/LabelingPage.tsx:497-503 |
| TC-FE-041 | 저장 실패 에러 토스트(BE 문구 우선) | 409 외 reject | handleSave | `extractBeMessage(e,'저장 실패')` 토스트 (구 `e.message` 직접 노출 아님) | component | Med | pages/label/LabelingPage.tsx:521-525 |
| TC-FE-042 | 포털 모드 저장 경로 분기 | portalMode | handleSave | savePortalLabels(원본 미수정) | security | High | pages/label/LabelingPage.tsx:486-491 |
| TC-FE-043 | 프레임 이동 dirty 가드 모달 | dirtyCount>0 | requestJumpTo | FrameNavGuardModal | component | High | pages/label/LabelingPage.tsx:292-302 |
| TC-FE-044 | 같은 프레임 이동 no-op | target===현재 | requestJumpTo | 무시 | component | Med | pages/label/LabelingPage.tsx:296 |
| TC-FE-045 | 저장 후 이동 — 실패 시 취소 | 저장 실패 | handleNavSaveAndMove | 에러+현 프레임 유지 | component | High | pages/label/LabelingPage.tsx:304-327 |
| TC-FE-046 | 저장 안 함 이동 — dirty 폐기 | navGuardTarget | handleNavDiscardAndMove | clearDirty 후 이동 | component | Med | pages/label/LabelingPage.tsx:329-336 |
| TC-FE-047 | 프레임 전환 시 setLabels 전체 교체 | srcSn 변경 | data effect | setLabels+dirty 초기화 | component | High | pages/label/LabelingPage.tsx:348-362 |
| TC-FE-048 | 같은 프레임 refetch는 dirty 있으면 미덮음 | dirty>0, 백그라운드 | data effect | 서버 라벨로 안 덮음 | component | High | pages/label/LabelingPage.tsx:355-361 |
| TC-FE-049 | 보류 추적 drain 병합 | pendingTracks | srcSn effect | mergeAutoLabels + info 토스트 **"보류된 AI 추적 N건 적용됨"** | component | High | pages/label/LabelingPage.tsx:364-381 |
| TC-FE-050 | 언마운트 시 store reset | 이동 | unmount | reset() | component | Med | pages/label/LabelingPage.tsx:384-388 |
| TC-FE-051 | AI 탐지 팝업 매핑 라벨만 선택 | detectCandidates 혼합 | AiToolModal | 미매핑 disabled+"미매핑", canRun=mappedCount>0 | component | High | features/label/components/AiToolModal.tsx:147,183-184,270 |
| TC-FE-052 | AI 탐지 mock 응답 자동적용 차단 | res.message 존재 | runAiTool detect | 경고 토스트, 병합 안 함 | security | High | pages/label/LabelingPage.tsx:698-701 |
| TC-FE-053 | AI 탐지 결과 작업본 병합(중복 스킵) | 정상 응답 | runAiTool | mergeAutoLabels+"N건 적용됨" | component | High | pages/label/LabelingPage.tsx:706-708 |
| TC-FE-054 | 폴리곤 shape → "AI 분할" 라벨 | POLYGON | runAiTool | 토스트 kind="AI 분할" | component | Med | pages/label/LabelingPage.tsx:707 |
| TC-FE-055 | 트랙 모드 — 객체 선택 유도 | mode=track | runAiTool | TRACK 도구+안내 | component | Med | pages/label/LabelingPage.tsx:673-690 |
| TC-FE-056 | 추적 결과 현재/미래 프레임 분리 | tracked 혼합 | handleTracked | 현재=즉시병합, 미래=stash | component | High | pages/label/LabelingPage.tsx:619-660 |
| TC-FE-057 | 부분 추적 실패 경고 | partial=true | handleTracked | "N/total만 추적됨" | component | Med | pages/label/LabelingPage.tsx:643-648 |
| TC-FE-058 | nextSrcSns 계산(현재 이후) | frameIdx | useMemo | slice(frameIdx+1) | component | Med | pages/label/LabelingPage.tsx:608-612 |
| TC-FE-059 | SAM2 추적 청크 50개 상한 정합 | nextSrcSns>50 | sam2TrackAllChunks | 50개 이하 분할(BE @Size max=50) | security | High | features/label/api.ts:sam2TrackAllChunks |
| TC-FE-060 | SAM2 추적 stale 가드(프레임 전환) | 응답 전 전환 | onSuccess | requestedSrcSn≠현재면 폐기 | component | High | features/label/hooks/useSam2Track.ts:57-59 |
| TC-FE-061 | SAM2 부분 실패 성공분만 병합 | ChunkError.partial | onError | 성공분 onTracked(partial=true) | component | Med | features/label/hooks/useSam2Track.ts:62-68 |
| TC-FE-062 | BBOX 추적 시드 외접박스 4점 확장 | BBOX 청크 | seedPolygon | 2점→4점(@Size min=3) | component | High | features/label/api.ts:toSeedPolygon |
| TC-FE-063 | 트랙 rename/삭제/분할 포털 차단 | portalMode | handleRename/Delete/Split | 조기 return(403 방어) | security | High | pages/label/LabelingPage.tsx:721,745,769 |
| TC-FE-064 | 잠금 영상 트랙 편집 차단 | isLocked | 각 핸들러 | 에러 토스트+미실행 | security | High | pages/label/LabelingPage.tsx:726,747,771 |
| TC-FE-065 | 트랙 rename 성공 후 invalidate | 정상 | mergeTracks | byVideo invalidate+토스트 | component | Med | pages/label/LabelingPage.tsx:731-737 |
| TC-FE-066 | 비식별 신고 성공 → 잠금+reset+무효화 | 신고 성공 | handleDeidentReportSuccess | reportedLock=true, store reset, `LABEL_KEYS.byVideo(srcSn)` invalidate. **★BE 는 라벨을 삭제하지 않는다(2026-07-27 보존 정책 반전)** — 재조회는 신고 게이트로 **412** 가 되어 "라벨 조회 실패"+서버 안내문 화면이 뜬다(빈 라벨 화면 아님) | security | High | pages/label/LabelingPage.tsx:465-473,979-999 · CLAUDE.md 비식별 누락 신고 |
| TC-FE-067 | 잠금 배너 노출 | LOCKED_FOR_REDEIDENT/reportedLock | 렌더 | role=status 배너 | component | Med | pages/label/LabelingPage.tsx:1079-1090 |
| TC-FE-068 | 비식별 신고 버튼 — RAW 프레임 disabled | frameImageType='RAW' | 렌더 | disabled | security | Med | pages/label/LabelingPage.tsx:1031-1040 |
| TC-FE-069 | 비식별 신고 버튼 포털 미노출 | portalMode | 렌더 | canReportDeident=false → null | security | High | pages/label/LabelingPage.tsx:115,1031-1032 |
| TC-FE-070 | 검수제출 버튼 WORKER만 | isWorker+data | 렌더 | submitButton | component | High | pages/label/LabelingPage.tsx:1041-1042 |
| TC-FE-071 | 상태별 제출 차단(REVIEW_PENDING/REVIEWING) | 비제출가능 | 렌더 | disabled+hint title | component | High | pages/label/LabelingPage.tsx:151-176 |
| TC-FE-072 | APPROVED 재검수 라벨 | COMPLETED | 렌더 | "재검수 제출" 문구 | component | Med | pages/label/LabelingPage.tsx:isResubmitOfApproved |
| TC-FE-073 | 제출 취소 버튼 REVIEW_PENDING만 | canCancelSubmit | 렌더 | "제출 취소" | component | Med | pages/label/LabelingPage.tsx:171,1044 |
| TC-FE-074 | 검수제출 성공 → /task 이동+토스트 | submitForReview | onSuccess | "검수 제출 완료"+navigate | component | High | pages/label/LabelingPage.tsx:139-145 |
| TC-FE-075 | X 닫기 dirty 시 3옵션 모달 | dirtyCount>0 | handleClose | closeConfirm 모달 | component | High | pages/label/LabelingPage.tsx:792-800 |
| TC-FE-076 | beforeunload dirty 경고 | dirtyCount>0 | 탭 닫기 | native 경고 | component | Med | pages/label/LabelingPage.tsx:831-839 |
| TC-FE-077 | 우측 탭 — 메타/이슈 내부 채널만 | portalMode | 렌더 | 메타·이슈 탭 미노출 | security | High | pages/label/LabelingPage.tsx:406,1205-1270 |
| TC-FE-078 | 이슈 탭 미해소 배지 카운트 | unresolvedInquiries>0 | 렌더 | danger 배지+aria-label | component | Med | pages/label/LabelingPage.tsx:1260-1266 |
| TC-FE-079 | 메타 탭 — 촬영환경/개인정보/설명/VLM/이벤트 패널 | rightTab=meta, 내부 | 렌더 | 5개 패널 | component | High | pages/label/LabelingPage.tsx:1290-1310 |
| TC-FE-080 | 뷰(zoom/pan) 유지 vs 리셋 | 동일영상+동일해상도 | handleImageSize | shouldResetView false → 유지 | component | Med | pages/label/LabelingPage.tsx:262-274 |
| TC-FE-081 | 붙여넣기 실측 dims clamp | frameNaturalSize | onPasteLabels | imageWidth/Height clamp | component | Med | pages/label/LabelingPage.tsx:875-890 |
| TC-FE-082 | 복사 — 빈 선택 no-op 토스트 | 라벨 없음 | onCopyLabels | "복사할 라벨이 없습니다." | component | Low | pages/label/LabelingPage.tsx:866-872 |
| TC-FE-083 | 잠금 영상 붙여넣기 차단 | isLocked | onPasteLabels | 에러+미실행 | security | Med | pages/label/LabelingPage.tsx:876-879 |
| TC-FE-084 | 저장 되돌리기 확인 모달 | 히스토리 카드 | handleRevertRequest | ConfirmDialog | component | Med | pages/label/LabelingPage.tsx:541-545 |
| TC-FE-085 | 되돌릴 항목 없음 경고 | reverted=0 | confirmRevert | "되돌릴 항목이 없습니다" | component | Low | pages/label/LabelingPage.tsx:546-566 |
| TC-FE-086 | 캔버스 lazy 마운트(konva 분리) | currentFrame | 렌더 | CanvasShell Suspense | component | Med | pages/label/LabelingPage.tsx:74-75,1175-1196 |
| TC-FE-087 | 히스토리 인라인 패널 내부만 | historyOpen+!portalMode | 렌더 | HistoryPanel | component | Low | pages/label/LabelingPage.tsx:1369-1381 |
| TC-FE-197 | 저장 409 → 충돌 다이얼로그(작업 보존) (신규) | 다른 사용자가 먼저 저장 | handleSave → ApiError status=409 | 에러 토스트가 아니라 **"다른 사용자가 먼저 저장했습니다"** ConfirmDialog. **dirty 유지**(내 작업 미폐기), 확인=최신 라벨 재조회(clearDirty+refetch), 취소="내 작업 유지" | component | High | pages/label/LabelingPage.tsx:515-519,533-538,1385-1394 |
| TC-FE-198 | 저장 요청에 labelVersion 동봉 (신규) | 조회 응답 labelVersion 존재 | PUT /frames/{srcSn}/labels | body 에 `labelVersion` 포함(값 없으면 필드 자체 생략 → BE 하위호환 skip 경로) | security | High | features/label/api.ts:putLabels · features/label/types.ts:labelVersion |
| TC-FE-199 | 연속 저장 시 캐시 버전 우선(자기 409 방지) (신규) | 1회차 저장 성공 직후 2회차 | handleSave 연속 2회 | onSuccess 가 `setQueryData` 로 캐시 버전을 동기 갱신 → 2회차는 **최신 버전** 전송(렌더 클로저 값 아님), 409 미발생 | component | High | features/label/hooks/useUpdateLabels.ts:50-70 · features/label/hooks/__tests__/useUpdateLabels.test.tsx |
| TC-FE-200 | 파생영상 — 비식별 신고 버튼 사전 비활성 (신규) | `VideoDetailResponse.derivative=true` | 라벨링 진입 | 버튼 disabled + title/aria-label 에 "증강·해상도 변환으로 만든 파생영상이라 …" 사유. **원본으로 유도하지 않고 부모 rawSn 도 표시하지 않는다** | security | High | pages/label/LabelingPage.tsx:127-132,1036 · features/label/components/DeidentReportButton.tsx:35-51,133-146 |
| TC-FE-201 | 신고 412 — 서버 안내문 그대로 노출 (신규) | 화면이 파생 여부를 모름(구 응답) | 신고 제출 → 412 | `resolveApiMessage` 로 **BE 안내문**을 폼 내 role=alert 에 표시(구: INTERNAL_ERROR 일반문구로 대체됨) | security | High | features/label/components/DeidentReportButton.tsx:109-116 · features/label/__tests__/DeidentReportButton.test.tsx |
| TC-FE-202 | 이벤트 어노테이션 cot 객체형 정규화 (신규) | `cot={"1단계":"…","2단계":"…"}` | toCaptionRows | 배열/객체 양형 모두 3단계 배열로 정규화(크래시 없음, 키 순서 유지) | component | High | features/label/api/eventAnnotation.ts:normalizeCot · features/label/components/eventAnnotationForm.ts:toCaptionRows |

## H-4. useLabelStore (Zustand)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-088 | setLabels 시 dirty/undo/redo/선택 초기화 | 세팅 | setLabels | 전부 초기화 | component | High | stores/useLabelStore.ts:401 |
| TC-FE-089 | addLabel dirty 마킹 | 추가 | addLabel | dirtyLabels 추가 | component | High | stores/useLabelStore.ts:412 |
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
| TC-FE-101 | clampPan 스케일 기반 뷰 제한 | zoom/pan | setPan | canvasGeometry clampPan 위임 | component | Med | stores/useLabelStore.ts:4,319-335 |

## H-5. 좌표 변환/캔버스 유틸 (canvas scale 함정)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-102 | translateToCanvas 이미지→캔버스 | geom.scale/left/top | 변환 | x*scale+left | component | High | features/label/canvas/utils/coordinateTransformer.ts:43-51 |
| TC-FE-103 | translateFromCanvas 왕복 정합 | 동일 geom | to→from | 원 좌표 복원(오차 내) | component | High | features/label/canvas/utils/coordinateTransformer.ts:57-61 |
| TC-FE-104 | clampToImage 경계 clamp | 캔버스 밖 | clampToImage | 경계로 clamp | component | Med | features/label/canvas/utils/coordinateTransformer.ts:132 |
| TC-FE-105 | computeWrappingBox 외접박스 | points | compute | min/max 박스 | component | Med | features/label/canvas/utils/coordinateTransformer.ts:106 |
| TC-FE-106 | rotate2DPoints 회전 유틸 | 각도 | rotate | 회전 좌표 | component | Low | features/label/canvas/utils/coordinateTransformer.ts:71 |
| TC-FE-107 | maskRleConverter MASK↔RLE↔Polygon | 마스크 | 변환 | 왕복 정합 | component | Med | features/label/canvas/utils/maskRleConverter.ts |
| TC-FE-108 | trackInterpolation 트랙 보간 | 두 키프레임 | interpolate | 중간 보간 | component | Med | features/label/canvas/utils/trackInterpolation.ts |
| TC-FE-109 | 캔버스 좌표 실측 naturalW/H 기준(하드코딩 제거) | 이미지 로드 | geometry | 실측 dims, 스트레치 없음 | component | High | pages/label/LabelingPage.tsx:242-274 |

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
| TC-FE-131 | 잘못된 검수 ID 에러 | id NaN | 진입 | "잘못된 검수 ID" | component | Med | pages/ReviewPage.tsx:232-241 |
| TC-FE-132 | 로딩 상태 | isLoading | 진입 | "검수 로딩" | component | Low | pages/ReviewPage.tsx:247-256 |
| TC-FE-133 | 에러/review 없음 | error/!review | 진입 | "검수 정보를 불러올 수 없습니다" | component | Med | pages/ReviewPage.tsx:260-269 |
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
| TC-FE-206 | 라벨 0건 승인 — 명시 확인 다이얼로그 (신규) | BE 409 `errorCode=REVIEW_NO_LABEL` | 승인 클릭 | "라벨이 없는 영상입니다" ConfirmDialog → 확인 시에만 `noLabelConfirmed:true` 로 재요청(평시 미전송) | component | High | pages/ReviewPage.tsx:170-172,220-226,386-398 |
| TC-FE-207 | 409 라도 사유가 다르면 다이얼로그 미노출 (신규) | 동시 승인 충돌 409(코드≠REVIEW_NO_LABEL) | 승인 클릭 | 확인 다이얼로그 **미노출**, BE 문구 토스트만. **상태코드가 아니라 `errorCode` 로 분기**(문자열 매칭 금지) | security | High | pages/ReviewPage.tsx:165-175 |
| TC-FE-208 | 검수 메타 cot 객체형 렌더 크래시 없음 (신규) | `cot` 가 `{"1단계":…}` 객체 | 메타 패널 렌더 | `normalizeCot` 로 배열화 후 필터 — 구 `(cand.cot ?? []).filter` TypeError 크래시 재발 없음 | component | High | features/review/components/ReviewMetaPanel.tsx:CaptionReadonly · features/label/api/eventAnnotation.ts:normalizeCot |
| TC-E2E-004 | 검수 플로우: 대기목록→시작→승인 | REVIEWER | serial | 진입→승인 완료 | e2e | High | e2e/specs/review-flow.spec.ts:5,12 |

## H-9. 관리 화면 (/manage/*)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-148 | 라벨 마스터 COCO 매핑 등록 | REVIEWER | dtctTypeCd 입력 | LabelMaster 매핑 반영 | component | High | pages/manage/LabelMasterManagePage.tsx |
| TC-FE-149 | 사용자/권한 관리 REVIEWER 전용 | REVIEWER | /manage/users | 목록/권한 관리 | security | High | pages/manage/__tests__/UserManagePage.test.tsx |
| TC-FE-150 | 시스템 설정 CRUD(정밀도/YOLO conf·iou) | REVIEWER | /manage/settings | 설정 카드 | component | Med | features/sysconfig/components/{YoloConfigCard,PrecisionConfigCard}.tsx |
| TC-FE-151 | 프리셋 목록/편집(마스터 join) | REVIEWER | /manage/presets | 코드↔labelId | component | Med | pages/manage/PresetListPage.tsx |
| TC-FE-152 | 비식별 신고 관리 목록 | REVIEWER | /manage/deident-reports | OPEN/RESOLVED 목록 | component | Med | pages/manage/DeidentReportListPage.tsx |
| TC-FE-153 | 작업 배정/재배정 모달 | REVIEWER | AssignModal | WORKER 배정+이력 | component | High | features/task/components/AssignModal.tsx |
| TC-FE-154 | 재배정 이력 드로어 | REVIEWER | HistoryDrawer | 배정 이력 | component | Low | features/task/components/HistoryDrawer.tsx |
| TC-FE-155 | 대시보드 KPI 3카드+이벤트 분포 | 인증됨 | /dashboard | KpiCard 3종+차트 | component | Med | pages/DashboardPage.tsx |
| TC-FE-156 | 영상 현황 목록 검색/필터/URL 동기화 | 인증됨 | 검색어 | URL 쿼리 갱신 | component | Med | features/video/parseVideoListParams.ts |
| TC-E2E-005 | REVIEWER 워크플로우 전체 | REVIEWER | 각 진입 | 헤더/컨테이너 노출 | e2e | High | e2e/specs/reviewer-workflow.spec.ts:14-47 |
| TC-E2E-006 | 영상 목록 검색어 URL 동기화 | WORKER | 검색 | URL `cctvNameKeyword=` 갱신 | e2e | Med | e2e/specs/video-list.spec.ts:5,15 |

## H-10. 증강/해상도 파생 화면

> ★ 이번 회차: `GET /v1/augments/{jobId}/result` 가 **해상도 파생(`RESL_*`)에 한해** `results[]`·`framePairs[]` 를 채운다(좌=부모 비식별 / 우=파생 리스케일, 양쪽 비식별본). 이미지는 **API 경로 문자열**이라 인증 blob 로딩이 필수다. WINTER/NIGHT/RAIN 은 여전히 빈 `results`.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-157 | 증강 요청 화면(WINTER/NIGHT/RAIN+해상도) | REVIEWER | /augment | ProcessKindCard 4종(단일 선택) | component | Med | features/augment/types.ts:24-46 · pages/AugmentRequestPage.tsx |
| TC-FE-158 | 해상도 파생 요청 mutation | rawSn | changeResolution | 파생영상+VIDEO_KEYS invalidate | component | High | features/video/hooks/useResolutionDerivative.ts:17-27 |
| TC-FE-159 | 해상도 파생 증강 이력 배지 | resolutionTypes | JobCard | RESL 배지(info, 한글 해상도 라벨) | component | Med | features/augment/components/JobCard.tsx:64-72 |
| TC-FE-160 | 해상도 파생 accept/reject 없음(비검수) | `result.reviewable=false` | AugmentResultPage ResultPanel | **DecisionCard 미렌더**(채택/거부 버튼 부재). 잡 이력 카드(JobCard)에는 원래 결정 액션이 없다 — 판정 지점은 결과 화면의 `reviewable` | component | High | pages/AugmentResultPage.tsx:419-421,452-461 |
| TC-FE-161 | 증강만 없고 파생만 → "파생" 태그 | types=[]+hasResolution | JobCard | 파생 구분자 | component | Med | features/augment/components/JobCard.tsx:75-83 |
| TC-FE-162 | 증강 채택 → ACCEPTED | PENDING | DecisionCard accept | ACCEPTED | component | High | features/augment/components/DecisionCard.tsx |
| TC-FE-163 | 증강 반려 사유 모달 | reject | RejectReasonModal | 사유+거부 | component | Med | features/augment/components/RejectReasonModal.tsx |
| TC-FE-164 | 거부 사유 표시(XSS escape) | rejectReason | DecisionCard | React 자동 escape | security | Med | features/augment/components/DecisionCard.tsx |
| TC-FE-209 | 해상도 파생 비교 이미지 인증 blob 렌더 (신규) | `RESL_*` 결과 + framePairs | 결과 화면 진입 | FrameGrid12·SideBySideCompare 가 `authImages` 로 **Bearer blob** 요청(raw `<img src>` 아님) → 401 로 전부 깨지던 경로 제거 | security | High | pages/AugmentResultPage.tsx:425-450 · features/deident/components/FrameGrid12.tsx:156-167 · features/deident/components/SideBySideCompare.tsx:88-98 · features/augment/__tests__/AugmentResultPage.resolution.test.tsx:111 |
| TC-FE-210 | 12쌍 초과 시 프레임 페이저 노출 (신규) | `totalFramePairs>12` | 결과 화면 | `augment-frame-pager` 노출, 다음 페이지로 나머지 쌍 접근(접근 불가 프레임 0). page/size 는 쿼리 파라미터로 전송(기본 12) | component | High | pages/AugmentResultPage.tsx:33,414-441 · features/augment/api.ts:getAugmentResult |
| TC-FE-211 | 탭 전환 시 프레임 페이지 리셋 (신규) | 1080P(15쌍) 2페이지 → 480P(10쌍) 전환 | 탭 클릭 | framePage=0 리셋 → 빈 그리드에 갇히지 않음. 마운트 시점에는 리셋 안 함(직전 탭과 비교). 안전망으로 `framePage>0` 이면 페이저 유지 | component | High | pages/AugmentResultPage.tsx:344-349,418 · features/augment/__tests__/AugmentResultPage.tabPaging.test.tsx:123,152 |
| TC-FE-212 | 해상도 타입 라벨 매핑(undefined 해소) (신규) | `type='RESL_720P'` | 탭·슬롯 라벨 | "해상도 720p" 등 사람이 읽는 문구. 미지 코드는 `'증강'`, `RESL_` 접두는 `resolutionDerivativeLabel` 폴백 — 기술코드 노출 0 | component | High | features/augment/augTypeLabel.ts:30-36 · features/augment/__tests__/AugmentResultPage.resolution.test.tsx:134 |
| TC-FE-213 | 결과 본문 있으면 "외부연동 대기" 안내 미표시 (신규) | COMPLETED + results 존재 | 결과 화면 | `augment-result-completed-empty` 미노출, 라벨 무결성 카드 표시. 총 처리 이미지 = **페이징 전 전체 쌍 수**(현재 페이지 길이 아님) | component | Med | pages/AugmentResultPage.tsx:36-38,89-99,242-254 |
| TC-E2E-007 | 증강 결과 PENDING 채택 → ACCEPTED | REVIEWER | 채택 | 상태 변경 | e2e | High | e2e/specs/augment-decision.spec.ts:4 |

## H-11. 포털 화면 (자산 업로드+수동 라벨링)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-165 | 이미지 확장자 검증(jpg/jpeg/png) | 비허용 | validateImageFiles | 제외+정책 안내 | security | High | features/portal/uploads/validation.ts:33-49 |
| TC-FE-166 | 이미지 20MB 초과 제외 | 대용량 | validate | "20MB 초과" | component | Med | features/portal/uploads/validation.ts:9,44 |
| TC-FE-167 | 이미지 50장 상한 초과 잘라냄 | 초과 | validate | slice(50)+안내 | component | Med | features/portal/uploads/validation.ts:12,50-56 |
| TC-FE-168 | 이미지 업로드 성공 후 초기화 | 유효 | onUploadImages | selected/errors/input 초기화 | component | Med | pages/portal/PortalUploadPage.tsx:77-87 |
| TC-FE-169 | 영상 TUS 재개 업로드(mp4/mov/avi) | 영상 | onVideoStart | tus.start(`/portal/uploads/tus`) | component | Med | pages/portal/PortalUploadPage.tsx:89-100 · features/upload/hooks/useTusUpload.ts:30 |
| TC-FE-170 | 삭제 확인+PROCESSING 버튼 비활성 | PROCESSING | 렌더 | disabled+title | component | Med | pages/portal/PortalUploadPage.tsx:270-271 |
| TC-FE-171 | 삭제 409 처리중 안내(내부 미노출) | BE 409 | deleteErrorMessage | "처리 중 자산 삭제 불가" | security | Med | pages/portal/PortalUploadPage.tsx:48-53 |
| TC-FE-172 | READY 자산만 라벨링 링크 | READY | 렌더 | /portal/uploads/:uldSn/label | component | Med | pages/portal/PortalUploadPage.tsx:296-306 |
| TC-FE-173 | FAILED 자산 실패 사유 표시 | FAILED | 렌더 | failRsnCn | component | Low | pages/portal/PortalUploadPage.tsx:288-292 |
| TC-FE-174 | 사용자 파일명 텍스트노드 렌더(XSS 방어) | 파일명 | 렌더 | 자동 escape | security | High | pages/portal/PortalUploadPage.tsx:280-281 |
| TC-FE-175 | 포털 업로드 라벨링 BBOX/POLYGON만 | READY | 진입 | CanvasShell, 오토라벨 없음 | component | Med | pages/portal/PortalUploadLabelingPage.tsx |
| TC-FE-176 | 포털 홈 데이터마트 영상 선택 | PORTAL_USER | /portal | 영상 목록+선택 | component | Med | pages/portal/PortalHomePage.tsx |
| TC-FE-177 | 포털 라벨링 저장(원본 미수정, 본인 적재) | 저장 | useSavePortalLabels | LS_PORTAL_USER_LABEL | security | High | features/portal/hooks/useSavePortalLabels.ts |
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
| TC-FE-188 | 통계 화면 차트(recharts 별도 청크) | REVIEWER/WORKER | /stat | Bar/Pie 차트 | component | Low | pages/WorkerStatPage.tsx · pages/OverallStatPage.tsx |
| TC-FE-214 | AuthImage `path` 화이트리스트 fail-closed (신규) | `path='https://evil/x'` · `'/v1/frames/../../x'` · 쿼리스트링 포함 | 렌더 | **요청을 보내지 않고** "이미지 없음" 폴백. 허용은 `^/v1/frames/[0-9]{1,19}/(deid-)?image$` 뿐 (CWE-918/22) | security | High | lib/api/imagePath.ts:11-21 · components/common/__tests__/AuthImage.test.tsx:84,102 |
| TC-FE-215 | authImageStore refcount 중복 페치 제거 (신규) | 같은 경로를 그리드 슬롯+좌우비교가 동시 사용 | 마운트 | XHR **1회**, objectURL 공유. 동시 실행 상한·대기열 없음(head-of-line blocking 제거 — 페이저 넘겨도 새 페이지가 즉시 발사) | component | High | lib/api/authImageStore.ts:97-105 · components/common/__tests__/AuthImage.test.tsx:136,187,226 |
| TC-FE-216 | 마지막 소비자 unmount 시 즉시 revoke (신규) | 공유 중 소비자 순차 unmount | refCount 0 | `URL.revokeObjectURL` 호출 + 엔트리 삭제. **영속 캐시 없음** → 재진입 시 BE 재호출(비식별 신고 412 게이트가 그대로 적용, CWE-359) | security | High | lib/api/authImageStore.ts:19-24,107-118 · components/common/__tests__/AuthImage.test.tsx:119,158,348 |
| TC-FE-217 | 실패 경로는 새 소비자 마운트 시 1회 재요청 (신규) | 첫 요청 실패 후 재마운트 | acquire | `failed` 엔트리를 재사용해 **acquire 시점에만** 재요청(자동 재시도 루프 없음, 세대 교차로 인한 조기 revoke 없음) | component | Med | lib/api/authImageStore.ts:51-105 · components/common/__tests__/AuthImage.test.tsx:279 |
| TC-FE-218 | 폴백 `<div>` 에 img 전용 속성 미전개 (신규) | width/height/loading 등 전달 | 로딩·에러 폴백 | React 무효 DOM 속성 경고 없음(IMG_ONLY_PROPS 제거 후 전개) | component | Low | components/common/AuthImage.tsx:27-47 · components/common/__tests__/AuthImage.test.tsx:322 |

## H-13. 접근성 (a11y)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-A11Y-001 | 우측 패널 탭 role=tab/tabpanel | 라벨링 | 렌더 | role=tablist/tab, aria-selected/controls | a11y | High | pages/label/LabelingPage.tsx:1205-1310 |
| TC-A11Y-002 | AiToolModal 라디오/체크박스 label 연결 | 팝업 | 렌더 | htmlFor↔id | a11y | High | features/label/components/AiToolModal.tsx:198,209,255,298 |
| TC-A11Y-003 | Modal 포커스 트랩+ESC+포커스 복귀 | 모달 열림 | ESC | 닫힘+포커스 복귀 | a11y | High | components/common/Modal.tsx |
| TC-A11Y-004 | 마킹 키보드 전 조작(Space/Del/Enter) | MANUAL | 키보드 | 마우스 없이 완결 | a11y | High | pages/MarkingPage.tsx:133-152 |
| TC-A11Y-005 | 라벨링 단축키(W/S/F/Q/T/?/Ctrl+C/V) | 라벨링 | 키 | 이동/폴리곤/표시토글/치트시트/복붙 | a11y | High | pages/label/LabelingPage.tsx:846-901 |
| TC-A11Y-006 | 잠금 배너 aria-live=polite | isLocked | 렌더 | role=status aria-live | a11y | Med | pages/label/LabelingPage.tsx:1079-1090 |
| TC-A11Y-007 | 진행률 progressbar aria-valuenow | 업로드 | 렌더 | role=progressbar+aria-value* | a11y | Med | pages/portal/PortalUploadPage.tsx:202-210 |
| TC-A11Y-008 | 이슈 배지 aria-label 카운트 | 미해소>0 | 렌더 | aria-label="미해소 문의 N건" | a11y | Med | pages/label/LabelingPage.tsx:1264 |
| TC-A11Y-009 | 삭제 버튼 aria-label(파일명) | 자산 목록 | 렌더 | aria-label="{파일명} 삭제" | a11y | Low | pages/portal/PortalUploadPage.tsx:312 |
| TC-A11Y-010 | 아이콘 aria-hidden(중복 낭독 방지) | 아이콘 | 렌더 | aria-hidden | a11y | Low | components/common/BatchStageIndicator.tsx:35,41,48 |
| TC-A11Y-011 | KRDS 포커스링 키보드 초점 | Tab | 포커스 | KRDS_FOCUS 가시 초점 | a11y | Med | lib/focusRing.ts |
| TC-A11Y-012 | 검수 캔버스 aria-label 읽기전용 | 검수 | 렌더 | aria-label="검수 캔버스 (읽기 전용)" | a11y | Low | pages/ReviewPage.tsx:297 |
| TC-A11Y-013 | KPI 필터 카드 aria-pressed 토글 시맨틱 (신규) | 클릭형 KPI 카드 | 카드 선택/해제 | `<button aria-pressed>` 로 선택 상태 전달 + **테두리 두께**로도 구분(색상 단독 금지). `onClick` 없는 카드는 `aria-pressed` 자체가 붙지 않음 | a11y | High | components/common/KpiCard.tsx:19-29,48-62 |
| TC-A11Y-014 | 정렬 가능 헤더 aria-sort + button (신규) | 작업목록 헤더 | 촬영일시/영상 ID 클릭 | `<th aria-sort=ascending\|descending\|none>` + 내부 `<button>`. 정렬 상태를 아이콘·색이 아니라 aria-sort 로 전달 | a11y | High | features/task/components/TaskBoardTable.tsx:58-84 |

## H-14. 보안 (XSS/토큰/용어정책)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-189 | 프레임 설명 script 입력 텍스트 렌더(XSS) | `<script>` | PUT 후 표시 | 텍스트 escape | security | High | e2e/specs/frame-description.spec.ts:191 |
| TC-FE-190 | dangerouslySetInnerHTML 미사용 전수 | 전 컴포넌트 | 정적 검사 | **JSX 속성으로서의 사용 0건**. ⚠ 단순 grep 은 22건 매치되나 전부 "미사용" 을 명시한 **주석·테스트 문자열**이다 — `dangerouslySetInnerHTML={` 패턴으로 검사할 것 | security | High | (grep: `dangerouslySetInnerHTML={`) |
| TC-FE-191 | FE 문구에 YOLO/SAM2 금지 | AI 도구/배치단계 | 렌더 | "AI 탐지/AI 분할/AI 추적", 미지 단계는 "처리중". ⚠ 예외: 시스템 설정의 설정 **키 이름**(`YOLO_CONF_THRESHOLD` 등)은 코드 식별자로 화면 문구가 아님 | security | High | features/label/components/AiToolModal.tsx:4-13 · components/common/BatchStageIndicator.tsx:26-28 |
| TC-FE-192 | 에러 메시지 내부경로/스택 미노출 | BE 에러 | 렌더 | 사용자 문구만(CWE-209) | security | High | components/common/ErrorBoundary.tsx:22 · lib/api/resolveApiMessage.ts:4 |
| TC-FE-193 | 사용자 ID axios URL 인코딩(IDOR/Path 방어) | id 경로 | 요청 | axios 인코딩+BE 재검증 | security | Med | features/label/api.ts · features/review/api.ts |
| TC-E2E-012 | 프레임 설명 저장 실패 에러 표시 | PUT 실패 | 저장 | 에러 표시 | e2e | Med | e2e/specs/frame-description.spec.ts:158 |
| TC-E2E-013 | 프레임 설명 기존값 표시+PUT 반영 | 프레임 선택 | 입력/저장 | 기존 표시+PUT | e2e | Med | e2e/specs/frame-description.spec.ts:94,113 |

## H-15. E2E 전체 사용자 시나리오

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-E2E-014 | WORKER 라벨링: 목록→캔버스→BBox 저장 | WORKER | serial | 저장 토스트 | e2e | High | e2e/specs/labeling-flow.spec.ts:10-25 |
| TC-E2E-015 | WORKER 라벨링 진입 도구바 렌더 | WORKER | 진입 | 바운딩박스 버튼 | e2e | Med | e2e/specs/worker-labeling.spec.ts:12-28 |
| TC-E2E-016 | 전체 워크플로우: 라벨링→저장→제출→반려→롤백→재제출→승인 | WORKER+REVIEWER | serial | 각 단계 통과 | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts:25-174 |
| TC-E2E-017 | WORKER 이력 패널 오픈 후 롤백 | 반려 후 | 롤백 | 버전 롤백 | e2e | Med | e2e/specs/labeling-review-full-flow.spec.ts:120 |
| TC-E2E-018 | REVIEWER 반려 처리 | 검수 진입 | 반려 | 반려 상태 전이 | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts:85 |
| TC-E2E-019 | REVIEWER 최종 승인 | 재제출 후 | 승인 | COMPLETED 전이 | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts:174 |

## H-16. 작업목록 필터·정렬·KPI (SCR-TASK-001, 서버 이관) — 신설

> ★ 구속 정책(루트 CLAUDE.md "목록 화면 정렬·필터 정책"): **정렬은 시간축 단일**(상태 우선순위 `ORDER BY CASE` 금지), **필터·집계는 BE 에서 전체 기준**, **미등록 정렬 키는 이 엔드포인트에서 strict 400**. 검수목록(H-17)의 lenient 정책과 **통일하지 말 것**.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-219 | 진입 기본 정렬 = 등록일 최신순 명시 전송 (신규) | REVIEWER, URL 에 sort 없음 | 목록 진입 | 요청 `sort=regDt,desc` **명시 전송**(BE 기본값 의존 금지). 상태 우선순위 정렬은 어디에도 없다 | component | High | features/task/boardSort.ts:38-40 · features/task/boardParams.ts:126-140 |
| TC-FE-220 | 정렬 키는 allowlist 매핑으로만 해석 (신규) | `?sort=priority,desc` 등 미등록 키 | 진입 | `parseBoardSort` 가 **조용히 제거** → BE 400 미발생. 매핑 대상은 regDt/shtDt(capturedAt)/rawSn(videoId) 뿐 | security | High | features/task/boardSort.ts:18-24,102-114 · features/task/__tests__/boardSort.test.ts |
| TC-FE-221 | 배치 상태 축은 URL 로 못 바꾼다(COMPLETED 고정) (신규) | `?status=UNASSIGNED` (구 북마크) | 진입 | 요청 `status=COMPLETED` 고정. 구 URL 이 파이프라인 미완료 영상을 노출하고 부제("처리 완료된 영상만 표시")를 거짓으로 만들던 문제 차단 | security | High | features/task/boardParams.ts:14-17,59,131 |
| TC-FE-222 | URL 키 `status` 는 워크플로 축으로 해석(하위호환) (신규) | `?status=REVIEW_PENDING` | 진입 | `workStatus=REVIEW_PENDING` 로 요청, 상태 select 도 동일 값 표시 | component | High | features/task/boardParams.ts:168-176 · features/task/__tests__/boardParams.test.ts |
| TC-FE-223 | allowlist 밖 워크플로 값은 무필터로 폴백 (신규) | `?status=BOGUS` | 진입 | 필터 미적용(빈 문자열) — 400 없이 전체 표시 | security | Med | features/task/boardParams.ts:80-96 |
| TC-FE-224 | IN_PROGRESS 는 UI 값이되 서버 미전송 (신규) | WORKER 시각 URL `?status=IN_PROGRESS` | 진입 | select 값은 유지(클라이언트 필터), 서버 파라미터에서는 제외(`asWorkStatusParam`→undefined) | component | High | features/task/boardParams.ts:74-96 |
| TC-FE-225 | REVIEWER 진입 시 IN_PROGRESS 필터 제거 (신규) | REVIEWER + `?status=IN_PROGRESS` | 진입 | 상태 select 는 빈칸인데 목록만 전체인 어긋난 화면 방지 — 초기 state 에서 `workStatus=''` 로 정규화 | component | Med | pages/TaskListPage.tsx:85-92 |
| TC-FE-226 | 정렬 키 3개 상한 + 서버키 중복 제거 (신규) | 헤더 4회 연속 클릭 / `videoId`+`rawSn` 동시 지정 | 정렬 | 최대 3개(가장 오래된 키 버림), 같은 서버 키는 1개만 → BE 400(개수 상한·중복) 미발생 | security | High | features/task/boardSort.ts:35,54-65,120-130 |
| TC-FE-227 | 헤더 클릭 = 1순위 승격 + desc→asc 토글 (신규) | 기본 정렬 regDt,desc | 촬영일시 헤더 1회/2회 클릭 | 1회=`shtDt,desc` 가 **맨 앞**, 2회=`shtDt,asc`. 뒤에 붙이면 regDt 가 지배해 무효 클릭이 된다 | component | High | features/task/boardSort.ts:46-65,86-93 |
| TC-FE-228 | URL 왕복 후에도 aria-sort 유지(서버키 비교) (신규) | `?sort=rawSn,desc` 로 재진입 | 영상 ID 헤더 | `aria-sort=descending` 유지(컬럼명이 videoId→rawSn 으로 바뀌어도 서버 키로 비교) | a11y | Med | features/task/boardSort.ts:73-80 · features/task/components/TaskBoardTable.tsx:63-67 |
| TC-FE-229 | 기본 정렬은 URL 에 기록하지 않음 (신규) | 기본 정렬 상태 | URL 동기화 | `sort` 파라미터 없음(왕복 시 기본값 복원 — 멱등). 비기본 정렬만 기록 | component | Med | features/task/boardParams.ts:188-202 |
| TC-FE-230 | 초기화가 정렬까지 기본값 복원 (신규) | `?sort=regDt,asc` (헤더 없는 축) | "초기화" 클릭 | 필터 + **정렬** 모두 기본값. regDt 는 컬럼 헤더가 없어 토글로 되돌릴 수 없으므로 이 버튼이 유일한 복구 경로 | component | High | pages/TaskListPage.tsx:395-402 |
| TC-FE-231 | KPI 5카드 = 서버 집계 + 클릭 토글 (신규) | REVIEWER | 카드 클릭/재클릭 | 전체/미배정/작업중/검수요청/반려 5카드. 클릭=해당 `workStatus` 필터, 재클릭=해제(전체). 숫자는 `/v1/tasks/board/summary` 전체 기준(현재 페이지 20건 아님) | component | High | features/task/components/TaskBoardKpiCards.tsx:30-121 · pages/TaskListPage.tsx:404-412 |
| TC-FE-232 | KPI '작업중' = PENDING 축 (신규) | 배정됐고 검수 미제출인 영상 존재 | KPI 확인 | '작업중' 카드가 `summary.inProgress`(=BoardWorkStatus.PENDING 집계)를 표시하고 클릭 시 `workStatus=PENDING` 전송. **`IN_PROGRESS` 는 BE 가 반환하지도 허용하지도 않는다** → 구 결함(REVIEWER 시각 0 고정) 재발 없음 | component | High | features/task/components/TaskBoardKpiCards.tsx:92-100 · features/task/types.ts:126-142,175-188 |
| TC-FE-233 | KPI 집계 요청에서 workStatus 제외 (신규) | 카드 선택된 상태 | summary 요청 | `workStatus` 미전송(카드 자체가 선택지). 검색어/이벤트/작업자 필터는 반영 | component | High | features/task/boardParams.ts:142-157 |
| TC-FE-234 | KPI 로딩/실패/정상 3상태 구분 (신규) | summary 로딩 · 실패 | 렌더 | 로딩=스켈레톤(`kpi-loading`), 실패=`kpi-error` "집계 정보를 불러오지 못했습니다. 목록은 정상 표시됩니다."(role=status). **페이지 레벨 에러는 목록 쿼리만 결정** — KPI 실패가 목록을 가리지 않음 | component | High | features/task/components/TaskBoardKpiCards.tsx:37-66 · pages/TaskListPage.tsx:174-176 |
| TC-FE-235 | REVIEWER 시각 클라이언트 재필터 금지 (신규) | 서버 필터 적용된 20건 | 렌더 | `visibleRows === allRows` (재필터 없음). 헤더 "전체 N건"=서버 `totalElements` — 목록/총건수/KPI 가 같은 집합을 말한다 | component | High | pages/TaskListPage.tsx:316-317,340-352 |
| TC-FE-236 | 체크박스 선택이 페이지 전환 후 잔존하지 않음 (신규) | 1페이지에서 3건 선택 → 2페이지 이동 | 일괄 배정 | 선택 초기화(`clearSelection`) + 화면에 없는 선택은 effect 가 제거 → **두 페이지 영상이 섞여 배정되던 결함** 재발 없음 | security | High | pages/TaskListPage.tsx:368-381,423-429 |
| TC-FE-237 | 필터·KPI·정렬 변경 시 page 0 + 선택 해제 (신규) | 3페이지에서 필터 변경 | 조회 | page=0 으로 리셋(같은 이벤트에서 처리 — 직전 페이지로 요청이 한 번 더 나가지 않음), 선택 해제 | component | High | pages/TaskListPage.tsx:385-421 |
| TC-FE-238 | 총 페이지 축소 시 범위 복귀 (신규) | 5페이지 보다 결과가 2페이지로 감소 | 재조회 | `page`→마지막 페이지로 되돌림(빈 목록+페이지네이션 소실로 복구 불가해지는 상태 방지) | component | Med | pages/TaskListPage.tsx:362-366 |
| TC-FE-239 | 이벤트유형 옵션 서버 조회 + truncated 안내 (신규) | 옵션 상한 초과 | 필터 렌더 | `{items,truncated}` **객체** 응답을 훅이 분해(배열 오인 `.map` 금지). `truncated=true` 면 "옵션이 많아 일부만 표시됩니다". 조회 실패해도 빈 옵션으로 폴백(목록은 유지) | component | Med | features/task/hooks/useTaskBoardEventTypes.ts:19-36 · features/task/components/TaskFilters.tsx:157-164 |
| TC-FE-240 | 목록 조회 실패 시 배정 액션 잠금 (신규) | board 쿼리 실패 | 렌더 | ErrorState + 체크박스/배정 버튼 disabled + title 안내(최신 아닌 목록으로 배정 방지). WORKER 에게는 "배정 기능" 안내 미노출 | security | High | pages/TaskListPage.tsx:542-554,460-465 · features/task/components/TaskBoardTable.tsx:149-163,320-340 |
| TC-FE-241 | 검색어 100자 상한(400 왕복 방지) (신규) | 101자 입력 | 조회 | input `maxLength=100` + 파라미터 `slice(100)` (BE `@Size(max=100)` 정합) | component | Med | features/task/boardParams.ts:53,106-109 · features/task/components/TaskFilters.tsx:126 |
| TC-FE-242 | WORKER 시각은 클라이언트 필터 + 4카드 유지 (신규) | WORKER | 검색/상태/이벤트 필터 | `/v1/assignments` 가 필터·정렬을 지원하지 않아 화면에서 거른다. 헤더 "전체 N건"=거른 행 수. KPI 는 표시 전용 4카드(클릭 필터 없음) | component | Med | pages/TaskListPage.tsx:319-334,350-352 · features/task/components/TaskWorkerKpiCards.tsx:12-53 |

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

---

> **불확실 항목 갱신 (2026-07-30)**
> - **#23 VideoPlayer 배속 — 코드로 확정**: `SPEED_OPTIONS=[0.25,0.5,1,1.5,2,4]` 이산 6버튼 + 기본 1x. **자유 수치 입력이 없어 클램프 로직 자체가 불필요**하다 → TC-FE-205 로 케이스화. (`features/marking/components/VideoPlayer.tsx:34,46-49,125-140`)
> - **#25 Dev 페이지/내부 TUS — 코드로 확정**: `pages/dev/DevAutolabelTestPage.tsx` 는 **존재**하며 `isDevUploadEnabled()` 빌드 플래그 안에서만 라우팅된다(`router/index.tsx:183-203`). 내부 TUS(`features/upload/*`, 기본 엔드포인트 `/uploads`)의 **유일한 소비자가 이 dev 페이지**이므로, 플래그 OFF 인 prod 빌드에서는 두 청크 모두 산출물에서 제거된다 → "dead-code 여부" 는 **prod 빌드 기준 dead-code 맞음 / dev 빌드에서는 살아있음**. 별도 폐지 작업 없이 TC-FE-028·029 의 플래그 게이팅만 검증하면 된다. (포털 TUS `/portal/uploads/tus` 는 별개이며 운영 경로다 — TC-FE-169)
> - **#22 캔버스 드로잉 픽셀 정확도** · **#24 반응형/WCAG 색대비**: 미확정 유지(런타임 브라우저 도구 필요).
> 상세는 [UNCERTAINTIES.md](UNCERTAINTIES.md)
