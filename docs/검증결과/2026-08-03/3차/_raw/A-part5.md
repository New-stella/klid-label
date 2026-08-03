# A클러스터 part5 — A-7. 듀얼 데이터소스·캐시·시스템설정·기동 가드·설정 바인딩·Actuator/헬스·Resilience4j

- 대상: `docs/test-cases/A-auth-common.md` A-7절(238행~파일끝), 49건 (TC-DS 4 · TC-CACHE 5 · TC-SYSCFG 9 · TC-PROF 17 · TC-CFG 5 · TC-ACT 2 · TC-HEALTH 3 · TC-RES 4)
- 스택: `docs/검증결과/2026-08-03/3차/_raw/stack-bringup.md` 재사용(재빌드 완료, HEAD `e065da42` 반영, backend `localhost:18081`+`/api`, DB `public` 스키마, KPST/VLM/증강/관제통지 전부 mock-server(:9400) 실배선). 별도 재기동 없음.
- 실동작 검증: 자체 서명(JWT_SECRET 재사용) HS256 토큰으로 REVIEWER(userNo 1001)·WORKER(userNo 2001) 인증 후 `curl` 왕복, DB 직접 조회(`ls_user_role`), 컨테이너 `env` 덤프.

## 판정 표

| ID | 케이스명 | 판정 | 근거 확인 |
|----|----------|:--:|-----------|
| TC-DS-001 | Control DataSource/EMF/TxManager @Primary | PASS | [정적] ControlDataSourceConfig.java:31,38,50 @Primary 3곳 확인. [실동작] `/actuator/health` db.components 에 controlDataSource·portalDataSource 둘 다 UP(PostgreSQL isValid()) — 듀얼 EMF 실제 기동 확인 |
| TC-DS-002 | @ControlRepo만 control EMF 라우팅 | PASS | [정적] includeFilter=ControlRepo (19-28). 저장소 61개 확인(`grep -rl @ControlRepo`) |
| TC-DS-003 | @PortalRepo는 portal EMF 라우팅(비-Primary) | PASS | [정적] includeFilter=PortalRepo, @Primary 없음(47-51) 확인. **실제 사용처 1건**(`PortalDatasetVideoMetaRepository` — 외부 포털 DB 데이터마트 조회)만 @PortalRepo, 반면 신규 `LS_PORTAL_*`(업로드) 5개 저장소는 전부 @ControlRepo — 설계 의도(포털 자산 업로드는 자체 소유 DB, 데이터마트 연동만 진짜 포털 DB)와 정합 |
| TC-DS-004 | 서비스 Tx가 controlTransactionManager 바인딩 | PASS | [근거 드리프트 발견·정정] 구 인용 `SystemConfigService.java:38,91` 은 2026-08-03 커밋 `5a625a87`(이벤트 제외코드 JSON 타입·getStringSet 신설, +50라인)로 드리프트 — 카탈로그를 `54,135`로 정정(아래 카탈로그 정정 참조). 기능 자체는 6개 메서드 모두 `@Transactional(value="controlTransactionManager", ...)` 확인 |
| TC-CACHE-001 | 4개 캐시 개별 TTL 등록 | PASS | [정적] CacheConfig.java:53-56(상수),70-96(Caffeine 스펙) 정확 일치 — sysconfig 60s/100, stream-meta 5m/200, eventType 6h/50, userRole 60s/500 |
| TC-CACHE-002 | sysconfig getInt/getString 60s 캐시 | PASS | [근거 드리프트 정정] 구 `47-48,80-82` → `63-65,96-98`. [실동작] `PUT /v1/manage/configs/POLYGON_SIMPLIFY_TOLERANCE` 로 즉시 반영 확인(아래 참조) — 캐시가 stale 을 영구 고정하지 않음 |
| TC-CACHE-003 | update 시 sysconfig 캐시 전체 무효화 | PASS | [근거 드리프트 정정] 구 `90-91` → `130-136`(@Caching evict 블록). [실동작] `GET /v1/manage/configs` 응답에 `mdfcnDt`가 최근 갱신 시각(예: `eventtype.excluded-class-codes` 2026-08-03T23:54)으로 실제 반영돼 있음 — 무효화 정상 |
| TC-CACHE-004 | userRole null 결과 미저장 | PASS | [정적] UserRoleResolver.java:46 `@Cacheable(..., unless="#result==null")` 정확 일치 |
| TC-CACHE-005 | stream-meta TTL 은 파일 유예삭제의 하한 | PASS(카탈로그 자체 기술) | 카탈로그가 이미 2026-08-03 "[기대결과 정정]"으로 최신화됨(1차 `A-ISSUE-81` 해소, 아래 참조). 재확인 결과 서술과 코드(CacheConfig.java:29-39,58-66) 일치 |
| TC-SYSCFG-001 | 화이트리스트 외 키 조회/갱신 거부 | PASS | [근거 드리프트 정정] 구 `94-98`→`138-142`. [실동작] `PUT /v1/manage/configs/NOT_A_REAL_KEY` → `400 INVALID_INPUT {"message":"허용되지 않은 설정 키입니다."}` (입력 키 미노출, CWE-117 방어 확인) |
| TC-SYSCFG-002 | REVIEWER 아니면 update FORBIDDEN(이중 검증) | PASS | [근거 드리프트 정정] 구 `93,166-170`→`137,256-259`. [실동작] WORKER(2001) 토큰으로 `PUT .../BATCH_INTERVAL_SEC` → `403 FORBIDDEN`. 동일 토큰 `GET /v1/manage/configs` 도 403(Controller `@PreAuthorize` 선차단) |
| TC-SYSCFG-003 | actor=null이면 update FORBIDDEN | PASS(정적) | [근거 드리프트 정정] 구 `166-169`→`256-259`. 코드 경로(`verifyReviewer`)는 컨트롤러를 우회한 내부 호출 방어선이라 HTTP 로 직접 재현 불가 — `SystemConfigServiceTest.java` 존재 확인(회귀 커버) |
| TC-SYSCFG-004 | NUMBER 키 정수+키별 범위 검증 | PASS | [근거 드리프트 정정] 구 `132-145`→`182-195`. [실동작] `PUT .../BATCH_INTERVAL_SEC {"value":"99999"}`(범위 10~3600) → `400 INVALID_INPUT {"message":"값이 허용 범위를 벗어났습니다."}` |
| TC-SYSCFG-005 | DECIMAL NaN/Infinity/범위밖 거부 | PASS | [근거 드리프트 정정] 구 `147-164`→`197-214`. [실동작] `PUT .../POLYGON_SIMPLIFY_TOLERANCE {"value":"NaN"}` → `400 INVALID_INPUT {"message":"유효하지 않은 숫자입니다."}` — `Double.parseDouble("NaN")` 이 예외를 안 던지므로 `isNaN()` 이 실질 방어선이라는 서술과 실동작 일치 |
| TC-SYSCFG-006 | BOOLEAN true/false만 | PASS(정적) | [근거 드리프트 정정] 구 `116-125`→`164-169`. 화이트리스트에 BOOLEAN 타입 키가 현재 없어(ConfigKeys.ALLOWED 9개 전부 NUMBER/DECIMAL/JSON) HTTP 로 직접 재현 불가 — 코드 로직(validateByType switch) 정확 확인 |
| TC-SYSCFG-007 | 미지원 CONFIG_TYPE 거부 | PASS(정적) | [근거 드리프트 정정] 구 `127-128`→`177-178`. default 분기 코드 확인, 화이트리스트 키 전부 지원 타입이라 HTTP 재현 불가 |
| TC-SYSCFG-008 | getInt 타입/값 불일치 각 예외 | PASS(정적) | [근거 드리프트 정정] 구 `49-61`→`62-77`. 코드 확인(타입불일치=INVALID_INPUT, 파싱실패=INTERNAL_ERROR) |
| TC-SYSCFG-009 | 없는 설정 키 NOT_FOUND | PASS(정적) | [근거 드리프트 정정] 구 `110-113`→`154-158`. `loadOrThrow` 코드 확인 |
| TC-PROF-001 | local 프로파일이 ENV=dev/stg/prd에서 부팅 차단 | PASS | [정적] LocalProfileGuard.java:43-55 정확 일치. 재기동 트리거는 위험(공유 스택)해 실제 크래시 재현은 생략, 로직 검증으로 대체 |
| TC-PROF-002 | ENV 미설정/local이면 local 허용 | PASS | [정적] :47-49 정확 일치. 현재 로컬 스택이 실제로 이 경로로 정상 기동해 간접 실증(SPRING_PROFILES_ACTIVE=local, ENV 미설정) |
| TC-PROF-003 | local 아니면 즉시 통과 | PASS | [정적] :44-46 정확 일치 |
| TC-PROF-004 | DevProfileGuard — dev+ENV=stg/prd 기동 거부 | PASS | [정적] :49-62 정확 일치. DEPLOYED_ENVS = DeployedEnvironmentDetector.DEPLOYED_ENV_MARKERS 위임 확인(복제 없음) |
| TC-PROF-005 | DevToggleProfileGuard — prd/stg dev로그인 활성 시 거부 | PASS | [정적] :37,42-65 정확 일치 |
| TC-PROF-006 | ForwardedHeadersConfigGuard — 3키 전 프로파일 거부 | PASS | [정적] :49-66,82-113 정확 일치(48-66 상수/66 FORBIDDEN_KEYS 리스트, 82-92 configuredForbiddenKeys, 100-113 verify) |
| TC-PROF-007 | DeidentifyEndpointTrustGuard 프로파일별 처리 | PASS | [정적] :58-62,82-127,188-198 정확 일치. [실동작] 현재 로컬 스택이 실제로 `kpst.deid.base-url=http://klid-mock-server:9400`(UNTRUSTED_HOSTS 대상, "mock" 토큰 포함) 로 WARN 형상 — local 이라 무음 처리(로그 확인 안 함, local 분기 코드로 검증) |
| TC-PROF-008 | 호스트 파싱 불가 시 fail-secure + authority 폴백 | PASS | [정적] :106-123,139-177 정확 일치 |
| TC-PROF-009 | QuartzClusteringGuard — stg/prd 클러스터링 강제 | PASS | [정적] application-stg.yml:11·application-prd.yml:13 `${QUARTZ_CLUSTERED:true}` 정확 일치, 공통 application.yml:95 `${QUARTZ_CLUSTERED:false}` 대비 확인. QuartzClusteringGuard.java 인용(50-56,64-95)은 실제 49-55,63-94 로 1~2줄 오차(경미 — 상수/메서드 시작 위치 오프셋) |
| TC-PROF-010 | allowlist containsAll + ENV 독립 축 | PASS | [정적] QuartzClusteringGuard.java:101-103(singleNodeAllowed) 정확 일치. DeployedEnvironmentDetector.java:63-79(isDeployed+deployedEnvMarker) 정확 일치 |
| TC-PROF-011 | ProfileGatedUrlPolicy 완화 인정 조건 | PASS | [정적] :45-51(약간의 오프셋, 실제 44-54),78-94(실제 81-94),138-156(실제 138-156 정확) — 전반적으로 일치, 사소한 서두 오프셋만 |
| TC-PROF-012 | 기동 assert 우회해도 판정 불변(이중 방어) | PASS | [정적] :124-136 정확 일치(check/validate/policy) |
| TC-PROF-013 | ExternalUrlPolicy strict — https전용+사설차단 | PASS | [정적] :90-133,168-188 정확 일치(check() 본문, requirePublicNetwork) |
| TC-PROF-014 | 완화 경로도 링크로컬/메타데이터 거부 | PASS | [정적] :123-127,143-166 정확 일치 |
| TC-PROF-015 | placeholder 호스트 어떤 정책에서도 차단 | PASS | [정적] :43-47,117-122 정확 일치 |
| TC-PROF-016 | 평문 http+토큰 시 경고(값 미출력) | PASS | [정적] ProfileGatedUrlPolicy.java:107-116 정확 일치, WebClientConfig.java:67-70 정확 일치(vlmWebClient 토큰 분기) |
| TC-PROF-017 | GenAiIntegrationWiringGuard 위탁↔콜백 배선 짝 | PASS | [정적] :35-39,52-85 정확 일치. [실동작] 현재 스택 env `AUGMENT_EXTERNAL_MODE=http` — allowlist 미설정이면 기동 자체가 실패했을 것이나 정상 기동 중이므로 `webhook.genai.allowed-ip-cidrs` 가 설정돼 있음을 간접 확인(가드 통과 실증) |
| TC-CFG-001 | prd hikari 20/5 평면 바인딩 | PASS | [정적] application-prd.yml:2-5,14-28 정확 일치, ControlDataSourceConfig.java:32-36(controlDataSource 빈) 정확 일치. `DataSourcePoolBindingTest.java` 존재 확인(테스트 실행은 baseline 담당 범위, 미실행) |
| TC-CFG-002 | multipart 한도 prd 21MB/1100MB vs 공통 500MB/1200MB | PASS | [정적] application-prd.yml:29-39, application.yml:14,28-29 정확 일치. `ConfigProfileDriftGuardTest.prdMultipartLimitsStayTight`/`nonProdMultipartLimitsComeFromCommon` 코드로 확인(회귀 가드 존재, 미실행) |
| TC-CFG-003 | dev 프로파일 내부 IP 리터럴 없음 | PASS | [정적] `ConfigProfileDriftGuardTest.java:87-105`(실제 87-104, 1줄 오차) `devProfileHasNoHardcodedIpLiteral` 확인 |
| TC-CFG-004 | dev 토글 override / stg dev로그인 리터럴 false | PASS | [정적] application-dev.yml:23-28, application-stg.yml:51-56 정확 일치(`${DEV_LOGIN_ENABLED:true}` vs 리터럴 `false`) |
| TC-CFG-005 | ENV 배포 표식 온프렘 템플릿 주입 | PASS | [정적] `deploy/onprem/config/backend/env.template:25` `ENV=prd` 정확 일치 |
| TC-ACT-001 | prd는 actuator health만 노출 | PASS | [정적] application-prd.yml:93-100 정확 일치(`include: health`, `show-details: never`) |
| TC-ACT-002 | 기본은 ACTUATOR_EXPOSE로 노출 제어 | PASS(단 아래 A-ISSUE-81 참조) | [정적] application.yml:133-140 정확 일치. [실동작] 무인증 `/actuator/health,info`=200, `/actuator/metrics,beans,env,heapdump,prometheus`=401(REVIEWER 토큰 필요). REVIEWER 토큰으로 `metrics,prometheus`=200, `env,beans,heapdump`=404(미노출) — exposure 설정과 인가 결합이 기대대로 동작. **단 반증 과정에서 `/actuator/health` 자체의 상세노출 범위가 역할 무관임을 발견(별도 이슈 기록, 아래)** |
| TC-HEALTH-001 | 비식별 헬스 — mock 모드 핑 없이 UP | PASS(정적) | [근거] :77-83(실제 78-83, 1줄 오차) 확인. 현재 스택은 `DEIDENTIFY_MOCK_MODE=false`(KPST 모드)라 mock 분기 실동작 재현은 안 됨 — 코드 로직으로 확인 |
| TC-HEALTH-002 | 비식별 헬스 — KPST 형상은 root(`/`) 핑 | PASS | [정적] :44,70-73,92-112 정확 일치. **[실동작]** REVIEWER 토큰 `/actuator/health` 응답에 `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}` 확인 — `kpst.deid.base-url=http://klid-mock-server:9400` 의 루트(`/`)를 실제로 2초 타임아웃 핑해 성공한 결과. mock-server 헬스 자체가 아니라 **루트 경로 실핑** 임을 실측으로 확인(케이스 핵심 주장 실증) |
| TC-HEALTH-003 | 비식별 헬스 — 미구성은 DOWN(fail-closed) | PASS(정적) | [근거] :84-91 정확 일치. 현재 스택은 kpst.deid.enabled=true 라 이 분기 실동작 재현 안 됨(코드로 확인) |
| TC-RES-001 | 외부 호출 Retry+CircuitBreaker 순차 적용 | PASS | [정적] AiServerClient.java:46-47(predictYolo),63-64(predictYoloTrack) 정확 일치. 5개 메서드(predictYolo·predictYoloTrack·segment·track·verifyObjects) 전부 동일 패턴 직접 확인(카탈로그의 2026-08-03 정정 내용과 일치) |
| TC-RES-002 | 5개 CircuitBreaker 빈 등록 | PASS | [정적] Resilience4jConfig.java:13-47 정확 일치(deid/ai/vlmClient/controlNotify/kpstDeid 5개 빈). [실동작] `/actuator/health` 에 aiServerHealth·controlNotifyHealth·deidentifyHealth 컴포넌트가 실제 UP 으로 나타나 해당 CB 경유 클라이언트들이 실제 배선돼 동작 중임을 간접 확인 |
| TC-RES-003 | aiOnline Bulkhead 초과 시 429 | PASS(정적) | [정적] Resilience4jConfig.java:55-58(aiOnlineBulkhead 빈), AutolabelOnlineService.java:499(YOLO callYolo)·529(SAM callSam) 두 호출부 모두 `BulkheadOperator.of(aiOnlineBulkhead)` 공유 확인(카탈로그 2026-08-03 정정 내용과 일치, `PortalSam2Service` 부재도 확인: `find`로 0건). 동시 초과 트리거는 실제 오토라벨 파이프라인 데이터 준비가 필요해 이번 파트에서는 미실시 |
| TC-RES-004 | NonRetryableExternalException 재시도/CB 제외 | PASS | [정적] NonRetryableExternalException.java:3-9 정확 일치. application.yml 내 `ignore-exceptions: - ...NonRetryableExternalException` 12개소(모든 resilience4j retry/circuitbreaker 인스턴스) 전부 등록 확인(`grep -n`) |

## 카탈로그 정정 (A-7 범위 내 직접 Edit, 12건)

`docs/test-cases/A-auth-common.md` A-7절에서 **`SystemConfigService.java` 근거 라인 전체가 드리프트**돼 있었다. 원인: 2026-08-03 커밋 `5a625a87`(feat(eventtype): 이벤트 제외 대분류 코드를 REVIEWER 설정화면에서 편집 가능하게 이관, 13:57)이 JSON 타입 지원·`getStringSet`·`validateExcludedClassCodes`·`parseStringArray` 등을 신설하며 파일이 약 50라인 늘어났는데, 카탈로그의 "근거 file:line 전수 재확인 4회차" 커밋 `9f99db50`(같은 날 17:38, 즉 그 이후)이 **이 파일만 재확인 대상에서 누락**했다(회차 변경이력에 JwtAuthenticationFilter/CacheConfig/SortAllowlist/GlobalExceptionHandler/QuartzClusteringGuard/ProfileGatedUrlPolicy 는 명시됐으나 SystemConfigService 는 없음).

정정한 행: TC-DS-004, TC-CACHE-002, TC-CACHE-003, TC-SYSCFG-001~009 (총 12행). 각 행에 `**[근거 정정 2026-08-04]**` 표기 + 신규 라인 + "(구 N-M 드리프트)" 사유를 남겼다(파일은 이미 갱신 완료, 위 판정 표의 근거 확인 열에도 반영). 기능 자체는 전부 정상 동작(실동작으로 재확인) — **드리프트는 카탈로그 문서 정합성 결함이며 코드 결함이 아니다.**

미세 오차(1~2라인, 수정 보류): TC-PROF-009(QuartzClusteringGuard.java 실제 49-55,63-94 vs 카탈로그 50-56,64-95), TC-PROF-011(ProfileGatedUrlPolicy.java 서두 오프셋), TC-CFG-003(ConfigProfileDriftGuardTest.java 87-105 vs 실제 87-104), TC-HEALTH-001(77-83 vs 실제 78-83), TC-RES-003(AutolabelOnlineService.java 501-505/531-535 vs 실제 catch 블록 502-505/533-535) — 전부 인용 범위 안에 실제 코드가 포함돼 있어 검증에 지장 없는 수준이라 정정하지 않음.

## 신규 이슈

### [A-ISSUE-81] TC-ACT-002 — `/actuator/health` 상세 노출이 REVIEWER 전용 의도를 벗어나 인증된 모든 역할(WORKER 포함)에 노출됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `SecurityConfig.java`(85-86행 permitAll `/actuator/health`·`/actuator/info`, 118행 `.requestMatchers("/actuator/**").hasRole(Role.REVIEWER.name())`)의 주석("actuator metrics/prometheus 는 REVIEWER 만")과 `.claude/rules/observability.md`("운영 환경에서 민감 엔드포인트 노출 금지")의 취지는 **actuator 내부 상세 정보는 REVIEWER(관리자 동급)만 봐야 한다**는 것. `application.yml:140` 의 `show-details: when-authorized` 도 이 의도를 뒷받침하는 설정으로 보인다.
- **현재 동작(이슈 내용)**: Spring Boot 의 `when-authorized` 는 기본적으로 `management.endpoint.health.roles` 가 **비어 있으면 "인증 여부"만 확인**하고 **역할(role)은 보지 않는다**. 이 프로젝트는 `management.endpoint.health.roles` 를 설정하지 않았다(`grep -n "roles" application*.yml` → 0건). 그 결과 `/actuator/health` 는 SecurityConfig 에서 permitAll 이라 REVIEWER 게이트(`hasRole(REVIEWER)`)를 아예 거치지 않고, Boot 자체 판정도 "인증되면 상세 노출"이라 **WORKER 역할 토큰으로도 REVIEWER 전용으로 의도된 내부 상세가 전부 노출**된다.
  실동작 근거(WORKER, userNo=2001, role=WORKER 토큰):
  ```
  GET /api/actuator/health (WORKER 토큰)
  → 200 {"status":"UP","components":{
      "aiServerHealth":{...,"details":{"service":"ai-server"}},
      "controlNotifyHealth":{...,"details":{"service":"control-notify"}},
      "db":{...,"components":{"controlDataSource":{...,"details":{"database":"PostgreSQL","validationQuery":"isValid()"}},"portalDataSource":{...}}},
      "deidentifyHealth":{...,"details":{"service":"deidentify","mode":"kpst"}},
      "diskSpace":{...,"details":{"total":485473984512,"free":...,"path":"/app/.","exists":true}},
      ...}}
  ```
  같은 WORKER 토큰으로 `GET /api/actuator/metrics` 는 정상적으로 `403 FORBIDDEN`(hasRole(REVIEWER) 게이트가 실제로 동작) — 즉 **actuator 내에서 `/health` 만 역할 게이트를 우회**하는 비대칭이 실측됐다.
- **재현/확인 경로**:
  ```bash
  # WORKER(userNo=2001) HS256 토큰으로
  curl -H "Authorization: Bearer $WORKER_TOKEN" http://localhost:18081/api/actuator/health   # → 200 + 전체 컴포넌트 상세
  curl -H "Authorization: Bearer $WORKER_TOKEN" http://localhost:18081/api/actuator/metrics   # → 403 (대조군, 정상)
  ```
- **영향**: CWE-200(민감하지 않은 수준이지만 내부 아키텍처 정보 노출) / CWE-862(의도된 인가 경계 미스매치). 노출 정보 자체(컨테이너 내부 경로 `/app/.`, DB 벤더/검증쿼리, 외부연동 모드 `kpst`/`mock` 등)는 비밀·PII 는 아니라 즉각적 악용 난이도는 낮으나, "actuator 상세=REVIEWER 전용" 이라는 코드 스스로의 의도(SecurityConfig 주석)를 무력화하고, WORKER 도 이를 통해 내부 배치/외부연동 형상(비식별이 mock 인지 kpst 인지 등)을 정찰할 수 있다. **local/dev/stg 에서만 유효** — prd 는 `show-details: never` 로 이미 차단(application-prd.yml:100).
- **수정 방향(제안)**: `application.yml` 의 `management.endpoint.health.roles: REVIEWER` 를 명시해 Boot 의 `when-authorized` 판정축을 REVIEWER 역할로 좁히거나(Spring Security `SecurityContext.isUserInRole` 기반), 그게 어렵다면 `SecurityConfig` 에서 `/actuator/health` 도 REVIEWER 게이트 뒤로 옮기고 헬스체크(k8s liveness/readiness) 용은 별도 경량 엔드포인트로 분리. prd 는 이미 `never` 라 영향 없음.

## 이전 회차 이슈 해소 확인

- **1차 `A-ISSUE-81`(TC-CACHE-005, `docs/검증결과/2026-08-01/1차/ISSUES.md:616`)**: "stream-meta TTL 기동 시 검증됨" 서술이 사실과 다르다는 지적 — **해소 확인됨**. 현재 카탈로그(A-auth-common.md:250)가 이미 "[기대결과 정정 2026-08-03]"으로 갱신되어 "현재 이 규칙을 쓰는 흐름도, 강제하는 검증도 없다"로 정확히 기술돼 있고, 코드(`CacheConfig.java:29-39,58-66`)와도 일치. 이번 3차 회차에서 별도 조치 불필요.
- 본 파트(A-7) 범위에서 1차 문서의 다른 이슈(actuator/resilience4j/datasource 관련)는 추가로 발견되지 않았음 — 1차 ISSUES.md 전체 grep 결과 A-7 관련 항목은 위 TC-CACHE-005 1건뿐.

## 미실시/한계

- Resilience4j 강제 타임아웃 유발(mock-server 지연 주입, ai-server 중단 등)은 **다른 병렬 검증 에이전트와 공유 중인 스택을 훼손할 위험**이 있어 이번 파트에서 보류 — 대신 `/actuator/health` 의 실제 CB 경유 컴포넌트 UP 상태로 간접 확인. 강제 재현이 필요하면 전용 격리 스택에서 별도 수행 권고.
- 기동 가드류(TC-PROF-001~017, TC-CFG-005)의 "기동 거부" 자체는 실제 재기동(잘못된 설정 주입)으로 재현하지 않고 코드 로직 검증으로 대체(공유 스택 보호). 판정근거 열에 [정적] 표기.
- `_raw/test-baseline.md` 가 이번 회차에 아직 없어 `SystemConfigServiceTest`/`DataSourcePoolBindingTest`/`QuartzClusteringGuardTest`/`ConfigProfileDriftGuardTest`/`DeidentifyHealthIndicatorTest`/`GenAiIntegrationWiringGuardTest`/`DeidentifyEndpointTrustGuardTest`/`AiServerClientTest`/`DeployedEnvironmentDetectorTest` 존재는 확인했으나 실제 통과 여부는 대조하지 못함(빌드/테스트 실행 금지 지시 준수).
