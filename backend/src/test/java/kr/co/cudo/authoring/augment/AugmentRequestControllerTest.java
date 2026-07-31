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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentRequestControllerTest {

    /**
     * 생성 조건 5필드 — 프롬프트가 관심사가 아닌 케이스에서 계약(필수)을 채우는 고정값.
     * 자유 문자열이므로 enum 이 아니다(연동명세서 v1.1 §4.1 이 허용값을 정의하지 않는다).
     */
    private static final Map<String, String> PROMPT = Map.of(
            "time", "NIGHT", "season", "WINTER", "weather", "RAIN",
            "terrain", "ROAD", "severity", "HIGH");


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
                "types", List.of("WINTER"),
                "prompt", PROMPT
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
                "types", List.of("WINTER"),
                "prompt", PROMPT
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
                "types", List.of("WINTER"),
                "prompt", PROMPT
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
                "types", List.of("WINTER", "NIGHT"),
                "prompt", PROMPT
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
                "types", List.of("WINTER"),
                "prompt", PROMPT
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
                "types", List.of("INVALID_TYPE"),
                "prompt", PROMPT
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
                "types", List.of("RESOLUTION"),
                "prompt", PROMPT
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
                "types", List.of("WINTER"),
                "prompt", PROMPT
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
                "types", List.of("WINTER"),
                "prompt", PROMPT
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

    // ─── 구조화 프롬프트 계약 (2026-07-31) ─────────────────────────────

    /**
     * 5필드가 전부 필수라는 계약을 <b>컨트롤러 400</b> 축에서 고정한다. 하나라도 비면 벤더가 임의
     * 기본값으로 채워 결과가 비결정적이 되므로 부분 입력을 허용하지 않는다.
     */
    @Test
    @DisplayName("프롬프트_필드가_하나라도_비면_400")
    void missingPromptFieldReturns400() throws Exception {
        Map<String, String> missingSeverity = Map.of(
                "time", "NIGHT", "season", "WINTER", "weather", "RAIN", "terrain", "ROAD");
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8501L),
                "types", List.of("WINTER"),
                "prompt", missingSeverity
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    /** prompt 자체가 없는 <b>구 계약</b> 요청도 거부된다 — 조건 없이 위탁되면 무엇으로 만든 결과인지 알 수 없다. */
    @Test
    @DisplayName("프롬프트_객체가_없으면_400")
    void missingPromptObjectReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8502L),
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
    @DisplayName("공백만_입력한_필드는_400")
    void blankPromptFieldReturns400() throws Exception {
        Map<String, String> blank = Map.of(
                "time", "   ", "season", "WINTER", "weather", "RAIN",
                "terrain", "ROAD", "severity", "HIGH");
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8503L),
                "types", List.of("WINTER"),
                "prompt", blank
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    /** 상한이 없으면 무제한 입력이 저장 컬럼과 외부 위탁으로 그대로 흘러간다(CWE-770). */
    @Test
    @DisplayName("프롬프트_필드_길이_상한_초과시_400")
    void oversizedPromptFieldReturns400() throws Exception {
        Map<String, String> tooLong = Map.of(
                "time", "X".repeat(51), "season", "WINTER", "weather", "RAIN",
                "terrain", "ROAD", "severity", "HIGH");
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8504L),
                "types", List.of("WINTER"),
                "prompt", tooLong
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    /**
     * prompt 가 자유 문자열이 됐다고 해서 <b>증강 종류까지 열린 것은 아니다</b>. types 는 여전히
     * enum 3종이며, 임의 문자열은 400 이다 — 이 경계가 무너지면 AUG_TYPE_CD 가 파생 산출물 경로
     * ({@code .../{augTypeCd}.mp4})로 흘러 경로 순회(CWE-22)와 RESL_ 네임스페이스 침범이 열린다.
     */
    @Test
    @DisplayName("증강종류는_여전히_enum_3종만_허용되고_임의_문자열은_400")
    void arbitraryTypeStillRejected() throws Exception {
        for (String bad : List.of("RESL_1080P", "../../etc/passwd", "WINTER2")) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "videoIds", List.of(8505L),
                    "types", List.of(bad),
                    "prompt", PROMPT
            ));

            mockMvc.perform(post("/v1/augments/request")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * 보이지 않는 문자만 채운 필드는 <b>입력이 아니다</b>. {@code @NotBlank} 는 {@code trim()}
     * ({@code U+0020} 이하)만 보고 {@code Character.isWhitespace} 는 NBSP 를 공백으로 보지 않으므로,
     * 이 케이스가 통과하면 "빈 조건" 이 그대로 벤더까지 나가 결과가 비결정적이 된다 —
     * DTO 주석이 막겠다고 선언한 바로 그 상태다.
     *
     * <p>본문은 <b>ASCII {@code \\uXXXX} 이스케이프</b>로 만든다 — 문자를 그대로 실으면 인코딩 경로
     * 어딘가에서 치환됐을 때 "정규화가 걸렀다" 고 오판하게 된다.
     */
    @Test
    @DisplayName("보이지_않는_문자만_입력한_프롬프트_필드는_400")
    void invisibleOnlyPromptFieldReturns400() throws Exception {
        // NBSP · ZWSP · BOM · WORD JOINER — 화면에는 아무것도 보이지 않는다.
        for (String escaped : List.of("\\u00A0", "\\u200B", "\\uFEFF", "\\u2060")) {
            String body = "{\"videoIds\":[8507],\"types\":[\"WINTER\"],\"prompt\":{"
                    + "\"time\":\"" + escaped + "\",\"season\":\"WINTER\",\"weather\":\"RAIN\","
                    + "\"terrain\":\"ROAD\",\"severity\":\"HIGH\"}}";

            mockMvc.perform(post("/v1/augments/request")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        }
    }

    /**
     * 보이는 내용이 하나라도 있으면 정상 접수된다(위 거부의 대칭 — 과잉 차단 회귀 가드).
     * NBSP 로 띄어 쓴 값은 일반 공백으로 다듬어 통과한다.
     */
    @Test
    @DisplayName("보이지_않는_문자가_섞여도_내용이_있으면_200")
    void invisibleMixedButNonEmptyPromptIsAccepted() throws Exception {
        seedStatus(8508L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8508L);

        String body = "{\"videoIds\":[8508],\"types\":[\"WINTER\"],\"prompt\":{"
                + "\"time\":\"\\u200BNIGHT\",\"season\":\"HEAVY\\u00A0WINTER\",\"weather\":\"RAIN\","
                + "\"terrain\":\"ROAD\",\"severity\":\"HIGH\"}}";

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(1));
    }

    // ─── R9 역추적 — 조회(GET) 경로에서 생성 조건 확인 (DEV_FIX HIGH-1) ──────────

    /**
     * <b>결정을 내리기 전에</b> "이 결과물이 어떤 조건으로 만들어졌는가" 를 볼 수 있어야 한다(R9).
     *
     * <p>구 구현에서 prompt 를 실어 나르는 경로는 accept/reject <b>응답</b>뿐이었다 — 처리하는 그 순간
     * 1회만 볼 수 있고, {@code applyGenerationResult} 가 재전이를 CONFLICT 로 막아 <b>재조회 수단이 없었다</b>.
     * 조회(GET) 경로에 실려야 비로소 판단 근거가 된다.
     *
     * <p>같은 (영상 × 종류)를 <b>조건만 바꿔 두 번</b> 요청해, 결과 항목이 요청별로 분리되고 각 항목이
     * 자기 조건을 들고 있는지 본다 — 반복 요청이 허용된 이상 이것이 결과물을 구분하는 유일한 축이다.
     */
    @Test
    @DisplayName("결과조회_응답에_요청별_생성조건_5필드가_실린다")
    void resultExposesPromptPerRequest() throws Exception {
        seedStatus(8509L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8509L);
        requestWithPrompt(8509L, Map.of(
                "time", "DAWN", "season", "SUMMER", "weather", "FOG",
                "terrain", "TUNNEL", "severity", "LOW"));
        requestWithPrompt(8509L, Map.of(
                "time", "DUSK", "season", "SPRING", "weather", "CLEAR",
                "terrain", "BRIDGE", "severity", "HIGH"));

        mockMvc.perform(get("/v1/augments/{jobId}/result", 8509L)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 같은 종류 2건이 각각의 항목으로 분리된다(항목 구분 축은 type 이 아니라 id + prompt).
                .andExpect(jsonPath("$.data.results.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].type").value("WINTER"))
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"))
                // 방금 요청한 건이라 <생성이 진행 중>이다 → 아직 결정 대상이 아니다(2026-07-31 DEV_FIX).
                // 그래도 항목과 prompt 는 실린다 — R9 역추적(조건 확인)은 결정 가능 여부와 별개 축이고,
                // 오히려 "결정 전에 조건을 본다" 는 요구가 이 상태에서 성립해야 한다.
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                .andExpect(jsonPath("$.data.results[0].id").isNumber())
                // ★ 정렬은 <최신순>이다(2026-07-31 흡수 항목 MED) — 나중에 요청한 DUSK 건이 맨 앞이다.
                //   오름차순이면 항목 1페이지에서 잘려나가는 쪽이 <가장 최신 = 유일한 결정 대상>이라,
                //   항목 페이저가 없는 FE 에서 방금 요청한 결과에 도달할 수단이 없었다.
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"time\":\"DUSK\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"season\":\"SPRING\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"weather\":\"CLEAR\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"terrain\":\"BRIDGE\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"severity\":\"HIGH\"")))
                // 먼저 요청한 건: 조건이 섞이지 않는다
                .andExpect(jsonPath("$.data.results[1].prompt", containsString("\"time\":\"DAWN\"")))
                .andExpect(jsonPath("$.data.results[1].prompt", containsString("\"terrain\":\"TUNNEL\"")));
    }

    /**
     * 생성 조건 노출 범위는 accept/reject 와 동일한 REVIEWER 경계 안에 머문다 —
     * 조회로 넓혔다고 해서 WORKER 에게 열리지 않는다(보안 리뷰 판정 유지).
     */
    @Test
    @DisplayName("결과조회는_WORKER에게_403이라_생성조건이_노출되지_않는다")
    void resultIsForbiddenForWorker() throws Exception {
        mockMvc.perform(get("/v1/augments/{jobId}/result", 8509L)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    /** 증강 요청 1건 접수(200 확인) — 결과 조회 테스트의 준비 단계. */
    private void requestWithPrompt(Long rawSn, Map<String, String> prompt) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(rawSn),
                "types", List.of("WINTER"),
                "prompt", prompt));
        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /**
     * 기존 {@code types[]} 계약은 prompt 추가와 무관하게 그대로 동작한다(회귀 가드).
     * 같은 (영상 × 종류) 재요청도 <b>연속 200</b> 이다 — 구 중복 차단 409 는 2026-07-31 폐기됐다.
     */
    @Test
    @DisplayName("기존_types_계약은_그대로_동작하고_같은_종류_재요청도_200이다")
    void existingTypesContractStillWorksAndRepeatIsAllowed() throws Exception {
        seedStatus(8506L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8506L);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8506L),
                "types", List.of("NIGHT"),
                "prompt", PROMPT
        ));

        for (int attempt = 1; attempt <= 2; attempt++) {
            mockMvc.perform(post("/v1/augments/request")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.typeCount").value(1))
                    .andExpect(jsonPath("$.data.createdCount").value(1));
        }
    }
}
