package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.review.dto.BatchApproveRequest;
import kr.co.cudo.authoring.review.service.ReviewBatchApprovePolicy;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>검수 일괄 승인</b> 회귀 가드 — {@code POST /v1/reviews/batch/approve}.
 *
 * <h3>이 시험이 지키는 것 (API-250 · AC-1113 · AC-1114 · ADR-067)</h3>
 * <ul>
 *   <li><b>부분 실패</b> — 되는 것만 처리하고 안 된 건은 사유와 함께 돌아온다. ★한 건의 실패가 다른
 *       건을 되돌리지 않는다(처리 경계가 건별).</li>
 *   <li><b>자격 ①</b> — 내가 점유한 건만 승인된다. 남이 점유한 건·아무도 열지 않은 건은 실패다.
 *       이것이 「한 번도 열어 보지 않은 영상을 무더기로 승인하는 길」을 막는 안전장치다.</li>
 *   <li><b>자격 ②</b> — 상태 축은 단건 승인의 판정을 그대로 쓴다. <b>재검수 건</b>(승인 + 재검토
 *       표시)도 담긴다 — 「검수 진행 중인 것만」으로 좁히면 그 건이 영영 빠진다.</li>
 *   <li><b>★단건과 같은 결과</b> — 개수만 세지 않고 <b>부수효과</b>(상태 확정 · 버전 스냅샷 ·
 *       승인 이력과 행위 시점 역할 · 재검토 표시 해제)를 직접 단언한다. 개수만 세면 승인 절차 중
 *       무엇이 빠져도 초록이다.</li>
 *   <li><b>요청 단위 거부</b> — 빈 목록·상한 초과는 400 이고 <b>한 건도</b> 처리되지 않는다. 이는
 *       개별 영상의 상태·게이트 위반(건별 실패)과 다른 축이다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReviewBatchApproveIT {

    private static final long REVIEWER_A = 1L;
    private static final long REVIEWER_B = 1001L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskEventLogRepository taskEventLogRepository;
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private ReviewBatchApprovePolicy policy;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        tokenA = JwtTestSupport.token(secret, String.valueOf(REVIEWER_A), "REVIEWER", "INTERNAL", issuer, 60);
        tokenB = JwtTestSupport.token(secret, String.valueOf(REVIEWER_B), "REVIEWER", "INTERNAL", issuer, 60);
    }

    /**
     * 변경지시서의 수용기준 그대로 — 내가 점유한 3건 + 남이 점유한 1건 + 비식별 미완료 1건.
     *
     * <p>성공 3건은 <b>단건 승인과 같은 결과</b>여야 하고, 실패 2건은 <b>서로 다른 사유</b>로 담겨야
     * 하며, 무엇보다 실패가 성공을 <b>되돌리지 않아야</b> 한다.
     */
    @Test
    @DisplayName("일괄승인_내가_점유한_3건은_성공하고_남의_점유_1건과_비식별_미완료_1건은_각각의_사유로_실패한다")
    void partialFailureDoesNotRollBackSuccesses() throws Exception {
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long id = seedReviewable("CLIP-BATCH-M" + i);
            start(id, tokenA).andExpect(status().isOk());
            mine.add(id);
        }
        Long othersClaim = seedReviewable("CLIP-BATCH-OTHER");
        start(othersClaim, tokenB).andExpect(status().isOk());

        Long deidentPending = seedReviewable("CLIP-BATCH-DEID");
        start(deidentPending, tokenA).andExpect(status().isOk());
        markDeidentNotCompleted(deidentPending);

        List<Long> all = new ArrayList<>(mine);
        all.add(othersClaim);
        all.add(deidentPending);

        batchApprove(all, tokenA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.successCount").value(3))
                .andExpect(jsonPath("$.data.failureCount").value(2))
                // 요청한 식별자마다 한 항목 — 전건이 담긴다.
                .andExpect(jsonPath("$.data.results.length()").value(5))
                .andExpect(jsonPath("$.data.results[?(@.videoId == " + othersClaim + ")].errorCode")
                        .value(org.hamcrest.Matchers.contains("CONFLICT")))
                .andExpect(jsonPath("$.data.results[?(@.videoId == " + deidentPending + ")].errorCode")
                        .value(org.hamcrest.Matchers.contains("PRECONDITION_FAILED")))
                // 성공 건은 사유가 비어 있다.
                .andExpect(jsonPath("$.data.results[?(@.videoId == " + mine.get(0) + ")].reason")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));

        // ★부수효과 — 개수가 아니라 「단건 승인과 같은 결과인가」를 본다.
        for (Long id : mine) {
            assertThat(statusOf(id))
                    .as("성공 건 %s 가 확정되지 않았다", id)
                    .isEqualTo(LsRawDataStatus.STTS_APPROVED);
            assertThat(labelVersionRepository.findFirstByDataRawSnOrderByVersionNoDesc(id))
                    .as("성공 건 %s 에 승인 버전 스냅샷이 없다 — 단건 승인과 결과가 다르다", id)
                    .isPresent();
            assertThat(approveEventsOf(id))
                    .as("성공 건 %s 에 승인 이력이 없다", id)
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getActorUserNo()).isEqualTo(REVIEWER_A);
                        // 행위 시점 역할이 함께 남는다 — 관리자가 눌렀다면 ADMIN 이 남을 자리다.
                        assertThat(e.getActorRoleCd()).isEqualTo("REVIEWER");
                    });
        }
        // 실패 2건은 <b>전혀 건드려지지 않았다</b> — 한 건의 실패가 다른 건을 되돌리지도 않았다.
        assertThat(statusOf(othersClaim)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
        assertThat(statusOf(deidentPending)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
        assertThat(approveEventsOf(othersClaim)).isEmpty();
        assertThat(approveEventsOf(deidentPending)).isEmpty();
    }

    /**
     * ★재검수 건은 <b>승인 상태에 머물러 있다</b>. 자격의 상태 축을 「검수 진행 중」으로 좁히면 이
     * 건이 영영 담기지 않는다 — 그래서 단건 승인의 판정을 그대로 쓴다.
     */
    @Test
    @DisplayName("재검수_건도_일괄승인_대상이_된다_상태가_APPROVED라고_빠지지_않는다")
    void recheckItemIsBatchApprovable() throws Exception {
        Long id = seedReviewable("CLIP-BATCH-RECHECK");
        LsRawDataStatus stts = dataSttsRepository.findById(id).orElseThrow();
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        stts.markNeedsRecheck();
        dataSttsRepository.saveAndFlush(stts);

        // 재검수 건도 먼저 검수 시작으로 점유를 세운다 — 상태는 승인 그대로 남는다.
        start(id, tokenA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value(LsRawDataStatus.STTS_APPROVED))
                .andExpect(jsonPath("$.data.needsRecheck").value(true))
                // 자격 표시가 이 시점에 이미 켜져 있어야 한다(화면이 체크칸을 켜는 근거).
                .andExpect(jsonPath("$.data.bulkApprovable").value(true));

        batchApprove(List.of(id), tokenA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(1));

        LsRawDataStatus after = dataSttsRepository.findById(id).orElseThrow();
        assertThat(after.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_APPROVED);
        // 재승인 갈래를 그대로 탔다 — 재검토 표시가 해제됐다.
        assertThat(after.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("아무도_점유하지_않은_건은_일괄승인에서_실패로_담긴다_열어보지_않은_영상은_승인되지_않는다")
    void unclaimedItemFails() throws Exception {
        Long id = seedReviewable("CLIP-BATCH-UNCLAIMED");
        dataSttsRepository.findById(id).ifPresent(s -> {
            s.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
            dataSttsRepository.saveAndFlush(s);
        });

        batchApprove(List.of(id), tokenA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failureCount").value(1))
                .andExpect(jsonPath("$.data.results[0].errorCode").value("CONFLICT"));

        assertThat(statusOf(id)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
        assertThat(approveEventsOf(id)).isEmpty();
    }

    @Test
    @DisplayName("상한을_넘긴_요청은_400이고_한_건도_처리되지_않는다")
    void exceedingLimitRejectsWholeRequest() throws Exception {
        int limit = policy.limit();
        List<Long> ids = new ArrayList<>();
        Long real = seedReviewable("CLIP-BATCH-LIMIT");
        start(real, tokenA).andExpect(status().isOk());
        ids.add(real);
        // 나머지는 실재하지 않아도 된다 — 상한 판정은 목록 길이만 본다(한 건도 처리되지 않으므로).
        for (long i = 1; i <= limit; i++) {
            ids.add(900_000_000L + i);
        }

        batchApprove(ids, tokenA)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        // ★한 건도 처리되지 않았다 — 실재하며 자격까지 갖춘 건조차 승인되지 않는다.
        assertThat(statusOf(real)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
        assertThat(approveEventsOf(real)).isEmpty();
    }

    @Test
    @DisplayName("빈_목록은_400이다")
    void emptyListIsRejected() throws Exception {
        batchApprove(List.of(), tokenA)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("중복_식별자는_한_건으로_취급된다")
    void duplicateIdsCountOnce() throws Exception {
        Long id = seedReviewable("CLIP-BATCH-DUP");
        start(id, tokenA).andExpect(status().isOk());

        batchApprove(List.of(id, id, id), tokenA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.successCount").value(1));
    }

    /**
     * 묶음 자리가 식별자 자리를 가리지 않는지 — {@code /reviews/batch/approve} 가 단건 승인
     * {@code /reviews/{videoId}/approve} 로 잘못 매칭되면 식별자 변환 실패로 400 이 된다.
     */
    @Test
    @DisplayName("일괄승인_주소가_단건승인_경로에_먹히지_않는다")
    void batchPathIsNotSwallowedBySingleApprovePath() throws Exception {
        Long id = seedReviewable("CLIP-BATCH-ROUTE");
        start(id, tokenA).andExpect(status().isOk());

        // 단건 경로로 해석됐다면 본문 구조가 달라 승인 결과 목록이 나올 수 없다.
        batchApprove(List.of(id), tokenA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results").isArray());
    }

    @Test
    @DisplayName("작업자는_일괄승인_창구에서_403이다")
    void workerIsForbidden() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        batchApprove(List.of(1L), workerToken).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------- helpers

    private ResultActions batchApprove(List<Long> ids, String token) throws Exception {
        return mockMvc.perform(post("/v1/reviews/batch/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BatchApproveRequest(ids))));
    }

    private ResultActions start(Long id, String token) throws Exception {
        return mockMvc.perform(post("/v1/reviews/" + id + "/start")
                .header("Authorization", "Bearer " + token));
    }

    /** 검수 대기 + 라벨 1건 — 승인 사전 게이트(라벨 0건 차단)를 통과하는 최소 형태. */
    private Long seedReviewable(String clipId) {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-BATCH", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30));
        Long id = raw.getRawSn();
        LsRawDataStatus stts = LsRawDataStatus.initial(id);
        dataSttsRepository.saveAndFlush(stts);
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(id, 0, "/var/raw/" + clipId + "-f0.jpg", LocalDateTime.now()));
        labelRepository.save(LsDataLbl.createManual(frame.getSrcSn(), "BBOX", null,
                "person", "[[1.0,1.0],[2.0,2.0]]", "100"));
        return id;
    }

    private void markDeidentNotCompleted(Long id) {
        LsRawDataStatus stts = dataSttsRepository.findById(id).orElseThrow();
        stts.markDeidentNotCompleted();
        dataSttsRepository.saveAndFlush(stts);
    }

    private String statusOf(Long id) {
        return dataSttsRepository.findById(id).orElseThrow().getDataSttsCd();
    }

    private List<LsTaskEventLog> approveEventsOf(Long id) {
        return taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(id).stream()
                .filter(e -> LsTaskEventLog.EVENT_APPROVE.equals(e.getEventTypeCd()))
                .toList();
    }
}
