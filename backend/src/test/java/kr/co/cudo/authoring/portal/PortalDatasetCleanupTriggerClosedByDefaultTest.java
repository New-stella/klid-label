package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.security.PortalSystemApiKeyFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사전 공유 키가 <b>비었을 때</b> 이 서버간 창구가 닫히는지 고정한다 (fail-closed).
 *
 * <p>값이 없을 때 <b>열리는 것이 아니라 닫히는</b> 방향이어야 한다. 이 축만 별도 클래스인 이유는
 * 키가 설정으로 주입되는 싱글턴이라 <b>키를 채운 컨텍스트와 같은 컨텍스트에서 검증할 수 없기</b>
 * 때문이다 — 기본 컨텍스트(설정 미지정)가 곧 이 시험의 조건이다.
 *
 * <p>⚠ 언젠가 배포 기본값으로 키를 채우게 되면 이 시험이 깨진다. 그때 시험을 지우지 말고
 * <b>빈 키를 명시로 주입</b>해 조건을 되살릴 것 — 사라지는 것은 조건이지 규칙이 아니다.
 *
 * @design API-244
 * @design AC-1103
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalDatasetCleanupTriggerClosedByDefaultTest {

    @Autowired private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Test
    @DisplayName("★사전_공유_키가_설정되지_않으면_어떤_요청으로도_이_창구에_들어가지_못한다")
    void missingApiKeyClosesTheEndpoint() throws Exception {
        // 키를 실은 요청 · 안 실은 요청 · 포털 토큰을 실은 요청 — 어느 것도 통과하지 못한다.
        //   설정이 비면 비교 대상 자체가 없으므로 값이 무엇이든 일치할 수 없다.
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(PATH)
                        .header(PortalSystemApiKeyFilter.API_KEY_HEADER, "any-key-at-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        String portalToken = JwtTestSupport.token(secret, "portal-user", "PORTAL_USER", "PORTAL", issuer, 600);
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    private static final String PATH = "/v1/portal-system/dataset-cleanups";
    private static final String BODY = "{\"datasetCode\":\"DS0001\",\"version\":\"v1\"}";
}
