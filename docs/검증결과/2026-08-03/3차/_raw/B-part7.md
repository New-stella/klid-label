# B클러스터 part7 — B-15·B-16·B-17·B-18 검증 결과 (3차, 2026-08-03/04)

담당 라인범위: `docs/test-cases/B-batch-deidentify.md` 395행~파일끝(480행). 총 43건.
이슈 ID 시작: B-ISSUE-121(미사용 — 발견된 FAIL/PARTIAL 없음, 아래 참조).

## 방법
- B-15/B-17: 소스 Read/Grep + 실 baseline 테스트 결과(XML, HEAD `e065da42`, 2026-08-04 00:09 생성분) 대조. gradle 재실행 없음(지시 준수).
- B-17: 라이브 DB(`klid-postgres`, `klid_system`) information_schema 직접 쿼리로 FK 존재·개수·delete_rule 실측.
- B-18: 기동 중인 스택(backend :18081)에 실제 curl 요청 + DB 상태 대조. REVIEWER(userNo=1001) dev 토큰 발급 후 12개 필터 조합 실측.

## B-15. 배치 스텝 트랜잭션 경계 (TC-BATCH-180~190) — 11건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-180 | PASS | [정적] YoloAutolabelStep.execute (166-170) `@Transactional(controlTransactionManager, REQUIRES_NEW)` 확인. 근거 라인 155-158→166-170 드리프트, 카탈로그 정정함 |
| TC-BATCH-181 | PASS | [정적] Sam2SegmentStep.execute (146-150) 동일 애노테이션 확인 |
| TC-BATCH-182 | PASS | [정적] TrackInterpolationStep.execute (104-105) 확인, 근거 라인 거의 정확 |
| TC-BATCH-183 | PASS | [정적] VlmTimeseriesStep.execute (219-227) readOnly 미지정(쓰기 가능) 확인, runWithMarking 분기가 마킹 상태 전이. 근거 라인 205-227→219-227 드리프트, 카탈로그 정정함 |
| TC-BATCH-184 | PASS | [정적] FfmpegFrameExtractor.execute (129-130) 확인, 근거 라인 정확 |
| TC-BATCH-185 | PASS | [정적] 5개 스텝 모두 `execute`→typed 메서드 자기호출(this.run/extractByMarks), 프록시 미경유로 중첩 없음 확인 |
| TC-BATCH-186 | PASS | [정적] YoloAutolabelStep.run (179-180) · VlmTimeseriesStep.run (236-237) 별도 REQUIRES_NEW 유지 확인, 근거 정확 |
| TC-BATCH-187 | PASS | [실동작] `BatchStepTransactionBoundaryTest.selfProxyStepsMustNotAnnotateExecute` 실행 결과 PASS(baseline XML, 2 tests 0 failures). DeidentifyStep.execute 무애노테이션 확인 |
| TC-BATCH-188 | PASS | [정적] MarkingLoadStep.execute (49-58) DML 0건(조회+파싱만) 확인, 근거 정확 |
| TC-BATCH-189 | PASS | [실동작] `BatchStepTransactionBoundaryTest.everyStepExecuteHasRequiresNewBoundary` PASS. 클래스패스 스캔 결과 BatchStep 구현 7개(YOLO/SAM2/TrackInterpolation/VLM/FrameExtract/Deidentify/MarkingLoad) 확인 — `hasSizeGreaterThanOrEqualTo(6)` 단언 공허하지 않음 |
| TC-BATCH-190 | PASS | [정적] LsDataSrcRepository.bumpLabelVersionIn (295, `@Modifying`만 있고 `@Transactional` 없음) 확인, 근거 정확(282-295) |

**baseline 근거**: `build/test-results/test/TEST-kr.co.cudo.authoring.batch.pipeline.BatchStepTransactionBoundaryTest.xml` → `tests="2" failures="0" errors="0"` (2026-08-04 00:09 생성, HEAD e065da42 기준).

## B-16. 오토라벨 일괄저장 (TC-BATCH-191~196) — 6건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-191 | PASS | [정적+실동작] `AutoLabelBatchPersister.saveAll` (61-85) 프레임당 saveAll 2회(라벨→AI메타) 확인. YoloAutolabelStep.java:214/369-370, Sam2SegmentStep.java:187/244-245 호출부 확인(근거 거의 정확, ±1줄). `YoloAutolabelStepTest.한_프레임의_검출들은_라벨_saveAll_1회와_AI메타_saveAll_1회로_저장된다` PASS(45 tests 0 failures) |
| TC-BATCH-192 | PASS | [정적] saveAll 반환 리스트를 인덱스 순회하며 `label.getLblSn()/getSrcSn()`으로 AI메타 구성(77-82) 확인. PK 매칭 계약 주석(30-34) 확인 |
| TC-BATCH-193 | PASS | [정적] size 불일치 시 `ErrorCode.INTERNAL_ERROR`("자동 라벨 일괄 저장 결과 개수 불일치") 즉시 throw (73-76) 확인 |
| TC-BATCH-194 | PASS | [정적] `pending==null\|\|isEmpty()` 조기 반환 0 확인(65-67). 근거 라인 "55"는 doc comment 줄이고 실제 코드는 65-67 — 근거 드리프트(경미, 담당범위 내 정정은 생략, 아래 카탈로그 정정 로그 참조) |
| TC-BATCH-195 | PASS | [정적] `PendingLabel(label, score)` record(47-48)로 원본 신뢰도 별도 보관, AI메타 생성 시 `pending.get(i).score()` 사용(81) 확인 — 엔티티 clampScore 보정과 분리됨 |
| TC-BATCH-196 | PASS | [정적] IDENTITY 전략 + JDBC batch 미적용 한계 명시 주석(22-28) 확인. `Sam2SegmentStepTest` PASS(21 tests 0 failures) |

## B-17. LS_DATA_RAW 참조무결성 FK V146 (TC-BATCH-200~207) — 8건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-200 | PASS | [실동작] 라이브 DB `information_schema` 쿼리: `ls_data_raw` 참조 FK 총 29건(CASCADE 27 + SET NULL 2). V146 자체 신설분(child_specs 배열 카운트: 배치7·작업검수9·버전증강5·관제3·포털1·원장세션2=27, CASCADE 25/SET NULL 2)과 정확히 일치. 나머지 2건(CASCADE)은 기존 LS_EVNT_ANNO + V162 신설 LS_CLIP_SCHEDULE_QUE — 카탈로그 서술("V146 이 27개 생성")과 모순 없음(V146 신설분만 27개가 맞음) |
| TC-BATCH-201 | PASS | [정적+실동작] `LsDataRawChildFkCascadeIT.deletingRawCascadesToChildren` PASS(7 tests 0 failures, baseline). SQL 주석(10-14)에 RESTRICT 미채택 사유(ResolutionPersistService/TusUploadService 삭제 경로 보존) 명시 |
| TC-BATCH-202 | PASS | [실동작] `LsDataRawChildFkCascadeIT.원장_세션_참조는_SET_NULL로_끊기고_행_자체는_보존된다` PASS. SQL(72-74) SET NULL 대상 2건 확인 |
| TC-BATCH-203 | PASS | [정적] `view_feed_tables` 배열(77-85, 7개 테이블) + RAISE EXCEPTION 분기(112-117) 확인 |
| TC-BATCH-204 | PASS | [정적] `max_orphans CONSTANT BIGINT := 1000`(87) + 초과 시 RAISE EXCEPTION(107-110) 확인 |
| TC-BATCH-205 | PASS | [정적+실동작] V146 주석(16-25)에 MNG_CLIP_SCHEDULE_QUE·ORGNL_RAW_SN 제외 근거 확인. V162 실측: 라이브 DB에 `ls_clip_schedule_que`(개명 완료, `mng_clip_schedule_que` 테이블 0건) + `fk_ls_clip_schedule_que_raw` 존재 확인. `LsClipScheduleQueFkIT` PASS(5 tests 0 failures) |
| TC-BATCH-206 | PASS | [정적] SQL 3-패스 구조(1)고아조사(95-119) (2)정리(121-143) (3)FK생성(145-163) 확인 |
| TC-BATCH-207 | PASS | [정적+실동작] `IF NOT EXISTS (... pg_constraint ...)` 멱등 가드(157) 확인. 이미 V163까지 적용된 라이브 DB에서 정상 기동 확인(Flyway 재실행 시 skip 동작이 실제로 일어난 상태) |

## B-18. 영상 처리 현황 목록 검색·필터 (TC-VIDEO-001~018) — 18건 [전건 실동작 라이브 curl 검증]

전제: REVIEWER(userNo=1001) dev 토큰 발급, backend :18081, DB `ls_data_raw`: 전체 95건 / `orgnl_raw_sn IS NULL`(원본) 71건.

| ID | 판정 | 실동작 근거 |
|---|:--:|---|
| TC-VIDEO-001 | PASS | `GET /v1/videos?page=0&size=5` → HTTP 200, `totalElements=71` = DB `count(orgnl_raw_sn IS NULL)` 71 과 정확히 일치. 필터 미전송 시 기본 정렬로 정상 응답 |
| TC-VIDEO-002 | PASS | `?cctvNameKeyword=cctv-qa`(소문자) → HTTP 200, `total=14`. `MNG_RESOURCE_CCTV` 마스터에 매칭 없는 `vms_cctv_id`(CCTV-QA*)라도 폴백 매칭 확인, 대소문자 무시 확인 |
| TC-VIDEO-003 | PASS | `?cctvNameKeyword=107` → HTTP 200, `total=1`, `content[0].id=107`(rawSn 일치 매칭) |
| TC-VIDEO-004 | PASS | `?cctvNameKeyword=%25`(URL인코딩 `%`) → HTTP 200, `total=0`(전체 목록 아님 — 리터럴 이스케이프 확인, 71건 전체가 나왔다면 결함이었을 것) |
| TC-VIDEO-005 | PASS | 101자 검색어 → **HTTP 400** `"검색어는 100자 이하여야 합니다."` (`INVALID_INPUT`) — ★5 정책과 일치 |
| TC-VIDEO-006 | PASS | `?eventTypeCd=020002`(EV02000201의 카테고리 키) → HTTP 200, `total=51` = DB `count(evnt_type_cd='EV02000201')` 51과 정확히 일치. 근거 EventTypeService.java 라인 드리프트 발견·카탈로그 정정(157-180→175-198) |
| TC-VIDEO-007 | PASS | `?eventTypeCd=999999`(미등록) → **HTTP 200**, `total=0` — 400 아님, ★5 정책과 정확히 일치 |
| TC-VIDEO-008 | PASS | 21자 카테고리 키 → **HTTP 200**, `total=0`(400 아님) — TC-VIDEO-007과 동일 처리 확인 |
| TC-VIDEO-009 | PASS | `?eventTypeCd=020002&cctvNameKeyword=106`(rawSn=106은 evnt_type_cd='INTRUSION', 마스터 미등록) → HTTP 200, `total=0` — 미등록 EV코드 영상이 카테고리 필터에 안 잡힘 확인 |
| TC-VIDEO-010 | PASS | `?from=2026-08-01&to=2026-08-01&cctvNameKeyword=901`(sht_dt=2026-08-01 23:47:23) → HTTP 200, `total=1`, `id=901` — 당일 23:59:59.999 경계 포함 확인 |
| TC-VIDEO-011 | PASS | `?from=2026-08-05&to=2026-08-01`(역전) → **HTTP 400** `"시작일은 종료일보다 늦을 수 없습니다."` — ★5 정책과 일치 |
| TC-VIDEO-012 | PASS | `?from=abc`(형식 오류) → **HTTP 400** `"파라미터 형식이 올바르지 않습니다: from"`, 내부 경로·스택 미노출 확인 — ★5 정책과 일치 |
| TC-VIDEO-013 | PASS | `?reviewStatusCd=APPROVED&eventTypeCd=020002&size=100` → HTTP 200, `total=16`, `content.length=16`. DB 직접 카운트(`APPROVED` ∩ `evnt_type_cd='EV02000201'` ∩ `orgnl_raw_sn IS NULL`) = 16 과 정확히 일치 — 필터 조합이 전부 DB 조건으로 내려감을 실측 확인 |
| TC-VIDEO-014 | PASS | 파생영상 rawSn=78(orgnl_raw_sn=26) 등 24건(95-71) 존재 확인. `?cctvNameKeyword=78` → `total=0`(검색으로도 안 잡힘). 필터 조합(TC-013 등) 전체에서 파생 0건 혼입 확인 |
| TC-VIDEO-015 | PASS | rawSn=39(`sht_dt IS NULL`, `reg_dt`=2026-08-01 10:18:52 존재) 응답: `capturedAt=None`, `regDt=2026-08-01T10:18:52...` — REG_DT 폴백 없음 확인. 동일 rawSn을 `from/to=2026-08-01` 필터에 포함해도 `total=0`(기간 필터에도 안 잡힘) 확인 |
| TC-VIDEO-016 | PASS | DB: `ls_raw_data_status.data_stts_cd='APPROVED'` 22건 중 1건(rawSn=18)이 파생(`orgnl_raw_sn=4`). API `?reviewStatusCd=APPROVED` → `total=21`(22-1) — LEFT JOIN + 필터 조건 조합이 구 INNER JOIN과 동치임을 실측(파생 제외까지 함께 반영) |
| TC-VIDEO-017 | PASS | `?sort=reviewCompletedAt,desc`(reviewStatusCd 미지정) → **HTTP 200**, `total=71`(정상 목록, 에러 아님) — lenient 200 + 기본 정렬 폴백 확인(strict 400 아님) |
| TC-VIDEO-018 | PASS | 위 모든 날짜 미지정 요청(TC-001 등)이 전부 HTTP 200으로 정상 응답 — `$n IS NULL` 타입 추론 실패(PostgreSQL 확장 프로토콜 에러) 없이 플래그 방식이 실동작함을 간접 확인 |

## 카탈로그 정정 (담당 범위 내, Edit 적용 완료)

1. TC-BATCH-180: `YoloAutolabelStep.java:155-158` → `166-170`으로 정정(근거 드리프트, execute 애노테이션 실제 위치)
2. TC-BATCH-183: `VlmTimeseriesStep.java:205-227` → `219-227`로 정정
3. TC-VIDEO-006: `EventTypeService.java:157-180` → `175-198`로 정정 — **구 라인은 인접한 다른 메서드(`categoryKeyOf`)를 가리키고 있었음**(단순 줄밀림이 아니라 잘못된 메서드 지목, 정정 우선순위 높음)

경미하여 미정정(사유: 라인 오차 5줄 이내이거나 doc-comment vs code 시작줄 차이 수준, 결론에 영향 없음): TC-BATCH-181(139-150 vs 실제 146-150), TC-BATCH-194(AutoLabelBatchPersister.java:55 vs 실제 65-67), TC-BATCH-203(76-84,111-114 vs 실제 77-85,112-117).

## 이전 회차 이슈 해소 여부

`docs/검증결과/2026-08-01/1차/ISSUES.md`·`2026-08-02/2차/ISSUES.md`를 grep한 결과, **B-15/B-16/B-17/B-18에 대응하는 이전 회차 B-ISSUE 없음** — B-15~17은 이번 회차 이전에 이미 신규 섹션으로 추가돼 있었으나 기존 이슈 목록에 해당 TC-ID 매핑이 없었고(1차/2차 시점 카탈로그에 존재하지 않았거나 미검증), B-18은 2026-08-03 신설 섹션(과제 지시대로 신규 검증 취급). 따라서 "해소 여부 대조" 대상 자체가 없음 — 전건 신규 판정.

## 종합 판정 집계

| 섹션 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| B-15 | 11 | 11 | 0 | 0 | 0 | 0 |
| B-16 | 6 | 6 | 0 | 0 | 0 | 0 |
| B-17 | 8 | 8 | 0 | 0 | 0 | 0 |
| B-18 | 18 | 18 | 0 | 0 | 0 | 0 |
| **합계** | **43** | **43** | **0** | **0** | **0** | **0** |

결함(B-ISSUE) 신규 발견 없음. B-ISSUE-121 이슈번호는 사용되지 않음(발생 이슈 0건).
