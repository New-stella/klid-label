package kr.co.cudo.authoring.portal.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 업로드 영상 재생 — <b>보안 체인을 실제로 통과</b>시키는 회귀.
 *
 * <p>재생 요소는 인증 헤더를 싣지 못하므로 서명 질의만으로 스트림에 도달해야 한다. 그 도달은 필터
 * 등록과 채널 격리 매처가 <b>함께</b> 맞아야 성립하므로, 서비스 단위 시험으로는 확인되지 않는다.
 *
 * <p>★ 동시에 <b>권한이 확대되지 않는다</b>는 것도 함께 고정한다 — 서명 컨텍스트는 역할 권한을 갖지
 * 않으므로 다른 포털 창구에는 닿지 못한다. 이 축이 깨지면 재생 주소 하나가 자산 삭제·라벨 저장까지
 * 여는 열쇠가 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalStreamSignedUrlIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PortalUploadAssetRepository assetRepository;
    /**
     * ★ 저장 경로를 시험별 임시 디렉터리로 덮지 않는다 — 그렇게 하면 <b>스프링 테스트
     * 컨텍스트가 하나 더 생기고 되돌아가지 않는다</b>(그 수의 래칫 가드가 실재한다).
     * 대신 운영 기본 경로 아래에 자기 파일만 쓰고 끝나면 그 파일만 지운다.
     */
    @Autowired private PortalUploadProperties uploadProperties;

    /** 이 시험이 만든 파일 — 공유 경로라 자기 것만 지운다(디렉터리는 건드리지 않는다). */
    private Path createdVideo;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static final String ALICE = "stream-alice";
    private static final String BOB = "stream-bob";

    private String aliceToken;
    private String bobToken;
    private Long uldSn;

    @BeforeEach
    void setUp() throws IOException {
        aliceToken = JwtTestSupport.token(secret, ALICE, "PORTAL_USER", "PORTAL", issuer, 60);
        bobToken = JwtTestSupport.token(secret, BOB, "PORTAL_USER", "PORTAL", issuer, 60);

        Path base = Paths.get(uploadProperties.storagePath()).toAbsolutePath().normalize();
        Files.createDirectories(base);
        createdVideo = base.resolve("stream-it-" + System.nanoTime() + ".mp4");
        Files.write(createdVideo, new byte[4096]);
        uldSn = assetRepository.insertUploaded(
                ALICE, createdVideo.toString(), "v.mp4", "video/mp4", 4096L);
    }

    @AfterEach
    void cleanupRows() {
        for (String user : new String[]{ALICE, BOB}) {
            assetRepository.findPageByOwner(user, null, PageRequest.of(0, 100)).getContent()
                    .forEach(u -> assetRepository.deleteOwned(u.uldSn(), user));
        }
        // 공유 경로라 자기가 만든 파일만 지운다 — 부모 디렉터리는 다른 실행의 잔재가 함께 있을 수 있다.
        if (createdVideo != null) {
            try {
                Files.deleteIfExists(createdVideo);
            } catch (IOException ignored) {
                // 정리 실패는 제품 결함이 아니다.
            }
        }
    }


    private String issueSignedQuery() throws Exception {
        MvcResult res = mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/stream-url")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        String url = body.path("data").path("url").asText();
        assertThat(url).contains("sig=");
        return url.substring(url.indexOf('?') + 1);
    }

    @Test
    @DisplayName("★서명_질의만으로_스트림에_도달한다 — 재생_요소는_인증_헤더를_싣지_못한다")
    void signedQueryReachesStreamWithoutToken() throws Exception {
        String query = issueSignedQuery();

        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/stream?" + query))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰도_서명도_없으면_401")
    void noTokenNoSignatureIsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("서명이_변조되면_401")
    void tamperedSignatureIsUnauthorized() throws Exception {
        String query = issueSignedQuery();
        String tampered = query.substring(0, query.length() - 1)
                + (query.endsWith("0") ? "1" : "0");

        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/stream?" + tampered))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ★★ 권한 확대 금지 — 서명 컨텍스트는 스트림 창구 밖으로 나가지 못한다.
     */
    @Test
    @DisplayName("★★서명_컨텍스트는_다른_포털_창구에_닿지_못한다")
    void signedContextCannotReachOtherPortalEndpoints() throws Exception {
        String query = issueSignedQuery();

        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "?" + query))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/markings?" + query))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("남의_자산은_발급도_스트림도_403")
    void foreignAssetIsForbidden() throws Exception {
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/stream-url")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/stream")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("구간_요청은_206")
    void rangeRequestIsPartialContent() throws Exception {
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/stream")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent());
    }
}
