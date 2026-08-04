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
    @DisplayName("시드가_관제_인입_미처리행을_적재해_파이프라인_시작점이_살아있다")
    void seedInsertsPendingIngestRows() {
        // given — 적재 소스가 관제 공유 클립 마스터 스캔 → LS_DATA_INGEST 폴링으로 바뀌었다.
        //   구 시드(MNG_CLIP_* 만)로는 dev/local 수동 드라이브가 <적재 0건>으로 조용히 죽는다.
        //   ★ DevSeedRunner 는 fail-soft(예외를 WARN 으로 삼킴)라, 시드 SQL 이 깨져도 부팅·다른
        //     단언은 통과한다. 그래서 인입 행 자체를 직접 단언해야 이 갭이 다시 열리지 않는다.
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);

        // when — 부팅 시 Runner 가 이미 1회 실행됨
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_INGEST WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'"
                        + " AND PROC_STTS_CD = 'PENDING'", Long.class);

        // then — 폴링 술어(PENDING)에 걸리는 후보 3건
        assertThat(pending).isEqualTo(3L);
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
