package kr.co.cudo.authoring.controlnotify.fallback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 2 — 관제서버 outbound 통지 fallback 영속 큐 엔티티.
 *
 * <p>검수 완료(TASK_COMPLETED) / 검수 후 수정(TASK_MODIFIED) 이벤트를
 * 관제서버에 전달 실패 시 재시도를 위해 큐에 적재한다.
 *
 * <h3>상태 전이</h3>
 * <pre>
 *   PENDING ───(retry job pick)──▶ RETRYING ──(success)──▶ SUCCEEDED
 *                                          │
 *                                          └──(fail, count&lt;max)──▶ PENDING (next_retry_at += backoff)
 *                                          │
 *                                          └──(fail, count&gt;=max)──▶ DEAD_LETTER
 * </pre>
 */
@Entity
@Table(name = "LS_CONTROL_NOTIFY_FALLBACK")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsControlNotifyFallback {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_DEAD_LETTER = "DEAD_LETTER";

    /** 기본 최대 재시도 횟수. */
    public static final int DEFAULT_MAX_RETRY = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "QUEUE_SN")
    private Long queueSn;

    @Column(name = "IDEMPOTENCY_KEY", length = 64, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "EVENT_TYPE", length = 32, nullable = false)
    private String eventType;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "PAYLOAD", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "RETRY_COUNT", nullable = false)
    private int retryCount;

    @Column(name = "MAX_RETRY", nullable = false)
    private int maxRetry;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Column(name = "LAST_ERROR", length = 2000)
    private String lastError;

    @Column(name = "NEXT_RETRY_AT")
    private LocalDateTime nextRetryAt;

    @Column(name = "DEAD_LETTER_AT")
    private LocalDateTime deadLetterAt;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 통지 적재용 정적 팩토리.
     *
     * @param idempotencyKey 중복 방지 키 (필수)
     * @param eventType      이벤트 타입 — TASK_COMPLETED / TASK_MODIFIED (필수)
     * @param rawSn          영상 단위 식별자 (필수)
     * @param payload        JSON 직렬화된 페이로드 (필수)
     */
    public static LsControlNotifyFallback pending(String idempotencyKey,
                                                   String eventType,
                                                   Long rawSn,
                                                   String payload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 는 필수입니다.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 는 필수입니다.");
        }
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 는 필수입니다.");
        }
        LsControlNotifyFallback q = new LsControlNotifyFallback();
        q.idempotencyKey = idempotencyKey;
        q.eventType = eventType;
        q.rawSn = rawSn;
        q.payload = payload;
        q.retryCount = 0;
        q.maxRetry = DEFAULT_MAX_RETRY;
        q.status = STATUS_PENDING;
        LocalDateTime now = LocalDateTime.now();
        q.nextRetryAt = now;
        q.createdAt = now;
        q.updatedAt = now;
        return q;
    }

    /** Quartz job 이 항목을 RETRYING 으로 표시 — 동시 처리 방지. */
    public void markRetrying() {
        this.status = STATUS_RETRYING;
        this.updatedAt = LocalDateTime.now();
    }

    /** 성공. */
    public void markSucceeded() {
        this.status = STATUS_SUCCEEDED;
        this.nextRetryAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 실패 시 다음 retry 스케줄. 최대 횟수 초과 시 DEAD_LETTER.
     *
     * <p>백오프: 2^retryCount 분 (1, 2, 4, 8, 16). cap 60분.
     *
     * @return true 면 DEAD_LETTER 전이됨
     */
    public boolean failAndSchedule(String error) {
        this.retryCount += 1;
        // CWE-117 + CWE-209: 제어문자 + 토큰/URL 마스킹 후 저장.
        this.lastError = sanitizeError(error);
        LocalDateTime now = LocalDateTime.now();
        if (this.retryCount > this.maxRetry) {
            this.status = STATUS_DEAD_LETTER;
            this.deadLetterAt = now;
            this.nextRetryAt = null;
            this.updatedAt = now;
            return true;
        }
        long backoffMin = Math.min(60L, 1L << Math.min(this.retryCount, 6));
        this.status = STATUS_PENDING;
        this.nextRetryAt = now.plusMinutes(backoffMin);
        this.updatedAt = now;
        return false;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
        if (this.status == null) this.status = STATUS_PENDING;
        if (this.maxRetry <= 0) this.maxRetry = DEFAULT_MAX_RETRY;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * CWE-117 + CWE-209 — LAST_ERROR 값 정화.
     *
     * <p>외부 WebClient 응답의 에러 메시지에 포함될 수 있는 보안 위험 요소:
     * <ul>
     *   <li>제어 문자 (\r, \n, \t, \0~) → Log Injection 위험</li>
     *   <li>Authorization / Bearer 토큰 / API 키 평문 → Credential 누출</li>
     *   <li>내부 URL (관제서버 호스트 / 포트) → Information Leak</li>
     * </ul>
     *
     * <p>처리 순서: 제어문자 → 공백 / 토큰류 → "***" / URL → "URL_REDACTED" / 1900자 truncate.
     */
    static String sanitizeError(String error) {
        if (error == null) return null;
        String s = error.replaceAll("[\\r\\n\\t\\u0000-\\u001F]", " ");
        // (?i) case-insensitive — Authorization: Bearer xxxx, token=xxxx 등
        s = s.replaceAll("(?i)(authorization|token|bearer\\s+)[^\\s,;]*", "$1***");
        s = s.replaceAll("https?://[^\\s,;]+", "URL_REDACTED");
        return truncate(s, 1900);
    }
}
