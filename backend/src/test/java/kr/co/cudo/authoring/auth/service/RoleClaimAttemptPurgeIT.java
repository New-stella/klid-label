package kr.co.cudo.authoring.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEV_FIX H-4 — {@code LS_AUTHRT_GRANT_ATMPT} 만료 행의 <b>주기 정리</b> 검증 (실 PostgreSQL).
 *
 * <p>구 구현은 {@code JdbcRoleClaimAttemptStore} 의 기회적 정리({@code writeCounter % 100})뿐이었다.
 * role-claim 은 신규 사용자 온보딩 경로라 정상 운영 트래픽이 하루 수 건 수준이며, 임계 100회에 도달하는 데
 * 수개월이 걸린다 — 그동안 만료 행이 계속 누적된다. Phase 1 의 웹훅 가드가 같은 이유로 전용 스케줄러
 * ({@code WebhookGuardPurgeJob})로 대체된 것과 동일한 실패 모드다.
 *
 * <p>여기서는 스케줄러 발화 타이밍이 아니라 <b>잡이 수행하는 정리 동작</b>을 검증한다(스케줄 등록 자체는
 * {@code @PostConstruct} 에서 이뤄지므로 빈 존재로 확인).
 */
@SpringBootTest
@ActiveProfiles("local")
class RoleClaimAttemptPurgeIT {

    private static final String COUNT_ROW = """
            SELECT COUNT(*) FROM LS_AUTHRT_GRANT_ATMPT
             WHERE ATMPT_SE_CD = ? AND ATMPT_IDNTFR = ? AND BGNG_DT = ?
            """;

    @Autowired private JdbcRoleClaimAttemptStore store;
    @Autowired private RoleClaimAttemptPurgeJob purgeJob;
    @Autowired @Qualifier("controlDataSource") private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private static String uniqueIdentifier() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** 노드 공통 PK 축과 동일하게 UTC 기준 분 버킷을 만든다 (DEV_FIX M-3). */
    private static LocalDateTime window() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
    }

    @Test
    @DisplayName("전용_주기_정리잡이_만료된_권한자가부여_시도행을_삭제한다")
    void purgeJob_removesExpiredRows() {
        String idntfr = uniqueIdentifier();
        LocalDateTime window = window();

        // given: 이미 만료된 TTL 로 기록된 시도 행 (기회적 임계에 도달하지 못해 남아 있는 상황)
        store.recordAttempt(RoleClaimRateLimiter.SE_ACCOUNT, idntfr, window, Duration.ofSeconds(-1));
        assertThat(jdbc().queryForObject(COUNT_ROW, Integer.class,
                RoleClaimRateLimiter.SE_ACCOUNT, idntfr, window)).isEqualTo(1);

        // when: 전용 주기 정리 잡 1회 실행
        int purged = purgeJob.run();

        // then: 만료 행이 삭제된다 — 이 잡이 없으면 저트래픽 환경에서 사실상 영구 축적된다.
        assertThat(purged).as("주기 정리 잡이 만료 행을 삭제해야 한다").isPositive();
        assertThat(jdbc().queryForObject(COUNT_ROW, Integer.class,
                RoleClaimRateLimiter.SE_ACCOUNT, idntfr, window)).isZero();
    }

    @Test
    @DisplayName("정리잡은_살아있는_시도행은_삭제하지_않는다")
    void purgeJob_keepsLiveRows() {
        String idntfr = uniqueIdentifier();
        LocalDateTime window = window();

        // given: 아직 만료되지 않은 시도 행 (rate limit 이 유효해야 하는 창)
        store.recordAttempt(RoleClaimRateLimiter.SE_ACCOUNT, idntfr, window, Duration.ofMinutes(10));

        purgeJob.run();

        // then: 살아 있는 카운터가 지워지면 무차별 대입 방어가 정리 주기마다 리셋된다.
        assertThat(jdbc().queryForObject(COUNT_ROW, Integer.class,
                RoleClaimRateLimiter.SE_ACCOUNT, idntfr, window))
                .as("만료되지 않은 행은 보존되어야 한다").isEqualTo(1);
    }
}
