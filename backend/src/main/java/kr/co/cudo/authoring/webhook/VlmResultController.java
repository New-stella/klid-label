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
 * 외부 VLM 시계열 메타 결과 수신 webhook — Phase 2.
 * 인증: HMAC (HmacWebhookFilter).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/vlm")
public class VlmResultController {

    private final VlmResultService service;

    @PostMapping("/result")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receive(
            @Valid @RequestBody VlmResultRequest request) {
        boolean applied = service.handle(request);
        Map<String, Object> body = Map.of(
                "applied", applied,
                "idempotencyKey", request.idempotencyKey()
        );
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
