package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털(외부 채널) SAM2 창구 회귀 테스트 — <b>선택 객체 추적은 없고, AI 분할은 포털 전용 창구로만 있다</b>.
 *
 * <p><b>정책 경위</b>: 2026-08-03 보안 2차 전수검증이 구 {@code PortalSam2Controller}(게이트 없는 원본 픽셀을
 * 외부 채널로 내보내던 분할·추적)를 HIGH 로 판정해 서버에서 제거했다. 2026-09-15 사용자 확정으로 포털 라벨링
 * 화면에 AI 보조(AI 탐지 · AI 분할 · AI 자동 추적)를 <b>포털 전용 창구</b>로 다시 제공한다(ADR-013 v24 ·
 * API-257). 새 AI 분할 창구는 구 창구를 되살린 것이 아니라 포털 작업 대상 인가 + 포털 서빙과 같은 입력 이미지로
 * 새로 둔 것이며, <b>선택 객체 AI 추적(sam2-track)의 포털 창구는 여전히 없다</b>(자동 추적과 기능 중복).
 *
 * <p><b>이 테스트가 잡는 것</b>:
 * <ul>
 *   <li>FE 도구 게이팅은 <b>신뢰 경계가 아니다</b>. 포털 선택 객체 추적은 서버에 핸들러가 없어야 한다 → 404.</li>
 *   <li>404 판정은 <b>핸들러 부재</b>여야 한다. 그래서 "존재했다면 400 이 났을 요청"(path≠body srcSn)으로
 *       검증한다 — 같은 요청을 포털 AI 분할 창구에 보내면 400 이 나는 것이 그 대조군이다.</li>
 *   <li>포털 경로의 SAM2 매핑은 AI 분할 한 건뿐이어야 한다 — 경로 문자열을 바꿔 추적 창구가 되살아나는 퇴행까지.</li>
 *   <li>내부(INTERNAL) 채널 SAM2 는 SFR-08-01(VOS) 핵심 기능이라 <b>그대로 살아있어야</b> 한다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalSam2RemovedTest {

    @Autowired private MockMvc mockMvc;

    /** 액추에이터 매핑 빈과 이름이 겹치므로 MVC 매핑 빈을 명시 지정한다. */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    /** path 는 9999, body 는 1 — 구 컨트롤러가 살아있었다면 srcSn 불일치로 400 이 났을 요청. */
    private static final String MISMATCHED_SEGMENT_BODY = """
            {"srcSn":1,"points":[[10,10]]}""";
    private static final String MISMATCHED_TRACK_BODY = """
            {"srcSn":1,"trackId":"t-1","prevPolygon":[[10,10],[30,30],[10,30]],
             "label":"person","nextSrcSns":[10000]}""";

    private String portalToken;
    private String internalReviewerToken;

    @BeforeEach
    void setUp() {
        portalToken = JwtTestSupport.token(secret, "alice", "PORTAL_USER", "PORTAL", issuer, 60);
        internalReviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("POST_portal_frames_sam2_segment_는_포털_AI_분할_창구로_존재한다_대조군")
    void portalSam2SegmentEndpointIsThePortalAiAssistWindow() throws Exception {
        // API-257 (2026-09-15) — 포털 AI 분할 창구가 핸들러로 존재한다. 같은 불일치 요청이 404 가 아니라
        // 400 이어야 아래 추적 창구의 404 가 「핸들러 부재」임이 대조로 드러난다.
        mockMvc.perform(post("/v1/portal/frames/9999/sam2-segment")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType("application/json").content(MISMATCHED_SEGMENT_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST_portal_frames_sam2_track_엔드포인트는_더이상_존재하지_않는다")
    void portalSam2TrackEndpointRemoved() throws Exception {
        mockMvc.perform(post("/v1/portal/frames/9999/sam2-track")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType("application/json").content(MISMATCHED_TRACK_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("포털_경로의_SAM2_핸들러_매핑은_AI_분할_한_건뿐이다")
    void onlyPortalAiSegmentHandlerMappingRegistered() {
        // given — 등록된 전체 요청 매핑 패턴.
        Set<String> portalSam2Patterns = registeredPatterns().stream()
                .filter(p -> p.startsWith("/v1/portal/") && p.contains("sam2"))
                .collect(Collectors.toSet());

        // then — 경로 문자열이 바뀌어(예: /v1/portal/labels/sam2-track) 추적 창구가 되살아나는 퇴행까지 잡는다.
        assertThat(portalSam2Patterns)
                .as("포털 채널의 SAM2 창구는 AI 분할(API-257) 하나뿐이다 — 선택 객체 추적은 ADR-013 미제공")
                .containsExactly("/v1/portal/frames/{srcSn}/sam2-segment");
    }

    @Test
    @DisplayName("내부_INTERNAL_채널의_SAM2_엔드포인트는_영향받지_않는다")
    void internalSam2EndpointsIntact() {
        // then — SFR-08-01(VOS) 핵심 기능. 포털 제거가 내부 경로를 함께 지웠는지 회귀 고정.
        assertThat(registeredPatterns())
                .contains("/v1/frames/{srcSn}/sam2-segment", "/v1/frames/{srcSn}/sam2-track");
    }

    @Test
    @DisplayName("내부_SAM2_엔드포인트는_포털_토큰에_대해_기존대로_403을_유지한다")
    void internalSam2StillForbiddenForPortalToken() throws Exception {
        // 포털 전용 경로를 없앴다고 해서 내부 경로가 외부 채널에 열리면 안 된다(채널 격리 회귀).
        mockMvc.perform(post("/v1/frames/9999/sam2-track")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType("application/json").content(MISMATCHED_TRACK_BODY))
                .andExpect(status().isForbidden());

        // sanity — 내부 토큰은 채널 격리에 막히지 않는다(핸들러 도달 = 비403).
        mockMvc.perform(post("/v1/frames/9999/sam2-track")
                        .header("Authorization", "Bearer " + internalReviewerToken)
                        .contentType("application/json").content(MISMATCHED_TRACK_BODY))
                .andExpect(status().is(org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo(403))));
    }

    /** 등록된 모든 요청 매핑의 URL 패턴 문자열. */
    private List<String> registeredPatterns() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(java.util.Objects::nonNull)
                .flatMap(c -> c.getPatterns().stream())
                .map(Object::toString)
                .toList();
    }
}
