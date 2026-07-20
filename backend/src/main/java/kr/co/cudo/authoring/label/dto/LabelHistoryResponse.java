package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.version.entity.LsDataLblHstry;

import java.time.LocalDateTime;

/**
 * Phase 2 — 프레임 단위 라벨 변경 이력 응답 (GET /v1/frames/{srcSn}/label-history).
 *
 * <ul>
 *   <li>{@code lblHstrySn} : 이력 PK (2차 정렬 tiebreaker 노출).</li>
 *   <li>{@code lblSn}      : 대상 라벨 LS_DATA_LBL.LBL_SN.</li>
 *   <li>{@code changeKind} : 변경종류 (ADDED/UPDATED/DELETED).</li>
 *   <li>{@code actor}      : 작업자 식별자(REG_ID). nullable(삭제 이력 경로는 미기록).</li>
 *   <li>{@code regDt}      : 변경 일시.</li>
 *   <li>{@code label}      : 라벨명(생존 라벨만 채움 — 삭제 이력은 null). Optional.</li>
 * </ul>
 *
 * 보안(CWE-209): 내부 경로/스택트레이스/토큰 등 기술·민감정보를 포함하지 않는다.
 */
public record LabelHistoryResponse(
        Long lblHstrySn,
        Long lblSn,
        String changeKind,
        String actor,
        LocalDateTime regDt,
        String label
) {

    public static LabelHistoryResponse from(LsDataLblHstry h, String label) {
        return new LabelHistoryResponse(
                h.getLblHstrySn(),
                h.getLblSn(),
                h.getChgKindCd(),
                h.getRegId(),
                h.getRegDt(),
                label);
    }
}
