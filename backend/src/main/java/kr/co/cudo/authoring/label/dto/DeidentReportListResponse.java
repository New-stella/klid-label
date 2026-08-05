package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.label.entity.LsDeidentReport;

import java.time.LocalDateTime;

/**
 * 비식별 신고 목록 행 응답 (G-1) — REVIEWER 신고 관리 화면용.
 *
 * <p>신고 사유(rsn)는 REVIEWER 가 해소 판단에 필요하므로 노출한다. 단, 신고 본문은 로그에 출력하지 않는다
 * (Privacy — DeidentReportService 의 로깅 정책 유지).
 *
 * <h3>하위호환 (구속)</h3>
 * <p>필드는 <b>추가만</b> 한다 — 기존 필드의 이름·타입·유무를 바꾸지 않는다(프로젝트 API 하위호환 규칙:
 * 응답 새 필드 추가는 하위호환, 필드 삭제·타입 변경은 breaking). 회귀 가드는
 * {@code DeidentReportControllerTest.신고목록_기존_응답필드는_이름·타입·유무가_그대로다}.
 *
 * @param rprtSn     신고 PK (DEIDENT_REPORT_SN)
 * @param rawSn      신고 대상 영상 PK
 * @param reporterNo 신고자 사용자 번호
 * @param reason     신고 사유
 * @param status     신고 상태 (OPEN / RESOLVED / DISMISSED)
 * @param reportDt   신고 일시
 * @param resolvedDt 해소 일시 (OPEN 이면 null)
 * @param stage      신고 단계 (V171 신설 · optional) — {@link LsDeidentReport#STAGE_MARKING} |
 *                   {@link LsDeidentReport#STAGE_LABELING} | {@code null}(단계 미상)
 */
public record DeidentReportListResponse(
        Long rprtSn,
        Long rawSn,
        Long reporterNo,
        String reason,
        String status,
        LocalDateTime reportDt,
        LocalDateTime resolvedDt,
        String stage
) {
    /**
     * 엔티티 → 목록 행 매핑.
     *
     * <p><b>단계({@code stage})는 엔티티에서 직접 읽는다</b> — 조회가
     * {@code LsDeidentReportRepository.findByReportSttsCd} 로 <b>엔티티 전체</b>를 가져오므로 SELECT 절
     * 누락으로 항상 null 이 되는 함정이 없다. 이 매핑을 DTO projection(생성자 표현식·인터페이스 projection)
     * 으로 바꾸는 경우에는 <b>SELECT 절에 {@code dclrStpCd} 를 반드시 포함</b>해야 한다.
     */
    public static DeidentReportListResponse from(LsDeidentReport r) {
        return new DeidentReportListResponse(
                r.getRprtSn(),
                r.getRawSn(),
                r.getReporterNo(),
                r.getRsn(),
                r.getReportSttsCd(),
                r.getReportDt(),
                r.getResolvedDt(),
                r.getDclrStpCd());
    }
}
