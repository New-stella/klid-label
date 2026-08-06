# H 클러스터 (FE 화면/컴포넌트/E2E) — 2차 검증 결과

> 293건 · 기준 소스 실측 + BE API 실호출(브라우저 자동화 미사용 — E2E 는 BLOCKED) · 2026-07-31


---

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

---

# H 클러스터 part2 (H-6·H-7·H-8) 2차 검증 결과

> 검증 대상: `docs/test-cases/H-frontend-e2e.md` H-6(20)·H-7(5)·H-8(21) = 46건
> 방법: 소스 실측(Read/Grep) + BE 실호출(dev 토큰, 라이브 DB) + 테스트 커버 확인(baseline 대조, 실행 안 함)
> 폐기(취소선) 행: 이 3개 섹션에는 없음(전건 검증 대상)
> 환경: frontend dev서버 :13000(Vite), backend :18081(HEAD `ca3c712b`). stack-bringup.md §2의 backend 11커밋 뒤처짐은 이 3섹션(MarkingPage/BatchStageIndicator/ReviewPage — 경로순회·심링크·배치 트랜잭션 이슈와 무관한 화면)에는 실질 영향 없음.
> ⚠ 검증 도중 DB 데이터가 stack-bringup.md/pipeline-drive.md 기록과 달라져 있었다(다른 에이전트의 동시 파이프라인 구동) — rawSn=123 은 소멸, 대신 rawSn=126~133 이 살아있는 실측 참조였다. 아래 실호출은 이 최신 데이터로 수행.

## 집계

| 섹션 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-6 | 20 | 20 | 0 | 0 | 0 | 0 | 0 |
| H-7 | 5 | 5 | 0 | 0 | 0 | 0 | 0 |
| H-8 | 21 | 19 | 1 | 0 | 1 | 0 | 0 |
| **합계** | **46** | **44** | **1** | **0** | **1** | **0** | **0** |

- FAIL 1건(TC-FE-146) — ReviewPage 에 증강여부/종류 표시가 실제로는 존재하지 않음(카탈로그 근거 파일 자체에 관련 코드 0건). 상세는 이슈 상세 참조.
- BLOCKED 1건(TC-E2E-004) — `e2e/specs/review-flow.spec.ts` 는 Playwright 스펙이라 vitest 제외 대상(`vitest.config.ts:20`)이며 이 세션엔 브라우저 자동화 도구가 없음. 반면 TC-E2E-003(`pages/__tests__/MarkingPage.test.tsx`) 은 RTL(jsdom) 컴포넌트 테스트로 baseline(1691건)에 포함돼 실행·통과가 확인되므로 PASS 처리(H-part1 의 TC-E2E-002 선례와 동일 판단 기준).
- H-7(BatchStageIndicator)은 실 BE 응답으로 직접 대조했다 — 아래 별도 절 참조. 드리프트 없음.

## ★BE↔FE 상수 드리프트 대조 (STAGE_ORDER 등)

**결론: 드리프트 없음 — 오히려 "드리프트가 구조적으로 불가능하게" 재설계돼 있다.**

- FE `components/common/BatchStageIndicator.tsx` 에는 `STAGE_ORDER`라는 이름의 상수가 **존재하지 않는다**(전체 저장소 grep 0건). 대신 `STAGE_LABEL`(name→한글라벨 매핑)만 있고, 렌더링은 `stages.map(...)`로 **BE 가 내려준 배열 순서를 그대로** 사용한다(정렬·재배치 로직 없음, `BatchStageIndicator.tsx:70`).
- 실 BE 응답 대조: `GET /api/v1/videos/133`(REVIEWER 토큰, 실측 rawSn=133, 배치 전단계 DONE)의 `stages` 필드가 정확히 다음 순서로 왔다.
  ```json
  [{"name":"DEIDENTIFY","status":"DONE"},{"name":"MARKING","status":"DONE"},{"name":"VLM","status":"DONE"},
   {"name":"FRAME_EXTRACT","status":"DONE"},{"name":"YOLO","status":"DONE"},{"name":"SAM2","status":"DONE"},
   {"name":"INTERPOLATE","status":"DONE"}]
  ```
  이는 CLAUDE.md 가 선언한 canonical 순서(`DEIDENTIFY → MARKING → VLM → FRAME_EXTRACT → YOLO → SAM2 → INTERPOLATE`)와 정확히 일치한다.
- FE `STAGE_LABEL` 키 7종(`DEIDENTIFY/MARKING/VLM/FRAME_EXTRACT/YOLO/SAM2/INTERPOLATE`)이 이 실측 7종과 1:1 대응하며, `YOLO→"AI 탐지"`/`SAM2→"AI 분할"`로 기술 모델명이 화면에 새지 않는다(`BatchStageIndicator.tsx:21-22`, FE 문구 규칙 준수). 미매핑 코드는 `"처리중"` 폴백(`:26-28`)이라 향후 BE 가 8번째 단계를 추가해도 크래시 없이 안전.
- 증강 파생 영상(rawSn=129, `derivative:true`)의 `stages` 는 `[]`(빈 배열) — `BatchStageIndicator` 는 이 경우 `null` 반환(`:66`, TC-FE-126)로 렌더 자체를 생략해 상위 배지로 폴백한다. 실측·소스 일치.
- `features/video/types.ts:103-108` 의 `BatchStageStatus`(`DONE|PROGRESS|PENDING|FAIL`)도 BE `StageStatusDto` 와 1:1 정합 주석이 있고, 실측 응답의 `status` 값(`"DONE"`)과 타입이 일치.

## H-6 결과표 (MarkingPage, 20건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-110 | 잘못된 rawSn 에러 | PASS | [정적] MarkingPage.tsx:154-156 정확 일치 | |
| TC-FE-111 | 비식별 미완료 진입 차단(백스톱) | PASS | [정적][실동작] MarkingPage.tsx:158-173 정확 일치, `MarkingPage.test.tsx` `비식별_미완료_영상_직접진입시_마킹차단_백스톱_안내` 통과(baseline) | |
| TC-FE-112 | 스트림 항상 비식별본('N'/'F' 차단) | PASS | [정적][실동작] `features/video/types.ts:isMarkingBlocked` 로직 확인 + **실 HTTP 검증**: rawSn=133(`deIdntfYn='F'`)에 `GET /v1/videos/133/stream-url` 호출 → `404 {"errorCode":"NOT_FOUND","message":"비식별 처리 미완료"}` 실측 확인(원본 노출 없음) | |
| TC-FE-113 | Space → 현재시각 마킹 | PASS | [정적] MarkingPage.tsx:139-141 정확 일치 | |
| TC-FE-114 | Del/Backspace → 선택 마크 삭제 | PASS | [정적] MarkingPage.tsx:142-144 정확 일치 | |
| TC-FE-115 | Enter → 제출 | PASS | [정적] MarkingPage.tsx:145-147 정확 일치 | |
| TC-FE-116 | INPUT/TEXTAREA/SELECT 포커스 시 단축키 억제 | PASS | [정적] MarkingPage.tsx:136-137 정확 일치 — 3종 태그 모두 조기 return 확인 | 키보드 이벤트 전용 단위테스트는 없음(로직은 `e.target.tagName` 순수 분기라 정적 판정으로 충분) |
| TC-FE-117 | AUTO intervalFrames<1 제출 차단 | PASS | [정적] MarkingPage.tsx:118-119 정확 일치 | |
| TC-FE-118 | MANUAL 마크 0건 제출 차단 | PASS | [정적] MarkingPage.tsx:124-125 정확 일치 | |
| TC-FE-119 | 제출 중복 방지(Enter 연타) | PASS | [정적][실동작] MarkingPage.tsx:116 정확 일치, `MarkingPage.test.tsx` `마킹_제출_pending_중_Enter_재호출시_추가_POST_미발생` 통과 | |
| TC-FE-120 | 제출 성공 → clearMarks+/task | PASS | [정적][실동작] MarkingPage.tsx:61-77 정확 일치, `MarkingPage.test.tsx` `마킹_제출_성공시_토스트_표시_및_task_목록으로_이동` 통과 | |
| TC-FE-121 | 이벤트 유형 없는 영상 실패 토스트 | PASS | [정적][실동작] MarkingPage.tsx:81-89 정확 일치, `MarkingPage.test.tsx` `이벤트유형없는영상_400응답시_에러토스트_표시_및_이동안함` 통과 | |
| TC-FE-122 | 스트림 401 만료 1회 재발급 | PASS | [정적] MarkingPage.tsx:49-58 정확 일치 — `streamRetriedRef` 로 1회 제한, finally 에서 재허용(무한루프 방지) 로직 확인 | 이 분기(onSrcError 401 재시도) 전용 테스트는 없음(`I3` 테스트는 최초 서명 URL 주입만 검증) — 테스트 커버 갭 |
| TC-FE-123 | 영상 변경 시 마크 reset+duration fallback | PASS | [정적] MarkingPage.tsx:92-97 정확 일치 | |
| TC-FE-124 | 배치단계 인디케이터 stages 있으면 노출 | PASS | [정적] MarkingPage.tsx:181-187 정확 일치 — `videoDetail?.stages && length>0` 가드 확인 | |
| TC-FE-125 | 마크 목록 칩 선택 하이라이트 | PASS | [정적] MarkingPage.tsx:221-241 정확 일치 | |
| TC-FE-203 | frameIndex 는 서버 fps 로 계산 | PASS | [정적][실동작] `features/marking/markingFps.ts:21-31` 정확 일치(`resolveMarkingFps`), `VideoPlayer.tsx:13,54-55` 정확 일치. `markingFps.test.ts` 5건(폴백30·25fps 끝구간·30fps하드코딩 회귀재현·29.97fps·상한밖거부) 전부 통과. 실측 rawSn=133 `fps=29.97002997002997` 로 소수 fps 도 그대로 전달됨을 실 API로 확인 | |
| TC-FE-204 | 마킹 저장됐으나 배치 미시작 안내 | PASS | [정적] MarkingPage.tsx:63-75 정확 일치 — `batchTriggered===false` 시 error 토스트(성공 문구 아님)+navigate 확인. BE `MarkingResponse.java` 에 `batchTriggered` 필드 실존 확인(grep) | 이 분기 전용 FE 테스트는 없음(테스트 커버 갭) — 소스 로직 자체는 명확 |
| TC-FE-205 | 배속 6단(0.25/0.5/1/1.5/2/4) 이산 선택 | PASS | [정적][실동작] `VideoPlayer.tsx:34`(`SPEED_OPTIONS`)·`:46-49`(changeSpeed)·`:125-140`(렌더) 정확 일치, 기본 `playbackRate=1`(:42), `min-h-11` 확인(:133). `VideoPlayer.test.tsx` `배속_버튼_6개_모두_렌더링됨`·`초기_배속은_1x_활성상태`·`배속_버튼_클릭시_playbackRate_변경` 3건 통과 | UNCERTAINTIES #23 확정대로 자유 입력 없어 클램프 케이스 불성립 — 그렇게 판정 |
| TC-E2E-003 | MarkingPage 통합(스트림/배속/단축키) | PASS | [실동작] `pages/__tests__/MarkingPage.test.tsx` 는 RTL(jsdom) 컴포넌트 테스트이며 `e2e/**` 가 아니라 vitest baseline(1691건 GREEN)에 포함 — 실행·통과 증거 있음(H-part1 TC-E2E-002 와 동일 판단 기준, Playwright 아님) | |

## H-7 결과표 (BatchStageIndicator, 5건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-126 | stages 비면 null 렌더(하위호환) | PASS | [정적][실동작] BatchStageIndicator.tsx:66 정확 일치. 실측 rawSn=129(증강 파생, `derivative:true`)의 `stages=[]` 확인(§ 상수 드리프트 대조 참조). 테스트 `stages가_빈배열이면_아무것도_렌더하지_않는다_배지폴백` 통과 | |
| TC-FE-127 | 7종 단계명 매핑(DEIDENTIFY~INTERPOLATE) | PASS | [정적][실동작] BatchStageIndicator.tsx:16-24 정확 일치, 실측 rawSn=133 응답 7종과 1:1 대응 확인. 테스트 `BE_name과_FE_라벨키가_일치해_status_룩업이_정상_렌더된다` 통과 | |
| TC-FE-128 | 미지 코드 폴백 "처리중"(기술코드 미노출) | PASS | [정적] BatchStageIndicator.tsx:26-28,81 정확 일치 | |
| TC-FE-129 | BE 배열 순서 그대로(FE 순서 가정 없음) | PASS | [정적][실동작] BatchStageIndicator.tsx:12-15(주석),70(map) 정확 일치. FE 코드 전체에 `STAGE_ORDER` 상수 0건(grep) — 재정렬 로직 자체가 없어 드리프트가 구조적으로 불가능. 테스트 `stages가_있으면_BE순서대로_단계라벨을_렌더한다` 통과 | |
| TC-FE-130 | 상태별 아이콘(DONE/PROGRESS/FAIL/PENDING) | PASS | [정적] BatchStageIndicator.tsx:30-57 정확 일치 — 4개 상태 분기 + `aria-hidden` 확인 | |

## H-8 결과표 (ReviewPage, 21건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-131 | 잘못된 검수 ID 에러 | PASS | [정적] 실제 조건문은 ReviewPage.tsx:229-238(카탈로그 232-241) — "잘못된 검수 ID" 문구 확인 | 근거 드리프트 -3줄 |
| TC-FE-132 | 로딩 상태 | PASS | [정적] 실제 블록은 ReviewPage.tsx:240-255(카탈로그 247-256) — Spinner label="검수 로딩" 확인 | 근거 드리프트 |
| TC-FE-133 | 에러/review 없음 | PASS | [정적] 실제 블록은 ReviewPage.tsx:257-266(카탈로그 260-269) — "검수 정보를 불러올 수 없습니다" 확인 | 근거 드리프트 -3줄 |
| TC-FE-134 | 진입 시 자동 startReview | PASS | [정적] ReviewPage.tsx:179-183 정확 일치 — `REVIEW_PENDING && !didStart` 가드 확인. 테스트 `검수_시작_버튼_클릭시_상태_REVIEWING_전이`(진입 자동시작 포함) 통과 | |
| TC-FE-135 | 캔버스 읽기 전용(좌표 마커 미사용) | PASS | [정적][실동작] ReviewPage.tsx:294-308(카탈로그 294-311) "읽기 전용" 배지 확인. 테스트 `검수_화면_캔버스_좌표_마커_컴포넌트_미사용` 통과 | 근거 드리프트(끝 -3줄) |
| TC-FE-136 | 프레임 로딩 중 스피너 | PASS | [정적][실동작] ReviewPage.tsx:306 정확 일치(`loading={framesLoading}`). 테스트 `검수화면_프레임_로딩중_스피너_표시되고_프레임없음_문구_미표시` 통과 | |
| TC-FE-137 | 승인 확인 다이얼로그 | PASS | [정적] ReviewPage.tsx:206-208 정확 일치 | |
| TC-FE-138 | 승인 성공 → /review 이동+토스트 | PASS | [정적][실동작] ReviewPage.tsx:155-160 정확 일치. 테스트 `승인시_상태_COMPLETED_전이` 통과 | |
| TC-FE-139 | 승인 실패 토스트(BE 문구) | PASS | [정적] ReviewPage.tsx:174 정확 일치 — `extractBeMessage(err,'승인 실패')` 확인 | |
| TC-FE-140 | 반려 사유 합성(의견+이슈) | PASS | [정적][실동작] ReviewPage.tsx:65-84(`composeRejectReason`) 정확 일치. 테스트 3건(`composeRejectReason_사용자입력만…`/`전체의견포함`/`이슈_라벨id_없을때_괄호_생략`) 통과 | |
| TC-FE-141 | 반려 성공 → /review 이동 | PASS | [정적][실동작] ReviewPage.tsx:380 정확 일치. 테스트 `ReviewPage_반려시_reason_에_검수의견과_pending_이슈_합쳐_전송` 통과 | |
| TC-FE-142 | 프레임 상태색(미해소 문의=빨강) | PASS | [정적][실동작] ReviewPage.tsx:126-139(`inquirySrcSns`) 정확 일치. 테스트 `ReviewPage가_미해소문의_srcSn집합을_산출해_FrameTimeline에_전달` 통과 | |
| TC-FE-143 | 저장 프레임=연두(labels>0) | PASS | [정적] ReviewPage.tsx:141-149(`savedSrcSns`) 정확 일치 | |
| TC-FE-144 | currentFrameIdx 범위 밖 → 0 reset | PASS | [정적] ReviewPage.tsx:187-192(카탈로그 186-192) 정확 일치 | |
| TC-FE-145 | 언마운트 store reset | PASS | [정적] ReviewPage.tsx:195-200(카탈로그 194-200) 정확 일치 | |
| TC-FE-146 | 증강여부/종류 표시 | **FAIL** | [정적] 카탈로그 근거 파일(`ReviewMetaPanel.tsx`·`ReviewHeader.tsx`) 전문을 확인했으나 증강 관련 필드(`orgnlRawSn`/`augType`/`augmented`/`vmsClipId` 파생 표시)가 **0건**. FE `Review` 타입(`features/review/types.ts:9-22`)에도 해당 필드가 없고, BE `ReviewResponse.java`(record 정의) 전체·실 HTTP 응답(`GET /v1/reviews/132`, `GET /v1/reviews?...`)에도 증강 관련 필드가 전무함을 실측 확인. 상세는 이슈 상세(H-ISSUE-21) | **FAIL** — 이슈 등록 |
| TC-FE-147 | 이슈 스레드 검수자 모드 | PASS | [정적] ReviewPage.tsx:355 정확 일치(`mode="reviewer"`), `IssueThreadPanel.tsx` 에 `mode==='reviewer'` 분기 실존 확인 | |
| TC-FE-206 | 라벨 0건 승인 — 명시 확인 다이얼로그 | PASS | [정적][실동작] ReviewPage.tsx:170-172(errorCode 분기),220-227(handleNoLabelApprove),386-397(ConfirmDialog) 정확 일치. BE `ErrorCode.REVIEW_NO_LABEL`(409) 실존 확인. 테스트 `H6_라벨0건_409는_errorCode_REVIEW_NO_LABEL_로_구분해_확인_다이얼로그를_띄운다` 통과 | |
| TC-FE-207 | 409 라도 사유가 다르면 다이얼로그 미노출 | PASS | [정적][실동작] ReviewPage.tsx:161-175 정확 일치 — `err.errorCode==='REVIEW_NO_LABEL'` 문자열 매칭 아닌 errorCode 분기 확인. 테스트 `H6_동시승인충돌_409는_라벨없음_다이얼로그를_띄우지_않는다` 통과 | |
| TC-FE-208 | 검수 메타 cot 객체형 렌더 크래시 없음 | PASS | [정적] `ReviewMetaPanel.tsx` `CaptionReadonly` 가 `normalizeCot(cand.cot).filter(...)` 사용 확인(구 `(cand.cot??[]).filter` 직접 호출 아님) | ReviewPageMetaPanel.test.tsx 에 cot 객체형 전용 케이스는 미확인(0/4건이 event_annotation 값 렌더·시계열·버튼미노출·빈상태) — 테스트 커버 갭 가능성, 소스 로직은 정상 |
| TC-E2E-004 | 검수 플로우: 대기목록→시작→승인 | **BLOCKED** | `e2e/specs/review-flow.spec.ts` 존재 확인(5,12행). Playwright 스펙이며 `vitest.config.ts:20` 에서 `e2e/**` 제외 — baseline(1691건) 미포함, 이 세션엔 브라우저 자동화 도구 없음 | **사유: 브라우저 자동화 필요.** 근거 체인 자체(startReview/approve 각 단계)는 TC-FE-134/137/138 로 개별 PASS 확인됨 |

## 근거 드리프트

전부 "카탈로그 범위가 함수 선언부/주석/공백 줄을 포함해 실제 조건문·JSX 시작줄과 1~7줄 차이" 수준의 경미한 드리프트다. 함수·로직 자체의 오귀속은 없음.

| TC-ID | 카탈로그 근거 | 실제 위치 | 드리프트 |
|---|---|---|---|
| TC-FE-131 | ReviewPage.tsx:232-241 | ReviewPage.tsx:229-238(`if (Number.isNaN(numericId))` 블록) | -3줄 |
| TC-FE-132 | ReviewPage.tsx:247-256 | ReviewPage.tsx:240-255(`if (isLoading)` 블록) | 시작 -7줄 |
| TC-FE-133 | ReviewPage.tsx:260-269 | ReviewPage.tsx:257-266(`if (error \|\| !review)` 블록) | -3줄 |
| TC-FE-135 | ReviewPage.tsx:294-311 | ReviewPage.tsx:294-308(`<main>` 블록, `</main>`까지) | 끝 -3줄 |

## 브라우저 자동화 필요 케이스 목록

### 사유: Playwright e2e 스펙 미실행 (이 세션에 브라우저 자동화 도구 없음)

- **TC-E2E-004** — `e2e/specs/review-flow.spec.ts`: 실제 브라우저로 검수 대기목록 진입→자동 시작→승인까지의 페이지 전환·네트워크 왕복을 확인해야 함. `vitest.config.ts:20` 이 `e2e/**` 를 명시 제외해 1차·2차 baseline 모두 미실행. 근거 체인 자체(각 단계 로직)는 이미 컴포넌트 테스트로 개별 PASS 확인됨 — 실패 예상 지점은 아니나 실제 브라우저 내비게이션 결과는 미확인.

(H-6의 TC-E2E-003 은 실제로는 Playwright 가 아니라 RTL 컴포넌트 테스트라 이 목록에서 제외 — 위 결과표에서 PASS 처리.)

## 이슈 상세

### [H-ISSUE-21] TC-FE-146 — ReviewPage 에 증강여부/종류(파생영상) 표시 기능이 실제로 존재하지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과 "증강여부/종류 표시" — 검수 화면에서 현재 검수 중인 영상이 증강/해상도 파생 영상인지, 어떤 종류(WINTER/NIGHT/RAIN/RESL_*)인지 검수자가 식별할 수 있어야 한다(근거로 명시된 `ORGNL_RAW_SN`/`VMS_CLIP_ID` 파생 판별 — CLAUDE.md "reviewpage-augmented-list-facts" 메모리 항목과 정합하는 동작).
- **현재 동작(이슈 내용)**:
  1. `frontend/src/features/review/components/ReviewMetaPanel.tsx`(244줄 전체 확인) — event_annotation + 시계열 메타만 읽기 표시. 증강/파생 관련 필드·문구 0건.
  2. `frontend/src/features/review/components/ReviewHeader.tsx`(93줄 전체) — cctvName/workerName/submittedAt/frame counter/StatusBadge 만 렌더. 증강 표시 없음.
  3. `frontend/src/features/review/types.ts:9-22` — `Review` 인터페이스에 `orgnlRawSn`/`augType`/`augmented`/`vmsClipId` 필드 자체가 없음:
     ```ts
     export interface Review {
       id: number; videoId: number; cctvName: string; workerId: number;
       workerName: string; submittedAt: string; labelCount: number;
       status: ReviewStatus; reviewerId?: number;
       eventName?: string | null; eventTypeCd?: string | null;
     }
     ```
  4. BE `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java`(117줄 전체) — record 필드에도 증강 관련 값이 없음(id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd 뿐).
  5. 실 HTTP 확인: `GET /api/v1/reviews/132`, `GET /api/v1/reviews?page=0&size=5` (REVIEWER 토큰) 응답 어디에도 증강 필드 없음(응답 예: `{"id":132,"cctvName":"CCTV-강남구-001",...,"eventTypeCd":"INTRUSION"}`).
  6. 참고로 **작업목록(`GET /v1/tasks/board`)에는 `augmented`/`augType` 필드가 실존**한다(예: `{"videoId":131,...,"augmented":true,"augType":"RESL_480P"}`) — 즉 이 정보 자체는 BE 도메인에 존재하지만 검수(`/v1/reviews*`) 경로로는 전달되지 않는다.
  7. 테스트 커버로도 재확인: `ReviewMetaPanel.test.tsx`(features/review/__tests__/ReviewPageMetaPanel.test.tsx) 4개 케이스(event_annotation 렌더/시계열 렌더/버튼 미노출/빈상태) 중 증강 관련 케이스 0건.
- **재현/확인 경로**: REVIEWER 로 `/review/{id}` 진입(또는 위 curl) — 어떤 영상(증강 파생이든 아니든)이어도 화면·응답에 증강 여부/종류 표시가 없음.
- **영향**: 기능 누락(보안 이슈 아님). 검수자가 증강/해상도 파생 영상을 원본과 구분 없이 검수하게 되어, CLAUDE.md 의 "파생영상도 기존 플로우와 동일하게 검수" 원칙은 지켜지지만 "무엇을 검수 중인지"에 대한 맥락 정보가 빠져 있다. 카탈로그가 이 정보의 화면 노출을 기대값으로 잡았다면 실제 구현과 불일치.
- **수정 방향(제안)**: ⚠ 구현하지 않는다 — `ReviewResponse`(BE)에 `orgnlRawSn`/`augType` 추가 후 `Review`(FE 타입)·`ReviewHeader`/`ReviewMetaPanel`에 배지 형태로 노출하는 방향이 board 목록과의 일관성 면에서 가장 자연스러워 보이나, 카탈로그 기대값 자체를 "현재 미구현"으로 재분류할지 BE/FE 를 보강할지는 정책 결정 필요.

---

## 카탈로그 외 추가 발견 사항 (참고, TC-ID 미부여)

검증 도중 도메인 규칙("마킹 중 비식별 누락 신고, rawSn 기준")과 대조하다 발견한 사항으로, H-6/H-7/H-8 어떤 TC-ID 도 이를 다루지 않아 정식 이슈로 등록하지 않고 참고로만 남긴다.

- BE 는 `POST /v1/videos/{rawSn}/deident-report`(마킹 단계 비식별 신고, `DeidentReportController.java:122`, B-ISSUE-28)를 보유하지만, `frontend/src/pages/MarkingPage.tsx` 및 `features/marking/**` 전체에 이 엔드포인트를 호출하는 코드가 **0건**(grep 확인)이다. FE 가 실제로 호출하는 것은 라벨링 단계용 `POST /v1/labels/{srcSn}/deident-report`(`features/label/api.ts:497,510`)뿐이다. 즉 마킹 화면에는 "비식별 누락 신고" 버튼/동선이 전혀 없다. H-6 카탈로그 20건 중 이를 다루는 TC-ID가 없어 PASS/FAIL 판정 대상은 아니지만, CLAUDE.md 가 명시한 "마킹 단계(rawSn 기준) + 라벨링 단계(srcSn 기준) 양쪽 가능"이라는 서술과 FE 구현 사이의 실측 간극으로 기록해 둔다.

---

# H 클러스터 part3 (H-3 LabelingPage) 2차 검증 결과

> 검증 대상: `docs/test-cases/H-frontend-e2e.md` `## H-3. LabelingPage (라벨링 캔버스)` — TC-FE-033~087(55건) + TC-FE-197~202(신규 6건) = **61건**
> 방법: 소스 실측(Read/Grep, `frontend/src/pages/label/LabelingPage.tsx` 등) + BE 실호출(dev 토큰, 라이브 DB, `localhost:18081` HEAD `ca3c712b`) + 테스트 커버 확인(`_raw/test-baseline.md` 1691건 GREEN 대조, 실행 안 함)
> 폐기(취소선) 행: H-3 섹션 내 0건(전건 검증 대상)
> 브라우저 자동화 도구 없음 — 이 섹션의 61건은 전부 상태/로직 단언(캔버스 픽셀 렌더링 케이스 없음)이라 정적 코드 대조 + 기존 jsdom 컴포넌트 테스트(vitest, baseline 통과 확인) + BE 실호출로 판정 가능했다. **BLOCKED 0건.**

## 집계

| 섹션 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-3 | 61 | 60 | 0 | 1 | 0 | 0 | 0 |

- PARTIAL 1건(TC-FE-056) — SAM2 Track mock-blind 자동적용(C-ISSUE-61)의 FE 종단 확인. 상세는 아래 전용 절 + 이슈 상세.
- self-fill 의심: 0건. 프레임 이미지·라벨·SAM2 track 전부 BE 응답을 그대로 반영(자체 채움 없음).
- 검증 중 BE 상태변경 호출 2건 수행 — ①`PUT /v1/frames/65/labels`(고의로 낡은 `labelVersion=1` 전송, BE 409 확인) ②`POST /v1/labels/68/deident-report`(파생영상 신고 시도, BE 412 확인). **둘 다 BE 가 요청을 거부**해 실제 데이터 변경 없음(사후 `labelVersion` 재조회로 불변 확인). 다른 에이전트가 참조 중인 rawSn=126/129 데이터는 원상 유지됨.

## ★mock 자동적용 차단 실측 (Segment vs Track 비대칭 — C-ISSUE-61 FE 종단 확인)

C-part4.md `C-ISSUE-61`(HIGH)이 지적한 "ai-server SAM2 Track mock 폴백 신호가 BE→FE 4계층에서 소멸"을 화면(LabelingPage) 관점에서 이어서 확인했다.

| 경로 | Detect/Segment(일반) | Track(추적) |
|---|---|---|
| FE 처리 코드 | `LabelingPage.tsx:713-716` — `if (res.message) { pushToast(warning); return; }`(mergeAutoLabels 미호출, 자동적용 차단) | `LabelingPage.tsx:634-677 handleTracked` — **mock/message 분기 없음**. `forCurrent`/`forFuture` 를 무조건 `mergeAutoLabels`/`stashPendingTracks` 하고, `partial===false` 면 항상 `"AI 추적 완료 (N프레임)"` **성공** 토스트(`:664-666`) |
| 대응 테스트 | `LabelingPageAutolabelToast.test.tsx:98 message있으면_mock경고_토스트로_자동적용_차단` — 존재 | **grep 0건** — `handleTracked`/`onTracked`/"mock"+"추적" 조합을 검증하는 테스트가 프로젝트 전체에 없음(`grep -rln "handleTracked" **/*.test.tsx` → 0) |
| 근거 | `runAiTool` 의 `res.message` 분기가 BE `ApiResponse.message`(모델 미로드 시 세팅)를 신뢰해 게이팅 | `useSam2Track`(`hooks/useSam2Track.ts`)의 `Sam2TrackedItem`/`Sam2TrackResponse` 타입에 `mock`/`score` 신뢰도 필드 자체가 없음(C-part4 실측: BE DTO `Sam2TrackResponse.java`가 3필드 미선언 → Jackson 이 ai-server 의 `mock`/`source`/`mock_reason` 을 조용히 버림) — FE 는애초에 판단할 재료를 받지 못한다 |

**결론**: C-ISSUE-61 의 배선 누락(BE DTO 필드 미선언)이 FE 까지 그대로 전파되어, 라벨링 화면에서 **가짜(mock fallback) 추적 결과가 실추론과 동일하게 병합되고 "AI 추적 완료" 성공 토스트가 뜬다.** 사용자는 신뢰할 수 없는 결과를 신뢰할 수 있는 결과로 오인한다. → `H-ISSUE-41`(TC-FE-056 PARTIAL 로 반영, 근본 수정은 BE `Sam2TrackResponse` DTO 필드 추가가 선행돼야 하므로 C-ISSUE-61 과 동일 근본원인으로 묶어 기록).

## H-3 결과표 (61건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-FE-033 | 잘못된 ID(NaN) 다크 에러 | PASS | [정적] `LabelingPage.tsx:926-944` `Number.isNaN(numericId)` → "잘못된 프레임 ID" + 뒤로가기 버튼 | 근거 드리프트(카탈로그 911-930 → 실제 926-944, +15) |
| TC-FE-034 | 로딩 상태 스피너 | PASS | [정적] `:947-960` `isLoading` → `Spinner label="라벨 로딩"` + "라벨 로딩 중..." | 드리프트(932-945→947-960) |
| TC-FE-035 | 포털 403 → graceful 차단화면 | PASS | [정적] `:965-992` `isPortalForbidden`(status===403 \|\| errorCode==='FORBIDDEN') → "접근할 수 없는 영상입니다" | 드리프트(950-977→965-992) |
| TC-FE-036 | 일반 에러 → "라벨 조회 실패" | PASS | [정적] `:994-1014` 일반 error 분기 → "라벨 조회 실패" + `{error.message}`(=BE message, `extractBeMessage` 아닌 원본 노출이나 GlobalExceptionHandler 가 내부정보 미노출 보장) | 드리프트(979-999→994-1014) |
| TC-FE-037 | siblings 비면 현재 프레임 단건 폴백 | PASS | [정적] `:220-247` `siblings.length===0` → 현재 프레임 1건 배열 반환 | 드리프트(202-219→220-247) |
| TC-FE-038 | 저장 성공 토스트 "저장됨" | PASS | [정적] `:507-541 handleSave` → `updateLabels`+`clearDirty`+`pushToast('저장됨')`(:520-525). [실동작] `PUT /v1/frames/65/labels` 로 BE 계약(`items`+`labelVersion`) 실측(아래 TC-FE-197 절 참조) | 드리프트(492-510→507-541) |
| TC-FE-039 | 저장 중복 제출 차단 | PASS | [정적] `:511` `if (saving) return;`(useMutation isPending 가드) | 드리프트(496→511) |
| TC-FE-040 | 잠금 영상 저장 차단 | PASS | [정적] `:512-518` `isLocked` → 에러 토스트, `updateLabels` 미호출(return) | 드리프트(497-503→512-518) |
| TC-FE-041 | 저장 실패 에러 토스트(BE 문구 우선) | PASS | [정적] `:536-539` `extractBeMessage(e,'저장 실패')`. `lib/api/extractBeMessage.ts` 확인 — `userMessage`→`message`→axios raw→fallback 순 | 드리프트(521-525→536-539) |
| TC-FE-042 | 포털 모드 저장 경로 분기 | PASS | [정적] `:493-506` `updateLabels = portalMode ? savePortalLabels : updateInternalLabels` | 드리프트(486-491→493-506) |
| TC-FE-043 | 프레임 이동 dirty 가드 모달 | PASS | [정적] `:307-317 requestJumpTo` → `dirtyCount>0` 시 `setNavGuardTarget(idx)`(FrameNavGuardModal 트리거) | 드리프트(292-302→307-317) |
| TC-FE-044 | 같은 프레임 이동 no-op | PASS | [정적] `:311` `if (target.srcSn === data.srcSn) return;` | 드리프트(296→311) |
| TC-FE-045 | 저장 후 이동 — 실패 시 취소 | PASS | [정적] `:319-342 handleNavSaveAndMove` — catch 시 에러 토스트 + `setNavGuardTarget(null)`(이동 미실행, 현재 프레임 유지) | 드리프트(304-327→319-342) |
| TC-FE-046 | 저장 안 함 이동 — dirty 폐기 | PASS | [정적] `:344-350 handleNavDiscardAndMove` — `clearDirty()` 후 `performJump` | 드리프트(329-336→344-350) |
| TC-FE-047 | 프레임 전환 시 setLabels 전체 교체 | PASS | [정적] `:364-377` `frameChanged` 시 `setLabels(nextLabels)` | 드리프트(348-362→364-377) |
| TC-FE-048 | 같은 프레임 refetch는 dirty 있으면 미덮음 | PASS | [정적] `:373-376` `useLabelStore.getState().dirtyLabels.size===0` 일 때만 `setLabels` | 드리프트(355-361→373-376) |
| TC-FE-049 | 보류 추적 drain 병합 | PASS | [정적] `:383-396` `drainPendingTracks`+`mergeAutoLabels`+`pushToast('보류된 AI 추적 N건 적용됨')` | 드리프트(364-381→383-396) |
| TC-FE-050 | 언마운트 시 store reset | PASS | [정적] `:399-403` `useEffect(() => () => reset(), [reset])` | 드리프트(384-388→399-403) |
| TC-FE-051 | AI 탐지 팝업 매핑 라벨만 선택 | PASS | [정적] `AiToolModal.tsx:148(mappedCandidates),184(canRun),270("미매핑")` — 근거 그대로 일치(드리프트 없음). [테스트] `AiToolModal.test.tsx:33 미매핑_라벨은_표시되지만_선택_불가`, `:70 매핑된_라벨이_없으면_일반_실행이_비활성이다` | |
| TC-FE-052 | AI 탐지 mock 응답 자동적용 차단 | PASS | [정적] `LabelingPage.tsx:712-716` `if (res.message) { pushToast(warning); return; }`(mergeAutoLabels 미호출). [테스트] `LabelingPageAutolabelToast.test.tsx:98 message있으면_mock경고_토스트로_자동적용_차단` | 드리프트(698-701→712-716) |
| TC-FE-053 | AI 탐지 결과 작업본 병합(중복 스킵) | PASS | [정적] `:717-723` `mergeAutoLabels(detected)` + `"${kind} ${added}건 적용됨"`. [테스트] `LabelingPageAutolabelMerge.test.tsx:120 오토라벨_직후_PUT저장이_호출되지_않는다_그리고_기존라벨_유지한채_병합` | 드리프트(706-708→717-723) |
| TC-FE-054 | 폴리곤 shape → "AI 분할" 라벨 | PASS | [정적] `:722` `shape === 'POLYGON' ? 'AI 분할' : 'AI 탐지'` | 드리프트(707→722) |
| TC-FE-055 | 트랙 모드 — 객체 선택 유도 | PASS | [정적] `:688-706` `mode==='track'` → `setActiveTool(TRACK)`+`setTrackShape`+안내 토스트("추적할 객체를 선택한 뒤...") | 드리프트(673-690→688-706) |
| TC-FE-056 | 추적 결과 현재/미래 프레임 분리 | PARTIAL | [정적] `:634-677 handleTracked` — 기대결과(현재=즉시병합, 미래=stash) 자체는 정확히 일치. 단 **mock/신뢰도 분기가 전혀 없이 무조건 병합 + 성공 토스트**(`C-ISSUE-61` FE 종단 확인, 위 전용 절 참조) | 카탈로그 기대결과는 충족하나 도메인 안전성 결함을 노출하는 케이스라 PARTIAL로 하향. `H-ISSUE-41` 참조. 드리프트(619-660→634-677) |
| TC-FE-057 | 부분 추적 실패 경고 | PASS | [정적] `:658-663` `partial` 시 `"${applied}/${total} 프레임만 추적됨 (일부 실패)"` | 드리프트(643-648→658-663) |
| TC-FE-058 | nextSrcSns 계산(현재 이후) | PASS | [정적] `:624-627` `frames.slice(frameIdx + 1).map(f => f.srcSn)` | 드리프트(608-612→624-627) |
| TC-FE-059 | SAM2 추적 청크 50개 상한 정합 | PASS | [정적] `api.ts:612 SAM2_TRACK_CHUNK_SIZE=50`, `:698-701` 청크 분할. [테스트] `sam2-track.test.tsx:96 상수는_BE_Size_상한과_정합한다`, `:100 120개_후속프레임은_50/50/20/3청크로_분할되고_폴리곤이_체인된다`, `:179 정확히_50개_후속프레임은_단일_청크` | BE `@Size(max=50)` 는 C-part4/C-part5 에서 실호출로 확정(51→400/50→200) |
| TC-FE-060 | SAM2 추적 stale 가드(프레임 전환) | PASS | [정적] `useSam2Track.ts:59` `if (context?.requestedSrcSn !== currentSrcSnRef.current) return;` | |
| TC-FE-061 | SAM2 부분 실패 성공분만 병합 | PASS | [정적] `useSam2Track.ts:62-69` `Sam2TrackChunkError` catch → `!stale && err.partial.length>0` 이면 `onTracked(err.partial, true)` | |
| TC-FE-062 | BBOX 추적 시드 외접박스 4점 확장 | PASS | [정적] `api.ts:620-639 toSeedPolygon` — 2점→4점 폐곡선(min=3 검증 대응). [테스트] `sam2-track.test.tsx:427 BBOX_추적_50프레임초과시_2번째청크_prevPolygon이_4점폐곡선으로_확장된다` | |
| TC-FE-063 | 트랙 rename/삭제/분할 포털 차단 | PASS | [정적] `LabelingPage.tsx:738,759,783` 3개 핸들러 선두 `if (portalMode) return;` | 드리프트(721,745,769→738,759,783, +17) |
| TC-FE-064 | 잠금 영상 트랙 편집 차단 | PASS | [정적] `:741,762,786` 각 핸들러 `isLocked` 체크 → 에러 토스트 + return | 드리프트(726,747,771→741,762,786, +15) |
| TC-FE-065 | 트랙 rename 성공 후 invalidate | PASS | [정적] `:745-748` `mergeTracks` 성공 시 `LABEL_KEYS.byVideo(rawSn)` invalidate + 성공 토스트 | 드리프트(731-737→745-748) |
| TC-FE-066 | 비식별 신고 성공 → 잠금+reset+무효화 | PASS | [정적] `:480-488 handleDeidentReportSuccess` — `setReportedLock(true)`+`reset()`+`invalidateQueries(LABEL_KEYS.byVideo(srcSn))`. [실동작] `POST /v1/labels/68/deident-report`(파생영상, 아래 TC-FE-200/201 절)로 신고→412 게이트 경로 확인. 재조회 시 `GET /v1/frames/{srcSn}/labels` 는 신고 게이트로 412 가 되어 `:994-1014` 일반 에러 분기("라벨 조회 실패"+BE 안내문)로 렌더 — 카탈로그가 명시한 "빈 라벨 화면 아님" 요건 충족 | ⚠ 문서 드리프트(비이슈): 컴포넌트 주석(`:472-474`)이 "BE 는 신고 접수 시 라벨을 전체 삭제하고 잠근다"고 서술하나 CLAUDE.md 최신 정책(2026-07-27)은 "라벨 보존, 조회만 412 차단"이다. 실제 동작(reset+invalidate→412 재조회)은 최신 정책과 정합하므로 기능 결함 아님, 주석만 낡음 |
| TC-FE-067 | 잠금 배너 노출 | PASS | [정적] `:1094-1104` `isLocked` → `role="status"` 배너 "비식별 재처리 중인 영상입니다..." | 드리프트(1079-1090→1094-1104) |
| TC-FE-068 | 비식별 신고 버튼 — RAW 프레임 disabled | PASS | [정적] `:1050` `disabled={isLocked \|\| data.frameImageType === 'RAW'}` | 드리프트(1031-1040→1046-1055) |
| TC-FE-069 | 비식별 신고 버튼 포털 미노출 | PASS | [정적] `:115` `canReportDeident = !portalMode && (isWorker \|\| isReviewer)`(라인 정확 일치), `:1046-1054` `canReportDeident && ... ? <DeidentReportButton/> : null` | 115는 드리프트 없음, 1031-1032→1046-1054 |
| TC-FE-070 | 검수제출 버튼 WORKER만 | PASS | [정적] `:1056-1091` `isWorker && data ? (...) : null` | 드리프트(1041-1042→1056-1091) |
| TC-FE-071 | 상태별 제출 차단(REVIEW_PENDING/REVIEWING) | PASS | [정적] `:167-199` `SUBMITTABLE_STATUSES`+`submitBlockedByStatus`, 버튼 `disabled={... \|\| submitBlockedByStatus}`(:1075) + `title={submitStatusHint}` | 드리프트(151-176→167-199) |
| TC-FE-072 | APPROVED 재검수 라벨 | PASS | [정적] `:188` `isResubmitOfApproved = workStatus === 'COMPLETED'` → `:189` "재검수 제출" | |
| TC-FE-073 | 제출 취소 버튼 REVIEW_PENDING만 | PASS | [정적] `:186` `canCancelSubmit = isWorker && workStatus === 'REVIEW_PENDING'`, `:1059-1071` 조건부 렌더 | 드리프트(171,1044→186,1059) |
| TC-FE-074 | 검수제출 성공 → /task 이동+토스트 | PASS | [정적] `:154-160` `onSuccess: () => { pushToast('검수 제출 완료'); navigate('/task'); }` | 드리프트(139-145→154-160) |
| TC-FE-075 | X 닫기 dirty 시 3옵션 모달 | PASS | [정적] `:809-815 handleClose` → `dirtyCount>0` 시 `setCloseConfirmOpen(true)`, 모달 3버튼(`:1109-1143`: 취소/저장 없이 닫기/저장 후 닫기) | 드리프트(792-800→809-815) |
| TC-FE-076 | beforeunload dirty 경고 | PASS | [정적] `:846-855` `dirtyCount>0` 이면 `beforeunload` 리스너 등록, `e.preventDefault()`+`returnValue=''` | 드리프트(831-839→846-855) |
| TC-FE-077 | 우측 탭 — 메타/이슈 내부 채널만 | PASS | [정적] `:428` `showMeta = !portalMode`, `:426` `showIssues = !portalMode && issueRawSn!==undefined`, 탭 렌더 `:1232-1301` 조건부 | 드리프트(406→428, 1205-1270→1232-1301) |
| TC-FE-078 | 이슈 탭 미해소 배지 카운트 | PASS | [정적] `:1289-1297` `unresolvedInquiries>0` → `data-testid="issue-tab-badge"` + `aria-label="미해소 문의 N건"` | 드리프트(1260-1266→1289-1297) |
| TC-FE-079 | 메타 탭 — 촬영환경/개인정보/설명/VLM/이벤트 패널 | PASS | [정적] `:1321-1330` `EnvironmentMetaPanel`+`FramePrivacyMetaPanel`+`FrameDescriptionPanel`+`TimeseriesSidePanel`+`EventAnnotationPanel` 5개 확인 | 드리프트(1290-1310→1313-1331) |
| TC-FE-080 | 뷰(zoom/pan) 유지 vs 리셋 | PASS | [정적] `:277-289 handleImageSize` → `shouldResetView(viewKeyRef.current, next)` false 면 `resetView()` 미호출(유지) | 드리프트(262-274→277-289) |
| TC-FE-081 | 붙여넣기 실측 dims clamp | PASS | [정적] `:890-908 onPasteLabels` → `imageWidth: frameNaturalSize?.width, imageHeight: frameNaturalSize?.height` | 드리프트(875-890→890-908) |
| TC-FE-082 | 복사 — 빈 선택 no-op 토스트 | PASS | [정적] `:881-888` `n===0` → `"복사할 라벨이 없습니다."` | 드리프트(866-872→881-888) |
| TC-FE-083 | 잠금 영상 붙여넣기 차단 | PASS | [정적] `:890-894` `isLocked` → 에러 토스트 + return(paste 미실행) | 드리프트(876-879→890-894) |
| TC-FE-084 | 저장 되돌리기 확인 모달 | PASS | [정적] `:556-558 handleRevertRequest` → `setRevertTarget(item)`, `ConfirmDialog`(`:1426-1434`) `open={revertTarget!==null}` | 드리프트(541-545→556-558) |
| TC-FE-085 | 되돌릴 항목 없음 경고 | PASS | [정적] `:561-581 confirmRevert` → `reverted===0` 시 `pushToast('되돌릴 항목이 현재 작업본에 없습니다.')` | 문구가 카탈로그 표기("되돌릴 항목이 없습니다")와 정확 문자열은 다르나 의미 동일(카탈로그 축약 표기로 판단, 결함 아님). 드리프트(546-566→561-581) |
| TC-FE-086 | 캔버스 lazy 마운트(konva 분리) | PASS | [정적] `:74-76` `lazy(() => import('.../CanvasShell')...)`(드리프트 없음), `:1190-1211` `<Suspense fallback={<Spinner label="캔버스 로딩"/>}>` | 1175-1196→1190-1211 |
| TC-FE-087 | 히스토리 인라인 패널 내부만 | PASS | [정적] `:1398-1410` `historyOpen && !portalMode && data?.srcSn!==undefined` 조건부 렌더 | 드리프트(1369-1381→1398-1410) |
| TC-FE-197 | 저장 409 → 충돌 다이얼로그(작업 보존) (신규) | PASS | [정적] `:530-535` `e instanceof ApiError && e.status===409` → `setSaveConflictMessage(extractBeMessage(...))`(dirty 미소거), `ConfirmDialog`(`:1414-1423`) confirm=`handleReloadAfterConflict`(clearDirty+refetch), cancel=`setSaveConflictMessage(null)`(dirty 유지). [실동작] `srcSn=65`(rawSn=126) 현재 `labelVersion=2` 확인 → `PUT /v1/frames/65/labels`에 고의로 `labelVersion=1` 전송 → **실측 409** `{"errorCode":"CONFLICT","message":"다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요."}`(BE 메시지가 FE fallback 문구와 문자열까지 일치). 재조회로 `labelVersion` 여전히 2(데이터 불변) 확인 | 신규 기능, BE-FE 계약 실측 완료 |
| TC-FE-198 | 저장 요청에 labelVersion 동봉 (신규) | PASS | [정적] `api.ts:296-307 putLabels` — `labelVersion != null` 일 때만 필드 포함(생략 시 하위호환 skip). [실동작] 위 TC-FE-197 실호출로 `labelVersion` 필드가 실제 전송·BE 인식됨을 확인 | |
| TC-FE-199 | 연속 저장 시 캐시 버전 우선(자기 409 방지) (신규) | PASS | [정적] `useUpdateLabels.ts:50-70` — `mutationFn` 이 렌더 클로저 `options.labelVersion` 대신 `qc.getQueryData(...).labelVersion` 우선 사용, `onSuccess` 가 `setQueryData` 로 즉시 캐시 갱신(동기). [테스트] `useUpdateLabels.test.tsx:98 H12_같은_사용자의_연속_저장이_자기자신과_409가_나지_않는다 — 새_labelVersion_즉시반영` | |
| TC-FE-200 | 파생영상 — 비식별 신고 버튼 사전 비활성 (신규) | PASS | [정적] `LabelingPage.tsx:127-132` `videoDetail?.derivative` → `deidentReportUnsupportedReason` 설정(원본 유도 없음, 부모 rawSn 미표시), `:1046-1054` `unsupportedReason` prop 전달. `DeidentReportButton.tsx:136-140` `disabled={... \|\| Boolean(unsupportedReason)}` + `title={unsupportedReason}`. [실동작] `GET /v1/videos/129` → `"derivative": true` 확인(WINTER 증강 파생, 부모 rawSn=126). [테스트] `DeidentReportButton.test.tsx:173 파생영상이면_버튼이_비활성화되고_사유가_툴팁으로_보인다` | |
| TC-FE-201 | 신고 412 — 서버 안내문 그대로 노출 (신규) | PASS | [정적] `DeidentReportButton.tsx:107-116` `status===412` → `setServerError(resolveApiMessage(e, fallback))`(BE 안내문 그대로). [실동작] `srcSn=68`(rawSn=129, derivative) 로 `POST /v1/labels/68/deident-report` 실제 호출 → **실측 412** `{"errorCode":"PRECONDITION_FAILED","message":"이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다."}` — 부모 rawSn 미노출 확인. [테스트] `DeidentReportButton.test.tsx:185 412_응답시_서버_안내문이_그대로_노출된다` (테스트의 mock 메시지가 실측 BE 메시지와 동일) | |
| TC-FE-202 | 이벤트 어노테이션 cot 객체형 정규화 (신규) | PASS | [정적] `api/eventAnnotation.ts:26-29 normalizeCot` — 배열/객체 양형→배열, `Object.values()`로 키 순서 유지. `components/eventAnnotationForm.ts:59-68 toCaptionRows` — `COT_STEPS`(3) 고정 배열로 정규화. [테스트] `eventAnnotationForm.test.ts:15 객체형_cot_값을_키순서대로_배열화`, `:27 객체형_cot_도_3단계_폼행으로_정규화` | |

## 근거 드리프트

전 항목이 아래 **체계적 오프셋**으로 설명된다(개별 결함 아님) — `LabelingPage.tsx` 상단부에 파생영상 비식별 신고 판정 블록(`useVideoDetail`+`deidentReportUnsupportedReason`, 2026-07-30 회차 추가, 약 10줄)과 우측 패널 렌더 영역의 이슈 탭·배지 코드가 추가되며 파일 전체가 누적 밀렸다.

| 구간(카탈로그 기준 라인) | 오프셋 | 대상 케이스 |
|---|---:|---|
| 상단부(0~700줄대) | +14~15줄 | TC-FE-033~065 대부분 |
| 우측 패널(1200줄대 이후) | +21~31줄 | TC-FE-077~079, 087 |

카탈로그가 인용한 함수/조건문·문자열 자체는 **전건 실재 확인**됐다(내용 드리프트 0건, 라인 번호만 밀림). 다음 카탈로그 갱신 시 `frontend/src` 회차 diff 재확인 필요.

## 브라우저 자동화 필요 케이스 목록 (BLOCKED 사유별)

없음. H-3 61건 전부가 상태·로직·API 계약 단언(loading/error 분기, dirty 가드, mock 차단, 409/412 계약, 좌표 clamp 등)이라 정적 코드 대조 + 기존 vitest(jsdom) 컴포넌트 테스트(전건 baseline GREEN) + BE 실호출로 판정 가능했다. UNCERTAINTIES #22(캔버스 드로잉 픽셀 정확도, 미해소)에 해당하는 케이스(실제 마우스 드래그·픽셀 좌표 검증)는 H-3 61건 안에 없다(캔버스 lazy 마운트 여부만 다루는 TC-FE-086은 Suspense 렌더 확인이라 해당 없음).

## 이슈 상세

### [H-ISSUE-41] TC-FE-056 — SAM2 Track mock 폴백 결과가 라벨링 화면에서 신뢰 가능한 결과와 동일하게 자동 병합·성공 토스트됨

- **심각도**: HIGH
- **기대 동작(기대효과)**: SAM2 분할(Segment)과 동일하게, ai-server 가 mock 폴백(실모델 추론 실패)으로 응답한 추적 결과는 화면에 **자동 적용되지 않고** 경고로 표시돼야 한다(CLAUDE.md SFR-08-01: "SAM2 분할(클릭/박스→폴리곤+신뢰도, mock 응답은 FE 자동적용 차단)"). Track 도 동일 계약 대상이다(C-ISSUE-61 근거).
- **현재 동작(이슈 내용)**: `frontend/src/pages/label/LabelingPage.tsx:634-677 handleTracked` 가 `tracked` 결과를 mock 여부·신뢰도와 무관하게 무조건 `mergeAutoLabels`/`stashPendingTracks` 하고, `partial===false` 이면 항상 성공 토스트를 띄운다.
  ```tsx
  const handleTracked = useCallback(
    (tracked: Sam2TrackedItem[], partial: boolean) => {
      ...
      let applied = 0;
      if (forCurrent.length > 0) {
        applied += mergeAutoLabels(
          forCurrent.map((t) => trackedItemToLabel(t, currentFrame.frameNo)),
        );
      }
      ...
      if (partial) {
        pushToast({ variant: 'warning', message: `${applied}/${total} 프레임만 추적됨 (일부 실패)` });
      } else {
        pushToast({ variant: 'success', message: `AI 추적 완료 (${applied}프레임)` }); // mock 여부 무관
      }
    }, [...]);
  ```
  근본 원인은 BE 계약 단절이다 — `Sam2TrackResponse.java`(BE→FE DTO)가 ai-server 가 실제로 보내는 `mock`/`source`/`mock_reason` 필드를 선언하지 않아 Jackson 이 조용히 버린다(C-part4.md `C-ISSUE-61` 실측: `prevPolygon` 역전 좌표로 mock 폴백을 유도했을 때 BE 가 **200** + `message:null` 로 실추론과 구분 불가능한 응답을 내려줌). FE `useSam2Track`/`handleTracked` 는 애초에 판단 재료를 받지 못하므로 분기를 만들 수 없는 상태다.
- **재현/확인 경로**: `POST /v1/frames/{srcSn}/sam2-track` 요청에 실모델이 mask 를 못 찾는 입력(예: 극단 좌표)을 넣으면 ai-server 가 `[SAM2] track real returned no mask — prev polygon fallback` 경고 로그와 함께 입력 폴리곤을 그대로 반사한 가짜 추적 결과를 반환(C-part4 실측). 이 응답이 라벨링 화면의 `handleTracked` 를 그대로 통과해 병합 + "AI 추적 완료" 토스트로 이어진다. 회귀 방지 테스트도 0건(`grep -rln "handleTracked" **/*.test.tsx` → 0건, `LabelingPageAutolabelToast.test.tsx` 는 Detect 경로만 커버).
- **영향**: 사용자가 신뢰할 수 없는 자동 추적 결과(가짜 폴리곤)를 실제 검출로 오인해 검수 없이 그대로 저장할 위험. 데이터 품질 저하(오검출 라벨 유입) — CWE 분류 대상은 아니나 SFR-08-01 요구사항(VOS 신뢰도 게이팅) 미충족.
- **수정 방향(제안)**: (구현하지 않음) ①BE `Sam2TrackResponse`/`Sam2TrackResponseDto.TrackedItem` 에 `mock`/`source`/`score` 필드를 Segment 와 동일하게 선언·전파 ②`handleTracked` 에 Segment 의 `res.message` 분기와 동일한 mock 경고 분기 추가(자동 병합 차단 + 경고 토스트) ③회귀 테스트 신설(`handleTracked` mock 케이스).

---

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

---

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
