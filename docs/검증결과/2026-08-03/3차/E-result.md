# E클러스터 — 3차(2026-08-03) 검증 결과 병합

> `_raw/E-part1.md` ~ `_raw/E-part7.md` 7개 파일을 순서대로 병합. 원본은 `_raw/`에 보존.

---

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

---

# E 클러스터 part2 — E-3 증강 결과 웹훅 / E-3B 증강 외부 위탁 (3차, 2026-08-03)

- 담당 범위: `docs/test-cases/E-augment-resolution-export-meta.md` **E-3**(74~108행, TC-AUG-050~078 29건) + **E-3B**(110~137행, TC-AUG-100~121 22건) = **51건**
  - ⚠ 지시서의 라인범위(69~133)는 드리프트다. 실제 섹션 헤딩은 E-3=74행 / E-3B=110행 / E-4=139행이며, 두 절의 케이스 합계는 지시서와 동일한 **51건**이다. TC-AUG-040~043(69~73행)은 E-2(증강 검수) 소속이라 part1 담당으로 두고 손대지 않았다.
- 폐기 5건(TC-AUG-054·056·058·059·062)은 통과율 분모에서 제외 → **검증 대상 46건**
- 검증 방식: 풀스택 실동작 우선(backend :18081 · mock-server :9400 · PostgreSQL). 빌드/테스트 미실행.
- 근거 스택: `_raw/stack-bringup.md`(HEAD `e065da42` 재빌드 후 기동, 외부연동 4종 mock-server 실배선) · `_raw/pipeline-drive.md`(rawSn=101 정상 파이프라인) · `_raw/test-baseline.md`(backend/frontend/ai-server 실패 0건)

## 판정 집계

| 판정 | 건수 |
|---|---:|
| PASS | 42 |
| FAIL | 0 |
| PARTIAL | 4 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **검증 대상 계** | **46** |
| (폐기 — 분모 제외) | 5 |

PASS율 42/46 = **91.3%**. 실동작 근거 케이스 **28건**([실동작] 표기), 나머지는 정적 대조 + 기존 자동테스트 대조.

---

## 0. 이번 회차에 실제로 구동한 것 (근거의 출처)

### 0-1. 정상 왕복 1회전 — 실제 증강 요청 → mock 위탁 → 웹훅 → 파생영상 생성

```bash
# REVIEWER 토큰 발급 후
POST /api/v1/augments/request
  {"videoIds":[101],"types":["WINTER"],
   "prompt":{"time":"NIGHT","season":"WINTER","weather":"RAIN","terrain":"ROAD","severity":"HIGH"}}
→ 200 {"jobId":1785768859946,"createdCount":1}
```

mock-server 인바운드(실경유 확인):

```
2026-08-03 16:40:15,335 [MOCK][GENAI] job accepted job_id=2a2fb36ef4084e168c80e22883aed5fe
                                       request_id=AUG-284edfa4-6118-4be9-8f7f-d07e74172310-1 mode=I2I inputs=10
INFO: 172.20.0.5:45850 - "POST /api/genai/jobs HTTP/1.1" 202 Accepted
... [MOCK][GENAI] webhook sent url=http://klid-backend:8080/api/v1/genai/callback status=200 job_id=2a2f...
```

DB 실측 (self-fill 아님 — 모든 값이 mock 응답에서 옴):

```
ls_data_aug     : data_aug_sn=52 src_sn=468 WINTER ACCEPTED otsd_job_id=2a2fb36ef4084e168c80e22883aed5fe new_raw_sn=158
ls_data_aug_job : aug_job_sn=13 job_seq=1 idmp_key=AUG-284edfa4-...-1 otsd_job_id=2a2f... SUCCEEDED tot_nocs=10
ls_data_aug_job_file : 10행, rslt_file_path_nm=/app/genai-out/genai/2a2fb36e.../001_frame-0_genai.jpg …
ls_data_raw     : raw_sn=158 orgnl_raw_sn=101 vms_clip_id=QA3RD-PIPELINE-DRIVE-001_AUG_WINTER_52
                  raw_file_path_nm=/app/storage/deidentified/videos/augment/101/158/WINTER.mp4
ls_data_src     : 567~576, src_file_path_nm=NULL / de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/158/frame-N.jpg
```

파일시스템:

```
/app/storage/raw/seed/101/deid/clip-9101-mask.mp4                     50854 bytes   (LS_DEIDENT_PROC_LOG 기록값)
/app/storage/deidentified/videos/augment/101/158/WINTER.mp4           50854 bytes   (바이트 동일 사본)
/app/storage/deidentified/frames/deid/158/frame-0.jpg                 13164 bytes   (외부 산출물 반입분)
```

### 0-2. ★ 반증 시도 3종 (지시서 필수 항목)

| 반증 항목 | 결과 | 근거 |
|---|---|---|
| **파생 깊이 1 고정** — 파생본 재증강 | ✅ 정책대로 차단 | `POST /v1/augments/request {"videoIds":[158],...}` → **400** `{"skippedVideoIds":[158],"message":"파생 영상은 증강 요청 대상이 아닙니다."}` |
| **신고 구간(`'F'`) 부모의 증강 콜백 인계** — "원본 신고와 무관" 정책 | ✅ 정책대로 **파생 생성됨** | 부모 rawSn=94(`de_ident_yn='F'`, 6프레임)에 aug 880017 심고 SUCCEEDED 콜백 → 200 `applied:true`, aug ACCEPTED, 신규 `raw_sn=169 orgnl_raw_sn=94` 생성. TC-AUG-056 폐기 판정이 실동작으로 확인됨 |
| **self-fill (외부 응답 없이 값 자체생성)** | ✅ 없음 | ①`otsd_job_id` = mock 202 응답값 ②`rslt_file_path_nm` = 콜백 `results[].output_file_path` 원문 ③파생 프레임 = `/app/genai-out/…` 외부 산출물 반입 사본 ④위탁 0건이면 아무 값도 안 생김(아래 TC-AUG-105) ⑤유일한 상수 채움은 `evnt_type` 미상 시 `"ETC"`(계약 필수 필드, TC-AUG-111 로 명시된 의도된 동작) |

---

## 1. E-3. 증강 결과 웹훅 (TC-AUG-050~078)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-AUG-050 | PASS | [실동작] §0-1 전체. 200 `{applied:true,requestId}`, aug ACCEPTED, 신규 RAW 158(`ORGNL_RAW_SN=101`), `RAW_FILE_PATH_NM`=자기 비식별 사본 경로(부모 원본 경로 폴백 **없음**), AFTER_COMMIT `AsyncAugmentFrameRunner` 기동 로그 확인. 근거 `GenAiCallbackService.java:90-158`·`AugmentJobRollup.java:64-69`·`AugmentResultService.java:409-448` 전부 실제와 일치 |
| TC-AUG-051 | PASS | [실동작] aug 880007 job FAILED(`GEN_FAIL`) 콜백 → 롤업 `rollup failed (partial failure — fail-closed)`, aug **REJECTED + rtry_nmtm=1 + dead_letter_at=2026-08-04 01:42:11**, `createAugmentedVideo` 미호출(신규 RAW 0건) |
| TC-AUG-052 | PASS | [실동작] 이미 SUCCEEDED 인 job(13) 에 동일 SUCCEEDED 재전송 → 200 `{"applied":false}` + `duplicate callback absorbed … state=SUCCEEDED`, 중복 영상 0건 |
| TC-AUG-053 | PASS | [실동작] ② aug 880011(externalJobId null) 에 **다른 증강(880003)이 보유한** `job_id=e3jobB1` 로 SUCCEEDED → **409** `"이미 다른 증강 결과에 인계된 작업 ID 입니다."` + WARN `otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 … ownerDataAugSn=880003`. **쓰기 이전 선점검사**라 PG 25P02(500) 없음 → 1차 E-ISSUE-05 해소 확인. ① 자기 자신 재수신은 TC-AUG-052 경로로 흡수 |
| ~~TC-AUG-054~~ | (폐기) | 분모 제외 |
| TC-AUG-055 | PASS | [실동작] ① 미발급 `request_id` → **401** `"발급되지 않은 request_id 입니다."` + `HmacWebhookFilter - downstream auth rejected ip=… path=/api/v1/genai/callback status=401`(rate-limit 집계 배선 확인). ② "job 은 있으나 aug 행 없음 = 404" 는 **FK CASCADE 로 도달 불가**(아래 E-ISSUE-29, 카탈로그 정정 완료) |
| ~~TC-AUG-056~~ | (폐기) | 분모 제외 — 단 폐기 근거(신고 구간에도 파생 생성)를 §0-2 에서 실동작 반증으로 확인함 |
| TC-AUG-057 | **PARTIAL** | [정적] `ParentGate.fail("parent has no frames")` 분기는 존재하나 **라이브 도달 불가**(E-ISSUE-22). 커버는 mock 단위테스트 `AugmentResultServiceTest.부모_프레임이_없으면_실패로_확정되고_영상과_프레임러너_미트리거` |
| ~~TC-AUG-058~~ | (폐기) | 분모 제외 |
| ~~TC-AUG-059~~ | (폐기) | 분모 제외 |
| TC-AUG-060 | PASS | [실동작] `status=CANCELED` → **400** `"status: status 는 RUNNING\|SUCCEEDED\|FAILED 중 하나여야 합니다."` |
| TC-AUG-061 | PASS | [실동작] `request_id="bad id!"` → 400 `"request_id 는 영숫자/대시/언더스코어만 허용됩니다."` / `job_id=".."` → 400 `"job_id 형식이 올바르지 않습니다."`(점-전용 세그먼트 차단 `(?!\.+$)` 실효 확인, CWE-22) |
| ~~TC-AUG-062~~ | (폐기) | 분모 제외 |
| TC-AUG-063 | PASS | [실동작] 마지막 job(880002) SUCCEEDED 콜백을 **동시 3건** 발사 → `applied:true` **정확히 1건**, 나머지 2건 `applied:false`. `ls_data_aug 880001` → ACCEPTED, `new_raw_sn=164` **1건만** 생성. `findByDataAugSnForUpdate` 선잠금 직렬화가 실동작으로 확인됨(CWE-362) |
| TC-AUG-064 | PASS | [실동작] 부모 rawSn=50(`de_ident_yn='N'`) 의 aug 880005 SUCCEEDED → WARN `result failed — parent unavailable … reason=parent has no deident artifact`, aug **REJECTED + dead-letter**, 신규 RAW 0건. 판정기 `LsDataRaw.hasDeidentArtifact()` 단일 헬퍼 확인 |
| TC-AUG-065 | **PARTIAL** | [정적] `GenAiWebhookIpAllowlist.java:44-55,63-77` 이 미설정/`none` → `allowed=List.of()` → `isAllowed()` 항상 false(fail-closed) 확인. **라이브 미검증** — 현 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` 명시라 403 을 재현하려면 설정 변경 후 재기동이 필요(§10-1 코드/설정 불변 규칙). 필터가 이 경로에 실제로 걸린다는 사실은 401 로그의 `HmacWebhookFilter … path=/api/v1/genai/callback` 으로 확인(E-ISSUE-23) |
| TC-AUG-066 | PASS | [정적+실동작] `GenAiIntegrationWiringGuard.verify()` 가 `mode=http`(미설정 포함) + allowlist `none/빈값` 이면 `IllegalStateException` 으로 기동 실패. 현 형상(mode=http + allowlist 명시)에서 **기동이 성공한 것**이 가드 통과의 실동작 근거. 커버: `GenAiIntegrationWiringGuardTest` |
| TC-AUG-067 | PASS | [실동작] job 13(externalJobId=2a2f…)에 `job_id=otherjob123` → **409** `"job_id 가 일치하지 않습니다."` + WARN `job_id mismatch … expected=2a2f… received=otherjob123`. 상태 미변경 확인 |
| TC-AUG-068 | PASS | [실동작] job 880007 `status=RUNNING,progress=50,current_step=GEN` → 200 `applied:true`, `job_stts_cd=RUNNING`, 롤업 로그 없음(결과처리 미수행) |
| TC-AUG-069 | PASS | [실동작] `output_file_path=/etc/passwd` 및 `/app/genai-out/../../etc/passwd` 둘 다 **400** `"output_file_path 가 허용된 저장 경로가 아닙니다."`. WARN `output path rejected (outside readable roots) … code=FORBIDDEN`(경로 원문 미노출, CWE-209). **job 상태 미변경**(880003 이 RECEIVED 유지 → 재전송 여지 보존) DB 확인. 검증 위치가 `AugmentJobSuccessApplier.verifyOutputPaths`(웹훅/회수 공용)라는 3차 기전 정정도 실제와 일치 |
| TC-AUG-070 | PASS | [정적] `VideoArtifactRootResolver.buildReadableRoots`(148-177) 가 `쓰기 allowlist ∪ external-read-roots` 이고 기본값이 빈 문자열 → 미설정 시 읽기 범위 = 쓰기 allowlist. `rejectFilesystemRoots` 로 `/` 지정 시 기동 실패. 현 env `STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out` 는 명시 확장분 |
| TC-AUG-071 | PASS | [실동작] job 880003(위탁 파일 2건)에 results 1건 SUCCEEDED → job `FAILED / ERR_CD=RESULT_COUNT_MISMATCH / "위탁 2건 대비 수신 1건"`, 롤업 fail-closed 로 aug 880003 **REJECTED + dead-letter**. 대조 위치가 `AugmentJobSuccessApplier.applySucceeded`(101-121)라는 3차 정정도 일치 |
| TC-AUG-072 | PASS | [실동작] SUCCEEDED + results 없음 → **400** `"SUCCEEDED 결과에는 results 가 필요합니다."` — 카탈로그 기대문구와 **일치**. → **1차 E-ISSUE-22 해소 확인**. 메트릭 `reason=missing_results` 는 코드 확인(`AugmentMetrics.REASON_MISSING_RESULTS`) |
| TC-AUG-073 | PASS | [실동작] 2청크 중 1청크만 SUCCEEDED → 200 `{"applied":false}` + `rollup deferred dataAugSn=880001 pendingJobSeqs=[2]`. job 자체의 SUCCEEDED 갱신은 커밋됨(DB 확인) |
| TC-AUG-074 | PASS | [실동작] `mdfcn_dt` 를 2일 전으로 심은 job 880013 → 스윕 tick(01:44:20)에서 `claimExpired` 1행 클레임 → `FAILED / EXPIRED / "외부 응답 없음 — 무갱신 경과 임계 초과로 만료 종결…"` → 롤업 `rollup=APPLIED` → aug 880013 REJECTED+dead-letter. 같은 tick 에서 클레임 실패 건은 no-op(아래 E-ISSUE-25 참조) |
| TC-AUG-075 | PASS | [실동작] job 0건 + `reg_dt` 2일 전인 aug 880015 → `orphan pending augment reclaimed (job 0건 · 깨울 주체 없음) dataAugSn=880015 result=APPLIED` → REJECTED + dead-letter |
| TC-AUG-076 | PASS | [실동작] `status` 누락 → 400 `"status: must not be blank"`. `results` 100건 상한은 `@Size(max=100)` 정적 확인(`GenAiCallbackRequest.java:88`) |
| TC-AUG-077 | PASS | [실동작] `{"brand_new_field":123,"nested":{"a":1}}` 추가 전송 → 200 정상 수신. `@JsonIgnoreProperties(ignoreUnknown=true)` 최상위(41행)+`ResultItem`(102행) 양쪽 확인 |
| TC-AUG-078 | PASS | [실동작] REJECTED 인 aug 880009 에 성공 결과 도착 → 200 `applied:false` + **WARN** `success result discarded — aug already terminal(REJECTED) dataAugSn=880009 otsdJobId=e3jobE1 (강등/반려/취소 — 필요 시 재요청)` |

## 2. E-3B. 증강 외부 위탁 (TC-AUG-100~121)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-AUG-100 | **PARTIAL** | [정적] 상한 clamp(`CONTRACT_MAX_INPUT_FILES=100`, `clampChunkSize`)·`chunkRequestId={augIdmpKey}-{jobSeq}`·`partition` 확인, 라이브에서도 `request_id=AUG-…-1` 형식 실측. **분할 자체는 라이브 미검증** — DB 최대 프레임 수가 30이라 250장 시나리오를 만들 수 없다(E-ISSUE-24). 커버: `AugmentJobSubmitServiceTest.프레임_250장이면_100_100_50_으로_3개_job_으로_분할_위탁됨` 외 3건 |
| TC-AUG-101 | PASS | [실동작] mock 202 의 `job_id=2a2fb36ef4084e168c80e22883aed5fe` 가 그대로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 적재됨(우리가 만든 값 아님). 3축 검증(`validate` 546-560: request_id echo 일치 + `status=RECEIVED` + `GenAiContract.isValidJobId`) 코드 확인 |
| TC-AUG-102 | PASS | [정적] `IDEMPOTENCY_HEADER="Idempotency-Key"`(97행) → `.header(IDEMPOTENCY_HEADER, command.requestId())`(178행). mock 로그가 헤더를 출력하지 않아 값 자체는 정적 확인 |
| TC-AUG-103 | PASS | [실동작] 위탁 입력 경로를 mock 허용 루트(`MOCK_GENAI_INPUT_BASE=/app/storage`) 밖(`/app/genai-out/outside/frame-*.jpg`)으로 만든 영상 880200 요청 → mock `400 INVALID_PARAMETER`, backend job 14 → `FAILED / SUBMIT_FAILED / "생성형AI 위탁 4xx 응답(status=400)"`. **재시도 0회**(mock 로그 POST 1건만) → `NonRetryableExternalException` 실효. **벤더 응답 본문 원문 미노출**(상태코드만) 확인, CWE-209 |
| TC-AUG-104 | PASS | [정적] `issueAllChunks`(375-394) 가 전량 선기록 후 `Optional.empty()` 시 `failIssuedJobs(… ERR_ISSUE_RECORD_FAILED)` 로 앞 청크까지 종결하고 `SubmitOutcome.of(0)` 반환(236-240). 커버: `선기록_실패시_외부로_위탁하지_않는다` / `청크_3개중_2번째_선기록_실패시_전체가_실패로_종결된다` / `선기록은_첫_위탁보다_먼저_전량_수행된다` |
| TC-AUG-105 | PASS | [실동작] 프레임 2건 중 1건의 `DE_IDNTF_SRC_FILE_PATH_NM=NULL` 인 영상 880210 요청 → **mock 인바운드 0건**(`grep -c "genai jobs"` = 0), job 15 → `FAILED / DEID_PATH_MISSING / "비식별 프레임 경로가 없는 프레임이 있어 외부 위탁을 거부합니다. missingCount=1"`, WARN `위탁 거부 — 비식별 프레임 경로 부재 … missingCount=1`. **원본 경로(`SRC_FILE_PATH_NM`) 폴백 없음** 확인 |
| TC-AUG-106 | PASS | [정적] `submit()` 최상단(207-217)에서 `DeidentReportGate.isUnderDeidentReport` → `recordRejected(… ERR_DEID_REPORT_OPEN)` + `SubmitOutcome.of(0)` → 브릿지(124-126) 즉시 실패 롤업. 라이브로는 **요청 입구(`AugmentRequestService`)가 먼저 412 로 막아** 이 2차 게이트에 도달할 수 없다(동일 게이트의 라이브 차단은 `request blocked — deident report open` 로그로 관측). 커버: `비식별_신고중인_영상은_증강_위탁이_거부된다` / `비식별_신고중_영상의_프레임_경로가_외부로_전송되지_않는다` |
| TC-AUG-107 | PASS | [정적] `Mono.defer` 안 `index>0 && isUnderDeidentReport` → `abortRemainingChunks(… ERR_DEIDENT_REPORT)` + `SubmitAbortedException`(319-322, 406-415). `concatMap` 직렬화라 재판정이 청크마다 실행됨을 코드로 확인. 커버: `위탁_도중_비식별_신고가_확인되면_남은_청크를_중단하고_실패로_종결한다` |
| TC-AUG-108 | PASS | [실동작] 위 TC-AUG-103 에서 `SUBMIT_FAILED` + 사유가 실제로 기록됨. "3번째 청크 계속" 은 1청크 환경이라 unit(`분할_위탁_중_2번째_job_실패해도_3번째_가_계속_위탁되고_실패가_기록됨`)으로 대조. ⚠ 카탈로그 근거에 실제 기록 주체(`AugmentSubmitOutcomeRecorder.onSubmitFailed` → `AugmentJobRecorder.markSubmitFailed`)가 빠져 있어 **정정함**(E-ISSUE-28) |
| TC-AUG-109 | PASS | [실동작] TC-AUG-103/105 두 경우 모두 `dispatched=0` → `AugmentRequestBridge.rollUpFailureIfNothingInFlight` → aug 67·68 REJECTED + `rtry_nmtm=1` + `dead_letter_at` 기록. "미종결 job 이 있으면 롤업 안 함" 가드(143-145)는 정적 확인 |
| TC-AUG-110 | PASS | [정적+실동작] `AugmentJobSubmitService` 클래스·`submit()` 모두 **트랜잭션 애너테이션 없음** 확인(82-134·198-203 javadoc 이 실제 상태와 일치). 라이브에서 위탁 3건이 근접 시각(01:39:45 / 01:40:01 / 01:40:15)에 겹쳤으나 `CannotCreateTransactionException` 0건 — 데드락 미재현. 회귀 가드 `AugmentRequestServiceTest.외부_위탁_HTTP_왕복중에는_트랜잭션_동기화와_EntityManager를_잡지_않는다` 존재 |
| TC-AUG-111 | PASS | [정적] `EVNT_TYPE_FALLBACK="ETC"`(144) + `resolveEventType`(497-502). mock 로그가 `evnt_type` 을 출력하지 않아 값 자체는 정적. 커버: `이벤트유형이_없는_영상은_ETC_로_대체된다` |
| TC-AUG-112 | PASS | [실동작] 이번 회차 backend 로그 전수 확인 — 위탁/거부 로그가 `originAugSn`·`rawSn`·`inputCount`·`missingCount` 수준만 남기고 **파일 절대경로 0건**. `LOG_UNSAFE` 에 ` / ` 포함(106행), `logValue` 길이 상한 50 확인(CWE-117/209/770) |
| TC-AUG-113 | PASS | [정적] `NoopExternalAugmentClient` 는 `@ConditionalOnProperty(havingValue="noop")`, `HttpExternalAugmentClient` 는 `havingValue="http", matchIfMissing=true` → 상호배타. 만료 스윕은 자기 토글(`authoring.augment.job-expiry.enabled`)만 참조(47-52 javadoc + 86행 `@Value`). 현 형상은 `AUGMENT_EXTERNAL_MODE=http` |
| TC-AUG-114 | PASS | [실동작] 존재하지 않는 산출 경로(`/app/genai-out/genai/e3/002.jpg`)로 확정된 aug 880001 → Phase B 반입 실패 → `augment frame re-extraction failed rawSn=164`, 신규 RAW 164 `DATA_STTS_CD=FAILED`, 프레임 0건(all-or-nothing). 정상 경로(158)에서는 10건 전량 반입 성공 |
| TC-AUG-115 | PASS | [실동작+정적] 실패한 164 의 목적 디렉터리에 `.part` 잔존 0건, 성공한 158 은 완결 파일만 존재. `NOFOLLOW_LINKS` + atomic move 는 코드 확인 |
| TC-AUG-116 | PASS | [실동작] `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM=/app/storage/raw/seed/101/deid/clip-9101-mask.mp4`(KPST 규약 `{stem}-mask{ext}`) → 파생 사본 `/app/storage/deidentified/videos/augment/101/158/WINTER.mp4`. **양쪽 50854 bytes 동일** = 경로를 조합·추측하지 않고 원장 값을 읽어 실제 복사함 |
| TC-AUG-117 | PASS | [실동작] `ls_data_src` raw_sn=158 → `src_file_path_nm` **전건 NULL**, `de_idntf_src_file_path_nm`=`/app/storage/deidentified/frames/deid/158/frame-N.jpg`. 두 컬럼 동일값 0건 |
| TC-AUG-118 | PASS | [실동작] `ls_data_aug_lbl_map` (data_aug_sn=52) → `orgnl_data_lbl_sn=729 / data_lbl_sn=1268 / coord_recalc_yn='N' / scale_x=NULL / scale_y=NULL`. 대조군으로 해상도 파생(aug 66)은 `'Y' / 2.0 / 2.0` |
| TC-AUG-119 | PASS | [실동작] async 실패 시 ①`augment frame re-extraction failed rawSn=164` ②RAW 164 `FAILED` ③`[Augment][ExtractC] aug marked dead-letter after async extraction failure dataAugSn=880001 status=ACCEPTED` — `AUG_PROC_STTS_CD` **미변경(ACCEPTED)** + `rtry_nmtm=1` + `dead_letter_at` 기록 확인 |
| TC-AUG-120 | **PARTIAL** | [실동작] 주 단언(ffprobe 를 확정 블록 **밖** 별도 catch 로 기동, 사본 확정 이후에만 실행)은 PASS — `AsyncVideoMetaRunner` 가 Phase C 성공 이후에만 돌아 158 의 `video.*` 가 채워짐. **⚠ 주석 단언 "`video.*` 가 부모와 동일한 것이 정상" 은 실측 반증**(E-ISSUE-21): 101=640x480/30fps/10000ms(인입 선언값) vs 158=320x240/10.0fps/5000ms(ffprobe 실측). 카탈로그 정정함 |
| TC-AUG-121 | PASS | [정적] `AsyncAugmentFrameRunner.java:92-97` — Phase C `SKIPPED` 시 cleanup·FAILED 전이 모두 skip. 중복 트리거 패자 상황을 라이브로 유도할 수 없어 정적. 커버: `AsyncAugmentFrameRunnerTest` |

---

## 3. 이슈 대장

### [E-ISSUE-21] TC-AUG-120 — 파생영상의 `video.*` 기술메타가 부모와 다르다 (두 메타 소스가 충돌하고, 복사분이 즉시 덮어써진다)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 파생영상의 비디오 파일은 부모 비식별본의 **바이트 동일 사본**이므로, `LS_DATA_META` 의 `video.*`(`RESL`/`FPS`/`duration_ms`/`codec`/`bit_rate`)와 그로부터 동결되는 `LS_DATASET_VIDEO_META`·`V_COMPLETED_VIDEO` 값이 부모와 **일치**해야 한다는 것이 `CLAUDE.md`("★ 파생영상에는 '원본영상'이 없다" 절 — *"video.\* 기술메타는 부모와 동일한 것이 정상 … `DerivedMetaCopier` 의 부모 값 복사는 결함이 아니다"*)와 TC-AUG-120 ⚠ 주석의 명시 계약이다. 관제는 이 값으로 파생 1행을 UPSERT 하므로 어느 쪽이 진실인지 확정돼 있어야 한다.
- **현재 동작(이슈 내용)**: 두 기전이 순차로 겹쳐 **최종값이 항상 ffprobe 실측값**이 된다.
  1. `DerivedMetaCopier.java:29-30` — *"메타 값 전체 복사 — `video.*` 기술메타도 **포함**. 파생영상의 비디오 파일은 원본(비식별) 복사본이라 `video.*`={원본값}이 정합적이다(구 `isTechnicalKey` skip 해제)"* → 부모 값을 복사한다.
  2. `AsyncAugmentFrameRunner.java:106-121` — Phase C 확정 이후 `AsyncVideoMetaRunner` 를 기동해 **파생 사본을 ffprobe** 하고 같은 키를 덮어쓴다.
  3. 부모 쪽 `video.*` 는 `VideoMetaService.java:37-54` 규약상 **`LS_DATA_INGEST` 인입 선언값 우선**(`bit_rate` 만 ffprobe 전용)이다.
  → 인입 선언이 실제 파일과 다르면 부모≠파생이 **구조적으로** 발생한다.
- **재현/확인 경로** (본 검증 실측):
  ```sql
  SELECT raw_sn, meta_key, meta_vl FROM ls_data_meta
   WHERE raw_sn IN (101,158) AND meta_key LIKE 'video%' ORDER BY meta_key, raw_sn;
  -- 101 video.resolution 640x480   | 158 video.resolution 320x240
  -- 101 video.fps        30        | 158 video.fps        10.0
  -- 101 video.duration_ms 10000    | 158 video.duration_ms 5000
  -- 101 video.codec      H264      | 158 video.codec      h264
  SELECT wdth, vrtc, resl, fps, vdo_len_sec FROM ls_data_ingest WHERE raw_sn=101;  -- 640|480|640x480|30|10  ← 인입 선언
  ```
  ```bash
  # 파생 158 의 비디오는 101 비식별본의 바이트 동일 사본인데도 값이 다르다
  docker exec klid-mock-server ffprobe -v error -show_entries stream=width,height,r_frame_rate -of csv=p=0 \
    /app/storage/deidentified/videos/augment/101/158/WINTER.mp4   # 320,240,10/1
  docker exec klid-mock-server ffprobe ... /app/storage/raw/seed/clip-9101.mp4      # 320,240,10/1  (부모 실제 파일도 동일)
  ```
- **영향**: 데이터 정합 + 외부 계약. ①관제가 같은 영상 트리(부모/파생)에서 **서로 다른 해상도·fps·길이**를 받는다 — 파생 산출물이 부모의 사본이라는 계약과 모순되어 관제 측 정합 검사·통계가 어긋난다. ②`CLAUDE.md` 가 관제에 명시하라고 한 *"`RESL` 은 비디오 파일 기준"* 규칙이 부모 행에서는 **성립하지 않는다**(부모는 인입 선언값). ③`DerivedMetaCopier` 의 `video.*` 복사는 즉시 덮어써지는 **죽은 작업**이며, 주석은 그 사실을 반영하지 않아 다음 수정자가 "복사가 최종값"으로 오독한다. 보안 노출 없음.
- **수정 방향(제안)**: 정책을 **한 축으로 확정**한다. ⓐ"파생 = 사본 실측"으로 간다면 `DerivedMetaCopier` 의 `video.*` 복사를 제거(죽은 작업 정리)하고 `CLAUDE.md`·TC-AUG-120 서술을 "파생 사본을 실측한 값"으로 고친다. 이때 **부모 쪽도 비식별본 기준으로 재측정할지**를 함께 정해야 부모/파생 비교가 성립한다. ⓑ"파생 = 부모와 동일"로 간다면 파생 경로에서 `AsyncVideoMetaRunner` 기동을 빼고 복사값을 최종으로 둔다(단 인입 선언이 틀린 경우 오류가 파생으로 전파된다). 어느 쪽이든 **관제 협의 대상**이다. ⚠ 구현은 하지 않았다. 카탈로그 TC-AUG-120 의 ⚠ 주석은 이번 회차에 사실 기준으로 정정했다.

### [E-ISSUE-25] [이월·미해소] 만료 스윕의 클레임과 롤업이 같은 트랜잭션이라 특정 job 이 **영구 회수 불가**(15분마다 무한 실패 반복)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 만료 스윕은 "비종결 job 은 반드시 종결된다"를 보장하는 최후 회수 장치다(`LsDataAugJobRepository.claimExpired` javadoc·`GenAiCallbackService` 주석이 "무한 대기는 없다"를 계약으로 명시). 회수 자체가 반복 실패하면 dead-letter 로 종결되거나 최소한 격리돼야 한다.
- **현재 동작(이슈 내용)**: 1차(2026-08-01) E-ISSUE-25 가 **그대로 남아 있고 이번 회차에도 실제로 반복 중**이다. `AugmentJobExpiryTxService.java:62-88` 에서 클레임 UPDATE(71-72)와 롤업(84)이 같은 `@Transactional` 이라, 롤업이 던지면 클레임까지 롤백된다.
  ```java
  @Transactional("controlTransactionManager")
  public boolean expire(Long augJobSn, Long dataAugSn, LocalDateTime cutoff) {
      if (augRepository.findByDataAugSnForUpdate(dataAugSn).isEmpty()) { ... return false; }
      if (jobRepository.claimExpired(augJobSn, cutoff, ...) == 0) return false;   // ← 이 클레임이
      ...
      AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobIdOf(jobs)); // ← 여기서 throw 하면 함께 롤백
  ```
  실측(이번 회차 backend 로그, 15분 간격으로 **7회** 반복):
  ```
  2026-08-04 00:44:19.819 ERROR AugmentJobExpirySweeper - [Augment][Expiry] expire failed augJobSn=770004 reason=CustomException
  2026-08-04 00:59:19.811 ERROR ... augJobSn=770004
  2026-08-04 01:14:19.796 ERROR ... augJobSn=770004
  2026-08-04 01:29:20.100 ERROR ... augJobSn=770004
  ```
  ```
  aug_job_sn=770004 | data_aug_sn=770003 | job_stts_cd=RECEIVED | err_cd=(null)   ← 회수 안 됨
  data_aug_sn=770003 | aug_proc_stts_cd=PENDING | dead_letter_at=(null)           ← 증강도 PENDING 고착
  ```
  근본 데이터 이상도 그대로다 — `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 UNIQUE 가 없어 같은 값이 두 job 에 존재한다:
  ```sql
  SELECT otsd_job_id, count(*) FROM ls_data_aug_job WHERE otsd_job_id IS NOT NULL
   GROUP BY otsd_job_id HAVING count(*)>1;   -- qa0802jobD | 2
  ```
  그 결과 롤업이 `requireJobIdNotOwnedByOtherAug` 에서 409 를 던지고(`AugmentResultService.java:271-285`), 스윕은 `RuntimeException` 을 잡아 ERROR 만 남기고 넘어간다(`AugmentJobExpirySweeper.java:225-228`) → 다음 tick 후보 쿼리에 다시 잡힌다.
- **재현/확인 경로**: 위 SQL 로 중복 `OTSD_JOB_ID` 확인 후 `docker logs klid-backend | grep "Expiry] expire failed"`. 신규 재현은 1차 이슈 블록의 INSERT 스크립트 그대로.
- **영향**: 기능/데이터 정합(liveness). 그 증강은 사람이 DB 를 손대기 전까지 PENDING 에서 못 나오고, 15분마다 실패 트랜잭션 1건 + ERROR 로그를 영구 생성한다. 같은 구조상 롤업이 던지는 **모든** 예외(`DataIntegrityViolationException` 포함)가 동일 결과를 낳는다. 보안 노출 없음.
- **수정 방향(제안)**: 1차 제안 그대로 유효 — ①`expire()` 에서 클레임 커밋과 롤업을 분리(클레임을 `REQUIRES_NEW` 로 먼저 커밋하면 롤업이 실패해도 job 은 종결로 남아 다음 tick 이 재선정하지 않는다) ②또는 회수 실패 횟수를 누적해 임계 초과 시 dead-letter ③부수적으로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` UNIQUE 검토(단 벤더가 같은 job_id 를 중복 반환하면 위탁이 깨지므로 트레이드오프 확인 필요). ⚠ 구현하지 않았다.

### [E-ISSUE-22] TC-AUG-057 — "부모 프레임 0건" 분기가 라이브 도달 불가능한 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 케이스는 실동작으로 검증 가능해야 한다. 불가능하면 그 사실이 케이스에 명시돼 회차마다 "확인 못 함"이 반복되지 않아야 한다(1차 E-ISSUE-21 과 동일 유형).
- **현재 동작(이슈 내용)**: `AugmentResultService.evaluateParentGate`(379-398)는 부모를 origin 프레임에서 역산한다.
  ```java
  LsDataSrc originSrc = srcRepository.findById(aug.getSrcSn()).orElse(null);      // 없으면 "origin frame not found"
  LsDataRaw parentRaw = videoRepository.findByRawSnForUpdate(originSrc.getRawSn()).orElse(null);
  ...
  if (srcRepository.findByRawSnOrderByFrameNoAsc(parentRaw.getRawSn()).isEmpty()) return ParentGate.fail("parent has no frames");
  ```
  `findByRawSnOrderByFrameNoAsc` 는 필터 없는 파생 쿼리(`LsDataSrcRepository.java:17`)이므로, `originSrc` 가 존재하는 한 그 행 자신이 최소 1건 반환된다 → 이 분기는 실행될 수 없다. 실제로 `de_ident_yn='Y'` 이면서 프레임 0건인 영상(raw_sn 64~69)이 DB 에 존재하지만, 그 영상에 매달 origin 프레임 자체가 없으므로 aug 행을 만들 수 없다.
- **재현/확인 경로**: `SELECT r.raw_sn FROM ls_data_raw r WHERE r.de_ident_yn='Y' AND NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.raw_sn=r.raw_sn);` → 프레임 0건 영상은 있으나 `LS_DATA_AUG.SRC_SN` 이 가리킬 프레임이 없다.
- **영향**: 기능/보안 영향 없음(fail-closed 다층 방어로는 정당). **카탈로그의 검증 불가 항목**이라 회차마다 판정이 흔들린다.
- **수정 방향(제안)**: 코드는 그대로 둔다. 카탈로그 기대결과에 "라이브 도달 불가 — mock 단위테스트(`AugmentResultServiceTest.부모_프레임이_없으면_실패로_확정되고_영상과_프레임러너_미트리거`)로만 검증"을 명시한다 → **이번 회차에 정정 반영함**.

### [E-ISSUE-29] TC-AUG-055 ② — "job 은 있으나 aug 행 없음 = 404" 도 도달 불가 (1차 E-ISSUE-21 카탈로그 미반영)
- **심각도**: LOW
- **기대 동작(기대효과)**: 1차(2026-08-01)에 이미 "FK CASCADE 로 도달 불가 — 방어 코드로만 존치" 로 카탈로그를 강등하라는 수정 방향이 나왔다. 카탈로그는 회차 간 대조의 입력 문서이므로 반영돼야 한다.
- **현재 동작(이슈 내용)**: 3차 카탈로그(2026-08-03 근거 전수 재확인 회차)에도 여전히 `② 404` 가 검증 대상처럼 남아 있었다. 실제 스키마:
  ```
  "fk_ldaj_data_aug" FOREIGN KEY (data_aug_sn) REFERENCES ls_data_aug(data_aug_sn) ON DELETE CASCADE
  ```
  aug 행이 지워지면 job 행도 같이 지워지므로 `GenAiCallbackService.java:103-105` 의 `orElseThrow`(404)는 실행될 수 없다.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "\d ls_data_aug_job"` → FK CASCADE 확인.
- **영향**: 기능/보안 영향 없음. 카탈로그 정합성 + 회차 간 판정 재현성.
- **수정 방향(제안)**: TC-AUG-055 기대결과에 ②의 도달 불가 사유를 명시 → **이번 회차에 정정 반영함**.

### [E-ISSUE-28] TC-AUG-108 — 근거 `file:line` 이 실제 실패 기록 주체를 가리키지 않는다 (근거 드리프트)
- **심각도**: LOW
- **기대 동작(기대효과)**: `근거(file:line)` 를 열면 기대결과의 단언(여기서는 "job `FAILED(SUBMIT_FAILED)` + 사유 기록")을 곧바로 대조할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 근거가 `AugmentJobSubmitService.java:339-354` 뿐이었는데, 그 구간은 `onErrorResume` 에서 **핸들러에 위임**만 한다.
  ```java
  outcomeRecorder.onSubmitFailed(event.originAugSn(), augJobSn, jobSeq, jobCount, err);
  ```
  실제 `SUBMIT_FAILED` 마킹은 `AugmentSubmitOutcomeRecorder.java:67-72` → `AugmentJobRecorder.java:84 markSubmitFailed`(조건부 원자 UPDATE — 지각 신호가 콜백 상태를 강등하지 못한다)에 있다. Phase C-3 논블로킹 전환 때 이관된 것이 반영되지 않았다.
- **재현/확인 경로**: `grep -n "onSubmitFailed\|markSubmitFailed" backend/src/main/java/kr/co/cudo/authoring/augment/service/AugmentSubmitOutcomeRecorder.java`
- **영향**: 기능 영향 없음. 다음 회차 검증 비용 + 오판(로직이 사라진 것으로 오인) 위험.
- **수정 방향(제안)**: 근거 컬럼에 두 파일을 추가 → **이번 회차에 정정 반영함**. (그 외 E-3/E-3B 근거 라인은 **전건 실제와 일치** — 1차 E-ISSUE-23(13건 드리프트)은 3차 카탈로그 최신화로 해소됐다.)

### [E-ISSUE-23] TC-AUG-065 — IP allowlist fail-closed(403)를 현재 환경에서 실동작 검증할 수 없다
- **심각도**: LOW (환경 제약)
- **기대 동작(기대효과)**: `webhook.genai.allowed-ip-cidrs` 미설정/`none` 이면 콜백이 전건 403 이어야 한다(VLM 과 반대로 fail-closed). 이는 무서명 웹훅의 1계층 방어라 실동작 확인이 바람직하다.
- **현재 동작(이슈 내용)**: 코드는 명확하다 — `GenAiWebhookIpAllowlist.java:44-55` 가 빈값/`none` 이면 `allowed=List.of()` 로 두고 `isAllowed()`(63-77)가 **항상 false** 를 돌려준다. 그러나 로컬 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0`(전면 허용 명시)이라 403 경로가 실행되지 않는다. 설정 변경 + 재기동은 §10-1(검증 중 코드·설정 불변) 위반이라 시도하지 않았다.
  다만 **필터가 이 경로에 실제로 배선돼 있다**는 사실은 확인했다:
  ```
  WARN k.c.c.a.c.security.HmacWebhookFilter - [Webhook] downstream auth rejected ip=172.20.0.1 path=/api/v1/genai/callback status=401
  ```
- **재현/확인 경로**: `docker exec klid-backend env | grep WEBHOOK_GENAI_ALLOWED_IP_CIDRS` → `0.0.0.0/0`.
- **영향**: 검증 커버리지 공백. 기능 영향 없음(커버: `GenAiWebhookIpAllowlistTest`).
- **수정 방향(제안)**: 다음 회차에 **allowlist 를 `none` 으로 둔 임시 프로파일**(별도 compose override)로 기동해 403 을 1회 실측하고, 그 뒤 원복한다. 코드 수정 불요.

### [E-ISSUE-24] TC-AUG-100 — 100장 청크 분할을 실동작으로 검증할 데이터가 없다 (최대 프레임 30)
- **심각도**: LOW (환경 제약)
- **기대 동작(기대효과)**: 프레임 250장 영상이 3청크(100/100/50)로 분할되고 `LS_DATA_AUG_JOB` 3행 + `LS_DATA_AUG_JOB_FILE` 250행이 생겨야 한다. 분할은 외부 계약(§4.1 input_files 상한 100)의 핵심이라 실왕복 확인 가치가 높다.
- **현재 동작(이슈 내용)**: DB 최다 프레임 영상이 30건이라(아래 쿼리) 라이브에서는 **항상 1청크**만 나간다. 실측한 위탁은 전부 `inputs=10`.
  ```sql
  SELECT r.raw_sn, count(s.src_sn) FROM ls_data_raw r JOIN ls_data_src s ON s.raw_sn=r.raw_sn
   GROUP BY r.raw_sn ORDER BY 2 DESC LIMIT 3;   -- 18|30, 4|30, 75|30
  ```
  상한 자체(`clampChunkSize` → `Math.min(configured, 100)`)와 `request_id={augIdmpKey}-{jobSeq}` 형식은 정적·라이브 모두 확인됐다.
- **재현/확인 경로**: 위 SQL.
- **영향**: 검증 커버리지 공백. 기능 영향 없음(커버: `AugmentJobSubmitServiceTest` 분할 4건).
- **수정 방향(제안)**: 다음 회차 §3-3 파이프라인 구동 시 **프레임 250장 이상 영상 1건**을 시드에 포함하거나(마킹 간격을 촘촘히), 30초 이상 영상을 사용해 분할 왕복을 1회 실측한다.

### [E-ISSUE-26] [이월·미해소] 앱(KST)과 DB DEFAULT(UTC)의 9시간 시계 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 만료 스윕은 `LocalDateTime.now() - idleTimeoutMinutes` 를 `MDFCN_DT`/`REG_DT` 와 직접 비교하므로 두 값이 같은 시계여야 임계(기본 360분)가 의미를 갖는다.
- **현재 동작(이슈 내용)**: 1차 E-ISSUE-26 그대로다. 이번 회차 실측:
  ```
  DB : show timezone → Etc/UTC ;  current_timestamp → 2026-08-03 16:41:21+00
  APP: 같은 순간 JPA 가 적재한 ls_data_aug_job.reg_dt → 2026-08-04 01:40:15   (Δ = 9h)
  ```
  본 검증에서도 이 함정을 우회하기 위해 시드 INSERT 를 전부 `now() at time zone 'Asia/Seoul'` 로 명시해야 했다(컬럼 DEFAULT 로 두면 생성 즉시 만료 대상이 된다).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "show timezone; select current_timestamp;"` + `docker exec klid-backend env | grep -i tz` / `Dockerfile:42` `-Duser.timezone=Asia/Seoul`.
- **영향**: 데이터 정합(시각 축). 앱 경로는 항상 `LocalDateTime.now()` 를 명시 대입하므로 현재 운영 흐름에서 관측된 오작동은 없다. 노출은 DB DEFAULT 에 의존하는 경로(Flyway 백필·운영 SQL·수동 INSERT)로 한정된다.
- **수정 방향(제안)**: ①DB 세션/컨테이너 TZ 를 `Asia/Seoul` 로 통일 ②또는 컬럼 DEFAULT 제거로 시각 기록 주체를 앱 하나로 못박음(권장) ③장기적으로 `timestamptz` 검토. ⚠ 변경하지 않았다.

### [E-ISSUE-27] [이월·미해소] `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 데이터 잔존 — 파생 생성 게이트가 대소문자 민감
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 산출물 보유 판정(`LsDataRaw.hasDeidentArtifact()`)이 증강·해상도 파생 생성의 단일 진실원이므로 컬럼 값 도메인이 `Y`/`F`/`N` 으로 닫혀 있어야 한다.
- **현재 동작(이슈 내용)**: 1차 E-ISSUE-24 그대로다.
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE de_ident_yn NOT IN ('Y','F','N');  -- 68 | y
  ```
  판정기는 `"Y".equals(...) || "F".equals(...)` 라 `'y'` 행은 "비식별 산출물 없음"으로 판정돼 증강 콜백이 오면 `ParentGate.FAIL`(REJECTED + dead-letter)로 끝난다. 방향은 fail-closed(안전)지만 운영자에게는 "비식별 완료된 영상인데 증강이 계속 실패"로 보인다. 컬럼에 DB 레벨 CHECK 제약이 없어 같은 오염이 재발할 수 있다.
- **재현/확인 경로**: 위 SQL.
- **영향**: 데이터 정합. 보안 노출 없음(안전한 방향으로 실패).
- **수정 방향(제안)**: ①오염 행 정정 ②`CHECK (DE_IDNTF_YN IN ('Y','F','N'))` 제약 추가 검토(Flyway). ⚠ 본 검증에서 수정하지 않았다.

---

## 4. 1차(2026-08-01) E-ISSUE 해소 대조 (본 담당 범위분)

| 1차 이슈 | 내용 | 3차 상태 |
|---|---|---|
| E-ISSUE-21 | TC-AUG-055 ② 404 dead branch | **미해소(카탈로그)** — 코드는 그대로 두는 것이 맞고, 카탈로그 반영이 누락돼 있었다. 이번 회차 정정 → 신규 E-ISSUE-29 로 기록 |
| E-ISSUE-22 | TC-AUG-072 기대 문구 불일치 | **✅ 해소** — 카탈로그가 `"SUCCEEDED 결과에는 results 가 필요합니다."` 로 정정돼 실제 응답과 일치(실동작 재확인) |
| E-ISSUE-23 | E-3/E-3B 근거 `file:line` 드리프트 13건 | **✅ 대부분 해소** — 3차 카탈로그 최신화로 E-3/E-3B 근거가 전건 실제와 일치. 잔여 1건(TC-AUG-108 기록 주체 누락)만 이번에 정정 → E-ISSUE-28 |
| E-ISSUE-24 | `DE_IDNTF_YN='y'` 오염 | **미해소** — 이월(E-ISSUE-27) |
| E-ISSUE-25 | 만료 스윕 클레임+롤업 동일 트랜잭션 → 영구 회수 불가 | **미해소 · 실측 재현** — 15분마다 `augJobSn=770004` 실패 반복 중. 이월(E-ISSUE-25 번호 유지) |
| E-ISSUE-26 | 앱(KST)↔DB(UTC) 9시간 드리프트 | **미해소** — 이월(E-ISSUE-26 번호 유지) |
| E-ISSUE-05 | 요청 응답 `jobId` ↔ 결과조회 `{jobId}` 값 공간 상이 | (E-1/E-2 범위 — part1 담당) 참고: 본 검증의 요청 응답도 `jobId=1785768859946` placeholder 였고 실제 결과는 `rawSn` 기준이었다 |

## 5. 카탈로그 정정 내역 (담당 라인범위 내 Edit, 프로덕션 코드 미수정)

| 케이스 | 정정 내용 |
|---|---|
| TC-AUG-055 | ②(404)에 "FK CASCADE 로 라이브 도달 불가 — 방어 코드로만 존치, 검증 대상은 ①만" 명시 + 근거에 FK 추가 |
| TC-AUG-057 | "라이브 도달 불가 — mock 단위테스트로만 검증 가능" 사유(부모를 origin 프레임에서 역산) + 커버 테스트명 + `LsDataSrcRepository.java:17` 근거 추가 |
| TC-AUG-108 | 실패 기록 주체 정정 — `AugmentSubmitOutcomeRecorder.java:67-72` · `AugmentJobRecorder.java:84` 추가, 사유 문구가 상태코드만 남긴다는 사실 추가 |
| TC-AUG-120 | ⚠ 주석 "`video.*` 가 부모와 동일한 것이 정상" → **실측 기반 정정**(부모=인입 선언값 우선 / 파생=ffprobe 실측, 실측 수치 병기, 근거 2개 추가) |

> `docs/test-cases/E-augment-resolution-export-meta.md` 이외의 파일은 수정하지 않았다(프로덕션·테스트·설정 전부).

## 6. 다른 파트에 넘길 관찰 (본 담당 범위 밖 — 이슈 미발행)

1. **rawSn=101 의 `video.*` 가 실제 파일과 다르다** — `LS_DATA_INGEST` 인입 선언(640x480/30fps/10s)이 실제 파일(320x240/10fps/5s)과 어긋난 채 그대로 메타가 됐다. `VideoMetaService` 의 "인입 값 우선" 정책상 **의도된 동작**이지만, 데이터마트 뷰로 나가는 값이 실측과 다르다는 점은 B/E-5(메타) 담당이 판단할 사안이다. `bit_rate` 만 ffprobe 전용이라 이 값(30624)만 실측이다.
2. **ACK 미수신(`OTSD_JOB_ID` null) job 은 콜백의 `job_id` 오배송 검사(TC-AUG-067)를 통과한다** — `GenAiCallbackService.java:116` 이 `target.getExternalJobId() != null` 일 때만 대조한다. Phase C-3 논블로킹 제출에서 콜백이 ACK 기록보다 먼저 도착할 수 있어 **의도된 설계**로 보이며(지각 ACK 는 `markSubmitFailed`/`markAccepted` 의 조건부 UPDATE 가 강등을 막는다), `request_id` 발급 게이트가 이미 대상을 인증하므로 결함으로 보지 않았다. 다만 계약 문서에 명시돼 있지 않다.
3. **다중 에이전트 간섭 관측** — 검증 중 `data_aug_sn` 47·48·49·50·51 과 `raw_sn` 153~157 이 다른 에이전트에 의해 생성/검수/폐기되는 것을 관측했다. 본 파트는 이를 피해 **880001~880017 / raw 880200·880210 / src 880200·880201·880210·880211** 대역만 사용했다(정상 왕복 1건만 자연 채번 — aug 52 / raw 158).

## 7. 본 검증이 남긴 테스트 데이터 (수정 금지 규칙에 따라 삭제하지 않고 기록만)

| 테이블 | 키 | 비고 |
|---|---|---|
| `ls_data_aug` | 880001·880003·880005·880007·880009·880011·880013·880015·880017 | 웹훅 시나리오용 (ACCEPTED/REJECTED/PENDING 혼재) |
| `ls_data_aug` | 52(정상 왕복 · `NEW_RAW_SN=158`), 67·68(위탁 실패 시나리오) | 52 는 정상 시나리오 산출물 — 유지 권장 |
| `ls_data_aug_job` | 880001~880013, 13·14·15 | |
| `ls_data_aug_job_file` | 위 job 들의 하위 행 | |
| `ls_data_raw` | **158**(정상 파생 · 유지 권장), 164·169(async 반입 실패로 `FAILED`), 880200·880210(위탁 실패 재현용 합성 영상) | 164·169 는 산출물 파일 없음 |
| `ls_raw_data_status` | 880200·880210 (`APPROVED`) | 합성 영상용 |
| `ls_data_src` | 567~576(raw 158), 880200·880201, 880210·880211 | |
| 파일시스템 | `/app/storage/deidentified/videos/augment/101/158/WINTER.mp4`, `/app/storage/deidentified/frames/deid/158/*.jpg` | 정상 산출물 |

---

# E클러스터 part3 — E-4. 해상도 파생 오케스트레이션 (TC-RESL-001~021)

- 담당: `docs/test-cases/E-augment-resolution-export-meta.md` E-4절(21건, VideoResolutionService/VideoController)
- 스택: 재확인(`docker ps` 5개 healthy, 2026-08-03 재빌드 이미지) — `_raw/stack-bringup.md` 참조
- 데이터: `_raw/pipeline-drive.md` 의 rawSn=101(APPROVED, 비식별 완료 640x480) 재사용 + 기존 DB 잔존 영상(rawSn=8/9/15/115/145/900/901) 활용. 신규 파생 생성은 실제 API 호출(REVIEWER 토큰, userNo=1001)로 수행 — rawSn 154/155/156(101의 3종)·159/160(900의 1080p/480p)·161/162(115의 720p/480p) 생성됨(부작용, 원복 불필요 — 검증 목적 정상 데이터).
- 코드/설정/프로덕션 파일 수정 없음. 카탈로그(`E-augment-resolution-export-meta.md`) 는 담당 범위(E-4절) 안에서 TC-RESL-013 한 줄만 Edit로 보강(아래 "카탈로그 정정" 참조).
- DB 테스트용 임시 조작은 전부 시행 직후 원복함(rawSn=101 frm_no=0 경로 2회 임시변경 후 원복, rawSn=9 procLog 테스트행 삽입 후 삭제).

## 판정 요약

| 판정 | 건수 | ID |
|---|---|---|
| PASS | 19 | TC-RESL-001,002,003,004,005,006,007,008,009,010,011,014,015,016,017,018,019,020,021 |
| PARTIAL | 1 | TC-RESL-013 |
| FAIL | 1 | TC-RESL-012 |
| 합계 | 21 | |

## 결과 표

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-RESL-001 | PASS | [실동작] `POST /v1/videos/101/resolution` body `{}` → 201, `derivatives` 3건 CREATED(rawSn 154/155/156, 1080p/720p/480p). 근거 `VideoResolutionService.java:92-141` 라인 정확 | |
| TC-RESL-002 | PASS | [실동작] rawSn=115 `{"presets":["RESL_720P","RESL_480P"]}` → 201 2건만(rawSn 161/162). `ResolutionChangeRequest.java:32-37` 정확 | |
| TC-RESL-003 | PASS | [실동작] 같은 요청에 `"RESL_720P"` 중복 포함 `{"RESL_720P","RESL_720P","RESL_480P"}` → distinct 2건만 생성(중복 무시). `resolvePresets` 의 `.distinct()` 확인. 근거 `:33-37` 정확 | |
| TC-RESL-004 | PASS | [정적+부분실동작] `VideoResolutionService.java:116-123` 루프의 `preset.width()==srcW && preset.height()==srcH` 스킵 로직 확인. rawSn=101(640x480)은 3종 어느 것과도 안 맞아 스킵 없이 3건 전부 생성됨을 실동작으로 확인(대칭 검증) — 정확히 일치하는 실 프레임(1920x1080/1280x720/854x480) 픽셀 fixture 는 구성 비용상 미실측, 코드 대조로 보완 | 라인 드리프트 없음 |
| TC-RESL-005 | PASS | [정적+부분실동작] `:126-129` `results.isEmpty()` → 400 확인. 3종 모두 생성된 rawSn=101 재요청(`{}`)은 사문화되지 않고 전건 시도 후 3건 FAILED(중복)로 `results` 는 비지 않아 이 케이스가 아니라 TC-RESL-006 경로로 빠짐(정상 — "전부 스킵"과 "전부 실패"는 다른 분기이며 카탈로그도 별도 케이스로 분리) | |
| TC-RESL-006 | PASS | [실동작] rawSn=101 재요청 `{"presets":[0]}`(=1080p, 이미 존재) → `HTTP 500 {"errorCode":"INTERNAL_ERROR","message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."}`. `:133-139` 정확 | |
| TC-RESL-007 | PASS | [실동작] rawSn=900(`DE_IDNTF_YN='F'`, 산출물 실재) `{}` 요청 → 201, `[{rawSn:159,RESL_1080P,CREATED},{rawSn:null,RESL_720P,FAILED},{rawSn:160,RESL_480P,CREATED}]` — CREATED/FAILED 혼재 201 확인(720p 는 기존 파생 중복으로 실패). `:205-215` 정확 | |
| TC-RESL-008 | PASS | [실동작] rawSn=101 원본 640x480 → 1080p(1920x1080)·720p(1280x720)·480p(854x480) 전부 원본보다 큼(업스케일)에도 거부 없이 3건 생성됨(201). 구 `targetH>=srcH` 가드 실제로 없음 확인. `:106-123` 정확 | |
| TC-RESL-009 | PASS | [실동작] 생성된 파생 rawSn=154(`ORGNL_RAW_SN=101`)에 재요청 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"원본 영상에만 해상도 변경 가능"}`. `:230-232` 정확 | |
| TC-RESL-010 | PASS | [실동작] rawSn=145(검수 FAILED) → `HTTP 409 {"errorCode":"CONFLICT","message":"검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다."}`. `:234-240` 정확 | |
| TC-RESL-011 | PASS | [실동작] rawSn=999999 → `HTTP 404`. `:225-227` 정확 | |
| TC-RESL-012 | **FAIL** | [실동작] rawSn=101 frm_no=0 의 `DE_IDNTF_SRC_FILE_PATH_NM` 을 임시로 mp4 경로(비이미지)로 변경 후 요청 → `HTTP 500 {"errorCode":"INTERNAL_ERROR","message":"프레임 이미지를 읽을 수 없습니다."}` (기대: 400 `"원본 프레임 해상도를 확인할 수 없습니다."`). 테스트 후 원본 경로로 즉시 원복. `Java2DImageResizer.readImage`(80-98행 부근) 가 `dim<=0` 반환 경로 자체가 없어 `VideoResolutionService.java:110-112` 의 400 분기가 여전히 사문화 코드임을 재확인 | **1차(2026-08-01) E-ISSUE-41 미해소 — 아래 이슈 참조** |
| TC-RESL-013 | PARTIAL | [실동작] 문자열 축(`["RESL_240P"]`,`["resl_720p"]`,`[""]`,`[true]`,`[{}]`) → 전부 `HTTP 400`(기대 일치). 그러나 **숫자 ordinal 축**(`[0]`,`[1]`) → Jackson 이 `RESL_1080P`/`RESL_720P` 로 정상 바인딩해 서비스 로직까지 진입(rawSn=101 재요청 시 중복이라 500으로 관측되지만 이는 "도달했다"는 증거). `[99]`(범위초과)만 400 | **1차 E-ISSUE-42 미해소 — 카탈로그 TC-RESL-013 행에 각주 보강(Edit 완료), 아래 이슈 참조** |
| TC-RESL-014 | PASS | [실동작] WORKER 토큰 → `HTTP 403 FORBIDDEN`("권한이 없습니다."), 토큰 없음 → `HTTP 401 UNAUTHORIZED`("인증이 필요합니다."). `VideoController.java:365-367` 정확 | |
| TC-RESL-015 | PASS | [실동작] 전 응답에서 `{derivatives:[{rawSn,goalResCd,targetW,targetH,status}]}` 형태만 확인, 내부 파일경로·EXPORT_SN 등 미노출 | |
| TC-RESL-016 | PASS | [실동작] rawSn=101 frm_no=0 경로를 `/etc/passwd` 로 임시 변경 후 요청 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"원본 프레임 경로가 허용된 저장 경로를 벗어납니다."}`(경로 원문 미노출). 즉시 원복. `:262-274` 정확 | |
| TC-RESL-017 | PASS | [실동작] 위 TC-RESL-001/007 등 전 요청이 `de_idntf_src_file_path_nm`(비식별, `/app/storage/deidentified/frames/deid/...` 절대경로)을 그대로 사용해 통과함 — deid base 허용 확인. `:266-270` 정확 | |
| TC-RESL-018 | PASS | [실동작] rawSn=8/9/15(`DE_IDNTF_YN='F'`, `ls_deident_proc_log` 에 SUCCESS 행 없음 — 비식별 API 실패형) 3건 모두 `HTTP 409 {"errorCode":"CONFLICT","message":"원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다."}`. `ParentDeidArtifactGuard.java:65-94`, 호출부 `VideoResolutionService.java:100` 정확 | |
| TC-RESL-019 | PASS | [실동작] rawSn=900(`DE_IDNTF_YN='F'`, procLog SUCCESS 행 존재 + 실제 파일 존재) → 201 정상 생성(TC-RESL-007 항목과 동일 응답). `'F'` 만으로 차단되지 않고 산출물 실재 여부로만 판정됨을 확인. `ParentDeidArtifactGuard.java:70-93` 정확 | |
| TC-RESL-020 | PASS | [실동작] rawSn=9 에 `ls_deident_proc_log` SUCCESS 행을 임시 삽입(`de_idntf_file_path_nm='/etc/passwd'`) 후 요청 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"경로가 허용된 비식별 저장 경로를 벗어납니다."}`(경로 원문 미노출). 테스트 후 삽입 행 즉시 삭제. `ParentDeidArtifactGuard.java:100-124` 정확 | |
| TC-RESL-021 | PASS | [실동작] 15초 대기 후 `GET /v1/videos/101/resolution` → `HTTP 200 {"derivatives":[{rawSn:154,...,COMPLETED},{rawSn:155,...,COMPLETED},{rawSn:156,...,COMPLETED}]}` — 비동기 확정 완료 반영 확인. `:153-200`, `VideoController.java:392-397` 정확 | |

## 근거 드리프트

**없음** — E-4절 21건 전부 `file:line` 을 현재 소스와 대조한 결과 정확히 일치했다(2026-08-03 카탈로그 갱신 changelog 3회차의 "E-4/E-5 는 리팩터 후에도 근거가 정확했다" 서술과 부합).

## 카탈로그 정정 (Edit 1건, 담당 범위 내)

- `TC-RESL-013` 행에 각주 보강: 문자열 축은 400 정상이나 숫자 ordinal(`[0]`/`[1]`/`[2]`) 은 Jackson 기본 동작으로 통과해 서비스 로직까지 진입하는 미해소 갭(1차 E-ISSUE-42)을 명시. 라인 드리프트는 없었고(기존 근거 `ResolutionPreset.java` 그대로 정확), 내용 보강만 수행.

## 이전 회차(2026-08-01 1차) 이슈 대조

| 1차 이슈 | 케이스 | 상태 |
|---|---|---|
| E-ISSUE-41 | TC-RESL-012 | **미해소 — 이월** (재현 동일, 코드 변경 없음 확인) |
| E-ISSUE-42 | TC-RESL-013 | **미해소 — 이월** (재현 동일, 코드 변경 없음 확인) |

E-4절 범위(TC-RESL-001~021) 내 1차 이슈는 이 2건뿐이며 둘 다 재현됨. (TC-RESL-030 이후 E-5절의 E-ISSUE-61~65 는 본 담당 범위 밖)

---

### [E-ISSUE-41] TC-RESL-012 — 프레임 해상도 실측 실패가 400 이 아니라 500 으로 나가고, 400 가드는 도달 불가 사문화 (1차 E-ISSUE-41 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 원본 프레임 이미지가 손상/미지원 포맷/부재라 해상도를 확인할 수 없으면 **400 INVALID_INPUT `"원본 프레임 해상도를 확인할 수 없습니다."`** 로 응답해야 한다. 서버 장애가 아니라 요청 대상 데이터 상태의 전제 불충족이므로 4xx 여야 FE 가 "이 영상은 프레임이 깨져 해상도 변경 불가"로 안내할 수 있고 5xx 알람(운영 오탐)을 만들지 않는다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.java:110-112`
  ```java
  int[] dim = measureFirstFrame(rawSn);
  int srcW = dim[0]; int srcH = dim[1];
  if (srcW <= 0 || srcH <= 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
  }
  ```
  실측기 `Java2DImageResizer.readImage`(80-98행 부근)는 `ImageIO.read` 가 IOException 이거나 null 이면 **그 자리에서** `INTERNAL_ERROR("프레임 이미지를 읽을 수 없습니다.")` 를 던진다 — `dim<=0` 을 반환하는 경로가 없다. 따라서 위 400 분기는 어떤 입력으로도 도달 불가능한 사문화 코드다.
  2026-08-03 3차 재실증(rawSn=101, frm_no=0 의 `de_idntf_src_file_path_nm` 을 mp4 경로로 임시 변경):
  ```
  HTTP/1.1 500
  {"success":false,"data":null,"message":"프레임 이미지를 읽을 수 없습니다.","errorCode":"INTERNAL_ERROR"}
  ```
  테스트 직후 원본 경로(`/app/storage/deidentified/frames/deid/101/frame-0.jpg`)로 원복함.
- **재현/확인 경로**:
  ```sql
  UPDATE ls_data_src SET de_idntf_src_file_path_nm='/app/storage/deidentified/videos/101/clip-9101-mask.mp4'
   WHERE src_sn=468; -- rawSn=101 frm_no=0 (허용 base 안, 확장자만 비이미지)
  ```
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/101/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{}'
  # → HTTP 500 "프레임 이미지를 읽을 수 없습니다."
  ```
- **영향**: 기능/운영. 데이터 상태 문제가 서버 오류로 분류되어 5xx 알람·SLO 오염, FE 표준 4xx/5xx 분기에서 "일시 장애"로 오안내, 회귀 테스트 커버 갭(400 분기가 원천적으로 테스트 불가). 보안 등급 아님(경로·스택 미노출 유지 확인됨).
- **수정 방향(제안)**: `Java2DImageResizer.readImage` 의 실패를 호출부에서 구분 가능하게 한다 — 포트 계약을 `Optional<int[]>` 로 넓히거나 실측 실패를 `INVALID_INPUT` 전용 예외로 승격해 `measureFirstFrame` 에서 400 메시지로 재던진다. `srcW<=0` 사문화 분기는 제거하거나 실제 도달 가능하게 배선하고 "손상 프레임 → 400" 테스트를 추가한다. (⚠ 구현은 하지 않는다)

---

### [E-ISSUE-42] TC-RESL-013 — `presets` enum 이 숫자(ordinal)로도 바인딩돼 문자열 화이트리스트 계약을 우회한다 (1차 E-ISSUE-42 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `presets` 원소는 `RESL_1080P`/`RESL_720P`/`RESL_480P` 세 문자열만 허용되고 그 외 값·형식은 Jackson 역직렬화 단계에서 400 으로 거부돼야 한다(`ResolutionChangeRequest.java` 의 명시 계약, "자유 입력 해상도 차단 CWE-20").
- **현재 동작(이슈 내용)**: Jackson 기본 동작상 JSON 정수는 enum ordinal 로 해석된다. 프로젝트에 `fail-on-numbers-for-enums` 류 하드닝 설정이 없고(`application*.yml` 에 `spring.jackson` 블록 없음) `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리도 없다.
  2026-08-03 3차 재실증(rawSn=101, 이미 3종 파생 존재하는 상태에서):
  ```
  {"presets":[0]}   -> HTTP 500 "요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."  ← 역직렬화 통과 + createOne 시도까지 도달(중복이라 실패로 관측되나 "도달했다"는 것 자체가 증거)
  {"presets":[99]}  -> HTTP 400 "요청 본문이 올바르지 않습니다." (ordinal 범위초과만 거부)
  {"presets":["RESL_240P"]} -> HTTP 400 (문자열 축은 정상)
  ```
- **재현/확인 경로**:
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/{approvedRawSn}/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{"presets":[2]}'
  # → 파생이 없는 원본이면 RESL_480P 파생이 실제로 생성됨(문서화되지 않은 표현)
  ```
- **영향**: 입력검증/계약(CWE-20). 값 공간이 넓어지지는 않으므로(0~2=동일 3개 프리셋) 권한상승·자유해상도 주입은 아니다. 실질 위험: ①계약 밖 표현이 허용돼 OpenAPI·연동규격과 실제 수용 입력이 어긋남 ②순서 의존 취약 — 향후 `ResolutionPreset` 에 상수를 앞/중간에 추가하면 기존 숫자 페이로드가 조용히 다른 프리셋으로 재매핑(무증상 데이터 오류) ③"enum 화이트리스트 강제" 보안 주장이 부분적으로만 성립.
- **수정 방향(제안)**: 전역 `spring.jackson.deserialization.fail-on-numbers-for-enums: true`(영향범위 넓어 전 DTO 회귀 확인 필요) 또는 국소적으로 `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리(미지값 → `IllegalArgumentException`→400)를 둔다. `{"presets":[0]}` → 400 케이스를 테스트에 추가한다. (⚠ 구현은 하지 않는다)

---

# E 클러스터 part4 — E-5 해상도 파생 예약/확정 + E-5B(폐기절) 검증 결과

- **회차**: 2026-08-03 3차
- **담당 범위**: `docs/test-cases/E-augment-resolution-export-meta.md` §E-5(TC-RESL-030~071, 42건) + §E-5B(TC-RESL-080~095, 16건 — 폐기 표기 확인만)
- **검증 방식**: 풀스택 실동작(backend `localhost:18081`, context-path `/api`, PostgreSQL `public` 스키마) + 정적 대조 + 기존 자동테스트 대조
- **환경**: `_raw/stack-bringup.md` 기준 스택(HEAD `e065da42`, 이미지 재빌드 완료). backend 실효 배선 mock-server 정상. `test-baseline.md` backend 실패 0건.
- **HEAD 확인**: 워크트리·형제 워크트리 모두 `e065da42` — 컨테이너 이미지와 검증 소스 일치.
- **빌드/테스트 실행 없음**(지시 준수). 프로덕션 코드 미수정. 카탈로그(E-5/E-5B 담당 범위)만 Edit 정정.

## 판정 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|------|:--:|:----:|:----:|:-------:|:-------:|:---:|:--------:|
| E-5 (분모 = 42 − 폐기 1) | **41** | **37** | **1** | **3** | 0 | 0 | 0 |
| E-5B (폐기, 분모 제외) | 16 | — | — | — | — | — | — |

PASS율 **90.2%** (37/41). 폐기행 TC-RESL-047 은 분모 제외.

---

## 1. 실동작 구동 요약 (이번 회차 신규 생성분)

REVIEWER 토큰(`POST /v1/dev/tokens`, userNo=1001)으로 `POST /v1/videos/{rawSn}/resolution` 을 직접 호출해
예약(PENDING) → Phase A(snapshot) → Phase B(materialize) → Phase C(persist) 전 구간을 실제로 돌렸다.

| 부모 rawSn | 조건(픽스처) | 프리셋 | 결과 | 검증한 케이스 |
|:--:|---|:--:|---|---|
| 906 | `'Y'`, 프레임 2건(deid 경로가 **서로 다른 디렉터리·동일 basename**) | 720P→163, 1080P→165 | 확정(COMPLETED/'Y'/ACCEPTED) **그러나 산출 파일 1건뿐** | 030·041·044·052·069·070·071 |
| 903 | `'Y'`, 프레임 2건 **vdo_frm_no 중복(7,7)** | 720P→166 | Phase A fail-fast → cleanup → 예약 해제 → RAW 삭제 | 039·053·061·066 |
| 902 | `'Y'`, 프레임 2건 중 1건 **deid 경로 null** | 720P→167 | Phase A CONFLICT → 동일 정리 경로 | 040·061·066 |
| 905 | `'Y'`, 비식별본 mtime **2030-01-01**(스냅샷 이후 교체 모사) | 720P→168 | **Phase C mtime stale 게이트 abort** | 049·061·066 |
| 905 | 요청~Phase C 사이에 **새 SUCCEEDED procLog(다른 경로) 삽입**(40ms 지연 레이스) | 1080P→170 | **Phase C 경로변경 stale 게이트 abort** | 048·061·066 |
| 905 | 정상 | 480P→171 | 확정 | 041·044·052 |
| 905 | **`DE_IDNTF_YN='F'`(신고구간)로 일시 전환** | 1080P→172 | **정상 확정** — 예약·PhaseA·PhaseC 3게이트 모두 통과 | ★031·036·050 |
| 905 | **동일 (부모, 프리셋) 3요청 동시 발사** | 720P×3→173 | 1건만 201, 2건 거부. `RESL_720P` aug 행 **정확히 1건**, 고아 RAW 0건 | ★033 |
| 115 | 라벨 5건·메타 7건·VLM 검수행 APPROVED 보유 (타 에이전트 구동분 관측) | 720P→161, 480P→162 | 레터박스 좌표·메타/검수행 승계 검증 | 045·046·070·071 |

> 검증 종료 후 905 의 `de_ident_yn` 은 `'Y'` 로 원복했다. 그 외 DB/파일 상태 변경은 API 를 통한 정상 생성분뿐이다.

### ★ 반증(적극적 실패 유도)에서 실제로 드러난 것
1. **Phase C stale 창 게이트 2조건 모두 실제로 발화**(경로변경·mtime) — 코드만 보면 "게이트가 있다"로 끝날 구간을 레이스로 강제해 abort·cleanup·예약해제·고아 RAW 삭제까지 전 흐름을 관측했다.
2. **`'F'`(신고) 통과 정책이 3게이트 전부에서 실동작으로 성립** — 2026-07-29 확정 정책과 코드가 일치.
3. **동시 예약 경합이 UK 로 정확히 1건만 통과** — 다만 패자의 **API 응답이 409 가 아니라 500**(E-ISSUE-66).
4. **파생 프레임 목적 파일명 충돌(E-ISSUE-61) 재현** — "코드가 그럴듯해 보이는" 구간에서 실제 파일 개수를 세어 확인. 부모 2프레임 → DB 2행이 같은 1개 파일을 가리키고 디스크엔 1개만 존재.
5. **거짓 FAIL 1건 회피** — 처음 파생 163 의 프레임 개인정보 3필드가 전부 NULL 로 보여 FAIL 로 판단할 뻔했으나, 동시 구동 중이던 타 에이전트가 부모 906 의 프레임 개인정보 필드를 그 사이에 갱신한 것이었다. 재현(165)에서 `N/Y/Y` 정상 복사 확인 → PASS. **공용 DB 동시 검증 환경에서는 부모 상태를 파생 생성 *직전*에 스냅샷해야 한다.**

---

## 2. E-5 케이스별 판정 (TC-RESL-030 ~ 071)

| ID | 판정 | 근거 확인 |
|----|:----:|-----------|
| TC-RESL-030 | PASS | [실동작] `ResolutionDerivativeService` 로그 `derivative reserved parentRawSn=906 newRawSn=163 dataAugSn=59` → AFTER_COMMIT 러너 즉시 기동. `LS_DATA_AUG(RESL_720P)` + 새 RAW(`ORGNL_RAW_SN=906`) 생성 확인. [정적] `ResolutionReservationPersister.java:68-130` 일치(근거 드리프트 0). 예약 직후 PENDING 중간상태는 확정이 4ms 내라 직접 관측 불가 — IT `해상도_파생_예약직후_finalize전에는_aug가_PENDING이라_집계가_COMPLETED가_아니다` 가 커버 |
| TC-RESL-031 | PASS | [실동작] ★`'F'` 통과: 905 를 `'F'` 로 두고 요청 → 201 + 파생 172 확정. [정적] `'N'`/null 차단은 `ResolutionReservationPersister.java:83-88`(`hasDeidentArtifact()`), 단위테스트 `부모_비식별산출물이_없으면(N)_예약게이트에서_파생생성이_거부된다`·`부모가_비식별신고구간(F)이어도_해상도_파생영상이_정상_예약생성된다`. ⚠ API 경로에서는 `ParentDeidArtifactGuard`(동기 409)가 선행하므로 이 게이트는 **2차 방어층** |
| TC-RESL-032 | PASS | [정적] `:92-94` INVALID_INPUT(400). API 경로에서는 `ResolutionDerivativeService.firstFrame()`(`:124-130`)이 먼저 400 을 던져 도달 불가한 방어층. 단위테스트 `srcSn_null이면_LS_DATA_AUG_INSERT전에_INVALID_INPUT을_던진다` |
| TC-RESL-033 | PASS | [실동작] 동일 (부모,프리셋) 3요청 동시 발사 → 1건 201 / 2건 거부, `RESL_720P` aug 행 정확히 1건·고아 RAW 0건. 순차 재요청도 동일. 스택트레이스가 `ResolutionReservationPersister.java:107`(`DataIntegrityViolationException`→CONFLICT)를 정확히 가리킴. DB 인덱스 실측 `uk_ls_data_aug_resl ON (src_sn, aug_type_cd) WHERE aug_type_cd LIKE 'RESL\_%'` — **상태 무관** 확인. ⚠ API 표면 응답은 500 → E-ISSUE-66 |
| TC-RESL-034 | PASS | [정적] `:164-170` `resolveSafeDir` = base 하위 + `StorageSubtreePolicy.isDeidentifiedArtifact` 이중 강제 → INVALID_INPUT(400). 입력이 전부 내부 상수(`SEG_VIDEOS`/`SEG_RESOLUTION`/rawSn)라 traversal 이 외부에서 주입될 표면은 없음(방어층). IT `경로에_상위탈출_시도시_거부되고_파생_확정_실패시_LS_DATA_RAW_고아행이_남지_않음` |
| TC-RESL-035 | PASS | [정적] `ResolutionSnapshotService.java:102-106` `Optional.empty`. 단위테스트 `이미확정된_파생RAW면_멱등skip하고_부모재잠금을_하지않는다` + IT `확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다`(baseline 통과) |
| TC-RESL-036 | PASS | [실동작] ★`'F'` 통과(905→172, Phase A `snapshot ready` 로그). [정적] `:116-122` `'N'`만 CONFLICT. 단위테스트 2건(`(N)_게이트에서_CONFLICT`·`(F)이어도_스냅샷이_정상_생성된다`) |
| TC-RESL-037 | PASS | [정적] `:149-153` NOT_FOUND(404). ⚠ **카탈로그 전제의 상태값 `SUCCESS` 는 오기 — 실제 적재값은 `SUCCEEDED`**(DB 실측 `proc_stts_cd` 분포: SUCCEEDED 70 / FAILED 8). 이번 회차에 카탈로그 정정 완료 |
| TC-RESL-038 | PASS | [정적] `:161-164` INTERNAL_ERROR. API 경로에서는 동기 `firstFrame()` 400 이 선행(방어층). 단위테스트 `부모_프레임이_0건이면_fail_fast로_거부한다(#9)` |
| TC-RESL-039 | PASS | [실동작] 부모 903(vdo_frm_no 7,7 중복) → 요청 201 후 러너 `finalize failed rawSn=166 cause=CustomException` → `reserved aug slot released dataAugSn=62` → `failed derivative RAW removed rawSn=166`. DB 에 166 부재 확인. [정적] `:194-199` |
| TC-RESL-040 | PASS | [실동작] 부모 902(프레임 2건 중 1건 `de_idntf_src_file_path_nm` null) → 167 CONFLICT → 동일 정리 경로 완주, RAW 부재 확인. 원본 폴백 없음(로그·DB 어디에도 원본 경로 미기록). [정적] `:240-247` |
| TC-RESL-041 | **FAIL** | [실동작] **목적 파일이 부모 프레임과 1:1 이 아니다.** 부모 906 의 2 프레임 deid 경로가 `frames/deid/26/frame-0.jpg` · `frames/deid/27/frame-0.jpg`(디렉터리는 다르고 basename 동일) → 파생 163·165·98 모두 `LS_DATA_SRC` 2행이 **같은 파일 1개**(`frames/deid/{newRawSn}/frame-0.jpg`)를 가리키고 디스크에도 1개만 존재. `SELECT raw_sn, count(*), count(DISTINCT de_idntf_src_file_path_nm)` → `163|2|1`, `165|2|1`, `98|2|1`. 비디오 복사·리스케일 자체와 "DB 접근 0·부모 잠금 0" 은 충족. → **E-ISSUE-61** |
| TC-RESL-042 | PASS | [정적] `ResolutionFileMaterializer.java:66-68` `exists()` → NOT_FOUND, 원본 폴백 없음. 단위테스트 `비식별_비디오원본이_없으면_NOT_FOUND로_실패하고_게이트를_반환한다`. 실동작 재현 시도(요청 20ms 후 파일 삭제)는 Phase B 가 먼저 복사를 마쳐 레이스 실패 — 파일 존재 확인이 2줄이라 정적 판정으로 충분 |
| TC-RESL-043 | PASS | [정적] `:63` `resizeGate.acquire()` 가 **비디오 복사(`:69`) 앞**, `:77-79` finally release → 복사+리사이즈 전체가 1슬롯. `ResizeConcurrencyGate` = fair Semaphore(기본 2, timeout 5s, 초과 429). 실효 설정 확인(`application.yml:420-421`, 환경변수 미지정 → 기본값). 429 유발은 픽스처 프레임이 2~6장이라 불가 |
| TC-RESL-044 | PASS | [실동작] 163·165·171·172·173 전건 `LS_DATA_RAW`(COMPLETED, `de_ident_yn='Y'`) + `LS_DATA_AUG`(ACCEPTED, `new_raw_sn` 매핑) + `LS_DEIDENT_PROC_LOG`(SUCCEEDED, 파생 비디오 경로) + `LS_DATA_SRC` INSERT + `LS_DATA_AUG_LBL_MAP` 적재 동시 확인. 로그 `[C] persisted rawSn=... frames=.. labels=.. metas=.. metaReviews=..`. [정적] `:85-154` |
| TC-RESL-045 | PASS | [실동작] **수치 정확 일치.** 부모 115(320×240) → 720P: `LetterboxTransform` scale=min(4.0,3.0)=**3.0**, offsetX=(1280−960)/2=**160**, offsetY=0 (로그 `scale=3.0 offset=160,0`). BBOX `[[8,8],[80,80]]` → `[[184,24],[400,240]]` = `x*3+160`, `y*3+0` ✔. 480P: scale=2.0, offsetX=107 → `[[123,16],[267,160]]` ✔. SKELETON 17점도 동일 변환 + **visibility 플래그 보존**(`[0,0,0]`→`[160,0,0]`, `[10,10,2]`→`[190,30,2]`) ✔. 매핑행 `coord_recalc_yn='Y'`, `scale_x=scale_y`(3.000000 / 2.000000) — 축별 독립배율 폐기 확인. [정적] `ResolutionSnapshotService.java:136-143`, `ResolutionPersistService.java:384-409` |
| TC-RESL-046 | PASS | [실동작] 부모 906(라벨 0건) → 파생 163/165 `labels=0`, `LS_DATA_AUG_LBL_MAP` 0행. [정적] `:386-389` |
| ~~TC-RESL-047~~ | (폐기) | 분모 제외. `ResolutionPersistService.java:306-360` 주석 실측 — "구 조건 *capturedAt 이후 신고 이력 존재* 는 제거됐다(순수 신고 결합)" 명시 확인. **폐기 표기 정확** |
| TC-RESL-048 | PASS | [실동작] ★**레이스로 강제 재현.** 905 요청 발사 40ms 후 새 `SUCCEEDED` procLog(다른 경로) INSERT → Phase A `snapshot ready`(01:46:25.612) → Phase B(25.783) → **Phase C `parent deident path changed since snapshot — abort (PII stale guard) parentRawSn=905 newRawSn=170`**(25.784) → cleanup + 예약해제 + RAW 170 삭제. 경로 해석 불가 시 `resolveQuietly` null → 불일치 취급도 코드 확인(`:362-369`). [정적] `:328-342` |
| TC-RESL-049 | PASS | [실동작] 905 비식별본 mtime `2030-01-01` 픽스처 → **`parent deident file replaced since snapshot (mtime) — abort`**(168) → 동일 정리 경로. `Files.exists` 선행 확인 후에만 판정, `IOException` 은 보수적 통과(`:355-359`). [정적] `:344-359` |
| TC-RESL-050 | PASS | [실동작] ★`'F'` 통과(905 `'F'` → 172 확정). [정적] `:94-103` `'N'`만 CONFLICT + 잠금 순서 parent(`:94`)→newRaw(`:113`) 고정 확인. 단위테스트 2건 |
| TC-RESL-051 | PASS | [정적] `:113-119` newRaw `findByRawSnForUpdate` 후 `'Y'` CAS → SKIPPED. IT `같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)`(baseline 통과). 트리거가 AFTER_COMMIT 단일 러너라 실동작 중복 finalize 유도 불가 |
| TC-RESL-052 | PASS | [실동작] 부모 906(`anony/psdo/prvc = N/Y/Y`) → 파생 165 프레임 2행 모두 `N/Y/Y` 복사. `SRC_FILE_PATH_NM` = **null**(정책 A), `DE_IDNTF_SRC_FILE_PATH_NM` 만 채워짐. 부모 115(3필드 null) → 파생 161/162 도 null. [정적] `:275-292`, `LsDataSrc.create(9-arg)`+`normalizeYn`. ⚠ 최초 관측(163)에서 전부 NULL 로 보였으나 **동시 구동 중인 타 에이전트가 부모 필드를 그 사이 갱신**한 것 — 재현으로 정정(위 §1-5) |
| TC-RESL-053 | PASS | [실동작] 실패 3건(166·167·168·170) 전부 `reserved aug slot released dataAugSn=.. augTypeCd=RESL_720P` 로그 + aug 행 부재 → 동일 프리셋 재요청 성공(905 720P 재요청 → 173 정상). 락아웃 없음 확인. [정적] `:200-222` |
| TC-RESL-054 | **PARTIAL** | [정적] `:205-210` 라벨맵 참조 시 skip 은 구현돼 있으나, **부모 라벨 0건이면 승자가 `LS_DATA_AUG_LBL_MAP` 행을 만들지 않아 보호가 성립하지 않는다**(`copyScaledLabels` `:386-389` 가 0 반환하며 조기 return). 실측: 파생 163·165 는 `labels=0` → 매핑 0행. 1차 **E-ISSUE-62 미해소** → **E-ISSUE-64** |
| TC-RESL-055 | PASS | [정적] `:211-221` `RESL_` 접두 아니면 delete skip + WARN. 단위테스트 `releaseReservedAug_RESL_접두가_아니면_삭제하지않는다(오배송_방어)` |
| TC-RESL-056 | PASS | [정적] `:178-187` `findByRawSnForUpdate` + (`'Y'` \|\| COMPLETED). javadoc `:168-176` 이 잔여 창(승자-뒤짐·락 타임아웃 페일오픈)을 **정직하게 명시** — 카탈로그 기대와 일치. 단위테스트 2건 |
| TC-RESL-057 | PASS | [실동작] 163·165·171·172·173 전건 `starting …` → `[A] snapshot ready` → `[B] materialized` → `[C] persisted` → `resolution derivative finalize completed` 순차 로그, 예외 0. [정적] `AsyncResolutionRunner.java:56-86` |
| TC-RESL-058 | PASS | [정적] `:62-66` empty → 로그 후 return. 단위테스트 `PhaseA가_멱등skip이면_B_C_정리_전이_모두_수행하지않는다` |
| TC-RESL-059 | PASS | [정적] `:73-79` SKIPPED → cleanup 미실행 후 return. 단위테스트 `PhaseC가_SKIPPED_중복finalize패자면_정리도_FAILED전이도_하지않는다` |
| TC-RESL-060 | PASS | [정적] `:98-113` `isAlreadyFinalized` 가 cleanup(`:127-141`)보다 **앞**. 단위테스트 `실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)` |
| TC-RESL-061 | PASS | [실동작] 실패 4건 전부 `WARN derivative discarded — finalize failed, reservation released` → cleanup → `releaseReservedAug` → `markRawDataFailed` 순서 관측. 메트릭은 `resolutionMetrics.finalizeFailed()`(`:121`) 정적 확인. [정적] `:115-150` |
| TC-RESL-062 | **PARTIAL** | [실동작] **핵심 안전성은 충족** — 원본 프레임(`/app/storage/raw/frames/raw/26`,`/27` 전건)·원본 영상 무손상, 파생 프레임 디렉터리 `frames/deid/{166..170}` 재귀 삭제 완료, 성공 파생(165)의 디렉터리는 무손상, 경로 키에 파생 RAW_SN 포함으로 타 파생 미공유 확인. **미충족** — 파생 비디오는 **파일만** 지우고 상위 디렉터리를 남긴다: `videos/resolution/905/{168,170,97}`, `videos/resolution/94/100` 등 **빈 고아 디렉터리 4건 실측**. 게다가 `cleanup()` 은 `Files.exists(videoDst)`(파일)만 보므로 `clean=true` 를 반환해 러너가 RAW 행까지 지운다 → DB 포인터 없는 잔존물. 1차 **E-ISSUE-65 미해소** → **E-ISSUE-62** |
| TC-RESL-063 | PASS | [정적] `:47-50` `@Async` + `finalizeDerivative` 의 `catch (RuntimeException)`. @Async void 라 호출자 전파 없음. ⚠ `handleFailure` 자체는 try 밖 — 관련 잔여 위험은 E-ISSUE-67 |
| TC-RESL-064 | PASS | [정적] `LsDataAug.java:306-308`(`createResolutionPending`) → `:319-323`(`buildResolution` 의 `RESL_` 접두 검증 → INVALID_INPUT 400). 근거 라인 정확 |
| TC-RESL-065 | PASS | [정적] `LsDataAug.java:340-350` — `RESL_` 접두 아니면 400, `PENDING` 아니면 CONFLICT(409) "이미 처리된 해상도 파생 행입니다". 근거 라인 정확 |
| TC-RESL-066 | **PARTIAL** | [실동작] 삭제 자체는 정상 작동 — 166·167·168·170 전건 `failed derivative RAW removed` + DB 부재. 서비스 선검사 4조건(`:242-253`)도 구현됨. **불일치** — 최종 DELETE SQL(`VideoRepository.java:494-502`)에는 조건이 **3개뿐**(`ORGNL_RAW_SN IS NOT NULL`·`DATA_STTS_CD='FAILED'`·프레임 `NOT EXISTS`)이고 `DE_IDENT_YN <> 'Y'` 가 빠져 카탈로그의 "DELETE 문에도 동일 조건 동봉"과 어긋난다. 1차 **E-ISSUE-64 미해소** → **E-ISSUE-63** (카탈로그에 실측 주석 병기 완료) |
| TC-RESL-067 | PASS | [정적] `AsyncResolutionRunner.java:126-141`(`artifactsClean` 수집)·`:155-158`(잔존이면 `deleteFailedDerivativeRaw` 미호출 + WARN). 단위테스트 `cleanup이_잔존false를_반환하면_cleanupFailed_메트릭을_올린다`. 실동작 강제(공유 NAS 퍼미션 조작)는 타 에이전트 영향 우려로 미수행. ⚠ 빈 디렉터리는 `clean` 판정에 안 들어가므로 이 보호가 발동하지 않음(E-ISSUE-62 참조) |
| TC-RESL-068 | PASS | [정적] `ResolutionFileMaterializer.java:119-138` — `legacyDir.startsWith(legacyRoot) && !legacyDir.equals(legacyRoot)` 두 조건 + raw base 미설정은 `catch` 로 잔존 판정 미반영(`clean` 불변). [실동작] 현 환경엔 `/app/storage/raw/resolution` 자체가 없어 no-op(무해) 확인 |
| TC-RESL-069 | PASS | [실동작] 파생 163 의 `RAW_FILE_PATH_NM` = `/app/storage/deidentified/videos/resolution/**906/163**/RESL_720P.mp4` — **부모+파생 RAW_SN 2단 키** 확인. 잠정 `.pending/` 경로는 커밋본 어디에도 없음(DB 실측). [정적] `ResolutionReservationPersister.java:111-119,126-130` + `StorageSubtreePolicy.resolutionVideoFile:83-85` |
| TC-RESL-070 | PASS | [실동작] 부모 115(메타 7건: `0-5` + `video.{bit_rate,codec,duration_ms,filesize,fps,resolution}`) → 파생 161 에 **7건 전건 복사**(`video.*` 포함, 값 동일). 검수행: 부모 `VLM/APPROVED` 1건 → 파생 161·162 각각 `VLM/**PENDING**` 1건(APPROVED 미승계) ✔. 로그 `[MetaCopy] copied rawSn=161 orgnlRawSn=115 metas=7 reviews=1`. 부모 메타 0건(906)이면 `parentMetas=0` no-op ✔. [정적] `DerivedMetaCopier.java:72-158` |
| TC-RESL-071 | PASS | [실동작] 파생 161·162·163·165·171·172·173 전건이 `de_ident_yn='Y'` + `COMPLETED` + aug `ACCEPTED` 로 **확정 유지**(메타 배치 upsert 의 `clear()` 로 dirty 유실이 발생하지 않음). [정적] `ResolutionPersistService.java:145-148` 이 확정블록(`:134-143`) **뒤**에 위치, `DerivedMetaCopier.java:43-49` 순서 계약 javadoc 일치. IT `해상도_finalize_실DB에서_부모메타_전건복사_VLM검수행만_PENDING신규_이면서_파생RAW는_확정유지된다(순서계약_HIGH4)` |

### 근거(file:line) 드리프트
**0건.** E-5 42행의 근거 파일·라인을 전부 실제 소스와 대조했고 모두 정확했다(3차 앞선 회차의 전수 재확인 결과가 유지됨). 다만 아래 2건은 **근거 자체가 아니라 전제/보강 정보**의 정정 대상이었고 이번 회차에 카탈로그를 고쳤다.
- TC-RESL-037 / TC-RESL-048 — 전제의 상태값 표기 `SUCCESS` → 실제 `SUCCEEDED`
- TC-RESL-066 — DELETE SQL 실제 위치(`VideoRepository.java:494-502`) 미기재

---

## 3. E-5B (폐기 절) 확인

| 확인 항목 | 결과 |
|---|---|
| 절 헤딩 취소선 + 폐기 표기 | ✔ `## ~~E-5B. 해상도 파생 산출물 비식별 저장소 이관 백필~~ **[폐기 2026-07-30]**` |
| 16행(TC-RESL-080~095) 전건 `~~TC-RESL-0xx~~` | ✔ 16/16 |
| 폐기 사유·대체 케이스 없음 명시 | ✔ 블록인용에 사유·창(V125~V133 나흘)·환경별 확인·복구 커밋(`dedb67b1`) 기재 |
| 대상 코드 실제 삭제 여부 | ✔ `grep -rl 'ResolutionBackfill' backend/src` → **Java 소스 0건**(잔존은 `V133__derivative_pii_isolation_frame_view_gate.sql` 과 `CacheConfig.java` 의 주석 언급뿐) |
| 카탈로그 위생 | ⚠ 폐기 블록인용 안에 **폐기 전 원문 문단이 그대로 이어 붙어** 기능이 살아 있는 것처럼 읽혔다 → `*(폐기 전 원문 — 이력 보존용)*` 라벨 + 3차 재확인 문단 추가로 정정 |

**판정: 폐기 표기 정확.** 상세 검증 대상 아님(지시대로 스킵), 통과율 분모 제외.

---

## 4. 이전 회차 이슈 해소 여부 (1차 2026-08-01, E-5 관련)

| 1차 이슈 | 제목 | 3차 상태 | 근거 |
|---|---|:--:|---|
| E-ISSUE-61 | 파생 프레임 목적지 파일명이 부모 basename 파생 → 다른 프레임이 같은 파일로 덮어써짐 | **미해소** | 실동작 재현(163·165·98 각 2행→1파일). `ResolutionSnapshotService.java:205-206,231-234` 무변경 → 3차 **E-ISSUE-61** |
| E-ISSUE-62 | `releaseReservedAug` 승자 보호가 부모 라벨 0건에서 무효 | **미해소** | `copyScaledLabels:386-389` 조기 return 유지 → 3차 **E-ISSUE-64** |
| E-ISSUE-63 | Phase C stale 게이트 `IOException` 폴백 주석이 이미 폐기된 신고 게이트를 근거로 듦 | **미해소** | `ResolutionPersistService.java:356` "PII 는 ②신고 게이트로 닫힘" 잔존(+`:344` 주석 번호도 ①로 오기) → 3차 **E-ISSUE-65** |
| E-ISSUE-64 | 고아 파생 RAW DELETE 문에 `DE_IDENT_YN <> 'Y'` 누락 | **미해소** | `VideoRepository.java:494-502` 조건 3개 → 3차 **E-ISSUE-63** |
| E-ISSUE-65 | cleanup 이 파생 비디오 파일만 지우고 빈 디렉터리 트리를 남김 | **미해소** | 빈 고아 디렉터리 4건 실측 → 3차 **E-ISSUE-62** |
| E-ISSUE-24 | `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 존재 — 게이트 대소문자 민감 | **미해소**(참고) | `de_ident_yn='y'` 1행(raw_sn=68) 잔존, `hasDeidentArtifact()` 는 `"Y".equals \|\| "F".equals` 로 여전히 대소문자 민감. E-4 소관이라 이슈 재발행하지 않고 사실만 기록 |

> **E-5 관련 1차 이슈 5건 전부 미해소로 이월.** 수정 사이클이 아직 이 구간에 닿지 않았다.

---

## 5. 이슈 대장 (E-ISSUE-61 ~ 67)

### [E-ISSUE-61] TC-RESL-041 / TC-RESL-044 — 파생 프레임 목적 파일명이 부모 basename 에서 파생돼 **서로 다른 프레임이 한 파일로 덮어써진다**(무경고 확정)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 부모 프레임 N건 → 파생 프레임 파일도 N건이어야 한다. 파생영상의 유일한 소비자는 관제서버이고(`CLAUDE.md` "파생영상에는 원본영상이 없다"), 관제는 `V_COMPLETED_FRAME`/export 폴더로 프레임 이미지를 픽업한다. 프레임 이미지가 서로 뒤바뀌면 **라벨 좌표는 프레임 A 것인데 픽셀은 프레임 B** 인 학습데이터가 마트로 나간다.
- **현재 동작(이슈 내용)**: 목적 경로가 `frames/deid/{newRawSn}/` + **부모 비식별 프레임의 basename** 으로 조립된다. 부모 프레임들이 서로 다른 디렉터리에 있고 파일명이 같으면 목적 경로가 충돌한다.
  ```java
  // ResolutionSnapshotService.java:205-206
  Path fdst = resolveSafeDir(base,
          StorageSubtreePolicy.deidFramesDir(newRawSn) + "/" + fileNameOf(deidFrameSrc, pf));
  // :231-234
  private static String fileNameOf(String frameSrc, LsDataSrc frame) {
      Path name = Paths.get(frameSrc).getFileName();
      return name != null ? name.toString() : (frame.getFrameNo() + ".jpg");
  }
  ```
  중복 방지는 `seenFrameKeys`(videoFrameNo, `:194-199`)뿐이고 **목적 파일명 중복은 검사하지 않는다**. Phase B 는 `imageResizer.resize(f.deidSrc(), f.dst(), …)` 를 순차 실행하므로 뒤 프레임이 앞 프레임을 덮어쓰고, Phase C 는 그 사실을 모른 채 `LS_DATA_SRC` N행을 모두 같은 경로로 INSERT + `markDeidentified('Y')` + `markCompleted()` + aug `ACCEPTED` 로 **정상 확정**한다. 경고 로그조차 없다(`[B] materialized rawSn=163 frames=2` 는 스펙 개수를 셀 뿐).
- **재현/확인 경로**:
  ```bash
  # 부모 906: 프레임 2건의 deid 경로가 서로 다른 디렉터리 + 동일 basename
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT src_sn, frm_no, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=906 ORDER BY frm_no;"
  #  464 | 0 | /app/storage/deidentified/frames/deid/26/frame-0.jpg
  #  465 | 1 | /app/storage/deidentified/frames/deid/27/frame-0.jpg

  curl -s -X POST localhost:18081/api/v1/videos/906/resolution -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"presets":["RESL_1080P"]}'   # -> 201 rawSn=165

  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, count(*) rows, count(DISTINCT de_idntf_src_file_path_nm) paths
       FROM ls_data_src WHERE raw_sn IN (98,163,165) GROUP BY 1;"
  #  98|2|1   163|2|1   165|2|1     <-- 2행이 같은 1경로
  docker exec klid-backend ls /app/storage/deidentified/frames/deid/165/
  #  frame-0.jpg                    <-- 파일도 1개뿐 (정상 파생 171 은 frame-0..4 5개)
  ```
- **영향**: 데이터 무결성 — 파생영상의 프레임 픽셀↔라벨 불일치가 **조용히** 관제/데이터마트로 유출. 손실된 프레임의 원본 픽셀은 파생본에 존재하지 않는다(복구 불가, 재생성만 가능). 현재 부모 프레임은 대개 `frames/deid/{parentRawSn}/frame-N.jpg` 단일 디렉터리라 일상 경로에서는 충돌하지 않지만, **재비식별·재추출로 프레임이 다른 디렉터리에 흩어진 부모**(실환경에 이미 존재)에서 발생한다. CWE-706(경로 이름 부적절 해석) 계열.
- **수정 방향(제안)**: 목적 파일명을 **부모 파일명이 아니라 파생 자신의 프레임 키**로 만든다 — 예 `frame-{frameNo}.{ext}` 또는 `{videoFrameNo}.{ext}`(부모 프레임 유일성 검사와 같은 축). 확장자만 소스에서 취한다. 추가로 `buildFrameSpecs` 에 **목적 경로 중복 fail-fast**(`seenFrameKeys` 와 같은 방식으로 `seenDst`)를 넣어 규약 위반이 다시 생겨도 확정 전에 멈추게 한다. 기존 충돌 파생(98·163·165 등)은 재생성 대상 식별 쿼리(`count(*) <> count(DISTINCT de_idntf_src_file_path_nm)`)로 뽑아 별도 정리.

### [E-ISSUE-62] TC-RESL-062 / TC-RESL-067 — cleanup 이 파생 비디오 **파일만** 지우고 디렉터리를 남기며, 그 잔존을 `clean=true` 로 오판한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 확정 실패 파생의 Phase B 산출물은 흔적 없이 정리돼야 한다. 특히 `cleanup()` 의 반환값은 러너가 "RAW 행(유일한 DB 포인터)을 지워도 되는가"를 판단하는 근거(`AsyncResolutionRunner.java:155-158`)이므로, 잔존물이 있으면 `false` 여야 한다.
- **현재 동작(이슈 내용)**: 프레임 디렉터리는 `deleteRecursivelyQuietly` 로 재귀 삭제되지만, 파생 비디오는 파일 1개만 삭제되고 그 부모 디렉터리(`videos/resolution/{parentRawSn}/{newRawSn}/`)가 남는다. 잔존 판정도 파일만 본다.
  ```java
  // ResolutionFileMaterializer.java:141-152
  if (videoDst != null) {
      try {
          deleteFileQuietly(videoDst);
          if (Files.exists(videoDst)) { clean = false; }   // 파일만 확인 — 상위 디렉터리는 미검사
      } catch (RuntimeException e) { clean = false; ... }
  }
  ```
  결과적으로 `clean=true` → 러너가 `deleteFailedDerivativeRaw` 로 RAW 행까지 지워 **DB 어디서도 참조되지 않는 빈 디렉터리**가 영구 누적된다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend find /app/storage/deidentified/videos/resolution -mindepth 2 -maxdepth 2 -type d -empty
  # /app/storage/deidentified/videos/resolution/905/168
  # /app/storage/deidentified/videos/resolution/905/170
  # /app/storage/deidentified/videos/resolution/905/97
  # /app/storage/deidentified/videos/resolution/94/100        (4건)
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT count(*) FROM ls_data_raw WHERE raw_sn IN (168,170,97,100);"   # -> 대응 RAW 없음
  ```
- **영향**: PII 노출은 없다(빈 디렉터리). 다만 NAS inode 무한 누적 + 운영자가 "이 디렉터리는 뭐지"를 추적할 DB 포인터가 없다. 5,000건 영상 × 3프리셋 재시도 규모에서 축적된다.
- **수정 방향(제안)**: `videoDst` 삭제 후 **부모 디렉터리가 비어 있으면 함께 제거**(`Files.deleteIfExists(videoDst.getParent())`, 단 `videos/resolution/{parentRawSn}` 루트까지 올라가지 않도록 파생 RAW_SN 세그먼트 1단만). 그리고 그 디렉터리 잔존도 `clean` 판정에 포함해 `deleteFailedDerivativeRaw` 보호(TC-RESL-067)와 일관되게 한다. 기존 4건은 일회성 스크립트로 정리(`-type d -empty` + DB 미참조 확인 후).

### [E-ISSUE-63] TC-RESL-066 — 고아 파생 RAW `DELETE` SQL 에 `DE_IDENT_YN <> 'Y'` 가 빠져 "검사 조건 = SQL 조건" 계약이 성립하지 않는다 (1차 E-ISSUE-64 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그·javadoc 이 명시한 대로 "잠금 후 4조건 재확인 + **최종 DELETE 문에도 동일 조건 동봉**"이어야 검사~삭제 사이 창이 닫힌다. `LS_DATA_SRC.RAW_SN` 에 FK 가 없어 DB 가 대신 막아주지 않으므로 SQL 조건이 마지막 방어선이다.
- **현재 동작(이슈 내용)**: 서비스는 4조건을 검사하는데 SQL 은 3조건뿐이다.
  ```java
  // ResolutionPersistService.java:246 — 서비스 선검사에는 있다
  if ("Y".equals(raw.getDeIdntfYn()) || !LsDataRaw.DATA_STTS_FAILED.equals(raw.getDataSttsCd())) { ... return false; }
  ```
  ```sql
  -- VideoRepository.java:495-501 — SQL 에는 없다
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  ```
- **재현/확인 경로**: 정적. `sed -n '494,502p' backend/src/main/java/kr/co/cudo/authoring/video/repository/VideoRepository.java`. 실동작 삭제 경로 자체는 정상(166·167·168·170 삭제 확인) — 결함은 **경합 창**에서만 드러난다: 검사 통과 후 DELETE 직전에 승자 Phase C 가 `markDeidentified('Y')` + `markCompleted()` 를 커밋하면 `DATA_STTS_CD` 가 `COMPLETED` 로 바뀌어 실제로는 SQL 이 0건 삭제하므로 **현 상태 머신에서는 사고가 나지 않는다**(`'Y'` + `FAILED` 조합이 만들어지지 않기 때문). 즉 위험은 잠재적이고, 문제는 **문서화된 계약과 코드의 불일치**다.
- **영향**: 현재 데이터 손실 위험은 낮음(위 근거). 그러나 향후 `'Y'` 와 `FAILED` 가 공존하는 전이(예: 확정 후 후처리 실패로 FAILED 표기)가 생기면 **확정된 파생 RAW 가 삭제**된다. 방어 심층화 위반.
- **수정 방향(제안)**: DELETE 문에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가해 서비스 선검사와 1:1로 맞춘다(`VideoRepository.java:495-501`). 회귀 가드로 `ResolutionPersistServiceTest.고아행_정리_직전_상태가_바뀐_행은_삭제되지_않는다` 에 "`'Y'` 로 바뀐 FAILED 행" 케이스를 추가. 카탈로그 TC-RESL-066 에는 이번 회차에 실측 주석을 병기해 두었다.

### [E-ISSUE-64] TC-RESL-054 — `releaseReservedAug` 의 "승자 보호"가 **부모 라벨 0건 영상에서 무효** (1차 E-ISSUE-62 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 동시 finalize 에서 패자의 실패 정리가 **승자의 예약 aug 행을 지우면 안 된다**. 지우면 승자의 파생 RAW 는 확정돼 있는데 `LS_DATA_AUG`(RESL_*) 행이 사라져 ①증강 이력(`GET /v1/augments`)에서 사라지고 ②`new_raw_sn` 매핑이 끊겨 파생 식별 근거가 없어지며 ③같은 (부모, 프리셋) 재요청이 통과해 중복 파생이 생긴다.
- **현재 동작(이슈 내용)**: 보호 근거가 `LS_DATA_AUG_LBL_MAP` 참조 존재 **하나뿐**인데, 그 매핑은 부모에 라벨이 있어야만 만들어진다.
  ```java
  // ResolutionPersistService.java:205-210
  List<LsDataAugLblMap> refs = lblMapRepository.findAllByDataAugSn(dataAugSn);
  if (!refs.isEmpty()) { ...skip... }        // 라벨 0건이면 refs 는 항상 비어 있다
  // :386-389 — 승자조차 매핑을 만들지 않는다
  List<LsDataLbl> parentLabels = lblRepository.findBySrcSnIn(parentSrcToNewSrc.keySet());
  if (parentLabels.isEmpty()) { return 0; }
  ```
- **재현/확인 경로**:
  ```bash
  # 부모 906 은 라벨 0건 -> 파생 163/165 확정 후에도 매핑 0행
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT (SELECT count(*) FROM ls_data_lbl l JOIN ls_data_src s ON s.src_sn=l.src_sn WHERE s.raw_sn=906) parent_lbl,
            (SELECT count(*) FROM ls_data_aug_lbl_map WHERE data_aug_sn IN (59,60)) map_rows;"
  # parent_lbl=0, map_rows=0   -> 이 aug 는 releaseReservedAug 가 무조건 삭제한다
  ```
  실제 동시 finalize 는 트리거가 단일 AFTER_COMMIT 러너라 현 배선에서는 재현되지 않는다(잠재 결함).
- **영향**: 데이터 정합 — 라벨이 아직 없는(막 검수 승인된, 오토라벨 0건인) 영상의 파생에서 승자 예약행 소실. 파생 등재 게이트(`CLAUDE.md` "등재 게이트의 축은 리뷰 행")의 해상도 예외 판정 근거인 매핑 행이 사라지는 것도 부작용.
- **수정 방향(제안)**: 보호 근거를 라벨맵이 아니라 **파생 RAW 확정 상태**로 바꾼다 — `releaseReservedAug(dataAugSn)` 진입 시 `aug.getNewRawSn()` 으로 파생 RAW 를 조회해 `deIdntfYn='Y' || DATA_STTS_CD=COMPLETED` 이면 삭제 skip. 라벨맵 검사는 보조로 유지. 또는 `aug.augProcSttsCd == ACCEPTED`(확정 전이 완료)면 skip — `markResolutionGenerated` 가 승자 커밋에서만 일어나므로 라벨 유무와 무관한 판정축이 된다.

### [E-ISSUE-65] TC-RESL-049 — Phase C stale 게이트의 `IOException` 폴백 주석이 **이미 폐기된 신고 게이트**를 안전 근거로 든다 (1차 E-ISSUE-63 이월)
- **심각도**: LOW (주석/추적성 — 동작 영향 없음)
- **기대 동작(기대효과)**: fail-open 분기(`stat` 실패 시 통과)의 주석은 "왜 통과시켜도 안전한가"의 **현재 유효한** 근거를 제시해야 한다. 폐기된 게이트를 근거로 들면 다음 사람이 "다른 게이트가 막아주니 괜찮다"고 오판한다.
- **현재 동작(이슈 내용)**:
  ```java
  // ResolutionPersistService.java:355-359
  } catch (IOException e) {
      // stat 실패는 결정적 게이트(①②)가 이미 통과했으므로 보수적으로 통과(로그만). PII 는 ②신고 게이트로 닫힘.
      log.warn("[Video][ResolutionDerivative][C] deident mtime check skipped ...");
  }
  ```
  "②신고 게이트" 는 2026-07-30 에 **제거**됐다(같은 파일 `:312-314` 이 "구 조건 *capturedAt 이후 신고 이력 존재* 는 순수 신고 결합이라 제거됐다"고 명시). 또한 `:344` 의 두 번째 조건 주석이 `// ①` 로 번호가 잘못 붙어 있어(앞선 `:328` 도 `①`) 두 조건이 구분되지 않는다.
- **재현/확인 경로**: 정적. `sed -n '344,359p' backend/src/main/java/kr/co/cudo/authoring/video/service/ResolutionPersistService.java`
- **영향**: 추적성/유지보수. 이 저장소의 "철회된 정책 재시도" 사고 패턴(조상/자손 전파 4라운드)과 같은 뿌리 — 폐기된 정책이 주석에 살아 있으면 되살아난다.
- **수정 방향(제안)**: 주석을 실제 근거로 교체 — *"경로 게이트(①)가 결정적으로 통과했고, 파생 RAW 는 이 시점까지 `deIdntfYn='N'` 이라 미서빙이므로 mtime 미확인을 보수적으로 통과시킨다"*. 두 번째 조건 번호를 `②` 로 정정.

### [E-ISSUE-66] TC-RESL-033 — 중복/경합 예약 패자가 API 표면에서 **409 가 아니라 500** 으로 응답된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 예약 계층은 정확히 409 CONFLICT("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")를 던진다. 단일 프리셋 요청에서 그 원인이 **클라이언트가 고칠 수 있는 상태 충돌**이라면 API 도 4xx 로 알려줘야 FE 가 "이미 있음"과 "서버 오류"를 구분해 안내·재시도 정책을 나눌 수 있다. 특히 `CLAUDE.md` 는 파생 중복의 **연타 방어를 FE 단독 책임**으로 두고 있어, FE 가 응답으로 원인을 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.changeResolution` 이 프리셋별 실패를 격리하며 예외를 삼키고, 전부 실패하면 일괄 500 을 낸다.
  ```
  WARN  VideoResolutionService - [Video][Resolution] derivative creation failed rawSn=906 preset=RESL_720P reason=CustomException
        at ResolutionReservationPersister.reserveAndCreate(ResolutionReservationPersister.java:107)   <-- 내부는 CONFLICT
  ERROR VideoResolutionService - [Video][Resolution] all presets failed rawSn=906 attempted=1
  ```
  ```json
  HTTP 500 {"success":false,"message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다.","errorCode":"INTERNAL_ERROR"}
  ```
- **재현/확인 경로**:
  ```bash
  # (1) 순차 중복
  curl -s -w '\n%{http_code}\n' -X POST localhost:18081/api/v1/videos/906/resolution \
    -H "Authorization: Bearer $REV" -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'
  # -> 500 INTERNAL_ERROR  (내부 원인은 409 CONFLICT)

  # (2) 동시 경합 3발
  for i in 1 2 3; do curl -s -o /tmp/c$i -w "req$i %{http_code}\n" -X POST \
    localhost:18081/api/v1/videos/905/resolution -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}' & done; wait
  # -> req3 201 / req1 500 / req2 500 ... aug 행은 정확히 1건(직렬화 자체는 정상)
  ```
- **영향**: 기능/UX. FE 가 `errorCode=INTERNAL_ERROR` 만 보고는 "장애"로 오인해 재시도 루프를 돌거나 사용자에게 잘못된 안내를 한다. 500 은 모니터링 알람도 오염시킨다(정상 중복 클릭이 서버 에러로 집계). 보안 영향은 없다.
- **수정 방향(제안)**: `VideoResolutionService` 의 롤업 규칙을 **원인 코드 보존형**으로 바꾼다 — 시도한 프리셋이 전부 실패했고 그 실패가 **모두 동일한 4xx `CustomException`** 이면 그 코드/메시지를 그대로 전파하고, 혼재·5xx 포함일 때만 현재의 500 롤업을 유지한다. E-4 의 "1건 이상 성공=201 / 전부 실패=500 / 전부 스킵=400" 계약과 충돌하지 않도록 카탈로그(E-4)도 함께 갱신 필요 — **이 판단은 E-4 담당 범위와 겹치므로 병합 시 조정 요망**.

### [E-ISSUE-67] TC-RESL-061 / TC-RESL-066 — `handleFailure` 의 `markRawDataFailed` 가 무가드라 예외 시 고아 RAW 정리(④)가 건너뛰어진다
- **심각도**: LOW
- **기대 동작(기대효과)**: 실패 정리의 모든 단계는 best-effort 이며 "원래 실패를 가리지 않는다"가 이 메서드의 명시 계약(`AsyncResolutionRunner.java:90-91`). 앞 단계 실패가 뒤 단계를 통째로 건너뛰게 하면 안 된다.
- **현재 동작(이슈 내용)**: cleanup(`:128-140`)과 `releaseReservedAug`(`:144-149`)는 각각 try/catch 로 감싸져 있으나 `markRawDataFailed` 는 맨몸이다.
  ```java
  // AsyncResolutionRunner.java:150
  batchTransitionService.markRawDataFailed(newRawSn);
  // :159-164  ← 위에서 예외가 나면 이 블록에 도달하지 못한다
  try { persistService.deleteFailedDerivativeRaw(newRawSn); } catch (RuntimeException re) { ... }
  ```
- **재현/확인 경로**: 정적. 관련 관측 사실 — 파생 RAW 는 `LS_RAW_DATA_STATUS` 행이 없어 매 실패마다 WARN 이 뜬다: `[BatchTransition] raw data status not found rawSn=170 target=FAILED`(정상 폴백 경로이며 결함 아님, 다만 이 WARN 이 실패 로그에 늘 섞여 실제 이상 신호를 가린다).
- **영향**: 관측성/누적. `markRawDataFailed` 가 던지는 상황(락 타임아웃 등)에서 고아 파생 RAW 가 정리되지 않고 남는다. E-ISSUE-23(고아 무한 누적) 재발 경로.
- **수정 방향(제안)**: `markRawDataFailed` 호출도 try/catch 로 감싸고, 실패해도 ④ 로 진행한다. 더불어 파생 RAW 에 대한 `raw data status not found` WARN 은 **파생(ORGNL_RAW_SN != null)일 때 DEBUG 로 낮추거나 메시지에 "파생 — 정상"을 명시**해 실패 로그의 신호대잡음비를 높인다.

---

## 6. 카탈로그 정정 내역 (담당 범위 내 Edit, 총 4건 + 절 위생 1건)

| 대상 | 정정 내용 | 사유 |
|---|---|---|
| TC-RESL-037 | 전제 `SUCCESS procLog 없음` → `최신 성공 procLog 없음 — ⚠ 실제 적재 상태값은 **SUCCEEDED**` + 근거에 `findLatestSuccessByDataRawSn(REQ_DT DESC)` 추가 | DB 실측값이 `SUCCEEDED`. 3차 검증 중 `proc_stts_cd='SUCCESS'` 로 조회해 "procLog 0건"으로 **실제 오판이 발생**했다 |
| TC-RESL-048 | 전제 `최신 SUCCESS procLog` → `최신 성공(SUCCEEDED) procLog` | 동일 |
| TC-RESL-066 | 근거에 `VideoRepository.java:494-502` 추가 + "DELETE SQL 실측 3조건뿐(`DE_IDENT_YN<>'Y'` 누락, E-ISSUE-63)" 주석 병기 | 기대결과가 주장하는 "동일 조건 동봉"의 검증 지점이 카탈로그에 없었다. **기대결과 자체는 낮추지 않았다**(결함을 숨기지 않기 위해) |
| TC-RESL-041 | 기대결과에 ★"목적 파일 ↔ 부모 프레임 1:1" 불변식 명문화 + 실측 FAIL·E-ISSUE-61 참조 + 근거에 `ResolutionSnapshotService.java:205-206,231-234` 추가 | 이 불변식이 카탈로그에 없어 1차에서 지적된 결함(E-ISSUE-61)이 회차마다 "검증 대상 밖"으로 흘렀다 |
| E-5B 블록인용 | 폐기 문단 뒤에 이어 붙어 있던 **폐기 전 원문**에 `*(폐기 전 원문 — 이력 보존용)*` 라벨 부여 + 3차 재확인 문단(Java 소스 0건·16행 취소선 정상) 추가 | 폐기 절인데 본문이 기능이 살아 있는 것처럼 읽혔다 |

추가로 파일 상단 `## 변경 이력` 아래에 **[3차 · E-5 part4 추가 정정 2026-08-03]** 항목을 신설해 위 4건을 요약 기재했다.

> ⚠ 이 카탈로그 파일은 검증 중 **다른 part 에이전트도 동시에 편집**했다(Edit 도구가 "modified on disk" 경고). 본 정정은 전부 E-5/E-5B 담당 라인 범위 안에서 수행했고 타 절은 건드리지 않았다.

---

## 7. 실동작 환경 관련 특기사항 (다음 회차 참고)

1. **`proc_stts_cd` 값은 `SUCCEEDED`** — `SUCCESS` 로 조회하면 전건 0건. `_raw/` 문서·카탈로그의 "SUCCESS procLog" 표현에 주의.
2. **컬럼명 함정** — `LS_DATA_RAW.DE_IDENT_YN`(엔티티 필드는 `deIdntfYn`, 컬럼은 `DE_IDENT_YN`), `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`(엔티티 getter 는 `getDeidFilePath()`), `LS_RAW_DATA_STATUS.RAW_DATA_ID`(`RAW_SN` 아님), `LS_DATA_LBL` 은 bbox 컬럼이 없고 `POINT_CN` JSON 하나, `LS_DATA_AUG_LBL_MAP.ORGNL_DATA_LBL_SN`. `LS_DATA_RAW` 에 `RESL` 컬럼은 없다(해상도는 `LS_DATA_META.video.resolution`).
3. **공용 DB 동시 검증 주의** — 여러 part 에이전트가 같은 스택을 쓴다. 부모 영상의 상태를 파생 생성 **직후**에 읽으면 타 에이전트의 갱신이 섞여 거짓 FAIL 이 난다(§1-5 실제 사례). 부모 상태는 생성 직전에 스냅샷할 것.
4. **이번 회차 픽스처 활용** — 1·2차가 만들어 둔 검증용 영상이 그대로 유효했다: 903(중복 vdo_frm_no) · 902(deid 경로 결손) · 905(`qa-e5/stale.mp4` mtime 2030) · 906(`QA-E5-DSTCOLLIDE`, deid basename 충돌) · 900(`'F'` + 3프리셋). 다음 회차도 재사용 권장.
5. **미검증으로 남은 것** — TC-RESL-043 의 429 실발화(픽스처 프레임이 2~6장이라 세마포어 포화 불가), TC-RESL-067 의 아티팩트 잔존 강제(공유 NAS 퍼미션 조작 필요), TC-RESL-051/059/060 의 실제 중복 finalize(트리거가 단일 AFTER_COMMIT 러너). 전부 단위/IT 로 커버되어 있고 baseline 실패 0건이라 PASS 로 판정했으나, 대규모 영상(수백 프레임) 픽스처가 생기면 TC-RESL-043 은 실동작 재검증 가치가 있다.

---

# E 클러스터 part5 — E-6. 데이터셋 Export (3차, 2026-08-03)

> 담당: `docs/test-cases/E-augment-resolution-export-meta.md` **E-6. 데이터셋 Export**(243~269행) — **23건**
> 대상 코드: `DatasetExportService` / `DatasetExportBridge` / `AsyncDatasetExportRunner` / `DatasetExportTxService` / `DatasetExportFailureRecoverer` / `DatasetExportPathResolver` / `DatasetExportWriter`
> 검증 방식: **실동작 최우선** — 풀스택(`klid-postgres`·`klid-backend`:18081·`klid-mock-server`:9400·`klid-ai-server`·`klid-frontend`) 위에서 실제 API 호출 + DB 조회 + 백엔드/목업 로그 + 파일시스템 실측. 프로덕션 코드·설정 **미변경**(DB 데이터·파일 mtime 조작만 — 각 항목에 명시).

---

## 0. 이번 파트에서 사용한 실동작 시나리오

| # | 시나리오 | 대상 | 결과 |
|:-:|---------|------|------|
| S1 | 승인 완료 영상에 **라벨 수정** → 디바운스 flush → 재export | rawSn=101 (srcSn=468, labelSn=729) | v3 신규 생성 + v1·v2 보존 |
| S2 | **비식별 누락 신고** 접수 후 재export 트리거 | rawSn=94 (srcSn=448) | export 차단(행 미생성) + 통지 보류 |
| S3 | **신고 해소(resolve)** → 보류분 복구 | rawSn=94 | v7 재생성 + TASK_COMPLETED 재개 |
| S4 | **산출 base 를 허용 루트 밖으로**(`/etc/evil/clip.mp4`) 조작 → 재export | rawSn=94 | FORBIDDEN → v8 FAILED(path=null) + 통지 보류 |
| S5 | **재승인**(제출→시작→승인) 2회 — 2회차는 **무수정** | rawSn=94 | v9·v10 신규 생성(v10 은 v9 와 **동일 해시**인데도 생성) |
| S6 | 다른 에이전트가 만든 **입력 부재 영상** 재export 관측 | rawSn=906 | `NO_INPUT` → 통지 보류 |
| S7 | **파생영상(증강) export** 산출물 실측 | rawSn=18 (ORGNL_RAW_SN=4) | `v1/deid` 1벌만, `orgnl` 폴더 부재, SUCCEEDED |
| S8 | **실패 export 회수기** — 최신 FAILED 앵커 생성 후 주기 잡 관측 | rawSn=94 v11 | §5 참조 |

> 검증용 데이터 조작(코드/설정 무변경): ①`ls_data_raw.raw_file_path_nm` 임시 변경 후 **원복 확인**(S4·S8) ②비식별 산출물 `touch`(신고 resolve 게이트 통과용, 1차와 동일 방식) ③프레임 설명/라벨 좌표 수정(정상 API).

---

## 1. 케이스별 판정

| ID | 판정 | 근거 확인 |
|----|:--:|----------|
| TC-EXPORT-001 | PASS | [실동작] rawSn=94 재승인 커밋 직후 `01:51:00.030 [DatasetExportBridge] … → AsyncDatasetExportRunner - async approval export starting rawSn=94` → v9 산출. AFTER_COMMIT 이후에만 발화(승인 실패 시나리오 `REVIEW_NO_LABEL` 400 에서는 export 로그 0건 — 01:50:36 승인 차단 시 러너 미발화 실측). [정적] `DatasetExportBridge.java:36-43` `runner.runApprovalAsync(rawSn)`(force=true) — **근거 정확** |
| TC-EXPORT-002 | PASS | [실동작] **무수정 재승인**(S5 2회차) → `export_ver_no=10` 신규 생성. `content_hash` 가 직전 baseline(v9 PARTIAL, `e82c89554414affc…`)과 **완전 동일한데도** skip 되지 않고 v10 폴더에 파일 20개 신규 기록. [정적] `:154-160` `if (!forceRegenerate && prep.isUnchangedFromLastExport())` — 승인 경로는 조건 자체를 건너뜀. **근거 정확** |
| TC-EXPORT-003 | PASS | [정적] `:154-160`(멱등 skip) + `DatasetExportTxService.java:144-154` baseline 조회가 `List.of(STATUS_SUCCEEDED, STATUS_PARTIAL)` IN 필터 → PARTIAL 포함 확인. 행 미INSERT + `outcome=idempotent_skip` metric+log 만. ⚠ **실동작 도달 불가** — 유일한 트리거 `DatasetReExportEvent` 의 **발행처가 0건**(`DatasetExportBridge.java:50-53` javadoc "휴면 리스너", `EvntAnnoReviewService.java:224-227` 이 이중 export 때문에 제거). 런타임 metric `dataset.export.result{outcome=idempotent_skip}` **태그 자체가 미생성**(availableTags 에 없음)으로 교차 확인. → **카탈로그에 휴면 사실 + baseline 근거 추가 정정**(§3) |
| TC-EXPORT-004 | PASS | [실동작] rawSn=101 v3: `frames written kind=ORIGINAL written=10 skipped=0` + `kind=DEIDENTIFIED written=10 skipped=0` → `export succeeded rawSn=101 version=3 written=20`, `export_stts_cd=SUCCEEDED`. `EXPORT_PATH_NM=/app/storage/raw/seed/101` = `dirname('/app/storage/raw/seed/clip-9101.mp4')/101` — **버전 루트 아님**(v1·v2·v3·deid 가 모두 이 아래 형제). [정적] `DatasetExportPathResolver.java:45-47 resolveVideoRoot` — **근거 정확** |
| TC-EXPORT-005 | PASS | [실동작] rawSn=94(유령 프레임 frm_no=99 보유) → `frame image missing skipped … frameNo=99`(ORIGINAL/DEID 각 1) → `partial export rawSn=94 version=7 written=10 skipped=2` → `export_stts_cd=PARTIAL`. metric `dataset.export.skipped_frames` 가 **2.0 → 6.0**(PARTIAL 3회 × 2)으로 증가 확인. [정적] `:215-226` — **근거 정확** |
| TC-EXPORT-006 | PASS | [정적] `:209-214` `if (totalWritten == 0) { txService.markFailed(...); outcome = FAILED; }` — `throw` 없음 → 승인 롤백 경로 없음(@Async 분리). 단위테스트 `DatasetExportServiceTest.아무것도_산출못하면_FAILED로_전이한다 — totalWritten==0` 커버(baseline 실패 0건). 실동작은 "전 프레임 이미지 부재" 상태를 만들어야 해 미유도. ⚠ 이 분기가 남기는 **빈 `v{n}` 디렉터리**는 정리되지 않는다 → **E-ISSUE-83** |
| TC-EXPORT-007 | PASS | [실동작] 파생영상 rawSn=18(`ORGNL_RAW_SN=4`) — `ls_data_src` 30행 중 `src_file_path_nm` **0건**/`de_idntf_src_file_path_nm` 30건. 산출물 트리: `/app/storage/deidentified/videos/augment/4/18/18/v1/deid/` **딱 하나**(파일 60개=30jpg+30json), `v1/orgnl` **디렉터리 자체가 없음**. `export_stts_cd=SUCCEEDED`(PARTIAL 강등 아님), `frame_cnt=30`. [정적] `:191-203`(originalAbsent 분기) + `:336-342 hasNoOriginalFrames` — **근거 정확** |
| TC-EXPORT-008 | PASS | [정적] `:373-383 insertWithRetry` — `for (attempt=1..MAX_VERSION_RETRY=3)` + `catch (DataIntegrityViolationException)` 재채번. `DatasetExportTxService.insertNextVersion` 이 `REQUIRES_NEW` + `saveAndFlush` 라 UK 위반이 그 트랜잭션 안에서 즉시 터지고 rollback-only 가 격리됨(`:173-183`). 테스트 `동시_승인_UK위반시_재시도로_다음버전_채번된다` + IT `LS_DATASET_EXPORT_UK_중복_버전_삽입시_제약위반`. **근거 정확** |
| TC-EXPORT-009 | PASS | [정적] `:177-182` `inserted == null` → `log.error(version numbering exhausted)` + `outcome = VERSION_EXHAUSTED` + `return`(abort). `DatasetExportOutcome.VERSION_EXHAUSTED` 의 `notifiable=false`(`:52`) → 통지도 보류. 테스트 `UK위반이_재시도_상한_초과하면_산출을_중단한다` + `재채번_소진시_result_version_exhausted_1회`. **근거 정확** |
| TC-EXPORT-010 | PASS | [정적] `:238-246` `catch (RuntimeException e) { txService.markFailed(inserted.exportSn()); log.warn(… cause={}, e.getClass().getSimpleName()); outcome = FAILED; }` — 예외 미전파(승인 불변), 로그에 **예외 클래스명만**(메시지·스택·경로 원문 없음, CWE-209/359). 테스트 `파일산출_실패해도_승인은_롤백되지_않는다`. **근거 정확** |
| TC-EXPORT-011 | PASS | [실동작] rawSn=906 재export flush 시 `01:42:36.066 DatasetExportTxService - no active video meta — skip export rawSn=906` → `AsyncDatasetExportRunner - async export not notifiable — notify withheld rawSn=906 outcome=NO_INPUT`. `ls_dataset_export` 에 906 행 **0건**(행 미INSERT), metric `outcome=no_input` 카운트 4→5 증가. [정적] `:147-151` — **근거 정확** |
| TC-EXPORT-012 | PASS | [실동작] 런타임 metric 실측 — `dataset.export.result` COUNT **33.0**, `dataset.export.duration` COUNT **33.0**(정확히 1:1). 태그별 합 `completed 21 + partial 3 + failed 2 + no_input 5 + deident_blocked 2 = 33` 로 **배타 1회** 확인. **예외 이탈 경로(deident_blocked 2건)도 누락 없이 계상**(`finally` 단일 지점). [정적] `:110`(startSample) + `:261-269`(finally) — **근거 정확** |
| TC-EXPORT-013 | PASS | [정적] `DatasetExportBridge.java:29-30` `@ConditionalOnProperty(prefix="authoring.dataset-export", name="enabled", havingValue="true", matchIfMissing=true)`. `DatasetExportBridgeBeanConditionTest` 3건(true 로드 / false 미로드 / 미설정 matchIfMissing 로드) 커버·통과. 현 환경은 기본(미설정)이라 브릿지 활성 — 실동작(S1·S5)이 이를 뒷받침. **근거 정확** |
| TC-EXPORT-014 | PASS | [실동작] `find /app/storage/raw/seed/101` → `101/deid/clip-9101-mask.mp4` · `101/v1|v2|v3/{orgnl,deid}/0000.jpg~0009.jpg + 0000.json~0009.json`. 고정 `labeling_root` 흔적 0건. 파생영상도 동일 규약(`…/augment/4/18/18/v1/deid`). [정적] `DatasetExportPathResolver.java:58-68 resolve` → `resolveUnder(videoRoot, "v"+version, kind.segment())`. **근거 정확** |
| TC-EXPORT-015 | PASS | [실동작] `UPDATE ls_data_raw SET raw_file_path_nm='/etc/evil/clip.mp4' WHERE raw_sn=94` 후 재export → `01:48:46.358 ERROR [DatasetExport] export base rejected — marked FAILED rawSn=94 reason=FORBIDDEN`. DB: `export_ver_no=8, export_stts_cd=FAILED, export_path_nm=NULL`(레코드는 남음 — 흔적 없이 사라지지 않음). **기본 루트로의 조용한 폴백 0건**(`/app/storage/**` 어디에도 v8 디렉터리 미생성). 로그에 경로 원문 없음(`reason=FORBIDDEN` 만, CWE-209). 통지도 `notify withheld … outcome=FAILED` 로 보류. [정적] `:166-173`(try/catch → markBaseRejected) + `:351-366` + `VideoArtifactRootResolver`(allowlist → normalize → toRealPath 3단). **근거 정확** |
| TC-EXPORT-016 | PASS | [실동작] rawSn=94 에 `POST /v1/labels/448/deident-report` → `DE_IDENT_YN='F'`. 이후 승인후수정 flush(`01:45:06.170 flush rawSn=94 regen=true`) → `01:45:06.171 WARN [DatasetExport] export blocked — deident report open rawSn=94` → `async export failed rawSn=94 cause=CustomException`. **`ls_dataset_export` 행 미생성**(6행 그대로, FAILED 행도 없음), metric `outcome=deident_blocked` +1, **mock-server 인바운드 0건**(통지 함께 보류). [정적] `DatasetExportService.java:115-145`(진입부 단일 게이트) + `AsyncDatasetExportRunner.java:128-149`. **근거 정확** |
| TC-EXPORT-017 | PASS | [정적] `:184-259` — `finalizeUnlessUnderDeidentReport` 가 false → `deidentBlocked=true` → `:252-259` `purgeThisRunVersionDir` + `outcome=DEIDENT_BLOCKED` + `throw CustomException`(통지 보류). `:291-310` 삭제 범위가 `resolveVideoRoot`→`resolveUnder(videoRoot,"v"+version)`→`verifyRealPathUnder` 3중 가드 통과 시에만, 실패하면 **아무것도 삭제 안 함**(fail-secure). `DatasetExportTxService.java:224-239` 가 RAW 잠금 하 재판정 + PENDING 행 **삭제**(FAILED 아님). IT `DatasetExportDeidentReportGateIT.export_중_신고가_접수되면_산출물이_기록되지_않고_통지도_나가지_않는다` / `export_중_신고시_이번_실행이_만든_버전폴더가_삭제되고_이전_버전은_보존된다` 커버(baseline 실패 0건). ⚠ 실동작 재현 미실시 — 로컬 export 가 **5ms 내 완료**(실측 01:42:26.054→.072)라 쓰기 도중 신고 커밋을 끼워 넣을 창이 없음. **근거 정확** |
| TC-EXPORT-018 | PASS | [실동작] **성공 경로 순서 실증** — rawSn=101: `01:42:26.072 export succeeded version=3` → `01:42:26.076 ControlNotifyService - TASK_MODIFIED sent rawSn=101` → mock `16:42:26,075 notify-updated accepted job_id=101` (export 마감 후 통지, 동기 순서). rawSn=94 resolve 경로: `01:45:40.854 partial export version=7` → `.857 TASK_COMPLETED sent`. **반증(실패 시 보류) 3회 관측** — ①NO_INPUT(906) ②FAILED/base 거부(94 v8) ③DEIDENT_BLOCKED(94) 모두 `notify withheld` + mock 인바운드 0건. [정적] `AsyncDatasetExportRunner.java:67-76`(doExport true 일 때만 `DatasetExportCompletedEvent`) + `:134-143`(`outcome.notifiable()` 단일 판정, `null` fail-closed). **근거 정확** |
| TC-EXPORT-019 | PASS | [실동작] 디바운스 flush → `01:42:26.054 async re-export(+notify) starting rawSn=101 forceRegenerate=true` → export 성공 → 콜백 실행(TASK_MODIFIED). 실패 시(906 NO_INPUT) 콜백 미실행. [정적] `:90-106` — `boolean succeeded = doExport(...); if (succeeded && afterExport != null) afterExport.run();`, 콜백 타입이 `Runnable` 이라 통지 패키지 컴파일 의존 없음(import 목록에 controlnotify 0건). **근거 정확** |
| TC-EXPORT-040 | PASS | [실동작] `02:11:20.121 [DatasetExportRecovery] retriggered failed exports count=1 rawSns=[94] maxAttempts=3` → `runApprovalAsync` 재산출 v12 PARTIAL → 통지 재개(409 자기치유 → `notify-updated` 202). 앵커 행 v11 의 `RTY_NMTM 0→1`·`RTY_DT=02:11:20` 클레임 확인. **재시도 유예도 실증** — 앵커 생성 3분 후 tick 은 `no retryable failed export`(cutoff=now-10m), 18분 후 tick 에서 클레임. 상세 §5 |
| TC-EXPORT-041 | PASS | [실동작] `POST /v1/deident-reports/36/resolve` → `01:45:40.843 resolved-manually` → `01:45:40.846 [DatasetExportBridge] deident report resolved rawSn=94 — re-triggering withheld export/notify` → `async approval export starting`(=force=true 전량 재생성) → `partial export version=7` → `TASK_COMPLETED sent` → mock `notify-completed accepted job_id=94` 202. `DE_IDENT_YN` 이 `'F'→'Y'` 복원됨. **팬아웃 0건**(같은 tick 에 다른 rawSn export 로그 없음 — 범위는 그 영상 하나). [정적] `DatasetExportBridge.java:81-87`. **근거 정확** |
| TC-EXPORT-042 | PASS | [정적] 현 환경은 `CONTROL_NOTIFY_ENABLED=true`(컨테이너 env 실측)라 토글 off 실동작 불가 → 정적+테스트 판정. 보장 지점 3곳 확인 — ①`TaskModifiedAccumulateListener.java:27-36` **`@Component` 만, 조건부 어노테이션 0건**(javadoc: "구 구현은 통지 토글 종속 리스너 안에 뒀다가 dev/stg/prd 에서 재생성이 전혀 일어나지 않았다") ②`ControlNotifyDebouncer.java:85` `@Component`(무조건) + `:334-357 send()` 가 `notifyService==null` 여부와 무관하게 `exportRunner.runReExportThenNotify(rawSn, true, notifyCallback)` 호출(콜백만 null) ③반대로 `ControlNotifyEventListener.java:27` 만 `@ConditionalOnProperty(control-notify.enabled)`. 테스트 `ControlNotifyDebouncerTest.재export_트리거는_control_notify_토글과_무관하게_동작한다` + `HIGH-E_통지_토글_off여도_승인후_수정_재생성_윈도우는_export를_트리거한다` 커버. ⚠ **카탈로그 근거가 엉뚱한 토글을 가리키고 있었음 → 정정**(§3, 1차 E-ISSUE-82 이월분) |
| TC-EXPORT-043 | PASS | [실동작] **전 버전 보존 확인** — rawSn=94: `v1 v2 v4 v6 v7 v9 v10` 디렉터리 전부 잔존(v3·v5·v8·v11 은 base 거부/수동 FAILED 라 디렉터리 자체가 생성된 적 없음). rawSn=101: `v1 v2 v3`(+ 타 에이전트가 만든 v4·v5) 전부 잔존, 각 40 파일. **v1 의 라벨 좌표가 그 시점 값 그대로 동결**(bbox v1=`[50,50,150,150]` / v2=`[55,55,…]` / v3=`[60,60,…]`) — 과거 버전 덮어쓰기 0건. 삭제 잡 미구현 확인(retention 관련 스케줄러/서비스 0건). [정적] `:45-46` javadoc + `:175-176` TODO. **근거 정확** |

### 집계

| 판정 | 건수 | 케이스 |
|------|:--:|------|
| PASS | 23 | 001~019, 040~043 |
| FAIL | 0 | — |
| PARTIAL | 0 | — |
| BLOCKED / N/A / 확인필요 | 0 | — |
| **합계** | **23** | 실측 행 수 `grep -cE '^\| *~*TC-'`(243~269행) = 23, 폐기 케이스 0건 |

> ⚠ **"전건 PASS" 를 그대로 신뢰하지 말 것** — 케이스 단위 기대결과는 모두 충족했으나, **케이스가 다루지 않는 인접 계약에서 결함 3건**(§4)이 나왔다. 특히 E-ISSUE-81 은 "export 실패 시 통지 보류"(TC-EXPORT-016/018/019 PASS)의 **부작용**으로, 보류된 통지의 변경 프레임 목록이 복구되지 않는 문제다.

---

## 2. 이전 회차 이슈 대조

| 이전 이슈 | 케이스 | 이번 회차 판정 |
|---|---|---|
| **D-ISSUE-61 (1차, CRITICAL)** — export 가 예외 없이 실패로 마감돼도 TASK_COMPLETED/MODIFIED 발송 | TC-EXPORT-018/019 | **✅ 해소 확정(실동작 3회 실증).** `DatasetExportOutcome.notifiable()` 단일 판정 + `null` fail-closed(`AsyncDatasetExportRunner.java:134-143`). 예외 없는 실패 2경로(**NO_INPUT** rawSn=906 / **FAILED-base거부** rawSn=94 v8)와 예외 경로(**DEIDENT_BLOCKED** rawSn=94)에서 모두 `notify withheld` + mock-server 인바운드 0건. D-part3 의 rawSn=147 실험과 **독립적으로 재확인**됨 |
| **E-ISSUE-81 (1차, HIGH)** — PARTIAL export 가 통지는 되는데 `V_COMPLETED_VIDEO` 에서 배제 | TC-EXPORT-005/019 | **✅ 해소 확정(라이브 DB).** `pg_get_viewdef` 실측: LATERAL 조인 조건이 `ex.export_stts_cd = ANY (ARRAY['SUCCEEDED','PARTIAL'])`(V160). rawSn=94 의 최신 산출이 **PARTIAL(v7→v9→v10)** 인데 `v_completed_video` 가 `export_path_nm=/app/storage/raw/autolabel-test/94, frame_cnt=10` 로 정상 노출. 뷰에 `export_stts_cd` 컬럼도 신설돼 관제가 부분산출을 식별 가능. 멱등 baseline(`DatasetExportTxService.java:144-154`)·통지 판정(`DatasetExportOutcome.PARTIAL notifiable=true`)·뷰 **3곳이 PARTIAL 로 일치** |
| **E-ISSUE-82 (1차, LOW)** — TC-EXPORT-042/040 근거 file:line 드리프트 | TC-EXPORT-042/040 | **부분 해소 → 이번 회차 잔여분 정정.** TC-EXPORT-040 근거(`DatasetExportTxService.java:247-261`)는 이미 정정돼 있었고 실측(`claimForRetry` javadoc 247-256 + 메서드 257-261)과 일치. **TC-EXPORT-042 는 미정정 상태로 남아 있었음** — `DatasetExportBridge.java:29-30 (자체 토글만 참조)` 는 `authoring.dataset-export.enabled`(=TC-EXPORT-013 근거)이지 control-notify 토글과의 무관성을 보장하는 코드가 아니다. **이번 회차에 카탈로그 직접 정정**(§3) → **E-ISSUE-82** 로 재등록(추적용) |
| **E-ISSUE-83 (1차, MEDIUM)** — 신고/실패로 export 가 보류되면 디바운스 윈도우가 이미 `complete` 처리돼 변경 프레임 목록이 유실 | TC-EXPORT-016/019 | **❌ 미해소 이월(실동작 재확인).** `ControlNotifyDebouncer.java:303-306` 가 여전히 `send(window); store.complete(window.acmlSn());` 이고, `send()` 의 `runReExportThenNotify` 는 `@Async` 라 즉시 반환한다. 실측: `01:45:06.170 flush rawSn=94 regen=true frames=448=[META_UPDATED]` → `.171 export blocked` → 이후 `ls_mon_noti_acml` 의 rawSn=94 행 **0건**(재클레임 없이 소멸). resolve 후 나간 통지는 M1 경로의 **영상 단위 `TASK_COMPLETED`** 라 `frames=448` 변경 목록을 담지 않음 → **E-ISSUE-81** 로 재등록 |

---

## 3. 카탈로그 정정 (담당 라인범위 내 직접 Edit) — 2건

| # | 대상 | 정정 내용 |
|:-:|------|----------|
| 1 | **TC-EXPORT-042** 근거 | `DatasetExportBridge.java:29-30 (자체 토글만 참조)` → **`TaskModifiedAccumulateListener.java:27-36; ControlNotifyDebouncer.java:44-55,85,334-357; ControlNotifyEventListener.java:27 (토글 종속은 여기만)`**. 기대결과에 보장 지점 3곳(축적 리스너 무조건 활성 / 디바운서 `send()` 무조건 위임 / 통지 리스너만 토글 종속)을 명시. **사유**: 기존 근거는 `authoring.dataset-export.enabled` 토글(TC-EXPORT-013 근거와 동일 라인)이라, 근거를 따라가면 **다른 토글을 검사하게 되어 잘못된 PASS/FAIL 을 유발**한다(1차 E-ISSUE-82 미해소분) |
| 2 | **TC-EXPORT-003** 기대결과·근거 | ⚠ **유일한 발행 트리거 `DatasetReExportEvent` 가 휴면**(발행처 0건)이라 실동작 도달 불가라는 사실을 기대결과에 명시. 근거에 `DatasetExportBridge.java:50-61(휴면 사유)` 와 **PARTIAL baseline 의 실제 보장 지점 `DatasetExportTxService.java:144-154`** 추가. **사유**: 케이스가 "PARTIAL 도 멱등 baseline 에 포함"을 단언하는데 기존 근거 2곳 어디에도 그 IN 필터가 없었고, 검증자가 실동작으로 재현하려다 "미구현"으로 오판할 수 있다 |

> 프로덕션 코드는 수정하지 않았다. `## 변경 이력` 표(파일 5~18행)는 담당 라인범위(243~269) 밖이라 **병렬 에이전트 충돌 방지를 위해 손대지 않았다** — 병합 시 위 2건을 회차 행에 반영 필요.

---

## 4. 신규/이월 이슈

### [E-ISSUE-81] TC-EXPORT-016 / TC-EXPORT-019 — export 가 보류·실패해도 디바운스 윈도우가 `complete` 처리돼 **그 수정 통지의 변경 프레임 목록이 영구 유실**된다 (1차 E-ISSUE-83 이월, 실동작 재확인)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ControlNotifyDebouncer.claimAndSendIsolated` 의 자체 계약 — *"실패한 윈도우는 `complete` 를 호출하지 않아 저장소에 FLUSHING 으로 남는다 … 통지가 **소실되지 않고 지연**된다"*(`ControlNotifyDebouncer.java:284-286`). 또 `CLAUDE.md` 는 TASK_MODIFIED 페이로드에 **변경 프레임 목록**(`SRC_SN` + 변경 종류)을 담도록 규정한다. 즉 export 가 보류되면 그 윈도우의 축적분도 함께 보류됐다가 재개돼야 한다.
- **현재 동작(이슈 내용)**: 재생성 동반 윈도우(`exportRegenerated=true`)의 실제 산출·통지는 `@Async` 러너에 위임되고 **즉시 반환**하므로, 그 뒤의 export 차단/실패가 `send()` 의 예외로 관측되지 않는다. 결과적으로 윈도우가 무조건 `complete` 로 마감돼 축적분이 사라진다.
  ```java
  // ControlNotifyDebouncer.java:344-351  (send)
  if (window.exportRegenerated()) {
      Runnable notifyCallback = notifyService == null ? null
              : () -> notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, true);
      exportRunner.runReExportThenNotify(rawSn, true, notifyCallback);   // @Async — 즉시 반환
      return;
  }
  // ControlNotifyDebouncer.java:303-306  (claimAndSendIsolated)
  send(window);
  store.complete(window.acmlSn());   // ← export/통지 결과와 무관하게 마감
  ```
  **실측(2026-08-04, rawSn=94)**:
  ```
  01:45:06.170 [ControlNotifyDebounce] flush rawSn=94 regen=true frames=448=[META_UPDATED]
  01:45:06.171 WARN [DatasetExport] export blocked — deident report open rawSn=94
  01:45:06.171 WARN [DatasetExport] async export failed rawSn=94 cause=CustomException
  ```
  ```sql
  -- flush 직후: 해당 rawSn 의 축적 윈도우가 상태 불문 0건 (FLUSHING 잔존 아님 = 재클레임 경로 없음)
  SELECT * FROM ls_mon_noti_acml WHERE raw_sn = 94;   -- (0 rows)
  ```
  신고 해소(01:45:40) 시 나간 통지는 M1 재트리거(`runApprovalAsync`)의 **영상 단위 `TASK_COMPLETED`**(mock: `notify-completed accepted job_id=94`)라 `frames=448` 변경 목록을 담지 않는다. **base 거부(FAILED) 경로도 동일** — `01:48:46.358 export base rejected` 직후 rawSn=94 윈도우 0건.
- **재현/확인 경로**:
  ```bash
  # APPROVED 영상에 신고 접수 → 신고 구간에 승인 후 수정 → 60초 뒤 flush
  curl -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"reason":"probe"}'
  curl -X PUT  localhost:18081/api/v1/frames/{srcSn}/description   -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"srcSn":{srcSn},"description":"x"}'
  # 로그: flush(regen=true, frames=…) → export blocked → async export failed
  ```
  ```sql
  SELECT noti_acml_sn, stts_cd, chg_dtl_cn FROM ls_mon_noti_acml WHERE raw_sn = :rawSn;  -- 0 rows = 축적분 소실
  ```
- **영향**: 기능/데이터정합 — 관제가 받는 수정 통지에서 **어느 프레임이 바뀌었는지**가 빠진다(관제는 전량 재조회로만 복구 가능). 영상 단위 정합 자체는 M1 재통지·회수기 재산출로 회복되므로 CRITICAL 은 아니나, 디바운서가 문서화한 "지연될 뿐 소실 없음" 보증이 **재생성 경로에서만 깨져 동작이 자기 문서와 다르다**. 통지 토글이 꺼진 형상(dev/stg/prd 기본)에서는 `notifyCallback` 이 애초에 null 이라 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 결과를 상위에 돌려주도록 하고(콜백 `onFailure` 또는 `CompletableFuture`), **비통지 종결(`notifiable()==false`)이면 윈도우를 `complete` 하지 않고 FLUSHING 으로 재개방**해 임차 만료 후 재클레임되게 한다 — 이미 존재하는 lease 복구 machinery 를 그대로 재사용하므로 신규 메커니즘이 필요 없다. 또는 ⓑ 재생성 윈도우는 러너 스레드에서 `complete` 를 호출하도록 소유권을 넘긴다. 최소 조치로 ⓒ 차단·실패 시 WARN 에 유실된 `frameChanges` 요약을 남겨 감사 가능하게 한다. D-part3 의 **D-ISSUE-41**(NO_INPUT 보류분 복구 경로 단절)과 **같은 축**("러너의 성공/실패를 상위가 알 수 있게 한다")이므로 함께 처리하는 것이 좋다. **구현은 하지 않는다.**

### [E-ISSUE-82] TC-EXPORT-042 — 카탈로그 근거(file:line)가 **다른 토글**을 가리킨다 (1차 E-ISSUE-82 미해소분, 이번 회차 정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 그 단언을 실제로 보장하는 코드 위치를 가리켜야 한다(다음 회차 재검증·수정 작업의 진입점).
- **현재 동작(이슈 내용)**: TC-EXPORT-042("재export 트리거는 control-notify 토글과 무관")의 근거가 `DatasetExportBridge.java:29-30 (자체 토글만 참조)` 였는데, 이 라인은 `authoring.dataset-export.enabled` 토글(= TC-EXPORT-013 의 근거와 **동일 라인**)이며 `authoring.control-notify.enabled` 와의 무관성을 보장하는 코드가 아니다. 실제 보장 지점은 ①`TaskModifiedAccumulateListener.java:27-36`(조건부 어노테이션 0건 = 항상 활성) ②`ControlNotifyDebouncer.java:44-55`(HIGH-E javadoc "이 빈은 control-notify.enabled 로 게이팅하지 않는다") + `:85`(`@Component`) + `:334-357`(`send()` 가 `notifyService` null 여부와 무관하게 `runReExportThenNotify` 호출) ③`ControlNotifyEventListener.java:27`(반대로 통지 리스너만 토글 종속).
- **재현/확인 경로**: `docs/test-cases/E-augment-resolution-export-meta.md` TC-EXPORT-042 행과 위 파일들을 대조. 1차 ISSUES.md `E-ISSUE-82` 에 동일 지적이 있었으나 카탈로그에 반영되지 않은 채 남아 있었다.
- **영향**: 기능 영향 없음. 카탈로그 자체의 정합성 결함 — 근거를 따라가면 다른 토글을 검사하게 되어 **잘못된 PASS/FAIL 판정**을 유발할 수 있다(3차 검증 착수 시 실제로 혼선 발생).
- **수정 방향(제안)**: **이번 회차에 카탈로그를 직접 정정 완료**(§3-1). 추가 조치는 `## 변경 이력` 표에 회차 행 반영뿐. **프로덕션 코드 변경 불필요.**

### [E-ISSUE-83] TC-EXPORT-006 / TC-EXPORT-004 — 실패한 export 가 남긴 `v{n}` 디렉터리가 정리되지 않는데, 뷰에 **버전 식별자가 없어** 관제가 최신 유효 버전을 판별할 수 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` — *"`EXPORT_PATH_NM` 은 **영상 루트**를 가리킨다 — 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 비교·복구가 가능하다"*. 즉 관제는 영상 루트 아래에서 **어느 `v{n}` 이 이번 통지가 가리키는 산출물인지**를 판별할 수 있어야 한다. 또 실패로 마감된 산출은 관제가 집을 수 있는 상태로 남아서는 안 된다.
- **현재 동작(이슈 내용)**: 두 사실이 겹친다.
  1. **뷰에 버전 컬럼이 없다.** `v_completed_video` 실측 컬럼 41개 중 export 관련은 `export_path_nm`(영상 루트)·`frame_cnt`·`export_stts_cd` 뿐이고 **`export_ver_no` 가 없다**. 통지 페이로드(6필드 평면: `job_id`·`event_type_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count`)에도 버전이 없다. 따라서 관제는 디렉터리명을 스캔해 `max(v)` 를 고르는 수밖에 없다.
  2. **실패 종결이 `v{n}` 디렉터리를 남긴다.** `DatasetExportWriter.java:83-89` 가 프레임 루프 **이전에** `Files.createDirectories(dir)` 를 무조건 수행한다.
     ```java
     Path dir = pathResolver.resolve(rawSn, rawFilePathNm, kind, version);
     createExportDir(dir, rawSn, kind, version);          // ← 프레임 0건이어도 생성됨
     …
     for (FrameContext frameCtx : frames) { … }
     ```
     그런데 `DatasetExportService.java:209-214`(산출 0건 → FAILED)와 `:238-246`(쓰기 중 예외 → FAILED) 어느 쪽도 `purgeThisRunVersionDir` 를 호출하지 않는다 — 삭제는 **신고 차단 경로(`:252-259`)에만** 배선돼 있다. retention 정리 잡도 없다(TC-EXPORT-043 확정 정책).
  - 결과: 최신 SUCCEEDED/PARTIAL 이 `v10` 인데 그 뒤 실패한 산출이 **비어 있거나 반쯤 찬 `v11`** 을 남기면, `max(v)` 로 고르는 관제는 **깨진 폴더를 최신 학습데이터로 픽업**한다. 뷰의 `frame_cnt`(=v10 기준)와 디스크 실체(v11)가 어긋나도 관제가 검출할 단서가 없다.
  - ⚠ 이번 실동작에서 남은 FAILED(rawSn=94 v8·v11)는 **base 거부** 유형이라 `pathResolver.resolve` 단계에서 막혀 디렉터리가 생성되지 않았다(`ls -d …/94/v*` → v1 v2 v4 v6 v7 v9 v10, v8·v11 없음). 즉 **base 거부는 안전하고, 산출 0건·쓰기 중 예외 두 유형만 해당**한다.
- **재현/확인 경로**:
  ```sql
  -- 1) 뷰에 버전 식별자가 없음
  \d+ v_completed_video      -- export_path_nm / frame_cnt / export_stts_cd 만, export_ver_no 없음
  -- 2) 산출 0건 유도: APPROVED 영상의 전 프레임 이미지 경로를 실재하지 않는 값으로 바꾼 뒤 재승인
  UPDATE ls_data_src SET src_file_path_nm = '/app/storage/raw/nope.jpg',
                         de_idntf_src_file_path_nm = '/app/storage/deidentified/nope.jpg'
   WHERE raw_sn = :rawSn;
  ```
  ```bash
  # 로그에 "nothing produced — marked FAILED rawSn=… version=N" 후
  docker exec klid-backend ls -R {영상루트}/v{N}     # orgnl/ deid/ 빈 디렉터리 잔존
  ```
- **영향**: 데이터정합 — 관제/데이터마트가 **깨진(빈·부분) 버전 폴더를 최신 학습데이터로 픽업**할 수 있다(사업 요구 *"데이터마트 학습데이터셋의 라벨링 정보 동기화"* 미충족). 부수적으로 빈 디렉터리가 무한 누적된다(retention 미구현 확정 정책이라 자연 정리되지 않음). 보안 영향은 없다(신고 차단 경로는 이미 purge 배선됨).
- **수정 방향(제안)**: ⓐ **뷰에 `EXPORT_VER_NO` 를 노출**(`V_COMPLETED_VIDEO` LATERAL 조인에 컬럼 추가)해 관제가 `max(v)` 추정 대신 명시값을 쓰게 한다 — 관제 계약 변경이므로 협의 필요하나 가장 근본적이다. ⓑ 실패 종결(`totalWritten==0`·쓰기 중 예외) 분기에서도 `purgeThisRunVersionDir(rawSn, prep.rawFilePathNm(), inserted.version())` 를 호출해 이번 실행이 만든 폴더만 정리한다(신고 차단 경로와 동일한 3중 경로가드를 그대로 재사용 — 신규 메커니즘 0). ⓒ 최소 조치로 `DatasetExportWriter` 가 **첫 프레임 쓰기 직전에** 디렉터리를 만들도록 지연시켜 산출 0건이면 디렉터리 자체가 생기지 않게 한다. **ⓐ+ⓑ 조합 권장. 구현은 하지 않는다.**

---

## 5. TC-EXPORT-040 — 실패 export 회수기 실동작 (PASS)

**시나리오(S8)**: rawSn=94 의 `raw_file_path_nm` 을 다시 허용 루트 밖으로 돌려 재export → **최신 export 가 FAILED 인 앵커**(`export_ver_no=11`, `REG_DT=01:52:46`) 생성 → 즉시 경로 원복 → 주기 잡(`interval-sec=900`, `retry-delay-minutes=10`) 관측.

**① 재시도 유예가 실제로 걸린다** — 앵커 생성 3분 34초 뒤 tick 은 후보로 집지 않았다.
```
01:56:20.122 DEBUG DatasetExportFailureRecoverer - [DatasetExportRecovery] no retryable failed export
```
(`findRetryableFailedAnchors` 의 `COALESCE(e.RTY_DT, e.REG_DT) < :cutoff`, cutoff=now-10m → 01:46:20 > 01:52:46 이라 제외)

**② 유예 경과 후 원자 클레임 + 재산출 + 통지 재개** — 다음 tick(18분 34초 경과)에서:
```
02:11:20.121 WARN  DatasetExportFailureRecoverer - [DatasetExportRecovery] retriggered failed exports count=1 rawSns=[94] maxAttempts=3
02:11:20.121 INFO  AsyncDatasetExportRunner      - [DatasetExport] async approval export starting rawSn=94
02:11:20.131 INFO  DatasetExportWriter           - frames written rawSn=94 kind=ORIGINAL      version=12 written=5 skipped=1
02:11:20.131 INFO  DatasetExportWriter           - frames written rawSn=94 kind=DEIDENTIFIED version=12 written=5 skipped=1
02:11:20.133 WARN  DatasetExportService          - partial export rawSn=94 version=12 written=10 skipped=2
02:11:20.137 INFO  ControlNotifyService          - completed conflicted -> resend as updated rawSn=94
02:11:20.140 INFO  ControlNotifyService          - TASK_COMPLETED sent rawSn=94 actual=TASK_MODIFIED
```
mock-server 인바운드: `POST /api/data-set/v2/jobs/94/notify-completed → 409` → **자기치유** → `notify-updated → 202 Accepted`.

**③ 시도 이력이 앵커 행에 기록됐다**(상한이 실제로 걸림 — H7① 계약):
```
 export_ver_no | export_stts_cd |           export_path_nm           | frame_cnt | rty_nmtm |           rty_dt
---------------+----------------+------------------------------------+-----------+----------+----------------------------
            12 | PARTIAL        | /app/storage/raw/autolabel-test/94 |        10 |        0 |
            11 | FAILED         |                                    |           |        1 | 2026-08-04 02:11:20.120095   ← 클레임됨
            10 | PARTIAL        | /app/storage/raw/autolabel-test/94 |        10 |        0 |
```
재산출이 새 행(v12)을 만들어 앵커가 바뀌어도 누적치는 "마지막 성공 이후 전 행 `SUM(RTY_NMTM)`" 로 계산되므로 리셋되지 않는다(`LsDatasetExportRepository.java:135-142`).

**④ 신고 구간 제외** — `DatasetExportFailureRecoverer.java:141-144` 가 **클레임 이전에** `deidentReportGate.isUnderDeidentReport(rawSn)` 로 건너뛴다(시도 상한 미소진). 이번 회차엔 신고 구간 FAILED 앵커가 없어 실동작 미유도 — IT `DatasetExportDeidentReportGateIT.회수기가_신고_구간에_재시도_상한을_소진하지_않는다` 로 커버(baseline 실패 0건).

**⑤ 2노드 중복 방지** — `claimForRetry` 가 `RTY_NMTM +1, RTY_DT=now WHERE EXPORT_SN=? AND EXPORT_STTS_CD='FAILED' AND RTY_NMTM < ? AND (RTY_DT IS NULL OR RTY_DT < ?)` 단일 조건부 UPDATE(`LsDatasetExportRepository.java:172-185`). 단일 노드 환경이라 실동작 미검증 — IT `DatasetExportFailureRecoveryIT.Quartz_클러스터링이_꺼져있어도_같은_tick_중복_실행시_한_번만_재산출된다` 로 커버.

**판정: PASS** — 근거(`DatasetExportFailureRecoverer.java:119-170`; `DatasetExportTxService.java:247-261`) **정확**.

> ⚠ 부수 관측(E-ISSUE-81 보강): 회수 재개 통지도 `runApprovalAsync` 경로라 **영상 단위 TASK_COMPLETED**(409 자기치유로 updated 전환)다. 원래 보류됐던 수정 통지의 **변경 프레임 목록은 회수 경로로도 복원되지 않는다**.

---

## 6. self-fill 관점

본 파트(E-6 Export) 범위에는 **외부 연동 응답으로 채워야 할 값이 없다** — 산출 JSON 의 값은 전부 DB(라벨·프레임·동결 메타)에서 오고, 유일한 외부 의존은 비식별 영상 경로다. 그 경로도 문자열 조합·추측이 아니라 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 그대로 읽는다(`DatasetExportTxService.java:133-135` `findLatestSuccessByDataRawSn(...).map(LsDeidentProcLog::getDeIdntfFilePathNm).orElse(null)`, 미상이면 **null 로 fail-secure**). 실측으로도 rawSn=101 의 비식별 사본이 mock KPST 가 정한 `clip-9101-mask.mp4`(=`{stem}-mask{ext}`)로 기록·복사돼 있었다(우리가 `deidentified.mp4` 로 조합하지 않음). **self-fill 결함 0건.**

관제 통지도 self-fill 이 아니다 — mock-server 인바운드에 `notify-updated accepted job_id=101 images=10 jsons=10`, `notify-completed accepted job_id=94` 가 실제로 도달했고 값이 DB 실측치와 일치했다.

---

## 7. 검증 중 생성/변경한 데이터 (이후 회차 참고 — 정리하지 않음)

| 대상 | 값 | 성격 |
|------|----|------|
| `ls_data_lbl` | rawSn=101 / srcSn=468 / labelSn=729 의 BBOX 좌표 `[55,55]-[205,205]` → **`[60,60]-[210,210]`** | 재export 유도용 라벨 수정(정상 API). `labelVersion` 2→3 |
| `ls_dataset_export` | rawSn=101 **v3**(SUCCEEDED) / rawSn=94 **v7·v9·v10·v12**(PARTIAL)·**v8·v11**(FAILED, path=null, v11 은 `RTY_NMTM=1` 클레임됨) | 본 검증이 만든 산출 이력 |
| 파일 | `/app/storage/raw/seed/101/v3/` · `/app/storage/raw/autolabel-test/94/v7,v9,v10,v12/` | 산출물(전 버전 보존 정책상 유지) |
| `ls_deident_report` | deident_report_sn=**36**(rawSn=94) — OPEN → **RESOLVED** 완결, `DE_IDENT_YN='Y'` 복원 | 신고 게이트 검증용 |
| `ls_data_src` | rawSn=94 / srcSn=448 의 `frm_expln` = `"E-part5 recoverer anchor"` | 재export 트리거용 |
| `ls_data_raw` | rawSn=94 `raw_file_path_nm` 을 `/etc/evil/clip.mp4` 로 2회 임시 변경 후 **원복 완료**(현재 `/app/storage/raw/autolabel-test/62e1aea1-0b7e-40d6-b806-121a370fe746.mp4`) | base 가드 검증용 — **원복 확인함** |
| 파일 mtime | `/app/storage/raw/autolabel-test/94/deid/62e1aea1-…-mask.mp4` `touch` | 신고 resolve 게이트(외부 솔루션 제자리 교체) 모사, 1차와 동일 |
| `ls_raw_data_status` | rawSn=94 검수 사이클 2회 재순회(APPROVED→PENDING→IN_REVIEW→APPROVED), 현재 **APPROVED** | 재승인 경로 검증용 — 최종 상태 복원됨 |

> ⚠ **rawSn=94 는 유령 프레임(`src_sn=453`, `frm_no=99`, 실재하지 않는 이미지 경로)을 계속 보유**한다(1차에서 생성). 이 영상의 향후 export 는 **항상 PARTIAL** 이다 — 정상 SUCCEEDED 시나리오가 필요하면 rawSn=101 을 쓸 것.
> ⚠ rawSn=101 은 본 파트 외에도 **다른 에이전트가 동시에 수정 중**이었다(검증 중 v4·v5 가 타 경로에서 생성됨). 이 영상의 export 이력을 단독 근거로 쓸 때 주의.

---

# E-part6 — 3차 검증 (2026-08-03/04)

검증자: 담당 에이전트(E-part6) · 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-7(18건, TC-EXPORT-020~037) + §E-8(20건, TC-META-001~020, TC-META-009 폐기 제외 실질 19건) = 38건(폐기 제외 실질 37건)

## 사용한 실증 데이터

- 공용 시나리오 rawSn=101(`_raw/pipeline-drive.md`) — APPROVED, export v1 SUCCEEDED(2026-08-03 15:09 기준)
- 본 검증 중 추가 실동작 유발: `PUT /v1/frames/468/privacy-meta`(프레임 개인정보 수동값), `PUT /v1/videos/101/environment-meta`(촬영환경 수동값) — 재export를 트리거해 v1→v3(privacy)→v5(environment)까지 순차 생성됨. 코드/설정/프로덕션 데이터는 수정하지 않았고, 검증 목적의 실제 API 호출만 수행(다른 병행 검증 에이전트의 활동으로 v2/v4도 함께 생성된 것을 로그로 확인 — rawSn=101이 여러 클러스터가 공유하는 시나리오 영상이라 정상적인 동시 사용).
- `docker exec klid-backend cat .../v{n}/{orgnl|deid}/0000.json` 실측 + `docker logs klid-backend`(ControlNotifyDebouncer/AsyncDatasetExportRunner/EnvironmentMetaService 로그) + `SELECT ... FROM ls_dataset_export/ls_dataset_video_meta` DB 조회.

## ★ 이번 회차 핵심 발견 — anonymity/pseudonymity/privacy_included 정책이 1차(2026-08-01) 이후 반전됨

커밋 `0d290c4e`(2026-08-03, "feat(privacy): 영상 단위 개인정보 메타 화면 + export 개인정보 3필드 정책 반전")가 `NiaJsonBuilder`/`VideoMetaMapper`의 개인정보 3필드 판정을 신설 `ExportPrivacyPolicy` 단일 판정기로 교체했다. 1차(2026-08-01) 검증 시점 코드(`ec5181a0`, 08-01)는 "ORIGINAL=N/DEIDENTIFIED=Y 고정, 수동 override 금지"였고 카탈로그 TC-EXPORT-022/023/030은 그 기대값을 그대로 담고 있었다. 3차 시점(08-03/04) 코드는 **"ORIGINAL=판정 안 함(null) / DEIDENTIFIED=수동값 우선, 미입력 시 기본상수(Y/N/N)"**로 정반대다. `ExportPrivacyPolicy.java` 클래스 주석에 정책 반전 경위와 "되돌리지 말 것" 경고가 명시돼 있다.

**카탈로그 3건(022/023/030)을 이 발견에 맞춰 직접 정정했다**(§10 예외 조항 — 카탈로그 정합 결함은 담당 라인범위 내 직접 수정 지시에 따름). 정정 내용은 아래 이슈 섹션 및 파일 자체(§E-7)에 반영됨.

## E-7. Export JSON 포맷 — 18건

| ID | 판정 | 근거 확인 | 근거 요약 |
|---|---|---|---|
| TC-EXPORT-020 | PASS | [실동작]+[정적] | rawSn=101 v1~v5 전 버전 JSON 최상위 키 순서 `info,dataset,licences,video,event,image,annotations,categories,type` 일관 확인. `NiaAnnotationDoc.java:24-37` `@JsonInclude(ALWAYS)`+`@JsonPropertyOrder` 코드 일치(라인 드리프트 없음, 1차와 동일 위치) |
| TC-EXPORT-021 | PASS | [실동작] | 동결 event 없는 rawSn=101 전 프레임 JSON `"event": null` 확인(키 always present) |
| TC-EXPORT-022 | **[카탈로그 정정]** PASS(신규 기대값 기준) | [실동작]+[정적] | **1차 기대값(수동 override 금지, ORIGINAL=N/DEID=Y 고정)은 2026-08-03 `0d290c4e`로 폐기됨.** 실측: srcSn=468에 `PUT /v1/frames/468/privacy-meta {"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}` → 재export v3 → deid `image.anonymity="N"`(수동값 그대로 반영), orgnl `image.anonymity=null`(판정 자체 없음, "N 고정"도 아님). `ExportPrivacyPolicy.resolve()`(원천=null 반환, DEID=manualYn 우선) 코드와 정확 일치. 카탈로그 기대결과·근거라인 정정 완료(신규: ORIGINAL=null/DEID=수동값 우선) |
| TC-EXPORT-023 | **[카탈로그 정정]** PASS(신규 기대값 기준) | [실동작]+[정적] | 동일 PUT으로 pseudonymity/privacyIncluded도 v3 deid에서 Y/Y로 정확 반영, orgnl은 null/null. 구 "파생 폴백(PRVC_TYPE_CD/PRVC_YN 파생)" 문구는 그 파생 로직 자체가 `ExportPrivacyPolicy` 도입으로 소멸해 폐기 — 미입력 시 고정 기본상수(N/N)로 대체됨. 카탈로그 정정 완료 |
| TC-EXPORT-024 | PASS | [정적] | `NiaJsonBuilder.buildDataset`(133-138, 1차 대비 +1라인 드리프트)이 `kind==DEIDENTIFIED`이고 `deidVideoPath=null`이면 `path=null`→dataset.src_path/name 모두 null. `VideoMetaMapper.toVideo`(61-62) `kindVideoPath=deidVideoPath(null)`→basename null도 동일. rawSn=101은 deid경로가 항상 존재해 라이브 재현은 못했으나(1차와 동일 사유) 결정론적 null 전파 로직은 명확 |
| TC-EXPORT-025 | PASS | [정적] | `buildAnnotations`(179-202, 1차 대비 라인 변동 없음) `try{...}catch(CustomException){skipped++}` 구조 그대로 — 코드 변경 없음(1차 이후 이 메서드 미수정 확인, git log) |
| TC-EXPORT-026 | PASS | [실동작]+[정적] | rawSn=101 JSON 실측: `type/format/location/pixel/cctv_height` 등 미보유 필드가 `null`로 키 유지. `NiaVideo.java:12`/`NiaImage.java:9` `@JsonInclude(ALWAYS)` 라인 정확 일치(드리프트 없음) |
| TC-EXPORT-027 | PASS(라인 드리프트 정정) | [실동작]+[정적] | JSON `"vd_description":null` 키 존재 확인. `NiaVideo.java:46`(일치)이나 `VideoMetaMapper.java:101`은 **드리프트** — 실제 vd_description 라인은 `114`(privacy 정책 반전으로 주석 추가되며 이동). 카탈로그 근거라인 101→114 정정 완료 |
| TC-EXPORT-028 | PASS | [실동작]+[정적] | rawSn=101 초기(수동 미입력) v1 export `weather/time_of_day/season` 전부 null 확인(SHT_DT 자동파생 없음). PUT으로 수동값(비/NGT/WINTER) 저장 후 재export(v5)에서 정확 반영(TC-META-017과 동일 실증 공유). `VideoMetaMapper.java:55-57` `firstNonBlank(raw,meta)` 코드 위치 라인만 44-57 범위로 소폭 조정(1차 46-59 대비, 근소한 드리프트라 유지) |
| TC-EXPORT-029 | PASS(라인 드리프트 정정) | [정적] | `VideoMetaMapper.firstNonBlank`(158-165, 1차 이후 privacy 코드 추가로 이동) 모두 blank→null 정규화. 카탈로그 근거라인 144-152→157-165 정정 완료 |
| TC-EXPORT-030 | **[카탈로그 정정]** PASS(신규 기대값 기준) | [실동작]+[정적] | 1차 기대값 "N/Y 고정 오버라이드"는 폐기. 실측: rawSn=101 video 블록 — orgnl `video.anonymity=null`(전 버전 일관), deid `video.anonymity="Y"`(영상 단위 수동값 `LS_DATA_RAW.ANONY_INCL_YN` 미설정 상태의 기본상수). 영상 단위 수동값을 별도로 설정하지 않아 기본값만 확인했으나(프레임 단위 수동값과는 별개 축, `VideoMetaMapper.java:74-79` 코드로 override 로직 확인), null/Y 분기 자체는 실측·정적 모두 일치. 카탈로그 정정 완료 |
| TC-EXPORT-031 | PASS | [정적] | `NiaJsonBuilder.prepareContext`(89-92, 1차 88-91 대비 근소 이동) `if(meta==null) throw CustomException(INVALID_INPUT,"영상 메타가 null 입니다.")` 일치 |
| TC-EXPORT-032 | PASS | [실동작]+[정적] | rawSn=101 전 버전 JSON `info.version="1.3"`, `type="instances"` 확인. `NiaJsonBuilder.java:38`(FORMAT_VERSION)/`:40`(TYPE_INSTANCES) 코드 존재 확인(1차 37/39/96 대비 1라인 드리프트, 경미해 미정정) |
| TC-EXPORT-033 | PASS | [실동작]+[정적] | rawSn=101 image.file_name="0000.jpg"~"0009.jpg"(FRM_NO 4자리 zero-pad). `ExportFileNaming.imageFileName(long)` 단일 지점 사용(`NiaJsonBuilder.java:146`) 확인, 규칙과 실측 파일명 일치 |
| TC-EXPORT-034 | PASS | [실동작] | image.frame_num=0(rawSn=101 srcSn=468, 단일 프레임만 확인 — 1차는 0/30/60/90/120으로 다프레임 실증했으나 이번 시나리오는 VDO_FRM_NO가 0으로 고정된 소규모 fixture). 파일명(0000.jpg=FRM_NO)과 frame_num(0=VDO_FRM_NO)이 이 프레임에서는 우연히 같은 값이라 분리 실증은 1차 결과에 의존(코드 미변경 확인) |
| TC-EXPORT-035 | PASS(정적, 코드 미변경) | [정적] | `DatasetExportService.java`의 파생 ORIGINAL 스킵 로직은 08-01 이후 미수정(git log 확인) — 1차 rawSn=18 실측 결과를 그대로 승계 |
| TC-EXPORT-036 | PASS | [실동작]+[정적] | rawSn=101: orgnl `dataset.src_path`=`/app/storage/raw/seed/clip-9101.mp4`, deid `dataset.src_path`=`/app/storage/raw/seed/101/deid/clip-9101-mask.mp4` — kind별 실제 경로 상이 확인. `NiaJsonBuilder.java:133-138` 일치 |
| TC-EXPORT-037 | PASS | [정적] | `NiaJsonBuilder.java:175`(1차 174 대비 1라인 이동) `src.getFrmExpln()`이 `NiaImage` 마지막 인자로 전달됨. 라이브 JSON `description:null`(미입력 상태와 일치) |

## E-8. 촬영환경 메타 — 20건(TC-META-009는 2026-07-30 폐기, 판정 대상 제외)

| ID | 판정 | 근거 확인 | 근거 요약 |
|---|---|---|---|
| TC-META-001 | PASS | [실동작] | rawSn=101 PUT 저장(weather=비/timeOfDay=NGT/season=WINTER) 후 GET → `weatherSource/timeOfDaySource/seasonSource` 전부 `"MANUAL"` 확인 |
| TC-META-002 | PASS | [실동작] | 수동값 저장 **전** GET(rawSn=101) → `weather:null, timeOfDay:"DAY", season:"SUMMER", timeOfDaySource:"DERIVED", seasonSource:"DERIVED"` — SHT_DT 기반 파생 프리필 확인 |
| TC-META-003 | PASS | [실동작] | PUT 3필드 전송 → 200 응답 정상 반영(dirty checking, 코드 08-01 이전부터 미변경) |
| TC-META-004 | PASS | [정적, 코드 08-01 이전 미변경] | `validate()`가 null/blank를 "수동값 없음"으로 정규화 → `changeShootingEnvironment`가 3필드만 UPDATE. 이번 회차는 3필드 모두 값 있는 PUT만 실동작 확인, 부분 null 케이스는 1차 실측 승계 |
| TC-META-005 | PASS(라인 드리프트 정정) | [정적] | `ShootingEnvironmentVocabulary.WEATHERS`(5종) 존재 라인이 23→**27**로 이동(설명 주석 추가). 카탈로그 근거라인 정정 완료. 값 자체(맑음/흐림/비/눈/안개)는 변경 없음 |
| TC-META-006 | PASS(라인 드리프트 정정) | [정적] | TIME_OF_DAYS/SEASONS 라인이 26-32→**29-36**으로 이동. 카탈로그 근거라인 정정 완료 |
| TC-META-007 | PASS | [정적] | 허용값 전부 20자 이내 + `@Size(max=MAX_LENGTH=20)`(`EnvironmentMetaUpdateRequest.java:28,32,36`) 이중 방어 확인(라인 변경 없음) |
| TC-META-008 | PASS | [실동작] | rawSn=101(APPROVED) PUT 후 백엔드 로그 `[EnvironmentMeta] re-freeze triggered rawSn=101` 확인(01:44:29). `RVW_CMPL_DT` 승계 확인 — 재동결 후에도 `ls_dataset_video_meta.rvw_cmpl_dt`가 원래 승인시각(2026-08-04 00:09:47.868)으로 유지, 편집 시각(01:44)으로 덮이지 않음을 DB 재조회로 직접 확인 |
| TC-META-009 | N/A | — | 2026-07-30 폐기 케이스(대체: TC-META-017). 판정 대상 제외 |
| TC-META-010 | PASS | [정적, 코드 미변경] | `isReviewApproved` 가드가 PENDING 영상엔 재동결 미수행. 이번 회차 미검수 영상으로 재현은 안 했으나(rawSn=101이 이미 APPROVED) 코드 경로 자체는 1차 실측·현재 코드 동일 |
| TC-META-011 | PASS | [정적] | `reFreezeApprovedSnapshot`(157-167, 1차 156-167 대비 거의 동일) `if(active.isEmpty()){warn; return;}` fail-safe 확인 |
| TC-META-012 | PASS | [정적] | `update()`(101-129) 내 `videoRepository.flush()`(117) → `videoMetaRepository.acquireRawLock(rawSn)`(118) → `isReviewApproved(rawSn)`(120) 순서 코드 그대로. 동시 요청 재현은 타이밍 조작 필요해 미수행(1차와 동일 한계) |
| TC-META-013 | PASS(코드 미변경, 실측은 1차 승계) | [정적] | `accessGuard.verifyRawAccess`가 `get()`(67)/`update()`(102) 진입부에 그대로 존재 — IDOR 방어 로직 미변경 확인 |
| TC-META-014 | PASS(코드 미변경) | [정적] | `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` + SecurityConfig 채널 격리 — 미변경 |
| TC-META-015 | PASS | [정적] | `findRaw`(187-190) `orElseThrow(NOT_FOUND)` — 미변경 |
| TC-META-016 | PASS | [실동작 정황] | `validate()`(174-185) `log.warn("...field={}", field)` — 필드명만 로깅, 입력원문 미포함 코드 확인(1차 실동작 로그와 동일 패턴, 이번 회차 재실행 없음) |
| TC-META-017 | PASS | [실동작] | PUT(weather=비/NGT/WINTER, 01:44:29) → 디바운스 flush 로그 `[ControlNotifyDebounce] flush rawSn=101 regen=true frames=468=[META_UPDATED]`(01:43:36 — **주의**: 이 flush는 직전 프레임 privacy-meta 변경분, 촬영환경 변경은 다음 flush 주기에 포함) → 실제로는 두 번째 flush에서 export v5 SUCCEEDED(48초 후 확인) → v5 JSON `video.weather="비", time_of_day="NGT", season="WINTER"` 정확 반영 — end-to-end 완전 실증 |
| TC-META-018 | PASS | [정적]+[실동작 정황] | `DatasetVideoMetaSnapshotService.java:126-128` `nullIfBlank(dayNgtCd/sesnCd/wthrNm)` — SHT_DT 파생 없이 수동값만 동결. rawSn=101 최초 승인(00:09:47) 시점 스냅샷에 weather/day_ngt/sesn 전부 null이었음을(PUT 이전 v1 export가 전부 null이었던 것으로) 간접 확인 |
| TC-META-019 | PASS | [실동작] | `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets`(REVIEWER 토큰) → 200 `{"targetCount":0}`(레거시 파생 동결행 없음, 1차와 동일) |
| TC-META-020 | PASS(부분 실동작) | [실동작] | WORKER 토큰으로 GET 호출 시 **403** 확인(REVIEWER 전용 방어 실증, 이번 회차 신규 확인). POST 실행 자체는 대상 0건 환경이라 실행 결과는 1차 실측(`{"corrected":0,"remaining":0,"completed":true}`)에 의존 |

## 카탈로그 정정 내역 (이번 회차, 담당 라인범위 내 직접 Edit)

1. **TC-EXPORT-022**: 기대결과 전면 정정(anonymity 수동 override 금지 → `ExportPrivacyPolicy` 단일판정, ORIGINAL=null/DEID=수동값 우선). 근거라인 `NiaJsonBuilder.java:150-153` → `ExportPrivacyPolicy.java:64-80; NiaJsonBuilder.java:158`
2. **TC-EXPORT-023**: 기대결과 정정("파생 폴백" 문구 폐기 → 기본상수 폴백). 근거라인 `156-159,204-206` → `ExportPrivacyPolicy.java:64-80; NiaJsonBuilder.java:159-160`
3. **TC-EXPORT-030**: 기대결과 정정("N/Y 고정 오버라이드" → ORIGINAL=null/DEID=영상단위 수동값 우선). 근거라인 `VideoMetaMapper.java:65` → `ExportPrivacyPolicy.java:64-80; VideoMetaMapper.java:74-79`
4. **TC-EXPORT-027**: 근거라인 드리프트 정정 `VideoMetaMapper.java:101` → `:114`
5. **TC-EXPORT-029**: 근거라인 드리프트 정정 `VideoMetaMapper.java:144-152` → `:157-165`
6. **TC-META-005**: 근거라인 드리프트 정정 `ShootingEnvironmentVocabulary.java:23` → `:27`
7. **TC-META-006**: 근거라인 드리프트 정정 `ShootingEnvironmentVocabulary.java:26-32` → `:29-36`

## 신규 이슈

### [E-ISSUE-101] TC-EXPORT-022/023/030 — 카탈로그가 2026-08-03 폐기된 개인정보 3필드 정책을 검증 대상으로 담고 있었다 (정정 완료)
- **심각도**: HIGH (카탈로그 정합 — 다음 회차 거짓 FAIL 위험이었음, 이번 회차에 정정 완료)
- **기대 동작(기대효과)**: 카탈로그는 현재 확정 정책만 검증 대상으로 담아야 한다. 폐기된 정책을 남겨두면 다음 검증자가 "수동값이 override됐다 → 결함"으로 오판할 위험이 있다.
- **현재 동작(이슈 내용)**: 카탈로그(2026-07-30 최신화 기준)는 "ORIGINAL=N/DEIDENTIFIED=Y 고정, 수동 override 금지"를 기대결과로 담고 있었으나, 커밋 `0d290c4e`(2026-08-03, "export 개인정보 3필드 정책 반전")가 이를 정반대로 바꿨다. 신설 `ExportPrivacyPolicy` 클래스(`backend/src/main/java/kr/co/cudo/authoring/dataset/export/ExportPrivacyPolicy.java`)의 javadoc에 "★ 확정 정책(2026-08-03 사용자 확정 — 구 정책 전면 반전)" 표와 "폐기된 구 정책과 그 경위(되돌리지 말 것)" 절이 명시돼 있다.
  ```java
  // ExportPrivacyPolicy.resolve()
  private static String resolve(ExportKind kind, String manualYn, String deidDefault) {
      if (kind != ExportKind.DEIDENTIFIED) { return null; }             // ORIGINAL=항상 null
      return (manualYn == null || manualYn.isBlank()) ? deidDefault : manualYn.trim(); // DEID=수동값 우선
  }
  ```
- **재현/확인 경로**:
  ```bash
  curl -X PUT localhost:18081/api/v1/frames/{srcSn}/privacy-meta -H "Authorization: Bearer $WORKER" \
    -d '{"srcSn":{srcSn},"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}'
  # 재export 후 v{n}/orgnl/*.json → image.anonymity=null (판정 안 함)
  # 재export 후 v{n}/deid/*.json  → image.anonymity="N" (수동값 그대로, override 됨)
  ```
- **영향**: 기능 영향 없음(현행 동작이 최신 정본과 일치). 검증 프로세스 영향 — 카탈로그를 정정하지 않았다면 다음 회차에서 거짓 FAIL 3건 발생 위험.
- **수정 방향(제안)**: 완료됨 — TC-EXPORT-022/023/030을 이번 회차에 직접 정정(위 "카탈로그 정정 내역" 참조). `UNCERTAINTIES.md` ★ 확정 정책 절에 6번째 항목으로 "개인정보 3필드(anonymity/pseudonymity/privacy_included) = `ExportPrivacyPolicy` 단일판정, ORIGINAL 판정 안 함/DEID 수동값 우선" 등재를 권장(향후 "일관성 없다"는 재검토·되돌리기 방지 — `ExportPrivacyPolicy` 클래스 주석이 이미 이 경고를 담고 있으나 UNCERTAINTIES에도 반영하면 검증자가 더 빨리 확인 가능).

### [E-ISSUE-102] EnvironmentMetaController Swagger 설명이 실제 재export 동작과 모순된다
- **심각도**: LOW (문서 전용 결함 — 실동작에는 영향 없음, API 소비자 오인 위험)
- **기대 동작(기대효과)**: OpenAPI/Swagger 설명은 실제 서버 동작과 일치해야 한다. TC-META-008/017이 검증하는 "APPROVED 후 촬영환경 수정 = export 새 버전 전량 재생성"은 실제로 그렇게 동작한다(이번 회차 실동작으로 재확인, v5 export에 새 촬영환경 값 정확 반영).
- **현재 동작(이슈 내용)**: `EnvironmentMetaController.java:66-68`의 PUT API `@Operation` description이 다음과 같이 **정반대 사실**을 적고 있다:
  > *"검수 완료 후 수정 시 동결 스냅샷만 재동결(데이터마트 뷰에 최신값 반영)되고 관제 TASK_MODIFIED(META_UPDATED) 통지가 발행된다. **편집은 export 파일 재생성을 트리거하지 않으며**(라벨 수정과 동일 정책), export 폴더는 다음 검수 승인 시점에 전량 재산출된다."*

  하지만 실제 서비스 코드(`EnvironmentMetaService.java:120-127`)는 `TaskModifiedEvent(rawSn, null, ChangeType.META_UPDATED, actorNo, **true**)`로 `exportRegenerated=true`를 실어 발행하며, 이는 CLAUDE.md의 "★ export 재생성·동기화 정책(2026-07-27 확정)"과 정확히 일치하는 현재 정책이다. 즉 컨트롤러의 Swagger 설명이 2026-07-27 이전 폐기된 구 정책("재생성 미트리거")을 그대로 남겨둔 상태다.
- **재현/확인 경로**:
  ```bash
  # Swagger UI에서 PUT /v1/videos/{rawSn}/environment-meta 설명 확인 — "export 파일 재생성을 트리거하지 않으며" 문구
  # 실제로는 재생성됨:
  curl -X PUT localhost:18081/api/v1/videos/101/environment-meta -H "Authorization: Bearer $REV" \
    -d '{"weather":"비","timeOfDay":"NGT","season":"WINTER"}'
  # 이후 docker logs klid-backend | grep DatasetExport → "async re-export(+notify) starting rawSn=101 forceRegenerate=true" 확인됨
  ```
- **영향**: 기능 영향 없음. API 문서를 신뢰하는 외부/내부 개발자(FE, 관제 연동 담당)가 "촬영환경만 고치면 export 파일은 안 바뀐다"고 오인해 별도 재산출을 기다리거나 잘못된 가정으로 연동 코드를 짤 위험(정보 정확성 문제).
- **수정 방향(제안)**: `EnvironmentMetaController.java`의 `update()` `@Operation` description에서 "편집은 export 파일 재생성을 트리거하지 않으며... 다음 검수 승인 시점에 전량 재산출된다" 문장을 삭제하고, "검수 완료 후 수정 시 export 폴더도 새 버전(v{n+1})으로 전량 재생성되며, 재생성 성공 후 통지가 발송된다"로 교체.

## 요약

- **합계 38건**(TC-META-009 폐기 제외 실질 37건 판정 대상): **PASS 37 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 1(폐기) / 확인필요 0**
  - 단, PASS 37건 중 **3건(TC-EXPORT-022/023/030)은 카탈로그의 구 기대값 기준으로는 FAIL이었을 것을 이번 회차에 기대값 자체를 신규 정책에 맞춰 정정한 뒤의 판정**이다(위 이슈·정정 내역 참조) — 단순 "PASS 37"로만 읽으면 이 반전을 놓친다.
- **핵심 실증**:
  - **정책 반전 라이브 확인**(TC-EXPORT-022/023/030): srcSn=468에 실제 수동값(N/Y/Y)을 저장하고 재export까지 실행해 deid에는 반영·orgnl은 null임을 직접 확인 — 코드 읽기가 아니라 API 호출+파일 실측으로 반증.
  - **재export end-to-end**(TC-META-008/017): 촬영환경 PUT → 디바운스 flush → export v5 SUCCEEDED → JSON 반영까지 전 구간 실시간 확인, RVW_CMPL_DT 승계도 DB로 직접 대조.
  - **REVIEWER 전용 방어**(TC-META-020): WORKER 토큰으로 실제 403 응답 확인(1차는 코드 근거만 있었음).
- **이전 회차(1차, 2026-08-01) 이슈 해소 여부**: 1차 E-part6(당시 §E-7/E-8/E-9 통합 53건)에서는 이 범위(TC-EXPORT-020~037, TC-META-001~020)에 대해 이슈가 0건 발생했었다(전건 PASS). 이번 3차에서도 신규 FAIL/PARTIAL은 없으나, **1차 이후 코드가 반전되면서 카탈로그 자체가 낡은 상태였다** — 이는 "이전 이슈의 미해소"가 아니라 "1차 검증 이후 발생한 신규 코드 변경에 카탈로그가 못 따라간 것"이며, 이번 회차에 정정 완료했다.
- **BLOCKED/확인필요**: 없음.

---

# E 클러스터 part7 — E-9. 프레임 개인정보 메타 (TC-META-030~044, 신규 045~047)

- 회차: 2026-08-03 **3차** · 담당 범위: `docs/test-cases/E-augment-resolution-export-meta.md` **E-9 절**(구 309~336행)
- 총 15건(기존) + **신규 3건 추가**(TC-META-045/046/047) = 18건 · 폐기 0건
- 환경: `_raw/stack-bringup.md` 기준 풀스택 기동(backend/mock-server/ai-server/frontend/DB 전부 healthy, HEAD `e065da42` 재빌드 이미지). 외부 연동 4종 전부 mock-server(:9400) 실배선.
- 공용 데이터: `_raw/pipeline-drive.md` 의 rawSn=101(APPROVED, 프레임 468~477), 추가로 raw 900(`DE_IDNTF_YN='F'` 신고 구간, 프레임 429~433) · raw 906(APPROVED, 프레임 464/465) · raw 158(미검수 파생) · raw 164/165(파생) 사용.
- 인증: `POST /v1/dev/tokens` REVIEWER(1001) / WORKER(2001).
- **코드·설정·테스트 파일 수정 0건.** 카탈로그(E-9 절)만 정정·신설. 빌드/테스트 미실행.

---

## 0. ★ 이번 회차 핵심 — V163 정책 반전(커밋 `0d290c4e`)의 카탈로그 반영 여부

| 반전 축 | 확정 정책(코드·CLAUDE.md 실측) | 반전 전 카탈로그 기술 | 반영 여부 |
|---|---|---|:--:|
| ① export 개인정보 3필드 | `ORIGINAL`=**전부 null**(판정 안 함) / `DEIDENTIFIED`=**수동값 우선**(미입력 시 Y/N/N) | TC-META-034 "anonymity 는 export 미덮음 — `ExportKind` 파생 유지" | **미반영 → 정정함** |
| ② 프레임 GET 프리필 원천 | `ExportPrivacyPolicy` 비식별 **기본상수(Y/N/N)** | TC-META-030 "미저장 시 파생(`PRVC_TYPE_CD=ANONY→Y`)" | **미반영 → 정정함** |
| ③ 입도 2축(video=`LS_DATA_RAW` V163 / image=`LS_DATA_SRC` V130) | 두 값이 달라도 모순 아님 | 카탈로그에 개념 자체 부재 | **미반영 → TC-META-047 신설** |
| ④ 신고 구간 PUT 412(단건·벌크) | `LabelAccessGuard#requireNotUnderDeidentReport`, 인가 이후 평가, GET 미차단 | 카탈로그에 케이스 부재 | **미반영 → TC-META-045/046 신설** |
| ⑤ 파생영상 **영상 축** 판정 계승(`LsDataRaw.copyPrivacyMetaFrom`) | 생성 시점 1회 스냅샷 | TC-META-044 는 **프레임 축만** 기술 | 부분 반영 → 영상 축은 E-ISSUE-126(영상 축 절 신설)로 이월 |

> `CLAUDE.md` 는 이미 최신이다(349~370행: "★export 개인정보 3필드 정책 — 비식별 산출물만 판정한다(2026-08-03 확정)", 파생 계승, 신고 리셋 2축, 게이트 차단범위 ⑧에 개인정보 메타 PUT 412 명시). **문서 드리프트는 CLAUDE.md 가 아니라 테스트케이스 카탈로그 쪽에만 있었다.**

---

## 1. 판정 결과

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| TC-META-030 | PASS | [실동작] `GET /v1/frames/468/privacy-meta` → `{"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}`. DB `ls_data_src(468)` 3필드 전부 NULL, 영상 `raw 101.prvc_type_cd='PRVC'` — **구 파생 규칙이라면 anonymity=N** 이 나와야 하므로 상수 프리필임이 반증적으로 확정 | 기대결과 정정(파생→기본상수) |
| TC-META-031 | PASS | [실동작] PUT `{N,Y,Y}` → 200 + DB 반영, PUT `{null,null,null}` → 200 + DB 3필드 NULL + 응답은 기본상수 Y/N/N | |
| TC-META-032 | PASS | [실동작] `"true"` → 400 `anonymity 는 Y 또는 N 이어야 합니다.` / **`"Y\n"` 도 400**(`\A[YN]\z`) | 표기 정정(`^[YN]$`→`\A[YN]\z`) |
| TC-META-033 | PASS | [실동작] path 464 vs body 465 → 400 `path 의 srcSn 과 body 의 srcSn 이 다릅니다.` | |
| TC-META-034 | PASS | [실동작] **정책 반전 실증** — raw101 v4 산출물: `orgnl/0000.json` video·image 3필드 전부 `null`; `deid/0000.json` image(468)=`N/Y/Y`(저장한 수동값 그대로), image(469)=`Y/N/N`(미입력 → 기본상수). 구 기대결과("수동값이 export 를 안 덮음")는 **반증됨** | 기대결과 전면 정정 |
| TC-META-035 | PASS | [정적+실동작] `updateBulk`(124-166) = `findAllById` 1회 → rawSn distinct 인가·게이트 1회 → `saveAll` 1회. 2건 벌크 200, 로그 `bulk-updated count=2 rawSns=1` | 쿼리 카운트 계측은 미수행 |
| TC-META-036 | PASS | [실동작] `items=[464, 9999999]` → 404 `프레임을 찾을 수 없습니다.` | |
| TC-META-037 | PASS | [실동작] WORKER(2001) `items=[468(본인), 464(타 영상)]` → 403. `items=[9999999, 464]` → **404 先** (순서 보존) | |
| TC-META-038 | PASS | [실동작] 위 404 실패 직후 DB 재조회 시 srcSn=464 값 변경 없음(NULL 유지) — 전체 롤백 확인 | |
| TC-META-039 | PASS | [실동작] 벌크 PUT(468 포함, APPROVED raw101) → 60초 디바운스 flush → 로그 `AsyncDatasetExportRunner ... re-export(+notify) starting rawSn=101 forceRegenerate=true` → `export succeeded version=5` → **그 뒤** `ControlNotifyService TASK_MODIFIED sent rawSn=101 ... reExport=true` (export 선행 → 통지 순서 실측) | |
| TC-META-040 | PASS | [실동작] `{"items":[]}` → 400 `items 는 1건 이상이어야 합니다.` / 5001건 → 400 `items 는 5000건 이하여야 합니다.` | 상한 5000 명시 |
| TC-META-041 | PASS | [실동작] 토큰 없음 → 401(프레임·영상 축 모두). WORKER 미배정 프레임 GET/PUT → 403. **신고 구간(raw900) 프레임을 미배정 WORKER 가 PUT → 412 가 아니라 403**(상태 오라클 미발생, CWE-209) | |
| TC-META-042 | PASS | [실동작] backend 로그 실측: `[FramePrivacyMeta] updated srcSn=468 rawSn=101`, `[FramePrivacyMeta] bulk-updated count=2 rawSns=1` — Y/N 판단값 미출력 | |
| TC-META-043 | PASS | [실동작] 단건 PUT(468) → v4/v5 새 버전 폴더 전량 재생성 후 통지. **미검수 영상(raw158, 상태행 없음) 프레임 567 단건 PUT → export 0건·통지 0건**(반대 케이스도 실증) | |
| TC-META-044 | PASS | [실동작] raw165(부모 906) 프레임 601/602 = `N/Y/Y` — 부모 프레임 464/465 에 저장돼 있던 수동값을 그대로 복사. 부모 미입력이던 raw153/158 프레임은 전부 NULL. 생성 후 부모 정정은 미전파(스냅샷) | |
| TC-META-045 (신규) | PASS | [실동작] 신고 구간 raw900 프레임 429 단건 PUT → **412** `비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.` (REVIEWER 토큰 — 역할 무관 확인) | 신설 |
| TC-META-046 (신규) | PASS | [실동작] 벌크 `[468(정상), 429(신고)]` → **412 전체 거부** + 468 값 변경 없음. 같은 프레임 **GET 은 200** | 신설 |
| TC-META-047 (신규) | PASS | [실동작] raw101 video 축=`N/Y/Y`, 프레임 469 미입력 → v4 `deid/0001.json` = video `N/Y/Y` / image `Y/N/N` (입도 차이 그대로 산출). ⚠ 역방향 조합은 E-ISSUE-122 참조 | 신설 |

**집계**: PASS 18 · FAIL 0 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0 (폐기 0 — 분모 18)

> 제품 동작은 전건 확정 정책과 일치했다. 다만 **카탈로그가 반전 이전 정책을 들고 있었고**(TC-META-030·034), 반전과 함께 들어온 신규 동작 3종이 카탈로그에 없었다 → 아래 이슈로 기록 + 담당 라인범위 내 정정 완료.

---

## 2. 이슈

### [E-ISSUE-121] TC-META-034 / TC-META-030 — 카탈로그가 2026-08-03 반전 **이전** 정책을 기대값으로 들고 있었다 (정정 완료)
- **심각도**: HIGH (카탈로그 정합 — 다음 전수 검증에서 정상 동작이 "결함"으로 재발견될 축)
- **기대 동작(기대효과)**: 카탈로그 기대결과는 확정 정책과 일치해야 한다. 어긋나면 ①검증자가 정상 동작을 FAIL 로 올리고 ②그 "수정"이 폐기된 정책을 되살린다(이 저장소의 반복 사고 패턴).
- **현재 동작(이슈 내용)**: 정정 전 TC-META-034 = *"화면·기록용만 — export `image.anonymity` 는 `ExportKind` 파생 유지"*, TC-META-030 = *"미저장 시 파생(`PRVC_TYPE_CD=ANONY→Y` 등)"*. 실제는 정확히 반대·소멸:
  - `ExportPrivacyPolicy.resolve()` (92-97) — `kind != DEIDENTIFIED` → `null`, 아니면 `manualYn` 우선 + 기본상수 폴백
  - `FramePrivacyMetaService.toEffective()` (190-196) — `firstNonBlank(src.getAnonyInclYn(), ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY)`; 서비스가 **영상 행을 더 이상 읽지 않는다**(`VideoRepository` 의존 제거)
- **재현/확인 경로**: (실행함) `PUT /v1/frames/468/privacy-meta {N,Y,Y}` → 디바운스 flush 후 `docker exec klid-backend cat /app/storage/raw/seed/101/v4/deid/0000.json` → `image = N/Y/Y`, `v4/orgnl/0000.json` → 3필드 `null`.
- **영향**: 문서 정합. 방치 시 "수동값이 export 를 덮는다"를 결함으로 오판 → 억제 로직 부활 위험.
- **수정 방향(제안)**: **이번 회차에 E-9 절에서 직접 정정 완료**(TC-META-030·034 기대결과 재작성, 절 머리말에 확정 정책 4줄 추가). 남은 조치는 파일 상단 `## 변경 이력` 표에 3차 행 추가인데, 이는 본 파트 담당 라인범위(309행~) 밖이라 **병합 담당이 반영**해야 한다. (같은 회차 part6 이 TC-EXPORT-022/023 을 동일 방향으로 이미 정정했음을 확인 — 두 정정은 서로 정합한다.)

### [E-ISSUE-122] TC-META-047 — 영상 축 미입력 시 기본상수(`privacy_included=N`)가 프레임 수동값(`Y`)과 **모순**되어 영상 단위 과소 신고가 나간다
- **심각도**: MEDIUM (데이터 정합 / 개인정보 과소 선언 — CWE-359 인접)
- **기대 동작(기대효과)**: 커밋 `0d290c4e` 와 `CLAUDE.md`(358행)가 명시한 불변식 — *"`image="Y"` / `video="N"` 은 정책이 정당화한 방향의 **역방향**이라 논리적으로 성립할 수 없는 조합이며 실질은 개인정보 잔존의 **과소 신고**"*. 그래서 파생영상에 대해 `LsDataRaw.copyPrivacyMetaFrom` 을 도입했다.
- **현재 동작(이슈 내용)**: 그 불변식은 **파생 계승 경로에서만** 닫혔고, 일반 영상에서는 열려 있다. 영상 축을 입력하지 않으면 `ExportPrivacyPolicy` 가 기본상수(`privacy_included=N`, `anonymity=Y`)를 넣기 때문에, 프레임 축에 `Y` 를 선언해도 video 블록은 "개인정보 없음"으로 나간다.
  ```
  # v5/deid/0000.json (raw101, video 축 수동값 삭제 · 프레임 468 = N/Y/Y)
  video: {'anonymity': 'Y', 'pseudonymity': 'N', 'privacy_included': 'N'}   ← 기본상수
  image: {'anonymity': 'N', 'pseudonymity': 'Y', 'privacy_included': 'Y'}   ← 사람이 선언한 사실
  ```
  같은 조합이 **파생에서도 재발**한다: raw165(부모 906) — 프레임 601/602 는 부모 프레임값 `N/Y/Y` 를 계승했으나 부모 영상 축이 NULL 이라 영상 축도 NULL → 산출 시 video 는 다시 기본상수 N.
- **재현/확인 경로**:
  ```bash
  # 영상 축은 비우고 프레임 축만 Y 선언
  curl -X PUT .../v1/videos/101/privacy-meta -d '{"anonymity":null,"pseudonymity":null,"privacyIncluded":null}'
  curl -X PUT .../v1/frames/468/privacy-meta -d '{"srcSn":468,"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}'
  # 60초 디바운스 후
  docker exec klid-backend cat /app/storage/raw/seed/101/v5/deid/0000.json | jq '{video:.video.privacy_included, image:.image.privacy_included}'
  # → {"video":"N","image":"Y"}
  ```
- **영향**: 관제/데이터마트가 영상 단위 1행을 UPSERT 하므로, 프레임 단위로 "개인정보 잔존"이 선언된 영상이 **영상 단위로는 '없음'** 으로 집계된다. 정책이 스스로 "성립 불가"로 규정한 조합이 상시 발생 가능하다.
- **수정 방향(제안)**: 셋 중 택1 — ⓐ 영상 축 미입력일 때 기본상수 대신 **프레임 축 수동값의 OR 집계**(어느 프레임이든 `privacy_included=Y` 면 video 도 Y)로 폴백 ⓑ 프레임 축 저장 시 영상 축이 미입력이면 **화면에서 영상 축 입력을 요구**(FE 게이트) ⓒ 정책상 허용으로 확정한다면 `CLAUDE.md`·`ExportPrivacyPolicy` 주석의 "성립 불가능한 조합" 문장을 "파생 계승 한정"으로 좁혀 기술한다. **구현은 하지 않는다** — 어느 쪽이든 사용자 확정 필요.

### [E-ISSUE-123] TC-META-042 — 프레임 축 개인정보 선언 **변경**에는 행 단위 감사가 없다 (영상 축·신고 리셋과 비대칭)
- **심각도**: MEDIUM (OWASP A09 — 감사 부재)
- **기대 동작(기대효과)**: 같은 라운드가 세운 기준 — *"PII 표기를 되돌리는 행위이므로 **행 단위 감사**가 필요하다. 로그만으로는 부족하다"*(`VideoPrivacyMetaService.auditPrivacyMetaUpdate` javadoc, `DeidentReportService` 5-1 주석). 그 기준대로 ①영상 축 PUT → `LS_TASK_EVENT_LOG(PRIVACY_META_UPDATE)` ②신고에 의한 프레임 축 리셋 → `LS_DATA_LBL_HSTRY` 프레임당 1행 ③신고에 의한 영상 축 리셋 → `LS_TASK_EVENT_LOG(PRIVACY_META_RESET)` 이 남는다.
- **현재 동작(이슈 내용)**: **사람이 프레임 축 값을 바꾸는 경로만 행 단위 이력이 없다.** `FramePrivacyMetaService.applyAndNotify`(170-183)·`updateBulk`(124-166) 는 `log.info` 한 줄뿐이고 이력 테이블에 쓰지 않는다.
  ```java
  src.updatePrivacyMeta(anonymity, pseudonymity, privacyIncluded);
  srcRepository.save(src);
  log.info("[FramePrivacyMeta] updated srcSn={} rawSn={}", src.getSrcSn(), rawSn);   // ← 이게 전부
  ```
  실측: raw101 에 프레임 PUT 을 여러 번 했으나 `ls_task_event_log`(raw_data_id=101)에는 영상 축 `PRIVACY_META_UPDATE` 1행만 존재, `ls_data_lbl_hstry` 에도 대응 행 없음.
- **재현/확인 경로**: `PUT /v1/frames/468/privacy-meta` 후 `select * from ls_task_event_log where raw_data_id=101;` / `select * from ls_data_lbl_hstry where src_sn=468 order by 1 desc limit 5;` → 변경 이력 없음.
- **영향**: "누가 언제 이 프레임을 '개인정보 없음'으로 선언했는가"를 사후 추적할 수 없다. 리셋(자동)은 추적되는데 선언(수동)은 추적 안 되는 비대칭이라 감사 목적 자체가 반쪽이다.
- **수정 방향(제안)**: 프레임 축 PUT/벌크에서 `LsDataLblHstry.recordPrivacyMetaResetEvent` 와 같은 축의 "변경" 이벤트(라벨 델타 0건, `V139` 뷰 필터로 관제 미노출)를 프레임당 1행 남긴다. 판단값(Y/N)은 담지 않고 actor·시각·changed 여부만(영상 축과 동일 기준). 벌크는 행 수가 커질 수 있으므로 `saveAll` 배치.

### [E-ISSUE-124] TC-META-030 — 프레임 응답에 **출처(MANUAL/DERIVED)가 없어** 프리필 상수가 사람의 판정으로 승격될 수 있다 (self-fill 축)
- **심각도**: MEDIUM (§1-3 self-fill 금지 원칙 — 외부/사람 입력 없이 상수가 산출물에 사실처럼 실림)
- **기대 동작(기대효과)**: 조회 프리필은 "아직 판정 안 함"을 뜻하고, export 에 사실로 실리는 값은 사람이 고른 값이어야 한다. 영상 축은 이 위험을 인지해 응답에 `anonymitySource`/`pseudonymitySource`/`privacyIncludedSource`(`MANUAL`|`DERIVED`)를 실어 FE 가 구분할 수 있게 했다.
- **현재 동작(이슈 내용)**: 프레임 축 응답은 값 3개뿐이다 — `{"srcSn":468,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}`. FE 는 이 `Y/N/N` 이 **저장값인지 상수 프리필인지 구분할 수단이 없고**, 폼을 그대로 되돌려 보내면 상수가 `MANUAL` 로 굳어 `deid/*.json` 의 `image` 블록에 사실처럼 실린다(반전 이후 프레임 수동값이 export 를 덮으므로 위험도가 반전 전보다 커졌다). `FramePrivacyMetaResponse` 에 source 필드 없음 — 커밋 `0d290c4e` 자신이 "미해소 — 프레임 패널의 항목별 출처(MANUAL/DERIVED) 뱃지 부재, 응답 계약 변경 필요"로 기재.
- **재현/확인 경로**: `GET /v1/frames/468/privacy-meta` 응답(위)과 `GET /v1/videos/101/privacy-meta` 응답(`...Source` 3필드 포함) 대조.
- **영향**: 화면을 열고 저장만 해도 전 프레임이 "익명=Y, 개인정보=N" 으로 확정 선언된다(과소 신고). BE 는 전송값의 출처를 알 수 없어 막지 못한다.
- **수정 방향(제안)**: `FramePrivacyMetaResponse` 에 영상 축과 동일한 3개 source 필드 추가(응답 추가는 하위호환) + FE 가 `DERIVED` 항목은 null 로 전송. 근본 차단이 필요하면 요청에 출처 축을 추가하는 계약 변경이 필요하며 이는 영상 축과 함께 결정할 사안.

### [E-ISSUE-125] 문서/주석 드리프트 — 폐기된 구 정책을 참조하는 서술 2건
- **심각도**: LOW
- **기대 동작(기대효과)**: Swagger·코드 주석이 확정 정책과 같은 사실을 말해야 한다(이 저장소의 "주석이 정책 갱신에 뒤처짐" 반복 패턴).
- **현재 동작(이슈 내용)**:
  1. `FramePrivacyMetaController.java:57-58,74-75` Swagger 설명이 여전히 *"없으면 **파생값**(프리필)을 반환", "수동값이 삭제되어 **파생값으로 폴백**"* 이라고 기술 — 실제 폴백 원천은 파생이 아니라 `ExportPrivacyPolicy` 비식별 기본상수다(클래스 javadoc 은 이미 정정돼 있어 **같은 파일 안에서 서로 다른 말**을 한다).
  2. `AugmentExtractPersist.java:104-107` 주석 *"두 컬럼에 같은 값을 넣으면 … export orgnl 벌이 `anonymity="N"` 으로 오표기된다"* — 반전 이후 `ORIGINAL` 은 `anonymity` 를 **판정하지 않고 항상 null** 이라 이 근거는 성립하지 않는다(컬럼 분리 자체는 뷰 불변식 때문에 여전히 유효).
- **재현/확인 경로**: 위 file:line Read.
- **영향**: 후속 작업자가 "파생 폴백"을 되살리거나, orgnl anonymity 를 근거로 잘못된 결론을 낼 수 있다.
- **수정 방향(제안)**: Swagger `description` 을 "미저장 필드는 비식별 기본상수(Y/N/N) 프리필"로, 증강 주석의 근거를 "마트 뷰의 두 경로 상이 불변식"만 남기고 anonymity 문장 제거.

### [E-ISSUE-126] 카탈로그 커버리지 갭 — 영상 단위 개인정보 메타 API(V163 신규)에 테스트케이스가 **0건**
- **심각도**: MEDIUM (카탈로그 커버리지)
- **기대 동작(기대효과)**: 신설 화면·API 는 경계·오류·하위호환 케이스와 함께 카탈로그에 들어와야 한다(`CLAUDE.md` 문서 동기화 규칙).
- **현재 동작(이슈 내용)**: `GET/PUT /v1/videos/{rawSn}/privacy-meta`(`VideoPrivacyMetaController`/`VideoPrivacyMetaService`, V161·V163)는 이번 반전의 **핵심 신설물**인데 E 클러스터 어디에도 케이스가 없다(`grep -n 'videos/.*privacy-meta' docs/test-cases/` → 0건). 이번 검증에서 실동작으로 확인된 것만 해도: 프리필+`...Source` 3필드(MANUAL/DERIVED), 전체 교체 PUT, `Y/N` 화이트리스트 400(필드명만 노출), 미존재 404, WORKER IDOR 403 / 미인증 401, 신고 구간 412, APPROVED 후 수정 시 `TaskModifiedEvent(videoLevel)` → export 재생성 후 통지(로그 `frames=0 videoLevel=1 reExport=true`), `LS_TASK_EVENT_LOG(PRIVACY_META_UPDATE, rsn='영상 개인정보 선언 변경')` 감사 1행, 파생영상 계승(raw164 가 부모 raw101 의 `N/Y/Y` 를 계승), 잠금 순서(`raw 행락 → advisory`, `FOR SHARE` 금지 — `LockOrderGuardTest` 정적 가드).
- **재현/확인 경로**: 위 항목 전부 이번 회차에 실호출로 관측(본 문서 §1·§3).
- **영향**: 반전의 절반(영상 축)이 회차 검증 대상 밖에 있어, 회귀가 나도 카탈로그로는 잡히지 않는다.
- **수정 방향(제안)**: E-9 다음에 **E-10 "영상 개인정보 메타(VideoPrivacyMetaService — 영상 단위)"** 절을 신설하고 위 12축을 케이스화(ID 는 TC-META-060~ 대역 권장). 본 파트는 담당 라인범위 밖이라 신설하지 않고 제안만 한다.

### [E-ISSUE-127] E-9 전 행 — 근거 `file:line` 드리프트 (정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 정확도가 이 카탈로그의 존재 이유다.
- **현재 동작(이슈 내용)**: 15행 중 **14행**의 라인 범위가 어긋나 있었다(신고 게이트 추가·프리필 재작성으로 서비스가 대폭 이동). 주요 예: TC-META-030 `66-70`→`77-80,190-196`, TC-META-031 `73-78`→`96-103`, TC-META-033 `85-88`→`93-96`, TC-META-035 `100-137`→`124-166`, TC-META-036 `101-118`→`137-142`, TC-META-037 `119-123`→`144-150`, TC-META-038 `99`→`124`, TC-META-039 `126-133,151-156`→`153-160`, TC-META-040 `104-107`→`111-116`·`19-25`→`20-25`, TC-META-041 `66-75`→`77-80,96-103`, TC-META-043 `151-156`→`177-182`, TC-META-044 `278-289`→`284-289`·`104-115`→`109-113`. 또 `NiaJsonBuilder.java` 는 경로가 `dataset/export/**json**/NiaJsonBuilder.java` 이고 개인정보 3필드는 `150-153` 이 아니라 `158-160` 이다.
- **재현/확인 경로**: `grep -n` 로 각 심볼 위치 확인(본 문서 작성 시 전 행 수행).
- **영향**: 근거 추적 실패 → 다음 회차 재검증 비용 증가.
- **수정 방향(제안)**: **정정 완료**(E-9 절 전 행 재작성).

---

## 3. 반증(적대) 시도 기록 — 무엇을 깨보려 했나

| 반증 가설 | 방법 | 결과 |
|---|---|---|
| "프리필이 여전히 `PRVC_TYPE_CD` 파생일 것"(구 카탈로그) | `PRVC_TYPE_CD='PRVC'` 인 raw101 프레임 GET → 파생이면 `anonymity=N` | 반증됨 — `Y` 반환(상수). 구 기대값 폐기 확정 |
| "수동값은 export 를 안 덮을 것"(구 카탈로그) | 프레임 수동값 저장 → 재export 대기 → v4 `deid` JSON 대조 | 반증됨 — 수동값 그대로 실림 |
| "`ORIGINAL` 에도 값이 실릴 것" | v4/v5 `orgnl/*.json` 대조 | 반증됨 — video·image 3필드 전부 null |
| "412 게이트가 인가보다 먼저 평가돼 상태 오라클이 될 것"(CWE-209) | 미배정 WORKER + 신고 영상 프레임 PUT | 403 우선 — 오라클 없음 |
| "벌크에서 신고 프레임이 섞이면 나머지는 저장될 것" | `[정상, 신고]` 벌크 PUT 후 DB 대조 | 412 전체 거부 + 정상 프레임 무변경 |
| "GET 도 412 로 막혀 화면이 안 뜰 것" | 신고 프레임 GET | 200 (의도된 정책과 일치) |
| "미검수 영상도 export 를 재생성할 것" | raw158(상태행 없음) 프레임 PUT 후 export 테이블 | 0건 — 발행 안 함 |
| "통지가 export 보다 먼저 나갈 것" | flush 로그 시퀀스 | export succeeded → TASK_MODIFIED sent 순서 확인 |
| "`Y\n` 이 `@Pattern` 을 통과할 것"(`^$` 였다면 통과) | `"anonymity":"Y\n"` PUT | 400 — `\A[YN]\z` 로 이미 하드닝됨 |
| "파생 프레임 상속이 사후 재동기화될 것" | 부모 정정 후 기존 파생 값 확인 | 미전파(스냅샷) — 설계와 일치, 결함 아님 |
| **"video/image 모순이 실제로는 못 나올 것"** | 영상 축 비우고 프레임 축만 Y | **모순 재현됨 → E-ISSUE-122** |

---

## 4. 이전 회차 이슈 해소 여부

- 1차(2026-08-01) `ISSUES.md` 에 **E-9(TC-META-030~044) 관련 `E-ISSUE-` 블록은 0건**이다(1차 `E-result.md:1318-1332` 에서 15건 전건 PASS). → **이월 이슈 없음.**
- 다만 1차의 TC-META-034 PASS 근거(*"srcSn=296 에 `ANONY_INCL_YN='Y'` 를 저장해도 export 는 kind 파생만 실었다"*)는 **구 정책 하의 사실**이며 현재는 성립하지 않는다. 1차 결과를 이번 회차와 대조할 때 이 행은 "회귀"가 아니라 **정책 반전에 따른 기대값 교체**로 읽어야 한다.
- 2차(2026-08-02)는 F/G/H 및 targeted 만 수행해 E-9 대조 대상 없음.

## 5. 카탈로그 정정 요약 (본 파트가 수행한 편집)

| 구분 | 건수 | 대상 |
|---|:--:|---|
| 기대결과 정정(정책 반전 반영) | 2 | TC-META-030(프리필 원천) · TC-META-034(export 반영 방향) |
| 표기·상세 정정 | 5 | TC-META-032(`\A[YN]\z`) · TC-META-035/039/040/041/043/044 문구 보강 |
| 근거 `file:line` 정정 | 15행 전건 | E-ISSUE-127 |
| 절 머리말 신설 | 1 | 확정 정책 4개 축 + 영상 축 범위 밖 명시 |
| 신규 케이스 | 3 | TC-META-045(단건 412) · TC-META-046(벌크 412 + GET 200) · TC-META-047(입도 2축) |
| 폐기 | 0 | — |

> ⚠ **병합 담당 조치 필요**: 파일 상단 `## 변경 이력` 표에 3차 행 추가(정정 22 / 신규 3 / 폐기 0, "V163 개인정보 3필드 정책 반전 반영")는 본 파트 담당 라인범위(309행~) 밖이라 수행하지 않았다. 또 `## E-9` 절 케이스 수가 15→18 로 늘어 파일 머리말의 총 케이스 수(232) 및 README 집계도 함께 갱신이 필요하다.

## 6. 검증 중 남긴 데이터 변경 (원복 여부)

| 대상 | 변경 | 원복 |
|---|---|:--:|
| `ls_data_raw(101)` 개인정보 3필드 | `N/Y/Y` 저장 → 삭제 | ✅ 원복(NULL) |
| `ls_data_src(464,465)` (raw906) | `N/Y/Y` 저장 → 삭제 | ✅ 원복(NULL) |
| `ls_data_src(567)` (raw158) | `N/N/N` 저장 → 삭제 | ✅ 원복(NULL) |
| `ls_data_src(468)` (raw101) | `N/Y/Y` — **part6(export 절)이 01:42:26 에 먼저 설정**한 값과 동일. 그쪽 검증 데이터일 수 있어 임의 삭제하지 않음 | ⛔ 유지 |
| raw101 export | 정책 검증 과정에서 v3→v5 로 버전 증가(정상 동작인 재생성) | 해당 없음 |

