package kr.co.cudo.authoring.webhook;

import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.webhook.dto.DeidentifyResultRequest;
import kr.co.cudo.authoring.webhook.service.DeidentifyResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 외부 Deidentify SW 결과 수신 webhook — Phase 2.
 *
 * <p>인증: HMAC ({@code HmacWebhookFilter} 가 SecurityConfig 보다 앞에서 처리).
 *
 * <p>JWT 인증을 거치지 않는 대신, allowlist (사전 발급된 idempotencyKey 만 수락) + HMAC 시그니처 +
 * timestamp 검증으로 인증을 대체한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/deidentify")
public class DeidentifyResultController {

    private final DeidentifyResultService service;

    @PostMapping("/result")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receive(
            @Valid @RequestBody DeidentifyResultRequest request) {
        boolean applied = service.handle(request);
        Map<String, Object> body = Map.of(
                "applied", applied,
                "idempotencyKey", request.idempotencyKey()
        );
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
