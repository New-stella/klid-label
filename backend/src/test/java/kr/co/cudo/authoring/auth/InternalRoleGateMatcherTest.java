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

import java.util.List;

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
 *
 * <p><b>라벨 마스터 읽기 3경로도 같은 축이다</b> — 이 매처들은 {@code .authenticated()} 라
 * 위 A-ISSUE-02 상향에서 <b>혼자 빠져</b> role=null 사용자를 그대로 통과시키고 있었다(같은 실패
 * 클래스의 재발). 세 역할(REVIEWER/WORKER/PORTAL_USER)만 통과하도록 고정한다 —
 * PORTAL_USER 를 빼면 포털 업로드 라벨링 화면이 깨지므로 <b>허용 쪽도 함께</b> 단언한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class InternalRoleGateMatcherTest {

    /**
     * 저장소에 <b>역할 행 자체가 없는</b> sub — 부트스트랩 모집단의 진짜 표본.
     * 아래 {@code unassignedToken}(enum 밖 코드 보유)과 <b>뜻이 다르므로 헬퍼를 공유하지 않는다</b>.
     */
    private static final long UNSEEDED_USER_NO = 969_700_001L;

    @Autowired private MockMvc mockMvc;
    @Autowired private org.springframework.cache.CacheManager cacheManager;
    @Autowired private kr.co.cudo.authoring.common.security.UserRoleResolver userRoleResolver;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    /**
     * 표본 엔드포인트({@code /v1/event-types})는 무효화 없는 장수명 캐시를 채운다. 본 테스트는 시드 없이
     * 호출하므로 빈 결과가 캐시에 남아 같은 컨텍스트를 공유하는 다른 테스트(StatsControllerTest 등)를
     * 오염시킨다. 호출 후 반드시 비운다(테스트 격리 — 프로덕션 동작과 무관).
     */
    /**
     * 이 시험이 만드는 유일한 부수효과 — 진입 시 자동 등록으로 생기는 사용자 행·역할 행을 지운다.
     * 남기면 사용자·작업자 목록 픽스처를 오염시킨다.
     */
    @org.junit.jupiter.api.AfterEach
    void purgeAutoRegisteredUser() {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(controlDataSource);
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", UNSEEDED_USER_NO);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", UNSEEDED_USER_NO);
        userRoleResolver.evict(UNSEEDED_USER_NO);
    }

    @org.junit.jupiter.api.AfterEach
    void evictEventTypeCache() {
        org.springframework.cache.Cache cache =
                cacheManager.getCache(kr.co.cudo.authoring.common.config.CacheConfig.CACHE_EVENT_TYPE);
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * 역할이 해석되지 않는 sub — 인증은 되지만 저작도구 역할 없음(role=null).
     *
     * <p>★ADR-055(진입 시 작업자 자동 등록) 이후 <b>시드에 없는 숫자 sub 는 role=null 표본이
     * 아니다</b> — 첫 요청에 WORKER 로 등록된다. 그래서 770001 은 V9002 가 Role enum 밖 코드로
     * 심어 두고, 자동 등록이 그 행을 덮지 않는다는 성질(DO NOTHING)에 기대어 표본을 유지한다.
     */
    private String unassignedToken() {
        return JwtTestSupport.token(secret, "770001", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private String workerToken() {
        return JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    private String reviewerToken() {
        return JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    /** PORTAL 채널 토큰 — 필터가 LS 조회 없이 role=PORTAL_USER 로 고정한다. */
    private String portalToken() {
        return JwtTestSupport.token(secret, "500", "PORTAL_USER", "PORTAL", issuer, 60);
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

    // ────────────────────────────────────────────────────────────────────
    // 라벨 마스터 읽기 3경로 — .authenticated() fail-open 회귀 (CWE-862)
    //   [@design API-024] GET /v1/manage/labels
    //   [@design API-028] GET /v1/manage/labels/{labelId}/attrs
    //   [@design API-177] GET /v1/manage/labels/detect-candidates
    // ────────────────────────────────────────────────────────────────────

    /** 존재하지 않는 라벨 — 매처를 통과하면 컨트롤러가 404 를 낸다(403 과 구분되는 "도달" 신호). */
    private static final long ABSENT_LABEL_ID = 999_999_999L;

    @Test
    @DisplayName("role_null_사용자는_라벨마스터_읽기_3경로에서_403")
    void roleNullForbiddenOnLabelMasterReadApis() throws Exception {
        // given: 서명·issuer·exp 는 모두 유효하지만 LS_USER_ROLE 미배정(role=null) 토큰
        String token = unassignedToken();

        // when/then: 세 경로 모두 매처 단계에서 차단된다.
        //   구 매처(.authenticated())는 ROLE_* 를 요구하지 않아 이 셋을 전건 통과시켰다.
        mockMvc.perform(get("/v1/manage/labels").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/manage/labels/detect-candidates").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/manage/labels/" + ABSENT_LABEL_ID + "/attrs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER_WORKER_PORTAL_USER_는_라벨마스터_읽기_3경로_통과_회귀0")
    void threeRolesStillReadLabelMasters() throws Exception {
        // 인가 축소가 정당한 사용자를 막지 않는지 — 특히 PORTAL_USER 를 빼면
        // 포털 업로드 라벨링 화면(useLabelMasters)이 통째로 깨진다.
        for (String token : List.of(reviewerToken(), workerToken(), portalToken())) {
            mockMvc.perform(get("/v1/manage/labels").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/v1/manage/labels/detect-candidates")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            // 속성 정의 조회는 라벨이 없으면 404 — 403 이 아니라는 것이 "매처를 통과했다" 는 증거다.
            mockMvc.perform(get("/v1/manage/labels/" + ABSENT_LABEL_ID + "/attrs")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("라벨마스터_쓰기는_여전히_REVIEWER_전용")
    void labelMasterWriteStaysReviewerOnly() throws Exception {
        // 읽기 매처는 GET 한정이라 쓰기는 /v1/manage/** REVIEWER 매처 + 메서드 @PreAuthorize 가 막는다.
        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + workerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"게이트확인\",\"color\":\"#ffffff\",\"labelType\":\"BBOX\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★역할_행이_없는_사용자도_role_claim_엔드포인트에는_도달_403_아님")
    void roleNullReachesRoleClaimEndpoint() throws Exception {
        // 매처가 막으면 403 이 된다. 도달하면 관리자 패스워드 미설정/불일치로 401 이 정상.
        //
        // ★표본이 <저장소에 행이 없는 sub> 여야 한다. enum 밖 역할코드 표본(770001)은 자동 등록이
        //   일어나지 않는 인위적 조건이라 프로덕션 부트스트랩 모집단을 대표하지 않는다 — 실제로
        //   그 표본으로 바꾼 탓에 「자동 등록이 부트스트랩을 막는다」는 결함이 전건 GREEN 으로
        //   숨었다. 도달성 자체는 AC-127 시험이 200 까지 확인한다.
        String unseeded = JwtTestSupport.token(
                secret, String.valueOf(UNSEEDED_USER_NO), "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + unseeded)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"adminPassword\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized());
    }
}
