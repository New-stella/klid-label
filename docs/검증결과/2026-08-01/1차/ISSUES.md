# 전 클러스터 이슈 대장

> 회차: 2026-08-01 1차 · 환경: klid-label V146(2026-07-31 빌드) — 재빌드는 Docker Desktop 프록시 장애로 보류(사용자 승인)

## 클러스터 A — 인증/권한/공통 인프라

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


### [A-ISSUE-81] TC-CACHE-005 — stream-meta TTL "기동 시 검증됨" 카탈로그 서술이 더 이상 사실이 아님
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그 기대결과는 "구 파일 유예(`stale-grace-minutes`)가 stream-meta TTL(5분)보다 길어야 하며 **기동 시 검증됨**"이라고 서술한다 — 즉 파일 유예 삭제 흐름을 새로 만들 때 유예 하한을 어기면 기동이 막힌다는 안전망이 존재한다는 전제.
- **현재 동작(이슈 내용)**: 그 기동 시 검증을 수행하던 서비스(`ResolutionBackfillService` 및 관련 스윕잡·설정키)가 2026-07-30에 구 스킴 산출물 정정용 1회성 배치 목적 소진으로 전량 제거됐다(`UNCERTAINTIES.md`의 이월 항목 #29~31 "해소/소멸" 참고). `CacheConfig.java:63-64` 자체 주석이 이를 명시한다: "이 하한을 기동 시 검증하던 `ResolutionBackfillService` 는 2026-07-30 제거됐다 — 현재 파일 유예 삭제를 하는 흐름이 없어 검증 대상도 없다." TTL 값(5분) 설정 자체는 정상이지만, "기동 시 검증됨"이라는 안전망은 현재 코드베이스에 존재하지 않는다.
- **재현/확인 경로**: `grep -rn "stale-grace-minutes\|ResolutionBackfillService" backend/src/main/java` → 결과 없음(서비스 삭제 확인). `CacheConfig.java:58-66`의 Javadoc/상수 주석 직접 열람으로 대조 가능.
- **영향**: 기능 결함은 아님(현재 이 TTL 하한을 사용하는 파일 이관 흐름 자체가 없어 실질 위험 0). 다만 카탈로그 문서가 실재하지 않는 안전망을 존재하는 것처럼 서술해, 향후 유사한 파일 이관 흐름을 새로 추가하는 개발자가 "기동 가드가 이미 있다"고 오인할 수 있는 문서 정합성 결함.
- **수정 방향(제안)**: `docs/test-cases/A-auth-common.md`의 TC-CACHE-005 기대결과를 "현재는 검증 흐름이 없음(2026-07-30 `ResolutionBackfillService` 제거로 소멸) — 파일 유예 삭제 흐름을 새로 만들 때 유예 하한 검증을 함께 재도입할 것"으로 정정. 코드 수정은 불필요(현재 미사용 상태가 의도된 설계).



## 클러스터 B — 배치 파이프라인/비식별화

### [B-ISSUE-01] TC-BATCH-001~025 — B-1 카탈로그 25건 전건이 V147 `LS_DATA_INGEST` 리팩터를 반영하지 않아 근거·기대결과가 무효(7건은 기대값이 오답)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 테스트케이스 카탈로그는 "현재 코드가 유일한 진실원"(B 파일 머리말)이어야 한다. 근거 `file:line` 과 기대결과가 실제 구현과 일치해야 이후 회차가 회귀를 판정할 수 있다.
- **현재 동작(이슈 내용)**: 카탈로그 B-1 은 커밋 `11c3e1b8`(tc-update) 시점의 **구 `MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 스캔** 구현을 기술한다. 그러나 `6c8a5303`(main 병합, V147/V148)이 적재 소스를 `LS_DATA_INGEST` 로 교체했다.
  - `TrainingVideoIngestService.java:70-71` — `ingestRepository.findPendingReadyForPolling(now, PageRequest.of(0, INGEST_SCAN_LIMIT))` (구: `clipMasterRepository.findIngestCandidatesByJobDmndYn`)
  - `TrainingVideoIngestTx.java:217` — `public boolean ingestOne(LsDataIngest candidate)` (구: `ingestOne(MngClipMaster clip, MngClipEvntLst evntLst)`)
  - 카탈로그가 인용한 라인은 전부 어긋난다(예: 근거 `TrainingVideoIngestTx.java:86-89`(blank clipId) → 현재 그 위치는 javadoc, 실제 로직은 `:398-407`)
  - **기대값이 오답이 된 7건**: TC-BATCH-010/011(evntLst 주입·CRT_DT 폴백 → 현재 `EVNT_TYPE_CD` 항상 null, `SHT_DT` 폴백 폐지) · TC-BATCH-013(ms→초 변환 → **변환 폐지**, `TrainingVideoIngestTx.java:506-529`) · TC-BATCH-020/023/024/025(MngClip 쿼리·IN 배치화 → 코드 삭제)
- **재현/확인 경로**: `grep -n "MngClipMaster" backend/src/main/java/kr/co/cudo/authoring/video/service/TrainingVideoIngestService.java` → 0건. `git log --oneline -1 -- backend/src/main/java/kr/co/cudo/authoring/video/service/TrainingVideoIngestTx.java` → `6c8a5303`
- **영향**: 카탈로그 정합성 결함. 이 상태로 다음 회차를 돌리면 정상 동작 7건이 FAIL 로 오판되고(특히 TC-BATCH-013 은 30500→31 을 기대해 실제 `30500` 을 결함으로 신고하게 된다), 신규 도입된 **미도착 backoff·대기 상한·재큐·원자 클레임·경로 allowlist** 등 20건 이상의 신규 표면은 케이스가 아예 없어 미검증으로 남는다.
- **수정 방향(제안)**: B-1 절을 `LS_DATA_INGEST` 기준으로 재작성. 최소 추가 축 — ①`claimForProcessing` 원자 클레임(0/1 분기·동시성) ②미도착 3분기(READY/NOT_ARRIVED/REJECTED) ③대기 상한 초과 종결 + 재큐 가역성 ④backoff(`NEXT_RTRY_DT`) head-of-line blocking 차단 ⑤`SRC_TYPE` allowlist fail-closed ⑥심링크 실경로 루트 검증 ⑦`EVNT_TYPE_CD`/`SHT_DT` 결손 WARN 1회성. 기존 테스트(`TrainingVideoIngestTxTest` 36건 · `LsDataIngestRepositoryIT` 23건 · `TrainingVideoIngestFlowIT` 9건)가 이미 이 축을 덮고 있어 케이스 도출 근거로 쓸 수 있다.


### [B-ISSUE-41] TC-BATCH-057/062 파생 — 브릿지가 배치를 skip 하면 **영구 고아 활성 마킹**이 남아 그 영상이 마킹 409 로 영구 잠긴다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 브릿지가 배치를 트리거하지 않기로 정당하게 판단했다면(검수 소유 상태·이미 큐잉), 그 마킹은 소비될 일이 없으므로 **종결 처리되거나 애초에 생성되지 않아야** 한다. V142 부분 유니크 인덱스의 도입 목적 자체가 *"영상당 마킹이 2건 이상 쌓이면 배치는 최신 1건만 VLM 에 위탁하고 나머지는 영원히 PENDING 인 고아가 된다"* 를 막는 것인데(`V142__add_ls_marking_active_unique.sql` 헤더), 현재 skip 경로는 **바로 그 고아를 제도적으로 생성**한다. 또한 `LS_MARKING` 활성 마킹은 후속 마킹을 409 로 막으므로, 고아가 종결되지 않으면 그 영상은 **다시는 마킹할 수 없다.**
- **현재 동작(이슈 내용)**: 마킹은 커밋된 뒤(`MarkingService.java:227-237`) AFTER_COMMIT 브릿지가 skip 을 결정한다 — 즉 **마킹 저장과 배치 트리거 판단이 분리**돼 있어 skip 이어도 `LS_MARKING` 행은 남는다.
  ```java
  // MarkingBatchBridge.java:146-154
  if (!claimed) {
      log.warn("[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn={} — skipping", rawSn);
      MarkingBatchTriggerReport.skipped(MarkingBatchTriggerReport.REASON_ALREADY_CLAIMED);
      return;   // ← 방금 커밋된 PENDING 마킹을 종결시키지 않는다
  }
  ```
  `PENDING → VLM_REQUESTED/VLM_FAILED` 전이는 **오직 VLM 단계**(`VlmMarkingTxService.persistVlmRequested/markVlmFailedIfRequested`, `VlmResultService:224`)에서만 일어나며, 그 단계는 배치가 돌아야 도달한다. 만료·정리 스윕은 **존재하지 않는다**(`markVlmFailed()` 호출부 전수 = 위 2곳).
  실측(rawSn=9110, stage=`MARKING_READY` · work=`APPROVED`):
  ```
  POST /v1/videos/9110/markings → 201 {"markingSn":25, "batchTriggered":false,
     "batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 …"}
  로그: [MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=9110 — skipping
  DB : ls_marking(25, raw_sn=9110, stts_cd='PENDING')   ← 이후 아무도 전이시키지 않음
  POST /v1/videos/9110/markings (재시도) → 409 "이미 진행 중인 마킹이 있습니다…"
  POST /v1/videos/9110/batch/retry     → 409 "배치가 실패(FAILED)한 영상만 재처리할 수 있으며…"
  ```
  즉 **복구용 API 가 하나도 없다**(재처리는 stage/work 중 하나가 `FAILED` 여야 하는데 여기는 `MARKING_READY`/`APPROVED`).
- **재현/확인 경로**: 도달 가능한 정상 동선이 `MarkingBatchBridge` Javadoc(`:56-60`)에 이미 명시돼 있다 — *"배치가 한 번도 안 돈 채 반려된 영상(stage=MARKING_READY, work=REJECTED — 배정→검수제출→반려로 만들 수 있다)"*. 그 영상에 마킹을 1회 하면 이후 영구 409.
  ```bash
  # 위 실측 그대로 (9110 = MARKING_READY + APPROVED)
  curl -X POST $API/v1/videos/9110/markings -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":100}'   # 201, batchTriggered=false
  curl -X POST $API/v1/videos/9110/markings ... # 409 (영구)
  ```
  ```sql
  -- 현재 고아 실태(운영 데이터에도 이미 존재)
  SELECT m.marking_sn, m.raw_sn, m.stts_cd, r.data_stts_cd stage, s.data_stts_cd work
    FROM ls_marking m JOIN ls_data_raw r ON r.raw_sn=m.raw_sn
    LEFT JOIN ls_raw_data_status s ON s.raw_data_id=m.raw_sn
   WHERE m.stts_cd IN ('PENDING','VLM_REQUESTED');
  -- 실측: 9110/9111/9108/9114 등이 (stage=MARKING_READY, work=APPROVED) 조합으로 PENDING 고착
  ```
- **영향**: 기능 — 해당 영상은 **마킹 재수행 불가(영구 409)**, DB 직접 수정 외 복구 수단 없음. 데이터 정합 — `LS_MARKING` 에 소비되지 않는 활성 행이 무기한 누적되고, V142 가 방지하려던 "고아 활성 마킹"이 정책적으로 재생산된다. 부가로 `APPROVED`(검수 완료) 영상에 새 마킹 행이 생성되는 것 자체가 완료 산출물의 부수 변경이다. (CWE-459 불완전 정리 / CWE-667 계열 자원 고착)
- **수정 방향(제안)**: 택1 —
  ① **입구에서 막기**: `MarkingGuards.requirePreconditions` 에 "작업 상태가 `REVIEW_OWNED_STATUSES` 면 412" 게이트를 추가해 브릿지가 skip 할 마킹은 애초에 만들지 않는다(응답 코드가 201→412 로 바뀌므로 FE 문구 동반 수정). 브릿지의 skip 은 진짜 동시성 경합 전용으로 축소된다.
  ② **출구에서 회수**: 브릿지 skip 분기에서 방금 생성된 마킹(`event.markingSn()` — 이벤트에 이미 실려 있다)을 별도 `REQUIRES_NEW` 트랜잭션으로 `VLM_FAILED`(또는 신설 종결코드 `SKIPPED`)로 내려 활성 집합에서 제거한다. `LsMarking.markVlmFailed()` 는 상태 무관 대입이므로 `PENDING` 한정 가드를 함께 둔다.
  > ①이 근본적이다(무의미한 마킹 자체를 만들지 않음). ②만 하면 "201 인데 마킹이 곧바로 실패 종결"이라는 또 다른 혼란이 남는다.


### [B-ISSUE-101] TC-BATCH-153 / TC-BATCH-154 — 배치 수동 재처리 원자 클레임이 2컬럼 폴백 때문에 상호배제에 실패(동일 rawSn 파이프라인 이중 실행)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `POST /v1/videos/{rawSn}/batch/retry` 는 동시 다중 요청·자동 폴러와 경합해도 **정확히 1건만** FAILED→PROCESSING 을 선점하고 나머지는 409 로 거부되어야 한다(`BatchReprocessService` javadoc "이중 파이프라인 실행 차단", CWE-362). 같은 영상의 파이프라인이 2벌 동시에 돌면 프레임 재추출·오토라벨 중복 INSERT·외부(ai-server/VLM) 중복 위탁·재시도 예산 이중 소모가 발생한다.
- **현재 동작(이슈 내용)**: `BatchTransitionService.tryClaimReprocessFromFailed`(BatchTransitionService.java:335-347)가 RAW 컬럼 클레임이 0행이면 **원인을 구분하지 않고** 작업상태 컬럼 클레임으로 폴백한다.
  ```java
  int rawClaimed = videoRepository.claimReprocessFromFailed(rawSn, FAILED, PROCESSING);
  if (rawClaimed == 1) { return true; }
  int statusClaimed = rawDataStatusRepository.claimReprocessFromFailed(rawSn, FAILED, PROCESSING);
  return statusClaimed == 1;   // ← 0행의 이유가 "남이 방금 선점" 이어도 여기로 내려온다
  ```
  정상 배치 실패는 `LS_DATA_RAW.DATA_STTS_CD` 와 `LS_RAW_DATA_STATUS.DATA_STTS_CD` 를 **함께 FAILED** 로 두므로, 호출자 A 가 RAW 컬럼을, 호출자 B 가 작업상태 컬럼을 각각 선점해 **둘 다 true** 를 받는다.
  실측(2026-08-01 19:17:39, rawSn 9109 · 5요청 동시): 응답 **200 2건 / 409 3건**, 로그가 서로 다른 스레드에서 같은 밀리초에
  ```
  19:17:39.920 [http-nio-8080-exec-7]  [BatchReprocess] manual retry claimed rawSn=9109
  19:17:39.921 [http-nio-8080-exec-11] [BatchReprocess] manual retry claimed rawSn=9109
  19:17:39.925 (exec-7)  [BatchOrchestrator] marking check rawSn=9109 count=1
  19:17:39.925 (exec-11) [BatchOrchestrator] marking check rawSn=9109 count=1
  19:17:39.929 (exec-7)  [BatchRetry] enqueued rawSn=9109 attempt=1 delaySec=60
  19:17:39.929 (exec-11) [BatchRetry] enqueued rawSn=9109 attempt=2 delaySec=120
  ```
  를 남겨 **파이프라인 2벌 동시 실행 + 재시도 카운터 이중 증가**가 확인된다.
- **재현/확인 경로**:
  ```bash
  # 전제: raw 배치상태와 작업상태가 둘 다 FAILED 인 영상 (정상 배치 실패의 기본형)
  psql -c "select r.data_stts_cd, s.data_stts_cd from ls_data_raw r
             left join ls_raw_data_status s on s.raw_data_id=r.raw_sn where r.raw_sn=9107"   # FAILED | FAILED
  for i in 1 2 3 4 5; do (curl -s -o /dev/null -w "%{http_code} " \
      -X POST http://localhost:18081/api/v1/videos/9107/batch/retry -H "Authorization: Bearer $REVIEWER") & done; wait
  # 실측: 409 409 409 200 200   ← 200 이 2건
  docker logs klid-backend | grep -c "manual retry claimed rawSn=9107"   # 2
  ```
  **대조군**(원인 격리): 작업상태 행이 없는 rawSn 11(`FAILED | (null)`)에 동일 시행 → `409 409 409 409 200`, claimed 로그 **1건**. 즉 "두 컬럼이 모두 FAILED" 일 때만 이중 클레임이 성립한다.
- **영향**: 동시성 결함(CWE-362, OWASP API6 — 민감 비즈니스 플로우 무제한 접근). ①동일 영상 프레임 추출/오토라벨의 중복 INSERT·경합 ②ai-server·mock/실 VLM 로의 중복 외부 위탁(비용·PII 전송량 증가) ③재시도 예산(`RTY_NMTM`)이 1회 실행당 2회 소모되어 조기 EXHAUSTED ④두 실행 중 늦게 끝난 쪽이 상태를 덮어써 배치 단계 상태가 비결정. REVIEWER 권한이 필요하지만 **버튼 더블클릭·프론트 재전송만으로도 자연 발생**한다.
- **테스트 사각지대**: `BatchReprocessServiceTest#배치재처리_동시요청시_한쪽만_기동된다`(:81-97)는 `transitionService.tryClaimReprocessFromFailed` 를 **mock 으로 false 고정**해 검증하므로, 실제 2컬럼 폴백 로직을 한 번도 실행하지 않는다. 회귀 가드가 없다.
- **수정 방향(제안)**: (구현하지 않음)
  1. `tryClaimReprocessFromFailed` 에서 RAW 클레임 0행일 때 **폴백 전에 RAW 상태를 재판정**한다 — RAW 가 이미 `PROCESSING`(=남이 방금 선점) 이면 즉시 `false` 를 반환하고, RAW 가 `FAILED` 가 아닌 다른 상태(작업상태만 FAILED 인 예외 형상)일 때만 작업상태 폴백을 허용.
  2. 또는 클레임 자체를 **단일 권위 컬럼**(LS_DATA_RAW)으로 일원화하고, 작업상태만 FAILED 인 형상은 별도 조건부 UPDATE 한 문장(두 테이블을 한 트랜잭션에서 `SELECT … FOR UPDATE` 후 전이)으로 처리.
  3. 회귀 가드로 **실 DB 동시성 IT**(Testcontainers, 두 스레드가 실제 `tryClaimReprocessFromFailed` 를 호출)를 추가해 "성공 1건" 을 단언 — 현행 mock 기반 단위 테스트로는 재발을 못 잡는다.


### [B-ISSUE-02] TC-BATCH-010 — 신규 적재분의 `EVNT_TYPE_CD` 가 항상 null 이라 관제 완료통지 `event_type_cd` 가 전건 null 로 나간다
- **심각도**: MEDIUM (관제 협의 대상 — 저작도구 단독 해소 불가)
- **기대 동작(기대효과)**: 관제 완료통지 계약의 6필드 중 `event_type_cd` 는 값이 있어야 하며(UNCERTAINTIES #3·#27), 작업/검수 목록의 이벤트유형 필터·통계 버킷·export 메타도 이 값에 의존한다.
- **현재 동작(이슈 내용)**: 인입 테이블에 이벤트 **유형** 코드 컬럼이 없다(`EVNT_ID` 는 `ABA_0001` 식별자형).
  ```java
  // TrainingVideoIngestTx.java:123
  private static final String EVNT_TYPE_CD_UNAVAILABLE = null;
  // :334  createFromIngest(vmsClipId, vmsCctvId, EVNT_TYPE_CD_UNAVAILABLE, ...)
  ```
  통지 페이로드는 이 값을 그대로 통과시킨다 — `ControlNotifyPayloadFactory.java:139-141` `toControlEventTypeCd(x) { return x; }`. 즉 **V147 이후 적재된 모든 영상의 완료통지 `event_type_cd` 가 null**. 구 경로에서는 `MNG_CLIP_EVNT_LST.EVNT_TYPE_CD` 로 채워지던 값이다.
  결손이 조용하지 않게 프로세스 1회 WARN 은 남긴다(`:369-376`) — self-fill(대용값 생성)을 하지 않은 점은 **정책상 옳다**.
- **재현/확인 경로**: V147+ 스택에서 `INSERT INTO LS_DATA_INGEST(...)` → 스캔 → `SELECT evnt_type_cd FROM ls_data_raw WHERE vms_clip_id='...'` → null. 이후 검수 승인 시 mock-server 인바운드 `POST /api/data-set/v2/jobs/{id}/notify-completed` 바디의 `event_type_cd` 확인.
- **영향**: 기능(관제 연동 계약 필드 결손) — 관제가 required 로 검증하면 통지 자체가 거부될 수 있다. UNCERTAINTIES #27(코드값 목록 미수령)과 **별개의 상위 문제**(값 자체가 없음).
- **수정 방향(제안)**: 저작도구에서 값을 만들지 말 것(self-fill 금지). ①관제에 `LS_DATA_INGEST` 이벤트유형 컬럼 추가를 계약으로 요청 ②그때까지 통지 페이로드의 null 허용 여부를 관제와 합의하고 결과를 UNCERTAINTIES 에 확정 기록.


### [B-ISSUE-08] TC-BATCH-001~015/021/022 — 실행 스택(V146)이 이 파트의 검증 대상 코드를 포함하지 않아 실동작 검증 불가
- **심각도**: MEDIUM (검증 커버리지 결손 — 코드 결함 아님)
- **기대 동작(기대효과)**: 1차 검증은 "실동작 기준"(VERIFY-PROMPT §1)이며, B-1 은 데이터 입구라 실적재·상태전이·동시성을 실스택에서 봐야 한다.
- **현재 동작(이슈 내용)**: `flyway_schema_history` 최대 버전 **146** / `to_regclass('public.ls_data_ingest')` **NULL** / 컨테이너 로그가 구 구현 문구(`skip clip with blank vmsCctvId evntId=…`) 출력. 검증 대상 코드는 V147(`V147__create_ls_data_ingest.sql`)·V148 을 요구한다. 재빌드는 Docker Desktop 프록시 장애로 사용자 지시에 따라 보류(`stack-bringup.md` 하단).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT MAX(version) FROM flyway_schema_history;"` → `146`
- **영향**: B-1 25건 중 14건이 BLOCKED. 특히 **동시 클레임 race·미도착 backoff head-of-line blocking·재큐 가역성**은 단위/IT 로만 덮여 있고 2노드 실환경 검증이 미수행이다.
- **필요 환경**: V147+ 를 포함한 backend 이미지 재빌드(Docker Desktop 프록시 복구 후) + `TRAINING_SCAN_ENABLED=true` 로 뜬 프로파일(local 은 `application-local.yml:66` 에서 비활성) + `LS_DATA_INGEST` 에 관제 역할로 INSERT 할 수 있는 시드. 재검증 시 ①정상 1건 ②파일 미도착 1건(backoff·상한) ③허용 루트 밖 경로 1건 ④동일 `VMS_CLIP_ID` 중복 1건 ⑤`FAILED` 재큐 1건의 5시나리오를 권장.


### [B-ISSUE-22] TC-BATCH-045 — 동일 rawSn 에 대한 `BatchOrchestrator.process()` 동시 중복 실행이 어디에서도 차단되지 않는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `BatchOrchestrator` 클래스 Javadoc 이 **"영상 단위 직렬 호출 보장 — `process(Long)` 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다"**(`BatchOrchestrator.java:50-51`)고 단언한다. 파이프라인은 프레임 추출·YOLO/SAM2 라벨 적재 등 **비멱등 INSERT** 를 수행하므로, 같은 rawSn 에 두 실행이 겹치면 프레임/AUTO 라벨이 중복 적재되고 상태 전이가 서로를 덮어쓴다. 2노드 Active-Active 형상에서는 JVM 락이 방어가 되지 않아 DB 수준의 클레임이 필요하다.
- **현재 동작(이슈 내용)**: `process()` 진입부에는 **검수 소유 상태 가드 하나뿐**이고, `PROCESSING` 은 `REVIEW_OWNED_STATUSES` 에 없으므로 이미 실행 중인 영상도 통과한다.
  ```java
  // BatchOrchestrator.java:112-115
  if (transitionService.markRawDataProcessingBlocked(rawSn)) {   // 검수 소유 4상태만 차단
      log.warn("[BatchOrchestrator] skipped — review-owned work status rawSn={}", rawSn);
      return BatchStage.SKIPPED;
  }
  ```
  ```java
  // BatchTransitionService.java:77-81  (PROCESSING·BATCH_QUEUED·ASSIGNED·FAILED 는 차단 대상 아님)
  public static final Set<String> REVIEW_OWNED_STATUSES = Set.of(
          STTS_PENDING, STTS_IN_REVIEW, STTS_APPROVED, STTS_REJECTED);
  ```
  개별 진입점의 클레임(`tryClaimBatchQueued` / 재시도 큐 `PENDING→RETRYING` CAS / `tryClaimReprocessFromFailed`)은 **서로 다른 락**이라 진입점이 다르면 교차 방어가 되지 않고, dev 트리거의 `PROCESSING` 사전 검사(`BatchDevTriggerController.java:84-87`)는 read-then-act 라 TOCTOU 다.

  **라이브 실측(동일 진입점 4-way 동시 발사)**:
  ```
  # 작업상태 row 부재(파생 RAW 형상)
  $ for i in 1 2 3 4; do (curl -sX POST ".../v1/dev/batch/trigger?rawSn=9203" -H "Auth...") & done
  → 4건 모두 200 / finalStage=FAILED  (409·SKIPPED 0건)
  # 작업상태 row 존재(ASSIGNED)
  $ for i in 1 2 3 4; do (curl -sX POST ".../v1/dev/batch/trigger?rawSn=9202" ...) & done
  → 4건 모두 200,  ls_raw_data_status.ver  2 → 10 (=4×PROCESSING + 4×FAILED, 8회 UPDATE)
  → backend 로그 "[BatchOrchestrator] marking check rawSn=9202" 4줄 = 파이프라인이 4회 병렬 실행됨
  ```
  이번 검체는 마킹이 없어 1단계에서 실패해 부수효과가 없었지만, **마킹이 있는 영상이면 프레임 추출·오토라벨 적재가 4중으로 수행**된다.
- **재현/확인 경로**: 위 셸 스니펫 그대로(마킹이 있는 rawSn 을 쓰면 `SELECT count(*) FROM ls_data_src WHERE raw_sn=?` 가 프레임 수의 배수로 늘어난다).
- **영향**: 데이터 정합(프레임·AUTO 라벨 중복 적재, 상태 전이 경합) · CWE-362(Race Condition) · CWE-1223(비원자 check-then-act). 2노드 Active-Active + Quartz 클러스터링 형상에서 **Quartz 는 트리거 중복 발화만 막고 잡 내부 레이스는 막지 않는다**(`CLAUDE.md` 명시)는 전제와 정확히 맞물리는 미방어 구간.
- **수정 방향(제안)**: `process()` 진입부의 `markRawDataProcessingBlocked` 를 **"진입 클레임"** 으로 승격하는 방향. 예 — `LS_DATA_RAW.DATA_STTS_CD` 를 `PROCESSING` 이 **아닐 때만** `PROCESSING` 으로 바꾸는 조건부 UPDATE(영향 행수 1 인 호출만 진행, 0 이면 `SKIPPED`)를 추가하고, 정상 종료 3경로(COMPLETED/FAILED/예외)에서 반드시 해제되도록 보장. 클레임 도입 시 **stale PROCESSING 회수 스윕**(`BatchRetryStaleReclaimSweeper` 와 동일 패턴)이 함께 필요하다 — 노드 사멸 시 영구 고착 위험. ⚠ 구현은 하지 않음.


### [B-ISSUE-42] TC-BATCH-078/096 파생 — AUTO 마킹의 **생성 marks 개수에 상한이 없다**(MANUAL 은 20,000 캡, CWE-770)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 단일 요청이 만들어내는 산출물 크기는 유계여야 한다. MANUAL 경로는 이미 그렇게 설계돼 있다 — `MarkingRequest.marks` 에 `@Size(max = 20000, message = "한 번에 처리 가능한 마킹 수 초과 (최대 20000)")` 가 **CWE-770 방어 목적으로 명시**돼 있다. AUTO 는 같은 산출물(`MARK_CN`)을 서버가 생성하므로 동일 상한이 걸려야 한다.
- **현재 동작(이슈 내용)**: AUTO 는 `intervalFrames >= 1` **하한만** 검증하고 결과 개수를 보지 않는다.
  ```java
  // MarkingService.java:196-198, 276-282
  if (req.intervalFrames() == null || req.intervalFrames() <= 0) { throw INVALID_INPUT; }
  ...
  int totalFrames = (int) Math.round(durationSec * fps);
  for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) { marks.add(...); }
  ```
  실측(DB 기존 행, rawSn=9111 · `VDO_LEN_SEC=1200` · fps 30 · `intervalFrames=1`):
  ```
  marking_sn=22 → mark_cn 길이 1,464,891 byte, marks 36,000건 (단일 TEXT 컬럼)
  ```
  1시간(3,600s) 30fps 영상이면 108,000건 ≈ 4.4 MB 가 한 요청으로 생성된다. `@Size` 상한(20,000)의 5배를 AUTO 로 우회할 수 있다.
- **재현/확인 경로**:
  ```bash
  # 긴 영상(예: 1200s)에 intervalFrames=1
  curl -X POST $API/v1/videos/9111/markings -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":1}'
  ```
  ```sql
  SELECT marking_sn, frme_intv_nocs, length(mark_cn),
         (length(mark_cn)-length(replace(mark_cn,'frameIndex','')))/10 AS mark_cnt
    FROM ls_marking WHERE raw_sn = 9111;   -- 실측 1464891 / 36000
  ```
- **영향**: 가용성/자원(CWE-770, OWASP API4). ①`ArrayList` 36k~108k 엔트리 + Jackson 직렬화가 요청 스레드에서 수행 ②`MARK_CN` 단일 TEXT 에 MB 급 적재 ③**증폭이 배치까지 전파** — `FfmpegFrameExtractor` 가 mark 당 1프레임을 뽑고(`mark-based extracted rawSn=30 frames=5` 로 1:1 확인) VLM 위탁 페이로드에도 marks 가 실린다. 즉 마킹 1회로 수만 회 ffmpeg seek + 수만 프레임 파일이 생성된다. ④`AsyncConfig.java:38-44` 의 `batchAsyncExecutor` 는 core 2/max 4/queue 50 에 **`CallerRunsPolicy`** 라, 큐 포화 시 이 대형 파이프라인이 **AFTER_COMMIT 리스너 안(=Tomcat 요청 스레드)에서 동기 실행**되어 마킹 API 응답시간이 무한정 늘어난다.
- **수정 방향(제안)**: `MarkingService.generateAutoMarks` 에 `MarkingRequest.marks` 의 `@Size` 와 **동일 상수**(20,000)를 공유 상수로 뽑아 상한 검증을 추가한다 — 산출 예상 개수 `ceil(totalFrames / intervalFrames)` 를 루프 **이전에** 계산해 초과 시 `INVALID_INPUT`(권장 최소 `intervalFrames` 를 메시지에 안내). 겸사겸사 B-ISSUE-23(intervalFrames 상한 미검증)도 같은 지점에서 "결과 0/1건 퇴화" 경고와 함께 정리 가능하다.


### [B-ISSUE-61] TC-VLM-032 — 신고 구간 배치 재처리가 VLM 은 보류하지만 FRAME_EXTRACT 에서 실패해 배치 FAILED + 재시도 예산을 소진한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 비식별 누락 신고는 **정책적 차단**이므로 "실패가 아니라 보류"여야 한다(`CLAUDE.md` — "예외로 실패시키면 ①정책적 차단이 장애로 오분류 ②BatchRetryQueue 가 반드시 다시 막힐 재시도로 시도 상한을 소진 ③작업 상태가 FAILED 로 내려가 라벨링·검수 동선이 끊긴다"). TC-VLM-032 도 "예외 미발생 → 배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진"을 요구한다.
- **현재 동작(이슈 내용)**: VLM 스텝은 규약대로 보류(SKIPPED, 예외 0)한다. 그러나 **바로 다음 단계인 `FfmpegFrameExtractor` 가 동일한 `'F'` 상태를 예외로 처리**한다.
  ```java
  // backend/.../batch/step/FfmpegFrameExtractor.java:185-188
  if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {          // DEIDENTIFIED = "Y" → 'F' 도 실패
      throw new CustomException(ErrorCode.INVALID_INPUT,
              "비식별이 완료되지 않은 영상입니다 rawSn=" + raw.getRawSn());
  }
  ```
  실측(rawSn=40, 마킹 1건 보유, `DE_IDENT_YN='F'`):
  ```
  [Batch][VlmTimeseries] withheld — deident report open rawSn=40      ← 보류(정상)
  [BatchRetry] enqueued rawSn=40 attempt=1 delaySec=60                ← 재시도 큐 등록(기대 위반)
  [BatchOrchestrator] failed rawSn=40 willRetry=true cause=CustomException
  ls_batch_proc_log: FRAME_EXTRACT / FAILED / "비식별이 완료되지 않은 영상입니다 rawSn=40"
  ls_data_raw.data_stts_cd: MARKING_READY → FAILED
  ```
  `authoring.batch.retry.max-attempts:3` + 지수 백오프(60/120/240초)이므로 **신고가 약 7분 이상 열려 있으면 재시도 예산이 전량 소진(EXHAUSTED)** 되고, 해소(resolve) 시 자동 복구 트리거는 VLM 재개(`DeidentGateReopenedEvent`)뿐이라 프레임추출·오토라벨은 되살아나지 않는다(수동 `POST /v1/videos/{rawSn}/batch/retry` 필요 — `BatchReprocessService:84 retryQueue.clearIfIdle`).
- **재현/확인 경로**:
  ```bash
  # 마킹을 보유한 MARKING_READY 영상에 신고를 걸고 재처리
  curl -X POST .../v1/videos/{rawSn}/deident-report -d '{"reason":"..."}'
  curl -X POST ".../v1/dev/batch/trigger?rawSn={rawSn}"   # 또는 POST /v1/videos/{rawSn}/batch/retry
  psql -c "select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn={rawSn};"
  psql -c "select * from ls_bat_rty_wtng where raw_sn={rawSn};"
  ```
- **영향**: 기능/운영. ①신고 구간에 자동 재시도가 반드시 실패하며 예산을 태운다 ②영상이 `FAILED` 로 표시돼 운영자가 장애로 오인한다 ③해소 후 프레임추출~오토라벨이 자동 복구되지 않아 수동 개입이 필요하다. 보안 유출은 없다(외부 전송은 정상 차단됨).
- **수정 방향(제안)**: `FfmpegFrameExtractor` 의 비식별 선행 가드를 **`'N'`(미수행)만 실패**로 두고 `'F'`(신고)는 VLM 과 동일하게 **보류(SKIPPED + 사유 적재)** 로 분기하거나, `BatchOrchestrator`/`BatchPipelineConfig` 의 `isEnabled(ctx)` 로 신고 구간에서는 post-marking 단계를 통째로 건너뛰고 재시도 큐에 넣지 않게 한다. 어느 쪽이든 해소 시 `DeidentGateReopenedEvent` 소비자를 늘려 프레임추출 이후 단계도 재개돼야 한다(현재는 VLM 재개만 배선).


### [B-ISSUE-65] B-7 섹션 전체가 논블로킹 전환(`862ca6d8`) 이전 코드 기준 — 근거 드리프트 34/36 + 신규 기계 케이스 부재
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그의 `file:line` 근거로 다음 회차가 대조 가능해야 하고, 실제 운영 중인 코드 경로에 케이스가 존재해야 한다.
- **현재 동작(이슈 내용)**: B-7 은 `HEAD 11c3e1b8` 기준으로 작성됐는데, 같은 날(2026-07-30) 병합된 `862ca6d8 refactor(integration): 외부연동 제출 3곳 논블로킹화` 가 이 파일들을 크게 재작성했다. 그 결과 **36건 중 34건의 근거 라인이 어긋난다**(위 표 비고 열). 더해 신설된 아래 기계에 대응하는 케이스가 **0건**이다:
  - `VlmSubmitOutcomeRecorder`(ACK 기록 + 원장 `ISSUED→ACCEPTED` 전이 + 상태 강등 금지) — `batch/step/VlmSubmitOutcomeRecorder.java`
  - `VlmSubmitPendingSweeper`(미결 회수, `ackWindowMin=30` / `callbackWindowMin=360`, `max-reclaims=3`) — `batch/vlm/`
  - 신규 재개 사유 3종 `SKIP_REASON_SUBMIT_FAILED` / `SKIP_REASON_ACK_MISSING` / `SKIP_REASON_CALLBACK_MISSING` 과 `RESUMABLE_SKIP_REASONS` 목록
  - 원장 3상태(`ISSUED`/`ACCEPTED`/`PROCESSED`)와 "비-PROCESSED 는 전부 발급됨으로 매핑" 불변식
  - `SubmitSignalDispatch`(전용 풀 거부 시 이벤트 루프 오염 차단)
  실동작 중 이 경로가 살아있음을 확인했다 — 로그 `[Vlm][Reclaim] pending VLM submit reclaimed=2 ackWindowMin=30 callbackWindowMin=360` 및 `ls_batch_proc_log` 의 `VLM/SKIPPED/"VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개"` 2행(rawSn=28).
- **재현/확인 경로**: `git log --oneline -- backend/.../VlmTimeseriesStep.java` → `862ca6d8`(2026-07-30); `ls backend/src/test/java/kr/co/cudo/authoring/batch/vlm/` → `VlmSubmitAckWindowIT`·`VlmSubmitPendingSweeperTest`·`VlmSubmitReclaimAtomicClaimIT`(전부 baseline PASS인데 카탈로그 케이스가 없음).
- **영향**: 카탈로그 정합 + 커버리지 갭. 논블로킹 전환의 핵심 회수 경로가 검증 카탈로그에서 누락돼 있다.
- **수정 방향(제안)**: B-7 근거 라인을 `56d30478` 기준으로 일괄 재산출하고, 위 5개 기계에 대한 신규 케이스(TC-VLM-041~ )를 추가한다. 기존 테스트 자산(`batch/vlm/*`·`VlmTimeseriesStepNonBlockingTest`·`VlmSubmitOutcomeRecorderTest`)과 1:1 매핑 가능하다.


### [B-ISSUE-66] `VlmTimeseriesStep.run` 의 `readOnly=true` 때문에 재개 경로에서 보류 감사 기록이 `cannot execute INSERT in a read-only transaction` 으로 실패한다 (실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개 러너가 호출한 위탁이 **다시 보류**(게이트가 아직 닫혀 있음)되거나 비활성으로 끝나면 그 사실이 `LS_BATCH_PROC_LOG` 에 사유와 함께 남아야 한다(B-ISSUE-24 규약 — "재처리 대상 식별이 애플리케이션 로그 보존기간에 종속되면 운영에서 복구 불가"). 코드 주석도 `VlmWithheldResumeRunner:45` 에서 "게이트가 아직 닫혀 있으면 스스로 다시 보류된다" 를 전제한다.
- **현재 동작(이슈 내용)**: `run` 은 `@Transactional(REQUIRES_NEW, readOnly = true)`(`VlmTimeseriesStep.java:236`)이고 그 안에서 호출되는 `BatchStatusService.recordVlmSkipped` 는 `REQUIRED`(`:58-62`)라 **읽기전용 트랜잭션에 참여**한다. Spring/Hibernate 가 JDBC 커넥션을 read-only 로 설정하므로 PostgreSQL 이 INSERT 를 거부한다. 파이프라인 경로(`execute` → 자기호출, `execute` 는 readOnly 아님)는 영향이 없지만(rawSn=28/40 감사 행 정상 커밋 확인), **`VlmWithheldResumeRunner` 는 프록시 경유로 `run` 을 직접 호출**(`VlmWithheldResumeRunner.java:69`)하므로 이 경로에서만 읽기전용 경계가 실제로 발효된다.
  ```java
  // VlmTimeseriesStep.java:236
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public VlmTimeseriesResponse run(Long rawSn) { return doSubmit(rawSn, null); }
  // → doSubmit:271(DISABLED), :305(DEIDENT_REPORT) 에서 batchStatusService.recordVlmSkipped(...) (REQUIRED, INSERT)
  ```
  **실동작 재현 로그**(rawSn=39, `DE_IDENT_YN='F'` 유지 상태에서 미결 스위퍼가 재개 트리거):
  ```
  19:31:22.196 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=39 stage=VLM      ← 스위퍼(REQUIRES_NEW) 는 성공
  19:31:22.201 [batch-async-1] [VlmResume] deident report resolved — resuming withheld VLM submit rawSn=39
  19:31:22.202 [batch-async-1] [Batch][VlmTimeseries] withheld — deident report open rawSn=39
  19:31:22.203 [batch-async-1] ERROR SqlExceptionHelper - ERROR: cannot execute INSERT in a read-only transaction
  19:31:22.204 [batch-async-1] WARN  [VlmResume] withheld VLM resume failed rawSn=39 cause=JpaSystemException
  ```
  결과적으로 **재보류 감사 행이 적재되지 않았다**(`ls_batch_proc_log` where data_raw_sn=39 → 스위퍼가 쓴 `ACK_MISSING` 1행만 존재, `DEIDENT_REPORT` 행 0건).
- **재현/확인 경로**:
  ```sql
  -- 게이트가 닫힌(F) 영상 + 시계열 메타 0건 + 재개 대상 SKIPPED 행이 있는 상태를 만든 뒤 재개를 트리거
  insert into ls_data_raw (...) values (..., de_ident_yn='F', ...);          -- rawSn=N
  insert into ls_webhook_idempotency (idmp_key,chnl_cd,stts_cd,reg_dt,mdfcn_dt,raw_sn)
         values ('QA-RO','VLM','ISSUED', now(), now(), N);                    -- ACK 창(30분) 경과로 보이게
  -- VlmSubmitPendingSweeper tick(기본 15분) 또는 POST /v1/deident-reports/{n}/resolve 로 재개 트리거
  select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn = N;
  ```
- **영향**: 데이터정합/운영. ①재보류 사실이 DB 에 남지 않아 B-ISSUE-24 가 세운 "로그 보존기간 비종속" 보장이 이 경로에서 깨진다 ②재개가 `JpaSystemException` 으로 중단되며 ERROR 스택이 남아 실장애와 구분이 어렵다 ③`vlm.client.enabled=false` 인 dev/stg/prd 기본 형상에서는 **모든** 재개(`run` 분기)가 이 예외로 끝난다 — 그 경우 `SKIP_REASON_DISABLED` 행도 남지 않는다. 다만 기존 보류 기록과 "메타 0건" 조건이 남아 다음 회수 때 재시도되므로 영구 정체는 아니다(`max-reclaims=3` 소진 시까지).
- **수정 방향(제안)**: ①`run` 의 `readOnly=true` 를 제거해 `runWithMarking`(쓰기 가능)과 경계 속성을 일치시키거나, ②`doSubmit` 의 `recordVlmSkipped` 호출을 이미 존재하는 `recordVlmSkippedInNewTx`(`BatchStatusService:78-82`, REQUIRES_NEW)로 교체한다. ②가 비동기 완료 핸들러·`ledger.recordIssued` 와 규약이 같아 일관적이다. 회귀 가드로 "프록시 경유 `run` 호출 시 SKIPPED 감사 행이 실제로 커밋된다" 는 IT 를 추가할 것(현행 `VlmTimeseriesStepTest` 는 mock 기반이라 이 경계를 잡지 못한다).

---

## 5. 근거 드리프트 목록(요약 — 상세는 §2 비고)

| 케이스 | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-VLM-001/002 | `VlmTimeseriesStep.java:135-142` | `:218-227` |
| TC-VLM-003 | `:182-186` · `BatchStatusService:57-64` | `:269-273` · `:58-62` |
| TC-VLM-004/005 | `:174-176` · `:189-191` | `:261-263` · `:276-278` |
| TC-VLM-006 | `:280-290` | `:390-400` |
| TC-VLM-007/008 | `:231-238` · `:232` | `:318-325` · `:319` |
| TC-VLM-009/010 | `:247-250` · `:78,246` | 소멸(§4 B-ISSUE-62/63) |
| TC-VLM-011/012 | `:262,300-308` | `:337` · `VlmMarkingTxService:47-60` |
| TC-VLM-013 | `:311-319` | `:402-411` |
| TC-VLM-018 | `VlmResultService:168-186` | `:210-232` |
| TC-VLM-020 | `VlmResultService:192-204` | `:238-250` |
| TC-VLM-022/023 | `:156` · `:160` | `:158-161` · `:164` |
| TC-VLM-031/032/033 | `:216-220,222` · `:206-220` · `:215-216` | `:303-307` |
| TC-VLM-034/035/037 | Runner `:54-70` · `:79-93` · `:71-76` | `:57-72` · `:88-101` · `:73-78` |
| TC-VLM-036 | Runner `:80-84` · `BatchStatusService:74-78` | `:89-90` · `:114-119`(API 교체) |
| TC-VLM-038/039 | `BatchStatusService:109-116` · `:104-108` | `:149-156` · `:78-82` |
| TC-VLM-040 | `:133-142,150-168` | `:218-227,236-255` |

> TC-VLM-014~017·019·021·024·025·030 의 `VlmResultService` 근거는 대체로 유효(±2줄 이내).

---

## 6. 남긴 테스트 데이터(정리 참고)

| 대상 | 키 |
|---|---|
| `ls_data_raw` | 28(QA0801-B7-01) · 35(QA0801-B7-02) · 37(QA0801-B7-NODEID) · 39(QA0801-B7-RONLY) · 40(QA0801-B7-03) |
| `ls_webhook_idempotency` | `QA-B7-DUPKEY` · `QA-B7-NORAW` · `QA-B7-UPSERT` · `QA-B7-FAILED` · `QA-B7-ACCEPTED` · `QA-B7-RACE` · `QA-B7-RO` |
| `ls_deident_report` | rprtSn 8·10(rawSn=28, RESOLVED) · 14(rawSn=40, OPEN) |
| 기타 | rawSn=28 비식별 산출물 mtime 을 `touch` 로 갱신(외부 재비식별 시뮬레이션 — resolve 게이트 통과 목적) |

> ⚠ 위 데이터는 검증 재현을 위해 남겨 두었다. rawSn=26 및 기존 데이터는 일절 변경하지 않았다.


### [B-ISSUE-102] TC-DEID-044 / TC-DEID-045 — resolve 산출물 검증의 60초 스큐 관용이 "재비식별하지 않은 옛 산출물"을 통과시킨다(신고 게이트 일괄 해제)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `POST /v1/deident-reports/{rprtSn}/resolve` 는 **신고 이후 실제로 재비식별된** 산출물이 있을 때만 통과해야 한다. 이 지점의 통과는 `DE_IDNTF_YN` 을 `'F'→'Y'` 로 되돌려 라벨 조회 412·프레임 이미지 412·영상 스트리밍 404·export 보류 게이트를 **한꺼번에 여는** 단일 관문이므로, 위장 산출물 통과 = 마스킹 실패 픽셀 재노출이다(CWE-359, `verifyDeidentArtifact` javadoc 명시 목적).
- **현재 동작(이슈 내용)**: 시간 판정 (2)번 조건이 신고시각에서 **60초를 빼고** 비교한다.
  ```java
  private static final long CLOCK_SKEW_TOLERANCE_SECONDS = 60L;
  ...
  LocalDateTime mtime = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
  fileAfterReport = mtime.isAfter(reportTime.minusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS));   // DeidentReportService.java:567-573
  ```
  따라서 **`신고시각-60s < mtime ≤ 신고시각`** 인 파일, 즉 *신고를 유발한 바로 그 비식별본* 도 "신고 이후 교체"로 인정된다. 실측(rawSn 6 / rprtSn 7): 파일 mtime `2026-07-31 03:23:21.127`, 신고 `dclr_dt=2026-07-31 03:23:21.165`(mtime 이 신고보다 **38ms 이르다**), 최신 SUCCESS procLog `rspns_dt=03:02:21`(신고 이전) — 두 조건 모두 "신고 이후 재비식별"이 아님에도 **resolve 200** 으로 통과했고 `de_ident_yn` 이 `'F'→'Y'`, 작업락 해제, `report_stts_cd=RESOLVED` 가 되었다.
  또한 `reportDt` 는 `LsDeidentReport.createReport` 의 `LocalDateTime.now()`(앱 JVM 시계)이고 mtime 도 같은 JVM 의 `ZoneId.systemDefault()` 로 환산되므로, **동일 호스트 배치에서는 보정할 스큐가 사실상 없다** — 관용치가 순수 손실로 남는다.
- **재현/확인 경로**:
  ```bash
  # 1) 비식별 산출물이 막 기록된 영상에서(=deid 파일 mtime ≈ now) 60초 안에 신고
  curl -X POST .../v1/videos/{rawSn}/deident-report -d '{"reason":"leftover face"}'
  # 2) 아무런 외부 재비식별 없이 즉시 resolve
  curl -X POST .../v1/deident-reports/{rprtSn}/resolve      # → 200
  psql -c "select de_ident_yn from ls_data_raw where raw_sn={rawSn}"   # → Y (게이트 전부 재개방)
  ```
  실증 데이터: `select r.dclr_dt from ls_deident_report r where deident_report_sn=7` = `03:23:21.165` vs `stat -c %y {deid파일}` = `2026-07-30 18:23:21.127 +0000`(=KST 03:23:21.127).
- **영향**: PII 재노출 창(CWE-359 / CWE-367). 특히 **마킹 화면 동선**이 위험하다 — 선두 비식별 완료(`MARKING_READY`) 직후 마킹을 시작해 60초 안에 누락을 신고하는 것은 정상 동선이고, 그 상태에서 resolve 를 누르면 재비식별 없이 신고가 닫히며 라벨·프레임·스트리밍·export 게이트가 모두 열린다. WORKER(본인 배정)도 resolve 권한이 있어 오·남용 표면이 넓다.
- **수정 방향(제안)**: (구현하지 않음)
  1. mtime 비교의 기준을 **완화 방향이 아니라 강화 방향**으로 바꾼다 — `mtime > reportTime` 엄격 비교로 두고, 스큐가 실제로 문제인 환경(NAS 가 다른 호스트 시계로 mtime 을 찍는 경우)만 별도 설정값으로 opt-in.
  2. 또는 "재비식별 사실"을 시각이 아니라 **콘텐츠 동일성**으로 판정한다 — 신고 시점에 그때의 비식별본 해시/크기/mtime 을 `LS_DEIDENT_REPORT` 에 스냅샷 기록하고, resolve 때 **스냅샷과 달라졌는지**를 본다(시계 의존 제거, 60초 창 소멸).
  3. 최소한 관용치를 `CLOCK_SKEW_TOLERANCE_SECONDS` 설정값으로 외부화하고 기본을 0 으로 낮춘 뒤, 실제 스큐가 관측되는 환경만 올린다.
  4. 회귀 가드: `DeidentReportServiceTest#신고이전_비식별본만_존재시_resolve_거부된다` 는 mtime 을 충분히 과거로 두어 통과하고 있다 — **경계값(`reportTime - 59s`) 케이스**를 추가해야 이 창이 드러난다.

---

## 6. 근거 드리프트 (카탈로그 정합성)

| 케이스 | 카탈로그 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-BATCH-183 | `VlmTimeseriesStep.java:128-135` | `VlmTimeseriesStep.java:218-220` | execute 위치 이동(약 90줄) — 실질 드리프트 |
| TC-BATCH-186 | `VlmTimeseriesStep.java:150-166` | `VlmTimeseriesStep.java:236`(run) · `252`(runWithMarking) | 실질 드리프트 |
| TC-BATCH-154 | `BatchTransitionService.java:334-362` | `334-347` | 범위 과대(포함은 함) |
| TC-BATCH-188 | `MarkingLoadStep.java:50-58` | `49-58` | 1줄 |
| TC-BATCH-167 | `BatchRetryStaleReclaimSweeper.java:42-50,84,102-110` | javadoc `42-52` · `@Value 84` · scheduler `106-111` | 소폭 |
| TC-DEID-057 | `DeidentReportService.java:480-491` | 발행문 `491`(javadoc 464-486) | 소폭 |
| TC-DEID-047 | `DeidentReportService.java:443-470` | `443-462` | 범위 과대 |
| TC-DEID-053 | `DeidentReportService.java:182-183,295-310` | `182-183` · `295-306` | 범위 과대 |
| TC-DEID-054 | `DeidentReportService.java:185-186,329-340` | `185-186` · `329-337` | 범위 과대 |

그 외 B-11/B-12/B-15 근거는 실제 위치와 일치.

---

## 7. 관측된 부수 사실 (결함 판정 아님 · 기록용)

1. **local 프로파일은 배치 Quartz 잡을 등록하지 않는다** — `application-local.yml:61-62` `authoring.batch.enabled: false`. `qrtz_triggers` 실측 3건(kpstDeidentPoll · datasetExportFailureRecovery · datasetExportPendingSweep)만 존재하고 `batchRetryTrigger` · `controlTrainingVideoScanTrigger` 는 부재. 그 결과 `ls_bat_rty_wtng` 의 도래한 PENDING 행(2026-07-31 등록분 포함)이 소진되지 않고 잔존한다. **의도된 로컬 설정**이며 TC-BATCH-159 BLOCKED 사유다.
2. **stale 회수 스윕은 배치 토글과 독립적으로 살아 있다** — 위 1번 상황에서도 `batch-retry-stale-reclaim` 데몬이 15분 주기로 tick 하며 회수를 수행했다(TC-BATCH-167 의 설계 의도 실증).
3. **`de_ident_yn` 컬럼 물리명 불일치** — 문서·주석은 `DE_IDNTF_YN` 을 쓰지만 실제 DDL/DB 컬럼은 **`DE_IDENT_YN`**(`LsDataRaw.java:118` `@Column(name = "DE_IDENT_YN")`). 코드는 일관되게 이 이름으로 매핑하므로 동작 결함은 없으나, 표준용어 관점(비식별=DE_IDNTF)과 문서 표기가 어긋나 SQL 직접 조회 시 혼동을 유발한다(본 검증에서도 최초 쿼리가 실패했다).
4. **`reExport=false`** — 신고 시 발행된 `TASK_MODIFIED`(META_UPDATED)는 `reExport=false` 로 전송된다. 신고 구간에는 export 가 게이트로 보류되므로 정합적이며, resolve 시 `DeidentReportResolvedEvent` → 재산출(`version=2`)로 이어지는 것을 실측했다(TC-DEID-058).
5. **개인정보 3필드 리셋의 벌크 UPDATE 영향행 ≠ 감사 행수** — rawSn 27 에서 `privacyReset=5`(영상 전 프레임 UPDATE) / `privacyResetAudited=3`(실제 값 보유 프레임). 감사 대상 선정이 "리셋 직전 값 보유 프레임" 이라는 계약과 일치한다(TC-DEID-052).


### [B-ISSUE-121] TC-DEID-063 — 재위탁 시 기존 비식별 산출물을 **선삭제**해, 위탁 실패 시 검수완료 영상의 비식별본이 영구 소실된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `cleanExportDir` 의 목적은 "이번 회차 산출물만 남겨 폴백 스캔의 stale 오회수·다중파일 모호 실패를 막는 것"이다. 그 목적은 **새 산출물이 확보된 뒤**에도 달성 가능하다. 반면 `CLAUDE.md`(★2026-07-28 확정)는 검수 완료·통지된 영상에 대해 "어떤 사유로도 뷰에서 감추거나 경로를 비우지 않는다 — 비식별 누락 신고(`DE_IDNTF_YN='F'`) 구간에도 관제 접근을 차단하지 않는다"를 구속 규칙으로 둔다. 즉 신고 구간에도 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 이 가리키는 파일이 **실재해야** 그 보장이 성립한다.
- **현재 동작(이슈 내용)**: 위탁 준비 단계에서 export 디렉터리 바로 아래 정규 파일을 **무조건 먼저 삭제**한다. co-locate 전략에서 이 디렉터리는 **직전 회차 산출물이 놓인 바로 그 디렉터리**다(라이브 실측: raw 17 → `/app/storage/raw/seed/17/deid/clip-9102-mask.mp4`, 재위탁 시 `deidVideoDir(17, …)` 가 동일 경로를 반환).
  ```java
  // KpstDeidentService.java:345 (buildProjectRequest, createProject 호출 전)
  cleanExportDir(exportDir, rawSn, rawFilePathNm);
  // KpstDeidentService.java:508-521
  try (Stream<Path> entries = Files.list(normalized)) {
      entries.forEach(entry -> {
          if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) return;
          try { Files.delete(entry); } catch (IOException de) { … }
      });
  }
  ```
  삭제 후 KPST 위탁이 실패(`SUBMIT_FAILED`)하거나 폴링 타임아웃(`poll-timeout-minutes:180`)으로 끝나면 **새 산출물은 생성되지 않고 옛 산출물은 이미 지워진 상태**다. 자동 재비식별 큐가 정책상 없어(외부 솔루션 수동 재처리) 복구 트리거도 없다. 그동안 데이터마트 뷰 lateral join 이 참조하는 최신 `SUCCEEDED` procLog 행(=삭제된 파일 경로)은 그대로 남아 **dangling path** 가 된다.
- **재현/확인 경로**:
  1. APPROVED + `DE_IDNTF_YN='F'`(신고 접수) 영상에 `POST /v1/videos/{rawSn}/redeident` (`ApprovedRedeidentService:83-85` 가 `'Y'` 만 409 로 막으므로 `'F'` 는 통과)
  2. 위탁 직후 export 디렉터리 확인 — 기존 `{stem}-mask{ext}` 가 사라짐
     `docker exec klid-backend ls -la /app/storage/raw/seed/17/deid/`
  3. mock-server 를 정지시켜 폴링을 타임아웃시키면 `poll_stts_cd=FAILED` 로 종결되고 파일은 끝내 복원되지 않음
  4. `SELECT de_idntf_file_path_nm FROM ls_deident_proc_log WHERE data_raw_sn=17 AND proc_stts_cd='SUCCEEDED' ORDER BY req_dt DESC, proc_log_sn DESC LIMIT 1;` → 존재하지 않는 파일 경로
- **영향**: 데이터정합/가용성. 검수완료·관제 통지된 영상의 유일한 비식별 영상 파일이 비가역 소실되고, 관제가 `V_COMPLETED_VIDEO` 로 픽업하는 비식별 경로가 dangling 이 된다(CLAUDE.md "관제 접근 무조건 보장" 위반). 프레임 이미지(`{base}/frames/deid/{rawSn}`)는 별도 경로라 남으므로 영상만 결손되는 **부분 정합 붕괴**가 된다. 보안 관점 상승은 없음(원본 노출 아님).
- **수정 방향(제안)**: `KpstDeidentService.cleanExportDir` 를 **삭제가 아니라 격리(quarantine)** 로 바꾼다 — 같은 디렉터리 하위 `.prev/`(폴백 스캔이 비재귀라 자동 제외됨)로 `Files.move(ATOMIC_MOVE)` 한 뒤 위탁하고, 완료(`finishDownloadAndComplete` 성공) 후에만 `.prev/` 를 비운다. 혹은 최소 조치로 **REDEIDENT 경로에서만 삭제를 보류**하고(`redeident` 플래그를 `buildProjectRequest` 까지 전달) 완료 시점에 정리한다. 어느 쪽이든 "새 산출물 확보 전 옛 산출물 삭제 금지" 불변식을 테스트로 고정할 것.


### [B-ISSUE-122] TC-DEID-088 / TC-DEID-089 — 완료 전이 원자 클레임의 **0행 분기와 롤백 해제 경로에 테스트가 0건**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `claimDownloadCompletion` 은 "완료 전이 자체가 클레임"이라는 설계의 핵심이다. ①동시 2노드 완료 시도에서 1행 얻은 쪽만 후처리(프레임 attach·Y 전이·락 해제·알림)해 **비식별 프레임 이중 attach 창이 없어야** 하고 ②후처리 실패 시 클레임이 함께 롤백돼 재폴링 대상으로 남아야 한다. 회귀하면 프레임 재추출·알림·락 해제가 두 번 일어나거나(①), 완료가 DOWNLOADED 로 굳은 채 Y 미전이 stuck 이 생긴다(②).
- **현재 동작(이슈 내용)**: 메커니즘 자체는 정합하다(PG 조건부 UPDATE 재평가 시맨틱을 실측 L3 로 확인).
  ```java
  // KpstDeidentTxService.java:209-213
  if (procLogRepository.claimDownloadCompletion(procLogSn, deidFilePath, LocalDateTime.now()) != 1) {
      log.info("[KpstDeid] completion already applied — skip duplicate rawSn={}", rawSn);
      return;   // ← 이 분기를 실행하는 테스트가 저장소 전체에 0건
  }
  ```
  `grep -rn "claimDownloadCompletion" backend/src/test/` 결과 유일 참조는 `KpstDeidentTxServiceTest:82` 의 `when(...).thenReturn(1)` 전역 스텁뿐이다. `KpstDeidentTxServiceTest` 는 전량 Mockito 단위 테스트라 실 트랜잭션 롤백도 관측 불가하다. `claimForPoll` 에는 Testcontainers 기반 2스레드 IT(`KpstDeidentPollClaimIT`)가 있는 반면, **완료 클레임에는 대응 IT 가 없다** — 두 클레임 중 부작용이 훨씬 큰 쪽에만 커버리지가 비어 있다.
- **재현/확인 경로**:
  - `grep -rn "claimDownloadCompletion" backend/src/test/` → 1건(스텁만)
  - `KpstDeidentTxServiceTest:82` 를 `thenReturn(0)` 으로 바꿔도 기존 테스트가 실패하지 않는지(= 0행 분기가 어떤 단언에도 걸리지 않음) 확인
- **영향**: 기능/데이터정합. 현재는 결함이 아니라 **회귀 방어 부재**다. 술어(`POLL_STTS_CD IN ('WAITING','POLLING')`)를 넓히거나 `!= 1` 가드를 지우는 변경이 어떤 테스트도 깨뜨리지 않고 통과한다 → 2노드 운영에서 프레임 이중 attach·중복 알림이 조용히 재발할 수 있다.
- **수정 방향(제안)**: `KpstDeidentPollClaimIT` 와 동일 골격(Testcontainers PG + 2스레드 + `CountDownLatch`)으로 `finishDownloadAndComplete` 동시 호출 IT 를 추가해 ①`deidentFrameAttacher.attachDeidentFrames` 가 정확히 1회만 호출되고 ②두 번째 호출이 no-op 임을 단언한다. 별도로 후처리에서 예외를 던지는 스텁으로 롤백 후 `poll_stts_cd` 가 `WAITING/POLLING` 으로 복귀함(재폴링 대상 유지)을 실 트랜잭션에서 단언한다. **구현은 하지 않는다.**


### [B-ISSUE-03] TC-BATCH-020/023/024/025 — 구 관제 공유 클립 스캔 리포지토리 2종이 프로덕션 미사용 dead code 로 잔존
- **심각도**: LOW
- **기대 동작(기대효과)**: 적재 소스 교체 후 구 경로는 제거되어야 검증자·후속 개발자가 "어느 쪽이 진짜 적재 경로인가"를 오해하지 않는다.
- **현재 동작(이슈 내용)**: `MngClipMasterRepository.findIngestCandidatesByJobDmndYn`(`:48-57`) 과 `MngClipEvntLstRepository.findFirstByEvntId`/`findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc`(`:28,42`) 는 **프로덕션 호출부 0건**이다. 유일한 참조가 테스트 `MngClipIngestCandidateIT`(:99,180,200)이라 CI 는 계속 초록이고, 카탈로그도 이 IT 를 근거로 살아 있는 경로처럼 기술한다.
- **재현/확인 경로**: `grep -rn "MngClipMasterRepository\|MngClipEvntLstRepository" backend/src --include="*.java" | grep -v "repository/MngClip"` → **0건**(테스트 포함 검색 시 `MngClipIngestCandidateIT` 만)
- **영향**: 유지보수/검증 오도. 보안·데이터 영향은 없음(READ 전용).
- **수정 방향(제안)**: 구 스캔 전용 쿼리 메서드 + `MngClipIngestCandidateIT` 제거. `MNG_CLIP_*` 엔티티 자체는 다른 참조가 있으면 존치하되, 적재 경로가 아님을 javadoc 에 명시.


### [B-ISSUE-04] TC-BATCH-018 — 스캔 JobDetail description 이 폐지된 구 스킴(`JOB_DMND_YN='Y'`) 문구로 남아 Quartz 테이블에 적재된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 운영자가 `QRTZ_JOB_DETAILS.DESCRIPTION` 으로 잡의 실제 소스를 파악할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `ControlTrainingVideoScanTriggerConfig.java:33` `.withDescription("관제 학습용 영상(JOB_DMND_YN='Y') 픽업 적재")` — 같은 커밋에서 Job 클래스 javadoc 은 "구 소스 `MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 스캔은 폐지"로 갱신됐으나(`ControlTrainingVideoScanJob.java:13-16`) description 만 남았다.
- **재현/확인 경로**: V147+ dev 스택 기동 후 `SELECT description FROM qrtz_job_details WHERE job_name='controlTrainingVideoScanJob';`
- **영향**: 운영 관측 오도(기능 영향 없음).
- **수정 방향(제안)**: `"관제 인입(LS_DATA_INGEST PENDING) 픽업 적재"` 로 교체.


### [B-ISSUE-05] TC-DEID-007 — `AsyncDeidentifyRunner.loadRaw` 의 `@Transactional(REQUIRES_NEW, readOnly)` 가 자기호출로 무효(선언과 실행이 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 근거와 코드 주석이 "REQUIRES_NEW readOnly 로 영상 메타를 조회한다"고 선언하면 실제로 그 경계가 적용돼야 한다. `CLAUDE.md` 도 "자기호출로 프록시를 우회하지 말 것"을 별도 규칙으로 못박고 있다(프레임 이미지 서빙 절).
- **현재 동작(이슈 내용)**:
  ```java
  // AsyncDeidentifyRunner.java:79
  LsDataRaw raw = loadRaw(rawSn).orElse(null);          // ← this.loadRaw (프록시 미경유)
  // :110-112
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  Spring AOP 는 외부 호출만 가로채므로 이 어노테이션은 no-op 이다. 동일 패턴이 `BatchOrchestrator.java:104`(`loadRaw(rawSn)` 자기호출) → `:149` 에도 있다.
- **재현/확인 경로**: 정적. 런타임 확인은 `loadRaw` 내부에서 `TransactionSynchronizationManager.isActualTransactionActive()` 를 찍어보면 드러난다(이번 회차는 코드 수정 금지라 미수행).
- **영향**: 현재는 **실피해 없음** — `@Async` 실행이라 앰비언트 트랜잭션이 없고, `VideoRepository` 는 `@ControlRepo`(control EMF)이며 `SimpleJpaRepository` 의 `@Transactional(readOnly=true)` 가 `@Primary` 인 `controlTransactionManager`(`ControlDataSourceConfig.java:31,38,50`)로 열리므로 결과가 동등하다. 위험은 **미래**다 — pre-marking 파이프라인이 확장돼 `runAsync` 가 트랜잭션 안에서 호출되면 "새 트랜잭션에서 읽는다"는 전제가 조용히 깨진다.
- **수정 방향(제안)**: 조회를 별도 빈(`…LookupService`)으로 분리하거나 `self` 프록시 주입으로 외부 호출화. 어느 쪽도 아니라면 어노테이션을 제거하고 javadoc 을 실제 동작에 맞춰 정정(선언만 남기는 것이 가장 나쁘다).


### [B-ISSUE-06] TC-BATCH-021 — `scanAndIngest` 의 readOnly 트랜잭션이 tick 전체(최대 100행 NAS 파일 I/O)를 감싸 커넥션을 점유한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 조회 트랜잭션은 조회 구간에만 열려야 한다. 이 프로젝트는 "커넥션을 쥔 채 NAS I/O 하면 커넥션 기아로 간다(전례 있음)"를 `CLAUDE.md` 구속 규칙으로 갖고 있다(프레임 이미지 서빙 절).
- **현재 동작(이슈 내용)**:
  ```java
  // TrainingVideoIngestService.java:68-69
  @Transactional(value = "controlTransactionManager", readOnly = true)
  public int scanAndIngest() { ... for (LsDataIngest row : pending) { ingestTx.ingestOne(row); } ... }
  ```
  외부 readOnly 트랜잭션이 루프 전체 동안 열려 있고, 행마다 `REQUIRES_NEW` 가 **두 번째 커넥션**을 잡는다. `ingestOne` 은 그 안에서 `Files.exists` / `toRealPath()` 로 NAS 를 친다(`TrainingVideoIngestTx.java:443-453`). 클래스 javadoc 은 "조회는 쓰기 밖에서"라고 적었지만 실제 경계는 tick 전체다.
- **재현/확인 경로**: NAS 응답이 느린 환경에서 tick 당 100행 처리 시 Hikari 활성 커넥션 2개가 tick 지속시간만큼 유지된다(`/api/actuator/metrics/hikaricp.connections.active` 관측).
- **영향**: 성능/가용성(커넥션 점유). 현재 상한 100 · 잡 1개라 실피해는 작다.
- **수정 방향(제안)**: `scanAndIngest` 에서 `@Transactional` 을 떼고 후보 조회만 별도 readOnly 메서드(별 빈)로 분리 — 루프는 트랜잭션 밖에서 돌린다. 행별 쓰기 경계는 이미 `ingestOne` 이 갖고 있어 변경 불필요.


### [B-ISSUE-07] TC-BATCH-007/008 — 클레임 후 종결 실패 시 `PROCESSING` 좀비의 회수 통로가 없다(재큐는 `FAILED` 만 대상)
- **심각도**: LOW
- **기대 동작(기대효과)**: 설계 §6-0-1 ②("되돌릴 수 없는 차단과 끝나지 않는 보류는 둘 다 가용성 결함")를 모든 종결 상태에 적용하려면 `PROCESSING` 고착도 회수 가능해야 한다.
- **현재 동작(이슈 내용)**: 클레임(`PROC_STTS_CD='PROCESSING'`)에 성공했는데 종결 전이가 안 되는 두 경로가 코드에 남아 있다.
  ```java
  // TrainingVideoIngestTx.java:229-234  — 클레임 직후 findById 가 null
  log.error("[TrainingIngest] claimed row disappeared rcptnSn={}", rcptnSn);  return false;
  // :305-310  — revertToPendingForRetry 가 0행
  log.warn("[TrainingIngest] pending revert affected {} rows — row may stay PROCESSING rcptnSn={}", ...);
  ```
  이때 행은 `PROCESSING` 으로 커밋된 채 남고, 폴링 술어(`PENDING`)에서 빠진다. 유일한 회수 API 인 `requeueFailedForRetry`/`requeueFailedBatch` 는 술어가 `PROC_STTS_CD='FAILED'` 라 이 행을 못 되살린다(`LsDataIngestRepository.java:264-316`). `UK_LS_DATA_INGEST_CLIP` 때문에 관제 재INSERT 도 불가하고 인입 행은 삭제 금지다.
  ※ **크래시는 안전하다** — 클레임 UPDATE 가 종결 로직과 같은 `REQUIRES_NEW` 트랜잭션 안이라 프로세스가 죽으면 함께 롤백돼 `PENDING` 으로 돌아온다. 그래서 심각도를 LOW 로 둔다.
- **재현/확인 경로**: `UPDATE LS_DATA_INGEST SET PROC_STTS_CD='PROCESSING' WHERE RCPTN_SN=:n;` 후 스캔을 돌려도 픽업되지 않고, `POST /v1/…/requeue` 도 0행을 반환하는지 확인(V147+ 필요).
- **영향**: 가용성(영상 1건 영구 미적재). 발생 확률은 낮다.
- **수정 방향(제안)**: ①`PRCS_DT` 기준 stale `PROCESSING` 을 `PENDING` 으로 되돌리는 회수 술어를 재큐 API 에 추가(다른 잡의 `claimExpired` 리스 패턴과 동일) 또는 ②클레임에 리스 만료 컬럼을 두고 폴링 술어에 포함.


### [B-ISSUE-21] TC-DEID-010~024 — B-3 섹션 근거 `file:line` 이 전 항목 드리프트(약 +10행)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 `근거(file:line)` 는 검증자가 즉시 해당 코드를 열어 대조할 수 있어야 한다. 회차 간 재검증에서 같은 위치를 다시 찾는 비용이 근거의 가치다.
- **현재 동작(이슈 내용)**: B-3 의 16건 중 **15건**(TC-DEID-010~024)의 라인 번호가 실제와 어긋난다. 오프셋이 대체로 +10 전후로 균일해 **주석 블록 추가에 의한 일괄 시프트**로 보인다.

  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 010 | `:87,178-215` | `:182-190,205-221`(+ allowlist 상수 `:95`) |
  | 011 | `:202,208` | `:210,216-221` |
  | 012 | `:216-218` | `:223-227` |
  | 013 | `:205-208` | `:207-214` |
  | 014 | `:259-262` | `:270-272` |
  | 015 | `:265-267` | `:275-277` |
  | 016 | `:269-273` | `:280-283` |
  | 017 | `:274-278` | `:286-288` |
  | 018 | `:296-306` | `:309-314` |
  | 019 | `:319-325` | `:330-335` |
  | 020 | `:319-350` | `:345-365` |
  | 021 | `:375-400` | `:385-407` |
  | 022 | `:408-420` | `:418-421` |
  | 023 | `:232-241` | `:239-250` |
  | 024 | `:241` | `:249` |

  (대조군: **B-4 의 16건은 라인 번호가 정확**하다 — `BatchOrchestrator.java:101-103`, `BatchTransitionService.java:77-81/184-197/110-124` 등 전부 일치. 즉 드리프트는 `DeidentifyStep.java` 한 파일에 국한된다.)
- **재현/확인 경로**: `sed -n '178,215p' backend/src/main/java/kr/co/cudo/authoring/batch/step/DeidentifyStep.java` → 카탈로그가 지목한 "mock 부트 게이트"가 아니라 필드 선언·생성자 영역이 나온다.
- **영향**: 기능 영향 없음. 카탈로그 자체의 정합성 결함(검증 비용 증가·오독 위험).
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` B-3 섹션의 근거 컬럼을 위 표의 "실제" 값으로 일괄 정정. 차기 최신화 시 `DeidentifyStep.java` 는 라인 대신 **메서드명 앵커**(예: `DeidentifyStep#assertMockAllowedProfile`)로 표기하면 재드리프트를 구조적으로 줄일 수 있다.


### [B-ISSUE-23] TC-BATCH-035 — `process(Long, Map)` stage 토글 오버로드의 호출자가 0건(도달 불가 경로)
- **심각도**: LOW
- **기대 동작(기대효과)**: `BatchOrchestrator.process(Long, Map)` Javadoc 은 *"토글 대상 단계(FRAME_EXTRACT/YOLO/SAM2)가 off 면 … **dev 단일 파이프라인 수렴 경로에서 사용한다**"*(`BatchOrchestrator.java:91-99`)라고 용도를 명시한다. 케이스 TC-BATCH-035 도 이를 전제로 "dev 토글" 시나리오를 검증 대상으로 잡고 있다.
- **현재 동작(이슈 내용)**: 프로덕션·dev 를 통틀어 이 오버로드를 호출하는 코드가 **한 곳도 없다**.
  ```
  $ grep -rn "\.process(" backend/src/main/java/ | grep -i orchestrator
  BatchDevTriggerController.java:119:  orchestrator.process(rawSn);
  BatchRetryQuartzJob.java:55:         orchestrator.process(rawSn);
  AsyncBatchRunner.java:25:            orchestrator.process(rawSn);
  BatchQuartzJob.java:51:              orchestrator.process(rawSn);
  BatchReprocessService.java:87:       orchestrator.process(rawSn);
  ```
  과거 호출자였던 `DevPipelineRunner` 는 클래스 주석대로 *"합성 마킹 생성과 `BatchOrchestrator.process` 직접 호출은 폐지되었으며"*(`DevPipelineRunner.java:24-27`) 오버로드 사용을 접었고, 오버로드와 `BatchContext.stageToggles`·`BatchStep.isEnabled` 오버라이드 3종(`FfmpegFrameExtractor:107` / `YoloAutolabelStep:135` / `Sam2SegmentStep:120`)만 남았다. 단위테스트(`BatchOrchestratorTest:355-423`, `BatchContextToggleTest`)가 살아 있어 커버리지 지표상으로는 드러나지 않는다.
- **재현/확인 경로**: 위 grep. 실행 중 스택에서 stage 토글을 태울 수 있는 REST/Quartz 진입점이 존재하지 않는다(`BatchDevTriggerController` 는 `rawSn` 만 받는다).
- **영향**: 기능 영향 없음(프로덕션 경로 100% 보존이 설계 의도이므로 회귀도 아님). 다만 **테스트만 존재하는 미사용 분기**로 남아 유지보수 시 "지원되는 기능"으로 오인될 수 있고, 케이스 TC-BATCH-035 는 실동작으로 검증할 대상이 없다.
- **수정 방향(제안)**: 둘 중 하나로 정리 — ①dev 토글이 여전히 필요하면 `BatchDevTriggerController.trigger` 에 optional `stages` 파라미터를 붙여 진입점을 복구(`@Profile("!prd")` 안이므로 노출 위험 없음), ②불필요하면 오버로드·`BatchContext.stageToggles`·3개 `isEnabled` 오버라이드·관련 단위테스트를 함께 제거하고 카탈로그에서 TC-BATCH-035/036 을 폐기 표기. ⚠ 구현은 하지 않음.


### [B-ISSUE-24] TC-BATCH-031 — `protected @Transactional loadRaw` 3곳이 자기호출이라 애노테이션이 무효(의도-구현 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: `BatchOrchestrator.process` Javadoc 이 *"process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), **영상 메타 조회를 위한 짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리**"*(`BatchOrchestrator.java:83-84`)라고 선언한다. 즉 `loadRaw` 는 실제로 `REQUIRES_NEW readOnly` 경계를 열어야 한다.
- **현재 동작(이슈 내용)**: 애노테이션이 **두 가지 이유로 동시에 무효**다. ①`process()` 가 `this.loadRaw(rawSn)` 로 자기호출해 Spring AOP 프록시를 우회한다 ②`AnnotationTransactionAttributeSource` 는 기본이 `publicMethodsOnly=true` 라 **`protected` 메서드는 외부 호출이어도 어드바이스 대상이 아니다**.
  ```java
  // BatchOrchestrator.java:104
  LsDataRaw raw = loadRaw(rawSn);          // ← 자기호출(프록시 우회)
  ...
  // BatchOrchestrator.java:147-153
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected LsDataRaw loadRaw(Long rawSn) { ... }   // ← protected + self-invoke = 사문화
  ```
  동일 패턴이 `AsyncDeidentifyRunner.java:110-117`, `DevPipelineRunner.java:88-95` 에도 있다(주석에 *"BatchOrchestrator.loadRaw 패턴"* 이라 명시돼 **의도적으로 복제 전파**됐다).
  이 코드베이스는 같은 함정을 이미 두 번 크게 겪었다 — `DeidentifyStep` 의 `selfProvider` 도입(`DeidentifyStep.java:116-126`)과 `BatchTransitionService` 별도 빈 분리(`BatchTransitionService.java:24-29`)가 모두 이 문제의 사후 수정이다.
- **재현/확인 경로**: `logging.level.org.springframework.transaction=TRACE` 로 dev 트리거를 호출하면 `loadRaw` 구간에 `Creating new transaction` 로그가 없고, `SimpleJpaRepository.findById` 자체의 `readOnly` 트랜잭션만 열렸다 닫힌다.
- **영향**: **현재 기능 영향은 없다** — `SimpleJpaRepository` 가 자체 `@Transactional(readOnly=true)` 를 갖고 있어 조회는 정상 동작하고, `LsDataRaw` 는 연관 매핑(`@OneToMany`/`@ManyToOne`)이 전무한 평면 엔티티라 detached 상태로 스텝에 전달돼도 lazy 초기화 예외가 나지 않는다(실측: rawSn=31 정상 완주). 위험은 **미래**에 있다 — `LsDataRaw` 에 연관을 추가하거나 `loadRaw` 에 조회를 하나 더 붙이는 순간 경계가 없어 `LazyInitializationException` 또는 커넥션 2회 왕복이 조용히 생긴다. 또 주석이 사실과 달라 후속 개발자를 오도한다.
- **수정 방향(제안)**: 세 곳 모두 ①`loadRaw` 를 별도 조회 빈(예: `BatchRawLookupService`)의 `public @Transactional(readOnly)` 메서드로 이동해 프록시를 경유시키거나, ②`DeidentifyStep` 이 이미 쓰는 `ObjectProvider<Self>` 패턴을 적용. 정리 후 `BatchStepTransactionBoundaryTest` 와 같은 **정적 가드**(“`@Transactional` 이 `private`/`protected` 메서드에 붙어 있으면 실패”)를 추가하면 재발을 구조적으로 막을 수 있다. ⚠ 구현은 하지 않음.


### [B-ISSUE-25] TC-DEID-022 — 케이스 기대 ErrorCode(`INVALID_INPUT`)가 base 이탈 실제 코드(`FORBIDDEN`)와 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 *"출력 경로 순회 방어(CWE-22) — base 이탈 → **INVALID_INPUT**"* 을 단언한다. 회차 간 재검증에서 응답/예외 코드가 판정 기준이므로 기대값이 코드와 일치해야 한다.
- **현재 동작(이슈 내용)**: 방어는 정상 동작하나 **코드가 두 축으로 갈린다**.
  ```java
  // VideoArtifactRootResolver.java:424-444 (resolveUnder)
  if (segment == null || segment.isBlank()
          || segment.contains("/") || segment.contains("\\") || segment.contains("..")) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 경로 세그먼트입니다.");   // ① 세그먼트 축
  }
  ...
  if (!target.startsWith(base)) {
      throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");           // ② base 이탈 축
  }
  if (!realOrNearest(target).startsWith(realOrNearest(base))) {
      throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");           // ③ 실경로(심링크) 축
  }
  ```
  즉 `INVALID_INPUT` 은 **세그먼트 검증 전용**이고, 케이스가 지목한 **"base 이탈"은 `FORBIDDEN`** 이다. `coLocateVideoRoot` 의 원본 덮어쓰기 차단(`VideoArtifactRootResolver.java:253-255`)도 `FORBIDDEN` 이다. 케이스의 "실제 도달 불가한 방어심도 분기"라는 판단 자체는 정확하다 — `resolveSafeTargetPath` 가 넘기는 세그먼트는 `rawSn:Long` 과 상수 `deidentified.mp4` 뿐이다.
- **재현/확인 경로**: `VideoArtifactRootResolver.resolveUnder(Paths.get("/nas/deid"), "..", "etc")` → `INVALID_INPUT` / `resolveUnder` 에 base 밖으로 해석되는 심링크를 물리면 → `FORBIDDEN`.
- **영향**: 코드 결함 아님(양쪽 다 fail-secure). 카탈로그 기대값 정합성 문제이며, 그대로 두면 차기 회차에서 "FORBIDDEN 이 떴으니 FAIL" 이라는 오판을 유발할 수 있다.
- **수정 방향(제안)**: TC-DEID-022 기대결과를 *"세그먼트 위반 → `INVALID_INPUT` / base·실경로 이탈 → `FORBIDDEN` (둘 다 기본 루트 폴백 없이 fail-secure)"* 로 분리 기술. ⚠ 구현은 하지 않음.

---

## 참고 — 이번 검증이 남긴 테스트 데이터 (기존 데이터 보존, 신규 행만 추가)

| 테이블 | 추가 행 | 용도 |
|---|---|---|
| `mng_clip_master` | `QA0801-EVT-01`, `QA0801-EVT-02` | 관제 적재 스캔 트리거 |
| `ls_data_raw` | `raw_sn=31`(COMPLETED/Y), `32`(PENDING/F), `9201~9206` | B-3 KPST 실왕복 · B-4 진입 가드 매트릭스 |
| `ls_raw_data_status` | `31`(PENDING), `9201`(APPROVED), `9202`(FAILED), `9204`(IN_REVIEW), `9205`(REJECTED), `9206`(PENDING) | 상태 전이 검증 |
| `ls_bat_rty_wtng` | `raw_sn=9203`(EXHAUSTED) | 재시도 소진 검증 |

> 기존 행은 수정하지 않았다(9202 의 작업상태만 본 검증이 생성한 행 내에서 ASSIGNED↔APPROVED 로 조작). 코드·설정·테스트 파일 수정 0건, 빌드/테스트 실행 0건.


### [B-ISSUE-43] TC-BATCH-050/051/052/060 — 브릿지 가드 4종이 **마킹 API 경로에서 도달 불가**한데 CRLF 정제에는 테스트가 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 방어적 이중화 코드라도 "언제 발화하는지"가 명확해야 하고, 보안 정제(CWE-117)는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: `MarkingCompletedEvent` 의 publisher 는 `MarkingService.java:237` **단 1곳**이고, 그 앞의 `MarkingGuards.requirePreconditions`(`MarkingGuards.java:79-96`)가 이미 ①영상 존재 ②`de_ident_yn='Y'` ③`stage == MARKING_READY` 를 강제한다. 따라서 브릿지의 `REASON_VIDEO_NOT_FOUND`(`:99-103`)·`REASON_STAGE_ALREADY_RUN`(`:109-114`)·`REASON_NOT_DEIDENTIFIED`(`:116-123`) 세 분기와, 그 안에서만 쓰이는 `sanitize()`(`:166-168`)는 **API 로 도달할 수 없다**(실측: 9104/9106/9109/9112 모두 마킹 단계 412 에서 종료, 브릿지 로그 미발생). 그리고 `MarkingBatchBridgeTest` 에 CR/LF 주입 케이스가 없다(`grep sanitize|\\n|CRLF` 무결과).
- **재현/확인 경로**: `grep -rn "new MarkingCompletedEvent" backend/src/main` → 1건. 9106(stage=COMPLETED) 마킹 시도 → 412 이며 `docker logs klid-backend | grep MarkingBatchBridge` 에 해당 rawSn 없음.
- **영향**: 보안 회귀 감지 불가(CWE-117 정제가 조용히 제거돼도 테스트가 잡지 못함) + 도달 불가 코드에 대한 오해(향후 다른 publisher 추가 시 이 가드가 유일한 방어선이 되는데 검증 자산이 없음).
- **수정 방향(제안)**: `MarkingBatchBridgeTest` 에 `dataSttsCd = "PROCESSING\r\n[FAKE] injected"`, `deIdntfYn = "N\nadmin"` 을 주입해 로그 출력에 개행이 없음을 단언하는 케이스 2건을 추가한다(코드 수정 불필요). 아울러 세 가드의 Javadoc 에 "현재 publisher 는 1곳이며 이 분기들은 향후 publisher 추가 대비 방어선"임을 명시.

---

## 6. 부수 관찰 (결함 아님 / 정보성)

1. **배치 실패 영상은 마킹 API 가 영구히 닫힌다** — 배치 실패 시 stage 가 `FAILED` 로 바뀌므로 `requirePreconditions` 가 412 를 낸다(9109 실측, 본 검증 중 9103·9113 도 동일 상태가 됨). 복구는 `POST /v1/videos/{rawSn}/batch/retry` 뿐이며 이는 `MarkingBatchBridge` Javadoc(`:50-55`)이 명시한 의도된 설계다. B-ISSUE-41 과 달리 **복구 API 가 존재**하므로 결함으로 보지 않는다.
2. **검증 중 생성한 데이터**(다음 회차 참고): 신규 영상 `rawSn=30/33/38`(vmsClipId `QA0801-B3-CONC`/`-WORKER`/`-TC096`), 신규 마킹 `marking_sn=25(9110), 26(9113), 27(9103), 28(30), 33(33), 38(38)`, `ls_raw_data_status` 신규 행 `9103`(테스트용 ASSIGNED INSERT, 이후 배치 실패로 FAILED). 기존 행 수정·삭제는 없음.
3. **YOLO 는 이 환경에서 mock 응답** — `mockReason=weights_missing`(ai-server 가중치 미탑재). B-5/B-6 판정에는 영향 없음(마킹 단계는 ai-server 를 타지 않음).
4. **VLM 실왕복 확인** — rawSn=30 마킹 → `[Batch][VlmTimeseries] describe submit … request_id=bc2d9a2b` → mock-server 콜백 `[Webhook][Vlm] result applied … markingsTransitioned=1` → 마킹이 `PENDING→VLM_REQUESTED→VLM_COMPLETED` 로 정상 종결. **즉 배치가 도는 정상 경로에서는 고아가 생기지 않는다** — B-ISSUE-41 은 오직 skip 경로 전용이다(대조군 확보).


### [B-ISSUE-62] TC-VLM-009 — 빈 describe 응답의 기대결과가 논블로킹 전환으로 무효화됨(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그가 실제 코드의 유일한 진실원과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**: 케이스는 "결과 null → `doSubmit` 이 `EXTERNAL_API_ERROR`" 를 요구하나, `862ca6d8`(2026-07-30, 논블로킹 전환) 이후 코드는
  ```java
  // VlmTimeseriesStep.java:362-370
  vlmClient.submitTimeseries(req)
      .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
      .subscribe(resp -> …onAccepted(…), err -> …onSubmitFailed(rawSn, markingSn, err));
  return VlmTimeseriesResponse.submitted(requestId);   // 예외 미전파
  ```
  로 바뀌어, 빈 응답은 **비동기 실패 기록(`SKIP_REASON_SUBMIT_FAILED`)** 이 되고 스텝은 `submitted` 를 반환한다.
- **재현/확인 경로**: `grep -n "switchIfEmpty" VlmTimeseriesStep.java` / 테스트 `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다`.
- **영향**: 카탈로그 정합(거짓 FAIL 유발).
- **수정 방향(제안)**: 기대결과를 "예외 미전파 + `VlmSubmitOutcomeRecorder.onSubmitFailed` 로 `LS_BATCH_PROC_LOG` 에 `SKIP_REASON_SUBMIT_FAILED` 적재 + 재개 대상 편입" 으로 갱신.


### [B-ISSUE-63] TC-VLM-010 — `BLOCK_TIMEOUT=45s` 이중 타임아웃 구조가 코드에서 사라짐(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 동상.
- **현재 동작(이슈 내용)**: VLM 경로에 `.block()` 호출과 `BLOCK_TIMEOUT` 상수가 모두 없다. `grep -rn "BLOCK_TIMEOUT" backend/src/main` → `ControlNotifyClient.java:52`(15s) 1곳뿐. 현재 VLM 의 유일한 시간 상한은 `vlm.client.timeout-seconds`(기본 10s, `VlmClient.java:71` → `.timeout(timeout)` `:108`)이고, 그 위에 Resilience4j Retry/CircuitBreaker 가 걸린다.
- **재현/확인 경로**: `grep -rn "BLOCK_TIMEOUT\|\.block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java` → 0건.
- **영향**: 카탈로그 정합. 겸하여 `UNCERTAINTIES.md` #13 의 "타임아웃 이중 구조(45s↔10s)" 기술도 갱신 대상이다.
- **수정 방향(제안)**: 케이스를 "단일 실효 타임아웃 10s + Retry/CB, 파이프라인 스레드 미점유" 로 재작성하고 UNCERTAINTIES #13 을 함께 정정.


### [B-ISSUE-64] TC-VLM-011 — 마킹 전이 시점이 "위탁 성공 후" → "제출 전 선커밋" 으로 반전(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 동상.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.java:337` 이 **외부 제출 이전**에 `markingTxService.persistVlmRequested(marking)`(별도 빈, `REQUIRES_NEW`)를 호출해 선커밋한다. 이유는 논블로킹 제출에서 콜백이 ACK 보다 먼저 도착하는 레이스(수신부가 전이 대상을 못 찾아 `VLM_REQUESTED` 영구 고착)를 닫기 위함이며, 수신부도 `ACTIVE_STATUSES`(PENDING 포함)로 범위를 넓혀 양단 방어한다(`VlmResultService.java:153-158`). 카탈로그의 `persistMarkingTransition`(`VlmTimeseriesStep:300-308`)은 존재하지 않는다.
- **재현/확인 경로**: `VlmTimeseriesStep.java:327-338`, `VlmMarkingTxService.java:47-60`.
- **영향**: 카탈로그 정합.
- **수정 방향(제안)**: 기대결과를 "제출 전 `VlmMarkingTxService.persistVlmRequested` 로 독립 커밋(detached → merge), PENDING 에서만 전이" 로 갱신.


### [B-ISSUE-81] TC-STREAM-B04 / TC-STREAM-B20 — 영상 스트리밍의 비식별 경로 가드가 lexical 전용이라 심링크 치환으로 **원본(비식별 이전) 영상이 그대로 서빙된다**
- **심각도**: **HIGH**
- **기대 동작(기대효과)**: `GET /v1/videos/{rawSn}/stream` 은 **항상 비식별 영상만** 내보내야 한다(`CLAUDE.md` — "비식별 미완료 시 NOT_FOUND 로 원본 노출 차단"). 프레임 이미지 4경로에 대해서는 이미 *"`StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기로 검증하고, 판정이 돌려준 실경로(`toRealPath()`)를 그대로 사용한다 — lexical 경로로 검증하고 lexical 경로로 여는 구현은 금지"* 가 구속 규칙으로 명문화돼 있다. **비식별 영상 파일도 동일 신뢰 경계**(외부 비식별 벤더 KPST 가 공유 마운트에 직접 산출물을 쓰는 디렉터리)에 있으므로 같은 규약이 적용돼야 한다.
- **현재 동작(이슈 내용)**: `VideoStreamService.resolveSafe` 가 `normalize()` + `startsWith` **lexical 검사만** 수행하고, 반환한 lexical 경로를 그대로 `UrlResource` 로 연다. 실경로 재검증(`toRealPath`)이 없다.
  ```java
  // backend/.../video/service/VideoStreamService.java:470-485
  static Path resolveSafe(List<Path> baseDirs, String filePath) {
      Path candidate = Paths.get(filePath);
      for (Path baseDir : baseDirs) {
          Path resolved = candidate.isAbsolute() ? candidate.normalize() : baseDir.resolve(candidate).normalize();
          if (resolved.startsWith(baseDir)) { return resolved; }   // ← 실경로 재검증 없음
      }
      throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
  }
  ```
  같은 DB 값(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)을 읽는 형제 소비자 `FfmpegFrameExtractor` 는 `underBaseWithRealPath`(`:356-368`) → `VideoArtifactRootResolver.verifyRealPathUnder`(`:458-466`) 로 **실경로를 재검증**한다. 프레임 이미지 서빙도 `StorageSubtreePolicy.verifyDeidentifiedFile`(`:201-215`)이 `realResolved = resolved.toRealPath()` 후 서브트리를 재판정하고 **실경로를 돌려준다**. **스트리밍만 빠져 있다** — 즉 "가드가 갈라져 하나씩 샌다"가 이 경로에서 실제로 일어나고 있다.
- **재현/확인 경로** (2026-08-01 실측, 합성 영상 rawSn=36 · 검증 후 원복 완료):
  ```bash
  # 1) 비식별 산출물 파일을 원본 영상 심링크로 치환 (허용 base 안, lexical 경로 불변)
  D=/app/storage/raw/autolabel-test/36/deid/378d1b22-…-mask.mp4
  O=/app/storage/raw/autolabel-test/378d1b22-….mp4          # 원본(비식별 이전)
  docker exec klid-backend sh -c "mv $D $D.real && ln -s $O $D"

  # 2) 프레임 추출(배치) — 차단됨 ✅
  curl -X POST /api/v1/videos/36/markings -d '{"mode":"AUTO","intervalFrames":30}'
  # log: WARN [Batch][FrameExtract] deid path rejected (base/realpath) rawSn=36 — RAW only
  # DB : ls_data_src 5행 de_idntf_src_file_path_nm = NULL

  # 3) 같은 심링크를 스트리밍 — 통과됨 ❌
  curl -o out.bin -w '%{http_code} %{size_download}' /api/v1/videos/36/stream -H "Authorization: Bearer $REVIEWER"
  # → 200 20590   (= 원본 mp4 크기. 정상 비식별본은 50854)
  ```
  같은 우회는 `/stream-url` 로 발급한 서명 URL 경유(무인증 재생)에서도 성립한다.
- **영향**: **CWE-59(Link Following) + CWE-367(TOCTOU) + CWE-359(개인정보 노출)**. 비식별 산출 디렉터리는 외부 비식별 벤더(KPST)가 공유 마운트로 직접 쓰는 영역이고 co-locate 전환으로 판정 대상이 관제 NAS 하위까지 넓어졌다. 그 디렉터리에 심링크 1개를 심으면 **마스킹 전 원본 영상이 "비식별 영상"으로 마킹 화면·검수 화면에 재생**된다. 신고 게이트(`DE_IDNTF_YN`)는 이 경로를 `'Y'` 로 보므로 뒤에서 막아주지 않는다. 자동 테스트에도 이 축이 없다 — `FfmpegFrameExtractorTest` 는 CWE-59 케이스 3건을 갖고 있으나 `VideoStreamServiceTest`(37건)·`DeidentReportStreamGateIT` 에는 심링크 케이스가 **0건**이다.
- **수정 방향(제안)**: `VideoStreamService.resolveSafe` 가 lexical 통과 후 **`VideoArtifactRootResolver.verifyRealPathUnder(resolved, baseDir)`(이미 존재하는 같은 정적 판정기)를 호출**하고, **판정이 돌려준 실경로**로 `StreamMeta.path` 를 채우도록 한다(판정 대상과 사용 대상 일치 — TOCTOU 차단). 실패는 예외가 아니라 다음 base 후보로 넘어가고, 모든 후보 실패 시 기존대로 `NOT_FOUND`(경로 원문 미노출). 판정 결과가 `stream-meta` 캐시에 실리므로 **캐시 채우기(miss) 시점에 1회만** 수행되어 핫패스 비용도 없다. 회귀 가드로 `VideoStreamServiceTest` 에 "비식별 파일이 원본 심링크면 404" · "중간 세그먼트 심링크도 404" 2건을 추가(추출기 테스트와 동일 골격). ⚠ 구현은 하지 않았다.


### [B-ISSUE-82] TC-STREAM-B12 — 성공 procLog 가 없는 영상에도 서명 스트림 URL 이 발급된다(발급 200 / 재생 404 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: 서명 URL 발급은 "재생 가능한 영상"에 대해서만 성공해야 한다. `issueSignedUrl` 의 주석도 *"노출본 대상 URL 발급을 사전 차단한다"* 를 목적으로 명시한다.
- **현재 동작(이슈 내용)**: `issueSignedUrl`(`VideoStreamService:166-206`)은 `DE_IDNTF_YN=='Y'` 와 신고 게이트만 확인하고 **`LS_DEIDENT_PROC_LOG` 성공 행 존재는 확인하지 않는다**. 반면 `stream` 은 `resolveDeidLocation`(`:423-427`)에서 procLog 부재 시 null → 404.
  ```
  rawSn=9114 (de_ident_yn='Y', 성공 procLog 0건)
    GET /v1/videos/9114/stream-url → 200 {"url":"…?exp=…&u=1001&sig=…"}
    GET /v1/videos/9114/stream     → 404 "비식별 처리 미완료"
  ```
- **재현/확인 경로**: 위 curl 2줄(실측 2026-08-01). DB: `select count(*) from ls_deident_proc_log where data_raw_sn=9114 and proc_stts_cd='SUCCEEDED'` → 0.
- **영향**: 보안 취약점 아님(재생 자체는 404 로 막힘). FE 가 URL 을 받아 `<video>` 에 물린 뒤 404 로 실패하므로 사용자에게 원인 불명 재생 실패로 보인다. 또한 발급 성공/실패가 procLog 유무를 노출하지 않는다는 점에서 정보 노출도 아님.
- **수정 방향(제안)**: `issueSignedUrl` 에서 `resolveDeidPath(rawSn) == null` 이면 `stream` 과 동일하게 `NOT_FOUND` 로 거부(같은 메시지 유지 — 상태 오라클 방지). 또는 카탈로그에 "발급은 플래그 축, 재생은 산출물 축"으로 의도된 분리임을 명문화. ⚠ 구현은 하지 않았다.


### [B-ISSUE-83] TC-STREAM-B05 파생 관찰 — `stream-meta` 캐시가 사라진 파일을 가리키면 404 가 아니라 **500 INTERNAL_ERROR**
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 파일이 실재하지 않으면 `NOT_FOUND`(404)로 정규화돼야 한다(`:359-363` 의 규약). 캐시 히트 여부가 응답 코드를 바꾸면 안 된다.
- **현재 동작(이슈 내용)**: 파일 존재 확인은 `resolveStreamMeta`(캐시 **뒤**) 안에서만 이뤄지므로, 캐시가 채워진 뒤 파일이 사라지면 `stream()` 은 그대로 `UrlResource` 를 열고 `ResourceRegionHttpMessageConverter` 가 write 시점에 실패해 500 이 된다.
  ```
  (rawSn=20, 캐시 warm 상태에서 대상 파일 제거)
  GET /v1/videos/20/stream → 500 {"errorCode":"INTERNAL_ERROR"}   … TTL 5분 경과 후 200 복귀
  ```
- **재현/확인 경로**: 캐시를 warm 한 뒤 `LS_DEIDENT_PROC_LOG` 경로가 가리키는 파일을 삭제/이동하고 5분 TTL 안에 재요청. (본 검증에서는 B-ISSUE-81 재현 과정의 부수 효과로 관측했으며 원복 완료.)
- **영향**: 기능 영향만. 정상 운영에서는 비식별본이 불변이므로 발생 빈도가 낮고, 신고/해소/재비식별 3경로는 `StreamMetaCacheEvictor.evictAfterCommit` 로 무효화된다. 다만 **외부 벤더가 파일을 교체/정리하는 형상**(공유 마운트)에서는 재현 가능하며, 5xx 는 모니터링 알람을 오염시킨다.
- **수정 방향(제안)**: `stream()` 에서 `region` 생성 직전에 `Files.isReadable(meta.path())` 를 1회 확인해 실패 시 캐시를 evict 하고 404 로 정규화하거나, `IOException` 을 `NOT_FOUND` 로 매핑. ⚠ 구현은 하지 않았다.


### [B-ISSUE-84] 카탈로그 정합 — TC-STREAM-B15 비고(B-ISSUE-63 미해소) · TC-STREAM-B04 기대값 · 근거 file:line 4건 드리프트
- **심각도**: LOW (카탈로그 자체의 정합성 결함 — 오탐/누락 유발)
- **기대 동작(기대효과)**: 케이스의 기대결과·근거가 현재 구현과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**:
  1. **TC-STREAM-B15 비고 무효** — "⚠ 영상 단위 배정 검증은 없다(B-ISSUE-63 미해소 — 현재 동작 고정)"는 사실이 아니다. `VideoController:210`(stream-url)·`:253`(stream) 에 `labelAccessGuard.verifyRawAccess(rawSn, actor)` 가 **진입부**(캐시 앞)에 배선돼 있고, 실동작으로 WORKER 미배정 영상 403 을 확인했다. `UNCERTAINTIES.md` "미해소 이월 이슈" 표의 B-ISSUE-63 행도 함께 해소 처리 필요.
  2. **TC-STREAM-B04 기대값 무효** — 기대 `FORBIDDEN` 이나 구현은 `NOT_FOUND` 로 **의도적 정규화**(`VideoStreamService:466-468` 주석: 존재/권한 구분을 응답으로 알려주지 않는 편이 원본 미노출 정책과 동급). 기대결과를 `NOT_FOUND` 로 정정할 것.
  3. **근거 드리프트**: TC-BATCH-108 `FfmpegFrameExtractor.java:266` → 실제 seek 계산은 `:248` / TC-BATCH-114 `VideoArtifactRootResolver.java:308-327` → 실제 `readableDeidVideoDirs` 는 `:308-320` / TC-STREAM-B17 `DeidentReportService.java:244-250` → 실제 파일은 `label/service/DeidentReportService.java` 이고 evict 호출은 `:248`(+`:395`,`:454`) / TC-STREAM-B15 근거가 `VideoController.java:@PreAuthorize(stream)` 로 라인 없음 → `:244`(+인가 `:253`).
- **재현/확인 경로**: 위 file:line Read 및 `curl -H "Authorization: Bearer $WORKER" /api/v1/videos/25/stream` → `403 "본인에게 배정되지 않은 영상입니다."`
- **영향**: 카탈로그를 근거로 재검증하는 다음 회차에서 **미해소 이슈를 이월 유지**하거나(B-ISSUE-63) **정상 동작을 FAIL 로 오판**(B04)할 수 있다.
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` 의 해당 4개 셀과 `UNCERTAINTIES.md` 이월 표를 정정. ⚠ 본 검증에서는 파일을 수정하지 않았다.


### [B-ISSUE-85] TC-STREAM-B18 파생 관찰 — 200 전체 응답에도 `Content-Range` 헤더가 붙는다(RFC 7233 비적합)
- **심각도**: LOW
- **기대 동작(기대효과)**: `Content-Range` 는 206(또는 416)에서만 의미가 있다. 200 응답의 `Content-Range` 는 RFC 7233 §4.2 상 무의미하며 일부 엄격한 클라이언트/프록시가 오해할 수 있다.
- **현재 동작(이슈 내용)**: Range 헤더 없는 요청 응답 헤더 실측 —
  ```
  HTTP/1.1 200
  Accept-Ranges: bytes
  Cache-Control: no-store
  Content-Range: bytes 0-50853/50854     ← 200 인데 부착됨
  Content-Length: 50854
  ```
  원인은 Range 무 경로(`VideoStreamService:276-282`)도 전체 파일을 `ResourceRegion` 으로 감싸 반환하고, `ResourceRegionHttpMessageConverter` 가 write 시점에 `Content-Range` 를 무조건 add 하기 때문.
- **재현/확인 경로**: `curl -D - -o /dev/null http://localhost:18081/api/v1/videos/26/stream -H "Authorization: Bearer $REVIEWER"`
- **영향**: 기능 영향 관측되지 않음(브라우저 재생 정상). 규격 적합성·프록시 캐시 상호작용 관점의 위생 이슈.
- **수정 방향(제안)**: Range 무 경로는 `ResourceRegion` 대신 `UrlResource` 자체를 body 로 반환(`ResourceHttpMessageConverter` 경유)하거나, 현 동작을 의도로 문서화. ⚠ 구현은 하지 않았다.

---

## 검증 중 만든 상태 변경 (전부 원복 또는 합성 데이터)

| 대상 | 변경 | 원복 |
|---|---|---|
| `ls_data_raw` rawSn=18 `de_ident_yn` | `'Y'`→`'F'`→`'Y'` (B19/B22 실동작) | ✅ `'Y'` |
| `ls_deident_proc_log` proc_log_sn=21 경로 | EVIL 심링크 경로로 오염 (B04 심링크 실증) | ✅ 원경로 복원, 심링크 삭제 |
| `ls_deident_proc_log` data_raw_sn=9113/9114 | 합성 행 INSERT (B05/B16) | ✅ 전량 DELETE |
| rawSn=34 procLog 경로 | `/etc/passwd` 오염 (TC-BATCH-105) | ✅ 원경로 복원 |
| rawSn=36 비식별 파일 | 원본 심링크로 치환 (TC-BATCH-115 / B-ISSUE-81) | ✅ 실파일 복원 |
| rawSn=36 신고 | `POST /deident-report` 201 → `POST /deident-reports/13/resolve` 200 (B17) | ✅ `'Y'` 복귀, 스트리밍 200 |
| 신규 rawSn 34·36 (`QA0801-B8-105`, `QA0801-B8-115`) | 합성 검증 영상 2건 신규 적재 | 잔존(합성 데이터). rawSn=36 의 `ls_data_src` 5행은 심링크 실험 결과 `de_idntf_src_file_path_nm=NULL` 로 남음 |

## 자동 테스트 커버 대조 (`_raw/test-baseline.md`: backend 4,755 tests / 실패 0 / skip 5)

| 파일 | 관련 케이스 | 비고 |
|---|---|---|
| `batch/step/FfmpegFrameExtractorTest.java` (30여 건) | TC-BATCH-100~118 대부분 | CWE-59 심링크 3건 포함, 전량 통과 |
| `batch/step/FfmpegFrameExtractorDeidPersistIT.java` (2건) | TC-BATCH-107 / 106 | 비식별 경로 실영속·NULL 무회귀 |
| `video/VideoStreamServiceTest.java` (37건) | TC-STREAM-B01~B14, B16, B18, B20~B22 | **심링크(CWE-59) 케이스 0건** ← B-ISSUE-81 의 미탐 원인 |
| `video/DeidentReportStreamGateIT.java` (2건) | TC-STREAM-B17, B19, B22 | 캐시 warm 후 신고 차단 커버 |
| `video/VideoStreamAssignmentAuthorizationTest.java` | TC-STREAM-B15 | B-ISSUE-63 해소 회귀 가드 |
| `video/StreamSignedUrlControllerTest.java` | TC-STREAM-B13, B14 | |
| `common/cache/StreamMetaCacheEvictorTest.java` | TC-STREAM-B16, B17 | |


### [B-ISSUE-123] TC-DEID-084 — 무결성 유예 재확인의 `Thread.sleep` 이 폴링 워커를 점유해, clamp 가 **틱 단위로는 보호되지 않는다**
- **심각도**: LOW
- **기대 동작(기대효과)**: `MAX_RESULT_RECHECK_DELAY_MS`(5s) clamp 의 목적은 코드 주석대로 "오설정(예: 300000)이 폴링 사이클을 정지시키는 것을 막는" 것이다. 폴링은 30초 주기로 계속 돌아야 다른 영상의 완료 감지가 밀리지 않는다.
- **현재 동작(이슈 내용)**: clamp 는 **1건당** 상한일 뿐이고, 유예는 Quartz 워커 스레드를 그대로 잡는 동기 sleep 이다.
  ```java
  // KpstDeidentService.java:712-717
  long delayMs = Math.min(resultRecheckDelayMs, MAX_RESULT_RECHECK_DELAY_MS);
  log.warn("[KpstDeid] deid artifact incomplete on first check — regrace rawSn={} delayMs={}", rawSn, delayMs);
  try { Thread.sleep(delayMs); } catch (InterruptedException e) { … }
  ```
  `poll-batch-size` 기본 200 이므로 한 틱에서 다수 건이 "후보 파일은 있는데 무결성만 실패"에 걸리면 최악 200 × 5s ≈ **16분 40초** 동안 그 틱이 끝나지 않는다. `@DisallowConcurrentExecution` 때문에 그동안 후속 틱은 전부 스킵되고, 정상 완료 대기 중인 다른 영상의 감지가 그만큼 지연된다(공유 NAS 쓰기 지연·마운트 이상처럼 **동시 다발로 발생하기 쉬운** 조건에서 정확히 이 상황이 만들어진다).
- **재현/확인 경로**: export 디렉터리에 `{stem}-mask{ext}` 이름의 512B 미만 파일을 여러 건 만들어 두고 완료(procState=2)를 받게 한 뒤, `[KpstDeid] … regrace rawSn=… delayMs=5000` WARN 건수 × 5s 만큼 다음 `[KpstDeidPoll] polling targets` 로그가 밀리는지 확인.
- **영향**: 가용성/성능(OWASP API4 자원 소진의 완화형). 데이터 손상은 없고 완료 감지 지연에 그친다. 지연은 `poll-timeout-minutes`(180) 안에서 흡수된다.
- **수정 방향(제안)**: 틱 단위 유예 예산(예: 누적 대기 상한 15s)을 두어 초과분은 sleep 없이 즉시 종결하거나, 유예 재확인을 sleep 대신 **다음 틱으로 미루는 상태**(재확인 1회 유예 플래그 + `POLL_LAST_DT` 재클레임)로 바꿔 워커 점유를 없앤다.


### [B-ISSUE-124] TC-DEID-067 — 기대결과 무효: prjId null 은 이제 `markTimeoutIfExpired` 가 아니라 **ACK 대기 유예/회수**다
- **심각도**: LOW (카탈로그 정정)
- **기대 동작(기대효과)**: 카탈로그는 "prjId null(위탁 미완) → `markTimeoutIfExpired`, 외부 미호출"을 기대한다.
- **현재 동작(이슈 내용)**: Phase C-2(논블로킹 제출) 도입 후 prjId null 은 **정상 상태**(ACK 대기)가 되어 의도적으로 타임아웃 예산을 소모하지 않는다.
  ```java
  // KpstDeidentService.java:553-560
  if (withinSubmitAckGrace(procLog)) {           // 기본 180s (submit-ack-grace-sec)
      log.debug("[KpstDeid] skip poll — awaiting submit ack rawSn={}");
      return;                                     // markTimeoutIfExpired 미호출
  }
  boolean reclaimed = txService.failSubmit(procLogSn, rawSn, ACK_MISSING_CODE, "submit ack not received");
  ```
  "외부 미호출"은 그대로 성립하고, 회수 종결은 `claimSubmitFailure`(WAITING + prjId null) 조건부 UPDATE 라 지각 ACK 를 강등하지 않는다 — 기능적으로는 개선이다. 카탈로그 기대결과와 근거 라인(`395-399` → 실제 `541-561`)만 어긋난다.
- **재현/확인 경로**: `KpstDeidentServiceTest` 의 `PhaseC2_prjId_미상은_ACK대기로_보고_유예안이면_아무것도_하지_않는다` / `PhaseC2_ACK가_유예를_넘겨도_안오면_폴러가_ACK_MISSING으로_회수한다`
- **영향**: 검증 정합성. 카탈로그를 그대로 믿으면 정상 동작을 FAIL 로 오판한다.
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` TC-DEID-067 의 기대결과를 "유예 안=no-op(외부 미호출·카운터 미소모) / 유예 초과=`failSubmit(ACK_MISSING)`"으로 갱신하고 근거를 `KpstDeidentService.java:541-561` 로 정정. **구현은 하지 않는다.**


### [B-ISSUE-125] TC-DEID-062 — 기대결과 부분 무효: createProject 실패는 **동기 예외 전파가 없다**
- **심각도**: LOW (카탈로그 정정)
- **기대 동작(기대효과)**: 카탈로그는 "createProject 예외 → `procLog.fail` + `markDeidentified("F")` + **예외 전파**(클래스명만)"를 기대한다.
- **현재 동작(이슈 내용)**: 862ca6d8(외부연동 논블로킹화) 이후 `createProject` 는 구독만 하고 즉시 반환한다. 실패는 완료 핸들러가 기록하며 호출자에게 예외가 가지 않는다.
  ```java
  // KpstDeidentService.java:421-431 (subscribeSubmit)
  kpstClient.createProject(projectReq)
      .subscribe(resp -> … outcomeRecorder.onAccepted(…),
                 err  -> … outcomeRecorder.onSubmitFailed(rawSn, procLogSn, err));
  } catch (RuntimeException e) { outcomeRecorder.onSubmitFailed(rawSn, procLogSn, e); }  // rethrow 없음
  ```
  `onSubmitFailed` → `failSubmit`(TxService:163-184) 이 procLog FAILED + raw `'F'` + (REDEIDENT면) 락 해제를 단일 `REQUIRES_NEW` 로 커밋하므로 실패 흔적과 안전성은 유지된다. **동기 예외가 남아 있는 것은 제출 이전 사전조건 실패뿐**(raw null · 원본 부재 · 부모 경로 없음 · export 디렉터리 생성/검증 실패, Service:294-303).
- **재현/확인 경로**: mock-server 정지 후 신규 적재 → `[KpstDeid] submit failed (async) rawSn=… cause=…` + `[KpstDeid] submit terminal-failed rawSn=… errCd=KPST_SUBMIT_FAILED` 로그, 호출자(`DeidentifyStep`)에는 예외 없음. 테스트 `제출실패는_예외전파대신_완료핸들러가_기록한다_PhaseC2`
- **영향**: 검증 정합성. 정상 동작을 FAIL 로 오판할 소지.
- **수정 방향(제안)**: TC-DEID-062 를 "제출 이전 사전조건 실패 = 원장 별도커밋 종결 + 동기 예외 / 외부 호출 실패 = 비동기 핸들러가 `failSubmit` 으로 종결(예외 전파 없음)" 두 케이스로 분할하고 근거를 `KpstDeidentService.java:294-303, 421-431` · `KpstDeidentTxService.java:163-184` 로 정정.


### [B-ISSUE-126] B-13 전 34건 — 근거 `file:line` 대량 드리프트(2026-07-30 최신화가 862ca6d8 미반영)
- **심각도**: LOW (카탈로그 정합성)
- **기대 동작(기대효과)**: 근거 `file:line` 은 검증자가 곧바로 대조할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `KpstDeidentService.java` 가 862ca6d8(2026-07-30, 논블로킹화)로 약 200줄 늘어나면서 B-13 의 근거 라인이 사실상 전부 어긋났다. 대표 실측 대조:

  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 060 | Service:218-278 | Service:279-306(+TxService:83-91,102-113) |
  | 061 | Service:235-241 | Service:319-323 |
  | 063 | Service:342-390 | Service:488-527 |
  | 064 | Service:438-490 | Service:600-664 |
  | 065 | Service:425-435 | Service:587-598 |
  | 067 | Service:395-399 | Service:541-561 |
  | 068 | Service:401-415 | Service:562-576 |
  | 069 | Service:445-462 | Service:611-625 |
  | 070/084 | Service:464-486,531-556 | Service:626-648,707-730 |
  | 071 | Service:505-506 | Service:665-669 |
  | 072/074 | Service:636-655,693-720 | Service:812-822,869-890 |
  | 073 | Service:657-690 | Service:833-857 |
  | 079 | Service:722-746 · TxService:116-120 | Service:898-911 · TxService:269-273 |
  | 081/082 | Service:225,300-320 / 302-305 | Service:286,446-466 / 447-451 |
  | 083 | Service:516-518 | Service:692-694 |
  | 088/089 | TxService:78-95 | TxService:206-223 |
  | 091/092 | Service:621-634 / 601-616 | Service:797-802 / 785 |
  | 093 | DeidentFrameAttacher:73-93 | DeidentFrameAttacher:99,160-162 |

  정확히 맞은 것은 066(94-98) · 075~078 · 085~087 · 090 정도다.
- **재현/확인 경로**: 위 표의 각 라인을 `Read` 로 대조
- **영향**: 검증 효율/정합성. 라인 대조 실패가 곧 오판으로 이어질 수 있음.
- **수정 방향(제안)**: B-13 섹션의 근거 컬럼을 위 실측 라인으로 일괄 갱신.


### [B-ISSUE-127] `LsDeidentProcLog.markKpstSubmitted` 가 프로덕션 dead code
- **심각도**: LOW
- **기대 동작(기대효과)**: 엔티티 상태 전이 메서드는 실제 전이 경로와 1:1로 대응해야, 이후 유지보수자가 "prjId 는 어디서 기록되나"를 잘못 짚지 않는다.
- **현재 동작(이슈 내용)**: 프로덕션 prjId 기록은 네이티브 조건부 UPDATE `claimSubmitAck`(Repository:143-154)로만 이뤄지고, 엔티티 메서드는 테스트에서만 쓰인다.
  ```
  grep -rn "markKpstSubmitted" backend/src/main → LsDeidentProcLog.java:154 (정의만)
  grep -rn "markKpstSubmitted" backend/src/test → 5파일 8건
  ```
  실제로 이 카탈로그의 TC-DEID-060 기대결과가 이미 그 이름을 근거로 쓰고 있어 오해가 발생한 상태다.
- **재현/확인 경로**: 위 grep
- **영향**: 유지보수성. 기능 영향 없음.
- **수정 방향(제안)**: `markKpstSubmitted` 를 제거하고 테스트 픽스처를 `markKpstSubmitPending` + `recordDatasetId`(또는 리포지토리 클레임)로 대체하거나, 남긴다면 "테스트 픽스처 전용 / 프로덕션 전이는 `claimSubmitAck`" 임을 javadoc 에 명시.

## 5. 부수 기록

- **검증 중 스택 부작용**: 원자 클레임 실증(L3)을 위해 실 WAITING 행 1건(`proc_log_sn=40`)의 `POLL_LAST_DT` 를 갱신했다. 리스(25s) 만료 후 폴러가 재클레임해 정상 완료(`DOWNLOADED/SUCCEEDED`)됨을 확인했다 — 잔존 영향 없음. mock-server 에 검증용 프로젝트 `qa-b13-live-1`(prj_id=14) 및 산출물 `/app/storage/deidentified/videos/qa-b13/clip-9101-mask.mp4` 를 생성했다(DB 미연결, 저작도구 상태 불변).
- **코드/설정/테스트 파일 수정 0건, 빌드/테스트 실행 0건.**
- `KpstDeidentServiceTest.java` 는 파일 인코딩상 `file(1)` 이 `data` 로 판정해 일반 `grep` 이 무결과를 낸다 — `grep -a` 필요(검증 시 함정).



## 클러스터 C — 마킹/라벨링

### [C-ISSUE-01] TC-MARK-45 — AUTO 마킹의 marks 개수에 상한이 없어 MANUAL 의 CWE-770 방어(20000)를 그대로 우회한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `MarkingRequest.marks` 의 `@Size(max=20000)` 는 "한 요청이 만들어낼 수 있는 마킹 수"를 제한해 과대 요청 DoS(CWE-770)를 막는 방어다(`MarkingRequest.java:26-31` 주석이 명시). 마킹 1건은 곧 **프레임 추출 1회(원본+비식별 2벌) + 이미지 파일 2개 + `LS_DATA_SRC` 1행**이 되므로, 생성 경로가 AUTO 든 MANUAL 이든 **동일한 상한이 적용돼야** 방어가 성립한다.
- **현재 동작(이슈 내용)**: 상한은 **요청 바디의 `marks` 배열에만** 걸려 있고, AUTO 가 서버에서 생성하는 marks 에는 어떤 상한도 없다.
  ```java
  // MarkingService.java:274-283  (generateAutoMarks) — 개수 상한 없음
  List<MarkItem> marks = new ArrayList<>();
  int totalFrames = (int) Math.round(durationSec * fps);
  for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) {
      marks.add(new MarkItem(frameIndex, formatTimestamp((int)(frameIndex / fps))));
  }
  return serializeMarks(marks);   // → LS_MARKING.MARK_CN(TEXT) 한 컬럼에 전량 저장 + 응답에 전량 반환
  ```
  하류 소비자에도 상한이 없다 — `FfmpegFrameExtractor.java:245` 는 `for (int i = 0; i < marks.size(); i++)` 로 marks 전건을 그대로 추출한다.
  **실측(raw67, `VDO_LEN_SEC=3600`, fps 30 폴백, `intervalFrames=1`)**:
  ```
  POST /v1/videos/67/markings {"mode":"AUTO","intervalFrames":1}
    → 201, 응답 size_download = 4,425,325 bytes, marks 108,000 건, 소요 0.2s
  DB : select length(mark_cn) from ls_marking where raw_sn=67;  →  4,424,891
  ```
  `intervalFrames` 는 사용자 입력이고 작을수록 결과가 커진다(1이 최댓값 조건). 24시간 영상이면 동일 요청으로 약 2,592,000 marks(≈106MB)가 한 컬럼·한 응답에 실린다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO ls_data_raw (vms_clip_id,vms_cctv_id,evnt_type_cd,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,vdo_len_sec,data_stts_cd)
  VALUES ('C1-BIG','CCTV','EV02000201','ANONY','N','Y','/app/storage/raw/seed/clip-9103.mp4',3600,'MARKING_READY');
  ```
  ```bash
  curl -s -w '%{size_download}\n' -X POST http://localhost:18081/api/v1/videos/{rawSn}/markings \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' \
    -d '{"mode":"AUTO","intervalFrames":1}' -o /dev/null
  ```
- **영향**: CWE-770(Allocation of Resources Without Limits) / OWASP API4. ①단일 요청으로 수 MB~수백 MB 문자열을 힙에 구성 후 TEXT 컬럼 저장 + 응답 전송 ②그 마킹이 배치를 트리거하면 marks 건수만큼 ffmpeg seek 과 이미지 파일 2배가 발생해 **NAS·CPU 증폭**이 훨씬 크다(108,000 marks = 216,000 파일). 인증된 WORKER/REVIEWER 면 누구나 호출 가능하며, 오타 한 번(`intervalFrames:1`)으로도 발생하는 **운영 사고 표면**이다.
- **수정 방향(제안)**: `MarkingService.generateAutoMarks` 에 MANUAL 과 동일한 상한(20000)을 적용해 초과 시 `INVALID_INPUT`(또는 `intervalFrames` 를 상향하도록 안내)으로 거부한다. 상수는 `MarkingRequest` 의 `@Size` 값과 **한 곳에서 공유**(예: `MarkingLimits.MAX_MARKS`)해 두 경로가 드리프트하지 않게 한다. 병행으로 `FfmpegFrameExtractor` 에 방어적 상한을 두는 것도 검토. ⚠ 구현은 하지 않는다.


### [C-ISSUE-02] TC-MARK-06/21 파생 — `intervalFrames` 입력검증이 ffprobe **이후**에 수행돼, 명백히 무효한 입력에도 프로브 서브프로세스가 실행된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `MarkingService` 는 "고비용 ffprobe 는 **인가·프리컨디션 통과 이후에만**" 실행한다는 불변식을 Javadoc(`:39-44`, `:94-98`)으로 명문화하고 `MarkingPrecheckReader` 로 구현했다(HIGH — CWE-862/400 회귀 봉인, TC-MARK-21). 같은 취지라면 **요청 자체가 무효라 어차피 400 이 될 입력**은 프로브보다 먼저 걸러져야 한다 — 프로브 결과를 쓸 곳이 없기 때문이다.
- **현재 동작(이슈 내용)**: `intervalFrames` 에는 Bean Validation 이 없고(`MarkingRequest.java:23` — `Integer intervalFrames,` 만 선언, `@Positive`/`@Min` 부재), 검증은 **persist 트랜잭션 안**에서야 수행된다(`MarkingService.java:196-198`). 그런데 프로브는 그보다 앞선 오케스트레이션 2단계에서 실행된다.
  ```java
  // MarkingService.java:121-126  — mode 만 보고 프로브를 먼저 태운다(intervalFrames 유효성 미확인)
  Integer durationSec = null;
  if (MODE_AUTO.equals(req.mode())) {
      durationSec = durationResolver.resolveDurationSec(rawSn);   // ← ffprobe 폴백 가능
  } else if (MODE_MANUAL.equals(req.mode())) { ... }
  // :196-198 — 여기서야 400
  if (req.intervalFrames() == null || req.intervalFrames() <= 0) { throw new CustomException(INVALID_INPUT, ...); }
  ```
  **실측(raw69: `VDO_LEN_SEC=NULL` + 메타 없음 → 3단계 프로브 도달 조건)**:
  ```
  POST /v1/videos/69/markings {"mode":"AUTO","intervalFrames":0}   → 400
  로그: [Video][Probe] ffprobe empty output path=9bc9c203   ← 400 을 내기 전에 프로브가 실행됨
  ```
  (대조군: 인가 실패·활성 중복 409 케이스에서는 프로브 로그 0건 — TC-MARK-21/39 확인. 즉 **입력 유효성 축만 이 보호에서 빠져 있다**.)
- **재현/확인 경로**:
  ```bash
  # VDO_LEN_SEC NULL + video.duration_ms 메타 없음 + 파일 부재인 rawSn 준비 후
  curl -s -X POST http://localhost:18081/api/v1/videos/{rawSn}/markings -H "Authorization: Bearer $REVIEWER" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":0}'
  docker logs klid-backend --since 10s | grep -E "Probe|ffprobe"
  ```
- **영향**: CWE-770 / OWASP API4(Unrestricted Resource Consumption). 인증된 사용자가 `intervalFrames:0` 같은 상수 바디를 반복 전송해 **거부될 요청마다 ffprobe 서브프로세스(최대 수십 초)** 를 띄울 수 있다. TC-MARK-21 이 봉인한 표면(미인가 트리거)과 동일한 자원 축이며, 방어가 인가 축에만 적용돼 **불완전**하다. 심각도는 인증 필요·요청당 1프로세스라는 점에서 MEDIUM.
- **수정 방향(제안)**: `MarkingRequest.intervalFrames` 에 `@Positive`(또는 `@Min(1)`) 를 붙여 컨트롤러 `@Valid` 단계(=프로브 이전)에서 400 이 나가게 한다. 서비스의 `:196-198` 백스톱은 직접 호출 경로 보호용으로 **유지**한다(TC-MARK-05/06 기대 메시지 보존을 위해 메시지 정합 필요). 동일 취지로 `MANUAL` 도 `marks` 빈값 검증을 `@NotEmpty` 로 앞당기는 것을 함께 검토. ⚠ 구현은 하지 않는다.


### [C-ISSUE-21] TC-LABEL-15 — 좌표 배열 원소가 null 이면 형식 검증을 통과해 NPE 500 (2-튜플 경로에만 방어 부재)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 좌표 **형식 위반은 전부 400(INVALID_INPUT)** 이어야 한다. `LabelService.validateSkeletonPoints` 는 이 위험을 코드 주석으로 명시하고 있다 — *"JSON-valid 하지만 원소가 null 인 경우(`[[10,20,null],...]`)는 Double→double 언박싱 NPE(→GlobalExceptionHandler catch-all 500)를 유발한다. 언박싱 전에 400 으로 fail-secure 거부하여 계약(형식 위반=400)과 일치시킨다."* 즉 **의도된 계약은 400** 이며, 이는 CWE-20 입력 방어선 + OWASP A10:2025(Mishandling of Exceptional Conditions, fail-secure) 요구이기도 하다.
- **현재 동작(이슈 내용)**: SKELETON 경로에만 null 원소 가드가 있고, **BBOX/POLYGON/SEGMENT/TRACK 이 타는 2-튜플 경로에는 없다.** `pair.size() != 2` 만 검사한 뒤 곧바로 언박싱한다.

  `backend/src/main/java/kr/co/cudo/authoring/label/service/LabelService.java:719-728`
  ```java
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) {
          throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
      }
      double x = pair.get(0);   // ← pair.get(0) == null 이면 언박싱 NPE
      double y = pair.get(1);
      if (x < 0 || y < 0) { ... }
  ```
  대조군 — SKELETON 경로 `LabelService.java:805-811` 는 동일 상황을 400 으로 거부한다.

  **실동작 근거**
  ```
  PUT /api/v1/frames/313/labels
  {"items":[{"lblTypeCd":"BBOX","label":"n","points":[[null,5],[10,10]]}]}
  → 500 {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}

  (대조) lblTypeCd:"SKELETON" + [[10,20,null], …x17]
  → 400 {"errorCode":"INVALID_INPUT","message":"키포인트 좌표에 null 원소가 있습니다."}
  ```
  backend 컨테이너 로그:
  ```
  2026-08-01 23:22:27.292 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  java.lang.NullPointerException: Cannot invoke "java.lang.Double.doubleValue()"
      because the return value of "java.util.List.get(int)" is null
  ```
  신규(`id=null`)·기존(`id` 지정) **양쪽 경로 모두 500**(기존 경로도 `validatePoints` 를 거친다).
- **재현/확인 경로**
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' \
    -d '{"sub":"1001","role":"REVIEWER","channel":"INTERNAL"}' -o /tmp/t.json; \
    python3 -c "import json;print(json.load(open('/tmp/t.json'))['data']['token'])")

  # {srcSn} 은 배정/REVIEWER 접근 가능한 아무 프레임 (예: 313)
  curl -i -X PUT http://localhost:18081/api/v1/frames/313/labels \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","label":"n","points":[[null,5],[10,10]]}]}'
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR  (기대: 400 INVALID_INPUT)
  ```
- **영향**
  - **계약 위반**: API 규약상 입력 형식 오류는 400 인데 500 이 나가 FE 가 "서버 장애"로 오분류한다(재시도 유발 가능).
  - **CWE-20**(Improper Input Validation) / **CWE-248**(Uncaught Exception) / OWASP **A10:2025** fail-secure 위반. `points` 는 인증된 내부 채널(REVIEWER/WORKER)에서만 오므로 외부 익명 DoS 는 아니나, 단순 요청으로 반복 500 + ERROR 스택 로그를 유발할 수 있어 **로그 오염·알람 노이즈**로 이어진다(운영 ERROR 레벨은 알림 대상).
  - 응답 본문에 스택트레이스는 노출되지 않아 CWE-209 정보노출은 없음(`GlobalExceptionHandler` 가 일반 메시지로 마스킹).
  - 유입 경로: FE 캔버스 버그·부분 직렬화·외부 도구로 만든 라벨 임포트 등에서 실제로 발생 가능.
- **수정 방향(제안)** — ⚠ 구현하지 않음
  `LabelService.validatePoints`(`LabelService.java:719-728`)의 2-튜플 루프에서 **언박싱 전에 null 원소를 400 으로 거부**한다. SKELETON 경로(`:805-811`)와 동일한 메시지 체계·동일한 fail-secure 순서를 쓴다.
  ```java
  Double xBox = pair.get(0);
  Double yBox = pair.get(1);
  if (xBox == null || yBox == null) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "좌표에 null 원소가 있습니다.");
  }
  double x = xBox; double y = yBox;
  ```
  더 근본적으로는 두 경로가 같은 실수를 반복하지 않도록 **null-safe 언박싱 헬퍼 1개로 통합**하는 편이 낫다(현재는 SKELETON 만 고쳐진 비대칭이 그대로 남아 있다).
  회귀 테스트 제안: `LabelControllerTest` 또는 `LabelSaveGuardsIT` 에
  `"좌표_배열_원소가_null이면_400"`(BBOX/POLYGON 각 1건, 신규·기존 id 양 분기) 추가.
  ※ `validateWithinBounds`(`:756-763`)는 이미 null 을 `continue` 로 흘리므로 추가 수정 불필요.

---

## 미해소 이월 확인

| 이슈 | 상태 | 확인 근거 |
|---|---|---|
| **C-ISSUE-23** (R7 손상 JSON 회귀 테스트 부재) | **미해소 이월** | `grep -rln "손상\|corrupt\|malformed" src/test/java` 결과 중 `pointsEqual`/`normalizePoints` 손상 `point_cn` 을 다루는 테스트 0건. `LabelServiceFullReplaceIntegrationTest#손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다` 는 **이력 조회 diff 파싱** 테스트로 별건. 동작 자체는 이번에 실동작으로 정상 확인(TC-LABEL-30 PASS)이나 회귀 가드는 여전히 없음 |

---

## 검증 중 생성/정리한 테스트 데이터

기존 데이터는 변경하지 않았고, 검증용으로 만든 것은 아래와 같이 정리했다.

| 항목 | 처리 |
|---|---|
| `ls_data_lbl` src 313·314 테스트 라벨(594·596·597·598) | API `items:[]` 로 전량 삭제 완료 |
| `ls_label` 마스터 21(`qa-c2-lbl`)·22(`qa-c2-race`) | API DELETE(soft delete, `use_yn='N'`) 완료 |
| `ls_auth_work_lock` `lck_id='qa-c2-lock-test'`(raw27) | `lck_stts_cd='RELEASED'`, `rmv_rsn='QA_C2_CLEANUP'` 로 해제 완료 |
| `ls_data_lbl_attr_val` atrb_vl_id=2 (TC-11 용) | 대상 라벨 삭제 시 애플리케이션이 동반 삭제 |
| src 303 잔존 라벨 592(`chg32`) | **잔존** — raw27 이 타 에이전트의 비식별 신고로 작업락 상태라 API 정리 불가. `lbl_id` 는 null 로 원복 |
| src 296(raw26, APPROVED) | 라벨 추가 후 `items:[]` 로 원상복구(라벨 0건). 단 `ls_mon_noti_acml` #31 및 export 재생성 트리거는 발생함 |


### [C-ISSUE-42] TC-LABEL-60/72 — 검출 건수만큼 `findLabelIdByDtctType` DB 왕복(N+1), 캐시 없음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 화면 툴바에서 프레임마다 호출되는 대화형 경로다. `rules/performance.md` 는 반복 단건 조회를 금지하고 일괄 조회를 요구한다. 검출 N건이면 매핑 조회도 1회로 끝나야 한다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:423-431`(BBOX) 과 `:334`(POLYGON) 이 **검출 1건마다** `labelMasterService.findLabelIdByDtctType()` 를 호출한다. 이 메서드는 `@Transactional(readOnly=true)`(`LabelMasterService.java:150-157`) 라 **호출마다 트랜잭션·커넥션 획득 + `SELECT ... WHERE dtct_type_cd=? AND use_yn='Y'` 1회**가 발생한다. 캐시(`@Cacheable`) 없음. 같은 요청에서 이미 `mappedDetectClasses()`(`:166-168`)로 전체 매핑을 읽었는데도 재사용하지 않는다.
  - 상한: BBOX 는 YOLO 검출 수 제한이 없어 혼잡 프레임이면 수십~수백 회. POLYGON 은 `maxBoxes`(최대 100)까지.
- **재현/확인 경로**: 가중치 탑재된 ai-server 로 `POST /v1/frames/{srcSn}/autolabel` 호출 후 `p6spy`/`hibernate.SQL` DEBUG 로 `ls_label` 조회 횟수 = 검출 건수인지 확인. 현 스택은 검출 0건이라 실측 불가(정적 판정).
- **영향**: 성능/자원(커넥션 풀). 서비스가 `non-transactional` 을 택한 이유가 "AI 블로킹 호출 중 HikariCP 커넥션 미점유"(`:48-50`)인데, 응답 조립 단계에서 커넥션을 N회 붙잡아 그 의도를 부분 상쇄한다. 동시 라벨러 다수 + 혼잡 프레임에서 풀 압박.
- **수정 방향(제안)**: `LsLabelRepository` 에 `findByDtctTypeCdInAndUseYn(Collection<String>, String)` 를 추가해 검출 라벨 집합을 **1회 조회 → `Map<String,Long>`** 으로 만들고 루프에서 조회. 또는 `mappedDetectClasses()` 를 `Map<String,Long>`(코드→labelId) 반환으로 확장해 이미 읽은 결과를 재사용. 구현은 하지 않음.


### [C-ISSUE-44] TC-LABEL-60/68/69/71~76 — ai-server 모델 가중치 미탑재로 검출 의존 분기 10건 실동작 검증 불가
- **심각도**: MEDIUM (환경/검증 커버리지 결함 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 온라인 오토라벨의 핵심 위험 구간(좌표 정규화 400/clamp/스킵, 폴리곤 상한·예산·부분실패·TOCTOU)은 **실제 검출 결과 위에서** 판정돼야 한다. 이 프로젝트의 실패 모드는 "코드가 있으니 PASS"이므로, 검출이 0건이면 해당 단언들은 반증 시도 자체가 불가능하다.
- **현재 동작(이슈 내용)**: `klid-ai-server` 는 `AI_MOCK_MODE=false` 이나 YOLO 가중치 파일이 없어 mock 으로 폴백한다.
  ```
  ai-server: WARNING:app.routers.yolo:[DETECT:yolox][MOCK] returning mock track
             (model not loaded or AI_MOCK_MODE=true) reason=weights_missing
  backend  : [Autolabel] mock response — skip detection srcSn=296 source=mock reason=weights_missing
  ```
  BE 는 mock 을 정상적으로 차단(`:246-251`, TC-67 PASS)하므로 그 **뒤에 있는** `normalizeDetections`(`:256`) · 빈검출 분기(`:259`) · `polygonAutolabel`(`:293~`) 전부가 도달 불가 코드가 된다. `pipeline-drive.md` 의 배치 YOLO 도 같은 사유로 `yoloCount=0`.
- **재현/확인 경로**: `curl -X POST .../v1/frames/296/autolabel` → 항상 `detectedCount:0` + `"AI 모델 미로드 — 결과 신뢰 불가"`. `docker logs klid-ai-server | grep weights_missing`.
- **영향**: 기능/검증. 좌표 clamp·퇴화 스킵·all-or-nothing 400·폴리곤 예산/상한/부분실패/TOCTOU 등 **10개 케이스가 실환경에서 한 번도 실행된 적이 없다**. 단위테스트(mock 주입)로만 커버되므로 실제 ai-server 응답 스키마 변화(필드명·좌표 순서)에 대한 회귀 감지력이 없다 — 본 검증 기준의 "self-fill / 계약 불일치" 관심사와 직결.
- **수정 방향(제안)**: ①로컬 검증 스택에 YOLOX ONNX 가중치를 마운트하거나 다운로드 스텝을 `docker-compose.local.yml` 에 추가 ②또는 mock-server 처럼 **결정적 검출 결과를 돌려주는 ai-server 스텁 모드**(mock 플래그 없이 고정 좌표 반환)를 검증 전용으로 도입해 위 분기를 실제로 태울 것. 구현은 하지 않음.


### [C-ISSUE-61] TC-LABEL-108/109 (인접) — `GET /v1/deident-reports` 정렬 키 미검증 → 미등록 키에 500 + 엔티티 필드 열거 오라클
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목록 API 의 정렬 키는 **allowlist 매핑으로만** 해석하고 개수 상한을 둔다(CLAUDE.md "목록 화면 정렬·필터 정책", CWE-89/CWE-770). 미등록 키는 그 엔드포인트의 확정 규약(strict 400 또는 lenient 200 폴백) 중 하나로 끝나야 하며, **사용자 입력으로 500 이 나면 안 된다**(오류 모니터링 오염 + 가용성).
- **현재 동작(이슈 내용)**: `DeidentReportController.java:76` 이 `@PageableDefault(...) Pageable` 을 **그대로** `DeidentReportService.listReports`(`:417-423`) → `reportRepository.findByReportSttsCd(normalized, pageable)` 로 넘겨, Spring Data 가 엔티티 프로퍼티를 직접 해석한다. 미등록 키면 `PropertyReferenceException` 이 `GlobalExceptionHandler` 의 unhandled 분기로 떨어져 **500**.
  ```
  GET /v1/deident-reports?sort=nosuchfield,desc   → 500 INTERNAL_ERROR
  GET /v1/deident-reports?sort=rawSn,desc         → 500  (엔티티 필드명은 dataRawSn)
  GET /v1/deident-reports?sort=rsn,desc           → 200  (신고 사유 컬럼으로 정렬됨)
  GET /v1/deident-reports?sort=reportSttsCd,asc   → 200
  로그: PropertyReferenceException: No property 'rsn;drop' found for type 'LsDeidentReport'
  ```
  200/500 차이가 **엔티티 내부 필드명 존재 여부를 알려주는 오라클**이 된다(CWE-209 계열). 응답 본문 자체에는 스택트레이스가 없어 유출은 없다.
  ※ ★2(작업목록 strict 400 vs 검수목록 lenient 200)는 `/v1/tasks/board*` 와 `/v1/reviews*`·`/v1/videos` 만 정의한다. 이 엔드포인트는 **두 정책 어느 쪽도 적용되지 않은 세 번째 목록 API** 이므로 "비일관성 재보고"가 아니라 **미배선 갭**이다.
- **재현/확인 경로**: 위 curl 4줄(REVIEWER 토큰). `size=100000` 은 100 으로 캡됨(=DoS 방어는 정상).
- **영향**: 사용자 입력으로 5xx 발생(가용성·모니터링 노이즈), 정렬 키를 통한 엔티티 스키마 열거, 정렬 대상이 allowlist 밖(예: 신고 사유 텍스트 `rsn`)까지 열려 인덱스 없는 컬럼 정렬 유발 가능.
- **수정 방향(제안)**: 다른 목록 API 와 동일하게 `sort` 를 **allowlist 매핑**(`reportDt`/`status`/`rawSn` → 엔티티 프로퍼티)으로 변환하는 정규화를 컨트롤러/서비스 경계에 추가하고, 이 엔드포인트는 **변경 전에도 `sort` 를 받아 500 이었던** 축이므로 ★2 기준상 **strict 400** 이 자연스럽다(검수목록형 lenient 폴백을 택할 경우 그 근거를 UNCERTAINTIES 에 명시). 추가로 `PropertyReferenceException` 을 `GlobalExceptionHandler` 에서 400 으로 매핑해 전역 fail-safe 를 둔다.


### [C-ISSUE-82] TC-SAM2-23 — SAM2 track 의 **외부 응답** 검증이 요청 검증과 동일 코드라 400 으로 나가고, 정점 수·이미지 경계·score 를 검증하지 않는다

> ⚠ 이 번호는 본 파트 신규 번호다. `UNCERTAINTIES.md` 이월표의 구 `C-ISSUE-82`(dead `common/util/TrackInterpolator`)와 무관.

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과는 **502 EXTERNAL_API_ERROR**. 외부 시스템(ai-server)의 잘못된 응답을 **클라이언트 입력 오류(400)로 오귀속하면 안 된다** — FE·운영자가 "내가 잘못 보냈다"로 오진단해 실제 장애(모델 이상·계약 드리프트)를 놓친다. segment 경로는 이미 502 로 처리한다(`Sam2SegmentService.java:159-175`). 또한 "외부 응답 불신" 원칙상 **정점 수 하한·이미지 경계 상한·score 범위**를 segment 와 동등하게 검사해야 한다.
- **현재 동작(이슈 내용)**: 요청 검증과 응답 검증이 **같은 메서드**를 공유한다.
  ```java
  // Sam2TrackService.java:71   (요청)
  validatePolygon(req.prevPolygon(), "prevPolygon");
  // Sam2TrackService.java:119  (외부 응답) — 같은 메서드
  validatePolygon(aiRes.polygon(), "ai-server polygon");

  // Sam2TrackService.java:188-204
  private void validatePolygon(List<List<Double>> polygon, String fieldName) {
      if (polygon == null || polygon.isEmpty())            // ← 정점 수 하한(3) 검사 없음
          throw new CustomException(ErrorCode.INVALID_INPUT, …);   // ← 400
      … if (… x < 0 || y < 0) throw new CustomException(ErrorCode.INVALID_INPUT, …);  // ← 상한 검사 없음
  }
  ```
  결과 3가지 갭:
  ① **응답코드 불일치** — `ErrorCode.INVALID_INPUT` = `HttpStatus.BAD_REQUEST`(`ErrorCode.java:6`), 기대는 `EXTERNAL_API_ERROR` = `BAD_GATEWAY`(`ErrorCode.java:28`). 프로젝트 테스트가 현행을 고정 중 — `Sam2TrackServiceTest#aiPolygonInvalid400`.
  ② **정점 수 미검증** — ai-server 가 1~2점 폴리곤을 돌려줘도 통과해 FE 로 나간다(segment 는 `MIN_POLYGON_POINTS=3` 으로 502).
  ③ **경계 상한·score 미검증** — segment 는 `x>imgWidth` 502 + `clampScore` [0,1] 4자리 반올림. track 은 둘 다 없어 실측 응답이 `"score":4.187454578641336E-6` 로 원본 노출:
  ```
  POST /api/v1/frames/1/sam2-track → 200
  {"tracked":[{"srcSn":2,…,"score":4.187454578641336E-6,"shapeType":"POLYGON"}]}
  ```
- **재현/확인 경로**:
  - 코드 대조: `Sam2TrackService.java:119,188-204` vs `Sam2SegmentService.java:129,159-175`.
  - score 미클램프 실동작: `curl -X POST -H "Authorization: Bearer <WORKER>" -H 'Content-Type: application/json' -d '{"srcSn":1,"trackId":"t","prevPolygon":[[10,10],[100,10],[100,100]],"label":"p","nextSrcSns":[2]}' localhost:18081/api/v1/frames/1/sam2-track` → 응답 `score` 필드 확인.
  - ①②는 ai-server 응답 조작이 필요(스텁) — 단위테스트 레벨 재현.
- **영향**: 기능/운영(오진단 유도) + 데이터 정합(퇴화 폴리곤·경계 밖 좌표가 FE 작업본에 유입 → 이후 `PUT /labels` 저장 시 `validateWithinBounds` 가 400 을 내 사용자에게 "저장 실패"로 나타남 — 원인이 상류에 있는데 하류에서 터진다). 보안 관점은 낮음(좌표 자체는 인가된 프레임 것).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `validatePolygon(polygon, fieldName)` 을 **요청용/응답용 2개로 분리** — 응답용은 `ErrorCode.EXTERNAL_API_ERROR` + `MIN_POLYGON_POINTS(3)` + 이미지 실측 경계 상한(track 도 `FrameImageEncoder`/`FrameBoundsResolver` 로 치수 확보 가능) + `clampScore` 를 segment 와 동일 적용. 기존 `#aiPolygonInvalid400` 테스트는 기대값을 502 로 갱신. 판정 로직을 두 서비스가 공유하도록 공용 검증기로 추출하는 편이 재발 방지에 낫다.

---


### [C-ISSUE-83] TC-SAM2-01 (부수) — `Sam2SegmentService` 가 `@Transactional` 을 유지해 AI 블로킹 호출 내내 DB 커넥션을 점유한다 (track 은 이미 제거됨)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SAM2 분할은 **DB write 가 없는 stateless 프록시**다(클래스 javadoc `:40` "BE 는 DB 저장하지 않는 stateless 프록시"). 파일 I/O + base64 + **최대 60s 블로킹 AI 호출** 구간에서 control HikariCP 커넥션을 쥐고 있으면 안 된다. `Sam2TrackService` 는 이미 그렇게 고쳤고 그 이유를 코드에 남겼다 — `Sam2TrackService.java:34-36`: *"비트랜잭셔널(F-1 커넥션풀 고갈 방지): DB write 가 없으므로 @Transactional 을 제거했다. AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다."* 프로젝트 규약도 같다(`CLAUDE.md` — "프레임 이미지 서빙은 트랜잭션 밖에서 파일 I/O 를 한다", "커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아로 간다 — 전례 있음").
- **현재 동작(이슈 내용)**: segment 만 트랜잭션이 남아 있다.
  ```java
  // Sam2SegmentService.java:58-62
  @Service
  @RequiredArgsConstructor
  @Transactional(value = "controlTransactionManager", readOnly = true)   // ← 남아 있음
  public class Sam2SegmentService {
      public Sam2SegmentResponse segment(...) {   // :84  — 프록시 경유 public
          accessGuard.verifyAccess(...);          // :86  DB 접근 → 커넥션 획득
          …
          aiRes = aiServerClient.segment(aiReq).block();   // :114  최대 60s 블로킹 (AiServerClient.java:73)
  ```
  **실측(반증 시도로 확인)** — segment 6건 동시 호출 중 `pg_stat_activity` 샘플링:
  ```
  ### segment ×6 동시
   state               | count
   active              |     1
   idle                |     4
   idle in transaction |     6     ← 6건 전부 AI 호출 동안 커넥션 점유
  ### track ×6 동시 (대조군)
   state  | count
   active |     1
   idle   |    10                  ← idle in transaction 0건
  ```
  운영 풀은 `application-prd.yml:20,27` `maximum-pool-size: 20`(control/portal 각각). 라벨링 작업자 20명이 동시에 클릭 분할을 하면 control 풀이 소진되며, ai-server 가 느려질수록(60s 타임아웃) 점유 시간이 그대로 늘어난다.
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3 4 5 6; do curl -s -o /dev/null -X POST -H "Authorization: Bearer <WORKER>" \
    -H 'Content-Type: application/json' -d "{\"srcSn\":$i,\"points\":[[50,50]]}" \
    localhost:18081/api/v1/frames/$i/sam2-segment & done
  sleep 1.2
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select state, count(*) from pg_stat_activity where datname='klid_system' group by 1;"
  ```
- **영향**: 가용성/성능(CWE-400 Uncontrolled Resource Consumption · OWASP API4:2023). 커넥션 기아 시 SAM2 와 무관한 API(라벨 저장·검수·배치)까지 동반 지연/실패한다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `Sam2SegmentService` 의 클래스 `@Transactional` 을 제거하고, DB 조회(`accessGuard.verifyAccess` + `srcRepository.findById` + `resolveFrameImageForInference` 의 게이트 조회)만 별도 `@Transactional(readOnly)` 조회 빈으로 분리한 뒤 **파일 I/O·base64·AI 호출은 트랜잭션 밖**에서 수행한다 — `FrameImageLookupService` 가 이미 같은 목적으로 존재하므로 그 패턴을 따르면 된다. ⚠ 자기호출(self-invocation)로 프록시를 우회하면 효과가 없다.

---


### [C-ISSUE-101] TC-TRACK-12~23 — CoordinateTransformer·MaskRleConverter 프로덕션 미사용(dead code)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md "CVAT 포팅 전략" Phase 6에 명시된 "MASK ↔ RLE ↔ Polygon 변환"(portable-modules/02)과 "좌표 변환/회전 유틸"(portable-modules/06)은 실제 라벨링 파이프라인(SAM2 분할 결과 저장, 캔버스 회전 등)에서 소비되어야 포팅 목적이 달성된다.
- **현재 동작(이슈 내용)**: `grep -rln "MaskRleConverter" backend/src/main` → 결과가 `MaskRleConverter.java` 자기 자신 1건뿐(호출부 0건). `grep -rln "CoordinateTransformer" backend/src/main` → 마찬가지로 자기 자신 1건뿐. 두 클래스 모두 `backend/src/test/.../common/util/{MaskRleConverterTest,CoordinateTransformerTest}.java` 에서만 참조된다. 실제 SAM2 분할 결과는 `Sam2SegmentService.java:119-152`에서 **polygon을 직접** 응답에 담아 반환하고 RLE 인코딩 경로를 거치지 않는다(`aiRes.polygon()` → `PolygonSimplifier.simplify` → 그대로 반환). 회전(rotate)은 프론트엔드 `frontend/src/features/label/canvas/utils/coordinateTransformer.ts`에 별도로 구현되어 있고, 백엔드는 라벨 엔티티에 회전각(angle/rotation) 필드 자체가 없어(grep 결과 0건) 서버측 회전 재계산이 필요한 지점이 없다.
- **재현/확인 경로**:
  ```
  cd backend/src/main && grep -rln "MaskRleConverter" .   # → 자기 파일만
  cd backend/src/main && grep -rln "CoordinateTransformer" .  # → 자기 파일만
  ```
- **영향**: 기능 결함은 아님(아무도 호출하지 않으므로 런타임 오류 없음). 다만 (1) 문서(CLAUDE.md)가 약속한 포팅 산출물이 실제로는 고아 코드로 남아 있어 유지보수 시 혼동 유발, (2) `MaskRleConverter`에는 미사용 코드 특유의 미검증 극단 입력 처리 결함이 존재 — `maskToRle`에서 `mask`가 `height>0, width==0`(예: `new boolean[3][0]`)인 경우 `mask.length==0` 조기 반환 가드를 통과한 뒤 `total=0`으로 `flat=new boolean[0]`가 되고, 이어지는 `flat[0]` 접근(:53,57)에서 **미제어 `ArrayIndexOutOfBoundsException`**이 발생한다(`IllegalArgumentException`이 아님 — 이 클래스의 나머지 가드는 전부 IAE로 통일돼 있는 것과 대비). 현재는 호출부가 없어 악용 경로가 없으나, 향후 이 클래스를 실제로 SAM2 마스크 저장에 연결하면 그대로 결함이 된다.
- **수정 방향(제안)**: ① 실제로 RLE 저장/회전 기능이 필요 없다면 두 클래스와 테스트를 정리(제거) 또는 카탈로그·CLAUDE.md 포팅 전략 표에서 "완료" 대신 "구현만 되고 미연동" 상태로 명시. ② 향후 연동 계획이 있다면 `MaskRleConverter.maskToRle`의 `width==0` 극단 케이스에 `IllegalArgumentException` 가드 추가(다른 가드와 일관되게).

## 카탈로그 근거 드리프트

- **TC-TRACK-13 비고 정정**: "※전용 단위테스트 여전히 부재(C-ISSUE-81)"는 `CoordinateTransformerTest.java`의 `rotateRejectsNullPoints`/`rotateRejectsNullCenter`/`rotateChecksPointsGuardBeforeCenterGuard` 3건과 불일치. git log 확인 결과 이 테스트 메서드들은 프로젝트 초기 커밋(2fd78c48, `feat(infra)`)부터 이미 존재했으며 이후 수정 이력이 없다. C-ISSUE-81("MaskRle/Coordinate 가드 테스트 0%")은 최소 CoordinateTransformer에 대해서는 사실이 아니며, MaskRleConverterTest 역시 null/DoS/경계값 가드 테스트를 풍부하게 보유(9개 가드 테스트 확인). C-ISSUE-81 클레임 자체의 재검증(또는 폐기) 권고.


### [C-ISSUE-123] TC-LABEL-90/91 인접 — Swagger 문서가 폐기된 "라벨 삭제" 정책을 그대로 광고 (문서/코드 드리프트)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 2026-07-27 사용자 확정으로 **비식별 신고는 라벨을 삭제하지 않고 보존**한다(구 "전체 라벨 스냅샷 후 전량 삭제 + `SAVE_REASON='DEIDENT_REPORT'`" 폐기, TC-LABEL-90·99 에 반영). 이 API 를 소비하는 FE·관제·외부 통합자가 보는 **OpenAPI 설명이 실제 동작과 일치**해야 한다.
- **현재 동작(이슈 내용)**: 서비스 본체는 정책 반전이 반영돼 있으나(`DeidentReportService.java:197-205` 주석 + 실제 삭제 코드 부재, 실동작에서도 라벨 보존 확인), **컨트롤러의 Swagger 문구는 구 정책 그대로**다.
  ```java
  // DeidentReportController.java:44-48  @Tag(description = …)
  "신고 즉시 영상이 잠기고(LOCKED_FOR_REDEIDENT) 현재 작업(영상 전체 라벨)을 " +
  "복원 가능 스냅샷 기록 후 삭제한다. 재처리는 외부 솔루션 수동 비식별화 흐름으로 진행되며, "

  // DeidentReportController.java:82-85  @Operation(description = …)  — POST /v1/labels/{srcSn}/deident-report
  "신고 시 해당 영상 전체 라벨을 복원 가능 스냅샷으로 기록 후 삭제하고 영상을 잠근다. "
  ```
  실동작 반증: rawSn 26/27 신고 후 로그가 `labelsPreserved=true` 를 남기고 `LS_LABEL_VERSION` 신규 스냅샷 0건 — **문서만 틀렸다**. 참고로 영상 단위 진입점(`:107-111`)의 설명은 최신 정책으로 갱신돼 있어 **같은 컨트롤러 안에서 두 엔드포인트 설명이 서로 모순**된다.
- **재현/확인 경로**: `GET /api/v3/api-docs` 또는 Swagger UI 에서 `DeidentReport` 태그 설명과 `POST /v1/labels/{srcSn}/deident-report` 설명 확인 → "스냅샷 기록 후 삭제" 문구 노출.
- **영향**: 기능·보안 결함은 아니나 **API 계약 오도**다. 이 설명을 믿는 소비자는 신고 후 라벨이 사라진다고 가정해 방어적 재조회·재작성 로직을 넣거나(불필요), 반대로 "삭제됐을 테니 안전하다"고 오판할 수 있다. 또한 `CLAUDE.md` 의 문서 동기화 규칙(기능 변경 시 같은 커밋에서 문서 갱신) 위반 잔재다.
- **수정 방향(제안)**: `DeidentReportController` 의 `@Tag(description)`(`:44-48`)과 srcSn 경로 `@Operation(description)`(`:82-85`)을 현행 정책으로 교체 — "라벨은 **보존**하며 신고 구간 동안 라벨 조회가 412 로 차단되고, resolve 시 보존된 라벨을 그대로 재사용한다 / 부수효과 = 작업락 + `DE_IDNTF_YN='F'` + 개인정보 3필드 리셋 + APPROVED 영상 `TASK_MODIFIED(META_UPDATED)` 통지". 이미 최신인 영상 단위 진입점(`:107-111`) 문구를 기준으로 정렬하면 된다. ⚠ 구현하지 않음.

---

## 5. 확정 정책 대조 (재보고 금지 항목 준수 확인)

| 항목 | 본 구간에서의 관측 | 처리 |
|---|---|---|
| **★1 신고 게이트 = 자기 rawSn 행 하나** | TC-LABEL-97 에서 파생본(18/19/20)에 개인정보 리셋이 전파되지 않음을 실측 | **정상**(확정 정책의 명시적 경계) — 결함 미보고 |
| **★1 귀결 '파생 경유 열람'** | 본 구간 케이스가 아님 | 미보고 |
| UNCERTAINTIES #3 TASK_COMPLETED payload | TC-LABEL-98 은 `TASK_MODIFIED` 축이라 무관. 관측된 `changed_items` 페이로드는 DB 실측 기반이며 self-fill 없음 | 미보고 |

**self-fill 결함**: 본 구간에서 **0건**. 비식별 산출물 판정은 전부 `LS_DEIDENT_PROC_LOG` 에 적재된 실제 값 + 파일시스템 실측(크기·시그니처·mtime)에서 오며, 코드가 값을 만들어 채우는 경로가 없다(경로 문자열 조합·추측도 없음 — `DE_IDNTF_FILE_PATH_NM` 을 그대로 읽는다). resolve 실패 시에도 하드코딩 기본값으로 통과시키지 않고 fail-closed 로 수렴함을 8변형에서 확인.


### [C-ISSUE-03] TC-MARK-01/02 파생 — `LS_MARKING.VIDEO_FILE_PATH_NM` 이 **원본(비식별 전) 절대경로**를 저장·응답 노출하며 소비자가 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: `CLAUDE.md` 는 "마킹은 **비식별 영상** 대상"으로 못박고 있고, 마킹 화면 스트리밍도 항상 비식별본만 서빙한다(비식별 미완료 시 404). 따라서 마킹 레코드가 보관·노출하는 영상 경로도 비식별본이어야 하며, 어느 쪽도 아니라면 **아예 응답에 싣지 않아야** 한다(내부 스토리지 경로 비노출, CWE-200/209).
- **현재 동작(이슈 내용)**: 저장·응답 값이 `LS_DATA_RAW.RAW_FILE_PATH_NM`(= 원본 경로)이다.
  ```java
  // MarkingService.java:217-218
  LsMarking marking = "AUTO".equals(req.mode())
      ? LsMarking.createAuto(rawSn, eventName, req.intervalFrames(), raw.getRawFilePathNm(), marksJson, actorNo, fps)
      : LsMarking.createManual(rawSn, eventName, raw.getRawFilePathNm(), marksJson, actorNo, fps);
  // MarkingResponse.java:54 — 그대로 응답 필드 videoPath 로 반환
  ```
  **실측(배정 WORKER 토큰, raw49)**: `{"markingSn":55,…,"videoPath":"/app/storage/raw/seed/clip-9103.mp4",…}` — 마스킹 없는 서버 내부 절대경로.
  또한 `grep -rn "getVideoFilePathNm" backend/src/main/java` 결과 **소비자는 `MarkingResponse:54` 하나뿐**이다. VLM 위탁은 이 값을 쓰지 않고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 에서 별도로 도출한다(`VlmTimeseriesStep.java:309, :391` — 비식별본 계약은 정상 유지). 즉 이 컬럼은 **응답 에코 전용 사실상 dead field** 다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/videos/{배정된rawSn}/markings -H "Authorization: Bearer $WORKER" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":30}' | python3 -m json.tool | grep videoPath
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select video_file_path_nm from ls_marking order by marking_sn desc limit 1;"
  ```
- **영향**: 보안 LOW(CWE-200 — 내부 스토리지 레이아웃 노출. 원본 파일 자체는 별도 인가로 보호되므로 직접 읽기는 불가). 설계 정합 관점에서는 "마킹 = 비식별본 대상" 규칙과 저장값이 어긋나, 향후 이 컬럼을 신뢰해 소비하는 코드가 붙으면 **원본 픽셀 경로가 파이프라인에 유입**될 수 있는 잠재 함정이다(VLM 이 이미 별도 경로를 쓰는 것이 그 방증).
- **수정 방향(제안)**: 셋 중 택1 — ①저장값을 비식별본 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)로 교체(단 파일명이 벤더마다 달라 조합 금지, 값 조회 필수) ②응답 `videoPath` 필드 제거(FE 소비 여부 확인 후) ③컬럼 자체 폐기. 어느 쪽이든 "이 컬럼은 신뢰 소스가 아니다"를 Javadoc 에 명시. ⚠ 구현은 하지 않는다.


### [C-ISSUE-04] TC-MARK-40/44/45 — 근거 `file:line` 드리프트 3건 (카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 근거 `file:line` 이 실제 위치를 가리켜야 다음 회차 판정·수정 작업이 성립한다.
- **현재 동작(이슈 내용)**: 실제 라인과 1~2줄 어긋난다(동작 결함 아님, 판정에는 영향 없음).

  | 케이스 | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | TC-MARK-40 | `MarkItem.java:26-27` (`@Min(0)`) | `@NotNull` = `:23`, **`@Min(0)` = `:24`** (`:26-27` 은 공백줄 + `@Pattern` 시작) |
  | TC-MARK-44 | `MarkItem.java:29-30` (`@Pattern`) | **`@Pattern` = `:27-28`** (`:29` 는 `String timestamp`, `:30` 은 `)`) |
  | TC-MARK-45 | `MarkingRequest.java:32` (`@Size`) | **`@Size` = `:31`** (`:32` 는 `List<MarkItem> marks`) |
- **재현/확인 경로**: `sed -n '22,30p' backend/src/main/java/kr/co/cudo/authoring/marking/dto/MarkItem.java`
- **영향**: 카탈로그 정합만. 그 외 TC-MARK-01~39·41~43·46 의 근거 43건은 **전량 실제 위치와 일치**함을 확인했다.
- **수정 방향(제안)**: `C-marking-labeling.md` 의 해당 3행 근거 컬럼을 위 실제 위치로 정정. ⚠ 구현은 하지 않는다.


### [C-ISSUE-05] TC-MARK-11 — 29.97fps marks 산출 회귀 테스트가 여전히 부재 (이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: 분수 fps 는 `Math.round(dur×fps)` 반올림 정책이 걸린 유일한 경로이고 마킹↔프레임추출 정합의 근거이므로, 이 계산에 회귀 가드가 있어야 한다.
- **현재 동작(이슈 내용)**: 실동작은 정확하다(본 회차 실측: raw58, `video.fps='29.97'` → `totalFrames=300`, marks 10건, `ls_marking.fps=29.97` pin). 그러나 `grep -rn "29.97" backend/src/test` 결과 관련 테스트는 `VideoFpsResolverTest#분수fps_29.97_정확파싱`(파싱만)과 `VideoMetaServiceTest`(적재만)뿐이고, **`MarkingService` 의 marks 산출 경로를 29.97 로 검증하는 테스트는 없다** — `MarkingServiceTest` 의 fps 케이스는 25 / 30(폴백) / 60 뿐이다(`#자동마킹_실fps25…`, `#M3_자동마킹_fps미상_30폴백…`, `#자동마킹_실fps60…`).
- **재현/확인 경로**: `grep -rn "29.97" backend/src/test` · `grep -n '@DisplayName' backend/src/test/java/kr/co/cudo/authoring/marking/MarkingServiceTest.java`
- **영향**: 테스트 커버리지 갭. `totalFrames` 반올림 정책(예: `round`→`floor` 변경)이 바뀌어도 CI 가 못 잡는다.
- **수정 방향(제안)**: `MarkingServiceTest` 에 `자동마킹_실fps29_97_totalFrames와_타임스탬프_정확계산` 추가(dur=10 → totalFrames 300, frameIndex 0/30/…/270, timestamp `00:00`~`00:09`). ⚠ 구현은 하지 않는다.

---

## 비고 (신규 이슈로 등록하지 않은 관측)

1. **B-ISSUE-41 재현(중복 등록 안 함)** — TC-MARK-46 경로에서 생성된 `PENDING` 마킹이 종결되지 않아 raw48 은 이후 모든 마킹 요청이 영구 409 다(실측: 재요청 → 409). 기 등록 이슈와 동일 현상이므로 판정 참고만 하고 신규 이슈를 만들지 않았다.
2. **B-ISSUE-23 재확인(중복 등록 안 함)** — `intervalFrames=2147483647` 이 201 + marks 1건으로 통과한다(상한 미검증). `UNCERTAINTIES.md` 의 "미해소 이월 · 현재 동작 고정(TC-BATCH-096)" 그대로다.
3. **본 세션 합성 데이터의 배치는 대부분 FAILED** — 내가 INSERT 한 영상들은 비식별 산출물(`LS_DEIDENT_PROC_LOG`)·비식별 프레임이 없어 배치가 프레임추출에서 실패한다. 이는 마킹 API 판정에 영향이 없으며(마킹은 커밋 후 AFTER_COMMIT 으로 배치를 넘긴다), VLM 상태전이(TC-MARK-27/28)는 `pipeline-drive.md` 의 정상 경로 실측(rawSn 26/30)을 근거로 삼았다.
4. **폐기 케이스 0건** — C-1 구간에 `~~취소선~~` 행은 없다. `grep -cE '^\| *~*TC-MARK-'` = 46 으로 헤더 표기(46건)와 실측 일치.


### [C-ISSUE-41] TC-LABEL-66 — ai-server 응답 클래스명은 매핑 화이트리스트로 재검증되지 않는다(방어심층 공백)
- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveDetectClasses` 는 "FE 요청 불신"을 구현해 **요청측**을 재구성한다(실동작 PASS). 같은 서비스가 좌표에 대해서는 "외부(ai-server) 응답 불신"을 명시하고 정규화·거부까지 하는데(`normalizeDetections`, `validatePolygonPoints` javadoc `:543,:582`), **클래스명(label)** 만은 응답을 그대로 신뢰한다. 대칭을 맞추면 ai-server 버전 스큐·회귀로 필터가 무력화돼도 미매핑 클래스가 화면까지 흘러가지 않는다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:423-431 toItems()` 가 응답 detection 을 그대로 아이템화한다. 매핑 조회는 하지만 **미매핑이면 차단이 아니라 `labelId=null` 로 통과**시킨다.
  ```java
  for (YoloResponse.Detection d : detections) {
      Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
      items.add(new AutolabelResponse.Item(
              null, labelId, d.label(), d.points(), clampScore(d.score()), d.trackId()));
  }
  ```
  폴리곤 경로 `:334-337` 도 동일. 현재는 ai-server 가 `yolo.py:44-57 _apply_class_filter` 로 한 번 더 걸러 실피해가 없다(`if not classes: return detections` — BE 는 항상 비어있지 않은 리스트를 보내므로 필터가 실효).
- **재현/확인 경로**: 코드 경로상만 재현 가능(현 스택에서는 ai-server 필터가 살아 있어 실동작 재현 불가). ai-server `_apply_class_filter` 를 우회/롤백한 버전이면 `POST /v1/frames/296/autolabel {"classes":["person"]}` 응답에 `{"labelId":null,"label":"dog",...}` 가 섞여 나올 수 있고, 저장 DTO `LabelItemDto.labelId` 가 nullable 이라 그대로 `LS_DATA_LBL` 에 적재될 수 있다.
- **영향**: CWE-20(불완전 입력 검증, 신뢰 경계 비대칭). 라벨 마스터 미등록 클래스가 학습데이터에 유입될 수 있는 잠재 경로. **CLAUDE.md 의 명문 정책("매핑 강제는 BE 가 담당 — FE 요청을 신뢰하지 않고 … ai-server 로 전달")은 요청측만 요구하므로 정책 위반은 아니다** → FAIL 이 아닌 관찰.
- **수정 방향(제안)**: `toItems`/폴리곤 루프 진입 전에 `effectiveClasses`(또는 `mappedDetectClasses()`) 를 응답 필터로 한 번 더 적용하고, 제외 건은 `drop unmapped detect class` 와 같은 WARN 으로 계상. 구현은 하지 않음.


### [C-ISSUE-43] TC-LABEL-64 — 매핑 0건이어도 프레임 이미지 base64 인코딩을 먼저 수행
- **심각도**: LOW
- **기대 동작(기대효과)**: "매핑된 라벨이 없으면 ai 미호출 + 빈 결과"(TC-LABEL-64) 는 **아무 작업도 하지 않고 즉시 반환**하는 것이 자연스럽다. 프레임 원본 이미지 읽기 + base64 인코딩은 이 경로에서 전혀 쓰이지 않는다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:222` 가 `resolveDetectClasses`(`:228`) 보다 **먼저** 실행된다.
  ```java
  String imageB64 = frameImageEncoder.encodeFrame(src);   // :222
  ...
  List<String> effectiveClasses = resolveDetectClasses(classes);   // :228
  if (effectiveClasses.isEmpty()) { ... return 빈 결과; }           // :229-235
  ```
  실동작 확인: `{"classes":["dog","zebra","toothbrush"]}` 요청이 ai 호출 0회로 끝났으나(로그 `no mapped detect classes`), 그 직전에 프레임 이미지 인코딩은 수행됨.
- **재현/확인 경로**: `curl -X POST http://localhost:18081/api/v1/frames/296/autolabel -H "Authorization: Bearer $WK" -d '{"classes":["dog"]}'` → 200 + `NO_MAPPED_CLASS_MESSAGE`. 응답 지연이 NAS I/O + 이미지 크기에 비례.
- **영향**: 성능/자원(불필요 NAS I/O + 힙 상 base64 문자열). 매핑이 하나도 없는 초기 운영 구간(마이그레이션 직후 전 라벨 `DTCT_TYPE_CD=NULL`)에서 전 요청이 이 경로를 타므로 체감 가능.
- **수정 방향(제안)**: `resolveDetectClasses` → 빈 결과 조기 반환 블록을 `encodeFrame` **앞으로** 이동. 단 `encodeFrame` 이 신고 게이트도 겸하므로(`:56-59` javadoc), 이동 시 `requireNotBlocked` 가 이미 `:214` 에서 선행 수행됨을 확인할 것. 구현은 하지 않음.


### [C-ISSUE-45] TC-LABEL-65 — 검출 클래스 화이트리스트 매칭이 대소문자 구분이라 대문자 요청이 조용히 0건이 된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 화이트리스트 매칭은 fail-closed 여야 하고(현재 그러함), 동시에 **정상 사용자가 이유를 알 수 있어야** 한다. 요청 전부가 케이스 불일치면 사용자에게는 "매핑을 등록하라"는 안내가 뜨는데 실제 원인은 케이스 표기다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:466-473` 이 `trim()` 만 하고 `mapped.contains(trimmed)` 로 **정확 일치** 비교한다.
  ```java
  String trimmed = c == null ? null : c.trim();
  if (trimmed != null && mapped.contains(trimmed)) { allowed.add(trimmed); }
  else { log.warn("[Autolabel] drop unmapped detect class={}", LogSanitizer.sanitize(trimmed)); }
  ```
  실동작: `"PERSON"` → `drop unmapped detect class=PERSON`(WARN), `" car "` → trim 후 통과. 요청이 `["PERSON"]` 뿐이면 응답은 `NO_MAPPED_CLASS_MESSAGE`("라벨 관리에서 AI 검출 클래스를 매핑해 주세요") 로 **원인과 다른 안내**가 나간다.
  - 비교 대상 `LS_LABEL.DTCT_TYPE_CD` 는 저장 시 `CocoClasses` allowlist 로 정규화되므로 항상 소문자 canonical 이다(`LabelMasterService.java:126-135`).
- **재현/확인 경로**: `curl -X POST .../v1/frames/296/autolabel -d '{"classes":["PERSON"]}'` → 200 `detectedCount:0` + "검출할 수 있는 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요."
- **영향**: 기능/사용성. 보안 영향 없음(fail-closed). 정상 FE 는 `GET /v1/manage/labels/detect-candidates` 가 준 canonical 값을 그대로 보내므로 실사용 발생 확률은 낮으나, 외부 스크립트·수기 호출에서 오진단 유발.
- **수정 방향(제안)**: ①`mapped` 를 소문자 정규화 Set 으로 만들고 `trimmed.toLowerCase(Locale.ROOT)` 로 비교(통과 시 canonical 값을 ai-server 로 전달) 또는 ②drop 이 1건 이상인데 결과가 비면 `NO_MAPPED_CLASS_MESSAGE` 대신 "요청한 클래스가 매핑되지 않았습니다" 로 구분 안내. 구현은 하지 않음.

---

## 확증편향 반증 시도 기록 (PASS 로 남은 항목들의 반증 근거)

| 반증 가설 | 시도 | 결과 |
|---|---|---|
| FE 가 임의 클래스를 요청하면 ai-server 로 전달된다 | `["person","hack","dog","PERSON"," car "]` 전송 후 백엔드 WARN·ai-server 호출수 관찰 | **반증 실패(방어 유효)** — hack/dog/PERSON drop, 나머지만 전달 |
| 전부 미매핑이면 그래도 ai 를 호출한다 | `["dog","zebra","toothbrush"]` 전송 + ai-server 누적 호출수 delta 측정 | **반증 실패** — delta 0, ai 미호출 |
| COCO 유효 클래스면 마스터 미매핑이어도 통과한다 | `zebra`/`toothbrush`(유효 COCO, 마스터 미매핑) 전송 | **반증 실패** — drop. allowlist 축은 COCO 가 아니라 `DTCT_TYPE_CD` |
| 온라인 오토라벨이 DB 에 라벨을 남긴다(미저장 정책 위반) | 약 50회 호출 후 `ls_data_lbl` (rawSn=26) 카운트 | **반증 실패** — 0건 유지 |
| 신고 구간(`'F'`) 영상도 오토라벨이 실행된다 | rawSn=33 `de_ident_yn='F'` 로 전환 후 호출 | **반증 실패** — 412 `PRECONDITION_FAILED` |
| 락·신고 동시 성립 시 응답 코드가 뒤바뀐다(규약 변경) | 락+`'F'` 동시 → 409, 락만 해제 → 412 | **반증 실패** — javadoc 규약(작업락 우선) 그대로 |
| in-flight 락이 예외 경로에서 새어 영구 409 가 된다 | 429(bulkhead)·502(ai 다운)·409(중복) 직후 재요청 | **반증 실패** — 전부 200 복귀(`finally` 해제 유효) |
| bulkhead 가 설정만 있고 실효하지 않는다 | 14 프레임 × 3회 = 42 동시요청 | **반증 실패** — 429 9건 실제 발생 |
| ai-server 장애 시 스택/내부경로가 응답에 샌다 | `docker pause klid-ai-server` 후 호출 | **반증 실패** — 502 + 고정 문구만, 스택·URL 미노출 |
| 활성 라벨에 같은 COCO 매핑을 2건 심을 수 있다 | DB 직접 INSERT(`dtct_type_cd='person'`, `use_yn='Y'`) | **반증 실패** — 부분 유니크 인덱스가 차단 |
| 라벨 삭제가 hard delete 다 | `DELETE /v1/manage/labels/20` 후 행 조회 | **반증 실패** — 행 존속 + `use_yn='N'` |
| 라벨명 근사중복(대소문자·공백)이 통과한다 | `" PERSON "` 로 수정 시도 | **반증 실패** — 409 (`LOWER(TRIM())` 비교) |
| `classes` 원소 길이 제한이 리스트 크기 제한에 가려 무력하다 | 원소 1개·51자 전송 | **반증 실패** — `classes[0]` 단위 400 |


### [C-ISSUE-62] TC-LABEL-143 — 기대결과 "`/image` = 통과"가 엔드투엔드로 성립하지 않음(카탈로그 정정)
- **심각도**: LOW (제품 결함 아님 — 실제 동작이 기대보다 **더 엄격**)
- **기대 동작(기대효과)**: 카탈로그는 PORTAL_USER 토큰에 대해 `/deid-image`=403 / `/image`=통과 로 "두 형제 경로의 역할 집합이 의도적으로 다름"을 검증하려 한다.
- **현재 동작(이슈 내용)**: `/deid-image` 403(기대 일치)이지만 `/image` 도 **403**. PORTAL_USER 토큰은 `channel=PORTAL` 이라 `SecurityConfig`(`/v1/portal/**` 외 `/v1/**` = `CHANNEL_INTERNAL` + ROLE_REVIEWER|WORKER|STREAM_SIGNED)에서 `@PreAuthorize` 도달 전에 차단된다. `FrameImageController.java:80` 의 `hasAnyRole(..., 'PORTAL_USER')` 는 현재 배선상 **도달 불가 권한**이다.
- **재현/확인 경로**: `POST /v1/dev/tokens {"role":"PORTAL_USER","channel":"PORTAL"}` 발급 후 `GET /v1/frames/301/image` → 403 / `GET /v1/frames/301/deid-image` → 403.
- **영향**: 없음(보안상 더 강함). 다만 카탈로그 기대값이 틀려 다음 회차에 위양성 FAIL 을 유발할 수 있고, 컨트롤러의 죽은 권한 표기가 정책 오독을 부른다.
- **수정 방향(제안)**: ① 카탈로그 TC-LABEL-143 기대결과를 "`/deid-image`=403 / `/image`=403(채널 격리 선행). 역할 집합 차이는 애노테이션 계층 정책" 으로 정정 ② 코드 쪽은 `FrameImageController` `/image` 의 `PORTAL_USER` 표기를 제거하거나 "채널 격리로 실효 없음" 주석을 다는 것을 검토(동작 변경 아님).


### [C-ISSUE-63] TC-LABEL-141~149 — 근거 `file:line` 드리프트(카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 file:line 이 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: 프레임 이미지 서빙 계열 근거가 전부 어긋난다.
  | TC | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | 141 | `FrameImageController.java:162-171` · `FrameImageService.java:214-269` | `FrameImageController.java:115-122` · `FrameImageService.java:283-334` |
  | 142 | `FrameImageService.java:223-234` | `FrameImageService.java:290-301` |
  | 143 | `FrameImageController.java:164 vs :90` | `:117 vs :80` |
  | 144 | `FrameImageService.java:214-226` | `:283-301`(+ 인가·게이트는 `FrameImageLookupService`) |
  | 145 | `FrameImageService.java:246-256` | `:313-320` + `openNoFollow :362-366` |
  | 146 | `FrameImageService.java:224-233` | `:291-301` |
  | 147 | `FrameImageService.java:263` | `:327` |
  | 148 | `FrameImageController.java:132` | 파일이 127줄로 **존재하지 않는 라인** — 실제는 `FrameImageService.java:252`(`serveFrame` 공용) |
  | 149 | `VideoController.java:277-295`(✅ 유효) · `FrameImageService.java:186` | no-store 는 `FrameImageService.java:252` |
  (TC-106~140 의 근거는 전건 유효 — `DeidentReportService`·`LabelAccessGuard`·`LabelService`·`DetectionBoxNormalizer` 라인 일치, `AutolabelOnlineService` 는 ±5줄 이내)
- **재현/확인 경로**: 해당 파일 Read 대조.
- **영향**: 검증 비용 증가·오판 위험.
- **수정 방향(제안)**: 카탈로그 근거 갱신(위 표 그대로 치환).


### [C-ISSUE-64] TC-LABEL-125 (인접) — 신고 감사 로그의 `privacyReset` 이 "실제로 지워진 프레임 수"가 아님
- **심각도**: LOW
- **기대 동작(기대효과)**: 개인정보 표기를 되돌리는 행위의 감사 로그는 **실제 변경 건수**를 말해야 한다(OWASP A09). 행 단위 감사(`privacyResetAudited`)와 집계 로그가 서로 다른 수를 말하면 운영자가 어느 쪽을 믿을지 알 수 없다.
- **현재 동작(이슈 내용)**: `resetPrivacyMetaByRawSn`(`LsDataSrcRepository.java:198-201`)은 `where s.rawSn = :rawSn` 만으로 **영상 전 프레임을 무조건 update** 하므로 반환값이 곧 "프레임 총수"다. 실측:
  ```
  [DeidentReport] created rprtSn=15 rawSn=27 ... privacyReset=5 privacyResetAudited=0
  [DeidentReport] created rprtSn=16 rawSn=27 ... privacyReset=5 privacyResetAudited=2
  ```
  같은 영상에서 값 보유 프레임이 0건일 때도 2건일 때도 `privacyReset=5` 로 동일하다.
- **재현/확인 경로**: 개인정보 3필드가 전부 NULL 인 영상에 신고 접수 → 로그의 `privacyReset` 이 프레임 수와 같음을 확인.
- **영향**: 감사 로그 오독(실제 변경이 없었는데 "5건 리셋"으로 읽힘). 데이터 정합·보안 자체에는 영향 없음.
- **수정 방향(제안)**: 로그 필드명을 `privacyResetScanned`(또는 `frameCount`)로 바꾸거나, 로그 값을 `privacyResetSrcSns.size()`(=실제 변경분)로 통일. 벌크 update 자체는 멱등이라 조건 추가는 불필요.

---

## 검증 중 발견한 환경/데이터 사실 (참고)

1. **ai-server 가중치 미탑재** — `[Batch][YOLO] mock response detected … mockReason=weights_missing` 로 온라인 오토라벨도 `detectedCount=0` + `message="AI 모델 미로드 — 결과 신뢰 불가"`. TC-136~140 의 실동작 판정을 막는 유일한 환경 갭(코드 결함 아님, baseline 기록과 일치).
2. **동시 검증 간섭** — 같은 시간대에 다른 C 파트 에이전트가 raw 26/27 프레임 라벨을 편집 중이어서, 신고 접수 시점 스냅샷이 흔들릴 수 있다(TC-125 최초 관측이 그 사례). 신고는 대상 영상을 잠그므로 **동시 검증 시 서로의 저장이 409 로 막힐 수 있음** — 다음 회차에는 파트별 전용 rawSn 을 배정하는 편이 안전하다.
3. **DB 컬럼명 주의** — 카탈로그·CLAUDE.md 표기는 `DE_IDNTF_YN` 이지만 실제 테이블 컬럼은 `ls_data_raw.de_ident_yn` 이다(프레임 쪽은 `de_idntf_src_file_path_nm` 으로 표기가 다름). SQL 로 검증할 때 혼동 주의.


### [C-ISSUE-81] TC-SAM2-14/24/26 (카탈로그 미커버 축) — SAM2 **Track** 경로에 mock 게이트가 없어 mock 좌표가 정상 결과로 FE 에 자동 적용된다

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: ai-server 가 mock 응답(`mock=true`, 사유 `env_mock`/`weights_missing`/`load_failed`/`empty_mask`)을 반환하면 BE 가 이를 감지해 **좌표를 그대로 내려보내지 않아야** 한다. segment 경로가 이미 그렇게 한다(`Sam2SegmentService.java:124-127` → 빈 폴리곤 + `MOCK_UNAVAILABLE_MESSAGE` → `OverlayLayer.tsx:578-583` 자동적용 차단). 이 보호가 없으면 **모델이 없는 상태에서 만들어진 가짜 좌표가 학습데이터로 확정**된다(SFR-08 라벨링 정확도 근간 훼손).
- **현재 동작(이슈 내용)**: **BE 의 클라이언트 DTO 가 mock 신호를 아예 파싱하지 않는다.**
  ```java
  // backend/.../common/client/dto/Sam2TrackResponse.java:14-18  — mock/source/mock_reason 없음
  public record Sam2TrackResponse(
          @JsonProperty("track_id") String trackId,
          List<List<Double>> polygon,
          double score) {}
  ```
  ai-server 는 실제로 그 필드를 내보낸다(실측):
  ```
  GET http://localhost:19300/openapi.json
  → Sam2TrackResponse  ['track_id','polygon','score','mock','source','mock_reason']
    Sam2SegmentResponse ['polygon','score','mock','source','mock_reason','success','message','error_code']
  ```
  따라서 `Sam2TrackService.track()`(`:105-142`)·`PortalSam2Service.track()`(`:173-181`) 어디에도 `aiRes.mock()` 검사가 없다(`grep -rn "\.mock()" backend/src/main` → segment 2곳·online autolabel 2곳·YOLO 배치 1곳만 히트, **track 0곳**).
  ai-server 의 mock track 은 특히 위험하다 — `ai-server/app/routers/sam2.py:317-326`:
  ```python
  def _mock_track(req, reason="env_mock"):
      return Sam2TrackResponse(track_id=req.track_id,
          polygon=[list(p) for p in req.prev_polygon],   # 시드 폴리곤 그대로 복사
          score=0.9, mock=True, source="mock", mock_reason=reason)
  ```
  **score 0.9** 라 FE 의 저신뢰 분기(`SAM_LOW_CONFIDENCE_THRESHOLD`)에도 걸리지 않는다. 즉 SAM2 가중치가 없으면 "N 프레임 추적"이 **시드 폴리곤 N개 복제**로 조용히 둔갑한다.
- **재현/확인 경로**:
  1. 현 스택은 SAM2 가중치가 **있어서** 실모델로 동작한다. 그러나 **동일 컨테이너의 YOLO 는 이미 `weights_missing`** 이다(`docker logs klid-ai-server` → `[DETECT:yolox][MOCK] … reason=weights_missing`) — SAM2 가중치 누락도 동일하게 발생 가능한 배포 상태다.
  2. `ai-server` 를 `AI_MOCK_MODE=true` 로 기동하거나 SAM2 가중치를 제거한 뒤:
     `curl -X POST -H "Authorization: Bearer <WORKER>" -d '{"srcSn":1,"trackId":"t","prevPolygon":[[10,10],[100,10],[100,100]],"label":"person","nextSrcSns":[2,3]}' localhost:18081/api/v1/frames/1/sam2-track`
     → 기대: 빈 결과 + 경고 메시지 / 실제(예상): 200 + 시드 폴리곤 복제 2건 + `score 0.9`, 경고 없음.
  3. 대조군(현행 정상 동작): 같은 스택에서 segment 는 mock 유도 시 차단됨 — `-d '{"srcSn":1,"box":[10000,10000,10001,10001]}'` → `{"polygon":[],"empty":true}` + `"message":"AI 모델 미로드 — 결과 신뢰 불가"`.
- **영향**: 데이터 정합/학습데이터 오염(CWE-345 불충분한 데이터 진정성 검증). 라벨은 검수 승인 시 `LS_LABEL_VERSION` 스냅샷 + export + 관제 `TASK_COMPLETED` 로 흘러가므로 오염이 데이터마트까지 전파된다. 포털 SAM2 track(`PortalSam2Service.track`)도 동일 결함이며, 포털은 오토라벨 미제공 채널이라 사용자가 결과를 신뢰할 근거가 더 약하다.
- **수정 방향(제안)**: ⚠ 구현하지 않음.
  1. `common/client/dto/Sam2TrackResponse` 에 `mock` / `source` / `@JsonProperty("mock_reason") mockReason` 3필드 추가(`Sam2Response` 와 동일 형태 + 기존 3-arg 호환 생성자 유지).
  2. `Sam2TrackService.track()` 의 응답 검증 지점(`:115-119`)에 `if (aiRes.mock()) { … }` 을 넣어 **해당 프레임을 결과에서 제외**하고, 루프 종료 후 mock 이 1건이라도 있었으면 컨트롤러가 `Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE` 와 동등한 안내를 `ApiResponse.message` 에 세팅(= segment 규약과 동형).
  3. `PortalSam2Service.track()` 에도 같은 배선(현재 segment 만 `:125` 에 있음).
  4. 회귀 가드: "ai-server 가 내보내는 mock 메타를 BE DTO 가 전부 보유한다"는 계약 드리프트 테스트(기존 `CocoClassesDriftTest` 패턴 재사용).
  5. 카탈로그에도 대응 케이스 신설 권장 — `TC-SAM2-3x: track mock→결과 제외+안내 메시지`(현재 C-3 에 track mock 케이스가 **없다**).

---


### [C-ISSUE-84] TC-SAM2-06 — `Sam2SegmentService` 의 경로가드가 dead code 이고 클래스 javadoc 이 실제 동작(비식별 프레임 사용)과 반대로 적혀 있다 (근거 드리프트 포함)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그·주석이 가리키는 방어 지점이 **실제로 실행되는 코드**여야 한다. 그렇지 않으면 다음 검증자·수정자가 "여기서 막고 있다"고 믿고 실제 방어선(`FrameImageEncoder`)의 회귀를 놓친다(프로젝트가 이미 겪은 패턴 — `CLAUDE.md`: *"컨트롤러가 정책 판정을 복제 보유하면 정책 갱신 때 조용히 뒤처진다"*).
- **현재 동작(이슈 내용)**: 3건.
  1. **dead code** — `Sam2SegmentService.java:179-188` `resolveSafe(Path baseDir, String relativePath)` 는 `segment()` 에서 호출되지 않는다(`grep -n "resolveSafe" Sam2SegmentService.java` → `:52`(javadoc) · `:179`(정의) 뿐). 함께 `@Value` 필드 `storageRawPath`(`:77-78`)도 미사용. 실제 경로가드는 `FrameImageEncoder`(비식별=`StorageSubtreePolicy.verifyDeidentifiedFile` realpath 판정 / 원본=`FrameImageEncoder.resolveSafe:220-229`).
  2. **javadoc 이 동작과 반대** — `:42-44`: *"SAM2 는 **원본 이미지에만 실행**한다 … `srcFilePathNm` = 원본 프레임 경로 사용"*. 실제 `:96` 은 `resolveFrameImageForInference` → `FrameImageEncoder:98-105` **비식별 경로 우선**이다.
     **실동작 반증(결정적)**: `raw_sn=4 frame-0` 은 원본/비식별 파일의 md5 가 다르다(`be89bcc1…` vs `efadbd02…`). ai-server 에 두 파일을 직접 넣어 비교하면
     - 원본 → `…[80.0,176.0],[81.0,177.0],[81.0,178.0],[95.0,178.0],[96.0,179.0],[97.0,178.0],[105…`
     - 비식별 → `…[80.0,176.0],[81.0,177.0],[81.0,178.0],[119.0,178.0],[119.0,6.0],[120.0,5.0],[120.0…`

       BE 응답은 `[[90,0],[80,8],[81,178],[119,178],[119,0]]` → **비식별본 계열과 일치**. 즉 SAM2 분할은 비식별 프레임으로 실행된다.
     (동작 자체는 2026-07-30 확정 정책 "라벨링 캔버스는 비식별 프레임을 서빙한다" 와 정합하며 **결함이 아니다** — 캔버스가 보여주는 픽셀과 분할 대상이 같아야 좌표가 맞는다. 문제는 **주석·`CLAUDE.md` "오토라벨링" 절이 갱신되지 않은 것**.)
  3. **근거 드리프트(카탈로그 정합성)** — TC-SAM2-06 의 `Sam2SegmentService.java:179` 는 dead code 지시. 부수적으로 TC-SAM2-32 서술 "유일한 **패키지 외** 소비자 `FrameBoundsResolver`" 는 사실과 다르다(`FrameBoundsResolver` 는 동일 패키지 `kr.co.cudo.authoring.label.service`).
  - 부가 관찰(별도 이슈 아님, LOW): `FrameImageEncoder` 의 **비식별** 분기는 realpath 기반(TOCTOU/심링크 방어, `StorageSubtreePolicy:201-215`)인데 **원본** 분기(`:220-229`)는 lexical 검증만 하고 `NOFOLLOW` 없이 연다. 경로값이 배치 소유(DB)라 현재 사용자 도달 경로는 없으나, `CLAUDE.md` 가 서빙 4경로에 요구하는 규약과 비대칭이다.
- **재현/확인 경로**: `grep -n "resolveSafe\|storageRawPath" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java` → 호출부 0건 확인. 비식별 사용 실증은 위 md5 + ai-server 직접 호출 대조.
- **영향**: 유지보수/검증 신뢰도(감사 지적 가능 — 주석이 보안 통제를 잘못 서술). 런타임 보안 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ①`Sam2SegmentService` 의 미사용 `resolveSafe`·`storageRawPath` 제거 ②클래스 javadoc `:42-44`·`:52` 를 "비식별 우선 해석 + 경로가드·신고게이트는 `FrameImageEncoder` 단일 위임"으로 정정 ③`CLAUDE.md` "오토라벨링" 절에 **온라인(사용자 트리거) SAM2 = 비식별본 / 배치 YOLO·SAM2 = 원본** 구분 명시 ④카탈로그 TC-SAM2-06 근거를 `FrameImageEncoder.java:220-229`(+`StorageSubtreePolicy.java:181-216`)로, TC-SAM2-32 서술을 "동일 패키지 유일 소비자"로 정정.

---


### [C-ISSUE-85] TC-KEYPOINT-07/09/11/13 — 자동테스트 공백 (동작은 정상, 회귀 가드 부재)

- **심각도**: LOW
- **기대 동작(기대효과)**: 키포인트는 `LBL_TYPE_CD` 기반 type-route 로 기존 2-튜플 경로와 격리된 분기라(`KeypointSerializer` javadoc `:14-16`), 분기 삭제·머지 시 조용히 깨지기 쉽다. 검증한 방어는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: 실측 커버리지 대조 결과 4건 무커버.

  | 케이스 | 방어 위치 | 테스트 |
  |---|---|---|
  | TC-KEYPOINT-07 음수 좌표(SKELETON) | `LabelService.java:820-823` | **없음** (`LabelServiceKeypointTest` 11건 중 음수 케이스 0건) |
  | TC-KEYPOINT-09 `fromJson` 비배열 | `KeypointSerializer.java:80-82` | **없음** (`KeypointSerializerTest` 4건: roundTrip / toJsonTripletFormat / emptyInputs / rejectsTwoTuple) |
  | TC-KEYPOINT-11 `fromJson` 숫자 아님 | `KeypointSerializer.java:91-93` | **없음** |
  | TC-KEYPOINT-13 SKELETON R7 무변경 폴백 | `LabelService.java:568-585` | **없음** (`grep -rn "normalizePoints" backend/src/test` 무결과) |

  ※ 4건 모두 본 검증에서 실동작/정적으로 **정상 확인**됨 — 결함은 "동작"이 아니라 "가드 부재"다. `_raw/test-baseline.md` 기준 backend 실패 0건이라 이 공백은 baseline 으로 드러나지 않는다.
- **재현/확인 경로**: `grep -n "DisplayName" backend/src/test/java/kr/co/cudo/authoring/common/util/KeypointSerializerTest.java` (4건) · `.../label/LabelServiceKeypointTest.java` (11건) · `grep -rn "normalizePoints" backend/src/test` (0건).
- **영향**: 회귀 감지력. 특히 TC-KEYPOINT-13 은 무커버 상태에서 `normalizePoints` 폴백이 사라지면 **SKELETON 라벨이 매 저장마다 "변경됨"으로 오판정**되어 `LS_DATA_LBL_HSTRY` 와 `TASK_MODIFIED` 관제 통지가 무한 증식한다(운영 영향이 조용하고 크다).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `KeypointSerializerTest` 에 비배열(`"{}"`)·비숫자(`"[[\"a\",2,1]]"`)·null 원소(`"[[1,2,null]]"`) 3건 추가, `LabelServiceKeypointTest` 에 음수 좌표 400 1건 추가, R7 무변경 폴백은 "SKELETON 동일값 재저장 시 이력 0건·버전 미증가" 통합테스트로 추가(본 검증의 실동작 시나리오를 그대로 코드화하면 된다).

---


### [C-ISSUE-86] TC-SAM2-09 (부수) — mock 안내 메시지가 사유와 무관하게 "AI 모델 미로드"로 고정돼 오진단을 유도한다

- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자·운영자에게 나가는 안내는 실제 사유와 일치해야 한다. ai-server 는 사유를 4종으로 구분해 내려준다(`mock_reason` ∈ `env_mock` | `weights_missing` | `load_failed` | `empty_mask`).
- **현재 동작(이슈 내용)**: BE 는 사유를 무시하고 단일 문자열을 세팅한다.
  ```java
  // Sam2SegmentResponse.java:23
  public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";
  // LabelController.java:173-177 — res.isEmpty() 이면 무조건 위 문자열
  ```
  **실측**: 현 스택은 SAM2 **모델이 정상 로드**된 상태인데, 마스크가 안 잡히는 프롬프트(`box:[10000,10000,10001,10001]` 또는 역박스 `[100,100,10,10]`)를 주면 ai-server 가 `mock_reason=empty_mask` 로 폴백하고 FE 에는 **"AI 모델 미로드"** 가 표시된다. 실제로는 "프롬프트 위치에 객체가 없음"이다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST -H "Authorization: Bearer <WORKER>" -H 'Content-Type: application/json' \
    -d '{"srcSn":1,"box":[100,100,10,10]}' localhost:18081/api/v1/frames/1/sam2-segment
  # → {"data":{"polygon":[],"score":0.0,"empty":true},"message":"AI 모델 미로드 — 결과 신뢰 불가"}
  # 동시에 ai-server 는 모델 로드 상태(실추론 로그 존재, AI_MOCK_MODE=false)
  ```
- **영향**: 운영/UX. 작업자가 "서버 장애"로 오인해 불필요한 에스컬레이션을 하거나, 반대로 진짜 `weights_missing` 상황이 "늘 뜨는 메시지"로 묻힌다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `Sam2Response.mockReason()` 을 BE 가 이미 파싱하고 있으므로(`common/client/dto/Sam2Response.java:24`) 이를 사유별 메시지로 매핑한다 — `empty_mask` → "선택 지점에서 객체를 찾지 못했습니다 — 다른 위치를 클릭해 주세요", `weights_missing`/`load_failed`/`env_mock` → 현행 "AI 모델 미로드 — 결과 신뢰 불가". **자동적용 차단(빈 폴리곤) 동작은 두 경우 모두 그대로 유지**한다. FE `OverlayLayer.onMockWarning` 은 `res.message` 를 그대로 표시하므로 BE 만 고치면 된다.

---

## 5. 근거 드리프트 목록 (카탈로그 자체 정합성)

| 케이스 | 카탈로그 근거/서술 | 실제 |
|---|---|---|
| TC-SAM2-06 | `Sam2SegmentService.java:179` (`resolveSafe`) | **dead code** — 호출부 0건. 실제 가드는 `FrameImageEncoder.java:220-229` + `StorageSubtreePolicy.java:181-216` |
| TC-SAM2-23 | 기대결과 `502 EXTERNAL_API_ERROR` | 실제 **400 INVALID_INPUT** (→ C-ISSUE-82. 카탈로그 기대값이 옳고 구현이 어긋난 케이스) |
| TC-SAM2-32 | "유일한 **패키지 외** 소비자 `FrameBoundsResolver`" | `FrameBoundsResolver` 는 **동일 패키지**(`label.service`) |
| TC-SAM2-01 | `Sam2SegmentService.java:84-149` | `segment()` 는 `84-153` |
| TC-SAM2-09 | `LabelController.java:174-178` | `173-177` (off-by-1) |
| TC-SAM2-13 | `Sam2SegmentService.java:132-143` | 단순화 블록 `131-147`, 3점미만 폴백 `144-147` |
| TC-KEYPOINT-05 | `LabelService.java:816-819` | `815-819` |
| — (미커버) | C-3 에 **track mock 게이트 케이스가 없다** | segment 만 TC-SAM2-09 로 커버 → C-ISSUE-81 대응 케이스 신설 필요 |

> 그 외 TC-SAM2-02~05/07/08/10/11/12/14~22/24~31/33 및 TC-KEYPOINT-01~04/06~13 의 `file:line` 은 실측과 일치(드리프트 없음).

## 6. self-fill 관점 점검

- SAM2 분할/추적은 **좌표를 자체 생성하지 않는다** — 전량 ai-server 응답에서 온다(`Sam2SegmentService:114` / `Sam2TrackService:107`). 응답이 null/폴리곤 null 이면 502 로 마감(`:119-121` / `:115-117`), 하드코딩 폴백 좌표 없음. `score` 도 외부값 기반(segment 는 clamp, track 은 원본 — C-ISSUE-82).
- 단 **ai-server 내부의 mock 폴백**은 self-fill 성격을 갖는다(`_mock_segment`/`_mock_track` 이 좌표를 합성). segment 는 BE 가 이를 감지해 차단하므로 방어되고, **track 은 감지하지 못해 self-fill 결과가 그대로 통과한다 → C-ISSUE-81 이 본 파트의 self-fill 항목**이다.
- 키포인트는 전량 사용자 입력이며 서버 생성값 없음. 미지정 필드를 임의값으로 메우는 경로 없음(개수 17 강제).

## 7. 검증 중 발생시킨 상태 변경 (원복 완료)

| 대상 | 변경 | 원복 |
|---|---|---|
| `ls_data_lbl` (raw 35 / `src_sn=318`) | 키포인트 라벨 생성·수정(TC-KEYPOINT-01/06/13) | `PUT /v1/frames/318/labels {"items":[]}` → `select count(*) … where src_sn=318` = **0** (검증 전과 동일) |
| `ls_data_lbl_hstry` (`src_sn=318`) | 이력 3행(59/60/61) 적재 | append-only 감사 테이블이라 미삭제 — 원복 대상 아님(정상 동작의 산물) |
| raw 4 / raw 18 / raw 27 | SAM2 segment·track 호출만 (전부 미저장 경로) | 라벨 수 불변 확인(raw 4 = 10건 유지). 변경 없음 |
| `ls_deident_report` | **본 파트는 신고를 생성하지 않음** — 병렬 C-part4 에이전트가 만든 raw 27 의 OPEN 신고를 **읽기만** 하여 TC-SAM2-29/30 을 실동작 검증 | 해당 없음 |


### [C-ISSUE-121] TC-LABEL-93 / TC-LABEL-101 — 신고·resolve 진입점의 리소스 존재 오라클(인가보다 조회가 먼저)
- **심각도**: LOW
- **기대 동작(기대효과)**: 인가되지 않은 사용자에게는 **리소스 존재 여부 자체를 알려주지 않아야** 한다. 같은 프로젝트의 영상 단위 진입점 `reportByVideo` 는 이 원칙을 명시적으로 채택했다 — `DeidentReportService.java:150-151` 주석 *"영상 조회보다 먼저 평가해 미인가자에게 존재 여부를 흘리지 않는다"*. 응답 코드 차이가 상태를 알려주는 오라클이 되면 안 된다는 것은 이 프로젝트가 이미 신고 게이트 응답코드 설계(CWE-209)에서 채택한 원칙이기도 하다.
- **현재 동작(이슈 내용)**: **프레임 단위 신고**와 **resolve** 두 진입점은 조회를 먼저 하고 인가를 나중에 해, 미인가 사용자에게 404/403 이 갈린다.
  ```java
  // DeidentReportService.java:118-125  (report — srcSn 경로)
  public Long report(Long srcSn, String reason, TokenClaims actor) {
      requireReason(reason);
      LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);   // ← 내부에서 findById 먼저(404), 그 뒤 배정검사(403)
  // LabelAccessGuard.verifyAndGet:  findById(srcSn).orElseThrow(NOT_FOUND)  →  이후 WORKER 배정검사 FORBIDDEN

  // DeidentReportService.java:357-361  (resolveManually)
  LsDeidentReport report = reportRepository.findById(rprtSn)
          .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));
  accessGuard.verifyRawAccess(report.getRawSn(), actor);        // ← 인가는 조회 뒤
  ```
  실측(WORKER 2002 = raw26·27 미배정):
  | 요청 | 응답 |
  |---|---|
  | `POST /v1/labels/301/deident-report` (존재하는 타인 프레임) | **403** |
  | `POST /v1/labels/999999/deident-report` (미존재 프레임) | **404** |
  | `POST /v1/videos/27/deident-report` (존재하는 타인 영상) | **403** |
  | `POST /v1/videos/999999/deident-report` (미존재 영상) | **403** ← 오라클 없음(정상 설계) |
  | `POST /v1/deident-reports/19/resolve` (타인 영상 신고) | **403** |
  | `POST /v1/deident-reports/999999/resolve` (미존재 신고) | **404** |
- **재현/확인 경로**:
  ```bash
  # 403 = 존재 / 404 = 미존재 로 SRC_SN·RPRT_SN 순차 열거 가능
  for s in 296 301 999999; do
    curl -s -o /dev/null -w "$s %{http_code}\n" -X POST \
      "http://localhost:18081/api/v1/labels/$s/deident-report" \
      -H "Authorization: Bearer $WORKER2002" -H 'Content-Type: application/json' \
      -d '{"reason":"probe"}'
  done
  ```
- **영향**: **CWE-204(관측 가능한 응답 불일치) / CWE-639 인접**. 인증된 내부 WORKER 가 자신에게 배정되지 않은 프레임·신고의 **존재 범위(순차 PK 구간)** 를 열거할 수 있다. 라벨/픽셀 본문은 여전히 차단되므로 정보 유출량은 "어떤 ID 가 실재하는가"에 국한되며, 미인증자는 401 로 막혀 도달하지 못한다 → 실질 위험 LOW. 다만 **같은 파일 안에서 두 진입점의 정책이 갈리는 비정합**이라 이후 유지보수 때 어느 쪽이 정본인지 혼동을 만든다.
- **수정 방향(제안)**: `report(srcSn,…)` 는 `LabelAccessGuard.verifyAndGet` 이 조회·인가를 함께 수행하는 구조라 순서를 바꾸려면 "srcSn→rawSn 해석 실패도 FORBIDDEN 으로 수렴" 같은 정책 결정이 선행돼야 한다(단, 정상 사용자에게 404 가 403 으로 바뀌면 화면 안내가 나빠진다). `resolveManually` 는 상대적으로 단순 — `findById` 결과가 없을 때도 미인가 role 이면 403 을 반환하도록 정렬 가능. **어느 쪽이든 "존재 오라클을 감수한다"를 명시 결정으로 고정**하는 편이 낫다(현 상태는 결정이 아니라 우연으로 보인다). ⚠ 구현하지 않음 — 사용자 정책 확정 대상.


### [C-ISSUE-122] TC-LABEL-104 — MPEG-TS 시그니처 판정을 만족하는 비-영상 파일이 비식별 산출물 gate 를 통과
- **심각도**: LOW
- **기대 동작(기대효과)**: `DeidentArtifactIntegrity.isValidVideoArtifact` 는 "실제 비식별 영상이 회수됐다"를 보증하는 **단일 판정 지점**이다. 이 판정 통과가 곧 `DE_IDNTF_YN 'F'→'Y'` 복원이고, 그 복원은 **라벨 조회·프레임 이미지·영상 스트리밍·export 게이트를 한꺼번에 여는** 지점이다(클래스 javadoc 이 스스로 명시). 따라서 비-영상 파일이 통과하면 안 된다(CWE-345).
- **현재 동작(이슈 내용)**: **2,000바이트 전부 `0x47`('G') 인 텍스트 파일**이 판정을 통과해 resolve 가 **200** 으로 성공했고, `DE_IDNTF_YN` 이 `'F'→'Y'` 로 복원되고 작업락이 해제됐다(실측).
  ```java
  // DeidentArtifactIntegrity.java:184-192
  private static boolean hasMpegTsSyncPattern(byte[] header) {
      for (int i = 0; i < TS_SYNC_REPEATS; i++) {          // 3회
          int offset = i * TS_PACKET_SIZE;                  // 0 / 188 / 376
          if (offset >= header.length || (header[offset] & 0xFF) != 0x47) return false;
      }
      return true;    // ← 오프셋 0·188·376 이 모두 0x47 이면 통과. 그 외 바이트는 보지 않는다
  }
  ```
  실측 응답: `{"success":true,...}` **HTTP=200** → DB `LS_DATA_RAW(raw_sn=27).DE_IDENT_YN='Y'`, `LS_AUTH_WORK_LOCK` RELEASED, 신고 RESOLVED.
  **반증 대조**: javadoc 이 표적으로 삼은 *현실적* 오탐 후보인 access 로그(`GET /v1/... ` 200줄, 11,519바이트)는 offset 188=`0x74`, 376=`0x73` 이라 **정상 거부**됨을 확인 → 강화 자체는 유효하며, 남은 것은 "동일 바이트 반복" 같은 퇴화 입력이다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend sh -c 'head -c 2000 /dev/zero | tr "\0" "G" > /tmp/x.ts; touch /tmp/x.ts'
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "UPDATE ls_deident_proc_log SET de_idntf_file_path_nm='/tmp/x.ts' WHERE data_raw_sn=<신고영상>;"
  curl -X POST http://localhost:18081/api/v1/deident-reports/<OPEN신고>/resolve -H "Authorization: Bearer $REVIEWER"
  # → 200, DE_IDENT_YN 'F'→'Y'
  ```
- **영향**: **CWE-345(불충분한 데이터 진정성 검증)**. 다만 이 경로를 실제로 밟으려면 ①`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 을 쓸 수 있거나 ②그 경로에 파일을 쓸 수 있어야 한다 — 둘 다 가능한 주체는 이미 유효한 mp4 헤더를 위조할 수도 있으므로 **공격 난이도를 낮추지는 않는다**. 실질 위험은 "외부 비식별 솔루션이 반복 바이트 패딩 산출물을 남기는" 우발 케이스로 한정되며 현실성이 낮다. TC-LABEL-104 가 단언한 **18바이트 스텁 회귀 차단은 충족**되므로 케이스 판정은 PASS 유지.
- **수정 방향(제안)**: MPEG-TS 분기에 **다양성 조건**을 덧붙이는 정도로 충분하다 — 예: 선두 377바이트의 서로 다른 바이트 값이 N종 미만이면 거부, 또는 TS 패킷 헤더의 PID/adaptation 필드가 전부 동일한 상수인 퇴화 패턴 배제. 관대함(오탐 거부 = 운영 사고)이 이 클래스의 설계 원칙이므로 **엄격 파싱은 도입하지 말 것**. ⚠ 구현하지 않음.



## 클러스터 D — 검수/버전관리/관제통지

> ⚠ **중복 결함 교차확증 (3경로 독립 발견, 병합 시 단일화)**: D-ISSUE-02(part1)·D-ISSUE-44(part3)·D-ISSUE-61(part4)는 **동일 근본원인** — `AsyncDatasetExportRunner.doExport`/`DatasetExportService.export`가 예외 유무로만 성공/실패를 판정해, 무예외 실패 종결 경로(`nothing produced`·base 거부·version 소진·NO_INPUT) 4종에서 export가 FAILED로 끝나도 TASK_COMPLETED/TASK_MODIFIED 통지가 그대로 발송됨(CLAUDE.md "export 성공 후에만 통지" 구속정책 위반). **canonical = D-ISSUE-61(CRITICAL, part4, 가장 상세)** — 수정은 1건으로 충분, 아래 3건 모두 동일 커밋으로 해소됨.

### [D-ISSUE-61] TC-NOTIFY-040 / TC-NOTIFY-001 / TC-NOTIFY-041 — export 가 FAILED 로 마감돼도 TASK_COMPLETED 통지가 발송된다 (예외 없는 실패 종결 4경로)
- **심각도**: CRITICAL
- **기대 동작(기대효과)**: `CLAUDE.md` 구속 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다… **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(통지 유실이 아니라 성공 시점으로 지연)."* 관제는 통지를 받으면 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`(최신 SUCCEEDED)을 픽업하므로, 실패 상태에서 통지가 나가면 **구 버전 폴더를 픽업하거나(기존 SUCCEEDED 존재 시) 아무 폴더도 못 찾는다(최초 export 실패 시)**.
- **현재 동작(이슈 내용)**: `AsyncDatasetExportRunner.doExport` 는 **예외 발생 여부**로만 성공을 판정한다.
  ```java
  // AsyncDatasetExportRunner.java:121-130
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }
      catch (Exception e) { log.warn(...); return false; }
  }
  ```
  그런데 `DatasetExportService.export` 는 **예외 없이 정상 반환하면서 실패로 종결하는 경로가 4종**이다:
  ```java
  // DatasetExportService.java:205-210  ← 실측 재현된 경로
  if (totalWritten == 0) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
      outcome = OUTCOME_FAILED;      // ← throw 없음 → doExport 는 true 반환
  }
  ```
  나머지 3종도 동일하게 `return`/정상 종료다 — `markBaseRejected`(경로 거부, `:167-171`), `OUTCOME_VERSION_EXHAUSTED`(`:176-180`), `OUTCOME_NO_INPUT`(`:141-145`), 그리고 쓰기 중 `catch (RuntimeException)` → `markFailed` 후 정상 반환(`:230-237`).
  결과적으로 `runApprovalAsync` 가 `DatasetExportCompletedEvent` 를 발행하고 `ControlNotifyEventListener.onExportCompleted` → `sendCompleted` 로 통지가 나간다.
  **실측 재현(2026-08-01, rawSn=72)** — 4ms 간격의 backend 로그:
  ```
  23:54:53.215 WARN  DatasetExportService  - [DatasetExport] nothing produced — marked FAILED rawSn=72 version=1
  23:54:53.219 INFO  ControlNotifyService  - [ControlNotify] TASK_COMPLETED sent rawSn=72 actual=TASK_COMPLETED
  ```
  DB: `ls_dataset_export{export_sn=18, data_raw_sn=72, export_ver_no=1, export_stts_cd='FAILED'}` / `v_completed_video where raw_sn=72` → **0행**. 즉 관제는 존재하지 않는 산출물에 대한 완료 통지를 받았다.
- **재현/확인 경로**:
  ```sql
  -- 실패 export 와 그 직후 발송된 완료 통지의 공존
  SELECT export_sn, data_raw_sn, export_ver_no, export_stts_cd, reg_dt
    FROM ls_dataset_export WHERE data_raw_sn = 72;         -- FAILED
  SELECT queue_sn, evnt_type_cd, raw_sn, send_rslt_cd, reg_dt
    FROM ls_control_notify_fallback WHERE raw_sn = 72;      -- TASK_COMPLETED / SUCCESS
  SELECT count(*) FROM v_completed_video WHERE raw_sn = 72; -- 0
  ```
  ```bash
  docker logs klid-backend 2>&1 | grep -E "nothing produced|TASK_COMPLETED sent rawSn=72"
  ```
  ⚠ 위 SQL 3건은 **검증 직후(15:0x UTC) 다른 QA 세션이 `ls_data_raw#72` 를 삭제**해 현재는 0행을 돌려준다(FK CASCADE 로 export·notify 행 동반 삭제). 위 DB 스냅샷은 삭제 전 실측이며, **영속 근거는 backend 로그**(위 `grep`)다.
  일반 재현: 프레임 원천 이미지가 실재하지 않는(=writer 가 전량 skip 하는) 영상을 검수 승인한다 — `[DatasetExport] nothing produced — marked FAILED` 직후 `[ControlNotify] TASK_COMPLETED sent` 이 이어지면 재현된 것이다.
- **영향**: 데이터정합 CRITICAL — 관제가 ①최초 승인 실패 시 경로 없는 job 을 등록하고 ②재승인 실패 시 **구 버전 폴더를 최신으로 오인**해 라벨 동기화가 영구 stale 이 된다(사업 요구 "데이터마트 학습데이터셋의 라벨링 정보 동기화" 위반). 회수기(`DatasetExportFailureRecoverer`)가 나중에 성공시켜도 그 사이 관제 보유본은 잘못돼 있고, 통지 정합성 전제(TC-NOTIFY-041)가 무너진다.
- **왜 자동테스트가 못 잡았나**: `AsyncDatasetExportRunnerTest` 의 실패 케이스 3건이 전부 `doThrow(new RuntimeException(...)).when(exportService).export(...)` — **예외 경로만** 검증한다(`:87`, `:113`). 예외 없이 FAILED 로 마감되는 실제 다수 경로에 대한 단언이 없다.
- **수정 방향(제안)**: `DatasetExportService.export` 가 결과(성공/실패 outcome)를 **반환값 또는 예외로 상위에 알리게** 한다. 최소 변경안은 ①`export` 의 시그니처를 `boolean`(또는 outcome enum) 으로 바꿔 `doExport` 가 그대로 관통시키거나, ②비-차단 실패 종결(`OUTCOME_FAILED`/`VERSION_EXHAUSTED`)도 신고 게이트처럼 예외로 이탈시키는 것. `NO_INPUT`/`IDEMPOTENT_SKIP` 은 "성공은 아니지만 통지해도 되는가" 를 별도 판단해야 하므로 outcome enum 방식(①)이 안전하다. 아울러 `AsyncDatasetExportRunnerTest` 에 "예외 없이 FAILED 로 마감되면 완료 이벤트 미발행" 회귀 테스트를 추가한다. ⚠ 본 검증에서는 구현하지 않음.


### [D-ISSUE-02] TC-REVIEW-011 — **export 가 FAILED 인데 `TASK_COMPLETED` 통지가 발송**된다 (교차 이슈: D-6/TC-NOTIFY 소관)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md`(★ export 재생성·동기화 정책) — *"통지는 export 성공(SUCCEEDED) 후 발송한다… export 가 실패하면 통지를 보류하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다"*. 관제는 통지를 받으면 `EXPORT_PATH_NM` 폴더를 픽업하므로, 산출물이 없는 상태에서 통지가 나가면 관제가 **빈/구 버전 폴더**를 집는다.
- **현재 동작(이슈 내용)**: 산출 프레임 0건(=사실상 실패) 경로는 **예외를 던지지 않고 정상 리턴**한다. 러너는 예외 유무로만 성공을 판정하므로 `true` 가 되어 통지 콜백이 그대로 발화한다.
  ```java
  // dataset/export/DatasetExportService.java:205-210
  if (totalWritten == 0) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
      outcome = OUTCOME_FAILED;      // ← 예외 없이 정상 종료
  }
  // dataset/export/AsyncDatasetExportRunner.java:121-131
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }   // ← FAILED 여도 true
      catch (Exception e) { ...; return false; }
  }
  ```
  실측 로그(승인 rawSn=72, 2026-08-01 23:54:53):
  ```
  WARN  DatasetExportService  - [DatasetExport] nothing produced — marked FAILED rawSn=72 version=1
  INFO  ControlNotifyService  - [ControlNotify] TASK_COMPLETED sent rawSn=72 actual=TASK_COMPLETED
  ```
  DB: `LS_DATASET_EXPORT(export_sn=18, data_raw_sn=72, export_stts_cd=FAILED, frame_cnt=NULL)` 인데 통지는 성공 기록(`LS_CONTROL_NOTIFY_FALLBACK` send success).
  ※ 신고 게이트 차단(`PRECONDITION_FAILED` 예외)과 런타임 예외 경로는 의도대로 `false` → 통지 보류가 동작한다. **구멍은 "예외 없이 FAILED 로 마감되는" 이 한 갈래**다.
- **재현/확인 경로**: 프레임 이미지 파일이 실재하지 않는(또는 전부 skip 되는) 영상을 검수 승인 → 백엔드 로그에서 `nothing produced — marked FAILED` 직후 `TASK_COMPLETED sent` 관측.
  ```sql
  SELECT export_stts_cd FROM ls_dataset_export WHERE data_raw_sn=<rawSn> ORDER BY export_sn DESC LIMIT 1; -- FAILED
  ```
- **영향**: 데이터 정합 — 관제가 산출물 없는(또는 구 버전) 상태에서 완료 통지를 수신해 뷰/폴더를 픽업. 추가로 `DatasetExportFailureRecoverer` 가 나중에 재산출에 성공하면 **동일 rawSn 에 통지가 2회** 나간다.
- **수정 방향(제안)**: `totalWritten == 0` 분기에서도 통지 억제 신호를 내보낸다 — ①`DatasetExportService.export` 가 이 경로에서 checked 신호(전용 예외 또는 리턴값)를 주고 ②`AsyncDatasetExportRunner.doExport` 가 "예외 없음"이 아니라 **"마감 상태가 SUCCEEDED/PARTIAL 인가"** 로 성공을 판정하도록 바꾼다. 구현은 하지 않음. **D-6 담당 파트와 중복 보고 가능 — 병합 시 통합할 것.**


### [D-ISSUE-44] TC-DIFF-012 — export 가 `nothing produced → FAILED` 로 끝나도 TASK_MODIFIED/TASK_COMPLETED 가 그대로 발송된다 (통지 보류 계약 우회)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` ★export 재생성·동기화 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다… export 가 실패하면 통지를 보류하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다."* 관제가 통지를 받고 `V_COMPLETED_VIDEO.EXPORT_PATH_NM` 을 픽업할 때 **새 버전 산출물이 반드시 존재**해야 "라벨링 정보 동기화" 요구가 성립한다.
- **현재 동작(이슈 내용)**: 산출 프레임이 0건이면 export 는 FAILED 로 마감되지만 **예외를 던지지 않는다**. 통지 보류는 오직 예외 이탈로만 성립하므로 이 경로가 게이트를 그대로 통과한다.
  ```java
  // dataset/export/DatasetExportService.java:205-210
  if (totalWritten == 0) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
      outcome = OUTCOME_FAILED;      // ← throw 없음 (deidentBlocked 만 throw)
  }
  // dataset/export/AsyncDatasetExportRunner.java:121-129
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }   // ← FAILED 여도 true
      catch (Exception e) { ...; return false; }
  }
  ```
  실측 로그(롤백 직후, 같은 배치 스레드 4ms 간격):
  ```
  00:02:32.940 WARN  DatasetExportService  - [DatasetExport] nothing produced — marked FAILED rawSn=900001 version=6
  00:02:32.945 INFO  ControlNotifyService  - [ControlNotify] TASK_MODIFIED sent rawSn=900001 frames=1 videoLevel=0 reExport=true
  ```
  mock-server: `POST /api/data-set/v2/jobs/900001/notify-updated → 202`. DB `ls_dataset_export` 는 해당 rawSn 의 8개 버전이 **전부 `EXPORT_STTS_CD='FAILED'`**. 승인 경로(`TASK_COMPLETED`)에서도 동일 패턴 관측(`no frames — skip export rawSn=43` 직후 `TASK_COMPLETED sent rawSn=43`).
- **재현/확인 경로**:
  ```bash
  # 프레임 이미지 파일이 없는(또는 전부 skip 되는) APPROVED 영상에서
  curl -s -X POST "$API/v1/versions/{hash}/rollback" -H "Authorization: Bearer $REV" \
       -H "Content-Type: application/json" -d '{"srcSn":<srcSn>}'
  docker logs klid-backend --since 1m | grep -E "nothing produced|TASK_MODIFIED sent"
  docker logs klid-mock-server --tail 50 | grep notify-updated
  ```
  ```sql
  select export_sn, export_ver_no, export_stts_cd from ls_dataset_export where data_raw_sn = :rawSn order by export_sn desc;
  ```
- **영향**: 데이터 정합(관제 연동 계약). 관제가 통지를 받고 뷰를 SELECT 하면 `EXPORT_PATH_NM` 이 **직전 성공 버전(구 라벨) 또는 NULL** 이다 — 즉 "수정했다"는 통지를 받고 **수정 전 산출물이나 빈 값**을 픽업한다. 정확히 이 시나리오를 막으려고 도입한 보류 로직(HIGH-D)이 무력화된 상태이며, 실패 행이 남아 `DatasetExportFailureRecoverer` 가 재산출·재통지하더라도 **이미 나간 잘못된 통지는 회수되지 않는다**.
  ※ 산출 경로 자체는 E 클러스터(TC-EXPORT) 소관이라 중복 보고 가능성 있음. 본 건은 **롤백(TC-DIFF-012) 경로에서 실측**된 것으로 기록한다.
- **수정 방향(제안)**: `DatasetExportService` 의 `totalWritten == 0` 분기를 `deidentBlocked` 와 동일하게 **예외 이탈**로 바꿔 `doExport → false → 통지 보류` 가 성립하게 한다(FAILED 행은 그대로 남겨 회수기가 집도록 유지). 또는 `doExport` 가 `boolean` 대신 export outcome 을 받아 `COMPLETED|PARTIAL` 일 때만 true 를 반환하도록 계약을 조인다. 회귀 가드: "export 가 FAILED 로 마감되면 `DatasetExportCompletedEvent`/`afterExport` 가 발화하지 않는다" IT 추가.


### [D-ISSUE-62] TC-NOTIFY-051 — 관제 통지 요청에 인증 헤더(`x-access-token`)가 부착되지 않는다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 관제 inbound SPI 계약(API-251/API-285)이 `x-access-token` 을 요구한다. 실서버 연동 시 인증 헤더가 없으면 전 통지가 401 로 거부되고, 폴백 큐가 5회 재시도 후 전량 dead-letter 로 고착된다(관제 동기화 전면 중단).
- **현재 동작(이슈 내용)**: 통지 전용 WebClient 가 base-url 만 설정한다.
  ```java
  // WebClientConfig#controlNotifyWebClient
  @Bean(name = "controlNotifyWebClient")
  public WebClient controlNotifyWebClient(@Value("${authoring.control-notify.url:http://localhost:8090}") String baseUrl) {
      return WebClient.builder().baseUrl(baseUrl).build();   // defaultHeader 없음
  }
  ```
  `ControlNotifyClient.doPost`(`:90-108`)도 `contentType(APPLICATION_JSON)` 만 설정한다. 비교 대상인 `vlmWebClient` 는 `vlm.client.token` 이 있으면 `Authorization: Bearer` 를 붙이는 배선이 있어 **관제 쪽만 누락**이다. 목서버는 인증을 요구하지 않아 로컬에서는 202 로 통과한다(로컬 통과가 실환경 계약을 보증하지 못하는 전형).
- **재현/확인 경로**: `docker logs klid-mock-server` 의 통지 요청은 전부 202 — 목서버가 인증을 검사하지 않기 때문. 코드상 헤더 주입 지점 부재는 `grep -n "defaultHeader" backend/src/main/java/kr/co/cudo/authoring/common/config/WebClientConfig.java` 로 확인(관제 빈에는 없음).
- **영향**: 기능/연동 — 실환경 401 로 통지 전량 실패. 보안 관점에서는 반대로 **인증 없는 outbound 를 관제가 수용하면** 통지 위조 표면이 되므로 양쪽 모두 협의가 필요(CWE-306 계열, 수신측 책임).
- **수정 방향(제안)**: `authoring.control-notify.token`(환경변수 주입, 평문 커밋 금지) 설정키를 신설하고 `controlNotifyWebClient` 에 `defaultHeader("x-access-token", token)` 를 조건부 배선한다(빈 값이면 미부착 + 기동 WARN). 실제 헤더명·값 형식은 **관제팀 확정 필요**(UNCERTAINTIES #26). 목서버에도 헤더 검사 옵션을 추가해 계약을 실왕복으로 고정하는 것이 바람직하다. ⚠ 본 검증에서는 구현하지 않음.


### [D-ISSUE-01] TC-REVIEW-017 — 승인 스냅샷의 **DTO 구성 단계 실패는 프레임 스킵이 아니라 승인 전체 500 + 롤백**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 승인은 라벨 본문 산출물의 문제로 막히면 안 된다. `VersionService.snapshotFrameOnApprove` 는 스냅샷 산출 실패를 **해당 프레임만 SKIPPED** 로 처리하고 승인은 성공시키며, `ReviewService` 가 `approved with snapshot skips` WARN 으로 운영자에게 가시화한다(TC-REVIEW-017 의 단언). 이래야 프레임 1건의 손상 데이터가 영상 전체의 검수 종결을 막지 않는다.
- **현재 동작(이슈 내용)**: 방어가 **직렬화 호출(try 블록)에만** 걸려 있고, 그 앞의 DTO 구성(`LabelResponse.of` → `LabelPointSerializer` 좌표 파싱)은 보호 범위 밖이다.
  ```java
  // backend/.../version/service/VersionService.java:265-278
  LabelResponse snapshot = LabelResponse.of(frame, siblings, labels, "DEID", null,
          aiInfoBySn, objectMapper);          // ← 여기서 던지면 catch 되지 않는다
  String payload;
  try {
      payload = serializeSnapshotWithSimplification(snapshot, frame.getSrcSn());
  } catch (CustomException e) { ... return FrameSnapshotOutcome.SKIPPED; }
  catch (Exception e)        { ... return FrameSnapshotOutcome.SKIPPED; }
  ```
  실측: `LS_DATA_LBL.POINT_CN` 이 배열이 아닌 객체(`{"x":10,...}`)인 라벨 1건이 있는 영상을 승인하면
  ```
  HTTP 500 {"success":false,"errorCode":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
  java.lang.IllegalArgumentException: 좌표는 배열이어야 합니다
      at ...common/util/LabelPointSerializer.java:90
      at VersionService.snapshotFrameOnApprove(VersionService.java:265)
      at VersionService.commitApproved(VersionService.java:197)
      at ReviewService.approve(ReviewService.java:495)
  ```
  승인 트랜잭션 전체가 롤백되어 **그 영상은 어떤 검수자도 영원히 승인할 수 없고**(작업자가 라벨을 손보기 전엔 반려밖에 못 함), 응답은 원인을 알 수 없는 일반 500 이다.
- **재현/확인 경로**:
  ```sql
  UPDATE ls_data_lbl SET point_cn='{"x":10,"y":10,"w":50,"h":50}' WHERE lbl_sn=<대상>;
  ```
  ```bash
  curl -X POST localhost:18081/api/v1/reviews/<rawSn>/approve -H "Authorization: Bearer $REVIEWER"
  # → 500, 백엔드 로그에 IllegalArgumentException: 좌표는 배열이어야 합니다
  ```
- **영향**: 기능(검수 종결 불가 — 손상 라벨 1건이 영상 전체를 잠금) + 운영(원인 미노출 500 으로 트리아지 불가). 관련 미해소 이슈 C-ISSUE-23(R7 손상 JSON 회귀 테스트 부재)과 같은 뿌리. 보안 등급은 아님(스택트레이스는 응답에 미노출 — CWE-209 는 해당 없음).
- **수정 방향(제안)**: `LabelResponse.of(...)` 호출을 같은 try 블록 안으로 옮겨 구성 단계 예외도 `FrameSnapshotOutcome.SKIPPED` 로 흡수(로그는 `srcSn` 만, 본문·좌표 미출력). 또는 `commitApproved` 루프에서 프레임 단위 예외를 잡아 skip 카운트에 합산. 구현은 하지 않음.


### [D-ISSUE-21] TC-ASSIGN-006 / TC-ASSIGN-021 — 배정 API 가 FK 위반·PK 충돌을 전부 "중복 배정 409" 로 오분류
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 존재하지 않는 영상(rawSn)에 대한 배정은 **404 NOT_FOUND**(또는 400 INVALID_INPUT)여야 한다. 이미 workerId/reviewerId 는 존재 검증 후 400 을 주므로(`:76-81`) rawDataIds 만 검증이 빠진 것은 계약 비대칭이다. 또 상태행 PK 충돌(동시 배정)은 "중복 배정" 과 원인이 달라 재시도 안내가 달라야 한다. 잘못된 메시지는 운영자가 존재하지 않는 중복 배정을 찾아 헤매게 만든다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:101-103` 의 `catch (DataIntegrityViolationException)` 가 **UK 충돌·FK 위반·PK 충돌을 구분 없이** 삼켜 동일 문구로 409 를 낸다.
  ```java
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  }
  ```
  실측 ①존재하지 않는 rawSn:
  ```
  POST /v1/assignments {"workerId":2001,"rawDataIds":[999999]}
  → 409 {"errorCode":"CONFLICT","message":"이미 동일 작업자에게 배정된 영상이 있습니다."}
  backend log: ERROR ... violates foreign key constraint "fk_ls_task_assignment_raw"
               WARN  [Assignment] duplicate assignment detected workerId=2001
  ```
  실측 ②동시 배정(raw52, 상태행 부재): 두 요청 중 하나가 `LS_RAW_DATA_STATUS` PK 충돌로 실패했는데 메시지는 동일하게 "이미 동일 작업자에게 배정된 영상". 부수적으로 매 발생마다 **ERROR 레벨 SQL 스택 로그**가 남아 정상 경합에도 오탐 알람이 발생한다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[999999]}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_data_raw where raw_sn=999999;"   -- 0
  ```
- **영향**: 기능/운영. API 계약 위반(`rules/api-design.md` — 리소스 없음=404), 오도성 에러 메시지, ERROR 로그 오탐. 데이터 정합은 tx 롤백으로 보존되어 보안 영향은 없음(CWE-209 는 경미 — 내부 정보를 노출하는 방향이 아니라 은폐하는 방향).
- **수정 방향(제안)**: `assign()` 진입부 `rejectApprovedTargets` 옆에 `videoRepository.findAllById(rawDataIds)` 로 **존재 검증(단일 IN 쿼리)** 을 추가해 미존재 시 404/400 을 먼저 던진다. 그리고 `catch (DataIntegrityViolationException)` 에서 `e.getMostSpecificCause().getMessage()` 의 제약명(`uk_...` vs `fk_...` vs `..._pkey`)으로 분기하거나, 상태행 upsert 를 `save` 대신 조건부 INSERT 로 분리해 UK 충돌만 "중복 배정" 문구를 쓰게 한다. ⚠ 구현하지 않음.


### [D-ISSUE-22] TC-ASSIGN-001 / TC-ASSIGN-007 / TC-ASSIGN-009 — 배정 대상 사용자의 **역할·활성 여부**를 검증하지 않아 PORTAL_USER·REVIEWER 를 LABELER 로 배정할 수 있다
- **심각도**: MEDIUM (CWE-863 Incorrect Authorization / 업무규칙 미집행)
- **기대 동작(기대효과)**: `CLAUDE.md` 작업 배정 규칙은 "**REVIEWER 가 WORKER 에게** 배정"이다. `LS_TASK_ASSIGNMENT.TASK_TYPE_CD='LABELER'` 행의 `USER_NO` 는 **WORKER 역할 + 활성(`USE_YN='Y'`) 사용자**여야 하고, `'REVIEWER'` 행은 REVIEWER 여야 한다. 역할 진실원(`LS_USER_ROLE`)이 이미 존재하므로 검증이 가능하다 — 실제로 작업자 선택 드롭다운을 채우는 `UserRepository.findAllWorkersWithTaskCount()` 는 `LsUserRole.roleCd='WORKER' AND u.useYn='Y'` 를 **이미 걸고 있다**. 즉 FE 목록은 필터링되는데 API 는 무검증인 전형적 비대칭이다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:76-81` 이 **존재 여부만** 본다.
  ```java
  if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
  }
  if (req.reviewerId() != null && userRepository.findByUserNo(req.reviewerId()).isEmpty()) { ... }
  ```
  `findByUserNo` 는 `USE_YN` 필터도, `LS_USER_ROLE` 조인도 없다. 재배정(`:255-257`)도 동일. 실측:
  ```
  POST /v1/assignments {"workerId":3001,"rawDataIds":[69]}  → 201  (3001 = PORTAL_USER)
  POST /v1/assignments {"workerId":1002,"rawDataIds":[69]}  → 201  (1002 = REVIEWER)
  POST /v1/assignments {"workerId":2001,"rawDataIds":[69],"reviewerId":2002} → 201 (2002 = WORKER 가 REVIEWER 행으로 INSERT)
  DB: ls_task_assignment(64,69,3001,LABELER) (65,69,1002,LABELER) (67,69,2002,REVIEWER)
  DB: ls_user_role → 1002=REVIEWER, 2001/2002=WORKER, 3001=PORTAL_USER
  ```
- **재현/확인 경로**: 위 curl 3줄 + `select a.*, r.role_cd from ls_task_assignment a join ls_user_role r on r.user_no=a.user_no where a.raw_data_id=69;`
- **영향**:
  - **데드락 워크플로**: PORTAL_USER 에게 배정된 영상은 그 사용자가 `/v1/reviews/{rawSn}/submit` 등 내부 API 를 호출할 수 없어(PORTAL 채널 403) **ASSIGNED 로 영구 정체**한다. 배정 취소 API 가 없어 재배정으로만 회수 가능.
  - **집계 오염**: 작업자별 배정 카운트·작업목록·이력에 비-WORKER 가 섞인다.
  - **직무분리(SoD)**: REVIEWER 를 LABELER 로 배정하면 라벨링·승인 동일인이 된다. 단 `LabelAccessGuard` 가 REVIEWER 에 전면 통과를 주는 현행 설계상 **새로운 권한 상승은 발생하지 않는다**(기존 정책의 귀결) — 그래서 HIGH 가 아니라 MEDIUM.
  - 비활성 사용자(`USE_YN='N'`) 배정도 동일 경로로 가능(시드에 비활성 사용자가 없어 실측은 미수행, 정적 확인).
- **수정 방향(제안)**: `assign`/`reassign` 의 사용자 검증을 존재 확인에서 **역할·활성 확인**으로 승격한다 — `LsUserRoleRepository.findByUserNo(workerId)` 가 `WORKER` 인지(+`MngAcctUser.useYn='Y'`) 검사하고 아니면 400. `reviewerId` 는 `REVIEWER` 로 동일 검사. 드롭다운 쿼리(`findAllWorkersWithTaskCount`)와 **같은 술어**를 공유하는 단일 판정 지점으로 추출해 FE/BE 드리프트를 막는다. ⚠ 구현하지 않음.


### [D-ISSUE-41] TC-DIFF-002 — SKELETON diff 응답이 가시성 v 를 버리고 shape 타입을 POLYGON 으로 왜곡
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수자가 버전 비교 화면에서 "무엇이 바뀌었는지"를 눈으로 확인할 수 있어야 한다. SKELETON 은 삼중값 `[x,y,v]` 이고 v(가시성)만 바뀌는 편집이 실제 작업 동선이므로, `MODIFIED` 로 감지했다면 `before`/`after` 에 그 차이가 드러나야 한다. 그러라고 `readPoints` 에 삼중값 비교(v-blindness 수정)를 넣은 것이다.
- **현재 동작(이슈 내용)**: 감지는 되지만 **렌더링에서 v 가 탈락**해 `before` 와 `after` 가 완전히 동일한 값으로 응답된다. 타입도 `SKELETON` 이 아니라 `POLYGON` 으로 나간다.
  ```java
  // version/dto/LabelDiffDto.java:69-77  ShapeDto.fromPoints
  List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
  for (List<Double> pt : points) {
      if (pt.size() >= 2) { flat.add(pt.get(0)); flat.add(pt.get(1)); }   // ← pt.get(2)(v) 유실
  }
  return new ShapeDto("POLYGON", null, null, null, null, flat);            // ← 타입 왜곡
  ```
  실측 응답(v 를 `1→0` 만 변경한 두 APPROVED 버전 비교):
  `{"type":"MODIFIED","objectId":"900003","before":{"type":"POLYGON","points":[34개]},"after":{"type":"POLYGON","points":[34개]}}` — 파이썬 대조 결과 `before.points == after.points` → **True**
- **재현/확인 경로**:
  1. SKELETON 라벨 보유 프레임 승인 → v1 생성
  2. `PUT /v1/frames/{srcSn}/labels` 로 키포인트 1개의 v 만 변경(좌표 동일) → 재승인 → v2 생성
  3. `curl "$API/v1/versions/{v2hash}/diff?compareWith={v1hash}" -H "Authorization: Bearer $REV"` → `before.points == after.points`
- **영향**: 기능(버전 비교 신뢰성). 검수자가 "변경됐다는데 뭐가 변경됐는지 안 보이는" 상태가 되어 diff 를 근거로 한 롤백 판단이 불가능해진다. FE 가 `type` 을 보고 shape 렌더러를 고르면 SKELETON 이 폴리곤으로 잘못 그려진다. 보안 영향 없음.
- **수정 방향(제안)**: `LabelDiffDto.ShapeDto` 에 SKELETON 분기를 추가해 `type="SKELETON"` + 삼중값 보존 표현(예: `keypoints: [[x,y,v]…]` 또는 `flat` 을 3-stride 로) 으로 내려보내고, FE `LabelDiff` 타입도 동반 확장한다. 회귀 가드로 "v 만 바뀐 두 버전의 diff 응답에서 before≠after" 단언을 `VersionServiceTest#버전diff_SKELETON_v만_바뀌면_MODIFIED_감지` 에 추가.


### [D-ISSUE-43] TC-DIFF-008 — 롤백 복원이 `LS_DATA_LBL.REG_USER_NO` 를 되살리지 못해 NULL 로 소실되고, 통계가 그 라벨을 "자동 라벨"로 오분류
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 롤백은 "그 시점 작업본으로 되돌리는 것"이므로 복원된 라벨의 귀속(누가 만든 라벨인가)이 보존돼야 한다. 특히 이 컬럼은 통계에서 **수동/자동 라벨 판별 프록시**로 쓰이므로 값이 바뀌면 지표가 틀어진다.
- **현재 동작(이슈 내용)**: 스냅샷 페이로드(`LabelResponse.Item`)에 `regUserNo` 가 없고, 명시 PK 복원 INSERT 도 그 컬럼을 쓰지 않는다.
  ```java
  // batch/repository/LsDataLblRepositoryImpl.java:26-29
  "INSERT INTO LS_DATA_LBL (LBL_SN, SRC_SN, LBL_TYPE_CD, LBL_ID, LBL_NM, POINT_CN, TRCK_ID, REG_DT) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT (LBL_SN) DO NOTHING";   // ← REG_USER_NO 없음
  ```
  그리고 통계는 이 컬럼의 NULL 여부로 자동 라벨을 센다.
  ```java
  // stats/repository/StatsQueryRepository.java:150-166
  /** regUserNo IS NULL 을 "자동 라벨" 프록시로 사용한다 */
  SELECT COUNT(l) FROM LsDataLbl l ... WHERE l.regUserNo IS NULL AND s.rawSn IN (...)
  ```
  실측: 롤백 전 `reg_user_no=2001` → 롤백 후 `reg_user_no=(null)` (복원된 900001·900002 전부).
- **재현/확인 경로**:
  ```sql
  -- 롤백 전
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- 2001
  -- POST /v1/versions/{hash}/rollback  수행 후
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- NULL
  ```
- **영향**: 데이터 정합/감사. ①라벨 작성자 귀속 소실 ②`SCR-STAT-001` 작업자 통계의 `autoLabelRate` 가 롤백된 프레임만큼 부풀려짐(수동 라벨이 자동으로 계상). 롤백은 되돌릴수록 누적되며 원복 수단이 없다(값이 어디에도 남지 않음).
- **수정 방향(제안)**: ①스냅샷 페이로드에 `regUserNo` 를 실어 왕복 복원하거나(권장 — AI메타·TRCK_ID 와 동일한 처리) ②최소한 `INSERT_SQL`/`createRestored` 에 컬럼을 추가하고 값이 없으면 **롤백 수행자 대신 원 작성자 불명을 구분할 수 있는 표식**을 남긴다. 함께 `StatsQueryRepository` 의 "regUserNo IS NULL = 자동" 프록시를 `LsDataLblAiInfo` 조인 기반으로 교체하는 것이 근본책(주석에도 비용 회피용 프록시라고 명시돼 있음). 회귀 가드는 `VersionRollbackRestoreIT` 에 "롤백 후 REG_USER_NO 보존" 단언 추가.


### [D-ISSUE-45] TC-DIFF-026 — 동일 `version_hash` 다중 매칭 시 `matches.get(0)` 이 비결정적이라 diff 결과가 조용히 뒤바뀐다 (D-ISSUE-27 이월, 실증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 같은 입력(두 해시)에 대해 diff 는 항상 같은 결과를 돌려주거나, 모호하면 명시적으로 거부해야 한다. `VERSION_HASH` 의 UNIQUE 는 `(DATA_SRC_SN, VERSION_HASH)` 복합이므로 **서로 다른 프레임이 같은 해시를 갖는 것은 정상**이다(라벨 집합이 같으면 발생 — 특히 라벨 0~1건인 단순 프레임에서 현실적으로 충돌 가능).
- **현재 동작(이슈 내용)**: 전역 해시 조회에 정렬이 없고 첫 행을 그대로 쓴다.
  ```java
  // VersionService.java:1020-1026
  private LsLabelVersion findByHashOrThrow(String versionHash, String notFoundMessage) {
      List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
      if (matches.isEmpty()) { throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage); }
      return matches.get(0);      // ← 어느 프레임의 버전인지 비결정
  }
  ```
  ```java
  // version/repository/LsLabelVersionRepository.java  (ORDER BY 없음)
  List<LsLabelVersion> findByVersionHash(String versionHash);
  ```
  **실측**: 같은 해시를 다른 프레임(src 900002)에 하나 더 만든 뒤 동일 diff 요청을 5회 반복 → 매번 정상 결과(변경 3건). 이어서 `UPDATE` 로 heap 순서를 뒤집자(ctid `(2,11)` 로 이동) **같은 요청이 `data:[]`(변경 없음)** 을 반환. 오류 없이 결과만 뒤바뀐다.
- **재현/확인 경로**:
  ```sql
  -- 다른 프레임에 같은 해시를 만든다 (복합 UNIQUE 라 허용됨)
  insert into ls_label_version (lbl_version_sn,data_raw_sn,data_src_sn,ver_no,save_reason_cd,actvtn_yn,reg_id,reg_dt,lbl_payload,version_hash)
  values (990002, <rawSn>, <otherSrcSn>, 9, 'APPROVED','N','1001', now(), '{"items":[]}', '<기존해시>');
  update ls_label_version set reg_id = reg_id where lbl_version_sn = <원본행>;   -- heap 순서 반전
  select ctid, lbl_version_sn, data_src_sn from ls_label_version where version_hash = '<기존해시>' order by ctid;
  ```
  ```bash
  curl -s "$API/v1/versions/{H2}/diff?compareWith={충돌해시}" -H "Authorization: Bearer $REV"   # 결과가 뒤바뀜
  ```
- **영향**: 데이터 정합/기능. ①검수자가 "변경 없음"을 보고 잘못된 승인 판단을 내릴 수 있다 ②VACUUM·UPDATE·인덱스 스캔 전환 등으로 재현이 산발적이라 장애 분석이 어렵다 ③`requireFrameScoped`/`accessGuard` 가 **선택된 그 행 기준**으로 평가되므로 인가 대상 프레임까지 요청마다 달라진다(권한 자체는 각 행에 대해 정상 검사되므로 인가 우회는 아님).
- **수정 방향(제안)**: 근본책은 **diff 진입점을 srcSn 스코프로 정렬**하는 것 — 요청에 `srcSn` 을 받아 `findByDataSrcSnAndVersionHash`(이미 존재, `rollback` 이 사용)로 단건 조회하면 모호성·D-ISSUE-42 오라클이 함께 사라진다. 하위호환이 필요하면 차선책으로 ①`findByVersionHash` 에 `order by dataSrcSn, labelVersionSn` 을 부여해 결정화하거나 ②`matches.size() > 1` 이면 `INVALID_INPUT`(400, "해시가 여러 프레임에 매칭됨 — srcSn 을 지정하세요")로 명시 거부한다. 어느 안이든 다중 매칭 상황의 회귀 테스트가 현재 **0건**이므로 함께 추가한다.


### [D-ISSUE-63] TC-NOTIFY-003 — `event_type_cd` 가 매핑 없이 pass-through 되어 서로 다른 코드체계가 섞여 나간다
- **심각도**: MEDIUM (확인필요 — 관제 코드값 목록 미수령)
- **기대 동작(기대효과)**: 관제 `datasets.event_type_cd` 는 관제 8대 이벤트 코드값 도메인을 따라야 한다. 저작도구 보유값이 그대로 나가면 관제가 미지의 코드로 적재하거나 거부한다.
- **현재 동작(이슈 내용)**:
  ```java
  // ControlNotifyPayloadFactory.java:139-141
  private String toControlEventTypeCd(String authoringEventTypeCd) {
      return authoringEventTypeCd;   // 매핑표 미수령 → pass-through
  }
  ```
  실측 결과 **DB 안에 이미 두 계통의 값이 공존**한다: `ls_data_raw.evnt_type_cd` = `INTRUSION`(raw 4·5·18, 영문 의미어) / `EV02000201`(raw 26, 관제 코드형). 그대로 통지되어 `ls_control_notify_fallback` 페이로드에도 두 계통이 그대로 남아 있다(queue_sn 1·3·9 = `INTRUSION`, 12·15 = `EV02000201`).
- **재현/확인 경로**:
  ```sql
  SELECT DISTINCT evnt_type_cd FROM ls_data_raw;                    -- INTRUSION, EV02000201 …
  SELECT payload_cn FROM ls_control_notify_fallback WHERE evnt_type_cd='TASK_COMPLETED';
  ```
- **영향**: 외부연동 계약 — 관제 적재 실패 또는 이벤트 유형 오분류. 데이터 자체의 정합(적재 소스별 코드체계 혼재)도 함께 드러난다.
- **수정 방향(제안)**: 관제 8대 코드 매핑표 수령 후 `toControlEventTypeCd` **한 곳**에서 allowlist 매핑(미매핑 값은 fail-closed 또는 지정 기본값)으로 교체. 동시에 적재 경로(`TrainingVideoIngestService` 등)에서 `EVNT_TYPE_CD` 코드체계를 하나로 정규화할지 별도 판단 필요. ⚠ 본 검증에서는 구현하지 않음. UNCERTAINTIES #27 과 동일 항목.


### [D-ISSUE-03] TC-REVIEW-034 — `ReviewStateMachine.verify(null, to)` NPE 500 (이월·미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 알 수 없는/누락 출발 상태는 `INVALID_INPUT(400)` 으로 거부되어야 한다(알 수 없는 코드값 = 전이 불가). 상태머신은 어떤 입력에도 fail-closed 여야 하며 500 을 내면 안 된다.
- **현재 동작(이슈 내용)**:
  ```java
  // review/service/ReviewStateMachine.java:43-63
  private static final Map<String, Set<String>> ALLOWED = Map.of( ... );   // ImmutableCollections.MapN
  public void verify(String from, String to) {
      if (STTS_APPROVED.equals(from) && !STTS_PENDING.equals(to)) { throw CONFLICT; }  // null-safe
      Set<String> allowed = ALLOWED.get(from);   // ← from==null 이면 NullPointerException
      ...
  }
  ```
  `Map.of(...)` 는 null 키 조회에서 NPE 를 던지므로 400 이 아니라 500(INTERNAL_ERROR)이 된다.
- **재현/확인 경로**: `LS_RAW_DATA_STATUS.DATA_STTS_CD` 가 NOT NULL 이라 **현행 API 로는 도달 불가**(실측: `COMPLETED`/`PROCESSING`/`FAILED` 출발은 정상 400). 단위 호출 `new ReviewStateMachine().verify(null, "PENDING")` 로만 재현.
- **영향**: 현재 실경로 미도달이라 운영 영향 없음. 다만 상태 코드 소스가 늘거나(외부 인입·마이그레이션 중 NULL 유입) 이 클래스를 재사용하면 500 이 표면화된다. 회귀 가드도 없다(`ReviewStateMachineTest` 7건에 null/미지정 코드 케이스 부재).
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null) throw new CustomException(INVALID_INPUT, "상태 정보를 확인할 수 없습니다.")` 가드 추가(메시지에 입력값 미포함) + `ReviewStateMachineTest` 에 null/`COMPLETED`/`PROCESSING` 케이스 3건 추가. 구현은 하지 않음.

---

## 4. 근거 드리프트 (카탈로그 정합성)

| TC | 카탈로그 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-REVIEW-006 · 024 | `ReviewService.java:614-631` | `614-627` (`verifyAssignedWorker`) | 4줄 초과 표기 |
| TC-REVIEW-028 | `ReviewService.java:335-376` | `335-372` (`listFrames`) | 4줄 초과 표기 |
| TC-REVIEW-052 | `EventAnnotationPayload.java:CotDeserializer(94-140)` | `CotDeserializer` 는 **120-142**, 94-97 은 이를 설명하는 javadoc | 클래스 본문 라인 불일치 |

그 외 D-1/D-2 의 근거 `file:line` 은 실측과 일치했다(ReviewService 103-149·378-383·394-406·421-439·444-452·467-518·471-478·477-486·496-501·502-506·507-510·543-565·545-553·556-564·570-599·576-584·633-650·652-659, ReviewStateMachine 43-51·53-64·54-58·59-63, ReviewController 47·92-94·95-103·127-139·286-294, ReviewQueryRepository 109-129·186-215·229-250·233-234·243-248·261-284·304-325·331-333·335-340·360-388, ReviewRepository 33-37, SortAllowlist 29-60·REVIEW(115-119), ReviewSummaryResponse of/verifyMappedCoverage, ReviewSearchCondition statusFilter).

---

## 5. 데이터 원복 (검증 종료 상태)

| 항목 | 조치 | 확인 |
|---|---|---|
| 테스트 영상 rawSn 72/73/74 (`QA-D1-A/B/C`) | `DELETE FROM ls_data_raw` (연쇄: status·assignment·src·issue·event_log·label_version·dataset_export·dataset_video_meta·meta·meta_review·control_notify_fallback), 비연쇄 자식(`ls_evnt_anno_review`·`ls_data_lbl`)은 선행 수동 삭제 | `SELECT count(*) … WHERE raw_sn IN (72,73,74)` → **0** |
| 임시 CCTV `QA-D1-CCTVBLANK` | 삭제 | 삭제 확인 |
| tie-break 실험용 `UPD_DT` 변경(raw 4/5/7/8/9/15/18) | 사전 백업(`/tmp/upd_backup.csv`) 후 **원값 복원** | 4=03:17:19.09094 · 5=03:21:28.784088 · 7=03:17:04.228946 · 8=03:21:02.173255 · 9=03:22:41.172949 · 15=03:20:39.436578 · 18=03:22:12.418234 (검증 전 값과 동일) |
| 기존 영상 26/27/31/33/34 등 | **상태·데이터 변경 없음**(테스트에 사용하지 않음) | 26=APPROVED · 27=ASSIGNED · 31=PENDING · 33=ASSIGNED · 34=ASSIGNED — 검증 전과 동일 |
| 컨테이너 산출물 | `/app/storage/raw/72|73|74` 삭제 | — |
| 코드·설정·테스트 파일 | **미수정**(본 결과 파일 1개만 생성) | — |

## 6. 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| D-1 검수 워크플로우 | 48 | 47 | 0 | 1 | 0 | 0 | 0 |
| D-2 검수 상태머신 | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **52** | **50** | **1** | **1** | **0** | **0** | **0** |

PASS율 50/52 = 96.2% (PARTIAL 제외 기준). 실동작 근거 판정 48/52 = 92.3%.


### [D-ISSUE-23] D-3 전 26건 — 카탈로그 근거 `file:line` 전면 드리프트
- **심각도**: LOW (카탈로그 정합성)
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 검증자가 바로 열어볼 수 있는 위치여야 한다. 어긋나면 검증자가 매번 재탐색해야 하고, 최악의 경우 **엉뚱한 코드를 근거로 PASS** 를 준다.
- **현재 동작(이슈 내용)**: D-3 의 `AssignmentService.java:*` 근거가 **26건 전부 어긋난다**(+14~+58행). 원인은 2026-07-31 이후 `rejectUnenrolledDerivatives`(현재 `:175-186`) 신설 + 재배정 게이트 주석 확장(커밋 `bd7ba946`·`284d81e2`·`31ecee47`).

  | TC | 카탈로그 | 실제(qa-0801 `56d30478`) |
  |---|---|---|
  | 001 | 57-114 | **71-129** |
  | 002 | 59 | **73**(호출) / **675-677**(판정) |
  | 003 | requireReviewer | **668-678** |
  | 004 | 62-64 | **76-78** |
  | 005 | 65-67 | **79-81** |
  | 006 | 86-88 | **101-103** |
  | 007 | 102-104,150-175 | **117-119, 195-220** |
  | 008 | 161-174 | **206-219** |
  | 009 | 177-244 | **222-302** |
  | 010·025·026 | 190-195 / 187-195 | **235-240** |
  | 011 | 182-183 | **227-228** |
  | 012 | 200-202 | **258-260** |
  | 013 | 204-217 | **265-275** |
  | 014 | 225-241 | **283-299** |
  | 015 | 264-270 | **322-328** |
  | 016 | 257-303 | **315-361** |
  | 017 | 258-260 | **316-318** |
  | 018·019 | 305- | **377-** |
  | 020 | 305- | **470-482**(`scopeForActor`) |
  | 021 | upsertDataStts | **663-666** |
  | 022 | 69,130-141 | **83, 145-156** |
  | 023 | 130-141 | **145-156** |
  | 024 | 89-98 | **104-113** |

  (대조: **D-7 의 `TaskQueryController`/`TaskQueryService`/`LabelAccessGuard` 근거 8건은 전부 정확**. `TaskQueryService.java:107-143`(TC-NOTIFY-027)만 실제 `107-137` + `234-245` 로 소폭 어긋남.)
- **재현/확인 경로**: `sed -n '71,129p' backend/src/main/java/kr/co/cudo/authoring/assignment/service/AssignmentService.java`
- **영향**: 검증 효율/정확도. 기능 영향 없음.
- **수정 방향(제안)**: `D-review-version-notify.md` D-3 표의 근거 컬럼을 위 대응표로 일괄 갱신한다. 근본적으로는 라인 번호 대신 **메서드명 앵커**(`AssignmentService#rejectApprovedTargets`)로 표기하면 드리프트가 사라진다. ⚠ 구현하지 않음.


### [D-ISSUE-24] TC-NOTIFY-028 — `frameIds` 상한 초과 400 응답에 컨트롤러 메서드명이 노출된다
- **심각도**: LOW (CWE-209, 경미)
- **기대 동작(기대효과)**: `rules/api-design.md`·`security.md` — 에러 응답에 내부 구현 정보(클래스·메서드·경로)를 담지 않는다. 사용자에게 보여줄 문구만 내려야 한다.
- **현재 동작(이슈 내용)**: `@Validated` + 파라미터 `@Size` 위반이 `ConstraintViolationException` 으로 전역 핸들러를 타면서 위반 경로가 그대로 실린다.
  ```
  GET /v1/tasks/4/labels?frameIds=<101개>
  → 400 {"errorCode":"INVALID_INPUT",
         "message":"getLabels.frameIds: frameIds 는 최대 100개까지 지정할 수 있습니다."}
  ```
  `getLabels.` 접두는 `TaskQueryController#getLabels` 메서드명이다(`TaskQueryController.java:87-92`).
- **재현/확인 경로**:
  ```bash
  IDS=$(python3 -c "print(','.join(str(i) for i in range(1,102)))")
  curl -s -G http://localhost:18081/api/v1/tasks/4/labels --data-urlencode "frameIds=$IDS" -H "Authorization: Bearer $REV"
  ```
- **영향**: 보안(정보 노출) — 실질 위험은 낮으나 Fortify/CodeQL 의 *System Information Leak* 룰에 걸릴 수 있는 형태. 같은 패턴이 `@Validated` 를 쓰는 다른 컨트롤러 전반에 있을 가능성이 높다(전역 이슈).
- **수정 방향(제안)**: `GlobalExceptionHandler` 의 `ConstraintViolationException` 핸들러에서 `propertyPath` 의 **마지막 노드(파라미터명)만** 남기거나 메시지만 취해 응답을 조립한다(`getLabels.frameIds` → `frameIds`). ⚠ 구현하지 않음. (A 클러스터 TC-EXC 담당과 중복 가능 — 병합 시 확인)

---

## 4. 이월 이슈 대조

| 이월 이슈 | 케이스 | 이번 회차 상태 |
|---|---|---|
| **D-ISSUE-05** IN_REVIEW 상태 재배정 허용 | TC-ASSIGN-026 | **미해소 이월.** 실동작 재확인 — raw43 을 `submit`→`start` 로 IN_REVIEW 전이시킨 뒤 `PATCH /v1/assignments/47 {workerId:2002}` → **200**, 상태 IN_REVIEW 유지. 가드(`:235-240`)는 `STTS_APPROVED` 만 차단한다. 부작용 실측: 재배정 후 승인 응답의 `workerName` 이 **제출자가 아니라 새 배정자**(정작업)로 표시되어 "누가 제출한 검수인지" 가 뒤바뀐다. 새 이슈 ID 를 부여하지 않고 D-ISSUE-05 로 이월 유지 — **정책 확정 필요**(IN_REVIEW 재배정을 허용할지, 허용 시 검수 제출 이력의 작업자 표시를 제출 시점으로 동결할지) |
| **D-ISSUE-01** APPROVED 무검증 ASSIGNED 강등 | TC-ASSIGN-022 | **해소 확인.** 409 + APPROVED 유지 실측 |
| **D-ISSUE-02** 동시 재배정 이력 중복 | TC-ASSIGN-014 | **해소 확인.** 4병렬 → 이력 1행 |
| **D-ISSUE-45** 관제 조회 무페이징 | TC-NOTIFY-028/029 | **해소 확인.** labels·meta 모두 기본 20 / 상한 100 클램프 실측 |
| **D-ISSUE-41** 검수자명 null 하드코딩 | TC-NOTIFY-026 | **해소 확인.** `reviewerName:"김검수"` 가 승인 이벤트 → 사용자 마스터 실조회로 채워짐 |
| **UNCERTAINTIES #4** 관제 조회 IDOR(반전) | TC-NOTIFY-052 | **반전 사실 실동작 확정.** 세 경로 모두 `verifyRawAccess` 배선, 미배정 WORKER 403. UNCERTAINTIES 원본을 "✅ 확정 — 실동작 검증 완료(2026-08-01)" 로 갱신 제안 |

---

## 5. 테스트 커버 대조 (`_raw/test-baseline.md` 기준 — 전건 통과, 실패 0)

| 범위 | 커버 테스트 |
|---|---|
| TC-ASSIGN-001/002/004/006/009/015/016/018 | `AssignmentControllerTest`(REVIEWER 배정 LABELER INSERT · WORKER 403 · 중복 409 · 재배정 이력 · IDOR 403/200 · 본인 배정만 반환) |
| TC-ASSIGN-003/007/010/016/017/020/022/023 | `AssignmentServiceTest`(actor null UNAUTHORIZED ×2 · reviewerId 반영 · 완료 배정 거부 · APPROVED 409 및 상태 유지 · 전체실패 · 이력 시간순 · PORTAL 403) |
| TC-ASSIGN-014/025 | `AssignmentReassignConcurrencyIT`(4병렬 1건만 200·이력 1행 / VER 매핑 / **공유잠금으로 승인 UPDATE 직렬화 H4-b**) |
| TC-ASSIGN-024 | `AssignmentOptimisticLockConflictIT`(assign 중 낙관적잠금 충돌 → 500 아닌 409) |
| TC-ASSIGN-005/008/011/012/013/019/021/026 | **전용 자동테스트 미확인** — 이번 회차 실동작으로만 검증됨(회귀 가드 부재, 신규 작성 후보) |
| TC-NOTIFY-026~031/052 | `TaskQueryControllerTest`(요약/라벨/메타 200 · 기본 20 · size 10000 클램프 · frameIds 필터 · 101개 400 · 404 · 401 · **배정되지 않은 WORKER 403** · 원본 경로 미포함) |
| TC-NOTIFY-026~030 | `TaskQueryServiceTest`(COUNT 집계 · 검수자명 실측/미지어냄 · frameIds 페이징 전 적용 · totalElements 정합 · 클램프 · 메타 페이징) |
| TC-NOTIFY-053 | `DeidentReportGateCoverageIT` 가 게이트 공유 호출부를 커버. 관제 라벨 경로 전용 단정은 미확인 → 신규 작성 후보 |

---

## 6. 검증 중 생성한 데이터 (기존 데이터 미변경, INSERT/상태전이만)

| 대상 | 내용 |
|---|---|
| `ls_task_assignment` | 47·49·50·51·54·59·62·64·65·66·67 신규(raw 43/44/52/55/66/69) |
| `ls_task_assign_history` | hstry_seq 8·10·14 등 재배정 이력 |
| `ls_task_event_log` | raw 43/44/52/55/66/69 의 ASSIGN·REASSIGN·검수 이벤트 |
| `ls_raw_data_status` | raw 43·52·55·66 신규 행 및 상태 전이(→APPROVED 등) |
| ⚠ 참고 | **D-ISSUE-22 반증용으로 raw69 에 비-WORKER 배정 3행(3001/1002 LABELER, 2002 REVIEWER)이 남아 있다.** 다른 클러스터 검증 시 이 행을 정상 시드로 오인하지 말 것 |


### [D-ISSUE-42] TC-DIFF-006 — 인가보다 해시 조회가 선행해 버전 존재 여부 오라클이 성립
- **심각도**: LOW
- **기대 동작(기대효과)**: 인가 실패자가 시스템 내부 상태(특정 버전 해시의 존재 여부)를 응답 코드 차이로 알아낼 수 없어야 한다(CWE-209 / OWASP A01).
- **현재 동작(이슈 내용)**: `diff` 는 해시 조회를 먼저 하고 인가를 나중에 한다.
  ```java
  // VersionService.java:338-349
  LsLabelVersion fromVersion = findByHashOrThrow(fromHash, "from 버전을 찾을 수 없습니다."); // ← 404
  LsLabelVersion toVersion   = findByHashOrThrow(toHash,   "to 버전을 찾을 수 없습니다.");
  requireFrameScoped(fromVersion, "from");
  LsDataSrc fromSrc = accessGuard.verifyAndGet(fromVersion.getDataSrcSn(), actor);          // ← 403
  ```
  실측(미배정 WORKER 토큰): 존재하는 해시 → **403**, 존재하지 않는 해시 → **404**. `findByVersionHash` 는 srcSn 제한이 없는 **전역 조회**라 남의 영상 버전도 판별된다.
- **재현/확인 경로**:
  ```bash
  # 미배정 WORKER 토큰으로
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/{존재하는해시}/diff?compareWith={존재하는해시}" -H "Authorization: Bearer $WK"  # 403
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/deadbeef/diff?compareWith=deadbeef" -H "Authorization: Bearer $WK"              # 404
  ```
- **영향**: 정보 노출(CWE-209). 실효 위험은 낮다 — 해시가 SHA-256 이라 무작위 추측이 불가능하고, 판별하려면 이미 페이로드를 알고 있어야 한다. 그럼에도 "특정 라벨 상태가 승인된 적 있는가"를 인가 없이 확인할 수 있는 채널이다.
- **수정 방향(제안)**: 우선순위가 높지 않으므로 **현행 유지도 수용 가능**. 정정한다면 `diff` 를 `rollback` 과 같은 **srcSn 스코프 진입점**으로 정렬하는 것이 근본책이다(요청에 srcSn 을 받아 `accessGuard.verifyAndGet(srcSn)` 을 먼저 수행 → `findByDataSrcSnAndVersionHash` 조회). 이는 D-ISSUE-45 도 동시에 해소한다.


### [D-ISSUE-64] TC-NOTIFY-019 — 백오프 javadoc(1,2,4,8,16)이 실제 동작(2,4,8,16,32)과 어긋난다
- **심각도**: LOW
- **기대 동작(기대효과)**: 운영자가 재시도 스케줄을 문서로 예측할 수 있어야 한다(장애 대응 시 "언제 다시 시도되나" 판단 근거).
- **현재 동작(이슈 내용)**:
  ```java
  // LsControlNotifyFallback.java:188-210
  /** <p>백오프: 2^retryCount 분 (1, 2, 4, 8, 16). cap 60분. */
  public boolean failAndSchedule(String error) {
      this.rtryCnt += 1;                                            // ← 증가 후 계산
      long backoffMin = Math.min(60L, 1L << Math.min(this.rtryCnt, 6));   // n=1 → 2분
  ```
  실측: 첫 실패의 `next_rtry_dt` 가 `23:56:20 → 23:58:20` = **2분**(javadoc 은 1분). 실제 수열은 2,4,8,16,32,(60 cap).
- **재현/확인 경로**: `SELECT rtry_nmtm, mdfcn_dt, next_rtry_dt FROM ls_control_notify_fallback WHERE idmp_key='qa-d6-badjobid-1';`
- **영향**: 문서 정합만(동작 결함 아님). 기대 총 재시도 소요시간이 실제의 절반으로 오인된다.
- **수정 방향(제안)**: javadoc 을 `(2, 4, 8, 16, 32 …cap 60)` 으로 정정하거나, 의도가 1분 시작이면 `1L << (rtryCnt-1)` 로 코드를 맞춘다(둘 중 무엇이 의도인지 확인 필요). ⚠ 본 검증에서는 구현하지 않음.

---

## 3. 미완결·제약 사항

| 항목 | 내용 |
|------|------|
| TC-NOTIFY-041 실동작 | 실패 export(`export_sn=18`) 회수는 `retry-delay-minutes=10` + Quartz `interval-sec=900`(15분) 조건이라 회수 tick 도달 전에 **다른 QA 세션이 `ls_data_raw#72` 를 삭제**해 관측 창이 사라졌다(회수기 로그는 계속 `no retryable failed export`). 회수 **로직 자체**는 `DatasetExportFailureRecoveryIT` 가 커버. |
| TC-NOTIFY-044 | 크로스노드는 2노드 기동이 필요해 실동작 미검증(조건부 원자 UPDATE 정적 확인 + `ControlNotifyDebounceCrossNodeIT` 로 대체). |
| TC-NOTIFY-043 | 토글 off 형상(dev/stg/prd 기본)은 본 스택이 `CONTROL_NOTIFY_ENABLED=true` 라 실동작 미관측(정적 + 테스트 2건). |
| 병행 간섭 | 검증 중 다른 QA 세션이 같은 스택에서 rawSn 26·72·900001 을 구동했다. 본 문서의 로그·DB 근거에는 그 트래픽이 섞여 있으나, 각 근거는 rawSn·queue_sn·타임스탬프로 특정되어 판정에 영향 없음. |
| 잔여 테스트 데이터 | `ls_control_notify_fallback` 의 `idmp_key LIKE 'qa-d6-%'` 7행은 검증 흔적으로 남겨 둠(기존 행 미변경). 정리 필요 시 `DELETE FROM ls_control_notify_fallback WHERE idmp_key LIKE 'qa-d6-%';` |

## 4. 근거 드리프트 (카탈로그 file:line 정합성)

| TC | 카탈로그 표기 | 실제 위치 | 비고 |
|----|------|------|------|
| TC-NOTIFY-008 | `ControlNotifyDebouncer.java:174-176` | `:175-177` | +1 |
| TC-NOTIFY-009 | `:80,190-203` | `:81,191-204` | +1 |
| TC-NOTIFY-010 | `:100-103,141-166` | `:101-104,142-167` | +1 |
| TC-NOTIFY-011 | `:289-320` | `:292-295,312-321` | 요약 로그 위치 이동 |
| TC-NOTIFY-025 | `ControlNotifyDebouncer.java:72-74` | `:73-75` | +1 |
| TC-NOTIFY-043 | `ControlNotifyDebouncer.java:285-308` | `:286-309` | +1 |
| TC-NOTIFY-044/045 | `:241-269` / `:82-83` | `:242-270` / `:84` | +1 |
| TC-NOTIFY-019/020/023 | `LsControlNotifyFallback.java:failAndSchedule` / `:sanitize` | `:194-213` / **`sanitizeError` `:246-253`** | 메서드명 드리프트(`sanitize`→`sanitizeError`) |
| TC-NOTIFY-047 | `ControlNotifyPayloadFactory.java:65-66,150-174` | 동일 | 정합 |

> 그 외 인용 라인(ControlNotifyService·ControlNotifyClient·PayloadFactory·DTO·AsyncDatasetExportRunner·DatasetExportBridge·DatasetExportFailureRecoverer)은 **전부 정합**.

## 5. UNCERTAINTIES 갱신 제안

- **#3 TASK_COMPLETED payload** → **확정 종결 가능**. 6필드 평면 snake_case + 경로 `/api/data-set/v2/jobs/{job_id}/notify-*` 를 실왕복 페이로드 원문으로 확증, 상수 self-fill 0건.
- **#26 관제 통지 인증 헤더 미부착** → **사실 확정**(코드상 미배선). 관제팀 협의 항목으로 유지 + D-ISSUE-62.
- **#27 관제 `event_type_cd` 코드값 목록 미수령** → **사실 확정 + 악화 근거 추가**. DB 안에 `INTRUSION`/`EV02000201` 두 계통이 공존한 채 그대로 전송된다(D-ISSUE-63).
- **신규**: `AsyncDatasetExportRunner` 의 "export 성공 후에만 통지" 규약이 **예외 경로에서만** 성립한다는 사실(D-ISSUE-61) — 5C HIGH-D 항목의 재검토 필요.


### [D-ISSUE-81] TC-VERSION-007 — `commitApproved`의 `rawSn==null` 가드가 처리되지 않은 예외로 이어져 500 응답
- **심각도**: LOW
- **기대 동작(기대효과)**: `VersionService.commitApproved(rawSn, actor)`는 `rawSn==null`일 때 의미있는 4xx(예: `INVALID_INPUT`/400)로 실패해야 한다. 카탈로그 기대결과는 "IllegalArgument/UNAUTHORIZED(401)"로, 명확한 클라이언트 오류 응답을 전제한다.
- **현재 동작(이슈 내용)**: `VersionService.java:160-162`가 순수 `throw new IllegalArgumentException("rawSn 은 필수입니다.")`를 던지는데, `GlobalExceptionHandler`(`backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java`)에는 `IllegalArgumentException` 전용 `@ExceptionHandler`가 없다(`CustomException`/`MethodArgumentNotValidException`/`ConstraintViolationException`/`HttpMessageNotReadableException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`DataIntegrityViolationException`/`AccessDeniedException`/`AuthenticationException`/`MaxUploadSizeExceededException`/`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/`Exception` 뿐). 따라서 `:273 @ExceptionHandler(Exception.class)` 제네릭 500 핸들러로 떨어진다.
  ```java
  // VersionService.java:160-165
  if (rawSn == null) {
      throw new IllegalArgumentException("rawSn 은 필수입니다.");
  }
  if (actor == null) {
      throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
  }
  ```
- **재현/확인 경로**: `commitApproved`는 REST로 직접 노출되지 않고 `ReviewService.approve()`가 `stts.getRawDataId()`(항상 non-null FK)로만 호출하는 내부 전용 메서드다. 따라서 **현재 시점엔 실 HTTP 요청으로 재현 불가**(내부 단위테스트에서 `versionService.commitApproved(null, actor)` 직접 호출 시에만 확인 가능하며, 해당 테스트도 리포지토리에 없음 — `grep -rn "commitApproved(null" backend/src/test` 0건).
- **영향**: 기능 영향 낮음(공개 API 경로에서 도달 불가). 다만 향후 `commitApproved`를 다른 컨텍스트(예: 관리자 수동 재스냅샷 API)에서 재사용하면 이 가드가 그대로 500을 낼 수 있어 잠재 함정이다. OWASP A10:2025(Mishandling of Exceptional Conditions) 관점에서 "의미있는 4xx" 원칙과 어긋남.
- **수정 방향(제안)**: `IllegalArgumentException`을 `CustomException(ErrorCode.INVALID_INPUT, ...)`으로 교체하거나, `GlobalExceptionHandler`에 `IllegalArgumentException → 400` 핸들러를 추가한다. 후자는 프로젝트 전역에 영향을 주므로 전자(로컬 교체)가 더 안전. ⚠ 구현은 하지 않는다.



## 클러스터 E — 증강/해상도/Export/메타

> ⚠ E-part5(TC-EXPORT-018/019)가 D-ISSUE-61(CRITICAL)을 재확인 — canonical은 D-ISSUE-61. 신규 등록 안 함. 단, 신고 게이트 2경로는 CustomException을 던져 통지 보류가 실제로 작동함을 이 파트가 추가로 밝힘(규약이 예외 경로에서만 성립한다는 근본원인 정밀화).

### [E-ISSUE-01] TC-AUG-012 / TC-AUG-013 / TC-AUG-014 (+015·018 변별력) — 중복 증강 차단 케이스가 폐기된 정책을 검증하고 있다
- **심각도**: HIGH (카탈로그 정합 — 다음 회차에서 거짓 FAIL 3건을 만든다)
- **기대 동작(기대효과)**: 케이스 카탈로그는 현행 확정 정책만 검증해야 한다. 폐기된 정책을 검증하면 다음 검증자가 "409 가 안 나온다 → 결함"으로 오판하고, 이 프로젝트가 명시적으로 금지한 **"철회된 정책 재시도"** 사이클(조상/자손 전파 4라운드 전례)이 재발한다.
- **현재 동작(이슈 내용)**: 카탈로그(2026-07-30)는 409 차단 + DB 부분 유니크 최종 방어를 기대하나, 2026-07-31 사용자 확정으로 전량 제거됐다.
  - `CLAUDE.md:243` — *"★중복 증강 요청은 BE 가 차단하지 않는다 … ①서비스 사전 조회 가드 ②409 응답·안내 문구 ③부분 유니크 인덱스 `UK_LS_DATA_AUG_ACTVTN` ④`LsDataAug.ACTIVE_STATUSES` 를 모두 제거했다. … ⚠ 되돌리기 금지"*
  - `V153__add_ls_data_aug_prompt_and_drop_active_unique.sql` — `DROP INDEX IF EXISTS UK_LS_DATA_AUG_ACTVTN;`
  - `AugmentRequestService.java:68-73` javadoc — *"사전 조회 가드·안내 문구·제약 위반 409 분기·인덱스(V153 DROP)를 모두 제거했다."*
  - `LsDataAug.java:65` — *"폐기 이력 — 구 ACTIVE_STATUSES(PENDING·ACCEPTED) 상수는 제거됐다 (2026-07-31)."*
- **재현/확인 경로**:
  ```bash
  # 1) 이미 ACCEPTED WINTER 가 있는 rawSn=4 에 동일 요청 → 200
  curl -s -X POST localhost:18081/api/v1/augments/request -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' \
    -d '{"videoIds":[4],"types":["WINTER"],"prompt":{"time":"A","season":"B","weather":"C","terrain":"D","severity":"E"}}'
  # 2) 인덱스 부재 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select indexname from pg_indexes where tablename='ls_data_aug';"   # uk_ls_data_aug_actvtn 없음
  ```
- **영향**: 기능 영향 없음(현행 동작이 정본과 일치). 검증 프로세스 영향 — 다음 회차 거짓 FAIL 3건 + 정책 되돌리기 위험.
- **수정 방향(제안)**: `E-augment-resolution-export-meta.md` §E-1 에서 TC-AUG-012/013/014 를 `~~취소선~~` + `**[폐기 2026-07-31]** 중복 차단 정책 폐기(CLAUDE.md:243 / V153 DROP), 대체 케이스 = "동일 (영상×종류) 재요청 200"` 으로 전환. TC-AUG-015 는 "REJECTED 후 재요청 허용"에서 "**모든 상태에서 재요청 허용**"으로 기대결과 갱신. TC-AUG-018 기대결과에 "V153 이후 인덱스 부재 — 과거 마이그레이션 가드 검증용" 각주 추가. `UNCERTAINTIES.md` ★ 절에 4번째 확정 정책으로 등재 권장(되돌리기 금지 관례 명문화).


### [E-ISSUE-81] TC-EXPORT-005 / TC-EXPORT-019 — PARTIAL export 는 통지되지만 데이터마트 뷰에서 배제되고 회수기 대상도 아니다 (관제 동기화 사각지대)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` — *"통지는 export 성공 후 발송한다"* + *"기 구축된 데이터 마트 연계 관리기능 … 데이터마트 학습데이터셋의 라벨링 정보 동기화"*. 관제는 통지를 받으면 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`/`FRAME_CNT` 로 산출물을 픽업한다. 따라서 **통지가 나간 산출은 반드시 뷰에서 보여야** 하고, 보이지 않는 산출은 재산출 회수 대상이 되어야 한다.
- **현재 동작(이슈 내용)**: `PARTIAL` 종결은 ①통지는 정상 발송되고 ②뷰에서는 **배제**되며 ③회수기 대상도 **아니다** — 세 판정 기준이 서로 어긋난다.
  - 뷰(실측 `pg_get_viewdef('v_completed_video')`):
    ```sql
    LEFT JOIN LATERAL (SELECT ex.export_path_nm, ex.frame_cnt
                         FROM ls_dataset_export ex
                        WHERE ex.data_raw_sn = m.raw_sn
                          AND ex.export_stts_cd::text = 'SUCCEEDED'::text   -- ← PARTIAL 제외
                        ORDER BY ex.export_ver_no DESC LIMIT 1) e ON true
    ```
  - 통지: `DatasetExportService.java:211-222` 가 PARTIAL 을 **정상 마감**(`finalizeUnlessUnderDeidentReport(..., partial=true)`)하고 예외 없이 반환 → `AsyncDatasetExportRunner.doExport:121-130` 이 `true` → 완료 이벤트/`afterExport` 통지 실행.
  - 회수기: `LsDatasetExportRepository.findRetryableFailedAnchors` 는 `e.EXPORT_STTS_CD = 'FAILED'` 만 앵커로 삼는다 → 최신이 PARTIAL 이면 **영원히 재시도되지 않는다**.
  - 멱등 baseline 은 PARTIAL 을 **포함**(`DatasetExportTxService.java:150-153`)하므로 재동결 경로에서는 "이미 산출됨"으로 skip 된다.
  - **실측(2026-08-02 08:50, rawSn=94)**:
    ```
    08:50:38.972 WARN  DatasetExportService - partial export rawSn=94 version=2 written=10 skipped=2
    08:50:38.977 INFO  ControlNotifyService - TASK_MODIFIED sent rawSn=94 frames=1 videoLevel=0 reExport=true
    ```
    디스크에는 `…/94/v2/{orgnl,deid}/0000.jpg…` 가 실재하는데,
    ```
    klid_system=# select raw_sn, export_path_nm, frame_cnt from v_completed_video where raw_sn=94;
     94 | /app/storage/raw/autolabel-test/94 | 10     ← v1(SUCCEEDED)의 값. v2 는 뷰에 전혀 반영 안 됨
    ```
    `EXPORT_PATH_NM` 이 영상 루트 고정이라 경로는 우연히 같지만, **`FRAME_CNT` 는 구 버전 값**이고 최신 산출의 존재/버전은 관제가 알 방법이 없다.
  - **더 심각한 경계**: 영상의 **최초 export 가 PARTIAL** 이면 SUCCEEDED 행이 하나도 없어 `EXPORT_PATH_NM`·`FRAME_CNT` 가 **NULL** 이다. 관제는 TASK_COMPLETED 를 받고 뷰를 조회했는데 산출물 경로를 못 찾고, 회수기도 집지 않아 **영구 미동기화**가 된다. 이는 `DatasetExportFailureRecoverer` javadoc 이 만들어진 계기(*"EXPORT_PATH_NM 이 NULL 인 행이 영구히 노출"*)와 정확히 같은 증상인데 FAILED 축만 막혀 있고 PARTIAL 축은 열려 있다.
- **재현/확인 경로**:
  ```sql
  -- 1) APPROVED 영상 한 건에 원천 이미지가 없는 프레임을 1건 추가(부분 산출 유도)
  INSERT INTO ls_data_src (raw_sn, frm_no, src_file_path_nm, de_idntf_src_file_path_nm, vdo_frm_no)
  VALUES (:rawSn, 99, '/app/storage/raw/.../missing.jpg', '/app/storage/deidentified/.../missing.jpg', 990);
  ```
  ```bash
  # 2) 승인 후 수정 트리거(재export) — 60초 디바운스 후 flush
  curl -X PUT localhost:18081/api/v1/frames/{srcSn}/description -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"srcSn":{srcSn},"description":"partial probe"}'
  ```
  ```sql
  -- 3) PARTIAL 로 마감됐는데 통지는 나갔고, 뷰에는 반영되지 않음
  SELECT export_ver_no, export_stts_cd, frame_cnt FROM ls_dataset_export WHERE data_raw_sn = :rawSn ORDER BY 1;
  SELECT raw_sn, export_path_nm, frame_cnt FROM v_completed_video WHERE raw_sn = :rawSn;
  ```
- **영향**: 데이터정합 — 관제/데이터마트가 **최신 학습데이터 버전을 픽업하지 못한다**(사업 요구 "라벨링 정보 동기화" 미충족). 최초 export 가 PARTIAL 인 영상은 산출물이 디스크에 있는데도 데이터마트에서 **미산출로 보인다**. 자동 회수 경로가 없어 운영자가 수동 개입해야 하며, 관측 지표(`result{outcome=partial}`)를 보지 않으면 무증상으로 누적된다.
- **수정 방향(제안)**: 셋 중 하나로 **판정 기준 3곳을 일치**시킬 것 — ⓐ 뷰(`V_COMPLETED_VIDEO` LATERAL 조인)의 상태 필터를 `IN ('SUCCEEDED','PARTIAL')` 로 넓히고 부분 산출임을 알리는 컬럼(예: `EXPORT_STTS_CD`)을 함께 노출, 또는 ⓑ `findRetryableFailedAnchors` 의 앵커 조건에 `PARTIAL` 을 포함해 회수 대상으로 삼되 `max-attempts` 로 상한(원천 이미지가 영구 부재면 무한 재시도가 되므로 필수), 또는 ⓒ PARTIAL 을 "통지하지 않는 종결"로 재정의(통지 보류 + 회수 대상화). ⓐ+ⓑ 조합이 요구(비교·복구)와 가장 정합적이며, 어느 쪽이든 `DatasetExportTxService` 의 멱등 baseline(PARTIAL 포함)과 함께 일관되게 조정해야 한다. **구현은 하지 않는다.**


### [E-ISSUE-02] TC-AUG-001 — 요청 입력 계약에 필수 `prompt` 5필드가 누락돼 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스의 입력열이 그대로 실행 가능해야 한다. 그렇지 않으면 검증자가 400 을 받고 "요청 API 결함"으로 오판한다.
- **현재 동작(이슈 내용)**: 2026-07-31 부터 `prompt`(time/season/weather/terrain/severity) 5필드가 **전부 필수**다. `AugmentRequestRequest.java` `@NotNull(message="prompt 는 필수입니다.") @Valid PromptFields prompt` + 각 필드 `@NotBlank @Size(max=50)`. 서비스도 `buildPrompt`(`AugmentRequestService.java:247-258`)로 fail-closed 재확인하고 `VisibleTextNormalizer` 로 보이지 않는 문자까지 제거한다. 카탈로그 입력열 `{videoIds:[N], types:["WINTER"]}` 로 보내면 **400**.
- **재현/확인 경로**: `curl … -d '{"videoIds":[4],"types":["WINTER"]}'` → `400 {"message":"prompt: prompt 는 필수입니다.","errorCode":"INVALID_INPUT"}`
- **영향**: 기능 영향 없음. 카탈로그 재현성 훼손.
- **수정 방향(제안)**: TC-AUG-001 입력열에 `prompt` 5필드 추가, 기대결과에 `PROMPT_CN` 원문 적재 단언 추가. `CLAUDE.md:242` 가 이미 정본을 서술하고 있으므로 케이스만 맞추면 된다. 아울러 E-ISSUE-04 의 신규 케이스 6건과 함께 반영.


### [E-ISSUE-03] TC-AUG-020 / TC-AUG-021 — 검수 축 분리(2026-07-31) 이전 구조를 기대·근거로 삼고 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 기대결과가 실제 소유 컬럼을 가리켜야 한다. 지금 표현대로면 검증자가 `LS_DATA_AUG.AUG_PROC_STTS_CD` 의 무변경을 "전이 실패 = 결함"으로 오판한다.
- **현재 동작(이슈 내용)**: accept/reject 는 더 이상 `aug.applyReviewStatus(...)` 를 부르지 않는다. `AugmentReviewService.java:344-350` javadoc — *"★ 생성 결과 컬럼(`AUG_PROC_STTS_CD`)은 건드리지 않는다 (2026-07-31 확정) … 두 축을 다시 합치지 말 것"*. 재결정 차단은 `LsDataAugRvw.ensurePending`(`:241`)이 담당하며 메시지도 `"이미 처리된 증강 검수입니다. status=ACCEPTED"` 로 바뀌었다. 실측: aug 14 accept 전후 모두 `aug_proc_stts_cd=ACCEPTED`, aug 22 reject 후에도 `ACCEPTED`.
  근거 라인도 전부 이동: `AugmentReviewService.java:336-354 → 359-374`, `LsDataAug.java:264 → LsDataAugRvw.java:241`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST localhost:18081/api/v1/augments/22/reject -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"reason":"x"}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select aug_proc_stts_cd from ls_data_aug where data_aug_sn=22;          -- ACCEPTED (무변경)
        select rvw_stts_cd from ls_data_aug_rvw where data_aug_sn=22;"          -- REJECTED
  ```
- **영향**: 기능 영향 없음. 오판 위험 + 근거 드리프트.
- **수정 방향(제안)**: TC-AUG-020 기대결과를 *"`LS_DATA_AUG_RVW.RVW_STTS_CD` PENDING→ACCEPTED, `LS_DATA_AUG.AUG_PROC_STTS_CD` **무변경**(생성 결과 축)"* 으로, TC-AUG-021 근거를 `LsDataAugRvw.ensurePending` 으로 교체. 응답 `augProcSttsCd` 가 검수행 상태를 싣는다는 점(`AugmentSummaryResponse.from`)도 명시.


### [E-ISSUE-04] E-1/E-2 — 2026-07-31~08-01 신설 동작 8종이 카탈로그에 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 신설 보안·정합 가드는 회귀 감시 대상이어야 한다. 케이스가 없으면 다음 회차에 조용히 사라져도 아무도 모른다.
- **현재 동작(이슈 내용)**: 아래 8종이 실동작으로 확인되나 E-1/E-2 어느 행에도 없다.
  1. **파생영상 증강 요청 차단** — `requireNotDerivative`(`AugmentRequestService.java:335-345`) → 400 `"파생 영상은 증강 요청 대상이 아닙니다."` (`CLAUDE.md:245` 파생 깊이 1 고정)
  2. **가드 순서 계약** — 인가(403) → 파생(400) → 미검수(400) → 신고(412) → 프레임(412). 파생 가드가 APPROVED 검증보다 **앞**이어야 한다는 명시 계약(`:157-164`)
  3. **미존재 rawSn 응답 계약 보존** — 404 아닌 400 NOT_REVIEWED(`:328-334`)
  4. **prompt 필수/공백/50자/보이지 않는 문자** 4종 검증
  5. **PROMPT_CN 원문 보관 = 전송본과 동일 dict**(`:276-286`)
  6. **types enum 이 prompt 로부터 파생되지 않음** (CWE-22 · `RESL_` 침범 차단)
  7. **`requireGeneratedResult`** — 생성 미완료/실패·취소 증강의 accept·reject 차단(409, 2문구 분기, `AugmentReviewService.java:503-520`). 이 가드가 없으면 파생영상 등재 게이트가 무력화된다
  8. **reject → `LS_DATA_AUG_DSCD` 폐기 표식** 동일 tx 기록(`:397`)
- **재현/확인 경로**: 본 문서 §1·§2 "카탈로그 미수록 동작" 표의 각 명령.
- **영향**: 회귀 감시 공백. 특히 7번은 "사람이 이미지를 보고 결정해야 작업목록에 올라간다"는 상위 요구를 지키는 가드라 유실 시 요구 위반이 조용히 발생한다.
- **수정 방향(제안)**: E-1 에 5건(1·2·3·4묶음·5/6), E-2 에 2건(7·8) 신규 케이스 추가. 7번은 우선순위 High.


### [E-ISSUE-23] E-3/E-3B 근거 file:line 드리프트 13건 (Phase C-3 + AugmentJobSuccessApplier 분리 미반영)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스의 `근거(file:line)` 는 다음 회차 검증자가 곧바로 열어 대조할 수 있어야 한다. 카탈로그는 검증 산출물의 입력 문서다.
- **현재 동작(이슈 내용)**: §4 표의 13건이 어긋난다. 대표 2건:
  - TC-AUG-069/071/072 의 근거 `GenAiCallbackService.java:214-237` 에는 이제 그 로직이 없다. `GenAiCallbackService` 는 172-175 에서 `successApplier` 에 위임만 한다.
    ```java
    private void applySucceeded(LsDataAugJob target, GenAiCallbackRequest req, String requestId) {
        List<String> outputs = successApplier.verifyOutputPaths(outputFilePathsOf(req), requestId);
        successApplier.applySucceeded(target, req.jobId(), outputs, requestId);
    }
    ```
  - TC-AUG-100/104/106~110 의 근거는 **동기 `.block()` 제출** 시절 라인이다. 현재는 `Flux.concatMap` 기반 논블로킹 제출(`dispatchChunks` 268-299, `submitChunkAsync` 311-357)로 전면 교체됐고 결과 기록은 `AugmentSubmitOutcomeRecorder`/`AugmentSubmitRollupTxService` 로 이관됐다.
- **재현/확인 경로**: 각 케이스의 근거 경로를 Read 후 §4 표의 실제 위치와 대조.
- **영향**: 기능 영향 없음. 다음 회차 검증 비용 증가 + 오판(코드가 사라진 것으로 오인) 위험.
- **수정 방향(제안)**: `docs/test-cases/E-augment-resolution-export-meta.md` 의 E-3/E-3B 근거 컬럼을 §4 표대로 갱신. 아울러 **신규 빈 3종**(`AugmentJobSuccessApplier`·`AugmentSubmitOutcomeRecorder`·`AugmentSubmitRollupTxService`)에 대한 케이스 신설을 검토(현재 `AugmentAsyncSubmitBoundaryIT` 8건이 커버하는 "지각 ACK 가 콜백 상태를 강등하지 않는다" 축은 카탈로그에 대응 케이스가 없다).


### [E-ISSUE-25] 만료 스윕의 롤업이 예외로 끝나면 클레임까지 롤백돼 그 job 이 **영구 회수 불가**(15분마다 무한 재시도)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 만료 스윕은 "비종결 job 은 반드시 종결된다"를 보장하는 최후 회수 장치다. `LsDataAugJobRepository.claimExpired` Javadoc·`GenAiCallbackService` 주석 모두 "재시도가 소진돼도 만료 스윕이 회수하므로 **무한 대기는 없다**"를 계약으로 명시한다. 따라서 회수 자체가 반복 실패하면 그 사실이 종결(dead-letter)되거나 최소한 격리돼야 한다.
- **현재 동작(이슈 내용)**: `AugmentJobExpiryTxService.expire`(63-89)는 **클레임 UPDATE 와 롤업이 같은 트랜잭션**이다.
  ```java
  @Transactional("controlTransactionManager")
  public boolean expire(Long augJobSn, Long dataAugSn, LocalDateTime cutoff) {
      ...
      if (jobRepository.claimExpired(augJobSn, cutoff, LsDataAugJob.ERR_EXPIRED, EXPIRY_REASON, LocalDateTime.now()) == 0) return false;
      ...
      AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobIdOf(jobs)); // ← 여기서 throw 하면
      ...                                                                                              //   위 클레임까지 롤백된다
  ```
  `rollUpIfAllTerminal` → `AugmentResultService.handle` 은 `requireJobIdNotOwnedByOtherAug`(271-285)에서 **다른 증강이 같은 `otsd_job_id` 를 보유하면 `CONFLICT`(409)를 던진다**. 스윕은 `RuntimeException` 을 잡아 ERROR 로그만 남기고 다음 후보로 넘어가므로(`AugmentJobExpirySweeper.java:219-224`) job 상태는 `RECEIVED` 그대로 → **다음 tick 의 후보 쿼리(`findExpirableAnchors`, `MDFCN_DT < cutoff`)에 다시 잡혀 같은 예외를 영구 반복**한다. 회수 실패에 대한 재시도 상한·dead-letter·별도 알림이 없다.
  실측(09:00:20 tick):
  ```
  WARN  AugmentResultService  otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 dataAugSn=770003 ownerDataAugSn=770001 otsdJobId=qa0802jobD
  ERROR AugmentJobExpirySweeper  [Augment][Expiry] expire failed augJobSn=770004 reason=CustomException
  ```
  ```
  aug_job_sn=770004 | job_stts_cd=RECEIVED | err_cd=(null)   ← 클레임이 롤백돼 회수 안 됨
  data_aug_sn=770003 | aug_proc_stts_cd=PENDING | dead_letter_at=(null)  ← 증강도 PENDING 영구 고착
  ```
- **재현/확인 경로**: 서로 다른 증강 2건의 job 이 **같은 `OTSD_JOB_ID`** 를 갖게 만든 뒤 한쪽을 만료시킨다. `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에는 UNIQUE 가 없고(`idx_ldaj_otsd_job_id` 는 비유니크) `LS_DATA_AUG.OTSD_JOB_ID` 에만 UNIQUE(`uk_aug_external_job_id`)가 있어 이 상태가 스키마상 허용된다.
  ```sql
  -- 재현 데이터(본 검증에서 실제로 사용)
  INSERT INTO ls_data_aug_job(aug_job_sn,data_aug_sn,job_seq,idmp_key,otsd_job_id,job_stts_cd,tot_nocs)
  VALUES (770004, 770003, 1, 'QA0802-E2-C-1', 'qa0802jobD', 'RECEIVED', 1);  -- 'qa0802jobD' 는 aug 770001 이 이미 보유
  UPDATE ls_data_aug_job SET mdfcn_dt = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE aug_job_sn=770004;
  -- 다음 스윕 tick(최대 15분) 후 → ERROR expire failed, 상태 변화 없음. 이후 매 tick 반복.
  ```
- **영향**: 기능/데이터 정합(liveness). 트리거는 **벤더가 서로 다른 `request_id` 에 같은 `job_id` 를 반환**하는 계약 이상이라 발생 확률은 낮지만, 발생하면 그 증강은 사람이 DB 를 직접 손대기 전까지 `PENDING` 에서 나오지 못하고 15분마다 실패 트랜잭션 1건 + ERROR 로그를 영구히 생성한다. 같은 구조상 롤업이 던지는 **모든** 예외(`DataIntegrityViolationException` → 409 포함)가 동일 결과를 낳는다. 보안 노출은 없다.
- **수정 방향(제안)**: ①`expire()` 에서 **클레임 커밋과 롤업을 분리**한다(클레임을 `REQUIRES_NEW` 로 먼저 커밋하면 롤업이 실패해도 job 은 종결 상태로 남아 다음 tick 이 재선정하지 않고, 롤업 재시도는 "전 job 종결" 경로가 다시 집는다). ②또는 회수 실패 횟수를 `LS_DATA_AUG_JOB` 에 누적해 임계 초과 시 dead-letter 로 종결한다. ③부수적으로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 UNIQUE 를 걸어 이 데이터 이상 자체를 입구에서 막는 안도 검토(단, 벤더 중복 반환 시 위탁이 깨지므로 트레이드오프 확인 필요).


### [E-ISSUE-41] TC-RESL-012 — 프레임 해상도 실측 실패가 400 이 아니라 500 으로 나가고, 400 가드는 도달 불가 사문화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 원본 프레임 이미지가 손상/미지원 포맷/부재라 해상도를 확인할 수 없으면 **400 INVALID_INPUT `"원본 프레임 해상도를 확인할 수 없습니다."`** 로 응답해야 한다. 이는 서버 장애가 아니라 **요청 대상 데이터 상태의 전제 불충족**이므로 4xx 여야 FE 가 사용자에게 "이 영상은 프레임이 깨져 해상도 변경이 불가"라고 안내할 수 있고, 5xx 알람(운영 오탐)을 만들지 않는다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.java:107-112`
  ```java
  int[] dim = measureFirstFrame(rawSn);
  int srcW = dim[0]; int srcH = dim[1];
  if (srcW <= 0 || srcH <= 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
  }
  ```
  그런데 실측기 `Java2DImageResizer.java:81-98` 은 **dim≤0 을 반환하는 경로가 없다** — `ImageIO.read` 가 IOException 이거나 null 이면 그 자리에서 `INTERNAL_ERROR("프레임 이미지를 읽을 수 없습니다.")` 를 던진다. 따라서 위 400 분기는 **어떤 입력으로도 도달할 수 없는 사문화 코드**이고, 실제 사용자는 항상 500 을 받는다.
  실측(rawSn=81, 첫 프레임 경로만 조작):
  - 비이미지 파일(mp4) → `HTTP 500 {"errorCode":"INTERNAL_ERROR","message":"프레임 이미지를 읽을 수 없습니다."}`
  - 파일 자체 부재 → 동일하게 `HTTP 500`
- **재현/확인 경로**
  ```sql
  -- 첫 프레임 소스를 읽을 수 없는 파일로 지정(허용 base 안이어야 경로가드를 통과)
  UPDATE ls_data_src SET de_idntf_src_file_path_nm='/app/storage/deidentified/frames/deid/81/NOPE.jpg'
   WHERE raw_sn=81 AND frm_no=0;
  ```
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/81/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' \
    -d '{"presets":["RESL_1080P"]}'
  # → HTTP/1.1 500 ... "프레임 이미지를 읽을 수 없습니다."
  ```
- **영향**: 기능/운영. ①데이터 상태 문제가 서버 오류로 분류되어 5xx 알람·SLO 오염 ②FE 가 4xx/5xx 로 분기하는 표준 처리에서 "재시도하면 될 일시 장애"로 오안내 ③해당 400 분기가 테스트로도 커버되지 않아(커버 갭) 회귀 감지 불가. 보안 등급은 아님(메시지에 경로·스택 미노출은 유지됨).
- **수정 방향(제안)**: `Java2DImageResizer.readImage` 의 실패를 호출부에서 구분 가능하게 만든다 — 예) 포트 계약을 `Optional<int[]> readDimensionsQuietly(Path)` 로 넓히거나, 실측 실패를 `ErrorCode.INVALID_INPUT` 계열 전용 예외로 승격하고 `VideoResolutionService.measureFirstFrame` 에서 400 메시지로 재던진다. 어느 쪽이든 `srcW<=0` 사문화 분기는 제거하거나 실제 도달 가능하게 배선해야 하며, "손상 프레임 → 400" 테스트를 추가한다. (⚠ 구현은 하지 않는다)


### [E-ISSUE-42] TC-RESL-013 — `presets` enum 이 숫자(ordinal)로도 바인딩돼 문자열 화이트리스트 계약을 우회한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `presets` 원소는 **`RESL_1080P`/`RESL_720P`/`RESL_480P` 세 문자열만** 허용되고 그 외 값·형식은 Jackson 역직렬화 단계에서 400 으로 거부돼야 한다(`ResolutionChangeRequest.java:16-17` 의 명시 계약, OpenAPI `allowableValues` 3종, "자유 입력 해상도 차단 CWE-20").
- **현재 동작(이슈 내용)**: Jackson 기본 동작상 **JSON 정수는 enum ordinal 로 해석**된다. 프로젝트에 `FAIL_ON_NUMBERS_FOR_ENUMS` 류 하드닝 설정이 없어(`application*.yml` 에 `spring.jackson` 블록 자체가 없음) 다음이 성립한다:
  - `{"presets":[0]}` → `RESL_1080P`, `{"presets":[1]}` → `RESL_720P`, `{"presets":[2]}` → `RESL_480P` 로 **정상 바인딩되어 서비스 로직까지 진입**
  - `{"presets":[99]}` → 400 (ordinal 범위 초과일 때만 거부)
  실측(rawSn=81, 해당 프리셋들이 이미 존재해 중복 실패로 마감되는 상태):
  ```
  {"presets":[0]}   -> HTTP 500 "요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."   ← 역직렬화 통과 + 실측 통과 + createOne 시도까지 진행
  {"presets":[1]}   -> HTTP 500 (동상)
  {"presets":[99]}  -> HTTP 400 "요청 본문이 올바르지 않습니다."
  {"presets":["RESL_240P"|"resl_720p"|"RESL_720P "|""|true|{}] } -> 전부 HTTP 400
  ```
  즉 **문자열 축만 화이트리스트가 서고, 숫자 축은 열려 있다.**
- **재현/확인 경로**
  ```bash
  # 파생이 아직 없는 APPROVED 원본에 대해 실행하면 ordinal 이 실제 프리셋으로 생성되는 것까지 관측된다
  curl -i -X POST http://localhost:18081/api/v1/videos/{approvedRawSn}/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{"presets":[2]}'
  # → RESL_480P 파생이 생성됨 (문서화되지 않은 표현)
  ```
- **영향**: 입력검증/계약(CWE-20). **값 공간이 넓어지지는 않으므로**(0~2 는 동일한 3개 프리셋) 권한상승·자유해상도 주입은 아니다. 실질 위험은 ①**계약 밖 표현이 허용돼 OpenAPI·연동규격과 실제 수용 입력이 어긋남** ②**순서 의존 취약** — 향후 `ResolutionPreset` 에 상수를 앞/중간에 추가하면 기존 숫자 페이로드가 **조용히 다른 프리셋으로 매핑**되어 잘못된 해상도 파생이 생성된다(무증상 데이터 오류) ③보안 스캐너/감리 관점의 "enum 화이트리스트 강제" 주장이 성립하지 않음.
- **수정 방향(제안)**: 전역 Jackson 설정에 `spring.jackson.deserialization.fail-on-numbers-for-enums: true` 를 켜거나(영향 범위가 넓으므로 전 DTO 회귀 확인 필요), 국소적으로 `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리(미지값 → `IllegalArgumentException`)를 두어 숫자 입력을 400 으로 떨어뜨린다. 아울러 `VideoResolutionControllerTest` 에 `{"presets":[0]}` → 400 케이스를 추가한다. (⚠ 구현은 하지 않는다)

---

## 남은 커버 갭 (이슈 아님 — 테스트 자산 보강 제안)

- TC-RESL-003: `ResolutionChangeRequest.resolvePresets` 중복 제거 단위 테스트 부재
- TC-RESL-012: 프레임 실측 실패 경로 테스트 부재 (E-ISSUE-41 이 드러나지 않은 직접 원인)
- TC-RESL-021: `statusOf` 의 `FAILED`/`IN_PROGRESS` 매핑 단언 부재 (COMPLETED 만 커버)


### [E-ISSUE-61] TC-RESL-054 — 파생 프레임 목적지 파일명이 부모 프레임 basename 에서 파생되어 **서로 다른 프레임이 같은 파일로 덮어써진다**(무경고 확정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 부모 프레임 N개는 파생 프레임 N개의 **서로 다른** 파일로 산출돼야 한다. Phase A 는 이미 "라벨 이중매핑"을 막으려고 중복 `videoFrameNo` 를 fail-fast 하는데(`ResolutionSnapshotService:195-199`), 같은 급의 위험인 **목적지 경로 충돌**에도 동일한 fail-fast 가 있어야 한다. 충돌을 허용하면 파생 프레임 i 의 라벨이 프레임 j 의 픽셀 위에 얹히는 데이터 정합 붕괴가 생기고, 그 산출물이 export→관제로 나간다.
- **현재 동작(이슈 내용)**: 목적지 파일명이 부모 `deidFilePath` 의 basename 그대로다. 중복 검사 없음.
  ```java
  // ResolutionSnapshotService.java:205-208
  Path fdst = resolveSafeDir(base,
          StorageSubtreePolicy.deidFramesDir(newRawSn) + "/" + fileNameOf(deidFrameSrc, pf));
  specs.add(new ResolutionSnapshot.FrameSpec(
          pf.getSrcSn(), pf.getFrameNo(), pf.getVideoFrameNo(), pf.getShtDt(), fsrc, fdst));
  // fileNameOf(231-234): Paths.get(frameSrc).getFileName()  ← 부모 파일명 그대로, 유일성 미검증
  ```
  실측(부모 906: frm0=`…/deid/26/frame-0.jpg`, frm1=`…/deid/27/frame-0.jpg`) → 파생 98 확정 **성공**(aug `ACCEPTED`), 그런데:
  ```
  src_sn | raw_sn | frm_no | vdo_frm_no | de_idntf_src_file_path_nm
     466 |     98 |      0 |          0 | /app/storage/deidentified/frames/deid/98/frame-0.jpg
     467 |     98 |      1 |         30 | /app/storage/deidentified/frames/deid/98/frame-0.jpg   ← 동일 경로
  $ ls /app/storage/deidentified/frames/deid/98/   →   frame-0.jpg  (1개뿐)
  ```
  두 프레임 행이 한 파일을 가리키고, 나중 리사이즈가 앞 프레임을 덮어썼다. 경고 로그도 없다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO ls_data_raw (raw_sn,vms_clip_id,vms_cctv_id,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,data_stts_cd)
   VALUES (906,'QA','C','ANONY','N','Y','/app/storage/raw/seed/clip-9103.mp4','COMPLETED');
  INSERT INTO ls_raw_data_status VALUES (906,'APPROVED',0,0,now(),0);
  INSERT INTO ls_data_src (raw_sn,frm_no,vdo_frm_no,src_file_path_nm,de_idntf_src_file_path_nm,sht_dt) VALUES
   (906,0,0,'/app/storage/raw/frames/raw/26/frame-0.jpg','/app/storage/deidentified/frames/deid/26/frame-0.jpg',now()),
   (906,1,30,'/app/storage/raw/frames/raw/27/frame-0.jpg','/app/storage/deidentified/frames/deid/27/frame-0.jpg',now());
  INSERT INTO ls_deident_proc_log (data_raw_sn,orgnl_file_path_nm,de_idntf_file_path_nm,proc_stts_cd)
   VALUES (906,'/x','/app/storage/deidentified/videos/resolution/26/77/RESL_720P.mp4','SUCCEEDED');
  ```
  ```bash
  curl -X POST localhost:18081/api/v1/videos/906/resolution -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"presets":["RESL_480P"]}'   # → 201, 이후 확정 성공
  ```
- **영향**: 데이터 정합(라벨↔픽셀 불일치) · export/데이터마트로 잘못된 프레임 쌍 유출. CWE-706(경로 → 리소스 잘못된 해석) / CWE-345(데이터 진정성 미검증). **실환경 도달성은 낮다** — 정상 추출은 `frames/deid/{rawSn}/frame-{n}.jpg` 로 raw 별 유일하고 `(RAW_SN, FRM_NO)` UK 가 basename 중복을 사실상 막는다. 다만 방어 없이 "관행"에만 의존하고 있고, 코드베이스 자체가 같은 급의 위험(`videoFrameNo` 중복)엔 fail-fast 를 둔 것과 비대칭이다.
- **수정 방향(제안)**: `ResolutionSnapshotService.buildFrameSpecs` 에서 ①`seenFrameKeys` 와 동일한 방식으로 `Set<Path> seenDst` 유일성 검사를 추가해 충돌 시 `INTERNAL_ERROR` fail-fast, 또는 ②더 근본적으로 목적지 파일명을 부모 basename 이 아니라 **파생 자신의 프레임 번호**(`frame-{frameNo}.{ext}`)로 결정론 생성. ②가 부모 경로 형식에 대한 의존을 없애 낫다.


### [E-ISSUE-83] TC-EXPORT-016 / TC-EXPORT-019 — 신고 게이트로 export 가 보류되면 디바운스 윈도우가 이미 `complete` 처리돼 그 수정 통지(변경 프레임 목록)가 영구 유실된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ControlNotifyDebouncer.claimAndSendIsolated` javadoc 의 자체 계약 — *"실패한 윈도우는 `complete` 를 호출하지 않아 저장소에 FLUSHING 으로 남는다 … 통지가 **소실되지 않고 지연**된다"*. 또 `CLAUDE.md` 는 TASK_MODIFIED 페이로드에 **변경 프레임 목록**(`SRC_SN` + 변경 종류)을 담도록 규정한다.
- **현재 동작(이슈 내용)**: 재생성 동반 윈도우(`exportRegenerated=true`)의 실제 전송은 `@Async` 러너에 위임되고 **즉시 반환**하므로, 그 뒤의 export 차단/실패가 `send()` 의 예외로 관측되지 않는다. 그 결과 윈도우가 `complete` 로 마감돼 축적분이 사라진다.
  ```java
  // ControlNotifyDebouncer.java:293-303  (send)
  if (window.exportRegenerated()) {
      Runnable notifyCallback = notifyService == null ? null
              : () -> notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, true);
      exportRunner.runReExportThenNotify(rawSn, true, notifyCallback);   // @Async — 즉시 반환
      return;
  }
  // ControlNotifyDebouncer.java:254-256  (claimAndSendIsolated)
  send(window);
  store.complete(window.acmlSn());   // ← export/통지 결과와 무관하게 마감
  ```
  **실측(2026-08-02, rawSn=94)**: `08:52:19.030 flush rawSn=94 regen=true frames=448=[META_UPDATED],449=[META_UPDATED]` → `08:52:19.031 export blocked — deident report open` → `08:52:19.032 async export failed cause=CustomException`. 이후 어떤 tick 에서도 이 윈도우가 재클레임되지 않았고(재flush 로그 0건), 신고 해소(08:57:44) 시 나간 통지는 M1 경로의 **영상 단위 `sendCompleted`**(409 자기치유로 updated 전환)라 **`frames=448,449` 변경 목록을 담지 않는다**.
- **재현/확인 경로**:
  ```bash
  # APPROVED 영상에 신고 접수 → 신고 구간에 승인 후 수정 → 60초 뒤 flush
  curl -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"reason":"probe"}'
  curl -X PUT  localhost:18081/api/v1/frames/{srcSn}/description   -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"srcSn":{srcSn},"description":"x"}'
  # 로그: flush(regen=true, frames=...) → export blocked → async export failed
  #      이후 같은 rawSn 의 재flush 없음 = 축적분 소실
  ```
- **영향**: 기능/데이터정합 — 관제가 받는 수정 통지에서 **어느 프레임이 바뀌었는지**가 빠진다(전량 재조회로만 복구 가능). 정합 자체는 M1 재통지로 회복되므로 CRITICAL 은 아니나, 디바운서가 문서화한 "지연될 뿐 소실 없음" 보증이 재생성 경로에서만 깨져 **동작이 문서와 다르다**. 통지 토글이 꺼진 형상에서는 `notifyCallback` 이 애초에 null 이라 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 실패했을 때 윈도우를 되돌리도록, `complete` 를 **콜백 이후**(러너 스레드)로 옮기거나 실패 시 `store` 를 `FLUSHING` 으로 재개방하는 배선 추가, 또는 ⓑ 재생성 윈도우는 러너가 성공했을 때만 `complete` 하도록 `AsyncDatasetExportRunner` 가 결과를 콜백(`onFailure`)으로 돌려주기, 또는 ⓒ 최소 조치로 **차단 시 WARN 에 유실된 `frameChanges` 요약을 남겨 감사 가능하게** 하기. E-ISSUE-81 / D-ISSUE-61 과 함께 "러너의 성공/실패를 상위가 알 수 있게 한다"는 같은 축의 수정이다. **구현은 하지 않는다.**

## 6. 집계

| 판정 | 건수 | 케이스 |
|------|:--:|------|
| PASS | 21 | 001~017, 040~043 |
| PARTIAL | 2 | **018**, **019** (둘 다 D-ISSUE-61 재확인 — 무예외 실패 4경로에서 통지 보류 미작동) |
| FAIL / BLOCKED / N/A / 확인필요 | 0 | — |
| **합계** | **23** | (카탈로그 실측 행 수 `grep -cE '^\| *~*TC-'` = 23, 폐기 케이스 0건) |

### 신규 등록 이슈
| ID | 심각도 | 요지 |
|----|:--:|------|
| E-ISSUE-81 | HIGH | PARTIAL export 는 통지되지만 `V_COMPLETED_VIDEO` 에서 배제 + 회수기 앵커도 아님 → 관제 동기화 사각지대 |
| E-ISSUE-82 | LOW | TC-EXPORT-042 / TC-EXPORT-040 근거 file:line 드리프트(카탈로그 정합) |
| E-ISSUE-83 | MEDIUM | 신고 게이트로 export 보류 시 디바운스 윈도우가 `complete` 돼 변경 프레임 목록 유실(디바운서 자체 "소실 없음" 계약 위반) |

### 기존 이슈 재확인 (신규 등록 없음)
- **D-ISSUE-61 (CRITICAL)** — TC-EXPORT-018 / TC-EXPORT-019. `AsyncDatasetExportRunner.doExport` 가 예외 유무로만 성공을 판정 → 무예외 실패 4경로(NO_INPUT · base 거부 · 버전 소진 · 산출 0건)에서 TASK_COMPLETED/TASK_MODIFIED 가 그대로 발송된다. 상세는 §4 말미.

### self-fill 관점
본 파트 범위(Export)에는 외부 연동 응답으로 채워야 할 값이 없다 — 산출 JSON 의 값은 전부 DB(라벨·프레임·동결 메타)에서 오고, 유일한 외부 의존은 비식별 영상 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`, mock KPST 응답에서 적재된 실측값 `…/94/deid/62e1aea1-…-mask.mp4` = `{stem}-mask{ext}` 규약)뿐이며 문자열 조합·추측이 아님을 확인했다. **self-fill 결함 0건.**

### 검증 중 생성한 데이터 (이후 회차 참고 — 정리하지 않음)
| 대상 | 값 | 성격 |
|------|----|------|
| `ls_data_raw` | rawSn=**94**(vmsClipId `QA0802-E5-EXPORT`) | 이번 검증 전용 신규 영상(파이프라인 정상 완주 → APPROVED) |
| `ls_data_src` | src_sn=**453**(rawSn=94, frm_no=99, 존재하지 않는 이미지 경로) | PARTIAL 유도용 유령 프레임 — 남겨두면 rawSn=94 의 향후 export 는 계속 PARTIAL 이 된다 |
| `ls_dataset_export` | export_sn=**35**(rawSn=26 v8), **39**(rawSn=94 v3), **42**(rawSn=94 v5) | 회수기 앵커용 수동 FAILED 행(물리 폴더 없음) |
| `ls_deident_report` | rprtSn=**23**(rawSn=94) | 신고→해소까지 완결(RESOLVED, `DE_IDENT_YN='Y'` 복원) |
| 파일 | `/app/storage/raw/autolabel-test/94/deid/…-mask.mp4` mtime 갱신(`touch`) | 신고 해소 게이트(외부 솔루션 제자리 교체) 모사 |


### [E-ISSUE-05] TC-AUG-011 — 요청 응답 `jobId` 와 결과 조회 `{jobId}` 가 서로 다른 값 공간이다
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 이름의 식별자는 같은 값 공간이어야 한다. 아니면 클라이언트가 요청 응답으로 결과를 폴링할 수 없고, 잘못 쓰면 **다른 영상의 결과**를 보게 된다.
- **현재 동작(이슈 내용)**: 요청 응답 `jobId` 는 `AugmentRequestService:127 jobIdSeq = new AtomicLong(System.currentTimeMillis())` 의 미영속 placeholder(실측 `1785593419430`)인 반면, `GET /v1/augments/{jobId}/result` 의 경로변수는 **원본 RAW_SN**(`aggregateResultStatus` → `findByOriginalRawSn(jobId)`, `AugmentReviewService.java:121-138`)이다. 실측 `/1785593419430/result` 계열은 `PROCESSING` + `results:[]` 로 조용히 빈 결과를 준다(404 아님).
- **재현/확인 경로**: 요청 응답의 jobId 로 `GET /v1/augments/{그 값}/result` → `{"status":"PROCESSING","results":[],"totalElements":0}`
- **영향**: 기능 오작동은 현재 없음 — FE 가 이미 명시적으로 회피한다(`frontend/src/pages/AugmentRequestPage.tsx:189-192` *"⚠ 응답의 `jobId` 로 이동하지 않는다 (Critical — 다시 되돌리지 말 것)"*). 그러나 계약상 이름이 같아 신규 소비자(관제·타 FE)가 반복해서 밟을 함정이며, 오사용 시 **다른 영상의 결과 조회**로 이어질 수 있다(CWE-639 유사).
- **수정 방향(제안)**: ①요청 응답 필드명을 `requestSeq` 등으로 바꾸거나 ②응답에 `resultJobId`(=rawSn)를 추가해 폴링 대상 식별자를 명시. 코드 변경 없이 가면 최소한 Swagger 설명과 TC-AUG-011 기대결과에 "이 jobId 로는 결과 조회 불가"를 못박을 것.


### [E-ISSUE-06] 문서 — `CLAUDE.md` 의 증강 마이그레이션 번호가 실제와 다르다
- **심각도**: LOW
- **기대 동작(기대효과)**: 정본 문서의 마이그레이션 번호는 실제 파일과 일치해야 한다(되돌리기 절차의 근거로 참조되므로).
- **현재 동작(이슈 내용)**: `CLAUDE.md:242` *"`LS_DATA_AUG.PROMPT_CN`(**V147**)"*, `CLAUDE.md:243` *"`UK_LS_DATA_AUG_ACTVTN`(V143 → **V147 에서 DROP**)"*, *"절차는 V143/**V147** 주석"* — 실제 두 변경은 모두 **`V153__add_ls_data_aug_prompt_and_drop_active_unique.sql`** 에 있고, `V147` 은 `create_ls_data_ingest.sql`(관제 인입 테이블)로 무관하다.
- **재현/확인 경로**: `ls backend/src/main/resources/db/migration/ | grep -E "V147|V153"` + `grep -n "PROMPT_CN\|DROP INDEX" backend/src/main/resources/db/migration/V153*.sql`
- **영향**: 되돌리기 절차 참조 시 엉뚱한 파일을 열게 된다.
- **수정 방향(제안)**: `CLAUDE.md:242-243` 의 `V147` 3곳을 `V153` 으로 정정.


### [E-ISSUE-07] E-1/E-2 전 행 — 근거 `file:line` 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 라인이 실제 코드를 가리켜야 검증 비용이 낮아진다.
- **현재 동작(이슈 내용)**: 확인한 주요 드리프트 — `AugmentRequestService.java` `127-137→167-177`(미검수), `143-153→183-193`(신고), `156-164→196-204`(프레임), `176-181→212-215`·`336-341→402-407`(적재 실패), `285-290→307-312`(단건), `311-317,344-351→371-376,411-417`(멱등키), `381-388→447-454`(RBAC). `AugmentReviewService.java` `336-354→359-374`, `363-365→391-393`, `421-431→468-479`, `389-395→415-421`, `409-419→435-445`, `433-440→522-529`, `255-259→257-270`, `81-89,148-170→83-91,150-172`. `LsDataAug.java:264 applyReviewStatus` → **메서드 자체가 `applyGenerationResult`(:412)로 개명**되고 재결정 가드는 `LsDataAugRvw.java:241` 로 이동.
- **재현/확인 경로**: 각 파일 Read 후 대조.
- **영향**: 검증 효율 저하. 특히 `applyReviewStatus` 는 이름조차 사라져 grep 이 무결과다.
- **수정 방향(제안)**: E-1/E-2 근거열 일괄 갱신.


### [E-ISSUE-08] TC-AUG-040 — `?srcSn=` 분기가 size 한도 검증과 페이징을 모두 우회한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 목록 API 는 어떤 분기로 들어와도 페이징·상한이 적용돼야 한다(`rules/api-design.md` "페이징 없는 목록 전체 조회 금지").
- **현재 동작(이슈 내용)**: `AugmentController.java:94-98` 이 `srcSn != null` 을 **size 검증보다 먼저** 처리하고, `AugmentReviewService.findBySource`(`:101-108`)는 `findBySrcSnOrderByAugTypeCd(srcSn)` 로 해당 srcSn 의 **전 행을 무제한 조회**한 뒤 `PageRequest.of(0, jobs.size())` 로 감싼다. 실측 `?srcSn=1&size=101` → 400 이 아니라 200.
- **재현/확인 경로**: `curl "localhost:18081/api/v1/augments?srcSn=1&size=101" -H "Authorization: Bearer $REV"` → 200
- **영향**: 실질 위험은 낮다 — 반환량이 "한 대표프레임에 걸린 증강 행 수"로 제한된다. 다만 **중복 요청 허용(2026-07-31)으로 같은 (영상×종류) 행이 무한히 누적될 수 있게 된 이상, 이 상한 부재의 성격이 달라졌다**(과거엔 `UK_LS_DATA_AUG_ACTVTN` 이 사실상 상한이었다). CWE-770.
- **수정 방향(제안)**: `srcSn` 분기에도 size 검증을 선행 적용하고 `findBySource` 에 `Pageable` 을 전달. 카탈로그 TC-AUG-040 의 ⚠ 각주에 "중복 허용 이후 누적 상한이 사라졌다"는 배경을 추가.

---

## 5. 작업 흔적 · 원복

| 항목 | 처리 |
|---|---|
| 수동 DB 주입 (`data_aug_sn` 900001~900004, `data_aug_rvw_sn` 900004) | **삭제 완료** — `DELETE FROM ls_data_aug_rvw WHERE data_aug_rvw_sn>=900000` / `DELETE FROM ls_data_aug WHERE data_aug_sn>=900000`, 잔여 0건 확인. ⚠ 삭제 시 `ls_data_aug` 가 5행 반환(주입은 4행) — 900000+ 구간에 다른 병렬 검증 에이전트의 테스트 행이 1건 섞여 있었을 가능성. 900000 미만 실데이터는 무영향 |
| API 로 생성된 데이터 (`ls_data_aug` 14·22·23, 파생 RAW 75·79·82 및 그 프레임/검수행, `ls_data_aug_dscd` 1행) | **보존** — 정상 시나리오 실구동 산출물이며 다른 클러스터(E-3/E-3B/D) 검증이 참조 중일 수 있어 삭제하지 않음 |
| 코드·설정·테스트 파일 | **무수정** (본 결과 파일 1개만 생성) |
| 빌드/테스트 실행 | **미실행** (baseline 인용만) |

### 병렬 실행 주의
검증 중 `data_aug_sn=23`(rawSn 26, WINTER, 08:45:49)이 본 에이전트가 보내지 않은 요청으로 생성됐다 —
다른 병렬 검증 에이전트가 같은 스택에 증강 요청을 보내고 있다. 목록·집계 스냅샷은 그 시점 값이며
재실행 시 숫자가 달라질 수 있다(판정 근거인 **상태 규칙**은 불변).


### [E-ISSUE-21] TC-AUG-055 — "job 은 있으나 aug 행 없음 = 404" 분기가 도달 불가능한 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 위탁 job 원장(`LS_DATA_AUG_JOB`)에 키는 있는데 대상 증강 행이 사라진 상태에서 콜백이 오면 404 로 종결해, NPE/500 대신 명확한 실패를 회신해야 한다.
- **현재 동작(이슈 내용)**: 그 상태 자체가 스키마상 성립하지 않는다.
  ```
  Foreign-key constraints:
    "fk_ldaj_data_aug" FOREIGN KEY (data_aug_sn) REFERENCES ls_data_aug(data_aug_sn) ON DELETE CASCADE
  ```
  aug 행이 지워지면 job 행도 같이 지워지므로 `GenAiCallbackService.java:103-105`
  ```java
  augRepository.findByDataAugSnForUpdate(dataAugSn)
          .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 행을 찾을 수 없습니다: dataAugSn=" + dataAugSn));
  ```
  의 `orElseThrow` 는 실행될 수 없다(다층 방어로서는 정당하나 **케이스로는 검증 불가**).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "\d ls_data_aug_job"` → FK CASCADE 확인. `DELETE FROM ls_data_aug WHERE data_aug_sn=N` 후 `SELECT * FROM ls_data_aug_job WHERE data_aug_sn=N` → 0행.
- **영향**: 기능/보안 영향 없음. **테스트케이스 카탈로그의 검증 불가 항목**이라 다음 회차에도 "확인 못 함" 이 반복된다.
- **수정 방향(제안)**: 코드는 그대로 둔다(fail-closed 다층 방어). **카탈로그**에서 TC-AUG-055 를 ①401(도달 가능)만 남기고 ②404 는 "FK CASCADE 로 도달 불가 — 방어 코드로만 존치" 비고로 강등한다.


### [E-ISSUE-22] TC-AUG-072 — 카탈로그 기대 메시지와 실제 응답 문구 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 기대결과의 문구가 실제 응답과 일치해야 회차 간 대조가 성립한다.
- **현재 동작(이슈 내용)**: 카탈로그 기대 `400("SUCCEEDED 콜백에는 results 가 필요합니다.")` ↔ 실제 응답
  ```json
  {"success":false,"message":"SUCCEEDED 결과에는 results 가 필요합니다.","errorCode":"INVALID_INPUT"}
  ```
  `AugmentJobSuccessApplier.java:69` — 웹훅/결과조회 회수 **공용** 원천으로 분리되면서 "콜백" → "결과" 로 일반화된 것으로 보인다(코드가 의도적이며 정확하다).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' \
    -d '{"request_id":"<발급된키>","job_id":"<보유jobid>","status":"SUCCEEDED"}'
  ```
- **영향**: 기능 영향 없음. 카탈로그 정합성.
- **수정 방향(제안)**: 카탈로그 TC-AUG-072 기대 문구를 `"SUCCEEDED 결과에는 results 가 필요합니다."` 로 정정.


### [E-ISSUE-24] `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 데이터 존재 — 파생 생성 게이트가 대소문자 민감
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 산출물 보유 판정(`LsDataRaw.hasDeidentArtifact()`)은 파생 생성(증강·해상도)의 단일 진실원이므로, 컬럼 값 도메인이 `Y`/`F`/`N` 으로 닫혀 있어야 판정이 결정적이다.
- **현재 동작(이슈 내용)**: DB 에 `de_ident_yn='y'`(소문자) 행이 1건 있다.
  ```
  raw_sn=68 | de_ident_yn = y | data_stts_cd = MARKING_READY
  ```
  판정기는 대소문자 민감이다 (`LsDataRaw.java:444-446`):
  ```java
  public boolean hasDeidentArtifact() {
      return "Y".equals(this.deIdntfYn) || "F".equals(this.deIdntfYn);
  }
  ```
  즉 `'y'` 행은 **비식별 산출물 없음**으로 판정돼 증강 콜백이 오면 `ParentGate.FAIL`(REJECTED + dead-letter)로 끝난다. 방향은 fail-closed(안전)이지만, 운영자는 "비식별 완료된 영상인데 증강이 계속 실패" 로 보인다. 쓰기 경로(`markDeidentified`)는 `Y/F/N` 만 허용하므로 이 값은 **코드 외 경로(수동 SQL·검증 세션 등)** 로 들어온 것이다.
- **재현/확인 경로**:
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE de_ident_yn NOT IN ('Y','F','N');
  ```
- **영향**: 데이터 정합. 보안 노출 없음(안전한 방향으로 실패). 다만 컬럼에 DB 레벨 도메인 제약이 없어 같은 오염이 재발할 수 있다.
- **수정 방향(제안)**: ①오염 행 정정(`UPDATE ... SET de_ident_yn='Y' WHERE de_ident_yn='y'`) ②`LS_DATA_RAW.DE_IDNTF_YN` 에 `CHECK (DE_IDNTF_YN IN ('Y','F','N'))` 제약 추가 검토(Flyway). ⚠ 본 검증에서는 **수정하지 않았다**.


### [E-ISSUE-26] 앱(KST)과 DB DEFAULT(UTC)가 같은 `timestamp without time zone` 컬럼에 **9시간 어긋난 시계**로 기록 — 만료 판정의 잠재 오작동
- **심각도**: LOW
- **기대 동작(기대효과)**: 만료 스윕은 `LocalDateTime.now() - idleTimeoutMinutes` 를 `MDFCN_DT`/`REG_DT` 와 직접 비교한다. 두 값이 같은 시계여야 임계(기본 360분)가 의미를 갖는다.
- **현재 동작(이슈 내용)**: 앱은 `-Duser.timezone=Asia/Seoul`(`backend/Dockerfile:42`) + `hibernate...time_zone: Asia/Seoul`(`application.yml:48`)이라 `LocalDateTime.now()` 가 **KST 벽시계**를 그대로 적재하는 반면, DB 세션은 `Etc/UTC` 라 컬럼 DEFAULT(`ls_data_aug_job.reg_dt/mdfcn_dt`, `ls_data_aug.reg_dt` 등 `DEFAULT CURRENT_TIMESTAMP`)로 채워진 값은 **UTC** 다.
  ```
  DB : show timezone → Etc/UTC ;  current_timestamp → 2026-08-02 00:01:50
  APP: 같은 순간 JPA 가 적재한 mdfcn_dt → 2026-08-02 09:00:20   (Δ = 9h)
  ```
  실측: DB DEFAULT 로 만들어진 job 행(`mdfcn_dt` = UTC now)이 **생성 직후** 스윕의 `MDFCN_DT < now(KST) - 360분` 조건을 만족해 즉시 만료됐다(`augJobSn=770011`). 앱 경로(`LsDataAugJob.createIssued` 161-168 등)는 항상 `LocalDateTime.now()` 를 명시 대입하므로 **현재 운영 흐름에서 관측된 오작동은 없다** — 노출은 DB DEFAULT 에 의존하는 경로(Flyway 백필·운영 SQL·본 검증 같은 수동 INSERT)로 한정된다.
- **재현/확인 경로**:
  ```sql
  -- DB 기본값으로 job 을 만들고 스윕 tick(최대 15분)을 기다린다 → 임계 미도달인데 EXPIRED 로 종결된다
  INSERT INTO ls_data_aug_job(aug_job_sn,data_aug_sn,job_seq,idmp_key,otsd_job_id,job_stts_cd,tot_nocs)
  VALUES (…, 'RECEIVED', 1);   -- reg_dt/mdfcn_dt 는 DEFAULT CURRENT_TIMESTAMP(UTC)
  ```
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "show timezone;"   # Etc/UTC
  docker exec klid-backend env | grep JAVA_OPTS                                     # -Duser.timezone=Asia/Seoul
  ```
- **영향**: 데이터 정합(시각 축). 현재는 잠복이며, 백필 마이그레이션이 이 컬럼들을 DEFAULT 로 채우면 **아직 살아 있는 위탁 job 이 즉시 만료 종결**돼 증강이 실패 확정될 수 있다.
- **수정 방향(제안)**: ①DB 세션/컨테이너 TZ 를 `Asia/Seoul` 로 통일하거나 ②컬럼 DEFAULT 를 제거해 시각 기록 주체를 앱 하나로 못박는다(권장 — 시계 단일 원천). ③장기적으로는 `timestamptz` 전환 검토. ⚠ 본 검증에서는 **아무것도 변경하지 않았다**.

---

## 6. 다른 파트에 넘길 관찰 (본 담당 범위 밖 — 참고용, 이슈 미발행)

1. **E-1 계약 변경 미반영**: `POST /v1/augments/request` 가 `prompt`(5필드 전부 필수)를 **필수**로 요구한다(`AugmentRequestRequest.java:52-55`, 2026-07-31 신설). 카탈로그 TC-AUG-001 의 요청 예시 `{videoIds:[N], types:["WINTER"]}` 는 이제 **400** 이다(실측). E-1 담당이 반영해야 한다.
2. **V143 활성 유니크 인덱스 소멸**: `\d ls_data_aug` 에 `UK_LS_DATA_AUG_ACTVTN` 이 **없다**. Flyway V153 `add ls data aug prompt and drop active unique` 가 드롭했다. TC-AUG-012/013/014(중복 활성 요청 409 + DB 최종 방어)의 전제가 바뀌었으므로 E-1 담당이 재판정해야 한다.
3. **병렬 에이전트 간섭 주의**: 검증 중 `data_aug_sn` 900001~900004 가 다른 에이전트에 의해 생성/삭제되는 것을 관측했다. 본 파트는 이를 피해 **770001~770011 / `src_sn` 770100** 대역만 사용했다.

## 7. 본 검증이 남긴 테스트 데이터 (정리 대상)

수정 금지 규칙에 따라 **삭제하지 않고 기록만** 한다. 전부 INSERT 로 만든 검증 전용 행이다.

| 테이블 | 키 | 비고 |
|---|---|---|
| `ls_data_aug` | 770001, 770003, 770005~770011 | 검증용 aug (REJECTED/PENDING/ACCEPTED 혼재) |
| `ls_data_aug_job` | 770001~770004, 770005~770011 | 검증용 job |
| `ls_data_aug_job_file` | 770002~770011 | |
| `ls_data_src` | 770100 (`raw_sn=50`) | TC-AUG-064 부모 `'N'` 게이트 재현용 |
| `ls_data_raw` | 82(정상 파생·유지 권장), 96·99(async 실패로 FAILED) | 82 는 정상 시나리오 산출물 |
| `ls_data_aug` | 23 (`NEW_RAW_SN=82`) | 정상 시나리오 산출물 |


### [E-ISSUE-62] TC-RESL-054 — `releaseReservedAug` 의 "승자 보호"가 **부모 라벨 0건 영상에서 무효**
- **심각도**: LOW
- **기대 동작(기대효과)**: 중복 finalize 패자의 실패 정리가 승자의 확정 결과(`LS_DATA_AUG` ACCEPTED 행)를 지우면 안 된다. 지워지면 ①증강 이력에서 파생이 사라지고 ②`UK_LS_DATA_AUG_RESL` 슬롯이 풀려 같은 (영상 × 프리셋) 재요청이 **두 번째 파생 RAW** 를 만들어 "요청 1회 = 파생영상 1건" 계약이 깨진다.
- **현재 동작(이슈 내용)**: 승자 보호 판정을 라벨맵 참조 유무 **하나에만** 의존한다.
  ```java
  // ResolutionPersistService.java:205-210
  List<LsDataAugLblMap> refs = lblMapRepository.findAllByDataAugSn(dataAugSn);
  if (!refs.isEmpty()) { log.warn("… skip release …"); return; }
  ```
  그런데 `copyScaledLabels`(386-389)는 **부모 라벨이 0건이면 매핑 행을 하나도 만들지 않는다**. 실측: 부모 26 은 라벨 0건이고 성공 파생 76/77/78 의 `ls_data_aug_lbl_map` 도 0행(`select data_aug_sn,count(*) … group by` 결과에 15/16/17 부재). 즉 라벨 없는 영상의 승자 aug 는 이 방어를 전혀 받지 못한다.
- **재현/확인 경로**: 라벨 0건 부모로 파생 확정 후 `select * from ls_data_aug_lbl_map where data_aug_sn = {승자 augSn}` → 0행. 이 상태에서 `handleFailure` 의 `isAlreadyFinalized` 가 false 로 떨어지는 잔여 창(`ResolutionPersistService` javadoc 168-175 가 명시한 "승자-뒤짐" / `lock_timeout` 페일오픈)에 진입하면 승자 aug 가 삭제된다.
- **영향**: 데이터 정합/이력 유실. 현 트리거가 단일 러너(AFTER_COMMIT 1회 등록)라 실제 재현 난도는 매우 높음 → LOW. 다만 `LS_DATA_AUG` 는 증강 이력·집계의 진실원이라 유실 시 조용하다.
- **수정 방향(제안)**: 참조 검사 축을 라벨맵에서 **`LS_DATA_AUG.NEW_RAW_SN` / `AUG_PROC_STTS_CD`** 로 옮긴다 — `augProcSttsCd != PENDING`(=이미 ACCEPTED 로 확정 전이됨)이면 삭제하지 않는 조건을 추가하면 라벨 유무와 무관하게 승자가 보호된다(V155 `new_raw_sn` 이 이미 승자 파생을 가리키므로 그것과 `newRawSn` 일치 여부도 함께 쓸 수 있다).


### [E-ISSUE-63] TC-RESL-049 — Phase C stale 게이트의 `IOException` 폴백 주석이 **이미 폐기된 신고 게이트**를 근거로 든다
- **심각도**: LOW
- **기대 동작(기대효과)**: 보수적 통과(페일오픈)를 선택한 근거는 실재하는 다른 방어를 가리켜야 한다. 없는 방어를 근거로 적어두면 다음 수정자가 "다른 게이트가 막아준다"고 오판해 실제 폐쇄를 미룬다.
- **현재 동작(이슈 내용)**:
  ```java
  // ResolutionPersistService.java:355-359
  } catch (IOException e) {
      // stat 실패는 결정적 게이트(①②)가 이미 통과했으므로 보수적으로 통과(로그만). PII 는 ②신고 게이트로 닫힘.
      log.warn("[Video][ResolutionDerivative][C] deident mtime check skipped …");
  }
  ```
  같은 메서드의 javadoc(312-314)은 **"구 조건 'capturedAt 이후 신고 이력 존재'는 제거됐다"** 고 명시한다. 즉 "②신고 게이트"는 더 이상 존재하지 않는다. 남은 게이트는 ①경로 불일치와 ②mtime 뿐이고, 이 catch 는 바로 그 ②를 스킵하는 자리다.
- **재현/확인 경로**: `ResolutionPersistService.java:306-360` 정독 — 주석 내 게이트 번호(①②)가 javadoc 의 ①② 와 의미가 어긋난다.
- **영향**: 문서 정합/유지보수 리스크(기능 영향 없음).
- **수정 방향(제안)**: 주석을 사실대로 정정 — "stat 실패 시에는 경로 게이트(①)만으로 판정하며 제자리 교체는 검출하지 못한다(정직한 한계)". 게이트 번호도 javadoc 과 통일.


### [E-ISSUE-64] TC-RESL-066 — 고아 파생 RAW DELETE 문에 `DE_IDENT_YN <> 'Y'` 조건이 빠져 있어 카탈로그의 "동일 조건 동봉"과 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: "검사~삭제 창 0건 삭제"를 위해 Java 사전검사 4조건(①파생임 ②`'Y'` 아님 ③FAILED ④프레임 0건)이 **그대로** DELETE 술어에 실려야 한다(카탈로그 TC-RESL-066 기대결과 문구).
- **현재 동작(이슈 내용)**: 4조건 중 3개만 SQL 에 있다.
  ```sql
  -- VideoRepository.deleteFailedDerivative (396-401)
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  -- ② DE_IDENT_YN <> 'Y' 부재
  ```
- **재현/확인 경로**: `ResolutionPersistService.java:242-254` 의 Java 검사와 `VideoRepository.java:394-402` SQL 술어 대조.
- **영향**: **실질 기능 갭은 없다** — 삭제는 같은 트랜잭션이 `findByRawSnForUpdate` 로 잡은 행 잠금 하에서 수행되고, 확정 경로(`ResolutionPersistService:138-139`)가 `markDeidentified("Y")` 와 `markCompleted()` 를 **항상 함께** 수행하므로 `DATA_STTS_CD='FAILED'` 술어가 `'Y'` 행을 이미 배제한다. 방어 다중화 관점의 불일치이자 카탈로그 문구 부정확.
- **수정 방향(제안)**: SQL 에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가하거나, 카탈로그 기대결과를 "3조건 동봉 + `'Y'` 는 상태 조건으로 간접 배제"로 정정.


### [E-ISSUE-65] TC-RESL-062 — cleanup 이 파생 비디오 **파일**만 지우고 빈 디렉터리 트리를 남긴다
- **심각도**: LOW
- **기대 동작(기대효과)**: 실패한 파생의 산출물 흔적이 저장소에 누적되지 않아야 한다(경로 키에 파생 RAW_SN 이 들어가므로 재시도마다 새 디렉터리가 생긴다).
- **현재 동작(이슈 내용)**: `cleanup` 은 프레임은 디렉터리째 재귀 삭제하지만(`deleteRecursivelyQuietly(framesDir)`, 108-112) 비디오는 파일 단위 삭제만 한다.
  ```java
  // ResolutionFileMaterializer.java:141-152
  if (videoDst != null) { deleteFileQuietly(videoDst); if (Files.exists(videoDst)) clean = false; }
  ```
  실측(rawSn 97 실패 후):
  ```
  $ ls -la /app/storage/deidentified/videos/resolution/905/
  drwxr-xr-x 2 app app 4096 … 97      ← 파일은 사라졌지만 디렉터리 잔존
  ```
- **재현/확인 경로**: 위 §0 의 905 시나리오 후 `ls /app/storage/deidentified/videos/resolution/905/`.
- **영향**: 저장소 위생(파일시스템 inode 누적). 기능·보안 영향 없음.
- **수정 방향(제안)**: `deleteFileQuietly(videoDst)` 이후 부모 디렉터리(`videoDst.getParent()`)가 비어 있으면 `Files.deleteIfExists` 로 함께 제거(루트 `videos/resolution` 자기자신 보호 조건 포함). 잔존 판정(`clean`)에는 반영하지 않는다.

---

## 3. 자동테스트 커버리지 대조

| 테스트 파일 | E-5 커버 케이스 |
|------|------|
| `video/ResolutionReservationPersisterTest.java` | 030 · 031 · 032 · 033 |
| `video/ResolutionSnapshotServiceTest.java` | 035 · 036 · 037 · 038 · 039 · 040 (+ deid base 격리) |
| `video/ResolutionPersistServiceTest.java` | 044 · 048 · 049 · 050 · 051 · 052 · 053 · 054 · 055 · 056 · 066 |
| `video/ResolutionFileMaterializerTest.java` | 041 · 042 · 043 · 062 · 067 |
| `video/AsyncResolutionRunnerTest.java` | 057 · 058 · 059 · 060 · 061 · 063 · 067 |
| `video/ResolutionLetterboxTest.java` | 045 |
| `video/ResolutionReservationPathIT.java` | 069 |
| `video/ResolutionDerivativeFlowIntegrationTest.java` | 030 · 033 · 035 · 044 · 045 · 048 · 050 · 051 · 052 · 053 · 069 · 070 · 071 |
| `meta/DerivedMetaCopierTest.java` · `DerivedMetaCopierIT.java` | 070 · 071 |

- baseline(`_raw/test-baseline.md`) 기준 위 전부 **통과**(backend 실패 0건).
- **커버리지 갭(전용 테스트 없음)**: **TC-RESL-034**(예약 출력경로 CWE-22) · **TC-RESL-064**(`RESL_` 접두 강제) · **TC-RESL-065**(이중전이 409) · **TC-RESL-068**(레거시 raw base 디렉터리 정리). 네 건 모두 정적 대조로는 구현이 확인되나 회귀 가드가 없다.

---

## 4. 근거(file:line) 드리프트

| TC | 카탈로그 근거 | 실제 위치 | 비고 |
|----|------|------|------|
| TC-RESL-030 | `ResolutionReservationPersister.java:68-123` | `68-130` | 메서드 끝 확장(경미) |
| TC-RESL-034 | `ResolutionReservationPersister.java:157-163` | **`164-170`** | `resolveSafeDir` 위치 이동 |
| TC-RESL-064 | `LsDataAug.java:214,228` | **`306`(createResolutionPending) · `320-323`(접두 가드)** | 214 는 무관한 빌더 생성자 |
| TC-RESL-065 | `LsDataAug.java:248-249` | **`340-348`(가드 341-343 · 344-347)** | |

그 외 37건은 근거 라인이 실제 코드와 일치했다.

---

## 5. ★ 확증편향 반증 결과 (핵심 위험 4축)

1. **3곳 부모 게이트가 `'N'`만 막고 `'F'`는 통과하는가** → **개별 실증 완료.**
   - 예약(`ResolutionReservationPersister:83-88`) — 부모 900(`'F'`) 요청이 **201** 로 예약됨.
   - Phase A(`ResolutionSnapshotService:116-122`) — `[A] snapshot ready rawSn=88 parentRawSn=900` 로그.
   - Phase C(`ResolutionPersistService:97-103`) — `[C] persisted rawSn=88 orgnlRawSn=900` 로그.
   - `'N'`(부모 901) 은 **409**. 세 게이트 모두 `LsDataRaw.hasDeidentArtifact()`(`'Y'|'F'`) 단일 헬퍼를 호출(grep 전수: 예약/A/C + `AugmentResultService:390` + `ParentDeidArtifactGuard:70` + `DeidentReportService:330`) → 축 드리프트 없음.
2. **Phase A 산출물 실재 검증이 fail-closed 인가** → procLog 부재 `NOT_FOUND`(149-153) · deid 프레임 경로 blank/null `CONFLICT`(240-247) 확인. **후자는 실동작으로도 실증**(부모 902 → 확정 실패, 원본 프레임 폴백 흔적 0). 두 경로 어디에도 `frames/raw/**` 폴백 분기가 없다(`resolveSafeDeidSource` 가 `isDeidentifiedArtifact` 로 거부).
3. **Phase C stale 창 게이트의 판정축이 신고가 아니라 복사 원자성인가** → **맞다.** `assertDeidentNotReplacedSince`(322-360) 에 신고(`DE_IDNTF_YN`) 참조가 **없고**, 조건은 ①최신 SUCCESS procLog 경로 불일치 ②파일 mtime > capturedAt 둘뿐. ②는 미래 mtime 주입으로 **실동작 실증**(rawSn 97 abort + cleanup).
4. **예약 동시성(같은 프리셋 중복 예약 방지)** → DB 부분 유니크 인덱스가 **상태 무관** 술어(`WHERE aug_type_cd ~~ 'RESL\_%'`)로 실재하고, 응용은 `save`+`flush` 로 **새 RAW/파일 생성 이전에** 위반을 감지해 `CONFLICT` 로 변환(101-109). 실동작으로 PG 23505 → 409 변환 확인. 동시(2스레드) 경합은 IT("같은영상_같은프리셋_동시요청시_1건성공_1건CONFLICT")가 커버.

**추가 반증에서 나온 것**: 위 4축은 전부 방어가 성립했고, 실제 결함은 **방어가 아예 없는 축**에서 나왔다 — 목적지 파일명 유일성(E-ISSUE-61). Phase A 가 `videoFrameNo` 중복은 fail-fast 하면서 목적지 경로 중복은 검사하지 않는 비대칭이 그 지점이다.


### [E-ISSUE-82] TC-EXPORT-042 / TC-EXPORT-040 — 카탈로그 근거(file:line) 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 그 단언을 실제로 보장하는 코드 위치를 가리켜야 한다(다음 회차 재검증·수정 작업의 진입점).
- **현재 동작(이슈 내용)**:
  1. **TC-EXPORT-042** 근거가 `DatasetExportBridge.java:29-30 (자체 토글만 참조)` 인데, 이 라인은 `authoring.dataset-export.enabled` 토글(= TC-EXPORT-013 의 근거)이며 **`control-notify` 토글과의 무관성을 보장하는 코드가 아니다**. 실제 보장 지점은 ①`controlnotify/listener/TaskModifiedAccumulateListener.java`(조건부 어노테이션 없음 — "항상 활성") ②`ControlNotifyDebouncer.java:71-79`(클래스 javadoc "HIGH-E — export 재생성 트리거는 통지 토글과 분리한다") + `:293-307`(`send()` 가 `notifyService==null` 여부와 무관하게 `runReExportThenNotify` 호출) ③반대로 `ControlNotifyEventListener.java:27` 만 `@ConditionalOnProperty(authoring.control-notify.enabled)`.
  2. **TC-EXPORT-040** 근거 `DatasetExportTxService.java:257-278` 중 `claimForRetry` 는 실제 `:257-261` 이고 `:263-291` 은 무관한 `sweepStalePending` 이다.
- **재현/확인 경로**: `docs/test-cases/E-augment-resolution-export-meta.md` 의 해당 행과 위 파일들을 대조.
- **영향**: 기능 영향 없음. 카탈로그 자체의 정합성 결함 — 근거를 따라가면 다른 토글을 검사하게 되어 **잘못된 PASS/FAIL 판정**을 유발할 수 있다.
- **수정 방향(제안)**: 카탈로그 근거를 위 실제 위치로 교체. **구현은 하지 않는다.**

### D-ISSUE-61 재확인 (신규 등록 없음) — TC-EXPORT-018 / TC-EXPORT-019
- 본 파트가 담당하는 `DatasetExportService`/`AsyncDatasetExportRunner` 에서 **동일 결함을 정적으로 재확인**했다. `AsyncDatasetExportRunner.doExport`(`:121-130`)가 `try { export(...); return true; } catch { return false; }` 로 **예외 유무로만** 성공을 판정하는데, `DatasetExportService.export` 에는 예외 없이 실패로 종결하는 경로가 4종이다:
  | 경로 | 라인 | 종결 |
  |------|------|------|
  | 입력 부재 | `:143-147` | `OUTCOME_NO_INPUT` + `return` |
  | 산출 base 거부 | `:162-169` | `markBaseRejected`(FAILED 행) + `OUTCOME_FAILED` + `return` |
  | 버전 채번 소진 | `:174-178` | `OUTCOME_VERSION_EXHAUSTED` + `return` |
  | 산출 0건 | `:205-210` | `markFailed` + `OUTCOME_FAILED`(throw 없음) |
  | (추가) 쓰기 중 예외 | `:234-241` | `markFailed` 후 **정상 반환** |
- 결과: `runApprovalAsync:73-75` 가 `DatasetExportCompletedEvent` 를 발행하고 `runReExportThenNotify:97-105` 가 `afterExport`(TASK_MODIFIED) 를 실행한다 → *"export 가 실패하면 통지를 보류"* 구속 정책 위반.
- 반면 **신고 게이트 차단(`:136-141`)과 쓰기 중 신고(`:247-254`)는 `CustomException` 을 던지므로 통지가 실제로 보류**된다(08:52:19 실측). 즉 규약은 **예외 경로에서만** 성립한다.
- 수정 방향은 D-ISSUE-61 에 기록된 것과 동일(권장: `export()` 가 종결 outcome 을 반환하고 러너가 `COMPLETED`/`PARTIAL` 등 성공 집합으로만 통지). 본 파트에서는 **중복 이슈를 등록하지 않는다.**



## 버그수정 사이클 중 신규 발견 (2026-08-02, Phase 3 QA)

### [FOLLOWUP-01] 배치 오토라벨 write-path가 mock 감지 시에도 차단 없이 그대로 적재
- **심각도**: HIGH
- **기대 동작(기대효과)**: ai-server가 mock/미검출 응답을 반환하면 온라인 경로(Phase 3에서 수정)처럼 배치 경로도 오염된 좌표를 학습데이터에 영구 적재하지 않아야 함
- **현재 동작(이슈 내용)**: `YoloAutolabelStep`은 `untrusted()`를 WARN 로깅만 하고 그대로 persist(파이프라인 차단은 별도 정책이라는 기존 주석 있음). `Sam2SegmentStep`은 mock 메타 자체를 확인하지 않음(관측조차 안 됨). 배치는 온라인보다 영향이 큼 — `LS_DATA_LBL`에 AUTO 폴리곤이 영구 적재됨
- **재현/확인 경로**: ai-server가 mock 응답 반환하는 상태에서 배치 오토라벨 스텝 실행 → mock 좌표가 흔적 없이(Sam2SegmentStep) 또는 WARN만 남기고(YoloAutolabelStep) LS_DATA_LBL에 적재
- **영향**: 데이터 정합 — 학습데이터 오염, CWE-345
- **수정 방향(제안)**: "배치에서 mock 감지 시 단계 실패/스킵/적재중단" 중 정책 결정 필요(온라인처럼 즉시 차단할지, 격리 후 검수자 확인을 거칠지). 결정 전 최소 조치로 Sam2SegmentStep에도 YOLO와 동일한 관측 가능성(WARN + LogSanitizer)을 우선 추가 권장(정책 결정 불필요)
