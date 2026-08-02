package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.controller.LabelController;
import kr.co.cudo.authoring.label.dto.Sam2TrackOutcome;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.label.service.Sam2SegmentService;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SAM2 Track mock 안전장치 배선 (C-ISSUE-81, CWE-345) — 컨트롤러가 서비스의 mock 신호를 받아
 * {@link ApiResponse#message} 에 안내를 세팅하는지 검증(POJO 단위, Spring 컨텍스트 불필요).
 *
 * <p>{@link Sam2SegmentMockMessageWiringTest}(세그) 와 동형이다. 포털(외부 채널) SAM2 는 ADR-013
 * 위반으로 제거돼 내부 경로만 남는다(제거 회귀는 {@code PortalSam2RemovedTest}).
 */
class Sam2TrackMockMessageWiringTest {

    private final TokenClaims actor =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));

    private final Sam2TrackRequest req = new Sam2TrackRequest(7L, "track-1",
            List.of(List.of(10.0, 10.0), List.of(30.0, 10.0), List.of(30.0, 30.0)),
            "person", List.of(8L));

    private static Sam2TrackResponseDto oneItem() {
        return new Sam2TrackResponseDto(List.of(new Sam2TrackResponseDto.TrackedItem(
                8L, "track-1", "person",
                List.of(List.of(11.0, 12.0), List.of(31.0, 12.0), List.of(31.0, 32.0)), 0.9)));
    }

    private static LabelController controller(Sam2TrackService trackService) {
        return new LabelController(mock(LabelService.class), trackService,
                mock(Sam2SegmentService.class), mock(YoloTrackService.class));
    }

    @Test
    @DisplayName("내부_SAM2추적_전량mock이면_빈결과와_모델미로드_안내를_반환")
    void internalAllMockSetsUnavailableMessage() {
        Sam2TrackService trackService = mock(Sam2TrackService.class);
        when(trackService.track(any(), any()))
                .thenReturn(Sam2TrackOutcome.withMock(new Sam2TrackResponseDto(List.of())));

        ApiResponse<Sam2TrackResponseDto> res = controller(trackService).sam2Track(7L, req, actor);

        assertThat(res.success()).isTrue();
        assertThat(res.data().tracked()).isEmpty();
        assertThat(res.message()).isEqualTo(Sam2TrackOutcome.MOCK_UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("내부_SAM2추적_일부mock이면_남은결과와_부분신뢰불가_안내를_반환")
    void internalPartialMockSetsPartialMessage() {
        Sam2TrackService trackService = mock(Sam2TrackService.class);
        when(trackService.track(any(), any())).thenReturn(Sam2TrackOutcome.withMock(oneItem()));

        ApiResponse<Sam2TrackResponseDto> res = controller(trackService).sam2Track(7L, req, actor);

        assertThat(res.data().tracked()).hasSize(1);
        assertThat(res.message()).isEqualTo(Sam2TrackOutcome.PARTIAL_MOCK_MESSAGE);
    }

    @Test
    @DisplayName("내부_SAM2추적_정상결과면_메시지_없음")
    void internalNormalNoMessage() {
        Sam2TrackService trackService = mock(Sam2TrackService.class);
        when(trackService.track(any(), any())).thenReturn(Sam2TrackOutcome.ok(oneItem()));

        ApiResponse<Sam2TrackResponseDto> res = controller(trackService).sam2Track(7L, req, actor);

        assertThat(res.data().tracked()).hasSize(1);
        assertThat(res.message()).isNull();
    }
}
