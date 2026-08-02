# A-part1 — A-1. 인증 (JWT) 전수 검증 결과

> 대상: `docs/test-cases/A-auth-common.md` `## A-1. 인증 (JWT)` — **34건**(TC-AUTH-001~032 + TC-COMMON-001~002)
> 검증일: 2026-08-01 · 회차: 1차 · 코드 기준: qa-0801 워크트리(커밋 56d30478) · 실동작 기준: 기동 중 스택(klid-backend, flyway V146)
> ⚠ **이슈 ID 주의** — 본 파트의 `A-ISSUE-01/02`는 **이 파트 전용 신규 번호**다. `UNCERTAINTIES.md` 미해소 이월표의 구 `A-ISSUE-01`(UserRoleResolver 프록시 캐시키 NPE)과는 **다른 항목**이다.

## 0. 검증 환경 실측

| 항목 | 실측값 |
|---|---|
| backend | `http://localhost:18081/api` (context-path `/api`), profile `local` |
| `JWT_SECRET` | 96바이트 hex 문자열(≥32B 충족) — `docker exec klid-backend printenv` |
| `JWT_ISSUER` | `klid-auth` |
| `JWT_ALLOWED_ISSUERS` | `klid-auth,klid,klid-portal` (= 코드 기본값과 동일) |
| DB | `klid-postgres` / `klid_system` / 스키마 `public` |
| `LS_USER_ROLE` 실데이터 | 1001=REVIEWER, 1002=REVIEWER, 2001=WORKER, 2002=WORKER, 3001=PORTAL_USER |

검증 방식: HS256 JWT를 파이썬으로 직접 조립(헤더/페이로드/서명 각각 조작)해 curl 로 보호 API 호출 → 응답 원문 대조.
캐시 적중 여부는 `pg_stat_user_tables.seq_scan+idx_scan` (relname=`ls_user_role`) 델타로 실측.
**본 파트 담당 구간은 최근 커밋(b2b44f0e~56d30478, LS_DATA_INGEST·증강 폐기/복구)과 무관** — 인증 필터·역할 해석기·시크릿/이슈어 검증기 전부 해당 커밋 범위 밖이라 **BLOCKED 사유(환경 버전 격차) 해당 없음**.

## 1. 케이스별 판정

| ID | 판정 | 근거 확인 | 비고 |
|----|------|-----------|------|
| TC-AUTH-001 | PASS | [실동작] `Bearer <HS256, sub=1001, iss=klid-auth, name=홍길동>` → `GET /api/v1/me` **200** `{"userId":"1001","name":"홍길동","role":"REVIEWER","channel":"INTERNAL"}`. 같은 토큰으로 `/actuator/metrics`(REVIEWER 전용) 200 → `ROLE_REVIEWER` authority 실증. `/v1/videos` 200 → `CHANNEL_INTERNAL` authority 실증 | `JwtAuthenticationFilter.java:56-111` 라인 일치(드리프트 없음). 테스트: `common/security/JwtAuthenticationFilterTest#validJwtSetsContext` (baseline 통과) |
| TC-AUTH-002 | PASS | [실동작] 헤더 없이 `GET /api/v1/me` → **401** `{"errorCode":"UNAUTHORIZED"}` (entryPoint) | `JwtAuthenticationFilter.java:55-56,117` 일치. `JwtAuthenticationFilterTest#noJwtReturns401` |
| TC-AUTH-003 | PASS | [실동작] `Authorization: Token <valid>` → 401 / `bearer <valid>`(소문자) → 401 / `Bearer<valid>`(공백 없음) → 401. 유효 토큰인데도 3건 모두 401 = 파싱 미시도 | [정적] `BEARER_PREFIX="Bearer "` + `header.startsWith(...)` 대소문자 정확 매칭(`:28,56`) |
| TC-AUTH-004 | PASS | [실동작] 다른 키(`wrong-secret-...`)로 HMAC 서명한 동일 페이로드 → **401**. 추가로 서명부만 제거(`h.p.`)한 토큰도 401 | `JwtAuthenticationFilter.java:112-115` catch → `clearContext()` 일치. `JwtAuthenticationFilterTest#invalidSignatureReturns401` |
| TC-AUTH-005 | **PARTIAL** | [실동작] `exp=now-1s` → 401, `exp=now-30s` → 401(clock skew 0 확인), `exp=now+2s` → 200 → **exp 과거 거부는 정상**. 그러나 **`exp` 클레임이 아예 없는 토큰은 200 으로 통과**(`/v1/me`·`/v1/videos` 모두 200) — 무기한 유효 | 결함 → **A-ISSUE-01**. `Jwts.parser()`에 `requireExpiration()`/필수 클레임 검증 없음(`:59-62`), `TokenClaims.exp` 는 null 허용(`:91`) |
| TC-AUTH-006 | PASS | [실동작] `{"alg":"none"}` + 서명부 빈 토큰 → **401** | `parseSignedClaims` 가 unsecured JWT 거부 → `JwtException` catch(`:112`) |
| TC-AUTH-007 | PASS | [실동작] `Bearer garbage` → 401, `Bearer ..` → 401, `Bearer `(빈 토큰) → 401 | `:112` |
| TC-AUTH-008 | PASS | [실동작] `iss="evil"` (서명 유효) → **401**. 서명은 통과하지만 issuer 게이트에서 컨텍스트 클리어 후 early return | `JwtIssuerValidator.java:22-27` + `JwtAuthenticationFilter.java:65-70` 라인 일치 |
| TC-AUTH-009 | PASS | [실동작] `iss` 부재 → 401, `iss="   "`(공백) → 401 | `JwtIssuerValidator.java:23-25` null/isBlank fail-closed |
| TC-AUTH-010 | PASS | [실동작] `iss=klid` / `klid-portal` / `klid-auth` 3종 모두 200. 런타임 `JWT_ALLOWED_ISSUERS` 가 코드 기본값과 동일해 기본값 경로를 그대로 실증 | `JwtIssuerValidator.java:15` |
| TC-AUTH-011 | PASS | [정적] `:16-19` `map(String::trim).filter(!isEmpty).toUnmodifiableSet()` → `" klid , , x "` = {klid,x}. 전부 공백이면 `allowed=∅` 이고 `isAllowed` 가 `allowed.contains()` 이므로 **전건 false = fail-closed**. 조회 측은 trim 하지 않아(더 엄격) 우회 없음 | ⚠ **테스트 공백** — `JwtIssuerValidatorTest`(2건)는 trim/빈항목 제거·∅ fail-closed 케이스를 커버하지 않음. 설정 변경이 불가해 실동작 미확인 |
| TC-AUTH-012 | **PARTIAL** | [실동작] `channel` 클레임 부재 → `channel=INTERNAL` 로 응답(기대결과 자체는 충족). 그러나 **`iss=klid-portal` 토큰도 `channel` 만 없으면 INTERNAL 로 승격**되어 `sub=1001` 로 `role=REVIEWER` 획득 확인(200) — issuer와 channel 이 무결합 | 결함 → **A-ISSUE-02**. `JwtAuthenticationFilter.java:72-73` |
| TC-AUTH-013 | PASS | [실동작] `channel=PORTAL` + `sub=1001`(LS:REVIEWER) → `role=PORTAL_USER`. `sub="abc"` / `sub` 부재 / `sub=2001`+`role=REVIEWER` 주장 → 전부 `PORTAL_USER`. 즉 LS 미조회 + sub 무관 확인. 해당 토큰으로 `/v1/videos` 403 | `JwtAuthenticationFilter.java:84-86` 일치 |
| TC-AUTH-014 | PASS | [실동작] `channel="X"` → 401, `channel="internal"`(소문자) → 401, `channel=123`(비문자열) → 401. `Channel.valueOf` IAE 및 `RequiredTypeException` 모두 catch(`:112`)로 fail-closed | `:73,112` 일치. `JwtAuthenticationFilterTest#invalidChannelClaimReturns401` |
| TC-AUTH-015 | PASS | [실동작] ①`sub=1001`+JWT `role=LEARN_MANAGER` → 응답 `role=REVIEWER`(LS 값) ②`sub=2001`(LS:WORKER)+JWT `role="REVIEWER"` 주장 → `role=WORKER`, `/actuator/metrics` **403**, `/v1/system/configs` **403**, `/v1/videos` 200. ③미매핑 sub + `role`/`authorities` 클레임 주장 → `/v1/videos` **403** → JWT role 클레임 인가 미참조 실증 | `JwtAuthenticationFilter.java:80-86`, `UserRoleResolver.java:46-61` 라인 일치 |
| TC-AUTH-016 | PASS | [실동작] `sub="abc"` → `/v1/me` 200 이지만 `role=null`, `/v1/videos` **403**. `sub="1001abc"` 도 `role=null` | `:82-83,125-134` 일치. `parseUserNo` NumberFormatException→null |
| TC-AUTH-017 | PASS | [실동작] `sub` 부재 → `userId=null,role=null`, `sub="  "` → 동일. 추가로 `sub`가 JSON 숫자(비문자열)면 401(fail-closed) | `:126-128` |
| TC-AUTH-018 | PASS | [실동작] `sub=777777`(LS 매핑 없음) → `/v1/me` 200 `role=null,channel=INTERNAL` | `UserRoleResolver.java:52-55` `orElse(null)` |
| TC-AUTH-019 | PASS | [실동작] role=null 토큰으로 `/v1/videos` **403**, `/v1/event-types` **403**, `/v1/notices` **403** / `/v1/me` 만 200 → A-ISSUE-02(1차) 해소 확인 | `SecurityConfig.java:84,147-152` 라인 일치. 참고: `/v1/auth/**` permitAll 화이트리스트는 역할 게이트와 별개 축(A-2 소관) |
| TC-AUTH-020 | PASS | [실동작] `name="홍길동"` → `/v1/me` 응답 `name` 노출. `name` 부재/`null` → `name:null` 이고 NPE·500 없음. `name`에 CRLF·`<script>` 주입해도 JSON 이스케이프되어 본문에만 반영(응답 헤더 오염 없음). `name`이 비문자열이면 401(fail-closed) | `:108-111` 일치 |
| TC-AUTH-021 | PASS | [실동작] role=null 토큰 → `/v1/videos/4/stream` **403**, 위조 `?exp=..&sig=deadbeef&u=1001` 붙여도 **403**(서명 필터가 이미 인증된 컨텍스트에 개입하지 않음). 익명+위조서명 → **401** [정적] `AUTHORITY_STREAM_SIGNED` 부여 지점은 전 소스에서 `StreamSignatureFilter.java:138` **1곳뿐**(grep 실증) — JWT 경로는 `ROLE_*`/`CHANNEL_*` 만 부여 | `JwtAuthenticationFilter.java:96-103`, `StreamSignatureFilter.java:55-63,138` 일치 |
| TC-AUTH-022 | PASS | [정적] `JwtAuthenticationFilter.java:37-48` 3개 인자 각각 null 검사 후 `IllegalArgumentException("... (fail-closed)")` | ⚠ **테스트 공백** — `new JwtAuthenticationFilter(` 호출은 프로덕션 `SecurityConfig.java:47` 1곳뿐이고 생성자 null 거부 단위테스트 없음 |
| TC-AUTH-023 | PASS | [정적] `SecretKeyResolver.java:18-22` `bytes.length < 32` → IAE. `@Component` 생성자 주입이므로 빈 생성 실패 = 기동 거부 | 테스트: `common/security/SecretKeyResolverTest#rejectsShortInput` (baseline 통과) |
| TC-AUTH-024 | PASS | [정적] `:17-29` `Keys.hmacShaKeyFor` 결과를 `final SecretKey key` 에 1회 보관, `resolve()` 는 동일 인스턴스 반환 [실동작] 96바이트 시크릿으로 기동된 스택이 정상 서명 검증 | `SecretKeyResolverTest#acceptsInputAtLeast32Bytes` |
| TC-AUTH-025 | PASS | [정적] `UserRoleResolver.java:56-60` `catch (DataAccessException)` → `return null` + `log.warn("... userNo={} ...")` — 토큰/PII 미출력 | 테스트: `UserRoleResolverTest#dbExceptionFailsClosed`. 비고: `CannotCreateTransactionException`(TransactionException 계열, DataAccessException 아님)은 catch 밖이라 500 으로 전파되나 **권한 부여는 일어나지 않아 fail-closed 성질은 유지**. DB 정지는 병렬 검증 에이전트에 영향을 주므로 실동작 미시도 |
| TC-AUTH-026 | PASS | [정적] `:73-80` `Role.valueOf` IAE catch → null. `role_cd`가 null 이면 `Optional.map` 단계에서 empty → `orElse(null)` 이라 `valueOf(null)` NPE 미도달. 소문자 코드도 valueOf 실패 → fail-closed | 테스트: `UserRoleResolverTest#unknownRoleCodeFailsClosed`, `JwtAuthenticationFilterTest#controlRoleCodeEnumMismatchReturns403`. DB 쓰기 금지로 실동작 미시도 |
| TC-AUTH-027 | PASS | [정적] 기대결과가 기술한 현재 동작과 일치 — `:46` `@Cacheable(key="#userNo")` 에 `condition` 없음(NPE 소지 유지), 메서드 본문 `:48-50` 은 null 반환. **실경로 도달 없음 재확인**: `resolve()` 호출부는 `JwtAuthenticationFilter.java:83`(null 선차단)과 `StreamSignatureFilter.java:165`(null/blank/NumberFormat 선차단) **2곳뿐**(grep 실증) | 구 `A-ISSUE-01`(1차 이월, LOW) 미해소 유지. 본 파트 신규 이슈로 재기록하지 않음 |
| TC-AUTH-028 | PASS | [실동작] `sub=3001` 워밍 후 동일 토큰 30회 호출 → `ls_user_role` 스캔 카운터 델타 **0** (301→301). 동일 실험 2001 사용자로도 델타 0 재현 → 캐시 적중 실증. TTL 62초 경과 후 1회 호출 시 카운터 재증가(만료 후 재조회) | `CacheConfig.java:85-92`(userRole, max 500, expireAfterWrite 60s), `UserRoleResolver.java:46`. ⚠ 근거 드리프트: 카탈로그 `CacheConfig.java:85-89` → 실제 **88-92** |
| TC-AUTH-029 | PASS | [실동작] 서로 다른 미매핑 sub 30명 호출 → 스캔 델타 **+30**(269→299), 동일 미매핑 sub 30회 반복도 델타 **+30** → null 결과 미캐시 확정. `cache.size{name=userRole}` 도 미매핑 호출 후 증가 없음(2 유지) | `unless="#result == null"`(`:46`) |
| TC-AUTH-030 | PASS | [정적] `@Cacheable(key="#userNo")`(`:46`) 와 `@CacheEvict(key="#userNo")`(`:67`) 키 표현식·캐시명(`CACHE_USER_ROLE`) 동일 → 무효화 적중 | 테스트: `common/security/UserRoleCacheEvictIT#roleChangeEvictsCacheAfterCommit` (baseline 통과) |
| TC-AUTH-031 | PASS | [정적] `UserRoleResolver.java:63-70` + `RoleClaimService.java:167-179` — `TransactionSynchronizationManager.registerSynchronization(...afterCommit → evict)` 로 커밋 후 무효화, 동기화 미활성 시 즉시 evict 폴백. `UserService.java:176,180` 도 동일 패턴 | 테스트: `UserRoleCacheEvictIT`. 강등 실동작은 DB 쓰기(역할 변경)가 필요해 미시도(검증 전용 제약) |
| TC-AUTH-032 | PASS | [정적] `TokenClaims.java:13-15` `role == null ? null : role.name()` [실동작] role=null 토큰의 `/v1/me` 가 500 없이 `role:null` 반환(`SessionController.java:42` 동일 방어) | 테스트: `common/security/TokenClaimsTest` 2건 |
| TC-COMMON-001 | PASS | [실동작] 미인증 `GET /api/v1/me` → **401** `{"success":false,"errorCode":"UNAUTHORIZED"}` (entryPoint 경유, 스택트레이스·내부경로 미노출) [정적] 컨트롤러 도달 시 방어 `SessionController.java:36-39` | `SecurityConfig.java:84,156` 일치. `SessionControllerTest#meWithoutTokenReturns401` |
| TC-COMMON-002 | PASS | [실동작] 유효 토큰 → `{"userId":"1001","name":"홍길동","role":"REVIEWER","channel":"INTERNAL"}`. role=null / channel 조합에서도 null 안전(`role:null` 반환, 500 없음) | `SessionController.java:35-45` 일치 |

### 집계

| 판정 | 건수 |
|------|:---:|
| PASS | 32 |
| FAIL | 0 |
| PARTIAL | 2 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **34** |

### 근거 드리프트 (카탈로그 정합성)

| TC | 카탈로그 표기 | 실제 |
|----|------|------|
| TC-AUTH-028 | `CacheConfig.java:85-89` | `CacheConfig.java:88-92` (userRole 캐시 블록) |

그 외 A-1 전 케이스의 `file:line` 은 현재 소스와 일치했다(드리프트 1건).

---

## 2. 이슈 블록

### [A-ISSUE-01] TC-AUTH-005 — `exp` 클레임이 없는 JWT가 무기한 유효(만료 강제 부재)

- **심각도**: HIGH
- **기대 동작(기대효과)**: 저작도구는 자체 로그인이 없고 관제/포털이 발급한 JWT 를 그대로 신뢰하는 구조이며 **토큰 폐기(revocation/blacklist) 수단이 전혀 없다**. 따라서 토큰 수명 상한은 오직 `exp` 로만 강제된다. 프로젝트 규칙(`.claude/rules/security.md` — "만료 시간(`exp`) 필수 설정", JWT 보안 절)도 이를 명시한다. 즉 **`exp` 가 없는 토큰은 검증 실패(401)로 거부**되어야 한다. 그래야 토큰이 유출·캡처됐을 때 노출 창이 유한해진다.
- **현재 동작(이슈 내용)**: 파서에 필수 클레임 요구가 없어 `exp` 부재 토큰이 정상 인증된다. jjwt 는 `exp` 가 없으면 만료 검사를 건너뛴다.
  ```java
  // JwtAuthenticationFilter.java:59-62
  Jws<Claims> jws = Jwts.parser()
          .verifyWith(keyResolver.resolve())
          .build()                       // ← requireExpiration()/require("exp") 없음
          .parseSignedClaims(token);
  // JwtAuthenticationFilter.java:87-92 — exp 가 null 이어도 그대로 TokenClaims 생성
  body.getExpiration() == null ? null : Instant.ofEpochMilli(body.getExpiration().getTime())
  ```
  실동작 근거 (요청/응답 원문):
  ```
  payload = {"sub":"1001","iss":"klid-auth"}          ← exp 클레임 자체를 뺀 HS256 토큰
  GET /api/v1/me            → 200 {"success":true,"data":{"userId":"1001","role":"REVIEWER","channel":"INTERNAL"}}
  GET /api/v1/videos?page=0&size=1 → 200 {"success":true,"data":{"totalElements":26, ...}}
  ```
  비교군(정상 동작): `exp=now-1s` → 401, `exp=now-30s` → 401(clock skew 0), `exp=now+2s` → 200.
  또한 `grep -rn "requireExpiration\|clockSkewSeconds" backend/src/main/java/kr/co/cudo/authoring/{common/security,auth}` 결과 **0건** — 만료 필수화·허용 오차 설정이 코드 어디에도 없다.
- **재현/확인 경로**:
  ```bash
  # 헤더/페이로드/서명을 직접 조립 (SECRET = backend 컨테이너의 JWT_SECRET)
  H=$(printf '{"alg":"HS256","typ":"JWT"}' | base64 | tr -d '=' | tr '/+' '_-')
  P=$(printf '{"sub":"1001","iss":"klid-auth"}' | base64 | tr -d '=' | tr '/+' '_-')
  S=$(printf "%s.%s" "$H" "$P" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64 | tr -d '=' | tr '/+' '_-')
  curl -i -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/me   # → 200 (기대: 401)
  ```
- **영향**: 보안 — **CWE-613(Insufficient Session Expiration)** / CWE-1270. 부수적으로 CWE-287. 폐기 수단이 없는 상태에서 만료 없는 베어러 토큰이 성립하면, 프록시 로그·브라우저 스토리지·URL 공유 등으로 한 번 유출된 토큰이 **영구 유효한 REVIEWER 자격증명**이 된다(REVIEWER 는 사용자 관리·시스템 설정·검수 승인 전권). 발급 주체가 외부 시스템(관제/포털)이라 저작도구는 "발급자가 항상 `exp` 를 넣는다"를 **검증할 수단도 강제할 수단도 없이 가정만 하고 있다** — 계약 위반 시 조용히 fail-open 된다.
- **수정 방향(제안)**: `JwtAuthenticationFilter.java:59-62` 의 파서 빌더에 만료 필수화를 추가한다(예: `Jwts.parser().verifyWith(...).require("exp", ...)` 가 값 고정이라 부적합하므로, `parseSignedClaims` 이후 `body.getExpiration() == null` 이면 issuer 게이트와 동일하게 `clearContext()` + early return 하는 fail-closed 분기를 `:65-70` 옆에 둔다). 동시에 `TokenClaims.exp` 를 non-null 계약으로 좁히고, 회귀 가드로 `JwtAuthenticationFilterTest` 에 "exp 없는 토큰 401" 케이스를 추가한다. (⚠ 구현은 하지 않음)

---

### [A-ISSUE-02] TC-AUTH-012 — `channel` 클레임 부재 시 무조건 INTERNAL 승격 — issuer 와 채널이 무결합

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 저작도구의 **채널 격리**(내부 `/v1/**` vs 포털 `/v1/portal/**`)는 `CHANNEL_INTERNAL`/`CHANNEL_PORTAL` authority 로만 강제된다(`SecurityConfig.java:134-152`). 외부 채널(포털) 토큰이 내부 채널로 승격되는 경로가 없어야 하며, 채널 판정은 **토큰 발급 출처(issuer)와 모순되지 않아야** 한다. 특히 INTERNAL 로 판정되면 `sub` 로 `LS_USER_ROLE` 을 조회해 REVIEWER/WORKER 를 부여하므로, 채널 오판정은 곧 권한 오부여다.
- **현재 동작(이슈 내용)**: `channel` 클레임이 없으면 issuer 와 무관하게 INTERNAL 로 기본 승격한다.
  ```java
  // JwtAuthenticationFilter.java:72-73
  String channelStr = body.get("channel", String.class);
  Channel channel = channelStr == null ? Channel.INTERNAL : Channel.valueOf(channelStr);
  // :81-83 → INTERNAL 이면 sub 로 LS_USER_ROLE 조회해 REVIEWER/WORKER 부여
  ```
  허용 issuer 목록에는 **포털 발급자 `klid-portal` 이 포함**되어 있고(`JwtIssuerValidator.java:15`, 런타임 `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal`), 서명 키도 채널별로 분리돼 있지 않다(단일 `authoring.jwt.secret`). 따라서 **`iss=klid-portal` 이면서 `channel` 이 없는 토큰은 내부 REVIEWER 로 인증된다.**
  실동작 근거:
  ```
  payload = {"sub":"1001","iss":"klid-portal","exp":<future>}   ← channel 클레임 없음
  GET /api/v1/me → 200 {"userId":"1001","name":null,"role":"REVIEWER","channel":"INTERNAL"}
  ```
  (대조군: 같은 토큰에 `"channel":"PORTAL"` 을 넣으면 `role=PORTAL_USER` + `/v1/videos` 403 으로 정상 격리된다.)
  파급: INTERNAL 로 판정된 무권한(role=null) 사용자는 온보딩 경로 `/v1/auth/role-claim` 의 진입 조건(`RoleClaimService.java:125-127` — `actor.channel() == INTERNAL && actor.role() == null`)까지 만족한다. 즉 포털 발급 토큰이 `channel` 만 누락돼도 **자가부여 온보딩 표면에 도달**한다(교차채널 차단이 그 조건 하나에 의존).
- **재현/확인 경로**:
  ```bash
  # channel 클레임만 뺀 포털 issuer 토큰
  P=$(printf '{"sub":"1001","iss":"klid-portal","exp":9999999999}' | base64 | tr -d '=' | tr '/+' '_-')
  curl -s -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/me
  # → {"role":"REVIEWER","channel":"INTERNAL"}   (기대: 401 또는 channel=PORTAL)
  ```
  ```sql
  -- 승격 대상이 되는 실제 매핑 (sub 충돌 시 부여되는 역할)
  select user_no, role_cd from ls_user_role order by user_no;
  ```
- **영향**: 보안 — **CWE-863(Incorrect Authorization)** / CWE-290(Spoofing by Authentication Bypass). 성립 조건은 "포털 발급자가 `channel` 클레임을 누락한 토큰을 발급" + "포털 `sub` 공간이 관제 `userNo` 공간과 겹침" 두 가지이며, 둘 다 **저작도구가 통제하지 못하는 외부 계약 가정**이다. 코드 주석은 이 기본값을 "fail-closed: 무클레임=INTERNAL → 내부 허용"이라 적고 있으나, 허용 issuer 에 외부 채널 발급자가 들어있는 현재 구성에서는 **fail-open 방향의 기본값**이다. (즉시 악용 가능한 결함은 아니므로 HIGH 가 아닌 MEDIUM.)
- **수정 방향(제안)**: 두 축 중 하나 이상.
  ① **issuer↔channel 바인딩** — `JwtIssuerValidator` 를 "issuer → 허용 채널 집합" 매핑으로 확장해 `klid-portal` 발급 토큰은 `channel=PORTAL` 만 허용하고, 불일치·누락이면 `:65-70` 과 동일하게 `clearContext()` + early return.
  ② **`channel` 필수화** — 기본값 승격을 없애고 클레임 누락 시 거부(하위호환이 필요하면 `authoring.jwt.default-channel` 같은 명시 설정 + 기동 시 경고로 한시 운용).
  회귀 가드로 `auth/SecurityConfigChannelTest` 에 "iss=klid-portal + channel 누락 → 401" 케이스를 추가한다. (⚠ 구현은 하지 않음)

---

## 3. 반증 시도했으나 결함이 아니었던 항목 (참고 — 재조사 방지)

| 반증 시나리오 | 결과 |
|---|---|
| `alg=RS256` 로 헤더만 바꾸고 HMAC 서명(알고리즘 혼동, CWE-347) | 401 — `verifyWith(SecretKey)` 가 비-HMAC alg 거부 |
| `alg=HS512` + HS256 서명값 | 401 |
| 서명부 제거(`h.p.`) / `Bearer ..` / `Bearer `(빈값) | 전부 401 |
| `nbf` 미래(+10분) 토큰 | 401 — jjwt 가 PrematureJwt 거부 |
| `Authorization` 헤더 2개 전송(유효+미매핑) | 첫 헤더만 사용, 권한 상승 없음 |
| JWT `role`/`authorities` 클레임 자가 주장 | 인가에 미반영(LS_USER_ROLE 단일 출처) — REVIEWER 전용 경로 403 |
| `name` 클레임에 CRLF/`<script>` 주입 | 응답 본문에 JSON 이스케이프되어 반영, 응답 헤더 오염 없음 |
| `sub`/`channel`/`name` 을 JSON 비문자열(숫자)로 전송 | `RequiredTypeException` → 401 fail-closed |
| `sub=3001`(LS:PORTAL_USER) 을 INTERNAL 채널로 제시 | `role=PORTAL_USER` + `CHANNEL_INTERNAL` → `/v1/**`(REVIEWER\|WORKER 요구) 403, `/v1/portal/**`(CHANNEL_PORTAL 요구) 도 불가 → 양방향 fail-closed |
| 위조 `?exp=&sig=&u=` 로 STREAM_SIGNED 획득 시도 | 403/401 — `AUTHORITY_STREAM_SIGNED` 부여 지점은 `StreamSignatureFilter.java:138` 단 1곳 |
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
# A 클러스터 검증 결과 — part3 (A-3 스트림 서명 필터 · A-4 웹훅 인증 필터)

- 담당 구간: `docs/test-cases/A-auth-common.md` §A-3 (TC-STREAM-001~012, 12건) + §A-4 (TC-HMAC-001~046, 46건) = **58건**
  (그중 `TC-HMAC-001` 은 **폐기** 케이스라 검증 대상 제외 → **실검증 57건**)
- 검증 일시: 2026-08-01
- 검증 방식: **실동작 우선**(실행 중 스택에 curl 직접 요청 · DB 조회 · 컨테이너 로그 · `/actuator/prometheus` 실측) + 정적 대조 + 자동테스트 커버 대조
- 판정 요약: **PASS 56 · PARTIAL 1 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0** (폐기 1 제외)
- 신규 이슈: **A-ISSUE-41 ~ A-ISSUE-43 (모두 LOW)**

---

## 0. 환경 게이트 · 버전 격차 판정

| 항목 | 실측 |
|---|---|
| 스택 | `klid-backend`(:18081→8080, context-path `/api`) · `klid-postgres` · `klid-mock-server` · `klid-ai-server` · `klid-frontend` 전부 healthy |
| 활성 프로파일 | `SPRING_PROFILES_ACTIVE=local` |
| 웹훅/스트림 실효 환경변수 | `WEBHOOK_HMAC_SECRET_AUGMENT=68e759de…`(64B, placeholder 아님) · `WEBHOOK_TRUSTED_PROXY_CIDRS=none` · `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none` · `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` · `STREAM_SIGN_SECRET=b49f3ef9…`(64B) |
| 가드 테이블 | `public.ls_whk_sign_use` · `public.ls_whk_fail_nmtm` 실재 |
| 기동 로그 | `[Webhook] 생성형 AI 콜백 IP allowlist 활성 count=1` · `[Webhook] guard purge job scheduled intervalMs=600000` |

### ★ 버전 격차 영향 — **이 구간은 영향 없음 (BLOCKED 0건)**

컨테이너는 2026-07-31 빌드(flyway V146)지만, 담당 구간 소스는 **그 이후 단 1커밋도 변경되지 않았다**:

```
$ git log --oneline --since="2026-07-30" -- backend/src/main/java/kr/co/cudo/authoring/common/security/
(출력 없음)
```

즉 실행 중 바이너리 == 검증 대상 소스(qa-0801 워킹트리)이므로 A-3/A-4 전건 실동작 판정이 유효하다.
지시받은 **mock-server 코드 변경의 계약 영향**도 확인했다 — `/v1/vlm/callback`·`/v1/genai/callback` 인증 계약은 **무서명 3계층**(IP allowlist → rate limit + size cap → `request_id` 발급 게이트)이고, mock-server 는 이 계약의 *호출자*일 뿐 인증 헤더를 만들지 않는다. 실제 mock-server 발 콜백이 `POST /api/v1/vlm/callback 200` · `POST /api/v1/genai/callback 200` 으로 왕복 성공하고 있음이 `stack-bringup.md` 및 backend 로그에서 확인되어 **계약 파손 없음**.

### 실동작 검증에 사용한 클라이언트 IP (rate limit 격리용)

`webhook.trusted-proxy-cidrs=none` 이라 클라이언트 IP = `remoteAddr` 이므로, 분당 5회 임계에 서로 오염되지 않도록 **4개 소스 IP**를 나눠 썼다:
`192.168.65.1`(macOS 호스트) · `172.20.0.4`(klid-ai-server) · `172.20.0.6`(klid-frontend) · `0:0:0:0:0:0:0:1`(backend 자기 localhost).

---

## 1. A-3. 스트림 서명 필터 (StreamSignatureFilter · StreamNonceCookie) — 12건

### 실동작 근거 원문 (핵심)

발급(`GET /api/v1/videos/4/stream-url`, REVIEWER 1001 JWT):

```
{"success":true,"data":{"url":"/api/v1/videos/4/stream?exp=1785577978&u=1001&sig=59341984…","expiresAt":…,"ttlSeconds":60}}
Set-Cookie: klid_stream_nonce=e6e49d49…..43670c07…; Path=/api/v1/videos; Max-Age=3600;
            Expires=…; HttpOnly; SameSite=Lax
```

동일 URL 에 대한 변형 요청 응답:

| 조건 | 응답 |
|---|---|
| 유효 sig + 유효 nonce 쿠키 + Range | **206** |
| 유효 sig, **쿠키 없음** | **401** |
| sig/exp 없음 (쿠키만) | **401** |
| sig 첫 글자 변조 | **401** |
| `u=1001`→`u=2001` 변조 | **401** |
| 봉인 없는 32-hex 쿠키(`aaaa…`) | **401** |
| 정상 nonce + 잘못된 seal(64×`b`) | **401** |
| 1001 로 봉인된 쿠키 + `u=2002` 서명 | **401** |
| rawSn 19자리(`1234567890123456789`) | **401** |
| 동일 URL 에 **POST** | **401** |
| `Authorization: Bearer` + sig 없음 | **206** |
| 경로 인코딩 변형 `/v1/videos/5/%73tream` | **401** (필터 미매칭 → 컨텍스트 미설정 → fail-closed) |

backend 로그(sig 전문 미노출 확인):

```
DEBUG k.c.c.a.c.s.StreamSignatureFilter - [StreamSign] invalid signature rawSn=4
```

### 판정표

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| TC-STREAM-001 | PASS | [실동작] 유효 sig+쿠키 → **206**. principal 이 실발급자로 채워짐은 TC-STREAM-012 의 403/206 분기로 역증명(`u=2002`→WORKER 로 재조회되어 미배정 403, 배정본은 206) | `StreamSignatureFilter.java:126-148` 일치 |
| TC-STREAM-002 | PASS | [실동작] sig/exp 부재 → **401** (필터 미개입 + 보안체인 거부) | `:108-114` |
| TC-STREAM-003 | PASS | [실동작] 변조 sig → 401 + 로그 `[StreamSign] invalid signature rawSn=4` — **sig 전문·쿠키값 미출력**(CWE-532 방어 실효) | `:149-152` |
| TC-STREAM-004 | PASS | [실동작] `u` 만 변조 → **401**. "URL 전체 재사용 차단"은 nonce 쿠키 담당임도 TC-STREAM-008 로 실증 | `:124-132` |
| TC-STREAM-005 | PASS | [실동작] Bearer 토큰 + sig 없음 → **206**(서명검증 스킵). `SecurityConfig.java:166` `addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class)` 확인 | 익명 필터보다 앞 배치 확인 |
| TC-STREAM-006 | PASS | [실동작] 동일 URL POST → 401(미개입), 비-stream 경로(`/v1/videos/4`)에 서명 부착 → 401 | `:84-89` |
| TC-STREAM-007 | PASS | [실동작] 19자리 rawSn → **401** (정규식 `\d{1,18}` 미매칭 → 패스스루 → fail-closed) | `:50,116-122` |
| TC-STREAM-008 | PASS | [실동작] URL 전량 확보 + 쿠키 없음 → **401**. 발급 응답 `url` 에 nonce 미포함 확인 | `:127-132`, `StreamNonceCookie.java:98-126` |
| TC-STREAM-009 | PASS | [실동작] ①봉인 없는 임의 32-hex ②nonce+오류 seal ③타 subject 봉인 쿠키 **3종 전부 401**. `unseal` 은 `MessageDigest.isEqual` 상수시간 비교 | `StreamNonceCookie.java:132-155,167-169`. ⚠ 파생 관측 → **A-ISSUE-41** |
| TC-STREAM-010 | PASS | [실동작] `HttpOnly` ✓ `SameSite=Lax` ✓ `Path=/api/v1/videos`(=ctx+/v1/videos) ✓ `Max-Age=3600` ✓ / **Secure 미부여**(현재 `local` 프로파일 — 기대 규약과 일치). 판정축이 `request.isSecure()` 가 아니라 프로파일임은 `:89,218` + `StreamNonceCookieSecureFlagTest`(3건, prd 평문HTTP 에도 Secure 부여) | `StreamNonceCookie.java:214-224` |
| TC-STREAM-011 | PASS | [정적] `deriveSealKey()` 가 빈 시크릿에 **JVM 기동 랜덤 32B** 반환 → 어떤 쿠키도 봉인검증 불통과. 동시에 `StreamUrlSigner.configured=false` → `verify()` 가 즉시 false. **이중 fail-closed**. ⚠ 실동작은 컨테이너 재기동이 필요해 미수행(설정 변경 금지 규칙) | `StreamNonceCookie.java:183-190`, `StreamUrlSigner.verify()` |
| TC-STREAM-012 | PASS | [실동작] **서명 자체를 위조**(`STREAM_SIGN_SECRET` 로 sig + seal 직접 계산)해 미배정 WORKER 2002 로 rawSn=4 요청 → **403 `본인에게 배정되지 않은 영상입니다.`**. 동일 방식 REVIEWER 1001 → 206. 서명 컨텍스트로 `/v1/videos/4`(타 API) 호출 → **401**(ROLE_* 미부여로 확대 불가) | `:139-148,160-169` + `LabelAccessGuard.verifyRawAccess` |

> ⚠ **오판 방지 기록**: 최초에 "미배정 WORKER"로 `2002 × rawSn=5` 를 골라 206 을 받고 IDOR 로 의심했으나, `ls_task_assignment` 를 **LIMIT 없이** 재조회한 결과 `assignment_id=7 | 2002 | 5 | LABELER` 로 **실제 배정된 조합**이었다. 진짜 미배정 조합(`2002 × rawSn=4`)으로 재시험해 403 을 확인했다. B-ISSUE-63 은 **해소 상태 유지**.

---

## 2. A-4. 웹훅 인증 필터 (HmacWebhookFilter · 무서명 가드 · WebhookGate) — 46건

### 실동작 근거 원문 (핵심)

**① 퍼센트 인코딩 경로 변형이 필터에 걸리는가 (CRITICAL 회귀 가드, TC-HMAC-022)** — chunked 전송으로 프로빙(필터가 적용돼야만 411 이 나옴):

```
POST /api/v1/vlm/callback        (chunked) → 411 LENGTH_REQUIRED
POST /api/v1/%76lm/callback      (chunked) → 411 LENGTH_REQUIRED   ← 필터 적용됨
POST /api/v1/vlm/%63allback      (chunked) → 411 LENGTH_REQUIRED   ← 필터 적용됨
POST /api/v1/g%65nai/callback    (chunked) → 411 LENGTH_REQUIRED   ← 필터 적용됨(rate limit 소진 시 429)
```

backend 로그가 **원시 URI 를 그대로(sanitize 후) 남기면서도 판정은 정규화 경로로** 했음을 보여준다:

```
WARN HmacWebhookFilter - [Webhook] unsigned callback missing/chunked Content-Length path=/api/v1/%76lm/callback
WARN HmacWebhookFilter - [Webhook] unsigned callback missing/chunked Content-Length path=/api/v1/vlm/%63allback
```

**② 반대 방향(오탐) 확인 — 허용 경로가 과잉 매칭되지 않는가**:

| 요청 | 응답 | 해석 |
|---|:--:|---|
| `/v1/VLM/callback` (대문자) | **401** | PathPattern 대소문자 구분 → 필터 미적용, `/v1/**` 인증 요구로 fail-closed |
| `/v1/vlm/callback/` (후행 슬래시) | **411** | 필터는 적용(`/v1/vlm/**`), MVC 라우팅은 별개 |
| `/v1/%2576lm/callback` (이중 인코딩) | **401** | 디코딩 1회 후 `%76lm` ≠ `vlm` → 미적용 + fail-closed |
| `/v1/vlm/callback%00` | **400** | Tomcat 선차단 |
| `/v1/vlm/call%zzback`, `/v1/vlm/callback%` | **400** | Tomcat 이 URI 파싱 단계에서 선차단 → "경로 판정 불가 → 401" 분기는 HTTP 로 도달 불가(더 엄격) |

또한 `/v1/vlm/**`·`/v1/genai/**` 하위에 **콜백 2종 외 다른 컨트롤러가 없음**을 확인(`grep RequestMapping("/v1/vlm|/v1/genai"` → `VlmResultController` · `GenAiCallbackController` 뿐) — allowlist 접두 패턴이 무관한 엔드포인트를 잘못 삼키지 않는다.

**③ 본문 상한 비대칭 (TC-HMAC-018 / -035)**:

```
POST /v1/genai/callback  CL=1,572,864 → 413 "Webhook 본문은 1024KB 를 초과할 수 없습니다."
POST /v1/vlm/callback    CL=5,242,880 → 413 "Webhook 본문은 4096KB 를 초과할 수 없습니다."
POST /v1/vlm/callback    CL=2,097,152 → 400 (INVALID_INPUT — size cap 통과해 컨트롤러 도달)
```

**④ 무서명 경로 최종 관문 = request_id 발급 게이트 (TC-HMAC-016 / -033)**:

```
POST /v1/vlm/callback   {"request_id":"FORGED-QA-0801-C","status":"completed","results":[…]} → 401
POST /v1/genai/callback {"request_id":"FORGED-QA-0801-B",…}                                   → 401
로그: [Webhook][Vlm] unknown request_id=FORGED-QA-0801-C
      [Webhook] downstream auth rejected ip=172.20.0.4 path=/api/v1/vlm/callback status=401
```

**⑤ rate limit 실동작 (TC-HMAC-012 / -032 / -034)** — 소스 IP 별 6연타:

```
[backend localhost, IPv6 ::1 → genai allowlist(0.0.0.0/0) 밖]
 req1..5 → 403(ip_not_allowed)   req6 → 429 Retry-After: 60   req7 → 429 Retry-After: 60

[macOS 호스트 192.168.65.1, 매 요청 X-Forwarded-For 를 203.0.113.1..6 으로 회전]
 req1..5 → 401(downstream)       req6 → 429 Retry-After: 60   req7 → 429 Retry-After: 60
```

공유 집계 테이블 실측 — **XFF 위조값이 키로 적재되지 않고 remoteAddr 로 수렴**:

```sql
select call_ip_addr, bgng_dt, fail_nmtm from ls_whk_fail_nmtm order by bgng_dt desc;
 172.20.0.4      | 2026-08-01 18:50:00 | 2
 0:0:0:0:0:0:0:1 | 2026-08-01 18:50:00 | 5
 192.168.65.1    | 2026-08-01 18:50:00 | 5    ← 203.0.113.x 는 한 행도 없음
```

**⑥ nonce 테이블 pre-auth write 없음 (TC-HMAC-026)** — 위 모든 실패 프로빙(수십 건) 이후:

```sql
select count(*) from ls_whk_sign_use;  →  0
```

**⑦ 메트릭 태그 저카디널리티 (TC-HMAC-043)** — `GET /api/actuator/prometheus` (REVIEWER 토큰, 200):

```
webhook_auth_failed_total{path="genai",reason="downstream_unauthorized"} 6.0
webhook_auth_failed_total{path="genai",reason="ip_not_allowed"} 5.0
webhook_auth_failed_total{path="vlm",reason="downstream_unauthorized"} 12.0
```
→ 원시 URI(`/api/v1/%76lm/callback` 등)를 수십 회 던졌음에도 **`path` 태그는 `vlm`/`genai` 상수 2종뿐**. Meter 폭주 없음.

**⑧ 필터 단일 실행 (TC-HMAC-044)** — 요청 1건당 필터 WARN 로그가 **정확히 1줄**(이중 등록 시 2줄). `WebhookGateConfig.java:42-49` 의 `setEnabled(false)` 와 정합.

### 판정표

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| ~~TC-HMAC-001~~ | — | — | **폐기 케이스**(검증 대상 아님). `/v1/aug/callback` 컨트롤러·DTO 부재 재확인 |
| TC-HMAC-002 | PASS | [정적] `HmacWebhookFilter.java:214-224`(경로 0개면 미설정 허용, 기동 성공) + `:311-318`(런타임 `secret_missing` 401). 실기동이 시크릿 없이 정상 부팅한 것은 아니나(현재 값 설정됨) 로직 분기 명확. 테스트: `HmacWebhookFilterTest` "서명필수_경로가_없으면_HMAC_시크릿_없이도_기동한다" / "경로_판정_불가_요청은_여전히_401_이다" | 판정 불가 요청은 Tomcat 400 선차단이라 HTTP 도달 불가(더 엄격) |
| TC-HMAC-003 | PASS | [정적] `:240-245` `bytes < 32 → BeanInitializationException`, 경로 유무와 무관. 테스트 "시크릿_32바이트_미만_시_부팅_실패" | |
| TC-HMAC-004 | PASS | [정적] `:333-340` `missing_signature` 401 + `recordFailure`. 테스트 "보호대상_경로에서_서명이_없으면_401_이며_필터가_스킵되지_않는다" | |
| TC-HMAC-005 | PASS | [정적] `:341-346` `unsupported_algorithm` 401 + recordFailure | |
| TC-HMAC-006 | PASS | [정적] `:349-357` `timestamp_malformed` 401 + recordFailure | |
| TC-HMAC-007 | PASS | [정적] `:360` `ageMs > windowSeconds*1000 \|\| ageMs < -FUTURE_SKEW_MS(30s)` — **비대칭 확인**(`Math.abs` 아님). 테스트 "미래_timestamp_는_시계오차_범위를_넘으면_401" | 구 ±5분 대칭 폐기 확인 |
| TC-HMAC-008 | PASS | [정적] `:391` `MessageDigest.isEqual(providedBytes, computedBytes)` — **상수시간 비교**. hex 문자열을 바이트로 비교하므로 길이 불일치도 안전 처리. 테스트 "HMAC_시그니처_틀리면_401" | 타이밍 공격 방어 실효 |
| TC-HMAC-009 | PASS | [정적] 서명 경로 `:321-326` → **401** `missing_content_length` / [실동작] 무서명 경로 → **411** — 정책차 실측 확인 | |
| TC-HMAC-010 | PASS | [정적] `:98,327-331` 선언 CL > 1MB → 413 | 서명 경로는 HTTP 도달 불가라 정적 |
| TC-HMAC-011 | PASS | [정적] `:368-375` + `readCapped` `:726-740` — 8KB 청크 누적, 상한 초과 **즉시 중단**(전량 버퍼링 없음). 테스트 "Content_Length_위조해도_스트림_상한_초과시_413", "실제_스트림은_상한까지만_읽고_중단_bounded_read" | |
| TC-HMAC-012 | PASS | [실동작] 실패 5회 → 6회째 **429 + `Retry-After: 60`**(2개 IP 에서 각각 재현). 공유 DB 행(`fail_nmtm=5`) 실측 — **로컬+공유 2단 동작 확인**. `:305-309,666-672`, `WebhookRateLimiter.java:68-71,95-114` | |
| TC-HMAC-013 | PASS | [정적] `reset()` 호출처는 `:417`(**서명 검증 성공 직후**) 1곳뿐. `WebhookRateLimiter.java:207-214` 가 현재+직전 버킷 모두 삭제(조회 범위와 대칭). 무서명 경로 200 은 `countDownstreamAuthOutcome` 이 조기 return 만 하고 reset 미호출(`:557-559`). 테스트 "알려진_request_id_의_200_을_끼워넣어도_실패카운터가_초기화되지_않아_차단이_유지된다" | |
| TC-HMAC-014 | PASS | [정적] `WebhookRateLimiter.java:74,256-273` — 만료 removeIf → 그래도 초과 시 `windowStartMs` 오름차순 evict. 테스트 "FailureTracker_4096_초과_시_hard_cap_적용" | |
| TC-HMAC-015 | PASS | [정적+실동작] `WebhookProtectedPaths.java:100-103,227-233` `[\r\n\t]→_` + 200자 절단. 실로그에서 원시 URI 가 한 줄로만 출력됨 확인 | CR/LF 주입은 Tomcat 이 URI 단계에서 400 선차단 |
| TC-HMAC-016 | PASS | [실동작] 무서명 VLM 콜백이 HMAC/timestamp/nonce 없이 컨트롤러 도달 → 최종 `request_id` 게이트가 **401**. 가드 순서 `:480-527`(rate limit → IP → CL → size cap) 정적 확인 | |
| TC-HMAC-017 | PASS | [실동작] chunked VLM 콜백 → **411 LENGTH_REQUIRED** + `recordFailure`(DB 카운터 증가 확인) | ⚠ 메트릭 미기록 → **A-ISSUE-43** |
| TC-HMAC-018 | PASS | [실동작] CL=5MB → **413**(선언 CL 단계). 2MB 는 통과해 400 도달(캡이 4MB 임을 양방향 확인). 스트림 2단 캡은 `:521` `new CachedBodyHttpServletRequest(request, maxBytes, false)` | |
| TC-HMAC-019 | PASS | [실동작] 대문자·이중인코딩 변형 → 필터 미적용 후 **fail-closed 401**. [정적] `:254-263` catch 시 `return false`(=보호), `WebhookProtectedPaths.java:155-161` 판정 불가 시 `true` | |
| TC-HMAC-020 | PASS | [정적] `:574-576` `hmacSha256Hex → HmacSigner.hex` 위임(단일 진실원), `HmacSigner.java:42-53` | |
| TC-HMAC-021 | PASS | [정적] `HmacSigner.java:48-52` — 예외 메시지에 `algorithm=HmacSHA256` 만, 시크릿 평문 없음 | |
| TC-HMAC-022 | PASS | [실동작] `/v1/%76lm/callback` · `/v1/vlm/%63allback` · `/v1/g%65nai/callback` **3종 모두 411**(=필터 적용). 컨트롤러 무인증 관통 재현 불가. `WebhookProtectedPaths.java:253-269` | **CRITICAL 회귀 가드 유효** |
| TC-HMAC-023 | PASS | [정적] `WebhookGateInterceptor.java:54-60` — 래퍼 부재 시 `preHandle` 401(=`@RequestBody` 역직렬화 이전). 테스트 `WebhookGateInterceptorTest` 7건(“필터_증거_래퍼가_없는_요청은_401_컨트롤러_미도달”, “VLM_경로도_증거_래퍼가_없으면_401”) | 필터가 항상 선행하므로 HTTP 로는 재현 불가(=설계 의도) |
| TC-HMAC-024 | PASS | [정적] `:51-53` `DispatcherType.ASYNC` 조기 통과. 테스트 "ASYNC_재디스패치는…401_이_아님" + "REQUEST_디스패치는_증거_래퍼가_없으면_여전히_401_ASYNC_예외가_우회로가_아님" | |
| TC-HMAC-025 | PASS | [정적] `:398-415` 최초 200 / 이후 **409**, `nonceHash(path.canonical(), ts, sig)` `:584-596` 가 **정규화 경로** 사용. 테스트 "동일_서명_재전송시…replay_로_흡수", "nonce_키는_원시URI가_아니라_정규화_경로로_계산됨" | |
| TC-HMAC-026 | PASS | [실동작+정적] 위조/실패 프로빙 수십 건 후 `select count(*) from ls_whk_sign_use` → **0**. 코드상 nonce 소비는 `:398` 서명 검증 성공 이후에만 | pre-auth write DoS 차단 실증 |
| TC-HMAC-027 | PASS | [정적] `:404-409` `WebhookGuardUnavailableException` → `writeServiceUnavailable` `:647-652`(503 + `Retry-After: 30`). 테스트 "nonce_저장소_장애시_fail_closed_503_이며_통과하지_않음" | |
| TC-HMAC-028 | PASS | [정적] `:418-443` `committed = status < 500`, `finally { if(!committed) releaseNonce(...) }`, release 실패는 요청 결과 불변. 테스트 3건 | |
| TC-HMAC-029 | PASS | [정적] `:157-166` placeholder 4종 + `:234-239` non-local 프로파일 기동 차단. 실효 시크릿은 64B 난수라 해당 없음. 테스트 "리포에_커밋된_공개_placeholder_시크릿은_local_이_아닌_프로파일에서_부팅_실패" | |
| TC-HMAC-030 | PASS | [정적] `:227-233` `secret.startsWith("?")` → 기동 차단. 테스트 "해석되지_않은_placeholder_기본값_물음표_시크릿은_부팅_실패" | |
| TC-HMAC-031 | PASS | [정적] `GenAiWebhookIpAllowlist.java:44-77` — 빈 값/`none` 모두 `allowed.isEmpty()` → `isAllowed()` **항상 false**(fail-closed). `WebhookIpAllowlist.java:80-98` 는 반대로 비면 **항상 true**(의도된 비대칭). 테스트 `GenAiWebhookIpAllowlistTest` 4건. [실동작 보강] 현재 `0.0.0.0/0` 설정에서 **IPv6 발신(::1)이 403** 으로 거부됨 — allowlist 가 실제로 게이트로 작동함을 확인 | |
| TC-HMAC-032 | PASS | [실동작] 비허용 IP 403 ×5 → **6회째 429**. 메트릭 `webhook_auth_failed_total{path="genai",reason="ip_not_allowed"} 5.0`. `:486-500` | |
| TC-HMAC-033 | PASS | [실동작] 위조 `request_id` 401 ×5 → 6회째 429, 메트릭 `reason="downstream_unauthorized"`. [정적] `:556-564` 2xx 는 조기 return(=reset 안 함) | |
| TC-HMAC-034 | PASS | [실동작] **XFF 를 매 요청 회전(203.0.113.1~6)해도 6회째 429**. 공유 카운터에 위조 IP 행 0건, `192.168.65.1` 로만 5 적재. `isClientIpAttributable` 류 게이트 부재 확인(`ClientIpResolver.java:55-61` 주석 + 코드 전수 확인) | 헤더 한 줄로 통제 무력화 불가 |
| TC-HMAC-035 | PASS | [실동작] genai CL=1.5MB → **413 "1024KB"**, VLM 은 같은 크기 통과(4MB 캡) — 상한 비대칭 실증. `:121-130,476` | |
| TC-HMAC-036 | **PARTIAL** | [실동작] XFF 폐기 + remoteAddr 채택 ✓ / 프로세스당 1회 WARN ✓. **다만 `webhook.trusted-proxy-cidrs=none` 을 명시했는데도 "비어 있습니다 … 'none' 을 명시하세요" WARN 이 출력**됨(운영자가 이미 요구대로 설정한 상태에서 설정 누락 경보) | → **A-ISSUE-42** |
| TC-HMAC-037 | PASS | [정적] `ClientIpResolver.java:150-167` 우측부터 신뢰 홉 제거 → 최초 비신뢰 값 채택, 비정상 값 시 remoteAddr 폴백. 테스트 "신뢰프록시_뒤에서는_XFF_클라이언트별로_rate_limit_이_독립_적용" | 현 환경은 `none` 이라 실동작 미도달 |
| TC-HMAC-038 | PASS | [정적] `:71,74`(`MAX_XFF_LENGTH=1024`, `MAX_XFF_HOPS=20`) + `:147`(길이 초과 시 헤더 무시) + `:153`(`.limit(20)`) | |
| TC-HMAC-039 | PASS | [정적] `:126-138` 비-IP remoteAddr → `unknown` sentinel + `:229-236` 1회 WARN. `isValidIp` 가 `WebhookCidrParser.isIpLiteral` 구조 검증 사용(DNS 조회 유입 차단). 테스트 3건 | |
| TC-HMAC-040 | PASS | [정적] `:93-107` + `WebhookConfigProfiles.java:32,38-44`(`{prd, stg}`). 테스트 "prd_에서_신뢰프록시_CIDR_미설정이면_기동_실패", "stg_에서도…기동_실패", "none_으로_명시하면_기동_가능" | 현 프로파일 local 이라 미적용이 정상 |
| TC-HMAC-041 | PASS | [정적] `WebhookIpAllowlist.java:46-61` 동일 구조. 테스트 "prd_에서_VLM_IP_allowlist_미설정이면_기동_실패" | |
| TC-HMAC-042 | PASS | [정적] `WebhookCidrParser.parseStrict` → 형식 오류 시 `BeanInitializationException`(프리픽스 비숫자·주소폭 초과·호스트명 전부). 테스트 "신뢰프록시_CIDR_오타는…기동이_차단되며_사유가_메시지에_드러난다", "VLM_allowlist_CIDR_오타는_전면허용으로_떨어지지_않고…" | |
| TC-HMAC-043 | PASS | [실동작] `/actuator/prometheus` 실측 — 태그가 `path∈{vlm,genai}` · `reason` 상수뿐. 원시 URI 변형 다수 투입에도 Meter 증식 0. `:136,610-622`, `WebhookProtectedPaths.java:73-75,198-207` | 관련 관측 → **A-ISSUE-43** |
| TC-HMAC-044 | PASS | [실동작+정적] 요청 1건당 필터 로그 정확히 1줄(이중 실행 없음). `WebhookGateConfig.java:42-49` `setEnabled(false)`, `SecurityConfig.java:162` 단일 등록. 추가 확인: `WebSecurityCustomizer`/`web.ignoring`/추가 `SecurityFilterChain` **부재**(전 소스 grep) | |
| TC-HMAC-045 | PASS | [실동작] 기동 로그 `[Webhook] guard purge job scheduled intervalMs=600000`. [정적] `WebhookGuardPurgeJob.java:36-56` 데몬 executor + `Math.max(60_000, …)` clamp + 초기지연 300000. `JdbcWebhookGuardStore.java:72,88` 조건부 DELETE(2노드 무해) | |
| TC-HMAC-046 | PASS | [정적] nonce = `:404-409` **503 fail-closed** / rate limit = `WebhookRateLimiter.java:137-140` + `JdbcWebhookGuardStore.java:139-143` **`UNAVAILABLE` fail-open**. 테스트 "nonce_저장소_장애시_fail_closed_503", "rate_limit_공유저장소_장애시_fail_open_으로_정상_콜백이_통과" | 비대칭 의도 확인 |

---

## 3. 반증 시도 요약 (확증편향 차단 — 시도했으나 결함이 아니었던 것)

| # | 반증 가설 | 실측 결과 | 결론 |
|---|---|---|---|
| 1 | HMAC 비교가 `String.equals` 라 타이밍 공격 가능 | `MessageDigest.isEqual` 사용(`:391`), 스트림 서명도 동일(`StreamUrlSigner`), 쿠키 봉인도 동일(`StreamNonceCookie:154`) | 결함 아님 |
| 2 | URL 인코딩으로 웹훅 필터 스킵(E-ISSUE-01 재발) | `%76lm`·`%63allback`·`g%65nai` 3종 전부 411(필터 적용). 이중인코딩·대문자는 필터 미적용이지만 `/v1/**` 인증으로 401 fail-closed | 결함 아님 |
| 3 | 스트림 필터도 원시 URI 매칭이라 `%73tream` 로 우회 | `/v1/videos/5/%73tream` → **401**(컨텍스트 미설정). 서명 인증이 *부여되지 않는* 방향의 미스매치라 fail-closed | 결함 아님 |
| 4 | nonce replay 방지가 실제로는 안 걸린다 | 서명 필수 경로가 0개라 HTTP 로 도달 불가. 대신 **nonce 테이블 0행**(pre-auth write 없음)을 실측하고, 정규화 경로 키·409 응답·5xx 해제를 단위테스트 6건으로 대조 | 결함 아님(경로 부재는 의도) |
| 5 | XFF 를 붙이면 rate limit 이 꺼진다(구 `isClientIpAttributable` 잔존) | XFF 회전 6연타 → 429. 공유 테이블에 위조 IP 미적재 | 결함 아님(R-1 제거 확인) |
| 6 | 무서명 allowlist 접두(`/v1/vlm/**`)가 엉뚱한 엔드포인트를 삼킨다 | 해당 접두 아래 컨트롤러는 콜백 2종뿐 | 결함 아님 |
| 7 | Security 체인이 2벌이거나 `web.ignoring` 으로 필터가 건너뛰어진다 | 체인 1개, ignoring 없음, 서블릿 자동등록 비활성 | 결함 아님 |
| 8 | 서명 스트림 컨텍스트(`STREAM_SIGNED`)로 다른 `/v1/**` API 접근 가능 | `/v1/videos/4` 에 서명 부착 → 401(필터가 stream 경로에만 컨텍스트 설정) | 결함 아님 |
| 9 | 미배정 WORKER 가 서명 URL 로 임의 영상 스트리밍(B-ISSUE-63 미해소) | 서명을 직접 위조해 시험 → **403**. (최초 오판은 배정표 LIMIT 누락 탓, §1 기록) | 결함 아님 |
| 10 | `0.0.0.0/0` 설정이 IPv6 도 허용해 fail-closed 가 무의미 | IPv6 발신(`::1`) → **403**. 즉 `0.0.0.0/0` 은 IPv4 전용이며 IPv6 은 별도 `::/0` 명시 필요 — 클래스 javadoc 이 그렇게 안내함 | 결함 아님(문서 정합) |

---

## 4. 이슈 블록

> 이번 구간에서 **FAIL 은 0건**이다. 아래 3건은 ①PARTIAL 1건(A-ISSUE-42) ②PASS 케이스에서 실동작 반증 중 파생 관측된 LOW 결함 2건(A-ISSUE-41 / -43)이다. 셋 다 인증 우회가 아니라 **가용성·운영 관측** 축이다.

### [A-ISSUE-41] TC-STREAM-009 — 동명 쿠키 shadowing 시 스트림 nonce 검증이 영구 401(가용성 DoS)

- **심각도**: LOW
- **기대 동작(기대효과)**: `klid_stream_nonce` 쿠키는 서버 봉인(HMAC) 검증을 통과한 값만 채택하고, 그렇지 않은 값은 폐기한다. 요청에 **같은 이름의 쿠키가 여러 개** 실려 오더라도 그중 서버가 발급한 유효 값이 하나라도 있으면 그것을 채택해 정상 재생이 유지돼야 한다(봉인 검증이 이미 위조 값을 걸러내므로, 위조 값의 존재가 정상 값을 가릴 이유가 없다).
- **현재 동작(이슈 내용)**: `StreamNonceCookie.read()` 가 이름이 일치하는 **첫 번째** 쿠키에서 곧바로 `return` 한다 — 그 값이 봉인 검증에 실패해도 뒤의 유효 쿠키를 보지 않는다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/StreamNonceCookie.java:115-126`
  ```java
  for (Cookie cookie : cookies) {
      if (COOKIE_NAME.equals(cookie.getName())) {
          return unseal(cookie.getValue(), subject);   // ← 첫 매칭에서 즉시 반환
      }
  }
  return null;
  ```
  실동작(동일 서명 URL, 쿠키 순서만 변경):
  ```
  Cookie: klid_stream_nonce=<유효>                                        → 206
  Cookie: klid_stream_nonce=deadbeef…(위조); klid_stream_nonce=<유효>     → 401   ← 정상 값이 가려짐
  Cookie: klid_stream_nonce=<유효>; klid_stream_nonce=deadbeef…(위조)     → 206
  ```
  `read()` 는 발급 경로(`resolveOrIssue`)도 공유하므로, 위조 쿠키가 앞에 오는 한 **발급 때마다 새 nonce 를 만들고 재생 때마다 401** 이 반복되어 피해자 계정의 영상 재생이 지속 차단된다.
- **재현/확인 경로**:
  ```bash
  # 1) 서명 URL + 정상 nonce 쿠키 발급
  curl -s -D h -H "Authorization: Bearer $TOKEN" \
    "http://localhost:18081/api/v1/videos/4/stream-url"
  # 2) 위조 쿠키를 앞에 두고 재생
  curl -s -o /dev/null -w "%{http_code}\n" -H "Range: bytes=0-99" \
    -H "Cookie: klid_stream_nonce=deadbeefdeadbeefdeadbeefdeadbeef.$(printf 'a%.0s' $(seq 64)); klid_stream_nonce=$VALID" \
    "http://localhost:18081$URL"     # → 401
  ```
  현실적 전제: 공격자가 같은 호스트에 대해 **더 긴(또는 같은) Path** 로 동명 쿠키를 심을 수 있어야 한다(RFC 6265 §5.4 는 path 길이 내림차순 전송). 본 프로젝트는 CLAUDE.md 상 **관제서버와 동일 도메인 운영**이라 인접 앱의 XSS·related-domain 경로로 쿠키 주입 표면이 존재한다.
- **영향**: 인증 우회는 아니다(위조 쿠키로는 여전히 통과 불가 — 기밀성 유지). 영향은 **가용성**으로, 특정 사용자의 영상 스트리밍이 쿠키를 수동 삭제할 때까지 지속 실패한다. CWE-1289(Improper Validation of Unsafe Equivalence in Input) / CWE-565 계열, 가용성 축 CWE-400 인접.
- **수정 방향(제안)**: `StreamNonceCookie.read()` 의 루프를 "첫 매칭 반환"에서 **"매칭 전부를 순회하며 unseal 성공값이 나오면 반환, 끝까지 실패면 null"** 로 바꾼다(값 개수 상한을 두어 CPU 소모 방지). 봉인 검증이 이미 안전 판정이므로 다중 후보 허용에 따른 보안 약화가 없다. ⚠ 구현은 하지 않음.

### [A-ISSUE-42] TC-HMAC-036 — `trusted-proxy-cidrs=none` 을 명시해도 "설정이 비어 있다"고 경고(오해 유발 운영 신호)

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.trusted-proxy-cidrs` 는 "빈 값(미설정)"과 "`none`(프록시 없음을 **의식적으로** 선택)"을 구분하는 것이 설계 규약이다(`ClientIpResolver.NO_PROXY` 주석, `WebhookIpAllowlist` 는 실제로 두 경우 로그를 구분한다). 따라서 `none` 을 명시한 운영자에게는 "설정 누락" 경고가 나가면 안 된다 — 경고가 나가면 이미 규약대로 설정한 사람이 다시 손댈 것이 없는데도 조치를 찾게 되고, 반대로 **진짜 누락 환경의 경고가 노이즈에 묻힌다**.
- **현재 동작(이슈 내용)**: 판정이 `trustedProxies.isEmpty()` 하나뿐이라 `none` 명시와 미설정이 **같은 상태로 접힌다**. 실효 환경변수가 `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 인 현재 스택에서 다음 WARN 이 실제로 출력됐다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/webhook/ClientIpResolver.java:105-110, 207-217`
  ```java
  this.trustedProxies = isNoProxy(trustedProxyCidrs) ? List.of() : WebhookCidrParser.parseStrict(...);
  // ... isNoProxy 였다는 사실이 필드로 보존되지 않는다
  private void warnIfUnconfiguredProxy(HttpServletRequest request) {
      if (!trustedProxies.isEmpty() || unconfiguredProxyWarned.get()) return;
      ...
      log.warn("[Webhook] X-Forwarded-For 가 관측됐으나 webhook.trusted-proxy-cidrs 가 비어 있습니다. ... 직접 노출이면 '{}' 을 명시하세요.", NO_PROXY);
  }
  ```
  실로그(klid-backend, `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 상태):
  ```
  2026-07-31 03:17:40.209 WARN k.c.c.a.c.s.webhook.ClientIpResolver -
    [Webhook] X-Forwarded-For 가 관측됐으나 webhook.trusted-proxy-cidrs 가 비어 있습니다.
    XFF 는 폐기하고 remoteAddr 로 집계합니다. ... 직접 노출이면 'none' 을 명시하세요. (이 경고는 프로세스당 1회만 출력)
  ```
  → 이미 `none` 인데 "`none` 을 명시하세요"라고 안내한다.
- **재현/확인 경로**:
  ```bash
  docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep TRUSTED_PROXY
  #  WEBHOOK_TRUSTED_PROXY_CIDRS=none
  docker logs klid-backend 2>&1 | grep "trusted-proxy-cidrs 가 비어"
  ```
  (XFF 헤더가 붙은 웹훅 요청이 1회 이상 들어오면 발화)
- **영향**: 보안 통제 자체는 정상(XFF 폐기 + remoteAddr 집계는 기대대로 동작하며 A-ISSUE 아님). 영향은 **운영 관측 품질** — 정상 설정에 대한 오경보로 인해 진짜 설정 누락(local/dev 에서 프록시 뒤 배포 등)을 놓칠 수 있다. CWE-1295(Debug Messages Revealing Unnecessary Information) 계열이라기보다 운영 신호 품질 결함이며, `WebhookIpAllowlist` 와의 **정책 표현 비대칭**이기도 하다.
- **수정 방향(제안)**: `ClientIpResolver` 에 `private final boolean explicitNoProxy = isNoProxy(trustedProxyCidrs);` 를 보존하고 `warnIfUnconfiguredProxy` 의 조건에 `&& !explicitNoProxy` 를 추가한다(또는 `none` 명시일 때는 기동 시 INFO 1회만 남기는 `WebhookIpAllowlist:64-67` 방식과 통일). ⚠ 구현은 하지 않음.

### [A-ISSUE-43] TC-HMAC-017/018/043 — 무서명 경로의 411/413 이 rate limit 은 올리면서 `webhook.auth.failed` 메트릭은 올리지 않음(관측 사각)

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.auth.failed` 는 웹훅 가드가 요청을 거절한 사유를 운영자가 관측·알람하는 단일 지표다. **rate limit 카운터를 올리는(=429 backoff 를 유발할 수 있는) 모든 거절 사유**는 이 지표에도 남아야, "왜 정상 벤더 콜백이 429 를 맞고 있는가"를 메트릭만으로 추적할 수 있다.
- **현재 동작(이슈 내용)**: 무서명 경로에서 `411`(Content-Length 누락/chunked)과 `413`(본문 상한 초과)은 `rateLimiter.recordFailure(clientIp)` 는 호출하지만 `countAuthFailure(...)` 를 호출하지 않는다 — `writeLengthRequired`/`writePayloadTooLarge` 는 `fail()` 을 경유하지 않기 때문이다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/HmacWebhookFilter.java:506-527`
  ```java
  if (contentLength < 0) {
      rateLimiter.recordFailure(clientIp);
      writeLengthRequired(response);          // ← countAuthFailure 없음
      return;
  }
  if (contentLength > maxBytes) {
      rateLimiter.recordFailure(clientIp);
      writePayloadTooLarge(response, maxBytes); // ← countAuthFailure 없음
      return;
  }
  ```
  (서명 필수 경로의 413 도 동일: `:327-331`)

  실측 — chunked 4회 + 413 2회를 실제로 발생시킨 뒤 `/actuator/prometheus`:
  ```
  webhook_auth_failed_total{path="genai",reason="downstream_unauthorized"} 6.0
  webhook_auth_failed_total{path="genai",reason="ip_not_allowed"} 5.0
  webhook_auth_failed_total{path="vlm",reason="downstream_unauthorized"} 12.0
  ```
  → `reason="length_required"` / `"payload_too_large"` 시리즈가 **아예 없다**. 반면 같은 요청들이 `ls_whk_fail_nmtm` 카운터는 정상적으로 올렸다(호스트 IP 기준 5 도달 → 429 관측).
- **재현/확인 경로**:
  ```bash
  # 411 유발
  printf '{"a":1}' | curl -s -o /dev/null -w "%{http_code}\n" -X POST \
    http://localhost:18081/api/v1/vlm/callback -H 'Content-Type: application/json' \
    -H 'Transfer-Encoding: chunked' --data-binary @-        # → 411
  # 메트릭 확인 (REVIEWER 토큰 필요)
  curl -s -H "Authorization: Bearer $REVIEWER_TOKEN" \
    http://localhost:18081/api/actuator/prometheus | grep webhook_auth_failed
  # → length_required 계열 시리즈 없음
  ```
- **영향**: 보안 통제 자체는 정상 동작(차단·집계 모두 유효). 영향은 **관측/알람** — chunked·대용량 본문 플러딩으로 벤더 IP 가 429 backoff 에 빠져 **정상 콜백이 유실되는 상황**이 발생해도 `webhook.auth.failed` 알람이 전혀 울리지 않아 원인 추적이 로그 grep 에 의존하게 된다. CWE-778(Insufficient Logging) 계열.
- **수정 방향(제안)**: `handleGuardOnly` 의 411/413 분기(및 서명 경로 413 분기)에서 `countAuthFailure(path.tag(), "length_required")` / `"payload_too_large"` 를 호출한다 — reason 은 기존과 동일하게 **상수 문자열**이라 태그 카디널리티(TC-HMAC-043)를 해치지 않는다. 또는 `recordFailure` 와 메트릭 증가를 한 헬퍼로 묶어 두 축이 구조적으로 어긋나지 못하게 한다. ⚠ 구현은 하지 않음.

---

## 5. 근거 드리프트 점검

케이스 표에 적힌 `file:line` **58건 전부를 실파일과 대조**했다. **드리프트 0건** — A-3(`StreamSignatureFilter` / `StreamNonceCookie` / `SecurityConfig:166`) · A-4(`HmacWebhookFilter` / `WebhookProtectedPaths` / `WebhookRateLimiter` / `ClientIpResolver` / `WebhookIpAllowlist` / `GenAiWebhookIpAllowlist` / `WebhookGateInterceptor` / `WebhookGateConfig` / `WebhookConfigProfiles` / `WebhookGuardPurgeJob` / `JdbcWebhookGuardStore` / `HmacSigner`) 전 인용 위치가 현재 소스와 일치한다.

## 6. 테스트 커버 대조

| 테스트 파일 | 건수 | 커버 케이스 |
|---|--:|---|
| `webhook/HmacWebhookFilterTest.java` | 63 | TC-HMAC-002~014, 016~021, 025~035, 037~043, 046 |
| `webhook/WebhookGateInterceptorTest.java` | 7 | TC-HMAC-023, 024 |
| `webhook/WebhookPathBypassSecurityIT.java` | 11 | TC-HMAC-022, 025, 033, 034 (+StrictHttpFirewall 선차단) |
| `webhook/WebhookRateLimiterTest.java` | 4 | TC-HMAC-012, 013 |
| `common/security/webhook/GenAiWebhookIpAllowlistTest.java` | 4 | TC-HMAC-031, 042 |
| `common/security/HmacSignerTest.java` | 1 | TC-HMAC-020, 021 |
| `video/StreamSignedUrlControllerTest.java` | 19 | TC-STREAM-001~010 |
| `video/VideoStreamAssignmentAuthorizationTest.java` | 17 | TC-STREAM-012 |
| `common/security/StreamNonceCookieSecureFlagTest.java` | 3 | TC-STREAM-010 |

`_raw/test-baseline.md` 대조: backend 4,755 tests / **실패 0** / skip 5 → 위 테스트 전건 통과 상태.

**커버 공백**: `TC-STREAM-011`(sign-secret 미설정 시 봉인키 랜덤 → 전건 불통과)에 대응하는 자동 테스트가 없다(`StreamNonceCookieSecureFlagTest` 는 Secure 플래그만 다룬다). 정적 논리는 명확하나 회귀 가드는 부재 — 카탈로그 후속 보완 후보로 기록한다.
# A 클러스터 — part4 검증 결과

> 담당 구간: `docs/test-cases/A-auth-common.md` **`## A-6. 추적ID·공통 응답·예외 + 공통 유틸`** (39건)
> 검증일 2026-08-01 · 회차 1차 · 검증자 A-part4 에이전트
> 스택: 실행 중 로컬 풀스택(backend `localhost:18081/api`, profile=**local**, klid-postgres/public 스키마).
> ⚠ 환경 버전 격차(구버전 이미지 V146) — **본 구간은 최근 커밋(b2b44f0e~56d30478: LS_DATA_INGEST·증강 폐기/복구)과 무관**하므로 BLOCKED 사유 해당 없음. 전건 정상 검증.

## 0. 집계

| 판정 | 건수 |
|------|:--:|
| PASS | 35 |
| PARTIAL | 4 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **39** |

이슈: **A-ISSUE-61 ~ A-ISSUE-71 (11건)** — HIGH 2 · MEDIUM 3 · LOW 6

## 1. 검증 환경·근거 수집 방법

- 실동작: `curl` + raw socket(python) 로 backend 직접 호출 → 응답 원문 + `docker logs klid-backend` 로그 라인 대조
- 토큰: `POST /api/v1/dev/tokens` (REVIEWER sub=1001 / WORKER sub=2001 / PORTAL_USER)
- 로그 프로파일 실측: `docker exec klid-backend printenv SPRING_PROFILES_ACTIVE` → **`local`** → `logback-spring.xml` 의 `LayoutWrappingEncoder` + `MaskingPatternLayout` 경로가 실제로 활성
- 코드/설정/테스트 파일 **수정 0건**, 빌드/테스트 실행 **0건** (baseline `_raw/test-baseline.md` 대조만 수행 — BE 4,755 tests / 실패 0)

### ★★ 확증편향 반증 — 로그 마스킹 "실배선" 실측 (지시사항 핵심 항목)

이 구간의 알려진 실패 모드는 "LogSanitizer 유닛테스트는 통과하는데 실제 로그 파이프라인엔 미배선"이었다.
**실제 로그를 찍어 반증을 시도**했고, 결과는 다음과 같다.

프로브: `GET /v1/videos?sort=<페이로드>,desc` → `SortAllowlist.resolveLenient` 가 미등록 키를
`LogSanitizer.sanitize(key,64)` 로 정제해 WARN 출력 → `docker logs klid-backend` 로 원문 확인.

| 투입 페이로드 | 실제 로그 출력 | 판정 |
|---|---|---|
| `aa\nFAKE-LOG-LINE-…\r\nERROR injected` | `key=aaFAKE-LOG-LINE-1785577789ERROR injected` (한 줄) | ✅ LogSanitizer **실배선 확인** (CWE-117 차단) |
| `U+2028`·`U+2029`·`U+0085`·TAB·NUL·DEL·ESC·`U+0090` 혼합 | `key=AALSDONEPSDONENELDONETABDONENULDONEDELDONEESCDONEC1DONE ZZ 가 나 다…` | ✅ 전부 제거, 공백·한글 보존 |
| `BBB…`(300자) | `BBB…(64자)...(truncated)` | ✅ 64자 절단 |
| `token=SUPERSECRET123` | `key=token=***` | ✅ **MaskingPatternLayout 실배선 확인** |
| `password=hunter2` | `key=password=***` | ✅ |
| `secret=abc` | `key=secret=***` | ✅ |
| `Authorization: Bearer eyJabc.def.ghi` | `key=Authorization: ***` | ✅ |

→ **UNCERTAINTIES #7("Logback 마스킹 레이아웃 실체 — 잔여: 런타임 프로브 비결정적")은 본 회차에서 런타임 실증으로 해소된다.** `MaskingPatternLayout` 은 죽은 코드가 아니라 실제 appender 종단에서 동작한다.

단, **같은 프로브로 마스킹 집합의 구멍이 드러났다** → A-ISSUE-62 / A-ISSUE-63.

---

## 2. 케이스별 판정

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|------|------|
| TC-TRACE-001 | PASS | [실동작] | 헤더 없이 요청 → `X-Trace-Id: 7592fe4a7479` (12자리 hex) 응답 헤더 + 로그 `[7592fe4a7479]`. `RequestIdFilter.java:28-33` 일치 |
| TC-TRACE-002 | PASS | [실동작] | raw 소켓으로 C1 제어바이트 포함 헤더 투입 → 응답 `X-Trace-Id: abcd` (제어문자 제거). `%0d%0a` 리터럴도 `abc0d0aInjected` 로 무해화. 응답 헤더 분할 미발생 |
| TC-TRACE-003 | PASS | [실동작] | `abc!@#$%^&*()_+=<>?/\|~\`'";:[]{}def` → `abcdef` / `A`×100 → 64자 절단 실측 |
| TC-TRACE-004 | PASS | [실동작] | 백그라운드 스레드 로그가 `[scheduling-2] DEBUG []` — traceId 공란. MDC 스레드 누수 없음(`finally MDC.remove`, RequestIdFilter.java:36-38) |
| TC-TRACE-005 | PASS | [실동작] | `@Order(HIGHEST_PRECEDENCE)` (RequestIdFilter.java:17). **실증**: Spring Security `StrictHttpFirewall` 이 거부한 요청의 ERROR 로그에도 traceId `[abcd]` 가 채워져 있음 → 필터가 시큐리티 체인(order -100)보다 먼저 실행됨 |
| TC-RESP-001 | PASS | [실동작] | `GET /v1/videos` → `{"success":true,"data":{…},"message":null,"errorCode":null}` |
| TC-RESP-002 | PASS | [실동작] | 401 → `{"success":false,"data":null,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` / 403 → `FORBIDDEN` + "권한이 없습니다." = `ErrorCode.defaultMessage()` |
| TC-RESP-003 | PASS | [실동작] | `POST /v1/augments/request {"videoIds":[9101],"types":["WINTER"]}` → 400 `{"data":{"blockedVideoIds":[9101]},"errorCode":"NOT_REVIEWED"}` |
| TC-RESP-004 | PASS | [실동작] | 모든 에러 응답에 `"data":null` 키 유지. 전역 Jackson `default-property-inclusion` 재정의 **없음**(application*.yml grep 0건, `Jackson2ObjectMapperBuilder` 커스터마이저 0건) |
| TC-EXC-001 | PASS | [실동작] | `PRECONDITION_FAILED`→412, `NOT_REVIEWED`→400, `CONFLICT`→409 등 status/errorCode 매핑 실측 |
| TC-EXC-002 | PASS | [실동작] | 위 TC-RESP-003 과 동일 응답에서 `details`(=`blockedVideoIds`) 가 `data` 로 실림. GlobalExceptionHandler.java:28-32 |
| TC-EXC-003 | PASS | [실동작] | `POST /v1/assignments {}` → 400 `"workerId: workerId 는 필수입니다., rawDataIds: rawDataIds 는 1건 이상이어야 합니다."` |
| TC-EXC-004 | PARTIAL | [실동작] | `GET /v1/reviews?q=<150자>` → 400 INVALID_INPUT 정규화는 성립. **단 메시지가 `list.q: …`** — 컨트롤러 **핸들러 메서드명(`list`)이 응답에 노출**된다(형제 케이스 TC-EXC-007 은 "원문 비노출"을 명시) → **A-ISSUE-69** |
| TC-EXC-005 | PASS | [실동작] | `-d '{broken'` → 400 `"요청 본문이 올바르지 않습니다."`, 로그는 `cause=JsonEOFException` (클래스명만) |
| TC-EXC-006 | PASS | [실동작] | `GET /v1/portal/datamart/labels` (rawSn 누락) → 400 `"필수 파라미터가 누락되었습니다: rawSn"` |
| TC-EXC-007 | PASS | [실동작] | `GET /v1/videos/notanumber` → 400 `"파라미터 형식이 올바르지 않습니다: rawSn"` — 입력 원문 `notanumber` 미노출 |
| TC-EXC-008 | PASS | [정적] | GlobalExceptionHandler.java:141-145,168-171 — `ux_ls_auth_work_lock_raw_active` 만 409. 커버: `auth/WorkLockDataIntegrityIT.java`(baseline 통과) |
| TC-EXC-009 | PASS | [실동작] | 운영 로그 실측(2026-07-31 03:22:18, 동시 요청 4건): `[Exception] label name ci-unique violation constraint=uk_ls_label_nm_ci` → 409 분기 진입 확인. 3종 인덱스 분기 GlobalExceptionHandler.java:146-160 |
| TC-EXC-010 | PASS | [정적] | :161-166 fail-closed 500 + 커버 `GlobalExceptionHandlerDataIntegrityTest("DataIntegrityViolation_cause_분기_workLock만_409_그외_fail_closed_500")` |
| TC-EXC-011 | PASS | [실동작] | 위 09 로그가 제약명 `uk_ls_label_nm_ci` 를 정확 추출함을 실증. Hibernate→SQL 메시지 순 :193-209 |
| TC-EXC-012 | PASS | [정적] | 409/500 응답 모두 `ErrorCode` 고정 메시지만(:143-165). 커버 `GlobalExceptionHandlerDataIntegrityTest("DataIntegrity_응답에_제약명_SQL_미포함")` |
| TC-EXC-013 | PASS | [실동작] | WORKER 토큰 + 유효 바디로 `POST /v1/assignments` → 403 `FORBIDDEN` |
| TC-EXC-014 | PASS | [실동작] | 토큰 없이 `GET /v1/videos` → 401 `UNAUTHORIZED` |
| TC-EXC-015 | PASS | [실동작] | `GET /v1/no-such-endpoint` → 404 `"요청한 API를 찾을 수 없습니다."` |
| TC-EXC-016 | PASS | [정적] | :234-239 → `PAYLOAD_TOO_LARGE`, 내부 한도(500MB/1200MB, application.yml:28-29) 미노출. 커버 `MaxUploadSizeExceededMappingTest`. 실동작은 500MB 업로드가 필요해 비용상 미수행 |
| TC-EXC-017 | PARTIAL | [실동작] | 응답 계약은 충족 — 500 + `"서버 내부 오류가 발생했습니다."` 고정, 스택은 서버 로그에만. **단 클라이언트 입력 오류(`RequestRejectedException`)가 이 핸들러로 떨어져 500 + ERROR 스택** → **A-ISSUE-64**. 근거 라인 드리프트(:248-253 → 실제 :273-278) |
| TC-LOG-001 | PASS | [실동작] | 위 §1 표 — TAB/NUL/DEL/ESC/`U+0090`(C1) 전부 **제거**(치환 아님). LogSanitizer.java:88-100 |
| TC-LOG-002 | PASS | [실동작] | `U+2028`/`U+2029` 제거 실증. 평문 레이아웃(local)에서 가짜 라인 위조 불가 |
| TC-LOG-003 | PASS | [실동작] | `" ZZ 가 나 다"` 그대로 보존(`U+0020`=Zs 는 비대상) |
| TC-LOG-004 | PARTIAL | [실동작] | 초과 절단·`...(truncated)` 접미는 정상(300자→64자). **단 ①입력 길이가 정확히 상한과 같아도 허위 `(truncated)` 부착**(64자 'C' → `CCCC…(truncated)`, 63자는 미부착) **②서로게이트 페어가 경계에서 분할돼 고립 서로게이트가 로그에 실림**(63자+😀 → 고위 서로게이트만 잔존) → **A-ISSUE-65 / A-ISSUE-66** |
| TC-LOG-005 | PASS | [정적] | :27,52-54 `NULL_PLACEHOLDER="(null)"`, NPE 없음. 커버 `LogSanitizerTest("LogSanitizer_null_입력시_플레이스홀더_반환")` |
| TC-SORT-001 | PASS | [실동작] | `GET /v1/tasks/board?sort=secretField,desc` → **400** `"지원하지 않는 정렬 기준입니다."` — 입력값·내부 필드명 미노출. `/v1/assignments` 도 동일(strict) |
| TC-SORT-002 | PASS | [실동작] | `GET /v1/reviews?sort=secretField,desc` → **200** + 기본 정렬. `/v1/videos` 도 200. 로그 `[Sort] unsupported sort key ignored key=secretField` WARN 발생. ★2 확정 정책 준수 |
| TC-SORT-003 | PASS | [실동작] | strict: TASK_BOARD 고유 필드 3개 → 4개 지정 시 400 `"정렬 기준이 너무 많습니다."`(개수·상한 미노출). lenient: VIDEO 고유 4개 → 5개 지정 시 200 + **전체 폴백** + WARN `firstDroppedKey=id` |
| TC-SORT-004 | PASS | [실동작] | `?sort=capturedAt,asc&sort=shtDt,desc` 결과가 `?sort=capturedAt,asc` 단독과 동일(id 8,9,10) / 순서를 뒤집으면 `shtDt,desc` 단독과 동일(9101,9103,9104) → **첫 지정만 적용** 실증 |
| TC-SORT-005 | PASS | [실동작] | `?sort=aa%0AFAKE-LOG-LINE-…%0D%0AERROR injected` → WARN 한 줄, 개행 제거. 300자 입력은 64자+`...(truncated)`. SortAllowlist.java:66,224-225,230-231 |
| TC-SORT-006 | PARTIAL | [실동작] | 단일 진실원 자체는 성립 — `VideoController.java:109` 가 `SortAllowlist.VIDEO` 를 직접 참조하고 컨트롤러 allowlist 사본 **0건**(grep). **단 allowlist 정책이 적용되지 않은 목록 엔드포인트가 3곳 이상 남아 미등록 키 → 500 + `PropertyReferenceException` 스택**(SortAllowlist 가 막으려던 바로 그 회귀) → **A-ISSUE-61** |
| TC-BLANK-001 | PASS | [실동작] | 실행 SQL 실측: `trim(BOTH from replace(replace(…(mrc2_0.CCTV_NM, ?, ''), …))<>?` — `Character.isWhitespace` 파생 **24개** 공백류(space 제외) 제거 후 trim. 표시측도 `isBlank()`(ReviewService.java:183, TaskBoardService.java:203, AssignmentResponse.java:151) → 판정 일치. 단 전용 자동 테스트 0건 → A-ISSUE-67 |
| TC-BLANK-002 | PASS | [실동작] | 위 SQL 의 제거 대상이 전부 `?` **바인딩 파라미터**(문자열 연결 0). BlankTextPredicate.java:62-65,68-76. CWE-89 안전 |

---

## 3. 이슈

### [A-ISSUE-61] TC-SORT-006 — 정렬 allowlist 미적용 목록 엔드포인트 3곳 이상: 미등록 정렬 키가 500 + `PropertyReferenceException` 로 새어나감

- **심각도**: HIGH
- **기대 동작(기대효과)**: `SortAllowlist` 는 스스로의 javadoc(`SortAllowlist.java:21-27`)에서 목적을 "미등록 키가 절대 쿼리에 닿지 않게 한다 — 안 그러면 `PropertyReferenceException` → 500 + 내부 필드명 노출(CWE-209), 또는 FE 에 노출하지 않는 내부 컬럼으로 정렬해 값의 순서로부터 내용을 추론"이라고 명시한다. 따라서 **클라이언트가 `sort` 를 넣을 수 있는 모든 목록 API** 는 strict/lenient 중 하나를 반드시 통과해야 한다. CLAUDE.md "목록 화면 정렬·필터 정책" 도 "정렬 키는 allowlist 매핑으로만 해석하고 개수 상한을 둔다 (CWE-89 / CWE-770)"를 **구속 규칙**으로 못 박고 있다.
- **현재 동작(이슈 내용)**: allowlist 적용은 4곳(`TaskBoardController:101`, `AssignmentController:143`, `ReviewController:102`, `VideoController:115`)뿐이고, `Pageable`/`Sort` 를 그대로 받아 리포지토리로 넘기는 엔드포인트가 남아 있다.

  ```java
  // label/controller/DeidentReportController.java:76  — allowlist 미적용
  @PageableDefault(size = 20, sort = "reportDt", direction = Sort.Direction.DESC) Pageable pageable) {
      return ApiResponse.ok(deidentReportService.listReports(status, pageable));

  // portal/controller/PortalUploadController.java:90,111 · PortalLabelController.java:65 — 동일
  ```

  실동작(로컬 스택, 2026-08-01 18:57):
  ```
  GET /api/v1/deident-reports?sort=secretField,desc          → 500 {"errorCode":"INTERNAL_ERROR"}
  GET /api/v1/portal/uploads?sort=secretField,desc           → 500
  GET /api/v1/portal/datamart/videos?sort=secretField,desc   → 500
  ```
  서버 로그(ERROR + 전체 스택):
  ```
  ERROR [f3c82176da48] GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.data.mapping.PropertyReferenceException: No property 'secretField' found for type 'LsDeidentReport'
  ...
  ERROR [21e27d06d4a3] ... InvalidDataAccessApiUsageException: UnknownPathException: Could not resolve attribute 'secretField' of
    'kr.co.cudo.authoring.video.entity.LsDataRaw'
    [SELECT v FROM LsDataRaw v JOIN LsRawDataStatus s ON s.rawDataId = v.rawSn ... ORDER BY v.regDt DESC, v.secretField desc]
  ```
  추가로 **미노출 내부 컬럼 정렬**과 **정렬 항목 개수 무제한**이 그대로 통한다:
  ```
  GET /api/v1/deident-reports?status=RESOLVED&sort=rsn,asc      → 200 (신고 사유 자유서술 컬럼으로 정렬)
  GET /api/v1/deident-reports?status=RESOLVED&sort=reporterNo,desc → 200
  GET /api/v1/deident-reports?size=1&sort=rsn,asc × 60개          → 200 (상한 없음)
  GET /api/v1/portal/datamart/videos?sort=rawFilePathNm,asc      → 200 (원본 파일 경로 컬럼, 응답 미노출)
  ```
  (※ `datamart/videos` 는 `ORDER BY v.regDt DESC` 가 1차라 사용자 정렬은 2차 tie-break 로만 작용 — 추론력은 제한적. `/v1/deident-reports` 는 사용자 정렬이 1차로 적용된다.)
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -X POST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"REVIEWER","channel":"INTERNAL"}' | jq -r .data.token)
  curl -i -H "Authorization: Bearer $TOK" 'localhost:18081/api/v1/deident-reports?sort=secretField,desc'   # 500
  curl -i -H "Authorization: Bearer $TOK" 'localhost:18081/api/v1/deident-reports?sort=rsn,asc'            # 200 (내부 컬럼 정렬)
  docker logs klid-backend --since 1m | grep -A3 'unhandled exception'
  ```
- **영향**: CWE-209(내부 엔티티명·JPQL 원문이 ERROR 로그에 적재 — 응답 body 는 안전) · CWE-770(정렬 항목 무제한 → Hibernate 쿼리 플랜 캐시 오염, `SortAllowlist` 가 명시적으로 방어 목표로 삼은 벡터) · CWE-200 준하는 순서 기반 추론(자유서술 신고 사유·원본 파일 경로) · 인증 사용자 1명이 ERROR 로그를 무제한 적재해 실장애 로그를 묻히게 함(OWASP A09).
- **수정 방향(제안)**: `DeidentReportController.list`, `PortalUploadController`(2곳), `PortalLabelController`, `TaskQueryController`(2곳)에 각각 allowlist 상수를 `SortAllowlist` 에 신설하고 `SortAllowlist.apply`(strict, 기존 500 이던 곳) 또는 `SortFieldMapper.apply`(lenient, 기존 200 이던 곳)로 감싼다. ★2 정책대로 **엔드포인트별 "변경 전 200 이었는가" 기준**으로 모드를 고른다(포털/관제 조회는 기존 200 → lenient, 이미 500 이던 곳은 strict). 근본 차단으로는 `PageableHandlerMethodArgumentResolver` 커스터마이저에서 화이트리스트 미등록 컨트롤러의 `Sort` 를 강제로 `unsorted()` 로 만드는 fail-closed 기본값도 검토.

### [A-ISSUE-62] TC-LOG-001 — 로그 마스킹 규칙에 PII(전화·이메일·주민번호형)·JSON 형태·bare JWT 가 전무 (실동작 누출 확인)

- **심각도**: HIGH
- **기대 동작(기대효과)**: CLAUDE.md "개인정보 보호" 는 *"로그에 개인정보·토큰 출력 금지 (Logback MaskingPatternLayout)"* 를, `rules/security.md` "민감 정보 목록" 은 *"전화번호, 이메일 (마스킹 필수: `010-****-1234`)"* · *"세션 ID, JWT 토큰 전문"* 을 **금지 항목**으로 규정한다. 즉 `MaskingPatternLayout`/`MaskingJsonValueMasker` 는 토큰뿐 아니라 PII 도 가려야 한다.
- **현재 동작(이슈 내용)**: 두 마스커의 정규식이 **`key=value` / `authorization:` 두 형태**뿐이다.
  ```java
  // common/logging/MaskingPatternLayout.java:10-15 (MaskingJsonValueMasker.java:18-23 도 동일 문자열)
  HEADER_COLON_PATTERN = "(?i)(authorization)\\s*:\\s*(?:Bearer\\s+)?\\S+"
  KV_PATTERN           = "(?i)(password|token|secret|authorization)\\s*=\\s*\\S+"
  ```
  실동작(local 프로파일, `docker logs klid-backend`) — 아래는 **전부 마스킹되지 않고 평문으로 기록**:
  ```
  key={"password":"hunter2"}                       ← JSON 형태 (외부 API 응답·요청 바디 로깅 시 통상 형태)
  key=010-1234-5678                                ← 전화번호
  key=ssn-900101-1234567                           ← 주민번호형
  key=user@example.com                             ← 이메일
  key=Bearer eyJhbGciOiJIUzI1NiJ9.PAYLOAD.SIG      ← 접두어 없는 JWT 전문
  key=X-Access-Token: TOKENVAL                     ← authorization 이외 토큰 헤더 (관제 계약 헤더명)
  ```
  추가로 **dev/stg/prd 는 `<stackTrace/>` provider 에 masker 가 걸려 있지 않다**(`logback-spring.xml` 의 `valueMasker` 는 `<message>` 하위에만 선언) → 예외 메시지·스택에 실린 토큰/PII 는 JSON 로그에서 무마스킹으로 나간다. (local 은 `MaskingPatternLayout.doLayout` 이 throwable 포함 전체 문자열을 처리해 이 격차가 없다 — **환경 간 비대칭**.)
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -H "Authorization: Bearer $TOK" \
    'localhost:18081/api/v1/videos?size=1&sort=010-1234-5678,desc'
  curl -s -o /dev/null -H "Authorization: Bearer $TOK" \
    'localhost:18081/api/v1/videos?size=1&sort=%7B%22password%22%3A%22hunter2%22%7D,desc'
  docker logs klid-backend --since 1m | grep 'unsupported sort key'
  ```
- **영향**: CWE-532(민감정보 로그 저장) / CWE-359(개인정보 노출) / OWASP A09:2025. 저작도구는 CCTV 영상 도메인이라 신고 사유·CCTV 명·외부 벤더 응답에 개인정보가 실릴 여지가 크고, 로그는 통상 장기 보존·광범위 열람 대상이다.
- **수정 방향(제안)**: ①`MaskingPatternLayout`/`MaskingJsonValueMasker` 의 정규식을 **단일 상수 클래스로 추출**해 두 구현의 드리프트를 없애고 ②JSON 형태(`"(password|token|secret|access_token|x-access-token)"\s*:\s*"[^"]*"`) · bare JWT(`eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+`) · 전화(`01[016-9]-?\d{3,4}-?\d{4}`) · 주민번호(`\d{6}-?[1-4]\d{6}`) · 이메일 패턴을 추가하며 ③`logback-spring.xml` 의 dev/stg/prd `<stackTrace/>` 에도 마스킹을 적용(`ShortenedThrowableConverter` + masker 또는 `throwableConverter`)한다. 정규식 추가 시 로그 경로 hot path 이므로 컴파일 상수 + 조기 반환으로 비용을 관리한다.

### [A-ISSUE-63] TC-LOG-001 — `authorization=[Bearer …]` 형태에서 토큰 값이 부분 누출

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 헤더 맵을 통째로 로깅하는 형태(`headers={Authorization=[Bearer eyJ…]}` — Spring `HttpHeaders#toString` 의 기본 출력 형태)에서도 토큰 값 전체가 가려져야 한다.
- **현재 동작(이슈 내용)**: `KV_PATTERN` 의 값 매칭이 `\S+` 라 **첫 공백에서 멈춘다**. `Bearer` 토큰은 `Bearer` 와 값 사이에 공백이 있어 뒤쪽 값이 남는다.
  ```
  투입:  authorization=[Bearer LEAKED]
  로그:  key=authorization=*** LEAKED]      ← 토큰 값이 그대로 남음
  ```
  (`MaskingPatternLayout.java:14` — `(?i)(password|token|secret|authorization)\s*=\s*\S+`)
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -H "Authorization: Bearer $TOK" \
    'localhost:18081/api/v1/videos?size=1&sort=authorization%3D%5BBearer%20LEAKED%5D,desc'
  docker logs klid-backend --since 1m | grep 'unsupported sort key'
  ```
- **영향**: CWE-532 / CWE-359. `HEADER_COLON_PATTERN` 은 `(?:Bearer\s+)?` 를 처리하는데 `KV_PATTERN` 만 누락돼 있어, "헤더 맵 toString" 이라는 가장 흔한 유출 경로가 열려 있다.
- **수정 방향(제안)**: `KV_PATTERN` 값부를 `(?:\[?\s*)?(?:Bearer\s+)?[^\s,\]}]+` 처럼 Bearer 접두 + 대괄호 래핑을 흡수하도록 확장하고, 마스킹 후 잔여 토큰이 없는지 확인하는 회귀 테스트(`MaskingPatternLayoutTest`)를 추가한다.

### [A-ISSUE-64] TC-EXC-017 — 클라이언트 헤더 오류(`RequestRejectedException`)가 500 + ERROR 스택으로 오분류

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 잘못된 요청 헤더는 **클라이언트 오류(400)** 다. 이 코드베이스는 이미 같은 논리로 `HttpRequestMethodNotSupportedException` 을 500 → 405 로 정규화하면서 *"이 핸들러가 없으면 500 + ERROR 스택트레이스가 된다 … 모니터링 로그에 정상 오요청이 장애와 섞여 쌓인다"*(GlobalExceptionHandler.java:251-255)고 사유를 명문화했다. 같은 기준이 firewall 거부에도 적용돼야 한다.
- **현재 동작(이슈 내용)**: `@RequestBody` 를 읽는 핸들러에서 헤더 열거가 일어나는데, Spring Security `StrictHttpFirewall` 이 그 시점에 예외를 던져 `@ExceptionHandler(Exception.class)`(GlobalExceptionHandler.java:273-278)로 떨어진다.
  ```
  HTTP/1.1 500
  {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}

  ERROR [abcd] GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.security.web.firewall.RequestRejectedException: The request was rejected because the header:
    "x-trace-id " has a value "Â â¨test" that is not allowed.
      at ...StrictHttpFirewall$StrictFirewalledRequest.validateAllowedHeaderValue(StrictHttpFirewall.java:837)
      at ...AbstractMessageConverterMethodArgumentResolver.readWithMessageConverters(...:158)
      (전체 스택 40여 줄)
  ```
  응답 body 자체는 안전(고정 메시지)이라 TC-EXC-017 의 단언은 충족하나, **분류와 로그 레벨이 틀렸다**.
- **재현/확인 경로** (raw 소켓 — curl 로는 C1 제어바이트 헤더를 못 보냄):
  ```python
  import socket
  tok = open('/tmp/rev.tok').read().strip()
  body = b'{"workerId":2001,"rawDataIds":[26]}'
  raw = (b"POST /api/v1/assignments HTTP/1.1\r\nHost: localhost:18081\r\n"
         b"Authorization: Bearer " + tok.encode() + b"\r\nContent-Type: application/json\r\n"
         b"Content-Length: " + str(len(body)).encode() +
         b"\r\nX-Trace-Id: ab\xe2\x80\xa8cd\r\nConnection: close\r\n\r\n" + body)
  s = socket.create_connection(("localhost", 18081)); s.sendall(raw); print(s.recv(4096))
  # → HTTP/1.1 500 / INTERNAL_ERROR
  ```
- **영향**: ①클라이언트가 서버 장애로 오인(가용성 지표 왜곡) ②인증 사용자가 임의 횟수로 **ERROR 레벨 40줄 스택**을 적재 가능 → 로그 폭주 + 실장애 은폐(OWASP A09, CWE-770) ③모니터링 알림 오탐.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `@ExceptionHandler(org.springframework.security.web.firewall.RequestRejectedException.class)` 를 추가해 **400 INVALID_INPUT + WARN(메시지 없이 거부 사유 분류만)** 으로 정규화한다. 헤더 값·경로 원문은 로그에 남기지 않는다(CWE-117/209). 근본적으로는 `RequestRejectedHandler` 빈을 등록해 필터 단계에서 400 으로 종결시키는 편이 더 이르다.

### [A-ISSUE-65] TC-LOG-004 — 입력 길이가 정확히 상한과 같아도 허위 `...(truncated)` 접미

- **심각도**: LOW
- **기대 동작(기대효과)**: `...(truncated)` 는 "잘렸다"는 사실을 운영자에게 알리는 표식이다. 잘리지 않은 값에 붙으면 운영자가 원본을 더 찾게 되고, 반대로 경계 값을 만든 공격자는 로그 판독을 교란할 수 있다.
- **현재 동작(이슈 내용)**: 문자를 먼저 붙이고 나서 길이를 검사하므로 `length == maxLength` 인 정상 입력에도 접미가 붙는다.
  ```java
  // common/util/LogSanitizer.java:66-70
  sb.append(c);
  if (sb.length() >= maxLength) {      // == 인 경우도 참
      sb.append(TRUNCATED_SUFFIX);
      break;
  }
  ```
  실동작(상한 64인 `SortAllowlist` WARN 경로):
  ```
  'C'×64 → key=CCCC…(64자)...(truncated)     ← 잘린 게 없는데 접미가 붙음
  'D'×63 → key=DDDD…(63자)                   ← 정상
  ```
  부수적으로 결과 문자열 길이가 `maxLength + 14` 라 "길이 상한" 이 하드 캡이 아니다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -H "Authorization: Bearer $TOK" "localhost:18081/api/v1/videos?size=1&sort=$(python3 -c "print('C'*64)"),desc"
  docker logs klid-backend --since 30s | grep 'unsupported sort key'
  ```
- **영향**: 기능·보안 영향은 없고 로그 판독 정확도 저하(운영성). CWE 매핑 없음.
- **수정 방향(제안)**: 절단 판정을 "다음 문자가 남아 있는가"로 바꾼다 — 루프 조건을 `sb.length() < maxLength` 로 두고 루프 종료 후 `i < input.length()` 인 경우에만 접미를 붙인다. 회귀 테스트로 `length == maxLength` 케이스를 `LogSanitizerTest` 에 추가한다.

### [A-ISSUE-66] TC-LOG-004 — 절단이 서로게이트 페어를 분할해 고립 서로게이트가 로그에 실림

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 정제 결과는 항상 유효한 유니코드 문자열이어야 한다. dev/stg/prd 는 로그를 JSON(`LoggingEventCompositeJsonEncoder`)으로 직렬화하므로 고립 서로게이트는 인코딩 단계에서 예외 또는 치환을 유발할 수 있다(로그 이벤트 유실 위험).
- **현재 동작(이슈 내용)**: `LogSanitizer.sanitize` 가 `char` 단위로 순회·절단해 BMP 밖 문자(이모지·일부 한자 확장)의 페어를 반으로 자른다.
  ```java
  // common/util/LogSanitizer.java:60-71
  for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);          // code unit 단위 — 서로게이트 페어를 모름
      ...
      sb.append(c);
      if (sb.length() >= maxLength) { sb.append(TRUNCATED_SUFFIX); break; }
  }
  ```
  실동작(상한 64): 입력 `'E'×63 + U+1F600(😀) + "tail"` → 로그
  ```
  key=EEEE…(63자)?...(truncated)      ← ? 는 고위 서로게이트(U+D83D) 단독. 저위(U+DE00)는 유실
  ```
  (`cat -v` 로 확인. local 프로파일 평문 인코더라 콘솔에는 치환 문자로 나오지만, **문자열 자체가 고립 서로게이트**다.)
- **재현/확인 경로**:
  ```bash
  K=$(python3 -c "import urllib.parse;print(urllib.parse.quote('E'*63+'\U0001F600'+'tail'))")
  curl -s -o /dev/null -H "Authorization: Bearer $TOK" "localhost:18081/api/v1/videos?size=1&sort=$K,desc"
  docker logs klid-backend --since 30s | grep 'unsupported sort key' | cat -v
  ```
- **영향**: CWE-116(부적절한 출력 인코딩). dev/stg/prd JSON 인코더에서의 실제 거동(예외 vs 치환)은 **현재 스택이 local 프로파일이라 미검증** — 최악의 경우 해당 로그 이벤트 유실(OWASP A09). CCTV 명·신고 사유 등 사용자 입력에 이모지가 흔해 도달 가능성이 낮지 않다.
- **수정 방향(제안)**: 절단 시 마지막 문자가 `Character.isHighSurrogate` 면 그 문자를 제거하거나, 순회를 `input.codePoints()` 기반으로 바꿔 code point 단위로 자른다. `LogSanitizerTest` 에 경계에 걸친 서로게이트 페어 케이스를 추가한다.

### [A-ISSUE-67] TC-BLANK-001 — `BlankTextPredicate` 전용 자동 테스트 0건 (구조적 드리프트 방어 부재)

- **심각도**: LOW
- **기대 동작(기대효과)**: `BlankTextPredicate` 는 "Java `isBlank()` 와 SQL 판정을 **구조적으로** 일치시킨다"는 것이 존재 이유(javadoc `BlankTextPredicate.java:11-24`)다. 그 동치성이 깨지면 "화면에 보이는 폴백값으로 검색해도 안 나오는 영상"이 다시 생기므로, 동치성을 고정하는 회귀 테스트가 필요하다.
- **현재 동작(이슈 내용)**: 테스트 트리 전체에서 `BlankTextPredicate` / `isBlankAsJava` 를 참조하는 파일이 **0건**이다.
  ```bash
  $ grep -rln "BlankTextPredicate\|isBlankAsJava" backend/src/test    # → 결과 없음
  ```
  실제 SQL 은 정상 동작함을 실측했으나(§2 TC-BLANK-001/002), 호출부(`ReviewQueryRepository:332`, `TaskBoardQueryRepository:407`, `AssignmentQueryRepository:361`)가 구 `trim(col) <> ''` 로 되돌아가도 잡히지 않는다.
- **재현/확인 경로**: 위 grep. 실동작 SQL 은 `GET /api/v1/reviews?q=강남` → `docker logs klid-backend | grep 'replace('`.
- **영향**: 기능 회귀 방어 부재(보안 아님). 과거 실제로 발생했던 결함의 재발 방지선이 없다.
- **수정 방향(제안)**: Testcontainers IT 로 `CCTV_NM` 이 `'\t'`·`'　'`·`' '`(NBSP — Java `isBlank()`=false)·`' '`·`''` 인 행을 넣고 목록 검색이 폴백 축(`VMS_CCTV_ID`)으로 열리는지/안 열리는지를 Java `isBlank()` 기대값과 대조한다. 24개 공백류 목록이 `Character.isWhitespace` 에서 파생되는지 확인하는 단위 테스트도 함께 둔다.

### [A-ISSUE-68] TC-TRACE-001~005 / TC-RESP-001~004 — `RequestIdFilter`·`ApiResponse` 전용 자동 테스트 0건

- **심각도**: LOW
- **기대 동작(기대효과)**: `RequestIdFilter` 는 CRLF 주입 방어(CWE-113)와 필터 순서라는 **보안 계약**을 지니고, `ApiResponse` 는 전 API 응답 포맷의 단일 계약이다. 둘 다 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: 테스트 트리에 `RequestIdFilter` / `X-Trace-Id` / `traceId` 를 참조하는 파일 **0건**, `ApiResponse` 전용 테스트 **0건**.
  ```bash
  $ grep -rln "RequestIdFilter" backend/src/test        # 결과 없음
  $ grep -rln "X-Trace-Id\|traceId"  backend/src/test   # 결과 없음
  ```
  본 회차에서 실동작으로는 전건 PASS 확인(§2)했으나, sanitize 화이트리스트(`RequestIdFilter.java:52-57`)나 `@Order`(:17)를 누가 바꿔도 CI 가 잡지 못한다.
- **재현/확인 경로**: 위 grep.
- **영향**: 보안 회귀 방어 부재(CWE-113 재발 위험). 현재 결함은 아님.
- **수정 방향(제안)**: `RequestIdFilterTest`(MockHttpServletRequest 로 CR/LF·특수문자·65자 초과·빈 결과 시 생성·`MDC.remove` 검증) + `ApiResponseTest`(4개 팩토리 + `JsonInclude.ALWAYS` 직렬화로 `data` 키 유지) 추가.

### [A-ISSUE-69] TC-EXC-004 — `ConstraintViolationException` 응답 메시지에 컨트롤러 핸들러 메서드명 노출

- **심각도**: LOW
- **기대 동작(기대효과)**: 형제 핸들러들이 일관되게 CWE-209 가드를 명시한다 — TC-EXC-007 은 "사용자 입력 원문/타입 내부 상세를 메시지에 노출하지 않는다"(GlobalExceptionHandler.java:86), TC-SORT-001 은 "입력값·내부 필드명 미노출". 파라미터 검증 메시지도 **파라미터명까지만** 노출해야 한다.
- **현재 동작(이슈 내용)**: `ConstraintViolation#getPropertyPath()` 를 그대로 붙여 **핸들러 메서드명이 접두로** 실린다.
  ```java
  // common/exception/GlobalExceptionHandler.java:51-53
  String msg = e.getConstraintViolations().stream()
          .map(v -> v.getPropertyPath() + ": " + v.getMessage())   // propertyPath = "list.q"
          .collect(Collectors.joining(", "));
  ```
  실동작:
  ```
  GET /api/v1/reviews?q=<150자>
  → 400 {"message":"list.q: 검색어는 100자 이하여야 합니다","errorCode":"INVALID_INPUT"}
                       ^^^^ 컨트롤러 메서드명
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -w '\n[%{http_code}]\n' -H "Authorization: Bearer $TOK" \
    "localhost:18081/api/v1/reviews?q=$(python3 -c "print('a'*150)")&size=1"
  ```
- **영향**: CWE-209(경미). 내부 메서드명이 드러나 API 내부 구조 추론에 소량 기여. 실질 피해는 낮음(Swagger 로 상당 부분이 이미 공개).
- **수정 방향(제안)**: `propertyPath` 의 **마지막 노드만** 사용한다(`StreamSupport.stream(v.getPropertyPath().spliterator(),false).reduce((a,b)->b)`). `MethodArgumentNotValidException` 핸들러(:38 `fe.getField()`)는 이미 필드명만 쓰므로 두 경로의 형식이 통일되는 이점도 있다.

### [A-ISSUE-70] TC-SORT-001~006 / TC-EXC-017 — 카탈로그 근거 `file:line` 드리프트 7건

- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 표의 근거 `file:line` 이 실제 코드 위치를 가리켜야 다음 회차 검증·수정이 곧바로 진입할 수 있다.
- **현재 동작(이슈 내용)**: 아래 7건이 어긋난다(코드가 이동했고 카탈로그가 따라가지 못함).

  | TC | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | TC-EXC-017 | `GlobalExceptionHandler.java:248-253` | `:273-278` (사이에 405 핸들러 :261-271 추가됨) |
  | TC-SORT-001 | `SortAllowlist.java:139-162` | `:163-186` (거부 `:174`,`:179`) |
  | TC-SORT-002 | `SortAllowlist.java:189-215` | `:213-239` |
  | TC-SORT-003 | `SortAllowlist.java:143,193,198-203,223-225` | `:167`(strict 상한), `:217`(lenient 상한), `:222-227`, `:247-249`(`maxOrders`) |
  | TC-SORT-004 | `SortAllowlist.java:145,157-159,210-212` | `:181-183`(strict), `:234-236`(lenient) |
  | TC-SORT-005 | `SortAllowlist.java:66,200-201,206-207` | `:66` 정확, 로그는 `:224-225`,`:230-231` |
  | TC-SORT-006 | `SortAllowlist.java:109-116` | `VIDEO` 상수는 `:133-140`. `SortFieldMapper.java:29-31,57-70` 은 `:57-60,68-70` |

  ※ 다른 32건(TC-TRACE·TC-RESP·TC-EXC-001~016·TC-LOG·TC-BLANK)의 근거 라인은 **전부 정확**했다.
- **재현/확인 경로**: 각 파일 Read 후 표와 대조.
- **영향**: 검증 효율(카탈로그 자체의 정합성 결함). 기능·보안 영향 없음.
- **수정 방향(제안)**: `docs/test-cases/A-auth-common.md` A-6 절의 위 7행 근거를 위 표의 실제 위치로 갱신.

### [A-ISSUE-71] TC-EXC-003 — 인가(@PreAuthorize) 평가 전에 @Valid 바디 검증이 먼저 실행돼 권한 없는 사용자에게 입력 스키마가 노출

- **심각도**: LOW
- **기대 동작(기대효과)**: 권한 없는 주체에게는 **거부(403)** 가 먼저 나가고, 요청 스키마(필드명·제약)를 알려주지 않는 편이 fail-secure 하다(`rules/security.md` — "인증/인가 실패 시 반드시 거부 처리(fail-open 금지)", CWE-209).
- **현재 동작(이슈 내용)**: `@RequestBody` 인자 해석·검증이 `@PreAuthorize` 인터셉터보다 먼저 일어나, WORKER 토큰(권한 없음)으로 REVIEWER 전용 엔드포인트를 호출하면 **403 이 아니라 400 + 필드 목록**이 돌아온다.
  ```
  POST /api/v1/assignments   (WORKER 토큰)  -d '{"rawSn":26,"userSn":2001}'
  → 400 {"message":"workerId: workerId 는 필수입니다., rawDataIds: rawDataIds 는 1건 이상이어야 합니다.",
         "errorCode":"INVALID_INPUT"}          ← 권한이 없는데 스키마를 알려줌

  POST /api/v1/assignments   (WORKER 토큰)  -d '{"workerId":2001,"rawDataIds":[26]}'
  → 403 {"errorCode":"FORBIDDEN"}              ← 바디가 유효해야 비로소 403
  ```
- **재현/확인 경로**: 위 두 curl (WORKER 토큰은 `POST /v1/dev/tokens {"role":"WORKER","channel":"INTERNAL"}`).
- **영향**: CWE-209(경미) — 인가되지 않은 사용자가 요청 스키마와 검증 규칙을 열거할 수 있다. 실질 위험은 Swagger 공개 범위에 따라 달라지며, 상태 변경은 발생하지 않는다. **Spring MVC 의 구조적 순서**(인자 해석 → 메서드 보안 인터셉터)라 컨트롤러별 수정이 아니라 정책 결정이 필요하다.
- **수정 방향(제안)**: (a) 현행 유지 — 프레임워크 기본 동작이며 상태 변경 없음, 문서에 "의도된 동작"으로 명시 / (b) 민감 엔드포인트에 한해 `SecurityConfig` 의 **URL 기반 `authorizeHttpRequests` 규칙**(필터 체인 = 인자 해석 이전)으로 역할을 한 번 더 게이트. 어느 쪽이든 **정책으로 확정**해 다음 회차에 판정 기준이 흔들리지 않게 한다.

---

## 4. 부수 관측 (이슈 미승격 — 사실 기록)

1. **`SortAllowlist.resolve`(strict)의 개수 상한 검사가 allowlist 검사보다 먼저다**(`:172-180`). 즉 미등록 키를 상한 초과 개수로 보내면 `"정렬 기준이 너무 많습니다."` 가 먼저 나온다. 두 메시지 모두 입력값을 노출하지 않으므로 CWE-209 영향은 없다.
2. **`CustomException(ErrorCode, String, Throwable)` 와 `(ErrorCode, String, Object)` 오버로드 공존** — Java 오버로드 해석상 `Throwable` 인자는 항상 cause 생성자로 가고 `details` 는 null 이 된다(현행 동작 정상). 다만 `null` 리터럴을 3번째 인자로 넘기면 컴파일 모호성 오류가 되므로 호출부에서 주의 필요.
3. **Tomcat 단계에서 거부되는 요청은 `ApiResponse` 포맷이 아니다** — 헤더에 raw 제어바이트를 넣으면 Tomcat 이 자체 HTML 400 페이지를 반환한다(앱 도달 전). 서버/프레임워크 버전은 노출되지 않아 CWE-209 상 문제는 없다.
4. **`/v1/tasks/board/summary`·`/board/event-types` 는 `Pageable` 을 받지 않는다** — 정렬 파라미터를 붙여도 200(무시). ★2 정책과 무관한 집계 엔드포인트다.
5. **`BlankTextPredicate` 는 SQL 에 `replace()` 24중첩을 생성**한다(`Character.isWhitespace` − space). 실행 SQL 실측으로 확인했고 javadoc 이 비용을 이미 명시(정확성 우선 결정)하고 있어 결함으로 보지 않는다. 다만 `LS_DATA_RAW` 스캔 경로에서 행당 평가되므로 데이터 증가 시 성능 관측 대상.
6. **`X-Access-Token`(관제 계약 헤더, UNCERTAINTIES #26)이 마스킹 대상 밖**이라는 점은 A-ISSUE-62 에 포함했다. 향후 관제 통지에 인증 헤더가 배선되면 마스킹 정규식 갱신이 **동반 필수**다.
# A-7. 듀얼 데이터소스 · 캐시 · 시스템설정 · 기동 가드 · 설정 바인딩 · Actuator/헬스 · Resilience4j (49건)

검증일: 2026-08-01 · 대상 스택: klid-backend(구버전 V146, `SPRING_PROFILES_ACTIVE=local`, `ENV` 미설정) · 근거는 `docs/검증결과/2026-08-01/1차/_raw/stack-bringup.md`(기동/배선) · `test-baseline.md`(backend 4,755 tests, 실패 0, cleanTest 강제 실행 확인) 참고.

이 섹션은 커밋 범위(b2b44f0e~56d30478)의 신규 기능(LS_DATA_INGEST 적재 리팩터·증강 폐기/복구)과 무관 — 환경 버전 격차(BLOCKED) 대상 케이스 없음.

## 결과표

| ID | 판정 | 근거확인 |
|----|:--:|----------|
| TC-DS-001 | PASS | [실동작]+[정적] `ControlDataSourceConfig.java:31-55` — `@Primary` 3곳(dataSource/EMF/TxManager) 확인. actuator health(REVIEWER 인증) 응답에 `db.components.controlDataSource:UP` 실측 |
| TC-DS-002 | PASS | [정적] `ControlDataSourceConfig.java:19-28` `@EnableJpaRepositories(includeFilters=@ControlRepo)`, `entityManagerFactoryRef=controlEntityManagerFactory`. `@ControlRepo` 사용처 63개 확인(grep) |
| TC-DS-003 | PASS | [정적] `PortalDataSourceConfig.java:18-28,47-52` — `@Primary` 전무 확인(파일 전체에 `@Primary` 0건), `portalTransactionManager` 라우팅. `@PortalRepo` 사용처 1개 확인. actuator health `db.components.portalDataSource:UP` 실측 |
| TC-DS-004 | PASS | [정적] `SystemConfigService.java:38,91` — `listAll()`/`update()` 모두 `@Transactional(value="controlTransactionManager", ...)`. 라인 드리프트 없음(정확 일치) |
| TC-CACHE-001 | PASS | [정적] `CacheConfig.java` 4개 캐시 TTL/사이즈: sysconfig 60s/max100(70-74), stream-meta 5m/max200(66,76-80), eventType 6h/max50(82-86), userRole 60s/max500(88-92). ⚠ 근거 라인 카탈로그(`51-54,63,65-94`)가 실제(`53-56,66,68-97`)보다 약 2~3줄 앞섬(Javadoc 확장 드리프트, 내용은 일치) |
| TC-CACHE-002 | PASS | [정적]+테스트커버 `SystemConfigService.java:47-48,80-82` 라인 정확 일치(`@Cacheable`+`@Transactional` 페어). `SystemConfigControllerTest`·`PolygonSimplifyConfigTest` 등에서 `getInt`/`getDouble` 경로 커버, baseline 전량 통과 |
| TC-CACHE-003 | PASS | [정적] `SystemConfigService.java:90-91` 라인 정확 일치(`@CacheEvict(allEntries=true)`). 실동작: PUT `/v1/manage/configs/BATCH_CONCURRENCY` 성공 후 GET 목록에 새 값 즉시 반영 확인(아래 참고) |
| TC-CACHE-004 | PASS | [정적] `UserRoleResolver.java:46` 라인 정확 일치 — `@Cacheable(..., unless="#result==null")` |
| TC-CACHE-005 | PARTIAL | [정적] TTL 값(5분) 자체는 `CacheConfig.java:66` 로 확인되나, "구 파일 유예가 TTL 보다 길어야 하며 **기동 시 검증됨**" 주장은 더 이상 사실이 아님 — 그 검증을 수행하던 `ResolutionBackfillService`가 2026-07-30 스윕잡·설정키와 함께 전량 제거됨(`UNCERTAINTIES.md` #29~31, `CacheConfig.java:63-64` 주석이 자인). 현재 이 TTL 하한을 쓰는 흐름 자체가 없음 → A-ISSUE-81 |
| TC-SYSCFG-001 | PASS | [실동작] `PUT /v1/manage/configs/NOT_ALLOWED_KEY` → `400 {"errorCode":"INVALID_INPUT","message":"허용되지 않은 설정 키입니다."}` — 입력 키 미노출(CWE-117) 확인. `SystemConfigService.java:94-98` 라인 정확 일치 |
| TC-SYSCFG-002 | PASS | [실동작] WORKER 토큰으로 `PUT /v1/manage/configs/BATCH_CONCURRENCY` → `403 FORBIDDEN`. Controller `@PreAuthorize("hasRole('REVIEWER')")` + Service `verifyReviewer` 이중 검증 확인(`SystemConfigController.java:65`, `SystemConfigService.java:93,166-169`) |
| TC-SYSCFG-003 | PASS | [정적] `SystemConfigService.java:166-169` 라인 정확 일치. HTTP 경로로는 인증된 요청만 Controller 를 통과하므로 `actor=null` 분기는 방어적 코드(2차 방어선)로만 도달 — 코드 검토로 확인 |
| TC-SYSCFG-004 | PASS | [실동작] `PUT BATCH_CONCURRENCY value=99`(허용범위 1~10) → `400 {"message":"값이 허용 범위를 벗어났습니다."}`. `ConfigKeys.NUMBER_RANGE` 값 실측 대조(BATCH_CONCURRENCY=[1,10] 등 7개 키) |
| TC-SYSCFG-005 | PASS | [실동작] `PUT POLYGON_SIMPLIFY_TOLERANCE value=NaN` → `400 {"message":"유효하지 않은 숫자입니다."}` — `Double.parseDouble("NaN")`이 예외를 던지지 않고 `isNaN()` 방어선이 실제로 작동함을 확인 |
| TC-SYSCFG-006 | PASS | [정적] `SystemConfigService.java:116-125` 라인 정확 일치 |
| TC-SYSCFG-007 | PASS | [정적] `SystemConfigService.java:127-128` 라인 정확 일치 |
| TC-SYSCFG-008 | PASS | [정적] `SystemConfigService.java:49-61` 라인 정확 일치 |
| TC-SYSCFG-009 | PASS | [정적] `SystemConfigService.java:110-113`(실제 110-114, 세미콜론 줄 차이) — `loadOrThrow` NOT_FOUND 확인 |
| TC-PROF-001 | PASS | [정적] `LocalProfileGuard.java:43-55` 라인 정확 일치 |
| TC-PROF-002 | PASS | [정적] `LocalProfileGuard.java:47-49` 라인 정확 일치 |
| TC-PROF-003 | PASS | [정적] `LocalProfileGuard.java:44-46` 라인 정확 일치 |
| TC-PROF-004 | PASS | [정적] `DevProfileGuard.java:42,46-61` 라인 정확 일치. `DEPLOYED_ENVS={stg,prd}`만 거부, ENV=qa 등 미지 라벨은 통과 로직 확인 |
| TC-PROF-005 | PASS | [정적] `DevToggleProfileGuard.java:37,42-65`(실제 66까지, 근사 일치). `PRODUCTION_LIKE=Profiles.of("prd","stg")`, stg 는 yml 리터럴 `false`(env var 로 덮이므로 이 가드가 유일한 강제) — `application-stg.yml` 확인 |
| TC-PROF-006 | PASS | [정적]+[실동작] `ForwardedHeadersConfigGuard.java:49-66,82-113` 라인 정확 일치. 전 yml 파일에 해당 3키 설정 0건(grep) — 설정돼 있었다면 기동 자체가 실패했을 것인데 backend 정상 기동·healthy 상태로 간접 확증 |
| TC-PROF-007 | PASS | [정적]+[실동작] `DeidentifyEndpointTrustGuard.java:58-62,82-127,188-198` 라인 정확 일치. 현재 스택은 `KPST_DEID_BASE_URL=http://klid-mock-server:9400`(미신뢰 호스트) + `SPRING_PROFILES_ACTIVE=local` → `isLocal()`true 로 무음 처리되어야 함. 실측: `docker logs klid-backend`에 `DeidentifyEndpointTrustGuard`/`untrusted deidentification` 로그 0건(=무음, 기대대로) |
| TC-PROF-008 | PASS | [정적] `DeidentifyEndpointTrustGuard.java:106-123,139-177` 라인 정확 일치 — authority 폴백 로직 확인 |
| TC-PROF-009 | PASS | [실동작] `docker logs klid-backend`: `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]` 실측(local 이므로 허용). `QuartzClusteringGuard.java:50-56,64-95` 라인 정확 일치. `application-stg.yml`/`application-prd.yml` 모두 `isClustered: ${QUARTZ_CLUSTERED:true}` 확인(카탈로그 라인은 comment 확장으로 stg:11→실제13, prd:13→실제15, 근소 드리프트) |
| TC-PROF-010 | PASS | [정적] `QuartzClusteringGuard.java:97-112` 라인 정확 일치. `singleNodeAllowed` 로직 추적: 혼합(`local,prd`)/대문자(`LOCAL`)/오타(`prd1`)/미지정(빈 리스트)/ENV 독립축 5개 시나리오 모두 코드상 strict 로 귀결됨을 확인 |
| TC-PROF-011 | PASS | [정적] `ProfileGatedUrlPolicy.java:45-51,78-91,145-161` 라인 정확 일치 |
| TC-PROF-012 | PASS | [정적] `ProfileGatedUrlPolicy.java:121-133` 라인 정확 일치 — `check()`가 `policy()`를 매번 재평가(이중 방어) |
| TC-PROF-013 | PASS | [정적] `ExternalUrlPolicy.java:90-133,168-188` 라인 정확 일치. `requirePublicNetwork`가 `UnknownHostException`도 거부로 처리(DNS rebinding 대응), 예외 메시지에 host 만 노출(baseUrl 원문 미노출) 확인 |
| TC-PROF-014 | PASS | [정적] `ExternalUrlPolicy.java:123-127,143-166` 라인 정확 일치 — `rejectMetadataRangeIfResolvable`: 해석 실패는 return(통과), 해석 성공 시 링크로컬/169.254. 만 거부 |
| TC-PROF-015 | PASS | [정적] `ExternalUrlPolicy.java:43-47,117-122` 라인 정확 일치 |
| TC-PROF-016 | PASS | [정적] `ProfileGatedUrlPolicy.java:104-113` 라인 정확 일치, `WebClientConfig.java:63-67` 라인 정확 일치 — 토큰값 미출력, `tokenLength`만 로깅 확인 |
| TC-PROF-017 | PASS | [실동작] `docker logs klid-backend`: `[GenAi] 위탁(http) ↔ 콜백 IP allowlist 짝 확인 완료` 실측. 현재 `AUGMENT_EXTERNAL_MODE=http` + `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0`(짝 충족) 확인. `GenAiIntegrationWiringGuard.java:35-39,52-85` 라인 정확 일치 |
| TC-CFG-001 | PASS | [정적] `application-prd.yml:2-5`(comment 정확 일치) + `14-28`→실제 hikari 값(19-20,26-27) `maximum-pool-size:20`/`minimum-idle:5` 확인. `ControlDataSourceConfig.java:32-36`(실제 31-36) `@ConfigurationProperties(prefix="spring.datasource.control")`가 `HikariDataSource` 인스턴스에 평면 바인딩. `DataSourcePoolBindingTest` 존재 확인(baseline 통과) |
| TC-CFG-002 | PASS | [정적] `application-prd.yml` multipart `max-file-size:21MB`/`max-request-size:1100MB` vs `application.yml:29-30`(카탈로그는 28-29, 근사) 공통값 `500MB`/`1200MB` 확인. `ConfigProfileDriftGuardTest` 존재·baseline 통과 |
| TC-CFG-003 | PASS | [정적] `ConfigProfileDriftGuardTest.java:87-105` 라인 정확 일치 — `devProfileHasNoHardcodedIpLiteral` 테스트, baseline 통과 |
| TC-CFG-004 | PASS | [정적] `application-dev.yml:24-26`(카탈로그 23-28, 근사) `enabled: ${DEV_LOGIN_ENABLED:true}`, `application-stg.yml`(카탈로그 51-56 범위 내) `enabled: false`(리터럴) 확인 |
| TC-CFG-005 | PASS | [정적] `deploy/onprem/config/backend/env.template:25` 라인 정확 일치 — `ENV=prd` |
| TC-ACT-001 | PASS | [정적] `application-prd.yml:92-99`(카탈로그 93-100, 근사) — `include: health`만, `show-details: never` 확인 |
| TC-ACT-002 | PASS | [실동작]+[정적] `application.yml:133-140` 라인 정확 일치(`include: ${ACTUATOR_EXPOSE:health,info,metrics,prometheus}`, `show-details: when-authorized`). 실측: 미인증 `GET /actuator/health`→`200 {"status":"UP"}`(상세 없음), 미인증 `/actuator/metrics`→`401`; REVIEWER 인증 `GET /actuator`→4개 링크(health/info/metrics/prometheus) 노출, `GET /actuator/health`→`components` 상세(db/deidentifyHealth/aiServerHealth 등) 전부 노출, `/actuator/metrics`·`/actuator/prometheus`→`200` 확인 |
| TC-HEALTH-001 | PASS | [정적] `DeidentifyHealthIndicator.java:77-83` 라인 정확 일치 |
| TC-HEALTH-002 | PASS | [실동작]+[정적] `DeidentifyHealthIndicator.java:44,70-73,92-112`(실제 92-113, 근사) 라인 일치. 실측: REVIEWER 인증 `GET /actuator/health` 응답에 `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}` — 현재 `KPST_DEID_BASE_URL=http://klid-mock-server:9400` 루트(`/`) 핑 성공을 실제로 확인(mode=kpst, `/health` 아닌 `/` 사용) |
| TC-HEALTH-003 | PASS | [정적] `DeidentifyHealthIndicator.java:84-91` 라인 정확 일치 |
| TC-RES-001 | PASS | [정적] `AiServerClient.java:46-47,63-64` 라인 정확 일치 — `RetryOperator`→`CircuitBreakerOperator` 순서. 동일 패턴이 `segment`(67-75)에도 반복 확인 |
| TC-RES-002 | PASS | [정적] `Resilience4jConfig.java:13-47` 라인 정확 일치 — deid/ai/vlmClient/controlNotify/kpstDeid 5개 CircuitBreaker 빈 확인 |
| TC-RES-003 | PASS | [정적]+테스트커버 `Resilience4jConfig.java:55-69` 라인 정확 일치(aiOnline/portalSam2 Bulkhead). `AutolabelOnlineService.java:499-503`, `PortalSam2Service.java:201-204` 에서 `BulkheadFullException`→`ErrorCode.TOO_MANY_REQUESTS`(HTTP 429) catch 확인. `AutolabelOnlineServiceTest.java:611`(`온라인_AI경로_동시_초과요청시_bulkhead_거부_429_TOO_MANY_REQUESTS`), `PortalSam2ServiceTest.java` 존재·baseline 통과 |
| TC-RES-004 | PASS | [정적] `NonRetryableExternalException.java` 클래스(catalog 라인 23-30은 Javadoc 구간, 실제 class 선언은 29행 — 근소 드리프트하나 내용 일치). `application.yml` 8개 인스턴스(circuitbreaker 5개+retry 3개 확인 범위 내 5곳 실측)에 `ignore-exceptions: NonRetryableExternalException` 등록 확인 |

## 요약

- **49/49 판정 완료** — PASS 48건, PARTIAL 1건(TC-CACHE-005, 카탈로그 문구 노후화)
- 강한 실동작 근거 다수 확보: REVIEWER/WORKER dev-token 발급 → `PUT /v1/manage/configs/*` 4종 시나리오(허용키 아님/범위초과/NaN/WORKER 403) 실제 HTTP 왕복, `GET /actuator/health`(미인증/REVIEWER 인증) 대조로 `show-details=when-authorized` 실증, `deidentifyHealth.mode=kpst` 로 KPST 루트 핑 실제 성공 확인, `docker logs`에서 `QuartzClusteringGuard`·`GenAiIntegrationWiringGuard` 기동 로그 실측
- 근거 `file:line` 드리프트는 다수 케이스에서 1~3줄 수준(Javadoc 확장 원인)으로 경미 — 내용은 전부 일치하여 PASS 판정에 영향 없음. 유의미한 예외는 TC-CACHE-005 하나(검증 메커니즘 자체가 소멸)

## 이슈

### [A-ISSUE-81] TC-CACHE-005 — stream-meta TTL "기동 시 검증됨" 카탈로그 서술이 더 이상 사실이 아님
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그 기대결과는 "구 파일 유예(`stale-grace-minutes`)가 stream-meta TTL(5분)보다 길어야 하며 **기동 시 검증됨**"이라고 서술한다 — 즉 파일 유예 삭제 흐름을 새로 만들 때 유예 하한을 어기면 기동이 막힌다는 안전망이 존재한다는 전제.
- **현재 동작(이슈 내용)**: 그 기동 시 검증을 수행하던 서비스(`ResolutionBackfillService` 및 관련 스윕잡·설정키)가 2026-07-30에 구 스킴 산출물 정정용 1회성 배치 목적 소진으로 전량 제거됐다(`UNCERTAINTIES.md`의 이월 항목 #29~31 "해소/소멸" 참고). `CacheConfig.java:63-64` 자체 주석이 이를 명시한다: "이 하한을 기동 시 검증하던 `ResolutionBackfillService` 는 2026-07-30 제거됐다 — 현재 파일 유예 삭제를 하는 흐름이 없어 검증 대상도 없다." TTL 값(5분) 설정 자체는 정상이지만, "기동 시 검증됨"이라는 안전망은 현재 코드베이스에 존재하지 않는다.
- **재현/확인 경로**: `grep -rn "stale-grace-minutes\|ResolutionBackfillService" backend/src/main/java` → 결과 없음(서비스 삭제 확인). `CacheConfig.java:58-66`의 Javadoc/상수 주석 직접 열람으로 대조 가능.
- **영향**: 기능 결함은 아님(현재 이 TTL 하한을 사용하는 파일 이관 흐름 자체가 없어 실질 위험 0). 다만 카탈로그 문서가 실재하지 않는 안전망을 존재하는 것처럼 서술해, 향후 유사한 파일 이관 흐름을 새로 추가하는 개발자가 "기동 가드가 이미 있다"고 오인할 수 있는 문서 정합성 결함.
- **수정 방향(제안)**: `docs/test-cases/A-auth-common.md`의 TC-CACHE-005 기대결과를 "현재는 검증 흐름이 없음(2026-07-30 `ResolutionBackfillService` 제거로 소멸) — 파일 유예 삭제 흐름을 새로 만들 때 유예 하한 검증을 함께 재도입할 것"으로 정정. 코드 수정은 불필요(현재 미사용 상태가 의도된 설계).
