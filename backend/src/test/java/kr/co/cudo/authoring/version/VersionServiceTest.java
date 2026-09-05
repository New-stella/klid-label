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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
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
        labelRepository.save(LsDataLbl.createManual(frameSn, "BBOX", null, label, pointsJson, "100"));
    }

    private void seedSkeletonLabel(Long frameSn, String label, String pointsJson) {
        labelRepository.save(LsDataLbl.createManual(
                frameSn, LsDataLbl.TYPE_SKELETON, null, label, pointsJson, "100"));
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
    @DisplayName("승인_스냅샷은_버전번호를_비운_채_저장된다 (P1 — VER_NO 재정의, 채번은 P3)")
    void 승인_스냅샷은_버전번호를_비운_채_저장된다() {
        // given — VER_NO 는 <영상 단위 산출 버전 번호>로 재정의됐다(구 의미: 프레임별 순번).
        //   구 채번(count + 1)을 계속 넣으면 레거시 행을 NULL 로 비운 의미가 없어지고, 그 값을
        //   회차로 읽는 순간 한 영상 안에 서로 다른 시점의 프레임이 섞인다.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");

        // when
        versionService.commitApproved(rawSn, reviewer);

        // then — 실제 산출 버전 번호를 채우는 배선은 P3. 지금은 null(= 아직 모름)이 정직한 값이다.
        LsLabelVersion saved = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn).get(0);
        assertThat(saved.getVersionNo()).isNull();
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

    /**
     * ★같은 내용의 <b>비활성</b> 스냅샷이 있으면 새 행을 만들지 않고 그 행을 다시 정본으로 삼는다.
     *
     * <p>멱등 판정이 ACTIVE 행만 보기 때문에, 작업본이 비활성 스냅샷과 같은 내용이 되면(과거 회차를
     * 불러와 확정 저장한 뒤 재승인하는 정상 동선) 새 행 INSERT 가 {@code (DATA_SRC_SN, VERSION_HASH)}
     * UNIQUE 에 걸려 <b>승인 트랜잭션 전체가 롤백</b>됐다. 여기서는 그 동선을 라벨 <b>제자리 수정</b>으로
     * 최소 재현한다(회차 기계장치 없이 재사용 자체를 고정) — 화면 동선 전체는
     * {@code StartVersionRollbackReproIT.과거_회차를_불러와_확정한_뒤_재승인해도_승인이_성공한다} 가 덮는다.
     */
    @Test
    @DisplayName("같은_내용의_비활성_스냅샷이_있으면_새_행을_만들지_않고_재사용한다")
    void commitApprovedReusesInactiveSnapshotWithSameContent() {
        // given ① 내용 A 로 승인 — 스냅샷 rA(active)
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String hashOfContentA = approvedSnapshotHash();

        // given ② 내용 B 로 <b>제자리</b> 수정 후 승인 — rA 비활성, rB active (LBL_SN 유지)
        renameLabelInPlace("car");
        versionService.commitApproved(rawSn, reviewer);
        assertThat(approvedSnapshotHash()).isNotEqualTo(hashOfContentA);

        // given ③ 작업본을 다시 내용 A 로 되돌린다 — 버전 축은 건드리지 않는다(active 는 여전히 rB)
        renameLabelInPlace("person");
        assertThat(approvedSnapshotHash()).isNotEqualTo(hashOfContentA);

        // when — 재승인
        VersionService.CommitResult result = versionService.commitApproved(rawSn, reviewer);

        // then ① 새로 만든 것이 아니므로 created 는 0 이고, 누락도 아니므로 skipped 도 0 이다
        assertThat(result.created())
                .as("행을 만들지 않았으므로 created 로 세면 거짓이다")
                .isZero();
        assertThat(result.skipped())
                .as("재사용은 누락이 아니다 — skipped 로 세면 승인 API 가 손실 경고를 잘못 울린다")
                .isZero();
        assertThat(result.hasSkips()).isFalse();
        // then ② 행이 적층되지 않는다 (내용 A / 내용 B 두 건 그대로)
        List<LsLabelVersion> history = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(history)
                .as("같은 내용으로 행을 적층하면 (프레임, 해시) UNIQUE 와 정면 충돌한다")
                .hasSize(2);
        // then ③ 정본은 내용 A 행 1건뿐이다
        assertThat(approvedSnapshotHash()).isEqualTo(hashOfContentA);
        assertThat(history).filteredOn(v -> LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn()))
                .as("잉여 ACTIVE 가 남으면 회차 매핑이 어느 스냅샷을 기록할지 흔들린다")
                .hasSize(1);
    }

    /**
     * 프레임 라벨명을 <b>제자리에서</b> 바꾼다 — {@code LBL_SN} 이 유지되는 실제 편집 경로
     * ({@code LabelService.applyFrameSave} 의 기존 id 갱신 분기)와 같은 결과를 만든다.
     * 삭제 후 재삽입하면 {@code LBL_SN} 이 바뀌어 payload(그 안의 {@code id})가 달라지고,
     * 재사용 시나리오의 전제(재직렬화 payload 가 옛 스냅샷과 바이트까지 같다)가 성립하지 않는다.
     */
    private void renameLabelInPlace(String label) {
        LsDataLbl target = labelRepository.findBySrcSn(srcSn).get(0);
        target.updateUserContent("BBOX", null, label, target.getPointCn());
        labelRepository.saveAndFlush(target);
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
    @DisplayName("배정된_WORKER가_본인_프레임_rollback_호출시_대상_스냅샷_행이_재활성")
    void assignedWorkerRollbackRestoresSnapshot() {
        // D-ISSUE-21 — 픽스처 해시는 반드시 payload 의 실제 SHA-256 이어야 한다(프로덕션 불변식).
        //   구 픽스처(40자 가짜 해시)는 도달 불가한 ROLLBACK 적층 분기를 통과시켜 위양성이었다.
        String pastPayload = "{\"items\":[{\"id\":7,\"label\":\"car\"}]}";
        String pastHash = sha256Hex(pastPayload);
        LsLabelVersion past = seed(pastHash, pastPayload, 1, false);
        seed(sha256Hex("{\"items\":[]}"), "{\"items\":[]}", 2, true);

        LsLabelVersion rollback = versionService.rollback(pastHash, srcSn, workerAssigned);

        // 기대: 새 행 적층이 아니라 대상 스냅샷 행의 재활성.
        assertThat(rollback).isNotNull();
        assertThat(rollback.getLabelVersionSn()).isEqualTo(past.getLabelVersionSn());
        assertThat(rollback.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_APPROVED);
        assertThat(rollback.getLabelPayload()).isEqualTo(pastPayload);
        assertThat(rollback.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(2);
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
        List<List<Double>> pts = LabelResponse.Item.from(after.get(0), null, objectMapper).points();
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
    @DisplayName("신고와_작업락이_함께_걸린_영상_rollback은_412_다 — 응답코드가_잠금상태_오라클이_되지_않는다")
    void rollbackUnderDeidentReportReturns412EvenWhenLocked() {
        // given — 비식별 누락 신고는 작업락과 DE_IDNTF_YN='F' 를 <함께> 세운다. 그런데 락은 6시간 뒤
        //   WorkLockSweepJob 이 회수하고 'F' 는 resolve 까지 남는다. 락을 먼저 보면 같은 영상이
        //   신고 직후엔 409, 락 회수 뒤엔 412 를 주어 응답 코드가 내부 잠금 상태를 알려주게 된다
        //   (CWE-209). 그래서 신고 게이트가 락보다 <먼저>다 (C-ISSUE-22 확정).
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = approvedSnapshotHash();
        LsDataRaw raw = rawRepository.findById(rawSn).orElseThrow();
        raw.markDeidentified("F");
        rawRepository.saveAndFlush(raw);
        workLockService.lockRawForRedeident(rawSn, "1");

        // when / then
        assertThatThrownBy(() -> versionService.rollback(v1Hash, srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);

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
    @DisplayName("REVIEWER_rollback_정상_동작_대상_버전_재활성_적층없음")
    void reviewerRollbackReactivatesTargetVersion() {
        // D-ISSUE-21 — 실제 SHA-256 픽스처 기준(위양성 교정). 새 행이 적층되지 않아야 한다.
        String pastPayload = "{\"items\":[{\"id\":1,\"label\":\"person\"}]}";
        String pastHash = sha256Hex(pastPayload);
        LsLabelVersion past = seed(pastHash, pastPayload, 1, false);
        seed(sha256Hex("{\"items\":[]}"), "{\"items\":[]}", 2, true);

        LsLabelVersion rollback = versionService.rollback(pastHash, srcSn, reviewer);

        assertThat(rollback).isNotNull();
        assertThat(rollback.getLabelVersionSn()).isEqualTo(past.getLabelVersionSn());
        assertThat(rollback.getLabelPayload()).isEqualTo(pastPayload);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(2);
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

    /** trackId/labelId 만 달리한 단일 BBOX 라벨 스냅샷 (그 외 필드는 완전히 동일). */
    private static String axisPayload(String trackIdJson, String labelIdJson) {
        return "{\"frameNo\":4,\"items\":[{\"id\":11,\"lblTypeCd\":\"BBOX\",\"label\":\"person\","
                + "\"labelId\":" + labelIdJson + ",\"points\":[[1.0,1.0],[2.0,2.0]],"
                + "\"trackId\":" + trackIdJson + "}]}";
    }

    @Test
    @DisplayName("R7_버전diff_trackId_만_바뀌면_MODIFIED_감지")
    void diffDetectsTrackIdOnlyChange() {
        // @req R7 — 기존 /diff 도 같은 비교기를 공유하므로 함께 감지된다(의도된 계약 변경).
        String fromHash = "aaaa0007aaaa0007aaaa0007aaaa0007aaaa0007";
        String toHash   = "bbbb0008bbbb0008bbbb0008bbbb0008bbbb0008";
        seed(fromHash, axisPayload("\"T-1\"", "null"), 1, false);
        seed(toHash, axisPayload("\"T-2\"", "null"), 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.labels()).hasSize(1);
        assertThat(resp.labels().get(0).type()).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
        assertThat(resp.labels().get(0).objectId()).isEqualTo("11");
    }

    @Test
    @DisplayName("R7_버전diff_trackId_가_null에서_값으로_바뀌어도_MODIFIED_감지")
    void diffDetectsTrackIdNullToValueChange() {
        String fromHash = "cccc0009cccc0009cccc0009cccc0009cccc0009";
        String toHash   = "dddd000addd0000addd0000addd0000addd0000a";
        seed(fromHash, axisPayload("null", "null"), 1, false);
        seed(toHash, axisPayload("\"T-9\"", "null"), 2, true);

        assertThat(versionService.diff(fromHash, toHash, workerAssigned).labels())
                .singleElement()
                .extracting(LabelDiffDto::type).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
    }

    @Test
    @DisplayName("R7_버전diff_labelId_만_바뀌면_MODIFIED_감지")
    void diffDetectsLabelIdOnlyChange() {
        // labelId 는 JSON 숫자지만 asText 로 문자열 정규화해 비교한다(누락/null 은 null).
        String fromHash = "eeee000beeee000beeee000beeee000beeee000b";
        String toHash   = "ffff000cffff000cffff000cffff000cffff000c";
        seed(fromHash, axisPayload("\"T-1\"", "7"), 1, false);
        seed(toHash, axisPayload("\"T-1\"", "8"), 2, true);

        assertThat(versionService.diff(fromHash, toHash, workerAssigned).labels())
                .singleElement()
                .extracting(LabelDiffDto::type).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
    }

    @Test
    @DisplayName("R7_버전diff_trackId_labelId_가_모두_동일하면_변화없음_회귀가드")
    void diffAxisExtensionKeepsIdenticalPayloadsEmpty() {
        // 비교축 확장이 "항상 MODIFIED" 로 퇴화하지 않았음을 고정한다.
        String same = axisPayload("\"T-1\"", "7");
        String fromHash = "1111000d1111000d1111000d1111000d1111000d";
        String toHash   = "2222000e2222000e2222000e2222000e2222000e";
        seed(fromHash, same, 1, false);
        seed(toHash, same, 2, true);

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

    @Test
    @DisplayName("기존_compareWith_diff_는_손상_payload_에도_빈_리스트_장애격리_유지")
    void diffKeepsFaultIsolationOnCorruptPayload() {
        // TC-DIFF-007 계약 회귀 가드 — diffWithWorking 이 손상 스냅샷을 400 으로 바꾸더라도
        //   기존 두 버전 diff 의 "파싱 실패 → 빈 리스트(장애 격리)" 동작은 그대로여야 한다.
        String fromHash = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111";
        String toHash = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222";
        seed(fromHash, "{not-json", 1, false);
        seed(toHash, "{\"frameNo\":0,\"items\":[{\"id\":1,\"lblTypeCd\":\"BBOX\","
                + "\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}]}", 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.labels()).isEmpty();
    }

    // ---------- diffWithWorking (버전 스냅샷 ↔ 현재 작업본) ----------

    /** 라벨 1건을 시드하고 검수 승인 스냅샷을 만든 뒤 그 versionHash 를 반환. */
    private String approveWith(String label, String pointsJson) {
        seedLabel(srcSn, label, pointsJson);
        versionService.commitApproved(rawSn, reviewer);
        return approvedSnapshotHash();
    }

    /** 영상을 비식별 누락 신고 구간(DE_IDNTF_YN='F')으로 전이. */
    private void markUnderDeidentReport() {
        LsDataRaw raw = rawRepository.findById(rawSn).orElseThrow();
        raw.markDeidentified("F");
        rawRepository.save(raw);
    }

    @Test
    @DisplayName("버전_1건만_있어도_현재_작업본과_diff_가_계산된다")
    void diffWithWorkingNeedsOnlyOneVersion() {
        String v1 = approveWith("person", "[[10.0,10.0],[50.0,50.0]]");
        // 승인 이후 작업본만 변경 — 버전은 여전히 1건뿐이라 기존 compareWith 경로로는 비교 대상이 없다.
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");

        DiffResponseDto resp = versionService.diffWithWorking(v1, workerAssigned);

        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(1);
        assertThat(resp.fromHash()).isEqualTo(v1);
        assertThat(resp.toHash()).isNotEqualTo(v1);
        assertThat(resp.labels()).isNotEmpty();
    }

    @Test
    @DisplayName("승인_이후_추가된_라벨은_ADDED_로_분류된다")
    void diffWithWorkingClassifiesAddedLabel() {
        String v1 = approveWith("person", "[[10.0,10.0],[50.0,50.0]]");
        Long addedSn = labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, "car", "[[1.0,1.0],[2.0,2.0]]", "100")).getLblSn();

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        assertThat(resp.labels()).hasSize(1);
        LabelDiffDto only = resp.labels().get(0);
        assertThat(only.type()).isEqualTo(LabelDiffDto.DiffType.ADDED);
        assertThat(only.objectId()).isEqualTo(String.valueOf(addedSn));
        assertThat(only.before()).isNull();
        assertThat(only.after()).isNotNull();
    }

    @Test
    @DisplayName("승인_이후_삭제된_라벨은_REMOVED_로_분류된다")
    void diffWithWorkingClassifiesRemovedLabel() {
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1 = approvedSnapshotHash();
        LsDataLbl car = labelRepository.findBySrcSn(srcSn).stream()
                .filter(l -> "car".equals(l.getLabelNm())).findFirst().orElseThrow();
        Long removedSn = car.getLblSn();
        labelRepository.delete(car);

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        assertThat(resp.labels()).hasSize(1);
        LabelDiffDto only = resp.labels().get(0);
        assertThat(only.type()).isEqualTo(LabelDiffDto.DiffType.REMOVED);
        assertThat(only.objectId()).isEqualTo(String.valueOf(removedSn));
        assertThat(only.before()).isNotNull();
        assertThat(only.after()).isNull();
    }

    @Test
    @DisplayName("승인_이후_좌표가_바뀐_라벨은_MODIFIED_로_분류된다")
    void diffWithWorkingClassifiesModifiedLabel() {
        String v1 = approveWith("person", "[[10.0,10.0],[50.0,50.0]]");
        LsDataLbl lbl = labelRepository.findBySrcSn(srcSn).get(0);
        lbl.updateUserContent("BBOX", null, "person", "[[20.0,20.0],[60.0,60.0]]");
        labelRepository.save(lbl);

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        assertThat(resp.labels()).hasSize(1);
        LabelDiffDto only = resp.labels().get(0);
        assertThat(only.type()).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
        assertThat(only.objectId()).isEqualTo(String.valueOf(lbl.getLblSn()));
        assertThat(only.before().left()).isEqualTo(10.0);
        assertThat(only.after().left()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("작업본이_스냅샷과_동일하면_힙_조회순서가_뒤집혀도_빈_diff_를_반환한다")
    void diffWithWorkingReturnsEmptyWhenUnchanged() {
        // 오탐 0 고정 — 작업본 payload 는 승인 스냅샷과 완전히 동일한 방식으로 만들어져야 한다.
        //   ★ 3건 시드 후 "중간 라벨의 인덱스 컬럼(TRCK_ID)을 갱신" 하는 이유
        //     (정렬 가드가 실제로 가드하게 만들기):
        //     연속 INSERT 만 하면 PostgreSQL 힙 물리순서 = 삽입순서 = LBL_SN 오름차순이라,
        //     buildWorkingPayload 의 labels.sort(...) 를 통째로 지워도 결과가 같아 아무것도 검증되지
        //     않는다(구 주석 "2건 이상이면 검증된다"는 틀린 논증이었다 — 건수가 아니라 순서 일치가 문제).
        //     ⚠ 비인덱스 컬럼만 바꾸면 HOT update 라 IX_LS_DATA_LBL_SRC 인덱스 엔트리가 그대로 남아
        //       조회 순서가 여전히 오름차순이다(실측). 인덱스 컬럼(TRCK_ID)을 바꿔야 non-HOT 이 되어
        //       새 튜플·새 인덱스 엔트리가 뒤에 붙고 조회 순서가 LBL_SN 오름차순과 어긋난다.
        //     ★ 갱신은 승인 스냅샷 생성 <b>전에</b> 한다 — 그래야 스냅샷과 작업본의 내용이 동일하고
        //       (오탐 0 시나리오 유지) 오직 조회 순서만 흔들린다.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");
        seedLabel(srcSn, "bike", "[[3.0,3.0],[4.0,4.0]]");
        LsDataLbl middle = labelRepository.findBySrcSn(srcSn).stream()
                .sorted(Comparator.comparing(LsDataLbl::getLblSn)).toList().get(1);
        middle.reassignTrack("TRK-REORDER");
        labelRepository.save(middle);
        // 전제 확인 — 힙 조회 순서가 LBL_SN 오름차순과 달라야 이 테스트가 정렬을 가드한다.
        //   같아지면(플랫폼/플랜 차이) 조용히 무력한 가드가 되므로 여기서 명시적으로 실패시킨다.
        List<Long> heapOrder = labelRepository.findBySrcSn(srcSn).stream()
                .map(LsDataLbl::getLblSn).toList();
        assertThat(heapOrder).as("힙 조회 순서가 LBL_SN 오름차순과 달라야 정렬 가드가 성립한다")
                .isNotEqualTo(heapOrder.stream().sorted().toList());

        versionService.commitApproved(rawSn, reviewer);
        String v1 = approvedSnapshotHash();

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        assertThat(resp.labels()).isEmpty();
        // payload 생성 방식(정렬·필드 구성·직렬화)이 동일하므로 재계산 해시까지 일치한다(R6).
        // ← labels.sort(...) 를 제거하면 items 순서가 힙 순서로 흔들려 이 단언이 깨진다(뮤테이션 확인 완료).
        assertThat(resp.toHash()).isEqualTo(v1);
    }

    @Test
    @DisplayName("AI_메타가_있는_라벨도_수정이_없으면_빈_diff_이고_해시가_일치한다")
    void diffWithWorkingIncludesAiMetaInWorkingPayload() {
        // R6 축 가드 — 작업본 payload 는 승인 스냅샷과 "같은 필드"를 담아야 한다.
        //   프로덕션 표준 경로(YOLO 오토라벨)는 LS_DATA_LBL_AI_INFO 행을 갖는데, 수동 라벨 픽스처만
        //   쓰면 양쪽 다 빈 맵이라 loadAiInfo 를 Map.of() 로 바꿔도 아무 테스트가 실패하지 않는다.
        //   ← buildWorkingPayload 의 loadAiInfo(labels) 를 Map.of() 로 치환하면 autoLblYn 이 'Y'→'N',
        //     confScore/lblSrcCd 가 값→null 로 바뀌어 아래 해시 단언이 깨진다(뮤테이션 확인 완료).
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        LsDataLbl auto = labelRepository.findBySrcSn(srcSn).get(0);
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        auto.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.90000"));
        labelRepository.saveAndFlush(auto);
        versionService.commitApproved(rawSn, reviewer);
        String v1 = approvedSnapshotHash();

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        assertThat(resp.labels()).isEmpty();
        assertThat(resp.toHash()).isEqualTo(v1);
    }

    @Test
    @DisplayName("AI_메타만_바뀐_라벨은_변경으로_잡히지_않는다_의도적_제외")
    void diffWithWorkingIgnoresAiMetaOnlyChange() {
        // @req R7 — autoLblYn/confScore/lblSrcCd 는 비교축에서 의도적으로 제외한다.
        //   좌표가 그대로인 채 신뢰도만 달라진 것은 사람의 라벨 편집이 아니고, 부동소수 비교는 잡음 diff 를 낸다.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        LsDataLbl auto = labelRepository.findBySrcSn(srcSn).get(0);
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        auto.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.90000"));
        labelRepository.saveAndFlush(auto);
        versionService.commitApproved(rawSn, reviewer);
        String v1 = approvedSnapshotHash();
        auto.updateConfScore(new BigDecimal("0.50000"));
        labelRepository.saveAndFlush(auto);

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        // 라벨 단위 변경으로는 보고하지 않는다(제외축).
        assertThat(resp.labels()).isEmpty();
        // 다만 payload 자체는 달라졌다 — "아무것도 안 바뀌었다"가 아니라 "라벨 편집이 아니다"라는 뜻.
        assertThat(resp.toHash()).isNotEqualTo(v1);
    }

    @Test
    @DisplayName("R7_트랙_병합처럼_trackId_만_바뀌어도_MODIFIED_로_잡힌다")
    void diffWithWorkingDetectsTrackIdOnlyChange() {
        // @req R7 — TrackMergeService.doMerge 는 reassignTrack 만 수행한다(좌표·타입·라벨명·LBL_SN 불변).
        //   시스템은 이를 TaskModifiedEvent(LABEL_UPDATED)로 인정해 export 를 재생성·재통지하는데,
        //   diff 만 "변경 없음"이라고 답하면 모순이다. null → 값 전이도 변경으로 잡혀야 한다.
        String v1 = approveWith("person", "[[10.0,10.0],[50.0,50.0]]");
        LsDataLbl lbl = labelRepository.findBySrcSn(srcSn).get(0);
        assertThat(lbl.getTrackId()).isNull();
        lbl.reassignTrack("TRK-MERGED-2");
        labelRepository.save(lbl);

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        assertThat(resp.labels()).hasSize(1);
        assertThat(resp.labels().get(0).type()).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
        assertThat(resp.labels().get(0).objectId()).isEqualTo(String.valueOf(lbl.getLblSn()));
    }

    @Test
    @DisplayName("ADDED_REMOVED_MODIFIED_가_동시에_섞인_작업본도_모두_분류된다")
    void diffWithWorkingClassifiesMixedChanges() {
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");
        versionService.commitApproved(rawSn, reviewer);
        String v1 = approvedSnapshotHash();

        List<LsDataLbl> seeded = labelRepository.findBySrcSn(srcSn).stream()
                .sorted(Comparator.comparing(LsDataLbl::getLblSn)).toList();
        LsDataLbl modified = seeded.get(0);
        modified.updateUserContent("BBOX", null, "person", "[[20.0,20.0],[60.0,60.0]]");
        labelRepository.save(modified);
        LsDataLbl removed = seeded.get(1);
        labelRepository.delete(removed);
        Long addedSn = labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, "bike", "[[3.0,3.0],[4.0,4.0]]", "100")).getLblSn();

        DiffResponseDto resp = versionService.diffWithWorking(v1, reviewer);

        assertThat(resp.labels()).hasSize(3);
        assertThat(resp.labels())
                .extracting(l -> l.type() + ":" + l.objectId())
                .containsExactlyInAnyOrder(
                        LabelDiffDto.DiffType.MODIFIED + ":" + modified.getLblSn(),
                        LabelDiffDto.DiffType.REMOVED + ":" + removed.getLblSn(),
                        LabelDiffDto.DiffType.ADDED + ":" + addedSn);
    }

    @Test
    @DisplayName("비식별_신고_구간_영상은_PRECONDITION_FAILED_412_를_반환한다")
    void diffWithWorkingBlockedUnderDeidentReport() {
        String v1 = approveWith("person", "[[10.0,10.0],[50.0,50.0]]");
        markUnderDeidentReport();

        assertThatThrownBy(() -> versionService.diffWithWorking(v1, reviewer))
                .isInstanceOf(CustomException.class)
                // CWE-359 — 거부 메시지에 라벨 좌표(PII 위치 특정 정보)가 실려선 안 된다.
                .hasMessageNotContaining("10.0")
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("미배정_WORKER_요청은_FORBIDDEN_403_을_반환한다")
    void diffWithWorkingForbiddenForUnassignedWorker() {
        String v1 = approveWith("person", "[[10.0,10.0],[50.0,50.0]]");
        TokenClaims unassigned = new TokenClaims("999", Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> versionService.diffWithWorking(v1, unassigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지_않는_해시는_NOT_FOUND_404_를_반환한다")
    void diffWithWorkingUnknownHashNotFound() {
        assertThatThrownBy(() -> versionService.diffWithWorking(
                "ffffffffffffffffffffffffffffffffffffffff", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("DATA_SRC_SN_이_NULL_인_레거시_버전은_INVALID_INPUT_400_을_반환한다")
    void diffWithWorkingRejectsRawScopedLegacyVersion() {
        // D-ISSUE-26 — 구 비식별 신고 rawSn 스코프 스냅샷은 프레임 단위 비교 대상이 아니다.
        String legacyHash = "0123456789abcdef0123456789abcdef01234567";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, null, legacyHash,
                "{\"items\":[]}", 1, LsLabelVersion.SAVE_REASON_APPROVED, "1"));

        assertThatThrownBy(() -> versionService.diffWithWorking(legacyHash, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("해시가_비-hex_이거나_길이가_다르면_INVALID_INPUT_400_을_반환한다")
    void diffWithWorkingRejectsMalformedHash() {
        assertThatThrownBy(() -> versionService.diffWithWorking("not-a-hash-../etc/passwd", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> versionService.diffWithWorking("a".repeat(65), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("저장된_스냅샷_payload_가_손상되면_빈_diff_대신_명시적_오류를_던진다")
    void diffWithWorkingRejectsCorruptSnapshot() {
        // HIGH #5 — 이 엔드포인트의 빈 결과는 화면에서 "변경 없음"으로 표시된다.
        //   손상 스냅샷을 빈 리스트로 삼키면 그 표시가 거짓말이 되므로 명시적으로 실패해야 한다.
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        String badHash = "abcdef0123456789abcdef0123456789abcdef01";
        seed(badHash, "{not-json", 1, true);

        assertThatThrownBy(() -> versionService.diffWithWorking(badHash, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("items_가_명시적_배열이_아닌_스냅샷은_전부_INVALID_INPUT_400")
    void diffWithWorkingRejectsNonArrayItems() {
        // 구 조건(!isMissingNode && !isNull && !isArray)은 누락·null 을 화이트리스트로 통과시켜
        //   parseLabelsById 가 빈 맵을 만들었고, 그 결과 작업본 라벨이 전량 ADDED 로 과대보고됐다.
        //   "변경 없음 거짓말"의 반대 방향 거짓 표시라 같은 결함이다 → allowlist 로 반전(fail-closed).
        seedLabel(srcSn, "person", "[[10.0,10.0],[50.0,50.0]]");
        String[] hashes = {
                "1000aaaa1000aaaa1000aaaa1000aaaa1000aaaa",   // {}            (items 누락)
                "2000bbbb2000bbbb2000bbbb2000bbbb2000bbbb",   // {"items":null}
                "3000cccc3000cccc3000cccc3000cccc3000cccc",   // {"items":"x"} (스칼라)
                "4000dddd4000dddd4000dddd4000dddd4000dddd",   // {"items":{}}  (객체)
                "5000eeee5000eeee5000eeee5000eeee5000eeee"};  // 123           (스칼라 root)
        String[] payloads = {"{}", "{\"items\":null}", "{\"items\":\"x\"}", "{\"items\":{}}", "123"};
        for (int i = 0; i < hashes.length; i++) {
            seed(hashes[i], payloads[i], i + 1, false);
        }

        for (String hash : hashes) {
            assertThatThrownBy(() -> versionService.diffWithWorking(hash, reviewer))
                    .as("payload=%s", hash)
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("스냅샷_payload_가_비어있으면_손상이_아니라_라벨0건으로_비교된다")
    void diffWithWorkingTreatsBlankSnapshotAsZeroLabels() {
        // blank/null 은 "라벨 0건" 이라는 정상 상태 — 손상과 구분해야 한다.
        String emptyHash = "0000111122223333444455556666777788889999";
        seed(emptyHash, "", 1, true);
        Long addedSn = labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, "car", "[[1.0,1.0],[2.0,2.0]]", "100")).getLblSn();

        DiffResponseDto resp = versionService.diffWithWorking(emptyHash, reviewer);

        assertThat(resp.labels()).hasSize(1);
        assertThat(resp.labels().get(0).type()).isEqualTo(LabelDiffDto.DiffType.ADDED);
        assertThat(resp.labels().get(0).objectId()).isEqualTo(String.valueOf(addedSn));
    }

    @Test
    @DisplayName("작업본_diff_조회는_새_버전_행을_적층하지_않는다")
    void diffWithWorkingDoesNotPersistVersion() {
        String v1 = approveWith("person", "[[10.0,10.0],[50.0,50.0]]");
        seedLabel(srcSn, "car", "[[1.0,1.0],[2.0,2.0]]");
        long before = labelVersionRepository.count();

        versionService.diffWithWorking(v1, reviewer);

        assertThat(labelVersionRepository.count()).isEqualTo(before);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(1);
    }

}
