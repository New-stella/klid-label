package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.service.MarkingOutcome;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 포털 업로드 영상 마킹 저장 응답. [design: API-240]
 *
 * <p>이 응답이 확정적으로 말하는 것은 <b>마킹을 저장했고 그 지점으로 추출이 시작되도록 자산 상태를
 * 넘겼다</b>는 사실이며, 프레임이 준비됐다는 뜻이 아니다. 추출이 끝났는지는 자산 상세 조회에서
 * 확인한다.
 *
 * <p>★ {@code truncated} 와 {@code requestedMarkCount} 를 함께 내리는 이유는 <b>절단이 조용히
 * 일어나지 않게</b> 하기 위해서다 — 저장된 지점 수만 돌려주면 소비자는 그 수가 자기가 보낸 수인지
 * 잘린 수인지 구분할 수 없다.
 *
 * @param markCount          실제로 저장된 지점 수 = 뽑힐 프레임 장수
 * @param requestedMarkCount 잘리기 전에 요청·산출된 지점 수
 * @param truncated          추출 장수 상한에 걸려 잘렸는가
 * @param uldSttsCd          저장으로 넘어간 자산 상태 — 추출이 <b>시작</b>됐음을 뜻한다
 */
public record PortalMarkingSaveResponse(
        Long markingSn,
        Long uldSn,
        String mode,
        Integer interval,
        List<MarkItem> marks,
        int markCount,
        int requestedMarkCount,
        boolean truncated,
        String uldSttsCd,
        LocalDateTime regDt
) {

    public static PortalMarkingSaveResponse of(Long uldSn, MarkingOutcome outcome) {
        return new PortalMarkingSaveResponse(
                outcome.response().markingSn(),
                uldSn,
                outcome.response().markingMode(),
                outcome.response().intervalFrames(),
                outcome.marks(),
                outcome.marks().size(),
                outcome.requestedMarkCount(),
                outcome.truncated(),
                PortalUploadLedger.STATUS_PROCESSING,
                outcome.response().createdAt());
    }
}
