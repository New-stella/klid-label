# H 클러스터 part1 (H-1·H-2·H-4·H-5) 2차 검증 결과

> 검증 대상: `docs/test-cases/H-frontend-e2e.md` H-1(26)·H-2(11)·H-4(14)·H-5(8) = 59건
> 방법: 소스 실측(Read/Grep) + 테스트 커버 확인(baseline 대조, 실행 안 함) + HTTP 정적확인(dev서버 :13000) + H-5는 좌표변환 함수 손계산 대조
> 폐기(취소선) 행: 이 4개 섹션에는 없음(전건 검증 대상)
> 환경: frontend dev서버 :13000(Vite), backend :18081(HEAD ca3c712b). 이 4개 섹션은 전량 FE 정적 로직/컴포넌트 계층이라 backend HEAD 뒤처짐(stack-bringup.md §2) 영향 없음.

## 집계

| 섹션 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-1 | 26 | 25 | 0 | 0 | 1 | 0 | 0 |
| H-2 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| H-4 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| H-5 | 8 | 8 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **59** | **58** | **0** | **0** | **1** | **0** | **0** |

- FAIL/PARTIAL/확인필요 0건 — 이번 회차 소스는 이미 07-30 1회차 최신화에서 근거 file:line이 전면 재확인된 상태라 드리프트도 경미했다.
- BLOCKED 1건(TC-E2E-001) — 실제 Playwright 브라우저 내비게이션 실행이 필요한 유일한 케이스. 나머지 "e2e" 계층 표기 케이스(TC-E2E-002)는 실제로는 RTL(jsdom) 컴포넌트 테스트이며 vitest baseline(1691건)에 포함돼 실행·통과가 확인됐다.
- 테스트 커버가 없는 PASS 2건(TC-FE-020, TC-FE-031)은 소스 로직은 명확하나 전용 단위테스트가 없음 — 비고에 명시.

## H-1 결과표 (인증/라우팅 가드, 26건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-001 | RoleGuard 하이드레이션 대기 | PASS | [정적] guards.tsx:34-40 정확 일치 — isHydrated=false 시 스피너 반환 | |
| TC-FE-002 | RoleGuard claims 없음 → ingress | PASS | [정적] guards.tsx:41-43 정확 일치 | |
| TC-FE-003 | RoleGuard exp 만료 → 상위 redirect | PASS | [정적] guards.tsx:27-32(useEffect clear+redirectToUpstream),44-51(만료 시 children 대신 스피너 반환) | 반증 확인: 만료 시 렌더는 children이 아니라 스피너를 반환하므로 "가드가 렌더 후 걸려 한 프레임 노출"되는 문제 없음 |
| TC-FE-004 | RoleGuard role=null → role-claim | PASS | [정적] 실제 조건문은 guards.tsx:54-56(카탈로그 52-55는 주석 포함 범위) | 근거 드리프트 경미(2줄) |
| TC-FE-005 | RoleGuard 역할 불일치 → forbidden | PASS | [정적] guards.tsx:57-58 정확 일치 | |
| TC-FE-006 | RoleGuard 역할 일치 통과 | PASS | [정적] guards.tsx:60 정확 일치 | |
| TC-FE-007 | ChannelGuard 채널 불일치 → forbidden | PASS | [정적] guards.tsx:88-89 정확 일치 | |
| TC-FE-008 | AuthenticatedGuard role=null 통과 | PASS | [정적] 실제 함수는 guards.tsx:114-144(카탈로그 104-143은 주석 포함, ~10줄 드리프트) — role 검사 없음 확인, RoleGuard의 role-claim 무한루프 방지 설계 확인 | 근거 드리프트 |
| TC-FE-009 | isExpired: exp=0/미지정은 만료 아님 | PASS | [정적] guards.tsx:94-97 정확 일치 — `exp<=0 → false`, `typeof!=='number' → false` 손확인 | |
| TC-FE-010 | JWT payload role 화이트리스트 | PASS | [정적] useAuthStore.ts:47-48 정확 일치. lib/api/types.ts:24-29 Role enum={REVIEWER,WORKER,PORTAL_USER} — "ADMIN"은 미포함이라 isRole()=false→claims=null 확인 | |
| TC-FE-011 | JWT role 빈값 허용(자가부여 대기) | PASS | [정적] useAuthStore.ts:47-49 정확 일치 | |
| TC-FE-012 | JWT channel/sub/exp 누락 무효 | PASS | [정적] useAuthStore.ts:54 정확 일치 | |
| TC-FE-013 | JWT 한글 name UTF-8 디코드 | PASS | [정적] useAuthStore.ts:29-40 TextDecoder 사용 확인 | |
| TC-FE-014 | JWT parts≠3 무효 | PASS | [정적] useAuthStore.ts:24 정확 일치 | |
| TC-FE-015 | hydrate: 만료 토큰 sessionStorage 제거 | PASS | [정적] useAuthStore.ts:89-102 hydrate() 로직 일치 | |
| TC-FE-016 | 토큰 저장소=sessionStorage | PASS | [정적] useAuthStore.ts:76,82,86,90 4곳 전부 sessionStorage.setItem/getItem/removeItem 정확 일치, localStorage 미사용 확인 | |
| TC-FE-017 | axios 요청 인터셉터 Bearer 주입 | PASS | [정적][실동작] client.ts:32-38 정확 일치, client.test.ts `axios_interceptor_Bearer_토큰_자동_주입` 통과(baseline) | |
| TC-FE-018 | ApiResponse 언랩 — data 추출 | PASS | [정적][실동작] client.ts:41-49 정확 일치, client.test.ts `ApiResponse_data_필드_unwrap_성공` 통과 | |
| TC-FE-019 | ApiResponse success=false → ApiError | PASS | [정적][실동작] client.ts:43-45 정확 일치, client.test.ts `ApiResponse_success_false면_ApiError로_변환` 통과 | |
| TC-FE-020 | 401 토큰 레이스 1회 재시도 | PASS | [정적] client.ts:54-71 정확 일치, `_retriedWithToken` 플래그로 1회만 재시도 확인(무한루프 방지) | 이 분기 전용 단위테스트는 없음(client.test.ts엔 정상 401만 존재) — 테스트 커버 갭 |
| TC-FE-021 | 정상 401 → clear+상위 로그인 | PASS | [정적][실동작] client.ts:72-75 정확 일치, client.test.ts `401_응답시_redirectToUpstreamLogin_호출` 통과 | |
| TC-FE-022 | baseURL 환경변수만(Open Redirect 방어) | PASS | [정적] client.ts:23-30 정확 일치 — `import.meta.env.VITE_API_BASE_URL` 만 사용, 사용자 입력 경유 없음 | |
| TC-FE-194 | 412 → errorCode PRECONDITION_FAILED 매핑 (신규) | PASS | [정적][실동작] errors.ts:37,47 정확 일치, errors.test.ts `412는_PRECONDITION_FAILED로_매핑된다` 등 3건 통과 | |
| TC-FE-195 | resolveApiMessage — 400/409/412만 서버 문구 노출 (신규) | PASS | [정적][실동작] resolveApiMessage.ts:4,16-19 정확 일치, errors.test.ts describe 블록 4건 통과(403/5xx는 fallback 확인) | |
| TC-FE-196 | compactParams 빈 값 키 제거 · 0/false 보존 (신규) | PASS | [정적][실동작] compactParams.ts:12-21 정확 일치, compactParams.test.ts 4건 통과(0/false 보존, 원본 불변 포함) | |
| TC-E2E-001 | 토큰 없이 보호 경로 → 상위 로그인 | **BLOCKED** | e2e/specs/login-redirect.spec.ts 존재 확인. Playwright 스펙이며 vitest.config.ts:20에서 `exclude: ['e2e/**']` — baseline(1691건)에 미포함, 이번 회차 미실행 | **사유: 브라우저 자동화 필요.** 단 근거 체인(guards.tsx RoleGuard claims-null→`/ingress` Navigate, `SessionIngressPage.tsx` handleAuthFailure, `redirectToUpstream.ts`)은 각각 단위테스트로 개별 검증되고 로직 결함 없음(TC-FE-002·TC-FE-022 PASS 참조 + `redirectToUpstream.test.ts` 존재) — 실제 브라우저 내비게이션 결과만 미확인 |

## H-2 결과표 (라우터 구조/코드스플리팅, 11건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-023 | `/` → `/dashboard` redirect | PASS | [정적] router/index.tsx:233 정확 일치 | |
| TC-FE-024 | `/label/:id`는 풀스크린 | PASS | [정적] router/index.tsx:218-224(카탈로그 216-224, 주석 포함 2줄 드리프트) — 이 route는 `<AppLayout/>` 트리(227행~) 밖 최상위 배열 항목이라 LNB/GNB 미포함 확인 | |
| TC-FE-025 | REVIEWER 전용 라우트 게이팅 | PASS | [정적] router/index.tsx:290(review index)·337(stat/overall)·350(augment index)·380(manage/users) 4곳 전부 `internalReviewerOnly` 정확 확인 | |
| TC-FE-026 | 알 수 없는 내부 경로 → 404 | PASS | [정적][실동작] router/index.tsx:459 정확 일치. HTTP `GET /xyz`→200(dev서버 SPA fallback, 클라이언트 라우팅은 JS 실행 필요) | 라우터 레벨 전용 테스트 없음(404 렌더 자체는 컴포넌트 로직으로 명확) |
| TC-FE-027 | manage/* placeholder REVIEWER 게이팅 | PASS | [정적] router/index.tsx:417-424 정확 일치 | |
| TC-FE-028 | dev 라우트 플래그 OFF dead-code 제거 | PASS | [정적] router/index.tsx:168-181(카탈로그 167-181, 1줄 드리프트) + lib/devLogin.ts `isDevLoginEnabled()` = `import.meta.env.DEV \|\| VITE_DEV_LOGIN_ENABLED==='true'` 확인 | 실제 prod 빌드 산출물의 트리쉐이킹 결과는 `npm run build` 실행 필요(이 작업 금지 규칙상 미실행) — Vite define 정적 치환 기반 통상 패턴이라 정적 근거로 충분(UNCERTAINTIES #25 동일 패턴 선례) |
| TC-FE-029 | devUpload 라우트 REVIEWER 제한 | PASS | [정적] router/index.tsx:183-203 정확 일치 + lib/devUpload.ts `isDevUploadEnabled()` 동일 패턴 확인, UNCERTAINTIES #25 확정사항과 일치(dev서버=DEV=true라 현재 :13000엔 라우트 존재) | |
| TC-FE-030 | PortalRoute 채널+역할 이중가드 | PASS | [정적] router/index.tsx:160-166 정확 일치 — ChannelGuard(PORTAL)+RoleGuard(portalOnly) 이중 래핑 확인 | |
| TC-FE-031 | lazyWithRetry 청크 로드 실패 재시도 | PASS | [정적] router/lazyWithRetry.ts 전체 — 1차 실패 시 300ms 지연 후 재시도, 재시도도 실패 시 세션당 1회만 reload(sessionStorage 플래그) 확인 | 전용 단위테스트 파일 없음(find 결과 0건) — 테스트 커버 갭 |
| TC-FE-032 | Suspense PageFallback 스피너 | PASS | [정적] router/index.tsx:127-137 정확 일치 | |
| TC-E2E-002 | 라우트 가드 스위트 | PASS | [정적][실동작] router/__tests__/{deepLinkHydrationGuard,manageGuard,portalGuard,reviewGuard,augmentExportGuard}.test.tsx 5개 파일 전부 존재, 각 파일의 개별 it() 케이스명 확인(WORKER forbidden, REVIEWER 허용, PORTAL_USER 채널가드 등). vitest.config.ts는 `e2e/**`만 제외하므로 이 파일들은 baseline(1691건 GREEN)에 포함되어 실제 실행·통과됨 | 카탈로그가 "e2e" 계층으로 표기했으나 실제로는 RTL(jsdom) 컴포넌트 테스트 — 실행 증거 있음(Playwright 아님) |

## H-4 결과표 (useLabelStore Zustand, 14건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-088 | setLabels 시 dirty/undo/redo/선택 초기화 | PASS | [정적][실동작] useLabelStore.ts:401-410(카탈로그는 401만 표기, 함수 시작줄) — dirtyLabels/undoStack/redoStack/selectedLabelId/hiddenLabelIds/lockedLabelIds 전부 초기화 확인. `useLabelStore.test.ts` `setLabels_시_dirty_undo_초기화` 통과 | |
| TC-FE-089 | addLabel dirty 마킹 | PASS | [정적][실동작] useLabelStore.ts:412-418 확인, `useLabelStore.test.ts` `addLabel_시_dirtyLabels에_추가` 통과 | |
| TC-FE-090 | 좌표 이동 shape별 | PASS | [정적] useLabelStore.ts:66-89 정확 일치 — BBOX/POLYGON/KEYPOINT 3종 shift 로직 확인(불변 — 새 객체 반환) | |
| TC-FE-091 | clampShape 경계 [0,w]/[0,h] | PASS | [정적] useLabelStore.ts:92-117 정확 일치 — 손계산: w 미지정 시 `Math.max(v,0)`만 적용(상한 클램프 없음), w 지정 시 `[0,w]` 양쪽 클램프. 기대결과("미지정 시 하한 0만 적용")와 일치 | |
| TC-FE-092 | mergeAutoLabels 중복 스킵+개수 | PASS | [정적][실동작] `useLabelStore.mergeAuto.test.ts` 존재, 소스 551-568 mergeDetections+IoU dedup 확인 | |
| TC-FE-093 | revertSaveEvent 역적용 | PASS | [정적][실동작] `useLabelStore.revert.test.ts` 존재, 소스 570-649 UPDATED/ADDED/DELETED 3분기 확인 | |
| TC-FE-094 | pendingTracks stash/drain | PASS | [정적][실동작] `useLabelStore.pendingTracks.test.ts` 존재, 소스 651-673 stash(누적)/drain(제거 후 반환) 확인 | |
| TC-FE-095 | 라벨 표시/숨김 토글(세션) | PASS | [정적][실동작] `useLabelStore.visibility.test.ts` 존재, 소스 482-487 새 Set 생성(불변성) 확인, setLabels/reset 시에만 초기화 | |
| TC-FE-096 | 라벨 잠금 편집 no-op | PASS | [정적][실동작] `useLabelStore.lock.test.ts` 존재, 소스 420-422(updateLabel)·431-433(removeLabel) lockedLabelIds 체크 후 조기 return 확인 | |
| TC-FE-097 | 클립보드 copy/paste(sourceRawSn) | PASS | [정적][실동작] `useLabelStore.clipboard.test.ts` 존재, 소스 497-549 크로스영상 판정(trackId 제거)+동일좌표 offset+clamp 확인 | |
| TC-FE-098 | 키포인트 17종 배치/편집 | PASS | [정적][실동작] `useLabelStore.keypoint.test.ts` 존재 | |
| TC-FE-099 | imageAdjust 밝기/대비/투명도(세션) | PASS | [정적][실동작] `useLabelStore.imageAdjust.test.ts` 존재, 소스 476-479 세션 전용(영속 계층 없음) 확인 | |
| TC-FE-100 | activeLabel 파생 프리셋 기본 | PASS | [정적][실동작] `useLabelStore.activeLabel.test.ts` 존재 | |
| TC-FE-101 | clampPan 스케일 기반 뷰 제한 | PASS | [정적] useLabelStore.ts:4(import),319-339(카탈로그 319-335, 함수 끝 4줄 드리프트) — `canvasGeometry.ts` clampPan(16-30)에 위임 확인, `buildGeometry`는 image size≤0 방어(scale=0 sentinel) 확인 | |

## H-5 결과표 (좌표 변환/캔버스 유틸, 8건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-102 | translateToCanvas 이미지→캔버스 | PASS | [정적][실동작] coordinateTransformer.ts:43-52(카탈로그 43-51) 정확. 손계산: geom={scale:2,left:20,top:10,angle:0}, image(50,50)→canvas(120,110) 수식(`x*scale+left`) 일치. `coordinateTransformer.test.ts` `translateToCanvas_scale_offset_적용` 통과 | |
| TC-FE-103 | translateFromCanvas 왕복 정합 | PASS | [정적][실동작] coordinateTransformer.ts:57-65(카탈로그 57-61) 정확. 손계산 2건: (a) angle=0, scale=2, offset(20,10) 왕복 정확 일치 (b) angle=90, image 100×100 중심(50,50) 기준 (0,0)→회전(100,0)→역회전 (0,0) 왕복 오차 0 확인(회전 중심이 이미지 좌표계로 일관돼 scale 적용 전에 회전하므로 함정 없음). 테스트도 scale=0.5/0.8·angle=0/30 조합으로 `toBeCloseTo` 검증 | canvas scale 함정 명시 검증 완료 — translateToCanvas가 스케일 전에 회전을 적용하고 translateFromCanvas가 스케일 역산 후 동일 중심으로 역회전하므로 라운드트립 정확(부동소수 오차는 1e-10 수준, toBeCloseTo(4~5) 통과) |
| TC-FE-104 | clampToImage 경계 clamp | PASS | [정적] coordinateTransformer.ts:132-137 정확. 손계산: image 100×100, 입력(-10,-10)→(0,0), 입력(150,150)→(100,100) 확인. clamp() 자체 단위테스트(범위내/하한/상한 3건) 통과 | |
| TC-FE-105 | computeWrappingBox 외접박스 | PASS | [정적][실동작] coordinateTransformer.ts:106-120 정확, 테스트 `점_배열의_외접_사각형_계산`·`단일_점도_정상_처리` 통과 | |
| TC-FE-106 | rotate2DPoints 회전 유틸 | PASS | [정적][실동작] coordinateTransformer.ts:71-87 정확, 테스트 90도/360도/여러점 배열 3건 통과 | |
| TC-FE-107 | maskRleConverter MASK↔RLE↔Polygon | PASS | [정적][실동작] maskRleConverter.ts 전체 확인 — MAX_PIXELS=1,000,000 DoS 방어(maskToRle/imageDataToRLE/rleToImageData 3곳), rleEncode/rleDecode 표준 RLE 로직. `maskRleConverter.test.ts`(187줄) 존재 | |
| TC-FE-108 | trackInterpolation 트랙 보간 | PASS | [정적][실동작] trackInterpolation.ts 전체 확인 — interpolateBBox(t clamp [0,1]), interpolateTrack(범위 밖은 최근접 끝값, extrapolation 없음, 키프레임 1개 시 그대로 반환) 손계산 확인. `trackInterpolation.test.ts`(71줄) 존재 | |
| TC-FE-109 | 캔버스 좌표 실측 naturalW/H 기준(하드코딩 제거) | PASS | [정적] pages/label/LabelingPage.tsx:242-276(카탈로그 242-274) — "imageWidth/Height 하드코딩 제거" 주석 확인, `frameNaturalSize` state로 실측 dims 관리(초기 undefined, handleImageSize에서 CanvasShell onload 통지 시 설정) 확인 | |

## 근거 드리프트

전부 "카탈로그 범위가 주석/공백 줄을 포함해 실제 코드 시작 줄과 1~10줄 차이" 수준의 경미한 드리프트다. 함수·로직 자체의 오귀속은 없음.

| TC-ID | 카탈로그 근거 | 실제 위치 | 드리프트 |
|---|---|---|---|
| TC-FE-004 | guards.tsx:52-55 | guards.tsx:54-56(조건문 자체) | 주석 2줄 포함 |
| TC-FE-008 | guards.tsx:104-143 | guards.tsx:114-144(함수 정의부) | 인터페이스+주석 ~10줄 포함 |
| TC-FE-024 | router/index.tsx:216-224 | router/index.tsx:218-224(route 객체) | 주석 2줄 포함 |
| TC-FE-028 | router/index.tsx:167-181 | router/index.tsx:168-181 | 1줄 |
| TC-FE-101 | stores/useLabelStore.ts:319-335 | stores/useLabelStore.ts:319-339(clampPan 함수 전체) | 함수 끝 4줄 초과 |

## ★브라우저 자동화 필요 케이스 목록 (BLOCKED 사유별)

### 사유: Playwright e2e 스펙 미실행 (이 세션에 브라우저 자동화 도구 없음)

- **TC-E2E-001** — `e2e/specs/login-redirect.spec.ts`: 토큰 없이 보호 경로 진입 시 실제 브라우저 내비게이션(window.location 변경)으로 상위 로그인 redirect 확인 필요. vitest baseline에서 `e2e/**` 디렉토리가 명시적으로 제외(vitest.config.ts:20)되어 있어 이번 회차에도, 1차 baseline에서도 실행되지 않았다. 다음 단계(브라우저 자동화 가능한 라운드)에서 Playwright로 직접 실행 권장. 근거 체인 자체(guards.tsx/SessionIngressPage/redirectToUpstream)는 개별 단위테스트로 이미 PASS 확인됨 — 이 케이스가 실패할 것으로 예상되진 않으나 실제 브라우저 내비게이션 결과 확인은 미완료.

## 이슈 상세

이번 회차 담당 범위(H-1·H-2·H-4·H-5, 59건)에서 FAIL/PARTIAL/확인필요 0건 — 기록할 이슈 없음.

보안 반증(우회 경로·fail-open·역할/채널 이중가드·Open Redirect 방어·XSS 저장소 선택 등)과 좌표 변환 함정(회전+스케일 왕복, 경계 clamp, undo/redo 스택 상한)을 개별 검토했으나 결함을 발견하지 못했다. 07-30 1회차 최신화에서 근거 file:line이 전면 재확인된 상태였고, 코드 자체도 불변성·방어적 초기화(image size≤0 sentinel)·DoS 방어(1M 픽셀 한도)가 이미 적용돼 있어 이번 슬라이스는 실제로 견고한 것으로 판단된다.
