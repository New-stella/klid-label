package kr.co.cudo.authoring.label.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 확장 예산 — 좌표·프레임 <b>입구 상한</b> (CWE-770).
 *
 * <h3>무엇을 막나</h3>
 * 영상 단위 확정 저장(API-196)이 <b>프레임 × 라벨 × 좌표</b> 곱셈 축을 만들었다. 서비스의
 * {@code MAX_POINTS_PER_LABEL}(1000)은 <b>신규 라벨만</b> 강제하고 기존 라벨은 simplify 로 통과시키므로
 * 좌표 개수에 사실상 상한이 없었고, {@code @Valid} 는 역직렬화 <b>후</b>에 도므로 앞단 방벽이 리버스
 * 프록시 본문 크기 제한뿐이면 수 GB 힙까지 열린다.
 *
 * <h3>왜 상한을 1000 으로 조이지 않았나 (되돌리기 방지)</h3>
 * 기존 라벨은 1000 초과여도 simplify 로 저장되는 계약이라(레거시 SAM2 폴리곤), 1000 으로 맞추면
 * <b>이미 저장된 라벨을 그대로 재전송하는 정상 저장이 400</b> 이 된다. 두 값은 축이 다르다:
 * 이쪽은 "역직렬화를 허용할 최대 크기", 그쪽은 "신규 라벨로 저장을 허용할 최대 크기".
 *
 * @req R6
 */
class LabelRequestExpansionBudgetTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private LabelItemDto itemWithPoints(int count) {
        List<List<Double>> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(List.of((double) i, (double) i));
        }
        return new LabelItemDto(null, "POLYGON", 12L, "사람", points, null);
    }

    @Test
    @DisplayName("좌표_개수가_입구_상한_이하면_통과한다 — simplify_계약을_깨지_않는다")
    void 좌표_개수가_상한_이하면_통과한다() {
        // 기존 라벨의 1000 초과 재전송도 통과해야 한다(서비스가 simplify 한다).
        assertThat(VALIDATOR.validate(itemWithPoints(2))).isEmpty();
        assertThat(VALIDATOR.validate(itemWithPoints(1_500))).isEmpty();
        assertThat(VALIDATOR.validate(itemWithPoints(LabelItemDto.MAX_POINTS_PER_REQUEST))).isEmpty();
    }

    @Test
    @DisplayName("좌표_개수가_입구_상한을_넘으면_400_대상이다")
    void 좌표_개수가_상한을_넘으면_거부된다() {
        assertThat(VALIDATOR.validate(itemWithPoints(LabelItemDto.MAX_POINTS_PER_REQUEST + 1)))
                .as("입구 상한이 사라지면 프레임×라벨×좌표 곱셈이 무제한이 된다(CWE-770)")
                .isNotEmpty();
    }

    @Test
    @DisplayName("좌표_튜플이_x_y_v_보다_길면_400_대상이다 — 안쪽_축도_막는다")
    void 좌표_튜플_길이도_막는다() {
        List<Double> tooLong = List.of(1.0, 2.0, 3.0, 4.0);
        LabelItemDto item = new LabelItemDto(null, "BBOX", 12L, "사람", List.of(tooLong), null);

        assertThat(VALIDATOR.validate(item))
                .as("이 축이 없으면 좌표쌍 하나가 무한히 길어질 수 있다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("SKELETON_삼중값은_통과한다 — 최대_형태를_막지_않는다")
    void 삼중값은_통과한다() {
        LabelItemDto item = new LabelItemDto(null, "SKELETON", 12L, "사람",
                List.of(List.of(1.0, 2.0, 2.0)), null);

        assertThat(VALIDATOR.validate(item)).isEmpty();
    }

    @Test
    @DisplayName("영상_단위_요청의_프레임_하드캡은_운영_상한_기본값과_같다 — 상한이_두_곳으로_갈리지_않는다")
    void 프레임_하드캡은_운영_상한_기본값과_같다() {
        // 두 값이 벌어지면 설정을 조여도 하드 캡까지는 힙에 올라오는 구멍이 남는다.
        assertThat(VideoLabelSaveRequest.MAX_FRAMES_HARD_CAP)
                .isEqualTo(new kr.co.cudo.authoring.version.config.StartVersionProperties(2000)
                        .maxFrames());
    }
}
