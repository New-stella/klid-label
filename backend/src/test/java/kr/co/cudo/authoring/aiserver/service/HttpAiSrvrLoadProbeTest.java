package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 부하 조회 경로({@code /internal/load}) 파싱·실패 분류 검증. [@design ADR-057]
 *
 * <h3>이 시험이 지키는 것은 「관대함」이다</h3>
 * <p>상대가 슬롯을 늘리거나 필드를 더해도 우리가 깨지지 않아야 하고, 반대로 <b>모르는 값을
 * 추측해 채우지도</b> 않아야 한다. 그리고 <b>실패의 종류를 구분</b>해야 한다 — 「아직 부하를
 * 알리지 않는 구 버전」과 「응답이 없는 이상」은 조치가 다르다.
 */
class HttpAiSrvrLoadProbeTest {

    private static final LsAiSrvr NODE = node();

    @Test
    @DisplayName("실효부하는_처리중건수와_대기건수의_합이다")
    void 실효부하는_처리중건수와_대기건수의_합이다() {
        // given — 대기 12 + 처리중 1
        AiSrvrLoadProbe probe = probeReturning(HttpStatus.OK, """
                {"slots":{"batch":{"running":1,"queued":12,"oldest_wait_ms":2380},
                          "interactive":{"running":0,"queued":0,"oldest_wait_ms":0}},
                 "observed_at":"2026-09-01T04:12:33.123456+00:00"}
                """);

        // when
        AiSrvrLoadReport report = probe.probe(NODE);

        // then — ★대기만 보면 한가한 노드와 이미 하나를 잡고 있는 노드가 똑같이 보인다.
        assertThat(report.outcome()).isEqualTo(AiSrvrLoadReport.Outcome.REPORTED);
        assertThat(report.slots().get(AiSrvrUsageType.BATCH).effectiveLoad()).isEqualTo(13);
        assertThat(report.slots().get(AiSrvrUsageType.INTERACTIVE).effectiveLoad()).isZero();
    }

    @Test
    @DisplayName("모르는_슬롯_키가_와도_아는_것만_반영하고_깨지지_않는다")
    void 모르는_슬롯_키가_와도_아는_것만_반영하고_깨지지_않는다() {
        // given — 상대가 셋째 슬롯을 늘렸다(알려주기로 했지만 우리 배포가 늦을 수 있다)
        AiSrvrLoadProbe probe = probeReturning(HttpStatus.OK, """
                {"slots":{"batch":{"running":0,"queued":3},
                          "training":{"running":1,"queued":99},
                          "interactive":{"running":1,"queued":0}}}
                """);

        // when
        AiSrvrLoadReport report = probe.probe(NODE);

        // then — 아는 둘만 반영한다. 모르는 슬롯 때문에 조회 전체를 실패로 만들지 않는다.
        assertThat(report.outcome()).isEqualTo(AiSrvrLoadReport.Outcome.REPORTED);
        assertThat(report.slots()).containsOnlyKeys(AiSrvrUsageType.BATCH, AiSrvrUsageType.INTERACTIVE);
        assertThat(report.slots().get(AiSrvrUsageType.BATCH).effectiveLoad()).isEqualTo(3);
    }

    @Test
    @DisplayName("한쪽_슬롯만_와도_그_슬롯만_반영한다")
    void 한쪽_슬롯만_와도_그_슬롯만_반영한다() {
        AiSrvrLoadReport report = probeReturning(HttpStatus.OK,
                "{\"slots\":{\"batch\":{\"running\":1,\"queued\":1}}}").probe(NODE);

        assertThat(report.slots()).containsOnlyKeys(AiSrvrUsageType.BATCH);
    }

    @Test
    @DisplayName("없는_경로라고_답하면_부하미보고로_분류한다")
    void 없는_경로라고_답하면_부하미보고로_분류한다() {
        // given — 되돌리기·배포 순서 어긋남으로 구 버전 노드와 신 버전 WAS 가 겹치는 구간
        AiSrvrLoadProbe probe = probeReturning(HttpStatus.NOT_FOUND, "");

        // when
        AiSrvrLoadReport report = probe.probe(NODE);

        // then — ★이것은 「장비에 이상이 있다」가 아니라 「아직 부하를 알리지 않는다」다.
        assertThat(report.outcome()).isEqualTo(AiSrvrLoadReport.Outcome.NOT_REPORTING);
        assertThat(report.slots()).isEmpty();
    }

    @Test
    @DisplayName("부하조회가_타임아웃되면_부하를_알_수_없음으로_둔다")
    void 부하조회가_타임아웃되면_부하를_알_수_없음으로_둔다() {
        // given — 응답이 상한 안에 오지 않는다
        AiSrvrLoadProbe probe = probe(request -> Mono.delay(Duration.ofSeconds(5))
                .map(ignored -> jsonResponse(HttpStatus.OK, "{\"slots\":{}}")));

        // when
        AiSrvrLoadReport report = probe.probe(NODE);

        // then — ★느린 것을 「포화」로 읽지 않는다. 모른다고 두고 직전 값을 유지한다.
        assertThat(report.outcome()).isEqualTo(AiSrvrLoadReport.Outcome.UNKNOWN);
        assertThat(report.slots()).isEmpty();
    }

    @Test
    @DisplayName("응답본문을_해석하지_못하면_부하를_알_수_없음으로_둔다")
    void 응답본문을_해석하지_못하면_부하를_알_수_없음으로_둔다() {
        // given — 리버스 프록시가 200 과 함께 HTML 오류 페이지를 준 형상
        AiSrvrLoadProbe probe = probe(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.TEXT_HTML_VALUE)
                .body("<html>gateway</html>")
                .build()));

        // when · then — 값을 지어내지 않는다
        assertThat(probe.probe(NODE).outcome()).isEqualTo(AiSrvrLoadReport.Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("음수_건수가_와도_0으로_눌러_담는다")
    void 음수_건수가_와도_0으로_눌러_담는다() {
        // given — 상대가 잠그지 않고 세는 근사값이라 순간적으로 음수가 나올 수 있다
        AiSrvrLoadReport report = probeReturning(HttpStatus.OK,
                "{\"slots\":{\"batch\":{\"running\":-1,\"queued\":-5}}}").probe(NODE);

        // then — 음수 부하는 「가장 한가한 노드」가 되어 요청을 빨아들인다. 컬럼 제약도 음수를 막는다.
        assertThat(report.slots().get(AiSrvrUsageType.BATCH).effectiveLoad()).isZero();
    }

    @Test
    @DisplayName("건수가_하나라도_비어있으면_그_슬롯은_값없음으로_읽는다")
    void 건수가_하나라도_비어있으면_그_슬롯은_값없음으로_읽는다() {
        // given — ★부하 경로 롤아웃 중 부분 구현된 노드가 실제로 이 모양을 보낸다.
        //        처리중이 빠졌을 뿐 대기 3이 실재하는데, 빠진 축을 0으로 채우면 원장에
        //        「처리중 0」이 확정값으로 남아 그 노드가 가장 한가해 보인다.
        AiSrvrLoadReport report = probeReturning(HttpStatus.OK,
                "{\"slots\":{\"batch\":{\"queued\":3}}}").probe(NODE);

        // then — 슬롯 객체가 통째로 빠졌을 때와 <같은 취급>이다. 저장하지 않고 직전 값을 유지한다.
        assertThat(report.slots()).doesNotContainKey(AiSrvrUsageType.BATCH);
        assertThat(report.hasValues()).isFalse();
    }

    @Test
    @DisplayName("두_건수가_모두_비어있어도_0으로_채우지_않는다")
    void 두_건수가_모두_비어있어도_0으로_채우지_않는다() {
        AiSrvrLoadReport report = probeReturning(HttpStatus.OK,
                "{\"slots\":{\"batch\":{}}}").probe(NODE);

        // then — 「모른다」를 「한가하다」로 바꾸지 않는다.
        assertThat(report.slots()).isEmpty();
        assertThat(report.hasValues()).isFalse();
    }

    @Test
    @DisplayName("한_슬롯이_불완전해도_온전한_다른_슬롯은_그대로_반영한다")
    void 한_슬롯이_불완전해도_온전한_다른_슬롯은_그대로_반영한다() {
        // given — 값 없음 처리가 조회 전체를 버리는 것으로 번지면 안 된다(관대함은 유지).
        AiSrvrLoadReport report = probeReturning(HttpStatus.OK, """
                {"slots":{"batch":{"queued":3},
                          "interactive":{"running":1,"queued":2}}}
                """).probe(NODE);

        assertThat(report.slots()).containsOnlyKeys(AiSrvrUsageType.INTERACTIVE);
        assertThat(report.slots().get(AiSrvrUsageType.INTERACTIVE).effectiveLoad()).isEqualTo(3);
        assertThat(report.hasValues()).isTrue();
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static AiSrvrLoadProbe probeReturning(HttpStatus status, String body) {
        return probe(request -> Mono.just(jsonResponse(status, body)));
    }

    private static AiSrvrLoadProbe probe(ExchangeFunction exchange) {
        return new HttpAiSrvrLoadProbe(WebClient.builder().exchangeFunction(exchange), 200L);
    }

    private static ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static LsAiSrvr node() {
        return LsAiSrvr.register("gpu01", null, "http://ai-1:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now());
    }
}
