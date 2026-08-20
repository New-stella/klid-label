package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비식별 완료 <b>기록</b> API 통합 시험 — {@code /api/v1/videos/{rawSn}/deident-complete} (API-215).
 *
 * <h3>★ 이 시험의 중심은 fail-closed 다</h3>
 * <p>이 계약은 값 하나를 바꿔 <b>검수 승인 보류를 푸는</b> 행위다. 확인 없이 기록만 바꿀 수 있으면
 * 처리되지 않은 산출물이 검수 승인을 통과한다(ADR-048 이 명시한 위험). 그래서 아래 두 축을 함께 고정한다.
 * <ul>
 *   <li>산출물이 <b>실재하지 않으면</b> 아무것도 기록하지 않고 거부한다 — 이력 행도 남지 않고 보류도
 *       그대로다.</li>
 *   <li>확인을 통과하면 이력에 산출물 위치가 적재되고 보류가 풀려 <b>같은 영상의 승인이 진행된다</b>.</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design API-215
 * @design AC-046
 * @design ADR-048
 */
@SpringBootTest(properties = {
        "authoring.storage.external-read-roots=../docs",
        "authoring.storage.raw-path=build/tmp/import-it/raw",
        "authoring.storage.deidentified-path=build/tmp/import-it/deid"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportDeidentCompleteControllerIT {

    private static final String IMPORTS = "/v1/imports";
    private static final String MAPPINGS = "/v1/import-mappings";

    private static final String MAPPING_LABEL_NAME = "이관비식별라벨";
    private static final String EVENT_TYPE_CD = "IMPTEST02";
    private static final Path STORAGE_ROOT = Paths.get("build", "tmp", "import-it");
    /** 확인을 통과해야 하는 폴더 — 허용 범위 안에 있고 일반 파일이 하나 있다. */
    private static final Path ARTIFACT_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid");
    /** 확인을 통과하면 안 되는 폴더 — 허용 범위 안이지만 <b>산출물이 없다</b>. */
    private static final Path EMPTY_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid-empty");
    /** 확인을 통과하면 안 되는 폴더 — 파일은 있으나 <b>내용이 비어 있다</b>. */
    private static final Path HOLLOW_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid-hollow");

    @Autowired private MockMvc mockMvc;
    @Autowired private EventTypeCacheEvictor eventTypeCacheEvictor;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String reviewerToken;
    private String workerToken;
    private long labelId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
        labelId = insertLabel();
        insertEventType();
        confirmMappings();
        Files.createDirectories(ARTIFACT_DIR);
        Files.write(ARTIFACT_DIR.resolve("00000001-mask.jpg"), new byte[]{1, 2, 3});
        Files.createDirectories(EMPTY_DIR);
        Files.createDirectories(HOLLOW_DIR);
        Files.write(HOLLOW_DIR.resolve("00000001-mask.jpg"), new byte[0]);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    @DisplayName("산출물이_실재하지_않으면_아무것도_기록하지_않고_거부한다")
    void 산출물이_실재하지_않으면_아무것도_기록하지_않고_거부한다() throws Exception {
        long rawSn = importOriginal();

        // ★ 이 단언이 이 계약의 fail-closed 지점이다. 실재 확인을 빼면 여기가 200 이 되고,
        //   처리되지 않은 산출물이 검수 승인을 통과한다.
        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(EMPTY_DIR.toAbsolutePath().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        // 이력도 남지 않고 보류도 그대로다 — 기록이 반쯤 되는 상태를 만들지 않는다.
        assertThat(procLogCount(rawSn)).isZero();
        assertThat(deidentCompleted(rawSn)).isEqualTo("N");

        // ★ 내용이 없는 파일도 산출물로 세지 않는다. 존재만 보면 <b>빈 파일 하나</b>로 보류가 풀려
        //   확인이 사실상 없는 것과 같아진다 — 저작도구 자체 비식별 경로가 이미 요구하는 강도다.
        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(HOLLOW_DIR.toAbsolutePath().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        assertThat(procLogCount(rawSn)).isZero();
        assertThat(deidentCompleted(rawSn)).isEqualTo("N");

        // 허용 범위 밖도 같은 방식으로 거부된다(표기가 아니라 실제로 닿는 자리 기준).
        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("/etc")))
                .andExpect(status().isBadRequest());
        assertThat(procLogCount(rawSn)).isZero();
        assertThat(deidentCompleted(rawSn)).isEqualTo("N");
    }

    @Test
    @DisplayName("산출물을_확인하면_이력에_위치가_적재되고_보류가_풀려_승인이_진행된다")
    void 산출물을_확인하면_이력에_위치가_적재되고_보류가_풀려_승인이_진행된다() throws Exception {
        long rawSn = importOriginal();

        mockMvc.perform(post("/v1/reviews/" + rawSn + "/start")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        // 보류가 서 있는 동안에는 승인이 막힌다.
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ARTIFACT_DIR.toAbsolutePath().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.approvalHoldReleased").value(true))
                .andExpect(jsonPath("$.data.procLogSn").isNumber());

        // 비식별 산출물의 위치는 적재값을 읽어 쓴다 — 이름을 조합하거나 추측하지 않는다.
        Map<String, Object> procLog = jdbc.queryForMap(
                "SELECT proc_stts_cd, de_idntf_file_path_nm FROM ls_deident_proc_log "
                        + "WHERE data_raw_sn = ?", rawSn);
        assertThat(procLog.get("proc_stts_cd")).isEqualTo("SUCCEEDED");
        assertThat((String) procLog.get("de_idntf_file_path_nm"))
                .isEqualTo(ARTIFACT_DIR.toAbsolutePath().toRealPath().toString());
        assertThat(deidentCompleted(rawSn)).isEqualTo("Y");

        // 보류가 풀리면 승인이 정상으로 진행된다.
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT data_stts_cd FROM ls_raw_data_status WHERE raw_data_id = ?",
                String.class, rawSn)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("이미_비식별_완료로_기록된_영상에는_다시_기록하지_않는다")
    void 이미_비식별_완료로_기록된_영상에는_다시_기록하지_않는다() throws Exception {
        // 비식별이 끝났다고 지정해 가져온 영상은 애초에 보류가 서지 않는다.
        long rawSn = importDeidentified();

        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ARTIFACT_DIR.toAbsolutePath().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        // 두 번 기록하면 어느 산출물이 그 영상의 비식별 결과인지 이력에서 갈린다 — 적재 시 남긴 1건뿐이다.
        assertThat(procLogCount(rawSn)).isEqualTo(1L);
    }

    @Test
    @DisplayName("이_경로로_들어온_영상이_아니면_거부한다")
    void 이_경로로_들어온_영상이_아니면_거부한다() throws Exception {
        Long rawSn = jdbc.queryForObject(
                "INSERT INTO ls_data_raw (vms_clip_id, evnt_type_cd, lclgv_cd, prvc_type_cd, "
                        + "raw_file_path_nm, de_ident_yn, src_type, data_stts_cd, reg_dt) "
                        + "VALUES (?, ?, '11680', 'ANONY', '/var/raw/clip.mp4', 'N', 'CONTROL', "
                        + "'PENDING', CURRENT_TIMESTAMP) RETURNING raw_sn",
                Long.class, "IMPORT-IT-NOT-IMPORTED-" + System.nanoTime(), EVENT_TYPE_CD);
        jdbc.update("INSERT INTO ls_raw_data_status (raw_data_id, data_stts_cd, stp_cycl, igi_cycl, "
                + "upd_dt, ver, de_idntf_cmptn_yn) VALUES (?, 'PENDING', 0, 0, CURRENT_TIMESTAMP, 0, 'N')",
                rawSn);
        try {
            mockMvc.perform(post(url(rawSn))
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ARTIFACT_DIR.toAbsolutePath().toString())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
            assertThat(procLogCount(rawSn)).isZero();
        } finally {
            jdbc.update("DELETE FROM ls_raw_data_status WHERE raw_data_id = ?", rawSn);
            jdbc.update("DELETE FROM ls_data_raw WHERE raw_sn = ?", rawSn);
        }
    }

    @Test
    @DisplayName("없는_영상은_찾을_수_없음이고_검수자가_아니면_권한_없음이다")
    void 없는_영상은_찾을_수_없음이고_검수자가_아니면_권한_없음이다() throws Exception {
        mockMvc.perform(post(url(9_999_999L))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ARTIFACT_DIR.toAbsolutePath().toString())))
                .andExpect(status().isNotFound());

        long rawSn = importOriginal();
        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ARTIFACT_DIR.toAbsolutePath().toString())))
                .andExpect(status().isForbidden());
        assertThat(deidentCompleted(rawSn)).isEqualTo("N");
    }

    // ------------------------------------------------------------------ 보조

    private static String url(long rawSn) {
        return "/v1/videos/" + rawSn + "/deident-complete";
    }

    private String body(String folderPath) throws Exception {
        return objectMapper.writeValueAsString(Map.of("deidentifiedFolderPath", folderPath));
    }

    /** 원본이라고 지정하고 영상 파일 없이 프레임만 가져온다 — 보류가 서는 유일한 조합이다. */
    private long importOriginal() throws Exception {
        return doImport(false);
    }

    private long importDeidentified() throws Exception {
        return doImport(true);
    }

    private long doImport(boolean deidentified) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("folderPath", ImportSampleFolder.path().toString());
        body.put("deidentified", deidentified);
        MvcResult result = mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("rawSn").asLong();
    }

    private void confirmMappings() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("kind", "LABEL",
                        "externalCode", ImportSampleFolder.LABEL_CATEGORY,
                        "externalName", "도로",
                        "labelId", labelId),
                Map.of("kind", "EVNT_TYPE",
                        "externalCode", ImportSampleFolder.EVENT_CATEGORY,
                        "externalName", ImportSampleFolder.EVENT_CATEGORY,
                        "evntTypeCd", EVENT_TYPE_CD))));
        mockMvc.perform(post(MAPPINGS)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String deidentCompleted(long rawSn) {
        return jdbc.queryForObject(
                "SELECT de_idntf_cmptn_yn FROM ls_raw_data_status WHERE raw_data_id = ?",
                String.class, rawSn);
    }

    private long procLogCount(long rawSn) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_deident_proc_log WHERE data_raw_sn = ?", Long.class, rawSn);
    }

    private long insertLabel() {
        jdbc.update("DELETE FROM ls_label WHERE lbl_nm = ?", MAPPING_LABEL_NAME);
        return jdbc.queryForObject(
                "INSERT INTO ls_label (lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_dt) "
                        + "VALUES (?, '#00FF00', 'POLYGON', 997, 'Y', CURRENT_TIMESTAMP) RETURNING lbl_id",
                Long.class, MAPPING_LABEL_NAME);
    }

    private void insertEventType() {
        jdbc.update("INSERT INTO ls_evnt_type (evnt_type_cd, evnt_nm, clct_yn, reg_dt) "
                + "VALUES (?, ?, 'Y', CURRENT_TIMESTAMP) ON CONFLICT (evnt_type_cd) DO NOTHING",
                EVENT_TYPE_CD, "이관비식별시험");
        // 등록 유형 판정이 장수명 캐시를 타므로, 새로 넣은 유형이 보이도록 캐시를 비운다.
        //   비우지 않으면 앞선 시험이 채운 스냅샷 때문에 이 유형이 "미등록"으로 판정된다.
        eventTypeCacheEvictor.evictNow();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ls_data_lbl WHERE src_sn IN (SELECT src_sn FROM ls_data_src "
                + "WHERE raw_sn IN (SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED'))");
        jdbc.update("DELETE FROM ls_data_src WHERE raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_data_meta WHERE raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_deident_proc_log WHERE data_raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_dataset_export WHERE data_raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_dataset_video_meta WHERE raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_raw_data_status WHERE raw_data_id IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_otsd_datst_trnsf_hstry");
        jdbc.update("DELETE FROM ls_data_raw WHERE src_type = 'IMPORTED'");
        jdbc.update("DELETE FROM ls_otsd_ctgry_mpng");
        jdbc.update("DELETE FROM ls_label WHERE lbl_nm = ?", MAPPING_LABEL_NAME);
        jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd = ?", EVENT_TYPE_CD);
        eventTypeCacheEvictor.evictNow();
        deleteStorage();
    }

    private static void deleteStorage() {
        Path root = STORAGE_ROOT.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
