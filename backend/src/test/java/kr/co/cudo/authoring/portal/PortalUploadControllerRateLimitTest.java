package kr.co.cudo.authoring.portal;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.controller.PortalUploadController;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * V107 — 포털 업로드 per-user RateLimiter(CWE-770) 단위 검증.
 *
 * <p>{@link PortalUploadController#acquireUploadPermit} 경로: {@code portalUpload} config 를 좁은
 * 한도(limitForPeriod=1)로 주입하여 같은 사용자의 두 번째 업로드 요청이 permit 소진으로
 * {@link ErrorCode#TOO_MANY_REQUESTS}(429) 로 거부되는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalUploadControllerRateLimitTest {

    @Mock PortalUploadService portalUploadService;

    private final TokenClaims portalUser =
            new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    /** limit-for-period=1 → 같은 사용자의 두 번째 요청은 permit 획득 실패로 429. config 이름 = portalUpload. */
    private RateLimiterRegistry strictRateLimiter() {
        return RateLimiterRegistry.of(Map.of("portalUpload", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO).build()));
    }

    @Test
    @DisplayName("업로드_요청량_초과시_429")
    void uploadRateLimitExceeded() {
        PortalUploadController controller =
                new PortalUploadController(portalUploadService, strictRateLimiter());
        when(portalUploadService.uploadImages(anyString(), any())).thenReturn(List.of());
        List<org.springframework.web.multipart.MultipartFile> files = List.of();

        // 첫 요청은 permit 획득 → 정상.
        controller.uploadImages(files, portalUser);

        // 두 번째 요청(같은 사용자)은 permit 소진 → 429.
        assertThatThrownBy(() -> controller.uploadImages(files, portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }
}
