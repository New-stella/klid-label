package kr.co.cudo.authoring.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-ISSUE-02 — {@code /v1/**} 포괄 매처의 역할 결합 상향 회귀.
 *
 * <p>과거 이 매처는 {@code hasAuthority("CHANNEL_INTERNAL")} 만 요구해서, LS_USER_ROLE 미배정
 * (role=null) 내부 사용자가 {@code @PreAuthorize} 가 없는 조회 엔드포인트를 전건 통과했다.
 * 여기서는 <b>메서드 보안이 없는</b> 엔드포인트({@code /v1/event-types})를 표본으로 매처 자체의 판정을
 * 검증한다 — {@code @PreAuthorize} 가 있는 엔드포인트로는 매처 회귀를 잡지 못한다.
 *
 * <p>함께 고정하는 <b>의도된 예외</b>: 온보딩 경로({@code /v1/me}, {@code /v1/auth/role-claim})는
 * role=null 사용자가 반드시 도달해야 하므로 역할 게이트에서 제외된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class InternalRoleGateMatcherTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private org.springframework.cache.CacheManager cacheManager;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    /**
     * 표본 엔드포인트({@code /v1/event-types})는 무효화 없는 장수명 캐시를 채운다. 본 테스트는 시드 없이
     * 호출하므로 빈 결과가 캐시에 남아 같은 컨텍스트를 공유하는 다른 테스트(StatsControllerTest 등)를
     * 오염시킨다. 호출 후 반드시 비운다(테스트 격리 — 프로덕션 동작과 무관).
     */
    @org.junit.jupiter.api.AfterEach
    void evictEventTypeCache() {
        org.springframework.cache.Cache cache =
                cacheManager.getCache(kr.co.cudo.authoring.common.config.CacheConfig.CACHE_EVENT_TYPE);
        if (cache != null) {
            cache.clear();
        }
    }

    /** LS 미배정 sub — 인증은 되지만 저작도구 역할 없음(role=null). */
    private String unassignedToken() {
        return JwtTestSupport.token(secret, "770001", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private String workerToken() {
        return JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("role_null_INTERNAL_사용자는_내부_조회_API_403")
    void roleNullForbiddenOnInternalQueryApi() throws Exception {
        // given: 유효 서명·허용 issuer 이지만 LS_USER_ROLE 미배정 토큰
        // when/then: 메서드 보안이 없는 내부 조회 API 도 매처 단계에서 403
        mockMvc.perform(get("/v1/event-types").header("Authorization", "Bearer " + unassignedToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/event-types/labels").header("Authorization", "Bearer " + unassignedToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("WORKER_는_내부_조회_API_정상_통과_회귀0")
    void workerStillAllowedOnInternalQueryApi() throws Exception {
        mockMvc.perform(get("/v1/event-types").header("Authorization", "Bearer " + workerToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("role_null_사용자도_me_조회는_가능_온보딩_보존")
    void roleNullStillReadsOwnSession() throws Exception {
        // /v1/me 는 본인 클레임만 반환하며, 무권한 사용자가 role-claim 화면으로 진입하는 유일한 경로다.
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + unassignedToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("role_null_사용자도_role_claim_엔드포인트에는_도달_403_아님")
    void roleNullReachesRoleClaimEndpoint() throws Exception {
        // 매처가 막으면 403 이 된다. 도달하면 관리자 패스워드 미설정/불일치로 401 이 정상.
        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + unassignedToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"WORKER\",\"adminPassword\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized());
    }
}
