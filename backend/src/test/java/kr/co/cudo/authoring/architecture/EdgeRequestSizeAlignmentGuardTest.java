package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앞단(프록시) 요청 크기 상한 ↔ 서버 상한 정합 — <b>판정은 언제나 서버가 한다</b>.
 *
 * <h3>무엇이 어긋나 있었나</h3>
 * <p>앞단은 512MB, 서버는 1200MB(운영 1100MB)였다. 그 사이 크기의 요청은 <b>서버에 닿기도 전에</b>
 * 프록시가 거부하므로, 앱이 만든 413(표준 {@code ApiResponse} + {@code errorCode})이 아니라 프록시
 * 기본 오류 페이지가 나가고 서버 로그에는 아무 흔적도 남지 않는다.
 *
 * <h3>왜 서버를 낮추지 않고 앞단을 올렸나 (확인한 사실)</h3>
 * <p>512MB 를 <b>정당하게</b> 넘는 요청이 실재한다 — 포털 이미지 다중 업로드
 * ({@code POST /v1/portal/uploads/images})는 확정 계약상 <b>개당 20MB × 요청당 50장</b>이라 한 요청이
 * 최대 1,000MB 다. 운영 상한 1100MB 는 정확히 그 계약에서 도출된 값이다(50×21MB + 헤드룸).
 * 재개 업로드(TUS)는 청크가 16MB 라 이 축과 무관하다. 즉 서버를 512MB 로 낮추면 확정된 제품 계약이
 * 깨지므로, 넓히는 쪽이 앞단이어야 한다.
 *
 * <p>앞단은 <b>서버 상한 중 최댓값</b>에 맞춘다 — 그래야 어느 프로파일에서도 앞단이 아니라 서버가
 * 판정한다(운영은 서버가 1100MB 에서 자기 형식의 413 을 낸다).
 *
 * <h3>두 형상을 <b>함께</b> 고정한다</h3>
 * <p>온프렘 웹 계층은 기본(Caddy)과 대안(기존 nginx 보유 서버) <b>두 형상</b>으로 배포된다. 한쪽만
 * 고치면 다른 배포에서 같은 결함이 그대로 남으므로 두 파일을 모두 검사한다.
 */
class EdgeRequestSizeAlignmentGuardTest {

    private static final String MAX_REQUEST_SIZE_KEY = "spring.servlet.multipart.max-request-size";

    private static final List<String> SERVER_YMLS = List.of(
            "application.yml", "application-local.yml", "application-dev.yml",
            "application-stg.yml", "application-prd.yml");

    private static final Path NGINX_TEMPLATE =
            Paths.get("../deploy/onprem/config/frontend/nginx.conf.template");
    private static final Path CADDY_TEMPLATE =
            Paths.get("../deploy/onprem/config/frontend/Caddyfile.template");

    /** {@code client_max_body_size 1200m;} */
    private static final Pattern NGINX_LIMIT =
            Pattern.compile("(?m)^\\s*client_max_body_size\\s+([0-9]+[kKmMgG]?)\\s*;");
    /** Caddy {@code request_body { max_size 1200MB }} */
    private static final Pattern CADDY_LIMIT =
            Pattern.compile("(?m)^\\s*max_size\\s+([0-9]+[kKmMgG]?[bB]?)\\s*$");

    @Test
    @DisplayName("nginx_대안_형상의_본문_상한이_서버_상한_이상이다")
    void nginxEdgeIsNotTighterThanServer() {
        assertEdgeCoversServer("nginx.conf.template", edgeLimit(NGINX_TEMPLATE, NGINX_LIMIT));
    }

    @Test
    @DisplayName("Caddy_기본_형상의_본문_상한이_서버_상한_이상이다")
    void caddyEdgeIsNotTighterThanServer() {
        assertEdgeCoversServer("Caddyfile.template", edgeLimit(CADDY_TEMPLATE, CADDY_LIMIT));
    }

    @Test
    @DisplayName("두_프록시_형상의_본문_상한이_서로_같다")
    void bothEdgeShapesAgree() {
        // 한쪽만 고치면 "어느 서버에 배포됐느냐"에 따라 같은 업로드가 되기도 하고 안 되기도 한다.
        assertThat(edgeLimit(NGINX_TEMPLATE, NGINX_LIMIT))
                .as("두 배포 형상의 상한이 갈리면 재현되지 않는 업로드 실패가 된다")
                .isEqualTo(edgeLimit(CADDY_TEMPLATE, CADDY_LIMIT));
    }

    @Test
    @DisplayName("포털_이미지_다중_업로드_최대_요청이_서버_상한_안에_들어간다")
    void portalBulkImageUploadFitsInServerLimit() {
        // 앞단을 넓힌 근거 자체를 고정한다 — 이 관계가 깨지면 "왜 1200MB 인가" 의 근거가 사라지고,
        // 다음 사람이 앞단을 다시 좁혀도 아무 테스트도 죽지 않는다.
        long perImage = Long.parseLong(String.valueOf(
                MainResourceYaml.environment("application.yml")
                        .getProperty("portal.upload.max-image-size-bytes")));
        int perRequest = Integer.parseInt(String.valueOf(
                MainResourceYaml.environment("application.yml")
                        .getProperty("portal.upload.max-images-per-request")));
        DataSize worstCase = DataSize.ofBytes(perImage * perRequest);

        for (String yml : List.of("application.yml", "application-prd.yml")) {
            DataSize serverLimit = serverLimit(yml);
            assertThat(serverLimit.toBytes())
                    .as("%s 의 multipart 상한(%s)이 포털 이미지 최대 요청(%s)보다 작다", yml, serverLimit, worstCase)
                    .isGreaterThanOrEqualTo(worstCase.toBytes());
        }
    }

    private void assertEdgeCoversServer(String label, DataSize edge) {
        DataSize widestServerLimit = SERVER_YMLS.stream()
                .map(EdgeRequestSizeAlignmentGuardTest::serverLimitOrNull)
                .filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.comparingLong(DataSize::toBytes))
                .orElseThrow(() -> new IllegalStateException(MAX_REQUEST_SIZE_KEY + " 를 선언한 yml 이 없다"));

        assertThat(edge.toBytes())
                .as("%s 의 상한(%s)이 서버 최대 상한(%s)보다 좁으면, 그 사이 크기의 요청은 서버에 닿기도"
                        + " 전에 프록시가 거부해 앱의 413(errorCode 포함)이 나가지 않는다", label, edge, widestServerLimit)
                .isGreaterThanOrEqualTo(widestServerLimit.toBytes());
    }

    private static DataSize serverLimit(String yml) {
        DataSize limit = serverLimitOrNull(yml);
        assertThat(limit).as("%s 에 %s 미선언", yml, MAX_REQUEST_SIZE_KEY).isNotNull();
        return limit;
    }

    private static DataSize serverLimitOrNull(String yml) {
        Object raw = MainResourceYaml.rawValue(yml, MAX_REQUEST_SIZE_KEY);
        return raw == null ? null : DataSize.parse(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
    }

    /** 프록시 표기(`1200m` / `1200MB`)를 바이트로 읽는다 — 두 문법의 단위 표기가 다르다. */
    private static DataSize edgeLimit(Path template, Pattern pattern) {
        String content = read(template);
        Matcher matcher = pattern.matcher(content);
        assertThat(matcher.find())
                .as("%s 에서 본문 크기 상한을 찾지 못했다 — 설정이 사라졌거나 문법이 바뀌었다", template)
                .isTrue();
        String token = matcher.group(1).toUpperCase(Locale.ROOT);
        // nginx 는 `512m`, Caddy 는 `512MB` — DataSize 는 `B` 로 끝나는 표기만 읽으므로 nginx 쪽에 붙여 준다.
        if (!token.endsWith("B")) {
            token = token + "B";
        }
        return DataSize.parse(token);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}
