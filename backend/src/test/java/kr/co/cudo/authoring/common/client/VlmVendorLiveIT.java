package kr.co.cudo.authoring.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.config.VlmUrlPolicy;
import kr.co.cudo.authoring.common.config.WebClientConfig;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 실벤더 왕복 확인 — <b>우리 실제 클라이언트 배선</b>으로 외부 시계열 서버에 붙는다.
 *
 * <p>목서버가 아니라 <b>사업자가 준 개발 서버</b>를 부른다. 목은 우리가 만든 것이라 우리 오해까지 그대로
 * 재현하므로, 「우리가 맞게 이해했는가」는 실서버로만 확인된다.
 *
 * <p>확인하는 것 넷:
 * <ol>
 *   <li>조회 창구가 살아 있는가({@code status}·{@code events})</li>
 *   <li>사업자가 지원하는 이벤트 목록이 우리 목록과 같은가</li>
 *   <li><b>추가 질문 축이 실제로 접수되는가</b> — {@code custom} 에 마킹 질문을 실어 보낸다</li>
 *   <li><b>결과 콜백이 우리에게 되돌아오는가</b> — 규격이 "callback_url 은 서버에서 접근 가능해야 한다"고
 *       못박으므로, 이 왕복이 되는지가 배포 형상의 실제 제약이다</li>
 * </ol>
 *
 * <p>★ 이 시험은 <b>외부 네트워크에 나간다</b>. 그래서 평소 회귀에서는 돌지 않고 시스템 속성을 줄 때만 돈다:
 * <pre>VLM_LIVE=true ./gradlew test --tests '*VlmVendorLiveIT*'</pre>
 *
 * <p>⚠ 시스템 속성(-D)이 아니라 <b>환경변수</b>다 — Gradle 의 -D 는 Gradle JVM 에만 붙고
 * 포크된 시험 JVM 에는 전달되지 않아 시험이 조용히 <b>SKIPPED</b> 된다(실제로 그렇게 됐다).
 *
 * <p>⚠ 판정은 <b>사실 보고</b>다. 콜백이 안 오는 것은 우리 코드의 결함이 아니라 <b>망 방향의 제약</b>일 수
 * 있으므로(사설 대역에 있는 개발기로는 외부에서 되돌아올 수 없다), 실패로 단정하지 않고 관측을 적는다.
 */
@EnabledIfEnvironmentVariable(named = "VLM_LIVE", matches = "true")
class VlmVendorLiveIT {

    private static final String VENDOR = "http://211.170.82.250:28000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 사업자가 준 키가 없으므로 비운다 — 비면 인증 헤더를 붙이지 않는다(규격상 키 미사용 환경). */
    private static final String TOKEN = System.getenv().getOrDefault("VLM_API_KEY", "");

    private WebClient realClient() {
        WebClientConfig cfg = new WebClientConfig();
        ObjectProvider<?> none = new ObjectProvider<>() {
            @Override public Object getObject(Object... args) { return null; }
            @Override public Object getObject() { return null; }
            @Override public Object getIfAvailable() { return null; }
            @Override public Object getIfUnique() { return null; }
        };
        @SuppressWarnings({"unchecked", "rawtypes"})
        IntegrationEndpointResolver resolver = new IntegrationEndpointResolver((ObjectProvider) none);
        return cfg.vlmWebClient(VENDOR, TOKEN, new VlmUrlPolicy(), resolver);
    }

    private static String lanIp() throws SocketException {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (ni.isLoopback() || !ni.isUp()) continue;
            for (var addr : Collections.list(ni.getInetAddresses())) {
                String h = addr.getHostAddress();
                if (h.contains(".") && !h.startsWith("127.")) return h;
            }
        }
        return "127.0.0.1";
    }

    @Test
    @DisplayName("실벤더 왕복 — 조회·위탁·콜백")
    void vendorRoundTrip() throws Exception {
        WebClient client = realClient();

        System.out.println("\n================ 실벤더 연동 확인 ================");
        System.out.println("대상: " + VENDOR + " | 인증키: " + (TOKEN.isBlank() ? "(없음 — 헤더 미부착)" : "설정됨"));

        // ── ① 서버 상태
        String status = get(client, "/v1/videovlm-klid/status");
        System.out.println("① status  : " + status);

        // ── ② 지원 이벤트
        String events = get(client, "/v1/videovlm-klid/events");
        System.out.println("② events  : " + summarizeEvents(events));

        // ── ③·④ 콜백 수신기를 띄우고 custom 위탁
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        // ★ 콜백 주소를 통째로 지정할 수 있게 한다 — 사업자가 되돌아올 수 있는 <공인 주소>가 필요한데,
        //   사설 대역 개발기에는 그런 주소가 없다. 외부 노출 터널이 붙어 있으면 그 주소를 그대로 쓴다.
        //   그 경우 수신기는 이 시험이 아니라 <터널이 가리키는 프로세스>가 맡으므로 여기서 띄우지 않는다.
        String fixedUrl = System.getenv("VLM_CALLBACK_URL");
        int want = Integer.parseInt(System.getenv().getOrDefault("VLM_CALLBACK_PORT", "0"));
        HttpServer cb = fixedUrl == null || fixedUrl.isBlank()
                ? HttpServer.create(new InetSocketAddress(want), 0) : null;
        int port = cb == null ? 0 : cb.getAddress().getPort();
        if (cb != null) cb.createContext("/v1/vlm/callback", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(ok); }
        });
        if (cb != null) cb.start();

        // ★ 콜백은 <사업자 서버가 되돌아올 수 있는 주소>여야 한다(규격 §1.2). 사설 대역으로는 도달하지
        //   못하므로 공인 주소를 넘길 수 있게 한다. 넘기지 않으면 종전대로 LAN 주소를 쓴다.
        String pub = System.getenv("VLM_CALLBACK_HOST");
        String host = (pub == null || pub.isBlank()) ? lanIp() : pub;
        String callbackUrl = (fixedUrl != null && !fixedUrl.isBlank())
                ? fixedUrl : ("http://" + host + ":" + port + "/v1/vlm/callback");
        String requestId = "LIVE-" + UUID.randomUUID();
        String question = "영상에서 '신체적 충돌을 동반한 싸움' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?";

        System.out.println("③ custom  : 콜백주소=" + callbackUrl);
        System.out.println("            prompt(마킹에서 고른 질문)=" + question);

        VlmTimeseriesRequest base = VlmTimeseriesRequest.ofFrameSelected(
                requestId, "violence", "/data/videos/sample.mp4", List.of(0, 30, 60), callbackUrl);
        VlmTimeseriesRequest custom = VlmTimeseriesRequest.toCustom(base, requestId, question);
        System.out.println("            보낼 본문=" + MAPPER.writeValueAsString(custom));

        String submitResult;
        try {
            submitResult = client.post().uri("/v1/videovlm-klid/custom")
                    .bodyValue(custom).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            submitResult = "접수됨 → " + submitResult;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            // ★ 응답 본문을 그대로 찍는다 — 400 은 사유를 모르면 진단이 안 된다.
            //   운영 코드는 본문을 보존하지 않지만(CWE-209) 이 시험은 사람이 읽는 진단 도구다.
            submitResult = "거부 → HTTP " + e.getStatusCode().value()
                    + " body=" + e.getResponseBodyAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            submitResult = "실패 → " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
        }
        System.out.println("            결과=" + submitResult);

        // ── ③-b path 가 막히면 업로드 방식으로 한 번 더 — <b>콜백 왕복 자체</b>를 확인하기 위함이다.
        //   ⚠ 업로드는 <b>우리 연동면이 아니다</b>(우리는 공유 저장소 path 로만 보낸다). 여기서 쓰는 이유는
        //     경로 협의가 끝나기 전에도 「접수→분석→콜백」 방향이 열려 있는지 알아야 하기 때문이다.
        if (submitResult.contains("허용되지 않은 경로")) {
            java.io.File f = new java.io.File(System.getenv().getOrDefault(
                    "VLM_SAMPLE_VIDEO", "build/resources/test/fixtures/tiny-video.mp4"));
            System.out.println("③-b upload: path 가 거부돼 업로드 방식으로 재시도 — " + f.getAbsolutePath()
                    + " (" + (f.exists() ? f.length() + "B" : "없음") + ")");
            if (f.exists()) {
                String uploadId = "LIVE-UP-" + UUID.randomUUID();
                var payload = MAPPER.createObjectNode();
                payload.put("request_id", uploadId);
                payload.put("prompt", question);
                payload.put("callback_url", callbackUrl);
                var media = payload.putObject("media");
                media.put("type", "video");
                media.put("source_type", "upload");
                media.putObject("frame_policy").put("mode", "frame_interval");

                var mb = new org.springframework.http.client.MultipartBodyBuilder();
                mb.part("payload", MAPPER.writeValueAsString(payload));
                mb.part("file", new org.springframework.core.io.FileSystemResource(f))
                        .filename(f.getName());
                try {
                    String up = client.post().uri("/v1/videovlm-klid/custom")
                            .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                            .bodyValue(mb.build())
                            .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(60));
                    System.out.println("            결과=접수됨 → " + up);
                } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                    System.out.println("            결과=거부 → HTTP " + e.getStatusCode().value()
                            + " body=" + e.getResponseBodyAsString(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    System.out.println("            결과=실패 → " + e.getClass().getSimpleName()
                            + ": " + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
                }
            }
        }

        // ④ 콜백 대기
        System.out.println("④ 콜백    : 최대 60초 대기…");
        // 외부 수신기를 쓰는 경우 이 시험은 직접 받지 않는다 — 대기만 하고 판정은 그쪽 로그로 한다.
        for (int i = 0; i < 90 && received.isEmpty(); i++) TimeUnit.SECONDS.sleep(1);
        if (cb != null) cb.stop(0);

        if (received.isEmpty()) {
            System.out.println("            수신 0건 — 사업자 서버가 " + callbackUrl + " 로 되돌아오지 못했다.");
            System.out.println("            ⚠ 이것이 곧 우리 코드의 결함은 아니다. 규격이 「callback_url 은 서버에서");
            System.out.println("              접근 가능해야 한다」고 못박으므로, 사설 대역 개발기로는 원래 도달할 수 없다.");
            System.out.println("              배포 형상에서 <사업자 → 저작도구> 방향이 열려 있는지 확인해야 한다.");
        } else {
            System.out.println("            수신 " + received.size() + "건");
            received.forEach(b -> System.out.println("            " + b));
        }
        System.out.println("=================================================\n");
    }

    private String get(WebClient c, String path) {
        try {
            return c.get().uri(path).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(15));
        } catch (Exception e) {
            return "실패 → " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
        }
    }

    private String summarizeEvents(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            return "version=" + n.path("version").asText("?")
                    + " describe=" + n.path("describe_events")
                    + " describe_sub=" + n.path("describe_sub_events");
        } catch (Exception e) {
            return json == null ? "(응답 없음)" : json.substring(0, Math.min(300, json.length()));
        }
    }
}
