package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.MetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MetaService} — <b>역할 계층(관리자 &gt; 검수자) 반영</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <h3>고정하는 자리 두 곳</h3>
 * <ul>
 *   <li><b>프레임 메타 접근</b>({@code verifyAccess}) — 검수자/작업자 분기 뒤에 거부가 오는 형태라
 *       동등 비교면 관리자가 fall-through 로 떨어져 메타 탭이 통째로 403 이 된다.</li>
 *   <li><b>메타 검토 승인·반려</b>({@code ensureReviewer}) — 창구의 「검수자 전용」은 「검수자 이상」이라
 *       관리자가 그대로 들어간다.</li>
 * </ul>
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <p>관리자에게 <b>배정 행을 주지 않고</b>, 나아가 배정 리포지토리가 한 번도 호출되지 않았음까지
 * 확인한다 — 검수자 분기에서 즉시 끝났음을(= 작업자 전용 자리에 흘러들지 않았음을) 고정한다.
 * 통과 단언만 두면 가드를 무조건-통과로 무력화해도 초록이므로 거부 축(포털 회원·작업자)을 함께 둔다.
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>세 지점을 동등 비교로 되돌리면 이 클래스의 관리자 시험 3건이 {@code FORBIDDEN} 으로 FAILED 가 된다.
 */
class MetaServiceRoleHierarchyTest {

    private static final long SRC_SN = 7101L;
    private static final long RAW_SN = 8101L;
    private static final long META_REVIEW_SN = 9101L;

    private LsDataMetaRepository metaRepository;
    private LsDataSrcRepository srcRepository;
    private LsTaskAssignmentRepository assignmentRepository;
    private LsDataMetaReviewRepository metaReviewRepository;
    private MetaService service;

    @BeforeEach
    void setUp() {
        metaRepository = mock(LsDataMetaRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        assignmentRepository = mock(LsTaskAssignmentRepository.class);
        metaReviewRepository = mock(LsDataMetaReviewRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ReviewApprovalGate approvalGate = mock(ReviewApprovalGate.class);

        service = new MetaService(metaRepository, srcRepository, assignmentRepository,
                metaReviewRepository, eventPublisher, approvalGate);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "frames/raw/" + RAW_SN + "/0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());
        // ★ 관리자에게 배정 행을 주지 않는다 — 있으면 작업자 분기로도 통과해 계층이 증명되지 않는다.
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(false);

        LsDataMetaReview review = LsDataMetaReview.createAuto(
                5555L, RAW_SN, null, LsDataMetaReview.META_TYPE_EXTERNAL, null,
                LsDataMetaReview.STTS_PENDING);
        ReflectionTestUtils.setField(review, "dataMetaReviewSn", META_REVIEW_SN);
        when(metaReviewRepository.findById(META_REVIEW_SN)).thenReturn(Optional.of(review));
    }

    private static TokenClaims actor(String sub, Role role, Channel channel) {
        return new TokenClaims(sub, role, channel, Instant.now().plusSeconds(600));
    }

    private static TokenClaims internal(String sub, Role role) {
        return actor(sub, role, Channel.INTERNAL);
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ---------- 계층 본체 ----------

    @Test
    @DisplayName("★관리자는_배정이_없어도_프레임_메타를_조회한다_계층")
    void adminReadsFrameMetaWithoutAssignment() {
        assertThatCode(() -> service.getByFrame(SRC_SN, internal("969300041", Role.ADMIN)))
                .doesNotThrowAnyException();

        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("★관리자는_메타_검토를_승인할_수_있다_검수자_전용은_검수자_이상이다")
    void adminApprovesMetaReview() {
        assertThatCode(() -> service.approveReview(META_REVIEW_SN, internal("969300041", Role.ADMIN)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★관리자는_메타_검토를_반려할_수_있다_검수자_전용은_검수자_이상이다")
    void adminRejectsMetaReview() {
        assertThatCode(() -> service.rejectReview(META_REVIEW_SN, "사유", internal("969300041", Role.ADMIN)))
                .doesNotThrowAnyException();
    }

    // ---------- 대조군 — 기존 정책 불변 ----------

    @Test
    @DisplayName("검수자는_종전대로_통과한다")
    void reviewerStillPasses() {
        assertThatCode(() -> service.getByFrame(SRC_SN, internal("1", Role.REVIEWER)))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.approveReview(META_REVIEW_SN, internal("1", Role.REVIEWER)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("작업자는_메타_검토_승인_창구에서_여전히_403이다_계층이_그_자리를_무르게_하지_않는다")
    void workerCannotApproveMetaReview() {
        assertThatThrownBy(() -> service.approveReview(META_REVIEW_SN, internal("100", Role.WORKER)))
                .isInstanceOf(CustomException.class)
                .extracting(MetaServiceRoleHierarchyTest::errorCodeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("포털_회원은_프레임_메타_접근이_차단된다_계층이_채널_격리를_뚫지_않는다")
    void portalUserForbidden() {
        assertThatThrownBy(() -> service.getByFrame(SRC_SN, actor("50000", Role.PORTAL_USER, Channel.PORTAL)))
                .isInstanceOf(CustomException.class)
                .extracting(MetaServiceRoleHierarchyTest::errorCodeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
