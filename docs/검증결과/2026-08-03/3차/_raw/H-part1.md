# H 클러스터 part1 — H-1(인증/라우팅 가드) + H-14(보안 XSS/토큰/용어정책)

> 3차 회차 · 2026-08-03 · 대상 33건 (H-1 26건 + H-14 7건)
> 카탈로그: `docs/test-cases/H-frontend-e2e.md` 21~51행 · 415~426행

## 0. 검증 환경 (실측)

| 항목 | 실측값 |
|---|---|
| 워크트리 / HEAD | `qa-0803` / `e065da42` |
| FE (docker, Vite **dev** 서버) | `http://localhost:13000` (내부 5174) → 200. ⚠ PM 지시문의 `:5173` 은 이 환경에 없음(`curl :5173` → 000) |
| BE | `http://localhost:18081/api` → `/api/actuator/health` UP |
| FE 컨테이너 실효 env | `docker inspect klid-frontend` → `VITE_API_BASE_URL=/api/v1`, **`VITE_TOKEN_INGRESS=all`**, **`VITE_CONTROL_LOGIN_URL=`(빈값)**, **`VITE_PORTAL_LOGIN_URL=`(빈값)** |
| FE 자동테스트 baseline | `_raw/test-baseline.md` — **2064/2064 PASS, 실패 0** |
| 브라우저 | Playwright(chromium, `frontend/node_modules/playwright-core`) 독립 컨텍스트 18+5개 프로브. **Playwright MCP 공용 브라우저는 타 검증 에이전트가 동시 사용 중이라 사용 불가**(탭 1개를 공유해 내비게이션이 서로 덮임 — 실측 확인). |

### ★ 상위 로그인 redirect 실동작 관측 방법 (중요)
현재 docker FE 는 `VITE_CONTROL_LOGIN_URL` 이 **의도적으로 빈 값**(dev fail-open, `docker-compose.yml:326-330` 주석 명시)이라 그대로는 redirect 를 관측할 수 없다.
그래서 **동일 소스에서 임시 Vite dev 서버 1개를 별도 포트(127.0.0.1:5199)로 기동**해
`VITE_CONTROL_LOGIN_URL=http://control.local/login VITE_PORTAL_LOGIN_URL=http://portal.local/login` 를 주입하고,
Playwright `route()` 로 상위 로그인 도메인을 스텁해 **최종 이동 URL 을 그대로 관측**했다.
- 코드·설정 파일은 **일절 수정하지 않았다**(환경변수만 셸에서 주입, 프로세스는 검증 후 종료).
- 빌드·테스트 스위트는 실행하지 않았다(dev 서버 기동 + 브라우저 프로브만).

---

## 1. ★★ 2차 HIGH 2건 해소 여부 (최우선)

### ✅ 2차 HIGH #7 = `[H-ISSUE-02]` 상위 로그인 URL 배포 미주입 → **해소 (실동작 확증)**

**(1) 배포 배선이 3개 진입점 전부에 들어갔다** — 2차의 "전수 grep 0건" 이 전부 뒤집혔다.

```
frontend/Dockerfile:40-48
  ARG VITE_CONTROL_LOGIN_URL= / ARG VITE_PORTAL_LOGIN_URL=
  ENV ... (build 스테이지)
  RUN test -n "$VITE_CONTROL_LOGIN_URL" && test -n "$VITE_PORTAL_LOGIN_URL" \
    || (echo "FATAL: ... 필수 (--build-arg 로 전달)" >&2 && exit 1)      ← fail-closed
docker-compose.yml:312-313(build args) · 329-330(environment)
deploy/onprem/scripts/lib/common.sh:62-79  require_upstream_login_urls()  ← 빈값·스킴누락 시 die
deploy/onprem/scripts/package/20-build-frontend.sh:39-41  export + require_upstream_login_urls
deploy/onprem/scripts/install/build-from-source.sh:189-191 (동일)
deploy/onprem/docs/04-configuration.md:196-197  ★필수 항목으로 등재
frontend/src/test/upstreamLoginUrlDeployWiring.test.ts  ← 3진입점 회귀 가드(실제 bash 로 가드 실행)
```

**(2) 복귀 URL(`next=`)이 실제로 주입된다** — `redirectToUpstream.ts:21-29,48-52` 에 `buildNextUrl()` 신설(토큰 쿼리 3종 제거 후 인코딩, 파싱 실패 시 fail-closed 로 next 생략).

**실동작 증거 (임시 dev 서버 :5199, 상위 도메인 스텁):**

```
# Q5 — 세션 도중 만료(RoleGuard useEffect 경로)
  최종 URL : http://control.local/login?next=http%3A%2F%2F127.0.0.1%3A5199%2Fvideo%2Fcompleted%3Fpage%3D2
  sessionStorage['klid_jwt'] : null   ← clear() 동작
  → 쿼리스트링(page=2)까지 보존된 복귀 URL 이 주입됨

# Q4 — 401 응답(client.ts:72-75 경로)
  최종 URL : http://control.local/login?next=http%3A%2F%2F127.0.0.1%3A5199%2Fdashboard
```

> ⚠ **잔여 조건부 갭(신규 이슈 아님, H-ISSUE-02 로 별도 기록)**: 위 두 경로(RoleGuard 만료·401)는 dev 빌드에서도 상위 로그인으로 나간다. 반면 **`/ingress` 진입 실패 경로만** `isDevLoginEnabled()` 가 먼저 걸려 `/dev/login` 으로 빠지는데(`SessionIngressPage.tsx:41-44`), **온프렘 패키징 기본값이 `VITE_DEV_LOGIN_ENABLED=true`**(2차 H-ISSUE-03 미해소 이월)라 그 산출물에서는 "토큰 없이 진입" 이 상위 로그인으로 가지 않는다 → TC-E2E-001 PARTIAL 사유.

### ❌ 2차 HIGH #8 = `[H-ISSUE-01]` `?token=` URL 인계 채널 → **미해소 (실동작 재현)**

운영 기본값이 여전히 전부 `all` 이고, **실브라우저에서 `?token=` 만으로 인증이 성립**한다.

```
frontend/Dockerfile:33            ARG VITE_TOKEN_INGRESS=all
docker-compose.yml:325            VITE_TOKEN_INGRESS: ${VITE_TOKEN_INGRESS:-all}
.env.example:141                  VITE_TOKEN_INGRESS=all
deploy/onprem/scripts/package/20-build-frontend.sh:32       :-all
deploy/onprem/scripts/install/build-from-source.sh:185      :-all
deploy/onprem/docs/02-build-package.md:30 · 08-build-from-source.md:79 · 04-configuration.md:195  (문서도 all)
docker inspect klid-frontend  → VITE_TOKEN_INGRESS=all        (실효값)
```

실동작 (P2/P3/P4 — 3채널 전부 열려 있음):

```
P2  GET /ingress?token=<REVIEWER JWT>  → 최종 http://localhost:13000/dashboard
    sessionStorage['klid_jwt'] = eyJhbGciOiJIUzI1NiJ9...   ← URL 채널로 인증 성립
    화면: "학습데이터 저작도구 / 검수자 김 김검수 / 대시보드 ..." 정상 렌더
P3  localStorage['klid-jwt-token'] 설정 후 GET /ingress (쿼리 없음) → /dashboard 인증
P4  cookie klid_jwt 설정 후 GET /ingress → /dashboard 인증
```

→ `CLAUDE.md` "인증·진입" 절의 **"URL 쿼리 파라미터(`?token=`) 방식 미사용"** 위반이 그대로 유지. CWE-598.

---

## 2. 판정표 — H-1. 인증/라우팅 가드 (26건)

| TC-ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-FE-001 | PASS | [정적] | `router/guards.tsx:34-40` `!isHydrated → <Spinner label="인증 확인 중" />`. `App.tsx:14` 에서 `hydrate()` 1회 호출로 `isHydrated` 전이. 근거 라인 일치. 전용 자동테스트는 없음(회귀 취약) |
| TC-FE-002 | PASS | [실동작] | P1/P5 nav 체인: `/dashboard` → `/ingress` → (dev 빌드) `/dev/login`. `guards.tsx:41-43` 일치. 테스트 `RoleGuard.test.tsx:66 claims_없으면_ingress로_navigate` |
| TC-FE-003 | PASS | [실동작] | **Q5**: 세션 중 exp 경과 → `sessionStorage=null`(clear) + `control.local/login?next=…%2Fvideo%2Fcompleted%3Fpage%3D2`. `guards.tsx:27-32,44-51` 일치. 테스트 `RoleGuard.test.tsx:71` |
| TC-FE-004 | PASS | [실동작] | P7: `role:''`+INTERNAL 토큰 → `/role-claim` 렌더("권한 부여 필요 … 작업자 (WORKER)"). `guards.tsx:52-55` 일치 |
| TC-FE-005 | PASS | [실동작] | P13: WORKER 토큰 → `/manage/users` → `/forbidden`("현재 역할: 작업자"). `guards.tsx:57-58` 일치 |
| TC-FE-006 | PASS | [실동작] | P2/P17: REVIEWER 토큰 → `/dashboard` 정상 렌더(KPI·LNB 노출). `guards.tsx:60` |
| TC-FE-007 | PASS | [실동작] | P14: PORTAL 채널 토큰 → `/dashboard` → `/forbidden`. `router/index.tsx:149-151` 이 `ChannelGuard(INTERNAL)` 로 `RoleGuard` 를 감싸므로 채널이 먼저 판정됨. 역방향 P15(INTERNAL→`/portal`, `router:157-159`)도 `/forbidden`. `guards.tsx:88-89` 일치. 테스트 `ChannelGuard.test.tsx:47` |
| TC-FE-008 | PASS | [실동작] | P7: role=null 로 `/role-claim` **렌더 완료** — 무한 redirect 없음(nav 체인이 role-claim 에서 멈춤). ⚠ **근거 드리프트** — 카탈로그 `104-143` → 실제 `103-144`(JSDoc 103~113 + 본문 114~144). **정정함** |
| TC-FE-009 | PASS | [정적] | `guards.tsx:94-97` `if (typeof exp !== 'number' \|\| exp <= 0) return false;` — undefined/0 은 만료 아님. ⚠ 실사용 경로에서는 도달 불가한 방어코드 — `useAuthStore.ts:54` 가 `!exp` 토큰을 decode 단계에서 무효 처리하기 때문(P11 실측: `exp:0` 토큰 → 인증 실패, `ss=null`). 케이스 자체는 가드 함수 단위 단언이므로 PASS |
| TC-FE-010 | PASS | [실동작] | P6: `role:"ADMIN"` 토큰 → 인증 실패(`ss=null`, `/dev/login`). `useAuthStore.ts:47-48` 화이트리스트(`isRole`) 동작 |
| TC-FE-011 | PASS | [실동작] | P7 동일 증거 — `role:""` 는 claims 유효 + role=null 로 통과 후 `/role-claim`. `useAuthStore.ts:47-49` |
| TC-FE-012 | PASS | [실동작] | P8: `channel` 누락 토큰 → 인증 실패(`ss=null`). `useAuthStore.ts:50,54` (`!sub \|\| !channel \|\| !exp → null`) |
| TC-FE-013 | PASS | [실동작] | P2: 실 BE 발급 토큰의 `name:"김검수"`(UTF-8) 가 화면에 `검수자 김 김검수` 로 정상 렌더. `useAuthStore.ts:29-40` TextDecoder 경로 |
| TC-FE-014 | PASS | [실동작] | P10: `aaa.bbb`(2 parts) → 인증 실패. `tokenIngress.ts:39-40`(1차 차단) + `useAuthStore.ts:24` |
| TC-FE-015 | PASS | [실동작] | P5: 만료 토큰을 sessionStorage 에 심고 진입 → `ss=null` + `/ingress` 로 이동(hydrate 가 제거 후 claims=null). `useAuthStore.ts:89-102` 일치 |
| TC-FE-016 | PARTIAL | [실동작] | 스토어 자체는 **sessionStorage 전용**(`useAuthStore.ts:76,82,86,90` — `localStorage.setItem` 0건) ✓. 그러나 ①`DevLoginPage.tsx:153` 이 발급 JWT 를 **localStorage(`klid-jwt-token`)에 영구 저장**하고 ②온프렘 패키징 기본이 `VITE_DEV_LOGIN_ENABLED=true` 라 **운영 산출물에도 이 경로가 실린다**(P3 로 localStorage 채널이 실제 인증에 쓰임을 확인) → "XSS 노출면 축소" 가 그 빌드에서 성립하지 않음. → **H-ISSUE-02** |
| TC-FE-017 | PASS | [실동작] | P17: `/v1/stats/summary`·`/v1/videos`·`/v1/assignments`·`/v1/event-types/labels` 4요청 전부 `Authorization: Bearer eyJhbGciO…`. `client.ts:32-38` |
| TC-FE-018 | PASS | [실동작]+[테스트] | P17/P2 에서 unwrap 된 `data` 로 대시보드 렌더. `client.ts:41-49` (`{...res, data: body.data, message: body.message}`). `client.test.ts:38 ApiResponse_data_필드_unwrap_성공` |
| TC-FE-019 | PASS | [정적]+[테스트] | `client.ts:43-45` `throw ApiError.fromBody(body, res.status)`. `client.test.ts:50 ApiResponse_success_false면_ApiError로_변환` |
| TC-FE-020 | PASS | [정적] | `client.ts:54-71` — `currentToken && !sentAuth && !config._retriedWithToken` 3조건 AND, 재시도 시 플래그를 먼저 세우고 헤더 주입. 2차 401 은 반드시 `_retriedWithToken=true` 라 clear 로 떨어짐(무한 루프 없음). ⚠ **전용 자동테스트 없음**(`client.test.ts` 6건에 race 케이스 부재) — 회귀 취약, 사실만 기록 |
| TC-FE-021 | PASS | [실동작] | P16: 서명 위조 토큰 → BE 401 3건(`/v1/videos`,`/v1/assignments`,`/v1/stats/summary`) → `ss=null`(clear) + `/ingress` 이동. **Q4**(env 주입 환경): 동일 401 이 `control.local/login?next=…%2Fdashboard` 로 이동. `client.ts:72-75` |
| TC-FE-022 | PASS | [실동작] | P17 요청 전량이 `/api/v1/…` 프리픽스(env `VITE_API_BASE_URL=/api/v1`). `client.ts:23-30` 은 env 만 읽고 사용자 입력 경로 없음 |
| TC-FE-194 | PASS | [정적]+[테스트] | `errors.ts:37` `if (status===412) return 'PRECONDITION_FAILED'`, `:47` 기본문구 "현재 상태에서는 수행할 수 없는 요청입니다." 라인 정확. `errors.test.ts:14,20` |
| TC-FE-195 | PASS | [정적]+[테스트] | `resolveApiMessage.ts:4` `USER_FACING_STATUSES = new Set([400,409,412])`, `:16-19` 그 외·비-ApiError 는 fallback. `errors.test.ts:36,43,48` |
| TC-FE-196 | PASS | [정적]+[테스트] | `compactParams.ts:12-21` — `undefined/null/공백문자열/빈배열` 제거, `0`·`false` 보존(early-return 순서상 숫자·불리언은 어떤 가드에도 안 걸림). `compactParams.test.ts:6,24,34,46` |
| TC-E2E-001 | PARTIAL | [실동작] | ①차단은 성립 — 미인증 `/dashboard` 진입 시 대시보드 미렌더(P1). 스펙 `e2e/specs/login-redirect.spec.ts:4` 도 "차단"만 단언. ②**"상위 시스템 로그인" 은 조건부로만 성립** — 이제 배선·`next` 는 갖춰졌고(Q4·Q5 실증) Dockerfile 프로덕션 빌드는 dev 라우트를 넣지 않지만, **온프렘 패키징 기본 `VITE_DEV_LOGIN_ENABLED=true`** 라 그 산출물의 미인증 진입은 `/dev/login` 으로 빠진다(실측 P1: `/dashboard`→`/ingress`→`/dev/login`). → **H-ISSUE-02**. ③E2E 스펙 자체는 이번 회차 미실행 |

---

## 3. 판정표 — H-14. 보안 (XSS/토큰/용어정책) (7건)

| TC-ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-FE-189 | PASS | [실동작] | **실제 페이로드 주입 검증.** `PUT /api/v1/frames/468/description` 로 `<script>alert(1)</script><img src=x onerror=alert(2)>` 저장 → REVIEWER 로 `/label/468` 진입 → '메타' 탭. 결과: `textarea.value` 에 **원문 그대로 텍스트로만** 존재, `document.querySelector('script[src="x"], img[onerror]')=null`, `img[src="x"]` 0개, **dialog 0건 · pageerror 0건**, `innerHTML` 에는 `&lt;script&gt;alert(1)&lt;/script&gt;` 로 escape 된 리터럴 존재. 검증 후 값 `null` 로 원복함 |
| TC-FE-190 | PASS | [정적] | `grep -rn "dangerouslySetInnerHTML={" src/ e2e/` → **0건**(JSX 속성 사용 없음). ⚠ **근거 드리프트** — 느슨한 grep 매치가 카탈로그 표기 22건 → **실측 25건**(전부 "미사용/금지" 를 명시한 주석·테스트 이름). **정정함** |
| TC-FE-191 | PASS | [실동작]+[정적] | 라벨링 화면 실측 툴바 문구: `선택Esc 바운딩 박스B 폴리곤P **AI 분할**G **AI 추적**Shift+T 스켈레톤K **AI 탐지**Y` — 모델명 0건. `BatchStageIndicator.tsx:16-24` `YOLO→'AI 탐지'`, `SAM2→'AI 분할'` + `:26-28` 미지 단계 폴백 `'처리중'`(라인 정확). `StageBadge.tsx:20-21` 동일. `AiToolModal.tsx:12-13` 용어 정책 주석. 전수 grep 결과 잔존 `YOLO/SAM2` 는 **enum 값(`AUTO_YOLO`)·색상 키·매핑 Record 키·주석**뿐이며 화면 텍스트로 렌더되는 곳 없음. 설정 화면도 키가 아니라 `AI 탐지 추론 파라미터`/`인식 민감도`/`Confidence Threshold` 로 표시(`YoloConfigCard.tsx:72,88` · `PrecisionConfigCard.tsx:87`) — 카탈로그의 "설정 키 이름 예외" 는 실제로는 키가 화면에 안 나오므로 적용조차 불필요 |
| TC-FE-192 | PASS | [정적] | `ErrorBoundary.tsx:21-25` 스택은 `console.error` 로만, `:31-34` 사용자 화면은 "오류가 발생했습니다 / 잠시 후 다시 시도해 주세요." 고정 문구(포착한 `state.message` 를 **렌더하지 않음**). `resolveApiMessage.ts:4,16-19` 가 401/403/5xx·비-ApiError 를 fallback 으로 통일. CWE-209 방어 성립 |
| TC-FE-193 | PASS | [정적] | ⚠ **카탈로그 기대결과 오류를 정정함** — axios 는 템플릿 보간된 **경로 세그먼트를 자동 인코딩하지 않는다**. 실제 방어는 ①숫자 ID 보간(`ReviewPage.tsx:90` `useParams` → 숫자 변환 후 사용) ②**문자열 ID 는 명시 인코딩**(`label/api.ts:986,1017` `encodeURIComponent(trackId)`, `sysconfig/api.ts:26` config key) ③BE `LabelAccessGuard` 소유권 재검증. 미인코딩 문자열 경로 보간은 전수 grep 상 0건 |
| TC-E2E-012 | PASS | [정적] | 스펙 `e2e/specs/frame-description.spec.ts:158 저장_실패시_에러가_표시된다`(PUT 500 → `frameDescriptionError` visible) 존재. 구현측 `FrameDescriptionPanel.tsx:72 {update.isError && …}` 로 에러 노출 배선 확인. **E2E 실행은 이번 회차 금지 범위라 미실행** |
| TC-E2E-013 | PASS | [실동작]+[정적] | 실동작: `PUT /v1/frames/468/description` 후 라벨링 '메타' 탭 진입 시 textarea 에 **기존값이 그대로 표시**됨(TC-FE-189 프로브와 동일 세션에서 확인). 스펙 라인도 정확 — `:94 프레임_선택시_기존_설명이_표시된다`, `:113 설명_입력_저장시_PUT이_호출되고_반영된다` |

---

## 4. 집계

| 판정 | 건수 |
|---|:--:|
| PASS | 31 |
| PARTIAL | 2 (TC-FE-016 · TC-E2E-001) |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **33** |

> ⚠ 이슈 `H-ISSUE-01`(`?token=` 활성)은 **케이스 판정에는 FAIL 로 잡히지 않는다** — H-1 에 이 채널을 직접 단언하는 케이스가 없기 때문이다(2차와 동일하게 "TC-FE-016 인접" 으로 기록). 카탈로그 갭이며 5절 마지막에 신설 제안을 남긴다.

### 근거 드리프트 (카탈로그 정정 3건 — 담당 라인범위 내 Edit 완료)
1. **TC-FE-008** 근거 `router/guards.tsx:104-143` → `103-144`
2. **TC-FE-190** 기대결과 주석 "단순 grep 22건" → "25건(2026-08-03 실측, 구 표기 22건)"
3. **TC-FE-193** 케이스명·기대결과·근거 전면 정정(axios 자동 인코딩 오해 제거 → 3층 방어 + 실제 `encodeURIComponent` 호출 지점 명시)

> 그 외 H-1·H-14 의 `file:line` 23개 인용은 **전건 정확**(guards.tsx 8건 · useAuthStore.ts 6건 · client.ts 6건 · errors/resolveApiMessage/compactParams 3건 · e2e spec 3건 · AiToolModal/BatchStageIndicator/ErrorBoundary 3건).

---

## 5. 이슈

### [H-ISSUE-01] TC-FE-016 인접 — 운영 빌드 기본값이 `VITE_TOKEN_INGRESS=all` 이라 **URL `?token=` 인계 채널이 활성** (2차 H-ISSUE-01 **미해소 이월**, 실동작 재현)
- **심각도**: MEDIUM (2차와 동일 등급 유지. PM 지시문은 이 건을 "2차 HIGH #8" 로 지칭하나, 2차 원본 블록의 등급은 MEDIUM 이므로 임의 승격하지 않고 사실만 기록한다)
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "인증·진입" 절 — **"브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 전달. URL 쿼리 파라미터(`?token=`) 방식 미사용"**. 운영 산출물에서 URL 채널이 꺼져 있어야 JWT 가 웹서버 access log·프록시 로그·브라우저 주소창/방문기록/Referer 에 평문으로 남지 않는다(CWE-598).
- **현재 동작(이슈 내용)**: 모든 운영 배포 경로의 기본값이 여전히 `all`(= url → localStorage → cookie 순 시도)이고, 실브라우저에서 URL 채널만으로 인증이 성립한다.
  ```
  frontend/Dockerfile:33                                   ARG VITE_TOKEN_INGRESS=all
  docker-compose.yml:325                                   VITE_TOKEN_INGRESS: ${VITE_TOKEN_INGRESS:-all}
  .env.example:141                                         VITE_TOKEN_INGRESS=all
  deploy/onprem/scripts/package/20-build-frontend.sh:32     :-all
  deploy/onprem/scripts/install/build-from-source.sh:185    :-all
  deploy/onprem/docs/{02-build-package.md:30, 08-build-from-source.md:79, 04-configuration.md:195}
  docker inspect klid-frontend → VITE_TOKEN_INGRESS=all    (실효값)
  ```
  ```ts
  // frontend/src/features/auth/tokenIngress.ts:23,130-132
  const v = (import.meta.env.VITE_TOKEN_INGRESS as string | undefined) ?? 'all';   // 미설정·오타 → all 폴백
  if (strategy === 'url' || strategy === 'both' || strategy === 'all') {
    candidates.push(params.urlToken);          // ← URL 채널 상시 개방
  }
  ```
  `frontend/.env.example:5` 만 `localStorage` 이고 **`.env.production` 파일은 여전히 부재**.
- **재현/확인 경로**:
  ```
  브라우저 → http://localhost:13000/ingress?token=<REVIEWER JWT>
    → 최종 /dashboard, sessionStorage['klid_jwt'] 적재, 검수자 화면 정상 렌더 (실측 P2)
  # 대조: localStorage / cookie 채널도 각각 단독으로 인증 성립 (실측 P3/P4)
  ```
- **영향**: 보안 — CWE-598(Sensitive Information in Query String). 인계 URL 이 리버스프록시/WAF access log·브라우저 히스토리·(외부 링크 클릭 시) Referer 헤더에 JWT 원문을 남긴다. 정책 문서와 구현이 어긋난 상태가 3개 회차 연속 유지 중.
- **수정 방향(제안)**: ①운영 3경로(`Dockerfile:33`·`docker-compose.yml:325`·온프렘 스크립트 2종)의 기본값을 `localStorage` 로 바꾸고, URL 채널이 필요한 환경만 명시 opt-in. ②`tokenIngress.ts:23` 의 미인식 값 폴백을 `all` → `localStorage` 로 바꾸고 `console.warn` 을 남긴다(현재는 오타 하나로 조용히 전 채널 개방). ③`frontend/src/vite-env.d.ts:5` 의 `readonly VITE_TOKEN_INGRESS: 'url' | 'cookie'` 를 실제 5종으로 정정(타입이 실제 허용값과 달라 IDE 가 오타를 못 잡는다). ④`upstreamLoginUrlDeployWiring.test.ts` 와 같은 방식의 배포 배선 회귀 가드를 이 변수에도 추가. ⚠ 구현하지 않음.

### [H-ISSUE-02] TC-FE-016 / TC-E2E-001 — 온프렘 **운영 패키징 기본값 `VITE_DEV_LOGIN_ENABLED=true`** 때문에 ①`/dev/login` 이 운영 산출물에 실리고 ②그 화면이 JWT 를 **localStorage 에 영구 저장**하며 ③미인증 진입이 상위 로그인으로 가지 않는다 (2차 H-ISSUE-03 **미해소 이월 + 영향 확장**)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: ①`router/index.tsx` 주석대로 prod 빌드에서 `DevLoginPage` 청크가 dead-code 제거되어야 한다. ②TC-FE-016 이 요구하는 "토큰 저장소=sessionStorage(XSS 노출면 축소)" 가 배포 산출물 전체에서 성립해야 한다 — sessionStorage 는 탭 종료 시 소멸하지만 localStorage 는 영속이라 XSS 1회로 장기 탈취가 된다. ③`CLAUDE.md` "세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트".
- **현재 동작(이슈 내용)**:
  ```
  deploy/onprem/scripts/package/20-build-frontend.sh:33      export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
  deploy/onprem/scripts/install/build-from-source.sh:183-184 (VITE_DEV_LOGIN_ENABLED / VITE_DEV_UPLOAD_ENABLED 둘 다 :-true)
  deploy/onprem/docs/08-build-from-source.md:65              같은 기본값을 문서로 고정
  ```
  ```ts
  // frontend/src/lib/devLogin.ts:11-13
  export function isDevLoginEnabled(): boolean {
    return import.meta.env.DEV || import.meta.env.VITE_DEV_LOGIN_ENABLED === 'true';
  }
  // frontend/src/features/auth/SessionIngressPage.tsx:41-44  — 상위 redirect 보다 먼저 걸린다
  if (isDevLoginEnabled()) { navigate('/dev/login', { replace: true }); return; }
  // frontend/src/features/auth/DevLoginPage.tsx:153
  localStorage.setItem(LOCAL_STORAGE_TOKEN_KEY, data.token);   // ← JWT 영속 저장
  ```
  실측: 미인증 `/dashboard` → `/ingress` → **`/dev/login`**(역할 3종 라디오 + "토큰 발급 + 진입" 버튼 노출). 그 화면이 저장한 `localStorage['klid-jwt-token']` 은 이후 `tokenIngress.ts:133-135` 의 localStorage 채널로 **자동 재인증**된다(실측 P3).
  ⚠ **RoleGuard 만료·401 경로는 영향 없음** — 두 경로는 `isDevLoginEnabled()` 를 거치지 않고 곧바로 `redirectToUpstream` 을 호출한다(Q4·Q5 로 확인). 즉 이 이슈는 **`/ingress` 진입 실패 경로 한정**이다.
- **재현/확인 경로**:
  ```
  신규 시크릿 창 → http://localhost:13000/dashboard  → /dev/login 도달 (상위 로그인 아님)
  /dev/login 에서 토큰 발급 → DevTools Application → localStorage['klid-jwt-token'] 에 JWT 원문 잔존
  # BE 최종 방어는 살아 있음(fail-closed):
  #   DevTokenController @ConditionalOnProperty(authoring.dev.login.enabled, havingValue="true")
  #   application.yml  authoring.dev.login.enabled = ${DEV_LOGIN_ENABLED:false}  (prd 미설정 → false)
  ```
- **영향**: 보안 — CWE-1188(안전하지 않은 기본값) + XSS 시 토큰 영속 탈취(CWE-522). 기능 — 운영에서 세션이 끊긴 사용자가 관제/포털 로그인으로 복귀하지 못하고 개발용 화면을 보게 된다(BE 가 404 를 주므로 막다른 화면). H-ISSUE-01 과 결합하면 "URL 채널 + 영속 localStorage + dev 토큰 발급 UI" 가 한 산출물에 공존한다.
- **수정 방향(제안)**: ①온프렘 두 스크립트의 기본값을 `false` 로 뒤집고, bring-up 이 필요한 현장만 명시 `true` 로 켠다(문서 `08-build-from-source.md:65` 동반 수정). ②`DevLoginPage` 의 `localStorage.setItem(LOCAL_STORAGE_TOKEN_KEY, …)` 를 제거하고 `useAuthStore.setToken`(sessionStorage) 만 쓰게 한다 — 인계 채널 시뮬레이션이 목적이면 `VITE_TOKEN_INGRESS` 를 dev 에서만 `all` 로 두는 것으로 충분하다. ③`upstreamLoginUrlDeployWiring.test.ts` 와 동일한 패턴으로 "운영 패키징 스크립트의 dev 플래그 기본값 = false" 회귀 가드를 추가한다. ⚠ 구현하지 않음.

### [카탈로그 신설 제안 — 이슈 아님]
H-1 에 **토큰 인계 채널 자체를 단언하는 케이스가 없다**. H-ISSUE-01 이 2회차 연속 "인접" 으로만 기록되는 이유이며, 케이스가 없으니 통과율에도 잡히지 않는다. 다음 최신화 때 아래 3건 신설을 제안한다(이번 회차에는 담당 라인범위 밖 신설을 하지 않았다).
- `TC-FE-xxx` 운영 기본 인계 전략 = `localStorage` (배포 3경로 실효값 검사, `upstreamLoginUrlDeployWiring.test.ts` 방식)
- `TC-FE-xxx` `?token=` 은 운영 빌드에서 무시된다 (`resolveToken` 이 urlToken 후보를 담지 않음)
- `TC-FE-xxx` 미인식 `VITE_TOKEN_INGRESS` 값의 폴백 = 최소권한 채널 (현재는 `all`)

---

## 6. 부수 관측 (내 담당 범위 밖 — 참고용, 판정 미부여)

- **★4 라벨명 정책 정합 확인**: `/label/468` 실화면의 객체 목록이 `person (2) / person #1 … BBOX` 로 **마스터 등록명(COCO 영문) 그대로** 표시됨(한글 사전 치환 없음). `labelDisplayName.ts:3` 주석이 `d8a7a2cc` 의 한글 우선 치환 폐기를 명시하고 `labelColors.ts:44-46` 은 `LABEL_CLASS_DEFS` 를 **색상 매핑 전용**으로 축소. 잔존 `COCO_LABEL_KO`(`constants/cocoClasses.ts:25,65`)의 유일한 소비자는 **라벨 마스터 등록 폼의 COCO 매핑 select**(`LabelMasterFormModal.tsx:9,151`)로, 라벨명 표시축이 아니라 COCO 클래스 선택축이라 ★4 위반 아님. (근거 케이스 TC-FE-293~296 은 part2 담당)
- **2차 H-ISSUE-103(TC-FE-275, 포털 SAM2 노출) 해소 정황**: `features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:110 포털_라벨링_도구바에_SAM2_분할_추적_스켈레톤이_노출되지_않는다` 가 존재하고 baseline 전건 통과. 확정 판정은 part2 담당.
- **`BatchStageIndicator.tsx:19` 의 `VLM: 'VLM'`**: TC-FE-191 이 금지하는 것은 `YOLO/SAM2` 라 케이스 범위 밖이나, 사용자에게 노출되는 유일한 기술 약어다. 용어 정책을 넓힐지 여부는 정책 결정 사항(사실만 기록).
- **Playwright MCP 공용 브라우저 경합**: 3차 회차의 다른 FE 검증 에이전트와 탭 1개를 공유하게 되어 내비게이션·sessionStorage 가 서로 덮이는 것을 실측했다. FE 실동작 검증을 병렬로 돌릴 때는 **에이전트마다 독립 브라우저 컨텍스트**(본 문서처럼 `playwright-core` 직접 기동)를 쓰거나 순차 실행이 필요하다. 다음 회차 운영 참고.
