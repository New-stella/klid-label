package kr.co.cudo.authoring.portal;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.controller.PortalLabelController;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 포털 사용자 라벨 저장({@code POST /v1/portal/user-labels}) per-user RateLimiter(CWE-770) 단위 검증.
 *
 * <p>형제 엔드포인트({@code PortalUploadController}, {@code portalUpload} config)와 동일 패턴이다 —
 * 저장 경로에만 속도 제한이 통째로 빠져 있어 인증된 PORTAL_USER 한 명이 무제한으로 라벨 행을
 * 적재할 수 있었다. config 를 좁은 한도(limitForPeriod=1)로 주입해 두 번째 요청이 429 인지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalLabelControllerRateLimitTest {

    @Mock PortalLabelService portalLabelService;

    private final TokenClaims portalUser =
            new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));
    private final TokenClaims otherUser =
            new TokenClaims("bob", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    /** limit-for-period=1 → 같은 사용자의 두 번째 요청은 permit 획득 실패로 429. config 이름 = portalUserLabel. */
    private RateLimiterRegistry strictRateLimiter() {
        return RateLimiterRegistry.of(Map.of("portalUserLabel", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO).build()));
    }

    private PortalUserLabelRequest request() {
        return new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[[0,0],[10,10]]", null, null);
    }

    @Test
    @DisplayName("과도한_저장_요청은_429로_제한된다")
    void saveRateLimitExceeded() {
        PortalLabelController controller =
                new PortalLabelController(portalLabelService, strictRateLimiter());
        when(portalLabelService.saveUserLabel(any(), any())).thenReturn(
                new PortalUserLabelResponse(1L, 100L, 10L, "BBOX", "person", "[[0,0],[10,10]]", null, null, null));

        controller.saveUserLabel(request(), portalUser);

        assertThatThrownBy(() -> controller.saveUserLabel(request(), portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("제한은_사용자별로_격리된다_다른_사용자는_영향없음")
    void rateLimitIsPerUser() {
        PortalLabelController controller =
                new PortalLabelController(portalLabelService, strictRateLimiter());
        when(portalLabelService.saveUserLabel(any(), any())).thenReturn(
                new PortalUserLabelResponse(1L, 100L, 10L, "BBOX", "person", "[[0,0],[10,10]]", null, null, null));

        controller.saveUserLabel(request(), portalUser);

        // 다른 사용자는 자기 permit 을 갖는다(전역 한도로 서로를 굶기지 않는다).
        assertThatCode(() -> controller.saveUserLabel(request(), otherUser)).doesNotThrowAnyException();
    }
}
