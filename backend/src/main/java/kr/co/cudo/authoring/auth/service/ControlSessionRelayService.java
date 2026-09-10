package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.dto.ControlTokenRefreshResponse;
import kr.co.cudo.authoring.common.client.ControlAccountClient;
import kr.co.cudo.authoring.common.client.ControlAccountClient.RefreshResult;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 관제 계정 세션 창구 중계 — 갱신·로그아웃 (관제 채널 전용).
 *
 * <h3>저작도구 로그인 검사를 하지 않는다 (사양)</h3>
 * <p>갱신은 access 토큰이 이미 만료돼 401 이 돌아온 뒤의 재시도 경로에서도 돼야 하고, 로그아웃은 만료
 * 직전·직후 토큰으로도 진행돼야 한다. 그래서 두 창구 모두 인증 컨텍스트를 보지 않는다 — 자격증명은
 * 갱신의 본문 refresh 토큰 / 로그아웃의 Bearer access 토큰이고, 판정은 관제가 한다.
 *
 * <p>DB 를 쓰지 않으므로 트랜잭션을 두지 않는다.
 *
 * @design API-247
 * @design API-246
 * @design INT-015
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlSessionRelayService {

    /** {@code Authorization} 에서 인정하는 유일한 스킴 접두 — 인증 필터와 같은 표기다. */
    static final String BEARER_PREFIX = "Bearer ";

    private final ControlAccountClient controlAccountClient;

    /**
     * 관제 세션 갱신 — 성공이면 새 토큰 쌍, 거절이면 401, 일시 장애면 503.
     *
     * <p>관제 오류 문구는 응답에 싣지 않는다 — 오류 코드로만 구분한다.
     */
    public ControlTokenRefreshResponse refresh(String refreshToken) {
        RefreshResult result = controlAccountClient.refresh(refreshToken);
        return switch (result.outcome()) {
            case SUCCESS -> new ControlTokenRefreshResponse(result.sessionToken(), result.refreshToken());
            case REJECTED -> throw new CustomException(ErrorCode.CONTROL_SESSION_REJECTED);
            case UNAVAILABLE -> throw new CustomException(ErrorCode.CONTROL_SESSION_UNAVAILABLE);
        };
    }

    /**
     * 관제 세션 로그아웃 — 결과와 무관하게 조용히 끝난다(호출자는 항상 204).
     *
     * <p>★ Bearer 형식이 아니거나 값이 비면 <b>관제를 부르지 않는다</b>(fail-closed) — 헤더가 없는 것과 같다.
     *
     * @param authorizationHeader 요청의 {@code Authorization} 헤더 원문(없으면 {@code null})
     */
    public void logout(String authorizationHeader) {
        String accessToken = bearerTokenOf(authorizationHeader);
        if (accessToken == null) {
            log.info("[ControlSession] logout relay skipped — no bearer credential");
            return;
        }
        try {
            boolean confirmed = controlAccountClient.logout(accessToken);
            if (confirmed) {
                log.info("[ControlSession] logout relayed");
            }
        } catch (RuntimeException e) {
            // 클라이언트는 던지지 않도록 만들었지만, 이 창구의 계약(항상 204)을 이중으로 지킨다.
            log.warn("[ControlSession] logout relay failed cause={}", e.getClass().getSimpleName());
        }
    }

    /** {@code Bearer } 접두가 있고 뒤가 공백이 아닐 때만 토큰을 돌려준다. */
    static String bearerTokenOf(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
