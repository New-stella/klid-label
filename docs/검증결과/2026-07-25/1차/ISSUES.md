# 이슈 대장 — 1차 검증 (2026-07-25)

> 수정 작업의 입력 문서. 각 이슈는 기대효과·현재이슈·재현경로·영향·수정방향을 포함한다.
> 수정 완료 후 2차 검증에서 이 블록으로 해소 여부를 대조한다.

## A. 인증/권한 + 공통 인프라

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [A-ISSUE-01] TC-AUTH-027 — `@Cacheable` 이 null 가드보다 먼저 평가돼 `resolve(null)` 이 NPE 경로
- **심각도**: LOW
- **기대 동작(기대효과)**: `UserRoleResolver.resolve(null)` 은 어떤 호출 경로에서도 예외 없이 `null`(무권한)을 반환해야 한다. fail-closed 계약이 방어적으로 성립해야 향후 호출자가 추가돼도 안전.
- **현재 동작(이슈 내용)**: 스프링 프록시를 통한 호출에서는 `@Cacheable(key = "#userNo")` 가 먼저 평가돼 캐시 키가 `null` 이 되고, Caffeine 은 null 키를 허용하지 않으므로 NPE 가 발생해 메서드 본문의 null 가드에 도달하지 못한다.
  ```java
  // UserRoleResolver.java:46-50
  @Cacheable(cacheNames = CacheConfig.CACHE_USER_ROLE, key = "#userNo", unless = "#result == null")
  public Role resolve(Long userNo) {
      if (userNo == null) {   // ← 프록시 경유 시 도달 불가
          return null;
  ```
  기존 테스트는 `new UserRoleResolver(repository)`(UserRoleResolverTest.java:29-32)로 **프록시 없이** 호출하므로 이 차이를 잡아내지 못한다.
- **재현/확인 경로**: 스프링 컨텍스트에서 주입받은 `UserRoleResolver` 빈에 `resolve(null)` 호출. 현재 프로덕션 유일 호출자(JwtAuthenticationFilter:82-83)는 `parseUserNo` 로 null 을 선차단하므로 **실경로 미도달**.
- **영향**: 현재 노출 없음. 향후 다른 호출자가 null 을 넘기면 필터 catch 범위(JwtException|IllegalArgumentException) 밖의 NPE → 500. 권한 상승은 아니라 보안 영향은 없음.
- **수정 방향(제안)**: ① `condition = "#userNo != null"` 를 `@Cacheable` 에 추가하거나, ② null 가드를 호출자 계약으로 문서화하고 파라미터를 `@NonNull` 로 명시. 겸하여 프록시 경유 테스트(@SpringBootTest) 1건 추가.

### [A-ISSUE-02] TC-AUTH-019 — role=null(LS 미배정) INTERNAL 사용자가 내부 조회 API 전건 통과
- **심각도**: MEDIUM (확인필요 — 의도된 설계일 가능성 높음)
- **기대 동작(기대효과)**: LS_USER_ROLE 에 역할이 배정되지 않은 내부 사용자는 "무권한" 이어야 하며, 최소한 학습데이터/영상/라벨 등 업무 데이터 조회는 차단되는 것이 fail-closed 원칙에 부합.
- **현재 동작(이슈 내용)**: role 이 null 이어도 채널 authority 는 무조건 부여된다.
  ```java
  // JwtAuthenticationFilter.java:99-103
  List<SimpleGrantedAuthority> authorities = new ArrayList<>();
  if (role != null) { authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name())); }
  authorities.add(new SimpleGrantedAuthority("CHANNEL_" + channel.name()));
  ```
  그리고 SecurityConfig.java:121-122 의 포괄 매처는 `hasAuthority("CHANNEL_INTERNAL")` 만 요구한다. 결과적으로 역할 미배정 사용자도 `GET /v1/videos`, `/v1/labels`, `/v1/frames`, `/v1/reviews` … 조회를 수행할 수 있다. 이 상태는 이미 인지되어 있으며(WriteEndpointAuthorizationGuardTest 클래스 javadoc), **쓰기(POST/PUT/PATCH/DELETE)만** `@PreAuthorize` 아키텍처 테스트로 강제되고 읽기는 강제 대상이 아니다.
- **재현/확인 경로**: 유효 서명·허용 issuer·`sub=<LS_USER_ROLE 미등록 userNo>` 토큰으로 `GET /v1/videos` 호출 → 200.
- **영향**: 관제 SSO 로 토큰을 받을 수 있는 모든 내부 계정(저작도구 미배정 포함)이 라벨링 대상 영상 메타·프레임 목록을 조회 가능. 비식별 영상 스트림/이미지 서빙 경로까지 영향 범위인지 별도 확인 필요.
- **수정 방향(제안)**: 정책 확정 후 ① `/v1/**` 매처를 `hasAnyRole(REVIEWER, WORKER)` + 채널 결합(`allOf`)으로 상향하거나, ② baseline 역할(예: `ROLE_ASSIGNED`) 개념을 도입. 어느 쪽이든 permitAll 목록·포털 경로 예외를 함께 재검토. (본 검증 범위에서는 구현하지 않음)

### [A-ISSUE-03] TC-AUTHZ-010 / TC-AUTHZ-002 — GET `/v1/manage/labels/**` 가 채널 격리 예외
- **심각도**: LOW (확인필요)
- **기대 동작(기대효과)**: "PORTAL 채널 토큰은 내부 `/v1/**` 에 접근 불가" 라는 채널 격리 불변식이 예외 없이 성립.
- **현재 동작(이슈 내용)**:
  ```java
  // SecurityConfig.java:108
  .requestMatchers(HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  ```
  `authenticated()` 는 채널·역할을 모두 보지 않으므로 CHANNEL_PORTAL 토큰과 role=null INTERNAL 토큰 모두 통과한다. 주석(:104-106)에 "라벨 마스터 조회는 WORKER/PORTAL_USER 도 허용"이라 명시되어 **의도된 예외**로 보인다.
- **재현/확인 경로**: PORTAL 채널 토큰으로 `GET /v1/manage/labels` → 200 (기존 테스트 `portalChannelForbiddenOnManage` 는 `/v1/manage/test` 를 쓰므로 이 예외를 못 잡음).
- **영향**: 노출 데이터는 라벨 클래스 마스터(라벨명/색상/형태)로 민감도 낮음. 다만 문서(CLAUDE.md "포털은 데이터마트 영상 선택 + 본인 자산 업로드 전용")와 대조 시 노출 범위 확인 필요.
- **수정 방향(제안)**: 의도라면 테스트 케이스 TC-AUTHZ-010 문구에 예외를 명시하고 포털 전용 라벨 마스터 조회 엔드포인트 분리를 검토. 의도가 아니면 `.access(allOf(...))` 로 채널 결합.

### [A-ISSUE-04] TC-AUTH-021 — StreamSignatureFilter javadoc 의 필터 등록 순서 서술이 실제와 반대
- **심각도**: LOW (문서 드리프트, 동작 정상)
- **기대 동작(기대효과)**: 소스 주석이 실제 필터 체인 순서와 일치해야 후속 변경 시 오판을 막는다.
- **현재 동작(이슈 내용)**: `StreamSignatureFilter.java:24` — "JwtAuthenticationFilter 보다 앞에 등록되며". 실제 등록은 뒤:
  ```java
  // SecurityConfig.java:133-135
  // 영상 스트림 단기 서명 URL 인증 — JWT 필터 뒤에 두어 …
  .addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class);
  ```
- **재현/확인 경로**: 두 파일 대조.
- **영향**: 기능 영향 없음(실제 동작은 SecurityConfig 주석대로 Authorization 헤더 우선). 유지보수 오해 위험만.
- **수정 방향(제안)**: StreamSignatureFilter 클래스 javadoc 문구를 "JwtAuthenticationFilter 뒤에 등록"으로 정정.

### [A-ISSUE-05] TC-AUTHZ-012 — dev 토큰 발급 엔드포인트가 dev/stg 에서 미인증 permitAll (임의 userNo 지정 가능)
- **심각도**: MEDIUM (확인필요 — 운영 노출 범위에 따라 HIGH)
- **기대 동작(기대효과)**: 인증 우회로 임의 권한 토큰을 만들 수 있는 경로는 접근 통제된 환경에서만 노출되어야 한다.
- **현재 동작(이슈 내용)**: `authoring.dev.login.enabled=true` 이면 `/v1/dev/tokens` 가 permitAll 이고(SecurityConfig:85-95), 컨트롤러는 인증 없이 임의 role/channel/userNo 로 서명 토큰을 발급한다.
  ```java
  // DevTokenService.java:51-53
  private static final String DEFAULT_USER_NO_REVIEWER = "1001";   // dev-seed 의 REVIEWER
  ...
  String userNo = resolveUserNo(req.userNo(), role);  // 요청자가 sub 를 임의 지정 가능
  ```
  INTERNAL 채널의 실제 인가 역할은 LS_USER_ROLE 에서 오므로, `sub=1001`(시드 REVIEWER)로 발급하면 **미인증 사용자가 REVIEWER 권한**을 획득한다. 활성 프로파일: local(application-local.yml:80-81), dev(application-dev.yml:24-25), **stg(application-stg.yml:24-25)**. prd 는 미설정 → false.
- **재현/확인 경로**: stg/dev 에 네트워크 도달 가능한 클라이언트에서 `POST /v1/dev/tokens {"role":"REVIEWER","channel":"INTERNAL","userNo":"1001"}` → 201 + 토큰 → 그 토큰으로 `/v1/manage/**` 접근.
- **영향**: stg = 온프렘 개발서버(메모리 `env-deploy-mapping`)라면 사내망 한정이라도 인증 우회 경로. prd 는 영향 없음.
- **수정 방향(제안)**: ① stg 에서 `authoring.dev.login.enabled=false` 로 내리거나, ② 발급 대상을 고정 시드 계정으로 제한 + IP allowlist/사전공유 헤더 추가. 정책 결정 필요(구현은 본 검증 범위 밖).

### [A-ISSUE-06] TC-AUTHZ-021 — HSTS 헤더는 HTTPS 요청에만 부착됨 (기대문구와 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: 테스트 케이스는 "임의 응답에 X-Frame-Options / X-Content-Type-Options / Strict-Transport-Security 3개 헤더 존재"를 단언.
- **현재 동작(이슈 내용)**: SecurityConfig.java:64-66 은 HSTS 를 설정하지만 Spring Security `HstsHeaderWriter` 의 기본 requestMatcher 가 secure 요청 한정이라 평문 HTTP 응답에는 헤더가 없다. 기존 테스트도 이를 전제로 `.secure(true)` 를 명시한다.
  ```java
  // SecurityConfigTest.java:49-51
  mockMvc.perform(get("/health").secure(true))
  ```
- **재현/확인 경로**: `.secure(true)` 없이 MockMvc 요청 → `Strict-Transport-Security` null.
- **영향**: 운영은 TLS 종단(리버스 프록시) 뒤이므로 실제 브라우저 응답에 헤더가 붙는지는 프록시의 `X-Forwarded-Proto` 전달 + `server.forward-headers-strategy` 설정에 의존 → **런타임 확인 필요(BLOCKED 성격)**.
- **수정 방향(제안)**: 테스트 케이스 기대문구를 "HTTPS 요청 시 3종" 으로 정정하고, 별도로 프록시 환경에서 `forward-headers-strategy=framework` 여부를 점검(설정 부재 시 HSTS 미부착 가능).

### [A-ISSUE-07] TC-ROLE-001 — RoleHierarchy 빈이 빈 문자열 계층 + 어디에도 미배선
- **심각도**: LOW (확인필요 — UNCERTAINTIES #6)
- **기대 동작(기대효과)**: 역할 상속 정책이 의도적으로 "없음"이라면 그 사실이 명문화되어야 하고, 빈이 존재한다면 실제 인가 판정에 연결되어야 한다.
- **현재 동작(이슈 내용)**:
  ```java
  // RoleHierarchy.java:11-13
  public RoleHierarchy authoringRoleHierarchy() {
      return RoleHierarchyImpl.fromHierarchy("");
  }
  ```
  계층 문자열이 비어 있어 REVIEWER 가 WORKER 권한을 상속하지 않는다(그래서 SecurityConfig:114 처럼 `hasAnyRole(REVIEWER, WORKER)` 로 매번 나열). 또한 이 빈을 참조하는 코드가 main/test 전체에 0건이라 `authorizeHttpRequests().hasRole()` 판정에도 관여하지 않는다(사실상 no-op).
- **재현/확인 경로**: Grep `RoleHierarchy` → 정의 파일 외 참조 0건. 역할 나열 매처(:114) 존재가 상속 부재의 방증.
- **영향**: 현재 인가 동작에 영향 없음. 향후 계층 문자열만 채우면 "설정했는데 안 먹는" 혼선 위험.
- **수정 방향(제안)**: 정책 확정 후 ① 상속 불필요면 빈 제거 + 나열 방식 문서화, ② 상속 필요면 계층 문자열 정의 + `AuthorityAuthorizationManager`/`MethodSecurityExpressionHandler` 에 명시 배선 및 회귀 테스트 추가.

---


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [A-ISSUE-11] TC-STREAM-004 — 서명 URL 의 userNo 바인딩이 "URL 재사용 차단"을 실제로 제공하지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 코드 주석이 명시한 대로 "타 사용자가 서명 URL 을 그대로 재사용해도 통과하지 못함"이 성립해야 한다. 서명 URL 은 비식별 영상 원본 스트림에 대한 **무인증 접근권**이므로, URL 유출(브라우저 히스토리·Referer·프록시 로그·화면 공유) 시 소유자 외 재생이 차단돼야 개인정보 노출을 막을 수 있다.
- **현재 동작(이슈 내용)**: `userNo` 를 서명 입력에 넣으면서 **동시에 URL 쿼리에도 노출**한다. 검증기는 쿼리의 `u` 를 그대로 서명 입력으로 되먹임하므로, URL 전체를 복사하면 누구든 TTL(기본 60초, 최대 600초) 동안 통과한다. 서버측에 "요청자 == u" 를 대조할 주체가 없다(무인증 경로).
  ```java
  // VideoStreamService.java:134-140
  // CWE-284 — userNo 를 서명 입력에 바인딩하고 URL 쿼리 u={userNo} 에도 포함한다.
  // ... (타 사용자가 URL 을 그대로 재사용해도 통과하지 못함)   ← 성립하지 않음
  String u = userNo == null ? "" : userNo;
  String url = "/api/v1/videos/" + rawSn + "/stream?exp=" + params.exp() + "&u=" + u + "&sig=" + params.sig();
  ```
  ```java
  // StreamSignatureFilter.java:115-116
  String userNo = request.getParameter("u");
  if (signer.verify(rawSn, exp, sig, userNo)) {   // u 를 검증 주체가 아니라 입력으로 사용
  ```
  또한 `StreamUrlSigner.java:30-31` javadoc 은 "URL 에는 포함하지 않으며 … 묶는 데 쓰지 않는다"라고 **정반대로** 기술돼 있어 문서/구현 드리프트도 존재한다.
- **재현/확인 경로**: `GET /v1/videos/{rawSn}/stream-url` 로 URL 발급 → 응답 URL 전체(`exp`,`u`,`sig` 포함)를 Authorization 헤더 없는 다른 클라이언트로 요청 → TTL 내 200/206 재생됨. (`u` 만 바꾸면 401 — 그래서 TC 의 문자적 단언은 통과)
- **영향**: 보안 — CWE-294(Authentication Bypass by Capture-replay), CWE-598(민감정보 쿼리스트링 노출). 실효 보호는 "짧은 TTL"뿐이며 `u` 바인딩은 방어 기여가 사실상 0. 비식별 영상이라 잔여위험은 제한적이나 원본 영상 스트림 정책과 충돌.
- **수정 방향(제안)**: ① 최소 조치 — 잘못된 주석 3곳(`StreamSignatureFilter.java:112-113`, `VideoStreamService.java:134-136`, `StreamUrlSigner.java:30-31`) 을 실제 보증 수준으로 정정하고 TC-STREAM-004 기대결과를 "u 변조 거부"로만 한정. ② 실보호가 필요하면 `u` 를 URL 에서 제거하고 요청 컨텍스트(쿠키/세션/헤더) 로 대조하거나, 서명에 클라이언트 바인딩 요소(단기 HttpOnly 쿠키 nonce) 를 추가 + 1회용 소비(nonce 캐시) 도입.

### [A-ISSUE-12] TC-HMAC-007 — HMAC 콜백의 replay 방지가 시간 윈도우뿐 (윈도우 내 무제한 재전송)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 클래스 javadoc(`HmacWebhookFilter.java:53`)이 "replay 방지"를 통제 항목으로 선언한다. 캡처된 유효 콜백이 재전송되면 거부되어 증강 결과가 중복 반영되지 않아야 한다.
- **현재 동작(이슈 내용)**: 검증은 `|now - ts| <= 300s` 와 서명 일치만 본다. nonce/`X-Request-Id` 중복 소비 캐시가 없어, 유효 요청을 그대로 캡처하면 **최대 5분(설정에 따라 그 이상) 동안 무한 재전송이 모두 통과**한다.
  ```java
  // HmacWebhookFilter.java:209-216
  long diffMs = Math.abs(nowMs - tsMs);
  if (diffMs > windowSeconds * 1000L) { ... 401 ... }
  // 이후 서명 일치 시 그대로 chain.doFilter — 사용된 서명의 재사용 기록 없음
  ```
  덧붙여 `Math.abs` 라 **미래 timestamp(+5분)** 도 허용되어 유효창이 실질 10분이다.
- **재현/확인 경로**: 정상 `POST /v1/aug/callback` 요청 1건을 캡처 → 동일 헤더/본문으로 5분 내 N회 재전송 → 전부 필터 통과.
- **영향**: 보안/데이터정합 — CWE-294. 실피해 여부는 `AugmentRequestService` 의 콜백 멱등성에 전적으로 의존한다(본 파트 범위 밖, **E 클러스터에서 반드시 교차확인 필요**). 멱등하지 않으면 증강 결과 중복 적재·상태 중복 전이.
- **수정 방향(제안)**: ① 서명값(또는 `X-Request-Id`) 을 윈도우 길이만큼 TTL 캐시에 기록해 중복 시 401/409. ② `Math.abs` 대신 미래 허용치를 별도 소폭(예: +30s)으로 축소. ③ 최소한 서비스단 멱등 보증을 문서/테스트로 고정하고 필터 javadoc 의 "replay 방지" 표현을 "재사용 창 제한"으로 정정.

### [A-ISSUE-13] TC-HMAC-019 — 경로 정확일치(raw URI) 기반 필터 적용 vs 디코딩 경로 기반 라우팅 → HMAC 우회 가능성
- **심각도**: HIGH (**런타임 확인 필요** — 정적 분석상 성립, 실증 미완)
- **기대 동작(기대효과)**: `/v1/aug/callback` 으로 라우팅되는 **모든** 요청은 예외 없이 HMAC 검증을 거쳐야 한다. 해당 경로는 `SecurityConfig.java:82-84` 에서 `permitAll` 이므로, HMAC 필터가 유일한 인증 수단이다.
- **현재 동작(이슈 내용)**: 필터 적용 여부를 `HttpServletRequest.getRequestURI()`(퍼센트 디코딩 **전** 원문) 의 **문자열 정확일치**로 판정한다.
  ```java
  // HmacWebhookFilter.java:138-141
  protected boolean shouldNotFilter(HttpServletRequest request) {
      String path = stripContext(request);                       // getRequestURI() 원문
      return !pathToSecret.containsKey(path) && !sizeCapOnlyPaths.contains(path);
  }
  ```
  반면 Spring Security 매처와 Spring MVC 핸들러 매핑은 **디코딩된 경로**를 사용한다. 따라서 `/v1/%61ug/callback`(a→%61) 같은 변형은 `shouldNotFilter=true`(필터 스킵) 이면서 `permitAll` 매칭 + 컨트롤러 라우팅이 성립할 수 있다. StrictHttpFirewall 기본값은 `%2f`/`%2e`/`%25`/`;` 만 차단하므로 `%61` 은 통과한다. 동일 논리로 VLM 경로의 본문 크기 캡(4MB) 도 우회 가능하다.
- **재현/확인 경로**: `curl -X POST 'http://<host>/api/v1/%61ug/callback' -H 'Content-Type: application/json' -d '{...}'` (서명 헤더 없음). 401 이 아니라 컨트롤러 응답이 오면 우회 성립. 대소문자·`//` 중복 슬래시·경로 파라미터(`;`) 변형도 함께 확인.
- **영향**: 보안 — CWE-436(Interpretation Conflict) / CWE-288(대체 경로를 통한 인증 우회) / CWE-863. 성립 시 **외부 증강 결과 콜백을 무인증 주입** 가능(임의 rawSn 상태 전이·라벨 매핑 오염). VLM 경로 우회 시 CWE-770 pre-auth DoS.
- **수정 방향(제안)**: `shouldNotFilter` 를 raw URI 문자열 비교 대신 Spring Security 와 **동일한 매처**(`PathPatternRequestMatcher`/`AntPathRequestMatcher`) 또는 `UrlPathHelper.getPathWithinApplication()` 기반 정규화 경로로 교체. 병행으로 StrictHttpFirewall 커스터마이즈(퍼센트 인코딩 알파벳 차단)와 우회 시도 회귀테스트(인코딩·대소문자·중복슬래시·trailing slash) 추가. 동일 결함 패턴이 `StreamSignatureFilter.java:76`(정규식 + raw URI) 에도 있으나 그쪽은 스킵=거부 방향이라 무해.

### [A-ISSUE-14] TC-HMAC-012 — 웹훅 rate limit 이 JVM-local 이라 2노드 Active-Active 에서 임계가 2배·재기동 시 소실
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: "인증 실패 분당 5회 초과 시 60초 backoff"(CWE-307 방어)가 **시스템 전체 기준**으로 보장돼야 한다. 배포 토폴로지는 주 서버 2노드 Active-Active(CLAUDE.md 확정)다.
- **현재 동작(이슈 내용)**: 카운터가 인스턴스 로컬 `ConcurrentHashMap` 이다.
  ```java
  // HmacWebhookFilter.java:112
  private final Map<String, FailureTracker> failureTrackers = new ConcurrentHashMap<>();
  ```
  LB 라운드로빈이면 실제 허용 시도는 **분당 10회**가 되고, 앱 재기동/배포마다 backoff 상태가 초기화된다. `windowStartMs` 가 최초 실패 시각 고정이라 지속 공격 시 60초마다 새 창이 열린다.
- **재현/확인 경로**: 2노드 환경에서 잘못된 서명으로 10회 연속 호출 → 429 가 6회째가 아니라 11회째부터 발생.
- **영향**: 보안(무차별 대입 완화 효과 약화). HMAC-SHA256 시크릿(≥32B) 자체가 대상이라 실질 크래킹 위험은 낮으므로 MEDIUM.
- **수정 방향(제안)**: 공유 저장소(DB/Redis) 기반 카운터로 승격하거나, LB/게이트웨이 계층에서 웹훅 경로 rate limit 을 강제하고 앱 카운터는 보조로 명시. 최소 조치로 javadoc 에 "노드 로컬" 한계를 명기하고 운영 문서에 반영.

### [A-ISSUE-15] TC-HMAC-012 — clientIp 를 remoteAddr 로만 산출 → 리버스 프록시 뒤에서 rate limit 이 전체 콜백 차단(가용성)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: rate limit 은 **공격 출처만** 격리해야 하며, 정상 벤더 콜백의 가용성을 해쳐서는 안 된다.
- **현재 동작(이슈 내용)**: 프록시 헤더를 전혀 보지 않고 `getRemoteAddr()` 만 쓴다.
  ```java
  // HmacWebhookFilter.java:353-357
  private String clientIp(HttpServletRequest request) {
      String addr = request.getRemoteAddr();
      return addr == null ? "unknown" : addr;
  }
  ```
  LB/Nginx 뒤에 배포되면 모든 요청의 remoteAddr 이 **동일한 프록시 IP** 로 수렴한다. 임의의 실패 5회가 누적되면 **정상 벤더 콜백까지 60초 동안 429** 로 막힌다(자기유발 DoS). 반대로 다중 IP 공격자는 각각 5회씩 소진해 사실상 무제한 시도한다.
- **재현/확인 경로**: 프록시 경유 환경에서 잘못된 서명 5회 → 직후 정상 서명 콜백이 429.
- **영향**: 가용성/데이터 유실(증강 결과 콜백 누락) + 보안 통제 실효 저하. CWE-348(Use of Less Trusted Source) 인접.
- **수정 방향(제안)**: `ForwardedHeaderFilter` 또는 신뢰 프록시 목록 기반 `X-Forwarded-For` 최우측 신뢰 홉 파싱을 도입하되, 신뢰 프록시 밖의 헤더는 무시(위조 방지). 프록시 IP 는 rate limit 키에서 제외하거나 화이트리스트 처리. 벤더 IP allowlist 병행 시 본 이슈 대부분 해소.

### [A-ISSUE-16] TC-HMAC-001/008 — 서명 대상이 raw 바이트가 아닌 UTF-8 디코딩 문자열
- **심각도**: LOW
- **기대 동작(기대효과)**: 송신측이 서명한 바이트열과 수신측이 검증하는 바이트열이 항상 동일해야 한다.
- **현재 동작(이슈 내용)**: 본문을 문자열로 디코딩했다가 `HmacSigner` 내부에서 다시 인코딩한다.
  ```java
  // HmacWebhookFilter.java:230
  String canonical = timestamp + "." + new String(body, StandardCharsets.UTF_8);
  // HmacSigner.java:44
  byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
  ```
  본문이 유효 UTF-8 이 아니면 U+FFFD 치환으로 라운드트립이 깨져 정상 서명도 401 이 된다(거부 방향이라 안전하나 원인 진단이 어렵다).
- **재현/확인 경로**: 비-UTF-8 바이트(예: latin-1 인코딩 한글)를 포함한 본문에 올바른 HMAC 을 붙여 전송 → 401.
- **영향**: 기능(외부 벤더 인코딩 상이 시 원인불명 401). 보안 영향 없음(fail-closed).
- **수정 방향(제안)**: HMAC 입력을 `byte[]` 로 다루도록 `HmacSigner.hex(String secret, byte[] message)` 오버로드 추가 후 `timestamp.getBytes(UTF_8) + '.' + body` 로 계산. 또는 "본문은 UTF-8 JSON" 을 규격 문서·연동규격서에 명시.

### [A-ISSUE-17] TC-CLAIM-001 — 공유 정적 패스워드만으로 임의 인증 사용자가 REVIEWER(최고권한) 자가부여
- **심각도**: HIGH (설계 정책 확인 필요 — 코드 결함이 아니라 **의도된 기능**이면 UNCERTAINTIES 로 승격 권고)
- **기대 동작(기대효과)**: 본 시스템에서 `REVIEWER` 는 사용자 관리·시스템 설정·작업 배정·검수 승인을 모두 보유한 **사실상 관리자**다(CLAUDE.md 역할표, ADMIN 통합). 최고권한 부여는 기존 관리자의 승인·감사 추적을 거쳐야 한다.
- **현재 동작(이슈 내용)**: 인증만 된(role 없는) INTERNAL 사용자가 **공유 정적 패스워드 1개**로 스스로 `REVIEWER` 를 부여받는다. 요청 role 은 사용자가 지정한다.
  ```java
  // RoleClaimService.java:112-121 — 거부는 PORTAL_USER / 이미 권한 보유 / 비-INTERNAL 뿐
  if (req.role() == Role.PORTAL_USER) { ... }
  if (actor.channel() != Channel.INTERNAL || actor.role() != null) { ... }
  // :156 — 사용자가 고른 역할을 그대로 부여
  lsUserRoleRepository.upsertRole(userNo, req.role().name());
  ```
  승인 워크플로·기존 REVIEWER 통지·부여 감사 테이블이 없고(로그 `[RoleClaim] granted` 1줄, :176), 패스워드 회전 정책·만료도 없다. `allowedClaimRoles()`(:239-241) 화이트리스트는 정의만 되고 `claim()` 에서 참조되지 않는다(현재는 enum 3값 구조상 결과가 같지만, Role enum 확장 시 즉시 취약해지는 잠재 결함).
- **재현/확인 경로**: 관제 JWT 로 인증된 임의 사용자(LS_USER_ROLE 미등록) → `POST /v1/auth/role-claim {"role":"REVIEWER","adminPassword":"<공유pw>"}` → 200 + REVIEWER 토큰. 공유 패스워드는 조직 내 전파·평문 공유 특성상 유출 표면이 넓다.
- **영향**: 보안 — CWE-269(Improper Privilege Management), CWE-1392(Use of Default/Shared Credentials), CWE-778(불충분한 감사 로깅). 성공 시 사용자 관리·시스템 설정·검수 승인 전권 획득.
- **수정 방향(제안)**: ① `claim()` 이 `allowedClaimRoles()` 를 실제로 참조하도록 연결(enum 확장 회귀 차단). ② `REVIEWER` 자가부여는 제거하고 WORKER 만 허용 + REVIEWER 는 기존 REVIEWER 의 `/manage` 승인 경로로만 부여. ③ 유지해야 한다면 부여 이력 전용 테이블(요청자·역할·시각·IP·요청ID) 적재 + 기존 REVIEWER 통지 + 패스워드 회전·만료 정책을 운영 문서에 고정.

### [A-ISSUE-18] TC-CLAIM-008 — 자가부여 rate limit 이 계정 단위 in-memory (교차계정·다중노드 우회) + attempts 맵 무한 증가
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 관리자 공유 패스워드에 대한 무차별 대입이 시스템 전체 기준으로 억제돼야 한다(javadoc 이 CWE-307 방어를 선언).
- **현재 동작(이슈 내용)**: 키가 호출자 `sub` 이고 저장소가 JVM 로컬이며, 성공 시도까지 카운트되고 **엔트리가 제거되지 않는다**.
  ```java
  // RoleClaimService.java:73
  private final ConcurrentHashMap<String, AttemptCounter> attempts = new ConcurrentHashMap<>();
  // :207-213 — 윈도우 만료 시 값만 교체, key 는 영구 잔존
  AttemptCounter counter = attempts.compute(key, (k, prev) -> { ... });
  int current = counter.count.incrementAndGet();
  ```
  → 실효 시도량 = (보유 계정 수) × 5회/분 × (노드 수). IP 단위 제한이 없어 동일 공격자가 여러 무권한 계정을 쓰면 선형 증폭된다. `attempts` 는 role-claim 을 호출한 모든 sub 를 영구 보관한다(유한하나 회수 없음).
- **재현/확인 경로**: 무권한 계정 A/B/C 토큰으로 번갈아 5회씩 호출 → 각 계정이 독립 429 창을 가짐(테스트 `rateLimitPerCaller` 가 이 동작을 고정). 2노드 환경이면 계정당 10회/분.
- **영향**: 보안 — CWE-307 부분 방어, CWE-770(비회수 맵). BCrypt cost 12 의 계산 비용이 실질 완화 요인이라 MEDIUM.
- **수정 방향(제안)**: 키를 `sub` + clientIp 조합으로 확장하고 **글로벌 실패 카운터**(전체 실패 N회/분 초과 시 엔드포인트 일시 차단) 를 추가. 윈도우 만료 엔트리 회수(사이즈 cap + evict, HmacWebhookFilter:383-397 패턴 재사용). 다중노드 대응은 A-ISSUE-14 와 동일 해법.

### [A-ISSUE-19] A-3/A-4/A-5 공통 — application-local.yml 에 관리자 BCrypt 해시·스트림 서명키·웹훅 시크릿 기본값 하드코딩
- **심각도**: LOW
- **기대 동작(기대효과)**: 자격증명·서명키는 어떤 프로파일에서도 저장소에 커밋되지 않아야 한다(security.md "Password in Configuration File" 금지).
- **현재 동작(이슈 내용)**: `application-local.yml:41` `admin-claim-password-hash: ${ADMIN_CLAIM_PASSWORD_HASH:$2y$12$NxE8...}`, `:47` `sign-secret: ${STREAM_SIGN_SECRET:local-stream-sign-secret-change-me-32bytes-min}`, `:113` `augment: ${WEBHOOK_HMAC_SECRET_AUGMENT:local-augment-callback-hmac-change-me-32bytes-min}` — 모두 환경변수 미설정 시 커밋된 기본값으로 동작.
- **재현/확인 경로**: 저장소 열람만으로 로컬 환경의 관리자 해시·서명키 확보. `application.yml` 의 운영 기본값은 빈 문자열이라 운영 노출은 없음.
- **영향**: 보안 — CWE-798. `LocalProfileGuard`(ENV=prd 시 부팅 차단)로 운영 유입은 막혀 있어 실질 영향은 로컬/개발 한정.
- **수정 방향(제안)**: 기본값을 제거하고 `.env.example` + 로컬 실행 문서로 이관하거나, 최소한 세 값의 성격(공개된 개발용 더미, 재사용 금지)을 주석으로 명시.

### [A-ISSUE-20] TC-CLAIM-013 / TC-HMAC-015 — 로그 sanitize 가 `\r\n\t` 만 치환 (기타 제어문자·ANSI 이스케이프 잔존)
- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자 제어 값이 로그에 들어갈 때 로그 라인 위조·터미널 제어 시퀀스 주입이 불가능해야 한다(CWE-117).
- **현재 동작(이슈 내용)**: 두 sanitize 모두 화이트리스트가 아닌 3문자 블랙리스트다.
  ```java
  // RoleClaimService.java:227-228
  String trimmed = value.length() > 32 ? value.substring(0, 32) : value;
  return trimmed.replaceAll("[\\r\\n\\t]", "_");
  // HmacWebhookFilter.java:101,350
  private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");
  ```
  ``(ESC), ``, `` 등은 통과한다. 참고로 `RequestIdFilter`(A-6, TC-TRACE-003)는 영숫자·하이픈 화이트리스트를 쓰고 있어 프로젝트 내 정책이 일관되지 않다.
- **재현/확인 경로**: sub 또는 요청 경로에 ANSI 이스케이프를 포함시켜 실패 유도 후 로그 파일/터미널 뷰어에서 렌더링 확인.
- **영향**: 보안 — CWE-117 잔여 표면. 두 값 모두 사실상 서명된 JWT sub / URI 라 실현 난이도가 높아 LOW.
- **수정 방향(제안)**: `RequestIdFilter` 와 동일한 화이트리스트(`[^A-Za-z0-9._/-]` → `_`) 방식으로 통일하고, 공통 `LogSanitizer` 유틸로 추출해 3곳이 같은 규칙을 쓰게 한다.

---


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [A-ISSUE-21] TC-PROF-001~003 — LocalProfileGuard 자동 회귀 테스트 전무
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ENV=dev/stg/prd` 환경에서 `spring.profiles.active=local`로 잘못 배포되면 부팅을 즉시 거부해(CWE-798 fallback 비밀번호 노출 차단) 운영 환경에 개발용 기본값이 노출되는 사고를 막아야 한다. 이 가드는 배포 안전의 마지막 방어선이므로 회귀 시 조기 발견이 중요하다.
- **현재 동작(이슈 내용)**: 코드(`LocalProfileGuard.java:29-43`)는 올바르게 구현되어 있으나, `find src/test -iname "*LocalProfileGuard*"` 결과 0건 — 전용 테스트 파일이 아예 없다. `build.gradle`에도 `System.getenv` 모킹용 라이브러리(system-stubs, junit-pioneer 등)가 없어 표준적인 JUnit 방식으로는 이 클래스를 테스트하기 어렵다.
  ```java
  @PostConstruct
  void verify() {
      if (!env.acceptsProfiles(Profiles.of("local"))) { return; }
      String envName = System.getenv("ENV");
      if (envName == null || envName.isBlank() || "local".equalsIgnoreCase(envName)) { return; }
      String lower = envName.toLowerCase();
      if (lower.equals("dev") || lower.equals("stg") || lower.equals("prd")) {
          throw new IllegalStateException(...);
      }
  }
  ```
- **재현/확인 경로**: 향후 리팩터링(예: 조건문 부호 실수, `equalsIgnoreCase` 제거 등)이 발생해도 CI가 이를 잡아내지 못하고 그대로 머지될 수 있다.
- **영향**: 보안 — CWE-798(하드코딩된 자격증명) 방어 가드의 무결성이 코드 리뷰에만 의존. 실제 결함은 아직 없음(정적 검토 결과 PASS)이나 회귀 방지망이 없다.
- **수정 방향(제안)**: `system-stubs-jupiter` 또는 리플렉션 기반 환경변수 오버라이드로 `LocalProfileGuardTest` 신설 — ENV=prd+active=local→예외, ENV=null+active=local→통과, active=dev→즉시 통과 3케이스 최소 커버.

### [A-ISSUE-22] TC-CACHE-002 — sysconfig 캐시 "적중" 자체를 증명하는 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: `SystemConfigService.getInt/getString`가 60초 TTL 캐시로 응답해 반복 호출 시 두 번째 이후 호출은 DB(Control DS)를 재조회하지 않아야 한다(핫 패스 가속 목적).
- **현재 동작(이슈 내용)**: `@Cacheable(cacheNames = "sysconfig", key = "'int:' + #key")` 어노테이션은 존재하나(`SystemConfigService.java:47-48`), 이를 검증하는 테스트는 `SystemConfigControllerTest#reviewerUpdatesAndCacheInvalidates` 하나뿐이며 이 테스트조차 "무효화 후 새 값 반영"만 확인할 뿐, "무효화 전 반복 호출이 DB를 재조회하지 않는지"는 검증하지 않는다.
- **재현/확인 경로**: repository를 스파이/목으로 감싸 `getInt` 2회 연속 호출 후 `verify(repository, times(1)).findByConfigKey(...)`으로 확인하는 테스트가 없어, 프록시 미적용(self-invocation)이나 캐시 키 설계 오류가 발생해도 테스트가 잡아내지 못한다.
- **영향**: 기능 성능 특성(캐시 가속) 회귀를 조기에 잡지 못함. 보안/데이터 정합성 영향은 없음.
- **수정 방향(제안)**: `@SpyBean LsSystemConfigRepository`로 감싸 `getInt` 연속 2회 호출 후 `verify(times(1))`로 캐시 히트를 직접 증명하는 단위/통합 테스트 추가.

### [A-ISSUE-23] TC-SYSCFG-003/005/006/007/008/009 — SystemConfigService 방어 분기 다수가 자동 테스트 커버리지 밖
- **심각도**: LOW
- **기대 동작(기대효과)**: actor null 거부, DECIMAL NaN/Infinity 거부, BOOLEAN true/false 외 거부, 미지원 CONFIG_TYPE 거부, getInt 타입/파싱 불일치 예외, 없는 키 NOT_FOUND — 이 6개 방어 분기 모두 회귀 시 CI가 잡아야 한다.
- **현재 동작(이슈 내용)**: 코드 검토 결과 6개 분기 모두 논리적으로 올바르다(예: `Double.isNaN(v) || Double.isInfinite(v)` 명시 체크 — `Double.parseDouble("NaN")`은 예외를 던지지 않고 NaN을 반환하므로 이 체크가 실제 방어선인데, `PolygonSimplifyConfigTest`는 범위초과·비숫자 문자열만 테스트하고 `"NaN"`/`"Infinity"` 리터럴 입력 케이스는 없다). actor==null 분기(`SystemConfigService.java:166-169`)도 `WORKER` 역할 거부 테스트만 있고 `actor=null` 자체를 넣는 테스트는 없다.
- **재현/확인 경로**: 각 분기에 대한 파라미터화 테스트 부재 상태에서 조건식이 변경돼도 실패하는 테스트가 없다.
- **영향**: 기능/보안 방어 분기의 회귀 방지망 공백. 현재 코드는 PASS.
- **수정 방향(제안)**: `SystemConfigServiceTest`(단위, Mock repository)를 신설해 6개 분기를 `@ParameterizedTest`로 묶어 커버.

### [A-ISSUE-24] MaskingPatternLayout 이 local 프로파일 로그 파이프라인에 실제로는 연결되어 있지 않음 (UNCERTAINTIES #7 확정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md는 "로그에 개인정보·토큰 출력 금지 (Logback MaskingPatternLayout)"이라 명시하며, 모든 환경에서 password/token/secret/Authorization 값이 마스킹되어야 한다.
- **현재 동작(이슈 내용)**: `MaskingPatternLayout` 클래스는 실존하며(`backend/src/main/java/kr/co/cudo/authoring/common/logging/MaskingPatternLayout.java`) `logback-spring.xml`에 `<conversionRule conversionWord="mask" converterClass="...MaskingPatternLayout"/>`로 등록돼 있지만, **local 프로파일 appender의 실제 `<pattern>`은 `%mask`가 아니라 `%msg`를 사용**한다.
  ```xml
  <springProfile name="local">
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
          <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
              <pattern>%d{...} [%thread] %-5level [%X{traceId:-}] %logger{36} - %msg%n</pattern>
          </encoder>
      </appender>
  ```
  즉 `MaskingPatternLayout`은 어떤 활성 패턴에서도 참조되지 않는 사실상 죽은 코드다. `MaskingPatternLayoutTest`는 `layout.mask(...)`를 직접 호출해 마스킹 로직 자체는 검증하지만, 이 레이아웃이 실제 로그 출력 경로에 연결됐는지는 검증하지 않아 "테스트는 통과하는데 실제로는 미적용"이라는 확증편향 함정이 존재한다. 반면 dev/stg/prd 프로파일은 `LoggingEventCompositeJsonEncoder`의 `valueMasker`로 별도 클래스 `MaskingJsonValueMasker`를 사용해 실제로 마스킹이 적용된다(이쪽은 정상).
- **재현/확인 경로**: local 프로파일로 기동 후 `password=1234`, `Authorization: Bearer xxx` 등을 포함한 로그를 남기면 콘솔에 평문 그대로 출력됨(코드 확인, 런타임 미실행 확인은 BLOCKED).
- **영향**: 보안 — CWE-532(로그 파일에 민감정보 삽입). local 프로파일은 `LocalProfileGuard`가 ENV=dev/stg/prd 배포를 차단하므로 실제 운영 노출 경로는 아니나, 로컬 콘솔 로그가 CI 아티팩트·화면 공유·로그 수집기로 유출될 경우 토큰/비밀번호가 평문 노출될 수 있다. 또한 CLAUDE.md의 서술(마스킹이 전 환경 적용된다는 전제)과 실제 구현이 불일치한다.
- **수정 방향(제안)**: (a) local 패턴도 `%mask` conversion word를 사용하도록 변경하거나, (b) `MaskingPatternLayout`을 `<layout class="...MaskingPatternLayout">` 형태의 실제 Layout으로 인코더에 연결하거나, (c) 의도적으로 local은 마스킹 생략이 맞다면 죽은 `conversionRule`/클래스를 정리하고 CLAUDE.md 서술을 "dev/stg/prd만 마스킹"으로 정정. 어느 쪽이든 현재는 "만들어놓고 안 쓰는" 상태라 문서-코드 불일치 해소가 필요.



## 환경 (외부연동 배선) — stack-bringup 실측

## ★ 자체 채움(self-fill) 의심 경로 — 발견 시 결함 기록

### [ENV-ISSUE-01] 증강(SFR-07) 외부 연동이 mock-server를 전혀 호출하지 않고 내부 시뮬레이터가 자체 완료 처리
- **심각도**: HIGH
- **기대 동작**: 로컬에서 증강 요청(`POST /v1/augments/request`)이 발생하면 외부 증강 시스템(로컬에서는 mock-server `POST /v1/augment`)에 실제로 위탁되고, mock-server가 만든 결과를 콜백으로 수신해 반영해야 한다(비식별·VLM과 동일 원칙).
- **현재 동작**:
  - `backend/src/main/resources/application-local.yml:104-105` — `authoring.augment.external.mode` 기본값이 `dev`.
  - `backend/src/main/java/kr/co/cudo/authoring/augment/integration/ExternalAugmentClient.java` 인터페이스의 구현체는 단 2개뿐:
    - `NoopExternalAugmentClient`(`mode=noop`, 운영 기본) — 로그만 남기고 즉시 `ack=true` 반환, 외부 호출 0건.
    - `DevAugmentCallbackSimulator`(`mode=dev`, local 기본 활성) — 외부 시스템을 흉내내어 **본인이 직접** `SIMULATED_STATUS = "SUCCESS"`를 만들고, HMAC 서명까지 자체 발급해 자기 자신의 콜백 엔드포인트(`/api/v1/aug/callback`)로 되쏘는 구조. mock-server의 `POST /v1/augment`를 호출하는 코드는 어디에도 없음(grep 결과 `ExternalAugmentClient` 구현체가 이 2개뿐임을 확인).
  - 런타임 증거: mock-server가 3일간 기동 중이며 openapi에 `/v1/augment`, `/v1/augment/status`가 정상 노출됨에도, `docker logs klid-mock-server`에 해당 경로 요청이 **1건도 없음**(반면 KPST `/project`·`/retrieve_progress`는 다건 기록).
- **영향**: "로컬은 무조건 목업서버를 본다"는 원칙이 증강 도메인 전체에서 위반됨. mock-server의 증강 스텁이 실제로는 한 번도 검증되지 않은 채 방치되고, 배포 전 실 외부 시스템 계약(요청 바디 스키마·콜백 서명 등)과의 정합성이 로컬 테스트로는 전혀 검증되지 않는다. 이후 예정된 "배치 파이프라인 정상 시나리오" 테스트케이스가 증강 단계를 포함한다면, 그 결과는 실제 연동이 아니라 자체 시뮬레이션 결과를 검증하는 셈이 된다.
- **수정 방향(제안, 이번 작업에서는 미적용)**: mock-server의 `POST /v1/augment`를 실제로 호출하고 결과를 콜백으로 수신하는 3번째 `ExternalAugmentClient` 구현체(예: `mode=live`)를 추가하고, `docker-compose.local.yml`에서 `AUGMENT_EXTERNAL_MODE=live`(가칭) + mock-server base-url을 명시적으로 오버라이드하도록 구성. 현재 구조상 `DevAugmentCallbackSimulator`는 "콜백 수신측 검증"(HMAC 서명·idempotency)만을 위한 것으로 보이며, mock-server 위탁 자체를 대체하지 못한다.

### [ENV-ISSUE-02] VLM 외부 연동이 로컬에서 통째로 비활성화되어 mock-server에 단 한 번도 도달하지 않음
- **심각도**: MEDIUM (자체 값 "생성"은 아니고 "미실행+스킵"이라 ENV-ISSUE-01보다 낮게 분류하되, 원칙1 위반은 동일)
- **기대 동작**: 로컬에서 VLM 위탁(`POST /v1/videovlm/describe`)이 mock-server로 실제 호출되어야 한다.
- **현재 동작**: `VLM_CLIENT_ENABLED=false`(compose 어디에도 override 없음, `application.yml:381` 기본값 그대로 유지) → `VlmClient.submitTimeseries()`가 `if (!enabled)` 분기로 외부 호출 없이 즉시 `VlmTimeseriesResponse.skipped(requestId)` 반환(`backend/.../common/client/VlmClient.java:92-95`). `VlmTimeseriesStep.doSubmit()`도 `vlmClient.isEnabled()`가 false면 ledger 등록조차 하지 않고 조기 반환(`backend/.../batch/step/VlmTimeseriesStep.java` doSubmit 상단). 값을 스스로 "채우지"는 않지만(결과 자체가 없음), 파이프라인 관점에서는 VLM 단계가 통째로 없는 것처럼 동작.
- **영향**: mock-server의 VLM 3EP(`verify`/`describe`/`status`)가 로컬 검증 대상에서 완전히 빠짐. VLM 콜백 수신(`POST /v1/vlm/callback`) 경로도 로컬에서 트리거될 일이 없어 미검증.
- **수정 방향(제안, 이번 작업에서는 미적용)**: `docker-compose.local.yml`에 `VLM_CLIENT_ENABLED=true` + `VLM_SERVICE_URL=http://klid-mock-server:9400` override 추가(현재 파일에는 비식별만 override되어 있고 VLM은 헤더 주석에 "VLM 은 local 에서 enabled=false"로 의도적으로 명시돼 있음 — 즉 현재는 "알려진 설계"이나, 이번 작업의 절대원칙 기준으로는 결함으로 재분류해 기록).


### 환경 이슈 처리 결정 (사용자 확정 2026-07-25)

| 이슈 | 결정 | 근거 |
|------|------|------|
| ENV-ISSUE-01 (증강 self-fill) | **결함 확정 — 수정 대상** | 외부 호출 구현체 자체가 부재(`ExternalAugmentClient` 인터페이스 + `NoopExternalAugmentClient` 로그만). javadoc 에 "실제 콜백 호출 구현은 Phase 2 에서 추가 예정" 명시 = 미구현. 설정으로 해결 불가. **E 클러스터 증강 외부연동 케이스는 FAIL 판정**하고, 실구현은 검증 완료 후 별도 개발로 진행 |
| ENV-ISSUE-02 (VLM 비활성) | **검증 착수 전 환경 구성으로 활성화** | 플래그 설정 문제이며 검증 대상 코드 수정이 아님. 활성화 후 mock-server VLM 3EP 경유를 실증하고 B 클러스터 VLM 케이스를 실동작으로 판정. 결과는 `_raw/vlm-wiring.md` |

> ⚠ 판정 기준: 로컬에서 외부 연동 값을 **외부 응답 없이 자체 생성**하는 경로는 전부 결함(self-fill). 최종 `SUMMARY.md` 에 self-fill 전용 섹션으로 집계한다.

### ENV-ISSUE-02 후속 — VLM 목업 배선 시도 결과 (설정만으로 불가 확정)

## 발견된 결함
### [결함] VlmClient 전용 WebClient가 HTTPS-only + private-IP 차단을 강제해 로컬 mock-server 배선이 원천 불가능
- **심각도**: HIGH (로컬 목업 전수검증 구속조건 "로컬은 무조건 목업서버를 바라본다"를 VLM 경로만 위반 — 코드 수정 없이는 준수 불가능한 구조적 결함)
- **기대 동작**: KPST 비식별 클라이언트(`KpstWebClientConfig`)처럼 `enabled=true`일 때 http/https 스킴을
  모두 허용하고, http면 WARN 로그만 남기고 통과시켜(내부망/로컬 mock 전제) mock-server(`klid-mock-server:9400`,
  평문 HTTP, TLS 미지원)로 정상 연결되어야 한다.
- **현재 동작**: `WebClientConfig.vlmWebClient`(`backend/src/main/java/kr/co/cudo/authoring/common/config/WebClientConfig.java:63-76`)가
  `enabled=true`일 때 `validateExternalUrl()`(같은 파일 116-162)을 호출하며, 이 메서드가:
  1. **HTTPS 스킴만 허용** (WebClientConfig.java:129-132) — `http` 이면 즉시 `IllegalStateException`으로
     빈 생성 실패 → Spring 컨텍스트 부트 자체가 실패(크래시 루프).
  2. 설령 https로 우회해도 host가 loopback/site-local/link-local(사설 IP, 예: docker 브리지 172.16.0.0/12
     대역)이면 SSRF 차단으로 동일하게 부트 실패(WebClientConfig.java:150-155).
  - 반면 mock-server(`mock-server/Dockerfile:28`)는 `uvicorn app.main:app --host 0.0.0.0 --port 9400`으로
    **TLS 미지원**(plain HTTP 전용)이고, docker 브리지 네트워크상 `klid-mock-server` 호스트명은
    `172.18.0.4`(site-local)로 resolve됨(`docker exec klid-mock-server hostname -i` 실측) — 두 조건 모두
    구조적으로 통과 불가능.
- **재현 경로**: `.env`에 `VLM_CLIENT_ENABLED=true` + `VLM_SERVICE_URL=http://klid-mock-server:9400` 설정 →
  `docker compose ... up -d klid-backend` → 컨테이너가 `Restarting`으로 크래시 루프, 로그에 위 스택트레이스 반복.
- **영향**: VLM 관련 테스트케이스(describe 위탁 → mock 콜백 수신 전 구간)가 **로컬 목업 기준 실동작 검증 자체가
  불가능**. KPST(비식별)는 동일 목적을 위해 http+사설IP를 명시적으로 허용하는 `KpstWebClientConfig`가 있어
  정상 경유하는 것과 대비된다(두 클라이언트의 보안 정책이 불일치 — VLM만 dev/stg/prd 전용 강도의 검증을
  local에도 무조건 적용).
- **수정 방향(제안, 미실행)**: `WebClientConfig.vlmWebClient`의 SSRF/HTTPS 검증을 `KpstWebClientConfig`와
  동일하게 스킴 기반 분기(http 허용 + WARN, https는 기존 검증 유지)로 정정하거나, **local 프로파일에서만**
  검증을 완화하는 조건(`@Profile`/`Environment` 분기)을 추가한다. 이 결정은 코드 변경이 필요하므로 본 임무
  범위(설정만 변경) 밖 — 사용자/담당 개발 확인 필요.


**처리 결정 (사용자 확정 2026-07-25)**: **결함 확정 — 수정은 1차 검증 완료 후.**
설정만으로는 로컬 목업 배선이 구조적으로 불가능함이 실증됐고(부팅 크래시 후 원복), 코드 수정은 검증 중 기준선 붕괴를 막기 위해 보류한다.
→ **B-7(VLM Step·콜백) 케이스는 "외부연동 미경유" 기준으로 판정**하고, 1차 완료 후 `WebClientConfig` 의 VLM URL 검증을 `KpstWebClientConfig` 와 동일 정책으로 정정한 뒤 **2차에서 VLM 실검증**한다.

## B. 배치 파이프라인 / 비식별화

## 이슈 상세

### [B-ISSUE-01] TC-DEID-016 — KPST 위탁 경로에 원본 존재 검증이 없고 산출물 무결성이 "존재+>0바이트"뿐 → 원본이 없는 영상이 18바이트 텍스트 스텁으로 "비식별 완료(Y)" 승인

- **심각도**: HIGH
- **기대 동작(기대효과)**: mock 경로가 명시적으로 보장하는 원칙(`DeidentifyStep` 클래스 주석 "정합성(HIGH-2): 원본 부재 시 'Y' 위장 금지 — 'F' 마킹 + 실패(MARKING_READY 미전이)")이 **운영 실경로인 KPST 위탁에도 동일하게** 적용되어야 한다. 비식별 완료('Y' + MARKING_READY)는 "원본이 실제로 존재하고, 그 원본을 비식별한 유효한 영상 산출물이 회수됐다"를 뜻해야 한다. 이 신호를 근거로 마킹 화면이 비식별본을 서빙하고 파이프라인이 진행되므로, 여기서 거짓 'Y'가 나면 후속 전 단계가 오염된다.
- **현재 동작(이슈 내용)**:
  1. `KpstDeidentService.submit`(`backend/src/main/java/kr/co/cudo/authoring/batch/service/KpstDeidentService.java:161-218`)은 `rawFilePathNm` 의 **blank 여부와 부모 디렉터리 유무만** 검사하고 파일 실재는 확인하지 않는다.
     ```java
     if (rawFilePathNm == null || rawFilePathNm.isBlank()) { throw ... }   // :174
     Path fullPath = Paths.get(rawFilePathNm);
     Path parent = fullPath.getParent();                                    // :177-178
     // ← Files.isRegularFile(fullPath) 검사 없음 (mock 경로 :270 에는 존재)
     ```
  2. 완료 판정 시 산출물 검증은 `isUsableDeidFile`(`:391-402`)의 **"정규 파일이고 size>0"** 이 전부다.
     ```java
     return Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0;   // :398
     ```
  - **실동작 증거(라이브)**: 원본 `./storage/raw/seed/clip-9101.mp4` 는 컨테이너에 **존재하지 않는다**(`docker exec klid-backend ls /app/storage/raw/seed` → `No such file or directory`). 그럼에도
    - `POST /project` 위탁 성공(prjId=3), 폴링 1회 만에 `[KpstDeid] poll completed rawSn=23`,
    - DB: `ls_data_raw(23).de_ident_yn='Y'`, `data_stts_cd='MARKING_READY'`, `ls_deident_proc_log(23).proc_stts_cd='SUCCEEDED'`, `poll_stts_cd='DOWNLOADED'`, `de_idntf_file_path_nm=/app/storage/deidentified/videos/23/clip-9101_202607250139_mask.mp4`,
    - 그 "비식별 영상"의 실체는 **18바이트 텍스트 파일**: `cat` 결과 `MOCK_DEIDENTIFIED`.
  - 원본 부재는 **두 단계 뒤에야** 드러난다: `ls_batch_proc_log(13, FRAME_EXTRACT, FAILED, err_msg_cn='원본 영상을 찾을 수 없습니다: rawSn=23')`. 즉 비식별 단계는 실패를 잡지 못하고 성공 신호를 흘려보냈다.
- **재현/확인 경로**:
  ```bash
  # 1) 원본 부재 확인
  docker exec klid-backend sh -c 'ls -la /app/storage/raw/seed/ ; ls -la /app/storage/deidentified/videos/23'
  docker exec klid-backend sh -c 'cat /app/storage/deidentified/videos/23/*_mask.mp4'   # → MOCK_DEIDENTIFIED (18B)
  # 2) 그럼에도 비식별 완료로 전이됐음
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, data_stts_cd, de_ident_yn FROM public.ls_data_raw WHERE raw_sn=23;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT proc_stts_cd, poll_stts_cd, de_idntf_file_path_nm, orgnl_file_path_nm FROM public.ls_deident_proc_log WHERE data_raw_sn=23;"
  # 3) 재현(신규): mng_clip_master 에 존재하지 않는 file_path 로 JOB_DMND_YN='Y' 클립 추가 후
  curl -X POST 'http://localhost:18081/api/v1/dev/batch/scan' -H "Authorization: Bearer <REVIEWER dev token>"
  ```
- **영향**: 데이터 정합 + 개인정보 보호. ① 비식별 산출물이 유효 영상인지 검증하지 않으므로 KPST가 `procState=2`를 주면서 잘린/빈 껍데기 파일을 남긴 실패 모드에서 `DE_IDNTF_YN='Y'`가 커밋되고 마킹 단계가 열린다(스트리밍 `GET /v1/videos/{rawSn}/stream` 은 "비식별 완료" 신호를 신뢰). ② 원본 부재를 비식별 단계가 잡지 못해 실패 위치가 FRAME_EXTRACT 로 밀려 원인 추적이 어려워지고, 배치 재시도(3회)가 무의미하게 소모된다. CWE-345(불충분한 데이터 진정성 검증) / CWE-754(비정상 조건 부적절 검사).
- **수정 방향(제안)**: (구현 금지)
  1. `submit()` 앞단에 mock 경로와 동일한 원본 실재 가드 추가 — `Files.isRegularFile(fullPath)` 실패 시 `BatchTransitionService.recordDeidentFailure(rawSn, ...)`(별도 REQUIRES_NEW)로 'F' 커밋 후 거부. 공유 마운트가 보이지 않는 배포에서는 프로퍼티로 가드를 끌 수 있게 하되 기본은 켬(fail-closed).
  2. `isUsableDeidFile` 에 최소 무결성 기준 추가 — 최소 바이트 임계값 + 컨테이너 시그니처(ftyp box) 또는 ffprobe 스트림 확인. 최소한 "원본 대비 터무니없이 작은 산출물" 거부.
  3. 두 검증 모두 `TC-DEID-018`(mock)과 대칭인 KPST 케이스로 테스트 추가.

### [B-ISSUE-02] TC-BATCH-016 — Quartz 클러스터링이 기본 비활성 + 온프렘 배포 템플릿도 false → 2노드 Active-Active 에서 `@DisallowConcurrentExecution` 이 중복 발화를 막지 못함 (UNCERTAINTY #8 확정)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md "배치 성능"이 명시한 배포 토폴로지는 **주 서버 2노드 Active-Active + Quartz 클러스터링 적용(QRTZ_LOCKS 행 락으로 잡 중복 방지)** 이다. 관제 학습용 스캔(60초)·KPST 폴링(30초)·export sweep(600초) 트리거가 노드당 1회씩이 아니라 클러스터 전체에서 1회만 발화해야 중복 적재/중복 폴링이 발생하지 않는다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/resources/application.yml:72` — `org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}` (기본 **false**).
  - `deploy/onprem/config/backend/env.template:175` — 배포 템플릿의 기본값도 `QUARTZ_CLUSTERED=false` (주석에는 "2노드를 띄울 때만 두 노드 모두 true 로 설정" 이라고 안내만 있음).
  - `@DisallowConcurrentExecution`(`ControlTrainingVideoScanJob.java:21`, `BatchQuartzJob.java:27`, `BatchRetryQuartzJob.java:23`)은 **동일 스케줄러 인스턴스 내 동일 JobKey**만 직렬화한다. non-clustered JobStore 에서는 노드 간 조율이 없어 두 노드가 각자 트리거를 획득·발화한다.
  - 부수: `deploy/onprem/src/backend/src/main/resources/application.yml:48` 은 `isClustered: 'false'` 로 **하드코딩**(환경변수 미참조)돼 있어 `QUARTZ_CLUSTERED=true` 를 넣어도 무효다. 다만 이 트리는 2026-06-29 스냅샷(현행 backend yml 405행 vs 303행)이라 실제 빌드 소스인지 확인 필요.
- **재현/확인 경로**:
  ```bash
  grep -n 'isClustered' backend/src/main/resources/application.yml deploy/onprem/src/backend/src/main/resources/application.yml
  grep -n 'QUARTZ_CLUSTERED' deploy/onprem/config/backend/env.template
  # 런타임(단일 노드) 확인: 클러스터 락 테이블 사용 여부
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT * FROM public.qrtz_scheduler_state;"
  ```
- **영향**: 기능/데이터 정합. 2노드 운영 시 관제 학습용 스캔이 동시에 두 번 돌아 같은 클립을 경쟁 적재한다(멱등 2중 방어가 있어 중복 INSERT 는 막히나 UK 위반 롤백/로그 잡음 발생). KPST 폴링이 두 번 돌면 완료 처리(`finishDownloadAndComplete`)와 타임아웃 마킹이 경쟁하고, 배치 큐 dequeue 가 이중화되어 동일 rawSn 파이프라인 중복 실행 가능. `deploy/onprem/src` 하드코딩이 실제 빌드 경로라면 토글 자체가 무력.
- **수정 방향(제안)**: ① 배포 템플릿에서 HA 여부를 필수 선택 항목으로 승격(2노드 프로파일 기본 true), ② `deploy/onprem/src` 스냅샷 제거 또는 `${QUARTZ_CLUSTERED}` 로 동기화, ③ 기동 시 "노드 수 > 1 인데 isClustered=false" 를 감지할 방법이 없으므로 최소한 `isClustered=false` 일 때 WARN 1줄 + 운영 체크리스트(`cc-deploy-check`) 항목화.

### [B-ISSUE-03] TC-BATCH-037 — 배치 상태 전이에 상태머신 검증이 전혀 없어 종결 상태(APPROVED)도 무조건 덮어써짐

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `LS_RAW_DATA_STATUS.DATA_STTS_CD` 는 검수 워크플로우 상태이고 `COMPLETED`/`APPROVED` 는 종결 상태다. 배치 경로가 종결된 작업을 임의로 되돌리면 검수 결과가 소실되므로, 불법 전이는 차단(또는 최소한 거부·경고)돼야 한다.
- **현재 동작(이슈 내용)**: 두 엔티티 모두 **무조건 setter** 이고, 검증 책임이 서로에게 떠넘겨져 배치 경로에서는 아무도 검증하지 않는다.
  - `BatchTransitionService.java:30-31` 주석: *"전이 불가 상태(상태 머신 위반) 여부 검증은 `LsRawDataStatus` 의 책임이며, 본 서비스는 단순 갱신만 위임한다."*
  - `LsRawDataStatus.java:87-93`:
    ```java
    /** ... 전이 가능 여부 검증은 ReviewStateMachine 가 책임지며, 본 메서드는 단순 갱신만 수행. */
    public void transitionTo(String newStatus) { this.dataSttsCd = newStatus; this.updDt = LocalDateTime.now(); }
    ```
    → 배치 경로는 `ReviewStateMachine` 을 호출하지 않는다(`BatchTransitionService` 에 의존 없음).
  - `LsDataRaw.changeStatus` 도 blank 검사만 하고 전이 규칙 검증 없음(`LsDataRaw.java:354-359`).
  - 결과: `markRawDataProcessing`(`:59`) → `transitionRawDataStatus(rawSn, PROCESSING)`, `markRawDataCompleted`(`:92`) → `ASSIGNED` 가 현재 상태와 무관하게 적용된다. `APPROVED` 영상에 배치를 재실행하면 `APPROVED → PROCESSING → ASSIGNED` 로 검수 승인이 조용히 소실된다.
  - 도달 경로: `BatchDevTriggerController.trigger`(`:83-86`)는 `PROCESSING` 만 거부하므로 `COMPLETED/APPROVED` 영상에 대해 트리거가 통과한다(`@Profile("!prd")` 라 prd 미노출). `MarkingBatchBridge` 는 `LS_DATA_RAW ∈ {PROCESSING, COMPLETED}` 를 막아 정상 UX 는 보호되나, 이는 **다른 테이블**(LS_DATA_RAW) 기준 방어라 작업상태 종결 보호와 직결되지 않는다.
  - 참고: `tryClaimBatchQueued`(`:188-195`)와 `tryClaimReprocessFromFailed`(`:269-281`)만 조건부 UPDATE(check-and-set)로 상태를 검증한다 — 즉 **검증 가능한 패턴이 이미 코드베이스에 있는데** 완료/실패/처리중 전이에는 적용돼 있지 않다.
- **재현/확인 경로**: (상태 파괴가 있으므로 격리 환경에서만)
  ```bash
  # APPROVED 인 rawSn 확인
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT s.raw_data_id, s.data_stts_cd, r.data_stts_cd FROM public.ls_raw_data_status s JOIN public.ls_data_raw r ON r.raw_sn=s.raw_data_id WHERE s.data_stts_cd='APPROVED';"
  # dev 트리거(비운영) — PROCESSING 이 아니면 통과하여 APPROVED 가 ASSIGNED 로 강등됨
  curl -X POST 'http://localhost:18081/api/v1/dev/batch/trigger?rawSn=<APPROVED rawSn>' -H "Authorization: Bearer <token>"
  ```
- **영향**: 데이터 정합. 검수 승인 이력(작업 종결)이 배치 재실행 한 번으로 사라지고, `TASK_COMPLETED` 통지를 이미 보낸 작업이 다시 미완료 상태가 되어 관제와 상태가 어긋난다. 현재 확인된 도달 경로가 dev 전용 엔드포인트라 CRITICAL로 올리지는 않았으나, 방어가 계약(주석)상으로만 존재하고 실제로는 없다는 점이 문제다.
- **수정 방향(제안)**: ① `markRawDataProcessing`/`markRawDataCompleted`/`markRawDataFailed` 를 조건부 UPDATE(허용 선행 상태 화이트리스트)로 전환하고 영향 행수 0 이면 WARN + skip, ② 불가하면 최소한 `ReviewStateMachine` 검증을 배치 경로에서도 호출, ③ 두 엔티티 주석의 "검증은 X 책임" 문구를 실제 호출 위치와 일치하도록 정정.

### [B-ISSUE-04] TC-BATCH-002 — 학습용 클립 스캔이 매 tick 전체 조회(페이징·미적재 필터 없음) + 클립당 개별 조회 2회

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 60초마다 도는 스캔 잡이므로 후보 조회는 "아직 적재되지 않은 클립" 으로 좁혀지고 처리 건수 상한(배치 크기)이 있어야 한다. 프로젝트 규칙(`.claude/rules/performance.md`)도 "페이징 없는 목록 전체 조회 금지 / `findAll()` without Pageable 금지"를 명시하고, 목표 규모는 영상 5,000건(SFR-17)·이미지 10만장(SFR-16)이다.
- **현재 동작(이슈 내용)**:
  - `MngClipMasterRepository:29-35` — `SELECT c FROM MngClipMaster c WHERE c.jobDmndYn=:jobDmndYn AND c.filePath IS NOT NULL AND TRIM(c.filePath) <> ''` : **Pageable/limit 없음, 이미 적재된 클립을 제외하는 조건 없음**(`NOT EXISTS (LS_DATA_RAW)` 부재).
  - `TrainingVideoIngestService.scanAndIngest:53` 이 결과 전체를 `List` 로 메모리 적재.
  - 클립마다 `videoRepository.findByVmsClipId`(`TrainingVideoIngestTx:100`) + `clipEvntLstRepository.findFirstByEvntId`(`:105`) 를 개별 실행 → 후보 N건이면 매 tick 2N 쿼리.
  - **실동작 증거**: 클립 3건 전량이 이미 적재된 뒤에도 2회차 스캔 로그가 `scanned=3 ingested=0` — 즉 매 tick 전량 재조회 후 전량 skip 이 구조적으로 반복됨을 확인.
- **재현/확인 경로**:
  ```bash
  curl -X POST 'http://localhost:18081/api/v1/dev/batch/scan' -H "Authorization: Bearer <token>"   # → data:0
  docker logs klid-backend | grep 'scan finished'   # scanned=N ingested=0 이 매회 반복
  ```
- **영향**: 성능. 학습용 지정 클립이 수천 건 누적되면 60초마다 전량 SELECT + 수천 회 point lookup 이 관제 공유 DB(MNG_*)에 발생한다. 관제서버와 DB를 공유하므로 부하가 저작도구 밖으로 파급된다.
- **수정 방향(제안)**: ① 쿼리에 `AND NOT EXISTS (SELECT 1 FROM LsDataRaw r WHERE r.vmsClipId = c.clipId)` 추가(멱등 1차 조회도 함께 제거 가능), ② `Pageable`(예: tick 당 100건)로 상한, ③ `findFirstByEvntId` 는 후보 evntId 집합 IN 조회 1회로 배치화. 현행 이중 멱등 가드는 그대로 유지(race 방어).

### [B-ISSUE-05] TC-DEID-019 — mock 비식별 복사 IOException 분기(`'F'` 마킹)의 전용 테스트 부재

- **심각도**: LOW
- **기대 동작(기대효과)**: `runMock` 의 두 실패 분기(원본 부재 / 복사 IOException)는 모두 "성공 위장 금지 — 별도 커밋으로 `'F'` 기록" 이라는 동일 안전 계약을 가지므로, 원본 부재와 동등한 수준의 회귀 테스트가 있어야 한다.
- **현재 동작(이슈 내용)**: 코드는 정확하다 — `DeidentifyStep.java:278-286` 이 `catch (IOException)` 에서 `batchTransitionService.recordDeidentFailure(...)` 호출 후 `INTERNAL_ERROR` 를 던진다. 그러나 `DeidentifyStepTest` / `DeidentifyStepFailurePersistenceIntegrationTest` / `DeidentifyStepExecutePersistenceIntegrationTest` 어디에도 복사 IOException 을 주입하는 테스트가 없다(원본 부재 분기만 3개 테스트로 커버). 즉 `copyAtomically` 리팩토링 시 이 분기가 조용히 깨져도 감지되지 않는다.
- **재현/확인 경로**:
  ```bash
  grep -rn 'IOException' backend/src/test/java/kr/co/cudo/authoring/batch/step/    # → 주입 케이스 없음
  ```
- **영향**: 회귀 위험(테스트 커버리지 갭). 기능 결함은 아님.
- **수정 방향(제안)**: target 부모 경로를 읽기 전용 디렉터리/기존 파일로 만들어 `Files.createDirectories` 또는 `Files.copy` 가 실패하도록 유도하는 통합 테스트 1건 추가(원본 부재 테스트와 동일 픽스처 재사용).

### [B-ISSUE-06] TC-DEID-007 / TC-BATCH-031 — `loadRaw` 자기호출로 `@Transactional(REQUIRES_NEW, readOnly)` 가 적용되지 않음(장식적 애너테이션)

- **심각도**: LOW
- **기대 동작(기대효과)**: 애너테이션이 붙어 있으면 실제로 그 트랜잭션 경계가 열려야 한다. 붙어 있는데 적용되지 않는 상태는, 과거 이 프로젝트에서 실제 결함으로 이어진 패턴이다(`DeidentifyStep.execute` 가 `selfProvider` 를 도입한 이유, 그리고 프레임 추출 deid 경로 NULL 버그).
- **현재 동작(이슈 내용)**: 두 곳 모두 `protected` 메서드를 `this` 로 직접 호출해 Spring AOP 프록시를 우회한다.
  - `AsyncDeidentifyRunner.java:58` `LsDataRaw raw = loadRaw(rawSn)...` → 대상 `:89-96` `@Transactional(readOnly=true, REQUIRES_NEW) protected Optional<LsDataRaw> loadRaw(...)`
  - `BatchOrchestrator.java:104` `LsDataRaw raw = loadRaw(rawSn);` → 대상 `:139-145` 동일 패턴
  - 실제 조회는 리포지토리 기본 트랜잭션으로 수행돼 **기능상 정상 동작**한다(라이브 확인: rawSn 23/24/25 정상 로드, 미존재 rawSn=999999 는 404). 다만 결과 엔티티가 detached 로 반환되며, 애너테이션이 주는 격리 보장은 실제로 존재하지 않는다.
- **재현/확인 경로**: 정적 — 위 두 파일의 호출부/선언부 대조. (동일 클래스 내 self-invocation, `selfProvider` 같은 프록시 우회 회피 장치 없음)
- **영향**: 현재는 무해(readOnly 조회). 향후 이 메서드에 쓰기·잠금·격리수준 의존 로직이 추가되면 즉시 결함이 된다. 또한 코드 독자가 "REQUIRES_NEW 로 격리됨" 이라고 오독한다.
- **수정 방향(제안)**: `DeidentifyStep` 이 이미 쓰는 `ObjectProvider<Self>` 프록시 경유 패턴을 적용하거나, 조회를 별도 빈(예: `BatchRawLoader`)으로 분리. 최소 조치로는 애너테이션을 제거하고 "리포지토리 기본 트랜잭션 사용" 을 주석으로 명시.

---

## 사전 확정 항목 처리 결과

| # | 항목 | 이번 회차 확정 |
|---|------|----------------|
| UNCERTAINTY #2 | 마킹단계 rawSn 비식별 신고 미구현 | **미구현 갭으로 기록** — `grep` 결과 `POST /v1/videos/{rawSn}/deident-report` 컨트롤러 부재 확인. 본 1부(B-1~B-4) 케이스에는 해당 케이스가 없어 판정 대상 아님. B-5 이후 구간에서 재확인 필요 |
| UNCERTAINTY #8 | Quartz 클러스터링 실제 활성 여부 | **확정: 기본 비활성** — `application.yml:72 isClustered=${QUARTZ_CLUSTERED:false}`, 온프렘 템플릿도 `QUARTZ_CLUSTERED=false`. → TC-BATCH-016 PARTIAL + B-ISSUE-02 |
| UNCERTAINTY #21 | `KpstDeidentTxService` 상태전이·락해제 원자성 | **확정: 원자적** — `completeDeidentification`(`:171-179`)이 단일 `@Transactional(REQUIRES_NEW)` 안에서 `verifyDeidFile` → `applyBatchCompletion`(`:191-212`: `markDeidentified("Y")` + `markMarkingReady()` + `releaseRaw` + `resolveOpenReports` + `notify`)을 수행. 라이브 확인 — 10:40:02.338 동일 워커에서 `lock-released rawSn=23` → `completed rawSn=23` 연속 기록, DB 에 Y·MARKING_READY 함께 커밋됨. 결함 없음 |


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-21] TC-VLM-001~030 전반 — VLM 외부 연동이 로컬 목업을 구조적으로 경유할 수 없어 실동작 검증 불가
- **심각도**: HIGH
- **기대 동작(기대효과)**: 검증 대전제("외부 연동은 전부 목업서버가 대행")대로 `vlm.client.url` 을 `klid-mock-server:9400` 으로 지정하면 describe 위탁 → mock 콜백 왕복이 실동작으로 확인돼야 한다.
- **현재 동작(이슈 내용)**:
  - backend 실효 env `VLM_CLIENT_ENABLED=false` (컨테이너 env 실측) → `VlmTimeseriesStep.java:148-151` 즉시 SKIPPED.
  - 활성화 시도는 `_raw/vlm-wiring.md` 실증대로 **부트 크래시**: `WebClientConfig.java:130 validateExternalUrl` → `vlm.client.url 은 HTTPS 스키마만 허용됩니다 (현재: http)`. https 로 우회해도 docker 브리지 사설IP(172.18.0.4)가 SSRF 가드(:150-155)에 걸림. mock-server 는 TLS 미지원.
  - 실증: `docker logs klid-mock-server --since 24h | grep videovlm` → **0건**. `select * from ls_webhook_idempotency` → **0행**. `ls_marking` 전 10행 `stts_cd=PENDING`(VLM_REQUESTED 전이 0건). `ls_data_meta` 의 유일 1행은 수기 EXTERNAL 메타.
  - KPST(비식별)는 `KpstWebClientConfig` 가 http+사설IP 를 명시 허용해 정상 경유(mock 로그에 `/project`, `/retrieve_progress` 인바운드 확인) → **두 외부 클라이언트의 보안 정책이 비대칭**.
- **재현/확인 경로**:
  ```
  docker exec klid-backend env | grep VLM_CLIENT_ENABLED       # → false
  docker logs klid-mock-server --since 24h | grep -i videovlm  # → 0건
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select count(*) from ls_webhook_idempotency;"  # → 0
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,count(*) from ls_marking group by 1;"  # → PENDING만
  ```
- **영향**: TC-VLM-005~012, 015, 016, 018~024 (18건)의 **실왕복 검증 부재**. 벤더 계약(IntelliVIX v2.0.1) 정합은 코드/단위테스트 신뢰에만 의존. self-fill 은 **없음**(메타·마킹 전이 0건으로 확증) — 이 점은 오히려 fail-closed 로 정상.
- **수정 방향(제안)**: `WebClientConfig.vlmWebClient` 의 검증을 `KpstWebClientConfig` 와 동일하게 스킴 분기(http 허용+WARN)하거나 local 프로파일 한정 완화. (구현 금지 — vlm-wiring.md 의 동일 제안과 중복 계상)

### [B-ISSUE-22] TC-VLM-001 — 동일 rawSn 에 마킹 N건 생성 가능, VLM 은 첫 건만 위탁 → 나머지 마킹 영구 PENDING
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 영상 1건당 마킹은 1건으로 수렴하거나, 다중 마킹이 허용된다면 전 건이 파이프라인 상태 머신을 완주해야 한다.
- **현재 동작(이슈 내용)**: `MarkingService.create` 에 중복 마킹 방지 가드가 없다(`ls_marking` 에 rawSn UNIQUE 없음 — `\d ls_marking` 실측 인덱스는 PK + `idx_lm_raw` 비유니크). 동시 3요청 → `markingSn 25,26,27` 3행 생성(전부 201). 배치는 1회만 돌고(`MarkingLoadStep … count=3`), `VlmTimeseriesStep.java:107` 이 `markings.get(0)` 만 위탁 → 나머지 2건은 어떤 전이도 받지 못한다.
  - 실측: `select marking_sn,raw_sn,stts_cd from ls_marking where raw_sn=16;` → 25/26/27 모두 `PENDING`.
- **재현/확인 경로**: MARKING_READY 영상에 `POST /v1/videos/{rawSn}/markings` 를 3회 동시 실행 → 201 ×3, `ls_marking` 3행, 배치 1회.
  ```
  for i in 1 2 3; do curl -s -X POST .../v1/videos/16/markings -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":600}' & done; wait
  ```
- **영향**: 시계열 메타가 일부 마킹 기준으로만 생성됨. 프레임 추출도 어떤 marks 를 쓰는지 비결정적(순서 의존). 고아 마킹 누적으로 상태 조회/통계 왜곡.
- **수정 방향(제안)**: ① rawSn 당 활성 마킹 1건 제약(부분 유니크 인덱스 또는 서비스 가드 409) 또는 ② 배치가 전 마킹을 순회 위탁. 브릿지의 배치 1회 보장과 별개 축이므로 마킹 생성 측에서 막는 편이 blast-radius 가 작다.

### [B-ISSUE-23] TC-BATCH-078 — AUTO intervalFrames 상한 미검증 → 과대값이 marks 1건으로 퇴화
- **심각도**: LOW
- **기대 동작(기대효과)**: `intervalFrames` 가 `totalFrames` 를 초과하면 자동 마킹이 의미를 잃으므로 400 으로 거부하거나 최소 marks 수를 보장한다.
- **현재 동작(이슈 내용)**: `MarkingService.java:162-164` 는 하한(≥1)만 검증한다. `intervalFrames=999999999` 요청이 **201** 로 통과하고 `marks` 는 `[{"frameIndex":0,"timestamp":"00:00"}]` 단 1건 → 이후 프레임 추출도 1프레임만 산출.
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:18081/api/v1/videos/15/markings -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":999999999}'
  # → 201, marks 1건 (실측: markingSn=24)
  ```
- **영향**: 오조작/오입력 시 학습데이터 프레임이 1장만 생성되는데 상태는 정상 완료로 흐른다. `durationSec≤0` 은 backstop 으로 막으면서 반대편 경계는 무방비(비대칭).
- **수정 방향(제안)**: `@Max` 또는 서비스에서 `intervalFrames < totalFrames` 검증, 혹은 산출 marks 가 1건이면 경고/거부.

### [B-ISSUE-24] TC-VLM-003 — VLM 단계 skip(disabled)이 DB에 무흔적 → 시계열 메타 누락의 사후 추적 불가
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 파이프라인 단계가 건너뛰어졌다면 `LS_BATCH_PROC_LOG` 에 SKIPPED 로 남아, 완료된 영상에 시계열 메타가 없는 이유를 사후 추적할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.java:147-151` 이 로그만 남기고 즉시 반환하며 `batchStatusService.recordVlmTimeseriesResult` 를 호출하지 않는다(단위테스트 `#enabled_false_시_BatchStatusService_recordVlmTimeseriesResult_미호출` 로 의도 확정). 실측 `ls_batch_proc_log` 의 rawSn 16/24 행에 `proc_step_cd` 가 `FRAME_EXTRACT` 만 있고 **VLM 단계 행은 아예 없음**.
- **재현/확인 경로**:
  ```
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select proc_step_cd, proc_stts_cd from ls_batch_proc_log where data_raw_sn=24;"   # VLM 행 없음
  docker logs klid-backend | grep "VlmTimeseries] skipped"                                 # 로그에만 존재
  ```
- **영향**: 운영에서 VLM 비활성/장애 구간에 처리된 영상들이 "메타 없음 + 무기록"으로 남아, 사후 재처리 대상 식별이 로그 보존기간에 종속된다.
- **수정 방향(제안)**: disabled 경로에서도 `LS_BATCH_PROC_LOG` 에 `proc_step_cd=VLM, proc_stts_cd=SKIPPED` 1행 기록(또는 영상 단위 skip 플래그).

### [B-ISSUE-25] TC-VLM-030 — VLM 콜백이 무인증 + rate limit 미적용 + IP allowlist TODO 미이행
- **심각도**: MEDIUM (CWE-770 Unrestricted Resource Consumption / CWE-307 brute force 방어 부재)
- **기대 동작(기대효과)**: 인증 없이 공개된 엔드포인트는 최소한 요청 빈도 제한(또는 IP allowlist)으로 보호돼야 한다. 동일 필터가 augment 경로에는 이미 이 방어를 적용하고 있다.
- **현재 동작(이슈 내용)**: `HmacWebhookFilter.java:150-153` 이 `/v1/vlm/callback` 을 `sizeCapOnlyPaths` 로 분기해 **본문 4MB 캡만** 적용하고 즉시 체인으로 넘긴다. `isRateLimited()` 호출은 HMAC 분기(:163-169)에만 있어 VLM 경로는 무제한이다. `VlmResultController.java:22` 의 `// TODO(보안): 실운영 전 IP allowlist 재검토` 는 미이행. 콜백 1건당 `lookupForProcessing`(비관적 락 SELECT)이 무조건 실행된다.
- **재현/확인 경로**:
  ```
  for i in $(seq 1 15); do curl -s -o /dev/null -w "%{http_code} " -X POST \
    http://localhost:18081/api/v1/vlm/callback -H 'Content-Type: application/json' \
    -d "{\"request_id\":\"forged-rl-$i\",\"status\":\"failed\",\"error\":{\"code\":\"E\",\"message\":\"m\"}}"; done
  # 실측 → 401 401 401 ... (15회 전부, 429 없음)
  ```
- **영향**: 인증 없이 DB 락 SELECT 를 유발하는 pre-auth DoS 표면. 데이터 주입 자체는 UUIDv4 request_id 게이트로 차단되므로 **무결성 침해는 아님**(위조 8종 전부 401 확인).
- **수정 방향(제안)**: `handleSizeCapOnly` 에도 IP 기반 rate limit 적용, 그리고 prd 프로파일에서 벤더 IP allowlist 필터 추가(TODO 종결).

### [B-ISSUE-26] TC-BATCH-060 — 로그 인젝션(CWE-117) sanitize 회귀 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 계층(sanitize) 은 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: `MarkingBatchBridge.java:120-122` 의 `sanitize()` 는 구현돼 있으나 `MarkingBatchBridgeTest` 12개 케이스 중 CR/LF 를 주입해 검증하는 테스트가 없다(`grep -niE "sanitize|CWE-117"` 0건). 누군가 로그 문장을 리팩터링하며 `sanitize()` 를 빼도 테스트가 잡지 못한다.
- **재현/확인 경로**: `grep -c "sanitize" backend/src/test/java/kr/co/cudo/authoring/marking/listener/MarkingBatchBridgeTest.java` → 0
- **영향**: 방어 로직의 조용한 소실 위험. 현재 코드 자체는 정상(탭 미제거이나 로그 1행 파괴는 아님).
- **수정 방향(제안)**: `dataSttsCd="PROC\nING"` 같은 값으로 로그 캡처 단언 테스트 1건 추가.

### [B-ISSUE-27] TC-VLM-010/013/024/025, TC-BATCH-081 — 테스트 커버리지 갭 + "45s 타임아웃" 근거 불일치
- **심각도**: LOW (단, TC-VLM-010 근거 불일치는 **확인필요**)
- **기대 동작(기대효과)**: 케이스가 지목한 동작(타임아웃 실효값, SSRF 고정 base, 콜백 동시성, 로그 마스킹, 분수 fps 반올림)이 테스트로 고정돼 있어야 한다.
- **현재 동작(이슈 내용)**:
  1. **TC-VLM-010 근거 불일치**: 케이스는 "45s 블록 타임아웃"이나, 실효 타임아웃은 `VlmClient.java:71,76` 의 `vlm.client.timeout-seconds:10` → `:108 .timeout(timeout)` 이다. `VlmTimeseriesStep.java:74` 의 45s `block()` 은 Retry(exp backoff) 누적을 감안한 상한이며 단일 호출로는 도달하지 않는다. `application.yml:384 timeout-seconds: 10` 실측. → UNCERTAINTIES #13 의 "VLM 45s" 전제와 코드가 어긋남.
  2. 타임아웃 동작 테스트 **없음**.
  3. TC-VLM-013(콜백 URL 고정 base) 전용 테스트 **없음**.
  4. TC-VLM-024(동시 콜백 직렬화) 동시성 IT **없음** — B-5 브릿지는 동시성 IT 가 있는데 콜백 측만 비대칭.
  5. TC-VLM-025 로그 마스킹 테스트 **없음**.
  6. TC-BATCH-081: `generateAutoMarks` 에 29.97 을 넣는 테스트 없음(25/30/60 만).
  7. `VlmResultControllerTest` 파일 자체가 부재.
- **재현/확인 경로**: `grep -rn "BLOCK_TIMEOUT\|resolveCallbackUrl\|29.97" backend/src/test/` → 해당 단언 0건
- **영향**: 회귀 감지 공백. 특히 45s/10s 이중 타임아웃은 운영 타임아웃 산정 문서(D4/연동규격서)와 드리프트할 소지.
- **수정 방향(제안)**: 케이스 문구를 "블록 상한 45s / 실효 클라이언트 타임아웃 10s"로 분리 기술하고, 위 5종 테스트 보강.

### [B-ISSUE-28] (참고 기록) 마킹 단계 rawSn 비식별 신고 미구현 — UNCERTAINTIES #2 확정
- **심각도**: MEDIUM (미구현 갭 — 케이스 표 대상 아님, 기록 목적)
- **기대 동작(기대효과)**: CLAUDE.md "마킹 단계(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`)" 가 존재해야 함(문서상 planned).
- **현재 동작(이슈 내용)**: 구현된 것은 라벨링 단계(`DeidentReportController.java:91` `POST /v1/labels/{srcSn}/deident-report`)뿐. rawSn 경로는 **컨트롤러 자체가 없음**.
- **재현/확인 경로**: `curl -X POST .../v1/videos/25/deident-report -H "Authorization: Bearer $RT" -d '{"reason":"test"}'` → **404**
- **영향**: 마킹 화면에서 비식별 누락을 발견해도 신고 경로가 없어 라벨링 단계까지 진행해야 신고 가능.
- **수정 방향(제안)**: 정책 확정 후 rawSn 기준 신고 엔드포인트 추가(작업락 + 영상 단위 처리).

---


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-41] TC-BATCH-107 관련 — 07-22 수정 이전 레거시 프레임(rawSn 11/12/13)의 DB NULL 방치로 export가 여전히 PARTIAL
- **심각도**: LOW (신규 결함 아님 — 이미 알려진 갭의 실측 재확인)
- **기대 동작(기대효과)**: 프레임 2벌(원본+비식별)이 추출된 영상은 검수 export가 SUCCEEDED로 산출되어야 한다.
- **현재 동작(이슈 내용)**: `ls_data_src`에서 rawSn 11/12/13은 디스크에 비식별 프레임 파일이 실재함(`docker exec klid-backend ls /app/storage/deidentified/frames/deid/{11,12,13}` 확인)에도 `de_idntf_src_file_path_nm` 컬럼이 전량 NULL. 07-22 수정(6-arg `LsDataSrc.create`로 INSERT 시점에 deid 경로 포함)은 코드상 정상 반영돼 있고 신규 rawSn(14,17,26 등)은 정상이지만, 수정 이전에 생성된 행은 백필되지 않아 export 재조회 시 rawSn=13은 `ls_dataset_export.export_stts_cd='PARTIAL'`로 실측됨.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT raw_sn, count(*), count(de_idntf_src_file_path_nm) FROM public.ls_data_src WHERE raw_sn IN (11,12,13) GROUP BY raw_sn;"`
- **영향**: 07-22 이전 생성된 소수의 레거시 영상만 영향(신규 파이프라인은 정상). 데이터마트 반출 시 해당 영상만 비식별 프레임 누락으로 표시.
- **수정 방향(제안)**: 1회성 백필 배치(레거시 `ls_deident_proc_log.de_idntf_file_path_nm` 기준으로 `ls_data_src.de_idntf_src_file_path_nm` UPDATE) — 메모리 `frame-extract-deid-path-null-bug`에 이미 "스코프 밖"으로 기록된 사항이라 이번 회차에서는 정보 기록만.

### [B-ISSUE-42] YOLO/SAM2 배치 저장이 루프 내 개별 save() — IDENTITY 전략이 hibernate.jdbc.batch_size 설정을 무력화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/performance.md`("대량 처리: 1건씩 save 금지 → saveAll() 사용", "hibernate.jdbc.batch_size: 50")에 따라 프레임×검출 단위의 대량 INSERT는 배치로 묶여야 한다.
- **현재 동작(이슈 내용)**:
  - `YoloLabelPersister.persistBbox`(`backend/src/main/java/kr/co/cudo/authoring/batch/step/YoloLabelPersister.java:58~`)가 `YoloAutolabelStep.java:220-224` 루프(프레임×검출) 안에서 매 detection마다 `lblRepository.save()` + `aiInfoRepository.save()`를 개별 호출.
  - `Sam2SegmentStep.java:186-189`도 동일하게 SegmentJob 루프 안에서 `lblRepository.save()` + `aiInfoRepository.save()` 개별 호출.
  - `TrackInterpolationStep.java:178,184`만 `saveAll()` 사용(상대적으로 낫지만 근본 해결은 아님 — 아래 참고).
  - `LsDataLbl`/`LsDataLblAiInfo` 엔티티는 `@GeneratedValue(strategy = GenerationType.IDENTITY)`(`LsDataLbl.java:55`) — Hibernate는 IDENTITY 전략에서 **PK를 즉시 알아야 하므로 JDBC 배치를 구조적으로 비활성화**한다. 즉 `application.yml:33-36`의 `hibernate.jdbc.batch_size=50`/`order_inserts=true` 설정은 이 엔티티들에는 **효과가 없다**(saveAll()로 바꿔도 동일 — IDENTITY 전략 자체가 원인).
  - 실측: rawSn=26 라이브 구동에서 YOLO 64건, SAM2 62건 각각 실제로는 128/124회의 개별 INSERT(라벨+AI_INFO)로 실행됨(로그 타임스탬프 간격 상 SAM2는 프레임당 순차 호출·저장이 관측됨 — SAM2 CPU 추론 자체가 지배적 비용이라 즉각적 성능 문제로 체감되진 않으나, SFR-16(이미지 10만장) 규모에서는 누적 INSERT 왕복이 유의미해질 수 있음).
- **재현/확인 경로**: `grep -n "GenerationType.IDENTITY" backend/src/main/java/kr/co/cudo/authoring/batch/entity/LsDataLbl.java` + `grep -n "batch_size" backend/src/main/resources/application.yml`
- **영향**: 성능(대량 처리 시 배치 INSERT 미적용) — 기능 정확성에는 영향 없음. CWE 해당 없음(성능 규칙 위반).
- **수정 방향(제안)**: (a) `LsDataLbl`/`LsDataLblAiInfo`를 시퀀스 기반 PK(`GenerationType.SEQUENCE` + `allocationSize`)로 전환해 실제 JDBC 배치를 활성화하거나, (b) 프레임 단위로 라벨을 모아 `saveAll()` 일괄 호출로 리팩터링(단, (a) 없이는 배치 효과 제한적).


## 이슈 상세 (FAIL / PARTIAL / BLOCKED / 확인필요 전건)

### [B-ISSUE-61] TC-STREAM-B04 — 해상도 파생영상 스트리밍이 전면 403(FORBIDDEN)으로 차단됨
- **심각도**: HIGH (기능 차단, 보안 회귀 위험 동반)
- **기대 동작(기대효과)**: 해상도 파생영상(`AUG_TYPE_CD=RESL_*`)도 파생영상으로서 마킹/라벨링/검수 화면에서 재생되어야 한다(CLAUDE.md — 파생영상은 기존 RAW_SN 파이프라인을 그대로 탄다).
- **현재 동작(이슈 내용)**: 파생영상의 비식별 경로가 **raw 저장소 밑**에 기록되는데, 스트리밍 가드는 **deidentified 저장소 base 만** 허용한다.
  - `backend/.../video/service/ResolutionReservationPersister.java:82-84`
    ```java
    Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
    String derivativeVideoPath = resolveSafeDir(base,
        "resolution/" + parent.getRawSn() + "/" + preset.name() + "/video/" + preset.name() + ".mp4").toString();
    ```
  - `backend/.../video/service/ResolutionPersistService.java:143-146` — 이 경로를 그대로 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 으로 기록(`procLog.succeed(videoDst)`).
  - `backend/.../video/service/VideoStreamService.java:266-267` — `baseDir = Paths.get(deidentifiedPath)` 로만 검증 → `resolveSafe` 가 `:353-354` 에서 FORBIDDEN.
  - 런타임 env: `STORAGE_RAW_PATH=/app/storage/raw`, `STORAGE_DEIDENTIFIED_PATH=/app/storage/deidentified` (서로 다름).
  - **실측**: `DE_IDENT_YN='Y'` 인 파생 4건(rawSn 15,16,18,19) 전부 403. 비파생 Y 영상 9건은 전부 206.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT r.raw_sn, r.de_ident_yn, l.de_idntf_file_path_nm FROM public.ls_data_raw r
     JOIN public.ls_deident_proc_log l ON l.data_raw_sn=r.raw_sn AND l.proc_stts_cd='SUCCEEDED'
     WHERE r.orgnl_raw_sn IS NOT NULL;"
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $RT" \
    -H 'Range: bytes=0-9' http://localhost:18081/api/v1/videos/18/stream   # -> 403
  ```
- **영향**: SFR-06-03 해상도 파생 결과물을 화면에서 확인·마킹·검수 불가. 더 위험한 것은 **성급한 수정 방향**이다 — 가드에 rawPath base 를 통째로 추가(과거 export 경로에서 실제로 채택된 우회, memory `cudo246-realtest-issue-batch` ★E)하면 `/app/storage/raw` 아래 **진짜 원본**까지 스트리밍 허용 범위에 들어와 PII 유출(CWE-22/CWE-359)로 전환된다.
- **수정 방향(제안)**: ① 파생영상 비디오 산출물을 **deidentified base 하위**(`{deidPath}/videos/{newRawSn}/…`)에 생성하도록 `ResolutionReservationPersister` 경로 기준을 바꾸는 것이 정공법. ② 불가피하게 raw base 를 허용해야 한다면 base 전체가 아니라 **`{rawPath}/resolution/` 서브트리로 한정한 allowlist** 로 좁히고, 원본 파일 경로(`RAW_FILE_PATH_NM`)와의 동일성 검사를 추가해 원본 직접 서빙을 차단할 것.

### [B-ISSUE-62] TC-DEID-035 — 실행 중 백엔드가 HEAD 미반영(V130 미적용)이라 개인정보 3필드 리셋을 실동작 검증 불가
- **심각도**: MEDIUM (검증 환경 결함 — 기능 결함 아님)
- **기대 동작(기대효과)**: 실동작 검증 대상 스택은 HEAD 소스와 동일 빌드여야 한다.
- **현재 동작(이슈 내용)**:
  - 실행 jar `/app/app.jar` 타임스탬프 `Jul 23 17:10`, 내장 마이그레이션 최대 = `V129__ls_label_add_dtct_type` (`V130__add_manual_env_privacy_meta` 부재).
  - DB `public.flyway_schema_history` 최신 = 129. `public.ls_data_src` 에 `anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 컬럼 **없음**.
  - `grep -a resetPrivacyMetaByRawSn /app/app.jar` → 0건 (HEAD `27b6bb0d` 에서 추가된 `DeidentReportService:151-155` 코드가 배포본에 없음).
  - HEAD 커밋 `27b6bb0d` 는 `DeidentReportService.java`·`LsDataSrc.java`·`LsDataSrcRepository.java` 를 변경했으므로 **B-11 의 5-1 단계는 실행 중 백엔드에 존재하지 않는다**. (반면 `VideoStreamService`/`StreamUrlSigner`/`StreamSignatureFilter`/`VideoController` 는 해당 커밋 미변경 → B-10 live 결과는 HEAD 와 동치.)
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend sh -c "ls -l /app/app.jar; grep -a -o 'V1[23][0-9]__[a-z_]*' /app/app.jar | sort -u | tail -3"
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "\d public.ls_data_src" | grep -i incl_yn
  ```
- **영향**: TC-DEID-035 실동작 검증 불가(BLOCKED). 그 외 이번 회차의 다른 B-11 케이스는 변경 범위 밖 코드라 결과 유효하나, **후속 클러스터(특히 메타 수동입력·export 관련)는 stale 스택에서 위양성/위음성이 날 수 있음**.
- **수정 방향(제안)**: 검증 착수 전 `docker compose -f docker-compose.yml -f docker-compose.local.yml build klid-backend && up -d` 로 HEAD 재빌드 후 flyway 가 V130 을 적용했는지(`flyway_schema_history` 최신 버전) 확인하는 절차를 검증 프로토콜에 고정.

### [B-ISSUE-63] TC-STREAM-B15 — `/stream` 에 영상 단위 인가가 없어 배정되지 않은 WORKER 가 임의 영상을 재생할 수 있음
- **심각도**: MEDIUM (확인필요 — 의도된 광범위 허용인지 정책 확인 필요)
- **기대 동작(기대효과)**: 라벨링/검수 흐름과 동일하게 WORKER 는 **본인 배정 영상**만 열람 가능해야 한다(`LabelAccessGuard.verifyRawAccess` 와 동일 기준). 비식별본이라도 영상 열람은 개인정보 인접 자산이다.
- **현재 동작(이슈 내용)**: `backend/.../video/controller/VideoController.java:220`
  ```java
  @PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")
  ```
  역할만 검사하고 `rawSn` 소유/배정 검증이 없다. `VideoStreamService.stream()` 내부에도 배정 검사 없음.
  - **실측**: 배정 이력이 전혀 없는 WORKER(sub=2002) 토큰으로 `rawSn=17`(다른 사용자 2001 배정) 스트리밍 → **206**. 배정 없는 25/24/23 도 206.
  - 서명 URL 경로도 동일 — `/stream-url` 은 role-gated 이므로 임의 WORKER 가 임의 rawSn 의 서명 URL 을 발급받을 수 있다(`VideoStreamService.java:114-141` 에 배정 검사 없음).
- **재현/확인 경로**:
  ```bash
  # 배정 0건 WORKER 토큰 발급
  curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"role":"WORKER","channel":"INTERNAL","userNo":"2002"}'
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $W2" \
    -H 'Range: bytes=0-100' http://localhost:18081/api/v1/videos/17/stream   # -> 206
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT user_no, raw_data_id, task_type_cd FROM public.ls_task_assignment WHERE user_no=2002;"  # -> 0 rows
  ```
- **영향**: 수평 권한 상승(IDOR, CWE-639 / OWASP API1:2023). 서빙 대상이 비식별본이라 원본 PII 직접 유출은 아니므로 CRITICAL 은 아니나, 내부 사용자 간 데이터 격리가 없다. `UNCERTAINTIES.md #4`(관제 조회 API 광범위 허용은 의도됨)와 동일한 정책 판단이 스트리밍에도 적용되는지 **확인 필요**.
- **수정 방향(제안)**: 의도된 허용이면 UNCERTAINTIES 에 명시 확정. 아니면 `/stream`·`/stream-url` 진입부에 `LabelAccessGuard.verifyRawAccess(rawSn, actor)` 를 적용(REVIEWER 전체 허용 / WORKER 본인 배정) 하고, 서명 URL 은 발급 시점 인가 결과가 서명에 이미 바인딩(userNo)되므로 추가 비용 없음.

### [B-ISSUE-64] 마킹 단계 rawSn 비식별 신고 미구현 (설계 대비 갭)
- **심각도**: LOW (기록 목적 — `UNCERTAINTIES.md #2` 확정에 따라 갭으로 기록)
- **기대 동작(기대효과)**: CLAUDE.md — 비식별 누락 신고는 **마킹 단계(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`)** 와 라벨링 단계(srcSn) 양쪽에서 가능해야 한다(마킹 단계는 "설계 타깃/planned" 로 표기됨).
- **현재 동작(이슈 내용)**: `POST /v1/videos/13/deident-report` → **404**. `grep -rn "videos/{rawSn}/deident-report" backend/src/main/java` → 0건. 신고 엔드포인트는 `DeidentReportController.java:91`(srcSn) 하나뿐.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18081/api/v1/videos/13/deident-report \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{"reason":"x"}'   # -> 404
  ```
- **영향**: 마킹 화면(프레임 추출 이전 단계)에서 비식별 누락을 발견해도 신고 경로가 없어 작업자가 그대로 마킹을 진행하게 된다. 프레임 추출 후(srcSn 존재) 단계에서만 신고 가능.
- **수정 방향(제안)**: 구현 필요 여부를 R1 v1.14 대비로 확정한 뒤, 구현 시 `DeidentReportService.report` 를 rawSn 진입 오버로드로 분리(현재는 srcSn→rawSn 해석에 `LabelAccessGuard.verifyAndGet(srcSn)` 의존).

### [B-ISSUE-65] 증강 파생영상(WINTER/NIGHT/RAIN)이 `DE_IDENT_YN='N'` 으로 고착되어 스트리밍 불가
- **심각도**: LOW (기지의 planned 갭 재확인)
- **기대 동작(기대효과)**: 증강 파생영상도 비식별 원본 복사본이므로 재생 가능해야 한다.
- **현재 동작(이슈 내용)**: rawSn 20/21/22 (`ORGNL_RAW_SN=13`, 외부 증강 파생) 전부 `DE_IDENT_YN='N'`, `DATA_STTS_CD='FAILED'` → 스트리밍 **404**. CLAUDE.md 기재대로 "증강 적재 경로는 `VideoIngestedEvent` 미발행 — 선두 비식별 자동화 미연동(planned)" 상태가 데이터로 확인됨.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, orgnl_raw_sn, de_ident_yn, data_stts_cd FROM public.ls_data_raw WHERE orgnl_raw_sn=13;"
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/20/stream  # -> 404
  ```
- **영향**: 증강 결과 검수(SCR-AUG-002) 화면에서 영상 재생 불가. 단 fail-closed 방향(원본 노출 없음)이라 보안 위험은 없음.
- **수정 방향(제안)**: 증강 적재 경로에도 `VideoIngestedEvent` 발행 또는 파생 확정 시 `markDeidentified("Y")`(해상도 파생 `ResolutionPersistService:141` 과 동일 처리) 적용 여부를 결정.

---

## 부수 관찰 (결함 아님 / 참고)

- **과거 데이터 잔존**: rprtSn=1(rawSn=4)은 `RESOLVED` + 작업락 `RELEASED(MANUAL_DEIDENT_DONE)` 인데 `LS_DATA_RAW.DE_IDENT_YN` 이 여전히 `'F'`. 해당 resolve 는 2026-07-15 로, `'F'→'Y'` 복원 코드(`DeidentReportService:226-230`)가 도입되기 이전 데이터로 보임. 현행 코드 기준 회귀 아님.
- **`resolveOpenReports`**: OPEN 신고가 0건이어도 `workLockService.releaseRaw(...)` 와 캐시 evict 를 무조건 호출(`:287-289`). 멱등이라 무해하나 의도 확인 시 참고.
- **200 응답의 `Content-Range`**: Range 미지정 200 응답에도 `Content-Range: bytes 0-17/18` 이 붙는다(`ResourceRegionHttpMessageConverter` 동작). RFC 상 200 에 `Content-Range` 는 무의미하나 클라이언트 영향 미관측.

## 근거 라인 드리프트

**0건.** B-10/B-11 의 근거 `file:line` 38개 참조를 HEAD 소스와 전수 대조한 결과 모두 일치(`VideoStreamService.java`, `VideoController.java`, `DeidentReportService.java`, `DeidentReportController.java`).
단 `TC-STREAM-B16` 의 `VideoStreamService.java:252` 는 실코드가 아닌 javadoc 라인이고 실제 `unless` 절은 `:257` — 표기 자체는 `252,257` 로 되어 있어 드리프트로 계수하지 않음.


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-81] TC-BATCH-171 — Quartz 클러스터링이 기본 비활성이며 온프렘 배포 템플릿도 false → 2노드 Active-Active 시 스케줄러 잡 중복 실행
- **심각도**: HIGH (운영/구성)
- **기대 동작(기대효과)**: CLAUDE.md "배치 성능" — *"주 서버 2노드 Active-Active 이중화 … **Quartz 클러스터링 적용**(PostgreSQL JobStore 락으로 잡 중복 방지)"*. 2노드 동시 기동 시 `kpstDeidentPollJob`·`batchPipelineJob`·`batchRetryJob`·`controlTrainingVideoScanJob`·`datasetExportPendingSweepJob` 각 트리거가 **정확히 1회만** 발화해야 한다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/resources/application.yml:69-73`
    ```yaml
    # 이중화(HA) 노드만 true — local/dev 단일 인스턴스는 false 유지.
    org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}
    org.quartz.jobStore.clusterCheckinInterval: ${QUARTZ_CHECKIN_MS:20000}
    ```
    → **기본값 false**. `application-{local,dev,stg,prd}.yml` 어디에도 `QUARTZ_CLUSTERED` override 없음(grep 0건).
  - `deploy/onprem/config/backend/env.template:175` → `QUARTZ_CLUSTERED=false` (배포 산출물 기본값도 false)
  - **런타임 실측**: `qrtz_scheduler_state` 0행 (클러스터 모드에서만 체크인 행이 생성됨) · `qrtz_fired_triggers.instance_name = "NON_CLUSTERED"`
  - 결과: 2노드를 띄우면서 두 노드 모두 `QUARTZ_CLUSTERED=true` 를 **명시 설정하지 않으면** 두 노드가 서로를 모른 채 동일 트리거를 각자 발화한다. `@DisallowConcurrentExecution` 은 **JVM/스케줄러 인스턴스 내부**에서만 유효하므로 노드 간 중복을 막지 못한다.
  - 파급: 관제 학습영상 스캔 이중 적재, KPST 폴링 이중 완료 처리(→ B-ISSUE-82), 배치 재시도 이중 실행, export sweep 이중 쓰기.
  - 문서 충돌: `CLAUDE.md`("클러스터링 적용") vs `docs/design/D5-아키텍처설계서.md:126`("기본은 단일 인스턴스, 이중화 시 옵션 지원") — **D5 가 코드와 일치**, CLAUDE.md 서술이 앞서감.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select * from qrtz_scheduler_state;"          # 0 rows
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select instance_name from qrtz_fired_triggers;" # NON_CLUSTERED
  grep -rn "QUARTZ_CLUSTERED" backend/src/main/resources deploy/onprem/config/backend/env.template
  ```
- **영향**: CWE-362 (Race Condition) — 노드 간 잡 중복 실행. 데이터 이중 적재/이중 상태전이. 배포 구성 실수 1건으로 조용히 발현되며 로그만으로는 감지 어려움.
- **수정 방향(제안)**: ① 온프렘 2노드 배포 프로파일에서 `QUARTZ_CLUSTERED=true` 를 **기본값으로** 하거나, 기동 시 "노드 수 ≥ 2인데 isClustered=false" 를 감지해 **fail-closed 부트 거부 또는 WARN 배너**(`DevToggleStartupWarner` 패턴 재사용). ② `env.template` 주석에 "2노드면 반드시 true" 를 체크리스트 항목으로 승격. ③ CLAUDE.md 를 D5 서술("옵션 지원")에 맞춰 정정하거나 반대로 기본값을 true 로 뒤집기 — 어느 쪽이든 **문서·기본값 단일화** 필요. ④ 클러스터 모드 필수 조건인 노드 클럭 동기화(NTP) 를 배포 체크리스트에 명시.

### [B-ISSUE-82] TC-DEID-077 — KPST 폴링 대상 조회에 원자 클레임이 없어, 비클러스터 2노드에서 동일 위탁 건 중복 폴링·중복 완료 처리 가능
- **심각도**: HIGH (B-ISSUE-81 이 선행 조건)
- **기대 동작(기대효과)**: 동일 `procLog` 를 두 노드가 동시에 완료 처리하지 않는다(중복 다운로드/전이 방지 — `KpstDeidentPollJob` Javadoc `:23` 이 명시한 방어 목표).
- **현재 동작(이슈 내용)**:
  - `KpstDeidentPollJob.java:50`
    ```java
    List<LsDeidentProcLog> targets = procLogRepository.findByPollSttsCdIn(POLL_TARGET_STATUSES);
    ```
    `LsDeidentProcLogRepository.java:47` — **평범한 파생 쿼리**. `BatchRetryQueue.claimAtomically`(PENDING→RETRYING CAS)에 해당하는 **소유권 클레임이 없다**. `POLLING` 상태는 "처리중" 이 아니라 단순 진행 표시라 다른 노드도 그대로 재조회한다.
  - `KpstDeidentTxService.finishDownloadAndComplete:59-70` 에도 멱등 가드(예: `poll_stts_cd='WAITING'/'POLLING'` 조건부 UPDATE)가 없어, 두 노드가 동시에 완료를 커밋하면 `markDownloaded`·`applyCompletion`(REDEIDENT 시 `deidentFrameAttacher.attachDeidentFrames`)이 **두 번** 수행된다.
  - 단일 노드에서는 `@DisallowConcurrentExecution`(DB `is_nonconcurrent='t'` 실확인)이 막으므로 현행 로컬/단일 배포에서는 발현하지 않음.
  - 부가: `findByPollSttsCdIn` 에 페이징/상한이 없어 대기 건이 누적되면 전건 로드(CWE-770 소지, LOW).
- **재현/확인 경로**:
  ```bash
  grep -n "findByPollSttsCdIn" backend/src/main/java/kr/co/cudo/authoring/batch/repository/LsDeidentProcLogRepository.java
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select proc_log_sn,poll_stts_cd from ls_deident_proc_log where poll_stts_cd in ('WAITING','POLLING');"
  # 2노드 재현: QUARTZ_CLUSTERED 미설정 상태로 backend 2 인스턴스 기동 → 동일 procLogSn 에 대해 [KpstDeid] poll completed 로그 2회
  ```
- **영향**: CWE-362. 중복 프레임 attach/캐시 무효화, 알림 이중 발송, `LS_DATA_SRC` 갱신 경쟁.
- **수정 방향(제안)**: `BatchRetryQueue` 와 동일 패턴 적용 — ① `POLL_CLAIMED`(또는 `POLLING` 의미 강화) 상태로 조건부 원자 UPDATE 클레임 후 그 건만 폴링, ② 또는 `finishDownloadAndComplete` 진입 시 `UPDATE … SET poll_stts_cd='DOWNLOADED' WHERE proc_log_sn=? AND poll_stts_cd IN ('WAITING','POLLING')` 영향행수 1 검사로 멱등 게이트. ③ 조회에 `Pageable` 상한 추가. (B-ISSUE-81 해결 시 위험은 크게 낮아지나, 클러스터링을 켜도 잡 자체는 한 노드에서만 돌 뿐이므로 ①/② 는 방어심도로 유효.)

### [B-ISSUE-83] TC-BATCH-159 — 재시도 큐 `RETRYING` 클레임 후 노드 사멸 시 복구 경로가 없어 해당 재시도가 영구 유실
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재시도 항목을 클레임한 노드가 처리 중 죽어도, 다른 노드/다음 tick 이 그 항목을 회수해 재시도를 이어간다(2노드 A-A 내구성 — 이 큐가 DB 로 옮겨진 애초 목적).
- **현재 동작(이슈 내용)**:
  - `BatchRetryQueue.pollReady:100-112` 가 `PENDING → RETRYING` 으로 CAS 클레임한다.
  - 이후 `RETRYING → PENDING` 복귀는 **오직** `BatchRetryQuartzJob.execute` 가 정상적으로 예외를 받아 `enqueueIfRetryable` 을 다시 부를 때만 일어난다(`BatchRetryQuartzJob.java:70`).
  - 프로세스 kill / OOM / 노드 장애로 그 사이가 끊기면 행은 `RETRYING` 으로 **영구 고착**된다:
    - `pollReady` 는 `STTS_CD='PENDING'` 만 조회 → 다시 안 잡힘
    - `clearIfIdle`(`LsBatRtyWtngRepository.java:80`)은 `STTS_CD <> 'RETRYING'` 이라 **의도적으로 보존** → 수동 재처리로도 리셋되지 않음
    - **stale RETRYING 을 되살리는 sweeper/타임아웃 잡이 코드베이스에 없음** (`grep -rn "RETRYING" backend/src/main/java` → `BatchRetryQueue`/엔티티 상수와 무관한 `controlnotify` 폴백만 매칭)
  - 결과: 해당 rawSn 은 `FAILED` 로 남고 자동 재시도가 무음 중단된다(REVIEWER 수동 재처리 전까지). 이는 in-memory 큐를 DB 로 옮기며 없앴다던 "재시도 무음 유실" 이 다른 형태로 잔존하는 것.
- **재현/확인 경로**:
  ```sql
  -- 고아 판별 쿼리 (현재 로컬엔 RETRYING 행 없음 — 폴러 미등록이라 클레임 자체가 미발생)
  select bat_rty_sn, raw_sn, rty_nmtm, stts_cd, mdfcn_dt
    from ls_bat_rty_wtng
   where stts_cd = 'RETRYING' and mdfcn_dt < now() - interval '30 minutes';
  ```
  재현: 폴러 활성 환경에서 `pollReady` 클레임 직후 backend 컨테이너 `docker kill` → 재기동 후 해당 행이 `RETRYING` 유지되고 재시도 미발화.
- **영향**: CWE-459 (Incomplete Cleanup) / 가용성. 실패 영상이 자동 복구 대상에서 조용히 이탈.
- **수정 방향(제안)**: ① `MDFCN_DT` 기준 stale 임계(예: 배치 최대 실행시간 ×2)를 넘긴 `RETRYING` 행을 조건부 UPDATE 로 `PENDING` 복귀시키는 sweeper 를 재시도 잡 앞단에 추가(`UPDATE … SET STTS_CD='PENDING' WHERE STTS_CD='RETRYING' AND MDFCN_DT < :cutoff`, 원자 CAS). ② 또는 `Quartz` `requestRecovery(true)` 를 재시도 잡에 부여해 노드 사망 시 복구 발화(단, 클러스터링 활성 전제). ③ `EXHAUSTED`/장기 `RETRYING` 깊이를 Micrometer 게이지로 노출(현재 `ControlNotifyMetrics` 만 유사 게이지 보유 — 배치 재시도 큐는 미계측).

### [B-ISSUE-84] TC-DEID-072 — mock-server 의 `retrieve_progress.fileName` 계약이 실서버와 달라, 1차 회수 경로(`{stem}-mask{ext}`)가 로컬 E2E 로 전혀 검증되지 않음
- **심각도**: MEDIUM (검증 커버리지 갭 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 목업 경유 E2E 가 실서버 계약을 재현해, 과거 회귀(“`fileName` 을 결과 파일명으로 오해 → 완료를 `'F'` 로 오종결”, 커밋 74be3a8/PR#27)가 다시 발생하면 로컬에서 잡힌다.
- **현재 동작(이슈 내용)**:
  - **실서버 계약(2026-07-21 curl/ll 실측)**: `fileName` = **원본 입력파일 절대경로**(예 `/nas-.../raw/001.mp4`), 산출물 = `export_path/001-mask.mp4` (`-` 하이픈).
  - **mock 구현**: `mock-server/app/routers/deid.py:86` `"fileName": ds.name` — `ds.name` 은 `services/deid_sim.py:157-159 MASK_SUFFIX="_mask"` 로 만든 **산출물 basename** `{stem}_{yyyyMMddHHmm}_mask{ext}`. 즉 (a) 경로가 아니라 basename 이고 (b) 접미사가 `_mask`(언더스코어)다.
  - **실동작 결과**: `sanitizeFileName("clip-9101_202607250139_mask.mp4")` → 동일 → `toMaskName` 이 `-mask` 로 끝나지 않는다고 판단(`KpstDeidentService.java:458`) → 1차 경로 `clip-9101_202607250139_mask-mask.mp4` (**존재하지 않음**) → `isUsableDeidFile` false → **폴백 `scanSingleUsable` 로만 회수**. DB 실적재 경로가 이를 증명한다.
  - 따라서 로컬 파이프라인이 아무리 돌아도 **`toMaskName` 정상 경로는 한 번도 실행되지 않으며**, 폴백을 제거하거나 export 디렉터리에 파일이 2개가 되는 순간 완료 회수가 깨진다(그때만 발현).
  - 단위 테스트(`KpstDeidentServiceTest:896` 경로형_fileName…, `:314` toMaskName_…)는 실계약을 커버하므로 코드 자체는 정상.
- **재현/확인 경로**:
  ```bash
  grep -n "fileName" mock-server/app/routers/deid.py                 # :86 "fileName": ds.name
  grep -n "MASK_SUFFIX" mock-server/app/services/deid_sim.py         # "_mask"
  grep -n "MASK_SUFFIX" backend/src/main/java/kr/co/cudo/authoring/batch/service/KpstDeidentService.java  # "-mask"
  docker exec klid-backend ls /app/storage/deidentified/videos/23/   # clip-9101_202607250139_mask.mp4
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select de_idntf_file_path_nm from ls_deident_proc_log where proc_log_sn=23;"
  ```
- **영향**: 회귀 감시 공백. "목업 경유 실동작 PASS" 가 실서버 정상 동작을 보증하지 못하는 구간이 생긴다(거짓 PASS 위험).
- **수정 방향(제안)**: mock `retrieve_progress` 의 `fileName` 을 **원본 입력 절대경로**(`input_path + 원본 basename`)로 바꾸고 산출물 파일명을 `{stem}-mask{ext}` 로 통일해 실계약을 재현. 타임스탬프 부착이 mock 고유 요구라면 별도 토글(`KPST_MOCK_FILENAME_CONTRACT=real|legacy`)로 두 계약을 모두 돌릴 수 있게 한다. 대안으로 폴백 회수 시 WARN 로그를 남겨 "1차 경로 miss" 를 운영에서 관측 가능하게 한다.

### [B-ISSUE-85] TC-BATCH-159/160 (참고) — local 프로파일에서 재시도 폴러가 미등록이라 적재된 재시도 항목이 발화되지 않음
- **심각도**: LOW (환경 구성 — dev/stg/prd 무영향)
- **기대 동작(기대효과)**: 검증 환경에서 재시도 큐의 등록→발화 전 사이클을 관측할 수 있다.
- **현재 동작(이슈 내용)**: `BatchRetryTriggerConfig.java:19` 가 `authoring.batch.enabled` 로 게이팅되는데 `application-local.yml:50` 이 `false`. 반면 배치 본체는 `MarkingBatchBridge → AsyncBatchRunner`(@Async) 로 구동되어 **실패 시 재시도 적재는 계속 일어난다**. 실측: `ls_bat_rty_wtng` 에 rawSn 15/16/23/24 4행이 `PENDING`, `rty_prnmnt_dt` 가 10:41~10:44 로 이미 도래했으나 검증 시각(10:49+)까지 발화 0건. 즉 로컬에서는 재시도 항목이 **적재만 되고 영구 대기**한다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select raw_sn,rty_nmtm,stts_cd,rty_prnmnt_dt from ls_bat_rty_wtng;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select job_name from qrtz_job_details;"  # batchRetryJob 없음
  ```
- **영향**: 검증 커버리지 한정(운영 영향 없음 — `application.yml:184 BATCH_ENABLED:true` 가 dev/stg/prd 기본).
- **수정 방향(제안)**: 재시도 폴러 토글을 배치 파이프라인 토글과 분리(`authoring.batch.retry.enabled`)하거나, 검증 회차에서 `BATCH_ENABLED=true` 로 임시 기동해 폴러 발화 구간을 실증한다.

---



### [DB-ISSUE-01] ls_data_raw 참조 무결성 미강제 — 자식 테이블 대부분에 FK 없음 (고아 행 발생 확인)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 영상 원본 행(`ls_data_raw`)이 사라지면 이를 참조하는 자식 데이터(마킹·프레임·비식별로그·작업배정·상태)도 함께 정리되거나, 최소한 **삭제가 차단**되어야 한다. 참조 무결성이 DB 레벨에서 강제되지 않으면 애플리케이션 버그·운영 실수·수동 조작이 곧바로 고아 데이터로 남고, 이후 조회·집계·export 가 조용히 틀린 결과를 낸다.
- **현재 동작(이슈 내용)**: `ls_data_raw` 를 참조하는 FK 제약이 **`ls_evnt_anno` 단 1건**(`fk_ls_evnt_anno_raw`, NO ACTION)뿐이다. `ls_marking`·`ls_data_src`·`ls_raw_data_status`·`ls_deident_proc_log`·`ls_task_assignment` 등 주요 자식 테이블엔 FK 가 없다.
  ```sql
  -- 실측(2026-07-25): ls_data_raw 를 참조하는 FK 전체
  ls_evnt_anno -> fk_ls_evnt_anno_raw (NO ACTION)
  -- 그 외 자식 테이블: FK 없음
  ```
  실제로 검증 중 rawSn 23/24/25 행이 사라진 뒤 `ls_marking` 에 해당 rawSn 참조 **2행이 고아로 잔존**함을 확인했다(FK 가 있었다면 삭제 자체가 차단됐을 상황).
- **재현/확인 경로**:
  ```sql
  -- FK 목록
  SELECT tc.table_name, tc.constraint_name, rc.delete_rule
    FROM information_schema.table_constraints tc
    JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name=ccu.constraint_name
    JOIN information_schema.referential_constraints rc ON tc.constraint_name=rc.constraint_name
   WHERE tc.constraint_type='FOREIGN KEY' AND ccu.table_name='ls_data_raw';
  -- 고아 확인
  SELECT count(*) FROM ls_marking WHERE raw_sn NOT IN (SELECT raw_sn FROM ls_data_raw);
  ```
- **영향**: 데이터 정합성. 고아 마킹/프레임/상태 행이 남으면 집계·데이터마트 View·export 가 실제와 다른 값을 낼 수 있고, 원인 추적이 어려워진다. 보안 결함은 아니나 **조용한 오류**를 만드는 유형이라 발견이 늦다.
- **수정 방향(제안)**: 자식 테이블에 `ls_data_raw(raw_sn)` 참조 FK 를 추가하고 삭제 정책(RESTRICT 또는 CASCADE)을 도메인 규칙에 맞게 확정한다. 기존 고아 행은 마이그레이션 전 정리 필요. ⚠ 운영 데이터가 이미 고아를 포함하면 FK 추가가 실패하므로 백필·정리 스크립트가 선행돼야 한다.

**참고 — 검증 중 rawSn 23/24/25 소실 건 (결함 아님으로 판정)**: 세 행은 B-part1 검증이 `/v1/dev/batch/scan` 으로 **직접 생성한 테스트 산출물**이며(23 은 원본 mp4 부재로 FRAME_EXTRACT 에서 이미 실패), 검증 세션이 정리한 것으로 보인다. **핵심 참조 시나리오 `rawSn=26` 은 온전**하다(raw 1 / frames 16 / labels 131 / export SUCCEEDED 실측). 제품 결함이 아니라 검증 환경 정리 흔적으로 기록한다. 다만 위 FK 부재는 별건의 실제 결함이다.

## C. 마킹 / 라벨링

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-01] TC-MARK-02(연장) — MANUAL marks 항목에 콘텐츠 검증 부재(중복 시점·영상길이 초과·음수 frameIndex 전부 통과)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: MANUAL 모드의 각 mark는 최소한 `frameIndex ≥ 0`, 중복 시점 배제, (가능하면) 영상 실제 프레임 수 이내로 검증돼야 한다. 그래야 이후 프레임 추출·라벨링 단계가 존재하지 않는 프레임을 참조하는 사고를 막는다.
- **현재 동작(이슈 내용)**: `MarkItem.java`(`backend/src/main/java/kr/co/cudo/authoring/marking/dto/MarkItem.java`)는 `frameIndex`에 `@NotNull`만 걸려 있고 하한(`@Min`)·상한·timestamp 형식 검증이 전혀 없다. `MarkingService.create`(MANUAL 분기, `:169-173`)도 `marks.isEmpty()`만 검사하고 개별 항목 유효성·중복은 전혀 보지 않는다.
  - 실측: rawSn=28(MARKING_READY, de_ident_yn=Y)에 REVIEWER가 `{"mode":"MANUAL","marks":[{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":999999999,"timestamp":"99:99"},{"frameIndex":-5,"timestamp":"-1:00"}]}` 요청 → **201** 그대로 성공, `ls_marking.mark_cn`(marking_sn=32)에 4개 항목(중복 0, 초과값 999999999, 음수 -5, 형식파괴 timestamp "99:99"/"-1:00") 그대로 영속.
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:18081/api/v1/videos/28/markings \
    -H "Authorization: Bearer $REVIEWER_TOKEN" -H "Content-Type: application/json" \
    -d '{"mode":"MANUAL","marks":[{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":999999999,"timestamp":"99:99"},{"frameIndex":-5,"timestamp":"-1:00"}]}'
  # → 201, marking_sn=32 (실측 완료, rawSn=28)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select mark_cn from ls_marking where marking_sn=32;"
  ```
- **영향**: 음수/과대 frameIndex 마크가 저장되면 후속 프레임 추출(FfmpegFrameExtractor)이나 라벨링 화면이 존재하지 않는 프레임을 참조해 예외를 던지거나(방어적이면) 조용히 스킵할 가능성. AUTO 모드는 이미 상한 미검증(B-part2 B-ISSUE-23, LOW)이 보고돼 있으나, MANUAL은 하한·중복까지 전무해 더 넓은 표면이다. TC-MARK-02/07 케이스 표에는 이 경계값이 명시돼 있지 않아 사각지대였다.
- **수정 방향(제안)**: `MarkItem`에 `@Min(0)` 추가, 서비스단에서 `frameIndex` 중복 제거/거부 및 (durationSec·fps로 계산한) 상한 초과 시 400 또는 클램프. (구현 금지 — 제안만)


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-21] TC-LABEL-36 — 라벨 full-replace 동시 저장 lost update (stale 작업본이 타인 라벨을 침묵 삭제)
- **심각도**: HIGH (데이터 소실 · 무경고)
- **기대 동작(기대효과)**: 두 사용자(또는 두 브라우저 탭)가 같은 프레임을 편집할 때, 나중 저장이 앞선 저장의 라벨을 소리 없이 지우면 안 된다. 최소한 충돌 감지(409/낙관적 락) 또는 병합 정책이 있어야 한다.
- **현재 동작**: `LabelService.bulkUpsert` 는 `existing = labelRepository.findBySrcSn(srcSn)`(:265) 를 읽고 요청의 non-null id 집합(`reqIds`)에 **없는 모든 기존 라벨을 물리 삭제**한다(:334-352). `@Version`·비관적 락·ETag 어느 것도 없고, 코드 주석(:331-333)이 방어를 "프레임은 단일 WORKER 배정이라 구조적으로 제한"이라는 **가정**에 의존한다고 명시한다.
  실측 로그(2026-07-25 11:05:54, srcSn=1):
  ```
  11:05:54.770 [Label] bulkUpsert srcSn=1 actor=2001 existing=0 saved=1 deleted=0   ← A 가 라벨 X 저장
  11:05:54.796 [Label] bulkUpsert srcSn=1 actor=2001 existing=1 saved=1 deleted=1   ← B(stale 작업본) 저장 → X 삭제
  ```
  B 는 A 의 저장을 모르는 상태(빈 작업본)에서 자기 라벨만 보냈고, 응답은 **200 정상**이었으며 A 의 라벨은 사라졌다. 사용자에게 어떤 경고도 없다.
  **가정이 깨지는 실제 경로(확인됨)**: ①`LabelAccessGuard` 는 REVIEWER 를 무조건 통과시키므로(LabelAccessGuard.java:45-47) REVIEWER 와 배정 WORKER 가 **동시에 같은 프레임을 편집 가능** — 실제로 REVIEWER 토큰과 WORKER 토큰으로 같은 srcSn 을 동시 PUT 해 둘 다 200 을 받았다. ②동일 WORKER 의 다중 탭. ③재배정 직후 이전 담당자의 열린 화면.
- **재현/확인 경로**:
  ```bash
  B=http://localhost:18081/api; WK=<WORKER 토큰>
  # 1) A 탭이 라벨 X 저장
  curl -X PUT $B/v1/frames/1/labels -H "Authorization: Bearer $WK" -H 'Content-Type: application/json' \
    -d '{"items":[{"id":null,"lblTypeCd":"BBOX","labelId":1,"label":"tabA-X","points":[[1,1],[11,11]]}]}'
  # 2) B 탭(A 저장 전에 화면을 열어둔 stale 작업본)이 자기 라벨만 저장
  curl -X PUT $B/v1/frames/1/labels -H "Authorization: Bearer $WK" -H 'Content-Type: application/json' \
    -d '{"items":[{"id":null,"lblTypeCd":"BBOX","labelId":2,"label":"tabB-Y","points":[[50,50],[60,60]]}]}'
  # 3) 확인 — tabA-X 는 사라져 있음
  docker exec -e PGPASSWORD=<pw> klid-postgres psql -U klid_user -d klid_system \
    -c "select lbl_sn,lbl_nm from ls_data_lbl where src_sn=1;"
  docker logs klid-backend --since 5m | grep '\[Label\] bulkUpsert'   # deleted=1 로 소실 확인
  ```
- **영향**: CWE-362(Race Condition) / lost update. 라벨 작업 결과가 통보 없이 사라진다. 삭제 자체는 `LS_DATA_LBL_HSTRY` 에 before 스냅샷으로 남으므로(:339-345) **사후 복구 근거는 존재**하나, 사용자·UI 는 소실을 인지하지 못한다. 검수 승인(APPROVED) 전 단계에서 발생하면 `LS_LABEL_VERSION` 스냅샷도 아직 없어 안전망이 이력뿐이다.
- **수정 방향(제안)**: (a) `LS_DATA_SRC` 또는 프레임 라벨 세트에 낙관적 버전(요청에 `baseVersion`/ETag 동반, 불일치 시 409)을 도입, 또는 (b) 프레임 단위 편집 세션 락(기존 `ls_auth_work_lock` 의 `DATA_SRC_SN` 컬럼이 이미 존재하나 미사용), 또는 (c) 최소 조치로 `deleted>0` 이면서 요청이 기존 id 를 하나도 포함하지 않는 저장을 경고·확인 요구로 승격. **UNCERTAINTIES #9 는 "기대 동작 미확정" 이므로 정책 확정이 선행되어야 함 — 본 보고서는 사실만 기록.**

### [C-ISSUE-22] 좌표 이미지 경계 초과 저장 무검증 (UNCERTAINTIES #10)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프레임 이미지 해상도를 벗어난 좌표(예: 640×480 프레임에 x=999999)는 학습데이터로 무의미하므로 저장 시점에 거부되거나 클램프되어야 한다.
- **현재 동작**: `validatePoints`(LabelService.java:575-599)는 **빈 배열·[x,y] 형식·음수·개수 상한**만 검증하고 **상한 좌표(이미지 폭/높이) 검증이 전혀 없다**. 실측:
  ```
  PUT /v1/frames/1/labels {"points":[[999999,888888],[1000000,999000]]}
  → HTTP 200, DB point_cn = [[999999.0,888888.0],[1000000.0,999000.0]] 그대로 저장
  ```
  `LabelItemDto` javadoc(LabelItemDto.java:19)은 "좌표 검증은 Service 에서 — 음수/**이미지 경계 초과** 차단"이라고 적혀 있어 **주석과 구현이 불일치**한다.
- **재현/확인 경로**: 위 PUT 그대로. (본 시험에서 저장한 초과 좌표 라벨은 후속 정리 단계에서 삭제 완료 — 잔존 없음)
- **영향**: CWE-20(입력 검증). 캔버스 클램프가 유일한 방어선이라 API 직접 호출·FE 버그·오토라벨 좌표 변환 오류가 그대로 학습데이터·export JSON(COCO bbox)에 유입된다. 보안 침해보다는 **데이터 품질 오염**이 주 리스크.
- **수정 방향(제안)**: `LS_DATA_SRC` 에 프레임 해상도가 있으면 그 값으로 상한 검증, 없으면 영상 해상도(`LS_DATA_RAW`) 기준 검증. 정책이 "클램프 허용"이면 최소한 위 javadoc 문구를 실제 구현에 맞게 정정. **UNCERTAINTIES #10 미확정이므로 사실만 기록.**

### [C-ISSUE-23] TC-LABEL-30 — R7 손상 좌표 JSON fail-safe 전용 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: `pointCn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 '변경됨'으로 판정해 이력이 유실되지 않아야 하고, 그 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작**: 구현(LabelService.java:479-483)은 `normalizePoints` 가 3포맷 + raw 폴백 모두 실패하면 `null` → `false`(변경됨) 반환으로 fail-safe 가 **정상 구현**되어 있다. 그러나 `backend/src/test/**` 전수 grep 결과 `pointCn` 손상 케이스를 직접 겨냥한 테스트가 없다. 유사 이름의 `LabelServiceFullReplaceIntegrationTest:277 corruptDiffJsonGracefulFallback` 은 **이력 diff JSON(`CHG_DTL_CN`) 손상** 케이스로 대상이 다르다.
- **재현/확인 경로**: `grep -rn "pointsEqual\|normalizePoints" backend/src/test` → 0건.
- **영향**: 회귀 위험만. 현재 동작 결함 아님.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `pointCn` 을 손상값(`"{{{"`)으로 덮어쓴 뒤 무변경 재저장 시 UPDATED 이력이 남는지 확인하는 케이스 1건 추가.

### [C-ISSUE-24] TC-LABEL-31 — TASK_MODIFIED 발행 런타임 관측 불가 (로컬 환경 제약)
- **심각도**: LOW (환경 제약 — 코드 결함 아님)
- **기대 동작(기대효과)**: APPROVED 영상의 라벨을 수정하면 `TaskModifiedEvent(LABEL_UPDATED)` 가 발행되고 디바운스를 거쳐 관제 outbound 통지로 이어져야 한다.
- **현재 동작**: 실동작으로 확인한 것은 **APPROVED 영상 라벨 편집이 차단되지 않고 200 으로 허용된다**는 사실까지다(rawSn=13/srcSn=359). 발행 여부는 `klid-backend` 컨테이너 env `CONTROL_NOTIFY_ENABLED=false` 이고 `ControlNotifyEventListener` 가 `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")`(ControlNotifyEventListener.java:21) 라 **리스너 빈 자체가 생성되지 않아** 로그·DB 어디에도 흔적이 남지 않는다. 발행 지점(LabelService.java:365-371)과 게이트(`isReviewApproved`)는 정적으로 정상이며, `LabelServiceTaskModifiedGuardTest:143 검수완료_발행` 이 rawSn/srcSn/changeType 을 단위 수준에서 검증한다.
- **재현/확인 경로**: `docker exec klid-backend env | grep CONTROL_NOTIFY_ENABLED` → `false`.
- **영향**: 검증 커버리지 공백. self-fill 결함 아님(값을 만들어낸 것이 아니라 기능이 설정으로 비활성).
- **수정 방향(제안)**: `CONTROL_NOTIFY_ENABLED=true` + 관제 inbound 를 mock-server 로 스텁한 별도 프로파일에서 재검증. (본 임무는 코드·설정 수정 금지라 미수행)

### [C-ISSUE-25] 사용 중 마스터 라벨을 soft delete 하면 해당 프레임의 라벨 저장이 전면 차단됨
- **심각도**: MEDIUM (참조 무결성 · 작업 차단 — 반증 시도 중 발견, 원 TC 목록 외)
- **기대 동작(기대효과)**: 마스터 라벨 soft delete 는 "신규 사용 중지"를 뜻해야 하며, 이미 그 라벨을 참조하는 기존 프레임의 편집·저장까지 막아서는 안 된다(막는다면 최소한 명시적 정책이어야 한다).
- **현재 동작**: `validateAndLoadLabels`(LabelService.java:541-564)는 요청 items 의 **모든** `labelId` 에 대해 `USE_YN='N'` 이면 409 를 던진다. 저장 계약이 **full-replace(프레임 전체 세트 전송)** 이므로, 프레임에 soft-delete 된 마스터를 참조하는 라벨이 1건이라도 있으면 그 프레임의 **모든 저장이 409 로 막힌다** — 그 라벨을 지우지 않는 한 다른 라벨 수정도 불가능하다. 실 데이터에 해당 상황이 이미 존재한다:
  ```
  ls_label:    lbl_id=248 'pose-skeleton'  use_yn='N'   (soft delete 됨)
  ls_data_lbl: lbl_sn=29  src_sn=11  lbl_type_cd=SKELETON  lbl_id=248  (참조 잔존)
  ```
  `USE_YN='N'` 라벨이 409 를 유발한다는 것은 TC-LABEL-21 에서 `labelId=7`(animal, `use_yn='N'`)로 **실동작 확증**했다.
- **재현/확인 경로**: (파괴 위험이 있어 srcSn=11 실저장은 **수행하지 않음** — 성공 시 rawSn=11 의 라벨 54건이 full-replace 로 손상될 수 있어 의도적으로 회피)
  ```sql
  -- 상황 확인 (읽기 전용)
  select l.lbl_sn, l.src_sn, l.lbl_id, m.lbl_nm, m.use_yn
    from ls_data_lbl l join ls_label m on m.lbl_id = l.lbl_id
   where m.use_yn = 'N';
  ```
  기전 확증(무해): `PUT /v1/frames/1/labels` 에 `labelId:7` 포함 → 409 "사용 중지된 라벨입니다: labelId=7".
- **영향**: 운영자가 사용 중인 라벨을 정리(soft delete)하는 순간 해당 라벨이 붙은 모든 프레임의 라벨링 작업이 잠긴다. 작업자는 원인을 알 수 없는 409 를 만난다.
- **수정 방향(제안)**: `USE_YN='N'` 검증을 **신규 부여(id==null)에만** 적용하고, 기존 라벨의 labelId 유지(변경 없음)는 통과시키는 방향. 또는 마스터 삭제 시 참조 건수를 사전 안내하고 대체 라벨 지정을 요구.


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-41] TC-LABEL-60 / TC-LABEL-70 — 실모델 YOLO 가 반환하는 경계 음수 좌표 때문에 온라인 오토라벨이 프레임 대부분에서 400 으로 전량 거부됨 (배치 경로와 검증 정책 불일치)
- **심각도**: HIGH (핵심 기능 가용성 — 라벨링 화면 'AI 탐지' 버튼이 실데이터에서 80% 실패)
- **기대 동작(기대효과)**: 라벨링 화면에서 AI 탐지를 누르면 프레임의 검출 좌표가 반환되어 캔버스 작업본에 반영된다. 화면 경계에 걸친 객체(사람이 프레임 왼쪽 끝에 반쯤 걸림 등)는 CCTV 학습데이터에서 정상적인 다수 케이스이며, 배치 오토라벨 경로는 이를 정상 저장한다.
- **현재 동작(이슈 내용)**: 온라인 경로는 ai 응답 좌표를 **all-or-nothing** 으로 검증하며 **음수를 곧바로 전체 거부**한다.
  - `backend/.../label/service/AutolabelOnlineService.java:509-512`
    ```java
    if (v < 0) {
        throw new CustomException(ErrorCode.INVALID_INPUT,
                "YOLO 응답 좌표는 0 이상이어야 합니다.");
    }
    ```
    `:230-233` 에서 검출 전체를 순회하며 하나라도 위반이면 400 → **정상 검출분까지 전부 폐기**(부분 반환 금지가 설계 의도).
  - **실측(로컬 실모델 ai-server, rawSn=26)**: 5개 프레임 중 **4개(src 446/448/449/450)가 400**, 성공은 src 447 하나뿐.
  - **동일 모델·동일 프레임을 배치 경로는 그대로 저장**한다 — `LS_DATA_LBL` 에 음수 좌표 라벨이 실존:
    ```
    lbl_sn=205 src=446 bus    [[-1.5731448368773044, 2.556953126603844], [1261.30…, 707.91…]]
    lbl_sn=213 src=448 bus    [[11.71…, -2.573257235451649], [1241.05…, 611.50…]]
    lbl_sn=215 src=449 person [[1032.46…, -0.9496699098489216], [1279.68…, 719.26…]]
    ```
    rawSn 별 음수 좌표 라벨 수: 26→15건, 28→4건, 27→2건, 14~19→각 1건.
  - `backend/.../batch/step/YoloLabelPersister.java` 에는 음수·유한성 검증이 **없다**(길이 홀수만 상위 스텝에서 검증 — `YoloAutolabelStepTest:195`). 즉 **같은 외부 응답을 배치는 신뢰하고 온라인만 거부**하는 정책 분기가 존재한다.
- **재현/확인 경로**:
  ```bash
  RT=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"role":"WORKER","channel":"INTERNAL","userNo":"2001"}' -o /tmp/t.json; \
    grep -o '"token":"[^"]*"' /tmp/t.json | sed 's/"token":"//;s/"$//')
  for s in 446 447 448 449 450; do printf "$s "; curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST http://localhost:18081/api/v1/frames/$s/autolabel \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{}'; done
  # -> 446:400 447:200 448:400 449:400 450:400
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT s.raw_sn, count(*) FROM public.ls_data_lbl l JOIN public.ls_data_src s ON s.src_sn=l.src_sn
     WHERE l.point_cn ~ '-[0-9]' GROUP BY 1 ORDER BY 1;"   -- 배치가 저장한 음수 좌표 라벨
  ```
- **영향**: SFR-08 라벨링 편의(AI 탐지)의 실사용 불가에 가까운 가용성 저하. 사용자에게 노출되는 메시지가 `"YOLO 응답 좌표는 0 이상이어야 합니다."` 라는 내부 검증 문구여서 원인 파악도 어렵다. 보안 방향은 fail-closed(과다 거부)이므로 데이터 유출·오염 위험은 없으나, **배치 DB 에는 이미 음수 좌표가 적재되어 있어 "온라인만 엄격" 이라는 비대칭이 검증 목적을 달성하지 못한다**(CWE-20 과잉 적용 + 정책 일관성 결함).
- **수정 방향(제안)**: ① 이미지 경계 기준 **clamp(0 ≤ x ≤ imgWidth, 0 ≤ y ≤ imgHeight)** 후 반환 — 모델의 경계 초과는 정상 출력이며 캔버스도 클램프 전제(`UNCERTAINTIES #10`)다. ② clamp 를 택하지 않는다면 최소한 **음수는 부분 스킵(해당 검출만 제외)** 으로 완화하고 all-or-nothing 은 NaN/Infinity·좌표 개수 위반에만 적용. ③ 어느 쪽이든 **배치(`YoloLabelPersister`)와 온라인(`AutolabelOnlineService.validateBbox`)의 좌표 정규화 규칙을 단일 유틸로 통일**해 비대칭을 제거. ④ 상한(x2>imgWidth) 미검증도 함께 정리(현재 `1279.68`, `721.30` 등 초과값이 무검증 통과).

---

## 부수 관찰 (결함 아님 / 참고)

- **역할 검증이 토큰 클레임 단독이 아님**: `role=REVIEWER` 토큰이라도 `userNo=2001`(DB상 WORKER)이면 `/v1/manage/labels` POST 가 **403**. `userNo=1001` 이어야 201. A 클러스터에서 지적된 "권한 자가부여" 유형의 우회가 이 구간에는 **없다**는 반증 근거.
- **`AutolabelResponse.savedCount`**: 미저장 경로인데 필드명이 `savedCount` 이고 `detectedCount` 와 동일값으로 노출된다(의도된 하위호환 — `AutolabelOnlineServiceTest:359`). FE 가 "저장됨"으로 오해할 여지가 있으나 계약상 명시됨.
- **`GET /v1/deident-reports?status=resolved`(소문자) → 400**: 서비스(`normalizeStatus:265`)는 `toUpperCase()` 로 관용하지만 컨트롤러 `@Pattern` 이 먼저 대문자만 허용해 거부한다. 방어 방향(엄격)이라 무해하나 서비스 javadoc 과 실동작이 다르다.
- **`X-HTTP-Method-Override` 미지원**: Spring Boot 3.x 기본값으로 `HiddenHttpMethodFilter`/override 필터가 비활성 — 메서드 우회 표면 없음.
- **PORTAL_USER 가 라벨 마스터·detect-candidates 조회 가능**: 설계상 허용(`LabelMasterController` javadoc). 응답에 `dtctTypeCd`(COCO 내부 매핑)가 포함되나 비활성 라벨은 제외되며 민감정보 아님.
- **`autolabel.polygon.max-boxes=20` vs 실데이터 최대 검출 19건/프레임**: TC-72 상한 분기가 운영 데이터에서 사실상 미발화. 상한이 유효한지(너무 높은지) 운영 판단 참고.

## 근거 라인 드리프트

**0건.** 본 구간 근거 `file:line` 45개 참조를 HEAD 소스와 전수 대조 — `LabelMasterController.java`, `LabelMasterService.java`, `LabelMasterRequest.java`, `LsLabelRepository.java`, `AutolabelOnlineService.java`, `AutolabelRequest.java`, `DeidentReportService.java`, `DeidentReportRequest.java` 전부 일치.
(참고: TC-LABEL-41 의 `:75-78` 은 실제 중복검사 라인이 `:76-78`, TC-LABEL-55 의 `:80-84` 는 javadoc 80-83 + 메서드 84 — 둘 다 범위 내라 드리프트로 계수하지 않음.)


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

전건 PASS — FAIL/PARTIAL/확인필요 항목 없음. 아래는 반증 시도 중 발견한 참고 관찰사항(카탈로그 결함 아님, 테이블 판정에 영향 없음).

### [C-ISSUE-61] (참고) SAM2 세그먼트 프롬프트 입력좌표 이미지 경계 사전검증 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: 반증 포인트 "프롬프트 좌표가 이미지 밖"에 대한 서버측 방어 확인 목적
- **현재 동작(관찰)**: `Sam2SegmentRequest.points/box` 및 `Sam2TrackRequest.prevPolygon` 은 요청 시점에 이미지 실측 width/height 상한 검증이 없다(오직 **응답** 폴리곤만 `Sam2SegmentService.validatePolygon`(160-172행)으로 이미지 경계 검증됨). 실동작으로 `points:[[99999,99999]]`(rawSn=26 프레임 실측 폭 약 1280px 대비 크게 초과)을 srcSn=446 에 요청한 결과 200 OK + 정상 형태의 폴리곤(score 0.9466)이 반환됨 — ai-server/모델이 내부적으로 좌표를 관대하게 처리한 것으로 보이나, BE 계약상 사전 차단은 없음
- **재현/확인 경로**: `curl -X POST http://127.0.0.1:18081/api/v1/frames/446/sam2-segment -H "Authorization: Bearer $TOKEN" -d '{"srcSn":446,"points":[[99999,99999]]}'`
- **영향**: 보안 취약점 아님(응답은 여전히 사후 검증됨, path traversal/injection 경로 없음). 잘못된 프롬프트 좌표를 외부 ai-server 로 그대로 전달하는 낭비성 호출/예측불가 결과 가능성 정도의 견고성 갭
- **수정 방향(제안)**: Sam2SegmentService 진입 시 이미지 실측 해상도 확보 이후(이미 100행에서 확보) points/box 좌표도 0≤x≤imgWidth, 0≤y≤imgHeight 로 사전 400 검증 추가 검토(구현 금지 — 제안만)


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-81] TC-TRACK-13/16/18/19/20/21/22 — MaskRleConverter·CoordinateTransformer 경계/보안 가드 단위테스트 커버리지 0%
- **심각도**: MEDIUM (TC-TRACK-20은 CWE-770 DoS 방어 P1 케이스라 회귀방지 공백은 실질적 리스크)
- **기대 동작(기대효과)**: 순수 알고리즘 클래스의 모든 가드절(특히 P1/security 우선순위 케이스)은 단위테스트로 회귀 방지되어야 한다.
- **현재 동작(이슈 내용)**: 코드 자체는 전부 정확함(수치 검산으로 확인 완료 — 예: TC-TRACK-21 `rleToMask([10,10], w=5,h=3)`→total=15, 20>15이므로 idx=15 도달 시점에 IAE 발생함을 직접 계산해 확인). 그러나 `MaskRleConverterTest.java`에는 `rleToMask` 계열 예외 가드(width/height≤0, null, 길이합 초과, DoS 상한)에 대한 테스트가 **하나도 없음**(maskToRle 쪽 DoS만 테스트됨 — 비대칭). `CoordinateTransformerTest.java`도 `rotate(null, ...)`/`rotate(points, angle, null)` null 가드 테스트가 없음.
- **재현/확인 경로**: `grep -c "rleToMask" backend/src/test/java/kr/co/cudo/authoring/common/util/MaskRleConverterTest.java` (roundtrip 호출 외 예외 케이스 assert 없음 확인 가능)
- **영향**: 향후 리팩터링 시 이 가드들이 조용히 깨져도(예: 상한 체크 순서 변경, null 체크 누락) CI가 잡아내지 못함. 기능 결함은 아님(현재는 정상).
- **수정 방향(제안)**: `rleToMask` 4종 예외 케이스(null/width≤0/height≤0/DoS상한/길이합초과) + `rotate` null 가드 2종에 대한 단위테스트 추가.

### [C-ISSUE-82] batch/interpolation/TrackInterpolator.java 와 동명이인 common/util/TrackInterpolator.java — 미사용 dead code 잔존
- **심각도**: LOW (기능 영향 없음 — 순수 housekeeping)
- **기대 동작(기대효과)**: 동일 클래스명이 프로덕션 경로 밖에 중복 존재하면 향후 유지보수자가 잘못된 파일을 참조/수정할 위험이 있어, 사용하지 않는 초안은 정리되어야 한다.
- **현재 동작(이슈 내용)**: `kr.co.cudo.authoring.common.util.TrackInterpolator`(POLYGON은 `UnsupportedOperationException`, 회전보간 `interpolateRotation` 포함)는 어떤 프로덕션 코드에서도 import 되지 않음(`grep -rn "common.util.TrackInterpolator"` 결과 0건). 실제 사용처는 `TrackInterpolationStep`이 참조하는 `kr.co.cudo.authoring.batch.interpolation.TrackInterpolator`(POLYGON 완전 지원)뿐. 본 문서(C-5)의 file:line 근거는 후자와 정확히 일치하므로 검증 자체엔 영향 없음.
- **재현/확인 경로**: `grep -rn "import kr.co.cudo.authoring.common.util.TrackInterpolator" backend/src/main/java` (결과 없음)
- **영향**: 없음(dead code). 라벨 도메인에 회전(rotation) 속성이 아예 존재하지 않아(`grep -rln rotation kr/co/cudo/authoring/label` 0건), dead 클래스의 회전보간 기능이 숨은 요구사항 갭도 아님.
- **수정 방향(제안)**: `common/util/TrackInterpolator.java` + 대응 테스트 삭제, 또는 명확히 "미사용 초안" 주석 강화.



## D. 검수 / 버전관리 / 관제통지

## 이슈 상세

### [D-ISSUE-01] TC-ASSIGN-001 — `POST /v1/assignments` 가 APPROVED(검수완료) 영상을 무검증으로 ASSIGNED 로 강등
- **심각도**: HIGH
- **기대 동작(기대효과)**: 검수 승인(`APPROVED`)은 작업 종결 상태다. CLAUDE.md 상 승인 시점에 라벨 버전 스냅샷이 확정되고 `TASK_COMPLETED` 가 관제서버로 push 되며, 데이터마트 View 4종이 `DATA_STTS_CD='APPROVED'` 게이트로 노출된다. 따라서 APPROVED 영상의 상태를 되돌리는 경로는 **반드시 `ReviewStateMachine.verify` 를 통과**해야 하고, APPROVED 출발은 `PENDING`(WORKER 재제출) 한 곳만 허용돼야 한다. 재배정(`reassign`)에는 이미 이 가드가 있다(`AssignmentService.java:146-153`).
- **현재 동작(이슈 내용)**: 신규 배정 경로에는 동일 가드가 없다. `AssignmentService.java:70-81`
  ```java
  for (Long rawDataId : req.rawDataIds()) {
      LsTaskAssignment entity = authrtRepository.save(LsTaskAssignment.createLabeler(...));
      LsRawDataStatus stts = upsertDataStts(rawDataId);
      stts.markAssigned();          // ← 현재 상태 무관 무조건 ASSIGNED (LsRawDataStatus.java:78-81, 검증 없는 setter)
  ```
  실측: rawSn=4 를 APPROVED 로 만든 뒤 다른 작업자(2002)를 배정하니 **201 성공 + `ls_raw_data_status(4)` APPROVED→ASSIGNED(ver 15→16)**. 결과적으로 rawSn=4 는 `V_COMPLETED_VIDEO` 에서 사라졌다(조회 결과 5,6,13,14,17,19,26 만 노출 — 4 없음). `ls_label_version` 스냅샷과 `LS_DATASET_VIDEO_META` 동결 데이터는 남아 있어 **뷰/스냅샷 불일치** 상태가 된다. 이벤트 로그에도 "강등" 흔적은 남지 않고 ASSIGN 만 기록된다.
  → B 클러스터가 배치 경로(`BatchTransitionService.java:283-291`)에서 찾은 동일 유형(B-ISSUE-03)에 더해, **검수/배정 API 경로에 존재하는 세 번째 무검증 writer**다.
- **재현/확인 경로**:
  ```bash
  # 사전: rawSn=X 가 APPROVED (submit→start→approve)
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H "Content-Type: application/json" -d '{"workerId":2002,"rawDataIds":[X]}'   # → 201
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT raw_data_id,data_stts_cd FROM public.ls_raw_data_status WHERE raw_data_id=X;"  # → ASSIGNED
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT raw_sn FROM public.v_completed_video WHERE raw_sn=X;"              # → 0 rows
  ```
- **영향**: 데이터 정합성 — ①`TASK_COMPLETED` 통지 후 관제서버가 완료로 인지한 작업이 저작도구에서 조용히 미완료로 되돌아감(재통지 없음) ②데이터마트 4종 View 에서 검수완료 영상이 사라짐 ③`LS_LABEL_VERSION`(APPROVED 스냅샷)·`LS_DATASET_VIDEO_META`(동결)와 상태행 불일치. 보안 축으로는 CWE-840(비즈니스 로직 검증 누락). REVIEWER 권한만 있으면 UI의 일반 배정 조작으로 유발되므로 도달성이 높다.
- **수정 방향(제안)**: `assign()` 루프 안에서 `upsertDataStts` 직후 현재 `dataSttsCd` 가 `APPROVED`(및 검수 진행 중 `IN_REVIEW`/`PENDING`)인 경우 `ASSIGNMENT_ALREADY_COMPLETED`/`CONFLICT` 로 차단하거나, 최소한 `markAssigned()` 를 `ReviewStateMachine.verify(current, ASSIGNED)` 경유로 바꾼다. 근본 처방은 `LsRawDataStatus.transitionTo/markAssigned` 를 상태머신 경유 단일 진입점으로 좁혀 배치·검수·배정 3경로가 같은 규칙을 공유하게 하는 것(B-ISSUE-03 과 공통 수정).

### [D-ISSUE-02] TC-ASSIGN-014 — 동시 재배정이 전혀 직렬화되지 않아 배정 이력이 중복 적재됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 테스트케이스 기대값은 "사전 통과 후 경합 시 CONFLICT(409)". 재배정은 감사 대상 행위이므로 동시 요청 N건이 들어와도 실제 전이는 1회, `LS_TASK_ASSIGN_HISTORY`/`LS_TASK_EVENT_LOG` 도 1건이어야 한다.
- **현재 동작(이슈 내용)**: `LsTaskAssignment` 엔티티에 `@Version`/비관적 잠금이 없고(`LsTaskAssignment.java` 전체에 `@Version` 부재), 재배정은 기존 row 의 UPDATE 라 UK 위반이 발생하지 않는다. 따라서 `AssignmentService.java:183-189` 의 `DataIntegrityViolationException` → CONFLICT 방어가 발화하지 않는다. 동일 작업자 가드(`:158-160`)도 4스레드가 모두 커밋 전 값(prev=2002)을 읽어 통과한다.
  실측(assignment 29, 2002→1002, 4병렬 PATCH):
  ```
  1 200 / 2 200 / 3 200 / 4 200
  ls_task_assign_history: hstry 4,5,6,7 = (authrt 29, prev 2002, new 1002) 동일 4행
  ls_task_event_log:      evnt 63,64,65,66 = REASSIGN(prev 2002 → subject 1002) 동일 4행
  ```
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code}\n" -X PATCH \
    http://localhost:18081/api/v1/assignments/29 -H "Authorization: Bearer $REV" \
    -H "Content-Type: application/json" -d '{"workerId":1002}' & done; wait
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT * FROM public.ls_task_assign_history WHERE authrt_seq=29;"
  ```
- **영향**: CWE-362 (비즈니스 로직 Race Condition). 최종 배정 결과 자체는 동일해 데이터 손상은 없으나 **배정 이력이 실제보다 부풀려져 감사 추적이 왜곡**된다(SCR-TASK-003 작업 이력 화면·감리 대상 이력). 검수 워크플로우(`LS_RAW_DATA_STATUS`)는 `@Version` 으로 보호되지만 배정 테이블은 무방비라는 비대칭.
- **수정 방향(제안)**: `LsTaskAssignment` 에 `@Version` 추가(→ 경합 시 `OptimisticLockingFailureException` → CONFLICT 매핑) 또는 `reassign` 진입 시 `findById` 를 `PESSIMISTIC_WRITE` 로 잠그고 동일작업자 가드를 잠금 이후로 이동. 어느 쪽이든 이력 INSERT 는 전이 성공 1회에만 일어나야 한다.

### [D-ISSUE-03] TC-REVIEW-034 — `ReviewStateMachine.verify(null, …)` 가 400 이 아닌 NPE(500)
- **심각도**: LOW
- **기대 동작(기대효과)**: 테스트케이스는 `from=COMPLETED/PROCESSING/null` 모두 `INVALID_INPUT(400)` 을 기대한다. 상태머신은 알 수 없는 입력에 대해 fail-closed 로 400 을 던져야 하며 500(내부 오류)을 노출해선 안 된다(security.md — 예외 처리 시 내부 구현 노출 금지).
- **현재 동작(이슈 내용)**: `ReviewStateMachine.java:43-51` 의 `ALLOWED` 는 `Map.of(...)` (JDK `ImmutableCollections.MapN`) 이다. `:59` `ALLOWED.get(from)` 에서 `from=null` 이면 `MapN.probe(null)` 이 `pk.hashCode()` 를 호출해 **NullPointerException** 이 발생하며, `:60-63` 의 `allowed == null` 분기에 도달하지 못한다. COMPLETED/PROCESSING 은 맵 미등록 키라 정상적으로 `INVALID_INPUT` 을 던진다(이 두 값은 정상).
- **재현/확인 경로**: HTTP 로는 재현 불가 — `LS_RAW_DATA_STATUS.DATA_STTS_CD` 는 `nullable=false`(`LsRawDataStatus.java:37`)이고 `transitionTo` 호출자 전수(`AssignmentService:75`, `BatchTransitionService:243,289`, `ReviewService:348,375,398,411,468`)에서 null 을 넣는 경로가 없다. 단위 수준 확인: `new ReviewStateMachine().verify(null, "PENDING")`.
- **영향**: 현재 도달 불가(이론적). 향후 상태 컬럼이 nullable 로 바뀌거나 다른 소스(외부 연동/DTO)에서 상태를 주입하면 500 + 스택트레이스 경로가 열린다.
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null || to == null) throw new CustomException(INVALID_INPUT, ...)` 가드 추가. 겸해 `ReviewStateMachineTest` 에 COMPLETED/PROCESSING/null 3케이스를 추가(현재 전용 테스트 없음).

### [D-ISSUE-04] TC-REVIEW-011(부가) — 라벨 0건·프레임 이미지 부재 영상도 승인되어 데이터마트 View 에 빈 레코드로 노출
- **심각도**: MEDIUM (확인필요 — 정책 확인 대상)
- **기대 동작(기대효과)**: 검수 승인은 "학습데이터로 확정" 을 뜻하며, 승인된 영상은 `V_COMPLETED_*` 를 통해 관제/데이터마트로 흘러간다. 라벨이 0건이거나 프레임 이미지 실체가 없는 영상은 학습데이터가 될 수 없으므로 승인 단계에서 걸러지거나 최소한 경고돼야 한다.
- **현재 동작(이슈 내용)**: `ReviewService.approve`(`:406-447`)에 라벨/프레임 존재 검증이 없다. 실측 — rawSn=4(프레임 1건, 라벨 0건)를 승인하니 200 APPROVED, `VersionService` 로그 `frames=1 created=0 skipped=0`(스냅샷 0건), 이어지는 AFTER_COMMIT export 는 `[DatasetExport] nothing produced — marked FAILED rawSn=4 version=1` 로 실패했고 `ls_dataset_export` export_sn=6 `export_stts_cd=FAILED` 만 남았다. 그럼에도 승인은 그대로 확정된다. rawSn=5·6 도 같은 방식으로 `v_completed_video` 에 `export_path_nm`·`frame_cnt` 가 NULL 인 행으로 노출된다(기존 rawSn=13 도 동일 형태).
  더불어 export 실패는 승인 트랜잭션 밖(AFTER_COMMIT `DatasetExportBridge`)이라 롤백되지 않고 재시도 큐도 없다 — "승인은 성공했으나 산출물 없음" 상태가 영구화된다.
- **재현/확인 경로**:
  ```bash
  # 라벨 0건 영상 X 를 submit→start→approve
  curl -s -X POST http://localhost:18081/api/v1/reviews/X/approve -H "Authorization: Bearer $REV"   # 200
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT export_sn,export_stts_cd FROM public.ls_dataset_export WHERE data_raw_sn=X;"   # FAILED
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT raw_sn,export_path_nm,frame_cnt FROM public.v_completed_video WHERE raw_sn=X;" # 노출, 값 NULL
  ```
- **영향**: 데이터 품질 — 관제/데이터마트가 "검수완료" 로 픽업하지만 실제 학습데이터(export 폴더)가 없는 영상이 유입된다. 승인 부수효과 4종 중 ①스냅샷 ②상태 ③materialize 는 동일 트랜잭션으로 원자적이지만(테스트 `materialize_실패시_approve_예외_전파_동일트랜잭션_롤백` 으로 확인), ④export 와 `TASK_COMPLETED` 통지는 AFTER_COMMIT 이라 실패해도 승인이 유지된다. 통지는 실패 시 `ControlNotifyService.java:50-55` 가 dead-letter 큐(`enqueuePending`)로 회수하지만 **export 실패는 회수 경로가 없다**.
- **수정 방향(제안)**: ①승인 전 사전 조건(라벨 ≥1건 또는 프레임 이미지 존재) 검증 후 400/409 로 차단할지 정책 확정 ②차단하지 않는다면 export 실패에도 재시도/알림 경로(현재 `LS_BAT_RTY_WTNG` 유사)를 붙여 "승인됐으나 산출물 없음" 이 방치되지 않게 한다. 어느 쪽인지 사용자 확정 필요.

### [D-ISSUE-05] TC-ASSIGN-010(부가) — 검수 진행 중(IN_REVIEW) 영상의 재배정이 허용됨
- **심각도**: LOW (확인필요 — 의도된 정책일 수 있음)
- **기대 동작(기대효과)**: 재배정 가드의 목적이 "진행 중인 검수 흐름 보호"라면, 검수자가 이미 검수를 시작한(IN_REVIEW) 영상의 작업자 교체는 차단되거나 최소한 검수 상태를 되돌려야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:146-153` 의 가드는 `STTS_APPROVED` 한 값만 본다. 실측 — rawSn=10 이 `IN_REVIEW` 인 상태에서 `PATCH /v1/assignments/29 {workerId:2002}` → 200 성공, 상태는 `IN_REVIEW` 그대로 유지되고 기존 작업자(2001)는 배정을 잃는다. 결과적으로 "검수 중인데 배정 작업자만 바뀐" 상태가 만들어진다.
- **재현/확인 경로**: 위 rawSn=10 시퀀스(submit → start → PATCH /v1/assignments/29).
- **영향**: 상태 일관성/운영 혼선. 데이터 손상은 없음.
- **수정 방향(제안)**: 가드를 `APPROVED` 단일 값에서 검수 진행 상태 집합(`PENDING`/`IN_REVIEW`/`APPROVED`)으로 확장할지, 아니면 현행(라벨 작업자 교체는 검수 중에도 허용)이 의도인지 정책 확정.

### [D-ISSUE-06] TC-ASSIGN-004(부가) — 배정 대상의 역할 미검증 (REVIEWER 계정·자기 자신을 LABELER 로 배정 가능)
- **심각도**: LOW (확인필요)
- **기대 동작(기대효과)**: `TASK_TYPE_CD='LABELER'` 배정 대상은 WORKER 역할 계정이어야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:61-63` 은 `userRepository.findByUserNo` 로 **존재 여부만** 확인한다. 실측 — REVIEWER 계정(1001)이 자기 자신(1001)을 rawSn=6 의 LABELER 로 배정 → 201 성공(`ls_task_assignment` id=28, user_no=1001, LABELER). 근본 원인은 `MNG_ACCT_USER` 에 역할 컬럼 자체가 없다는 것(실제 스키마: user_no/user_id/user_nm/user_email/use_yn/reg_dt/upd_dt) — 역할은 JWT `role` 클레임에만 존재한다.
- **재현/확인 경로**: `POST /v1/assignments -d '{"workerId":1001,"rawDataIds":[6]}'` (REVIEWER 토큰) → 201.
- **영향**: 권한 상승은 없다 — `submit`/`cancelSubmit` 은 토큰의 `role == WORKER` 를 요구(`ReviewService.java:500-502`)하므로 REVIEWER 토큰으로는 여전히 제출할 수 없고, 배정 행이 "죽은 배정"으로 남을 뿐이다. 다만 배정 목록·통계에 비작업자가 섞인다.
- **수정 방향(제안)**: 역할 원천이 JWT뿐이면 DB 검증이 불가하므로, ①`MNG_ACCT_USER` 또는 별도 매핑에 역할 컬럼 확보 후 검증하거나 ②배정 UI가 노출하는 후보 목록을 서버가 제한하는 방식 중 선택. 정책 확정 필요.

### [D-ISSUE-07] TC-ASSIGN-021(부가) — 상태행 PK 충돌을 "동일 작업자 중복 배정" 으로 오안내
- **심각도**: LOW
- **기대 동작(기대효과)**: 에러 메시지는 실제 충돌 원인을 반영해야 한다(운영 진단성).
- **현재 동작(이슈 내용)**: 서로 **다른** 작업자(2001/2002)를 같은 rawSn=10 에 동시 배정했을 때 패자가 받는 응답은 `409 "이미 동일 작업자에게 배정된 영상이 있습니다."` 다. 실제 충돌은 `LS_RAW_DATA_STATUS` PK INSERT 경합(`AssignmentService.java:492-495`)이고, 작업자 중복은 아니다. `:83-86` 의 catch 가 두 원인을 한 메시지로 뭉갠다.
- **재현/확인 경로**: status 행이 없는 rawSn 에 서로 다른 workerId 로 `POST /v1/assignments` 2건 동시 실행.
- **영향**: 운영/지원 진단 혼선. 데이터 영향 없음(재시도하면 성공).
- **수정 방향(제안)**: `DataIntegrityViolationException` 의 제약명(UK vs PK)에 따라 메시지를 분기하거나, 상태행 upsert 를 별도 try-catch 로 분리해 "일시적 충돌 — 재시도 필요" 로 안내.

---

## 반증(적극적 실패 유도) 수행 내역 — 결함 미발견 항목

| 반증 시나리오 | 결과 |
|---|---|
| 두 REVIEWER(1001·1002) 동시 승인 9병렬 | **방어 성립** — 1건 200 / 8건 409 "다른 검수자가 먼저 처리했습니다", `ls_task_event_log` APPROVE 정확히 1건, 최종 APPROVED 1회 (낙관적 잠금 `LsRawDataStatus.java:52-55` 실효) |
| 미배정 REVIEWER(1002)의 검수 조작 | 인가 통과(현재 정책 — `ReviewControllerTest:REVIEWER는_본인_검수자_미배정_영상도_검수_가능_현재정책`). 상태 오류만 400 반환 → 의도된 광범위 허용, UNCERTAINTIES #4 취지와 일관 |
| PORTAL_USER 채널 격리 | assign/reviews 목록/상세/이슈 전부 403 |
| 이미 제출된(PENDING) 영상 재제출 | 400 "(PENDING → PENDING)" 차단 |
| APPROVED → PENDING 외 4방향(start/reject/approve/cancel) | 전부 409 CONFLICT |
| REJECTED 후 재승인 직행 | 400 "(REJECTED → APPROVED)" 차단 |
| WORKER 의 승인/검수시작/프레임목록/이슈목록/검수목록 | 전부 403 |
| self-fill 점검 | D-1~D-3 범위에서 외부 응답 없이 값을 자체 생성한 지점 **0건**. `TASK_COMPLETED` payload 의 0/null 하드코딩(`ControlNotifyService.java:97-100`)은 존재하나 D-5(TC-NOTIFY-003) 소관이며 UNCERTAINTIES #3 로 이미 확정된 기존 결함 |

## 환경 변경 기록 (다른 클러스터 참고용)

- **rawSn=26 무변경** — GET 조회 2회만. 최종 `ls_raw_data_status(26)=APPROVED` 유지 확인.
- 신규 데이터: `ls_task_assignment` id=23(raw4/2002), 24·25·27(raw5), 28(raw6), 29(raw10/최종 1002) / `ls_data_issue` sn=2,3(raw4) / `ls_evnt_anno` sn=3 + `ls_evnt_anno_review` rvw_sn=3(raw6) / `ls_dataset_export` sn=6(raw4, FAILED) / `ls_task_assign_history` hstry 2~7 / `ls_task_event_log` evnt 29~66.
- 상태 변경: rawSn=4 ASSIGNED(원상 복귀), 5·6 → APPROVED(신규), 10 → IN_REVIEW(신규). rawSn=8·9·13 은 조회성 시도만 하여 불변.
- 파일 수정 0건, 빌드/테스트 실행 0건.


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [D-ISSUE-21] TC-DIFF-008 — `SAVE_REASON_ROLLBACK` 분기가 프로덕션에서 도달 불가(dead branch) + 이를 검증하는 테스트 2건이 위양성
- **심각도**: HIGH
- **기대 동작(기대효과)**: 롤백 시 `LS_LABEL_VERSION` 에 `SAVE_REASON_CD='ROLLBACK'` 인 새 active 버전이 적층되어, "언제 누가 어느 버전으로 되돌렸는가"가 이력으로 남는다(TC 기대값 + `VersionController` javadoc "새 active 버전이 생성되며 LS_LABEL_VERSION에 기록").
- **현재 동작(이슈 내용)**: 롤백 결과 해시는 대상 스냅샷 payload 로부터 재계산된다.
  - `VersionService.java:376` `String newHash = sha256Hex(snapshot);` (snapshot = `target.getLabelPayload()`)
  - 정상 스냅샷은 `version_hash == sha256(lbl_payload)` 가 항상 성립한다(실측: srcSn=446 에서 `shasum -a 256` 결과가 저장 해시와 완전 일치).
  - 따라서 `:410-411` `findByDataSrcSnAndVersionHash(srcSn, newHash)` 는 **항상 대상 행 자신을 찾아** `:412-419` 재활성 경로로 분기하고, `:421-422` `saveActiveVersion(..., SAVE_REASON_ROLLBACK, ...)` 에는 도달하지 못한다.
  - **실측**: srcSn=421 롤백 2회 실행 후에도 `save_reason_cd='ROLLBACK'` 행 0건. DB 전체 집계도 `APPROVED 37 / DEIDENT_REPORT 1` 뿐.
  - 로그도 항상 `[Version] rollback reactivated existing …` 만 출력되고 `[Version] rolled back …`(:423) 은 미출력.
- **테스트 위양성**: `VersionServiceTest.java:336` `assignedWorkerRollbackRestoresSnapshot` / `:510` `reviewerRollbackCreatesNewVersion` 은 픽스처 해시를 `"feedface1234567890abcdef1234567890abcdef"`(40자, payload 의 SHA-256 아님)로 시드한다. 이 때만 `newHash != 저장 해시`가 되어 ROLLBACK 분기가 실행되고 `assertThat(rollback.getSaveReasonCd()).isEqualTo(SAVE_REASON_ROLLBACK)` 가 통과한다. **프로덕션에서 발생할 수 없는 상태를 픽스처로 만들어 통과시키는 구조**라, 두 테스트는 회귀 방어력이 없다. (반면 `:524` `rollbackToExistingHashReactivatesInsteadOfInsert` 는 실제 sha256 을 써서 프로덕션 동작과 일치한다.)
- **재현/확인 경로**:
  ```bash
  V1=$(docker exec klid-postgres psql -U klid_user -d klid_system -At \
    -c "SELECT version_hash FROM ls_label_version WHERE lbl_version_sn=16;")
  curl -s -X POST "http://localhost:18081/api/v1/versions/$V1/rollback" \
    -H "Authorization: Bearer $REV" -H "Content-Type: application/json" -d '{"srcSn":421}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT save_reason_cd, count(*) FROM ls_label_version GROUP BY 1;"   # ROLLBACK 0건
  # 해시 결정성(= 분기 도달 불가의 근거)
  docker exec klid-postgres psql -U klid_user -d klid_system -At \
    -c "SELECT lbl_payload FROM ls_label_version WHERE data_src_sn=446;" > /tmp/p.txt
  printf '%s' "$(cat /tmp/p.txt)" | shasum -a 256   # == version_hash
  ```
- **영향**: 롤백 이력이 별도 행으로 남지 않아 **"되돌리기 행위" 자체가 감사 추적에서 소실**된다. 재활성된 행의 `REG_ID`/`REG_DT` 는 최초 승인자·승인시각이므로, 누가 언제 롤백했는지 DB 만으로 복원할 수 없다(로그에만 존재). SFR-08 버전관리의 이력 요건과 문서(Controller javadoc·CLAUDE.md "롤백은 대상 검수완료 스냅샷을 새 active 버전으로 복원")에 대한 실질 미충족.
- **수정 방향(제안)**: ① 롤백을 항상 신규 행으로 적층하되 `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 와 충돌하지 않도록 버전 식별 축을 분리(예: 재활성 대신 `SAVE_REASON_CD='ROLLBACK'` + 별도 이력 테이블/컬럼에 롤백 actor·시각 기록), 또는 ② 재활성 경로에서도 롤백 수행자/시각을 갱신·별도 이력에 남기고, TC 기대값을 "기존행 재활성"으로 정정. 어느 쪽이든 위양성 테스트 2건은 실제 sha256 픽스처로 교정 필요.

### [D-ISSUE-22] TC-DIFF-008 — 롤백이 `LBL_SN` 을 재발급해 이후 diff 가 "전량 교체"로 오분류(round-trip 불안정)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 스냅샷 v1 → 수정 → v2 → v1 로 롤백 → 재승인하면, 라벨 내용이 v1 과 동일하므로 v1 대비 diff 는 **변경 0건**이어야 한다(또는 최소한 "전부 삭제+전부 추가"로 보이지 않아야 한다).
- **현재 동작(이슈 내용)**: `replaceFrameLabels`(`VersionService.java:478-504`)가 기존 라벨을 `deleteAll` 후 `LsDataLbl.createManual(...)` 로 재생성하므로 IDENTITY PK 가 새로 발급된다(주석 :325 "lbl_sn 재발급 허용"). diff 의 라벨 식별자는 `items[].id`(=`LBL_SN`, :668-669)이므로 동일 좌표라도 다른 객체로 인식된다.
  - **실측**: srcSn=421 라벨 `176,177,178,179` → 롤백 후 `392,393,394,395` → 멱등 롤백 후 `396,397,398,399` → 재롤백 후 `400,401,402,403`.
  - 롤백 후 재승인으로 만든 v3(`5c3a62fe…`)는 v1 과 **좌표 집합이 완전히 동일**(payload `points` 정렬 비교 결과 IDENTICAL)한데도 새 해시로 적층됐고, `diff(v1 → v3)` 는 **8건**을 반환했다: `REMOVED 176/177/178/179 + ADDED 392/393/394/395`.
- **재현/확인 경로**:
  ```bash
  # (이미 적층된 v1=16, v3=38 기준)
  V1=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c "SELECT version_hash FROM ls_label_version WHERE lbl_version_sn=16;")
  V3=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c "SELECT version_hash FROM ls_label_version WHERE lbl_version_sn=38;")
  curl -s "http://localhost:18081/api/v1/versions/$V3/diff?compareWith=$V1" -H "Authorization: Bearer $REV"
  # → 8건 (REMOVED×4 + ADDED×4), 좌표는 동일
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT jsonb_agg(e->'points' ORDER BY e->>'label') FROM ls_label_version, jsonb_array_elements((lbl_payload::jsonb)->'items') e WHERE lbl_version_sn IN (16);"
  # 38번과 비교 시 완전 동일
  ```
- **영향**: 데이터 손실은 아니나 **버전 비교 결과가 사실과 다르게 보고**된다. ① 검수자가 diff 화면에서 "라벨 전량 삭제 후 재작성"으로 오독, ② `V_COMPLETED_LABEL_CHANGE`/`LS_DATA_LBL_HSTRY` 기반 변경점이 롤백 1회로 프레임 전체 라벨 수만큼 부풀려져 관제서버로 전달될 수 있음(TASK_MODIFIED 변경 프레임 목록·요약 카운트 왜곡). ③ 롤백을 반복할수록 `LBL_SN` 이 무한 증가.
- **수정 방향(제안)**: 롤백 복원을 `LBL_SN` 보존형으로 전환(스냅샷 `items[].id` 를 그대로 사용해 삭제 대상만 delete / 존재분은 update / 없는 것만 insert). PK 를 명시 지정해야 하므로 IDENTITY 재사용 가능성 검토 필요. 대안으로 diff 식별 축을 `LBL_SN` 이 아닌 안정 키(예: trackId + 라벨명 + 순번)로 바꾸는 방법도 있으나, 라벨 이력 전반의 식별 축을 함께 바꿔야 해 영향 범위가 크다.

### [D-ISSUE-23] TC-DIFF-008 — 롤백이 AI 메타(`LS_DATA_LBL_AI_INFO`)와 `TRCK_ID` 를 복원하지 않고 고아 행을 남김
- **심각도**: HIGH
- **기대 동작(기대효과)**: 롤백은 해당 스냅샷 시점의 라벨 상태로 되돌리는 연산이므로, 오토라벨 출처(`LBL_SRC_CD` YOLO/SAM2/INTERPOLATED)·신뢰도(`CONF_SCORE`)·자동라벨 여부(`AUTO_LBL_YN`)·트랙 연속성(`TRCK_ID`)이 함께 복원되어야 한다. 최소한 삭제되는 라벨의 부수 데이터는 함께 정리되어야 한다.
- **현재 동작(이슈 내용)**:
  1. `replaceFrameLabels`(:495-496)는 `LsDataLbl.createManual(srcSn, lblTypeCd, labelId, label, pointsJson, null)` 만 호출한다. `createManual`(`LsDataLbl.java:226-239`)은 `autoLblYn(AUTO_NO)`, `confScore(null)` 을 **강제**하고 `trackId` 인자를 받지 않는다 → `TRCK_ID`(실제 컬럼)는 항상 NULL 이 된다.
  2. `autoLblYn/confScore/lblSrcCd` 는 `@Transient`(`LsDataLbl.java:88-114`)로, 실제 저장소는 별도 테이블 `LS_DATA_LBL_AI_INFO`(FK 없음, `DATA_LBL_SN` 로 참조)다. `VersionService` 는 이 테이블을 **전혀 참조하지 않는다**(`grep -c "AiInfo" VersionService.java` = **0**).
  3. **비대칭**: 같은 "라벨 삭제" 연산인 `LabelService.bulkUpsert` 는 `LabelService.java:350` 에서 `aiInfoRepository.deleteByDataLblSnIn(delSns);` 로 정리한다. 롤백 경로만 이 처리가 빠져 있다.
  4. 결과적으로 롤백 시 구 `LBL_SN` 의 AI 메타 행이 **고아로 잔존**하고, 복원된 라벨은 AI 메타가 없어 전부 수동 라벨로 보인다.
  5. **블라스트 반경**: `LS_DATA_LBL_AI_INFO` 는 rawSn=26 에 131행(라벨 131건과 1:1), rawSn=13/14/17/27/28 에도 존재. rawSn=26 의 어느 프레임이든 롤백하면 해당 프레임의 AI 메타가 전부 고아화된다.
  6. 동일 구조로 `LS_DATA_LBL_ATTR_VAL`(`ls_data_lbl` 에 **FK 제약 보유**: `fk_ls_data_lbl_attr_lbl`)도 미처리다. 현재 0행이라 미발현이지만, 속성값이 존재하는 프레임을 롤백하면 `deleteAll`(:482)이 **FK 위반으로 500** 이 되거나 속성값이 유실된다.
- **재현/확인 경로**:
  ```sql
  -- 롤백 대상 프레임의 AI 메타 존재 확인 (예: rawSn=26)
  SELECT data_src_sn, count(*) FROM ls_data_lbl_ai_info WHERE data_raw_sn=26 GROUP BY 1;
  -- 롤백 실행 후 고아 검출
  SELECT count(*) FROM ls_data_lbl_ai_info a
    LEFT JOIN ls_data_lbl l ON l.lbl_sn = a.data_lbl_sn WHERE l.lbl_sn IS NULL;
  -- trackId 유실 확인
  SELECT lbl_sn, trck_id FROM ls_data_lbl WHERE src_sn = <롤백한 srcSn>;
  ```
  (본 검증은 rawSn=26 보호를 위해 AI 메타가 0건인 srcSn=421 에서만 롤백을 실행했으므로 고아 행은 실제로 만들지 않았다. 위 3·5 항이 정적 근거다.)
- **영향**: **데이터 손실** — 조건: 오토라벨(YOLO/SAM2/보간) 산출 라벨이 있는 프레임을 롤백할 때. 손실 항목 = 라벨 출처·신뢰도·자동라벨 플래그·트랙 ID. 학습데이터 export(NIA/COCO)의 provenance 필드와 트랙 연속성이 훼손되고, 고아 행이 무한 누적된다. 속성값 보유 프레임에서는 FK 위반으로 롤백 자체가 500 이 될 수 있다(가용성).
- **수정 방향(제안)**: `replaceFrameLabels` 에 ① 삭제 시 `aiInfoRepository.deleteByDataLblSnIn(...)` + 속성값 정리를 추가(LabelService 와 동일 규약), ② 스냅샷 payload 에 이미 존재하는 `autoLblYn/confScore/trackId/lblSrcCd`(`LabelResponse.Item` — `VersionService.java:649-650` 에서 직렬화 확인됨)를 `parseSnapshotLabels`/`RestoredLabel` 에서 함께 읽어 복원하도록 확장. payload 에 이미 값이 있으므로 스키마 변경 없이 복원 가능하다.

### [D-ISSUE-24] TC-DIFF-014 — "멱등" 롤백도 라벨을 삭제·재생성하며, 그 결과 active 스냅샷과 실제 라벨의 ID 가 어긋남
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 현재 active 와 동일한 버전으로 롤백하면 아무 것도 바뀌지 않아야 한다(no-op).
- **현재 동작(이슈 내용)**: `rollback`(:363-378)은 **먼저** `replaceFrameLabels`(:373)로 라벨을 지우고 다시 만든 **뒤에** `upsertRollbackVersion`(:378) 안에서 멱등 여부(:401-408)를 판정한다. 멱등이라 판정돼도 라벨 계층의 파괴적 재작성은 이미 끝난 상태다.
  - **실측**: active=v3(`5c3a62fe…`) 상태에서 v3 로 롤백 → 응답은 기존행(`lblHstrySn=38`), 버전 행 수 3 유지(정상). 그러나 같은 요청 로그에 두 줄이 함께 남았다.
    ```
    [Version] rollback restored labels srcSn=421 deleted=4 created=4
    [Version] rollback idempotent srcSn=421 hash=5c3a62fe… actor=1001
    ```
    라벨 `lbl_sn` 은 392~395 → **396~399** 로 변동.
  - 부수 결과: active 버전 v3 의 payload `items[].id` 는 `392~395` 인데 실제 `LS_DATA_LBL` 은 `396~399` 다 → **active 스냅샷과 작업본의 식별자가 불일치**.
- **재현/확인 경로**:
  ```bash
  ACT=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c \
    "SELECT version_hash FROM ls_label_version WHERE data_src_sn=421 AND actvtn_yn='Y';")
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT lbl_sn FROM ls_data_lbl WHERE src_sn=421 ORDER BY 1;"
  curl -s -X POST "http://localhost:18081/api/v1/versions/$ACT/rollback" -H "Authorization: Bearer $REV" \
       -H "Content-Type: application/json" -d '{"srcSn":421}'
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT lbl_sn FROM ls_data_lbl WHERE src_sn=421 ORDER BY 1;"  # 값이 변함
  docker logs klid-backend --since 1m 2>&1 | grep "rollback"
  ```
- **영향**: 데이터 내용 손실은 없으나, ① no-op 이어야 할 요청이 `LS_DATA_LBL` 전 행 delete+insert 를 유발(불필요 I/O·이력 오염), ② D-ISSUE-22/23 의 부작용(ID 증가·AI 메타 고아화)이 **의미 없는 반복 클릭만으로도** 누적, ③ active 스냅샷 payload 의 `id` 가 실제 라벨과 불일치해 후속 diff 신뢰도 저하.
- **수정 방향(제안)**: 멱등 판정을 라벨 교체 **이전**으로 끌어올린다. 잠금(:368) 획득 직후 `activeVersions` 중 `newHash` 일치 행이 있으면 `replaceFrameLabels` 를 건너뛰고 즉시 기존 active 를 반환. (잠금은 이미 교체 전에 획득하므로 Race 순서 규약 — TC-DIFF-018 — 는 유지된다.)

### [D-ISSUE-25] TC-VERSION-011 — 비식별 신고 스냅샷(`DEIDENT_REPORT`)에 복원 경로가 존재하지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `snapshotDeidentReport` javadoc(:238-241)은 "**복원 가능한** 전체 라벨 스냅샷을 기록한다 … 기존 `LS_DATA_LBL_HSTRY` 는 좌표 복원이 불가하므로"라고 명시한다. 즉 신고로 삭제된 라벨을 되살릴 수단이 있어야 한다.
- **현재 동작(이슈 내용)**: 이 스냅샷은 `LsLabelVersion.createInactiveRawSnapshot(rawSn, …)`(:271-273)으로 적재되어 `DATA_SRC_SN` 이 **NULL** 이다(실측: `lbl_version_sn=1, data_raw_sn=8, data_src_sn=NULL, items=28, actvtn_yn=N`). 반면 두 조회/복원 진입점은 모두 srcSn 스코프다.
  - `listVersions`(:281) → `findByDataSrcSnOrderByRegDtDesc(srcSn)` → NULL 행은 절대 매칭되지 않음.
  - `rollback`(:348) → `findByDataSrcSnAndVersionHash(srcSn, hash)` → 동일하게 매칭 불가.
  - **실측**: rawSn=8 의 프레임(srcSn=4)으로 `GET /v1/frames/4/versions` → `data: []`. 해당 해시로 rollback → **404** "롤백 대상 버전을 찾을 수 없습니다."
- **재현/확인 경로**:
  ```bash
  H8=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c \
    "SELECT version_hash FROM ls_label_version WHERE save_reason_cd='DEIDENT_REPORT' LIMIT 1;")
  S8=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c "SELECT src_sn FROM ls_data_src WHERE raw_sn=8 LIMIT 1;")
  curl -s "http://localhost:18081/api/v1/frames/$S8/versions" -H "Authorization: Bearer $REV"        # []
  curl -s -X POST "http://localhost:18081/api/v1/versions/$H8/rollback" -H "Authorization: Bearer $REV" \
       -H "Content-Type: application/json" -d "{\"srcSn\":$S8}"                                       # 404
  ```
- **영향**: 비식별 누락 신고로 **영상 전체 라벨이 삭제**된 뒤(R1 v1.14), 오탐이었거나 재비식별 완료 후 라벨을 복구하려 해도 API 로는 불가능하다. 스냅샷은 DB 에 있으나 write-only 상태이므로 수기 SQL 없이는 복원 수단이 없다. 라벨 작업량 손실 위험.
- **수정 방향(제안)**: ① rawSn 스코프 조회/복원 진입점 추가(예: `GET /v1/videos/{rawSn}/versions`, `POST /v1/versions/{hash}/restore-raw`)하고 payload 의 프레임별 분해 복원 로직을 구현하거나, ② 신고 스냅샷을 프레임 단위로 분할 적재해 기존 srcSn 경로에 자연스럽게 노출. 어느 쪽이든 "신고 스냅샷은 검수 버전 목록에 섞이면 안 된다"(현재 `ACTIVE_YN='N'` 의도)는 제약을 유지해야 하므로 별도 API 분리가 안전하다.

### [D-ISSUE-26] TC-DIFF-005 관련 — `DATA_SRC_SN` 이 NULL 인 버전 해시로 diff 호출 시 처리되지 않은 500
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 어떤 입력에도 `ApiResponse` 규격의 4xx 로 응답해야 한다(rules/api-design.md — 에러 응답에 내부 정보 노출 금지, 미처리 예외 금지 / OWASP A10:2025 Mishandling of Exceptional Conditions).
- **현재 동작(이슈 내용)**: `diff`(:302-305)는 `findByHashOrThrow` 로 버전을 찾은 뒤 `accessGuard.verifyAccess(fromVersion.getDataSrcSn(), actor)` 를 호출한다. `DEIDENT_REPORT` 스냅샷은 `DATA_SRC_SN` 이 NULL 이므로 `LabelAccessGuard.verifyAndGet`(`LabelAccessGuard.java:46`)의 `srcRepository.findById(null)` 에서 예외가 발생한다.
  - **실측**: HTTP **500**. 백엔드 로그:
    ```
    ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
    org.springframework.dao.InvalidDataAccessApiUsageException: The given id must not be null
    Caused by: java.lang.IllegalArgumentException: The given id must not be null
    ```
- **재현/확인 경로**:
  ```bash
  H8=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c \
    "SELECT version_hash FROM ls_label_version WHERE save_reason_cd='DEIDENT_REPORT' LIMIT 1;")
  curl -s -o /dev/null -w "%{http_code}\n" \
    "http://localhost:18081/api/v1/versions/$H8/diff?compareWith=$H8" -H "Authorization: Bearer $REV"   # 500
  ```
- **영향**: 인증된 REVIEWER/WORKER 누구나 유발 가능한 미처리 500. 응답 본문에 내부 정보는 노출되지 않으나(GlobalExceptionHandler 가 일반화) 오류 처리 규약 위반이며, 신고 이력이 쌓일수록 노출면이 커진다. 데이터 손상·권한 우회는 없음.
- **수정 방향(제안)**: `diff` 에서 `getDataSrcSn() == null` 인 버전을 **비교 대상 아님**으로 판단해 `INVALID_INPUT(400)` 또는 `NOT_FOUND(404)` 로 조기 반환. 방어적으로 `LabelAccessGuard.verifyAndGet` 진입부에도 `srcSn == null` 가드를 추가.

### [D-ISSUE-27] TC-DIFF-005 관련 — `findByHashOrThrow` 가 다중 매칭 시 `.get(0)` 을 비결정적으로 선택
- **심각도**: LOW
- **기대 동작(기대효과)**: 해시로 버전을 특정할 때 결과가 결정적이어야 한다.
- **현재 동작(이슈 내용)**: `VersionService.java:560-566`
  ```java
  List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
  if (matches.isEmpty()) { throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage); }
  return matches.get(0);
  ```
  UNIQUE 제약은 `(DATA_SRC_SN, VERSION_HASH)` 복합(`uk_ls_label_version_src_hash`)이므로 **서로 다른 프레임이 같은 해시를 가질 수 있다**. `findByVersionHash` 는 정렬 없이 리스트를 반환하고 `.get(0)` 은 DB 반환 순서에 의존한다.
- **재현/확인 경로**: 현재 데이터에는 프레임 간 동일 해시가 없어 실동작 재현 불가(스냅샷 payload 에 `srcSn`/`frameNo` 가 포함되어 충돌 확률이 매우 낮음). 스키마·코드 정적 근거만 존재.
  ```sql
  SELECT version_hash, count(*) FROM ls_label_version GROUP BY 1 HAVING count(*) > 1;  -- 현재 0행
  ```
- **영향**: 인가는 선택된 버전 기준으로 재검증되므로 **권한 우회는 아니다**. 다만 diff 대상이 비결정적으로 뒤바뀔 수 있어 결과 재현성이 떨어진다. 실현 가능성 낮음.
- **수정 방향(제안)**: diff API 가 srcSn 컨텍스트를 받도록 시그니처를 확장해 `findByDataSrcSnAndVersionHash` 를 쓰거나, 최소한 정렬 기준(예: `ORDER BY LBL_VERSION_SN`)을 명시.

### [D-ISSUE-28] TC-DIFF-020 — `isCommittable` 이 프로덕션에서 참조되지 않는 dead code
- **심각도**: LOW
- **기대 동작(기대효과)**: PORTAL 채널 배제 가드가 실제 실행 경로에서 동작한다.
- **현재 동작(이슈 내용)**: `VersionService.java:814-816` `public static boolean isCommittable(TokenClaims actor)` 의 참조처는 `VersionServiceTest.java:315-317`(자기 자신을 검증하는 단위 테스트) **뿐**이다. `grep -rn "isCommittable" backend/src frontend/src` 결과 프로덕션 참조 0건. 실제 PORTAL 차단은 `VersionController` 의 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 가 수행한다.
- **재현/확인 경로**:
  ```bash
  grep -rn "isCommittable" backend/src frontend/src   # 테스트 4줄 + 선언 1줄만
  # 실차단은 정상 동작:
  curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:18081/api/v1/frames/421/versions" -H "Authorization: Bearer $PORTAL_TOKEN"  # 403
  ```
- **영향**: 기능 결함 없음(차단은 실제로 동작). 다만 테스트가 "PORTAL 배제 검증"이라는 이름으로 **실행되지 않는 코드**를 검증하고 있어, 컨트롤러의 `@PreAuthorize` 가 제거·완화돼도 이 테스트는 통과한다(회귀 방어 공백).
- **수정 방향(제안)**: `isCommittable` 을 제거하고 PORTAL 배제 회귀 테스트를 `VersionControllerTest` 의 `@WithMockUser`/MockMvc 403 검증으로 이관하거나, 서비스 진입부에서 실제로 호출하도록 배선.

---

## 반증 시도 요약 (확증편향 차단 기록)

| 반증 포인트 | 시도한 반증 | 결과 |
|------------|-----------|------|
| 스냅샷이 라벨 저장 시점에도 새는가 | 승인 상태에서 `PUT /v1/frames/421/labels` 실행 후 버전 행 수 비교 | **누출 없음** (1행 → 1행). 정책 준수 |
| 스냅샷이 영상 전체를 담는가 | rawSn=26 의 16개 payload `items` 개수를 프레임별 `ls_data_lbl` 실카운트와 1:1 대조, 합계 대조 | **131 = 131 완전 일치**, 프레임 누락 0 |
| 해시가 결정적인가 | 저장 payload 를 추출해 `shasum -a 256` 재계산 | **완전 일치**. 다만 이 결정성이 D-ISSUE-21(ROLLBACK 분기 도달 불가)의 원인이기도 함 |
| 동일 payload 재스냅샷이 동일 해시인가 | 미변경 11프레임 재승인 | 새 행 미생성(멱등) — 해시 동일 확인 |
| 롤백이 이력을 파괴하는가 | 롤백 후 v1·v2 행 잔존 여부 확인 | **원본 스냅샷 보존됨**(16/37/38 모두 잔존, `actvtn_yn` 만 전환). 이력 파괴 없음 |
| 롤백이 실제로 라벨을 복원하는가 | 좌표 단위 비교 | **정확 복원**. 단 식별자·AI메타·trackId 는 유실(D-ISSUE-22/23) |
| 롤백 round-trip 이 안정적인가 | v1→수정→v2→롤백→재승인 v3 후 diff(v1,v3) | **불안정** — 좌표 동일한데 8건 오분류 (D-ISSUE-22) |
| 멱등 롤백이 진짜 no-op 인가 | active 로 롤백 후 `lbl_sn` 비교 | **no-op 아님** — 라벨 재작성됨 (D-ISSUE-24) |
| 순서 뒤바뀐 diff(v2→v1) | 역방향 호출 | 정확히 반전 (REMOVED↔ADDED) — 정상 |
| 동일 버전 self-diff | v1 vs v1 | 200 `[]` — 정상 |
| 타인 영상 버전 조회·롤백(CWE-639) | 미배정 WORKER(userNo=9999) 로 list/diff/rollback | 전부 **403** — 방어 정상 |
| 채널 격리 | PORTAL 토큰으로 3개 엔드포인트 | 전부 **403** — 방어 정상 |
| 미인증 접근 | 토큰 없이 list/rollback | **401** — 정상 |
| 라벨 0건 프레임 승인 시 스냅샷 | rawSn=13 의 라벨 0 프레임 7개 | 버전 0행 — 정상 스킵 |
| PII 로그 누출 | 전체 로그에서 좌표·payload·label 본문 grep | **0건** — 정상 |
| 기존 테스트가 실동작을 보장하는가 | 롤백 테스트 픽스처의 해시 실체 확인 | **위양성 2건 발견** (D-ISSUE-21) |

---

## 환경 변경 및 원복 (공유 스택 — 다른 에이전트 참고용)

**rawSn=26 은 일절 변경하지 않았다** (읽기 전용 SQL·GET 만 수행). 후속 E 클러스터 참조 데이터 무결.

변경한 대상: **rawSn=19 / srcSn=421** (프레임 12개 중 1개)

| 항목 | 원래 상태 | 현재 상태 | 원복 |
|------|----------|----------|:--:|
| `ls_raw_data_status.data_stts_cd` (rawSn=19) | `APPROVED` | `APPROVED` | 완료 |
| active 버전 (srcSn=421) | `lbl_version_sn=16` (ver_no=1) | `lbl_version_sn=16` (ver_no=1) | 완료 |
| 라벨 내용 (srcSn=421) | person×1 + car×3, 특정 좌표 | **좌표·라벨명·타입·labelId 전부 동일** | 완료(내용 기준) |
| `ls_label_version` 행 (srcSn=421) | 1행 (16) | **3행 (16, 37, 38)** — 37·38 은 `actvtn_yn=N` | ✗ **원복 불가** |
| `ls_data_lbl.lbl_sn` (srcSn=421) | 176, 177, 178, 179 | **400, 401, 402, 403** | ✗ **원복 불가** |
| `ls_task_event_log` (rawSn=19) | 기존 | submit/start/approve **2사이클 추가** | ✗ 원복 불가 |

- `lbl_sn` 재발급과 버전 행 적층은 **본 이슈(D-ISSUE-21/22)의 산물 자체**이며, DB 직접 쓰기가 금지되어 되돌리지 않았다. 라벨 **내용**은 원본과 완전히 동일하므로 rawSn=19 를 참조하는 후속 검증의 라벨 데이터 정합성에는 영향이 없다. 단 **`lbl_sn` 값 자체나 버전 행 수를 하드코딩해 비교하는 검증이 있다면 위 값을 사용**해야 한다.
- 그 외 어떤 파일도 수정하지 않았고, 빌드·테스트도 실행하지 않았다.

---


## 이슈 상세 (FAIL / PARTIAL 전건)

### [D-ISSUE-41] TC-NOTIFY-003 — TASK_COMPLETED 페이로드가 0/null 하드코딩(self-fill)
- **심각도**: CRITICAL
- **기대 동작(기대효과)**: `TASK_COMPLETED` 는 관제서버가 조회 API 를 호출하기 전 1차 판단에 쓰는 메타 통지다. CLAUDE.md 계약 = 이벤트타입 + 작업ID + **영상 메타(파일명·길이·채널)** + 검수 완료 일시 + **프레임 개수** + **결과 요약 카운트(라벨 N건·메타 M건)** + 요청 ID. UNCERTAINTIES #3 확정에 따라 `totalFrames`/`labeledFrames`/`reviewerName` 은 **실카운트**여야 한다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/controlnotify/service/ControlNotifyService.java:93-103`
  ```java
  return new TaskCompletedPayload(
          "TASK_COMPLETED",
          event.rawSn(),
          null,  // reviewerName 은 추후 lookup 가능하나 현 단계에서는 미포함
          event.approvedAt(),
          0,     // totalFrames 는 추후 lookup
          0,     // labeledFrames 는 추후 lookup
          UUID.randomUUID().toString());
  ```
  - 외부/DB 조회 없이 **상수를 채워 전송**한다 = self-fill 의 전형. `ReviewApprovedEvent`(rawSn/reviewerNo/approvedAt)에 카운트가 없고, 서비스가 어떤 repository 도 주입받지 않는다(`ControlNotifyService.java:31-33`).
  - 추가로 `TaskCompletedPayload.java:19-27` 에 **영상 메타(파일명·길이·채널) 필드 자체가 없고**, 결과 요약 카운트(라벨/메타 건수)도 없다 — 계약의 절반이 미구현.
  - `reviewerName` 은 `ReviewApprovedEvent.reviewerNo` 가 있음에도 매핑하지 않는다.
  - 즉 관제서버는 모든 완료 통지를 "프레임 0개·라벨 0개·검수자 미상"으로 수신한다.
- **재현/확인 경로**:
  ```bash
  # 1) 통지 활성화 (설정만, 코드 수정 불요)
  #    .env: CONTROL_NOTIFY_ENABLED=true / CONTROL_NOTIFY_URL=http://klid-mock-server:9400
  # 2) 영상 승인 후 적재된 페이로드 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select evnt_type_cd, payload_cn from ls_control_notify_fallback where evnt_type_cd='TASK_COMPLETED';"
  # → "totalFrames":0,"labeledFrames":0,"reviewerName":null 확인
  # 실카운트 대조(rawSn=26): 프레임 16, export frame_cnt 32
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_data_src where raw_sn=26;"
  ```
- **영향**: 관제 연동 계약(CLAUDE.md "TASK_COMPLETED 페이로드 — 메타만") 미이행. 관제가 통지만으로 완료 규모를 판단할 수 없어 매 건 조회 API 왕복을 강제하며, 0 값이 "라벨 0건 완료"로 오해되어 수신측 데이터마트 UPSERT 를 오염시킬 수 있다.
- **수정 방향(제안)**: `ReviewApprovedEvent` 에 카운트/영상메타를 실어 보내거나(승인 트랜잭션에서 이미 집계됨), `ControlNotifyService` 에 `LsDataSrcRepository`/`LsDataLblRepository`/`LsDatasetVideoMeta` 조회를 주입해 payload 빌드 시점에 실카운트를 채운다. 조회 실패 시 0 으로 대체하지 말고 통지를 폴백 큐로 보낼 것(fail-closed).

### [D-ISSUE-42] TC-NOTIFY-007 — TASK_MODIFIED 가 프레임↔변경종류 페어링을 잃고 요약 카운트도 없음
- **심각도**: HIGH
- **기대 동작(기대효과)**: CLAUDE.md — "**변경 프레임 목록**(각 항목: 프레임 ID `SRC_SN` + 변경 종류 `LABEL_ADDED|LABEL_UPDATED|LABEL_DELETED|META_UPDATED`) + **변경 요약 카운트** + 요청 ID".
- **현재 동작(이슈 내용)**: `controlnotify/dto/TaskModifiedPayload.java:19-26` 이 `List<Long> frameIds` 와 `List<String> changeTypes` 를 **평행한 별개 리스트**로 보유. `ControlNotifyDebouncer.java:77-78` 이 각각 `Set` 으로 dedup 하므로 "프레임 A 는 라벨수정, 프레임 B 는 메타수정" 정보가 `frameIds=[A,B], changeTypes=[LABEL_UPDATED, META_UPDATED]` 로 뭉개져 **어느 프레임이 어떤 변경인지 복원 불가**. 변경 요약 카운트 필드도 없다.
- **재현/확인 경로**: 활성화 후 서로 다른 changeType 을 같은 rawSn 의 다른 프레임에 발생시킨 뒤
  `select payload_cn from ls_control_notify_fallback where evnt_type_cd='TASK_MODIFIED';`
- **영향**: 관제 연동 계약 위반. 관제가 변경 종류별 차등 처리를 할 수 없어 통지 수신 시 항상 전량 재조회해야 한다(통지의 요약 가치 소멸).
- **수정 방향(제안)**: `List<FrameChange(srcSn, changeType)>` 형태로 페이로드를 바꾸고 `DebouncedWindow` 를 `Map<Long, Set<String>>` 으로 축적. 요약 카운트(`changedFrameCount`, 종류별 건수)를 별도 필드로 추가. 관제팀 계약 협의 필요.

### [D-ISSUE-43] TC-NOTIFY-008 / TC-NOTIFY-014 — 영상 단위(srcSn=null) 변경 통지가 디바운서 NPE 로 유실
- **심각도**: HIGH
- **기대 동작(기대효과)**: 영상 단위 메타 수정(촬영환경 메타, event_annotation 등)도 `TASK_MODIFIED` 로 관제에 통지되어야 한다.
- **현재 동작(이슈 내용)**:
  - 발행 측이 `srcSn` 에 **명시적으로 `null`** 을 넣는 경로가 3곳:
    - `dataset/service/EnvironmentMetaService.java:112-113` → `new TaskModifiedEvent(rawSn, null, ChangeType.META_UPDATED, ...)`
    - `evntanno/service/EvntAnnoService.java:128-129` → 동일
    - `evntanno/service/EvntAnnoReviewService.java:226-227` → 동일
  - 소비 측 `controlnotify/service/ControlNotifyDebouncer.java:77-83`:
    ```java
    private final Set<Long> frameIds = ConcurrentHashMap.newKeySet();
    void addFrame(Long srcSn, String changeType) { frameIds.add(srcSn); ... }
    ```
    `ConcurrentHashMap.KeySetView.add(null)` 은 **NullPointerException** 을 던진다(널 키 불허). null 가드 없음.
  - 호출 경로가 `@TransactionalEventListener(AFTER_COMMIT)`(`ControlNotifyEventListener.java:34-37`) 이므로 예외는 Spring 이 삼키고 **통지가 조용히 사라진다**(폴백 큐에도 안 들어감 — 폴백은 전송 실패에만 동작).
  - 부수적으로 `flushExpiredWindows` 의 `windows.remove(rawSn)` → `window.getFrameIds()` 사이에 들어온 `accumulate` 는 유실되는 좁은 경합 구간도 존재(LOW).
- **재현/확인 경로**: 활성화 후 APPROVED 영상의 촬영환경 메타를 수정
  ```bash
  curl -X PUT http://localhost:18081/v1/videos/26/environment-meta -H "Authorization: Bearer <REVIEWER>" \
       -H 'Content-Type: application/json' -d '{"weather":"CLEAR"}'
  docker logs klid-backend 2>&1 | grep -i "NullPointer\|TaskModified"
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select * from ls_control_notify_fallback;"   # → 행 없음(유실)
  ```
- **영향**: 검수 완료 후 영상 단위 메타 수정이 관제에 **전혀 통지되지 않음** → 데이터마트 정합 깨짐. 단위 테스트가 srcSn=null 을 다루지 않아 BE 3013 GREEN 에도 잡히지 않는다.
- **수정 방향(제안)**: `DebouncedWindow.addFrame` 에서 `srcSn != null` 일 때만 `frameIds.add`, `changeTypes` 는 항상 축적. 영상 단위 변경임을 나타내는 별도 플래그(또는 D-ISSUE-42 의 `FrameChange` 에서 `srcSn` nullable 허용)를 페이로드에 반영. `ControlNotifyDebouncerTest` 에 null srcSn 회귀 케이스 추가.

### [D-ISSUE-44] TC-NOTIFY-011 / TC-NOTIFY-014 — `LABEL_ADDED` 는 발행되지 않는 dead 계약값
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `changeType` 4값이 실제 변경 성격에 맞게 발행되어 관제가 종류별로 판단할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 발행처 11곳 전수 조사 결과 실제 사용값은 `META_UPDATED`(7곳)·`LABEL_UPDATED`(3곳)·`LABEL_DELETED`(1곳, `label/service/DeidentReportService.java:147`)뿐이며 **`LABEL_ADDED` 는 어디서도 발행되지 않는다**. `label/service/LabelService.java:367-370` 은 주석대로 추가/수정/삭제를 모두 `LABEL_UPDATED` 하나로 통일한다(의도된 설계 결정이나 계약값 4종의 의미가 붕괴). 또한 발행 시점에 `ChangeType.ALL` 화이트리스트 검증은 수행되지 않는다(상수 사용 관례에만 의존).
- **재현/확인 경로**:
  ```bash
  grep -rn "new TaskModifiedEvent(" -A5 backend/src/main/java --include=*.java | grep -c "ChangeType.LABEL_ADDED"   # → 0
  ```
- **영향**: 관제가 `LABEL_ADDED` 를 기대해 분기 로직을 구현하면 영원히 도달하지 않는 죽은 경로가 된다. 계약 문서(CLAUDE.md)와 실제 발행 집합 불일치.
- **수정 방향(제안)**: ① `LabelService.bulkUpsert` 에서 `changes` 의 kind 분포로 ADDED/UPDATED/DELETED 를 구분 발행하거나, ② 계약값을 실제 발행 집합(3종)으로 축소하고 CLAUDE.md·관제 계약을 정정. 어느 쪽이든 `ChangeType.ALL` 로 발행 시점 assert 를 추가.

### [D-ISSUE-45] TC-NOTIFY-026 / TC-NOTIFY-028 — 관제 조회 API 라벨/프레임 조회에 페이징·상한 부재
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/api-design.md` — "목록 조회는 반드시 페이징 적용. 전체 조회(페이징 없는 findAll) 금지".
- **현재 동작(이슈 내용)**: `controlnotify/controller/TaskQueryController.java:66-73` 의 `getLabels` 는 `Pageable` 없이 `List<TaskLabelsResponse>` 전량 반환. `controlnotify/service/TaskQueryService.java:98,113-115` 가 영상의 **전 프레임 + 전 라벨을 한 번에 메모리 적재**한다. `frameIds` 파라미터에도 `@Size` 상한이 없어(`TaskQueryController.java:71`) 임의 길이 리스트를 받는다. `getSummary` 역시 전 프레임·전 라벨을 로드해 카운트한다(`TaskQueryService.java:54-67`).
- **재현/확인 경로**: 활성화 후 `curl "http://localhost:18081/v1/tasks/26/labels" -H "Authorization: Bearer <REVIEWER>"` → 16프레임 전량 반환(대용량 영상에서는 수천 프레임).
- **영향**: CWE-770 (Unrestricted Resource Consumption) / OWASP API4:2023. 인증된 계정 하나로 대용량 영상을 반복 조회해 BE 힙·대역폭을 고갈시킬 수 있다.
- **수정 방향(제안)**: `getLabels` 에 `Pageable`(기본 20, 최대 100) 적용, `frameIds` 에 `@Size(max=...)` 부여, `getSummary` 는 count 쿼리(`countDistinct`)로 대체해 전량 적재 제거.

### [D-ISSUE-46] TC-MARTVIEW-006 — V_COMPLETED_FRAME 이 원본 경로를 비식별 경로로 노출(파생영상)
- **심각도**: HIGH
- **기대 동작(기대효과)**: CLAUDE.md — "신규 추출은 `{base}/frames/raw|deid/{rawSn}` 로 분기 저장돼 두 경로가 항상 상이(원본 덮어쓰기 0)". 관제가 `DEIDENTIFIED_PATH` 로 픽업한 파일은 반드시 비식별본이어야 한다.
- **현재 동작(이슈 내용)** — [실동작]:
  ```
  select raw_sn, count(*) tot,
         count(*) filter (where original_path = deidentified_path) same,
         count(*) filter (where deidentified_path is null) deidnull
  from v_completed_frame group by 1;

   raw_sn | tot | same | deidnull
   -------+-----+------+---------
       13 |  11 |    0 |      11      ← 비식별 경로 전량 NULL
       14 |  12 |    0 |       0
       17 |  12 |    0 |       0
       19 |  12 |   12 |       0      ← 원본 == 비식별 (12/12)
       26 |  16 |    0 |       0      ← 참조 데이터는 정상 분리
  ```
  rawSn=19 실값: `original_path = deidentified_path = /app/storage/raw/resolution/19/frames/frame-0.jpg` — **해상도 파생영상 경로가 `raw/` 아래 단일 경로**로 기록돼 있고 뷰가 그대로 두 컬럼에 복제한다. rawSn=26 등 정상 추출본은 `raw/frames/raw/26/` vs `deidentified/frames/deid/26/` 로 분리되어 있어 대비된다.
- **재현/확인 경로**: 위 SQL 그대로 (`docker exec klid-postgres psql -U klid_user -d klid_system -c "..."`).
- **영향**: 데이터마트로 반출되는 "비식별 경로"가 실제로는 비식별 미적용 프레임을 가리킨다 = 개인정보 노출 경로(CWE-359 / 비식별 정책 위반). rawSn=13 은 반대로 비식별 경로 NULL 이라 관제 픽업이 결측된다. 뷰 자체에 불변식(원본≠비식별, 비식별 NOT NULL) 가드가 없어 파생영상 생성 경로의 결함이 그대로 마트까지 전파된다.
- **수정 방향(제안)**: ① 해상도 파생영상(`ls_data_src` 생성 경로)이 raw/deid 를 분기 저장하도록 정정, ② 뷰에 `DEIDENTIFIED_PATH IS NOT NULL AND DEIDENTIFIED_PATH <> ORIGINAL_PATH` 게이트를 걸어 fail-closed 로 노출 차단, ③ 기존 19/13 데이터 백필. (본 검증 범위상 rawSn=19/13 상태는 변경하지 않았음)

### [D-ISSUE-47] TC-MARTVIEW-008 — V_COMPLETED_LABEL_CHANGE 가 라벨 본문(좌표)을 그대로 노출
- **심각도**: HIGH
- **기대 동작(기대효과)**: CLAUDE.md V114 정책 — "라벨 좌표·속성 **본문은 검수 승인 export 폴더 JSON 에 존재하므로 뷰로 중복 노출하지 않음**(구 `V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR` 제거)". 변경점 뷰는 *변경이 있었다는 사실*만 전달해야 한다.
- **현재 동작(이슈 내용)** — [실동작] `select lbl_hstry_sn, raw_sn, left(chg_dtl_cn,600) from v_completed_label_change;`
  ```json
  [{"lblSn":83,"kind":"DELETED","labelName":"car",
    "before":{"lblTypeCd":"BBOX","labelId":2,"labelNm":"car",
              "pointCn":"[[685.9210689525468,1222.0134712939594],[789.9154049892563,1313.2119858975082]]"},
    "after":null}, ...]
  ```
  `V115__restructure_lbl_hstry_save_event.sql:58-66` 이 `h.CHG_DTL_CN` (= `List<LabelChange>` diff JSON, before/after 전체 라벨 스냅샷)을 뷰 컬럼으로 그대로 노출한다. `V_COMPLETED_LABEL` 을 제거한 목적(라벨 본문 뷰 중복 제거)이 무력화됐다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select lbl_hstry_sn, raw_sn, left(chg_dtl_cn,400) from v_completed_label_change limit 3;"
  ```
- **영향**: 관제 연동 계약(라벨 본문 비노출) 위반 + 라벨 데이터가 export JSON 과 뷰 두 곳에 존재해 **동기화 시점 차이로 불일치**할 수 있다(V114 슬림화가 해결하려던 문제의 재발).
- **부수 발견 — 근거 드리프트**: TC-MARTVIEW-008 의 근거는 `V114:105-121` + 기대값 `CHG_KIND_CD` 이나, **V115 가 해당 뷰를 DROP 후 재정의**(`V115:26,58`)하여 실제 출력 컬럼은 `LBL_HSTRY_SN/RAW_SN/SRC_SN/ADD_CNT/MDFCN_CNT/DEL_CNT/CHG_DTL_CN/REG_ID/REG_DT` 다. 테스트케이스 근거를 V115 로 갱신 필요.
- **수정 방향(제안)**: 뷰에서 `CHG_DTL_CN` 컬럼을 제거하고 건수 3종(`ADD_CNT/MDFCN_CNT/DEL_CNT`)만 노출하거나, 좌표·속성을 제거한 축약 diff(예: `lblSn`+`kind`+`labelName`)를 별도 생성 컬럼으로 파생. 관제팀 계약 협의 대상.

### [D-ISSUE-48] TC-NOTIFY-001·002 / TC-NOTIFY-026·031 — 통지·조회 기능이 런타임 비활성이라 실동작 미검증
- **심각도**: MEDIUM (검증 커버리지 갭 — 코드 결함 아님)
- **기대 동작(기대효과)**: 로컬 풀스택 검증 회차에서 관제 통지/조회가 목업서버(:9400) 상대로 실제 왕복하며 발행 시점·페이로드·인가가 실측되어야 한다.
- **현재 동작(이슈 내용)**: `.env:41` `CONTROL_NOTIFY_ENABLED=false` → `@ConditionalOnProperty` 빈 7종 미등록.
  - `GET /v1/tasks/26/summary|labels|meta` (미인증) → **404 ×3** (기대 401/403 을 실측 불가)
  - `ls_control_notify_fallback` **0 rows** — rawSn=26 은 APPROVED·export SUCCEEDED 인데 통지 관찰행이 없다 ⇒ **TASK_COMPLETED 미발행 확정**
  - 목업서버 `mock-server/` 에 관제 inbound SPI(`POST /api/v1/notify`) 스텁이 있는지도 별도 확인 필요(현 `CONTROL_NOTIFY_URL` 기본값은 `http://localhost:8090` 으로 목업서버 :9400 을 가리키지 않음, `application.yml:200`).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep CONTROL_NOTIFY   # CONTROL_NOTIFY_ENABLED=false
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18081/v1/tasks/26/summary   # 404
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select count(*) from ls_control_notify_fallback;"  # 0
  ```
- **영향**: D-6/D-7 31케이스 중 실동작으로 확정된 것은 TC-NOTIFY-025 하나뿐. AFTER_COMMIT 발행 시점, 실제 페이로드 JSON, 401/403 인가, 디바운스 60s 실 flush, Resilience4j 재시도·서킷 동작이 전부 미실측 상태다(D-ISSUE-41~44 도 정적 근거로만 확정).
- **수정 방향(제안)**: 검증 전용으로 `CONTROL_NOTIFY_ENABLED=true` + `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` 을 설정하고 목업서버에 `POST /api/v1/notify` 수신 스텁(요청 본문 기록)을 두어 2차 회차에서 페이로드 실측을 수행. **rawSn=26 은 E 클러스터 참조 데이터이므로 별도 신규 영상으로 재현할 것.**

### [D-ISSUE-49] TC-MARTVIEW-005 / TC-MARTVIEW-011 — 필터 반증 데이터 부재로 실효성 미확인
- **심각도**: LOW (검증 커버리지 갭)
- **기대 동작(기대효과)**: `ACTIVE_YN='N'` 스냅샷과 `video.*` 기술메타가 각각 뷰에서 제외되는 것을 **실제 반례로** 확인해야 한다.
- **현재 동작(이슈 내용)**: [실동작] `select active_yn, count(*) from ls_dataset_video_meta group by 1;` → `Y | 6` (N 행 0건). `select meta_key, count(*) from ls_data_meta group by 1;` → `manual-timeseries | 1` (video.* 0건). 필터 절 자체는 정적으로 존재(`V114:94`, `V101:104`)하고 `DatamartViewRebuildIT` 가 Testcontainers 에서 GREEN 이지만, 현 로컬 DB 로는 필터를 통과시켜 보는 반증이 불가능하다.
- **재현/확인 경로**: 위 두 SQL.
- **영향**: 뷰 게이트의 회귀 여부를 운영 데이터로 감지할 수 없다(테스트 픽스처에만 의존).
- **수정 방향(제안)**: 검증용 시드에 `ACTIVE_YN='N'` 스냅샷 1건 + `META_KEY='video.codec'` 메타 1건을 추가해 회차마다 반례 SELECT 가 0행임을 확인.

### [D-ISSUE-50] TC-MARTVIEW-014 — 승인→materialize→롤백 후 뷰·스냅샷 정합 시나리오 미커버
- **심각도**: LOW
- **기대 동작(기대효과)**: 승인 후 롤백(버전 되돌림)이 일어났을 때 `V_COMPLETED_*` 4뷰와 `LS_DATASET_VIDEO_META` 스냅샷이 정합 상태를 유지해야 한다.
- **현재 동작(이슈 내용)**: `backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java` 는 DisplayName 1건 — "materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영" 뿐. 이는 *승인 트랜잭션 롤백* 검증이며, TC 가 요구하는 *승인 완료 후 버전 롤백 시 뷰 정합* 은 검증하지 않는다.
- **재현/확인 경로**: `grep -n "DisplayName" backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java`
- **영향**: 롤백 후 뷰가 구버전 라벨/신버전 export 를 섞어 노출하는 회귀를 감지하지 못한다.
- **수정 방향(제안)**: 승인 → materialize → `VersionService.rollback` → 4뷰 SELECT 정합 검증 IT 를 추가.

---



## E. 증강 / 해상도 / Export / 메타

## 이슈 상세

### [E-ISSUE-01] TC-AUG-050~063 (전 콜백 케이스) — HMAC 웹훅 필터 경로 우회 (A-ISSUE-13 실증 확정)
- **심각도**: CRITICAL
- **기대 동작(기대효과)**: `/v1/aug/callback` 로 라우팅되는 **모든** 요청은 `HmacWebhookFilter` 의 서명·타임스탬프·본문크기·rate-limit 검증을 반드시 통과해야 한다.
- **현재 동작(이슈 내용)**: 필터 적용 여부를 `HttpServletRequest.getRequestURI()`(=**미디코딩 raw URI**)의 **정확일치**로 판정한다.
  - `HmacWebhookFilter.java:138-141` `shouldNotFilter` → `stripContext(request)`(:338-346, `getRequestURI()` 기반) → `!pathToSecret.containsKey(path)` 이면 필터 스킵
  - 반면 Spring MVC 라우팅은 **디코딩된 경로**로 매칭 → `/v1/%61ug/callback` 은 필터를 건너뛰고 `AugmentResultController`(permitAll)에 도달
  - 실증(무서명·무타임스탬프·시크릿 미설정 상태):
    - `POST /api/v1/aug/callback` → **401** `{"message":"Webhook 시크릿이 설정되지 않았습니다."}` (필터 적용)
    - `POST /api/v1/%61ug/callback` → **404** `{"message":"증강 행을 찾을 수 없습니다: dataAugSn=999999","errorCode":"NOT_FOUND"}` ← **서비스까지 도달 = 필터 우회**
  - 실제 상태 변조까지 성공: 무인증으로 `data_aug_sn=8/9/10/16` 을 ACCEPTED/REJECTED 로 전이시키고 신규 증강 영상 `rawSn=29,31` 을 생성했다(본 검증의 E-3 전건이 이 경로로 수행됨).
  - 2차 방어선도 없음 — AUGMENT 채널 멱등 원장은 **write-only**(E-ISSUE-07)라 콜백 시 조회되지 않고, 공격자는 `data_aug_sn`(순차 정수) + `aug_type_cd`(3택1)만 맞히면 된다.
  - `X-Timestamp` replay 윈도우·본문 1MB 캡·실패 rate-limit(5/분)도 전부 함께 무력화된다(우회 경로는 필터를 통째로 건너뜀).
- **재현/확인 경로**:
  ```bash
  # 필터 적용(대조군)
  curl -i -X POST 'http://localhost:18081/api/v1/aug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":999999,"otsd_job_id":"probe","aug_type_cd":"RAIN","aug_proc_sts_cd":"SUCCESS"}'   # 401
  # 우회(실험군)
  curl -i -X POST 'http://localhost:18081/api/v1/%61ug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":999999,"otsd_job_id":"probe","aug_type_cd":"RAIN","aug_proc_sts_cd":"SUCCESS"}'   # 404 (컨트롤러 도달)
  # 실제 변조(대상은 PENDING 행이면 무엇이든)
  curl -X POST 'http://localhost:18081/api/v1/%61ug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":<PENDING_PK>,"otsd_job_id":"EVIL-1","aug_type_cd":"<row와 동일>","aug_proc_sts_cd":"SUCCESS","raw_file_path_nm":"/nas-storage/evil.mp4"}'
  ```
  ```sql
  select data_aug_sn, aug_proc_stts_cd, otsd_job_id from public.ls_data_aug order by data_aug_sn desc;
  select raw_sn, data_stts_cd, orgnl_raw_sn, raw_file_path_nm from public.ls_data_raw where raw_sn >= 29;
  ```
- **영향**: **CWE-288** (Authentication Bypass Using an Alternate Path/Channel), **CWE-289**(Authentication Bypass by Alternate Name), 파생 **CWE-347**(서명 검증 부재)·**CWE-639**(임의 `data_aug_sn` 지정으로 타인 증강 결과 조작, IDOR)·**CWE-307 우회**(rate-limit 무력화)·**CWE-770 우회**(본문 캡 무력화). 공격자는 무인증으로 ①증강 결과 상태 위조(승인/반려) ②임의 `raw_file_path_nm` 을 가진 신규 RAW 행 대량 생성 ③외부 결과 인계 유실을 유발할 수 있다.
- **수정 방향(제안)**: 필터 적용 판정을 서블릿 컨테이너가 디코딩·정규화한 경로(Spring `ServletRequestPathUtils.getCachedPathValue` / `UrlPathHelper.getPathWithinApplication` 또는 `PathPatternParser` 매칭)로 바꾸고, "필터 적용 대상 판정"과 "컨트롤러 매핑"이 **동일한 경로 표현**을 쓰도록 단일화한다. 추가로 `SecurityConfig` 의 permitAll 매처도 동일 표현으로 좁히고, 컨트롤러 진입 시 "필터를 통과했다"는 마커(request attribute) 부재 시 fail-closed 로 거부하는 이중 게이트를 둔다. 회귀 테스트에 `%61ug`·대소문자·`;param`·`//` 변형 케이스를 추가.

### [E-ISSUE-02] TC-AUG-001/027 — 증강 외부 연동 미구현(ENV-ISSUE-01 재확인, 실측)
- **심각도**: HIGH
- **기대 동작**: 증강 요청이 목업서버(mock-server)를 포함한 외부 증강 시스템으로 실제 전달되고, 그 응답/콜백으로 결과가 채워져야 한다.
- **현재 동작**:
  - `ExternalAugmentClient` 의 유일 활성 구현이 `NoopExternalAugmentClient`(`NoopExternalAugmentClient.java:31-46`) — HTTP 호출 없이 로그만 찍고 `true` 반환. 실동작 로그로 확정:
    `[Augment] external request (noop) originAugSn=8 augType=WINTER externalJobId=JOB-… callbackUrl=http://localhost:8080/api/v1/aug/callback`
  - `docker logs klid-mock-server` 전체 기간 인바운드: `/project`·`/retrieve_progress`(KPST 비식별)·`/health` **뿐** — `/v1/augment` **0건**.
  - mock-server 측도 `mock-server/app/routers/augment.py:44-48` `POST /v1/augment` 가 **501 Not Implemented 스텁**이라 애초에 왕복이 성립하지 않음.
  - 결과: 증강 요청 행은 외부 응답 없이 **영구 PENDING**(재시도·타임아웃·dead-letter 없음, E-ISSUE-06 참조).
- **재현/확인 경로**: `docker logs klid-mock-server 2>&1 | grep augment` → 0건 / `docker logs klid-backend | grep "external request"` → noop 로그
- **영향**: SFR-07 외부 증강 위탁 플로우가 **엔드투엔드로 성립하지 않음**. 본 클러스터에서 외부 왕복에 의존하는 결과값은 전부 미검증 상태로 남는다.
- **수정 방향(제안)**: WebClient + Resilience4j 기반 실 구현체를 추가하고 `authoring.augment.external.mode` 로 noop/real/mock 을 전환. mock-server `/v1/augment` 를 접수→비동기 HMAC 서명 콜백 발신까지 구현해 로컬 왕복을 성립시킨다.

### [E-ISSUE-03] TC-AUG-001 — `application-local.yml` 키 오중첩으로 dev 콜백 시뮬레이터 영구 비활성
- **심각도**: HIGH
- **기대 동작**: local 프로파일에서 `authoring.augment.external.mode=dev` 가 되어 `DevAugmentCallbackSimulator` 가 로드되고 자족 콜백이 발신되어야 한다(주석에 명시된 의도).
- **현재 동작**: `backend/src/main/resources/application-local.yml:95-105` 에서 `augment:` 블록이 **`kpst:` 하위에 중첩**돼 있어 실제 프로퍼티 키는 `kpst.augment.external.mode` 다.
  ```yaml
  kpst:
    deid:
      enabled: ...
      base-url: ...
    augment:            # ← authoring: 이 아니라 kpst: 하위
      external:
        mode: ${AUGMENT_EXTERNAL_MODE:dev}
  ```
  `DevAugmentCallbackSimulator.java:46` 은 `authoring.augment.external.mode=dev` 를, `NoopExternalAugmentClient.java:26` 은 같은 키의 `noop`(matchIfMissing=true)을 본다 → 키가 미설정이므로 **항상 Noop** 이 활성. 실동작 로그가 `NoopExternalAugmentClient` 를 찍는 것으로 확정.
- **재현/확인 경로**: `docker logs klid-backend | grep "external request"` → `NoopExternalAugmentClient` (DevAugmentCallbackSimulator 로그 `[Augment][dev-sim] callback sent` 은 0건)
- **영향**: 로컬 자족 콜백 검증 수단이 침묵 상태로 죽어 있음. 콜백 관련 회귀가 로컬에서 전혀 감지되지 않는다.
- **수정 방향(제안)**: `augment:` 블록을 `authoring:` 하위로 이동. 아울러 `@ConditionalOnProperty(matchIfMissing=true)` 대신 기동 시 활성 구현체를 INFO 로 명시 로깅해 오설정이 침묵하지 않게 한다.

### [E-ISSUE-04] TC-AUG-050~063 — 정상 콜백 경로가 시크릿 빈 값으로 전건 401 (우회 경로만 열린 최악 조합)
- **심각도**: HIGH
- **기대 동작**: local 은 `application-local.yml:113` 의 기본 시크릿(32B)으로 정상 콜백이 통과해야 한다.
- **현재 동작**: `.env:45` 에 `WEBHOOK_HMAC_SECRET_AUGMENT=`(**빈 값**)가 정의돼 있고 컨테이너 env 로 주입된다(`docker inspect klid-backend` 확인). Spring 플레이스홀더는 "값이 빈 문자열"을 "미정의"로 보지 않으므로 yml 기본값이 적용되지 않고 시크릿 = `""` → `HmacWebhookFilter.java:156-161` fail-closed 로 **모든 정상 콜백 401**.
  로그: `[Webhook] secret missing path=/v1/aug/callback`
- **재현/확인 경로**: `docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep WEBHOOK` → `WEBHOOK_HMAC_SECRET_AUGMENT=` / 위 curl 대조군 401
- **영향**: 인증을 지키는 경로는 죽고(정상 콜백 불가), 인증을 건너뛰는 경로만 살아있는(E-ISSUE-01) 상태. 실 연동을 붙여도 콜백이 전부 401로 실패한다.
- **수정 방향(제안)**: `.env` 에서 빈 값 항목을 제거하거나 실제 값을 채운다. 부팅 시 "augment webhook 시크릿 미설정 → 콜백 비활성" 경고를 WARN 로 1회 노출(현재는 요청이 올 때만 로깅되어 배포자가 인지 못함).

### [E-ISSUE-05] TC-AUG-053 — UNIQUE 위반 후 "멱등 흡수" 경로가 PostgreSQL 에서 동작 불가 (500)
- **심각도**: HIGH
- **기대 동작**: 동시/오배송으로 `otsd_job_id` UNIQUE 가 충돌하면 `DataIntegrityViolationException` 을 잡아 `findByExternalJobId` 재조회 후 `false`(멱등 흡수)를 반환해야 한다(`AugmentResultService.java:119-131`).
- **현재 동작**: PostgreSQL 은 제약 위반 시 **트랜잭션 전체를 abort** 한다. catch 블록 내 재조회가 같은 트랜잭션에서 실행되므로 즉시 `SQLState 25P02 (current transaction is aborted…)` → `JpaSystemException` 전파 → **500 INTERNAL_ERROR**.
  ```
  ERROR ... duplicate key value violates unique constraint "uk_aug_external_job_id"
  WARN  ... SQL Error: 0, SQLState: 25P02
  ERROR ... current transaction is aborted, commands ignored until end of transaction block
  ERROR ... GlobalExceptionHandler - [Exception] unhandled exception  org.springframework.orm.jpa.JpaSystemException: ... select ... from LS_DATA_AUG lda1_0 where lda1_0.OTSD_JOB_ID=?
  ```
  대상 행(#16)은 변경 없이 PENDING 으로 롤백되어 데이터 손상은 없으나, 외부 시스템은 5xx 를 받고 **재전송을 반복**하게 된다.
  기존 테스트 `AugmentResultServiceTest:180-199` 는 Mockito 로 `save` 가 throw 하고 `findByExternalJobId` 가 값을 반환하도록 스텁 → **실 DB 의 aborted-transaction 을 전혀 재현하지 않는 위양성 GREEN**.
- **재현/확인 경로**:
  ```bash
  # 1) 임의 PENDING 행에 이미 사용된 otsd_job_id 를 전송
  curl -i -X POST 'http://localhost:18081/api/v1/%61ug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":<PENDING_PK>,"otsd_job_id":"<이미 존재하는 값>","aug_type_cd":"<row와 동일>","aug_proc_sts_cd":"SUCCESS"}'
  # → 500 INTERNAL_ERROR (기대: 200 {"applied":false})
  ```
- **영향**: 동시 콜백/오배송 시 멱등 계약 위반 + 5xx 노출. 외부 재시도 폭주 유발(가용성). 신뢰성 결함.
- **수정 방향(제안)**: ①선점 검사(`findByExternalJobId`)를 **저장 이전**에 수행해 위반을 회피하거나, ②충돌 처리를 `REQUIRES_NEW` 별도 트랜잭션 또는 서비스 바깥(재조회 전용 트랜잭션)으로 분리한다. ③테스트는 Testcontainers 기반 실 DB IT 로 교체(현 IT `AugmentDeidentConcurrencyIT` 는 서로 다른 otsd_job_id 케이스만 커버).

### [E-ISSUE-06] TC-AUG-032 / TC-AUG-036 — 증강 dead-letter·retry 가 프로덕션 도달 불가(테스트 위양성)
- **심각도**: MEDIUM
- **기대 동작**: 증강 처리가 영구 실패하면 `DEAD_LETTER_AT` 이 기록돼 잡 상태가 FAILED 로 집계되고, 재시도 횟수(`RTRY_NMTM`)가 누적돼야 한다.
- **현재 동작**: `LsDataAug.markDeadLetter()`(`LsDataAug.java:275-278`)·`incrementRetryCount()`(`:270-273`) 의 **호출자가 `main/` 전체에 0건**(grep: 정의부와 `LsDataMeta` 의 동명 메서드만 검출). 즉 증강 채널에는 재시도/데드레터 파이프라인 자체가 없다. 그 결과 `AugmentReviewService.java:244-247` 의 FAILED 분기와 `aggregateResultStatus` 의 `FAILED` 매핑은 **운영에서 도달 불가**. 관련 테스트(`listAll_그룹내_dead_letter_1건이면_status_FAILED`, `aggregateResultStatus_dead_letter_aug면_FAILED_반환`)는 픽스처가 `markDeadLetter()` 를 직접 호출해 GREEN 이 된다.
- **재현/확인 경로**: `grep -rn "markDeadLetter()\|incrementRetryCount()" backend/src/main/java` → 정의부만 / `select count(*) from public.ls_data_aug where dead_letter_at is not null;` → 0
- **영향**: 외부 증강 실패가 화면에 "실패"로 표시되지 않고 REQUESTED/IN_PROGRESS 로 영구 정체 → 운영 관측성 결함. E-ISSUE-02 와 결합 시 모든 증강 요청이 이 상태가 된다.
- **수정 방향(제안)**: 요청 후 무응답 감시(타임아웃 스케줄러) + 재시도 카운트 증가 + 임계 초과 시 dead-letter 마킹을 배치/스케줄러로 배선한다. 테스트는 그 배선 경로를 통해 상태가 만들어지는지로 검증한다.

### [E-ISSUE-07] TC-AUG-007/052 — AUGMENT 멱등 원장이 write-only (콜백에서 조회되지 않음)
- **심각도**: MEDIUM
- **기대 동작**: 요청 시 발급한 멱등 키(allowlist)가 콜백 수신 시 조회되어 무단/미발급 콜백을 2차 차단해야 한다(VLM 채널과 동일 패턴).
- **현재 동작**: `AugmentRequestBridge.java:50-51` 이 `ledger.recordIssued(...)` 로 AUGMENT 채널 키를 적재하지만, `AugmentResultService` 는 `WebhookIdempotencyLedger` 를 **주입조차 하지 않는다**(grep: 소비처는 `VlmResultService`·`VlmTimeseriesStep` 뿐). 콜백 DTO 에서 `idempotency_key` 필드도 제거됨(`AugmentResultRequest.java:21-23`). 실 DB 확인: `ls_webhook_idempotency` 의 AUGMENT 행 5건이 모두 `stts_cd='ISSUED'`·`aply_dt=NULL` 로 **영원히 소비되지 않음**.
- **재현/확인 경로**: `select idmp_key, chnl_cd, stts_cd, aply_dt from public.ls_webhook_idempotency where chnl_cd='AUGMENT';`
- **영향**: 콜백 인증이 HMAC 단일 계층에 전적으로 의존 → E-ISSUE-01 우회 시 남는 방어선이 0. 또한 원장 테이블이 무한 증가한다.
- **수정 방향(제안)**: 콜백에 발급 키를 되돌려받아 `lookupForProcessing`/`markProcessed` 로 게이팅하거나(VLM 패턴), 그럴 계획이 없다면 AUGMENT 채널의 `recordIssued` 를 제거해 사문화 상태를 없앤다.

### [E-ISSUE-08] TC-AUG-001/003/008 — 단건 요청 계약(@Size max=1) 과 다건 distinct·건별격리 로직/테스트케이스 불일치
- **심각도**: MEDIUM
- **기대 동작(테스트케이스 기준)**: 영상 N건 × 종류 3종을 한 요청으로 보내면 distinct 후 (영상×종류) PENDING 이 생성되고, 1건 실패는 격리된다.
- **현재 동작**: `AugmentRequestRequest.java:27-33` 이 `videoIds`·`types` 모두 `@Size(max=1)` 로 강제 → 2건 이상은 400. 따라서 `AugmentRequestService.java:99-103`(distinct)·`:126-138`(영상 루프)·`:133-137`(종류 루프)·`:154-175`(건별 격리)는 **API 경로에서 항상 1×1 로만 실행**되어 실질적 사문 코드다. 단위테스트 `distinctVideoIds`/`distinctTypes` 는 서비스를 직접 호출하므로 GREEN 이지만 실제 API 로는 도달 불가.
- **재현/확인 경로**: `curl -X POST …/v1/augments/request -d '{"videoIds":[13,13],"types":["WINTER"]}'` → 400 "영상은 한 번에 1건만 증강 요청할 수 있습니다."
- **영향**: 테스트케이스 카탈로그(TC-AUG-001/003/008)의 기대값이 현행 계약과 불일치 → 판정 왜곡. 코드 유지보수 부담(사문 분기).
- **수정 방향(제안)**: 계약을 정본으로 확정(단건 유지)한 뒤 테스트케이스 기대값을 단건 기준으로 갱신하고, 서비스의 다건 분기/단위테스트를 정리하거나 다건을 다시 허용한다.

### [E-ISSUE-09] TC-AUG-004/008 — 생성 0건이어도 200 성공 응답(silent no-op)
- **심각도**: MEDIUM
- **기대 동작**: 프레임 부재 스킵·건별 저장 실패로 PENDING 이 하나도 생성되지 않으면 호출자가 그 사실을 알 수 있어야 한다.
- **현재 동작**: `AugmentRequestService.java:126-145` 는 `createdCount` 를 로그에만 남기고, 응답 `AugmentRequestResponse(jobId, requestedAt, videoIds.size(), types.size())` 에는 **요청한 개수**를 그대로 담아 200 을 반환한다. 단건 계약(E-ISSUE-08)에서는 "요청 성공했으나 아무것도 생성되지 않음"이 곧 전체 실패인데도 성공으로 보인다.
- **재현/확인 경로**: 프레임 0건 APPROVED 영상으로 요청 → 로그 `no frame for video — skip rawSn=…` + `createdAugCount=0`, 응답은 200 `videoCount:1`
- **영향**: 운영자가 실패를 인지하지 못해 증강 누락이 조용히 발생.
- **수정 방향(제안)**: 응답에 `createdCount`/`skippedVideoIds` 를 포함하고, 생성 0건이면 4xx 또는 명시적 경고 필드로 구분한다.

### [E-ISSUE-10] TC-AUG-010 — 콜백 base URL 기본값이 자기 자신(localhost:8080)
- **심각도**: LOW
- **기대 동작**: 외부 시스템이 도달 가능한 주소가 콜백 URL 로 전달되어야 한다.
- **현재 동작**: `AugmentRequestService.java:77` `@Value("${authoring.webhook.callback-base-url:http://localhost:8080/api}")` 기본값이 컨테이너 내부 자기 자신을 가리키며, local 에서 override 되지 않는다(실측 로그 `callbackUrl=http://localhost:8080/api/v1/aug/callback`). 검증·경고 없음.
- **재현/확인 경로**: `docker logs klid-backend | grep callbackUrl`
- **영향**: 실 연동 시 외부가 도달할 수 없는 URL 을 전달해 콜백 유실(현재는 Noop 이라 증상이 드러나지 않음). 자족(self-referential) 설정이 기본값인 점은 self-fill 성 리스크.
- **수정 방향(제안)**: 기본값 제거(미설정 시 부팅 경고 또는 요청 차단) + `localhost/127.0.0.1` 기본값 사용 시 non-local 프로파일에서 부팅 실패 처리.

### [E-ISSUE-11] TC-AUG-056 — PII 게이트로 영상 생성이 보류돼도 aug 행은 ACCEPTED 종결 + applied:true
- **심각도**: MEDIUM
- **기대 동작**: 부모 비식별 미완으로 증강본 생성을 보류했다면, 그 증강 결과는 재처리 가능한 상태로 남거나 최소한 실패로 식별돼야 한다.
- **현재 동작**: `AugmentResultService.java:116-136` 이 상태 전이를 먼저 커밋한 뒤 `createAugmentedVideo` 안에서 게이트에 걸려 `return` 한다. 실측: aug#9 는 `AUG_PROC_STTS_CD='ACCEPTED'`, 응답 `{"applied":true}`, 그러나 `orgnl_raw_sn=5` 신규 영상 0건, 로그는 WARN 1줄뿐. 이후 이 행은 non-PENDING 이라 재콜백도 멱등 스킵(:100-105)되어 **영구 유실**된다. `originSrc`/`parentRaw` 미존재(:153-165)·부모 프레임 0건(:175-182) 경로도 동일하다.
- **재현/확인 경로**:
  ```sql
  select data_aug_sn, aug_proc_stts_cd from public.ls_data_aug where data_aug_sn=9;      -- ACCEPTED
  select count(*) from public.ls_data_raw where orgnl_raw_sn=5;                          -- 0
  ```
- **영향**: 증강 산출물 유실이 통계상 "완료(COMPLETED)"로 집계됨(잡카드 status). 운영 오판.
- **수정 방향(제안)**: 게이트 성립 시 상태를 종결시키지 말고 보류 상태(PENDING 유지 또는 별도 HELD 코드)로 남기고 응답을 `applied:false` + 사유로 회신, 부모 비식별 재완료 시 재처리 큐로 넘긴다.

### [E-ISSUE-12] TC-AUG-027/038/057/004 — 실동작 미재현 분기(정보)
- **심각도**: LOW
- **기대/현재**: 아래 분기는 현 환경 데이터·구현으로 재현 불가하여 정적 확인에 머물렀다.
  - `syncDecision` 예외 분기(TC-AUG-027): Noop 구현이 절대 throw 하지 않음 → 단위테스트도 부재
  - `cctvName "(이름 없음)" 폴백`(TC-AUG-038): 전 그룹 CCTV 매핑 존재
  - 부모 프레임 0건 보류(TC-AUG-057) / 요청 시 프레임 없는 영상 스킵(TC-AUG-004): APPROVED & 프레임 0건 데이터 부재
- **수정 방향(제안)**: 해당 픽스처를 시드에 추가하거나 실 DB IT 로 커버.

---

## A-ISSUE-13 (HMAC 우회) 실증 결과

| 항목 | 내용 |
|------|------|
| **가설** | `HmacWebhookFilter.shouldNotFilter` 가 raw URI 정확일치로 판정 → 퍼센트 인코딩 변형이 필터를 스킵하고 permitAll 컨트롤러에 도달 |
| **시도 요청 원문** | `POST /api/v1/%61ug/callback HTTP/1.1` · `Content-Type: application/json` · 서명/타임스탬프 헤더 **없음** · body `{"data_aug_sn":999999,"otsd_job_id":"probeA","aug_type_cd":"RAIN","aug_proc_sts_cd":"SUCCESS"}` |
| **응답** | `HTTP/1.1 404` · `{"success":false,"data":null,"message":"증강 행을 찾을 수 없습니다: dataAugSn=999999","errorCode":"NOT_FOUND"}` |
| **대조군(정규 경로)** | `POST /api/v1/aug/callback` (동일 body, 동일 무헤더) → `HTTP/1.1 401` · `WWW-Authenticate: HMAC` · `{"message":"Webhook 시크릿이 설정되지 않았습니다."}` |
| **필터 적용 여부** | **미적용(우회 성공)**. 백엔드 로그에 우회 요청에 대한 `[Webhook] …` 필터 로그가 전혀 남지 않음(정규 경로는 `secret missing path=/v1/aug/callback` 기록) |
| **다른 변형 결과** | `/v1/aug/callback/`(trailing slash) → 401(Spring Security), `/api//v1/aug/callback` → 401, `/v1/aug;x=1/callback` → 401, `/v1/AUG/callback` → 401 — **퍼센트 인코딩 변형만 관통** |
| **영향 실증** | 우회 경로로 무인증 상태에서 실 PENDING 행 4건(#8·#9·#10·#16)의 상태를 ACCEPTED/REJECTED 로 전이시키고, 신규 증강 영상 `ls_data_raw.raw_sn=29, 31`(임의 `raw_file_path_nm` 포함)을 생성 성공 |
| **기존 테스트** | `AugmentCallbackFlowIntegrationTest:390` `잘못된_서명_콜백은_401로_거부된다` 는 **정규 경로만** 검증. **(정정 2026-07-25)** `HmacWebhookFilter` 단위 테스트는 없는 것이 아니라 `src/test/java/kr/co/cudo/authoring/webhook/HmacWebhookFilterTest.java` 에 **17건 존재**한다(`common/security/` 하위만 확인해 놓침). 단 **경로 변형(우회) 케이스 0건 + 전부 `MockHttpServletRequest` 기반**이라 필터가 무력화돼도 GREEN 이 될 수 있는 위양성 위험이 있었다 |
| **판정** | **확정(CONFIRMED) — CRITICAL**. A-ISSUE-13 은 이론적 가능성이 아니라 **재현 가능한 실동작 결함**이며, E-ISSUE-04(정상 경로 401 fail-closed)와 결합해 "정규 경로는 막히고 우회 경로만 열린" 상태다. E-ISSUE-01 로 등재 |

---


## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [E-ISSUE-21] TC-RESL-041 / TC-RESL-044 — 해상도 파생 산출물이 비식별 저장소(deid base) 밖 raw base 에 생성·기록됨
- **심각도**: HIGH (보안 CWE-359 정보 격리 / 데이터 무결성)
- **기대 동작**: 파생 비디오·프레임은 비식별 산출물이므로 `STORAGE_DEIDENTIFIED_PATH` 하위(`videos/{rawSn}/`, `frames/deid/{rawSn}/`)에 생성되고, `DE_IDNTF_*` 컬럼에는 deid base 경로가, `SRC_FILE_PATH_NM` 에는 원본 경로가(파생은 원본 부재 → 별도 정책) 기록되어 마트·스트리밍·export 가 일관되게 동작해야 한다.
- **현재 동작**: `ResolutionReservationPersister.java:82-84`(비디오), `ResolutionSnapshotService.java:177-178` + `:244-254 resolveSafeFile`(프레임)이 출력 base 를 raw base 로만 강제. `ResolutionPersistService.java:243-247` 이 같은 경로를 `SRC_FILE_PATH_NM`·`DE_IDNTF_SRC_FILE_PATH_NM` 두 컬럼에 동일 저장. `:143-146` 이 procLog `DE_IDNTF_FILE_PATH_NM` 에도 raw base 경로 기록.
  실측: `/app/storage/raw/resolution/{15,16,18,19}/frames/*.jpg`(각 12장), `/app/storage/raw/resolution/{14,17}/{preset}/video/*.mp4`, `ls_deident_proc_log` 15/16/18/19 전부 raw base.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT raw_sn, count(*), sum(CASE WHEN original_path=deidentified_path THEN 1 ELSE 0 END) FROM v_completed_frame GROUP BY raw_sn"` → rawSn=19 가 12/12. / `curl -H 'Authorization: Bearer <REVIEWER>' localhost:18081/api/v1/videos/19/stream` → 403.
- **영향**: ①파생 영상 재생 전면 불가(B-61) ②관제 데이터마트가 raw 저장소 경로를 "비식별 경로"로 수신(D-46) ③디렉토리 단위 접근제어·보존·백업 정책이 파생 비식별본을 raw 로 취급 ④`FrameSource` 우회 폴백을 강제해 전 영상 PII 격리 약화(E-ISSUE-22). CWE-359(Privacy Violation) / CWE-668(Exposure of Resource to Wrong Sphere).
- **수정 방향(제안)**: 위 "통합 수정 방향" 1~8. **B-61 을 raw base 허용으로 고치면 안 됨**(D-46 확대).

### [E-ISSUE-22] TC-RESL-041 파생 — `FrameSource` 의 DEIDENTIFIED rawBase 폴백이 전 영상 PII 격리를 fail-open 으로 만듦
- **심각도**: HIGH (보안 CWE-359)
- **기대 동작**: export 의 DEIDENTIFIED 벌은 deid base 하위 파일만 허용해야 한다(ORIGINAL 이 rawBase 단일 강제인 것과 대칭).
- **현재 동작**: `backend/src/main/java/kr/co/cudo/authoring/dataset/export/FrameSource.java:68-75`
  `Path[] candidateBases = (kind == ExportKind.ORIGINAL) ? new Path[]{rawBase} : new Path[]{deidBase, rawBase};`
  주석(69-71행)에 "해상도 파생 = ResolutionPersistService 가 파생 비식별을 raw base 에 기록"이라고 **우회 목적이 명시**되어 있다. 즉 해상도 파생 하나를 살리려고 **모든 영상**의 DEIDENTIFIED export 가 raw base 파일을 수용하게 됐다.
- **재현/확인 경로**: 임의 영상의 `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM` 이 raw base 를 가리키도록 오염돼도 export 가 이를 "비식별본"으로 기록한다(정적).
- **영향**: 비식별 미적용 원본 프레임이 DEIDENTIFIED export 벌에 섞여 관제/데이터마트로 유출될 수 있는 경로가 상시 열림.
- **수정 방향(제안)**: E-ISSUE-21 의 1~4 선행 후 `candidateBases` 를 `{deidBase}` 단일로 되돌린다. 단독 제거 시 기존 파생 export 가 즉시 PARTIAL 로 회귀하므로 **반드시 경로 이동·백필과 동일 릴리스**여야 한다.

### [E-ISSUE-23] TC-RESL-061 — 파생 확정 실패 시 `LS_DATA_RAW` 고아 행이 영구 잔존·무한 누적
- **심각도**: MEDIUM (데이터 위생)
- **기대 동작**: 확정 실패한 파생은 예약 aug 슬롯 해제와 함께 사용자/운영자가 상태를 인지하거나 정리할 수 있어야 한다.
- **현재 동작**: `AsyncResolutionRunner.handleFailure` 는 파일 cleanup + aug 삭제 + `markRawDataFailed` 만 수행하고 `LS_DATA_RAW` 행은 남긴다(`ResolutionPersistService`/`AsyncResolutionRunner` 전체에 파생 RAW 삭제 경로 없음). 실측: 부모 13 에 대한 FAILED 파생 RAW 가 **12건**(20,21,22,30,32~39) 누적. `GET /v1/videos` 는 파생을 제외하므로 화면·증강 이력 어디에도 노출되지 않는 **침묵 쓰레기**.
- **재현/확인 경로**: `POST /v1/videos/13/resolution` 반복 → `SELECT orgnl_raw_sn, data_stts_cd, count(*) FROM ls_data_raw WHERE orgnl_raw_sn IS NOT NULL AND raw_file_path_nm LIKE '%resolution%' GROUP BY 1,2`
- **영향**: 재시도마다 RAW 행 증가(파일은 미생성), 통계·마이그레이션·감사 시 노이즈. RAW_SN 시퀀스 소모.
- **수정 방향(제안)**: ①실패 파생 RAW 를 soft-delete/삭제하거나 ②FAILED 파생을 조회 가능한 운영 화면·API 에 노출하고 수동 정리 제공. ③최소한 동일 (부모,프리셋) 재요청 시 기존 FAILED 파생 RAW 를 재사용하도록 변경.

### [E-ISSUE-24] TC-RESL-001/007 — 201 `CREATED` 가 "예약 성공"만 의미하며 비동기 확정 실패를 알 방법이 없음
- **심각도**: MEDIUM (기능/UX)
- **기대 동작**: 사용자가 파생 생성 성공/실패를 확인할 수 있어야 한다.
- **현재 동작**: [실동작] `POST /v1/videos/13/resolution` → 201 `derivatives:[{rawSn:33,status:"CREATED"},...]` 반환. 그러나 ~20ms 뒤 3건 전부 Phase A CONFLICT 로 FAILED 전이. 응답은 CREATED 그대로이고, 파생 RAW 는 `GET /v1/videos` 에서 제외, 예약 aug 는 삭제되어 `GET /v1/augments` 의 `resolutionTypes` 에도 미노출 → **어느 화면에서도 실패를 볼 수 없다**.
- **재현/확인 경로**: 위 요청 후 `SELECT raw_sn,data_stts_cd FROM ls_data_raw WHERE raw_sn IN (33,34,35)` → 전부 FAILED.
- **영향**: REVIEWER 가 파생이 생성됐다고 오인. 부모 13 처럼 비식별 프레임이 없는 영상은 매번 조용히 실패.
- **수정 방향(제안)**: ①응답 상태값을 `RESERVED`/`ACCEPTED` 로 명확화 ②파생 상태 조회 API 또는 증강 이력에 FAILED 파생 노출 ③가능하면 동기 단계에서 "부모 비식별 프레임 보유" 선검증을 추가해 예약 전에 400 으로 거부.

### [E-ISSUE-25] TC-RESL-001 — 파생 `VMS_CLIP_ID` 의 `RESL_RESL_` 이중 접두 드리프트
- **심각도**: LOW (데이터 품질)
- **기대 동작**: 파생 식별자에 프리셋 코드가 1회만 들어간다.
- **현재 동작**: `LsDataRaw.createFromResolution` (`LsDataRaw.java:226`) `vmsClipId = parent + "_RESL_" + goalResCd + "_" + millis` 이고 `goalResCd` 자체가 `RESL_720P` → 실측 `test-1784791814270_RESL_RESL_720P_1784792022000`.
- **재현/확인 경로**: `SELECT vms_clip_id FROM ls_data_raw WHERE orgnl_raw_sn IS NOT NULL`
- **영향**: 파생 종류를 `VMS_CLIP_ID` 마커 파싱으로 식별하는 코드/화면의 드리프트(기존 메모 `reviewpage-augmented-list-facts` 의 `RESL_RESL`/`RES_RES` 이슈와 동일 뿌리).
- **수정 방향(제안)**: 접두를 `"_"` 로 바꾸거나 파생 종류를 `LS_DATA_AUG.AUG_TYPE_CD` 조인으로만 판별(문자열 파싱 폐지).

### [E-ISSUE-26] TC-RESL-008 — 종횡비 보존 미구현(Javadoc 과 실제 불일치), 비-16:9 원본이 강제 왜곡됨
- **심각도**: MEDIUM (기능)
- **기대 동작**: `ResolutionPreset` Javadoc — "실제 다운스케일은 원본 종횡비를 보존하므로 목표 세로(height)를 기준으로 비율을 산정하고 가로는 종횡비에 맞춰 계산한다."
- **현재 동작**: `ResolutionSnapshotService.java:111-121` 이 `targetW=preset.width()`, `targetH=preset.height()` 를 그대로 쓰고 `ResolutionFileMaterializer.java:56` 이 `imageResizer.resize(src, dst, targetW, targetH)` 로 **고정 W×H 강제 스케일**. [실동작] 부모 13(1080×1920 세로) → RESL_1080P 요청 시 로그 `src=1080x1920 target=1920x1080` (scaleX=1.778, scaleY=0.5625) — 세로 영상이 가로로 눌린다. 라벨 좌표도 동일 배율로 왜곡 복사.
- **재현/확인 경로**: `POST /v1/videos/13/resolution` 로그 확인.
- **영향**: 세로/비표준 종횡비 원본의 파생 영상·라벨이 왜곡된 학습데이터로 산출. 문서·주석과 구현 불일치(감리 지적 소지).
- **수정 방향(제안)**: 정책 확정 필요 — ①Javadoc 대로 종횡비 보존(목표 높이 기준, 가로는 계산)으로 구현 정정하거나 ②정책이 고정 W×H 라면 Javadoc·설계서를 실제에 맞게 고치고 비-16:9 원본 처리(레터박스/거부) 규칙을 명시.

### [E-ISSUE-27] TC-RESL-062 — 파생 비디오/프레임 저장 스코프 비대칭 + 빈 부모 디렉토리 잔존
- **심각도**: LOW (설계 견고성)
- **기대 동작**: 한 파생의 모든 산출물이 파생 스코프 한 디렉토리에 모여 cleanup 1회로 완전 정리된다.
- **현재 동작**: 비디오=`resolution/{parentRawSn}/{preset}/video/{preset}.mp4`(`ResolutionReservationPersister.java:83-84`), 프레임=`resolution/{newRawSn}/frames/`(`ResolutionSnapshotService.java:177-178`). `cleanup(newRawSn, videoDst)` 은 프레임 디렉토리와 비디오 파일만 지우고 `resolution/{parentRawSn}/{preset}/video/` 빈 디렉토리는 남는다(실측 `resolution/14`, `resolution/17`).
- **재현/확인 경로**: `docker exec klid-backend ls -R /app/storage/raw/resolution`
- **영향**: 현재 id 상호배타성(파생은 부모가 될 수 없음) 덕분에 삭제 충돌은 없으나, 규약 변경 시 교차 삭제 위험. 빈 디렉토리 누적.
- **수정 방향(제안)**: E-ISSUE-21 경로 이동 시 비디오·프레임 모두 `{deid base}/resolution/{newRawSn}/` 단일 스코프로 통일.

### [E-ISSUE-28] TC-RESL-013 — 근거 라인 드리프트 (`ResolutionPreset.java:7-8`)
- **심각도**: LOW (문서)
- **기대 동작**: 케이스 근거가 실제 enum 정의 위치를 가리킨다.
- **현재 동작**: 테스트케이스 근거 `ResolutionPreset.java:7-8` 은 클래스 Javadoc. 실제 화이트리스트 enum 상수는 **14~16행**(`RESL_1080P(1920,1080)` 등).
- **영향**: 근거 추적 오류. (E-4/E-5 범위 다른 44개 근거는 전부 정합 — 드리프트 1건)
- **수정 방향(제안)**: 근거를 `ResolutionPreset.java:14-16` 으로 정정.

### [E-ISSUE-29] TC-RESL-037/039/049 — 확정 게이트 3종 전용 테스트 부재 + "테스트 GREEN = 안전" 착각 지점
- **심각도**: MEDIUM (검증 신뢰도)
- **기대 동작**: 보안·정합 게이트마다 실행 경로를 검증하는 테스트가 있어야 한다.
- **현재 동작**: backend 3013 테스트 전건 GREEN(`_raw/test-baseline.md`)임에도 —
  ①`ResolutionSnapshotService.java:126-130`(비식별 비디오 procLog 부재 404) ②동 `:166-171`(중복 videoFrameNo fail-fast) ③`ResolutionPersistService.java:311-321`(mtime 교체 게이트, `IOException` 시 **페일오픈 통과**) 3건에 대응하는 `@DisplayName` 이 `Resolution*Test`/`*IT` 전체에 없다.
  더 중요한 것은 **확정 후 파생 스트리밍**을 검증하는 테스트가 없다는 점이다 — 존재하는 테스트는 "확정**전**_파생RAW는_deIdntfYn_N이라_스트리밍이_NOT_FOUND로_거부된다(#2_PII_TOCTOU)" 뿐이라, 확정 후 403(E-ISSUE-21 증상5)이 전 테스트를 통과한다. 마찬가지로 어떤 테스트도 산출물 base 가 deid base 인지 단언하지 않고 raw base 를 기대값으로 고정하고 있다(`ResolutionFileMaterializerTest`).
- **재현/확인 경로**: `grep -h "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/video/Resolution*.java`
- **영향**: 결함이 CI 를 통과. 회귀 감지 불가.
- **수정 방향(제안)**: ①위 3게이트 단위 테스트 추가 ②`deIdntfYn='Y'` 확정 후 파생 스트리밍 200 IT 추가 ③`V_COMPLETED_FRAME` 에서 파생의 ORIGINAL≠DEIDENTIFIED 를 단언하는 IT 추가 ④mtime 게이트의 `IOException` 페일오픈이 의도된 정책인지 확정.

### [E-ISSUE-30] TC-RESL-034 — 출력 경로 traversal 가드가 도달 불가 코드
- **심각도**: LOW (정보)
- **기대 동작**: 케이스는 "preset명 traversal → 400" 을 기대.
- **현재 동작**: `ResolutionReservationPersister.java:122-128` `resolveSafeDir` 는 존재하나 입력이 `preset.name()`(enum 상수명)이라 traversal 문자가 유입될 경로가 없다. 즉 **가드는 실행되지만 위반 분기는 도달 불가**.
- **영향**: 없음(방어적 코드). 다만 케이스가 실제 위협을 검증하지 않는다는 착각을 준다.
- **수정 방향(제안)**: 케이스 기대값을 "enum 바인딩으로 traversal 원천 차단(가드는 심층방어)"으로 재기술. 코드 변경 불필요.

### [E-ISSUE-31] TC-RESL-005/012/031/043 — 실동작 미재현 4건(환경 제약)
- **심각도**: LOW (검증 커버리지)
- **내용**: ①TC-RESL-005(전 프리셋 동일 해상도) — 3 프리셋과 모두 일치하는 영상 부재 ②TC-RESL-012(dim≤0) — 손상 이미지 주입 불가(파일 수정 금지) ③TC-RESL-031(예약 단계 PII 게이트) — `APPROVED` + `de_ident_yn≠'Y'` + base 내 프레임을 동시 만족하는 영상 부재(rawSn 5/6 은 경로 가드가 선행 400) ④TC-RESL-043(리사이즈 게이트 포화 429) — 동시 부하 미생성.
- **수정 방향(제안)**: 검증용 시드(비식별 미완 APPROVED 영상, 프리셋 동일 해상도 영상)를 dev 시드에 추가하면 이후 회차에서 실동작 재현 가능.

---


## 이슈 상세

### [E-ISSUE-41] TC-EXPORT-007 — 해상도 파생 영상의 orgnl/deid 프레임 경로가 **동일**해 2벌 산출이 바이트 동일하고 anonymity 가 오표기됨
- **심각도**: HIGH (개인정보 메타 오표기 · 데이터마트 계약 위반 · 저장소 2배 낭비)
- **기대 동작(기대효과)**: `V_COMPLETED_FRAME` 계약대로 `ORIGINAL_PATH`(원본)와 `DEIDENTIFIED_PATH`(비식별)가 **항상 상이**하고, `orgnl/` 산출물(anonymity="N")은 비식별 처리되지 않은 원본 픽셀, `deid/` 산출물(anonymity="Y")은 비식별 픽셀이어야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - `ls_data_src`(raw_sn=18,19 — 해상도 파생) 실측: `src_file_path_nm` == `de_idntf_src_file_path_nm` = `/app/storage/raw/resolution/19/frames/frame-N.jpg` (완전 동일 문자열, 12/12행).
  - 산출물 md5 동일: `/app/storage/labeling/19/v3/orgnl/frame-0.jpg` = `/app/storage/labeling/19/v3/deid/frame-0.jpg` = `ae0d1773889308f3435a5ecb122f5523`.
  - 코드 근거 — `ResolutionPersistService.java:243-247` `LsDataSrc.create(newRawSn, frameNo, videoFrameNo, dst, dst, ...)` (주석 "파생본은 비식별 산출 → src=deid 경로 동일" 로 **의도적**).
  - 결과: `orgnl/frame-0.json` 이 `video.anonymity="N"`, `image.anonymity="N"` 으로 나가지만 픽셀 실체는 비식별본이다(`NiaJsonBuilder.java:145` 가 kind 로만 결정). 역으로 `deid/` 는 정상.
  - 또한 `FrameSource.java:71-78` 이 DEIDENTIFIED 에 대해 `deidBase` 실패 시 `rawBase` fallback 을 허용해 이 경로가 통과한다(설계상 해상도 파생 수용 목적).
- **재현/확인 경로**:
  1) `SELECT src_file_path_nm, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=19;` → 두 컬럼 동일
  2) `docker exec klid-backend md5sum /app/storage/labeling/19/v3/{orgnl,deid}/frame-0.jpg` → 동일 해시
  3) `grep '"anonymity"' /app/storage/labeling/19/v3/orgnl/frame-0.json` → `"N"`
- **영향**: 학습데이터 라벨 메타 오표기(비식별본을 "익명화 안 됨"으로 배포). 데이터마트 `V_COMPLETED_FRAME` 의 "두 경로 항상 상이" 불변식 파손. 해상도 파생 1건마다 동일 바이트를 버전당 2벌 복사(디스크 2배). 보안 CWE-1188(부정확한 보안 속성 초기화) 성격 — PII 유출 방향은 아니나 **역방향 오표기**.
- **수정 방향(제안, 구현 금지)**: ①해상도 파생 프레임의 `SRC_FILE_PATH_NM` 을 부모 **원본** 프레임을 리스케일한 별도 산출로 두고 deid 경로를 분리하거나, ②파생 영상은 원천이 비식별본임을 `LsDataRaw`(예: `orgnl_raw_sn` + `de_ident_yn`) 로 판정해 **ORIGINAL 산출을 스킵하거나 `anonymity="Y"` 로 표기**하도록 `NiaJsonBuilder`/`VideoMetaMapper` 의 kind 파생을 보정. ③최소 조치로 export 시 두 경로 동일이면 `deid` 1벌만 산출.

### [E-ISSUE-42] TC-META-002 / TC-EXPORT-028 — `time_of_day`·`season` 이 원천 없이 촬영일시 규칙으로 생성되어 export·데이터마트에 **파생 표시 없이** 실림 (self-fill)
- **심각도**: MEDIUM (데이터 정확성 · 관제 원천 미활용)
- **기대 동작(기대효과)**: 촬영환경 3필드의 원천은 ①작업자 수동입력 또는 ②관제 공유 `MNG_CLIP_EVNT_LST.WTHR_CD/SESN_CD/HR_TYPE_CD` 실값이어야 하고, 어느 쪽도 없으면 `null`(미상) 이거나 최소한 파생임이 소비자에게 식별 가능해야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn=26: `ls_data_raw.day_ngt_cd/sesn_cd` 모두 NULL 인데 `ls_dataset_video_meta` 에 `NGT`/`SUMMER` 가 동결되고 export JSON `video.time_of_day="NGT"`, `season="SUMMER"` 로 출력.
  - 생성 규칙 — `TimeOfDaySeasonDeriver.java:49-55`(hour ∈ [6,18) → DAY, else NGT), `:63-74`(월 3-3-3-3). 호출 지점 `DatasetVideoMetaSnapshotService.java:109-110`.
  - **정확성 결함 실증**: rawSn=26 의 `sht_dt = 2026-07-25 18:00`(한국 7월 일몰 ≈ 19:50) → 실제로는 주간이나 규칙상 `NGT`. 경계 18:00 고정이 계절과 무관해 여름 저녁은 항상 야간으로 오분류된다.
  - **원천 미활용 실증**: 저작도구 엔티티 `MngClipEvntLst.java:20` 주석이 `SESN_CD/WTHR_CD/HR_TYPE_CD/PRVC_TYPE_CD` 를 **명시적으로 매핑 생략**. 코드 전체에 `WTHR_CD`/`HR_TYPE_CD` 참조 0건(grep).
  - **소비자 구분 불가**: 조회 API 는 `timeOfDaySource:"DERIVED"` 를 주지만(투명), **export JSON·`LS_DATASET_VIDEO_META`·데이터마트 뷰에는 MANUAL/DERIVED 구분자가 없다** → 관제/데이터마트는 파생 추정값을 관측값과 동일하게 소비한다.
- **재현/확인 경로**: `SELECT sht_dt, day_ngt_cd, sesn_cd FROM ls_data_raw WHERE raw_sn=26;`(전부 NULL) ↔ `SELECT day_ngt_cd, sesn_cd FROM ls_dataset_video_meta WHERE raw_sn=26 AND active_yn='Y';`(NGT/SUMMER) ↔ `grep time_of_day /app/storage/labeling/26/v1/orgnl/frame-0.json`
- **영향**: 학습데이터 속성(주야간·계절)이 사실과 다를 수 있고, 이 속성으로 필터링/증강 유형 매칭을 하면 오염이 전파된다. 관제에 이미 존재하는 정답 값을 두고 추정값을 배포한다(메모리 `control-clip-meta-source-of-truth` 의 확정 방향과 배치).
- **수정 방향(제안, 구현 금지)**: ①`MngClipEvntLst` 에 `WTHR_CD/SESN_CD/HR_TYPE_CD/PRVC_TYPE_CD` 매핑 추가 → 적재/동결 시 **관제 실값 우선**(우선순위: 수동 > 관제 > 파생 > null). ②관제 코드도메인↔`WTHR_NM` 매핑표 확보(ERD-024). ③파생만 남는 경우 `LS_DATASET_VIDEO_META` 에 출처 컬럼(예: `ENV_SRC_CD` MANUAL/CTRL/DERIVED)을 추가해 export·뷰로 전파하거나, ④파생을 **중단하고 null** 로 두어 self-fill 을 제거.

### [E-ISSUE-43] TC-EXPORT-006 — 라벨/프레임 산출 불가 영상이 승인은 되고 export 만 FAILED 로 남아 학습데이터 0건인 채 "검수 완료"로 노출됨
- **심각도**: MEDIUM (기보고 `D-ISSUE-04` 의 E 구간 실증 — 중복 아님, 산출물 관점 보강)
- **기대 동작(기대효과)**: 검수 승인 = 학습데이터 확정이므로, 산출 가능한 프레임이 0건이면 승인 자체가 차단되거나 최소한 승인 후 재시도/알림 경로가 있어야 한다.
- **현재 동작(이슈 내용)** [실동작]: rawSn=4(v1,v2)·5·6 → `ls_dataset_export` 4행 `FAILED, frame_cnt=NULL`. 대응 폴더 `/app/storage/labeling/4/v1/{orgnl,deid}` 는 **생성되었으나 비어 있음**. `ls_raw_data_status` 는 `APPROVED` 유지. 코드 근거 `DatasetExportService.java:126-131`(`markFailed` 후 예외 미전파 — 승인 불변은 의도된 계약).
  - 부수: 실패해도 빈 디렉터리가 남고 정리(cleanup)되지 않는다(`DatasetExportWriter.java:80-87` 에서 선생성).
  - 부수: `retention` 미구현이 코드 TODO 로 명시(`DatasetExportService.java:110-111`) — 승인 반복마다 v1..vN 이 무한 누적(rawSn=19 는 이미 v3).
- **재현/확인 경로**: `SELECT * FROM ls_dataset_export WHERE export_stts_cd='FAILED';` → `docker exec klid-backend ls -R /app/storage/labeling/4`
- **영향**: 데이터마트가 `V_COMPLETED_VIDEO.EXPORT_PATH_NM` 로 픽업하면 빈 폴더/NULL 을 얻는다. 운영 알림 없이 무산출 승인이 축적.
- **수정 방향(제안, 구현 금지)**: 승인 전 `프레임 수>0 && 산출 가능 이미지>0` 선검증(reject 대신 경고+차단), export FAILED 시 재시도 잡/운영 알림 연결, 빈 산출 디렉터리 cleanup, retention 잡 도입.

### [E-ISSUE-44] TC-EXPORT-020 — export 최상위 VLM 키가 `event` 로 rename 되었으나 테스트케이스 문서·기존 산출물(v1)은 `event_annotation` — 계약 이원화 + `cot` 배열/객체 혼재
- **심각도**: LOW~MEDIUM (다운스트림 파서 이원화)
- **기대 동작(기대효과)**: 산출 JSON 의 VLM 블록 키와 내부 구조가 단일 계약이어야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - 현 코드: `NiaAnnotationDoc.java:33` `@JsonProperty("event")` → rawSn=17 **v2**(오늘 산출) = `"event"`, rawSn=19 v3 = `"event": null`.
  - 과거 산출물: rawSn=17 **v1**(07-23) = `"event_annotation"` + `image.orign_file_name` 잉여키 존재. **두 포맷이 같은 스토리지에 공존**(v1/v2 폴더).
  - `cot`: 정본은 객체(`{"1단계":..}`) 이나(`EventAnnotationPayload.java:88-96`) 동결 소스는 `ls_evnt_anno.anno_cn` **원문 JsonNode pass-through** 이므로 기존 저장분(`evnt_anno_sn=1,2`)의 **배열** 형태가 그대로 export 된다 — 실측 v2 JSON `"cot" : [ "111", "222", "3333" ]`. 코드 주석이 "배열 동결본 백필은 out of scope" 로 명시(의도된 잔존).
  - 테스트케이스 문서(TC-EXPORT-020/021)는 여전히 `event_annotation` 표기 → **근거 드리프트**.
- **재현/확인 경로**: `grep -n '"event' /app/storage/labeling/17/v1/orgnl/frame-0.json` vs `.../17/v2/orgnl/frame-0.json`
- **영향**: 관제/데이터마트 파서가 두 키와 두 `cot` 형태를 모두 다뤄야 한다.
- **수정 방향(제안, 구현 금지)**: ①구 버전 산출물 재생성 또는 폐기 정책 명시, ②`cot` 배열 동결본 백필(또는 export 시 배열→`n단계` 객체 정규화 — 이미 `CotDeserializer` 로직 재사용 가능), ③테스트케이스 문서 `event_annotation`→`event` 정정.

### [E-ISSUE-45] TC-META-008 / TC-META-039 — 로컬 설정으로 TASK_MODIFIED 통지 경로 실동작 미검증 (검증 한계)
- **심각도**: INFO (제품 결함 아님 — 검증 커버리지 공백)
- **기대 동작**: 촬영환경/프레임 개인정보 메타를 APPROVED 후 수정하면 관제로 `TASK_MODIFIED(META_UPDATED)` 가 rawSn 단위 1회 코얼레스되어 발행.
- **현재 상태**: `ControlNotifyEventListener.java:21` `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")` + 로컬 `false` → **소비 리스너 빈 자체가 없다.** 이벤트 발행부(`EnvironmentMetaService.java:112-114`, `FramePrivacyMetaService.java:126-129,147-150`)는 정적 확인만 완료. 로그에 `TaskModified`/`Debounce` 출력 0건.
- **영향**: TC-META-008 의 통지 절반, TC-META-039 전체가 실동작 미검증 → 두 케이스 PARTIAL.
- **수정 방향(제안)**: 목업 관제 inbound 엔드포인트(mock-server)로 `CONTROL_NOTIFY_ENABLED=true` 를 켠 별도 회차에서 재검증.

### [E-ISSUE-46] 검증 한계 — 목업 비식별이 원본을 복사하므로 orgnl/deid 산출물의 **픽셀 차이**를 실증할 수 없음
- **심각도**: INFO (환경 제약)
- **현재 상태**: rawSn=26 의 `orgnl/frame-0.jpg` 와 `deid/frame-0.jpg` md5 동일(`f7d5e59c…`). 단 **경로는 상이**(`/app/storage/raw/frames/raw/26/…` vs `/app/storage/deidentified/frames/deid/26/…`) 이고, mock-server 비식별이 원본 파일을 그대로 복사하는 구현이므로 **코드 결함이 아니다**(E-ISSUE-41 의 rawSn=19 와는 다름 — 그쪽은 경로 자체가 동일).
- **영향**: "비식별 픽셀이 실제로 마스킹되었는가"는 이번 회차에서 판정 불가.
- **수정 방향(제안)**: 실 KPST 연동 환경에서 재확인.

---


