package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.transfer.entity.LsOtsdDatstTrnsfHstry;

import java.time.LocalDateTime;

/**
 * 이관 이력 한 건의 상세(API-208).
 *
 * <h3>실패한 이관도 상세를 가진다</h3>
 * <p>이 계약의 존재 이유는 <b>왜 안 들어왔는지를 되짚는 것</b>이다. 그래서 목록에 없는
 * {@link #failReason}·{@link #folderPath}·{@link #externalDatasetId} 가 여기에 있다.
 *
 * <h3>승인 보류 여부는 여기에 없다 — 의도된 비대칭이다</h3>
 * <p>목록(API-207)만 그 값을 싣는다. 화면이 보류가 선 영상을 <b>목록에서</b> 골라 비식별 완료 기록으로
 * 들어가기 때문이다. 상세에도 실을지는 아직 확정되지 않았으므로 계약에 없는 값을 여기서 지어내지
 * 않는다 — 확정은 설계의 몫이다.
 *
 * @param rawSn      만들어진 영상. 실패했거나 영상이 지워졌으면 {@code null}
 * @param failReason 실패한 경우의 사유. 성공·진행중이면 {@code null}
 * @design API-208
 * @design DFEAT-059
 */
@Schema(description = "이관 이력 상세")
public record ImportHistoryDetailResponse(
        long trnsfSn,
        String folderPath,
        String folderName,
        String externalDatasetId,
        Long rawSn,
        String status,
        Integer frameCount,
        Integer labelCount,
        String failReason,
        String regId,
        LocalDateTime regDt) {

    public static ImportHistoryDetailResponse from(LsOtsdDatstTrnsfHstry history) {
        return new ImportHistoryDetailResponse(
                history.getTrnsfSn(),
                history.getOrgnlFldrPathNm(),
                history.getOrgnlFldrNm(),
                history.getOtsdDatstId(),
                history.getRawSn(),
                history.getTrnsfSttsCd(),
                history.getFrmeCnt(),
                history.getLblCnt(),
                history.getFailRsn(),
                history.getRegId(),
                history.getRegDt());
    }
}
