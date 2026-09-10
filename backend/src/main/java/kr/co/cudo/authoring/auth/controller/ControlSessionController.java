package kr.co.cudo.authoring.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.auth.dto.ControlTokenRefreshRequest;
import kr.co.cudo.authoring.auth.dto.ControlTokenRefreshResponse;
import kr.co.cudo.authoring.auth.service.ControlSessionRelayService;
import kr.co.cudo.authoring.common.config.DeployFlavorResolver;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관제 세션 중계 창구 — 갱신({@code POST /v1/auth/control-tokens}) · 로그아웃({@code DELETE /v1/auth/control-session}).
 *
 * <h3>인증 매처를 추가하지 않는다</h3>
 * <p>두 경로는 {@code SecurityConfig} 의 {@code /v1/auth/**} permitAll 구역에 있고, <b>그것이 사양이다</b>
 * (로그인 검사 없음 — 만료 토큰으로도 갱신·로그아웃해야 한다). {@code @PreAuthorize} 도 두지 않는다.
 * 인증 필터가 컨텍스트를 세우든 비우든 두 창구의 동작은 같다 — 컨텍스트를 읽지 않는다.
 *
 * <h3>호출 횟수 제한을 두지 않는다 — 인지·수용한 잔여 위험</h3>
 * <p>로그인 검사가 없는 창구라 임의 토큰 반복 호출이 관제 계정 서비스로 그대로 증폭되지만, 속도 제한을
 * 두지 않는다. 근거·수용 범위는 {@code NFR-013} 이 소유한다(내부망 배포 · 포털 향 404 · 판정 주체는 관제).
 * 같은 문단이 포털 향에서 허용되지 않은 메서드에 405 가 나 경로 존재가 드러나는 것도 함께 수용한다 —
 * 「보안 강화」 명목으로 둘 중 어느 것도 되돌리지 않는다.
 *
 * <h3>포털 향 배포본에서는 없는 창구다 (404)</h3>
 * <p>포털 채널은 Host 가 세션을 소유하므로 저작도구가 관제 세션을 연장할 일이 없다. 판정은
 * {@link DeployFlavorResolver} 한 곳이며 여기서 다시 판정하지 않는다.
 * <p>★ 그 게이트를 {@code @ModelAttribute} 로 둔 이유 — 스프링은 {@code @ModelAttribute} 메서드를
 * <b>핸들러 인자 해석(본문 역직렬화·{@code @Valid})보다 먼저</b> 호출한다. 핸들러 본문에서 판정하면
 * 포털 향에서도 잘못된 본문이 400 으로 먼저 거절돼 「창구가 있다」가 드러난다.
 *
 * @design API-247
 * @design API-246
 * @design NFR-013
 */
@Tag(name = "Control Session",
        description = "관제 채널 세션 중계 — 저작도구 서버가 관제지원 계정 서비스의 갱신·로그아웃 창구를 대신 호출한다. 포털 채널 배포본에는 없다.")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class ControlSessionController {

    /** 포털 향 404 문구 — 경로 자체가 없을 때의 공통 응답과 같은 모양이다. */
    static final String NOT_FOUND_MESSAGE = "요청한 API를 찾을 수 없습니다.";

    private final ControlSessionRelayService relayService;
    private final DeployFlavorResolver deployFlavorResolver;

    /**
     * 포털 향 배포본이면 두 창구 모두 404 — 인자 해석보다 먼저 돈다(클래스 javadoc).
     */
    @ModelAttribute
    public void requireControlFlavor() {
        if (deployFlavorResolver.isPortal()) {
            throw new CustomException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
    }

    @Operation(
            summary = "관제 세션 갱신 중계 (관제 채널 전용)",
            description = """
                    저작도구 서버가 관제지원 계정 서비스의 갱신 창구를 대신 호출해 새 토큰 쌍을 돌려준다.
                    저작도구 로그인 검사를 하지 않는다 — access 토큰이 만료된 뒤의 재시도 경로에서도 갱신해야 한다.
                    자격증명은 본문의 refresh 토큰 하나이며 유효성은 관제가 판정한다.
                    비멱등이라 자동 재시도하지 않는다. 서버는 토큰을 저장하지 않는다.
                    실패는 두 갈래다 — 관제 거절 401 CONTROL_SESSION_REJECTED / 일시 장애 503 CONTROL_SESSION_UNAVAILABLE.
                    포털 채널 배포본에서는 404 다.
                    """)
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "갱신 성공 — 관제가 새로 발급한 토큰 쌍"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "refreshToken 누락·공백·4096자 초과 (INVALID_INPUT) — 관제를 호출하지 않는다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "관제가 갱신을 거절했다 (CONTROL_SESSION_REJECTED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "포털 채널 배포본 — 이 창구가 없다 (NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "관제에 닿지 못했거나 응답을 해석할 수 없다 (CONTROL_SESSION_UNAVAILABLE)")
    })
    @PostMapping("/control-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ControlTokenRefreshResponse> refresh(@Valid @RequestBody ControlTokenRefreshRequest request) {
        return ApiResponse.ok(relayService.refresh(request.refreshToken()));
    }

    @Operation(
            summary = "관제 세션 로그아웃 중계 (관제 채널 전용)",
            description = """
                    Authorization: Bearer <관제 access 토큰> 을 읽어 관제지원 계정 서비스의 로그아웃 창구로 넘긴다.
                    저작도구 로그인 검사를 하지 않는다 — 만료 직전·직후 토큰으로도 진행돼야 한다.
                    헤더가 없거나 Bearer 형식이 아니거나 값이 비면 관제를 부르지 않는다.
                    관제 결과와 무관하게 항상 204 다(브라우저가 결과와 무관하게 토큰 키를 지우고 관제 로그인으로 간다).
                    포털 채널 배포본에서는 404 다.
                    """)
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리 완료 — 관제 결과와 무관"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "포털 채널 배포본 — 이 창구가 없다 (NOT_FOUND)")
    })
    @DeleteMapping("/control-session")
    public ResponseEntity<Void> logout(
            @Parameter(description = "관제 access 토큰 — Bearer 스킴. 없으면 관제를 부르지 않는다")
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        relayService.logout(authorization);
        return ResponseEntity.noContent().build();
    }
}
