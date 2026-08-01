package kr.co.cudo.authoring.assignment;

import com.jayway.jsonpath.JsonPath;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 (R1) — 작업목록 정렬 정책: <b>시간축 단일 정렬</b>.
 *
 * <p>구 정책(반려&gt;검수대기&gt;배정&gt;미배정&gt;완료 상태 우선순위 정렬, R2 AC2)은 폐기됐다 —
 * 작업목록에서 REVIEWER 가 수행하는 고유 액션은 배정/재배정인데 검수 축 우선순위가 미배정 건을
 * 아래로 밀어냈기 때문이다. 우선순위는 이제 {@code workStatus} 필터로 표현하고
 * (검증: {@code TaskBoardFilterSortTest}), 정렬은 등록일(REG_DT) 최신순 단일 축이다.
 *
 * <p>본 클래스는 구 {@code TaskBoardStatusPrioritySortTest} 를 대체하며, 정렬 정책 전환 이후에도
 * 응답 스키마(파생영상 표기)와 배치 상태 필터·페이징이 회귀하지 않음을 함께 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskBoardTimeOrderSortTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;

    private final JdbcTemplate jdbc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    TaskBoardTimeOrderSortTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private LsDataRaw seedCompletedVideo(String clipId) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus("COMPLETED");
        return videoRepository.save(raw);
    }

    private void assignAndSetWorkflow(Long rawSn, String workflowStatus) {
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);
    }

    private void forceRegDt(Long rawSn, LocalDateTime regDt) {
        jdbc.update("UPDATE LS_DATA_RAW SET REG_DT = ? WHERE RAW_SN = ?", Timestamp.valueOf(regDt), rawSn);
    }

    @Test
    @DisplayName("최신_등록일이_상태와_무관하게_최상단으로_정렬된다")
    void latestRegDtFirstRegardlessOfStatus() throws Exception {
        // given: 구 정책이라면 최상단이었을 반려 건이 가장 오래된 등록일
        LsDataRaw rejected = seedCompletedVideo("CLIP-T-REJECTED");
        assignAndSetWorkflow(rejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw approved = seedCompletedVideo("CLIP-T-APPROVED");
        assignAndSetWorkflow(approved.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        forceRegDt(rejected.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0));
        forceRegDt(approved.getRawSn(), LocalDateTime.of(2026, 6, 2, 0, 0));

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].videoId").value(approved.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content[1].videoId").value(rejected.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].status").value("REJECTED"));
    }

    @Test
    @DisplayName("동일_등록일이면_rawSn_내림차순_tiebreak")
    void sameRegDtRawSnTiebreak() throws Exception {
        LsDataRaw first = seedCompletedVideo("CLIP-T-TB-A");   // 작은 rawSn
        assignAndSetWorkflow(first.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw second = seedCompletedVideo("CLIP-T-TB-B");  // 큰 rawSn
        assignAndSetWorkflow(second.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        LocalDateTime sameRegDt = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        forceRegDt(first.getRawSn(), sameRegDt);
        forceRegDt(second.getRawSn(), sameRegDt);

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].videoId").value(second.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].videoId").value(first.getRawSn()));
    }

    /** 원본에서 파생된 해상도 영상(ORGNL_RAW_SN 세팅, COMPLETED)을 생성한다. */
    private LsDataRaw seedDerivedCompletedVideo(LsDataRaw parent, String goalResCd) {
        LsDataRaw derived = LsDataRaw.createFromResolution(
                parent, "/var/raw/deriv-" + goalResCd + ".mp4", goalResCd);
        derived = videoRepository.save(derived);
        derived.changeStatus("COMPLETED");
        return videoRepository.save(derived);
    }

    /** 파생 영상의 {@code VMS_CLIP_ID} 만 임의 값으로 바꾼다 — 판별이 clipId 와 무관함을 보이기 위한 조작. */
    private void forceVmsClipId(Long rawSn, String vmsClipId) {
        jdbc.update("UPDATE LS_DATA_RAW SET VMS_CLIP_ID = ? WHERE RAW_SN = ?", vmsClipId, rawSn);
    }

    /** 파생 영상의 {@code AUG_TYPE_CD} 컬럼만 바꾼다(레거시 코드 재현). */
    private void forceAugTypeCd(Long rawSn, String augTypeCd) {
        jdbc.update("UPDATE LS_DATA_RAW SET AUG_TYPE_CD = ? WHERE RAW_SN = ?", augTypeCd, rawSn);
    }

    /** 파생 영상의 {@code AUG_TYPE_CD} 를 비운다(백필·쓰기측 배선 이전 형상 재현). */
    private void clearAugTypeCd(Long rawSn) {
        jdbc.update("UPDATE LS_DATA_RAW SET AUG_TYPE_CD = NULL WHERE RAW_SN = ?", rawSn);
    }

    private Map<String, Object> boardItem(Long videoId) throws Exception {
        String body = mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items =
                JsonPath.read(body, "$.data.content[?(@.videoId==" + videoId + ")]");
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    @Test
    @DisplayName("작업목록_응답의_augType이_컬럼값으로_반환된다")
    void boardAugTypeFromColumn() throws Exception {
        // given: 해상도 파생 1건. VMS_CLIP_ID 에서 마커를 지워도(구 파서라면 null) 컬럼값이 그대로 나와야 한다.
        LsDataRaw origin = seedCompletedVideo("CLIP-AUG-COL-ORIGIN");
        LsDataRaw derived = seedDerivedCompletedVideo(origin, "RESL_720P");
        forceVmsClipId(derived.getRawSn(), "CLIP-AUG-COL-no-marker");

        // when / then
        Map<String, Object> item = boardItem(derived.getRawSn());
        assertThat(item.get("augmented")).isEqualTo(true);
        assertThat(item.get("augType")).isEqualTo("RESL_720P");
    }

    @Test
    @DisplayName("작업목록_AUG_TYPE_CD가_null인_파생행도_예외없이_augType_null로_응답된다")
    void boardNullAugTypeColumn() throws Exception {
        // given: 백필/쓰기측 배선 이전 경로로 만들어진 파생행(컬럼 미채움) 방어.
        LsDataRaw origin = seedCompletedVideo("CLIP-AUG-NULLCOL-ORIGIN");
        LsDataRaw derived = seedDerivedCompletedVideo(origin, "RESL_480P");
        clearAugTypeCd(derived.getRawSn());

        // when / then: 파생 여부는 유지, 종류만 미상.
        Map<String, Object> item = boardItem(derived.getRawSn());
        assertThat(item.get("augmented")).isEqualTo(true);
        assertThat(item.get("augType")).as("AUG_TYPE_CD 미채움 파생의 augType 은 null").isNull();
    }

    @Test
    @DisplayName("작업목록_레거시_RESOLUTION_값은_FE계약값이_아니므로_augType_null이다")
    void boardLegacyResolutionAugType() throws Exception {
        // given: 통합 이전 레거시 단일 코드(LsDataAug.AUG_RESOLUTION) — 구 파서도 null 을 냈다.
        LsDataRaw origin = seedCompletedVideo("CLIP-AUG-LEGACY-ORIGIN");
        LsDataRaw derived = seedDerivedCompletedVideo(origin, "RESL_480P");
        forceAugTypeCd(derived.getRawSn(), "RESOLUTION");

        // when / then
        Map<String, Object> item = boardItem(derived.getRawSn());
        assertThat(item.get("augmented")).isEqualTo(true);
        assertThat(item.get("augType")).as("FE 계약값 밖의 코드는 노출하지 않는다").isNull();
    }

    @Test
    @DisplayName("작업보드_파생영상은_augmented_true_augType_원본은_false_null")
    void boardAugmentInfo() throws Exception {
        // given: 원본 1건(미배정) + 그 원본의 해상도 파생 1건(미배정)
        LsDataRaw origin = seedCompletedVideo("CLIP-AUG-ORIGIN");
        LsDataRaw derived = seedDerivedCompletedVideo(origin, "RESL_480P");

        // when / then: 두 건 모두 작업 목록에 노출(R2)되고, 파생만 augmented=true + augType=RESL_480P.
        String body = mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + derived.getRawSn() + ")].augmented").value(true))
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + derived.getRawSn() + ")].augType").value("RESL_480P"))
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + origin.getRawSn() + ")].augmented").value(false))
                .andReturn().getResponse().getContentAsString();

        // 원본은 augType 이 없어야 한다 — 값이 null 이든 키가 없든 동일하게 판정되도록 항목을 통째로 읽는다.
        List<Map<String, Object>> originItems =
                JsonPath.read(body, "$.data.content[?(@.videoId==" + origin.getRawSn() + ")]");
        assertThat(originItems).hasSize(1);
        assertThat(originItems.get(0).get("augType")).as("원본 영상의 augType 은 null").isNull();
    }

    @Test
    @DisplayName("기존_board_상태필터·페이징_무회귀")
    void boardFilterAndPagingRegression() throws Exception {
        // given: COMPLETED 배치 영상 2건 + PENDING 배치 영상 1건 (COMPLETED 필터에서 제외되어야 함)
        LsDataRaw done1 = seedCompletedVideo("CLIP-RG-DONE1");
        assignAndSetWorkflow(done1.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw done2 = seedCompletedVideo("CLIP-RG-DONE2");
        assignAndSetWorkflow(done2.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        LsDataRaw pendingBatch = LsDataRaw.createFromIngest(
                "CLIP-RG-PENDING", "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/pending.mp4", LocalDateTime.now(), 30);
        videoRepository.save(pendingBatch); // 배치 상태 PENDING 유지

        forceRegDt(done1.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0));
        forceRegDt(done2.getRawSn(), LocalDateTime.of(2026, 6, 2, 0, 0)); // 더 최신

        // when / then: COMPLETED 필터는 배치 COMPLETED 2건만 반환 (PENDING 제외), 페이징 메타 정상
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=1")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                // page 0 = 등록일 최신 1건 (상태 우선순위 아님)
                .andExpect(jsonPath("$.data.content[0].videoId").value(done2.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"));
    }
}
