package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.marking.dto.MarkItem;

import java.util.List;

/**
 * 포털 업로드 영상 마킹 저장 요청. [design: API-240]
 *
 * <h3>간격의 단위는 <b>프레임 수</b>다</h3>
 * <p>수동으로 찍는 지점이 이미 프레임 단위이므로 자동만 시간 단위로 두면 한 화면 안에서 단위가
 * 갈린다. 재사용하는 마킹 원장의 간격 칸도 프레임 수다. 시간으로 받으면 저장할 때 초당 프레임 수로
 * 나눠야 하는데 그 값이 영상마다 달라 <b>같은 입력이 영상마다 다른 저장값</b>이 된다.
 *
 * <p>기본값 {@value #DEFAULT_INTERVAL_FRAMES} 프레임은 관제 채널 자동 마킹의 기본 간격과 같은
 * 값이라 두 채널이 같은 눈금에서 시작한다. 사용자가 바꾼 값은 <b>그 영상의 마킹에만</b> 쓰이고 운영
 * 기본값을 바꾸지 않는다.
 *
 * @param mode     마킹 방식 — {@code AUTO} 또는 {@code MANUAL}
 * @param interval 자동 방식의 간격(프레임 수). 자동이고 비어 있으면 {@value #DEFAULT_INTERVAL_FRAMES}
 * @param marks    수동 방식의 지점 목록. 수동이면 하나 이상이어야 한다
 */
public record PortalMarkingRequest(

        @NotBlank(message = "mode 는 필수입니다.")
        String mode,

        @Min(value = 1, message = "interval 은 1 이상이어야 합니다.")
        Integer interval,

        /*
         * 원소 수 상한은 <b>과대 요청 방어</b>(CWE-770)라 넘으면 거부한다 — 실제로 뽑히는 프레임
         * 장수의 상한과는 다른 축이며, 그쪽은 넘으면 자른다.
         */
        @Valid
        @Size(max = 20000, message = "한 번에 처리 가능한 마킹 수 초과 (최대 20000)")
        List<MarkItem> marks
) {

    /** 자동 방식 식별자. */
    public static final String MODE_AUTO = "AUTO";

    /** 자동 간격 기본값(프레임 수) — 관제 채널 자동 마킹 기본값과 같다. */
    public static final int DEFAULT_INTERVAL_FRAMES = 300;

    /**
     * 실제로 쓸 간격 — 자동이면 미지정 시 기본값으로 채우고, 수동이면 쓰지 않으므로 비운다.
     */
    public Integer effectiveInterval() {
        if (!MODE_AUTO.equals(mode)) {
            return null;
        }
        return interval == null ? DEFAULT_INTERVAL_FRAMES : interval;
    }
}
