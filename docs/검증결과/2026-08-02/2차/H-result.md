# H 클러스터 part1 — H-1 인증/라우팅 가드(26) + H-14 보안(XSS/토큰/용어정책)(7)

> 회차: 2026-08-02 2차 · 대상 33건 · 담당 범위: `docs/test-cases/H-frontend-e2e.md` §H-1 · §H-14
> 스택: FE `http://localhost:13000`(klid-frontend, vite dev) · BE `http://localhost:18081/api`(context-path `/api`) · PostgreSQL/mock-server/ai-server 기동중
> 검증 방식: Playwright MCP 실브라우저 조작(라우팅 가드·토큰 인계·스토리지·네트워크 헤더) + `file:line` 정적 대조 + 기존 FE 테스트 인벤토리 대조
> 기준선: `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md` — frontend vitest **1,951 tests / 실패 0**(332 파일). ⚠ **E2E(Playwright `e2e/specs/*.spec.ts` 11파일)는 baseline 에서 실행되지 않았다** → TC-E2E-* 는 "스펙 존재·내용 정합"까지만 판정.

## 검증 환경 실측 (판정 신뢰도 근거)

| 항목 | 실측 |
|---|---|
| FE 서빙 소스 == 워킹트리 | **일치 확인**. `curl http://localhost:13000/src/{router/guards.tsx,stores/useAuthStore.ts,features/auth/tokenIngress.ts,lib/api/client.ts,lib/api/errors.ts}` 에서 `role-claim`·`klid_jwt`·`LOCAL_STORAGE_TOKEN_KEY`·`isAlgAcceptable`·`_retriedWithToken`·`PRECONDITION_FAILED` 심볼 모두 존재 → **F 클러스터에서 보고된 "배포 jar 가 워킹트리보다 오래됨"(F-ISSUE-27) 문제는 FE 에는 해당 없음** |
| FE 컨테이너 실효 env | `docker inspect klid-frontend` → `VITE_API_BASE_URL=/api/v1`, **`VITE_TOKEN_INGRESS=all`** (H-ISSUE-01 근거) |
| FE 응답 보안 헤더 | `curl -I http://localhost:13000/` → `X-Content-Type-Options: nosniff` · `X-Frame-Options: DENY` · CSP(`object-src 'none'; frame-ancestors 'none'; base-uri 'self'`) 부여 확인 |
| BE 무인증 응답 | `GET /api/v1/videos` → `{"success":false,...,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` (스택·내부경로 없음) |
| ⚠ 브라우저 공유 간섭 | Playwright MCP 브라우저를 **다른 검증 에이전트가 동시에 구동**해 탭이 계속 생성·전환됐다. 이 때문에 `navigate → evaluate` 2단계 관측은 다른 탭 결과가 섞였다. 그래서 **판정 근거는 ①`browser_tabs new`(신규 탭 = 빈 sessionStorage) 단일 호출의 응답 URL, ②그 직후 1회 evaluate 로 자기 일관성이 확인된 것**만 `[실동작]` 으로 채택했다. 자기 일관성이 깨진 관측은 폐기하고 `[정적]` 으로 낮췄다. |

---

## 1. 판정 표 — H-1. 인증/라우팅 가드 (26건)

| ID | 판정 | 근거 확인 | 실측 내용 / 근거 |
|----|:--:|---|---|
| TC-FE-001 | PASS | [정적] | `router/guards.tsx:34-40` — `!isHydrated` 시 `<Spinner label="인증 확인 중" />` 반환(Navigate 없음). 근거 라인 정확. 테스트: `router/__tests__/deepLinkHydrationGuard.test.tsx:47` `hydration_미완료_상태에서_딥링크_진입시_ingress로_튕기지_않음` |
| TC-FE-002 | PASS | [실동작] | 빈 스토리지 신규 탭 → `GET http://localhost:13000/dashboard` → 최종 URL `/dev/login`. 경로: `RoleGuard`(claims=null) → `<Navigate to="/ingress">` → `SessionIngressPage` 토큰 없음 → `handleAuthFailure` → dev 빌드라 `/dev/login`. `guards.tsx:41-43` 정확. 테스트 `RoleGuard.test.tsx:66` |
| TC-FE-003 | PASS | [정적] | `guards.tsx:27-32`(useEffect: `clear()`+`redirectToUpstream(claims.channel)`) + `44-51`(스피너로 빈 화면 방지). 근거 라인 정확. 테스트 `RoleGuard.test.tsx:71` `만료된_exp는_상위_시스템_redirect`. ⚠ 실동작에서는 **`hydrate()` 가 만료 토큰을 먼저 제거**(TC-FE-015)해 이 분기는 "세션 중 만료" 에만 도달한다. **redirect 실효성은 H-ISSUE-02 참조(운영 빌드에서 upstream URL 미주입 → `redirectToUpstream` 이 항상 false)** |
| TC-FE-004 | PASS | [실동작] | 빈 스토리지 탭 → `/ingress?token={sub,channel:INTERNAL,exp+3600, role 없음}` → 최종 URL **`/role-claim`**. `guards.tsx:52-55` 정확 |
| TC-FE-005 | PASS | [실동작] | WORKER(BE 발급 `sub=2001` 정품 토큰) 세션에서 직접 URL 진입 3종 모두 **`/forbidden`**: `/manage/users`·`/review`·`/augment`. `guards.tsx:57-58` 정확. 테스트 `RoleGuard.test.tsx:57` |
| TC-FE-006 | PASS | [실동작] | REVIEWER(BE 발급 `sub=1001`) → `/manage/settings` 진입 성공(최종 URL 유지, 화면 렌더). `guards.tsx:60` |
| TC-FE-007 | PASS | [실동작] | INTERNAL(WORKER) 세션에서 `/portal` 직접 진입 → **`/forbidden`**. `guards.tsx:88-89` 정확. 테스트 `ChannelGuard.test.tsx:47` |
| TC-FE-008 | PASS | [실동작] | ① role 미부여 토큰으로 진입 시 `/role-claim` 이 **렌더**되고 무한 redirect 없음(TC-FE-004 와 동일 관측) ② role 보유(WORKER) 사용자도 `/role-claim` 진입 시 그대로 렌더. `guards.tsx:114-144`(카탈로그 `104-143` 은 JSDoc 시작 기준 — 실제 함수 본문 114-144, **경미한 근거 드리프트**) |
| TC-FE-009 | PASS | [정적] | `guards.tsx:94-97` `if (typeof exp !== 'number' \|\| exp <= 0) return false;`. 근거 정확. ⚠ **전용 자동 테스트 0건**(H-ISSUE-05 커버리지 갭에 합산). 실동작으로는 도달 불가 — `decodeJwtPayload` 가 `exp=0` 토큰을 앞서 무효화(TC-FE-012 실측)하므로 `exp=0` 인 claims 자체가 생성되지 않는다 |
| TC-FE-010 | PASS | [실동작] | 빈 스토리지 탭 → `/ingress?token={role:'ADMIN'}` → **`/dev/login`**(auth failure) + `sessionStorage.klid_jwt === null`(미저장). `stores/useAuthStore.ts:47-48` `hasRole && !isRole(raw.role) → return null` 정확 |
| TC-FE-011 | PASS | [실동작] | `/ingress?token={role:''}` → **`/role-claim`** + `sessionStorage.klid_jwt` **저장됨**. 즉 claims 유효 + role=null. `useAuthStore.ts:47-49` 정확 |
| TC-FE-012 | PASS | [실동작] | 3종 모두 빈 스토리지 신규 탭에서 **`/dev/login`**(미저장): ①`channel` 누락 ②`sub` 누락 ③`exp:0`. `useAuthStore.ts:54` `if (!sub \|\| !channel \|\| !exp) return null;` 정확. **⚠ 반증 확인**: URL 토큰이 형식(3-part·base64url·alg≠none)만 통과하면 `resolveToken` 이 그것을 반환하고 localStorage 후보로 **폴백하지 않는다** → 무효 URL 토큰이 유효 localStorage 토큰을 가리는 일은 없으나, "이미 유효 세션이 있는 탭"에서는 `setToken` 이 no-op 이라 **기존 세션이 유지된다**(`SessionIngressPage.tsx:73-76` 이 `getState().claims` 를 재조회) — 권한 상승이 아니라 fail-safe 이므로 결함 아님 |
| TC-FE-013 | PASS | [실동작] | REVIEWER 토큰(`name:"김검수"`) 세션에서 GNB/LNB `document.body.innerText` 에 `검수자` / `검수자김` 정상 렌더(모지바케 없음). `useAuthStore.ts:29-40` TextDecoder 경로 정확 |
| TC-FE-014 | PASS | [정적] | `useAuthStore.ts:24` `if (parts.length !== 3) return null;` + 앞단 `tokenIngress.ts:39-41` 이 형식 검증. 테스트 `features/auth/__tests__/tokenIngress.test.ts:71` `JWT_형식이_아닌_입력은_null_반환` |
| TC-FE-015 | PASS | [실동작] | 탭에서 `sessionStorage['klid_jwt']=만료토큰` + localStorage 비움 → `/dashboard` 재적재 → 결과 `sessionStorage.klid_jwt === null` + `/dev/login`. `useAuthStore.ts:89-102`(`hydrate` 의 `claims.exp > now` 실패 시 `removeItem`) 정확. ⚠ **자동 테스트 0건**(H-ISSUE-11) |
| TC-FE-016 | PASS | [실동작] | 로그인 직후 `Object.keys(sessionStorage) === ['klid_jwt']`, 프로덕션 코드 경로에서 localStorage 쓰기 0건(`grep -rn localStorage src` → 쓰기는 **`features/auth/DevLoginPage.tsx:104,107,110,153` 뿐**이며 `isDevLoginEnabled()` 라우트 게이팅 하). `tokenIngress.ts:98-105` 는 **읽기 전용**. `useAuthStore.ts:76,82,86,90` 정확. ⚠ 관련 이슈 **H-ISSUE-01/04**(인계 채널 기본값), **H-ISSUE-11**(테스트 갭) |
| TC-FE-017 | PASS | [실동작] | Playwright 네트워크 캡처 — `GET /api/v1/augments/4/result?...` 요청 헤더에 `authorization: Bearer eyJhbGciOiJIUzI1NiJ9...`. `lib/api/client.ts:32-38` 정확. 테스트 `lib/api/__tests__/client.test.ts:24` |
| TC-FE-018 | PASS | [실동작] | 대시보드 KPI·최근완료영상 표가 `ApiResponse.data` 값으로 렌더(처리대기 2건/처리완료 20건/반려 1건 등). `client.ts:41-49` — `return { ...res, data: body.data, message: body.message }` 로 message 보존. 테스트 `client.test.ts:38` |
| TC-FE-019 | PASS | [정적] | `client.ts:43-45` — 성공 인터셉터 안에서 `throw ApiError.fromBody(body, res.status)`(같은 `use()` 의 rejection 핸들러를 타지 않고 Promise reject). 테스트 `client.test.ts:50` |
| TC-FE-020 | PARTIAL | [정적] | 구현은 정확: `client.ts:54-71` — `currentToken && !sentAuth && !config._retriedWithToken` 3조건 AND, 재시도 시 `_retriedWithToken=true` 로 **루프 차단**(반증 확인: 2번째 401 은 `sentAuth` truthy → clear+redirect 경로). 그러나 **전용 자동 테스트 0건**(`client.test.ts` 에 `_retriedWithToken`/재시도 케이스 없음)이고 실동작 재현은 레이스라 결정론적으로 만들 수 없다 → 회귀 무방비. **H-ISSUE-05** |
| TC-FE-021 | PASS | [정적] | `client.ts:72-75` `clear()` + `redirectToUpstreamLogin()`. 테스트 `client.test.ts:87` `401_응답시_redirectToUpstreamLogin_호출` / `:117` PORTAL 분기. ⚠ 실효성은 **H-ISSUE-02** 참조 |
| TC-FE-022 | PASS | [실동작] | `client.ts:23-30` — `import.meta.env.VITE_API_BASE_URL ?? '/api/v1'`, 사용자 입력 경로 없음. 실측: 캡처된 모든 API 호출이 `http://localhost:13000/api/v1/...`(동일 출처 상대경로)이며 외부 오리진 호출 0건. `redirectToUpstream.ts:14-24` 도 env 값만 사용 + `next` 는 `encodeURIComponent` |
| TC-FE-194 | PASS | [정적] | `lib/api/errors.ts:37` `if (status === 412) return 'PRECONDITION_FAILED';` · `:47` `'현재 상태에서는 수행할 수 없는 요청입니다.'`. 근거 라인 정확. 테스트 `lib/api/__tests__/errors.test.ts:14,20`. 라이브 dev 서버 서빙 모듈에도 존재 확인(`curl /src/lib/api/errors.ts`) |
| TC-FE-195 | PASS | [정적] | `lib/api/resolveApiMessage.ts:4` `USER_FACING_STATUSES = new Set([400,409,412])` · `:16-19` 그 외/비-ApiError 는 fallback. 근거 정확. 테스트 `errors.test.ts:36,43,48`. ⚠ 호출부 우회 경로 존재 → **H-ISSUE-06**(판정은 유틸 자체 기준이므로 PASS) |
| TC-FE-196 | PASS | [정적] | `lib/compactParams.ts:12-21` — `undefined/null/공백문자열/빈배열` 키 삭제, `0`·`false` 보존(`Object.entries` 순회에서 숫자 0 은 어떤 가드에도 걸리지 않음). 테스트 `lib/__tests__/compactParams.test.ts` 4건(`숫자_0_과_false_는_보존된다` 포함). 라이브 서빙 모듈 동일 |
| TC-E2E-001 | PARTIAL | [실동작] | ①**보호 경로 차단은 확인** — 미인증 `/dashboard`·`/video/completed` 진입 시 해당 화면이 렌더되지 않음. 스펙 `e2e/specs/login-redirect.spec.ts:4` 도 `expect(url).not.toMatch(/\/video\/completed$/)` 로 **"차단"만** 단언한다. ②그러나 케이스명의 **"상위 시스템 로그인으로 이동"은 어떤 빌드에서도 성립하지 않는다** — `VITE_CONTROL_LOGIN_URL`/`VITE_PORTAL_LOGIN_URL` 이 `.env.development` 에서 빈 값이고 **운영 배포 경로 어디에서도 주입되지 않아**(`docker-compose.yml`·`frontend/Dockerfile`·`deploy/onprem/**` 전수 grep 0건) `redirectToUpstream.ts:17` 이 항상 `false` 를 반환한다. → **H-ISSUE-02**. ③E2E 스펙 자체는 baseline 에서 미실행 |

**H-1 집계**: PASS 24 · PARTIAL 2 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 2. 판정 표 — H-14. 보안 (XSS/토큰/용어정책) (7건)

| ID | 판정 | 근거 확인 | 실측 내용 / 근거 |
|----|:--:|---|---|
| TC-FE-189 | PASS | [정적] | ①`features/label/components/FrameDescriptionPanel.tsx:59-61` — 설명은 `<textarea value={text}>` 로만 바인딩(주석 `:8` "저장형 XSS 방어") ②전 컴포넌트에 `dangerouslySetInnerHTML={` 0건(TC-FE-190) ③E2E 스펙 `e2e/specs/frame-description.spec.ts:191` `script태그_입력해도_텍스트로_표시된다_XSS방어` 가 `toHaveValue(payload)` + `locator('script').filter({hasText:'alert(1)'}).count() === 0` 를 단언 — **근거 라인 정확**. ⚠ 스펙 미실행(baseline 에 E2E 없음) → `[실동작]` 승격 불가 |
| TC-FE-190 | PASS | [정적] | `grep -rn 'dangerouslySetInnerHTML={' frontend/src frontend/e2e` → **0건**. 단순 `dangerouslySetInnerHTML` grep → **23건**(전부 "미사용" 명시 주석 21건 + 테스트 설명 2건: `KeypointGuide.test.tsx:75`, `IssueThreadPanel.test.tsx:3`). 부가 확인: `.innerHTML` 프로덕션 사용 0건(유일 매치는 테스트 단언 `PortalHomeGradient.test.tsx:32`), `eval(`/`new Function(`/`document.write`/`srcdoc` 0건. **근거 드리프트**: 카탈로그 "22건 매치" → 실측 **23건** |
| TC-FE-191 | PARTIAL | [실동작] | ①**화면 실렌더 위반 0건** — REVIEWER 세션 대시보드 `document.body.innerText` 에 `YOLO`/`SAM2`/`yolo` 0회. `components/common/BatchStageIndicator.tsx:16-24` 가 `YOLO→'AI 탐지'`, `SAM2→'AI 분할'` 로 매핑하고 `:26-28` 미지 단계는 `'처리중'` 폴백 — **근거 라인 정확**. `features/label/components/AiToolModal.tsx:4-13` 용어 정책 주석 및 실제 문구("AI 탐지/AI 분할/AI 추적") 정확. 시스템설정 `YoloConfigCard.tsx` 의 `YOLO_*` 는 register 키·`id` 속성뿐이고 노출 문구는 `AI 탐지 추론 파라미터`/`Confidence Threshold`/`이미지 크기 (imgsz)`/`IoU 임계값` → 카탈로그의 "설정 키 이름 예외" 와 정합. ②**그러나 두 건의 정책 이탈**: `components/common/StageBadge.tsx:60` 은 폴백이 `?? stage`(**원시 기술 코드 그대로 노출**)이고 그 맵에 `MARKING`/`INTERPOLATE` 가 없다(**H-ISSUE-07**), `pages/RoleClaimPage.tsx:60,87,90,94,129` 가 사용자 문구로 **"관리자 패스워드"/"관리자에게 받은"** 을 쓴다 — CLAUDE.md "ADMIN 역할 없음·UI 호칭 '검수자' 통일" 과 불일치(**H-ISSUE-08**) |
| TC-FE-192 | PASS | [실동작] | ①BE 실응답에 스택·내부경로 없음(위 환경 실측) — `GlobalExceptionHandler.java:273-278` 이 미처리 예외를 `ErrorCode.INTERNAL_ERROR.defaultMessage()` 로 고정 ②`components/common/ErrorBoundary.tsx:22-24` 는 스택을 `console.error` 로만 보내고, `render()`(`:27-37`)는 `state.message` 를 **렌더하지 않고** 고정 문구("오류가 발생했습니다 / 잠시 후 다시 시도해 주세요.")만 출력 — 근거 라인 정확 ③`resolveApiMessage.ts:4` 화이트리스트. ⚠ 일부 화면이 이 유틸을 우회해 `error.message` 를 직접 렌더 → **H-ISSUE-06**(현재 BE 메시지가 전부 일반 문구라 실제 유출은 없어 PASS 유지, 방어 일관성 결함) |
| TC-FE-193 | PASS | [정적] | 경로 파라미터가 **전부 `number` 타입**(`getLabels(srcSn: number)`, `reviews/${id:number}`, `videos/${rawSn:number}`)이라 주입 표면이 없고, **문자열 파라미터는 모두 명시적 `encodeURIComponent`**: `features/label/api.ts:978,1009`(trackId), `features/sysconfig/api.ts:26`(config key), `features/upload/api/tusClient.ts:110,124,141`(uploadId). ⚠ 카탈로그 기대결과의 "axios 인코딩"은 **부정확한 서술**(axios 는 템플릿 리터럴 path 를 자동 인코딩하지 않는다) — 실제 방어는 타입 제약 + 명시 인코딩. 근거 파일(`features/label/api.ts`·`features/review/api.ts`) 자체는 정확 |
| TC-E2E-012 | PASS | [정적] | `e2e/specs/frame-description.spec.ts:158` `저장_실패시_에러가_표시된다` — PUT 500(`ApiResponse success:false`) 모킹 후 `expect(labeling.frameDescriptionError).toBeVisible()`. 패널 측 `FrameDescriptionPanel.tsx:73` `<p role="alert">` 존재. 근거 라인 정확. ⚠ 스펙 미실행 |
| TC-E2E-013 | PASS | [정적] | `frame-description.spec.ts:94` `프레임_선택시_기존_설명이_표시된다` · `:113` `설명_입력_저장시_PUT이_호출되고_반영된다`(`waitForResponse` 로 `/frames/\d+/description` PUT 확인). 근거 라인 정확. ⚠ 스펙 미실행 |

**H-14 집계**: PASS 6 · PARTIAL 1 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 3. 합계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| H-1 | 26 | 24 | 0 | 2 | 0 | 0 | 0 |
| H-14 | 7 | 6 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **33** | **30** | **0** | **3** | **0** | **0** | **0** |

### 근거(file:line) 드리프트
| TC | 카탈로그 | 실측 |
|---|---|---|
| TC-FE-008 | `router/guards.tsx:104-143` | 실제 `AuthenticatedGuard` 함수 본문 `114-144`(104-113 은 JSDoc) |
| TC-FE-190 | "단순 grep 22건 매치" | **23건**(주석 21 + 테스트 설명 2) |
| TC-FE-193 | 기대결과 "axios 인코딩" | axios 는 path 템플릿을 자동 인코딩하지 않음. 실제 방어는 `number` 타입 제약 + 명시적 `encodeURIComponent` |

그 외 H-1/H-14 의 모든 `file:line` 근거는 실측과 일치했다.

---

## 4. 이슈 기록

### [H-ISSUE-01] TC-FE-016 인접 — 운영 빌드 기본값이 `VITE_TOKEN_INGRESS=all` 이라 **URL `?token=` 인계 채널이 활성**이다 (CLAUDE.md 정책 위반)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "인증·진입" 절이 **"브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 전달. URL 쿼리 파라미터(`?token=`) 방식 미사용"** 을 명시한다. 운영 산출물에서는 URL 채널이 꺼져 있어야 JWT 가 웹서버 access log·프록시 로그·브라우저 주소창/기록에 남지 않는다(CWE-598 Sensitive Information in Query String).
- **현재 동작(이슈 내용)**: 모든 운영 배포 경로의 기본값이 `all`(= url → localStorage → cookie 순 시도)이다.
  ```
  frontend/Dockerfile:33        ARG VITE_TOKEN_INGRESS=all
  docker-compose.yml:314        VITE_TOKEN_INGRESS: ${VITE_TOKEN_INGRESS:-all}
  .env.example:135              VITE_TOKEN_INGRESS=all
  deploy/onprem/scripts/package/20-build-frontend.sh:29
                                export VITE_TOKEN_INGRESS="${VITE_TOKEN_INGRESS:-all}"
  deploy/onprem/scripts/install/build-from-source.sh:182  (동일)
  deploy/onprem/docs/02-build-package.md:30  VITE_API_BASE_URL=/api/v1 VITE_TOKEN_INGRESS=all ./scripts/package.sh
  ```
  `frontend/.env.example:5` 만 `localStorage` 이고 **`.env.production` 파일 자체가 없다**. 실행중 컨테이너 실효값도 `all`:
  ```
  docker inspect klid-frontend → VITE_TOKEN_INGRESS=all
  ```
  코드:
  ```ts
  // frontend/src/features/auth/tokenIngress.ts:130-132
  if (strategy === 'url' || strategy === 'both' || strategy === 'all') {
    candidates.push(params.urlToken);
  }
  ```
- **재현/확인 경로**: 라이브 실측으로 URL 토큰 인계가 그대로 동작한다.
  ```
  브라우저 신규 탭 → http://localhost:13000/ingress?token=<JWT>
    → 최종 URL /dashboard (INTERNAL) 또는 /portal (PORTAL) 로 진입, sessionStorage['klid_jwt'] 적재됨
  ```
  (본 검증의 TC-FE-004/010/011/012 실측이 전부 이 채널로 수행됐다 = 채널이 살아 있다는 증거)
- **영향**: 보안 — JWT 가 GET 요청라인에 실려 nginx/Caddy access log, 리버스 프록시 로그, WAF 로그에 평문 기록된다(CWE-598/CWE-532). 관제·저작도구가 **같은 도메인**이라 브라우저 기록에도 남는다. `SessionIngressPage.tsx:97` 이 `navigate(target,{replace:true})` 로 history 항목은 치환하지만 **최초 document 요청은 이미 서버 로그에 남은 뒤**다.
- **수정 방향(제안)**: ①`frontend/Dockerfile:33`·`docker-compose.yml:314`·`deploy/onprem/scripts/package/20-build-frontend.sh:29`·`build-from-source.sh:182`·`.env.example:135` 의 기본값을 **`localStorage`** 로 뒤집는다(운영 표준 채널). ②`url` 채널이 필요한 곳은 로컬 개발뿐이므로 `frontend/.env.development` 에서만 `all` 유지. ③`deploy/onprem/docs/02-build-package.md:30,177` 예시 명령도 동시 갱신(문서가 `all` 을 권장 형태로 보여주고 있다).

### [H-ISSUE-02] TC-E2E-001 / TC-FE-003 / TC-FE-021 — 상위 시스템 로그인 URL 이 **운영 배포 어디에서도 주입되지 않아** 세션 만료·401 시 상위 로그인 이동이 성립하지 않는다
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` "세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트". TC-E2E-001(토큰 없이 보호 경로 → 상위 로그인), TC-FE-003(exp 만료 → `redirectToUpstream`), TC-FE-021(401 → `clear()`+상위 로그인)이 모두 이 배선에 의존한다. 저작도구는 **자체 로그인 UI 가 없으므로** 이 경로가 끊기면 사용자가 재로그인할 수단 자체가 없다.
- **현재 동작(이슈 내용)**: `redirectToUpstream` 은 env 미설정 시 아무 것도 하지 않고 `false` 를 반환하는데, 그 env 를 채우는 배포 배선이 없다.
  ```ts
  // frontend/src/features/auth/redirectToUpstream.ts:13-18
  const portalUrl  = import.meta.env.VITE_PORTAL_LOGIN_URL as string | undefined;
  const controlUrl = import.meta.env.VITE_CONTROL_LOGIN_URL as string | undefined;
  const target = channel === 'PORTAL' ? portalUrl : controlUrl;
  if (!target) return false;          // ← 운영 빌드에서 항상 여기
  ```
  전수 grep 결과 — 두 변수는 **`frontend/.env.example`(빈 값 아님, 예시 도메인)과 `frontend/.env.development`(빈 값)에만** 존재하고
  `docker-compose.yml` · `frontend/Dockerfile` · `deploy/onprem/**`(패키징·설치 스크립트·Caddyfile/nginx 템플릿·env.template) 어디에도 없다.
  ```
  grep -rn "VITE_CONTROL_LOGIN_URL\|VITE_PORTAL_LOGIN_URL" docker-compose.yml frontend/Dockerfile deploy → 0건
  grep -rn "VITE_" frontend/Dockerfile → VITE_API_BASE_URL, VITE_TOKEN_INGRESS 두 개뿐
  ```
  Vite 는 `VITE_*` 를 **빌드 시점에 정적 치환**하므로(`deploy/onprem/docs/02-build-package.md:177` 명시) 런타임 주입으로 보완되지도 않는다.
- **재현/확인 경로**:
  ```
  # 현재 스택(dev 빌드) — 빈 값이라 redirect 미발생. dev 플래그 덕에 /dev/login 으로 빠진다
  신규 탭 → http://localhost:13000/dashboard   → 최종 URL /dev/login
  # 운영 빌드 재현: VITE_DEV_LOGIN_ENABLED=false 로 빌드 후 동일 진입
  #   → redirectToUpstream=false → SessionIngressPage.tsx:47 setErrorMessage(...)
  #   → "로그인 서버에 연결할 수 없습니다." 문구만 뜨는 막다른 화면(재진입 수단 없음)
  ```
- **영향**: 기능 — 운영에서 세션 만료·401 발생 시 **사용자가 스스로 복귀할 수 없다**(브라우저 주소창에 관제 URL 을 직접 입력해야 함). 특히 `client.ts:73-75` 는 `clear()` 를 먼저 수행하므로 토큰만 사라지고 이동은 안 되는 상태가 된다. TC-E2E-001 의 기대결과가 구조적으로 미충족.
- **수정 방향(제안)**: ①`frontend/Dockerfile` 에 `ARG/ENV VITE_CONTROL_LOGIN_URL`·`VITE_PORTAL_LOGIN_URL` 추가, `docker-compose.yml` build args 및 `deploy/onprem/scripts/package/20-build-frontend.sh`·`install/build-from-source.sh` 에 export 추가(값 미지정 시 **빌드를 실패시키는 fail-closed** 검사 권장 — 조용히 비면 위 막다른 화면이 된다). ②`deploy/onprem/config/frontend/*.template` 또는 설치 문서(`04-configuration.md`)에 필수 항목으로 등재. ③보완책으로 `SessionIngressPage` 의 막다른 에러 화면에 "관제서버로 돌아가기" 링크를 두되, 링크 대상도 같은 env 라 ①이 선행돼야 한다.

### [H-ISSUE-03] TC-FE-016 / TC-E2E-001 인접 — 온프렘 **운영 패키징 기본값이 `VITE_DEV_LOGIN_ENABLED=true` · `VITE_DEV_UPLOAD_ENABLED=true`** 라 prd 산출물에 dev 라우트가 포함된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `frontend/src/router/index.tsx:167-181` 주석이 명시하듯 "플래그가 false/미설정인 prod 빌드에서는 `if` 블록 전체가 dead-code 로 제거되어 DevLoginPage 청크 자체가 산출물에 포함되지 않는다"(TC-FE-028 기대결과와 동일). 운영 산출물에는 개발용 토큰 발급 UI 가 존재해선 안 된다.
- **현재 동작(이슈 내용)**: 온프렘 패키징·소스빌드 스크립트가 기본을 **`true`** 로 켠다.
  ```
  deploy/onprem/scripts/package/20-build-frontend.sh:33
      export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
  deploy/onprem/scripts/install/build-from-source.sh:183-184
      export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
      export VITE_DEV_UPLOAD_ENABLED="${VITE_DEV_UPLOAD_ENABLED:-true}"
  deploy/onprem/docs/08-build-from-source.md:64-65  (동일 기본값을 문서에 명시)
  ```
  결과적으로 prd 번들에 `/dev/login` 라우트 + `DevLoginPage` 청크가 실린다. 실동작으로도 확인됨 — 현재 스택의 미인증 진입이 상위 로그인이 아니라 **`/dev/login`** 으로 빠진다(`SessionIngressPage.tsx:41-44`).
- **재현/확인 경로**:
  ```
  신규 탭 → http://localhost:13000/dashboard → /dev/login (역할 3종 라디오 + "토큰 발급 + 진입" 버튼 노출)
  # BE 측 최종 방어는 살아 있다(fail-closed 확인):
  #   backend/src/main/java/.../dev/controller/DevTokenController.java:34
  #     @ConditionalOnProperty(prefix="authoring.dev.login", name="enabled", havingValue="true")
  #   application.yml:456-458  authoring.dev.login.enabled = ${DEV_LOGIN_ENABLED:false}
  #   application-prd.yml      dev 블록 없음 → false, deploy/onprem/config/backend/env.template:155 도 주석 처리
  ```
- **영향**: 보안(공격 표면) — BE 가 fail-closed 라 **실제 토큰 자가 발급은 차단**되므로 권한 상승은 성립하지 않는다. 다만 ①운영 화면에 동작하지 않는 개발용 로그인 UI 가 노출돼(H-ISSUE-02 와 겹쳐 **만료 사용자가 이 막다른 페이지로 유도된다**) ②`DEV_LOGIN_ENABLED=true` 가 한 번이라도 BE 에 켜지면 즉시 임의 역할 토큰 발급으로 이어지는 **단일 설정 실수 = 전면 권한 우회** 구조가 된다 ③LNB "개발 도구 / 영상 업로드" 메뉴도 REVIEWER 에게 노출된다.
- **수정 방향(제안)**: 패키징/설치 스크립트 기본값을 **`false`** 로 뒤집고(폐쇄망 bring-up 시에만 명시 opt-in), `deploy/onprem/docs/08-build-from-source.md:64-65`·`02-build-package.md` 예시를 함께 정정. 추가로 빌드 산출물 검사(`grep -r "dev/login" dist/`)를 패키징 스크립트의 사후 게이트로 넣는 것을 권장.

### [H-ISSUE-04] TC-FE-016 인접 — `getStrategy()` 가 **잘못된 설정값을 가장 넓은 `all` 로 fail-open** 한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 관련 설정의 파싱 실패는 **가장 좁은 값(fail-closed)** 으로 떨어져야 한다(`rules/security.md` "Mishandling of Exceptional Conditions — fail-secure/fail-closed"). 오타 하나로 인계 채널이 조용히 넓어져선 안 된다.
- **현재 동작(이슈 내용)**:
  ```ts
  // frontend/src/features/auth/tokenIngress.ts:22-34
  function getStrategy(): IngressStrategy {
    const v = (import.meta.env.VITE_TOKEN_INGRESS as string | undefined) ?? 'all';
    if (v === 'url' || v === 'cookie' || v === 'localStorage' || v === 'both' || v === 'all') return v;
    return 'all';          // ← 오타·미설정 모두 최광범위로 폴백
  }
  ```
  기존 테스트도 이 동작을 고정하고 있다(`tokenIngress.test.ts:156` `VITE_TOKEN_INGRESS_잘못된_값이면_all로_폴백`).
- **재현/확인 경로**: `VITE_TOKEN_INGRESS=localstorage`(소문자 오타)로 빌드 → `all` 로 동작해 URL·cookie 채널이 함께 열린다.
- **영향**: 보안 — H-ISSUE-01 의 재발 경로. 설정을 `localStorage` 로 고쳐도 오타 시 원상 복귀한다.
- **수정 방향(제안)**: 폴백을 `'localStorage'`(운영 표준 채널)로 바꾸고, 미인식 값은 `console.warn` + 폴백을 남긴다. 함께 `frontend/src/vite-env.d.ts:5` 의 `readonly VITE_TOKEN_INGRESS: 'url' | 'cookie'` 타입을 실제 5종(`'url'|'cookie'|'localStorage'|'both'|'all'`)으로 정정한다(현재 타입 선언이 실제 허용값과 어긋나 IDE 가 오타를 못 잡는다).

### [H-ISSUE-05] TC-FE-020 — 401 토큰 레이스 **1회 재시도 로직에 자동 테스트가 0건**이다
- **심각도**: LOW
- **기대 동작(기대효과)**: "토큰 적재 직전에 발사돼 Authorization 없이 401 을 받은 요청은 토큰을 붙여 **정확히 1회만** 재시도" 는 무한 재시도(요청 폭주)와 재시도 누락(첫 화면 데이터 유실) 사이의 좁은 계약이라 회귀 가드가 필요하다.
- **현재 동작(이슈 내용)**: 구현은 존재하고 정확하다.
  ```ts
  // frontend/src/lib/api/client.ts:62-71
  if (config && currentToken && !sentAuth && !config._retriedWithToken) {
    config._retriedWithToken = true;
    config.headers?.set?.('Authorization', `Bearer ${currentToken}`);
    return apiClient.request(config);
  }
  ```
  그러나 `frontend/src/lib/api/__tests__/client.test.ts` 의 7개 케이스(`:24,38,50,68,87,117`) 중 재시도를 다루는 것이 없고, 저장소 전체에 `_retriedWithToken` 을 참조하는 테스트가 0건이다.
- **재현/확인 경로**: `grep -rn "_retriedWithToken" frontend/src --include="*.test.ts*"` → 0건. 실동작 재현은 "토큰 적재 직전 발사" 레이스라 결정론적으로 만들 수 없다.
- **영향**: 기능/회귀 — 인터셉터 리팩터링 시 `!sentAuth` 나 `_retriedWithToken` 가드가 조용히 사라지면 401 무한 루프(자기 DoS) 또는 초기 화면 빈 데이터가 된다. 두 실패 모두 조용하다.
- **수정 방향(제안)**: `client.test.ts` 에 ①헤더 없이 보낸 401 + store 토큰 존재 → 재요청 1회 + `Authorization` 부착 ②재시도분도 401 → **추가 재시도 없이** `clear()`+redirect ③애초에 헤더를 달고 보낸 401 → 재시도 0회, 3케이스를 추가한다(`vi.spyOn(apiClient,'request')` 호출 횟수로 단언).

### [H-ISSUE-06] TC-FE-192 / TC-FE-195 — `resolveApiMessage` 정책을 **우회해 `error.message` 를 그대로 렌더**하는 화면이 있다
- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveApiMessage.ts:4,16-19` 는 "400/409/412 만 서버 문구 노출, 그 외(401/403/5xx·비-ApiError)는 fallback" 를 **단일 지점**에서 강제하려고 만든 유틸이다(CWE-209). 에러 표시 경로가 이 유틸을 통과해야 정책이 실효를 갖는다.
- **현재 동작(이슈 내용)**: 아래 4곳이 상태코드 구분 없이 원문을 렌더한다.
  ```tsx
  frontend/src/features/version/components/HistoryPanel.tsx:194
      <ErrorState title="버전 이력 조회 실패" message={error.message} />
  frontend/src/features/version/components/HistoryPanel.tsx:254
      <ErrorState title="diff 조회 실패" message={diffQuery.error.message} />
  frontend/src/pages/HistoryPage.tsx:99
      <ErrorState title="프레임 조회 실패" message={error.message} />
  frontend/src/pages/label/LabelingPage.tsx:1144
      <p className="text-sm text-gray-400 mb-4">{error.message}</p>
  ```
- **재현/확인 경로**: 네트워크 차단 상태에서 `/history/{videoId}` 진입 → 화면에 axios 원문(`Network Error` / `timeout of 30000ms exceeded`)이 그대로 노출. 5xx 시에는 BE 가 준 문구가 상태코드 필터 없이 노출.
- **영향**: 보안(정보 노출, 현재는 **잠재**) — 실측상 BE 는 500 에 `ErrorCode.INTERNAL_ERROR.defaultMessage()` 고정값만 내려주므로(`GlobalExceptionHandler.java:273-278`) **지금 당장 내부 정보가 새지는 않는다**. 다만 BE 가 어떤 예외 핸들러에서 상세 메시지를 담기 시작하면 이 4곳만 조용히 새는 구조이고, 정책이 한 곳에 모여 있지 않아 감사도 어렵다.
- **수정 방향(제안)**: 위 4곳을 `resolveApiMessage(error, '조회에 실패했습니다. 잠시 후 다시 시도해 주세요.')` 로 교체한다. 재발 방지로 ESLint `no-restricted-syntax` 규칙(예: JSX 속성/자식으로 `*.message` 직접 사용 금지, 폼 검증 `errors.*.message` 는 예외) 추가를 검토.

### [H-ISSUE-07] TC-FE-191 — `StageBadge` 의 미지 단계 폴백이 **원시 기술 코드 그대로**여서 `BatchStageIndicator` 의 `'처리중'` 정책과 어긋난다
- **심각도**: LOW
- **기대 동작(기대효과)**: TC-FE-191 기대결과 "미지 단계는 '처리중'". 화면에 배치 단계 **기술 코드명이 새지 않아야** 한다(`BatchStageIndicator.tsx:26-28` 주석이 같은 취지를 명시).
- **현재 동작(이슈 내용)**: 같은 목적의 두 컴포넌트가 폴백 정책이 다르다.
  ```tsx
  // frontend/src/components/common/BatchStageIndicator.tsx:28,81
  const STAGE_LABEL_FALLBACK = '처리중';
  {STAGE_LABEL[stage.name] ?? STAGE_LABEL_FALLBACK}

  // frontend/src/components/common/StageBadge.tsx:60
  const label = STAGE_LABEL[stage] ?? stage;      // ← 원시 코드 노출
  ```
  게다가 `StageBadge` 의 맵(`:15-24`)에는 BE canonical 단계 중 **`MARKING`·`INTERPOLATE` 가 없다**(`BatchStageIndicator` 에는 있다).
- **재현/확인 경로**: `<StageBadge stage="MARKING" />` → 화면에 `MARKING` 그대로 출력. **현재 프로덕션 호출부는 `pages/VideoListPage.tsx:318,320` 두 곳뿐이고 둘 다 리터럴 `"COMPLETED"`/`"FAILED"` 를 넘기므로 실도달하지 않는다**(그래서 TC-FE-191 을 FAIL 이 아닌 PARTIAL 로 판정).
- **영향**: 기능/용어정책(잠재) — BE 가 단계를 추가하거나 `StageBadge` 를 동적 stage 로 재사용하는 순간 기술 코드가 화면에 노출된다. 두 컴포넌트의 정책 분기 자체가 드리프트 소스다.
- **수정 방향(제안)**: `StageBadge.tsx:60` 폴백을 `'처리중'` 으로 바꾸고 `MARKING`/`INTERPOLATE` 매핑을 추가한다. 근본적으로는 `STAGE_LABEL` 맵과 폴백 상수를 한 모듈(예: `components/common/stageLabel.ts`)로 추출해 두 컴포넌트가 공유하게 한다.

### [H-ISSUE-08] TC-FE-191 — 사용자 문구에 **"관리자"** 를 쓴다 (ADMIN 역할 부재 · UI 호칭 '검수자' 통일 정책 위반)
- **심각도**: LOW
- **기대 동작(기대효과)**: `CLAUDE.md` "역할 정의" — *"시스템 관리자(ADMIN) 역할은 없으며 모든 관리 권한은 REVIEWER에 통합되어 있다. **UI 호칭은 '검수자'로 통일**"*. 존재하지 않는 역할명을 화면에 쓰면 사용자가 누구에게 요청해야 하는지 알 수 없고, 감리에서 역할 모델 불일치로 지적된다.
- **현재 동작(이슈 내용)**:
  ```tsx
  frontend/src/pages/RoleClaimPage.tsx:60   관리자에게 받은 패스워드로 역할을 부여받으세요.
  frontend/src/pages/RoleClaimPage.tsx:87   label="관리자 패스워드"
  frontend/src/pages/RoleClaimPage.tsx:90   placeholder="관리자에게 받은 패스워드를 입력하세요"
  frontend/src/pages/RoleClaimPage.tsx:94   aria-label="관리자 패스워드"
  frontend/src/pages/RoleClaimPage.tsx:129  '관리자 패스워드가 일치하지 않습니다.'
  frontend/src/pages/MarkingPage.tsx:70     data.batchSkipReason ?? '관리자에게 문의하세요.'
  ```
  같은 파일 `:12` 주석은 오히려 *"REVIEWER(사실상 관리자)"* 라고 적어 코드 자체가 호칭 혼용을 인지하고 있다.
- **재현/확인 경로**: WORKER/REVIEWER 세션 무관 — role 미부여 토큰으로 진입하면 `/role-claim` 이 렌더되고 위 문구가 그대로 표시된다(본 검증에서 `/role-claim` 실렌더 확인). 실동작 대조: LNB/GNB 역할 표기는 정책대로 **"검수자"** 로 나온다(`document.body.innerText` 실측) → 화면 간 호칭이 갈린다.
- **영향**: 요구사항 정합/UX — 역할 모델(REVIEWER 단일화)과 화면 문구 불일치. 사용자가 존재하지 않는 "관리자"를 찾게 된다.
- **수정 방향(제안)**: 위 6개 문구를 **"검수자"** 기준으로 정정(예: `검수자 패스워드`, `검수자에게 받은 패스워드를 입력하세요`, `검수자에게 문의하세요`). `RoleClaimPage.tsx:12` 주석의 "사실상 관리자" 표현도 정리. 재발 방지로 FE 문구 lint(금칙어 `관리자`/`ADMIN`, 예외: 코드 식별자·주석)를 검토.

### [H-ISSUE-09] TC-FE-002 인접 — `/` 하위 **404 catch-all 라우트가 무가드**라 미인증 사용자에게 AppLayout(GNB/LNB 메뉴 전체)이 렌더된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 인증 전에는 애플리케이션 셸(메뉴 구조)이 노출되지 않아야 한다. `/` 이하 모든 경로는 `InternalRoute`(ChannelGuard+RoleGuard)를 통과한 뒤 렌더되는 것이 라우터 설계 의도다.
- **현재 동작(이슈 내용)**: `path:'/'` 의 `element: <AppLayout />` 자체에는 가드가 없고, 자식 중 `{ path:'*' }` 와 `{ index:true }` 만 가드가 빠져 있다.
  ```tsx
  // frontend/src/router/index.tsx:228-233, 459
  { path: '/', element: <AppLayout />, errorElement: <AppErrorPage status={500} />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },   // 가드 없음(→ 즉시 guarded 경로로 이동하므로 무해)
      ...
      { path: '*', element: <AppErrorPage status={404} /> },            // 가드 없음 → 셸 렌더
  ```
  `AppLayout.tsx` → `Gnb`/`Lnb` 는 API 호출은 없으나 claims 없을 때 기본값으로 렌더된다(`Gnb.tsx:30-31` `role = claims?.role ?? Role.WORKER`, `name = claims?.name ?? '사용자'`).
- **재현/확인 경로**:
  ```
  신규 탭(빈 sessionStorage) → http://localhost:13000/xyz-nonexistent-h1
    → /ingress 로 튕기지 않고 그대로 유지되며 AppLayout 404 화면이 렌더된다
    (비교: /dashboard·/review 등 가드 경로는 즉시 /ingress→/dev/login 으로 이동)
  ```
- **영향**: 보안(정보 노출, 경미) — 사용자 데이터는 없고 **메뉴 구조·기능 목록만** 노출된다(CWE-200 경미). 개인정보·업무 데이터 유출은 없다.
- **수정 방향(제안)**: `router/index.tsx:459` 의 404 element 를 `<InternalRoute allow={internalAllRoles}><AppErrorPage status={404} /></InternalRoute>` 로 감싸거나, `AppLayout` 자체를 `ChannelGuard` 안으로 옮긴다(후자가 `index` 라우트까지 한 번에 정리된다).

### [H-ISSUE-10] TC-FE-015 / TC-FE-016 — `hydrate()`·세션 스토리지 정책에 **자동 테스트가 0건**이다
- **심각도**: LOW
- **기대 동작(기대효과)**: "만료 토큰은 hydrate 시점에 sessionStorage 에서 제거"(TC-FE-015)와 "토큰은 sessionStorage 에만 저장"(TC-FE-016)은 XSS 노출면과 직결된 보안 계약이라 회귀 가드가 필요하다.
- **현재 동작(이슈 내용)**: 두 동작을 검증하는 테스트가 없다.
  ```
  grep -rn "hydrate(" frontend/src --include="*.test.ts*"        → 0건
  grep -rln "sessionStorage" frontend/src --include="*.test.ts*" → 0건
  ```
  `stores/__tests__/useAuthStore.test.ts` 의 3케이스(`:18,27,47`)는 `setToken`/`clear`/무효 토큰만 다루고 `hydrate` 와 스토리지 종류는 다루지 않는다.
- **재현/확인 경로**: 본 검증은 실브라우저로 두 동작을 확인했다(TC-FE-015: 만료 토큰 주입 후 재적재 → `sessionStorage.klid_jwt === null` / TC-FE-016: `Object.keys(sessionStorage) === ['klid_jwt']`). 자동화가 없어 회귀는 잡히지 않는다.
- **영향**: 회귀 — `hydrate` 의 `claims.exp > now` 비교가 사라지면 만료 토큰이 잔류하고, 저장소를 localStorage 로 바꾸는 변경이 테스트에 걸리지 않는다.
- **수정 방향(제안)**: `useAuthStore.test.ts` 에 ①만료 토큰이 sessionStorage 에 있을 때 `hydrate()` → `token=null` + `sessionStorage.getItem('klid_jwt')===null` + `isHydrated=true` ②유효 토큰 → 복원 성공 ③`setToken` 후 `localStorage` 에 아무 키도 생기지 않음, 3케이스를 추가한다(`src/test/setup.ts:40-58` 이 이미 jsdom 스토리지를 폴리필하므로 추가 배선 불필요).

---

## 5. 판정 시 적용한 확정 정책(★) 확인
- **★1(신고 게이트 자기 rawSn 한정)·★2(정렬 키 응답 비대칭)·★3(좌표 검증 2축)** — 본 범위(H-1·H-14)에는 해당 케이스가 없어 적용 대상 없음. 위 이슈 중 어느 것도 ★ 정책을 되돌리자는 제안이 아니다.
- UNCERTAINTIES **#23**(VideoPlayer 배속 이산 6버튼)·**#25**(DevAutolabelTestPage/내부 TUS dead-code) 관련 — #25 의 "prod 빌드 기준 dead-code 맞음" 전제는 **온프렘 패키징 기본값이 `VITE_DEV_UPLOAD_ENABLED=true` 라 성립하지 않는다**(H-ISSUE-03). UNCERTAINTIES #25 원문 갱신 제안 대상.
# H 클러스터 part2 — H-2(라우터/코드스플리팅) + H-4(useLabelStore) + H-5(좌표변환/캔버스 유틸) 검증 결과

> 대상: `docs/test-cases/H-frontend-e2e.md` `## H-2`(11건: TC-FE-023~032, TC-E2E-002) + `## H-4`(14건: TC-FE-088~101) + `## H-5`(8건: TC-FE-102~109) = **33건**
> 회차: 2026-08-02 2차 · 이슈 ID 범위: H-ISSUE-21~40
> 코드 기준: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801`
> frontend `localhost:13000`(docker `klid-frontend`, Vite dev 모드) · backend `localhost:18081`(docker `klid-backend`)

## 0. 검증 환경 실측

| 항목 | 실측 |
|---|---|
| 스택 | `docker ps` — frontend/backend/ai-server/mock-server/postgres 전부 `Up 19h (healthy)` |
| frontend 접근 | `curl -o /dev/null -w '%{http_code}' http://localhost:13000/` → **200** |
| dev 토큰 발급 | `POST http://localhost:18081/api/v1/dev/tokens` (REVIEWER/sub=1001) → 200, JWT 정상 발급 |
| baseline | `_raw/test-baseline.md`(2026-08-01): frontend `npx vitest run` **1,951 tests / 1,951 pass / 0 fail**(332 파일) |
| 브라우저 관찰 | Playwright MCP 로 `http://localhost:13000/ingress` 진입 → **`/dashboard`로 즉시 redirect 실동작 확인**(TC-FE-023). 단, 이 브라우저 인스턴스가 **다른 병렬 QA 서브에이전트와 세션/스토리지를 공유**하는 정황(새 탭에서도 동일 `klid_jwt` 토큰 관찰, 다른 파트가 연 페이지의 네트워크 로그가 섞여 관찰됨)을 확인해 **상호 오염 방지를 위해 대화형 브라우저 조작은 읽기 전용 1건(redirect 확인)으로 제한**하고, 이후 판정은 **정적 코드 대조 + 기존 FE 단위테스트(격리된 vitest 프로세스, 오염 없음)** 중심으로 수행함 — 근거확인 컬럼에 [실동작]/[정적] 명시 |

---

## 1. H-2. 라우터 구조/코드스플리팅 (11건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-FE-023 | PASS | [실동작]+[정적] | Playwright 로 `/ingress` 진입(인증 상태) → **`/dashboard`로 replace redirect** 즉시 확인. 코드: `router/index.tsx:233` `{ index: true, element: <Navigate to="/dashboard" replace /> }` — 카탈로그 라인과 정확히 일치 |
| TC-FE-024 | PASS | [정적] | `router/index.tsx:218-224` `/label/:id` 는 `path:'/'`(AppLayout) 트리 **바깥**에 최상위로 정의돼 LNB/GNB(AppLayout) 미적용. `InternalRoute` 인가 가드는 유지 |
| TC-FE-025 | PASS | [정적] | `internalReviewerOnly=[Role.REVIEWER]`(line 149) 사용처 grep 결과 review index=**290**, stat/overall=**337**, augment index=**350**, manage/users=**380** — 카탈로그 근거 라인 4곳 전부 **정확히 일치**(drift 없음) |
| TC-FE-026 | PASS | [정적] | `router/index.tsx:459` `{ path: '*', element: <AppErrorPage status={404} /> }` — `/` 트리(AppLayout) children 최하단, 라인 정확히 일치 |
| TC-FE-027 | PASS | [정적] | `router/index.tsx:417-424` manage 하위 `path:'*'` → `PlaceholderPage title="관리"` + `InternalRoute allow={internalReviewerOnly}`, 라인 정확히 일치. react-router v6 splat(`*`)은 빈 나머지 세그먼트도 매칭하므로 `/manage` 단독 진입도 커버 |
| TC-FE-028 | PASS | [정적] | `lib/devLogin.ts:isDevLoginEnabled()` = `import.meta.env.DEV \|\| VITE_DEV_LOGIN_ENABLED==='true'`. Vite 는 `import.meta.env.DEV`/env 값을 빌드타임 리터럴로 치환하므로 두 값 모두 false 인 prod 빌드는 `if(isDevLoginEnabled())` 블록이 상수 false 로 접혀 tree-shaking 대상이 된다. **이번 라운드에 실제 `npm run build` 를 재실행해 청크 부재를 재확인하지는 않음**(빌드 실행 금지 규칙) — 동일 패턴의 자매 플래그(`isDevUploadEnabled`)가 `UNCERTAINTIES.md` #25 에서 이미 "prod 빌드 기준 dead-code 확정"으로 정리돼 있어 그 판례를 그대로 적용 |
| TC-FE-029 | PASS | [정적] | `router/index.tsx:183-203` 주석·코드 정확히 일치. `isDevUploadEnabled()`(`lib/devUpload.ts`) 판정 로직도 TC-FE-028 과 대칭. `UNCERTAINTIES.md` #25 에서 **이미 "✅ 확정"** — 재검증으로 반증 시도(라우트 배열 조건문 재확인) 결과 동일 결론 |
| TC-FE-030 | PASS | [정적] | `router/index.tsx:160-166` `PortalRoute` = `ChannelGuard channel="PORTAL"` + `RoleGuard allow=[PORTAL_USER]}` 이중 래핑, 라인 정확히 일치 |
| TC-FE-031 | PASS | [정적] | `router/lazyWithRetry.ts` 코드 전문 검토: 1차 실패 → 300ms 지연 후 1회 재시도 → 그래도 실패 시 `sessionStorage` 플래그로 **버스트당 1회만** `window.location.reload()`, 성공 시 플래그 제거(다음 실패 버스트에 다시 reload 가능). **전용 자동테스트 파일 없음**(카탈로그 근거도 파일명만 명시하고 테스트 파일을 인용하지 않아 정합) — 코드 검증만으로 판정 |
| TC-FE-032 | PASS | [정적] | `router/index.tsx:127-137` `PageFallback`(`Spinner label="페이지 로딩"`) + `withSuspense` 정확히 일치 |
| TC-E2E-002 | PASS | [정적] | 5개 테스트 파일 전부 실존 확인: `deepLinkHydrationGuard.test.tsx`(hydration 대기 중 미redirect), `manageGuard.test.tsx`, `portalGuard.test.tsx`, `reviewGuard.test.tsx`, `augmentExportGuard.test.tsx`. 테스트는 `RoleGuard`/`ChannelGuard` 를 자체 `MemoryRouter` 래퍼로 감싸 검증하는 방식이라 **실제 라우터 배선과 allow 목록이 일치하는지 별도 대조** 필요 — `router/index.tsx` 의 `internalReviewerOnly`(manage/review/augment/overall) · `internalAllRoles`(video/task/dashboard/stat/notice/history) · `portalOnly` 조합이 테스트 wrapper 의 `Role.REVIEWER`/`portalOnly` 등과 **동일하게 일치**함을 확인 — wrapper 와 실배선 간 드리프트 없음. baseline(`test-baseline.md`) 상 FE 전체 1,951/1,951 통과에 포함 |

### H-2 반증 시도 기록

1. **manage 인덱스 없는 진입** — `/manage` 단독 URL(하위 세그먼트 없음)이 react-router v6 splat(`*`) 매칭 규칙상 catch-all `PlaceholderPage`(REVIEWER 게이팅)로 떨어지는지 정적 확인 — v6 splat 은 "0개 이상" 세그먼트를 매칭하므로 성립, 미인가 우회 없음.
2. **dev 플래그 조합 우회** — `isDevLoginEnabled`/`isDevUploadEnabled` 둘 다 `import.meta.env.DEV`(Vite 자동 주입, 사용자가 조작 불가) 우선 OR 조건이라 **prod 빌드에서 임의 문자열/미설정은 전부 false**(fail-closed) 로 떨어짐 — 화이트리스트 반대의 "명시적 true 문자열만 허용" 패턴이라 우회 여지 없음.
3. **lazyWithRetry 무한루프 가능성** — 재시도 실패 후 reload 플래그를 `sessionStorage`에 남기므로 새 세션(플래그 없음)마다 다시 1회 reload 가능 — 무한 reload 루프는 "같은 세션 내 재실패"로 제한되어 방지됨(코드 47-79행 로직 확인).

---

## 2. H-4. useLabelStore (Zustand) (14건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-FE-088 | PASS | [정적] | `setLabels`(실제 위치 `stores/useLabelStore.ts:486-495`, 카탈로그는 401 — **근거 드리프트 +85줄**) — `labels`/`dirtyLabels`/`undoStack`/`redoStack`/`selectedLabelId` 전부 초기화 + `hiddenLabelIds`/`lockedLabelIds`도 함께 초기화(기대결과 "전부 초기화"와 일치, 오히려 더 넓게 초기화). 직접 테스트 `useLabelStore.test.ts:71` `setLabels_시_dirty_undo_초기화` |
| TC-FE-089 | PASS | [정적] | `addLabel`(실제 497-503, 카탈로그 412 — **드리프트 +85줄**) — `dirtyLabels.add(label.id)` 확인. 직접 테스트 `useLabelStore.test.ts:41` `addLabel_시_dirtyLabels에_추가` |
| TC-FE-090 | PASS | [정적] | `offsetShape`(66-89, 카탈로그와 **정확히 일치**) — BBOX(4필드 평행이동)/POLYGON(짝수인덱스 dx, 홀수인덱스 dy)/KEYPOINT(x,y 각각 이동) 타입별 분기 확인. 간접 테스트: `useLabelStore.clipboard.test.ts` `완전동일_좌표_존재_시_offset_적용`(BBOX 기준 +10 오프셋 검증) |
| TC-FE-091 | PASS | [정적] | `clampShape`(92-117, **정확히 일치**) — `cx=(v)=> w!=null ? clamp(v,0,w) : max(v,0)` — **w/h 미지정 시 하한 0만 적용**(상한 무시) 정확히 일치. 테스트 `useLabelStore.clipboard.test.ts`: `offset_후_이미지_경계_clamp`(상한 15로 clamp) + `붙여넣기_실측_dims_미확정시_상한_미적용_하한만`(imageWidth/Height 미전달 시 상한 미적용, "1080 하드코딩" 회귀 아님을 명시 검증) |
| TC-FE-092 | PASS | [정적] | `mergeAutoLabels`(636-653) — `mergeDetections` 로 중복(같은 클래스+IoU) 스킵, `merged.length` 반환. 테스트 파일 `useLabelStore.mergeAuto.test.ts` 실존 |
| TC-FE-093 | PASS | [정적] | `revertSaveEvent`(655-734) — UPDATED/ADDED/DELETED 3분기 + `reverted`/`skipped` 카운트 반환. 테스트 `useLabelStore.revert.test.ts` 실존 |
| TC-FE-094 | PASS | [정적] | `stashPendingTracks`/`drainPendingTracks`(736-758) — srcSn 별 누적 stash, drain 시 해당 키만 제거(불변). 테스트 `useLabelStore.pendingTracks.test.ts` 실존 |
| TC-FE-095 | PASS | [정적] | `toggleLabelVisibility`(567-572) — 새 `Set` 생성(불변성 준수), `dirtyLabels` 미영향(별개 상태). 테스트 `useLabelStore.visibility.test.ts` 실존 |
| TC-FE-096 | PASS | [정적] | `updateLabel`/`removeLabel` 진입부에서 `lockedLabelIds.has(id)` 체크 후 조기 return(507,518) — dirty/undoStack 변화 없음(no-op) 확인. 테스트 `useLabelStore.lock.test.ts` 실존 |
| TC-FE-097 | PASS | [정적] | `copyLabels`/`pasteLabels`(582-634) — `sourceRawSn` 기준 크로스영상 판정(`crossVideo`)해 `trackId` 제거. 테스트 `useLabelStore.clipboard.test.ts` 16건 케이스(크로스영상/동일영상/undo/클립보드 유지 등) 실존 |
| TC-FE-098 | PASS | [정적] | `KeypointShape.keypoints`(`types.ts:114-117`) — COCO-17 삼중값 `{x,y,v}` 배열. 테스트 `useLabelStore.keypoint.test.ts` 실존 |
| TC-FE-099 | PASS | [정적] | `imageAdjust`(`DEFAULT_IMAGE_ADJUST` 상수로 세션 초기화, `setImageAdjust`/`resetImageAdjust`) — zustand 인메모리 상태라 영속 계층 없음(스토어 정의에 persist 미들웨어 없음, plain `create()`). 테스트 `useLabelStore.imageAdjust.test.ts` 실존 |
| TC-FE-100 | PASS | [정적] | `activeLabelId` 필드 자체는 단순 setter(store 레벨, `useLabelStore.activeLabel.test.ts` 로 초기값 null/set/reset 검증). 파생 프리셋 기본값 로직(`resolveDefaultLabel`, null 시 sortNo 최소 활성 라벨)은 별도 순수함수 `canvas/layers/resolveDefaultLabel.ts` + 전용 테스트 `resolveDefaultLabel.test.ts` 로 분리 검증 — 두 테스트를 합쳐 기대결과 "activeLabelId → resolveDefaultLabel" 흐름 성립 확인 |
| TC-FE-101 | PASS | [정적] | `useLabelStore.ts:4` `import { clampPan as clampPanByScale } from '.../canvasGeometry'` + store `export function clampPan(...)`(실제 386-406, 카탈로그 319-335 — **드리프트 +67줄**)이 `canvasGeometry.clampPan` 에 위임. `canvasGeometry.test.ts` 의 `clampPan_letterbox축은_중앙유지_pan0`/`clampPan_줌인축은_초과분_절반까지만_허용` + `useLabelStoreView.test.ts` 로 scale 기반 clamp 회귀 검증 |

### H-4 반증 시도 기록 (canvas scale 함정 · Zustand 불변성 중점)

1. **불변성 위반 스캔** — `addLabel`/`updateLabel`/`removeLabel`/`toggleLabelVisibility`/`toggleLabelLock`/`pasteLabels`/`mergeAutoLabels` 전 액션이 **기존 배열/Set 을 직접 mutate 하지 않고 새 배열/Set 을 생성 후 `set()`** 하는 패턴을 소스 레벨로 전수 확인 — `frontend-coding-style.md` 불변성 규칙 위반 0건.
2. **MASK/SEGMENT 타입 좌표이동 갭(참고 관찰, 이슈 미승격)** — `offsetShape`/`clampShape` 는 `types.ts` 의 4개 Shape 중 **BBOX/POLYGON/KEYPOINT 만 명시 분기**하고 `MASK` 타입은 `return {...shape}`(no-op, dx/dy 미적용·clamp 미적용)로 폴백한다. `shapeEquals` 도 MASK 비교 시 `false` 고정이라 붙여넣기 시 중복판정·offset 이 MASK 에는 적용되지 않는다. 다만 **카탈로그 TC-FE-090 기대결과 자체가 "BBOX/POLYGON/KEYPOINT" 3종만 명시**하고 있어 케이스 범위 밖이며, `MASK` shape 는 `api.ts:98`(`return { type: 'MASK' }`, 좌표 없는 placeholder) · `revertSaveEvent` 주석의 "좌표복원 불가(SEGMENT 등)"과 일관되게 **BE SEGMENT 라벨을 FE 가 편집 불가 opaque 값으로 다루는 의도된 설계**로 판단 — 결함으로 승격하지 않음(H-3/캔버스 편집 클러스터에서 SEGMENT 편집 동작이 별도로 검증되면 교차 확인 권장).
3. **busyGeneration 토큰 0 falsy 함정** — `beginBusy` 반환값이 `{ok:false}` 판별 유니온이라 세션 첫 토큰(0)을 `if(!token)` 로 오판하지 않도록 타입 설계된 것을 확인(H-3 범위이나 H-4 파일 내 교차 확인) — 함정 없음.

---

## 3. H-5. 좌표 변환/캔버스 유틸 (canvas scale 함정) (8건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-FE-102 | PASS | [정적] | `coordinateTransformer.ts:43-51` `translateToCanvas` — 이미지중심 회전 후 `*scale+left/top`, 카탈로그 라인 **정확히 일치**. 테스트 `coordinateTransformer.test.ts:37` `translateToCanvas_scale_offset_적용` |
| TC-FE-103 | PASS | [정적] | `translateFromCanvas`(57-65, 카탈로그 57-61 — 함수 종료 라인만 약간 드리프트, 시작 라인 일치) — `(canvasX-left)/scale` 후 `-angle` 역회전으로 정확한 역변환. 테스트 `coordinateTransformer.test.ts:26` 라운드트립 + `:45` **회전 각도 있는 상태 라운드트립**까지 검증(단순 왕복이 아니라 회전 포함 케이스로 canvas scale 함정 반증) |
| TC-FE-104 | PASS | [정적] | `clampToImage`(132-137, 카탈로그 132 — 시작 라인 일치) — `clamp(x,0,image.width)`/`clamp(y,0,image.height)`. 테스트 `coordinateTransformer.test.ts:98-106` 범위내/하한/상한 3케이스 |
| TC-FE-105 | PASS | [정적] | `computeWrappingBox`(106-120, 카탈로그 106 — 일치) — min/max 스캔으로 외접 사각형. 테스트 `:80` 다중점 + `:88` 단일점 |
| TC-FE-106 | PASS | [정적] | `rotate2DPoints`(71-87, 카탈로그 71 — 일치) — `(cx,cy)` 기준 각도 회전. 테스트 `:58` 90도 + `:64` 360도(원위치 복귀) + `:70` 다중점 |
| TC-FE-107 | PASS | [정적] | `maskRleConverter.ts` — `maskToRle`/`rleToMask`(2D mask↔RLE) + `imageDataToRLE`/`rleToImageData`(RGBA alpha↔RLE) 왕복 정합을 `maskRleConverter.test.ts` 8개 describe 블록(단순사각형/CVAT 첫run 규칙/빈mask/1픽셀/imageData/32×32 100회/16×16 1000회 부하/1M 픽셀 상한 보안)으로 광범위 검증. **참고**: 케이스명의 "↔Polygon" 부분은 FE 코드에 없음 — `CLAUDE.md` "CVAT 포팅 전략" 표가 MASK↔RLE↔Polygon 변환의 포팅 대상 언어를 **Java(BE)로 명시**하고 있어(portable-modules/02, Phase 6) FE 는 캔버스 픽셀 편집에 필요한 Mask↔RLE 구간만 구현하는 것이 설계 의도로 판단 — 갭 아님(카탈로그 케이스명이 module 원 출처명을 그대로 물려받아 다소 과잉 표기된 것으로 추정) |
| TC-FE-108 | PASS | [정적] | `trackInterpolation.ts:19-27` `interpolateBBox`(선형보간, t clamp[0,1]) + `:34-58` `interpolateTrack`(구간 탐색 + 범위밖 extrapolation 금지, 가장 가까운 끝값 반환). 테스트 `trackInterpolation.test.ts` 실존(파일 확인, BE `TrackInterpolator` 가 정답이고 FE 는 보조 미리보기 용도임을 파일 상단 주석에서 명시) |
| TC-FE-109 | PASS | [정적] | `LabelingPage.tsx` 프레임 목록 구성부(약 235-259, 카탈로그 242-274 — 실제 `handleImageSize`/`shouldResetView` 연동부는 293-320 부근으로 **드리프트 있음**, 기능은 동일 파일 내 인접 위치) — 주석 "imageWidth/Height 는 하드코딩하지 않는다 — 캔버스가 로드된 이미지의 실측 naturalWidth/Height 를 geometry 기준으로 사용" 명시. `handleImageSize` 콜백이 `shouldResetView(prev,next)`(`stores/useLabelStore.ts:415-422`: videoId 상이 또는 width/height 상이 시 true)로 뷰 리셋 여부 판정 |

### H-5 반증 시도 기록 (canvas scale 함정 중점 — 확증편향 금지 대상)

1. **회전 상태에서의 좌표 왕복 정합** — `translateToCanvas`→`translateFromCanvas` 라운드트립을 `angle=0` 뿐 아니라 **회전각 있는 상태**로도 테스트(`coordinateTransformer.test.ts:45`)가 커버함을 확인. 역변환이 "역순으로 -angle 회전"을 정확히 수행(순변환은 회전→스케일/오프셋, 역변환은 오프셋제거/스케일제거→역회전 순서로 수학적으로 대칭) — 줌/팬/회전 복합 상태에서 좌표 어긋남 없음.
2. **letterbox 축 팬 트랩** — `canvasGeometry.clampPan`(H-4 TC-FE-101 경유 확인)이 `scaled ≤ canvas`(letterbox 축, 즉 캔버스보다 이미지가 작은 축)에서 `pan=0` 강제 — 이미지가 캔버스보다 작은 축에서 팬 여백이 생기는 함정을 원천 차단. 반대로 확대(zoom>1)축은 `|pan| ≤ (scaled-canvas)/2` 로 이미지가 캔버스를 항상 덮도록 제한 — 두 축을 **동일 함수에서 분기**해 한쪽만 처리되는 함정 없음.
3. **0-division/NaN 가드** — `buildGeometry`(`canvasGeometry.ts:49-51`)가 이미지 실측 크기 미확정(로드 전 0/음수)일 때 `scale=0` 폴백을 반환(NaN 전파 차단), 테스트 `이미지_미로드_0크기_가드_NaN없이_scale0_반환` 확인.
4. **clampToImage 가 회전을 무시하는지** — `clampToImage` 는 이미지 좌표계(회전 역변환 이후)에서만 동작하도록 설계돼 있어 캔버스 좌표를 직접 clamp 하지 않음(역변환 후 좌표에 적용하는 게 올바른 사용법) — 호출부(`LabelingPage.tsx` 등)가 이 계약을 지키는지는 H-3(캔버스 드로잉 도구) 범위라 이번 파트에서는 유틸 자체의 정확성만 확인.

---

## 4. 근거(file:line) 드리프트 요약 — 카탈로그 정합성 결함(기능 결함 아님)

| TC-ID | 카탈로그 근거 | 실제 위치 | 드리프트 |
|---|---|---|---|
| TC-FE-088 | `stores/useLabelStore.ts:401` | `:486-495` | +85줄 |
| TC-FE-089 | `stores/useLabelStore.ts:412` | `:497-503` | +85줄 |
| TC-FE-101 | `stores/useLabelStore.ts:319-335` | `:386-406` | +67줄 |
| TC-FE-109 | `pages/label/LabelingPage.tsx:242-274` | 프레임 구성부 235-259 / `handleImageSize` 실제 로직 293-320대 | 기능은 동일, 정확 라인 재확인 필요 |

원인 추정: 파일 상단부(busy 상태 관련 타입·필드·주석 블록 등)에 코드가 추가되며 하단 함수들의 라인이 일괄 밀린 것으로 보임(TC-FE-088/089 드리프트량이 +85줄로 동일). TC-FE-090/091/092~101 등 파일 앞쪽(순수 헬퍼 함수)이나 뒤쪽(액션 정의부이지만 088/089 이후 위치)은 오히려 일치하거나 드리프트가 없어, **088/089 두 건만 국소적으로 어긋남**.

---

## 5. 집계

| 구분 | 건수 |
|---|---:|
| 대상 케이스 | 33 |
| PASS | 33 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| 신규 이슈(H-ISSUE-21~40) | **0건 — 발견된 결함 없음** |

## 6. 결론

H-2(라우터/코드스플리팅)·H-4(useLabelStore)·H-5(좌표변환/캔버스 유틸) 33건 전건 **PASS**. 확증편향 금지 대상으로 지정된 3개 함정(canvas scale 좌표변환, 코드스플리팅 lazy load 실패 처리, Zustand 불변성)을 반증 관점에서 재확인했으나 결함을 찾지 못함:
- **canvas scale 함정**: 회전 포함 라운드트립 테스트, letterbox/줌인 축 분기 clamp, 0-division 가드가 모두 유닛테스트로 뒷받침됨.
- **코드스플리팅 lazy load 실패**: `lazyWithRetry` 가 재시도→1회성 reload→플래그 리셋의 3단 방어를 구현(전용 자동테스트는 없어 코드 리딩으로만 검증).
- **Zustand 불변성**: 전 액션이 새 배열/Set 생성 패턴 준수. MASK/SEGMENT 타입 좌표이동 미지원은 카탈로그 케이스 범위 밖이자 의도된 설계로 판단해 이슈 미승격(§H-4 반증 시도 기록 2번 참고).

근거 드리프트 4건(§4)은 카탈로그 정합성 문제로, 다음 카탈로그 갱신 시 라인 번호 재동기화 권장.
# H-3. LabelingPage (라벨링 캔버스) — 검증 결과 (2026-08-02 / 2차)

> 대상: `docs/test-cases/H-frontend-e2e.md` §H-3 **71건**(TC-FE-033~087 · 197~202 · 261~270)
> 환경: frontend `localhost:13000` · backend `localhost:18081/api` · DB `public` 스키마 · mock-server 경유(스택 기동 상태 실측 200)
> 방식: **Playwright(Chromium) 실브라우저 조작 최우선** — dev 토큰(`POST /v1/dev/tokens`, WORKER `sub=2001`)을 `sessionStorage['klid_jwt']` 에 주입 후 `/label/{srcSn}` 진입.
> 대상 데이터: `raw_sn=4`(WORKER 2001 배정, 프레임 30장, `src_sn 1~30`, `DE_IDENT_YN='Y'`, 작업상태 COMPLETED=APPROVED). 파생/RAW/잠금 등 **실데이터로 못 만드는 전제는 `page.route` 로 API 응답만 패치**해 FE 분기를 실동작 판정했다(코드·설정·테스트 파일 무수정).
> 판정 토큰: PASS / FAIL / PARTIAL / BLOCKED / N/A / 확인필요 — 근거확인 컬럼에 `[실동작]` / `[정적]` 접두.

## 0. 요약

| 판정 | 건수 |
|------|---:|
| PASS | 68 |
| FAIL | 1 |
| PARTIAL | 1 |
| 확인필요 | 1 |
| **계** | **71** |

**핵심 결함 3건**
1. **H-ISSUE-41 (HIGH · FAIL)** — `getLabels()` 가 BE 응답의 `labelVersion` 을 반환 객체에 **매핑하지 않아**, 라벨 저장 PUT 이 **낙관적 동시성 토큰을 한 번도 싣지 않는다**(실측 PUT body 3건 전부 `labelVersion` 부재). C-ISSUE-21 이 막으려던 **full-replace lost update 가 그대로 성립**한다. 단위테스트는 `useLabels` 응답을 목으로 주입해 통과 중이라 회귀 감지가 안 된다(A 클러스터의 "로그 마스킹 미배선"과 동일 계열).
2. **H-ISSUE-43 (확인필요)** — 폴리곤 도구에서 **마우스 클릭으로 점이 추가되지 않는다**(3~4회 클릭 + dblclick/Q 모두 라벨 0건, 드래프트 정점 렌더도 없음). 같은 화면에서 **F(점 추가)/Q(완성) 키보드 경로는 정상 커밋**되고 BBOX 드래그·라벨 선택 클릭도 정상이라, 도구별 클릭 경로만 어긋난 것으로 보인다. CDP 합성 클릭 특유의 아티팩트 가능성이 남아 **실사용자 마우스 재현이 필요**.
3. **H-ISSUE-44 (LOW)** — H-3 절 **근거 `file:line` 이 사실상 전건 드리프트**(`LabelingPage.tsx` 가 1,609줄로 늘며 대부분 +60~160줄 이동). 카탈로그 자체의 정합성 결함.

**★ 프롬프트 지정 확증편향 점검 결과**
- **비식별 프레임 서빙**: 라벨링 캔버스가 `GET /v1/frames/{srcSn}/image` 로 받은 이미지에 **`MOCK 비식별` 워터마크가 찍힌 비식별본**이 렌더되고 헤더 뱃지도 `DEID` — 확정정책 성립(스크린샷 `.playwright-mcp/h3-canvas.png`).
- **REVIEWER `raw=true` 동선 FE 부재**: `useImageBlob(srcSn, {raw})` 옵션은 존재하나 **프로덕션 호출부 3곳(`LabelingPage` · `DarkFrameStrip` · `FrameTimeline` · `review/LabelCanvas`) 어디도 `raw:true` 를 넘기지 않는다**. `raw: true` 리터럴은 **테스트 파일에만** 존재(`__tests__/useImageBlob.test.tsx:86`, `api.test.ts:464`). CLAUDE.md "REVIEWER 원본 열람 동선을 FE 에 두지 않는다" **성립(PASS)**.
- BBOX 그리기·undo/redo·저장/불러오기·AI 탐지/분할/추적 트리거는 **모두 실동작으로 확인**했고, 실패는 위 3건에 국한된다.

---

## 1. 판정표

| ID | 판정 | 근거 확인 | 실측 근거 / 비고 |
|----|:--:|------|------|
| TC-FE-033 | PASS | [실동작] | `/label/abc` → `잘못된 프레임 ID` + `뒤로 가기`. 실제 위치 `LabelingPage.tsx:1067-1086`(카탈로그 911-930 드리프트) |
| TC-FE-034 | PASS | [정적] | `:1088-1101` `<Spinner label="라벨 로딩" />` + "라벨 로딩 중..." (드리프트 932-945) |
| TC-FE-035 | PASS | [정적] | `:1106-1133` `portalMode && (status===403 \|\| errorCode==='FORBIDDEN')` → `data-testid="portal-forbidden-screen"` + "접근할 수 없는 영상입니다" (드리프트 950-977) |
| TC-FE-036 | PASS | [실동작] | `/label/999999` → `라벨 조회 실패` + **BE 문구 "프레임을 찾을 수 없습니다."** + 뒤로가기. `:1135-1155` |
| TC-FE-037 | PASS | [정적] | `:233-248` `siblings.length===0` → 현재 프레임 1건 폴백 (드리프트 202-219) |
| TC-FE-038 | PASS | [실동작] | 프레임4 BBOX 1건 그린 뒤 Ctrl+S → 토스트 `성공: 저장됨`, 헤더 `● 편집 중`→`✓ 저장됨`. PUT `{"items":[{...}]}` 200 |
| TC-FE-039 | PASS | [실동작] | PUT 3초 지연 라우트 + Ctrl+S **3연타** → 실제 PUT **1회**, 토스트 1건. `:560 if (saving) return;` + busy 배타 |
| TC-FE-040 | PASS | [실동작] | `lockSttsCd='LOCKED_FOR_REDEIDENT'` 패치 → 저장 버튼 `disabled=true`, Ctrl+S → `오류: 비식별 재처리 중인 영상은 저장할 수 없습니다.`, PUT 미발생. `:562-568` |
| TC-FE-041 | PASS | [실동작] | PUT 500(`{success:false,data:null,message:'BE-특정-저장실패-문구'}`) → 토스트 `오류: BE-특정-저장실패-문구`(=`extractBeMessage`). dirty 유지 |
| TC-FE-042 | PASS | [정적] | `:550-555` `updateLabels = portalMode ? savePortalLabels : updateInternalLabels` (드리프트 486-491) |
| TC-FE-043 | PASS | [실동작] | dirty 1건 상태에서 썸네일 클릭 → `저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이 있습니다. 프레임을 이동하기 전에… / 취소·저장 안 함·저장 후 이동`, URL 미변경 |
| TC-FE-044 | PASS | [정적] | `:343 if (target.srcSn === data.srcSn) return;` |
| TC-FE-045 | PASS | [정적] | `:351-379` — `updateLabels` 예외 시 에러 토스트 + `setNavGuardTarget(null)`(이동 취소), `saved===null` 이면 이동 안 함 |
| TC-FE-046 | PASS | [실동작] | `저장 안 함` → `/label/1`→`/label/4` 이동, 객체 `2개`→`0개`(dirty 폐기 + 새 프레임 라벨), 헤더 `✓ 저장됨` |
| TC-FE-047 | PASS | [실동작] | 프레임 전환 시 서버 라벨로 전체 교체(`0개 객체`) + dirty 초기화. `:411-424` |
| TC-FE-048 | PASS | [정적] | `:420-423` 같은 프레임 재조회는 `getState().dirtyLabels.size===0` 일 때만 `setLabels` |
| TC-FE-049 | PASS | [정적] | `:430-443` drain→`mergeAutoLabels`→토스트 **"보류된 AI 추적 N건 적용됨"**(문구 일치) |
| TC-FE-050 | PASS | [정적] | `:446-450` unmount cleanup `reset()` |
| TC-FE-051 | PASS | [실동작] | AI Tool 팝업: `person/car/bicycle/motorbike/bus/truck` 선택 가능, `fire/smoke/water/c2a-race-lbl` **checkbox disabled + "미매핑"** 표기, 실행 버튼 라벨 `전체 (6종)`, `일반`/`트랙` 활성 |
| TC-FE-052 | PASS | [실동작] | 응답 `message:'모델이 로드되지 않아 mock 응답입니다'` → `경고:` 토스트만, 객체 `1개→1개`(병합 0) |
| TC-FE-053 | PASS | [실동작] | 정상 응답 2건 → `성공: AI 탐지 2건 적용됨`, 객체 `1개→3개` |
| TC-FE-054 | PASS | [실동작] | shape=POLYGON 실행 → `성공: AI 분할 1건 적용됨` (`BUSY_KIND_NAME` 단일 소스 파생) |
| TC-FE-055 | PASS | [실동작] | 팝업 `트랙` → 도구 `AI 추적` 활성 + `안내: 추적할 객체를 선택한 뒤 속성 패널에서 자동추적을 실행하세요.` |
| TC-FE-056 | PASS | [정적] | `:761-804` 현재=`mergeAutoLabels` 즉시, 미래=`stashPendingTracks` (드리프트 619-660) |
| TC-FE-057 | PASS | [정적] | `:785-790` `${applied}/${total} 프레임만 추적됨 (일부 실패)` warning |
| TC-FE-058 | PASS | [정적] | `:751-754` `frames.slice(frameIdx+1)` |
| TC-FE-059 | PASS | [정적] | `api.ts:705-708` `SAM2_TRACK_CHUNK_SIZE` 단위 `slice(i, i+SIZE)` 순차 — BE `@Size(max=50)` 정합 |
| TC-FE-060 | PASS | [정적] | `useSam2Track.ts:54-58,96-110` — `requestedSrcSnRef`(실행 시점 프레임) + `runExclusiveOrNotify(ctx.srcSn)` 의 `isStaleScope` 로 폐기 (카탈로그 57-59 드리프트) |
| TC-FE-061 | PASS | [정적] | `useSam2Track.ts:83-88` `Sam2TrackChunkError.partial.length>0 → onTracked(partial, true)` |
| TC-FE-062 | PASS | [정적] | `api.ts:628-647 toSeedPolygon` 2점→4점 외접박스(+비유한 좌표 warn) |
| TC-FE-063 | PASS | [정적] | `:856 / :878 / :903` 각 핸들러 `if (portalMode) return;` (드리프트 721/745/769) |
| TC-FE-064 | PASS | [정적] | `:859 / :881 / :906` `isLocked` 에러 토스트 + 미실행 |
| TC-FE-065 | PASS | [정적] | `:864-866` `mergeTracks` → `LABEL_KEYS.byVideo(rawSn)` invalidate + 성공 토스트 |
| TC-FE-066 | PASS | [정적] | `:527-535` `setReportedLock(true)` + `reset()` + `LABEL_KEYS.byVideo(srcSn)` invalidate. 412 재조회 화면은 `:1135-1155`(에러 문구=BE message) 와 `frameImageErrorHint` 412 분기(`:161`)로 성립. ⚠ 실제 신고 접수는 **DB 작업락이 걸려 타 클러스터 검증을 오염**시키므로 미수행 |
| TC-FE-067 | PASS | [실동작] | 잠금 패치 → `data-testid="deident-locked-banner"` `role=status` "비식별 재처리 중인 영상입니다…" 노출 |
| TC-FE-068 | PASS | [실동작] | `frameImageType='RAW'` 패치 → 신고 버튼 `disabled=true`(잠금·busy 아님) |
| TC-FE-069 | PASS | [정적] | `:128 canReportDeident = !portalMode && (isWorker\|\|isReviewer)`, `:1187-1198` false 면 `null` |
| TC-FE-070 | PASS | [실동작] | WORKER 진입 시 `submit-review-button` 렌더(`:1199-1200 isWorker && data`) |
| TC-FE-071 | PASS | [정적] | `:193-212` `SUBMITTABLE_STATUSES=['ASSIGNED','REJECTED','COMPLETED','APPROVED']`, 그 외 `disabled` + `title=submitStatusHint`(REVIEW_PENDING/REVIEWING 별 문구) |
| TC-FE-072 | PASS | [실동작] | 대상 영상 작업상태 COMPLETED → 버튼 문구 **"재검수 제출"**(`:201-202`) |
| TC-FE-073 | PASS | [정적] | `:199 canCancelSubmit = isWorker && workStatus==='REVIEW_PENDING'`, `:1202` 조건부 렌더. 실동작에서도 COMPLETED 상태라 **미노출**(조건 일치) |
| TC-FE-074 | PASS | [정적] | `:167-173` onSuccess → `'검수 제출 완료'` + `navigate('/task')`. ⚠ 실제 제출은 검수 워크플로 상태를 바꿔 미수행 |
| TC-FE-075 | PASS | [실동작] | dirty 상태 X 클릭 → 3옵션 모달(`label-close-cancel/discard/save`), "저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이…" |
| TC-FE-076 | PASS | [정적] | `:983-992` `dirtyCount>0` 일 때만 `beforeunload` 등록 + `preventDefault`/`returnValue=''` |
| TC-FE-077 | PASS | [실동작] | INTERNAL 진입 시 `객체/메타/이슈` 3탭 렌더 + `label-issue-panel` 존재. portalMode 게이트 `:473-476`(`showMeta=!portalMode`, `showIssues=!portalMode && videoId!==undefined`) |
| TC-FE-078 | PASS | [정적] | `:1434-1442` `unresolvedInquiries>0` 시 `issue-tab-badge` + `aria-label="미해소 문의 N건"`. 실동작 대상 영상은 미해소 문의 0 → 배지 없음(조건 일치) |
| TC-FE-079 | PASS | [실동작] | 메타 탭 = **촬영환경 / 개인정보 / 프레임 설명 / 시계열 메타(검토 상태 승인됨) / 이벤트 어노테이션** 5패널 전부 렌더(`:1467-1475`) |
| TC-FE-080 | PASS | [정적] | `:294-317` `viewKeyRef`(videoId+실측 dims) + `shouldResetView` — 동일 영상·동일 해상도면 `resetView()` 미호출 |
| TC-FE-081 | PASS | [실동작] | Ctrl+Shift+C → `라벨 1건 복사됨`, Ctrl+V → `라벨 1건 붙여넣음`(객체 2개). clamp 는 `:1036-1038 imageWidth/Height = frameNaturalSize?.*` |
| TC-FE-082 | PASS | [정적] | `:1018-1025` `n===0` → warning `복사할 라벨이 없습니다.` (성공 경로는 실동작 확인) |
| TC-FE-083 | PASS | [정적] | `:1027-1031` `isLocked` → 에러 토스트 + `return`(붙여넣기 미실행) |
| TC-FE-084 | PASS | [정적] | `:643-645 handleRevertRequest` → `revertTarget` → `:1574-1582 ConfirmDialog("이 저장으로 되돌리기")` |
| TC-FE-085 | PASS | [정적] | `:667-670` `reverted===0` → warning. ⚠ **문구 드리프트**: 실제는 `되돌릴 항목이 현재 작업본에 없습니다.`(카탈로그 "되돌릴 항목이 없습니다") |
| TC-FE-086 | PASS | [실동작] | `:87-89` `lazy(() => import('.../CanvasShell'))` + `:1331-1353` Suspense fallback `캔버스 로딩`. 실브라우저에서 konva 캔버스 3레이어(1280×720) 마운트 확인 |
| TC-FE-087 | PASS | [실동작] | 히스토리 토글 → `inline-history-panel` ("히스토리 / 변경 이력 / 버전 0 / 라벨 변경 이력…"), `historyOpen && !portalMode && srcSn!==undefined`(`:1546-1558`) |
| TC-FE-197 | PASS | [실동작] | PUT 409 → 토스트 아님, **ConfirmDialog "다른 사용자가 먼저 저장했습니다" + BE 문구 그대로 + `내 작업 유지`/`최신 라벨 불러오기`**. 객체 `2개` 유지 + 헤더 `● 편집 중`(dirty 미폐기) |
| TC-FE-198 | **FAIL** | [실동작] | **PUT body 에 `labelVersion` 이 실리지 않는다.** 실측 3회 전부 `{"items":[…]}` 뿐. 원인: `api.ts:196-229 getLabels()` 반환 객체에 `labelVersion` 미포함(BE 는 응답에 포함 — 실측 `labelVersion:2`). → **H-ISSUE-41** |
| TC-FE-199 | PARTIAL | [실동작] | `useUpdateLabels.ts:60-70` 캐시 우선 로직은 존재하고 2회 연속 저장이 409 를 내지 않는 것도 실측 확인. 그러나 **409 가 안 나는 이유가 "최신 버전 전송"이 아니라 "버전 자체 미전송(BE 검사 skip)"** 이다(TC-FE-198). 기대결과의 보호 효과는 미성립 → **H-ISSUE-42** |
| TC-FE-200 | PASS | [실동작] | 영상상세 `derivative=true` 패치 → 신고 버튼 `disabled=true`, `title`/`aria-label` = **"증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다."** — **부모 rawSn·원본 유도 문구 없음**(확정정책 일치) |
| TC-FE-201 | PASS | [정적] | `DeidentReportButton.tsx:109-116` status 412 → `resolveApiMessage(e, …)` 로 **서버 안내문**을 폼 내 `role=alert` 에 표시. `errors.ts:37,47` 412→`PRECONDITION_FAILED` 매핑 존재 |
| TC-FE-202 | PASS | [정적] | `api/eventAnnotation.ts:26-29 normalizeCot` — 배열은 그대로, 객체는 `Object.values`(JSON 키 순서 유지) |
| TC-FE-261 | PASS | [실동작] | AI 탐지 7초 지연 중: 저장/신고/검수제출 버튼 `disabled=true`, **캔버스 드래그해도 객체 수 불변(1개)**, 오버레이 백드롭이 포인터 흡수 |
| TC-FE-262 | PASS | [실동작+정적] | 신고 버튼 disabled 실측(`:1193 disabled={isLocked \|\| isEditBlocked \|\| RAW}`). 되돌리기 `:655 isEditBlockedNow` 이중 방어, 롤백/신고 경로 동일 |
| TC-FE-263 | PASS | [실동작] | busy 중 `B` keydown → 활성 도구 `선택` 유지(전환 안 됨). `useLabelingShortcuts.ts:216 blocked \|\| isEditBlockedNow()`, `:247 if (editBlocked) continue` |
| TC-FE-264 | PASS | [실동작] | 실행 **150ms 시점 오버레이 없음**, 1.5초 시점 `AI 탐지 진행 중 / 1초 경과 / 작업 취소 / 취소하면 결과를 반영하지 않고…` — **모델명·식별자·경로 없음**. `busyPolicy.ts:69 BUSY_OVERLAY_DELAY_MS=300` |
| TC-FE-265 | PASS | [실동작] | `busy-overlay-cancel` 클릭 → 오버레이 즉시 해제 + 저장 버튼 재활성. **7초 뒤 도착한 성공 응답이 반영되지 않음**(객체 1개 유지, 성공 토스트 없음) = 세대 토큰 폐기 |
| TC-FE-266 | PASS | [정적] | `OverlayLayer.tsx:357-381` ESC → `setPendingConfirm(false)` + `handleBusyEscape()`(누적점 보존). 전용 테스트 `canvas/layers/__tests__/OverlayLayerSegmentBusy.test.tsx` |
| TC-FE-267 | PASS | [정적] | `useBusyTask.ts:13 BUSY_MAX_DURATION_MS=5*60*1000`, `:145-151` 토큰 생존 시에만 `cancelBusy()` + warn |
| TC-FE-268 | PASS | [정적] | 메타 5패널은 `EnvironmentMetaPanel`/`FramePrivacyMetaPanel`/`FrameDescriptionPanel`/`TimeseriesSidePanel`/`EventAnnotationPanel` 로 라벨 작업본과 상태 공유 없음. 회귀 가드 `__tests__/metaEditBusyIndependence.test.tsx` 존재 |
| TC-FE-269 | PASS | [정적] | `CanvasShell.tsx:71-104` `KEYBOARD_ACTIVATABLE_TAGS/ROLES` + `isKeyboardActivatableTarget` → 버튼 포커스 시 Space 팬 미발동·preventDefault 미적용. 테스트 `canvas/__tests__/CanvasShellSpaceActivation.test.tsx` |
| TC-FE-270 | PASS | [정적+실동작] | FE busy 는 같은 탭 한정(`useLabelStore` 메모리). 교차 수정은 `:583-588` 409 → 충돌 다이얼로그(TC-FE-197 실증)가 담당 |

---

## 2. 이슈

### [H-ISSUE-41] TC-FE-198 — 라벨 저장 PUT 이 낙관적 동시성 토큰(`labelVersion`)을 전혀 싣지 않아 lost update 가 그대로 성립
- **심각도**: HIGH
- **기대 동작(기대효과)**: 조회 응답의 `labelVersion` 을 저장 요청 body 에 되돌려 보내야, 그사이 다른 사용자가 같은 프레임을 저장했을 때 BE 가 409 로 거부한다. 라벨 저장이 **full-replace 계약**이므로 이 토큰이 없으면 "내 화면에 없던 남의 라벨"이 조용히 전량 삭제된다(C-ISSUE-21 이 막으려던 실측 결함).
- **현재 동작(이슈 내용)**: BE 는 `GET /v1/frames/{srcSn}/labels` 응답에 `labelVersion` 을 내려주지만(실측 `{"srcSn":4,…,"labelVersion":2,…}`), FE 변환 함수가 이 필드를 **반환 객체에 매핑하지 않는다**.
  ```ts
  // frontend/src/features/label/api.ts:216-228  getLabels()
  return {
    frameNo: d.frameNo ?? 0,
    srcSn,
    videoId: …,
    frameImageType,
    lockSttsCd,
    siblings,
    labels: rawList.map(normalizeLabel),
  };          // ← labelVersion 없음 (types.ts:263-269 는 필드를 선언하고 있다)
  ```
  결과적으로 `LabelingPage.tsx:545 { labelVersion: data?.labelVersion }` 는 항상 `undefined` 이고, `useUpdateLabels.ts:63-65` 의 캐시 폴백(`qc.getQueryData(internalKey)?.labelVersion`)도 같은 변환 결과를 읽으므로 `undefined` 다. `putLabels(…, undefined)` 는 `api.ts:304 ...(labelVersion != null ? { labelVersion } : {})` 로 필드를 생략 → **BE 하위호환 경로(검사 skip)** 로 떨어진다.
  저장 직후 `setQueryData` 가 PUT 응답(원본 JSON, `labelVersion` 포함)을 병합해 잠깐 캐시에 값이 생기지만, 같은 블록의 `invalidateQueries` 재조회가 다시 `getLabels()` 를 태워 값을 **덮어 지운다**.
- **재현/확인 경로**:
  1. 브라우저: `/label/4` 진입 → BBOX 1건 그리기 → Ctrl+S. DevTools Network 의 `PUT /api/v1/frames/4/labels` request payload 확인 → `{"items":[…]}` (labelVersion 없음). 2.5초 후 재저장해도 동일.
  2. 서버 측 확인:
     ```bash
     curl -s -H "Authorization: Bearer $T" http://localhost:18081/api/v1/frames/4/labels | jq '.data.labelVersion'   # → 2
     curl -s -X PUT -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
          -d '{"items":[]}' http://localhost:18081/api/v1/frames/4/labels                                            # → 200 (stale 여부와 무관하게 통과)
     ```
- **영향**: 데이터 정합 — 같은 프레임을 두 작업자(또는 두 탭)가 편집하면 **나중 저장이 앞 저장 결과를 경고 없이 전량 삭제**한다(CWE-362 계열, 라벨 유실). 부수적으로 TC-FE-197 의 409 충돌 다이얼로그와 TC-FE-199 의 캐시 우선 로직이 **실사용에서 도달 불가능한 죽은 경로**가 된다. 단위테스트(`api.test.ts:401-433`, `useUpdateLabels.test.tsx:98-`)는 `putLabels`/`useUpdateLabels` 에 버전을 **직접 주입**해 검증하므로 이 배선 단절을 잡지 못한다.
- **수정 방향(제안)**: `api.ts` `getLabels()` 반환 객체에 `labelVersion: typeof d.labelVersion === 'number' ? d.labelVersion : null` 을 추가한다(응답에 없으면 null → 기존 하위호환 유지). 회귀 가드로 `getLabels` 가 응답의 `labelVersion` 을 그대로 노출하는지 검증하는 테스트를 추가하고, 가능하면 "저장 PUT 이 조회에서 받은 버전을 실제로 싣는지"를 화면 레벨(`LabelingPage`)에서 확인하는 통합 테스트를 둔다. ⚠ 구현은 하지 않았다.

### [H-ISSUE-42] TC-FE-199 — 연속 저장 409 미발생이 "최신 버전 전송"이 아니라 "버전 미전송"으로 성립
- **심각도**: MEDIUM (H-ISSUE-41 의 파생)
- **기대 동작(기대효과)**: 1회차 저장 성공 직후 2회차 저장이 **응답으로 갱신된 최신 `labelVersion`** 을 보내 자기 자신과 409 가 나지 않아야 한다(DEV_FIX H12).
- **현재 동작(이슈 내용)**: 실측상 연속 저장은 409 없이 둘 다 성공하지만, 두 PUT 모두 `labelVersion` 자체가 없다.
  ```
  PUT /api/v1/frames/4/labels  {"items":[{"id":null,…}]}     ← 1회차
  PUT /api/v1/frames/4/labels  {"items":[{"id":726,…}]}      ← 2회차 (labelVersion 여전히 없음)
  ```
  `useUpdateLabels.ts:63-65` 의 `cached?.labelVersion ?? options.labelVersion` 은 두 소스가 모두 `undefined` 라 동작할 여지가 없다.
- **재현/확인 경로**: H-ISSUE-41 재현 1과 동일(연속 2회 Ctrl+S 후 두 payload 비교).
- **영향**: 케이스가 "통과처럼 보이지만 보호 효과는 0"인 상태다. H-ISSUE-41 을 고치면 이 로직이 비로소 실효를 갖는데, 그때 **캐시 우선 순서가 실제로 맞는지**는 아직 실환경 미검증이므로 수정 후 재검증 대상이다.
- **수정 방향(제안)**: H-ISSUE-41 수정 후, 연속 저장 2회의 payload `labelVersion` 이 `v` → `v+1` 로 증가하는지 실브라우저로 재검증한다(수정 없이는 판정 불가).

### [H-ISSUE-43] (H-3 케이스 미할당 — 캔버스 도구 실동작) 폴리곤 도구에서 마우스 클릭으로 점이 추가되지 않는다
- **심각도**: HIGH (확정 시) / 현재 판정 **확인필요**
- **기대 동작(기대효과)**: 폴리곤 도구 선택 후 캔버스를 클릭하면 점이 추가되고, dblclick 또는 시작점 근접으로 닫혀 POLYGON 라벨이 커밋되어야 한다(`OverlayLayer.tsx:107` 주석 "클릭으로 점 추가, dblclick 또는 시작점 근접 시 닫기"). 라벨링 도구의 핵심 입력 경로다.
- **현재 동작(이슈 내용)**: Chromium(Playwright) 실브라우저에서 폴리곤 도구 활성 후 캔버스 3~4회 클릭 → **드래프트 정점이 렌더되지 않고**(스크린샷 `.playwright-mcp/h3-poly-draft.png`), 이어진 `dblclick` 및 `Q` 모두 라벨 0건. 활성 라벨(`1` 키)을 먼저 선택한 경우와 아닌 경우 **모두 동일**했고, 오류 토스트도 없다(무음 실패).
  같은 페이지·같은 좌표에서:
  - `F`(점 추가) 3회 + `Q`(완성) → **POLYGON 라벨 정상 커밋**(`사람 #2 … POLYGON`)
  - BBOX 드래그(mousedown→mousemove→mouseup) → 정상 커밋
  - 선택 도구로 라벨 클릭 → 정상 선택(속성 패널에 `#tmp-…`, `형태 BBOX`) → **Konva click 자체는 발화한다**
  즉 `captureRect` 의 `onMouseDown/Move/Up`·`onDblClick` 은 발화하는데 **`onClick`(`OverlayLayer.tsx:869-905` 의 폴리곤 분기)만 도달하지 않는 것으로 보인다.
- **재현/확인 경로**: `/label/1` 진입 → 툴바 `폴리곤`(또는 `P`) → 캔버스 3회 클릭 → 정점 표시 여부 확인 → `Q` 또는 dblclick → 객체 수 변화 확인. 대조군으로 마우스 이동 후 `F` 3회 + `Q` 를 수행하면 정상 생성된다.
- **영향**: 사실이면 **마우스만 쓰는 작업자가 폴리곤/세그멘테이션을 그릴 수 없다**(SFR-08 라벨링 핵심 기능). 같은 `onClick` 경로를 쓰는 **KEYPOINT 17점 순차 배치**와 **AI 분할 클릭 프롬프트 누적**도 동일하게 영향받을 수 있다. 다만 CDP 합성 클릭과 Konva 의 click 합성(`pointerdown/pointerup` → `click`) 간 상호작용 아티팩트일 가능성이 남아 있어 **결함 확정 전 실사용자 마우스 재현이 필요**하다.
- **수정 방향(제안)**: ① 먼저 실제 마우스로 재현 여부 확정(재현 안 되면 본 이슈는 하네스 아티팩트로 종결). ② 재현되면 `OverlayLayer.tsx:869-905` 의 `onClick` 분기에 도달하는지(특히 `pointerCanvas()` 가 mouseup 시점에 null 을 반환하는지) 진단하고, 필요 시 폴리곤 점 추가를 `onMouseUp`(이동량 임계값 기반) 경로로 옮겨 BBOX/SAM2 와 이벤트 축을 통일한다. ③ 어느 쪽이든 **커밋 실패 시 무음이 되지 않도록** `commitPolygon` 실패·점 부족 상황의 사용자 안내를 보강한다. ⚠ 구현은 하지 않았다.

### [H-ISSUE-44] (카탈로그 정합성) H-3 절 근거 `file:line` 전건 드리프트 + 기대 문구 1건 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 근거 `file:line` 이 실제 코드 위치를 가리켜야 다음 회차 검증·수정 작업이 곧바로 착지한다.
- **현재 동작(이슈 내용)**: `LabelingPage.tsx` 가 **1,609줄**로 늘면서 H-3 의 `pages/label/LabelingPage.tsx:*` 근거가 사실상 전건 어긋났다. 대표 예:
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | TC-FE-033 | 911-930 | **1067-1086** |
  | TC-FE-038 | 492-510 | **556-578** |
  | TC-FE-043 | 292-302 | **337-349** |
  | TC-FE-052 | 698-701 | **692-695** |
  | TC-FE-055 | 673-690 | **815-833** |
  | TC-FE-066 | 465-473 | **527-535** |
  | TC-FE-067 | 1079-1090 | **1238-1247** |
  | TC-FE-079 | 1290-1310 | **1467-1475** |
  | TC-FE-197 | 515-519,533-538,1385-1394 | **583-588, 601-640, 1562-1571** |
  | TC-FE-060 | useSam2Track.ts:57-59 | **useSam2Track.ts:96-110** |
  또 TC-FE-085 의 기대 문구가 `되돌릴 항목이 없습니다` 인데 실제 구현은 `되돌릴 항목이 현재 작업본에 없습니다.`(`LabelingPage.tsx:668`)다.
- **재현/확인 경로**: `grep -n` 으로 위 표의 심볼(`잘못된 프레임 ID`, `handleSave`, `requestJumpTo`, `handleDeidentReportSuccess` 등) 위치 확인.
- **영향**: 판정 자체는 바뀌지 않으나, 근거를 따라가면 무관한 코드가 나와 재검증·수정 착수 비용이 늘고 오판 위험이 생긴다.
- **수정 방향(제안)**: H-3 절 근거를 위 실측 위치로 일괄 갱신하고 TC-FE-085 기대 문구를 실제 구현 문구로 정정한다. 라인 대신 **심볼/함수명 기준 근거 표기**로 바꾸면 드리프트가 재발하지 않는다. ⚠ 카탈로그는 수정하지 않았다.

---

## 3. 검증 방법 메모 (재현용)

- 토큰: `POST /api/v1/dev/tokens {"role":"WORKER","channel":"INTERNAL"}` → `data.token` 을 `sessionStorage.setItem('klid_jwt', …)`.
- 브라우저 프로파일을 다른 검증 에이전트와 공유해 탭이 탈취되므로, **모든 조작을 단일 `browser_run_code_unsafe` 호출 안에서 `context.newPage()` 로 격리**해 수행했다.
- 전제 조작(잠금/RAW/파생/409/500/지연)은 `page.route` 로 **응답만 패치**했고 프로덕션·테스트·설정 파일은 일절 수정하지 않았다.
- DB 부작용: 검증 중 `src_sn=4` 에 BBOX 1건을 저장했다가 **삭제 후 재저장해 라벨 0건으로 원복**했다(`LS_DATA_LBL` 잔여 없음). `labelVersion` 만 증가했다(무해).
- 스크린샷: `.playwright-mcp/h3-canvas.png`(비식별 프레임 렌더), `h3-poly-draft.png`(폴리곤 클릭 무반응), `h3-bbox-drawn.png`, `h3-select-click.png`.
# H-part4 — H-6. MarkingPage(20건) + H-7. BatchStageIndicator(5건) = 25건

> 검증일 2026-08-02 · 회차 2차 · 담당 범위: `docs/test-cases/H-frontend-e2e.md` §H-6 `TC-FE-110~125,203~205,TC-E2E-003` · §H-7 `TC-FE-126~130`
> 대상 코드: `frontend/src/pages/MarkingPage.tsx` · `frontend/src/features/marking/**` · `frontend/src/components/common/BatchStageIndicator.tsx` · `frontend/src/features/video/types.ts`
> 실행 스택: frontend(:13000, 실행중) · backend(:18081/api, 실행중, local profile) · PostgreSQL(`klid-postgres` docker, DB=`klid_system`, 스키마 `public`)
> ⚠ 코드/설정/테스트 파일 수정 없음, 빌드/테스트 실행 없음 (Read/Grep/DB조회(SELECT만)/Playwright 조작만 수행)

## 0. 검증 방법 메모

- **실동작**: Playwright 로 `/dev/login`(WORKER 프리셋) → `/marking/69`(DB 조회로 선정: `de_ident_yn='Y'`, `data_stts_cd='MARKING_READY'`, `evnt_type_cd` 존재, 미마킹) 진입 성공 확인. 화면 렌더(제목·배속 6버튼·모드토글·단축키 안내·마크 0건·비활성 제출버튼)을 스냅샷으로 확인.
- ⚠ **환경 제약**: 이 브라우저는 병렬 실행 중인 다른 QA 서브에이전트와 **탭·localStorage(JWT 토큰 키)를 공유**하는 것으로 관측됨(navigate 직후 다른 role/URL 로 튀는 현상 반복 재현) — 다회 순차 상호작용(Space/Enter 연타 등 라이브 키 입력 시퀀스)은 신뢰도가 낮아, 최초 1회 클린 렌더 확인 이후는 **정적 코드 대조 + 기존 vitest 커버리지 대조**로 전환. 이 사실 자체는 FE 결함이 아니므로 이슈로 기록하지 않음.
- 영상 스트림은 `GET /videos/69/stream...` → **404** 관측. DB 조회 결과 이 로컬 DB의 `LS_DEIDENT_PROC_LOG` 에 raw_sn=69 행이 없고(시드 데이터가 `de_ident_yn='Y'` 를 직접 주입), 실제 비식별 산출물이 있는 raw_sn(94/81/80)도 파일 경로가 `/app/storage/...`(컨테이너 전용, 이 호스트엔 `/app` 없음)라 로컬에서 재생 불가 — **테스트 데이터/환경 한계이며 MarkingPage 코드 결함 아님**(별도 이슈 생성 안 함).
- **BE 파이프라인 순서 실측 대조(확증편향 금지 지시 이행)**: `BatchStageProgressMapper.DISPLAY_ORDER`(`backend/.../batch/status/BatchStageProgressMapper.java:22-30`) = `[DEIDENTIFY, MARKING, VLM, FRAME_EXTRACT, YOLO, SAM2, INTERPOLATE]` — `BatchPipelineConfig.java`(pre-marking=[DEIDENTIFY], post-marking=[MARKING,VLM,FRAME_EXTRACT,YOLO,SAM2,INTERPOLATE])·루트 CLAUDE.md 서술과 정확히 일치. FE 는 `STAGE_ORDER` 상수를 **어디에도 갖고 있지 않음**(`grep -rn STAGE_ORDER frontend/` = 0건) — BatchStageIndicator 는 BE 가 내려준 `stages` 배열을 그대로 `.map()` 렌더(순서 가정 0, name→라벨 매핑만). **"STAGE_ORDER 드리프트 함정"은 현재 코드에 존재하지 않음** — FE 가 애초에 그 함정 패턴(자체 순서 하드코딩)을 쓰지 않도록 설계돼 있다.

---

## 1. H-6. MarkingPage — 20건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-110 | PASS | [정적] | `MarkingPage.tsx:154-156` `if (rawSn===undefined \|\| isNaN(rawSn)) return <div>...잘못된 영상 ID입니다.</div>` |
| TC-FE-111 | PASS | [정적]+[실동작] | `MarkingPage.tsx:158-173` `role="alert"` + "비식별 완료 후 마킹이 가능합니다." vitest `MarkingPage.test.tsx:220-245`(`비식별_미완료_영상_직접진입시_마킹차단_백스톱_안내`)로 회귀 고정 |
| TC-FE-112 | PASS | [정적] | `features/video/types.ts:27-38` `isMarkingBlocked` — `deIdntfYn==='N'\|\|'F'` 또는 `deidentStatus IN_PROGRESS/FAILED` → true. `'F'`(신고) 도 차단 대상에 포함돼 있어 프로젝트 확정 정책(신고 구간 라벨/스트리밍 차단)과 일치 |
| TC-FE-113 | PASS | [정적] | `MarkingPage.tsx:139-141` `e.code==='Space'` → `preventDefault`+`handleAddMarkAtCurrentTime()`→`addMark` |
| TC-FE-114 | PASS | [정적] | `MarkingPage.tsx:142-144` `Delete\|Backspace` → `removeSelectedMark()` |
| TC-FE-115 | PASS | [정적]+[실동작] | `MarkingPage.tsx:145-147` `Enter`→`handleSubmit()`. vitest(`마킹_제출_pending_중_Enter_재호출시_추가_POST_미발생`)가 Enter→POST 발화를 실제로 검증 |
| TC-FE-116 | PASS | [정적] | `MarkingPage.tsx:136-137` `tag==='INPUT'\|\|'TEXTAREA'\|\|'SELECT'` 3종 모두 조기 return — 구 2종(INPUT/TEXTAREA)에서 SELECT 로 확장된 상태 그대로 |
| TC-FE-117 | PASS | [정적] | `MarkingPage.tsx:118-119` `if (!intervalFrames \|\| intervalFrames<1) return;` — mutate 미호출. `MarkingToolbar.tsx:29-30` 도 동일 조건으로 버튼 자체를 비활성화(방어 이중화) |
| TC-FE-118 | PASS | [정적] | `MarkingPage.tsx:124-125` `if (localMarks.length===0) return;` |
| TC-FE-119 | PASS | [정적]+[실동작] | `MarkingPage.tsx:116` `if (createMutation.isPending) return;`. vitest 로 Enter 연타 시 `postCount` 가 1로 유지됨을 실제 확인(`postCount` 카운터 mock) |
| TC-FE-120 | PASS | [정적]+[실동작] | `MarkingPage.tsx:61-77` `onSuccess`: `batchTriggered!==false` 분기에서 `clearMarks()`+success 토스트("마킹이 제출되었습니다. 배치 처리가 시작됩니다.")+`navigate('/task')`. vitest 로 토스트·navigate 모두 확인 |
| TC-FE-121 | PASS | [정적]+[실동작] | `MarkingPage.tsx:81-89` `onError`→`extractBeMessage(err, 기본메시지)` 로 error 토스트. vitest(`이벤트유형없는영상_400응답시_에러토스트_표시_및_이동안함`)로 400 응답 시 에러토스트+미navigate 확인 |
| TC-FE-122 | PASS | [정적] | `MarkingPage.tsx:49-58` `streamRetriedRef` 로 1회만 재발급(`handleStreamError`가 재진입 시 `if (streamRetriedRef.current) return;` 가드), `finally` 에서 플래그 리셋해 **다음** 만료 시 다시 1회 허용 — 무한루프 방지 로직 정확. ⚠ 이 케이스 전용 vitest 는 없음(커버리지 갭, 결함은 아님) |
| TC-FE-123 | PASS | [정적] | `MarkingPage.tsx:92-97` `useEffect([rawSn])` → `reset()` + `setDurationSec(FALLBACK_DURATION_SEC=60)`, unmount/변경 시 cleanup 도 `reset()` |
| TC-FE-124 | PASS | [정적]+[실동작] | `MarkingPage.tsx:181-187` `videoDetail?.stages && stages.length>0` 일 때만 `<BatchStageIndicator>` 렌더. 실동작: raw_sn=69 는 BE `LS_BATCH_PROC_LOG` 행이 없어(`stages=[]`) 인디케이터 미노출 상태로 렌더됨(스냅샷 확인) — 조건부 렌더 정상 동작의 실증 |
| TC-FE-125 | PASS | [정적] | `MarkingPage.tsx:221-241` 각 마크 칩 `onClick={() => selectMark(i)}`, `selectedMarkIndex===i` 시 `bg-primary-600 text-white` 하이라이트 |
| TC-FE-203 | PASS | [정적]+[실동작] | `markingFps.ts:21-31` `resolveMarkingFps` — 서버 fps 우선, 무효값만 `MARKING_FALLBACK_FPS=30` 폴백(BE `VideoFpsResolver.DEFAULT_FPS` 와 동일값). `VideoPlayer.tsx:54-55` `getCurrentFrame(fps)→markingFrameIndex(currentTime,fps)`(필수 인자, 기본값 없음 — 호출부 누락 시 컴파일 에러로 회귀 차단). vitest(`markingFps.test.ts`) 5건이 25fps/29.97fps/하드코딩 회귀 케이스까지 수식 대조로 고정 |
| TC-FE-204 | PASS | [정적] | `MarkingPage.tsx:63-75` `batchTriggered===false` 분기: **error** 토스트("마킹은 저장되었으나 배치가 시작되지 않았습니다. {batchSkipReason ?? 기본문구}")+`navigate('/task')` — 성공 문구로 위장하지 않음. BE 계약 확인: `MarkingResponse.java`·`MarkingBatchTriggerReport.java` 에 `batchTriggered`/`batchSkipReason` 필드 실재(self-fill 아님, FE 타입 `types.ts:43-45` 와 1:1). ⚠ 이 분기 전용 vitest 는 없음(커버리지 갭, 결함은 아님 — 기존 `MarkingPage.test.tsx` 는 `batchTriggered` 미포함 응답만 사용) |
| TC-FE-205 | PASS | [실동작]+[정적] | **실동작**: `/marking/69` 스냅샷에서 `0.25x/0.5x/1x/1.5x/2x/4x` 6개 버튼 렌더 확인(요청한 배속 순서·이산 개수 그대로). **정적**: `VideoPlayer.tsx:34` `SPEED_OPTIONS=[0.25,0.5,1,1.5,2,4] as const`, `:46-49` `changeSpeed`가 `videoRef.current.playbackRate=rate`+`setPlaybackRate`, `:130-135` 활성 버튼만 `bg-primary-600 text-white`, `:133` 전 버튼 `min-h-11`(KRDS 터치 최소 높이) 적용. 자유 수치 입력 UI 없음(슬라이더는 seek 전용) — UNCERTAINTIES #23 확정("클램프 불필요")과 일치 |
| TC-E2E-003 | PASS | [정적]+[실동작] | `pages/__tests__/MarkingPage.test.tsx`(총 8개 시나리오: 제출성공/토스트/navigate, MANUAL payload 직렬화, 400에러토스트, 마킹목록 비노출, 백스톱 진입차단/정상렌더, pending 중복방지, 서명URL 주입)가 스트림 서명 URL 주입 + 배속 UI 존재 + 단축키(Enter) 발화까지 mock API 기반으로 커버. 라이브 브라우저 진입도 위와 동일한 화면 구조로 렌더됨을 실측 확인(§0 참조) — 단, 실제 `<video>` 재생까지의 종단 E2E 는 이 환경의 영상 파일 부재로 미실증(환경 한계, §0 명시) |

**H-6 집계**: PASS 20 · FAIL 0 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 2. H-7. BatchStageIndicator (STAGE_ORDER 드리프트 함정) — 5건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-126 | PASS | [정적]+[실동작 vitest] | `BatchStageIndicator.tsx:66` `if (!stages \|\| stages.length===0) return null;`. vitest(`stages가_빈배열이면_아무것도_렌더하지_않는다_배지폴백`) `container.firstChild` 가 null 임을 확인 |
| TC-FE-127 | PASS | [정적]+[실동작 vitest] | `STAGE_LABEL`(`:16-24`) 7키 모두 존재: DEIDENTIFY→비식별, MARKING→마킹, VLM→VLM, FRAME_EXTRACT→프레임추출, YOLO→AI 탐지, SAM2→AI 분할, INTERPOLATE→보간. vitest 3건이 7라벨 전부 렌더 확인 |
| TC-FE-128 | PASS | [정적] | `STAGE_LABEL_FALLBACK='처리중'`(`:28`), `:81` `STAGE_LABEL[stage.name] ?? STAGE_LABEL_FALLBACK` — 매핑 없는 코드는 "처리중"으로 폴백해 기술 코드명이 노출되지 않음. YOLO/SAM2 도 라벨 매핑되어 원문 노출 안 됨(vitest `container.textContent` 에 'YOLO'/'SAM2' 미포함 확인). ⚠ **미지 코드(예: 매핑에 없는 신규 BE 코드) 입력에 대한 전용 vitest 는 없음**(커버리지 갭 — 로직 자체는 정확) |
| TC-FE-129 | PASS | [정적]+BE 대조 | `:70` `{stages.map((stage, idx) => {...})}` — 배열을 받은 순서 그대로 렌더, 자체 정렬/재배치 로직 0. **BE 대조(반증 시도)**: `BatchStageProgressMapper.DISPLAY_ORDER`(`backend/.../batch/status/BatchStageProgressMapper.java:22-30`)가 `[DEIDENTIFY,MARKING,VLM,FRAME_EXTRACT,YOLO,SAM2,INTERPOLATE]` 로 유일한 순서 진실원이며 FE 에 순서 상수(`STAGE_ORDER` 류) 자체가 존재하지 않음(`grep -rn STAGE_ORDER frontend/` 0건). §0 서술한 대로 "STAGE_ORDER 드리프트" 함정은 이 코드베이스에 **현재 존재하지 않는다** — 케이스명의 "함정"은 과거/잠재 위험에 대한 경고이며 실제 구현은 이미 회피 설계됨 |
| TC-FE-130 | PARTIAL | [정적] | 아이콘 4종(DONE=Check/success, PROGRESS=Loader2 spin/info, FAIL=X/danger, 기타(PENDING)=회색 점) 모두 `aria-hidden`(Check/Loader2/X 는 `aria-hidden` prop, PENDING 점은 장식용 div) — 케이스가 요구하는 "아이콘 aria-hidden" 자체는 사실과 일치해 표 판정상 PASS 이나, **반증 결과 스크린리더 사용자에게 단계별 상태(완료/진행중/실패/대기)를 알릴 대체 텍스트가 컴포넌트 어디에도 없음**(`aria-label`·`sr-only`·`role` 전 검색 0건 — 라벨 텍스트는 단계 "이름"만 노출, 상태는 아이콘 모양/색으로만 구분). WCAG 2.1 AA(프로젝트 요구사항) 및 `component.md` "색상만으로 정보 전달 금지" 원칙에 비춰 접근성 갭 → **H-ISSUE-70** 로 기록(케이스 자체는 표기대로 PASS 조건 충족이라 판정은 PARTIAL) |

**H-7 집계**: PASS 4 · PARTIAL 1 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 3. 이슈

### [H-ISSUE-70] TC-FE-130 — BatchStageIndicator 단계 상태가 스크린리더에 노출되지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WCAG 2.1 AA 준수 요구(루트 CLAUDE.md 포털 섹션) 및 `component.md`의 "색상만으로 정보 전달 금지(아이콘/텍스트 병행)" 원칙에 따라, 배치 단계별 상태(완료/진행중/실패/대기)는 스크린리더 사용자도 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `frontend/src/components/common/BatchStageIndicator.tsx` 의 `StageIcon`(:30-57)이 렌더하는 `Check`/`Loader2`/`X` 아이콘은 모두 `aria-hidden`(각 `:34,41,48`)이고 PENDING 상태는 장식용 `<div>`(`:53-55`)뿐이다. 스테이지마다 노출되는 텍스트(`:77-82`)는 **단계 이름**(예: "비식별", "마킹")만이며 상태를 나타내는 `aria-label`/`sr-only`/`role` 속성이 컴포넌트 전체에 하나도 없다(`grep -n "aria-label|sr-only|role="` 결과 0건). 즉 스크린리더 사용자는 "비식별 단계가 존재한다"는 것만 알 수 있고 그 단계가 끝났는지/실패했는지/대기 중인지 알 방법이 없다.
- **재현/확인 경로**: 스크린리더(VoiceOver 등)로 마킹/영상상세 화면 진입 → `data-testid="batch-stage-indicator"` 영역 탐색 → 각 단계 이름만 낭독되고 상태 정보 낭독 없음. 코드 확인: `grep -n "aria-hidden\|aria-label" frontend/src/components/common/BatchStageIndicator.tsx`.
- **영향**: 접근성(WCAG 2.1 AA 1.1.1/4.1.2 상당) — 시각장애 사용자가 배치 처리 실패(FAIL) 여부를 화면에서 인지할 수 없어, 실패 시 대응이 늦어질 수 있음. 보안 영향 없음.
- **수정 방향(제안)**: 각 단계 아이콘 wrapper 또는 단계 컨테이너에 상태를 서술하는 `aria-label`(예: `"${label} — ${status==='DONE'?'완료':status==='PROGRESS'?'진행중':status==='FAIL'?'실패':'대기'}"`) 또는 시각적으로 숨긴 `sr-only` 텍스트를 추가. 아이콘 자체의 `aria-hidden` 은 유지하되 상위 요소에 접근성 트리 정보를 부여.

---

## 4. 집계 요약

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| H-6 | 20 | 20 | 0 | 0 | 0 | 0 | 0 |
| H-7 | 5 | 4 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **25** | **24** | **0** | **1** | **0** | **0** | **0** |

신규 이슈: **H-ISSUE-70** (MEDIUM, a11y) 1건. 이전 회차 이월 대상 없음(H-6/H-7 최초 세부 검증).
# H-part5 — H-8. ReviewPage(검수 화면, 21건) + H-9. 관리 화면(/manage/*, 11건) = 32건

> 검증일 2026-08-02 · 회차 2차 · 담당 범위: `docs/test-cases/H-frontend-e2e.md` §H-8 `TC-FE-131~147,206~208 + TC-E2E-004` · §H-9 `TC-FE-148~156 + TC-E2E-005~006`
> 대상 코드: `frontend/src/pages/ReviewPage.tsx` · `frontend/src/features/review/**` · `frontend/src/pages/manage/**` · `frontend/src/router/index.tsx`
> 실행 스택: `klid-backend`(:18081) · `klid-frontend`(:13000) · `klid-postgres` (2026-08-01 재빌드 V158 최신 바이너리, `git log 56d30478..HEAD -- frontend/` = 0건이라 baseline 과 워킹트리 frontend 동일)

## 0. 검증 환경 실측 (판정 전제)

| 항목 | 실측값 | 근거 |
|------|--------|------|
| FE baseline | **1,951 tests 전량 PASS**(2026-08-01, commit 56d30478) | `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md:11` |
| frontend 워킹트리 변경 | **0건** (baseline 커밋 이후) | `git log --oneline 56d30478..HEAD -- frontend/` 무출력 |
| REVIEWER dev 토큰 | 실발급 확인 | `POST /api/v1/dev/tokens {role:REVIEWER,userNo:1001}` → 200 |
| WORKER dev 토큰 | 실발급 확인 | `POST /api/v1/dev/tokens {role:WORKER,userNo:1003}` → 200 |
| 실 검수 데이터 | PENDING 2건(id=31,7, labelCount=0) · COMPLETED 20건 · REJECTED 1건 | `GET /api/v1/reviews/summary` → `{"total":23,"pending":2,"inReview":0,"approved":20,"rejected":1}` |
| WORKER→관리 API 접근 | **403 전건** | `GET /api/v1/users`·`/labels/master`·`/presets`·`/deident-reports` (WORKER 토큰) → 모두 403 |

**⚠ 환경 제약(중요, 판정 방법에 영향)**: 이번 회차는 다수의 병렬 검증 에이전트가 **동일 Playwright MCP 브라우저 세션을 공유**하고 있어(같은 세션의 다른 에이전트가 임의 시점에 탭을 재사용/전환), 신규 탭을 명시적으로 열고 `browser_tabs select`로 포커스를 고정해도 스냅샷 직전에 다른 에이전트가 그 탭에서 자체 네비게이션을 발생시켜 `/review/31` → `/task` → `/augment/result/4` 로 내용이 계속 바뀌는 현상을 반복 실측했다(탭 인덱스는 고정돼도 실제 렌더 콘텐츠가 매 스냅샷마다 달랐음 — 격리 실패, 코드 결함 아님). 이 때문에 본 라운드는 **①BE 실HTTP 왕복(curl, 격리 안전) + ②정적 코드 대조 + ③기존 FE 컴포넌트/E2E 테스트 자산 대조**를 주 근거로 삼았다(README §5의 방법 2·3). 상호작용성 UI 렌더링 자체(다이얼로그 애니메이션 등 순수 시각 확인)만 브라우저 관측이 필요한 항목이며, 그 로직은 vitest 컴포넌트 테스트(axios-mock-adapter 기반, 실제 DOM 렌더 + userEvent 클릭)로 이미 실행 검증되어 있어 판정 신뢰도에 공백은 없다고 판단했다. 접근권한(RBAC) 계층은 BE 실HTTP(격리 안전)로 별도 실증했다.

---

## 1. H-8. ReviewPage (검수 화면) — 21건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-131 | PASS | [정적] | `pages/ReviewPage.tsx:229-238` — `Number.isNaN(numericId)` 시 `ErrorState title="잘못된 검수 ID"` 렌더. 라인 정확 일치. 전용 유닛테스트는 없으나 단순 가드절이라 코드로 확정 |
| TC-FE-132 | PASS | [정적]+[테스트] | `ReviewPage.tsx:240-255` `isLoading` 분기. `ReviewPageLayout.test.tsx:101` `로딩중_spinner_노출` 실행 검증(1,951 baseline 통과분) |
| TC-FE-133 | PASS | [정적]+[테스트] | `ReviewPage.tsx:257-266`. `ReviewPageLayout.test.tsx:128` `에러_상태_안내_표시` — "검수 정보를 불러올 수 없습니다" 텍스트 확인 |
| TC-FE-134 | PASS | [정적]+[테스트] | `ReviewPage.tsx:179-183` mount effect `review.status==='REVIEW_PENDING' && !didStart → doStart`. `ReviewPage.test.tsx:70-112` `검수_시작_버튼_클릭시_상태_REVIEWING_전이`(테스트명은 오기이나 실내용은 버튼 클릭 없이 mount 만으로 `POST /reviews/10/start` 자동 호출을 `waitFor(started===true)`로 검증 — 코드와 일치) |
| TC-FE-135 | PASS | [정적]+[테스트] | `ReviewPage.tsx:294-308` "읽기 전용" 배지 + `LabelCanvas` 좌표 마커 미사용. `ReviewPage.test.tsx:366-390` `검수_화면_캔버스_좌표_마커_컴포넌트_미사용` — `[data-marker]`/`[data-testid*="marker"]`/`[data-testid*="coordinate"]` 전부 DOM 부재 확인 |
| TC-FE-136 | PASS | [정적]+[테스트] | `ReviewPage.tsx:306` `loading={framesLoading}` prop 전달. `ReviewPage.test.tsx:330-364` `검수화면_프레임_로딩중_스피너_표시되고_프레임없음_문구_미표시` — 캔버스 영역 내 스피너 확인 + "프레임 없음" 오표시 부재 확인 |
| TC-FE-137 | PASS | [정적]+[테스트] | `ReviewPage.tsx:206-208,399-408` 승인 클릭→`ConfirmDialog(title="승인 확정")`. `ReviewPage.test.tsx:114-160` `승인시_상태_COMPLETED_전이` — 승인 버튼→다이얼로그→확정 클릭 흐름 실행 |
| TC-FE-138 | PASS | [정적]+[테스트] | `ReviewPage.tsx:155-160` `onSuccess`에서 `noLabelConfirmOpen` 닫힘+"승인 완료" 토스트+`navigate('/review')`. 동일 테스트가 `REVIEW_LIST` 라우트 전환까지 확인 |
| TC-FE-139 | PASS | [정적] | `ReviewPage.tsx:174` `extractBeMessage(err, '승인 실패')` — BE 메시지 우선, fallback 만 고정문구. `lib/api/extractBeMessage.ts` 로직 확인(ApiError.userMessage→message→axios response.data.message→fallback 순). `ReviewPage.test.tsx:162-207`(H6_동시승인충돌) 이 이 분기를 실행하나 토스트 문자열 자체는 미단언 — 코드로는 확정, 문자열 수준 단언 테스트는 부재(경미, 이슈화 대상 아님) |
| TC-FE-140 | PASS | [정적]+[테스트] | `ReviewPage.tsx:65-84` `composeRejectReason`. `ReviewPage.test.tsx:309-328` 3개 유닛테스트(사용자입력만/전체의견포함/이슈labelId없을때 괄호생략) 전부 통과분 |
| TC-FE-141 | PASS | [정적] | `ReviewPage.tsx:380` `RejectModal onSuccess={() => navigate('/review')}`. `RejectModal.tsx:63-67` mutation `onSuccess`에서 `onSuccess?.()` 호출 확인. 승인 경로와 동일 패턴이나 반려 전용 navigate 단정 테스트는 부재(경미) |
| TC-FE-142 | PASS | [정적]+[테스트] | `ReviewPage.tsx:126-139` `inquirySrcSns` — `issueTypeCd===INQUIRY && issueSttsCd!==RESOLVED && srcSn!=null` 필터. `ReviewPageFrameStatus.test.tsx` 가 미해소(OPEN, srcSn101)=포함·해소(RESOLVED, srcSn100)=제외·영상단위(srcSn null)=제외 3분기 모두 실데이터로 검증 |
| TC-FE-143 | PASS | [정적]+[테스트] | `ReviewPage.tsx:141-149` `savedSrcSns` — `labels.length>0`. 동일 테스트 파일이 4프레임 중 라벨 있는 srcSn102 만 포함되는지 확인 |
| TC-FE-144 | PASS | [정적] | `ReviewPage.tsx:187-192` `currentFrameIdx<0 \|\| >=frames.length → setCurrentFrameIdx(0)`. 전용 유닛테스트는 없으나 단순 range-guard effect라 코드로 확정 |
| TC-FE-145 | PASS | [정적] | `ReviewPage.tsx:195-200` unmount cleanup — `clearSelection()+setCurrentFrameIdx(0)`. 전용 유닛테스트는 없으나 useEffect cleanup 반환 패턴이 명확 |
| TC-FE-146 | **FAIL** | [정적] | **증강/파생 여부 표시 자체가 화면에 없다.** `features/review/components/ReviewHeader.tsx`·`ReviewMetaPanel.tsx` 전체 grep 결과 `ORGNL_RAW_SN`/`orgnlRawSn`/`VMS_CLIP_ID`/`vmsClipId`/`aug`/`증강`/`파생` 매치 0건. `features/review/types.ts`의 `Review` 인터페이스(`id,videoId,cctvName,workerId,workerName,submittedAt,labelCount,status,reviewerId,eventName,eventTypeCd`)에도 파생 관련 필드가 아예 없다. **BE 근본 원인**: `backend/.../review/dto/ReviewResponse.java` 전체 필드에도 `orgnlRawSn`/증강 관련 값이 없음 — FE 구현 누락이 아니라 BE 계약 자체에 파생 식별자가 실려있지 않다. 검수자가 검수 화면만으로는 이 영상이 원본인지 증강/해상도 파생본인지 알 수 없다 → **H-ISSUE-81** |
| TC-FE-147 | PASS | [정적] | `ReviewPage.tsx:355` `<IssueThreadPanel rawSn={review.videoId} mode="reviewer" dark />` — 라인·props 정확 일치 |
| TC-FE-206 | PASS | [정적]+[테스트] | `ReviewPage.tsx:170-172,220-226,386-398` — BE 409 `errorCode==='REVIEW_NO_LABEL'`일 때만 `noLabelConfirmOpen=true`, 확인 시 `doApprove({reviewId, body:{noLabelConfirmed:true}})`(상시 미전송). BE 측 `ErrorCode.REVIEW_NO_LABEL = 409 CONFLICT`(`common/exception/ErrorCode.java:20`), 발행부 `ReviewService.java:560` 확인. `ReviewPage.test.tsx:209-239` `H6_라벨0건_409는_errorCode_REVIEW_NO_LABEL_로_구분해_확인_다이얼로그를_띄운다` 통과 |
| TC-FE-207 | PASS | [정적]+[테스트] | `ReviewPage.tsx:165-175` — 상태코드(409)가 아니라 `err.errorCode`로 분기, `REVIEW_NO_LABEL` 아니면(예: `CONFLICT`) 다이얼로그 미노출+토스트만. `ReviewPage.test.tsx:162-207` `H6_동시승인충돌_409는_라벨없음_다이얼로그를_띄우지_않는다` — `CONFLICT` errorCode 로 409 응답 시 "라벨 없음 확인 후 승인" 버튼 부재 확인 |
| TC-FE-208 | PASS | [정적]+[테스트] | `features/review/components/ReviewMetaPanel.tsx` `CaptionReadonly`가 `normalizeCot(cand.cot).filter(...)` 사용(구 `(cand.cot ?? []).filter` 직접 배열연산 아님). `features/label/components/__tests__/eventAnnotationForm.test.ts:10-24` — 배열형 그대로 반환/객체형(`{'1단계':...}`)을 키순서 배열화/undefined→빈배열 3케이스 전부 통과. 객체형 cot 입력 시 TypeError 크래시 재발 없음 |
| TC-E2E-004 | PASS | [정적] | `e2e/specs/review-flow.spec.ts:5-24` — `test.describe.serial`, 목록 진입(`review-list-page` testid) → 상세 진입 → 시작버튼 있으면 클릭 → 승인버튼 있으면 클릭+승인 텍스트 확인. 시나리오 구조가 카탈로그 기대와 일치. **실행은 안 함**(빌드/테스트 실행 금지 지침) — 파일 존재·로직 정합만 확인 |

**H-8 집계**: PASS 20 · FAIL 1 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 2. H-9. 관리 화면 (/manage/*) — 11건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-148 | PASS | [정적]+[테스트] | `pages/manage/LabelMasterManagePage.tsx` — `dtctTypeCd` 입력 필드. `LabelMasterManagePage.test.tsx:137-180` `COCO_클래스를_지정해_생성하면_dtctTypeCd가_요청본문에_포함된다` / `COCO_클래스_미지정으로_생성하면_dtctTypeCd가_null로_전송된다(미매핑_허용)` / `COCO_중복_매핑시_409응답이_사용자_메시지로_표시된다` 3건 통과분 |
| TC-FE-149 | PASS | [정적]+[테스트]+[실동작] | 라우터: `router/index.tsx:375-425` `/manage/*` 전 하위경로가 `InternalRoute allow={internalReviewerOnly}`(=`[Role.REVIEWER]`). **근거 드리프트**: 카탈로그가 지목한 `pages/manage/__tests__/UserManagePage.test.tsx`는 실제로는 검색필터만 테스트하고 역할 접근제어는 미검증 — 실제 검증 파일은 `router/__tests__/manageGuard.test.tsx:45-74`(WORKER→`/manage/users`,`/manage/settings` 모두 FORBIDDEN_PAGE / REVIEWER→정상 렌더 3케이스 통과). **BE 실HTTP 이중 확인**: WORKER 토큰으로 `GET /api/v1/users`·`/api/v1/labels/master`·`/api/v1/presets`·`/api/v1/deident-reports` 전부 **403** 실측(FE 가드 우회해도 BE 가 방어) → **H-ISSUE-82**(근거 드리프트, LOW) |
| TC-FE-150 | PASS | [정적]+[테스트] | `pages/manage/SystemSettingsPage.tsx:40-56` — `BatchConfigCard`+`YoloConfigCard`+`PrecisionConfigCard`(①편집가능) / `HealthStatusList`(②모니터링) / `DangerActions`(③위험액션) 3섹션 정확 일치. `SystemSettingsPage.test.tsx:55-70` `3섹션_렌더링_(Batch_health_danger)` 통과 |
| TC-FE-151 | PASS | [정적]+[테스트] | `pages/manage/PresetListPage.tsx` — 마스터 join. `PresetListPage.test.tsx:75-103` 카테고리 라벨 표시/미매핑 프리셋 "미매핑" 표시/연결 라벨은 마스터 라벨명+형태 표시/미연결 배지 4케이스 통과 |
| TC-FE-152 | PASS | [정적]+[테스트] | `pages/manage/DeidentReportListPage.tsx`. `features/deident/__tests__/DeidentReportListPage.test.tsx:52-88` — OPEN 신고 목록 렌더(rawSn/reason 표시) + 해소 클릭→`POST /deident-reports/{id}/resolve`→목록 재조회(invalidate) 2케이스 통과 |
| TC-FE-153 | PASS | [정적]+[테스트] | `features/task/components/AssignModal.tsx`. `AssignModal.test.tsx:90-145` `작업자_선택_후_POST_assignments_호출` + `작업자_미선택_상태에서는_저장_버튼_disabled` 통과 |
| TC-FE-154 | PASS | [정적]+[테스트] | `features/task/components/HistoryDrawer.tsx:26-40`(주석: ASSIGN/REASSIGN/SUBMIT/APPROVE/REJECT 5종 타임라인, `GET /assignments/{id}/history`). `HistoryDrawer.test.tsx` — KRDS 상태색 토큰 매핑 3케이스 + 이벤트 설명(CANCEL_SUBMIT 등) 2케이스로 렌더 로직 간접 검증 |
| TC-FE-155 | PASS | [정적]+[테스트] | `pages/DashboardPage.tsx:110-164` — REVIEWER(`isWorker=false`)는 KpiCard **정확히 3개**(처리대기/처리완료/반려건수, "내 작업"은 `isWorker` 조건부라 WORKER 전용) + 166행 이하 이미지/영상 데이터 카드(이벤트 6종 분포). `DashboardPage.test.tsx:74-106` KPI 3개 노출 + 이벤트 6종 노출 테스트 통과. `e2e/specs/reviewer-workflow.spec.ts:14-22` `대시보드_KPI_3카드와_이벤트_분포_노출` e2e 시나리오도 동일 주장 |
| TC-FE-156 | PASS | [정적]+[테스트] | `features/video/parseVideoListParams.ts`. `parseVideoListParams.test.ts` 8케이스(기본값/정상파싱/size상한/날짜형식/키워드절단/역변환/기본값URL미포함) 전부 통과 |
| TC-E2E-005 | PASS | [정적] | `e2e/specs/reviewer-workflow.spec.ts:14-56` — 대시보드 KPI+이벤트분포(L14-22) / 영상목록 헤더(L24-31) / 작업목록 헤더(L33-38) / 검수목록 컨테이너(L40-45) / `/manage/users` REVIEWER 접근(L47-56, forbidden 미도달 확인) 5개 서브시나리오 카탈로그 기대와 정확 일치. 실행은 안 함(빌드/테스트 금지) |
| TC-E2E-006 | PASS | [정적] | `e2e/specs/video-list.spec.ts:5-13` — `workerPage`로 검색 → `cctvNameKeyword=` URL 파라미터 갱신 확인. 카탈로그 "WORKER 검색" 전제와 정확 일치(REVIEWER 아님에 유의 — 카탈로그 기재도 WORKER) |

**H-9 집계**: PASS 11 · FAIL 0 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 3. 종합 집계

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|------|--:|--:|--:|--:|--:|--:|--:|
| H-8 (21건) | 21 | 20 | 1 | 0 | 0 | 0 | 0 |
| H-9 (11건) | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **32** | **31** | **1** | 0 | 0 | 0 | 0 |

---

## 4. 이슈

### [H-ISSUE-81] TC-FE-146 — 검수 화면에 증강/해상도 파생 여부·종류 표시가 전혀 없다(BE 계약 자체 미보유)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수자가 검수 화면(`/review/:id`)에서 이 영상이 원본인지, 증강(WINTER/NIGHT/RAIN)이나 해상도 변경(RESL_1080P/720P/480P) 파생본인지, 파생이라면 어떤 종류인지 확인할 수 있어야 한다. 파생영상은 라벨/메타가 원본에서 복사된 값이고 좌표가 재계산(해상도) 또는 그대로(증강) 적용된 결과라, 검수자가 이 맥락을 모르면 원본 대비 이상 여부(예: 리스케일 아티팩트, 계절/야간 변환에 따른 색상 변화)를 오판할 위험이 있다.
- **현재 동작(이슈 내용)**: `frontend/src/features/review/components/ReviewHeader.tsx`·`ReviewMetaPanel.tsx` 전체에 `ORGNL_RAW_SN`/`orgnlRawSn`/`VMS_CLIP_ID`/`vmsClipId`/증강 관련 키워드 매치가 0건이다. 상위 타입 `frontend/src/features/review/types.ts`의 `Review` 인터페이스도 다음 필드만 갖는다:
  ```ts
  export interface Review {
    id: number; videoId: number; cctvName: string; workerId: number;
    workerName: string; submittedAt: string; labelCount: number;
    status: ReviewStatus; reviewerId?: number;
    eventName?: string | null; eventTypeCd?: string | null;
  }
  ```
  BE 응답 계약도 동일하게 비어있다. `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java`의 필드 전체가 `id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd`뿐이며 파생 식별자가 없다. 즉 FE 구현 누락이 아니라 **BE 응답 DTO 자체에 파생 정보가 실려있지 않다.**
- **재현/확인 경로**: 증강 파생본(`LS_DATA_RAW.ORGNL_RAW_SN IS NOT NULL`)을 검수 목록에서 진입해 `GET /api/v1/reviews/{id}` 응답을 확인하면 `orgnlRawSn` 등 파생 관련 키가 존재하지 않는다. (참고: `GET /api/v1/tasks/board` 응답에는 `증강 데이터: 해상도 480p` 같은 배지가 실제로 존재함 — 작업목록 화면은 파생 여부를 이미 표시하고 있어 검수 화면만 빠진 것으로 보인다. 본 검증 세션에서 `/task` 화면 스냅샷에 `쓰러짐 증강 데이터: 해상도 480p` 셀이 다수 관측됨.)
- **영향**: 기능 정합성(검수 정확도) 저하. 보안 영향은 없음(정보 은닉이 아니라 단순 미표시).
- **수정 방향(제안)**: `ReviewResponse`에 `orgnlRawSn`(nullable)·(선택) 증강/해상도 종류 코드를 추가하고, `ReviewMetaPanel` 또는 `ReviewHeader`에 파생 배지를 렌더한다. 작업목록(`TaskBoardTable`)이 이미 "증강 데이터: {종류}" 배지를 갖고 있으므로 동일 소스/포맷을 재사용할 수 있는지 확인.

### [H-ISSUE-82] TC-FE-149 — 카탈로그 근거 file:line 드리프트: `/manage/*` REVIEWER 전용 접근제어의 실제 검증 파일이 다르다
- **심각도**: LOW
- **기대 동작(기대효과)**: 테스트 케이스 카탈로그의 "근거" 컬럼은 실제로 그 단언을 검증하는 코드/테스트 위치를 정확히 가리켜야 한다(회귀 시 추적성).
- **현재 동작(이슈 내용)**: `docs/test-cases/H-frontend-e2e.md` TC-FE-149 행의 근거가 `pages/manage/__tests__/UserManagePage.test.tsx`로 기재돼 있으나, 이 파일(`frontend/src/pages/manage/__tests__/UserManagePage.test.tsx:31`)은 REVIEWER 세션을 고정한 채 검색 필터 동작만 테스트하고 **역할 접근제어를 전혀 검증하지 않는다**. 실제로 WORKER/REVIEWER 접근 분기를 검증하는 파일은 `frontend/src/router/__tests__/manageGuard.test.tsx:36-75`이다(3케이스: WORKER→`/manage/users` FORBIDDEN, WORKER→`/manage/settings` FORBIDDEN, REVIEWER→둘 다 정상 렌더).
- **재현/확인 경로**: `UserManagePage.test.tsx` 파일 내용을 `grep -n "WORKER\|forbidden\|Role\."`으로 확인하면 역할 분기 코드가 없음을 바로 확인 가능.
- **영향**: 기능 결함은 아님(실제 접근제어는 라우터 가드 + BE 403으로 정상 동작, 본 검증에서 실HTTP로 재확인됨). 카탈로그 정합성 결함으로, 다음 회차 검증자가 잘못된 파일을 열어보고 "테스트 없음"으로 오판할 위험이 있다.
- **수정 방향(제안)**: 카탈로그 근거를 `router/__tests__/manageGuard.test.tsx:45-74`로 정정.

---

## 5. 판정 근거 요약

- **PASS 31건**: 코드 정적 대조(라인 정확 일치 다수) + 기존 vitest 컴포넌트 테스트(axios-mock-adapter 기반 실행 검증, 2026-08-01 baseline 1,951건 전량 통과, 이후 frontend 워킹트리 변경 0건이라 현재도 유효) + 일부 BE 실HTTP 왕복(RBAC 403, REVIEW_NO_LABEL 409 계약)으로 뒷받침.
- **FAIL 1건**(TC-FE-146): BE 응답 DTO에 파생 식별자 필드 자체가 없어 FE 가 표시할 수 없는 상태 — 미구현 갭으로 판단해 FAIL 승격.
- 브라우저 실동작(mcp playwright) 은 세션 공유로 인한 탭 콘텐츠 불안정 때문에 상호작용 흐름의 신뢰 가능한 근거로 사용하지 않았고, 대신 이미 실행된 컴포넌트 테스트(동일 렌더 엔진·동일 이벤트 시스템 — React Testing Library + userEvent)로 대체했다. 이는 README §5 방법 3("테스트 커버 확인")에 해당하며, 방법 1(실동작)이 아니라는 점을 근거확인 컬럼에 정직하게 `[정적]`으로 표기했다.
# H 클러스터 검증 결과 — part6 (H-10 증강/해상도 파생 화면 14건 + H-11 포털 화면 22건 = 36건)

- **대상**: `docs/test-cases/H-frontend-e2e.md` `## H-10. 증강/해상도 파생 화면`(TC-FE-157~164, 209~213, TC-E2E-007) + `## H-11. 포털 화면`(TC-FE-165~177, 271~275, TC-E2E-008~011)
- **검증일**: 2026-08-02 (2차)
- **환경**: frontend `localhost:13000` · backend `localhost:18081/api` (실기동, `/actuator/health` UP). 코드 기준 `qa-0801`
- **인증**: BE `POST /v1/dev/tokens` 실서명 JWT → `/ingress?token=` (REVIEWER userNo=1001 / PORTAL_USER userNo=2001)
- **검증 방식**: Playwright MCP 실브라우저 조작(실동작) 우선 + `file:line` 정적 대조 + FE 단위테스트 인벤토리 대조
- **판정 집계**: 총 36건 — **PASS 32 · PARTIAL 3 · FAIL 1** (FAIL/PARTIAL 4건 = 이슈 H-ISSUE-101~105)

## ⚠ 검증 환경 특기사항 (판정 신뢰도에 영향)

1. **Playwright MCP 브라우저가 다른 병렬 에이전트와 공유**되어 `sessionStorage['klid_jwt']`·현재 탭이 수시로 덮어써졌다. `navigate → tabs.select(내 탭) → evaluate(SPA pushState + DOM 수집)` 순서로 재시도해 **모든 실동작 판정은 URL·역할(`role` 클레임)을 응답에 함께 실어 검증**했다. 아래 `[실동작]` 표기 건은 전부 대상 URL 이 응답에 확인된 회차의 결과다.
2. **검증 중 수행한 데이터 변경**(실동작 확인 목적, 전부 QA 데이터):
   - 포털 이미지 2건 업로드(`uldSn=80`, `81` — 81은 XSS 페이로드 파일명)
   - 포털 user-label 1건 저장(`userLblSn=20`, rawSn=94/srcSn=448)
   - 증강 `dataAugSn=8`(job 18 NIGHT) **PENDING→ACCEPTED** (되돌릴 수 없음 — `applyReviewStatus` 는 PENDING 에서만 전이)
   - 증강 `dataAugSn=4`(job 4 RAIN #2) PENDING→REJECTED→**복구(PENDING)** — 원상복구 완료
   - 해상도 파생 1건 생성(원본 rawSn=94 → 파생 **rawSn=100**, 480P, 검수 대기)

---

## 1. H-10. 증강/해상도 파생 화면 (14건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|------|------|
| TC-FE-157 | PASS | [실동작] | `/augment` 진입 시 `radiogroup "처리 종류"` 안에 **ProcessKindCard 4종**(겨울·야간·우천·해상도 변경)이 `role=radio` 로 렌더 → 단일 선택. 해상도 변경 선택 시에만 "생성할 해상도(파생영상)" 체크박스 3종(1080P/720P/480P, 기본 전체 체크) 노출. 근거 `features/augment/types.ts:24-46` · `pages/AugmentRequestPage.tsx:63-69,428` |
| TC-FE-158 | PASS | [실동작] | 종류=해상도 변경, 프리셋 480P 만 체크, 영상 `#94` 선택 → [처리 요청] → 화면에 **"파생영상 1건 생성됨 — 검수 대기 / 480P (854×480) / 검수 대기 (영상 #100)"**. `useResolutionDerivative.ts:17-27` 이 성공 시 `queryClient.invalidateQueries({queryKey: VIDEO_KEYS.all})` 수행(근거 라인 정확) |
| TC-FE-159 | PASS | [실동작] | `/augment` 최근 요청 이력에서 jobId 81 카드가 `해상도 1080p`·`해상도 720p`·`해상도 480p` 3배지 렌더. 배지 클래스 `border-info/30 bg-info/10 text-info`(info 톤), 문구는 `resolutionDerivativeLabel(code)` 한글 라벨. ⚠ **근거 드리프트**: 카탈로그 `JobCard.tsx:64-72` → 실제 `JobCard.tsx:81-89` |
| TC-FE-160 | PASS | [실동작] | jobId 81(RESL 3항목, `reviewable=false`) 결과 화면 → 3탭 전부에서 `[data-testid=decision-card]` **미렌더**(`decisionCard:false`). jobId 4 의 `해상도 480p` 탭에서도 동일. 판정 로직은 `AugmentResultPanel.tsx:123-128`(`isResolution` + `reviewable`) → `:237-252` 조건부 렌더. JobCard 에는 원래 결정 액션 없음(`JobCard.tsx` 전체에 accept/reject 부재) 확인. ⚠ **근거 드리프트**: 카탈로그 `AugmentResultPage.tsx:419-421,452-461`(해당 파일은 422줄이고 DecisionCard 배선이 없음) → 실제 `features/augment/components/AugmentResultPanel.tsx:123-128,237-252` |
| TC-FE-161 | PASS | [실동작] | jobId 906·900(`types=[]`, `resolutionTypes=["RESL_480P"]`/`["RESL_720P"]`) 카드에 해상도 배지 + **"파생"** 구분자 동시 렌더. ⚠ **근거 드리프트**: `JobCard.tsx:75-83` → 실제 `:91-98` |
| TC-FE-162 | PASS | [실동작] | jobId 18 `야간` 탭 = `data-decision="PENDING"` + [채택]/[거부] 버튼 → [채택] 클릭 → **`data-decision="ACCEPTED"` + "채택됨 / 결정 일시: 2026. 8. 2. 오후 6:42:53"**, 토스트 "채택 처리됨". 훅 `useAugmentDecision.ts:57-67`(성공 시 `AUGMENT_KEYS.all` 무효화) |
| TC-FE-163 | PASS | [실동작] | jobId 4 `비 #2`(PENDING) → [거부] → `role=dialog` 에 "거부 사유 입력 / 이 증강 결과를 거부하는 사유를 입력하세요. / 거부 사유 / 취소 / 거부 확정". 사유 입력 후 [거부 확정] → `data-decision="REJECTED"` 전이 확인 |
| TC-FE-164 | PASS | [실동작] | 거부 사유에 `<img src=x onerror="window.__xss=1">QA검증` 입력·저장 → `[data-testid=decision-reject-reason]` 의 `innerHTML` = `거부 사유: &lt;img src=x onerror="window.__xss=1"&gt;QA검증`(escape), 주입된 `img` 0개, `window.__xss` 미정의 → **스크립트 미실행**. 코드에도 `dangerouslySetInnerHTML` 실사용 0건(주석뿐) |
| TC-FE-209 | PASS | [실동작] | jobId 81(RESL_*) 결과 화면의 비교 이미지가 전부 `src="blob:http://localhost:13000/…"` (raw `<img src="/v1/frames/…">` 아님), `alt`="원본 프레임 0" / "해상도 1080p 프레임 0". `FrameGrid12`·`SideBySideCompare` 에 `authImages` prop 전달(`AugmentResultPanel.tsx:175,194`) → `AuthImage`(Bearer blob). ⚠ **근거 드리프트**: `AugmentResultPage.tsx:425-450` → 실제 `AugmentResultPanel.tsx:170-196`. `FrameGrid12.tsx:156-167` → 실제 `:158`(근사), `SideBySideCompare.tsx:88-98` → `:90`(정확) |
| TC-FE-210 | PASS | [실동작] | jobId 4 `겨울 #1`(totalFramePairs=30) → `[data-testid=augment-frame-pager]` 노출, 버튼 `1/2/3`. 2페이지 클릭 시 그리드가 `frame-pair-351`~`362`(다음 12쌍)로 교체 → **접근 불가 프레임 0**. `page`/`size` 는 `getAugmentResult` 쿼리 파라미터(기본 `FRAME_PAGE_SIZE=12`). ⚠ **근거 드리프트**: `AugmentResultPage.tsx:33,414-441` → 실제 `AugmentResultPanel.tsx:30(FRAME_PAGE_SIZE),113,177-186` |
| TC-FE-211 | PASS | [실동작] | jobId 4 에서 `겨울 #1` 프레임 2페이지로 이동한 뒤 `해상도 480p` 탭 클릭 → framePage 리셋되어 `frame-pair-145`~`156`(그 항목의 1페이지) 표시, 빈 그리드 없음. 마운트 시점 리셋 안 함(`prevIdRef` 비교) + 안전망 `framePage>0` 이면 페이저 유지(`AugmentResultPanel.tsx:113`) 확인. ⚠ **근거 드리프트**: `AugmentResultPage.tsx:344-349,418` → 실제 `AugmentVideoSection.tsx:59-64` + `AugmentResultPage.tsx:120-124`(항목 페이저 전환 시 리셋) |
| TC-FE-212 | PASS | [실동작] | 탭 라벨·슬롯 라벨·요약이 모두 "해상도 1080p / 해상도 720p / 해상도 480p"(기술코드 `RESL_*` 노출 0). `augTypeLabel.ts:30-36` 근거 라인 **정확**. 미지 코드→`'증강'`, `RESL_` 접두→`resolutionDerivativeLabel` 폴백 구현 확인 |
| **TC-FE-213** | **PARTIAL** | [실동작] | ①`augment-result-completed-empty` 미노출 = **충족**(jobId 18·81 모두 COMPLETED + results 존재 시 미렌더, `AugmentResultPage.tsx:263-274`) ②"총 처리 이미지 = 페이징 전 전체 쌍 수" = **충족**(라벨명만 "비교 프레임 쌍"으로 바뀜. jobId 4 = 120쌍 = 현재 항목 페이지 항목들의 `totalFramePairs` 합, jobId 81 = 15쌍 = 5+5+5. `totalPairsOf()` 가 `totalFramePairs` 사용 — `resultView.ts:44-47`) ③**"라벨 무결성 카드 표시" = 불충족** — 그 카드는 더 이상 없다. `augment-result-integrity` 는 **"증강 이미지 생성률"** 로 이름·의미가 바뀌었고(분모 = 현재 프레임 페이지에 로드된 쌍), **`status==='COMPLETED' && hasFramePairs` 일 때만** 렌더된다. 프레임 쌍 0인 COMPLETED 잡(jobId 18)에서는 카드 자체가 없다 → 카탈로그 기대값이 구현에 뒤처짐(H-ISSUE-104). ⚠ 근거 드리프트: `:36-38,89-99,242-254` → 실제 `:263-274`(empty), `:282-296`(생성률), `:186-196`(쌍 수) |
| **TC-E2E-007** | **PARTIAL** | [실동작]+[정적] | 수동 실동작으로는 PASS(TC-FE-162 참조). 그러나 **자동 스펙이 사실상 아무것도 단언하지 않는다** — `e2e/specs/augment-decision.spec.ts:6` 이 `/augment/result/3001` 로 이동하는데 실 DB 에 jobId 3001 이 없어 BE 가 `{status:"PROCESSING", results:[]}` 를 주고, 스펙은 `if ((await adoptBtn.count()) > 0)` 가드 안에서만 단언하므로 **무단언 통과**(H-ISSUE-102) |

---

## 2. H-11. 포털 화면 (자산 업로드 + 수동 라벨링) (22건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|------|------|
| TC-FE-165 | PASS | [정적]+[테스트] | `features/portal/uploads/validation.ts:33-48` — 확장자 allowlist(`IMAGE_EXTENSIONS=['jpg','jpeg','png']`) 위반 파일 제외 + `IMAGE_POLICY_TEXT` 정책 안내 동봉. 화면 실측: `/portal/uploads` 안내문 "허용: jpg/jpeg/png · 개당 최대 20MB · 요청당 최대 50장 (최종 검증은 서버가 수행합니다)". 단위테스트 `validation.test.ts:21,28` |
| TC-FE-166 | PASS | [정적]+[테스트] | `validation.ts:10(MAX_IMAGE_BYTES=20*1024*1024),43-45` → "크기가 20MB를 초과했습니다." + 정책문. 테스트 `validation.test.ts:36` |
| TC-FE-167 | PASS | [정적]+[테스트] | `validation.ts:12(MAX_IMAGE_COUNT=50),50-56` → 초과 안내 후 `accepted.slice(0,50)`. 테스트 `validation.test.ts:42` |
| TC-FE-168 | PASS | [정적]+[테스트] | `pages/portal/PortalUploadPage.tsx:77-87`(근거 라인 **정확**) — `uploadAsync` 성공 후 `setSelected([])`·`setValidationErrors([])`·`imageInputRef.current.value=''`. 테스트 `PortalUploadPage.test.tsx:72`. (실브라우저 파일 선택은 공유 브라우저 경합으로 미수행) |
| TC-FE-169 | PASS | [실동작]+[정적] | 화면 실측: `#portal-video-input` `accept="video/mp4,video/quicktime,video/x-msvideo,.mp4,.mov,.avi"`, 안내 "mp4/mov/avi · 최대 5GB (재개 가능 업로드)". 배선 `PortalUploadPage.tsx:35(PORTAL_TUS_ENDPOINT='/portal/uploads/tus'),89-100` → `useTusUpload({endpointBase})`(`features/upload/hooks/useTusUpload.ts:29-31,34-35`) |
| TC-FE-170 | PASS | [정적]+[테스트] | `PortalUploadPage.tsx:270-271`(`isProcessing` 판정, 근거 정확) → `:310 disabled={deleting||isProcessing}` + `:311 title="처리 중 자산은 삭제할 수 없습니다."`. 테스트 `PortalUploadPage.test.tsx:156`. ⚠ 실동작 미확인 — 이미지 업로드가 즉시 READY 로 끝나 PROCESSING 상태 자산을 만들 수 없었다 |
| TC-FE-171 | PASS | [정적]+[테스트] | `PortalUploadPage.tsx:48-53`(근거 정확) — 409/`ErrorCode.CONFLICT` 만 "처리 중 자산은 삭제할 수 없습니다.", 그 외 일반 문구. **BE 원문 메시지·스택 미노출**(CWE-209 방어). 테스트 `PortalUploadPage.test.tsx:167` |
| TC-FE-172 | PASS | [실동작] | `/portal/uploads` 에 READY 자산 2건 → 각 항목에 `<a href="/portal/uploads/80/label">라벨링</a>`, `/portal/uploads/81/label`. 근거 `:296-306` 정확 |
| TC-FE-173 | PASS | [정적]+[테스트] | `PortalUploadPage.tsx:288-292`(정확) — `isFailed` 시 `failRsnCn ?? 기본 문구`. 테스트 `PortalUploadPage.test.tsx:178`. (FAILED 자산 미보유로 실동작 미확인) |
| TC-FE-174 | PASS | [실동작] | 파일명 `qa <img src=x onerror=alert(1)>.jpg` 로 업로드 → 목록 `innerHTML` = `qa &lt;img src=x onerror=alert(1)&gt;.jpg`(escape), `li img` 0개, alert 미발화. `aria-label` 도 텍스트 속성으로만 사용. 근거 `:280-281` 정확 |
| TC-FE-175 | PASS | [실동작] | `/portal/uploads/80/label` 진입 → `role=toolbar` 버튼 = **선택 / 이동 / 바운딩 박스 / 폴리곤 4종뿐**, `<canvas>` 렌더(CanvasShell). 본문에 "AI 탐지/AI 분할/AI 추적/스켈레톤/오토라벨" 문구 0건, "검수/버전/승인/반려" 0건. 근거 `PortalUploadLabelingPage.tsx:43-49(UPLOAD_TOOLS)` |
| TC-FE-176 | PASS | [실동작] | `/portal` 진입(PORTAL_USER) → "AI 학습데이터 작성 포털" + "데이터마트 영상" 목록 12건 노출(BE `GET /v1/portal/datamart/videos` totalElements=20), 각 영상 선택 버튼 + "라벨링 가능 20건 / 시작하기" 카드. 상단 내비는 `/portal`·`/portal/uploads` **2개뿐**(내부 화면 진입점 0) |
| TC-FE-177 | PASS | [실동작] | 포털 저장 경로 실왕복: `POST /v1/portal/user-labels {sourceRawSn:94, sourceSrcSn:448, BBOX}` → `userLblSn=20` 적재, `GET /v1/portal/user-labels?rawSn=94` 에 1건. **동일 시점 내부 라벨 `GET /v1/frames/448/labels` = items 0건 · labelVersion 0 (변화 없음)** → 원본(LS_DATA_LBL) 미수정 확인. 훅 `useSavePortalLabels.ts:78-112`(busy 'SAVE' 배타 + 폐기 시 성공 후처리 skip) |
| TC-FE-271 | PASS | [정적]+[테스트] | `PortalUploadLabelingPage.tsx:370` `<BusyOverlay kind={busyKind} startedAt={busyStartedAt} onCancel={cancelBusy} />`, busy 는 프레임 스코프(`isEditBlockedState(s, uldFrmeSn)`, `:100-106`)로 필터. 취소 시 dirty 유지(성공 후처리 미실행 — 아래 272). 테스트 `PortalUploadLabelingBusy.test.tsx:146` |
| TC-FE-272 | PASS | [정적]+[테스트] | `features/portal/uploads/hooks/useSaveUploadLabels.ts:85-98`(근거 **정확**) — `runExclusiveOrNotify` 의 `isAlive()` 가드로 캐시 무효화 차단, `onSuccess(result)` 가 `result===null` 이면 즉시 return → `clearDirty()` 미호출. 테스트 `PortalUploadLabelingBusy.test.tsx:173` |
| TC-FE-273 | PASS | [정적]+[테스트] | `features/label/busyPolicy.ts:69 BUSY_OVERLAY_DELAY_MS = 300`(근거 **정확**) + `isBusyOverlayVisible` 파생. 테스트 `PortalUploadLabelingBusy.test.tsx:191` |
| TC-FE-274 | PASS | [실동작] | `/portal/uploads/80/label` 전체 버튼 = 내보내기(JSON)·원본 다운로드·선택·이동·바운딩 박스·폴리곤·저장. **AI 진입점 0** → 관측 가능한 busy 는 SAVE 뿐. 테스트 `PortalUploadLabelingBusy.test.tsx:197`. ⚠ 근거 드리프트: 카탈로그 `:42-48` → 실제 `:43-49` |
| **TC-FE-275** | **PARTIAL** | [실동작] | 경계 구분 자체는 **충족** — `/portal/label/448`(데이터마트 영상)은 도구 = 선택·바운딩 박스·폴리곤·**AI 분할·AI 추적·스켈레톤**, **AI 탐지 없음**(`DarkToolbar.tsx:118-132 portalHidden:true`, 필터 `:141-147`). `/portal/uploads/:uldSn/label`(본인 업로드)은 AI 0. 양쪽 모두 저장 배타·오버레이·취소를 store busy 단일 축으로 공유. **그러나 `PORTAL_HIDDEN_TOOLS` 가 빈 배열이라 AI 분할·추적·스켈레톤이 포털에 노출되는 것은 `CLAUDE.md`/ADR-013 "포털 오토라벨링(YOLO/SAM2)·VLM 미제공" 위반**이며 `UNCERTAINTIES.md` #1 이 **결함(FAIL) 유지**로 확정한 항목 → 이월(H-ISSUE-103) |
| TC-E2E-008 | PASS | [실동작] | PORTAL_USER 토큰으로 `/portal` 진입 → `location.pathname='/portal'`, h1 "AI 학습데이터 작성 포털" |
| TC-E2E-009 | PASS | [실동작] | PORTAL_USER 가 `/dashboard` 진입 시도 → `/forbidden` ("이 화면에 접근할 수 없습니다 / 현재 역할: 포털") |
| TC-E2E-010 | PASS | [실동작] | `/manage/users` → `/forbidden`. 추가 반증: `/review/pending`·`/augment` 도 `/forbidden`, `/portal/uploads` 는 정상 진입 → 가드가 경로 축으로 정확히 동작 |
| **TC-E2E-011** | **FAIL** | [실동작]+[정적] | 화면 자체는 정상(`/portal/uploads` 에 이미지·영상 파일 입력 + 정책 안내가 실제로 있음 — TC-FE-165/169 참조). 그러나 **E2E 스펙이 업로드 화면을 겨냥하지 않는다** — `e2e/pages/PortalHomePage.ts:19-25 goto()` 가 `/portal` 로만 이동하는데 `PortalHomePage.tsx`(134줄)에는 `input[type=file]` 도 `data-testid="upload-dropzone"` 도 **없다**(실브라우저 실측 및 grep 확인. `upload-dropzone` 문자열은 `PortalHomePage.test.tsx:51` 의 "없어야 한다" 단언에만 존재). 따라서 `portal-upload.spec.ts:10-18` 의 `expect(visible).toBe(true)` 는 **거짓**이 되어야 하고, `:20-38` 의 업로드 시도는 `if (count>0)` 가드로 무단언 통과한다(H-ISSUE-101) |

---

## 3. 이슈 기록

### [H-ISSUE-101] TC-E2E-011 — 포털 업로드 E2E 스펙이 업로드 화면이 아닌 포털 홈을 겨냥한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `/portal/uploads` 의 파일 선택 영역 노출과 실제 업로드 흐름이 E2E 로 회귀 보호돼야 한다. ADR-013 예외로 신설된 포털 자산 업로드는 내부 파이프라인과 분리된 별도 경로라 단위테스트만으로는 라우팅·가드·화면 조립이 보장되지 않는다.
- **현재 동작(이슈 내용)**:
  - `frontend/e2e/pages/PortalHomePage.ts:19-25`
    ```ts
    async goto() {
      await this.page.evaluate(() => {
        if (window.location.pathname !== '/portal') {
          window.history.pushState({}, '', '/portal');   // ← 업로드 화면이 아니라 포털 홈
    ```
  - `frontend/e2e/specs/portal-upload.spec.ts:10-18` 이 그 POM 으로 이동한 뒤 `expect((await home.dropzone.count()) > 0 || (await home.fileInput.count()) > 0).toBe(true)` 를 단언한다.
  - 실측(Playwright, PORTAL_USER): `/portal` 본문에 `input[type=file]` 0개(버튼만: "시작하기 ▶", 영상 선택 카드 12개). `grep -rn "upload-dropzone" frontend/src` 결과는 `pages/portal/__tests__/PortalHomePage.test.tsx:51`(= `toBeNull()` 단언) 1건뿐.
  - `frontend/src/pages/portal/PortalHomePage.tsx`(134줄)에 `input` 문자열 0건.
  - 두 번째 테스트(`:20-38`)는 `if ((await home.fileInput.count()) > 0)` 안에서만 단언하므로 파일 입력이 없으면 **아무 것도 검증하지 않고 통과**한다. fixture `e2e/fixtures/sample.jpg.txt` 도 첫 줄이 `e2e mock placeholder file (not a real jpg)` 라 실제 이미지가 아니다(BE 매직바이트 검증 통과 불가).
- **재현/확인 경로**: `cd frontend && npx playwright test e2e/specs/portal-upload.spec.ts` → 첫 테스트 실패 예상. 또는 브라우저로 `/portal` 진입 후 `document.querySelectorAll('input[type=file]').length` → `0`.
- **영향**: 포털 업로드 화면의 E2E 회귀 보호가 **0**이다. 라우팅·`ChannelGuard`·업로드 UI 조립이 깨져도 CI 가 잡지 못한다(기능 결함은 아니며 테스트 자산 결함).
- **수정 방향(제안)**: ① `PortalHomePage` POM 을 그대로 두고 `PortalUploadPage` POM 을 신설해 `/portal/uploads` 로 이동시키거나, `portal-upload.spec.ts` 가 직접 `/portal/uploads` 로 이동하도록 변경 ② `input#portal-image-input` / `input#portal-video-input` 을 명시 로케이터로 사용 ③ `if (count>0)` 가드를 제거하고 무조건 단언 ④ fixture 를 실제 최소 JPEG(매직바이트 `FFD8FF`) 로 교체.

### [H-ISSUE-102] TC-E2E-007 — 증강 채택 E2E 가 존재하지 않는 jobId 를 대상으로 해 무단언 통과한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: PENDING 증강 결과를 채택하면 ACCEPTED 로 전이되는 흐름이 E2E 로 보호돼야 한다(REVIEWER 검수 결정 경로).
- **현재 동작(이슈 내용)**:
  - `frontend/e2e/specs/augment-decision.spec.ts:6-18`
    ```ts
    await reviewerPage.goto('/augment/result/3001');
    const adoptBtn = reviewerPage.getByRole('button', { name: /채택/ });
    if ((await adoptBtn.count()) > 0) {   // ← 0 이면 통째로 스킵
      await adoptBtn.first().click();
    ```
  - 실측: `GET /api/v1/augments/3001/result` → `{"jobId":3001,"status":"PROCESSING","results":[],...}`. 결과 항목이 0건이라 DecisionCard 자체가 렌더되지 않고 `adoptBtn.count()===0` → 단언 미실행.
  - 실 DB 의 잡 ID 는 `4·18·26·50·80·81·900·906·77777777` 이며 3001 은 없다(`GET /v1/augments` 실측 totalElements=9).
- **재현/확인 경로**: `curl -s localhost:18081/api/v1/augments/3001/result -H "Authorization: Bearer <REVIEWER JWT>"`
- **영향**: 채택 상태전이 회귀가 CI 에서 잡히지 않는다(수동 실동작 검증에서는 정상 동작 확인됨 — TC-FE-162).
- **수정 방향(제안)**: 스펙 안에서 `POST /v1/augments/request` 로 PENDING 항목을 만들거나 시드 고정 잡을 쓰고, `if (count>0)` 가드를 제거해 `expect(adoptBtn).toBeVisible()` → 클릭 → `expect(getByText('채택됨')).toBeVisible()` 로 무조건 단언.

### [H-ISSUE-103] TC-FE-275 — 포털 데이터마트 라벨링에 AI 분할·추적·스켈레톤 도구가 노출된다 (ADR-013 위반, 이월)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` "포털 — 오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공" + ADR-013 에 따라 포털 채널에서는 AI 보조 도구가 제공되지 않아야 한다. `UNCERTAINTIES.md` #1 이 "문서가 정본, 노출은 정책 위반 → 결함 플래그 유지"로 확정.
- **현재 동작(이슈 내용)**: `frontend/src/features/label/components/DarkToolbar.tsx:103-105,141-147`
  ```ts
  // Phase 9 — 포털에 SAM 분할/추적·키포인트 도구 제공(PORTAL_HIDDEN_TOOLS 현재 비어있음).
  ...
  if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);
  ```
  실측(PORTAL_USER, `/portal/label/448`): `role=toolbar` 버튼 = `선택, 바운딩 박스, 폴리곤, AI 분할, AI 추적, 스켈레톤, 삭제, 실행 취소, 화면 맞춤, 저장`. 단축키 안내도 노출(`AI 분할 G`, `AI 추적 Shift+T`, `스켈레톤 K`). AI 탐지(YOLO)만 `portalHidden:true` 로 숨겨짐.
- **재현/확인 경로**: PORTAL_USER JWT 로 `/portal/label/{srcSn}` 진입 → 좌측 도구바에 "AI 분할"·"AI 추적"·"스켈레톤" 버튼 확인.
- **영향**: 범위 정책 위반(요구사항 불일치). 포털 사용자가 ai-server 추론 자원을 소비할 수 있다(외부 채널 자원 소모 + `UNCERTAINTIES` #12 에 따라 해당 경로에 rate limit 부재). 전송 픽셀은 비식별본(`encodeDeidentifiedFrameForInference`)이고 신고 게이트도 적용되므로 PII 노출 위험은 완화된 상태.
- **수정 방향(제안)**: `PORTAL_HIDDEN_TOOLS` 에 `SAM_SEGMENT`·`TRACK`·`KEYPOINT` 를 추가하고 `useLabelingShortcuts` 의 포털 게이팅 정책 소스와 동기화. **또는** 정책을 바꿀 거라면 `CLAUDE.md`·ADR-013·`UNCERTAINTIES.md` #1 을 먼저 갱신(문서가 정본이므로 코드 단독 선행 금지).

### [H-ISSUE-104] TC-FE-213 — 카탈로그 기대값 "라벨 무결성 카드"가 구현에 존재하지 않는다 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 기대결과가 현재 구현을 정확히 서술해야 다음 회차 재검증이 성립한다.
- **현재 동작(이슈 내용)**: `frontend/src/pages/AugmentResultPage.tsx:276-296`
  ```tsx
  {summary.status === 'COMPLETED' && summary.hasFramePairs && (
    <div ... data-testid="augment-result-integrity">
      <p ...>증강 이미지 생성률</p>
      ...현재 화면에 표시된 프레임 {summary.loadedPairs}쌍 기준입니다.
  ```
  "라벨 무결성"이라는 이름·개념은 코드에서 제거됐고(주석에 "라벨을 검사한 값이 아니어서 구 이름은 사실과 달랐다"고 명시), 카드는 **프레임 쌍이 있는 COMPLETED 잡에서만** 렌더된다. 실측: jobId 81 → "증강 이미지 생성률 100% / 현재 화면에 표시된 프레임 15쌍 기준입니다.", jobId 18(쌍 0건) → 카드 없음. 또 "총 처리 이미지"는 "비교 프레임 쌍"으로 개명됐다(값 자체는 기대대로 페이징 전 전체 쌍 수).
- **재현/확인 경로**: `/augment/result/18` 진입 → `document.querySelector('[data-testid=augment-result-integrity]')` → `null`.
- **영향**: 케이스를 문자 그대로 판정하면 오검(FAIL)이 난다. 기능 결함 아님.
- **수정 방향(제안)**: `H-frontend-e2e.md` TC-FE-213 기대결과를 "`augment-result-completed-empty` 미노출 + (프레임 쌍이 있을 때만) `augment-result-integrity`= '증강 이미지 생성률' 카드 표시 + '비교 프레임 쌍' = 페이징 전 전체 쌍 수"로 갱신.

### [H-ISSUE-105] H-10/H-11 근거 `file:line` 드리프트 8건 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 링크가 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: 아래 8건이 어긋난다. 특히 TC-FE-160/209/210/211 은 **파일 자체가 바뀌었다**(증강 결과 화면이 `AugmentResultPage` → `AugmentVideoSection` → `AugmentResultPanel` 로 분해되면서 프레임 페이징·DecisionCard·authImages 배선이 전부 패널로 이동. `AugmentResultPage.tsx` 는 422줄이라 `452-461` 은 존재하지 않는 라인이다).

  | TC | 카탈로그 근거 | 실제 위치 |
  |----|------|------|
  | TC-FE-159 | `JobCard.tsx:64-72` | `JobCard.tsx:81-89` |
  | TC-FE-160 | `AugmentResultPage.tsx:419-421,452-461` | `features/augment/components/AugmentResultPanel.tsx:123-128,237-252` |
  | TC-FE-161 | `JobCard.tsx:75-83` | `JobCard.tsx:91-98` |
  | TC-FE-209 | `AugmentResultPage.tsx:425-450` · `FrameGrid12.tsx:156-167` | `AugmentResultPanel.tsx:170-196` · `FrameGrid12.tsx:158`(`SideBySideCompare.tsx:90` 은 정확) |
  | TC-FE-210 | `AugmentResultPage.tsx:33,414-441` | `AugmentResultPanel.tsx:30,113,177-186` |
  | TC-FE-211 | `AugmentResultPage.tsx:344-349,418` | `AugmentVideoSection.tsx:59-64` · `AugmentResultPage.tsx:120-124` |
  | TC-FE-213 | `AugmentResultPage.tsx:36-38,89-99,242-254` | `AugmentResultPage.tsx:186-196,263-274,282-296` |
  | TC-FE-274 | `PortalUploadLabelingPage.tsx:42-48` | `PortalUploadLabelingPage.tsx:43-49` |

  정확했던 근거(참고): `augTypeLabel.ts:30-36` · `useResolutionDerivative.ts:17-27` · `validation.ts:33-49/9,44/12,50-56` · `PortalUploadPage.tsx:48-53,77-87,270-271,288-292,296-306,280-281` · `useSaveUploadLabels.ts:85-98` · `busyPolicy.ts:69` · `SideBySideCompare.tsx:88-98`.
- **재현/확인 경로**: 각 파일을 Read 해 라인 대조.
- **영향**: 검증자가 매번 Grep 으로 재탐색해야 한다. 기능 결함 아님.
- **수정 방향(제안)**: `H-frontend-e2e.md` H-10/H-11 근거 컬럼을 위 표대로 갱신.

---

## 4. 확증편향 반증 시도 기록 (요구 지시 대응)

| 반증 대상 | 시도 | 결과 |
|------|------|------|
| **증강 폐기/복구가 실제로 도는가** | jobId 4 `비 #2` 를 UI 로 거부(REJECTED) → `[data-testid=decision-restore]` 클릭 → 복구 사유 모달("결과물을 다시 활용 결정 대기로 되돌립니다…") 입력 → [복구 확정] | **정상 동작** — `data-decision` REJECTED→**PENDING**, 탭 라벨도 "비 #2 · 거부됨"→"비 #2 · 활용 결정 대기", 토스트 "복구 처리됨". `restoreEligible=false` 인 항목(id 3 = GENERATION_FAILED, id 14 = ACCEPTED)에는 복구 버튼이 아예 안 뜬다(fail-closed 확인) |
| **진행률이 자체 생성(self-fill)인가** | jobId 4 항목 14 의 `augment-progress-*` 실측 | **BE 실값** — "진행 상태 완료 / 진행률 100% / 처리 완료 1 / 1건". `AugmentResultPage.tsx:200-206` 주석대로 잡 단위 가짜 진행률(0/50/100)을 그리지 않고 항목별 `GET /v1/augments/{id}/progress` 를 쓴다. 해상도 파생 항목은 `enabled={!isResolution}` 으로 폴링 자체를 안 함 |
| **생성 조건(prompt)도 self-fill 인가** | 같은 항목 `augment-prompt-14` | **BE 값 그대로** — `{"time":"NIGHT","season":"WINTER","weather":"RAIN","terrain":"ROAD","severity":"HIGH"}` 를 "시간대 NIGHT / 계절 WINTER / …" 로 표시. 값 없는 항목(`prompt:null`)은 카드를 그리지 않음 |
| **포털에 오토라벨/검수/버전관리 버튼이 정말 없는가** | `/portal`·`/portal/uploads`·`/portal/uploads/80/label` 3화면 본문 정규식 검사 | 업로드 라벨링: `AI 탐지\|AI 분할\|AI 추적\|스켈레톤\|오토라벨` **0건**, `검수\|버전\|승인\|반려` **0건**. 포털 홈 내비 링크 = `/portal`·`/portal/uploads` 2개뿐. ⚠ **단 `/portal/label/:srcSn`(데이터마트 영상)에는 AI 분할·추적·스켈레톤이 노출** → H-ISSUE-103 |
| **해상도 파생에 채택/거부가 새는 경로가 있는가** | jobId 81 3탭 + jobId 4 의 RESL 3탭 전수 + JobCard 전체 코드 | `decision-card` 0건. BE 도 `reviewable=false` 로 내려줌. 다만 `GET /v1/augments/4/result` 실측상 RESL 항목의 `decision` 은 `ACCEPTED` 로 채워져 내려오는데(내부 생성물 라이프사이클), 화면은 `isResolution` 우선 판정이라 표시되지 않는다 — 의도된 동작 |
| **프레임 페이저로 도달 불가한 프레임이 있는가** | 30쌍 항목에서 페이지 1→2 이동, 12쌍 슬라이스 경계 확인 | 1페이지 `frame-pair-339~350`, 2페이지 `frame-pair-351~362`, 페이저 버튼 `1/2/3` → 30쌍 전부 도달 가능 |
| **탭 전환 시 빈 그리드에 갇히는가** | 30쌍 항목 2페이지 → 5쌍짜리 480P 탭 전환 | framePage 리셋되어 정상 렌더(갇힘 없음). 안전망 `emptyFramePage` + "첫 페이지로" 버튼도 코드상 존재(`AugmentResultPanel.tsx:198-228`) |
| **XSS 가 실제로 실행되는가** | ①증강 거부 사유 `<img src=x onerror="window.__xss=1">` ②포털 업로드 파일명 `qa <img src=x onerror=alert(1)>.jpg` | 둘 다 **escape 되어 텍스트로 렌더**, DOM 에 주입 `img` 0개, 스크립트 미실행 |
| **포털 저장이 원본을 건드리는가** | 포털 user-label 저장 전/후 내부 `GET /v1/frames/448/labels` 대조 | items 0 → 0, labelVersion 0 → 0 (**불변**). 포털 데이터는 `LS_PORTAL_USER_LABEL` 에만 적재 |
| **포털 채널 가드가 경로별로 다 도는가** | `/dashboard`·`/manage/users`·`/review/pending`·`/augment` 4경로 시도 | 전부 `/forbidden`. 포털 전용 `/portal`·`/portal/uploads` 만 통과 |
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
