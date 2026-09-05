package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.dataset.export.DatasetExportOutcome;
import kr.co.cudo.authoring.dataset.export.DatasetExportService;
import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import org.awaitility.Awaitility;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 외부 산출물 <b>적재</b> API 통합 시험 — {@code /api/v1/imports} (API-206).
 *
 * <h3>무엇을 고정하는가</h3>
 * <ul>
 *   <li>영상 1건 + 프레임 N건 + 라벨이 만들어지고 이관 이력이 남는다. 프레임 수는 <b>실제 파일 기준</b>
 *       이며 문서가 선언한 수가 아니다(AC-047).</li>
 *   <li>작업자 배정도 검수 제출도 없이 <b>검수 대기</b>가 된다(AC-045).</li>
 *   <li>같은 산출물을 두 번 가져오면 거부하고 <b>기존 영상 번호를 알려 준다</b>(AC-044).</li>
 *   <li>원본이라고 지정하면 승인 보류가 서고, 비식별이 끝났다고 지정하면 서지 않는다(AC-046).</li>
 *   <li>저작도구에 착지할 자리가 없는 값(원천 축 개인정보 3필드 포함)이 <b>원문 그대로</b> 보관된다.</li>
 *   <li>허용 범위 밖 경로·권한 없는 요청은 아무것도 남기지 않고 거부된다(AC-048).</li>
 * </ul>
 *
 * <h3>표본은 실물이다</h3>
 * <p>저장소에 함께 둔 산출물 폴더를 그대로 읽는다({@link ImportSampleFolder}). 저장 위치는 빌드
 * 디렉터리 아래로 돌려, 시험이 실제 저장소를 건드리지 않게 한다.
 *
 * <h3>적재는 관리자 자리다 (ADR-055)</h3>
 * <p>적재 실행과 분류 대응 확정은 관리자만 호출한다. 반면 검수·프레임 열람 같은 뒤이은 자리는
 * 검수자 그대로라, 이 시험은 두 배우를 <b>일부러 갈라</b> 쓴다 — 한 배우로 통일하면 축이 갈렸다는
 * 사실이 시험에서 사라진다.
 *
 * @design DOMAIN-017
 * @design API-206
 * @design ADR-055
 * @design ROLE-004
 * @design AC-044
 * @design AC-045
 * @design AC-046
 * @design AC-047
 * @design AC-048
 */
@SpringBootTest(properties = {
        "authoring.storage.external-read-roots=../docs",
        "authoring.storage.raw-path=build/tmp/import-it/raw",
        "authoring.storage.deidentified-path=build/tmp/import-it/deid"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportControllerIT {

    private static final String IMPORTS = "/v1/imports";
    private static final String MAPPINGS = "/v1/import-mappings";

    private static final String MAPPING_LABEL_NAME = "이관적재라벨";
    private static final String EVENT_TYPE_CD = "IMPTEST01";
    private static final Path STORAGE_ROOT = Paths.get("build", "tmp", "import-it");

    /** 비동기 학습데이터 산출을 기다리는 상한 — 같은 저장소의 산출 대기 선례(20초)를 따른다. */
    private static final Duration EXPORT_SETTLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXPORT_SETTLE_POLL = Duration.ofMillis(100);

    /** 이 시험 전용 관리자 사용자번호 — 공용 시드·다른 시험과 겹치지 않는 대역. */
    private static final long ADMIN_NO = 969_300_042L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRoleResolver userRoleResolver;
    @Autowired private EventTypeCacheEvictor eventTypeCacheEvictor;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatasetExportService datasetExportService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** 검수 승인이 산출을 띄우는 풀 — 그 산출이 끝났는지 보려고 잡는다. {@link #drainAsyncExports()} 참조. */
    @Autowired
    @Qualifier("batchAsyncExecutor")
    private Executor batchAsyncExecutor;

    private JdbcTemplate jdbc;
    private ImportAdminActor admin;
    private String adminToken;
    private String reviewerToken;
    private String workerToken;
    private long labelId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        admin = new ImportAdminActor(jdbc, userRoleResolver, ADMIN_NO);
        admin.grant();
        // JWT 의 role 클레임은 인가에 쓰이지 않는다(역할 저장소가 진실원) — 값은 표기일 뿐이다.
        adminToken = JwtTestSupport.token(secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
        labelId = insertLabel();
        insertEventType();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        admin.clear();
    }

    // ------------------------------------------------------------------ 적재

    @Test
    @DisplayName("비식별이_끝난_산출물을_적재하면_검수_대기_영상과_실제_파일_기준_프레임이_만들어진다")
    void 비식별이_끝난_산출물을_적재하면_검수_대기_영상과_실제_파일_기준_프레임이_만들어진다() throws Exception {
        confirmMappings();

        long rawSn = importFolder(true, null);

        Map<String, Object> raw = jdbc.queryForMap(
                "SELECT src_type, vms_clip_id, de_ident_yn, raw_file_path_nm, prvc_type_cd, "
                        + "evnt_type_cd, lclgv_cd FROM ls_data_raw WHERE raw_sn = ?", rawSn);
        // 관제가 보낸 것도 저작도구가 만든 것도 아니다 — 기존 출처유형 어디에도 넣을 수 없다.
        assertThat(raw.get("src_type")).isEqualTo("IMPORTED");
        assertThat(raw.get("vms_clip_id")).isEqualTo(ImportSampleFolder.VMS_CLIP_ID);
        // 비식별이 끝났다고 지정해 받았으므로 그 영상이 곧 비식별 영상이다.
        assertThat(raw.get("de_ident_yn")).isEqualTo("Y");
        // 저장 위치는 비울 수 없다 — 비식별·학습데이터 산출물의 위치가 이 값에서 파생된다.
        assertThat((String) raw.get("raw_file_path_nm")).endsWith("은평구51.mp4");
        // 확정된 이벤트 대응이 영상에 실린다.
        assertThat(raw.get("evnt_type_cd")).isEqualTo(EVENT_TYPE_CD);

        // 프레임 수는 문서가 선언한 140 이 아니라 <b>실제 파일 수</b>다(AC-047).
        assertThat(countFrames(rawSn)).isEqualTo(ImportSampleFolder.FRAME_COUNT);
        assertThat(countLabels(rawSn)).isEqualTo(ImportSampleFolder.LABEL_COUNT);
        // 짝 문서가 없는 이미지도 <b>라벨이 없는 프레임</b>으로 담긴다.
        assertThat(countFramesWithoutLabel(rawSn)).isEqualTo(
                ImportSampleFolder.FRAME_COUNT - ImportSampleFolder.LABEL_COUNT);

        // 비식별 완료본을 받았으므로 비식별 프레임 경로가 채워지고 원본 경로는 비어 있다 —
        //   그 자리에 원본 경로를 넣으면 마스킹 전 화면이 "비식별본"으로 서빙된다.
        Map<String, Object> frame = jdbc.queryForMap(
                "SELECT src_file_path_nm, de_idntf_src_file_path_nm, vdo_frm_no, frm_no "
                        + "FROM ls_data_src WHERE raw_sn = ? ORDER BY frm_no LIMIT 1", rawSn);
        assertThat(frame.get("src_file_path_nm")).isNull();
        assertThat((String) frame.get("de_idntf_src_file_path_nm")).endsWith(".jpg");
        // 추출 순번과 영상 내 실제 위치는 뜻이 다르다 — 바꿔 담으면 프레임과 라벨이 다른 장면을 가리킨다.
        assertThat(frame.get("vdo_frm_no")).isNotNull();

        // 라벨은 확정된 대응을 따르고, 무엇이 만들었는가를 담는 축은 비어 있다(사람이 그린 것이다).
        Map<String, Object> label = jdbc.queryForMap(
                "SELECT l.lbl_id, l.lbl_src_cd, l.auto_lbl_yn, l.lbl_type_cd, l.point_cn "
                        + "FROM ls_data_lbl l JOIN ls_data_src s ON s.src_sn = l.src_sn "
                        + "WHERE s.raw_sn = ? LIMIT 1", rawSn);
        assertThat(((Number) label.get("lbl_id")).longValue()).isEqualTo(labelId);
        assertThat(label.get("lbl_src_cd")).isNull();
        assertThat(label.get("auto_lbl_yn")).isNull();
        assertThat(label.get("lbl_type_cd")).isEqualTo("POLYGON");
        // 산출물은 좌표를 평면으로 나열한다 — 저작도구 정규 형식으로 바꿔 담는다.
        assertThat((String) label.get("point_cn")).startsWith("[[");

        // 비식별 완료본을 받았으면 그 위치를 이력에 <b>적재값으로</b> 남긴다 — 조합·추측하지 않는다.
        Map<String, Object> procLog = jdbc.queryForMap(
                "SELECT proc_stts_cd, de_idntf_file_path_nm FROM ls_deident_proc_log "
                        + "WHERE data_raw_sn = ?", rawSn);
        assertThat(procLog.get("proc_stts_cd")).isEqualTo("SUCCEEDED");
        assertThat(procLog.get("de_idntf_file_path_nm")).isEqualTo(raw.get("raw_file_path_nm"));

        // 이관 이력 — 언제 누가 어떤 폴더를 가져와 몇 건이 들어왔는지.
        Map<String, Object> history = jdbc.queryForMap(
                "SELECT trnsf_stts_cd, frme_cnt, lbl_cnt, orgnl_fldr_nm, otsd_datst_id, reg_id "
                        + "FROM ls_otsd_datst_trnsf_hstry WHERE raw_sn = ?", rawSn);
        assertThat(history.get("trnsf_stts_cd")).isEqualTo("SUCCESS");
        assertThat(((Number) history.get("frme_cnt")).intValue())
                .isEqualTo(ImportSampleFolder.FRAME_COUNT);
        assertThat(((Number) history.get("lbl_cnt")).intValue())
                .isEqualTo(ImportSampleFolder.LABEL_COUNT);
        assertThat(history.get("orgnl_fldr_nm")).isEqualTo(ImportSampleFolder.FOLDER_NAME);
        // 가져온 사람이 그대로 남는다 — 적재는 관리자 자리이므로 그 관리자의 식별자다.
        assertThat(history.get("reg_id")).isEqualTo(String.valueOf(ADMIN_NO));

        // 파일이 실제로 옮겨졌다 — 경로만 적히고 파일이 없으면 관제가 픽업해도 열 것이 없다.
        assertThat(Files.isRegularFile(Paths.get((String) frame.get("de_idntf_src_file_path_nm"))))
                .isTrue();
    }

    @Test
    @DisplayName("적재된_영상은_배정_없이_검수_목록에_나타나고_검수를_시작할_수_있다")
    void 적재된_영상은_배정_없이_검수_목록에_나타나고_검수를_시작할_수_있다() throws Exception {
        confirmMappings();
        long rawSn = importFolder(true, null);

        // 작업자 배정도 검수 제출 기록도 없다 — 이 경로에는 라벨링 작업 자체가 없다.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_task_altmnt WHERE raw_data_id = ?", Long.class, rawSn))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT data_stts_cd FROM ls_raw_data_status WHERE raw_data_id = ?",
                String.class, rawSn))
                .isEqualTo("PENDING");

        mockMvc.perform(get("/v1/reviews").param("status", "PENDING").param("size", "100")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.videoId==" + rawSn + ")]").exists())
                // 작업자 칸은 비어 있다.
                .andExpect(jsonPath("$.data.content[?(@.videoId==" + rawSn + ")].workerName")
                        .value(""));

        mockMvc.perform(post("/v1/reviews/" + rawSn + "/start")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("같은_산출물을_다시_적재하면_거부하고_기존_영상_번호를_알려준다")
    void 같은_산출물을_다시_적재하면_거부하고_기존_영상_번호를_알려준다() throws Exception {
        confirmMappings();
        long rawSn = importFolder(true, null);
        long framesBefore = countFrames(rawSn);

        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(true, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                // 표준 오류 본문 자리가 null 로 고정돼 있어 식별번호는 메시지에 싣는다.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(String.valueOf(rawSn))));

        // 기존 영상은 그대로다 — 조용히 덮어쓰지 않는다.
        assertThat(countFrames(rawSn)).isEqualTo(framesBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_data_raw WHERE vms_clip_id = ?", Long.class,
                ImportSampleFolder.VMS_CLIP_ID)).isEqualTo(1L);
    }

    @Test
    @DisplayName("원본으로_적재하면_승인_보류가_서고_비식별이_끝난_것으로_적재하면_서지_않는다")
    void 원본으로_적재하면_승인_보류가_서고_비식별이_끝난_것으로_적재하면_서지_않는다() throws Exception {
        confirmMappings();

        long rawSn = importFolder(false, null);

        // 승인 보류는 비식별 여부 값이 아니라 검수 워크플로 상태의 별도 값이 담당한다(ADR-048).
        assertThat(jdbc.queryForObject(
                "SELECT de_idntf_cmptn_yn FROM ls_raw_data_status WHERE raw_data_id = ?",
                String.class, rawSn)).isEqualTo("N");
        assertThat(jdbc.queryForObject(
                "SELECT de_ident_yn FROM ls_data_raw WHERE raw_sn = ?", String.class, rawSn))
                .isEqualTo("N");
        // 영상 파일을 함께 주지 않았으므로 비식별할 대상이 없다 — 이력 행도 아직 없다.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_deident_proc_log WHERE data_raw_sn = ?", Long.class, rawSn))
                .isZero();

        // 이 보류는 승인 하나만 막는다 — 라벨과 프레임은 이미 들어와 있고 그것을 못 보면 검수가 성립하지 않는다.
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/start")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    @DisplayName("착지할_자리가_없는_값과_원천_개인정보_세_필드가_원문_그대로_보관된다")
    void 착지할_자리가_없는_값과_원천_개인정보_세_필드가_원문_그대로_보관된다() throws Exception {
        confirmMappings();
        long rawSn = importFolder(true, null);

        Map<String, String> meta = new HashMap<>();
        jdbc.query("SELECT meta_key, meta_vl FROM ls_data_meta WHERE raw_sn = ?",
                rs -> { meta.put(rs.getString(1), rs.getString(2)); }, rawSn);

        // 원천 축 개인정보 3필드 — 착지 컬럼이 관제 수신 원장에만 있는데 이 경로는 그 원장을 거치지
        //   않는다. 보관하지 않으면 학습데이터 산출에서 조달할 곳이 아예 없다.
        assertThat(meta.get("import.video.anonymity")).isEqualTo("N");
        assertThat(meta.get("import.video.pseudonymity")).isEqualTo("N");
        assertThat(meta.get("import.video.privacy_included")).isEqualTo("Y");
        // 프레임 축 값은 비식별이 끝난 것으로 지정해 가져왔으므로 <b>컬럼에 착지</b>한다 — 같은 사실이
        //   메타에도 있으면 어느 것이 진실인지 갈리므로 여기 보관하지 않는다.
        assertThat(meta).doesNotContainKeys("import.image.anonymity", "import.image.pseudonymity",
                "import.image.privacy_included");
        assertThat(framePrivacy(rawSn)).containsExactly(Map.of(
                "anony_incl_yn", "Y", "psdo_incl_yn", "N", "prvc_incl_yn", "N"));
        // 좌표·위치·이벤트 기록 — 버리면 되돌릴 수 없다.
        assertThat(meta.get("import.video.coordinates")).isEqualTo("37.6159028, 126.9327926");
        assertThat(meta.get("import.video.location")).isEqualTo("서울특별시 은평구");
        assertThat(meta.get("import.video.event_log")).isEqualTo("서울특별시 은평구 도로침수");
        // 이벤트 상위 계층 이름은 대응 대상이 아니라 참고 정보다 — 그래도 버리지 않는다.
        assertThat(meta.get("import.video.event_level1_name")).isEqualTo("자연재난");
        // ⚠ 표본에서 설치 높이·방위·관리번호·데이터 출처는 <b>빈 값</b>이다. 빈 값에 행을 만들면
        //   "값이 없다"와 "빈 문자열을 받았다"가 구분되지 않으므로 보관하지 않는다(값을 지어내지 않는다).
        assertThat(meta).doesNotContainKeys("import.video.cctv_height", "import.video.cctv_azimuth",
                "import.video.cctv_mng_no", "import.video.data_source");
    }

    @Test
    @DisplayName("원본이라고_지정하면_프레임_개인정보는_컬럼이_아니라_메타에_원문으로_보관된다")
    void 원본이라고_지정하면_프레임_개인정보는_컬럼이_아니라_메타에_원문으로_보관된다() throws Exception {
        confirmMappings();
        long rawSn = importFolder(false, null);

        Map<String, String> meta = new HashMap<>();
        jdbc.query("SELECT meta_key, meta_vl FROM ls_data_meta WHERE raw_sn = ?",
                rs -> { meta.put(rs.getString(1), rs.getString(2)); }, rawSn);

        // 원본이라고 지정한 경우 프레임 축 값은 <b>원천 축</b>의 사실이라 착지할 컬럼이 없다. 버리면
        //   되돌릴 수 없으므로 원문을 보관한다 — 이 보관 여부가 두 축을 가르는 관측 가능한 차이다.
        assertThat(meta.get("import.image.anonymity")).isEqualTo("Y");
        assertThat(meta.get("import.image.pseudonymity")).isEqualTo("N");
        assertThat(meta.get("import.image.privacy_included")).isEqualTo("N");

        // ⚠ 표본의 프레임 축 값이 적재 기본값과 같은 모양이라 <b>컬럼 값만으로는 두 축이 구분되지
        //   않는다</b>. 그래서 축의 구분은 위 메타 보관 여부로 고정하고, 여기서는 착지하지 않은 축이
        //   기본값으로 시작한다는 것만 확인한다(값을 지어내지 않는다).
        assertThat(framePrivacy(rawSn)).containsExactly(Map.of(
                "anony_incl_yn", "Y", "psdo_incl_yn", "N", "prvc_incl_yn", "N"));
    }

    @Test
    @DisplayName("대응이_정해지지_않은_분류가_남으면_적재하지_않는다")
    void 대응이_정해지지_않은_분류가_남으면_적재하지_않는다() throws Exception {
        // 대응을 하나도 확정하지 않은 상태다 — 짐작으로 연결하면 다른 분류로 저장되고 되돌릴 수 없다.
        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        assertThat(importedRawCount()).isZero();
        assertThat(historyCount()).isZero();
    }

    /**
     * ★ 이번 좁히기의 핵심 대조군 — 적재만 관리자로 좁아지고 검사·이력 조회는 검수자 그대로다.
     *
     * <h3>본문을 일부러 "성공하는 본문"으로 준다</h3>
     * <p>인가 뒤에는 경로 판정·대응 확정 여부 같은 게이트가 줄줄이 이어진다. 그중 하나에라도 걸리는
     * 본문을 주면 인가를 통째로 풀어도 같은 거부가 나와 시험이 <b>조용히 항상 참</b>이 된다. 그래서
     * 같은 본문을 관리자가 보내면 실제로 만들어지는 것까지 함께 단언한다 — 그 짝이 있어야 위의
     * 거부가 본문 결함이 아니라 인가였음이 증명된다.
     */
    @Test
    @DisplayName("검수자는_적재에서만_막히고_검사와_이력_조회는_그대로_통과한다")
    void 검수자는_적재에서만_막히고_검사와_이력_조회는_그대로_통과한다() throws Exception {
        confirmMappings();
        String body = importBody(true, null);

        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        assertThat(importedRawCount()).isZero();
        assertThat(historyCount()).isZero();

        // 대조군 — 검사와 이력 조회는 검수자 권한으로 계속 응답한다. 함께 좁아지면 화면이
        //   열리자마자 빈 채로 죽는다.
        mockMvc.perform(post(IMPORTS + "/scan")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("folderPath", ImportSampleFolder.path().toString()))))
                .andExpect(status().isOk());
        mockMvc.perform(get(IMPORTS).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 같은 본문을 관리자가 보내면 실제로 들어온다 — 위 거부가 본문 결함이 아니었다는 증명.
        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        assertThat(importedRawCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("허용_범위_밖_경로와_권한_없는_요청은_아무것도_남기지_않고_거부한다")
    void 허용_범위_밖_경로와_권한_없는_요청은_아무것도_남기지_않고_거부한다() throws Exception {
        confirmMappings();

        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("folderPath", "/etc", "deidentified", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        // 권한 없는 요청은 위치의 유효성과 무관하게 같은 방식으로 거부된다 — 갈리면 응답이
        //   그 위치의 존재를 알려주는 오라클이 된다.
        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(true, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("folderPath", "/etc", "deidentified", true))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(IMPORTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(true, null)))
                .andExpect(status().isUnauthorized());

        // 영상·프레임·라벨·이관 이력 어느 것도 만들어지지 않는다(AC-048).
        assertThat(importedRawCount()).isZero();
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("확인_기록을_보내도_막는_사유가_남아_있으면_적재되지_않는다")
    void 확인_기록을_보내도_막는_사유가_남아_있으면_적재되지_않는다() throws Exception {
        // 확인 기록은 감사 기록일 뿐 차단 사유를 해제하지 않는다(API-206).
        String body = objectMapper.writeValueAsString(Map.of(
                "folderPath", ImportSampleFolder.path().toString(),
                "deidentified", true,
                "acknowledgedWarnings", List.of("UNPAIRED_IMAGE", "DECLARED_COUNT_MISMATCH")));

        mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(importedRawCount()).isZero();
    }

    // ------------------------------------------------------- 프레임 서빙·학습데이터 산출

    @Test
    @DisplayName("가져온_프레임이_열리고_승인하면_학습데이터_산출이_성공으로_마감된다")
    void 가져온_프레임이_열리고_승인하면_학습데이터_산출이_성공으로_마감된다() throws Exception {
        confirmMappings();
        long rawSn = importFolder(true, null);
        long srcSn = firstSrcSn(rawSn);

        // ★ 프레임 이미지가 열려야 한다. 이 경로의 영상은 적재 직후 곧바로 검수 대기라, 프레임을 못
        //   보면 검수 자체가 성립하지 않는다(검수 화면 백지).
        mockMvc.perform(get("/v1/frames/" + srcSn + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        // 비식별 완료본으로 가져왔으므로 비식별 전용 경로로도 열린다.
        mockMvc.perform(get("/v1/frames/" + srcSn + "/deid-image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 비식별이 끝났다고 지정해 가져왔으므로 보류가 없고 곧바로 승인할 수 있다.
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/start")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 승인이 이미 띄워 둔 비동기 산출을 먼저 끝낸다 — 그것과 아래 동기 산출이 겹치면 산출 행이
        //   둘 생기고 마감 순서가 정해지지 않는다(자세한 사정은 바로 아래 단언 주석).
        drainAsyncExports();

        // ★★ 이 단언이 이 시험의 중심이다. 프레임 저장 위치가 원본·비식별 서브트리 규약 밖이면
        //    산출 단계가 전 프레임을 거부해 <b>한 장도 쓰지 못하고 실패로 마감</b>되고, 그러면 관제
        //    완료 통지가 나가지 않은 채 실패 회수기가 영원히 다시 시도한다. 경로 문자열만 단언하면
        //    그 상태가 그대로 통과한다.
        DatasetExportOutcome outcome = datasetExportService.export(rawSn, true);
        assertThat(outcome).isEqualTo(DatasetExportOutcome.COMPLETED);

        // ── 왜 「전 행」을 보고 왜 「기다리는가」 ────────────────────────────────────────
        //  검수 승인은 커밋된 뒤(AFTER_COMMIT) <b>스스로 또 하나의 산출</b>을 별도 스레드에서 띄운다.
        //  그래서 위 동기 산출까지 합치면 같은 영상에 산출 행이 둘 생기고, 두 행이 마감되는 순서는
        //  정해져 있지 않다. 구 단언은 최신 1행만(ORDER BY output_sn DESC LIMIT 1) 집었는데, 비동기
        //  행이 나중에 들어오면 그 행은 아직 마감 전(PENDING)이라 <b>확률적으로</b> 실패했다.
        //  ⇒ 최신 1행으로 되돌리지 말 것.
        //  ⇒ "성공한 행이 하나라도 있으면 통과"로 완화하지도 말 것 — 위 동기 산출이 실제로 실패해도
        //    다른 행이 그것을 가려 주어, 이 시험의 중심(★★)이 무력해진다.
        //  그래서 <b>모든 행</b>이 마감되고 <b>모두 성공</b>이며 프레임 수까지 맞는지를 본다.
        Awaitility.await().atMost(EXPORT_SETTLE_TIMEOUT).pollInterval(EXPORT_SETTLE_POLL)
                .untilAsserted(() -> {
                    List<Map<String, Object>> exports = jdbc.queryForList(
                            "SELECT output_stts_cd, frme_cnt FROM ls_dataset_export "
                                    + "WHERE data_raw_sn = ? ORDER BY output_sn", rawSn);
                    assertThat(exports).isNotEmpty().allSatisfy(row -> {
                        assertThat(row.get("output_stts_cd")).isEqualTo("SUCCEEDED");
                        assertThat(row.get("frme_cnt")).isNotNull();
                        assertThat(((Number) row.get("frme_cnt")).intValue())
                                .isEqualTo(ImportSampleFolder.FRAME_COUNT);
                    });
                });
    }

    @Test
    @DisplayName("이관_프레임은_원본_비식별_서브트리_규약_위에_놓인다")
    void 이관_프레임은_원본_비식별_서브트리_규약_위에_놓인다() throws Exception {
        confirmMappings();

        long deidRawSn = importFolder(true, null);
        String deidPath = jdbc.queryForObject(
                "SELECT de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn = ? "
                        + "ORDER BY frm_no LIMIT 1", String.class, deidRawSn);
        // 두 기준경로가 같은 디렉터리일 수 있어, 어느 벌인지 가리는 것은 기준경로가 아니라 이 접두다.
        assertThat(deidPath).contains("/frames/deid/" + ImportSampleFolder.VMS_CLIP_ID + "/");
        assertThat(Files.isRegularFile(Paths.get(deidPath))).isTrue();

        cleanup();
        labelId = insertLabel();
        insertEventType();
        confirmMappings();

        long rawRawSn = importFolder(false, null);
        String srcPath = jdbc.queryForObject(
                "SELECT src_file_path_nm FROM ls_data_src WHERE raw_sn = ? ORDER BY frm_no LIMIT 1",
                String.class, rawRawSn);
        assertThat(srcPath).contains("/frames/raw/" + ImportSampleFolder.VMS_CLIP_ID + "/");
        assertThat(Files.isRegularFile(Paths.get(srcPath))).isTrue();
    }

    // ------------------------------------------------------------------ 보조

    /**
     * 검수 승인이 띄운 비동기 학습데이터 산출이 끝날 때까지 기다린다.
     *
     * <p>승인 요청이 200 으로 돌아온 시점에 그 산출은 이미 산출 전용 풀의 대기열에 올라가 있다 —
     * 승인 커밋 직후 도는 리스너가 <b>승인 요청 스레드에서</b> 제출하기 때문이다. 그래서 이 풀이 비는
     * 것을 곧 그 산출이 끝난 것으로 볼 수 있다.
     *
     * <p>산출 행이 생기기를 기다리지 않는 이유: 산출은 <b>행을 남기지 않고</b> 끝나는 종결도 있어
     * (산출할 입력이 없거나 정책적으로 보류될 때) 행을 기다리면 그때 영영 멈춘다.
     *
     * <p>고정 대기(sleep)를 쓰지 않는다 — 느린 장비에서는 모자라고 빠른 장비에서는 낭비다.
     */
    private void drainAsyncExports() {
        if (!(batchAsyncExecutor instanceof ThreadPoolTaskExecutor executor)) {
            // 조용히 넘어가지 않는다 — 여기서 no-op 이 되면 아래 단언이 비동기 산출을 못 기다려
            //   이 시험이 다시 확률적으로 실패한다. 그때 원인이 "가드가 멈춘 것"임을 알 수 없다.
            throw new IllegalStateException(
                    "batchAsyncExecutor 가 ThreadPoolTaskExecutor 가 아니다: "
                            + batchAsyncExecutor.getClass().getName()
                            + " — 산출 대기 방식을 이 타입에 맞게 고쳐야 한다.");
        }
        var pool = executor.getThreadPoolExecutor();
        Awaitility.await().atMost(EXPORT_SETTLE_TIMEOUT).pollInterval(EXPORT_SETTLE_POLL)
                .until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
    }

    /** 프레임 개인정보 3필드 — 프레임마다 같은 값이면 1건으로 접어 돌려준다. */
    private java.util.Set<Map<String, String>> framePrivacy(long rawSn) {
        java.util.Set<Map<String, String>> values = new java.util.LinkedHashSet<>();
        jdbc.query("SELECT anony_incl_yn, psdo_incl_yn, prvc_incl_yn FROM ls_data_src "
                        + "WHERE raw_sn = ? ORDER BY frm_no",
                rs -> {
                    values.add(Map.of("anony_incl_yn", String.valueOf(rs.getString(1)).trim(),
                            "psdo_incl_yn", String.valueOf(rs.getString(2)).trim(),
                            "prvc_incl_yn", String.valueOf(rs.getString(3)).trim()));
                }, rawSn);
        return values;
    }

    private long importFolder(boolean deidentified, String videoPath) throws Exception {
        MvcResult result = mockMvc.perform(post(IMPORTS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(deidentified, videoPath)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.frameCount").value(ImportSampleFolder.FRAME_COUNT))
                .andExpect(jsonPath("$.data.labelCount").value(ImportSampleFolder.LABEL_COUNT))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("rawSn").asLong();
    }

    private String importBody(boolean deidentified, String videoPath) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("folderPath", ImportSampleFolder.path().toString());
        body.put("deidentified", deidentified);
        if (videoPath != null) {
            body.put("videoPath", videoPath);
        }
        return objectMapper.writeValueAsString(body);
    }

    /** 도형 축·이벤트 축 대응을 사람이 확정한 상태로 만든다 — 자동 확정 통로는 없다. */
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
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private long firstSrcSn(long rawSn) {
        return jdbc.queryForObject(
                "SELECT src_sn FROM ls_data_src WHERE raw_sn = ? ORDER BY frm_no LIMIT 1",
                Long.class, rawSn);
    }

    private long countFrames(long rawSn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ls_data_src WHERE raw_sn = ?",
                Long.class, rawSn);
    }

    private long countLabels(long rawSn) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_data_lbl l JOIN ls_data_src s ON s.src_sn = l.src_sn "
                        + "WHERE s.raw_sn = ?", Long.class, rawSn);
    }

    private long countFramesWithoutLabel(long rawSn) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_data_src s WHERE s.raw_sn = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM ls_data_lbl l WHERE l.src_sn = s.src_sn)",
                Long.class, rawSn);
    }

    private long importedRawCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ls_data_raw WHERE src_type = 'IMPORTED'",
                Long.class);
    }

    private long historyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ls_otsd_datst_trnsf_hstry", Long.class);
    }

    private long insertLabel() {
        jdbc.update("DELETE FROM ls_label WHERE lbl_nm = ?", MAPPING_LABEL_NAME);
        return jdbc.queryForObject(
                "INSERT INTO ls_label (lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_dt) "
                        + "VALUES (?, '#FF0000', 'POLYGON', 998, 'Y', CURRENT_TIMESTAMP) RETURNING lbl_id",
                Long.class, MAPPING_LABEL_NAME);
    }

    private void insertEventType() {
        jdbc.update("INSERT INTO ls_evnt_type (evnt_type_cd, evnt_nm, clct_yn, reg_dt) "
                + "VALUES (?, ?, 'Y', CURRENT_TIMESTAMP) ON CONFLICT (evnt_type_cd) DO NOTHING",
                EVENT_TYPE_CD, "이관시험이벤트");
        // 등록 유형 판정이 장수명 캐시를 타므로, 새로 넣은 유형이 보이도록 캐시를 비운다.
        //   비우지 않으면 앞선 시험이 채운 스냅샷 때문에 이 유형이 "미등록"으로 판정된다.
        eventTypeCacheEvictor.evictNow();
    }

    private void cleanup() {
        // 이관으로 만들어진 영상만 지운다 — 다른 시험이 만든 영상을 건드리지 않는다.
        jdbc.update("DELETE FROM ls_data_lbl WHERE src_sn IN (SELECT src_sn FROM ls_data_src "
                + "WHERE raw_sn IN (SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED'))");
        jdbc.update("DELETE FROM ls_data_src WHERE raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_data_meta WHERE raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE src_type = 'IMPORTED')");
        jdbc.update("DELETE FROM ls_deident_proc_log WHERE data_raw_sn IN "
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

    /** 시험이 옮겨 놓은 파일을 지운다 — 남기면 다음 회차가 이미 있는 파일 위에서 돈다. */
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
