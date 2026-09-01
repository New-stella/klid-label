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
 * 자동 등록기의 <b>2차 게이트</b>·fail-open·<b>개발용 로그인 표준 계정 제외</b>
 * (@design AC-1016 · @design UC-041).
 *
 * <h3>왜 호출 횟수로 재는가</h3>
 * <p>쓰기 문장이 {@code ON CONFLICT DO NOTHING}·no-op upsert 라 <b>행이 바뀌지 않는다</b>. 그래서
 * "매 요청 쓰기가 되면 안 된다" 를 DB 관측으로 검증하면 되돌림을 잡지 못한다 — 문장이 매번 실행돼도
 * 초록이다. 여기서는 트랜잭션 경계(쓰기 경로)의 <b>호출 횟수</b>를 직접 센다.
 */
class AutoWorkerRegistrarTest {

    private static final long USER_NO = 5150L;
    private static final String NAME = "인계이름";

    /** 개발용 로그인 표준 계정 — 값은 판정기가 소유한다(여기에 번호를 다시 적지 않는다). */
    private static final long DEV_ADMIN_USER_NO =
            Long.parseLong(DevStandardAccounts.DEFAULT_USER_NO_ADMIN);

    private AutoWorkerRegisterTxService txService;
    private UserRoleResolver userRoleResolver;
    private LsUserRoleRepository lsUserRoleRepository;
    private AutoWorkerRegistrar registrar;

    @BeforeEach
    void setUp() {
        txService = mock(AutoWorkerRegisterTxService.class);
        userRoleResolver = mock(UserRoleResolver.class);
        lsUserRoleRepository = mock(LsUserRoleRepository.class);
        registrar = registrarWithDevLogin(false);
    }

    /**
     * 개발용 로그인 토글을 명시해 등록기를 만든다.
     *
     * <p>토글은 <b>제외 판정의 스위치</b>라 값을 감추면 어느 형상을 시험하는지 흐려진다. 판정기는
     * 목이 아니라 <b>실물</b>을 쓴다 — 목으로 감싸면 "번호가 실제로 제외 집합에 있는가" 를 시험이
     * 스스로 정해 버려 공허해진다.
     */
    private AutoWorkerRegistrar registrarWithDevLogin(boolean devLoginEnabled) {
        return new AutoWorkerRegistrar(txService, userRoleResolver, lsUserRoleRepository,
                new DevStandardAccounts(devLoginEnabled));
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

    @Test
    @DisplayName("★개발용_로그인이_켜진_형상에서_표준_계정은_등록하지_않는다_DB_도_보지_않는다")
    void devStandardAccountIsExcludedWhenDevLoginEnabled() {
        AutoWorkerRegistrar devRegistrar = registrarWithDevLogin(true);

        assertThat(devRegistrar.registerAsWorker(DEV_ADMIN_USER_NO, NAME)).isNull();

        // 그 계정은 역할이 해석되지 않아 <매 요청> 여기로 온다 — 존재 확인조차 하지 않아야 한다.
        verify(lsUserRoleRepository, never()).existsById(anyLong());
        verify(txService, never()).registerAsWorker(anyLong(), any());
        verify(userRoleResolver, never()).evict(anyLong());
    }

    @Test
    @DisplayName("★개발용_로그인_표준_계정_4종이_전부_제외된다_관리자만이_아니다")
    void allDevStandardAccountsAreExcluded() {
        AutoWorkerRegistrar devRegistrar = registrarWithDevLogin(true);

        for (String userNo : new String[]{
                DevStandardAccounts.DEFAULT_USER_NO_REVIEWER,
                DevStandardAccounts.DEFAULT_USER_NO_WORKER,
                DevStandardAccounts.DEFAULT_USER_NO_PORTAL,
                DevStandardAccounts.DEFAULT_USER_NO_ADMIN}) {
            assertThat(devRegistrar.registerAsWorker(Long.parseLong(userNo), NAME))
                    .as("표준 계정 %s 가 제외되지 않으면 고른 역할과 실제 인가가 어긋난 채 굳는다", userNo)
                    .isNull();
        }
        verify(txService, never()).registerAsWorker(anyLong(), any());
    }

    @Test
    @DisplayName("★★개발용_로그인이_꺼진_형상에서는_같은_번호여도_종전대로_등록된다")
    void devStandardAccountIsRegisteredWhenDevLoginDisabled() {
        // 운영에는 이 번호를 쓰는 <실제 사용자>가 있을 수 있다. 토글과 무관하게 제외하면 그
        //   사용자가 자동 등록에서 조용히 빠지는데 오류가 나지 않아 발견되지 않는다.
        when(lsUserRoleRepository.existsById(DEV_ADMIN_USER_NO)).thenReturn(false);
        when(txService.registerAsWorker(DEV_ADMIN_USER_NO, NAME)).thenReturn(true);

        assertThat(registrar.registerAsWorker(DEV_ADMIN_USER_NO, NAME)).isEqualTo(Role.WORKER);

        verify(txService, times(1)).registerAsWorker(eq(DEV_ADMIN_USER_NO), eq(NAME));
        verify(userRoleResolver, times(1)).evict(DEV_ADMIN_USER_NO);
    }

    @Test
    @DisplayName("★표준_계정이_아닌_진입자는_개발용_로그인이_켜져도_종전대로_등록된다")
    void nonDevUserIsStillRegisteredWhenDevLoginEnabled() {
        AutoWorkerRegistrar devRegistrar = registrarWithDevLogin(true);
        when(lsUserRoleRepository.existsById(USER_NO)).thenReturn(false);
        when(txService.registerAsWorker(USER_NO, NAME)).thenReturn(true);

        assertThat(devRegistrar.registerAsWorker(USER_NO, NAME)).isEqualTo(Role.WORKER);

        verify(txService, times(1)).registerAsWorker(eq(USER_NO), eq(NAME));
    }

    @Test
    @DisplayName("표준_계정이어도_이미_역할이_있으면_그_역할은_덮이지_않는다")
    void existingRoleOfDevStandardAccountIsNotOverwritten() {
        // 제외 판정이 앞서므로 쓰기 경로에 아예 들어가지 않고(켜짐), 꺼진 형상에서는 2차 게이트가 막는다.
        when(lsUserRoleRepository.existsById(DEV_ADMIN_USER_NO)).thenReturn(true);

        assertThat(registrarWithDevLogin(true).registerAsWorker(DEV_ADMIN_USER_NO, NAME)).isNull();
        assertThat(registrarWithDevLogin(false).registerAsWorker(DEV_ADMIN_USER_NO, NAME)).isNull();

        verify(txService, never()).registerAsWorker(anyLong(), any());
    }
}
