package kr.co.cudo.authoring.version.fallback;

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
 * Phase 3 — Gitea fallback 영속 큐 엔티티.
 *
 * <p>ccarch {@code if-gitea-contents} 명세의 "동기 REST + fallback 큐" 의 영속 백킹 스토어.
 *
 * <h3>상태 전이</h3>
 * <pre>
 *   PENDING ───(retry job pick)──▶ RETRYING ──(success)──▶ SUCCEEDED
 *                                          │
 *                                          └──(fail, count<max)──▶ PENDING (next_retry_at += backoff)
 *                                          │
 *                                          └──(fail, count>=max)──▶ DEAD_LETTER
 * </pre>
 */
@Entity
@Table(name = "LS_GITEA_FALLBACK_QUEUE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsGiteaFallbackQueue {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_DEAD_LETTER = "DEAD_LETTER";

    public static final String OP_PUT = "PUT";
    public static final String OP_GET = "GET";
    public static final String OP_DELETE = "DELETE";

    /** 적재 페이로드 최대 크기 (1 MB) — 큐 비대 차단. */
    public static final int MAX_CONTENT_BYTES = 1 * 1024 * 1024;

    /** 기본 최대 재시도 횟수. */
    public static final int DEFAULT_MAX_RETRY = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "QUEUE_SN")
    private Long queueSn;

    @Column(name = "IDEMPOTENCY_KEY", length = 64, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "OPERATION", length = 16, nullable = false)
    private String operation;

    @Column(name = "PATH", length = 512, nullable = false)
    private String path;

    @Column(name = "BRANCH", length = 128)
    private String branch;

    @Column(name = "COMMIT_MESSAGE", length = 1024)
    private String commitMessage;

    @Column(name = "AUTHOR", length = 64)
    private String author;

    @Column(name = "CONTENT_BASE64", columnDefinition = "LONGTEXT")
    private String contentBase64;

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
     * PUT 적재용 정적 팩토리. 큐 적재 전 호출자가 base64 페이로드 크기를 검증해야 한다.
     */
    public static LsGiteaFallbackQueue putPending(String idempotencyKey,
                                                  String path,
                                                  String branch,
                                                  String commitMessage,
                                                  String author,
                                                  String contentBase64) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 는 필수입니다.");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 는 필수입니다.");
        }
        if (contentBase64 != null && contentBase64.length() > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "CONTENT_BASE64 가 허용 크기(" + MAX_CONTENT_BYTES + ") 를 초과했습니다.");
        }
        LsGiteaFallbackQueue q = new LsGiteaFallbackQueue();
        q.idempotencyKey = idempotencyKey;
        q.operation = OP_PUT;
        q.path = path;
        q.branch = branch;
        q.commitMessage = commitMessage;
        q.author = author;
        q.contentBase64 = contentBase64;
        q.retryCount = 0;
        q.maxRetry = DEFAULT_MAX_RETRY;
        q.status = STATUS_PENDING;
        // M-1: LocalDateTime.now() 이중 호출 제거 — 단일 변수로 일관성 보장.
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
        // MEDIUM-1 (CWE-117 + CWE-209): 제어문자 + 토큰/URL 마스킹 후 저장.
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
     * MEDIUM-1 (CWE-117 + CWE-209) — LAST_ERROR 값 정화.
     *
     * <p>외부 WebClient 응답의 에러 메시지에는 다음이 포함될 수 있어 그대로 저장 시 보안 사고:
     * <ul>
     *   <li>제어 문자 (\r, \n, \t, \0~) → Log Injection 위험</li>
     *   <li>Authorization / Bearer 토큰 / API 키 평문 → Credential 누출</li>
     *   <li>내부 URL (Gitea 호스트 / 포트 / 토큰 쿼리) → Information Leak</li>
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
