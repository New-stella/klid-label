package kr.co.cudo.authoring.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.service.MarkingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-ISSUE-01 — MANUAL 마킹 항목 검증 단위 테스트.
 *
 * <p>실측 결함: {@code [{0,"00:00"},{0,"00:00"},{999999999,"99:99"},{-5,"-1:00"}]} 가 201 로 저장돼
 * 중복·영상 길이 초과·음수·형식 파괴 값이 {@code LS_MARKING.MARK_CN} 에 그대로 영속됐다.
 * 하한·형식은 Bean Validation(@Min/@Pattern + MarkingRequest 의 @Valid 전파)으로, 중복·상한은
 * {@link MarkingService#validateManualMarks} 로 막는다.
 */
class MarkingManualValidationTest {

    private static final double FPS = 30.0;
    private static final long RAW_SN = 28L;

    private final MarkingService service = new MarkingService(
            null, null, null, new ObjectMapper(), null, null, null, null, null, null);

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("중복시점_영상길이초과_음수frameIndex_마킹은_400")
    void duplicateOutOfRangeAndNegativeMarksRejected() {
        // (1) 하한/형식 — Bean Validation 이 위반을 잡아야 한다(과거엔 @Valid 누락으로 전혀 발화하지 않았다).
        MarkingRequest bad = new MarkingRequest("MANUAL", null, Arrays.asList(
                new MarkItem(0, "00:00"),
                new MarkItem(0, "00:00"),
                new MarkItem(999_999_999, "99:99"),
                new MarkItem(-5, "-1:00")));
        assertThat(validator.validate(bad)).isNotEmpty();
        assertThat(validator.validate(bad))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("frameIndex"));
        assertThat(validator.validate(bad))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("timestamp"));

        // (2) 중복 시점 — 서비스가 400.
        assertThatThrownBy(() -> service.validateManualMarks(
                List.of(new MarkItem(0, "00:00"), new MarkItem(0, "00:00")), 10, FPS, RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // (3) 영상 길이 초과 — 10초 x 30fps = 300 프레임이므로 300 이상은 존재하지 않는 시점.
        assertThatThrownBy(() -> service.validateManualMarks(
                List.of(new MarkItem(999_999_999, "99:99")), 10, FPS, RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // (4) 음수 — 서비스 백스톱(직접 호출 경로)에서도 400.
        assertThatThrownBy(() -> service.validateManualMarks(
                List.of(new MarkItem(-5, "00:00")), 10, FPS, RAW_SN))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("영상길이를_알_수_없으면_상한만_스킵하고_하한과_중복은_그대로_400")
    void unknownDurationSkipsOnlyUpperBound() {
        // 상한 skip — 길이 미상(null)이면 큰 frameIndex 도 통과해야 한다(전부 스킵 금지의 반대편: 정상 작업 보존).
        assertThatCode(() -> service.validateManualMarks(
                List.of(new MarkItem(999_999, "10:00")), null, FPS, RAW_SN))
                .doesNotThrowAnyException();

        // 중복은 여전히 400.
        assertThatThrownBy(() -> service.validateManualMarks(
                List.of(new MarkItem(7, "00:00"), new MarkItem(7, "00:00")), null, FPS, RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 하한(음수)도 여전히 400.
        assertThatThrownBy(() -> service.validateManualMarks(
                List.of(new MarkItem(-1, "00:00")), null, FPS, RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("정상_수동마킹은_그대로_통과한다")
    void validManualMarksPass() {
        // 회귀 방어 — 게이트가 정상 마킹을 막지 않는다(0-base, 중복 없음, 길이 이내, 형식 정상).
        MarkingRequest ok = new MarkingRequest("MANUAL", null,
                List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05"), new MarkItem(299, "00:09")));
        assertThat(validator.validate(ok)).isEmpty();
        assertThatCode(() -> service.validateManualMarks(ok.marks(), 10, FPS, RAW_SN))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("25fps_영상_끝부분_정상마킹이_400_으로_거부되지_않는다")
    void realFpsEndOfVideoMarkAccepted() {
        // DEV_FIX(H10) — FE 가 30fps 를 하드코딩하던 동안 서버 상한은 실 fps 로 계산돼, 25fps·60초 영상의
        //   55초 마킹(FE 산출 1650 > 상한 1500)이 거부돼 뒤 16.7% 구간을 마킹할 수 없었다.
        //   근본 수정은 FE 가 서버 fps 를 쓰는 것이고(frontend markingFps), 서버는 같은 fps 기준으로
        //   상한을 계산하며 정수 초 저장 오차를 흡수할 1초 마진을 둔다.
        double fps = 25.0;
        int durationSec = 60;
        // FE 가 실 fps 로 산출한 끝부분 마킹 — 통과해야 한다.
        int endMark = (int) Math.round(59.9 * fps);
        assertThatCode(() -> service.validateManualMarks(
                List.of(new MarkItem(endMark, "00:59")), durationSec, fps, RAW_SN))
                .doesNotThrowAnyException();

        // 정수 초 절단 흡수 — 실제 60.4초 영상이 60 으로 저장돼도 60.4초 지점 마킹이 통과한다.
        int overStoredDuration = (int) Math.round(60.4 * fps);
        assertThatCode(() -> service.validateManualMarks(
                List.of(new MarkItem(overStoredDuration, "01:00")), durationSec, fps, RAW_SN))
                .doesNotThrowAnyException();

        // 마진이 "무제한 허용"으로 변질되지 않았는지 — 영상 길이를 크게 벗어난 값은 여전히 400.
        assertThatThrownBy(() -> service.validateManualMarks(
                List.of(new MarkItem(999_999_999, "99:99")), durationSec, fps, RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // FE 가 30 을 하드코딩하면 만들어지던 값(55×30=1650)은 상한(1500+25=1525) 밖이라 여전히 거부된다
        //   — 서버가 관대해진 게 아니라 FE 가 같은 기준을 쓰게 된 것이 수정의 핵심임을 고정.
        assertThatThrownBy(() -> service.validateManualMarks(
                List.of(new MarkItem(1650, "00:55")), durationSec, fps, RAW_SN))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("자동생성_마킹은_형식과_상한을_스스로_만족한다")
    void autoGeneratedMarksSatisfyTheSameRules() {
        // AUTO 는 사용자 marks 표면이 없다(서버가 생성). 그 산출물이 MANUAL 규칙을 그대로 만족하는지 확인해
        // 형제 경로에 동일 결함이 없음을 고정한다(중복 없음·0 이상·총 프레임 미만·타임스탬프 형식 정상).
        String json = service.generateAutoMarks(10, 30, FPS);
        assertThat(json).isNotBlank();
        List<MarkItem> generated = parse(json);
        assertThat(generated).isNotEmpty();
        assertThat(generated).allSatisfy(m -> assertThat(validator.validate(m)).isEmpty());
        assertThatCode(() -> service.validateManualMarks(generated, 10, FPS, RAW_SN))
                .doesNotThrowAnyException();
    }

    private List<MarkItem> parse(String json) {
        try {
            return new ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<MarkItem>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
