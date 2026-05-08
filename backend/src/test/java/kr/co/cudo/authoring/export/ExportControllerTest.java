package kr.co.cudo.authoring.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.export.dto.ExportRequest;
import kr.co.cudo.authoring.export.entity.LsDataSet;
import kr.co.cudo.authoring.export.quartz.ExportJobScheduler;
import kr.co.cudo.authoring.export.repository.LsDataSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ExportControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsDataSetRepository repository;

    /**
     * Quartz Scheduler 호출은 Mock — 본 테스트 범위는 RBAC + 엔티티 PENDING 생성 검증.
     */
    @MockBean private ExportJobScheduler scheduler;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        repository.deleteAll();
        Mockito.doNothing().when(scheduler).schedule(Mockito.anyLong());
    }

    @Test
    @DisplayName("ExportController_WORKER가_export_호출시_403")
    void workerCannotExport() throws Exception {
        ExportRequest req = new ExportRequest(10L, "YOLO", null, null);

        mockMvc.perform(post("/v1/exports/prepare")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ExportController_REVIEWER_export_prepare_정상_PENDING_생성_+_Quartz_트리거")
    void reviewerPreparesExportWithPendingStatus() throws Exception {
        ExportRequest req = new ExportRequest(10L, "YOLO", null, null);

        mockMvc.perform(post("/v1/exports/prepare")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.format").value("YOLO"))
                .andExpect(jsonPath("$.data.pjtId").value(10));

        // 엔티티 검증
        List<LsDataSet> all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getExportSttsCd()).isEqualTo(LsDataSet.STTS_PENDING);
        assertThat(all.get(0).getExportFormat()).isEqualTo(LsDataSet.FORMAT_YOLO);

        // Quartz 트리거 호출 검증
        Mockito.verify(scheduler, Mockito.times(1)).schedule(all.get(0).getExportSn());
    }

    @Test
    @DisplayName("ExportController_REVIEWER_GET_datasets_페이징_응답")
    void reviewerListsDatasetsPaged() throws Exception {
        repository.save(LsDataSet.createPending(10L, "YOLO", "1"));
        repository.save(LsDataSet.createPending(11L, "COCO", "1"));

        mockMvc.perform(get("/v1/exports/datasets")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @DisplayName("ExportController_WORKER가_GET_datasets_호출시_403")
    void workerForbiddenOnDatasetList() throws Exception {
        mockMvc.perform(get("/v1/exports/datasets")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }
}
