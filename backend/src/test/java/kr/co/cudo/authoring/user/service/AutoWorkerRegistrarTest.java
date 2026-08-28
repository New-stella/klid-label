package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자동 등록기의 <b>2차 게이트</b>와 fail-open (@design AC-126).
 *
 * <h3>왜 호출 횟수로 재는가</h3>
 * <p>쓰기 문장이 {@code ON CONFLICT DO NOTHING}·no-op upsert 라 <b>행이 바뀌지 않는다</b>. 그래서
 * "매 요청 쓰기가 되면 안 된다" 를 DB 관측으로 검증하면 되돌림을 잡지 못한다 — 문장이 매번 실행돼도
 * 초록이다. 여기서는 트랜잭션 경계(쓰기 경로)의 <b>호출 횟수</b>를 직접 센다.
 */
class AutoWorkerRegistrarTest {

    private static final long USER_NO = 5150L;
    private static final String NAME = "인계이름";

    private AutoWorkerRegisterTxService txService;
    private UserRoleResolver userRoleResolver;
    private LsUserRoleRepository lsUserRoleRepository;
    private AutoWorkerRegistrar registrar;

    @BeforeEach
    void setUp() {
        txService = mock(AutoWorkerRegisterTxService.class);
        userRoleResolver = mock(UserRoleResolver.class);
        lsUserRoleRepository = mock(LsUserRoleRepository.class);
        registrar = new AutoWorkerRegistrar(txService, userRoleResolver, lsUserRoleRepository);
    }

    @Test
    @DisplayName("★역할_행이_이미_있으면_쓰기_경로에_들어가지_않는다_해석이_비어도")
    void existingRoleRowSkipsWritePath() {
        // 역할 코드가 Role enum 밖이라 해석은 <매 요청> 비지만(그리고 null 은 캐시되지 않는다)
        //   행은 있다. 존재 확인을 빼면 인증 경로에서 요청마다 트랜잭션 1 + 문장 2 가 돈다.
        when(lsUserRoleRepository.existsById(USER_NO)).thenReturn(true);

        for (int i = 0; i < 5; i++) {
            assertThat(registrar.registerAsWorker(USER_NO, NAME)).isNull();
        }

        verify(txService, never()).registerAsWorker(anyLong(), any());
        verify(userRoleResolver, never()).evict(anyLong());
    }

    @Test
    @DisplayName("★역할_행이_없으면_한_번만_등록하고_이름을_함께_넘긴다")
    void registersOnceWithName() {
        when(lsUserRoleRepository.existsById(USER_NO)).thenReturn(false);
        when(txService.registerAsWorker(USER_NO, NAME)).thenReturn(true);

        assertThat(registrar.registerAsWorker(USER_NO, NAME)).isEqualTo(Role.WORKER);

        verify(txService, times(1)).registerAsWorker(eq(USER_NO), eq(NAME));
        verify(userRoleResolver, times(1)).evict(USER_NO);
    }

    @Test
    @DisplayName("경합으로_다른_요청이_먼저_넣었으면_역할을_지어내지_않는다_fail_closed")
    void concurrentInsertLoserReturnsNull() {
        when(lsUserRoleRepository.existsById(USER_NO)).thenReturn(false);
        when(txService.registerAsWorker(USER_NO, NAME)).thenReturn(false);

        assertThat(registrar.registerAsWorker(USER_NO, NAME)).isNull();
        verify(userRoleResolver, never()).evict(anyLong());
    }

    @Test
    @DisplayName("★DB_장애는_요청을_죽이지_않는다_fail_open")
    void dbFailureIsFailOpen() {
        when(lsUserRoleRepository.existsById(USER_NO))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(registrar.registerAsWorker(USER_NO, NAME)).isNull();
    }

    @Test
    @DisplayName("식별할_수_없는_주체로는_아무것도_하지_않는다")
    void nullUserNoDoesNothing() {
        assertThat(registrar.registerAsWorker(null, NAME)).isNull();
        verify(lsUserRoleRepository, never()).existsById(anyLong());
        verify(txService, never()).registerAsWorker(anyLong(), any());
    }
}
