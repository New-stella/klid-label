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

    /** 발송 결과 코드 — STTS_CD(큐 처리 상태)와 분리된 순수 발송 결과. */
    public static final String SEND_RSLT_SUCCESS = "SUCCESS";
    public static final String SEND_RSLT_FAILED = "FAILED";

    /** 기본 최대 재시도 횟수. */
    public static final int DEFAULT_MAX_RETRY = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "QUEUE_SN")
    private Long queueSn;

    @Column(name = "IDMP_KEY", length = 64, nullable = false, unique = true)
    private String idmpKey;

    @Column(name = "EVNT_TYPE_CD", length = 32, nullable = false)
    private String eventTypeCd;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "PAYLOAD_CN", columnDefinition = "TEXT", nullable = false)
    private String payloadCn;

    @Column(name = "RTRY_NMTM", nullable = false)
    private int rtryCnt;

    @Column(name = "MAX_RTRY_NMTM", nullable = false)
    private int maxRtryCnt;

    @Column(name = "STTS_CD", length = 16, nullable = false)
    private String sttsCd;

    /** 발송 결과 코드 — SUCCESS/FAILED. 과거 행은 NULL 가능(관찰 미기록). */
    @Column(name = "SEND_RSLT_CD", length = 16)
    private String sendRsltCd;

    @Column(name = "LAST_ERR_MSG_CN", length = 2000)
    private String lastErrMsg;

    @Column(name = "NEXT_RTRY_DT")
    private LocalDateTime nextRtryDt;

    @Column(name = "DLQ_DT")
    private LocalDateTime dlqDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

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
        q.idmpKey = idempotencyKey;
        q.eventTypeCd = eventType;
        q.rawSn = rawSn;
        q.payloadCn = payload;
        q.rtryCnt = 0;
        q.maxRtryCnt = DEFAULT_MAX_RETRY;
        q.sttsCd = STATUS_PENDING;
        // 즉시 발송 실패로 폴백 큐 진입 → 발송 결과는 FAILED.
        q.sendRsltCd = SEND_RSLT_FAILED;
        LocalDateTime now = LocalDateTime.now();
        q.nextRtryDt = now;
        q.regDt = now;
        q.mdfcnDt = now;
        return q;
    }

    /**
     * 즉시 발송 성공 관찰용 정적 팩토리.
     *
     * <p>{@code STTS_CD=SUCCEEDED}(터미널) + {@code SEND_RSLT_CD=SUCCESS} 로 적재해
     * 재시도 잡(STTS_CD=PENDING)·depth 게이지(PENDING+RETRYING) 대상에서 제외된다.
     *
     * @param idempotencyKey 중복 방지 키 (필수)
     * @param eventType      이벤트 타입 — TASK_COMPLETED / TASK_MODIFIED (필수)
     * @param rawSn          영상 단위 식별자 (필수)
     * @param payload        JSON 직렬화된 페이로드 (필수)
     */
    public static LsControlNotifyFallback succeeded(String idempotencyKey,
                                                    String eventType,
                                                    Long rawSn,
                                                    String payload) {
        LsControlNotifyFallback q = pending(idempotencyKey, eventType, rawSn, payload);
        q.sttsCd = STATUS_SUCCEEDED;
        q.sendRsltCd = SEND_RSLT_SUCCESS;
        q.nextRtryDt = null;
        return q;
    }

    /** Quartz job 이 항목을 RETRYING 으로 표시 — 동시 처리 방지. */
    public void markRetrying() {
        this.sttsCd = STATUS_RETRYING;
        this.mdfcnDt = LocalDateTime.now();
    }

    /** 성공. */
    public void markSucceeded() {
        this.sttsCd = STATUS_SUCCEEDED;
        this.sendRsltCd = SEND_RSLT_SUCCESS;
        this.nextRtryDt = null;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 실패 시 다음 retry 스케줄. 최대 횟수 초과 시 DEAD_LETTER.
     *
     * <p>백오프: 2^retryCount 분 (1, 2, 4, 8, 16). cap 60분.
     *
     * @return true 면 DEAD_LETTER 전이됨
     */
    public boolean failAndSchedule(String error) {
        this.rtryCnt += 1;
        // CWE-117 + CWE-209: 제어문자 + 토큰/URL 마스킹 후 저장.
        this.lastErrMsg = sanitizeError(error);
        // 재시도 실패 → 발송 결과는 FAILED (PENDING 복귀·DEAD_LETTER 공통).
        this.sendRsltCd = SEND_RSLT_FAILED;
        LocalDateTime now = LocalDateTime.now();
        if (this.rtryCnt > this.maxRtryCnt) {
            this.sttsCd = STATUS_DEAD_LETTER;
            this.dlqDt = now;
            this.nextRtryDt = null;
            this.mdfcnDt = now;
            return true;
        }
        long backoffMin = Math.min(60L, 1L << Math.min(this.rtryCnt, 6));
        this.sttsCd = STATUS_PENDING;
        this.nextRtryDt = now.plusMinutes(backoffMin);
        this.mdfcnDt = now;
        return false;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
        if (this.sttsCd == null) this.sttsCd = STATUS_PENDING;
        if (this.maxRtryCnt <= 0) this.maxRtryCnt = DEFAULT_MAX_RETRY;
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
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
