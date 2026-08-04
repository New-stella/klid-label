package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.label.entity.LsDeidentReport;

import java.time.LocalDateTime;

/**
 * 비식별 신고 목록 행 응답 (G-1) — REVIEWER 신고 관리 화면용.
 *
 * <p>신고 사유(rsn)는 REVIEWER 가 해소 판단에 필요하므로 노출한다. 단, 신고 본문은 로그에 출력하지 않는다
 * (Privacy — DeidentReportService 의 로깅 정책 유지).
 *
 * <p><b>신고자 표시 축이 둘인 이유</b> — {@code reporterNo} 는 {@code USER_NO} 원값이고
 * {@code reporterName} 은 그 번호로 조회한 {@code MNG_ACCT_USER.USER_NM} 이다. 화면은
 * <b>{@code reporterName} 을 표시</b>하고 없을 때만 폴백을 쓴다 — 내부 번호를 사람 이름 자리에 그대로
 * 찍지 않기 위해서다. {@code reporterNo} 는 기존 소비자 하위호환을 위해 유지한다(필드 추가만).
 *
 * <p>{@code reporterName} 이 {@code null} 인 경우: 신고자 번호가 없거나(레거시 행) 사용자 마스터에
 * 없을 때(탈퇴·관제 계정 삭제). 이름을 못 찾아도 신고 목록 자체는 조회돼야 하므로 예외로 올리지 않는다.
 * 사용자명은 PII 가 아닌 표시명이며 조회 권한은 컨트롤러의 REVIEWER 인가로 이미 제한된다.
 *
 * @param rprtSn       신고 PK (DEIDENT_REPORT_SN)
 * @param rawSn        신고 대상 영상 PK
 * @param reporterNo   신고자 사용자 번호
 * @param reporterName 신고자 표시명 ({@code MNG_ACCT_USER.USER_NM}) — 조회 실패 시 null
 * @param reason       신고 사유
 * @param status       신고 상태 (OPEN / RESOLVED / DISMISSED)
 * @param reportDt     신고 일시
 * @param resolvedDt   해소 일시 (OPEN 이면 null)
 */
public record DeidentReportListResponse(
        Long rprtSn,
        Long rawSn,
        Long reporterNo,
        String reporterName,
        String reason,
        String status,
        LocalDateTime reportDt,
        LocalDateTime resolvedDt
) {
    public static DeidentReportListResponse from(LsDeidentReport r, String reporterName) {
        return new DeidentReportListResponse(
                r.getRprtSn(),
                r.getRawSn(),
                r.getReporterNo(),
                reporterName,
                r.getRsn(),
                r.getReportSttsCd(),
                r.getReportDt(),
                r.getResolvedDt());
    }
}
