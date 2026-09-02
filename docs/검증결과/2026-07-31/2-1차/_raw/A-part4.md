# A-part4 — A-7. 듀얼 데이터소스·캐시·시스템설정·기동 가드·설정 바인딩·Actuator/헬스·Resilience4j (49건)

- 담당 범위: `docs/test-cases/A-auth-common.md` "## A-7." 섹션(line 234~파일 끝) 전체 49건
- 이슈 ID 시작번호: A-ISSUE-81~
- 검증 방식: 실동작(가능 범위: 기동된 로컬 스택 HTTP/DB/컨테이너 env 조회) 우선 → 정적 대조(file:line Read) → 테스트커버 Grep
- 참고: `_raw/stack-bringup.md`(기동/배선 실측 기존 기록) 재사용, 코드/설정/컨테이너 변경 없음, 빌드·테스트 미실행

## 판정 결과 표

| ID | 판정 | 근거확인 | 근거 요약 |
|----|:--:|------|------|
| TC-DS-001 | PASS | [정적] | `ControlDataSourceConfig`의 `controlDataSource`/`controlEntityManagerFactory`/`controlTransactionManager` 3개 빈 모두 `@Primary` 확인(파일 19-56행) |
| TC-DS-002 | PASS | [정적] | `@EnableJpaRepositories`의 `includeFilters`가 `ControlRepo` 애노테이션 필터 + `entityManagerFactoryRef=controlEntityManagerFactory`/`transactionManagerRef=controlTransactionManager` 확인 |
| TC-DS-003 | PASS | [정적] | `PortalDataSourceConfig`는 `PortalRepo` 필터 + `portalTransactionManager`이며 3개 빈 어디에도 `@Primary` 없음(파일 전체 확인) |
| TC-DS-004 | PASS | [정적] | `SystemConfigService.java:38`(`listAll`) · `:91`(`update`) 모두 `@Transactional(value="controlTransactionManager", ...)` 명시 |
| TC-CACHE-001 | PASS | [정적] | `CacheConfig.cacheManager()`(68-97행): sysconfig 60s/100, stream-meta 5m(`STREAM_META_TTL`)/200, eventType 6h/50, userRole 60s/500 — 근거 라인(51-54,63,65-94)은 실제 53-56·66·68-97과 소폭 드리프트(±2~3행)지만 값은 카탈로그와 완전 일치 |
| TC-CACHE-002 | PASS | [정적] | `getInt`/`getString`에 `@Cacheable(cacheNames="sysconfig", key="'int:'+#key"/"'str:'+#key")` 확인. 전용 반복조회 테스트는 별도 파일 미발견(`SystemConfigServiceTest` 없음) — 캐시 적중 자체를 직접 검증하는 테스트커버는 확인 안 됨(구현은 일치) |
| TC-CACHE-003 | PASS | [실동작 커버][정적] | `update()`에 `@CacheEvict(cacheNames="sysconfig", allEntries=true)`. 테스트 `SystemConfigControllerTest#SystemConfig_REVIEWER_BATCH_INTERVAL_SEC_업데이트시_캐시_무효화`로 커버 확인(파일 66-90행) |
| TC-CACHE-004 | PASS | [정적] | `UserRoleResolver.java:46` `@Cacheable(cacheNames=CACHE_USER_ROLE, key="#userNo", unless="#result==null")` 정확 일치. `UserRoleResolverTest` 커버 확인 |
| TC-CACHE-005 | PARTIAL | [정적] | TTL 5분 값 자체는 일치하나, 기대결과의 "기동 시 검증됨"은 **더 이상 사실이 아님** — 이 하한을 기동 시 검증하던 유일한 소비자 `ResolutionBackfillService`(+ 스윕잡)가 2026-07-30 기능 전체 제거(UNCERTAINTIES.md #29~31). `CacheConfig.java:36-39` 주석도 "지금은 이 규칙을 쓰는 흐름이 없다"고 명시. `stale-grace-minutes`/`ResolutionBackfillService` 문자열은 `CacheConfig.java` 주석에만 남아있고 실행 코드/기동 검증 로직은 0건(Grep 확인) → 카탈로그 근거 드리프트, A-ISSUE-81 |
| TC-SYSCFG-001 | PASS | [정적] | `SystemConfigService.update():94-98` 화이트리스트 위반 시 키를 메시지에 넣지 않고 고정 문구 반환(CWE-117). 테스트 `SystemConfig_화이트리스트_외_키_업데이트시_INVALID_INPUT` 커버 |
| TC-SYSCFG-002 | PASS | [실동작 커버][정적] | `verifyReviewer()`(166-169행) WORKER→FORBIDDEN. 테스트 `SystemConfig_WORKER가_configs_PUT_호출시_403` 존재 |
| TC-SYSCFG-003 | PASS | [정적] | `verifyReviewer(actor)`: `actor==null` 조건 포함 → FORBIDDEN(동일 라인) |
| TC-SYSCFG-004 | PASS | [실동작 커버][정적] | `validateNumberRange()`(132-145행) `ConfigKeys.NUMBER_RANGE` 대조. 테스트 `SystemConfig_BATCH_CONCURRENCY_범위_초과시_INVALID_INPUT_400` 존재 |
| TC-SYSCFG-005 | PASS | [정적] | `validateDecimalRange()`(147-164행) `Double.parseDouble` 후 `isNaN`/`isInfinite` 명시 방어 확인(파싱 자체는 NaN/Infinity 문자열도 통과하므로 이 체크가 실질 방어선이라는 근거 서술과 일치) |
| TC-SYSCFG-006 | PASS | [정적] | BOOLEAN 분기(120-125행) true/false(대소문자 무관) 외 거부 |
| TC-SYSCFG-007 | PASS | [정적] | `default` 분기(127-128행) 미지원 타입 INVALID_INPUT |
| TC-SYSCFG-008 | PASS | [정적] | `getInt()`(49-61행) 타입 불일치 INVALID_INPUT, `NumberFormatException`→INTERNAL_ERROR 분리 확인 |
| TC-SYSCFG-009 | PASS | [정적] | `loadOrThrow()`(110-113행) 없는 키 NOT_FOUND |
| TC-PROF-001 | PASS | [정적] | `LocalProfileGuard.verify(String)`: active=local + ENV=dev/stg/prd → `IllegalStateException`(43-55행) |
| TC-PROF-002 | PASS | [정적] | ENV null/blank/local → early return(47-49행). `LocalProfileGuardTest` 커버 |
| TC-PROF-003 | PASS | [정적] | `env.acceptsProfiles(Profiles.of("local"))`이 false면 즉시 return(44-46행) |
| TC-PROF-004 | PASS | [정적] | `DevProfileGuard`: dev 프로파일 + ENV∈{stg,prd} → 거부(48-60행). qa 등 미지 라벨/미설정/blank/local/dev는 통과. `DevProfileGuardTest` 커버 |
| TC-PROF-005 | PASS | [정적] | `DevToggleProfileGuard.verify(boolean,boolean)`: prd/stg 활성 + devLoginEnabled=true → 거부(59-65행). `application-stg.yml`의 `authoring.dev.login.enabled`는 리터럴 `false`(env var 오버라이드 불가) — `ConfigProfileDriftGuardTest#stg_dev로그인은_켤_수_없는_리터럴_false다`로 이중 확정 |
| TC-PROF-006 | PASS | [정적] | `ForwardedHeadersConfigGuard.configuredForbiddenKeys()`(82-92행) 3키 중 하나라도 비어있지 않으면 검출 → `verify()`(100-113행) 예외. `Environment` 경유라 환경변수(relaxed binding) 포함 전 프로퍼티 소스 커버. `ForwardedHeadersConfigGuardTest` 존재(7 테스트) |
| TC-PROF-007 | PASS | [정적] | `DeidentifyEndpointTrustGuard.verify()`(82-97행): mock-mode 또는 목/루프백 호스트 시 prd=거부/stg·dev=WARN/local=무음. 판정은 `untrustedReason()` 단일 지점 |
| TC-PROF-008 | PASS | [정적] | `hostOf()`(139-153행) `URI.getHost()` null 시 `hostFromAuthority()` 폴백(159-177행)으로 언더스코어 호스트도 동일 deny 판정 유지. 폴백도 실패 시 null(비신뢰) |
| TC-PROF-009 | PASS | [실동작 커버][정적] | `QuartzClusteringGuard.verify()`(85-95행) stg/prd+false→거부. `application-stg.yml:11`/`application-prd.yml:11`(코드상 정확히는 prd 파일 12행대) 모두 `${QUARTZ_CLUSTERED:true}` 기본값 확인(grep 실측). `QuartzClusteringGuardTest`+`QuartzClusteringConfigGuardTest`+`QuartzClusteringBootGuardTest` 3중 커버 |
| TC-PROF-010 | PASS | [정적] | `singleNodeAllowed()`(98-103행) `SINGLE_NODE_PROFILES.containsAll(activeProfiles)` — 혼합(`local,prd`)·대문자·오타·미지정 전부 allowlist 불일치로 엄격 처리. ENV 축은 `deployedEnvMarker()` 독립 판정 |
| TC-PROF-011 | PASS | [정적] | `ProfileGatedUrlPolicy.verifyRelaxationScope()`(78-91행) `@PostConstruct`에서 완화 플래그 ON + 비allowlist 프로파일 → 기동 실패. `VlmUrlPolicyTest` 13개 테스트로 커버 |
| TC-PROF-012 | PASS | [정적] | `policy()`(131-133행)가 `check()`/`validate()` 호출마다 재판정 — assert 우회해도 이중 방어 |
| TC-PROF-013 | PASS | [정적] | `ExternalUrlPolicy.strict()`→`Transport.HTTPS_ONLY`+`blockPrivateNetwork=true`. `check()`(90-133행) http 거부(107-111행), `requirePublicNetwork()`(169-188행) DNS 해석 실패도 예외(174행), 메시지에 baseUrl 원문 미포함(scheme/host만) |
| TC-PROF-014 | PASS | [정적] | `internalNetwork()` 정책의 `rejectMetadataRangeIfResolvable()`(143-153행): 링크로컬/169.254 거부하되 `resolveQuietly()`(156-166행) 해석 실패는 통과(예외 없음) — "해석되면 검사, 안 되면 통과" 정확 구현 |
| TC-PROF-015 | PASS | [정적] | `PLACEHOLDER_HOST_FRAGMENTS`(43-47행) 포함 시 두 정책 공통으로 거부(117-122행, 정책 분기 이전 공통 체크) |
| TC-PROF-016 | PASS | [정적] | `ProfileGatedUrlPolicy.warnIfTokenOnCleartext()`(104-113행) http+token 시 WARN, `tokenLength`만 로깅. `WebClientConfig.vlmWebClient()`(63-67행 부근)에서 실제 호출 확인 |
| TC-PROF-017 | PASS | [정적] | `GenAiIntegrationWiringGuard.verify()`(65-76행) http모드+allowlist 미설정/none → 거부. `GenAiIntegrationWiringGuardTest`(6 테스트) 커버 |
| TC-CFG-001 | PASS | [정적] | `DataSourcePoolBindingTest#운영_prd_yml의_풀설정이_실제_바인딩되는_형식으로_선언돼있다`가 실제 `application-prd.yml`을 로드해 `ControlDataSourceConfig`/`PortalDataSourceConfig`가 만드는 `HikariDataSource` 인스턴스에 바인딩 후 `maximumPoolSize=20`/`minimumIdle=5` 단언(76-90행). yml 값도 prefix 직하 평면(`spring.datasource.control.maximum-pool-size: 20` 등) 확인 |
| TC-CFG-002 | PASS | [실동작 확인][정적] | `application-prd.yml:37-39` `max-file-size:21MB`/`max-request-size:1100MB`, 공통 `application.yml` 500MB/1200MB(grep 실측 일치). `ConfigProfileDriftGuardTest#prdMultipartLimitsStayTight`/`#nonProdMultipartLimitsComeFromCommon`이 회귀 고정 |
| TC-CFG-003 | PASS | [정적] | `ConfigProfileDriftGuardTest#dev_프로파일에_내부IP_기본값이_남아있지_않는다`가 IPv4 정규식으로 `application-dev.yml` 전체를 스캔해 0건 단언(87-104행) |
| TC-CFG-004 | PASS | [정적] | `ConfigProfileDriftGuardTest#dev토글은_환경변수로_override_가능하다`(dev/stg 업로드 토글 `${DEV_UPLOAD_ENABLED` 확인) + `#stg_dev로그인은_켤_수_없는_리터럴_false다`(stg 로그인 리터럴 false) 양쪽 테스트로 정확히 커버 |
| TC-CFG-005 | PASS | [실동작] | `deploy/onprem/config/backend/env.template:25` 에 `ENV=prd` 실측 확인(grep) |
| TC-ACT-001 | PASS | [정적] | `application-prd.yml:93-100` `management.endpoints.web.exposure.include: health` + `show-details: never` 정확 일치 |
| TC-ACT-002 | PASS | [실동작][정적] | `application.yml:133-140` `include: ${ACTUATOR_EXPOSE:health,info,metrics,prometheus}` + `show-details: when-authorized` 확인. 로컬 실기동 curl: `/actuator/health`→200(UP), `/actuator/info`→200, `/actuator/metrics`·`/actuator/prometheus`→401(비인증) — `SecurityConfig.java:118` `.requestMatchers("/actuator/**").hasRole(REVIEWER)`가 노출 설정과 별개로 인가를 요구하는 추가 방어층으로, 노출 자체는 카탈로그 기대와 일치(401은 결함 아님 — 노출 여부와 인가 여부는 별개 축) |
| TC-HEALTH-001 | PASS | [정적] | `DeidentifyHealthIndicator.health()`(77-83행) mock-mode=true → 핑 없이 UP(mode=mock). 현재 로컬 실효 설정은 `DEIDENTIFY_MOCK_MODE=false`(KPST 경로)라 이 분기는 이번 세션에서 실동작 미경유 — 코드 로직만 정적 확인 |
| TC-HEALTH-002 | PASS | [실동작][정적] | 실효 env 확인: `KPST_DEID_ENABLED=true`, `DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400` — 카탈로그가 명시한 "KPST 형상" 그대로. 실제 `GET /actuator/health`→`{"status":"UP"}` 확인 + `GET http://localhost:9400/`(mock-server root)→200 확인, 판정축(`kpstWebClient.get().uri("/")...`)과 일치하는 결과. `mode=kpst` detail은 `show-details=when-authorized`라 비인증 응답엔 미노출(코드상 존재는 92-104행에서 확인) |
| TC-HEALTH-003 | PASS | [정적] | `!kpstEnabled \|\| kpstWebClient==null` → DOWN(`mode=unconfigured`, `error=NoDeidentifyPathConfigured`)(84-91행). 현재 환경은 kpstEnabled=true라 이 분기 실동작 미경유 — 코드 로직 확인 |
| TC-RES-001 | PASS | [정적] | `AiServerClient`의 4개 메서드(`predictYolo`/`predictYoloTrack`/`segment`/`track`/`verifyObjects` — 실제로는 5개) 모두 `.transformDeferred(RetryOperator...)` → `.transformDeferred(CircuitBreakerOperator...)` 순서로 동일 패턴 |
| TC-RES-002 | PASS | [정적] | `Resilience4jConfig`에 `deidCircuitBreaker`/`aiCircuitBreaker`/`vlmClientCircuitBreaker`/`controlNotifyCircuitBreaker`/`kpstDeidCircuitBreaker` 5개 빈 정확히 등록(13-47행) |
| TC-RES-003 | PASS | [정적] | `AutolabelOnlineService.java:499-502`(폴리곤 SAM: 529-532도 동일 패턴), `PortalSam2Service.java:201-204` 모두 `BulkheadFullException` catch → `ErrorCode.TOO_MANY_REQUESTS`(429) 변환 확인. `Resilience4jConfig`에 `aiOnlineBulkhead`/`portalSam2Bulkhead` 빈 등록(55-69행) |
| TC-RES-004 | PASS | [정적] | `NonRetryableExternalException`은 마커 예외이며, `application.yml`에 7곳(506,517,526,535,552,562,569,578행 등) `ignore-exceptions: [kr.co.cudo.authoring.common.client.NonRetryableExternalException]` 실배선 확인 |

## 집계

| 판정 | 건수 |
|---|--:|
| PASS | 48 |
| PARTIAL | 1 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **49** |

## 이슈

### [A-ISSUE-81] TC-CACHE-005 — stream-meta TTL "기동 시 검증됨" 기대결과가 더 이상 사실과 다름(카탈로그 근거 드리프트)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그 기대결과는 "구 파일 유예(`stale-grace-minutes`)가 TTL(5분)보다 길어야 하며 **기동 시 검증됨**"이라고 서술 — 즉 2노드 Active-Active 환경에서 프로세스 로컬 Caffeine evict 미전파를 보완하는 파일 유예 규칙이 기동 시점에 자동 검증돼야 한다는 것.
- **현재 동작(이슈 내용)**: 이 검증을 수행하던 유일한 소비자 `ResolutionBackfillService`(및 그 스윕잡)가 2026-07-30 커밋에서 **기능 전체 제거**됐다(`UNCERTAINTIES.md` #29~31 "해소(2026-07-30) — 기능 전체 제거"). 현재 `CacheConfig.java:36-39` 주석: "지금은 이 규칙을 쓰는 흐름이 없다 — 유일한 사용처였던 해상도 파생 저장소 이관 백필이 2026-07-30 제거됐다." 저장소 전체에 `stale-grace-minutes`/`ResolutionBackfillService` 문자열을 grep한 결과 `CacheConfig.java` 주석 안에만 남아 있고, 실행 코드·기동 검증 로직은 0건이다.
- **재현/확인 경로**: `grep -rn "stale-grace-minutes\|ResolutionBackfillService" backend/src/main/java` → `CacheConfig.java` 주석만 매치. `find backend/src/main/java -iname "ResolutionBackfillService.java"` → 결과 없음.
- **영향**: 기능적 결함은 아님(TTL 값 5분 자체는 정확히 설정돼 있고, 파일 유예 삭제 흐름 자체가 현재 존재하지 않아 위반할 대상도 없음). 다만 카탈로그의 기대결과 문구가 존재하지 않는 기동 검증을 전제하므로, 향후 이 규칙을 사용하는 파일 이관 흐름이 새로 생길 때 "검증이 이미 있다"고 오인해 재도입을 누락할 위험이 있음.
- **수정 방향(제안)**: 코드 수정 아님 — `docs/test-cases/A-auth-common.md` TC-CACHE-005 기대결과 문구를 "현재는 유예 삭제 흐름이 없어 검증 대상도 없음(2026-07-30 `ResolutionBackfillService` 제거로 소멸) — 재도입 시 기동 검증을 함께 되살릴 것"으로 갱신 제안.

## 참고 — 확정 정책/미해소 우려 대조

- A-7 범위에서 UNCERTAINTIES.md 확정 정책(★1~★3) 해당 케이스 없음(신고 게이트·정렬·좌표검증은 다른 클러스터).
- UNCERTAINTIES.md #6(RoleHierarchy 빈 계층)은 A-7이 아닌 A 클러스터 다른 섹션 소관으로 본 파트 범위 밖.
- TC-PROF-005 관련 A-ISSUE-05(구 이슈, DevToggleStartupWarner WARN-only)는 `DevToggleProfileGuard` 도입으로 이미 해소된 것으로 코드상 확인(부팅 fail-fast 배선 확인) — 재이월 불필요.
