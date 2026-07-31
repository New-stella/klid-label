package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.assignment.service.TaskBoardService;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>파생영상 등재 게이트</b> — 미검수 파생은 <b>목록에도 배정에도</b> 나타나지 않는다.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>차단</b> — 검수 승인 전 파생은 작업목록/배정목록에서 빠지고, 배정 API 직접 호출도 거부된다(S1)</li>
 *   <li><b>해제</b> — {@code LS_DATA_AUG_RVW} 가 ACCEPTED 가 되면 즉시 등재된다</li>
 *   <li><b>회귀 0</b> — 원본 영상 건수는 게이트 전후로 <b>불변</b>이다(S3 — 조인/술어 오타로 전멸하는 사고 방지)</li>
 *   <li><b>예외</b> — 해상도 파생(RESL_*)은 검수 행이 영영 생기지 않으므로 통과해야 한다</li>
 *   <li><b>그랜드퍼더링</b> — V149 이전 파생(NEW_RAW_SN 매핑 없음)은 통과한다(S2 — 고아 배정 방지)</li>
 *   <li><b>깊이 무관</b> — 손자 파생도 예외 없이 <b>자기 행</b>으로만 판정한다(S5 — 조상 순회 금지)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DerivativeWorkEnrollmentGateTest {

    @Autowired private TaskBoardService taskBoardService;
    @Autowired private AssignmentService assignmentService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugRvwRepository reviewRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    /** 결정 API — 사전승인 차단(HIGH)이 게이트와 함께 성립하는지 실제 서비스로 검증한다. */
    @Autowired private AugmentReviewService reviewService;
    @Autowired private LsDataSrcRepository srcRepository;

    /** test-data.sql 시드 작업자/검수자. */
    private static final long WORKER_NO = 100L;
    /** 재배정 대상 작업자(test-data.sql 시드). */
    private static final long OTHER_WORKER_NO = 101L;

    private static final AtomicLong CLIP_SEQ = new AtomicLong(System.nanoTime());

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private TokenClaims worker() {
        return new TokenClaims(String.valueOf(WORKER_NO), Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
    }

    // ---------------------------------------------------------------- fixtures

    /** 원본 영상 1건(배치 완료 — 작업목록 기본 필터 대상). */
    private LsDataRaw seedOriginal() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "GATE-" + CLIP_SEQ.incrementAndGet(), "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/nas-storage/raw/gate.mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        return videoRepository.saveAndFlush(raw);
    }

    /**
     * 파생 영상 1건 + 그것을 만든 증강 행(매핑 포함) — 실제 생성 경로와 동일한 순서로 시드한다
     * (증강 행 INSERT → 파생 RAW INSERT → 같은 트랜잭션에서 NEW_RAW_SN 배정).
     */
    private LsDataRaw seedDerivative(LsDataRaw parent, String augType) {
        LsDataAug aug = augRepository.saveAndFlush(
                LsDataAug.createPending(9_000_000L + CLIP_SEQ.incrementAndGet(), augType,
                        new BigDecimal("90.00"), "system"));
        LsDataRaw derivative = videoRepository.save(LsDataRaw.createFromAugment(
                parent, "/nas-storage/deidentified/videos/augment/derived.mp4",
                augType, aug.getDataAugSn()));
        aug.assignDerivativeRawSn(derivative.getRawSn());
        derivative.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        return videoRepository.saveAndFlush(derivative);
    }

    /** V149 이전 생성분 재현 — 파생 RAW 는 있지만 이를 가리키는 증강 매핑이 없다. */
    private LsDataRaw seedLegacyDerivative(LsDataRaw parent) {
        LsDataRaw derivative = videoRepository.save(LsDataRaw.createFromAugment(
                parent, "/nas-storage/deidentified/videos/augment/legacy.mp4",
                LsDataAug.AUG_WINTER, 999_000L + CLIP_SEQ.incrementAndGet()));
        derivative.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        return videoRepository.saveAndFlush(derivative);
    }

    /** 그 파생을 만든 증강 행에 REVIEWER 의 <b>사용 결정(ACCEPTED)</b> 을 남긴다. */
    private void approveDerivative(LsDataRaw parent, LsDataRaw derivative) {
        LsDataAug aug = augRepository.findAll().stream()
                .filter(a -> derivative.getRawSn().equals(a.getNewRawSn()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("증강 매핑이 없다 — 픽스처 오류"));
        reviewRepository.saveAndFlush(LsDataAugRvw.createAccepted(
                aug.getDataAugSn(), parent.getRawSn(), aug.getSrcSn(),
                new BigDecimal("95.00"), "1", LocalDateTime.now()));
    }

    private List<Long> boardRawSns() {
        Page<TaskBoardItemResponse> page = taskBoardService.listBoard(
                TaskBoardSearchCondition.defaults(), reviewer(), PageRequest.of(0, 500));
        return page.getContent().stream().map(TaskBoardItemResponse::videoId).toList();
    }

    // ---------------------------------------------------------------- 목록 차단

    @Test
    @DisplayName("승인_전_파생영상은_작업목록에_나오지_않는다")
    void unapprovedDerivativeHiddenFromBoard() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_WINTER);

        assertThat(boardRawSns())
                .contains(parent.getRawSn())
                .doesNotContain(derivative.getRawSn());
    }

    @Test
    @DisplayName("승인하면_작업목록에_등재된다")
    void approvedDerivativeAppearsOnBoard() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_NIGHT);
        assertThat(boardRawSns()).doesNotContain(derivative.getRawSn());

        approveDerivative(parent, derivative);

        assertThat(boardRawSns()).contains(derivative.getRawSn());
    }

    @Test
    @DisplayName("원본영상은_게이트의_영향을_받지_않는다")
    void originalVideosUnaffectedByGate() {
        // given — 게이트 도입 전 기준선(원본 N건)
        List<Long> baseline = boardRawSns();

        LsDataRaw originalA = seedOriginal();
        LsDataRaw originalB = seedOriginal();
        LsDataRaw originalC = seedOriginal();
        // 미등재 파생 2건(M) — 목록에서 빠져야 한다
        seedDerivative(originalA, LsDataAug.AUG_WINTER);
        seedDerivative(originalB, LsDataAug.AUG_RAIN);
        // 등재 파생 1건(K) — 목록에 남아야 한다
        LsDataRaw enrolled = seedDerivative(originalC, LsDataAug.AUG_NIGHT);
        approveDerivative(originalC, enrolled);

        // when
        List<Long> after = boardRawSns();

        // then — 정확히 baseline + 원본 3 + 등재 파생 1
        assertThat(after).hasSize(baseline.size() + 4);
        // 기준선의 원본이 <단 1건도> 사라지지 않는다 (S3 — .or 를 .and 로 잘못 쓰면 여기서 전멸한다)
        assertThat(after).containsAll(baseline);
        assertThat(after).contains(originalA.getRawSn(), originalB.getRawSn(), originalC.getRawSn(),
                enrolled.getRawSn());
    }

    @Test
    @DisplayName("해상도파생은_게이트를_통과한다")
    void resolutionDerivativePassesGate() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw resolution = seedDerivative(parent, LsDataAug.AUG_RESL_720P);

        // 해상도 파생은 accept/reject 진입 자체가 차단돼 검수 행이 영영 생기지 않는다.
        // 예외가 없으면 해상도 파생 전량이 작업목록에서 사라진다.
        assertThat(boardRawSns()).contains(resolution.getRawSn());
    }

    @Test
    @DisplayName("게이트_도입_이전_파생은_그랜드퍼더링으로_통과한다")
    void legacyDerivativeGrandfathered() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw legacy = seedLegacyDerivative(parent);

        assertThat(boardRawSns()).contains(legacy.getRawSn());
    }

    @Test
    @DisplayName("손자파생_조회시_예외없이_자기행으로만_판정된다")
    void grandchildDerivativeJudgedByItsOwnRowOnly() {
        // given — 깊이 2 (원본 → 파생 → 손자 파생). 손자만 승인한다.
        LsDataRaw parent = seedOriginal();
        LsDataRaw child = seedDerivative(parent, LsDataAug.AUG_WINTER);       // 미승인
        LsDataRaw grandchild = seedDerivative(child, LsDataAug.AUG_NIGHT);    // 승인
        approveDerivative(child, grandchild);

        // when / then — 조상 체인을 순회하지 않으므로 예외 없이 각자 자기 행으로만 판정된다.
        //               (부모가 미승인이라고 손자를 함께 막지 않는다 — 조상 전파는 철회된 정책이다)
        assertThatCode(this::boardRawSns).doesNotThrowAnyException();
        assertThat(boardRawSns())
                .contains(grandchild.getRawSn())
                .doesNotContain(child.getRawSn());
    }

    // ------------------------------------------- 사전승인으로 게이트를 무력화할 수 없다 (HIGH)

    /**
     * <b>결과가 생기기 전에 미리 승인해 게이트를 통과시킬 수 없다</b> (2026-07-31 DEV_FIX HIGH).
     *
     * <p>적대검증이 실증한 경로를 그대로 재현한다: ①증강 요청(결과물 0건) ②그 상태에서 승인
     * ③뒤늦게 콜백 도착 → 파생영상 생성 ④게이트는 "ACCEPTED 검수 행이 있다" 만 보고 통과.
     * 그러면 사람이 <b>결과 이미지를 한 번도 보지 않은</b> 파생영상이 작업목록에 오르고 배정된다.
     *
     * <p>차단 지점은 ②다 — 결정 사전조건이 결과물 실재를 요구한다. 게이트 술어는 바꾸지 않는다
     * (게이트는 "사람이 사용하기로 했는가" 만 판정하고, "언제 결정할 수 있는가" 는 결정 API 의 책임).
     */
    @Test
    @DisplayName("결과물이_생기기_전에_승인해서_미검수_파생을_등재시킬_수_없다")
    void preApprovalBeforeResultCannotEnrollDerivative() {
        LsDataRaw parent = seedOriginal();

        // ① 요청만 접수된 증강 행(결과물 없음). 검수 이력은 대표프레임 → 원본 RAW_SN 을 역해석해
        //    적으므로 프레임이 실재해야 한다(실 운영 형상).
        Long representativeSrcSn = srcRepository.saveAndFlush(
                LsDataSrc.create(parent.getRawSn(), 0, "/storage/raw/gate-pre.jpg", null)).getSrcSn();
        LsDataAug requested = augRepository.saveAndFlush(
                LsDataAug.createPending(representativeSrcSn,
                        LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));

        // ② 그 상태에서 승인 시도 → 거부되어야 한다(여기서 막지 못하면 ④가 뚫린다)
        assertThatThrownBy(() -> reviewService.accept(requested.getDataAugSn(), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // ③ 뒤늦게 콜백이 도착해 파생영상이 실제로 만들어진다(웹훅 경로와 동일 순서)
        requested.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        LsDataRaw derivative = videoRepository.save(LsDataRaw.createFromAugment(
                parent, "/nas-storage/deidentified/videos/augment/late.mp4",
                LsDataAug.AUG_WINTER, requested.getDataAugSn()));
        requested.assignDerivativeRawSn(derivative.getRawSn());
        derivative.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        videoRepository.saveAndFlush(derivative);
        augRepository.saveAndFlush(requested);

        // ④ 사전 승인이 성립하지 않았으므로 등재도 배정도 되지 않는다.
        assertThat(boardRawSns()).doesNotContain(derivative.getRawSn());
        assertThatThrownBy(() -> assignmentService.assign(
                new AssignmentCreateRequest(WORKER_NO, List.of(derivative.getRawSn())), reviewer()))
                .isInstanceOf(CustomException.class);

        // 그리고 이제서야(결과물이 실재하는 지금) 승인하면 정상 등재된다 — 가드가 워크플로를
        // 영구히 막는 것이 아니라 <순서>를 강제한다는 반증.
        reviewService.accept(requested.getDataAugSn(), reviewer());
        assertThat(boardRawSns()).contains(derivative.getRawSn());
    }

    // ---------------------------------------------------------------- 배정 차단 (S1)

    @Test
    @DisplayName("승인_전_파생영상은_배정할_수_없다")
    void unapprovedDerivativeCannotBeAssigned() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_WINTER);

        // 목록 API 를 거치지 않고 배정 API 를 직접 호출한다 — 목록 필터는 인가가 아니다.
        AssignmentCreateRequest req =
                new AssignmentCreateRequest(WORKER_NO, List.of(derivative.getRawSn()));

        assertThatThrownBy(() -> assignmentService.assign(req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(assignmentRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                LsTaskAssignment.TASK_LABELER, List.of(derivative.getRawSn()))).isEmpty();
    }

    @Test
    @DisplayName("미등재_파생이_섞이면_같은_요청의_원본_배정도_전부_거부된다")
    void mixedRequestIsRejectedEntirely() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_RAIN);

        AssignmentCreateRequest req = new AssignmentCreateRequest(
                WORKER_NO, List.of(parent.getRawSn(), derivative.getRawSn()));

        assertThatThrownBy(() -> assignmentService.assign(req, reviewer()))
                .isInstanceOf(CustomException.class);

        // 부분성공 금지 — 원본도 배정되지 않는다(기존 APPROVED 가드와 동일 정책).
        assertThat(assignmentRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                LsTaskAssignment.TASK_LABELER, List.of(parent.getRawSn()))).isEmpty();
    }

    @Test
    @DisplayName("승인된_파생영상은_배정되고_작업자_배정목록에도_보인다")
    void approvedDerivativeAssignableAndVisibleInAssignmentList() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_NIGHT);
        approveDerivative(parent, derivative);

        AssignmentResponse response = assignmentService.assign(
                new AssignmentCreateRequest(WORKER_NO, List.of(derivative.getRawSn())), reviewer());
        assertThat(response.items()).hasSize(1);

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), worker(), PageRequest.of(0, 100));
        assertThat(page.getContent()).extracting(AssignmentResponse.Item::rawDataId)
                .contains(derivative.getRawSn());
    }

    /**
     * <b>재배정도 배정 축이다</b> (2026-07-31 DEV_FIX) — 미등재 파생은 작업자 교체도 막는다.
     *
     * <p>구 구현은 "차단하면 레거시 파생이 락아웃" 이라며 WARN 만 남겼는데 그 근거는 성립하지 않는다:
     * 레거시(매핑 없음) 파생은 게이트를 <b>통과</b>하고(아래 음성 케이스), 실제로 걸리는 미등재 파생은
     * 이미 목록에서 숨겨져 <b>이미 락아웃 상태</b>라 열어 둬도 구제되는 대상이 없다.
     */
    @Test
    @DisplayName("미등재_파생에_남은_배정은_재배정도_거부된다")
    void unenrolledDerivativeCannotBeReassigned() {
        LsDataRaw parent = seedOriginal();
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_WINTER);
        // 게이트 이전에 만들어진 배정(정합 이상)을 리포지토리로 직접 심는다.
        LsTaskAssignment stale = assignmentRepository.saveAndFlush(
                LsTaskAssignment.createLabeler(derivative.getRawSn(), WORKER_NO, 1L));

        assertThatThrownBy(() -> assignmentService.reassign(
                stale.getAssignmentId(), new ReassignRequest(OTHER_WORKER_NO), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 작업자는 그대로 — 이동하지 않았다.
        assertThat(assignmentRepository.findById(stale.getAssignmentId()).orElseThrow().getUserNo())
                .isEqualTo(WORKER_NO);
    }

    @Test
    @DisplayName("승인된_파생과_레거시_파생은_재배정이_막히지_않는다")
    void enrolledAndLegacyDerivativesRemainReassignable() {
        LsDataRaw parent = seedOriginal();

        // ① 승인된 파생 — 게이트 통과
        LsDataRaw enrolled = seedDerivative(parent, LsDataAug.AUG_NIGHT);
        approveDerivative(parent, enrolled);
        LsTaskAssignment onEnrolled = assignmentRepository.saveAndFlush(
                LsTaskAssignment.createLabeler(enrolled.getRawSn(), WORKER_NO, 1L));

        // ② V149 이전 파생(매핑 없음) — 그랜드퍼더링 통과. "차단하면 락아웃" 근거가 성립하지
        //    않는다는 반증이다(애초에 여기서 걸리지 않는다).
        LsDataRaw legacy = seedLegacyDerivative(parent);
        LsTaskAssignment onLegacy = assignmentRepository.saveAndFlush(
                LsTaskAssignment.createLabeler(legacy.getRawSn(), WORKER_NO, 1L));

        assertThatCode(() -> assignmentService.reassign(
                onEnrolled.getAssignmentId(), new ReassignRequest(OTHER_WORKER_NO), reviewer()))
                .doesNotThrowAnyException();
        assertThatCode(() -> assignmentService.reassign(
                onLegacy.getAssignmentId(), new ReassignRequest(OTHER_WORKER_NO), reviewer()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("미등재_파생에_남아있던_배정은_작업자_배정목록에서도_숨겨진다")
    void unenrolledDerivativeAssignmentHiddenFromAssignmentList() {
        // given — 게이트 이전에 만들어진 배정(정합 이상)을 리포지토리로 직접 심는다.
        LsDataRaw parent = seedOriginal();
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_WINTER);
        assignmentRepository.saveAndFlush(
                LsTaskAssignment.createLabeler(derivative.getRawSn(), WORKER_NO, 1L));

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), worker(), PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(AssignmentResponse.Item::rawDataId)
                .doesNotContain(derivative.getRawSn());
    }
}
