package kr.co.cudo.authoring.auth;

import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.common.client.ControlNotifyClient;
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
import java.util.List;
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
 *   <li><b>관리자가 바꾼 관제 주소가 다음 호출부터 쓰인다</b> — 관제 흉내 서버의 주소를 배포 설정이 아니라
 *       <b>연동 주소 설정 저장값</b>으로 넣는다. 배포 기본값(localhost:8090)에는 아무도 없으므로, 저장값을
 *       읽지 않으면 요청이 흉내 서버에 닿지 않는다.</li>
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

    private JdbcTemplate jdbc;

    /** 시험 전에 이미 있던 저장값 — 끝나면 그대로 되돌린다(없었으면 행을 지운다). */
    private String priorOverride;
    private boolean priorOverridePresent;

    @BeforeEach
    void pointControlAddressAtStub() throws InterruptedException {
        jdbc = new JdbcTemplate(controlDataSource);
        List<String> prior = jdbc.queryForList(
                "SELECT STNG_VALUE FROM LS_SYSTEM_CONFIG WHERE STNG_KEY = ?", String.class,
                ConfigKeys.CONTROL_NOTIFY_URL);
        priorOverridePresent = !prior.isEmpty();
        priorOverride = priorOverridePresent ? prior.get(0) : null;
        // 관리자가 연동 주소 설정에서 관제 주소를 바꾼 상태의 재현 — 배포 기본값에는 아무도 없다.
        upsertOverride("http://" + control.getHostName() + ":" + control.getPort());
        while (control.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // 이전 시험의 잔여 요청을 비운다.
        }
    }

    @AfterEach
    void restoreControlAddress() {
        if (priorOverridePresent) {
            upsertOverride(priorOverride);
        } else {
            jdbc.update("DELETE FROM LS_SYSTEM_CONFIG WHERE STNG_KEY = ?", ConfigKeys.CONTROL_NOTIFY_URL);
            clearConfigCache();
        }
    }

    private void upsertOverride(String value) {
        jdbc.update("""
                INSERT INTO LS_SYSTEM_CONFIG (STNG_KEY, STNG_VALUE, STNG_TYPE_CD, EXPLN, MDFR_ID)
                VALUES (?, ?, 'STRING', '통합시험 시드', 'TEST')
                ON CONFLICT (STNG_KEY) DO UPDATE SET STNG_VALUE = EXCLUDED.STNG_VALUE
                """, ConfigKeys.CONTROL_NOTIFY_URL, value);
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
}
