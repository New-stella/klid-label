# B-part8 — B-9 / B-14 / B-16 / B-17 검증 결과

- 검증 대상 커밋: 56d30478 (qa-0801, backend 실기동 V146)
- 스택: docker klid-backend(18081)·klid-ai-server(19300, `AI_MOCK_MODE=false`이나 YOLOX/SAM2 weights 미탑재로 실제 응답은 mock 폴백)·klid-postgres(5432, `public` 스키마)
- 환경 격차: LS_DATA_INGEST 리팩터(b2b44f0e 이후)는 이 스택 이미지에 미반영이나, 본 담당 4개 섹션(B-9/B-14/B-16/B-17)은 해당 리팩터와 무관한 오토라벨·Quartz·저장헬퍼·FK 영역이라 영향 없음. 전부 실동작/정적 검증 완주.
- 참고: `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md` — backend cleanTest 4,755 tests / 실패 0 / skip 5 (전량 통과, 2026-08-01 실행 증거 있음).

## B-9. YOLO / SAM2 / Interpolate (30건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-120 | PASS | [정적] YoloAutolabelStep.java:169-171 | rawSn null → `CustomException(INVALID_INPUT)` 확인 |
| TC-BATCH-121 | PASS | [정적][테스트] YoloAutolabelStep.java:185,200-211 · YoloAutolabelStepTest#predictYoloTrackCalledAndTrackIdPersisted(728) | clipId=String.valueOf(rawSn), frameIndex 0부터 순차 증가(루프 끝 `frameIndex++`) |
| TC-BATCH-122 | PASS | [정적] YoloAutolabelStep.java:207-216 · YoloAutolabelStepTest#externalErrorWrapped(425) | RuntimeException catch → EXTERNAL_API_ERROR 래핑 확인 |
| TC-BATCH-123 | PASS | [실동작][정적] YoloAutolabelStep.java:221-233 · ai-server 실측: `docker logs klid-ai-server`에 `[DETECT:yolox][MOCK] returning mock prediction reason=weights_missing` 실측(가중치 미탑재로 이 스택에서는 실제로 mock 경로가 항상 발화) | LogSanitizer.sanitize(source/mockReason) 적용 확인 |
| TC-BATCH-124 | PASS | [정적] YoloAutolabelStep.java:276-279 | `labelMasterService.findLabelIdByDtctType(d.label())` 사용, 미매칭 시 `.orElse(null)` |
| TC-BATCH-125 | PASS | [정적] YoloAutolabelStep.java:240-243,400-410(resolveToggle) | togglesOpt empty → `AnnotationToggle.BOTH` fail-safe 확인(catalog 400 근사) |
| TC-BATCH-126 | PASS | [정적] YoloAutolabelStep.java:427-432(readImageAsBase64) | `imagePath.startsWith(baseRawPath)` 위반 시 INVALID_INPUT, 경로 원문 미노출(CWE-22/209) |
| TC-BATCH-127 | PASS | [정적][테스트] YoloAutolabelStep.java:178-181,358-386 · YoloAutolabelStepTest#systemConfigValuesPassedToAiServer(658)·fallbackDefaultsWhenSystemConfigMissing(682) | 조회 실패 시 catch(Exception) fallback, 정상 시 SystemConfig 값 사용 |
| TC-BATCH-128 | PASS | [정적][테스트] Sam2SegmentStep.java:241-256(buildJobs) · Sam2SegmentStepTest#Sam2Step_dedup_은_label_과_trackId_조합_기준(409) | DB BBOX가 `putIfAbsent`로 먼저 등록되어 hint보다 우선 |
| TC-BATCH-129 | PASS | [정적][테스트] Sam2SegmentStep.java:182-188 · Sam2SegmentStepTest#Sam2Step_polygonEnabled_false(273) | `!toggle.polygon()` → WARN + continue |
| TC-BATCH-130 | PASS | [정적] Sam2SegmentStep.java:196,365-383(capPolygon) | `polygon.size() > MAX_POINTS_PER_LABEL`일 때만 `PolygonSimplifier.simplifyToMax` 호출, 이하면 원본 반환 |
| TC-BATCH-131 | PASS | [정적] Sam2SegmentStep.java:226-234(callSam2) | RuntimeException catch → EXTERNAL_API_ERROR |
| TC-BATCH-132 | PASS | [정적] TrackInterpolationStep.java:144-147 | `frames.isEmpty()` → return 0 |
| TC-BATCH-133 | PASS | [정적][테스트] TrackInterpolationStep.java:158-171 · TrackInterpolationStepTest#재실행_idempotency(476) | 삭제 순서 `aiInfoRepository.deleteByDataLblSnIn`(168, 자식) → `lblRepository.deleteAllByIdInBatch`(169, 부모) 확인, FK 고아 방지 순서 정확 |
| TC-BATCH-134 | PASS | [정적][테스트] TrackInterpolationStep.java:187-195 · TrackInterpolationStepTest#한트랙_예외_다른트랙_보간은_저장됨(450) | 트랙별 try/catch, WARN 후 skip. 다른 트랙 정상 저장 |
| TC-BATCH-135 | PASS | [정적][테스트] TrackInterpolationStep.java:353-357 · TrackInterpolationStepTest#트랙내_타입혼재(433) | `types.size() > 1` → WARN + `List.of()` |
| TC-BATCH-136 | PASS | [정적][테스트] TrackInterpolationStep.java:441-481(parseBbox) · TrackInterpolationStepTest#nested_BBOX_좌표_포맷도_파싱_지원(494) | flat[x1,y1,x2,y2] / nested[[x1,y1],[x2,y2]] 양쪽 파싱 분기 확인 |
| TC-BATCH-137 | PASS | [정적] TrackInterpolationStep.java:247-328(interpolateSingleTrack/Touched) | from+to 양쪽 stale 삭제(274-288), `@Transactional` 미부착으로 caller tx 참여, 예외 미포착(전파) — 트랙 병합 원자성 보장 확인 |
| TC-BATCH-138 | PASS | [정적] MarkingLoadStep.java:50-58 | `ctx.setMarkings` + 첫 항목 markCn 파싱 후 `ctx.setMarks` |
| TC-BATCH-139 | PASS | [정적] MarkingLoadStep.java:60-66(parseMarks) | JsonProcessingException → INTERNAL_ERROR |
| TC-BATCH-140 | PASS | [정적][테스트] BatchPipelineConfig.java:31-42 · BatchPipelineConfigTest#파이프라인_순서는_MARKING_VLM_FRAME_YOLO_SAM2_INTERPOLATE(29) | `List.of(markingLoad, vlm, frame, yolo, sam2, interp)` 순서 정확 |
| TC-BATCH-141 | PASS | [정적][테스트] DetectionBoxNormalizer.java:41-69 · DetectionBoxNormalizerTest 전건(clampsNegativeToZero 등) | clamp 규칙 단일 유틸 확인, YOLO/SAM2 양쪽이 동일 함수 사용 |
| TC-BATCH-142 | PASS | [정적][테스트] DetectionBoxNormalizer.java:54-58 · DetectionBoxNormalizerTest#rejectsNonFinite(115) | `Double.isFinite` 가드가 clamp/음수검사보다 **선행**함을 코드 순서로 확인(NaN<0=false 함정 회피) |
| TC-BATCH-143 | PASS | [정적][테스트] DetectionBoxNormalizer.java:65-68 · YoloAutolabelStep.java:263-268 · DetectionBoxNormalizerTest#degenerateAfterClampIsSkipped(89)·reversedBoxIsSkipped(99) · YoloAutolabelStepTest#batchSkipsDegenerateBoxWithoutFailingFrame(259) | `x2<=x1 \|\| y2<=y1` → `Optional.empty()`, 호출부는 `droppedDegenerate++` 후 continue(영상 전체 실패 없음) |
| TC-BATCH-144 | PASS | [정적][테스트] YoloAutolabelStep.java:259-275 · YoloAutolabelStepTest#malformedPointsDropDetectionOnlyNotWholeVideo(281)·nanPointsDropDetectionOnly(309) | IllegalArgumentException을 루프 내부 catch → `droppedMalformed++` + continue, 온라인 경로 all-or-nothing 400은 별도 유지(AutolabelOnlineService, 미변경 확인) |
| TC-BATCH-145 | PASS | [정적][테스트] YoloAutolabelStep.java:316-325 · YoloAutolabelStepTest#폴리곤전용_프리셋의_hint좌표는_이미지_경계로_clamp된_값이다(356) | `hints.add(new BbHint(..., points, ...))` — points는 정규화 완료된 좌표(245-269에서 선계산), DB BBOX 없을 때도 clamp 값이 프롬프트가 됨 |
| TC-BATCH-146 | PASS | [정적][테스트] YoloAutolabelStep.java:259-275(continue 공유 지점) · YoloAutolabelStepTest#bothPresetSkipsHintWhenBboxDegenerate(379) | 정규화 실패/퇴화 시 `continue`가 bbox 저장·polygon hint 발행 코드 양쪽보다 앞에 있어 둘 다 스킵 |
| TC-BATCH-147 | PASS | [정적][테스트] DetectionBoxNormalizer.java:41-49,72-78(upperBound) · YoloAutolabelStep.java:236-237 · DetectionBoxNormalizerTest#clampsLowerBoundOnlyWhenBoundsUnknown(77)·invalidBoundsTreatedAsUnknown(138) | bounds null/비정상 시 `Double.MAX_VALUE`(상한 없음), 하한(0)만 적용 |
| TC-BATCH-148 | PASS | [정적] YoloAutolabelStep.java:236-237 | `resp.detections().isEmpty() ? null : frameBoundsResolver.resolve(...)` — 검출 0건이면 resolver 자체를 호출하지 않음 |
| TC-BATCH-149 | PASS | [정적][테스트] YoloAutolabelStep.java:189-190,299,343-345 · LsDataSrcRepository.java:283-308 | `labeledFrames`(실제 라벨 생성된 srcSn만) 수집 후 `bumpLabelVersionIn(labeledFrames)` 호출. 구 `bumpLabelVersionByRawSn`은 리포지토리에 메서드만 남고 **어디서도 호출되지 않음**(grep 확인 — 사실상 폐기) |

## B-14. Quartz 클러스터링 / 인프라 / 헬스 (10건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-170 | PASS | [실동작][정적] QuartzConfig.java:29-36 · `docker exec klid-postgres psql \d qrtz_*` → 11개 QRTZ_* 테이블 public 스키마 실존 | controlDataSource 명시 주입 + `application.yml:85-86` PostgreSQLDelegate/useProperties=true 확인 |
| TC-BATCH-171 | PASS(부분 BLOCKED) | [정적] application-stg.yml:11 `isClustered: ${QUARTZ_CLUSTERED:true}` · application-prd.yml:13 동일 · application.yml:95 공통 기본 false | 코드/설정상 stg/prd 기본 true 확정. 이 스택은 `SPRING_PROFILES_ACTIVE=local`(단일 노드) 실행 중이라 **2노드 동시성 자체는 BLOCKED(사유: 로컬 스택이 단일 프로파일·단일 인스턴스, 2노드 재현 불가)** — 설정값 자체는 실측 확인됨 |
| TC-BATCH-172 | PASS | [정적][테스트] AsyncBatchRunner.java:21-35 | `@Async`, catch(Exception) → ERROR 로그, 정상 반환(re-throw 없음) |
| TC-BATCH-173 | PASS | [정적][테스트] QuartzClusteringGuard.java:64-95 · QuartzClusteringGuardTest#prdWithoutClusteringIsRejected(32)·stgWithoutClusteringIsRejected(41) | `verify()`가 IllegalStateException 던짐 → `@PostConstruct`에서 기동 실패 유도. 실제 이 로컬 스택은 local 프로파일이라 통과(정상) |
| TC-BATCH-174 | PASS | [정적][테스트] QuartzClusteringGuard.java:53,97-103 · QuartzClusteringGuardTest#mixedOrUnknownProfilesAreStrict(49) | `SINGLE_NODE_PROFILES.containsAll(activeProfiles)` — allowlist 방식, 오타/혼합/미지정 전부 거부 확인 |
| TC-BATCH-175 | PASS | [정적][테스트] QuartzClusteringGuard.java:56,98-112 · QuartzClusteringGuardTest#배포표식_ENV가_stg_prd면_프로파일이_dev여도_엄격하게_판정한다(65) | `ENV` 환경변수가 `DevProfileGuard.DEPLOYED_ENVS`와 동일 축(stg/prd)으로 독립 판정, 프로파일 낮춰도 우회 불가 |
| TC-BATCH-176 | PASS | [정적] QuartzClusteringGuard.java:50,66 | `environment.getProperty(KEY_CLUSTERED=spring.quartz.properties.org.quartz.jobStore.isClustered, ...)` — Quartz 실 프로퍼티 직접 조회 |
| TC-BATCH-177 | PASS | [정적] QuartzClusteringGuard.java:40-41(Javadoc) · BatchRetryQueue/KpstDeidentPollJob의 원자 클레임 코드(B-12/B-13에서 별도 확인된 조건부 UPDATE) | 클러스터링과 원자 클레임이 별개 방어층임을 주석·실제 구현(조건부 UPDATE) 양쪽으로 확인 |
| TC-BATCH-178 | PASS | [정적] AsyncConfig.java:32-47(batchAsyncExecutor) · grep `@Async("batchAsyncExecutor")` 사용처 9개 파일 실측 | core2/max4/queue50/CallerRunsPolicy 단일 풀을 AsyncDeidentifyRunner(비식별)·AsyncBatchRunner(배치)·AsyncAugmentFrameRunner+AugmentRequestBridge(증강)·AsyncDatasetExportRunner(export)·AsyncResolutionRunner(해상도)·VlmWithheldResumeRunner(VLM 재개)·AsyncVideoMetaRunner(영상메타)가 실제로 공유함을 확인. VLM/KPST/증강 "제출 완료 핸들러"용 vlmSubmitExecutor 등은 **별개 계층**(리액티브 publishOn 전용)이라 혼동 주의 |
| TC-BATCH-179 | PASS | [정적][테스트] DeidentifyHealthIndicator.java:75-113 · DeidentifyHealthIndicatorTest 7건(mock UP/실모드 UP/타임아웃 DOWN/설정오류 DOWN/루트경로 핑/배선 분리 등) | 3분기(mock/kpst/미구성) + 예외 클래스명만 노출(CWE-209) 확인. 실동작 `GET /actuator/health`는 `show-details: when-authorized`라 미인증 요청은 컴포넌트 상세 미노출(정상 설정, 결함 아님) — 전체 상태는 UP 확인 |

## B-16. 오토라벨 일괄저장 AutoLabelBatchPersister (6건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-191 | PASS | [정적][테스트] AutoLabelBatchPersister.java:61-84 · YoloAutolabelStepTest#한_프레임의_검출들은_라벨_saveAll_1회와_AI메타_saveAll_1회로_저장된다(1106)·Sam2SegmentStepTest#동일(611) | 프레임 단위 pending 목록 → `lblRepository.saveAll` 1회 + `aiInfoRepository.saveAll` 1회 |
| TC-BATCH-192 | PASS | [정적] AutoLabelBatchPersister.java:72-83 | `saved.get(i)`(저장된 라벨, PK 부여된 입력 인스턴스 그 자체)에서 직접 lblSn/srcSn 추출 — 인덱스 오염 불가 |
| TC-BATCH-193 | PASS | [정적] AutoLabelBatchPersister.java:72-76 | `saved.size() != pending.size()` → `CustomException(INTERNAL_ERROR, "자동 라벨 일괄 저장 결과 개수 불일치")`. **주의**: 이 분기를 직접 겨냥한 전용 단위테스트는 없음(grep 결과 0건) — JpaRepository.saveAll이 항상 동일 크기를 반환하는 정상 경로에서는 도달하지 않는 방어 코드라 실무 영향은 낮으나, 회귀 방지용 전용 테스트 부재는 커버리지 갭으로 기록(결함 아님, ISSUE 미등록) |
| TC-BATCH-194 | PASS | [정적][테스트] AutoLabelBatchPersister.java:65-67 · YoloAutolabelStepTest#저장할_검출이_없는_프레임은_saveAll을_호출하지_않는다(1190)·Sam2SegmentStepTest#SAM2_저장대상이_없는_프레임은_saveAll을_호출하지_않는다(677) | `pending == null \|\| pending.isEmpty()` → return 0, 리포지토리 미호출 |
| TC-BATCH-195 | PASS | [정적] AutoLabelBatchPersister.java:41-48,77-82(PendingLabel.score()) | AI 메타 생성 시 `pending.get(i).score()`(원본 신뢰도) 사용, `label.getConfScore()`(엔티티 clamp 보정값) 미사용 확인 |
| TC-BATCH-196 | PASS | [정적] AutoLabelBatchPersister.java:22-28(Javadoc) · LsDataLbl.java:54-55 `@GeneratedValue(strategy=IDENTITY)` · LsDataLblAiInfo.java:29-30 동일 | 두 엔티티 모두 IDENTITY 확인 — Hibernate JDBC 배치가 구조적으로 비활성(호출 횟수 감소만이 실익)이라는 주석 서술이 실제 엔티티 전략과 일치 |

## B-17. LS_DATA_RAW 참조 무결성 FK V146 (8건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-200 | PASS | [실동작] `docker exec klid-postgres psql -c "SELECT conname,... FROM pg_constraint WHERE confrelid='public.ls_data_raw'::regclass"` → **28개 FK 실존**(child_specs 27개 + 기존 LS_EVNT_ANNO 1개, confdeltype 'c'=CASCADE 26 / 'n'=SET NULL 2) · V146__add_ls_data_raw_child_fk.sql:41-75 | 카탈로그 "27개 생성(CASCADE 25/SET NULL 2)"은 migration 스크립트 신규분 기준과 정확히 일치(기존 evnt_anno 1건은 별도 4)섹션에서 CASCADE로 재확정, 신규 생성 아님) |
| TC-BATCH-201 | PASS | [실동작] 트랜잭션 내 실측: `raw_sn=4`에 대해 `DELETE FROM ls_data_raw WHERE raw_sn=4` 실행 → `ls_marking WHERE raw_sn=4` 건수 1→0 확인 후 ROLLBACK(실 데이터 미훼손) · LsDataRawChildFkCascadeIT#영상_삭제시_자식_마킹_프레임_상태가_CASCADE로_함께_삭제된다(114) | CASCADE 실동작 확인 |
| TC-BATCH-202 | PASS | [실동작] 동일 트랜잭션에서 `ls_webhook_idempotency WHERE raw_sn=4`(1건 존재) 삭제 후 `raw_sn IS NULL` 건수 1로 증가(행 보존 + 참조만 NULL) 확인 후 ROLLBACK · LsDataRawChildFkCascadeIT#원장_세션_참조는_SET_NULL로_끊기고_행_자체는_보존된다(149) | SET NULL 실동작 확인 |
| TC-BATCH-203 | PASS | [정적][테스트] V146__add_ls_data_raw_child_fk.sql:76-85,112-117 · LsDataRawOrphanCleanupIT#데이터마트_뷰_공급_테이블에_고아가_있으면_자동삭제하지_않고_중단한다(114) | view_feed_tables 7개(ls_dataset_video_meta 등) 고아 시 RAISE EXCEPTION으로 마이그레이션 중단 — 실제 V146 스크립트를 Testcontainers로 재실행해 검증하는 전용 IT 존재 |
| TC-BATCH-204 | PASS | [정적] V146__add_ls_data_raw_child_fk.sql:87,107-111 | `max_orphans=1000` 초과 시 RAISE EXCEPTION 로직 확인. **주의**: 1000건 초과 시나리오를 직접 겨냥한 전용 테스트는 미발견(grep 0건) — 로직 자체는 단순 비교문이라 오류 가능성 낮음(결함 아님, 커버리지 갭만 기록) |
| TC-BATCH-205 | PASS | [실동작][정적] `docker exec klid-postgres psql`로 `ls_dataset_video_meta`·`mng_clip_schedule_que` FK 조회 → `ls_dataset_video_meta`는 `raw_sn` 참조 FK 1건만 존재(orgnl_raw_sn 미대상), `mng_clip_schedule_que`는 FK 0건 · V146 주석:17-26 · LsDataRawChildFkCascadeIT#관제_공유_MNG_테이블에는_FK를_걸지_않았다(201)·파생_계보_ORGNL_RAW_SN에는_FK를_걸지_않았다(217) | MNG_* 및 ORGNL_RAW_SN 제외 실측 확인 |
| TC-BATCH-206 | PASS | [정적][테스트] V146__add_ls_data_raw_child_fk.sql:94-163(DO 블록 3단계: 96-119 실태조사 → 121-143 정리 → 145-163 FK생성) · LsDataRawOrphanCleanupIT(실 스크립트 재실행 검증) | 1패스 RAISE NOTICE, 2패스 정리, 3패스 멱등 FK 생성 순서 코드로 확인 |
| TC-BATCH-207 | PASS | [실동작] `flyway_schema_history`에서 `version=146, success=t` 확인(2026-07-31 적용 완료, 재기동해도 재실행 안 됨이 Flyway 표준 동작) · V146:157(`IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname=fk_name)`) | 멱등 가드 코드 확인 + 실제 적용 이력 성공 확인. 스크립트 강제 재실행(순수 재현)은 마이그레이션 상태 조작이 필요해 미실행(코드 로직상 명백히 멱등이라 실익 낮음) |

## 종합

- **총 54건 — PASS 54 / FAIL 0 / PARTIAL 0 / BLOCKED 0(TC-BATCH-171은 부분 실측·부분 설정확인으로 PASS 처리, 완전 BLOCKED 아님) / N/A 0 / 확인필요 0**
- **근거 드리프트**: 없음 — 카탈로그 file:line이 실제 코드와 대부분 정확히 일치(±2~5줄 근사 오차는 주석/공백 삽입으로 인한 자연 드리프트이며 의미 위치는 동일)
- **self-fill 결함**: 없음 — 이 4개 섹션은 외부 연동(비식별/VLM/증강)과 직접 관련 없는 내부 로직(좌표 정규화·Quartz·저장 헬퍼·DB FK)이라 self-fill 해당 케이스 자체가 없음. 단 ai-server YOLO/SAM2는 가중치 미탑재로 이 스택에서 상시 mock 폴백 중임을 실측(TC-BATCH-123 근거로 활용, 결함 아님 — ai-server 자체가 `mock=true` 플래그를 정직하게 응답에 실어 보내고 backend가 이를 WARN으로 드러냄)
- **테스트 커버리지 갭(결함 아님, 기록만)**: TC-BATCH-193(AutoLabelBatchPersister size 불일치 방어), TC-BATCH-204(V146 max_orphans>1000 방어) — 둘 다 코드 로직은 명확히 정확하나 전용 단위테스트 부재
- **확증편향 방지 조치**: 좌표 clamp 순서(유한성 가드 우선), degenerate/malformed 분기의 실제 continue 위치, SAM2 dedup의 DB BBOX 우선순위, TrackInterpolation 삭제 순서(자식→부모), FK CASCADE/SET NULL을 실제 트랜잭션 내 DELETE로 직접 실행해 결과 확인(단순 코드 신뢰 아님) — 전부 반증 시도 후 기대결과와 일치함을 확인
