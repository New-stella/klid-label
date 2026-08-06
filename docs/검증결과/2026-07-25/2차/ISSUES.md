# 2차 검증 이슈 대장 — 2026-07-31

> 8 클러스터 1,888 케이스 전수 검증에서 도출. 클러스터별 · 심각도순.
> 각 블록은 다음 회차 재검증 시 1:1 대조 입력이다 — 축약하지 말 것.

## A 클러스터 — 14건

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


## B 클러스터 — 29건

### [B-ISSUE-41] TC-VLM-034 / TC-VLM-035 — 보류된 VLM 위탁의 **재개가 영구 no-op**: 멱등 조건이 시계열 메타가 아니라 `LS_DATA_META` **전건**을 세어 ingest 시점 `video.*` 기술메타에 항상 걸린다

- **심각도**: **CRITICAL** (데이터 영구 결손 + 유일 복구 경로 소실)
- **기대 동작(기대효과)**: `CLAUDE.md` 구속 정책 — "보류는 스스로 재개되지 않으므로 해소 시 `DeidentGateReopenedEvent` → `VlmResumeBridge` → `VlmWithheldResumeRunner` 가 이 SKIPPED 기록을 근거로 **재위탁**한다(멱등 조건: **시계열 메타 0건**). 이 배선이 없으면 시계열 메타가 영구 결손된다."
- **현재 동작(이슈 내용)**: 멱등 조건이 "시계열 메타 0건"이 아니라 **"그 영상의 `LS_DATA_META` 행이 0건"** 으로 구현돼 있다.

  `backend/src/main/java/kr/co/cudo/authoring/batch/runner/VlmWithheldResumeRunner.java:88-101`
  ```java
  private boolean isWithheld(Long rawSn) {
      boolean withheldLogged = batchStatusService.isStageSkippedWithAnyReason(
              rawSn, BatchStage.VLM, VlmTimeseriesStep.RESUMABLE_SKIP_REASONS);
      if (!withheldLogged) return false;
      long metaCount = metaRepository.countByRawSn(rawSn);   // ← 전건 카운트
      if (metaCount > 0) {
          log.info("[VlmResume] resume skipped — timeseries meta already present rawSn={} count={}", rawSn, metaCount);
          return false;
      }
      return true;
  }
  ```
  `LsDataMetaRepository.java:29` 의 `long countByRawSn(Long rawSn)` 에는 `META_KEY` 필터가 없다. 그런데 같은 `LS_DATA_META` 테이블에는 **적재(ingest) 시점에 `VideoMetaService`(`:62,72-77` — `video.fps/codec/bit_rate/duration_ms/filesize/resolution`)가 ffprobe 실측 6건을 AFTER_COMMIT @Async 로 먼저 써 넣는다.** 즉 **정상 적재된 모든 영상은 VLM 이 한 번도 돌기 전에 이미 `countByRawSn >= 1`** 이다 → `isWithheld` 는 언제나 false → **재위탁이 실행되지 않는다.**

  **실동작 A/B 확증 (rawSn=144, 내가 생성한 전용 영상)**

  | | 조건 | resolve 후 로그 | mock `videovlm/describe` 누계 | 시계열 메타 |
  |---|---|---|---|---|
  | **A** (실환경 그대로) | `LS_DATA_META` = `video.*` 6건 | `[VlmResume] resume skipped — timeseries meta already present rawSn=144 **count=6**` | 13 → **13 (불변)** | **0건 (영구 결손)** |
  | **B** (메타 0으로 비운 대조군) | `LS_DATA_META` = 0건 | `[VlmResume] deident report resolved — resuming withheld VLM submit rawSn=144` → `describe submit … d44875d4…` | 15 → **16 (재위탁 발생)** | **15건 생성** |

  A 와 B 의 유일한 차이는 `video.*` 6건의 존재 여부다. 재개 메커니즘 자체(브릿지·이벤트·러너·게이트 재판정)는 **전부 정상 동작**하며, 막고 있는 것은 이 카운트 범위 하나다.

  **테스트가 잡지 못한 이유(위양성)**: `backend/src/test/java/kr/co/cudo/authoring/batch/runner/VlmWithheldResumeRunnerTest.java`
  ```java
  @DisplayName("신고_보류됐던_VLM_위탁은_해제_후_재위탁된다")
  void resumesWithheldSubmit() {
      stubWithheld(true);
      when(metaRepository.countByRawSn(RAW_SN)).thenReturn(0L);   // ← 프로덕션에서 성립 불가능한 전제
      ...
      verify(vlmTimeseriesStep).run(RAW_SN);
  }
  ```
  Mockito 스텁이 DB 현실(ffprobe 메타 선적재)을 재현하지 않아 GREEN 이다. `LsDataMetaRepository` 를 진짜로 붙인 IT 는 없다.
- **재현/확인 경로**:
  ```bash
  BASE=http://localhost:18081/api; RT=<REVIEWER 토큰>
  # 1) 비식별 완료(MARKING_READY) 영상 하나를 신고 → 'F'
  curl -s -X POST $BASE/v1/videos/144/deident-report -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"reason":"..."}'
  # 2) 배치 트리거 → VLM 보류(SKIPPED) 기록, 외부 호출 0
  curl -s -X POST "$BASE/v1/dev/batch/trigger?rawSn=144" -H "Authorization: Bearer $RT"
  docker logs klid-mock-server | grep -c videovlm/describe      # 증가 없음
  # 3) 해소 → 재개가 skip 되는 것을 확인
  docker exec klid-backend touch /app/storage/raw/seed/144/deid/sample-cctv-1080p-mask.mp4
  curl -s -X POST $BASE/v1/deident-reports/5/resolve -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"note":"..."}'
  docker logs klid-backend --since 30s | grep VlmResume
  #   → [VlmResume] resume skipped — timeseries meta already present rawSn=144 count=6
  ```
  ```sql
  -- 어떤 영상이 이 조건에 걸리는가 = ingest 되어 video.* 메타를 가진 전부
  select raw_sn, count(*) filter (where meta_key like 'video.%') as tech,
         count(*) filter (where meta_key not like 'video.%') as timeseries
  from ls_data_meta group by raw_sn order by raw_sn;
  ```
- **영향**:
  1. **비식별 누락 신고 구간에 배치가 돈 영상은 시계열 메타(`LS_DATA_META`)가 영구 결손**된다. 보류는 실패 행을 남기지 않으므로 `BatchRetryQueue`·실패 회수기 어느 쪽도 집지 않고(설계상 그렇게 만든 것), 재개가 유일한 복구 경로인데 그 경로가 닫혀 있다.
  2. 파급은 신고 경로에 국한되지 않는다 — `RESUMABLE_SKIP_REASONS`(`VlmTimeseriesStep:150-152`)의 **나머지 3종**(비동기 제출 실패 `SKIP_REASON_SUBMIT_FAILED`, ACK 미수신 `SKIP_REASON_ACK_MISSING`, 콜백 창 만료 `SKIP_REASON_CALLBACK_MISSING`)도 **같은 `isWithheld` 를 통과해야 재개**되므로, Phase C-1 논블로킹 전환이 도입한 **미결 회수(`VlmSubmitPendingSweeper`) 전체가 함께 무력화**된다. 즉 "ACK 를 못 받았거나 콜백이 안 온 위탁"도 회수되지 않는다.
  3. 결손된 메타는 `LS_DATA_META_REVIEW` 검수큐에도 진입하지 않아 REVIEWER 가 "메타가 없다"는 사실 자체를 화면에서 알 수 없고, 검수 승인 시 export `event` 블록과 `V_COMPLETED_META` 도 비어 나간다.
  4. 자동 회귀 가드는 위양성이라 이 상태가 조용히 유지된다.
- **수정 방향(제안)**: `countByRawSn` 대신 **시계열 메타만 세는 카운트**를 쓴다 — 예: `LsDataMetaRepository` 에 `countByRawSnAndMetaKeyNotStartingWith(rawSn, VideoMetaService.KEY_PREFIX)` 또는 `countTimeseriesByRawSn`(구간키 패턴 `^\d+-\d+$` / `META_KEY NOT LIKE 'video.%'`) 를 신설하고 `VlmWithheldResumeRunner:94` 를 그 쪽으로 바꾼다. 판정 축의 단일 원천은 `VideoMetaService.KEY_PREFIX`(이미 public 상수)를 재사용해 문자열을 재구현하지 않는다. 회귀 가드는 **Mockito 스텁이 아니라 실제 리포지토리를 붙인 IT**(ingest 메타 6건이 있는 상태에서 재개가 발생하는지)로 세운다. ⚠ **구현하지 않는다.**


### [B-ISSUE-21] TC-BATCH-042 / TC-BATCH-061 / TC-BATCH-090 교차 — 검수 소유 상태 + `stage=MARKING_READY` 조합에서 영상이 **영구 고착**된다 (복구 경로 0)

- **심각도**: **HIGH** (영상 1건이 파이프라인에서 영구 이탈, 운영 복구 수단 없음)
- **기대 동작(기대효과)**: 진입 가드(B-ISSUE-03 수정)는 "검수 결과를 배치가 덮어쓰지 못하게" 하는 것이지, 영상을 되살릴 수 없는 상태로 만드는 것이 아니다. 어떤 상태 조합이든 운영자가 되돌릴 수 있는 경로(재마킹 · 재처리 · 배치 재트리거 중 최소 1개)가 남아 있어야 한다.
- **현재 동작(이슈 내용)**: `LS_DATA_RAW.DATA_STTS_CD='MARKING_READY'` 인데 `LS_RAW_DATA_STATUS.DATA_STTS_CD` 가 검수 소유 상태(PENDING/IN_REVIEW/APPROVED/REJECTED)가 되면, **마킹은 201 로 수락되지만 배치는 영원히 트리거되지 않고, 그 마킹이 활성으로 남아 재마킹까지 409 로 막힌다.** 세 방어가 서로를 잠근다.
  1. 브리지 입구 — `MarkingBatchBridge.java:66-71` `SKIP_STATUSES` 가 `REVIEW_OWNED_STATUSES` 를 포함 → `tryClaimBatchQueued` 실패, `tryCreateBatchQueuedRow` 도 row 존재로 false → `:150-153` 스킵.
  2. 오케스트레이터 본체 — `BatchOrchestrator.java:112-115` 진입 가드가 `SKIPPED` 반환(dev 트리거·Quartz 큐·재시도 잡 전부 동일).
  3. 마킹 재시도 — `MarkingGuards.java:109-114` `requireNoActiveMarking` 이 409. `LsMarking.ACTIVE_STATUSES = [PENDING, VLM_REQUESTED]` 인데, 이 마킹을 `VLM_FAILED`(종결)로 내려 줄 주체는 **VLM 단계뿐이고 그 단계가 돌지 않는다**.
  ```
  # 실측 (rawSn=134)
  stage=MARKING_READY  work=REJECTED  ls_marking(134)={markingSn:10, stts_cd:PENDING}
  POST /v1/videos/134/markings          → 409 CONFLICT "이미 진행 중인 마킹이 있습니다."
  POST /v1/dev/batch/trigger?rawSn=134  → 200 finalStage=SKIPPED (step 0건)
  POST /v1/videos/134/batch/retry       → 409 CONFLICT "배치가 실패(FAILED)한 영상만 재처리할 수 있으며…"
  ```
- **재현/확인 경로** (실행 순서 그대로 재현됨):
  ```bash
  # 1) MARKING_READY 영상(비식별 완료)을 배정하고, 배치 전에 검수 제출한다
  curl -X POST $BASE/v1/assignments -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[134]}'
  curl -X POST $BASE/v1/reviews/134/submit -H "Authorization: Bearer $WT"   # work: ASSIGNED→PENDING
  # 2) 그 뒤 마킹하면 201 이지만 배치는 시작되지 않는다
  curl -X POST $BASE/v1/videos/134/markings -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":600}'
  #    → 201 {"batchTriggered":false,"batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 …"}
  # 3) 반려해도(→REJECTED) 복구되지 않는다 — 위 3개 호출 전부 막힘
  ```
  ```sql
  select r.raw_sn, r.data_stts_cd stage, s.data_stts_cd work,
         (select count(*) from ls_data_src d where d.raw_sn=r.raw_sn) frames
    from ls_data_raw r join ls_raw_data_status s on s.raw_data_id=r.raw_sn
   where r.data_stts_cd='MARKING_READY'
     and s.data_stts_cd in ('PENDING','IN_REVIEW','APPROVED','REJECTED');
  ```
- **영향**:
  - 해당 영상은 **프레임 0건 · 라벨 0건 · 시계열 메타 0건**인 채로 검수 워크플로우 안에 남는다. REVIEWER 가 이를 승인하면 **내용이 없는 영상이 "검수 완료(작업 종결)"** 로 처리되고 `TASK_COMPLETED` 통지·export 대상이 된다(`V_COMPLETED_VIDEO` 는 `LS_DATASET_VIDEO_META`+`LS_RAW_DATA_STATUS='APPROVED'` 기준이라 승인만 되면 노출 경로가 열린다).
  - 반려해도 상태가 `REJECTED`(여전히 차단 집합)라 되돌아오지 않는다 — **단방향 함정**이다.
  - 유발 조건은 "배치 완료 전에 검수 제출"이며, `ReviewService.submit` 에 배치 단계 선행 조건이 없어 **정상 권한만으로 도달 가능**하다(WORKER 1회 호출).
  - 브리지 Javadoc(`MarkingBatchBridge.java:47-64`)은 "배치가 돌았던 영상의 재마킹은 프리컨디션(`MARKING_READY` 요구)에서 막히므로 REJECTED 유지는 무해하다"고 도달성 분석을 남겼으나, **배치가 한 번도 안 돈 채 검수 소유 상태가 된 조합**(같은 Javadoc 이 "도달 가능"이라 인정한 케이스)에 대해서는 회복 경로를 남기지 않았다.
- **수정 방향(제안)**: ① `ReviewService.submit` 에 배치 단계 선행 조건(`LS_DATA_RAW.DATA_STTS_CD='COMPLETED'`)을 두어 애초에 이 조합이 생기지 않게 하거나, ② 마킹 API 가 브리지 skip 을 관측하면(현재 이미 `batchTriggered=false` 로 알고 있다) 방금 만든 마킹을 종결(`VLM_FAILED`) 처리해 409 함정을 남기지 않거나, ③ `stage=MARKING_READY` 인 영상에 한해 수동 재처리(`/batch/retry`)를 허용하고 그 경로에서만 작업 상태를 `ASSIGNED` 로 되돌리는 관리자 복구 API 를 둔다. ⚠ **구현하지 않는다.**


### [B-ISSUE-81] TC-DEID-035 / TC-DEID-052 — 프레임 개인정보 3필드 수정 API 가 신고 게이트·작업락 어디에도 배선되지 않아, 신고가 리셋한 PII 표기를 신고 구간에 즉시 되돌릴 수 있다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 비식별 누락 신고는 "그 판정은 비식별이 잘못된 영상에서 내려진 것이므로 재판정 대상"이라는 이유로 개인정보 3필드(익명/가명/PII 포함여부)를 NULL 로 리셋한다(CLAUDE.md · TC-DEID-035 "stale PII 방지"). 따라서 신고~해소 구간에는 **그 값을 다시 채울 수 없어야** 리셋이 의미를 갖는다. 같은 구간의 라벨 저장이 작업락 409 로 막히는 것과 동일한 취급이어야 한다.
- **현재 동작(이슈 내용)**: `PUT /v1/frames/{srcSn}/privacy-meta` 는 인가만 검사하고 **신고 게이트도 작업락도 보지 않는다.**
  ```java
  // backend/src/main/java/kr/co/cudo/authoring/dataset/service/FramePrivacyMetaService.java:74-75
  public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
      LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만. requireNotUnderDeidentReport / workLock 없음
  ```
  같은 클래스의 `updateBulk`(`:100-122`)도 `verifyRawAccess` 만 수행한다. 비교 대상인 `LabelService.getByFrame:193` · `getHistory:482` 는 `accessGuard.requireNotUnderDeidentReport(...)` 를 인가 직후에 호출한다.
- **재현/확인 경로** (rawSn 133 = 신고 OPEN, srcSn 78. 실제로 수행했고 즉시 NULL 로 원복함):
  ```bash
  # 신고 직후 상태: 3필드 전부 NULL
  docker exec klid-postgres psql -U klid_user -d klid_system -At -F'|' \
    -c "select src_sn,anony_incl_yn,psdo_incl_yn,prvc_incl_yn from ls_data_src where src_sn=78;"   # 78|||

  curl -s -X PUT http://localhost:18081/api/v1/frames/78/privacy-meta \
    -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}'
  # -> 200 {"success":true,"data":{"srcSn":78,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}}
  # DB -> 78|Y|N|N   (신고가 지운 값이 되살아남)
  ```
  대조: 같은 프레임의 `PUT /v1/frames/78/labels` 는 **409** "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."
- **영향**: CWE-359(민감정보 노출) / CWE-863(부정확한 인가). ①신고의 보안 부수효과가 무효화되어 "개인정보 없음(`prvcInclYn='N'`)" 같은 **stale 오표기가 신고 구간에 재삽입**될 수 있고, 그 값은 export JSON·데이터마트로 그대로 나간다. ②`LS_DATA_LBL_HSTRY` 감사(TC-DEID-052)는 **리셋만** 기록하고 이 재설정은 기록하지 않아, "누가 언제 PII 표기를 되돌렸는가"가 추적되지 않는다(OWASP A09). ③작업락이 걸린 영상에 쓰기가 통과하므로 락의 계약도 부분적으로 깨진다.
- **수정 방향(제안)**: `FramePrivacyMetaService.update`/`updateBulk` 진입부(인가 **이후**)에 `accessGuard.requireNotUnderDeidentReport(src.getRawSn())` 를 추가하고, 라벨 저장과 동일하게 작업락 409 가드를 적용한다. 판정은 반드시 `DeidentReportGate` 단일 원천을 재사용할 것(호출처마다 `"F".equals(...)` 를 재구현하지 않는다). ⚠ **구현하지 않는다.**


### [B-ISSUE-101] TC-BATCH-201 — `ls_data_src` 를 참조하는 손자 테이블에 FK 가 0건이라 부모 영상 삭제 시 라벨이 고아로 남는다

- **심각도**: HIGH (데이터 정합 — 조용히 누적되고 집계·마이그레이션·통계를 틀리게 만든다)
- **기대 동작(기대효과)**: TC-BATCH-201 — *"자식 행이 함께 삭제되어 **고아가 구조적으로 불가능**해진다."* V146 의 선언 목적(`V146__add_ls_data_raw_child_fk.sql:5-7`)도 *"영상 원본 행이 사라져도 자식(마킹·프레임·상태·비식별로그·작업배정 등)이 고아로 잔존했다 … 고아는 조용히 집계·뷰·export 를 틀리게 만드는 유형"* 이다.
- **현재 동작(이슈 내용)**: V146 은 **`ls_data_raw` 를 직접 참조하는 27개 테이블만** 다룬다. 그런데 라벨 도메인의 실제 부모는 `ls_data_src`(프레임)이고, **`ls_data_src` 를 참조하는 FK 는 DB 전체에 0건**이다.

  ```sql
  -- 0행
  SELECT cl.relname, con.conname FROM pg_constraint con
    JOIN pg_class rf ON rf.oid=con.confrelid JOIN pg_class cl ON cl.oid=con.conrelid
   WHERE con.contype='f' AND rf.relname='ls_data_src';
  ```

  `src_sn`/`data_src_sn` 컬럼을 보유하고도 FK 가 없는 테이블 **14종**: `ls_data_lbl` · `ls_data_lbl_ai_info` · `ls_data_lbl_hstry` · `ls_label_version` · `ls_data_aug` · `ls_data_aug_job_file` · `ls_data_aug_rvw` · `ls_data_issue` · `ls_data_meta_review` · `ls_auth_work_lock` · `ls_batch_proc_log` · `ls_data_src_hstry` · `ls_data_src`(자기참조).

  따라서 `DELETE FROM ls_data_raw` → `ls_data_src` 는 CASCADE 로 사라지지만 **그 프레임에 달린 라벨은 그대로 남는다.**
- **재현/확인 경로** (실행 완료 — 트랜잭션 ROLLBACK 으로 데이터 무변경 확인):

  ```sql
  BEGIN;
    SELECT count(*) FROM ls_data_lbl l
      WHERE NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn=l.src_sn);   -- 64
    DELETE FROM ls_data_raw WHERE raw_sn=126;                                    -- DELETE 1
    SELECT count(*) FROM ls_data_src WHERE raw_sn=126;                           -- 0  (CASCADE 정상)
    SELECT count(*) FROM ls_data_lbl l
      WHERE NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn=l.src_sn);   -- 86 (+22 고아)
  ROLLBACK;
  ```

  현행 DB 의 고아 라벨 **64행**은 `PIPE-ISSUE-01`(`backend/src/main/resources/db/seed/dev-seed.sql:52` 의 `DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'`)이 부팅마다 남긴 실물이며, V146 적용 이후에도 **줄지 않았다**(V146 의 고아 정리는 `ls_data_raw` 직접 자식만 스캔한다 — 27 spec 재조사 결과 `TOTAL ORPHANS=0`).
- **영향**:
  - 라벨 통계·품질검사·버전 스냅샷 집계가 삭제된 영상의 라벨을 계속 센다.
  - `LBL_SN` 을 참조하는 12+ 모듈(증강 라벨맵·해상도 파생·포털·export 해시·버전 롤백의 LBL_SN 보존 복원)이 존재하지 않는 프레임을 가리키는 행을 만날 수 있다.
  - 장차 `ls_data_src` 에 FK 를 추가하려 할 때 고아가 누적된 만큼 마이그레이션이 중단(V146 과 같은 fail-closed)되어 배포가 막힌다 — 지금 64행이 매 부팅 증가한다.
  - CWE 해당 없음(무결성/데이터 품질). 보안 영향은 없다.
- **수정 방향(제안)**: ① V146 과 같은 3패스 구조로 `ls_data_src` 자식 FK(`ON DELETE CASCADE`) 마이그레이션을 별건 신설하되, 뷰 공급 테이블(`ls_data_lbl_hstry` → `V_COMPLETED_LABEL_CHANGE`, `ls_data_meta_review` → `V_COMPLETED_META`)은 V146 과 동일하게 **고아 발견 시 중단**. ② 선행 조건으로 `PIPE-ISSUE-01`(부팅 시드 전삭제)을 먼저 없애야 고아가 다시 쌓이지 않는다. ⚠ **구현하지 않는다.**


### [B-ISSUE-01] TC-BATCH-008 — UK 위반 catch-skip 이 실제로는 예외를 삼키지 못한다(REQUIRES_NEW 커밋에서 `UnexpectedRollbackException` 전파) + 비-중복 제약위반을 "duplicate ingest race" 로 오분류

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `TrainingVideoIngestTx.ingestOne` 의 이중 멱등 2차 방어는 "동시 race 로 UK(VMS_CLIP_ID)가 충돌하면 **정상 skip**(false 반환)으로 흡수하고 예외를 호출자로 전파하지 않는다"이다(케이스 기대결과 = `catch-skip false, 예외 미전파`, 코드 주석 `:126` "동시 race 의 중복 적재 — 정상 skip 처리"). 스캔 로그도 그 상황을 `DEBUG` 수준의 정상 흐름으로 남기도록 설계돼 있다.
- **현재 동작(이슈 내용)**:
  - `TrainingVideoIngestTx.java:82` 의 `@Transactional(propagation = REQUIRES_NEW)` 안에서 `:120` `videoRepository.save(raw)`(IDENTITY 채번 → 즉시 INSERT)가 제약을 위반하면 PostgreSQL 이 **트랜잭션 전체를 abort** 하고 Hibernate 가 세션을 rollback-only 로 마킹한다. `:125-129` 의 catch 는 실행되지만, 이후 REQUIRES_NEW 경계를 **커밋할 때** Spring 이 `UnexpectedRollbackException` 을 던진다.
    ```java
    } catch (DataIntegrityViolationException e) {          // :125
        log.debug("[TrainingIngest] duplicate ingest race — skip clipId={}", vmsClipId);
        return false;                                       // :128 ← 여기까지는 도달하지만
    }                                                       //        커밋에서 예외가 다시 난다
    ```
  - **실동작 증거**(`file_path` 586자 클립 1건 투입 → `raw_file_path_nm varchar(500)` 위반, UK 위반과 동일한 `DataIntegrityViolationException` 계열):
    ```
    03:15:45.899 ERROR o.h.e.jdbc.spi.SqlExceptionHelper - ERROR: value too long for type character varying(500)
    03:15:45.924 DEBUG TrainingVideoIngestTx  - [TrainingIngest] duplicate ingest race — skip clipId=VB3-CLIP-01
    03:15:45.925 ERROR TrainingVideoIngestService - [TrainingIngest] ingest failed evntId=VB3-EVT-01 clipId=VB3-CLIP-01 causeType=UnexpectedRollbackException
    03:15:45.925 INFO  TrainingVideoIngestService - [TrainingIngest] scan finished scanned=1 ingested=0 limit=100 carriedOver=false
    ```
    catch 블록이 실행됐음에도(`DEBUG` 줄) 예외가 호출자까지 올라가 `:92-97` 의 포괄 catch 가 `ERROR` 로 받아냈다.
  - 부수 결함: catch 가 `DataIntegrityViolationException` **전체**를 잡아 "duplicate ingest race" 로 단정한다. 위 사례처럼 **중복이 아닌** 데이터 오류(길이 초과·NOT NULL·CHECK)도 같은 문구로, 그것도 `DEBUG` 로 기록돼 원인이 은폐된다.
  - 테스트가 이를 못 잡는 이유: `TrainingVideoIngestTxTest#treatsUniqueViolationAsDuplicateSkip` 은 Mockito 로 리포지토리를 스텁해 예외만 던지므로 **실 트랜잭션 커밋 단계가 존재하지 않는다**(전형적 거짓 GREEN).
- **재현/확인 경로**:
  ```sql
  INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, lclgv_cd, file_path, vdo_len_sec, crt_dt, job_dmnd_yn, vms_cctv_id)
  VALUES ('VB3-EVT-01','ORIGINAL','VB3-CLIP-01','11110', '/app/storage/raw/seed/'||repeat('x',560)||'.mp4', 30000, now(), 'Y', 'CCTV-T9');
  ```
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REVIEWER_TOKEN"
  docker logs klid-backend --since 60s 2>&1 | grep -E "TrainingIngest|value too long"
  # 정리: DELETE FROM mng_clip_master WHERE evnt_id='VB3-EVT-01';
  ```
- **영향**: 기능 영향은 제한적이다 — 상위 루프(`TrainingVideoIngestService:92-97`)가 예외를 흡수해 스캔은 계속되고 중복 INSERT 도 실제로 막힌다(DB UK). 그러나 ① 2노드 Active-Active 정상 운영 중 발생하는 **정상적인 race 마다 `ERROR` 로그**가 쌓여 알람·로그 분석이 오염되고, ② `DataIntegrityViolationException` 을 전부 "중복"으로 라벨링해 **실제 스키마/데이터 오류가 `DEBUG` 로 묻힌다**(CWE-390 부적절한 예외 처리 / CWE-755). ③ 코드·주석·테스트가 모두 "흡수된다"고 주장해 후속 개발자가 오독한다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.**
  1. 중복 판정을 예외 타입이 아니라 **제약명**(`uk_ls_data_raw_vms_clip`)으로 좁히고, 그 외 `DataIntegrityViolationException` 은 `WARN`+원인 코드와 함께 그대로 전파.
  2. 흡수를 정말 원한다면 `ingestOne` 안이 아니라 **트랜잭션 경계 바깥**(스캔 루프)에서 `UnexpectedRollbackException`/제약 위반을 중복으로 식별해 `DEBUG` skip 처리 — PostgreSQL 은 tx abort 후 같은 tx 를 커밋할 수 없으므로 경계 안에서의 흡수는 원리적으로 불가.
  3. 회귀 테스트는 Mockito 가 아니라 **Testcontainers 실 DB + 실 트랜잭션 커밋**으로 작성(같은 `VMS_CLIP_ID` 2회 적재).


### [B-ISSUE-02] TC-BATCH-021 — 가드-스킵·적재실패 후보가 `NOT EXISTS` 필터에서 빠지지 않아 tick 예산(100건)을 영구 점유한다(신규 클립 기아)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `MngClipMasterRepository.java:38,41-42` javadoc — *"{@link Pageable} — tick 당 처리 상한. 잔여분은 다음 tick 이 이어서 처리한다(의도된 이월)"*, `TrainingVideoIngestService.java:59-61` — *"상한을 넘는 후보는 굶지 않고 다음 tick 에 이어서 처리된다(… 적재된 클립은 다음 조회에서 `NOT EXISTS` 로 빠지므로 **커서가 전진한다**)"*. 즉 매 tick 마다 후보 집합이 줄어 결국 모든 학습용 클립이 적재돼야 한다.
- **현재 동작(이슈 내용)**: 커서 전진의 전제는 "선택된 클립이 `LS_DATA_RAW` 행을 만든다"인데, 아래 세 부류는 **행을 만들지 않으면서 후보 조건은 계속 만족**한다.
  1. `CLIP_ID` blank → `TrainingVideoIngestTx.java:86-89` skip (`FILE_PATH` 는 유효하므로 후보 쿼리는 통과)
  2. `VMS_CCTV_ID` blank → `:92-96` skip
  3. 적재 자체가 실패하는 클립(B-ISSUE-01 의 제약 위반 등)
  이들은 `NOT EXISTS (SELECT 1 FROM LsDataRaw r WHERE r.vmsClipId = c.clipId)` 를 **영원히 만족**해 매 tick 재선택된다. 정렬이 `ORDER BY evntId ASC, clipTypeCd ASC` 로 고정이라, 이런 클립이 100건 누적되면 그보다 뒤 순번의 정상 클립은 **영구히 선택되지 않는다**.
  - **실동작 증거 A(예산 점유)**: 스킵 전용 후보 101건 + 기존 2건 = 103건 투입 →
    ```
    03:12:40 [TrainingIngest] scan finished scanned=100 ingested=0 limit=100 carriedOver=true
    (skip WARN 100줄, 마지막 = VB2-EVT-198)
    ```
    100건 전량이 스킵으로 소모되고 `ingested=0`. 이 상태에서 후속 tick 도 동일 100건을 다시 집는다(어떤 행도 생기지 않았으므로 후보 집합 불변).
  - **실동작 증거 B(비전진 반복)**: 적재 실패 클립 1건에 대해 scan 을 2회 연속 실행 → 두 번 모두 동일하게 `ingest failed evntId=VB3-EVT-01` ERROR 발생, `ls_data_raw` 는 계속 0행.
  - 관제 스키마상 `mng_clip_master.clip_id`·`vms_cctv_id` 는 **둘 다 nullable** 이므로(실측 `\d mng_clip_master`) 이 조건은 운영에서 실제로 발생 가능하다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, lclgv_cd, file_path, vdo_len_sec, crt_dt, job_dmnd_yn, vms_cctv_id)
  SELECT 'VB2-EVT-'||lpad(g::text,3,'0'), 'ORIGINAL', 'VB2-CLIP-'||lpad(g::text,3,'0'), '11110',
         '/app/storage/raw/seed/sample-cctv-1080p.mp4', 30000, now(), 'Y', NULL
  FROM generate_series(101,201) g;
  ```
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REVIEWER_TOKEN"   # → data:0
  docker logs klid-backend --since 40s 2>&1 | grep "scan finished"   # scanned=100 ingested=0 carriedOver=true
  # 재실행해도 동일 100건이 다시 선택됨(후보 집합 불변)
  # 정리: DELETE FROM mng_clip_master WHERE evnt_id LIKE 'VB2-EVT-%';
  ```
- **영향**: 기능/가용성. 관제가 학습용으로 설정한 정상 영상이 **무기한 적재되지 않는다**(사일런트 기아 — 알람 없음, `scan finished ingested=0` 만 반복). 목표 규모가 영상 5,000건(SFR-17)이라 불량 메타 클립이 100건만 누적돼도 파이프라인 입구가 완전히 막힌다. 동시에 매 tick `WARN`/`ERROR` 100줄이 관제 공유 DB 쿼리와 함께 반복된다. 1차 B-ISSUE-04 가 지적한 "매 tick 전량 재조회 후 전량 skip" 이 **상한 안에서 국소적으로 재발**한 형태다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.**
  1. 후보 쿼리에 정적으로 판별 가능한 배제 조건을 추가 — `AND c.clipId IS NOT NULL AND TRIM(c.clipId) <> '' AND c.vmsCctvId IS NOT NULL AND TRIM(c.vmsCctvId) <> ''`(`FILE_PATH` 와 동일 패턴). 그러면 세 부류 중 1·2 가 후보에서 사라져 커서가 전진한다.
  2. 정적 판별이 불가한 3(적재 실패)에는 **실패 기록 테이블 또는 마지막 시도 시각 컬럼**을 두고 백오프 재시도(예: 24h 후 재시도)로 예산 점유를 끊는다.
  3. `scanned`/`ingested`/`skipped` 를 분리 집계해 `skipped == scanned` 가 연속 N tick 지속되면 `WARN` 1줄로 기아를 관측 가능하게 한다.


### [B-ISSUE-03] TC-BATCH-002/009 부수 발견 — 관제 `MNG_CLIP_MASTER.FILE_PATH`(varchar 1000) vs `LS_DATA_RAW.RAW_FILE_PATH_NM`(varchar 500) 폭 축소로 500자 초과 경로 클립은 영구 적재 불가

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 저작도구는 관제 공유 테이블을 READ 해 적재하므로, 관제가 넣을 수 있는 값의 도메인을 수용할 수 있어야 한다. 수용 불가한 값은 **명확한 사유로 skip 되고 관측**돼야 하며, 침묵 실패로 무한 재시도되면 안 된다.
- **현재 동작(이슈 내용)**: 실측 스키마
  ```
  mng_clip_master.file_path        character varying(1000)  (관제 소유)
  ls_data_raw.raw_file_path_nm     character varying(500)   NOT NULL (저작도구 소유)
  ```
  586자 경로 클립 1건으로 scan 실행 시 `ERROR: value too long for type character varying(500)` → B-ISSUE-01 의 오분류 경로를 타 `DEBUG duplicate ingest race` 로 기록되고, `ERROR ... causeType=UnexpectedRollbackException` 으로 마감된다. 적재 행이 안 생기므로 B-ISSUE-02 에 따라 매 tick 무한 재시도된다.
- **재현/확인 경로**: B-ISSUE-01 의 재현 스크립트와 동일. 스키마 대조는
  ```sql
  select table_name, column_name, character_maximum_length from information_schema.columns
   where (table_name='mng_clip_master' and column_name='file_path')
      or (table_name='ls_data_raw' and column_name='raw_file_path_nm');
  ```
- **영향**: 데이터 정합/가용성. NAS 경로가 깊은 운영 환경에서 특정 영상이 원인 불명으로 영구 미적재된다. 로그가 "duplicate" 라고 말하므로 운영자가 원인에 도달하기 어렵다. 표준도메인 관점에서도 소유 테이블 간 동일 의미 컬럼의 길이가 다른 것은 감리 지적 대상이 될 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ① `RAW_FILE_PATH_NM` 을 관제와 동일 폭(1000)으로 확장(표준도메인 확인 후 Flyway 마이그레이션), 또는 ② 적재 전 길이 검증 가드를 `FILE_PATH` blank 가드 옆에 추가해 명시적 `WARN` skip + 후보 배제. ①과 ② 중 어느 쪽이든 B-ISSUE-01(오분류)·B-ISSUE-02(무한 재시도)와 함께 다뤄야 실효가 있다.


### [B-ISSUE-42] TC-VLM-010 — `BLOCK_TIMEOUT=45s` 가 코드에서 사라졌다(논블로킹 전환). 케이스·UNCERTAINTIES #13 의 "이중 구조" 전제가 무효

- **심각도**: MEDIUM (문서·기대값 정합 / 판정 기준 오염)
- **기대 동작(기대효과)**: 케이스와 `UNCERTAINTIES.md #13` 은 "`BLOCK_TIMEOUT=45s` 는 재시도 누적 상한, 단일 호출 실효 타임아웃은 `vlm.client.timeout-seconds`(10s) — **둘 다 초과 시 EXTERNAL_API_ERROR**"를 기대한다.
- **현재 동작(이슈 내용)**: Phase C-1(`862ca6d8`)에서 `.block(45s)` 가 **제거**됐다. `VlmTimeseriesStep` 에 `BLOCK_TIMEOUT` 상수도, `block(` 호출도 없다(grep 0건 — 주석 `:70`, `:348` 에 "구 코드는 `.block(45s)`" 라는 서술로만 잔존).
  ```java
  // VlmTimeseriesStep.java:361-377 — subscribe 만 하고 즉시 반환
  vlmClient.submitTimeseries(req)
          .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
          .subscribe(resp -> …onAccepted(…), err -> …onSubmitFailed(…));
  return VlmTimeseriesResponse.submitted(requestId);   // ACK 도 기다리지 않는다
  ```
  현재 유일한 시간 제약은 `VlmClient.java:71`(`vlm.client.timeout-seconds:10`, `application.yml:633` 실값 10) 이 `:108 .timeout(timeout)` 으로 거는 **10초**이고, 그 위에 Retry(max-attempts 3, wait 1s, ×2 backoff)·CircuitBreaker 가 얹힌다(`application.yml:500-507,547-553`). 파이프라인 스레드는 **어느 값에도 블록되지 않는다.**
  4xx 비재시도는 `:106 onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)` → `NonRetryableExternalException` 이 retry/CB `ignore-exceptions` 에 등록돼 정상 배선(케이스 표엔 없으나 확인함).
- **재현/확인 경로**:
  ```bash
  grep -rn "BLOCK_TIMEOUT\|\.block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java   # 0건(주석 제외)
  grep -n "timeout-seconds" backend/src/main/resources/application.yml                                              # 633: timeout-seconds: 10
  ```
- **영향**: 검증자가 "45s 안에 안 끝나면 EXTERNAL_API_ERROR"를 기준으로 판정하면 **존재하지 않는 동작을 근거로 PASS/FAIL 을 매긴다.** 또 운영 관점에서 "느린 벤더가 배치 스레드를 45초 붙잡는다"는 리스크 서술도 더는 사실이 아니다(대신 새 리스크는 "ACK 를 못 받은 위탁의 회수" 쪽으로 옮겨갔고 그 회수가 B-ISSUE-41 로 막혀 있다).
- **수정 방향(제안)**: TC-VLM-010 의 기대결과를 "**단일 실효 타임아웃 10s**(`vlm.client.timeout-seconds`) + Retry 3회/exp backoff + CircuitBreaker. 초과·실패는 호출자에게 전파되지 않고 `VlmSubmitOutcomeRecorder.onSubmitFailed` → `SKIP_REASON_SUBMIT_FAILED` 감사 행으로 기록"으로 재작성하고, `UNCERTAINTIES.md #13` 의 "이중 구조" 문구를 폐기한다. ⚠ **구현하지 않는다.**


### [B-ISSUE-43] TC-VLM-009 — describe 실패·빈 응답이 **호출자에게 전파되지 않아** 배치는 성공으로 마감된다(논블로킹 전환의 관측 가능성 손실)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스 — "describe 응답 null → `EXTERNAL_API_ERROR`".
- **현재 동작(이슈 내용)**: 가드 자체는 살아 있으나 **예외가 비동기 경로로 흡수**된다.
  ```java
  // VlmTimeseriesStep.java:362-377
  .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, "VLM describe 응답이 비어있습니다 rawSn=" + rawSn)))
  .subscribe(resp -> …,
             err  -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                        () -> outcomeRecorder.onSubmitFailed(rawSn, markingSn, err)));
  …
  return VlmTimeseriesResponse.submitted(requestId);
  ```
  `doSubmit` 은 항상 `submitted` 를 돌려주므로 `BatchOrchestrator` 는 VLM 단계를 **성공으로 간주**하고 파이프라인을 계속 진행해 `COMPLETED` 로 마감한다. 실패 사실은 `VlmSubmitOutcomeRecorder.onSubmitFailed` 가 `LS_BATCH_PROC_LOG` 에 `VLM/SKIPPED/SKIP_REASON_SUBMIT_FAILED` 감사 행으로만 남긴다(상태 강등은 의도적으로 금지 — 주석 "★ 상태 강등 금지").
  설계 의도(지각 실패가 완료된 파이프라인을 FAILED 로 역행시키지 않게)는 타당하나, **회수 경로가 B-ISSUE-41 로 막혀 있어** 현재는 "실패했고, 기록만 남았고, 아무도 다시 시도하지 않는다"가 된다.
- **재현/확인 경로**: mock 을 오류 응답으로 만들 수 없어 이번 회차에 실동작 재현은 못 했다(컨테이너 재기동 금지). 정적으로는 위 스니펫 + `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다` / `#외부_호출_실패는_파이프라인_스레드로_전파되지_않고_완료핸들러에_위임된다` 가 이 동작을 **기대값으로 고정**하고 있다.
- **영향**: (a) 케이스 표의 "EXTERNAL_API_ERROR" 기대값이 무효. (b) 외부 VLM 이 계속 실패해도 배치는 전건 `COMPLETED` 로 보이고 시계열 메타만 조용히 비는 상태가 된다 — 운영 알림·대시보드가 `LS_BATCH_PROC_LOG` 의 `SKIPPED` 행을 별도로 보지 않는 한 무증상이다(CWE-778 계열 관측 실패).
- **수정 방향(제안)**: ①TC-VLM-009 기대값을 "`EXTERNAL_API_ERROR` 가 `onSubmitFailed` 로 흡수되어 `SKIP_REASON_SUBMIT_FAILED` 감사 행이 남는다"로 갱신. ②운영 축에서는 `LS_BATCH_PROC_LOG(VLM, SKIPPED)` 잔존 건수를 메트릭/알림으로 노출(파이프라인 상태를 강등하지 않으면서 가시화). ⚠ **구현하지 않는다.**


### [B-ISSUE-61] TC-BATCH-121 — 배치 오토라벨의 `TRCK_ID` 가 실환경에서 전량 NULL → 트랙 보간 단계가 항상 0건 산출

- **심각도**: MEDIUM (기능 미달 · 테스트 GREEN 뒤에 가려진 런타임 무산출)
- **기대 동작(기대효과)**: `predictYoloTrack` 은 `clipId`(영상 격리)·`frameIndex`(0=리셋)로 트래커 상태를 유지해 **같은 객체에 동일 `track_id`** 를 부여하고, 그 값이 `LS_DATA_LBL.TRCK_ID` 에 적재되어야 한다. 그래야 `TrackInterpolationStep`(`findAutoBboxWithTrackId` → trackId 그룹핑)이 키프레임 사이를 보간하고, SAM2 dedup 축(`(srcSn,label,trackId)`)·트랙 편집/병합 UI 가 성립한다(CVAT 트랙 보간 포팅 · SFR-08-01).
- **현재 동작(이슈 내용)**:
  - DB 실측 — `select count(*) from ls_data_lbl where trck_id is not null` = **1 / 298**(그 1건도 srcSn=63 의 레거시 `0`). 파이프라인이 완주한 rawSn 126·132·133·135·136·137·138·143 의 YOLO 저장분은 **전부 NULL**.
  - 그 결과 보간 단계가 매번 후보 0건으로 끝난다 — backend 로그:
    ```
    [Batch][Interpolation] no interpolation candidates rawSn=126
    [Batch][Interpolation] no interpolation candidates rawSn=132 / 133 / 135 / 136 / 137 / 138 / 143
    ```
    `select count(*) from ls_data_lbl_ai_info where lbl_src_cd='INTERPOLATE'` = **0**(YOLO 108 · SAM2 60).
  - BE 측 배선은 정상이다 — `YoloLabelPersister.java:82-84` 가 `trackId` 를 문자열로 변환해 `LsDataLbl.createAutoBbox(..., trackIdStr)` 로 넘긴다. 즉 **ai-server 가 `track_id: null` 을 돌려준다.**
  - ai-server 로그가 원인 축을 보여준다 — `app/models/bytetrack_util.py`:
    ```
    WARNING:app.models.bytetrack_util:[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5 — 누락분 track_id=None 유지
    ```
    `_apply_bytetrack` 은 `det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None`(`bytetrack_util.py:88-90`) 로 **-1(미확정 트랙)을 None 으로 정규화**한다. 프레임 추출은 마킹 간격 기반(`intervalFrames=300` ≈ 10초)이라 인접 추출 프레임 사이에 IoU 연관이 성립하지 않고, ByteTrack 이 트랙을 "확정" 상태로 올리지 못해 매 프레임이 미확정 → None 이 된다.
  - 단위 테스트는 통과한다 — `YoloAutolabelStepTest:727 YoloStep_predictYoloTrack_을_호출하고_trackId_를_LsDataLbl_에_저장`, `TrackInterpolationStepTest` 19건이 모두 **trackId 가 부여된 픽스처**를 전제로 하기 때문이다. 실파이프라인에서 전제가 성립하지 않는다는 사실은 어떤 테스트도 관측하지 않는다(memory `tests-green-runtime-broken-selfcall-tx` 와 동형 패턴).
- **재현/확인 경로**:
  ```sql
  select count(*) from ls_data_lbl where trck_id is not null;                       -- 1
  select lbl_src_cd, count(*) from ls_data_lbl_ai_info group by 1;                  -- YOLO/SAM2 만, INTERPOLATE 0
  ```
  ```bash
  docker logs klid-backend --since 60m 2>&1 | grep "\[Batch\]\[Interpolation\]"     # 전건 "no interpolation candidates"
  docker logs klid-ai-server --since 30m 2>&1 | grep ByteTrack                      # tracker_id 길이 불일치
  ```
- **영향**: ①트랙 보간(TC-BATCH-132~137)·트랙 편집/병합/분할(`TrackEditService`·`TrackMergeService`)이 실데이터에서 대상 0건으로 무의미 ②SAM2 dedup 이 `(label, trackId=null)` = 라벨 단위로 축소되어 같은 프레임의 서로 다른 동종 객체가 1건으로 합쳐짐(rawSn 126 실측: YOLO car 3건 → SAM2 car 폴리곤 1건) ③학습데이터셋에 객체 연속성 정보가 결손. 보안 영향 없음.
- **수정 방향(제안)**: (a) 프레임 추출 간격과 트래킹 전제를 정합시킨다 — 추적이 목적이면 마킹 구간 내 **연속 프레임**을 별도로 뽑아 트래커에 먹이거나, (b) ai-server 가 미확정 트랙에도 잠정 ID 를 부여(`min_hits=1` 상당)하도록 `ByteTrackTracker` 파라미터를 노출하거나, (c) 현재 샘플링 정책에서는 트랙 축이 성립하지 않음을 인정하고 보간 단계를 `isEnabled` 조건부로 낮춘다. ⚠ **구현하지 않는다.**


### [B-ISSUE-62] TC-BATCH-121 — ai-server ByteTrack 이 필터된 `tracker_id` 를 위치(zip)로 매칭해 `track_id` 오귀속 가능

- **심각도**: MEDIUM (잠재적 데이터 오염 — 현재는 B-ISSUE-61 때문에 표면화되지 않음)
- **기대 동작(기대효과)**: 트래커가 돌려준 `tracker_id` 는 **그 id 가 부여된 검출** 에 붙어야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:84-90`
  ```python
  tracked = tracker.update(sv_dets)
  tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
  if len(tracker_ids) != len(dets):
      logger.warning("[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d — 누락분 track_id=None 유지", ...)
  for det, tid in zip(dets, tracker_ids):  # 짧은 쪽 길이만큼만 매핑
  ```
  `tracker.update()` 는 **연관에 성공한 검출만 남긴 `sv.Detections`** 를 반환할 수 있는데(로그가 실제로 `dets=6 / tracker_ids=5`, `dets=3 / tracker_ids=2`, `dets=8 / tracker_ids=7` 로 매 호출 불일치를 보고한다), 코드는 원본 `dets` 와 **위치 순서로 zip** 한다. 필터링으로 인덱스가 밀리면 **5번째 트랙 id 가 원본 6개 중 앞 5개에 순서대로 붙어** 다른 객체에 귀속된다. 경고는 "누락분은 None 유지"라고만 말하고 **정렬 대응이 보장되지 않는다는 사실은 다루지 않는다**.
- **재현/확인 경로**: `docker logs klid-ai-server 2>&1 | grep "tracker_id 길이 불일치"` — 관측된 전 호출에서 발생. 단정하려면 `tracked.xyxy` 와 `dets[i].points` 를 대조해야 하는데 현재 코드가 그 대조를 하지 않는다.
- **영향**: `track_id` 가 실제로 부여되기 시작하면(B-ISSUE-61 해소 시) 보간이 **서로 다른 객체를 한 트랙으로 잇는** 오보간 산출물을 만들 수 있다(CWE 해당 없음 — 데이터 정확성).
- **수정 방향(제안)**: `tracker.update()` 결과를 위치가 아니라 **좌표(xyxy) 동치 또는 인덱스 맵**으로 원본 검출에 되매핑하고, 되매핑에 실패한 검출만 `None` 으로 남긴다. ⚠ **구현하지 않는다.**


### [B-ISSUE-82] B-11 인접 — 프레임 설명 수정 API 가 작업락·신고 게이트 미배선으로 신고 구간에도 200 으로 기록된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 신고 구간(`DE_IDNTF_YN='F'` + 작업락 LOCKED)에는 그 영상에 대한 작업 산출물 수정이 차단되어야 한다(CLAUDE.md "저장·수정은 기존 작업락으로 409 차단"). 프레임 설명은 승인 후 수정 시 **export 전량 재생성 트리거**로 명시된 산출 대상이다(CLAUDE.md ★export 재생성 정책, `FrameDescriptionService`).
- **현재 동작(이슈 내용)**:
  ```java
  // backend/src/main/java/kr/co/cudo/authoring/label/service/FrameDescriptionService.java:45-46
  public FrameDescriptionResponse update(Long srcSn, String description, TokenClaims actor) {
      LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만
  ```
  신고 게이트·작업락 검사가 없다.
- **재현/확인 경로** (수행 후 `description:null` 로 원복함):
  ```bash
  curl -s -X PUT http://localhost:18081/api/v1/frames/78/description \
    -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"description":"gate probe"}'
  # -> 200 {"success":true,"data":{"srcSn":78,"description":"gate probe"}}
  ```
- **영향**: 잠긴 영상에 대한 작업이 계속되어 락 계약이 깨진다. 승인된 영상이라면 재export 브리지를 깨우는데, export 자체는 신고 게이트로 skip 되므로 **파일은 갱신되지 않은 채 트리거만 소모**된다(관제 통지도 보류). PII 직접 노출은 아니라 MEDIUM.
- **수정 방향(제안)**: `FrameDescriptionService.update` 에 `requireNotUnderDeidentReport` + 작업락 가드 추가. 조회(`get`)까지 막을지는 정책 결정 대상(설명 텍스트는 좌표·픽셀이 아니므로 조회 차단은 불필요하다는 판단도 가능). ⚠ **구현하지 않는다.**


### [B-ISSUE-04] TC-DEID-007 — `AsyncDeidentifyRunner.loadRaw` 자기호출로 `@Transactional(REQUIRES_NEW, readOnly)` 가 적용되지 않는다 (1차 B-ISSUE-06 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 애너테이션이 붙어 있으면 그 경계가 실제로 열려야 한다. 이 프로젝트는 같은 패턴이 실제 결함으로 이어진 전례가 있어(`DeidentifyStep.execute` 가 `ObjectProvider` 자기참조 프록시를 도입한 이유, 프레임 추출 deid 경로 NULL 버그) `BatchStepTransactionBoundaryTest` 라는 정적 가드까지 두고 있다.
- **현재 동작(이슈 내용)**: 선언부에는 경계가 있으나 호출부가 `this` 직접 호출이라 Spring AOP 프록시를 우회한다.
  ```java
  // AsyncDeidentifyRunner.java:79
  LsDataRaw raw = loadRaw(rawSn).orElse(null);                   // ← 자기호출(프록시 우회)
  // AsyncDeidentifyRunner.java:110-112
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  동일 패턴이 `BatchOrchestrator.java:104 → :149` 에도 남아 있다. 현재는 리포지토리 기본 트랜잭션으로 조회가 수행돼 **기능상 정상**이며(실동작: rawSn 126/132/133/139~142 모두 정상 로드, 미존재 rawSn 은 skip), `DeidentifyStep.execute` 는 별도로 프록시 경유가 보장된다.
- **재현/확인 경로**: 정적 — `grep -n "loadRaw" backend/src/main/java/kr/co/cudo/authoring/batch/runner/AsyncDeidentifyRunner.java backend/src/main/java/kr/co/cudo/authoring/batch/orchestrator/BatchOrchestrator.java`
- **영향**: 현재 무해(readOnly 단건 조회). 다만 ① 반환 엔티티가 detached 이며 선언된 격리 보장이 실재하지 않고, ② 이 메서드에 잠금·격리수준 의존 로직이 추가되는 순간 즉시 결함이 된다. ③ `BatchStep` 에는 정적 가드가 있으나 **러너/오케스트레이터에는 동등한 가드가 없어** 같은 패턴이 재유입될 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `DeidentifyStep` 이 쓰는 `ObjectProvider<Self>` 프록시 경유 패턴을 적용하거나 조회를 별도 빈(`BatchRawLoader`)으로 분리. 최소 조치로는 애너테이션 제거 + "리포지토리 기본 트랜잭션 사용" 주석 명시. 아울러 `BatchStepTransactionBoundaryTest` 와 같은 정적 스캔을 러너/오케스트레이터의 `protected @Transactional` 자기호출로 확장하는 것을 검토.


### [B-ISSUE-05] TC-DEID-019 — mock 비식별 복사 `IOException` 분기('F' 마킹)의 회귀 테스트가 여전히 없다 (1차 B-ISSUE-05 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: `runMock` 의 실패 분기 3종(원본 부재 / base 거부 / **복사 IOException**)은 모두 "성공 위장 금지 — 별도 커밋으로 `'F'` 기록" 이라는 동일 안전 계약을 가지므로 동등한 회귀 보호가 있어야 한다. 실제로 원본 부재(1건 이상)와 base 거부(S1/S2)는 테스트로 고정돼 있다.
- **현재 동작(이슈 내용)**: 코드는 정확하다.
  ```java
  // DeidentifyStep.java:330-335
  } catch (IOException e) {
      batchTransitionService.recordDeidentFailure(raw.getRawSn(), MOCK_ERROR_CODE, e.getClass().getSimpleName());
      log.error("[Batch][Deid][mock] copy failed rawSn={} errType={}", raw.getRawSn(), e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.INTERNAL_ERROR, "mock 비식별 복사 실패", e);
  }
  ```
  그러나 `DeidentifyStepTest` / `DeidentifyStepExecutePersistenceIntegrationTest` / `DeidentifyStepFailurePersistenceIntegrationTest` / `DeidentifyStepKpstDisabledIntegrationTest` 어디에도 `IOException` 을 주입하는 케이스가 없다(두 IT 의 `import java.io.IOException` 은 픽스처 셋업용). `copyAtomically`/`moveAtomically` 리팩토링 시 이 분기가 조용히 깨져도 감지되지 않는다.
- **재현/확인 경로**:
  ```bash
  grep -rn "IOException" backend/src/test/java/kr/co/cudo/authoring/batch/step/DeidentifyStep*.java
  grep -rn "복사 실패\|copy failed" backend/src/test/java/kr/co/cudo/authoring/batch/step/   # → 0건
  ```
- **영향**: 회귀 위험(테스트 커버리지 갭)이며 현재 기능 결함은 아니다. 다만 이 분기가 깨지면 "복사 실패인데 예외 없이 진행" 또는 "'F' 미기록" 으로 이어져 **비식별 성공 위장**이라는 HIGH 급 결과를 낳는 위치다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** target 부모 경로를 읽기 전용 디렉터리로 만들거나 target 이름과 동일한 디렉터리를 미리 생성해 `Files.copy`/`Files.move` 가 `IOException` 을 던지도록 유도하는 통합 테스트 1건 추가(원본 부재 테스트의 픽스처 재사용). 단언은 `DE_IDNTF_YN='F'` 영속 + `MARKING_READY` 미전이 + `INTERNAL_ERROR`.

---


### [B-ISSUE-22] TC-BATCH-035 — `BatchOrchestrator.process(Long, Map)` stage 토글 오버로드에 **프로덕션 호출자가 0건**

- **심각도**: LOW (죽은 API 표면 · 검증 불가)
- **기대 동작(기대효과)**: 케이스가 `integration` 계층으로 분류돼 있으므로 dev 경로에서 실제로 토글을 주입해 단계 skip 을 관측할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `orchestrator.process(` 호출자 전수(5곳) — `BatchDevTriggerController.java:119` · `BatchRetryQuartzJob.java:55` · `AsyncBatchRunner.java:25` · `BatchReprocessService.java:87` · `BatchQuartzJob.java:51` — 이 **전부 1인자 오버로드**를 쓰고, 그 오버로드는 `BatchOrchestrator.java:87` 에서 `process(rawSn, null)` 로 위임한다. 즉 `stageToggles` 가 non-null 이 되는 런타임 경로가 없다. 소비자는 테스트 5곳뿐이다.
  ```java
  // BatchOrchestrator.java:86-88
  public BatchStage process(Long rawSn) {
      return process(rawSn, null);
  }
  ```
- **재현/확인 경로**: `grep -rn "orchestrator.process" backend/src/main` → 5건 전부 1인자. `grep -rn "process(.*,.*)" backend/src/main .../BatchOrchestrator.java` → 자기 위임 1건뿐.
- **영향**: 기능 결함은 아니다(`isEnabled` 기본값 `true` 라 프로덕션 동작은 100% 보존). 다만 ①`BatchStep.isEnabled` 오버라이드 3종(`YoloAutolabelStep:135`·`Sam2SegmentStep:120`·`FfmpegFrameExtractor:107`)과 `BatchContext.stageToggles` 전체가 테스트 전용 코드로 남아 있고, ②TC-BATCH-035 를 명세대로 실동작 검증할 수단이 없다.
- **수정 방향(제안)**: dev 트리거(`/v1/dev/batch/trigger`)에 선택적 `stages` 파라미터를 붙여 실제로 사용하거나, 사용 계획이 없으면 오버로드·토글·`isEnabled` 오버라이드를 제거한다. ⚠ **구현하지 않는다.**


### [B-ISSUE-23] TC-BATCH-031 — `BatchOrchestrator.loadRaw` 가 자기호출이라 `@Transactional(REQUIRES_NEW, readOnly)` 가 발효되지 않는다 (1차 B-ISSUE-06 미해소)

- **심각도**: LOW (현재 기능 영향 없음 · 장식적 애너테이션 = 향후 회귀 위험)
- **기대 동작(기대효과)**: `process()` 는 무-트랜잭션이므로 영상 메타 조회를 "짧은 readOnly REQUIRES_NEW"로 격리한다는 주석(`BatchOrchestrator.java:83-84`)대로 실제 트랜잭션이 열려야 한다.
- **현재 동작(이슈 내용)**: `:104` 가 `LsDataRaw raw = loadRaw(rawSn);` 로 **자기호출**하고 `:147-149` 의 선언은
  ```java
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected LsDataRaw loadRaw(Long rawSn) { … }
  ```
  Spring AOP 프록시를 우회하므로 이 애너테이션은 적용되지 않는다. 현재는 `videoRepository.findById` 가 Spring Data 의 자체 트랜잭션으로 동작해 결과가 같아 무해하다.
- **재현/확인 경로**: [실동작] 미존재 rawSn 조회는 여전히 NOT_FOUND 로 정상 동작(`888888` → 404) — 즉 **증상이 없어 테스트로 잡히지 않는다.** [정적] `BatchOrchestrator.java:104` vs `:147-149`.
- **영향**: 이 프로젝트는 동일 패턴(`@Transactional` 자기호출로 경계 유실)으로 **배치 전면 불통 사고**를 낸 전례가 있다(`tests-green-runtime-broken-selfcall-tx`). 여기서 `loadRaw` 에 쓰기·락·격리 요구가 추가되는 순간 조용히 깨진다.
- **수정 방향(제안)**: `loadRaw` 를 별도 빈(`BatchRawLoader`)으로 분리하거나, 애너테이션을 제거해 "트랜잭션 없음"을 사실대로 표기한다. 클래스패스 스캔 가드(자기호출 `@Transactional` 탐지)에 이 지점을 추가한다. ⚠ **구현하지 않는다.**


### [B-ISSUE-24] TC-BATCH-060 / TC-BATCH-081 / TC-BATCH-089 — 회귀 테스트 공백 3종 (1차 이월 2건 + 신규 1건)

- **심각도**: LOW (방어·계약의 조용한 소실 위험)
- **기대 동작(기대효과)**: 보안 방어(로그 sanitize)·경계 계산(분수 fps)·역할 인가(PORTAL 차단)는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**:
  1. **CWE-117 sanitize**(1차 B-ISSUE-26 이월) — `MarkingBatchBridge.java:166-168` 의 `sanitize()` 를 검증하는 테스트가 `MarkingBatchBridgeTest`(15케이스) 에 없다. `grep -niE "sanitize|CWE-117|\\n|\\r"` → **0건**. 누군가 로그 문장을 리팩터링하며 `sanitize()` 를 빼도 잡히지 않는다.
  2. **분수 fps 반올림**(1차 B-ISSUE-27-6 이월) — `MarkingServiceTest` 는 fps 25/30/60 만 검증한다. `generateAutoMarks`/`manualFrameIndexLimit` 의 `Math.round` 가 절단으로 바뀌어도(실측상 상한이 3417→3416 으로 1 줄어드는 회귀) 테스트가 잡지 못한다. `grep -rn "29.97" backend/src/test` → 마킹 테스트군 **0건**.
  3. **PORTAL_USER 마킹 차단**(신규) — `MarkingController.java:51` 의 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 를 PORTAL 역할로 검증하는 테스트가 없다. `grep -rn "PORTAL" backend/src/test/java/kr/co/cudo/authoring/marking/` → **0건**. 실동작으로는 403 확인됨.
- **재현/확인 경로**: 위 3개 grep.
- **영향**: 세 항목 모두 **현재 구현은 정상**이며 실동작으로 확인했다. 리팩터링 시 무증상 소실 위험만 남는다.
- **수정 방향(제안)**: ①`dataSttsCd="PROC\nING"` 로그 캡처 단언 1건 ②`generateAutoMarks(10, 30, 29.97)` + `manualFrameIndexLimit(113, 29.97)==3417` 단언 ③`MarkingControllerTest` 에 PORTAL 토큰 403 케이스 1건. ⚠ **구현하지 않는다.**

---


### [B-ISSUE-44] TC-VLM-011 — 마킹 `PENDING→VLM_REQUESTED` 전이 시점이 "위탁 성공 후"에서 "**제출 전 선커밋**"으로 반전됐다(기대값 무효)

- **심각도**: LOW (동작은 의도된 개선, 문서 정합 문제)
- **기대 동작(기대효과)**: 케이스 — "위탁 **성공 시** 마킹 PENDING→VLM_REQUESTED, `persistMarkingTransition` 후 save".
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep:337` 이 **제출 이전에** 별도 빈을 프록시 경유로 호출해 독립 커밋한다.
  ```java
  markingTxService.persistVlmRequested(marking);   // REQUIRES_NEW, 제출 전 선커밋
  Long markingSn = marking == null ? null : marking.getMarkingSn();
  …
  vlmClient.submitTimeseries(req).subscribe(...);  // 이후에 제출
  ```
  `persistMarkingTransition` 메서드는 존재하지 않고 `VlmMarkingTxService.persistVlmRequested`(REQUIRES_NEW) / `markVlmFailedIfRequested`(제출 확정 실패 시 보상)로 분리됐다. 근거는 **콜백 선행 레이스 폐쇄** — 논블로킹 제출에서 벤더 콜백이 ACK 보다 먼저 도착하면 수신부가 전이 대상을 못 찾고, 그 뒤 스텝이 올려버려 `VLM_REQUESTED` 영구 고착이 된다. 수신부도 `markingsInScope`(`VlmResultService:186-208`)로 `ACTIVE_STATUSES` 를 보게 넓혀 **양단 방어**를 이룬다.
- **재현/확인 경로**: `VlmTimeseriesStepNonBlockingTest#상관키_등록과_마킹_VLM_REQUESTED_전이는_외부_제출보다_먼저_수행된다`.
- **영향**: 케이스 기대값이 현재 코드와 어긋난다. 또 "제출이 확정 실패하면 마킹이 `VLM_REQUESTED` 로 잠깐 올라갔다가 `VLM_FAILED` 로 보상 전이"라는 **중간 상태가 새로 생겼다** — 이 사이에 화면이 마킹을 조회하면 실제로는 위탁되지 않은 건이 "요청됨"으로 보인다(짧은 창).
- **수정 방향(제안)**: TC-VLM-011 을 "제출 **전** 선커밋(REQUIRES_NEW, `VlmMarkingTxService`)" 으로 재작성하고, 실패 보상 전이(`markVlmFailedIfRequested`)를 별도 케이스로 신설. ⚠ **구현하지 않는다.**


### [B-ISSUE-45] TC-VLM-040 / TC-VLM-003 관련 — 재개 경로가 프록시로 부르는 `run()` 은 `readOnly=true` 트랜잭션이라, 그 안에서 보류를 **다시 기록**하려 하면 실패한다 (미재현·정적)

- **심각도**: LOW (현재는 B-ISSUE-41 때문에 도달 자체가 불가 — 41 을 고치면 표면화)
- **기대 동작(기대효과)**: 어느 진입점으로 들어와도 VLM 보류/비활성 사유는 `LS_BATCH_PROC_LOG` 에 남아야 한다(B-ISSUE-24 해소의 전제).
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.run` 은 `@Transactional(REQUIRES_NEW, **readOnly = true**)`(`:236-239`) 다. `execute` 경로에서는 자기호출이라 이 속성이 무시되고 `execute` 의 쓰기 가능 경계를 쓰지만, **`VlmWithheldResumeRunner:69` 는 프록시 경유로 `run` 을 직접 호출**하므로 readOnly 경계가 실제로 개시된다. 그 안에서 게이트가 여전히 닫혀 있거나 `enabled=false` 면
  `:271` / `:305` 의 `batchStatusService.recordVlmSkipped(...)`(REQUIRED, `BatchStatusService:58-62`)가 **readOnly 트랜잭션에 참여**해 `LsBatchProcLog`(`@GeneratedValue(IDENTITY)`, `LsBatchProcLog.java:25-28`) INSERT 를 시도한다. `HibernateJpaDialect` 가 readOnly 를 JDBC 커넥션에 전파하므로 PostgreSQL 은 `cannot execute INSERT in a read-only transaction`(25006)로 거부한다. 그 예외는 `resumeAsync` 의 `catch(RuntimeException)`(`:73-78`)이 삼켜 WARN 한 줄만 남긴다.
  같은 경로의 다른 쓰기(`ledger.recordIssued`, `markingTxService.persistVlmRequested`, `recordVlmSkippedInNewTx`)는 전부 `REQUIRES_NEW` 별도 빈이라 영향이 없다 — **문제는 REQUIRED 인 `recordVlmSkipped` 하나**다.
- **재현/확인 경로**: 이번 회차 **미재현**. 재개가 실제로 발생한 유일한 케이스(B 대조군)는 게이트가 열려 제출 경로로 갔기 때문에 이 분기를 타지 않았다. 재현하려면 "메타 0건 + 보류 기록 有 + resolve 직후 다시 `'F'`" 또는 `vlm.client.enabled=false` 상태에서 재개를 발화시켜야 한다.
- **영향**: B-ISSUE-41 을 고쳐 재개가 살아난 뒤, **재개했는데 여전히 보류해야 하는 경우의 감사 행이 유실**된다(무한 재개 시도 + 무기록). 보안 영향은 없다(외부 전송은 여전히 차단됨).
- **수정 방향(제안)**: `run()` 의 `readOnly=true` 를 제거하거나(주석 `:213-216` 이 "이 경로엔 dirty 엔티티가 없다"고 하나 `recordVlmSkipped` 는 새 엔티티 INSERT 다), `recordVlmSkipped` 호출을 `recordVlmSkippedInNewTx`(이미 존재, `:78-82`)로 통일한다. 후자는 커넥션 점유가 늘어 주석에 적힌 커넥션 기아 우려가 있으므로 **전자(readOnly 제거)가 안전**하다. 회귀 가드는 ambient tx 없는 IT 로 세운다. ⚠ **구현하지 않는다.**


### [B-ISSUE-46] TC-VLM-013 / 019 / 024 / 025 — 1차 B-ISSUE-27 의 테스트 커버 갭이 **그대로 이월**, 게다가 벤더 왕복 계약 IT 는 2차에도 SKIP

- **심각도**: LOW (품질 게이트 신뢰도)
- **기대 동작(기대효과)**: P0/P1 로 분류된 보안·동시성 케이스는 자동 회귀 가드를 보유해야 한다.
- **현재 동작(이슈 내용)**:
  | 케이스 | 우선 | 커버 상태 |
  |---|:--:|---|
  | TC-VLM-013 콜백 URL 고정 base(SSRF) | P1 | 부분 — `VlmTimeseriesStepTest:177` 이 `endsWith("/v1/vlm/callback")` 만 단언. **base 가 사용자 입력에 오염되지 않음**을 단언하는 테스트 없음 |
  | TC-VLM-019 completed + 영상 미존재 → NOT_FOUND | P1 | **없음**(`VlmResultServiceTest` 에 해당 시나리오 부재) |
  | TC-VLM-024 동일 request_id 동시 콜백 직렬화 | **P0** | **없음.** 증강 채널엔 `AugmentCallbackIdempotencyIT`·`GenAiCallbackRollupConcurrencyIT`·`AugmentDeidentConcurrencyIT` 가 있는데 VLM 만 비대칭 |
  | TC-VLM-025 로그 마스킹(CR/LF/tab) | P1 | **없음**(`safe()`/`safeForLog()` 전용 테스트 0건) |

  추가로 `test-baseline.md §5` 의 **backend 스킵 5건이 전부 "라이브 목 서버 실연동 IT — KPST/VLM 클라이언트 왕복"** 이며, 그중 `VLM describe 는 accepted 와 request_id echo 를 반환한다` 가 포함된다. 즉 `VlmClient` 의 벤더 계약 정합(요청 스키마·`accepted` 화이트리스트·request_id echo 검증 `:154-169`)은 **1차·2차 모두 자동 실행 0회**다. 본 검증이 수동으로 왕복을 확인했으나 CI 회귀 가드는 여전히 없다.
- **재현/확인 경로**:
  ```bash
  grep -rn "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/webhook/VlmResultServiceTest.java
  ls backend/src/test/java/kr/co/cudo/authoring/webhook/ | grep -i concurren   # Augment 계열만 존재
  ```
- **영향**: TC-VLM-024 는 P0(동시 콜백 → 검수큐 중복 적재/META race)인데 비관적 락 배선이 깨져도 CI 가 잡지 못한다. 라이브 목서버 IT 스킵은 벤더 계약 드리프트를 무증상으로 만든다.
- **수정 방향(제안)**: ①`VlmCallbackIdempotencyIT`(동일 request_id 2스레드 동시 POST → 1건만 `applied:true`, 검수행 N건 유지)를 증강 IT 패턴 그대로 신설. ②`VlmResultServiceTest` 에 영상 미존재/로그 마스킹 케이스 추가. ③`resolveCallbackUrl` 이 설정 base 만 쓰고 요청값을 반영하지 않음을 단언하는 단위테스트. ④라이브 목서버 IT 를 CI 에서 mock-server 를 띄우고 실행하는 프로파일로 승격(현재는 조건부 SKIP 이라 영구 미실행). ⚠ **구현하지 않는다.**

---


### [B-ISSUE-63] TC-STREAM-B06 — Range 없는 200 응답에도 `Content-Range` 헤더가 부여됨 (RFC 7233 비정합, 정보)

- **심각도**: LOW (정보 — 케이스 기대결과는 충족)
- **기대 동작(기대효과)**: `Content-Range` 는 206(또는 416) 응답의 헤더다. 200 전체 응답에는 의미가 없다(RFC 7233 §4.2).
- **현재 동작(이슈 내용)**: [실동작]
  ```
  GET /api/v1/videos/126/stream   (Range 헤더 없음)
  HTTP/1.1 200
  Accept-Ranges: bytes
  Cache-Control: no-store
  Content-Range: bytes 0-34654318/34654319     ← 200 인데 부여됨
  Content-Length: 34654319
  ```
  원인은 200 경로도 `ResourceRegion` 을 반환하기 때문이다(`VideoStreamService.java:276-282`) — `ResourceRegionHttpMessageConverter` 가 write 시점에 `Content-Range` 를 직접 add 한다(`:262-265` 주석이 206 경로에서 이 동작을 이미 설명하고 있다).
- **재현/확인 경로**: `curl -s -D - -o /dev/null -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/126/stream`
- **영향**: 엄격한 중간 프록시/캐시가 200+`Content-Range` 를 부분 응답으로 오인할 여지. 브라우저 재생은 정상(실측). 보안 영향 없음.
- **수정 방향(제안)**: Range 부재 경로는 `ResourceRegion` 대신 `Resource`(또는 `ResponseEntity<Resource>`)를 반환하거나, 200 응답에서 `Content-Range` 를 제거한다. ⚠ **구현하지 않는다.**


### [B-ISSUE-64] TC-BATCH-126 — 배치 오토라벨 이미지 읽기가 lexical 경로 검증뿐이고 전용 회귀 테스트가 없다 (정보)

- **심각도**: LOW (정보 — 현재 입력원이 내부 DB 값이라 악용 경로 미확인)
- **기대 동작(기대효과)**: 프레임 이미지 open 은 `StorageSubtreePolicy`/`openNoFollow`(`NOFOLLOW_LINKS`) 계열의 realpath 기반 단일 판정기를 쓰는 것이 이 리포의 확립된 규약이다(CLAUDE.md "비식별 프레임을 파일로 여는 4경로는 단일 규약", CWE-59/367).
- **현재 동작(이슈 내용)**: `YoloAutolabelStep.java:427-440`(및 동일 코드 `Sam2SegmentStep.java:314-327`)
  ```java
  Path imagePath = baseRawPath.resolve(relativePath).normalize();
  if (!imagePath.startsWith(baseRawPath)) { throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로 범위 초과"); }
  byte[] bytes = Files.readAllBytes(imagePath);
  ```
  ①`normalize()` 후 `startsWith` 는 **lexical** 판정이라 base 하위의 심링크가 밖을 가리키면 통과한다(검증 경로 == open 경로이므로 TOCTOU 창은 좁으나 심링크 자체는 걸러지지 않는다). ②`backend/src/test/java/kr/co/cudo/authoring/batch/step/` 에 이 두 클래스의 **경로 순회 회귀 테스트가 없다**(traversal 테스트는 `DeidentFrameAttacherTest:511`·`FfmpegFrameExtractorTest:265`·`DeidentifyStepFailurePersistenceIntegrationTest:161` 뿐).
- **재현/확인 경로**:
  ```bash
  grep -rn "이미지 경로 범위 초과" backend/src/main/java   # 2곳(Yolo·Sam2)
  grep -rn "Traversal\|\.\./" backend/src/test/java/kr/co/cudo/authoring/batch/step/  # Yolo/Sam2 테스트 0건
  ```
- **영향**: 현재 `LS_DATA_SRC.SRC_FILE_PATH_NM` 은 프레임 추출기가 쓴 내부 값이고 증강 반입 경로는 `VideoArtifactRootResolver.verifyIngestablePath` 로 별도 검증되므로 실악용 경로는 확인되지 않았다. 리스크는 **가드가 다른 축으로 분화되어 규약 갱신 시 조용히 뒤처지는 것**(memory `state-gate-single-entry-point-rule` 6번째 축과 동형).
- **수정 방향(제안)**: 두 스텝의 `readImageAsBase64` 를 공용 판정기(`StorageSubtreePolicy` + `openNoFollow`)로 통일하고, base 이탈·심링크 이탈 각각의 회귀 테스트를 추가한다. ⚠ **구현하지 않는다.**

---


### [B-ISSUE-83] B-11 인접 — `GET /v1/videos/{rawSn}/labels/auto` · `/auto-summary` 가 신고 구간에도 라벨 메타를 노출한다(게이트 미배선 형제 경로)
- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간에는 라벨 조회 계열이 412 로 차단된다(`GET /v1/frames/{srcSn}/labels`·`label-history`·관제 `tasks/{rawSn}/labels` 전부 배선됨).
- **현재 동작(이슈 내용)**: 같은 영상의 **영상 단위 라벨 조회 2종**에는 게이트가 없다.
  ```
  GET /v1/videos/133/labels/auto   -> 200  data.objects[28]  {id,labelCode,labelName,color,confidence,createdBy}
  GET /v1/videos/133/auto-summary  -> 200  totalFrames=6 totalLabels=28 classDistribution=[car 11, truck 7, …]
                                           lowConfidenceFrames[].thumbnailUrl="/v1/frames/80/image"
  ```
  `grep -rn "requireNotUnderDeidentReport\|isUnderDeidentReport" VideoController/관련 서비스` → 해당 두 핸들러 경로에 0건.
- **영향**: 응답 DTO에 **좌표(`pointCn`)가 없어** PII 위치 특정 정보는 나가지 않고, 참조된 `thumbnailUrl` 도 호출하면 412 로 막힌다. 따라서 실질 노출은 "어떤 클래스가 몇 개 있는가"에 그친다. 다만 **차단 범위 목록이 엔드포인트 단위로 열거된 정책**에서 형제 경로만 빠진 형태라, 향후 이 DTO 에 좌표/썸네일 바이트가 추가되면 조용히 누수로 승격된다(이 리포의 반복 사고 패턴 — "컨트롤러가 정책 판정을 복제·누락").
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/133/labels/auto | head -c 300
  curl -s -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/133/auto-summary | head -c 300
  ```
- **수정 방향(제안)**: 두 핸들러의 서비스 진입부(인가 이후)에 `requireNotUnderDeidentReport(rawSn)` 을 추가하거나, "좌표 없는 집계는 게이트 대상 아님"을 `UNCERTAINTIES.md` 확정 정책으로 명문화해 판정 기준을 고정한다(둘 중 하나는 필요 — 현재는 의도인지 누락인지 문서로 판별 불가). ⚠ **구현하지 않는다.**


### [B-ISSUE-84] TC-BATCH-166 / 167 / 168 — stale 회수 스윕의 clamp·토글 독립·예외 격리에 전용 테스트가 없다
- **심각도**: LOW (커버리지 갭 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 케이스 3건이 각각 단언 대상(`MIN_STALE_TIMEOUT_MINUTES` 하한 clamp / 자기 토글 off 시 미등록 / `Throwable` 삼킴)을 회귀 테스트로 고정한다. 특히 clamp 는 "과소 설정이 정상 처리 중 항목을 뺏어가 이중 처리 전면화"를 막는 fail-safe 라 회귀 시 영향이 크다.
- **현재 동작(이슈 내용)**: `grep -rn "BatchRetryStaleReclaimSweeper" backend/src/test/` → **0건**. `BatchRetryStaleReclaimIT`(4건)는 `BatchRetryQueue.sweepStaleRetrying` 만 직접 호출하고 스위퍼 빈은 거치지 않는다. 동형 자산(`AugmentJobExpirySweeperTest` 의 `isScheduled()` 단언, `VlmSubmitPendingSweeperTest:121` 의 `staleTimeoutMinutes()` 하한 단언)이 존재하므로 패턴은 이미 있다.
- **재현/확인 경로**:
  ```bash
  grep -rn "BatchRetryStaleReclaimSweeper\|MIN_STALE" backend/src/test/java   # 0건
  grep -rn "isScheduled\|staleTimeoutMinutes" backend/src/test/java           # Augment/Vlm 스위퍼만 매칭
  ```
- **영향**: clamp 상수를 낮추거나 `@PostConstruct` 등록 조건을 바꿔도 테스트가 초록으로 통과한다. 현재 구현 자체는 정상(정적 확인 + 실기동 로그로 확인).
- **수정 방향(제안)**: `VlmSubmitPendingSweeperTest`/`AugmentJobExpirySweeperTest` 와 동형으로 `BatchRetryStaleReclaimSweeperTest` 신설 — ①`stale-timeout-minutes=0/-1/5` 로 생성 시 `staleTimeoutMinutes() >= 30` ②`enabled=false` 면 `isScheduled()==false` ③`retryQueue.sweepStaleRetrying` 가 `Error` 를 던져도 `run()` 이 0 을 반환. ⚠ **구현하지 않는다.**


### [B-ISSUE-85] TC-BATCH-154 — 재처리 클레임의 2컬럼(raw 우선 → work 폴백) 조건부 UPDATE 에 직접 테스트가 없다
- **심각도**: LOW (커버리지 갭)
- **기대 동작(기대효과)**: `tryClaimReprocessFromFailed` 가 ①`LS_DATA_RAW` FAILED→PROCESSING 을 우선 클레임하고 ②실패 시 `LS_RAW_DATA_STATUS` FAILED→PROCESSING 을 클레임한다는 두 갈래가 회귀 테스트로 고정된다.
- **현재 동작(이슈 내용)**: `grep -rn "claimReprocessFromFailed" backend/src/test/java` → **0건**. `BatchReprocessServiceTest` 는 `transitionService.tryClaimReprocessFromFailed(...)` 를 Mockito 로 stub 할 뿐 실제 UPDATE 를 실행하지 않는다. `BatchReviewOwnedEntryGuardIT:225` 는 `releaseReprocessClaim` 을 **정리 목적**으로만 호출한다.
- **재현/확인 경로**: 위 grep. 실동작으로는 raw 경로(rawSn 128, raw=FAILED)만 확인되었고 **work 폴백 경로는 미검증**이다(raw≠FAILED + work=FAILED 인 영상이 현 DB 에 없음).
- **영향**: 두 컬럼 중 한쪽 술어가 바뀌어도 감지되지 않는다. 이중 실행 차단(CWE-362)의 최종 방어라 회귀 시 영향이 크다.
- **수정 방향(제안)**: Testcontainers IT 로 ①raw=FAILED/work=null → true·raw 가 PROCESSING 으로 전이 ②raw=COMPLETED/work=FAILED → true·work 만 전이 ③둘 다 아님 → false·전이 0건 ④동시 2호출 시 1건만 true 를 단언. ⚠ **구현하지 않는다.**


### [B-ISSUE-86] TC-BATCH-159 / 160 — local 프로파일에서 재시도 폴러가 미등록이라 큐의 등록→발화 전 사이클을 실동작으로 검증할 수 없다 (1차 B-ISSUE-85 이월)
- **심각도**: LOW (환경 구성 — dev/stg/prd 무영향)
- **기대 동작(기대효과)**: 검증 환경에서 `enqueueIfRetryable` → `pollReady`(CAS 클레임) → `orchestrator.process` 전 사이클과 2노드 클레임 직렬화를 관측할 수 있다.
- **현재 동작(이슈 내용)**: `BatchRetryTriggerConfig:19` 가 `@ConditionalOnProperty(prefix="authoring.batch", name="enabled", havingValue="true", matchIfMissing=true)` 인데 `application-local.yml:61-62` 가 `batch.enabled: false` → Quartz 트리거 미등록. 반면 배치 본체는 `MarkingBatchBridge → AsyncBatchRunner`(@Async) 로 구동되어 **재시도 적재는 계속 일어난다.**
  ```
  ls_bat_rty_wtng: 8|144|3|3|PENDING|…    ← 도래했으나 발화 0건
  기동 로그: Scheduler KlidAuthoringScheduler_$_NON_CLUSTERED started  (batchRetryTrigger 등록 로그 없음)
  ```
  런타임 실측 `SPRING_PROFILES_ACTIVE=local`, `BATCH_ENABLED` 미설정.
- **영향**: TC-BATCH-159/160 이 IT·정적 근거로만 판정된다(본 리포트도 그렇게 처리). 1차 대비 상태 변화 없음. 단 **stale 회수 스윕은 자기 토글이라 이 영향을 받지 않는다**(TC-BATCH-167 이 실동작으로 확인) — 무관 토글 종속 회피 설계가 실제로 작동함이 확인된 셈이다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -E "SPRING_PROFILES_ACTIVE|BATCH_ENABLED"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select * from ls_bat_rty_wtng;"
  docker logs klid-backend 2>&1 | grep -i "batchRetryTrigger"    # 0건
  ```
- **수정 방향(제안)**: 검증용 프로파일에서만 `BATCH_ENABLED=true` + 짧은 `BATCH_RETRY_INTERVAL_SEC` 로 기동하거나, 폴링 1 tick 을 강제하는 dev 트리거(`POST /v1/dev/batch/retry-tick`)를 두어 큐 사이클을 관측 가능하게 한다. ⚠ **구현하지 않는다.**

---


### [B-ISSUE-102] TC-DEID-061 — 위탁 사전조건 위반(CWE-22 경로 가드 포함)이 `INVALID_INPUT`(400) 대신 `EXTERNAL_API_ERROR`(502) 로 마스킹된다

- **심각도**: LOW (오류 분류 정확도 — 실경로 도달 가능성이 낮다)
- **기대 동작(기대효과)**: TC-DEID-061 — 원본 경로에 부모 디렉터리가 없으면 `INVALID_INPUT`. `ErrorCode.INVALID_INPUT` = `HttpStatus.BAD_REQUEST`(`ErrorCode.java:6`). 입력 문제이므로 400 이어야 하고, 외부 API 장애(502)와 구분돼야 운영이 원인을 가른다.
- **현재 동작(이슈 내용)**: 가드 자체는 존재하나 호출측이 **모든 `RuntimeException` 을 재포장**한다.

  ```java
  // KpstDeidentService.java:291-303
  try {
      projectReq = buildProjectRequest(raw, rawSn);          // 내부에서 INVALID_INPUT / INTERNAL_ERROR 를 던짐
  } catch (RuntimeException e) {
      txService.failSubmit(procLogSn, rawSn, SUBMIT_FAILED_CODE, e.getClass().getSimpleName());
      log.error("[KpstDeid] submit prepare failed rawSn={} errType={}", rawSn, e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 위탁 실패", e);   // ← 502 로 덮임
  }
  ```

  `buildProjectRequest` 안의 `INVALID_INPUT` 3종(빈 경로 `:315-317`, 부모 없음 `:320-323`, 디렉터리 생성 실패 `INTERNAL_ERROR` `:333-336`)이 전부 502 로 나온다. 대비되게 **원본 실재 가드**(TC-DEID-081)는 `try` **이전**(`:286`)에 있어 `INVALID_INPUT` 이 그대로 보존된다 — 같은 성격의 두 가드가 서로 다른 코드로 나가는 비대칭이다.
- **재현/확인 경로**: `ls_data_raw.raw_file_path_nm` 을 부모 없는 상대명(예: `video.mp4`)으로 두고 위탁. 단 `verifySourceOrFail` 이 먼저 파일 실재를 보므로, 이 분기에 도달하려면 프로세스 CWD 에 같은 이름의 파일이 실재해야 한다 → **실경로 도달 가능성 낮음**(그래서 LOW).
- **영향**: 운영 알림/대시보드에서 "입력 오류"가 "외부 비식별 서버 장애"로 분류된다. 또한 사전조건 실패인데 `ERR_CD=KPST_SUBMIT_FAILED`(외부 호출 실패 코드)로 원장에 남아 `KPST_SOURCE_MISSING` 과 달리 원인 식별이 안 된다. 보안 영향 없음(경로 원문은 노출되지 않는다).
- **수정 방향(제안)**: `catch (CustomException ce)` 를 분리해 사전조건 계열은 원래 `ErrorCode` 로 재전파하고(원장 코드도 `KPST_PREPARE_FAILED` 등으로 분리), 그 외만 `EXTERNAL_API_ERROR` 로 감싼다. ⚠ **구현하지 않는다.**


### [B-ISSUE-103] TC-DEID-062 — 논블로킹 제출 전환으로 `createProject` 실패의 **동기 예외 전파가 사라졌다**(케이스 기대값 무효)

- **심각도**: LOW (정보 — 코드가 의도적 개선, 카탈로그가 낡음)
- **기대 동작(기대효과)**: TC-DEID-062 — `createProject` 예외 시 `procLog.fail` + `markDeidentified("F")` + **예외 전파**(클래스명만).
- **현재 동작(이슈 내용)**: `862ca6d8`(카탈로그 최신화 `119ea148` **이후** 머지) 이후 제출은 구독만 하고 즉시 반환한다.

  ```java
  // KpstDeidentService.java:421-431 — subscribeSubmit
  kpstClient.createProject(projectReq)
      .subscribe(resp -> ... outcomeRecorder.onAccepted(...),
                 err  -> ... outcomeRecorder.onSubmitFailed(rawSn, procLogSn, err));
  ```

  외부 호출 실패는 전용 풀(`kpstSubmitScheduler`)에서 `KpstSubmitOutcomeRecorder.onSubmitFailed` → `KpstDeidentTxService.failSubmit`(`:163-184`) 로 처리되어 **procLog FAILED + `DE_IDNTF_YN='F'` + (REDEIDENT면) 작업락 해제**가 커밋된다. 즉 *부수효과 2종은 충족*하고 **동기 예외 전파만 성립하지 않는다**. 동기 `EXTERNAL_API_ERROR` 는 제출 **이전** 사전조건 실패에만 남는다(`:302`).
  아무 신호도 오지 않는 경우(노드 사망 등)는 폴러가 ACK 유예 만료로 `ACK_MISSING_CODE` 회수한다(`:557-560`).
- **재현/확인 경로**: `KPST_DEID_BASE_URL` 을 닫힌 포트로 두고 적재 → 호출 응답은 성공(202/201)이고, 수 초 뒤 `ls_deident_proc_log.err_cd='KPST_SUBMIT_FAILED'` + `ls_data_raw.de_ident_yn='F'` 로 관측된다. 관련 테스트: `KpstSubmitOutcomeRecorderTest` · `KpstSubmitAsyncCommitIT` · `KpstSubmitSignalDispatchGuardTest`.
- **영향**: 없음(설계 의도 — 메모리 `external-integrations-must-be-async` 정책 준수). 다만 카탈로그 기대값을 고치지 않으면 다음 회차에서 같은 PARTIAL 이 반복된다.
- **수정 방향(제안)**: TC-DEID-062 의 기대결과를 "비동기 실패 신호 → `failSubmit`(procLog FAILED + `'F'` + REDEIDENT 락 해제) 커밋, **동기 예외 없음**"으로 개정하고, 동기 전파 케이스는 `TC-DEID-061`(사전조건) 쪽으로 분리. ⚠ **구현하지 않는다(문서 개정 제안).**


### [B-ISSUE-104] TC-DEID-067 — `prjId == null` 경로가 `markTimeoutIfExpired` 대신 **ACK 대기 유예 + 회수**로 대체되었다(케이스 기대값 무효)

- **심각도**: LOW (정보 — 코드가 의도적 개선, 카탈로그가 낡음)
- **기대 동작(기대효과)**: TC-DEID-067 — `prjId` 가 null 이면 `markTimeoutIfExpired` 만 수행하고 외부는 호출하지 않는다.
- **현재 동작(이슈 내용)**:

  ```java
  // KpstDeidentService.java:541-561 (pollOne)
  if (prjId == null) {
      if (withinSubmitAckGrace(procLog)) {          // REQ_DT + kpst.deid.submit-ack-grace-sec(기본 180)
          log.debug("[KpstDeid] skip poll — awaiting submit ack rawSn={}", rawSn);
          return;                                    // ← 외부 호출 0건, 시도 카운터 미소모
      }
      boolean reclaimed = txService.failSubmit(procLogSn, rawSn, ACK_MISSING_CODE, "submit ack not received");
      log.warn("[KpstDeid] submit ack missing — reclaimed rawSn={} applied={}", rawSn, reclaimed);
      return;
  }
  ```

  코드 주석이 사유를 명시한다 — *"구 코드처럼 `markTimeoutIfExpired` 를 부르면 시도 카운터/경과 타임아웃 예산만 헛되이 소모"*. 종결은 `claimSubmitFailure`(WAITING + prjId null) 조건부 UPDATE 라 **지각 ACK 를 강등하지 않는다**(`LsDeidentProcLogRepository:168-184`).
  → 케이스의 "외부 미호출"은 충족하나 "markTimeoutIfExpired"는 성립하지 않는다.
- **재현/확인 경로**: `ls_deident_proc_log` 에 `POLL_STTS_CD='WAITING'` + `DE_IDNTF_PJT_ID IS NULL` 행을 두고 폴링 틱 관측 → 유예 내에는 `poll_atmpt_cnt` 가 증가하지 않고 mock 인바운드도 없다. 본 검증에서 만든 합성 행(procLogSn=141)도 유예 구간이라 외부 호출 0건이었다(검증 후 삭제 완료).
- **영향**: 없음(개선). 유예 기본 180초는 클라이언트 타임아웃 45s × 재시도 3회 + 백오프 최악값(≈138s)을 덮도록 산정돼 있다(`:202-210`).
- **수정 방향(제안)**: TC-DEID-067 기대결과를 "ACK 유예 내 skip(외부 호출 0·카운터 미소모) / 유예 초과 시 `failSubmit(ACK_MISSING)` 회수"로 개정. ⚠ **구현하지 않는다(문서 개정 제안).**

---


## C 클러스터 — 20건

### [C-ISSUE-61] TC-SAM2-14 / TC-SAM2-09 대비 — SAM2 Track 의 mock 폴백이 실추론과 구분 불가하고 FE 가 무조건 자동 병합한다 (G-ISSUE-22 의 FE 종단 파급)

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: CLAUDE.md SFR-08-01 은 *"SAM2 분할(클릭/박스→폴리곤+신뢰도) … **mock 응답은 FE 자동적용 차단**"* 을 계약으로 못박고 있다. SAM2 Track 은 같은 VOS(추적+분할) 축의 형제 기능이고, ai-server 는 Track 응답에도 `mock`/`source`/`mock_reason` 을 **실제로 실어 보낸다**(`ai-server/app/schemas.py:180-188`). 따라서 Track 의 mock 폴백 결과도 사용자 작업본에 자동 병합되어서는 안 되며, 최소한 "AI 모델 미로드 — 결과 신뢰 불가" 급의 경고가 표시돼야 한다. Track 의 mock 폴백은 *"이전 프레임 폴리곤을 그대로 반사하고 score 를 0.5 로 고정"* 하는 **추적이 전혀 일어나지 않은 가짜 결과**라 오히려 Segment 보다 위험하다(Segment 는 빈 폴리곤이라 시각적으로 티가 나지만 Track 은 정상 결과와 형태가 동일하다).
- **현재 동작(이슈 내용)**: mock 신호가 **BE 클라이언트 DTO → BE 서비스 → BE 응답 DTO → FE** 4계층에서 연속으로 소멸한다.
  1. `backend/src/main/java/kr/co/cudo/authoring/common/client/dto/Sam2TrackResponse.java:14-18`
     ```java
     public record Sam2TrackResponse(
             @JsonProperty("track_id") String trackId,
             List<List<Double>> polygon,
             double score
     ) {}
     ```
     `mock`/`source`/`mock_reason` **미선언** → Jackson 이 조용히 폐기(컴파일·런타임 에러 없음). 대조군 `Sam2Response.java:26-32` 는 3필드를 모두 선언하고 javadoc 에 *"BE 는 이를 그대로 클라이언트 응답으로 전파해 FE 가 … 자동 적용 차단을 수행하도록 한다"* 고 명시돼 있다.
  2. `label/service/Sam2TrackService.java:105-142` — `aiRes.trackId()`/`polygon()`/`score()` 만 읽고 mock 판정 없음. 대조군 `Sam2SegmentService.java:124-127` 는 `if (aiRes.mock()) return Sam2SegmentResponse.empty();`.
  3. `label/dto/Sam2TrackResponseDto.java:21-28 TrackedItem` — mock 필드 없음. 컨트롤러(`LabelController.java:132-145`)도 Segment 와 달리 안내 message 를 세팅하지 않는다.
  4. `frontend/src/pages/label/LabelingPage.tsx:634-667 handleTracked` — **조건 없이** `mergeAutoLabels(...)` / `stashPendingTracks(...)` 후 `pushToast({variant:'success', message:'AI 추적 완료 (N프레임)'})`. mock 분기도 score 임계 분기도 없다. 대조군 `frontend/src/features/label/canvas/layers/OverlayLayer.tsx:408-417` 는 `res.polygon.length===0`(mock 신호) 와 `res.score < 0.3`(저신뢰) 두 단계로 자동 적용을 차단한다.
- **재현/확인 경로** (실측 완료):
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' -d '{"role":"WORKER","channel":"INTERNAL"}' | jq -r .data.token)
  # 이미지 밖 폴리곤으로 실모델 mask 실패 → ai-server 가 mock 폴백
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-track \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"trackId":"mock-probe","prevPolygon":[[9000,9000],[9001,9000],[9001,9001]],"label":"car","nextSrcSns":[79]}'
  # → 200 {"success":true,"data":{"tracked":[{"srcSn":79,"trackId":"mock-probe","label":"car",
  #        "points":[[9000.0,9000.0],[9001.0,9000.0],[9001.0,9001.0]],"score":0.5,"shapeType":"POLYGON"}]},
  #        "message":null,"errorCode":null}
  docker logs --tail 20 klid-ai-server | grep "mock fallback"
  # → WARNING:app.routers.sam2:[SAM2] track real returned no mask — prev polygon fallback
  ```
  대조군(Segment 는 정상 차단):
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"box":[400,400,100,100]}'
  # → 200 {"data":{"polygon":[],"score":0.0,"empty":true},"message":"AI 모델 미로드 — 결과 신뢰 불가"}
  ```
- **영향**: **데이터 무결성 / 학습데이터 품질**(CWE-393 Return of Wrong Status Code 계열, 신뢰경계 미표시). ①ai-server 모델 미로드(`AI_MOCK_MODE=true`·가중치 부재·로드 실패)나 실모델의 mask 실패 시, 작업자는 **"AI 추적 완료"** 성공 토스트를 보고 가짜 좌표를 작업본에 받아들인다. ②그 좌표는 `PUT /v1/frames/{srcSn}/labels` 로 확정되면 실제 학습데이터가 되고, `AUTO_LBL_YN='Y'`+`AUTO_SAM2` provenance 까지 붙어 **"AI 가 추적한 라벨"로 데이터마트에 나간다**. ③mock 폴백은 이전 폴리곤을 그대로 반사하므로 N프레임 전부 **동일 좌표**가 되어 트랙 보간·품질검사에서도 "정지한 객체"로 보일 뿐 오류로 식별되지 않는다. ④부수적으로, 반사된 좌표가 이미지 경계를 벗어나 있으면(track 의 `validatePolygon` 은 상한 검사가 없다 — `C-ISSUE-62` 참조) 저장 단계(`LabelService.validateWithinBounds`)에서야 400 이 나 작업자는 원인 불명의 저장 실패를 겪는다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`Sam2TrackResponse` 에 `mock`/`source`/`mock_reason` 3필드 추가(하위호환 생성자 병행) → ②`Sam2TrackService` 에서 `aiRes.mock()` 이면 해당 프레임을 결과에서 제외하거나(Segment 의 `empty()` 대칭) 응답에 표식 전파 → ③`Sam2TrackResponseDto.TrackedItem` 또는 `Sam2TrackResponseDto` 상위에 표식 노출 + 컨트롤러가 `Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE` 를 재사용 → ④FE `handleTracked` 에 mock·저신뢰 차단 분기 추가(Segment 의 `OverlayLayer.tsx:408-417` 과 동일 규약). 회귀 가드로 `Sam2TrackServiceTest` 에 mock 응답 케이스, FE `sam2-track.test.tsx` 에 자동병합 차단 케이스 추가.


### [C-ISSUE-81] TC-TRACK-30(인접) — ByteTrack `track_id` 가 위치 기반 zip 으로 **엉뚱한 검출에 배정**된다

- **심각도**: HIGH
- **기대 동작(기대효과)**: `/infer/yolo/track` 이 돌려주는 `track_id` 는 **그 검출(bbox)의 객체 식별자**여야 한다. 이 값이 `LS_DATA_LBL.TRCK_ID` 로 적재되어 트랙 보간·트랙 편집·트랙 병합·검수 스냅샷의 객체 동일성 기준이 되므로, 잘못 붙으면 **서로 다른 객체가 같은 트랙으로 묶이고 같은 객체가 끊긴다**.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:_apply_bytetrack` 이 트래커 출력을 **입력 리스트와 위치(zip)로** 매핑한다.

  ```python
  tracked = tracker.update(sv_dets)
  tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
  if len(tracker_ids) != len(dets):
      logger.warning("[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d — 누락분 track_id=None 유지", ...)
  for det, tid in zip(dets, tracker_ids):          # ← 위치 기반. tracked 의 순서를 보지 않는다
      det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None
  ```

  그런데 `ByteTrackTracker.update()` 는 **입력 순서를 보존하지 않는다.** 컨테이너 내 직접 프로브(`docker exec klid-ai-server python /tmp/bt_probe.py`, 입력 박스 순서 `[A(10,10), B(200,200), C(400,400)]`):

  ```
  step 0  ids=[-1,-1,-1]   out_xyxy=[[200..],[10..],[400..]]      # B,A,C 로 재배열
  step 1  ids=[ 0, 1,-1]   out_xyxy=[[10..],[400..],[200..]]      # A,C,B 로 재배열
  ```

  step 1 의 정답 매핑은 `A→0, C→1, B→None` 인데, 코드는 위치로 `dets[0]=A→0`(우연히 일치), **`dets[1]=B→1`(오배정 — 정답은 None)**, **`dets[2]=C→None`(정답은 1)** 을 부여한다.
  실환경 로그에도 같은 상황이 반복 관측된다: `docker logs klid-ai-server` → `WARNING:app.models.bytetrack_util:[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5 — 누락분 track_id=None 유지`(3회). 이 WARN 은 "길이가 다르다"만 알릴 뿐 **순서가 다르다는 사실은 감지하지 못한다.**
  BE 는 이 값을 그대로 신뢰해 적재한다 — 배치 `batch/step/YoloAutolabelStep.java:295` → `batch/step/YoloLabelPersister.java:82-84`(`String.valueOf(trackId)` → `TRCK_ID`).
- **재현/확인 경로**:
  ```bash
  # 1) 재배열 확증 (ai-server 컨테이너 내부)
  docker exec klid-ai-server python - <<'PY'
  import numpy as np, supervision as sv
  from trackers import ByteTrackTracker
  t = ByteTrackTracker()
  boxes=[[10,10,50,50],[200,200,240,240],[400,400,440,440]]; confs=[0.9,0.15,0.85]
  for s in range(2):
      d=sv.Detections(xyxy=np.array(boxes,float),confidence=np.array(confs,float),class_id=np.array([0,0,0]))
      o=t.update(d); print(s, list(o.tracker_id), o.xyxy.tolist())
  PY
  # 2) 실경로 관측
  docker logs klid-ai-server 2>&1 | grep "길이 불일치"
  # 3) BE 경유 (REVIEWER 토큰)
  curl -s -X POST http://localhost:18081/api/v1/frames/94/yolo-track \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"srcSn":94,"nextSrcSns":[95,95,95]}'
  # → frameIndex 2·3 에서 9건 중 앞 3건에만 trackId 4,5,6 부여
  ```
- **영향**: 데이터 무결성(CWE-707 계열 — 부정확한 식별자 할당). 학습데이터의 객체 궤적이 조용히 뒤섞인다. `TrackMergeService`·`TrackEditService`·`TrackInterpolationStep` 이 모두 `TRCK_ID` 를 신뢰하므로 오염이 산출물(export JSON)까지 전파된다. 현재 실DB 에 `TRCK_ID` 보유 행이 1건뿐이라 **피해 규모는 작지만, `TRCK_ID` 가 정상화되는 순간 즉시 대량 오염으로 바뀐다**(즉 `C-ISSUE-82` 가 이 결함을 가리고 있다).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `tracked` 가 돌려주는 `xyxy`(또는 detection index)로 **입력 detection 을 역매핑**한 뒤 `track_id` 를 부여한다(좌표 동등 비교 또는 `sv.Detections` 에 인덱스를 `data` 로 실어 왕복). 길이 불일치 WARN 은 유지하되 **순서 불일치도 감지**하는 단언을 추가한다. 역매핑이 불가능한 트래커 구현이면 `track_id` 를 아예 부여하지 않는 편(전량 None)이 오배정보다 안전하다.

---


### [C-ISSUE-82] TC-TRACK-01~11 — 트랙 보간이 실환경에서 **한 번도 실행되지 않는다**(`TRCK_ID` 사실상 전량 NULL, B-ISSUE-61 재확인)

- **심각도**: MEDIUM (기능 미작동은 HIGH 급이나, 1차 발견 이슈(B-ISSUE-61)의 2차 재확인 + 원인 규명이므로 중복 상향을 피함)
- **기대 동작(기대효과)**: CVAT 트랙 보간 포팅(SFR-08 / `docs/analysis/portable-modules/01`)의 목적은 **키프레임 사이 프레임을 자동으로 채워 라벨링 공수를 줄이는 것**이다. 배치 `INTERPOLATE` 단계가 실제로 `LBL_SRC_CD='INTERPOLATE'` 라벨을 생성해야 한다.
- **현재 동작(이슈 내용)**: 보간 후보 조건이 `TRCK_ID IS NOT NULL` 인데(`batch/step/TrackInterpolationStep.java:174` → `LsDataLblRepository.findAutoBboxWithTrackId`), 실DB 는 —

  ```
  select count(*) total, count(trck_id) with_track, count(distinct trck_id) d from ls_data_lbl;
   total | with_track | d
  -------+------------+---
     421 |          1 | 1        -- 유일한 값은 '0'
  ```

  그 결과 배치 로그가 전건 no-op 이다(`docker logs klid-backend --since 24h`):
  ```
  [Batch][Interpolation] no interpolation candidates rawSn=143
  [Batch][Interpolation] no interpolation candidates rawSn=145 / 146 / 147 / 148 / 149 / 152 / 153 / 154 / 156
  ```
  `[Batch][Interpolation] saved … interpolatedRows=` 로그는 **0건**. 즉 `TrackInterpolator`·`PolyshapeMatcher`(총 약 340줄)와 그 위의 `TrackMergeService` 재보간 원자성 배선까지 **전부 사문(死文)** 이다.
  원인은 상위 `track_id` 부재다 — ai-server 직접 프로브 결과 ByteTrack 은 `frame_index=0,1` 에서 전 검출 `track_id=null`, `frame_index=2` 에서야 9건 중 3건만 부여한다. 현 데이터의 영상당 프레임 수가 3~12장이라 확정 전에 시퀀스가 끝난다. **단위 테스트는 `Keyframe` 픽스처를 직접 생성하므로 이 결손을 구조적으로 감지하지 못한다**(`TrackInterpolatorTest` 10건 + `TrackInterpolatorPolyshapeTest` 13건 전부 GREEN).
- **재현/확인 경로**:
  ```sql
  select count(*), count(trck_id) from ls_data_lbl;
  select count(*) from ls_data_lbl l join ls_data_lbl_ai_info ai on ai.data_lbl_sn=l.lbl_sn
   where ai.lbl_src_cd='INTERPOLATE';   -- 0
  ```
  ```bash
  docker logs klid-backend --since 24h 2>&1 | grep "\[Batch\]\[Interpolation\]"
  ```
- **영향**: 요구 기능(트랙 보간) 미제공. 라벨링 공수 절감 효과 0. 나아가 **회귀 안전망이 실효 없음** — 보간 코드가 깨져도 실환경에서는 증상이 나타나지 않는다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①선행으로 `C-ISSUE-81`(track_id 오배정) 해소 ②ByteTrack 확정 지연(`track_activation_threshold`/`minimum_consecutive_frames`) 튜닝 또는 미확정 트랙에도 잠정 ID 를 부여하는 정책 결정 ③**보간 도달성 자체를 검증하는 통합 테스트**(실프레임 시퀀스 → `TRCK_ID` 부여 → `INTERPOLATE` 라벨 ≥1) 추가 — 픽스처 단위 테스트만으로는 이 결함을 영원히 못 잡는다 ④수동 라벨링에서 작업자가 트랙을 지정하는 동선이 있다면 그 경로로도 `TRCK_ID` 가 채워지는지 확인.

---


### [C-ISSUE-01] TC-MARK-01/02(연장) — 마킹 201 응답과 `LS_MARKING` 이 **원본(비식별 전) NAS 절대경로**를 담는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 마킹은 비식별 영상을 대상으로 하는 단계다(루트 `CLAUDE.md` — "마킹 화면: **비식별 영상** 스트리밍", "마킹 = 비식별화 완료 후 비식별 영상 대상"). 마킹 레코드가 보관·반환하는 "영상 경로"도 비식별 산출물(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)이어야 하며, 최소한 원본 절대경로를 작업자에게 반환해서는 안 된다.
- **현재 동작(이슈 내용)**: `MarkingService.java:216-218`
  ```java
  LsMarking marking = "AUTO".equals(req.mode())
          ? LsMarking.createAuto(rawSn, eventName, req.intervalFrames(), raw.getRawFilePathNm(), marksJson, actorNo, fps)
          : LsMarking.createManual(rawSn, eventName, raw.getRawFilePathNm(), marksJson, actorNo, fps);
  ```
  `raw.getRawFilePathNm()`(= 관제 원본 경로)이 `LS_MARKING.VIDEO_FILE_PATH_NM` 에 저장되고, `MarkingResponse.java:54` 가 이 값을 `videoPath` 로 **201 응답에 실어 WORKER 에게 반환**한다.
  - 실측(rawSn 146, **배정 WORKER 토큰**): `"videoPath":"/app/storage/raw/seed/sample-cctv-1080p.mp4"`
  - 같은 영상의 비식별 산출 경로: `LS_DEIDENT_PROC_LOG(146).DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/146/deid/sample-cctv-1080p-mask.mp4` (디렉터리·파일명 모두 상이 — 문자열 치환으로 도출되지 않음)
  - 본 검증이 만든 마킹 10행(sn 14~24) **전부** 원본 경로.
  - **소비자 추적**: `src/main` 전수 grep 결과 `getVideoFilePathNm()` 참조는 `MarkingResponse.java:54` **한 곳뿐**이다. 즉 이 컬럼은 파이프라인 어디에서도 쓰이지 않고 **응답으로 내보내는 것이 유일한 용도**다. (VLM 위탁은 이 값을 쓰지 않고 별도로 비식별본을 해석 — mock 로그가 `-mask.mp4` 를 프로브한 것으로 확인.)
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/videos/146/markings \
    -H "Authorization: Bearer $WORKER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"mode":"AUTO","intervalFrames":1200}' | python3 -m json.tool   # → data.videoPath
  ```
  ```sql
  select m.video_file_path_nm, d.de_idntf_file_path_nm
    from ls_marking m join ls_deident_proc_log d on d.data_raw_sn = m.raw_sn
   where m.raw_sn = 146 and d.proc_stts_cd = 'SUCCEEDED';
  ```
- **영향**: 화면상 원본 **픽셀** 노출은 없다(스트리밍은 비식별본만 서빙, `Content-Range .../34654319` = mask 파일 크기로 확인). 그러나 ① 관제 NAS 내부 절대경로가 WORKER 권한 응답에 실린다(CWE-209 정보 노출 — 저장소 레이아웃·파일명 규칙이 그대로 드러난다) ② 지금은 소비자가 없지만 이 컬럼을 읽는 후속 로직이 생기면 곧바로 **원본을 가리키게 된다**(비식별 계약 위반의 잠복 지점). 2차 파이프라인 구동의 PIPE-ISSUE-02 와 동일 결함이며 독립 재현으로 확정됐다.
- **수정 방향(제안)**: 비식별 산출 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` **저장값 조회** — 파일명을 조합·추측하지 말 것)를 저장·반환하거나, 응답에서 경로 필드를 제거하고 스트리밍 URL 만 내린다. ⚠ **구현하지 않는다.**


### [C-ISSUE-03] TC-MARK-46(연장) — 배치가 미트리거된 마킹은 **영원히 활성(PENDING)** 으로 남아 해당 영상의 재마킹을 무기한 409 로 막는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 409 안내문이 실제로 수행 가능한 동선을 말해야 한다(루트 `CLAUDE.md` 의 증강 409 안내 원칙과 동일 취지 — "수행 불가능한 안내를 하지 않는다"). `이미 진행 중인 마킹이 있습니다. 기존 마킹이 **종결된 뒤** 다시 시도하세요.` 라고 안내한다면, 그 마킹을 종결시키는 경로가 존재해야 한다.
- **현재 동작(이슈 내용)**: `MarkingBatchBridge` 가 배치를 트리거하지 못하는 경로(검수 소유 상태 `PENDING/IN_REVIEW/APPROVED/REJECTED` 또는 이미 클레임됨)에서는 마킹 행만 `PENDING` 으로 커밋되고 배치·VLM 이 전혀 돌지 않는다. 그런데 `LS_MARKING` 을 종결(`VLM_COMPLETED`/`VLM_FAILED`)로 전이시키는 코드는 **VLM 콜백 수신부(`VlmResultService.java:188·224`) 하나뿐**이며(그 외 `src/main` 전수 grep 결과 마킹 상태를 내리는 스윕/만료 잡 **0건**), VLM 위탁은 배치가 돌아야 발생한다. 따라서:
  - 마킹 = `PENDING`(활성) 고착 → `MarkingGuards.requireNoActiveMarking` 이 이후 모든 재마킹을 **409** 로 거부
  - 배치단계는 `MARKING_READY` 로 남아 있어 프리컨디션은 계속 통과 → 사용자는 "마킹은 되는데 아무 일도 안 일어남"이 아니라 "이제 마킹 자체가 안 됨" 으로 악화
  - 실측 고착 인스턴스 2건: rawSn **151**(본 검증 — 검수제출로 작업상태 PENDING, 마킹 sn 21이 `PENDING` 으로 남아 재마킹 409), rawSn **134**(타 에이전트 구동 — 작업상태 `REJECTED`, 마킹 sn 10이 `PENDING` 고착, 03:18 이후 계속). **특히 반려(REJECTED) 후 재마킹 시도는 이 상태를 만드는 정규 동선이다.**
- **재현/확인 경로**:
  ```bash
  # 1) 배정 → 검수제출(작업상태 PENDING) → 마킹
  curl -X POST .../v1/assignments -d '{"workerId":2001,"rawDataIds":[N]}'
  curl -X POST .../v1/reviews/N/submit
  curl -X POST .../v1/videos/N/markings -d '{"mode":"AUTO","intervalFrames":1200}'   # 201 batchTriggered=false
  # 2) 같은 영상 재마킹
  curl -X POST .../v1/videos/N/markings -d '{"mode":"AUTO","intervalFrames":1200}'   # 409 (영구)
  ```
  ```sql
  select m.marking_sn, m.stts_cd, r.data_stts_cd, s.data_stts_cd
    from ls_marking m join ls_data_raw r on r.raw_sn=m.raw_sn
    left join ls_raw_data_status s on s.raw_data_id=m.raw_sn
   where m.stts_cd='PENDING';   -- 실측: 134, 151
  ```
- **영향**: 기능 정지(가용성). 해당 영상은 마킹을 다시 걸 수 없어 배치 재기동 경로가 사라진다(운영 API 중 마킹 상태를 종결시키는 것이 없으므로 DBA 수동 UPDATE 외 복구 수단 없음). DEV_FIX H11 이 "무음 스킵"은 없앴지만 그 대가로 만들어진 활성 마킹의 **회수 경로가 없다** — 이 프로젝트가 반복해 겪은 "차단은 있는데 복구가 없다" 비대칭의 재발 형태다.
- **수정 방향(제안)**: ① 브리지가 배치를 트리거하지 못한 경우 같은 트랜잭션 경계 밖에서 그 마킹을 종결(`VLM_FAILED` 등)로 내려 활성 집합에서 빼거나, ② 아예 배치 미트리거가 확정된 상태(검수 소유)에서는 마킹 생성 자체를 프리컨디션에서 거부하거나, ③ 활성 마킹 만료 스윕(고아 PENDING 회수)을 추가. ⚠ **구현하지 않는다.**


### [C-ISSUE-21] (범위 외 발견 — TC-LABEL-15/16/17 반증 중) 사용자 저장 경로에 **기하 유효성(점 개수·퇴화·순서역전) 검증이 없다**

- **심각도**: MEDIUM (데이터 품질 오염 — 학습데이터/export COCO JSON 로 그대로 전파)
- **기대 동작(기대효과)**: `LBL_TYPE_CD` 가 요구하는 최소 기하 조건을 저장 시점에 강제해야 한다. BBOX/TRACK 은 대각 2점(면적 > 0), POLYGON/SEGMENT 는 정점 ≥ 3. 저장된 라벨은 곧바로 `LabelToAnnotationMapper` 를 거쳐 `bbox=[x,y,w,h]` / `polygon` 으로 export 되므로 폭·높이 0 이나 정점 1개짜리 도형은 학습데이터로 의미가 없다.
- **현재 동작**: `LabelService.validatePoints`(`backend/.../label/service/LabelService.java:706-730`)는 **① 비어있음 ② 각 원소가 `[x,y]` 2튜플인가 ③ 점 개수 상한 1000 ④ 음수** 만 본다. **점 개수 하한도, 타입별 arity 도, 퇴화/순서역전도 검사하지 않는다.**
  ```java
  // LabelService.java:719-729 (발췌)
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) { ... 400 ... }   // 원소 내부만 검사
      double x = pair.get(0); double y = pair.get(1);
      if (x < 0 || y < 0) { ... 400 ... }
  }
  // → points.size() 자체에 대한 하한·타입별 제약 없음
  ```
  실동작(srcSn 105, REVIEWER, 전부 **HTTP 200 저장 성공**):

  | 입력 | 결과 |
  |---|---|
  | `{"lblTypeCd":"BBOX","points":[[1,1]]}` (1점) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[1,1],[2,2],[3,3]]}` (3점) | **200** 저장 |
  | `{"lblTypeCd":"POLYGON","points":[[1,1]]}` (정점 1) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[50,50],[50,50]]}` (면적 0) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[50,50],[50,80]]}` (폭 0) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[500,500],[100,100]]}` (x2<x1 역전) | **200** 저장, DB `[[500.0,500.0],[100.0,100.0]]` 확인 |

  **같은 시스템의 다른 경로는 전부 막는다** — 비대칭이 본 지적의 핵심이다:
  - AI 검출 응답: `DetectionBoxNormalizer:51-53`(개수 ≠ 4 → 400), `:65-67`(clamp 후 `x2<=x1 || y2<=y1` → 해당 검출 스킵)
  - SAM 폴리곤 응답: `AutolabelOnlineService.validatePolygonPoints:600-604`(정점 < 3 → INVALID_INPUT)
  - SKELETON: `LabelService.validateSkeletonPoints:792-825`(정확히 17점, 원소 3, v∈{0,1,2})
  즉 **외부(AI) 입력에는 기하 유효성 규칙이 있는데 사용자 입력에는 없다.**
- **재현/확인 경로**:
  ```bash
  B=http://localhost:18081/api; RT=<REVIEWER 토큰>
  curl -s -o /dev/null -w "%{http_code}\n" -X PUT $B/v1/frames/105/labels \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"rev","points":[[500,500],[100,100]]}]}'
  # → 200
  ```
  ```sql
  select lbl_sn, lbl_type_cd, lbl_nm, point_cn from ls_data_lbl where src_sn = 105;
  -- 411|BBOX|rev|[[500.0,500.0],[100.0,100.0]]
  ```
  (본 시험에서 만든 라벨은 검증 종료 시 `{"items":[]}` 로 전량 삭제 — 잔존 없음)
- **영향**: CWE-20(입력 검증 부재). 보안 침해는 아니고 **데이터 품질**이 리스크다. ①`LabelToAnnotationMapper.toBbox`(`:83-99`)가 min/max 바운딩을 하므로 크래시는 없으나 **`bbox` 의 w/h 가 0** 인 annotation 이 export JSON 에 그대로 실린다(정점 1개 POLYGON 은 `polygon=[x,y]` 2원소로 나간다). ②관제/데이터마트로 나가는 학습데이터셋에 학습 불가 도형이 섞인다. ③FE 캔버스가 이런 도형을 되읽을 때 렌더 이상이 발생할 수 있다(미확인). ④API 직접 호출·FE 버그·타 도구 연동 시 방어선이 전무하다.
- **★3 확정 정책과의 관계 (명시)**: **본 지적은 ★3 의 2축(사용자=400 거부 / AI=clamp)을 통일하자는 요구가 아니다.** ★3 은 *경계(bounds) 초과 좌표를 고칠 것인가 거부할 것인가* 의 축이고, 본 건은 *도형이 도형인가(arity·degeneracy)* 라는 별개 축이다. 좌표값을 **조용히 고치자는 제안이 아니라** 사용자 경로의 기존 스탠스(거부)를 그대로 적용하자는 것이다. 실제로 이 환경의 경계 clamp/거부 동작은 정책대로 정상 작동함을 위 「★좌표 2축 실측」에 별도 기록했다.
- **수정 방향(제안)**: `validatePoints` 에 타입별 최소 조건을 추가 — BBOX/TRACK 은 `points.size() == 2` 이고 `x2 != x1 && y2 != y1`(또는 min/max 로 면적 > 0), POLYGON/SEGMENT 는 `points.size() >= 3`. 위반 시 기존 좌표 검증과 동일하게 400(`INVALID_INPUT`). 기존 라벨(`id != null`)에는 `MAX_POINTS`·bounds 와 동일하게 **좌표가 실제로 바뀔 때만** 강제해 레거시 데이터로 프레임 저장이 영구 차단되는 회귀를 피한다. ⚠ **구현하지 않는다.**


### [C-ISSUE-62] TC-SAM2-23 — SAM2 Track 이 ai-server 응답 오류를 400(클라이언트 귀책)으로 반환하고, 응답 폴리곤 최소 정점 수를 검증하지 않는다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대값은 **502 `EXTERNAL_API_ERROR`** 다. 외부 시스템(ai-server)이 규약 위반 응답을 보낸 것은 **클라이언트 귀책이 아니므로** 400 이 아니라 502 여야 한다(형제 경로 `Sam2SegmentService.validatePolygon:159-175` 은 정확히 `EXTERNAL_API_ERROR` 를 던진다). 또한 폐곡선 폴리곤의 최소 정점 수(3) 검사도 segment 와 동일하게 있어야 한다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2TrackService.java:119`
    ```java
    // 외부 시스템 응답도 신뢰하지 않음 — 동일 좌표 검증.
    validatePolygon(aiRes.polygon(), "ai-server polygon");
    ```
    이 `validatePolygon(:188-204)` 은 **요청 `prevPolygon` 과 공용**이며 전부 `ErrorCode.INVALID_INPUT`(=`HttpStatus.BAD_REQUEST`, `ErrorCode.java:6`)을 던진다 → 외부 응답 오염이 **400** 으로 나간다.
  - 같은 메서드에 **최소 정점 수 검사가 없다**:
    ```java
    private void validatePolygon(List<List<Double>> polygon, String fieldName) {
        if (polygon == null || polygon.isEmpty()) { ... }   // ← null/빈 만 거부
        for (List<Double> pair : polygon) { /* size!=2, null, 비유한, 음수 */ }
    }
    ```
    segment 의 `MIN_POLYGON_POINTS(=3)` 대칭 검사가 부재하므로 ai-server 가 **1~2정점 폴리곤**을 반환해도 그대로 통과해 FE 작업본에 병합된다(요청 `prevPolygon` 은 DTO `@Size(min=3)` 로 막히지만 **응답 경로는 무방비**).
  - 상한(이미지 경계) 검사도 없다 — segment 는 `x > imgWidth` 를 502 로 막지만 track 은 `x < 0` 만 본다. `C-ISSUE-61` 실측에서 `[[9000,9000],…]` 이 그대로 200 으로 나온 것이 그 증거다.
  - 회귀 테스트가 현재 동작을 **기대값으로 고정**하고 있다: `backend/src/test/java/kr/co/cudo/authoring/label/Sam2TrackServiceTest.java:272-285 sam2_track_ai응답폴리곤_검증실패시_400` — 음수 좌표 응답 스텁에 대해 `ErrorCode.INVALID_INPUT` 을 단언. 즉 코드를 502 로 고치면 이 테스트가 깨진다(정책 확정 필요).
- **재현/확인 경로**: 실모델이 오염 폴리곤을 반환하도록 강제할 수 없어 정적·테스트 대조로 확인.
  ```bash
  sed -n '115,120p;188,204p' backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2TrackService.java
  sed -n '1,10p' backend/src/main/java/kr/co/cudo/authoring/common/exception/ErrorCode.java   # INVALID_INPUT = BAD_REQUEST
  sed -n '270,285p' backend/src/test/java/kr/co/cudo/authoring/label/Sam2TrackServiceTest.java
  ```
- **영향**: ①**오류 귀속 왜곡** — FE·운영자가 400 을 보고 "내 요청이 잘못됐다"고 판단해 ai-server 장애를 놓친다(관측성 저하). 동일 실패에서 segment 는 502, track 은 400 으로 갈려 계약이 비일관하다. ②**미검증 정점부족·경계초과 좌표 유입** — 1~2정점 폴리곤은 폐곡선이 아니라 캔버스 렌더·MASK/RLE 변환·트랙 보간에서 하류 오류를 유발하고, 경계 초과 좌표는 저장 단계(`validateWithinBounds`)에서야 400 이 나 작업 손실로 이어진다. ③보안 등급은 낮음 — 응답 메시지 `"ai-server polygon 좌표는 …"` 이 내부 컴포넌트명을 노출하나 호스트·경로는 없다(CWE-209 경미).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `validatePolygon` 을 **요청용/응답용 2개로 분리**해 ①요청 경로는 현행 `INVALID_INPUT`(400) 유지 ②응답 경로는 `EXTERNAL_API_ERROR`(502) + `MIN_POLYGON_POINTS(3)` + 이미지 실측 상한(segment 처럼 `readImageSize` 로 확보) 검사 추가. `Sam2TrackServiceTest:272` 는 502 기대로 갱신. ⚠ 카탈로그 기대값을 코드에 맞춰 400 으로 내리는 선택도 가능하나, 그 경우에도 **정점부족·상한 미검증**은 별개로 남으므로 최소한 그 두 가드는 추가해야 한다.


### [C-ISSUE-63] TC-SAM2-27 인접 — `Sam2SegmentService` 가 예외 원문을 응답 본문에 그대로 실어 내부 ai-server URL·파일 경로를 노출한다 (CWE-209)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/security.md` "System Information Leak (CWE-209)" · `api-design.md` *"스택트레이스, 내부 경로 등 기술 정보 절대 포함 금지"*. 형제 경로 `Sam2TrackService.java:108-114` 는 정확히 이 규약을 지킨다 — 예외 원문은 `LogSanitizer.sanitize` 를 거쳐 **서버 로그로만** 가고 클라이언트에는 `"SAM2 track 호출에 실패했습니다."` 라는 일반화 메시지만 나간다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java` 4곳이 `e.getMessage()` 를 클라이언트 메시지에 직접 연결한다.
  ```java
  :115-118  } catch (Exception e) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 segment 호출 실패: " + e.getMessage());   // ← 내부 URL 노출
            }
  :194      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 크기 확인 실패: " + e.getMessage());
  :207      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
  :217      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
  ```
  실동작 확인(ai-server 500 유발):
  ```
  HTTP=502
  {"success":false,"data":null,
   "message":"SAM2 segment 호출 실패: 500 Internal Server Error from POST http://klid-ai-server:9300/infer/sam2/segment",
   "errorCode":"EXTERNAL_API_ERROR"}
  ```
  → **내부 서비스 호스트명·포트·엔드포인트 경로**가 인증된 WORKER 응답으로 그대로 나간다. `:194/:207/:217` 의 `IOException.getMessage()` 는 통상 **파일 절대경로**(`/app/storage/deidentified/frames/deid/…`)를 포함하므로 스토리지 레이아웃까지 노출된다(CWE-209 + 경로 정보 노출).
  참고: `FrameImageEncoder.encode:190-197` 은 같은 상황에서 `"이미지 읽기에 실패했습니다."` 로 원문을 삼키도록 이미 고쳐져 있다 — `Sam2SegmentService` 만 구 패턴이 남았다.
- **재현/확인 경로**:
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' -d '{"role":"WORKER","channel":"INTERNAL"}' | jq -r .data.token)
  # points 내부 좌표쌍 누락 → ai-server 500 (G-ISSUE-21) → BE 502 + URL 노출
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"points":[[]]}'
  ```
- **영향**: **정보 노출(CWE-209)**. 내부 네트워크 토폴로지(서비스 DNS 명 `klid-ai-server`, 포트 9300, 추론 엔드포인트 경로)와 스토리지 절대경로가 애플리케이션 응답으로 유출된다. 저작도구는 관제/포털에서 토큰을 인계받는 다중 채널 앱이라 내부 주소 노출은 후속 SSRF·측면 이동의 정찰 정보가 된다. 즉시 착취 가능한 취약점은 아니나 Fortify/CodeQL 의 *System Information Leak* 룰에 직접 걸리는 패턴이다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 4곳 모두 `Sam2TrackService.java:108-114` 패턴으로 통일 — `log.error(..., LogSanitizer.sanitize(e.getMessage()))` 로 서버 로그에만 남기고, `CustomException` 메시지는 `"SAM2 분할 호출에 실패했습니다."` / `"이미지를 읽을 수 없습니다."` 같은 일반화 문구로 교체(원인 예외는 `CustomException(code, msg, e)` 3-arg 로 체인). 회귀 가드로 "응답 message 에 `http://`·`/app/` 문자열이 없다"는 단언을 `Sam2SegmentServiceTest#aiServerTimeoutMapped` 에 추가.


### [C-ISSUE-84] TC-TRACK-31 — CVAT 포팅 유틸 5개 파일이 **전부 dead code**(1차 C-ISSUE-82 미해소 + 범위 확대)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` "CVAT 포팅 전략" 이 Phase 6 산출물로 명시한 **MASK↔RLE↔Polygon 변환**(portable-modules/02)과 **좌표 변환/회전 유틸**(portable-modules/06)은 제품 코드에서 실제로 쓰여야 한다. 또한 보간 진실원은 1개여야 한다.
- **현재 동작(이슈 내용)**: `backend/src/main` 전수 grep 결과, 아래 5개 파일은 **프로덕션 참조가 0건**이다(자기 파일 제외).

  | 파일 | 프로덕션 참조 | 비고 |
  |---|:--:|---|
  | `common/util/TrackInterpolator.java` | 0 | 1차 C-ISSUE-82 그대로. `batch/interpolation/TrackInterpolator`(POLYGON 완전 지원)와 **동명이인**이고 이쪽은 POLYGON 이 `UnsupportedOperationException`(`:37-40`) |
  | `common/util/MaskRleConverter.java` | 0 | portable-modules/02 포팅물 |
  | `common/util/CoordinateTransformer.java` | 0 | portable-modules/06 포팅물 |
  | `common/util/ShapeType.java` | 0 | dead `TrackInterpolator` 전용 |
  | `common/util/Keyframe.java` | 0 | dead `TrackInterpolator` 전용 (`batch/interpolation/Keyframe` 이 실사용본) |

  ```bash
  grep -rn "MaskRleConverter"     backend/src/main | grep -v util/MaskRleConverter.java      # 0건
  grep -rn "CoordinateTransformer" backend/src/main | grep -v util/CoordinateTransformer.java # 0건
  grep -rn "TrackInterpolator"    backend/src/main | grep -v "util/TrackInterpolator.java"
  #  → batch/step/TrackInterpolationStep.java:13,72 (batch.interpolation 쪽) 과 주석뿐
  ```
  이 5개 파일에 대응하는 단위 테스트는 **31건 존재하며 전부 GREEN** 이다(`TrackInterpolatorTest` 7 · `MaskRleConverterTest` 12 · `CoordinateTransformerTest` 10 등). 즉 **테스트 통계가 "구현·검증 완료" 처럼 보이지만 제품에는 배선되지 않았다.**
  또 TC-TRACK-31 이 요구하는 "보간 진실원 단일 확인"의 **자동 구조 단언 테스트가 없어**, 누군가 dead 쪽을 import 해도 CI 가 막지 못한다.
- **재현/확인 경로**: 위 grep 3종. 추가로 `grep -rn "common.util.ShapeType\|common.util.Keyframe" backend/src/main` → 0건.
- **영향**: ①요구 기능(RLE 변환·좌표 회전) **미배선** — SAM2 mask 산출물의 RLE 직렬화·회전 라벨 처리가 필요해지는 시점에 "이미 있다"고 오판할 위험 ②동명이인 클래스로 인한 **오import 위험**(POLYGON 미지원 쪽을 import 하면 런타임 `UnsupportedOperationException`) ③테스트 수·커버리지 지표 왜곡 ④유지보수 비용(사문 코드 5파일 + 테스트 31건).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①`MaskRleConverter`·`CoordinateTransformer` 는 **배선 계획을 확정**(어느 경로에서 쓸지)하거나, 계획이 없으면 대응 테스트와 함께 삭제 ②`common/util/{TrackInterpolator,ShapeType,Keyframe}` 는 삭제(진실원은 `batch/interpolation`) ③삭제 대신 존치한다면 **구조 단언 테스트**(클래스패스 스캔으로 `common.util.TrackInterpolator` import 0건 단언)를 추가해 TC-TRACK-31 을 자동화.

---


### [C-ISSUE-85] TC-PRESET-15 — 마스터 soft delete 후 프리셋 코드가 **식별정보를 전부 잃는다**(`labelName`·`code` 모두 null, `string[]` 에 null 원소)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 라벨 프리셋 절 — *"마스터에 매칭 안 되는 기존 코드(labelId null/비활성)는 오류 없이 **'미연결'**로 표시(자동 생성/삭제 없음)"*. FE 도 이를 전제로 미연결 칩에 *"legacy 라벨명 + '미연결' 배지(마스터에서 **재선택 유도**)"* 를 렌더한다(`frontend/src/features/preset/components/PresetCodeChip.tsx:18`). 재선택을 유도하려면 **어떤 라벨이었는지 보여야** 한다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/preset/service/PresetService.java:239-245`

  ```java
  private static PresetCodeView toCodeView(LsLabelPresetCode code, Map<Long, LabelMasterResponse> masters) {
      LabelMasterResponse master = code.getLabelId() == null ? null : masters.get(code.getLabelId());
      boolean linked = master != null;
      if (!linked) {
          // 미연결 — 오류 없이 legacy 코드로 노출.
          return new PresetCodeView(code.getLabelId(), code.getCode(), code.getCode(), null, false, false, false);
          //                                            ^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^ labelName 도 code 를 재사용
  ```
  그런데 **labelId 기반으로 생성된 행은 `LBL_CD` 를 저장하지 않는다**(`toSpecs:164-172` 가 `new LabelCodeSpec(id, null)`, DB 실측 `lbl_cd` NULL). 따라서 그 마스터가 나중에 soft delete 되면 `code`·`labelName` **둘 다 null** 이 된다.

  실측(마스터 41 을 `DELETE /v1/manage/labels/41` 후 프리셋 재조회):
  ```json
  "labelCodes": ["ZZTEST-C5A-RENAMED", null],
  "labelCodeOptions": [ {...}, {"labelId":41,"code":null,"labelName":null,"labelType":null,
                                "linked":false,"bboxEnabled":false,"polygonEnabled":false} ]
  ```
  FE 는 `<span>{code.labelName}</span>` 를 그대로 렌더하므로(`PresetCodeChip.tsx:31`) **이름 없는 '미연결' 칩**이 뜬다 — 운영자는 어떤 라벨을 재선택해야 하는지 알 수 없다. 또한 `labelCodes` 는 FE 타입 선언상 `string[]`(`frontend/src/features/preset/api.ts:33`)인데 **null 원소**가 실려 계약이 깨진다.
  참고: `labelId=null` 인 순수 레거시 행은 `lbl_cd` 가 있어 정상 표시된다(TC-PRESET-14 실측 `'ZZ-LEGACY-CODE'`) — **문제는 "연결됐다가 끊긴" 행에 한정**된다.
- **재현/확인 경로**:
  ```bash
  RT=<REVIEWER 토큰>
  curl -s -X POST http://localhost:18081/api/v1/manage/labels -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"name":"TMP-X","color":"#AABBCC","type":"BBOX","sortNo":900}'   # → labelId
  curl -s -X POST http://localhost:18081/api/v1/manage/presets -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"name":"TMP-P","labelIds":[<labelId>]}'
  curl -s -X DELETE http://localhost:18081/api/v1/manage/labels/<labelId> -H "Authorization: Bearer $RT"
  curl -s http://localhost:18081/api/v1/manage/presets -H "Authorization: Bearer $RT"
  # → labelCodes:[null], labelCodeOptions[0].labelName:null
  ```
- **영향**: 운영 복구 불능(어떤 라벨이 끊겼는지 화면·응답 어디에도 없음). `labelCodes: string[]` 계약 위반으로 FE 가 문자열 메서드를 호출하면 런타임 오류 가능(현재 `PresetCodeChip` 은 텍스트 렌더만 해서 빈칸으로 끝남). 보안 영향 없음.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①`toCodeView` 의 미연결 분기에서 `labelName` 폴백을 두어(`code != null ? code : "미연결 라벨 #" + labelId`) 최소한 labelId 를 노출 ②또는 `labelId` 연결 행에도 마스터 라벨명 스냅샷을 **표시 전용 폴백 컬럼**으로 남길지 정책 결정(단일 진실원 원칙과 상충하므로 "조회 실패 시 폴백" 용도로만) ③`labelCodes` 는 null 원소를 제외하거나 필드 자체를 deprecate(이미 `labelCodeOptions` 가 상위 호환) ④FE `PresetCodeChip` 에 이름 미상 시 `labelId` 표기 추가.

---


### [C-ISSUE-02] TC-MARK-11 — 29.97fps(분수 fps) 마킹 계산의 회귀 테스트가 여전히 없다 (1차 B-ISSUE-27 이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: 실제 CCTV 자산의 표준 프레임레이트인 29.97(30000/1001)에서 `totalFrames = Math.round(dur×fps)` 와 타임스탬프 절단이 정확한지 자동 회귀로 고정돼야 한다. 이 값이 틀어지면 마킹↔프레임추출 정렬(`seekMillis`)이 통째로 어긋난다.
- **현재 동작(이슈 내용)**: 실동작은 정상이다 — rawSn 153(dur=113s, `video.fps=29.97002997002997`)에 `intervalFrames=1129` → marks `0/1129/2258`. fps 30 가정이면 `totalFrames=3390` 이라 3387 이 포함됐어야 하는데 포함되지 않았으므로 **29.97 기반 3387 이 실제로 쓰였음**이 확정된다.
  그러나 `backend/src/test` 전수 grep 결과 **마킹 계산 경로에 29.97 을 쓰는 테스트는 0건**이다. 존재하는 것은 `VideoFpsResolverTest:52 "분수fps_29.97_정확파싱"`(리졸버 파싱), `VideoMetaServiceTest`(메타 적재), `BrampVideoProbeTest`(프로브 파싱)뿐이고, `MarkingServiceTest` 가 커버하는 fps 는 **25 / 30(폴백) / 60** 세 가지다.
- **재현/확인 경로**:
  ```bash
  grep -rn "29.97" backend/src/test/java/kr/co/cudo/authoring/marking/   # → 0건
  grep -n "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/marking/MarkingServiceTest.java | grep -i fps
  ```
- **영향**: 정수 fps(25/30/60)만 고정돼 있어, `Math.round` 를 다른 반올림/절단으로 바꾸는 리팩터링이 **정수 fps 테스트는 전부 통과시키면서** 분수 fps에서만 1프레임 어긋나는 회귀를 통과시킬 수 있다. 실 자산이 29.97 이므로 운영 영향이 가장 큰 구간이 무방비다.
- **수정 방향(제안)**: `MarkingServiceTest` 에 `fps=29.97002997002997 · dur=113 → totalFrames=3387`(끝경계 3387 미포함) 및 대표 타임스탬프 절단 단언을 추가. ⚠ **구현하지 않는다.**


### [C-ISSUE-04] (이월 재확인) B-ISSUE-23 — AUTO `intervalFrames` 상한 미검증이 2차에도 그대로다
- **심각도**: LOW
- **기대 동작(기대효과)**: `intervalFrames` 가 `totalFrames` 를 초과하면 자동 마킹이 의미를 잃으므로 400 으로 거부하거나 최소 marks 수를 보장한다(1차 기대값 유지).
- **현재 동작(이슈 내용)**: `MarkingService.java:196-198` 은 여전히 하한(`null || <= 0`)만 본다. 실측(rawSn 154, dur=113s·fps 29.97 → totalFrames 3387):
  ```
  POST /v1/videos/154/markings {"mode":"AUTO","intervalFrames":2147483647}
  → 201 {"marks":[{"frameIndex":0,"timestamp":"00:00"}], "batchTriggered":true}
  ```
  후속 배치가 정상 완주하여 `LS_DATA_SRC(154)=1`(프레임 1장), `LS_DATA_RAW(154)=COMPLETED`. 즉 **학습데이터가 1프레임만 생성됐는데 상태는 정상 완료**다. 타 에이전트 구동분(rawSn 137, `FRME_INTV_NOCS=999999999`, marks 1건)도 동일 형태로 관측된다.
- **재현/확인 경로**: 위 curl + `select frme_intv_nocs, mark_cn from ls_marking where raw_sn=154;` / `select count(*) from ls_data_src where raw_sn=154;`
- **영향**: 1차 판단과 동일(오조작 시 1프레임 학습데이터가 정상 완료로 흐름). `durationSec<=0` 은 backstop 으로 막으면서 반대편 경계는 무방비인 비대칭도 그대로다. 케이스 소관은 B 클러스터 `TC-BATCH-096`("현재 동작 고정")이므로 C-1 판정에는 반영하지 않고 **이월 이슈 대조 목적**으로만 기록한다.
- **수정 방향(제안)**: `intervalFrames < totalFrames` 검증 또는 산출 marks 가 1건이면 경고/거부. ⚠ **구현하지 않는다.**

---


### [C-ISSUE-22] (이월 확인) TC-LABEL-30 — R7 손상 `pointCn` fail-safe 전용 회귀 테스트 여전히 부재

- **심각도**: LOW (동작 정상 — 회귀 위험만)
- **기대 동작(기대효과)**: `pointCn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 '변경됨'으로 판정해 이력이 유실되지 않아야 하고, 그 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작**: 구현은 정상이다 — `LabelService.normalizePoints:568-585` 가 3포맷 + raw 폴백 모두 실패하면 `null` 반환, `pointsEqual:556-559` 가 `false`(변경됨)로 fail-safe. **실동작으로 확증**했다(`pointCn='not-a-json{{'` 라벨 재저장 → `LS_DATA_LBL_HSTRY` UPDATED 1건, `before.pointCn="not-a-json{{"`). 그러나 테스트 자산은 1차와 동일하게 없다: `grep -rn "pointsEqual\|normalizePoints" backend/src/test` = **0건**. 유사명 `LabelServiceFullReplaceIntegrationTest`「손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다」는 **이력 diff JSON(`CHG_DTL_CN`)** 손상 케이스로 대상이 다르다.
- **재현/확인 경로**:
  ```bash
  grep -rn "pointsEqual\|normalizePoints" backend/src/test   # 0건
  ```
  ```sql
  INSERT INTO ls_data_lbl(src_sn,lbl_type_cd,lbl_nm,point_cn,reg_user_no,reg_dt,lbl_id)
    VALUES (105,'BBOX','corrupt','not-a-json{{',1001,now(),NULL);
  -- 이후 PUT /v1/frames/105/labels 로 정상 좌표 재전송 → UPDATED 이력 생성 확인
  ```
- **영향**: 현재 결함 아님. 향후 `normalizePoints` 리팩터가 손상값을 '무변경'으로 오판하면 이력이 조용히 유실되는데 이를 잡을 테스트가 없다.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `pointCn` 을 손상값(`"{{{"`)으로 덮어쓴 뒤 무변경 재저장 시 UPDATED 이력이 남는지 확인하는 케이스 1건 추가. ⚠ **구현하지 않는다.**

---


### [C-ISSUE-41] TC-LABEL-143 — `/v1/frames/{srcSn}/image` 의 `PORTAL_USER` 허용은 도달 불가한 dead grant (케이스 기대값 "통과" 불성립)

> ⚠ 번호는 임무 지시(part3 = 41번부터)를 따른 것이며, **1차 ISSUES.md 의 `C-ISSUE-41`(YOLO 좌표 clamp — 이미 해소)과는 다른 이슈**다.

- **심각도**: LOW (보안 위험 없음 — 오히려 더 좁게 닫혀 있음. 계약·문서 정합 문제)
- **기대 동작(기대효과)**: 카탈로그 TC-LABEL-143 은 "두 형제 경로의 역할 집합이 **의도적으로 다름**" 을 검증 대상으로 삼아 `/deid-image` = 403 / `/image` = **통과**를 기대한다.
- **현재 동작**: PORTAL 채널 토큰으로 두 경로 모두 **403**(`{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`).
  - `FrameImageController.java:80` 은 실제로 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER','PORTAL_USER')")` 이나,
  - `SecurityConfig.java:147-153` 이 그보다 앞서 `/v1/**` 에 대해 `CHANNEL_INTERNAL ∧ (ROLE_REVIEWER ∨ ROLE_WORKER ∨ STREAM_SIGNED)` 를 요구한다. PORTAL 토큰은 `CHANNEL_PORTAL` 이라 **컨트롤러에 도달하지 못한다**.
  - 설령 채널 검사를 통과하더라도 `LabelAccessGuard.verifyAndGet:59-71` 이 REVIEWER/WORKER 외 역할을 `FORBIDDEN` 으로 끝낸다(2중 차단).
  - 즉 `/image` 의 `PORTAL_USER` 항목은 **어떤 경로로도 실행되지 않는 dead grant** 이며, 두 형제 경로의 관측 가능한 역할 집합은 **동일**하다.
- **재현/확인 경로**:
  ```bash
  PT=$(curl -s -X POST $B/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $PT" $B/v1/frames/66/image      # 403
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $PT" $B/v1/frames/66/deid-image # 403
  ```
  테스트 자산에도 `/deid-image` 의 PORTAL 403 케이스(`FrameDeidImageControllerTest DeidImage_PORTAL_USER_채널은_내부_프레임_접근_403`)만 있고 `/image` 의 PORTAL 통과를 단언하는 테스트는 **0건**이다.
- **영향**: 보안상 위험은 없다(포털은 ADR-013 대로 `/v1/portal/**` 전용 경로를 쓰며 실제로 닫혀 있다). 다만 ①`@PreAuthorize` 가 실제 정책보다 넓게 적혀 있어 **읽는 사람이 포털 접근을 허용된 것으로 오해**할 수 있고 ②채널 격리가 나중에 완화되면 그 순간 내부 프레임이 포털에 열린다(잠재 fail-open 표면) ③카탈로그 기대값이 영구히 성립하지 않는다.
- **수정 방향(제안)**: (a) `FrameImageController:80` 의 `@PreAuthorize` 에서 `PORTAL_USER` 를 제거해 `/deid-image` 와 동일하게 맞추고 (b) TC-LABEL-143 의 기대결과를 "두 경로 모두 PORTAL 403 — 포털은 `/v1/portal/**` 전용" 으로 정정. ⚠ **구현하지 않는다.**


### [C-ISSUE-42] TC-LABEL-149 — `GET /v1/videos/{rawSn}/frames/{frameNo}/image` 의 412(신고 게이트)가 OpenAPI 에 선언되어 있지 않다

- **심각도**: LOW (문서·계약 갭. 런타임 동작은 정상)
- **기대 동작(기대효과)**: 형제 경로(`FrameImageController` 의 `/image`·`/deid-image`)와 동일하게, 비식별 누락 신고 구간에서 반환되는 **412** 가 API 문서(@ApiResponses)에 선언되어 FE·관제가 분기를 인지할 수 있어야 한다.
- **현재 동작**: `VideoController.java:279-284` 의 `@ApiResponses` 는 200/401/403/404 만 선언한다. 그러나 실제로는 신고 구간에서 **412** 를 반환한다(실측):
  ```
  GET /v1/videos/133/frames/1/image  (WORKER, rawSn 133 = DE_IDENT_YN 'F')
  → 412 {"errorCode":"PRECONDITION_FAILED","message":"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."}
  ```
  같은 게이트를 쓰는 `FrameImageController` 는 `/image`(:77)·`/deid-image`(:113) 양쪽에 412 를 선언하고 있어 **형제 경로 간 문서 규약만 어긋난 상태**다.
- **재현/확인 경로**: 위 curl. 또는 `/swagger-ui` 에서 두 컨트롤러의 응답 코드 목록 비교.
- **영향**: FE/외부 소비자가 412 를 미문서화 오류로 취급해 일반 에러 처리로 흘릴 수 있다(신고 구간 전용 안내 UI 분기 누락). 보안 영향 없음.
- **수정 방향(제안)**: `VideoController.getFrameImage` 의 `@ApiResponses` 에 412 항목 추가(문구는 `FrameImageController:77` 과 동일하게). 부수적으로 같은 블록의 404 설명("비식별 미완료")도 412 와 구분되도록 정리. ⚠ **구현하지 않는다.**

---


### [C-ISSUE-64] TC-SAM2-06 — `Sam2SegmentService.resolveSafe` 는 호출부 0건의 dead code이며 카탈로그가 이를 경로순회 방어 근거로 인용하고 있다

- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 가드로 문서화·인용되는 코드는 실제 실행 경로에 있어야 한다. 그렇지 않으면 다음 검증자·유지보수자가 "가드가 있다"고 믿고 실제 방어선을 점검하지 않는다(1차·2차 모두 이 라인을 근거로 PASS 판정했다).
- **현재 동작(이슈 내용)**: `Sam2SegmentService.java:179-188` 의 `private Path resolveSafe(Path baseDir, String relativePath)` 는 **어디서도 호출되지 않는다**. 클래스 javadoc `:52` 도 여전히 *"경로 순회(CWE-22): `{@link #resolveSafe}` 로 기준 디렉토리 외부 접근 차단"* 이라고 기술한다. 실제 경로 해석은 `:96` 에서 `frameImageEncoder.resolveFrameImageForInference(src)` 로 위임되며, 진짜 가드는 `FrameImageEncoder.resolveSafe:220-229`(lexical) 와 `StorageSubtreePolicy.verifyDeidentifiedFile:181-193`(**`toRealPath()` 실경로 서브트리 판정 — 심링크 우회 차단**)이다.
  ```bash
  grep -n "resolveSafe" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java
  # 52: *   <li>경로 순회(CWE-22): {@link #resolveSafe} 로 ...   ← javadoc
  # 179:    private Path resolveSafe(Path baseDir, String relativePath) {   ← 선언만
  # (호출 0건)
  ```
- **재현/확인 경로**: 위 grep. 방어 자체는 유효함을 별도 확인 — 프레임 경로는 DB 컬럼 유래라 사용자 입력이 닿는 표면이 없고, `FrameImageEncoder`/`StorageSubtreePolicy` 이중 가드가 실행 경로에 있다.
- **영향**: **기능·보안 영향 없음**(경로순회는 실제로 차단됨). 순수 유지보수/추적성 문제 — 검증 문서의 `file:line` 근거가 무효 코드를 가리켜 **감사 추적이 허위 안전감을 준다**. `C-ISSUE-82`(동명이인 dead `TrackInterpolator`)와 같은 계열.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** dead `resolveSafe` 제거 + 클래스 javadoc `:52` 를 실제 가드(`FrameImageEncoder`/`StorageSubtreePolicy`) 위임으로 갱신. 카탈로그 TC-SAM2-06 근거도 `FrameImageEncoder.java:220-229 · StorageSubtreePolicy.java:181-193` 으로 정정.


### [C-ISSUE-65] TC-SAM2-01/14 — SAM2 프롬프트 입력좌표의 이미지 경계 사전검증 부재 (1차 C-ISSUE-61 이월·미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 응답 폴리곤은 이미지 실측 상한까지 검증하면서(`Sam2SegmentService.validatePolygon:171`) 요청 프롬프트는 무검증인 것은 비대칭이다. 최소한 명백히 무의미한 좌표(이미지 폭·높이의 수십 배)는 외부 ai-server 로 보내기 전에 400 으로 끊는 것이 자원·계약상 합리적이다. 서비스는 이미 `:104-106` 에서 실측 `imgWidth/imgHeight` 를 확보하고 있어 추가 I/O 없이 검사 가능하다.
- **현재 동작(이슈 내용)**: `Sam2SegmentRequest.points/box`(`:33-34` — 개수 상한만) 과 `Sam2TrackRequest.prevPolygon`(`:24` — 개수 상한만) 어디에도 좌표 상한 검증이 없다. 실측:
  - `{"srcSn":77,"points":[[99999,99999]]}` → **200**, polygon 981정점 / score 0.1984 (프레임 실측 1920×1080)
  - `{"srcSn":77,"points":[[-500,-500]]}` → **200**, 정상 형태 폴리곤
  - track `prevPolygon:[[9000,9000],[9001,9000],[9001,9001]]` → **200**(음수만 `Sam2TrackService.validatePolygon:199` 로 차단, 상한은 무검증)
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"points":[[99999,99999]]}'   # → 200
  ```
- **영향**: 보안 취약점 아님(응답은 여전히 사후 검증되고 경로순회·주입 표면 없음). ①낭비성 외부 추론 호출(실모델 CPU 추론 1회 ≈ 수백 ms) ②`C-ISSUE-61` 과 결합 시 악화 — 이미지 밖 프롬프트는 mask 실패 → mock 폴백 확률을 높이고, track 은 그 결과를 무표식으로 자동 병합한다. 1차 대비 **상태 변화 없음**.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `Sam2SegmentService` 는 `:104-106` 직후에, `Sam2TrackService` 는 시작 프레임 이미지 치수를 확보한 뒤 `0 ≤ x ≤ imgWidth`, `0 ≤ y ≤ imgHeight` 사전 400 검증 추가 검토. ⚠ 좌표 2축 정책(★3)상 이는 **사용자 입력 경로**이므로 clamp 가 아니라 **400 거부**가 맞다.


### [C-ISSUE-66] TC-SAM2-08 인접 — 이미지 상한이 압축 바이트만 검사하고 디코딩 픽셀 수 상한이 없으며, 내부 채널 SAM2 엔드포인트에는 rate limit 이 없다

- **심각도**: LOW
- **기대 동작(기대효과)**: `rules/security.md` "Unrestricted Resource Consumption(OWASP API4:2023)" — 요청 크기 제한만으로는 부족하고 **디코딩 후 자원 사용량**까지 통제돼야 한다.
- **현재 동작(이슈 내용)**:
  1. **압축 크기만 검사** — `Sam2SegmentService.java:98-102` 는 `Files.size()`(압축 바이트)를 `maxImageBytes`(기본 20MB)와 비교한 뒤, `:199-209 readImageSize` 에서 `ImageIO.read(imagePath.toFile())` 로 **이미지를 전량 디코딩**한다. JPEG/PNG 압축비를 감안하면 20MB 파일이 수십억 픽셀로 전개될 수 있고(디컴프레션 밤, CWE-409), `BufferedImage` 는 픽셀당 3~4바이트를 힙에 잡는다. 픽셀 치수·총 픽셀 수 상한이 어디에도 없다. (`ImageIO.setUseCache`/`ImageReader` 헤더-only 치수 조회 미사용 — `ImageIO.read` 는 전체 래스터를 읽는다.)
  2. **내부 채널 rate limit 부재** — `POST /v1/frames/{srcSn}/sam2-segment` · `sam2-track` 에는 RateLimiter 배선이 없다(`grep -rn "RateLimit" label/controller/LabelController.java` → 0건). 포털 경로는 `PortalSam2Service`·`PortalUploadController` 가 per-user Resilience4j RateLimiter 를 보유해 대칭이 깨져 있다. track 은 1요청당 최대 **50회 순차 실모델 추론**을 유발하므로(TC-SAM2-16 실측 50개 완주) 인증된 계정 하나로 ai-server CPU 를 장시간 점유시킬 수 있다.
- **재현/확인 경로**:
  ```bash
  grep -n "maxImageBytes\|ImageIO.read" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java
  grep -rn "RateLimit" backend/src/main/java/kr/co/cudo/authoring/label/controller/LabelController.java   # 0건
  grep -rn "RateLimit" backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalSam2Service.java   # 존재
  ```
- **영향**: **가용성(DoS)**, 착취 난도 높음. ①디컴프레션 밤은 프레임 이미지가 **내부 ffmpeg 추출물**(경로도 DB 유래)이라 공격자가 임의 파일을 심을 표면이 사실상 없어 현실 위험은 낮다 — 다만 외부 비식별 솔루션(KPST)이 산출한 비식별 프레임도 같은 경로를 타므로 "외부 산출물을 무조건 디코딩한다"는 신뢰 가정이 남는다. ②rate limit 부재는 내부 인증 사용자(WORKER/REVIEWER) 한정이라 위협 모델상 낮으나, ai-server 는 배치 오토라벨과 GPU/CPU 를 공유하므로 배치 지연으로 번질 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`ImageIO.getImageReaders` 로 **헤더만 읽어 치수를 얻고**(전체 디코딩 없이) `width*height` 상한(예: 8K = 33M px)을 먼저 검사한 뒤 필요할 때만 디코딩 ②내부 SAM2 엔드포인트에도 `PortalSam2Service` 와 동일한 per-user Resilience4j RateLimiter 적용 검토(임계값은 운영 판단). ⚠ 실제 도입 전 "내부 작업자 편의 저하 vs 자원 보호" 트레이드오프를 사용자와 확정할 것.

---


### [C-ISSUE-83] TC-TRACK-16 — `maskToRle` 가 0폭 행 mask 에서 `IllegalArgumentException` 이 아니라 `ArrayIndexOutOfBoundsException`

- **심각도**: LOW
- **기대 동작(기대효과)**: "빈 mask" 입력은 예외 없이 `int[0]` 을 돌려주거나, 최소한 이 클래스의 다른 방어 규약과 동일하게 **`IllegalArgumentException`(명시 예외)** 이어야 한다. `common.util` 규약상 이 유틸은 IAE 만 던지도록 설계돼 있다(`DetectionBoxNormalizer` 주석 참조).
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/common/util/MaskRleConverter.java:30-59`

  ```java
  public static int[] maskToRle(boolean[][] mask) {
      if (mask == null || mask.length == 0) { return new int[0]; }   // :31-33  ← 0폭 행은 걸리지 않음
      int h = mask.length;  int w = mask[0].length;                   // h=1, w=0
      long total = (long) h * w;                                      // 0
      ...
      boolean[] flat = new boolean[(int) total];                      // length 0
      ...
      if (flat[0]) {                                                  // :53  ← AIOOBE
  ```
  `mask = new boolean[1][0]`(또는 `new boolean[3][0]`)이면 `mask.length != 0` 이라 조기 반환 가드를 통과하고, `total=0` 이라 DoS 가드(`:37-39`)와 직사각형 가드(`:44-47`)도 통과한 뒤 `:53` 의 `flat[0]` 에서 `ArrayIndexOutOfBoundsException` 이 난다.
  `MaskRleConverterTest` 에 이 입력 형태의 테스트가 없어(빈 mask 테스트는 `new boolean[5][5]` = 값이 모두 false 인 5×5) 회귀로 잡히지 않는다.
- **재현/확인 경로**: `MaskRleConverter.maskToRle(new boolean[1][0])` → `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0`. (정적 판독 — 이 유틸은 프로덕션 호출부가 없어 API 로는 재현 불가, `C-ISSUE-84` 참조.)
- **영향**: 현재 프로덕션 도달 경로가 **없어** 실피해 0. 다만 `C-ISSUE-84` 를 해소해 이 유틸을 실제로 배선하는 순간, 0폭 mask(모델이 폭 0 박스를 낸 경우 등)에서 예외 종류가 규약과 달라 상위 핸들러(IAE→400 매핑)를 빠져나가 **500** 이 된다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `:31-33` 가드를 `mask == null || mask.length == 0 || mask[0].length == 0` 로 확장하거나, `:53` 진입 전에 `if (total == 0) return new int[0];` 를 둔다. 대응 테스트(`maskToRle(new boolean[1][0])`) 추가.

---


### [C-ISSUE-86] TC-PRESET-06/09(인접) — `DataIntegrityViolationException` 을 **무조건 "이미 다른 프리셋에 매핑된 이벤트입니다"** 로 변환

- **심각도**: LOW
- **기대 동작(기대효과)**: 409 응답 메시지는 실제로 위반된 제약을 반영해야 한다(이름 중복이면 이름 중복, 이벤트 중복이면 이벤트 중복).
- **현재 동작(이슈 내용)**: `preset/service/PresetService.java:174-181` · `:88-93`

  ```java
  private LsLabelPreset saveWithEventUniqueGuard(LsLabelPreset preset) {
      try { return presetRepository.saveAndFlush(preset); }
      catch (DataIntegrityViolationException e) {
          throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);   // = "이미 다른 프리셋에 매핑된 이벤트입니다"
      }
  }
  ```
  `ls_label_preset` 에는 유니크 제약이 **2개**(`uk_ls_label_preset_evnt(evnt_type_cd)`, `uk_ls_label_preset_name(preset_nm)`)이고 `ls_label_preset_code` 에도 2개(`uk_ls_label_preset_code(preset_id,lbl_cd)`, `uk_ls_label_preset_code_lblid`)가 있는데, 어느 것이 깨져도 같은 문구가 나간다. 이름 중복은 `existsByPresetNm`(`:65-67`, `:81-83`)이 1선에서 잡지만 **그 검사와 insert 사이의 동시 요청(TOCTOU)** 은 DB 제약으로 떨어져 "이벤트" 메시지를 받는다. 운영자는 이름이 아니라 이벤트를 고치려다 계속 실패한다.
  ※ labelId 중복은 애그리거트가 dedup 하므로(§프리셋 단일 진실원 6번) API 경로로는 이 분기에 도달하지 않는다.
- **재현/확인 경로**: 동일 `name` 으로 `POST /v1/manage/presets` 를 동시 2건 발사(단일 요청 순차 실행에서는 `existsByPresetNm` 이 선행해 올바른 문구가 나온다 — 본 회차 실측 409 "이미 사용 중인 프리셋 이름입니다."). 정적으로는 위 catch 블록이 제약명을 보지 않음이 근거.
- **영향**: 오해를 유발하는 오류 메시지(사용성). 데이터 정합성·보안 영향 없음. 응답 코드(409)는 어느 경우든 옳다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `DataIntegrityViolationException` 의 root cause(`ConstraintViolationException#getConstraintName`)를 읽어 `uk_ls_label_preset_evnt` / `uk_ls_label_preset_name` 을 분기하고, 미상이면 중립 문구("프리셋 저장 중 제약 조건 위반")로 폴백한다. 제약명은 내부 스키마 정보이므로 **응답에 노출하지 말 것**(CWE-209).


## D 클러스터 — 13건

### [D-ISSUE-01] TC-REVIEW-028(부가) — `GET /v1/reviews/{videoId}/frames` 가 비식별 신고 게이트를 우회해 라벨 본문을 벌크 노출

- **심각도**: **HIGH** (CWE-359 민감정보 노출 · 확정 정책 위반)
- **기대 동작(기대효과)**: `CLAUDE.md` — *"신고 구간 동안 **라벨 조회를 차단**한다(`DE_IDENT_YN='F'` 인 영상의 `GET /v1/frames/{srcSn}/labels` → 412, 역할 무관). **라벨 좌표가 PII 위치 특정 정보이므로 스트리밍만 막는 것으로는 부족**"*. 판정 원천은 `DeidentReportGate`(자기 rawSn 행 하나 — ★1 확정 정책 범위 그대로). 즉 **같은 영상의 라벨 좌표를 내보내는 모든 경로**가 412 여야 한다.
- **현재 동작(이슈 내용)**: `ReviewService.listFrames`(`review/service/ReviewService.java:335-372`)는 `requireReviewer(actor)` + 영상 존재 확인만 하고 **`DeidentReportGate` 를 호출하지 않는다.** `ReviewService` 전체에 `DeidentReportGate` import·주입이 **0건**(`grep -rn "DeidentReportGate" backend/src/main/java/kr/co/cudo/authoring` 결과에 `review/` 패키지 부재).
  ```java
  // ReviewService.java:335-349 (발췌)
  public FrameListResponse listFrames(Long videoId, TokenClaims actor) {
      requireReviewer(actor);
      videoRepository.findById(videoId)
              .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
      List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(videoId);
      ...
      List<LsDataLbl> allLabels = labelRepository.findBySrcSnIn(srcSns);   // ← 게이트 없이 라벨 본문 조회
  ```
  **실동작 대조 (rawSn=133, `LS_DATA_RAW.DE_IDENT_YN='F'`, `LS_DEIDENT_REPORT` OPEN)**:

  | 경로 | 결과 |
  |---|---|
  | `GET /v1/frames/78/labels` | **412** `비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다` |
  | `GET /v1/frames/80/labels` · `/82/labels` | **412** (동일) |
  | `GET /v1/frames/78/image` | **412** |
  | `GET /v1/videos/133/frames/0/image` | **412** |
  | **`GET /v1/reviews/133/frames`** | **200** — 6프레임 · **라벨 28건 전량**(`(78,0) (80,10) (82,4) (84,3) (86,9) (88,2)`) |

  반환 본문에 좌표가 그대로 실린다:
  ```json
  {"id":204,"lblTypeCd":"BBOX","label":"car","labelId":2,
   "points":[[0.0,683.0894050576364],[396.11424862685664,1017.736759187969]],
   "autoLblYn":"N","trackId":null}
  ```
  즉 **개별 조회로는 412 로 막히는 라벨 좌표를 이 엔드포인트 하나로 영상 전체분 한 번에 받아낼 수 있다.** 이미지 URL 은 게이트가 걸린 경로를 가리켜 실제 픽셀은 나가지 않지만(412), 게이트가 막으려던 "PII 위치 특정 정보"는 그대로 나간다.
- **★확정 정책과의 관계(오분류 아님)**: 이 건은 UNCERTAINTIES **★1**(조상/자손 전파 금지 · 파생 경유 열람은 결함 아님)과 **무관**하다. 133 은 파생영상이 아니라 **신고가 접수된 자기 자신**이며, `DeidentReportGate` 의 판정 범위(자기 rawSn 행)에 정확히 들어온다. 확정 정책이 "차단해야 한다"고 규정한 바로 그 대상이 한 엔드포인트에서만 새는 것이다. 학습된 반복 결함 패턴(**게이트를 호출처마다 배선하면 반드시 샌다**)의 재발이다.
- **재현/확인 경로**:
  ```bash
  BASE=http://localhost:18081/api
  # 신고 구간 영상(133)의 개별 라벨 조회 → 412
  curl -s -o /dev/null -w '%{http_code}\n' "$BASE/v1/frames/80/labels" -H "Authorization: Bearer $REVIEWER"
  # 같은 영상의 검수 프레임 목록 → 200 + 라벨 28건
  curl -s "$BASE/v1/reviews/133/frames" -H "Authorization: Bearer $REVIEWER" | head -c 400
  ```
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE raw_sn = 133;   -- 133|F
  SELECT s.src_sn, count(l.lbl_sn) FROM ls_data_src s
    LEFT JOIN ls_data_lbl l ON l.src_sn = s.src_sn
   WHERE s.raw_sn = 133 GROUP BY 1;                                  -- 28건
  ```
- **영향**: CWE-359(민감정보 노출) / CWE-863(불완전 인가). 도달성이 높다 — REVIEWER 는 신고 여부와 무관하게 전 영상을 조회할 수 있고(`ReviewController.java:179 @PreAuthorize("hasRole('REVIEWER')")`), 검수 화면이 이 엔드포인트를 상시 호출한다. 부가로 `GET /v1/reviews/{videoId}`(단건 상세)와 `GET /v1/reviews` 목록은 `labelCount`(개수)만 노출하므로 심각도가 낮지만 같은 미배선 구간이다.
- **수정 방향(제안)**: `ReviewService.listFrames` 진입부(인가 검사 **이후**)에 `deidentReportGate.isUnderDeidentReport(videoId)` 판정을 넣고 참이면 다른 라벨 경로와 동일하게 **412** 를 던진다. 겸해 `getDetail`/`list` 의 `labelCount` 노출 여부를 정책적으로 확정한다. 근본 처방은 "라벨 본문을 내보내는 서비스 메서드"를 열거해 게이트 적용을 회귀 테스트로 고정하는 것이다(클래스패스 스캔 가드 방식). ⚠ **구현하지 않는다.**

---


### [D-ISSUE-21] TC-ASSIGN-022(부가) — 신규 배정이 `PENDING`(검수 대기)·`IN_REVIEW`(검수 중) 영상을 무검증으로 `ASSIGNED` 로 강등해 검수 큐에서 사라지게 한다

- **심각도**: HIGH
- **기대 동작(기대효과)**: D-ISSUE-01 의 근본 처방은 "`markAssigned()`(검증 없는 setter)를 상태머신 경유 단일 진입점으로 좁혀 배치·검수·배정 3경로가 같은 규칙을 공유"하는 것이었다(1차 ISSUES.md 수정 방향). 작업자가 이미 **검수 제출을 마친(PENDING)** 영상이나 검수자가 **검수를 시작한(IN_REVIEW)** 영상은, 배정 조작만으로 그 워크플로 상태를 되돌릴 수 없어야 한다. 되돌린다면 최소한 별도 이벤트(예: 검수 취소)로 감사에 남아야 한다.
- **현재 동작(이슈 내용)**: 이번 회차에 신설된 가드는 **APPROVED 한 값만** 본다.
  `backend/src/main/java/kr/co/cudo/authoring/assignment/service/AssignmentService.java:141-152`
  ```java
  private void rejectApprovedTargets(List<Long> rawDataIds) {
      ...
      boolean anyApproved = dataSttsRepository.findByRawDataIdIn(rawDataIds).stream()
              .anyMatch(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()));   // ← APPROVED 만
  ```
  가드를 통과하면 상태 전이는 여전히 검증 없는 setter 다.
  `AssignmentService.java:88-89`
  ```java
  LsRawDataStatus stts = upsertDataStts(rawDataId);
  stts.markAssigned();          // 현재 상태 무관 무조건 ASSIGNED
  ```
  `LsRawDataStatus.java:83-86`
  ```java
  public void markAssigned() {
      this.dataSttsCd = STTS_ASSIGNED;   // ReviewStateMachine.verify 미경유
      this.updDt = LocalDateTime.now();
  }
  ```
  **실동작 근거(2026-07-31 03:39 KST)**
  - `PENDING`(검수 대기) 영상 rawSn=146 에 신규 배정 → **201**, `LS_RAW_DATA_STATUS(146)` `PENDING → ASSIGNED`(`VER` 15→16). 직전 `GET /v1/reviews?size=100` 에 노출되던 146 이 **검수 목록에서 소실**(응답 `totalElements=10`, `[155,132,151,128,136,134,126,20013,20012,20011]` — 146 부재).
  - `IN_REVIEW`(검수 진행 중) 영상 rawSn=155 에 신규 배정 → **201**, `IN_REVIEW → ASSIGNED`(`VER` 3→4). 진행 중이던 검수가 배정 조작만으로 리셋.
  - 두 경우 모두 `LS_TASK_EVENT_LOG` 에는 **`ASSIGN` 이벤트만** 남고 상태 강등 흔적이 없다(rawSn 155 이벤트 로그 실측: `ASSIGN` → `REASSIGN` → `REASSIGN` 뿐).
  - (재현 후 146 은 `POST /v1/reviews/146/submit` 으로 `PENDING` 복구 완료)
- **재현/확인 경로**:
  ```bash
  BASE=http://localhost:18081/api
  # 사전: rawSn=X 가 PENDING(검수 제출 완료) 또는 IN_REVIEW
  curl -s -X POST $BASE/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2002,"rawDataIds":[X]}'   # → 201
  ```
  ```sql
  SELECT raw_data_id, data_stts_cd, ver FROM ls_raw_data_status WHERE raw_data_id = X;  -- → ASSIGNED
  SELECT evnt_type_cd FROM ls_task_event_log WHERE raw_data_id = X ORDER BY evnt_id;    -- → ASSIGN 만
  ```
  검수 목록 소실 확인: `curl -s -H "Authorization: Bearer $REV" "$BASE/v1/reviews?page=0&size=100"`
- **영향**: 워크플로 정합성 + 감사 추적. ①작업자가 제출한 검수 건이 **검수자 대기열에서 예고 없이 사라진다**(검수자는 원인을 알 수 없고 이벤트 로그에도 근거가 없다) ②진행 중 검수(IN_REVIEW)가 무효화되어 검수자 작업이 유실된다 ③상태 되돌림이 `ReviewStateMachine` 를 우회하므로 상태머신이 상태 전이의 단일 진실원이 아니게 된다(D-ISSUE-05·B-ISSUE-03 과 동일 뿌리 = "검증 없는 상태 writer"). 보안 축은 CWE-840(비즈니스 로직 검증 누락). REVIEWER 권한만 있으면 일반 배정 UI 조작으로 도달한다.
- **수정 방향(제안)**: ① `rejectApprovedTargets` 의 차단 집합을 `APPROVED` 단일값에서 **검수 진행 상태 집합**(`PENDING`/`IN_REVIEW`/`APPROVED`)으로 넓힐지, ②아니면 강등을 허용하되 `ReviewStateMachine.verify(current, ASSIGNED)` 를 경유시키고 별도 이벤트(`REVIEW_CANCELLED` 등)를 남길지 **정책 확정**이 먼저다. 어느 쪽이든 `markAssigned()` 를 상태머신 경유 단일 진입점으로 좁히는 것이 근본 처방이며, 이는 재배정 축의 D-ISSUE-05 와 같은 결정에 묶어야 한다(두 경로가 다른 규칙을 갖는 현행이 비대칭의 원인). ⚠ **구현하지 않는다.**


### [D-ISSUE-02] TC-REVIEW-050 — `size <= 0` / `page < 0` 이 400 이 아닌 **500**

- **심각도**: MEDIUM (API 계약 위반 · CWE-20 입력 검증 누락)
- **기대 동작(기대효과)**: `rules/api-design.md` — 입력값 검증 실패는 **400**. 페이징 파라미터는 사용자 제어 입력이므로 상·하한 모두 검증돼야 하며, 인증된 사용자가 유발 가능한 미처리 500 이 남아서는 안 된다(`security.md` — 예외 처리 시 fail-secure).
- **현재 동작(이슈 내용)**: `ReviewController.java:92-94` 는 **상한만** 검증한다.
  ```java
  if (size > MAX_PAGE_SIZE) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
  }
  Pageable pageable = PageRequest.of(page, size, ...);   // ← size<1 / page<0 이면 IllegalArgumentException
  ```
  `page`/`size` 는 `@RequestParam(defaultValue=…) int` 라 Bean Validation(`@Min`)이 붙어 있지 않고, `PageRequest.of` 가 던지는 `IllegalArgumentException` 은 `GlobalExceptionHandler` 의 `handleUnknown` 으로 떨어져 500 이 된다.

  **실측**

  | 요청 | 응답 |
  |---|---|
  | `size=100` | 200 |
  | `size=101` · `size=1000` | **400** `size 한도 초과 (max=100)` |
  | **`size=0`** | **500** `서버 내부 오류가 발생했습니다.` |
  | **`size=-1`** | **500** |
  | **`page=-1`** | **500** |
  | `page=99999` | 200(빈 결과) |
  | `size=abc` · `page=abc` | 400 `파라미터 형식이 올바르지 않습니다` |

  backend 로그: `java.lang.IllegalArgumentException: Page size must not be less than one` / `Page index must not be less than zero`.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:18081/api/v1/reviews?size=0"   -H "Authorization: Bearer $REVIEWER"  # 500
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:18081/api/v1/reviews?page=-1"  -H "Authorization: Bearer $REVIEWER"  # 500
  ```
- **영향**: 응답 본문에 스택트레이스·내부 경로는 **노출되지 않아**(`GlobalExceptionHandler` 가 일반화) CWE-209 는 성립하지 않는다. 다만 인증된 REVIEWER 누구나 유발 가능한 미처리 500 이며, 오류 모니터링·알럿 노이즈가 되고 FE 는 400/500 을 구분해 처리할 수 없다. `GET /v1/reviews/summary` 는 page/size 를 받지 않아 영향 없음.
- **수정 방향(제안)**: `size < 1 || page < 0` 을 상한 검증과 같은 블록에서 `INVALID_INPUT(400)` 으로 처리하거나, `@Min(0) int page` / `@Min(1) int size` 를 붙여 기존 `handleConstraintViolation` 경로에 태운다(컨트롤러에 `@Validated` 가 이미 있다). 같은 패턴이 다른 목록 API 에도 있는지는 A/D-part2 소관으로 별도 확인 권장. ⚠ **구현하지 않는다.**

---


### [D-ISSUE-03] TC-REVIEW-019(부가) — 지원하지 않는 `Content-Type` 요청이 415 가 아닌 **500**

- **심각도**: MEDIUM (API 계약 위반 · 크로스컷 — `GlobalExceptionHandler` 핸들러 누락)
- **기대 동작(기대효과)**: 지원하지 않는 미디어 타입은 **415 Unsupported Media Type**(또는 최소 400). `GlobalExceptionHandler` 는 이미 `HttpRequestMethodNotSupportedException` 을 `handleMethodNotSupported`(405 `METHOD_NOT_ALLOWED`)로 처리하고 있어, 같은 계열인 미디어 타입만 누락된 것은 일관성 결함이다.
- **현재 동작(이슈 내용)**: `GlobalExceptionHandler` 의 `@ExceptionHandler` 13종(`handleCustom`/`handleValidation`/`handleConstraintViolation`/`handleMessageNotReadable`/`handleMissingParam`/`handleTypeMismatch`/`handleDataIntegrityViolation`/`handleAccessDenied`/`handleAuth`/`handleMaxUploadSizeExceeded`/`handleNoResourceFound`/`handleMethodNotSupported`/`handleUnknown`) 중 **`HttpMediaTypeNotSupportedException` 핸들러가 없다** → `handleUnknown` → 500.
  ```
  2026-07-31 03:46:36.994 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'text/plain;charset=UTF-8' is not supported
  ```
  **실측 범위**

  | 요청 | 응답 |
  |---|---|
  | `POST /v1/reviews/134/reject` + `Content-Type: text/plain` | **500** |
  | `PUT /v1/videos/146/event-annotation` + `text/plain` | **500** |
  | `POST /v1/videos/146/event-annotation` + `text/plain` | 405(매핑 자체 없음 — 정상) |
  | `GET /v1/reviews/132/approve` / `DELETE /v1/reviews/132` / `PUT /…/submit` | **405** `허용되지 않은 요청 메서드입니다.` (정상) |
  | `POST /…/approve` + 깨진 JSON | 400 `요청 본문이 올바르지 않습니다.` (정상) |
  | `POST /…/approve` + `{"noLabelConfirmed":"yes"}` | 400 (정상) |

  즉 **`@RequestBody` 를 받는 모든 엔드포인트**가 대상이라 D-1 국한 결함이 아니다(A 클러스터 공통 인프라). D-1 에서는 `reject`(사유 필수 케이스 TC-REVIEW-019 의 인접 경로)로 실측돼 여기 기록한다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18081/api/v1/reviews/134/reject \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: text/plain' -d 'reason'   # 500
  ```
- **영향**: 응답 본문에 내부 정보 미노출(일반화됨). 데이터 손상·권한 우회 없음. 오류 처리 규약 위반 + 모니터링 노이즈. 인증 사용자면 누구나 유발 가능.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` → `415`(신규 `ErrorCode.UNSUPPORTED_MEDIA_TYPE`) 또는 기존 `INVALID_INPUT(400)` 매핑을 추가한다. 겸해 `HttpMediaTypeNotAcceptableException`(Accept 헤더)도 함께 처리 검토. ⚠ **구현하지 않는다.**

---


### [D-ISSUE-41] TC-DIFF-002 — SKELETON diff 의 `before`/`after` 가 가시성 `v` 를 버려 "무엇이 바뀌었는지" 표시할 수 없고, shape 타입도 `POLYGON` 으로 오표기된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 키포인트 `v`(가시성)만 바뀐 두 APPROVED 버전을 diff 하면 `MODIFIED` 로 분류되고, 응답의 `before`/`after` 가 **서로 다른 값**을 담아 검수자가 화면에서 변경 지점을 확인할 수 있어야 한다(v-blindness 수정의 취지). shape 타입도 실제 라벨 타입(SKELETON)을 반영해야 FE 가 올바른 렌더러를 고른다.
- **현재 동작(이슈 내용)**: 분류는 정상이나 **응답 페이로드가 v 를 버린다.**
  - `VersionService.readPoints`(`VersionService.java:1180-1197`)는 SKELETON 일 때 삼중값 `[x,y,v]` 를 읽어 **비교에는 반영**한다(그래서 MODIFIED 로 잡힘).
  - 그러나 응답 변환은 `LabelDiffDto.ShapeDto.fromPoints`(`LabelDiffDto.java:52-79`)로 가는데, 이 메서드는
    ```java
    List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
    for (List<Double> pt : points) {
        if (pt.size() >= 2) { flat.add(pt.get(0)); flat.add(pt.get(1)); }   // ← 3번째 원소(v) 폐기
    }
    return new ShapeDto("POLYGON", null, null, null, null, flat);            // ← 타입도 POLYGON 고정
    ```
  - 실측(rawSn 156 / srcSn 132 / lblSn 421, v 를 `[0,1,2,…]` → 전량 `2` 로 변경 후 재승인):
    ```
    {"type":"MODIFIED","frameId":1,"objectId":"421",
     "before":{"type":"POLYGON","points":[100.0,200.0,110.0,205.0, … ,260.0,280.0]},
     "after" :{"type":"POLYGON","points":[100.0,200.0,110.0,205.0, … ,260.0,280.0]}}
    ```
    → `before` 와 `after` 가 **바이트 단위로 동일**하다. 소비자 입장에서는 "MODIFIED 인데 아무것도 안 바뀐" 항목이 된다.
- **재현/확인 경로**:
  ```bash
  # 1) SKELETON 라벨 보유 프레임을 승인해 v1 스냅샷 생성
  # 2) v 만 바꿔 저장 → 재승인해 v2 스냅샷 생성
  curl -s "http://localhost:18081/api/v1/versions/$V2/diff?compareWith=$V1" -H "Authorization: Bearer $REV"
  # → type=MODIFIED 이나 before.points == after.points, type="POLYGON"
  ```
- **영향**: 데이터 손상은 없다(스냅샷·롤백은 v 를 무손실 보존). 다만 ①검수자가 diff 화면에서 키포인트 가시성 변경을 **식별할 수 없고** ②`type:"POLYGON"` 때문에 FE 가 SKELETON 전용 렌더링(관절 연결·가시성 색상)을 선택할 근거를 잃는다. SFR-08 "버전별 변경 내용 비교" 요건의 키포인트 라벨에 대한 실질 미충족.
- **수정 방향(제안)**: `ShapeDto` 에 SKELETON 분기를 추가해 삼중값을 보존(`type:"SKELETON"` + 3배수 평탄 배열 또는 `[[x,y,v],…]` 중첩)하고, FE `LabelDiff` 타입에 대응 렌더러를 추가한다. `readPoints` 는 이미 v 를 읽고 있으므로 변환부만 손대면 된다. ⚠ **구현하지 않는다.**


### [D-ISSUE-42] TC-DIFF-008 — 롤백이 **복원되는 `LBL_SN` 의 라벨 속성값(`LS_DATA_LBL_ATTR_VAL`)까지 무조건 삭제**한다 (AI 메타 처리와 비대칭)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 롤백은 "해당 스냅샷 시점의 라벨 상태로 되돌리는" 연산이다. `LBL_SN` 이 보존 복원되는(D-ISSUE-22 해소) 라벨은 **같은 라벨이 계속 존재하는 것**이므로, 스냅샷이 담지 않는 부수 데이터(속성값)는 **AI 메타와 동일하게 보존**되어야 한다. 최소한 소실 사실이 사용자에게 드러나야 한다.
- **현재 동작(이슈 내용)**: `VersionService.replaceFrameLabels`(`VersionService.java:679-695`)의 삭제 블록이 두 자식 테이블을 **다르게** 취급한다.
  ```java
  List<Long> delSns = existing.stream().map(LsDataLbl::getLblSn).toList();
  attrValRepository.deleteByLblSnIn(delSns);                                  // ← 전량 삭제 (restoredIds 미고려)
  List<Long> aiDropSns = delSns.stream().filter(id -> !restoredIds.contains(id)).toList();
  if (!aiDropSns.isEmpty()) { aiInfoRepository.deleteByDataLblSnIn(aiDropSns); }  // ← 복원 대상은 보존
  ```
  AI 메타는 `restoredIds`(= 다시 살아날 `LBL_SN`)를 제외하고 지우는데, 속성값은 제외 없이 전부 지운다. 자바독(`:655-658`)도 "속성값은 스냅샷 페이로드에 없으므로 복원되지 않고, FK 위반(500) 방지를 위해 삭제된다"고 **의도된 동작**으로 적고 있으나, 그 근거였던 FK 위반 위험은 **PK 보존 복원이 도입된 지금은 해당 라벨에 대해 성립하지 않는다**(행이 같은 PK 로 되살아난다).
- **재현/확인 경로**:
  ```bash
  # 라벨마스터 속성 생성 → 라벨(lblSn=420)에 속성값 부여
  curl -X POST .../v1/manage/labels/1/attrs   -d '{"name":"x","inputType":"TEXT"}'
  curl -X PUT  .../v1/labels/420/attrs        -d '{"values":[{"attrId":2,"value":"d5"}]}'
  # 라벨 좌표를 바꿔 롤백이 교체 경로를 타게 한 뒤 롤백
  curl -X POST .../v1/versions/$HASH/rollback -d '{"srcSn":132}'      # → 200 (FK 위반 500 없음 ✅)
  ```
  ```sql
  select count(*) from ls_data_lbl_attr_val;   -- 롤백 전 1 → 롤백 후 0
  select lbl_sn from ls_data_lbl where src_sn=132;  -- 420, 421 (라벨 자체는 동일 PK 로 생존)
  ```
- **영향**: **조용한 데이터 손실.** 라벨은 그대로 남아 있는데 그 라벨에 붙은 CVAT-Like 속성값(예: 차량 유형·가림 여부 등 작업자가 수기로 채운 값)만 사라지며, 응답·로그 어디에도 경고가 없다. 속성값 사용이 본격화되면 롤백 1회로 프레임 전체 속성 작업이 유실된다. 현재 운영 DB 의 속성값 보유 행이 적어 발현이 드물 뿐이다(본 검증에서 직접 만들어 재현). 가용성 이슈(FK 위반 500)는 없음 — 그 부분은 정상 해소 확인.
- **수정 방향(제안)**: `attrValRepository.deleteByLblSnIn(...)` 에도 `restoredIds` 제외 필터를 적용해 AI 메타와 규약을 맞추거나(권장), 그럴 수 없다면 삭제 건수를 WARN 감사 로그로 남기고 API 응답에 소실 건수를 포함한다. ⚠ **구현하지 않는다.**


### [D-ISSUE-61] TC-MARTVIEW-018 — `V_COMPLETED_VIDEO.DE_IDNTF_YN` 이 동결 스냅샷값이라 비식별 신고 상태를 반영하지 못한다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 확정 정책상 신고 구간에도 행·경로를 유지하되, **관제가 자체 판단할 수 있도록 `DE_IDNTF_YN` 컬럼을 내보낸다**(카탈로그 TC-MARTVIEW-018 기대결과 후단 · `CLAUDE.md` "관제가 원하면 자체 판단도 가능하다"). 즉 이 컬럼은 관제가 "지금 이 영상이 신고 구간인가"를 판별하는 유일한 단서다.
- **현재 동작(이슈 내용)**: 뷰가 이 컬럼을 **라이브 `LS_DATA_RAW` 가 아니라 동결 스냅샷 `LS_DATASET_VIDEO_META` 에서 읽는다.**

  `backend/src/main/resources/db/migration/V138__expose_deident_video_path_in_completed_video_view.sql:49,80`
  ```sql
  m.DE_IDENT_YN      AS DE_IDNTF_YN,
  ...
  FROM LS_DATASET_VIDEO_META m
  INNER JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN   -- r 은 상태 컬럼에 쓰이지 않는다
  ```
  스냅샷은 **검수 승인 시점(materialize)** 에 동결되므로, 승인 이후 접수된 신고는 재동결이 일어나기 전까지 반영되지 않는다.

  실측(rawSn 169):
  ```
  04:11:27  POST /api/v1/labels/179/deident-report  -> 201 (rprtSn=12)
  live :  select de_ident_yn from ls_data_raw where raw_sn=169;              -> F
  frozen: select active_yn, de_ident_yn from ls_dataset_video_meta ...=169;  -> (N,'Y') (Y,'Y')
  view  : select de_idntf_yn from v_completed_video where raw_sn=169;        -> Y     ← 신고 미반영
  ```
  값이 **비결정적**이라는 점이 특히 문제다 — 같은 시점의 rawSn 173 은 신고 이후 재동결이 한 번 더 일어나 뷰가 `F` 를 보여준다. 즉 관제 입장에서 같은 컬럼이 어떤 영상에선 최신, 어떤 영상에선 승인 시점 값이다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report \
       -H "Authorization: Bearer $REVIEWER_OR_ASSIGNED_WORKER" \
       -H 'Content-Type: application/json' -d '{"reason":"..."}'
  ```
  ```sql
  SELECT r.de_ident_yn AS live, v.de_idntf_yn AS view_value
    FROM ls_data_raw r JOIN v_completed_video v ON v.raw_sn = r.raw_sn
   WHERE r.raw_sn = {rawSn};   -- live='F' 인데 view_value='Y'
  ```
- **영향**: 관제가 이 컬럼으로 "마스킹 실패 가능성 있는 산출물"을 스스로 걸러내려 하면 **걸러지지 않는다**(CWE-359 정보 노출 판단 근거 오류). 확정 정책상 저작도구가 뷰에서 차단하지 않는 대신 관제에게 판단 재료를 준다는 전제가 성립하지 않으므로, "잔여 누수"가 아니라 **계약 상 약속한 신호의 부재**다. 파일 접근 자체는 정책대로 허용이 정상이며 그 부분은 결함이 아니다.
- **수정 방향(제안)**: 셋 중 택1로 관제팀과 확정. ①`V138` 의 해당 컬럼을 `r.DE_IDENT_YN`(라이브 `LS_DATA_RAW`, 이미 INNER JOIN 되어 있어 추가 조인 불요)으로 전환 — 다만 `CREATE OR REPLACE` 는 기존 컬럼 타입 변경을 허용하므로 같은 타입이면 안전. ②컬럼 의미를 "승인 시점 값"으로 문서 고정하고 **라이브 신고 상태용 컬럼을 맨 끝에 신규 추가**(하위호환). ③이 컬럼으로 자체 판단하지 않기로 관제와 합의하고 카탈로그·`CLAUDE.md` 문구에서 "자체 판단 가능" 서술 삭제. ⚠ **구현하지 않는다.**


### [D-ISSUE-04] TC-REVIEW-034 — `ReviewStateMachine.verify(null, …)` 가 400 이 아닌 NPE(500) *(1차 D-ISSUE-03 미해소 이월)*

- **심각도**: LOW (현재 도달 불가 — 잠재 결함)
- **기대 동작(기대효과)**: 카탈로그 TC-REVIEW-034 는 `from = COMPLETED / PROCESSING / null` 전부 `INVALID_INPUT(400)` 을 기대한다. 상태 머신은 알 수 없는 입력에 fail-closed 로 400 을 던져야 하며 500(내부 오류) 경로를 열어서는 안 된다.
- **현재 동작(이슈 내용)**: `ReviewStateMachine.java:43-51` 의 `ALLOWED` 가 `Map.of(...)`(JDK `ImmutableCollections.MapN`) 이라, `:59 ALLOWED.get(from)` 에서 `from == null` 이면 `MapN.probe(null)` 이 `pk.hashCode()` 를 호출해 **NullPointerException** 이 나고 `:60-63` 의 `allowed == null` 분기에 도달하지 못한다.
  ```java
  public void verify(String from, String to) {
      if (LsRawDataStatus.STTS_APPROVED.equals(from) && !LsRawDataStatus.STTS_PENDING.equals(to)) { ... }
      Set<String> allowed = ALLOWED.get(from);        // ← from=null 이면 여기서 NPE
      if (allowed == null || !allowed.contains(to)) { throw new CustomException(INVALID_INPUT, ...); }
  }
  ```
  진입부 null 가드는 **여전히 없다**. `COMPLETED`/`PROCESSING` 은 맵 미등록 키라 정상적으로 400 을 던진다(이 두 값은 정상).
- **1차 대비 변화**: 없음. 코드 무변경이고, 1차 수정 제안이던 `ReviewStateMachineTest` 의 COMPLETED/PROCESSING/null 3케이스도 **추가되지 않았다**(현재 7 케이스 전부 정상 상태값 조합).
- **재현/확인 경로**: HTTP 로는 재현 불가 — `LS_RAW_DATA_STATUS.DATA_STTS_CD` 는 `nullable=false`(`LsRawDataStatus.java:37`)이고 `transitionTo`/`verify` 호출자 전수(`ReviewService.java:398,425,448,473,574`)에서 null 이 들어갈 경로가 없다. 단위 수준: `new ReviewStateMachine().verify(null, "PENDING")`.
- **영향**: 현재 도달 불가(이론적). 향후 상태 컬럼이 nullable 로 바뀌거나 외부 연동/DTO 에서 상태를 주입하면 500 경로가 열린다.
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null || to == null) throw new CustomException(INVALID_INPUT, "상태 값이 없습니다.");` 가드 추가 + `ReviewStateMachineTest` 에 `COMPLETED`/`PROCESSING`/`null` 3케이스 추가. ⚠ **구현하지 않는다.**

---


### [D-ISSUE-05] 파생영상(증강·해상도)의 검수 조작 차단이 **명시 가드가 아니라 상태행 부재에 의한 404** *(정보 제공)*

- **심각도**: LOW (현재 실동작 결함 없음 — 방어 근거의 취약성)
- **기대 동작(기대효과)**: `CLAUDE.md` — 파생영상(`ORGNL_RAW_SN` non-null)은 내부 생성물이며 라벨 검수 대상이 아니다.
- **현재 동작(이슈 내용)**: `ReviewService` 에는 **파생 여부(`ORGNL_RAW_SN`) 판정이 없다.** 파생 129/130/131 에 검수 조작이 막히는 유일한 이유는 `loadByVideoId`(`ReviewService.java:605-608`)가 `LS_RAW_DATA_STATUS` 행을 찾지 못해 404 를 던지기 때문이다.

  | 요청 | 실측 |
  |---|---|
  | `POST /v1/reviews/{129,130,131}/start` | **404** `검수 대상 영상을 찾을 수 없습니다.` |
  | `POST /v1/reviews/{129,130,131}/approve` | **404** (동일) |
  | `POST /v1/reviews/{129,130,131}/reject` | **404** (동일) |

  DB 확인: `LS_DATA_RAW.ORGNL_RAW_SN=126` 인 3건 모두 `LS_RAW_DATA_STATUS` 행 `(none)` (PIPE-ISSUE-04 와 동일 관측).
  그런데 `LS_RAW_DATA_STATUS` 행은 **배정 시점에 lazy 생성**된다(126 도 배정 때 생성됨). 즉 `POST /v1/assignments` 로 파생영상에 작업자를 배정하면 상태행이 생기고, 그 순간부터 이 404 방어가 사라져 파생영상도 승인·반려가 가능해진다. 실제로 `CLAUDE.md` 는 *"파생영상은 미검수(PENDING) 상태로 시작 → 작업자 배정 → 수정 → 검수"* 라고 파생 검수를 **허용**한다고 적고 있어, 두 서술(검수 차단 vs 검수 대상) 사이의 정책 확정이 필요하다.
- **범위 주의**: `CLAUDE.md` 의 *"해상도 파생은 … accept/reject(검수 승인·반려)는 차단"* 은 **증강 이력의 accept/reject(`LS_DATA_AUG.AUG_PROC_STTS_CD`)** 를 가리키며 그쪽은 E 클러스터(TC-AUG/TC-RESL) 소관이다. 본 항목은 **검수 워크플로(`/v1/reviews/*`)** 축의 관측이다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18081/api/v1/reviews/129/approve -H "Authorization: Bearer $REVIEWER"  # 404
  ```
  ```sql
  SELECT r.raw_sn, r.orgnl_raw_sn, COALESCE(s.data_stts_cd,'(none)')
    FROM ls_data_raw r LEFT JOIN ls_raw_data_status s ON s.raw_data_id = r.raw_sn
   WHERE r.orgnl_raw_sn IS NOT NULL;   -- 129/130/131 → (none)
  ```
  ⚠ 파생영상에 실제로 배정을 걸면 다른 에이전트(E 클러스터)의 참조 데이터가 바뀌므로 **배정은 수행하지 않았다.** 위 서술은 코드 경로(`AssignmentService` 의 상태행 lazy upsert) 정적 확인에 근거한다.
- **영향**: 현재 데이터 상태에서는 실제 노출 없음. 정책이 "파생은 검수 대상 아님" 쪽이면 방어가 우발적(상태행 유무)이라 배정 한 번으로 무너지고, "파생도 검수 대상" 쪽이면 404 가 오히려 정상 동선을 막는 결함이다. 어느 쪽이든 **의도를 코드로 표현하고 있지 않다.**
- **수정 방향(제안)**: 정책 확정 후 ①검수 대상이 아니라면 `ReviewService` 진입부에 `ORGNL_RAW_SN != null` → 412/409 명시 거부 + 안내 문구, ②검수 대상이라면 파생 확정 시 `LS_RAW_DATA_STATUS` PENDING 행을 선생성(PIPE-ISSUE-04 제안과 동일). ⚠ **구현하지 않는다.**

---


### [D-ISSUE-22] TC-ASSIGN-021(부가) — 상태행 PK 충돌을 "동일 작업자 중복 배정" 으로 계속 오안내 (1차 D-ISSUE-07 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 409 메시지는 실제 충돌 원인을 반영해야 한다(운영 진단성). 서로 다른 작업자를 동시에 배정해 `LS_RAW_DATA_STATUS` PK 가 충돌한 경우와, 같은 작업자를 두 번 배정해 `LS_TASK_ASSIGNMENT` UK 가 충돌한 경우는 다른 안내여야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:97-99` 의 catch 가 두 원인을 한 메시지로 뭉갠다.
  ```java
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  ```
  실측(C3): 상태행이 없는 rawSn=150 에 **서로 다른** 작업자(2001/2002)를 동시 배정 → 패자 응답 `409 {"message":"이미 동일 작업자에게 배정된 영상이 있습니다."}`. 실제 충돌은 `upsertDataStts`(`:613-616`)의 PK INSERT 경합이고 작업자 중복이 아니다.
- **재현/확인 경로**:
  ```bash
  for w in 2001 2002; do curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/v1/assignments \
    -H "Authorization: Bearer $REV" -H 'Content-Type: application/json' \
    -d "{\"workerId\":$w,\"rawDataIds\":[<상태행 없는 rawSn>]}" & done; wait
  ```
- **영향**: 운영·지원 진단 혼선만 있고 데이터 영향은 없다(재시도하면 성공). 1차 D-ISSUE-07 과 동일 건이며 이번 회차에도 변화 없음.
- **수정 방향(제안)**: 제약명(PK vs UK)으로 메시지를 분기하거나, 상태행 upsert 를 별도 try-catch 로 분리해 "일시적 충돌 — 재시도해 주세요" 로 안내. ⚠ **구현하지 않는다.**


### [D-ISSUE-23] TC-ASSIGN-014(부가) — 동일 재배정 경합의 응답 코드가 타이밍에 따라 409/400 으로 갈린다

- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 원인(다른 요청이 먼저 재배정 성공)으로 거부되는 요청은 클라이언트가 동일하게 분기할 수 있도록 일관된 상태코드를 받아야 한다. 케이스 기대값도 "성공 1건 + 나머지 CONFLICT(409)" 다.
- **현재 동작(이슈 내용)**: 8병렬 스트레스(C2)에서 `200 ×1 / 409 ×6 / **400 ×1**` 이 관측됐다. 400 은 승자 커밋 이후에 `findById` 를 수행한 요청이 동일작업자 가드(`AssignmentService.java:211-213`)에 먼저 걸린 것으로, 낙관적 잠금 충돌(`:244-252`, 409)에 도달하지 못한다.
  ```java
  if (prev.getUserNo().equals(req.workerId())) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "현재 배정된 작업자와 동일합니다.");
  }
  ```
- **재현/확인 경로**: `for i in $(seq 1 8); do curl -s -o /dev/null -w "%{http_code}\n" -X PATCH $BASE/v1/assignments/{id} -H "Authorization: Bearer $REV" -H 'Content-Type: application/json' -d '{"workerId":2001}' & done; wait`
- **영향**: 데이터 정합성 영향 없음(이력·이벤트 모두 +1행 유지, C1/C2 확인). FE 가 409 만 "다시 시도" 로 처리하고 400 은 입력 오류로 처리하면 동일 상황에서 다른 문구가 노출된다. 정보 노출 관점의 위험은 없다.
- **수정 방향(제안)**: 동일작업자 가드를 "요청 시점 값" 이 아니라 "전이 결과" 기준으로 판정하거나(예: 잠금/버전 확인 이후로 이동), 경합 상황에서 나온 400 을 409 로 승격. 혹은 케이스 기대값에 "선착 커밋 후 동일작업자로 수렴한 요청은 400" 을 명시해 계약을 고정. ⚠ **구현하지 않는다.**

---


### [D-ISSUE-43] TC-DIFF-021 — 신고 구간 rollback 의 실제 응답은 412 가 아니라 **409**(작업락이 신고 게이트보다 먼저 평가됨) — 케이스 기대값 정정 필요
- **심각도**: LOW (동작 결함 아님 — 차단 자체는 성립)
- **기대 동작(기대효과)**: 카탈로그 TC-DIFF-021 은 `DE_IDNTF_YN='F'`(신고 **또는** 비식별 실패) 영상의 rollback 을 **412(PRECONDITION_FAILED)** 로 규정한다.
- **현재 동작(이슈 내용)**: `VersionService.rollback` 은 두 가드를 **작업락 → 신고 게이트** 순으로 평가한다.
  ```java
  if (workLockService.isRawLocked(raw.getRawSn())) {                 // :427-430
      throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상은 롤백할 수 없습니다.");
  }
  accessGuard.requireNotUnderDeidentReport(raw.getRawSn());          // :432-437  ← 412
  ```
  비식별 누락 **신고**는 `'F'` 세팅과 **작업락 획득을 함께** 수행하므로, 신고 경로에서는 항상 `:427` 이 먼저 걸려 **409** 가 나간다. 실측:
  ```
  신고 접수(POST /v1/labels/133/deident-report) → 201, ls_data_raw(156).de_ident_yn='F'
  POST /v1/versions/{v1}/rollback {"srcSn":133}  → 409 {"errorCode":"CONFLICT","message":"작업이 잠긴 영상은 롤백할 수 없습니다."}
  같은 시점 GET /v1/versions/{v2}/diff           → 412 (게이트 정상)
  ```
  `:437` 의 412 가 실제로 노출되는 조건은 "락 없이 `'F'` 인 배치 실패 경로"인데, 비식별이 파이프라인 **선두**라 프레임·버전을 보유한 상태에서 `'F'` 가 되는 배치 경로가 현재 없어 **라이브 재현 수단이 없다**(rawSn 127/128 은 `'F'` 지만 프레임 0건).
- **재현/확인 경로**: 위 3줄 그대로.
- **영향**: 보안·데이터 영향 없음(어느 쪽이든 차단). 다만 ①테스트 카탈로그의 기대값이 실동작과 달라 후속 회차에서 반복 오판정될 소지가 있고 ②클라이언트가 "재비식별 대기" 와 "다른 작업이 잠금 중"을 응답 코드로 구분하지 못한다.
- **수정 방향(제안)**: (a) 카탈로그 TC-DIFF-021 기대값을 "신고 경로 = 409(작업락) / 락 없는 `'F'` = 412"로 정정하거나, (b) 코드에서 신고 게이트를 작업락 검사보다 **앞으로** 옮겨 `'F'` 축을 일관되게 412 로 노출한다(diff·라벨 조회와 코드 일치). ⚠ **구현하지 않는다.**

---


### [D-ISSUE-62] TC-MARTVIEW-014 — 승인→materialize→라벨 버전 롤백 후 뷰·활성 스냅샷 정합 시나리오가 여전히 미커버 (D-ISSUE-50 미해소)

- **심각도**: LOW (커버리지 갭 — 현재까지 관측된 오동작 없음)
- **기대 동작(기대효과)**: 검수 승인으로 동결된 스냅샷이 있는 상태에서 라벨 버전을 롤백(대상 스냅샷 재활성)했을 때, `V_COMPLETED_VIDEO`/`V_COMPLETED_LABEL_CHANGE` 가 정합을 유지하고 **활성 스냅샷이 1건으로 유지**되는지 전용 통합 테스트가 고정해야 한다.
- **현재 동작(이슈 내용)**: 카탈로그가 근거로 지목한 `backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java` 의 유일한 시나리오는
  ```java
  :129  @DisplayName("materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영")
  ```
  로, **"materialize 가 실패하면 승인 트랜잭션이 통째로 롤백된다"**를 검증한다. 케이스가 요구하는 **"승인 성공 → 이후 라벨 버전 롤백 → 뷰 정합"** 과 다른 시나리오다. `VersionService` 롤백 경로를 뷰와 함께 검증하는 IT 는 검색되지 않는다(`DatamartViewSlimIT`·`DatamartViewRebuildIT` 모두 롤백 미포함).
- **재현/확인 경로**:
  ```bash
  grep -rn "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java
  grep -rln "VersionService\|rollback" backend/src/test/java/kr/co/cudo/authoring/dataset/view/
  ```
  실동작 측면에서도 이번 회차에서는 확인 불가였다 — 신규 승인 영상(170)의 프레임별 활성 스냅샷이 `APPROVED` 1건뿐이라(`GET /v1/frames/186/versions` → 1건) 롤백 대상 다버전이 존재하지 않고, 다버전 확보에는 반려→재작업→재승인 사이클이 필요해 타 클러스터 참조 데이터를 건드리게 된다.
- **영향**: 롤백이 `LS_LABEL_VERSION` 재활성 방식으로 바뀐 뒤(`SAVE_REASON='ROLLBACK'` 적층 폐기) 뷰/동결 스냅샷과의 상호작용이 회귀 가드 없이 남아 있다. 롤백 시 활성 스냅샷이 2건이 되거나 `V_COMPLETED_LABEL_CHANGE` 에 팬텀 행이 생기는 회귀가 조용히 통과할 수 있다.
- **수정 방향(제안)**: `DatamartViewSlimIT` 에 시나리오 1건 추가 — ①승인(v1 스냅샷) ②라벨 수정 후 재검수·재승인(v2) ③`POST /v1/versions/{v1hash}/rollback` ④단언: `LS_LABEL_VERSION` 활성 1건 = v1 · `V_COMPLETED_VIDEO` 1행 유지 · `V_COMPLETED_LABEL_CHANGE` 에 델타 0 롤백 감사행 미노출. ⚠ **구현하지 않는다.**


## E 클러스터 — 22건

### [E-ISSUE-21] TC-AUG-070 / 연관 TC-AUG-069·TC-AUG-114 — 벤더 산출 경로의 읽기 허용 루트에 **원본(비-비식별) 저장소**가 포함돼, 콜백 1건으로 원본 PII 프레임이 파생영상의 "비식별 프레임"으로 반입된다

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: 벤더가 콜백으로 건네는 `results[].output_file_path` 는 **벤더 자신의 산출 트리**
  (`authoring.storage.external-read-roots`, 현 형상 `/app/genai-out`)만 가리킬 수 있어야 한다.
  `VideoArtifactRootResolver` 클래스 주석이 스스로 이 목적을 "쓰기 allowlist 에 추가하면 PII 격리 축
  (원본/비식별 산출 위치 통제)이 흐려진다" 로 명시하고 있다.
- **현재 동작(이슈 내용)**: 읽기 루트가 **쓰기 allowlist ∪ external-read-roots** 로 계산되는데,
  쓰기 allowlist 에 **원본 저장소**가 그대로 들어 있다.

  `common/storage/VideoArtifactRootResolver.java:159-161`
  ```java
  private static List<Path> buildReadableRoots(List<Path> writeRoots, String configured) {
      Set<Path> roots = new LinkedHashSet<>(writeRoots);   // ← 원본 저장소 포함
  ```
  런타임 형상: `STORAGE_RAW_MOUNT_ROOTS=/app/storage/raw,/app/storage/deidentified`,
  `STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out` → 읽기 루트에 **`/app/storage/raw`**(마스킹 전 원본 프레임·영상)가 포함된다.

  `AugmentFrameProducer` 의 3중 검증(허용 루트 / 정규 파일·size>0 / **부모 비식별 프레임과 해상도 동일**)은
  원본 프레임을 걸러내지 못한다 — 원본과 비식별본은 **같은 영상에서 뽑은 같은 해상도**이기 때문이다.

  **실동작 재현 결과(실험 B)**: job 7(aug 36, 부모 rawSn 152)에
  `output_file_path=/app/storage/raw/frames/raw/152/frame-0.jpg`(마스킹 전 원본) + 위조 checksum 을 제출 →
  **200 `applied:true`** → 파생 **rawSn 167** 생성(`DATA_STTS_CD=COMPLETED`, `DE_IDNTF_YN='Y'`)
  → `LS_DATA_SRC(167).DE_IDNTF_SRC_FILE_PATH_NM` 파일의 실제 내용이 **원본 프레임과 md5 동일**.

  | 파일 | md5 |
  |---|---|
  | rawSn 152 **원본** `/app/storage/raw/frames/raw/152/frame-0.jpg` | `fae1cc00a40111d8869d4fecc200dd5c` |
  | rawSn 152 **비식별본** `/app/storage/deidentified/frames/deid/152/frame-0.jpg` | `e0df599df62cba67952a6530fc70423c` |
  | **파생 rawSn 167 의 "비식별 프레임"** `/app/storage/deidentified/frames/deid/167/frame-0.jpg` | **`fae1cc00a40111d8869d4fecc200dd5c`** ← 원본과 동일 |

- **재현/확인 경로**:
  ```bash
  # 1) 증강 요청 → mock job 을 즉시 취소해 우리 job 을 RECEIVED 로 동결
  curl -s -X POST http://localhost:18081/api/v1/augments/request -H "Authorization: Bearer $TOK" \
       -H 'Content-Type: application/json' -d '{"videoIds":[152],"types":["NIGHT"]}'
  curl -s -X POST http://localhost:9400/api/genai/jobs/{mockJobId}/cancel \
       -H 'Content-Type: application/json' -d '{"requested_by":"probe"}'
  # 2) 원본 프레임 경로를 산출물로 위장 제출
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' -d '{
    "request_id":"{issuedChunkKey}","job_id":"{mockJobId}","status":"SUCCEEDED",
    "results":[{"generated_data_id":"forged-1","media_type":"IMAGE",
                "output_file_path":"/app/storage/raw/frames/raw/152/frame-0.jpg",
                "checksum":"0000000000000000000000000000000000000000000000000000000000000000"}]}'
  # 3) 결과 확인
  docker exec klid-backend md5sum /app/storage/raw/frames/raw/152/frame-0.jpg \
       /app/storage/deidentified/frames/deid/167/frame-0.jpg     # → 동일 해시
  ```
- **영향**: **CWE-345**(Insufficient Verification of Data Authenticity) + **CWE-359**(Privacy Violation)
  + **CWE-501**(Trust Boundary Violation). 마스킹 전 PII 프레임이 `DE_IDNTF_YN='Y'` 파생영상의 비식별
  산출물로 확정되어 ①라벨링 캔버스 서빙(`/v1/frames/{srcSn}/deid-image`) ②검수 ③데이터셋 export
  ④데이터마트 뷰(`V_COMPLETED_FRAME.DEIDENTIFIED_PATH`) 로 전파된다. 파생영상은 **비식별 신고 체계
  바깥**(★확정 정책)이라 이 오염을 신고로 되돌릴 경로도 없다.
  공격자 = 벤더(또는 벤더 침해자). 발급 `request_id` 를 아는 주체가 정확히 벤더이므로 발급 게이트(401)는
  이 위협을 막지 못하며, IP allowlist 도 벤더 대역을 허용해야 하므로 동일하다.
  현 dev 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` 이라 **네트워크 도달 가능한 누구나** 해당한다.
- **수정 방향(제안)**: ①`verifyExternalReadablePath` 의 기준을 `external-read-roots` **단독**으로 좁힌다
  (쓰기 allowlist 와 합집합하지 않는다 — 벤더 산출물이 우리 저장소 안에 있을 이유가 없다).
  미설정 시 전건 400 은 fail-closed 로 오히려 올바른 동작이다. ②그래도 합집합이 필요하다면 최소한
  **원본 저장소(`raw-path`)를 읽기 루트에서 제외**한다. ③`Phase B` 반입 검증에 "산출 경로가 우리 저장소
  서브트리가 아닐 것" 불변식을 추가한다(`StorageSubtreePolicy` 재사용).
  ⚠ **구현하지 않는다** — 읽기 축 축소는 KPST/실벤더 공유마운트 형상과 함께 결정해야 한다.

---


### [E-ISSUE-61] TC-EXPORT-018 — export 가 **FAILED 로 마감된 경우에도** 관제 통지가 발송된다 (통지 보류 계약 파손)

- **심각도**: HIGH
- **기대 동작(기대효과)**: 확정 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다. **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다."* 즉 산출이 실패하면 관제가 통지를 받고 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`(최신 SUCCEEDED)을 조회해 **구 버전 폴더를 픽업**하는 일이 없어야 한다. TC-EXPORT-018 기대결과도 `doExport` 가 true 일 때만 완료 이벤트를 발행하고 "실패면 통지 보류"다.
- **현재 동작(이슈 내용)**: `DatasetExportService.export()` 의 **FAILED 종결 3분기가 예외를 던지지 않고 정상 return** 한다. 따라서 `AsyncDatasetExportRunner.doExport` 가 `true` 를 반환하고 통지가 그대로 나간다.

  ```java
  // DatasetExportService.java:205-210  (totalWritten == 0)
  txService.markFailed(inserted.exportSn());
  log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
  outcome = OUTCOME_FAILED;          // ← throw 없음

  // DatasetExportService.java:234-241 (writer.write 예외)
  } catch (RuntimeException e) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] export failed — approval unaffected ...");
      outcome = OUTCOME_FAILED;      // ← 삼키고 정상 흐름 복귀
  }

  // DatasetExportService.java:162-169 (산출 base 거부)
  } catch (RuntimeException e) {
      markBaseRejected(rawSn, prep.contentHash(), e);
      outcome = OUTCOME_FAILED;
      return;                        // ← 정상 return
  }

  // AsyncDatasetExportRunner.java:121-130
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }   // ← FAILED 도 true
      catch (Exception e) { ...; return false; }
  }
  ```

  **실동작 근거(rawSn 173, 두 경로 모두 재현)**
  ```
  04:10:01.352 WARN  [DatasetExport] nothing produced — marked FAILED rawSn=173 version=5
  04:10:01.360 INFO  [ControlNotify] completed conflicted -> resend as updated rawSn=173
  04:10:01.362 INFO  [ControlNotify] TASK_COMPLETED sent rawSn=173 actual=TASK_MODIFIED
  ---
  04:10:43.758 ERROR [DatasetExport] export base rejected — marked FAILED rawSn=173 reason=FORBIDDEN
  04:10:43.767 INFO  [ControlNotify] completed conflicted -> resend as updated rawSn=173
  04:10:43.770 INFO  [ControlNotify] TASK_COMPLETED sent rawSn=173 actual=TASK_MODIFIED
  ```
  대조군(정상 보류): 신고 게이트 차단은 예외로 이탈하므로 `async export failed rawSn=173 cause=CustomException` 이 찍히고 통지가 **나가지 않는다**(04:06:48). 즉 **보류가 동작하는 유일한 경로는 신고 게이트뿐**이고, 정작 회수기가 다루도록 설계된 `FAILED` 유형이 전부 새고 있다.

  **왜 테스트가 못 잡았나 (거짓 GREEN)**: `AsyncDatasetExportRunnerTest:87 승인_export_실패시_완료이벤트를_발행하지_않는다 (HIGH-D)` 와 `:113 재산출_실패시_통지콜백을_실행하지_않는다` 는 둘 다 `doThrow(new RuntimeException("io")).when(exportService).export(...)` 로 **예외를 스텁**한다. 그런데 실제 서비스는 같은 상황에서 예외를 던지지 않도록 `DatasetExportServiceTest:264 파일산출_실패해도_승인은_롤백되지_않는다 — 예외 미전파` 가 명시적으로 보장한다. 두 테스트가 **서로 모순된 전제** 위에서 각자 GREEN 이라 결합 지점이 비어 있다.

- **재현/확인 경로**
  ```bash
  # 1) 승인된 영상의 프레임 원천 이미지를 전부 제거 → 산출 0건 유도
  docker exec klid-backend sh -c 'rm -f /app/storage/raw/frames/raw/173/*.jpg \
                                        /app/storage/deidentified/frames/deid/173/*.jpg'
  # 2) 산출 트리거(신고→해소 또는 승인 후 수정 디바운스)
  # 3) 로그에서 markFailed 직후 통지가 나가는지 확인
  docker logs klid-backend 2>&1 | grep -E "nothing produced|TASK_COMPLETED sent|async export failed"
  ```
  ```sql
  SELECT export_ver_no, export_stts_cd, frame_cnt FROM ls_dataset_export WHERE data_raw_sn=173 ORDER BY 1;
  -- 5|FAILED|(null)  ← 이 시점에 통지가 이미 발송됨
  SELECT export_path_nm, frame_cnt FROM v_completed_video WHERE raw_sn=173;
  -- 관제는 통지 수신 후 여기서 '최신 SUCCEEDED' = 구 버전(v3)을 픽업한다
  ```

- **영향**
  - 관제서버가 **산출되지 않은/실패한 버전에 대해 통지를 받고, 뷰에서는 직전 성공 버전(구 내용)을 픽업**한다 — 확정 정책이 막으려던 바로 그 시나리오("파일이 옛 내용이면 라벨링 정보 동기화 요구가 성립하지 않는다").
  - 최초 승인이 실패한 영상(직전 SUCCEEDED 없음)이면 `EXPORT_PATH_NM=NULL` 인 행을 통지받아 관제 배치가 빈 경로/NULL 을 픽업한다.
  - `DatasetExportFailureRecoverer` 의 "성공 시점으로 지연" 설계가 무의미해진다(이미 통지가 나간 뒤라 재통지가 정정이 아니라 중복이 된다).
  - 데이터 무결성 계열(CWE-670 부적절한 제어 흐름 구현). PII 유출 방향은 아니다.

- **수정 방향(제안)** ⚠ **구현하지 않는다**
  1. `DatasetExportService.export()` 를 `boolean`(또는 `ExportOutcome`) 반환으로 바꾸고 `AsyncDatasetExportRunner.doExport` 가 예외 유무가 아니라 **outcome 으로** 성공을 판정한다(`COMPLETED`·`PARTIAL`·`IDEMPOTENT_SKIP` = 통지, `FAILED`·`VERSION_EXHAUSTED`·`DEIDENT_BLOCKED` = 보류, `NO_INPUT` 은 정책 결정 필요).
  2. 시그니처를 바꾸기 어렵다면 FAILED 3분기에서 **전용 예외**(`ExportFailedSilentlyException` 등)를 던지고 러너가 그것만 잡아 `false` 를 반환한다. 단 "승인 불변(예외 미전파)" 계약은 러너가 여전히 삼키므로 유지된다.
  3. 회귀 가드는 **스텁 예외가 아니라 실제 FAILED 경로**로 작성한다 — 예: `DatasetExportE2EIT` 에서 원천 이미지 0건 상태로 승인 → `verify(eventPublisher, never()).publishEvent(DatasetExportCompletedEvent)` 를 단언.

---


### [E-ISSUE-81] TC-META-031 / TC-DEID-035 — `PUT /v1/frames/{srcSn}/privacy-meta` 가 비식별 신고 게이트·작업락 어디에도 걸리지 않아, 신고가 리셋한 개인정보 3필드를 신고 구간에 즉시 되돌릴 수 있다
- **심각도**: HIGH (B-ISSUE-81 독립 재확인 — 본 part 가 결함의 본진)
- **기대 동작(기대효과)**: 비식별 누락 신고는 "그 영상의 비식별이 잘못됐다"는 신호이며, `DeidentReportService` 가 개인정보 3필드(익명/가명/PII 포함여부)를 **재판정 대상으로 리셋**한다(`CLAUDE.md` 개인정보 보호 절). 따라서 신고가 열려 있는 동안(`DE_IDNTF_YN='F'` + 작업락 `LOCKED`) 그 3필드에 대한 **쓰기는 차단**되어야 한다 — 라벨 저장이 409 로 막히는 것과 동일 축이다. 최소한 작업락 409, 정책상으로는 신고 게이트 412 가 맞다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn 133(`DE_IDNTF_YN='F'`, `LS_DEIDENT_REPORT` sn=3 `OPEN`, `LS_AUTH_WORK_LOCK` `LOCKED`)의 프레임 srcSn 78 에 대해
    `PUT /v1/frames/78/privacy-meta {"srcSn":78,"anonymity":"Y","pseudonymity":"Y","privacyIncluded":"Y"}` → **200**.
  - DB 즉시 확인: `ls_data_src(78)` 의 `anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 이 `NULL,NULL,NULL` → `Y,Y,Y` 로 **실제 기록**됨(신고 시 `privacyReset=6` 로 지운 값의 복귀).
  - 같은 시각 대조군: `GET /v1/frames/78/labels` = **412**, `PUT /v1/frames/78/labels` = **409**(작업락). 즉 **라벨 축만 닫혀 있고 메타 축은 열려 있다.**
  - 코드 근거 — `FramePrivacyMetaService.java:74-78`:
    ```java
    public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만 수행
        applyAndNotify(src, req.anonymity(), req.pseudonymity(), req.privacyIncluded(), actor);
    ```
    `DeidentReportGate`(판정 단일 원천) 주입 자체가 없고, `updateBulk:100-140` 에도 없다. `grep DeidentReportGate` → `dataset` 패키지 전체 0건.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"REVIEWER","channel":"INTERNAL"}' | jq -r .data.token)
  curl -s -o /dev/null -w '%{http_code}\n' -XPUT localhost:18081/api/v1/frames/78/privacy-meta \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"anonymity":"Y","pseudonymity":"Y","privacyIncluded":"Y"}'   # => 200
  ```
  ```sql
  SELECT src_sn, anony_incl_yn, psdo_incl_yn, prvc_incl_yn FROM ls_data_src WHERE src_sn=78;
  ```
- **영향**: `TC-DEID-035` 의 명시 목적("stale PII 방지")이 성립하지 않는다. 재비식별 전의 낡은 개인정보 판정이 그대로 복원되어 승인·export·데이터마트 뷰로 전파될 수 있다(APPROVED 영상이면 `pseudonymity`/`privacy_included` 로 export JSON 에 직접 실린다). **CWE-359**(개인정보 오표기 노출) / **CWE-362**(신고 처리 중 상태 변경). 작업락을 무시한다는 점에서 **CWE-863**(부정확한 인가) 성격도 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `FramePrivacyMetaService.update`/`updateBulk` 진입부(인가 직후)에 `DeidentReportGate` 판정을 추가한다 — 라벨 저장과 동일하게 **작업락 409 우선, 신고 412** 규약을 따르면 응답 코드 오라클(CWE-209) 위험 없이 기존 규약과 정합한다. 벌크는 rawSn distinct 집합 기준 1회 판정으로 N+1 없이 가능하다.


### [E-ISSUE-82] TC-META-003 — `PUT /v1/videos/{rawSn}/environment-meta` 도 같은 게이트 미배선. APPROVED 영상이면 신고 구간에 **동결 스냅샷만 갱신되고 export 는 차단**되어 뷰와 산출물이 갈린다
- **심각도**: HIGH (신규 — B-part5 미탐지 표면)
- **기대 동작(기대효과)**: 신고 구간에는 그 영상의 산출 계열 상태를 바꾸는 쓰기가 차단되어야 한다. 특히 촬영환경 수정은 APPROVED 영상에서 **동결 스냅샷 재동결(→ `V_COMPLETED_VIDEO` 즉시 변경) + export 새 버전 전량 재생성 + 관제 통지**를 연쇄 유발하는 무거운 경로라, 신고로 산출이 보류된 구간에서는 시작 자체가 막혀야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn 133(신고 OPEN + 작업락 LOCKED)에 `PUT /v1/videos/133/environment-meta {"weather":"비","timeOfDay":"DAY","season":"FALL"}` → **200**, `ls_data_raw(133)` 3필드 실제 기록.
  - 코드 근거 — `EnvironmentMetaService.java:100-112`: `accessGuard.verifyRawAccess(rawSn, actor)` → 값 검증 → `raw.changeShootingEnvironment(...)`. 게이트 판정 호출 없음.
  - **APPROVED 영상에서의 파생 피해(실측 관측)**: rawSn 169 에서 `PUT environment-meta` 로 재동결이 커밋된 뒤(04:10:18 `materialized … inserted=true`), 60초 디바운스 flush 시점(04:11:28)에 그 사이 접수된 신고 때문에 `[DatasetExport] export blocked — deident report open rawSn=169` → `async export failed rawSn=169`. 결과적으로 **①`LS_DATASET_VIDEO_META`(=`V_COMPLETED_VIDEO`)는 새 촬영환경으로 갱신되고 ②export 폴더는 옛 값 그대로** 남았다. 관제 계약상 뷰와 폴더는 같은 승인분을 가리켜야 하는데 갈린다.
  - **형제 결함 동시 재확인**: `PUT /v1/frames/78/description` 도 신고 구간에 **200**(작업락 무시) — B-ISSUE-82 미해소. `FrameDescriptionService` 역시 `exportRegenerated=true` 로 발행하므로(`:59-62`) 위와 같은 갈림을 만든다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -XPUT localhost:18081/api/v1/videos/133/environment-meta \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"weather":"비","timeOfDay":"DAY","season":"FALL"}'    # => 200
  ```
  ```sql
  SELECT raw_sn, wthr_nm, day_ngt_cd, sesn_cd, de_ident_yn FROM ls_data_raw WHERE raw_sn=133;
  ```
- **영향**: ①신고 구간 산출 보류 정책의 우회 ②뷰(즉시 갱신) ↔ export 폴더(보류) 불일치로 관제가 서로 다른 촬영환경을 본다 — `CLAUDE.md` "통지는 export 성공 후" 직렬화가 지키려던 불변식이 이 경로에서 깨진다. **CWE-362**(TOCTOU/상태 경합) / **CWE-863**. `EnvironmentMetaService` 는 `CLAUDE.md` 의 재export 7경로 중 하나로 명시된 서비스라 정책적 비중이 크다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `EnvironmentMetaService.update`(및 `FrameDescriptionService.update`) 진입부에 `DeidentReportGate` 판정을 추가한다. 게이트 배선을 서비스마다 손으로 다는 방식이 반복 누락의 원인이므로(메모리 `state-gate-single-entry-point-rule`), **`TaskModifiedEvent(exportRegenerated=true)` 를 발행하는 모든 서비스에 게이트를 강제하는 구조적 장치**(공통 진입점 또는 클래스패스 스캔 가드 테스트)를 함께 검토할 것.


### [E-ISSUE-01] TC-AUG-020 / TC-AUG-021 — in-flight 증강에 대한 REVIEWER accept 가 도착할 성공 콜백을 INFO 로 조용히 흡수(파생영상 미생성)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 외부 위탁이 진행 중(`LS_DATA_AUG_JOB` 비종결)인 증강행은 아직 결과가 없으므로 검수 대상이 아니다. REVIEWER 가 그 행에 accept 를 시도하면 거부(409)되거나, 최소한 뒤늦게 도착한 성공 콜백이 **폐기됐다는 사실이 운영자에게 드러나야** 한다(WARN/알림). "요청은 했는데 파생영상이 없다"가 조용히 성립해서는 안 된다.
- **현재 동작(이슈 내용)**:
  - `AugmentReviewService.loadOrThrow`(`AugmentReviewService.java:421-431`)는 **`RESL_` 접두만** 차단하고 위탁 진행 여부(`LS_DATA_AUG_JOB.JOB_STTS_CD`)를 전혀 보지 않는다.
    ```java
    private LsDataAug loadOrThrow(Long dataAugSn) {
        LsDataAug aug = repository.findById(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));
        if (aug.getAugTypeCd() != null && aug.getAugTypeCd().startsWith(LsDataAug.RESL_PREFIX)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "해상도 파생 결과는 검수 대상이 아닙니다.");
        }
        return aug;      // ← in-flight(PENDING + 비종결 job) 여부 미판정
    }
    ```
  - `LS_DATA_AUG.AUG_PROC_STTS_CD` 는 **성공/실패 콜백이 스스로 종결시키는 축**이다(`AugmentResultService.java:227` `String newStatus = success ? STTS_ACCEPTED : STTS_REJECTED;` → `:498 applyReviewStatus`). 실측으로도 `data_aug_sn=1·5·27` 모두 **`LS_DATA_AUG_RVW` 행 없이** ACCEPTED 로 종결됐다(검증 시작 시점 `ls_data_aug_rvw` = 0행).
  - 따라서 REVIEWER 가 in-flight PENDING 행을 accept 하면, 뒤늦게 도착한 성공 콜백은 non-PENDING 앵커에 막혀 `AugmentResultService.java:186-201` 로 흡수된다. 이때 **REJECTED 로 종결된 경우만 WARN 으로 가시화**하고, **ACCEPTED 로 종결된 경우는 `else` 분기의 INFO** 로 떨어진다.
    ```java
    if (!LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())) {
        if (outcome.success() && LsDataAug.STTS_REJECTED.equals(aug.getAugProcSttsCd())) {
            log.warn("[Webhook][Augment] success result discarded — aug already terminal(REJECTED) ...");
        } else {
            log.info("[Webhook][Augment] duplicate result skipped dataAugSn={} ... state={}", ...);  // ← ACCEPTED 경로
        }
        return AugmentApplyResult.DUPLICATE;
    }
    ```
  - 결과: **파생 RAW 영상은 만들어지지 않는데** 증강 이력 화면은 `status=COMPLETED`(전 행 terminal)로 표시되고, 로그에는 INFO 한 줄만 남는다. 코드 주석 스스로 "운영에서 '요청했는데 파생영상이 없다'의 유일한 단서"라고 적어 놓은 신호가 **accept 경로에만 없다.**
  - 창의 크기: 목 서버는 요청→콜백 약 2~8초라 실측 재현이 어렵지만, 실제 벤더(생성형 AI I2I, 프레임 N장)에서는 수 분~수십 분이며 그 사이 잡카드는 `REQUESTED/IN_PROGRESS` 로 노출되고 accept/reject 버튼이 활성이다(FE 는 `resolutionTypes` 만 숨긴다 — `AugmentReviewService.java:185-190`).
- **재현/확인 경로**:
  ```bash
  # 1) 요청 → 즉시(콜백 도착 전) PENDING 행 확인
  curl -s -X POST http://localhost:18081/api/v1/augments/request \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"videoIds":[<APPROVED rawSn>],"types":["RAIN"]}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select data_aug_sn, aug_proc_stts_cd from ls_data_aug order by data_aug_sn desc limit 1;"
  # 2) 콜백 도착 전 accept  (mock 은 ~2s 라 벤더 지연 시뮬레이션 필요)
  curl -s -X POST http://localhost:18081/api/v1/augments/<dataAugSn>/accept -H "Authorization: Bearer $RT"
  # 3) 이후 backend 로그 — WARN 이 아니라 INFO "duplicate result skipped" 만 남는지 확인
  docker logs klid-backend 2>&1 | grep -a "duplicate result skipped"
  # 4) 파생 RAW 미생성 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select raw_sn, orgnl_raw_sn from ls_data_raw where orgnl_raw_sn=<rawSn>;"
  ```
  ※ 정적 근거는 위 3개 스니펫으로 완결되며, 실측으로는 "콜백이 `LS_DATA_AUG` 를 자체 종결시킨다"(RVW 행 0개인 ACCEPTED 3행)와 "`loadOrThrow` 에 job 상태 판정이 없다"를 각각 확인했다.
- **영향**: 증강 산출물 무단 유실(요청 1건이 파생 0건으로 끝남) + 운영 가시성 결손. 보안 취약점은 아니나 **CWE-778(Insufficient Logging)** 성격의 관측 결손이며, 잘못된 `COMPLETED` 표시로 운영자가 재요청 시점을 놓친다. 게다가 ACCEPTED 는 `applyReviewStatus` 상 되돌릴 수 없어(TC-AUG-013 안내대로) **같은 (영상 × 종류) 재요청이 409 로 영구 차단**된다 — 복구 동선이 없다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**:
  ①`loadOrThrow` 에 "비종결 `LS_DATA_AUG_JOB` 존재 시 409(위탁 진행 중)" 게이트를 추가해 in-flight accept/reject 자체를 막거나,
  ②최소 조치로 `AugmentResultService` 의 `else` 분기 중 `outcome.success() && ACCEPTED && LS_DATA_AUG_RVW` 행이 존재(=사람이 먼저 종결)하는 경우를 **WARN 으로 승격**해 REJECTED 경로와 대칭을 맞춘다.
  ③또는 FE 잡카드에서 `status ∈ {REQUESTED, IN_PROGRESS}` 인 항목의 accept/reject 를 비활성화(현재 `resolutionTypes` 숨김과 같은 방식).


### [E-ISSUE-02] TC-AUG-033 — 실동선(콜백 종결) 증강의 `completedAt` 이 항상 `requestedAt` 으로 표시됨

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 잡카드가 `COMPLETED` 일 때 `completedAt` 은 실제 종결 시각을 나타내야 한다(카탈로그 기대값 `completedAt=max(RVW_DT)`). 요청 시각과 같은 값이 나오면 화면상 "요청 즉시 완료"로 보여 처리 소요시간·SLA·지연 탐지가 전부 무의미해진다.
- **현재 동작(이슈 내용)**: `AugmentReviewService.java:200-207`
  ```java
  LocalDateTime completedAt = null;
  if (status == AugmentJobStatus.COMPLETED) {
      completedAt = group.stream()
              .map(a -> reviewDtByAug.get(a.getDataAugSn()))
              .filter(java.util.Objects::nonNull)
              .max(Comparator.naturalOrder())
              .orElse(requestedAt); // 검수 일시 유실 시 요청 일시로 폴백(null 회피)
  }
  ```
  `reviewDtByAug` 는 `LS_DATA_AUG_RVW.RVW_DT` 에서만 채워진다(`loadReviewDates`, `:315-330`). 그런데 **정상 동선의 증강은 REVIEWER 가 아니라 성공 콜백이 종결시키므로 `LS_DATA_AUG_RVW` 행이 생성되지 않는다** — 검증 시작 시점 DB 실측에서 `ls_data_aug` 의 ACCEPTED 3행(`data_aug_sn=1,2,3`)에 대해 `ls_data_aug_rvw` 는 **0행**이었다. 결과적으로 `orElse(requestedAt)` 이 **예외가 아니라 기본 경로**가 된다.
  - 실측(2차, 목 실왕복 산물):
    - rawSn=126 잡카드 → `"requestedAt":"2026-07-31T02:54:36.675253", "completedAt":"2026-07-31T02:54:36.675253"` (완전 동일)
    - rawSn=136 잡카드 → `"requestedAt":"2026-07-31T03:44:25.589713", "completedAt":"2026-07-31T03:44:25.589713"` (완전 동일). 실제 콜백 종결은 03:44:33 경로 최소 8초 이후였다(mock 웹훅 로그).
  - 반대로 내가 합성 검수(REVIEWER accept/reject)를 넣은 그룹만 `completedAt`(`03:45:41.409933`) ≠ `requestedAt` 으로 정상 산출됐다 → **폴백이 정상 동선을 덮고 있음이 A/B 로 확인**됨.
- **재현/확인 경로**:
  ```bash
  # 증강 요청 → 콜백 완료 대기 → 잡카드 조회
  curl -s -H "Authorization: Bearer $RT" "http://localhost:18081/api/v1/augments?page=0&size=20"
  #  → 해당 그룹의 requestedAt 과 completedAt 이 동일한지 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select a.data_aug_sn, a.aug_proc_stts_cd, r.rvw_dt from ls_data_aug a
        left join ls_data_aug_rvw r on r.data_aug_sn=a.data_aug_sn;"
  #  → 콜백 종결분은 rvw_dt 가 NULL
  ```
- **영향**: 증강 이력 화면(SCR-AUG-002)의 완료 일시가 **전 건 오표시**. 외부 위탁 소요시간을 화면·통계 어디서도 알 수 없고, 장기 지연 위탁을 완료 건과 구분할 수 없다. 데이터 유실/보안 문제는 아니며 표시·운영 지표 결함이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 종결 시각의 진실원을 `LS_DATA_AUG_RVW.RVW_DT` 단일에 두지 말고, ①콜백 종결 시에도 `LS_DATA_AUG` 에 종결 시각을 적재하거나(현재 `DCSN_DT` 컬럼이 존재하나 어느 경로에서도 채워지지 않는다 — `applyReviewStatus` 는 상태만 바꾼다), ②`LS_DATA_AUG_JOB.MDFCN_DT`(SUCCEEDED 전이 시각)를 폴백 순위 1로 올리고 `requestedAt` 폴백은 최후로 내린다. 어느 쪽이든 "폴백이 기본 경로"인 현 구조를 깨는 것이 요지다.


### [E-ISSUE-22] TC-AUG-069·TC-AUG-071 — 벤더 산출물 `checksum` 미검증이 E-3 반입 경로에서 실제 파급을 낳는다 (G-ISSUE-44 의 E 클러스터 확증)

- **심각도**: **MEDIUM**
- **기대 동작(기대효과)**: 벤더가 `results[].checksum`(SHA-256)을 주는 이유는 **그 파일을 파생 프레임으로
  반입하기 전에 무결성을 확인**하라는 것이다. 반입 경로가 공유 NAS 라 전송 중단·부분 기록·교체가 실재한다.
- **현재 동작(이슈 내용)**: `GenAiCallbackRequest.ResultItem.checksum`(`webhook/dto/GenAiCallbackRequest.java:114-116`)
  은 **선언만** 되어 있고 소비 지점이 0건이다.

  `webhook/service/GenAiCallbackService.java:222-235`
  ```java
  List<String> paths = new ArrayList<>(results.size());
  for (GenAiCallbackRequest.ResultItem item : results) {
      try { artifactRootResolver.verifyExternalReadablePath(item.outputFilePath()); }
      ...
      paths.add(item.outputFilePath());     // checksum 미사용
  }
  ```
  검증: `grep -n "checksum\|sha256\|mediaMetadata" GenAiCallbackService.java AugmentResultService.java AugmentFrameProducer.java` → **0건**.

  **실동작**: 실험 B 에서 `checksum:"0000…0000"`(64자리 0)을 실었으나 어떤 경고·거부·메트릭도 발생하지 않고
  200 으로 반입 완료. 즉 **checksum 은 존재 여부조차 관측되지 않는다**.

  더불어 짝짓기가 **순서 의존**이다 — `GenAiCallbackService.applySucceeded(:186-189)` 가
  `LS_DATA_AUG_JOB_FILE`(FILE_SEQ 오름차순)과 `results[]` 를 인덱스로 대응시키고, 유일한 방어가
  **건수 일치**(TC-AUG-071)다. 우리가 `input_files[].checksum`·`source_file_id` 를 **송신하지 않으므로**
  (G-part3 3자 대조표 A) 결과에 되짚을 식별자도 없다. 건수가 같고 순서만 어긋난 페이로드는
  **검출 수단이 전혀 없으며**, 라벨 좌표는 그대로 복사되므로 프레임 N 에 프레임 M 의 증강본이 붙어도
  파이프라인 어디에서도 드러나지 않는다.
- **재현/확인 경로**: E-ISSUE-21 의 재현 스크립트(2단계에서 `checksum` 값을 임의로 넣어도 결과 동일).
  ```bash
  cd backend && grep -rn "checksum" src/main/java/kr/co/cudo/authoring/webhook/ | grep -v "/dto/"   # → 0건
  ```
- **영향**: CWE-345. 손상·부분 기록·교체된 산출물이 학습데이터로 확정된다. E-ISSUE-21 과 결합하면
  "경로도 신뢰, 내용도 미검증" 이라 반입 경로 전체에 진정성 방어가 없다.
- **수정 방향(제안)**: ①`applySucceeded` 의 경로 되붙이기 직전에 `checksum` 이 있으면 파일 해시와 대조하고
  불일치 시 `ERR_RESULT_CHECKSUM_MISMATCH` 로 job FAILED(기존 `ERR_RESULT_COUNT_MISMATCH` 와 동일 fail-closed 규약).
  ②`media_metadata.size_bytes` 를 DTO 에 선언해 실파일과 대조. ③중기적으로 `input_files[].source_file_id` 를
  송신하고 결과의 대응 필드로 짝지어 순서 의존을 제거. ⚠ **구현하지 않는다**(계약 변경 = 벤더 협의 대상).

---


### [E-ISSUE-41] TC-RESL-033 — 중복 해상도 파생 요청이 409 CONFLICT 가 아니라 **500 INTERNAL_ERROR** 로 나간다

- **심각도**: MEDIUM (API 계약/운영 — 클라이언트가 "중복"과 "서버 장애"를 구분할 수 없고 5xx 알람이 오발화)
- **기대 동작(기대효과)**: 케이스 기대는 `409("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")` 이며, 부분 유니크 인덱스가 막은 "이미 존재" 는 클라이언트 정정 가능한 조건이므로 4xx 여야 한다(`api-design.md`: 409 = 충돌/중복).
- **현재 동작(이슈 내용)**:
  - `ResolutionReservationPersister.java:106-109` 는 정확히 409 를 던진다.
    ```java
    } catch (DataIntegrityViolationException e) {
        throw new CustomException(ErrorCode.CONFLICT, "동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.");
    }
    ```
  - 그런데 `VideoResolutionService.createOne:203-208` 이 **모든 `RuntimeException` 을 삼켜** `FAILED` 결과로 바꾸고, `changeResolution:134-140` 이 "CREATED 가 하나도 없으면" `INTERNAL_ERROR`(500)로 승격한다. 요청 프리셋이 1개면 중복 = 전부 실패 = **500**.
  - [실동작] `POST /v1/videos/146/resolution {"presets":["RESL_720P"]}` 재요청 → `500 {"errorCode":"INTERNAL_ERROR","message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."}`. 동시 5요청 시에도 승자 1건 201 / **패자 4건 전부 500**.
  - 로그에는 원인이 남는다(`[Video][Resolution] derivative creation failed … reason=CustomException` + `all presets failed`), 즉 서버는 중복임을 알면서 5xx 로 응답한다.
  - 다중 프리셋 요청에서는 201 + 항목별 `FAILED` 로 나가므로(실측) 여전히 사유를 알 수 없다 — 응답에 사유 코드가 없다(CWE-209 회피 목적의 의도적 설계).
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/v1/videos/146/resolution \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'   # 1회차 201
  # 동일 명령 재실행 → 500
  ```
- **영향**: FE 가 "이미 만들어져 있음"을 안내하지 못하고 일반 오류로 처리한다. 운영 모니터링에서 정상 중복 클릭이 5xx 로 집계돼 알람 노이즈·SLO 오염. 보안 영향 없음.
- **수정 방향(제안)**: `createOne` 이 `CustomException` 의 `ErrorCode` 를 보존해(예: `CreatedDerivative` 에 추상 사유코드 필드 추가) `changeResolution` 이 "전부 실패이고 전부 CONFLICT" 면 409 로, 그 외 전부 실패면 500 으로 갈라 응답한다. 응답에는 경로·스택이 아닌 **사유 코드만** 싣는다. ⚠ **구현하지 않는다.**


### [E-ISSUE-43] TC-RESL-021 — 확정 실패한 파생은 **상태 조회 API 에서도 사라진다**(E-ISSUE-24 잔여)

- **심각도**: MEDIUM (관측성 — 1차 지적의 핵심 시나리오가 여전히 남음)
- **기대 동작(기대효과)**: 1차 E-ISSUE-24 의 요지는 "REVIEWER 가 파생 생성 성공/실패를 확인할 수 있어야 한다" 이고, TC-RESL-021 은 `DATA_STTS_CD=FAILED` → 조회 응답 `FAILED` 매핑을 기대한다.
- **현재 동작(이슈 내용)**:
  - 조회 API 는 신설됐고 `IN_PROGRESS`/`COMPLETED` 는 실동작으로 확인된다(`VideoResolutionService.statusOf:185-194`).
  - 그러나 **정상적인 실패 경로에서는 그 행이 남지 않는다.** `AsyncResolutionRunner.handleFailure:150-164` 가 `markRawDataFailed` 직후 `deleteFailedDerivativeRaw` 를 호출하고, 그 조건(파생·FAILED·프레임 0건)은 Phase A/B 실패의 **전형적 상태와 정확히 일치**한다(Phase C 는 같은 트랜잭션이라 프레임이 롤백된다). 결과적으로 `FAILED` 는 **cleanup 이 실패해 파일이 잔존한 경우에만** 조회된다(`:155-158`).
  - 코드가 이 트레이드오프를 스스로 인정한다 — `:115-118` "여기서부터는 파생 RAW·예약행이 정리되어 **실패 흔적이 DB 에 남지 않는다** … WARN 로그 + 메트릭이 유일한 관측 수단이다".
  - 즉 E-ISSUE-23(고아 누적 제거)과 E-ISSUE-24(실패 가시화)가 **서로를 상쇄**한다. 예약 단계 실패(중복 등)는 요청 응답에 `FAILED` 로 즉시 보이지만(실측), 그 응답을 놓치면 사후 조회 수단이 없다.
- **재현/확인 경로**: `POST /v1/videos/{approvedRawSn}/resolution` 로 확정 실패를 유발한 뒤 `GET /v1/videos/{rawSn}/resolution` → 해당 프리셋이 목록에 없음. 로그만 `[AsyncResolutionRunner] derivative discarded — finalize failed, reservation released rawSn=… dataAugSn=…` + 메트릭 `resolution.finalize.failed`.
- **영향**: REVIEWER 가 "3종 요청했는데 2종만 있다"를 화면에서 인지할 수 없다. 운영자는 로그/메트릭 접근이 필요하다. 보안 영향 없음.
- **수정 방향(제안)**: ①고아 RAW 를 삭제하는 대신 **경량 실패 이력 테이블**(또는 `LS_BATCH_PROC_LOG` 재사용)에 `(parentRawSn, preset, 사유코드, 시각)` 을 남기고 조회 API 가 그 이력을 합쳐 응답하거나, ②`GET /v1/videos/{rawSn}/resolution` 응답에 "요청됐으나 산출물 없음" 을 표현할 수 있도록 프리셋 3종을 항상 나열하고 미존재를 `NOT_CREATED` 로 표기한다. ⚠ **구현하지 않는다.**


### [E-ISSUE-62] TC-EXPORT-035 — 해상도 파생 export JSON 의 `image.width/height`(및 `video.width/height/resolution`)가 **실제 산출 이미지·라벨 좌표계와 불일치**

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 학습데이터 JSON 의 `image.width`/`image.height` 는 같은 폴더에 실제로 놓인 프레임 이미지의 픽셀 크기여야 한다. `annotations[].bbox`/`polygon` 좌표계와도 일치해야 COCO 계열 소비자가 정규화·검증을 할 수 있다. (`video.*` 기술메타가 부모와 같은 것은 CLAUDE.md 확정 정책상 정상이나, 그 정책은 **비디오 파일 기준**을 말한다.)
- **현재 동작(이슈 내용)**: 해상도 파생(rawSn 178, `AUG_TYPE_CD=RESL_720P`)의 export JSON 이 부모 해상도를 그대로 싣는다.

  ```java
  // NiaJsonBuilder.java:161-164  — image 블록의 크기 원천이 "영상 메타"다
  return new NiaImage(
          imageId,
          fileName,
          meta.getVdoWdth(),      // ← 1920 (부모 비디오 폭)
          meta.getVdoHgt(),       // ← 1080 (부모 비디오 높이)
          ...
  ```
  ```java
  // VideoMetaMapper.java:81-83 — video 블록도 동일 원천
          meta.getVdoWdth(), meta.getVdoHgt(), meta.getResl(),
  ```

  **실동작 근거**
  | 항목 | 값 |
  |---|---|
  | 실제 파일 `…/178/178/v1/deid/0001.jpg` 크기 | **1280 × 720** (JPEG SOF 파싱) |
  | 같은 폴더 `0001.json` `image.width/height` | **1920 / 1080** |
  | 〃 `video.width/height`, `video.resolution` | **1920 / 1080**, `"1920x1080"` |
  | 〃 `annotations[].bbox` (id 734) | `[1223.94, 352.50, 55.84, 74.18]` — x 최댓값 1279.x = **720p 좌표계** |
  | 부모 173 의 대응 라벨 (id 608) | `[1835.91, 528.76, 83.75, 111.27]` — **1080p 좌표계** (배율 1.5 정확히 대응) |
  | DB | `ls_data_aug.AUG_TYPE_CD='RESL_720P'`, `targetW/H=1280/720`, `LS_DATA_AUG_LBL_MAP` 좌표 재계산 적용 |

  즉 **이미지와 라벨은 720p 로 서로 정합**한데 **선언된 크기만 1080p** 다. `LS_DATASET_VIDEO_META` 에는 목표 해상도를 담을 필드가 없고(`DerivedMetaCopier` 가 부모 `video.*` 를 복사), 목표값은 `LS_DATA_AUG.AUG_TYPE_CD`/예약행에만 존재한다.

- **재현/확인 경로**
  ```bash
  BASE=http://localhost:18081/api
  curl -s -X POST "$BASE/v1/videos/{승인영상}/resolution" -H "Authorization: Bearer $REVIEWER" \
       -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'
  # 파생 rawSn 배정→검수제출→승인 후
  docker exec klid-backend sh -c 'grep -nE "\"width\"|\"height\"|resolution" \
       /app/storage/deidentified/videos/resolution/{parent}/{derived}/{derived}/v1/deid/0001.json'
  docker exec klid-backend python3 -c '...JPEG SOF 파싱...'   # 실제 1280x720
  ```
  ```sql
  SELECT aug_type_cd FROM ls_data_aug WHERE src_sn IN (SELECT src_sn FROM ls_data_src WHERE raw_sn={parent});
  SELECT point_cn FROM ls_data_lbl WHERE src_sn={파생 프레임};  -- 720p 좌표
  ```

- **영향**
  - 학습데이터셋 소비자가 `image.width/height` 로 좌표를 정규화하면 **1.5배 어긋난 박스**를 얻는다(x/1920 vs x/1280).
  - 해상도 변경 산출물(SFR-06-03)의 본래 목적인 "여러 해상도 학습데이터"가 메타상 구분되지 않는다 — 3종 파생 모두 `resolution="1920x1080"` 로 나간다.
  - `V_COMPLETED_VIDEO.RESL/VDO_WDTH/VDO_HGT` 도 같은 동결값을 쓰므로 관제 데이터마트에도 동일하게 전파된다.
  - 증강 파생(WINTER/NIGHT/RAIN)은 해상도를 바꾸지 않아 영향 없음 — **해상도 파생 전용 결함**이다.
  - CWE-1188(부정확한 초기화) 계열. PII 유출 방향 아님.

- **수정 방향(제안)** ⚠ **구현하지 않는다** — 정책 확정이 선행돼야 한다.
  1. **`image.width/height` 를 프레임 실측으로 분리**한다. `LS_DATA_SRC` 에 프레임 폭/높이 컬럼(표준용어 조합 필요)을 두고 추출·리스케일 시 적재해 `NiaJsonBuilder.buildImage` 가 그것을 쓰게 한다. 미측정이면 현행 video 메타 폴백(하위호환).
  2. **`video.width/height/resolution` 은 "비디오 파일 기준"이라는 확정 정책을 유지**하되(파생 비디오는 실제로 부모 복사본이므로 1920×1080 이 맞다), 관제 계약 문서에 그 의미를 명시하고 **목표 해상도를 별도 필드로 노출**하는 안을 검토한다(예: `AUG_TYPE_CD` 를 뷰/JSON 으로 전달).
  3. 회귀 가드: 해상도 파생 export IT 에서 `image.width == ffprobe(실파일).width` 와 `max(bbox.x) <= image.width` 를 단언.

---


### [E-ISSUE-83] TC-META-030 / TC-META-034 — 적재가 `PRVC_TYPE_CD='ANONY'` 상수를 쓰고 관제 원천을 읽지 않아 export `pseudonymity`/`privacy_included` 가 항상 `N`, 프레임 프리필 `anonymity` 가 항상 `Y` 로 고정
- **심각도**: MEDIUM (1차 E-ISSUE-42 의 잔여 축 — 코드에 "의도적 미해소"로 명시되어 있으나 산출물 오염은 실재)
- **기대 동작(기대효과)**: 개인정보 유형의 원천은 관제 `MNG_CLIP_EVNT_LST.PRVC_TYPE_CD` 다(메모리 `control-clip-meta-source-of-truth`). 원천을 읽지 못하면 `null`(미상)이어야 하고, 상수로 특정 값을 단정해서는 안 된다.
- **현재 동작(이슈 내용)**:
  - [실동작] `ls_data_raw` 전 영상 `prvc_type_cd='ANONY'`, `prvc_yn='N'`(47건 전수).
  - [정적] `TrainingVideoIngestTx.java:64-67,116-119`:
    ```java
    /** 비식별 유형 기본값. 전체 비식별 정책상 ANONY 로 적재한다(파이프라인이 무조건 비식별 수행). */
    private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_ANONY;
    ...
    LsDataRaw.createFromIngest(vmsClipId, clip.getVmsCctvId(), evntTypeCd, clip.getLclgvCd(), DEFAULT_PRVC_TYPE, ...)
    ```
  - 파급 — [실동작] 프레임 수동 입력이 없는 모든 프레임에서 `GET privacy-meta` → `{anonymity:"Y", pseudonymity:"N", privacyIncluded:"N"}`(srcSn 117 실측), export `image.pseudonymity="N"`, `image.privacy_included="N"`(rawSn 169 `v1` 실측). 관제 실값이 `PSDO`(가명)여도 그대로 `N` 이 나간다.
  - [정적] `MngClipEvntLst.java:28-32` 가 이 사실을 **미해소로 명기**: "관제 원천이 여기 실재하는데도 적재는 `DEFAULT_PRVC_TYPE` 하드코딩을 쓴다 … 코드도메인 확정 전에는 매핑 자체가 추정이 되므로 의도적으로 미해소로 둔다".
  - **본 환경 한계**: 로컬 `MNG_CLIP_EVNT_LST` 는 V63 stub(`EVNT_ID`,`EVNT_TYPE_CD`,`SHT_DT` 3컬럼)이라 `PRVC_TYPE_CD` 컬럼 자체가 없다 → 관제 실값과의 1:1 대조는 이 환경에서 **수행 불가**(값 불일치를 실증하지 못했고, 원천 미독 사실만 확증).
- **재현/확인 경로**:
  ```sql
  SELECT DISTINCT prvc_type_cd, prvc_yn, count(*) FROM ls_data_raw GROUP BY 1,2;   -- ANONY|N 만
  SELECT column_name FROM information_schema.columns WHERE table_name='mng_clip_evnt_lst';  -- 3컬럼(stub)
  ```
  운영 DB 에서는 `SELECT prvc_type_cd FROM MNG_CLIP_EVNT_LST WHERE evnt_id=?` 로 실값 대조 필요.
- **영향**: 학습데이터 속성(가명/개인정보 포함여부)이 사실과 다를 수 있고, 데이터마트가 이 속성으로 필터링하면 오염이 전파된다. 촬영환경 3필드는 "미상=null" 로 정리됐는데 이 축만 "상수 단정"이 남아 self-fill 금지 원칙의 유일한 잔존 위반이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①관제 코드도메인(ERD-024) 확보 → `MngClipEvntLst` 에 `PRVC_TYPE_CD` 매핑 추가 → 적재 시 관제 실값 우선, 부재면 `null` ②확보 전이라면 `DEFAULT_PRVC_TYPE` 을 `null` 로 바꾸고 파생 프리필도 `null`(미상)로 내려 "상수 단정"을 제거(촬영환경 3필드와 동일 처방) ③둘 다 어려우면 최소한 export 소비자가 구분할 수 있도록 출처 표기 추가.


### [E-ISSUE-03] TC-AUG-009 — 카탈로그 근거 라인 드리프트 (`AugmentRequestBridge.java:29-35,92-96`)

- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 근거 `file:line` 이 판정 대상 코드(리스너 선언·롤업 가드)를 직접 가리켜야 검증자가 즉시 대조할 수 있다.
- **현재 동작(이슈 내용)**: 카탈로그가 가리킨 `:29-35` 와 `:92-96` 은 모두 **javadoc 서술 구간**이다(`:29-35` = "request_id 발급 원장은 LS_DATA_AUG_JOB.IDMP_KEY 다" 절, `:92-96` = 메서드 javadoc). 실제 판정 대상은:
  ```java
  100:    @Async("batchAsyncExecutor")
  101:    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  102:    public void onAugmentRequested(AugmentRequestedItemEvent event) {
  103:        submitAndRollUpIfNothingSent(event);
  ```
  이며, "위탁 0건 → 실패 롤업" 가드는 `:120-138`(`rollUpFailureIfNothingInFlight`)이다. 파일이 Phase 7~8 주석 대폭 보강으로 밀린 것으로 보인다.
- **재현/확인 경로**: `grep -n "onAugmentRequested\|@Async\|TransactionalEventListener" backend/src/main/java/kr/co/cudo/authoring/augment/listener/AugmentRequestBridge.java`
- **영향**: 판정 결과에는 영향 없음(동일 파일 · 동일 클래스). 카탈로그 신뢰도·검증 속도만 저하.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 카탈로그 TC-AUG-009 근거를 `AugmentRequestBridge.java:100-103,120-138` 로 갱신.

---

### 참고 — 결함으로 보고하지 않은 관찰 (확정 정책·픽스처 산물)

| 관찰 | 왜 결함이 아닌가 |
|---|---|
| 신고 구간(`'F'`)에 요청은 412 로 거부되지만 콜백 인계는 계속 진행 | **확정 정책**(2026-07-29) — 파생 생성은 원본 신고와 무관, 차단 대상은 외부 위탁(요청 입구 + 전송 진입점)뿐. 실측에서도 `AugmentApplyResult.WITHHELD_*` 흔적·PENDING 고착 0건 확인 |
| 해상도 파생(130·131 계열 `RESL_*`)이 accept/reject 400 으로 막히면서 `GET /v1/augments`·집계에는 포함 | **확정 정책** — 내부 생성물이라 검수 대상 아님 / 통계 반영은 운영 결정. 실측 일치 |
| `LS_DATA_AUG.DCSN_USER_NO`·`DCSN_DT` 가 accept/reject 후에도 NULL | 코드 주석(`LsDataAug.java:262`)이 "상세 audit 컬럼은 `LS_DATA_AUG_RVW` 에서 관리"로 **명시 위임**. 응답 `decisionUserNo`/`decisionAt` 은 RVW 행에서 채워짐(실측 `1001`/타임스탬프) |
| 목록 응답에 동일 `jobId`(=RAW_SN) 잡카드 2장이 나온 사례 | **내 합성 픽스처 산물** — 실제 요청 경로는 항상 대표프레임 `MIN(SRC_SN)` 만 쓰므로(`findFirstSrcSn`) 한 RAW_SN 당 SRC_SN 그룹이 1개다. 정리 후 재현되지 않음 |
| 만료 스윕이 검증 중 내 합성 PENDING 행을 REJECTED 로 회수 | 정상 동작 — `AugmentJobExpiryTxService` 가 `job 0건 · 깨울 주체 없음` 고아 PENDING 을 회수(`reclaimed orphan pending augments count=3`). 신고 구간 제외 술어가 제거된 현 정책과 일치 |

---


### [E-ISSUE-23] TC-AUG-101 — `AugmentJobRecorder.markAccepted()` 프로덕션 호출자 0건 (구 E-ISSUE-32 와 동형의 dead code)

- **심각도**: **LOW**
- **기대 동작(기대효과)**: 카탈로그 TC-AUG-101 의 기대결과는 "응답 검증 3축 통과 시에만
  `markAccepted(외부 job_id)`" 다. 즉 그 메서드가 ACK 기록의 진입점이어야 한다.
- **현재 동작(이슈 내용)**: Phase C-3 논블로킹 전환에서 ACK 기록이
  `AugmentSubmitOutcomeRecorder.onAccepted(:45-49) → AugmentJobRecorder.markSubmitAccepted(:73-76)`
  (조건부 원자 UPDATE `claimSubmitAck`, 술어 `JOB_STTS_CD IN ('RECEIVED','RUNNING')`)로 이관됐는데
  **구 메서드가 그대로 남았다.**

  `augment/service/AugmentJobRecorder.java:59-62`
  ```java
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
  public void markAccepted(Long augJobSn, String externalJobId) {
      jobRepository.findById(augJobSn).ifPresent(job -> job.markAccepted(externalJobId));
  }
  ```
  호출자 grep 결과 — `src/main/java` **0건**, `src/test/java` 5건(전부 테스트 픽스처가 엔티티 메서드를 직접 호출).
  이 메서드는 **무조건 덮어쓰기**라 콜백이 ACK 보다 먼저 도착한 경우 SUCCEEDED job 을 RECEIVED 로
  강등시킨다 — `markSubmitAccepted` 의 javadoc 이 정확히 그 위험 때문에 조건부 UPDATE 를 도입했다고 적고 있다.
  즉 **되살리면 안 되는 구현이 살아 있는 상태**이며, 1차 E-ISSUE-32(`markDeadLetter` 호출자 0건)와 같은 패턴이다.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "markAccepted(" src/main/java   # → 정의 2곳뿐, 호출 0건
  ```
- **영향**: 기능 결함은 없다(현재 아무도 부르지 않으므로). 다만 ①카탈로그·리뷰어가 죽은 경로를 근거로
  삼게 되고(TC-AUG-101 기대결과가 실제로 이 메서드를 지목한다) ②향후 누군가 "간단한 쪽"을 호출해
  ACK↔콜백 레이스 강등을 재도입할 여지가 남는다.
- **수정 방향(제안)**: `AugmentJobRecorder.markAccepted` 를 삭제하고 테스트 픽스처는
  `LsDataAugJob.markAccepted`(엔티티 메서드)를 직접 쓰게 둔다. 카탈로그 TC-AUG-101 근거를
  `HttpExternalAugmentClient.java:164-181` + `AugmentJobRecorder.java:73-76` 으로 정정한다.
  ⚠ **구현하지 않는다**.

---


### [E-ISSUE-24] TC-AUG-067 — ACK 미수신 job 은 `job_id` 오배송 검사가 스킵되고 **첫 콜백이 실은 임의 job_id 가 그대로 앵커로 적재**된다

- **심각도**: **LOW**
- **기대 동작(기대효과)**: TC-AUG-067 은 "202 로 받아 둔 `externalJobId` 와 콜백 `job_id` 가 다르면 409" 다.
  오배송 방어의 근거는 "외부가 발급한 job_id 만 신뢰한다" 이다.
- **현재 동작(이슈 내용)**: 검사가 `externalJobId != null` 로 게이팅돼 있어, **ACK 가 아직 기록되지 않은
  job**(논블로킹 제출에서 콜백이 ACK 보다 먼저 도착하는 정상 구간, 또는 ACK 자체가 유실된 구간)은
  어떤 `job_id` 든 통과한다.

  `webhook/service/GenAiCallbackService.java:122-126`
  ```java
  if (target.getExternalJobId() != null && !target.getExternalJobId().equals(req.jobId())) {
      ... throw new CustomException(ErrorCode.CONFLICT, "job_id 가 일치하지 않습니다.");
  }
  ```
  그리고 이어지는 `target.markRunning(req.jobId())` / `markSucceeded(req.jobId())` 가 그 값을 그대로
  `OTSD_JOB_ID` 에 적재하므로, **첫 콜백이 실은 값이 사후 앵커가 된다.**

  **실동작(시나리오 24)**: `OTSD_JOB_ID` 가 비어 있는 job 4 에 `job_id=anything-goes` 를 보내면
  400/409 없이 **200** 으로 처리된다(해당 job 이 이미 terminal 이라 상태 변화는 없었다).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' \
    -d '{"request_id":"AUG-271a61ab-79b3-41e0-a446-36397bfb3ab0","job_id":"anything-goes","status":"SUCCEEDED",
         "results":[{"generated_data_id":"g1","media_type":"IMAGE","output_file_path":"/app/genai-out/x.jpg"}]}'
  # → HTTP 200 (job_id 불일치 검사 미수행)
  ```
- **영향**: 실질 위험은 낮다 — 진입에는 우리가 발급한 128비트급 `request_id`(UUID 기반) 가 필요하고,
  그 값을 아는 주체는 벤더뿐이다. 다만 ①"발급 request_id + 임의 job_id" 조합으로 **다른 증강이 나중에
  쓸 job_id 를 선점**해 그 증강을 409 로 영구 고착시키는 경합이 이론적으로 가능하고
  ②`OTSD_JOB_ID` 가 "외부가 발급한 식별자" 라는 계약이 데이터 수준에서 보장되지 않는다.
- **수정 방향(제안)**: ACK 미수신 구간에서는 `job_id` 를 앵커로 승격하지 말고 별도 컬럼(또는 `null` 유지)에
  보관한 뒤, `claimSubmitAck` 가 도착했을 때 대조해 불일치면 job 을 FAILED 로 종결한다.
  ⚠ **구현하지 않는다**.

---


### [E-ISSUE-25] TC-AUG-068 — RUNNING 진행 콜백에 **순서 검증이 없다**(progress 역전 수용)

- **심각도**: **LOW**
- **기대 동작(기대효과)**: 카탈로그 TC-AUG-068 은 "job `JOB_STTS_CD=RUNNING` 갱신, 200 `applied:true`,
  결과 처리·롤업 미수행" 만 요구하므로 **케이스 자체는 PASS** 다. 다만 계약(v1.1)이 진행 웹훅을
  10 → 50 → 90 순으로 정의하고 있어, 네트워크 재정렬·재시도로 역순 도착 시의 규약이 정의돼 있지 않다.
- **현재 동작(이슈 내용)**: `GenAiCallbackService.java:136-142` 는 `progress` 를 **로그로만** 쓰고
  저장·비교하지 않는다(엔티티에 progress 컬럼 없음). 실측: 같은 job 에 `progress=50` → `progress=10` 을
  연속 전송해도 둘 다 200 `applied:true` 로 수용된다.
- **재현/확인 경로**: 시나리오 13(위 표) 참조.
- **영향**: 현재는 무해하다 — 진행률을 어디에도 저장하지 않으므로 역전이 상태를 왜곡하지 않는다.
  다만 향후 진행률을 UI/집계에 노출하면 그대로 역행 표시가 된다. **기록 목적의 관측 항목**이다.
- **수정 방향(제안)**: 진행률을 노출할 계획이 생기면 `LS_DATA_AUG_JOB` 에 `PRGS_RT` 를 추가하고
  단조 증가 조건부 UPDATE 로만 갱신한다(표준용어 확인 선행). ⚠ **구현하지 않는다**.

---


### [E-ISSUE-42] TC-RESL-066 — `deleteFailedDerivative` DELETE 문에 `DE_IDENT_YN <> 'Y'` 조건이 빠져 javadoc 의 "동일 조건 동봉" 서술과 불일치

- **심각도**: LOW (문서·구현 불일치 / 심층방어 결손 — 현재 실피해 경로는 확인되지 않음)
- **기대 동작(기대효과)**: `ResolutionPersistService.java:230-233` javadoc — "다음 중 하나라도 어긋나면 삭제하지 않는다 — ①파생이 아님 ②이미 확정(deIdntfYn='Y') ③상태가 FAILED 가 아님 ④프레임이 이미 적재됨. **최종 DELETE 문에도 동일 조건을 SQL 조건으로 함께 걸어**, 검사~삭제 사이에 상태가 바뀐 행은 0건 삭제된다."
- **현재 동작(이슈 내용)**: `video/repository/VideoRepository.java:445-453`
  ```sql
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  ```
  4조건 중 **②`DE_IDENT_YN <> 'Y'` 가 없다.** 그 조건은 Java 선검사(`ResolutionPersistService.java:246-249`)에만 존재한다.
- **재현/확인 경로**: `grep -n "deleteFailedDerivative" -A10 backend/src/main/java/kr/co/cudo/authoring/video/repository/VideoRepository.java`
- **영향**: 현실적 위험은 낮다 — ①선검사와 DELETE 가 같은 `REQUIRES_NEW` 트랜잭션 안에서 `findByRawSnForUpdate` 잠금을 쥔 채 연속 실행되고 ②확정 경로(`persist:138-139`)가 `markDeidentified('Y')` 와 `markCompleted()` 를 항상 함께 수행하므로 `'Y'` 인 행은 `DATA_STTS_CD='FAILED'` 조건에서 이미 걸러진다. 다만 향후 상태 전이가 분리되면 조용히 깨지는 형태의 결손이고, **javadoc 이 사실과 다르다**는 점이 남는다.
- **수정 방향(제안)**: DELETE 문에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가하거나, javadoc 을 "SQL 은 3조건, `'Y'` 는 FAILED 조건이 함의" 로 정정한다. ⚠ **구현하지 않는다.**


### [E-ISSUE-44] TC-RESL-001 — `VMS_CLIP_ID` 의 `_RESL_RESL_` 이중 접두 드리프트가 신규 생성분에도 그대로 남아 있다 (1차 E-ISSUE-25 미해소)

- **심각도**: LOW (데이터 품질 — 기능 영향 없음)
- **기대 동작(기대효과)**: 파생 식별자에 프리셋 코드가 1회만 들어간다.
- **현재 동작(이슈 내용)**: `video/entity/LsDataRaw.java:226`
  ```java
  raw.vmsClipId = parent.getVmsClipId() + "_RESL_" + goalResCd + "_" + System.currentTimeMillis();
  ```
  `goalResCd` 가 이미 `RESL_720P` 이므로 결과가 `…_RESL_RESL_720P_…` 가 된다. [실동작] 본 검증 신규 생성분 전건 — `DEV-CLIP-9502_RESL_RESL_720P_1785437782919`, `DEV-CLIP-9509_RESL_RESL_480P_…`.
- **재현/확인 경로**: `select vms_clip_id from ls_data_raw where orgnl_raw_sn is not null and vms_clip_id like '%RESL%';`
- **영향**: 기능 영향은 **없다** — `AugTypeParser`(`video/util/AugTypeParser.java:64-80`)가 "가장 오른쪽 마커 뒤 토큰" 규칙으로 이중/구형(`_RES_RES_`) 접두를 모두 정규화하고, 회귀 테스트(`AugmentResolutionResultTest:217/:244`)도 있다. 남는 것은 ①식별자 가독성 ②`VMS_CLIP_ID` 를 사람이 읽는 감사·운영 문서에서의 혼란 ③파서를 계속 유지해야 하는 부채다.
- **수정 방향(제안)**: 접두를 `"_"` 로 바꾸거나(신규 생성분만 정상화, 파서는 하위호환용으로 존치), 파생 종류 판별을 `LS_DATA_AUG.AUG_TYPE_CD` 조인으로 일원화해 문자열 파싱을 폐지한다. ⚠ **구현하지 않는다.**


### [E-ISSUE-45] TC-RESL-064/065/067/068 — 확정·정리 가드 4종에 전용 테스트가 없다 (1차 E-ISSUE-29 의 잔여 축)

- **심각도**: LOW (검증 신뢰도)
- **기대 동작(기대효과)**: 상태 전이·정리 가드마다 실행 경로를 고정하는 테스트가 있어야 회귀가 감지된다. 1차 E-ISSUE-29 가 지목한 3건(procLog 부재 404 / 중복 videoFrameNo / mtime 교체)은 이번 회차에 **전부 신설·해소**됐다(`ResolutionSnapshotServiceTest:238,:256` · `ResolutionPersistServiceTest:252`).
- **현재 동작(이슈 내용)**: 아래 4건은 여전히 단언하는 테스트가 없다.
  1. **TC-RESL-064** `LsDataAug.buildResolution:227-231` 의 `RESL_` 접두 400 — 테스트에서 팩토리는 정상값으로만 호출된다(`grep createResolutionPending` 결과 12곳 전부 `AUG_RESL_720P`).
  2. **TC-RESL-065** `LsDataAug.markResolutionGenerated:253-256` 의 이중 전이 409 — `@DisplayName` 전수 스캔 결과 대응 테스트 0건.
  3. **TC-RESL-067** `AsyncResolutionRunner:155-158` "파일 잔존 시 RAW 보존" — `AsyncResolutionRunnerTest` 에 `deleteFailedDerivativeRaw` 를 `verify`/`never()` 로 단언하는 케이스가 **0건**(`:128` 은 `cleanupFailed` 메트릭만 본다).
  4. **TC-RESL-068** `ResolutionFileMaterializer:119-138` 레거시 raw base 정리 — `ResolutionFileMaterializerTest` 가 `storageRawPath` 를 주입하지 않아 이 블록이 **항상 `IllegalStateException` 으로 skip** 된다(테스트에서 한 번도 실행되지 않는다).
- **재현/확인 경로**:
  ```bash
  grep -rn "deleteFailedDerivativeRaw" backend/src/test/java/kr/co/cudo/authoring/video/AsyncResolutionRunnerTest.java   # 0건
  grep -rn "storageRawPath" backend/src/test/java/kr/co/cudo/authoring/video/ResolutionFileMaterializerTest.java          # 0건
  grep -rn "markResolutionGenerated" backend/src/test/java/kr/co/cudo/authoring                                           # 단언 0건
  ```
- **영향**: 결함이 CI 를 통과한다. 특히 ③은 "고아 파일 방지"라는 안전 불변식이고 ④는 원본 저장소에 PII 사본이 잔존하는 것을 막는 정리 로직이라, 회귀 시 조용히 무력화된다.
- **수정 방향(제안)**: 각각 ①비-RESL 코드 400 ②ACCEPTED 재전이 409 ③`cleanup=false` 일 때 `verify(persistService, never()).deleteFailedDerivativeRaw(any())` ④`storageRawPath` 주입 후 `{rawBase}/resolution/{newRawSn}` 삭제·`{rawBase}/resolution` 루트 보존 단언을 추가한다. ⚠ **구현하지 않는다.**


### [E-ISSUE-46] TC-RESL-044/045 — 파생 라벨이 AI 메타(`LS_DATA_LBL_AI_INFO`)·등록자를 승계하지 않아 오토라벨 산출물이 수동 라벨처럼 보인다

- **심각도**: LOW (데이터 계보 — 현재 export/뷰 소비 경로에는 영향 없음)
- **기대 동작(기대효과)**: 파생영상은 부모 라벨의 **복사본**이므로, 그 라벨이 AI 가 만든 것인지(자동여부·신뢰도·출처)가 함께 따라가야 계보가 유지된다. 같은 리포의 버전 롤백 정책은 이 점을 명시적으로 요구한다(CLAUDE.md — "`LBL_SN`·AI 메타(`AUTO_LBL_YN`/신뢰도/출처)·`TRCK_ID` 까지 보존 복원").
- **현재 동작(이슈 내용)**: `batch/entity/LsDataLbl.java:333-348 copyForNewSrcScaled` 가 복사하는 필드는 `srcSn / lblTypeCd / labelId / labelNm / pointCn / trackId` 6개뿐이다.
  - `LS_DATA_LBL_AI_INFO`(별도 테이블)는 어느 파생 경로에서도 복사되지 않는다. [실동작] 부모 156 의 `ls_data_lbl_ai_info` **7행** ↔ 파생 174 **0행**.
  - `REG_USER_NO` 도 빌더에 없어 파생 라벨은 전부 `null`(실측).
  - 증강 경로(`webhook/service/AugmentExtractPersist.java:125` → `LsDataLbl.copyForNewSrc:300-307`)도 **동일하게 미복사**라 두 파생 경로 사이의 드리프트는 아니다. `:297-298` 주석은 "Transient 필드(autoLblYn, confScore, lblSrcCd)는 DB 미저장이므로 복사 대상 아님" 이라고만 서술하고, 별도 테이블인 `LS_DATA_LBL_AI_INFO` 는 언급하지 않는다.
- **재현/확인 경로**:
  ```sql
  select 'parent' , count(*) from ls_data_lbl_ai_info i join ls_data_lbl l on l.lbl_sn=i.data_lbl_sn
    join ls_data_src d on d.src_sn=l.src_sn where d.raw_sn=156
  union all select 'deriv', count(*) from ls_data_lbl_ai_info i join ls_data_lbl l on l.lbl_sn=i.data_lbl_sn
    join ls_data_src d on d.src_sn=l.src_sn where d.raw_sn=174;    -- 7 vs 0
  ```
- **영향**: 현재 export JSON 빌더는 자동여부·신뢰도를 내보내지 않으므로(`grep autoLblYn|confScore backend/.../dataset/export/` 0건) **학습데이터 산출물에는 영향이 없다.** 남는 영향은 ①파생영상 라벨링 화면에서 AI 라벨/수동 라벨 구분 불가 ②품질 통계(오토라벨 비율)에서 파생분이 전량 수동으로 집계 ③향후 export 계약에 자동여부가 추가되면 조용히 오표기된다.
- **수정 방향(제안)**: 파생 라벨 복사 시 `LS_DATA_LBL_AI_INFO` 를 함께 복제(신뢰도·출처 유지, `LBL_SRC_CD` 는 그대로 두거나 `DERIVED` 파생값 추가)하고 `REG_USER_NO` 를 승계한다. 증강·해상도 두 경로가 같은 헬퍼를 쓰므로 한 곳만 고치면 된다. **또는** "파생 라벨은 계보를 승계하지 않는다"를 명시적 계약으로 문서화한다. ⚠ **구현하지 않는다.**

---


### [E-ISSUE-84] TC-META-017 — `EnvironmentMetaController` Swagger 설명이 폐기된 구 정책("편집은 export 재생성을 트리거하지 않음")을 그대로 유지해 실동작과 정면 배치
- **심각도**: LOW (문서 드리프트 — 외부 계약 문서에 노출)
- **기대 동작(기대효과)**: 공개 API 문서(Swagger)는 실동작과 일치해야 한다. 2026-07-27 확정 정책(C-1b)은 "APPROVED 후 촬영환경 수정 = export 새 버전 `v{n+1}` 전량 재생성"이다.
- **현재 동작(이슈 내용)** [정적] `EnvironmentMetaController.java:66-68`:
  ```java
  + "검수 완료 후 수정 시 동결 스냅샷만 재동결(데이터마트 뷰에 최신값 반영)되고 관제 "
  + "TASK_MODIFIED(META_UPDATED) 통지가 발행된다. 편집은 export 파일 재생성을 트리거하지 않으며(라벨 수정과 동일 정책), "
  + "export 폴더는 다음 검수 승인 시점에 전량 재산출된다. WORKER 는 본인 배정 영상만.")
  ```
  [실동작] 실제로는 rawSn 168 PUT 이 60초 내에 `v2` 를 전량 재생성했다(TC-META-017 근거). 즉 **"트리거하지 않는다"·"다음 검수 승인 시점에 재산출"이 둘 다 거짓**이다. 서비스 Javadoc(`EnvironmentMetaService.java:76-78,145-150`)은 신정책으로 정확히 갱신돼 있어 컨트롤러 설명만 뒤처졌다.
- **재현/확인 경로**: `GET /swagger-ui/index.html` → EnvironmentMeta → PUT 설명 문구 ↔ `ls_dataset_export` 버전 증가 실측 대조.
- **영향**: FE·관제·감리가 Swagger 를 계약 정본으로 읽으면 "수정해도 파일은 안 바뀐다"고 오판해, 데이터마트 동기화 요구의 충족 여부를 잘못 판단한다. 폐기 케이스 `TC-META-009` 와 같은 문장이라 폐기 정책이 문서에 살아남은 형태다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 해당 3줄을 "검수 완료 후 수정 시 동결 스냅샷 재동결 + **export 새 버전 폴더로 전량 재생성** 후 관제 `TASK_MODIFIED(META_UPDATED)` 통지(통지는 export 성공 후)" 로 교체.


### [E-ISSUE-85] TC-META-002 — 조회 프리필(DERIVED)의 MANUAL 승격을 BE 가 막지 못해, FE 이외 클라이언트는 추정값을 출처 구분자 없이 동결·export 로 밀어넣을 수 있다
- **심각도**: LOW (코드에 "알려진 한계"로 명시 · 현 FE 는 규율 준수 확인)
- **기대 동작(기대효과)**: self-fill 금지의 보증은 서버가 져야 한다. 동결·export 에는 출처 구분자가 없으므로("동결된 non-null = 전부 수동값" 단언에 의존), 추정값이 수동값으로 승격되는 경로가 서버 차원에서 닫혀 있어야 한다.
- **현재 동작(이슈 내용)**:
  - [정적] `EnvironmentMetaService.java:85-91` 이 한계를 명문화: "BE 는 전송값의 출처를 알 수 없어 DERIVED 프리필의 MANUAL 승격을 **막지 못한다** … 이 API 는 공개 계약이라 다른 클라이언트는 프리필을 그대로 되돌려 보내 추정값을 수동값으로 승격시킬 수 있다".
  - [실동작] 재현 확인: 145 GET → `timeOfDay:"NGT"(DERIVED)` → 같은 값을 그대로 PUT → 응답 `timeOfDaySource:"MANUAL"`, DB `day_ngt_cd='NGT'` 저장. 이후 승인되면 그 추정값이 그대로 동결·export 된다.
  - 완화 확인 — FE 는 규율을 지킨다: `frontend/src/features/label/components/EnvironmentMetaPanel.tsx:64-112` `resolveField(…, source)` 가 손대지 않은 DERIVED 필드를 `null` 로 전송.
- **재현/확인 경로**: 위 GET→그대로 PUT→`SELECT day_ngt_cd FROM ls_data_raw WHERE raw_sn=145;`
- **영향**: E-ISSUE-42 가 닫은 self-fill 경로가 클라이언트 규율에만 의존해 재개방될 수 있다. 현 시점 실제 오염은 관측되지 않았다(모든 동결 스냅샷 3필드 null).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①요청에 항목별 `source` 축을 추가해 `MANUAL` 만 저장(계약 변경) ②또는 저장 시 "전송값 == 현재 파생 프리필값 && 기존 수동값 없음" 이면 수동값으로 승격하지 않고 null 유지 — 둘 다 하위호환 영향이 있으므로 FE 계약과 함께 결정.


### [E-ISSUE-86] TC-META-020 — 촬영환경 정정 백필의 **실행 경로**(재동결→재export→통지, `max-per-run` 상한)가 대상 0건이라 실동작으로 확증되지 않음
- **심각도**: LOW (검증 커버리지 공백 — 제품 결함 아님)
- **기대 동작**: `POST /v1/dev/dataset-video-meta/shooting-env-corrections` 1회 실행이 대상 최대 200건을 정정하고, 정정 1건마다 재동결 → `TaskModifiedEvent(exportRegenerated=true)` → export 새 버전 재생성 → 통지가 이어져야 한다. 재호출은 자연 멱등(0건).
- **현재 상태** [실동작]: 현 스택은 2026-07-31 재구축분이라 "파생 폐기 이전에 동결된 레거시 행"이 없다 — `GET .../shooting-env-correction-targets` → `{"targetCount":0}`, 판별식 SQL 직접 실행도 0건. 따라서 `POST` 는 `{"corrected":0,"remaining":0,"completed":true}` 만 반환하고 루프 본문에 진입하지 않았다(로그도 `no target`). API 형태(sub-resource 분리 · REVIEWER 전용 · 멱등 응답)와 인가(403/401)는 실동작 확인 완료.
- **재현/확인 경로**: 레거시 상태를 인위로 만들려면 `ls_dataset_video_meta` 활성행의 `day_ngt_cd/sesn_cd` 를 직접 UPDATE 해야 하는데, ①`SNPSHT_HASH` 멱등 계약이 깨지고 ②동시 검증 중인 타 에이전트의 영상을 오염시키므로 **의도적으로 수행하지 않았다**.
- **영향**: 상한 소진·부분 진행·실패 격리 동작이 실환경에서 미확증. 정적 근거(`DatasetVideoMetaBackfillService.java:161-215`, `DatasetVideoMetaEnvCorrectionTx.correct:60-88`)와 IT 6건(`DatasetVideoMetaEnvCorrectionIT`)은 갖춰져 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 레거시 동결행이 실재하는 dev(246) 스냅샷에서 별도 회차로 재검증하거나, 검증 전용 시드(파생값으로 동결된 영상 1건)를 `dev-seed.sql` 에 추가하는 방안 검토.


## F 클러스터 — 19건

### [F-ISSUE-03] TC-PORTAL-060 / 061 / 072 — 포털 채널에 SAM2 분할·추적이 노출돼 있다(정책 위반)

- **심각도**: HIGH (구 CRITICAL 에서 하향 — 아래 "완화된 축" 참조)
- **기대 동작(기대효과)**: `CLAUDE.md` "포털 (외부 채널)" 절 + ADR-013 — **오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공.** 포털 사용자는 데이터마트 영상 선택 + 기존 라벨 확인·수정·저장 + 본인 자산 수동 라벨링(BBOX/POLYGON)만 가능해야 한다. 따라서 포털 SAM2 엔드포인트와 FE 도구바 버튼은 **존재하지 않아야** 한다.
- **현재 동작(이슈 내용)**: 전용 컨트롤러·서비스가 살아 있고 실제로 추론이 나간다.
  - `backend/src/main/java/kr/co/cudo/authoring/portal/controller/PortalSam2Controller.java:43,53-78`
    ```java
    @RequestMapping("/v1/portal/frames")
    @PostMapping("/{srcSn}/sam2-track")   @PreAuthorize("hasRole('PORTAL_USER')")
    @PostMapping("/{srcSn}/sam2-segment") @PreAuthorize("hasRole('PORTAL_USER')")
    ```
  - `PortalSam2Service.java:108-190` — `aiServerClient.segment/track` 실호출.
  - FE `frontend/src/features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:109-124` 는 노출을 **회귀 가드로 고정**한다:
    ```ts
    expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'AI 추적' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 탐지' })).toBeNull();   // YOLO만 숨김
    ```
  - [실동작] `POST /v1/portal/frames/130/sam2-segment` → **200**, polygon 1139점 / score 0.9027 / 2.77s, `docker logs klid-ai-server` 에 backend 컨테이너 IP(`172.18.0.5`) 발신 `POST /infer/sam2/segment 200 OK`. `sam2-track` 도 200(tracked 2건).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
    -d '{"srcSn":130,"points":[[100,100]]}' \
    localhost:18081/api/v1/portal/frames/130/sam2-segment | jq '{n:(.data.polygon|length), score:.data.score}'
  # → {"n":1139,"score":0.9027518630027771}
  docker logs klid-ai-server --since 5m | grep infer/sam2
  ```
- **영향**: ①**계약 위반** — 사업 산출물(요구사항정의서·ADR-013)에 없는 기능이 외부 채널에 열려 있어 범위·감리 정합이 깨진다. ②**자원 축** — 외부 사용자가 내부 추론 서버(CPU/GPU)를 직접 구동시킨다(격리는 되어 있으나 소비는 실제로 발생). ③FE 테스트가 노출을 고정하고 있어 "실수로 열린 것"이 아니라 **되돌리려면 테스트까지 함께 뒤집어야 하는 상태**다.
  **완화된 축(07-30 반영)** — PII 유출(CWE-359)은 닫혔다: 전송 픽셀이 비식별본 전용이고(원본 폴백 시 404 실증), 신고 구간은 412 로 ai 호출 0건이며, 결과는 DB 에 저장되지 않아 데이터마트 오염도 없다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 정책을 유지한다면 `PortalSam2Controller`/`PortalSam2Service`/포털 도구바 3버튼을 제거하고 FE 회귀 가드를 "미노출" 단언으로 반전한다. 반대로 이 기능을 유지하기로 한다면 **ADR-013 을 개정해 "포털 SAM2 는 제공(단 persist 없음·비식별본 전용)" 을 명문화**하고 `CLAUDE.md` "오토라벨링(YOLO/SAM2) 미제공" 문장을 "YOLO 파이프라인 오토라벨만 미제공" 으로 정정해야 한다. **둘 중 하나를 사용자가 결정하기 전에는 코드도 문서도 건드리지 않는다** — 현재는 코드와 정본 문서가 서로 다른 말을 하는 상태 자체가 결함이다.


### [F-ISSUE-01] TC-PORTAL-023/024 인접 — `GET /v1/portal/datamart/labels` 가 APPROVED 게이트·비식별 신고 게이트를 **둘 다** 통과시킨다 (형제 엔드포인트 미배선)

- **심각도**: HIGH
- **기대 동작(기대효과)**: 포털은 **외부 채널**이므로 데이터마트 노출(`LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`) 영상의 데이터만 볼 수 있어야 한다(`PortalLabelService` 의 다른 3경로와 동일). 또한 비식별 누락 신고 구간(`DE_IDNTF_YN='F'`)에서는 **라벨 좌표가 PII 위치 특정 정보**이므로 412 로 차단돼야 한다(CLAUDE.md "차단 범위 ④ 포털 프레임 라벨").
- **현재 동작(이슈 내용)**: `PortalLabelService.loadDatamartLabels` 에 **어떤 게이트도 없다**. `rawSn` 을 그대로 받아 내부 파이프라인 라벨 테이블(`LS_DATA_LBL`)을 전량 반환한다.

  `backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalLabelService.java:99-116`
  ```java
  public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size) {
      if (rawSn == null) { throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다."); }
      int clampedSize = Math.min(Math.max(size, 1), 100);
      int clampedPage = Math.max(page, 0);
      List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);   // ← isExposedToDatamart 없음
      ...                                                          // ← accessGuard.requireNotUnderDeidentReport 없음
  ```
  같은 클래스의 형제 3경로는 전부 배선돼 있다 — `loadFrameLabels`(`:257-260` APPROVED + `:267` 신고), `serveFrameImage`(`:436-439` + `:443`), `saveUserLabel`(`:203-206` APPROVED). **이 한 메서드만 빠졌다.**

  [실동작] 실측:
  | rawSn | 상태 | `de_ident_yn` | 응답 | 반환 라벨 |
  |---|---|:--:|:--:|---:|
  | 133 | **ASSIGNED**(미승인) | **F**(신고 OPEN) | **200** | **28건** |
  | 135 | ASSIGNED | Y | 200 | 3건 |
  | 143 | ASSIGNED | Y | 200 | 14건 |
  | 126 | APPROVED | Y | 200 | 22건 |

  rawSn=133 은 **미승인 + 신고 OPEN** 이므로 두 게이트 모두에 걸려야 하는데 좌표가 그대로 나간다. 같은 rawSn 의 프레임 단위 경로(`GET /v1/portal/frames/80/labels`)는 정상적으로 403 이다 — 즉 **정책은 있고 이 엔드포인트만 정책 밖에 있다.**
- **재현/확인 경로**:
  ```bash
  PT=$(curl -s -X POST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  # 미승인 + 신고 OPEN 영상의 내부 라벨이 외부 채널로 200 반환
  curl -s -H "Authorization: Bearer $PT" \
    'localhost:18081/api/v1/portal/datamart/labels?rawSn=133&page=0&size=100' | jq '.data | length'   # → 28
  # 대조: 같은 영상 프레임 경로는 403
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $PT" \
    localhost:18081/api/v1/portal/frames/80/labels                                                    # → 403
  ```
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE raw_sn=133;                    -- 133 | F
  SELECT data_stts_cd FROM ls_raw_data_status WHERE raw_data_id=133;               -- ASSIGNED
  ```
- **영향**: **CWE-862(Missing Authorization)** + **CWE-639(IDOR — rawSn 열거만으로 임의 영상 접근)** + **CWE-359(PII)**. 외부 채널 사용자가 rawSn 을 1씩 증가시키며 내부 파이프라인 전 영상의 라벨 좌표·프레임 ID(`srcSn`)·트랙 ID 를 수집할 수 있다. 좌표는 마스킹 실패 위치를 특정하므로 신고 구간에서는 특히 위험하다. 이 프로젝트의 **반복 실패 모드("상태 차단 게이트를 호출처마다 배선하면 반드시 샌다")의 재발**이며, 2차에서 이미 발견된 `privacy-meta`·`description`·`reviews/{videoId}/frames` 와 동형이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `loadDatamartLabels` 진입부에 형제 경로와 **동일 순서**로 ①`if (!isExposedToDatamart(rawSn)) throw FORBIDDEN` ②`accessGuard.requireNotUnderDeidentReport(rawSn)` 를 추가한다. 근본 대책으로는 포털 데이터 접근 경로가 4개(+SAM2 2개)로 늘어난 만큼 "rawSn/srcSn 을 받는 포털 진입점" 을 공통 가드(예: `PortalDatamartAccessGuard.requireAccessible(rawSn)`)로 수렴시켜 **메서드마다 재배선하지 않게** 하고, 전 포털 엔드포인트를 파라미터화한 회귀 IT(미승인·신고 조합)로 고정한다.


### [F-ISSUE-21] TC-PORTALUP-004 — `files` 파트 누락 시 400 이어야 할 요청이 500 + 전체 스택트레이스로 떨어진다 (A-ISSUE-21 중복)

- **심각도**: HIGH
- **중복 고지**: `A-part3.md` / `A-result.md` 의 **A-ISSUE-21** 이 **동일 원인 · 동일 재현 경로**(`POST /v1/portal/uploads/images`, multipart 에 `files` 파트 없음 → 500)를 이미 등재했다. 본 항목은 F 클러스터 TC 판정 근거로서만 기록하며, `ISSUES.md` 누적 시 **A-ISSUE-21 로 병합**하는 것을 권장한다(신규 결함 아님).
- **기대 동작(기대효과)**: 필수 multipart 파트가 없는 요청은 **클라이언트 오류(400 `INVALID_INPUT`)** 로 응답하고, 로그는 WARN + 예외 클래스명·파트명만 남겨야 한다. 서비스에는 이미 그 의도가 코드로 존재한다 — `PortalUploadService.java:87-89`

  ```java
  if (files == null || files.isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "업로드할 이미지가 없습니다.");
  }
  ```

- **현재 동작(이슈 내용)**: 컨트롤러가 `@RequestParam("files") List<MultipartFile> files`(required 기본값 `true`)로 받으므로, 파트 부재는 서비스 진입 **이전에** `MissingServletRequestPartException` 으로 끊긴다. `GlobalExceptionHandler` 에는 `MissingServletRequestParameterException` 핸들러(`:76`)만 있고 **`MissingServletRequestPartException` 핸들러가 없어** `@ExceptionHandler(Exception.class)`(`:273`)로 떨어진다.

  실측 응답:
  ```json
  {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}   // HTTP 500
  ```
  실측 로그(ERROR + 전체 스택):
  ```
  ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.
      at org.springframework.web.method.annotation.RequestParamMethodArgumentResolver.handleMissingValueInternal(...)
  ```
  같은 계열로 `Content-Type` 이 multipart 가 아닌 요청은 `MultipartException: Current request is not a multipart request` → 역시 **500**.

  부수 효과로 서비스의 빈 목록 분기(`:87-89`)는 **HTTP 로 도달할 수 없는 dead branch** 가 된다(단위 테스트 `#빈_목록_업로드시_400` 은 서비스를 직접 호출하므로 통과한다 — 테스트 GREEN 이 런타임을 보증하지 못하는 사례).

- **재현/확인 경로**:
  ```bash
  TOKEN=$(...)   # PORTAL_USER
  curl -i -X POST http://localhost:18081/api/v1/portal/uploads/images \
       -H "Authorization: Bearer $TOKEN" -F "dummy=1"
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR
  docker logs klid-backend --since 1m | grep MissingServletRequestPart
  ```
- **영향**: ①**CWE-209/CWE-779** — 클라이언트 오류에 서버 스택트레이스를 ERROR 레벨로 적재. 조작된 요청을 반복하면 로그 볼륨 증폭(CWE-770). ②FE 가 입력 누락을 **서버 장애로 오인**(재시도·알림 오발). ③운영 5xx 알림 오탐으로 실장애 탐지 저하.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `GlobalExceptionHandler` 에 405 핸들러(`:261-271`)와 동형으로 `MissingServletRequestPartException` → 400 `INVALID_INPUT`(파트명만 노출) 핸들러를 추가하고, 겸해 `MultipartException`(비-multipart 요청) → 400 을 함께 처리한다. 로그는 WARN + 예외 클래스명·파트명만(경로 원문 금지). 회귀 가드는 A-ISSUE-21 제안대로 "`Exception.class` 종착지에 도달하는 표준 MVC 예외가 없다"를 고정하는 테스트로 통합한다.

---


### [F-ISSUE-41] TC-TUS-002/007 — Upload-Metadata 파일명의 널바이트가 500 + 정리되지 않는 고아 임시파일을 남기고, 동시세션 상한(429)까지 우회한다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 파일명은 표시용 필드지만 사용자 입력이므로 **제어문자(특히 `\0`)를 거부하거나 제거**해 400 으로 마감해야 한다. 실패 시에도 `createEmptyFile` 로 이미 만든 임시파일은 보상 삭제돼야 한다(부분 실패 원자성).
- **현재 동작(이슈 내용)**:
  - `PortalTusUploadController.java:178-192 parseFileName` → `decodeBase64` 는 base64 디코딩만 하고 **제어문자 검사를 하지 않는다.**
  - `PortalVideoUploadService.java:104-118` 은 ① `TusChunkStore.createEmptyFile(absolutePath)`(디스크 쓰기) → ② `LsPortalTusUpload.create(..., truncate(cmd.fileName()))` → ③ `tusRepository.save(session)` 순서다. `ORGNL_FILE_NM` 에 `0x00` 이 실려 **PostgreSQL 이 INSERT 를 거부**한다.
  - 실측 로그: `SQL Error: 0, SQLState: 22021` / `ERROR: invalid byte sequence for encoding "UTF8": 0x00` → `GlobalExceptionHandler - unclassified data integrity violation constraint=null cause=PSQLException` → **HTTP 500** `{"errorCode":"INTERNAL_ERROR"}`.
  - **부작용 3종**:
    1. `/app/storage/raw/portal/tus-video/{uuid}.mp4` **0바이트 고아 파일이 남는다**(트랜잭션 롤백은 DB 만 되돌린다). 실측 2건(`cd6aebc1-…`, `73d0d1b4-…`) 확인.
    2. 스윕(`claimExpiredSessions`)은 **DB 행 기준**으로만 파일을 지우므로 이 고아 파일은 **영구히 정리되지 않는다**(CWE-459).
    3. DB 행이 안 생기므로 `countByPortalUserNoAndSttsCd(IN_PROGRESS)` 가 증가하지 않아 **동시세션 상한 3(429)이 전혀 걸리지 않는다** → 한 PORTAL_USER 가 이 요청을 무제한 반복해 파일/아이노드를 계속 증식시킬 수 있다(CWE-770). TUS 경로에는 별도 rate limiter 도 없다(`F-ISSUE-46`).
  - 성공 INFO 로그 `[PortalTus] session created uldId=… ext=mp4` 가 **커밋 실패 전에** 찍혀 운영 로그가 실제와 어긋난다.
- **재현/확인 경로**:
  ```bash
  TOKEN=... # PORTAL_USER
  MD=$(printf 'a\0b.mp4' | base64)   # YQBiLm1wNA==
  curl -i -X POST http://localhost:18081/api/v1/portal/uploads/tus \
    -H "Authorization: Bearer $TOKEN" -H "Tus-Resumable: 1.0.0" \
    -H "Upload-Length: 100" -H "Upload-Metadata: filename $MD"
  # → HTTP 500 {"errorCode":"INTERNAL_ERROR"}
  docker exec klid-backend ls -l /app/storage/raw/portal/tus-video/   # 0바이트 고아 파일 잔존
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_portal_tus_uld where orgnl_file_nm like 'a%';"   # 0
  ```
- **영향**: CWE-20(입력 검증 누락) · CWE-459(불완전 정리) · CWE-770(무제한 자원 소비 — 상한 우회) · CWE-209(운영 500). 실동작 확인. 저장소가 공유 NAS 이므로 아이노드 고갈은 **내부 파이프라인까지 동반 마비**시킬 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`parseFileName` 단계에서 `\0`·개행 등 제어문자 포함 시 400 으로 거부(또는 제거 후 저장). ②`createSession` 순서를 "DB 저장 → 파일 생성"으로 뒤집거나, INSERT 실패 시 `TusChunkStore.deleteQuietly(absolutePath, storageRoot)` 보상 삭제를 `catch` 에 추가. ③성공 로그를 커밋 이후로 이동.


### [F-ISSUE-02] TC-PORTAL-021 — 데이터마트 목록의 "프레임 0건 제외" 가 **페이징 이후**에 적용돼 페이지에 구멍이 나고 `totalElements` 가 페이지 크기에 따라 달라진다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프레임 0건 영상을 제외한 결과가 요청한 `size` 만큼 채워지고, `totalElements` 는 어떤 `size` 로 조회하든 동일해야 한다.
- **현재 동작(이슈 내용)**: DB 쿼리로 페이지를 먼저 자른 뒤 그 페이지 안에서만 0프레임 행을 걸러낸다.

  `PortalLabelService.java:135-161`
  ```java
  Page<LsDataRaw> page = videoRepository.findAllWithReviewStatus(null, STTS_APPROVED, pageable); // 페이징 먼저
  ...
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // 페이지 안에서만 제외
          .map(...).toList();
  return new PageImpl<>(content, pageable, page.getTotalElements());
  ```
  [실동작] APPROVED 10건(그중 151·155 가 0프레임) 기준 실측:
  | 요청 | 반환 content | totalElements | totalPages |
  |---|---:|---:|---:|
  | `page=0&size=2` | **1** | 10 | 5 |
  | `page=0&size=5` | **3** | 10 | 2 |
  | `page=0&size=20` | 8 | **8** | 1 |
  | `page=0&size=50` | 8 | **8** | 1 |
  | `page=1&size=5` | 5 | 10 | 2 |

  `size=2` 요청에 1건만, `size=5` 에 3건만 온다(페이지 구멍). 또 마지막 페이지에서는 `PageImpl` 이 total 을 `offset + content.size()` 로 재계산하므로 `totalElements` 가 10 이 아니라 **8** 이 된다 — 코드 주석 `:160` 의 "totalElements 는 원본(게이트 후) 기준 유지" 라는 의도와도 어긋난다.
- **재현/확인 경로**:
  ```bash
  for s in 2 5 20 50; do
    curl -s -H "Authorization: Bearer $PT" \
      "localhost:18081/api/v1/portal/datamart/videos?page=0&size=$s" \
      | jq -c '{size:'"$s"', n:(.data.content|length), total:.data.totalElements}'
  done
  ```
- **영향**: 포털 홈 목록의 무한스크롤/페이지네이션이 "빈 페이지" 또는 "덜 찬 페이지"를 만들고, 총 건수 표기가 조회 크기에 따라 달라진다. 보안 영향은 없으나 외부 채널 첫 화면의 사용성·정합성 결함이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 0프레임 제외 조건을 **DB 쿼리로 밀어 넣는다**(`AND EXISTS (SELECT 1 FROM LsDataSrc s2 WHERE s2.rawSn = v.rawSn)`) — 그러면 페이징·총건수가 자연히 정합한다. 어쩔 수 없이 앱단 필터를 유지한다면 `PageImpl` 대신 total 을 별도 count 쿼리로 명시 주입해 마지막 페이지 재계산을 피한다.


### [F-ISSUE-04] UNCERTAINTIES #12 — 데이터마트 저장/조회·이미지 서빙에 rate limit 이 없다(부재 확인)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 외부 채널 엔드포인트는 요청량 상한이 있어야 한다(포털 SAM2 30/1m, 포털 업로드 60/1m 과 동일 수준의 방어).
- **현재 동작(이슈 내용)**: `PortalLabelService` 가 담당하는 5개 경로 어디에도 `RateLimiter`·`Bulkhead` 획득 코드가 없다(`PortalLabelService.java` 전문에 `RateLimiter`/`Bulkhead` import 0건). `application.yml:602-619` 의 `ratelimiter.configs` 에는 `portalSam2`·`portalUpload` 두 개만 있다.
  [실동작] 단일 사용자 연타 실측 — **429 0건**:
  | 경로 | 요청 수 | 응답 |
  |---|---:|---|
  | `GET /v1/portal/datamart/videos` | 60 | 200 ×60 |
  | `GET /v1/portal/user-labels?rawSn=` | 60 | 200 ×60 |
  | `GET /v1/portal/frames/130/image` | 60 | 200 ×60 (매건 파일 I/O) |
  | `GET /v1/portal/datamart/labels?rawSn=` | 60 | 200 ×60 |
  | `GET /v1/portal/frames/130/labels` | 60 | 200 ×60 |
  | `POST /v1/portal/user-labels` | 40 | **201 ×40**(DB 행 40개 생성) |
  대조군으로 같은 사용자의 포털 SAM2 는 31번째 요청부터 429 였다 — **동일 채널 안에서 방어 수준이 갈린다.**
- **재현/확인 경로**:
  ```bash
  for i in $(seq 1 60); do curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $PT" \
    localhost:18081/api/v1/portal/frames/130/image; done | sort | uniq -c   # → 60 200
  ```
- **영향**: **CWE-770(Unrestricted Resource Consumption)** / OWASP API4:2023. 이미지 서빙은 매 요청 NAS 파일 I/O 를, 저장은 무제한 DB INSERT 를 유발한다. 외부 채널이라 인증된 계정 1개만 있으면 트래픽·스토리지를 밀어 넣을 수 있다. `datamart/labels` 는 F-ISSUE-01 과 결합하면 **전 영상 라벨 대량 수집**의 실행 수단이 된다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `portalSam2`/`portalUpload` 와 동일 패턴(per-user `RateLimiterRegistry` lazy 생성 + `timeout-duration: 0`)으로 `portalDatamart` config 를 추가하고, 읽기(목록·라벨·이미지)와 쓰기(user-labels 저장)에 서로 다른 상한을 둔다. 개별 서비스 메서드에 흩뿌리지 말고 `/v1/portal/**` 공통 인터셉터/필터 한 곳에서 적용해 **또 다른 형제 경로 누락(F-ISSUE-01 과 동형)** 을 예방한다.


### [F-ISSUE-05] UNCERTAINTIES #11 — "본인 데이터 기간 내 다운로드" 의 기간 제한 로직이 없다(부재 확인)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 포털 절 — "본인 데이터 **기간 내** 다운로드". 즉 다운로드 대상에 유효기간이 있고, 기간이 지난 데이터는 거부돼야 한다.
- **현재 동작(이슈 내용)**: 포털 패키지 전체(`backend/src/main/java/kr/co/cudo/authoring/portal/**`)에서 기간 판정 로직은 **TUS 업로드 세션 TTL 하나뿐**이다 — `LsPortalTusUpload.java:72,98,102-103`(`expiresAt = now.plusHours(TTL_HOURS)`, `isExpired`), `LsPortalTusUploadRepository.java:54-55,68`, `PortalUploadSweepJob`. 이는 **미완료 업로드 세션 정리**용이며 다운로드 권한과 무관하다.
  또한 F-1~F-4 범위(데이터마트 라벨링)에는 **다운로드 엔드포인트가 아예 없다** — `PortalLabelController` 의 6개 매핑 중 다운로드/export 성격은 0개다. 다운로드는 포털 **업로드 자산** 쪽(`PortalUploadLabelController.java:82` `GET /{uldSn}/export`, `:92` `GET /{uldSn}/file`, F-7 범위)에만 존재하고, 그 두 경로에도 기간 조건은 없다(소유자 검증만).
- **재현/확인 경로**:
  ```bash
  grep -rniE "retention|expire|validUntil|기간|다운로드 기간" backend/src/main/java/kr/co/cudo/authoring/portal
  # → LsPortalTusUpload / LsPortalTusUploadRepository / PortalUploadSweepJob (업로드 세션 TTL) 만 매칭
  grep -rn "Mapping" backend/src/main/java/kr/co/cudo/authoring/portal/controller/PortalLabelController.java
  # → 다운로드/export 매핑 0건
  ```
- **영향**: 요구 문구("기간 내")가 구현으로 실현되지 않았다. 데이터 보관·파기 정책이 코드로 강제되지 않으므로 개인정보 보유기간 관점의 감리 지적 소지가 있다. 보안 즉시 위험은 낮다(소유자 검증은 있음).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 먼저 **"기간" 의 기준(다운로드 가능 기간인지 데이터 보관 기간인지, 기산점이 저장일인지 승인일인지, 일수)을 사용자·발주처와 확정**해야 한다. 확정 후 `LS_SYSTEM_CONFIG` 설정키로 일수를 두고 다운로드 진입부에서 `regDt + N일 < now` 이면 410/403 으로 거부하는 방식이 자연스럽다. 데이터마트 작업 데이터 다운로드 경로가 필요한지 여부도 함께 확정 대상이다.


### [F-ISSUE-23] TC-PORTALUP-057·058(관련) — 포털 다운로드/export 에 "본인 데이터 기간 내" 기간 제한이 전혀 구현돼 있지 않다 (UNCERTAINTIES #11 미해소 재확인)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 「포털(외부 채널)」 — *"본인 데이터 **기간 내** 다운로드"*. 포털 사용자가 자신의 자산·라벨을 내려받을 수 있는 기간에 상한이 있어야 하며, 기간 경과분은 다운로드가 거부(또는 자산이 정리)되어야 한다.
- **현재 동작(이슈 내용)**: 기간 판정 로직·기준 컬럼·설정 키가 **모두 부재**하다.
  - `LS_PORTAL_ULD` 스키마 실측 컬럼 14개 — `uld_sn, portal_user_no, uld_type_cd, orgnl_file_nm, file_path_nm, file_sz, mime_type_nm, uld_stts_cd, vdo_len_sec, fps, frme_cnt, fail_rsn_cn, reg_dt, mdfcn_dt`. **만료/보존기한 컬럼 0개.**
  - `PortalUploadLabelService.downloadFile`(`:211-256`)·`exportLabels`(`:153-202`) 의 가드는 **소유권 + 경로 안전성 + 파일 실재** 3가지뿐이며 시간축 판정이 없다.
  - `portal/` 패키지 내 `expire*` 참조는 전부 **TUS 업로드 세션 만료**(`PortalUploadSweepJob:71`, `PortalVideoUploadService:133`, `PortalVideoUploadTxService:77`)로 **다운로드 기간과 무관**한 별개 개념이다.
  - `PortalUploadProperties`(11필드)에 보존기간 설정 키 없음.
- **재현/확인 경로**:
  ```bash
  # reg_dt 를 과거로 밀어도 다운로드/export 가 그대로 200
  psql -c "update ls_portal_uld set reg_dt = now() - interval '10 years' where uld_sn=1"
  curl -i .../v1/portal/uploads/1/file   -H "Authorization: Bearer $P"   # → 200
  curl -i .../v1/portal/uploads/1/export -H "Authorization: Bearer $P"   # → 200
  # 스키마·코드 확인
  psql -c "\d ls_portal_uld" | grep -iE "expir|retent|valid"    # → 0건
  grep -rn "retention\|보존기간\|downloadableUntil" backend/src/main/java/kr/co/cudo/authoring/portal/  # → 0건
  ```
- **영향**: 요구사항 미충족(기능 갭). 보안 관점으로는 데이터 최소보관 원칙 미이행 — 포털 사용자 업로드 자산이 무기한 서버에 남고 무기한 재다운로드 가능하다. 개인 자산이 삭제 요청 없이 영구 축적되면 스토리지 증가와 함께 개인정보 보관기간 준수 이슈로 번질 수 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 이 항목은 **정책 확정이 선행**돼야 한다(기간의 기준: `reg_dt` 기준 N일인가 / 마지막 접근 기준인가 / 데이터마트 다운로드와 업로드 자산에 같은 규칙을 쓰는가). 확정 후 ①`PortalUploadProperties` 에 보존기간 키 추가 ②`downloadFile`/`exportLabels` 진입부에 기간 게이트(410 Gone 또는 403) ③기존 정리 스윕(`PortalUploadSweepJob`)에 만료 자산 정리 스텝 추가 순으로 배선하는 것이 기존 구조와 정합한다. **정책 미확정 상태에서 임의 기간을 코드에 박지 말 것.**

---


### [F-ISSUE-42] TC-PORTALUP-082/083 — 포털 정리 스윕(`@Scheduled`)의 발화가 무관한 토글 `authoring.work-lock.sweep.enabled` 에 결합돼 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `PortalUploadSweepJob` 은 자기 도메인 설정(`portal.upload.sweep.*`)으로 발화 여부가 정해져야 한다. 만료 TUS 세션·고착 자산 정리는 기능 토글과 무관하게 항상 돌아야 하는 위생 잡이다.
- **현재 동작(이슈 내용)**:
  - 애플리케이션 전체에서 `@EnableScheduling` 은 **`auth/scheduler/WorkLockSweepConfig.java:14-17` 단 한 곳**뿐이고, 거기에 `@ConditionalOnProperty(name="authoring.work-lock.sweep.enabled", havingValue="true", matchIfMissing=false)` 가 붙어 있다.
  - `application-local.yml:68-70` 은 `authoring.work-lock.sweep.enabled: false` → **local 프로파일에서는 `@EnableScheduling` 이 등록되지 않아 `PortalUploadSweepJob.run()` 의 `@Scheduled` 가 영원히 발화하지 않는다.**
  - 실측: 컨테이너 기동 `2026-07-30T17:43:08Z`(프로파일 `local`), 초기지연 10분 + 주기 30분 기준으로 4회 이상 발화했어야 하나 `docker logs klid-backend | grep PortalSweep` **0건**. 만료 세션 픽스처(`EXPRY_DT` 2시간 과거, `IN_PROGRESS`)와 고착 자산 픽스처(`PROCESSING`, `MDFCN_DT` 2시간 과거)를 두고 30분+ 관측했으나 **둘 다 그대로 남았다.**
  - `application.yml:206-210` 주석은 이 결합을 **인지하고 있음**을 보여준다("…다른 `@Scheduled` 잡(Portal/TUS sweep)을 부수적으로 발화시키지 않는다"). 즉 의도된 부작용이지만, **포털 스윕 쪽에는 자기 토글이 없어** 운영자가 `WORK_LOCK_SWEEP_ENABLED=false` 로 두면 포털 정리가 조용히 죽는다(dev/stg/prd 기본값은 `true` 라 현재는 동작).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep SPRING_PROFILES_ACTIVE     # local
  grep -rn "EnableScheduling" backend/src/main/java              # WorkLockSweepConfig 1곳뿐
  sed -n '66,71p' backend/src/main/resources/application-local.yml   # work-lock.sweep.enabled: false
  docker logs klid-backend 2>&1 | grep -c PortalSweep            # 0
  ```
- **영향**: 프로젝트 반복 결함 패턴(`feature-toggle-coupling-defect-pattern`)의 재현. local/테스트에서 스윕 회귀가 **런타임으로는 절대 잡히지 않고**, 운영에서 무관한 토글 조작 한 번으로 고아 세션·임시파일·PROCESSING 영구 고착이 누적된다. CWE-459/CWE-770 의 완화 수단이 사라지는 것.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`PortalUploadSweepConfig`(`@ConditionalOnProperty("portal.upload.sweep.enabled")` + `@EnableScheduling`)를 도메인 국소로 신설해 자기 토글로 발화하게 하거나, ②`ControlNotifyDebouncer`(`:114`)처럼 전용 데몬 스케줄러를 잡이 직접 소유해 `@EnableScheduling` 비의존으로 전환. ③어느 쪽이든 "스케줄이 실제 등록됐는가"를 기동 로그/헬스에 드러낼 것.


### [F-ISSUE-43] TC-TUS-006/013 — 만료된 IN_PROGRESS 세션이 동시세션 슬롯을 계속 점유하고, 세션 목록 조회 API 가 없어 사용자가 최대 24시간 자기 잠금에 걸린다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 동시 진행 상한(3)은 **살아있는** 세션만 세야 한다. 만료된 세션은 상한 계산에서 빠지거나, 사용자가 스스로 정리할 수단이 있어야 한다.
- **현재 동작(이슈 내용)**:
  - `PortalVideoUploadService.java:97-102` 는 `tusRepository.countByPortalUserNoAndSttsCd(portalUserNo, IN_PROGRESS)` 만 세고 **`EXPRY_DT` 를 보지 않는다**(`LsPortalTusUploadRepository:26`).
  - 만료 세션은 `findExpired`+스윕이 지워야 정리되는데, 그 스윕이 30분 주기이고 로컬에선 아예 안 돈다(`F-ISSUE-42`). TTL 은 `LsPortalTusUpload.TTL_HOURS = 24`.
  - 결과: 브라우저 탭을 닫아 `uldId` 를 잃어버린 세션 3건이 쌓이면 **최대 24시간 + 스윕 주기 동안 신규 업로드가 전부 429** 가 된다. `PortalTusUploadController` 에는 **내 세션 목록 조회(GET) 엔드포인트가 없어** 사용자가 uldId 를 되찾아 DELETE 할 방법도 없다.
  - 실측: 사용자 3002 에 만료 세션 1건이 IN_PROGRESS 로 남아 있는 상태에서 신규 생성 → `201, 201, 429`. 만료 세션이 슬롯 1칸을 그대로 먹고 있음을 확인.
- **재현/확인 경로**:
  ```sql
  update ls_portal_tus_uld set expry_dt = now() - interval '2 hour' where uld_id='<세션>';
  ```
  ```bash
  # 같은 사용자로 세션 3개 추가 생성 → 3번째부터 429
  for i in 1 2 3; do curl -s -o /dev/null -w "%{http_code} " -X POST .../uploads/tus \
    -H "Authorization: Bearer $P2" -H "Tus-Resumable: 1.0.0" -H "Upload-Length: 1000" \
    -H "Upload-Metadata: filename $(printf lock$i.mp4|base64)"; done   # 201 201 429
  ```
- **영향**: 가용성(사용자 자기 잠금). 보안 결함은 아니나 포털 사용자 입장에서 복구 불가능한 막다른 상태다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①상한 카운트를 `stts_cd='IN_PROGRESS' AND expry_dt > now()` 로 좁히거나, ②세션 생성 시 자기 만료 세션을 lazy 정리, ③`GET /v1/portal/uploads/tus`(내 진행 중 세션 목록)를 추가해 재개·취소 동선을 열 것.


### [F-ISSUE-44] TC-TUS-014 — TUS PATCH 에 잘못된 Content-Type 을 보내면 415 가 아니라 500 + `INTERNAL_ERROR` 가 나간다 (A-ISSUE-21 의 포털 경로 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `@PatchMapping(consumes = "application/offset+octet-stream")` 매칭 실패는 **415 Unsupported Media Type**.
- **현재 동작(이슈 내용)**: `HttpMediaTypeNotSupportedException` 이 `GlobalExceptionHandler` 의 `Exception.class` fallback 으로 떨어져 **500** 을 반환하고, 로그에 `ERROR … unhandled exception` + 스택트레이스를 남긴다.
  ```
  2026-07-31 04:33:59.630 ERROR [0c43e8eabb47] GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/json' is not supported
  ```
  응답: `{"success":false,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` / HTTP 500.
- **재현/확인 경로**:
  ```bash
  curl -i -X PATCH .../v1/portal/uploads/tus/<uldId> -H "Authorization: Bearer $P1" \
    -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 100" -H "Content-Type: application/json" --data-binary @chunk
  # → HTTP 500
  ```
- **영향**: A 클러스터 `A-ISSUE-21`(415/406/multipart 4xx → 500 + 전체 스택)의 **동일 근본원인이 포털 TUS 경로에서도 재현**됨을 확증. 인증된 PORTAL_USER 가 헤더 한 줄로 ERROR 로그 + 스택트레이스를 무제한 생성 가능(CWE-770/CWE-209). 표준 tus 클라이언트 호환성도 깨진다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** A-ISSUE-21 수정(`HttpMediaTypeNotSupportedException`·`HttpMediaTypeNotAcceptableException` 등 Spring MVC 표준 예외를 `@ExceptionHandler` 로 4xx 매핑)에 흡수시킬 것 — 포털 전용 수정은 불필요.


### [F-ISSUE-45] TC-PORTALUP-065 — `computeFrameNumbers` 가 `maxFrames` 상한을 적용하기 *전에* 후보 프레임 리스트를 전량 생성하고, 그 크기가 ffprobe 가 신고한 영상 길이에 비례한다(길이 무검증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 상한(2000)이 **메모리 할당 자체를 제한**해야 한다. 컨테이너 헤더가 신고하는 duration 은 신뢰할 수 없는 사용자 입력이므로 상식적 범위로 클램프돼야 한다.
- **현재 동작(이슈 내용)**: `PortalFrameExtractRunner.java:151-163`
  ```java
  long totalFrames = Math.max(1L, Math.round(durationSec * effFps));
  long step        = Math.max(1L, Math.round(effInterval * effFps));
  List<Integer> candidates = new ArrayList<>();
  for (long n = 0; n < totalFrames; n += step) { candidates.add((int) n); }   // ← 상한 적용 전
  if (candidates.size() <= cap) { return candidates; }
  ```
  루프 반복 수 = `totalFrames / step` ≈ **`durationSec / intervalSec`** (fps 는 상쇄된다). `cap`(=2000)은 리스트를 **다 만든 뒤에** 적용된다.
  - `durationSec` 은 `PortalVideoProbeFfprobe.probeTask` 가 ffprobe `format.duration`/스트림 duration 을 **그대로** 돌려주며(`:88-95`) 어떤 상한 검증도 없다. 파일 크기와의 정합성 교차검증도 없다.
  - mp4 `mvhd/mdhd` duration 은 uint32 이므로 timescale=1 이면 최대 ≈4.29e9 초를 신고할 수 있다. 이 경우 후보 수 ≈ 8.6억 → `ArrayList<Integer>` OOM.
  - `runAsync` 의 `catch (Exception)`(`:78`)은 **`OutOfMemoryError` 를 잡지 못하며**, `portalExtractExecutor` 는 core=1 이라 이 스레드가 죽으면 포털 프레임 추출 전체가 영향을 받는다.
  - `(int) n` 캐스팅(`:156`)도 `totalFrames > Integer.MAX_VALUE` 에서 음수 프레임 번호를 만든다.
- **재현/확인 경로**: 관계식은 실측으로 확인했다 — 120s/interval 5 → 후보 24개, 12s/interval 10 → 2개, 12s/interval 5 → 3개(정확히 `duration/interval`). ⚠ **OOM PoC(mvhd duration 위조 컨테이너 업로드)는 공유 스택을 죽여 다른 검증 에이전트의 데이터를 파괴할 위험이 있어 의도적으로 실행하지 않았다.** 코드 경로상 도달 가능성은 위 라인으로 확정.
  ```bash
  # 관계식 확인(실행함)
  # interval 10 + 12s/10fps → frme_cnt=2 / interval 5 + 120s/10fps → frme_cnt=24
  ```
- **영향**: CWE-770(무제한 자원 소비) / CWE-789(입력값 기반 대용량 할당). 인증된 PORTAL_USER 가 **작은 크기의 크래프트 mp4 하나로** 백엔드 힙을 고갈시킬 수 있다. 5GB 상한·확장자 allowlist·매직바이트·ffprobe 검증을 전부 통과할 수 있는 경로다(모두 "실제 영상인가"만 보고 "길이가 말이 되는가"는 안 본다).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`candidates` 생성 루프에 `cap` 기반 조기 중단(또는 처음부터 `min(totalFrames/step, cap)` 개만 생성)을 넣고, ②`durationSec` 을 상식 상한(예: 24h)으로 클램프하며 초과 시 400/FAILED 로 마감, ③`(int)` 캐스팅 대신 long→int 안전 변환.


### [F-ISSUE-06] TC-PORTAL-040 부가 발견 — 사용자 라벨 저장 시 `sourceSrcSn` 이 `sourceRawSn` 소속인지 검증하지 않는다

- **심각도**: LOW
- **기대 동작(기대효과)**: `LS_PORTAL_USER_LABEL(SRC_RAW_SN, SRC_DATA_SRC_SN)` 은 같은 영상의 프레임을 가리켜야 한다(참조 무결성).
- **현재 동작(이슈 내용)**: APPROVED 게이트는 `sourceRawSn` 만 보고, `sourceSrcSn` 은 존재 여부조차 확인하지 않은 채 그대로 적재한다.

  `PortalLabelService.java:203-220`
  ```java
  if (!isExposedToDatamart(req.sourceRawSn())) { ... throw FORBIDDEN; }   // rawSn 만 검사
  ...
  userLabelRepository.save(LsPortalUserLabel.create(
          actor.sub(), req.sourceRawSn(), req.sourceSrcSn(), ...));       // srcSn 소속 미검증
  ```
  [실동작] `{"sourceRawSn":152(APPROVED), "sourceSrcSn":80(rawSn=133 소속·미승인·신고중)}` → **201 저장 성공**. DB 스키마상 FK 는 `SRC_RAW_SN → LS_DATA_RAW` 하나뿐이고 `SRC_DATA_SRC_SN` 에는 FK 가 없다(`\d ls_portal_user_label` 실측).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
    -d '{"sourceRawSn":152,"sourceSrcSn":80,"lblTypeCd":"BBOX","label":"crossref","points":"[[1,1],[2,2]]"}' \
    localhost:18081/api/v1/portal/user-labels        # → 201
  ```
- **영향**: 읽기 누수는 없다 — `loadFrameLabels(80)` 은 실제 소속 영상(133)으로 APPROVED 게이트를 걸어 403 이고, `listMyLabels(152)` 는 본인 데이터만 돌려준다. 남는 것은 **참조 무결성 오염**(존재하지 않는 srcSn 도 저장 가능)과, 향후 이 테이블을 srcSn 기준으로 조인·집계·export 하는 기능이 생길 때의 잠재 오류다. `LS_DATA_SRC` 참조 FK 가 DB 전체 0건인 기존 지적(`B-ISSUE-101`)과 같은 계열이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 저장 진입부에서 `srcRepository.findById(sourceSrcSn)` 로 프레임을 읽어 `frame.getRawSn().equals(req.sourceRawSn())` 를 확인하고 불일치·부재면 400/404 로 거부한다(APPROVED 판정도 프레임에서 역산한 rawSn 으로 하면 축이 하나로 준다). DB 측에는 `SRC_DATA_SRC_SN → LS_DATA_SRC` FK 추가를 검토한다.


### [F-ISSUE-07] TC-PORTAL-048 부가 발견 — `GET·POST /v1/portal/user-labels` 는 신고 구간에서도 동작한다

- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간(`DE_IDNTF_YN='F'`)에서는 그 영상에 얽힌 좌표 열람·작업을 멈추는 것이 일관적이다(내부 경로는 조회 412 + 저장 409 작업락).
- **현재 동작(이슈 내용)**: `listMyLabels`(`PortalLabelService.java:226-235`)에는 APPROVED 게이트도 신고 게이트도 없고, `saveUserLabel`(`:198-224`)에는 APPROVED 게이트만 있고 신고 게이트가 없다.
  [실동작] rawSn=152 를 `'F'` 로 둔 상태에서 `GET /v1/portal/user-labels?rawSn=152` → **200**, `POST /v1/portal/user-labels` → **201**(같은 순간 `frames/130/labels`·`/image` 는 412).
- **재현/확인 경로**: `ls_data_raw.de_ident_yn` 을 `'F'` 로 둔 뒤 위 두 요청 수행(본 검증에서는 152 로 수행 후 `'Y'` 원복).
- **영향**: 낮다 — 반환·저장되는 데이터가 **그 사용자가 직접 그린 좌표**이지 내부 파이프라인 라벨이 아니다(datamart 원본 병합은 게이트가 걸린 `loadFrameLabels` 에서만 일어난다). 다만 신고 구간에 새 작업을 계속 쌓게 되어, 재비식별 후 좌표가 어긋난 작업본이 남는다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: F-ISSUE-01 의 공통 가드로 수렴시킬 때 함께 판단한다 — 조회는 "본인 데이터라 허용" 으로 명시적으로 남기고 저장만 412 로 막는 선택도 가능하므로, **정책을 먼저 확정**하고 그 결정을 `CLAUDE.md` 차단 범위 목록에 명문화한 뒤 배선한다.


### [F-ISSUE-08] TC-PORTAL-068 부가 발견 — track wall-clock 예산이 루프 진입 시점에만 판정돼 최대 초과폭이 예산의 2배를 넘고, 전용 테스트가 없다

- **심각도**: LOW
- **기대 동작(기대효과)**: `TRACK_WALL_CLOCK_BUDGET`(60s)을 넘기면 조기 종료해 Tomcat 스레드 장기 점유를 막는다.
- **현재 동작(이슈 내용)**: 판정이 **프레임 루프의 맨 앞**에서만 이뤄지고, 한 프레임의 블로킹 상한은 별도로 70s 다.

  `PortalSam2Service.java:155-173,196-200`
  ```java
  long deadlineNanos = System.nanoTime() + TRACK_WALL_CLOCK_BUDGET.toNanos();   // 60s
  for (Long nextSrcSn : req.nextSrcSns()) {
      if (System.nanoTime() > deadlineNanos) { ... throw TOO_MANY_REQUESTS; }   // 진입 시점만
      ...
      var aiRes = callWithBulkhead(aiServerClient.track(aiReq), "track", nextSrcSn);  // block 70s
  ```
  59.9s 경과 시점에 시작한 프레임이 70s 를 쓰면 총 **약 130s** 까지 스레드가 점유된다(예산 60s 의 2배 초과).
  또 `PortalSam2ServiceTest` 에 wall-clock 예산 전용 테스트가 없다(`grep -n "wall\|budget\|TRACK_WALL" …PortalSam2ServiceTest.java` → 0건). 커버된 429 는 bulkhead·rate limit 두 종류뿐이다.
- **재현/확인 경로**: 정적 확인(위 grep). 60s 초과 추론 부하를 인위적으로 만들 수 없어 실동작 미재현.
- **영향**: 낮다 — 상위에 bulkhead 4 + per-user 30/1m 이 있어 동시 점유 스레드 수 자체가 제한된다. 다만 "예산 60s" 라는 이름과 실효 상한(≈130s)이 달라 운영 시 오해를 부른다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 남은 예산을 per-frame block timeout 에 반영하거나(`block(min(70s, 남은예산))`), 예산 초과 판정을 프레임 완료 직후에도 수행한다. 어느 쪽이든 예산 초과 시 429 를 반환하는 단위 테스트를 `PortalSam2ServiceTest` 에 추가해 회귀를 고정한다.

---


### [F-ISSUE-22] TC-PORTALUP-017(부가) — 원본 파일명 255자 절단이 UTF-16 단위라 surrogate pair 를 쪼개고 치환문자를 남긴다

- **심각도**: LOW
- **기대 동작(기대효과)**: `LS_PORTAL_ULD.ORGNL_FILE_NM varchar(255)` 에 안전하게 담기도록 절단하되, **문자(코드포인트) 경계를 보존**해 저장된 표시용 파일명이 원본의 앞부분과 시각적으로 동일해야 한다.
- **현재 동작(이슈 내용)**: `PortalUploadService.java:454-459`

  ```java
  private static String truncate(String name) {
      if (name == null) { return null; }
      return name.length() > ORGNL_FILE_NM_MAX ? name.substring(0, ORGNL_FILE_NM_MAX) : name;
  }
  ```
  `String.length()`/`substring` 은 **UTF-16 코드유닛** 기준이다. BMP 밖 문자(이모지·일부 CJK 확장·희귀 문자)는 2유닛이므로, 절단 지점이 surrogate pair 한가운데면 **고아 high surrogate** 가 남는다. 실측(이모지 `U+1F600` 200개 + `.jpg`, 총 404 유닛):

  ```
  uld_sn | length | octet_length | right(orgnl_file_nm,3) | ascii(right(...,1))
  121    | 128    | 509          | 😀😀?                  | 63
  ```
  마지막 문자가 `?`(치환) 로 저장됐다. **예외는 발생하지 않고 조용히 손상**된다(JDBC/PG UTF-8 인코딩 단계에서 치환).

  ※ 컬럼 초과(=INSERT 실패)는 발생하지 않는다 — UTF-16 유닛 수 ≥ 코드포인트 수이므로 255유닛 절단 결과는 항상 255자 이하다. 즉 **가용성 문제는 없고 표시 정확도 문제**다.

- **재현/확인 경로**:
  ```bash
  EMO=$(python3 -c "print('\U0001F600'*200+'.jpg')")
  curl -X POST .../v1/portal/uploads/images -H "Authorization: Bearer $P" -F "files=@ok.jpg;filename=$EMO"
  psql -c "select length(orgnl_file_nm), ascii(right(orgnl_file_nm,1)) from ls_portal_uld where uld_sn=121"
  # → 128 | 63   (마지막이 '?')
  ```
- **영향**: 표시용 파일명 말미 1문자 손상. 보안 영향 없음(저장 경로는 UUID 강제, 이 값은 어떤 경로 조합에도 쓰이지 않는다). 다운로드 `Content-Disposition` 의 `filename*` 에 치환문자가 실려 나갈 수 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 코드포인트 경계 보존 절단으로 교체 — 예: `name.codePoints().limit(ORGNL_FILE_NM_MAX).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString()`, 또는 `BreakIterator`/`offsetByCodePoints` 사용. 회귀 테스트는 "surrogate pair 경계에서 절단해도 lone surrogate 가 남지 않는다"를 고정.

---


### [F-ISSUE-24] TC-PORTALUP-018(부가) — rate limit 이 업로드에만 있고 라벨 PUT·export·원본 다운로드는 무제한이다

- **심각도**: LOW
- **기대 동작(기대효과)**: 자원 소비형 포털 엔드포인트(대용량 JSON 파싱·파일 스트리밍·DB 벌크 DELETE+INSERT)에도 per-user 요청량 제한을 두어 단일 사용자가 자원을 독점하지 못하게 한다(OWASP API4:2023 / CWE-770).
- **현재 동작(이슈 내용)**: `RateLimiter` 획득은 `PortalUploadController.acquireUploadPermit`(`:157-165`) **한 곳뿐**이며 `POST /images` 에서만 호출된다. 나머지 포털 업로드 계열 엔드포인트에는 어떤 제한도 없다.

  실측(동일 사용자 70연타):
  | 엔드포인트 | 70회 중 429 |
  |---|---:|
  | `POST /v1/portal/uploads/images` | **10회** (60 초과분 전부) |
  | `GET /v1/portal/uploads/{uldSn}/export` | **0회** |
  | `PUT /v1/portal/uploads/frames/{n}/labels` | **0회** |

  라벨 PUT 은 요청당 최대 2MB 본문 파싱 + 500건 검증 + 벌크 DELETE + `saveAll` 500건을 수행하고, export 는 자산 전체 라벨을 메모리에 모아 pretty JSON 으로 직렬화한다 — 둘 다 업로드 못지않은 자원 소비 경로다.

- **재현/확인 경로**:
  ```bash
  for i in $(seq 1 70); do
    curl -s -o /dev/null -w "%{http_code} " "$B/v1/portal/uploads/1/export" -H "Authorization: Bearer $P1"
  done    # → 200 ×70, 429 없음
  ```
- **영향**: 단일 포털 사용자가 export/라벨 PUT 을 폭주시켜 커넥션·힙·CPU 를 점유할 수 있다. 다만 ①본문 상한(2MB) ②라벨 개수 상한(500) ③페이지 크기 하드캡(100) 이 이미 **요청당 비용**을 제한하고 있어 즉시 서비스 정지로 이어질 여지는 낮다 → LOW.
- **관련**: `UNCERTAINTIES #12`(데이터마트 저장/조회·이미지 서빙 rate limit 부재, 미해소 유지)와 **같은 축의 별개 표면**이다. #12 는 `PortalLabelService`(데이터마트 계열), 본 건은 `PortalUploadLabelService`(업로드 자산 계열)다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `acquireUploadPermit` 과 동일한 per-user RateLimiter 패턴을 `PortalUploadLabelController` 의 4개 메서드에 config 만 달리해(예: `portalLabel` 120/1m, `portalDownload` 30/1m) 적용한다. 컨트롤러마다 복제하지 말고 공통 헬퍼(또는 `HandlerInterceptor`)로 뽑아 **획득 지점을 단일화**하는 편이 이후 표면 추가 시 누락을 막는다(게이트 배선을 호출처마다 복제하면 반드시 샌다는 기존 교훈과 동형).

---


### [F-ISSUE-46] TC-TUS-002/029 — TUS 세션 생성에 rate limit 이 없고 CANCELLED/COMPLETED 세션 행은 스윕 대상이 아니라 `LS_PORTAL_TUS_ULD` 가 무한 증식한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 세션 생성에 속도 제한이 있거나, 종결된 세션 행이 일정 기간 후 정리돼야 한다.
- **현재 동작(이슈 내용)**:
  - `/v1/portal/uploads/tus/**` 에는 rate limiter 가 없다 — 코드베이스에서 rate limit 을 갖는 곳은 `PortalUploadController`(이미지 업로드)·`PortalSam2Service`·`RoleClaimRateLimiter`·`WebhookRateLimiter` 뿐이고 TUS 컨트롤러/서비스에는 없다(grep 0건). 유일한 억제는 **동시 IN_PROGRESS 3건** 상한이다.
  - 그런데 `deleteExpiredInProgress`(`LsPortalTusUploadRepository:66-68`)와 `findExpired`(`:53-55`)는 **`stts_cd='IN_PROGRESS'` 인 행만** 지운다. `CANCELLED` 행은 어떤 경로로도 삭제되지 않는다(`COMPLETED` 는 영구 영상 링크라 보존이 타당).
  - 따라서 "3건 생성 → 3건 DELETE(취소) → 반복" 루프로 **행을 무제한 적재**할 수 있다(파일은 취소 시 지워지므로 디스크는 안전). 실측 세션 18건 중 CANCELLED 7건이 남아 있다.
  - 참고로 UNCERTAINTIES #12 는 "rate limit 은 업로드·SAM2·**TUS** 에만 존재"라고 적고 있으나 **TUS 에는 실제로 없다** — 문서 정정 대상.
- **재현/확인 경로**:
  ```bash
  grep -rln "RateLimit" backend/src/main/java | grep -i tus     # 0건
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select stts_cd, count(*) from ls_portal_tus_uld group by stts_cd;"   # CANCELLED 누적
  ```
- **영향**: CWE-770(약함 — 행 크기가 작고 디스크는 무사). 장기적으로 `LS_PORTAL_TUS_ULD` 비대화 및 `idx_lptu_user_stts` 외 조회 성능 저하.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①TUS `POST` 에 사용자별 생성 rate limit 추가, ②스윕에 "종결(CANCELLED) 후 N일 경과 행 삭제" 절 추가, ③UNCERTAINTIES #12 의 "TUS 에도 rate limit 존재" 서술 정정.


### [F-ISSUE-47] TC-TUS-010 — `Tus-Resumable` 헤더가 아예 없는 요청을 그대로 처리한다(TUS 1.0 권고는 412)
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 은 OPTIONS 를 제외한 모든 요청에 `Tus-Resumable` 을 요구하며, 없으면 412 를 권고한다.
- **현재 동작(이슈 내용)**: `PortalTusUploadController.java:163-168`
  ```java
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) { throw ...PRECONDITION_FAILED; }
  }
  ```
  `null` 이면 검사를 건너뛴다. 실측: 헤더 없이 보낸 POST/PATCH/HEAD/DELETE 가 모두 정상 처리됐다(본 검증의 여러 요청이 이 경로를 탔다).
- **재현/확인 경로**:
  ```bash
  curl -i -X POST .../v1/portal/uploads/tus -H "Authorization: Bearer $P1" \
    -H "Upload-Length: 30817" -H "Upload-Metadata: filename $(printf a.mp4|base64)"   # → 201
  ```
- **영향**: 프로토콜 준수 미달(보안 영향 없음). 버전 협상 없이 다른 TUS 버전 클라이언트가 조용히 붙어 향후 스펙 변경 시 무증상 오동작 가능.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `tusResumable == null` 도 412 로 마감(OPTIONS 제외). FE 포털 TUS 클라이언트가 항상 헤더를 붙이는지 먼저 확인 후 적용할 것.

---


## G 클러스터 — 10건

### [G-ISSUE-22] TC-AICONTRACT-06 — SAM2 Track·VLM verify-objects, BE DTO가 ai-server의 mock 지표를 유실시킴 (설계 의도 무력화)

- **심각도**: HIGH
- **기대 동작(기대효과)**: ai-server는 SAM2 Track(`Sam2TrackResponse`, `schemas.py:180-188`)과 VLM verify-objects(`VlmVerifyResponse`, `schemas.py:219-225`) 응답에 **모두** `mock`/`source`/`mock_reason` 필드를 포함해 실제로 전송한다(본 검증 §G-4/G-5에서 실동작으로 확인 — 예: `{"track_id":"t1",...,"mock":false,"source":"model","mock_reason":null}`). SAM2 Segment 쪽 BE DTO(`Sam2Response.java`)는 이 지표를 그대로 받아 Javadoc에 명시된 대로 **"FE 가 'AI 모델 미로드 — 결과 신뢰 불가' 경고 + 자동 적용 차단을 수행"**하도록 설계돼 있다(CLAUDE.md: "SAM2 분할(...) mock 응답은 FE 자동적용 차단"). SAM2 Track도 동일한 신뢰성 보호가 있어야 정합적이다 — Track이 fallback되면 실제로는 "이전 폴리곤을 score=0.5로 그대로 반사"하는 가짜 추적 결과이기 때문이다(TC-AISAM2-17/18 실측).
- **현재 동작(이슈 내용)**: BE 클라이언트 DTO가 두 응답 모두에서 mock 지표 필드를 **아예 선언하지 않는다** — Jackson이 알 수 없는 JSON 필드를 조용히 무시하므로 컴파일·런타임 에러 없이 정보만 유실된다.
  - `backend/src/main/java/kr/co/cudo/authoring/common/client/dto/Sam2TrackResponse.java:14-18`
    ```java
    public record Sam2TrackResponse(
            @JsonProperty("track_id") String trackId,
            List<List<Double>> polygon,
            double score
    ) {}
    ```
    `mock`/`source`/`mock_reason` 필드 없음. 이 DTO를 소비하는 `Sam2TrackService.java:105-135`도 `aiRes.trackId()`/`aiRes.score()`/`aiRes.polygon()`만 읽고 mock 여부를 전혀 확인하지 않으며, 최종 FE 응답 DTO(`Sam2TrackResponseDto.TrackedItem`, `label/dto/Sam2TrackResponseDto.java:21-28`)에도 mock 필드가 없다 — **FE는 mock fallback(score=0.5, "이전 폴리곤 그대로")과 실제 저신뢰 AI 추적 결과를 구분할 방법이 전혀 없다.**
  - `backend/src/main/java/kr/co/cudo/authoring/common/client/dto/VlmVerifyResponse.java:15-22`도 동일하게 `results`만 있고 mock 지표 없음(다만 이 VLM verify-objects 자체가 BE 어디서도 호출되지 않는 미배선 상태라 — G-5 TC-AIVLM-14 비고 참조 — 실질 영향은 Track 대비 낮음).
- **재현/확인 경로**:
  ```bash
  # ai-server가 실제로 mock 지표를 보내는 것 확인 (mock 모드 격리 프로세스)
  docker exec -e PYTHONPATH=/app -e AI_MOCK_MODE=true -w /app klid-ai-server python3 -c "
  import asyncio, base64, io
  from app.config import reload_settings; reload_settings()
  from app.routers import sam2 as r
  from app.schemas import Sam2TrackRequest
  from PIL import Image
  img = Image.new('RGB',(50,50)); buf=io.BytesIO(); img.save(buf,format='JPEG')
  b64 = base64.b64encode(buf.getvalue()).decode()
  req = Sam2TrackRequest(track_id='t1', prev_image_b64=b64, next_image_b64=b64, prev_polygon=[[1,1],[2,2],[3,3]])
  print(asyncio.run(r.track(req)))
  "
  # → track_id='t1' polygon=... score=0.9 mock=True source='mock' mock_reason='env_mock'
  # 그러나 backend/.../dto/Sam2TrackResponse.java 는 이 mock/source/mock_reason 3필드를 애초에 매핑하지 않음(Read 확인)
  ```
- **영향**: 데이터 정합성/신뢰성 — SAM2 Track이 실패 시나리오(모델 예외·빈 마스크·env_mock)로 폴백해도 BE/FE 어느 계층도 이를 인지하지 못해, **가짜 추적 결과(이전 프레임 폴리곤 반복, score=0.5)가 정상 AI 추적 결과처럼 라벨 작업본에 병합될 위험**이 있다. 이는 CLAUDE.md에 명시된 "SFR-08-01(라벨링 정확도 향상) = VOS(추적+분할)" 기능의 신뢰성 전제를 훼손한다. 보안 카테고리는 아니며(CWE 해당 없음), 데이터 품질/기능 결함으로 분류.
- **수정 방향(제안)**: `Sam2TrackResponse.java`에 `mock`/`source`/`mockReason` 필드를 `Sam2Response.java`와 동일 패턴으로 추가하고, `Sam2TrackService`가 이를 읽어 `Sam2TrackResponseDto.TrackedItem`까지 전파(예: `mock` boolean 필드 추가)해 FE가 세그먼트와 동일하게 "AI 추적 결과 신뢰 불가" 경고·자동적용 차단을 수행하도록 배선. `VlmVerifyResponse.java`는 verify-objects가 실제 배선되는 시점에 함께 보완. ⚠ 구현하지 않음(검증 스코프).

---


### [G-ISSUE-03] TC-AIYOLO-54 — bytetrack_util.py 전용 단위테스트 파일 부재(트래커 미설치 graceful 포함)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `trackers`(roboflow ByteTrackTracker) 패키지가 미설치/로드실패이거나 트래킹 도중 예외가 발생해도 500 크래시 없이 track_id 미부여로 graceful 처리되어야 하며, 미설치 경고는 프로세스당 1회만 남아야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py`의 `_new_bytetrack_tracker`(37-56행)·`_apply_bytetrack`(59-94행)에 해당 로직이 정확히 구현돼 있으나, `ai-server/tests/`에 `bytetrack_util.py`를 직접 대상으로 하는 테스트 파일이 없다(`test_bytetrack*.py` 부재). 유일하게 관련된 `test_yolox_loader.py:389`(`test_backend_track가_predict후_bytetrack으로_track_id_부여`)는 `_apply_bytetrack` 자체를 `monkeypatch`로 통째로 대체해 검증하므로 실제 내부 로직(예외 처리, WARN-once, -1→None 매핑)은 전혀 실행되지 않는다. 실배포 컨테이너는 `trackers` 패키지가 설치돼 있어(`docker exec klid-ai-server python3 -c "import trackers"` 성공) 미설치 분기를 라이브로도 재현할 수 없었다.
- **재현/확인 경로**: `find ai-server/tests -iname '*bytetrack*'` → 결과 없음. `grep -n "_apply_bytetrack\|tracker_id" ai-server/tests/*.py` → `test_yolox_loader.py:407`의 monkeypatch 대체 1건뿐.
- **영향**: 기능 영향 없음(정적 검토상 코드 정확, 실운영 로그에서도 WARN 정상 관측 — TC-53 참조). 회귀 위험 — 트래킹 그레이스풀 폴백이 향후 깨져도 자동 테스트가 잡지 못한다.
- **수정 방향(제안)**: `tests/test_bytetrack_util.py` 신설 — `trackers` import를 monkeypatch로 실패시켜 `_new_bytetrack_tracker`가 None+WARN-once를 반환하는지, `tracker.update()`가 예외를 던질 때 `_apply_bytetrack`이 그레이스풀한지 직접 단언. 구현은 하지 않음.


### [G-ISSUE-04] TC-AIYOLO-55 — 다중 클래스 track_id 충돌 방지 로직 미검증(단위테스트 부재 + 실동작 미확증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `coco_id_from_label`이 라벨 문자열을 안정적인 정수 class_id로 매핑해, ByteTrackTracker가 클래스별로 트랙 공간을 분리하는 경우 person과 car 등 서로 다른 클래스의 track_id가 충돌하지 않아야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:74-80`(`class_id=np.array([coco_id_from_label(d.label) for d in dets], ...)`)에서 라벨별 고유 정수를 산출하는 로직은 확인되나(G-ISSUE-03과 동일 사유로 전용 단위테스트 없음), **실동작 검증도 미완**이다 — frame-4.jpg(car+truck 동시 검출)로 6프레임 연속 track 요청을 시도했으나 두 클래스 모두 ByteTrack 활성화 임계(저신뢰 detection, score 0.10~0.44)를 넘지 못해 track_id가 계속 `None`(미확정 트랙)으로 남아, **서로 다른 클래스가 실제로 별개 트랙 ID를 받는지 값 자체로는 확인하지 못했다**.
- **재현/확인 경로**: `curl -X POST http://localhost:19300/infer/yolo/track -d '{"image_b64":"<frame-4.jpg base64>","clip_id":"c1","frame_index":0,"conf_threshold":0.1}'` — 응답의 `track_id`가 전부 `null`. 고신뢰 검출(예: score ≥ 0.5 이상의 명확한 person 객체가 포함된 실영상 프레임)로 재시도해야 track_id 비-null 값 비교가 가능.
- **영향**: 기능 영향 미확정(코드 검토상 위험 신호 없음). 다중 클래스 트래킹은 오토라벨링 정확도(SFR-08-01 VOS)와 직결되는 영역이라 회귀 검증 공백이 방치되면 발견이 늦어질 수 있음.
- **수정 방향(제안)**: G-ISSUE-03과 함께 `bytetrack_util.py` 전용 단위테스트에 person+car 동시 검출 합성 픽스처를 넣어 `coco_id_from_label` 매핑값이 실제로 다른 class_id를 산출하고 tracker_id가 클래스 간 충돌하지 않는지 직접 단언. 별도로 고신뢰 실영상 프레임 확보 시 실동작 재확인 권장. 구현은 하지 않음.

---


### [G-ISSUE-21] TC-AIINFRA-06 — SAM2 points 좌표쌍 길이 미검증 → 500(입력검증 누락, CWE-20)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 잘못된 형식의 `points` 입력(좌표쌍 요소 수 부족)은 스키마 검증 단계에서 깨끗한 400으로 거부되어야 한다. `box` 필드는 이미 `min_length=4, max_length=4`로 길이를 강제하는데(`schemas.py:153-155`), `points: list[list[float]] | None`은 바깥 리스트 길이만 암묵 검증될 뿐 **안쪽 각 좌표쌍이 정확히 2개 요소(x,y)를 가져야 한다는 제약이 없다**.
- **현재 동작(이슈 내용)**: `points=[[]]`(빈 내부 리스트) 또는 `points=[[1]]`(요소 1개)을 보내면 pydantic 검증은 통과하고, 이후 두 경로 중 하나에서 처리되지 않은 예외로 500이 발생한다.
  1. **실모델 경로**: `_real_segment`가 내부적으로 numpy 변환·predict를 시도하다 실패 → `except Exception` 가드가 이를 잡아 `_mock_segment(width, height, req, "empty_mask")`로 폴백을 시도하는데, 그 안에서 `cx, cy = req.points[0][0], req.points[0][1]`(`sam2.py:306`)이 다시 `req.points`(=`[[]]`)를 그대로 참조해 **IndexError**가 발생한다. 즉 "graceful fallback"으로 설계된 코드 경로 자체가 방어되지 않은 입력에 대해 2차로 크래시한다.
  2. 이 2차 예외는 `sam2.py`에 잡히지 않고 `exceptions.py:65-69`의 전역 `Exception` 핸들러까지 전파되어 500으로 응답된다(스택트레이스는 서버 로그에만 남고 HTTP 응답에는 노출되지 않음 — CWE-209는 해당 없음).
  - 실측 로그(`docker logs klid-ai-server`):
    ```
    File "/app/app/routers/sam2.py", line 220, in _real_segment
        return _mock_segment(width, height, req, "empty_mask")
    File "/app/app/routers/sam2.py", line 306, in _mock_segment
        cx, cy = req.points[0][0], req.points[0][1]
    IndexError: list index out of range
    ```
- **재현/확인 경로**:
  ```bash
  curl -s -w "\nHTTP:%{http_code}\n" -X POST http://localhost:19300/infer/sam2/segment \
    -H "Content-Type: application/json" \
    -d '{"image_b64":"<유효한 base64 JPEG>","points":[[]]}'
  # → 500 {"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}
  # points:[[1]] (좌표 1개만)도 동일하게 500 재현됨
  ```
- **영향**: 보안 정보유출은 없음(CWE-209 미해당, 메시지 일반화됨). 다만 CWE-20(Improper Input Validation) — 마땅히 400이어야 할 요청이 서버측 미처리 예외 경로를 타면서 ①불필요한 ERROR 레벨 로그 오염(정상 요청과 공격/오탐 구분이 로그 레벨만으론 어려워짐) ②"graceful mock fallback"이라는 설계 의도가 이 입력 한정으로 깨져 가용성 저하 가능성.
- **수정 방향(제안)**: `Sam2SegmentRequest.points`(및 `Sam2TrackRequest.prev_polygon`도 동일 패턴 점검)에 pydantic 필드 검증으로 각 내부 좌표가 정확히 2개 요소인지 강제(`box`가 이미 하는 것과 동일하게 예: `Annotated[list[float], Field(min_length=2, max_length=2)]` 원소 타입으로 재정의, 또는 `field_validator`로 `all(len(p) == 2 for p in points)` 단언). 아울러 `_mock_segment`의 fallback 분기 자체도 `req.points`가 비정상일 때를 대비해 방어적으로 작성하면 이중 안전망이 된다. ⚠ 구현하지 않음(검증 스코프).


### [G-ISSUE-41] TC-AIMOCK-05 — `MOCK_OUTPUT_BASE` 미설정 시 산출물 0건인데 목이 "완료(procState=2)"로 보고한다 (형제 경로와 비대칭)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목이 "완료"를 보고하는 것은 **BE 가 회수 가능한 산출물이 최종 경로에 실재한다**는 뜻이어야 한다. `deid_sim.py` 스스로 이 원칙을 명문화하고 있다 — `OutputWriteResult` docstring(`deid_sim.py:376-393`)은 "아무 파일도 남기지 않는데 성공으로 보고돼 `production=SUCCEEDED`/`files=0`/`procState=2`(진행률 100) 라는 **거짓 완료**가 나왔다"를 폐쇄 대상 결함으로 기술하고, 형제 경로인 `OUTPUT_DIR_REJECTED`(export_path 경계 위반)는 `failed=True` → `procState=99` 로 보고한다.
- **현재 동작(이슈 내용)**: 허용 루트 자체가 **미설정**인 경우만 예외적으로 `failed=False` 로 빠져나간다.

  `mock-server/app/services/deid_sim.py:1705-1713`
  ```python
  if not output_base:
      global _base_unset_warned
      if not _base_unset_warned:
          logger.warning(
              "[MOCK][KPST] MOCK_OUTPUT_BASE 미설정 — 더미 비식별 출력 파일 미생성"
              "(fail-closed). e2e 시 BE 의 STORAGE_RAW_MOUNT_ROOTS 와 같은 값(콤마 구분)으로 설정하세요.")
          _base_unset_warned = True
      return ProductionOutcome(written=written)      # ← failed 기본값 False
  ```
  `run_production`(`deid_sim.py:1953-1964`)이 `outcome.failed` 만 보므로 `PRODUCTION_SUCCEEDED` → `proc_state_for` → **`procState=2`**. 바로 아래 형제 분기(`deid_sim.py:1716-1725`)는 같은 "쓸 수 없음"인데 `failed=True, reason="OUTPUT_DIR_REJECTED"` → **99**.
- **재현/확인 경로**(`MOCK_OUTPUT_BASE=""` 로 mock 을 기동한 환경에서):
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' -d '{
    "project_name":"chk-nobase","creator":"qa","export_path":"/app/storage/raw/seed/x/deid",
    "input_path":"/app/storage/raw/seed/","files":["sample-cctv-1080p.mp4"],"is_img":0}'
  # 몇 초 뒤
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk-nobase"}'   # → dsStatus[].procState 관찰(현재 2 예상)
  ```
  대조군(현 배포에서 즉시 재현 가능): `export_path=/tmp/g3v-escape` → `procState=99`(본 회차 prj_id=7 실측).
- **영향**: 목이 완료를 보고하면 BE(`KpstDeidentService`)는 존재하지 않는 산출물을 회수하러 가 무결성 검증에서 탈락 → `DE_IDNTF_YN='F'` 로 종결하는데, 로그에는 "완료 후 회수 실패"만 남아 **원인(설정 누락)이 드러나지 않는다**. 게다가 `projectName`(=`raw{rawSn}`)이 이미 점유돼 재위탁이 409 로 막히는 조용한 고착이 된다 — 이 파일이 다른 경로들에서 제거하려 한 바로 그 실패 모드다. 스코프는 **목 서버 오설정 시에 한정**(운영 영향 없음), 다만 로컬·dev 검증 전체가 이 목을 경유하므로 디버깅 비용이 크다.
- **수정 방향(제안)**: `output_base` 미설정 분기도 `ProductionOutcome(written=[], failed=True, reason="OUTPUT_BASE_UNSET")` 로 통일한다. 단 **명시적 opt-out 인 `write_output_files=false`(`deid_sim.py:1931-1934`)와는 구분**해야 한다 — 그쪽은 "만들지 않기로 한 설정"이라 성공 no-op 이 옳고, 이쪽은 "설정 누락"이다. ⚠ **구현하지 않는다**(검증 회차 스코프 밖).


### [G-ISSUE-43] TC-AIMOCK-44 — VLM 타임아웃 "2계층(10s/45s)" 전제가 소멸했다(논블로킹 전환). 카탈로그·UNCERTAINTIES #13 동시 스테일

- **심각도**: MEDIUM (문서·기대값 정합. 운영 동작은 개선된 상태)
- **기대 동작(기대효과)**: 카탈로그 = "WebClient 자체 10s(`vlm.client.timeout-seconds`) 안쪽에 배치 오케스트레이션 블록 45s(`BLOCK_TIMEOUT`)". UNCERTAINTIES #13 = "블록 상한 45s ↔ 실효 클라이언트 타임아웃 10s".
- **현재 동작(이슈 내용)**: **45s 블록 계층 자체가 없다.** `VlmTimeseriesStep` 은 Phase C-1 에서 논블로킹 제출로 전환됐다.

  `backend/.../batch/step/VlmTimeseriesStep.java:361-377` (발췌)
  ```java
  vlmClient.submitTimeseries(req)
      .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
      .subscribe(
          resp -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                  () -> outcomeRecorder.onAccepted(rawSn, requestId, resp)),
          err  -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                  () -> outcomeRecorder.onSubmitFailed(rawSn, markingSn, err)));
  ...
  return VlmTimeseriesResponse.submitted(requestId);   // ACK 도 기다리지 않는다
  ```
  검증: `grep -rn "BLOCK_TIMEOUT\|block(Duration"` → `VlmTimeseriesStep`·`VlmClient` **0건**(잔존은 `ControlNotifyClient.java:52` 의 별개 상수). `VlmTimeseriesStep.java` 안의 문자열 `45` 는 349행 **과거 동작을 설명하는 주석**뿐. 카탈로그가 가리키는 `VlmTimeseriesStep.java:78` 은 현재 javadoc 본문이다.
  따라서 실효 타임아웃은 **WebClient 10s 단일**(`VlmClient.java:71-76,108` + `application.yml:633 timeout-seconds: 10`) + `retry vlmClient`(max-attempts 3 · wait 1s · multiplier 2, `application.yml:547-553`)이며, 파이프라인 스레드는 **전혀 점유되지 않는다**.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "BLOCK_TIMEOUT\|\.block(" src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java \
      src/main/java/kr/co/cudo/authoring/common/client/VlmClient.java   # → 0건
  grep -n "timeout-seconds" src/main/resources/application.yml           # → 633: timeout-seconds: 10
  # 회귀 가드(실행하지 말 것 — 파일:메서드만 대조):
  # backend/src/test/.../VlmTimeseriesStepNonBlockingTest.java:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다
  ```
- **영향**: 문서 기준으로 "45s 안에 스텝이 실패한다"를 전제한 운영 판단·회귀 케이스가 어긋난다. 실패 경로도 바뀌었다 — 제출 실패는 **스텝 FAILED 가 아니라** `SKIP_REASON_SUBMIT_FAILED` 감사행 + `VlmSubmitPendingSweeper`/`VlmWithheldResumeRunner` 회수로 흐르므로, "45s 뒤 배치 FAILED"를 기다리는 관측은 영원히 오지 않는다.
- **수정 방향(제안)**: ①TC-AIMOCK-44 기대결과를 "실효 타임아웃 = WebClient 10s 단일 + retry 3회. 스텝은 ACK 를 기다리지 않으며(논블로킹) 미수신은 `SKIP_REASON_ACK_MISSING`/`SKIP_REASON_CALLBACK_MISSING` 로 회수" 로 교체하고 근거를 `VlmClient.java:71-76,108` / `VlmTimeseriesStep.java:361-381` 로 이동 ②UNCERTAINTIES #13 의 "블록 상한 45s" 서술 삭제(미확정은 IntelliVIX 실서버 대조만 남김). ⚠ **구현하지 않는다**.


### [G-ISSUE-44] (연관 TC-AIMOCK-19 / 3자 대조표 C) — BE 가 생성형 AI 산출물의 `checksum` 을 선언만 하고 검증하지 않는다 (`media_metadata` 는 아예 폐기)

- **심각도**: MEDIUM (CWE-345 데이터 진정성 검증 부재)
- **기대 동작(기대효과)**: 벤더가 `results[].checksum`(SHA-256)을 주는 이유는 **우리가 그 파일을 읽어 파생 프레임으로 반입하기 전에 무결성을 확인**하라는 것이다. 반입 경로는 NAS 공유 마운트라 전송 중단·부분 기록·교체가 실재하는 실패 모드다.
- **현재 동작(이슈 내용)**: 목(=명세서)은 실제 해시를 계산해 보낸다.

  `mock-server/app/services/genai_sim.py:446-457`
  ```python
  results.append({
      "generated_data_id": uuid.uuid4().hex,
      "media_type": media_type.value,
      "output_file_path": str(target),
      "checksum": _sha256_of(target),
      "media_metadata": {"mime_type": _MIME_BY_EXT.get(ext, "application/octet-stream"),
                         "size_bytes": target.stat().st_size},
  })
  ```
  BE `GenAiCallbackRequest.ResultItem`(`webhook/dto/GenAiCallbackRequest.java:96-117`)은 `checksum` 을 **선언만** 하고, `media_metadata` 는 **선언조차 없어** `@JsonIgnoreProperties(ignoreUnknown = true)` 로 조용히 버려진다. 소비 지점(`GenAiCallbackService.verifiedOutputPaths`, 214-237)은 `item.outputFilePath()` 만 쓴다:
  ```java
  for (GenAiCallbackRequest.ResultItem item : results) {
      artifactRootResolver.verifyExternalReadablePath(item.outputFilePath());  // 경로 경계만
      paths.add(item.outputFilePath());                                        // checksum 미사용
  }
  ```
  검증: `grep -n "checksum\|Checksum\|sha256\|mediaMetadata" GenAiCallbackService.java AugmentResultService.java` → **0건**.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "checksum" src/main/java/kr/co/cudo/authoring/webhook/ | grep -v "dto/"   # → 0건
  # 실동작 대조(본 회차 실측): mock 이 내려준 checksum 과 파일 해시는 일치하지만 BE 는 그 값을 읽지 않는다
  docker exec klid-mock-server sha256sum /app/genai-out/genai/<job_id>/001_frame-1_genai.jpg
  ```
- **영향**: 경로 경계(CWE-22)는 막지만 **내용 진정성은 확인하지 않는다**. 부분 기록·손상·교체된 산출물이 그대로 파생영상 프레임으로 확정되고(`AugmentFrameProducer`), 그 뒤에는 라벨링·검수·데이터마트까지 흘러간다. 같은 계약의 `input_files[].checksum`/`source_file_id` 도 **송신하지 않고 있어**(3자 대조표 A), 결과 짝짓기가 `LS_DATA_AUG_JOB_FILE` 의 **순서**에만 의존한다 — 순서가 어긋나면 다른 프레임에 남의 증강본이 붙는데(코드 주석도 이 위험을 명시), 그 방어가 "건수 일치" 하나뿐이다.
- **수정 방향(제안)**: ①`applySucceeded` 의 경로 되붙이기 직전에 `checksum` 이 존재하면 파일 해시와 대조하고 불일치 시 `ERR_RESULT_CHECKSUM_MISMATCH` 로 job FAILED(기존 `ERR_RESULT_COUNT_MISMATCH` 와 동일한 fail-closed 규약) ②`media_metadata` 를 DTO 에 선언해 최소한 `size_bytes` 를 실파일과 대조(선언 없이 버리면 벤더가 무엇을 보내는지 코드만 보고 알 수 없다) ③중기적으로 `input_files[].source_file_id` 를 송신하고 결과의 대응 필드로 짝짓기해 순서 의존을 제거. ⚠ **구현하지 않는다**(계약 변경은 벤더 협의 대상).


### [G-ISSUE-01] TC-AIYOLO-26 — mock WARN 1회 로그를 단언하는 회귀 가드 없음
- **심각도**: LOW
- **기대 동작(기대효과)**: 프로세스 수명 동안 mock 응답이 처음 발생할 때만 WARN 로그를 남기고 이후 반복 호출에서는 남기지 않아야 한다(로그 폭주 방지). 코드(`_warn_mock_once`)가 이를 구현하고 있다면, 리팩터링 중 실수로 가드가 깨져도(예: `if not _mock_warned` 조건 삭제) 자동 테스트가 잡아야 한다.
- **현재 동작(이슈 내용)**: `app/routers/yolo.py:73-83`의 `_warn_mock_once`는 전역 불리언 플래그로 정확히 구현돼 있으나, `tests/test_yolo_dispatch.py:48`은 `yolo_router.reset_mock_warn_flag()`를 호출만 할 뿐 `caplog`로 실제 WARN 횟수(2회 호출 시 1회만 발생)를 단언하지 않는다. 프로젝트 전체 테스트에서 `caplog` 사용 자체가 0건(grep 확인).
- **재현/확인 경로**: `grep -rn "caplog" ai-server/tests/` → 결과 없음. 코드 리뷰: `ai-server/app/routers/yolo.py:73-83`.
- **영향**: 기능 영향 없음(현재 로직은 정확). 회귀 위험 — 향후 리팩터링 시 WARN 폭주 회귀가 CI에서 잡히지 않는다.
- **수정 방향(제안)**: `test_yolo_dispatch.py`에 `caplog.set_level(logging.WARNING)` + `client.post(...)` 2회 호출 후 WARN 레코드 수 `==1` 단언 테스트 추가. 구현은 하지 않음.


### [G-ISSUE-02] TC-AIYOLO-36 — anchor 개수 불일치 방어 분기 단위테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: YOLOX raw grid decode 시 예상치 못한 모델/해상도로 anchor 총합이 grid 계산과 어긋나면 IndexError/broadcast 오류 없이 원본을 그대로 반환해야 한다(크래시 방지).
- **현재 동작(이슈 내용)**: `ai-server/app/models/yolox_loader.py:135-136`에 방어 코드가 정확히 존재(`if predictions.ndim != 3 or predictions.shape[1] != grids.shape[1]: return predictions`)하지만, `tests/test_yolox_loader.py`의 raw-grid 테스트(`test_YOLOX_raw_grid_출력이_grid_stride_복원으로...`, 85행)는 항상 정합하는 anchor 수(84)만 사용해 이 분기를 실행하지 않는다. `grep -n "anchor" tests/test_yolox_loader.py`는 주석 3건만 매칭, 실제 mismatch 입력 케이스 없음.
- **재현/확인 경로**: `ai-server/tests/test_yolox_loader.py:85-127` 확인, mismatch 케이스 부재.
- **영향**: 기능 영향 없음(현재 코드 정확). 회귀 위험 — 이 분기가 깨져도(예: 조건 반전) 테스트가 GREEN을 유지한다.
- **수정 방향(제안)**: `input_size`와 어긋나는 shape(예: (1, 10, 85))의 합성 텐서를 `_decode_grid_if_needed`에 직접 넣어 원본 그대로 반환되는지 단언하는 테스트 추가. 구현은 하지 않음.


### [G-ISSUE-42] TC-AIMOCK-07 — 카탈로그 기대값(placeholder 대체)이 스테일: 현재는 산출 실패로 종결한다

- **심각도**: LOW (카탈로그 정합성 — 제품 결함 아님)
- **기대 동작(기대효과)**: 카탈로그 기대결과 = "target 파일이 원본 복사가 아닌 **18바이트 placeholder**".
- **현재 동작(이슈 내용)**: placeholder 산출은 **의도적으로 폐기**됐고 최종 경로를 선점하지 않은 채 산출 실패로 끝난다.

  `mock-server/app/services/deid_sim.py:212-218`
  ```
  ★ #3 — placeholder 산출물은 폐기됐다. 원본을 읽지 못하는 경우(부재/권한/허용 루트 밖/입력 마운트
    불일치) 구 구현은 18바이트 스텁을 최종 경로에 쓰고 완료(procState=2)로 보고했다. 그 파일은 BE
    무결성(≥512B + 컨테이너 시그니처)에서 탈락해 'F' 가 되는데, no-overwrite 라 그 이름은 이후 어떤
    재시도로도 대체되지 않는다(영구 고착). 지금은 산출 실패(procState=99) 로 종결한다 …
    유효 크기의 가짜 영상으로 대체하는 안은 채택하지 않았다: 읽지도 못한 원본을 '비식별 완료'로
    승인시키는 위장 산출물이 되기 때문이다(CWE-345).
  ```
  실측(본 회차 prj_id=8, `input_path=/etc/`): export 디렉터리는 **빈 채로 생성**, WARN 2줄 후 `production failed reason=OUTPUT_WRITE_FAILED` → `procState=99`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' -d '{
    "project_name":"chk-inescape","creator":"qa","export_path":"/app/storage/raw/seed/chk/deid",
    "input_path":"/etc/","files":["hostname"],"is_img":0}'
  docker exec klid-mock-server ls -la /app/storage/raw/seed/chk/deid   # → 빈 디렉터리
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk-inescape"}'                   # → procState 99
  ```
- **영향**: TC 의 보안 목적(허용 루트 밖 임의 파일 노출·디스크 고갈 차단)은 **더 강하게** 충족된다. 다만 기대값 그대로 자동검증을 짜면 위양성 FAIL 이 난다.
- **수정 방향(제안)**: 카탈로그 기대결과를 "원본을 읽지 않고 **산출 실패(procState=99)** 로 종결, 최종 경로 미선점(placeholder 없음)"으로 정정. 회귀 가드는 `mock-server/tests/test_deid_output.py:323` 이 이미 보유. ⚠ **구현하지 않는다**.


## H 클러스터 — 6건

### [H-ISSUE-41] TC-FE-056 — SAM2 Track mock 폴백 결과가 라벨링 화면에서 신뢰 가능한 결과와 동일하게 자동 병합·성공 토스트됨

- **심각도**: HIGH
- **기대 동작(기대효과)**: SAM2 분할(Segment)과 동일하게, ai-server 가 mock 폴백(실모델 추론 실패)으로 응답한 추적 결과는 화면에 **자동 적용되지 않고** 경고로 표시돼야 한다(CLAUDE.md SFR-08-01: "SAM2 분할(클릭/박스→폴리곤+신뢰도, mock 응답은 FE 자동적용 차단)"). Track 도 동일 계약 대상이다(C-ISSUE-61 근거).
- **현재 동작(이슈 내용)**: `frontend/src/pages/label/LabelingPage.tsx:634-677 handleTracked` 가 `tracked` 결과를 mock 여부·신뢰도와 무관하게 무조건 `mergeAutoLabels`/`stashPendingTracks` 하고, `partial===false` 이면 항상 성공 토스트를 띄운다.
  ```tsx
  const handleTracked = useCallback(
    (tracked: Sam2TrackedItem[], partial: boolean) => {
      ...
      let applied = 0;
      if (forCurrent.length > 0) {
        applied += mergeAutoLabels(
          forCurrent.map((t) => trackedItemToLabel(t, currentFrame.frameNo)),
        );
      }
      ...
      if (partial) {
        pushToast({ variant: 'warning', message: `${applied}/${total} 프레임만 추적됨 (일부 실패)` });
      } else {
        pushToast({ variant: 'success', message: `AI 추적 완료 (${applied}프레임)` }); // mock 여부 무관
      }
    }, [...]);
  ```
  근본 원인은 BE 계약 단절이다 — `Sam2TrackResponse.java`(BE→FE DTO)가 ai-server 가 실제로 보내는 `mock`/`source`/`mock_reason` 필드를 선언하지 않아 Jackson 이 조용히 버린다(C-part4.md `C-ISSUE-61` 실측: `prevPolygon` 역전 좌표로 mock 폴백을 유도했을 때 BE 가 **200** + `message:null` 로 실추론과 구분 불가능한 응답을 내려줌). FE `useSam2Track`/`handleTracked` 는 애초에 판단 재료를 받지 못하므로 분기를 만들 수 없는 상태다.
- **재현/확인 경로**: `POST /v1/frames/{srcSn}/sam2-track` 요청에 실모델이 mask 를 못 찾는 입력(예: 극단 좌표)을 넣으면 ai-server 가 `[SAM2] track real returned no mask — prev polygon fallback` 경고 로그와 함께 입력 폴리곤을 그대로 반사한 가짜 추적 결과를 반환(C-part4 실측). 이 응답이 라벨링 화면의 `handleTracked` 를 그대로 통과해 병합 + "AI 추적 완료" 토스트로 이어진다. 회귀 방지 테스트도 0건(`grep -rln "handleTracked" **/*.test.tsx` → 0건, `LabelingPageAutolabelToast.test.tsx` 는 Detect 경로만 커버).
- **영향**: 사용자가 신뢰할 수 없는 자동 추적 결과(가짜 폴리곤)를 실제 검출로 오인해 검수 없이 그대로 저장할 위험. 데이터 품질 저하(오검출 라벨 유입) — CWE 분류 대상은 아니나 SFR-08-01 요구사항(VOS 신뢰도 게이팅) 미충족.
- **수정 방향(제안)**: (구현하지 않음) ①BE `Sam2TrackResponse`/`Sam2TrackResponseDto.TrackedItem` 에 `mock`/`source`/`score` 필드를 Segment 와 동일하게 선언·전파 ②`handleTracked` 에 Segment 의 `res.message` 분기와 동일한 mock 경고 분기 추가(자동 병합 차단 + 경고 토스트) ③회귀 테스트 신설(`handleTracked` mock 케이스).

---


### [H-ISSUE-21] TC-FE-146 — ReviewPage 에 증강여부/종류(파생영상) 표시 기능이 실제로 존재하지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과 "증강여부/종류 표시" — 검수 화면에서 현재 검수 중인 영상이 증강/해상도 파생 영상인지, 어떤 종류(WINTER/NIGHT/RAIN/RESL_*)인지 검수자가 식별할 수 있어야 한다(근거로 명시된 `ORGNL_RAW_SN`/`VMS_CLIP_ID` 파생 판별 — CLAUDE.md "reviewpage-augmented-list-facts" 메모리 항목과 정합하는 동작).
- **현재 동작(이슈 내용)**:
  1. `frontend/src/features/review/components/ReviewMetaPanel.tsx`(244줄 전체 확인) — event_annotation + 시계열 메타만 읽기 표시. 증강/파생 관련 필드·문구 0건.
  2. `frontend/src/features/review/components/ReviewHeader.tsx`(93줄 전체) — cctvName/workerName/submittedAt/frame counter/StatusBadge 만 렌더. 증강 표시 없음.
  3. `frontend/src/features/review/types.ts:9-22` — `Review` 인터페이스에 `orgnlRawSn`/`augType`/`augmented`/`vmsClipId` 필드 자체가 없음:
     ```ts
     export interface Review {
       id: number; videoId: number; cctvName: string; workerId: number;
       workerName: string; submittedAt: string; labelCount: number;
       status: ReviewStatus; reviewerId?: number;
       eventName?: string | null; eventTypeCd?: string | null;
     }
     ```
  4. BE `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java`(117줄 전체) — record 필드에도 증강 관련 값이 없음(id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd 뿐).
  5. 실 HTTP 확인: `GET /api/v1/reviews/132`, `GET /api/v1/reviews?page=0&size=5` (REVIEWER 토큰) 응답 어디에도 증강 필드 없음(응답 예: `{"id":132,"cctvName":"CCTV-강남구-001",...,"eventTypeCd":"INTRUSION"}`).
  6. 참고로 **작업목록(`GET /v1/tasks/board`)에는 `augmented`/`augType` 필드가 실존**한다(예: `{"videoId":131,...,"augmented":true,"augType":"RESL_480P"}`) — 즉 이 정보 자체는 BE 도메인에 존재하지만 검수(`/v1/reviews*`) 경로로는 전달되지 않는다.
  7. 테스트 커버로도 재확인: `ReviewMetaPanel.test.tsx`(features/review/__tests__/ReviewPageMetaPanel.test.tsx) 4개 케이스(event_annotation 렌더/시계열 렌더/버튼 미노출/빈상태) 중 증강 관련 케이스 0건.
- **재현/확인 경로**: REVIEWER 로 `/review/{id}` 진입(또는 위 curl) — 어떤 영상(증강 파생이든 아니든)이어도 화면·응답에 증강 여부/종류 표시가 없음.
- **영향**: 기능 누락(보안 이슈 아님). 검수자가 증강/해상도 파생 영상을 원본과 구분 없이 검수하게 되어, CLAUDE.md 의 "파생영상도 기존 플로우와 동일하게 검수" 원칙은 지켜지지만 "무엇을 검수 중인지"에 대한 맥락 정보가 빠져 있다. 카탈로그가 이 정보의 화면 노출을 기대값으로 잡았다면 실제 구현과 불일치.
- **수정 방향(제안)**: ⚠ 구현하지 않는다 — `ReviewResponse`(BE)에 `orgnlRawSn`/`augType` 추가 후 `Review`(FE 타입)·`ReviewHeader`/`ReviewMetaPanel`에 배지 형태로 노출하는 방향이 board 목록과의 일관성 면에서 가장 자연스러워 보이나, 카탈로그 기대값 자체를 "현재 미구현"으로 재분류할지 BE/FE 를 보강할지는 정책 결정 필요.

---


### [H-ISSUE-61] TC-FE-149 — 사용자 관리 화면의 역할/상태 필터가 BE 파라미터를 쓰지 않고 현재 페이지만 client-side 필터링
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프로젝트 규칙("목록 화면 정렬·필터 정책") — "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다". BE `UserController.list`(`/v1/users`)는 이미 `role` 쿼리 파라미터(정규식 화이트리스트 `REVIEWER|WORKER|PORTAL_USER`)를 지원한다.
- **현재 동작(이슈 내용)**: `frontend/src/pages/manage/UserManagePage.tsx:86-95` 의 `params` 는 `keyword`/`page`/`size` 만 BE 로 전송하고 `roleFilter`/`statusFilter` 는 포함하지 않는다. 대신 `136-145`행:
  ```tsx
  const allRows = data?.content ?? [];
  const filteredRows = useMemo(() => {
    return allRows.filter((u) => {
      if (roleFilter && u.role !== roleFilter) return false;
      if (statusFilter === 'active' && !u.active) return false;
      if (statusFilter === 'inactive' && u.active) return false;
      return true;
    });
  }, [allRows, roleFilter, statusFilter]);
  ```
  로 **현재 로드된 페이지(`data.content`, 기본 20건)** 만 필터링한다. 그런데 `DataTable` 에 넘기는 `totalElements={data?.totalElements ?? 0}` 은 **필터 적용 전 BE 전체 카운트**를 그대로 쓴다(`325-335`행). BE `listUsers` API(`features/user/api.ts`) 는 `role`/`active` 파라미터를 이미 축조해 보낼 수 있는 타입(`UserListParams`)까지 갖췄고, 실제로 같은 코드베이스의 `AssignModal.tsx` 는 `useUsers({ role: Role.REVIEWER, size: 50 })` 로 서버측 role 필터를 정상 사용 중이다 — 즉 UserManagePage 만 이 패턴을 쓰지 않는다.
- **재현/확인 경로**: 시드 데이터가 5명(REVIEWER 2·WORKER 2·PORTAL_USER 1)뿐이라 1페이지(size=20)에 다 들어와 현재 환경에서는 필터 결과와 페이지네이션이 우연히 일치해 육안으로 드러나지 않는다. 사용자가 21명 이상으로 늘어나고 역할 필터를 걸면: (1) 2페이지 이후 사용자는 필터 후보에서 아예 빠짐(현재 페이지 데이터만 filter 대상), (2) 하단 페이지네이션은 여전히 "전체 N명" 기준으로 여러 페이지를 보여줘 필터링된 화면과 불일치.
- **영향**: 기능 정확성 결함(보안 취약점 아님). 대량 사용자 환경에서 역할/상태 필터가 "현재 페이지에서만 거르고 끝"이라 다른 페이지의 매칭 대상을 놓친다 — 프로젝트 구속 규칙 정면 위반.
- **수정 방향(제안)**: `params` useMemo 에 `role: roleFilter || undefined` 를 포함해 BE 로 전송(BE 는 이미 지원). `active` 는 BE `UserController` 에 파라미터가 없으므로 ①BE 에 `active` 쿼리 파라미터 추가 후 서버 필터로 전환하거나 ②상태 필터를 제거(관제서버 책임 영역이라는 페이지 주석과의 정합도 함께 검토). 구현하지 않음(검증 전용 에이전트 — 지시에 따름).


### [H-ISSUE-62] TC-FE-157 — 증강 중복 요청 409 안내가 FE 에서 유실되어 항상 동일한 일반 메시지만 노출
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프로젝트 규칙 — "중복 요청은 409 이며 안내는 PENDING(\"완료·반려 후 재요청 가능\") / ACCEPTED(\"같은 영상·종류로는 다시 요청할 수 없음\")로 갈려야 한다". BE `AugmentRequestService.duplicateGuidance()`(`backend/src/main/java/kr/co/cudo/authoring/augment/service/AugmentRequestService.java:264-276`)가 정확히 이 요구사항대로 상태별 실행 가능 안내 문구를 만들어 `CustomException(ErrorCode.CONFLICT, ...)` 로 던진다.
- **현재 동작(이슈 내용)**: 실제 BE 호출로 확증:
  ```
  POST /api/v1/augments/request {"videoIds":[136],"types":["NIGHT"]}
  → 409 {"message":"이미 채택된 증강입니다. 채택된 증강은 되돌릴 수 없어 같은 영상·종류로는 다시 요청할 수 없습니다.","errorCode":"CONFLICT"}
  ```
  그러나 `frontend/src/pages/AugmentRequestPage.tsx:159-161`:
  ```tsx
  onError: () => {
    pushToast({ variant: 'error', message: '증강 요청 실패' });
  },
  ```
  `onError` 콜백이 인자(`err`)를 아예 받지 않고 항상 고정 문구 `'증강 요청 실패'`만 토스트로 노출한다. 즉 BE 가 애써 PENDING/ACCEPTED 를 구분해 만든 안내 문구가 **사용자에게 절대 도달하지 않는다** — 사용자는 왜 실패했는지, 기다리면 되는지 다시 요청할 수 없는지 알 방법이 없다. 같은 파일의 해상도 파생 경로(`resolutionErrorMessage`, `260-263`행)는 `ApiError.userMessage` 를 제대로 추출해 노출하는 반면, 증강 요청 경로만 이 패턴을 쓰지 않는 비일관도 있다.
- **재현/확인 경로**: 위 curl 재현 완료(rawSn 136, NIGHT 타입, 기존 ACCEPTED 상태에서 재요청 시 409 + 상태별 메시지 확인). FE 코드상 `useRequestAugment`(`features/augment/hooks/useAugmentDecision.ts:16-27`)의 `onError: options.onError` 로 그대로 전파되므로 `AugmentRequestPage` 호출부의 무시가 원인.
- **영향**: 보안 취약점 아님(정보 노출 반대 방향 — 오히려 유용한 정보가 유실). 사용자 경험 결함이며, 프로젝트가 2026-07-29 자로 "수행 가능한 동선만 말한다"고 명시적으로 재설계한 BE 문구가 FE 에 배선되지 않아 그 설계 의도가 무력화된다.
- **수정 방향(제안)**: 다른 mutation(해상도 파생, `resolveErrorMessage` in `PresetListPage.tsx`, `extractBeMessage` in `AssignModal.tsx`)에서 이미 쓰는 `ApiError.userMessage` 또는 `resolveApiMessage()` 패턴을 `AugmentRequestPage.tsx:159-161` 의 `onError` 에도 적용해 BE `message` 를 그대로 토스트에 반영. 구현하지 않음.


### [H-ISSUE-63] TC-FE-176 — 포털 홈 "라벨링 완료" KPI 가 실제 데이터와 무관하게 항상 0으로 하드코딩
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: KPI 카드는 실제 사용자 데이터를 반영해야 한다(README 검증 기준 "본 프로그램이 외부 응답 없이 값을 자체 생성하면(self-fill) 결함"과 동일 성격의 패턴).
- **현재 동작(이슈 내용)**: `frontend/src/pages/portal/PortalHomePage.tsx:61`:
  ```tsx
  <KpiCard label="영상 수" value={totalVideos} unit="건" />
  <KpiCard label="라벨링 완료" value={0} unit="건" />
  ```
  "영상 수" 는 `useDatamartVideos` 응답(`data.totalElements`)을 바인딩하지만, 바로 아래 "라벨링 완료" 는 어떤 훅/상태와도 연결되지 않은 리터럴 `0` 이다. 사용자가 포털에서 실제로 라벨을 저장(`useSavePortalLabels` → `POST /v1/portal/user-labels`)해도 이 카드는 영구히 "0건"으로 표시된다. 테스트(`PortalHomePage.test.tsx:129`)도 텍스트 존재만 확인(`getByText(/라벨링 완료/)`)할 뿐 값 검증이 없어 이 하드코딩이 회귀 가드 없이 방치돼 있다.
- **재현/확인 경로**: 소스 열람만으로 확정(라인 61) — `value={0}` 이 상수 리터럴이며 어떤 props/query 결과도 참조하지 않음을 코드 레벨에서 확인.
- **영향**: 사용자에게 실제와 다른(항상 축소된) 통계를 보여줘 신뢰도를 해친다. 보안 문제는 아니나 "카운트 self-fill" 은 이 워크스페이스가 여러 클러스터에서 반복적으로 결함으로 분류해온 패턴과 동일 성격.
- **수정 방향(제안)**: 포털 사용자별 라벨링 완료 영상 수를 반환하는 BE 집계 API 신설(예: `LS_PORTAL_USER_LABEL` 기준 distinct 영상 카운트) 후 FE 바인딩. 또는 현재 이 지표를 낼 수 있는 BE 데이터가 없다면 카드 자체를 보류하거나 "준비 중" 표기로 self-fill 을 피한다. 구현하지 않음.

---


### [H-ISSUE-88] TC-FE-242 — 카탈로그가 서술하는 WORKER 목록 필터링 매커니즘이 현재 구현과 불일치(클라이언트 필터 → 서버 필터로 개선됨, 문서 미갱신)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그(H-frontend-e2e.md TC-FE-242)는 "`/v1/assignments` 가 필터·정렬을 지원하지 않아 화면에서 거른다"(WORKER 시각 클라이언트 재필터)로 기술한다. 카탈로그가 실제 구현을 정확히 서술해야 향후 회귀 판정·유지보수 시 잘못된 전제로 판단하지 않는다.
- **현재 동작(이슈 내용)**: 실제로는 BE `GET /v1/assignments` 가 `q`/`workStatus`/`eventTypeCd` 서버 필터를 지원하며(`backend/src/main/java/kr/co/cudo/authoring/assignment/controller/AssignmentController.java:112-137` `@RequestParam` 3종 실재), FE `frontend/src/features/task/boardParams.ts:197-205` 의 `buildAssignmentParams` 주석도 "필터는 **서버**가 전체 배정 기준으로 적용한다(화면 재필터 금지)"로 명시하며, `pages/TaskListPage.tsx:327` `pagedRows = allRows`(WORKER 행에 대한 추가 클라이언트 필터 로직 없음)를 그대로 사용한다. 실 호출로 확인:
  ```
  GET /v1/assignments?page=0&size=3 (WORKER 토큰)                 → 200, totalElements=50
  GET /v1/assignments?q=%EA%B0%95%EB%82%A8&page=0&size=3          → 200, totalElements=22
  ```
  q=강남 필터가 BE 단에서 즉시 반영돼(50→22) 서버 필터가 실제로 동작함을 확인.
- **재현/확인 경로**:
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H "Content-Type: application/json" -d '{"role":"WORKER","channel":"INTERNAL"}')
  curl -s -H "Authorization: Bearer <token>" "http://localhost:18081/api/v1/assignments?page=0&size=3"
  curl -s -G -H "Authorization: Bearer <token>" --data-urlencode "q=강남" "http://localhost:18081/api/v1/assignments"
  ```
- **영향**: 기능적 영향 없음(오히려 서버 필터로 확장 페이지에서도 정확한 결과를 주는 개선). 다만 카탈로그가 "지원하지 않는다"고 잘못 서술하면 이후 검증자·개발자가 실제로는 존재하는 서버 필터 기능을 인지하지 못하고 중복 구현하거나, 반대로 실제 서버 계약이 바뀐 걸 놓칠 위험(회귀 감지 오탐/누락).
- **수정 방향(제안)**: `docs/test-cases/H-frontend-e2e.md` TC-FE-242 기대결과를 "WORKER 시각도 서버 필터(`/v1/assignments` 의 q/workStatus/eventTypeCd)로 위임하며 클라이언트 재필터는 없다. KPI 4카드는 표시 전용(클릭 필터 없음)"으로 정정. ⚠ 본 세션은 카탈로그 수정 권한 밖(검증 전용) — 실제 정정은 사용자 지시로 별도 진행.

