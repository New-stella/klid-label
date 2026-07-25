package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.HmacSigner;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimitStore;
import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimiter;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹훅 인증 우회(CWE-436 Interpretation Conflict / CWE-288) 회귀 차단 — <b>실 Security 체인</b> 검증.
 *
 * <p>1차 전수 검증(2026-07-25)에서 {@code POST /api/v1/%61ug/callback} 이 서명 없이
 * {@code HmacWebhookFilter} 를 건너뛰고 컨트롤러에 도달해 <b>무인증으로 증강행을 전이</b>시킨 사실이
 * 실증되었다(E-ISSUE-01 CRITICAL). 원인은 필터가 {@code getRequestURI()}(퍼센트 디코딩 <b>전</b> 원문)의
 * 문자열 정확일치로 적용 여부를 판정하는데, Spring MVC 라우팅은 <b>디코딩된 경로</b>를 쓰기 때문이다.
 *
 * <p><b>필터 단위테스트로 갈음 금지</b>: {@code MockHttpServletRequest} 기반 단위테스트는 필터가 통째로
 * 무력화돼도 GREEN 이 될 수 있다(위양성, S-21). 본 IT 는 {@code MockMvc} + 실제
 * {@code springSecurityFilterChain} + 실제 DispatcherServlet 라우팅으로 판정한다.
 *
 * <h3>단언 축</h3>
 * <ul>
 *   <li>모든 경로 변형이 2xx 가 아니며(=컨트롤러 미도달), <b>DB 가 무변경</b>(PENDING 증강행 유지 + 파생영상 0건)</li>
 *   <li>정규 경로 + 유효 서명은 200 으로 정상 전이 — 방어가 정상 플로우를 죽이지 않음</li>
 *   <li>StrictHttpFirewall 기본값 유지(이중슬래시·세미콜론·인코딩 슬래시 거부) 회귀 고정</li>
 * </ul>
 *
 * <p>공유 Testcontainers PG 격리: 시드는 {@code BYPASS-} 고유 clipId 로 만들고 단언을 시드 PK 로 좁힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "webhook.hmac.secret.augment=bypass-it-secret-32bytes-min-len-aa!!"
})
class WebhookPathBypassSecurityIT {

    private static final String HMAC_KEY_VALUE = "bypass-it-secret-32bytes-min-len-aa!!";
    private static final String CALLBACK_PATH = HmacWebhookFilter.PATH_AUGMENT;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private WebhookIdempotencyLedger ledger;
    @Autowired private WebhookRateLimitStore rateLimitStore;

    /** 우회 시도에 사용할 경로 변형 — 인코딩·대소문자·구분자·순회·널바이트. */
    private static List<String> bypassVariants() {
        return List.of(
                "/v1/%61ug/callback",       // 퍼센트 인코딩 (a → %61) — 1차 검증에서 실제 관통
                "/v1/%2561ug/callback",     // 이중 퍼센트 인코딩
                "/v1/AUG/callback",         // 대문자
                "/v1/aug/callback/",        // trailing slash
                "/v1//aug/callback",        // 이중 슬래시
                "/v1/aug;a=b/callback",     // 세미콜론 path parameter
                "/v1/aug/../aug/callback",  // 경로 순회
                "/v1/aug./callback",        // 후행 점
                "/v1/aug%00/callback"       // 널바이트
        );
    }

    // ─── 시드 ─────────────────────────────────────────────

    private record Seed(Long parentRawSn, Long dataAugSn, String jobId) { }

    private Seed seedPendingAug(String suffix) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "BYPASS-" + suffix + "-" + UUID.randomUUID(), "CCTV-BYPASS", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/bypass-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        LsDataSrc frame0 = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, 0L, parent.getRawSn() + "/f0.jpg", null));

        String key = "BYPASS-K-" + suffix + "-" + UUID.randomUUID();
        String jobId = "BYPASS-J-" + suffix + "-" + UUID.randomUUID();
        LsDataAug aug = augRepository.save(
                LsDataAug.createRequested(frame0.getSrcSn(), "WINTER", "1", key, jobId));
        ledger.recordIssued(key, LsWebhookIdempotency.CHANNEL_AUGMENT, jobId);
        return new Seed(parent.getRawSn(), aug.getDataAugSn(), jobId);
    }

    private byte[] successBody(Seed seed) throws Exception {
        AugmentResultRequest payload = new AugmentResultRequest(
                seed.dataAugSn(), seed.jobId(), "WINTER", "SUCCESS",
                "/storage/augment/bypass.mp4");
        return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    }

    private String sign(String timestamp, byte[] body) {
        String canonical = timestamp + "." + new String(body, StandardCharsets.UTF_8);
        return HmacWebhookFilter.SIGNATURE_PREFIX + HmacSigner.hex(HMAC_KEY_VALUE, canonical);
    }

    private String augStatus(Long dataAugSn) {
        return augRepository.findById(dataAugSn).orElseThrow().getAugProcSttsCd();
    }

    private long derivedCount(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .count();
    }

    // ─── 테스트 ─────────────────────────────────────────────

    @Test
    @DisplayName("퍼센트인코딩_경로_aug_callback_은_401_이며_DB_무변경")
    void percentEncodedPath_isUnauthorized_andNoDbChange() throws Exception {
        Seed seed = seedPendingAug("PCT");
        byte[] body = successBody(seed);

        MvcResult result = mockMvc.perform(post(URI.create("/v1/%61ug/callback"))
                        .with(remoteAddr("198.18.9.51"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("퍼센트 인코딩 변형은 HMAC 필터가 적용되어 401 이어야 한다 (E-ISSUE-01)")
                .isEqualTo(401);
        assertThat(augStatus(seed.dataAugSn()))
                .as("무인증 우회로 증강행 상태가 전이되면 안 된다")
                .isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(derivedCount(seed.parentRawSn()))
                .as("무인증 우회로 파생 영상이 생성되면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("이중_퍼센트인코딩_경로는_2xx_가_아니며_DB_무변경")
    void doubleEncodedPath_isRejected() throws Exception {
        Seed seed = seedPendingAug("DBL");
        byte[] body = successBody(seed);

        MvcResult result = mockMvc.perform(post(URI.create("/v1/%2561ug/callback"))
                        .with(remoteAddr("198.18.9.52"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).matches(st -> st < 200 || st > 299);
        assertThat(augStatus(seed.dataAugSn())).isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(derivedCount(seed.parentRawSn())).isZero();
    }

    @Test
    @DisplayName("대문자_trailing슬래시_이중슬래시_세미콜론_경로순회_후행점_널바이트_변형이_전부_차단되고_DB_무변경")
    void allPathVariants_areBlocked_andNoDbChange() throws Exception {
        Seed seed = seedPendingAug("VAR");
        byte[] body = successBody(seed);

        for (String variant : bypassVariants()) {
            MvcResult result = mockMvc.perform(post(URI.create(variant))
                            .with(remoteAddr("198.18.9.53"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("우회 변형이 컨트롤러에 도달하면 안 된다: %s", variant)
                    .matches(st -> st < 200 || st > 299);
        }

        assertThat(augStatus(seed.dataAugSn()))
                .as("우회 시도 후에도 PENDING 증강행의 상태가 변경되지 않아야 한다")
                .isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(derivedCount(seed.parentRawSn()))
                .as("우회 시도 후에도 파생 영상이 생성되지 않아야 한다")
                .isZero();
    }

    @Test
    @DisplayName("유효_서명과_타임스탬프면_정상_통과하고_증강_상태가_전이")
    void validSignature_passesAndTransitions() throws Exception {
        Seed seed = seedPendingAug("OK");
        byte[] body = successBody(seed);
        String ts = Long.toString(System.currentTimeMillis());

        mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.54"))
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, sign(ts, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        assertThat(augStatus(seed.dataAugSn()))
                .as("정상 서명 콜백은 증강행을 전이시켜야 한다")
                .isNotEqualTo(LsDataAug.STTS_PENDING);
    }

    @Test
    @DisplayName("동일_서명_재전송시_윈도우_내라도_replay_로_흡수되고_401_이_아님")
    void replayWithinWindow_returns409_not401() throws Exception {
        Seed seed = seedPendingAug("RPL");
        byte[] body = successBody(seed);
        String ts = Long.toString(System.currentTimeMillis());
        String signature = sign(ts, body);

        mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.55"))
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        MvcResult replay = mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.55"))
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(replay.getResponse().getStatus())
                .as("replay 는 인증 실패(401)가 아니라 중복 흡수(409) 로 구분돼야 한다 (S-10)")
                .isEqualTo(409);
    }

    @Test
    @DisplayName("VLM_콜백은_서명_없이도_통과해_정상_처리된다_벤더_무서명_계약_회귀고정")
    void vlmCallback_passesWithoutSignature() throws Exception {
        // 발급된 request_id 로 "끝까지 정상 처리(200 + applied=true)" 를 단정한다.
        //   구 단정("응답에 '시그니처' 문자열이 없다")은 403/411/429/게이트401 이어도 GREEN 이라
        //   무서명 계약이 깨져도 통과하는 위양성이었다(DEV_FIX M-6).
        Seed seed = seedPendingAug("VLM");
        String requestId = "VLM-REQ-" + UUID.randomUUID();
        ledger.recordIssued(requestId, LsWebhookIdempotency.CHANNEL_VLM, requestId, seed.parentRawSn());

        mockMvc.perform(post(URI.create("/v1/vlm/callback"))
                        .with(remoteAddr("198.18.9.11"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request_id\":\"" + requestId
                                + "\",\"status\":\"failed\",\"error\":{\"code\":\"E\",\"message\":\"m\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.requestId").value(requestId));
    }

    @Test
    @DisplayName("VLM_위조_request_id_연타는_6회째부터_429_로_차단된다")
    void vlmForgedRequestId_isRateLimited() throws Exception {
        // B-ISSUE-25 원 재현: 정상 형식 + 위조 request_id. 서비스단 401 은 필터의 recordFailure 이후
        // 경로라 과거에는 집계되지 않아 15연타가 전부 401 로 통과했다(DEV_FIX H-3).
        String attacker = "198.18.9.21";
        for (int i = 1; i <= 5; i++) {
            MvcResult result = mockMvc.perform(post(URI.create("/v1/vlm/callback"))
                            .with(remoteAddr(attacker))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(forgedVlmBody("forged-rl-" + i)))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%d 회차는 서비스단 request_id 게이트에서 401 이어야 한다", i)
                    .isEqualTo(401);
        }

        for (int i = 6; i <= 8; i++) {
            MvcResult result = mockMvc.perform(post(URI.create("/v1/vlm/callback"))
                            .with(remoteAddr(attacker))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(forgedVlmBody("forged-rl-" + i)))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%d 회차부터는 429 여야 한다 — 비관적 락 SELECT 무제한 유발 차단", i)
                    .isEqualTo(429);
        }
    }

    @Test
    @DisplayName("위조_X_Forwarded_For_를_매요청_회전시켜도_6회째부터_429_이고_카운터에_위조IP가_적재되지_않는다")
    void rotatingForwardedForHeader_doesNotBypassRateLimit() throws Exception {
        // ── 회귀 고정 대상 (REDESIGN 2026-07-25) ──────────────────────────────
        // `server.forward-headers-strategy: framework` 를 켜면 ForwardedHeaderFilter 가
        // Security 체인보다 앞에서 getRemoteAddr() 을 X-Forwarded-For 첫 토큰으로 무검증 치환한다.
        // 그러면 rate limit 키가 매 요청 갈라져 위 vlmForgedRequestId_isRateLimited 가 보장하던
        // "6회째부터 429" 가 헤더 한 줄로 무력화되고(실측 12연타 전부 401), 공유 카운터 테이블에는
        // 공격자가 지정한 문자열이 IP 로 적재된다. 이 테스트는 그 설정이 되살아나면 반드시 깨진다.
        //
        // 필터 단위테스트(MockHttpServletRequest 직접 호출)로는 이 회귀를 잡을 수 없다 —
        // 상위 ForwardedHeaderFilter 를 통째로 건너뛰기 때문이다(그래서 3130건 GREEN 인 채로 뚫렸다).
        String attacker = "198.18.9.61";
        List<String> forgedIps = new ArrayList<>();

        // given/when: 실제 소스 IP 는 고정, X-Forwarded-For 만 매 요청 회전시킨다(프록시 위조 모사)
        for (int i = 1; i <= 5; i++) {
            String forged = "198.51.100." + i;
            forgedIps.add(forged);
            MvcResult result = mockMvc.perform(post(URI.create("/v1/vlm/callback"))
                            .with(remoteAddr(attacker))
                            .header("X-Forwarded-For", forged)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(forgedVlmBody("forged-xff-" + i)))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%d 회차는 서비스단 request_id 게이트에서 401 이어야 한다", i)
                    .isEqualTo(401);
        }

        // then: XFF 는 신뢰 프록시 CIDR 밖(local 은 빈 설정)이므로 폐기되고 실제 소스 IP 로 집계된다
        for (int i = 6; i <= 8; i++) {
            String forged = "198.51.100." + i;
            forgedIps.add(forged);
            MvcResult result = mockMvc.perform(post(URI.create("/v1/vlm/callback"))
                            .with(remoteAddr(attacker))
                            .header("X-Forwarded-For", forged)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(forgedVlmBody("forged-xff-" + i)))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%d 회차부터는 429 여야 한다 — XFF 회전으로 rate limit 을 우회할 수 없다", i)
                    .isEqualTo(429);
        }

        // then: 공유 카운터에는 실제 소스 IP 만 남고, 공격자가 고른 문자열은 적재되지 않는다
        assertThat(sharedFailures(attacker))
                .as("실제 소스 IP 기준으로 임계(%d)까지 집계돼야 한다",
                        WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE)
                .isGreaterThanOrEqualTo(WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);
        for (String forged : forgedIps) {
            assertThat(sharedFailures(forged))
                    .as("공격자가 지정한 값(%s)이 공유 카운터에 IP 로 적재되면 안 된다", forged)
                    .isZero();
        }
    }

    /**
     * 공유 카운터 누적치 — 분 단위 고정 버킷이므로 현재+직전 버킷을 합산한다(버킷 롤오버 flaky 방지).
     * 저장소 장애 시그널({@link WebhookRateLimitStore#UNAVAILABLE})은 0 으로 환산한다.
     */
    private int sharedFailures(String ip) {
        LocalDateTime window = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        return Math.max(0, rateLimitStore.currentFailures(ip, window))
                + Math.max(0, rateLimitStore.currentFailures(ip, window.minusMinutes(1)));
    }

    @Test
    @DisplayName("유효_서명을_퍼센트인코딩_경로변형으로_재전송하면_409_로_차단된다")
    void replayViaEncodedPathVariant_isBlocked() throws Exception {
        Seed seed = seedPendingAug("ENC");
        byte[] body = successBody(seed);
        String ts = Long.toString(System.currentTimeMillis());
        String signature = sign(ts, body);

        mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.31"))
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 서명 canonical(timestamp + "." + body)에는 경로가 없다 → 서명 검증은 통과한다.
        // nonce 키가 원시 URI 기준이면 인코딩 변형마다 키가 갈라져 replay 가 전부 신규로 통과한다(H-2).
        for (String variant : List.of("/v1/%61ug/callback", "/v1/a%75g/callback",
                "/v1/au%67/callback", "/v1/aug/%63allback")) {
            MvcResult replay = mockMvc.perform(post(URI.create(variant))
                            .with(remoteAddr("198.18.9.31"))
                            .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                            .header(HmacWebhookFilter.SIGNATURE_HEADER, signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();

            assertThat(replay.getResponse().getStatus())
                    .as("인코딩 변형 replay 가 통과하면 인증 후 증폭 DoS 가 된다: %s", variant)
                    .isEqualTo(409);
        }
    }

    @Test
    @DisplayName("StrictHttpFirewall_기본값이_유지되어_이중슬래시_세미콜론_인코딩슬래시가_400_으로_선차단됨")
    void strictHttpFirewall_defaultsRetained() throws Exception {
        // 구 단정(isIn(400,401,403,404))은 firewall 을 완화해도 요청이 필터에 잡혀 401 이 되면 GREEN
        // 이었다 — "완화가 들어오면 깨진다" 는 주석이 거짓이었다(DEV_FIX M-6).
        // firewall 선차단(400)과 필터 인증실패(401)를 명확히 구분해 단정한다.
        for (String hostile : List.of("/v1//aug/callback", "/v1/aug;a=b/callback", "/v1/aug%2fcallback")) {
            MvcResult result = mockMvc.perform(post(URI.create(hostile))
                            .with(remoteAddr("198.18.9.41"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("StrictHttpFirewall 이 필터 이전에 400 으로 거부해야 한다(완화 시 401 로 바뀜): %s",
                            hostile)
                    .isEqualTo(400);
        }
    }

    private static String forgedVlmBody(String requestId) {
        return "{\"request_id\":\"" + requestId
                + "\",\"status\":\"failed\",\"error\":{\"code\":\"E\",\"message\":\"m\"}}";
    }

    /**
     * 요청 소스 IP 고정 — rate limit 은 IP 단위 공유 카운터라, 기본값(127.0.0.1)을 그대로 쓰면
     * 같은 컨텍스트의 다른 테스트가 쌓은 실패에 연좌돼 플레이키해진다.
     */
    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
