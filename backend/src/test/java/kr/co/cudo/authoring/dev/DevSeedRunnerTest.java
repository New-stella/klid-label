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
                "SELECT COUNT(*) FROM LS_ACNT_USER WHERE USER_NO IN (1001, 2001, 3001)", Long.class);
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
    @DisplayName("시드는_관제_인입_원장을_건드리지_않는다")
    void seedDoesNotTouchControlIngestLedger() {
        // given — ★구 동작 폐지(2026-08-19 사용자 확정): 시드는 DEV-CLIP-9101~9103 3건을 PENDING 으로
        //   심어 dev 파이프라인 시작점을 만들었고, 이 테스트는 그 3건을 단언했다.
        //   LS_DATA_INGEST 는 <관제가 직접 INSERT 하는 인입 원장>이고 저작도구 DB 가 관제 DB 와
        //   같은 서버에 놓이면서, 시드의 가짜 행이 관제 실인입과 같은 테이블에 섞이게 됐다.
        //   그래서 단언을 지우지 않고 <반대 방향의 가드>로 뒤집는다 — 누가 편의를 이유로 시드에
        //   인입 행을 되살리면 여기서 걸린다.
        //   ★ DevSeedRunner 는 fail-soft(예외를 ERROR 로 남기고 삼킴)라, 시드 SQL 이 깨져도 부팅과
        //     다른 단언은 통과한다. 그래서 인입 행 자체를 직접 세야 이 갭이 다시 열리지 않는다.
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);

        // when — 부팅 시 Runner 가 이미 1회 실행됨
        Long seeded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_INGEST WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'", Long.class);

        // then — 시드 네임스페이스의 인입 행이 하나도 생기지 않는다.
        //   dev 파이프라인 시작점은 시드가 아니라 dev 업로드(POST /v1/dev/upload)로 만든다.
        assertThat(seeded).isZero();
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
