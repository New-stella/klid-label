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
 *
 * <p><b>인증</b>: HMAC ({@code HmacWebhookFilter}) 단독. {@code SecurityConfig} 에서 permitAll 이므로
 * 필터가 유일한 인증 수단이다.
 *
 * <p><b>이중 게이트</b>: 경로 인코딩 변형으로 필터를 우회한 요청이 이 컨트롤러에 도달하는 사고가
 * 실증된 바 있어(E-ISSUE-01, CWE-436→CWE-288), {@code WebhookGateInterceptor} 가 진입 직전
 * "필터를 통과했다는 증거"({@code WebhookGuardedRequest} 래퍼)를 확인하고 없으면 401 로 차단한다.
 * 인터셉터는 {@code @RequestBody} 역직렬화보다 먼저 실행된다.
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
