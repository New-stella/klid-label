package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.transfer.entity.LsOtsdDatstTrnsfHstry;

import java.time.LocalDateTime;

/**
 * 이관 이력 목록의 한 항목(API-207).
 *
 * <h3>★ 승인 보류는 이관 상태와 다른 축이다</h3>
 * <p>{@link #status} 는 <b>가져오는 일이 어떻게 끝났는가</b>(진행중·성공·실패)이고,
 * {@link #approvalHeld} 는 <b>그렇게 만들어진 영상이 지금 검수 승인을 받을 수 있는가</b>이다.
 * 성공한 이관 가운데 <b>일부에만</b> 보류가 서므로 이관 상태로 대신 판단할 수 없다. 두 값을 같은
 * 것으로 다루면, 보류가 선 영상을 골라 비식별 완료를 기록하는 자리가 화면에서 도달 불가가 된다.
 *
 * <p>보류가 <b>서는 조건</b>은 이 응답이 정하지 않는다 — 그 판정은 검수 워크플로 상태가 소유하며
 * 여기서는 그 값을 읽어 실을 뿐이다. 조건을 이 통로에 다시 적으면 판정이 두 벌이 된다.
 *
 * @param rawSn        만들어진 영상. 아직 만들어지지 않았거나 실패했으면 {@code null}
 * @param approvalHeld 그 영상에 검수 승인 보류가 서 있는지. 영상이 없으면 {@code null}
 * @design API-207
 * @design DFEAT-059
 */
@Schema(description = "이관 이력 1건")
public record ImportHistoryItemResponse(
        long trnsfSn,
        String folderName,
        Long rawSn,
        String status,
        Integer frameCount,
        Integer labelCount,
        Boolean approvalHeld,
        String regId,
        LocalDateTime regDt) {

    /**
     * 엔티티 → 응답 변환.
     *
     * @param approvalHeld 호출부가 <b>한 번에 모아 조회한</b> 판정값. 항목마다 상태를 다시 읽으면
     *                     목록 크기만큼 조회가 늘어난다(N+1)
     */
    public static ImportHistoryItemResponse from(LsOtsdDatstTrnsfHstry history, Boolean approvalHeld) {
        return new ImportHistoryItemResponse(
                history.getTrnsfSn(),
                history.getOrgnlFldrNm(),
                history.getRawSn(),
                history.getTrnsfSttsCd(),
                history.getFrmeCnt(),
                history.getLblCnt(),
                approvalHeld,
                history.getRegId(),
                history.getRegDt());
    }
}
