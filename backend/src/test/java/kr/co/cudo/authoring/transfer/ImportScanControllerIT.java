package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 산출물 폴더 검사 API 통합 시험 — {@code /api/v1/imports/scan}.
 *
 * <h3>실물 산출물에 고정한다</h3>
 * <p>이 형식은 우리가 만든 것이 아니라 받는 것이다. 손으로 지어낸 표본으로 고정하면 지어낼 때의
 * 짐작이 그대로 기대값이 되어, 실제 산출물이 다를 때 시험만 통과하고 적재가 깨진다. 그래서 저장소에
 * 함께 둔 표본 폴더를 그대로 읽고, 그 폴더가 들어 있는 {@code docs} 를 읽기 허용 루트로 지정한다.
 *
 * <h3>무엇을 검증하는가</h3>
 * <ul>
 *   <li>검사 뒤 <b>DB 행 수와 폴더의 파일이 하나도 달라지지 않는다</b>(AC-041) — "저장하지 않는다"는
 *       주장을 실제 상태 비교로 확인한다.</li>
 *   <li>짝 없는 이미지·선언 건수 불일치가 <b>경고로</b> 담기고 거부되지 않는다(AC-047).</li>
 *   <li>한 번 확정한 대응은 다음 검사에서 미확정 목록에 나오지 않는다(AC-043).</li>
 *   <li>허용 범위 밖 경로와 권한 없는 요청은 거부된다(AC-048).</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design API-205
 * @design AC-041
 * @design AC-043
 * @design AC-047
 * @design AC-048
 */
@SpringBootTest(properties = "authoring.storage.external-read-roots=../docs")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportScanControllerIT {

    private static final String SCAN = "/v1/imports/scan";
    private static final String MAPPINGS = "/v1/import-mappings";

    /** 이 시험이 만드는 라벨 마스터 행 — 정리 대상을 한 곳에서 가리키려고 상수로 둔다. */
    private static final String MAPPING_LABEL_NAME = "이관대응라벨";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        // 이 표는 이 도메인 전용이라 시험이 만든 행만 존재한다.
        jdbc.update("DELETE FROM ls_otsd_ctgry_mpng");
        // 라벨 마스터는 공유 표다 — 이 시험이 만든 행을 남기면 활성 라벨을 세는 다른 시험이 깨진다.
        jdbc.update("DELETE FROM ls_label WHERE lbl_nm = ?", MAPPING_LABEL_NAME);
    }

    /**
     * 표본 폴더 — 디렉터리 이름이 한글이라 파일시스템마다 자모 결합 형태가 다르다(맥은 분리형으로
     * 저장한다). 문자열을 이어 붙이면 플랫폼에 따라 못 찾으므로 부모를 훑어 정규화 후 비교한다.
     */
    private static Path sampleFolder() {
        Path docs = Paths.get("..", "docs");
        try (Stream<Path> entries = Files.list(docs)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> "1차어노테이션".equals(
                            Normalizer.normalize(p.getFileName().toString(), Normalizer.Form.NFC)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "표본 산출물 폴더를 찾을 수 없다. 이 시험은 실물에 고정돼 있다."))
                    .resolve("00000073");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String scanBody(String folderPath) throws Exception {
        return objectMapper.writeValueAsString(Map.of("folderPath", folderPath));
    }

    @Test
    @DisplayName("검수자가_표본_폴더를_검사하면_실제_파일_기준_건수와_경고가_돌아온다")
    void 검수자가_표본_폴더를_검사하면_실제_파일_기준_건수와_경고가_돌아온다() throws Exception {
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(sampleFolder().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 문서 5건 + 짝 문서 없는 이미지 1장 = 실제 파일 기준 6건.
                .andExpect(jsonPath("$.data.frameCount").value(6))
                // 문서가 선언한 값은 140 이다 — 실제와 다르지만 그대로 알려 준다.
                .andExpect(jsonPath("$.data.declaredFrameCount").value(140))
                .andExpect(jsonPath("$.data.videoFileName").value("은평구51.mp4"))
                .andExpect(jsonPath("$.data.warnings[?(@.code=='UNPAIRED_IMAGE')]").exists())
                .andExpect(jsonPath("$.data.warnings[?(@.code=='DECLARED_COUNT_MISMATCH')]").exists())
                // 대응이 하나도 없으므로 미확정 분류가 남아 적재할 수 없다(AC-042).
                .andExpect(jsonPath("$.data.importable").value(false))
                // 문서가 선언한 분류가 아니라 <b>실제로 쓰인</b> 분류만 묻는다 — 표본에서 도형이 쓴
                // 분류는 asphalt 하나이고, 영상이 가리키는 이벤트가 하나 더 있다.
                .andExpect(jsonPath("$.data.unmappedCategories.length()").value(2))
                .andExpect(jsonPath("$.data.unmappedCategories[?(@.externalCode=='asphalt')].kind")
                        .value("LABEL"))
                .andExpect(jsonPath("$.data.unmappedCategories[?(@.kind=='EVNT_TYPE')].externalCode")
                        .value("도로침수"))
                .andExpect(jsonPath("$.data.duplicate").doesNotExist());
    }

    @Test
    @DisplayName("검사는_DB와_폴더에_아무_변화도_남기지_않는다")
    void 검사는_DB와_폴더에_아무_변화도_남기지_않는다() throws Exception {
        Map<String, Long> before = rowCounts();
        Map<String, String> filesBefore = folderFingerprint(sampleFolder());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(SCAN)
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(scanBody(sampleFolder().toString())))
                    .andExpect(status().isOk());
        }

        // 영상·프레임·라벨·이관 이력·분류 대응 어느 것도 새로 생기지 않는다(AC-041).
        assertThat(rowCounts()).isEqualTo(before);
        // 파일도 건드리지 않는다 — 이름·크기·수정시각이 그대로다.
        assertThat(folderFingerprint(sampleFolder())).isEqualTo(filesBefore);
    }

    @Test
    @DisplayName("한_번_확정한_대응은_다음_검사에서_미확정으로_나오지_않는다")
    void 한_번_확정한_대응은_다음_검사에서_미확정으로_나오지_않는다() throws Exception {
        long labelId = insertLabel(MAPPING_LABEL_NAME);

        String confirm = objectMapper.writeValueAsString(Map.of(
                "items", List.of(Map.of(
                        "kind", "LABEL",
                        "externalCode", "asphalt",
                        "externalName", "도로",
                        "labelId", labelId))));
        mockMvc.perform(post(MAPPINGS)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created").value(1));

        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(sampleFolder().toString())))
                .andExpect(status().isOk())
                // 확정한 분류는 더 묻지 않는다.
                .andExpect(jsonPath("$.data.unmappedCategories[?(@.externalCode=='asphalt')]").doesNotExist())
                // 아직 확정하지 않은 이벤트 유형은 그대로 남는다 — 축이 다르면 따로 확정해야 한다.
                .andExpect(jsonPath("$.data.unmappedCategories.length()").value(1))
                .andExpect(jsonPath("$.data.unmappedCategories[0].kind").value("EVNT_TYPE"));
    }

    @Test
    @DisplayName("상위로_거슬러_올라가는_경로와_허용_범위_밖_경로는_거부한다")
    void 상위로_거슬러_올라가는_경로와_허용_범위_밖_경로는_거부한다() throws Exception {
        String traversal = sampleFolder().resolve("../../../backend").toString();
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(traversal)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody("/etc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("허용_범위_안이지만_없는_폴더는_찾을_수_없음이다")
    void 허용_범위_안이지만_없는_폴더는_찾을_수_없음이다() throws Exception {
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(sampleFolder().resolveSibling("00000000").toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("검수자가_아니면_경로의_유효성과_무관하게_같은_방식으로_거부한다")
    void 검수자가_아니면_경로의_유효성과_무관하게_같은_방식으로_거부한다() throws Exception {
        // 있는 폴더든 없는 폴더든 응답이 같아야 한다 — 갈리면 응답이 그 위치의 존재를 알려준다.
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(sampleFolder().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(sampleFolder().resolveSibling("00000000").toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("토큰이_없으면_인증_실패다")
    void 토큰이_없으면_인증_실패다() throws Exception {
        mockMvc.perform(post(SCAN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(sampleFolder().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("경로가_비었거나_길이를_넘으면_거부한다")
    void 경로가_비었거나_길이를_넘으면_거부한다() throws Exception {
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":\"\"}"))
                .andExpect(status().isBadRequest());

        String tooLong = sampleFolder() + "/" + "a".repeat(ImportSourcePolicy.FOLDER_PATH_MAX);
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanBody(tooLong)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 보조

    private long insertLabel(String name) {
        jdbc.update("DELETE FROM ls_label WHERE lbl_nm = ?", name);
        return jdbc.queryForObject(
                "INSERT INTO ls_label (lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_dt) "
                        + "VALUES (?, '#FF0000', 'POLYGON', 999, 'Y', CURRENT_TIMESTAMP) RETURNING lbl_id",
                Long.class, name);
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("ls_data_raw", "ls_data_src", "ls_data_lbl",
                "ls_otsd_ctgry_mpng", "ls_otsd_datst_trnsf_hstry")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    /** 폴더 안 파일의 이름·크기·수정시각 — 검사가 파일을 건드리지 않았음을 확인하는 지문. */
    private static Map<String, String> folderFingerprint(Path folder) {
        Map<String, String> fingerprint = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(folder)) {
            entries.sorted().forEach(p -> {
                try {
                    fingerprint.put(p.getFileName().toString(),
                            Files.size(p) + "@" + Files.getLastModifiedTime(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return fingerprint;
    }
}
