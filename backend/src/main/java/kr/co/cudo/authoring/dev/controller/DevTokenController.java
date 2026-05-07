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
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [개발/검수 전용] 테스트 JWT 발급 API.
 *
 * <p>운영(prd) 환경에서는 {@code @Profile("!prd")} 로 빈 자체가 등록되지 않아 endpoint 가 노출되지 않는다 (404).
 *
 * <p>SecurityConfig 에서도 환경에 따라 {@code /v1/dev/**} 매처가 조건부로 permitAll 된다.
 */
@Tag(name = "dev-token",
        description = "[개발/검수 전용] 테스트 JWT 발급. ⚠ 운영(prd) 환경에서는 비활성화되어 endpoint 가 존재하지 않습니다.")
@RestController
@RequestMapping("/v1/dev")
@RequiredArgsConstructor
@Profile("!prd")
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
