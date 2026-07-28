package kr.co.cudo.authoring.common.security.webhook;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 웹훅 인증 실패 카운터 공유 저장소 — rate limit 노드 공유(A-ISSUE-14).
 *
 * <p>기존 카운터는 인스턴스 로컬 {@code ConcurrentHashMap} 이라 2노드 Active-Active 에서 실제 허용
 * 시도가 임계의 2배가 되고 재기동마다 소실됐다.
 *
 * <h3>fail-open</h3>
 * <p>nonce 와 달리 rate limit 은 저장소 장애 시 <b>허용</b>한다(S-05 비대칭 정책). 실패 카운터를 못
 * 읽는다고 정상 벤더 콜백을 막으면 저장소 장애가 곧 결과 유실이 되기 때문이다. 구현체는 예외를
 * 던지지 않고 {@link #UNAVAILABLE} 을 반환한다.
 */
public interface WebhookRateLimitStore {

    /** 저장소 사용 불가 시그널 — 호출자는 제한하지 않고 통과시킨다(fail-open). */
    int UNAVAILABLE = -1;

    /**
     * 해당 윈도우의 실패 횟수를 1 증가시키고 누적값을 돌려준다.
     *
     * @return 누적 실패 횟수, 저장소 장애 시 {@link #UNAVAILABLE}
     */
    int recordFailure(String clientIp, LocalDateTime windowStart, Duration ttl);

    /**
     * 해당 윈도우의 누적 실패 횟수를 조회한다.
     *
     * @return 누적 실패 횟수(없으면 0), 저장소 장애 시 {@link #UNAVAILABLE}
     */
    int currentFailures(String clientIp, LocalDateTime windowStart);

    /**
     * 인증 성공 시 공유 카운터를 즉시 해제한다 (DEV_FIX M-4).
     *
     * <p>로컬 카운터만 리셋하면, 잘못된 서명 5회 뒤 <b>유효 서명 콜백</b>이 와도 공유 집계가 남아
     * 그 분(minute) 버킷 동안 <b>2노드 전체에서</b> 429 가 된다 — 정상 벤더가 자기 실패에 연좌된다.
     *
     * <p>fail-open: 실패해도 예외를 던지지 않는다(윈도우 만료로 자연 소멸).
     */
    void resetFailures(String clientIp, LocalDateTime windowStart);
}
