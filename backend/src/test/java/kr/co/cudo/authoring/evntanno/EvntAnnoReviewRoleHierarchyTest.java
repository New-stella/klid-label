package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvntAnnoReviewService} — <b>역할 계층(관리자 &gt; 검수자) 반영</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <h3>고정하는 것</h3>
 * <p>event_annotation 승인·반려 창구의 「검수자 전용」은 <b>「검수자 이상」</b>이다 — 관리자는 계층으로
 * 물려받아 그대로 들어간다. 동등 비교로 두면 관리자가 검수 업무의 이 한 조각에서만 403 이 되어
 * 계층이 반쪽만 성립한다.
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <p>「관리자가 통과한다」만 두면 게이트를 통째로 지워도 초록이므로, 같은 창구에서 <b>작업자·포털
 * 회원이 여전히 거부된다</b>는 축을 짝으로 둔다. 통과 쪽은 상태코드가 아니라 <b>실제 전이가
 * 일어났는지</b>(승인·반려 호출)로 확인해 "게이트만 지나고 아무 일도 안 함"을 배제한다.
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>{@code !actor.hasRole(Role.REVIEWER)} 를 {@code actor.role() != Role.REVIEWER} 로 되돌리면
 * 관리자 시험 2건이 {@code FORBIDDEN} 으로 FAILED 가 된다.
 */
class EvntAnnoReviewRoleHierarchyTest {

    private static final long RAW_SN = 610L;
    private static final long EVNT_ANNO_SN = 910L;

    private LsEvntAnnoReviewRepository reviewRepository;
    private LsEvntAnnoReview review;
    private EvntAnnoReviewService service;

    @BeforeEach
    void setUp() {
        LsEvntAnnoRepository annoRepository = mock(LsEvntAnnoRepository.class);
        reviewRepository = mock(LsEvntAnnoReviewRepository.class);
        ReviewApprovalGate approvalGate = mock(ReviewApprovalGate.class);
        LsDatasetVideoMetaRepository videoMetaRepository = mock(LsDatasetVideoMetaRepository.class);
        DatasetVideoMetaSnapshotService snapshotService = mock(DatasetVideoMetaSnapshotService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        // 목 기본값(아무 것도 안 함)이 "신고 없음" 통과를 뜻한다.
        LabelAccessGuard accessGuard = mock(LabelAccessGuard.class);

        service = new EvntAnnoReviewService(annoRepository, reviewRepository, approvalGate,
                videoMetaRepository, snapshotService, eventPublisher, accessGuard);

        LsEvntAnno anno = mock(LsEvntAnno.class);
        when(anno.getEvntAnnoSn()).thenReturn(EVNT_ANNO_SN);
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(anno));
        review = mock(LsEvntAnnoReview.class);
        when(review.getRvwSn()).thenReturn(1L);
        when(reviewRepository.findByEvntAnnoSn(EVNT_ANNO_SN)).thenReturn(List.of(review));
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
    @DisplayName("★관리자는_event_annotation_을_승인할_수_있다_검수자_전용은_검수자_이상이다")
    void adminApproves() {
        service.approve(RAW_SN, internal("969300051", Role.ADMIN));

        // 게이트만 지나고 아무 일도 안 하는 상태를 배제한다 — 실제 전이가 일어나야 한다.
        verify(review).approve("969300051");
    }

    @Test
    @DisplayName("★관리자는_event_annotation_을_반려할_수_있다_검수자_전용은_검수자_이상이다")
    void adminRejects() {
        service.reject(RAW_SN, "사유", internal("969300051", Role.ADMIN));

        verify(review).reject("969300051", "사유");
    }

    // ---------- 대조군 — 기존 정책 불변 ----------

    @Test
    @DisplayName("검수자는_종전대로_승인한다")
    void reviewerStillApproves() {
        assertThatCode(() -> service.approve(RAW_SN, internal("11", Role.REVIEWER)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("작업자는_여전히_403이다_계층이_검수_창구를_작업자에게_열지_않는다")
    void workerForbidden() {
        assertThatThrownBy(() -> service.approve(RAW_SN, internal("100", Role.WORKER)))
                .isInstanceOf(CustomException.class)
                .extracting(EvntAnnoReviewRoleHierarchyTest::errorCodeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.reject(RAW_SN, "사유", internal("100", Role.WORKER)))
                .isInstanceOf(CustomException.class)
                .extracting(EvntAnnoReviewRoleHierarchyTest::errorCodeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("포털_회원은_여전히_403이다_계층이_채널_격리를_뚫지_않는다")
    void portalUserForbidden() {
        assertThatThrownBy(() -> service.approve(RAW_SN, actor("50000", Role.PORTAL_USER, Channel.PORTAL)))
                .isInstanceOf(CustomException.class)
                .extracting(EvntAnnoReviewRoleHierarchyTest::errorCodeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("역할_미배정_토큰은_거부된다_fail_closed")
    void nullRoleForbidden() {
        assertThatThrownBy(() -> service.approve(RAW_SN, actor("300", null, Channel.INTERNAL)))
                .isInstanceOf(CustomException.class)
                .extracting(EvntAnnoReviewRoleHierarchyTest::errorCodeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
