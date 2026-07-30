package kr.co.cudo.authoring.augment;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentRequestControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 시드한 부모 영상 — V146 FK(작업상태·프레임·증강 → LS_DATA_RAW) 충족용. */
    private final java.util.Set<Long> seededRawSns = new java.util.LinkedHashSet<>();

    /** 위탁 리스너가 {@code @Async} 로 바뀌었으므로 테스트 간 비동기 잔업을 명시적으로 배수한다. */
    @Autowired @Qualifier("batchAsyncExecutor") private Executor batchAsyncExecutor;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        statusRepository.deleteAll();
    }

    /**
     * 접수(200) 요청이 띄운 <b>비동기 외부 위탁</b>이 끝날 때까지 기다린다 (Phase 8 DEV_FIX HIGH-1).
     *
     * <p>{@code AugmentRequestBridge} 의 AFTER_COMMIT 리스너는 커밋 스레드에서 돌면
     * {@code PROPAGATION_REQUIRED} 인계가 커밋되지 않으므로 {@code @Async} 로 분리됐다. 그 결과 위탁이
     * 다음 테스트의 {@code deleteAll()} 과 겹칠 수 있는데, 테스트 커넥션 풀이 2개뿐이라 위탁이 붙잡은
     * 커넥션 때문에 다음 테스트가 굶는다(운영은 풀 20 · 비동기 스레드 4라 해당 없음).
     */
    @AfterEach
    void drainAsyncSubmit() {
        if (batchAsyncExecutor instanceof ThreadPoolTaskExecutor pool) {
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                    .until(() -> pool.getActiveCount() == 0
                            && pool.getThreadPoolExecutor().getQueue().isEmpty());
        }
        // 시드한 부모 영상 정리 — CASCADE 로 작업상태·프레임·증강 자식까지 함께 사라진다.
        // (비동기 위탁 배수 후에 실행해야 위탁 트랜잭션의 FK 키공유 잠금과 겹치지 않는다)
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    /** 부모 영상 선시드(V146 FK, 멱등) — 종료 시 CASCADE 로 정리하도록 기록한다. */
    private void seedParentVideo(Long rawDataId) {
        RawVideoFixture.seedRaw(jdbcTemplate, rawDataId);
        seededRawSns.add(rawDataId);
    }

    private void seedStatus(Long rawDataId, String dataSttsCd) {
        seedParentVideo(rawDataId);
        LsRawDataStatus status = LsRawDataStatus.initial(rawDataId);
        status.transitionTo(dataSttsCd);
        statusRepository.saveAndFlush(status);
    }

    /**
     * 대표 프레임 적재 — 프레임이 없으면 증강 위탁 입력이 성립하지 않아 412 다(E-ISSUE-09).
     * 200 을 기대하는 케이스는 반드시 프레임을 함께 시드해야 <b>실제로 접수되는</b> 요청이 된다.
     */
    private void seedFrame(Long rawDataId) {
        seedParentVideo(rawDataId);
        srcRepository.saveAndFlush(LsDataSrc.create(rawDataId, 0, null,
                "/storage/raw/" + rawDataId + "_0.jpg",
                "/storage/deidentified/" + rawDataId + "_0.jpg", null));
    }

    @Test
    @DisplayName("AugmentRequestController_WORKER_권한으로_요청시_403")
    void workerForbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(9001L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AugmentRequestController_유효한_단일_요청_바디로_200_응답")
    void validRequestReturns200() throws Exception {
        // 단일 선택 계약: 영상 1건 + 종류 1개
        seedStatus(8001L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8001L);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8001L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").isNumber())
                .andExpect(jsonPath("$.data.videoCount").value(1))
                .andExpect(jsonPath("$.data.typeCount").value(1))
                // E-ISSUE-09 — 요청 echo 가 아니라 실제 생성 수. 0 건이면 200 이 아니다.
                .andExpect(jsonPath("$.data.createdCount").value(1));
    }

    @Test
    @DisplayName("증강요청_영상_2건_이상_요청시_400")
    void multipleVideoIdsReturns400() throws Exception {
        // 단일 선택 계약 위반: 영상 2건 → @Size(max=1) 로 400
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8001L, 8002L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("증강요청_종류_2개_이상_요청시_400")
    void multipleTypesReturns400() throws Exception {
        // 단일 선택 계약 위반: 종류 2개 → @Size(max=1) 로 400
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8001L),
                "types", List.of("WINTER", "NIGHT")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("AugmentRequestController_videoIds_빈_배열_요청시_400")
    void emptyVideoIdsReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("AugmentRequestController_types에_잘못된_값_요청시_400")
    void invalidTypeReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8101L),
                "types", List.of("INVALID_TYPE")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("증강요청_RESOLUTION_타입_400_거부_allowlist_WINTER_NIGHT_RAIN")
    void resolutionTypeRejected400() throws Exception {
        // 해상도(RESOLUTION)는 외부 증강 위탁 대상이 아님(저작도구 직접 수행) — allowlist 밖이므로 400.
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8301L),
                "types", List.of("RESOLUTION")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("증강요청_WINTER_단일_정상_200")
    void winterOnlyReturns200() throws Exception {
        seedStatus(8401L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8401L);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8401L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.typeCount").value(1));
    }

    @Test
    @DisplayName("AugmentRequestController_미검수_영상_요청시_400_blockedVideoIds_포함")
    void notReviewedReturns400WithBlockedIds() throws Exception {
        // 단일 선택 계약: 미검수 영상 1건 요청 → NOT_REVIEWED + blockedVideoIds
        seedStatus(8202L, LsRawDataStatus.STTS_IN_REVIEW);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8202L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_REVIEWED"))
                .andExpect(jsonPath("$.data.blockedVideoIds[0]").value(8202));
    }
}
