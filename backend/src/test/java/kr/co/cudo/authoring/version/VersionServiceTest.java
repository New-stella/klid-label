package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VersionService 통합 테스트 (DB 스냅샷 기반).
 *
 * <p>버전/이력은 LS_LABEL_VERSION (versionHash + labelPayload) 에만 저장된다.
 * 버전 스냅샷은 검수 승인(APPROVED) 시점에만 영상(rawSn) 단위로 생성된다(SFR-08, commitApproved).
 * commitApproved(영상 다중 프레임 스냅샷·빈 프레임 스킵·멱등 재승인), diff(두 스냅샷 비교),
 * rollback(스냅샷 복원), 접근권한(IDOR), 페이로드 한도를 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@RecordApplicationEvents
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VersionServiceTest {

    @Autowired private VersionService versionService;
    @Autowired private LabelService labelService;
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private WorkLockService workLockService;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;
    @Autowired private ApplicationEvents applicationEvents;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private Long srcSn;
    private Long rawSn;

    private TokenClaims reviewer;
    private TokenClaims workerAssigned;
    private TokenClaims portalUser;

    @BeforeEach
    void setup() {
        // LsLabelVersion 시드 정리 — 이 테스트 동안 새로 만든 row 만 남도록.
        labelVersionRepository.deleteAll();

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-VER-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        srcSn = srcRepository.save(LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now()))
                .getSrcSn();

        // 작업자 100 만 배정
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));

        // 이전 테스트 잔여 잠금 정리.
        workLockService.releaseRaw(rawSn, "test", "TEST_SETUP");

        Instant exp = Instant.now().plusSeconds(60);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, exp);
        workerAssigned = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, exp);
        portalUser = new TokenClaims("100", Role.PORTAL_USER, Channel.PORTAL, exp);
    }

    private LsLabelVersion seed(String versionHash, String payload, int versionNo, boolean active) {
        LsLabelVersion v = LsLabelVersion.create(rawSn, srcSn, versionHash, payload,
                versionNo, LsLabelVersion.SAVE_REASON_APPROVED, "100");
        if (!active) {
            v.deactivate();
        }
        return labelVersionRepository.save(v);
    }

    private void seedLabel(Long frameSn, String label, String pointsJson) {
        labelRepository.save(LsDataLbl.createManual(frameSn, "BBOX", null, label, pointsJson, 100L));
    }

    private void seedSkeletonLabel(Long frameSn, String label, String pointsJson) {
        labelRepository.save(LsDataLbl.createManual(
                frameSn, LsDataLbl.TYPE_SKELETON, null, label, pointsJson, 100L));
    }

    /** v 를 지정한 17-keypoint SKELETON 좌표 JSON ([[x,y,v],x17]). */
    private static String skeleton17Points(java.util.function.IntUnaryOperator vByIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(i * 2.0).append(',').append(i * 3.0)
                    .append(',').append(vByIndex.applyAsInt(i)).append(']');
        }
        return sb.append(']').toString();
    }

    /** 영상(rawSn) 검수 상태를 지정값으로 적재 — APPROVED 롤백 통지 조건 검증용. */
    private void seedRawStatus(String stts) {
        LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
        status.transitionTo(stts);
        rawDataStatusRepository.save(status);
    }

    /** commitApproved 가 만든 active 스냅샷의 versionHash 를 반환 (스냅샷 직렬화 형식 그대로 롤백 입력). */
    private String approvedSnapshotHash() {
        return labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn).stream()
                .filter(v -> LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn()))
                .findFirst().orElseThrow().getVersionHash();
    }

    // ---------- commitApproved (검수 승인 시점 영상 단위 스냅샷) ----------

    @Test
    @DisplayName("검수_승인시_영상_프레임의_현재_라벨로_DB_스냅샷_APPROVED_버전_생성")
    void commitApprovedWritesSnapshotPerFrame() {
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");

        int created = versionService.commitApproved(rawSn, reviewer).created();

        assertThat(created).isEqualTo(1);
        List<LsLabelVersion> history = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(history).hasSize(1);
        LsLabelVersion saved = history.get(0);
        assertThat(saved.getVersionHash()).hasSize(64).matches("[0-9a-f]+");
        assertThat(saved.getLabelPayload()).isNotNull();
        assertThat(saved.getRegId()).isEqualTo("1");
        assertThat(saved.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        assertThat(saved.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_APPROVED);
    }

    @Test
    @DisplayName("HIGH_영상_다중_프레임_각_프레임마다_스냅샷_생성_라벨_없는_프레임은_스킵")
    void commitApprovedSnapshotsEachFrameSkipsEmpty() {
        // 프레임 2개 추가: frame1(라벨 있음), frame2(라벨 없음)
        Long frame1 = srcRepository.save(LsDataSrc.create(rawSn, 1, "/raw/1.jpg", LocalDateTime.now()))
                .getSrcSn();
        Long frame2 = srcRepository.save(LsDataSrc.create(rawSn, 2, "/raw/2.jpg", LocalDateTime.now()))
                .getSrcSn();
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        seedLabel(frame1, "car", "[[1.0,1.0],[2.0,2.0]]");
        // frame2 는 라벨 없음 → 스냅샷 미생성

        int created = versionService.commitApproved(rawSn, reviewer).created();

        assertThat(created).isEqualTo(2);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(1);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(frame1)).hasSize(1);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(frame2)).isEmpty();
    }

    @Test
    @DisplayName("HIGH_멱등_재승인_수정_없이_재승인시_동일_payload_중복_버전_미생성")
    void commitApprovedReApprovalIsIdempotent() {
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");

        int firstCreated = versionService.commitApproved(rawSn, reviewer).created();
        int secondCreated = versionService.commitApproved(rawSn, reviewer).created();

        assertThat(firstCreated).isEqualTo(1);
        assertThat(secondCreated).isEqualTo(0);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(1);
    }

    @Test
    @DisplayName("HIGH_수정_후_재승인시_새_active_버전_생성_이전_active_deactivate")
    void commitApprovedAfterEditCreatesNewVersion() {
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);

        // 라벨 수정 후 재승인
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        seedLabel(srcSn, "person", "[[20.0,20.0],[60.0,60.0]]");
        int created = versionService.commitApproved(rawSn, reviewer).created();

        assertThat(created).isEqualTo(1);
        List<LsLabelVersion> all = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(all).hasSize(2);
        long activeCount = all.stream()
                .filter(v -> LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn())).count();
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("프레임이_없는_영상_승인시_스냅샷_0건_생성_예외없음")
    void commitApprovedNoFramesCreatesNothing() {
        // 새 라벨 없는 별도 영상
        LsDataRaw empty = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-VER-EMPTY", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/empty.mp4", LocalDateTime.now(), 30));

        int created = versionService.commitApproved(empty.getRawSn(), reviewer).created();

        assertThat(created).isZero();
    }

    @Test
    @DisplayName("BE_4_대용량_폴리곤_라벨_검수승인_1MB_초과여도_단순화로_정상_스냅샷_생성")
    void commitApprovedLargePolygonNotBlockedBy1MbLimit() {
        // given — 1MB 를 넘는 대형 폴리곤 라벨(좌표 ~6만 점). 과거엔 validatePayloadSize(1MB) 하드 한도에
        // 걸려 commitApproved 가 CustomException 을 던지고 approve 트랜잭션 전체가 롤백되어 검수 승인 자체가
        // 차단됐다. BE-4 수정으로 1MB 초과 시 폴리곤 단순화(+10MB 상향 한도)를 적용해 정상 승인된다.
        StringBuilder sb = new StringBuilder("[");
        int pointCount = 60_000; // 점당 약 20+ 바이트 → 직렬화 시 1MB 초과
        for (int i = 0; i < pointCount; i++) {
            if (i > 0) sb.append(',');
            sb.append("[").append(10000.0 + i).append(",").append(20000.0 + i).append("]");
        }
        sb.append("]");
        seedLabel(srcSn, "person", sb.toString());

        // when — 검수 승인 스냅샷 생성 (예외 없이 성공해야 함)
        int created = versionService.commitApproved(rawSn, reviewer).created();

        // then — 스냅샷 1건 생성 + 단순화로 페이로드가 1MB 이하로 축소되어 저장됨
        assertThat(created).isEqualTo(1);
        List<LsLabelVersion> history = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(history).hasSize(1);
        LsLabelVersion saved = history.get(0);
        assertThat(saved.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_APPROVED);
        assertThat(saved.getVersionHash()).hasSize(64).matches("[0-9a-f]+");
        // 단순화 적용 후 페이로드는 원본(1MB 초과)보다 작아진다(좌표 상한 1000점 이하로 축소).
        int payloadBytes = saved.getLabelPayload()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(payloadBytes)
                .as("단순화 후 페이로드는 1MB 일반 한도 이하로 축소돼야 한다")
                .isLessThanOrEqualTo(VersionService.MAX_PAYLOAD_BYTES);
    }

    @Test
    @DisplayName("M2_정상_승인시_CommitResult_스킵0건_created정합_무음누락_없음")
    void commitApprovedReportsZeroSkipsOnNormalApproval() {
        // given — 정상 라벨 1건.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");

        // when
        VersionService.CommitResult result = versionService.commitApproved(rawSn, reviewer);

        // then — 직렬화/크기 문제 없으므로 스킵 0건, created 1건, hasSkips=false.
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.hasSkips()).isFalse();
    }

    @Test
    @DisplayName("M2_라벨없는_프레임만_있는_영상은_스킵집계에_포함되지_않음_빈프레임_제외")
    void commitApprovedEmptyFramesAreNotCountedAsSkips() {
        // given — 라벨이 없는 프레임만 (빈 프레임은 무음 누락이 아니라 정상 스킵 — skipped 집계 제외).
        // 기본 srcSn 프레임에 라벨을 시드하지 않는다.

        // when
        VersionService.CommitResult result = versionService.commitApproved(rawSn, reviewer);

        // then — created 0, 빈 프레임은 skipped 에 포함되지 않아 0건 → hasSkips=false (불필요 경고 방지).
        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.hasSkips()).isFalse();
    }

    @Test
    @DisplayName("존재하지_않는_영상_승인_스냅샷_시도시_NOT_FOUND")
    void commitApprovedUnknownRawNotFound() {
        assertThatThrownBy(() -> versionService.commitApproved(999_999L, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------- 채널 분기: PORTAL → 버전관리 미제공 (정책 유지) ----------

    @Test
    @DisplayName("포털_모드_channel_PORTAL_는_버전관리_미제공_isCommittable_검증")
    void portalChannelHasNoVersioning() {
        assertThat(VersionService.isCommittable(portalUser)).isFalse();
        assertThat(VersionService.isCommittable(workerAssigned)).isTrue();
        assertThat(VersionService.isCommittable(reviewer)).isTrue();
    }

    @Test
    @DisplayName("라벨_저장_bulkUpsert_는_버전_스냅샷을_생성하지_않음_트리거_이동_회귀가드")
    void labelBulkUpsertDoesNotCreateVersion() {
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null)
        ));
        labelService.bulkUpsert(srcSn, req, workerAssigned);

        // 라벨 저장 시 버전 스냅샷 미생성 (검수 승인 시점에만 생성).
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).isEmpty();
    }

    // ---------- rollback ----------

    @Test
    @DisplayName("배정된_WORKER가_본인_프레임_rollback_호출시_대상_스냅샷_복원_새_ROLLBACK_버전_생성")
    void assignedWorkerRollbackRestoresSnapshot() {
        String pastPayload = "{\"items\":[{\"id\":7,\"label\":\"car\"}]}";
        seed("feedface1234567890abcdef1234567890abcdef", pastPayload, 1, false);
        seed("0000000000000000000000000000000000000000", "{\"items\":[]}", 2, true);

        LsLabelVersion rollback = versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, workerAssigned);

        assertThat(rollback).isNotNull();
        assertThat(rollback.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_ROLLBACK);
        assertThat(rollback.getLabelPayload()).isEqualTo(pastPayload);
        assertThat(rollback.getRegId()).isEqualTo("100");
        assertThat(rollback.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
    }

    // ---------- rollback 작업본(LS_DATA_LBL) 복원 시맨틱 (신규) ----------

    @Test
    @DisplayName("rollback_후_LS_DATA_LBL_이_스냅샷_라벨_수_좌표와_일치_작업본_복원")
    void rollbackRestoresWorkingCopyToSnapshotContent() {
        // given — 라벨 1건(person)으로 승인 스냅샷 v1 생성 → 이후 라벨을 (car 2건)으로 변경.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = approvedSnapshotHash();

        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");
        seedLabel(srcSn, "bike", "[[3.0,3.0],[4.0,4.0]]");
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(2);

        // when — v1 스냅샷으로 롤백.
        versionService.rollback(v1Hash, srcSn, reviewer);

        // then — 작업본이 v1 (person 1건, 좌표 [10,10]-[50,50]) 으로 복원.
        List<LsDataLbl> after = labelRepository.findBySrcSn(srcSn);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getLabelNm()).isEqualTo("person");
        List<List<Double>> pts = LabelResponse.Item.from(after.get(0), null, null, objectMapper).points();
        assertThat(pts).containsExactly(List.of(10.0, 10.0), List.of(50.0, 50.0));
    }

    @Test
    @DisplayName("롤백_SKELETON_v_보존")
    void rollbackPreservesSkeletonVisibility() throws Exception {
        // given — SKELETON 라벨 1건(17 삼중값, v 0/1/2 순환)으로 승인 스냅샷 v1 생성.
        seedSkeletonLabel(srcSn, "person", skeleton17Points(i -> i % 3));
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = approvedSnapshotHash();

        // 이후 라벨을 v 전부 0 인 다른 삼중값으로 교체 (v 변경이 복원으로 되돌려지는지 확인용).
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        seedSkeletonLabel(srcSn, "person", skeleton17Points(i -> 0));

        // when — v1 스냅샷으로 롤백 (parseSnapshotLabels → replaceFrameLabels 복원 경로 실행).
        versionService.rollback(v1Hash, srcSn, reviewer);

        // then — 복원된 LS_DATA_LBL 의 POINT_CN 삼중값 v 가 원본(0/1/2 순환)과 동일.
        List<LsDataLbl> after = labelRepository.findBySrcSn(srcSn);
        assertThat(after).hasSize(1);
        com.fasterxml.jackson.databind.JsonNode pts = objectMapper.readTree(after.get(0).getPointCn());
        assertThat(pts.isArray()).isTrue();
        assertThat(pts.size()).isEqualTo(17);
        for (int i = 0; i < 17; i++) {
            assertThat(pts.get(i).size()).isEqualTo(3);
            assertThat(pts.get(i).get(0).asDouble()).isEqualTo(i * 2.0);
            assertThat(pts.get(i).get(1).asDouble()).isEqualTo(i * 3.0);
            assertThat(pts.get(i).get(2).asInt()).isEqualTo(i % 3);
        }
    }

    @Test
    @DisplayName("rollback_후_캔버스_조회_getByFrame_가_스냅샷_라벨_반환")
    void rollbackReflectedInLabelCanvasQuery() {
        // given
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = approvedSnapshotHash();
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");

        // when
        versionService.rollback(v1Hash, srcSn, workerAssigned);

        // then — 라벨링 캔버스 API(getByFrame) 가 복원된 라벨을 반환.
        LabelResponse resp = labelService.getByFrame(srcSn, workerAssigned);
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).label()).isEqualTo("person");
        assertThat(resp.items().get(0).points())
                .containsExactly(List.of(10.0, 10.0), List.of(50.0, 50.0));
    }

    @Test
    @DisplayName("APPROVED_영상_rollback시_TaskModifiedEvent_LABEL_UPDATED_발행")
    void rollbackOnApprovedPublishesTaskModified() {
        // given — 승인 스냅샷 생성 + 영상 상태 APPROVED + 라벨 변경.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = approvedSnapshotHash();
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");
        seedRawStatus(LsRawDataStatus.STTS_APPROVED);

        // when
        versionService.rollback(v1Hash, srcSn, reviewer);

        // then — TASK_MODIFIED(LABEL_UPDATED) 통지 1회 발행.
        List<TaskModifiedEvent> events = applicationEvents.stream(TaskModifiedEvent.class).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).rawSn()).isEqualTo(rawSn);
        assertThat(events.get(0).srcSn()).isEqualTo(srcSn);
        assertThat(events.get(0).changeType()).isEqualTo(ChangeType.LABEL_UPDATED);
        assertThat(ChangeType.ALL).contains(events.get(0).changeType());
        // HIGH-A(Phase 5C) — 롤백은 프레임 라벨을 과거 스냅샷으로 교체하므로 export JSON 도 바뀐다.
        //   exportRegenerated=true 여야 디바운스 flush 가 export 폴더를 새 버전으로 재생성한 뒤 통지한다.
        //   4-arg(false)로 되돌리면 이 단언이 실패한다(재생성 미트리거 회귀 방어).
        assertThat(events.get(0).exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("미검수_영상_rollback시_TaskModifiedEvent_미발행")
    void rollbackOnNonApprovedDoesNotPublish() {
        // given — 승인 스냅샷은 있으나 영상 상태 row 없음(미검수 간주).
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = approvedSnapshotHash();
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");

        // when
        versionService.rollback(v1Hash, srcSn, reviewer);

        // then — 미검수이므로 통지 미발행.
        assertThat(applicationEvents.stream(TaskModifiedEvent.class).toList()).isEmpty();
    }

    @Test
    @DisplayName("작업락_잠긴_영상_rollback시_CONFLICT_거부_라벨_미변경")
    void rollbackOnLockedRawRejected() {
        // given — 승인 스냅샷 + 라벨 변경 + 영상 작업락.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = approvedSnapshotHash();
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");
        workLockService.lockRawForRedeident(rawSn, "1");

        // when / then — 잠긴 영상은 롤백 거부.
        assertThatThrownBy(() -> versionService.rollback(v1Hash, srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        // 라벨은 변경 전(car) 그대로 — 롤백 미적용.
        List<LsDataLbl> after = labelRepository.findBySrcSn(srcSn);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getLabelNm()).isEqualTo("car");

        workLockService.releaseRaw(rawSn, "test", "TEST_CLEANUP");
    }

    @Test
    @DisplayName("손상된_스냅샷_payload_rollback시_INVALID_INPUT_부분적용_없음")
    void rollbackWithCorruptSnapshotRejected() {
        // given — 잘못된 JSON 페이로드 active 스냅샷.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        String badHash = "abcdef0123456789abcdef0123456789abcdef01";
        seed(badHash, "{not-json", 1, true);

        // when / then — 손상 스냅샷은 400, 기존 라벨 보존(부분 적용 금지).
        assertThatThrownBy(() -> versionService.rollback(badHash, srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
        assertThat(labelRepository.findBySrcSn(srcSn).get(0).getLabelNm()).isEqualTo("person");
    }

    @Test
    @DisplayName("REVIEWER_rollback_정상_동작_새_LS_LABEL_VERSION_생성")
    void reviewerRollbackCreatesNewVersion() {
        String pastPayload = "{\"items\":[{\"id\":1,\"label\":\"person\"}]}";
        seed("feedface1234567890abcdef1234567890abcdef", pastPayload, 1, false);
        seed("0000000000000000000000000000000000000000", "{\"items\":[]}", 2, true);

        LsLabelVersion rollback = versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, reviewer);

        assertThat(rollback).isNotNull();
        assertThat(rollback.getLabelPayload()).isEqualTo(pastPayload);
    }

    @Test
    @DisplayName("R12_1_롤백_대상_payload_해시가_기존_버전과_동일하면_UNIQUE_충돌없이_기존행_active_전환")
    void rollbackToExistingHashReactivatesInsteadOfInsert() {
        // given — past(비활성) + current(active). 둘 다 실제 페이로드의 SHA-256 해시로 시드해
        // 롤백 시 재계산된 해시가 기존 (srcSn, versionHash) UNIQUE 행과 충돌하는 상황 재현.
        String pastPayload = "{\"items\":[{\"id\":7,\"label\":\"car\"}]}";
        String currentPayload = "{\"items\":[]}";
        String pastHash = sha256Hex(pastPayload);
        String currentHash = sha256Hex(currentPayload);
        LsLabelVersion past = seed(pastHash, pastPayload, 1, false);
        seed(currentHash, currentPayload, 2, true);

        // when — past 버전으로 롤백 (롤백 결과 해시 = pastHash, 이미 존재)
        LsLabelVersion result = versionService.rollback(pastHash, srcSn, reviewer);

        // then — 500(UNIQUE 충돌) 없이 기존 past 행이 active 로 전환되고, current 는 비활성.
        assertThat(result).isNotNull();
        assertThat(result.getLabelVersionSn()).isEqualTo(past.getLabelVersionSn());
        assertThat(result.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        assertThat(result.getLabelPayload()).isEqualTo(pastPayload);

        List<LsLabelVersion> all = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        // 신규 INSERT 가 없으므로 행 수는 그대로 2.
        assertThat(all).hasSize(2);
        long activeCount = all.stream()
                .filter(v -> LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn())).count();
        assertThat(activeCount).isEqualTo(1L);
        // 동일 (srcSn, versionHash) 중복 없음 보장.
        assertThat(all.stream().map(LsLabelVersion::getVersionHash).distinct().count())
                .isEqualTo(all.size());
    }

    @Test
    @DisplayName("R12_1_동일_해시_롤백_반복_호출도_멱등_500_미발생")
    void rollbackToExistingHashIsIdempotentOnRepeat() {
        String pastPayload = "{\"items\":[{\"id\":7,\"label\":\"car\"}]}";
        String currentPayload = "{\"items\":[]}";
        seed(sha256Hex(pastPayload), pastPayload, 1, false);
        seed(sha256Hex(currentPayload), currentPayload, 2, true);
        String pastHash = sha256Hex(pastPayload);

        versionService.rollback(pastHash, srcSn, reviewer);
        // 두 번째 롤백 — 이미 past 가 active 라 멱등.
        LsLabelVersion second = versionService.rollback(pastHash, srcSn, reviewer);

        assertThat(second.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(2);
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("미배정_WORKER가_rollback_호출시_FORBIDDEN_accessGuard_차단")
    void unassignedWorkerRollbackForbidden() {
        TokenClaims unassigned = new TokenClaims("999", Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
        seed("feedface1234567890abcdef1234567890abcdef", "{\"items\":[]}", 1, true);

        assertThatThrownBy(() -> versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, unassigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지_않는_버전_해시_롤백시_NOT_FOUND")
    void unknownHashRollbackReturnsNotFound() {
        assertThatThrownBy(() -> versionService.rollback(
                "0000000000000000000000000000000000000000", srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("잘못된_해시_형식_입력시_INVALID_INPUT")
    void invalidHashFormatRejected() {
        assertThatThrownBy(() -> versionService.rollback("not-a-hash-../etc/passwd", srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ---------- listVersions ----------

    @Test
    @DisplayName("listVersions_빈_커밋_새_영상_은_빈_리스트_반환")
    void listVersionsEmptyForFreshSrc() {
        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listVersions_DB_스냅샷_메타_사용_active_가_current")
    void listVersionsUsesDbSnapshotMetadata() {
        seed("abc1234abc1234abc1234abc1234abc1234abc12", "{\"items\":[]}", 1, true);

        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).commitSha()).isEqualTo("abc1234abc1234abc1234abc1234abc1234abc12");
        assertThat(result.get(0).shortHash()).isEqualTo("abc1234");
        assertThat(result.get(0).isCurrent()).isTrue();
    }

    @Test
    @DisplayName("listVersions_미배정_WORKER_접근시_FORBIDDEN")
    void listVersionsForbiddenForUnassignedWorker() {
        TokenClaims unassigned = new TokenClaims("999", Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> versionService.listVersions(srcSn, unassigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------- diff (라벨 단위) ----------

    @Test
    @DisplayName("diff_두_스냅샷_labels_JSON_파싱하여_라벨_단위_ADDED_REMOVED_MODIFIED_반환")
    void diffParsesSnapshotsAndClassifiesPerLabel() {
        // id=1: 좌표 이동 → MODIFIED, id=2: from 에만 → REMOVED, id=3: to 에만 → ADDED
        String fromJson = "{\"frameNo\":7,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":2,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[100.0,100.0],[200.0,200.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":7,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[20.0,20.0],[60.0,60.0]]},"
                + "{\"id\":3,\"lblTypeCd\":\"BBOX\",\"label\":\"bike\",\"points\":[[300.0,300.0],[400.0,400.0]]}"
                + "]}";
        String fromHash = "c0fefe11c0fefe11c0fefe11c0fefe11c0fefe11";
        String toHash   = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef0";
        seed(fromHash, fromJson, 1, false);
        seed(toHash, toJson, 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.fromHash()).isEqualTo(fromHash);
        assertThat(resp.toHash()).isEqualTo(toHash);
        assertThat(resp.labels()).hasSize(3);
        assertThat(resp.labels()).extracting(LabelDiffDto::type)
                .containsExactlyInAnyOrder(LabelDiffDto.DiffType.MODIFIED,
                        LabelDiffDto.DiffType.REMOVED,
                        LabelDiffDto.DiffType.ADDED);

        LabelDiffDto modified = resp.labels().stream()
                .filter(l -> l.type() == LabelDiffDto.DiffType.MODIFIED).findFirst().orElseThrow();
        assertThat(modified.objectId()).isEqualTo("1");
        assertThat(modified.frameId()).isEqualTo(7);
        assertThat(modified.before()).isNotNull();
        assertThat(modified.after()).isNotNull();
        assertThat(modified.before().type()).isEqualTo("BBOX");
        assertThat(modified.after().left()).isEqualTo(20.0);

        LabelDiffDto removed = resp.labels().stream()
                .filter(l -> l.type() == LabelDiffDto.DiffType.REMOVED).findFirst().orElseThrow();
        assertThat(removed.objectId()).isEqualTo("2");
        assertThat(removed.before()).isNotNull();
        assertThat(removed.after()).isNull();

        LabelDiffDto added = resp.labels().stream()
                .filter(l -> l.type() == LabelDiffDto.DiffType.ADDED).findFirst().orElseThrow();
        assertThat(added.objectId()).isEqualTo("3");
        assertThat(added.before()).isNull();
        assertThat(added.after()).isNotNull();
    }

    @Test
    @DisplayName("diff_동일_라벨_데이터_비교시_라벨_변화_없음_회귀가드")
    void diffSameSnapshotReturnsEmptyLabels() {
        String sameJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        String fromHash = "abc111abc111abc111abc111abc111abc111abc1";
        String toHash   = "def222def222def222def222def222def222def2";
        seed(fromHash, sameJson, 1, false);
        seed(toHash, sameJson, 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.labels()).isEmpty();
    }

    @Test
    @DisplayName("diff_라벨_shape_변경시_MODIFIED_분류_before_after_좌표_모두_포함")
    void diffShapeChangeClassifiedAsModified() {
        String fromJson = "{\"frameNo\":2,\"items\":["
                + "{\"id\":42,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":2,\"items\":["
                + "{\"id\":42,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[15.0,12.0],[55.0,52.0]]}"
                + "]}";
        String fromHash = "1234abcd1234abcd1234abcd1234abcd1234abcd";
        String toHash   = "5678ef015678ef015678ef015678ef015678ef01";
        seed(fromHash, fromJson, 1, false);
        seed(toHash, toJson, 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.labels()).hasSize(1);
        LabelDiffDto only = resp.labels().get(0);
        assertThat(only.type()).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
        assertThat(only.objectId()).isEqualTo("42");
        assertThat(only.before().left()).isEqualTo(10.0);
        assertThat(only.before().right()).isEqualTo(50.0);
        assertThat(only.after().left()).isEqualTo(15.0);
        assertThat(only.after().right()).isEqualTo(55.0);
    }

    /** kp0 의 v 만 지정하고 나머지 좌표(x,y)는 항상 동일한 17-keypoint SKELETON 스냅샷 payload. */
    private static String skeletonDiffPayload(int kp0Visibility) {
        StringBuilder sb = new StringBuilder(
                "{\"frameNo\":3,\"items\":[{\"id\":77,\"lblTypeCd\":\"SKELETON\",\"label\":\"person\",\"points\":[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            int v = (i == 0) ? kp0Visibility : (i % 3);
            sb.append('[').append(i * 2.0).append(',').append(i * 3.0).append(',').append(v).append(']');
        }
        return sb.append("]}]}").toString();
    }

    @Test
    @DisplayName("버전diff_SKELETON_v만_바뀌면_MODIFIED_감지")
    void diffSkeletonVisibilityOnlyChangeDetected() {
        // x,y 는 완전히 동일하고 kp0 의 v(가시성)만 2 → 1 로 바뀐 두 APPROVED 스냅샷.
        String fromHash = "aaaa0001aaaa0001aaaa0001aaaa0001aaaa0001";
        String toHash   = "bbbb0002bbbb0002bbbb0002bbbb0002bbbb0002";
        seed(fromHash, skeletonDiffPayload(2), 1, false);
        seed(toHash, skeletonDiffPayload(1), 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.labels()).hasSize(1);
        assertThat(resp.labels().get(0).type()).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
        assertThat(resp.labels().get(0).objectId()).isEqualTo("77");
    }

    @Test
    @DisplayName("버전diff_SKELETON_v_동일이면_변화없음_회귀가드")
    void diffSkeletonIdenticalReturnsEmpty() {
        String fromHash = "cccc0003cccc0003cccc0003cccc0003cccc0003";
        String toHash   = "dddd0004dddd0004dddd0004dddd0004dddd0004";
        String same = skeletonDiffPayload(2);
        seed(fromHash, same, 1, false);
        seed(toHash, same, 2, true);

        assertThat(versionService.diff(fromHash, toHash, workerAssigned).labels()).isEmpty();
    }

    @Test
    @DisplayName("버전diff_기존BBOX_2튜플_회귀없음")
    void diffBboxTwoTupleRegressionUnchanged() {
        // non-SKELETON diff 는 v 확장의 영향을 받지 않아야 한다: 동일 2-튜플 → 변화 없음.
        String bbox = "{\"frameNo\":1,\"items\":[{\"id\":9,\"lblTypeCd\":\"BBOX\",\"label\":\"car\","
                + "\"points\":[[1.0,1.0],[2.0,2.0]]}]}";
        String fromHash = "eeee0005eeee0005eeee0005eeee0005eeee0005";
        String toHash   = "ffff0006ffff0006ffff0006ffff0006ffff0006";
        seed(fromHash, bbox, 1, false);
        seed(toHash, bbox, 2, true);

        assertThat(versionService.diff(fromHash, toHash, workerAssigned).labels()).isEmpty();
    }

    @Test
    @DisplayName("diff_존재하지_않는_from_해시_조회시_NOT_FOUND")
    void diffUnknownFromHashNotFound() {
        seed("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "{\"items\":[]}", 1, true);
        assertThatThrownBy(() -> versionService.diff(
                "ffffffffffffffffffffffffffffffffffffffff",
                "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", workerAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

}
