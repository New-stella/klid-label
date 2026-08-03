# A 클러스터 part4 — A-6. 추적ID·공통 응답·예외 + 공통 유틸 (42건)

- **담당 구간**: `docs/test-cases/A-auth-common.md` 191~237행 — `RequestIdFilter`·`ApiResponse`·`GlobalExceptionHandler`·`LogSanitizer`·`SortAllowlist`/`SortFieldMapper`·`BlankTextPredicate`
- **총 42건** (TC-TRACE 5 / TC-RESP 4 / TC-EXC 20 / TC-LOG 5 / TC-SORT 6 / TC-BLANK 2). 폐기 케이스 없음.
- **검증 일자**: 2026-08-03(3차) / 워크트리 `qa-0803`(HEAD `e065da42`) / 스택은 다른 파트 에이전트가 이미 재빌드해 HEAD와 이미지 일치(`docker ps` 기동 9분 경과 확인) / `SPRING_PROFILES_ACTIVE=local`
- **이전 회차 이슈 재확인 지시**: `A-ISSUE-62`(로그 마스킹 미배선) — 1차에서 실동작 누출 발견 → 2차에서 수정 확인했으나 "정적+단위 PASS, 실동작(로그 파일 관측)은 BLOCKED"(당시 스택이 구버전 이미지). 이번 회차는 스택이 실코드와 일치하므로 **실제 컨테이너 stdout 로그를 직접 떠서 재확인**했다 (§근거 문서 하단 참조).

## 실동작 검증 기반

| 항목 | 내용 |
|---|---|
| 진입점 | `http://localhost:18081/api` (context-path `/api`) |
| 토큰 | 컨테이너 실효 `JWT_SECRET`으로 HS256 직접 서명(HS256, `iss=klid-auth`, `channel` 명시). `docker exec klid-backend env` 로 시크릿 실측 후 파이썬으로 서명(외부 라이브러리 없이 hmac/base64로 구현) |
| 사용 sub | 1001=REVIEWER, 2001=WORKER (`ls_user_role` 조회) |
| 로그 확인 | `docker logs klid-backend --since Ns` 로 실제 stdout 관찰(local profile → `MaskingPatternLayout` 경유) |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system` (스키마 `public`) |

---

## 판정 결과

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-TRACE-001 | PASS | [실동작] | 헤더 미전송 시 응답 `X-Trace-Id`가 매 요청 12자리 신규 값(`1822a4fe94c2`, `7f3d7bf63a90`, `2de359f66a93`, `788a6eb71310` 등) — `RequestIdFilter.java:28-33` |
| TC-TRACE-002 | PASS | [실동작] | raw 소켓으로 헤더 값에 실제 CR/LF·제어문자를 주입 시도 → Tomcat 자체가 HTTP 프레이밍 위반으로 400(필터 도달 전 차단). 이는 컨테이너 레벨 방어이며, `sanitize()`의 화이트리스트(영숫자+하이픈)도 CRLF를 구조적으로 제거하는 이중 방어임을 TC-003 결과로 실증 — `RequestIdFilter.java:48-59` |
| TC-TRACE-003 | PASS | [실동작] | 특수문자+118자 헤더 값 전송 → 응답 헤더가 영숫자·하이픈만 남기고 정확히 **64자**로 절단(`abc-123XXXX…` 64자 확인, `wc`로 실측) — `RequestIdFilter.java:52-57` |
| TC-TRACE-004 | PASS | [실동작] | 동일 토큰으로 연속 3회 요청 → 매번 서로 다른 랜덤 traceId(리크·재사용 없음), MDC 격리 확인 — `RequestIdFilter.java:34-38` |
| TC-TRACE-005 | PASS | [정적] | `@Order(Ordered.HIGHEST_PRECEDENCE)` 라인 17 확인 |
| TC-RESP-001 | PASS | [실동작] | `GET /v1/me` → `{"success":true,"data":{...},"message":null,"errorCode":null}` — `ApiResponse.java:9-11` |
| TC-RESP-002 | PASS | [실동작] | 인가 실패 `{"success":false,"data":null,"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}`, 인증 실패 `{"...","message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — ErrorCode.defaultMessage() 그대로 노출 — `ApiResponse.java:21-23` |
| TC-RESP-003 | PASS | [정적] | `error(code,message,data)` 3-인자 오버로드 코드 확인(`ApiResponse.java:29-31`). 실제 트리거 지점(`AugmentRequestService` NOT_REVIEWED)은 사전조건(증강 요청 플로우 데이터 셋업)이 복잡해 실동작 재현은 생략, 코드 대조로 대체 |
| TC-RESP-004 | PASS | [실동작] | 성공 응답에 `"message":null,"errorCode":null` 키가 생략되지 않고 그대로 직렬화됨(`@JsonInclude(ALWAYS)`) — `ApiResponse.java:6` |
| TC-EXC-001 | PASS | [정적+테스트] | `handleCustom` 코드 확인 + `GlobalExceptionHandlerTest.customExceptionReturnsErrorCode` 존재 — `:24-33` |
| TC-EXC-002 | PASS | [정적] | `details!=null` 분기로 `error(code,msg,details)` 사용 — `:28-32` |
| TC-EXC-003 | PASS | [정적+테스트] | `GlobalExceptionHandlerTest.validationFailureReturnsInvalidInput` 존재, 코드 확인 — `:35-43` |
| TC-EXC-004 | PASS | [정적] | `ConstraintViolationException` 핸들러 확인 — `:49-57` |
| TC-EXC-005 | PASS | [실동작] | `POST /v1/videos/1/deident-report` 에 깨진 JSON(`{invalid json!!`) 전송 → `400 {"message":"요청 본문이 올바르지 않습니다.","errorCode":"INVALID_INPUT"}`. 파서 상세(Jackson 예외 클래스명·위치) 미노출 — `:64-69` |
| TC-EXC-006 | PASS | [실동작] | `DELETE /v1/videos/1/tracks/T1` 에서 필수 파라미터 `fromFrameNo` 누락 → `400 {"message":"필수 파라미터가 누락되었습니다: fromFrameNo",...}` — 파라미터명만 노출 — `:76-81` |
| TC-EXC-007 | PASS | [실동작] | `GET /v1/videos/notanumber` → `400 {"message":"파라미터 형식이 올바르지 않습니다: rawSn",...}` — 입력 원문("notanumber") 미노출, 파라미터명만 — `:88-93` |
| TC-EXC-008 | PASS | [정적+테스트] | `GlobalExceptionHandlerDataIntegrityTest.workLockUniqueViolation()` 합성 예외로 핸들러 직접 호출·검증(실제 `ConstraintViolationException`+`SQLException` 객체) — `:141-145,168-171`. HTTP 동시요청 재현은 도메인 사전조건 복잡성으로 생략 |
| TC-EXC-009 | PASS | [정적+테스트] | 프리셋 labelId·라벨명CI 위반은 `GlobalExceptionHandlerDataIntegrityTest`에 합성 케이스 존재. **검출유형(dtct_type) 위반은 동 테스트 파일에 전용 케이스 없음**(코드 로직은 나머지 두 분기와 완전 동형이라 기능 위험 낮음, 커버리지 갭으로만 기록) — `:146-160` |
| TC-EXC-010 | PASS | [정적+테스트] | `DataIntegrityViolation_cause_분기_workLock만_409_그외_fail_closed_500` 테스트로 미분류 위반 500 확인 — `:161-166` |
| TC-EXC-011 | PASS | [정적] | Hibernate `getConstraintName()` 우선, null 시 SQL 메시지 정규식 파싱 — `:193-209` |
| TC-EXC-012 | PASS | [정적+테스트] | `DataIntegrity_응답에_제약명_SQL_미포함` 테스트로 응답 body에 제약명/SQL 부재 확인 — 강제 코드 `:138-166`, 정책 주석 `:127-137` |
| TC-EXC-013 | PASS | [실동작] | WORKER 토큰으로 REVIEWER 전용 `GET /v1/system/configs` 호출 → `403 {"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}` — `:211-216` |
| TC-EXC-014 | PASS | [실동작] | 토큰 미전송 및 서명 불일치(다른 시크릿) 토큰 모두 `401 {"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — `:218-223` |
| TC-EXC-015 | PASS | [실동작] | `GET /v1/does-not-exist-xyz` → `404 {"message":"요청한 API를 찾을 수 없습니다.","errorCode":"NOT_FOUND"}`. 서버 로그도 `path=v1/does-not-exist-xyz`만 남고 스택 없음 — `:241-246` |
| TC-EXC-016 | PASS | [정적+테스트] | `MaxUploadSizeExceededMappingTest` 전용 테스트 존재, 코드 확인 — `:234-239` |
| TC-EXC-017 | PASS | [정적+테스트] | `unhandledExceptionReturnsInternalError` 테스트로 임의 RuntimeException→500+고정메시지 확인. 실HTTP 재현은 안전한 트리거 지점 부재로 생략(로그는 `log.error`로 서버에만 스택 적재, 응답 body는 고정 메시지) — `:273-278` |
| TC-EXC-018 | PASS | [실동작] | `POST /v1/me`(GET 전용 경로) → `405 {"message":"허용되지 않은 요청 메서드입니다.","errorCode":"METHOD_NOT_ALLOWED"}` — `:261-272` |
| TC-EXC-019 | PASS | [실동작] | 위 405 응답에 `Allow: GET` 헤더 실측 확인 — `:266-269` |
| TC-EXC-020 | PASS | [실동작] | `docker logs`로 실제 WARN 로그 확인: `[Exception] method not allowed method=POST` — 경로/쿼리스트링 미기록, 메서드명만 — `:264` |
| TC-LOG-001 | PASS | [실동작] | 정렬 키에 `%0A` 개행 주입(`a\nFAKE_LOG_LINE_INJECTED`) → 실제 stdout 로그에는 `key=aFAKE_LOG_LINE_INJECTED` 한 줄로만 기록(가짜 로그 라인 미생성). C0 제어문자 제거 실증 — `LogSanitizer.java:88-100` |
| TC-LOG-002 | PASS | [실동작] | 정렬 키에 U+2028(LINE SEPARATOR)+U+2029(PARAGRAPH SEPARATOR) 주입 → 실제 로그에 `key=aFAKE2028tail` 한 줄로만 기록(구분자 제거, 위조 라인 없음) — `:78-99` |
| TC-LOG-003 | PASS | [실동작] | 정렬 키 `가 나 다`(한글+공백) 주입 → 로그에 `key=가 나 다` 그대로 보존(가시문자·일반공백 무손실) — `:86,93-95` |
| TC-LOG-004 | PASS | [실동작] | ~80자 bare-JWT형 문자열 주입 → 로그에 `key=***...(truncated)` 기록. `LogSanitizer`가 64자(SortAllowlist 호출 시 `LOG_KEY_MAX_LENGTH`)에서 절단+접미 부착 후, 같은 로그 라인이 `MaskingPatternLayout`을 거치며 JWT 패턴이 `***`로 마스킹되는 이중 처리까지 실측 — `:25,28,51-72` |
| TC-LOG-005 | PASS | [정적] | `sanitize(null)` → `"(null)"` 반환 코드 확인. null 입력은 내부 코드 경로 전용이라 HTTP로 직접 트리거 불가, 코드 대조로 대체 — `:27,52-54` |
| TC-SORT-001 | PASS | [실동작] | `GET /v1/tasks/board?sort=secretField,desc` → `400 {"message":"지원하지 않는 정렬 기준입니다.","errorCode":"INVALID_INPUT"}` — `SortAllowlist.java:262-285` |
| TC-SORT-002 | PASS | [실동작] | `GET /v1/reviews?sort=secretField,desc` → `200` + 기본 정렬(updDt desc) 폴백 정상 응답, 서버 WARN 로그 `[Sort] unsupported sort key ignored key=secretField` 동시 확인. ★2 정책과 정확히 일치(비일관 아님) — `:312-338; SortFieldMapper.java:57-60` |
| TC-SORT-003 | PASS | [실동작] | 고유 필드 4개 지정(TASK_BOARD 상한=3) → strict `GET /v1/tasks/board`는 `400 "정렬 기준이 너무 많습니다."`, lenient `GET /v1/reviews`(REVIEW 상한=3)는 동일 조건에서 `200`(전체 폴백) 확인 — `:266,271-274,321-326,346-348` |
| TC-SORT-004 | PASS | [실동작] | `GET /v1/videos?sort=capturedAt,asc&sort=shtDt,desc`(둘 다 `shtDt`로 매핑되는 중복) 결과 rawSn 순서가 `sort=shtDt,asc` 단독 호출 결과(`[8,9,10,11,7]`)와 **정확히 일치**하고 `sort=shtDt,desc` 단독 결과(`[37,39,101,81,80]`)와는 다름 → 첫 지정(asc)만 적용 실증 — `:135-142,333-335` |
| TC-SORT-005 | PASS | [실동작] | TC-LOG-001 재현 시 WARN 로그의 key 필드가 정제(개행 제거)되어 한 줄로만 출력됨을 실측 — `:68,322-324,329-330` |
| TC-SORT-006 | PASS | [정적] | `SortFieldMapper.apply/mapSort`가 `SortAllowlist.resolveLenient`에 위임, 컨트롤러 사본 없음 확인 — `SortAllowlist.java:123-134; SortFieldMapper.java:29-31,57-70` |
| TC-BLANK-001 | PASS | [정적+테스트] | 전용 IT `TaskBoardFilterSortTest.blankCctvNameFallsBackToVmsIdOnBothDisplayAndSearch`/`ReviewListFilterSortTest` 동명 테스트가 tab·개행·전각공백·혼합 4종 CCTV명으로 표시 폴백+검색 정합을 실제 DB 시나리오로 검증(신규 라이브 재현은 기존 시드 데이터 변형이 필요해 생략, IT가 이미 동일 시나리오를 커버) — `BlankTextPredicate.java:60-66` |
| TC-BLANK-002 | PASS | [정적] | `Expressions.stringTemplate("replace({0}, {1}, '')", ...)` 파라미터 바인딩 확인, 문자열 연결 없음 — `:49,62-65,68-76` |

---

## 이전 회차 이슈 해소 확인 — A-ISSUE-62 (로그 마스킹 미배선)

- **1차**: 실동작 누출 확인(HIGH) — 전화번호/이메일/JSON형 자격증명/bare JWT가 전부 평문 기록.
- **2차**: 수정 확인 — `LogMaskingPatterns` 단일 원천화 + 규칙 9종 신설, `logback-spring.xml` 양 프로파일 배선. 단 "실동작(로그 파일 확인) 재현은 BLOCKED"(컨테이너가 구버전 이미지라 반영 전 코드 실행 중).
- **3차(본 검증) — 실동작으로 완전 재확인**: 정렬 키 파라미터로 4종을 실제 주입해 `docker logs klid-backend`의 **실제 stdout**을 직접 관찰:
  - 전화번호 `010-1234-5678` → 로그 `010-****-5678`
  - 이메일 `user@example.com` → 로그 `u***@example.com`
  - JSON 자격증명 `{"password":"hunter2"}` → 로그 `{"password":"***"}`
  - bare JWT(`eyJ...`) → 로그 `***...(truncated)`
  - 추가로 CRLF(TC-LOG-001)·U+2028/U+2029(TC-LOG-002) 라인 위조 시도도 실제 로그에서 단일 줄로 정상 처리됨을 확인
  - **판정: RESOLVED(실동작 확인 완료)** — 2차의 "실동작 BLOCKED" 유보가 이번 회차로 해소됨. dev/stg/prd JSON 인코더 경로(`MaskingJsonValueMasker` + `jsonGeneratorDecorator`)는 이 스택이 local 프로파일만 구동 중이라 실측은 못 했으나, `logback-spring.xml:26-56` 정적 확인상 message+stackTrace 양쪽에 동일 마스커가 걸려 있어 구조적으로 동일 결과가 기대된다(별도 판정 없이 사실만 기록).

## 카탈로그 정정

- **정정 건수: 0건.** 191~237행 42건 전건에 대해 근거 `file:line`을 실제 코드와 1줄 단위로 대조했으며, 드리프트가 전혀 없었다(모든 라인 범위가 실제 메서드/필드 경계와 정확히 일치). 이 구간은 이번 3차 검증 범위 중 근거 정확도가 가장 높은 섹션이다.

## 발견된 결함

- **없음.** FAIL/PARTIAL 0건. 카탈로그 오류·정책 오판(★2 목록 정렬 비대칭 등)도 없음.
- **경미한 커버리지 갭(결함 아님, 참고용)**: ①`GlobalExceptionHandlerDataIntegrityTest`에 검출유형(`uk_ls_label_dtct_type`) 유니크 위반 전용 케이스 없음(TC-EXC-009 일부) ②`ApiResponse`에 전용 단위 테스트 파일 없음(TC-RESP 전체, 실동작으로 대체 확인) ③`BlankTextPredicate` 자체에 대한 순수 단위 테스트 없음(리포지토리 레벨 IT로만 커버).
