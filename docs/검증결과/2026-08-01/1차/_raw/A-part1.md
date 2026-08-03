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
