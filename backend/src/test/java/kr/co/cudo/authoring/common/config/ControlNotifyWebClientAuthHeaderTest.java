package kr.co.cudo.authoring.common.config;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-ISSUE-62 (HIGH) 회귀 가드 — <b>관제 outbound 통지에 인증 헤더({@code x-access-token})가 부착된다</b>.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * {@code controlNotifyWebClient} 는 base-url 만 설정하고 인증 헤더를 붙이지 않았다. 로컬 목 서버가 인증을
 * 검사하지 않아 202 로 통과했기 때문에(로컬 통과가 실환경 계약을 보증하지 못하는 전형) 결함이 드러나지
 * 않았고, 실서버 연동 시 전 통지가 401 로 거부되어 폴백 큐가 재시도 상한을 소진한 뒤 dead-letter 로
 * 고착될 상태였다(관제 동기화 전면 중단). 실왕복(MockWebServer)으로 헤더를 고정한다.
 */
class ControlNotifyWebClientAuthHeaderTest {

    private MockWebServer server;
    private final WebClientConfig config = new WebClientConfig();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /** 빈이 만든 WebClient 로 실제 1회 POST 하고 서버가 받은 요청을 돌려준다. */
    private RecordedRequest post(WebClient client) throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(202));
        client.post().uri("/api/data-set/v2/jobs/1/notify-completed")
                .bodyValue("{}")
                .retrieve()
                .toBodilessEntity()
                .block();
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("토큰이_설정되면_x-access-token_헤더가_실제_요청에_부착된다 (D-ISSUE-62)")
    void attachesAccessTokenHeader() throws Exception {
        // given — 설정(환경변수)에서 주입된 토큰. 코드/yml 평문 상수가 아니다(CWE-798).
        WebClient client = config.controlNotifyWebClient(
                server.url("/").toString(), "s3cr3t-control-token", true, null);

        // when
        RecordedRequest recorded = post(client);

        // then — 관제 SPI 계약(API-251/API-285)이 요구하는 인증 헤더가 실제로 전송된다.
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader("x-access-token")).isEqualTo("s3cr3t-control-token");
    }

    @Test
    @DisplayName("토큰_앞뒤_공백은_제거되어_부착된다 — 환경변수 개행/공백 혼입 방어")
    void trimsToken() throws Exception {
        WebClient client = config.controlNotifyWebClient(
                server.url("/").toString(), "  padded-token  ", true, null);

        RecordedRequest recorded = post(client);

        assertThat(recorded.getHeader("x-access-token")).isEqualTo("padded-token");
    }

    @Test
    @DisplayName("토큰이_비어있으면_헤더를_부착하지_않는다 — 인증 미요구 환경(local 목서버) 동작 보존")
    void omitsHeaderWhenTokenBlank() throws Exception {
        WebClient client = config.controlNotifyWebClient(server.url("/").toString(), "", false, null);

        RecordedRequest recorded = post(client);

        assertThat(recorded.getHeader("x-access-token")).isNull();
    }

    @Test
    @DisplayName("토큰이_null이어도_기동에_실패하지_않고_헤더만_생략한다")
    void omitsHeaderWhenTokenNull() throws Exception {
        WebClient client = config.controlNotifyWebClient(server.url("/").toString(), null, true, null);

        RecordedRequest recorded = post(client);

        assertThat(recorded.getHeader("x-access-token")).isNull();
    }
}
