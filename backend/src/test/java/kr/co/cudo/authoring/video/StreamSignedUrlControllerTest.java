package kr.co.cudo.authoring.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.security.StreamNonceCookie;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.StreamUrlSigner;
import org.junit.jupiter.api.AfterEach;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 스트림 재생 주소 발급 + 재생 인증 회귀 (Round 4 I-3 → ADR-071 · API-084 · API-114 · AC-1015).
 *
 * <p>&lt;video&gt; 가 Authorization 헤더를 못 붙여 401 이 나는 문제를, 발급 창구가 내려준
 * <b>발급자 봉인 쿠키</b>로 우회한다. 실 HTTP 직렬화 레이어(MockMvc)로 재생 인증 필터 + 보안 체인을 통과시켜 검증한다.
 *
 * <h3>판정은 쿠키 하나다 (ADR-071)</h3>
 * <p>재생 요청은 쿼리 {@code u} 에게 봉인된 쿠키가 있으면 통과한다. 주소의 {@code exp}·{@code sig} 는 판정에
 * 쓰이지 않는다 — 구 동작은 서명(60초)이 먼저 만료돼 재생 도중 부분 요청이 401 로 끊겼다. 그래서 만료·변조된
 * 서명이라도 유효 쿠키가 있으면 통과하는 것이 <b>새 사양</b>이며, 이 클래스가 그것을 고정한다.
 * 주소만 복사한 요청(쿠키 없음)·봉인 불일치(조작 값·다른 발급자 쿠키·{@code u} 변조)·{@code u} 누락은 401 이다.
 *
 * <p>시드 역할(V9001): 1=REVIEWER.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class StreamSignedUrlControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private StreamUrlSigner signer;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private StreamNonceCookie streamNonceCookie;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;
    // 만료된 주소를 동일 시크릿으로 재현하기 위해 설정값을 주입받는다 (하드코딩 금지).
    @Value("${authoring.stream.sign-secret}") private String streamSignSecret;

    /** 테스트용 nonce — 실제 발급 형식(32 hex)과 동일. 시크릿이 아니라 클라이언트 바인딩 랜덤값이다. */
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_NONCE = "fedcba9876543210fedcba9876543210";

    private static final Pattern COOKIE_VALUE =
            Pattern.compile(StreamNonceCookie.COOKIE_NAME + "=([0-9a-f]{32})\\.[0-9a-f]{64}");

    private String token;
    private Long rawSn;
    /** 공유 저장소에 쓴 비식별 영상 — {@link #cleanupSharedStorage()} 가 지운다. */
    private Path deidVideoPath;
    private static final int FILE_SIZE = 10_000;

    /**
     * 서버가 실제로 내려보내는 <b>봉인된</b> 형태로 쿠키를 만든다 (DEV_FIX M-1).
     * <p>봉인은 서버 비밀 + subject 로 계산되므로 테스트가 HMAC 을 재구현하지 않고 프로덕션 로직을 그대로 쓴다.
     */
    private Cookie nonceCookie(String nonce, String subject) {
        return new Cookie(StreamNonceCookie.COOKIE_NAME, streamNonceCookie.seal(nonce, subject));
    }

    /** 봉인되지 않은 raw 값 쿠키 — 서버가 발급하지 않은 값을 심는 상황(nonce fixation) 모사. */
    private Cookie rawNonceCookie(String value) {
        return new Cookie(StreamNonceCookie.COOKIE_NAME, value);
    }

    @BeforeEach
    void setup() throws IOException {
        token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        String uniq = "SIGNURL-" + System.nanoTime();
        String relPath = "stream-test/" + uniq + ".mp4";
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + uniq, "CCTV-STREAM", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, relPath,
                LocalDateTime.now(), 30);
        // 비식별 완료 상태('Y') — 서명 URL 발급/스트림 게이트(DE_IDNTF_YN='Y' 요구) 통과 전제.
        raw.markDeidentified("Y");
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        // Phase 2: 마킹 스트림은 비식별 영상을 서빙 → 비식별 <영상> 규약 위치({deid}/videos/{rawSn}/)에
        //          비식별 파일 + 성공 procLog 준비(B-ISSUE-41 — 읽기 허용 base 는 이 서브트리다).
        Path deidBase = Paths.get(storageDeidPath).toAbsolutePath().normalize();
        Path videoPath = deidBase.resolve("videos").resolve(String.valueOf(rawSn))
                .resolve(uniq + ".mp4").normalize();
        Files.createDirectories(videoPath.getParent());
        Files.write(videoPath, new byte[FILE_SIZE]);
        deidVideoPath = videoPath;

        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null,
                Paths.get(storageRawPath).resolve(relPath).toString(), "test");
        procLog.succeed(videoPath.toString());
        procLogRepository.save(procLog);
    }

    /**
     * 공유 저장소에 쓴 비식별 영상을 되돌린다 — {@code @TempDir} 이 아니라
     * <b>프로젝트 상대 경로</b>({@code ./storage/deidentified})라 지우지 않으면 실행이 끝나도 남는다.
     *
     * <p>남기면 다음 실행에서 <b>같은 {@code RAW_SN} 을 받은 다른 테스트</b>가 이 파일을 자기 영상의
     * 산출물로 열거한다({@code RAW_SN} 은 테스트 DB 가 실행마다 새로 뜨므로 같은 값이 재발급된다).
     * 실제로 이 잔재가 비식별 신고 후보 목록의 건수 단언을 깨뜨린 사고가 있었다.
     *
     * <p>⚠ <b>자기가 만든 파일만 지우고 부모 디렉터리는 건드리지 않는다.</b> 이 경로는 실행 간 공유라
     * 다른 실행의 잔재가 같은 디렉터리에 함께 있을 수 있는데, {@code Files.deleteIfExists} 는 비어 있지
     * 않은 디렉터리에 {@code DirectoryNotEmptyException} 을 <b>던진다</b>(조용히 건너뛰지 않는다).
     * 그래서 부모까지 지우려던 구현이 이 클래스의 테스트 전량을 실패시킨 적이 있다.
     *
     * <p>또한 <b>정리 실패는 테스트를 실패시키지 않는다</b> — 정리는 위생이지 검증 대상이 아니다.
     */
    @AfterEach
    void cleanupSharedStorage() {
        if (deidVideoPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(deidVideoPath);
        } catch (IOException ignored) {
            // 정리 실패는 무시한다(제품 결함이 아니다).
        }
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
        // API-114 — 배포 접두 없는 API 기준 경로로 시작한다(로컬 컨텍스트에서도 종전과 같은 값).
        assertThat(url).startsWith("/api/v1/videos/" + rawSn + "/stream?exp=");
        assertThat(url).contains("&sig=");
    }

    @Test
    @DisplayName("서명URL_발급시_HttpOnly_nonce_쿠키가_내려가고_URL에는_없음")
    void issueStreamUrl_setsHttpOnlyNonceCookie() throws Exception {
        MvcResult res = mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains(StreamNonceCookie.COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");

        // nonce 는 URL 에 절대 실리지 않는다 — URL 유출만으로 서명을 재구성할 수 없어야 하기 때문.
        String url = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("url").asText();
        Matcher m = COOKIE_VALUE.matcher(setCookie);
        assertThat(m.find()).isTrue();
        assertThat(url).doesNotContain(m.group(1));
    }

    @Test
    @DisplayName("서명URL_은_발급자_쿠키_없이_재사용하면_차단됨")
    void issuedUrl_withoutCookie_401() throws Exception {
        // given: 정상 발급된 서명 URL (URL 전체가 유출된 상황을 모사)
        MvcResult issued = mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String url = objectMapper.readTree(issued.getResponse().getContentAsString())
                .path("data").path("url").asText();
        String query = url.substring(url.indexOf('?') + 1);

        // when/then: 쿠키 없이(=제3자) URL 만으로 재생 시도 → 401 (주소에 비밀이 없으므로 주소만으로는 재생 불가)
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream?" + query))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("서명URL_정상_재생은_쿠키와_함께_206")
    void issuedUrl_withCookie_206() throws Exception {
        MvcResult issued = mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String url = objectMapper.readTree(issued.getResponse().getContentAsString())
                .path("data").path("url").asText();
        String query = url.substring(url.indexOf('?') + 1);
        Matcher m = COOKIE_VALUE.matcher(issued.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(m.find()).isTrue();

        // when/then: 발급자 브라우저(쿠키 보유)는 정상 재생 — Range 요청도 반복 가능해야 한다.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/v1/videos/" + rawSn + "/stream?" + query)
                            .cookie(nonceCookie(m.group(1), "1"))
                            .header(HttpHeaders.RANGE, "bytes=0-1023"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 1024L));
        }
    }

    @Test
    @DisplayName("같은_발급자에게_봉인된_다른_nonce_쿠키도_유효한_자격이라_재생된다 — 서명과의 nonce 결속 폐기(ADR-071)")
    void issuedUrl_withSameSubjectDifferentNonceCookie_200() throws Exception {
        // given: 주소의 서명은 NONCE 로 만들었지만, 쿠키는 같은 발급자("1")에게 봉인된 다른 nonce.
        //   구 판정은 서명 입력의 nonce 불일치로 401 이었다. 쿠키 단독 판정에서는 "서버가 u=1 에게 발급한
        //   쿠키" 라는 사실만 보므로 정당한 자격이다(예: 재발급으로 쿠키가 갱신된 뒤 옛 주소로 이어 재생).
        // ※ 타인 쿠키 거부의 의도는 stream_nonceSealedForOtherSubject_401 이 지킨다.
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);

        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig())
                        .cookie(nonceCookie(OTHER_NONCE, "1")))
                .andExpect(status().isOk());
    }

    // ─────────────── DEV_FIX M-1 — nonce fixation (클라이언트가 준 값을 그대로 신뢰 금지) ───────────────

    @Test
    @DisplayName("공격자가_심은_봉인없는_nonce_쿠키는_서명비밀로_채택되지_않음")
    void plantedUnsealedNonce_isNotAdopted() throws Exception {
        // given: 공격자가 피해자 브라우저에 임의 nonce 를 심어둔 상태(평문 HTTP 덮어쓰기 등)
        // when: 피해자가 서명 URL 을 발급받는다
        MvcResult issued = mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + token)
                        .cookie(rawNonceCookie(NONCE)))
                .andExpect(status().isOk())
                .andReturn();

        // then: 서버는 심어진 값을 폐기하고 새 nonce 를 봉인해 재발급한다 →
        //       공격자가 아는 nonce 로 서명이 만들어지지 않으므로 URL 유출 시에도 재생 불가.
        Matcher m = COOKIE_VALUE.matcher(issued.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isNotEqualTo(NONCE);
    }

    @Test
    @DisplayName("타인_subject_로_봉인된_nonce_쿠키는_채택되지_않음")
    void nonceSealedForOtherSubject_isNotAdopted() throws Exception {
        // given: 다른 사용자(999)용으로 정상 봉인된 쿠키를 피해자(sub=1) 브라우저에 이식
        MvcResult issued = mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + token)
                        .cookie(nonceCookie(NONCE, "999")))
                .andExpect(status().isOk())
                .andReturn();

        // then: subject 바인딩 불일치로 폐기 → 새 nonce 발급
        Matcher m = COOKIE_VALUE.matcher(issued.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isNotEqualTo(NONCE);
    }

    @Test
    @DisplayName("스트림_봉인없는_nonce_쿠키로는_쿠키판정_실패_401")
    void stream_unsealedNonceCookie_401() throws Exception {
        // given: 공격자가 아는 nonce 로 만든 서명 + 봉인 없는 원시 쿠키 → 봉인 검증 실패(fail-closed)
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);

        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig())
                        .cookie(rawNonceCookie(NONCE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_타인_subject_봉인_쿠키로는_쿠키판정_실패_401")
    void stream_nonceSealedForOtherSubject_401() throws Exception {
        // given: 다른 사용자(999)에게 봉인된 쿠키로 u=1 주소를 재생 → 봉인 불일치(타인 쿠키 거부)
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);

        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig())
                        .cookie(nonceCookie(NONCE, "999")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("서명URL_발급_비인증_401")
    void issueStreamUrl_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_쿼리_쿠키_헤더_모두없음_401")
    void stream_noSignature_noHeader_401() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스트림_유효쿠키_200_AcceptRanges")
    void stream_validSignature_200() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig())
                        .cookie(nonceCookie(NONCE, "1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    @Test
    @DisplayName("스트림_유효쿠키_Range_206_PartialContent")
    void stream_validSignature_range_206() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig())
                        .cookie(nonceCookie(NONCE, "1"))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 1024L));
    }

    @Test
    @DisplayName("★주소_만료후에도_유효쿠키면_재생된다 — 서명 만료로 재생이 끊기지 않는다(AC-1015 · ADR-071)")
    void stream_expiredAddress_withValidCookie_206() throws Exception {
        // given: exp 가 과거인 동일 시크릿 서명(발급 후 TTL 이 지난 상황) + 발급자 봉인 쿠키
        long pastExp = Instant.now().minusSeconds(120).getEpochSecond();
        String expiredSig = computeHex(rawSn, pastExp, "1", NONCE);
        // when/then: 같은 재생 세션의 부분 요청이 계속 성공한다 — 판정은 쿠키 하나다.
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(pastExp))
                        .param("u", "1")
                        .param("sig", expiredSig)
                        .cookie(nonceCookie(NONCE, "1"))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent());
    }

    @Test
    @DisplayName("스트림_서명값이_달라도_유효쿠키면_재생된다 — sig 는 판정에 쓰지 않는다(ADR-071)")
    void stream_tamperedSignature_withValidCookie_200() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);
        // sig 마지막 글자 변조
        String tampered = p.sig().substring(0, p.sig().length() - 1)
                + (p.sig().endsWith("a") ? "b" : "a");
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", tampered)
                        .cookie(nonceCookie(NONCE, "1")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("스트림_유효쿠키로_다른영상을_요청하면_영상단위_판정이_그대로_걸린다_404")
    void stream_validCookie_otherVideo_stillJudgedPerVideo() throws Exception {
        // given: rawSn 으로 발급받은 주소·쿠키를 다른(존재하지 않는) rawSn 경로에 재사용.
        //   쿠키는 영상에 결속되지 않으므로 인증은 통과한다(구 동작: 서명 입력 불일치 401).
        //   그 뒤 영상 단위 판정(인가 → 신고 게이트 → 존재·비식별 확인)은 그대로 걸린다.
        //   사용자 1 은 REVIEWER(V9001 시드)라 인가를 통과하고, 영상이 없으므로 404 다.
        //   ※ 쿠키가 유효해도 권한 밖 영상이 거부되는 축은 VideoStreamAssignmentAuthorizationTest 가 지킨다.
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);
        long otherRawSn = rawSn + 999_999L;
        mockMvc.perform(get("/v1/videos/" + otherRawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig())
                        .cookie(nonceCookie(NONCE, "1")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("주소의_u_를_변조하면_쿠키봉인_불일치로_401")
    void stream_tamperedUser_401() throws Exception {
        // given: 사용자 '1' 에게 봉인된 쿠키를 들고 주소의 u 만 다른 사용자('999')로 변조.
        // 봉인은 서버 비밀 + u 로 계산되므로 u='999' 로 계산한 봉인과 쿠키의 봉인이 다르다 → 거부(fail-closed).
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "999")
                        .param("sig", p.sig())
                        .cookie(nonceCookie(NONCE, "1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("주소에_exp_sig_가_없어도_u_와_유효쿠키만으로_재생된다(ADR-071)")
    void stream_onlyUserAndCookie_noExpNoSig_206() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("u", "1")
                        .cookie(nonceCookie(NONCE, "1"))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 1024L));
    }

    @Test
    @DisplayName("주소에_u_가_없으면_유효쿠키가_있어도_401 — 봉인을 검증할 발급자가 없다")
    void stream_cookieWithoutUser_401() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("sig", p.sig())
                        .cookie(nonceCookie(NONCE, "1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("서명URL_발급_URL에_u바인딩_포함")
    void issueStreamUrl_includesUserBinding() throws Exception {
        // when: 인증된 사용자(sub=1)가 stream-url 발급
        // then: 발급 URL 에 u={userNo} 쿼리가 포함된다 — 재생 판정이 이 값으로 쿠키 봉인을 검증한다
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

    /** signer 와 동일한 시크릿/canonical 포맷으로 hex 서명 계산 (만료된 주소 재현용 — 판정에는 쓰이지 않는다). */
    private String computeHex(long rawSn, long exp, String userNo, String nonce) throws Exception {
        String canonical = rawSn + "." + exp + "." + userNo + "." + nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(streamSignSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(raw);
    }
}
