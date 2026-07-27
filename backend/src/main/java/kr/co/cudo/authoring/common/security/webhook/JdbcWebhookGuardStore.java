package kr.co.cudo.authoring.common.security.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 웹훅 가드 공유 저장소(PostgreSQL) — nonce(replay 방지) + 실패 카운터(rate limit).
 *
 * <p>2노드 Active-Active 배포에서 노드 간 상태를 공유해야 하므로, 이미 운영 중인 control DB 를
 * 그대로 쓴다(신규 인프라 도입 없음 — {@code LS_WEBHOOK_IDEMPOTENCY} 와 동일 축).
 *
 * <h3>왜 JPA 가 아니라 JdbcTemplate 인가 (S-07)</h3>
 * <p>필터는 트랜잭션 밖에서 동작한다. PostgreSQL 은 UNIQUE 위반이 발생하면 트랜잭션 전체를
 * abort(25P02) 시켜 같은 트랜잭션 안에서 재시도가 불가능하다. 그래서 JPA/서비스 트랜잭션에 얹지 않고
 * <b>단문 {@code INSERT ... ON CONFLICT DO NOTHING} + updateCount 판정</b>으로 예외 경로 자체를 없앤다.
 *
 * <h3>장애 정책 (S-05, 비대칭)</h3>
 * <ul>
 *   <li><b>nonce = fail-closed</b>: 저장소 장애 시 {@link WebhookGuardUnavailableException} → 요청 거부.
 *       replay 판정 불가 상태에서 통과시키면 방어가 사라진다.</li>
 *   <li><b>rate limit = fail-open</b>: 저장소 장애 시 {@link WebhookRateLimitStore#UNAVAILABLE} 반환 →
 *       제한하지 않고 통과. 카운터를 못 읽는다고 정상 콜백을 막으면 장애가 곧 결과 유실이 된다.</li>
 * </ul>
 *
 * <h3>만료 행 정리 (S-15 / DEV_FIX H-5)</h3>
 * <p>정리는 <b>중복 실행이 무해한 형태</b>({@code DELETE ... WHERE EXPD_DT < now()})로만 수행한다.
 * 2노드가 동시에 실행해도 안전하므로 Quartz 클러스터링 없이 {@code WebhookGuardPurgeJob} 이 주기적으로
 * 호출한다.
 *
 * <p>기회적 정리는 <b>모든 쓰기 경로</b>(nonce 소비 + 실패 카운터 기록)에서 카운트한다. 과거에는
 * {@link #consume} 안에서만 카운트해서, ①성공 콜백이 한 건도 없으면(=서명 검증 통과 전무) 정리가 영원히
 * 돌지 않고 ②무서명 VLM 경로의 실패 기록만 쌓이는 상황에서 {@code LS_WHK_FAIL_NMTM} 이 무한 증가했다.
 * 주기 잡이 1차 방어이고 기회적 정리는 보조다.
 *
 * <h3>만료 시각은 <b>DB 시계</b>로만 쓰고 DB 시계로만 지운다 (REDESIGN R-6)</h3>
 * <p>구 구현은 {@code EXPD_DT} 를 <b>앱 시계</b>({@code LocalDateTime.now().plus(ttl)})로 계산하면서
 * 삭제는 <b>DB 시계</b>({@code DELETE ... WHERE EXPD_DT < CURRENT_TIMESTAMP})로 했다. 앱 TZ 가 DB TZ 보다
 * 뒤인 배포(예: 백엔드 컨테이너 UTC + DB 서버 KST — onprem 은 {@code -Duser.timezone=Asia/Seoul} 인데
 * compose 는 TZ 미설정이라 형상별로 갈린다)에서는 <b>방금 넣은 행이 이미 만료</b>로 판정되어
 * 살아 있는 nonce 가 purge 된다 → replay 방어가 purge 주기 안에서 붕괴한다.
 * 그래서 <b>삽입도 DB 시계</b>({@code CURRENT_TIMESTAMP + INTERVAL}) 로 계산해 판정 축을 하나로 만든다.
 *
 * <p>{@code BGNG_DT}(집계 버킷 키)만은 앱이 계산한다 — 이는 <b>시각 비교용이 아니라 노드 공통 키</b>이며
 * (동일 이미지·동일 TZ 로 뜨는 2노드가 같은 키를 만든다), 만료 비교에는 쓰이지 않는다.
 */
@Slf4j
@Component
public class JdbcWebhookGuardStore implements WebhookNonceStore, WebhookRateLimitStore {

    /** 기회적 만료 정리 주기 — N회 소비마다 1회 DELETE. */
    static final long PURGE_EVERY_N_CALLS = 100L;

    /** EXPD_DT 는 DB 시계 기준으로 계산한다 — 앱 시계로 넣고 DB 시계로 지우면 살아 있는 행이 purge 된다(R-6). */
    private static final String INSERT_NONCE = """
            INSERT INTO LS_WHK_SIGN_USE (SIGN_HASH, WHK_PATH_NM, EXPD_DT, REG_DT)
            VALUES (?, ?, CURRENT_TIMESTAMP + (INTERVAL '1 second' * ?), CURRENT_TIMESTAMP)
            ON CONFLICT (SIGN_HASH) DO NOTHING
            """;

    private static final String DELETE_NONCE =
            "DELETE FROM LS_WHK_SIGN_USE WHERE SIGN_HASH = ?";

    private static final String PURGE_NONCE =
            "DELETE FROM LS_WHK_SIGN_USE WHERE EXPD_DT < CURRENT_TIMESTAMP";

    private static final String UPSERT_FAILURE = """
            INSERT INTO LS_WHK_FAIL_NMTM (CALL_IP_ADDR, BGNG_DT, FAIL_NMTM, EXPD_DT, REG_DT, MDFCN_DT)
            VALUES (?, ?, 1, CURRENT_TIMESTAMP + (INTERVAL '1 second' * ?),
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (CALL_IP_ADDR, BGNG_DT)
            DO UPDATE SET FAIL_NMTM = LS_WHK_FAIL_NMTM.FAIL_NMTM + 1,
                          MDFCN_DT  = CURRENT_TIMESTAMP
            RETURNING FAIL_NMTM
            """;

    private static final String SELECT_FAILURE =
            "SELECT FAIL_NMTM FROM LS_WHK_FAIL_NMTM WHERE CALL_IP_ADDR = ? AND BGNG_DT = ?";

    private static final String PURGE_FAILURE =
            "DELETE FROM LS_WHK_FAIL_NMTM WHERE EXPD_DT < CURRENT_TIMESTAMP";

    private static final String DELETE_FAILURE =
            "DELETE FROM LS_WHK_FAIL_NMTM WHERE CALL_IP_ADDR = ? AND BGNG_DT = ?";

    private final JdbcTemplate jdbcTemplate;
    /** 기회적 정리 카운터 — nonce 소비 + 실패 기록 등 모든 쓰기 경로가 증가시킨다(H-5). */
    private final AtomicLong writeCounter = new AtomicLong();

    public JdbcWebhookGuardStore(@Qualifier("controlDataSource") DataSource controlDataSource) {
        this.jdbcTemplate = new JdbcTemplate(controlDataSource);
    }

    @Override
    public boolean consume(String signatureHash, String path, Duration ttl) {
        int inserted;
        try {
            // 파라미터 바인딩 전용 (CWE-89) — 문자열 연결 없음.
            // 만료는 DB 시계 기준(CURRENT_TIMESTAMP + ttl초) — purge 조건과 동일 축(R-6).
            inserted = jdbcTemplate.update(
                    INSERT_NONCE, signatureHash, truncatePath(path), toSeconds(ttl));
        } catch (DataAccessException e) {
            throw new WebhookGuardUnavailableException("webhook nonce 저장소 접근 실패", e);
        }
        purgeExpiredOpportunistically();
        return inserted > 0;
    }

    @Override
    public void release(String signatureHash) {
        try {
            jdbcTemplate.update(DELETE_NONCE, signatureHash);
        } catch (DataAccessException e) {
            // 해제 실패는 승격하지 않는다 — 최악의 경우 동일 서명 재전송이 409 로 흡수될 뿐이다(M-1).
            log.warn("[Webhook] nonce release failed reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code EXPD_DT} 는 <b>DB 시계 기준 {@code now + ttl}</b> 이다(구: 앱 시계 {@code windowStart + ttl}).
     * TTL(10분)이 버킷 폭(1분)보다 충분히 길어 보존 의미는 동일하고, purge 조건({@code CURRENT_TIMESTAMP})
     * 과 축이 같아져 앱/DB TZ 차이로 살아 있는 카운터가 삭제되는 경로가 사라진다(R-6).
     */
    @Override
    public int recordFailure(String clientIp, LocalDateTime windowStart, Duration ttl) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    UPSERT_FAILURE, Integer.class, clientIp, windowStart, toSeconds(ttl));
            purgeExpiredOpportunistically();
            return count == null ? UNAVAILABLE : count;
        } catch (DataAccessException e) {
            // fail-open — 공유 카운터를 못 써도 로컬 카운터가 1차 방어를 유지한다.
            log.warn("[Webhook] shared rate-limit record failed reason={}", e.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    @Override
    public void resetFailures(String clientIp, LocalDateTime windowStart) {
        try {
            jdbcTemplate.update(DELETE_FAILURE, clientIp, windowStart);
        } catch (DataAccessException e) {
            // fail-open — 해제 실패해도 윈도우 만료로 자연 소멸한다(M-4).
            log.warn("[Webhook] shared rate-limit reset failed reason={}", e.getClass().getSimpleName());
        }
    }

    @Override
    public int currentFailures(String clientIp, LocalDateTime windowStart) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    SELECT_FAILURE, Integer.class, clientIp, windowStart);
            return count == null ? 0 : count;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return 0;
        } catch (DataAccessException e) {
            log.warn("[Webhook] shared rate-limit read failed reason={}", e.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    /**
     * 만료 행 정리 — 운영/테스트에서 직접 호출 가능. 2노드 동시 실행해도 무해(조건부 DELETE).
     *
     * @return 삭제된 행 수(nonce + 실패 카운터)
     */
    public int purgeExpired() {
        try {
            return jdbcTemplate.update(PURGE_NONCE) + jdbcTemplate.update(PURGE_FAILURE);
        } catch (DataAccessException e) {
            log.warn("[Webhook] guard purge failed reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 기회적 정리 — <b>모든 쓰기 경로</b>(nonce 소비 + 실패 카운터 기록)에서 카운트한다.
     * 성공 콜백이 전무한 상황에서도 정리가 돌아야 만료 행이 무한 증가하지 않는다(H-5).
     */
    private void purgeExpiredOpportunistically() {
        if (writeCounter.incrementAndGet() % PURGE_EVERY_N_CALLS == 0) {
            purgeExpired();
        }
    }

    /**
     * TTL → 초(소수 허용). {@code INTERVAL '1 second' * ?} 의 곱셈 인자로 바인딩한다.
     * 음수 TTL(테스트의 "이미 만료" 시뮬)도 그대로 표현된다.
     */
    private static double toSeconds(Duration ttl) {
        return ttl == null ? 0d : ttl.toMillis() / 1000.0d;
    }

    /** 경로 컬럼 길이(200) 초과 방지 — 저장 전 절단. */
    private String truncatePath(String path) {
        if (path == null) {
            return "";
        }
        if (path.length() <= 200) {
            return path;
        }
        // 무음 절단은 운영 추적에서 "왜 경로가 잘렸는지" 를 알 수 없게 한다(L-4).
        log.debug("[Webhook] guard path truncated to 200 chars originalLength={}", path.length());
        return path.substring(0, 200);
    }
}
