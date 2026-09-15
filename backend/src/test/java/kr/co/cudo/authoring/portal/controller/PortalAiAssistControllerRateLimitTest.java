package kr.co.cudo.authoring.portal.controller;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AiCancelResponse;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import kr.co.cudo.authoring.portal.service.PortalAiAssistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 AI 실행 창구 사용자별 요청량 제한(CWE-770) — 실행 창구 세 곳이 한 한도를 함께 쓰고, 조회·취소는 제외.
 *
 * @design API-254, API-255, API-257, API-258
 */
class PortalAiAssistControllerRateLimitTest {

    private final TokenClaims alice = new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));
    private final TokenClaims bob = new TokenClaims("bob", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    private PortalAiAssistService service;
    private PortalAiAssistController controller;

    /** limit-for-period=2 → 같은 사용자의 세 번째 실행은 429. config 이름 = portalAiAssist. */
    @BeforeEach
    void setUp() {
        service = mock(PortalAiAssistService.class);
        when(service.autolabel(any(), any(), any())).thenReturn(new AutolabelOnlineService.AutolabelOutcome(
                new AutolabelResponse(1L, 0, List.of()), false, null));
        when(service.segment(any(), any())).thenReturn(Sam2SegmentResponse.empty());
        when(service.track(any(), any())).thenReturn(new YoloTrackResponseDto(List.of()));
        when(service.cancel(anyString(), any())).thenReturn(new AiCancelResponse(false));
        RateLimiterRegistry registry = RateLimiterRegistry.of(Map.of(PortalAiAssistController.AI_ASSIST_RL_CONFIG,
                RateLimiterConfig.custom().limitForPeriod(2).limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO).build()));
        controller = new PortalAiAssistController(service, registry);
    }

    @Test
    @DisplayName("★실행_창구_세_곳은_사용자별_한도_하나를_함께_쓴다_초과시_429")
    void threeExecutionWindowsShareOneLimit() {
        controller.autolabel(1L, null, alice);
        controller.sam2Segment(1L, new Sam2SegmentRequest(1L, List.of(List.of(1.0, 1.0)), null, null), alice);

        assertThatThrownBy(() -> controller.yoloTrack(1L, new YoloTrackRequest(1L, List.of()), alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        verify(service, never()).track(any(), any());
    }

    @Test
    @DisplayName("한도는_사용자별로_격리된다")
    void limitIsPerUser() {
        controller.autolabel(1L, null, alice);
        controller.autolabel(1L, null, alice);

        assertThatCode(() -> controller.autolabel(1L, null, bob)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("조회와_취소에는_한도를_걸지_않는다")
    void defaultsAndCancelAreNotLimited() {
        controller.autolabel(1L, null, alice);
        controller.autolabel(1L, null, alice);

        assertThatCode(() -> {
            controller.aiDefaults();
            controller.cancel("req-1", alice);
            controller.cancel("req-1", alice);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("path와_body의_srcSn이_다르면_400_이고_한도를_소모하지_않는다")
    void pathBodyMismatchIs400WithoutConsumingPermit() {
        assertThatThrownBy(() -> controller.yoloTrack(9L, new YoloTrackRequest(1L, List.of()), alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> controller.sam2Segment(9L,
                new Sam2SegmentRequest(1L, List.of(List.of(1.0, 1.0)), null, null), alice))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        controller.autolabel(1L, null, alice);
        assertThatCode(() -> controller.autolabel(1L, null, alice)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("mock_분할_응답이면_안내_문구를_싣는다_내부_창구와_같은_규약")
    void mockSegmentCarriesMessage() {
        var res = controller.sam2Segment(1L, new Sam2SegmentRequest(1L, List.of(List.of(1.0, 1.0)), null, null), alice);
        assertThat(res.message()).isEqualTo(Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE);
    }
}
