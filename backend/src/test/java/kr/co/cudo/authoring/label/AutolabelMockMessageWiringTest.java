package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.controller.AutolabelController;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 오토라벨 mock 안전장치 재배선(SAM2 세그와 대칭) — 컨트롤러가 내부 mock 신호를 감지하면
 * {@link ApiResponse#message} 에 안내를 세팅하는지 검증(POJO 단위, Spring 컨텍스트 불필요).
 *
 * <p>FE 는 이 message 유무로 mock(경고 토스트) 와 정상 "0건 검출"(성공 토스트) 을 구분한다.
 * {@link AutolabelResponse} record 에는 mock 플래그가 없어 FE 계약이 불변임을 함께 확인한다.
 */
class AutolabelMockMessageWiringTest {

    private final TokenClaims actor =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));

    @Test
    @DisplayName("오토라벨_내부mock이면_ApiResponse에_안내message_세팅")
    void mockSetsMessage() {
        AutolabelOnlineService service = mock(AutolabelOnlineService.class);
        when(service.autolabel(any(), any(), any(), any(), any(), any())).thenReturn(
                new AutolabelOnlineService.AutolabelOutcome(
                        new AutolabelResponse(7L, 0, List.of()), true));
        AutolabelController controller = new AutolabelController(service);

        ApiResponse<AutolabelResponse> res = controller.autolabel(7L, null, actor);

        assertThat(res.success()).isTrue();
        assertThat(res.data().savedCount()).isZero();
        assertThat(res.message()).isEqualTo(AutolabelResponse.MOCK_UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("오토라벨_정상이면_message_없음")
    void normalNoMessage() {
        AutolabelOnlineService service = mock(AutolabelOnlineService.class);
        when(service.autolabel(any(), any(), any(), any(), any(), any())).thenReturn(
                new AutolabelOnlineService.AutolabelOutcome(
                        new AutolabelResponse(7L, 1, List.of(
                                new AutolabelResponse.Item(1L, 10L, "person",
                                        List.of(1.0, 2.0, 3.0, 4.0), 0.9, 3))), false));
        AutolabelController controller = new AutolabelController(service);

        ApiResponse<AutolabelResponse> res = controller.autolabel(7L, null, actor);

        assertThat(res.data().savedCount()).isEqualTo(1);
        assertThat(res.message()).isNull();
    }

    @Test
    @DisplayName("폴리곤_상한초과_message가_ApiResponse에_그대로_세팅된다")
    void polygonTruncatedMessageWired() {
        AutolabelOnlineService service = mock(AutolabelOnlineService.class);
        String msg = AutolabelResponse.polygonTruncatedMessage(30, 20);
        when(service.autolabel(any(), any(), any(), any(), any(), any())).thenReturn(
                new AutolabelOnlineService.AutolabelOutcome(
                        new AutolabelResponse(7L, 20, List.of()), false, msg));
        AutolabelController controller = new AutolabelController(service);

        ApiResponse<AutolabelResponse> res = controller.autolabel(7L, null, actor);

        // 폴리곤 안내 message 가 mock 안내보다 우선하며 그대로 노출된다.
        assertThat(res.message()).isEqualTo(msg);
    }

    @Test
    @DisplayName("AutolabelResponse_record에_mock컴포넌트_없음")
    void dtoHasNoMockField() {
        boolean hasMock = java.util.Arrays.stream(AutolabelResponse.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().equals("mock"));
        assertThat(hasMock).isFalse();
    }
}
