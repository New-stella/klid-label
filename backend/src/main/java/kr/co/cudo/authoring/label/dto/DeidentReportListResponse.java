package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.label.entity.LsDeidentReport;

import java.time.LocalDateTime;

/**
 * 비식별 신고 목록 행 응답 (G-1) — REVIEWER 신고 관리 화면용.
 *
 * <p>신고 사유(rsn)는 REVIEWER 가 해소 판단에 필요하므로 노출한다. 단, 신고 본문은 로그에 출력하지 않는다
 * (Privacy — DeidentReportService 의 로깅 정책 유지).
 *
 * @param rprtSn     신고 PK (DEIDENT_REPORT_SN)
 * @param rawSn      신고 대상 영상 PK
 * @param reporterNo 신고자 사용자 번호
 * @param reason     신고 사유
 * @param status     신고 상태 (OPEN / RESOLVED / DISMISSED)
 * @param reportDt   신고 일시
 * @param resolvedDt 해소 일시 (OPEN 이면 null)
 */
public record DeidentReportListResponse(
        Long rprtSn,
        Long rawSn,
        Long reporterNo,
        String reason,
        String status,
        LocalDateTime reportDt,
        LocalDateTime resolvedDt
) {
    public static DeidentReportListResponse from(LsDeidentReport r) {
        return new DeidentReportListResponse(
                r.getRprtSn(),
                r.getRawSn(),
                r.getReporterNo(),
                r.getRsn(),
                r.getReportSttsCd(),
                r.getReportDt(),
                r.getResolvedDt());
    }
}
