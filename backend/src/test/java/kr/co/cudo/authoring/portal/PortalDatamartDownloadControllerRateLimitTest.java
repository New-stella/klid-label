package kr.co.cudo.authoring.portal;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.controller.PortalDatamartDownloadController;
import kr.co.cudo.authoring.portal.service.PortalDatamartDownloadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API-203 — 다운로드 per-user RateLimiter(CWE-770) 단위 검증.
 *
 * <p>판정 순서 ②: 속도 제한은 자원 판정(③~⑤)보다 <b>앞</b>이라 제한을 넘긴 요청은 조회 자체를
 * 수행하지 않는다. 형제 엔드포인트(portalUpload · portalUserLabel)와 동일 패턴이며 config 이름은
 * {@code portalDatamartDownload} 다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalDatamartDownloadControllerRateLimitTest {

    @Mock PortalDatamartDownloadService downloadService;

    private final TokenClaims portalUser =
            new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));
    private final TokenClaims otherUser =
            new TokenClaims("bob", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    /** limit-for-period=1 → 같은 사용자의 두 번째 요청은 permit 획득 실패로 429. */
    private RateLimiterRegistry strictRateLimiter() {
        return RateLimiterRegistry.of(Map.of("portalDatamartDownload", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO).build()));
    }

    private PortalDatamartDownloadController controller(RateLimiterRegistry registry) {
        ResponseEntity<StreamingResponseBody> ok = ResponseEntity.ok(out -> { });
        when(downloadService.download(anyLong(), any())).thenReturn(ok);
        return new PortalDatamartDownloadController(downloadService, registry);
    }

    @Test
    @DisplayName("과도한_다운로드_요청은_429로_제한되고_자원_판정에_도달하지_않는다")
    void downloadRateLimitExceeded() {
        PortalDatamartDownloadController controller = controller(strictRateLimiter());

        controller.download(100L, portalUser);

        assertThatThrownBy(() -> controller.download(100L, portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        // 제한을 넘긴 두 번째 요청은 조회·게이트 판정을 수행하지 않는다(제한기가 막으려는 비용).
        verify(downloadService).download(anyLong(), any());
    }

    @Test
    @DisplayName("제한은_사용자별로_격리된다_다른_사용자는_영향없음")
    void rateLimitIsPerUser() {
        PortalDatamartDownloadController controller = controller(strictRateLimiter());

        controller.download(100L, portalUser);

        assertThatCode(() -> controller.download(100L, otherUser)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("토큰이_없으면_401이고_제한기를_소모하지_않는다")
    void missingToken_unauthorized() {
        PortalDatamartDownloadController controller = controller(strictRateLimiter());

        assertThatThrownBy(() -> controller.download(100L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(downloadService, never()).download(anyLong(), any());
    }
}
