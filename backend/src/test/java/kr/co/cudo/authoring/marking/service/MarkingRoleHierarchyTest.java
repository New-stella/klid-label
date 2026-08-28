package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 마킹 창구의 <b>영상 단위 접근 가드 — 역할 계층(관리자 &gt; 검수자) 반영</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <h3>고정하는 것</h3>
 * <p>{@code MarkingGuards.requireAssignedOrReviewer} 는 「검수자 분기 → 배정 검사 → 거부」 형태다.
 * 역할을 동등 비교하면 관리자가 검수자 분기에 걸리지 못하고 배정 검사로 떨어지며, 관리자에게는
 * 배정 행이 없으므로 403 이 된다. 창구의 「검수자 전용」은 「검수자 이상」으로 읽어야 하므로
 * 관리자는 배정 없이 통과해야 한다. Spring 의 권한 계층은 authority 축에만 걸려 이 자리를 덮지 못한다.
 *
 * <h3>★ 시험이 헛돌지 않게 하는 장치 — 거부 사유가 둘인 경로</h3>
 * <p>이 가드는 <b>역할 축</b>과 <b>배정 축</b> 두 사유로 같은 {@code FORBIDDEN} 을 던진다. 관리자에게
 * 배정 행을 준 채로 시험하면 계층을 되돌려도 배정 검사가 통과시켜 버려 <b>변이를 한 건도 잡지 못한다</b>.
 * 그래서 관리자 시험을 <b>배정 행이 없는 픽스처</b>로 세우고, 나아가 <b>배정 리포지토리가 호출조차
 * 되지 않았음</b>을 확인한다 — 그래야 「검수자 분기에서 즉시 끝났다」가 증명된다.
 * 배정 행이 있는 픽스처로도 함께 시험해 계약(관리자는 어느 경우든 통과)을 양쪽에서 고정한다.
 *
 * <h3>대조군</h3>
 * <p>「미배정 작업자는 거부된다」 같은 부정 단언만 두면 경로가 애초에 도달 불가여도 통과한다.
 * 「배정된 작업자는 통과한다」를 짝으로 두어 이 경로가 살아 있고 다만 역할·배정으로 갈림을 보인다.
 *
 * <h3>평가 순서 보존</h3>
 * <p>인가는 영상 존재 확인보다 <b>먼저</b> 평가된다 — 미배정 작업자는 미존재 영상에도 NOT_FOUND 가
 * 아니라 FORBIDDEN 을 받아 리소스 존재 여부가 노출되지 않는다. 사전 확인 진입점
 * ({@link MarkingPrecheckReader})으로 그 순서를 함께 고정한다.
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>가드의 계층 판정을 역할 동등 비교로 되돌리면 이 클래스의 관리자 시험 3건이
 * {@code FORBIDDEN} / 배정 리포지토리 호출 발생으로 FAILED 된다.
 */
class MarkingRoleHierarchyTest {

    private static final Long RAW_SN = 9310L;
    private static final Long ABSENT_RAW_SN = 9399L;

    private static final TokenClaims ADMIN =
            new TokenClaims("900", Role.ADMIN, Channel.INTERNAL, null);
    private static final TokenClaims REVIEWER =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, null);
    private static final TokenClaims WORKER =
            new TokenClaims("100", Role.WORKER, Channel.INTERNAL, null);
    private static final TokenClaims PORTAL =
            new TokenClaims("500", Role.PORTAL_USER, Channel.PORTAL, null);

    private LsTaskAssignmentRepository assignmentRepository;
    private VideoRepository videoRepository;
    private LsMarkingRepository markingRepository;

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(LsTaskAssignmentRepository.class);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        // 기본 픽스처는 「배정 없음」이다 — 관리자 통과가 계층 덕분임을 보이기 위한 설정.
        lenient().when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                anyLong(), anyString(), anyLong())).thenReturn(false);
    }

    private void giveAssignment(long userNo) {
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                userNo, LsTaskAssignment.TASK_LABELER, RAW_SN)).thenReturn(true);
    }

    // ---------------------------------------------------- 관리자 (계층)

    /**
     * ★ 배정 행을 <b>주지 않는다</b>. 주면 계층을 되돌려도 배정 검사가 통과시켜 시험이 헛돈다.
     */
    @Test
    @DisplayName("관리자는_배정이_없어도_마킹_창구에_진입한다_검수자_전용은_검수자_이상으로_읽는다")
    void adminEntersWithoutAssignment() {
        assertThatCode(() -> MarkingGuards.requireAssignedOrReviewer(RAW_SN, ADMIN, assignmentRepository))
                .doesNotThrowAnyException();

        // 검수자 분기에서 즉시 끝났음을 고정 — 작업자 전용 배정 검사에 흘러들지 않는다.
        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("관리자는_배정이_있어도_마킹_창구에_진입한다_계약은_양쪽_픽스처에서_같다")
    void adminEntersWithAssignment() {
        giveAssignment(900L);

        assertThatCode(() -> MarkingGuards.requireAssignedOrReviewer(RAW_SN, ADMIN, assignmentRepository))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("관리자는_배정이_없어도_사전확인을_통과해_영상_조회까지_간다_계층")
    void adminReachesVideoLookupThroughPrecheck() {
        MarkingPrecheckReader reader =
                new MarkingPrecheckReader(videoRepository, assignmentRepository, markingRepository);
        when(videoRepository.findById(ABSENT_RAW_SN)).thenReturn(Optional.empty());

        // 인가를 통과했으므로 다음 단계인 영상 존재 확인에서 NOT_FOUND 가 된다.
        assertThatThrownBy(() -> reader.precheck(ABSENT_RAW_SN, ADMIN))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        verify(videoRepository).findById(ABSENT_RAW_SN);
    }

    // ---------------------------------------------------- 검수자 (기존 계약 보존)

    @Test
    @DisplayName("검수자는_배정이_없어도_마킹_창구에_진입한다_기존_계약_보존")
    void reviewerEntersWithoutAssignment() {
        assertThatCode(() -> MarkingGuards.requireAssignedOrReviewer(RAW_SN, REVIEWER, assignmentRepository))
                .doesNotThrowAnyException();

        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    // ---------------------------------------------------- 작업자 (대조군 + 보존)

    @Test
    @DisplayName("배정된_작업자는_마킹_창구에_진입한다_대조군")
    void assignedWorkerEnters() {
        giveAssignment(100L);

        assertThatCode(() -> MarkingGuards.requireAssignedOrReviewer(RAW_SN, WORKER, assignmentRepository))
                .doesNotThrowAnyException();

        verify(assignmentRepository)
                .existsByUserNoAndTaskTypeCdAndRawDataId(100L, LsTaskAssignment.TASK_LABELER, RAW_SN);
    }

    @Test
    @DisplayName("미배정_작업자는_여전히_거부된다_계층이_새지_않는다")
    void unassignedWorkerStillForbidden() {
        assertThatThrownBy(() -> MarkingGuards.requireAssignedOrReviewer(RAW_SN, WORKER, assignmentRepository))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------- 포털 회원 · 미인증 (보존)

    @Test
    @DisplayName("포털회원은_여전히_거부된다_채널_격리는_역할_계층으로_뚫리지_않는다")
    void portalUserStillForbidden() {
        assertThatThrownBy(() -> MarkingGuards.requireAssignedOrReviewer(RAW_SN, PORTAL, assignmentRepository))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("인증_토큰이_없으면_401이다_fail_closed")
    void missingActorIsUnauthorized() {
        assertThatThrownBy(() -> MarkingGuards.requireAssignedOrReviewer(RAW_SN, null, assignmentRepository))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    // ---------------------------------------------------- 평가 순서 (기존 계약 보존)

    /**
     * 인가가 영상 존재 확인보다 먼저다 — 미배정 작업자는 <b>미존재</b> 영상에도 NOT_FOUND 가 아니라
     * FORBIDDEN 을 받아야 하고, 영상 조회 자체가 일어나서는 안 된다(리소스 존재 여부 노출 차단).
     */
    @Test
    @DisplayName("미배정_작업자는_미존재_영상에도_FORBIDDEN이다_인가가_존재확인보다_먼저다")
    void unassignedWorkerGetsForbiddenEvenForAbsentVideo() {
        MarkingPrecheckReader reader =
                new MarkingPrecheckReader(videoRepository, assignmentRepository, markingRepository);

        assertThatThrownBy(() -> reader.precheck(ABSENT_RAW_SN, WORKER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);

        verify(videoRepository, never()).findById(any());
        verify(markingRepository, never()).existsByRawSnAndSttsCdIn(eq(ABSENT_RAW_SN), any());
    }
}
