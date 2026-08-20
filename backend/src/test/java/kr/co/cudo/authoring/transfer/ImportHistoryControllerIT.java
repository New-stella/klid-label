package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이관 이력 조회 API 통합 시험 — {@code /api/v1/imports}(목록·상세).
 *
 * <h3>이 API 가 없으면 무엇이 깨지는가</h3>
 * <p>이미 가져온 산출물인지 판단할 근거가 없고, 적재가 도중에 깨졌을 때 무엇 때문이었는지를 되짚을
 * 자리가 없다(DFEAT-059).
 *
 * <h3>★ 이 시험이 고정하는 핵심 성질</h3>
 * <p><b>승인 보류 여부는 이관 상태와 다른 축</b>이다. 같은 {@code SUCCESS} 두 건이 서로 다른 보류
 * 값을 가지는 것을 단언한다 — 그 성질이 이 필드가 존재하는 이유이고, 이관 상태로 대신 판단하면
 * 보류를 푸는 자리가 화면에서 도달 불가가 된다.
 *
 * @design DOMAIN-017
 * @design API-207
 * @design API-208
 * @design DFEAT-059
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportHistoryControllerIT {

    private static final String BASE = "/v1/imports";
    private static final String CLIP_PREFIX = "IT-IMP-HIST-";
    private static final String FOLDER_PREFIX = "it-import-history-";

    @Autowired private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String reviewerToken;
    private String workerToken;

    /** 원본으로 가져와 승인 보류가 선 영상. */
    private long heldRawSn;
    /** 비식별이 끝난 것으로 가져와 보류가 없는 영상. */
    private long freeRawSn;

    private long heldTrnsfSn;
    private long freeTrnsfSn;
    private long failedTrnsfSn;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);

        heldRawSn = insertRaw(CLIP_PREFIX + "held");
        freeRawSn = insertRaw(CLIP_PREFIX + "free");
        // 보류는 검수 워크플로 상태가 가진 값이다 — 이 시험은 그 값을 심고 응답이 그것을 읽는지 본다.
        insertStatus(heldRawSn, "N");
        insertStatus(freeRawSn, "Y");

        // 이관 상태는 셋 다 다르게 두되, 앞 둘은 같은 SUCCESS 다(보류 축과 갈리는 것을 보이려고).
        failedTrnsfSn = insertHistory(FOLDER_PREFIX + "c", null, "FAILED", null, null,
                "프레임이 한 건도 없습니다.", "2026-08-19 09:10:00");
        heldTrnsfSn = insertHistory(FOLDER_PREFIX + "a", heldRawSn, "SUCCESS", 120, 340,
                null, "2026-08-19 09:11:00");
        freeTrnsfSn = insertHistory(FOLDER_PREFIX + "b", freeRawSn, "SUCCESS", 7, 9,
                null, "2026-08-19 09:12:00");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        // 표를 통째로 비운다 — 남의 이력이 남아 있으면 건수·순서 단언이 흔들린다(ImportControllerIT 와 동일 관례).
        jdbc.update("DELETE FROM ls_otsd_datst_trnsf_hstry");
        jdbc.update("DELETE FROM ls_raw_data_status WHERE raw_data_id IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE vms_clip_id LIKE ?)", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
    }

    private long insertRaw(String clipId) {
        return jdbc.queryForObject(
                "INSERT INTO ls_data_raw (vms_clip_id, prvc_type_cd, raw_file_path_nm, src_type, reg_dt) "
                        + "VALUES (?, 'PRVC', '/import/none.mp4', 'IMPORTED', CURRENT_TIMESTAMP) "
                        + "RETURNING raw_sn",
                Long.class, clipId);
    }

    private void insertStatus(long rawSn, String deidentCompletedYn) {
        jdbc.update("INSERT INTO ls_raw_data_status "
                        + "(raw_data_id, data_stts_cd, stp_cycl, igi_cycl, upd_dt, ver, revlt_yn, de_idntf_cmptn_yn) "
                        + "VALUES (?, 'PENDING', 0, 0, CURRENT_TIMESTAMP, 0, 'N', ?)",
                rawSn, deidentCompletedYn);
    }

    private long insertHistory(String folderName, Long rawSn, String status, Integer frameCount,
                               Integer labelCount, String failReason, String regDt) {
        return jdbc.queryForObject(
                "INSERT INTO ls_otsd_datst_trnsf_hstry "
                        + "(orgnl_fldr_path_nm, orgnl_fldr_nm, otsd_datst_id, raw_sn, trnsf_stts_cd, "
                        + " frme_cnt, lbl_cnt, fail_rsn, reg_id, reg_dt) "
                        + "VALUES (?, ?, 'DS-2026-001', ?, ?, ?, ?, ?, 'reviewer01', CAST(? AS timestamp)) "
                        + "RETURNING trnsf_sn",
                Long.class, "/import/" + folderName, folderName, rawSn, status,
                frameCount, labelCount, failReason, regDt);
    }

    private String item(long trnsfSn) {
        return "$.data.content[?(@.trnsfSn==" + trnsfSn + ")]";
    }

    @Test
    @DisplayName("★승인_보류_여부는_이관_상태와_다른_축이다_같은_SUCCESS_인데_판정이_갈린다")
    void 승인_보류_여부는_이관_상태와_다른_축이다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                // 이관 상태는 둘 다 같다 — 그런데도
                .andExpect(jsonPath(item(heldTrnsfSn) + ".status").value("SUCCESS"))
                .andExpect(jsonPath(item(freeTrnsfSn) + ".status").value("SUCCESS"))
                // 보류 판정은 갈린다. 이관 상태로 이 값을 대신할 수 없다는 뜻이다.
                .andExpect(jsonPath(item(heldTrnsfSn) + ".approvalHeld").value(true))
                .andExpect(jsonPath(item(freeTrnsfSn) + ".approvalHeld").value(false));
    }

    @Test
    @DisplayName("영상이_없는_실패_이력은_보류_값이_비어_있고_예외가_나지_않는다")
    void 영상이_없는_실패_이력은_보류_값이_비어_있다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].trnsfSn").value(failedTrnsfSn))
                .andExpect(jsonPath("$.data.content[0].rawSn").isEmpty())
                // 없는 것을 "보류 없음(false)"으로 단정하지 않는다 — 그러면 화면이 보류를 풀 자리를 감춘다.
                //   키를 지우지도 않는다 — 계약이 비어 있을 수 있다고 밝힌 값이라 자리를 남긴다.
                .andExpect(jsonPath("$.data.content[0].approvalHeld").isEmpty());
    }

    @Test
    @DisplayName("목록은_최근순으로_정렬되고_상태가_정렬_우선순위로_끼어들지_않는다")
    void 목록은_최근순으로_정렬된다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("size", "100"))
                .andExpect(status().isOk())
                // 가장 최근이 맨 앞. FAILED 가 가장 오래된 건이라 위로 끌어올려지지 않는다.
                .andExpect(jsonPath("$.data.content[0].trnsfSn").value(freeTrnsfSn))
                .andExpect(jsonPath("$.data.content[1].trnsfSn").value(heldTrnsfSn))
                .andExpect(jsonPath("$.data.content[2].trnsfSn").value(failedTrnsfSn));
    }

    @Test
    @DisplayName("목록은_한_쪽씩만_돌려주고_기본_크기는_20이다")
    void 목록은_한_쪽씩만_돌려준다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.number").value(0));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    @DisplayName("쪽_크기_상한을_넘거나_음수_쪽을_요청하면_거부한다")
    void 쪽_크기_상한을_넘으면_거부한다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("size", "101"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("계약에_없는_이관_상태로_거르면_0건이_아니라_거부한다")
    void 계약에_없는_이관_상태로_거르면_거부한다() throws Exception {
        // 조용히 0건을 돌려주면 "그런 이력이 없다"와 "잘못 물었다"가 구분되지 않는다.
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("status", "DONE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("상세는_경로와_실패_사유를_함께_돌려준다")
    void 상세는_경로와_실패_사유를_함께_돌려준다() throws Exception {
        mockMvc.perform(get(BASE + "/" + failedTrnsfSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trnsfSn").value(failedTrnsfSn))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.folderPath").value("/import/" + FOLDER_PREFIX + "c"))
                .andExpect(jsonPath("$.data.externalDatasetId").value("DS-2026-001"))
                .andExpect(jsonPath("$.data.failReason").value("프레임이 한 건도 없습니다."))
                // 상세에는 보류 여부를 싣지 않는다 — 계약에 없는 값을 지어내지 않는다(의도된 비대칭).
                .andExpect(jsonPath("$.data.approvalHeld").doesNotExist());
    }

    @Test
    @DisplayName("없는_이관_이력_상세는_찾을_수_없음이다")
    void 없는_이관_이력_상세는_찾을_수_없음이다() throws Exception {
        mockMvc.perform(get(BASE + "/999999999")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("작업자는_이관_이력을_볼_수_없다")
    void 작업자는_이관_이력을_볼_수_없다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        // 인가가 이력 존재 판정보다 먼저다 — 있는 이력이어도 있는지 없는지가 새지 않는다(CWE-209).
        mockMvc.perform(get(BASE + "/" + heldTrnsfSn).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/999999999").header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증_없이는_이관_이력을_볼_수_없다")
    void 인증_없이는_이관_이력을_볼_수_없다() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("이력이_늘어나도_목록이_한_번에_돌아온다")
    void 이력이_늘어나도_목록이_한_번에_돌아온다() throws Exception {
        for (int i = 0; i < 6; i++) {
            long rawSn = insertRaw(CLIP_PREFIX + "n" + i);
            insertStatus(rawSn, (i % 2 == 0) ? "N" : "Y");
            insertHistory(FOLDER_PREFIX + "n" + i, rawSn, "SUCCESS", 1, 1, null,
                    "2026-08-19 10:0" + i + ":00");
        }

        // 모아 읽기가 제대로 동하는가 — 여러 건이 섞여 있어도 보류 판정이 영상별로 정확하게 붙는다.
        //   (조회 수가 건수에 비례하지 않는다는 사실은 ImportHistoryQueryServiceTest 가 따로 고정한다.)
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(9))
                .andExpect(jsonPath("$.data.content.length()").value(9))
                .andExpect(jsonPath(item(heldTrnsfSn) + ".approvalHeld").value(true))
                .andExpect(jsonPath(item(freeTrnsfSn) + ".approvalHeld").value(false));
    }
}
