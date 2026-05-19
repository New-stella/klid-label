package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCR-AUG-001 — {@code GET /v1/videos?reviewStatusCd=...} 검수 상태 필터 검증.
 *
 * <p>증강 요청 화면에서 검수 완료(APPROVED) 영상만 선택 가능하도록, 영상 목록 API 에
 * {@code reviewStatusCd} 파라미터를 추가한다. 검수 상태는 별도 테이블 {@code LS_RAW_DATA_STATUS}
 * 에 저장되어 있으며 본 필터는 INNER JOIN 으로 적용된다.
 *
 * <p>회귀 방지: 기존 호출(dataSttsCd 단독)은 그대로 동작해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoListReviewStatusFilterTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository statusRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private LsDataRaw seedCompletedVideo(String clipId, String cctvId, String evntType) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, cctvId, evntType, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus("COMPLETED");
        return videoRepository.save(raw);
    }

    private void seedStatus(Long rawSn, String sttsCd) {
        LsRawDataStatus s = LsRawDataStatus.initial(rawSn);
        s.transitionTo(sttsCd);
        statusRepository.save(s);
    }

    @Test
    @DisplayName("reviewStatusCd_APPROVED_요청시_검수_완료_영상만_반환")
    void approvedOnly() throws Exception {
        // given: 3개 영상 — APPROVED / IN_REVIEW / PENDING
        LsDataRaw v1 = seedCompletedVideo("CLIP-APPROVED-1", "CCTV-001", "EVT-FIRE");
        LsDataRaw v2 = seedCompletedVideo("CLIP-INREVIEW-1", "CCTV-001", "EVT-FALL");
        LsDataRaw v3 = seedCompletedVideo("CLIP-PENDING-1", "CCTV-002", "EVT-FIRE");
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        seedStatus(v2.getRawSn(), LsRawDataStatus.STTS_IN_REVIEW);
        seedStatus(v3.getRawSn(), LsRawDataStatus.STTS_PENDING);

        // when / then
        mockMvc.perform(get("/v1/videos?reviewStatusCd=APPROVED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(v1.getRawSn()));
    }

    @Test
    @DisplayName("검수_미완료_영상_은_결과에서_제외됨")
    void incompleteReviewExcluded() throws Exception {
        // given: APPROVED 1건 + REJECTED 1건 + IN_REVIEW 1건
        LsDataRaw v1 = seedCompletedVideo("CLIP-APPROVED-2", "CCTV-001", "EVT-FIRE");
        LsDataRaw v2 = seedCompletedVideo("CLIP-REJECTED-1", "CCTV-001", "EVT-FALL");
        LsDataRaw v3 = seedCompletedVideo("CLIP-INREVIEW-2", "CCTV-002", "EVT-FIRE");
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        seedStatus(v2.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        seedStatus(v3.getRawSn(), LsRawDataStatus.STTS_IN_REVIEW);

        // when / then
        mockMvc.perform(get("/v1/videos?reviewStatusCd=APPROVED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("LsRawDataStatus_row가_없는_영상_은_결과에서_제외됨")
    void noStatusRowExcluded() throws Exception {
        // given: APPROVED 1건 + status row 없는 영상 1건
        LsDataRaw v1 = seedCompletedVideo("CLIP-APPROVED-3", "CCTV-001", "EVT-FIRE");
        LsDataRaw v2 = seedCompletedVideo("CLIP-NO-STATUS-1", "CCTV-002", "EVT-FALL");
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        // v2 는 LS_RAW_DATA_STATUS 미생성

        // when / then
        mockMvc.perform(get("/v1/videos?reviewStatusCd=APPROVED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(v1.getRawSn()));
    }

    @Test
    @DisplayName("dataSttsCd와_reviewStatusCd_동시_지정시_AND_조건_적용")
    void bothFiltersAndCondition() throws Exception {
        // given:
        // - v1: dataSttsCd=COMPLETED + 검수=APPROVED  → 통과
        // - v2: dataSttsCd=PENDING  + 검수=APPROVED  → 배치 미완료 → 제외
        // - v3: dataSttsCd=COMPLETED + 검수=REJECTED  → 검수 미완료 → 제외
        LsDataRaw v1 = seedCompletedVideo("CLIP-AND-1", "CCTV-001", "EVT-FIRE");
        LsDataRaw v2 = seedCompletedVideo("CLIP-AND-2", "CCTV-001", "EVT-FALL");
        LsDataRaw v3 = seedCompletedVideo("CLIP-AND-3", "CCTV-002", "EVT-FIRE");
        // v2 는 PENDING 으로 되돌림
        v2.changeStatus("PENDING");
        videoRepository.save(v2);
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        seedStatus(v2.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        seedStatus(v3.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        // when / then
        mockMvc.perform(get("/v1/videos?dataSttsCd=COMPLETED&reviewStatusCd=APPROVED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(v1.getRawSn()));
    }

    @Test
    @DisplayName("두_필터_모두_미지정시_전체_영상_반환_회귀_보장")
    void noFilterReturnsAll() throws Exception {
        LsDataRaw v1 = seedCompletedVideo("CLIP-ALL-1", "CCTV-001", "EVT-FIRE");
        LsDataRaw v2 = seedCompletedVideo("CLIP-ALL-2", "CCTV-002", "EVT-FALL");
        // status row 없는 영상도 두 필터 미지정 시 포함되어야 함
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        mockMvc.perform(get("/v1/videos?page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("reviewStatusCd_REJECTED_요청시_반려_영상만_반환")
    void rejectedOnly() throws Exception {
        LsDataRaw v1 = seedCompletedVideo("CLIP-REJ-A", "CCTV-001", "EVT-FIRE");
        LsDataRaw v2 = seedCompletedVideo("CLIP-REJ-B", "CCTV-001", "EVT-FALL");
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        seedStatus(v2.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        mockMvc.perform(get("/v1/videos?reviewStatusCd=REJECTED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(v2.getRawSn()));
    }

    @Test
    @DisplayName("reviewStatusCd_공백_문자열은_필터_미적용_blank_무시")
    void blankReviewStatusIgnored() throws Exception {
        LsDataRaw v1 = seedCompletedVideo("CLIP-BLANK-1", "CCTV-001", "EVT-FIRE");
        LsDataRaw v2 = seedCompletedVideo("CLIP-BLANK-2", "CCTV-002", "EVT-FALL");
        // status row 없는 영상 — blank 필터 시 모두 반환되어야 함
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        mockMvc.perform(get("/v1/videos?reviewStatusCd=&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("reviewStatusCd_길이_과대_또는_SQL_injection_시도시_안전하게_빈결과")
    void maliciousLongInputSafelyHandled() throws Exception {
        LsDataRaw v1 = seedCompletedVideo("CLIP-MAL-1", "CCTV-001", "EVT-FIRE");
        seedStatus(v1.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        // 길이 20 초과 — 사전 검증 또는 빈 결과 (파라미터 바인딩으로 SQL injection 자체는 차단)
        String malicious = "APPROVED'; DROP TABLE LS_DATA_RAW;--";
        mockMvc.perform(get("/v1/videos?reviewStatusCd=" + malicious + "&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
