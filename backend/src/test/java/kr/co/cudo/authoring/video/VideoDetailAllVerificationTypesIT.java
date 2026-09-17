package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntType;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntTypeRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 상세({@code GET /v1/videos/{rawSn}})의 검증 이벤트 유형 전체 목록 — <b>작업자 토큰</b> 종단 검증.
 * [@design API-043]
 *
 * <p><b>왜 종단으로 보나</b>: 이 필드의 존재 이유는 「유형 이름을 주는 다른 조회 경로는 검수자 전용이라
 * 작업자가 쓸 수 없다」는 것이다. 서비스 단위시험은 인가를 지나가지 않으므로, 본인에게 배정된 영상을
 * <b>작업자 토큰으로</b> 조회했을 때 실 DB 카탈로그가 정렬대로 실려 오는지를 여기서 고정한다.
 *
 * <p>기대값은 하드코딩하지 않고 <b>같은 저장소 조회 결과</b>와 대조한다 — 카탈로그 시드가 바뀌어도
 * 이 시험의 판정 축(전체·정렬 순서·코드와 이름)은 그대로다. 대신 비어 있으면 대조가 공허해지므로
 * 시드가 1건 이상인지 먼저 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoDetailAllVerificationTypesIT {

    /** {@code test-data.sql} 시드 — 1=REVIEWER, 100=WORKER. */
    private static final long REVIEWER_NO = 1L;
    private static final long WORKER_NO = 100L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsVrfcEvntTypeRepository vrfcEvntTypeRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String workerToken;
    private Long rawSn;

    @BeforeEach
    void setUp() {
        workerToken = JwtTestSupport.token(secret, String.valueOf(WORKER_NO), "WORKER", "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-allvrfc-" + System.nanoTime(), "CCTV-ALLVRFC", null, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/allvrfc.mp4",
                LocalDateTime.of(2026, 5, 10, 0, 0, 0), 30);
        rawSn = videoRepository.save(raw).getRawSn();
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, WORKER_NO, REVIEWER_NO));
    }

    private ResultActions detailAsWorker() throws Exception {
        return mockMvc.perform(get("/v1/videos/" + rawSn)
                .header("Authorization", "Bearer " + workerToken));
    }

    @Test
    @DisplayName("★작업자_토큰으로_배정_영상_상세를_조회하면_검증이벤트유형_전체가_정렬순서대로_코드와_이름으로_온다")
    void workerReceivesWholeCatalogInSortOrder() throws Exception {
        List<LsVrfcEvntType> expected = vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc();
        assertThat(expected).as("대조가 공허하지 않으려면 카탈로그 시드가 있어야 한다").isNotEmpty();

        ResultActions result = detailAsWorker()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allVrfcEvntTypes", hasSize(expected.size())));
        for (int i = 0; i < expected.size(); i++) {
            result.andExpect(jsonPath("$.data.allVrfcEvntTypes[" + i + "].vrfcEvntTypeCd")
                            .value(expected.get(i).getVrfcEvntTypeCd()))
                    .andExpect(jsonPath("$.data.allVrfcEvntTypes[" + i + "].vrfcEvntTypeNm")
                            .value(expected.get(i).getVrfcEvntTypeNm()));
        }
        // 질문 목록은 원소에 싣지 않는다.
        result.andExpect(jsonPath("$.data.allVrfcEvntTypes[0].questions").doesNotExist());
    }
}
