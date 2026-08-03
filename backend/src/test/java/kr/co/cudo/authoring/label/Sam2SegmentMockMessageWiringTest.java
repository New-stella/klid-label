package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.controller.LabelController;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
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
 * SAM2 세그 mock 안전장치 재배선 — 컨트롤러가 빈 폴리곤(내부 mock 신호)을 감지하면
 * {@link ApiResponse#message} 에 안내를 세팅하는지 검증(POJO 단위, Spring 컨텍스트 불필요).
 *
 * <p>포털(외부 채널) SAM2 는 ADR-013 위반으로 제거됐다 — 내부 경로만 남는다
 * (제거 회귀는 {@code PortalSam2RemovedTest}).
 */
class Sam2SegmentMockMessageWiringTest {

    private final TokenClaims actor =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    private final Sam2SegmentRequest req =
            new Sam2SegmentRequest(7L, List.of(List.of(10.0, 10.0)), null);

    @Test
    @DisplayName("내부_SAM2세그_빈폴리곤이면_ApiResponse에_안내메시지_세팅")
    void internalEmptyPolygonSetsMessage() {
        Sam2SegmentService segService = mock(Sam2SegmentService.class);
        when(segService.segment(any(), any())).thenReturn(Sam2SegmentResponse.empty());
        LabelController controller = new LabelController(
                mock(LabelService.class), mock(Sam2TrackService.class), segService, mock(YoloTrackService.class));

        ApiResponse<Sam2SegmentResponse> res = controller.sam2Segment(7L, req, actor);

        assertThat(res.success()).isTrue();
        assertThat(res.data().polygon()).isEmpty();
        assertThat(res.message()).isEqualTo(Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("내부_SAM2세그_정상폴리곤이면_메시지_없음")
    void internalNormalPolygonNoMessage() {
        Sam2SegmentService segService = mock(Sam2SegmentService.class);
        when(segService.segment(any(), any())).thenReturn(new Sam2SegmentResponse(
                List.of(List.of(1.0, 1.0), List.of(2.0, 2.0), List.of(1.0, 2.0)), 0.9));
        LabelController controller = new LabelController(
                mock(LabelService.class), mock(Sam2TrackService.class), segService, mock(YoloTrackService.class));

        ApiResponse<Sam2SegmentResponse> res = controller.sam2Segment(7L, req, actor);

        assertThat(res.data().polygon()).hasSize(3);
        assertThat(res.message()).isNull();
    }
}
