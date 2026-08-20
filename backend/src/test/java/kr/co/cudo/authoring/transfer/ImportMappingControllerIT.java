package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.eventtype.service.EventTypeAutoRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분류 대응 관리 API 통합 시험 — {@code /api/v1/import-mappings}.
 *
 * <h3>이 API 가 없으면 무엇이 깨지는가</h3>
 * <p>외부 산출물의 분류 이름은 저작도구 라벨 체계와 다르다. 대응을 한 번 정해 두는 통로가 없으면
 * 산출물마다 같은 분류를 다시 확정해야 하고, 그러면 규모가 큰 이관에서는 쓸 수 없다(AC-043).
 *
 * <h3>확정은 사람의 행위라는 성질을 함께 고정한다</h3>
 * <p>종류와 연결 대상이 어긋나거나 대상이 실재하지 않으면 저장하지 않는다. 이름이 비슷하다고 서버가
 * 대신 채우는 통로는 이 API 에 없다.
 *
 * @design DOMAIN-017
 * @design API-209
 * @design API-210
 * @design API-211
 * @design AC-043
 */
@SpringBootTest(properties = "authoring.storage.external-read-roots=../docs")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportMappingControllerIT {

    private static final String BASE = "/v1/import-mappings";
    private static final String EVENT_CODE = "ITMPEV01";
    private static final String LABEL_NAME_A = "이관시험라벨A";
    private static final String LABEL_NAME_B = "이관시험라벨B";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventTypeAutoRegistrar autoRegistrar;
    @Autowired private CacheManager cacheManager;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String reviewerToken;
    private String workerToken;
    private long labelA;
    private long labelB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
        labelA = insertLabel(LABEL_NAME_A);
        labelB = insertLabel(LABEL_NAME_B);
        autoRegistrar.register(EVENT_CODE, "이관시험이벤트", "01", null);
        evictEventTypeCache();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        evictEventTypeCache();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ls_otsd_ctgry_mpng");
        jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd = ?", EVENT_CODE);
        jdbc.update("DELETE FROM ls_label WHERE lbl_nm IN (?, ?)", LABEL_NAME_A, LABEL_NAME_B);
    }

    private void evictEventTypeCache() {
        var cache = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        if (cache != null) {
            cache.clear();
        }
    }

    private long insertLabel(String name) {
        return jdbc.queryForObject(
                "INSERT INTO ls_label (lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_dt) "
                        + "VALUES (?, '#00FF00', 'POLYGON', 998, 'Y', CURRENT_TIMESTAMP) RETURNING lbl_id",
                Long.class, name);
    }

    private String body(boolean overwrite, Map<String, Object>... items) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("items", List.of(items));
        payload.put("overwrite", overwrite);
        return objectMapper.writeValueAsString(payload);
    }

    private static Map<String, Object> labelItem(String externalCode, String externalName, Long labelId) {
        Map<String, Object> item = new HashMap<>();
        item.put("kind", "LABEL");
        item.put("externalCode", externalCode);
        item.put("externalName", externalName);
        item.put("labelId", labelId);
        return item;
    }

    private static Map<String, Object> eventItem(String externalCode, String evntTypeCd) {
        Map<String, Object> item = new HashMap<>();
        item.put("kind", "EVNT_TYPE");
        item.put("externalCode", externalCode);
        item.put("evntTypeCd", evntTypeCd);
        return item;
    }

    private long confirmLabelMapping(String externalCode) throws Exception {
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, labelItem(externalCode, "도로", labelA))))
                .andExpect(status().isCreated());
        return jdbc.queryForObject(
                "SELECT mpng_sn FROM ls_otsd_ctgry_mpng WHERE otsd_ctgry_cd = ?", Long.class, externalCode);
    }

    @Test
    @DisplayName("검수자가_라벨_대응과_이벤트유형_대응을_한_번에_확정할_수_있다")
    void 검수자가_라벨_대응과_이벤트유형_대응을_한_번에_확정할_수_있다() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false,
                                labelItem("asphalt", "도로", labelA),
                                eventItem("도로침수", EVENT_CODE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.updated").value(0));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[?(@.externalCode=='asphalt')].labelName")
                        .value(LABEL_NAME_A))
                .andExpect(jsonPath("$.data.items[?(@.kind=='EVNT_TYPE')].evntTypeCd").value(EVENT_CODE));
    }

    @Test
    @DisplayName("같은_분류에_대한_대응은_의도를_밝히지_않으면_거부한다")
    void 같은_분류에_대한_대응은_의도를_밝히지_않으면_거부한다() throws Exception {
        confirmLabelMapping("asphalt");

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, labelItem("asphalt", "도로", labelB))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        // 거부된 요청은 기존 대응을 바꾸지 않는다.
        assertThat(jdbc.queryForObject(
                "SELECT lbl_id FROM ls_otsd_ctgry_mpng WHERE otsd_ctgry_cd = 'asphalt'", Long.class))
                .isEqualTo(labelA);
    }

    @Test
    @DisplayName("의도를_밝히면_기존_대응을_바꿔_쓴다")
    void 의도를_밝히면_기존_대응을_바꿔_쓴다() throws Exception {
        confirmLabelMapping("asphalt");

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true, labelItem("asphalt", "도로면", labelB))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.updated").value(1));

        assertThat(jdbc.queryForObject(
                "SELECT lbl_id FROM ls_otsd_ctgry_mpng WHERE otsd_ctgry_cd = 'asphalt'", Long.class))
                .isEqualTo(labelB);
        // 행은 하나뿐이다 — 바꿔 쓰기가 새 행을 쌓지 않는다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ls_otsd_ctgry_mpng", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("종류와_연결_대상이_맞지_않으면_거부한다")
    void 종류와_연결_대상이_맞지_않으면_거부한다() throws Exception {
        Map<String, Object> both = labelItem("asphalt", "도로", labelA);
        both.put("evntTypeCd", EVENT_CODE);
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, both)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, labelItem("asphalt", "도로", null))))
                .andExpect(status().isBadRequest());

        // 종류는 라벨인데 이벤트 유형을 지정한 경우.
        Map<String, Object> crossed = eventItem("asphalt", EVENT_CODE);
        crossed.put("kind", "LABEL");
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, crossed)))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ls_otsd_ctgry_mpng", Long.class)).isZero();
    }

    @Test
    @DisplayName("실재하지_않는_라벨이나_이벤트유형에는_연결하지_않는다")
    void 실재하지_않는_라벨이나_이벤트유형에는_연결하지_않는다() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, labelItem("asphalt", "도로", 987654321L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, eventItem("도로침수", "NOSUCHEVENT"))))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ls_otsd_ctgry_mpng", Long.class)).isZero();
    }

    @Test
    @DisplayName("한_요청에_같은_분류가_두_번_들어오면_아무것도_저장하지_않는다")
    void 한_요청에_같은_분류가_두_번_들어오면_아무것도_저장하지_않는다() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false,
                                labelItem("asphalt", "도로", labelA),
                                labelItem("asphalt", "도로면", labelB))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ls_otsd_ctgry_mpng", Long.class)).isZero();
    }

    @Test
    @DisplayName("한_건이라도_거부되면_같은_요청의_다른_건도_저장되지_않는다")
    void 한_건이라도_거부되면_같은_요청의_다른_건도_저장되지_않는다() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false,
                                labelItem("asphalt", "도로", labelA),
                                labelItem("water", "물", 987654321L))))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ls_otsd_ctgry_mpng", Long.class)).isZero();
    }

    @Test
    @DisplayName("해제하면_목록에서_빠지고_포함을_요청해야_보인다")
    void 해제하면_목록에서_빠지고_포함을_요청해야_보인다() throws Exception {
        long mpngSn = confirmLabelMapping("asphalt");

        mockMvc.perform(delete(BASE + "/" + mpngSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

        // 행은 남는다 — 과거 이관이 어느 대응으로 적재됐는지 되짚을 수 있어야 한다.
        assertThat(jdbc.queryForObject(
                "SELECT use_yn FROM ls_otsd_ctgry_mpng WHERE mpng_sn = ?", String.class, mpngSn))
                .isEqualTo("N");

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get(BASE).param("includeUnused", "true")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].useYn").value("N"));
    }

    @Test
    @DisplayName("해제한_대응을_다시_확정하면_되살아난다")
    void 해제한_대응을_다시_확정하면_되살아난다() throws Exception {
        long mpngSn = confirmLabelMapping("asphalt");
        mockMvc.perform(delete(BASE + "/" + mpngSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        // 해제된 행이 남아 있어 새로 만들 수 없다 — 바꿔 쓰기로 되살린다.
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true, labelItem("asphalt", "도로", labelB))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.updated").value(1));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].useYn").value("Y"));
    }

    @Test
    @DisplayName("종류로_거를_수_있고_페이지_단위로_돌려준다")
    void 종류로_거를_수_있고_페이지_단위로_돌려준다() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false,
                                labelItem("asphalt", "도로", labelA),
                                labelItem("water", "물", labelB),
                                eventItem("도로침수", EVENT_CODE))))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE).param("kind", "LABEL")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        mockMvc.perform(get(BASE).param("page", "0").param("size", "1")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        // 종류를 잘못 적으면 조용히 전체 조회로 넘어가지 않는다.
        mockMvc.perform(get(BASE).param("kind", "LABELS")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는_대응을_해제하면_찾을_수_없음이다")
    void 없는_대응을_해제하면_찾을_수_없음이다() throws Exception {
        mockMvc.perform(delete(BASE + "/987654321")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("검수자가_아니면_조회도_확정도_해제도_할_수_없다")
    void 검수자가_아니면_조회도_확정도_해제도_할_수_없다() throws Exception {
        long mpngSn = confirmLabelMapping("asphalt");

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, labelItem("water", "물", labelB))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/" + mpngSn)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());

        // 거부된 요청은 분류 대응을 만들지도 바꾸지도 않는다(AC-048).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ls_otsd_ctgry_mpng", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT use_yn FROM ls_otsd_ctgry_mpng WHERE mpng_sn = ?", String.class, mpngSn))
                .isEqualTo("Y");
    }
}
