package kr.co.cudo.authoring.architecture;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>실험(probe)</b> — 컨테이너(Tomcat)가 <b>클라이언트 연결 끊김</b>을 알려주는가.
 *
 * <h3>왜 실험을 코드로 남기나</h3>
 * <p>온디맨드 AI 추론의 <b>취소</b>를 "클라이언트가 끊으면 진행 중 추론을 중단한다"로 구현하려면
 * 먼저 <b>서버가 끊김을 관측할 수 있어야</b> 한다. 그 전제는 자명하지 않고 문서·통념도 갈리므로,
 * 이 저장소가 실제로 쓰는 컨테이너에서 확인한 사실만 근거로 삼는다.
 *
 * <h3>관측 결과 (이 테스트가 고정하는 사실)</h3>
 * <p><b>비동기 처리 중 유휴 구간에서는 끊김이 통지되지 않는다.</b> 클라이언트가 소켓을 정상적으로
 * 닫아도(FIN) {@link AsyncListener#onError}·{@code onComplete}·{@code onTimeout} 어느 것도 불리지
 * 않았다({@value #AWAIT_SECONDS}초 대기, {@code Connection: close}/keep-alive 두 형태 모두).
 *
 * <p>⇒ <b>동기 경로는 말할 것도 없다</b>(서블릿 API 에 관측 수단 자체가 없다). 따라서
 * "끊김 관측 기반 취소"는 이 스택에서 성립하지 않으며, 취소는 <b>클라이언트가 보내는 명시적 취소
 * 요청</b>으로 구현해야 한다.
 *
 * <h3>이 실험이 증명하지 <b>못하는</b> 것</h3>
 * <ul>
 *   <li>여기서 재현한 것은 <b>정상 종료(FIN)</b> 다. 회선 단절 같은 half-open 은 더더욱 알 수 없다
 *       (더 나쁜 쪽이므로 결론을 뒤집지 않는다).</li>
 *   <li><b>응답에 실제로 쓰기를 시도하면</b> 끊김이 드러날 수 있다. 그러나 그러려면 결과가 나오기
 *       전에 응답을 커밋해야 해서 상태코드·오류 응답을 쓸 수 없게 된다 — 채택하지 않은 길이다.</li>
 *   <li>앞단 프록시가 있으면 브라우저의 끊김이 프록시에서 흡수돼 서버는 더 볼 수 없다. 이 실험은
 *       <b>직결</b> 기준이라 실배포는 이보다 관측이 어렵다.</li>
 * </ul>
 *
 * <p>⚠ <b>이 테스트가 깨지면 좋은 소식이다</b> — 컨테이너가 통지 능력을 갖게 됐다는 뜻이므로,
 * 그때는 명시적 취소에 더해 끊김 기반 취소를 얹을 수 있는지 재검토한다.
 */
class ClientDisconnectObservabilityProbeTest {

    /** 끊김 통지를 기다리는 상한(초) — 넉넉히 준다("안 온다"와 "늦게 온다"를 가르기 위해). */
    private static final int AWAIT_SECONDS = 10;

    /** 아무 통지도 받지 못했을 때의 관측값. */
    private static final String NO_SIGNAL = "(통지 없음)";

    @Test
    @DisplayName("비동기_유휴_구간의_클라이언트_끊김은_컨테이너가_통지하지_않는다")
    void idleAsyncRequestNeverLearnsAboutDisconnect() throws Exception {
        assertThat(observeAfterClientClose(true))
                .as("통지가 온다면 끊김 기반 취소가 가능하다는 뜻이므로 설계를 재검토해야 한다")
                .isEqualTo(NO_SIGNAL);
    }

    @Test
    @DisplayName("keepalive_요청에서도_같다_Connection_헤더는_원인이_아니다")
    void keepAliveVariantIsAlsoSilent() throws Exception {
        // 대조군 — `Connection: close` 가 통지를 막은 것인지 가른다(두 형태 모두 통지 없음).
        assertThat(observeAfterClientClose(false)).isEqualTo(NO_SIGNAL);
    }

    /**
     * 비동기 처리를 시작한 뒤 클라이언트가 소켓을 닫았을 때 컨테이너가 준 신호를 돌려준다.
     *
     * @param connectionClose {@code Connection: close} 헤더를 실을지 여부
     * @return 관측된 신호 이름, 아무것도 없으면 {@link #NO_SIGNAL}
     */
    private String observeAfterClientClose(boolean connectionClose) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<String> observed = new AtomicReference<>(NO_SIGNAL);

        Tomcat tomcat = newTomcat();
        Wrapper wrapper = Tomcat.addServlet(
                tomcat.addContext("", Files.createTempDirectory("probe-ctx").toString()),
                "probe", new ProbeServlet(started, notified, observed));
        // 프로그램적 등록은 기본이 async 미지원이라 이 줄이 없으면 startAsync 가 즉시 예외를 던진다.
        wrapper.setAsyncSupported(true);
        wrapper.addMapping("/probe");

        tomcat.start();
        try {
            int port = tomcat.getConnector().getLocalPort();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                OutputStream out = socket.getOutputStream();
                out.write(("GET /probe HTTP/1.1\r\nHost: localhost\r\n"
                        + (connectionClose ? "Connection: close\r\n" : "")
                        + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(started.await(10, TimeUnit.SECONDS))
                        .as("서블릿이 비동기 처리를 시작하지 못했다 — 실험 전제가 성립하지 않는다")
                        .isTrue();
            } // try-with-resources 종료 = 클라이언트가 연결을 닫는다(FIN).

            notified.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            return observed.get();
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    /** 응답을 완료하지 않고 비동기 상태로 머무는 서블릿 — 진행 중인 추론을 흉내낸다. */
    private static final class ProbeServlet extends HttpServlet {

        private final transient CountDownLatch started;
        private final transient CountDownLatch notified;
        private final transient AtomicReference<String> observed;

        private ProbeServlet(CountDownLatch started, CountDownLatch notified,
                             AtomicReference<String> observed) {
            this.started = started;
            this.notified = notified;
            this.observed = observed;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) {
            AsyncContext ctx = req.startAsync();
            // 제한시간을 대기보다 길게 둬야 "끊김 통지"와 "제한시간 만료"가 섞이지 않는다.
            ctx.setTimeout(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS * 10L));
            ctx.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    record("onComplete");
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    record("onTimeout");
                }

                @Override
                public void onError(AsyncEvent event) {
                    Throwable t = event.getThrowable();
                    record("onError(" + (t == null ? "throwable 없음" : t.getClass().getSimpleName()) + ")");
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // no-op
                }

                private void record(String signal) {
                    observed.compareAndSet(NO_SIGNAL, signal);
                    notified.countDown();
                }
            });
            started.countDown();
        }
    }

    private static Tomcat newTomcat() throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("probe-base").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        return tomcat;
    }
}
