package kr.co.cudo.authoring.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 권한 자가부여(role-claim) 무차별 대입 억제기 (CWE-307, A-ISSUE-18).
 *
 * <h3>2중 축 × 노드 공유</h3>
 * <p>임계를 강제하는 축은 <b>계정·전역 2개</b>이며, 각 축의 카운터를 공유 저장소에도 기록해
 * 2노드 Active-Active 에서 합산되게 한다(노드 공유는 별도 축이 아니라 두 축의 <i>집계 범위</i>다 —
 * 구 javadoc 의 "3중 축" 표현은 구현과 불일치했다).
 * <ul>
 *   <li><b>계정 축</b>(sub): 한 계정이 창당 {@code accountLimit} 회를 넘기면 429.</li>
 *   <li><b>전역 축</b>(엔드포인트 고정 키): 무권한 계정 A/B/C 를 번갈아 쓰는 증폭을 막는다.
 *       계정 축만으로는 실효 시도량이 (보유 계정 수)배로 늘어난다.</li>
 *   <li><b>노드 공유</b>: 위 두 카운터를 공유 저장소({@link RoleClaimAttemptStore})에도 기록해
 *       임계가 노드 수만큼 곱해지지 않게 한다.</li>
 * </ul>
 *
 * <h3>버킷 키의 시간축 = UTC 고정 (DEV_FIX M-3)</h3>
 * <p>분 단위 버킷 시각({@code BGNG_DT})은 <b>노드 공통 PK 의 일부</b>다. JVM 기본 타임존으로 산출하면
 * 두 노드의 컨테이너 TZ 설정이 어긋났을 때 같은 순간의 요청이 서로 다른 행을 갱신해 공유 카운터가
 * 조용히 갈라진다(= 이 테이블을 만든 목적인 노드 축 통합이 무력화). 그래서 {@link Clock#systemUTC()}
 * (={@code LocalDateTime.now(ZoneOffset.UTC)} 와 동치)로 고정한다. 만료 판정({@code EXPD_DT})은
 * DB 시계({@code CURRENT_TIMESTAMP}) 기준이라 이 변경과 무관하다.
 *
 * <p><b>테스트에서만 {@link Clock} 을 주입 가능</b>하다 — 분 경계를 넘는 순간 시도 횟수 버킷이 바뀌어
 * 카운트가 리셋되는 것을 결정론화하려는 목적이며(운영 기본값은 {@link Clock#systemUTC()} 로 불변),
 * TTL 회수용 {@link Ticker} 주입과는 <b>별개 축</b>이라 서로 대체하지 않는다.
 *
 * <h3>★ 사용처가 둘이며 전역 버킷을 공유한다 — 축을 분리하지 말 것</h3>
 * <p>이 억제기는 단일 {@code @Component} 이고 전역 축 식별자({@link #GLOBAL_IDNTFR})가 엔드포인트와
 * 무관한 고정 문자열이라, <b>역할 승격</b>({@code RoleClaimService})과 <b>관리자 설정 유효창 발급</b>
 * ({@link AdminSessionService})이 <b>같은 전역 버킷을 실제로 나눠 쓴다</b>.
 *
 * <p><b>공유는 의도다.</b> 두 진입점이 <b>같은 관리자 패스워드</b>({@link AdminPasswordVerifier})를
 * 검증하므로, 버킷을 진입점별로 나누면 공격자가 진입점을 번갈아 쓰는 것만으로 <b>실효 한도가 두 배</b>
 * 가 된다. 무차별 대입 억제라는 목적 자체가 무너지므로 <b>축을 분리하지 말 것</b>.
 *
 * <p><b>대가 — 무관한 두 기능의 가용성이 묶인다.</b> 역할 승격 호출이 몰리면(온보딩 같은 <b>정상
 * 사용</b>) 전역 버킷이 소진되어 그 순간 REVIEWER 의 관리자 설정 유효창 발급이 429 로 막힌다.
 * 기능적으로 무관한 두 작업이 서로의 가용성을 갉아먹는다는 뜻이며, <b>알고 받아들인 트레이드오프</b>다.
 *
 * <p>⚠ <b>계정 축은 실질 충돌이 없다</b> — 겹친다고 오해하지 말 것. 역할 승격은 <b>역할이 없는 계정
 * 전용</b>이고(보유자는 409) 관리자 설정은 <b>REVIEWER 전용</b>이며(비보유자는 403), 두 판정 모두
 * {@link #consumeOrReject(String)} 호출보다 <b>앞</b>에 있다. 따라서 같은 계정이 두 계정 카운터를
 * 동시에 소모할 수 없고, 실제로 겹치는 것은 <b>전역 축 하나뿐</b>이다.
 *
 * <h3>IP 축을 두지 않는 이유</h3>
 * <p>신뢰 프록시 파싱 없이 {@code getRemoteAddr()} 로 IP 를 키에 넣으면 리버스 프록시 뒤에서 전 사용자가
 * 한 IP 로 수렴해 한 명의 실패가 전원을 잠근다(가용성 사고). 전역 축이 교차계정 증폭을 이미 덮는다.
 *
 * <h3>장애 정책 — 완전 fail-open 금지</h3>
 * <p>공유 저장소가 {@link RoleClaimAttemptStore#UNAVAILABLE} 을 반환해도 <b>로컬 카운터가 그대로
 * 임계를 강제</b>한다. DB 부하는 공격자가 유발할 수 있는 조건이므로 그 축으로 방어가 꺼지면 안 된다.
 *
 * <h3>맵 무한 증가 차단 (CWE-770)</h3>
 * <p>로컬 카운터는 {@code ConcurrentHashMap} 이 아니라 <b>Caffeine TTL + maximumSize</b> 캐시다.
 * 윈도우가 지난 엔트리는 자동 회수되어 role-claim 을 호출한 모든 sub 가 영구 잔존하지 않는다.
 */
@Slf4j
@Component
public class RoleClaimRateLimiter {

    /** 집계 윈도우 — 분 단위 고정 버킷(노드 공통 키). */
    static final Duration WINDOW = Duration.ofMinutes(1);

    /** 로컬 엔트리 보존 기간 — 윈도우보다 넉넉히 두되 유한(회수 보장). */
    static final Duration LOCAL_RETENTION = Duration.ofMinutes(10);

    /** 로컬 카운터 상한 — 초과 시 Caffeine 이 오래된 엔트리부터 축출(메모리 상한 보장). */
    static final long MAX_LOCAL_ENTRIES = 10_000L;

    static final String SE_ACCOUNT = "ACCOUNT";
    static final String SE_GLOBAL = "GLOBAL";

    /** 전역 축 식별자 — 엔드포인트 단일 카운터. */
    static final String GLOBAL_IDNTFR = "GLOBAL";

    private final Cache<String, AtomicInteger> localCounters;
    private final RoleClaimAttemptStore sharedStore;
    private final int accountLimit;
    private final int globalLimit;
    private final Clock clock;

    // 생성자가 여럿(운영/테스트)이라 주입 대상을 명시한다 — 미지정 시 컨테이너가 후보를 고르지 못한다.
    @Autowired
    public RoleClaimRateLimiter(
            RoleClaimAttemptStore sharedStore,
            @Value("${authoring.auth.role-claim.account-attempts-per-minute:5}") int accountLimit,
            @Value("${authoring.auth.role-claim.global-attempts-per-minute:50}") int globalLimit) {
        this(sharedStore, accountLimit, globalLimit, Ticker.systemTicker(), Clock.systemUTC());
    }

    /** 테스트 전용 — TTL 회수 검증을 위해 가상 시계(Caffeine {@link Ticker})를 주입한다. */
    RoleClaimRateLimiter(RoleClaimAttemptStore sharedStore, int accountLimit, int globalLimit, Ticker ticker) {
        this(sharedStore, accountLimit, globalLimit, ticker, Clock.systemUTC());
    }

    /** 테스트 전용 — 분 버킷({@code windowStart}) 산출을 결정론화하기 위해 {@link Clock} 을 주입한다. */
    RoleClaimRateLimiter(RoleClaimAttemptStore sharedStore, int accountLimit, int globalLimit, Clock clock) {
        this(sharedStore, accountLimit, globalLimit, Ticker.systemTicker(), clock);
    }

    /** 테스트 전용 — {@link Ticker}(TTL 회수)와 {@link Clock}(분 버킷) 을 모두 제어해야 할 때 사용한다. */
    RoleClaimRateLimiter(RoleClaimAttemptStore sharedStore, int accountLimit, int globalLimit, Ticker ticker, Clock clock) {
        this.sharedStore = sharedStore;
        this.accountLimit = accountLimit;
        this.globalLimit = globalLimit;
        this.clock = clock;
        this.localCounters = Caffeine.newBuilder()
                .expireAfterWrite(LOCAL_RETENTION)
                .maximumSize(MAX_LOCAL_ENTRIES)
                .ticker(ticker)
                .build();
    }

    /**
     * 시도 1회를 소비한다. 계정 축 → 전역 축 순으로 검사하며 임계 초과 시
     * {@link ErrorCode#TOO_MANY_REQUESTS} 로 거절한다.
     *
     * @param accountKey 호출자 식별자(JWT sub)
     */
    public void consumeOrReject(String accountKey) {
        // 노드 공통 PK 축이므로 JVM 기본 TZ 가 아니라 UTC 로 고정한다 (DEV_FIX M-3).
        // clock 은 운영에서 Clock.systemUTC() 고정 — 테스트에서만 결정론화를 위해 주입된다.
        LocalDateTime windowStart = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        String account = (accountKey == null || accountKey.isBlank()) ? "-" : accountKey;
        consumeAxis(SE_ACCOUNT, account, windowStart, accountLimit);
        consumeAxis(SE_GLOBAL, GLOBAL_IDNTFR, windowStart, globalLimit);
    }

    private void consumeAxis(String seCd, String idntfr, LocalDateTime windowStart, int limit) {
        String cacheKey = seCd + '\0' + idntfr + '\0' + windowStart;
        int local = localCounters.get(cacheKey, k -> new AtomicInteger()).incrementAndGet();

        int shared = sharedStore == null
                ? RoleClaimAttemptStore.UNAVAILABLE
                : sharedStore.recordAttempt(seCd, idntfr, windowStart, LOCAL_RETENTION);

        // 공유 저장소를 못 읽어도(UNAVAILABLE=-1) 로컬 카운터가 임계를 강제한다(완전 fail-open 금지).
        int effective = Math.max(local, shared);
        if (effective > limit) {
            // CWE-117 — 식별자는 서명된 sub 이지만 방어적으로 제어문자를 제거해 로그한다.
            log.warn("[RoleClaim] rate-limited axis={} attempts={} limit={}", seCd, effective, limit);
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "시도 횟수가 제한을 초과했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    /** 테스트 전용 — 만료 회수 후 남아 있는 로컬 엔트리 수. */
    long localEntryCount() {
        localCounters.cleanUp();
        return localCounters.estimatedSize();
    }

    /** 테스트 전용 — 로컬 카운터 초기화. */
    void reset() {
        localCounters.invalidateAll();
    }
}
