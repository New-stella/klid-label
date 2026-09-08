package kr.co.cudo.authoring.aiserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.auth.repository.LsMngrPswdRepository;
import kr.co.cudo.authoring.auth.service.AdminPasswordVerifier;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 장비 관리 창구의 <b>HTTP 계약</b> 검증.
 * [@design API-226] [@design API-227] [@design API-228] [@design API-229] [@design API-230]
 * [@design AC-1088] [@design AC-1089] [@design AC-1090] [@design AC-1091]
 *
 * <h3>왜 전체 스택으로 도는가</h3>
 * <p>이 창구의 핵심 계약 중 <b>둘은 컨트롤러 코드에 없다</b> — 역할 게이트는 보안 설정의 순서 있는
 * 매처와 표기가 함께 정하고, 유효창 요구는 인터셉터가 건다. 서비스만 부르는 시험은 「검수자가 쓰기에
 * 닿는다」는 회귀를 한 건도 잡지 못한다.
 *
 * <h3>거부 문구에 입력이 드러나지 않는지도 함께 본다</h3>
 * <p>주소 거부 사유에 입력 원문·호스트·해석 결과가 실리면 <b>그 응답 자체가 내부망을 훑는 수단</b>이
 * 된다(CWE-209). 상태코드만 단언하면 그 회귀가 통과한다.
 *
 * <p>★ 역할 행과 관리자 자격은 이 시험이 직접 심고 {@code @Transactional} 이 되돌린다. 공유 DB 에
 * 관리자 행이 남으면 부트스트랩 창구를 검증하는 시험들이 409 로 뒤집힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AiSrvrAdminApiIT {

    private static final String PATH = "/v1/manage/ai-servers";
    private static final String SESSION_PATH = "/v1/manage/admin-session";

    /** 시드 사용자와 겹치지 않는 대역 — 겹치면 역할 해석 캐시가 남의 키를 쓴다. */
    private static final String ADMIN_SUB = "9700001";
    private static final String REVIEWER_SUB = "9700002";

    /**
     * ★유효창은 <b>클래스당 한 번만</b> 연다.
     *
     * <p>발급 창구에는 분 단위 <b>전역</b> 속도 제한이 걸려 있다(계정 축과 달리 사용자를 바꿔도
     * 피할 수 없다). 시험마다 열면 이 클래스 하나가 그 예산을 통째로 먹어, 같은 분에 도는 다른
     * 시험이 429 로 무너진다 — 실제로 이 저장소에 그 유형의 flaky 이력이 있다.
     *
     * <p>토큰 검증은 서명·만료·주체 결박만 보므로(무상태) 시험 사이에 재사용해도 안전하다. 만료가
     * 가까우면 그때만 다시 연다.
     */
    private static String cachedSessionToken;
    private static Instant cachedSessionExpiresAt;
    private static String cachedAdminPlaintext;

    /**
     * ★해시도 <b>한 번만</b> 만들어 재사용한다 — 매번 다시 인코딩하면 유효창이 전부 끊긴다.
     *
     * <p>유효창 서명 키는 <b>현재 관리자 자격 해시</b>에서 파생된다(자격이 바뀌면 과거 유효창이
     * 상태 없이 무효가 되도록). bcrypt 는 매번 다른 소금을 써서 같은 평문이라도 해시가 달라지므로,
     * 시험마다 다시 인코딩해 심으면 <b>직전에 받은 유효창이 그 순간 무효</b>가 되어 쓰기 요청이
     * 전건 403 이 된다. 그 403 은 인가 결함처럼 보이지만 원인은 이 자리다.
     */
    private static String cachedAdminHash;

    /** 남은 시간이 이보다 적으면 다시 연다 — 시험 도중 만료로 403 이 나지 않게. */
    private static final Duration SESSION_RENEW_MARGIN = Duration.ofMinutes(2);

    /**
     * 유효창 수명을 <b>실제보다 짧게</b> 가정한다(기본 10분, 상한 30분).
     *
     * <p>짧게 잡으면 필요 없는 재발급이 한 번 더 날 뿐이고, 길게 잡으면 시험 도중 만료로 403 이
     * 난다 — 틀릴 때 안전한 쪽으로 틀리는 값을 고른다.
     */
    private static final Duration CONSERVATIVE_SESSION_LIFETIME = Duration.ofMinutes(7);

    @Autowired private MockMvc mockMvc;
    @Autowired private LsAiSrvrRepository repository;
    @Autowired private LsUserRoleRepository lsUserRoleRepository;
    @Autowired private LsMngrPswdRepository mngrPswdRepository;
    @Autowired private UserRoleResolver userRoleResolver;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String adminToken;
    private String reviewerToken;
    private String adminSessionToken;

    @BeforeEach
    void seed() {
        // 원장을 비운 상태에서 출발한다 — 기동 씨앗과 앞선 시험의 잔재가 섞이면 건수 단언이 흔들린다.
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_usg");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");

        // 역할 행은 시험마다 심고 @Transactional 이 되돌린다. 공유 DB 에 관리자 행이 남으면
        // 부트스트랩 창구를 검증하는 시험들이 409 로 뒤집힌다.
        lsUserRoleRepository.save(LsUserRole.of(Long.valueOf(ADMIN_SUB), "ADMIN"));
        lsUserRoleRepository.save(LsUserRole.of(Long.valueOf(REVIEWER_SUB), "REVIEWER"));
        userRoleResolver.evict(Long.valueOf(ADMIN_SUB));
        userRoleResolver.evict(Long.valueOf(REVIEWER_SUB));
        adminToken = JwtTestSupport.token(secret, ADMIN_SUB, "ADMIN", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, REVIEWER_SUB, "REVIEWER", "INTERNAL", issuer, 60);

        // 평문 상수를 두지 않는다(Fortify Hardcoded Password) — 한 번 뽑아 해시만 심는다.
        if (cachedAdminPlaintext == null) {
            byte[] pw = new byte[24];
            new SecureRandom().nextBytes(pw);
            cachedAdminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pw);
        }
        if (cachedAdminHash == null) {
            cachedAdminHash = new BCryptPasswordEncoder(AdminPasswordVerifier.BCRYPT_COST)
                    .encode(cachedAdminPlaintext);
        }
        String hash = cachedAdminHash;
        LsMngrPswd row = mngrPswdRepository.findById(LsMngrPswd.SINGLE_ROW_SN).orElse(null);
        if (row == null) {
            mngrPswdRepository.save(LsMngrPswd.of(hash, "1", LocalDateTime.now()));
        } else {
            row.changeHash(hash, "1", LocalDateTime.now());
            mngrPswdRepository.save(row);
        }
        adminSessionToken = adminSessionToken();
    }

    /** 캐시된 유효창을 쓰되, 만료가 가까우면 그때만 다시 연다(전역 속도 제한 절약). */
    private String adminSessionToken() {
        if (cachedSessionToken != null && cachedSessionExpiresAt != null
                && Instant.now().plus(SESSION_RENEW_MARGIN).isBefore(cachedSessionExpiresAt)) {
            return cachedSessionToken;
        }
        try {
            openAdminSession(cachedAdminPlaintext);
        } catch (Exception e) {
            throw new IllegalStateException("관리자 유효창을 열지 못했습니다.", e);
        }
        return cachedSessionToken;
    }

    // ---- 인가 축 (AC-1090) ----------------------------------------------------------------

    @Test
    @DisplayName("★검수자는_장비_목록을_조회할_수_있다")
    void 검수자는_장비_목록을_조회할_수_있다() throws Exception {
        // 조회까지 관리자로 올리면 장비 상태를 확인할 길이 없어진다.
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("★검수자는_장비를_등록할_수_없다_403")
    void 검수자는_장비를_등록할_수_없다() throws Exception {
        // 계층은 관리자가 검수자 자리를 통과하게 할 뿐 그 반대는 성립하지 않는다.
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("gpu01", "http://10.0.0.11:9300", "INFERENCE")))
                .andExpect(status().isForbidden());
        assertThat(repository.findById("gpu01")).isEmpty();
    }

    @Test
    @DisplayName("★관리자라도_유효창이_없으면_등록할_수_없다_403")
    void 관리자라도_유효창이_없으면_등록할_수_없다() throws Exception {
        // 유효창은 권한을 대체하지 않고 가산된다. 거부 사유는 권한 부족과 구분해 알리지 않는다.
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("gpu01", "http://10.0.0.11:9300", "INFERENCE")))
                .andExpect(status().isForbidden());
        assertThat(repository.findById("gpu01")).isEmpty();
    }

    @Test
    @DisplayName("검수자는_상태전이도_삭제도_할_수_없다_403")
    void 검수자는_상태전이도_삭제도_할_수_없다() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");
        register("gpu02", "http://10.0.0.12:9300", "INFERENCE");

        mockMvc.perform(patch(PATH + "/gpu01/status")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srvrSttsCd\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(PATH + "/gpu01")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken))
                .andExpect(status().isForbidden());

        assertThat(repository.findById("gpu01")).isPresent();
    }

    // ---- 등록·목록 축 (AC-1088) ------------------------------------------------------------

    @Test
    @DisplayName("★유형별로_여러_대를_등록하면_유형_필터로_갈라_보인다")
    void 유형별로_여러_대를_등록하면_유형_필터로_갈라_보인다() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");
        register("gpu02", "http://10.0.0.12:9300", "INFERENCE");
        register("vlm01", "https://vlm.internal:8443", "TIMESERIES");

        mockMvc.perform(get(PATH).param("srvrTypeCd", "INFERENCE")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].srvrId").value("gpu01"))
                .andExpect(jsonPath("$.data[1].srvrId").value("gpu02"));

        mockMvc.perform(get(PATH).param("srvrTypeCd", "TIMESERIES")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].srvrId").value("vlm01"))
                .andExpect(jsonPath("$.data[0].srvrSttsCd").value("AVAILABLE"));

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("같은_식별자를_두_번_등록하면_409")
    void 같은_식별자를_두_번_등록하면_409() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");

        mockMvc.perform(adminPost(createBody("gpu01", "http://10.0.0.99:9300", "INFERENCE")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("식별자_형식을_어기면_400_이고_원장에_남지_않는다")
    void 식별자_형식을_어기면_400_이고_원장에_남지_않는다() throws Exception {
        // ⚠구 표본 폐기(2026-09-08): "klid-ai-gpu-01" 이었다. 하이픈이 허용되면서 그 값은 <정상>이다.
        //   여전히 위반인 것(대문자)으로 바꾼다 — 이 값은 기록·메트릭 라벨에 그대로 실린다.
        mockMvc.perform(adminPost(createBody("KLID_GPU01", "http://10.0.0.11:9300", "INFERENCE")))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("★하이픈_밑줄_식별자는_201_로_등록된다_구_거부_폐기")
    void 하이픈_밑줄_식별자는_201_로_등록된다() throws Exception {
        mockMvc.perform(adminPost(createBody("gpu-02", "http://10.0.0.12:9300", "INFERENCE")))
                .andExpect(status().isCreated());
        mockMvc.perform(adminPost(createBody("infer_gpu3", "http://10.0.0.13:9300", "INFERENCE")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("장비_유형을_주지_않으면_400")
    void 장비_유형을_주지_않으면_400() throws Exception {
        mockMvc.perform(adminPost("""
                {"srvrId":"gpu01","srvrAddr":"http://10.0.0.11:9300"}
                """))
                .andExpect(status().isBadRequest());
    }

    // ---- 주소 검증 축 (AC-1089) ------------------------------------------------------------

    @Test
    @DisplayName("★평문_http_와_사설_대역_주소는_등록된다")
    void 평문_http_와_사설_대역_주소는_등록된다() throws Exception {
        // 연동 대상이 내부망 별도 장비에 있는 것이 통상이라, 대역으로 막으면 정당한 대상을 막는다.
        mockMvc.perform(adminPost(createBody("gpu01", "http://10.0.0.11:9300", "INFERENCE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.srvrAddr").value("http://10.0.0.11:9300"));
        mockMvc.perform(adminPost(createBody("gpu02", "http://192.168.10.7:9300", "INFERENCE")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("★클라우드_메타데이터_주소는_400_이고_거부_문구에_입력이_드러나지_않는다")
    void 클라우드_메타데이터_주소는_400_이고_거부_문구에_입력이_드러나지_않는다() throws Exception {
        String imds = "http://169.254.169.254/latest/meta-data";

        MvcResult result = mockMvc.perform(adminPost(createBody("gpu01", imds, "INFERENCE")))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("거부 문구가 입력 원문·호스트를 되돌려주면 그 응답이 내부망을 훑는 수단이 된다")
                .doesNotContain(imds)
                .doesNotContain("169.254.169.254");
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("비허용_스킴과_형식_위반_주소는_400")
    void 비허용_스킴과_형식_위반_주소는_400() throws Exception {
        mockMvc.perform(adminPost(createBody("gpu01", "ftp://10.0.0.11/ai", "INFERENCE")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(adminPost(createBody("gpu01", "not-a-url", "INFERENCE")))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("주소_수정도_같은_검증을_받는다")
    void 주소_수정도_같은_검증을_받는다() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");

        mockMvc.perform(patch(PATH + "/gpu01")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srvrAddr\":\"http://169.254.169.254/\"}"))
                .andExpect(status().isBadRequest());

        assertThat(repository.findById("gpu01").orElseThrow().getSrvrAddr())
                .isEqualTo("http://10.0.0.11:9300");
    }

    @Test
    @DisplayName("보내지_않은_항목은_수정되지_않는다")
    void 보내지_않은_항목은_수정되지_않는다() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");

        mockMvc.perform(patch(PATH + "/gpu01")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srvrNm\":\"klid-ai-gpu-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.srvrNm").value("klid-ai-gpu-01"))
                .andExpect(jsonPath("$.data.srvrAddr").value("http://10.0.0.11:9300"));
    }

    // ---- 상태 전이·마지막 가용 장비 축 (AC-1091) ---------------------------------------------

    @Test
    @DisplayName("★같은_상태로의_전이는_409")
    void 같은_상태로의_전이는_409() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");
        register("gpu02", "http://10.0.0.12:9300", "INFERENCE");

        // 상태가 안 바뀌는 갱신은 전이가 아니라 아무것도 아니다.
        mockMvc.perform(statusPatch("gpu01", "AVAILABLE"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("★허용되지_않는_전이는_409")
    void 허용되지_않는_전이는_409() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");
        register("gpu02", "http://10.0.0.12:9300", "INFERENCE");
        mockMvc.perform(statusPatch("gpu01", "DRAINING")).andExpect(status().isOk());

        // 정비중 -> 이용불가는 사람이 세운 정비 상태를 기계 관측이 덮어쓰는 방향이라 막혀 있다.
        mockMvc.perform(statusPatch("gpu01", "UNAVAILABLE"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("★그_유형의_마지막_가용장비는_내릴_수도_지울_수도_없다_409")
    void 그_유형의_마지막_가용장비는_내릴_수도_지울_수도_없다() throws Exception {
        // 다른 유형에는 가용 장비가 있다 — 전체 축으로 세면 통과해 버리는 조합이다.
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");
        register("vlm01", "https://vlm.internal:8443", "TIMESERIES");

        mockMvc.perform(statusPatch("gpu01", "DISABLED"))
                .andExpect(status().isConflict());
        mockMvc.perform(delete(PATH + "/gpu01")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken))
                .andExpect(status().isConflict());

        assertThat(repository.findById("gpu01").orElseThrow().getSrvrSttsCd())
                .isEqualTo(AiSrvrStatus.AVAILABLE);
    }

    @Test
    @DisplayName("가용장비가_둘이면_하나는_내리고_지울_수_있다")
    void 가용장비가_둘이면_하나는_내리고_지울_수_있다() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");
        register("gpu02", "http://10.0.0.12:9300", "INFERENCE");

        mockMvc.perform(statusPatch("gpu02", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.srvrSttsCd").value("DISABLED"));
        mockMvc.perform(delete(PATH + "/gpu02")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken))
                .andExpect(status().isNoContent());

        assertThat(repository.findById("gpu02")).isEmpty();
    }

    @Test
    @DisplayName("없는_장비는_수정도_전이도_삭제도_404")
    void 없는_장비는_수정도_전이도_삭제도_404() throws Exception {
        mockMvc.perform(patch(PATH + "/nosuch01")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srvrNm\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(statusPatch("nosuch01", "DISABLED"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(PATH + "/nosuch01")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(AdminSessionGate.HEADER, adminSessionToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("알_수_없는_상태값은_400")
    void 알_수_없는_상태값은_400() throws Exception {
        register("gpu01", "http://10.0.0.11:9300", "INFERENCE");

        mockMvc.perform(statusPatch("gpu01", "ZOMBIE"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------

    private void openAdminSession(String plaintext) throws Exception {
        MvcResult result = mockMvc.perform(post(SESSION_PATH)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminPassword\":\"" + plaintext + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("token").isTextual())
                .as("관리자 유효창 응답에서 토큰을 찾지 못했습니다: " + data)
                .isTrue();
        cachedSessionToken = data.path("token").asText();
        // ★응답의 만료 시각을 파싱하지 않는다 — 직렬화 형태(ISO 문자열/에폭 숫자)는 설정에 따라
        //   갈리고, 그 차이로 시험이 깨지면 원인이 이 창구와 무관한 곳에 있다. 재발급 판단에만
        //   쓰는 값이므로 <실제 수명보다 짧게> 잡아 두면 언제나 안전한 쪽으로 틀린다.
        cachedSessionExpiresAt = Instant.now().plus(CONSERVATIVE_SESSION_LIFETIME);
    }

    private void register(String srvrId, String srvrAddr, String srvrTypeCd) throws Exception {
        mockMvc.perform(adminPost(createBody(srvrId, srvrAddr, srvrTypeCd)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPost(String body) {
        return post(PATH)
                .header("Authorization", "Bearer " + adminToken)
                .header(AdminSessionGate.HEADER, adminSessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statusPatch(
            String srvrId, String next) {
        return patch(PATH + "/" + srvrId + "/status")
                .header("Authorization", "Bearer " + adminToken)
                .header(AdminSessionGate.HEADER, adminSessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"srvrSttsCd\":\"" + next + "\"}");
    }

    private static String createBody(String srvrId, String srvrAddr, String srvrTypeCd) {
        return "{\"srvrId\":\"" + srvrId + "\",\"srvrAddr\":\"" + srvrAddr
                + "\",\"srvrTypeCd\":\"" + srvrTypeCd + "\"}";
    }

    @SuppressWarnings("unused")
    private LsAiSrvr reload(String srvrId) {
        return repository.findById(srvrId).orElseThrow();
    }
}
