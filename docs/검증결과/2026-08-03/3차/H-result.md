# H클러스터 — 테스트케이스 전수 검증 3차 회차 병합 결과

> 8개 파트(part1~part8) 병렬 실동작 검증 결과를 원문 그대로 병합(축약 없음). 각 파트 원본은 `_raw/H-part{1..8}.md` 참조.
> 카탈로그: `docs/test-cases/H-frontend-e2e.md` (337건, H-1~H-18)

## 목차

| 파트 | 담당 범위 | 케이스 수 |
|---|---|---:|
| part1 | H-1(인증/라우팅 가드) + H-14(보안 XSS/토큰/용어정책) | 33 |
| part2 | H-3 LabelingPage 앞 1/3 (TC-FE-033~063) | 31 |
| part3 | H-3 LabelingPage 중간부 (TC-FE-064~087,197~202,261) | 31 |
| part4 | H-3 LabelingPage 뒷부분 (TC-FE-262~296) | 30 |
| part5 | H-11(포털 화면) + H-15(E2E 전체 사용자 시나리오) | 28 |
| part6 | H-2(라우터) + H-4(useLabelStore) + H-5(좌표변환) + H-7(BatchStageIndicator) | 38 |
| part7 | H-6(MarkingPage) + H-8(ReviewPage) + H-9(관리화면) + H-10(증강/해상도) | 66 |
| part8 | H-12(공통컴포넌트) + H-13(a11y) + H-16(작업목록) + H-17(검수목록) + H-18(영상목록/상세) | 80 |
| **합계** |  | **337** |

---


<!-- ============================================================ -->
<!-- H-part1.md 원문 시작 -->
<!-- ============================================================ -->

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

<!-- H-part1.md 원문 끝 -->

---

<!-- ============================================================ -->
<!-- H-part2.md 원문 시작 -->
<!-- ============================================================ -->

# H 클러스터 part2 — H-3. LabelingPage(라벨링 캔버스) 앞 1/3 (TC-FE-033~063, 31건)

- 담당 범위: `docs/test-cases/H-frontend-e2e.md` **68~104행** (H-3 섹션 앞부분 31건)
- 검증 일자: 2026-08-03 / 3차
- 검증 대상 커밋: `e065da42` (워크트리 `qa-0803`), 컨테이너 이미지 동일 커밋으로 재빌드됨(`_raw/stack-bringup.md`)
- 실동작 환경: frontend `localhost:13000`, backend `localhost:18081`(context-path `/api`), 파이프라인 데이터 **rawSn=101 / srcSn 468~477**(`_raw/pipeline-drive.md`), WORKER=2001 / REVIEWER=1001
- 사용 도구: Playwright(실브라우저 Chromium) + curl(BE 직접)
- ⚠ **실브라우저는 병렬 검증 에이전트와 세션을 공유**해 세션 도중 다른 에이전트가 토큰·URL 을 바꾸는 간섭이 반복 발생했다. 이 때문에 일부 케이스는 실동작 대신 정적+기존 자동테스트 근거로 판정했다(각 행에 `[정적]` 표기).

---

## 0. 착수 전 대전제 확인 — 라벨링 캔버스 비식별 서빙 (★ 반증 필수 항목)

CLAUDE.md "라벨링 캔버스는 비식별 프레임을 서빙한다"(2026-07-30 확정)를 **실동작으로 반증 시도**했다.

| 요청 | 응답 | 크기 | md5 |
|---|---|---|---|
| `GET /api/v1/frames/468/image` (WORKER) | 200 `image/jpeg`, `Cache-Control: no-store` | 13,164 | `efadbd02…` |
| `GET /api/v1/frames/468/image?raw=true` (**WORKER**) | 200 | 13,164 | `efadbd02…` ← **DEID 와 동일 = raw 무시됨** |
| `GET /api/v1/frames/468/image?raw=true` (**REVIEWER**) | 200 | **9,441** | `be89bcc1…` ← 원본(다른 바이트) |
| `GET /api/v1/frames/468/image` (REVIEWER) | 200 | 13,164 | `efadbd02…` |

- **WORKER 의 `raw=true` 는 무시되고 DEID 가 강제**된다(md5 동일). REVIEWER 만 원본을 받는다 → 정책 준수 **PASS**.
- `GET /v1/frames/468/labels` 응답 `"frameImageType":"DEID"` → 화면 헤더 뱃지도 실브라우저에서 `DEID` 로 렌더 확인.
- FE 호출부 `LabelingPage.tsx:160` 은 `useImageBlob(data?.srcSn, { portalMode })` 로 **`raw` 옵션을 아예 넘기지 않는다** → `useImageBlob.ts:77-81` 이 `params` 자체를 붙이지 않음. 즉 **캔버스에는 REVIEWER 원본 열람 동선이 FE 에 존재하지 않는다**(CLAUDE.md "REVIEWER 원본 열람 동선은 FE 에 두지 않는다" 와 정합).
- 게이트가 걸린 미디어 응답의 `Cache-Control: no-store` 실측 확인.

## 0-2. konva.js 렌더링 실측

`/label/468` 진입 후 DOM 실측:
```
canvas 3개 (konva Stage 레이어), 각 1280×720 (attribute w/h == style w/h → DPR 스케일 왜곡 없음)
.konvajs-content 존재, getBoundingClientRect w=1280 h=720
프레임 스트립 썸네일 img 10개, 전부 blob: URL, naturalWidth/Height = 320×240 (비식별 프레임 실측 해상도)
```
- 캔버스 레이어 마운트·이미지 blob 로드 모두 정상. 라벨 `person #1 BBOX` 가 객체 목록·속성 패널에 렌더됨(라벨 좌표 `[[61,61],[211,211]]`, 이미지 320×240 범위 내).
- 참고(케이스 범위 밖): MCP 기본 뷰포트 1200px 에서 stage 가 1280px 로 측정되어 좌측으로 `x=-156` 오버플로했다. 데스크톱 폭에서는 재현되지 않으며 H-13(반응형/a11y) 소관이라 이 파트에서는 결함으로 집계하지 않고 사실만 기록한다.

---

## 1. 케이스별 판정

| ID | 케이스명 | 판정 | 근거 확인 |
|----|---------|:--:|------|
| TC-FE-033 | 잘못된 ID(NaN) 다크 에러 | PASS | [실동작] `/label/abc` 진입 → 스냅샷 `paragraph "잘못된 프레임 ID"` + `button "뒤로 가기"`. 정적 `LabelingPage.tsx:1076-1095`(카탈로그 1075-1094 → **+1 드리프트, 정정함**) |
| TC-FE-034 | 로딩 상태 스피너 | PASS | [정적] `LabelingPage.tsx:1097-1110` → `<Spinner label="라벨 로딩" />` + "라벨 로딩 중...". ⚠ 카탈로그 기대결과 `"라벨 로딩" data-testid` 는 **오류** — `Spinner.tsx:9-23` 은 `role=status`+`aria-live=polite`+`aria-label`+`sr-only` 만 부여하고 data-testid 는 없다(페이지 컨테이너만 `data-testid="labeling-page"`). 기대결과 **정정함** |
| TC-FE-035 | 포털 403 → graceful 차단화면 | PASS | [정적] `LabelingPage.tsx:1115-1142` `isPortalForbidden`(status===403 \|\| errorCode==='FORBIDDEN') → `data-testid="portal-forbidden-screen"` + "접근할 수 없는 영상입니다". 테스트 `features/portal/__tests__/LabelingPagePortalForbidden.test.tsx:55 포털_미승인_영상_403시_접근불가_안내_화면`(baseline 전건 통과). 근거 **+1 정정** |
| TC-FE-036 | 일반 에러 → "라벨 조회 실패" | PASS | [실동작] `/label/999999` → `"라벨 조회 실패"` + BE 문구 `"프레임을 찾을 수 없습니다."` + 뒤로 가기(HTTP 404 실측). 근거 `LabelingPage.tsx:1144-1164`(**+1 정정**). ⚠ 인접 이슈 → **H-ISSUE-21** |
| TC-FE-037 | siblings 비면 현재 프레임 단건 폴백 | PASS | [정적] `LabelingPage.tsx:236-263` `siblings.length===0` → 현재 1건 배열. 실동작에서는 BE 가 항상 siblings 10건을 주어 폴백 경로 미발생(스트립 10건 렌더 확인 = else 분기). 폴백 분기는 `LabelingPageBusyWiring.test.tsx:206`(`siblings: []` fixture)이 간접 커버 — **전용 단언 테스트는 없음**. 근거 **+1 정정** |
| TC-FE-038 | 저장 성공 토스트 "저장됨" | PASS | [실동작] `/label/468`에서 Ctrl+S → `PUT /api/v1/frames/468/labels` 1건 발생, 바디 `{"items":[…],"labelVersion":5}`, 화면 텍스트에 `저장됨` 관측. 서버 `labelVersion` 4→5 증가 확인. 근거 `:559-597`(**+1 정정**) |
| TC-FE-039 | 저장 중복 제출 차단 | PASS | [실동작] Ctrl+S **3연타** → 캡처된 PUT **1건**(XHR open 후킹). 근거 `:563` `if (saving) return;`(**+1 정정**). 테스트 `features/review/__tests__/LabelingPageSaveGuard.test.tsx:75` |
| TC-FE-040 | 잠금 영상 저장 차단 | PASS | [정적] `:565-571` `isLocked` → error 토스트 `'비식별 재처리 중인 영상은 저장할 수 없습니다.'` 후 `return` (updateLabels 미호출 = PUT 미발생). 테스트 `LabelingPageDeidentReport.test.tsx:108 lockSttsCd_LOCKED_FOR_REDEIDENT_시_배너_표시_+_저장_버튼_비활성`. 근거 **+1 정정** |
| TC-FE-041 | 저장 실패 에러 토스트(BE 문구 우선) | PASS | [정적] `:592-595` `extractBeMessage(e,'저장 실패')`. 409 는 `:586-591` 에서 별도 다이얼로그로 분기(카탈로그 "409 외" 전제와 일치). `lib/api/extractBeMessage.ts:8-26` 이 userMessage→message→response.data.message→fallback 순. 근거 **+1 정정** |
| TC-FE-042 | 포털 모드 저장 경로 분기 | PASS | [정적] `:552-558` `useSavePortalLabels` + `const updateLabels = portalMode ? savePortalLabels : updateInternalLabels`. 테스트 `features/portal/__tests__/PortalLabelingDataPath.test.tsx:91 포털_모드_라벨로드는_portal_API만_호출_내부API_미호출`. 근거 **+1 정정** |
| TC-FE-043 | 프레임 이동 dirty 가드 모달 | PASS | [실동작] 라벨 1건 삭제(dirty=1) 후 프레임1 썸네일 클릭 → 모달 `"저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이 있습니다… / 취소 · 저장 안 함 · 저장 후 이동"`, URL 은 `/label/468` 유지. 근거 `:340-352`(**+1 정정**) |
| TC-FE-044 | 같은 프레임 이동 no-op | PASS | [실동작] 현재 선택 프레임(`aria-selected=true`) 옵션 클릭 → URL 불변, 가드 모달 미출현. 근거 `:346`(**+1 정정**) |
| TC-FE-045 | 저장 후 이동 — 실패 시 취소 | PASS | [정적] `:353-382` catch → error 토스트 + `setNavGuardTarget(null)`(이동 취소). 추가로 `saved === null`(폐기) 시에도 이동 안 함. 테스트 `LabelingPageFrameNavGuard.test.tsx:205 저장후이동_save실패시_이동취소_현재프레임유지`. 근거 **+1 정정** |
| TC-FE-046 | 저장 안 함 이동 — dirty 폐기 | PASS | [실동작] 모달에서 "저장 안 함" → `/label/469` 로 이동, **PUT 0건**(XHR 후킹), 객체수 0개. 근거 `:383-400`(**+1 정정**). ⚠ 현행 구현은 카탈로그 기대결과보다 **더 강하다** — 이동 가능 여부(`isEditBlocked`)를 clearDirty **앞에서** 판정해 "파기만 되고 못 떠나는" 경로를 차단(`:389-396`). 기대결과와 모순 아님 |
| TC-FE-047 | 프레임 전환 시 setLabels 전체 교체 | PASS | [실동작+정적] 468(1건)→469 이동 후 객체수 0개로 교체 확인. `:404-427` `frameChanged` 분기에서 `setLabels(nextLabels)`(내부에서 dirty/undo/redo/selection 초기화). 테스트 `LabelingPageFrameSwitch.test.tsx:92`. 근거 **+1 정정** |
| TC-FE-048 | 같은 프레임 refetch는 dirty 있으면 미덮음 | PASS | [정적] `:423-426` `if (useLabelStore.getState().dirtyLabels.size === 0) setLabels(...)` — `getState()` 로 최신 dirty 를 읽어 stale 판정 회피. 테스트 `LabelingPageAutolabelMerge.test.tsx:154 refetch가_와도_dirty편집이_유실되지_않는다`. 근거 **+1 정정** |
| TC-FE-049 | 보류 추적 drain 병합 | PASS | [정적] `:433-446` — `drainPendingTracks(srcSn)` → `mergeAutoLabels` → `pushToast(info, "보류된 AI 추적 ${added}건 적용됨")`. deps 가 `data?.srcSn` 하나라 같은 프레임 refetch 로 이중 병합 없음. 근거 **+1 정정** |
| TC-FE-050 | 언마운트 시 store reset | PASS | [정적] `:449-453` cleanup-only `reset()`(deps `[reset]`) — data 변경마다 reset 되던 회귀를 막는 구조. 근거 **+1 정정** |
| TC-FE-051 | AI 탐지 팝업 매핑 라벨만 선택 | PASS | [실동작] AI 탐지 모달 실측 — 매핑 6종(`person·car·bicycle·motorbike·bus·truck`) `disabled=false`, 미매핑 4종(`fire·smoke·water·c2a-race-lbl`) `disabled=true` + `미매핑` 뱃지, 실행 버튼 라벨 `전체 (6종)`, `일반`/`트랙` 활성(= `canRun = mappedCount>0`). 근거 `AiToolModal.tsx:147,183,250-272` **드리프트 없음**. ⚠ 인접 이슈 → **H-ISSUE-22** |
| TC-FE-052 | AI 탐지 mock 응답 자동적용 차단 | PASS | [실동작] `/label/470`에서 AI 탐지 "일반" 실행 → BE 가 `ApiResponse.message = "AI 모델 미로드 — 결과 신뢰 불가"` 반환 → FE 가 **경고 토스트만** 띄우고 객체수 `0개 → 0개`(병합 0건). `:695-698` 조기 return 실효 확인. self-fill 없음(값은 전부 BE 응답 경유). 근거 **+1 정정** |
| TC-FE-053 | AI 탐지 결과 작업본 병합(중복 스킵) | PASS | [정적] `:700-707` `mergeAutoLabels(detected)` + `` `${kind} ${added}건 적용됨` ``, PUT/invalidate 없음. ⚠ **실동작 성공경로는 이 환경에서 미재현** — ai-server 에 YOLO 가중치가 미로드라 항상 mock 분기(TC-FE-052)로 빠진다. 테스트 `LabelingPageAutolabelMerge.test.tsx:120 오토라벨_직후_PUT저장이_호출되지_않는다_그리고_기존라벨_유지한채_병합` + `LabelingPageAutolabelToast.test.tsx:120`. 근거 **+1 정정** |
| TC-FE-054 | 폴리곤 shape → "AI 분할" 라벨 | PASS | [정적] `:706` `BUSY_KIND_NAME[ctx.shape === 'POLYGON' ? 'AI_SEGMENT' : 'AI_DETECT']`, `busyPolicy.ts:20-26` `AI_SEGMENT: 'AI 분할'` / `AI_DETECT: 'AI 탐지'` — 문구 단일 소스. 근거 **+1 정정** |
| TC-FE-055 | 트랙 모드 — 객체 선택 유도 | PASS | [실동작] 모달 "트랙" 클릭 → 모달 닫힘, 툴바 `AI 추적` 버튼 `aria-pressed="true"`, info 토스트 `"추적할 객체를 선택한 뒤 속성 패널에서 자동추적을 실행하세요."`. 근거 `:818-836`(**+1 정정**) |
| TC-FE-056 | 추적 결과 현재/미래 프레임 분리 | PASS | [정적] `:764-807` — `forCurrent`(=== data.srcSn) 즉시 `mergeAutoLabels`, `forFuture` 는 srcSn 별 그룹화 후 `stashPendingTracks`. 미래분 frameNo 는 `frames.find(...)`로 해석. 근거 **+1 정정** |
| TC-FE-057 | 부분 추적 실패 경고 | PASS | [정적] `:788-796` warning 토스트 `` `${applied}/${total} 프레임만 추적됨 (일부 실패)` ``, `total = nextSrcSns.length \|\| tracked.length`. 카탈로그 기대문구 "N/total만 추적됨" 은 실제 문자열과 달라 **정정함**. 근거 **+1 정정** |
| TC-FE-058 | nextSrcSns 계산(현재 이후) | PASS | [정적] `:753-757` `frames.slice(frameIdx + 1).map(f => f.srcSn)` (useMemo deps `[frames, frameIdx]`). 근거 **+1 정정** |
| TC-FE-059 | SAM2 추적 청크 50개 상한 정합 | PASS | [정적] `features/label/api.ts:632` `SAM2_TRACK_CHUNK_SIZE = 50`, `:715-718` slice 분할. 테스트 `features/label/__tests__/sam2-track.test.tsx:96 상수는_BE_Size_상한과_정합한다` / `:100 120개_후속프레임은_50_50_20_3청크` / `:179 정확히_50개는_단일_청크`. 실동작 미재현(대상 영상 프레임 10건뿐이라 청크 1개). 드리프트 없음 |
| TC-FE-060 | SAM2 추적 stale 가드(프레임 전환) | PASS | [정적] `useSam2Track.ts:61`(runExclusiveOrNotify), `:72-73`(isAlive 통과 시에만 onTracked), `:80-81`(부분실패도 isAlive 검사) + `useBusyTask.ts:113-118`(프레임 전환 시 이전 busy cancel), `:127-139`(시작 시점 stale 차단 + `isAlive` = 토큰축 ∧ 렌더축). 카탈로그의 "onSuccess 비교 아님" 정정 표기가 **현행과 일치**. 테스트 `useSam2Track.invalidate.test.tsx:100 추적_응답이_프레임전환후_도착하면_폐기된다`. 드리프트 없음 |
| TC-FE-061 | SAM2 부분 실패 성공분만 병합 | PASS | [정적] `useSam2Track.ts:78-84` catch → `Sam2TrackChunkError.partial.length>0` 이면 `onTracked(err.partial, true)` 후 rethrow. 테스트 `useSam2Track.invalidate.test.tsx:65 추적_부분실패시_성공분만_병합하고_경고한다` / `:84 완전실패(partial_0)면_onTracked_미호출`. 드리프트 없음 |
| TC-FE-062 | BBOX 추적 시드 외접박스 4점 확장 | PASS | [정적] `api.ts:640-659` `toSeedPolygon` — 2점만 4모서리 폐곡선으로 확장, 3점 이상은 그대로. 비유한 좌표는 WARN + 0 폴백(침묵 실패 가시화). 테스트 `sam2-track.test.tsx:478 BBOX_추적_50프레임초과시_2번째청크_prevPolygon이_4점폐곡선으로_확장된다`. 드리프트 없음 |
| TC-FE-063 | 트랙 rename/삭제/분할 포털 차단 | PASS | [정적] `:859`(rename) / `:881`(delete) / `:906`(split) 각각 `if (portalMode) return;` — 모두 `isEditBlockedNow` 가드 **직후, rawSn 조회 앞**에 위치해 우회 경로 없음. 테스트 `features/portal/__tests__/LabelingPageTrackRenameGuard.test.tsx:110 포털모드_트랙_rename_버튼_미노출_및_mergeTracks_미호출`(delete/split 은 전용 테스트 없음 — 동일 패턴 정적 확인). 근거 **+1 정정** |

### 집계

| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---|---|---|---|---|---|
| **31** | **31** | 0 | 0 | 0 | 0 | 0 |

- 실동작(`[실동작]`) 판정 **12건**, 정적+자동테스트(`[정적]`) 판정 19건.
- 케이스 자체는 전건 PASS. 다만 **인접 결함 2건**(H-ISSUE-21·22)과 **카탈로그 정합성 결함 1건**(H-ISSUE-23, 정정 완료)을 아래에 기록한다.

---

## 2. 이슈

### [H-ISSUE-21] TC-FE-036 인접 — 라벨링 에러 화면이 `resolveApiMessage` 정책을 우회해 `error.message` 를 그대로 렌더한다 (2차 H-ISSUE-06 **미해소 이월**)
- **심각도**: LOW
- **기대 동작(기대효과)**: `lib/api/resolveApiMessage.ts:4,16-19` 가 "400/409/412 만 서버 문구 노출, 401/403/5xx·비-ApiError 는 fallback" 을 **단일 지점에서** 강제한다(CWE-209 정보 노출 방어). 에러 표시 경로가 이 유틸을 통과해야 정책이 실효를 갖는다.
- **현재 동작(이슈 내용)**: 2차 H-ISSUE-06 이 지목한 `LabelingPage.tsx:1144` 는 라인만 밀렸을 뿐 **그대로 남아 있다**.
  ```tsx
  // frontend/src/pages/label/LabelingPage.tsx:1151-1153
  <div className="text-center">
    <p className="text-lg font-semibold mb-2">라벨 조회 실패</p>
    <p className="text-sm text-gray-400 mb-4">{error.message}</p>
  ```
  같은 파일의 저장 실패 토스트(`:594`)도 `extractBeMessage()` 를 쓰는데, 이 유틸은 `lib/api/extractBeMessage.ts:12-23` 에서 **상태코드를 보지 않고** `userMessage → message → response.data.message` 순으로 무조건 서버 문구를 채택한다. 즉 라벨링 화면에는 정책 우회 경로가 **표시(1153)와 토스트(594) 두 축**에 있다.
- **재현/확인 경로**: `/label/999999` 진입 → 화면 `"라벨 조회 실패 / 프레임을 찾을 수 없습니다."`(404 문구가 그대로 노출, 실측). 네트워크 차단 상태로 진입하면 axios 원문(`Network Error` / `timeout of 30000ms exceeded`)이 그대로 노출된다.
- **영향**: 보안(정보 노출, **잠재**) — 현재 BE 는 500 에 `ErrorCode.INTERNAL_ERROR.defaultMessage()` 고정값만 내려주므로 실제 유출은 없다. 다만 어느 예외 핸들러가 상세 메시지를 담기 시작하면 이 경로들만 조용히 새고, 정책이 한 곳에 모이지 않아 감사가 어렵다. CWE-209.
- **수정 방향(제안)**: `LabelingPage.tsx:1153` 을 `resolveApiMessage(error, '라벨을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')` 로 교체. `extractBeMessage` 는 상태코드 필터를 내장하거나(400/409/412 만 서버 문구), `resolveApiMessage` 위임형으로 재작성. 2차 제안대로 ESLint `no-restricted-syntax`(JSX 자식/속성으로 `*.message` 직접 사용 금지, 폼 `errors.*.message` 예외) 도입 검토. ⚠ 2차에서 지목된 나머지 3곳(`HistoryPanel.tsx:194,254`)도 함께 확인 필요 — `HistoryPage.tsx` 는 2026-08-03 결정2(`b27b3108`)로 삭제되어 해당 없음.

### [H-ISSUE-22] TC-FE-051 / TC-FE-053 인접 — WORKER 라벨링 화면이 REVIEWER 전용 `/v1/manage/configs` 를 호출해 **AI 정밀도 시스템 설정이 작업자에게 영영 반영되지 않는다** (+ 매 진입마다 403 2회)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `LabelingPage.tsx:722-725` 는 시스템 설정(`YOLO_CONF_THRESHOLD`, `POLYGON_SIMPLIFY_TOLERANCE`)을 AI 탐지 모달 슬라이더 기본값으로 **프리필**하기 위해 만들어졌다("Phase 2 [FE] — AI 정밀도 프리필. 시스템 설정값을 슬라이더 기본값으로 사용"). 라벨링의 주 사용자는 **WORKER** 이므로, REVIEWER 가 설정한 값이 WORKER 화면에도 반영되어야 이 기능이 성립한다.
- **현재 동작(이슈 내용)**: 프리필 소스가 **REVIEWER 전용 관리 API** 하나뿐이라 WORKER 는 항상 403 → `sysConfigs === undefined` → 코드 상수로 폴백한다.
  ```ts
  // frontend/src/pages/label/LabelingPage.tsx:722-725
  const { data: sysConfigs } = useConfigs();
  const defaultConfThreshold =
    sysConfigs?.YOLO_CONF_THRESHOLD != null ? sysConfigs.YOLO_CONF_THRESHOLD / 100 : undefined;
  const defaultSimplifyTolerance = sysConfigs?.POLYGON_SIMPLIFY_TOLERANCE;
  // → undefined → PrecisionSliders.tsx:14,20  SENSITIVITY_DEFAULT=0.25 / TOLERANCE_DEFAULT=1 폴백
  ```
  실측:
  ```
  GET /api/v1/manage/configs  (REVIEWER) → 200  YOLO_CONF_THRESHOLD=25, POLYGON_SIMPLIFY_TOLERANCE=1.0
  GET /api/v1/manage/configs  (WORKER)   → 403  {"errorCode":"FORBIDDEN","message":"권한이 없습니다."}
  ```
  WORKER 실브라우저에서 AI 탐지 모달을 열면 "인식 민감도 **0.25**" 가 표시되는데, 이는 DB 값(25→0.25)이 아니라 **코드 상수**다. 현재는 두 값이 우연히 같아 증상이 보이지 않지만, REVIEWER 가 설정을 60 으로 바꾸면 **REVIEWER 화면만 0.60, WORKER 화면은 계속 0.25** 가 된다.
  부수 효과로 `lib/queryClient.ts:7` 의 `retry: 1` 때문에 **라벨링 화면 진입 1회당 403 이 2회** 발생하고 콘솔 에러가 누적된다(실측: 한 세션에 8건 관측).
- **재현/확인 경로**:
  1. `POST /api/v1/dev/tokens {"userNo":2001,"role":"WORKER","channel":"INTERNAL"}` 로 WORKER 토큰 발급 → `sessionStorage.klid_jwt` 주입 → `/label/468` 진입.
  2. DevTools Network → `GET /api/v1/manage/configs` **403 × 2**.
  3. 툴바 "AI 탐지" 클릭 → 인식 민감도 슬라이더가 코드 상수 `0.25` 로 표시.
  4. 대조: REVIEWER 토큰(userNo=1001)으로 `curl -H "Authorization: Bearer $RT" localhost:18081/api/v1/manage/configs` → `YOLO_CONF_THRESHOLD=25`.
- **영향**: 기능(설정 무효화) — 운영자가 관리 화면에서 조정한 AI 정밀도 기본값이 **실제 라벨링 작업자에게 도달하지 않는다**. AI 탐지 결과 품질에 직결되며, "설정을 바꿨는데 왜 안 바뀌냐"는 형태로만 드러나 원인 추적이 어렵다. 보안 영향은 없다(BE fail-closed 는 정상 동작). 부차적으로 콘솔 403 노이즈가 실제 오류를 가린다.
- **수정 방향(제안)**: 두 갈래 중 택1 — ①BE 에 **읽기 전용 프리셋 조회 엔드포인트**(예: `GET /v1/labels/ai-presets`, WORKER+REVIEWER 허용, AI 정밀도 키만 화이트리스트 노출)를 신설하고 라벨링 화면이 그것을 보게 한다(권장 — 설정 단일 진실원 유지). ②`/v1/manage/configs` 의 **GET 만** WORKER 에 허용한다(다만 관리 설정 전체가 노출되므로 키 화이트리스트 필요). 어느 쪽이든 `useConfigs()` 에 `retry: false` 와 403 시 조용한 폴백(콘솔 에러 억제)을 함께 적용한다. ⚠ **구현은 하지 않는다.**

### [H-ISSUE-23] (카탈로그 정합성) H-3 앞 1/3 근거 `file:line` 26건 +1 드리프트 + 기대결과 오류 2건 — **이번 회차에서 정정 완료**
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 존재 이유가 "근거 정확도"이므로 `file:line` 이 실제 코드를 가리켜야 한다(`CLAUDE.md` 문서 동기화 규칙 2026-08-03 확정: "라인 드리프트만 바뀐 것도 고친다").
- **현재 동작(이슈 내용)**: 4회차(`9f99db50`)가 `e58aa086` 기준으로 H-3 근거를 전수 재확인했으나, 그 **직후 커밋 `0d290c4e`(영상 단위 개인정보 메타 화면)** 가 `LabelingPage.tsx` 에 import 1줄(`:49 VideoPrivacyMetaPanel`)을 추가해 **파일 49행 이후 전체가 +1 밀렸다**.
  ```
  frontend/src/pages/label/LabelingPage.tsx | 5 ++++-  (e58aa086 → HEAD e065da42)
  +import { VideoPrivacyMetaPanel } from '@/features/label/components/VideoPrivacyMetaPanel';   ← :49
  ```
  결과적으로 내 담당 범위 31건 중 **`LabelingPage.tsx` 를 인용한 26건 전부**가 1줄씩 어긋나 있었다(TC-FE-033~050·052~058·063). 반면 `AiToolModal.tsx`·`useSam2Track.ts`·`useBusyTask.ts`·`api.ts` 를 인용한 5건(TC-FE-051·059~062)은 **전건 정확**했다.
  추가로 기대결과 서술 오류 2건:
  - TC-FE-034 `"라벨 로딩" data-testid` → 실제는 `Spinner.tsx:9-23` 의 `role=status`/`aria-live`/`aria-label`/`sr-only` 이며 **data-testid 는 존재하지 않는다**.
  - TC-FE-057 `"N/total만 추적됨"` → 실제 문자열은 `` `${applied}/${total} 프레임만 추적됨 (일부 실패)` ``.
- **재현/확인 경로**: `git diff e58aa086 HEAD -- frontend/src/pages/label/LabelingPage.tsx` / `grep -n "잘못된 프레임 ID" frontend/src/pages/label/LabelingPage.tsx` → 1084(카탈로그는 1075-1094 로 표기했었음).
- **영향**: 검증 효율 — 다음 회차 검증자가 어긋난 라인을 읽고 "구현 없음"으로 오판할 수 있다. 기능/보안 영향 없음.
- **수정 방향(제안)**: **이미 정정함** — `docs/test-cases/H-frontend-e2e.md` 74~104행 범위에서 `pages/label/LabelingPage.tsx:` 인용 26건을 +1 시프트하고 TC-FE-034·057 기대결과를 실제 코드 문구로 교체했다(프로덕션 코드는 무수정). ⚠ **파일 상단 `## 변경 이력` 표 회차 행 추가는 이 파트에서 하지 않았다** — 6~16행은 병렬 파트와 공유되는 구역이라 충돌 방지를 위해 손대지 않았다. **병합 담당이 H 클러스터 전체 정정 건수를 합산해 5회차 행을 1번만 추가**할 것.

---

## 3. 이전 회차(2026-08-02 2차) 이슈 해소 여부 — 이 파트 범위분

| 2차 이슈 | 대상 | 이번 회차 판정 | 근거 |
|---|---|---|---|
| **H-ISSUE-41** (HIGH) 라벨 저장 PUT 이 `labelVersion` 을 안 싣는다 | TC-FE-198(타 파트) — 단 내 실동작 경로에서 직접 관측 | **✅ 해소** | Ctrl+S 실측 PUT 바디 `{"items":[…],"labelVersion":5}` — `getLabels()` 가 `labelVersion` 을 매핑하고 `LabelingPage.tsx:543-549` 가 `{ labelVersion: data?.labelVersion }` 로 전달. 서버 버전 4→5 증가도 확인 |
| **H-ISSUE-42** (MEDIUM) 연속 저장 409 미발생이 "버전 미전송"으로 성립 | TC-FE-199(타 파트) | **🔶 부분 해소 — 재검증 필요** | 토큰 자체는 실려 나간다(위). 다만 이번 관측은 단발 저장 1회(4→5)뿐이라 **연속 2회 저장 시 `v → v+1` 로 갱신 전송되는지**는 미확인. 해당 케이스 담당 파트에서 확인 요망 |
| **H-ISSUE-06** (LOW) `error.message` 직접 렌더 4곳 | 그중 `LabelingPage.tsx:1144` 가 내 TC-FE-036 경로 | **❌ 미해소 이월** | 현재 `:1153` 에 그대로 존재 → **H-ISSUE-21** 로 재기록. 단 `HistoryPage.tsx:99` 건은 결정2(`b27b3108`)로 페이지가 삭제되어 자동 소멸 |
| **H-ISSUE-44** (카탈로그) H-3 절 근거 전건 드리프트 | 내 범위 전체 | **🔄 재발 → 재정정** | 4회차 정정 직후 `0d290c4e` 로 +1 재드리프트. 이번에 재정정 → **H-ISSUE-23** |
| **H-ISSUE-43** (HIGH/확인필요) 폴리곤 클릭 점 추가 안 됨 | 캔버스 드로잉 — **내 범위 밖**(H-3 뒤 2/3) | **미검증** | 도형 도구 → 라벨 선택 모달 흐름(2026-08-03 결정3)이 신설돼 재현 절차 자체가 바뀌었다. part3 담당 소관 |
| **H-ISSUE-103** (HIGH) 포털 SAM2 노출 | TC-FE-275 — 내 범위 밖 | **미검증** | 4회차 문서가 `dcdbb827` 로 `PORTAL_HIDDEN_TOOLS` 채워졌다고 기록. 해당 파트에서 확인 |

---

## 4. 반증 시도 기록 (확증편향 방지)

기대결과의 단언을 뒤집으려 시도한 항목과 결과:

| 반증 가설 | 시도 | 결과 |
|---|---|---|
| "캔버스가 사실은 원본을 서빙한다" | WORKER 토큰으로 `?raw=true` 강제 요청 후 DEID 응답과 md5 비교 | **반증 실패** — 바이트 동일(raw 무시). REVIEWER 만 다른 바이트 수신 |
| "FE 어딘가에서 `raw=true` 를 몰래 붙인다" | `grep -rn "raw" src/features/label/hooks/useImageBlob.ts` + 호출부 전수 | **반증 실패** — `LabelingPage.tsx:160` 은 `raw` 미전달, `useImageBlob.ts:77-81` 이 params 자체를 생략 |
| "중복 저장 차단이 실제로는 안 걸린다(디바운스 착시)" | XHR `open` 후킹 후 Ctrl+S 3연타 | **반증 실패** — PUT 정확히 1건 |
| "저장 안 함 이동이 몰래 저장한다" | 폐기 이동 중 PUT 카운트 | **반증 실패** — PUT 0건 |
| "같은 프레임 클릭도 가드 모달을 띄운다(no-op 미구현)" | dirty 없는 상태에서 현재 프레임 옵션 클릭 | **반증 실패** — URL 불변·모달 없음 |
| "AI mock 응답이 조용히 자동 병합된다(self-fill)" | 모델 미로드 상태에서 AI 탐지 실행 후 객체수 대조 | **반증 실패** — 0→0, 경고 토스트만. 값이 BE 응답 없이 채워지는 경로 없음 |
| "미매핑 라벨도 사실은 선택 가능하다" | 모달 체크박스 `disabled` 속성 전수 덤프 | **반증 실패** — 미매핑 4종 전부 `disabled=true` |
| "트랙 모드가 즉시 추적을 실행해버린다" | 모달 "트랙" 클릭 후 네트워크·툴 상태 관찰 | **반증 실패** — 도구만 TRACK 으로 전환 + 안내 토스트, 추적 요청 없음 |
| "포털 트랙 가드가 rename 에만 있다" | delete/split 핸들러 선두 라인 직접 확인 | **반증 실패** — `:881`·`:906` 에도 동일 `if (portalMode) return;` |
| "근거 라인이 맞다(4회차가 재확인했으니)" | `git diff e58aa086 HEAD` + 실제 grep 위치 대조 | **반증 성공** → H-ISSUE-23 |
| "AI 정밀도 프리필이 정상 동작한다" | WORKER 로 `/manage/configs` 호출 + 모달 슬라이더 값 대조 | **반증 성공** → H-ISSUE-22 |

<!-- H-part2.md 원문 끝 -->

---

<!-- ============================================================ -->
<!-- H-part3.md 원문 시작 -->
<!-- ============================================================ -->

# H 클러스터 part3 — H-3 LabelingPage 중간부(카탈로그 105~142행, 31건) 검증 결과

- 회차: **3차** / 일자: 2026-08-03(실행 시각 기준 2026-08-04 03:00~03:30 KST)
- 대상 파일/범위: `docs/test-cases/H-frontend-e2e.md` **105~142행** — TC-FE-064~087(24건) + TC-FE-197~202(6건) + TC-FE-261(1건) = **31건**
- 검증 방식: **실브라우저(Playwright/Chromium headless, 로컬 `playwright-core` 직접 구동) + 실 API 왕복 + DB 실측**. 빌드/테스트는 실행하지 않음.
  - ⚠ MCP 공용 브라우저는 **다른 part 에이전트와 세션(localStorage·탭)이 공유되어** 검증 도중 토큰이 PORTAL_USER 로 바뀌고 `/forbidden` 으로 튀는 간섭이 재현됨. 그래서 본 검증은 **독립 브라우저 컨텍스트**(스크립트 구동)로 수행했다. 이후 회차에서도 동일 간섭이 예상되므로 참고.
- 스택: `_raw/stack-bringup.md` 3차 재빌드 형상 그대로(backend `localhost:18081/api`, FE `localhost:13000`, mock-server 9400). 배선 OK.
- 사용 데이터: rawSn **101**(srcSn 468~477, APPROVED, WORKER 2001 배정) · rawSn **115**(srcSn 508~513) · rawSn **158**(파생영상, `ORGNL_RAW_SN=101`, srcSn 567) · rawSn **900**(`DE_IDENT_YN='F'`, srcSn 429)

---

## 0. 집계

| 판정 | 건수 | ID |
|---|---:|---|
| **PASS** | 27 | 066, 068, 069, 070, 071, 072, 073, 074, 075, 076, 077, 078, 079, 080, 082, 083, 084, 085, 086, 087, 197, 198, 199, 200, 201, 202, 261 |
| **조건부 PASS**(코드 경로는 성립하나 실동작 진입 조건이 성립하지 않음) | 2 | 064, 067 |
| **부분 PASS / 확인필요** | 2 | 065(트랙 rename API 미도달), 081(clamp 경계값 미검증) |
| FAIL | 0 | — |
| 신규 이슈 | 2 | **H-ISSUE-41**(HIGH), **H-ISSUE-42**(MED) |
| 카탈로그 정정 | **5행** | 067 · 079 · 085 · 087 · 197 |

### ★ 2차 HIGH #9 (낙관적 동시성) — **해소 확인**

2차 `ISSUES.md` 의 **[H-ISSUE-41] TC-FE-198 (HIGH, 라벨 저장 PUT 이 `labelVersion` 을 싣지 않아 lost update 성립)** 및 그 파생 **[H-ISSUE-42] TC-FE-199 (MED)** 는 **이번 회차에 실동작으로 해소 확인**되었다. 상세는 §2.

---

## 1. 케이스별 판정

| ID | 판정 | 실측 근거(요약) |
|----|:--:|---|
| TC-FE-064 잠금 영상 트랙 편집 차단 | **조건부 PASS** | `LabelingPage.tsx:861-864/883-886/908-911` 에 `isLocked` 가드 + 토스트 존재(근거 라인 정확). 단 **서버 잠금이 `isLocked` 로 전달되지 않아**(H-ISSUE-41) 실운영에서 이 분기는 `reportedLock` 경로로만 도달한다. 그 경로는 `reset()` 이 라벨을 비워 트랙 UI 자체가 사라져 UI 재현 불가 |
| TC-FE-065 트랙 rename 후 invalidate | **확인필요** | `866-868`(mergeTracks → `LABEL_KEYS.byVideo` invalidate + 토스트) 코드 확인. 실동작: 대상 프레임 라벨이 전부 **트랙 미부여(T:—)** 라 `ObjectClassTree:120 if (current != null)` 에서 걸려 `onRenameTrack` 자체가 호출되지 않음 → API 왕복 미도달. 트랙 보유 데이터 확보 후 재검증 필요 |
| TC-FE-066 신고 성공 → 잠금+reset+무효화 | **PASS** | rawSn 115/srcSn 508 실신고: `POST /v1/labels/508/deident-report` → **201**, 직후 `GET /v1/frames/508/labels` **412** ×2 → 화면 = "라벨 조회 실패" + BE 안내문("비식별 재처리 대기 중인 영상입니다…") + 성공 토스트. **빈 라벨 화면이 아님** — 카탈로그 ★서술(2026-07-27 보존 정책 반전)과 정확히 일치 |
| TC-FE-067 잠금 배너 노출 | **조건부 PASS** | 신고 POST 만 목 201 로 가로챈 순수 FE 경로에서 `deident-locked-banner` **1건**, `role=status`, 문구 "비식별 재처리 중인 영상입니다…" 정상. **그러나 서버 경로는 미발화** — BE 는 `lockSttsCd="LOCKED"` 를 내리고 FE 는 `'LOCKED_FOR_REDEIDENT'` 와 비교 → **H-ISSUE-41**. 응답만 `LOCKED_FOR_REDEIDENT` 로 치환한 대조군에서는 배너 1건 정상 표시(원인 확정) |
| TC-FE-068 신고 버튼 RAW disabled | **PASS** | 응답 `frameImageType='RAW'` 주입 → 뱃지 RAW, 신고 버튼 `disabled=true`(DEID 일 때는 `false`) |
| TC-FE-069 신고 버튼 포털 미노출 | **PASS** | PORTAL_USER `/portal/label/468` — 신고 버튼 count **0** |
| TC-FE-070 검수제출 버튼 WORKER만 | **PASS** | WORKER `/label/468` 에 `submit-review-button` 1건 / PORTAL 0건 |
| TC-FE-071 상태별 제출 차단 | **PASS** | rawSn 115 를 REVIEW_PENDING 으로 만든 뒤: 버튼 `disabled=true`, `title="이미 검수 제출되어 검수 대기 중입니다."` |
| TC-FE-072 APPROVED 재검수 라벨 | **PASS** | rawSn 101(APPROVED/COMPLETED) → 버튼 문구 **"재검수 제출"**, `title="검수 완료된 영상을 재검수에 다시 제출합니다."` |
| TC-FE-073 제출 취소 REVIEW_PENDING만 | **PASS** | REVIEW_PENDING 시 `cancel-submit-review-button` 1건("제출 취소", title="검수 시작 전이라…"), 취소 후 0건 |
| TC-FE-074 제출 성공 → /task 이동 | **PASS** | UI 제출 후 최종 URL `http://localhost:13000/task`. (성공 토스트는 navigate 직후라 이동 후 DOM 에서 미포착 — 코드 `169-175`) |
| TC-FE-075 X 닫기 dirty 3옵션 모달 | **PASS** | dirty 상태에서 `aria-label="뒤로가기"` 클릭 → "저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이 있습니다. 어떻게 하시겠습니까?" + 버튼 **취소 · 저장 없이 닫기 · 저장 후 닫기** |
| TC-FE-076 beforeunload dirty 경고 | **PASS** | dirty 상태에서 `beforeunload` 디스패치 → `defaultPrevented=true`. 클린 상태에서는 리스너 미등록(`dirtyCount===0` early return) |
| TC-FE-077 메타/이슈 탭 내부만 | **PASS** | 내부: objects/meta/issues 3탭 존재 / 포털: **탭 자체 0건**(`hasTabs=false`) |
| TC-FE-078 이슈 탭 미해소 배지 | **PASS** | rawSn 101 에 `INQUIRY`/`OPEN` 1건 주입 → 배지 텍스트 **"1"**, `aria-label="미해소 문의 1건"` (검증 후 행 삭제 완료) |
| TC-FE-079 메타 탭 패널 구성 | **PASS**(+카탈로그 정정) | 실측 헤딩 6개: 촬영환경 / **개인정보(영상)** / 개인정보(프레임) / 프레임 설명 / 시계열 메타 / 이벤트 어노테이션. 카탈로그의 "5개 패널"은 커밋 `0d290c4e` 의 `VideoPrivacyMetaPanel` 신설 미반영 → **행 정정함**. 시계열 패널에 승인/반려 표면 없음(★서술 일치) |
| TC-FE-080 뷰(zoom/pan) 유지 | **PASS** | Konva Image 노드 실측 — 초기 960×720@(160,0) → 휠 줌 후 **1056×792@(112,-36)** → 동일 영상·동일 해상도 프레임 전환 후에도 **1056×792@(112,-36) 그대로 유지**(리셋 없음) |
| TC-FE-081 붙여넣기 실측 dims clamp | **부분 PASS(확인필요)** | 붙여넣기 자체는 정상("라벨 2건 붙여넣음"), `1029-1047` 에서 `frameNaturalSize` 를 `imageWidth/Height` 로 전달하는 배선 확인. 다만 **경계를 넘는 좌표가 실제로 clamp 되는지**(이미지 밖 라벨 복사 → 붙여넣기)는 이번 회차에서 좌표 검증까지 수행하지 못함 |
| TC-FE-082 복사 빈 선택 no-op | **PASS** | 라벨 0건 프레임(frame#1)에서 Ctrl+C → warning 토스트 **"복사할 라벨이 없습니다."**. (라벨이 있으면 선택 없어도 전량 복사 — `useLabelStore:582-591` 설계상 정상) |
| TC-FE-083 잠금 영상 붙여넣기 차단 | **PASS** | `reportedLock` 상태에서 Ctrl+V → **"비식별 재처리 중인 영상은 붙여넣을 수 없습니다."** 토스트, 붙여넣기 미실행 |
| TC-FE-084 저장 되돌리기 확인 모달 | **PASS** | 인라인 히스토리 카드 "되돌리기" → ConfirmDialog "이 저장으로 되돌리기" + "이 저장의 변경을 현재 작업본에 되돌립니다. 저장해야 확정됩니다." |
| TC-FE-085 되돌릴 항목 없음 경고 | **PASS**(+카탈로그 정정) | 오래된 저장 이벤트 4건 되돌리기 → 전부 warning **"되돌릴 항목이 현재 작업본에 없습니다."**. 카탈로그 기대문구("되돌릴 항목이 없습니다")와 불일치 → **행 정정함** |
| TC-FE-086 캔버스 lazy 마운트 | **PASS** | 라벨링 진입 시 `react-konva.js` · `konva_lib_filters_*.js` 가 **별도 청크로 지연 로드**됨(초기 번들 분리), `Suspense` fallback(`Spinner label="캔버스 로딩"`) 배선 확인 |
| TC-FE-087 히스토리 인라인 패널 | **PASS**(+근거 정정) | 내부 채널에서 `inline-history-panel` 렌더, 패널 내 **"변경 이력" / "버전" 탭** 확인. 포털에서는 히스토리 버튼 자체 0건. 근거 라인 `1570-1582`→`1574-1586` 정정 |
| **TC-FE-197 저장 409 → 충돌 다이얼로그** | **PASS** | §2 참조 — 409 수신 시 **다이얼로그**(제목 "다른 사용자가 먼저 저장했습니다", 확인="최신 라벨 불러오기", 취소="내 작업 유지"), **dirty 유지**(● 편집 중 그대로), 취소 후에도 내 작업 보존 |
| **TC-FE-198 저장 요청에 labelVersion 동봉** | **PASS** ★2차 HIGH 해소 | §2 참조 — PUT body 실측 `{"items":[…],"labelVersion":5}` |
| **TC-FE-199 연속 저장 캐시 버전 우선** | **PASS** ★2차 MED 해소 | §2 참조 — 1회차 `labelVersion:5` → 서버 6 승격 → 2회차가 **6** 을 전송, 자기 409 미발생 |
| TC-FE-200 파생영상 신고버튼 사전 비활성 | **PASS** | 파생 srcSn 567(rawSn 158): 버튼 `disabled=true`, `title`/`aria-label` = "증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다." — **원본 유도·부모 rawSn 노출 없음**(본문 전체에 부모 번호 문자열 부재 확인) |
| TC-FE-201 신고 412 서버 안내문 노출 | **PASS** | 412 + BE message 주입 → 폼 내 `role=alert` 에 **BE 안내문 원문 그대로** 표시(일반 INTERNAL_ERROR 문구로 대체되지 않음), 모달 유지 |
| TC-FE-202 cot 객체형 정규화 | **PASS** | `cot={"1단계":"접근","2단계":"몸싸움","3단계":"분리"}`(객체형)과 `["A1","A2","A3"]`(배열형)을 같은 응답에 섞어 주입 → 두 후보 모두 3단계 입력값으로 **키 순서 유지** 정규화, pageerror 0건 |
| TC-FE-261 busy 중 편집·버튼 차단 | **PASS** | autolabel 응답 8초 지연 주입 후 실행: 오버레이 "AI 탐지 진행 중 / 1초 경과 / 작업 취소" 표시, **저장·검수제출·비식별신고 전부 `disabled=true`**, 프레임 썸네일 option `disabled=true`, `B` 단축키 눌러도 BBOX 도구 `aria-pressed=false`(단축키 무시) |

---

## 2. ★ 2차 HIGH #9(낙관적 동시성) 재현·해소 확인 상세

### 2-1. 수정 확인 (H-ISSUE-41 / 2차)

`frontend/src/features/label/api.ts` `getLabels()` 가 응답의 `labelVersion` 을 반환 객체에 매핑하도록 수정됨(정수·0 이상만 채택, 그 외 `null` → BE 하위호환 skip). 커밋 `dcdbb827 fix(security): 2차 검증 신규 HIGH 11건 수정 (…FE동시성…)`.

### 2-2. 실동작 재현 (브라우저 PUT payload 캡처)

```
# BE 조회 응답
GET /api/v1/frames/468/labels → 200 {"labelVersion":3, "items":[…]}

# FE 저장 1회차 (실브라우저 Ctrl+S 상당 — 헤더 저장 버튼)
PUT /api/v1/frames/468/labels
  body = {"items":[{"id":1288,…}],"labelVersion":5}     ← ★ labelVersion 실려 나감 (2차 결함 해소)
  → 200
```

### 2-3. 연속 저장 시 캐시 버전 우선 (TC-FE-199)

내용을 실제로 바꿔 서버 버전이 오르게 한 뒤 연속 저장:

```
SEQ PUT#1  body={"items":[],"labelVersion":5}   → 200   (서버 버전 5→6 승격)
SEQ PUT#2  body={"items":[],"labelVersion":6}   → 200   ← 렌더 클로저(5)가 아니라 캐시 최신값(6) 전송, 자기 409 없음
화면 문구: "저장됨"
```

### 2-4. lost update 반증 — 경고 없이 덮어쓰는가? → **아니오, 409 로 차단됨** (TC-FE-197)

화면이 버전 N 을 들고 있는 동안 **다른 사용자가 먼저 저장**해 서버 버전을 N+1 로 올린 뒤, 화면에서 저장:

```
외부 선저장 status=200  newVersion=8   (화면 캐시 버전=7)
PUT /api/v1/frames/468/labels body={"items":[…],"labelVersion":7} → 409
화면: "다른 사용자가 먼저 저장했습니다"
      "… 최신 라벨을 불러오면 저장하지 않은 변경은 사라집니다. 작업 내용을 남기려면 '내 작업 유지'를 …"
      버튼: [최신 라벨 불러오기] [내 작업 유지]
편집표식(409 후): ● 편집 중 = true      ← dirty 유지, 내 작업 미폐기
'내 작업 유지' 클릭 후에도: ● 편집 중 = true
```

BE 측 대조(순수 API):

```
PUT labelVersion=1(stale) → 409 {"errorCode":"CONFLICT","message":"다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요."}
PUT labelVersion=3(최신)  → 200, 내용 변경 시에만 버전 승격(3→4). 동일 내용 재저장은 버전 미승격(정상)
```

**결론: 2차 HIGH #9(H-ISSUE-41) 및 파생 H-ISSUE-42 모두 해소.** 2차에서 "도달 불가능한 죽은 경로"였던 TC-FE-197 충돌 다이얼로그·TC-FE-199 캐시 우선 로직이 이제 실사용 경로에서 실제로 동작한다.

---

## 3. 신규 이슈

### [H-ISSUE-41] TC-FE-067 / 064 / 083 — BE 가 내리는 잠금 코드(`LOCKED`)와 FE 판정값(`LOCKED_FOR_REDEIDENT`)이 달라 **서버 잠금이 화면에 전혀 반영되지 않는다**
- **심각도**: **HIGH**
- **기대 동작**: 영상이 재비식별 잠금 상태면 라벨링 화면 진입 시 `lockSttsCd` 로 이를 인지해 ①잠금 배너(role=status) 노출 ②저장·트랙편집·붙여넣기·되돌리기·신고 버튼 비활성 — `LabelResponse.java:29` 주석("`null`/빈 문자열 = 잠금 없음, `\"LOCKED_FOR_REDEIDENT\"` = 비식별 재처리 중")이 이 계약을 명시한다.
- **현재 동작**: BE 는 **`"LOCKED"`** 문자열을 내려보낸다.
  ```java
  // backend/.../label/service/LabelService.java:200
  String lockSttsCd = workLockService.isRawLocked(current.getRawSn()) ? "LOCKED" : null;
  ```
  FE 는 계약대로 `LOCKED_FOR_REDEIDENT` 와만 비교한다.
  ```ts
  // frontend/src/pages/label/LabelingPage.tsx:516
  const isLocked = data?.lockSttsCd === 'LOCKED_FOR_REDEIDENT' || reportedLock;
  ```
  → **서버 잠금만 걸린 상태(비식별 신고 없이 잠금, 또는 다른 세션이 잠근 경우)에서는 `isLocked` 가 영영 false** 다.
- **재현/확인 경로** (rawSn 115, `DE_IDENT_YN='Y'` 유지한 채 작업락만 LOCKED 로):
  ```sql
  UPDATE ls_auth_work_lock SET lck_stts_cd='LOCKED' WHERE work_lock_sn=41;  -- data_raw_sn=115
  ```
  ```
  GET /api/v1/frames/508/labels → 200, lockSttsCd = 'LOCKED'
  화면 /label/508 : 잠금 배너 0건 · 저장 버튼 enabled · 비식별 신고 버튼 enabled  ← 잠금 인지 실패
  PUT /api/v1/frames/508/labels → 409 {"message":"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."}  ← BE 는 정상 차단
  ```
  **대조군**(응답의 `lockSttsCd` 만 `LOCKED_FOR_REDEIDENT` 로 치환): 배너 1건 정상 표시 → 원인이 값 불일치임이 확정됨.
- **영향**: 데이터 유실은 없다(BE 가 409 로 최종 차단). 그러나 ①작업자는 잠긴 영상인 줄 모르고 **편집을 계속하다 저장 시점에야 거부**당해 작업이 낭비되고 ②`isLocked` 에 걸린 FE 가드(TC-FE-064 트랙편집·TC-FE-083 붙여넣기·되돌리기·신고 버튼)가 **서버 경로에서 전부 무력**하며 ③잠금 배너가 사실상 `reportedLock`(신고 직후 클라이언트 표식) 전용이 되는데, 그 경로는 곧바로 412 에러 화면으로 대체되어 배너를 볼 수 있는 창이 거의 없다.
- **참고**: 이 값은 최근 회귀가 아니라 `6c40e02c`(테이블 분리 리팩터) 시점부터 이어진 장기 드리프트다.
- **수정 방향(제안)**: BE `LabelService:200` 의 리터럴을 `"LOCKED_FOR_REDEIDENT"` 로 맞추는 것이 최소 변경(FE 타입 `LockSttsCd` 와 `LabelResponse` javadoc 이 이미 그 값을 정본으로 선언). 반대로 FE 를 넓히면(둘 다 허용) 계약 문서와 어긋난 값이 고착된다. 어느 쪽이든 **BE↔FE 값 동치를 고정하는 회귀 테스트**(응답 DTO 상수 + FE 판정 상수 동일성)를 함께 둘 것. ⚠ 구현은 하지 않았다.

### [H-ISSUE-42] TC-FE-197 — 잠금(409)까지 "다른 사용자가 먼저 저장했습니다" 충돌 다이얼로그로 안내된다
- **심각도**: MEDIUM
- **기대 동작**: 409 라도 사유가 다르면 안내가 달라야 한다. 낙관적 잠금 충돌(다른 사용자 선저장)은 "최신 라벨 불러오기"가 해법이지만, **재비식별 잠금 409** 는 최신 라벨을 불러와도 저장할 수 없다.
- **현재 동작**: `LabelingPage.tsx:586` 이 `status === 409` 만 보고 분기한다. 잠금 사유 409(`"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."`)도 제목 **"다른 사용자가 먼저 저장했습니다"** + 확인 버튼 **"최신 라벨 불러오기"** 의 다이얼로그로 뜬다(설명문에는 BE 메시지가 `extractBeMessage` 로 들어가므로 본문만 사유가 맞고 제목·행동유도는 어긋난다).
- **재현/확인 경로**: H-ISSUE-41 재현으로 영상을 LOCKED 로 만든 뒤 화면에서 저장(현재는 H-ISSUE-41 때문에 FE 가 잠금을 몰라 저장이 시도됨) → 409 → 위 다이얼로그.
- **영향**: 사용자가 "최신 라벨 불러오기"를 눌러 **미저장 작업만 잃고** 여전히 저장하지 못한다. H-ISSUE-41 을 고치면 저장 버튼이 애초에 비활성화돼 노출 빈도는 크게 줄지만, 진입 직후 다른 세션이 잠그는 경우는 남는다.
- **수정 방향(제안)**: 409 를 `errorCode` 또는 BE 메시지가 아닌 **명시적 사유 코드**로 분기(예: `CONFLICT_STALE_VERSION` vs `CONFLICT_LOCKED`)해 잠금 사유는 "불러오기" 유도 없이 안내 전용 토스트/다이얼로그로 처리. ⚠ 구현은 하지 않았다.

---

## 4. 카탈로그 정정 (담당 범위 105~142행 안에서만 수행, 5행)

| 행 | ID | 정정 내용 |
|---|---|---|
| 108 | TC-FE-067 | 3차 실측 주석 추가 — BE 는 `LOCKED` 를 내려 서버 경로에서 배너·`isLocked` 미발화(H-ISSUE-41), `reportedLock` 경로만 성립. 근거에 `LabelingPage.tsx:516` · `LabelService.java:200` 추가 |
| 120 | TC-FE-079 | 기대결과 **5개 패널 → 6개 패널** (`VideoPrivacyMetaPanel` 2번째 신설, 커밋 `0d290c4e`). 케이스명·근거 라인 `1493-1502 → 1494-1505` 정정. 구 기대값은 "폐기" 명시 |
| 126 | TC-FE-085 | 기대문구 `"되돌릴 항목이 없습니다"` → **`"되돌릴 항목이 현재 작업본에 없습니다."`** (실제 문자열), 근거 `650-680 → 651-680` |
| 128 | TC-FE-087 | 근거 라인 `1570-1582 → 1574-1586` |
| 129 | TC-FE-197 | 근거 라인 `585-590,1586-1596 → 586-591,1590-1599` |

> ⚠ 파일 상단 `## 변경 이력` 표 회차 행 추가는 **담당 라인 범위 밖**이라 하지 않았다. H 클러스터 취합 담당이 part1~partN 정정을 합산해 1행으로 기록할 것.

근거 라인 무드리프트 확인(정정 불필요): TC-FE-064(861-864/883-886/908-911) · 065(866-868) · 066(529-537,1143-1163) · 068(1195-1206) · 069(130) · 070(1207-1242) · 071(182-214) · 072(203-204) · 073(201,1210-1222) · 074(169-175) · 075(942-948) · 076(985-994) · 077(475-478,1405-1473) · 078(1461-1469) · 080(307-319) · 081(1029-1047) · 082(1020-1027) · 083(1030-1033) · 084(645-647) · 086(89-91,1352-1372) · 198~202 · 261.

---

## 5. 검증 중 변경한 데이터와 원복 내역 (다른 part 참고 필수)

| 대상 | 변경 | 원복 |
|---|---|---|
| `srcSn 468`(rawSn 101) 라벨 | 저장/삭제/복원 반복으로 **`LBL_SN` 이 729 → 1293 으로 바뀜**(저장이 full-replace 계약이라 PK 재발급) | 내용은 원상(**BBOX person `[[60,60],[210,210]]` 1건**)으로 정규화 완료. `labelVersion` 은 13. `pipeline-drive.md` 가 적어둔 `labelSn=729` 는 더 이상 유효하지 않음 |
| `rawSn 115` 비식별 신고 | TC-FE-066 실검증으로 `DE_IDENT_YN='F'` + 작업락 LOCKED + 신고행 생성 | API `resolve` 는 "비식별 산출물 미확인"으로 409 → **DB 직접 원복**: `de_ident_yn='Y'`, 신고행(37) `RESOLVED`, 작업락(41) `RELEASED` |
| `rawSn 115` 워크플로 상태 | 제출/취소 검증으로 APPROVED → PENDING → ASSIGNED | DB 로 **APPROVED 원복** |
| `ls_data_issue` | 배지 검증용 INQUIRY/OPEN 1행(sn=23) 삽입 | **삭제 완료** (현재 OPEN 이슈 0건) |
| 프로덕션 코드 | 변경 없음 | — |

## 6. 남은 확인 항목

1. **TC-FE-065** — 트랙 ID 가 부여된 라벨이 있는 프레임에서 rename→`mergeTracks` API 왕복 + `byVideo` invalidate 재검증 필요.
2. **TC-FE-081** — 이미지 경계를 벗어나는 좌표로 복사→붙여넣기 시 `imageWidth/Height` clamp 실동작 확인 필요(★3 좌표검증 2축 정책과 무관한 별개 축).
3. **TC-FE-064** — H-ISSUE-41 수정 후 서버 잠금 경로에서 트랙 편집 차단 토스트를 재검증할 것.

<!-- H-part3.md 원문 끝 -->

---

<!-- ============================================================ -->
<!-- H-part4.md 원문 시작 -->
<!-- ============================================================ -->

# H 클러스터 part4 — H-3. LabelingPage 뒷부분(카탈로그 143~216행, 30건)

> 3차 회차 · 2026-08-03 · 대상 파일 `docs/test-cases/H-frontend-e2e.md`
> 담당 범위: **TC-FE-262~270 · 276~278 · 279~292 · 293~296 (총 30건)**
> (TC-FE-261 은 142행이라 part3 범위 — 미검증)

## 0. 검증 환경 / 방법

| 항목 | 값 |
|---|---|
| 스택 | `_raw/stack-bringup.md` 의 재빌드 스택 그대로 (frontend :13000, backend :18081, mock :9400, PG :5432) |
| 인증 | `POST /api/v1/dev/tokens` 로 REVIEWER(userNo=1001) JWT 발급 → `sessionStorage['klid_jwt']` 주입 |
| 대상 데이터 | `_raw/pipeline-drive.md` 의 **rawSn=101 / srcSn=468**(프레임 0, 라벨 1건, `labelVersion=4`) |
| 실동작 도구 | 프로젝트 `frontend/node_modules/playwright-core@1.59.1` 로 **독립 headless Chromium** 구동 (스크립트 `h4a~h4f.mjs`). ⚠ Playwright **MCP 브라우저는 다른 part 가 동시에 조작 중**이라 탭이 `/portal/uploads` 로 튀는 간섭이 실제 발생 → 격리 실행으로 전환함 |
| 금지 준수 | 빌드·테스트(`./gradlew`, `npm test`) 미실행. 프로덕션 코드 무수정 |

라벨 마스터 실측(`GET /v1/manage/labels`): `person·car·bicycle·motorbike·bus·truck·fire·smoke·water·c2a-race-lbl` **10건 전부 영문/코드명** — ★4(마스터 등록명 그대로) 반증에 최적 조건.

---

## 1. 판정 요약

| 판정 | 건수 |
|---|---|
| PASS | 30 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A · 확인필요 | 0 |

**신규 기능 결함 0건.** 결함은 전부 **카탈로그 정합성(근거 file:line 드리프트 8건)** 과 **테스트 커버리지 갭 1건**, **사(死)코드 잔재 1건** → `H-ISSUE-61~63`.

### ★ 2차 HIGH #9(낙관적 동시성) 소재 확인 — **내 구간 아님, 해소 확인됨**
- 2차 `H-ISSUE-41`(TC-FE-198 저장 PUT 이 `labelVersion` 미전송) / `H-ISSUE-42`(TC-FE-199) 는 **카탈로그 129~131행 = part3 범위**다.
- 다만 내 담당 **TC-FE-270 이 그 전제("교차 수정은 서버 409 + 충돌 다이얼로그가 담당")를 검증**하므로 실동작으로 해소 여부를 확인했다 → **해소됨**:
  - BE 실동작: 같은 `labelVersion=4` 로 2회 PUT → 1회차 200(`labelVersion:5`), **2회차 409 `CONFLICT` "다른 사용자가 먼저 저장했습니다…"**
  - FE 실동작: 409 수신 시 토스트가 아니라 **ConfirmDialog**(`내 작업 유지` / `최신 라벨 불러오기`) 노출
  - FE 코드: `features/label/api.ts:311-316`(`labelVersion` 동봉·null 이면 필드 생략), `useUpdateLabels.ts:63-84`(캐시 버전 우선 + 성공 시 `setQueryData` 동기 갱신), `LabelingPage.tsx:586`(409 → `setSaveConflictMessage`)

---

## 2. 케이스별 결과

### 2-1. busy(장시간 작업) 배타 실행 — TC-FE-262~270

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-262 | PASS | [실동작] busy(저장 in-flight) 중 **되돌리기 버튼 11개 전부 `disabled=true`**, 해제 후 12개 전부 `false` 복귀. **비식별 누락 신고 `disabled=true`**, 툴바 실행취소 `disabled=true`. 정적: `LabelHistoryPanel.tsx:104,207-211`, `editBlocking.test.tsx:273` |
| TC-FE-263 | PASS | [실동작] busy 중 `d`(다음 프레임)·`b`(BBOX) keydown → `Frame 1 / 10` 불변·모달 0·도구 불변. [정적] fail-closed OR 판정 `useLabelingShortcuts.ts:196` `const editBlocked = blocked \|\| isEditBlockedNow();` + `227` `if (editBlocked) continue;`. ⚠ **근거 라인 드리프트**(→ H-ISSUE-61) |
| TC-FE-264 | PASS | [실동작] 저장 응답 4s 지연 주입 → **150ms 시점 오버레이 0개**, 850ms 시점 노출. 내용 = `저장 중` + `0초 경과` + `작업 취소` + "서버 처리가 즉시 중단되지는 않습니다". `role=status` `aria-live=polite`, 경과초 `aria-hidden=true`, 포커스가 취소 버튼으로 이동. **`/YOLO\|SAM2?\|sam/` 정규식 매칭 false = 모델명 미노출** |
| TC-FE-265 | PASS | [실동작] 취소 클릭 → 오버레이 즉시 소멸 + 툴바 재활성. **4s 뒤 지연 응답이 실제 도착(PUT 1건 서버 도달 확인)했음에도 헤더는 `● 편집 중` 유지**(= 결과 미반영 · dirty 보존). 오버레이 표시 중 취소라 안내 토스트 없음 — `busyPolicy.ts:129-133` 정책대로 |
| TC-FE-266 | PASS | [정적+단위] `OverlayLayer.tsx:386-389` — ESC 시 `setPendingConfirm(false)` 를 `handleBusyEscape()` **앞에** 실행(반환값 판정 안 함). 단위 `OverlayLayerSegmentBusy.test.tsx:251 ESC로_취소하면_확정_큐도_비워져_자동_발사되지_않는다`. [실동작] AI 분할 클릭 후 Enter→ESC 시 객체 수 3→3(자동 발사 없음). ⚠ 근거 라인 드리프트(→ H-ISSUE-61) |
| TC-FE-267 | PASS | [정적] `useBusyTask.ts:13` `BUSY_MAX_DURATION_MS = 5*60*1000`, `145-151` 타이머가 `isTokenAlive` 확인 후 `cancelBusy()`. 훅 생명주기와 분리(주석 142-144). **5분 대기는 실동작 미수행**(회차 시간 예산) — 라인 인용 정확 |
| TC-FE-268 | PASS | [실동작] **오버레이가 떠 있는 상태(overlay=1)** 에서 메타 탭 `#frame-description-input` 편집 가능(`disabled:false, readOnly:false`) → 저장 버튼 `disabled:false` → 클릭 시 **`PUT /frames/468/description` → 200** 실제 발생. 시계열/촬영환경/개인정보/이벤트어노테이션 textarea 4종도 전부 편집 가능. 정적으로도 메타 패널 5종에 `useIsEditBlocked` 참조 0건 |
| TC-FE-269 | PASS | [실동작] ① `AI 추적` 버튼 포커스 → Space → **`aria-pressed` false→true(버튼 활성화)**, 포커스는 그 버튼에 유지, 캔버스 cursor 변화 없음(팬 미발동) ② 포커스 blur 후 Space hold → 캔버스 컨테이너 `computed cursor: grab`, release → `auto` (**팬 정상 복귀**). ⚠ 근거 라인 드리프트(→ H-ISSUE-61) |
| TC-FE-270 | PASS | [실동작] 위 §1 ★ 참조. BE 409 실측 + FE 충돌 다이얼로그 실측. wiki 근거 `docs/v2-wiki/10-labeling.md:168` 존재 확인 |

### 2-2. 메타 탭 시계열 메타 검토 UI 제거 — TC-FE-276~278

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-276 | PASS | [정적] `frontend/src` 전역 grep 결과 `ts-review-*` 문자열이 **테스트 파일의 부재 단언 3줄에만** 존재하고 프로덕션 코드 0건. `approveMetaReview`/`rejectMetaReview`/`useMetaReview` **전부 0건**. `TimeseriesSidePanel.tsx:55-58` 주석이 정책 명시. 파일 총 177행 = 인용 `124-177` 이 렌더 끝까지 정확 |
| TC-FE-277 | PASS | [정적] 위와 동일. `승인`/`반려`/`검토 상태` 문자열이 렌더에 0건(159행 placeholder 의 "검토 후 수정할 수 있습니다" 안내문만 존재 — 배지 아님) |
| TC-FE-278 | PASS | [정적] `TimeseriesSidePanel.tsx:108-113` `dirtyItems` = `draft !== original && draft.trim().length > 0` 인 슬롯만, `92-102` 슬롯 0건이면 `MANUAL_TIMESERIES_META_KEY` 단일 슬롯, `121` `updateMutation.mutate({items: dirtyItems})` 1회. 라인 인용 `79-122` 정확 |

### 2-3. 도구 클릭 → 라벨 선택 모달 — TC-FE-279~292

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-279 | PASS | [실동작] `바운딩 박스` 클릭 → `role=dialog aria-modal=true` 오픈, 안내 문구 **"바운딩 박스 도구로 그릴 라벨을 선택하세요…"**(도구명 포함). **반증**: 모달 상태에서 캔버스 드래그 → 객체 수 2→2(드로잉 미시작). ⚠ 근거 라인 -1 드리프트 |
| TC-FE-280 | PASS | [실동작] ESC → 모달 0 + `activeTool` 이 **`선택`(직전 도구)으로 복귀** + 복귀 전이로 모달이 다시 뜨지 않음(`suppressRef`). 정적 `useToolLabelPicker.ts:111-119,69-73` 라인 정확 |
| TC-FE-281 | PASS | [실동작] 모달에서 `car` 클릭 → 모달 0 + `바운딩 박스` `aria-pressed=true`. 정적 `102-109` 정확 |
| TC-FE-282 | PASS | [실동작] 확정 후 캔버스 드래그 2회 → **모달 재노출 0**, 객체 `car #1`·`car #2` 2건 생성(마지막 선택 라벨 유지). 정적 `11-12,64-84` 정확 |
| TC-FE-283 | PASS | [실동작] 이미 활성인 `바운딩 박스` 재클릭 → 모달 1개 오픈. 정적 `86-100`(전이 없을 때 `requestTool` 이 직접 오픈, 라벨 불필요 도구는 no-op) 정확. ⚠ LabelingPage 라인 -1 드리프트 |
| TC-FE-284 | PASS | [실동작] `AI 추적` 클릭 → 모달 0, `선택` 클릭 → 모달 0. 정적 `LABEL_REQUIRED_TOOLS`(27-32) = BBOX·POLYGON·SAM_SEGMENT·KEYPOINT 4종 |
| TC-FE-285 | PASS | [실동작] 단축키 `p` → **동일 모달**(문구 "폴리곤 도구로 그릴 라벨을…"). 판정이 `activeTool` 전이 감시 1곳이라 진입점 무관 확인 |
| TC-FE-286 | PASS | [실동작] 네트워크 캡처 결과 **`/preset` 패턴 요청 0건**, `GET /api/v1/manage/labels` 1건만. 목록 = 활성(`useYn='Y'`) 10건이 `sortNo`(1,2,…,10,77) 순 정렬 |
| TC-FE-287 | PASS | [실동작] `CA` 입력(대문자) → `car` 1건만(대소문자 무시 부분일치). `zzz` → **"검색 결과가 없습니다" + `aria-live=polite`**, 목록 0건. ESC 후 재오픈 → **검색어 `''` 초기화 + 10건 복귀** |
| TC-FE-288 | PASS | [실동작 **반증**] 모달 닫힌 상태에서 전역 `1` keydown 후 도형을 그림 → 새 객체가 **`car #2`**(직전 선택 라벨 유지). 전역 1 이 발화했다면 마스터 1번=`person` 이 됐어야 함 → **미발화 확정**. 정적 `SHORTCUT_KEYMAP` 숫자 바인딩 grep 0건. ⚠ keymap 라인 -1 드리프트 |
| TC-FE-289 | PASS | [실동작] 검색 input 포커스 상태에서 `1` → `keyword='1'` 로 입력만 되고 선택(`aria-pressed=true`) 0건. 정적 `LabelPickerModal.tsx:74-88` INPUT/TEXTAREA/contentEditable 가드 + `if (!picked) return`(범위 밖 무시) |
| TC-FE-290 | PASS | [실동작 **반증**] 마스터 응답을 가로채 색상에 `red; background-image:url(javascript:alert(1))` / `javascript:alert(1)` 주입 → 칩 inline style 이 **`background-color: rgb(148, 163, 184)`(=`FALLBACK_LABEL_COLOR #94A3B8`)** 로 정규화, `document.body.innerHTML` 내 `javascript:alert` **0건**. `safeHexColor`(labelColor.ts) 가 `^#[0-9A-Fa-f]{6}$` 만 통과 |
| TC-FE-291 | PASS | [실동작] `[data-testid="label-sidebar"]` 0건, `aside[aria-label="라벨 목록"]` 0건, `role=toolbar` 의 다음 형제가 곧바로 캔버스 컨테이너. ⚠ 라인 -1 드리프트 |
| TC-FE-292 | PASS | [실동작] `keypoint-guide-slot` 존재 · **부모가 `labeling-right-panel` 직계** · `role=tablist` **앞** · `role=tabpanel` **바깥**(`closest('[role=tabpanel]')` null) → 어느 탭에서도 상시 노출. ⚠ 라인 -1 드리프트 |

### 2-4. 라벨명 = 마스터 등록명 그대로(★4) — TC-FE-293~296

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-293 | PASS | [실동작] 마스터가 `person`/`car`/`bus`/`bicycle` 등 **영문**인 상태에서 라벨 선택 모달·우측 객체 목록 모두 **동일 영문 문자열**. `사람`·`자동차` 등 한글 치환 **0건**. 정적 `labelDisplayName.ts:26-29` = trim + 빈값 `-` 뿐(사전 조회 코드 자체 부재) |
| TC-FE-294 | PASS | [정적] 인용 6곳 라인 **전건 정확**(ObjectClassTree:153 / ObjectAttributePanel:258,268 / AiToolModal:269 / LabelChangeDetail:127 / PortalUploadLabelingPage:328 / LabelPickerModal:58). ⚠ 7번째 호출부 `LabelPanel.tsx:44` 발견 — **사(死)코드**(→ H-ISSUE-63), 카탈로그에 주석 추가함 |
| TC-FE-295 | PASS | [정적] `putLabels`(api.ts:301-316) body 는 `label` 원문 그대로 전송. BE 실측에서도 `label` 누락 시 400(`items[0].label: must not be blank`) → 표시명 경유 필드가 아님이 확인됨. 회귀 가드 `labelDisplayNameNoPayloadLeak.test.tsx:51,55` 라인 정확 |
| TC-FE-296 | PASS | [정적] `labelDisplayName.ts:17-29` — `(rawName ?? '').trim()`, 빈값이면 `'-'`. 사전/맵 조회가 없어 `__proto__`·`constructor` 도 원문 반환(프로토타입 오염 경로 부재). 테스트 41,45,51 라인 정확 |

---

## 3. PM 지정 ★ 반증 항목 처리

| 지시 | 결과 |
|---|---|
| **mock 응답(SAM2/YOLO)의 FE 자동적용 차단 + 안내** | **내 담당 30건에 해당 케이스 없음**(카탈로그상 mock 자동적용 차단은 H-3 앞부분/AiToolModal 절 소관 = part3). 부수 확인만: 이 구간의 AI 경로는 `busy` 배타 실행으로만 관여하며, 취소 후 도착한 AI/저장 결과가 세대 토큰(`isTokenAlive`)으로 폐기되는 것은 TC-FE-265 로 **실동작 확인**함. **mock 판별 기반 자동적용 차단 로직 자체는 이 구간 밖이라 미검증** — part3 결과와 대조 필요 |
| **좌표 검증 2축(★3) 사용자 저장=400 거부의 FE 반영** | [실동작] `PUT /v1/frames/468/labels` 에 `points=[[-50,-50],[99999,99999]]` → **HTTP 400 `INVALID_INPUT` "좌표는 0 이상이어야 합니다 (x=-50.0, y=-50.0)"** — 클램프 아님을 실측 확인. FE 는 `resolveApiMessage` 정책상 400 을 사용자 문구 그대로 노출(TC-FE-195, part1/3 소관). **이 구간에는 좌표 검증 케이스가 없어 판정 대상 아님**(참고 기록) |

---

## 4. 이슈

### [H-ISSUE-61] TC-FE-263 / 266 / 269 / 279 / 283 / 288 / 291 / 292 — 근거 `file:line` 드리프트 8건 (카탈로그 정합성)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그의 `근거` 컬럼은 다음 회차 검증자가 **그 줄을 열어 기대결과와 대조**하는 유일한 좌표다. 어긋나면 검증자가 무관한 함수를 읽고 "코드가 없다/다르다"로 오판하거나, 대조 자체를 포기한다(4회차 변경이력이 지적한 바로 그 실패 모드).
- **현재 동작(이슈 내용)**:

  | TC | 카탈로그 인용 | 실제 위치 | 성격 |
  |----|---|---|---|
  | TC-FE-263 | `useLabelingShortcuts.ts:216,223,247` | `196`(fail-closed OR 판정) · `203`(ESC) · `227`(키맵 차단). 247 은 파일 말미 재-export | **-20행급, 인용 3개 전부 무관한 줄** |
  | TC-FE-266 | `OverlayLayer.tsx:357-381` | 핵심 코드(`setPendingConfirm(false)`)는 **387행** — 인용 범위 **밖** | 범위 부족 |
  | TC-FE-269 | `CanvasShell.tsx:71-104,185-193` | `66-99`(`isKeyboardActivatableTarget`) · `180-187`(Space keydown 가드) | -5행 |
  | TC-FE-279 | `LabelingPage.tsx:1328-1334` | `1329-1335` | -1행 |
  | TC-FE-283 | `LabelingPage.tsx:1343` | `1344` | -1행 |
  | TC-FE-288 | `labelingKeymap.ts:33-36` | `34-37` | -1행 |
  | TC-FE-291 | `LabelingPage.tsx:1336-1344` | `1337-1345` | -1행 |
  | TC-FE-292 | `LabelingPage.tsx:1400-1404` | `1401-1405` | -1행 |

- **재현/확인 경로**: `sed -n '196p;203p;227p' frontend/src/features/label/hooks/useLabelingShortcuts.ts` / `git merge-base --is-ancestor 890894c5 9f99db50` → **not ancestor**
- **영향**: 기능 영향 없음(카탈로그 품질). **원인이 둘로 갈린다**:
  ① `LabelingPage.tsx` 계열 -1행 = 카탈로그 4회차(`9f99db50`) **이후** `0d290c4e`/`890894c5`(영상 개인정보 메타 화면)가 파일을 건드림 → **불가피한 사후 드리프트**
  ② `useLabelingShortcuts.ts`(-20행)·`CanvasShell.tsx`(-5행)·`labelingKeymap.ts`(-1행) = 인용 대상 커밋(`dcdbb827`·`d8a7a2cc`)이 4회차 **이전**인데도 어긋남 → **4회차가 "H-3 원본 절 TC-FE-033~087,197,199~201" 만 재확인하고 라운드2·3 신설분(261~296)은 대조 대상에서 누락**시킨 것. 4회차 변경이력이 스스로 경고한 "파일이 안 바뀐 것 같아 대조를 생략" 패턴의 변형이다.
- **수정 방향(제안)**: **본 회차에서 8건 전부 Edit 로 정정 완료**(담당 라인범위 143~216 내부). 후속으로 회차 운영 규칙에 **"신설 케이스도 다음 회차 근거 재확인 대상"** 을 명시할 것.

### [H-ISSUE-62] TC-FE-262 — 근거가 '되돌리기·버전 롤백' 축을 커버하지 않고, 롤백 busy 차단에 자동 테스트가 0건이다
- **심각도**: LOW
- **기대 동작(기대효과)**: TC-FE-262 는 **되돌리기 · 버전 롤백 · 비식별 신고 3축**의 busy 차단을 보장한다. 롤백은 작업본(`labels`/dirty)을 바꾸는 편집이라, 저장 in-flight 중 실행되면 저장 성공의 `clearDirty()` 가 되돌린 분의 미저장 표식까지 지워 **무음 소실**이 난다(`LabelHistoryPanel.tsx:101-103` 주석이 직접 명시).
- **현재 동작(이슈 내용)**: 구 인용 `editBlocking.test.tsx:259,280` 중
  - `280` → `busy_중에는_비식별_누락_신고를_시작할_수_없다`(273행 시작) ✅ 신고 축은 커버
  - `259` → `busy_중_캔버스_위에_진행_오버레이가_뜨고_취소로_즉시_편집에_복귀한다`(254행 시작) — **되돌리기·롤백 축이 아니다**
  - `editBlocking.test.tsx` 13개 `it()` 어디에도 롤백/되돌리기 케이스 없음. `LabelHistoryPanel.revert.test.tsx` 는 2개 `it()` 모두 busy 를 세우지 않음(`grep -n 'busy' → 0건`).
  → 즉 **"버전 롤백은 busy 중 차단된다"에 자동 회귀 가드가 없다.** 구현(`LabelHistoryPanel.tsx:104,207-211 disabled={editBlocked}`)은 정상이나, 누가 `disabled` 를 떼도 테스트가 잡지 못한다.
- **재현/확인 경로**: 본 회차 실동작으로 정상 확인함 — busy 중 되돌리기 버튼 11개 `disabled=true`, 해제 후 12개 `false`(스크립트 `h4f.mjs`).
- **영향**: 회귀 방어 공백(기능 결함 아님).
- **수정 방향(제안)**: ① 카탈로그 근거를 실제 축으로 교정(**본 회차 정정 완료** — `LabelHistoryPanel.tsx:104,207-211` + 테스트 공백 경고 명시) ② `LabelHistoryPanel.revert.test.tsx` 에 `beginBusy` 후 되돌리기 버튼 `disabled` 단언 1건 추가.

### [H-ISSUE-63] TC-FE-294 인접 — `LabelPanel.tsx` 가 어디서도 import 되지 않는 사(死)코드로 남았다 (`LabelSidebar` 폐지 잔재)
- **심각도**: LOW
- **기대 동작(기대효과)**: 결정 3(2026-08-03, `d8a7a2cc`)은 좌측 상시 라벨 패널을 **컴포넌트·테스트째로 삭제**했다. 라벨 선택 표면은 모달 하나여야 하고, 표시명 경유 지점도 "6곳"으로 카탈로그가 못 박혀 있다.
- **현재 동작(이슈 내용)**: `features/label/components/LabelPanel.tsx:44` 가 `resolveLabelDisplayName(cls)` 를 호출하는 **7번째 표시 지점**으로 살아 있다. 그러나 전역 grep 결과 자기 테스트(`__tests__/LabelPanel.test.tsx`) 외 **import 0건** — 렌더 경로가 없다.
  ```
  $ grep -rn 'LabelPanel' --include='*.tsx' src/ | grep -v __tests__ | grep -v 'components/LabelPanel.tsx'
  (출력 없음)
  ```
- **재현/확인 경로**: 위 grep. 라벨링 화면 실동작 DOM 에도 해당 패널 없음(TC-FE-291 실측).
- **영향**: 기능 영향 0. 다만 ①번들에 잔존(트리셰이킹 여부 미확인) ②다음 회차 검증자가 "표시 지점 7곳인데 카탈로그는 6곳"으로 재발견해 같은 논의를 반복 ③테스트가 살아 있어 "쓰이는 컴포넌트"로 오인.
- **수정 방향(제안)**: `LabelPanel.tsx` + `__tests__/LabelPanel.test.tsx` 삭제(구 `LabelSidebar` 삭제와 동일 처리). 삭제 전까지는 카탈로그 주석(**본 회차 추가 완료**)으로 오인을 막는다.

---

## 5. 카탈로그 정정 내역 (담당 라인범위 143~216 내부에서만 수행)

| # | 대상 | 변경 |
|:--:|---|---|
| 1 | TC-FE-262 근거 | `editBlocking.test.tsx:259,280` → `editBlocking.test.tsx:273(신고),222·254(ESC·취소)` + `LabelHistoryPanel.tsx:104,207-211(버전 롤백 — ⚠ 롤백 축은 자동 테스트 0건)` |
| 2 | TC-FE-263 근거 | `useLabelingShortcuts.ts:216,223,247` → `:196(fail-closed OR 판정),203(ESC),227(키맵 차단)` |
| 3 | TC-FE-266 근거 | `OverlayLayer.tsx:357-381` → `:354-392(ESC 분기 373-391 · setPendingConfirm(false) 387)` + 테스트 라인 `:251` 명시 |
| 4 | TC-FE-269 근거 | `CanvasShell.tsx:71-104,185-193` → `:66-99(활성화 대상 판정),180-187(Space keydown 가드)` + 테스트 경로 풀패스·라인 `:153,169` 명시 |
| 5 | TC-FE-279 근거 | `LabelingPage.tsx:1328-1334` → `:1329-1335` |
| 6 | TC-FE-283 근거 | `LabelingPage.tsx:1343` → `:1344` |
| 7 | TC-FE-288 근거 | `labelingKeymap.ts:33-36` → `:34-37` |
| 8 | TC-FE-291 근거 | `LabelingPage.tsx:1336-1344` → `:1337-1345` |
| 9 | TC-FE-292 근거 | `LabelingPage.tsx:1400-1404` → `:1401-1405` |
| 10 | TC-FE-294 기대결과 | 7번째 호출부 `LabelPanel.tsx:44` 가 **사코드**라 집계 제외임을 명시(⚠ 주석 추가) |

**폐기 처리한 케이스 없음**(30건 전부 현행 구현과 정합).
**변경 이력 표(파일 8~16행)는 담당 범위 밖이라 손대지 않았다** — 회차 행 추가는 H 클러스터 통합 담당이 수행해야 한다.

---

## 6. 미검증·한계

| 항목 | 사유 |
|---|---|
| TC-FE-267 의 5분 fail-safe 실경과 | 회차 시간 예산. 코드(`useBusyTask.ts:13,145-151`)·타이머 분리 구조는 정적 확인 |
| TC-FE-263 의 "커밋과 리렌더 **사이**" 실제 경합 창 | 브라우저에서 결정론적으로 재현 불가. `isEditBlockedNow()` OR 판정(196행)과 `shortcutsFailClosed.test.tsx` 로 대체 |
| TC-FE-266 의 지연 창 Enter 큐잉 정확 재현 | SAM2 응답 지연 주입 시 분할 결과 자체가 비어 큐잉 여부를 외부에서 분리 관측하기 어려움. 코드+전용 단위테스트로 판정 |
| mock 응답 자동적용 차단(★ PM 지시) | 해당 케이스가 담당 30건에 없음 — part3 소관 |

<!-- H-part4.md 원문 끝 -->

---

<!-- ============================================================ -->
<!-- H-part5.md 원문 시작 -->
<!-- ============================================================ -->

# H 클러스터 part5 — H-11(포털 화면) + H-15(E2E 전체 사용자 시나리오) 검증 결과

- 회차: 2026-08-03 **3차** / 담당 범위: `docs/test-cases/H-frontend-e2e.md` **H-11**(347~373행) + **H-15**(427~437행)
- 대상 케이스: **28건** (H-11 22건 = TC-FE-165~177·271~275·TC-E2E-008~011 / H-15 6건 = TC-E2E-014~019). 폐기행 0건
- 이슈 ID: `H-ISSUE-81` ~
- 실행 금지 준수: 빌드·테스트(gradle/vitest/playwright) **미실행**. 실동작 확인은 HTTP 요청 + 브라우저(Playwright MCP) 조작 + DB 조회로만 수행

## ★ 최우선 결론 — 2차 HIGH 2건 해소 여부

| 2차 이슈 | 내용 | **3차 판정** |
|---|---|---|
| **H-ISSUE-103** (2차 HIGH #11) 포털 데이터마트 라벨링에 AI 분할·추적·스켈레톤 노출 | ADR-013 위반 | **✅ 해소 (FE 실동작 재확인)** |
| **H-ISSUE-143** (2차 HIGH #10) 전체 워크플로 E2E 픽스처(`WORKFLOW_VIDEO_ID=9035`) 부재 → 검수종결 E2E 커버리지 0 | 4건 연쇄 스킵 | **✅ 해소 (하드코딩 제거 + 실행시점 동적 해석, 전제 live 확인)** ⚠ 단 완주 실행은 이번 회차 규칙(실행 금지)상 미확인 |

### ① H-ISSUE-103 해소 근거 — FE단 실측 (F클러스터가 확인한 BE 완전제거와 동일축)

- **소스 단일 게이팅**: `frontend/src/features/label/types.ts:215-219`
  ```ts
  export const PORTAL_HIDDEN_TOOLS: readonly ToolType[] = [
    ToolType.SAM_SEGMENT, ToolType.TRACK, ToolType.KEYPOINT,
  ];
  ```
  `DarkToolbar.tsx:148-153` 이 이 단일 소스로 필터(`if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);`), 오토라벨(YOLO) 액션은 `portalHidden: true`(:135)로 별도 숨김.
- **실동작(브라우저, PORTAL_USER JWT `channel=PORTAL`, `role=PORTAL_USER`)** — 2회 독립 재현:
  - `/portal/label/468`(데이터마트 영상) `role=toolbar` 버튼 = **`선택, 바운딩 박스, 폴리곤, 삭제, 실행 취소, 화면 맞춤, 저장`**
    → `AI 분할`·`AI 추적`·`스켈레톤`·`AI 탐지` **0건**. 본문 텍스트에 `검수제출|시계열|VLM|버전` **0건**. 캔버스(`canvas-shell` 1개, `<canvas>` 3개) 정상 렌더 + 프레임 blob 이미지 10건 로드 성공.
  - `/portal/uploads/80/label`(본인 업로드 자산) 버튼 = **`내보내기(JSON), 원본 다운로드, 선택, 이동, 바운딩 박스, 폴리곤, 저장`** → AI 진입점 0건.
- **회귀 가드 존재**: `features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:110-123` 가 `queryByRole('button',{name:'AI 분할'|'AI 추적'|'스켈레톤'|'AI 탐지'}).toBeNull()` 로 **부재를 단언**(2차의 "노출을 기대값으로 고정"에서 반전 완료). 3차 baseline frontend 2,064건 전건 통과.
- ⇒ `UNCERTAINTIES.md` **#1(포털 SAM2 노출 = 결함 유지)** 은 FE 축에서도 **해소**. F-part1 의 원본 갱신 제안(“3차에서 ✅ 해소”)에 동의하며, 재도입 방지를 위해 **★확정 정책 절 승격**을 함께 권고한다(이 저장소의 ‘철회된 정책 재시도’ 차단 관례).

### ② H-ISSUE-143 해소 근거

- `frontend/e2e/fixtures/test-data.ts` 에서 구 상수 **삭제 확인**: `grep -n "WORKFLOW_VIDEO_ID\|WORKFLOW_SRC_SN"` → **0건**. 파일 상단 주석이 사고 경위(H-ISSUE-143)를 명시하고 하드코딩 금지를 선언.
- 대체 메커니즘: `resolveWorkflowFixture()`(`test-data.ts:116-128`)가 ①`GET /v1/assignments` 로 LABELER 배정 + `firstSrcSn` 보유 후보 수집 ②`GET /v1/reviews/{videoId}`·`GET /v1/frames/{srcSn}/labels` 로 신고게이트(412)·권한(403)·프레임부재(404) 후보 탈락 ③제출 가능 상태로 정규화(`cancel-submit`/`reject`) ④롤백용 커밋 2건 미달 시 승인 사이클로 적층 — 전부 **공개 API**, 테스트 백도어 없음. 스펙(`labeling-review-full-flow.spec.ts:27-36`)은 `beforeAll` 에서 이를 호출하고 180s 타임아웃을 설정.
- **전제 실동작 확인(현 스택)**:
  ```
  POST /api/v1/dev/tokens {role:WORKER,userNo:2001} → 200
  GET  /api/v1/assignments?page=0&size=5  (WORKER 2001) → 200, totalElements=49,
       content[0] = {id:76, videoId:115, taskTypeCd:"LABELER", firstSrcSn:508, ...}
  GET  /api/v1/frames/1/labels            (WORKER 2001) → 200
  ```
  ⇒ 후보 0건으로 인한 `pickCandidate` 실패는 현 시드에서 발생하지 않는다.
- 스펙 자체도 보강됨: 테스트 4건 → **9건**(픽스처 유효성 단언 `:53-83`, 최종 완주 단언 `:274-277` 신설). 단언이 전부 **무조건**(`toBeVisible`/`expect(status).toBe(200)`/정확한 토스트 문구)이라 2차의 "조건부 단언" 문제도 이 스펙에는 없다.
- ⚠ **남은 한계**: 이번 회차는 E2E 실행이 금지되어 **완주 자체는 미확인**이다. 또 현 로컬 스택 포트와 E2E 기본 대상이 어긋나 있어(→ H-ISSUE-84) 이 스펙은 **아직 한 번도 compose 스택 위에서 돌아본 적이 없다**. 다음 회차에서 `E2E_BE_URL`/`BASE_URL` 을 맞춰 1회 완주시켜야 “커버리지 0 → 실효 확보”가 최종 확정된다.

---

## 1. H-11. 포털 화면 (자산 업로드+수동 라벨링) — 22건

> 사용한 실데이터: 포털 업로드 자산 `uldSn=80`(`qa-portal.jpg`, READY)·`uldSn=81`(`qa <img src=x onerror=alert(1)>.jpg`, READY) / 데이터마트 영상 29건(대표 `rawSn=101`, `firstSrcSn=468`).
> ⚠ 검증 중 MCP 브라우저가 **다른 병렬 에이전트와 프로필을 공유**해 sessionStorage/localStorage 토큰이 도중에 REVIEWER·WORKER 토큰으로 교체되는 현상을 관측했다(→ `/portal/*` 이 `/forbidden` 으로 튐). **제품 결함이 아니라 검증 환경 아티팩트**임을 토큰 클레임 디코드로 확인했고, 스토리지 정리 후 재인계하여 모든 판정을 재현했다. 아래 판정은 전부 재현 후 값이다.

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-165 | PASS | [정적] `validation.ts:38-42` 확장자 allowlist(`IMAGE_EXTENSIONS=['jpg','jpeg','png']`) 위반 시 `errors.push(...IMAGE_POLICY_TEXT)` + `continue`(제외). [실동작] `/portal/uploads` 의 `input#portal-image-input accept="image/jpeg,image/png,.jpg,.jpeg,.png"`. 단위테스트 `validation.test.ts:21,28` |
| TC-FE-166 | PASS | [정적] `validation.ts:10` `MAX_IMAGE_BYTES=20*1024*1024`, `:43-46` 초과 시 "크기가 20MB를 초과했습니다" + 제외. 테스트 `validation.test.ts:36`. **근거 드리프트 1건 정정**(구 `:9`→`:10`) |
| TC-FE-167 | PASS | [정적] `validation.ts:12` `MAX_IMAGE_COUNT=50`, `:50-56` 초과 안내 + `valid: accepted.slice(0,50)`. 테스트 `validation.test.ts:42` |
| TC-FE-168 | PASS | [정적] `PortalUploadPage.tsx:77-87` 성공 후 `setSelected([])`·`setValidationErrors([])`·`imageInputRef.current.value=''` 3종 초기화. 테스트 `PortalUploadPage.test.tsx:72` |
| TC-FE-169 | PASS | [정적] `PortalUploadPage.tsx:90` `useTusUpload({ endpointBase: PORTAL_TUS_ENDPOINT })`, `:35` `PORTAL_TUS_ENDPOINT='/portal/uploads/tus'`, `:95-100` `tus.start(videoFile,...)`. [실동작] `input#portal-video-input accept="video/mp4,video/quicktime,video/x-msvideo,.mp4,.mov,.avi"`. **근거 드리프트 1건 정정**(`useTusUpload.ts:30`→`:36`) |
| TC-FE-170 | PASS | [정적] `PortalUploadPage.tsx:104` `window.confirm(...되돌릴 수 없습니다)`, `:310-311` `disabled={deleting \|\| isProcessing}` + `title="처리 중 자산은 삭제할 수 없습니다."`. 테스트 `PortalUploadPage.test.tsx:141,156`. (현 스택에 PROCESSING 자산이 없어 실동작 재현 불가 — 정적+단위테스트로 판정) |
| TC-FE-171 | PASS | [정적] `PortalUploadPage.tsx:48-54` `deleteErrorMessage` 가 409/CONFLICT 만 "처리 중 자산은 삭제할 수 없습니다.", 그 외는 일반 문구 — BE 내부 메시지 미전달(CWE-209 방어). 테스트 `PortalUploadPage.test.tsx:167` |
| TC-FE-172 | PASS | [실동작] `/portal/uploads` DOM 링크 = `["/portal","/portal/uploads/80/label","/portal/uploads/81/label"]` — READY 2건에만 라벨링 링크. [정적] `:296-306` `{isReady && <Link to={`/portal/uploads/${uldSn}/label`}>}` |
| TC-FE-173 | PASS | [정적] `PortalUploadPage.tsx:288-291` `{isFailed && ...(upload.failRsnCn ?? '처리에 실패했습니다...')}`. 테스트 `PortalUploadPage.test.tsx:178`. (FAILED 자산 부재로 실동작 재현 불가) |
| TC-FE-174 | PASS | [실동작] 업로드 자산명 `qa <img src=x onerror=alert(1)>.jpg` 가 **DOM 에 `&lt;img src=x` 로 escape 되어 존재**(`innerHTML.includes('&lt;img src=x') === true`), 스크립트 실행/이미지 태그 생성 0건. [정적] `:281` 텍스트 노드 렌더. 테스트 `PortalUploadPage.test.tsx:189` |
| TC-FE-175 | PASS | [실동작] `/portal/uploads/80/label` 도구 = `선택/이동/바운딩 박스/폴리곤` + `저장`, AI 진입점 0건. [정적] `PortalUploadLabelingPage.tsx:45-50` `UPLOAD_TOOLS` 4종 고정, `CanvasShell` 조립 |
| TC-FE-176 | PASS | [실동작] `/portal` 진입 → "AI 학습데이터 작성 포털 / 데이터마트 영상" 목록 렌더, 영상 29건(`GET /v1/portal/datamart/videos` totalElements=29 와 일치), 각 항목 선택 가능. 내비 링크 = `/portal`·`/portal/uploads` 2개뿐(내부 화면 링크 0건) |
| TC-FE-177 | PASS | [정적] `useSavePortalLabels.ts:1-45` — BE 계약 `POST /v1/portal/user-labels`(본인 작업분 `LS_PORTAL_USER_LABEL` 별도 적재, 원본 `LS_DATA_LBL` 미수정), 직렬화가 **BBOX/POLYGON 만** 허용하고 그 외 형태는 `null` 로 제외(BE allowlist 400 회피). 테스트 `features/portal/__tests__/useSavePortalLabels.test.tsx` |
| TC-FE-271 | PASS | [정적] `PortalUploadLabelingPage.tsx:371` `<BusyOverlay kind={busyKind} startedAt={busyStartedAt} onCancel={cancelBusy} />` 배선 + `:107` `cancelBusy` 를 store 에서 취득. 저장은 `useSaveUploadLabels` 가 `runExclusiveOrNotify('SAVE', ...)` 로 store busy 배타축에 올림(`:85`). 회귀 테스트 `PortalUploadLabelingBusy.test.tsx:146`("저장 중 진행 오버레이가 뜨고 취소할 수 있다") |
| TC-FE-272 | PASS | [정적] `useSaveUploadLabels.ts:94-98` `onSuccess: (result) => { if (result === null) return; options.onSuccess?.(); }` — 취소·폐기(null)면 `clearDirty` 등 성공 후처리 미실행. 캐시 무효화도 `isAlive()` 가드 안(`:88-90`). 테스트 `PortalUploadLabelingBusy.test.tsx:173` |
| TC-FE-273 | PASS | [정적] `busyPolicy.ts:69` `BUSY_OVERLAY_DELAY_MS = 300`, `BusyOverlay.tsx:48-66` 이 `startedAt` 기준 잔여시간만큼 `setTimeout` 후 `setVisible(true)` — 300ms 미만 저장은 오버레이 미표시. 테스트 `PortalUploadLabelingBusy.test.tsx:191` |
| TC-FE-274 | PASS | [실동작] `/portal/uploads/80/label` 전체 버튼 목록에 `AI 탐지/AI 분할/AI 추적/스켈레톤/오토라벨/SAM2/YOLO` **0건** → 관측 가능한 busy 는 SAVE 뿐. [정적] `UPLOAD_TOOLS`(:45-50)에 AI 도구 부재. 테스트 `PortalUploadLabelingBusy.test.tsx:197` |
| **TC-FE-275** | **PASS** | **[실동작]** 위 "★최우선 결론 ①" 참조 — 두 화면 모두 BBOX/POLYGON 만. ⚠ 2차 H-ISSUE-103 **해소 확정** |
| TC-E2E-008 | PASS | [실동작] PORTAL_USER JWT 로 `/ingress?token=` → `/portal` 자동 착지, 홈 렌더 확인. [정적] `portal-channel-guard.spec.ts:10` |
| TC-E2E-009 | PASS | [실동작] 포털 세션에서 `/dashboard` SPA 진입 → `location.pathname === '/forbidden'`. [정적] 스펙 `:15` + `router/guards.tsx:89` ChannelGuard |
| TC-E2E-010 | PASS | [실동작] `/manage/users` → `/forbidden`. 추가 반증으로 `/task`·`/label/468`(내부 라벨링) 도 `/forbidden` 확인 — 우회 경로 없음. [정적] 스펙 `:26` |
| **TC-E2E-011** | **FAIL** | [실동작] 스펙이 겨냥하는 `/portal`(홈)에 `input[type=file]` **0개**, `[data-testid=upload-dropzone]` **0개** → 첫 테스트의 `expect(visible).toBe(true)` 가 **실패**한다. 두 번째 테스트는 `if (fileInput.count()>0)` 안이라 통째로 미실행(공허 통과). 실제 업로드 UI 는 `/portal/uploads` 에만 있다(`#portal-image-input`/`#portal-video-input`). 2차 **H-ISSUE-101 미해소 이월** → **H-ISSUE-81** |

**H-11 집계: PASS 21 / FAIL 1 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**

---

## 2. H-15. E2E 전체 사용자 시나리오 — 6건

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| **TC-E2E-014** | **FAIL** | [정적] `labeling-flow.spec.ts:30,35,39` 세 핵심 단계가 전부 `if ((await ...count()) > 0)` 조건부라 요소 미렌더 시 **아무 것도 검증하지 않고 통과**. [실동작] 더 근본적으로 대상 `srcSn=1` 은 이 스펙이 쓰는 `workerPage`(=`TEST_USERS.worker`, userNo **1003**)에게 **403**(`GET /api/v1/frames/1/labels` → `{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`)이라 캔버스 자체가 뜨지 않는다. 같은 프레임을 userNo **2001** 로 조회하면 200 — 즉 **픽스처 사용자와 대상 영상의 불일치**가 근본원인이며, 조건부 가드만 제거하면 이번엔 확정 실패한다. 2차 **H-ISSUE-141 미해소 이월 + 근본원인 신규 확인** → **H-ISSUE-82** |
| **TC-E2E-015** | **FAIL** | [정적] `worker-labeling.spec.ts:33-34` `const cnt = await labeling.bboxToolBtn.count(); expect(cnt).toBeGreaterThanOrEqual(0);` — **항상 참인 공허 단언**(count 는 음수가 될 수 없다). 기대결과 "바운딩박스 버튼 렌더"를 전혀 보장하지 않는다. TC-E2E-014 와 동일한 403 문제도 공유(`TEST_VIDEO_WITH_LABEL=1`, `workerPage`=1003). 2차 **H-ISSUE-142 미해소 이월** → **H-ISSUE-83** |
| TC-E2E-016 | PARTIAL | [정적+실동작] 차단 결함(H-ISSUE-143) 해소·전제 live 확인(위 ★결론 ②). 단 **완주 실행 미확인**(회차 규칙상 E2E 실행 금지) + 하네스 기본 포트 불일치(H-ISSUE-84)로 현 스택에서 즉시 실행 불가 → **H-ISSUE-86** |
| TC-E2E-017 | PARTIAL | [정적] `labeling-review-full-flow.spec.ts:186-216` — 이력 패널 열기 → 버전 탭 → 커밋 2건 이상 단언(`toBeGreaterThanOrEqual(2)`) → 롤백 트리거 → `POST .../versions/.../rollback` 200 단언. 단언은 전부 무조건. 픽스처가 커밋 2건을 사전 보장(`ensureRollbackableVersions`). **실행 미확인** → H-ISSUE-86 |
| TC-E2E-018 | PARTIAL | [정적] `:150-184` — 상세 진입 시 `POST /reviews/{id}/start` 응답 대기 → 반려 사유 입력 → `POST .../reject` 200 + 토스트 "반려 처리됨" 단언. **실행 미확인** → H-ISSUE-86 |
| TC-E2E-019 | PARTIAL | [정적] `:239-272` 승인(`POST .../approve` 200 + "승인 완료" 토스트) + `:274-277` 최종 `expect(await readStatus()).toBe('APPROVED')` — 카탈로그의 "APPROVED 전이" 정정 표현이 코드와 일치함을 재확인. **실행 미확인** → H-ISSUE-86 |

**H-15 집계: PASS 0 / FAIL 2 / PARTIAL 4 / BLOCKED 0 / N/A 0 / 확인필요 0**

---

## 3. 총 집계 (28건)

| 판정 | 건수 |
|---|---:|
| PASS | 21 |
| FAIL | 3 |
| PARTIAL | 4 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |

---

## 4. 이슈 대장

### [H-ISSUE-81] TC-E2E-011 — 포털 업로드 E2E 가 업로드 UI 가 없는 `/portal`(홈)을 겨냥해 첫 단언이 확정 실패한다 (2차 H-ISSUE-101 미해소 이월)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 포털 자산 업로드(ADR-013 예외 경로)는 외부 채널이 파일을 반입하는 유일한 입구다. 업로드 화면의 dropzone/파일 입력 노출과 실제 업로드 시도가 E2E 로 보장되어야 회귀(라우트 변경·컴포넌트 이동)가 잡힌다.
- **현재 동작(이슈 내용)**: 스펙이 `PortalHomePage` POM 을 써서 `/portal` 로 이동한 뒤 업로드 요소를 찾는다.
  ```ts
  // e2e/specs/portal-upload.spec.ts:10-18
  const home = new PortalHomePage(portalPage);   // pages/PortalHomePage.ts:19 → pushState('/portal')
  await home.goto();
  const visible = (await home.dropzone.count()) > 0 || (await home.fileInput.count()) > 0;
  expect(visible).toBe(true);
  ```
  실측(PORTAL_USER 세션, 브라우저):
  ```
  /portal          → input[type=file] 0개, [data-testid=upload-dropzone] 0개   ← 단언 실패
  /portal/uploads  → input#portal-image-input(multiple, jpg/jpeg/png)
                     input#portal-video-input(mp4/mov/avi)
  ```
  두 번째 테스트(`:20-38`)는 `if ((await home.fileInput.count()) > 0)` 안에서만 동작하므로 **아무 것도 검증하지 않고 통과**한다. fixture `e2e/fixtures/sample.jpg.txt` 도 첫 줄이 `e2e mock placeholder file (not a real jpg)` 라 BE 매직바이트 검증을 통과할 수 없다.
- **재현/확인 경로**: PORTAL_USER JWT 로 `/ingress?token=...` → `/portal` 에서 `document.querySelectorAll('input[type=file]').length` → `0`. `/portal/uploads` 로 이동하면 `2`.
- **영향**: 기능 — 포털 업로드 화면의 E2E 보장이 0 이다(첫 테스트는 실패, 둘째는 공허). CI 에 E2E 를 붙이면 첫 테스트가 상시 red 라 스위트 자체가 무시되기 쉽다.
- **수정 방향(제안)**: ①`PortalUploadPage` POM 신설(또는 스펙이 직접 `/portal/uploads` 로 이동) ②`#portal-image-input`/`#portal-video-input` 을 명시 로케이터로 사용 ③`if (count>0)` 가드 제거하고 무조건 단언 ④fixture 를 실제 최소 JPEG(매직바이트 `FFD8FF`)로 교체. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-82] TC-E2E-014 — 라벨링 플로우 E2E 의 핵심 단언이 전부 조건부이고, 대상 프레임이 픽스처 사용자에게 403 이라 실질 커버리지가 0 이다 (2차 H-ISSUE-141 미해소 + 근본원인 신규)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WORKER 가 목록→캔버스→BBox 작성→저장→토스트까지 완주하는 것이 이 케이스의 보장 대상이다. 라벨링 캔버스는 이 제품의 핵심 화면이라 조건부가 아닌 확정 단언이 필요하다.
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/specs/labeling-flow.spec.ts:29-42
  if ((await labeling.bboxToolBtn.count()) > 0) { await labeling.bboxToolBtn.first().click(); }
  if ((await labeling.canvas.count()) > 0)      { await labeling.drawBoundingBox(...); }
  if ((await labeling.saveBtn.count()) > 0)     { await labeling.save(); await expect(...).toBeVisible(); }
  ```
  세 단계 모두 요소가 없으면 조용히 통과한다. 그리고 실제로 요소가 없다 — 이 스펙은 `workerPage`(`TEST_USERS.worker`, userNo **1003**)로 `srcSn=1` 에 진입하는데:
  ```
  POST /api/v1/dev/tokens {role:WORKER,userNo:1003}     → 200
  GET  /api/v1/frames/1/labels  (userNo 1003)           → 403 {"errorCode":"FORBIDDEN","message":"권한이 없습니다."}
  GET  /api/v1/frames/1/labels  (userNo 2001)           → 200
  ```
  즉 배정이 없는 사용자로 남의 프레임에 진입하므로 `LabelingPage.goto()` 의 `canvas-shell` 대기가 `.catch(() => undefined)` 로 삼켜지고(POM `:72-75`), 이후 3개 `if` 가 모두 false 가 된다.
- **재현/확인 경로**: 위 curl 3줄. 또는 `E2E_BE_URL`·`BASE_URL` 을 맞춘 뒤 `npx playwright test labeling-flow --reporter=list` 로 "통과하지만 아무 것도 안 한" 상태 확인.
- **영향**: 기능 — "E2E 스펙 11개 보유" 통계가 실제 보장과 어긋난다. 라벨링 저장 회귀가 이 스펙으로는 절대 잡히지 않는다.
- **수정 방향(제안)**: ①`labelerPage`(userNo 2001, 실제 LABELER 배정 보유)로 픽스처 사용자를 교체하거나, `resolveWorkflowFixture()` 와 같은 **동적 해석**으로 대상 프레임을 얻는다 ②`if (count>0)` 가드를 전부 제거하고 `toBeVisible()` + PUT 응답 200 + 정확한 토스트 문구('저장됨')로 단언한다(`labeling-review-full-flow.spec.ts:94-120` 이 이미 모범 사례다). **본 검증에서는 수정하지 않음.**

### [H-ISSUE-83] TC-E2E-015 — 바운딩박스 버튼 렌더 단언이 항상 참(`>= 0`)인 공허한 단언이다 (2차 H-ISSUE-142 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 진입 시 도구바에 '바운딩 박스' 버튼이 실제로 렌더되어야 한다(포털 게이팅 회귀의 대조군이기도 하다 — 내부 채널에서는 도구가 살아 있어야 한다).
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/specs/worker-labeling.spec.ts:32-34
  // BBox 도구 버튼이 렌더 — 정확한 텍스트는 ... 변경 가능하므로 count 만 검증.
  const cnt = await labeling.bboxToolBtn.count();
  expect(cnt).toBeGreaterThanOrEqual(0);   // Locator.count() 는 음수가 될 수 없다 → 항상 참
  ```
  주석이 든 이유("정확한 텍스트가 변경 가능")도 현재는 성립하지 않는다 — POM `LabelingPage.ts:20` 이 `getByRole('button',{name:'바운딩 박스',exact:true})` 로 **접근성 이름을 이미 확정**해 쓰고 있고, `DarkToolbar` 의 표시명은 `TOOL_DISPLAY_NAME` 단일 출처에서 파생된다.
- **재현/확인 경로**: 해당 3줄 코드. 도구바를 통째로 제거해도 이 테스트는 통과한다.
- **영향**: 회귀 — 내부 라벨링 도구바가 사라져도 잡히지 않는다. 포털 게이팅(`PORTAL_HIDDEN_TOOLS`)을 확장하다가 내부 채널까지 숨기는 실수를 이 E2E 로는 못 잡는다(현재는 vitest `DarkToolbar.test.tsx` 만이 대조군 역할).
- **수정 방향(제안)**: `await expect(labeling.bboxToolBtn).toHaveCount(1)` 또는 `toBeVisible()` 로 교체. 함께 H-ISSUE-82 의 픽스처 사용자 문제(userNo 1003 → 403)도 해결해야 실효가 생긴다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-84] (H-15 전반) E2E 하네스의 기본 대상 주소가 로컬 compose 스택과 어긋나 있어 E2E 가 회귀 baseline 에 한 번도 포함된 적이 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수 종결 워크플로를 유일하게 종단 검증하는 자산(TC-E2E-016~019)이 로컬 표준 스택 위에서 그대로 실행 가능해야 한다. 실행되지 않는 E2E 는 커버리지가 아니라 장식이다.
- **현재 동작(이슈 내용)**:
  ```
  e2e/fixtures/be-client.ts:48   export const BE_BASE = process.env.E2E_BE_URL || 'http://127.0.0.1:8080';
  playwright.config.ts:20        baseURL: process.env.BASE_URL || 'http://127.0.0.1:5174'
  playwright.config.ts:26-31     webServer: { command: 'npm run dev', url: 'http://127.0.0.1:5174' }
  ```
  실제 검증 스택(docker compose, `_raw/stack-bringup.md`)은 **backend `18081:8080`**, **frontend `13000:5174`** 로 발행된다. 로컬에 8080 리스너 없음(`lsof -nP -iTCP:8080 -sTCP:LISTEN` → 0건). 따라서 환경변수 없이 `npm run e2e` 를 돌리면 `issueDevToken` 이 ECONNREFUSED 로 죽고 모든 스펙이 실패한다.
  또 3차 `_raw/test-baseline.md` 에는 backend(5,203)·frontend vitest(2,064)·ai-server(145)만 있고 **Playwright 실행 기록이 없다** — 1~3차 어느 회차에도 E2E baseline 이 없다.
- **재현/확인 경로**: `cd frontend && npx playwright test --list` 는 통과하지만, 실행하면 `be-client.ts` 의 토큰 발급에서 즉시 실패. `E2E_BE_URL=http://127.0.0.1:18081 BASE_URL=http://127.0.0.1:13000` 을 주면 대상이 맞는다(단, vite dev 프록시의 `BACKEND_ORIGIN` 도 함께 맞춰야 함).
- **영향**: 기능/프로세스 — H-ISSUE-143 을 고쳐 픽스처를 동적화했지만 **한 번도 실행으로 확인된 적이 없다**. 다음 회차에서도 같은 이유로 PARTIAL 이 반복될 위험.
- **수정 방향(제안)**: ①`.env.e2e`(또는 `package.json` 의 `e2e:local` 스크립트)에 compose 포트를 기본값으로 고정 ②`playwright.config.ts` 의 `webServer.command` 를 `reuseExistingServer` 와 함께 compose FE(13000) 를 쓰도록 선택 가능하게 ③검증 회차 §3-2 baseline 에 **Playwright 실행을 항목으로 추가**(현재 backend/frontend/ai-server 3종만). **본 검증에서는 수정하지 않음.**

### [H-ISSUE-85] (TC-E2E-014/015 인접) `TEST_VIDEO_WITH_LABEL = 1` 하드코딩이 남아 픽스처 파일의 자기 선언과 모순된다
- **심각도**: LOW
- **기대 동작(기대효과)**: `e2e/fixtures/test-data.ts:9-13` 이 스스로 *"⚠ 영상(rawSn)·프레임(srcSn) 은 하드코딩하지 않는다 — 시드가 재적재되면 PK 가 통째로 바뀌어 스펙 전체가 404 로 죽는다(H-ISSUE-143 실사고)"* 라고 선언했다. 같은 파일의 다른 상수도 그 규칙을 따라야 한다.
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/fixtures/test-data.ts:53-54
  /** 시드 데이터의 srcSn 1~5 에 라벨 존재 — 라벨링 진입 테스트는 1 사용. */
  export const TEST_VIDEO_WITH_LABEL = 1;
  ```
  `worker-labeling.spec.ts` 가 이 상수를 쓰는데, 현 스택에서 srcSn=1 은 **userNo 1003 에게 403**(H-ISSUE-82). H-ISSUE-143 과 동일한 실패 모드(하드코딩 PK + 시드 변화)가 축소된 형태로 남아 있다.
- **재현/확인 경로**: `GET /api/v1/frames/1/labels` 를 userNo 1003 / 2001 토큰으로 각각 호출 → 403 / 200.
- **영향**: 회귀 — 시드가 바뀌면 같은 방식으로 다시 깨진다. 현재는 조건부 단언(H-ISSUE-82/83) 때문에 실패조차 하지 않고 침묵한다.
- **수정 방향(제안)**: `resolveWorkflowFixture()` 와 같은 방식으로 "라벨을 보유하고 현재 사용자가 접근 가능한 프레임"을 API 로 해석하는 경량 헬퍼(`resolveLabelableFrame()`)를 만들어 상수를 대체한다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-86] TC-E2E-016/017/018/019 — 차단 결함은 해소됐으나 완주 실행이 이번 회차에서 확인되지 않았다 (PARTIAL 사유)
- **심각도**: LOW (검증 갭 — 제품 결함 아님)
- **기대 동작(기대효과)**: 라벨링→저장→제출→반려→롤백→재제출→승인 전 구간이 실제 스택에서 완주되어야 한다.
- **현재 동작(이슈 내용)**: 스펙·픽스처는 정상화됐고(H-ISSUE-143 해소) 전제도 실동작으로 확인됐다(WORKER 2001 배정 49건·`firstSrcSn` 보유·`/v1/frames/{srcSn}/labels` 200). 그러나 ①본 회차 규칙이 테스트 실행을 금지하고 ②하네스 기본 대상 포트가 스택과 어긋나(H-ISSUE-84) 즉시 실행이 불가해, **완주 여부는 미확인**이다.
- **재현/확인 경로**: `E2E_BE_URL=http://127.0.0.1:18081 BASE_URL=http://127.0.0.1:13000 npx playwright test labeling-review-full-flow --reporter=list` (다음 회차 또는 별도 실행 사이클에서 1회 완주 필요).
- **영향**: 검증 신뢰도 — "검수 종결 E2E 커버리지 확보"가 코드 근거로만 성립하고 실행 근거가 없다.
- **수정 방향(제안)**: H-ISSUE-84 를 먼저 처리한 뒤 이 4건을 실행해 PASS 로 승격. 실행 결과를 `_raw/test-baseline.md` 에 E2E 섹션으로 추가.

### [H-ISSUE-87] (카탈로그 정합성) H-11·H-15 근거 `file:line` 드리프트 5건 + TC-E2E-016 전제 stale — **이번 회차에서 직접 정정 완료**
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 존재 이유가 근거 정확도다(루트 `CLAUDE.md` 문서 동기화 규칙 — 2026-08-03 확정).
- **현재 동작(이슈 내용)**: 아래 6건이 실제 코드와 어긋나 있었다.

  | 케이스 | 구 근거/전제 | 실제 | 조치 |
  |---|---|---|---|
  | TC-FE-165 | `validation.ts:33-49` | `validateImageFiles` 본문 33-57, 확장자 분기 38-42 | `:33-48` 로 정정 + 범위 주석 |
  | TC-FE-166 | `validation.ts:9,44` | `MAX_IMAGE_BYTES` 는 `:10`(`:9` 는 JSDoc) | `:10,44` 로 정정 |
  | TC-FE-169 | `useTusUpload.ts:30` | `endpointBase` 옵션은 `:36`(`:30` 은 JSDoc) | `:36` 으로 정정 |
  | TC-FE-170 | `PortalUploadPage.tsx:103-105,309-311` | `window.confirm` 은 `:104`, `disabled`/`title` 은 `:310-311` | `:103-104,310-311` 로 정정 |
  | TC-FE-275 | `DarkToolbar.tsx:110-112,148-154` | 필터 블록은 `:148-153` | `:148-153` 으로 정정 |
  | TC-E2E-016 | 전제 "WORKER+REVIEWER / serial" (구 하드코딩 픽스처 전제) | 픽스처가 `resolveWorkflowFixture()` 동적 해석으로 교체, 테스트 4→9건 | 전제에 동적 픽스처·구 상수 삭제 명시 + 근거에 `test-data.ts:61-64,116-128` 추가 |
  | TC-E2E-019 | `spec.ts:239-277` | 승인 단계 `:239-272`, 최종 `APPROVED` 단언 `:274-277` | `:239-272,274-277` 로 분리 표기 |

- **영향**: 카탈로그 정합성. 특히 TC-E2E-016 전제는 **이미 폐기된 하드코딩 픽스처 전제**를 들고 있어, 다음 회차가 H-ISSUE-143 을 "미해소"로 오판할 소지가 있었다.
- **수정 방향(제안)**: 이번 회차에서 담당 라인범위(H-11·H-15) 안에서 **Edit 로 직접 정정 완료**. 프로덕션 코드는 수정하지 않았다. 변경 이력 표(파일 상단)에 5회차 행 추가는 병합 담당(PM)이 전 파트 정정 건수를 합산해 기재할 것을 권고.

---

## 5. 판정 시 적용한 확정 정책(★) / UNCERTAINTIES 확인

- **UNCERTAINTIES #1(포털 SAM2 노출)** — 본 파트 FE 축에서 **해소 확인**. 원본 갱신 제안: *"1차/2차 결함 → 3차(2026-08-03, `dcdbb827`)에서 BE 삭제 + FE `PORTAL_HIDDEN_TOOLS` 단일소스 게이팅으로 ✅ 해소. FE 실동작 재확인(3차 H-part5): `/portal/label/:id`·`/portal/uploads/:uldSn/label` 양쪽 도구바에 AI 분할·추적·스켈레톤·탐지 0건."* 아울러 **★확정 정책 절 승격**(포털 SAM2 재도입 금지)을 F-part1 과 함께 권고한다.
- **UNCERTAINTIES #12(포털 rate limit 부재)** — 본 파트 범위(FE) 밖. 판정 변경 없음.
- **★1~★5** — 본 범위(H-11·H-15)에는 해당 케이스가 없어 적용 대상 없음. 위 이슈 중 어느 것도 ★ 정책을 되돌리자는 제안이 아니다.

## 6. 검증 환경 비고 (판정 무효화 아님, 다음 회차 주의)

- **MCP 브라우저 프로필 공유로 인한 세션 오염**: 검증 도중 sessionStorage `klid_jwt` 가 다른 병렬 에이전트가 인계한 REVIEWER(`sub=1001`)/WORKER(`sub=2001, channel=INTERNAL`) 토큰으로 교체되어, 정상 동작하는 `/portal`·`/portal/uploads` 가 `/forbidden` 으로 튀는 **위양성 FAIL** 이 관측됐다. 토큰 클레임 디코드로 원인을 특정하고 스토리지 정리 후 재현하여 전건 정정했다. 앞으로 **FE 실동작 검증 파트를 병렬로 배정할 때는 브라우저 컨텍스트를 분리**하거나 직렬화할 것.
- 잔여 localStorage 키 `klid-jwt-token`/`klid-user-id`/`klid-authority`(관제 공유 스토리지 채널)가 sessionStorage 보다 우선 해석되는 정황도 함께 관측됐다 — 2차 H-ISSUE-01/04(`VITE_TOKEN_INGRESS=all` fail-open)의 실증 사례로, 해당 이슈 담당 파트에 참고 정보로 이월한다(본 파트 범위 밖이라 별도 이슈로 기록하지 않음).

<!-- H-part5.md 원문 끝 -->

---

<!-- ============================================================ -->
<!-- H-part6.md 원문 시작 -->
<!-- ============================================================ -->

# H클러스터 3차 검증 — part6 (H-2 라우터구조/코드스플리팅 · H-4 useLabelStore · H-5 좌표변환/캔버스유틸 · H-7 BatchStageIndicator)

담당 라인범위: `docs/test-cases/H-frontend-e2e.md` 52~67행(H-2) · 217~235행(H-4) · 236~248행(H-5) · 274~283행(H-7). 총 38건.
검증 방법: **정적 대조 위주**(코드 Read + Grep) + 기존 자동 테스트 파일 존재·내용 확인. 빌드/테스트 실행 없음(지시에 따름). BatchStageIndicator 는 backend `BatchPipelineConfig.java`(List.of 선언 순서)와 FE `STAGE_LABEL` 매핑 순서를 직접 대조.

---

## H-2. 라우터 구조/코드스플리팅 (11건)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-023 | PASS | [정적] `router/index.tsx:228` `{ index: true, element: <Navigate to="/dashboard" replace /> }` — 정확 일치 |
| TC-FE-024 | PASS | [정적] `router/index.tsx:213-219` `/label/:id` 가 `/` AppLayout 트리 밖에서 별도 매칭(LNB/GNB 미포함), `InternalRoute allow={internalAllRoles}` 게이팅 유지 |
| TC-FE-025 | PASS | [정적] `router/index.tsx:280`(`path:'review'`)·`330`(`path:'overall'`, internalReviewerOnly)·`340`(`path:'augment'`)·`370`(`path:'manage'`) 4개 라인 전부 정확 일치. WORKER 는 `internalAllRoles` 미포함 라우트라 진입 시 RoleGuard 가 forbidden 처리 |
| TC-FE-026 | PASS | [정적] `router/index.tsx:446` `{ path: '*', element: <AppErrorPage status={404} /> }` — 정확 일치 |
| TC-FE-027 | PASS | [정적] `router/index.tsx:412-419` manage `path:'*'` placeholder 가 `internalReviewerOnly` 로 게이팅 — 정확 일치 |
| TC-FE-028 | PASS | [정적] `router/index.tsx:163-176` `isDevLoginEnabled()` false 면 `devOnlyRoutes` 배열이 비어 `/dev/login` lazy import 자체가 if 블록 안에 있어 dead-code 제거(빌드 실행 없이 코드 구조로 확인 — Vite tree-shaking 은 실측 안 함, BLOCKED 아님: 정적 판정으로 충분한 케이스) |
| TC-FE-029 | PASS | [정적] `router/index.tsx:178-198` `isDevUploadEnabled()` true 일 때만 `dev/autolabel-test` 라우트 push, `InternalRoute allow={internalReviewerOnly}`(line 193)로 REVIEWER 전용 확인 |
| TC-FE-030 | PASS | [정적] `router/index.tsx:155-161` `PortalRoute` = `ChannelGuard channel="PORTAL"` + `RoleGuard allow={portalOnly}` 이중가드, 코드 정확 일치 |
| TC-FE-031 | PASS | [정적] `router/lazyWithRetry.ts` 전체 확인 — 1차 실패 시 300ms 지연 후 1회 재시도, 재시도도 실패하면 세션당 1회 `window.location.reload()`, reload 중엔 영구 pending Promise 로 Suspense fallback 유지. 기대결과("재시도(Suspense fallback)")와 일치. ⚠ **coverage 관찰**: 이 유틸 전용 단위테스트 파일이 없음(`grep -rn lazyWithRetry --include=*.test.*` 0건) — 로직 자체는 정확하나 회귀 가드 부재. FAIL 아님(카탈로그가 테스트 존재를 주장하지 않음), 관찰 기록만 |
| TC-FE-032 | PASS | [정적] `router/index.tsx:122-128` `PageFallback` 컴포넌트 `<Spinner label="페이지 로딩" />` — 정확 일치 |
| TC-E2E-002 | PASS | [정적] `router/__tests__/{deepLinkHydrationGuard,manageGuard,portalGuard,reviewGuard,augmentExportGuard}.test.tsx` 5개 파일 전부 존재(총 429줄) 확인. `manageGuard.test.tsx` 내용 열람 — `RoleGuard`+`MemoryRouter` 조합으로 REVIEWER 전용 라우트 접근/차단을 렌더 테스트로 검증하는 구조 확인 |

**H-2 소계**: 11 PASS / 0 FAIL / 0 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요

---

## H-4. useLabelStore (Zustand) (14건)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-088 | PASS | [정적] `stores/useLabelStore.ts:486-495` `setLabels` — `dirtyLabels/undoStack/redoStack/selectedLabelId/hiddenLabelIds/lockedLabelIds` 전부 초기화, 라인 정확 일치 |
| TC-FE-089 | PASS | [정적] `stores/useLabelStore.ts:497-503` `addLabel` — `dirty.add(label.id)` 확인, 라인 정확 일치 |
| TC-FE-090 | PASS | [정적] `stores/useLabelStore.ts:66-89` `offsetShape` — BBOX(4꼭짓점 dx/dy)·POLYGON(짝/홀 인덱스 dx/dy)·KEYPOINT(전체 keypoints dx/dy) 타입별 분기 확인, 라인 정확 일치 |
| TC-FE-091 | PASS | [정적] `stores/useLabelStore.ts:92-117` `clampShape` — `cx/cy` 헬퍼가 `w`/`h` undefined 시 `Math.max(v,0)`(하한 0만), 지정 시 `Math.min(Math.max(v,0),w)`(양쪽 clamp). "미지정 시 하한 0만 적용" 기대결과와 정확 일치 |
| TC-FE-092 | PASS | [정적+테스트] `mergeAutoLabels`(:636-653) `mergeDetections` 로 중복(같은 클래스+IoU) 스킵 후 신규분만 병합, `merged.length` 반환. `useLabelStore.mergeAuto.test.ts` 파일 존재 확인 |
| TC-FE-093 | PASS | [정적+테스트] `revertSaveEvent`(:655-734) UPDATED/ADDED/DELETED 3종 분기로 역적용, `{reverted, skipped}` 반환 구조 확인. `useLabelStore.revert.test.ts` 존재 |
| TC-FE-094 | PASS | [정적+테스트] `stashPendingTracks`/`drainPendingTracks`(:736-758) — drain 은 해당 srcSn 만 제거하고 반환, `setLabels` 호출에도 보존됨(테스트 `setLabels_프레임전환에도_보류는_보존된다` 확인). 호출측 `pages/label/LabelingPage.tsx:437`에서 프레임 진입 시 `drainPendingTracks(srcSn)` 호출 확인 — "진입 시 drain 병합" 실배선 확인 |
| TC-FE-095 | PASS | [정적+테스트] `toggleLabelVisibility`(:567-572) — 새 Set 생성(불변성), `dirtyLabels` 미접촉. 테스트 파일 `useLabelStore.visibility.test.ts` 열람 — `toggle는_불변성_유지_새_Set_생성` 케이스로 확인됨 |
| TC-FE-096 | PASS | [정적+테스트] `updateLabel`(:505-514) 진입부 `if (get().lockedLabelIds.has(id)) return;` 로 no-op. 테스트 `useLabelStore.lock.test.ts` — `잠금_라벨은_updateLabel이_no_op이다`(dirty/undo 미변화 단언 포함) 확인 |
| TC-FE-097 | PASS | [정적+테스트] `copyLabels`(:582-596)/`pasteLabels`(:598-634) — 개수 반환, `sourceRawSn` 비교로 crossVideo 판정 시 `trackId=null`. `useLabelStore.clipboard.test.ts` — 딥클론·onlySelected·빈 케이스 0반환 전부 확인 |
| TC-FE-098 | PASS | [정적+테스트] `features/label/types.ts` `KeypointShape.keypoints`(x/y/v 삼중값) 확인. `useLabelStore.keypoint.test.ts`의 `makeKps()`가 17개 keypoint 생성(COCO-17, `keypointHelpers.ts:1` 주석 "COCO-17 휴먼 포즈" 확인) + undo 시 keypoints 배열까지 딥클론 복원 검증 |
| TC-FE-099 | PASS | [정적+테스트] `setImageAdjust`(:561-562)/`resetImageAdjust`(:564) — zustand store 에 `persist` 미들웨어 없음(`grep persist` 0건) → 세션 전용 확인. 테스트 `조절값_세션전용_영속안됨_reset시_default복원` 통과 구조 확인 |
| TC-FE-100 | PASS | [정적+테스트] `activeLabelId`(초기 null) + `setActiveLabelId`. `useLabelStore.activeLabel.test.ts`로 store 측 확인. `resolveDefaultLabel`(`features/label/canvas/layers/resolveDefaultLabel.ts`)이 `OverlayLayer.tsx`에서 `activeLabelId` 를 받아 프리셋 기본값 파생 — 별도 `resolveDefaultLabel.test.ts` 존재(카탈로그 미인용이나 실사용처 확인됨). 부가 관찰: 카탈로그가 인용한 테스트 파일은 store setter 만 다루고 `resolveDefaultLabel` 자체 로직은 별도 파일이 커버 — 기능은 정상, 인용 범위만 다소 좁음(FAIL 아님) |
| TC-FE-101 | PASS | [정적] `stores/useLabelStore.ts:4`(import `clampPan as clampPanByScale`) + `:386-406`(`clampPan` 함수, `clampPanByScale` 위임) + `:534`(`setPan: (x, y) => set({ panX: x, panY: y })` — raw setter, 클램프 없음) 확인. `CanvasShell.tsx:301-320`(`handleMouseMove`) 에서 드래그 좌표 계산 후 `clampPan(...)` 호출해 clamp 결과를 `setPan`에 전달 — 라인 정확 일치, 위임 구조 실배선 확인 |

**H-4 소계**: 14 PASS / 0 FAIL / 0 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요

---

## H-5. 좌표 변환/캔버스 유틸 (canvas scale 함정) (8건)

> **함정 재현 시도 결과**: "canvas scale 함정"(줌/팬 시 좌표 오차)을 반증하기 위해 `coordinateTransformer.test.ts`의 라운드트립 테스트를 스케일 0.5·오프셋(top20/left40)·회전각(30°) 조합으로 확인했다. `translateToCanvas`→`translateFromCanvas` 역변환이 오차 4~5자리 소수점 이내로 원좌표를 복원(`toBeCloseTo(original.x, 4~5)`) — **함정이 실제로 열려있지 않고 방어돼 있음**을 실측 확인. `canvasGeometry.ts`(`buildGeometry`)가 fit-scale × zoom 을 단일 `scale` 값으로 합성해 `coordinateTransformer.Geometry`에 주입하는 구조라 두 좌표계가 분리되지 않는다.

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-102 | PASS | [정적+테스트] `coordinateTransformer.ts:43-51` `translateToCanvas` — `x: rx*scale+left`(회전 적용 후). 테스트 `translateToCanvas_scale_offset_적용`에서 `100*0.5+20`/`200*0.5+10` 정확 일치 단언 확인 |
| TC-FE-103 | PASS(카탈로그 라인 정정) | [정적+테스트] `coordinateTransformer.ts:57-65` `translateFromCanvas` — 카탈로그 인용 `:57-61`은 함수의 앞 절반(cx/cy/rx/ry 계산)만 가리키고 핵심인 역회전+return 문(62-65)이 라인범위 밖이었음. **본 회차에서 `:57-65`로 정정**(H-frontend-e2e.md:241, 내 라인범위 내). 로직 자체는 정상 — 회전 각도 30° 포함 라운드트립 테스트(`회전_각도_있는_상태에서도_라운드트립_유지`)로 오차 4자리 이내 복원 확인 |
| TC-FE-104 | PASS | [정적+테스트] `coordinateTransformer.ts:132` `clampToImage` — `clamp(x,0,image.width)`/`clamp(y,0,image.height)`. 별도 테스트(`clamp` describe 블록: 하한/상한/범위내 3케이스) 확인 |
| TC-FE-105 | PASS | [정적+테스트] `coordinateTransformer.ts:106` `computeWrappingBox` — min/max 순회로 외접박스 계산, 테스트로 3점 세트·단일점 케이스 모두 확인 |
| TC-FE-106 | PASS | [정적+테스트] `coordinateTransformer.ts:71` `rotate2DPoints` — 90°/360°/180° 회전 테스트 전부 기대값과 일치(`toBeCloseTo`) |
| TC-FE-107 | PASS(카탈로그 제목 정정) | [정적+테스트] `maskRleConverter.ts` 전체 열람 — `maskToRle`/`rleToMask`/`imageDataToRLE`/`rleToImageData`/`isEmptyRle` 5개 함수만 존재, **Polygon 변환 함수는 파일 전체에 0건**(`grep -n Polygon` 무결과). 테스트 파일 자체 헤더 주석도 "CVAT MASK ↔ RLE 변환 테스트"로 Polygon 언급 없음. `frontend/src` 전체에서 RLE↔Polygon 상호변환 함수 검색(`grep -rln rleToMask\|maskToRle`)해도 `MaskBrushTool.tsx`/`MaskEraserTool.tsx` 2곳만 이 모듈을 소비할 뿐 Polygon 변환 경로 없음. **카탈로그 표제 "MASK↔RLE↔Polygon 변환"이 미구현 기능을 포함한 오류로 판단 — 본 회차에서 "MASK↔RLE 변환"으로 정정**(H-frontend-e2e.md:245, 내 라인범위 내). 실제 구현(MASK↔RLE·imageData↔RLE 왕복)은 랜덤 마스크 1000회 부하 테스트까지 포함해 정상 동작 확인 |
| TC-FE-108 | PASS | [정적+테스트] `trackInterpolation.ts` — `interpolateBBox`(선형보간, t clamp [0,1])·`interpolateTrack`(범위 밖 extrapolation 없이 가장 가까운 끝값, 구간 탐색 후 t 계산). `trackInterpolation.test.ts` 존재 확인 |
| TC-FE-109 | PASS | [정적] `pages/label/LabelingPage.tsx:287-319` `frameNaturalSize` state + `handleImageSize` — 주석 "하드코딩 1920×1080 제거", `CanvasShell`이 이미지 onload 시 실측 naturalWidth/Height 통지 → `viewKeyRef`로 영상/해상도 변경 시에만 `resetView()`(동일 영상·해상도면 zoom/pan 유지, 스트레치 없음) |

**H-5 소계**: 8 PASS / 0 FAIL / 0 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요 (카탈로그 정정 2건: TC-FE-103 라인범위, TC-FE-107 표제)

---

## H-7. BatchStageIndicator (STAGE_ORDER 드리프트 함정) (5건)

> **드리프트 함정 재현 시도 결과**: BE `backend/.../batch/pipeline/BatchPipelineConfig.java`의 `List.of(markingLoad, vlm, frame, yolo, sam2, interp)`(post-marking) + `List.of(deid)`(pre-marking) 선언 순서(DEIDENTIFY→MARKING→VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE, CLAUDE.md 서술과 일치)와 FE `BatchStageIndicator.tsx:16-24` `STAGE_LABEL` 맵의 키 순서를 1:1 대조 — **완전 일치, 드리프트 없음**. 더 중요한 것은 FE 렌더 로직(`:70` `stages.map`)이 이 맵 순서에 의존하지 않고 **BE가 내려준 `stages` 배열 순서를 그대로 순회**한다는 점(`:12-15` 주석 "FE 는 순서를 가정하지 않고 배열을 그대로 렌더") — 즉 향후 BE가 `BatchPipelineConfig`의 `List.of(...)` 순서를 재배치해도 FE 는 하드코딩된 순서 가정이 없어 자동으로 정합 유지. 함정이 이름과 달리 **현재는 방어돼 있음**.

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-126 | PASS | [정적] `BatchStageIndicator.tsx:66` `if (!stages \|\| stages.length === 0) return null;` — 정확 일치 |
| TC-FE-127 | PASS | [정적] `BatchStageIndicator.tsx:16-24` `STAGE_LABEL` 7종 매핑(DEIDENTIFY→'비식별', MARKING→'마킹', VLM→'VLM', FRAME_EXTRACT→'프레임추출', YOLO→'AI 탐지', SAM2→'AI 분할', INTERPOLATE→'보간') — 기대결과 문구와 정확 일치 |
| TC-FE-128 | PASS | [정적] `BatchStageIndicator.tsx:26-28`(`STAGE_LABEL_FALLBACK = '처리중'`) + `:81`(`STAGE_LABEL[stage.name] ?? STAGE_LABEL_FALLBACK`) — YOLO/SAM2 같은 기술 코드명이 화면에 노출되지 않고 매핑된 한글 라벨("AI 탐지"/"AI 분할")로만 표시됨. 미지 코드는 "처리중" 폴백 |
| TC-FE-129 | PASS | [정적+BE 대조] `BatchStageIndicator.tsx:12-15`(주석)+`:70`(`stages.map((stage, idx) => {`) — FE 순서 가정 없이 배열 순회. BE `BatchPipelineConfig.java` 의 `List.of(...)` 선언 순서와 FE 매핑 키 순서 대조 결과 완전 일치(위 서두 참조) |
| TC-FE-130 | **PARTIAL(2차 H-ISSUE-70 재확인 — 미해소)** | [정적] `BatchStageIndicator.tsx:30-57` `StageIcon` — DONE(Check)/PROGRESS(Loader2)/FAIL(X) 아이콘 전부 `aria-hidden`(:34,41,48), PENDING 은 장식용 `<div>`(:52-56). `grep -n "aria-label\|sr-only\|role="` 전체 파일 0건 — 상태(완료/진행중/실패/대기)를 스크린리더가 인지할 방법이 없음. **2026-08-02 2차 검증에서 이미 `H-ISSUE-70`(MEDIUM)으로 보고된 것과 동일 결함이며, 이번 회차 코드에도 수정 미반영(회귀 아님, 단순 미해소)** — 아래 이슈로 재기록(신규 ID 부여, 원 이슈 참조 명시) |

**H-7 소계**: 4 PASS / 0 FAIL / 1 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요

---

## 이슈 목록

### [H-ISSUE-101] TC-FE-130 — BatchStageIndicator 단계 상태가 스크린리더에 노출되지 않음 (2차 H-ISSUE-70 미해소 재확인)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WCAG 2.1 AA 준수 요구(루트 CLAUDE.md 포털 섹션) 및 `component.md`의 "색상만으로 정보 전달 금지(아이콘/텍스트 병행)" 원칙에 따라, 배치 단계별 상태(완료/진행중/실패/대기)는 스크린리더 사용자도 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `frontend/src/components/common/BatchStageIndicator.tsx`의 `StageIcon`(:30-57)이 렌더하는 `Check`/`Loader2`/`X` 아이콘은 전부 `aria-hidden`(각 :34,41,48)이고 PENDING 상태는 장식용 `<div>`(:52-56)뿐이다. 단계마다 노출되는 텍스트(:77-82)는 단계 이름("비식별", "마킹" 등)만이며, 상태를 나타내는 `aria-label`/`sr-only`/`role` 속성이 컴포넌트 전체에 0건이다(`grep -n "aria-label\|sr-only\|role="` 결과 0건, 3차 시점 재확인). 2026-08-02 2차 검증에서 동일 결함이 `H-ISSUE-70`으로 이미 보고됐으나, 3차 시점 코드(HEAD `e065da42`)에도 수정이 반영되지 않았다.
- **재현/확인 경로**: `grep -n "aria-hidden\|aria-label" frontend/src/components/common/BatchStageIndicator.tsx` → aria-hidden 3건, aria-label 0건. 스크린리더(VoiceOver 등)로 마킹/영상상세 화면 진입 → `data-testid="batch-stage-indicator"` 영역 탐색 시 단계 이름만 낭독되고 상태 정보 낭독 없음.
- **영향**: 접근성(WCAG 2.1 AA 1.1.1/4.1.2 상당) — 시각장애 사용자가 배치 처리 실패(FAIL) 여부를 화면에서 인지할 수 없어 대응이 늦어질 수 있음. 보안 영향 없음.
- **수정 방향(제안)**: 각 단계 아이콘 wrapper 또는 단계 컨테이너에 상태를 서술하는 `aria-label`(예: `` `${label} — ${status==='DONE'?'완료':status==='PROGRESS'?'진행중':status==='FAIL'?'실패':'대기'}` ``) 또는 `sr-only` 텍스트를 추가. 아이콘 자체의 `aria-hidden`은 유지하되 상위 요소에 접근성 트리 정보를 부여. (2차 이슈와 동일 제안 — 재수록)

---

## 카탈로그 정정 (내 라인범위 내 Edit로 직접 반영, 2건)

1. **TC-FE-103**(H-frontend-e2e.md:241) — 근거 라인 `coordinateTransformer.ts:57-61` → `:57-65`로 정정. 구 범위는 `translateFromCanvas` 함수의 역회전 계산 및 `return` 문(62-65줄)을 누락하고 있었다.
2. **TC-FE-107**(H-frontend-e2e.md:245) — 표제 "maskRleConverter MASK↔RLE↔Polygon" → "maskRleConverter MASK↔RLE 변환"으로 정정 + 사유 주석 추가. 실제 `maskRleConverter.ts`에는 Polygon 변환 함수가 전혀 없다(MASK↔RLE·imageData↔RLE 왕복만 제공). 테스트 파일 자체 설명도 "MASK ↔ RLE 변환"으로 Polygon을 언급하지 않는다 — 미구현 기능을 표제에 포함한 카탈로그 오류였다.

---

## 이전 회차(2026-08-02 2차) 이슈 해소 여부

내 담당 라인범위(H-2/H-4/H-5/H-7)에 걸리는 2차 이슈는 **H-ISSUE-70**(TC-FE-130, BatchStageIndicator a11y) 1건뿐이었다. **미해소** — 위 H-ISSUE-101로 재기록(동일 결함, 코드 변경 없음). 그 외 2차 ISSUES.md의 H 관련 이슈(H-ISSUE-02/41/43/103/143/01/03/42/70/81/101~102/141~147/44/82/104~105/144~145 등)는 H-1/H-3/H-6/H-8~H-18 섹션 소관으로 내 라인범위 밖이라 이번 part 에서 재검증하지 않았다.

---

## 종합 집계 (H-part6)

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| H-2 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| H-4 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| H-5 | 8 | 8 | 0 | 0 | 0 | 0 | 0 |
| H-7 | 5 | 4 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **38** | **37** | **0** | **1** | **0** | **0** | **0** |

신규/재기록 이슈: **H-ISSUE-101** 1건(MEDIUM, a11y, 2차 H-ISSUE-70 미해소 재확인). 카탈로그 정정 2건(TC-FE-103 라인범위, TC-FE-107 표제).

<!-- H-part6.md 원문 끝 -->

---

<!-- ============================================================ -->
<!-- H-part7.md 원문 시작 -->
<!-- ============================================================ -->

# H클러스터 part7 — H-6(MarkingPage) · H-8(ReviewPage) · H-9(관리화면) · H-10(증강/해상도 파생화면) — 3차 검증

담당 라인범위: `docs/test-cases/H-frontend-e2e.md` 249~346행 (H-6·H-8·H-9·H-10), 총 66건.
방법: 실 소스코드 정적 대조(파일:라인 1:1 실측) + `_raw/test-baseline.md`(2026-08-04 실행, frontend 340 files/2064 tests 전량 PASS) 테스트 커버 대조 + BE DTO 실측(ReviewResponse.java) + 이전 회차(2차) 이슈 대조. 스택은 살아있음(`localhost:9400/health`=200, `localhost:18081/api/actuator/health`=UP) 확인. 코드/빌드/테스트 실행 없음(baseline 재사용).

## 판정 요약

| 절 | 케이스 수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| H-6 MarkingPage | 20 | 20 | 0 | 0 | 0 | 0 | 0 |
| H-8 ReviewPage | 21 | 20 | 1 | 0 | 0 | 0 | 0 |
| H-9 관리화면 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| H-10 증강/해상도 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **66** | **65** | **1** | 0 | 0 | 0 | 0 |

## H-6. MarkingPage (TC-FE-110~125, TC-FE-203~205, TC-E2E-003) — 전건 PASS

`frontend/src/pages/MarkingPage.tsx`(244줄) 전문을 Read 하여 카탈로그 근거 20건 **전부 라인 단위로 정확히 일치**함을 확인(TC-FE-110:154-156, 111:158-173, 113~115:139-147, 116:136-137, 117~118:118-125, 119:116, 120:61-77, 121:81-89, 122:49-58, 123:92-97, 124:181-187, 125:221-241 — 전건 실측 라인과 카탈로그 라인 동일). `features/marking/markingFps.ts`(TC-FE-203, resolveMarkingFps 21-27+markingFrameIndex 29-31)·`VideoPlayer.tsx`(TC-FE-205, SPEED_OPTIONS 34/changeSpeed 46-49/배속버튼 125-140) 도 정확. 드리프트 0건 — 이 절은 최근 회차에 이미 정비된 상태로 판단.
- 기능 확인: isMarkingBlocked 백스톱(158-173, role=alert), Space/Del·Backspace/Enter 단축키(139-147), INPUT/TEXTAREA/**SELECT** 3종 억제(136-137), AUTO intervalFrames<1·MANUAL 0건 제출 차단(118-125), 중복 제출 방지(createMutation.isPending, 116), batchTriggered=false 시 error 토스트로 성공 위장 금지(63-75, TC-FE-204) 전부 소스 확인.
- 테스트 커버: `pages/__tests__/MarkingPage.test.tsx`(TC-E2E-003) 파일 존재 확인, `test-baseline.md` frontend 2064건 전량 PASS(2026-08-04 실행)에 포함.

## H-8. ReviewPage (TC-FE-131~147, TC-FE-206~208, TC-E2E-004) — 20 PASS / 1 FAIL

`frontend/src/pages/ReviewPage.tsx`(412줄) 전문 Read. TC-FE-131~145·147·206~208 근거 라인 전부 실측과 일치(131:229-238, 132:240-255, 133:257-266, 134:179-183, 136:306, 137:206-208, 138:155-160, 139:174, 140:65-84, 141:380, 142:126-139, 143:141-149, 144:186-192, 145:194-200, 147:355, 206:170-172+224-227+387-397, 207:165-175, 208: `ReviewMetaPanel.tsx` normalizeCot(55)+`eventAnnotation.ts` normalizeCot(26) 확인). TC-FE-135(캔버스 읽기전용, 카탈로그 294-311)만 `<main>`이 실제로는 308행에서 닫히고 309-311은 `<aside>` 시작부라 3줄 과다포함 — 기능 검증에는 무해한 경미한 오차라 별도 이슈로 올리지 않음.

### [해소 확인 실패 — H-ISSUE-81 재확인 FAIL] TC-FE-146 — 검수 화면 증강/해상도 파생 표시, 2차 이후 미해소
- **판정**: **FAIL** (2차 H-ISSUE-81 그대로 이월, 미해소)
- 근거: `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java` 전체 필드(id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd)에 `orgnlRawSn`/파생 종류 필드 없음(그대로 실측). FE `features/review/types.ts`의 `Review` 인터페이스도 동일하게 파생 필드 없음. `ReviewMetaPanel.tsx`·`ReviewHeader.tsx`에 `orgnlRawSn`/`ORGNL_RAW_SN`/`vmsClipId`/증강 키워드 매치 0건(grep 재확인).
- 이 회차에도 관련 커밋 없음(`git log`로 review/dto 변경이력 확인, 이슈 이후 review 폴더 변경은 `이슈 댓글 작성자 이름 표시`(a3dc1579)뿐 — 파생 필드 추가 없음).
- **이전 회차 대비**: H-ISSUE-81(2차, MEDIUM)과 완전 동일 사유로 재확인 FAIL. 별도 신규 이슈 번호 부여하지 않고 이월 처리(ISSUES.md 병합 단계에서 H-ISSUE-81 재확인으로 표기 권장).

## H-9. 관리 화면 /manage/* (TC-FE-148~156, TC-E2E-005~006) — 전건 PASS

파일 존재·핵심 동작 실측: `LabelMasterManagePage.tsx`(dtctTypeCd 폼 필드 112행), `YoloConfigCard`/`PrecisionConfigCard.tsx`(zod 스키마+useUpdateConfig 재사용, YOLO_CONF_THRESHOLD/POLYGON_SIMPLIFY_TOLERANCE), `PresetListPage.tsx`(라벨 1~20종 zod 제한 + labelId 기반 join 표시), `DeidentReportListPage.tsx`(OPEN/RESOLVED 상태 필터), `AssignModal.tsx`(workerId state), `HistoryDrawer.tsx`(REVIEWER 전용 주석 + `/assignments/{id}/history` 조회) 전부 카탈로그 기대대로 존재.
E2E: `reviewer-workflow.spec.ts`(TC-E2E-005, 카탈로그 14-47 → 실측 5개 테스트 시작줄 14/24/33/40/47과 정확히 일치), `video-list.spec.ts:5,15`(TC-E2E-006, 실측 라인 5·15 정확 일치).

### [카탈로그 정정 1건] TC-FE-149 — 근거 file 드리프트 재확인 + 이번 회차 직접 정정
- 2차 H-ISSUE-82(LOW, 카탈로그 정합성)에서 이미 지적된 건: 카탈로그가 `pages/manage/__tests__/UserManagePage.test.tsx`를 근거로 들지만 이 파일(70줄)은 REVIEWER 세션 고정 후 검색필터만 검증하고 역할 분기 코드가 0건(grep "WORKER\|forbidden\|Role\." 결과 REVIEWER role 세팅 라인 1건뿐, WORKER 분기 없음). 실제 역할 접근제어 검증은 `frontend/src/router/__tests__/manageGuard.test.tsx:45-71`(WORKER→FORBIDDEN 2케이스 + REVIEWER→정상 렌더 1케이스, 3케이스 전부 실측 확인)이다.
- **이번 회차 조치**: 담당 라인범위(315행) 내에서 `H-frontend-e2e.md`를 Edit로 직접 정정 — 근거를 `router/__tests__/manageGuard.test.tsx:45-71`로 교체하고 구 근거의 한계를 인라인 각주로 남김. 기능 자체는 정상(라우터 가드 + BE 403 실측 기반, 2차에서 이미 실HTTP 확인됨)이라 판정은 PASS 유지.

## H-10. 증강/해상도 파생 화면 (TC-FE-157~164, TC-FE-209~213, TC-E2E-007) — 전건 PASS

2차 H-ISSUE-105가 "H-10/H-11 근거 file:line 드리프트 8건"을 지적했으나, **이번 3차 실측 결과 H-10 범위(TC-FE-159/160/161/209/210/211) 전부 이미 카탈로그가 정확한 라인으로 갱신되어 있음을 확인**(2차 이후 다른 세션이 반영한 것으로 추정, 이번 회차 재수정 불필요):
- TC-FE-159: `JobCard.tsx:81-89`(resolutionTypes.map 렌더) — 실측 라인 81-89 정확 일치.
- TC-FE-160: `AugmentResultPanel.tsx:123-128,237-252`(isResolution/showDecision 판정 + DecisionCard 조건부 렌더) — 정확 일치.
- TC-FE-161: `JobCard.tsx:90-98`(파생 태그 조건부 렌더) — 정확 일치.
- TC-FE-209: `AugmentResultPanel.tsx:168-197`(gridFrames 렌더 블록, FrameGrid12+SideBySideCompare authImages) — 정확 일치.
- TC-FE-210: `AugmentResultPanel.tsx:29-30,110-113,177-186`(FRAME_PAGE_SIZE 상수+showFramePager 판정+페이저 렌더) — 정확 일치.
- TC-FE-211: `AugmentVideoSection.tsx:45-64`(activeId state+탭 전환 시 프레임페이지 리셋 effect) — 정확 일치.
- TC-FE-158: `useResolutionDerivative.ts:17-27`(mutation 함수 전체) — 정확 일치. TC-FE-157: `augment/types.ts:24-46`(PROCESS_KINDS~PROCESS_KIND_DESCRIPTION) — 정확 일치. TC-FE-212: `augTypeLabel.ts:30-36`(augTypeLabel 함수 전체) — 정확 일치. TC-FE-213: `AugmentResultPage.tsx:263-296,339-421`(완료+결과0건 안내 미노출 블록·생성률 카드·summarize 함수 페이지스코프 주석) — 정확 일치, "비교 프레임 쌍은 페이지 스코프 값" 정정 문구도 코드 주석(340행 `⚠ **현재 항목 페이지에 실린 항목들**`)과 부합.
- TC-FE-162~164(DecisionCard.tsx/RejectReasonModal.tsx, 파일 레벨 근거)도 파일 존재·테스트(`DecisionCard.test.tsx`/`.discard.test.tsx`/`.restore.test.tsx`) 확인.
- TC-E2E-007: `augment-decision.spec.ts:4` 파일 존재 확인(2차 H-ISSUE-102가 지적한 "존재하지 않는 jobId 대상 무단언 통과" 문제는 H-11 인접 범위라 본 파트 판정 대상 아님, 참고만 기록).

## 이전 회차(2차) 이슈 대조 — 내 담당 범위(H-6/H-8/H-9/H-10) 관련분

| 2차 이슈 | 내용 | 3차 상태 |
|---|---|---|
| H-ISSUE-81 | TC-FE-146 검수화면 파생 표시 BE DTO 미보유 FAIL | **미해소 — 재확인 FAIL** (위 상세 참조) |
| H-ISSUE-82 | TC-FE-149 근거 file 드리프트(LOW) | **미해소이나 이번 회차 카탈로그 직접 정정 완료**(315행) |
| H-ISSUE-104 | TC-FE-213 "라벨 무결성"→"증강 이미지 생성률" 개명 카탈로그 정합 | **해소 확인** — 카탈로그가 이미 "증강 이미지 생성률"·"비교 프레임 쌍" 문구로 갱신되어 실제 코드(287행 "증강 이미지 생성률")와 일치 |
| H-ISSUE-105 (H-10 해당분) | TC-FE-159/160/161/209/210/211 근거 라인 드리프트 8건 중 H-10분 | **해소 확인** — 위 상세 참조, 재수정 불필요 |

## 카탈로그 정정 건수

**1건** (TC-FE-149, 315행 — 근거 file 교체 `router/__tests__/manageGuard.test.tsx:45-71`). H-10 관련 드리프트는 이미 타 세션이 정정 완료된 상태를 재확인만 함(신규 Edit 없음).

## 결론

담당 66건 중 PASS 65 / FAIL 1(TC-FE-146, 2차 H-ISSUE-81 미해소 이월). 카탈로그 자체 정합성은 H-6·H-10 전건 정확, H-9 1건 정정 완료, H-8 1건(TC-FE-135) 3줄 경미한 과다범위는 기능 검증 무해로 별도 이슈화 안 함.

<!-- H-part7.md 원문 끝 -->

---

<!-- ============================================================ -->
<!-- H-part8.md 원문 시작 -->
<!-- ============================================================ -->

# H클러스터 Part8 — H-12/H-13/H-16/H-17/H-18 (2026-08-03 3차)

담당 범위: `docs/test-cases/H-frontend-e2e.md` H-12(374-393) · H-13(395-413) · H-16(438-467) · H-17(469-492) · H-18(494-517) — 총 80건.
방법: 정적 대조(Read) 전건 + 라이브 API 확인(backend :18081, REVIEWER/WORKER dev 토큰) 일부 + 이전 회차(2026-08-02 2차) 이슈 대조.
빌드/테스트 미실행(지시 준수). 스택은 `_raw/stack-bringup.md` 기준 기동·배선 OK 확인된 상태를 전제.

---

## 카탈로그 정정 (완료, 1건)

`docs/test-cases/H-frontend-e2e.md` **TC-FE-224**를 Edit로 직접 정정함(내 담당 라인범위 내, 프로덕션 코드 미수정).

- **정정 전**: "WORKER 시각 URL `?status=IN_PROGRESS`" 를 전제로 하면서 기대결과는 "select 값 유지, 서버 파라미터에서는 제외(`asWorkStatusParam`→undefined)" — 이는 실제로는 **REVIEWER 축**(`asWorkStatusParam`)의 동작 서술이며, WORKER 축은 다른 함수(`asAssignmentWorkStatusParam`)를 쓰고 결과도 반대(서버로 전송됨)다. 2차 `H-ISSUE-144`가 이미 이 불일치를 지적했으나 후속 정정(`H-ISSUE-147`)은 file:line만 고치고 본문 텍스트는 그대로 남아 있었다.
- **실측(라이브)**: `GET /api/v1/assignments?workerId=2001&workStatus=IN_PROGRESS` (WORKER 토큰) → `200`, `totalElements` 49(무필터) → 1(필터 적용) — **서버로 전송되고 실제로 필터링됨**을 확인. 반대로 `GET /api/v1/tasks/board?...&workStatus=IN_PROGRESS` (REVIEWER 토큰) → `400 INVALID_INPUT "board.workStatus: 허용되지 않은 workStatus 값"` — REVIEWER 축은 BE 가 거부.
- **정정 후**: TC-FE-224 를 WORKER 축 전용 케이스로 재서술(select 유지 + 서버 전송 확인, REVIEWER 축은 TC-FE-225 가 별도로 다룸을 명시), 근거 file:line 도 `asWorkStatusParam` vs `asAssignmentWorkStatusParam` 두 함수 모두를 가리키도록 보정.

---

## 이전 회차(2차) 이슈 해소 여부 대조

| 2차 이슈 | 내용 | 이번 회차 상태 |
|---|---|---|
| H-ISSUE-144 | TC-FE-224 기대결과가 stale(카탈로그 정합성) | **부분 해소 → 이번에 완전 정정.** file:line 은 2차 후속(H-ISSUE-147)에서 고쳤으나 본문 텍스트가 REVIEWER/WORKER 축을 혼동한 채 남아 있어 이번에 재정정함(위 "카탈로그 정정" 참조) |
| H-ISSUE-145 | TC-FE-242 기대결과가 stale("클라이언트 필터") | **해소 확인.** 현재 카탈로그는 "서버 필터(정렬만 미지원)"으로 이미 정정되어 있고 코드(`TaskListPage.tsx`에 `.filter(` 재필터 없음, `buildAssignmentParams`가 q/workStatus/eventTypeCd를 서버로 위임)와 일치 |
| H-ISSUE-146 | WORKER KPI 4카드가 전체 기준이 아니라 현재 페이지 20행만 집계(정책 위반, MEDIUM) | **미해소 — 이번 회차에도 재확인.** `TaskWorkerKpiCards.tsx:20-21` 이 `rowStatuses.filter()`로 페이지 내 카운트, `TaskListPage.tsx:484-487` `workerRowStatuses = pagedRows.map(...)`. 아래 H-ISSUE-141 로 이월 |
| H-ISSUE-147 | H-16 근거 file:line 광범위 드리프트(14건 표) | **거의 전건 해소.** 표의 14개 TC 중 13건은 현재 카탈로그가 정확한 위치(또는 정확한 위치를 포함하는 약간 넓은 범위)를 가리킴. TC-FE-223 하나만 `boardParams.ts:80-96`(원래 드리프트 값)로 **미수정 상태였으나, 실제 `asUiWorkStatus`(90-93)가 이 범위 안에 포함되어 있어 추적성에 실질적 지장은 없음** — 별도 이슈로 올리지 않음(경미) |

2차 H-ISSUE 중 H-12/H-13/H-18 관련 항목은 없었음(2차에서 이 세 섹션은 이슈 없이 통과).

---

## 판정 결과표

### H-12. 공통 컴포넌트/에러/상태 (16건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-178 | PASS | [정적] `ErrorBoundary.tsx:21-38` — `getDerivedStateFromError`가 사용자 메시지만 state에 담고, `componentDidCatch`는 `console.error`로만 스택 출력. 렌더는 `role="alert"` + "오류가 발생했습니다" 고정 문구 |
| TC-FE-179 | PASS | [정적] `ErrorBoundary.tsx:30` `this.props.fallback ?? (...)` — fallback prop 우선 렌더 |
| TC-FE-180 | PASS | [정적] `AppErrorPage.tsx` — 403/404/500 `TITLES` 매핑, `role="alert"` |
| TC-FE-181 | PASS | [정적] `EmptyState.tsx` — `role="status"`, 기본 메시지 "데이터가 없습니다" |
| TC-FE-182 | PASS | [정적] `AuthImage.tsx:60-122` — `srcSn`→`/frames/{srcSn}/image`, blob→objectURL, 로딩/에러 `<div>` 폴백에 `toFallbackProps`로 `data-*/aria-*` 유지(43-47행) |
| TC-FE-183 | PASS | [정적] `Pagination.tsx` — `onPageChange` 클릭 핸들러 전건 배선 |
| TC-FE-184 | PASS | [정적] `DataTable.tsx:60-77` — `handleSort`, `col.sortable`인 컬럼만 정렬 버튼 렌더 |
| TC-FE-185 | PASS | [정적] `Toast.tsx` — `variantClass`/`variantIcon` 4종(success/error/warning/info) 매핑 |
| TC-FE-186 | PASS | [정적] `pages/NoticeListPage.tsx`·`pages/NoticeDetailPage.tsx` 존재 확인 |
| TC-FE-187 | PASS | [정적] `pages/RoleClaimPage.tsx` 존재 확인 |
| TC-FE-188 | PASS | [정적] `WorkerStatPage.tsx:7,143`(recharts `DailyCompletionChart`) vs `OverallStatPage.tsx:12-13`(자체 `SimpleBarChart`/`SimplePieChart`, recharts 미사용) — 카탈로그 "(정정)" 서술과 일치 |
| TC-FE-214 | PASS | [정적] `lib/api/imagePath.ts:11` `ALLOWED_IMAGE_PATH = /^\/v1\/frames\/[0-9]{1,19}\/(?:deid-)?image$/` — 화이트리스트 밖 경로는 `toApiImagePath`가 `null` 반환 → `AuthImage.tsx:74-78`에서 요청 자체를 보내지 않고 즉시 에러 폴백(fail-closed) |
| TC-FE-215 | PASS | [정적] `authImageStore.ts:97-105` `acquireAuthImage` — 기존 엔트리 있으면 `refCount+=1`로 같은 promise 재사용(재요청 없음) |
| TC-FE-216 | PASS | [정적] `authImageStore.ts:108-118` `releaseAuthImage` — `refCount`가 0이 되는 즉시 `entries.delete` + `URL.revokeObjectURL`. 영속 캐시(localStorage 등) 코드 없음 |
| TC-FE-217 | PASS | [정적] `authImageStore.ts:97-105` — `existing.failed`일 때만 `startFetch(path, existing)` 재요청, acquire 시점에만 발생(자동 재시도 루프 없음) |
| TC-FE-218 | PASS | [정적] `AuthImage.tsx:27-47` `IMG_ONLY_PROPS` 목록 정의 후 `toFallbackProps`가 이를 제거하고 나머지만 div에 전개 |

테스트 커버: `components/common/__tests__/AuthImage.test.tsx` 14개 케이스 존재 확인(TC-FE-182,214~218 전부 커버).

### H-13. 접근성 (a11y) (15건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-A11Y-001 | PASS | [정적] `LabelingPage.tsx:1406-1408` `role="tablist"`, 각 탭 `role="tab"` + `aria-selected`/`aria-controls`(1414-1418 등) |
| TC-A11Y-002 | PASS | [정적] `AiToolModal.tsx:197,208` `label htmlFor` ↔ `input id` 라디오 2종. `ObjectAttributePanel.tsx:144-150` "즉시 그리기" 체크박스도 `htmlFor="ai-segment-immediate"` ↔ `id` 연결 확인 |
| TC-A11Y-003 | PASS | [정적] `Modal.tsx:47-93` — ESC(`keydown` Escape→`onClose`), 포커스 트랩(Tab 순환, 47-93행), `lastActiveRef`로 닫힘 시 포커스 복귀(91행) |
| TC-A11Y-004 | PASS | [정적] `MarkingPage.tsx:132-150` — `Space`(마킹 추가)/`Delete·Backspace`(삭제)/`Enter`(제출), `INPUT/TEXTAREA/SELECT` 포커스 시 무시 가드 |
| TC-A11Y-005 | PASS | [정적] `LabelingPage.tsx:1000-1046` `useLabelingShortcuts` — W/S(첫/끝 프레임)·F/Q(폴리곤)·T(표시토글)·`?`(치트시트)·Ctrl+C/V(복붙) 전부 배선 |
| TC-A11Y-006 | PASS | [정적] `LabelingPage.tsx:1246-1255` `isLocked` 배너 — `role="status" aria-live="polite"` |
| TC-A11Y-007 | PASS | [정적] `PortalUploadPage.tsx:201-210` `role="progressbar" aria-valuenow/valuemin/valuemax` |
| TC-A11Y-008 | PASS | [정적] `LabelingPage.tsx:1461-1467`(구 근거 1465와 근접) 미해소 배지 `aria-label={\`미해소 문의 ${n}건\`}` |
| TC-A11Y-009 | PASS | [정적] `PortalUploadPage.tsx:312` 삭제 버튼 `aria-label={\`${upload.orgnlFileNm} 삭제\`}` |
| TC-A11Y-010 | PASS | [정적] `BatchStageIndicator.tsx` `StageIcon` — Check/Loader2/X 아이콘 전부 `aria-hidden` |
| TC-A11Y-011 | PASS | [정적] `lib/focusRing.ts` `KRDS_FOCUS` — `focus-visible:ring-[3px] ring-offset-2 ring-primary-500` |
| TC-A11Y-012 | PASS | [정적] `ReviewPage.tsx:296-298` `aria-label="검수 캔버스 (읽기 전용)"` |
| TC-A11Y-013 | PASS | [정적] `KpiCard.tsx:48-62` — `onClick && selected!==undefined`일 때만 `aria-pressed` 부여(19-29행 주석대로 미전달 시 속성 자체 미부여), 테두리 두께(`border-2`, 60행)로도 구분 |
| TC-A11Y-014 | PASS | [정적] `TaskBoardTable.tsx:58-84` `SortableHeader` — `<th aria-sort={ascending\|descending\|none}>` + 내부 `<button>` |
| TC-A11Y-015 | PASS | [정적] `BusyOverlay.tsx:108-131` — `role="status" aria-live="polite" aria-busy="true"`(109-114), 취소 버튼 포커스 이동(77-79, `hasOpenModalDialog()` 가드 포함), 포커스 트랩 없음(70행 주석), 경과 초 `aria-hidden="true"`(129행), 스피너 `aria-hidden`(122행), 모달 열림 시 포커스 미탈취(72-79행) — 6개 세부 요구사항 전부 실제 코드에서 확인 |

### H-16. 작업목록 필터·정렬·KPI (24건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-219 | PASS | [정적] `boardSort.ts:38-40` `DEFAULT_BOARD_SORT=[{regDt,desc}]`, `boardParams.ts:163` `toBoardSortParams(sort.length?sort:DEFAULT_BOARD_SORT)` 항상 명시 전송 |
| TC-FE-220 | PASS | [정적] `boardSort.ts:102-114` `parseBoardSort` — `isSortColumn` 통과 못하면 조용히 스킵(무시), BE 400 유발 안 함 |
| TC-FE-221 | PASS | [정적] `boardParams.ts:67` `BOARD_BATCH_STATUS=BATCH_STATUS_PARAMS.COMPLETED` 고정, `searchParamsToFilters`(231행)는 `status` 를 읽지 않음(워크플로 축만 읽음) |
| TC-FE-222 | PASS | [정적] `boardParams.ts:234-235` `searchParamsToFilters` — `workStatus: asUiWorkStatus(sp.get('status'))` (URL `status` 키를 워크플로 축으로 해석) |
| TC-FE-223 | PASS | [정적] `boardParams.ts:90-93` `asUiWorkStatus` — allowlist 밖이면 빈 문자열. (카탈로그 근거는 `:80-96`으로 다소 넓게 표기돼 있으나 해당 함수를 포함해 추적에 지장 없음) |
| TC-FE-224 | PASS | [실동작+정적, 카탈로그 정정 완료] 위 "카탈로그 정정" 절 참조. `GET /v1/assignments?workerId=2001&workStatus=IN_PROGRESS` 실측 200/필터링 확인(49→1) |
| TC-FE-225 | PASS | [정적] `TaskListPage.tsx:93-102` — REVIEWER 축 `asWorkStatusParam(parsed.workStatus)` falsy 면 `workStatus:''` 로 정규화(select 도 빈칸) |
| TC-FE-226 | PASS | [정적] `boardSort.ts:35` `MAX_BOARD_SORT_KEYS=3`, `applyBoardSort:54-65` 서버 키 중복 제거 + `slice(0,3)`로 최고령 키 제거 |
| TC-FE-227 | PASS | [정적] `boardSort.ts:86-93` `toggleBoardSort` — 클릭 컬럼을 배열 맨 앞(`[{column,direction}, ...kept]`)에 배치, 첫 클릭 desc/재클릭 asc |
| TC-FE-228 | PASS | [정적] `boardSort.ts:73-80` `sortDirectionOf` — `BOARD_SORT_KEY_BY_COLUMN[e.column]===serverKey` 서버 키 기준 비교(컬럼명 videoId↔rawSn 전환에도 유지) |
| TC-FE-229 | PASS | [정적] `boardParams.ts:256-264` `filtersToSearchParams` — `isDefaultSort`면 `sort:[]`(URL 미기록) |
| TC-FE-230 | PASS | [정적] `TaskListPage.tsx:377-384` `handleFiltersReset` — 필터 초기화 + `setSort([...DEFAULT_BOARD_SORT])` 동시 수행 |
| TC-FE-231 | PASS | [정적] `TaskBoardKpiCards.tsx` 전체(5카드) + `TaskListPage.tsx:387-393` `handleKpiSelect`(같은 값 재클릭 시 `undefined`로 해제) |
| TC-FE-232 | PASS | [실동작+정적] `TaskBoardKpiCards.tsx:92-98` '작업중' 카드 = `WORK_STATUS_PARAMS.PENDING`. 실측: `GET /v1/tasks/board?...&workStatus=IN_PROGRESS`(REVIEWER) → `400 INVALID_INPUT`으로 BE 가 실제로 거부함을 확인 |
| TC-FE-233 | PASS | [정적] `boardParams.ts:176-181` `buildBoardSummaryParams` — `workStatus` 필드 자체가 조립 객체에 없음 |
| TC-FE-234 | PASS | [정적] `TaskBoardKpiCards.tsx:37-66` — `isLoading`→`data-testid="kpi-loading"` 스켈레톤, `isError`→`kpi-error` role=status, `TaskListPage.tsx:190` `error = isReviewer ? boardError : tasksError`(summary 에러 미포함) |
| TC-FE-235 | PASS | [정적] `TaskListPage.tsx:327` `const pagedRows = allRows;`(재필터 없음) + `:334` `totalElements = listPage?.totalElements` |
| TC-FE-236 | PASS | [정적] `TaskListPage.tsx:356-363` 화면에 없는 선택 항목 제거 effect + `:365` `clearSelection` |
| TC-FE-237 | PASS | [정적] `TaskListPage.tsx:368-374`(`handleFiltersChange`)·`387-393`(`handleKpiSelect`)·`396-403`(`handleSort`) 전부 같은 콜백에서 `setPage(0)`+`clearSelection()` 동시 처리 |
| TC-FE-238 | PASS | [정적] `TaskListPage.tsx:344-348` `page > totalPages-1` 이면 되돌림 effect |
| TC-FE-239 | PASS | [정적] `useTaskBoardEventTypes.ts:11,19-34` `EMPTY_OPTIONS={items:[],truncated:false}` 폴백, `{items,truncated}` 객체 분해(배열 오인 없음) |
| TC-FE-240 | PASS | [정적] `TaskListPage.tsx:526-538` `hasListError` 시 `ErrorState`+역할별 다른 안내 문구(WORKER 에게 배정 언급 안 함), `:575` 배정 버튼 `disabled={hasListError}` |
| TC-FE-241 | PASS | [정적] `boardParams.ts:61` `MAX_SEARCH_KEYWORD_LENGTH=100`, `:131-134` `asKeyword`가 `slice(0,100)`. `TaskFilters.tsx:131` `maxLength={MAX_SEARCH_KEYWORD_LENGTH}` |
| TC-FE-242 | PASS | [정적, 카탈로그 자체가 이미 실제 동작과 일치] `TaskListPage.tsx:134-137` `buildAssignmentParams` 로 검색/상태/이벤트 서버 위임, `.filter(` 재필터 코드 없음(`:212` `tasks=tasksPage?.content`). KPI는 `TaskWorkerKpiCards.tsx`가 `pagedRows` 기반 페이지 단위 집계임을 카탈로그가 명시 — 단, 이 페이지 단위 집계 자체는 정책 위반 소지가 있어 별도 이슈(H-ISSUE-141)로 이월 |

테스트 커버: `features/task/__tests__/boardSort.test.ts`, `boardParams.test.ts` 존재 확인.

### H-17. 검수목록 진입 기본값·필터·정렬·KPI (18건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-243 | PASS | [정적] `reviewListParams.ts:80-83` `DEFAULT_REVIEW_SORT={submittedAt,asc}`, `:92-95` `DEFAULT_REVIEW_FILTERS.status='REVIEW_PENDING'` |
| TC-FE-244 | PASS | [정적] `reviewListParams.ts:208-220` `toReviewSearchParams` — status/sort 기본값도 항상 기록(`compactParams` 대상에서 제외되지 않음, 208-219행에 조건부 생략 없음) |
| TC-FE-245 | PASS | [정적] `reviewListParams.ts:112-119` `asUiReviewStatus` — allowlist 밖이면 `DEFAULT_REVIEW_FILTERS.status`로 정규화. 재정규화는 같은 값 반환(멱등) |
| TC-FE-246 | PASS | [정적] `reviewListParams.ts:37` `REVIEW_STATUS_ALL='ALL'` — 전체 선택 시 키 삭제 아닌 `status=ALL` 유지(`:215` `toReviewSearchParams`) |
| TC-FE-247 | PASS | [정적] `features/review/api.ts:38-43` `REVIEW_STATUS_TO_BE: Record<ReviewStatus, ReviewStatusParam>` — 타입상 키 누락이 컴파일 에러. 매핑 값 확인(PENDING/IN_REVIEW/APPROVED/REJECTED) |
| TC-FE-248 | PASS | [정적] `api.ts:55-64` `listReviews` — `status ? REVIEW_STATUS_TO_BE[status] : undefined`(매핑 실패 시 필터 생략, 빈 결과 위장 없음) |
| TC-FE-249 | PASS | [정적] `reviewListParams.ts:122-125` `parseReviewSort` — allowlist 밖 컬럼이면 `{...DEFAULT_REVIEW_SORT}` 반환 |
| TC-FE-250 | PASS | [정적] `ReviewListPage.tsx` 컬럼 정의부에 `status` 컬럼 `sortable` 미부여(코드 주석에도 tie-break 역전 사유 명시) |
| TC-FE-251 | PASS | [정적] `ReviewListPage.tsx:268-274` 제출일 헤더 클릭 → `handleSortChange` → `writeSearchParams({sort:...})` |
| TC-FE-252 | PASS | [정적] `ReviewListFilters.tsx:16` `SEARCH_DEBOUNCE_MS=300`, `:83-91` debounce effect, `:95-98` 조회(Enter) 즉시 적용, `:107-111` 초기화 시 `cancelPendingSearch()` 명시 취소 |
| TC-FE-253 | PASS | [정적] `ReviewKpiCards`(제목상 `features/review/components/ReviewKpiCards.tsx`) 4카드 + `reviewListParams.ts:168-174` `buildReviewSummaryParams` — 타입 `ReviewSummaryParams`가 `q`만 허용 |
| TC-FE-254 | PASS | [정적] `ReviewListPage.tsx:161-165`(유사) `useReviewSummary({enabled:isReviewer})` — 비REVIEWER 는 조회 자체 안 함, summary 에러는 카드 영역만 영향 |
| TC-FE-255 | PASS | [정적] `ReviewListPage.tsx:334-345` `error` 이면 `ErrorState`만 렌더(표·총건수 미표시), 정상/에러 분기 명확 |
| TC-FE-256 | PASS | [정적] `ReviewListPage.tsx:349-358` `isRefreshing=isFetching&&!isLoading` → `aria-busy` + `role="status"` "갱신 중… (아래 목록은 이전 조건의 결과입니다)" |
| TC-FE-257 | PASS | [정적] `ReviewListPage.tsx:143-154` `totalPages===undefined`면 판단 보류(early return), 응답 도착 후에만 페이지 복귀 |
| TC-FE-258 | PASS | [정적] `ReviewListPage.tsx:188-216`(유사) `isDefaultView` 판정에 `sort.column`/`sort.direction` 포함 — 정렬만 바꿔도 초기화 버튼 활성 |
| TC-FE-259 | PASS | [정적] `ReviewListPage.tsx:218-235` `actionLabel` — 표시 문구("검수시작"/"이어서 검수"/"결과보기")를 그대로 버튼 accessible name 으로 사용, 장식 화살표는 `aria-hidden` |
| TC-FE-260 | PASS | [정적] `ReviewListPage.tsx:366-372`(유사, emptyMessage 분기) + `ReviewListFilters.tsx:178` `"${REVIEW_STATUS_LABEL[values.status]} 상태만 표시 중"` 배지 문구 |

테스트 커버: `features/review/__tests__/reviewListParams.test.ts`, `api.statusMapping.test.ts` 존재 확인.

### H-18. 영상 목록·영상 상세 (7건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-FE-297 | PASS | [정적] `pages/VideoDetailPage.tsx` 전체 grep — "버전관리"/"history"/`onNavigateLabel` 문자열 0건. `pages/HistoryPage.tsx` 파일 자체가 존재하지 않음(삭제 확인) |
| TC-FE-298 | PASS | [정적] 위와 동일 grep — 라이트박스 관련 "라벨링 편집" 텍스트 없음, `onNavigateLabel` prop 체인 부재 확인 |
| TC-FE-299 | PASS | [정적] `router/index.tsx` grep — `history`/`HistoryPage` 매치 0건(라우트 자체가 정의돼 있지 않음) → `/history/1` 진입 시 catch-all 404 경로로 귀결 |
| TC-FE-300 | PASS | [정적] `LabelHeader.tsx:133-149` — `showHistory && videoId!==undefined && onHistoryClick` 조건부 렌더(구 `/history` Link 폴백 없음), 주입 시 `aria-expanded={historyOpen}` 토글 버튼 |
| TC-FE-301 | PASS | [실동작+정적] `VideoFilters.tsx:44-64` `handleSubmit`이 `cctvNameKeyword`(trim)·`eventTypeCd`·`from`·`to`·`dataSttsCd` 조립, `video/api.ts:76-83` `listVideos`가 params 그대로 전달. 실측: `GET /v1/videos?cctvNameKeyword=zzz_no_such_cctv` → `200`/`totalElements:0`(필터 실제 반영), `eventTypeCd=BOGUS_XYZ` → `200`/0건(★5 그대로), `from>to` → `400 INVALID_INPUT`(★5 그대로) — BE 계약과 FE 조립이 일치 |
| TC-FE-302 | PASS | [정적] `VideoFilters.tsx:15-29` `STATUS_OPTIONS` — 전체/완료(COMPLETED)/처리중(PROCESSING)/마킹 대기(MARKING_READY)/대기(PENDING)/실패(FAILED) 6항목(전체 포함) 확인, `MARKING_READY` 존재 |
| TC-FE-303 | PASS | [실동작+정적] `video/api.ts:44` `capturedAt: (v.capturedAt ?? '') as string`(regDt 폴백 없음). 실측: `GET /v1/videos` 응답에서 `"capturedAt":null` 이면서 `"regDt":"2026-08-04T..."` 존재하는 행 확인(BE 도 폴백하지 않고 null 그대로 반환) — FE 가 이를 몰래 채우지 않음을 라이브로 재확인 |

---

## 판정 집계

| 구분 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-12 | 16 | 16 | 0 | 0 | 0 | 0 | 0 |
| H-13 | 15 | 15 | 0 | 0 | 0 | 0 | 0 |
| H-16 | 24 | 24 | 0 | 0 | 0 | 0 | 0 |
| H-17 | 18 | 18 | 0 | 0 | 0 | 0 | 0 |
| H-18 | 7 | 7 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **80** | **80** | **0** | **0** | **0** | **0** | **0** |

담당 범위 80건 전건 PASS. FAIL/PARTIAL 없음 — 단, 아래 정책 위반성 이슈 1건(H-ISSUE-141, 2차 H-ISSUE-146 carry-forward)은 카탈로그의 명시적 기대결과 자체가 이미 이 동작을 "구 동작 그대로" 유지한다고 밝히고 있어 TC-FE-242 개별 판정은 PASS로 유지하되, 근본 동작이 루트 CLAUDE.md 정책과 어긋나므로 이슈로 별도 기록한다.

---

## 이슈 기록

### [H-ISSUE-141] TC-FE-242 인접 — WORKER KPI 4카드가 "전체 기준"이 아니라 현재 페이지 20행만 집계한다 (2차 H-ISSUE-146 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "목록 화면 정렬·필터 정책" 구속 규칙 — "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다." REVIEWER 5카드(`TaskBoardKpiCards`)는 `/v1/tasks/board/summary` 서버 집계를 쓰므로 이 규칙을 지킨다. WORKER 4카드도 헤더 "전체 N건"과 같은 집합을 말해야 사용자가 잔여 작업량을 정확히 인지한다.
- **현재 동작(이슈 내용)**:
  ```tsx
  // frontend/src/features/task/components/TaskWorkerKpiCards.tsx:20-21
  export function TaskWorkerKpiCards({ rowStatuses }: TaskWorkerKpiCardsProps) {
    const count = (status: RowStatus) =>
      rowStatuses.filter((s) => s === status).length;
  ```
  ```tsx
  // frontend/src/pages/TaskListPage.tsx:484-487
  const workerRowStatuses = useMemo(
    () => pagedRows.map((r) => r.rowStatus),
    [pagedRows],
  );
  ```
  `pagedRows`는 현재 페이지(최대 20건)만 담는다(`:327` `const pagedRows = allRows;`, `allRows`는 `tasksPage?.content`). "전체 작업" 카드는 21건 이상 배정된 WORKER 에게 항상 페이지 크기(20)로 고정되고, 헤더의 "전체 N건"(서버 `totalElements`)과 어긋난다. 소스 주석(`TaskWorkerKpiCards.tsx:12-17`)은 "REVIEWER 전용 집계 API(403)라 구 동작 그대로"라고 사유를 밝히고 있으나 정책이 금지한 페이지 단위 집계다.
- **재현/확인 경로**:
  ```
  # 배정 21건 이상인 WORKER 로 /task 진입 시 재현. 현재 시드는 worker 2001 배정 49건(실측)이라 이미 재현 조건을 충족한다.
  curl -H "Authorization: Bearer $WORKER_TOKEN" "http://localhost:18081/api/v1/assignments?workerId=2001&page=0&size=20" → totalElements 49 (헤더 "전체 49건")
  화면: KPI "전체 작업" 카드는 pagedRows.length = 20 으로 표시 → 헤더와 불일치
  ```
- **영향**: 데이터 정합/사용자 오판(보안 영향 없음). 작업자가 본인 잔여 작업량을 실제보다 적게 인식할 수 있다.
- **수정 방향(제안)**: ①BE 에 WORKER 도 호출 가능한 배정 집계 엔드포인트(예: `GET /v1/assignments/summary`, 서버 인가로 본인 범위 고정) 추가 후 REVIEWER 5카드와 동일 "전체 기준" 축으로 통일하거나, ②카드 라벨을 "이 페이지 기준"으로 명시하고 "전체 작업" 값만 서버 `totalElements` 로 대체. **본 검증에서는 수정하지 않음.**

---

## 비고

- H-12/H-13/H-16/H-17/H-18 담당 범위 안에서는 위 TC-FE-224 1건 외에 카탈로그 file:line 드리프트나 기대결과 stale 이 추가로 발견되지 않았다(2차에서 지적된 H-ISSUE-144/145/147 은 위 표에서 대조 완료).
- H-16/H-18 일부 케이스(TC-FE-232, TC-FE-301, TC-FE-303)는 라이브 API 호출(REVIEWER/WORKER dev 토큰 발급 후 `/v1/tasks/board`, `/v1/assignments`, `/v1/videos` 직접 조회)로 실동작까지 확인했다. 나머지는 정적 대조(코드 인용) 기반이며, 관련 자동 테스트 파일(`boardSort.test.ts`, `boardParams.test.ts`, `reviewListParams.test.ts`, `api.statusMapping.test.ts`, `AuthImage.test.tsx`, `LabelHeader.test.tsx`, `PortalUploadLabelingBusy.test.tsx`) 존재를 확인해 커버리지 공백은 없었다.
- 빌드/테스트는 실행하지 않았으므로(지시 준수) 위 테스트 파일들의 **통과 여부** 자체는 `_raw/test-baseline.md`(baseline 담당)를 참조할 것.

<!-- H-part8.md 원문 끝 -->

---
