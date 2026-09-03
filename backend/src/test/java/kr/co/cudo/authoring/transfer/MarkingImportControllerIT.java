package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마킹 산출물 일괄 가져오기 창구 3종 통합 시험 — 검사 → 적재 → 진행 조회를 <b>한 흐름으로 관통</b>한다.
 *
 * <h3>왜 관통 시험이 필요한가</h3>
 * <p>부품 시험은 「부품이 규약대로 동작한다」를 말할 뿐 「이관이 실제로 된다」를 말하지 못한다. 이 갈래는
 * 창구·훑기·판정·복사·적재·예약이 전부 이어져야 성립하는데, 그 사슬은 부품을 아무리 덮어도 관통하지
 * 않으면 <b>이어져 있는지가 검증 대상 0</b> 으로 남는다.
 *
 * <h3>★ 권한 비대칭을 여기서 고정한다</h3>
 * <p>검사·진행 조회는 검수자, 적재 실행은 관리자다(API-216/217/218). <b>의도된 비대칭</b>이라 「일관성」을
 * 이유로 한쪽으로 맞추면 둘 중 하나가 무너진다 — 조회까지 좁히면 화면이 열리자마자 빈 채로 죽고,
 * 적재를 넓히면 운영 성격의 쓰기가 검수자에게 열린다.
 *
 * <h3>이 시험이 덮지 <b>못하는</b> 것 (덮은 척하지 않는다)</h3>
 * <ul>
 *   <li><b>비식별 완료 이후의 예약 깨우기.</b> 이 환경에는 외부 비식별 서버가 없어 그 단계가 완료로
 *       가지 않는다. 그 축은 비식별 도메인의 시험이 따로 덮으며, 여기서는 <b>예약이 걸렸다</b>는
 *       사실까지만 본다.</li>
 *   <li><b>영상 판독기가 있는 환경.</b> 표본 영상이 실제 영상이 아니라 판독이 실패하고, 그때
 *       속도 대조는 건너뛰어진다(그 자체가 사양이다 — 판독 실패가 적재를 막지 않는다).</li>
 * </ul>
 * <p>초록을 그 축들의 근거로 읽지 말 것.
 *
 * <h3>{@code @SpringBootTest} 설정은 {@code ImportScanControllerIT} 와 글자 그대로 같다</h3>
 * <p>다르게 쓰면 <b>캐시되는 스프링 컨텍스트가 하나 더 늘어난다</b>. 이 저장소는 그 수에 상한을 두고
 * 있어, 전용 설정을 붙이는 순간 무관한 회귀 시험이 곧바로 실패한다.
 *
 * @design DOMAIN-017
 * @design ADR-052
 * @design ADR-053
 * @design API-216
 * @design API-217
 * @design API-218
 * @design AC-1032
 * @design AC-1033
 * @design UC-037
 * @design SEQ-030
 */
@SpringBootTest(properties = "authoring.storage.external-read-roots=../docs")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MarkingImportControllerIT {

    private static final String SCAN = "/v1/imports/markings/scan";
    private static final String IMPORT = "/v1/imports/markings";

    /** 이 시험이 만드는 산출물 폴더 — 점으로 시작해 다른 시험의 탐색 목록에 잡히지 않는다. */
    private static final String FOLDER_NAME = ".marking-import-it";

    /** 이 시험이 만드는 영상의 식별자 접두 — 정리 대상을 한 눈에 가릴 수 있게 고유 대역을 쓴다. */
    private static final String CLIP_PREFIX = "MKITZ";

    private static final String CLIP_A = CLIP_PREFIX + "0001";
    private static final String CLIP_B = CLIP_PREFIX + "0002";

    /** 이 시험 전용 관리자 사용자번호 — 공용 시드·다른 시험과 겹치지 않는 대역. */
    private static final long ADMIN_NO = 969_300_071L;

    /** 진행이 끝나기를 기다리는 상한(ms). 넘으면 기다림을 멈추고 그때까지의 상태로 판정한다. */
    private static final long AWAIT_TIMEOUT_MS = 60_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRoleResolver userRoleResolver;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path}") private String storageRawPath;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private ImportAdminActor admin;
    private String adminToken;
    private String reviewerToken;
    private String workerToken;
    private Path folder;

    @BeforeEach
    void setUp() throws IOException {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        admin = new ImportAdminActor(jdbc, userRoleResolver, ADMIN_NO);
        admin.grant();
        adminToken = JwtTestSupport.token(secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
        folder = prepareFolder();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        admin.clear();
        deleteRecursively(docsRoot().resolve(FOLDER_NAME));
    }

    // ------------------------------------------------------------------ 검사

    @Test
    @DisplayName("검사는_문서와_영상을_이름으로_짝지어_건별_판정을_돌려준다")
    void 검사는_문서와_영상을_이름으로_짝지어_건별_판정을_돌려준다() throws Exception {
        String body = mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode data = objectMapper.readTree(body).path("data");
        // 문서는 하위 폴더에, 영상은 다른 하위 폴더에 둔다 — 폴더 구조에 기대지 않는다는 사양.
        assertThat(data.path("matchedCount").asInt()).isEqualTo(2);
        assertThat(data.path("importableCount").asInt()).isEqualTo(2);
        assertThat(data.path("truncated").asBoolean()).isFalse();
        // 어느 문서도 가리키지 않은 영상을 함께 알린다 — 사람이 묶음 온전성을 판단하는 근거다.
        assertThat(data.path("unmatchedVideoCount").asInt()).isEqualTo(1);
        assertThat(data.path("unmatchedVideoNames").toString()).contains("orphan.mp4");

        JsonNode first = data.path("items").get(0);
        assertThat(first.path("clipId").asText()).isEqualTo(CLIP_A);
        assertThat(first.path("videoFileName").asText()).isEqualTo(CLIP_A + ".mp4");
        assertThat(first.path("videoFound").asBoolean()).isTrue();
        assertThat(first.path("segmentCount").asInt()).isEqualTo(1);
        assertThat(first.path("markCount").asInt()).isEqualTo(2);
        assertThat(first.path("importable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("검사는_아무것도_저장하지_않는다")
    void 검사는_아무것도_저장하지_않는다() throws Exception {
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanRequest()))
                .andExpect(status().isOk());

        // 확인한 뒤에 상태가 이미 달라져 있으면 그 확인은 무엇을 확인한 것인지 알 수 없다(API-216).
        assertThat(countJobs()).isZero();
        assertThat(countVideos()).isZero();
    }

    @Test
    @DisplayName("허용된_저장소_범위_밖의_폴더는_400_이다")
    void 허용된_저장소_범위_밖의_폴더는_400_이다() throws Exception {
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":\"/etc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ------------------------------------------------------------------ 적재

    @Test
    @DisplayName("적재는_202_로_곧바로_반환하고_뒤에서_건별로_적재한다")
    void 적재는_202_로_곧바로_반환하고_뒤에서_건별로_적재한다() throws Exception {
        long jobSn = createJob();

        JsonNode progress = awaitFinished(jobSn);

        assertThat(progress.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(progress.path("targetCount").asInt()).isEqualTo(2);
        assertThat(progress.path("succeededCount").asInt()).isEqualTo(2);
        assertThat(progress.path("failedCount").asInt()).isZero();
        assertThat(progress.path("doneCount").asInt()).isEqualTo(2);
        assertThat(progress.path("itemsTruncated").asBoolean()).isFalse();
        assertThat(progress.path("items")).hasSize(2);
        assertThat(progress.path("items").get(0).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(progress.path("items").get(0).path("rawSn").asLong()).isPositive();
        // 위치가 아니라 이름을 돌려준다 — 저장소 내부 구조를 화면으로 내보내지 않는다(CWE-209).
        assertThat(progress.path("items").get(0).path("markingFileName").asText())
                .isEqualTo(CLIP_A + ".json");
        assertThat(progress.path("items").get(0).path("failureReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("적재한_영상은_비식별되지_않은_원본이며_파일이_저작도구_저장소로_복사된다")
    void 적재한_영상은_비식별되지_않은_원본이며_파일이_저작도구_저장소로_복사된다() throws Exception {
        awaitFinished(createJob());

        Map<String, Object> raw = jdbc.queryForMap(
                "SELECT raw_sn, src_type, prvc_type_cd, evnt_type_cd, lclgv_cd, vms_cctv_id, raw_file_path_nm "
                        + "FROM ls_data_raw WHERE vms_clip_id = ?", CLIP_A);

        // 사람이 화면에서 지정한 값이 항목마다 같은 값으로 붙는다(DFEAT-060).
        assertThat(raw.get("evnt_type_cd")).isEqualTo("EV01000101");
        assertThat(raw.get("lclgv_cd")).isEqualTo("4113500000");
        assertThat(raw.get("vms_cctv_id")).isEqualTo("CCTV_IT_0001");
        assertThat(raw.get("prvc_type_cd")).isEqualTo("PRVC");

        // 파일은 원래 자리를 가리키는 것이 아니라 저작도구 저장소로 <복사>된다 — 남의 폴더가 치워지면
        // 이미 적재한 영상이 깨지기 때문이다(API-217).
        String stored = String.valueOf(raw.get("raw_file_path_nm"));
        Path storedPath = Paths.get(stored);
        assertThat(storedPath).exists();
        assertThat(storedPath.toAbsolutePath().normalize())
                .startsWith(Paths.get(storageRawPath).toAbsolutePath().normalize());
        assertThat(stored).doesNotContain(FOLDER_NAME);
    }

    @Test
    @DisplayName("적재한_영상마다_외부가_준_시점을_담은_마킹이_예약으로_만들어진다")
    void 적재한_영상마다_외부가_준_시점을_담은_마킹이_예약으로_만들어진다() throws Exception {
        awaitFinished(createJob());

        Map<String, Object> marking = jdbc.queryForMap(
                "SELECT m.mark_mode_cd, m.frme_intv_nocs, m.mark_cn, m.stts_cd FROM ls_marking m "
                        + "JOIN ls_data_raw r ON r.raw_sn = m.raw_sn WHERE r.vms_clip_id = ?", CLIP_A);

        // 외부가 준 시점 배열의 구조가 사람이 직접 찍은 마킹과 같아 방식 코드가 수동이고, 간격으로
        // 만든 값이 아니므로 프레임 간격은 비어 있다(ADR-052).
        assertThat(marking.get("mark_mode_cd")).isEqualTo("MANUAL");
        assertThat(marking.get("frme_intv_nocs")).isNull();
        // 문서가 준 프레임 번호가 그대로 담긴다. 프레임 이미지는 적재하지 않고 시점 위치로만 쓴다.
        assertThat(String.valueOf(marking.get("mark_cn"))).contains("100").contains("400");
        // ★상태는 예약이거나, 비식별이 끝내 실패해 마감된 것이다. 어느 쪽이든 <활성 축 밖>이라
        //  사람이 그 영상을 다시 마킹할 수 있다(ADR-052 가 즉시 활성화를 기각한 이유).
        assertThat(String.valueOf(marking.get("stts_cd"))).isIn("RESERVED", "SKIPPED", "PENDING");
    }

    @Test
    @DisplayName("이미_들어와_있는_식별자는_그_항목만_건너뛰고_실패로_세지_않는다")
    void 이미_들어와_있는_식별자는_그_항목만_건너뛰고_실패로_세지_않는다() throws Exception {
        awaitFinished(createJob());

        // 같은 폴더를 한 번 더 적재한다 — 두 항목 모두 이미 들어와 있다.
        long second = createJob();
        JsonNode progress = awaitFinished(second);

        // 검사 단계에서 이미 「적재할 수 없음」으로 걸러지므로 대상 자체가 0 이다. 조용히 덮어쓰는
        // 경로가 없다는 것이 요점이다(AC-1033).
        assertThat(progress.path("targetCount").asInt()).isZero();
        // 원래 영상은 그대로다 — 덮어쓰면 검수 중이거나 승인된 내용이 사라진다.
        assertThat(countVideos()).isEqualTo(2);
    }

    @Test
    @DisplayName("고른_항목만_적재하고_나머지는_손대지_않는다")
    void 고른_항목만_적재하고_나머지는_손대지_않는다() throws Exception {
        long jobSn = createJob(List.of(CLIP_A + ".json"));

        JsonNode progress = awaitFinished(jobSn);

        assertThat(progress.path("targetCount").asInt()).isEqualTo(1);
        assertThat(progress.path("succeededCount").asInt()).isEqualTo(1);
        assertThat(countVideos()).isEqualTo(1);
    }

    @Test
    @DisplayName("이관_이력에_한_줄로_남는다")
    void 이관_이력에_한_줄로_남는다() throws Exception {
        awaitFinished(createJob());

        // 이력은 두 갈래 공통 표시 대상이라 마킹 갈래도 한 줄을 남긴다. 건별은 진행 조회에서 본다.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT trnsf_stts_cd, frme_cnt, lbl_cnt FROM ls_otsd_datst_trnsf_hstry "
                        + "WHERE orgnl_fldr_nm = ?", FOLDER_NAME);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("trnsf_stts_cd")).isEqualTo("SUCCESS");
        // 이 갈래는 프레임도 라벨도 적재하지 않는다 — 프레임은 뒤의 추출 단계가 만든다.
        assertThat(rows.get(0).get("frme_cnt")).isEqualTo(0);
        assertThat(rows.get(0).get("lbl_cnt")).isEqualTo(0);
    }

    // ------------------------------------------------------------------ 진행 조회

    @Test
    @DisplayName("상태로_거르면_담기는_목록만_줄고_집계는_전체_기준_그대로다")
    void 상태로_거르면_담기는_목록만_줄고_집계는_전체_기준_그대로다() throws Exception {
        long jobSn = createJob();
        awaitFinished(jobSn);

        String body = mockMvc.perform(get(IMPORT + "/" + jobSn).param("status", "FAILED")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.path("items")).isEmpty();
        // 함께 줄면 사람이 보는 진행률이 필터에 따라 달라져 무엇이 참인지 알 수 없다(API-218).
        assertThat(data.path("succeededCount").asInt()).isEqualTo(2);
        assertThat(data.path("targetCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("계약이_정한_값_밖의_상태_필터는_조용히_무시하지_않고_400_이다")
    void 계약이_정한_값_밖의_상태_필터는_조용히_무시하지_않고_400_이다() throws Exception {
        long jobSn = createJob();

        // 무시하면 「그 상태가 0건」과 「값이 잘못됐다」가 구분되지 않는다.
        mockMvc.perform(get(IMPORT + "/" + jobSn).param("status", "UNKNOWN")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는_작업을_조회하면_404_다")
    void 없는_작업을_조회하면_404_다() throws Exception {
        mockMvc.perform(get(IMPORT + "/999999999")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 인가 (의도된 비대칭)

    @Test
    @DisplayName("검사는_검수자_자리이고_작업자는_막힌다")
    void 검사는_검수자_자리이고_작업자는_막힌다() throws Exception {
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanRequest()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("적재는_관리자_자리라_검수자로는_막힌다")
    void 적재는_관리자_자리라_검수자로는_막힌다() throws Exception {
        // ★이 비대칭은 의도다 — 조회까지 관리자로 좁히면 화면이 열리자마자 빈 채로 죽는다.
        mockMvc.perform(post(IMPORT)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(null)))
                .andExpect(status().isForbidden());

        assertThat(countJobs()).isZero();
    }

    @Test
    @DisplayName("인가는_경로_판정보다_먼저_평가되어_그_위치가_있는지가_새지_않는다")
    void 인가는_경로_판정보다_먼저_평가되어_그_위치가_있는지가_새지_않는다() throws Exception {
        // 허용 범위 밖 경로를 권한 없이 보내도 400 이 아니라 403 이다 — 400 이면 응답이
        // 「그 경로는 범위 밖이다」를 알려 주는 셈이 된다(CWE-209).
        mockMvc.perform(post(SCAN)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":\"/etc\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 입구 검증

    @Test
    @DisplayName("사람이_지정해야_하는_값의_형식을_입구에서_거부한다")
    void 사람이_지정해야_하는_값의_형식을_입구에서_거부한다() throws Exception {
        // 서버는 화면을 신뢰하지 않는다. 형식이 틀린 값이 통과하면 그 값이 영상과 함께 산출물·
        // 운영 로그로 퍼져 나간다.
        String bad = """
                {"folderPath":"%s","meta":{"eventTypeCd":"EV1","localGovCd":"4113500000",
                 "cctvId":"CCTV_IT_0001","prvcTypeCd":"PRVC"}}
                """.formatted(folder.toString());

        mockMvc.perform(post(IMPORT)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("개인정보_유형에_미상은_선택지가_아니다")
    void 개인정보_유형에_미상은_선택지가_아니다() throws Exception {
        // 열어 두면 화면이 값을 안 보냈을 때 조용히 미상으로 적재되어 결손이 드러나지 않는다.
        String bad = """
                {"folderPath":"%s","meta":{"eventTypeCd":"EV01000101","localGovCd":"4113500000",
                 "cctvId":"CCTV_IT_0001","prvcTypeCd":"UNKNOWN"}}
                """.formatted(folder.toString());

        mockMvc.perform(post(IMPORT)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 보조

    private long createJob() throws Exception {
        return createJob(null);
    }

    private long createJob(List<String> targets) throws Exception {
        String body = mockMvc.perform(post(IMPORT)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(targets)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data").path("jobSn").asLong();
    }

    /** 진행이 끝날 때까지 기다린다 — 적재는 요청이 끝난 뒤에 일어나므로 조회가 끝 시점을 대신한다. */
    private JsonNode awaitFinished(long jobSn) throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        JsonNode data = progress(jobSn);
        while ("RUNNING".equals(data.path("status").asText())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            data = progress(jobSn);
        }
        return data;
    }

    private JsonNode progress(long jobSn) throws Exception {
        String body = mockMvc.perform(get(IMPORT + "/" + jobSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    private String scanRequest() {
        return "{\"folderPath\":\"" + folder + "\"}";
    }

    private String createRequest(List<String> targets) throws Exception {
        StringBuilder sb = new StringBuilder("{\"folderPath\":\"").append(folder).append("\",")
                .append("\"meta\":{\"eventTypeCd\":\"EV01000101\",\"localGovCd\":\"4113500000\",")
                .append("\"cctvId\":\"CCTV_IT_0001\",\"prvcTypeCd\":\"PRVC\"}");
        if (targets != null) {
            sb.append(",\"targets\":").append(objectMapper.writeValueAsString(targets));
        }
        return sb.append("}").toString();
    }

    /**
     * 표본 폴더를 만든다 — <b>문서와 영상을 서로 다른 하위 폴더</b>에 둔다.
     *
     * <p>짝짓기가 폴더 구조에 기대지 않는다는 사양을 형상으로 고정하기 위해서다. 같은 폴더에 두면
     * 「이름으로 찾는다」와 「옆에 있는 것을 집는다」가 구분되지 않는다.
     */
    private Path prepareFolder() throws IOException {
        Path base = docsRoot().resolve(FOLDER_NAME);
        deleteRecursively(base);
        Path docs = Files.createDirectories(base.resolve("reports"));
        Path videos = Files.createDirectories(base.resolve("clips/2026"));
        for (String clip : List.of(CLIP_A, CLIP_B)) {
            Files.writeString(docs.resolve(clip + ".json"), """
                    [{"id":1,"video_name":"%s.mp4","video_path":"C:\\\\cctv\\\\%s.mp4","notes":"이벤트",
                      "images":[{"filename":"a.jpg","frame":100,"time":"00:00:10.000"},
                                {"filename":"b.jpg","frame":400,"time":"00:00:20.000"}]}]
                    """.formatted(clip, clip), StandardCharsets.UTF_8);
            Files.writeString(videos.resolve(clip + ".mp4"), "video-" + clip, StandardCharsets.UTF_8);
        }
        // 어느 문서도 가리키지 않는 영상 — 묶음 온전성 안내의 표본.
        Files.writeString(videos.resolve("orphan.mp4"), "orphan", StandardCharsets.UTF_8);
        return base.toRealPath();
    }

    private static Path docsRoot() {
        return Paths.get("..", "docs");
    }

    private int countJobs() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_eblc_uld_job WHERE orgnl_fldr_path_nm LIKE ?",
                Integer.class, "%" + FOLDER_NAME + "%");
        return count == null ? 0 : count;
    }

    private int countVideos() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_data_raw WHERE vms_clip_id LIKE ?",
                Integer.class, CLIP_PREFIX + "%");
        return count == null ? 0 : count;
    }

    /**
     * 이 시험이 남긴 것을 지운다.
     *
     * <p>영상에 딸린 표는 외래키가 없거나 방향이 달라 <b>연쇄로 정리되지 않는</b> 것이 있어 하나씩
     * 지운다. 정리에 실패하면 다음 시험이 남은 행을 보고 엉뚱한 판정을 한다.
     */
    private void cleanup() {
        String rawScope = "(SELECT raw_sn FROM ls_data_raw WHERE vms_clip_id LIKE '" + CLIP_PREFIX + "%')";
        for (String table : List.of("ls_marking", "ls_data_meta", "ls_deident_proc_log",
                "ls_batch_proc_log", "ls_data_lbl_hstry", "ls_data_lbl", "ls_data_src",
                "ls_raw_data_status", "ls_bat_rty_wtng")) {
            quietly("DELETE FROM " + table + " WHERE raw_sn IN " + rawScope);
        }
        quietly("DELETE FROM ls_eblc_uld_job_artcl WHERE eblc_uld_job_sn IN "
                + "(SELECT eblc_uld_job_sn FROM ls_eblc_uld_job WHERE orgnl_fldr_path_nm LIKE '%"
                + FOLDER_NAME + "%')");
        quietly("DELETE FROM ls_eblc_uld_job WHERE orgnl_fldr_path_nm LIKE '%" + FOLDER_NAME + "%'");
        quietly("DELETE FROM ls_otsd_datst_trnsf_hstry WHERE orgnl_fldr_nm = '" + FOLDER_NAME + "'");
        quietly("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE '" + CLIP_PREFIX + "%'");
        deleteRecursively(Paths.get(storageRawPath).resolve("imports").resolve(CLIP_A));
        deleteRecursively(Paths.get(storageRawPath).resolve("imports").resolve(CLIP_B));
    }

    /** 없는 표·컬럼 때문에 정리가 멈추지 않게 한다 — 정리는 최선 노력이다. */
    private void quietly(String sql) {
        try {
            jdbc.update(sql);
        } catch (RuntimeException e) {
            // 무시 — 그 표가 이 형상에 없거나 이미 비어 있다.
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // 정리 실패는 시험 결과를 바꾸지 않는다 — 다음 실행이 다시 지운다.
        }
    }
}
