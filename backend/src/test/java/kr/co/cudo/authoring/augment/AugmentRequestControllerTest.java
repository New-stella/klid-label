package kr.co.cudo.authoring.augment;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
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
    /** v1.3 생성 조건 — 다섯 축 모두 <b>허용 코드</b>다(자유 문자열 아님). */
    private static final Map<String, String> MTDT = Map.of(
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
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
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
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
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
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
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
                "types", List.of("AUGMENT", "AUGMENT"),
                "mtdt", MTDT
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
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
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
                "mtdt", MTDT
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("증강요청_RESOLUTION_타입_400_거부_allowlist는_AUGMENT_단일값")
    void resolutionTypeRejected400() throws Exception {
        // 해상도(RESOLUTION)는 외부 증강 위탁 대상이 아님(저작도구 직접 수행) — enum 밖이므로 400.
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8301L),
                "types", List.of("RESOLUTION"),
                "mtdt", MTDT
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("증강요청_AUGMENT_단일_정상_200")
    void augmentOnlyReturns200() throws Exception {
        seedStatus(8401L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8401L);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8401L),
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
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
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
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

    // ─── 구조화 생성 조건 계약 (2026-07-31 신설 · 2026-08-27 v1.3 정합) ─────────────

    /**
     * 다섯 항목이 전부 필수라는 계약을 <b>컨트롤러 400</b> 축에서 고정한다. 하나라도 비면 벤더가 임의
     * 기본값으로 채워 결과가 비결정적이 되므로 부분 입력을 허용하지 않는다.
     *
     * <p><b>이것은 우리 규칙</b>이다 — 벤더 계약은 "최소 1개" 지만 완화하지 않는다(2026-07-31 확정).
     */
    @Test
    @DisplayName("생성조건_항목이_하나라도_비면_400")
    void missingMtdtFieldReturns400() throws Exception {
        Map<String, String> missingSeverity = Map.of(
                "time", "NIGHT", "season", "WINTER", "weather", "RAIN", "terrain", "ROAD");
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8501L),
                "types", List.of("AUGMENT"),
                "mtdt", missingSeverity
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    /** 생성 조건 자체가 없으면 거부된다 — 조건 없이 위탁되면 무엇으로 만든 결과인지 알 수 없다. */
    @Test
    @DisplayName("생성조건_객체가_없으면_400")
    void missingMtdtObjectReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8502L),
                "types", List.of("AUGMENT")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    /**
     * ★ v1.3 핵심 — 생성 조건은 <b>허용 코드로 닫혀 있다</b>. 코드 밖 값은 접수 단계에서 400 이며,
     * 통과시키면 벤더가 {@code 400 INVALID_PARAMETER} 로 되돌려 위탁 자체가 실패한다.
     *
     * <p>구 계약(v1.1)에서는 이 값들이 자유 문자열이었고 "우리가 좁히지 않는다" 가 명시 정책이었다 —
     * 그 정책은 2026-08-27 폐기됐다. 되살리면 실벤더에서 요청이 전량 거부된다.
     */
    @Test
    @DisplayName("생성조건이_허용코드_밖이면_400")
    void mtdtOutsideAllowedCodesReturns400() throws Exception {
        // ★ 영상을 <접수 가능한 상태로> 시드하는 것이 이 시험의 핵심이다 (2026-08-27 DEV_FIX).
        //    시드하지 않으면 허용 코드 방어가 무너져 바인딩이 통과해도 서비스가 미검수로 400 을 내고,
        //    ErrorCode.NOT_REVIEWED 역시 400 이라 시험이 그대로 GREEN 이 된다(실증됨 — 허용 코드에
        //    TUNNEL 을 더해도 전건 통과). 시드해 두면 바인딩이 통과하는 순간 <200> 이 되어 RED 다.
        //    errorCode 단언은 그 위의 두 번째 그물이다(400 이 나더라도 <입력 검증> 400 인지 못박는다).
        seedStatus(8503L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8503L);

        // 구 테스트가 정상값으로 쓰던 TUNNEL·BRIDGE 는 v1.3 허용 코드가 아니다(UNDERPASS·RIVER 가 코드).
        List<Map<String, String>> invalid = List.of(
                mtdtWith("terrain", "TUNNEL"),
                mtdtWith("terrain", "BRIDGE"),
                mtdtWith("time", "MIDNIGHT"),
                mtdtWith("weather", "폭우"),
                mtdtWith("severity", "CRITICAL"),
                mtdtWith("season", "RESL_1080P"),
                mtdtWith("terrain", "../../etc/passwd"));

        for (Map<String, String> bad : invalid) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "videoIds", List.of(8503L),
                    "types", List.of("AUGMENT"),
                    "mtdt", bad));

            mockMvc.perform(post("/v1/augments/request")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        }
    }

    /** 허용 코드 한 축을 갈아끼운 생성 조건 — 나머지는 정상값이라 <b>그 축만</b> 검증된다. */
    private static Map<String, String> mtdtWith(String key, String value) {
        Map<String, String> mtdt = new java.util.LinkedHashMap<>(MTDT);
        mtdt.put(key, value);
        return mtdt;
    }

    /**
     * ★ v1.3 핵심 — {@code prompt} 는 <b>문자열</b>이다. 구 계약의 5필드 객체를 실으면 400 이며,
     * 명세가 <i>"prompt 값으로 객체를 전달하지 않습니다"</i> 를 명시한다.
     */
    @Test
    @DisplayName("구계약_객체형_prompt는_400")
    void objectPromptReturns400() throws Exception {
        String body = "{\"videoIds\":[8504],\"types\":[\"AUGMENT\"],"
                + "\"mtdt\":{\"time\":\"NIGHT\",\"season\":\"WINTER\",\"weather\":\"RAIN\","
                + "\"terrain\":\"ROAD\",\"severity\":\"HIGH\"},"
                + "\"prompt\":{\"condition\":\"눈\",\"text\":\"겨울로\"}}";

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /** 상한이 없으면 무제한 입력이 저장 컬럼과 외부 위탁으로 그대로 흘러간다(CWE-770). */
    @Test
    @DisplayName("자유지시문_길이_상한_초과시_400")
    void oversizedPromptTextReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8505L),
                "types", List.of("AUGMENT"),
                "mtdt", MTDT,
                "prompt", "X".repeat(AugmentPrompts.MAX_PROMPT_LENGTH + 1)
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ─── 이벤트 유형 계약 (2026-09-02 · @design ADR-059) ──────────────────

    /**
     * ★ 이벤트 유형과 침수 세부 유형은 <b>요청 본문 계약에서 사라졌다</b>. 요청자가 실어 보내도
     * 바인딩될 자리가 없어 <b>조용히 무시</b>되고 요청은 정상 접수된다.
     *
     * <p>구 계약은 두 값을 요청자에게 물었고 허용 코드 밖이면 400 이었다. 그 값은 벤더 창구가
     * <b>배경에 무슨 장면을 만들지</b> 정하는 축인데 우리 증강은 이미 이벤트가 담긴 프레임을 변환할
     * 뿐이라 지정할 자리가 없다. 지금은 위탁 시점에 서버가 중립값
     * ({@code GenAiJobSubmitRequest.EVENT_TYPE_ETC})을 고정 송신하고 세부 유형은 보내지 않는다.
     *
     * <p>구 값 {@code FLOOD}·{@code WILDFIRE}·{@code ROAD_FLOOD} 를 그대로 실어 보내는 것이
     * 핵심이다 — 400 이 나면 계약이 되살아난 것이고, 200 이면 요청자 입력이 위탁에 닿지 못한다는
     * 뜻이다(위탁 바디가 실제로 {@code ETC} 를 싣는지는 {@code HttpExternalAugmentClientTest} 가
     * 고정한다).
     */
    @Test
    @DisplayName("이벤트유형을_실어보내도_무시되고_요청은_접수된다")
    void requesterSuppliedEventTypeIsIgnored() throws Exception {
        seedStatus(8512L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8512L);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8512L),
                "types", List.of("AUGMENT"),
                "evntType", "WILDFIRE",
                "evntSubtype", "ROAD_FLOOD",
                "mtdt", MTDT
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(1));
    }

    /** 이벤트 유형 없이도 접수된다 — 구 계약의 필수 검증이 남아 있으면 400 이 되어 깨진다. */
    @Test
    @DisplayName("이벤트유형_없이_증강요청이_접수된다")
    void requestWithoutEventTypeIsAccepted() throws Exception {
        seedStatus(8511L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8511L);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8511L),
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(1));
    }

    /**
     * 생성 조건이 허용 코드가 됐다고 해서 <b>증강 종류까지 그 축에서 오는 것은 아니다</b>. types 는
     * 여전히 enum(현행 단일값 {@code AUGMENT})이며, 임의 문자열은 400 이다 — 이 경계가 무너지면 AUG_TYPE_CD 가 파생 산출물
     * 경로({@code .../{augTypeCd}.mp4})로 흘러 경로 순회(CWE-22)와 RESL_ 네임스페이스 침범이 열린다.
     */
    @Test
    @DisplayName("증강종류는_여전히_enum만_허용되고_임의_문자열은_400")
    void arbitraryTypeStillRejected() throws Exception {
        // ★ 이 시험이 지키는 것은 CWE-22(산출물 경로 순회)와 RESL_ 네임스페이스 침범이라, 가드가
        //    조용히 무력화되면 안 된다. 미시드 영상을 쓰면 types enum 이 열려도 미검수 400 에 가려
        //    GREEN 이 되므로(다른 두 부정 시험과 동일 취약성) 접수 가능한 상태로 시드한다.
        seedStatus(8506L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8506L);

        for (String bad : List.of("RESL_1080P", "../../etc/passwd", "WINTER", "AUGMENT2")) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "videoIds", List.of(8506L),
                    "types", List.of(bad),
                    "mtdt", MTDT
            ));

            mockMvc.perform(post("/v1/augments/request")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        }
    }

    /**
     * 보이지 않는 문자만 채운 <b>자유 지시문</b>은 입력이 아니다 — "지시문 없음" 으로 취급해 접수한다.
     *
     * <p>구 계약에서는 이 케이스가 <b>400</b> 이었다(그때는 생성 조건 5필드가 자유 문자열이라 빈 조건이
     * 벤더로 나가면 결과가 비결정적이 됐다). v1.3 에서 그 다섯 축은 enum 이 되어 이 경로 자체가
     * 사라졌고, 남은 자유 텍스트는 <b>선택</b> 필드라 비어도 정상이다 — 태도가 갈리는 근거는
     * <b>필수/선택 차이</b>이지 검증을 느슨하게 한 것이 아니다.
     *
     * <p>본문은 <b>ASCII {@code \\uXXXX} 이스케이프</b>로 만든다 — 문자를 그대로 실으면 인코딩 경로
     * 어딘가에서 치환됐을 때 "정규화가 걸렀다" 고 오판하게 된다.
     */
    @Test
    @DisplayName("보이지_않는_문자만_입력한_자유지시문은_지시문_없음으로_접수된다")
    void invisibleOnlyPromptTextIsTreatedAsAbsent() throws Exception {
        seedStatus(8507L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8507L);

        // NBSP · ZWSP · BOM · WORD JOINER — 화면에는 아무것도 보이지 않는다.
        String body = "{\"videoIds\":[8507],\"types\":[\"AUGMENT\"],"
                + "\"mtdt\":{\"time\":\"NIGHT\",\"season\":\"WINTER\",\"weather\":\"RAIN\","
                + "\"terrain\":\"ROAD\",\"severity\":\"HIGH\"},"
                + "\"prompt\":\"\\u00A0\\u200B\\uFEFF\\u2060\"}";

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(1));
    }

    /**
     * 보이지 않는 문자가 섞여도 내용이 있으면 그 내용이 살아 접수된다(과잉 차단 회귀 가드).
     * NBSP 로 띄어 쓴 값은 일반 공백으로 다듬어 통과한다.
     */
    @Test
    @DisplayName("보이지_않는_문자가_섞여도_내용이_있으면_200")
    void invisibleMixedButNonEmptyPromptTextIsAccepted() throws Exception {
        seedStatus(8508L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8508L);

        String body = "{\"videoIds\":[8508],\"types\":[\"AUGMENT\"],"
                + "\"mtdt\":{\"time\":\"NIGHT\",\"season\":\"WINTER\",\"weather\":\"RAIN\","
                + "\"terrain\":\"ROAD\",\"severity\":\"HIGH\"},"
                + "\"prompt\":\"\\u200B도로\\u00A0구조 유지\"}";

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
    @DisplayName("결과조회_응답에_요청별_생성조건_5항목이_실린다")
    void resultExposesMtdtPerRequest() throws Exception {
        seedStatus(8509L, LsRawDataStatus.STTS_APPROVED);
        seedFrame(8509L);
        requestWithMtdt(8509L, Map.of(
                "time", "DAWN", "season", "SUMMER", "weather", "FOG",
                "terrain", "UNDERPASS", "severity", "LOW"));
        requestWithMtdt(8509L, Map.of(
                "time", "DUSK", "season", "SPRING", "weather", "CLEAR",
                "terrain", "RIVER", "severity", "HIGH"));

        mockMvc.perform(get("/v1/augments/{jobId}/result", 8509L)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 같은 종류 2건이 각각의 항목으로 분리된다(항목 구분 축은 type 이 아니라 id + prompt).
                .andExpect(jsonPath("$.data.results.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].type").value("AUGMENT"))
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"))
                // 방금 요청한 건이라 <생성이 진행 중>이다 → 아직 결정 대상이 아니다(2026-07-31 DEV_FIX).
                // 그래도 항목과 prompt 는 실린다 — R9 역추적(조건 확인)은 결정 가능 여부와 별개 축이고,
                // 오히려 "결정 전에 조건을 본다" 는 요구가 이 상태에서 성립해야 한다.
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                .andExpect(jsonPath("$.data.results[0].id").isNumber())
                // ★ 정렬은 <최신순>이다(2026-07-31 흡수 항목 MED) — 나중에 요청한 DUSK 건이 맨 앞이다.
                //   오름차순이면 항목 1페이지에서 잘려나가는 쪽이 <가장 최신 = 유일한 결정 대상>이라,
                //   항목 페이저가 없는 FE 에서 방금 요청한 결과에 도달할 수단이 없었다.
                // v1.3 — 보관도 나간 바디와 같은 분리 형태({"mtdt":{...}})다.
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"mtdt\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"time\":\"DUSK\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"season\":\"SPRING\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"weather\":\"CLEAR\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"terrain\":\"RIVER\"")))
                .andExpect(jsonPath("$.data.results[0].prompt", containsString("\"severity\":\"HIGH\"")))
                // 먼저 요청한 건: 조건이 섞이지 않는다
                .andExpect(jsonPath("$.data.results[1].prompt", containsString("\"time\":\"DAWN\"")))
                .andExpect(jsonPath("$.data.results[1].prompt", containsString("\"terrain\":\"UNDERPASS\"")));
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
    private void requestWithMtdt(Long rawSn, Map<String, String> mtdt) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(rawSn),
                "types", List.of("AUGMENT"),
                "mtdt", mtdt));
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
                "types", List.of("AUGMENT"),
                "mtdt", MTDT
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
