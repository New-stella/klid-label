package kr.co.cudo.authoring.auth.service;

import com.github.benmanes.caffeine.cache.Ticker;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 권한 자가부여 rate limit — 계정 축 / 전역 축 / 노드 공유 축 / 저장소 장애 / 맵 회수 (A-ISSUE-18).
 */
class RoleClaimRateLimiterTest {

    /** 노드 간 공유 저장소 대역 — 여러 limiter 인스턴스가 같은 맵을 본다. */
    private static class InMemoryStore implements RoleClaimAttemptStore {
        private final Map<String, Integer> counters = new HashMap<>();

        @Override
        public synchronized int recordAttempt(String seCd, String idntfr, LocalDateTime windowStart, Duration ttl) {
            return counters.merge(seCd + '|' + idntfr + '|' + windowStart, 1, Integer::sum);
        }
    }

    /** 항상 장애를 신고하는 저장소 (DB 다운 시뮬레이션). */
    private static class UnavailableStore implements RoleClaimAttemptStore {
        @Override
        public int recordAttempt(String seCd, String idntfr, LocalDateTime windowStart, Duration ttl) {
            return UNAVAILABLE;
        }
    }

    private static void assertRejected(RoleClaimRateLimiter limiter, String account) {
        assertThatThrownBy(() -> limiter.consumeOrReject(account))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("계정_시도_5회_초과시_429")
    void accountAxisRejectsOverLimit() {
        RoleClaimRateLimiter limiter = new RoleClaimRateLimiter(null, 5, 1000);

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> limiter.consumeOrReject("1001")).doesNotThrowAnyException();
        }
        assertRejected(limiter, "1001");
    }

    @Test
    @DisplayName("무권한_계정_여러개로_번갈아_시도해도_전역_카운터로_차단됨")
    void globalAxisRejectsCrossAccountAmplification() {
        // given: 계정 한도(5)는 넉넉하고 전역 한도만 3 — 계정 축만 있으면 무제한 증폭되는 조건
        RoleClaimRateLimiter limiter = new RoleClaimRateLimiter(null, 5, 3);

        // when: 서로 다른 계정 A/B/C 로 1회씩 (계정 축은 어느 것도 초과하지 않음)
        limiter.consumeOrReject("A");
        limiter.consumeOrReject("B");
        limiter.consumeOrReject("C");

        // then: 전역 축이 4번째 시도를 차단한다 (계정이 달라도 우회 불가)
        assertRejected(limiter, "D");
    }

    @Test
    @DisplayName("rate_limit_이_노드간_공유되어_2인스턴스_합산으로_차단됨")
    void sharedStoreAggregatesAcrossNodes() {
        // given: 2노드 Active-Active 를 모사한 두 limiter 인스턴스가 하나의 공유 저장소를 본다.
        InMemoryStore shared = new InMemoryStore();
        RoleClaimRateLimiter node1 = new RoleClaimRateLimiter(shared, 4, 1000);
        RoleClaimRateLimiter node2 = new RoleClaimRateLimiter(shared, 4, 1000);

        // when: 같은 계정으로 노드당 2회씩(로컬 카운터로는 각각 2 — 어느 쪽도 한도 미달)
        node1.consumeOrReject("1001");
        node1.consumeOrReject("1001");
        node2.consumeOrReject("1001");
        node2.consumeOrReject("1001");

        // then: 공유 합산이 5 가 되는 다음 시도는 어느 노드에서든 차단된다(임계가 노드 수만큼 곱해지지 않음).
        assertRejected(node2, "1001");
        assertRejected(node1, "1001");
    }

    @Test
    @DisplayName("rate_limit_저장소_장애시에도_로컬_카운터로_차단이_유지됨")
    void localCounterKeepsEnforcingWhenStoreUnavailable() {
        // given: 공유 저장소가 항상 UNAVAILABLE (DB 장애 — 공격자가 유발 가능한 조건)
        RoleClaimRateLimiter limiter = new RoleClaimRateLimiter(new UnavailableStore(), 3, 1000);

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> limiter.consumeOrReject("1001")).doesNotThrowAnyException();
        }

        // then: 완전 fail-open 이 아니라 로컬 카운터가 최종 방어선으로 임계를 강제한다.
        assertRejected(limiter, "1001");
    }

    /**
     * DEV_FIX M-3 — 버킷 키({@code BGNG_DT})는 <b>노드 공통 PK</b>다. JVM 기본 TZ 로 산출하면 두 노드의
     * 컨테이너 TZ 가 어긋났을 때 같은 순간의 요청이 서로 다른 행을 갱신해 공유 카운터가 갈라진다.
     *
     * <p>JVM 기본 TZ 를 UTC 에서 9시간 떨어뜨린 상태로 실행해, 저장소로 넘어가는 windowStart 가
     * <b>로컬 시각이 아니라 UTC</b> 임을 고정한다. {@code LocalDateTime.now()} 로 되돌리면 실패한다.
     */
    @Test
    @DisplayName("버킷_시각은_JVM_타임존과_무관하게_UTC_로_고정된다")
    void windowStartIsUtcRegardlessOfJvmTimeZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            CapturingStore store = new CapturingStore();
            new RoleClaimRateLimiter(store, 5, 1000).consumeOrReject("1001");

            LocalDateTime expectedUtc = LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            LocalDateTime localBucket = LocalDateTime.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);

            assertThat(store.lastWindowStart)
                    .as("노드 공통 PK 축이므로 UTC 로 통일되어야 한다")
                    .isEqualTo(expectedUtc);
            assertThat(store.lastWindowStart)
                    .as("JVM 로컬 타임존 버킷을 쓰면 노드 간 카운터가 갈라진다")
                    .isNotEqualTo(localBucket);
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    /**
     * ★flake 수정 가드 — {@code windowStart} 산출이 실제로 주입된 {@link Clock} 을 쓰는지 직접
     * 단언한다(mutation 으로는 재현이 안 된다 — 시스템 시계로 되돌려도 분 경계 flake 는 확률적(~3%)이라
     * 결정론적 회귀 테스트가 되지 못한다).
     *
     * <p>주입한 Clock 을 실제 "지금"과 확실히 다른 과거 시각(2000-01-01)에 고정한다. 구현이 Clock 을
     * 무시하고 시스템 시계를 쓰면 저장소로 넘어간 windowStart 가 <b>실제 현재 분 버킷</b>과 같아지므로
     * {@code isNotEqualTo(realNowBucket)} 단언이 실패한다.
     */
    @Test
    @DisplayName("windowStart는_주입된_Clock을_따른다_시스템_시계를_쓰지_않는다")
    void windowStartUsesInjectedClockNotSystemClock() {
        // given: 실제 "지금"과 결코 같을 수 없는 고정 과거 시각의 Clock
        Instant fixedInstant = Instant.parse("2000-01-01T00:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        CapturingStore store = new CapturingStore();
        RoleClaimRateLimiter limiter = new RoleClaimRateLimiter(store, 5, 1000, fixedClock);

        // when
        limiter.consumeOrReject("1001");

        // then: 버킷 시각은 주입된 Clock 기준이며, 실제 시스템 시계의 현재 분 버킷과는 다르다.
        LocalDateTime expected = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime realNowBucket = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);

        assertThat(store.lastWindowStart)
                .as("주입된 Clock 기준 버킷이어야 한다")
                .isEqualTo(expected);
        assertThat(store.lastWindowStart)
                .as("시스템 시계를 썼다면 실제 현재 분 버킷과 같아진다 — Clock 미사용으로의 회귀를 잡는다")
                .isNotEqualTo(realNowBucket);
    }

    /**
     * ★flake 재현 시나리오 — 고정 Clock 을 주입하면(분 경계 근접 여부와 무관하게) 연속 6회 호출이
     * 항상 같은 분 버킷에 들어가 6회째가 결정론적으로 429 다(RoleClaimServiceTest 의 수정 의도와 동일).
     */
    @Test
    @DisplayName("고정_Clock_주입시_분경계와_무관하게_6회째_결정론적으로_429")
    void fixedClockMakesSixthAttemptDeterministicallyRateLimited() {
        // given: 분 경계 바로 직전(59.999초)에 고정 — Clock.fixed 는 흐르지 않으므로 6회 호출 내내 불변.
        Clock justBeforeMinuteBoundary = Clock.fixed(
                Instant.parse("2026-01-01T00:00:59.999Z"), ZoneOffset.UTC);
        RoleClaimRateLimiter limiter = new RoleClaimRateLimiter(null, 5, 1000, justBeforeMinuteBoundary);

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> limiter.consumeOrReject("1001")).doesNotThrowAnyException();
        }
        assertRejected(limiter, "1001");
    }

    /** 저장소로 넘어간 windowStart 를 관찰한다. */
    private static class CapturingStore implements RoleClaimAttemptStore {
        private LocalDateTime lastWindowStart;

        @Override
        public int recordAttempt(String seCd, String idntfr, LocalDateTime windowStart, Duration ttl) {
            this.lastWindowStart = windowStart;
            return 1;
        }
    }

    @Test
    @DisplayName("자가부여_실패_시도_맵이_TTL_로_회수됨")
    void localCountersExpireByTtl() {
        // given: 가상 시계를 주입한 limiter (구 구현은 ConcurrentHashMap 이라 key 가 영구 잔존 — CWE-770)
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        RoleClaimRateLimiter limiter = new RoleClaimRateLimiter(null, 5, 1000, ticker);

        limiter.consumeOrReject("1001");
        limiter.consumeOrReject("2001");
        // 계정 3개 축(1001, 2001) + 전역 축 1개 = 3 엔트리
        assertThat(limiter.localEntryCount()).isEqualTo(3);

        // when: 보존 기간(10분)을 넘겨 시계를 전진
        nanos.addAndGet(Duration.ofMinutes(11).toNanos());

        // then: 만료 엔트리가 회수되어 맵이 무한 증가하지 않는다.
        assertThat(limiter.localEntryCount()).isZero();
    }
}
