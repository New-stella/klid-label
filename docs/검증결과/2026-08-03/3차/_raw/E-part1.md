# E클러스터 part1 — E-1(증강 요청) + E-2(증강 검수) 실측 검증 결과 (3차, 2026-08-04)

담당: `docs/test-cases/E-augment-resolution-export-meta.md` §E-1(TC-AUG-001~018, +044~048 신설) · §E-2(TC-AUG-020~043)
환경: `_raw/stack-bringup.md`(HEAD e065da42 재빌드 완료) + `_raw/pipeline-drive.md`(rawSn=101 APPROVED 공용 데이터) 그대로 사용. 추가로 rawSn 11(FAILED·프레임0)·15/43(0프레임)·78(파생, ORGNL_RAW_SN=26)·900(DE_IDENT_YN='F') 기존 시드 데이터를 실측에 활용.
방법: REVIEWER(1001)/WORKER(2001) JWT를 `POST /v1/dev/tokens`로 발급해 `/v1/augments/**` 엔드포인트를 실제로 호출하고 응답·`ls_data_aug`/`ls_data_aug_rvw` DB 상태로 대조. 빌드/테스트 미실행, 결과 파일 1개만 생성.

## 판정 요약

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A(폐기) | 확인필요|
|---|---|---|---|---|---|---|---|
| E-1 (기존 18 + 신설 5) | 23 | 18 | 0 | 0 | 0 | 3(TC-AUG-003/012/013/014 중 003·012·013·014=4건 폐기) | 0 |
| E-2 (20~43) | 24 | 24 | 0 | 0 | 0 | 0 | 0 |
| **합계(폐기 제외 분모)** | **43** | **42** | **0** | **0** | **0** | **4 폐기 별도** | **0** |

FAIL 0건. 다만 **카탈로그 정합 이슈 1건(정정 완료)** + **미해소 이월 저severity 이슈 2건**을 기록한다.

## E-1. 증강 요청 — 케이스별 결과

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-AUG-001 | PASS | [실동작] `{videoIds:[101],types:["WINTER"]}`(prompt 누락) → `400 INVALID_INPUT "prompt: prompt 는 필수입니다."`. prompt 5필드 포함 재요청 → `200 {jobId,...,createdCount:1}`, DB `ls_data_aug`에 PENDING 1행(`idmp_key=AUG-<uuid>`, `otsd_job_id=null`). **카탈로그 입력열에 prompt 누락(E-ISSUE-02, 2026-08-01 지적 이후 2회차 미반영) → 본 회차에서 직접 정정**(라인 21) |
| TC-AUG-002 | PASS | [실동작] rawSn=11(FAILED, ls_raw_data_status 행 없음) 요청 → `400 NOT_REVIEWED {"blockedVideoIds":[11]}` |
| TC-AUG-003 | N/A(폐기) | 카탈로그 자체가 폐기 표기 — 검증 대상 제외 |
| TC-AUG-004 | PASS | [실동작] rawSn=43(APPROVED, DE_IDENT_YN='Y', 프레임 0건) 요청 → `412 PRECONDITION_FAILED "프레임이 추출되지 않은 영상은..." {"skippedVideoIds":[43]}`. (최초 rawSn=15로 시도했을 때는 DE_IDENT_YN='F'라 신고 게이트가 먼저 걸려 다른 사유가 나옴 — 가드 순서가 실제로 신고>프레임임을 역으로 재확인) |
| TC-AUG-005 | PASS | [실동작] WORKER 토큰으로 요청 → `403` |
| TC-AUG-006 | PASS | [실동작] 토큰 없이 요청 → `401` |
| TC-AUG-007 | PASS | [실동작] DB `idmp_key='AUG-66120a6d-...'`(정규식 `^[A-Za-z0-9_-]+$` 만족, ≤64) 확인. 생성 직후 `otsd_job_id=null` 확인(별도로 아주 짧은 지연 후 조회 시 mock 202 ACK로 채워짐 — "요청 시점 null, 외부 응답으로 반전" 그대로) |
| TC-AUG-008 | PASS[정적] | `createOneAugmentRequest`(`AugmentRequestService.java:365-407`) — 제약위반 외 일반 예외는 `false` 반환→`request()`가 `INTERNAL_ERROR` 회신. 라이브에서 `augRepository.save` 예외를 강제 주입하긴 어려워 코드 경로 확인으로 대체 |
| TC-AUG-009 | PASS[정적] | `AugmentRequestBridge.java` 클래스 헤더 — "구 ledger write 제거", `@TransactionalEventListener(AFTER_COMMIT)`로 커밋 후에만 위탁 확인. 요청 트랜잭션 자체를 인위 롤백시키긴 어려워 코드 구조로 확인 |
| TC-AUG-010 | PASS | [정적] `AugmentCallbackUrlResolver.resolve()` — trailing slash 제거 + `WebhookProtectedPaths.PATH_GENAI_CALLBACK="/v1/genai/callback"` 결합 확인(grep) |
| TC-AUG-011 | PASS(단, 알려진 갭 존속) | [실동작] 응답 `jobId`(예: 1785768859944)는 미영속 AtomicLong 값. **E-ISSUE-05(1차, jobId 값공간 불일치)는 3차에도 그대로 재현** — 결과조회 `{jobId}` 는 rawSn 이라 응답 jobId로 폴링 불가. 이번 케이스 자체(AtomicLong 충돌 없음)는 PASS, 별개 이슈로 이월 |
| TC-AUG-012/013/014 | N/A(폐기) | 카탈로그 자체가 폐기 표기 |
| TC-AUG-015 | PASS | [실동작] rawSn=101×WINTER PENDING 존재 상태에서 동일 요청 재전송 → `200`, 신규 PENDING 행(aug 47) 추가 생성. 상태 무관 재요청 허용 확인 |
| TC-AUG-016 | PASS | [실동작] rawSn=900(`DE_IDNTF_YN='F'`) 요청 → `412 PRECONDITION_FAILED "비식별 재처리 대기 중인 영상은..."` |
| TC-AUG-017 | PASS | [실동작] `videoIds:[101,102]` → `400 INVALID_INPUT "영상은 한 번에 1건만..."` |
| TC-AUG-018 | PASS[정적] | `V143__add_ls_data_aug_active_unique.sql:45` `RAISE EXCEPTION`, `:64` `WITH ranked` UPDATE 확인(grep). 과거 마이그레이션 재실행 불가(이미 적용됨) — 각주대로 회귀검증 전용 |
| TC-AUG-044(신설) | PASS | [실동작] rawSn=78(`ORGNL_RAW_SN=26`) 요청 → `400 INVALID_INPUT "파생 영상은 증강 요청 대상이 아닙니다." {"skippedVideoIds":[78]}` |
| TC-AUG-045(신설) | PASS[정적+간접실동작] | `request()` 호출 순서(`requireReviewer→buildPrompt→requireNotDerivative→findBlockedVideoIds→deidentReportGate→findFirstSrcSn`, `AugmentRequestService.java:140-204`) 확인 + rawSn=78 테스트가 "미검수" 아닌 "파생" 사유로 거부된 것으로 간접 확인 |
| TC-AUG-046(신설) | PASS | [실동작] 3종 모두 확인 — 공백만(`"  "`)→400 `@NotBlank`, 60자→400 `@Size`, ZWSP만(U+200B)→DTO는 통과하나 서비스 `VisibleTextNormalizer`가 400 "증강 생성 조건 항목은 비워둘 수 없습니다: time"으로 2차 방어 |
| TC-AUG-047(신설) | PASS | [실동작] 요청 dict `{"time":"낮",...}` 전송 후 DB `prompt_cn` 조회 결과 완전 동일 문자열 확인(aug 47) |
| TC-AUG-048(신설) | PASS[정적] | `augType = requireSingleSelection(request.types(),...).name()`(`:148-150`) — `types`만 유형을 결정하고 prompt 는 전혀 참조되지 않음을 코드로 확인 |

## E-2. 증강 검수 — 케이스별 결과

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-AUG-020 | PASS | [실동작] aug 51(WINTER, 생성결과 이미 ACCEPTED) accept → `200`, `ls_data_aug_rvw.rvw_stts_cd=ACCEPTED`, `ls_data_aug.aug_proc_stts_cd`는 accept 전후 무변화(ACCEPTED 그대로 — 두 축 분리 확인) |
| TC-AUG-021 | PASS | [실동작] aug 51 재accept → `409 CONFLICT "이미 처리된 증강 검수입니다. status=ACCEPTED"` |
| TC-AUG-022 | PASS | [실동작] aug 52 reject reason 없이 → `400 "reason: 반려 사유는 필수입니다."` |
| TC-AUG-023 | PASS | [실동작] aug 52 reject reason="화질 불량" → `200`, `rvw_stts_cd=REJECTED`. `aug_proc_stts_cd` 는 여전히 ACCEPTED(무변화) |
| TC-AUG-024 | PASS | [실동작] aug 53(RESL_1080P) accept → `400 "해상도 파생 결과는 검수 대상이 아닙니다."` |
| TC-AUG-025 | PASS | [실동작] aug 53 reject → 동일 400 |
| TC-AUG-026 | PASS | [실동작] 존재하지 않는 augSn(999999999) accept → `404` |
| TC-AUG-027 | PASS | [정적] `HttpExternalAugmentClient.syncDecision` — "외부 계약 미정의, no-op(로그+true)" 확인. throw 분기는 프로덕션 도달 불가(카탈로그 서술과 일치) |
| TC-AUG-028 | PASS | [실동작] WORKER accept → `403`, 무토큰 accept → `401` |
| TC-AUG-029 | PASS[정적+간접] | `listAll`(`:83-91`)·`buildJobs`(`:150-172`) 코드 확인 — distinct SRC_SN 페이징 + 4종 일괄조회(row/rawSn/cctv/review) 구조. `?srcSn=468` 조회 시 정상 그룹핑 응답으로 간접 확인 |
| TC-AUG-030 | PASS | [실동작] `?srcSn=468` → `types:["WINTER","NIGHT","RAIN"]`, `resolutionTypes:["RESL_1080P","RESL_720P","RESL_480P"]` — 완전 분리 확인 |
| TC-AUG-031 | PASS | [실동작] 위 응답의 정렬이 WINTER→NIGHT→RAIN, RESL_1080P→720P→480P 순 — `AUG_ORDER` 맵(`:58-66`)과 일치 |
| TC-AUG-032 | PASS | [실동작] `/v1/augments/101/result` → `status:"FAILED"`(dead_letter_at 보유 행 존재) |
| TC-AUG-033 | PASS[정적] | `aggregateStatus`(`:257-270`) — terminal==size → COMPLETED. 로직 단순·결정적이며 032/036 실동작으로 같은 메서드 간접 검증됨 |
| TC-AUG-034 | PASS[정적] | 동일 메서드 — 일부 종료 IN_PROGRESS / 전부 PENDING REQUESTED 분기 확인 |
| TC-AUG-035 | PASS | [실동작] RESL 행(48/49/50/53/55)이 전부 ACCEPTED(finalize 확정)로 COMPLETED 집계에 정상 반영됨을 위 result 응답에서 확인 |
| TC-AUG-036 | PASS | [실동작] `/101/result` → `FAILED`(dead-letter 존재) |
| TC-AUG-037 | PASS | [실동작] `/999999999/result` → `PROCESSING`(무데이터 폴백) |
| TC-AUG-038 | PASS[정적] | `resolveCctvName`(`:288-291`) — null/blank 시 `"(이름 없음)"` 폴백. rawSn=101은 CCTV 시드가 있어 실측 폴백 트리거는 못 봤으나 로직은 항상 non-null 반환 구조 |
| TC-AUG-039 | PASS | [실동작] `?srcSn=999999999` → `totalElements:0`, 빈 content |
| TC-AUG-040 | PASS(단, 알려진 갭 존속) | [실동작] `?size=101`(srcSn 없음) → `400 "size 한도 초과 (max=100)"`. `?srcSn=468&size=101` → **200**(size 검증 우회 재확인). **E-ISSUE-08(1차)이 3차에도 그대로 존재** — 카탈로그 자체에 이미 각주로 반영돼 있어 카탈로그 정정 불필요, 코드 결함으로 이월 |
| TC-AUG-041 | PASS[정적] | `resolveRawSnOrThrow`(`:435-445`) — srcSn 역해석 실패 시 `409 CONFLICT`. 실제 프레임을 삭제해 재현하는 것은 운영 데이터 파괴라 라이브 재현 보류, 코드로 확인 |
| TC-AUG-042 | PASS[정적+간접] | `loadOrCreateReview`(`:415-421`) — `findLatestByDataAugSn` 우선 확인. TC-AUG-021 재accept 시 새 PENDING 행이 아니라 기존 ACCEPTED 행을 그대로 찾아 409를 낸 것으로 간접 확인(신규 행이었다면 PENDING이라 통과했을 것) |
| TC-AUG-043 | PASS | [실동작] aug 770003(RAIN, 생성결과 PENDING, 2차 회차부터 미종결 상태로 존속) accept → `409 CONFLICT "증강 결과가 아직 생성되지 않았습니다..."`. (aug 47을 먼저 시도했으나 요청 후 110초가 지나 이미 웹훅이 ACCEPTED로 확정한 뒤였음 — 770003으로 재시도해 올바르게 재현) |

## 카탈로그 정정 (본 회차 직접 수정, `docs/test-cases/E-augment-resolution-export-meta.md` §E-1 범위)

1. **TC-AUG-001**(라인 21): 입력열에 필수 `prompt` 5필드 추가 + 미포함 시 400이라는 실동작 근거 명시. (E-ISSUE-02, 2026-08-01 최초 지적 후 2회차째 미반영이던 드리프트)
2. **TC-AUG-044~048 신설**(§E-1 말미, TC-AUG-018 뒤): E-ISSUE-04(1차)가 지적한 "카탈로그에 없는 신규 동작 8종" 중 요청측 5종(파생 차단·가드 순서·prompt 4종 검증·PROMPT_CN 원문 보관·types-prompt 비파생)을 전부 실동작으로 확인 후 신규 케이스로 등재. (검수측 2종은 이미 TC-AUG-043·discard 로직으로 커버돼 추가 불필요 확인)

## 이전 회차 이슈 해소 여부 (1차 `2026-08-01/1차/ISSUES.md` 대조, 내 담당 범위 TC-AUG-001~018·020~043 한정)

| 이슈 | 1차 상태 | 3차 실측 |
|---|---|---|
| E-ISSUE-01(TC-AUG-012/013/014/015/018) | 카탈로그가 폐기 정책 검증 중 | **✅ 해소** — 2026-08-02 회차에서 이미 취소선+폐기 처리됨(재확인, 변경 불필요) |
| E-ISSUE-02(TC-AUG-001 prompt 누락) | 카탈로그 입력열에 prompt 없음 | **❌ 미해소 → 본 회차에 직접 정정**(2회차 연속 미반영이었음, 위 "카탈로그 정정" 1번 참조) |
| E-ISSUE-03(TC-AUG-020/021 축분리 서술) | 구 구조 기대 | **✅ 해소** — 2026-08-03(2차 갱신분) 카탈로그가 이미 `ensurePending`/축분리로 정정됨(코드와 일치 재확인) |
| E-ISSUE-04(신규 동작 8종 미수록) | 카탈로그에 0건 | **🔶 부분 해소** — 요청측 5종은 본 회차에 TC-AUG-044~048로 신설. 검수측 2종(`requireGeneratedResult`·discard 기록)은 이미 TC-AUG-043과 discard 관련 서술로 커버되어 있음을 확인(추가 신설 불필요) |
| E-ISSUE-05(TC-AUG-011 jobId 값공간) | LOW, 미수정 | **미해소 이월** — 3차에도 동일 구조 재현(응답 jobId=AtomicLong 값, result 조회는 rawSn). FE가 이미 회피 중이라 실질 영향 없음, 코드 수정 여부는 사용자 판단 필요 |
| E-ISSUE-07(근거 file:line 드리프트, E-1/E-2) | 다수 드리프트 | **✅ 대부분 해소** — 2026-08-03 회차(changelog #3)에서 이미 일괄 정정됨. 본 회차 재대조 결과 TC-AUG-001~048 전 행의 근거 라인이 실제 코드와 일치(단, TC-AUG-001은 위 1번처럼 입력열 자체가 드리프트였음) |
| E-ISSUE-08(TC-AUG-040 srcSn 페이징 우회) | LOW, 미수정 | **미해소 이월** — 3차에도 `?srcSn=468&size=101`→200 재현. 카탈로그 각주는 이미 존재해 정정 불필요, 코드 수정 여부는 별도 판단 필요 |

## 작업 흔적

- 실동작 검증 중 REVIEWER API로 rawSn=101에 WINTER 증강 3건(aug 47/51/52) 신규 요청 → 웹훅이 비동기로 처리해 파생 rawSn 153/157/158 등이 실제 생성됨(정상 파이프라인 산출물). **삭제하지 않고 보존** — 다른 클러스터(E-3 이후, D)가 참조 중일 수 있음(1차 회차와 동일 컨벤션).
- 코드·설정·테스트 파일 무수정. 빌드/테스트 미실행.
- DB에 수동 INSERT/UPDATE 없음(전부 정상 API 호출로만 생성) — 이번 라운드는 데이터 롤백 대상 없음.
