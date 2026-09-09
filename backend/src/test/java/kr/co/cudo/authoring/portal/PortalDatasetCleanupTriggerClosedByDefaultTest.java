package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.security.PortalSystemSubjectPolicy;
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
 * 시스템 계정 주체 식별자 목록이 <b>비었을 때</b> 시스템 주체 창구가 닫히는지 고정한다 (fail-closed).
 *
 * <p>값이 없을 때 <b>열리는 것이 아니라 닫히는</b> 방향이어야 한다. 이 축만 별도 클래스인 이유는
 * 목록이 설정으로 주입되는 싱글턴이라 <b>목록을 채운 컨텍스트와 같은 컨텍스트에서 검증할 수 없기</b>
 * 때문이다 — 기본 컨텍스트(설정 미지정)가 곧 이 시험의 조건이다.
 *
 * <p>⚠ 언젠가 배포 기본값으로 목록을 채우게 되면 이 시험이 깨진다. 그때 시험을 지우지 말고
 * <b>빈 목록을 명시로 주입</b>해 조건을 되살릴 것 — 사라지는 것은 조건이지 규칙이 아니다.
 *
 * @design API-244
 * @design AC-1103
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalDatasetCleanupTriggerClosedByDefaultTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PortalSystemSubjectPolicy policy;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Test
    @DisplayName("★시스템_계정_주체_목록이_비면_어떤_포털_토큰으로도_시스템_주체_창구에_들어가지_못한다")
    void emptySubjectListClosesTheEndpoint() throws Exception {
        assertThat(policy.registeredCount())
                .as("이 시험의 전제 — 기본 형상에는 시스템 계정 주체가 등록돼 있지 않다")
                .isZero();

        // 목록에 없으므로 어떤 주체를 써도 시스템 주체로 판정되지 않는다.
        for (String subject : new String[]{"portal-sys-acct", "anything", "9001"}) {
            String token = JwtTestSupport.token(secret, subject, "PORTAL_USER", "PORTAL", issuer, 600);
            mockMvc.perform(post("/v1/portal-system/dataset-cleanups")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"datasetCode\":\"DS0001\",\"version\":\"v1\"}"))
                    .andExpect(status().isForbidden());
        }
    }
}
