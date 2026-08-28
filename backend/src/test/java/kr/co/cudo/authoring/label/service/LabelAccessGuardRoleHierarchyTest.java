package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LabelAccessGuard} — <b>역할 계층(관리자 &gt; 검수자) 반영</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <h3>이 가드가 왜 특히 중요한가</h3>
 * <p>이 빈은 라벨 조회·저장, 프레임 이미지 서빙, 메타·이벤트 어노테이션 등 <b>프레임 경로 대부분이
 * 통과하는 길목</b>이다. 그래서 여기가 역할을 동등 비교하면 관리자가 두 분기 어디에도 걸리지 않고
 * 마지막 {@code FORBIDDEN} 으로 떨어져, 상위 도메인이 아무리 계층을 반영해도 <b>라벨·프레임 접근이
 * 통째로 403</b> 이 된다. 실제로 이 이관 전에는 {@code GET /v1/videos/{rawSn}/frames/{frameNo}/image}
 * 가 관리자에게 403 이었다 — 서빙 판정을 담당하는 영상 도메인은 이미 계층을 반영한 상태였는데도.
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <ul>
 *   <li>관리자에게 <b>배정 행이 없음</b>을 픽스처로 못박는다. 배정이 있으면 작업자 분기로도 통과해
 *       계층 덕분에 통과한 것인지 구분되지 않는다. 나아가 배정 리포지토리가 <b>한 번도 호출되지
 *       않았음</b>까지 확인해 관리자가 검수자 분기에서 즉시 끝났음을 고정한다.</li>
 *   <li>「작업자 전용 자리를 계층이 열지 않는다」는 규정을 별도 시험으로 고정한다 — 관리자는
 *       {@code hasRole(WORKER)} 로 참이 되지 않으므로 배정 검사에 흘러들지 않는다.</li>
 *   <li>대조군으로 포털 회원 거부를 함께 둔다. 통과 단언만 있으면 가드를 통째로 무력화(무조건 통과)
 *       해도 초록이라 아무것도 지키지 못한다.</li>
 * </ul>
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>{@code actor.hasRole(Role.REVIEWER)} 를 {@code actor.role() == Role.REVIEWER} 로 되돌리면
 * 관리자 시험 2건이 {@code FORBIDDEN} 으로 FAILED 가 된다.
 */
class LabelAccessGuardRoleHierarchyTest {

    private static final long SRC_SN = 7777L;
    private static final long RAW_SN = 8888L;

    private LsDataSrcRepository srcRepository;
    private LsTaskAssignmentRepository assignmentRepository;
    private DeidentReportGate deidentReportGate;
    private LabelAccessGuard guard;

    @BeforeEach
    void setUp() {
        srcRepository = mock(LsDataSrcRepository.class);
        assignmentRepository = mock(LsTaskAssignmentRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        guard = new LabelAccessGuard(srcRepository, assignmentRepository, deidentReportGate);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "frames/raw/" + RAW_SN + "/0.jpg", null);
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        // ★ 관리자에게 배정 행을 주지 않는다 — 배정이 있으면 작업자 분기로도 통과해
        //   "계층으로 통과했다"는 것이 증명되지 않는다.
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(false);
    }

    private static TokenClaims actor(String sub, Role role, Channel channel) {
        return new TokenClaims(sub, role, channel, Instant.now().plusSeconds(600));
    }

    private static TokenClaims internal(String sub, Role role) {
        return actor(sub, role, Channel.INTERNAL);
    }

    // ---------- 계층 본체 — 관리자는 검수자에게 열린 자리를 그대로 통과한다 ----------

    @Test
    @DisplayName("★관리자는_배정이_없어도_프레임_접근_인가를_통과한다_계층")
    void adminPassesFrameAccessWithoutAssignment() {
        LsDataSrc src = guard.verifyAndGet(SRC_SN, internal("969300021", Role.ADMIN));

        assertThat(src.getRawSn()).isEqualTo(RAW_SN);
        // 검수자 분기에서 즉시 끝났음을 고정 — 배정 조회에 흘러들면 "작업자 전용 자리를 계층이 열지
        // 않는다"는 규정과 어긋난다.
        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("★관리자는_배정이_없어도_영상_단위_인가를_통과한다_계층")
    void adminPassesRawAccessWithoutAssignment() {
        assertThatCode(() -> guard.verifyRawAccess(RAW_SN, internal("969300021", Role.ADMIN)))
                .doesNotThrowAnyException();

        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    // ---------- 대조군 — 기존 정책 불변 ----------

    @Test
    @DisplayName("검수자는_종전대로_배정_없이_통과한다")
    void reviewerStillPasses() {
        assertThatCode(() -> guard.verifyAndGet(SRC_SN, internal("1", Role.REVIEWER)))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.verifyRawAccess(RAW_SN, internal("1", Role.REVIEWER)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("미배정_작업자는_종전대로_403이다_계층이_배정_검사를_무르게_하지_않는다")
    void unassignedWorkerStillForbidden() {
        TokenClaims worker = internal("100", Role.WORKER);

        assertThatThrownBy(() -> guard.verifyAndGet(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> guard.verifyRawAccess(RAW_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("포털_회원은_여전히_차단된다_계층이_채널_격리를_뚫지_않는다")
    void portalUserStillForbidden() {
        // 통과 단언만 있으면 가드를 무조건-통과로 무력화해도 초록이다. 거부 축을 함께 고정한다.
        TokenClaims portal = actor("50000", Role.PORTAL_USER, Channel.PORTAL);

        assertThatThrownBy(() -> guard.verifyAndGet(SRC_SN, portal))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> guard.verifyRawAccess(RAW_SN, portal))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("역할_미배정_토큰은_거부된다_fail_closed")
    void nullRoleIsForbidden() {
        TokenClaims noRole = actor("300", null, Channel.INTERNAL);

        assertThatThrownBy(() -> guard.verifyRawAccess(RAW_SN, noRole))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
