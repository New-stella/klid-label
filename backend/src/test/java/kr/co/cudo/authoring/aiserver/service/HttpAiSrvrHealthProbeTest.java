package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmServerStatus;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 상태점검 창구가 <b>계통마다 갈린다</b>. [@design ADR-057] [@design INTSPEC-003]
 *
 * <p>여기서 고정하는 것은 둘이다 — ①어느 창구를 두드리는가 ②「살아 있다」의 기준이 계통마다 다르다.
 * 후자는 오판의 대가가 다르기 때문이다: 시계열은 후보가 0이 되면 위탁이 폴백 없이 거부되므로
 * 멀쩡한 <b>외부 벤더</b>를 4xx 하나로 내리면 그 계통이 통째로 멈춘다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpAiSrvrHealthProbeTest {

    @Mock private VlmClient vlmClient;

    private MockWebServer server;
    private HttpAiSrvrHealthProbe probe;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 10, 0);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        probe = new HttpAiSrvrHealthProbe(WebClient.builder(), vlmClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // --- 추론 계통 -------------------------------------------------------------------------------

    @Test
    @DisplayName("추론_노드는_우리_서버의_상태_창구를_두드린다")
    void 추론_노드는_우리_서버의_상태_창구를_두드린다() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        assertThat(probe.ping(inference())).isTrue();

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo(HttpAiSrvrHealthProbe.HEALTH_PATH);
        // 시계열 창구를 대신 쓰지 않는다.
        verifyNoInteractions(vlmClient);
    }

    @Test
    @DisplayName("추론_노드는_2xx가_아니면_죽은_것으로_본다")
    void 추론_노드는_2xx가_아니면_죽은_것으로_본다() {
        // 우리 계약이 보장하는 경로라, 2xx 가 아니면 그 노드는 추론도 받지 못한다.
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThat(probe.ping(inference())).isFalse();
    }

    // --- 시계열 계통 -----------------------------------------------------------------------------

    @Test
    @DisplayName("★시계열_노드는_벤더_규격의_상태_창구를_그_장비_주소로_두드린다")
    void 시계열_노드는_벤더_규격의_상태_창구를_그_장비_주소로_두드린다() {
        given(vlmClient.fetchStatus(anyString(), eq(true)))
                .willReturn(Mono.just(new VlmServerStatus("ok", null, null)));

        assertThat(probe.ping(timeseries())).isTrue();

        // ★장비별 주소로 물어야 한다 — 설정 base 한 곳만 찌르면 두 장비의 상태가 같은 값이 된다.
        // 주기 점검 표식(true)과 함께 부른다 — 성공 호출 로그가 DEBUG 로 낮아진다(NFR-038).
        verify(vlmClient).fetchStatus("https://vendor.example", true);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("★시계열_노드는_4xx를_받아도_살아_있는_것으로_본다_추론과_기준이_다르다")
    void 시계열_노드는_4xx를_받아도_살아_있는_것으로_본다() {
        // 외부 벤더의 인증 정책·응답 형식 변화는 <벤더가 죽었다는 뜻이 아니고> 우리가 고칠 수도 없다.
        // 규격에 없는 경로를 핑해 403 을 받고 헬스가 상시 DOWN 이 된 사고의 교훈과 같은 축이다.
        given(vlmClient.fetchStatus(anyString(), eq(true))).willReturn(Mono.error(
                WebClientResponseException.create(403, "Forbidden",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null)));

        assertThat(probe.ping(timeseries())).isTrue();
    }

    @Test
    @DisplayName("★벤더가_준비중이라_처리할_수_없다고_밝히면_죽은_것으로_본다")
    void 벤더가_준비중이라_처리할_수_없다고_밝히면_죽은_것으로_본다() {
        // ★「살아 있는가」와 「일을 받을 수 있는가」는 다른 축이다. 원장의 가용/이용불가가 재는 것은
        //   뒤의 축이므로, 벤더가 스스로 못 받는다고 밝힌 장비는 응답을 돌려줘도 후보로 남으면 안 된다.
        //   (위탁 직전 관측은 「게이트 아님」이 확정 사양이라 경고만 남기고 그대로 보낸다.)
        given(vlmClient.fetchStatus(anyString(), eq(true)))
                .willReturn(Mono.just(new VlmServerStatus(VlmServerStatus.LOADING, 0, 0)));

        assertThat(probe.ping(timeseries())).isFalse();
    }

    @Test
    @DisplayName("★접수는_되나_결과가_지연된다는_응답은_살아_있는_것으로_본다_그건_부하_축이다")
    void 접수는_되나_결과가_지연된다는_응답은_살아_있는_것으로_본다() {
        // 부하로 장비를 내리면 포화를 이유로 멀쩡한 장비가 배제되고 남은 한 대에 전부 몰린다 —
        // 「살아 있는지와 여유가 있는지를 한 값에 엉키게 하지 않는다」가 애초에 막으려던 역효과다.
        given(vlmClient.fetchStatus(anyString(), eq(true)))
                .willReturn(Mono.just(new VlmServerStatus(VlmServerStatus.BUSY, 12, 3)));

        assertThat(probe.ping(timeseries())).isTrue();
    }

    @Test
    @DisplayName("★200인데_본문_형식이_예상_밖이어도_살아_있는_것으로_본다_도달했으므로")
    void 응답_형식이_예상_밖이어도_살아_있는_것으로_본다() {
        // 프록시 오류 페이지·벤더 규격 드리프트 — 구 코드는 이것이 마지막 포괄 catch 로 떨어져
        // <죽음>으로 세어졌다. 응답이 도달했는데 실패로 센 것이며, 그 오판의 대가는 계통 정지다.
        given(vlmClient.fetchStatus(anyString(), eq(true))).willReturn(Mono.error(
                new DecodingException("JSON decoding error", new java.io.IOException("unexpected token"))));

        assertThat(probe.ping(timeseries())).isTrue();
    }

    @Test
    @DisplayName("★시계열_노드도_전송이_실패하면_죽은_것으로_본다")
    void 시계열_노드도_전송이_실패하면_죽은_것으로_본다() {
        // 연결 거부·타임아웃은 벤더 정책과 무관하게 「닿지 않는다」이며 우리가 알아야 할 사실이다.
        given(vlmClient.fetchStatus(anyString(), eq(true)))
                .willReturn(Mono.error(new java.net.ConnectException("refused")));

        assertThat(probe.ping(timeseries())).isFalse();
    }

    @Test
    @DisplayName("시계열_상태_조회가_시간_안에_돌아오지_않으면_죽은_것으로_본다")
    void 시계열_상태_조회가_시간_안에_돌아오지_않으면_죽은_것으로_본다() {
        // ★전송 계층 실패는 <원인 사슬 안쪽>에 있다(block 이 검사 예외를 리액터 예외로 감싼다) —
        //   겉 예외 타입만 보면 「알 수 없음 → 생존」으로 새어 죽은 벤더가 가용으로 남는다.
        given(vlmClient.fetchStatus(anyString(), eq(true)))
                .willReturn(Mono.error(new java.util.concurrent.TimeoutException("2s")));

        assertThat(probe.ping(timeseries())).isFalse();
    }

    @Test
    @DisplayName("시계열_노드_주소로_목적지를_만들_수_없으면_죽은_것으로_본다")
    void 시계열_노드_주소로_목적지를_만들_수_없으면_죽은_것으로_본다() {
        given(vlmClient.fetchStatus(anyString(), eq(true)))
                .willThrow(new NonRetryableExternalException("목적지를 만들 수 없습니다."));

        assertThat(probe.ping(timeseries())).isFalse();
    }

    /**
     * 추론 노드 — MockWebServer 주소를 그대로 쓴다(포트가 매번 다르다).
     *
     * <p>★인스턴스 메서드다. {@code static} 필드에 주소를 담고 별도 {@code @BeforeEach} 로 채우면
     * <b>JUnit 5 가 여러 {@code @BeforeEach} 의 실행 순서를 보장하지 않아</b> 아직 비어 있는 값을
     * 읽을 수 있다.
     */
    private LsAiSrvr inference() {
        return LsAiSrvr.register("gpu-01", null, server.url("/").toString(),
                LsAiSrvr.SrvrType.INFERENCE, NOW);
    }

    private static LsAiSrvr timeseries() {
        return LsAiSrvr.register("vendor-1", null, "https://vendor.example",
                LsAiSrvr.SrvrType.TIMESERIES, NOW);
    }
}
