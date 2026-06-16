package kr.co.cudo.authoring.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dev-seed.sql 자동 적재 Runner 통합 검증 (Testcontainers PostgreSQL + Flyway).
 *
 * <p>local 프로파일에서 {@code authoring.dev.seed.enabled=true} 일 때 dev-seed.sql 이
 * controlDataSource 에 멱등 적재됨을 검증한다. 일반 테스트 컨텍스트는
 * {@code src/test/resources/application-local.yml} 의 {@code enabled=false} 로 격리된다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.dev.seed.enabled=true")
class DevSeedRunnerTest {

    @Autowired
    private DevSeedRunner devSeedRunner;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private long countSeedUsers() {
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM MNG_ACCT_USER WHERE USER_NO IN (1001, 2001, 3001)", Long.class);
        return cnt == null ? 0L : cnt;
    }

    @Test
    @DisplayName("로컬_프로파일에서_시드_SQL이_실행되어_사용자_시드가_적재된다")
    void seedAppliedOnLocalProfile() {
        // given — @SpringBootTest 부팅 시 Flyway 마이그레이션 후 Runner 가 이미 1회 실행됨
        // when — 시드 사용자 조회
        long count = countSeedUsers();
        // then — dev-seed 사용자(REVIEWER 1001 / WORKER 2001 / PORTAL 3001) 3명 적재
        assertThat(count).isEqualTo(3L);
    }

    @Test
    @DisplayName("시드_재실행시_멱등하여_중복_없이_통과한다")
    void seedIsIdempotent() {
        // given — 부팅 시 1회 적재됨
        long before = countSeedUsers();
        // when — Runner 를 한 번 더 직접 실행
        devSeedRunner.applySeed();
        // then — 예외 없이 row 수 동일 (ON CONFLICT 멱등)
        long after = countSeedUsers();
        assertThat(before).isEqualTo(3L);
        assertThat(after).isEqualTo(before);
    }
}
