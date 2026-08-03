# A 클러스터 part2 — A-2 인가(32건) · A-5 권한 자가부여(21건)

- **담당 구간**: `docs/test-cases/A-auth-common.md` → `## A-2. 인가 (SecurityConfig · 채널 격리 · 화이트리스트)` + `## A-5. 권한 자가부여 (RoleClaimService · RoleClaimRateLimiter)`
- **총 53건** (A-2 32 = TC-AUTHZ 27 + TC-CORS 3 + TC-ROLE 2 / A-5 21 = TC-CLAIM 21). 폐기 케이스 없음.
- **검증 일자**: 2026-08-01 / 코드 기준 워크트리 `qa-0801`(56d30478) / 실행 스택 `klid-*` 컨테이너(구버전 V146)
- **환경 버전 격차 영향 판정**: 담당 구간은 `LS_DATA_INGEST` 적재 리팩터·증강 폐기/복구(b2b44f0e~56d30478)와 **무관**하다. `SecurityConfig` / `RoleClaimService` / `RoleClaimRateLimiter` / `StreamSignatureFilter` 는 해당 커밋 범위에서 변경되지 않았고, 실행 중 컨테이너의 응답이 워크트리 소스와 전부 일치했다(매처 순서·에러코드·rate limit 임계·버킷 UTC 모두 실측 일치). → **BLOCKED 없음, 전건 정상 검증**.

## 실동작 검증 기반 (요약)

| 항목 | 내용 |
|---|---|
| 진입점 | `http://localhost:18081/api` (context-path `/api`) |
| 토큰 | `JWT_SECRET`(컨테이너 실효값)로 직접 HS256 서명 발급. **역할은 JWT `role` 클레임이 아니라 `LS_USER_ROLE` 조회로 결정**(`JwtAuthenticationFilter:80-86`)이라 sub 로 역할을 고른다 |
| 사용 sub | 1001=REVIEWER · 2001/2002=WORKER · 3001=PORTAL_USER · 999001/770001~/888001(LS 미등록)=role=null |
| 채널 | `channel` 클레임 (`INTERNAL`/`PORTAL`), 미지정=INTERNAL 기본값 |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system` (스키마 `public`) |
| 특이 환경값 | `ADMIN_CLAIM_PASSWORD_HASH=`(공란) → role-claim 의 **성공 경로**는 fail-closed 로 항상 401. A-5 7건은 이 때문에 정적+단위테스트 근거 (→ A-ISSUE-23) |
| 자동테스트 baseline | backend 4,755 / 실패 0 / skip 5 — 이 구간 관련 테스트 전부 통과 |

---

## A-2. 인가 (SecurityConfig · 채널 격리 · 화이트리스트) — 32건

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AUTHZ-001 | PASS | [실동작] | `POST /v1/manage/labels` (WORKER) → **403** `{"errorCode":"FORBIDDEN"}`. GET 전용 매처(`SecurityConfig:126`) 뒤 `/v1/manage/**` hasRole(REVIEWER)(`:127`)로 낙하 확인 |
| TC-AUTHZ-002 | PASS | [실동작] | `GET /v1/manage/labels` → WORKER **200** · PORTAL(채널 PORTAL) **200** · role=null **200**. `authenticated()` 로 채널·역할 무검사임을 3조합으로 실증 |
| TC-AUTHZ-003 | PASS | [실동작] | `GET /v1/system/scheduler/health` → WORKER **403** / REVIEWER **200**. (`/v1/system/configs` 는 미존재 경로라 REVIEWER 404 — 매처는 핸들러 해석 전에 적용됨을 함께 확인) |
| TC-AUTHZ-004 | PASS | [실동작]+[정적] | `GET /actuator/metrics` → WORKER **403** / REVIEWER **200** / 익명 **401**. prd 는 `application-prd.yml:92-96` `exposure.include: health` 로 노출 자체 차단 |
| TC-AUTHZ-005 | PASS | [실동작] | 익명 `GET /actuator/health` **200** `{"status":"UP","groups":[...]}` (details 미노출) · `/actuator/info` **200** `{}` · `/health` **200**. 공통 `show-details: when-authorized`(`application.yml:137`), prd `never`(`application-prd.yml:98-100`) |
| TC-AUTHZ-006 | PASS | [실동작] | `/v1/integration/foo` → REVIEWER **403** / 익명 **401**. denyAll(`SecurityConfig:120`) |
| TC-AUTHZ-007 | PASS | [실동작] | `/v1/export-api/foo` → REVIEWER **403** / 익명 **401**. denyAll(`:121`) |
| TC-AUTHZ-008 | PASS | [실동작] | PORTAL 채널+PORTAL_USER → `GET /v1/portal/uploads` **200**. `allOf(ROLE_PORTAL_USER, CHANNEL_PORTAL)`(`:134-135`) AND 결합 |
| TC-AUTHZ-009 | PASS | [실동작] | INTERNAL REVIEWER/WORKER → `/v1/portal/uploads`·`/v1/portal/videos` **403** |
| TC-AUTHZ-010 | PASS | [실동작] | **전 GET 경로 스윕**(openapi 74경로 중 비-portal 62경로 + 문서외 미디어 6경로). PORTAL 토큰이 401/403 이 아닌 경로 = `/health`(permitAll) · `/v1/me` · `/v1/manage/labels{,/detect-candidates,/1/attrs}` **뿐** — 카탈로그의 "의도된 예외 2곳"과 정확히 일치, 그 외 이탈 0건 |
| TC-AUTHZ-011 | PASS | [실동작] | `GET /v1/notices` → PORTAL_USER **403** / WORKER **200** / REVIEWER **200** / role=null **403**. `/v1/**` 매처보다 앞 배치(`:132`) 확인 |
| TC-AUTHZ-012 | PASS | [실동작] | local(`authoring.dev.login.enabled=true`, `application-local.yml:103-104`)에서 **익명** `POST /v1/dev/tokens` → **201**. 발급 토큰(sub=1001)으로 `/actuator/metrics` **200** = REVIEWER 급 획득 → permitAll 매처가 REVIEWER 가드보다 앞에 있음을 실증 |
| TC-AUTHZ-013 | PASS | [정적] | 3중 차단 코드 확인: ①`SecurityConfig:52-53,103-113` 조건부 permitAll 매처 ②`SecurityConfig:116` `/v1/dev/**` hasRole(REVIEWER) ③`DevTokenController:34`·`DevTokenService:41` `@ConditionalOnProperty(authoring.dev.login.enabled, havingValue="true")` (matchIfMissing 없음=기본 미등록). 기본값 `application.yml:453` `${DEV_LOGIN_ENABLED:false}`. prd 오버라이드 없음 + `DevProfileGuard:42,56` 가 ENV=stg/prd 에서 dev 프로파일 기동 거부. **live 미실행 사유: 이 스택은 enabled=true(local)** |
| TC-AUTHZ-014 | PASS | [실동작] | `POST /v1/dev/autolabel-test`·`/v1/dev/batch/run` → WORKER **403** / 익명 **401** |
| TC-AUTHZ-015 | PASS | [실동작] | 익명 `POST /v1/auth/role-claim` → **401**. 매처 순서 반증 6종(`/`, `%2f`, 대소문자, `/./`, `//`, `/x/../`) 전부 **401/404/400** — permitAll(`/v1/auth/**`)로 흘려보내는 우회 없음 |
| TC-AUTHZ-016 | PASS | [실동작] | 익명 `GET /v1/auth/foo`·`/v1/portal/auth/foo` → **404**(보안 통과 후 핸들러 부재) = permitAll 성립. `/v1/portal/**` 채널 매처보다 앞 |
| TC-AUTHZ-017 | PASS | [실동작]+[정적] | 익명 `GET /swagger-ui/index.html` **200**, `/v3/api-docs` **200**. prd 는 `application-prd.yml:86-90` `springdoc.api-docs.enabled=false`·`swagger-ui.enabled=false` → 404 |
| TC-AUTHZ-018 | PASS | [실동작] | 익명 보호리소스 → `HTTP 401` + `{"success":false,"data":null,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}`. 스택트레이스·내부경로 미포함 |
| TC-AUTHZ-019 | PASS | [실동작] | 역할부족 → `HTTP 403` + `errorCode:"FORBIDDEN"`. 동일하게 내부정보 미포함 |
| TC-AUTHZ-020 | PASS | [실동작] | 인증/미인증/에러 응답 전부 `Set-Cookie: JSESSIONID` **미발급**(응답헤더 grep 0건). `SecurityConfig:60` STATELESS |
| TC-AUTHZ-021 | PASS | [실동작]+[정적] | HTTP 요청 응답에 `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY` **항상** 부착(익명 401 응답 포함). `Strict-Transport-Security` 는 HTTP 요청에 **부재** — 카탈로그 기대(HTTPS 한정, A-ISSUE-06)와 일치. HTTPS 부착은 `SecurityConfigTest#hstsHeaderPresentOnSecureRequest`(baseline 통과)가 커버. ⚠ 운영 edge HSTS 미부여 잔여는 **A-ISSUE-06 이월**(신규 ID 미부여) |
| TC-AUTHZ-022 | PASS | [실동작] | CSRF 토큰 없이 `POST /v1/dev/tokens` **201**·`POST /v1/manage/labels` 403(CSRF 아닌 authz) → CSRF 필터 부재. 401 응답에 `WWW-Authenticate` 헤더 없음 → httpBasic 비활성. formLogin 리다이렉트 없음(JSON 401) |
| TC-AUTHZ-023 | PASS | [실동작] | WORKER `GET /v1/manage/labels` **200** — GET 매처(`:126`)가 REVIEWER 매처(`:127`)보다 먼저 평가됨(순서 역전 회귀 없음) |
| TC-AUTHZ-024 | PASS | [실동작] | role=null INTERNAL(sub=999001) → `/v1/videos` **403** · `/v1/event-types` **403** · `/v1/videos/1` **403** · `/v1/reviews` **403** · `/v1/tasks/board` **403**. 추가로 **전 GET 경로 스윕**에서 403/401 이 아닌 것은 `/health`·`/v1/me`·`/v1/manage/labels/**` 3종뿐 = fail-open 잔존 0건 |
| TC-AUTHZ-025 | PASS | [실동작] | role=null INTERNAL `GET /v1/me` **200** `{"userId":"999001","role":null,"channel":"INTERNAL"}` · PORTAL role=null **200** `{"role":"PORTAL_USER","channel":"PORTAL"}`. 응답이 **본인 토큰 클레임 반향뿐**(업무데이터 0)임을 원문으로 확인 |
| TC-AUTHZ-026 | PASS | [실동작] | `GET /v1/videos/4/stream-url`(WORKER) 로 서명 URL + `klid_stream_nonce` HttpOnly 쿠키 획득 → **Authorization 헤더 없이** 서명+쿠키만으로 `GET /v1/videos/4/stream` **206 video/mp4**. 반증 4종 전부 **401**: ①쿠키 없이 URL 만(replay) ②동일 서명으로 `/v1/videos`·`/v1/event-types`·`/v1/videos/4`·`/v1/videos/4/stream-url`·`/v1/frames/1/labels` 접근(권한 확대) ③rawSn 4→7 로 재사용 ④`u=2001→1001` 변조. 영상 단위 인가도 확인(WORKER 2001 → 미배정 rawSn 9 `stream-url` **404**) |
| TC-AUTHZ-027 | PASS | [실동작]+[정적] | 익명 `POST /v1/vlm/callback`·`/v1/genai/callback` → **411**(무서명 가드의 Content-Length 요구까지 도달 = permitAll 성립). 구 경로 `POST /v1/aug/callback`·`/v1/deidentify/result` → **401**(permitAll 대상 아님). `WebhookProtectedPaths` 에도 구 경로가 남아있지 않음(주석: "수신처 없는 경로를 보호 목록에 두면 죽은 보안 설정") |
| TC-CORS-001 | PASS | [실동작]+[정적] | 미허용 `Origin: http://evil.com` → preflight **403**, 단순요청도(유효 REVIEWER 토큰 포함) **403** + `Access-Control-Allow-Origin` 헤더 **부재**. `allowed-origins` 공란 분기(`SecurityConfig:185-187` `setAllowedOrigins(List.of())`)는 [정적] — 이 스택은 `CORS_ALLOWED_ORIGINS` 가 설정돼 있어 공란 상태를 live 재현할 수 없음 |
| TC-CORS-002 | PASS | [정적]+[실동작] | `SecurityConfig:180-184` `split(",")→trim()→filter(!isEmpty)`. 실동작으로 콤마 다중 origin 이 개별 허용됨을 확인(`http://localhost:13000`·`http://127.0.0.1:5174` 모두 ACAO 반향). 공백 포함 입력의 trim 자체는 정적 |
| TC-CORS-003 | PASS | [실동작] | preflight 응답 실측: `Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`(6종) · `Allow-Headers: Authorization, X-Trace-Id`(요청분 반향, 코드 allowlist 8종 `:189-193`) · `Expose-Headers: X-Trace-Id, Upload-Offset, Upload-Length, Tus-Resumable, Tus-Version, Tus-Extension, Tus-Max-Size, Location`(8종) · `Allow-Credentials: true`. `*` 미사용 |
| TC-ROLE-001 | PASS | [정적]+[실동작] | `RoleHierarchy.java:10-13` `RoleHierarchyImpl.fromHierarchy("")` — 상속 0건. `grep -rn "RoleHierarchy\|authoringRoleHierarchy" src` → 정의 파일 외 **참조 0건**. 실동작으로도 상속 없음 확인(REVIEWER 가 WORKER 권한을 상속하지 않고, WORKER 는 REVIEWER 경로 전건 403). UNCERTAINTIES #6 미해소 상태 그대로 |
| TC-ROLE-002 | PASS | [실동작]+[정적] | `channel:"ADMIN"` 토큰 → **401**(`JwtAuthenticationFilter:73` `Channel.valueOf` → IllegalArgumentException → `:112` catch → 컨텍스트 클리어). `channel` 누락 → INTERNAL 기본값으로 200. enum 정의는 `Role.java:3-7`(3역할)·`Channel.java:3-6`(2채널) |

**A-2 집계**: PASS 32 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0

### A-2 추가 반증(카탈로그 외) — 전부 차단 확인
매처 우회 시도 20종을 `--path-as-is` 로 실행해 **우회 0건**:
- 대소문자(`/v1/SYSTEM/configs`→404) · 퍼센트 인코딩(`/v1/%73ystem/configs`→403, `/actuator/%6Detrics`→403 = **디코딩 후 매칭이 정상 적용**)
- 이중 슬래시(`/v1/system//configs`→401) · path parameter(`;jsessionid=x`→401) · trailing slash(→403)
- 상대경로 traversal(`/v1/manage/labels/../videos`, `/actuator/health/../metrics`, `/v1/auth/../videos`, `/v1/portal/../videos`, `/v1/vlm/callback/../videos` → 전부 **401**) · 인코딩 traversal(`..%2f` → **400**, StrictHttpFirewall)
- **문서 외 경로**(`/v1/videos/{n}/stream`, `/v1/frames/{n}/image`, `/v1/frames/{n}/deid-image`, `/v1/videos/{n}/frames/{m}/image`, `/v1/portal/frames/{n}/image`) 도 PORTAL/role=null/익명 조합에서 전부 403/401
- `/v1` 밖 노출 경로는 openapi 130경로 중 `/health` **하나뿐**(익명 200, 의도된 permitAll)

---

## A-5. 권한 자가부여 (RoleClaimService · RoleClaimRateLimiter) — 21건

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-CLAIM-001 | PASS | [정적] | `RoleClaimService:105-193` 성공 경로(사용자 조회 → `lsUserRoleRepository.upsertRole` → evict → 새 토큰). **live 미실행 사유: 이 스택의 `ADMIN_CLAIM_PASSWORD_HASH` 가 공란**이라 pw 게이트(`:137`)에서 항상 401 (→ A-ISSUE-23). 단위테스트 `RoleClaimServiceTest#claimWorkerSuccess`(baseline 통과)가 커버. 부수 단언 "REVIEWER 는 자가부여 불가"는 TC-CLAIM-015 로 **live 실증** |
| TC-CLAIM-002 | PASS | [실동작] | `{"role":"PORTAL_USER"}` → **400** `{"errorCode":"INVALID_INPUT","message":"PORTAL_USER 역할은 본 API 로 부여할 수 없습니다."}` (`:111-114`) |
| TC-CLAIM-003 | PASS | [실동작] | WORKER(2001)·REVIEWER(1001) 토큰 → 각 **409** `{"errorCode":"CONFLICT"}` (`:127-129`) |
| TC-CLAIM-004 | PASS | [실동작] | PORTAL 채널 토큰(3001, 그리고 LS 미등록 999002) → **409 CONFLICT**. PORTAL 은 `JwtAuthenticationFilter:85` 가 role 을 PORTAL_USER 로 고정하므로 채널·역할 두 조건 모두 걸림(이중). SecurityConfig 는 role-claim 을 `authenticated()`(`:71`)로만 잡으므로 서비스단이 유일 방어선인 것도 코드로 확인 |
| TC-CLAIM-005 | PASS | [정적] | `:137-144` `BCryptPasswordEncoder.matches`(상수시간) → 불일치 시 UNAUTHORIZED. live 는 공란 해시라 006 과 응답이 구분되지 않아 정적. 단위테스트 `RoleClaimServiceTest#wrongPasswordReturns401` 커버 |
| TC-CLAIM-006 | PASS | [실동작] | 실효 `ADMIN_CLAIM_PASSWORD_HASH=`(공란) 상태에서 role=null INTERNAL + 유효 role(WORKER) → **401** `"관리자 패스워드가 일치하지 않습니다."` = `:87-97` 공란 유지 + `:137` `adminPasswordHash.isEmpty()` 명시 차단이 실제로 fail-closed 동작 |
| TC-CLAIM-007 | PASS | [정적] | `:92-95` 비-BCrypt 시 `IllegalStateException` 생성자 throw, `:101-103` prefix `$2a$/$2b$/$2y$` 검사. 단위테스트 `#rejectNonBcryptHashAtBoot` 커버. (기동 거부는 live 재현 시 컨테이너 재기동이 필요해 미시도 — 검증 규칙상 설정 변경 금지) |
| TC-CLAIM-008 | PASS | [실동작] | 동일 sub(888001) 로 WORKER 시도 7회 → `401 401 401 401 401 **429 429**`. 6회째부터 `TOO_MANY_REQUESTS` 이며 응답이 UNAUTHORIZED 가 아니라 429 → **pw 검증 전 차단** 실증(`:133` 이 `:137` 앞). 다른 계정(888002)은 영향 없음(401 401). 검사 순서도 실동작으로 확인: role 화이트리스트(403, 쿼터 미소모) → 채널/역할(409) → rate limit(429) → pw(401) |
| TC-CLAIM-009 | PASS | [정적] | `:159-162` LS 매핑 존재 시 409(이중 게이트). live 로는 `:127` 앞선 게이트가 먼저 걸려 도달 불가(캐시 stale 상황 재현 필요). 단위테스트 `#alreadyHasLsRoleReturns409` 커버 |
| TC-CLAIM-010 | PASS | [정적] | `:153-156` NOT_FOUND. 단위테스트 `#missingUserReturns404` 커버. live 는 pw 게이트가 앞서 도달 불가 |
| TC-CLAIM-011 | PASS | [정적] | `:167-179` `TransactionSynchronizationManager.isSynchronizationActive()` 분기 — 활성이면 `afterCommit()` evict, 아니면 즉시 evict 폴백. 단위테스트 `#claimSuccessEvictsCache` 커버 |
| TC-CLAIM-012 | PASS | [정적] | `:195-208` `Jwts.SIG.HS256` · `issuer` · `claim("channel","INTERNAL")` · exp=now+3600(`:61`). issuer 기본값 `application.yml:169` `${JWT_ISSUER:klid-auth}`, allowlist `JwtIssuerValidator:15` 기본 `klid,klid-portal,klid-auth` ∋ `klid-auth`(컨테이너 실효 `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal`). 단위테스트 `#issuedTokenHasOneHourTtl` 커버 |
| TC-CLAIM-013 | PASS | [실동작] | **로그 인젝션 실주입 반증**: sub 에 `661\r\nWARN forged-log-line role=REVIEWER granted\tTAB-...`(64자↑) 를 넣은 서명 토큰으로 호출 → 컨테이너 로그 실측 결과 **단일 라인**으로 `denied userNo=661__WARN forged-log-line role=R role=WORKER reason=invalid_password` — `\r\n`→`__` 치환 + **정확히 32자 절단**(`:211-218`) 확인, 위조 로그 라인 생성 실패. `adminPassword` 는 로그 어디에도 미출력(전 로그 grep 0건) |
| TC-CLAIM-014 | PASS | [정적] | `:146-151` pw 검증 **이후** `Long.parseLong` → `INVALID_INPUT`. live 는 공란 해시로 pw 단계에서 401 로 끝나 도달 불가(= 정보노출 순서상 안전하다는 단언 자체는 오히려 실증됨: 비숫자 sub 도 400 이 아니라 401 을 받음). 단위테스트 `#nonNumericSubjectReturns400` 커버 |
| TC-CLAIM-015 | PASS | [실동작] | role=null INTERNAL + REVIEWER 요청 → **403** `"해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요."` (`:115-123`, `:227-229` `allowedClaimRoles()=List.of(WORKER)`). **쿼터 미소모 실증**: 같은 sub 로 REVIEWER 10회(전부 403) 후 WORKER 시도 → `401×5, 429` 로 정상 5회 쿼터가 온전히 남아 있음 |
| TC-CLAIM-016 | PASS | [실동작] | 무권한 계정 11개(770001~770011)를 번갈아 5회씩 호출 → 누적 **51회째부터 429**(정확히 임계 50 초과 지점). 직후 **완전 신규 계정의 첫 시도**(계정축=1)도 **429** → 전역 축 발동 확정. 컨테이너 로그 `rate-limited axis=GLOBAL attempts=51 limit=50` · DB `LS_AUTHRT_GRANT_ATMPT(GLOBAL/GLOBAL)=56` 일치 (`RoleClaimRateLimiter:81-82,108-109`) |
| TC-CLAIM-017 | PASS | [정적]+[실동작] | `:116-127` `Math.max(local, shared)`, `JdbcRoleClaimAttemptStore:79-84` DataAccessException 시 UNAVAILABLE(-1) 반환·예외 미승격. 공유 축이 실제로 기록됨은 DB 실측으로 확인(ACCOUNT/GLOBAL 행 적재). UNAVAILABLE 분기는 단위테스트 `RoleClaimRateLimiterTest#localCounterKeepsEnforcingWhenStoreUnavailable`·`#sharedStoreAggregatesAcrossNodes` 커버 |
| TC-CLAIM-018 | PASS | [실동작] | **강한 실증**: 컨테이너 JVM 은 `-Duser.timezone=Asia/Seoul`(실효 env)이고 호출 시각 벽시계는 18:54 인데, DB `LS_AUTHRT_GRANT_ATMPT.BGNG_DT` 는 **`2026-08-01 09:54:00`(=UTC)** 로 적재됨 → `LocalDateTime.now(ZoneOffset.UTC).truncatedTo(MINUTES)`(`:105-106`)가 JVM TZ 와 무관하게 UTC 고정임을 노드 TZ 불일치 시나리오 그대로 재현. 같은 순간 요청이 동일 PK 행을 갱신(count 누적)하는 것도 확인 |
| TC-CLAIM-019 | PASS | [정적] | `:60-64` `LOCAL_RETENTION=10분` · `MAX_LOCAL_ENTRIES=10,000`, `:91-95` Caffeine `expireAfterWrite`+`maximumSize`. 단위테스트 `#localCountersExpireByTtl`(가상 Ticker) 커버. 공유행 TTL 도 DB 실측 일치(`EXPD_DT` = 기록시각+10분) |
| TC-CLAIM-020 | PASS | [정적] | `:113` `String cacheKey = seCd + '\0' + idntfr + '\0' + windowStart` — NUL 구분자로 축·식별자·창 분리. 런타임 동작은 리터럴 NUL 시절과 동일(문자값 U+0000 불변). 실동작으로도 축 간 간섭 없음 확인(ACCOUNT 초과가 GLOBAL 카운터와 독립적으로 집계됨) |
| TC-CLAIM-021 | PASS | [실동작] | 기동 로그 `[RoleClaim] attempt purge job scheduled intervalMs=600000`(10분) + 실제 발화 로그 `[role-claim-attempt-purge] ... attempt rows purged count=65`, `count=2` → 전용 데몬 스레드명·주기·삭제 동작 모두 실증. 초기 지연 5분(`:49` 기본 300000)·예외 승격 없음(`:84-97` `catch (Throwable)`) 은 정적 |

**A-5 집계**: PASS 21 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0

---

## 총 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| A-2 인가 | 32 | 32 | 0 | 0 | 0 | 0 | 0 |
| A-5 권한 자가부여 | 21 | 21 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **53** | **53** | **0** | **0** | **0** | **0** | **0** |

**근거 드리프트**: 이 구간 53건의 `근거(file:line)` 는 **전건 실제 코드 위치와 일치**(±2줄 이내). 드리프트 0건.

---

# 이슈 블록

> ⚠ 이 구간은 FAIL/PARTIAL/확인필요가 0건이다. 아래 3건은 **케이스 판정은 PASS 이지만 적극 반증 과정에서 드러난 잔여 리스크·환경 이슈**로, 다음 회차 수정 사이클의 판단 재료로 기록한다(카탈로그 기대결과 자체는 충족됨).

### [A-ISSUE-21] TC-CLAIM-016 — role-claim 전역 축(50회/분)이 단일 계정에 의해 소진 가능 → 전 사용자 온보딩 차단(DoS)
- **심각도**: MEDIUM *(케이스 판정은 PASS — 기대결과인 "교차계정 증폭 차단"은 정확히 동작함. 그 설계의 반대편 실패 모드를 기록)*
- **기대 동작(기대효과)**: 전역 축은 무권한 계정 A/B/C 를 번갈아 쓰는 무차별 대입 증폭을 막기 위한 것이다. 동시에 **정상 신규 사용자의 온보딩(권한 자가부여)은 가용해야** 한다 — role-claim 은 role=null 사용자가 시스템에 진입하는 유일한 경로이므로(`SecurityConfig:71,84`, TC-AUTHZ-024/025), 이 경로가 막히면 신규 사용자는 어떤 업무 API 에도 접근할 수 없다.
- **현재 동작(이슈 내용)**: 전역 축은 **엔드포인트 단일 카운터**(`RoleClaimRateLimiter.java:70` `GLOBAL_IDNTFR = "GLOBAL"`)이고, 카운터는 **거부된 시도에도 증가**한다 — `consumeAxis` 는 증가 후 판정한다:
  ```java
  // RoleClaimRateLimiter.java:112-122
  private void consumeAxis(String seCd, String idntfr, LocalDateTime windowStart, int limit) {
      String cacheKey = seCd + '\0' + idntfr + '\0' + windowStart;
      int local = localCounters.get(cacheKey, k -> new AtomicInteger()).incrementAndGet();
      int shared = sharedStore == null ? RoleClaimAttemptStore.UNAVAILABLE
                                       : sharedStore.recordAttempt(seCd, idntfr, windowStart, LOCAL_RETENTION);
      int effective = Math.max(local, shared);
      if (effective > limit) { ... throw TOO_MANY_REQUESTS; }
  ```
  따라서 유효 INTERNAL 토큰 + role=null 을 가진 **계정 하나**가 매 분 51회 이상 호출하면 그 분의 전역 축이 계속 초과 상태로 유지되어 **다른 모든 사용자의 role-claim 이 429** 로 거절된다. 실측: 신규 계정의 **첫 시도**(계정축=1)가 429 를 받았고, 로그는 `rate-limited axis=GLOBAL attempts=56 limit=50` 으로 임계 초과 후에도 카운터가 계속 상승했다. 부수적으로 거부 요청마다 `REQUIRES_NEW` 트랜잭션 + 단일 행(`GLOBAL/GLOBAL/버킷`) UPSERT 가 발생해(`JdbcRoleClaimAttemptStore.java:69,75-79`) 고빈도 공격 시 동일 행 락 경합·커넥션 소모가 증폭된다.
  - 참고: 클래스 javadoc(`:41-43`)은 IP 축을 두지 않은 이유로 "한 명의 실패가 전원을 잠근다(가용성 사고)"를 들고 있는데, 전역 축은 **그 실패 모드를 그대로 재현**한다.
- **재현/확인 경로**:
  ```bash
  # role=null INTERNAL 토큰 1개로 1분 내 51회 호출 (pw 는 틀려도 무방 — rate limit 이 pw 앞)
  for i in $(seq 1 51); do curl -s -o /dev/null -w "%{http_code} " -X POST \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T_NOROLE" \
    -d '{"role":"WORKER","adminPassword":"x1234"}' http://localhost:18081/api/v1/auth/role-claim; done
  # 이후 전혀 다른 신규 계정의 첫 시도 → 429
  ```
  ```sql
  select atmpt_se_cd, atmpt_idntfr, bgng_dt, atmpt_nmtm from ls_authrt_grant_atmpt
   where atmpt_se_cd='GLOBAL' order by bgng_dt desc limit 3;   -- 임계 초과 후에도 계속 증가
  ```
- **영향**: 가용성 — 신규/역할미배정 사용자의 온보딩 전면 차단(CWE-770 Allocation of Resources Without Limits, OWASP API4:2023 Unrestricted Resource Consumption). 인증된 저권한 계정 1개만 있으면 성립하므로 내부자·탈취 토큰 시나리오에서 현실적이다. 보안 강도(무차별 대입 억제)는 손상되지 않는다.
- **수정 방향(제안)**: (구현하지 않음) ① 임계 초과가 확정된 축은 카운터를 더 올리지 않고 즉시 거절(초과 상태 고착·DB 쓰기 증폭 동시 완화) ② 전역 축을 "차단"이 아니라 "경보 + 지수 백오프/짧은 쿨다운"으로 낮추고, 실제 차단은 계정 축 + (신뢰 프록시 파싱 전제 하) 네트워크 축이 담당 ③ 전역 축 임계를 소진시킨 계정을 식별해 그 계정만 장기 차단(`ATMPT_IDNTFR` 별 상위 소비자 집계) ④ 최소한 전역 429 응답에 `Retry-After` 를 실어 정상 사용자가 재시도 시점을 알 수 있게 함.

### [A-ISSUE-22] TC-AUTHZ-002 / TC-AUTHZ-010 / TC-AUTHZ-024 — `/v1/manage/labels/**` GET 예외가 PORTAL 채널·역할미배정에게 내부 라벨 마스터·COCO 검출 매핑·속성 정의를 노출
- **심각도**: LOW *(케이스 판정은 PASS — 카탈로그가 "의도된 예외"로 명시하고 실동작이 그와 일치. 예외의 정당화 근거가 `/v1/me` 대비 약하다는 점을 기록)*
- **기대 동작(기대효과)**: 채널 격리(R5-1)는 외부 채널(PORTAL) 토큰이 내부 업무 데이터에 도달하지 못하게 하고, A-ISSUE-02 수정은 역할 미배정(role=null) INTERNAL 사용자가 업무 조회 API 를 전건 통과하던 fail-open 을 닫는 것이었다. 예외는 **업무 데이터가 없는 경로**로 한정되어야 한다 — `SecurityConfig:79-83` 이 `/v1/me` 예외를 정당화한 근거가 정확히 그것이다("응답이 호출자 본인 토큰의 클레임 반향뿐이라 채널을 넘나드는 업무 데이터가 없다").
- **현재 동작(이슈 내용)**: `/v1/manage/labels` 와 그 하위 전체가 GET 에 한해 `authenticated()` 다.
  ```java
  // SecurityConfig.java:126
  .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  ```
  전 GET 경로 스윕 실측 결과, PORTAL 채널 토큰과 role=null INTERNAL 토큰이 403 을 받지 않는 경로는 `/health`·`/v1/me` 외에 아래 3개다(모두 200 + 실데이터 반환):
  - `GET /v1/manage/labels` — 라벨 마스터 전량(`labelId/name/color/type/dtctTypeCd/useYn`)
  - `GET /v1/manage/labels/detect-candidates` — **AI 검출 후보(COCO 클래스 매핑)**. 포털은 ADR-013 상 오토라벨링 미제공이라 이 데이터가 필요 없다
  - `GET /v1/manage/labels/{labelId}/attrs` — 라벨 속성 정의(`attrId/name/inputType/...`)
  이는 `/v1/me` 와 달리 **호출자 본인 클레임이 아니라 내부 설정/업무 메타데이터**이며, 역할 미배정 계정(=아직 온보딩되지 않은, 권한 심사를 통과하지 않은 계정)도 동일하게 열람한다.
- **재현/확인 경로**:
  ```bash
  # PORTAL 채널 토큰 / role=null INTERNAL 토큰 각각
  curl -s -H "Authorization: Bearer $T_PORTAL"  http://localhost:18081/api/v1/manage/labels/detect-candidates
  curl -s -H "Authorization: Bearer $T_NOROLE"  http://localhost:18081/api/v1/manage/labels/1/attrs
  # → 둘 다 200 + 실데이터
  ```
- **영향**: 정보 노출(CWE-200) · 불완전한 채널 격리(CWE-863 부분). 유출 데이터는 개인정보가 아니라 라벨 taxonomy·AI 검출 클래스 매핑·속성 스키마이므로 직접 피해는 낮으나, ①외부 채널 사용자에게 내부 AI 파이프라인 구성을 드러내고 ②A-ISSUE-02 가 닫은 role=null fail-open 의 잔여 표면이다.
- **수정 방향(제안)**: (구현하지 않음) 예외를 **실제 필요 범위로 축소**한다 — ①포털 수동 라벨링에 필요한 것은 라벨 마스터 목록(+속성 정의)뿐이므로 `detect-candidates` 는 `/v1/manage/**` REVIEWER 가드로 되돌리거나 별도 매처로 `hasAnyRole(REVIEWER, WORKER)` 를 건다 ②`/v1/manage/labels/**` 와일드카드 대신 실제 필요한 GET 서브리소스만 명시 매처로 나열(신규 GET 서브리소스가 자동으로 예외에 편입되는 구조 제거) ③role=null 은 온보딩 전 상태이므로 이 예외에서 제외(채널 조건만 완화)하는 안도 검토. 어느 쪽이든 `SecurityConfig:72-83` 의 `/v1/me` 예외 주석처럼 **"여기에 업무 데이터를 추가하면 근거가 무효"** 라는 불변식을 이 매처에도 명시.

### [A-ISSUE-23] TC-CLAIM-001/005/009/010/011/012/014 — 검증 스택의 `ADMIN_CLAIM_PASSWORD_HASH` 공란으로 role-claim 성공 경로를 실동작 검증할 수 없음(환경 이슈)
- **심각도**: LOW *(코드 결함 아님 — 검증 환경 구성 이슈. 다음 회차 환경 준비 항목으로 기록)*
- **기대 동작(기대효과)**: 검증 대전제(VERIFY-PROMPT §1)는 "실제로 그렇게 동작한다"를 판정하는 것이다. 권한 자가부여의 **성공 경로**(WORKER 부여 → LS_USER_ROLE upsert → 캐시 evict → 새 토큰 발급)는 수직 권한 상승과 직결된 구간이므로 실동작으로 확인되어야 한다.
- **현재 동작(이슈 내용)**: 실행 중 backend 컨테이너의 실효 환경변수가 `ADMIN_CLAIM_PASSWORD_HASH=`(빈 문자열)이다.
  ```
  $ docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH
  (빈 줄)
  ```
  Spring 은 `${ADMIN_CLAIM_PASSWORD_HASH:$2y$12$...}`(`application-local.yml:35`)에서 **환경변수가 존재하되 빈 값**이면 기본값을 쓰지 않고 `""` 로 해석하므로, `RoleClaimService:87-90` 이 해시를 `""` 로 유지하고 `:137` 이 모든 요청을 401 로 종결한다. 결과적으로 pw 게이트 **이후** 로직(사용자 조회 404 · LS 이중 게이트 409 · sub 파싱 400 · upsert · evict · 토큰 발급)에 **어떤 요청도 도달하지 못한다**.
  - 이 자체는 설계된 fail-closed 동작이며(TC-CLAIM-006 은 이 덕분에 live PASS), 결함이 아니다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH   # → 빈 값
  curl -s -X POST -H "Content-Type: application/json" -H "Authorization: Bearer $T_NOROLE" \
    -d '{"role":"WORKER","adminPassword":"admin1234"}' http://localhost:18081/api/v1/auth/role-claim
  # → 401 "관리자 패스워드가 일치하지 않습니다." (어떤 평문으로도 동일)
  ```
- **영향**: 검증 커버리지 — A-5 21건 중 7건이 실동작이 아닌 정적+단위테스트 근거에 머문다(해당 단위테스트는 baseline 전량 통과). 운영 관점으로는 이 설정이 그대로 배포되면 role-claim 온보딩 자체가 불가하므로 dev/stg/prd 배포 체크리스트에서 **공란 여부를 기동 시 경고**하는 것이 바람직하다(현재는 조용히 항상 401).
- **수정 방향(제안)**: (구현하지 않음) ①다음 회차 스택 기동 시 `docker-compose.local.yml` 에 로컬 전용 BCrypt 해시(cost 12)를 주입해 role-claim 성공 경로를 live 검증 대상에 포함 ②코드 측 개선안으로는 `RoleClaimService` 생성자에서 공란 감지 시 **기동 WARN 로그**(예: `DevToggleStartupWarner` 동형)를 남겨 "설정 누락으로 온보딩이 항상 실패 중"임을 운영자가 인지하게 함 — fail-closed 는 유지하되 무음(silent) 상태를 없앤다.

---

## 이월 (신규 ID 미부여)

- **A-ISSUE-06(HSTS)** — TC-AUTHZ-021 의 기대결과에 이미 편입된 잔여 이슈. 이번 실측에서도 HTTP 요청 응답에 `Strict-Transport-Security` 미부착을 확인했고(HTTPS 는 `SecurityConfigTest` 통과), 카탈로그가 지적한 "운영 edge(nginx/Caddy)에서도 HSTS 미부여" 상태는 본 구간에서 확인/해소할 수 없다(로컬 스택에 TLS 종단 없음). **미해소 상태로 이월**.
- **UNCERTAINTIES #6(RoleHierarchy 무의미 빈)** — TC-ROLE-001 로 재확인. `fromHierarchy("")` + 참조 0건 = 사실상 no-op 이며 상속 부재가 실동작으로도 확인됨. 미해소 상태 유지(제거 여부는 정책 결정 사항).
