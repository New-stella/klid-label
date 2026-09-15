package kr.co.cudo.authoring.review.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 일괄 승인 요청 — 승인할 영상 식별자 목록.
 *
 * <p>중복된 식별자는 <b>한 건으로</b> 취급한다. 목록이 비었거나 건수 상한을 넘으면 요청 자체가
 * 거부되고 한 건도 처리되지 않는다 — 그 상한은 배포 설정값이라 여기에 숫자를 박지 않는다
 * ({@code ReviewBatchApprovePolicy}).
 *
 * <p>확인 항목(라벨 0건 확인 등)을 두지 않는다 — 그 판단은 영상 하나를 보고 내리는 것이라 단건 승인
 * 창구에서만 할 수 있다. 라벨이 없는 영상은 여기서 그 건의 실패로 담긴다.
 *
 * @design API-250
 */
public record BatchApproveRequest(
        @NotEmpty(message = "승인할 영상을 하나 이상 선택해야 합니다")
        List<Long> videoIds
) {
}
