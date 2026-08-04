# A 클러스터 part2 — A-2 인가(32) + A-5 권한 자가부여(21) = 53건

- **회차**: 2026-08-03 3차 · **담당 범위**: `docs/test-cases/A-auth-common.md` 55~94행(A-2) + 165~190행(A-5)
- **검증 방식**: 실동작 우선. 풀스택(`_raw/stack-bringup.md` 기준, backend `localhost:18081`, context-path `/api`) 위에서 직접 요청.
- **토큰 조달**: dev 엔드포인트가 아니라 **컨테이너 실효 `JWT_SECRET` 으로 직접 HS256 서명**해 발급(`sub`/`channel`/`iss` 를 자유롭게 조합해야 반증이 가능하므로). 실계정 = 1001 REVIEWER · 2001/2002 WORKER · 3001 PORTAL_USER, 무권한 = 9001~9165·9999(LS_USER_ROLE 미등록).
- **판정 집계**: 총 53 — **PASS 46 · FAIL 0 · PARTIAL 0 · BLOCKED 7 · N/A 0 · 확인필요 0**
- **이슈 ID**: `A-ISSUE-21` ~ `A-ISSUE-27` (⚠ **3차 part2 전용 번호**로, 2026-08-01 1차의 동명 ID 와는 별개다. 각 블록에 1차 원본 ID 를 명시했다.)
- ⚠ **빌드/테스트 미실행**(지시). 3차 `_raw/test-baseline.md` 가 아직 없어 자동테스트 **통과 여부는 미대조**이며, 아래 "테스트 커버" 는 `파일:라인` 존재 확인까지다.

---

## 1. A-2. 인가 (SecurityConfig · 채널 격리 · 화이트리스트) — 32건

| ID | 판정 | 근거 확인 |
|----|:--:|-----------|
| TC-AUTHZ-001 | PASS | [실동작] `POST /v1/manage/labels`(WORKER) → **403** `{"errorCode":"FORBIDDEN"}`. GET 전용 매처 뒤 `/v1/manage/**` hasRole(REVIEWER) 낙하 확인(`SecurityConfig.java:126-127`). 테스트: `SecurityConfigRoleTest.java:41` |
| TC-AUTHZ-002 | PASS | [실동작] `GET /v1/manage/labels` → WORKER/PORTAL/role=null **전부 200 + 라벨 마스터 실데이터**. `SecurityConfig.java:126` `.authenticated()` 그대로 (→ A-ISSUE-23 노출 이슈 별도 기록) |
| TC-AUTHZ-003 | PASS | [실동작] `GET /v1/system/configs`(WORKER) → **403** / (REVIEWER) → 404(핸들러 부재 = 인가는 통과). `GET /v1/system/scheduler/health`(WORKER) → **403**. 근거 `SecurityConfig.java:128` |
| TC-AUTHZ-004 | PASS | [실동작] `GET /actuator/metrics` — WORKER **403** / REVIEWER **200**(메트릭 목록) / 익명 **401**. `/actuator/beans`·`/actuator/env`·`/actuator/prometheus` 익명 **401**. prd `include: health` 는 [정적] `application-prd.yml:93-100` |
| TC-AUTHZ-005 | PASS | [실동작] 익명 `GET /actuator/health` **200** `{"status":"UP","groups":[...]}` (details 없음) / REVIEWER 는 **components 상세 노출** → `show-details: when-authorized`(`application.yml:138-140`) 실동작 일치. `/actuator/info` 200, `/health` 200, `/actuator/health/liveness|readiness` 200 |
| TC-AUTHZ-006 | PASS | [실동작] `/v1/integration/x` — REVIEWER **403** / 익명 **401**. bare prefix `/v1/integration` 도 403. `SecurityConfig.java:120` |
| TC-AUTHZ-007 | PASS | [실동작] `/v1/export-api/x`·`/v1/export-api` — REVIEWER **403**. `SecurityConfig.java:121` |
| TC-AUTHZ-008 | PASS | [실동작] PORTAL 토큰 `GET /v1/portal/datamart/videos` → **200**(21건). `allOf(ROLE_PORTAL_USER, CHANNEL_PORTAL)` (`:134-135`). 테스트 `SecurityConfigChannelTest.java:58` |
| TC-AUTHZ-009 | PASS | [실동작] INTERNAL REVIEWER 토큰 `GET /v1/portal/datamart/videos` → **403**. 테스트 `SecurityConfigChannelTest.java:68` |
| TC-AUTHZ-010 | PASS | [실동작] PORTAL 토큰: `/v1/videos` 403 · `/v1/notices` 403 · `/v1/notices/1` 403 · `/v1/manage/configs`·`/presets`·`/manage/health` 403. **의도된 예외 2곳 실증** — `/v1/manage/labels(/**)` GET 200, `/v1/me` 200. `sub` 를 내부 userNo(1001)로 위장한 PORTAL 토큰도 role=PORTAL_USER 고정 → `/v1/videos` 403 (수직상승 불가) |
| TC-AUTHZ-011 | PASS | [실동작] `GET /v1/notices` — PORTAL_USER **403** / WORKER **200**. `SecurityConfig.java:132` |
| TC-AUTHZ-012 | PASS | [실동작] 익명 `POST /v1/dev/tokens` → **400**(바디 검증) = permitAll 매처 도달. local 프로파일 `authoring.dev.login.enabled=true`(`application-local.yml:104-108`). 프로파일별 실측: local `true` · dev `${DEV_LOGIN_ENABLED:true}` · **stg `false`** · prd 미정의(공통 기본 false) → 카탈로그 "local/dev 만 활성" 정확 |
| TC-AUTHZ-013 | PASS | [정적+실동작] 3중 차단 중 2층을 실동작 확인 — 익명 `POST /v1/dev/batch/run` **401**, WORKER **403**(`/v1/dev/**` hasRole(REVIEWER), `:116`). 3층째 `@ConditionalOnProperty(prefix="authoring.dev.login", name="enabled", havingValue="true")` = `DevTokenController.java:34` [정적]. enabled=false 분기는 local 프로파일이라 live 불가 → 정적 |
| TC-AUTHZ-014 | PASS | [실동작] WORKER `POST /v1/dev/autolabel-test` → **403**, `POST /v1/dev/batch/run` → **403** |
| TC-AUTHZ-015 | PASS | [실동작] 익명 `POST /v1/auth/role-claim` → **401**(permitAll `/v1/auth/**` 보다 먼저 매칭). `SecurityConfig.java:71,90` |
| TC-AUTHZ-016 | PASS | [실동작] 익명 `GET /v1/auth/anything`·`/v1/portal/auth/anything` → **404**(=Security 통과 후 MVC 미매핑). 401 아님 |
| TC-AUTHZ-017 | PASS | [실동작] 익명 `GET /swagger-ui/index.html` 200, `GET /v3/api-docs` 200(134 paths). prd 404 는 [정적] `application-prd.yml:86-90` |
| TC-AUTHZ-018 | PASS | [실동작] 익명 `/v1/videos` → 401 `{"success":false,"data":null,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — 스택/내부경로/서버헤더 없음. 응답에 `Server`·`X-Powered-By` 미부착 확인 |
| TC-AUTHZ-019 | PASS | [실동작] WORKER `/v1/system/configs` → 403 `{"...","errorCode":"FORBIDDEN"}` |
| TC-AUTHZ-020 | PASS | [실동작] 인증/미인증 응답 모두 `Set-Cookie: JSESSIONID` **0건**(grep -c = 0). (stream nonce 쿠키는 세션 아님) |
| TC-AUTHZ-021 | PASS | [실동작] HTTP 응답에 `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY` **항상** 부착(200/401/403 전부 확인). `Strict-Transport-Security` **미부착**(HTTP 요청이므로 기대결과와 일치). 기대결과에 이미 명시된 잔여 이슈는 A-ISSUE-25 로 이월. 테스트 `SecurityConfigTest.java:48`(HTTPS 분기) |
| TC-AUTHZ-022 | PASS | [실동작] CSRF 토큰 없이 `POST /v1/notices`(REVIEWER) → 400(검증) = CSRF 필터 부재. `GET /login` → 401(로그인 폼 없음), Basic 자격증명 무시(`-u` → 401), 응답에 `WWW-Authenticate` 없음 |
| TC-AUTHZ-023 | PASS | [실동작] WORKER `GET /v1/manage/labels` **200** — GET 매처가 REVIEWER 매처보다 앞(순서 역전 없음). ⚠ HEAD 는 GET 매처에 안 걸려 `/v1/manage/**` REVIEWER 로 낙하 → WORKER/PORTAL **403**(더 엄격, 결함 아님) |
| TC-AUTHZ-024 | PASS | [실동작] role=null INTERNAL(sub=9999): `/v1/videos` **403** · `/v1/event-types` **403** · `/v1/videos/901` **403**. 구 fail-open 재현 안 됨. 테스트 `InternalRoleGateMatcherTest.java:63` |
| TC-AUTHZ-025 | PASS | [실동작] role=null INTERNAL `GET /v1/me` → **200** `{"userId":"9999","role":null,"channel":"INTERNAL"}` — 본인 클레임 반향뿐, 업무 데이터 없음. PORTAL 채널도 200 |
| TC-AUTHZ-026 | PASS | [실동작] REVIEWER 로 `GET /v1/videos/906/stream-url` → 서명 URL + `Set-Cookie: klid_stream_nonce=...; HttpOnly; SameSite=Lax; Path=/api/v1/videos; Max-Age=3600`. **Authorization 헤더 없이** 쿠키+서명만으로 `GET .../stream` → **206**. 쿠키 없으면 401 |
| TC-AUTHZ-027 | PASS | [실동작] 익명 `POST /v1/vlm/callback`·`/v1/genai/callback` → **400**(바디 검증 = permitAll 도달) / `POST /v1/aug/callback`·`/v1/deidentify/result` → **401**(permitAll 대상 아님, 매처 제거 확인). 근거 `WebhookProtectedPaths.java:50,57` 정확 |
| TC-CORS-001 | PASS | [실동작] `Origin: http://evil.com` — preflight **403**, 단순 GET 도 **403** 이며 `Access-Control-Allow-Origin` 헤더 **미부착**(origin 반향 없음). 빈 설정(`List.of()`) 분기는 [정적] `SecurityConfig.java:186` |
| TC-CORS-002 | PASS | [정적+실동작] 실효 `CORS_ALLOWED_ORIGINS` 8개 콤마 목록이 그대로 허용됨(`http://localhost:13000` preflight 200). trim 로직은 [정적] `:181-184` |
| TC-CORS-003 | PASS | [실동작] preflight 응답: `Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`(6종) · `Allow-Headers` 요청분 그대로 반향(허용목록 8종 정적 확인) · `Expose-Headers` **8종**(X-Trace-Id, Upload-Offset, Upload-Length, Tus-Resumable, Tus-Version, Tus-Extension, Tus-Max-Size, Location) · `Allow-Credentials: true`, `*` 미사용 |
| TC-ROLE-001 | PASS | [정적+실동작] `RoleHierarchy.java:10-13` `fromHierarchy("")` 유지. **참조 코드 0건**(`grep -rn RoleHierarchy backend/src` → 정의 파일 외 매칭 없음). 실동작으로도 상속 부재 확인(REVIEWER→WORKER 하향 상속 없음: WORKER 는 REVIEWER 경로 전건 403). UNCERTAINTIES #6 **미해소 유지** → A-ISSUE-26 |
| TC-ROLE-002 | PASS | [실동작+정적] `Role`=REVIEWER/WORKER/PORTAL_USER 3값(`Role.java:3-7`), `Channel`=INTERNAL/PORTAL 2값(`Channel.java:3-6`). `channel:"EXTERNAL"` 토큰 → `/v1/me`·`/v1/videos` **401**(valueOf 예외 → 컨텍스트 미설정), `role:"ADMIN"` role-claim 요청 → **400** `INVALID_INPUT` |

### A-2 반증(우회) 시도 결과 — 전부 차단

| 우회 벡터 | 시도 | 결과 |
|---|---|---|
| 대소문자 | `/v1/System/configs`(WORKER) · `/v1/Integration/x`(REVIEWER) · `/v1/Portal/...`(INTERNAL) · `/V1/videos`(role=null) | 전부 **404**. Security 매처가 케이스 센시티브라 `/v1/**` 로 낙하하지만 **MVC 라우팅도 케이스 센시티브라 핸들러 미존재** → 실제 도달 0. 우회 불가 |
| trailing slash | `/v1/system/configs/`(WORKER) · `/v1/videos/`(role=null) | **403** 유지 |
| 퍼센트 인코딩 | `/v1/%73ystem/configs`(WORKER) | **403** (정규화 후 매칭) |
| 경로 순회 `..` | `/v1/manage/labels/../system/configs` · `/v1/manage/labels/../../videos` · `/actuator/health/../metrics` · `/v1/portal/../portal/...` · `/api/v1/x/../actuator/health` | 전부 **401** — 컨테이너/Security firewall 단계에서 거부되어 어떤 매처도 통과하지 못함(fail-closed). REVIEWER 정상 토큰으로도 401 |
| 세미콜론 path param | `/v1/system;a=b/configs` · `/v1/videos;a=1` | **401** (동일) |
| 중복 슬래시 | `//v1/system/configs` · `/v1//system/configs` · `/v1//portal/...` | **401** (동일) |
| bare prefix | `/v1/integration`·`/v1/export-api`·`/v1/dev`·`/v1/system`·`/v1/manage`·`/v1/portal`·`/actuator` | 전부 게이트 적용(403/401) — `/**` 매처가 0-세그먼트도 커버 |
| 매처 사각지대 | OpenAPI 134 경로 스윕 → `/v1` 밖 경로는 `/health` 1개뿐(permitAll 의도) | 사각 없음 |
| 서명 스트림 권한 확대 | 발급된 `exp/u/sig` 쿼리를 `/v1/videos`·`/v1/manage/labels`·`/v1/system/configs`·`/v1/videos/906/frames` 에 재부착(+nonce 쿠키) | 전부 **401** — `StreamSignatureFilter` 가 스트림 경로에만 컨텍스트를 세팅하고 ROLE_* 도 부여하지 않음(`:133-138`) |
| 클레임 위조 | INTERNAL 토큰에 `role:"REVIEWER"` 주입(실제 LS=WORKER) | `/v1/me` role=WORKER, `/v1/system/**` **403** — JWT role 클레임 미참조 확인 |
| 수직 상승(업무 API) | WORKER 로 `POST /v1/assignments`(정상 바디) · `PATCH /v1/assignments/1` · `POST /v1/reviews/906/approve|reject` · `GET /v1/users(/{no})` · `PATCH /v1/users/1001` · `POST /v1/control-ingests/requeue` · `GET /v1/stats/overall` · `GET /v1/tasks/board` | 전부 **403** |
| 수평(IDOR) | WORKER(2001) → `GET /v1/assignments/{67,59,54,73}/history`(타 사용자 배정) | **403** "본인 배정 이력만 조회할 수 있습니다" |
| 목록 스코프 | WORKER `GET /v1/assignments?size=50` · `GET /v1/stats/worker?userNo=2002` | 각각 본인(workerId=2001)만 반환 / 파라미터 무시하고 본인 통계 반환 — 누수 없음 |
| 채널 위장 | `channel` 클레임 **제거** + `iss=klid-portal` + `sub=2001(WORKER)` | **CHANNEL_INTERNAL 승격 → `/v1/videos` 200** ← 유일하게 통과한 벡터. **A-ISSUE-24** 참조(1차 A-ISSUE-02 미해소 이월) |

---

## 2. A-5. 권한 자가부여 (RoleClaimService · RoleClaimRateLimiter) — 21건

> ⚠ **실행 스택의 `ADMIN_CLAIM_PASSWORD_HASH` 가 여전히 빈 문자열**(`docker exec klid-backend printenv` 확인)이라 pw 게이트 이후 로직에 **어떤 요청도 도달하지 못한다**. 1차(2026-08-01) A-ISSUE-23 의 환경 준비 권고가 반영되지 않았다 → 7건 BLOCKED, **A-ISSUE-21** 로 이월.

| ID | 판정 | 근거 확인 |
|----|:--:|-----------|
| TC-CLAIM-001 | BLOCKED | [실동작] pw 게이트(`RoleClaimService.java:137`)가 빈 해시로 전건 401 → upsert/토큰발급 도달 불가. 사유·해소책 A-ISSUE-21. 단위테스트 `RoleClaimServiceTest.java:107` 존재(통과여부 미대조) |
| TC-CLAIM-002 | PASS | [실동작] role=PORTAL_USER + 유효길이 pw → **400** `"PORTAL_USER 역할은 본 API 로 부여할 수 없습니다."`(`:111-113`). ⚠ pw 가 4자 미만이면 `@Size(4,100)` 바디검증이 먼저 400 을 내 이 분기에 도달하지 않는다(관측 주의) |
| TC-CLAIM-003 | PASS | [실동작] WORKER(2001)·REVIEWER(1001) 토큰 → **409** `"이미 권한이 부여된 사용자입니다."`(`:127-128`) |
| TC-CLAIM-004 | PASS | [실동작] PORTAL 채널 토큰(3001) → **409**(deny-by-default). SecurityConfig 는 `authenticated()` 뿐(`:71`)이므로 서비스단이 유일 방어선임도 확인(익명은 401) |
| TC-CLAIM-005 | BLOCKED | [실동작] 틀린 pw → 401 이 나오긴 하나, 빈 해시라 `adminPasswordHash.isEmpty()` 단락 평가에서 종결되어 **`BCryptPasswordEncoder.matches` 상수시간 경로 자체에 도달하지 않는다**. A-ISSUE-21 |
| TC-CLAIM-006 | PASS | [실동작] 임의 pw(`admin1234`)·로컬 yml 기본해시에 대응하는 평문 후보 모두 **401** `"관리자 패스워드가 일치하지 않습니다."` — 빈 해시 fail-closed 실증(`:137`) |
| TC-CLAIM-007 | PASS | [정적] `isBcryptHash`(`:101-103`) prefix `$2a$/$2b$/$2y$` 검사 + 불일치 시 `IllegalStateException`(`:92-94`). 기동 거부는 설정 변경이 필요해 live 불가. 단위테스트 `RoleClaimServiceTest.java:319` |
| TC-CLAIM-008 | PASS | [실동작] 동일 sub(9003) 연속 호출 → **1~5회 401 / 6·7회 429**. 검사 순서도 실증 — REVIEWER 요청(403)·409 요청은 `ls_authrt_grant_atmpt` 에 ACCOUNT 행을 만들지 않음 = 화이트리스트·채널게이트가 rate limit **앞**(`:118→:127→:133`) |
| TC-CLAIM-009 | BLOCKED | [실동작] pw 게이트 이전 종결. `:160-161` 이중 게이트 미도달. A-ISSUE-21 |
| TC-CLAIM-010 | BLOCKED | 〃 (`:153-155` NOT_FOUND 미도달) |
| TC-CLAIM-011 | BLOCKED | 〃 (`:168-180` AFTER_COMMIT evict 미도달) |
| TC-CLAIM-012 | BLOCKED | 〃 (`:61` TTL 3600s, `:195-208` 토큰 발급 미도달) |
| TC-CLAIM-013 | PASS | [실동작] 실패 로그 원문 `[RoleClaim] denied userNo=9149 role=WORKER reason=invalid_password` — **adminPassword 평문 미출력**, sub 32자 절단·`[\r\n\t]`→`_`(`:119-120,140-141,211-218`). rate-limit 로그도 식별자 미노출 |
| TC-CLAIM-014 | BLOCKED | [실동작] sub="abc" 요청이 400 이 아니라 **401**(pw 게이트가 앞). 기대한 `:147-151` 분기 미도달. A-ISSUE-21 |
| TC-CLAIM-015 | PASS | [실동작] role=REVIEWER + 유효 pw → **403** `"해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요."`(`:118-123`, `allowedClaimRoles()` `:227-229`). **쿼터 미소모 실증** — 해당 sub(9002)에 ACCOUNT 시도행 0건. **1차 A-ISSUE-17(CWE-269 공유 정적 패스워드 REVIEWER 자가부여) 해소 확인** |
| TC-CLAIM-016 | PASS | [실동작] 서로 다른 60개 sub 로 1분 내 호출 → **50회까지 401, 51회째부터 429**. `ls_authrt_grant_atmpt` GLOBAL 버킷 `atmpt_nmtm=61`. 교차계정 증폭 차단됨(`RoleClaimRateLimiter.java:81-82,108-109`) |
| TC-CLAIM-017 | PASS | [정적] `Math.max(local, shared)`(`:116-127`) + `JdbcRoleClaimAttemptStore.java:80-84` 가 `DataAccessException` → `UNAVAILABLE(-1)` 반환. DB 장애 주입은 스택 파괴라 미수행. 단위테스트 `RoleClaimRateLimiterTest.java:95` |
| TC-CLAIM-018 | PASS | [실동작] **UTC 고정 실증** — 요청 시각 KST 2026-08-04 00:08/00:09 인데 `ls_authrt_grant_atmpt.bgng_dt` 는 `2026-08-03 15:08/15:09`(UTC, 분 절단). `RoleClaimRateLimiter.java:105-106` |
| TC-CLAIM-019 | PASS | [정적] Caffeine `expireAfterWrite(10분)` + `maximumSize(10_000)`(`:60-64,91-95`). 단위테스트 `RoleClaimRateLimiterTest.java:152`(가상 Ticker) |
| TC-CLAIM-020 | PASS | [정적] `seCd + '\0' + idntfr + '\0' + windowStart`(`:113`) — 축·식별자·창 3분할, 런타임 동작 불변 |
| TC-CLAIM-021 | PASS | [실동작] 기동 로그 `[RoleClaim] attempt purge job scheduled intervalMs=600000` 확인. 데몬 스레드명 `role-claim-attempt-purge`(`RoleClaimAttemptPurgeJob.java:62-68`), 하한 60s clamp·초기지연 300s(`:47-52`), `catch (Throwable)` 로 스케줄러 사망 차단(`:91`) |

---

## 3. 카탈로그 정정 (담당 라인범위 내)

| # | 위치 | 이전 | 이후 | 근거 |
|:--:|---|---|---|---|
| 1 | 71행 `TC-AUTHZ-013` 근거 | `SecurityConfig.java:52-53,103,116; application.yml:456-458` | `SecurityConfig.java:52-53,103,116; application.yml:462-464; DevTokenController.java:34` | `application.yml:456-458` 은 **주석 3줄**만 가리키고 실제 프로퍼티(`authoring.dev.login.enabled: ${DEV_LOGIN_ENABLED:false}`)는 **462~464행**이다(실측). 또한 기대결과가 말하는 "3중 차단"의 3번째 층(`@ConditionalOnProperty`) 근거가 어디에도 없어 `DevTokenController.java:34` 를 추가했다. **정책 변경 아님 — 근거 드리프트 정정** |

> 그 외 담당 범위 53건의 근거 `file:line` 은 **전수 재확인 결과 드리프트 없음**(A-2 32건 · A-5 21건). 특히 회차2에서 갱신된 `SecurityConfig.java` 라인(71/84/85-90/100-102/116/118/120/121/126-128/132/134-135/147-152/156-157/178-200/239-244), `RoleClaimService.java`(87-97/101-103/111-123/127-133/137-143/147-155/160-161/168-180/195-208/211-218/227-229), `RoleClaimRateLimiter.java`(60-64/81-82/91-95/104-128), `RoleClaimAttemptPurgeJob.java`(47-49/62-68/86-94), `StreamSignatureFilter.java:136-138`, `WebhookProtectedPaths.java:50,57`, `RoleHierarchy.java:10-13`, `Role.java:3-7`, `Channel.java:3-6`, `application.yml:138-140`, `application-prd.yml:86-90,93-100` 을 개별 확인했다.

---

## 4. 이전 회차 이슈 대조 (담당 범위)

| 1차(2026-08-01) ID | 내용 | 3차 상태 | 근거 |
|---|---|:--:|---|
| **A-ISSUE-17**(2026-07-25 1차) | 공유 정적 패스워드로 **REVIEWER 자가부여**(CWE-269/1392/778) | **해소** | `allowedClaimRoles()`(WORKER 단일)가 `claim()` **최상단**에서 강제(`:118-123`). 실동작 403. 잔여(감사 테이블·pw 회전 정책 부재)는 A-ISSUE-27 |
| **A-ISSUE-18**(2026-07-25 1차) | rate limit 이 JVM 로컬 `ConcurrentHashMap`, 교차계정·다중노드 우회, 맵 무한증가 | **해소** | 계정+전역 2축, 공유 저장소(`ls_authrt_grant_atmpt`) 합산, Caffeine TTL/maximumSize. 실동작 5/분·50/분 확인 |
| A-ISSUE-02 | `channel` 클레임 부재 시 무조건 INTERNAL 승격 (issuer↔채널 무결합) | **미해소** | 실동작 재현. → **A-ISSUE-24** |
| A-ISSUE-06 / UNCERTAINTIES #28 | HSTS 가 앱·edge 어디에서도 미부여 | **미해소** | HTTP 응답에 `Strict-Transport-Security` 없음(재확인). → **A-ISSUE-25** |
| A-ISSUE-21 | role-claim 전역 축(50/분)을 단일 계정이 소진 → 전원 온보딩 차단 | **미해소** | 60회 소진 후 신규 계정 첫 시도 429 재현. → **A-ISSUE-22** |
| A-ISSUE-22 | `/v1/manage/labels/**` GET 예외가 PORTAL·role=null 에게 라벨 마스터/COCO 매핑/속성정의 노출 | **미해소** | 3경로 전부 200 재현. → **A-ISSUE-23** |
| A-ISSUE-23 | 검증 스택 `ADMIN_CLAIM_PASSWORD_HASH` 공란 → role-claim 성공 경로 검증 불가 | **미해소** | `printenv` 빈 값 재확인. → **A-ISSUE-21** |
| UNCERTAINTIES #6 | `RoleHierarchy` 빈 계층 + 참조 0건 | **미해소** | → **A-ISSUE-26** |
| A-ISSUE-71(참고, A-6 소관) | `@Valid` 바디 검증이 `@PreAuthorize` 보다 먼저 실행 → 권한 없는 사용자에게 입력 스키마 노출 | **미해소**(범위 밖, 사실만 기록) | WORKER `POST /v1/assignments {}` → **400 + 필수필드 목록**, 정상 바디로는 403. `AssignmentController.java:67` `@PreAuthorize("hasRole('REVIEWER')")` 존재하나 검증이 선행 |

---

## 5. 이슈 상세 (§7 6요소)

### [A-ISSUE-21] TC-CLAIM-001/005/009/010/011/012/014 — 검증 스택 `ADMIN_CLAIM_PASSWORD_HASH` 공란이 3회차째 유지되어 role-claim 성공 경로가 여전히 미검증
- **심각도**: LOW *(코드 결함 아님 — 검증 환경 구성 이슈. 단 1차 권고 후 2회차가 지나도록 반영되지 않아 승격 없이 재기록)*
- **기대 동작(기대효과)**: 권한 자가부여의 성공 경로(WORKER 부여 → `LS_USER_ROLE` upsert → AFTER_COMMIT 캐시 evict → 새 INTERNAL 토큰 발급)는 **수직 권한 상승과 직결된 유일한 온보딩 경로**이므로 VERIFY-PROMPT §1(실동작 기준)에 따라 실제 요청으로 확인돼야 한다.
- **현재 동작(이슈 내용)**: 실행 중 backend 의 실효 환경변수가 빈 문자열이라 `RoleClaimService` 가 해시를 `""` 로 유지하고 모든 요청을 pw 게이트에서 종결한다.
  ```
  $ docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH
  (빈 줄)
  ```
  ```java
  // RoleClaimService.java:137-143
  if (adminPasswordHash.isEmpty() ||
          !passwordEncoder.matches(req.adminPassword(), adminPasswordHash)) {
      log.warn("[RoleClaim] denied userNo={} role={} reason=invalid_password", ...);
      throw new CustomException(ErrorCode.UNAUTHORIZED, "관리자 패스워드가 일치하지 않습니다.");
  ```
  `application-local.yml:35` 의 기본 해시는 **환경변수가 "존재하되 빈 값"이라 적용되지 않는다**(Spring 은 이를 `""` 로 해석). 결과적으로 pw 이후 5개 분기(사용자 404 · LS 이중게이트 409 · sub 파싱 400 · upsert · evict · 토큰발급)가 전부 도달 불가다. 부수적으로 **`BCryptPasswordEncoder.matches` 상수시간 비교 경로(TC-CLAIM-005)도 단락 평가로 건너뛴다**.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH   # → 빈 값
  T=<role=null INTERNAL JWT>
  curl -s -X POST -H "Content-Type: application/json" -H "Authorization: Bearer $T" \
    -d '{"role":"WORKER","adminPassword":"admin1234"}' http://localhost:18081/api/v1/auth/role-claim
  # → 401 (어떤 평문으로도 동일)
  ```
- **영향**: 검증 커버리지 — A-5 21건 중 **7건이 정적+단위테스트 근거에 머문다**. 운영 관점으로는 이 설정이 그대로 배포되면 role-claim 온보딩이 **조용히 항상 실패**한다(기동 경고 없음).
- **수정 방향(제안)**: (구현하지 않음) ①다음 회차 스택 기동 시 `docker-compose.local.yml` 에 로컬 전용 BCrypt(cost 12) 해시를 주입해 성공 경로를 live 대상에 포함 ②코드 측으로는 `RoleClaimService` 생성자에서 공란 감지 시 기동 WARN(`DevToggleStartupWarner` 동형)을 남겨 fail-closed 는 유지하되 **무음 상태를 제거**.

### [A-ISSUE-22] TC-CLAIM-016 — role-claim 전역 축(50회/분)을 단일 계정이 소진해 전 사용자 온보딩을 차단할 수 있다(미해소 이월)
- **심각도**: MEDIUM *(케이스 판정은 PASS — "교차계정 증폭 차단"이라는 기대결과 자체는 정확히 동작. 그 설계의 반대편 실패 모드)*
- **기대 동작(기대효과)**: 전역 축은 계정 A/B/C 를 번갈아 쓰는 무차별 대입 증폭 차단이 목적이며, 동시에 **정상 신규 사용자의 온보딩은 가용해야 한다** — role-claim 은 role=null 사용자가 시스템에 진입하는 유일한 경로다(TC-AUTHZ-024/025).
- **현재 동작(이슈 내용)**: 전역 축은 엔드포인트 단일 카운터(`GLOBAL_IDNTFR="GLOBAL"`)이고 **거부된 시도에도 계속 증가**한다(증가 후 판정).
  ```java
  // RoleClaimRateLimiter.java:112-127
  int local = localCounters.get(cacheKey, k -> new AtomicInteger()).incrementAndGet();
  int shared = sharedStore == null ? UNAVAILABLE : sharedStore.recordAttempt(...);
  int effective = Math.max(local, shared);
  if (effective > limit) { log.warn(...); throw new CustomException(ErrorCode.TOO_MANY_REQUESTS, ...); }
  ```
  실측(3차): 1분 내 60회 호출 후 **전혀 다른 신규 계정(sub=9999001)의 첫 시도가 429**. 로그는 `axis=GLOBAL attempts=51..61 limit=50` 으로 임계 초과 후에도 상승했고 DB `atmpt_nmtm=61` 로 확정됐다. 추가 관측 2건: ①**429 응답에 `Retry-After` 헤더가 없다**(정상 사용자가 재시도 시점을 알 수 없음) ②`rate-limited axis=GLOBAL` 로그에 **소진 주체 식별자가 없어** 운영자가 원인 계정을 특정할 수 없다.
- **재현/확인 경로**:
  ```bash
  # 서로 다른 sub 60개(또는 동일 sub)로 1분 내 호출 — pw 는 틀려도 무방(rate limit 이 pw 앞)
  for t in $(cat toks.txt | head -60); do curl -s -o /dev/null -w "%{http_code} " -X POST \
    -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
    -d '{"role":"WORKER","adminPassword":"xxxx"}' http://localhost:18081/api/v1/auth/role-claim; done
  # → 50회 401, 51회째부터 429. 이후 신규 계정 첫 시도도 429
  ```
  ```sql
  select atmpt_se_cd, atmpt_idntfr, bgng_dt, atmpt_nmtm from ls_authrt_grant_atmpt
   where atmpt_se_cd='GLOBAL' order by bgng_dt desc limit 3;   -- 51 초과 후에도 61까지 증가
  ```
- **영향**: 가용성 — 신규/역할미배정 사용자 온보딩 전면 차단(CWE-770, OWASP API4:2023). 인증된 저권한 계정 1개면 성립. 보안 강도(무차별 대입 억제)는 손상되지 않는다.
- **수정 방향(제안)**: (구현하지 않음) ①임계 초과가 확정된 축은 카운터를 더 올리지 않고 즉시 거절(고착·DB 쓰기 증폭 동시 완화) ②전역 축을 차단이 아니라 경보+지수 백오프로 낮추고 실제 차단은 계정 축이 담당 ③`ATMPT_IDNTFR` 별 상위 소비자를 집계해 소진 계정만 장기 차단 + 로그에 축별 상위 소비자 노출 ④최소한 429 에 `Retry-After: 60` 부착(`HmacWebhookFilter` 는 이미 그렇게 한다 — 동일 프로젝트 내 비대칭).

### [A-ISSUE-23] TC-AUTHZ-002 / TC-AUTHZ-010 / TC-AUTHZ-024 — `/v1/manage/labels/**` GET 예외가 PORTAL 채널·역할미배정에게 라벨 마스터·COCO 검출 매핑·속성 정의를 노출(미해소 이월)
- **심각도**: LOW *(케이스 판정은 PASS — 카탈로그가 "의도된 예외"로 명시하고 실동작이 일치. 예외의 정당화 근거가 `/v1/me` 대비 약하다는 점을 재기록)*
- **기대 동작(기대효과)**: 채널 격리(R5-1)와 role=null fail-open 차단(A-ISSUE-02 수정)의 예외는 **업무 데이터가 없는 경로로 한정**되어야 한다. `SecurityConfig.java:72-83` 이 `/v1/me` 예외를 정당화한 근거가 정확히 그것이다.
- **현재 동작(이슈 내용)**: `/v1/manage/labels` 와 하위 전체가 GET 에 한해 `authenticated()` 다.
  ```java
  // SecurityConfig.java:126
  .requestMatchers(HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  ```
  3차 실측 — PORTAL 채널 토큰·role=null INTERNAL 토큰·WORKER 모두 아래 3경로가 **200 + 실데이터**:
  `GET /v1/manage/labels`(라벨 마스터 전량 `labelId/name/color/type/dtctTypeCd/useYn`) · `GET /v1/manage/labels/detect-candidates`(**COCO 검출 클래스 매핑** — 포털은 ADR-013 상 오토라벨링 미제공이라 불필요) · `GET /v1/manage/labels/{labelId}/attrs`(속성 정의). 같은 `/v1/manage` 하위의 `configs`·`presets`·`health` 는 정상적으로 403 이라 **노출 범위는 labels 3경로로 한정**됨을 재확인했다.
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $T_PORTAL" http://localhost:18081/api/v1/manage/labels/detect-candidates
  curl -s -H "Authorization: Bearer $T_NOROLE" http://localhost:18081/api/v1/manage/labels/1/attrs
  # → 둘 다 200 + 실데이터
  ```
- **영향**: 정보 노출(CWE-200) · 불완전한 채널 격리(CWE-863 부분). 유출물은 PII 가 아닌 라벨 taxonomy·AI 검출 클래스 매핑·속성 스키마.
- **수정 방향(제안)**: (구현하지 않음) ①`detect-candidates` 를 `hasAnyRole(REVIEWER, WORKER)` 로 분리(포털에 불필요) ②와일드카드 대신 **필요한 GET 서브리소스만 명시 매처로 나열**(신규 GET 이 자동으로 예외에 편입되는 구조 제거) ③role=null 은 예외에서 제외(채널 조건만 완화) ④`/v1/me` 예외 주석과 같은 불변식 주석을 이 매처에도 명시.

### [A-ISSUE-24] TC-AUTHZ-009 / TC-AUTHZ-010 — `channel` 클레임을 생략한 포털 발급 토큰이 INTERNAL 로 승격되어 채널 격리를 우회(미해소 이월, 1차 A-ISSUE-02)
- **심각도**: MEDIUM *(케이스 판정은 PASS — 카탈로그가 기술한 `CHANNEL_PORTAL` 토큰 시나리오는 정확히 403. 그 전제를 무너뜨리는 인접 경로를 기록)*
- **기대 동작(기대효과)**: R5-1 채널 격리는 "외부(포털) 채널 토큰이 내부 업무 API 에 도달하지 못한다"를 보장해야 한다. 두 채널이 **동일 시크릿·동일 issuer allowlist** 를 공유하므로(CLAUDE.md "인증·진입"), 채널 판정은 클레임 유무가 아니라 **발급자와 결합**돼야 한다.
- **현재 동작(이슈 내용)**: `channel` 클레임이 없으면 발급 issuer 와 무관하게 INTERNAL 로 기본값 처리된다(`JwtAuthenticationFilter.java:86-87`, `SecurityConfig.java:141-146` 주석이 "기존 내부 사용자 토큰 호환"으로 의도 명시). 실측:
  ```
  iss=klid-portal, channel 클레임 없음, sub=2001(LS_USER_ROLE=WORKER)
   → GET /v1/me  : {"role":"WORKER","channel":"INTERNAL"}
   → GET /v1/videos : 200   (내부 업무 API 통과)
  iss=klid-portal, channel 없음, sub=1001(REVIEWER)
   → GET /actuator/metrics : 200
  ```
  **완화 요인(실측)**: 역할은 여전히 `LS_USER_ROLE` 에서 오므로, 실제 포털 사용자(sub=3001, LS=PORTAL_USER)가 같은 짓을 하면 `CHANNEL_INTERNAL + ROLE_PORTAL_USER` 가 되어 `/v1/**` 403 이다(자기 포털 API 도 403 이 되어 오히려 기능만 깨진다). 따라서 성립 조건은 **"sub 를 내부 userNo 로 지정한 토큰을 얻을 수 있는가"** = 포털 발급 서버의 오작동·침해 또는 공유 시크릿 유출이다.
- **재현/확인 경로**:
  ```bash
  # JWT_SECRET 으로 {"iss":"klid-portal","sub":"2001"} (channel 클레임 없음) 서명
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" http://localhost:18081/api/v1/videos   # → 200
  ```
- **영향**: 보안 — 불완전한 채널 격리(CWE-863) · 신뢰 경계 혼동(CWE-501). 단독 취약점이 아니라 "포털 발급 서버 침해 시 저작도구 내부 API 까지 즉시 확산"이라는 **폭발 반경 확대** 성격이다.
- **수정 방향(제안)**: (구현하지 않음) ①`channel` 을 **필수 클레임으로 승격**하고 미보유 토큰을 거부(하위호환이 필요하면 이행 기간 동안 WARN 후 차단) ②또는 issuer→channel 매핑표(`klid-portal → PORTAL`)를 두고 클레임과 불일치 시 거부 ③장기적으로 채널별 서명 키 분리. ⚠ ①은 기존 관제 토큰 호환을 깨뜨릴 수 있어 관제팀 협의 대상.

### [A-ISSUE-25] TC-AUTHZ-021 — HSTS 가 앱에서도 edge 에서도 실제로 부여되지 않는다(미해소 이월, 1차 A-ISSUE-06 / UNCERTAINTIES #28)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 운영 도메인 응답에 `Strict-Transport-Security` 가 부착돼 SSL stripping·평문 다운그레이드를 차단해야 한다(security.md "Security 응답 헤더 필수").
- **현재 동작(이슈 내용)**: `SecurityConfig.java:64-67` 에 `httpStrictTransportSecurity(includeSubDomains, maxAge=31536000)` 가 설정돼 있으나 Spring Security 의 `SecureRequestMatcher` 때문에 **HTTPS 요청에만** 부착된다. 3차 실측에서 HTTP 응답(200·401·403 전부)에 헤더가 없음을 재확인했고, `nosniff`·`X-Frame-Options: DENY` 는 정상 부착됐다. 로컬 스택에 TLS 종단이 없어 **운영 edge(nginx/Caddy) 부여 여부는 이 회차에서도 확인 불가**.
- **재현/확인 경로**:
  ```bash
  curl -s -D - -o /dev/null -H "Authorization: Bearer $T_REV" http://localhost:18081/api/v1/videos | grep -i strict
  # → 출력 없음 (nosniff / X-Frame-Options 는 출력됨)
  ```
- **영향**: 보안 — 전송 계층 다운그레이드 방어 부재(OWASP A02:2025 Security Misconfiguration).
- **수정 방향(제안)**: (구현하지 않음) ①운영 edge(nginx/Caddy) 설정에 HSTS 를 명시하고 그 설정을 `deploy/onprem/` 문서에 고정 ②또는 앱이 `X-Forwarded-Proto` 를 신뢰하도록 `server.forward-headers-strategy` 를 켜서 프록시 뒤 HTTPS 요청을 인식하게 함(신뢰 프록시 CIDR 전제 필요) ③어느 쪽이든 **배포 체크리스트에 실응답 헤더 확인 항목**을 넣어 "설정은 있는데 안 나감"이 재발하지 않게 함.

### [A-ISSUE-26] TC-ROLE-001 — `RoleHierarchy` 빈이 빈 계층 + 참조 0건으로 사실상 no-op(UNCERTAINTIES #6 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 등록된 스프링 빈은 실제 인가 판정에 관여하거나, 관여하지 않는다면 제거돼 **"역할 상속이 설정돼 있다"는 오독**을 만들지 않아야 한다.
- **현재 동작(이슈 내용)**:
  ```java
  // RoleHierarchy.java:10-13
  @Bean
  public org.springframework.security.access.hierarchicalroles.RoleHierarchy authoringRoleHierarchy() {
      return RoleHierarchyImpl.fromHierarchy("");     // 상속 규칙 0건
  }
  ```
  `grep -rn RoleHierarchy backend/src` 결과 **정의 파일 외 참조 0건**(3차 재확인). 실동작으로도 상속 부재가 확인된다 — WORKER 는 `/v1/system/**`·`/v1/manage/**`(쓰기)·`/actuator/**`·`/v1/users/**`·`/v1/reviews/*/approve` 전건 403.
- **재현/확인 경로**: `grep -rn 'RoleHierarchy' backend/src` → 1개 파일만 매칭.
- **영향**: 유지보수 — 후속 개발자가 "역할 계층이 이미 배선돼 있다"고 오해해 상속 문자열만 채우면 **아무 효과 없이 통과**한다(무음 실패).
- **수정 방향(제안)**: (구현하지 않음) 정책 결정 필요 — ①빈을 제거하고 "명시 역할 나열" 정책을 주석으로 고정, 또는 ②실제로 계층을 쓸 계획이면 `@EnableMethodSecurity` 표현식 핸들러·`authorizeHttpRequests` 에 배선하고 회귀 테스트를 추가. 현 상태(정의만 존재)는 둘 다 아니다.

### [A-ISSUE-27] TC-CLAIM-015 — REVIEWER 상승은 막혔으나 공유 정적 패스워드 자체의 잔여 통제(감사 원장·회전·만료)가 여전히 없다
- **심각도**: LOW *(케이스 판정은 PASS — 1차 A-ISSUE-17 의 핵심 요구 ①②는 해소됨. 미이행 항목 ③만 잔존)*
- **기대 동작(기대효과)**: 1차 A-ISSUE-17 수정 제안 ③ — 공유 패스워드 기반 자가부여를 유지한다면 **부여 이력 전용 원장**(요청자·역할·시각·IP·요청ID)과 **패스워드 회전·만료 정책**이 있어야 한다(CWE-1392 Use of Shared Credentials, CWE-778 Insufficient Logging).
- **현재 동작(이슈 내용)**: 부여 성공의 유일한 흔적은 애플리케이션 로그 1줄이다.
  ```java
  // RoleClaimService.java:185
  log.info("[RoleClaim] granted userNo={} role={}", userNo, req.role().name());
  ```
  `ls_authrt_grant_atmpt` 는 **rate limit 카운터 전용**(축/식별자/버킷/횟수)이라 감사 원장이 아니고 IP·요청ID·성공 여부를 담지 않는다. 패스워드는 `authoring.auth.admin-claim-password-hash` 단일 값으로 회전 주기·만료·직전 값 무효화 개념이 없다.
- **재현/확인 경로**: `\d ls_authrt_grant_atmpt` → 컬럼 `atmpt_se_cd/atmpt_idntfr/bgng_dt/atmpt_nmtm/expd_dt` 뿐. `grep -rn "granted userNo" backend/src/main` → 로그 1곳.
- **영향**: 감사·사후추적 — 공유 패스워드가 유출돼 다수 계정이 WORKER 를 자가부여해도 **누가·언제·어디서 받았는지 DB 로 조회할 수 없다**(로그 보존기간에 의존). WORKER 는 라벨 수정·검수 제출 권한이라 데이터 무결성 영향이 있다.
- **수정 방향(제안)**: (구현하지 않음) ①부여 성공 시 전용 이력 테이블에 (userNo, role, 시각, clientIp, traceId, 결과) 적재 — 표준용어·표준도메인 준수 필요 ②패스워드 회전 절차와 만료 주기를 운영 문서(`deploy/onprem/docs/`)에 고정 ③선택적으로 부여 발생 시 기존 REVIEWER 통지.

---

## 6. 참고 — 판정에 반영하지 않은 관측

- **경로 조작 요청의 응답 코드가 401 이다**: `..`·`;`·`//`·`/./` 를 포함한 요청은 유효한 REVIEWER 토큰을 붙여도 401 `UNAUTHORIZED` 로 종결된다(표준 `ApiResponse`, 스택트레이스 없음). **거부 자체는 fail-closed 로 정상**이나, 의미상 400(Bad Request)이 적절한 요청이 401 로 나가 클라이언트가 "토큰 문제"로 오진할 수 있다. 1차 A-ISSUE-64(`RequestRejectedException` 500 오분류)와 같은 계열이며 A-6 소관이라 이슈로 올리지 않고 사실만 기록한다.
- **`GET /v1/assignments` 는 `@PreAuthorize("isAuthenticated()")`** (`AssignmentController.java:113`)라 WORKER 도 200 이지만, 실측상 **본인 배정만** 반환되고 타인 배정 이력은 403 이다. CLAUDE.md 의 "배정 이력 조회 권한은 REVIEWER" 서술과 코드가 완전히 일치하지는 않으나 실데이터 누수는 없다 — D 클러스터(TC-ASSIGN-*) 소관이라 여기서 판정하지 않는다.
- **`AI_MOCK_MODE=true`** 가 backend 컨테이너에 남아 있으나 backend 코드/yml 이 소비하지 않는 스트레이 변수(stack-bringup §3 과 동일 관측).
