package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimitStore;
import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebhookRateLimiter} 단위 테스트 — 차단 <b>지속시간</b>과 해제 대칭성 (REDESIGN R-5 / R-7).
 *
 * <p>필터 레벨 테스트({@code HmacWebhookFilterTest})는 "차단되는가" 는 보지만 "얼마나 오래 차단되는가"
 * 는 보지 않는다. 과대 차단은 <b>정상 벤더의 결과 유실 시간</b>이라 별도로 고정한다.
 *
 * <p>스텁 저장소는 실제 테이블({@code LS_WHK_FAIL_NMTM}) 처럼 <b>(IP, 버킷) 을 키</b>로 잡는다 —
 * 버킷을 무시하는 스텁 위에서는 "현재/직전 버킷" 로직이 구조적으로 검증되지 않는다(R-7).
 */
class WebhookRateLimiterTest {

    private static final String IP = "198.18.7.1";

    private static LocalDateTime currentWindow() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * <b>R-5 회귀 고정</b> — 구현은 {@code adoptRemoteBlock} 에서 <b>now 기준 새 60초 창</b>을 각인했다.
     * 그래서 로컬 창이 만료되는 순간 직전 버킷을 읽어 <b>다시 60초 연장</b>했고, 차단이 문서화된 창
     * (60초)의 2배 이상 지속됐다(12:00:59 실패 5회 → 12:02:59 까지). 차단 중에는 체인 진입 자체가
     * 막혀 정상 벤더의 성공 콜백이 reset 을 탈 기회조차 없다.
     *
     * <p>이 테스트는 "직전 버킷에서 승계한 차단이 <b>버킷 지평을 넘겨 연장되지 않는다</b>" 를 단정한다.
     * 각인이 {@code now+60s} 로 새로 잡히면(구 동작) 공유 근거가 사라진 뒤에도 계속 차단되어 실패한다.
     */
    @Test
    @DisplayName("직전_버킷에서_승계한_차단은_새_60초_창을_각인하지_않아_문서화된_창을_넘겨_연장되지_않는다")
    void adoptFromPreviousWindow_doesNotExtendBlockWindow() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        WebhookRateLimiter limiter = new WebhookRateLimiter(store);
        LocalDateTime previous = currentWindow().minusMinutes(1);
        store.seed(IP, previous, WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);

        assertThat(limiter.isLimited(IP))
                .as("직전 버킷의 임계 초과는 롤오버 직후에도 노드 간에 승계돼야 한다 (N-1 유지)")
                .isTrue();

        // 공유 근거 소멸(버킷 만료·purge). 차단은 여기서 끝나야 한다.
        store.clearAll();

        assertThat(limiter.isLimited(IP))
                .as("승계가 'now+60초' 를 새로 각인하면 근거가 사라진 뒤에도 최대 60초 더 차단된다"
                        + " — 문서화된 창의 2배 (R-5)")
                .isFalse();
    }

    @Test
    @DisplayName("현재_버킷에서_승계한_차단은_로컬에_각인되어_DB_왕복없이_유지된다")
    void adoptFromCurrentWindow_isCachedLocally() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        WebhookRateLimiter limiter = new WebhookRateLimiter(store);
        store.seed(IP, currentWindow(), WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);

        assertThat(limiter.isLimited(IP)).isTrue();
        int readsAfterAdopt = store.readCalls;
        store.clearAll(); // 공유를 지워도 현재 버킷 지평(창 종료)까지는 로컬 각인이 유지된다

        assertThat(limiter.isLimited(IP))
                .as("각인하지 않으면 공격 지속 중 요청마다 DB 를 읽어 차단 자체가 부하가 된다")
                .isTrue();
        assertThat(store.readCalls)
                .as("로컬 fast-path 는 공유 저장소를 다시 읽지 않는다")
                .isEqualTo(readsAfterAdopt);
    }

    /**
     * <b>R-7</b> — {@code isLimited} 는 현재 + 직전 버킷을 보는데 {@code reset} 은 현재 버킷만 지웠다.
     * 그래서 버킷 롤오버 직후에는 유효 서명 콜백이 성공해도 직전 버킷 실패가 남아 차단이 유지됐다
     * ("성공 시 즉시 해제"(M-4) 의 의도가 경계에서 무너짐).
     */
    @Test
    @DisplayName("서명검증_성공_해제는_직전_버킷의_실패까지_함께_지운다_조회범위와_대칭")
    void reset_clearsPreviousWindowToo() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        WebhookRateLimiter limiter = new WebhookRateLimiter(store);
        LocalDateTime previous = currentWindow().minusMinutes(1);
        store.seed(IP, previous, WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);
        assertThat(limiter.isLimited(IP)).isTrue();

        limiter.reset(IP);

        assertThat(limiter.isLimited(IP))
                .as("해제 범위가 조회 범위보다 좁으면 성공한 벤더가 롤오버 경계에서 계속 429 를 받는다")
                .isFalse();
    }

    @Test
    @DisplayName("임계_미만_실패는_공유에_기록되지만_차단되지_않는다")
    void belowThreshold_isNotLimited() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        WebhookRateLimiter limiter = new WebhookRateLimiter(store);

        for (int i = 0; i < WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE - 1; i++) {
            limiter.recordFailure(IP);
            assertThat(limiter.isLimited(IP)).as("%d 회차", i + 1).isFalse();
        }
        limiter.recordFailure(IP);

        assertThat(limiter.isLimited(IP))
                .as("임계 도달 시에는 차단돼야 한다")
                .isTrue();
        assertThat(store.currentFailures(IP, currentWindow()))
                .as("차단 전까지의 실패는 전부 공유에 기록된다 (N-1)")
                .isEqualTo(WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);
    }

    /** (IP, 버킷) 키를 반영하는 인메모리 공유 저장소 스텁. */
    private static final class InMemoryRateLimitStore implements WebhookRateLimitStore {
        private final Map<String, Integer> counters = new ConcurrentHashMap<>();
        private int readCalls;

        private static String key(String ip, LocalDateTime window) {
            return ip + "@" + window;
        }

        void seed(String ip, LocalDateTime window, int failures) {
            counters.put(key(ip, window), failures);
        }

        void clearAll() {
            counters.clear();
        }

        @Override
        public int recordFailure(String clientIp, LocalDateTime windowStart, Duration ttl) {
            return counters.merge(key(clientIp, windowStart), 1, Integer::sum);
        }

        @Override
        public int currentFailures(String clientIp, LocalDateTime windowStart) {
            readCalls++;
            return counters.getOrDefault(key(clientIp, windowStart), 0);
        }

        @Override
        public void resetFailures(String clientIp, LocalDateTime windowStart) {
            counters.remove(key(clientIp, windowStart));
        }
    }
}
