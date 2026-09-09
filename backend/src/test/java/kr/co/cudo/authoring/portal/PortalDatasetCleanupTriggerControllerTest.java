package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.portal.entity.LsDatstArngmtTrgr;
import kr.co.cudo.authoring.portal.repository.LsDatstArngmtTrgrRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 배포본 정리 삭제 트리거 수신 창구의 <b>계약면</b>을 실동작으로 고정한다.
 *
 * <p>고정하는 축은 셋이다.
 * <ol>
 *   <li><b>접수와 재수신 멱등</b> — 첫 수신은 기산점을 남기고, 재수신은 같은 응답이면서
 *       <b>기산점을 뒤로 밀지 않는다.</b> 밀면 중복 수신이 반복되는 동안 정리가 영영 일어나지 않는다.</li>
 *   <li><b>대상 유무를 응답으로 드러내지 않는다</b> — 알지 못하는 데이터셋도 같은 202 다.</li>
 *   <li><b>주체 축 격리는 양방향</b> — 사용자 주체는 이 창구에 못 들어오고, 시스템 주체는 포털
 *       사용자 창구에 못 들어간다.</li>
 * </ol>
 *
 * <p>목록이 <b>비었을 때</b> 창구가 닫히는 축은 별도 컨텍스트가 필요해
 * {@link PortalDatasetCleanupTriggerClosedByDefaultTest} 가 따로 고정한다.
 *
 * <p>토큰은 {@code Authorization: Bearer} 로 싣는다 — 두 인계 자리가 <b>같은 검증 경로</b>를 타므로
 * 주체 축 판정에는 차이가 없고, 포털 전용 헤더 이름의 상수가 다른 패키지에 있어 리터럴을 새로
 * 박지 않기 위해서다. 헤더 축 자체는 {@code JwtFilterPortalHeaderIngressTest} 가 고정한다.
 *
 * @design API-244
 * @design ERD-035
 * @design AC-1102
 * @design AC-1103
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.portal.system-subjects=portal-sys-acct,portal-sys-alt")
class PortalDatasetCleanupTriggerControllerTest {

    private static final String PATH = "/v1/portal-system/dataset-cleanups";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsDatstArngmtTrgrRepository triggerRepository;
    @Autowired @Qualifier("controlDataSource") private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private final TransactionTemplate txTemplate;

    PortalDatasetCleanupTriggerControllerTest(
            @Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** 시스템 주체 토큰 — 설정 목록에 등록된 주체. */
    private String systemToken;
    /** 사용자 주체 토큰 — 같은 포털 채널이지만 주체 축이 다르다. */
    private String userToken;
    /** 한 시험 안에서만 쓰는 데이터셋 코드(다른 시험과 섞이지 않게 유일화). */
    private String datasetCode;

    @BeforeEach
    void setUp() {
        // ★ 시스템 주체 토큰의 role 클레임은 판정에 쓰이지 않는다 — 주체 식별자가 목록에 있으면
        //   필터가 역할을 비우고 주체 축 권한만 준다. 그래도 실제 포털 토큰 모양을 따라 싣는다.
        systemToken = JwtTestSupport.token(secret, "portal-sys-acct", "PORTAL_USER", "PORTAL", issuer, 600);
        userToken = JwtTestSupport.token(secret, "portal-user-" + System.nanoTime(),
                "PORTAL_USER", "PORTAL", issuer, 600);
        // 컬럼 폭이 20 이라 코드도 그 안에 들어와야 한다.
        datasetCode = "DS" + (System.nanoTime() % 1_000_000_000L);
    }

    // ---------------------------------------------------------------- 접수

    @Test
    @DisplayName("정리_트리거를_받으면_202로_접수하고_원장에_처음_받은_시각이_남는다")
    void firstReceiptIsAcceptedAndRecorded() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, "v3")))
                .andExpect(status().isAccepted());

        LsDatstArngmtTrgr row = txTemplate.execute(s ->
                triggerRepository.findByDatstCdAndVerNo(datasetCode, "v3").orElse(null));
        assertThat(row).isNotNull();
        assertThat(row.getRcptnDt()).isNotNull();
        assertThat(triggerRepository.countByDatstCdAndVerNo(datasetCode, "v3")).isEqualTo(1L);
    }

    /**
     * ★★ 이 시험이 이 변경의 핵심 축이다.
     *
     * <p>기산점을 <b>과거 시각으로 고정해 두고</b> 재수신시킨다. 시계 해상도에 기대지 않으므로,
     * 삽입이 {@code DO UPDATE SET rcptn_dt = ...} 로 바뀌면 <b>반드시</b> 붉게 된다.
     *
     * <p>기산점이 밀리면 중복 수신이 반복되는 동안 정리가 영영 일어나지 않는다.
     */
    @Test
    @DisplayName("★같은_데이터셋과_버전을_재수신해도_202이고_처음_받은_시각이_밀리지_않으며_행이_늘지_않는다")
    void reReceiptIsIdempotentAndDoesNotPushTheBaseline() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, "v3")))
                .andExpect(status().isAccepted());

        // 기산점을 과거로 못박는다 — 시계 차이가 아니라 <값>으로 판정하기 위해서다.
        LocalDateTime pinned = LocalDateTime.now().minusDays(3).withNano(0);
        new JdbcTemplate(controlDataSource).update(
                "UPDATE ls_datst_arngmt_trgr SET rcptn_dt = ? WHERE datst_cd = ? AND ver_no = ?",
                pinned, datasetCode, "v3");

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, "v3")))
                .andExpect(status().isAccepted());

        LsDatstArngmtTrgr row = txTemplate.execute(s ->
                triggerRepository.findByDatstCdAndVerNo(datasetCode, "v3").orElse(null));
        assertThat(row).isNotNull();
        assertThat(row.getRcptnDt())
                .as("재수신이 기산점을 갱신하면 정리가 무한히 미뤄진다 — 처음 받은 시각 그대로여야 한다")
                .isEqualTo(pinned);
        assertThat(triggerRepository.countByDatstCdAndVerNo(datasetCode, "v3"))
                .as("재수신으로 행이 하나 더 생기면 기산점이 어느 쪽인지 알 수 없게 된다")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("버전이_다르면_다른_짝이라_각각_접수된다")
    void differentVersionIsADifferentPair() throws Exception {
        for (String v : new String[]{"v3", "v4"}) {
            mockMvc.perform(post(PATH)
                            .header("Authorization", "Bearer " + systemToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(datasetCode, v)))
                    .andExpect(status().isAccepted());
        }
        assertThat(triggerRepository.countByDatstCdAndVerNo(datasetCode, "v3")).isEqualTo(1L);
        assertThat(triggerRepository.countByDatstCdAndVerNo(datasetCode, "v4")).isEqualTo(1L);
    }

    @Test
    @DisplayName("이_배포본이_알지_못하는_데이터셋도_404가_아니라_같은_202다")
    void unknownDatasetIsAcceptedToo() throws Exception {
        // 이 저장소 어디에도 없는 코드 — 존재 확인을 하지 않으므로 접수된다.
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOSUCHDS" + (System.nanoTime() % 100_000L), "v9")))
                .andExpect(status().isAccepted());
    }

    // ---------------------------------------------------------------- 검증 거부

    @Test
    @DisplayName("데이터셋_코드가_없거나_비면_400이다")
    void blankDatasetCodeIsRejected() throws Exception {
        Map<String, Object> missing = new HashMap<>();
        missing.put("version", "v3");
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missing)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", "v3")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("버전이_없거나_비면_400이다")
    void blankVersionIsRejected() throws Exception {
        Map<String, Object> missing = new HashMap<>();
        missing.put("datasetCode", datasetCode);
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missing)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, "")))
                .andExpect(status().isBadRequest());
    }

    /**
     * 컬럼 폭을 넘는 값이 <b>DB 오류(500)로 새지 않고</b> 입구에서 400 으로 막힌다.
     *
     * <p>형식을 확정한 것이 아니다 — 표현 형식은 여전히 열린 항목이고, 여기서 막는 이유는 저장
     * 구조가 응답으로 드러나지 않게 하기 위해서다.
     */
    @Test
    @DisplayName("컬럼_폭을_넘는_값은_400이고_DB오류로_새지_않는다")
    void overlongValuesAreRejectedAtTheDoor() throws Exception {
        String tooLongCode = "D".repeat(LsDatstArngmtTrgr.DATASET_CODE_MAX_LENGTH + 1);
        String tooLongVersion = "v".repeat(LsDatstArngmtTrgr.VERSION_MAX_LENGTH + 1);

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tooLongCode, "v3")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, tooLongVersion)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("거부_응답에_내부_경로와_예외_흔적과_저장_구조가_없다")
    void rejectionLeaksNothing() throws Exception {
        String responseBody = mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody.toLowerCase())
                .doesNotContain("ls_datst_arngmt_trgr")   // 표 이름
                .doesNotContain("datst_cd")               // 컬럼 이름
                .doesNotContain("ver_no")
                .doesNotContain("kr.co.cudo")             // 패키지·스택
                .doesNotContain("exception")
                .doesNotContain("/users/")                // 파일시스템 경로
                .doesNotContain("sql");
    }

    // ---------------------------------------------------------------- 주체 축 격리 (양방향)

    @Test
    @DisplayName("사용자_주체_토큰으로_시스템_주체_창구를_부르면_403이다")
    void userSubjectCannotEnterSystemEndpoint() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, "v3")))
                .andExpect(status().isForbidden());

        assertThat(triggerRepository.countByDatstCdAndVerNo(datasetCode, "v3"))
                .as("거부된 요청이 원장에 닿으면 안 된다")
                .isZero();
    }

    /**
     * 반대 방향 — 더 위험한 쪽이다. 시스템 주체 토큰은 특정 사용자를 가리키지 않으므로, 소유자
     * 비교로 접근을 가르는 포털 사용자 창구에 도달하면 그 비교가 의미를 잃은 채 통과한다.
     */
    @Test
    @DisplayName("★시스템_주체_토큰은_포털_사용자_창구에_들어가지_못한다")
    void systemSubjectCannotEnterPortalUserEndpoint() throws Exception {
        mockMvc.perform(get("/v1/portal/frames/{srcSn}/meta", 1L)
                        .header("Authorization", "Bearer " + systemToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰_없이_부르면_401이고_주체_축_불일치의_403과_구분된다")
    void missingTokenIsUnauthorizedNotForbidden() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, "v3")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내부_채널_토큰은_주체_축_이전에_채널에서_막힌다")
    void internalChannelTokenIsRejected() throws Exception {
        String internalToken =
                JwtTestSupport.token(secret, "9001", "REVIEWER", "INTERNAL", issuer, 600);
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(datasetCode, "v3")))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helper

    private String body(String datasetCode, String version) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("datasetCode", datasetCode);
        payload.put("version", version);
        return objectMapper.writeValueAsString(Collections.unmodifiableMap(payload));
    }
}
