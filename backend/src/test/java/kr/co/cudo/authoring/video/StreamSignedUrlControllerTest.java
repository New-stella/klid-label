package kr.co.cudo.authoring.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.StreamUrlSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 스트림 단기 서명 URL — 발급/검증/만료/변조/헤더경로 회귀 (Round 4 I-3).
 *
 * <p>&lt;video&gt; 가 Authorization 헤더를 못 붙여 401 이 나는 문제를 단기 HMAC 서명 URL 로 우회한다.
 * 실 HTTP 직렬화 레이어(MockMvc)로 서명 검증 필터 + 보안 체인을 통과시켜 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class StreamSignedUrlControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private StreamUrlSigner signer;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    // 만료 서명을 동일 시크릿으로 재현하기 위해 설정값을 주입받는다 (하드코딩 금지).
    @Value("${authoring.stream.sign-secret}") private String streamSignSecret;

    private String token;
    private Long rawSn;
    private static final int FILE_SIZE = 10_000;

    @BeforeEach
    void setup() throws IOException {
        token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        String uniq = "SIGNURL-" + System.nanoTime();
        String relPath = "stream-test/" + uniq + ".mp4";
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + uniq, "CCTV-STREAM", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, relPath,
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        Path baseDir = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path videoPath = baseDir.resolve(relPath).normalize();
        Files.createDirectories(videoPath.getParent());
        Files.write(videoPath, new byte[FILE_SIZE]);
    }

    @Test
    @DisplayName("서명URL_발급_인증요청_200_url_exp_반환")
    void issueStreamUrl_authenticated_200() throws Exception {
        // when: 인증된 사용자가 stream-url 발급 요청
        // then: 200 + url(서명 쿼리 포함) + expiresAt 반환
        MvcResult res = mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").exists())
                .andExpect(jsonPath("$.data.expiresAt").isNumber())
                .andReturn();

        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
        String url = data.path("url").asText();
        assertThat(url).contains("/api/v1/videos/" + rawSn + "/stream?exp=");
        assertThat(url).contains("&sig=");
    }

    @Test
    @DisplayName("서명URL_발급_비인증_401")
    void issueStreamUrl_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_서명없는_쿼리_헤더없음_401")
    void stream_noSignature_noHeader_401() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_유효서명_200_AcceptRanges")
    void stream_validSignature_200() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, null);
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("sig", p.sig()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    @Test
    @DisplayName("스트림_유효서명_Range_206_PartialContent")
    void stream_validSignature_range_206() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, null);
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("sig", p.sig())
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 1024L));
    }

    @Test
    @DisplayName("스트림_만료서명_401")
    void stream_expiredSignature_401() throws Exception {
        // given: exp 가 과거인 동일 시크릿 서명 (만료 검증 타겟)
        long pastExp = Instant.now().minusSeconds(120).getEpochSecond();
        String expiredSig = computeHex(rawSn, pastExp);
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(pastExp))
                        .param("sig", expiredSig))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_변조서명_401")
    void stream_tamperedSignature_401() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, null);
        // sig 마지막 글자 변조
        String tampered = p.sig().substring(0, p.sig().length() - 1)
                + (p.sig().endsWith("a") ? "b" : "a");
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("sig", tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_다른영상_서명_재사용_401")
    void stream_signatureForOtherVideo_401() throws Exception {
        // given: rawSn 으로 발급한 서명을 다른 rawSn 경로에 재사용 → 서명 입력 불일치
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, null);
        long otherRawSn = rawSn + 999_999L;
        mockMvc.perform(get("/v1/videos/" + otherRawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("sig", p.sig()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_userNo바인딩_정상u_200")
    void stream_userBound_validU_200() throws Exception {
        // given: userNo='1' 로 서명 + URL 쿼리 u=1 (서명 입력과 일치)
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1");
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("스트림_타사용자_u변조_재사용_401")
    void stream_tamperedUser_401() throws Exception {
        // given: userNo='1' 로 서명한 URL 의 u 만 다른 사용자('999')로 변조 → 서명 입력 불일치
        // (서명이 rawSn.exp.userNo 전체를 커버하므로 u 변조 시 서명 불일치로 거부)
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1");
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "999")
                        .param("sig", p.sig()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("서명URL_발급_URL에_u바인딩_포함")
    void issueStreamUrl_includesUserBinding() throws Exception {
        // when: 인증된 사용자(sub=1)가 stream-url 발급
        // then: 발급 URL 에 u={userNo} 쿼리가 포함되어 서명 입력과 바인딩된다
        MvcResult res = mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String url = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("url").asText();
        assertThat(url).contains("&u=1");
    }

    @Test
    @DisplayName("스트림_Authorization_헤더_경로_기존동작_200")
    void stream_authorizationHeader_stillWorks_200() throws Exception {
        // 회귀: 서명 없이 Authorization 헤더만으로도 기존대로 200
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    /** signer 와 동일한 시크릿/canonical 포맷으로 hex 서명 계산 (만료 서명 재현용). */
    private String computeHex(long rawSn, long exp) throws Exception {
        String canonical = rawSn + "." + exp + ".";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(streamSignSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(raw);
    }
}
