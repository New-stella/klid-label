package kr.co.cudo.authoring.augment.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.security.HmacSigner;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 콜백 충실 플로우 Phase 2 — dev 콜백 시뮬레이터 단위 테스트.
 *
 * <p>WebClient fluent 체인을 mock 으로 가로채 실제로 전송되는 본문/헤더를 캡처하고,
 * 그 값이 (1) status=SUCCESS·originAugSn 페이로드, (2) HmacWebhookFilter 검증을 통과하는
 * 서명, (3) 서명 대상 본문 == 전송 본문(바이트 동일) 임을 단언한다.
 */
class DevAugmentCallbackSimulatorTest {

    /** 테스트용 더미 시크릿(평문 아님 — 런타임 조립으로 32B 이상 구성). 운영 시크릿과 무관. */
    private static final String SECRET = ("augment-test-dummy-" + "x".repeat(20));

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebClient webClient;
    @SuppressWarnings("rawtypes")
    private WebClient.RequestBodyUriSpec uriSpec;
    @SuppressWarnings("rawtypes")
    private WebClient.RequestBodySpec bodySpec;
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec headersSpec;
    private WebClient.ResponseSpec responseSpec;

    private DevAugmentCallbackSimulator simulator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webClient = mock(WebClient.class);
        uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        // header(name, value...) 는 RequestBodySpec 으로 체이닝 (self type)
        lenient().when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        lenient().when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(reactor.core.publisher.Mono.empty());

        simulator = new DevAugmentCallbackSimulator(webClient, objectMapper, SECRET);
    }

    @Test
    @DisplayName("시뮬이_콜백_페이로드를_status_SUCCESS와_originAugSn으로_구성한다")
    void buildsPayloadWithSuccessStatusAndOriginAugSn() throws Exception {
        // given
        Long originAugSn = 42L;

        // when
        simulator.requestAugment(originAugSn, "WINTER", "key-001", "job-001",
                "http://localhost:8080/api/v1/aug/callback");

        // then
        byte[] sentBody = captureSentBody();
        AugmentResultRequest decoded = objectMapper.readValue(sentBody, AugmentResultRequest.class);
        assertThat(decoded.augProcStsCd()).isEqualTo("SUCCESS");
        assertThat(decoded.dataAugSn()).isEqualTo(42L);
        assertThat(decoded.augTypeCd()).isEqualTo("WINTER");
        // 외부 시스템이 콜백 시점 otsd_job_id 를 부여하는 것을 시뮬(= 요청 시 발급한 externalJobId echo)
        assertThat(decoded.otsdJobId()).isEqualTo("job-001");
    }

    @Test
    @DisplayName("시뮬이_HmacSigner로_올바른_X_Signature를_생성한다")
    void generatesSignatureThatPassesFilterRule() throws Exception {
        // when
        simulator.requestAugment(7L, "RAIN", "key-xyz", "job-xyz",
                "http://localhost:8080/api/v1/aug/callback");

        // then — 캡처한 timestamp·body 로 재계산한 서명이 전송된 서명과 일치(필터 검증 규칙과 동일)
        String sentSignature = captureHeader(HmacWebhookFilter.SIGNATURE_HEADER);
        String sentTimestamp = captureHeader(HmacWebhookFilter.TIMESTAMP_HEADER);
        byte[] sentBody = captureSentBody();

        String canonical = sentTimestamp + "." + new String(sentBody, StandardCharsets.UTF_8);
        String expected = HmacWebhookFilter.SIGNATURE_PREFIX + HmacSigner.hex(SECRET, canonical);
        assertThat(sentSignature).isEqualTo(expected);
        assertThat(sentSignature).startsWith(HmacWebhookFilter.SIGNATURE_PREFIX);
    }

    @Test
    @DisplayName("서명대상_본문과_전송_본문이_바이트_동일하다")
    void signedBodyEqualsSentBodyByteForByte() throws Exception {
        // when
        simulator.requestAugment(99L, "NIGHT", "key-eq", "job-eq",
                "http://localhost:8080/api/v1/aug/callback");

        // then — 전송된 timestamp+body 로 계산한 서명이 전송 서명과 일치하면, 서명 대상 == 전송 본문 동일성 보장
        String sentSignature = captureHeader(HmacWebhookFilter.SIGNATURE_HEADER);
        String sentTimestamp = captureHeader(HmacWebhookFilter.TIMESTAMP_HEADER);
        byte[] sentBody = captureSentBody();

        // HmacWebhookFilter 검증부와 동일하게 raw body bytes → UTF-8 문자열로 canonical 구성
        String canonical = sentTimestamp + "." + new String(sentBody, StandardCharsets.UTF_8);
        String recomputed = HmacWebhookFilter.SIGNATURE_PREFIX + HmacSigner.hex(SECRET, canonical);
        assertThat(recomputed)
                .as("전송 본문 raw bytes 로 재계산한 서명이 전송 서명과 같아야 함 (바이트 동일)")
                .isEqualTo(sentSignature);
    }

    @Test
    @DisplayName("콜백_HTTP_실패시_예외를_삼키고_false를_반환한다")
    void swallowsHttpFailureAndReturnsFalse() {
        // given — retrieve 단계에서 예외 발생하도록 재설정
        when(headersSpec.retrieve()).thenThrow(new RuntimeException("connection refused"));

        // when — 예외가 전파되지 않아야 함
        boolean result = simulator.requestAugment(1L, "WINTER", "k", "j",
                "http://localhost:8080/api/v1/aug/callback");

        // then
        assertThat(result).isFalse();
    }

    // ===== helpers =====

    private byte[] captureSentBody() {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(bodySpec).bodyValue(captor.capture());
        return captor.getValue();
    }

    private String captureHeader(String name) {
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> valueCaptor = ArgumentCaptor.forClass(String[].class);
        org.mockito.Mockito.verify(bodySpec, org.mockito.Mockito.atLeastOnce())
                .header(nameCaptor.capture(), valueCaptor.capture());
        java.util.List<String> names = nameCaptor.getAllValues();
        java.util.List<String[]> values = valueCaptor.getAllValues();
        for (int i = 0; i < names.size(); i++) {
            if (name.equals(names.get(i))) {
                return values.get(i)[0];
            }
        }
        throw new AssertionError("header not sent: " + name);
    }
}
