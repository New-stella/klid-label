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
 * <p>인증: 벤더 규격상 콜백은 HMAC 등 서명 헤더가 없다. 무단 콜백 주입은
 * {@code VlmResultService} 의 request_id 발급 게이트(isIssued)로 차단한다.
 *
 * <p>// TODO(보안): 실운영 전 IP allowlist 재검토 — 벤더 규격 무인증(HMAC 제거, 2026-07-07 승인)
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
