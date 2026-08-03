# A 클러스터 — part4 검증 결과

> 담당 구간: `docs/test-cases/A-auth-common.md` **`## A-6. 추적ID·공통 응답·예외 + 공통 유틸`** (39건)
> 검증일 2026-08-01 · 회차 1차 · 검증자 A-part4 에이전트
> 스택: 실행 중 로컬 풀스택(backend `localhost:18081/api`, profile=**local**, klid-postgres/public 스키마).
> ⚠ 환경 버전 격차(구버전 이미지 V146) — **본 구간은 최근 커밋(b2b44f0e~56d30478: LS_DATA_INGEST·증강 폐기/복구)과 무관**하므로 BLOCKED 사유 해당 없음. 전건 정상 검증.

## 0. 집계

| 판정 | 건수 |
|------|:--:|
| PASS | 35 |
| PARTIAL | 4 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **39** |

이슈: **A-ISSUE-61 ~ A-ISSUE-71 (11건)** — HIGH 2 · MEDIUM 3 · LOW 6

## 1. 검증 환경·근거 수집 방법

- 실동작: `curl` + raw socket(python) 로 backend 직접 호출 → 응답 원문 + `docker logs klid-backend` 로그 라인 대조
- 토큰: `POST /api/v1/dev/tokens` (REVIEWER sub=1001 / WORKER sub=2001 / PORTAL_USER)
- 로그 프로파일 실측: `docker exec klid-backend printenv SPRING_PROFILES_ACTIVE` → **`local`** → `logback-spring.xml` 의 `LayoutWrappingEncoder` + `MaskingPatternLayout` 경로가 실제로 활성
- 코드/설정/테스트 파일 **수정 0건**, 빌드/테스트 실행 **0건** (baseline `_raw/test-baseline.md` 대조만 수행 — BE 4,755 tests / 실패 0)

### ★★ 확증편향 반증 — 로그 마스킹 "실배선" 실측 (지시사항 핵심 항목)

이 구간의 알려진 실패 모드는 "LogSanitizer 유닛테스트는 통과하는데 실제 로그 파이프라인엔 미배선"이었다.
**실제 로그를 찍어 반증을 시도**했고, 결과는 다음과 같다.

프로브: `GET /v1/videos?sort=<페이로드>,desc` → `SortAllowlist.resolveLenient` 가 미등록 키를
`LogSanitizer.sanitize(key,64)` 로 정제해 WARN 출력 → `docker logs klid-backend` 로 원문 확인.

| 투입 페이로드 | 실제 로그 출력 | 판정 |
|---|---|---|
| `aa\nFAKE-LOG-LINE-…\r\nERROR injected` | `key=aaFAKE-LOG-LINE-1785577789ERROR injected` (한 줄) | ✅ LogSanitizer **실배선 확인** (CWE-117 차단) |
| `U+2028`·`U+2029`·`U+0085`·TAB·NUL·DEL·ESC·`U+0090` 혼합 | `key=AALSDONEPSDONENELDONETABDONENULDONEDELDONEESCDONEC1DONE ZZ 가 나 다…` | ✅ 전부 제거, 공백·한글 보존 |
| `BBB…`(300자) | `BBB…(64자)...(truncated)` | ✅ 64자 절단 |
| `token=SUPERSECRET123` | `key=token=***` | ✅ **MaskingPatternLayout 실배선 확인** |
| `password=hunter2` | `key=password=***` | ✅ |
| `secret=abc` | `key=secret=***` | ✅ |
| `Authorization: Bearer eyJabc.def.ghi` | `key=Authorization: ***` | ✅ |

→ **UNCERTAINTIES #7("Logback 마스킹 레이아웃 실체 — 잔여: 런타임 프로브 비결정적")은 본 회차에서 런타임 실증으로 해소된다.** `MaskingPatternLayout` 은 죽은 코드가 아니라 실제 appender 종단에서 동작한다.

단, **같은 프로브로 마스킹 집합의 구멍이 드러났다** → A-ISSUE-62 / A-ISSUE-63.

---

## 2. 케이스별 판정

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|------|------|
| TC-TRACE-001 | PASS | [실동작] | 헤더 없이 요청 → `X-Trace-Id: 7592fe4a7479` (12자리 hex) 응답 헤더 + 로그 `[7592fe4a7479]`. `RequestIdFilter.java:28-33` 일치 |
| TC-TRACE-002 | PASS | [실동작] | raw 소켓으로 C1 제어바이트 포함 헤더 투입 → 응답 `X-Trace-Id: abcd` (제어문자 제거). `%0d%0a` 리터럴도 `abc0d0aInjected` 로 무해화. 응답 헤더 분할 미발생 |
| TC-TRACE-003 | PASS | [실동작] | `abc!@#$%^&*()_+=<>?/\|~\`'";:[]{}def` → `abcdef` / `A`×100 → 64자 절단 실측 |
| TC-TRACE-004 | PASS | [실동작] | 백그라운드 스레드 로그가 `[scheduling-2] DEBUG []` — traceId 공란. MDC 스레드 누수 없음(`finally MDC.remove`, RequestIdFilter.java:36-38) |
| TC-TRACE-005 | PASS | [실동작] | `@Order(HIGHEST_PRECEDENCE)` (RequestIdFilter.java:17). **실증**: Spring Security `StrictHttpFirewall` 이 거부한 요청의 ERROR 로그에도 traceId `[abcd]` 가 채워져 있음 → 필터가 시큐리티 체인(order -100)보다 먼저 실행됨 |
| TC-RESP-001 | PASS | [실동작] | `GET /v1/videos` → `{"success":true,"data":{…},"message":null,"errorCode":null}` |
| TC-RESP-002 | PASS | [실동작] | 401 → `{"success":false,"data":null,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` / 403 → `FORBIDDEN` + "권한이 없습니다." = `ErrorCode.defaultMessage()` |
| TC-RESP-003 | PASS | [실동작] | `POST /v1/augments/request {"videoIds":[9101],"types":["WINTER"]}` → 400 `{"data":{"blockedVideoIds":[9101]},"errorCode":"NOT_REVIEWED"}` |
| TC-RESP-004 | PASS | [실동작] | 모든 에러 응답에 `"data":null` 키 유지. 전역 Jackson `default-property-inclusion` 재정의 **없음**(application*.yml grep 0건, `Jackson2ObjectMapperBuilder` 커스터마이저 0건) |
| TC-EXC-001 | PASS | [실동작] | `PRECONDITION_FAILED`→412, `NOT_REVIEWED`→400, `CONFLICT`→409 등 status/errorCode 매핑 실측 |
| TC-EXC-002 | PASS | [실동작] | 위 TC-RESP-003 과 동일 응답에서 `details`(=`blockedVideoIds`) 가 `data` 로 실림. GlobalExceptionHandler.java:28-32 |
| TC-EXC-003 | PASS | [실동작] | `POST /v1/assignments {}` → 400 `"workerId: workerId 는 필수입니다., rawDataIds: rawDataIds 는 1건 이상이어야 합니다."` |
| TC-EXC-004 | PARTIAL | [실동작] | `GET /v1/reviews?q=<150자>` → 400 INVALID_INPUT 정규화는 성립. **단 메시지가 `list.q: …`** — 컨트롤러 **핸들러 메서드명(`list`)이 응답에 노출**된다(형제 케이스 TC-EXC-007 은 "원문 비노출"을 명시) → **A-ISSUE-69** |
| TC-EXC-005 | PASS | [실동작] | `-d '{broken'` → 400 `"요청 본문이 올바르지 않습니다."`, 로그는 `cause=JsonEOFException` (클래스명만) |
| TC-EXC-006 | PASS | [실동작] | `GET /v1/portal/datamart/labels` (rawSn 누락) → 400 `"필수 파라미터가 누락되었습니다: rawSn"` |
| TC-EXC-007 | PASS | [실동작] | `GET /v1/videos/notanumber` → 400 `"파라미터 형식이 올바르지 않습니다: rawSn"` — 입력 원문 `notanumber` 미노출 |
| TC-EXC-008 | PASS | [정적] | GlobalExceptionHandler.java:141-145,168-171 — `ux_ls_auth_work_lock_raw_active` 만 409. 커버: `auth/WorkLockDataIntegrityIT.java`(baseline 통과) |
| TC-EXC-009 | PASS | [실동작] | 운영 로그 실측(2026-07-31 03:22:18, 동시 요청 4건): `[Exception] label name ci-unique violation constraint=uk_ls_label_nm_ci` → 409 분기 진입 확인. 3종 인덱스 분기 GlobalExceptionHandler.java:146-160 |
| TC-EXC-010 | PASS | [정적] | :161-166 fail-closed 500 + 커버 `GlobalExceptionHandlerDataIntegrityTest("DataIntegrityViolation_cause_분기_workLock만_409_그외_fail_closed_500")` |
| TC-EXC-011 | PASS | [실동작] | 위 09 로그가 제약명 `uk_ls_label_nm_ci` 를 정확 추출함을 실증. Hibernate→SQL 메시지 순 :193-209 |
| TC-EXC-012 | PASS | [정적] | 409/500 응답 모두 `ErrorCode` 고정 메시지만(:143-165). 커버 `GlobalExceptionHandlerDataIntegrityTest("DataIntegrity_응답에_제약명_SQL_미포함")` |
| TC-EXC-013 | PASS | [실동작] | WORKER 토큰 + 유효 바디로 `POST /v1/assignments` → 403 `FORBIDDEN` |
| TC-EXC-014 | PASS | [실동작] | 토큰 없이 `GET /v1/videos` → 401 `UNAUTHORIZED` |
| TC-EXC-015 | PASS | [실동작] | `GET /v1/no-such-endpoint` → 404 `"요청한 API를 찾을 수 없습니다."` |
| TC-EXC-016 | PASS | [정적] | :234-239 → `PAYLOAD_TOO_LARGE`, 내부 한도(500MB/1200MB, application.yml:28-29) 미노출. 커버 `MaxUploadSizeExceededMappingTest`. 실동작은 500MB 업로드가 필요해 비용상 미수행 |
| TC-EXC-017 | PARTIAL | [실동작] | 응답 계약은 충족 — 500 + `"서버 내부 오류가 발생했습니다."` 고정, 스택은 서버 로그에만. **단 클라이언트 입력 오류(`RequestRejectedException`)가 이 핸들러로 떨어져 500 + ERROR 스택** → **A-ISSUE-64**. 근거 라인 드리프트(:248-253 → 실제 :273-278) |
| TC-LOG-001 | PASS | [실동작] | 위 §1 표 — TAB/NUL/DEL/ESC/`U+0090`(C1) 전부 **제거**(치환 아님). LogSanitizer.java:88-100 |
| TC-LOG-002 | PASS | [실동작] | `U+2028`/`U+2029` 제거 실증. 평문 레이아웃(local)에서 가짜 라인 위조 불가 |
| TC-LOG-003 | PASS | [실동작] | `" ZZ 가 나 다"` 그대로 보존(`U+0020`=Zs 는 비대상) |
| TC-LOG-004 | PARTIAL | [실동작] | 초과 절단·`...(truncated)` 접미는 정상(300자→64자). **단 ①입력 길이가 정확히 상한과 같아도 허위 `(truncated)` 부착**(64자 'C' → `CCCC…(truncated)`, 63자는 미부착) **②서로게이트 페어가 경계에서 분할돼 고립 서로게이트가 로그에 실림**(63자+😀 → 고위 서로게이트만 잔존) → **A-ISSUE-65 / A-ISSUE-66** |
| TC-LOG-005 | PASS | [정적] | :27,52-54 `NULL_PLACEHOLDER="(null)"`, NPE 없음. 커버 `LogSanitizerTest("LogSanitizer_null_입력시_플레이스홀더_반환")` |
| TC-SORT-001 | PASS | [실동작] | `GET /v1/tasks/board?sort=secretField,desc` → **400** `"지원하지 않는 정렬 기준입니다."` — 입력값·내부 필드명 미노출. `/v1/assignments` 도 동일(strict) |
| TC-SORT-002 | PASS | [실동작] | `GET /v1/reviews?sort=secretField,desc` → **200** + 기본 정렬. `/v1/videos` 도 200. 로그 `[Sort] unsupported sort key ignored key=secretField` WARN 발생. ★2 확정 정책 준수 |
| TC-SORT-003 | PASS | [실동작] | strict: TASK_BOARD 고유 필드 3개 → 4개 지정 시 400 `"정렬 기준이 너무 많습니다."`(개수·상한 미노출). lenient: VIDEO 고유 4개 → 5개 지정 시 200 + **전체 폴백** + WARN `firstDroppedKey=id` |
| TC-SORT-004 | PASS | [실동작] | `?sort=capturedAt,asc&sort=shtDt,desc` 결과가 `?sort=capturedAt,asc` 단독과 동일(id 8,9,10) / 순서를 뒤집으면 `shtDt,desc` 단독과 동일(9101,9103,9104) → **첫 지정만 적용** 실증 |
| TC-SORT-005 | PASS | [실동작] | `?sort=aa%0AFAKE-LOG-LINE-…%0D%0AERROR injected` → WARN 한 줄, 개행 제거. 300자 입력은 64자+`...(truncated)`. SortAllowlist.java:66,224-225,230-231 |
| TC-SORT-006 | PARTIAL | [실동작] | 단일 진실원 자체는 성립 — `VideoController.java:109` 가 `SortAllowlist.VIDEO` 를 직접 참조하고 컨트롤러 allowlist 사본 **0건**(grep). **단 allowlist 정책이 적용되지 않은 목록 엔드포인트가 3곳 이상 남아 미등록 키 → 500 + `PropertyReferenceException` 스택**(SortAllowlist 가 막으려던 바로 그 회귀) → **A-ISSUE-61** |
| TC-BLANK-001 | PASS | [실동작] | 실행 SQL 실측: `trim(BOTH from replace(replace(…(mrc2_0.CCTV_NM, ?, ''), …))<>?` — `Character.isWhitespace` 파생 **24개** 공백류(space 제외) 제거 후 trim. 표시측도 `isBlank()`(ReviewService.java:183, TaskBoardService.java:203, AssignmentResponse.java:151) → 판정 일치. 단 전용 자동 테스트 0건 → A-ISSUE-67 |
| TC-BLANK-002 | PASS | [실동작] | 위 SQL 의 제거 대상이 전부 `?` **바인딩 파라미터**(문자열 연결 0). BlankTextPredicate.java:62-65,68-76. CWE-89 안전 |

---

## 3. 이슈

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
