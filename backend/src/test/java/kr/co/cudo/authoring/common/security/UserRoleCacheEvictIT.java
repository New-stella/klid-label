package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.user.dto.UserUpdateRequest;
import kr.co.cudo.authoring.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 분리 Phase 3 — 인가 역할 캐시(userRole) 무효화 동작 검증.
 *
 * <p>역할 변경({@code UserService.update})은 트랜잭션 AFTER_COMMIT 에 {@code UserRoleResolver.evict}
 * 를 호출해, 캐시된 이전 역할이 강등 즉시(≤다음 요청) 새 역할로 반영됨을 실 PostgreSQL 위에서 증명한다.
 *
 * <p>{@code @Cacheable}/{@code @CacheEvict} 가 프록시로 동작하려면 빈(UserRoleResolver/UserService)을
 * 주입받아 외부 호출해야 하므로 본 테스트는 {@code @Transactional} 을 붙이지 않는다(update 자체 tx 가
 * 커밋되어야 afterCommit 이 발화).
 */
@SpringBootTest
@ActiveProfiles("local")
class UserRoleCacheEvictIT {

    @Autowired
    private UserRoleResolver userRoleResolver;
    @Autowired
    private UserService userService;
    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** 본 테스트 전용 USER_NO — 다른 통합테스트/시드와 충돌 회피. */
    private static final long USER_NO = 976_100_001L;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    @AfterEach
    void cleanup() {
        jdbc().update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", USER_NO);
        jdbc().update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", USER_NO);
        userRoleResolver.evict(USER_NO);
    }

    @Test
    @DisplayName("역할_변경후_AFTER_COMMIT_evict로_즉시_반영")
    void roleChangeEvictsCacheAfterCommit() {
        // given — REVIEWER 로 시드된 사용자(+사용자 마스터 행). resolve 로 캐시 적재.
        jdbc().update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USE_YN, REG_DT) "
                        + "VALUES (?, ?, ?, 'Y', CURRENT_TIMESTAMP)",
                USER_NO, "evict-user", "강등대상");
        jdbc().update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'REVIEWER', CURRENT_TIMESTAMP)",
                USER_NO);
        assertThat(userRoleResolver.resolve(USER_NO)).isEqualTo(Role.REVIEWER); // 캐시 적재

        // when — WORKER 로 강등 (update tx 커밋 시 AFTER_COMMIT evict 발화)
        userService.update(USER_NO, new UserUpdateRequest("WORKER", null));

        // then — 캐시 무효화로 다음 resolve 가 새 역할(WORKER) 반환
        assertThat(userRoleResolver.resolve(USER_NO)).isEqualTo(Role.WORKER);
    }
}
