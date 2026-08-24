package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VlmClient 단위 테스트 — 벤더 확정 계약(v2.0.1) <b>verify</b> 규격 정합.
 *
 * <p>verify 요청({@code POST /v1/videovlm-klid/describe}) 바디/엔드포인트 + 동기 응답
 * ({@code status="accepted"}, request_id echo) 수용을 검증한다. 동기 응답 형식은 구 describe 와
 * 동일하므로 검증 로직은 무변경이며, 바뀐 것은 <b>경로</b>와 <b>{@code event_type} 필드</b>다.
 */
class VlmClientTest {

    private MockWebServer server;
    private CircuitBreakerRegistry cbRegistry;
    private RetryRegistry retryRegistry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        // V1: 4xx 비재시도 예외는 서킷 failure 로 집계하지 않는다(프로덕션 YAML 정합).
                        // ★ 429 전용 예외도 제외한다 — 서킷이 열리면 그 구간의 정상 위탁이 확정
                        //   실패로 종결되고 마킹이 종결 상태로 굳는다(과부하 완충은 재시도 백오프가 맡는다).
                        .ignoreExceptions(NonRetryableExternalException.class,
                                RateLimitedExternalException.class)
                        .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private WebClient webClient() {
        return WebClient.builder().baseUrl(server.url("/").toString()).build();
    }

    private RetryRegistry singleAttempt() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    private RetryRegistry tripleAttemptShort() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(RuntimeException.class)
                .build());
    }

    /** 프로덕션 정합 재시도 레지스트리 — 3회 재시도하되 4xx 비재시도 예외는 무시(V1). */
    private RetryRegistry tripleAttemptIgnoringNonRetryable() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    private VlmTimeseriesRequest verifyReq(String requestId) {
        return VlmTimeseriesRequest.ofFrameInterval(requestId, "fall", "/data/videos/deid.mp4", "http://localhost:8080/api/v1/vlm/callback");
    }

    @Test
    @DisplayName("위탁_요청_endpoint가_videovlm_verify_이고_바디에_request_id_event_type_media_frame_policy_callback_url_포함")
    void verifyEndpointAndBody() throws InterruptedException {
        // given — accepted 응답 stub
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-abc\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        // when
        VlmTimeseriesResponse resp = client.submitDescribe(verifyReq("req-abc"))
                .block(Duration.ofSeconds(2));

        // then — endpoint + 요청 바디 필드 확인
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("accepted");
        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo(VlmClient.DESCRIBE_PATH);
        assertThat(VlmClient.DESCRIBE_PATH).isEqualTo("/v1/videovlm-klid/describe");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"request_id\":\"req-abc\"");
        assertThat(body).contains("\"event_type\":\"fall\"");
        assertThat(body).contains("\"media\"");
        assertThat(body).contains("\"source_type\":\"path\"");
        assertThat(body).contains("\"path\":\"/data/videos/deid.mp4\"");
        assertThat(body).contains("\"frame_policy\"");
        assertThat(body).contains("\"mode\":\"frame_interval\"");
        // ★ framerate 는 규격의 frame_policy 에 없는 필드다(§2.5) — 실리면 안 된다.
        assertThat(body).doesNotContain("framerate");
        assertThat(body).contains("\"callback_url\":\"http://localhost:8080/api/v1/vlm/callback\"");
        // 벤더 규격 밖 필드 미전송 — 마킹 원문은 frame_policy 로만 반영된다
        assertThat(body).doesNotContain("eventName");
        assertThat(body).doesNotContain("marks");
    }

    @Test
    @DisplayName("★429는_재시도_대상이고_비재시도_예외가_아니다")
    void tooManyRequests_isRetryableAndNotNonRetryable() throws Exception {
        // given — 규격 §2.9: 동시 처리 한도 초과는 "잠시 후 재시도"다. 다른 4xx 와 함께 비재시도로
        //   묶으면, 창구가 둘로 늘어 호출이 2배가 된 상황에서 정상 위탁이 확정 실패로 종결된다.
        //   첫 두 번은 429, 세 번째에 수락 — 재시도가 실제로 돌아야 성공한다.
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-429\",\"status\":\"accepted\"}"));
        retryRegistry = tripleAttemptIgnoringNonRetryable();
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        // when
        VlmTimeseriesResponse resp = client.submitDescribe(verifyReq("req-429"))
                .block(Duration.ofSeconds(10));

        // then — 재시도로 도달한 수락 응답
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("accepted");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("★400과_415는_비재시도라_한_번만_보낸다")
    void deterministicClientErrors_areNotRetried() {
        // given — 다시 보내도 결과가 같은 결정적 실패다(형식 오류·미지원 Content-Type).
        server.enqueue(new MockResponse().setResponseCode(400));
        retryRegistry = tripleAttemptIgnoringNonRetryable();
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        // when / then
        assertThatThrownBy(() -> client.submitDescribe(verifyReq("req-400")).block(Duration.ofSeconds(5)))
                .isInstanceOf(NonRetryableExternalException.class);
        assertThat(server.getRequestCount())
                .as("비재시도 예외는 지수 백오프를 태우지 않는다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("동기응답_status_accepted_수용하고_request_id_echo_확인")
    void acceptedStatusAndRequestIdEcho() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-echo\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        VlmTimeseriesResponse resp = client.submitDescribe(verifyReq("req-echo"))
                .block(Duration.ofSeconds(2));

        assertThat(resp).isNotNull();
        assertThat(resp.requestId()).isEqualTo("req-echo");
        assertThat(resp.status()).isEqualTo("accepted");
    }

    @Test
    @DisplayName("응답_request_id_echo_불일치_시_EXTERNAL_API_ERROR")
    void requestIdEchoMismatchRejected() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"OTHER\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        assertThatThrownBy(() -> client.submitDescribe(verifyReq("req-abc"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("request_id");
    }

    @Test
    @DisplayName("응답_status_accepted_아니면_EXTERNAL_API_ERROR")
    void nonAcceptedStatusRejected() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-abc\",\"status\":\"rejected\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        assertThatThrownBy(() -> client.submitDescribe(verifyReq("req-abc"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("accepted");
    }

    /**
     * ★ 뒤집힌 단언이다 — 구 기대값은 "설정 토글이 꺼져 있으면 외부 호출 0건 + 즉시 SKIPPED(NO-OP)" 였다.
     *
     * <p>그 토글은 폐지됐다(ADR-049). 기본값이 비활성이라 <b>시계열이 꺼진 채 납품돼도 그 사실이
     * 산출물에도 이력에도 남지 않았기</b> 때문이다. 이제 이 클라이언트에는 외부 호출을 건너뛰는 분기가
     * <b>하나도 없다</b> — 건너뛰려면 사람이 단계 스킵을 눌러야 하고(그 판정은 위탁 단계가 갖는다),
     * 미연동이면 호출이 그대로 실패해 기존 실패 경로로 흐른다.
     *
     * <p><b>mutation 확인</b>: {@code submitTimeseries} 에 조기반환 분기를 되살리면 요청 수가 0 이 되어
     * 이 테스트가 실패한다.
     */
    @Test
    @DisplayName("조용히_건너뛰는_분기가_없다_어떤_요청이든_외부로_나간다")
    void neverSkipsSilently() throws InterruptedException {
        // given — 벤더가 수락한다.
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-x\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        // when
        VlmTimeseriesResponse resp = client.submitDescribe(verifyReq("req-x"))
                .block(Duration.ofSeconds(2));

        // then — SKIPPED 로 삼키지 않고 실제로 외부에 나갔다.
        assertThat(resp).isNotNull();
        assertThat(resp.status())
                .as("조용한 SKIPPED 응답을 만들어내는 경로가 남아 있으면 시계열 결손이 드러나지 않는다")
                .isEqualTo("accepted");
        assertThat(server.getRequestCount())
                .as("외부 호출이 0건이면 어딘가에 건너뛰기 분기가 되살아난 것이다")
                .isEqualTo(1);
        RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(sent).isNotNull();
        assertThat(sent.getPath()).isEqualTo("/v1/videovlm-klid/describe");
    }

    @Test
    @DisplayName("호출자가_request_id_null_제공_시_UUID_자동발급_방어")
    void requestIdAutoIssuedWhenNull() throws InterruptedException {
        retryRegistry = singleAttempt();
        // 서버는 클라이언트가 발급한 UUID 를 모르므로 echo 를 맞출 수 없다 — 응답 검증은 실패한다.
        //   구 테스트는 비활성 경로(외부 호출 0건)로 자동발급만 봤으나 그 경로가 폐지됐으므로,
        //   <실제로 전송된 요청 바디>로 판정한다(전송을 관측하므로 오히려 더 강한 단언이다).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"other\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        assertThatThrownBy(() -> client.submitDescribe(
                VlmTimeseriesRequest.ofFrameInterval(null, "fire", "/data/deid.mp4", "http://cb"))
                .block(Duration.ofSeconds(2)))
                .as("echo 불일치는 종전대로 거부된다 — 여기서 보려는 것은 전송된 바디다")
                .isInstanceOf(CustomException.class);

        RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(sent).isNotNull();
        assertThat(sent.getBody().readUtf8())
                .as("request_id 가 null 이면 클라이언트가 UUID 를 발급해 실어 보내야 한다")
                .containsPattern("\"request_id\"\\s*:\\s*\"[0-9a-fA-F-]{36}\"");
    }

    @Test
    @DisplayName("타임아웃_500_시_재시도_3회_후_예외_전파")
    void retryThreeTimesOnFailure() {
        retryRegistry = tripleAttemptShort();
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        assertThatThrownBy(() -> client.submitDescribe(verifyReq("req-fail"))
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);

        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    // ===== V1: 4xx(400/422) 비재시도 분류 — 벤더 규격(§4.1) 비-일시적 오류 =====

    @Test
    @DisplayName("V1_400_형식오류는_재시도없이_1회요청_비재시도예외전파")
    void badRequest400NotRetried() {
        // given — 벤더 규격상 400(형식오류)은 비-일시적. 3회 재시도 설정이라도 재시도되지 않아야 한다.
        retryRegistry = tripleAttemptIgnoringNonRetryable();
        server.enqueue(new MockResponse().setResponseCode(400));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        // when / then
        assertThatThrownBy(() -> client.submitDescribe(verifyReq("req-400"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(NonRetryableExternalException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("V1_422_파라미터값오류도_재시도없이_1회요청")
    void unprocessable422NotRetried() {
        // given — 422(파라미터 값 오류)도 비-일시적.
        retryRegistry = tripleAttemptIgnoringNonRetryable();
        server.enqueue(new MockResponse().setResponseCode(422));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        assertThatThrownBy(() -> client.submitDescribe(verifyReq("req-422"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(NonRetryableExternalException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답_unknown_필드_있어도_역직렬화_통과")
    void responseUnknownFieldsIgnored() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-u\",\"status\":\"accepted\",\"extraEvil\":\"<script>\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, 5L);

        VlmTimeseriesResponse resp = client.submitDescribe(verifyReq("req-u"))
                .block(Duration.ofSeconds(2));
        assertThat(resp).isNotNull();
        assertThat(resp.requestId()).isEqualTo("req-u");
    }

    /**
     * ★ 뒤집힌 단언이다 — 구 테스트는 {@code isEnabled()} 가 토글 상태를 노출하는지 확인했다.
     *
     * <p>그 접근자는 위탁 단계가 "조용히 건너뛸지" 를 묻는 유일한 수단이었고, 토글 폐지(ADR-049)와
     * 함께 제거됐다. 접근자만 되살아나도 그 분기가 함께 되살아나므로 <b>부재 자체를 고정</b>한다.
     * (설정 키 축의 부재는 {@code VlmEnabledToggleRemovalTest} 가 별도로 고정한다.)
     */
    @Test
    @DisplayName("토글_상태를_노출하는_접근자가_없다_건너뛰기_분기_재유입_차단")
    void noToggleAccessorIsExposed() {
        assertThat(VlmClient.class.getDeclaredMethods())
                .as("건너뛰기 판정을 되묻는 접근자가 되살아나면 조용한 NO-OP 경로도 함께 돌아온다")
                .noneSatisfy(m -> assertThat(m.getName()).isEqualTo("isEnabled"));
    }

    @Test
    @DisplayName("로그_sanitize_개행_탭_문자_치환")
    void safeForLogReplacesControlChars() {
        assertThat(VlmClient.safeForLog("line1\nline2")).isEqualTo("line1_line2");
        assertThat(VlmClient.safeForLog("a\r\nb\tc")).isEqualTo("a__b_c");
        assertThat(VlmClient.safeForLog(null)).isEqualTo("null");
        assertThat(VlmClient.safeForLog("normal-id_123")).isEqualTo("normal-id_123");
    }
}
