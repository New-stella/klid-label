package kr.co.cudo.authoring.webhook;

import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.webhook.dto.GenAiCallbackRequest;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.GenAiCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 생성형 AI(증강) 결과 수신 webhook — 「생성형 AI API 연동명세서 v1.1」 §4.2/§4.3 (Phase 7-A2).
 *
 * <p><b>인증</b>: 명세서상 웹훅에 서명·인증 헤더가 없다. 구 계약({@code /v1/aug/callback}, HMAC
 * 서명 필수)은 외부 실물과 맞지 않아 제거했고, VLM 과 동일한 <b>무서명 3계층</b>으로 보호한다:
 * <ol>
 *   <li><b>IP allowlist</b> — {@code webhook.genai.allowed-ip-cidrs}
 *       ({@code GenAiWebhookIpAllowlist}). VLM 과 달리 <b>미설정이면 전면 차단</b>(fail-closed).</li>
 *   <li><b>rate limit + 본문 size cap(1MB)</b> — {@code HmacWebhookFilter} 무서명 가드 경로.
 *       무인증 상태의 pre-auth 자원 소모(CWE-770/307)를 차단한다.</li>
 *   <li><b>request_id 발급 게이트</b> — {@code GenAiCallbackService} 가
 *       {@code LS_DATA_AUG_JOB.IDMP_KEY} 에 있는 키만 처리한다(무단 주입 차단, 최종 방어선).</li>
 * </ol>
 *
 * <p>또한 {@code WebhookGateInterceptor} 가 컨트롤러 진입 직전 "필터를 통과했다는 증거" 를 확인하므로,
 * 경로 인코딩 변형으로 필터를 건너뛴 요청은 가드 우회도 불가능하다(E-ISSUE-01 재발 차단).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/genai")
public class GenAiCallbackController {

    private final GenAiCallbackService service;

    /**
     * <p>{@code applied} 는 <b>증강 인계가 실제로 반영됐는지</b>를 뜻한다. 멱등 흡수({@code DUPLICATE})·
     * 롤업 보류({@code DEFERRED})는 {@code applied:false} 로 회신한다 — 구 구현은 이 경우에도
     * {@code true} 를 돌려줘 외부가 정상 인계로 오해했다(E-ISSUE-11).
     *
     * <p>정책 보류({@code WITHHELD_*})는 2026-07-29 로 폐기됐다 — 부모가 비식별 신고({@code 'F'})
     * 구간이어도 파생 생성은 진행하며, 부모를 물리적으로 쓸 수 없는 경우(비식별 미완료·부모/프레임
     * 부재)는 보류가 아니라 {@code REJECTED} 로 실패 확정한다. 사유 코드는 내부 경로·식별정보 없는
     * 고정 코드값만 노출한다는 규약을 유지한다(CWE-209).
     */
    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receive(
            @Valid @RequestBody GenAiCallbackRequest request) {
        AugmentApplyResult result = service.handle(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applied", result.applied());
        body.put("requestId", request.requestId());
        if (result.reasonCode() != null) {
            body.put("reason", result.reasonCode());
        }
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
