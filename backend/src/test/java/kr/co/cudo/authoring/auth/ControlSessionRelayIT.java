package kr.co.cudo.authoring.auth;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 세션 중계 end-to-end — 실제 보안 필터 체인 + 운영 빈 배선 + 소켓 관제 (@design API-247 · API-246 · ADR-063 ⑦).
 *
 * <p>이 시험만이 볼 수 있는 것:
 * <ul>
 *   <li><b>로그인 검사 없음</b> — 토큰 없이·만료 토큰으로도 두 창구가 동작한다(permitAll 구역).</li>
 *   <li><b>통지 토글 off 에서도 동작하고 통지 토큰이 실리지 않는다</b> — 이 컨텍스트는 통지 클라이언트 빈이
 *       <b>없는</b>(토글 off) 형상이다. 통지 빈을 재사용하면 전송 직전 필터가 사용자 토큰을 서비스 토큰으로
 *       <b>덮어써</b> 여기서 실패한다(변이 실증).</li>
 *   <li><b>관리자가 바꾼 관제 계정 창구 주소가 다음 호출부터 쓰인다</b> — 관제 흉내 서버의 주소를 배포 설정이
 *       아니라 <b>연동 주소 설정 저장값</b>({@link ConfigKeys#CONTROL_ACCOUNT_URL})으로 넣는다. 배포 기본값은
 *       비어 있으므로, 저장값을 읽지 않으면 요청이 흉내 서버에 닿지 않는다.</li>
 *   <li><b>관제 통지 수신처 주소는 폴백이 아니다</b> — 계정 창구 저장값이 없고 통지 저장값만 흉내 서버를
 *       가리키면 관제를 부르지 않고 갱신 503 · 로그아웃 204 로 끝나며 서킷 실패로 세지 않는다(2026-09-14).</li>
 *   <li><b>갱신·로그아웃 서킷이 설정이 바인딩된 별개 인스턴스다</b> — 갱신 서킷이 열려도 로그아웃이 나간다.</li>
 *   <li><b>refresh 토큰으로 보호 창구 401 · access 토큰 200</b> — 두 인계 자리 모두.</li>
 * </ul>
 *
 * <p>⚠ 클래스 애노테이션을 {@code SessionControllerTest} 와 <b>같게</b> 둔다 — 캐시되는 테스트 컨텍스트
 * 종류에 상한이 있다({@code TestContextDiversityRatchetTest}). {@code @DynamicPropertySource} 를 쓰면
 * 새 컨텍스트가 하나 늘어 그 상한을 넘는다(실측: 76/75). 그래서 주소를 설정 저장값으로 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ControlSessionRelayIT {

    private static MockWebServer control;

    @BeforeAll
    static void startControl() throws IOException {
        control = new MockWebServer();
        control.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/api/account/auth/refresh")) {
                    return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                            .setBody("{\"error\":0,\"message\":\"success\","
                                    + "\"data\":{\"session_token\":\"NEW-S\",\"refresh_token\":\"NEW-R\"}}");
                }
                if (path.startsWith("/api/account/auth/logout")) {
                    return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                            .setBody("{\"error\":0}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        control.start();
    }

    @AfterAll
    static void stopControl() throws IOException {
        control.shutdown();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    @Qualifier("controlAccountCircuitBreaker")
    private CircuitBreaker refreshCircuit;

    @Autowired
    @Qualifier("controlAccountLogoutCircuitBreaker")
    private CircuitBreaker logoutCircuit;

    private JdbcTemplate jdbc;

    /** 시험이 건드리는 연동 주소 키 — 계정 창구와 통지 수신처 둘 다 끝나면 그대로 되돌린다. */
    private static final List<String> RELAY_ADDRESS_KEYS =
            List.of(ConfigKeys.CONTROL_ACCOUNT_URL, ConfigKeys.CONTROL_NOTIFY_URL);

    /** 시험 전에 이미 있던 저장값(키 → 조회 결과, 빈 목록이면 행 없음). */
    private final Map<String, List<String>> priorOverrides = new LinkedHashMap<>();

    @BeforeEach
    void pointControlAddressAtStub() throws InterruptedException {
        jdbc = new JdbcTemplate(controlDataSource);
        priorOverrides.clear();
        for (String key : RELAY_ADDRESS_KEYS) {
            priorOverrides.put(key, jdbc.queryForList(
                    "SELECT STNG_VALUE FROM LS_SYSTEM_CONFIG WHERE STNG_KEY = ?", String.class, key));
        }
        // 관리자가 연동 주소 설정에서 관제 계정 창구 주소를 바꾼 상태의 재현 — 배포 기본값은 비어 있다.
        upsertOverride(ConfigKeys.CONTROL_ACCOUNT_URL, stubAddress());
        while (control.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // 이전 시험의 잔여 요청을 비운다.
        }
    }

    @AfterEach
    void restoreControlAddress() {
        priorOverrides.forEach((key, prior) -> {
            if (prior.isEmpty()) {
                deleteOverride(key);
            } else {
                upsertOverride(key, prior.get(0));
            }
        });
    }

    private static String stubAddress() {
        return "http://" + control.getHostName() + ":" + control.getPort();
    }

    private void upsertOverride(String key, String value) {
        jdbc.update("""
                INSERT INTO LS_SYSTEM_CONFIG (STNG_KEY, STNG_VALUE, STNG_TYPE_CD, EXPLN, MDFR_ID)
                VALUES (?, ?, 'STRING', '통합시험 시드', 'TEST')
                ON CONFLICT (STNG_KEY) DO UPDATE SET STNG_VALUE = EXCLUDED.STNG_VALUE
                """, key, value);
        clearConfigCache();
    }

    private void deleteOverride(String key) {
        jdbc.update("DELETE FROM LS_SYSTEM_CONFIG WHERE STNG_KEY = ?", key);
        clearConfigCache();
    }

    private void clearConfigCache() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_SYSCONFIG);
        if (cache != null) {
            cache.clear();
        }
    }

    private String typedToken(String type) {
        Instant now = Instant.now();
        var b = Jwts.builder()
                .subject("1")
                .issuer(issuer)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)));
        if (type != null) {
            b.claim("type", type);
        }
        return b.signWith(JwtTestSupport.key(secret)).compact();
    }

    @Test
    @DisplayName("★통지_토글이_꺼진_형상에서_토큰_없이_갱신하면_201_이고_관제에는_사용자_refresh_토큰만_실린다")
    void refreshWithoutLoginCarriesUserTokenNotNotifyToken() throws Exception {
        // 전제 — 이 컨텍스트는 통지를 끈 배포 형상이다(통지 클라이언트 빈 미등록).
        assertThat(context.getBeanProvider(ControlNotifyClient.class).getIfAvailable())
                .as("통지 토글 off 형상에서 검증해야 「통지를 꺼도 연장된다」가 성립한다")
                .isNull();
        String refresh = typedToken("refresh");

        mockMvc.perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionToken").value("NEW-S"))
                .andExpect(jsonPath("$.data.refreshToken").value("NEW-R"));

        RecordedRequest req = control.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).as("저장된 관제 주소가 요청을 받아야 한다 — 배포 기본값에는 아무도 없다").isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/account/auth/refresh");
        assertThat(req.getHeader("x-access-token"))
                .as("통지용 서비스 토큰이 사용자 토큰 자리를 덮으면 관제 갱신이 통째로 실패한다")
                .isEqualTo(refresh);
        assertThat(control.takeRequest(300, TimeUnit.MILLISECONDS)).as("관제 호출은 1회").isNull();
    }

    @Test
    @DisplayName("만료된_Bearer_를_달고_와도_갱신은_201_이다_로그인_검사_없음")
    void refreshWithExpiredBearerStillWorks() throws Exception {
        String expired = JwtTestSupport.expiredToken(secret, "1", "REVIEWER", "INTERNAL", issuer);

        mockMvc.perform(post("/v1/auth/control-tokens")
                        .header("Authorization", "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"R-IN\"}"))
                .andExpect(status().isCreated());
        RecordedRequest req = control.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("x-access-token")).isEqualTo("R-IN");
    }

    @Test
    @DisplayName("★로그아웃은_Bearer_access_토큰을_관제로_넘기고_204_헤더가_없으면_부르지_않는다")
    void logoutRelaysAccessToken() throws Exception {
        String access = typedToken("access");

        mockMvc.perform(delete("/v1/auth/control-session").header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());

        RecordedRequest req = control.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/account/auth/logout");
        assertThat(req.getHeader("x-access-token")).isEqualTo(access);

        mockMvc.perform(delete("/v1/auth/control-session")).andExpect(status().isNoContent());
        assertThat(control.takeRequest(300, TimeUnit.MILLISECONDS)).as("헤더가 없으면 관제를 부르지 않는다").isNull();
    }

    @Test
    @DisplayName("★refresh_토큰으로_보호_창구를_부르면_401_같은_키의_access_와_종류없는_토큰은_200_Bearer")
    void refreshTokenIsNotAnApiCredentialViaBearer() throws Exception {
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + typedToken("refresh")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + typedToken("access")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + typedToken(null)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★refresh_토큰은_포털_전용_헤더로_와도_401_access_는_200")
    void refreshTokenIsNotAnApiCredentialViaPortalHeader() throws Exception {
        mockMvc.perform(get("/v1/me").header("x-access-token", typedToken("refresh")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/me").header("x-access-token", typedToken("access")))
                .andExpect(status().isOk());
    }

    // ─────────────── ★ 관제 계정 창구 주소 분리 · 서킷 분리 (2026-09-14 · AC-1105 · AC-1106) ───────────────

    @Test
    @DisplayName("★계정_창구_주소가_비고_통지_주소만_관제를_가리키면_관제를_부르지_않고_갱신_503_로그아웃_204_서킷_실패_미집계")
    void notifyAddressIsNotAFallback() throws Exception {
        // 전제 — 계정 창구 배포 기본값은 비어 있다(application.yml `${CONTROL_ACCOUNT_URL:}`).
        assertThat(context.getEnvironment().getProperty("authoring.control-account.url")).isNullOrEmpty();
        deleteOverride(ConfigKeys.CONTROL_ACCOUNT_URL);
        upsertOverride(ConfigKeys.CONTROL_NOTIFY_URL, stubAddress());
        refreshCircuit.reset();
        logoutCircuit.reset();

        mockMvc.perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"R-IN\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CONTROL_SESSION_UNAVAILABLE"));
        mockMvc.perform(delete("/v1/auth/control-session").header("Authorization", "Bearer " + typedToken("access")))
                .andExpect(status().isNoContent());

        assertThat(control.takeRequest(500, TimeUnit.MILLISECONDS))
                .as("통지 수신처로 폴백하면 흉내 서버가 요청을 받는다").isNull();
        for (CircuitBreaker cb : List.of(refreshCircuit, logoutCircuit)) {
            assertThat(cb.getMetrics().getNumberOfFailedCalls()).as(cb.getName()).isZero();
            assertThat(cb.getMetrics().getNumberOfBufferedCalls())
                    .as("주소 미설정은 결정적 설정 상태라 집계에서 빠진다 — " + cb.getName()).isZero();
        }
    }

    @Test
    @DisplayName("★갱신_서킷이_열려_있어도_로그아웃_중계는_관제로_나간다")
    void logoutRelayIgnoresOpenRefreshCircuit() throws Exception {
        refreshCircuit.transitionToOpenState();
        try {
            mockMvc.perform(post("/v1/auth/control-tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"R-IN\"}"))
                    .andExpect(status().isServiceUnavailable());
            assertThat(control.takeRequest(300, TimeUnit.MILLISECONDS)).as("열린 갱신 서킷은 갱신만 막는다").isNull();

            String access = typedToken("access");
            mockMvc.perform(delete("/v1/auth/control-session").header("Authorization", "Bearer " + access))
                    .andExpect(status().isNoContent());

            RecordedRequest req = control.takeRequest(3, TimeUnit.SECONDS);
            assertThat(req).as("갱신 누적 실패가 로그아웃까지 막으면 관제 서버 세션이 닫히지 않는다").isNotNull();
            assertThat(req.getPath()).isEqualTo("/api/account/auth/logout");
            assertThat(req.getHeader("x-access-token")).isEqualTo(access);
        } finally {
            refreshCircuit.reset();
        }
    }

    @Test
    @DisplayName("★갱신·로그아웃_서킷은_yml_설정이_바인딩된_별개_인스턴스이고_주소_미설정_예외를_집계에서_뺀다")
    void relayCircuitsAreSeparateAndIgnoreUnusableAddress() {
        assertThat(refreshCircuit.getName()).isEqualTo("controlAccount");
        assertThat(logoutCircuit.getName()).isEqualTo("controlAccountLogout");
        assertThat(logoutCircuit).isNotSameAs(refreshCircuit);
        assertThat(circuitBreakerRegistry.circuitBreaker("controlAccountLogout")).isSameAs(logoutCircuit);

        NonRetryableExternalException unusable =
                new NonRetryableExternalException("관제 계정 창구 연동 주소가 설정되지 않아 요청을 보내지 않았습니다.");
        for (CircuitBreaker cb : List.of(refreshCircuit, logoutCircuit)) {
            CircuitBreakerConfig cfg = cb.getCircuitBreakerConfig();
            // 라이브러리 기본값(100/100)이 아니라 application.yml 인스턴스 값이 바인딩됐는지 값으로 본다.
            assertThat(cfg.getSlidingWindowSize()).as(cb.getName()).isEqualTo(10);
            assertThat(cfg.getMinimumNumberOfCalls()).as(cb.getName()).isEqualTo(5);
            assertThat(cfg.getIgnoreExceptionPredicate().test(unusable)).as(cb.getName()).isTrue();
        }
    }
}
