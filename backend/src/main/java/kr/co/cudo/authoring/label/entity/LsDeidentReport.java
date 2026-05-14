package kr.co.cudo.authoring.label.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 3 — 비식별 누락 신고 (LS_DEIDENT_REPORT).
 *
 * <p>라벨러(WORKER) 또는 검수자(REVIEWER) 가 라벨링 중 비식별 미흡(얼굴/번호판 미블러 등)
 * 을 발견하면 본 엔티티를 INSERT 한다.
 *
 * <p>상태 전이:
 * <ul>
 *   <li>{@link #STATUS_OPEN}      — 신고 직후 (LS_DATA_RAW.LOCK_STTS_CD 가 LOCKED_FOR_REDEIDENT 로 잠김).</li>
 *   <li>{@link #STATUS_RESOLVED}  — 재비식별 성공 시 (DeidentifyStep 가 호출).</li>
 *   <li>{@link #STATUS_DISMISSED} — 검수자가 반려 (현재 Phase 범위 밖, 향후 확장).</li>
 * </ul>
 */
@Entity
@Table(name = "LS_DEIDENT_REPORT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDeidentReport {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RPRT_SN")
    private Long rprtSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "REPORTER_NO", nullable = false)
    private Long reporterNo;

    @Column(name = "REASON", nullable = false, length = 1000)
    private String reason;

    @Column(name = "STTS_CD", nullable = false, length = 16)
    private String sttsCd;

    @Column(name = "RPRT_DT", nullable = false)
    private LocalDateTime rprtDt;

    @Column(name = "RESOLVED_DT")
    private LocalDateTime resolvedDt;

    private LsDeidentReport(Long rawSn, Long reporterNo, String reason) {
        this.rawSn = rawSn;
        this.reporterNo = reporterNo;
        this.reason = reason;
        this.sttsCd = STATUS_OPEN;
        this.rprtDt = LocalDateTime.now();
    }

    public static LsDeidentReport create(Long rawSn, Long reporterNo, String reason) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (reporterNo == null) {
            throw new IllegalArgumentException("reporterNo 는 필수입니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 은 필수입니다.");
        }
        return new LsDeidentReport(rawSn, reporterNo, reason);
    }

    /** 재비식별 성공 시 본 신고를 RESOLVED 로 전이. */
    public void resolve() {
        this.sttsCd = STATUS_RESOLVED;
        this.resolvedDt = LocalDateTime.now();
    }

    /** 검수자가 반려 처리할 때. */
    public void dismiss() {
        this.sttsCd = STATUS_DISMISSED;
        this.resolvedDt = LocalDateTime.now();
    }
}
