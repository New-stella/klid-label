package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.controller.PortalMaterialsController;
import kr.co.cudo.authoring.portal.dto.PortalMaterialsState;
import kr.co.cudo.authoring.portal.dto.PortalMaterialsStatusResponse;
import kr.co.cudo.authoring.portal.service.PortalMaterialsFailureReason;
import kr.co.cudo.authoring.portal.service.PortalMaterialsProvisionService;
import kr.co.cudo.authoring.portal.service.PortalMaterialsSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조달 창구의 계약면(경로 · 상태코드 · 응답 형태)을 고정한다.
 *
 * <h3>★ 인가는 여기서 검증하지 않는다 — 그 자리가 다르다</h3>
 * <p>인가의 1차 원천은 {@code SecurityConfig} 의 순서 있는 매처({@code /v1/portal/**})이고, 이 시험은
 * 필터 없이 컨트롤러만 세운다. 그래서 <b>여기서 통과한다는 것이 인가가 열렸다는 뜻이 아니다</b>.
 * 이 구분을 적어 두지 않으면 다음 사람이 이 시험을 인가 가드로 오해한다.
 *
 * <h3>★ 착수 응답이 상태와 무관하게 202 인 것은 의도다</h3>
 * <p>착수는 멱등이라 이미 준비됐거나 진행 중이어도 <b>접수 응답</b>을 낸다. 상태 구분은 본문의
 * 상태 값이 싣는다 — 상태코드로 가르면 클라이언트가 코드마다 분기해야 한다.
 *
 * <h3>★ 내부 경로가 응답에 실리지 않는다</h3>
 * <p>요약 형태에 조달처 절대경로·저장소 루트가 <b>애초에 들어갈 자리가 없다</b>는 것을 형태로 본다.
 *
 * @design INT-014
 */
class PortalMaterialsControllerTest {

    private PortalMaterialsProvisionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PortalMaterialsProvisionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PortalMaterialsController(service)).build();
    }

    @Test
    @DisplayName("착수는_접수만_답한다_202")
    void provisionReturnsAccepted() throws Exception {
        when(service.start(4704L))
                .thenReturn(PortalMaterialsStatusResponse.of(4704L, PortalMaterialsState.IN_PROGRESS));

        mockMvc.perform(post("/v1/portal/datasets/4704/materials"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.datasetId").value(4704));
    }

    @Test
    @DisplayName("★이미_준비된_대상의_착수도_같은_202다_상태는_본문이_싣는다")
    void provisionOnReadyAlsoReturnsAccepted() throws Exception {
        when(service.start(anyLong())).thenReturn(PortalMaterialsStatusResponse.ready(
                4704L, new PortalMaterialsSummary("DS-1", "11.0.0", null, 12, 345L, 2, Instant.now())));

        mockMvc.perform(post("/v1/portal/datasets/4704/materials"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("READY"));
    }

    @Test
    @DisplayName("상태_조회는_요약을_함께_돌려주고_내부_경로는_싣지_않는다")
    void statusReturnsSummaryWithoutInternalPaths() throws Exception {
        when(service.status(4704L)).thenReturn(PortalMaterialsStatusResponse.ready(
                4704L, new PortalMaterialsSummary("DS-1", "11.0.0", "A", 12, 345L, 2, Instant.now())));

        mockMvc.perform(get("/v1/portal/datasets/4704/materials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"))
                .andExpect(jsonPath("$.data.materials.entryCount").value(12))
                .andExpect(jsonPath("$.data.materials.totalBytes").value(345))
                .andExpect(jsonPath("$.data.materials.videoCount").value(2))
                // ★ 조달처 절대경로·저장소 루트는 응답 형태에 자리가 없다(CWE-209).
                .andExpect(jsonPath("$.data.materials.localPath").doesNotExist())
                .andExpect(jsonPath("$.data.materials.repoRootDir").doesNotExist());
    }

    @Test
    @DisplayName("실패_상태는_사유를_값으로_싣는다")
    void statusCarriesFailureReason() throws Exception {
        when(service.status(4704L)).thenReturn(PortalMaterialsStatusResponse.failed(
                4704L, PortalMaterialsFailureReason.MATERIAL_PATH_REJECTED));

        mockMvc.perform(get("/v1/portal/datasets/4704/materials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("MATERIAL_PATH_REJECTED"));
    }
}
