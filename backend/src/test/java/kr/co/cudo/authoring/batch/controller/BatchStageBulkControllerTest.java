package kr.co.cudo.authoring.batch.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.dto.BatchStageRerunResponse;
import kr.co.cudo.authoring.batch.dto.BatchStageSkipResponse;
import kr.co.cudo.authoring.batch.service.BatchStageRerunService;
import kr.co.cudo.authoring.batch.service.BatchStageSkipService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 작업 묶음 일괄 스킵·해제·재수행 E2E (MockMvc 통합).
 * [@design API-212] [@design API-213] [@design API-214]
 *
 * <p>인가 · 입력 검증 400 · <b>대상 묶음 VLM 한정</b> · 부분 성공 200 · <b>경로 라우팅</b>(단건 경로와
 * 겹치지 않음) · <b>DELETE 가 본문을 실제로 받는지</b>를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchStageBulkControllerTest {

    private static final String SKIP_URL = "/v1/videos/batch/stages/VLM/skip";
    private static final String RERUN_URL = "/v1/videos/batch/stages/VLM/rerun";
    private static final String REASON = "외부 시계열 분석 벤더 연동 전이라 시계열 없이 진행";

    @Autowired private MockMvc mockMvc;

    @MockBean private BatchStageSkipService skipService;
    @MockBean private BatchStageRerunService rerunService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        when(skipService.skip(anyLong(), anyString(), anyString()))
                .thenAnswer(inv -> new BatchStageSkipResponse(
                        inv.getArgument(0), inv.getArgument(1), true, "[수동 스킵] " + REASON, null));
        when(rerunService.rerun(anyLong(), anyString()))
                .thenAnswer(inv -> new BatchStageRerunResponse(
                        inv.getArgument(0), inv.getArgument(1), true));
    }

    private static String skipBody(Object... rawSns) {
        return "{\"rawSns\":[" + join(rawSns) + "],\"reason\":\"" + REASON + "\"}";
    }

    private static String body(Object... rawSns) {
        return "{\"rawSns\":[" + join(rawSns) + "]}";
    }

    private static String join(Object... rawSns) {
        return Arrays.stream(rawSns).map(String::valueOf).collect(Collectors.joining(","));
    }

    // ────────────────────────── 인가 ──────────────────────────

    @Test
    @DisplayName("일괄스킵API_WORKER는_403")
    void skipWorkerForbidden() throws Exception {
        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(skipBody(12)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("일괄해제API_WORKER는_403")
    void clearWorkerForbidden() throws Exception {
        mockMvc.perform(delete(SKIP_URL).header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("일괄재수행API_WORKER는_403")
    void rerunWorkerForbidden() throws Exception {
        mockMvc.perform(post(RERUN_URL).header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("일괄3API_인증없음_401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post(SKIP_URL).contentType(MediaType.APPLICATION_JSON).content(skipBody(12)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(SKIP_URL).contentType(MediaType.APPLICATION_JSON).content(body(12)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RERUN_URL).contentType(MediaType.APPLICATION_JSON).content(body(12)))
                .andExpect(status().isUnauthorized());
    }

    // ────────────────────────── 입력 검증 ──────────────────────────

    @Test
    @DisplayName("일괄3API_빈_목록은_400")
    void emptyListBadRequest() throws Exception {
        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSns\":[],\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rawSns\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(RERUN_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rawSns\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★일괄3API_상한_100건_초과는_400_무제한_목록은_자원_소모다")
    void overLimitBadRequest() throws Exception {
        String tooMany = IntStream.rangeClosed(1, 101).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSns\":[" + tooMany + "],\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSns\":[" + tooMany + "]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(RERUN_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSns\":[" + tooMany + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★일괄3API_1_미만_식별자는_400_단건_경로와_같은_축이다")
    void nonPositiveRawSnBadRequest() throws Exception {
        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(skipBody(0)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(RERUN_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(1, -5)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★일괄스킵API_사유가_없거나_공백만이면_400")
    void skipReasonRequired() throws Exception {
        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rawSns\":[12]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSns\":[12],\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────── 대상 묶음 제한 (R5) ──────────────────────────

    @Test
    @DisplayName("★★일괄3API_AUTOLABEL은_400이다_대량_품질축_우회_차단")
    void autolabelRejected() throws Exception {
        mockMvc.perform(post("/v1/videos/batch/stages/AUTOLABEL/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(skipBody(12)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_INPUT.name()));
        mockMvc.perform(delete("/v1/videos/batch/stages/AUTOLABEL/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_INPUT.name()));
        mockMvc.perform(post("/v1/videos/batch/stages/AUTOLABEL/rerun")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_INPUT.name()));
    }

    @Test
    @DisplayName("★일괄API_거부_메시지가_요청한_묶음_값을_되비추지_않는다")
    void unknownBundleDoesNotEchoInput() throws Exception {
        // 반사 XSS·로그 오염 차단(CWE-79/117). 경로 메타문자(퍼센트 인코딩된 <>)는 애플리케이션에
        //   닿기 전에 보안 필터가 거부하므로, 실제로 핸들러까지 도달하는 <미지의 평문 값>으로 검증한다.
        mockMvc.perform(post("/v1/videos/batch/stages/NOTABUNDLE/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(skipBody(12)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_INPUT.name()))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NOTABUNDLE"))));
        verify(skipService, times(0)).skip(anyLong(), anyString(), anyString());
    }

    // ────────────────────────── 부분 성공 ──────────────────────────

    @Test
    @DisplayName("★일괄스킵API_되는것만_처리하고_거부분은_건별사유로_200")
    void partialSuccessReturns200() throws Exception {
        when(skipService.skip(eq(43L), anyString(), anyString())).thenThrow(
                new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(skipBody(12, 43, 45)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.failureCount").value(1))
                .andExpect(jsonPath("$.data.results.length()").value(3))
                .andExpect(jsonPath("$.data.results[0].rawSn").value(12))
                .andExpect(jsonPath("$.data.results[0].success").value(true))
                .andExpect(jsonPath("$.data.results[1].success").value(false))
                .andExpect(jsonPath("$.data.results[2].success").value(true));
    }

    @Test
    @DisplayName("★일괄재수행API_한건도_성공하지_못해도_200이다")
    void allFailedStill200() throws Exception {
        when(rerunService.rerun(anyLong(), anyString())).thenThrow(new CustomException(
                ErrorCode.INVALID_INPUT, "건너뛰기를 해제한 작업 묶음이 아니거나 지원하지 않는 값입니다."));

        mockMvc.perform(post(RERUN_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12, 43)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failureCount").value(2));
    }

    @Test
    @DisplayName("일괄API_실패사유에_스택트레이스나_DB제약명이_없다")
    void reasonHasNoInternals() throws Exception {
        when(skipService.skip(anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException(
                "could not execute statement [ERROR: violates unique constraint \"uk_ls_batch_proc_log\"] at /opt/app"));

        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(skipBody(12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].reason",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))))
                .andExpect(jsonPath("$.data.results[0].reason",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("constraint"))))
                .andExpect(jsonPath("$.data.results[0].reason",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/opt/app"))));
    }

    // ────────────────────────── 경로 라우팅 · DELETE 본문 ──────────────────────────

    @Test
    @DisplayName("★★신규_일괄경로가_단건경로로_라우팅되지_않는다_rawSn_자리에_리터럴_batch")
    void bulkPathDoesNotCollideWithSinglePath() throws Exception {
        // 단건이 /v1/videos/{rawSn}/batch/stages/{stage}/skip 이라 {rawSn}="batch" 로 잡히면
        //   타입 변환 실패 400 이 "검증 실패"처럼 보여 원인을 놓친다. 실제 라우팅을 고정한다.
        mockMvc.perform(post(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(skipBody(12, 43)))
                .andExpect(status().isOk())
                // 일괄 응답 스키마(results 배열)여야 한다 — 단건 응답에는 results 가 없다.
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results.length()").value(2));

        mockMvc.perform(post(RERUN_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results").isArray());
    }

    @Test
    @DisplayName("★단건_경로는_그대로_동작한다_일괄_신설이_기존_매핑을_가리지_않는다")
    void singlePathStillWorks() throws Exception {
        mockMvc.perform(post("/v1/videos/12/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isOk())
                // 단건 응답 스키마 — rawSn 단일 필드이며 results 가 없다.
                .andExpect(jsonPath("$.data.rawSn").value(12))
                .andExpect(jsonPath("$.data.results").doesNotExist());

        // 단건 경로는 AUTOLABEL 도 계속 받는다(일괄 제한이 단건으로 전이되지 않았다).
        mockMvc.perform(post("/v1/videos/12/batch/stages/AUTOLABEL/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★★DELETE가_본문의_대상_목록을_실제로_받는다")
    void deleteReceivesBody() throws Exception {
        mockMvc.perform(delete(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12, 43, 45)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(3))
                .andExpect(jsonPath("$.data.successCount").value(3));

        verify(skipService, times(1)).clearSkip(12L, "VLM");
        verify(skipService, times(1)).clearSkip(43L, "VLM");
        verify(skipService, times(1)).clearSkip(45L, "VLM");
    }

    @Test
    @DisplayName("★DELETE_본문이_비어_있으면_400이다_목록없는_해제를_수락하지_않는다")
    void deleteWithoutBodyRejected() throws Exception {
        // 중간 경로가 DELETE 본문을 버리는 구성에서도 "전건 해제"로 흐르지 않아야 한다.
        mockMvc.perform(delete(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verify(skipService, times(0)).clearSkip(anyLong(), anyString());
    }

    @Test
    @DisplayName("★일괄해제API_거부된_건만_사유와_함께_돌아온다")
    void clearPartialFailure() throws Exception {
        doThrow(new CustomException(ErrorCode.INVALID_INPUT,
                "이 영상은 다른 영상에서 파생된 영상이라 배치 단계를 조작할 수 없습니다."))
                .when(skipService).clearSkip(eq(43L), anyString());

        mockMvc.perform(delete(SKIP_URL).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(12, 43)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.results[1].reason",
                        org.hamcrest.Matchers.containsString("파생")));
    }
}
