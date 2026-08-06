# A 클러스터 part3 (A-5·A-6) 2차 검증 결과

> 대상: `docs/test-cases/A-auth-common.md` **A-5**(TC-CLAIM-001~021, 21건) + **A-6**(TC-TRACE/RESP/EXC/LOG/SORT/BLANK, 39건) = **60건**
> 검증 일시: 2026-07-31 03:00~03:11 KST (UTC 2026-07-30 18:00~18:11)
> 실행 형상: backend `klid-backend:latest` — 이미지 빌드 `2026-07-30T17:42:35Z`(=**2026-07-31 02:42 KST**), 컨테이너 기동 `02:43:01 KST`.
> **`stack-bringup.md` §2 의 "이미지가 HEAD 대비 11커밋 뒤처짐" 제약은 이번 회차에 소멸했다** — 405 정규화(`476bc91a`)·`ASSIGNMENT` 정렬 allowlist(`eace1213`) 가 모두 실동작으로 관측돼 HEAD 재빌드본임이 확증됐다(§근거 드리프트 참조).
> DB `public` 스키마(`klid_system`), 프로파일 `local`.
> 취소선 폐기 행 **0건** — 60건 전수가 검증 대상.

## 집계

| 구간 | 대상 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| A-5 (TC-CLAIM) | 21 | **21** | 0 | 0 | 0 | 0 | 0 |
| A-6 (TC-TRACE/RESP/EXC/LOG/SORT/BLANK) | 39 | **39** | 0 | 0 | 0 | 0 | 0 |
| **합계** | **60** | **60** | **0** | **0** | **0** | **0** | **0** |

| 근거 확인 방식 | 건수 |
|---|--:|
| `[실동작]` 포함 | 41 |
| `[정적]`/`[테스트]` 전용 | 19 |

> **케이스 전건 PASS ≠ 결함 0.** 적대 검증 과정에서 **케이스 표가 다루지 않는 신규 결함 3건**(A-ISSUE-21 HIGH · A-ISSUE-22 LOW · A-ISSUE-23 운영)을 발견했다. 특히 A-ISSUE-21 은 `TC-EXC-017` 이 **기대대로 동작하기 때문에** 케이스로는 잡히지 않는 유형이다(미처리 예외가 500 으로 정규화되는 것 자체는 정상 — 문제는 **400/406/415 여야 할 표준 MVC 예외가 그 경로로 흘러드는 것**).

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **A-ISSUE-17** | 공유 정적 패스워드로 임의 인증 사용자가 **REVIEWER 자가부여**(CWE-269/1392) | **해소** | `[실동작]` `POST /v1/auth/role-claim {"role":"REVIEWER"}` (role=null·INTERNAL 토큰) → **403** `"해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요."`. `allowedClaimRoles()`(`RoleClaimService.java:227-229`, WORKER 단일)가 `claim()` **최상단**(`:118-123`)에서 실제로 참조됨(1차의 "정의만 되고 미참조" 해소). **우회 경로 재검증**: `upsertRole` 호출부는 `RoleClaimService:165`·`UserService:139` 2곳뿐이고 후자의 진입점 `PATCH /v1/users/{userNo}` 는 `@PreAuthorize("hasRole('REVIEWER')")` — role=null 토큰·WORKER 토큰 모두 **403 실측**. 승격 경로 없음 |
| **A-ISSUE-18** | rate limit 이 계정 단위 in-memory (교차계정·다중노드 우회) + `attempts` 맵 무한 증가(CWE-307/770) | **해소** | `[실동작]` ①**계정 축**: 동일 sub 7연타 → `401·401·401·401·401·429·429`(5회 허용, 6회째부터 429) ②**전역 축**: 서로 다른 12개 sub × 5회 = 60요청 → **앞 50건 401 / 51번째부터 10건 전부 429**(교차계정 증폭 차단 실증) ③**노드 공유**: 공유 테이블 `LS_AUTHRT_GRANT_ATMPT` 에 `ACCOUNT|{sub}` 와 `GLOBAL|GLOBAL` **양축 실적재** 확인 ④**맵 회수**: `ConcurrentHashMap` → Caffeine `expireAfterWrite(10m)`+`maximumSize(10_000)`(`RoleClaimRateLimiter.java:60-64,91-95`) ⑤**UTC 버킷**: `bgng_dt='2026-07-30 18:00:00'`(호스트 KST 03:00) — JVM TZ 아닌 UTC 고정 실측. ⚠ **IP 축은 의도적 미도입**(프록시 뒤 전 사용자 수렴 사고 회피 — 클래스 javadoc `:41-43`) |
| **A-ISSUE-24** | `MaskingPatternLayout` 이 local 로그 파이프라인에 **미연결**(죽은 `conversionRule`, 패턴이 `%msg`) — CWE-532 | **해소(런타임 확증)** | `[실동작]` 1차·2차 Phase2 문서 모두 "런타임 프로브 비결정적(마스킹 대상 로그 미발생)"으로 남겨둔 **잔여 ③ 을 이번에 직접 닫았다**. 마스킹 대상 문자열을 정렬 키로 실제 주입해 WARN 로그를 발생시킨 결과: `key=password=***` / `key=Authorization: ***` / `key=token=***` — 평문 0건. `logback-spring.xml:11-24` 가 `LayoutWrappingEncoder`+`<layout class="…MaskingPatternLayout">` 로 배선됨(죽은 `conversionRule` 제거 확인) |

**재현 커맨드(요약)**
```bash
# 마스킹 런타임 확증 (A-ISSUE-24)
TOK=<REVIEWER dev 토큰>
curl -s -o /dev/null -H "Authorization: Bearer $TOK" \
  "http://localhost:18081/api/v1/videos?page=0&size=1&sort=password%3DSuperSecret123,desc"
docker logs klid-backend --since 15s | grep "unsupported sort key"
#   → key=password=***
```

## A-5 결과표 (권한 자가부여 — RoleClaimService · RoleClaimRateLimiter)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-CLAIM-001 | 무권한 INTERNAL이 관리자 pw로 WORKER 역할 획득 | PASS | `[정적]` `RoleClaimService.java:105-193` `[테스트]` `RoleClaimServiceTest#claimWorkerSuccess` `[실동작]` REVIEWER 불가 403 확인 | ⚠ **성공(200) 경로는 이 환경에서 실동작 불가** — `ADMIN_CLAIM_PASSWORD_HASH=`(빈 값, `docker exec klid-backend env` 실측)이라 `:137` fail-closed 로 항상 401. 의도된 배포 형상(A-ISSUE-23) |
| TC-CLAIM-002 | PORTAL_USER 역할 요청 400 거부 | PASS | `[실동작]` 400 `INVALID_INPUT` `"PORTAL_USER 역할은 본 API 로 부여할 수 없습니다."` `[정적]` `:111-114` | |
| TC-CLAIM-003 | 이미 역할 보유자 409 거부 | PASS | `[실동작]` sub=2001(LS_USER_ROLE=WORKER), JWT role 클레임 없이 요청 → **409** `"이미 권한이 부여된 사용자입니다."` `[정적]` `:127-129` | `actor.role()` 은 **JWT 클레임이 아니라 `LS_USER_ROLE` DB 해석값**이다(`/v1/me` 로 교차확인 — role 없는 JWT 인데 `role:"WORKER"` 반환). stale JWT 로 게이트를 속일 수 없다 |
| TC-CLAIM-004 | PORTAL 채널 교차채널 자가부여 차단 | PASS | `[실동작]` PORTAL 채널·role=null 토큰 → **409** `[정적]` `:127-129`; `SecurityConfig.java:71` `auth.requestMatchers("/v1/auth/role-claim").authenticated()` | 근거대로 SecurityConfig 는 채널을 좁히지 않아 **서비스단이 유일 방어선**임을 확인 |
| TC-CLAIM-005 | 잘못된 관리자 pw 401(상수시간) | PASS | `[실동작]` 401 `UNAUTHORIZED` `"관리자 패스워드가 일치하지 않습니다."` `[정적]` `:137-144` | 이 환경은 해시 공백이라 `BCryptPasswordEncoder.matches` 자체는 **미도달**(단락 평가). 상수시간 특성은 `[정적]` 판정 |
| TC-CLAIM-006 | admin pw 해시 미설정 시 항상 401(fail-closed) | PASS | `[실동작]` **타이밍 판별로 확증** — pw 경로 401 응답 `7.6ms`, 역할 화이트리스트 403 경로 `5.2ms`(차이 없음). BCrypt cost 12 라면 200~400ms 가 나와야 하므로 `adminPasswordHash.isEmpty()` 단락이 실제로 동작 중 `[정적]` `:87-97,137` `[테스트]` `RoleClaimServiceTest#emptyAdminHashAlwaysReturns401` | |
| TC-CLAIM-007 | admin pw가 BCrypt 아니면 부팅 거부 | PASS | `[정적]` `:92-95,101-103`(prefix `$2a$/$2b$/$2y$`) `[테스트]` `RoleClaimServiceTest#rejectNonBcryptHashAtBoot` | 재기동 금지 제약으로 실기동 검증 미수행 |
| TC-CLAIM-008 | 계정 축 5회/분 초과 429 (pw 검증 **전** 차단) | PASS | `[실동작]` 동일 sub 7연타 → `401×5 → 429×2`. **검사 순서 실증**: REVIEWER(403)·PORTAL_USER(400)·PORTAL채널(409) 요청 3건은 `LS_AUTHRT_GRANT_ATMPT` 에 **카운터를 남기지 않음**(WORKER 시도 1건만 적재) `[정적]` `:118-133`; `RoleClaimRateLimiter.java:104-128` | 잘못된 role 시도가 정상 사용자 쿼터를 소모하지 않음이 DB 로 확인됨 |
| TC-CLAIM-009 | LS_USER_ROLE 이미 매핑 있으면(stale JWT) 409 | PASS | `[실동작]` 409 관측(TC-CLAIM-003 과 동일 요청) `[정적]` 이중 게이트 `:159-162` `[테스트]` `#alreadyHasLsRoleReturns409` | 실환경에서는 게이트 ②(`:127-129`)가 먼저 잡으므로 `:160` 은 **더 깊은 백스톱**이다(pw 성공 이후에만 도달 → 실동작 미도달) |
| TC-CLAIM-010 | 사용자 미존재 404 | PASS | `[정적]` `:153-156` `[테스트]` `#missingUserReturns404` | pw 성공 이후 단계라 실동작 불가(A-ISSUE-23) |
| TC-CLAIM-011 | 부여 성공 후 AFTER_COMMIT 캐시 evict | PASS | `[정적]` `:167-179`(동기화 비활성 시 즉시 evict 폴백) `[테스트]` `#claimSuccessEvictsCache` | 동상 |
| TC-CLAIM-012 | 발급 토큰 HS256/issuer/channel=INTERNAL/exp 1h | PASS | `[정적]` `:61`(TTL 3600) `:195-208`(HS256·issuer·role·channel=INTERNAL) `[테스트]` `#issuedTokenHasOneHourTtl` | issuer 기본 `klid-auth` ∈ `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal`(컨테이너 env 실측) |
| TC-CLAIM-013 | 로그에 pw 평문/userNo CRLF 미노출 | PASS | `[실동작]` sub 에 `7799\r\n…[RoleClaim] granted userNo=1001 role=REVIEWER\r\n…` 주입 → 로그 `userNo=7799__2026-07-31 03:00:00.000 [m` (CR/LF→`_`, 32자 절단, **가짜 로그 라인 0건**) `[정적]` `:119-120,140-141,211-218` | `adminPassword` 는 전 로그에 미출현(grep 0건) |
| TC-CLAIM-014 | sub 비숫자면 400 | PASS | `[정적]` `:146-151` `[테스트]` `#nonNumericSubjectReturns400` | pw 검증 후 파싱 순서 확인(정보노출 순서상 안전). 실동작 불가(A-ISSUE-23) |
| TC-CLAIM-015 | **REVIEWER 자가부여는 403 거부** | PASS | `[실동작]` **403** + rate limit 카운터 미소모(DB 실측) `[정적]` `:115-123,227-229` `[테스트]` `#claimReviewerForbidden`, `#disallowedRoleRejectedBeforeRateLimit` | A-ISSUE-17 해소의 핵심 케이스 |
| TC-CLAIM-016 | 전역 축 50회/분 초과 시 429 (교차계정 증폭 차단) | PASS | `[실동작]` sub 12종 × 5회 = 60요청 → `401×50 → 429×10`, 로그 `[RoleClaim] rate-limited axis=GLOBAL attempts=51 limit=50` `[정적]` `RoleClaimRateLimiter.java:81-82,108-109` | ⚠ 부수효과: 전역 축을 채우면 **최대 60초간 전 사용자 온보딩이 잠긴다**(Phase2 잔존 ⑤ MEDIUM 과 동일 관측 — 계정 축 5/분 + 분 버킷 자동 해제로 저위험, 설계상 수용된 트레이드오프) |
| TC-CLAIM-017 | 공유 저장소 장애여도 로컬 카운터가 임계 강제 | PASS | `[정적]` `RoleClaimRateLimiter.java:116-127` `Math.max(local, shared)`; `JdbcRoleClaimAttemptStore.java:79-83` `catch(DataAccessException) → UNAVAILABLE`(예외 미승격) `[테스트]` `RoleClaimRateLimiterTest#localCounterKeepsEnforcingWhenStoreUnavailable` | DB 장애 유발이 다른 에이전트 작업을 깨뜨리므로 실동작 미수행 |
| TC-CLAIM-018 | 버킷 시각은 UTC 고정(노드 TZ 불일치 방지) | PASS | `[실동작]` 호스트 `2026-07-31 03:00 KST` 요청 → `LS_AUTHRT_GRANT_ATMPT.BGNG_DT = 2026-07-30 18:00:00`(UTC) `[정적]` `:105-106` `[테스트]` `#windowStartIsUtcRegardlessOfJvmTimeZone` | |
| TC-CLAIM-019 | 로컬 카운터 회수 — Caffeine TTL 10분 + maximumSize 10,000 | PASS | `[정적]` `:60-64,91-95` `[테스트]` `#localCountersExpireByTtl`(가상 Ticker) | |
| TC-CLAIM-020 | 캐시 키 구분자는 NUL(`'\0'`) | PASS | `[정적]` `:113` `String cacheKey = seCd + '\0' + idntfr + '\0' + windowStart;` | 소스 표현이 `'\0'` 이스케이프이며 런타임 동작 불변(a18c646f) 서술과 일치 |
| TC-CLAIM-021 | 시도 기록 만료행 주기 정리 | PASS | `[실동작]` 기동 로그 `2026-07-31 02:43:32.013 [main] INFO [RoleClaim] attempt purge job scheduled intervalMs=600000` `[정적]` `RoleClaimAttemptPurgeJob.java:47-49,62-68,86-94`(전용 데몬 스레드 `role-claim-attempt-purge`, `catch(Throwable)` 로 스케줄러 사망 차단) `[테스트]` `RoleClaimAttemptPurgeIT`(2건) | 초기 지연 5분 + 10분 주기라 실삭제 관측은 미수행 |

## A-6 결과표 (추적ID·공통 응답·예외 + 공통 유틸)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-TRACE-001 | X-Trace-Id 없으면 12자리 생성해 MDC/응답 설정 | PASS | `[실동작]` 헤더 없이 요청 → 응답 `X-Trace-Id: 3d4438652ead`(12 hex), 동일 값이 로그 `[3d4438...]` MDC 로 출현 `[정적]` `RequestIdFilter.java:28-33` | |
| TC-TRACE-002 | 제공 traceId CRLF 제거(CWE-113) | PASS | `[실동작]` **raw 소켓으로 obs-fold 주입**(`X-Trace-Id: aaa\r\n\tX-Injected: yes`) → 응답 헤더 `X-Trace-Id: aaaX-Injectedyes` **단일 라인**, 주입 헤더 미생성(응답 분할 차단) `[정적]` `:48-59` | |
| TC-TRACE-003 | 영숫자·하이픈 외 제거 + 64자 절단 | PASS | `[실동작]` `ab<script>c!@#$%^&*()_+=[]{}\|;:.,/?~\`d-e` → `abscriptcd-e`; `A`×200 → **정확히 64자** `[정적]` `:52-57` | |
| TC-TRACE-004 | 요청 종료 후 MDC 제거(스레드 누수 방지) | PASS | `[실동작]` 동일 워커 스레드(`http-nio-8080-exec-5`) 재사용 요청들이 **매번 다른 traceId** 를 찍고, 비요청 스레드(`[main]` 퍼지잡) 로그는 `[]` 빈 MDC `[정적]` `:34-38` finally MDC.remove | |
| TC-TRACE-005 | RequestIdFilter 최우선순위 | PASS | `[정적]` `:17` `@Order(Ordered.HIGHEST_PRECEDENCE)` `[실동작]` 401/403/404/405/500 **전 응답에 X-Trace-Id 부여**(보안 필터 거부 응답 포함) | |
| TC-RESP-001 | ApiResponse.ok success=true, errorCode=null | PASS | `[실동작]` `{"success":true,"data":{…},"message":null,"errorCode":null}` `[정적]` `ApiResponse.java:9-11` | |
| TC-RESP-002 | error(code) success=false + name() + 기본msg | PASS | `[실동작]` 403 → `{"success":false,"data":null,"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}` `[정적]` `:21-23` | |
| TC-RESP-003 | error(code,message,data)로 부가 data 포함 | PASS | `[실동작]` `POST /v1/augments/request {"videoIds":[123],…}` → `{"success":false,"data":{"blockedVideoIds":[123]},…,"errorCode":"NOT_REVIEWED"}` `[정적]` `:29-31` | |
| TC-RESP-004 | JsonInclude.ALWAYS null 필드도 직렬화 | PASS | `[실동작]` 실패 응답에 `"data":null` 키 유지, 성공 응답에 `"message":null,"errorCode":null` 유지 `[정적]` `:6`; 전역 ObjectMapper 에 `NON_NULL`/`default-property-inclusion` 재정의 **0건**(grep — DTO 개별 지정만 존재) | |
| TC-EXC-001 | CustomException이 ErrorCode status/message 매핑 | PASS | `[실동작]` NOT_REVIEWED→400 · FORBIDDEN→403 · CONFLICT→409 · TOO_MANY_REQUESTS→429 전건 일치 `[정적]` `GlobalExceptionHandler.java:24-33` | |
| TC-EXC-002 | details 있으면 응답 data 포함 | PASS | `[실동작]` 위 `blockedVideoIds` `[정적]` `:28-32` | |
| TC-EXC-003 | @Valid 바디 실패 400 + 필드별 메시지 | PASS | `[실동작]` role 누락 → 400 `"role: role 은 필수입니다."` `[정적]` `:35-43` | |
| TC-EXC-004 | ConstraintViolation 400 정규화 | PASS | `[실동작]` `DELETE /v1/videos/123/tracks/t1?fromFrameNo=-5` → 400 `"deleteTrack.fromFrameNo: fromFrameNo 는 0 이상이어야 합니다."`; `@Size` 위반도 400 `[정적]` `:49-57` | ⚠ 메시지에 **컨트롤러 메서드명이 실린다**(`deleteTrack.…`) — A-ISSUE-22(LOW) |
| TC-EXC-005 | 잘못된 JSON/enum 역직렬화 400(파서상세 비노출) | PASS | `[실동작]` 깨진 JSON `{"role":` → 400 `"요청 본문이 올바르지 않습니다."`; 미정의 enum `"SUPERADMIN"` → 동일 400. 로그도 `cause=…`(클래스명만) `[정적]` `:64-69` | |
| TC-EXC-006 | 필수 RequestParam 누락 400 | PASS | `[실동작]` `GET /v1/versions/abcdef/diff`(compareWith 누락) → 400 `"필수 파라미터가 누락되었습니다: compareWith"` `[정적]` `:76-81` | |
| TC-EXC-007 | 파라미터 타입 불일치 400(원문 비노출) | PASS | `[실동작]` `GET /v1/tasks/abc/summary` → 400 `"파라미터 형식이 올바르지 않습니다: rawSn"`(입력 원문 `abc` 미노출) `[정적]` `:88-93` | |
| TC-EXC-008 | 작업락 partial unique 위반만 409 | PASS | `[정적]` `:141-145,168-171`(`ux_ls_auth_work_lock_raw_active`, `equalsIgnoreCase`) `[테스트]` `GlobalExceptionHandlerDataIntegrityTest#…workLock만_409_그외_fail_closed_500`, `WorkLockDataIntegrityIT` | 실제 동시 락 INSERT 경합 유발은 타 에이전트 데이터 오염 위험으로 미수행 |
| TC-EXC-009 | 프리셋 labelId/라벨명 CI/검출유형 유니크 위반 각 409 | PASS | `[정적]` `:146-160` 3분기 + 각 메시지 3종(`"이미 처리 중이거나 충돌하는 요청입니다."` / `"이미 사용 중인 라벨 이름입니다."` / `"이미 사용 중인 검출 클래스 매핑입니다."`) `[테스트]` `LsLabelPresetCodeLabelIdUniqueIT`, `LabelNameCiUniqueMigrationIT` | 동상 |
| TC-EXC-010 | 판별 불가 무결성 위반은 500 노출(fail-closed) | PASS | `[정적]` `:161-166` — 제약명 미판별 시 `INTERNAL_ERROR`(조용한 409 흡수 없음) `[테스트]` 상동 | |
| TC-EXC-011 | 제약명 Hibernate→SQL 메시지 순 추출 | PASS | `[정적]` `:193-209` — 원인 체인에서 `org.hibernate.exception.ConstraintViolationException#getConstraintName()` 우선, null 이면 `constraint "…"` 정규식 파싱 | |
| TC-EXC-012 | 무결성 위반 응답에 제약명/SQL/스택 미노출 | PASS | `[정적]` `:138-166` 응답은 `ErrorCode` 고정 메시지만, 제약명은 **로그에만** `[테스트]` `#DataIntegrity_응답에_제약명_SQL_미포함` | 정책 서술 주석 `:127-137` 위치도 케이스 근거와 일치 |
| TC-EXC-013 | AccessDeniedException 403 매핑 | PASS | `[실동작]` WORKER 토큰 → `GET /v1/users` → 403 `FORBIDDEN` `[정적]` `:211-216` | |
| TC-EXC-014 | AuthenticationException 401 매핑 | PASS | `[실동작]` 무토큰 → 401 `{"…","errorCode":"UNAUTHORIZED"}`(ApiResponse 포맷 유지) `[정적]` `:218-223` | 필터 체인에서 던져지는 경우는 `ExceptionTranslationFilter`/EntryPoint 가 처리하며 본 핸들러는 컨트롤러·서비스 발 예외 담당 — 관측 결과(상태·바디 포맷)는 동일 |
| TC-EXC-015 | 미매핑 경로(NoResourceFound) 404 정규화 | PASS | `[실동작]` `GET /v1/no-such-endpoint-xyz` → 404 `"요청한 API를 찾을 수 없습니다."` `[정적]` `:241-246` | |
| TC-EXC-016 | multipart 크기 초과 413 정규화 | PASS | `[정적]` `:234-239` `MaxUploadSizeExceededException → PAYLOAD_TOO_LARGE`(한도 수치 비노출) `[테스트]` `MaxUploadSizeExceededMappingTest` | 실동작 미수행 — local 한도가 `max-file-size 500MB / max-request-size 1200MB`(`application.yml:29-31`)라 500MB+ 전송이 필요하고, 공유 스택에 임시파일 스풀 부하를 주는 것이 타 에이전트 작업을 위협해 의도적으로 배제 |
| TC-EXC-017 | 미처리 Exception 500 + 고정메시지(스택 비노출) | PASS | `[실동작]` 500 응답 바디는 `{"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` — 스택·클래스명·내부 경로 **0건**. 스택은 서버 로그에만(`log.error(…, e)`) `[정적]` **실제 위치 `:273-278`**(케이스 근거 `:248-253` 은 드리프트 — 아래 참조) | ⚠ 이 케이스는 PASS 지만, **여기로 흘러들어선 안 될 표준 MVC 예외 3종을 발견**(A-ISSUE-21 HIGH) |
| TC-LOG-001 | LogSanitizer — C0/DEL/C1 제어문자 제거 | PASS | `[실동작]` 정렬 키에 `\r \n \x07(BEL) \x7F(DEL) \x85(NEL)` 주입 → 로그 `key=kLS1PS2NEL3DEL4BEL5CRLF6 가 나 다`(od -c 로 바이트 확인, 줄바꿈 1개=라인 끝뿐) `[정적]` `LogSanitizer.java:88-100` | |
| TC-LOG-002 | LogSanitizer — 유니코드 라인 구분자 제거 | PASS | `[실동작]` 동일 프로브에 `U+2028`·`U+2029` 포함 → **둘 다 제거**(local 평문 레이아웃에서 가짜 라인 위조 불가) `[정적]` `:78-99` `[테스트]` `LogSanitizerTest#…U2028_U2029_제거` | |
| TC-LOG-003 | LogSanitizer — 가시 문자·일반 공백 보존 | PASS | `[실동작]` `가 나 다` 와 그 사이 `U+0020` 공백 **원형 보존** `[정적]` `:86,93-95` | |
| TC-LOG-004 | LogSanitizer — 길이 상한 + 절단 표기 | PASS | `[실동작]` 64자 상한 호출부(SortAllowlist) 프로브 → `key=aaaaaaaaaa2026-07-31 03:00:00.000 [main] ERROR FAKE - INJECTEDbb...(truncated)` `[정적]` `:25,28,51-72` | |
| TC-LOG-005 | LogSanitizer — null 은 `(null)` | PASS | `[정적]` `:27,52-54` `[테스트]` `LogSanitizerTest#…null_입력시_플레이스홀더_반환` | |
| TC-SORT-001 | SortAllowlist strict — 미등록 정렬 키 400 | PASS | `[실동작]` `GET /v1/tasks/board?sort=secretField,desc` → **400** `"지원하지 않는 정렬 기준입니다."`(입력값·내부 필드명 미노출); SQL 조각 `regDt;DROP TABLE ls_data_raw--` 도 동일 400; `/v1/assignments` 도 strict 400 `[정적]` **실제 `:163-186`(throw `:179`)** | ★2 정책대로 strict 유지 — 통일 권고 없음 |
| TC-SORT-002 | SortAllowlist lenient — 미등록 키 무시 + 기본 정렬 폴백 | PASS | `[실동작]` `/v1/reviews?sort=secretField,desc` → **200**(totalElements=5), `/v1/videos?sort=secretField,desc` → **200**(totalElements=40) + WARN `[Sort] unsupported sort key ignored key=secretField` `[정적]` **실제 `:213-239`(무시 `:228-233`)**; `SortFieldMapper.java:57-60` | ★2 확정 정책 — strict/lenient 비대칭은 **정상** |
| TC-SORT-003 | 정렬 항목 개수 상한 = allowlist 고유 엔티티 필드 수 | PASS | `[실동작]` board 4개 지정(상한 3) → **400** `"정렬 기준이 너무 많습니다."`(개수·상한 미노출) / reviews 4개·videos 5개 → **200 전체 폴백**(부분 적용 없음, WARN `too many sort orders … firstDroppedKey=…`) `[정적]` **실제 `:167,172-175,217,222-227,247-249`** | |
| TC-SORT-004 | 같은 엔티티 필드 중복 지정 시 첫 지정만 적용 | PASS | `[실동작]` `/v1/videos?sort=capturedAt,asc&sort=shtDt,desc` → 결과가 `capturedAt,asc` 단독과 **완전 동일**(rawSn 20001,20002 / 2026-07-30T11:23:34); 순서를 뒤집으면 desc 단독과 동일(126,127) `[정적]` **실제 `:169,181-183,234-236`** | |
| TC-SORT-005 | 폴백 WARN 로그의 입력 키는 LogSanitizer 로 정제 | PASS | `[실동작]` `?sort=aaaaaaaaaa%0A…ERROR FAKE - INJECTED…,desc` → WARN 한 줄, 개행 제거 + 64자 절단 `...(truncated)` `[정적]` `:66`(정확) + **실제 `:224-225,230-231`** | |
| TC-SORT-006 | 컨트롤러 allowlist 사본 금지 — `SortAllowlist.VIDEO` 단일 진실원 | PASS | `[정적]` **실제 `:133-140`**(VIDEO 상수 1벌); `SortFieldMapper.java:29-31,57-70`(정확) — `resolveLenient` 로 전량 위임, 자체 판정 로직 0줄 `[테스트]` `SortFieldMapperTest`(8건), `SortAllowlistTest`(18건) | `ASSIGNMENT` allowlist 신설(`eace1213`)도 동일 상수 파일에 추가돼 사본 없음 |
| TC-BLANK-001 | BlankTextPredicate — Java isBlank()와 동치 판정 | PASS | `[정적]` `BlankTextPredicate.java:60-66` `[실동작(DB 의미검증)]` PG 실행 결과 `trim(chr(9))='' → f` (구 `trim(col)<>''` 판정) vs `trim(replace(chr(9),chr(9),''))='' → t`; 전각공백 `U+3000` 도 동일하게 구 방식 f / 신 방식 t `[테스트]` `TaskBoardFilterSortTest#공백문자만_있는_CCTV명은_표시된_VMS_ID_로_검색된다`, `ReviewListFilterSortTest` 동명 | 이 환경엔 `CCTV_NM='\t'` 실데이터가 없고 DB 쓰기가 금지라 화면 폴백 종단 시나리오는 미재현 — 판정식 동치성은 PG 직접 실행으로 확증 |
| TC-BLANK-002 | BlankTextPredicate — 제거 문자는 파라미터 바인딩(CWE-89) | PASS | `[실동작]` `GET /v1/tasks/board?q=CCTV-018` 실행 시 Hibernate SQL 로그에 `replace(replace(…replace(mrc2_0.CCTV_NM, ?, '')…` — **24중첩 전부 `?` 바인딩**, 문자열 연결 0건 `[정적]` `:49,62-65,68-76`(`Character.isWhitespace` 에서 생성) | |

## 근거 드리프트

케이스 표의 `file:line` 이 현재 HEAD 와 어긋난 건 **7건**(모두 코드 삽입에 의한 행 밀림 — 로직 변경 아님).

| ID | 표기된 근거 | 실제 위치 | 원인 |
|---|---|---|---|
| TC-EXC-017 | `GlobalExceptionHandler.java:248-253` | **`:273-278`**(`handleUnknown`) | `476bc91a` 가 `handleMethodNotSupported`(405 정규화, `:248-271`)를 삽입해 **+25행** 밀림. 표기 범위 `248-253` 은 현재 그 405 핸들러의 javadoc |
| TC-SORT-001 | `SortAllowlist.java:139-162` | **`:163-186`**(`resolve`, throw `:179`) | `eace1213`(배정 목록 정렬 allowlist)이 `ASSIGNMENT` 상수(`:80-102`)를 삽입해 이후 전 구간 **+24행** 밀림 |
| TC-SORT-002 | `SortAllowlist.java:189-215` | **`:213-239`**(`resolveLenient`, 무시 `:228-233`) | 동상 (+24). `SortFieldMapper.java:57-60` 은 정확 |
| TC-SORT-003 | `:143,193,198-203,223-225` | **`:167,217,222-227,247-249`** | 동상 (+24) |
| TC-SORT-004 | `:145,157-159,210-212` | **`:169,181-183,234-236`** | 동상 (+24) |
| TC-SORT-005 | `:66,200-201,206-207` | `:66`(정확) + **`:224-225,230-231`** | 동상 (+24). `LOG_KEY_MAX_LENGTH` 는 삽입 지점보다 앞이라 불변 |
| TC-SORT-006 | `SortAllowlist.java:109-116` | **`:133-140`**(VIDEO 상수) | 동상 (+24). `SortFieldMapper.java:29-31,57-70` 은 정확 |

**정확 확인된 근거**: A-5 전 21건(`RoleClaimService`·`RoleClaimRateLimiter`·`JdbcRoleClaimAttemptStore`·`RoleClaimAttemptPurgeJob`·`SecurityConfig.java:71`) · TC-TRACE 5건 · TC-RESP 4건 · TC-EXC 16건(017 제외) · TC-LOG 5건 · TC-BLANK 2건.

> ⚠ 이 드리프트 2건(`476bc91a`·`eace1213`)은 `stack-bringup.md` 가 "이미지 미반영"으로 지목한 커밋이다. **두 커밋의 동작이 실동작으로 확인**(405+`Allow: POST` 응답 / `/v1/assignments` strict 400)됐으므로, 현재 실행 이미지는 **HEAD `ca3c712b` 재빌드본이 맞다**. 이후 회차는 `stack-bringup.md` §2·§7-1 의 "11커밋 뒤처짐" 제약을 더 이상 적용하지 말 것.

## 이슈 상세

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

## 부기 — 확정 정책 준수 확인

- **★2(정렬 strict/lenient 비대칭)**: `TC-SORT-001~003` 에서 `/v1/tasks/board`·`/v1/assignments` = **400**, `/v1/reviews`·`/v1/videos` = **200 폴백** 을 실측했고 **이를 결함으로 보고하지 않는다.** 두 정책 모두 allowlist 매핑으로만 정렬을 해석하므로 보안 목적(CWE-89/770)은 동일하게 달성된다.
- **★1·★3**: 이 파트(A-5·A-6) 범위 밖 — 해당 케이스 없음.
- **`RoleClaimRateLimiter.java:123` 주석 미세 불일치(보고 대상 아님)**: 주석은 "식별자는 …제어문자를 제거해 로그한다"고 하나 실제 WARN(`:124`)에는 식별자가 **아예 실리지 않는다**(`axis=… attempts=… limit=…`). 노출을 줄이는 방향이라 결함이 아니며 주석 문구만 현행화 대상.
