package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.common.security.webhook.JdbcWebhookGuardStore;
import kr.co.cudo.authoring.common.security.webhook.WebhookGuardPurgeJob;
import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimitStore;
import org.junit.jupiter.api.Assumptions;
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
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹훅 가드 공유 저장소 통합 검증 (실 PostgreSQL Testcontainer).
 *
 * <p>필터는 트랜잭션 밖에서 동작하므로 UNIQUE 위반이 트랜잭션 전체를 abort(25P02) 시키는 경로를
 * 만들면 안 된다. 구현이 {@code INSERT ... ON CONFLICT DO NOTHING} + updateCount 판정을 쓰는지
 * (=예외 없이 replay 를 판정하는지) 실 DB 로 확인한다(S-07).
 */
@SpringBootTest
@ActiveProfiles("local")
class JdbcWebhookGuardStoreIT {

    @Autowired private JdbcWebhookGuardStore store;
    @Autowired private WebhookGuardPurgeJob purgeJob;
    @Autowired @Qualifier("controlDataSource") private DataSource controlDataSource;

    @Test
    @DisplayName("동일_nonce_두번_소비시_두번째는_replay_이며_예외가_발생하지_않는다")
    void consumeTwice_secondIsReplay() {
        String hash = UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000";
        String nonce = hash.substring(0, 64);

        assertThat(store.consume(nonce, "/v1/genai/callback", Duration.ofMinutes(6))).isTrue();
        assertThat(store.consume(nonce, "/v1/genai/callback", Duration.ofMinutes(6)))
                .as("동일 서명 재전송은 replay 로 판정돼야 한다 (UNIQUE 위반 예외 없이)")
                .isFalse();
    }

    @Test
    @DisplayName("만료된_nonce_는_정리되어_재소비가_가능하다")
    void expiredNonce_isPurged() {
        String nonce = (UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "")).substring(0, 64);

        // 이미 만료된 TTL 로 소비 → 정리 대상
        assertThat(store.consume(nonce, "/v1/genai/callback", Duration.ofSeconds(-1))).isTrue();
        store.purgeExpired();

        assertThat(store.consume(nonce, "/v1/genai/callback", Duration.ofMinutes(6)))
                .as("만료 정리 후에는 동일 해시를 다시 소비할 수 있어야 한다(무한 증가 방지)")
                .isTrue();
    }

    @Test
    @DisplayName("rate_limit_카운터가_공유저장소에_윈도우별로_누적된다")
    void failureCounterAccumulates() {
        // 공유 컨테이너에서 난수 IP 가 충돌하면 first==1 단정이 플레이키해진다 → 고유 키 보장(L-5).
        String ip = uniqueIp();
        LocalDateTime window = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        int first = store.recordFailure(ip, window, Duration.ofMinutes(10));
        int second = store.recordFailure(ip, window, Duration.ofMinutes(10));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(store.currentFailures(ip, window)).isEqualTo(2);

        // 다른 윈도우는 독립 집계
        assertThat(store.currentFailures(ip, window.minusMinutes(5)))
                .as("윈도우가 다르면 카운터는 독립이어야 한다")
                .isZero();
    }

    @Test
    @DisplayName("미기록_IP_조회는_0_이며_저장소_장애로_오인되지_않는다")
    void unknownIp_returnsZeroNotUnavailable() {
        int count = store.currentFailures("203.0.113.254",
                LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES));

        assertThat(count).isNotEqualTo(WebhookRateLimitStore.UNAVAILABLE);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("성공시_공유_실패카운터가_즉시_해제된다")
    void resetFailures_clearsSharedCounter() {
        String ip = uniqueIp();
        LocalDateTime window = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        store.recordFailure(ip, window, Duration.ofMinutes(10));
        store.recordFailure(ip, window, Duration.ofMinutes(10));
        assertThat(store.currentFailures(ip, window)).isEqualTo(2);

        store.resetFailures(ip, window);

        assertThat(store.currentFailures(ip, window))
                .as("공유 카운터가 남으면 유효 서명 콜백도 그 분 동안 2노드 전체에서 429 (M-4)")
                .isZero();
    }

    @Test
    @DisplayName("VLM_무서명_실패기록만_반복돼도_만료된_실패카운터_행이_정리된다")
    void expiredFailureRows_arePurged_withoutAnySuccessfulCallback() {
        // 성공 콜백(=nonce consume) 이 한 건도 없는 상황을 재현한다.
        //   과거 정리는 consume() 안에서만 카운트해서, 무서명 VLM 경로 실패만 쌓이면 LS_WHK_FAIL_NMTM
        //   이 영원히 정리되지 않고 무한 증가했다(H-5).
        String ip = uniqueIp();
        LocalDateTime window = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(30);
        store.recordFailure(ip, window, Duration.ofMinutes(-1)); // 이미 만료된 TTL
        assertThat(store.currentFailures(ip, window)).isEqualTo(1);

        int purged = purgeJob.run();

        assertThat(purged).as("주기 정리 잡이 만료 행을 삭제해야 한다").isPositive();
        assertThat(store.currentFailures(ip, window))
                .as("만료된 실패 카운터 행이 남으면 테이블이 무한 증가한다")
                .isZero();
    }

    @Test
    @DisplayName("만료된_nonce_와_실패카운터가_주기_정리잡으로_함께_삭제된다")
    void purgeJob_removesBothTables() {
        String nonce = (UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "")).substring(0, 64);
        String ip = uniqueIp();
        LocalDateTime window = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(30);
        store.consume(nonce, "/v1/genai/callback", Duration.ofSeconds(-1));
        store.recordFailure(ip, window, Duration.ofMinutes(-1));

        purgeJob.run();

        assertThat(store.consume(nonce, "/v1/genai/callback", Duration.ofMinutes(6)))
                .as("만료 nonce 가 정리되면 동일 해시를 다시 소비할 수 있다")
                .isTrue();
        assertThat(store.currentFailures(ip, window)).isZero();
    }

    /**
     * <b>R-6 회귀 고정</b> — 만료 시각을 <b>앱 시계</b>로 쓰고 삭제는 <b>DB 시계</b>로 하면, 앱 TZ 가 DB TZ
     * 보다 뒤인 배포(백엔드 컨테이너 UTC + DB 서버 KST 등)에서 <b>방금 넣은 행이 이미 만료</b>로 판정돼
     * 살아 있는 nonce 가 purge 된다 → replay 방어가 purge 주기 안에서 붕괴한다.
     *
     * <p>JVM 기본 TZ 를 12시간 뒤로 옮겨 그 형상을 재현한다. 이미 열려 있는 풀 커넥션의 세션 TZ 는
     * 바뀌지 않으므로 DB 시계는 그대로다(= 앱/DB 시계 어긋남). 삽입이 DB 시계를 쓰면 행이 살아남고,
     * 앱 시계로 되돌리면 purge 후 재소비가 가능해져(=replay 통과) 이 테스트가 실패한다.
     */
    @Test
    @DisplayName("앱_TZ가_DB_TZ보다_뒤여도_살아있는_nonce와_실패카운터가_purge되지_않는다_시계_통일")
    void liveRowsSurvivePurge_whenAppClockIsBehindDbClock() {
        String nonce = (UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "")).substring(0, 64);
        String ip = uniqueIp();
        LocalDateTime window = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        store.currentFailures(ip, window); // 커넥션 워밍업 — 세션 TZ 를 원래 값으로 고정
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12")); // 앱 시계를 12시간 뒤로
            LocalDateTime dbNow = dbNow();
            Assumptions.assumeTrue(
                    Duration.between(LocalDateTime.now(), dbNow).toHours() >= 1,
                    "앱/DB 시계 어긋남을 만들지 못했다(커넥션이 새로 열려 세션 TZ 가 함께 이동) — 검증 생략");

            assertThat(store.consume(nonce, "/v1/genai/callback", Duration.ofMinutes(6))).isTrue();
            store.recordFailure(ip, window, Duration.ofMinutes(10));

            store.purgeExpired();

            assertThat(store.consume(nonce, "/v1/genai/callback", Duration.ofMinutes(6)))
                    .as("살아 있는 nonce 가 purge 되면 동일 서명 재전송이 신규로 통과한다 — replay 방어 붕괴 (R-6)")
                    .isFalse();
            assertThat(store.currentFailures(ip, window))
                    .as("살아 있는 실패 카운터가 purge 되면 공유 rate limit 이 사실상 비활성이 된다 (R-6)")
                    .isEqualTo(1);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /**
     * DB 세션의 <b>벽시계</b>(TIMESTAMP WITHOUT TIME ZONE 기준) — 앱 시계와의 어긋남 확인용.
     *
     * <p>{@code queryForObject(..., LocalDateTime.class)} 로 읽으면 드라이버가 <b>JVM 기본 TZ 로 변환</b>해
     * 어긋남이 상쇄되어 보이지 않는다. 문자열로 받아 변환을 우회한다(이 값이 곧 {@code EXPD_DT} 비교에
     * 쓰이는 축이다).
     */
    private LocalDateTime dbNow() {
        String text = new JdbcTemplate(controlDataSource).queryForObject(
                "SELECT to_char(CURRENT_TIMESTAMP, 'YYYY-MM-DD\"T\"HH24:MI:SS')", String.class);
        return LocalDateTime.parse(text);
    }

    /** 공유 Testcontainer 에서 키 충돌로 인한 플레이키를 없애기 위한 고유 IPv6 리터럴(45자 이내). */
    private static String uniqueIp() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return "2001:db8:" + hex.substring(0, 4) + ":" + hex.substring(4, 8)
                + ":" + hex.substring(8, 12) + ":" + hex.substring(12, 16)
                + ":" + hex.substring(16, 20) + ":" + hex.substring(20, 24);
    }
}
