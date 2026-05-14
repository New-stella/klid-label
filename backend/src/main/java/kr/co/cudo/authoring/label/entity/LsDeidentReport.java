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
 * LS_DEIDENT_REPORT: 영상 단위 비식별 요청/결과 이력 + 사용자 신고 통합 테이블.
 *
 * <p>한 row 는 두 가지 흐름 중 하나를 담는다 — 시스템 트랜잭션이거나 사용자 신고이거나.
 * <ul>
 *   <li>시스템 비식별 트랜잭션: PROC_STTS_CD(REQUESTED/SUCCEEDED/FAILED) + ORGN_FILE_PATH/DE_IDNTF_FILE_PATH 사용.</li>
 *   <li>사용자 신고: REPORT_STTS_CD(OPEN/RESOLVED/DISMISSED) + REPORTER_NO/REASON/REPORT_DT/RESOLVED_DT 사용.</li>
 * </ul>
 * 비식별 결과 영상 경로는 LS_DATA_RAW 에 붙이지 않고 본 테이블의 DE_IDNTF_FILE_PATH 에만 저장한다.
 */
@Entity
@Table(name = "LS_DEIDENT_REPORT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDeidentReport {

    // 시스템 비식별 트랜잭션 상태 코드 (PROC_STTS_CD).
    public static final String PROC_REQUESTED = "REQUESTED";
    public static final String PROC_SUCCESS = "SUCCEEDED";
    public static final String PROC_FAILED = "FAILED";

    // 사용자 신고 상태 코드 (REPORT_STTS_CD).
    public static final String REPORT_OPEN = "OPEN";
    public static final String REPORT_RESOLVED = "RESOLVED";
    public static final String REPORT_DISMISSED = "DISMISSED";

    // 기존 호출자 호환 alias.
    public static final String STATUS_REQUESTED = PROC_REQUESTED;
    public static final String STATUS_SUCCEEDED = PROC_SUCCESS;
    public static final String STATUS_FAILED = PROC_FAILED;
    public static final String STATUS_OPEN = REPORT_OPEN;
    public static final String STATUS_RESOLVED = REPORT_RESOLVED;
    public static final String STATUS_DISMISSED = REPORT_DISMISSED;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DEIDENT_REPORT_SN")
    private Long deidentReportSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    // ----- 시스템 비식별 트랜잭션 흐름 -----
    @Column(name = "REQ_ID", length = 64)
    private String reqId;

    @Column(name = "ORGN_FILE_PATH", length = 1000)
    private String orgnFilePath;

    @Column(name = "DE_IDNTF_FILE_PATH", length = 1000)
    private String deIdntfFilePath;

    @Column(name = "PROC_STTS_CD", length = 20)
    private String procSttsCd;

    @Column(name = "REQ_DT")
    private LocalDateTime reqDt;

    @Column(name = "RES_DT")
    private LocalDateTime resDt;

    @Column(name = "ERROR_CD", length = 50)
    private String errorCd;

    @Column(name = "ERROR_MSG", length = 1000)
    private String errorMsg;

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
    // 시스템 비식별 트랜잭션 (DeidentifyStep 호출 흐름)
    // =========================================================

    /**
     * 시스템 비식별 트랜잭션 row 생성 (PROC_STTS_CD='REQUESTED').
     * - rawSn: 영상 PK
     * - originalFilePath: 원본 영상 경로 (ORGN_FILE_PATH)
     */
    public static LsDeidentReport createSystemTrx(Long rawSn, String originalFilePath) {
        return request(rawSn, null, originalFilePath, "batch");
    }

    /**
     * 시스템 비식별 트랜잭션 row 생성 — 호출자가 reqId 와 regId 를 명시.
     */
    public static LsDeidentReport request(Long rawSn, String reqId, String originalFilePath, String regId) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (originalFilePath == null || originalFilePath.isBlank()) {
            throw new IllegalArgumentException("originalFilePath 는 필수입니다.");
        }
        LsDeidentReport r = new LsDeidentReport();
        r.dataRawSn = rawSn;
        r.reqId = reqId;
        r.orgnFilePath = originalFilePath;
        r.procSttsCd = PROC_REQUESTED;
        r.reqDt = LocalDateTime.now();
        r.regId = regId;
        r.regDt = r.reqDt;
        return r;
    }

    /** 시스템 트랜잭션 성공 — 결과 경로 + 종료시각 기록. */
    public void markSuccess(String resultPath, LocalDateTime resDt) {
        this.procSttsCd = PROC_SUCCESS;
        this.deIdntfFilePath = resultPath;
        this.resDt = resDt == null ? LocalDateTime.now() : resDt;
        this.mdfcnDt = this.resDt;
    }

    /** 시스템 트랜잭션 실패 — 오류 코드/메시지 기록. */
    public void markFailure(String errorMsg, String errorCd) {
        this.procSttsCd = PROC_FAILED;
        this.errorCd = errorCd;
        this.errorMsg = errorMsg;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    /** 기존 호출자 호환 — DeidentifyStep 등에서 사용. */
    public void succeed(String resultPath) {
        markSuccess(resultPath, LocalDateTime.now());
    }

    /** 기존 호출자 호환. */
    public void fail(String errorCd, String errorMsg) {
        markFailure(errorMsg, errorCd);
    }

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

    /**
     * 통합 상태 코드.
     * 사용자 신고 row 면 REPORT_STTS_CD, 시스템 트랜잭션 row 면 PROC_STTS_CD 를 우선 반환.
     */
    public String getSttsCd() {
        if (reportSttsCd != null) {
            return reportSttsCd;
        }
        return procSttsCd;
    }
}
