package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「내 작업」 목록 창구의 <b>계약면</b> — 인가 · 정렬 allowlist · 응답 모양. @design API-225
 *
 * <p>목록 내용의 정확성은 실 DB 시험({@code PortalUserWorkListIT})이 맡고, 여기서는 <b>요청이 어디까지
 * 들어오는가</b>만 고정한다. 정렬 키를 allowlist 로 거르지 않으면 미등록 키가 리포지토리로 직행해
 * 500 + 내부 컬럼명 노출이 된다(CWE-20/89/209).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalUserWorkControllerTest {

    private static final String PATH = "/v1/portal/user-works";
    private static final String ALICE = "work-ctl-alice";

    @Autowired private MockMvc mockMvc;
    @Autowired private PortalUploadAssetRepository assetRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String portalToken;
    private String reviewerToken;

    @BeforeEach
    void setUp() {
        portalToken = JwtTestSupport.token(secret, ALICE, "PORTAL_USER", "PORTAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @AfterEach
    void cleanup() {
        assetRepository.findPageByOwner(ALICE, null, PageRequest.of(0, 1000)).getContent()
                .forEach(u -> assetRepository.deleteOwned(u.uldSn(), ALICE));
    }

    @Test
    @DisplayName("포털_사용자는_페이지_구조로_목록을_받는다")
    void portalUserGetsPageEnvelope() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").exists())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.number").value(0));
    }

    @Test
    @DisplayName("토큰이_없으면_401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    /** 외부 채널 전용 창구다 — 내부 역할이 통과하면 채널 격리가 무너진다. */
    @Test
    @DisplayName("★내부_채널_역할은_접근하지_못한다")
    void internalRoleIsForbidden() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★allowlist_밖의_정렬_키는_400")
    void unknownSortKeyIsRejected() throws Exception {
        mockMvc.perform(get(PATH).param("sort", "rawFilePathNm,desc")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("기본_정렬_키는_파라미터_없이도_통과한다")
    void defaultSortKeyIsAllowed() throws Exception {
        mockMvc.perform(get(PATH).param("sort", "lastSavedAt,desc")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk());
    }

    /** 페이지 크기 상한 — 넘겨도 거부가 아니라 상한으로 클램프한다(API-225). */
    @Test
    @DisplayName("페이지_크기가_상한을_넘으면_상한으로_클램프한다")
    void oversizedPageIsClamped() throws Exception {
        mockMvc.perform(get(PATH).param("size", "5000")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }
}
