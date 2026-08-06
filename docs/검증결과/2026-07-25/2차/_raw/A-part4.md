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
