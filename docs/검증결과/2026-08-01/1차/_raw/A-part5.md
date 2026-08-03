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
