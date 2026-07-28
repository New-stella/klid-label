## A-6. 추적ID·공통 응답·예외

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-TRACE-001 | X-Trace-Id 없으면 12자리 생성해 MDC/응답 설정 | PASS | RequestIdFilter.java:28-33 일치 | RequestIdFilterTest#generatesNewTraceId | - |
| TC-TRACE-002 | 제공 traceId CRLF 제거(CWE-113) | PASS | RequestIdFilter.java:48-58(sanitize, 종료 `}`는 59) 일치 | RequestIdFilterTest#sanitizesCrlfInjection | 근거 1줄 오차(58→실제 58 endpoint, 59 닫는 괄호) — 무시 가능 |
| TC-TRACE-003 | 영숫자·하이픈 외 제거+64자 절단 | PASS | RequestIdFilter.java:52-57 일치 | RequestIdFilterTest#truncatesOversizedTraceId | - |
| TC-TRACE-004 | 요청 종료 후 MDC 제거(스레드 누수 방지) | PASS | RequestIdFilter.java:36-38 일치 | RequestIdFilterTest#generatesNewTraceId(MDC null 단언 포함) | - |
| TC-TRACE-005 | RequestIdFilter 최우선순위 | PASS | RequestIdFilter.java:17 `@Order(Ordered.HIGHEST_PRECEDENCE)` 일치 | 전용 테스트 없음(정적 확인) | - |
| TC-RESP-001 | ApiResponse.ok success=true,errorCode=null | PASS | ApiResponse.java:9-11 일치 | 전용 단위테스트 없음, 다수 컨트롤러 테스트에서 간접 검증 | - |
| TC-RESP-002 | ApiResponse.error(code) success=false+name()+기본msg | PASS | ApiResponse.java:21-23 일치 | SystemConfigControllerTest#workerCannotUpdateConfig 등 다수 | - |
| TC-RESP-003 | error(code,message,data)로 부가 data 포함 | PASS | ApiResponse.java:29-31 일치, GlobalExceptionHandler.java:28-32 details!=null 분기 | 전용 테스트 없음(간접 추정) | data 파라미터 사용 실사례(NOT_REVIEWED 등)는 A-6 범위 밖 컨트롤러 |
| TC-RESP-004 | JsonInclude.ALWAYS null 필드도 직렬화 | PASS | ApiResponse.java:6 일치, 전역 Jackson 설정 충돌 없음 확인 | 직접 직렬화 단위테스트 없음(정적 확인) | 전역 ObjectMapper에 SerializationInclusion 재정의 없음 확인 완료 |
| TC-EXC-001 | CustomException이 ErrorCode status/message 매핑 | PASS | GlobalExceptionHandler.java:24-33 일치 | GlobalExceptionHandlerTest#customExceptionReturnsErrorCode | - |
| TC-EXC-002 | details 있으면 응답 data 포함 | PASS | GlobalExceptionHandler.java:28-32 일치 | 전용 테스트 없음(간접 추정) | - |
| TC-EXC-003 | @Valid 바디 실패 400+필드별 메시지 | PASS | GlobalExceptionHandler.java:35-43 일치 | GlobalExceptionHandlerTest#validationFailureReturnsInvalidInput | - |
| TC-EXC-004 | ConstraintViolation 400 정규화 | PASS | GlobalExceptionHandler.java:49-57 일치 | 전용 테스트 없음(코드 확인, 단순 매핑) | 커버리지 갭 — 회귀 시 조기 발견 어려움 |
| TC-EXC-005 | 잘못된 JSON/enum 역직렬화 400(파서상세 비노출) | PASS | GlobalExceptionHandler.java:64-69 일치, 로그도 클래스명만 기록(원문 비노출) | 전용 테스트 없음(코드 확인) | 로그도 getMostSpecificCause 클래스 simple name만 기록 — 이중 방어 양호 |
| TC-EXC-006 | 필수 RequestParam 누락 400 | PASS | GlobalExceptionHandler.java:76-81 일치 | 전용 테스트 없음(코드 확인) | 커버리지 갭 |
| TC-EXC-007 | 파라미터 타입 불일치 400(원문 비노출) | PASS | GlobalExceptionHandler.java:88-93 일치, 파라미터명만 노출·값/타입 비노출 확인 | 전용 테스트 없음(코드 확인) | 커버리지 갭 |
| TC-EXC-008 | 작업락 partial unique 위반만 409 | PASS | GlobalExceptionHandler.java:141-145,168-171 일치 | GlobalExceptionHandlerDataIntegrityTest#dataIntegrityBranching(work_lock 케이스) | - |
| TC-EXC-009 | 프리셋 labelId/라벨명 CI/검출유형 유니크 위반 각 409 | PASS | GlobalExceptionHandler.java:146-160 일치(3개 분기 모두 확인) | GlobalExceptionHandlerDataIntegrityTest#dataIntegrityBranching(preset/label_name_ci 케이스) — 검출유형(dtctType) 분기는 코드 확인만, 전용 파라미터화 케이스 없음 | isLabelDtctTypeUnique 분기 자체 단위테스트 부재(경미) |
| TC-EXC-010 | 판별 불가 무결성 위반은 500 노출(fail-closed) | PASS | GlobalExceptionHandler.java:161-166 일치 | GlobalExceptionHandlerDataIntegrityTest#dataIntegrityBranching(FK위반/제약명null 케이스) | fail-closed 정책 실증 — 조용한 409 흡수 없음 확인 |
| TC-EXC-011 | 제약명 Hibernate→SQL 메시지 순 추출 | PASS | GlobalExceptionHandler.java:193-209 일치 | GlobalExceptionHandlerDataIntegrityTest(제약명 있는/null 케이스 모두 커버) | - |
| TC-EXC-012 | 무결성 위반 응답에 제약명/SQL/스택 미노출 | PASS | GlobalExceptionHandler.java:135-136(정책 주석, 실제 강제 코드는 138-166) | GlobalExceptionHandlerDataIntegrityTest#responseHasNoConstraintNameOrSql(제약명/SQL/SQLState/원문 미노출 전수 검증) | 근거 라인이 주석(정책 설명)이라 실제 강제 코드(138-166)와 분리 — 경미한 근거 위치 드리프트 |
| TC-EXC-013 | AccessDeniedException 403 매핑 | PASS | GlobalExceptionHandler.java:211-216 일치 | 전용 테스트 없음(코드 확인, 단순 매핑) | - |
| TC-EXC-014 | AuthenticationException 401 매핑 | PASS | GlobalExceptionHandler.java:218-223 일치 | 전용 테스트 없음(코드 확인, 단순 매핑) | - |
| TC-EXC-015 | 미매핑 경로(NoResourceFound) 404 정규화 | PASS | GlobalExceptionHandler.java:241-246 일치 | GlobalExceptionHandlerTest#noResourceFoundReturnsNotFound | - |
| TC-EXC-016 | multipart 크기 초과 413 정규화 | PASS | GlobalExceptionHandler.java:234-239 일치 | MaxUploadSizeExceededMappingTest#MaxUploadSizeExceededException_표준_응답_매핑 | - |
| TC-EXC-017 | 미처리 Exception 500+고정메시지(스택 비노출) | PASS | GlobalExceptionHandler.java:248-253 일치, log.error에는 스택 기록(서버 로그 한정)·응답 body는 고정 메시지만 | GlobalExceptionHandlerTest#unhandledExceptionReturnsInternalError | 응답과 서버 로그 분리 확인 — 스택은 로그 파일에만, 클라이언트 응답엔 미노출 |

## A-7. 듀얼 DS·캐시·시스템설정·프로파일·Actuator·Resilience4j

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-DS-001 | Control DataSource/EMF/TxManager @Primary | PASS | ControlDataSourceConfig.java:31-56 일치(31,38,50 각 @Primary) | 전용 단위테스트 없음, IT 다수가 control TxManager로 간접 구동(예: LsLabelPresetRepositoryTest 등) | - |
| TC-DS-002 | @ControlRepo만 control EMF 라우팅 | PASS | ControlDataSourceConfig.java:19-28(@Configuration+@EnableJpaRepositories, includeFilter=ControlRepo) 일치 | 다수 control 레포지토리 IT 간접 커버 | 저장소 58개 전수 grep 결과 @ControlRepo/@PortalRepo 누락 0건(잘못된 EM 주입 여지 없음, fail-closed 구조) |
| TC-DS-003 | @PortalRepo는 portal EMF 라우팅(비-Primary) | PASS | PortalDataSourceConfig.java:18-27 일치, @Primary 없음 확인 | LsPortalUldRepositoryIT 등 간접 커버 | - |
| TC-DS-004 | 서비스 Tx가 controlTransactionManager 바인딩 | PASS | SystemConfigService.java:38,91 일치 | SystemConfigControllerTest(update/getInt 왕복으로 간접 실증) | - |
| TC-CACHE-001 | 4개 캐시 개별 TTL 등록 | PASS | CacheConfig.java:47-76 일치(60s/5m/6h/60s 값 전부 확인) | 전용 단위테스트 없음(정적 확인) | - |
| TC-CACHE-002 | sysconfig getInt/getString 60s 캐시 | PARTIAL | SystemConfigService.java:47-48 `@Cacheable` 존재 확인 | SystemConfigControllerTest#reviewerUpdatesAndCacheInvalidates(무효화 후 신규값 반영만 검증) | 캐시 "적중"(2번째 호출이 DB 재조회 안 함) 자체를 증명하는 테스트 없음 — repository mock 호출횟수 검증 부재. `@EnableCaching`+어노테이션 존재로 정상 동작 개연성 높으나 회귀(예: self-invocation, 프록시 미적용) 조기 발견 못 함 → A-ISSUE-22 |
| TC-CACHE-003 | update 시 sysconfig 캐시 전체 무효화 | PASS | SystemConfigService.java:90-91 일치 | SystemConfigControllerTest#reviewerUpdatesAndCacheInvalidates | 실제 DB 갱신 후 getInt 재조회로 신규값 확인 — 실질 검증 |
| TC-CACHE-004 | userRole null 결과 미저장 | PASS(근거 드리프트) | 근거로 지목된 CacheConfig.java:32-35는 **주석**(설명)이며, 실제 강제 코드는 `UserRoleResolver.java:46` `@Cacheable(..., unless="#result == null")` | UserRoleResolverTest(LS_역할없는_사용자는_무권한_null 등), UserRoleCacheEvictIT | 근거 파일 위치 드리프트(카탈로그가 comment만 인용, 실제 어노테이션은 별도 클래스) — 동작 자체는 정상, fail-closed(DataAccessException도 null) 확인 |
| TC-SYSCFG-001 | 화이트리스트 외 키 조회/갱신 거부 | PASS | SystemConfigService.java:93-98 일치, CWE-117 방어 주석 확인(사용자 입력 키 미노출) | SystemConfigControllerTest#SystemConfig_화이트리스트_외_키_업데이트시_INVALID_INPUT | 클래스 상단 주석("화이트리스트 4개 키")은 ConfigKeys.ALLOWED 실제 8개와 불일치(문서 drift, 동작에는 영향 없음) |
| TC-SYSCFG-002 | REVIEWER 아니면 update FORBIDDEN(이중 검증) | PASS | SystemConfigService.java:166-170 일치 | SystemConfigControllerTest#workerCannotUpdateConfig | - |
| TC-SYSCFG-003 | actor=null이면 update FORBIDDEN | PASS | SystemConfigService.java:166-169 일치(`actor == null \|\| actor.role() != Role.REVIEWER`) | 전용 테스트 없음(코드 확인만) | 인증 우회 방어 핵심 분기인데 자동 회귀테스트 부재 → A-ISSUE-23에 포함 |
| TC-SYSCFG-004 | NUMBER 키 정수+키별 범위 검증 | PASS | SystemConfigService.java:132-145 일치 | SystemConfigControllerTest#SystemConfig_BATCH_CONCURRENCY_범위_초과시_INVALID_INPUT_400 | - |
| TC-SYSCFG-005 | DECIMAL NaN/Infinity/범위밖 거부 | PASS(부분 커버) | SystemConfigService.java:147-164 일치, isNaN/isInfinite 명시 체크 확인 | PolygonSimplifyConfigTest(범위/비숫자만 커버, NaN/Infinity 문자열 입력 테스트는 없음) | Double.parseDouble("NaN")은 예외 없이 NaN 반환하므로 isNaN 분기가 실질 방어선인데 이 경로 전용 테스트 부재 → A-ISSUE-23 |
| TC-SYSCFG-006 | BOOLEAN true/false만 | PASS | SystemConfigService.java:120-125 일치 | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-SYSCFG-007 | 미지원 CONFIG_TYPE 거부 | PASS | SystemConfigService.java:127-128 일치 | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-SYSCFG-008 | getInt 타입/값 불일치 각 예외 | PASS | SystemConfigService.java:49-61 일치(NUMBER 아니면 INVALID_INPUT, 파싱 실패시 INTERNAL_ERROR) | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-SYSCFG-009 | 없는 설정 키 NOT_FOUND | PASS | SystemConfigService.java:110-113 일치 | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-PROF-001 | local 프로파일이 ENV=prd에서 부팅 차단 | PARTIAL | LocalProfileGuard.java:30-43 일치(ENV dev/stg/prd + local profile → IllegalStateException) | **테스트 파일 자체 없음**(find 결과 0건) | System.getenv 모킹 라이브러리(system-stubs 등) 미도입 — CWE-798 방어 핵심 가드가 회귀 테스트 무방비 → A-ISSUE-21 |
| TC-PROF-002 | ENV 미설정/local이면 local 허용 | PARTIAL | LocalProfileGuard.java:34-37 일치 | 테스트 없음 | A-ISSUE-21에 포함 |
| TC-PROF-003 | local 아니면 즉시 통과 | PARTIAL | LocalProfileGuard.java:31-32 일치 | 테스트 없음 | A-ISSUE-21에 포함 |
| TC-ACT-001 | prd는 actuator health만 노출 | PASS | application-prd.yml:39-43 일치(include: health, show-details: never) | 설정 파일 정적 검증(부팅 테스트로 exposure 확인하는 전용 테스트는 없음) | - |
| TC-ACT-002 | 기본은 ACTUATOR_EXPOSE로 노출 제어 | PASS | application.yml:90-94 일치 | 정적 검증 | - |
| TC-RES-001 | 외부 호출 Retry+CircuitBreaker 순차 적용 | PASS | AiServerClient.java:46-47,63-64 일치(predictYolo/predictYoloTrack 모두 Retry→CB 순서) | AiServerClientTest#AiServerClient_타임아웃_시_CircuitBreaker_OPEN_전환 | 나머지 2개 메서드(segment/track, 67-76·78-87)도 동일 패턴이나 전용 언급은 predictYolo류만 카탈로그에 인용 |
| TC-RES-002 | 5개 CircuitBreaker 빈 등록 | PASS | Resilience4jConfig.java:13-47 일치(deid/ai/vlmClient/controlNotify/kpstDeid 정확히 5개) | 전용 빈카운트 테스트 없음(정적 확인) | - |
| TC-RES-003 | aiOnline/portalSam2 Bulkhead 초과 시 429 | PASS | Resilience4jConfig.java:55-69(빈 등록만) — 실제 429 매핑은 AutolabelOnlineService.java:450,480 및 PortalSam2Service.java:192 catch(BulkheadFullException) | AutolabelPolygonServiceTest#폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다 | 근거 라인이 빈 등록 지점(Resilience4jConfig)만 가리키고 실제 429 전환 로직은 별도 서비스 클래스 — 근거 위치 드리프트(동작은 정상, 테스트로 실증됨) |
| TC-RES-004 | NonRetryableExternalException 재시도/CB 제외 | PASS | NonRetryableExternalException.java:23-30(마커 클래스 정의) — 실제 제외 설정은 application.yml:290-291,305-306,322-323,333-334 `ignore-exceptions` | KpstDeidentifyClientTest(K3_createProject_409/400 재시도없이 등), VlmClientTest(V1_400/422 재시도없이) | 근거가 클래스 정의만 가리키고 실제 제외 메커니즘은 yml 설정 — 근거 위치 드리프트. "ai"/"deid" CB 인스턴스에는 ignore-exceptions 없음(해당 클라이언트가 이 예외를 던지지 않으므로 정상) |

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

## 요약
- 총 48건 / PASS 43 / FAIL 0 / PARTIAL 5 / BLOCKED 0 / N/A 0
- 근거 라인 드리프트: 4건(TC-CACHE-004, TC-EXC-012, TC-RES-003, TC-RES-004 — 모두 "정책/구성 근거가 실제 강제 코드와 다른 파일·다른 라인에 위치", 동작 자체는 정상으로 확인됨)
