package kr.co.cudo.authoring.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 권한 자가부여 시도 카운터의 PostgreSQL 공유 구현 (A-ISSUE-18).
 *
 * <h3>왜 REQUIRES_NEW 인가 (Critical)</h3>
 * <p>{@code RoleClaimService.claim} 은 {@code @Transactional("controlTransactionManager")} 이고,
 * rate limit 에 걸리거나 패스워드가 틀리면 <b>예외로 롤백</b>된다. 카운터 기록이 그 트랜잭션에 얹히면
 * 실패 시도가 함께 롤백되어 카운터가 영원히 오르지 않는다(=방어 무력화). 또한 PostgreSQL 은 UNIQUE
 * 위반이 트랜잭션 전체를 abort(25P02) 시켜 같은 트랜잭션 안에서 재시도가 불가능하다. 그래서
 * <b>REQUIRES_NEW 로 외부 트랜잭션을 suspend</b> 하고, 단문 {@code INSERT ... ON CONFLICT DO UPDATE}
 * 로 예외 경로 자체를 없앤다.
 *
 * <h3>장애 정책</h3>
 * <p>저장소 장애는 {@link #UNAVAILABLE} 로만 신호하고 예외를 승격하지 않는다. 완전 fail-open 이 아니라
 * 호출자의 로컬 카운터가 최종 방어선으로 유지되기 때문이다. 반대로 여기서 예외를 던지면 DB 장애가 곧
 * 정상 사용자의 온보딩 차단(가용성 사고)이 된다.
 *
 * <h3>만료 행 정리</h3>
 * <p>1차 정리는 {@link RoleClaimAttemptPurgeJob}(전용 스케줄러)이 담당한다. 여기의 기회적 정리
 * (N회 쓰기마다 1회)는 <b>보조</b>다 — role-claim 은 트래픽이 낮아 기회적 임계에 도달하는 데 수개월이
 * 걸리므로 그것만으로는 만료 행이 사실상 영구 축적된다(DEV_FIX H-4). 둘 다 중복 실행이 무해한
 * 조건부 DELETE 라 2노드 동시 실행에 안전하다.
 */
@Slf4j
@Component
public class JdbcRoleClaimAttemptStore implements RoleClaimAttemptStore {

    /** 기회적 만료 정리 주기 — N회 기록마다 1회 DELETE. */
    static final long PURGE_EVERY_N_CALLS = 100L;

    private static final String UPSERT_ATTEMPT = """
            INSERT INTO LS_AUTHRT_GRANT_ATMPT
                (ATMPT_SE_CD, ATMPT_IDNTFR, BGNG_DT, ATMPT_NMTM, EXPD_DT, REG_DT, MDFCN_DT)
            VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + (INTERVAL '1 second' * ?),
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (ATMPT_SE_CD, ATMPT_IDNTFR, BGNG_DT)
            DO UPDATE SET ATMPT_NMTM = LS_AUTHRT_GRANT_ATMPT.ATMPT_NMTM + 1,
                          MDFCN_DT   = CURRENT_TIMESTAMP
            RETURNING ATMPT_NMTM
            """;

    private static final String PURGE_EXPIRED =
            "DELETE FROM LS_AUTHRT_GRANT_ATMPT WHERE EXPD_DT < CURRENT_TIMESTAMP";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNew;
    private final AtomicLong writeCounter = new AtomicLong();

    public JdbcRoleClaimAttemptStore(
            @Qualifier("controlDataSource") DataSource controlDataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTransactionManager) {
        this.jdbcTemplate = new JdbcTemplate(controlDataSource);
        this.requiresNew = new TransactionTemplate(controlTransactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public int recordAttempt(String seCd, String idntfr, LocalDateTime windowStart, Duration ttl) {
        try {
            Integer count = requiresNew.execute(status -> jdbcTemplate.queryForObject(
                    // 파라미터 바인딩 전용 (CWE-89) — 문자열 연결 없음.
                    UPSERT_ATTEMPT, Integer.class, seCd, truncate(idntfr), windowStart, toSeconds(ttl)));
            purgeExpiredOpportunistically();
            return count == null ? UNAVAILABLE : count;
        } catch (DataAccessException e) {
            // 로컬 카운터가 1차 방어를 유지한다 — 예외 클래스명만 남기고 상세는 노출하지 않는다(CWE-209).
            log.warn("[RoleClaim] shared attempt-counter record failed reason={}", e.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    /** 만료 행 정리 — 운영/테스트에서 직접 호출 가능. 2노드 동시 실행해도 무해(조건부 DELETE). */
    public int purgeExpired() {
        try {
            Integer deleted = requiresNew.execute(status -> jdbcTemplate.update(PURGE_EXPIRED));
            return deleted == null ? 0 : deleted;
        } catch (DataAccessException e) {
            log.warn("[RoleClaim] attempt-counter purge failed reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    private void purgeExpiredOpportunistically() {
        if (writeCounter.incrementAndGet() % PURGE_EVERY_N_CALLS == 0) {
            purgeExpired();
        }
    }

    /** 식별자 컬럼 길이(36, 식별자V36) 초과 방지 — 저장 전 절단. */
    private static String truncate(String idntfr) {
        if (idntfr == null) {
            return "";
        }
        return idntfr.length() <= 36 ? idntfr : idntfr.substring(0, 36);
    }

    /** TTL → 초. {@code INTERVAL '1 second' * ?} 의 곱셈 인자로 바인딩한다. */
    private static double toSeconds(Duration ttl) {
        return ttl == null ? 0d : ttl.toMillis() / 1000.0d;
    }
}
