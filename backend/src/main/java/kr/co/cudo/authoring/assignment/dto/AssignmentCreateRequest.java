package kr.co.cudo.authoring.assignment.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 작업 배정 생성 요청.
 *
 * <p><b>배정의 대상은 작업자뿐이다</b> — 검수자를 영상에 배정하는 절차를 두지 않는다. 검수는 배정 없이
 * 전체 대기열에서 집어가며 배정을 인가 축으로 쓰지 않는다(ADR-067). 그래서 구 {@code reviewerId} 항목을
 * 없앴다.
 *
 * <p>★ <b>옛 호출자가 그 항목을 그대로 보내도 거부하지 않고 무시한다</b>(400 이 아니다). 옵션 항목이었고
 * 소비처가 표시뿐이었으므로 400 으로 돌려주면 기존 호출만 깨뜨린다. 알 수 없는 항목을 무시하는 것은
 * 이 애플리케이션의 기본 역직렬화 동작이며, 회귀 가드가 그 사실을 고정한다.
 *
 * @design API-070
 * @design ADR-067
 */
public record AssignmentCreateRequest(
        @NotNull(message = "workerId 는 필수입니다.")
        @Positive
        Long workerId,

        @NotEmpty(message = "rawDataIds 는 1건 이상이어야 합니다.")
        List<@NotNull @Positive Long> rawDataIds
) {
}
