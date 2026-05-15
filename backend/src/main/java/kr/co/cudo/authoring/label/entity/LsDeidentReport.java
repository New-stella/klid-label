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
 * LS_DEIDENT_REPORT: 사용자(라벨러)의 비식별 누락 신고 전용 테이블.
 *
 * <p>DB 설계서 §8 기준 — 시스템 비식별 처리 이력은 {@code LS_DEIDENT_PROC_LOG} 로 분리됨.
 * 본 테이블은 REPORTER_NO/REASON/REPORT_STTS_CD/REPORT_DT/RESOLVED_DT 만 보유한다.
 */
@Entity
@Table(name = "LS_DEIDENT_REPORT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDeidentReport {

    // 사용자 신고 상태 코드 (REPORT_STTS_CD).
    public static final String REPORT_OPEN = "OPEN";
    public static final String REPORT_RESOLVED = "RESOLVED";
    public static final String REPORT_DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DEIDENT_REPORT_SN")
    private Long deidentReportSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    // ----- 사용자 신고 흐름 -----
    @Column(name = "REPORTER_NO")
    private Long reporterNo;

    @Column(name = "REASON", length = 1000)
    private String reason;

    @Column(name = "REPORT_STTS_CD", length = 16)
    private String reportSttsCd;

    @Column(name = "REPORT_DT")
    private LocalDateTime reportDt;

    @Column(name = "RESOLVED_DT")
    private LocalDateTime resolvedDt;

    // ----- audit -----
    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    // =========================================================
    // 사용자 신고 (DeidentReportService.report 흐름)
    // =========================================================

    /**
     * 사용자 신고 row 생성 (REPORT_STTS_CD='OPEN').
     */
    public static LsDeidentReport createReport(Long rawSn, Long reporterNo, String reason) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        LsDeidentReport r = new LsDeidentReport();
        r.dataRawSn = rawSn;
        r.reporterNo = reporterNo;
        r.reason = reason;
        r.reportSttsCd = REPORT_OPEN;
        r.reportDt = LocalDateTime.now();
        r.regId = reporterNo == null ? null : String.valueOf(reporterNo);
        r.regDt = r.reportDt;
        return r;
    }

    /** 기존 호출자 호환 — DeidentReportService 의 신구 호출 모두 통과. */
    public static LsDeidentReport create(Long rawSn, Long reporterNo, String reason) {
        return createReport(rawSn, reporterNo, reason);
    }

    /** 사용자 신고 해소 — REPORT_STTS_CD='RESOLVED' + RESOLVED_DT 기록. */
    public void resolve() {
        this.reportSttsCd = REPORT_RESOLVED;
        this.resolvedDt = LocalDateTime.now();
        this.mdfcnDt = this.resolvedDt;
    }

    /** 신고 기각. */
    public void dismiss() {
        this.reportSttsCd = REPORT_DISMISSED;
        this.resolvedDt = LocalDateTime.now();
        this.mdfcnDt = this.resolvedDt;
    }

    // =========================================================
    // 호환 getter (기존 테스트/호출자)
    // =========================================================

    public Long getRprtSn() {
        return deidentReportSn;
    }

    public Long getRawSn() {
        return dataRawSn;
    }
}
