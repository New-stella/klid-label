# A-part3 — A-6. 추적ID·공통 응답·예외 + 공통 유틸 (39건)

- 담당 범위: `docs/test-cases/A-auth-common.md` "## A-6." 섹션(line 190~233) 전체 39건
  (TC-TRACE-001~005 · TC-RESP-001~004 · TC-EXC-001~017 · TC-LOG-001~005 · TC-SORT-001~006 · TC-BLANK-001~002)
- 이슈 ID 시작번호: A-ISSUE-61~
- 검증 방식: **실동작 최우선** — 기동된 로컬 스택(`localhost:18081`)에 `POST /v1/dev/tokens`로 REVIEWER/WORKER/PORTAL_USER 3역할 JWT를 직접 발급받아 실제 HTTP 요청을 보내고, 응답 바디·HTTP 상태코드·`docker compose logs klid-backend`(traceId·WARN 로그)·DB(`mng_resource_cctv` 등)를 근거로 확인. 원시 소켓(Python `socket`)으로 Tomcat 헤더 파서가 허용하는 제어문자(tab/DEL/U+2028/U+0085)를 X-Trace-Id 헤더에 직접 주입해 반증 시도. 정적 대조·테스트 커버(Grep)는 실동작이 불가능한 경계(2노드 동시성, 500MB 페이로드 생성 등)에서만 보조로 사용.
- 코드/설정/컨테이너 변경 없음, 빌드·테스트 미실행(baseline은 다른 에이전트 담당).

## 판정 결과 표

| ID | 판정 | 근거확인 | 근거 요약 |
|----|:--:|------|------|
| TC-TRACE-001 | PASS | [실동작] | 헤더 없이 요청 → 응답 `X-Trace-Id: 7c0a7090d8d2`(12자리 hex) 확인. `RequestIdFilter.java:28-33`과 일치 |
| TC-TRACE-002 | PASS | [실동작][정적] | Tomcat 자체 HTTP 파서가 헤더값에 raw CR(`\r`)·NUL(`\x00`)이 섞이면 필터 도달 전 **컨테이너 레벨에서 400**으로 선차단(원시 소켓 확인: `abc\rdef`, `abc\x00def` 모두 400, 필터 로그 자체가 안 남음) — 즉 리터럴 CRLF 벡터는 net상 재현 불가. 대신 **동일 정규식·동일 코드 경로**로 tab(`\t`, 0x09, `\p{Cntrl}` 동일 부류)·U+2028을 주입한 결과 둘 다 200 통과 후 `X-Trace-Id: abcdef`로 정상 제거됨을 확인(`RequestIdFilter.java:52-57`) → 코드가 CR/LF를 포함한 전체 `\p{Cntrl}` 클래스를 동일하게 제거한다는 반증 실패. 컨테이너가 선차단하는 것은 앱 방어가 무력화된 게 아니라 이중 방어(fail-closed)이므로 결함 아님 |
| TC-TRACE-003 | PASS | [실동작] | `X-Trace-Id: ab!!c@@d##123-XYZ` → 응답 `abcd123-XYZ`(영숫자+하이픈만 보존, 특수문자 제거) 확인. 200자 입력(`a1`×100) → 응답이 정확히 64자에서 절단됨 확인. `RequestIdFilter.java:52-57` 일치 |
| TC-TRACE-004 | PASS | [실동작] | 커스텀 `X-Trace-Id: MYUNIQUEMARKER99`로 404 유발 요청 후, 헤더 없이 동일 자원 재요청 → 새 traceId(`40dbe319bf17`)가 로그에 남고 `MYUNIQUEMARKER99`는 재사용되지 않음(`docker compose logs` 확인). 배경 스케줄 스레드(`control-notify-debounce-flush`) 로그도 `[traceId 빈값]`으로 확인돼 MDC 오염·스레드풀 누수 없음 재확인. `RequestIdFilter.java:34-38` `finally { MDC.remove }` 일치 |
| TC-TRACE-005 | PASS | [정적] | `@Order(Ordered.HIGHEST_PRECEDENCE)`(`RequestIdFilter.java:17`) — 구조적 사실, 실동작 우회 경로 없음 |
| TC-RESP-001 | PASS | [실동작] | 모든 정상 응답(예: `GET /v1/videos`)이 `{"success":true,"data":{...},"message":null,"errorCode":null}` 형태로 반환됨을 다회 확인. `ApiResponse.java:9-11` 일치 |
| TC-RESP-002 | PASS | [실동작] | WORKER 토큰으로 REVIEWER 전용 `/v1/reviews/4/approve` 호출 → `{"success":false,"data":null,"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}`. 인증 없이 호출 → `{"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}`. 두 메시지 모두 `ErrorCode.java:7-8`의 `defaultMessage()`와 정확히 일치(핸들러가 별도 문자열을 하드코딩하지 않음을 확인) |
| TC-RESP-003 | PASS | [실동작] | `POST /v1/augments/request {"videoIds":[7],"types":["WINTER"]}`(rawSn=7은 MARKING_READY, 미검수) → `{"success":false,"data":{"blockedVideoIds":[7]},"message":"검수가 완료되지 않은 영상이 포함되어 있습니다.","errorCode":"NOT_REVIEWED"}`. `data`에 부가정보(`blockedVideoIds`) 포함 확인. `ApiResponse.java:29-31`, `AugmentRequestService.java:134-135` 경로 실제 경유 |
| TC-RESP-004 | PASS | [실동작] | 위 모든 에러 응답에서 `"data":null`이 키 자체는 생략되지 않고 항상 노출됨을 확인(`@JsonInclude(ALWAYS)`, `ApiResponse.java:6`) |
| TC-EXC-001 | PASS | [실동작] | CustomException 경유 다수 코드 확인: FORBIDDEN→403, UNAUTHORIZED→401, NOT_FOUND→404("영상을 찾을 수 없습니다."), PRECONDITION_FAILED→412, CONFLICT→409, INVALID_INPUT→400 — 각각 정확한 HTTP status로 매핑됨을 실응답으로 확인(`GlobalExceptionHandler.java:24-33`) |
| TC-EXC-002 | PASS | [실동작] | TC-RESP-003과 동일 요청에서 `details!=null` 분기로 `data`에 `blockedVideoIds` 포함 확인(`GlobalExceptionHandler.java:28-32`) |
| TC-EXC-003 | PASS | [실동작] | `POST /v1/augments/request {"videoIds":[],"types":["WINTER"]}`(빈 배열, `@NotEmpty` 위반) → 400 `"videoIds: videoIds 는 필수이며 비어있을 수 없습니다."` — `field: message` 포맷 정확 일치(`GlobalExceptionHandler.java:35-43`) |
| TC-EXC-004 | PASS | [실동작] | `GET /v1/notices?page=-1`(`@Min(0)` 위반) → 400 `"list.page: must be greater than or equal to 0"`. `GET /v1/notices?size=500`(`@Max(100)` 위반) → 400 `"list.size: must be less than or equal to 100"`. `propertyPath: message` 포맷 확인(`GlobalExceptionHandler.java:49-57`) |
| TC-EXC-005 | PASS | [실동작] | `POST /v1/assignments`에 깨진 JSON(`{invalid json`) → 400 `{"message":"요청 본문이 올바르지 않습니다.","errorCode":"INVALID_INPUT"}` — 파서 내부 상세(Jackson 예외 클래스명·위치 등) 응답에 미노출 확인(CWE-209). `GlobalExceptionHandler.java:64-69` |
| TC-EXC-006 | PASS | [실동작] | `GET /v1/portal/datamart/labels`(필수 `@RequestParam Long rawSn` 누락, PORTAL_USER 토큰) → 400 `"필수 파라미터가 누락되었습니다: rawSn"` — 파라미터명만 노출, 내부 타입 등 상세 없음. `GlobalExceptionHandler.java:76-81` |
| TC-EXC-007 | PASS | [실동작] | `GET /v1/videos/not-a-number`(Long 자리 문자열) → 400 `"파라미터 형식이 올바르지 않습니다: rawSn"` — 원문 입력값·내부 타입 미노출. `GlobalExceptionHandler.java:88-93` |
| TC-EXC-008 | PASS | [정적][테스트커버] | 실동작으로는 동시 락 INSERT 경합(2노드/동시요청) 재현이 이번 세션에서 불가(BLOCKED 대상 성격) — 대신 `GlobalExceptionHandlerDataIntegrityTest.dataIntegrityBranching`(work_lock_unique_위반은_409 케이스, 88행)이 합성 `ConstraintViolationException`으로 `handleDataIntegrityViolation` 직접 호출해 409 CONFLICT 확인. `GlobalExceptionHandler.java:141-145,168-171` 코드와 일치 |
| TC-EXC-009 | PASS | [정적][테스트커버] | 동일 테스트의 preset_labelId_unique_위반은_409·label_name_ci_unique_위반은_409 2케이스로 409 확인. 단 4번째 분기(`label_dtct_type_unique`, `GlobalExceptionHandler.java:156-160`)는 이 파라미터라이즈드 테스트에 **포함되지 않음**(3/4만 커버) — 코드는 앞 3개와 완전히 동형 패턴이라 기능 결함으로 보진 않으나 테스트 공백으로 기록(A-ISSUE-63) |
| TC-EXC-010 | PASS | [정적][테스트커버] | `GlobalExceptionHandlerDataIntegrityTest`의 `FK위반은_409아닌_500`·`제약명_null이면_500` 2케이스가 fail-closed 500/INTERNAL_ERROR를 직접 확인. `GlobalExceptionHandler.java:161-166` 코드와 일치(조용한 409 흡수 없음) |
| TC-EXC-011 | PASS | [정적][테스트커버] | Hibernate `getConstraintName()` 우선 추출 경로는 위 5케이스 전부가 실제로 경유(정상 커버). 다만 "Hibernate 이름 null이지만 SQL 메시지에 `constraint "..."` 패턴이 있어 정규식으로 추출 성공"하는 **폴백 성공 분기**(`GlobalExceptionHandler.java:207` `CONSTRAINT_IN_MESSAGE` 매치 성공 케이스)는 테스트의 `nullConstraintName()`이 애초에 메시지에 `constraint` 토큰이 없는 NOT NULL 위반이라 이 분기를 실제로 통과시키지 않음(양쪽 다 실패 → null 케이스만 커버). 코드 로직 자체(193-209행)는 정적으로 타당하나 "폴백 성공" 경로는 무테스트 — A-ISSUE-63과 함께 기록 |
| TC-EXC-012 | PASS | [실동작][테스트커버] | `GlobalExceptionHandlerDataIntegrityTest.responseHasNoConstraintNameOrSql`이 5개 시나리오 모두에서 응답 메시지에 제약명·SQL·SQLState·"constraint"/"sql" 문자열이 없음을 단언(114-134행). 실동작 측에서도 본 세션의 모든 에러 응답이 고정 `ErrorCode` 메시지만 반환하고 스택/SQL 문구가 전혀 노출되지 않음을 반복 확인 |
| TC-EXC-013 | PASS | [실동작] | WORKER 토큰으로 `POST /v1/reviews/4/approve`(REVIEWER 전용) → 403 `{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`. `GlobalExceptionHandler.java:211-216` |
| TC-EXC-014 | PASS | [실동작] | `Authorization: Bearer garbage.invalid.token`·헤더 없음·60초 만료 토큰 발급 시도 등 다회 확인 → 전부 401 `{"errorCode":"UNAUTHORIZED","message":"인증이 필요합니다."}`. `GlobalExceptionHandler.java:218-223` |
| TC-EXC-015 | PASS | [실동작] | `GET /v1/does-not-exist-xyz` → 404 `{"errorCode":"NOT_FOUND","message":"요청한 API를 찾을 수 없습니다."}`. `GlobalExceptionHandler.java:241-246`. (근거 드리프트: 카탈로그 라인 241-246은 현재도 정확 일치) |
| TC-EXC-016 | PASS | [정적][테스트커버] | 500MB(local) 실제 초과 페이로드 생성은 이번 세션 비용 대비 편익이 낮아 미실시. 대신 `MaxUploadSizeExceededMappingTest.mapsToPayloadTooLarge`가 `handleMaxUploadSizeExceeded` 직접 호출로 413/PAYLOAD_TOO_LARGE + 고정 메시지(내부 한도 수치 비노출)를 확인. `GlobalExceptionHandler.java:234-239` 코드와 일치 |
| TC-EXC-017 | PASS | [실동작][테스트커버] | 실동작으로 순수 무예외 계층 500(임의 RuntimeException)을 직접 유발할 취약점은 발견하지 못함(안전한 신호) — 대신 `GlobalExceptionHandlerTest.unhandledExceptionReturnsInternalError`가 `/test/runtime-ex`로 500/INTERNAL_ERROR 확인. `ApiResponse` record가 success/data/message/errorCode 4필드로 고정돼 있어 구조적으로 스택트레이스가 응답 바디에 실릴 경로가 없음(`GlobalExceptionHandler.java:248-253`, 실측 라인은 273-278로 드리프트 — 아래 근거 드리프트 참고) |
| TC-LOG-001 | PASS | [실동작][테스트커버] | 원시 소켓으로 `X-Trace-Id: abc\tdef`(tab 포함, Tomcat이 값으로 허용) 전송 → 응답 `X-Trace-Id: abcdef`(tab 제거) 확인. `LogSanitizerTest`(`LogSanitizer_개행_탭_NULL_등_제어문자_제거` 등 12개 메서드)로 유닛 레벨 전건 커버. `LogSanitizer.java:88-100` |
| TC-LOG-002 | PASS | [실동작][테스트커버] | 원시 소켓으로 UTF-8 U+2028(LINE SEPARATOR) 바이트를 트레이스ID 헤더에 삽입 → 200 응답 + 헤더값에서 제거 확인(단, 이 경로는 RequestIdFilter의 별도 정규식이며 LogSanitizer 클래스 자체는 아님). LogSanitizer 클래스 단위 테스트 `LogSanitizer_유니코드_라인구분자_U2028_U2029_제거`가 직접 커버. `LogSanitizer.java:78-99` |
| TC-LOG-003 | PASS | [테스트커버][정적] | `LogSanitizer_한글과_가시문자_보존` 테스트 확인. 소스상 `U+0020`(SPACE_SEPARATOR)만 예외 처리해 보존(`LogSanitizer.java:86,93-95`) |
| TC-LOG-004 | PASS | [실동작][테스트커버] | `SortAllowlist`가 실제로 이 유틸을 64자 상한으로 호출하는 경로(아래 TC-SORT-005)에서 정상 동작 확인. 200자 상한/절단 접미사는 `LogSanitizer_길이_상한_초과시_잘라내고_접미사_추가` 테스트로 커버. `LogSanitizer.java:25,28,51-72` |
| TC-LOG-005 | PASS | [테스트커버] | `LogSanitizer_null_입력시_플레이스홀더_반환` 테스트로 `"(null)"` 반환·NPE 없음 확인. `LogSanitizer.java:27,52-54` |
| TC-SORT-001 | PASS | [실동작] | `GET /v1/tasks/board?sort=secretField,desc`(REVIEWER) → 400 `{"errorCode":"INVALID_INPUT","message":"지원하지 않는 정렬 기준입니다."}` — 입력값·내부 필드명 응답에 미노출. `SortAllowlist.java:139-162` |
| TC-SORT-002 | PASS | [실동작] | `GET /v1/reviews?sort=secretField,desc` → 200 정상 목록 반환(폴백 정렬 적용, 거부 없음). `GET /v1/videos?sort=secretField,desc`도 동일하게 200 확인. ★2 확정 정책과 정확히 일치 — "비일관"으로 재보고하지 않음. `SortAllowlist.java:189-215` |
| TC-SORT-003 | PASS | [실동작] | strict: `TASK_BOARD`(고유 필드 3개: regDt/shtDt/rawSn)에 4개 정렬 키 지정 → 400 `"정렬 기준이 너무 많습니다."`. lenient: `VIDEO`(고유 필드 4개)에 5개 정렬 키 지정 → 200 + 전체 폴백(기본 정렬인 `regDt desc`로 rawSn=11 최상단, 부분 적용 없음) 확인. `SortAllowlist.java:143,193,198-203,223-225` |
| TC-SORT-004 | PASS | [실동작] | `GET /v1/videos?sort=capturedAt,asc&sort=shtDt,desc`(둘 다 `shtDt`로 매핑되는 별칭) → 응답이 `shtDt` 오름차순으로 정렬됨(첫 지정 `capturedAt,asc`만 적용, 뒤 `shtDt,desc`는 무시) 확인. `SortAllowlist.java:145,157-159,210-212` |
| TC-SORT-005 | PASS | [실동작] | `GET /v1/videos?sort=a%0Afakeinjected,desc`(개행 주입) → 200. `docker compose logs`에서 `WARN ... [Sort] unsupported sort key ignored key=afakeinjected`로 **단일 로그 라인**에 개행 없이 출력됨을 확인(가짜 로그 라인 위조 불가). `SortAllowlist.java:66,200-201,206-207` |
| TC-SORT-006 | PASS | [정적] | `SortFieldMapper.apply/mapSort`(`SortFieldMapper.java:57-70`)가 전부 `SortAllowlist.resolveLenient`에 위임하며 자체 allowlist 사본을 두지 않음을 소스로 확인. `VIDEO` 상수는 `SortAllowlist.java:133-140` 1곳에만 정의 |
| TC-BLANK-001 | PARTIAL | [실동작(부분)][정적] | 코드 로직(`Character.isWhitespace` 파생 목록 + `replace`+`trim`)이 Java `isBlank()`와 동치임은 정적으로 확인했고, `q=CCTV-001` 검색으로 이 식이 실제 SQL에 반영되는 것(TC-BLANK-002 근거)도 확인했다. 그러나 **`CCTV_NM='\t'`류의 공백뿐인 실 데이터가 시드(`mng_resource_cctv`, 13행 전부 정상 한글명)에 없고**, 절대 규칙상 DB에 테스트용 행을 직접 INSERT할 수 없어 "폴백값(VMS_CCTV_ID)으로 검색해도 결과가 나온다"는 **최종 단언은 end-to-end로 재현하지 못함**. 또한 `BlankTextPredicate` 전용 단위 테스트 파일이 **존재하지 않음**(`find`/`grep` 결과 0건) — A-ISSUE-61 |
| TC-BLANK-002 | PASS | [실동작] | `GET /v1/tasks/board?q=CCTV-001` 실행 후 백엔드 SQL 로그에서 `trim(BOTH from replace(replace(...replace(mrc2_0.CCTV_NM, ?, ''), ?, '')...))<>?` 형태로 공백류 전부가 **바인드 파라미터(`?`)** 로 전달됨을 확인(문자열 연결 없음, CWE-89 안전). `BlankTextPredicate.java:49,62-65,68-76` |

## 집계

| 판정 | 건수 |
|---|--:|
| PASS | 38 |
| PARTIAL | 1 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **39** |

## 이슈

### [A-ISSUE-61] TC-BLANK-001 — BlankTextPredicate 전용 단위 테스트 0건 + 실제 blank 데이터 end-to-end 미검증
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CCTV_NM='\t'`처럼 표시측 Java `isBlank()`로는 공백 판정되는 CCTV가, 검색측 SQL에서도 동일하게 공백으로 판정돼 화면 폴백값(`VMS_CCTV_ID`)으로 검색해도 결과가 나와야 한다(작업목록·검수목록·배정목록 3개 리포지토리 공통 사용).
- **현재 동작(이슈 내용)**: `BlankTextPredicate.isBlankAsJava()`(`backend/src/main/java/kr/co/cudo/authoring/common/util/BlankTextPredicate.java:60-66`)는 `Character.isWhitespace` 파생 문자 목록으로 HQL `replace` 체인을 동적 생성하는, 오탈자·템플릿 문법 오류에 취약한 코드인데도 **전용 단위 테스트 파일이 저장소에 없다**(`find backend/src/test -iname "*BlankTextPredicate*"` → 0건, `grep -rl "BlankTextPredicate" backend/src/test` → 0건). 실동작 측에서도 `mng_resource_cctv` 시드 13행이 전부 정상 한글 CCTV명이라(`length=12`, 공백류 없음) `GET /v1/tasks/board?q=CCTV-001` 호출로는 SQL이 올바르게 **생성**되는 것만 확인했을 뿐, 실제로 blank 이름을 가진 행이 폴백 검색으로 **찾아지는지**는 이번 세션에서 재현 불가능했다(DB에 테스트 행을 직접 INSERT하는 것은 이번 검증의 절대 규칙 위반이라 시도하지 않음).
- **재현/확인 경로**: `find backend/src/test -iname "*BlankTextPredicate*"`(0건) / SQL: `INSERT INTO mng_resource_cctv (vms_cctv_id, cctv_nm, ...) VALUES ('CCTV-TEST', E'\t', ...)` 후 `GET /v1/tasks/board?q=CCTV-TEST` 응답에 해당 영상이 노출되는지 확인(다음 회차에 별도 시드/테스트 데이터로 재시도 권장).
- **영향**: 기능/보안 결함이 확정된 것은 아니다(코드 리뷰상 로직은 타당). 다만 이 컴포넌트는 3개 리포지토리가 공유하는 QueryDSL 표현식 생성 로직이라, 향후 Hibernate/QueryDSL 버전 업이나 리팩토링 시 템플릿 문법이 조용히 깨져도 잡아낼 회귀 테스트가 전혀 없다는 것이 실질 리스크(회귀 안전망 부재).
- **수정 방향(제안)**: `BlankTextPredicateTest`(순수 QueryDSL `Expressions` 대상 유닛 테스트 또는 Testcontainers 기반 실제 PostgreSQL round-trip 테스트)를 추가해 최소 "탭/줄바꿈/전각공백만 있는 문자열 → blank 판정" 케이스를 커버할 것을 제안. 아울러 다음 검증 회차 전에 `mng_resource_cctv` 개발 시드에 공백류 이름 CCTV 1건을 추가해 실동작 종단 검증을 완성할 것을 제안.

### [A-ISSUE-62] TC-EXC-009/011 — DataIntegrityViolation 4번째 분기(label_dtct_type)·제약명 정규식 폴백 성공 경로 테스트 공백
- **심각도**: LOW
- **기대 동작(기대효과)**: `GlobalExceptionHandler.handleDataIntegrityViolation`의 4개 유니크 인덱스 분기(work_lock/preset_labelId/label_name_ci/label_dtct_type) 및 `extractConstraintName`의 2단계 추출(Hibernate 우선 → SQL 메시지 정규식 폴백) 전 경로가 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: `GlobalExceptionHandlerDataIntegrityTest`(`backend/src/test/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandlerDataIntegrityTest.java:85-97`)의 `cases()`는 work_lock·preset_labelId·label_name_ci·FK·null-constraint **5가지만** 파라미터화돼 있고, `label_dtct_type` 유니크 위반(`GlobalExceptionHandler.java:156-160`)은 포함되지 않는다. 또한 `nullConstraintName()`(75-83행)이 SQL 메시지에 애초에 `constraint "..."` 토큰이 없는 NOT NULL 위반이라, `extractConstraintName`의 **정규식 폴백이 실제로 성공하는** 케이스(Hibernate 이름은 null이지만 SQL 메시지에는 제약명이 있는 경우, `GlobalExceptionHandler.java:202-208`)는 어떤 테스트에도 등장하지 않는다.
- **재현/확인 경로**: `grep -n "Arguments.of" backend/src/test/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandlerDataIntegrityTest.java` → 5건만 확인, `label_dtct_type`/정규식-폴백-성공 케이스 부재.
- **영향**: 코드 로직 자체는 앞의 3개 유니크 분기와 완전히 동형 패턴(`equalsIgnoreCase` 비교)이라 기능 결함 가능성은 낮음(LOW). 다만 향후 `LABEL_DTCT_TYPE_UNIQUE_INDEX` 상수명이 실제 Flyway 인덱스명과 드리프트되거나, 정규식 폴백 로직이 리팩토링으로 깨져도 이 2개 지점은 테스트가 잡아내지 못한다.
- **수정 방향(제안)**: `cases()`에 `labelDtctTypeUniqueViolation()` 케이스 추가 + `extractConstraintName`이 정규식 폴백으로 실제 이름을 추출해내는 케이스(Hibernate name=null, SQL 메시지에 `constraint "uk_ls_label_dtct_type"` 포함) 1건 추가 제안.

## 참고 — 확정 정책/미해소 우려 대조

- **★2(목록 정렬 strict/lenient 비대칭)**: TC-SORT-001/002/003에서 `/v1/tasks/board`=400, `/v1/reviews`·`/v1/videos`=200+폴백을 실동작으로 정확히 재확인. UNCERTAINTIES.md 지침대로 "비일관"으로 보고하지 않음.
- **UNCERTAINTIES #7(Logback 마스킹 레이아웃)**: 이번 A-6 검증 범위(RequestIdFilter/SortAllowlist WARN 로그)에서 마스킹 대상(비밀번호·토큰 등 민감 필드)이 포함된 로그를 직접 유발하진 못했으나, `logback-spring.xml`의 `local` 프로파일이 `LayoutWrappingEncoder`+`MaskingPatternLayout`으로 실제 배선돼 있고(주석상 A-ISSUE-24 해소 기록) `docker compose logs`에서 traceId가 `[%X{traceId:-}]` 패턴대로 매 라인에 정확히 출력되는 것을 반복 확인함 — 배선 자체는 실동작으로 재확인됨. 잔여 "런타임 프로브 비결정적" 상태는 A-6 범위를 벗어나는 민감정보 마스킹 트리거(예: 인증 실패 시 토큰 로깅 등)에 대한 것으로, 다른 파트(A-1~A-5, A-7) 또는 D/F 클러스터의 관제 통지·포털 인증 경로에서 재시도 권장.
- **근거 드리프트**: TC-EXC-017의 카탈로그 근거 `GlobalExceptionHandler.java:248-253`은 현재 실제로는 `handleUnknown` 메서드가 273-278행에 위치(405 `HttpRequestMethodNotSupportedException` 핸들러가 2026-07-30 배포 검증에서 추가되며 그 아래로 밀림). 기능 동작 자체(500/INTERNAL_ERROR)는 정확하므로 FAIL로 승격하지 않고 근거 드리프트로만 기록.
- **부가 관찰(이슈 아님)**: `GlobalExceptionHandler`에 `HttpRequestMethodNotSupportedException`(405, `Allow` 헤더 포함) 전용 핸들러가 존재하며 실동작으로 `DELETE /v1/tasks/board` → 405 확인. 이 케이스는 39건 카탈로그에 TC ID가 없어(신규 추가분 미등재) 판정표에는 포함하지 않았으나, 향후 카탈로그 갱신 시 TC-EXC-018로 추가할 것을 제안.
