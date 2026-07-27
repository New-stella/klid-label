package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * DEV_FIX H4-a 회귀 IT — 배정(assign) 중 낙관적 잠금 충돌이 <b>409(CONFLICT)</b>로 매핑되는지 고정한다.
 *
 * <p>실측 결함: {@code rejectApprovedTargets} 가 읽은 {@code LS_RAW_DATA_STATUS} 를 그대로
 * {@code markAssigned} 하므로 그 사이 다른 트랜잭션이 승인하면 flush 가 낙관적 잠금 실패로 롤백된다
 * (데이터는 안전). 그러나 {@code assign} 의 catch 는 {@code DataIntegrityViolationException} 뿐이고
 * {@code GlobalExceptionHandler} 에도 {@code OptimisticLockingFailureException} 핸들러가 없어
 * {@code handleUnknown} → <b>500</b> 이 나갔다(재배정 경로는 이미 409).
 *
 * <p>전역 핸들러 매핑 대신 <b>국소 catch</b> 로 고친 이유: 전역 매핑은 낙관적 잠금을 다르게 처리하는
 * 다른 경로(예: 자체 catch 로 이미 409 를 던지는 review/reassign, 배치 재시도 경로)의 기존 동작까지
 * 바꿀 수 있어 영향 범위가 넓다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentOptimisticLockConflictIT {

    @Autowired private AssignmentService assignmentService;

    /** flush 시점의 낙관적 잠금 실패를 결정적으로 재현한다(경합 스케줄링에 의존하지 않음). */
    @SpyBean private LsTaskAssignmentRepository authrtRepository;

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("assign_중_낙관적잠금_충돌시_500이_아니라_409_CONFLICT_로_거부된다")
    void assignMapsOptimisticLockFailureToConflict() {
        // given — 동시 승인으로 상태 row 버전이 올라가 flush 가 낙관적 잠금 실패하는 상황
        doThrow(new OptimisticLockingFailureException("stale LS_RAW_DATA_STATUS"))
                .when(authrtRepository).flush();

        // when / then — 알 수 없는 예외(500)가 아니라 재배정 경로와 동일한 409 로 통일된다
        assertThatThrownBy(() -> assignmentService.assign(
                new AssignmentCreateRequest(100L, List.of(1000L)), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }
}
