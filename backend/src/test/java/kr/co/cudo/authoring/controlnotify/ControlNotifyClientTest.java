package kr.co.cudo.authoring.controlnotify;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.common.client.ControlNotifyStatusException;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ControlNotifyClient 단위 테스트 (MockWebServer).
 *
 * <p>관제 계약(API-251 / API-285) 정합 검증:
 * <ul>
 *   <li>통지 종류별 경로 분리 — {@code notify-completed} / {@code notify-updated}</li>
 *   <li>6필드 평면 snake_case 바디 (구 2단 중첩 폐기)</li>
 *   <li>4xx 상태코드 보존 — 409/404 자기치유가 가능해야 한다(S4·S5)</li>
 * </ul>
 */
class ControlNotifyClientTest {

    private MockWebServer mockServer;
    private ControlNotifyClient client;
    private CircuitBreaker circuitBreaker;

    private static final TaskCompletedPayload COMPLETED = new TaskCompletedPayload(
            "100", "FIRE", "11680", "서울특별시 강남구", 30, 16);

    private static final TaskModifiedPayload MODIFIED = new TaskModifiedPayload(
            "200", new TaskModifiedPayload.ChangedItems(List.of(), List.of("0007.json")));

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                // 운영 설정(application.yml)과 동일하게 4xx 는 서킷 failure 집계에서 제외한다.
                .ignoreExceptions(NonRetryableExternalException.class)
                .build();
        circuitBreaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("controlNotify");

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                // ★ 운영 설정과 동일 — ignore-exceptions 는 "재시도 억제" 일 뿐 "예외 삼킴" 이 아니다.
                .ignoreExceptions(NonRetryableExternalException.class)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);

        client = new ControlNotifyClient(webClient, circuitBreaker, retryRegistry);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("완료통지가_notify_completed_경로로_6필드_평면_바디를_보낸다")
    void sendCompleted_usesContractPathAndFlatSnakeCaseBody() throws InterruptedException {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(201));

        // when
        client.sendTaskCompleted(COMPLETED).block(ControlNotifyClient.BLOCK_TIMEOUT);

        // then — 경로가 job_id 를 포함한 계약 경로여야 한다.
        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/data-set/v2/jobs/100/notify-completed");

        // then — 바디는 snake_case 평면 6필드. 구 2단 중첩(payload/eventType)은 없어야 한다.
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"job_id\":\"100\"");
        assertThat(body).contains("\"event_type_cd\":\"FIRE\"");
        assertThat(body).contains("\"lclgv_cd\":\"11680\"");
        assertThat(body).contains("\"lclgv_nm\":\"서울특별시 강남구\"");
        assertThat(body).contains("\"duration_sec\":30");
        assertThat(body).contains("\"image_count\":16");
        assertThat(body).doesNotContain("\"payload\"");
        assertThat(body).doesNotContain("eventType");
        assertThat(body).doesNotContain("rawSn");
    }

    @Test
    @DisplayName("재승인은_notify_updated_경로로_changed_items_를_보낸다")
    void sendModified_usesUpdatedPathAndChangedItems() throws InterruptedException {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        // when
        client.sendTaskModified(MODIFIED).block(ControlNotifyClient.BLOCK_TIMEOUT);

        // then
        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/data-set/v2/jobs/200/notify-updated");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"job_id\":\"200\"");
        assertThat(body).contains("\"changed_items\"");
        assertThat(body).contains("\"images\":[]");
        assertThat(body).contains("\"jsons\":[\"0007.json\"]");
        assertThat(body).doesNotContain("changedItems");
    }

    @Test
    @DisplayName("202_Accepted_응답도_성공으로_수용된다")
    void accepts202() {
        // given — 관제가 비동기 수신을 202 로 응답해도 실패로 보지 않는다.
        mockServer.enqueue(new MockResponse().setResponseCode(202));

        // when / then — 예외 없이 완료
        client.sendTaskCompleted(COMPLETED).block(ControlNotifyClient.BLOCK_TIMEOUT);
        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("409_응답이_재시도되지_않고_데드레터로_쌓이지_않는다")
    void conflictIsNotRetriedAndPreservesStatus() throws InterruptedException {
        // given — 409 1건만 큐잉. 재시도가 일어나면 두 번째 요청이 들어온다.
        mockServer.enqueue(new MockResponse().setResponseCode(409)
                .setBody("{\"message\":\"duplicated job_id\"}"));

        // when / then — 예외는 삼켜지지 않고 catch 가능한 형태로 전파되어야 한다(자기치유 전제).
        assertThatThrownBy(() -> client.sendTaskCompleted(COMPLETED).block(ControlNotifyClient.BLOCK_TIMEOUT))
                .isInstanceOf(ControlNotifyStatusException.class)
                .satisfies(e -> {
                    ControlNotifyStatusException se = (ControlNotifyStatusException) e;
                    assertThat(se.statusCode()).isEqualTo(409);
                    assertThat(se.isConflict()).isTrue();
                    assertThat(se.bodySummary()).contains("duplicated job_id");
                });

        // then — 첫 요청만 존재(재시도 0회) → 폴백 큐 재시도/데드레터 증식 없음
        assertThat(mockServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(mockServer.takeRequest(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("ignore_exceptions_설정에도_409_예외가_catch_까지_전파되어_자기치유가_발동한다")
    void ignoreExceptionsDoesNotSwallowStatusException() {
        // given — retry·circuitbreaker 모두 NonRetryableExternalException 을 ignore 로 설정한 상태.
        mockServer.enqueue(new MockResponse().setResponseCode(409));

        // when
        Throwable thrown = catchThrowable(
                () -> client.sendTaskCompleted(COMPLETED).block(ControlNotifyClient.BLOCK_TIMEOUT));

        // then — ignore-exceptions 는 "재시도 억제" 일 뿐 예외를 삼키지 않는다.
        assertThat(thrown)
                .as("ignore-exceptions 가 예외를 삼키면 409 자기치유 분기가 영원히 발동하지 않는다")
                .isInstanceOf(ControlNotifyStatusException.class);
        // 서킷도 4xx 를 failure 로 세지 않는다.
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("422_도_재시도되지_않고_상태코드와_바디가_보존된다")
    void unprocessableEntityIsNotRetried() throws InterruptedException {
        // given — 422(파라미터 오류)는 재전송해도 결과가 같은 결정적 실패다. 재시도하면 폴백 큐 재시도
        //         횟수만 소모되어 dead-letter 증식이 빨라진다.
        mockServer.enqueue(new MockResponse().setResponseCode(422)
                .setBody("{\"message\":\"invalid event_type_cd\"}"));

        // when / then
        assertThatThrownBy(() -> client.sendTaskCompleted(COMPLETED).block(ControlNotifyClient.BLOCK_TIMEOUT))
                .isInstanceOf(ControlNotifyStatusException.class)
                .satisfies(e -> {
                    ControlNotifyStatusException se = (ControlNotifyStatusException) e;
                    // .toBodilessEntity() 로는 불가능했던 "상태코드 + 바디" 파싱이 실제로 된다(S4).
                    assertThat(se.statusCode()).isEqualTo(422);
                    assertThat(se.isConflict()).isFalse();
                    assertThat(se.isNotFound()).isFalse();
                    assertThat(se.bodySummary()).contains("invalid event_type_cd");
                });

        // then — 재시도 0회 + 서킷 failure 미집계
        assertThat(mockServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(mockServer.takeRequest(1, TimeUnit.SECONDS)).isNull();
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("404_응답이_notFound_예외로_전파된다")
    void notFoundIsPropagated() {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(404));

        // when / then
        assertThatThrownBy(() -> client.sendTaskModified(MODIFIED).block(ControlNotifyClient.BLOCK_TIMEOUT))
                .isInstanceOf(ControlNotifyStatusException.class)
                .satisfies(e -> assertThat(((ControlNotifyStatusException) e).isNotFound()).isTrue());
    }

    @Test
    @DisplayName("관제서버_500_응답시_Retry_3회_후_실패")
    void retryOnServerError() {
        // given -- 5xx 는 일시적 실패이므로 재시도 대상이다.
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        // when / then
        assertThatThrownBy(() ->
                client.sendTaskCompleted(COMPLETED).block(ControlNotifyClient.BLOCK_TIMEOUT))
                .isNotNull();

        // 3회 요청 확인
        assertThat(mockServer.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("관제서버_타임아웃시_CircuitBreaker_동작")
    void circuitBreakerOpensOnFailures() {
        // given -- CircuitBreaker를 OPEN 상태로 강제 전이
        circuitBreaker.transitionToOpenState();

        // when / then -- OPEN 상태에서는 CallNotPermittedException 발생
        assertThatThrownBy(() ->
                client.sendTaskCompleted(COMPLETED).block(ControlNotifyClient.BLOCK_TIMEOUT))
                .isNotNull();

        // 요청이 서버에 도달하지 않아야 함
        assertThat(mockServer.getRequestCount()).isZero();
    }
}
