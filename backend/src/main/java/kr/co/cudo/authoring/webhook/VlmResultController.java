package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.webhook.dto.VlmResultRequest;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 외부 VLM <b>verify</b> 콜백 결과 수신 webhook — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) 정합.
 *
 * <p>본문 아래에 남은 "describe" 언급은 <b>폐기된 구 규격</b>을 가리키는 서술이며 현행 동작이 아니다
 * ({@link #handleUnreadableCallback} 의 과도기 힌트 로그).
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
@Slf4j
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

    /**
     * 본문 역직렬화 실패 — <b>구조 힌트만</b> 로그로 남기고 응답은 전역 핸들러와 동일한 400 을 유지한다. [req: R3]
     *
     * <p>과도기에 벤더가 <b>구 describe 배열</b>({@code results:[{start_sec,end_sec,description}]})을 계속
     * 보내면 Jackson 이 400 으로 거부하는 것 자체는 안전하다(관대한 파싱을 두지 않는 것이 확정 계약 정책 —
     * 무단 하위호환은 벤더 버그를 숨긴다). 문제는 전역 핸들러의 메시지가 "요청 본문이 올바르지 않습니다" 뿐이라
     * <b>원인이 보이지 않아 연동 트러블슈팅이 막힌다</b>는 점이다.
     *
     * <p>따라서 이 엔드포인트에 한해 {@code results} 구조 불일치를 식별해 힌트를 남긴다.
     * <b>요청 바디 원문은 로그에 남기지 않는다</b>(CWE-117 로그 위조 / CWE-359 — 서술에 개인정보 묘사가 실릴 수
     * 있다). 응답 본문·상태코드는 전역 핸들러와 동일해 클라이언트가 관측하는 계약은 달라지지 않는다(CWE-209).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableCallback(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        if (isResultsShapeMismatch(cause)) {
            log.warn("[Webhook][Vlm] callback rejected — results 가 배열입니다(확정 계약은 단일 객체 "
                    + "{{description}}). 벤더가 구 규격(구간 배열)을 보내고 있는지 확인 필요");
        } else {
            log.warn("[Webhook][Vlm] callback body not readable cause={}", cause.getClass().getSimpleName());
        }
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "요청 본문이 올바르지 않습니다."));
    }

    /** 실패 지점이 {@code results} 속성이거나 대상 타입이 {@link VlmResultRequest.Results} 인가. */
    private static boolean isResultsShapeMismatch(Throwable cause) {
        if (!(cause instanceof MismatchedInputException mie)) {
            return false;
        }
        Class<?> target = mie.getTargetType();
        if (target != null && VlmResultRequest.Results.class.isAssignableFrom(target)) {
            return true;
        }
        return mie.getPath().stream().anyMatch(ref -> "results".equals(ref.getFieldName()));
    }
}
