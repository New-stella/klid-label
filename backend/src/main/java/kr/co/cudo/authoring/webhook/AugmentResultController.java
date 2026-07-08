package kr.co.cudo.authoring.webhook;

import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 외부 생성형 AI 증강 결과 수신 webhook — Phase 2 (if-augment-result-handover).
 * 인증: HMAC (HmacWebhookFilter).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/aug")
public class AugmentResultController {

    private final AugmentResultService service;

    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receive(
            @Valid @RequestBody AugmentResultRequest request) {
        boolean applied = service.handle(request);
        Map<String, Object> body = Map.of(
                "applied", applied,
                "otsdJobId", request.otsdJobId()
        );
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
