package kr.co.cudo.authoring.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 과도기 방어 — 벤더가 <b>구 describe 배열</b> 형식을 계속 보낼 때의 관측성 (@req R3).
 *
 * <p>확정 계약이므로 신·구 양쪽을 받아주는 <b>관대한 파싱은 하지 않는다</b>(무단 하위호환은 벤더 버그를
 * 숨긴다). 다만 Jackson 이 내는 400 은 "요청 본문이 올바르지 않습니다" 뿐이라 원인이 보이지 않아 연동
 * 트러블슈팅이 막히므로, 서버 로그에 <b>구조 힌트</b>를 남긴다(요청 바디 원문은 남기지 않는다 — CWE-117/209).
 */
class VlmResultControllerLegacyPayloadTest {

    private static final String LEGACY_ARRAY_BODY = """
            {
              "request_id": "00000001",
              "status": "completed",
              "results": [
                {"start_sec": 0, "end_sec": 8, "description": "사람이 도로를 무단횡단"}
              ]
            }
            """;

    private MockMvc mockMvc;
    private VlmResultService service;
    private Logger controllerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = mock(VlmResultService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new VlmResultController(service)).build();

        controllerLogger = (Logger) LoggerFactory.getLogger(VlmResultController.class);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
        controllerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("results가_배열로_오면_400이고_구조_힌트가_로그에_남는다")
    void legacyArrayResults_rejectedWithStructuralHintLog() throws Exception {
        mockMvc.perform(post("/v1/vlm/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LEGACY_ARRAY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // 적재 경로에 도달하지 않는다(fail-secure)
        verify(service, never()).handle(org.mockito.ArgumentMatchers.any());

        assertThat(appender.list)
                .as("구조 힌트가 없으면 벤더 연동 시 원인 불명 400 만 남는다")
                .anyMatch(e -> e.getFormattedMessage().contains("results")
                        && e.getFormattedMessage().contains("배열"));
        // 요청 바디 원문(서술 텍스트)은 로그에 남기지 않는다
        assertThat(appender.list)
                .noneMatch(e -> e.getFormattedMessage().contains("무단횡단"));
    }

    @Test
    @DisplayName("results_외의_본문_파손은_구조_힌트_없이_일반_400으로_거부된다")
    void otherMalformedBody_rejectedWithoutHint() throws Exception {
        mockMvc.perform(post("/v1/vlm/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest());

        assertThat(appender.list)
                .noneMatch(e -> e.getFormattedMessage().contains("배열"));
    }
}
