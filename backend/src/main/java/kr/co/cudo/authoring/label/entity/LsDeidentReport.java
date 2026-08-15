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
 * 본 테이블은 REPORTER_NO/RSN/REPORT_STTS_CD/DCLR_DT/RESOLVED_DT 만 보유한다.
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

    /**
     * 신고 단계 코드 (DCLR_STP_CD, V171) — <b>마킹 화면</b>에서 접수된 신고.
     *
     * <p>해소 후 재개 지점: <b>마킹부터 다시</b>(비식별 재수행 결과 위에서). 이 단계의 신고는
     * {@code DATA_STTS_CD='MARKING_READY'} 에서만 접수되므로 프레임({@code LS_DATA_SRC})·라벨이
     * 아직 존재하지 않는다 — 재마킹이 파괴할 작업 결과가 없다.
     */
    public static final String STAGE_MARKING = "MARKING";

    /**
     * 신고 단계 코드 (DCLR_STP_CD, V171) — <b>라벨링 화면</b>에서 접수된 신고.
     *
     * <p>해소 후 재개 지점: <b>프레임 이미지만 재추출</b>하고 라벨링을 이어간다(마킹 유지 ·
     * 라벨 좌표 보존). 기존 {@code LS_DATA_SRC} 행을 dirty-update 하므로 {@code SRC_SN} 이
     * 보존되어 라벨 FK 가 끊기지 않는다.
     */
    public static final String STAGE_LABELING = "LABELING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DEIDENT_REPORT_SN")
    private Long deidentReportSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    // ----- 사용자 신고 흐름 -----
    @Column(name = "REPORTER_NO")
    private Long reporterNo;

    /**
     * 신고 상세사유 — 반려사유(1000)급 상세 필요로 VARCHAR(1000) 유지.
     * 단순 사유(500, 예: LS_TASK_EVNT_LOG.RSN)와 도메인 구분.
     */
    @Column(name = "RSN", length = 1000)
    private String rsn;

    @Column(name = "REPORT_STTS_CD", length = 16)
    private String reportSttsCd;

    @Column(name = "DCLR_DT")
    private LocalDateTime reportDt;

    /**
     * 신고 단계 (DCLR_STP_CD, V171) — {@link #STAGE_MARKING} | {@link #STAGE_LABELING}.
     *
     * <p><b>nullable = 단계 미상</b>(컬럼 신설 이전 레거시 행). 백필하지 않는다 — 어디서 신고했는지
     * 지어내지 않는다. NULL 행은 해소 시 단계별 재개 이벤트를 발행하지 않으며, 기존 2종
     * ({@code DeidentGateReopenedEvent} / {@code DeidentReportResolvedEvent})만 발행되어 현행 동작이
     * 그대로 유지된다.
     */
    @Column(name = "DCLR_STP_CD", length = 20)
    private String dclrStpCd;

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
     * 사용자 신고 row 생성 (REPORT_STTS_CD='OPEN') — <b>단계 미상</b>(DCLR_STP_CD=NULL).
     *
     * <p>단계를 아는 호출자는 {@link #createReport(Long, Long, String, String)} 을 쓴다. 이 3-arg 는
     * 단계 개념이 없던 시절의 호출자·테스트 호환용이며, 만들어진 행은 해소 시 단계별 재개 이벤트를
     * 발행하지 않는다(레거시 NULL 행과 동일 취급).
     */
    public static LsDeidentReport createReport(Long rawSn, Long reporterNo, String reason) {
        return createReport(rawSn, reporterNo, reason, null);
    }

    /**
     * 사용자 신고 row 생성 (REPORT_STTS_CD='OPEN') — <b>신고 단계 포함</b> (V171).
     *
     * @param stage {@link #STAGE_MARKING} | {@link #STAGE_LABELING} | {@code null}(단계 미상)
     */
    public static LsDeidentReport createReport(Long rawSn, Long reporterNo, String reason, String stage) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (stage != null && !STAGE_MARKING.equals(stage) && !STAGE_LABELING.equals(stage)) {
            throw new IllegalArgumentException("DCLR_STP_CD 는 MARKING/LABELING 중 하나여야 합니다: " + stage);
        }
        LsDeidentReport r = new LsDeidentReport();
        r.dataRawSn = rawSn;
        r.reporterNo = reporterNo;
        r.rsn = reason;
        r.reportSttsCd = REPORT_OPEN;
        r.dclrStpCd = stage;
        r.reportDt = LocalDateTime.now();
        r.regId = reporterNo == null ? null : String.valueOf(reporterNo);
        r.regDt = r.reportDt;
        return r;
    }

    /** 기존 호출자 호환 — DeidentReportService 의 신구 호출 모두 통과. */
    public static LsDeidentReport create(Long rawSn, Long reporterNo, String reason) {
        return createReport(rawSn, reporterNo, reason, null);
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
