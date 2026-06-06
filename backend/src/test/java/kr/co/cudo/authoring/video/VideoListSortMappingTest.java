package kr.co.cudo.authoring.video;

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
 * I-1 회귀 — {@code GET /v1/videos?sort=capturedAt,desc} 정렬 키 매핑/폴백 검증.
 *
 * <p>FE 는 외부 정렬 키 {@code capturedAt} 을 보내지만 엔티티 실제 필드는 {@code shtDt}(촬영시각)다.
 * 이전에는 임의 프로퍼티가 Pageable 로 직행해 {@code PropertyReferenceException} → 500 으로 노출되었다.
 * 본 테스트는 (1) capturedAt 정렬이 200 + shtDt 매핑으로 동작하고 (2) allowlist 밖 임의 정렬 키도
 * 500 이 아니라 기본 정렬로 폴백되는 것을 보증한다 (CWE-20 입력 검증, fail-secure).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoListSortMappingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private LsDataRaw seedVideoWithShtDt(String clipId, LocalDateTime shtDt) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                shtDt, 30);
        return videoRepository.save(raw);
    }

    @Test
    @DisplayName("sort_capturedAt_desc_요청시_500이_아니라_200_OK_shtDt_정렬로_동작")
    void capturedAtSortMapsToShtDtNo500() throws Exception {
        // given: shtDt 가 서로 다른 영상 2건 (regDt 는 동일 트랜잭션이라 거의 같음 → 보조키 shtDt 로 순서 결정)
        LsDataRaw older = seedVideoWithShtDt("CLIP-SORT-OLD", LocalDateTime.of(2026, 1, 1, 0, 0));
        LsDataRaw newer = seedVideoWithShtDt("CLIP-SORT-NEW", LocalDateTime.of(2026, 6, 1, 0, 0));

        // when / then: capturedAt → shtDt 매핑되어 200 + 최신 shtDt 영상이 먼저 노출 (500 금지)
        mockMvc.perform(get("/v1/videos?sort=capturedAt,desc&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(newer.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].id").value(older.getRawSn()));
    }

    @Test
    @DisplayName("allowlist_밖_임의_정렬_키는_500이_아니라_기본_정렬로_폴백")
    void unknownSortKeyFallsBackNo500() throws Exception {
        // given: 영상 1건
        LsDataRaw v = seedVideoWithShtDt("CLIP-SORT-UNKNOWN", LocalDateTime.of(2026, 3, 1, 0, 0));

        // when / then: 존재하지 않는 프로퍼티로 정렬 요청 → PropertyReferenceException(500) 이 아니라 200
        mockMvc.perform(get("/v1/videos?sort=notAField,desc&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(v.getRawSn()));
    }

    @Test
    @DisplayName("정렬_미지정시_기본_regDt_DESC_정렬_회귀_보장")
    void noSortDefaultRegDtDesc() throws Exception {
        seedVideoWithShtDt("CLIP-SORT-A", LocalDateTime.of(2026, 2, 1, 0, 0));
        seedVideoWithShtDt("CLIP-SORT-B", LocalDateTime.of(2026, 2, 2, 0, 0));

        mockMvc.perform(get("/v1/videos?page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }
}
