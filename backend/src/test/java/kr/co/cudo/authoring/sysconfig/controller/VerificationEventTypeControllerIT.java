package kr.co.cudo.authoring.sysconfig.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증 이벤트 유형·질문 관리 API 통합 테스트 — {@code /api/v1/manage/verification-event-types}.
 * [design: API-219 · API-220]
 *
 * <h3>이 API 가 없으면 무엇이 깨지는가</h3>
 * <p>외부 시계열 분석의 추가 질문 문장은 <b>사업자 서버가 관리</b>해 우리가 지정할 수도, 응답으로 받을
 * 수도 없다. 그런데 이벤트 어노테이션의 질문 칸은 채워져야 하므로 문구를 저작도구가 보관하고 거기서
 * 조달한다. 이 API 는 그 목록의 유일한 확인·편집 통로다.
 *
 * <h3>회귀 시 특히 위험한 축</h3>
 * <ul>
 *   <li><b>전체 교체의 원자성</b> — 하나라도 어긋나면 요청 전체를 거부해야 한다. 일부만 반영되면
 *       남은 목록의 순서가 운영자가 보낸 것과 달라지고 그 사이 조달되는 질문이 의도와 달라진다.</li>
 *   <li><b>문구 검증</b> — 이 값은 외부 사업자 요청 바디와 로그에 그대로 실린다(CWE-117).</li>
 *   <li><b>관제 짝은 인입 원장에서 읽는다</b> — 매핑표를 만들면 두 번째 진실원이 된다.</li>
 * </ul>
 *
 * <p>시드 7종을 건드리지 않도록 {@code itc} 접두 유형·클립만 만들고 명시 정리한다(전체 교체가
 * 커밋되므로 테스트 트랜잭션 롤백에 기댈 수 없다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VerificationEventTypeControllerIT {

    private static final String BASE = "/v1/manage/verification-event-types";
    private static final String TYPE_PREFIX = "itc_";
    private static final String TYPE_EDIT = "itc_edit";
    private static final String TYPE_PAIR = "itc_pair";
    private static final String TYPE_NOPAIR = "itc_nopair";
    private static final String CLIP_PREFIX = "ITC-VRFC-";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);

        seedType(TYPE_EDIT, "편집대상", 901);
        seedType(TYPE_PAIR, "짝있음", 902);
        seedType(TYPE_NOPAIR, "짝없음", 903);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    /** 질문 → 유형 순서로 지운다 — FK 가 {@code ON DELETE RESTRICT} 라 역순이면 삭제가 막힌다. */
    private void cleanup() {
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_vrfc_evnt_qstn WHERE vrfc_evnt_type_cd LIKE ?", TYPE_PREFIX + "%");
        jdbc.update("DELETE FROM ls_vrfc_evnt_type WHERE vrfc_evnt_type_cd LIKE ?", TYPE_PREFIX + "%");
    }

    private void seedType(String code, String name, int sortSeq) {
        jdbc.update("""
                INSERT INTO ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, sort_seq)
                VALUES (?, ?, ?)
                """, code, name, sortSeq);
    }

    private void seedQuestion(String typeCd, int sortSeq, String text) {
        jdbc.update("""
                INSERT INTO ls_vrfc_evnt_qstn (vrfc_evnt_type_cd, sort_seq, qstn_cn)
                VALUES (?, ?, ?)
                """, typeCd, sortSeq, text);
    }

    /** 관제가 두 코드를 <b>짝지어</b> 실어 보낸 인입 행 — 짝의 유일한 조달처다. */
    private void seedIngestPair(String clipSuffix, String vrfcEvntTypeCd, String evntTypeCd) {
        jdbc.update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd, vrfc_evnt_type_cd, evnt_type_cd)
                VALUES (?, 'CCTV-ITC-01', 'clip.mp4', '/nas-storage/raw/clip.mp4', 'RELAY', ?, 'PENDING', ?, ?)
                """, CLIP_PREFIX + clipSuffix, Timestamp.valueOf(LocalDateTime.now()),
                vrfcEvntTypeCd, evntTypeCd);
    }

    private String body(String... questions) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("questions");
        for (String q : questions) {
            arr.addObject().put("qstnCn", q);
        }
        return objectMapper.writeValueAsString(root);
    }

    private List<String> storedTexts(String typeCd) {
        return jdbc.queryForList("""
                SELECT qstn_cn FROM ls_vrfc_evnt_qstn
                 WHERE vrfc_evnt_type_cd = ? ORDER BY sort_seq ASC
                """, String.class, typeCd);
    }

    // ------------------------------------------------------------------ 조회

    @Test
    @DisplayName("시드_7종이_질문과_함께_조회된다")
    void 시드_7종이_질문과_함께_조회된다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 연동 규격서의 지원 이벤트 7종이 시드다(smoke 포함 — 인입 상수 6종과 축이 다르다)
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='fire')].vrfcEvntTypeNm").value("화재"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='smoke')].vrfcEvntTypeNm").value("연기"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='fall')].vrfcEvntTypeNm").value("쓰러짐"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='violence')].vrfcEvntTypeNm").value("폭력"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='flooding')].vrfcEvntTypeNm").value("침수"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='car_accident')].vrfcEvntTypeNm").value("교통사고"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='kidnapping')].vrfcEvntTypeNm").value("납치"))
                // 각 시드 유형에 질문 1건이 들어 있다
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='fire')].questions[0].sortSeq").value(1));
    }

    @Test
    @DisplayName("유형은_정렬순서_오름차순이고_질문도_정렬순서_오름차순이다")
    void 유형은_정렬순서_오름차순이고_질문도_정렬순서_오름차순이다() throws Exception {
        // 물리 삽입 순서를 정렬순서와 반대로 준다 — 조회 순서에 기대면 여기서 깨진다
        seedQuestion(TYPE_EDIT, 3, "셋");
        seedQuestion(TYPE_EDIT, 1, "하나");
        seedQuestion(TYPE_EDIT, 2, "둘");

        String json = mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_EDIT + "')].questions[0].qstnCn")
                        .value("하나"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_EDIT + "')].questions[2].qstnCn")
                        .value("셋"))
                .andReturn().getResponse().getContentAsString();

        List<Integer> sortSeqs = com.jayway.jsonpath.JsonPath.read(json, "$.data[*].sortSeq");
        assertThat(sortSeqs).isSorted();
    }

    @Test
    @DisplayName("관제_유형_짝은_인입_원장에서_읽으며_다건도_모두_실린다")
    void 관제_유형_짝은_인입_원장에서_읽으며_다건도_모두_실린다() throws Exception {
        seedIngestPair("A1", TYPE_PAIR, "EV02000101");
        seedIngestPair("A2", TYPE_PAIR, "EV02000102");
        seedIngestPair("A3", TYPE_PAIR, "EV02000101"); // 중복 수신은 한 건으로 접힌다

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_PAIR + "')].evntTypeCds[0]")
                        .value("EV02000101"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_PAIR + "')].evntTypeCds[1]")
                        .value("EV02000102"))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_PAIR + "')].evntTypeCds.length()")
                        .value(2));
    }

    @Test
    @DisplayName("짝지어_수신된_적이_없는_유형은_빈_목록이다")
    void 짝지어_수신된_적이_없는_유형은_빈_목록이다() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_NOPAIR + "')].evntTypeCds.length()")
                        .value(0))
                // 질문이 없는 유형도 목록에서 빠지지 않는다 — 비어 있다는 사실이 운영자가 봐야 할 상태다
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_NOPAIR + "')].questions.length()")
                        .value(0));
    }

    @Test
    @DisplayName("조회는_WORKER_403_무토큰_401")
    void 조회는_WORKER_403_무토큰_401() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 전체 교체

    @Test
    @DisplayName("전체_교체는_요청_배열_순서를_정렬순서로_매긴다")
    void 전체_교체는_요청_배열_순서를_정렬순서로_매긴다() throws Exception {
        seedQuestion(TYPE_EDIT, 1, "옛 질문");

        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("새 첫 번째", "새 두 번째", "새 세 번째")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vrfcEvntTypeCd").value(TYPE_EDIT))
                .andExpect(jsonPath("$.data.questions.length()").value(3))
                .andExpect(jsonPath("$.data.questions[0].sortSeq").value(1))
                .andExpect(jsonPath("$.data.questions[0].qstnCn").value("새 첫 번째"))
                .andExpect(jsonPath("$.data.questions[2].sortSeq").value(3))
                .andExpect(jsonPath("$.data.questions[0].vrfcEvntQstnSn").isNumber());

        assertThat(storedTexts(TYPE_EDIT))
                .containsExactly("새 첫 번째", "새 두 번째", "새 세 번째");
    }

    @Test
    @DisplayName("순서만_뒤집어_저장해도_유일제약에_걸리지_않는다")
    void 순서만_뒤집어_저장해도_유일제약에_걸리지_않는다() throws Exception {
        // (유형코드, 정렬순서) 유일 제약 때문에 기존 행을 남긴 채 재배치하면 중간에 충돌한다.
        // 전체 교체는 한 트랜잭션 안에서 지우고 다시 넣으므로 그 충돌이 성립하지 않는다.
        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A", "B")))
                .andExpect(status().isOk());

        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("B", "A")))
                .andExpect(status().isOk());

        assertThat(storedTexts(TYPE_EDIT)).containsExactly("B", "A");
    }

    @Test
    @DisplayName("빈_배열을_보내면_그_유형의_질문이_없어진다")
    void 빈_배열을_보내면_그_유형의_질문이_없어진다() throws Exception {
        seedQuestion(TYPE_EDIT, 1, "지워질 질문");

        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions.length()").value(0));

        assertThat(storedTexts(TYPE_EDIT)).isEmpty();
    }

    @Test
    @DisplayName("questions_를_아예_보내지_않으면_400_이다")
    void questions_를_아예_보내지_않으면_400_이다() throws Exception {
        // "보내지 않았다"와 "비우겠다"는 다른 의도다 — 전자를 후자로 읽으면 실수로 전량 삭제된다.
        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 문구 검증

    @Test
    @DisplayName("개행_제어문자가_섞인_질문은_400_이고_기존_목록이_보존된다")
    void 개행_제어문자가_섞인_질문은_400_이고_기존_목록이_보존된다() throws Exception {
        seedQuestion(TYPE_EDIT, 1, "기존 질문");

        List<String> bads = List.of(
                "첫 줄\n둘째 줄",       // LF — 로그 한 줄에 여러 줄을 밀어 넣는 위조 경로
                "캐리지\r리턴",          // CR
                "탭\t문자",              // TAB
                "널\u0000문자",          // NUL
                "삭제\u007F문자");       // DEL
        for (String bad : bads) {
            mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("정상 질문", bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        }

        // 하나라도 어긋나면 요청 전체를 거부한다 — 일부만 반영되면 목록 순서가 보낸 것과 달라진다
        assertThat(storedTexts(TYPE_EDIT)).containsExactly("기존 질문");
    }

    @Test
    @DisplayName("공백만인_질문은_400_이다")
    void 공백만인_질문은_400_이다() throws Exception {
        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        assertThat(storedTexts(TYPE_EDIT)).isEmpty();
    }

    @Test
    @DisplayName("컬럼_폭을_넘는_4001자_질문은_400이다_DB오류로_새지_않는다")
    void 컬럼_폭을_넘는_4001자_질문은_400이다_DB오류로_새지_않는다() throws Exception {
        String tooLong = "가".repeat(4001);

        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tooLong)))
                .andExpect(status().isBadRequest());

        // 경계값(정확히 4000자)은 통과해야 한다 — 상한을 한 칸 좁히면 정상 입력을 우리가 먼저 막는다
        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("나".repeat(4000))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("등록되지_않은_검증_이벤트_유형은_404이다")
    void 등록되지_않은_검증_이벤트_유형은_404이다() throws Exception {
        mockMvc.perform(put(BASE + "/itc_absent/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("질문")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("경로변수_형식_위반은_400이다")
    void 경로변수_형식_위반은_400이다() throws Exception {
        mockMvc.perform(put(BASE + "/ITC_UPPER/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("질문")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("교체는_WORKER_403_무토큰_401이고_데이터가_바뀌지_않는다")
    void 교체는_WORKER_403_무토큰_401이고_데이터가_바뀌지_않는다() throws Exception {
        seedQuestion(TYPE_EDIT, 1, "기존 질문");

        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("작업자가 바꾼 질문")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("무인증 질문")))
                .andExpect(status().isUnauthorized());

        assertThat(storedTexts(TYPE_EDIT)).containsExactly("기존 질문");
    }

    @Test
    @DisplayName("교체는_다른_유형의_질문을_건드리지_않는다")
    void 교체는_다른_유형의_질문을_건드리지_않는다() throws Exception {
        seedQuestion(TYPE_PAIR, 1, "이웃 유형 질문");

        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("내 질문")))
                .andExpect(status().isOk());

        assertThat(storedTexts(TYPE_PAIR)).containsExactly("이웃 유형 질문");
        // 시드 7종도 그대로다 — 이 경로는 대상 유형만 다룬다
        assertThat(storedTexts("fire")).hasSize(1);
    }

    @Test
    @DisplayName("교체_후_조회에_그_목록이_그대로_보인다")
    void 교체_후_조회에_그_목록이_그대로_보인다() throws Exception {
        mockMvc.perform(put(BASE + "/" + TYPE_EDIT + "/questions")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("교체 첫 번째", "교체 두 번째")))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_EDIT + "')].questions.length()")
                        .value(2))
                .andExpect(jsonPath("$.data[?(@.vrfcEvntTypeCd=='" + TYPE_EDIT + "')].questions[0].qstnCn")
                        .value("교체 첫 번째"));
    }
}
