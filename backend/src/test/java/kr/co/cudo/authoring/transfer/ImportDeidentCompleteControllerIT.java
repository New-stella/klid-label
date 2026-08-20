package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.dataset.export.DatasetExportOutcome;
import kr.co.cudo.authoring.dataset.export.DatasetExportService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 *   <li>실재하더라도 프레임과 <b>이름이 맞는 파일이 한 건도 없으면</b> 그 폴더는 이 영상의 산출물이
 *       아니므로 거부한다 — 보류도 풀리지 않는다.</li>
 *   <li>확인을 통과하면 이력에 산출물 위치가 적재되고 <b>프레임마다 비식별 이미지 위치가 채워져</b>
 *       보류가 풀리고, 같은 영상의 승인이 진행되며 학습데이터 산출이 <b>성공으로 마감</b>된다.</li>
 *   <li>이름이 맞지 않는 프레임은 <b>비워진 채로 남고 그 수가 응답에 나온다</b> — 순서로 메우지 않는다.</li>
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
    /** 확인을 통과해야 하는 폴더 — 표본의 프레임 이미지 이름과 <b>전부 같은 이름</b>을 가진다. */
    private static final Path ARTIFACT_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid");
    /** 일부만 이름이 맞는 폴더 — 나머지 프레임은 비워진 채로 남아야 한다. */
    private static final Path PARTIAL_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid-partial");
    /** 파일은 실재하지만 <b>이름이 하나도 맞지 않는</b> 폴더 — 이 영상의 산출물이 아니다. */
    private static final Path MISMATCH_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid-other");
    /** 확인을 통과하면 안 되는 폴더 — 허용 범위 안이지만 <b>산출물이 없다</b>. */
    private static final Path EMPTY_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid-empty");
    /** 확인을 통과하면 안 되는 폴더 — 파일은 있으나 <b>내용이 비어 있다</b>. */
    private static final Path HOLLOW_DIR = STORAGE_ROOT.resolve("raw").resolve("outside-deid-hollow");

    @Autowired private MockMvc mockMvc;
    @Autowired private EventTypeCacheEvictor eventTypeCacheEvictor;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatasetExportService datasetExportService;

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
        // 대응은 파일 이름으로만 한다 — 표본 프레임과 같은 이름을 놓아야 이어진다.
        Files.createDirectories(ARTIFACT_DIR);
        for (int i = 1; i <= ImportSampleFolder.FRAME_COUNT; i++) {
            Files.write(ARTIFACT_DIR.resolve(frameFileName(i)), new byte[]{1, 2, 3});
        }
        // 첫 프레임과 <b>중간 프레임</b>만 놓는다 — 순서로 이으면 두 번째 파일이 두 번째 프레임에 붙어
        //   대응이 어긋나는데, 개수만 세면 그 어긋남이 드러나지 않는다.
        Files.createDirectories(PARTIAL_DIR);
        Files.write(PARTIAL_DIR.resolve(frameFileName(1)), new byte[]{1, 2, 3});
        Files.write(PARTIAL_DIR.resolve(frameFileName(5)), new byte[]{1, 2, 3});
        // 이름이 다르면 실재해도 이 영상의 산출물이 아니다 — 순서로 메우면 다른 프레임에 붙는다.
        Files.createDirectories(MISMATCH_DIR);
        Files.write(MISMATCH_DIR.resolve("00000001-mask.jpg"), new byte[]{1, 2, 3});
        Files.createDirectories(EMPTY_DIR);
        Files.createDirectories(HOLLOW_DIR);
        Files.write(HOLLOW_DIR.resolve(frameFileName(1)), new byte[0]);
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
    @DisplayName("산출물을_확인하면_프레임까지_이어지고_보류가_풀려_산출이_성공으로_마감된다")
    void 산출물을_확인하면_프레임까지_이어지고_보류가_풀려_산출이_성공으로_마감된다() throws Exception {
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
                .andExpect(jsonPath("$.data.procLogSn").isNumber())
                .andExpect(jsonPath("$.data.deidentFrameMatchedCount")
                        .value(ImportSampleFolder.FRAME_COUNT))
                .andExpect(jsonPath("$.data.deidentFrameUnmatchedCount").value(0));

        // 비식별 산출물의 위치는 적재값을 읽어 쓴다 — 이름을 조합하거나 추측하지 않는다.
        Map<String, Object> procLog = jdbc.queryForMap(
                "SELECT proc_stts_cd, de_idntf_file_path_nm FROM ls_deident_proc_log "
                        + "WHERE data_raw_sn = ?", rawSn);
        assertThat(procLog.get("proc_stts_cd")).isEqualTo("SUCCEEDED");
        assertThat((String) procLog.get("de_idntf_file_path_nm"))
                .isEqualTo(ARTIFACT_DIR.toAbsolutePath().toRealPath().toString());
        assertThat(deidentCompleted(rawSn)).isEqualTo("Y");

        // 폴더 위치만 남기지 않는다 — 프레임마다 비식별 이미지 위치가 채워져야 한다. 비워 두면 아래
        //   산출이 언제나 부분 성공으로 마감돼 관제가 비식별 이미지 없는 산출물을 받는다.
        assertThat(deidFramePaths(rawSn)).hasSize(ImportSampleFolder.FRAME_COUNT);

        // 프레임의 비식별 이미지는 규약이 정한 자리에 실제로 놓여야 열린다 — 규약 밖이면 산출도
        //   프레임 서빙도 통째로 거부된다.
        mockMvc.perform(get("/v1/frames/" + firstSrcSn(rawSn) + "/deid-image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 보류가 풀리면 승인이 정상으로 진행된다.
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT data_stts_cd FROM ls_raw_data_status WHERE raw_data_id = ?",
                String.class, rawSn)).isEqualTo("APPROVED");

        // ★★ 이 단언이 이 시험의 중심이다. 경로 문자열만 단언하면 프레임이 이어지지 않은 상태도
        //    그대로 통과한다 — 결과로 잇는다. 비식별 벌이 한 장도 없으면 여기가 PARTIAL 이 된다.
        assertThat(datasetExportService.export(rawSn, true))
                .isEqualTo(DatasetExportOutcome.COMPLETED);
        // ⚠ 승인이 띄운 산출이 비동기로 함께 돌아 가장 최근 행이 아직 진행 중일 수 있다. 최신 1건만
        //   보면 그 타이밍에 흔들리므로, 이번 실행이 남긴 <b>성공 마감 행의 존재</b>로 고정한다.
        assertThat(jdbc.queryForList(
                "SELECT output_stts_cd FROM ls_dataset_export WHERE data_raw_sn = ?",
                String.class, rawSn))
                .contains("SUCCEEDED")
                .doesNotContain("PARTIAL");
    }

    @Test
    @DisplayName("이름이_맞지_않는_프레임은_비워진_채로_남고_그_수가_응답에_나온다")
    void 이름이_맞지_않는_프레임은_비워진_채로_남고_그_수가_응답에_나온다() throws Exception {
        long rawSn = importOriginal();
        int unmatched = ImportSampleFolder.FRAME_COUNT - 2;

        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PARTIAL_DIR.toAbsolutePath().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deidentFrameMatchedCount").value(2))
                .andExpect(jsonPath("$.data.deidentFrameUnmatchedCount").value(unmatched));

        // 한 건이라도 이었으면 기록은 성립한다 — 보류는 풀린다.
        assertThat(deidentCompleted(rawSn)).isEqualTo("Y");
        // 나머지는 비워진 채로 남는다(짐작으로 메우지 않는다).
        assertThat(deidFramePaths(rawSn)).hasSize(2);

        // ★★ 개수만 세면 순서로 이어도 통과한다. <b>어느 프레임에 붙었는가</b>를 고정한다 — 순서로
        //    이으면 두 번째 파일이 두 번째 프레임에 붙어 다른 장면의 비식별 이미지가 실리고, 붙고 나면
        //    어느 것이 짐작이었는지 구분할 수 없다.
        assertThat(pairedFileNames(rawSn)).containsExactlyInAnyOrderEntriesOf(Map.of(
                frameFileName(1), frameFileName(1),
                frameFileName(5), frameFileName(5)));
    }

    @Test
    @DisplayName("이름이_맞는_파일이_한_건도_없으면_기록하지_않고_보류가_유지된다")
    void 이름이_맞는_파일이_한_건도_없으면_기록하지_않고_보류가_유지된다() throws Exception {
        long rawSn = importOriginal();

        // 산출물 실재 확인은 통과한다(내용이 있는 일반 파일이 있다). 그래도 이 영상의 프레임과 이름이
        //   하나도 맞지 않으면 그 폴더는 이 영상의 산출물이 아니다.
        mockMvc.perform(post(url(rawSn))
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(MISMATCH_DIR.toAbsolutePath().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        assertThat(procLogCount(rawSn)).isZero();
        assertThat(deidentCompleted(rawSn)).isEqualTo("N");
        assertThat(deidFramePaths(rawSn)).isEmpty();
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

    /** 비식별 이미지가 채워진 프레임의 <b>원본 파일 이름 → 비식별 파일 이름</b> 대응. */
    private Map<String, String> pairedFileNames(long rawSn) {
        Map<String, String> paired = new HashMap<>();
        jdbc.query("SELECT src_file_path_nm, de_idntf_src_file_path_nm FROM ls_data_src "
                        + "WHERE raw_sn = ? AND de_idntf_src_file_path_nm IS NOT NULL",
                rs -> { paired.put(baseName(rs.getString(1)), baseName(rs.getString(2))); }, rawSn);
        return paired;
    }

    private static String baseName(String path) {
        return path == null ? null : Paths.get(path).getFileName().toString();
    }

    /** 비식별 이미지 위치가 실제로 채워진 프레임의 경로 목록. */
    private List<String> deidFramePaths(long rawSn) {
        return jdbc.queryForList(
                "SELECT de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn = ? "
                        + "AND de_idntf_src_file_path_nm IS NOT NULL ORDER BY frm_no",
                String.class, rawSn);
    }

    private long firstSrcSn(long rawSn) {
        return jdbc.queryForObject(
                "SELECT src_sn FROM ls_data_src WHERE raw_sn = ? ORDER BY frm_no LIMIT 1",
                Long.class, rawSn);
    }

    /** 표본 프레임 이미지의 파일 이름 — 대응은 이 이름으로만 이뤄진다. */
    private static String frameFileName(int index) {
        return String.format("%08d.jpg", index);
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
