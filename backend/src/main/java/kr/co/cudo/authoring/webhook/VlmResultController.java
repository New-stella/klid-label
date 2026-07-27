package kr.co.cudo.authoring.webhook;

import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.webhook.dto.VlmResultRequest;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 외부 VLM describe 콜백 결과 수신 webhook — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) 정합.
 *
 * <p><b>인증</b>: 벤더 규격상 콜백은 HMAC 등 서명 헤더가 없다(2026-07-07 승인). HMAC 을 요구하면 실
 * 콜백이 전건 401 이 되므로 편입하지 않는다. 대신 <b>3계층</b>으로 보호한다(B-ISSUE-25 종결):
 * <ol>
 *   <li><b>IP allowlist</b> — {@code webhook.vlm.allowed-ip-cidrs} ({@code WebhookIpAllowlist}).
 *       운영 프로파일에서 벤더 대역을 지정한다. 미설정 시 기동 로그 WARN.</li>
 *   <li><b>rate limit + 본문 size cap</b> — {@code HmacWebhookFilter} 의 무서명 가드 경로.
 *       무인증 상태에서의 pre-auth 자원 소모(CWE-770/307)를 차단한다.</li>
 *   <li><b>request_id 발급 게이트</b> — {@code VlmResultService.lookupForProcessing} 이 본 도구가
 *       발급한 request_id 만 처리한다. <b>HMAC 편입 여부와 무관하게 항상 유지되는 최종 방어선</b>이며
 *       제거하면 무단 주입이 성립한다(S-25).</li>
 * </ol>
 *
 * <p>또한 {@code WebhookGateInterceptor} 가 컨트롤러 진입 직전 "필터를 통과했다는 증거" 를 확인하므로,
 * 경로 변형으로 필터를 건너뛴 요청은 size cap 우회도 불가능하다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/vlm")
public class VlmResultController {

    private final VlmResultService service;

    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receive(
            @Valid @RequestBody VlmResultRequest request) {
        boolean applied = service.handle(request);
        Map<String, Object> body = Map.of(
                "applied", applied,
                "requestId", request.requestId()
        );
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
