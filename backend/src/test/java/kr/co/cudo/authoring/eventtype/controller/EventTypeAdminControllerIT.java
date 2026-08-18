package kr.co.cudo.authoring.eventtype.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.eventtype.service.EventTypeAutoRegistrar;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이벤트유형 <b>관리 API</b> 통합 테스트 — {@code /api/v1/manage/event-types}.
 *
 * <h3>이 API 의 존재 이유(회귀 시 무엇이 깨지는가)</h3>
 * <p>자동등록은 <b>관제 칸</b>({@code EVNT_NM})만 갱신하고 <b>운영자 칸</b>({@code OPTR_INDCT_NM})은
 * 건드리지 않는다. 그 분리가 의미를 가지려면 사람이 표시명을 정할 통로가 있어야 하고, 이 API 가
 * 그 통로다. 표시명을 비우면 관제값으로 자연 복귀하므로 되돌릴 수 없는 차단이 생기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EventTypeAdminControllerIT {

    private static final String PREFIX = "ITADM";
    private static final String BASE = "/v1/manage/event-types";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventTypeAutoRegistrar autoRegistrar;
    @Autowired private EventTypeService eventTypeService;
    @Autowired private CacheManager cacheManager;

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
        evictCache();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        evictCache();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd LIKE ?", PREFIX + "%");
    }

    private void evictCache() {
        var cache = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        if (cache != null) {
            cache.clear();
        }
    }

    private String body(String evntNm, String clctYn) throws Exception {
        return body(evntNm, clctYn, null);
    }

    private String body(String evntNm, String clctYn, String unusedLegacyArg) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        if (evntNm != null) {
            node.put("optrIndctNm", evntNm);
        }
        if (clctYn != null) {
            node.put("clctYn", clctYn);
        }
        return objectMapper.writeValueAsString(node);
    }

    @Test
    @DisplayName("REVIEWER는_이벤트명을_정정할_수_있다")
    void REVIEWER는_이벤트명을_정정할_수_있다() throws Exception {
        // given — 인입에서 자동등록된 유형(초기 이름은 관제 수신값)
        String code = PREFIX + "NM";
        autoRegistrar.register(code, "관제원본명", "01", null);

        // when / then — PATCH 200 + 수정된 데이터 반환(api-design.md)
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("운영자정정명", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.evntTypeCd").value(code))
                .andExpect(jsonPath("$.data.optrIndctNm").value("운영자정정명"))
                .andExpect(jsonPath("$.data.dsplNm").value("운영자정정명"))
                // ★관제 원본은 유실되지 않는다(칸이 다르다)
                .andExpect(jsonPath("$.data.evntNm").value("관제원본명"))
                // 분류코드는 관제 수신 사실이라 화면에서 바꾸지 않는다(요청에 없으면 그대로)
                .andExpect(jsonPath("$.data.evntClsfCd").value("01"))
                ;
    }

    @Test
    @DisplayName("REVIEWER는_수집여부를_토글할_수_있고_필터에서_사라진다")
    void REVIEWER는_수집여부를_토글할_수_있고_필터에서_사라진다() throws Exception {
        // given — 수집대상으로 등록돼 필터에 노출되던 유형
        String code = PREFIX + "YN";
        autoRegistrar.register(code, "토글대상", null, null);
        assertThat(eventTypeService.filterOptions())
                .extracting(o -> o.categoryKey()).contains(code);

        // when — 수집여부를 N 으로 토글
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "N")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clctYn").value("N"))
                // 이름은 요청에 없었으므로 그대로(PATCH 부분 수정 시맨틱)
                .andExpect(jsonPath("$.data.dsplNm").value("토글대상"));

        // then — ★캐시가 무효화돼 필터에서 즉시 사라진다(TTL 6시간을 기다리지 않는다)
        assertThat(eventTypeService.filterOptions())
                .extracting(o -> o.categoryKey()).doesNotContain(code);
    }

    @Test
    @DisplayName("정정한_표시명은_이후_자동등록이_덮어쓰지_않는다")
    void 정정한_표시명은_이후_자동등록이_덮어쓰지_않는다() throws Exception {
        // given — 자동등록 → 운영자 표시명 지정
        String code = PREFIX + "KP";
        autoRegistrar.register(code, "관제원본명", "01", null);
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("운영자표시명", null)))
                .andExpect(status().isOk());

        // when — 관제가 새 유형명을 보낸다
        autoRegistrar.register(code, "관제신규명", "01", null);

        // then — ★관제 칸은 갱신되지만 표시는 운영자 값 그대로다(칸 분리의 존재 이유)
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + code + "')].dsplNm")
                        .value("운영자표시명"))
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + code + "')].evntNm")
                        .value("관제신규명"));
    }

    @Test
    @DisplayName("표시명을_비우면_관제값으로_복귀한다")
    void 표시명을_비우면_관제값으로_복귀한다() throws Exception {
        // given — 운영자가 표시명을 정한 행
        String code = PREFIX + "RL";
        autoRegistrar.register(code, "관제원본명", "01", null);
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("잘못정정함", null)))
                .andExpect(jsonPath("$.data.dsplNm").value("잘못정정함"));

        // when — 표시명을 빈 문자열로 해제한다(되돌릴 수 없는 차단을 만들지 않는다)
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optrIndctNm").doesNotExist())
                // then — 관제 수신명으로 자연 복귀한다(관제 원본이 살아 있었다)
                .andExpect(jsonPath("$.data.dsplNm").value("관제원본명"));
    }

    @Test
    @DisplayName("관리_목록은_비수집_유형도_보여준다")
    void 관리_목록은_비수집_유형도_보여준다() throws Exception {
        // given — 비수집으로 내려 필터에서 빠진 유형
        String code = PREFIX + "HD";
        autoRegistrar.register(code, "숨긴유형", "01", null);
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "N")))
                .andExpect(status().isOk());

        // when / then — 관리 화면은 "왜 안 보이는지"를 보고 고치는 화면이라 걸러 내보내면 안 된다
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + code + "')].clctYn").value("N"));
    }

    @Test
    @DisplayName("WORKER의_수정_요청은_403")
    void WORKER의_수정_요청은_403() throws Exception {
        // given
        String code = PREFIX + "WK";
        autoRegistrar.register(code, "권한검증", "01", null);

        // when / then — 관리 경로는 REVIEWER 전용(SecurityConfig /v1/manage/** + @PreAuthorize)
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("무단수정", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증_수정_요청은_401")
    void 미인증_수정_요청은_401() throws Exception {
        mockMvc.perform(patch(BASE + "/" + PREFIX + "NA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("무단수정", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("미등록_유형_수정은_404")
    void 미등록_유형_수정은_404() throws Exception {
        mockMvc.perform(patch(BASE + "/" + PREFIX + "XX")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("없는유형", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("잘못된_입력은_400 — 빈요청·공백이름·과대길이·허용외_수집여부")
    void 잘못된_입력은_400() throws Exception {
        // given
        String code = PREFIX + "VD";
        autoRegistrar.register(code, "검증대상", "01", null);
        String url = BASE + "/" + code;

        // when / then — ① 수정 항목 없음(조용히 200 을 주면 화면이 "저장됐다"고 믿는다)
        mockMvc.perform(patch(url).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // ③ 컬럼 길이(명V200) 초과 — DB 가 아니라 입력 검증에서 막는다(CWE-20)
        mockMvc.perform(patch(url).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("가".repeat(201), null)))
                .andExpect(status().isBadRequest());

        // ④ 허용값 외 수집여부
        mockMvc.perform(patch(url).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "X")))
                .andExpect(status().isBadRequest());


        // then — 어떤 400 도 값을 바꾸지 않았다
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + code + "')].dsplNm").value("검증대상"));
    }

    @Test
    @DisplayName("PK와_분류코드는_요청으로_바꿀_수_없다")
    void PK와_분류코드는_요청으로_바꿀_수_없다() throws Exception {
        // given — Mass Assignment 시도(CWE-915): DTO 에 없는 필드를 함께 보낸다
        String code = PREFIX + "MA";
        autoRegistrar.register(code, "원본", "01", null);
        String payload = "{\"optrIndctNm\":\"정상수정\",\"evntTypeCd\":\"HACKED\","
                + "\"evntNm\":\"관제칸침범\",\"evntClsfCd\":\"99\",\"regDt\":\"2000-01-01T00:00:00\"}";

        // when
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                // then — 허용 필드만 반영되고 나머지는 무시된다
                .andExpect(jsonPath("$.data.evntTypeCd").value(code))
                .andExpect(jsonPath("$.data.optrIndctNm").value("정상수정"))
                // ★관제 칸은 요청으로 못 바꾼다(바꿀 수 있으면 다음 인입이 덮어써 "저장했는데 사라짐")
                .andExpect(jsonPath("$.data.evntNm").value("원본"))
                .andExpect(jsonPath("$.data.evntClsfCd").value("01"));
    }

    // ------------------------------------------------------------------
    // 표시명 채택 단계(dsplNmSource) 와이어 노출 — @design API-185, API-186.
    //
    // 서버가 채택 단계를 함께 내려주므로 화면이 원본 필드로 폴백을 다시 판정하지 않는다.
    // 4단계 값 매트릭스는 EventTypeAdminDisplayNameSourceTest 가 덮고, 여기서는 <두 응답의
    // JSON 에 실제로 실리는지>와 <수정으로 단계가 전이되는지>를 고정한다.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("목록_응답에_표시명_채택단계가_실린다")
    void 목록_응답에_표시명_채택단계가_실린다() throws Exception {
        // given — 관제 수신명만 있는 행 + 이름이 아예 없는 행(코드 폴백)
        String withName = PREFIX + "S1";
        String noName = PREFIX + "S2";
        autoRegistrar.register(withName, "관제수신명", "01", null);
        autoRegistrar.register(noName, null, "01", null);

        // when / then — 각 행이 자기 단계를 갖는다(카테고리코드가 없어 카테고리명 폴백은 성립하지 않는다)
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + withName + "')].dsplNm")
                        .value("관제수신명"))
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + withName + "')].dsplNmSource")
                        .value("control"))
                // 이름 후보가 전무하면 표시명은 유형코드이고 단계가 그 사실을 알려준다
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + noName + "')].dsplNm")
                        .value(noName))
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + noName + "')].dsplNmSource")
                        .value("code"));
    }

    @Test
    @DisplayName("수정_응답에도_표시명_채택단계가_실리고_해제하면_되돌아간다")
    void 수정_응답에도_표시명_채택단계가_실리고_해제하면_되돌아간다() throws Exception {
        // given — 관제 수신명만 있는 행(현재 단계 control)
        String code = PREFIX + "S3";
        autoRegistrar.register(code, "관제수신명", "01", null);

        // when / then — ① 운영자 표시명을 지정하면 그 자리에서 operator 로 전이한다.
        //   ★수정 응답에 단계가 없으면, 목록을 재조회하지 않는 화면은 저장 직후 옛 출처를 그린다.
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("운영자지정명", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dsplNm").value("운영자지정명"))
                .andExpect(jsonPath("$.data.dsplNmSource").value("operator"));

        // ② 빈 문자열로 해제하면 관제 수신명으로 복귀하고 단계도 함께 되돌아간다(되돌리기 경로)
        mockMvc.perform(patch(BASE + "/" + code)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dsplNm").value("관제수신명"))
                .andExpect(jsonPath("$.data.dsplNmSource").value("control"));

        // then — 목록 재조회도 같은 단계를 낸다(두 응답이 같은 판정을 쓴다)
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(jsonPath("$.data[?(@.evntTypeCd=='" + code + "')].dsplNmSource")
                        .value("control"));
    }
}
