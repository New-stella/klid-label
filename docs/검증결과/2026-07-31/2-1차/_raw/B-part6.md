# B 클러스터 — Part 6 (B-14~B-17, 35건) 검증 결과

- 담당: B-14 Quartz클러스터링/인프라/헬스(10) · B-15 배치스텝 트랜잭션경계(11) · B-16 오토라벨 일괄저장(6) · B-17 LS_DATA_RAW FK(V146)(8)
- 이슈 ID 대역: B-ISSUE-106~120
- 환경: backend `http://localhost:18081/api` (SPRING_PROFILES_ACTIVE=local), DB `klid-postgres`/`klid_system`(스키마 `public`) — 2-1차 공용 스택(stack-bringup.md/pipeline-drive.md 확인, rawSn 4/5/6 파이프라인 실구동 완료 상태) 그대로 사용. **재기동 없음.**
- 코드/설정/DB 스키마/다른 결과 파일 수정 없음. DB 검증은 전량 `BEGIN...ROLLBACK` 트랜잭션 내부에서만 수행(영속 변경 0건, 세션 종료로 재확인).

## B-14. Quartz 클러스터링 / 인프라 / 헬스 (10건)

| ID | 판정 | 근거확인 | 비고 |
|---|:--:|:--:|---|
| TC-BATCH-170 | PASS | [실동작] | QRTZ_* 11개 테이블 실존(`\dt qrtz*`), `qrtz_job_details` 4건·`qrtz_locks` 2건 실측. `QuartzConfig.java:31-38`에서 `controlDataSource` 명시 주입 코드 확인 + `QuartzClusteringConfigGuardTest.clusteringSupportSettingsAreCoherent`가 `PostgreSQLDelegate`+`useProperties=true`를 prd yml 기준으로 고정 |
| TC-BATCH-171 | BLOCKED | [정적] | 사유: 로컬은 단일 노드라 2노드 A-A 동시 트리거 중복발화 실측 불가. `application.yml:95`(공통 false) / `application-stg.yml:11`,`application-prd.yml:13`(각 true override) 라인 실측 일치. 클러스터링 자체(중복 발화 억제)는 Quartz 표준 JDBC-JobStore 내장 기능이라 커스텀 로직 없음 |
| TC-BATCH-172 | PASS | [정적] | `AsyncBatchRunner.java:32-34` `catch (Exception e) { log.error(...) }`로 예외 삼킴 확인, 재throw 없이 정상 반환(메서드 시그니처 void) |
| TC-BATCH-173 | PASS | [실동작] | `QuartzClusteringBootGuardTest.prdWithoutClusteringFailsContextStartup`(컨텍스트 러너로 `IllegalStateException` 루트원인 실제 기동 실패 고정) + `QuartzClusteringGuard.java:64-95` 코드 일치 |
| TC-BATCH-174 | PASS | [실동작] | `QuartzClusteringGuardTest.mixedOrUnknownProfilesAreStrict`가 혼합(`local,prd`)·오타(`prd1`)·대소문자(`LOCAL`)·미지정 4가지 모두 예외 발생을 실제 단언. `QuartzClusteringGuard.java:97-103`(`containsAll` allowlist) 코드 일치 |
| TC-BATCH-175 | PASS | [실동작] | `QuartzClusteringGuardTest.deployedEnvMarkerWins` + `QuartzClusteringBootGuardTest.deployedEnvMarkerFailsEvenOnDevProfile`(컨텍스트 기동 실패까지 고정) — `SPRING_PROFILES_ACTIVE=dev`+`ENV=prd` 조합 거부 확인 |
| TC-BATCH-176 | PASS | [정적] | `QuartzClusteringGuard.java:50,66` — `KEY_CLUSTERED` 상수로 Quartz 실 프로퍼티 키(`spring.quartz.properties.org.quartz.jobStore.isClustered`)를 `Environment.getProperty`로 직접 읽음(별도 authoring.* 커스텀 키 없음, 우회 불가) |
| TC-BATCH-177 | PASS | [정적] | `QuartzClusteringGuard.java` 클래스 주석(40-41행) "클러스터링은 트리거 중복 발화만 막는다" 명시 + `KpstDeidentTxService.tryClaimPoll`/`claimDownloadCompletion`(1차에서 확인된 원자 클레임, 별도 유지) — 두 방어가 독립적으로 존재 |
| TC-BATCH-178 | PASS | [정적] | `AsyncConfig.java:32-47` core2/max4/queue50/`CallerRunsPolicy` 확인. `grep '@Async("batchAsyncExecutor")'` 결과 11개 파일 공유(`AsyncDeidentifyRunner`/`AsyncBatchRunner`/`AsyncResolutionRunner`/`AsyncDatasetExportRunner`/`AsyncAugmentFrameRunner`/`AugmentResultService`/`AugmentRequestBridge`/`AugmentJobSubmitService`/`AsyncVideoMetaRunner`/`DevPipelineRunner`/`VlmWithheldResumeRunner`) — 비식별·배치·증강·export·해상도·VLM재개·영상메타 전부 확인 |
| TC-BATCH-179 | PASS | [정적] | `DeidentifyHealthIndicator.java:76-113` 3분기(mock=핑없이 UP / kpst enabled+WebClient존재=루트 핑 / 둘다아님=DOWN unconfigured) 코드 확인 + `DeidentifyHealthIndicatorTest`(MockWebServer 기반) 5개 테스트가 각 분기(mock UP·정상 UP·루트경로(`/`)단언·타임아웃 DOWN·미구성 DOWN)를 전부 실동작 고정, 에러 상세는 `e.getClass().getSimpleName()`만 노출(줄바꿈 미포함 단언 존재, CWE-209) |

## B-15. 배치 스텝 트랜잭션 경계 (11건)

| ID | 판정 | 근거확인 | 비고 |
|---|:--:|:--:|---|
| TC-BATCH-180 | PASS | [정적] | `YoloAutolabelStep.java:155-156` `@Transactional(value="controlTransactionManager", propagation=REQUIRES_NEW) public void execute(...)` 확인(근거 라인 155-158→실제 155-167, 오차 미미) |
| TC-BATCH-181 | PASS | [정적] | `Sam2SegmentStep.java:134-135` 동일 애노테이션 확인 |
| TC-BATCH-182 | PASS | [정적] | `TrackInterpolationStep.java:104-105` 동일 애노테이션 확인 |
| TC-BATCH-183 | PASS | [정적] ⚠근거드리프트 | `VlmTimeseriesStep.java:219-220`에서 `@Transactional(value="controlTransactionManager", propagation=REQUIRES_NEW)`(readOnly 속성 없음=쓰기가능) 확인. **카탈로그 근거는 128-135인데 실제는 219-220 — 파일 상단에 상수/필드가 늘어나며 91행 드리프트 발생(내용은 일치, 라인 번호만 어긋남)** |
| TC-BATCH-184 | PASS | [정적] | `FfmpegFrameExtractor.java:129-130` 동일 애노테이션 확인(카탈로그 129-131과 거의 일치) |
| TC-BATCH-185 | PASS | [실동작 코드경로] | `FfmpegFrameExtractor.java:143` `extractByMarks(ctx.getRaw(), marks, pinnedFps)` — `this.` 암묵 자기호출(프록시 미경유) 확인, 클래스 주석(123-127행)도 "self-invocation"·"중첩 없음" 명시 |
| TC-BATCH-186 | PASS | [정적] | `YoloAutolabelStep.java:167-168`(`public List<BbHint> run(Long rawSn)`)·`VlmTimeseriesStep.java:252-255`(`runWithMarking`) 둘 다 독립 `@Transactional(REQUIRES_NEW)` 유지 확인(직접 호출 진입점도 트랜잭션 보유) |
| TC-BATCH-187 | PASS | [실동작] | `BatchStepTransactionBoundaryTest.selfProxyStepsMustNotAnnotateExecute` 실제 리플렉션 검사로 `DeidentifyStep.execute`에 `@Transactional` 없음을 단언 + `DeidentifyStep.java` execute 본문(238행 부근) `selfProvider.getObject()`로 자기참조 프록시 경유 `run()` 호출 코드 확인 |
| TC-BATCH-188 | PASS | [실동작] | `BatchStepTransactionBoundaryTest`의 `BOUNDARY_EXEMPT`에 `MarkingLoadStep.class` 포함 확인 + `MarkingLoadStep.java:49-58` execute가 조회+JSON파싱만 수행(DML 0건) 확인 |
| TC-BATCH-189 | PASS | [실동작] | `BatchStepTransactionBoundaryTest.everyStepExecuteHasRequiresNewBoundary`(클래스패스 스캔, `hasSizeGreaterThanOrEqualTo(6)` 공허 방지 가드 포함) — 면제 목록 밖 모든 `BatchStep` 구현에 `REQUIRES_NEW`+`controlTransactionManager` 강제 확인. `execute` 미선언 시 `NoSuchMethodException`으로 즉시 실패하는 방어도 확인(37-105행) |
| TC-BATCH-190 | PASS | [정적] | `LsDataSrcRepository.java:292-295` `bumpLabelVersionIn`에 `@Modifying`+`@Query`만 있고 `@Transactional` 미부착 확인(카탈로그 283-295와 근접 일치) |

## B-16. 오토라벨 일괄저장 AutoLabelBatchPersister (6건)

| ID | 판정 | 근거확인 | 비고 |
|---|:--:|:--:|---|
| TC-BATCH-191 | PASS | [실동작] | `YoloAutolabelStepTest.한_프레임의_검출들은_라벨_saveAll_1회와_AI메타_saveAll_1회로_저장된다`(line 1106)·`Sam2SegmentStepTest`(line 611) 동일명 테스트가 `verify(lblRepository, times(1)).saveAll(...)` / `verify(aiInfoRepository, times(1)).saveAll(...)`로 실제 단언. `AutoLabelBatchPersister.java:61-84` 코드와 `YoloAutolabelStep.java:200-329`(pending 리스트→saveAll 1회) 호출부 일치 |
| TC-BATCH-192 | PASS | [정적] | `AutoLabelBatchPersister.java:77-83` — `saved`(순서 보존) 순회하며 `label.getLblSn()/getSrcSn()`을 직접 읽어 AI메타 생성. 인덱스 매칭이라 오염 불가 |
| TC-BATCH-193 | PASS | [정적] | `AutoLabelBatchPersister.java:72-76` `saved.size() != pending.size()`시 `CustomException(INTERNAL_ERROR, "자동 라벨 일괄 저장 결과 개수 불일치")` throw 확인 |
| TC-BATCH-194 | PASS | [정적] | `AutoLabelBatchPersister.java:65-67` `pending==null\|\|isEmpty()`→즉시 `return 0`(리포지토리 미호출) 확인 |
| TC-BATCH-195 | PASS | [정적] | `AutoLabelBatchPersister.java:47-48`(`PendingLabel(label, score)` record, 엔티티와 분리 보관) + `:80-81`(`pending.get(i).score()`를 AI메타에 사용, 엔티티 재조회 아님) 확인 |
| TC-BATCH-196 | PASS(현행유지) | [정적] | **B-ISSUE-42 이월 재확인 — 여전히 부분 해소.** `LsDataLbl.java:55` `@GeneratedValue(strategy=GenerationType.IDENTITY)`, `LsDataLblAiInfo.java:30` 동일 — 두 엔티티 모두 IDENTITY PK 그대로다. Hibernate는 IDENTITY 전략에서 PK를 즉시 알아야 해 JDBC 배치(`hibernate.jdbc.batch_size`)가 구조적으로 비활성화된다. `AutoLabelBatchPersister.java:22-28` 클래스 주석도 이 한계를 명시("실익은 왕복 감소·저장 지점 단일화이지 INSERT 문 묶음 아님"). **1차 판정과 동일 — 실 JDBC 배치 insert는 여전히 비활성.** |

## B-17. LS_DATA_RAW 참조 무결성 FK V146 (8건)

| ID | 판정 | 근거확인 | 비고 |
|---|:--:|:--:|---|
| TC-BATCH-200 | PASS | [실동작] | **DB-ISSUE-01 재확인 — 해소됨.** `psql`로 `pg_constraint WHERE confrelid='ls_data_raw'::regclass` 실측 결과 **28건**(V146 신규 27 + 기존 LS_EVNT_ANNO 1건 CASCADE로 재생성) 확인. CASCADE 25 · SET NULL 2(`fk_ls_tus_upload_raw`,`fk_ls_webhook_idempotency_raw`, `confdeltype='n'`) — 카탈로그 수치와 완전 일치. `flyway_schema_history`에 `version=146, success=t` 확인 |
| TC-BATCH-201 | PASS | [실동작] | **BEGIN/ROLLBACK 트랜잭션 내 실제 DELETE 수행**: 신규 `ls_data_raw` 1행 INSERT → `ls_marking` 자식 1행 INSERT(전=1건) → 부모 `DELETE` → 자식 재조회 결과 **0건**(CASCADE 실증). 세션 종료 시 ROLLBACK으로 영속 변경 없음(사후 `SELECT`로 원상 확인 완료). 자동 테스트 `LsDataRawChildFkCascadeIT.deletingRawCascadesToChildren`도 동일 시나리오를 Testcontainers로 고정 |
| TC-BATCH-202 | PASS | [실동작] | 동일 트랜잭션에서 `ls_webhook_idempotency` 1행(참조 있음) → 부모 삭제 후 **행 생존 + `raw_sn` NULL 처리** 확인(`fk_ls_webhook_idempotency_raw`, `confdeltype='n'`). `LsDataRawChildFkCascadeIT.ledgerRowsSurviveWithNullReference` 자동 테스트와 일치 |
| TC-BATCH-203 | PASS | [정적] | `V146__add_ls_data_raw_child_fk.sql:76-85,112-117` `view_feed_tables`(7개) 고아 발견 시 `RAISE EXCEPTION`(삭제 대신 중단) 확인. **자동 테스트 `LsDataRawOrphanCleanupIT.orphansInViewFeedingTableAbortMigration`가 실 V146 스크립트를 재실행해 `ls_data_meta`(뷰공급 테이블) 고아 삽입 후 예외("V146 중단"+테이블명 포함) 및 고아 미삭제(잔존 1건)까지 실증** — 강한 근거 |
| TC-BATCH-204 | PASS | [정적] | `V146__add_ls_data_raw_child_fk.sql:87,107-110` `max_orphans=1000` 초과 시 `RAISE EXCEPTION` 확인. ⚠ 1000건 초과 시나리오를 실제로 재현하는 전용 테스트는 미발견(대량 데이터 삽입 비용 때문으로 추정) — 로직 자체는 명확하나 임계값 초과 분기는 테스트 커버 갭 |
| TC-BATCH-205 | PASS | [실동작] | `psql`로 `mng_clip_schedule_que`의 제약 목록 실측 결과 PK 1건뿐(ls_data_raw 참조 FK 없음) 확인. `\d ls_data_raw`로 `orgnl_raw_sn` 컬럼에 인덱스(`idx_ldr_orgnl`)만 있고 FK 없음 확인(둘 다 제외 대상과 일치). `LsDataRawOrphanCleanupIT`/`LsDataRawChildFkCascadeIT`에 각각 대응 테스트(`sharedControlTableHasNoFk`, `derivativeLineageColumnHasNoFk`) 존재 |
| TC-BATCH-206 | PASS | [정적] | `V146__add_ls_data_raw_child_fk.sql:96-163` 구조 확인: 1)94-119행 고아 실태조사(`RAISE NOTICE`) → 2)121-143행 정리(CASCADE=DELETE/SET NULL=UPDATE) → 3)145-162행 멱등 FK 생성(`pg_constraint` 존재확인 후 `ADD CONSTRAINT`). `LsDataRawOrphanCleanupIT.orphansInPlainChildTableAreCleanedAndFkCreated`가 실제 스크립트 재실행으로 순서 동작(고아 정리 후 FK 생성)까지 실증 |
| TC-BATCH-207 | PASS | [실동작] | `LsDataRawOrphanCleanupIT`의 `@AfterEach tearDown()`이 매 테스트 후 `runMigration()`(V146 스크립트 재실행)을 호출하며 예외 없이 통과 — 기존 FK 존재 시 `pg_constraint` 체크로 skip되는 멱등성을 반복 재실행으로 실증(전용 스크립트 재실행 테스트가 회귀 형태로 이미 내재) |

## 이월 이슈 재확인 (요청 사항)

### B-ISSUE-42 — 오토라벨 일괄저장 JDBC 배치, 여전히 부분 해소 (미해소 유지)
- **재확인 결과**: `LsDataLbl.java:55`, `LsDataLblAiInfo.java:30` 둘 다 `@GeneratedValue(strategy = GenerationType.IDENTITY)` 그대로. `AutoLabelBatchPersister`(B-16, 2026-07-30 신규)가 검출 루프의 개별 `save()`를 프레임 단위 `saveAll()` 2회로 묶었지만(왕복 감소·저장 지점 단일화는 확실히 개선), Hibernate는 IDENTITY 전략에서 PK를 즉시 확보해야 하므로 `hibernate.jdbc.batch_size`가 이 엔티티들에는 여전히 적용되지 않는다(실제 INSERT 문 배치화는 없음).
- **판정**: 카탈로그 TC-BATCH-196의 기대결과 자체가 이 현재 상태(부분 해소)를 정확히 반영하고 있어 케이스 자체는 PASS. 다만 **근본 이슈(B-ISSUE-42)는 1차 대비 상태 변화 없이 그대로 미해소 유지**임을 재확인·보고한다. 완전 해소하려면 시퀀스 PK 전환이 선행돼야 하며, `AutoLabelBatchPersister.java` 클래스 주석이 `LBL_SN`을 참조하는 모듈 12개 이상(증강 라벨맵·해상도 파생·포털·품질검사·export 해시·버전 롤백 등)의 영향으로 별건 과제임을 명시하고 있어 이번 회차에서 추가로 손댈 필요는 없다(계획된 범위 밖).

### DB-ISSUE-01 — LS_DATA_RAW 참조 무결성 FK 부재 → **해소 확인**
- **1차 발견**: `ls_data_raw`를 참조하는 FK가 `ls_evnt_anno` 단 1건(NO ACTION)뿐이라 자식 테이블(마킹 등) 고아 행 발생.
- **재확인 결과**: V146 마이그레이션(`flyway_schema_history` version=146, success=t) 적용 확인. 실 DB `pg_constraint` 조회 결과 `ls_data_raw`를 참조하는 FK **28건**(CASCADE 26 — 신규 25 + 기존 evnt_anno 재생성 1 / SET NULL 2) 확인. 트랜잭션 내 실제 DELETE로 CASCADE·SET NULL 동작 모두 실증(위 TC-BATCH-201/202). MNG_* 및 ORGNL_RAW_SN 제외 정책도 실측 일치.
- **판정**: **완전 해소.** 고아 행이 구조적으로 불가능해졌다.

## 판정 집계

| 판정 | 건수 |
|---|:--:|
| PASS | 34 |
| BLOCKED | 1 (TC-BATCH-171, 사유: 2노드 Active-Active 환경 필요 — 로컬 단일 노드로는 재현 불가) |
| FAIL / PARTIAL / 확인필요 | 0 |
| **합계** | **35** |

## 근거 드리프트 (카탈로그 정합성)

- TC-BATCH-183: 카탈로그 근거 `VlmTimeseriesStep.java:128-135` → 실제 `execute`는 219-220행(91행 드리프트). 내용(REQUIRES_NEW, readOnly 아님)은 일치하나 파일 상단에 상수/필드가 증가하며 라인 번호가 크게 밀렸다. 원본 카탈로그 라인 갱신 제안.
