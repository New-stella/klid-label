package kr.co.cudo.authoring.marking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 마킹 프레임 단위 아이템.
 *
 * <h3>C-ISSUE-01 — 무검증 표면 제거 (CWE-20)</h3>
 * 과거에는 {@code frameIndex} 에 {@code @NotNull} 만 있었고 (게다가 {@code MarkingRequest.marks} 에
 * {@code @Valid} 가 없어 그 {@code @NotNull} 조차 발화하지 않았다) 하한·형식 검증이 전무했다. 실측:
 * {@code [{0,"00:00"},{0,"00:00"},{999999999,"99:99"},{-5,"-1:00"}]} 가 201 로 저장돼 중복·영상 길이
 * 초과·음수·형식 파괴 값이 {@code LS_MARKING.MARK_CN} 에 그대로 영속됐다.
 * 이제 하한({@code @Min(0)})과 타임스탬프 형식을 DTO 에서 400 으로 거부하고, 요청 내 <b>중복 시점</b>과
 * <b>영상 길이 기반 상한</b>은 길이 정보가 필요하므로 {@code MarkingService} 가 검증한다.
 *
 * @param frameIndex 프레임 인덱스 (0-base, 음수 불가)
 * @param timestamp  타임스탬프 문자열 "mm:ss" 또는 "mm:ss:ff" — nullable(미지정 허용).
 *                   분은 1~4자리(장시간 영상), 초는 00~59, 프레임은 1~3자리.
 */
public record MarkItem(
        @NotNull(message = "frameIndex 는 필수입니다.")
        @Min(value = 0, message = "frameIndex 는 0 이상이어야 합니다.")
        Integer frameIndex,

        @Pattern(regexp = "^\\d{1,4}:[0-5]\\d(:\\d{1,3})?$",
                message = "timestamp 는 mm:ss 또는 mm:ss:ff 형식이어야 합니다.")
        String timestamp
) {}
