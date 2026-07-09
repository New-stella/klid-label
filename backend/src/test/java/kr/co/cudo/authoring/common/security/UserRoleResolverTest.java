package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UserRoleResolver 단위 테스트 — 역할 분리 Phase 3 fail-closed 검증.
 *
 * <p>인가 역할 해석이 LS_USER_ROLE 기준으로 동작하며, 모호한 상황(미배정/DB장애/미상 역할/
 * 비정상 입력)에서는 절대 fail-open(기본 역할 부여) 하지 않고 무권한(null)을 반환함을 검증한다.
 */
class UserRoleResolverTest {

    private LsUserRoleRepository repository;
    private UserRoleResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(LsUserRoleRepository.class);
        resolver = new UserRoleResolver(repository);
    }

    @Test
    @DisplayName("INTERNAL_사용자_역할이_LS_USER_ROLE에서_해석된다")
    void resolvesRoleFromLs() {
        when(repository.findByUserNo(1L)).thenReturn(Optional.of(LsUserRole.of(1L, "REVIEWER")));

        assertThat(resolver.resolve(1L)).isEqualTo(Role.REVIEWER);
    }

    @Test
    @DisplayName("LS_역할없는_사용자는_무권한_null")
    void noLsRoleReturnsNull() {
        when(repository.findByUserNo(99L)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(99L)).isNull();
    }

    @Test
    @DisplayName("DB조회_예외시_fail_closed_null")
    void dbExceptionFailsClosed() {
        when(repository.findByUserNo(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(resolver.resolve(1L)).isNull();
    }

    @Test
    @DisplayName("비정상_ROLE_CD는_무권한_null")
    void unknownRoleCodeFailsClosed() {
        when(repository.findByUserNo(5L)).thenReturn(Optional.of(LsUserRole.of(5L, "LEARN_MANAGER")));

        assertThat(resolver.resolve(5L)).isNull();
    }

    @Test
    @DisplayName("userNo_null이면_무권한_null")
    void nullUserNoReturnsNull() {
        assertThat(resolver.resolve(null)).isNull();
    }
}
