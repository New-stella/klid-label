package kr.co.cudo.authoring.version.async;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gitea 커밋 실패 시 재시도 큐 (Phase 8).
 *
 * <p>in-memory ConcurrentLinkedDeque 기반 — 단일 인스턴스 운영 가정.
 * 운영 환경 다중 인스턴스 시 Redis/RabbitMQ 등으로 확장 필요.
 *
 * <p>최대 재시도 횟수 초과 시 큐에서 영구 제거 + 경고 로그 (운영 알림 대상).
 *
 * <p>보안:
 * <ul>
 *   <li>Privacy (CWE-359): labels JSON 내용은 로그 출력 금지 (length 만 노출).</li>
 *   <li>Race Condition (CWE-362): poll/enqueue 는 ConcurrentLinkedDeque 가 보장 + retryCount 는 AtomicInteger.</li>
 * </ul>
 */
@Slf4j
@Component
public class GiteaCommitFallbackQueue {

    /** 정책: 최대 재시도 5회. */
    public static final int MAX_RETRY_ATTEMPTS = 5;

    private final ConcurrentLinkedDeque<RetryItem> deque = new ConcurrentLinkedDeque<>();

    /** 재시도 큐에 등록. */
    public void enqueue(Long srcSn, String labelsJson, String userNo) {
        if (srcSn == null) {
            throw new IllegalArgumentException("srcSn 은 필수입니다.");
        }
        RetryItem item = new RetryItem(srcSn, labelsJson, userNo);
        deque.offerLast(item);
        log.warn("[Version] gitea commit failed - enqueue retry srcSn={} payloadLen={}",
                srcSn, labelsJson == null ? 0 : labelsJson.length());
    }

    /** 가장 오래된 1건 poll (없으면 empty). 호출자가 처리 결과에 따라 markSuccess/markFailure 호출. */
    public Optional<RetryItem> poll() {
        return Optional.ofNullable(deque.pollFirst());
    }

    /** 처리 실패 — 재시도 횟수가 한계 미만이면 다시 큐 끝에 push. 한계 초과면 폐기. */
    public void requeueIfRetryable(RetryItem item) {
        if (item == null) return;
        int attempt = item.retryCount.incrementAndGet();
        if (attempt > MAX_RETRY_ATTEMPTS) {
            log.error("[Version] gitea retry exhausted srcSn={} attempts={} - dropped from queue",
                    item.srcSn, attempt);
            return;
        }
        deque.offerLast(item);
    }

    /** 디버그/모니터링용 — 큐 크기. */
    public int size() {
        return deque.size();
    }

    /** 테스트 보조 — 큐 비우기. */
    public void clear() {
        deque.clear();
    }

    /** 큐 항목. retryCount 는 mutable (AtomicInteger). */
    @Getter
    public static final class RetryItem {
        private final Long srcSn;
        private final String labelsJson;
        private final String userNo;
        private final Instant enqueuedAt;
        private final AtomicInteger retryCount = new AtomicInteger(0);

        public RetryItem(Long srcSn, String labelsJson, String userNo) {
            this.srcSn = srcSn;
            this.labelsJson = labelsJson;
            this.userNo = userNo;
            this.enqueuedAt = Instant.now();
        }

        public int retryCount() {
            return retryCount.get();
        }
    }
}
