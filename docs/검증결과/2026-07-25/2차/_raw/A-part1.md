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
