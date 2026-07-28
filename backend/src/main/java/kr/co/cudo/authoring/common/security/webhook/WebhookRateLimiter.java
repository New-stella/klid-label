package kr.co.cudo.authoring.common.security.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 웹훅 인증 실패 rate limit — <b>공유 집계 + 로컬 캐시</b> (A-ISSUE-14, CWE-307).
 *
 * <h3>계층의 역할 (DEV_FIX N-1 — 1차 수정의 뿌리 오류 교정)</h3>
 * <p><b>진실원은 공유 집계다.</b> 로컬 카운터는 <b>DB 왕복을 줄이기 위한 캐시</b>이지 별도 임계가 아니다.
 * 1차 수정은 "로컬 임계 도달 이후에만 공유에 기록" 으로 pre-auth write 를 줄이려 했는데,
 * {@link #isLimited}가 로컬 차단 시 <b>DB 를 보지 않고 즉시 429</b> 를 반환해 {@link #recordFailure}
 * 가 더 이상 호출되지 않았다. 그 결과 한 노드가 한 창에 공유에 남기는 값은 <b>정확히 +1</b> 이었고
 * {@code shared >= 임계} 판정이 <b>구조적으로 도달 불가</b>했다 — 전역 집계가 사문화되고
 * 노드 A 5회 + 노드 B 5회 = 총 10회가 통과했다.
 *
 * <p>그래서 규칙을 다음과 같이 고정한다.
 * <ul>
 *   <li><b>실패는 차단에 이르기까지 전부 공유에 기록</b>한다. 임계 도달 여부로 기록을 미루지 않는다.</li>
 *   <li>pre-auth write 축소(H-5)는 <b>"이미 차단된 IP 는 더 쓰지 않는다"</b> 로 달성한다. 이미 임계를
 *       넘긴 카운터에 더 더해도 판정이 바뀌지 않으므로 무해하다. 결과적으로 한 IP 가 한 창에 유발하는
 *       공유 쓰기는 <b>최대 {@value #RATE_LIMIT_FAILURES_PER_MINUTE}회</b> 로 상한이 걸린다.</li>
 *   <li>{@link #isLimited}는 로컬이 미차단이면 <b>반드시 공유를 조회</b>해 다른 노드의 차단을 승계한다.
 *       승계 시 로컬 트래커에 각인({@code adopt})해 이후 요청은 DB 왕복 없이 즉시 차단된다 —
 *       공격 지속 시 오히려 DB 읽기가 줄어든다.</li>
 * </ul>
 *
 * <p>검증 가능한 속성(회귀 테스트로 고정): <b>노드 A 에서 임계까지 실패해 차단된 직후, 같은 IP 가
 * 노드 B 로 가면 노드 B 도 즉시 차단된다.</b>
 *
 * <h3>윈도우</h3>
 * <p>공유 집계는 분 단위 고정 버킷이고 로컬 backoff 는 첫 실패로부터 60초다. 두 축을 맞추기 위해
 * 공유 조회는 <b>현재 버킷과 직전 버킷</b>을 함께 본다(둘 중 하나라도 임계면 차단). 직전 버킷을 보지
 * 않으면 버킷 롤오버 순간에 차단이 다른 노드로 전파되지 않는다.
 *
 * <h3>고정 윈도우 근사의 알려진 한계 (REDESIGN R-7 — 이번 스코프에서 고치지 않음)</h3>
 * <p>공유 집계는 <b>슬라이딩 윈도우가 아니라 분 단위 고정 버킷</b>이라 양방향으로 오차가 있다.
 * <ul>
 *   <li><b>과소 차단</b>: 실패 5회가 두 버킷에 4+1 로 갈리면 어느 버킷도 임계에 닿지 않아
 *       최대 9회까지 통과할 수 있다(버킷을 합산하지 않으므로). 다만 이를 <b>지속</b>하려면 전역
 *       실패율을 분당 4회 이하로 유지해야 해서 임계(5회/분)를 실질적으로 넘지 못한다 —
 *       영구 회피는 성립하지 않는다.</li>
 *   <li><b>과대 차단</b>: 버킷 {@code W} 의 실패는 {@code W} 가 "현재 또는 직전" 인 동안 보이므로,
 *       차단이 최대 {@code W+120s} 까지 이어질 수 있다(실패가 버킷 앞머리에 몰린 경우).
 *       {@link #adoptRemoteBlock} 이 <b>버킷 만료 시각</b>을 각인하므로 그 지평을 <b>넘겨</b>
 *       연장되지는 않는다(R-5).</li>
 * </ul>
 * <p>두 오차를 모두 없애려면 슬라이딩 윈도우(또는 마지막 실패 시각 기반 판정)로 전환해야 하며,
 * 저장 스키마·조회 계약이 함께 바뀌므로 별도 이슈로 다룬다.
 *
 * <p>공유 저장소 장애 시에는 <b>fail-open</b>(로컬 카운터만으로 동작)이다 — 카운터를 못 읽는다고 정상
 * 벤더 콜백을 막으면 저장소 장애가 곧 결과 유실이 되기 때문이다(S-05).
 */
@Slf4j
@Component
public class WebhookRateLimiter {

    /** 분당 실패 횟수 임계. */
    public static final int RATE_LIMIT_FAILURES_PER_MINUTE = 5;

    /** backoff 윈도우 (ms). */
    public static final long RATE_LIMIT_BACKOFF_MS = 60_000L;

    /** 로컬 추적기 메모리 hard cap (CWE-770 resource exhaustion). */
    public static final int MAX_FAILURE_TRACKERS = 4096;

    /** 공유 카운터 행 보존기간 — 윈도우의 2배(정리 여유). */
    private static final Duration SHARED_ROW_TTL = Duration.ofMinutes(10);

    /** 1차 방어 — IP 단위 분당 카운터(노드 로컬). */
    private final Map<String, FailureTracker> failureTrackers = new ConcurrentHashMap<>();

    /** 2차 방어 — 노드 공유 집계. */
    private final WebhookRateLimitStore sharedStore;

    public WebhookRateLimiter(WebhookRateLimitStore sharedStore) {
        this.sharedStore = sharedStore;
    }

    /**
     * 현재 차단 상태인가.
     *
     * <p>로컬이 차단이면 DB 를 건드리지 않고 즉시 차단한다. 로컬이 <b>미차단이면 반드시 공유를 조회</b>해
     * 다른 노드에서 이미 차단된 IP 인지 확인하고, 그렇다면 로컬에 각인해 승계한다(N-1).
     */
    public boolean isLimited(String clientIp) {
        if (isLocallyLimited(clientIp)) {
            return true;
        }
        if (sharedStore == null) {
            return false;
        }
        LocalDateTime window = currentWindowStart();
        // 현재 버킷 → (미달 시) 직전 버킷. 롤오버 직후에도 60초 backoff 가 노드 간에 유지된다.
        if (isSharedLimited(clientIp, window)) {
            adoptRemoteBlock(clientIp, window);
            return true;
        }
        LocalDateTime previous = window.minusMinutes(1);
        if (isSharedLimited(clientIp, previous)) {
            adoptRemoteBlock(clientIp, previous);
            return true;
        }
        return false;
    }

    /**
     * 인증 실패 1건 기록 — 로컬은 항상, 공유는 <b>차단되기 전까지 매번</b>.
     *
     * <p>1차 수정은 "로컬 임계 도달 이후에만 공유 기록" 이었는데, 임계 도달 시 {@link #isLimited}가
     * DB 조회 없이 즉시 429 를 반환해 이 메서드가 더 이상 호출되지 않았다 — 공유 카운터가 한 창에
     * 정확히 1까지만 올라가 전역 판정이 <b>영원히 거짓</b>이 됐다(N-1). 그래서 임계와 무관하게 기록한다.
     *
     * <p>pre-auth write 축소(H-5)는 "이미 차단된 IP 는 더 쓰지 않는다" 로 달성한다 — 이미 임계를 넘긴
     * 카운터에 더해도 판정이 바뀌지 않으므로 생략해도 안전하며, IP 당 공유 쓰기가
     * 창당 {@value #RATE_LIMIT_FAILURES_PER_MINUTE}회로 상한이 걸린다(로컬 트래커 수도
     * {@value #MAX_FAILURE_TRACKERS} 로 cap).
     */
    public void recordFailure(String clientIp) {
        boolean alreadyBlocked = isLocallyLimited(clientIp);
        recordLocalFailure(clientIp);
        if (sharedStore == null || alreadyBlocked) {
            return;
        }
        sharedStore.recordFailure(clientIp, currentWindowStart(), SHARED_ROW_TTL);
    }

    private boolean isSharedLimited(String clientIp, LocalDateTime window) {
        int shared = sharedStore.currentFailures(clientIp, window);
        return shared != WebhookRateLimitStore.UNAVAILABLE && shared >= RATE_LIMIT_FAILURES_PER_MINUTE;
    }

    /**
     * 다른 노드가 이미 차단한 IP 를 이 노드 로컬에도 각인한다.
     *
     * <p>각인하지 않으면 공격이 지속되는 동안 <b>요청마다</b> 공유 저장소를 읽어야 해 차단 자체가 DB
     * 부하가 된다. 각인 이후에는 로컬 fast-path 로 즉시 차단된다.
     *
     * <h3>각인 만료는 "지금부터 60초" 가 아니라 <b>원격 버킷의 만료 시각</b> (REDESIGN R-5)</h3>
     * <p>구 구현은 {@code now + 60s} 를 새로 각인했다. 그래서 로컬 창이 만료되는 순간 직전 버킷을 읽어
     * <b>다시 60초를 연장</b>했고, 차단이 문서화된 창의 2배 이상 지속됐다(결정적 재현: 12:00:59 실패
     * 5회 → 12:01:59 로컬 만료 → 직전 버킷 재판정 → 12:02:59). 정상 벤더가 시크릿 회전 지연으로 5회
     * 실패하면 차단 중에는 체인 진입 자체가 막혀 성공 콜백이 reset 을 탈 기회조차 없으므로, 이 연장은
     * 곧바로 결과 유실 시간이 된다.
     *
     * <p>이제 각인 만료 = {@code 버킷 시작 + 60s} 다. 따라서 차단은 버킷 지평({@code W+120s},
     * 고정 윈도우 근사의 상한)을 <b>넘겨</b> 연장되지 않는다. 직전 버킷에서 승계한 경우 각인은 이미
     * 만료 상태라 로컬 fast-path 로 남지 않는다(판정 자체는 공유 조회로 유지된다).
     *
     * @param window 임계를 넘긴 <b>원격 버킷의 시작 시각</b> — 각인 만료 산출 기준
     */
    private void adoptRemoteBlock(String clientIp, LocalDateTime window) {
        long now = System.currentTimeMillis();
        long windowStartMs = toEpochMillis(window);
        failureTrackers.compute(clientIp, (k, existing) -> {
            // 기존 각인이 더 늦게 만료된다면(=이 노드가 직접 관측한 실패) 그대로 둔다 — 승계가
            // 로컬 판정을 앞당겨 완화하는 일이 없어야 한다.
            if (existing != null && existing.windowStartMs >= windowStartMs
                    && now - existing.windowStartMs <= RATE_LIMIT_BACKOFF_MS) {
                existing.failures.set(Math.max(existing.failures.get(), RATE_LIMIT_FAILURES_PER_MINUTE));
                return existing;
            }
            if (now - windowStartMs > RATE_LIMIT_BACKOFF_MS) {
                // 버킷 지평이 이미 지났다 — 새 창을 각인하면 그게 곧 연장이다(R-5). 각인하지 않는다.
                return existing;
            }
            FailureTracker t = new FailureTracker(windowStartMs);
            t.failures.set(RATE_LIMIT_FAILURES_PER_MINUTE);
            return t;
        });
        enforceHardCap(now);
    }

    /** 분 버킷 시작(LocalDateTime, 시스템 기본 존) → epoch millis. */
    private static long toEpochMillis(LocalDateTime windowStart) {
        return windowStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * <b>서명 검증 성공</b> 시 카운터 초기화 — 로컬 + 공유 모두.
     *
     * <p>공유 카운터를 남겨두면 잘못된 서명 5회 뒤 유효 서명 콜백이 와도 그 분(minute) 버킷 동안
     * 2노드 전체에서 429 가 된다(DEV_FIX M-4).
     *
     * <h3>호출 조건 제한 (DEV_FIX N-2 — 반드시 지킬 것)</h3>
     * <p>이 메서드는 <b>공격자가 스스로 만들 수 없는 인증 성공</b>에서만 호출해야 한다. 현재 유일한
     * 호출처는 HMAC 서명 검증을 통과한 증강 콜백이다. 1차 수정은 무서명 VLM 경로의 <b>하류 2xx</b>
     * (=이미 처리된 {@code request_id} 하나만 알면 누구나 만들 수 있는 200)에서도 호출했는데, 그러면
     * "위조 4회 + 알려진 id 1회" 반복으로 카운터가 영구히 0 이 되어 rate limit 이 전면 무력화된다.
     * 실패 카운터는 명시적 reset 없이도 <b>분 단위 창 만료로 자연 소멸</b>하므로, 인증 성공이 아닌
     * 신호로 reset 하지 않는다.
     *
     * <h3>해제 범위는 조회 범위와 <b>대칭</b>이어야 한다 (REDESIGN R-7)</h3>
     * <p>{@link #isLimited} 는 현재 + <b>직전</b> 버킷을 보는데 해제는 현재 버킷만 지웠다. 그래서 버킷
     * 롤오버 직후에는 유효 서명 콜백이 성공해도 직전 버킷의 실패가 그대로 남아 차단이 유지됐다 —
     * "성공하면 즉시 해제"(M-4) 의 의도가 경계에서 무너진다. 두 버킷을 함께 해제한다.
     */
    public void reset(String clientIp) {
        failureTrackers.remove(clientIp);
        if (sharedStore != null) {
            LocalDateTime window = currentWindowStart();
            sharedStore.resetFailures(clientIp, window);
            sharedStore.resetFailures(clientIp, window.minusMinutes(1));
        }
    }

    /** 테스트/관측용 — 로컬 추적기 크기. */
    public int localTrackerCount() {
        return failureTrackers.size();
    }

    /** 고정 윈도우(분 단위 버킷) 시작 시각 — 공유 집계 키. */
    private LocalDateTime currentWindowStart() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    }

    private boolean isLocallyLimited(String ip) {
        FailureTracker tracker = failureTrackers.get(ip);
        if (tracker == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - tracker.windowStartMs > RATE_LIMIT_BACKOFF_MS) {
            failureTrackers.remove(ip);
            return false;
        }
        return tracker.failures.get() >= RATE_LIMIT_FAILURES_PER_MINUTE;
    }

    /** @return 갱신 후 이 노드의 윈도우 내 누적 실패 횟수 */
    private int recordLocalFailure(String ip) {
        long now = System.currentTimeMillis();
        FailureTracker tracker = failureTrackers.compute(ip, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs > RATE_LIMIT_BACKOFF_MS) {
                FailureTracker t = new FailureTracker(now);
                t.failures.incrementAndGet();
                return t;
            }
            existing.failures.incrementAndGet();
            return existing;
        });
        enforceHardCap(now);
        return tracker == null ? 0 : tracker.failures.get();
    }

    /** 메모리 hard cap (CWE-770): 윈도우 내 다수 IP 실패 폭주 시 무한 증가 차단. */
    private void enforceHardCap(long now) {
        if (failureTrackers.size() <= MAX_FAILURE_TRACKERS) {
            return;
        }
        // 1차: 윈도우 만료 항목 우선 제거
        failureTrackers.entrySet().removeIf(e -> now - e.getValue().windowStartMs > RATE_LIMIT_BACKOFF_MS);
        if (failureTrackers.size() <= MAX_FAILURE_TRACKERS) {
            return;
        }
        // 2차: 그래도 cap 초과 시 가장 오래된 항목부터 강제 evict
        int over = failureTrackers.size() - MAX_FAILURE_TRACKERS;
        failureTrackers.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(t -> t.windowStartMs)))
                .limit(over)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(failureTrackers::remove);
    }

    /** IP 단위 실패 추적기. */
    private static final class FailureTracker {
        final long windowStartMs;
        final AtomicInteger failures = new AtomicInteger(0);

        FailureTracker(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}
