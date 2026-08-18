package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 대기 예산의 <b>절대 상한</b> ↔ 앞단 프록시 읽기 제한시간 정합.
 *
 * <h3>왜 고정하는가</h3>
 * <p>{@code AiWaitBudgetPolicy.DEFAULT_CEILING_SEC} 는 임의로 고른 숫자가 아니라 <b>앞단이 상류 응답을
 * 기다려 주는 시간</b>이다. 그보다 큰 예산을 화면에 내려보내면 <b>서버는 아직 일하는 중인데 앞단이
 * 먼저 끊어</b> 사용자에게는 또다시 "AI 실패"로 보인다 — 이 라운드가 고친 결함과 정확히 같은 모양이다.
 *
 * <p>반대로 앞단만 늘리고 이 상수를 그대로 두면, 넓힌 여유를 아무도 쓰지 못한다. 두 값은 <b>함께</b>
 * 움직여야 하므로 기계로 묶는다.
 *
 * <p>온프렘 웹 계층은 기본(Caddy)과 대안(기존 nginx 보유 서버) <b>두 형상</b>으로 배포되므로 둘 다
 * 검사한다 — 한쪽만 맞으면 "어느 서버에 배포됐느냐"에 따라 재현되지 않는 실패가 된다.
 */
class AiWaitBudgetEdgeAlignmentGuardTest {

    private static final Path NGINX_TEMPLATE =
            Paths.get("../deploy/onprem/config/frontend/nginx.conf.template");
    private static final Path CADDY_TEMPLATE =
            Paths.get("../deploy/onprem/config/frontend/Caddyfile.template");

    /** nginx {@code proxy_read_timeout 300s;} */
    private static final Pattern NGINX_READ_TIMEOUT =
            Pattern.compile("(?m)^\\s*proxy_read_timeout\\s+([0-9]+)s\\s*;");
    /** Caddy {@code read_timeout 300s} */
    private static final Pattern CADDY_READ_TIMEOUT =
            Pattern.compile("(?m)^\\s*read_timeout\\s+([0-9]+)s\\s*$");

    @Test
    @DisplayName("기본_절대_상한이_nginx_형상의_읽기_제한시간과_같다")
    void ceilingMatchesNginxReadTimeout() {
        assertThat(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC)
                .as("앞단이 %d초에 끊는데 예산을 다르게 잡으면, 서버가 일하는 중에 앞단이 먼저 끊거나"
                        + " 넓힌 여유를 아무도 쓰지 못한다", readTimeoutSec(NGINX_TEMPLATE, NGINX_READ_TIMEOUT))
                .isEqualTo(readTimeoutSec(NGINX_TEMPLATE, NGINX_READ_TIMEOUT));
    }

    @Test
    @DisplayName("기본_절대_상한이_Caddy_형상의_읽기_제한시간과_같다")
    void ceilingMatchesCaddyReadTimeout() {
        assertThat(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC)
                .isEqualTo(readTimeoutSec(CADDY_TEMPLATE, CADDY_READ_TIMEOUT));
    }

    @Test
    @DisplayName("기본_절대_상한은_어떤_종류든_한_프레임은_완주할_수_있는_값이다")
    void ceilingFitsAtLeastOneFrameOfEveryKind() {
        // 이 관계가 깨지면 그 종류는 "나눠 보내도 완주 불가" 가 되어 기능 자체가 서지 않는다.
        // 하한은 재시도 예산에서 파생되므로, yml 의 재시도를 크게 늘리면 이 테스트가 먼저 경고한다.
        AiWaitBudgetPolicy policy = new AiWaitBudgetPolicy(
                io.github.resilience4j.retry.RetryRegistry.of(
                        io.github.resilience4j.retry.RetryConfig.custom()
                                .maxAttempts(3)
                                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                                        .ofExponentialBackoff(java.time.Duration.ofSeconds(1), 2.0))
                                .build()));
        assertThat(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC)
                .isGreaterThanOrEqualTo(policy.minimumCeilingSeconds());
    }

    private static int readTimeoutSec(Path template, Pattern pattern) {
        Matcher matcher = pattern.matcher(read(template));
        assertThat(matcher.find())
                .as("%s 에서 상류 읽기 제한시간을 찾지 못했다 — 설정이 사라졌거나 문법이 바뀌었다", template)
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}
