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
