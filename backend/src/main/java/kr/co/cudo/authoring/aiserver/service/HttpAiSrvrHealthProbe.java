package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmServerStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.CodecException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 노드 상태 점검을 HTTP 로 수행하는 구현 — <b>창구가 계통마다 다르다</b>. [@design ADR-057]
 *
 * <h3>★ 창구를 가르는 <b>단일 지점</b>이 여기다 [@design ADR-057] [@design INTSPEC-003]</h3>
 * <table>
 *   <caption>계통별 상태점검 창구와 판정 기준</caption>
 *   <tr><th>계통</th><th>창구</th><th>「살아 있다」의 기준</th></tr>
 *   <tr><td>추론(INFERENCE)</td><td>우리가 배포한 서버의 {@value #HEALTH_PATH}</td>
 *       <td><b>2xx 만</b></td></tr>
 *   <tr><td>시계열(TIMESERIES)</td><td>외부 벤더 규격이 정한 상태 조회 창구
 *       ({@link VlmClient#fetchStatus(String)})</td><td><b>응답이 도달했고, 그 응답이 처리 불가를
 *       밝히지 않았을 때</b></td></tr>
 * </table>
 * <p>판정을 호출부에 복제하지 않는다 — 폴러는 「이 노드를 점검하라」고만 하고 <b>어디를 어떻게
 * 두드릴지는 전부 이 클래스가 정한다</b>. 경로 문자열도 각 계통의 소유자에게서 조달한다(시계열
 * 경로는 {@code VlmClient} 가 갖고 있고 여기 옮겨 적지 않는다).
 *
 * <h3>★ 왜 기준이 계통마다 다른가 (「일관성」을 이유로 통일하지 말 것)</h3>
 * <p><b>추론은 우리가 배포한 서버</b>다. {@value #HEALTH_PATH} 는 우리 계약이 보장하는 경로이므로
 * 그것이 2xx 가 아니라는 것은 그 노드가 추론도 받지 못한다는 뜻이고, 틀렸으면 우리가 고칠 수 있다.
 *
 * <p><b>시계열은 외부 벤더의 서비스</b>다. 그쪽 상태 창구가 인증 정책·응답 형식을 바꿔 4xx 나
 * 해석 불가 본문을 돌려주는 것은 <b>벤더가 죽었다는 뜻이 아니고 우리가 고칠 수도 없다</b>. 그런데
 * 이 축은 오판의 대가가 훨씬 크다 — 시계열은 후보가 0이 되면 위탁이 <b>폴백 없이 거부</b>되므로
 * 멀쩡한 벤더를 내리면 그 계통이 통째로 멈춘다. 실제로 규격에 없는 경로를 핑해 403 을 받고 헬스가
 * 상시 DOWN 이 된 사고가 있었고, 그 교훈을 같은 창구를 쓰는 시계열 인디케이터가 이미 코드로 갖고
 * 있다(4xx·5xx·해석 실패도 UP). <b>같은 벤더·같은 창구이므로 같은 기준을 쓴다.</b>
 *
 * <p>⚠ 그래서 시계열에서 거짓이 되는 것은 <b>둘뿐</b>이다 — ①<b>전송 자체가 실패</b>했을 때(연결
 * 거부·타임아웃·이름 해석 실패)와 ②<b>벤더가 스스로 「준비 중이라 아직 처리할 수 없다」고 밝혔을 때</b>다.
 *
 * <h3>★★ 왜 ②가 거짓인가 — 「살아 있는가」와 「일을 받을 수 있는가」는 다른 축이다 [@design AC-1101]</h3>
 * <p>원장의 가용·이용불가가 재는 것은 <b>「지금 이 장비로 보내도 되는가」</b>이지 「프로세스가 응답하는가」가
 * 아니다. 두 개념을 섞으면 「살아 있으니 보내도 된다」가 되어, 벤더가 <b>스스로 못 받는다고 말하는데도</b>
 * 위탁이 그대로 나간다(위탁 직전 관측은 「게이트 아님」이 확정 사양이라 경고만 남기고 보낸다).
 *
 * <p>판정은 {@link kr.co.cudo.authoring.common.client.dto.VlmServerStatus#isSubmittable()} 에
 * <b>위임</b>한다 — 「지금 보내도 되나」를 판정하는 자리가 이미 있고, 여기서 상태 문자열을 다시 비교하면
 * 그 사본이 두 번째 진실원이 되어 벤더 값이 늘 때 한쪽만 바뀐다.
 *
 * <p>⚠ <b>「접수는 되나 결과 지연」은 거짓이 아니다.</b> 그것은 <b>부하</b>이고 부하는 이 결정이 상태점검과
 * 다른 축으로 갈라 둔 것이다. 그것으로 장비를 내리면 <b>포화를 이유로 멀쩡한 장비가 배제되고 남은 한 대에
 * 전부 몰리는</b> 역효과가 난다 — 「살아 있는지와 여유가 있는지를 한 값에 엉키게 하지 않는다」가 애초에
 * 막으려던 것이다. 일시적인 처리 불가는 <b>연속 실패 임계가 흡수</b>하므로 한 번의 관측으로 내려가지 않는다.
 *
 * <p>관대함의 근거는 그대로다 — 다만 그 관대함은 <b>우리가 해석해야만 알 수 있는 것</b>(오류 응답·해석
 * 불가 본문·예상 밖 형식)에만 적용되고 <b>벤더가 스스로 밝히는 것</b>에는 적용되지 않는다. 벤더가 지금은
 * 못 받는다고 말하는 것을 그대로 받아들이는 것은 오판이 아니라 <b>계약을 지키는 것</b>이다.
 *
 * <h3>★ 판정은 <b>거짓 쪽을 열거</b>한다 — 참 쪽을 열거하면 형식이 바뀔 때마다 죽는다</h3>
 * <p>구 코드는 「응답 도달」을 예외 타입으로 <b>열거</b>하고 나머지를 마지막 포괄 {@code catch} 에서
 * 거짓으로 삼았다. 그래서 <b>200 인데 본문이 우리가 기대한 형식이 아닌 경우</b>(프록시 오류 페이지 ·
 * 벤더 규격 드리프트)가 그 포괄 분기로 떨어져 <b>「죽음」으로 세어졌다</b> — 응답이 도달했는데 실패로 센 것이다.
 * 지금은 <b>전송 계층 실패와 벤더가 밝힌 처리 불가만</b> 거짓이고 그 밖은 전부 「닿았다」이므로, 벤더가
 * 응답 형식을 어떻게 바꿔도 판정이 흔들리지 않는다.
 *
 * <h3>「응답이 오면 살아 있다」를 <b>추론 축에서는</b> 쓰지 않는다</h3>
 * <p>추론은 이 결과가 노드를 목록에서 내리고 올리는데, {@code /health} 는 우리 계약이 보장하는
 * 경로이므로 그것이 2xx 가 아니라는 것은 그 노드가 추론도 받지 못한다는 뜻이다. 따라서
 * <b>2xx 만 정상</b>으로 본다.
 *
 * <p>⚠ <b>구 서술 정정(2026-09-08)</b> — 여기 <i>「시계열 인디케이터는 4xx·5xx 도 UP 으로 보지만
 * 여기는 판정의 무게가 다르다 … 두 규칙을 일관성을 이유로 통일하지 말 것 — 한쪽은 화면 표시이고
 * 다른 한쪽은 배제 판정이다」</i>라고 적혀 있었다. 그때는 <b>이 클래스가 추론만 점검</b>했으므로
 * 참이었으나, 이제 시계열도 여기서 점검한다. <b>가르는 축은 「화면 표시 ↔ 배제 판정」이 아니라
 * 「우리 서버 ↔ 외부 벤더」</b>다 — 위 §왜 기준이 계통마다 다른가. 두 규칙을 통일하지 말라는
 * <b>결론은 그대로</b>이며 근거만 바뀌었다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다</b> — 응답 없음은 {@code false} 다.
 */
@Slf4j
@Component
public class HttpAiSrvrHealthProbe implements AiSrvrHealthProbe {

    static final String HEALTH_PATH = "/health";

    /**
     * 핑 상한 — 형제 인디케이터와 같은 2초.
     *
     * <p>설정 키를 새로 만들지 않았다. 이 값이 늘어나면 <b>노드 수 x 상한</b>이 폴링 주기(기본 5초)를
     * 넘어 틱이 밀리므로, 운영자가 임의로 늘려도 되는 값이 아니다.
     */
    static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;
    private final VlmClient vlmClient;

    public HttpAiSrvrHealthProbe(WebClient.Builder webClientBuilder, VlmClient vlmClient) {
        // 노드마다 주소가 다르므로 baseUrl 을 고정하지 않는다(부하 조회와 같은 이유).
        this.webClient = webClientBuilder.build();
        this.vlmClient = vlmClient;
    }

    /**
     * 계통을 가른다 — <b>여기가 유일한 분기</b>다.
     *
     * <p>유형을 모르는 행(과거 데이터·마이그레이션 중)은 추론 창구로 본다. 그 값이 원장의 기본
     * 형상이고, 모르는 것을 「외부 벤더」로 가정하면 우리 서버를 벤더 기준으로 재게 된다.
     */
    @Override
    public boolean ping(LsAiSrvr server) {
        return server.getSrvrTypeCd() == LsAiSrvr.SrvrType.TIMESERIES
                ? pingTimeseries(server)
                : pingInference(server);
    }

    /**
     * 외부 시계열 분석 벤더 — <b>응답이 도달했고 그 응답이 처리 불가를 밝히지 않았으면</b> 살아 있다
     * (위 클래스 주석 §왜 기준이 다른가 · §왜 ②가 거짓인가). [@design AC-1101]
     *
     * <p>목적지 고정·경로·타임아웃 정책을 {@link VlmClient} 에게 맡긴다. 여기서 직접 조립하면
     * 「상태를 물을 때」와 「위탁할 때」의 주소 판정이 갈린다.
     */
    private boolean pingTimeseries(LsAiSrvr server) {
        VlmServerStatus status;
        try {
            // 주기 점검 표식 — 성공 호출 로그는 DEBUG 로 낮아진다(실패는 평소 레벨). [@design NFR-038]
            status = vlmClient.fetchStatus(server.getSrvrAddr(), true).timeout(PING_TIMEOUT).block();
        } catch (NonRetryableExternalException unpinnable) {
            // ★거짓이지만 <벤더 상태가 아니다> — 주소로 목적지를 만들 수 없다는 <우리 원장>의 결손이다.
            //   그래도 「닿지 않는다」는 사실은 같으므로 거짓으로 둔다(고를 때도 같은 술어가 이 행을
            //   거른다). 이 구분을 지우면 운영자가 벤더 장애로 오진한다.
            log.warn("[AiSrvr] 시계열 상태점검 목적지를 만들 수 없습니다(벤더 상태가 아니라 원장 결손입니다)."
                    + " srvrId={}", VlmClient.safeForLog(server.getSrvrId()));
            return false;
        } catch (RuntimeException error) {
            if (transportFailed(error)) {
                log.debug("[AiSrvr] 시계열 상태점검 전송이 실패했습니다. srvrId={} · 원인={}",
                        VlmClient.safeForLog(server.getSrvrId()), error.getClass().getSimpleName());
                return false;
            }
            // 응답은 도달했다 — 오류 응답·해석 불가 본문·예상 밖 형식은 벤더 정책·규격 드리프트이지
            // 장애가 아니다. 상태코드·예외 종류만 남긴다(CWE-209).
            log.debug("[AiSrvr] 시계열 상태 응답이 우리 기대와 달랐습니다(도달했으므로 생존으로 봅니다)."
                            + " srvrId={} · 원인={}",
                    VlmClient.safeForLog(server.getSrvrId()), error.getClass().getSimpleName());
            return true;
        }
        if (status != null && !status.isSubmittable()) {
            // ★벤더가 <스스로> 지금은 처리할 수 없다고 밝혔다. 이것은 우리가 해석해서 추정한 것이
            //   아니라 계약이 정의한 값이라, 관대함의 대상이 아니다.
            log.warn("[AiSrvr] 시계열 장비가 아직 처리할 수 없다고 응답했습니다(실패로 셉니다)."
                    + " srvrId={}", VlmClient.safeForLog(server.getSrvrId()));
            return false;
        }
        return true;
    }

    /**
     * 이 실패가 <b>전송 계층</b>의 것인가 — 「닿지 못했다」와 「닿았는데 내용이 달랐다」를 가른다.
     *
     * <p>각 단계에서 <b>도달 표식을 먼저</b> 본다. 본문 해석 실패({@link CodecException})의 원인 사슬에는
     * {@code JsonProcessingException} 이 있고 그것이 {@link IOException} 의 하위형이라, 원인부터 훑으면
     * <b>도달한 응답이 전송 실패로 오분류</b>된다.
     *
     * <p>모르는 예외는 <b>「닿았다」쪽</b>으로 둔다 — 이 축은 오판의 대가가 비대칭이라(멀쩡한 벤더를
     * 내리면 그 계통이 통째로 멈춘다) 확실한 것만 거짓으로 센다.
     */
    private static boolean transportFailed(Throwable error) {
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof WebClientResponseException || t instanceof CodecException) {
                return false;
            }
            if (t instanceof WebClientRequestException
                    || t instanceof TimeoutException
                    || t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** 우리가 배포한 추론 서버 — <b>2xx 만</b> 살아 있는 것으로 본다(위 클래스 주석). */
    private boolean pingInference(LsAiSrvr server) {
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(server.getSrvrAddr()) + HEALTH_PATH);
        } catch (RuntimeException malformed) {
            // ★주소 값을 로그에 싣지 않는다(내부 토폴로지 — CWE-497).
            // ★식별자도 원장 유래라 정제해 싣는다 — 시계열 분기와 같은 처리다(CWE-117).
            //   원장 행은 DB 체크 제약을 우회해 들어왔을 수 있다는 것이 이 저장소의 전제다.
            log.warn("[AiSrvr] 상태점검 주소를 만들 수 없습니다. srvrId={}",
                    VlmClient.safeForLog(server.getSrvrId()));
            return false;
        }
        try {
            return Boolean.TRUE.equals(webClient.get()
                    .uri(uri)
                    .exchangeToMono(response -> response.releaseBody()
                            .thenReturn(response.statusCode().is2xxSuccessful()))
                    .timeout(PING_TIMEOUT)
                    .onErrorResume(error -> {
                        log.debug("[AiSrvr] 상태점검 실패. srvrId={} · 원인={}",
                                VlmClient.safeForLog(server.getSrvrId()),
                                error.getClass().getSimpleName());
                        return Mono.just(false);
                    })
                    .blockOptional()
                    .orElse(false));
        } catch (RuntimeException unexpected) {
            log.warn("[AiSrvr] 상태점검이 예상치 못하게 중단됐습니다. srvrId={} · 원인={}",
                    VlmClient.safeForLog(server.getSrvrId()),
                    unexpected.getClass().getSimpleName());
            return false;
        }
    }

    private static String stripTrailingSlash(String addr) {
        String value = addr == null ? "" : addr.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
