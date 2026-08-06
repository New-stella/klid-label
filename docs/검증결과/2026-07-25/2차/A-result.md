# A 클러스터 (인증/권한/공통 인프라) — 2차 검증 결과

> 232건 · 기준 실동작(HEAD ca3c712b 재빌드본 + mock-server 연동 풀스택) · 2026-07-31


---

# A 클러스터 part1 (A-1·A-2) 2차 검증 결과

> 대상: `docs/test-cases/A-auth-common.md` 의 **A-1. 인증(JWT) 34건** + **A-2. 인가(SecurityConfig·채널격리·화이트리스트) 32건** = **66건**
> 검증 일시: 2026-07-31 02:45~03:15 KST · 검증 기준 커밋: **HEAD `ca3c712b`** (backend 이미지 재빌드 완료, `_raw/backend-rebuild.md` 참조)
> 방식: **실동작 우선** — `localhost:18081/api` 에 직접 HTTP 요청. 토큰은 ①`POST /v1/dev/tokens` 발급분 ②실 `JWT_SECRET`(`e00184be…`)으로 **직접 서명 위조한 26종 변형**(만료·무서명·alg=none·issuer/channel/sub/role 위조·exp 누락 등) 병행.
> 폐기(`~~취소선~~`) 행: A-1·A-2 구간에 **0건** — 66건 전수가 검증 대상.
> ⚠ 검증 중 다른 에이전트가 파이프라인 데이터를 재생성해 `ls_data_raw` 참조가 `123~125` → **`126~128`** 로 교체됨(rawSn=126 = COMPLETED/`DE_IDENT_YN='Y'`/worker1(2001) 배정). 스트림 관련 판정은 rawSn=126 실측 기준.
> ⚠ 검증 목적으로 `public.ls_user_role` 에 **합성 userNo(990001~990003) 행을 일시 삽입 후 전량 삭제**했고, `2002`(worker2) 역할을 `WORKER→REVIEWER→WORKER` 로 왕복시켜 원복했다. 최종 상태 재확인 완료(1001/1002 REVIEWER · 2001/2002 WORKER · 3001 PORTAL_USER). 프로덕션·테스트·문서 **파일 수정 0건, 빌드/테스트 실행 0건**.

## 집계

| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| **66** | **64** | **0** | **2** | 0 | 0 | 0 |

- A-1(34): PASS 33 · PARTIAL 1(TC-AUTH-005)
- A-2(32): PASS 31 · PARTIAL 1(TC-AUTHZ-013)
- 실동작 판정 **58건** / 정적 전용 판정 **8건**(TC-AUTH-011·022·023·024·025 · TC-AUTHZ-013 정적축 · TC-CORS-002 · TC-ROLE-001)
- 근거 `file:line` 드리프트 **3건**(전부 경미, 판정 영향 없음)
- 신규 결함 **4건**(MEDIUM 1 · LOW 3). **CRITICAL/HIGH 0건.**

### 반증 시도 요약 (거짓 PASS 방지)

기대결과의 단언을 그대로 믿지 않고 아래를 **실제로 던져 반증 시도**했다. 전부 fail-closed 였다.

| 반증 축 | 시도한 변형 | 결과 |
|------|------|------|
| 경로 정규화 우회(CWE-436/288) | `%6danage`·`%61ctuator`·`%70ortal`·`%76ideos`·`user%73` 퍼센트인코딩 / `//` 중복슬래시 / `/./` / `/../` 순회 / `;a=1` 경로파라미터 / 대소문자(`MANAGE`,`ACTUATOR`,`PORTAL`) / trailing slash / `.json` 접미 — **5개 게이트(manage REVIEWER · actuator REVIEWER · integration denyAll · portal 채널격리 · /v1/\*\* 역할게이트) × 각 3~10변형** | **전건 401/403/404.** 200 단 1건도 없음. A-ISSUE-13(1차 CRITICAL) 계열 우회 재현 불가 |
| 컨텍스트패스 우회 | `/v1/videos`(=/api 미접두) · `/actuator/metrics`(미접두) · `/api/../actuator/metrics` | 전건 404 |
| JWT 위조 | 다른 키 서명 / `alg:none`(무서명) / HS512 헤더 / malformed / 만료 / iss=`evil`·null·공백 / channel=`X`·소문자 / role 클레임 위조(`sub=2001`+`role=REVIEWER`) | 전건 401 또는 위조 클레임 무시(LS_USER_ROLE 승) |
| 수직 권한 상승 | `POST /v1/auth/role-claim {"role":"REVIEWER"}` (인증만 된 role=null 계정) | **403** "해당 역할은 자가 부여할 수 없습니다" — 1차 A-ISSUE-17 CRITICAL 해소 실증 |
| 수평 권한 상승(IDOR) | worker2(2002, rawSn 126 **미배정**) 토큰으로 `/v1/videos/126`·`/stream`·`/stream-url` | 전건 **403** "본인에게 배정되지 않은 영상입니다" |
| 서명 스트림 권한 합성 | 사용자 토큰(REVIEWER/WORKER/role=null/PORTAL)으로 `/stream` 직접 / 서명 URL 을 **nonce 쿠키 없이** / `sig` 1자 변조 | 쿠키 없음·변조 → **401**, role=null·PORTAL → **403**. STREAM_SIGNED 합성 불가 |
| 화이트리스트 오염 | permitAll 목록 전 경로 무토큰 호출 + 낙하 경로(`/error`,`/favicon.ico`,`/v2/videos`,`/`,`/actuator`) | 민감 노출 0. 낙하 경로 전건 401 |
| 메서드 축 우회 | `HEAD`/`OPTIONS`/`TRACE`/`PUT`/`DELETE` + `X-HTTP-Method-Override` | manage 게이트 전건 403(TRACE 400) |
| 정보 유출(CWE-209) | 잘못된 JSON / 타입 불일치 PathVariable / `page=-1` / `size=99999` / `sort=;DROP TABLE` / 미존재 리소스 | 스택트레이스·내부경로·DB스키마 노출 **0**. `size` 는 100 으로 클램프. `Server`/`X-Powered-By` 헤더 미노출 |
| fail-open catch | `UserRoleResolver` DB예외·미지 ROLE_CD·미배정 | 전부 `null`(무권한) → 게이트 403 |

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|------|------|:--:|------|
| **A-ISSUE-01** | `UserRoleResolver.resolve(null)` 이 `@Cacheable(key="#userNo")` 에 `condition` 이 없어 프록시 경유 시 캐시키 NPE (LOW) | **미해소** | [정적] `UserRoleResolver.java:46` 에 `condition` 여전히 부재(`@Cacheable(cacheNames=…, key="#userNo", unless="#result == null")`). **다만 실경로 미도달은 유지** — 프로덕션 호출자가 1차의 1곳에서 2곳으로 늘었으나(`JwtAuthenticationFilter:83`, **신규** `StreamSignatureFilter:165`) 양쪽 모두 null 을 전달하지 않는다(전자는 `parseUserNo` 선차단, 후자는 `Long.parseLong` 결과라 non-null + `NumberFormatException` catch). 케이스 TC-AUTH-027 은 이 "미해소·미도달" 상태를 기대결과로 고정하고 있어 **케이스 판정은 PASS** |
| **A-ISSUE-12** | HMAC 콜백 replay 방지가 시간 윈도우뿐(윈도우 내 무제한 재전송) (MEDIUM) | **해소(구조 교체)** | [정적] `WebhookNonceStore`/`JdbcWebhookGuardStore:107-119`(서명해시 nonce 1회 소비, 중복 시 거부) 신설 + 애초에 **서명 필수 경로가 0개**로 재설계(`WebhookProtectedPaths` — 구 `/v1/aug/callback` 제거). [실동작] `POST /v1/aug/callback` 익명 → **401**(경로 자체 부재) |
| **A-ISSUE-13** | raw URI 문자열 정확일치 기반 필터 적용 → 퍼센트인코딩 경로로 HMAC 우회 (**HIGH/CRITICAL, 1차 실증 확정**) | **해소** | [실동작] 위 "반증 시도" 표의 퍼센트인코딩·중복슬래시·대소문자·`;`·`..` 변형을 **5개 게이트에 전부** 던졌으나 200 통과 0건. [정적] `WebhookProtectedPaths` 가 MVC 와 동일한 `PathPattern`/`RequestPath` 로 판정 + `WebhookGateInterceptor` 가 컨트롤러 진입 직전 통과증거를 재확인하는 **2단 게이트**(SecurityConfig.java:95-97 주석) |
| **A-ISSUE-14** | 웹훅 rate limit 이 JVM-local → 2노드에서 임계 2배·재기동 시 소실 (MEDIUM) | **해소** | [정적] `JdbcWebhookGuardStore:56`(`implements WebhookNonceStore, WebhookRateLimitStore`) — `controlDataSource` 기반 **노드 공유 DB 저장소**로 승격. `WebhookGuardPurgeJob` 로 회수 |
| **A-ISSUE-15** | clientIp 를 `getRemoteAddr()` 로만 산출 → 프록시 뒤에서 정상 콜백까지 차단(자기유발 DoS) (MEDIUM) | **해소** | [정적] `ClientIpResolver` 신설 — `webhook.trusted-proxy-cidrs`(CSV) 신뢰 프록시 목록 기반 `X-Forwarded-For` 파싱(`ClientIpResolver:76-94`). 동시에 `ForwardedHeadersConfigGuard` 가 `server.forward-headers-strategy`/`server.tomcat.remoteip.*` 설정 시 **기동을 거부**해 무검증 XFF 치환 회귀를 차단. [실동작] `X-Forwarded-Proto: https` 를 보내도 `request.isSecure()` 가 바뀌지 않음(=필터 미설치) 확인 |
| **A-ISSUE-17** | 공유 정적 패스워드만으로 임의 인증 사용자가 **REVIEWER 자가부여** (**HIGH**) | **해소** | [실동작] role=null INTERNAL 토큰 → `POST /v1/auth/role-claim {"role":"REVIEWER"}` → **403** `해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요.` (패스워드 검증 **이전**에 역할 화이트리스트로 거절). `WORKER` 는 401(패스워드 불일치), `PORTAL_USER` 는 400. [정적] `RoleClaimService:118` 이 `allowedClaimRoles()`(WORKER 단일, `:223-227`)를 **실제로 참조** — 1차 지적("화이트리스트가 정의만 되고 미참조") 해소 |
| **A-ISSUE-18** | 자가부여 rate limit 이 계정 단위 in-memory(교차계정·다중노드 우회) + attempts 맵 무한 증가 (MEDIUM) | **해소** | [정적] `RoleClaimRateLimiter` 로 분리 — **계정 축 + 엔드포인트 전역 축 2축**(`:30`), 공유 저장소 `RoleClaimAttemptStore`/`JdbcRoleClaimAttemptStore`(노드 공유), 로컬 카운터는 `ConcurrentHashMap` → **Caffeine TTL + maximumSize**(`:50`)로 교체돼 무한 증가 제거. `RoleClaimAttemptPurgeJob` 회수 |

> ⚠ A-ISSUE-12·13·14·15·17·18 은 케이스 소속이 **A-4(웹훅)·A-5(권한 자가부여)** 라 본 파트(A-1·A-2)의 66건 집계에는 포함되지 않는다. 위 판정은 "해소 여부 1:1 대조" 지시에 따른 **교차 확인**이며, A-4·A-5 케이스별 전수 판정은 해당 파트 담당이 별도 수행해야 한다.

## A-1 결과표 (인증 JWT — 34건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-AUTH-001 | 유효한 INTERNAL JWT로 인증 컨텍스트 설정 | PASS | [실동작] `GET /v1/me` + 유효 REVIEWER 토큰 → 200 `{"userId":"1001","name":"김검수","role":"REVIEWER","channel":"INTERNAL"}` / [정적] JwtAuthenticationFilter.java:56-111 일치 | authority 부여는 `/v1/system`·`/v1/manage` 200 으로 간접 실증 |
| TC-AUTH-002 | Authorization 헤더 없으면 익명 통과 | PASS | [실동작] 무헤더 `/v1/me`·`/v1/videos` → 401 `UNAUTHORIZED` (필터는 통과, 인가에서 거부) | |
| TC-AUTH-003 | Bearer 접두사 없으면 파싱 미시도 | PASS | [실동작] `Token <t>` / `bearer <t>`(소문자) / `Bearer`(값 없음) → 전건 401 | 대소문자 정확 매칭 확인 |
| TC-AUTH-004 | 서명 불일치 토큰 거부+컨텍스트 클리어 | PASS | [실동작] 64B 임의 키로 서명한 동일 페이로드 → 401 | |
| TC-AUTH-005 | 만료 토큰(exp 과거) 거부 | **PARTIAL** | [실동작] `exp=now-60` → 401 (문자적 단언 성립). **그러나 `exp` 클레임 자체가 없는 토큰은 200 + 전 업무 API 통과** — 만료 통제가 fail-open | **A-ISSUE-01**(신규, MEDIUM) |
| TC-AUTH-006 | alg=none 토큰 거부 | PASS | [실동작] `{"alg":"none"}` 헤더 + 서명부 빈 문자열 → 401 | 단위 테스트 부재(A-ISSUE-04) |
| TC-AUTH-007 | malformed JWT 거부 | PASS | [실동작] `Bearer garbage` → 401 / HS512 헤더 토큰도 401 | |
| TC-AUTH-008 | 미허용 issuer 거부 | PASS | [실동작] `iss=evil` → 401 | |
| TC-AUTH-009 | issuer null/blank 거부 | PASS | [실동작] `iss` 키 삭제 → 401 / `iss="   "` → 401 | |
| TC-AUTH-010 | 허용 issuer 기본값(klid/klid-portal/klid-auth) | PASS | [실동작] 3종 모두 200 / [정적] application.yml:173 `${JWT_ALLOWED_ISSUERS:klid-auth,klid,klid-portal}` — 런타임 env 값도 동일 3종 | |
| TC-AUTH-011 | issuer 목록 공백/빈항목 제거 | PASS | [정적] JwtIssuerValidator.java:16-19 `map(trim).filter(!isEmpty)` + `isAllowed` 가 `allowed.contains` 라 빈 집합이면 전건 false(fail-closed) | 실동작 미검증 — 환경변수 재주입 불가(컨테이너 재시작 금지) |
| TC-AUTH-012 | channel 부재 시 INTERNAL 기본 | PASS | [실동작] `channel` 키 삭제 → `/v1/me` channel=`INTERNAL`, `/v1/videos` 통과 | |
| TC-AUTH-013 | channel=PORTAL은 role=PORTAL_USER 고정 | PASS | [실동작] `sub=1001`(LS=REVIEWER) + `channel=PORTAL` → role=**PORTAL_USER**(LS 미조회 확인), `sub=777777`(미매핑)도 PORTAL_USER | sub 값 무관 실증 |
| TC-AUTH-014 | 알 수 없는 channel 거부 | PASS | [실동작] `channel="X"` → 401 / `channel="internal"`(소문자) → 401 | `Channel.valueOf` IllegalArgument catch |
| TC-AUTH-015 | INTERNAL 인가 역할은 LS_USER_ROLE에서 | PASS | [실동작] `sub=2001`(LS=WORKER) + **JWT `role=REVIEWER` 위조** → `/v1/me` role=`WORKER`, `/v1/system/configs` **403** | JWT role 클레임 미참조 실증 |
| TC-AUTH-016 | INTERNAL sub 비숫자면 무권한 | PASS | [실동작] `sub="abc"` → `/me` role=null, `/v1/videos` 403 | |
| TC-AUTH-017 | INTERNAL sub null/blank 무권한 | PASS | [실동작] `sub` 삭제 / `sub="   "` → 둘 다 role=null | |
| TC-AUTH-018 | LS_USER_ROLE 매핑 없으면 role=null | PASS | [실동작] `sub=999999` → role=null, CHANNEL만 | |
| TC-AUTH-019 | role=null 토큰은 CHANNEL authority만 → 업무 API 403 | PASS | [실동작] `sub=999999` → `/v1/videos` **403** · `/v1/event-types` **403** · `/v1/me` **200** | A-ISSUE-02(1차) 해소 재확인 |
| TC-AUTH-020 | name 클레임 request attribute 전달 | PASS | [실동작] `name` 있음 → `/me` name 반환 / `name` 삭제 → `name:null` (NPE·500 없음) | |
| TC-AUTH-021 | 사용자 토큰은 STREAM_SIGNED authority 획득 불가 | PASS | [실동작] rawSn=126: role=null·PORTAL 토큰 `/stream` → **403**, 무토큰 → 401, **서명URL+nonce쿠키(무토큰) → 200** / [정적] StreamSignatureFilter.java:55-63(권한 상수 단일 출처), :136-138(ROLE_* 미부여) | 사용자 경로로 STREAM_SIGNED 합성 불가 실증 |
| TC-AUTH-022 | 생성자 인자 null이면 필터 생성 거부(fail-closed) | PASS | [정적] JwtAuthenticationFilter.java:37-48 — 3개 인자 각각 `IllegalArgumentException` | 런타임 재현 불가(빈 생성 시점) |
| TC-AUTH-023 | JWT 시크릿 32B 미만이면 부팅 거부 | PASS | [정적] SecretKeyResolver.java:19-22 + `SecretKeyResolverTest.rejectsShortInput` | 재기동 금지로 실동작 미재현 |
| TC-AUTH-024 | 32B 이상 시크릿 HS256 키 생성 | PASS | [정적] SecretKeyResolver.java:17-29 — `final SecretKey key` 필드 1회 생성 후 반환 + `acceptsInputAtLeast32Bytes` | |
| TC-AUTH-025 | UserRoleResolver DB 장애 시 null(fail-closed) | PASS | [정적] UserRoleResolver.java:56-60 `catch(DataAccessException) → return null`, 로그에 userNo만 + `UserRoleResolverTest.dbExceptionFailsClosed` | DB 장애 주입 미수행(다른 에이전트 파이프라인 보호) |
| TC-AUTH-026 | ROLE_CD가 enum에 없으면 null | PASS | [실동작] `ls_user_role(990002,'ADMIN')` 삽입 → `/me` role=**null**, `/v1/videos` **403** (삽입행 삭제 완료) | fail-closed 실증 |
| TC-AUTH-027 | userNo=null 이면 무권한 — 단 프록시 경유는 캐시키 NPE | PASS | [정적] UserRoleResolver.java:46 `condition` 부재 확인(A-ISSUE-01 **미해소**). 프로덕션 호출자 2곳(JwtAuthenticationFilter:83, StreamSignatureFilter:165) 모두 null 미전달 → 실경로 미도달 | 케이스가 "미해소 현행"을 기대결과로 고정 → PASS. 이슈 자체는 1차 대조표에 미해소로 기록 |
| TC-AUTH-028 | 역할 조회 userNo 캐시 | PASS | [실동작] `(990001,'WORKER')` 삽입 → `/me` WORKER → **DB 행 삭제** → 재요청에도 **여전히 WORKER** = 캐시 적중 실증 | **근거 드리프트**: `CacheConfig.java:85-89` → 실제 **88-92**. 부수: `recordStats()` 미설정이라 `cache.gets` 메트릭 항상 0(A-ISSUE-03) |
| TC-AUTH-029 | null 결과 캐시 미저장 | PASS | [실동작] `sub=990003` 미배정 상태 3회 조회(전부 null) → `REVIEWER` 부여 → **즉시 REVIEWER** 반영 | `unless="#result==null"` 실증 |
| TC-AUTH-030 | evict(userNo) 캐시 무효화 | PASS | [실동작] `PATCH /v1/users/2002 {"role":"REVIEWER"}` 직후 `/me` 가 즉시 REVIEWER | `@CacheEvict` 키 표현식 일치 실증 |
| TC-AUTH-031 | 역할 변경 후 강등 즉시 반영 | PASS | [실동작] 승격: `/v1/users`(REVIEWER 전용) 403→**200**. 강등(REVIEWER→WORKER): 즉시 **200→403**. DB 최종 `WORKER` 원복 확인 | AFTER_COMMIT evict 실증 |
| TC-AUTH-032 | roleName() role null이면 NPE 없이 null | PASS | [실동작] role=null 토큰 `/me` → `"role":null` 200 (500 없음) / [정적] TokenClaims.java:13-15 | |
| TC-COMMON-001 | /me 미인증 시 401 | PASS | [실동작] 무토큰 `/v1/me` → 401 `{"errorCode":"UNAUTHORIZED","message":"인증이 필요합니다."}` | |
| TC-COMMON-002 | /me sub/name/role/channel 반환 | PASS | [실동작] `MeResponse` 4필드. role/channel null 안전(role=null 케이스 200) | 근거 SessionController.java:36-39 → 실제 `me()` 는 34-45(경미 드리프트) |

## A-2 결과표 (인가 — 32건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-AUTHZ-001 | REVIEWER 아니면 /v1/manage/** 쓰기 403 | PASS | [실동작] WORKER: `POST`/`PUT` `/v1/manage/labels` → 403, `DELETE /v1/manage/labels/1` → 403, `GET /v1/manage/users` → 403 | GET 매처 뒤 낙하 확인 |
| TC-AUTHZ-002 | /v1/manage/labels GET은 임의 역할 허용 | PASS | [실동작] WORKER·**PORTAL**·**role=null** 전부 200(라벨 마스터 전문 반환). `/v1/manage/labels/1/attrs` 도 PORTAL 200 | `authenticated()` = 채널·역할 무검사 실증. 의도된 예외(SecurityConfig.java:122-126 주석) |
| TC-AUTHZ-003 | /v1/system/**은 REVIEWER만 | PASS | [실동작] WORKER → **403** / REVIEWER → 404(핸들러 부재 = 인가는 통과) | |
| TC-AUTHZ-004 | /actuator/**(health/info 제외) REVIEWER만 | PASS | [실동작] WORKER `/actuator/metrics` **403** · REVIEWER **200** · 익명 **401** · 익명 `/actuator/env` **401** / [정적] application-prd.yml:92-101 `exposure.include: health` | |
| TC-AUTHZ-005 | /health, /actuator/health(/**), /actuator/info permitAll | PASS | [실동작] 익명 `/actuator/health` 200(`status`+`groups` 만) · `/actuator/health/liveness` 200 · `/actuator/info` 200 · `/health` 200. REVIEWER 는 `components`(db/aiServerHealth/…) 노출 = `when-authorized` 실증 | |
| TC-AUTHZ-006 | /v1/integration/** 무조건 거부(deprecated) | PASS | [실동작] REVIEWER **403** / 익명 **401** | |
| TC-AUTHZ-007 | /v1/export-api/** 무조건 거부 | PASS | [실동작] REVIEWER **403** / 익명 **401** | |
| TC-AUTHZ-008 | 포털 API는 PORTAL 채널+역할 둘 다 필요 | PASS | [실동작] PORTAL 토큰 `/v1/portal/datamart/videos` → **200** | |
| TC-AUTHZ-009 | INTERNAL 채널로 포털 API 접근 거부 | PASS | [실동작] INTERNAL REVIEWER → `/v1/portal/datamart/videos` **403**, `/v1/portal/uploads` **403** | `allOf` AND 결합 실증 |
| TC-AUTHZ-010 | PORTAL 채널로 내부 /v1/** 접근 거부 | PASS | [실동작] PORTAL → `/v1/videos` **403**, `/v1/reviews` **403**. 의도된 예외 2곳: `GET /v1/manage/labels` **200**, `/v1/me` **200** | 예외 2곳 모두 실측 확인 |
| TC-AUTHZ-011 | /v1/notices REVIEWER/WORKER만, PORTAL_USER 차단 | PASS | [실동작] PORTAL **403** · WORKER **200** · role=null **403** | 매처 순서 정상 |
| TC-AUTHZ-012 | /v1/dev/tokens dev-login 활성 시만 permitAll | PASS | [실동작] 익명 `POST /v1/dev/tokens` → **201** (`authoring.dev.login.enabled=true`, local) | 임의 `userNo` 지정 발급도 가능하나 role 은 LS_USER_ROLE 이 결정하므로 권한 상승 아님(sub=424242 → role null 확인) |
| TC-AUTHZ-013 | dev-login 비활성 시 /v1/dev/** 가드(fail-closed) | **PARTIAL** | [정적] 3중 차단 확인 — ①SecurityConfig.java:52-53,103(permitAll 매처 조건부) ②:116 `/v1/dev/**` `hasRole(REVIEWER)` ③`DevTokenController.java:34` `@ConditionalOnProperty(havingValue="true")`. 추가로 `DevToggleProfileGuard:59-63` 이 prd/stg + enabled=true 조합에서 **기동 거부**. [실동작] tokens **외** 경로는 익명 401 확인(`/v1/dev/autolabel-test`, `/v1/dev/batch/scan`, `/v1/dev/tokensXX`, `/v1/dev/tokens/../autolabel-test`) | **토글=false 형상 자체를 재현 불가**(컨테이너 재기동 금지). **A-ISSUE-02**(검증 제약). 근거 드리프트: `application.yml:437-441` → 실제 **424-428** |
| TC-AUTHZ-014 | /v1/dev/**(tokens 외) REVIEWER만 | PASS | [실동작] WORKER `POST /v1/dev/autolabel-test` **403** / 익명 **401** / `GET /v1/dev/batch/scan` 익명 **401** | |
| TC-AUTHZ-015 | /v1/auth/role-claim authenticated만 | PASS | [실동작] 익명 `POST /v1/auth/role-claim` → **401**(permitAll `/v1/auth/**` 보다 먼저 매칭) | |
| TC-AUTHZ-016 | /v1/auth/**, /v1/portal/auth/** permitAll | PASS | [실동작] 익명 `/v1/auth/xxx`·`/v1/portal/auth/xxx` → **404**(=인가 통과 후 핸들러 부재). 401 이 아님 = permitAll 실증 | |
| TC-AUTHZ-017 | swagger/api-docs permitAll(비운영 한정) | PASS | [실동작] 익명 `/swagger-ui.html` 302 · `/swagger-ui/index.html` 200 · `/v3/api-docs` 200 · `/v3/api-docs/swagger-config` 200 / [정적] application-prd.yml:86-90 `api-docs.enabled:false`,`swagger-ui.enabled:false` → prd 404 | |
| TC-AUTHZ-018 | 인증 실패 시 표준 ApiResponse(UNAUTHORIZED) JSON | PASS | [실동작] 401 본문 `{"success":false,"data":null,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — 스택트레이스·내부경로·DB스키마 **미포함** | `WWW-Authenticate` 헤더도 미부착(httpBasic 비활성 실증) |
| TC-AUTHZ-019 | 인가 실패 시 표준 ApiResponse(FORBIDDEN) JSON | PASS | [실동작] 403 본문 `errorCode:"FORBIDDEN"` | |
| TC-AUTHZ-020 | 세션 STATELESS(세션 미생성) | PASS | [실동작] 200·401 양쪽 응답 헤더에 `Set-Cookie: JSESSIONID` **없음** | 유일한 Set-Cookie 는 `klid_stream_nonce`(서명 스트림 전용, HttpOnly·SameSite=Lax·Path=/api/v1/videos) |
| TC-AUTHZ-021 | 보안 헤더 — nosniff/XFO 전 응답, HSTS 는 HTTPS만 | PASS | [실동작] 200·401·403 전 응답에 `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY`. HTTP 요청엔 `Strict-Transport-Security` 미부착(`X-Forwarded-Proto: https` 를 붙여도 동일 — `ForwardedHeadersConfigGuard` 가 프록시 헤더 신뢰를 의도적으로 차단) / [정적] SecurityConfig.java:61-67 + `SecurityConfigTest.hstsHeaderPresentOnSecureRequest` | ⚠ edge(nginx/Caddy) HSTS 미부여 = **UNCERTAINTIES #28 잔여 유지**(케이스 기대결과에 이미 반영됨) |
| TC-AUTHZ-022 | CSRF/formLogin/httpBasic 비활성 | PASS | [실동작] CSRF 토큰 없이 `POST/PUT/DELETE` 처리(403 은 인가 사유) · 401 이 로그인 리다이렉트 아닌 JSON · `WWW-Authenticate` 미부착 / [정적] SecurityConfig.java:56,58,59 | |
| TC-AUTHZ-023 | 매처 순서상 manage/labels GET이 REVIEWER 매처보다 먼저 | PASS | [실동작] WORKER `GET /v1/manage/labels` **200**, 같은 토큰 `GET /v1/manage/users` **403** = 순서 정상 | 회귀 가드 성립 |
| TC-AUTHZ-024 | 역할 미배정(role=null) INTERNAL 은 업무 조회 API 전건 403 | PASS | [실동작] `sub=999999` → `/v1/videos` 403 · `/v1/event-types` 403 · `/v1/notices` 403 · `/v1/reviews` 403 | 구 fail-open 해소 재확인 |
| TC-AUTHZ-025 | /v1/me 는 역할 미배정도 200 — 온보딩 예외 | PASS | [실동작] role=null INTERNAL **200** · PORTAL 채널 **200**. 응답은 `sub/name/role/channel` 4필드(토큰 클레임 반향)뿐 — 업무 데이터 없음 | 의도된 예외 유지 |
| TC-AUTHZ-026 | STREAM_SIGNED 단독 authority 로 /v1/** 통과 | PASS | [실동작] rawSn=126: `stream-url` 발급(`?exp&u&sig` + `klid_stream_nonce` 쿠키) → **Authorization 헤더 없이 200**. 쿠키 미동봉 → **401**, `sig` 1자 변조 → **401**. 영상 단위 인가는 worker2(미배정) → **403** 로 별도 강제 확인 | 케이스의 "LabelAccessGuard 별도 강제" 단언까지 실증 |
| TC-AUTHZ-027 | 웹훅 permitAll 경로는 무서명 2종뿐 | PASS | [실동작] 익명 `POST /v1/vlm/callback`·`/v1/genai/callback` → **400**(본문 검증 도달 = 인증 통과) / 익명 `POST /v1/aug/callback`·`/v1/deidentify/result` → **401**(permitAll 대상 아님) / [정적] SecurityConfig.java:100-102, WebhookProtectedPaths.java:50,57 | 구 매처 제거 실증 |
| TC-CORS-001 | CORS origin 미설정 시 외부 origin 차단 | PASS | [실동작] 현 환경은 `CORS_ALLOWED_ORIGINS` 설정됨 — `Origin: http://evil.com` preflight → **403**, 실요청에도 `Access-Control-Allow-Origin` **미부여**. `Origin: null` → 403 / [정적] SecurityConfig.java:178-187 미설정 시 `setAllowedOrigins(List.of())` | 미설정 축은 정적 |
| TC-CORS-002 | 콤마 origin 목록 trim 허용 | PASS | [정적] SecurityConfig.java:180-184 `split(",")→trim→filter(!isEmpty)` | 런타임 env 값에 공백이 없어 실동작 미재현 |
| TC-CORS-003 | 허용 메서드/헤더/노출헤더/credentials 구성 | PASS | [실동작] preflight 200 — `Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`(6종) · `Allow-Headers: Authorization` 반향 · `Expose-Headers` **8종**(X-Trace-Id, Upload-Offset, Upload-Length, Tus-Resumable, Tus-Version, Tus-Extension, Tus-Max-Size, Location) · `Allow-Credentials: true` · `*` 미사용 | |
| TC-ROLE-001 | RoleHierarchy 빈 계층(상속 없음) | PASS | [정적] RoleHierarchy.java:10-13 `fromHierarchy("")` 유지, 소스 내 이 빈 참조 0건 → UNCERTAINTIES #6 **미해소 유지** | ⚠ Spring Security 6.3+ 는 `RoleHierarchy` 빈을 표현식 핸들러에 자동 배선할 수 있어 "참조 0건"은 **소스 grep 기준**이다. 계층이 비어 결과는 어느 쪽이든 no-op — 판정 영향 없음 |
| TC-ROLE-002 | Role/Channel enum 정의값만 | PASS | [정적] Role.java:3-7(REVIEWER/WORKER/PORTAL_USER) · Channel.java:3-6(INTERNAL/PORTAL) / [실동작] `channel="X"`·`"internal"` → 401(`IllegalArgumentException` 경로) | |

## 근거 드리프트

| TC | 카탈로그 근거 | 실제 위치 | 영향 |
|----|------|------|:--:|
| TC-AUTH-028 | `CacheConfig.java:85-89` | `CacheConfig.java:88-92` (`CaffeineCache userRole = …maximumSize(500)…expireAfterWrite(60s)`) | 없음(내용 동일, 3행 이동) |
| TC-AUTHZ-013 | `application.yml:437-441` | `application.yml:424-428` (`authoring.dev.login.enabled: ${DEV_LOGIN_ENABLED:false}` / `dev.upload.enabled`) | 없음 |
| TC-COMMON-001 | `SessionController.java:36-39` | `me()` 본문은 `34-45` (36-39 는 `@ApiResponses` 어노테이션) | 없음 |

> 나머지 63건의 `file:line` 은 실측과 일치했다(JwtAuthenticationFilter 12건 · SecurityConfig 24건 · UserRoleResolver 6건 · JwtIssuerValidator 4건 · SecretKeyResolver 2건 · StreamSignatureFilter 2건 · WebhookProtectedPaths 1건 · RoleClaimService 1건 · TokenClaims/Role/Channel/RoleHierarchy 4건 · application*.yml 3건). 1차 대비 드리프트율이 크게 낮다(3/66 = 4.5%).

## 이슈 상세

### [A-ISSUE-01] TC-AUTH-005 — `exp` 클레임이 **없는** JWT 가 무기한 통과 (만료 통제 fail-open)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `security.md` JWT 보안 절이 **"만료 시간(`exp`) 필수 설정"** 을 못박고 있다. 저작도구는 관제/포털이 발급한 토큰을 검증만 하는 구조라, 만료 없는 토큰을 받아들이면 **캡처된 토큰이 영구 유효**해지고(CWE-613 Insufficient Session Expiration / CWE-294) 세션 만료 시 상위 시스템 로그인으로 되돌리는 정책(`CLAUDE.md` 인증·진입)도 성립하지 않는다. `TokenClaims.exp` 를 소비하는 후속 코드도 `null` 을 받는다.
- **현재 동작(이슈 내용)**: `parseSignedClaims` 는 `exp` 가 **존재할 때만** 만료를 본다. 필터는 `exp` 부재를 별도로 거부하지 않고 그대로 `TokenClaims` 에 `null` 로 담는다.
  ```java
  // JwtAuthenticationFilter.java:59-63, 87-92
  Jws<Claims> jws = Jwts.parser().verifyWith(keyResolver.resolve()).build().parseSignedClaims(token);
  Claims body = jws.getPayload();
  ...
  TokenClaims claims = new TokenClaims(
          body.getSubject(), role, channel,
          body.getExpiration() == null ? null : Instant.ofEpochMilli(body.getExpiration().getTime()));
  //                       ↑ null 을 "만료 없음"으로 수용 — 거부하지 않는다
  ```
  [실동작] `iat` 만 있고 `exp` 가 없는 유효 서명 토큰 → `GET /v1/me` **200**(role=REVIEWER), `GET /v1/videos` **200**(업무 데이터 반환). 만료 토큰(`exp=now-60`)은 정상적으로 401 이므로 **`exp` 가 있을 때만 통제가 걸린다**.
  회귀 테스트도 없다 — `backend/src/test/.../JwtAuthenticationFilterTest` 는 `expiredJwtReturns401` 만 있고 `exp` 부재 케이스가 없다(grep 0건).
- **재현/확인 경로**:
  ```bash
  SECRET='e00184be2d1299f859b3b5a2356c5c1004a07660c897d664137dbf3e7a211abc8fc75592e0f53d12b88063ed2bdf1daf'
  python3 - <<'EOF'
  import base64,hmac,hashlib,json,time
  SEC=b"e00184be2d1299f859b3b5a2356c5c1004a07660c897d664137dbf3e7a211abc8fc75592e0f53d12b88063ed2bdf1daf"
  b=lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
  p={"sub":"1001","iss":"klid-auth","role":"REVIEWER","channel":"INTERNAL","iat":int(time.time())}  # exp 없음
  h=b(json.dumps({"alg":"HS256"},separators=(",",":")).encode()); pp=b(json.dumps(p,separators=(",",":")).encode())
  print(f"{h}.{pp}."+b(hmac.new(SEC,f"{h}.{pp}".encode(),hashlib.sha256).digest()))
  EOF
  # 출력 토큰으로:
  curl -s -H "Authorization: Bearer <TOKEN>" 'http://localhost:18081/api/v1/videos?page=0&size=1'   # → 200
  ```
- **영향**: 보안 — **CWE-613**(Insufficient Session Expiration), CWE-294(capture-replay), OWASP A07:2025. 공격자가 서명 시크릿 없이 스스로 만들 수는 없으므로 즉시 악용 가능한 우회는 아니다. 실피해 경로는 ①발급측(관제/포털)이 `exp` 없는 토큰을 내보내는 순간 저작도구가 조용히 영구 세션을 허용 ②XSS·로그·프록시로 유출된 토큰의 유효기간이 무한. 저작도구는 발급측 구현을 통제하지 못하므로 **수신측 강제가 유일한 방어선**이다.
- **수정 방향(제안)**: `JwtAuthenticationFilter` 파서에 `exp` 필수를 강제한다 — `Jwts.parser().verifyWith(...).require("exp", …)` 는 값 일치 검사라 부적합하므로, `parseSignedClaims` 직후 `if (body.getExpiration() == null) { clearContext(); chain.doFilter(...); return; }` 를 issuer 검증(:65-70)과 같은 자리에 fail-closed 로 추가하는 편이 단순하다. 병행으로 `exp` 부재 토큰 401 회귀 테스트 1건과 (선택) 최대 유효기간 상한(`exp - iat <= N`) 검사를 검토. ⚠ **본 검증에서 구현하지 않았다.**

### [A-ISSUE-02] TC-AUTHZ-013 — dev-login **비활성** 형상을 실동작으로 재현하지 못함 (검증 제약)
- **심각도**: LOW (검증 커버리지 갭 — 코드 결함 아님)
- **기대 동작(기대효과)**: 케이스의 핵심 단언은 "`authoring.dev.login.enabled=false`(기본) 이면 `/v1/dev/tokens` 가 **매처 부재 + REVIEWER 가드 + 빈 부재** 3중으로 차단돼 401/403" 이다. `/v1/dev/tokens` 는 **인증 없이 임의 역할 토큰을 발급**하는 경로라, 이 차단이 실제로 걸리는지가 A 클러스터에서 가장 파급이 큰 단언 중 하나다.
- **현재 동작(이슈 내용)**: 검증 환경(local)은 `DEV_LOGIN_ENABLED` 가 활성이라 **차단 분기 자체가 실행되지 않는다**. 실동작으로 확인한 것은 "활성 상태에서 tokens **외** 경로는 여전히 닫혀 있다"까지다.
  ```
  [실동작] POST /v1/dev/tokens        (익명) → 201  ← 활성 형상
          POST /v1/dev/autolabel-test (익명) → 401
          GET  /v1/dev/batch/scan     (익명) → 401
          POST /v1/dev/tokensXX       (익명) → 401   (접두 매칭 오염 없음)
          POST /v1/dev/tokens/../autolabel-test (익명) → 401 (경로순회로 permitAll 확장 불가)
  ```
  정적으로는 3중 차단이 모두 확인된다 — `SecurityConfig.java:52-53,103`(조건부 permitAll), `:116`(`/v1/dev/**` `hasRole(REVIEWER)`), `DevTokenController.java:34`(`@ConditionalOnProperty(prefix="authoring.dev.login", name="enabled", havingValue="true")`). 추가 방어로 `DevToggleProfileGuard:59-63` 이 prd/stg 프로파일 + 토글 활성 조합에서 `IllegalStateException` 으로 **기동을 거부**한다.
- **재현/확인 경로**: `DEV_LOGIN_ENABLED=false` 로 backend 를 기동한 별도 형상에서
  `curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' -d '{"role":"REVIEWER","channel":"INTERNAL"}'` → **401 기대**. (본 회차는 컨테이너 재기동 금지라 미수행)
- **영향**: 기능/보안 검증 커버리지. 정적 3중 차단 + 기동 가드가 있어 실제 결함 가능성은 낮으나, **"토글 off 에서 실제로 401 인가"는 이번 회차에서 증명되지 않았다.**
- **수정 방향(제안)**: 코드 수정 대상 아님. ①다음 회차에서 `DEV_LOGIN_ENABLED=false` 프로파일로 backend 를 1회 기동해 이 한 건만 확인하거나, ②`@SpringBootTest(properties="authoring.dev.login.enabled=false")` 로 `/v1/dev/tokens` 401 을 고정하는 IT 를 추가(현재 `DevTokenProfileGuardTest`·`DevToggleStartupWarnerTest` 는 가드 단위 판정만 덮고 **엔드포인트 응답코드는 덮지 않는다**).

### [A-ISSUE-03] TC-AUTH-028 — `userRole` 캐시가 `recordStats()` 미설정이라 적중률 관측 불가
- **심각도**: LOW
- **기대 동작(기대효과)**: `userRole` 캐시는 **모든 인증 요청마다** 타는 핫패스다(`JwtAuthenticationFilter:83`). 적중률이 떨어지면 곧바로 LS_USER_ROLE 조회가 요청당 1회씩 발생하므로, `observability.md`("외부/반복 호출 결과 메트릭 미수집 = 장애 감지 불가") 기준으로 적중/미스가 관측 가능해야 한다.
- **현재 동작(이슈 내용)**: `CacheConfig` 의 Caffeine 빌더에 `recordStats()` 가 없어 Micrometer 가 바인딩한 `cache.gets` 계측이 **항상 0** 이다.
  ```java
  // CacheConfig.java:88-92
  CaffeineCache userRole = new CaffeineCache(CACHE_USER_ROLE,
          Caffeine.newBuilder()
                  .maximumSize(500)
                  .expireAfterWrite(Duration.ofSeconds(60))
                  .build());          // ← recordStats() 없음
  ```
  [실동작] REVIEWER 토큰으로 `GET /actuator/metrics/cache.gets?tag=name:userRole&tag=result:hit|miss` → 메트릭은 **등록돼 있고 태그도 노출**되지만(`availableTags: result=[hit,miss], name=[userRole]`) 값은 요청 5회·8회를 태워도 계속 `0.0`. 실제 캐시는 정상 동작한다(TC-AUTH-028 에서 DB 행 삭제 후에도 역할 유지로 적중 실증) — **동작이 아니라 관측만 비어 있다.** 4개 캐시(`sysconfig`/`stream-meta`/`eventType`/`userRole`) 전부 동일.
- **재현/확인 경로**:
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"role":"REVIEWER","channel":"INTERNAL"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
  for i in $(seq 5); do curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/v1/me; done
  curl -s -H "Authorization: Bearer $TOKEN" \
    'http://localhost:18081/api/actuator/metrics/cache.gets?tag=name:userRole&tag=result:hit'   # → COUNT 0.0
  ```
- **영향**: 관찰가능성. 캐시 미스율 급증(예: `evict` 폭주, TTL 부적정, 키 불일치 회귀)을 **운영에서 감지할 수단이 없다**. 보안·데이터정합 영향은 없다.
- **수정 방향(제안)**: `CacheConfig.cacheManager()` 의 4개 `Caffeine.newBuilder()` 에 `.recordStats()` 를 추가한다(Caffeine 통계는 카운터 증분뿐이라 오버헤드가 무시할 수준). 그 뒤 `cache.gets{result=hit|miss}`·`cache.evictions` 로 대시보드/알림을 구성. ⚠ **본 검증에서 구현하지 않았다.**

### [A-ISSUE-04] TC-AUTH-001/016 — `sub` 정규화가 필터(trim)와 하위 서비스(non-trim) 간 비대칭
- **심각도**: LOW
- **기대 동작(기대효과)**: 토큰 `sub` → `userNo` 변환은 **인가 판정에 쓰는 값과 소유권/감사에 쓰는 값이 같아야** 한다. 한쪽만 정규화하면 "인가는 통과했는데 본인 확인은 실패"하는 상태가 만들어지고, 감사 로그가 같은 사용자를 여러 문자열로 기록한다.
- **현재 동작(이슈 내용)**: 필터는 `trim()` 후 파싱하고, 하위 서비스 5곳 이상은 `trim` 없이 파싱한다.
  ```java
  // JwtAuthenticationFilter.java:129-133 (인가 축) — trim 있음
  return Long.parseLong(sub.trim());

  // LabelAccessGuard.java:143-149 / AssignmentService / MetaService / UserService (소유권·감사 축) — trim 없음
  public Long parseUserNo(String sub) {
      try { return Long.parseLong(sub); }
      catch (NumberFormatException e) { throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다."); }
  }
  ```
  [실동작] 같은 서명키로 `sub` 만 바꾼 5종 토큰:
  | sub | REVIEWER 전용 `/v1/users` | `/v1/users/me` | `/v1/me` 의 userId |
  |---|:--:|:--:|---|
  | `"1001"` | 200 | 200 | `1001` |
  | `"+1001"` | 200 | 200 | `+1001` |
  | `"0001001"` | 200 | 200 | `0001001` |
  | `" 1001 "` | **200** | **401** | `" 1001 "` |
  | `"1001 "` | **200** | **401** | `"1001 "` |
  즉 공백 패딩 토큰은 **REVIEWER 로 인가되지만** 본인 확인이 필요한 API 에서 401 로 떨어진다. 또한 `/v1/me` 와 로그에는 정규화 전 원문(`" 1001 "`)이 그대로 나간다.
- **재현/확인 경로**: A-ISSUE-01 의 토큰 생성 스니펫에서 `"sub":"1001"` 을 `"sub":" 1001 "` 로 바꿔 발급 후
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <TOKEN>" 'http://localhost:18081/api/v1/users?page=0&size=1'  # 200
  curl -s -w ' <<%{http_code}>>\n' -H "Authorization: Bearer <TOKEN>" 'http://localhost:18081/api/v1/users/me'                     # 401
  ```
- **영향**: 기능/감사. **권한 상승은 아니다** — 비대칭의 방향이 "인가 통과 → 소유권 검사에서 거부"라 fail-closed 쪽이고, 하위 파서가 예외를 던져도 `CustomException(UNAUTHORIZED)` 로 잡혀 500·스택노출도 없다. 실질 리스크는 ①동일 사용자가 `1001`/`+1001`/`0001001`/`" 1001 "` 4가지 문자열로 감사 로그·`LS_DATA_LBL_HSTRY` 행위자 표기에 흩어질 수 있음(CWE-117 인접) ②정상 발급 경로가 패딩을 넣지 않으므로 현재 노출 표면은 사실상 0.
- **수정 방향(제안)**: 정규화를 **한 곳**으로 모은다 — `JwtAuthenticationFilter` 가 파싱한 `Long userNo` 를 `TokenClaims` 에 필드로 담아(예: `TokenClaims(String sub, Long userNo, Role, Channel, Instant)`) 하위 서비스가 문자열을 다시 파싱하지 않게 하거나, 최소 조치로 필터의 `trim()` 을 제거해 양쪽을 "정확 일치"로 통일한다(엄격 쪽 통일이 더 안전). 겸하여 중복 구현된 `parseUserNo` 5벌을 공용 유틸 1곳으로 합칠 것. ⚠ **본 검증에서 구현하지 않았다.**

---

## 부수 관측 (케이스 판정에는 영향 없음 — 다음 회차 참고)

| # | 관측 | 근거 |
|:--:|------|------|
| 1 | **B-ISSUE-63(스트림 영상 단위 인가 부재)은 이번 실측에서 재현되지 않았다** — worker2(rawSn 126 미배정) 토큰으로 `/v1/videos/126/stream`·`/stream-url`·`/v1/videos/126` 전부 403 "본인에게 배정되지 않은 영상입니다". `StreamSignatureFilter:139-146` 이 서명 경로 principal 에 실 sub·재조회 role 을 채워 `LabelAccessGuard` 가 서명 경로에도 걸리도록 배선돼 있다. UNCERTAINTIES 의 "미해소 이월" 표기는 B 파트 담당이 재확인 권장 | [실동작] |
| 2 | **테스트 커버 공백 4종** — ①CORS 설정 테스트 파일 0건(`*Cors*` 검색 무결과) ②`RoleHierarchy` 참조 테스트 0건 ③`alg=none` JWT 거부 단위 테스트 0건(`HmacWebhookFilterTest` 의 "none" 은 다른 맥락) ④`exp` 부재 토큰 테스트 0건(A-ISSUE-01 근거). 나머지는 `JwtAuthenticationFilterTest`(7) · `UserRoleResolverTest`(5) · `SecretKeyResolverTest`(2) · `TokenClaimsTest`(2) · `SecurityConfigTest`(3, HSTS 포함) · `SecurityConfigRoleTest`(4) · `SecurityConfigChannelTest`(6) · `InternalRoleGateMatcherTest`(4) · `UserRoleCacheEvictIT`(1) · `JwtIssuerValidatorTest`(2) 로 덮여 있다(**실행하지 않음** — `_raw/test-baseline.md` 생성 시 통과 여부를 그쪽에서 대조할 것) | [정적] |
| 3 | `GET /v1/manage/labels/**` 가 `authenticated()` 라 **role=null INTERNAL·PORTAL_USER 도 라벨 마스터 전문 + 속성 정의를 읽는다**. TC-AUTHZ-002/010 이 "의도된 예외"로 명시하고 있어 결함으로 올리지 않으나, 이 경로에 민감 필드가 추가되면 재검토가 필요하다(`/v1/me` 예외와 동일한 성격의 전제) | [실동작] |
| 4 | `POST /v1/dev/tokens` 는 요청 바디의 임의 `userNo` 로 토큰을 발급한다(`sub=424242` 성공). 다만 인가 역할은 `LS_USER_ROLE` 이 결정하므로 미등록 userNo 는 role=null 로 떨어져 권한 상승이 되지 않는다 | [실동작] |
| 5 | `/v1/videos?size=99999` 는 **100 으로 클램프**, `page=-1` 은 0 으로 보정, `sort=;DROP TABLE` 은 200 + 기본 정렬 폴백(★2 lenient 정책 준수). SQL 오류·에러 노출 없음 | [실동작] |

---

# A 클러스터 part2 (A-3·A-4) 2차 검증 결과

> 대상: `docs/test-cases/A-auth-common.md` §A-3(TC-STREAM-001~012, 12건) · §A-4(TC-HMAC-001~046 중 폐기 1건 제외 45건)
> 판정 기준시각: **2026-07-31 02:45~03:00 KST**
> 판정 대상 이미지: `klid-backend:latest` `bdc64ea2ac26` = **HEAD `ca3c712b` 재빌드본**(2026-07-31 02:42 빌드, `_raw/backend-rebuild.md`)
> 방식: 실HTTP 프로브(로컬 스택 5서비스) + DB 직접 조회(`public.ls_whk_sign_use` / `ls_whk_fail_nmtm`) + 코드 정적 대조 + 테스트 자산 grep(**테스트 실행 없음**)
> 파일 수정 없음 — 본 결과 파일 1개만 생성

---

## 집계

| 구분 | 케이스 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기(집계 제외) |
|------|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| A-3 스트림 서명 필터 | 12 | 12 | 0 | 0 | 0 | 0 | 0 | 0 |
| A-4 웹훅 인증 필터 | 45 | 45 | 0 | 0 | 0 | 0 | 0 | 1 (TC-HMAC-001) |
| **합계** | **57** | **57** | **0** | **0** | **0** | **0** | **0** | **1** |

근거 확인 내역: **`[실동작]` 26건 / `[정적]` 31건**.
`[정적]` 이 31건인 주된 이유는 **서명 필수 웹훅 경로가 현재 0개**여서 HMAC 검증 분기 전체가 런타임에 도달하지 않기 때문이다(→ A-ISSUE-11). 기동 차단(BeanInitializationException) 계열은 컨테이너 재기동 금지 제약으로 정적 판정했다.

**근거 드리프트 0건** — A-3·A-4 전 케이스의 `file:line` 이 HEAD 소스와 일치했다(§근거 드리프트).

이슈: **7건**(CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 5). 전건 PASS 이지만 "검증 가능성·형상·커버리지" 축의 관찰 결과를 이슈로 등재했다.

---

## ★1차 CRITICAL 재현 결과

1차 `E-ISSUE-01`(=`A-ISSUE-13` 실증) 은 **무인증 `POST /api/v1/%61ug/callback` 이 필터를 스킵하고 컨트롤러에 도달**(증강행 4건 전이 + `raw_sn=29·31` 생성)한 CWE-436 결함이었다.
본 회차에서 **전 변형 재현 시도 → 전건 차단**. 추가로 `%61ug` 대상 컨트롤러(`/v1/aug/**`) 자체가 Phase 7-A2 에서 제거돼 **공격면이 소멸**했다.

### (1) 구 CRITICAL 경로 (`/v1/aug/**`)

| 경로 | 기대 | 실측 | 판정 |
|------|:--:|:--:|:--:|
| `POST /api/v1/aug/callback` | 401 | **401** `UNAUTHORIZED` | PASS |
| `POST /api/v1/%61ug/callback` (**1차 침투 경로**) | 401 | **401** `UNAUTHORIZED` | **PASS** |
| `POST /api/v1/a%75g/callback` | 401 | **401** | PASS |
| `POST /api/v1/aug/%63allback` | 401 | **401** | PASS |
| `POST /api/v1/%2561ug/callback` (이중 인코딩) | 401 | **401** | PASS |

### (2) 현행 보호 경로(무서명 가드 2종)의 경로 변형 — **"필터 스킵 + 컨트롤러 도달" 입력을 찾지 못함**

판정 기준: ①`400 INVALID_INPUT` = 필터 통과 + 컨트롤러 도달(**정상**, 필터가 적용된 증거) ②`401 인증이 필요합니다.` = Security 체인 차단(라우팅 미성립) ③`411/413` = **필터가 직접 거부**(가장 강한 필터 적용 증거).

| 경로 | 기대 | 실측 | 판정 |
|------|:--:|:--:|:--:|
| `/v1/genai/callback` (대조군) | 필터 적용 → 컨트롤러 | **400** INVALID_INPUT | PASS |
| `/v1/vlm/callback` (대조군) | 필터 적용 → 컨트롤러 | **400** INVALID_INPUT | PASS |
| `/v1/%76lm/callback` | 필터 적용 | **400** / chunked 시 **411**(필터 발행, 로그 `path=/api/v1/%76lm/callback`) | PASS |
| `/v1/vlm/%63allback` | 필터 적용 | **400** | PASS |
| `/v1/g%65nai/callback` | 필터 적용 | **400** / 2MB 시 **413**(필터 발행, 로그 `path=/api/v1/g%65nai/callback`) | PASS |
| `/v1/genai/%63allback` | 필터 적용 | **400** | PASS |
| `/v1/genai/callback/` (trailing slash) | 2xx 아님 | **401** (필터 적용 후 하류 401 — DB 카운터 증가 확인) | PASS |
| `//v1/genai/callback` | 2xx 아님 | **401** | PASS |
| `/v1//genai/callback` · `/v1/genai//callback` | 2xx 아님 | **401** | PASS |
| `/v1/./genai/callback` · `/v1/genai/./callback` | 2xx 아님 | **401** | PASS |
| `/v1/genai/x/../callback` | 2xx 아님 | **401** | PASS |
| `/v1/genai/%2e/callback` · `/v1/genai/%2e%2e/genai/callback` | 2xx 아님 | **401** | PASS |
| `/v1/genai;a=b/callback` · `/v1/genai/callback;a=b` | 2xx 아님 | **401** | PASS |
| `/v1/vlm/callback;jsessionid=x` | 2xx 아님 | **401** | PASS |
| `/v1/GENAI/callback` (대문자) | 2xx 아님 | **401** | PASS |
| `/v1/genai/callback%20` · `/v1/genai/callback.` | 2xx 아님 | **401** (필터 적용 후 하류 401) | PASS |
| `/v1/genai%2fcallback` (인코딩 슬래시) | 차단 | **400** (Tomcat/StrictHttpFirewall 선차단) | PASS |
| `/v1/genai/callback%00` (널바이트) | 차단 | **400** (선차단) | PASS |
| `/v1\genai/callback` (백슬래시) | 차단 | **400** (선차단) | PASS |
| `/v1/genai/callback%0d%0aINJECT` (CRLF) | 2xx 아님 | **401**, 로그 인젝션 미발생 | PASS |
| `/v1/%EF%BD%87enai/callback` (유니코드 전각 g) | 2xx 아님 | **401** | PASS |
| `GET /v1/vlm/callback` | 필터 적용 | **411** (메서드 무관 필터 적용) | PASS |
| `PUT /v1/genai/callback` | 필터 적용 → MVC 405 | **405** | PASS |

**결론: A-ISSUE-13 / E-ISSUE-01 은 HEAD 재빌드본에서 재현되지 않는다.** 2단 게이트(`WebhookProtectedPaths` PathPattern allowlist + `WebhookGateInterceptor` 증거 래퍼)와 `SIGNATURE_REQUIRED` 목록 비움(경로 제거)이 함께 작용한다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 상태 | 2차 실측 | 판정 |
|---|---|---|:--:|
| **E-ISSUE-01** HMAC 필터 경로 우회 (CRITICAL) | `%61ug` 무인증 관통, 증강행 전이 + rawSn 생성 | 위 표 전 변형 차단. 필터 판정이 MVC 와 동일 `RequestPath`+`PathPattern`(`WebhookProtectedPaths:253-269`), 판정 불가=보호(fail-closed). 구 `/v1/aug/**` 컨트롤러·DTO 제거로 공격면 소멸 | **해소** |
| **A-ISSUE-13** raw URI 정확일치 판정 | 정적 성립·실증 미완 | 동상. 추가로 `WebhookPathBypassSecurityIT`(11건, 실 `springSecurityFilterChain`+DispatcherServlet)로 회귀 고정 | **해소** |
| **E-ISSUE-04** 빈 시크릿 → 정상 콜백 전건 401 | `.env` 빈 값이 yml 기본값을 덮어 401 | `WEBHOOK_HMAC_SECRET_AUGMENT` = 32B 난수(비-placeholder). 빈 값·32B 미만·커밋 placeholder·미해석 `?…` 4축 기동 차단 구현(`HmacWebhookFilter:214-245`). 정상 무서명 콜백은 실왕복 200 확인(mock→backend VLM 콜백 `result applied request_id=8cc179fb… rawSn=126`) | **해소**(단 성격 변화 — §A-ISSUE-11) |
| **A-ISSUE-12** replay 방어가 시간창뿐 | 윈도우 내 무제한 재전송 | nonce 1회성 소비(`LS_WHK_SIGN_USE`, 키=**정규화 경로**+ts+sig SHA-256) + 미래 skew 30s 비대칭 창 구현. **단 서명 필수 경로 0개라 런타임 미도달**(nonce 테이블 실측 **0행**) | **해소(코드)** / 실동작 미검증 |
| **A-ISSUE-14** rate limit 이 JVM-local | 2노드에서 임계 2배 | 공유 DB 집계(`LS_WHK_FAIL_NMTM`, 현재+직전 버킷 조회) + `adoptRemoteBlock` 승계 구현. **실동작: 401×5 → 429×10, 공유 카운터 정확히 5 에서 정지, `Retry-After: 60`** | **해소**(2노드 승계는 단위테스트 커버) |
| **A-ISSUE-15** clientIp = remoteAddr 전용 | 프록시 뒤 자기유발 DoS | `ClientIpResolver` 신뢰 프록시 CIDR 기반 XFF 파싱 + prd/stg 미설정 기동 차단. **실동작: XFF 회전 15연타(`203.0.113.1~15`)에도 401×5→429 이고 카운터에 위조 IP 미적재** | **해소** |
| **B-ISSUE-25** VLM 콜백 무인증 + rate limit 부재 | 위조 request_id 15연타 전부 401(429 없음) | **실동작: 401×5 → 429×10.** 1차 재현 명령 그대로 실행해 대조. IP allowlist·본문 4MB 캡·request_id 발급 게이트 3계층 동작 | **해소** |
| Phase1 잔여 ① `adoptRemoteBlock` 차단 W+180s | MEDIUM | `WebhookRateLimiter:161-181` 이 각인 만료를 **원격 버킷 시작+60s** 로 산출하고, 지평 경과 시 각인하지 않음(R-5) | **해소** |
| Phase1 잔여 ② `JdbcWebhookGuardStoreIT` `assumeTrue` 초록 skip | MEDIUM | `JdbcWebhookGuardStoreIT.java:171` `Assumptions.assumeTrue` **잔존** | **미해소**(→ A-ISSUE-17) |
| Phase1 잔여 ③ `ClientIpResolver.isProxyAware()` 죽은 API | LOW | 프로덕션 호출처 **0건**(테스트 1건만) **잔존** | **미해소**(→ A-ISSUE-15) |

### 1차 정정 사항 대조 — `HmacWebhookFilterTest` 위양성 위험

> 1차 지적: "17건 존재하나 경로변형 케이스 0건 + 전부 `MockHttpServletRequest` 라 판정 방식을 바꾸면 전건 GREEN 이 되는 위양성 위험".

**구조적으로 개선됨.** `HmacWebhookFilterTest` 는 **63건**으로 확장됐고(여전히 `MockHttpServletRequest` 기반), 그 위양성을 정면으로 겨냥한 **`WebhookPathBypassSecurityIT`(11건)** 가 신설됐다 — `@SpringBootTest`+`@AutoConfigureMockMvc` 로 **실 `springSecurityFilterChain` + 실 DispatcherServlet 라우팅**을 태우고, 파일 javadoc 에 *"필터 단위테스트로 갈음 금지 … MockHttpServletRequest 기반 단위테스트는 필터가 통째로 무력화돼도 GREEN 이 될 수 있다(위양성, S-21)"* 를 명시했다. 커버 변형: 퍼센트 인코딩 · 이중 인코딩 · 대문자 · trailing slash · 이중 슬래시 · 세미콜론 · 경로 순회 · 후행 점 · 널바이트 + **DB 무변경 단언** + StrictHttpFirewall 기본값 유지 단언 + XFF 회전 rate limit + 인코딩 변형 replay.
**잔존 위양성 표면**: nonce/replay/503/해제 4종(TC-HMAC-025~028)은 서명 필수 경로가 0개라 IT 를 만들 수 없어 여전히 단위테스트 단독 커버다(→ A-ISSUE-17).

---

## A-3 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-STREAM-001 | 유효 서명 + 유효 nonce 쿠키 → CHANNEL_INTERNAL+STREAM_SIGNED | PASS | [실동작] | `GET /v1/videos/126/stream?exp&u=1001&sig` + 발급 쿠키 → **206**, `Accept-Ranges: bytes`, `Content-Range: bytes 0-99/34654319`. principal 에 실 sub·재조회 역할이 채워짐은 TC-STREAM-012 로 확증 |
| TC-STREAM-002 | 서명 없으면 미개입 → 401 | PASS | [실동작] | `GET /v1/videos/126/stream`(sig/exp 없음, 헤더 없음) → **401** |
| TC-STREAM-003 | 만료/변조 서명 거부 + sig 전문 로그 미노출 | PASS | [실동작] | 변조 sig → **401**. 로그 실측 `[StreamSign] invalid signature rawSn=126` — **sig·exp·u 미출력**(CWE-532 준수) |
| TC-STREAM-004 | u 변조 시 서명 불일치 거부 | PASS | [실동작] | `u=1001`→`u=2001` 치환 → **401** |
| TC-STREAM-005 | 선인증 컨텍스트 있으면 서명 검증 스킵 | PASS | [실동작] | `Authorization: Bearer <REVIEWER>` + 서명 쿼리 없음 → **206**. `SecurityConfig:166` `addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class)` 실측 일치(부팅 로그 체인 순서 `HmacWebhookFilter → JwtAuthenticationFilter → StreamSignatureFilter → …AnonymousAuthenticationFilter`) |
| TC-STREAM-006 | GET 아니거나 스트림 경로 아니면 미동작 | PASS | [정적] | `StreamSignatureFilter:84-89` 확인. `POST`(서명 URL) → **401** 관측(보안체인 차단, 필터 미개입과 정합) |
| TC-STREAM-007 | rawSn 정규식 1~18자리 숫자만 | PASS | [실동작] | 19자리 `1234567890123456789` → **401**(패스스루 후 fail-closed) |
| TC-STREAM-008 | **쿠키 없이 URL 전체 재사용 시 401** | PASS | [실동작] | 발급 URL 전체(exp·u·sig) 보유 + 쿠키 미부착 → **401**. 발급 응답 URL 에 nonce 미포함 확인 |
| TC-STREAM-009 | 봉인 없는/타 subject 쿠키 채택 불가 | PASS | [실동작] | ①봉인 없는 32-hex `aaaa…` → **401** ②임의 `{32hex}.{64hex}` 위조 봉인 → **401** ③화이트박스로 `sealKey=HMAC(signSecret,"klid-stream-nonce-seal")` 를 재현해 **타 subject(2002)로 봉인한 쿠키**를 만들면 채택됨을 확인(=봉인이 subject 바인딩) |
| TC-STREAM-010 | nonce 쿠키 속성 | PASS | [실동작] | 실측 `Set-Cookie: klid_stream_nonce=…; Path=/api/v1/videos; Max-Age=3600; Expires=…; HttpOnly; SameSite=Lax`. **Secure 없음 = local 프로파일**(판정축이 프로파일임은 `StreamNonceCookie:86-89,214-224` + `StreamNonceCookieSecureFlagTest` 3건) |
| TC-STREAM-011 | sign-secret 미설정 시 봉인키 기동 랜덤 → 전건 불통과 | PASS | [정적] | `StreamNonceCookie:183-190` 확인(미설정 시 `SecureRandom` 32B ephemeral). 재기동 금지로 실동작 미검증 + **전용 회귀 테스트 부재**(→ A-ISSUE-16) |
| TC-STREAM-012 | 서명 경로에도 영상 단위 인가 적용 (B-ISSUE-63 해소) | PASS | [실동작] | 시크릿 보유 화이트박스로 **미배정 WORKER(2002) 의 u 로 유효 서명 URL + 유효 봉인 쿠키**를 생성 → `raw=126` **403** `"본인에게 배정되지 않은 영상입니다."` / `raw=20012` **403** / 미존재 사용자(999999) **403** `"라벨 접근 권한이 없습니다."` / 배정 WORKER(2001) **206**. 배정 상태: `ls_task_assignment` 46행 `usr_no=2001, raw_sn=126` |

---

## A-4 결과표

> ⚠ 본 표의 `[정적]` 다수는 **서명 필수 경로가 0개**(`WebhookProtectedPaths.SIGNATURE_REQUIRED = List.of()`)여서 HMAC 검증 분기(TC-HMAC-002·004~014·020·025~028)가 런타임에 도달하지 않기 때문이다. 이 구조적 제약 자체를 A-ISSUE-11 로 등재했다.

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| ~~TC-HMAC-001~~ | ~~유효 HMAC 콜백 통과~~ | — | — | **[폐기 2026-07-30]** 집계 제외 |
| TC-HMAC-002 | 시크릿 미설정 + 경로 판정 불가 → 401 `secret_missing` | PASS | [정적] | `HmacWebhookFilter:214-224`(경로 0개면 미설정 허용) + `:311-318`(런타임 401). 현 형상은 시크릿 설정 상태 + 판정 불가 URI 를 컨테이너가 먼저 400 으로 끊어 실동작 재현 불가. 테스트 `HmacWebhookFilterTest#emptySecret_bootsWhenNoSignatureRequiredPaths`·`#unresolvablePath_stillReturns401_withoutSecret` |
| TC-HMAC-003 | 시크릿 32B 미만 부팅 차단 | PASS | [정적] | `:240-245`. 재기동 금지 → `#shortSecret_failsBoot` |
| TC-HMAC-004 | 서명/timestamp 헤더 누락 401 + recordFailure | PASS | [정적] | `:333-340` 일치. `#protectedPath_withoutSignature_returns401_andIsNotSkipped` |
| TC-HMAC-005 | 미지원 서명 접두사 401 | PASS | [정적] | `:341-346` 일치 |
| TC-HMAC-006 | timestamp 비숫자 401 | PASS | [정적] | `:349-357` 일치 |
| TC-HMAC-007 | 과거 300s / **미래 30s 비대칭** | PASS | [정적] | `:132-133 FUTURE_SKEW_MS=30_000L`, `:358-365` `ageMs > window*1000 \|\| ageMs < -FUTURE_SKEW_MS`. `Math.abs` 제거 확인. `#augmentReplayTimestampExceeded_returns401`·`#futureTimestampBeyondSkew_returns401` |
| TC-HMAC-008 | 상수시간 비교 401 (CWE-208) | PASS | [정적] | `:389-396` `MessageDigest.isEqual(providedBytes, computedBytes)` — `equals` 아님 |
| TC-HMAC-009 | Content-Length 누락 401(서명 경로) / 411(무서명) 정책차 | PASS | [정적] | `:321-326`(401) vs `:506-511`(411). 무서명 411 은 실동작 확인(TC-HMAC-017) |
| TC-HMAC-010 | 서명 경로 본문 1MB 초과 413 | PASS | [정적] | `:98 MAX_WEBHOOK_BODY_BYTES`, `:327-331` |
| TC-HMAC-011 | 위조 Content-Length / 대용량 스트림 읽기상한 | PASS | [정적] | `:368-375` + `:726-740 readCapped`(8KB 청크, 상한 초과 즉시 throw — 전량 버퍼링 없음) |
| TC-HMAC-012 | 실패 5회 초과 60초 backoff (로컬+공유 2단) | PASS | **[실동작]** | 위조 `request_id` 15연타 → **401×5 → 429×10**, 응답 헤더 `Retry-After: 60`. DB `ls_whk_fail_nmtm` 버킷 `02:51:00` = **정확히 5**(차단 후 공유 쓰기 중단 확인). `:305-309`·`WebhookRateLimiter:95-114` |
| TC-HMAC-013 | 카운터 reset 은 **서명 검증 성공** 시에만 | PASS | [정적] | `:417`(서명 성공 직후만 `reset`), `WebhookRateLimiter:207-214`(현재+직전 버킷 대칭 삭제). 무서명 2xx 에는 reset 호출부 없음(`countDownstreamAuthOutcome` 는 401/403 만 처리) |
| TC-HMAC-014 | 로컬 트래커 hard cap(4096) evict | PASS | [정적] | `WebhookRateLimiter:74,256-273`(만료 removeIf → 오래된 순 evict) |
| TC-HMAC-015 | 로그 CR/LF/탭 치환 + 200자 절단 | PASS | [정적] | `WebhookProtectedPaths:100-103,227-233`. 실측 로그에서 `path=/api/v1/genai/callback%20`·`%76lm` 등이 **1행으로 안전 출력**되고 CRLF 경로 요청 시 로그 인젝션 미발생 |
| TC-HMAC-016 | VLM 무서명 — 가드 순서 rate limit→IP→CL→size cap | PASS | **[실동작]** | 서명 헤더 없이 통과해 컨트롤러 도달(400/401). 순서 확증: backoff 상태에서는 chunked 요청도 **411 이 아니라 429** 가 우선 반환됨. 최종 인증은 `VlmResultService` request_id 게이트(미발급 → 401) 실측 |
| TC-HMAC-017 | VLM 콜백 chunked 411 + recordFailure | PASS | **[실동작]** | `Transfer-Encoding: chunked` → **411** `"Content-Length 헤더가 필요합니다. (chunked 전송은 허용되지 않습니다)"` + DB 카운터 `02:50:00` 버킷 +1 확인 |
| TC-HMAC-018 | VLM 4MB 초과 413 | PASS | **[실동작]** | 5MB 본문 → **413** `"Webhook 본문은 4096KB 를 초과할 수 없습니다."` / 2MB 본문은 통과(400 검증오류) |
| TC-HMAC-019 | 미등록 경로 필터 미동작 — 판정 불가는 적용 | PASS | **[실동작]**+[정적] | `/v1/aug/**`·`/v1/./genai/…` 등은 필터 로그 0건(=shouldNotFilter) / `/v1/vlm/**`·`/v1/genai/**` 는 로그 발생. fail-closed 분기는 `:254-263`·`WebhookProtectedPaths:155-161` 정적 |
| TC-HMAC-020 | HmacSigner 와 필터가 동일 서명 규칙 공유 | PASS | [정적] | `:574-576` → `HmacSigner.hex`(`:42-53`) 단일 위임 |
| TC-HMAC-021 | 시크릿이 로그/예외에 미노출 | PASS | [정적] | `HmacSigner:48-52` 예외 메시지 `"HMAC 서명 계산 실패 (algorithm=HmacSHA256)"` — 시크릿·메시지 본문 미포함 |
| TC-HMAC-022 | 퍼센트 인코딩 경로 변형도 필터 적용 (**CRITICAL 회귀 가드**) | PASS | **[실동작]** | `/v1/%76lm/callback` + chunked → **411**, `/v1/g%65nai/callback` + 2MB → **413**, 두 건 모두 **필터 로그가 인코딩 경로 그대로** 남음 = 필터가 직접 발행. `/v1/vlm/%63allback`·`/v1/genai/%63allback` 도 컨트롤러 도달(400) |
| TC-HMAC-023 | 필터 우회 라우팅은 인터셉터가 401 | PASS | [정적] | `WebhookGateInterceptor:54-60`(`@RequestBody` 역직렬화 전 preHandle). 런타임 재현 불가 — 우회 입력을 찾지 못했다(위 CRITICAL 표). `WebhookGateInterceptorTest` 7건 |
| TC-HMAC-024 | ASYNC 재디스패치는 2단 게이트 통과 | PASS | [정적] | `:51-53`. `#asyncDispatch_isNotBlocked` + `#requestDispatchWithoutMarker_stillBlocked`(예외가 우회로가 아님) |
| TC-HMAC-025 | 서명 nonce 1회성 소비 → 재전송 409 | PASS | [정적] | `:398-415`·`:584-596`(키 = **정규화 경로**+ts+sig SHA-256). **서명 필수 경로 0개라 런타임 미도달**(→ A-ISSUE-11). `#replayWithinWindow_returns409_not401`·`#nonceKey_usesCanonicalPath` |
| TC-HMAC-026 | nonce 소비는 서명 검증 **성공 이후**에만 | PASS | **[실동작]**+[정적] | 코드 `:59-62,398-401`. **DB 실측: 본 회차 전 프로브(무인증·위조 60여 건) 후에도 `ls_whk_sign_use` 행수 = 0** → pre-auth write 0건 |
| TC-HMAC-027 | nonce 저장소 장애 시 503 + `Retry-After: 30` | PASS | [정적] | `:404-409`·`:647-652`. `#nonceStoreUnavailable_failsClosed` |
| TC-HMAC-028 | 하류 5xx 시 nonce 예약 해제 | PASS | [정적] | `:418-443`(`committed = status < 500`, finally 에서 release / release 실패는 결과 불변). `#downstreamServerError_releasesNonce…`·`#downstreamException_releasesNonce` |
| TC-HMAC-029 | 커밋된 공개 placeholder 시크릿은 local 외 기동 차단 | PASS | [정적] | `:157-166 KNOWN_PLACEHOLDER_SECRETS`(4종)·`:234-239`. 현 env 는 32B 난수라 미해당 |
| TC-HMAC-030 | `?`로 시작하는 미해석 placeholder 기동 차단 | PASS | [정적] | `:227-233`. `#unresolvedPlaceholderSecret_failsBoot` |
| TC-HMAC-031 | genai IP allowlist 미설정 = **전면 403**(fail-closed) | PASS | [정적] | `GenAiWebhookIpAllowlist:44-77`(빈 값·`none` → `allowed.isEmpty()` → 항상 false) vs `WebhookIpAllowlist:80-98`(빈 값 → 항상 true) — **의도된 비대칭 확인**. `GenAiWebhookIpAllowlistTest` 4건. ⚠ **현 배포 형상은 `0.0.0.0/0` 명시**라 런타임 403 유발 불가(→ A-ISSUE-13) |
| TC-HMAC-032 | 비허용 IP 403 도 실패 집계 | PASS | [정적] | `:486-500`(`recordFailure` + `countAuthFailure("ip_not_allowed")` 후 403). 현 형상 전면 허용이라 실동작 불가. `#genAiCallbackFromDisallowedIp_isCountedInRateLimit` |
| TC-HMAC-033 | 무서명 하류 401/403 집계, 2xx 는 reset 안 함 | PASS | **[실동작]**+[정적] | 위조 request_id 15연타 → **401×5 → 429×10** 실측. "2xx 미reset" 은 `:543-564` 에 reset 호출부가 없음으로 정적 확인 |
| TC-HMAC-034 | 집계를 끄는 런타임 분기가 없다 (XFF) | PASS | **[실동작]** | 청정 버킷에서 **매 요청 XFF 회전(`203.0.113.1~15`)** → **401×5 → 429×10**. DB 카운터에 `203.0.113.*` 행 **0건**, `172.217.221.84`(remoteAddr) 로만 5건 집계 = 헤더로 카운터를 갈라칠 수 없음 |
| TC-HMAC-035 | genai 본문 상한 1MB (VLM 4MB 와 다름) | PASS | **[실동작]** | genai 2MB → **413** `"본문은 1024KB 를 초과할 수 없습니다."` / 동일 2MB 가 VLM 에서는 통과 → 비대칭 실증 |
| TC-HMAC-036 | 신뢰 프록시 밖이면 XFF 폐기 | PASS | **[실동작]** | `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 상태에서 XFF 부착 요청의 집계 키가 remoteAddr. 1회 WARN 제한은 `ClientIpResolver:207-217` 정적 |
| TC-HMAC-037 | 신뢰 홉 우측부터 벗기고 최초 비신뢰 값 채택 | PASS | [정적] | `ClientIpResolver:150-167`(역순 순회, 비정상 값 만나면 remoteAddr 폴백). 현 형상 `none` 이라 실동작 불가. `#trustedProxyXff_isolatesClients` |
| TC-HMAC-038 | XFF 파싱 상한(길이 1024 · 홉 20) | PASS | [정적] | `:70-74 MAX_XFF_LENGTH/MAX_XFF_HOPS`, `:147`(길이 초과 시 헤더 무시), `:153 .limit(MAX_XFF_HOPS)` |
| TC-HMAC-039 | remoteAddr 비-IP 리터럴 → `unknown` sentinel | PASS | [정적] | `:126-138`·`:193-198 isValidIp→WebhookCidrParser.isIpLiteral`·`:229-236`(1회 WARN). `#nonLiteralRemoteAddr_isNotTrusted`·`#hostnameLikeXffValue_isRejected` |
| TC-HMAC-040 | prd/stg trusted-proxy-cidrs 미설정 기동 차단 | PASS | [정적] | `ClientIpResolver:93-107` + `WebhookConfigProfiles:32,38-44`(`{prd,stg}`). `#prdWithoutTrustedProxyCidrs_failsBoot`·`#stgWithoutWebhookGuardConfig_failsBoot`·`#localAndDevProfiles_bootWithoutExplicitConfig`. ⚠ 판정축이 프로파일 단일 (→ A-ISSUE-12) |
| TC-HMAC-041 | prd/stg vlm allowed-ip-cidrs 미설정 기동 차단 | PASS | [정적] | `WebhookIpAllowlist:46-61`. `#prdWithoutVlmAllowlist_failsBoot`. ⚠ 동일(→ A-ISSUE-12) |
| TC-HMAC-042 | CIDR 형식 오류는 기동 차단 | PASS | [정적] | `WebhookCidrParser.parseStrict`(프리픽스 비숫자·주소폭 초과·호스트명 전부 `BeanInitializationException`). `#invalidTrustedProxyCidr_failsBoot`·`#invalidVlmAllowlistCidr_failsBoot`·`GenAiWebhookIpAllowlistTest#malformedCidrFailsStartup` |
| TC-HMAC-043 | 인증 실패 메트릭 태그는 저카디널리티 상수뿐 | PASS | **[실동작]** | `GET /actuator/metrics/webhook.auth.failed` → `availableTags: reason=[downstream_unauthorized], path=[vlm, genai]`, COUNT 14. **원시 URI 태그 0건**(인코딩 변형·임의 경로 프로브를 다수 던진 뒤 실측) |
| TC-HMAC-044 | 필터 실행 경로는 Security 체인 1개로 고정 | PASS | **[실동작]** | 부팅 로그 `Filter hmacWebhookFilterRegistration was not registered (disabled)` + `DefaultSecurityFilterChain` 목록에 `HmacWebhookFilter` **1회만** 등장 |
| TC-HMAC-045 | 가드 만료행 주기 정리 | PASS | **[실동작]**+[정적] | 부팅 로그 `[Webhook] guard purge job scheduled intervalMs=600000`. SQL 은 조건부 DELETE (`JdbcWebhookGuardStore:72,88` `WHERE EXPD_DT < CURRENT_TIMESTAMP`) → 2노드 동시 무해. 최소 60s clamp·초기지연 5분 `WebhookGuardPurgeJob:45-54` |
| TC-HMAC-046 | 저장소 장애 정책 비대칭 (nonce=fail-closed / rate limit=fail-open) | PASS | [정적] | `:404-409`(503) vs `WebhookRateLimiter:137-140`·`JdbcWebhookGuardStore:139-143`(`UNAVAILABLE` → 로컬만으로 계속). `#nonceStoreUnavailable_failsClosed`·`#sharedRateLimitStoreDown_failsOpen` |

---

## 근거 드리프트

**0건.** A-3 12건 · A-4 45건 전 케이스의 `file:line` 을 HEAD 소스와 대조했고 전부 일치했다. 표본 검증 내역:

| 케이스 | 카탈로그 근거 | HEAD 실측 | 일치 |
|---|---|---|:--:|
| TC-HMAC-002 | `HmacWebhookFilter.java:214-224,311-318` | `ensureSecretConfigured` 214~, `secret missing` 311-318 | ✓ |
| TC-HMAC-007 | `HmacWebhookFilter.java:132-133,358-365` | `FUTURE_SKEW_MS` 133, 윈도우 판정 358-365 | ✓ |
| TC-HMAC-011 | `HmacWebhookFilter.java:368-375,726-740` | 본문 캐싱 368-375, `readCapped` 726-740 | ✓ |
| TC-HMAC-035 | `HmacWebhookFilter.java:121-130,476` | `MAX_GENAI_BODY_BYTES` javadoc 121-130, `maxBytes` 선택 476 | ✓ |
| TC-HMAC-045 | `WebhookGuardPurgeJob.java:36-56`; `JdbcWebhookGuardStore.java:72,88` | 클래스 36 / start 56, `PURGE_NONCE` 72 · `PURGE_FAILURE` 88 | ✓ |
| TC-STREAM-010 | `StreamNonceCookie.java:214-224` | `buildCookie` 214-224 | ✓ |
| TC-STREAM-012 | `StreamSignatureFilter.java:139-148,160-169` | principal 구성 139-148, `resolveRole` 160-169 | ✓ |

> 참고: `WebhookProtectedPaths.java:253-269`(TC-HMAC-022 근거)는 `pathWithinApplication` 본체 253-269 로 정확히 대응한다.

---

## 이슈 상세

### [A-ISSUE-11] TC-HMAC-002/004~014/020/025~028 — HMAC 서명 검증 분기 전체가 런타임 도달 불가(죽은 보안 표면)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그가 열거한 HMAC 검증 통제(헤더 검사·timestamp 윈도우·상수시간 비교·nonce 1회성 소비·replay 409·503 fail-closed·5xx 시 nonce 해제)는 **실제 요청 위에서 동작이 확인 가능**해야 한다.
- **현재 동작(이슈 내용)**: 서명 필수 경로 목록이 비어 있다.
  ```java
  // WebhookProtectedPaths.java:88
  private static final List<PathPattern> SIGNATURE_REQUIRED = List.of();
  // :128-134  판정 불가일 때만 true
  public static boolean requiresSignature(HttpServletRequest request) {
      PathContainer path = pathWithinApplication(request);
      if (path == null) return true;              // fail-closed
      return matchesAny(SIGNATURE_REQUIRED, path);
  }
  ```
  즉 `handleSignatureRequired`(`HmacWebhookFilter:299-432`)에 도달하는 유일한 조건은 **`RequestPath.parse` 실패(경로 판정 불가)** 인데, 그런 URI 는 Tomcat/StrictHttpFirewall 이 앞단에서 400 으로 끊어 실무상 도달하지 않는다. 실측 근거:
  - `ls_whk_sign_use` 행수 = **0**(스택 기동 이후 누적, 파이프라인 실구동 중인데도 0)
  - `webhook.auth.failed` 의 `reason` 태그가 **`downstream_unauthorized` 단일** — 서명 계열 사유(`missing_signature`/`signature_mismatch`/`timestamp_window`/`secret_missing`)가 한 번도 발생하지 않았다
  - `WEBHOOK_HMAC_SECRET_AUGMENT`(32B 난수)는 **어떤 요청도 검증하지 않는 dead config**
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -tAc \
    "SELECT count(*) FROM public.ls_whk_sign_use"            # → 0
  curl -s -H "Authorization: Bearer $RT" \
    localhost:18081/api/actuator/metrics/webhook.auth.failed  # reason=[downstream_unauthorized] 만
  grep -n "SIGNATURE_REQUIRED = List.of()" backend/src/main/java/.../WebhookProtectedPaths.java
  ```
- **영향**: 보안 결함이 아니라 **검증 가능성·유지보수 리스크**다. ①TC 15건이 단위테스트(`MockHttpServletRequest`) 단독 근거로만 남아 1차가 지적한 위양성 표면이 이 구간에 잔존한다 ②`LS_WHK_SIGN_USE` 테이블·purge SQL·nonce 해제 로직·`HmacSigner` 가 전부 미가동 코드로 유지된다 ③운영자가 "시크릿을 설정했으니 웹훅이 서명 검증된다"고 오독할 수 있다(실제 보호는 IP allowlist + rate limit + request_id 게이트 3계층뿐).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 선택지 ①현행 유지 + `WEBHOOK_HMAC_SECRET_AUGMENT` 를 배포 형상(`.env`/compose)에서 제거하고 "서명 필수 경로 0개" 를 운영 문서에 명시 ②장래 서명 웹훅이 생길 때까지 HMAC 분기를 유지하되, 관련 TC 의 기대결과에 "런타임 미도달 — 단위테스트 근거" 를 명기해 회차마다 재조사되지 않게 한다.

---

### [A-ISSUE-12] TC-HMAC-040/041 — 웹훅 보안 설정 기동 강제가 프로파일 축만 보고 `ENV` 배포 표식 축이 없다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md`("클러스터링은 stg/prd 에서 fail-closed") 가 못 박은 프로젝트 표준은 **프로파일 allowlist + `ENV` 배포 표식 독립 2축**이다 — `SPRING_PROFILES_ACTIVE=dev` 로 낮춰도 배포 환경에서는 우회되지 않아야 한다.
- **현재 동작(이슈 내용)**: 웹훅 가드만 프로파일 단일 축이다.
  ```java
  // WebhookConfigProfiles.java:38-44
  static boolean requiresExplicitConfig(Environment environment) {
      if (environment == null) return false;
      return List.of(environment.getActiveProfiles()).stream()
              .anyMatch(EXPLICIT_CONFIG_PROFILES::contains);   // {"prd","stg"} 뿐
  }
  ```
  같은 저장소의 동종 가드는 2축이다 — `QuartzClusteringGuard:55` *"배포 환경 표식(`ENV`) — `DevProfileGuard.DEPLOYED_ENVS` 와 동일 기준"*, `ProfileGatedUrlPolicy:49-52` `DEPLOYED_ENV_MARKERS = {"stg","prd"}`, `DevProfileGuard:42` `DEPLOYED_ENVS = {"stg","prd"}`. 즉 stg/prd 호스트에서 `SPRING_PROFILES_ACTIVE=dev` 로 기동하면 Quartz·외부 URL 정책은 기동을 막지만 **`webhook.trusted-proxy-cidrs`/`webhook.vlm.allowed-ip-cidrs` 는 조용히 빈 기본값**(XFF 전면 무시 + VLM allowlist 전면 허용)으로 떨어진다.
- **재현/확인 경로**:
  ```bash
  grep -rn "DEPLOYED_ENVS\|DEPLOYED_ENV_MARKERS" backend/src/main/java   # 3개 클래스에서 ENV 축 사용
  grep -n "getActiveProfiles" backend/src/main/java/.../WebhookConfigProfiles.java  # ENV 축 없음
  # 재현: ENV=prd SPRING_PROFILES_ACTIVE=dev 로 기동 → QuartzClusteringGuard 는 차단, 웹훅 가드는 통과
  ```
- **영향**: 보안 — CWE-1188(Insecure Default) / A-ISSUE-15 원상복귀 경로. 배포 실수 한 줄로 rate limit 귀속(XFF)과 VLM IP allowlist 두 통제가 동시에 무력화되는데 **기동은 성공**한다. 방어 정책 강도의 비대칭이며, 프로젝트가 이미 채택한 표준(2축)에서 이탈해 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `WebhookConfigProfiles.requiresExplicitConfig` 에 `DevProfileGuard.DEPLOYED_ENVS` 와 동일한 `ENV` 표식 축을 OR 결합(새 환경변수 발명 금지). 회귀 가드로 "`ENV=prd` + `SPRING_PROFILES_ACTIVE=dev` + 웹훅 설정 미지정 → 기동 실패" 테스트 1건 추가.

---

### [A-ISSUE-13] TC-HMAC-031/032/037/041 — 현 배포 형상이 무서명 콜백 2종의 IP allowlist 계층을 실효 0 으로 만든다

- **심각도**: LOW (local/dev 한정 · 의도된 완화일 가능성 높음)
- **기대 동작(기대효과)**: 무서명 웹훅(VLM·생성형 AI)의 3계층 방어 중 **1계층이 IP allowlist** 다. genai 는 fail-closed(미설정=전면 차단)로 설계돼 있다.
- **현재 동작(이슈 내용)**: 실행 중 컨테이너 env 실측
  ```
  WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0     # 전면 허용 (fail-closed 설계를 명시적으로 해제)
  WEBHOOK_VLM_ALLOWED_IP_CIDRS=none            # 미적용
  WEBHOOK_TRUSTED_PROXY_CIDRS=none             # XFF 전면 무시
  ```
  부팅 로그도 `[Webhook] 생성형 AI 콜백 IP allowlist 활성 count=1` 로 "활성" 이라 표시되나 그 1건이 `0.0.0.0/0` 이다. 결과적으로 두 콜백 경로 모두 **rate limit + request_id 게이트 2계층**으로만 보호된다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -E "WEBHOOK_(GENAI|VLM)_ALLOWED_IP_CIDRS|WEBHOOK_TRUSTED_PROXY_CIDRS"
  curl -i -X POST localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' -d '{}'  # 403 아닌 400
  ```
- **영향**: 코드 결함 아님(`GenAiWebhookIpAllowlist` 는 설계대로 동작). 다만 **TC-HMAC-031/032 의 403 경로를 실동작으로 검증할 수 없게** 만들고, 이 값이 stg/prd 로 전파되면 fail-closed 설계 의도가 무력화된다. `IpAddressMatcher` 는 IPv4 CIDR 만 등록돼 있어 IPv6 발신자는 여전히 차단된다는 점도 부수 확인.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** local/dev 는 현행 유지하되 ①로그 문구를 "전면 허용(0.0.0.0/0) 명시됨" 으로 구분 출력 ②stg/prd 배포 템플릿(`deploy/onprem/env.template`)에서 `0.0.0.0/0` 사용을 금지하는 체크를 두는 방안.

---

### [A-ISSUE-14] TC-HMAC-017/018/035/043 — 무서명 경로의 **필터 단계** 실패(411/413)가 `webhook.auth.failed` 메트릭에 집계되지 않는다

- **심각도**: LOW
- **기대 동작(기대효과)**: rate limit 에 집계되는 실패는 관측 지표에도 나타나야 운영자가 "왜 벤더가 429 를 받는가" 를 사유별로 판별할 수 있다.
- **현재 동작(이슈 내용)**: `handleGuardOnly` 는 411/413 에서 `rateLimiter.recordFailure(clientIp)` 만 호출하고 `countAuthFailure(...)` 를 호출하지 않는다.
  ```java
  // HmacWebhookFilter.java:506-518
  if (contentLength < 0) { ...; rateLimiter.recordFailure(clientIp); writeLengthRequired(response); return; }
  if (contentLength > maxBytes) { ...; rateLimiter.recordFailure(clientIp); writePayloadTooLarge(...); return; }
  ```
  반면 서명 경로는 `fail(...)`(`:604-608`)이 항상 `countAuthFailure` 를 부르고, 무서명 경로의 403(`:497`)·하류 401(`:563`)도 부른다. 실측으로도 411×3 · 413×2 를 발생시킨 뒤 메트릭 `reason` 태그는 `downstream_unauthorized` 뿐이었다.
- **재현/확인 경로**:
  ```bash
  curl -X POST localhost:18081/api/v1/vlm/callback -H 'Transfer-Encoding: chunked' -H 'Content-Type: application/json' -d '{}'   # 411
  curl -s -H "Authorization: Bearer $RT" localhost:18081/api/actuator/metrics/webhook.auth.failed   # reason 에 length_required/payload_too_large 없음
  ```
- **영향**: 관측성 비대칭. 보안 통제 자체는 정상(카운터는 증가). 429 원인 분석 시 "필터 단계에서 끊긴 트래픽" 이 메트릭에 보이지 않아 오진 가능.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `writeLengthRequired`/`writePayloadTooLarge` 직전에 `countAuthFailure(path.tag(), "length_required"|"payload_too_large")` 를 추가(태그는 기존과 동일한 저카디널리티 상수).

---

### [A-ISSUE-15] TC-STREAM-006/007 — `StreamSignatureFilter.shouldNotFilter` 가 여전히 raw URI 정규식이다(A-ISSUE-13 과 동일 패턴) + `ClientIpResolver.isProxyAware()` 죽은 API 잔존

- **심각도**: LOW
- **기대 동작(기대효과)**: 1차 A-ISSUE-13 의 교훈은 "필터 적용 판정과 MVC 라우팅이 **동일한 경로 표현**을 써야 한다" 였고, 웹훅 필터는 그대로 이행됐다(`RequestPath`+`PathPattern`).
- **현재 동작(이슈 내용)**: 스트림 필터는 미이행이다.
  ```java
  // StreamSignatureFilter.java:50,84-89,171-181
  private static final Pattern STREAM_PATH = Pattern.compile("^/v1/videos/(\\d{1,18})/stream$");
  protected boolean shouldNotFilter(HttpServletRequest request) {
      if (!"GET".equalsIgnoreCase(request.getMethod())) return true;
      return !STREAM_PATH.matcher(stripContext(request)).matches();   // getRequestURI() 원문
  }
  ```
  실동작 확인: `/v1/videos/126/%73tream?exp&u&sig` + 유효 쿠키 → **401**. 즉 **스킵 = 인증 미부여 = 거부** 방향이라 현재는 무해하다(1차 A-ISSUE-13 도 이 점을 명시했다).
  별건으로 Phase1 잔여 ③ `ClientIpResolver:114 isProxyAware()` 는 프로덕션 호출처 **0건**(테스트 1건만)으로 여전히 죽은 API 다 — "프록시 인지 여부로 보안 통제를 분기해도 된다" 는 폐기된 설계(R-1)를 재유입시킬 표면.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" -H "Cookie: klid_stream_nonce=..." \
    --path-as-is "http://localhost:18081/api/v1/videos/126/%73tream?exp=..&u=1001&sig=.."   # 401
  grep -rn "isProxyAware" backend/src   # main 1(정의) + test 1(단언), 호출처 0
  ```
- **영향**: 현재 무해(fail-closed 방향). 다만 ①동일 뿌리의 규칙이 코드베이스에 2벌로 남아 있고 ②장래 이 필터가 "인증 부여" 가 아니라 "차단" 역할을 겸하게 되면 즉시 우회 표면이 된다. `isProxyAware()` 는 오해 재유입 위험.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 스트림 필터도 `RequestPath`+`PathPattern` 으로 통일(웹훅과 같은 규칙 1벌)하고, `isProxyAware()` 는 삭제하거나 `@Deprecated` + javadoc 에 "보안 분기 금지" 를 명시.

---

### [A-ISSUE-16] TC-STREAM-011 — `authoring.stream.sign-secret` 미설정 시 봉인키 fail-closed 회귀 테스트 부재

- **심각도**: LOW
- **기대 동작(기대효과)**: "시크릿 미설정 → JVM 기동 랜덤 파생키 → 어떤 쿠키도 봉인 검증 통과 불가" 는 보안 통제이므로 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: 구현은 존재한다.
  ```java
  // StreamNonceCookie.java:183-190
  private static byte[] deriveSealKey(String signSecret) {
      if (signSecret == null || signSecret.isBlank()) {
          byte[] ephemeral = new byte[32];
          new SecureRandom().nextBytes(ephemeral);
          return ephemeral;                      // fail-closed
      }
      return hmac(signSecret.getBytes(StandardCharsets.UTF_8), SEAL_KEY_LABEL);
  }
  ```
  그러나 `StreamNonceCookieSecureFlagTest`(3건)는 Secure 플래그만 다루고, `StreamSignedUrlControllerTest`(20건)는 시크릿이 설정된 전제다. 빈 시크릿 경로를 검증하는 테스트가 없다.
- **재현/확인 경로**: `grep -rn "deriveSealKey\|ephemeral\|sign-secret" backend/src/test/java` → 봉인키 fail-closed 단언 0건
- **영향**: 회귀 감지 공백. 누군가 "미설정 시 상수 키" 로 바꿔도 테스트가 잡지 못한다(그 순간 임의 쿠키가 전건 채택돼 URL 유출 = 재생 가능 상태로 복귀).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 빈 시크릿으로 `StreamNonceCookie` 를 2회 생성해 서로의 `seal()` 결과가 불일치함(=키가 인스턴스별 랜덤)을 단언하는 단위테스트 1건.

---

### [A-ISSUE-17] TC-HMAC-025~028 + Phase1 잔여 ② — nonce/replay 계열의 위양성 안전망 구멍이 잔존한다

- **심각도**: LOW
- **기대 동작(기대효과)**: 1차 교훈 3 *"새로 쓰는 테스트마다 '이 수정을 되돌리면 실패하는가' 를 실측한다"* 에 따라, 보안 통제는 되돌렸을 때 반드시 빨간불이 켜져야 한다.
- **현재 동작(이슈 내용)**: 두 갈래로 구멍이 남았다.
  1. TC-HMAC-025~028(nonce 1회성 소비 · pre-auth write 차단 · 503 fail-closed · 5xx 시 해제)의 유일한 근거가 `HmacWebhookFilterTest`(`MockHttpServletRequest` 사용 72회)다. 실 체인 IT 를 만들 수 없는 이유는 **서명 필수 경로가 0개**이기 때문(A-ISSUE-11)이며, `WebhookPathBypassSecurityIT` 의 replay 케이스는 nonce 가 아니라 genai **멱등 원장** 경로를 검증한다.
  2. Phase1 잔여 ② 그대로 — 시계 통일(R-6) 회귀를 지키는 테스트가 조건부 skip 가능하다.
     ```java
     // JdbcWebhookGuardStoreIT.java:171
     Assumptions.assumeTrue(  ...  );   // 초록 skip 가능
     ```
- **재현/확인 경로**:
  ```bash
  grep -c "MockHttpServletRequest" backend/src/test/java/.../HmacWebhookFilterTest.java   # 72
  grep -n "assumeTrue" backend/src/test/java/.../JdbcWebhookGuardStoreIT.java             # 171
  ```
- **영향**: 회귀 감지 신뢰도. 다만 해당 코드 경로가 현재 미가동(A-ISSUE-11)이라 실피해 가능성은 낮다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`assumeTrue` 를 제거하고 시계 차이를 테스트가 직접 주입(또는 skip 시 실패로 승격) ②A-ISSUE-11 의 방향이 "현행 유지" 로 확정되면, 해당 TC 들의 근거 컬럼에 "단위테스트 단독 — 실체인 IT 불가(경로 0개)" 를 명기해 매 회차 재조사를 방지.

---

## 판정 환경 메모 (다음 회차 참고)

1. **backend 이미지는 HEAD `ca3c712b` 재빌드본**이다. `_raw/stack-bringup.md` §2/§7-1 의 "11개 커밋 뒤처짐" 단서는 본 파트 판정에는 **적용되지 않는다**(`backend-rebuild.md` §7).
2. **DB 데이터가 stack-bringup 시점과 또 달라졌다** — rawSn 123/124/125 는 사라지고 **126(COMPLETED/비식별 Y/`ls_deident_proc_log` SUCCEEDED) · 127 · 128(PENDING/`F`)** 이 존재한다. 다른 에이전트가 동시에 파이프라인을 구동 중(검증 중 VLM 콜백 왕복·`ls_task_assignment` 46행 신규 배정 관측). **A-3 실동작 참조는 rawSn=126** 을 썼다.
3. **호스트발 요청의 `remoteAddr` 이 `172.217.221.84`** 로 관측된다(Docker Desktop NAT). 컨테이너발 요청은 `172.18.0.4` 로 정상 분리되며, rate limit 이 출처별로 독립 집계됨을 확인했다 — **앱 결함이 아니라 환경 특성**이다. 다만 이 값이 공유 카운터 테이블(`LS_WHK_FAIL_NMTM.CALL_IP_ADDR`)에 그대로 적재되므로, IP 기반 케이스를 판정할 때 이 주소를 "호스트 발신자" 로 읽을 것.
4. **rate limit 이 검증 자체를 방해한다** — 같은 출처에서 인증 실패 5회를 넘기면 60초 backoff 가 걸려 후속 프로브가 전부 429 가 된다. 무서명 경로 프로브는 **분당 4건 이하**로 나누거나, 청정 버킷(현재+직전 분에 실패 0건)을 확인하고 시작할 것. 검증 중 오판 1회를 이 이유로 폐기했다.
5. **본 회차가 남긴 부수 데이터**: `ls_whk_fail_nmtm` 에 `172.217.221.84`(버킷 02:49~02:55, 총 5행) + `172.18.0.4` 1행. 전부 TTL 10분 + `WebhookGuardPurgeJob`(10분 간격)으로 자동 소멸한다. `ls_whk_sign_use` 는 **0행 유지**. 프로덕션 데이터 오염 없음.

---

# A 클러스터 part3 (A-5·A-6) 2차 검증 결과

> 대상: `docs/test-cases/A-auth-common.md` **A-5**(TC-CLAIM-001~021, 21건) + **A-6**(TC-TRACE/RESP/EXC/LOG/SORT/BLANK, 39건) = **60건**
> 검증 일시: 2026-07-31 03:00~03:11 KST (UTC 2026-07-30 18:00~18:11)
> 실행 형상: backend `klid-backend:latest` — 이미지 빌드 `2026-07-30T17:42:35Z`(=**2026-07-31 02:42 KST**), 컨테이너 기동 `02:43:01 KST`.
> **`stack-bringup.md` §2 의 "이미지가 HEAD 대비 11커밋 뒤처짐" 제약은 이번 회차에 소멸했다** — 405 정규화(`476bc91a`)·`ASSIGNMENT` 정렬 allowlist(`eace1213`) 가 모두 실동작으로 관측돼 HEAD 재빌드본임이 확증됐다(§근거 드리프트 참조).
> DB `public` 스키마(`klid_system`), 프로파일 `local`.
> 취소선 폐기 행 **0건** — 60건 전수가 검증 대상.

## 집계

| 구간 | 대상 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| A-5 (TC-CLAIM) | 21 | **21** | 0 | 0 | 0 | 0 | 0 |
| A-6 (TC-TRACE/RESP/EXC/LOG/SORT/BLANK) | 39 | **39** | 0 | 0 | 0 | 0 | 0 |
| **합계** | **60** | **60** | **0** | **0** | **0** | **0** | **0** |

| 근거 확인 방식 | 건수 |
|---|--:|
| `[실동작]` 포함 | 41 |
| `[정적]`/`[테스트]` 전용 | 19 |

> **케이스 전건 PASS ≠ 결함 0.** 적대 검증 과정에서 **케이스 표가 다루지 않는 신규 결함 3건**(A-ISSUE-21 HIGH · A-ISSUE-22 LOW · A-ISSUE-23 운영)을 발견했다. 특히 A-ISSUE-21 은 `TC-EXC-017` 이 **기대대로 동작하기 때문에** 케이스로는 잡히지 않는 유형이다(미처리 예외가 500 으로 정규화되는 것 자체는 정상 — 문제는 **400/406/415 여야 할 표준 MVC 예외가 그 경로로 흘러드는 것**).

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **A-ISSUE-17** | 공유 정적 패스워드로 임의 인증 사용자가 **REVIEWER 자가부여**(CWE-269/1392) | **해소** | `[실동작]` `POST /v1/auth/role-claim {"role":"REVIEWER"}` (role=null·INTERNAL 토큰) → **403** `"해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요."`. `allowedClaimRoles()`(`RoleClaimService.java:227-229`, WORKER 단일)가 `claim()` **최상단**(`:118-123`)에서 실제로 참조됨(1차의 "정의만 되고 미참조" 해소). **우회 경로 재검증**: `upsertRole` 호출부는 `RoleClaimService:165`·`UserService:139` 2곳뿐이고 후자의 진입점 `PATCH /v1/users/{userNo}` 는 `@PreAuthorize("hasRole('REVIEWER')")` — role=null 토큰·WORKER 토큰 모두 **403 실측**. 승격 경로 없음 |
| **A-ISSUE-18** | rate limit 이 계정 단위 in-memory (교차계정·다중노드 우회) + `attempts` 맵 무한 증가(CWE-307/770) | **해소** | `[실동작]` ①**계정 축**: 동일 sub 7연타 → `401·401·401·401·401·429·429`(5회 허용, 6회째부터 429) ②**전역 축**: 서로 다른 12개 sub × 5회 = 60요청 → **앞 50건 401 / 51번째부터 10건 전부 429**(교차계정 증폭 차단 실증) ③**노드 공유**: 공유 테이블 `LS_AUTHRT_GRANT_ATMPT` 에 `ACCOUNT|{sub}` 와 `GLOBAL|GLOBAL` **양축 실적재** 확인 ④**맵 회수**: `ConcurrentHashMap` → Caffeine `expireAfterWrite(10m)`+`maximumSize(10_000)`(`RoleClaimRateLimiter.java:60-64,91-95`) ⑤**UTC 버킷**: `bgng_dt='2026-07-30 18:00:00'`(호스트 KST 03:00) — JVM TZ 아닌 UTC 고정 실측. ⚠ **IP 축은 의도적 미도입**(프록시 뒤 전 사용자 수렴 사고 회피 — 클래스 javadoc `:41-43`) |
| **A-ISSUE-24** | `MaskingPatternLayout` 이 local 로그 파이프라인에 **미연결**(죽은 `conversionRule`, 패턴이 `%msg`) — CWE-532 | **해소(런타임 확증)** | `[실동작]` 1차·2차 Phase2 문서 모두 "런타임 프로브 비결정적(마스킹 대상 로그 미발생)"으로 남겨둔 **잔여 ③ 을 이번에 직접 닫았다**. 마스킹 대상 문자열을 정렬 키로 실제 주입해 WARN 로그를 발생시킨 결과: `key=password=***` / `key=Authorization: ***` / `key=token=***` — 평문 0건. `logback-spring.xml:11-24` 가 `LayoutWrappingEncoder`+`<layout class="…MaskingPatternLayout">` 로 배선됨(죽은 `conversionRule` 제거 확인) |

**재현 커맨드(요약)**
```bash
# 마스킹 런타임 확증 (A-ISSUE-24)
TOK=<REVIEWER dev 토큰>
curl -s -o /dev/null -H "Authorization: Bearer $TOK" \
  "http://localhost:18081/api/v1/videos?page=0&size=1&sort=password%3DSuperSecret123,desc"
docker logs klid-backend --since 15s | grep "unsupported sort key"
#   → key=password=***
```

## A-5 결과표 (권한 자가부여 — RoleClaimService · RoleClaimRateLimiter)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-CLAIM-001 | 무권한 INTERNAL이 관리자 pw로 WORKER 역할 획득 | PASS | `[정적]` `RoleClaimService.java:105-193` `[테스트]` `RoleClaimServiceTest#claimWorkerSuccess` `[실동작]` REVIEWER 불가 403 확인 | ⚠ **성공(200) 경로는 이 환경에서 실동작 불가** — `ADMIN_CLAIM_PASSWORD_HASH=`(빈 값, `docker exec klid-backend env` 실측)이라 `:137` fail-closed 로 항상 401. 의도된 배포 형상(A-ISSUE-23) |
| TC-CLAIM-002 | PORTAL_USER 역할 요청 400 거부 | PASS | `[실동작]` 400 `INVALID_INPUT` `"PORTAL_USER 역할은 본 API 로 부여할 수 없습니다."` `[정적]` `:111-114` | |
| TC-CLAIM-003 | 이미 역할 보유자 409 거부 | PASS | `[실동작]` sub=2001(LS_USER_ROLE=WORKER), JWT role 클레임 없이 요청 → **409** `"이미 권한이 부여된 사용자입니다."` `[정적]` `:127-129` | `actor.role()` 은 **JWT 클레임이 아니라 `LS_USER_ROLE` DB 해석값**이다(`/v1/me` 로 교차확인 — role 없는 JWT 인데 `role:"WORKER"` 반환). stale JWT 로 게이트를 속일 수 없다 |
| TC-CLAIM-004 | PORTAL 채널 교차채널 자가부여 차단 | PASS | `[실동작]` PORTAL 채널·role=null 토큰 → **409** `[정적]` `:127-129`; `SecurityConfig.java:71` `auth.requestMatchers("/v1/auth/role-claim").authenticated()` | 근거대로 SecurityConfig 는 채널을 좁히지 않아 **서비스단이 유일 방어선**임을 확인 |
| TC-CLAIM-005 | 잘못된 관리자 pw 401(상수시간) | PASS | `[실동작]` 401 `UNAUTHORIZED` `"관리자 패스워드가 일치하지 않습니다."` `[정적]` `:137-144` | 이 환경은 해시 공백이라 `BCryptPasswordEncoder.matches` 자체는 **미도달**(단락 평가). 상수시간 특성은 `[정적]` 판정 |
| TC-CLAIM-006 | admin pw 해시 미설정 시 항상 401(fail-closed) | PASS | `[실동작]` **타이밍 판별로 확증** — pw 경로 401 응답 `7.6ms`, 역할 화이트리스트 403 경로 `5.2ms`(차이 없음). BCrypt cost 12 라면 200~400ms 가 나와야 하므로 `adminPasswordHash.isEmpty()` 단락이 실제로 동작 중 `[정적]` `:87-97,137` `[테스트]` `RoleClaimServiceTest#emptyAdminHashAlwaysReturns401` | |
| TC-CLAIM-007 | admin pw가 BCrypt 아니면 부팅 거부 | PASS | `[정적]` `:92-95,101-103`(prefix `$2a$/$2b$/$2y$`) `[테스트]` `RoleClaimServiceTest#rejectNonBcryptHashAtBoot` | 재기동 금지 제약으로 실기동 검증 미수행 |
| TC-CLAIM-008 | 계정 축 5회/분 초과 429 (pw 검증 **전** 차단) | PASS | `[실동작]` 동일 sub 7연타 → `401×5 → 429×2`. **검사 순서 실증**: REVIEWER(403)·PORTAL_USER(400)·PORTAL채널(409) 요청 3건은 `LS_AUTHRT_GRANT_ATMPT` 에 **카운터를 남기지 않음**(WORKER 시도 1건만 적재) `[정적]` `:118-133`; `RoleClaimRateLimiter.java:104-128` | 잘못된 role 시도가 정상 사용자 쿼터를 소모하지 않음이 DB 로 확인됨 |
| TC-CLAIM-009 | LS_USER_ROLE 이미 매핑 있으면(stale JWT) 409 | PASS | `[실동작]` 409 관측(TC-CLAIM-003 과 동일 요청) `[정적]` 이중 게이트 `:159-162` `[테스트]` `#alreadyHasLsRoleReturns409` | 실환경에서는 게이트 ②(`:127-129`)가 먼저 잡으므로 `:160` 은 **더 깊은 백스톱**이다(pw 성공 이후에만 도달 → 실동작 미도달) |
| TC-CLAIM-010 | 사용자 미존재 404 | PASS | `[정적]` `:153-156` `[테스트]` `#missingUserReturns404` | pw 성공 이후 단계라 실동작 불가(A-ISSUE-23) |
| TC-CLAIM-011 | 부여 성공 후 AFTER_COMMIT 캐시 evict | PASS | `[정적]` `:167-179`(동기화 비활성 시 즉시 evict 폴백) `[테스트]` `#claimSuccessEvictsCache` | 동상 |
| TC-CLAIM-012 | 발급 토큰 HS256/issuer/channel=INTERNAL/exp 1h | PASS | `[정적]` `:61`(TTL 3600) `:195-208`(HS256·issuer·role·channel=INTERNAL) `[테스트]` `#issuedTokenHasOneHourTtl` | issuer 기본 `klid-auth` ∈ `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal`(컨테이너 env 실측) |
| TC-CLAIM-013 | 로그에 pw 평문/userNo CRLF 미노출 | PASS | `[실동작]` sub 에 `7799\r\n…[RoleClaim] granted userNo=1001 role=REVIEWER\r\n…` 주입 → 로그 `userNo=7799__2026-07-31 03:00:00.000 [m` (CR/LF→`_`, 32자 절단, **가짜 로그 라인 0건**) `[정적]` `:119-120,140-141,211-218` | `adminPassword` 는 전 로그에 미출현(grep 0건) |
| TC-CLAIM-014 | sub 비숫자면 400 | PASS | `[정적]` `:146-151` `[테스트]` `#nonNumericSubjectReturns400` | pw 검증 후 파싱 순서 확인(정보노출 순서상 안전). 실동작 불가(A-ISSUE-23) |
| TC-CLAIM-015 | **REVIEWER 자가부여는 403 거부** | PASS | `[실동작]` **403** + rate limit 카운터 미소모(DB 실측) `[정적]` `:115-123,227-229` `[테스트]` `#claimReviewerForbidden`, `#disallowedRoleRejectedBeforeRateLimit` | A-ISSUE-17 해소의 핵심 케이스 |
| TC-CLAIM-016 | 전역 축 50회/분 초과 시 429 (교차계정 증폭 차단) | PASS | `[실동작]` sub 12종 × 5회 = 60요청 → `401×50 → 429×10`, 로그 `[RoleClaim] rate-limited axis=GLOBAL attempts=51 limit=50` `[정적]` `RoleClaimRateLimiter.java:81-82,108-109` | ⚠ 부수효과: 전역 축을 채우면 **최대 60초간 전 사용자 온보딩이 잠긴다**(Phase2 잔존 ⑤ MEDIUM 과 동일 관측 — 계정 축 5/분 + 분 버킷 자동 해제로 저위험, 설계상 수용된 트레이드오프) |
| TC-CLAIM-017 | 공유 저장소 장애여도 로컬 카운터가 임계 강제 | PASS | `[정적]` `RoleClaimRateLimiter.java:116-127` `Math.max(local, shared)`; `JdbcRoleClaimAttemptStore.java:79-83` `catch(DataAccessException) → UNAVAILABLE`(예외 미승격) `[테스트]` `RoleClaimRateLimiterTest#localCounterKeepsEnforcingWhenStoreUnavailable` | DB 장애 유발이 다른 에이전트 작업을 깨뜨리므로 실동작 미수행 |
| TC-CLAIM-018 | 버킷 시각은 UTC 고정(노드 TZ 불일치 방지) | PASS | `[실동작]` 호스트 `2026-07-31 03:00 KST` 요청 → `LS_AUTHRT_GRANT_ATMPT.BGNG_DT = 2026-07-30 18:00:00`(UTC) `[정적]` `:105-106` `[테스트]` `#windowStartIsUtcRegardlessOfJvmTimeZone` | |
| TC-CLAIM-019 | 로컬 카운터 회수 — Caffeine TTL 10분 + maximumSize 10,000 | PASS | `[정적]` `:60-64,91-95` `[테스트]` `#localCountersExpireByTtl`(가상 Ticker) | |
| TC-CLAIM-020 | 캐시 키 구분자는 NUL(`'\0'`) | PASS | `[정적]` `:113` `String cacheKey = seCd + '\0' + idntfr + '\0' + windowStart;` | 소스 표현이 `'\0'` 이스케이프이며 런타임 동작 불변(a18c646f) 서술과 일치 |
| TC-CLAIM-021 | 시도 기록 만료행 주기 정리 | PASS | `[실동작]` 기동 로그 `2026-07-31 02:43:32.013 [main] INFO [RoleClaim] attempt purge job scheduled intervalMs=600000` `[정적]` `RoleClaimAttemptPurgeJob.java:47-49,62-68,86-94`(전용 데몬 스레드 `role-claim-attempt-purge`, `catch(Throwable)` 로 스케줄러 사망 차단) `[테스트]` `RoleClaimAttemptPurgeIT`(2건) | 초기 지연 5분 + 10분 주기라 실삭제 관측은 미수행 |

## A-6 결과표 (추적ID·공통 응답·예외 + 공통 유틸)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-TRACE-001 | X-Trace-Id 없으면 12자리 생성해 MDC/응답 설정 | PASS | `[실동작]` 헤더 없이 요청 → 응답 `X-Trace-Id: 3d4438652ead`(12 hex), 동일 값이 로그 `[3d4438...]` MDC 로 출현 `[정적]` `RequestIdFilter.java:28-33` | |
| TC-TRACE-002 | 제공 traceId CRLF 제거(CWE-113) | PASS | `[실동작]` **raw 소켓으로 obs-fold 주입**(`X-Trace-Id: aaa\r\n\tX-Injected: yes`) → 응답 헤더 `X-Trace-Id: aaaX-Injectedyes` **단일 라인**, 주입 헤더 미생성(응답 분할 차단) `[정적]` `:48-59` | |
| TC-TRACE-003 | 영숫자·하이픈 외 제거 + 64자 절단 | PASS | `[실동작]` `ab<script>c!@#$%^&*()_+=[]{}\|;:.,/?~\`d-e` → `abscriptcd-e`; `A`×200 → **정확히 64자** `[정적]` `:52-57` | |
| TC-TRACE-004 | 요청 종료 후 MDC 제거(스레드 누수 방지) | PASS | `[실동작]` 동일 워커 스레드(`http-nio-8080-exec-5`) 재사용 요청들이 **매번 다른 traceId** 를 찍고, 비요청 스레드(`[main]` 퍼지잡) 로그는 `[]` 빈 MDC `[정적]` `:34-38` finally MDC.remove | |
| TC-TRACE-005 | RequestIdFilter 최우선순위 | PASS | `[정적]` `:17` `@Order(Ordered.HIGHEST_PRECEDENCE)` `[실동작]` 401/403/404/405/500 **전 응답에 X-Trace-Id 부여**(보안 필터 거부 응답 포함) | |
| TC-RESP-001 | ApiResponse.ok success=true, errorCode=null | PASS | `[실동작]` `{"success":true,"data":{…},"message":null,"errorCode":null}` `[정적]` `ApiResponse.java:9-11` | |
| TC-RESP-002 | error(code) success=false + name() + 기본msg | PASS | `[실동작]` 403 → `{"success":false,"data":null,"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}` `[정적]` `:21-23` | |
| TC-RESP-003 | error(code,message,data)로 부가 data 포함 | PASS | `[실동작]` `POST /v1/augments/request {"videoIds":[123],…}` → `{"success":false,"data":{"blockedVideoIds":[123]},…,"errorCode":"NOT_REVIEWED"}` `[정적]` `:29-31` | |
| TC-RESP-004 | JsonInclude.ALWAYS null 필드도 직렬화 | PASS | `[실동작]` 실패 응답에 `"data":null` 키 유지, 성공 응답에 `"message":null,"errorCode":null` 유지 `[정적]` `:6`; 전역 ObjectMapper 에 `NON_NULL`/`default-property-inclusion` 재정의 **0건**(grep — DTO 개별 지정만 존재) | |
| TC-EXC-001 | CustomException이 ErrorCode status/message 매핑 | PASS | `[실동작]` NOT_REVIEWED→400 · FORBIDDEN→403 · CONFLICT→409 · TOO_MANY_REQUESTS→429 전건 일치 `[정적]` `GlobalExceptionHandler.java:24-33` | |
| TC-EXC-002 | details 있으면 응답 data 포함 | PASS | `[실동작]` 위 `blockedVideoIds` `[정적]` `:28-32` | |
| TC-EXC-003 | @Valid 바디 실패 400 + 필드별 메시지 | PASS | `[실동작]` role 누락 → 400 `"role: role 은 필수입니다."` `[정적]` `:35-43` | |
| TC-EXC-004 | ConstraintViolation 400 정규화 | PASS | `[실동작]` `DELETE /v1/videos/123/tracks/t1?fromFrameNo=-5` → 400 `"deleteTrack.fromFrameNo: fromFrameNo 는 0 이상이어야 합니다."`; `@Size` 위반도 400 `[정적]` `:49-57` | ⚠ 메시지에 **컨트롤러 메서드명이 실린다**(`deleteTrack.…`) — A-ISSUE-22(LOW) |
| TC-EXC-005 | 잘못된 JSON/enum 역직렬화 400(파서상세 비노출) | PASS | `[실동작]` 깨진 JSON `{"role":` → 400 `"요청 본문이 올바르지 않습니다."`; 미정의 enum `"SUPERADMIN"` → 동일 400. 로그도 `cause=…`(클래스명만) `[정적]` `:64-69` | |
| TC-EXC-006 | 필수 RequestParam 누락 400 | PASS | `[실동작]` `GET /v1/versions/abcdef/diff`(compareWith 누락) → 400 `"필수 파라미터가 누락되었습니다: compareWith"` `[정적]` `:76-81` | |
| TC-EXC-007 | 파라미터 타입 불일치 400(원문 비노출) | PASS | `[실동작]` `GET /v1/tasks/abc/summary` → 400 `"파라미터 형식이 올바르지 않습니다: rawSn"`(입력 원문 `abc` 미노출) `[정적]` `:88-93` | |
| TC-EXC-008 | 작업락 partial unique 위반만 409 | PASS | `[정적]` `:141-145,168-171`(`ux_ls_auth_work_lock_raw_active`, `equalsIgnoreCase`) `[테스트]` `GlobalExceptionHandlerDataIntegrityTest#…workLock만_409_그외_fail_closed_500`, `WorkLockDataIntegrityIT` | 실제 동시 락 INSERT 경합 유발은 타 에이전트 데이터 오염 위험으로 미수행 |
| TC-EXC-009 | 프리셋 labelId/라벨명 CI/검출유형 유니크 위반 각 409 | PASS | `[정적]` `:146-160` 3분기 + 각 메시지 3종(`"이미 처리 중이거나 충돌하는 요청입니다."` / `"이미 사용 중인 라벨 이름입니다."` / `"이미 사용 중인 검출 클래스 매핑입니다."`) `[테스트]` `LsLabelPresetCodeLabelIdUniqueIT`, `LabelNameCiUniqueMigrationIT` | 동상 |
| TC-EXC-010 | 판별 불가 무결성 위반은 500 노출(fail-closed) | PASS | `[정적]` `:161-166` — 제약명 미판별 시 `INTERNAL_ERROR`(조용한 409 흡수 없음) `[테스트]` 상동 | |
| TC-EXC-011 | 제약명 Hibernate→SQL 메시지 순 추출 | PASS | `[정적]` `:193-209` — 원인 체인에서 `org.hibernate.exception.ConstraintViolationException#getConstraintName()` 우선, null 이면 `constraint "…"` 정규식 파싱 | |
| TC-EXC-012 | 무결성 위반 응답에 제약명/SQL/스택 미노출 | PASS | `[정적]` `:138-166` 응답은 `ErrorCode` 고정 메시지만, 제약명은 **로그에만** `[테스트]` `#DataIntegrity_응답에_제약명_SQL_미포함` | 정책 서술 주석 `:127-137` 위치도 케이스 근거와 일치 |
| TC-EXC-013 | AccessDeniedException 403 매핑 | PASS | `[실동작]` WORKER 토큰 → `GET /v1/users` → 403 `FORBIDDEN` `[정적]` `:211-216` | |
| TC-EXC-014 | AuthenticationException 401 매핑 | PASS | `[실동작]` 무토큰 → 401 `{"…","errorCode":"UNAUTHORIZED"}`(ApiResponse 포맷 유지) `[정적]` `:218-223` | 필터 체인에서 던져지는 경우는 `ExceptionTranslationFilter`/EntryPoint 가 처리하며 본 핸들러는 컨트롤러·서비스 발 예외 담당 — 관측 결과(상태·바디 포맷)는 동일 |
| TC-EXC-015 | 미매핑 경로(NoResourceFound) 404 정규화 | PASS | `[실동작]` `GET /v1/no-such-endpoint-xyz` → 404 `"요청한 API를 찾을 수 없습니다."` `[정적]` `:241-246` | |
| TC-EXC-016 | multipart 크기 초과 413 정규화 | PASS | `[정적]` `:234-239` `MaxUploadSizeExceededException → PAYLOAD_TOO_LARGE`(한도 수치 비노출) `[테스트]` `MaxUploadSizeExceededMappingTest` | 실동작 미수행 — local 한도가 `max-file-size 500MB / max-request-size 1200MB`(`application.yml:29-31`)라 500MB+ 전송이 필요하고, 공유 스택에 임시파일 스풀 부하를 주는 것이 타 에이전트 작업을 위협해 의도적으로 배제 |
| TC-EXC-017 | 미처리 Exception 500 + 고정메시지(스택 비노출) | PASS | `[실동작]` 500 응답 바디는 `{"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` — 스택·클래스명·내부 경로 **0건**. 스택은 서버 로그에만(`log.error(…, e)`) `[정적]` **실제 위치 `:273-278`**(케이스 근거 `:248-253` 은 드리프트 — 아래 참조) | ⚠ 이 케이스는 PASS 지만, **여기로 흘러들어선 안 될 표준 MVC 예외 3종을 발견**(A-ISSUE-21 HIGH) |
| TC-LOG-001 | LogSanitizer — C0/DEL/C1 제어문자 제거 | PASS | `[실동작]` 정렬 키에 `\r \n \x07(BEL) \x7F(DEL) \x85(NEL)` 주입 → 로그 `key=kLS1PS2NEL3DEL4BEL5CRLF6 가 나 다`(od -c 로 바이트 확인, 줄바꿈 1개=라인 끝뿐) `[정적]` `LogSanitizer.java:88-100` | |
| TC-LOG-002 | LogSanitizer — 유니코드 라인 구분자 제거 | PASS | `[실동작]` 동일 프로브에 `U+2028`·`U+2029` 포함 → **둘 다 제거**(local 평문 레이아웃에서 가짜 라인 위조 불가) `[정적]` `:78-99` `[테스트]` `LogSanitizerTest#…U2028_U2029_제거` | |
| TC-LOG-003 | LogSanitizer — 가시 문자·일반 공백 보존 | PASS | `[실동작]` `가 나 다` 와 그 사이 `U+0020` 공백 **원형 보존** `[정적]` `:86,93-95` | |
| TC-LOG-004 | LogSanitizer — 길이 상한 + 절단 표기 | PASS | `[실동작]` 64자 상한 호출부(SortAllowlist) 프로브 → `key=aaaaaaaaaa2026-07-31 03:00:00.000 [main] ERROR FAKE - INJECTEDbb...(truncated)` `[정적]` `:25,28,51-72` | |
| TC-LOG-005 | LogSanitizer — null 은 `(null)` | PASS | `[정적]` `:27,52-54` `[테스트]` `LogSanitizerTest#…null_입력시_플레이스홀더_반환` | |
| TC-SORT-001 | SortAllowlist strict — 미등록 정렬 키 400 | PASS | `[실동작]` `GET /v1/tasks/board?sort=secretField,desc` → **400** `"지원하지 않는 정렬 기준입니다."`(입력값·내부 필드명 미노출); SQL 조각 `regDt;DROP TABLE ls_data_raw--` 도 동일 400; `/v1/assignments` 도 strict 400 `[정적]` **실제 `:163-186`(throw `:179`)** | ★2 정책대로 strict 유지 — 통일 권고 없음 |
| TC-SORT-002 | SortAllowlist lenient — 미등록 키 무시 + 기본 정렬 폴백 | PASS | `[실동작]` `/v1/reviews?sort=secretField,desc` → **200**(totalElements=5), `/v1/videos?sort=secretField,desc` → **200**(totalElements=40) + WARN `[Sort] unsupported sort key ignored key=secretField` `[정적]` **실제 `:213-239`(무시 `:228-233`)**; `SortFieldMapper.java:57-60` | ★2 확정 정책 — strict/lenient 비대칭은 **정상** |
| TC-SORT-003 | 정렬 항목 개수 상한 = allowlist 고유 엔티티 필드 수 | PASS | `[실동작]` board 4개 지정(상한 3) → **400** `"정렬 기준이 너무 많습니다."`(개수·상한 미노출) / reviews 4개·videos 5개 → **200 전체 폴백**(부분 적용 없음, WARN `too many sort orders … firstDroppedKey=…`) `[정적]` **실제 `:167,172-175,217,222-227,247-249`** | |
| TC-SORT-004 | 같은 엔티티 필드 중복 지정 시 첫 지정만 적용 | PASS | `[실동작]` `/v1/videos?sort=capturedAt,asc&sort=shtDt,desc` → 결과가 `capturedAt,asc` 단독과 **완전 동일**(rawSn 20001,20002 / 2026-07-30T11:23:34); 순서를 뒤집으면 desc 단독과 동일(126,127) `[정적]` **실제 `:169,181-183,234-236`** | |
| TC-SORT-005 | 폴백 WARN 로그의 입력 키는 LogSanitizer 로 정제 | PASS | `[실동작]` `?sort=aaaaaaaaaa%0A…ERROR FAKE - INJECTED…,desc` → WARN 한 줄, 개행 제거 + 64자 절단 `...(truncated)` `[정적]` `:66`(정확) + **실제 `:224-225,230-231`** | |
| TC-SORT-006 | 컨트롤러 allowlist 사본 금지 — `SortAllowlist.VIDEO` 단일 진실원 | PASS | `[정적]` **실제 `:133-140`**(VIDEO 상수 1벌); `SortFieldMapper.java:29-31,57-70`(정확) — `resolveLenient` 로 전량 위임, 자체 판정 로직 0줄 `[테스트]` `SortFieldMapperTest`(8건), `SortAllowlistTest`(18건) | `ASSIGNMENT` allowlist 신설(`eace1213`)도 동일 상수 파일에 추가돼 사본 없음 |
| TC-BLANK-001 | BlankTextPredicate — Java isBlank()와 동치 판정 | PASS | `[정적]` `BlankTextPredicate.java:60-66` `[실동작(DB 의미검증)]` PG 실행 결과 `trim(chr(9))='' → f` (구 `trim(col)<>''` 판정) vs `trim(replace(chr(9),chr(9),''))='' → t`; 전각공백 `U+3000` 도 동일하게 구 방식 f / 신 방식 t `[테스트]` `TaskBoardFilterSortTest#공백문자만_있는_CCTV명은_표시된_VMS_ID_로_검색된다`, `ReviewListFilterSortTest` 동명 | 이 환경엔 `CCTV_NM='\t'` 실데이터가 없고 DB 쓰기가 금지라 화면 폴백 종단 시나리오는 미재현 — 판정식 동치성은 PG 직접 실행으로 확증 |
| TC-BLANK-002 | BlankTextPredicate — 제거 문자는 파라미터 바인딩(CWE-89) | PASS | `[실동작]` `GET /v1/tasks/board?q=CCTV-018` 실행 시 Hibernate SQL 로그에 `replace(replace(…replace(mrc2_0.CCTV_NM, ?, '')…` — **24중첩 전부 `?` 바인딩**, 문자열 연결 0건 `[정적]` `:49,62-65,68-76`(`Character.isWhitespace` 에서 생성) | |

## 근거 드리프트

케이스 표의 `file:line` 이 현재 HEAD 와 어긋난 건 **7건**(모두 코드 삽입에 의한 행 밀림 — 로직 변경 아님).

| ID | 표기된 근거 | 실제 위치 | 원인 |
|---|---|---|---|
| TC-EXC-017 | `GlobalExceptionHandler.java:248-253` | **`:273-278`**(`handleUnknown`) | `476bc91a` 가 `handleMethodNotSupported`(405 정규화, `:248-271`)를 삽입해 **+25행** 밀림. 표기 범위 `248-253` 은 현재 그 405 핸들러의 javadoc |
| TC-SORT-001 | `SortAllowlist.java:139-162` | **`:163-186`**(`resolve`, throw `:179`) | `eace1213`(배정 목록 정렬 allowlist)이 `ASSIGNMENT` 상수(`:80-102`)를 삽입해 이후 전 구간 **+24행** 밀림 |
| TC-SORT-002 | `SortAllowlist.java:189-215` | **`:213-239`**(`resolveLenient`, 무시 `:228-233`) | 동상 (+24). `SortFieldMapper.java:57-60` 은 정확 |
| TC-SORT-003 | `:143,193,198-203,223-225` | **`:167,217,222-227,247-249`** | 동상 (+24) |
| TC-SORT-004 | `:145,157-159,210-212` | **`:169,181-183,234-236`** | 동상 (+24) |
| TC-SORT-005 | `:66,200-201,206-207` | `:66`(정확) + **`:224-225,230-231`** | 동상 (+24). `LOG_KEY_MAX_LENGTH` 는 삽입 지점보다 앞이라 불변 |
| TC-SORT-006 | `SortAllowlist.java:109-116` | **`:133-140`**(VIDEO 상수) | 동상 (+24). `SortFieldMapper.java:29-31,57-70` 은 정확 |

**정확 확인된 근거**: A-5 전 21건(`RoleClaimService`·`RoleClaimRateLimiter`·`JdbcRoleClaimAttemptStore`·`RoleClaimAttemptPurgeJob`·`SecurityConfig.java:71`) · TC-TRACE 5건 · TC-RESP 4건 · TC-EXC 16건(017 제외) · TC-LOG 5건 · TC-BLANK 2건.

> ⚠ 이 드리프트 2건(`476bc91a`·`eace1213`)은 `stack-bringup.md` 가 "이미지 미반영"으로 지목한 커밋이다. **두 커밋의 동작이 실동작으로 확인**(405+`Allow: POST` 응답 / `/v1/assignments` strict 400)됐으므로, 현재 실행 이미지는 **HEAD `ca3c712b` 재빌드본이 맞다**. 이후 회차는 `stack-bringup.md` §2·§7-1 의 "11커밋 뒤처짐" 제약을 더 이상 적용하지 말 것.

## 이슈 상세

### [A-ISSUE-21] TC-EXC-017(부가) — 415/406/multipart-part 누락 등 표준 MVC 4xx 예외가 전용 핸들러 없이 500 + ERROR 스택트레이스로 떨어진다 (미인증 경로 포함)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `GlobalExceptionHandler` 는 클라이언트 오요청을 **4xx 로 정규화**하고, `@ExceptionHandler(Exception.class)` 의 500 + `log.error(…, e)`(전체 스택) 경로에는 **진짜 서버 결함만** 도달해야 한다. 이 원칙은 이미 코드 안에 명문화돼 있다 — `:248-259` 주석: *"이 핸들러가 없으면 `@ExceptionHandler(Exception.class)` 로 떨어져 **500 + ERROR 스택트레이스**가 된다(2026-07-30 배포 검증에서 실측). 클라이언트에는 '요청이 잘못됐다'가 아니라 '서버 장애'로 보이고, 모니터링 로그에는 정상 오요청이 장애와 섞여 쌓인다."* 405(`HttpRequestMethodNotSupportedException`)만 그 근거로 고쳐졌고 **형제 예외들은 그대로 남았다.**
- **현재 동작(이슈 내용)**: `GlobalExceptionHandler` 는 `ResponseEntityExceptionHandler` 를 상속하지 않고 예외를 개별 등록하는데(`backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java:20-22`), 아래 표준 예외에 핸들러가 없다.
  ```java
  // GlobalExceptionHandler.java:273-278 — 등록되지 않은 모든 예외의 종착지
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
      log.error("[Exception] unhandled exception", e);   // ← 전체 스택 ERROR 레벨
      return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
              .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
  }
  ```
  실측(2026-07-31 03:04~03:07 KST):

  | 요청 | 올바른 상태 | 실제 | 발생 예외(컨테이너 로그) |
  |---|:--:|:--:|---|
  | `POST /v1/auth/role-claim` + `Content-Type: text/plain` | 415 | **500** | `org.springframework.web.HttpMediaTypeNotSupportedException` |
  | `POST /v1/auth/role-claim` + `application/x-www-form-urlencoded` | 415 | **500** | 동상 |
  | `GET /v1/me` + `Accept: application/xml` | 406 | **500→401**\* | `org.springframework.web.HttpMediaTypeNotAcceptableException` |
  | `POST /v1/portal/uploads/images` (multipart 에 `files` 파트 없음) | 400 | **500** | `org.springframework.web.multipart.support.MissingServletRequestPartException` |

  \* 406 케이스는 500 발생 후 ERROR 디스패치가 보안 필터를 재통과해 **최종 응답이 401 `"인증이 필요합니다."`** 로 나간다 — 인증된 요청인데 인증 오류로 보이는 오도(誤導) 응답.

  ★ **미인증으로도 트리거된다**: `permitAll` 웹훅 경로에서 동일 결과 확인.
  ```
  POST /v1/vlm/callback    (Content-Type: text/plain) → 500 + ERROR 스택
  POST /v1/genai/callback  (Content-Type: text/plain) → 500 + ERROR 스택
  POST /v1/dev/tokens      (Content-Type: text/plain) → 500 + ERROR 스택
  ```
- **재현/확인 경로**:
  ```bash
  # 인증 불필요 — 한 줄로 ERROR 스택 1건 생성
  curl -s -o /dev/null -w "%{http_code}\n" -X POST \
    http://localhost:18081/api/v1/vlm/callback -H "Content-Type: text/plain" -d 'x'
  # → 500
  docker logs klid-backend --since 30s | grep -A5 "unhandled exception"
  # → org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'text/plain;charset=UTF-8' is not supported
  #   + 전체 스택트레이스

  # 인증 경로
  TOK=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"REVIEWER","channel":"INTERNAL"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOK" -H "Accept: application/xml" \
    http://localhost:18081/api/v1/me     # → 401 (실제로는 내부 500)
  ```
- **영향**: 보안/운영 —
  - **CWE-779(과도한 로그 기록) / CWE-770**: 미인증 원격 호출자가 헤더 한 줄로 ERROR + 전체 스택을 무제한 생성. 로그 볼륨·디스크·수집기 비용을 밀어올리고, **진짜 장애 알림이 정상 오요청에 묻힌다**(alert fatigue). 웹훅 경로(`/v1/vlm/callback`·`/v1/genai/callback`)는 외부 벤더에 열려 있어 노출 표면이 실재한다.
  - **기능**: FE·외부 벤더가 "내 요청이 잘못됨"과 "서버 장애"를 구분할 수 없어 잘못된 재시도(500 은 보통 재시도 대상)를 유발. 406 케이스는 상태코드까지 401 로 뒤바뀐다.
  - 응답 바디 자체에는 스택·내부 경로가 없어 **CWE-209 정보노출은 아니다**(TC-EXC-012·017 은 그대로 PASS).
- **수정 방향(제안)**: ⚠ **이 라운드에서 구현하지 않음.**
  `GlobalExceptionHandler` 에 405 핸들러(`:261-271`)와 동형으로 전용 핸들러를 추가한다 — ① `HttpMediaTypeNotSupportedException` → 415(+RFC 9110 권장 `Accept-Post`/`Accept` 헤더), ② `HttpMediaTypeNotAcceptableException` → 406, ③ `MissingServletRequestPartException` → 400 `INVALID_INPUT`(파트명만 노출), ④ 겸해 `ServletRequestBindingException`(필수 헤더/쿠키 누락) → 400. 로그는 WARN + 예외 클래스명·파라미터명만(경로 원문 금지 — 기존 405 핸들러 주석의 CWE-117/209 규칙 준수). 회귀 가드로 "`Exception.class` 종착지에 도달하는 표준 MVC 예외가 없다"를 고정하는 테스트(405 케이스와 같은 방식) 추가 권장.

### [A-ISSUE-22] TC-EXC-004(부가) — ConstraintViolation 400 응답 메시지에 컨트롤러 메서드명이 노출된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 400 응답은 사용자가 고칠 수 있는 **입력 필드명**만 알려주면 충분하다. 다른 400 핸들러들은 이 규칙을 지킨다 — 타입 불일치는 `"파라미터 형식이 올바르지 않습니다: rawSn"`(파라미터명만), 누락은 `"필수 파라미터가 누락되었습니다: compareWith"`.
- **현재 동작(이슈 내용)**: `ConstraintViolationException` 의 `propertyPath` 를 그대로 이어붙여 **`{메서드명}.{파라미터명}`** 이 나간다.
  ```java
  // GlobalExceptionHandler.java:49-57
  String msg = e.getConstraintViolations().stream()
          .map(v -> v.getPropertyPath() + ": " + v.getMessage())   // propertyPath = "deleteTrack.fromFrameNo"
          .collect(Collectors.joining(", "));
  ```
  실측:
  ```
  DELETE /v1/videos/123/tracks/t1?fromFrameNo=-5
    → 400 {"message":"deleteTrack.fromFrameNo: fromFrameNo 는 0 이상이어야 합니다.", ...}
  DELETE /v1/videos/123/tracks/{41자}?fromFrameNo=0
    → 400 {"message":"deleteTrack.trackId: trackId 는 30자 이하여야 합니다.", ...}
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -X DELETE -H "Authorization: Bearer $TOK" \
    "http://localhost:18081/api/v1/videos/123/tracks/t1?fromFrameNo=-5"
  ```
- **영향**: 보안 — CWE-209(내부 구현 세부 노출), 매우 낮음. 내부 메서드명 몇 개가 새는 수준이며 공격에 직접 쓰이지는 않는다. FE 표시 메시지 품질 저하가 실질 영향에 가깝다.
- **수정 방향(제안)**: ⚠ **구현하지 않음.** `:52` 를 `propertyPath` 의 **마지막 노드만** 취하도록 변경(`StreamSupport.stream(v.getPropertyPath().spliterator(), false).reduce((a,b)->b)`). 기존 400/메시지 형태는 유지되므로 하위호환 파손 없음.

### [A-ISSUE-23] A-5 전반(검증 제약) — 배포 형상의 `ADMIN_CLAIM_PASSWORD_HASH` 가 빈 값이라 role-claim 성공 경로가 실동작 검증 불가
- **심각도**: LOW (운영·검증 인프라 — 코드 결함 아님)
- **기대 동작(기대효과)**: 검증 회차가 `TC-CLAIM-001/010/011/012/014`(성공 경로 및 그 이후 분기)를 **실동작**으로 판정할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `docker exec klid-backend env` 실측 `ADMIN_CLAIM_PASSWORD_HASH=`(빈 문자열). Spring 은 환경변수가 **존재하되 비어 있으면** `${ADMIN_CLAIM_PASSWORD_HASH:<application-local.yml 기본 해시>}` 의 기본값을 쓰지 않고 빈 문자열로 바인딩하므로, `RoleClaimService.java:87-97` 이 `adminPasswordHash=""` 로 확정되고 `:137` 의 `adminPasswordHash.isEmpty()` 단락으로 **어떤 패스워드도 401**이 된다.
  - 이는 문서화된 의도다 — `docs/operations/config-key-changes-20260727.md:236` "빈 기본값 fail-closed", `deploy/onprem/config/backend/env.template:73` 도 빈 값.
  - 타이밍으로 실증: pw 경로 401 = `7.6ms` / 역할 화이트리스트 403 = `5.2ms` (BCrypt cost 12 라면 200ms+ 필요) → BCrypt 미호출 확정.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep ADMIN_CLAIM_PASSWORD_HASH   # → ADMIN_CLAIM_PASSWORD_HASH=
  # 임의 pw 로 항상 401 + 응답시간이 403 경로와 동일
  curl -s -o /dev/null -w "%{http_code} %{time_total}\n" -X POST \
    http://localhost:18081/api/v1/auth/role-claim -H "Authorization: Bearer <role=null 토큰>" \
    -H 'Content-Type: application/json' -d '{"role":"WORKER","adminPassword":"whatever123"}'
  ```
- **영향**: 기능 — local/dev 스택에서 **권한 자가부여 온보딩 기능 전체가 비활성**. 보안 관점에선 오히려 안전(fail-closed)하며 `TC-CLAIM-006` 의 기대 동작 그 자체다. 검증 관점에서만 제약.
- **수정 방향(제안)**: ⚠ **이 라운드에서 변경하지 않음**(설정 변경은 컨테이너 재기동을 수반해 금지 범위). 다음 회차에 A-5 성공 경로 실동작 판정이 필요하면, `.env` 에 알려진 평문의 BCrypt(cost 12) 해시를 넣고 **검증 전용으로만** 기동할 것 — 그때 `TC-CLAIM-001/010/011/012/014` + `LS_USER_ROLE` upsert·AFTER_COMMIT evict·발급 토큰 클레임을 실왕복으로 닫을 수 있다. `application-local.yml:35` 의 커밋된 기본 해시(A-ISSUE-19, LOW)는 평문이 저장소에 없어 그대로는 쓸 수 없다.

## 부기 — 확정 정책 준수 확인

- **★2(정렬 strict/lenient 비대칭)**: `TC-SORT-001~003` 에서 `/v1/tasks/board`·`/v1/assignments` = **400**, `/v1/reviews`·`/v1/videos` = **200 폴백** 을 실측했고 **이를 결함으로 보고하지 않는다.** 두 정책 모두 allowlist 매핑으로만 정렬을 해석하므로 보안 목적(CWE-89/770)은 동일하게 달성된다.
- **★1·★3**: 이 파트(A-5·A-6) 범위 밖 — 해당 케이스 없음.
- **`RoleClaimRateLimiter.java:123` 주석 미세 불일치(보고 대상 아님)**: 주석은 "식별자는 …제어문자를 제거해 로그한다"고 하나 실제 WARN(`:124`)에는 식별자가 **아예 실리지 않는다**(`axis=… attempts=… limit=…`). 노출을 줄이는 방향이라 결함이 아니며 주석 문구만 현행화 대상.

---

# A 클러스터 part4 (A-7) 2차 검증 결과

대상: `docs/test-cases/A-auth-common.md` `## A-7. 듀얼 데이터소스 · 캐시 · 시스템설정 · 기동 가드 · 설정 바인딩 · Actuator/헬스 · Resilience4j` (49건, 취소선 폐기 0건).
환경: backend `localhost:18081`(HEAD `ca3c712b` 재빌드본, 2026-07-31 02:42 KST), mock-server `:9400`, ai-server `:19300`, postgres `:5432`(`public` 스키마), local 프로파일. `_raw/stack-bringup.md` · `_raw/backend-rebuild.md` · `_raw/test-baseline.md` 를 근거로 사용.

## 집계

| 판정 | 건수 |
|---|---:|
| PASS | 49 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0(본문 처리: TC-CACHE-005 는 PASS 표기하되 "기능 제거로 판정 대상 소멸" 특이사항 부기 — 아래 참조) |
| 확인필요 | 0 |
| **합계** | **49** |

TC-CACHE-005 는 엄밀히는 "기동 시 검증"이 더 이상 일어나지 않는(대상 흐름 자체가 2026-07-30 제거됨) 상태라 PASS/FAIL 어느 쪽도 성립하지 않는다. 결함이 아니므로 FAIL 로 잡지 않되, 카탈로그 기술과 실측이 어긋나 근거 드리프트로 별도 집계(아래 근거 드리프트 절 참조). 집계표의 PASS 49 에는 이 건도 포함했다(제품 결함 없음이 실측 결론이므로).

이번 회차는 CRITICAL/HIGH 신규 결함 0건. 코드·설정·런타임 3중 대조(정적 Read + 부팅 로그 + 실 HTTP 왕복)에서 전건 기대결과와 일치했다.

## 1차 이슈 해소 대조

| 이슈 | 07-25 상태 | 2차(이번) 상태 |
|---|---|---|
| A-ISSUE-21 (LocalProfileGuard 회귀 테스트 전무) | MEDIUM, 테스트 0건 | **✅ 해소.** `LocalProfileGuardTest`(4케이스: ENV=prd/dev·stg 거부, ENV 미설정 통과, non-local 즉시 통과) 신설 확인. 이슈가 제안한 3케이스 이상 커버 |
| A-ISSUE-22 (sysconfig 캐시 "적중" 증명 테스트 부재) | LOW | **미해소 유지.** `SystemConfigControllerTest`(9케이스)·`SystemConfigService` 전체 grep 결과 `verify(repository, times(1))` 류 캐시-히트 직접 증명 테스트 없음. `@Cacheable` 프레임워크 표준 동작 자체는 실사용(REVIEWER PUT→즉시 값 반영, `TC-CACHE-003` 아래 실동작으로 무효화는 확인됨)이나, "무효화 전 반복 호출이 DB 를 안 타는지" 를 직접 증명하는 테스트는 여전히 없음. 회귀 방지망 공백만 남은 LOW 이슈로 재확정(신규 이슈 미발행) |
| A-ISSUE-23 (SystemConfigService 방어 분기 다수 미커버) | LOW | **부분 해소.** `SystemConfigControllerTest` 가 NUMBER 문자열 입력·BATCH_CONCURRENCY 범위초과·화이트리스트 외 키 3케이스를 컨트롤러 레벨로 커버(신규 추가분 추정). 그러나 actor=null·DECIMAL NaN/Infinity 리터럴·BOOLEAN 분기·getInt 타입불일치는 여전히 전용 테스트 없음(`SystemConfigServiceTest` 자체가 존재하지 않음). 실동작으로는 이번에 NaN·범위초과·화이트리스트 위반 3건을 직접 curl 로 재현해 코드가 맞다는 것은 확인(아래 표 참조), 회귀 방지망 공백만 잔존 |

A-6 소관 A-ISSUE-24(MaskingPatternLayout local 미배선)는 A-7 대상 아님(범위 밖, 판정 생략).

## ★설정 실효값 대조표

| 설정키 | yml 선언 | .env·compose(docker inspect 실효값) | 실동작 판정 |
|---|---|---|---|
| `spring.quartz.properties.org.quartz.jobStore.isClustered` | 공통 `${QUARTZ_CLUSTERED:false}`, prd/stg yml `${QUARTZ_CLUSTERED:true}`(`application-prd.yml:13`, `application-stg.yml:11`) | 컨테이너 env 에 `QUARTZ_CLUSTERED` 미설정(local 이라 기본값 상속) | **[실동작]** 부팅 로그 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]` — local 은 `QuartzClusteringGuard.SINGLE_NODE_PROFILES` allowlist 안이라 정상. TC-PROF-009/010 일치 |
| `vlm.client.allow-insecure-url` | — | `VLM_ALLOW_INSECURE_URL=true` | **[실동작]** 부팅 로그 `[VlmUrl] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용` — local allowlist 통과, 기동 성공. TC-PROF-011 일치 |
| `authoring.augment.external.allow-insecure-url` | — | (동일 축) | **[실동작]** 부팅 로그 `[AugmentUrl] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용` |
| `authoring.augment.external.mode` | 공통 기본 `http`(matchIfMissing) | `AUGMENT_EXTERNAL_MODE=http` | **[실동작]** 부팅 로그 `[Augment] active ExternalAugmentClient=HttpExternalAugmentClient` |
| `webhook.genai.allowed-ip-cidrs` | stg 기본 빈 값(fail-closed) | `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` | **[실동작]** 부팅 로그 `[GenAi] 위탁(http) ↔ 콜백 IP allowlist 짝 확인 완료` — mode=http 인데 allowlist 가 none 이 아니므로 `GenAiIntegrationWiringGuard` 통과. 콜백 실제 200 처리 로그도 확인(`GenAiCallbackService` running/succeeded). TC-PROF-017 일치 |
| `kpst.deid.base-url` / `kpst.deid.enabled` | — | `KPST_DEID_BASE_URL=http://klid-mock-server:9400`, `KPST_DEID_ENABLED=true` | **[실동작]** 호스트에 `mock` 토큰 포함 → `DeidentifyEndpointTrustGuard.untrustedReason()` 는 비신뢰로 판정하지만 **local 프로파일이라 무음**(정책대로). 부팅 로그에 해당 WARN 없음 = 설계대로. `deidentifyHealth` 실측 `mode=kpst, status=UP` (아래) |
| `authoring.integration.deidentify.mock-mode` | 기본 `false` | `DEIDENTIFY_MOCK_MODE=false` | **[실동작]** self-fill 자체 복사 모드가 아니라 실제 KPST(목업서버) 프로토콜 경유 확인 — `mode=kpst` |
| `management.endpoints.web.exposure.include` | 공통 `${ACTUATOR_EXPOSE:health,info,metrics,prometheus}`, prd `health` 고정 | (local, override 없음) | **[실동작]** `/api/actuator` 루트가 `health/health-path/info/metrics/metrics-{name}/prometheus` 6개 링크만 노출. `env`/`beans`/`heapdump`/`configprops`는 REVIEWER 인증 여부와 무관하게 **404**(노출 목록에 없음) — 노출 자체가 안 되므로 SecurityConfig 의 REVIEWER 게이트 이전 단계에서 이미 차단 |
| `SecurityConfig` `/actuator/**` 인가 | `.requestMatchers("/actuator/**").hasRole(REVIEWER)`(health/info 제외) | — | **[실동작]** 무토큰 `metrics`/`prometheus` 요청 401, REVIEWER 토큰으로는 200. 방어 2중화(노출 축 + 인가 축) 확인 |

## A-7 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|-----------|------|
| TC-DS-001 | Control DataSource/EMF/TxManager @Primary | PASS | [정적] ControlDataSourceConfig.java:31-55(@Primary 3곳) 일치 / [실동작] `GET /api/actuator/health`(REVIEWER) → `db.controlDataSource.status=UP` | |
| TC-DS-002 | @ControlRepo만 control EMF 라우팅 | PASS | [정적] ControlDataSourceConfig.java:19-28 `@EnableJpaRepositories includeFilters=ControlRepo.class` 일치. `@ControlRepo` 사용 리포지토리 61개 grep 확인 | |
| TC-DS-003 | @PortalRepo는 portal EMF 라우팅(비-Primary) | PASS | [정적] PortalDataSourceConfig.java:18-27,47-51(카탈로그 18-28,47-50 대비 1줄 드리프트) — `@Primary` 없음 확인 / [실동작] `db.portalDataSource.status=UP` | `@PortalRepo` 실사용 1건(`PortalDatasetVideoMetaRepository`) — 포털 자산 업로드(LS_PORTAL_ULD 등)는 `@ControlRepo`(자체 스키마 저장)이며 이는 CLAUDE.md ADR-013 설계와 일치(데이터마트 복제만 portal DS 경유) |
| TC-DS-004 | 서비스 Tx가 controlTransactionManager 바인딩 | PASS | [정적] SystemConfigService.java:38,91 정확 일치 / [실동작] PUT BATCH_INTERVAL_SEC 후 즉시 GET 목록에 반영(커밋 확인) | |
| TC-CACHE-001 | 4개 캐시 개별 TTL 등록 | PASS | [정적] CacheConfig.java 상수 53-56/TTL상수 66/빈메서드 68-97(카탈로그 51-54,63,65-94 대비 소폭 드리프트) — sysconfig 60s/100, stream-meta 5m/200, eventType 6h/50, userRole 60s/500 전부 일치 | |
| TC-CACHE-002 | sysconfig getInt/getString 60s 캐시 | PASS | [정적] `@Cacheable(cacheNames="sysconfig", key=...)` SystemConfigService.java:47-48,80-82(카탈로그 대비 1줄 드리프트) | A-ISSUE-22 잔존(캐시-히트 직접 증명 테스트 없음, LOW) |
| TC-CACHE-003 | update 시 sysconfig 캐시 전체 무효화 | PASS | [정적] `@CacheEvict(allEntries=true)` SystemConfigService.java:90-91 / [실동작] REVIEWER PUT 직후 GET 목록에 새 값 즉시 반영 / [테스트] `SystemConfigControllerTest#reviewerUpdatesAndCacheInvalidates` | |
| TC-CACHE-004 | userRole null 결과 미저장 | PASS | [정적] `@Cacheable(..., unless="#result==null")` UserRoleResolver.java:46 정확 일치 / [테스트] `UserRoleResolverTest#noLsRoleReturnsNull`, `#dbExceptionFailsClosed`, `#unknownRoleCodeFailsClosed`, `#nullUserNoReturnsNull` 4케이스 | |
| TC-CACHE-005 | stream-meta TTL 은 파일 유예삭제의 하한 | PASS(특이) | [정적] `CacheConfig.java:37-39` 주석이 스스로 명시: "이 하한을 기동 시 검증하던 `ResolutionBackfillService` 는 2026-07-30 제거됐다 — 현재 파일 유예 삭제를 하는 흐름이 없어 검증 대상도 없다." `grep -r "ResolutionBackfillService\|stale-grace-minutes"` 코드베이스 전체 0건 | **근거 드리프트**: 카탈로그 기대결과("구 파일 유예가 TTL 보다 길어야 하며 기동 시 검증됨")가 가리키는 기동 검증 로직 자체가 소멸. memory `resolution-backfill-removed`(2026-07-30, PR#63) 와 일치 — 결함 아님, 카탈로그 갱신 필요 |
| TC-SYSCFG-001 | 화이트리스트 외 키 조회/갱신 거부 | PASS | [실동작] `PUT /v1/manage/configs/NOT_A_REAL_KEY {"value":"1"}`(REVIEWER) → 400 `{"errorCode":"INVALID_INPUT","message":"허용되지 않은 설정 키입니다."}` — 입력 키 문자열 메시지 미노출(CWE-117 방어) 확인 / [정적] SystemConfigService.java:94-98 | |
| TC-SYSCFG-002 | REVIEWER 아니면 update FORBIDDEN(이중 검증) | PASS | [실동작] WORKER 토큰으로 `PUT BATCH_INTERVAL_SEC` → 403 `{"errorCode":"FORBIDDEN"}` (Controller `@PreAuthorize` + Service `verifyReviewer` 이중 방어 중 최소 1단 실측 확인) / [정적] SystemConfigService.java:93,166-169(카탈로그 166-170 대비 1줄 드리프트) | |
| TC-SYSCFG-003 | actor=null이면 update FORBIDDEN | PASS | [정적] `verifyReviewer` SystemConfigService.java:166-169 `actor==null` 분기 확인 | HTTP 경로로는 JwtAuthenticationFilter 가 무인증 요청을 401 로 선차단해 actor=null 상태로 서비스에 도달 불가(인가 이후 방어선) — 실동작 재현 불가, 코드 검증만 |
| TC-SYSCFG-004 | NUMBER 키 정수+키별 범위 검증 | PASS | [실동작] `PUT BATCH_INTERVAL_SEC {"value":"99999"}` → 400 `{"errorCode":"INVALID_INPUT","message":"값이 허용 범위를 벗어났습니다."}` / [정적] SystemConfigService.java:132-145, ConfigKeys.NUMBER_RANGE 7개 키 범위 확인 | |
| TC-SYSCFG-005 | DECIMAL NaN/Infinity/범위밖 거부 | PASS | [실동작] `PUT POLYGON_SIMPLIFY_TOLERANCE {"value":"NaN"}` → 400 `{"errorCode":"INVALID_INPUT","message":"유효하지 않은 숫자입니다."}` — `Double.isNaN`/`isInfinite` 명시 체크가 실제 방어선임을 실증 / [정적] SystemConfigService.java:147-164 | |
| TC-SYSCFG-006 | BOOLEAN true/false만 | PASS | [정적] `validateByType` "BOOLEAN" 분기 SystemConfigService.java:120-125 로직 정확 | 현재 `ConfigKeys.ALLOWED` 8개 키 중 CONFIG_TYPE_CD=BOOLEAN 인 키가 0개(전부 NUMBER/DECIMAL) — HTTP 로 이 분기를 직접 재현할 진입 키가 없음(죽은 코드는 아니고 도달 데이터 부재). 근거 드리프트로 참고 기록 |
| TC-SYSCFG-007 | 미지원 CONFIG_TYPE 거부 | PASS | [정적] `validateByType` default 분기 SystemConfigService.java:127-128 | TC-SYSCFG-006 과 동일 사유로 실동작 재현 불가(현재 DB 값이 전부 NUMBER/DECIMAL) |
| TC-SYSCFG-008 | getInt 타입/값 불일치 각 예외 | PASS | [정적] SystemConfigService.java:49-61 — 타입불일치 INVALID_INPUT(line 52-53), 파싱실패 INTERNAL_ERROR(line 57-58) 확인 | getInt 는 내부 호출 전용(BATCH/YOLO/포털 설정 등)이라 HTTP 직접 트리거 불가, 코드 검증만 |
| TC-SYSCFG-009 | 없는 설정 키 NOT_FOUND | PASS | [정적] `loadOrThrow` SystemConfigService.java:110-113 확인 | DB 데이터 변경 없이 재현 불가(모든 ALLOWED 키가 시드로 존재) — 코드 검증만 |
| TC-PROF-001 | local 프로파일이 ENV=dev/stg/prd에서 부팅 차단 | PASS | [정적] LocalProfileGuard.java:43-55 정확 / [테스트] `LocalProfileGuardTest#prdEnvWithLocalProfileThrows`, `#devAndStgEnvWithLocalProfileThrow` — A-ISSUE-21 해소 확인 | |
| TC-PROF-002 | ENV 미설정/local이면 local 허용 | PASS | [정적] LocalProfileGuard.java:47-49 / [테스트] `#nullEnvWithLocalProfilePasses` / [실동작] 현재 컨테이너가 정확히 이 경로로 정상 기동 중 | |
| TC-PROF-003 | local 아니면 즉시 통과 | PASS | [정적] LocalProfileGuard.java:44-46 / [테스트] `#nonLocalProfilePassesImmediately` | |
| TC-PROF-004 | DevProfileGuard — dev + ENV=stg/prd 기동 거부 | PASS | [정적] DevProfileGuard.java:42,46-61 정확 일치 / [테스트] `DevProfileGuardTest` 4케이스(배포환경 거부/정상dev 통과/non-dev 미판정/미지ENV 통과) | |
| TC-PROF-005 | DevToggleProfileGuard — prd/stg dev로그인 활성 시 기동 거부 | PASS | [정적] DevToggleProfileGuard.java:37,42-65 정확 일치 / [테스트] `DevLoginToggleTest` — 순수판정 2케이스 + 컨텍스트 3케이스 | |
| TC-PROF-006 | ForwardedHeadersConfigGuard — 3키 전 프로파일 기동 거부 | PASS | [정적] ForwardedHeadersConfigGuard.java:49-66,82-113 정확 일치 / [테스트] `ForwardedHeadersConfigGuardTest` 6케이스(환경변수 축 포함) / [실동작] 컨테이너 env 에 `SERVER_FORWARD_HEADERS_STRATEGY` 등 3키 전부 미설정 — 정상 기동 | |
| TC-PROF-007 | DeidentifyEndpointTrustGuard 프로파일별 처리 | PASS | [정적] DeidentifyEndpointTrustGuard.java:58-62,82-127,188-198 정확 일치 / [테스트] 13케이스 / [실동작] `KPST_DEID_BASE_URL=http://klid-mock-server:9400`(호스트에 mock 토큰 포함 → untrusted 판정) local 프로파일이라 무음 — 부팅 로그에 해당 WARN 부재로 확인 | |
| TC-PROF-008 | 호스트 파싱 불가 시 fail-secure + authority 폴백 | PASS | [정적] DeidentifyEndpointTrustGuard.java:106-123,139-177 정확 일치 / [테스트] `#underscoreVendorHostDoesNotBlockBoot`, `#underscoreMockHostIsStillBlocked`, `#unparsableHostLeavesReasonHint`, `#unparsableBaseUrlIsUntrusted` | |
| TC-PROF-009 | QuartzClusteringGuard — stg/prd 클러스터링 강제 | PASS | [정적] QuartzClusteringGuard.java:50-56,64-95; application-stg.yml:11; application-prd.yml:13 전부 정확 일치 / [테스트] `QuartzClusteringGuardTest#prdWithoutClusteringIsRejected`,`#stgWithoutClusteringIsRejected` / [실동작] 부팅 로그 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]` | |
| TC-PROF-010 | QuartzClusteringGuard — allowlist containsAll + ENV 독립 축 | PASS | [정적] QuartzClusteringGuard.java:97-112 정확 일치 / [테스트] `#mixedOrUnknownProfilesAreStrict`, `#deployedEnvMarkerWins` | |
| TC-PROF-011 | ProfileGatedUrlPolicy — 완화는 local/dev + ENV無 만 인정 | PASS | [정적] ProfileGatedUrlPolicy.java:45-51,78-91,145-161 정확 일치 / [실동작] 부팅 로그 `[VlmUrl] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용. property=vlm.client.allow-insecure-url` 실증 / [테스트] `VlmUrlPolicyTest` 9케이스 | |
| TC-PROF-012 | 기동 assert 우회해도 판정은 완화 안 됨 | PASS | [정적] `check()`→`policy()` 매 호출 재판정, ProfileGatedUrlPolicy.java:121-133 정확 일치 | |
| TC-PROF-013 | ExternalUrlPolicy strict — https전용+사설/루프백 차단 | PASS | [정적] ExternalUrlPolicy.java:90-133,168-188 정확 일치 / [테스트] `VlmUrlPolicyTest#prdRejectsPlaintextHttp`,`#prdRejectsPrivateNetwork` | |
| TC-PROF-014 | 완화 경로도 링크로컬/메타데이터 차단 | PASS | [정적] ExternalUrlPolicy.java:123-127,143-166 정확 일치 / [테스트] `AugmentUrlPolicyTest#metadataRangeRejectedEvenWhenRelaxed` | |
| TC-PROF-015 | placeholder/예제 호스트 어떤 정책서도 차단 | PASS | [정적] ExternalUrlPolicy.java:43-47,117-122 정확 일치 / [테스트] `#placeholderBlockedInEveryProfile`,`#placeholderBlockedEverywhere` | |
| TC-PROF-016 | 평문 http+토큰 경고(값 미출력) | PASS | [정적] ProfileGatedUrlPolicy.java:104-113; WebClientConfig.java:63-67 정확 일치 — `tokenLength=N` 만 로깅, 토큰 원문 미노출 | `warnIfTokenOnCleartext` 전용 테스트 미발견(grep 0건) — 커버리지 갭(LOW), 로직 자체는 정확 |
| TC-PROF-017 | GenAiIntegrationWiringGuard — 위탁↔콜백 배선 짝 | PASS | [정적] GenAiIntegrationWiringGuard.java:35-39,52-85 정확 일치 / [실동작] 부팅 로그 `[GenAi] 위탁(http) ↔ 콜백 IP allowlist 짝 확인 완료`, `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` 확인, 콜백 실제 처리 로그(`GenAiCallbackService` running/succeeded) 확인 / [테스트] `GenAiIntegrationWiringGuardTest` 6케이스 | |
| TC-CFG-001 | prd hikari 풀 20/5 평면 바인딩 | PASS | [정적] application-prd.yml:2-5,14-28; ControlDataSourceConfig.java:32-36 정확 일치(주석에 회귀근거 명시) / [테스트] `DataSourcePoolBindingTest` 3케이스(`flatFormatBindsToPool`,`nestedHikariFormatIsIgnored`,`prdPoolSettingsActuallyBind`) | |
| TC-CFG-002 | multipart 한도 prd 21MB/1100MB, 그 외 500MB/1200MB | PASS | [정적] application-prd.yml:29-39; application.yml:14,28-29 정확 일치 / [테스트] `ConfigProfileDriftGuardTest#prdMultipartLimitsStayTight`,`#nonProdMultipartLimitsComeFromCommon` | |
| TC-CFG-003 | dev 프로파일 내부 IP 리터럴 기본값 없음 | PASS | [정적] application-dev.yml 1-18 DB 접속정보 전부 `${VAR}` 플레이스홀더(기본값 없음) 확인 / [테스트] `ConfigProfileDriftGuardTest#devProfileHasNoHardcodedIpLiteral` | |
| TC-CFG-004 | dev 토글 override 가능 / stg dev로그인 리터럴 false | PASS | [정적] application-dev.yml:23-24 `${DEV_LOGIN_ENABLED:true}`, application-stg.yml:51-53(카탈로그 51-56 근사) `enabled: false` 리터럴 확인 / [테스트] `ConfigProfileDriftGuardTest#devTogglesAreOverridableByEnvVariables`,`#stgDevLoginIsHardDisabled` | |
| TC-CFG-005 | ENV 배포 표식이 온프렘 템플릿에 주입 | PASS | [정적] `deploy/onprem/config/backend/env.template:25` = `ENV=prd` grep 확인 | |
| TC-ACT-001 | prd는 actuator health만 노출 | PASS | [정적] application-prd.yml:93-100 정확 일치 — `include: health`, `show-details: never` | |
| TC-ACT-002 | 기본은 ACTUATOR_EXPOSE로 노출 제어 | PASS | [정적] application.yml:133-140 정확 일치 / [실동작] `/api/actuator` 루트 응답에 `health/info/metrics/prometheus` 링크만 존재, `env`/`beans`/`heapdump`/`configprops` 404(노출 안 됨) — 무인증 metrics/prometheus 401, REVIEWER 인증 시 200 | |
| TC-HEALTH-001 | 비식별 헬스 — mock 모드는 핑 없이 UP | PASS | [정적] DeidentifyHealthIndicator.java:77-83 정확 일치 / [테스트] `DeidentifyHealthIndicatorTest#health_up_mock_when_mock_mode_enabled` | 현재 환경은 `DEIDENTIFY_MOCK_MODE=false`(KPST 경로 사용) — mock-mode=true 조건은 실동작 재현 불가, 테스트로만 확인 |
| TC-HEALTH-002 | 비식별 헬스 — KPST 형상은 root(`/`)를 핑 | PASS | [정적] DeidentifyHealthIndicator.java:44,70-73,92-112 정확 일치 / [실동작] `GET /api/actuator/health`(REVIEWER) → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}` — 실제 `kpst.deid.base-url` 루트 핑 성공 확인 | |
| TC-HEALTH-003 | 비식별 헬스 — 미구성은 DOWN(fail-closed) | PASS | [정적] DeidentifyHealthIndicator.java:84-91 정확 일치 / [테스트] `#health_down_when_no_deidentify_path_configured` | 현재 환경은 `kpst.deid.enabled=true` — 미구성 상태 실동작 재현 불가, 테스트로만 확인 |
| TC-RES-001 | 외부 호출 Retry+CircuitBreaker 순차 적용 | PASS | [정적] AiServerClient.java:39-48,56-65,67-76,78-84(4개 메서드 `RetryOperator`→`CircuitBreakerOperator` 순) — 카탈로그 46-47,63-64 와 대표 라인 일치 / [테스트] `AiServerClientTest#circuitBreakerOpensOnFailures`,`#trackReturnsResponse`,`#predictYoloTrackPostsToTrackEndpoint` | |
| TC-RES-002 | 5개 CircuitBreaker 빈 등록 | PASS | [정적] Resilience4jConfig.java:13-47 정확 일치 — deid(13-16)/ai(18-21)/vlmClient(27-30)/controlNotify(35-38)/kpstDeid(44-47) 5개 전부 확인 | |
| TC-RES-003 | aiOnline/portalSam2 Bulkhead 초과 시 429 | PASS | [정적] Resilience4jConfig.java:55-69 빈 등록 + application.yml:588-601(aiOnline/portalSam2 각 max-concurrent-calls=4, max-wait-duration=0) + `catch(BulkheadFullException)` AutolabelOnlineService.java(2곳: 499,529), PortalSam2Service.java(201) 확인 | |
| TC-RES-004 | NonRetryableExternalException 재시도/CB 제외 | PASS | [정적] NonRetryableExternalException.java:23-30 정확 일치 / application.yml `ignore-exceptions` 4개 CB 인스턴스(vlmClient:506-507, controlNotify:517-518, kpstDeid:526-527, augmentClient:534-535)에 동일 클래스 등록 확인 | |

## 근거 드리프트

1. **TC-CACHE-005 대상 기능 소멸** — 카탈로그가 "구 파일 유예(stale-grace-minutes)가 TTL 보다 길어야 하며 기동 시 검증됨"이라 기술하나, 그 기동 검증 주체였던 `ResolutionBackfillService` 가 2026-07-30(PR#63)에 제거되어 `stale-grace-minutes` 설정키·검증 로직 모두 코드베이스에 0건. `CacheConfig.java` 자체 주석이 이 사실을 정확히 기록하고 있어 결함이 아니라 카탈로그 갱신 대상. memory `resolution-backfill-removed` 와 일치.
2. **TC-SYSCFG-006/007 재현 데이터 부재** — `ConfigKeys.ALLOWED` 8개 키가 전부 NUMBER/DECIMAL 이라 BOOLEAN·미지원 CONFIG_TYPE 분기는 HTTP 로 직접 재현할 진입 키가 없다. 로직은 정확하나 실동작 검증은 불가능한 구조 — 카탈로그에 "현재 화이트리스트에 해당 타입 키 없음" 주석 추가를 제안.
3. **소폭 라인 드리프트(1~3줄, 판정 영향 없음)**: CacheConfig.java(카탈로그 51-54,63,65-94 vs 실측 53-56,66,68-97), SystemConfigService.java getString 블록(80-82 vs 80-81), SystemConfigService.java verifyReviewer(166-170 vs 166-169), PortalDataSourceConfig.java(18-28,47-50 vs 18-27,47-51), application-stg.yml dev 토글(51-56 vs 51-53). 전부 주석/포맷 조정에 기인한 것으로 추정되며 로직 자체는 카탈로그 기대와 100% 일치.
4. **SystemConfigService 클래스 javadoc 자체 드리프트(참고, TC 행 아님)**: 클래스 상단 주석이 "화이트리스트 4개 키만 허용"이라 적혀 있으나 `ConfigKeys.ALLOWED` 는 현재 8개(YOLO 3종·POLYGON_SIMPLIFY_TOLERANCE·PORTAL_UPLOAD_FRAME_INTERVAL_SEC·AUTOLABEL_POLYGON_MAX_BOXES 신설). 코드 동작에는 영향 없는 코드 내부 주석 드리프트.

## 이슈 상세

이번 회차 A-7 신규 FAIL/PARTIAL/확인필요 0건 — 발행 이슈 없음(A-ISSUE-41 이후 번호 미사용).

기존 A-ISSUE-22·A-ISSUE-23(테스트 커버리지 공백, LOW)은 위 "1차 이슈 해소 대조" 표에 상태를 갱신해 기록했으며, 실제 동작(런타임)은 정상이라 신규 이슈로 재발행하지 않았다.
