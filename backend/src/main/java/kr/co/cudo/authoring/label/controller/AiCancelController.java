package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.client.AiCallCancellationInterceptor;
import kr.co.cudo.authoring.common.client.AiCallCancellationRegistry;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AiCancelResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라벨링 화면의 <b>AI 취소 버튼</b> — 진행 중인 온디맨드 추론을 실제로 끊는다.
 *
 * <h3>왜 별도 요청이어야 하는가 (실험으로 확인한 제약)</h3>
 * <p>"클라이언트가 연결을 끊으면 서버도 끊는다"는 이 스택에서 <b>성립하지 않는다</b> — Tomcat 은
 * 비동기 처리 중 유휴 구간의 클라이언트 종료를 알려주지 않고, 동기 처리 중에는 관측 수단 자체가 없다
 * (실험: {@code ClientDisconnectObservabilityProbeTest}). 그래서 화면이 추론 요청에
 * {@code X-AI-Request-Id} 를 실어 보내고, 취소 버튼이 <b>같은 식별자로 이 API 를 부른다</b>.
 *
 * <h3>범위</h3>
 * <p>라벨링 화면의 온디맨드 추론만이다. <b>배치 파이프라인 오토라벨은 대상이 아니다</b> — 사람이
 * 기다리는 요청이 아니고, 취소할 화면도 없다.
 *
 * <h3>응답은 «끊었다/못 끊었다» 뿐이며 항상 200 이다</h3>
 * <p>못 끊는 사유는 여럿이지만(이미 끝남·이미 취소함·다른 노드가 처리 중) 구분해 알려주지 않는다.
 * 화면에는 어느 쪽이든 «더 기다릴 필요 없음» 으로 같고, 구분해 주면 응답이 남의 요청 존재 여부를
 * 알려주는 오라클이 된다.
 */
@Tag(name = "AI Cancel", description = "진행 중인 온디맨드 AI 추론 취소 — 본인이 시작한 요청만")
@RestController
@RequestMapping("/v1/ai-requests")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AiCancelController {

    private final AiCallCancellationRegistry registry;

    @Operation(
            summary = "진행 중인 AI 추론 취소 (REVIEWER/WORKER)",
            description = "추론 요청에 " + AiCallCancellationInterceptor.REQUEST_ID_HEADER
                    + " 헤더로 실어 보낸 식별자로 그 요청의 추론 호출을 끊는다. "
                    + "본인이 시작한 요청만 취소할 수 있으며, 남의 식별자는 «없음» 과 같게 처리된다. "
                    + "이미 끝났거나 다른 노드가 처리 중이면 cancelled=false 로 200 을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취소 시도 결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "내부 채널 검수자·작업자 아님")
    })
    @PostMapping("/{requestId}/cancel")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<AiCancelResponse> cancel(
            @Parameter(description = "추론 요청 시 보낸 취소 식별자", required = true, example = "a1b2c3d4")
            @PathVariable String requestId,
            @AuthenticationPrincipal TokenClaims actor) {
        // 소유자 판정은 등록소가 한다 — 여기서 다시 판정하면 규칙이 두 곳에 생긴다.
        boolean cancelled = registry.cancel(requestId, actor == null ? null : actor.sub());
        return ApiResponse.ok(new AiCancelResponse(cancelled));
    }
}
