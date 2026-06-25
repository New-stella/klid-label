package kr.co.cudo.authoring.dev.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.dev.dto.DevTokenRequest;
import kr.co.cudo.authoring.dev.dto.DevTokenResponse;
import kr.co.cudo.authoring.dev.service.DevTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [개발/검수 전용] 테스트 JWT 발급 API.
 *
 * <p>{@code authoring.dev.login.enabled=true}(env {@code DEV_LOGIN_ENABLED}) 일 때만 빈이 등록되어
 * endpoint 가 노출된다. 미설정/false 면 prd·dev 무관하게 빈 부재(404) — <b>fail-closed</b>.
 * local/dev/stg 프로파일은 yml 기본값으로 true 라 기존 동작이 유지된다.
 *
 * <p>SecurityConfig 도 같은 프로퍼티를 읽어 {@code /v1/dev/tokens} permitAll 매처를 조건부로 추가한다.
 */
@Tag(name = "dev-token",
        description = "[개발/검수 전용] 테스트 JWT 발급. ⚠ authoring.dev.login.enabled=true 일 때만 노출됩니다 (기본 비활성).")
@RestController
@RequestMapping("/v1/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "authoring.dev.login", name = "enabled", havingValue = "true")
public class DevTokenController {

    private final DevTokenService devTokenService;

    @Operation(
            summary = "테스트 JWT 토큰 발급 (개발/검수 전용)",
            description = """
                    HS256 서명된 테스트 JWT 를 즉시 발급한다.

                    <ul>
                      <li>인증 불필요 (운영 환경에서는 endpoint 가 존재하지 않음).</li>
                      <li>role-channel 조합: REVIEWER/WORKER ↔ INTERNAL, PORTAL_USER ↔ PORTAL.</li>
                      <li>expSeconds 범위: 60 ~ 86400(24h). 미지정 시 3600(1h).</li>
                      <li>응답에는 토큰·디코드된 클레임·복사용 Authorization 헤더가 포함된다 (secret 미포함).</li>
                    </ul>
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "토큰 발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 (role-channel 불일치, expSeconds 범위 초과 등)")
    })
    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DevTokenResponse> issue(@Valid @RequestBody DevTokenRequest request) {
        return ApiResponse.ok(devTokenService.issue(request));
    }
}
