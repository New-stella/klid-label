package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 작업 창구의 <b>계약면</b>(경로·응답 모양·채널 격리)을 실동작으로 고정한다.
 *
 * @design API-234, API-235, API-236, API-237
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalWorkMetaControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private PortalUploadFrameRepository frameRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private final TransactionTemplate txTemplate;

    PortalWorkMetaControllerTest(
            @Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
    }

    private String owner;
    private String portalToken;
    private String internalToken;
    private long rawSn;
    private long srcSn;

    @BeforeEach
    void setUp() {
        owner = "work-ctl-" + System.nanoTime();
        portalToken = JwtTestSupport.token(secret, owner, "PORTAL_USER", "PORTAL", issuer, 600);
        internalToken = JwtTestSupport.token(secret, "9001", "REVIEWER", "INTERNAL", issuer, 600);
        rawSn = txTemplate.execute(s ->
                assetRepository.insertUploaded(owner, "/portal/v.mp4", "v.mp4", "video/mp4", 100L));
        srcSn = txTemplate.execute(s ->
                frameRepository.save(LsDataSrc.create(rawSn, 0L, "/portal/frames/0.png", null)).getSrcSn());
    }

    @Test
    @DisplayName("메타_저장_후_조회하면_편집목록에_담기고_축은_소문자_표기다")
    void saveThenLoadMeta() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("metaKey", "manual-timeseries", "metaVl", "내 서술", "scope", "video"))));

        mockMvc.perform(put("/v1/portal/frames/{srcSn}/meta", srcSn)
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // ⚠ 목록에는 컬럼 축(촬영환경·프레임 설명·개인정보 판정)이 함께 담기므로 순서에
                //   기대지 않고 키로 골라 본다.
                .andExpect(jsonPath("$.data.items[?(@.metaKey == 'manual-timeseries')].metaVl")
                        .value("내 서술"))
                .andExpect(jsonPath("$.data.items[?(@.metaKey == 'manual-timeseries')].scope")
                        .value("video"))
                .andExpect(jsonPath("$.data.items[?(@.metaKey == 'manual-timeseries')].overridden")
                        .value(false))
                // 컬럼 축이 키/값 모양으로 함께 내려가고 출처가 병기된다.
                //   ⚠ 이 픽스처는 촬영일시가 없어 시간대의 자동 계산값도 없다 — 그때는 「값 자체가
                //   없음」이 정답이다(지어내지 않는다). 자동 계산값이 서는 경우는 통합시험이 본다.
                .andExpect(jsonPath("$.data.items[?(@.metaKey == 'env.timeOfDay')].scope")
                        .value("video"))
                .andExpect(jsonPath("$.data.items[?(@.metaKey == 'env.timeOfDay')].source")
                        .value("NONE"))
                .andExpect(jsonPath("$.data.items[?(@.metaKey == 'frame.description')].scope")
                        .value("frame"));

        mockMvc.perform(get("/v1/portal/frames/{srcSn}/meta", srcSn)
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.srcSn").value(srcSn))
                .andExpect(jsonPath("$.data.technicalMeta").isArray())
                .andExpect(jsonPath("$.data.readOnlyMeta").isArray());
    }

    @Test
    @DisplayName("이벤트_어노테이션은_구조체를_중첩째_왕복한다")
    void saveThenLoadAnnotation() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("annotation",
                Map.of("caption", Map.of("c1", Map.of("caption_text", "캡션")))));

        mockMvc.perform(put("/v1/portal/videos/{rawSn}/event-annotation", rawSn)
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.annotation.caption.c1.caption_text").value("캡션"))
                .andExpect(jsonPath("$.data.overridden").value(false));

        mockMvc.perform(get("/v1/portal/videos/{rawSn}/event-annotation", rawSn)
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.annotation.caption.c1.caption_text").value("캡션"));
    }

    @Test
    @DisplayName("내부_채널_토큰은_포털_작업_창구에_접근하지_못한다")
    void internalChannelIsIsolated() throws Exception {
        mockMvc.perform(get("/v1/portal/frames/{srcSn}/meta", srcSn)
                        .header("Authorization", "Bearer " + internalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/videos/{rawSn}/event-annotation", rawSn)
                        .header("Authorization", "Bearer " + internalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("표시_전용_키_저장은_400이고_거부_메시지에_요청_키가_실리지_않는다")
    void uneditableKeyIsRejectedWithoutEcho() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("metaKey", "portal.upload_status", "metaVl", "READY", "scope", "video"))));

        mockMvc.perform(put("/v1/portal/frames/{srcSn}/meta", srcSn)
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("upload_status"))));
    }

    @Test
    @DisplayName("모르는_축_값은_400이다_임의로_한쪽에_배정하지_않는다")
    void unknownScopeIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("metaKey", "note", "metaVl", "값", "scope", "clip"))));

        mockMvc.perform(put("/v1/portal/frames/{srcSn}/meta", srcSn)
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는_프레임은_404다")
    void absentFrameIsNotFound() throws Exception {
        mockMvc.perform(get("/v1/portal/frames/{srcSn}/meta", 999_999_999L)
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("남의_자산은_403이다")
    void otherOwnerIsForbidden() throws Exception {
        String stranger = JwtTestSupport.token(secret, owner + "-other", "PORTAL_USER", "PORTAL", issuer, 600);

        mockMvc.perform(get("/v1/portal/frames/{srcSn}/meta", srcSn)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden());
    }
}
