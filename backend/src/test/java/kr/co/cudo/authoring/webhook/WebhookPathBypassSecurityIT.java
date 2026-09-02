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
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.webhook.dto.GenAiCallbackRequest;
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
        "webhook.hmac.secret.augment=bypass-it-secret-32bytes-min-len-aa!!",
        // B-2 — 콜백 본문의 raw_file_path_nm(/storage/augment/bypass.mp4)이 적재 시점 allowlist 검증을
        //   통과해야 "인증 통과 후 정상 처리" 경로가 그대로 검증된다.
        "authoring.storage.raw-mount-roots=/storage"
})
class WebhookPathBypassSecurityIT {

    private static final String CALLBACK_PATH =
            kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths.PATH_GENAI_CALLBACK;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository augJobRepository;
    @Autowired private LsDataAugJobFileRepository augJobFileRepository;
    @Autowired private kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository
            deidentProcLogRepositoryForFixture;
    @Autowired private WebhookIdempotencyLedger ledger;
    @Autowired private WebhookRateLimitStore rateLimitStore;

    /** 우회 시도에 사용할 경로 변형 — 인코딩·대소문자·구분자·순회·널바이트. */
    private static List<String> bypassVariants() {
        return List.of(
                "/v1/%67enai/callback",        // 퍼센트 인코딩 (g → %67) — 1차 검증에서 실제 관통한 수법
                "/v1/%2567enai/callback",      // 이중 퍼센트 인코딩
                "/v1/GENAI/callback",          // 대문자
                "/v1/genai/callback/",         // trailing slash
                "/v1//genai/callback",         // 이중 슬래시
                "/v1/genai;a=b/callback",      // 세미콜론 path parameter
                "/v1/genai/../genai/callback", // 경로 순회
                "/v1/genai./callback",         // 후행 점
                "/v1/genai%00/callback"        // 널바이트
        );
    }

    // ─── 시드 ─────────────────────────────────────────────

    private record Seed(Long parentRawSn, Long dataAugSn, String jobId, String requestId) { }

    private Seed seedPendingAug(String suffix) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "BYPASS-" + suffix + "-" + UUID.randomUUID(), "CCTV-BYPASS", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/bypass-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        seedParentDeidVideo(parent.getRawSn(), parent.getRawFilePathNm());
        LsDataSrc frame0 = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, 0L, parent.getRawSn() + "/f0.jpg", null));

        String key = "BYPASS-K-" + suffix + "-" + UUID.randomUUID();
        String jobId = "BYPASS-J-" + suffix + "-" + UUID.randomUUID();
        LsDataAug aug = augRepository.save(
                LsDataAug.createRequested(frame0.getSrcSn(), "WINTER", "1", key, jobId));
        // 새 계약의 request_id 발급 원장 = LS_DATA_AUG_JOB.IDMP_KEY (위탁 직전 선기록).
        LsDataAugJob job = LsDataAugJob.createIssued(aug.getDataAugSn(), 1, key, 1);
        job.markAccepted(jobId);
        job = augJobRepository.save(job);
        // Phase 7-D — 위탁 항목 1건(콜백 results 1건과 건수 일치. 불일치면 fail-closed 로 실패 종결).
        augJobFileRepository.save(LsDataAugJobFile.issued(job.getAugJobSn(), 1, frame0.getSrcSn()));
        // 구 LS_WEBHOOK_IDEMPOTENCY(AUGMENT) 선기록은 발급 게이트가 아니므로 시드하지 않는다.
        return new Seed(parent.getRawSn(), aug.getDataAugSn(), jobId, key);
    }

    /**
     * 부모 <b>비식별 영상 산출물</b> 처리 이력 — 파생 복사 원본 경로의 진실원(ADR-058).
     *
     * <p>흡수 이전 부모 게이트는 {@code DE_IDENT_YN} <b>플래그</b>만 봤고 실제 경로는 비동기 Phase A 에서야
     * 확인했다. 지금은 게이트가 <b>경로를 직접 조달</b>하므로(「비식별이 끝났나」는 조건이 아니라 결과였다)
     * 플래그만 세운 부모로는 통과하지 않는다 — 관제 경로가 <b>더 엄격해진</b> 지점이며, 운영에서는
     * {@code DE_IDENT_YN='Y'} 전이와 성공 처리 이력 적재가 <b>같은 트랜잭션</b>이라 이 조합이 실재하지
     * 않는다(구 픽스처가 실제보다 느슨했다). 경로는 상대값으로 둔다 — co-locate 디렉터리와 비식별 저장소
     * 서브트리({@code videos/**}) 양쪽에서 해석된다.
     */
    private void seedParentDeidVideo(Long parentRawSn, String orgnlFilePath) {
        var procLog = kr.co.cudo.authoring.batch.entity.LsDeidentProcLog.request(
                parentRawSn, null, orgnlFilePath, "test");
        procLog.succeed("videos/" + parentRawSn + "/deidentified.mp4");
        deidentProcLogRepositoryForFixture.saveAndFlush(procLog);
    }

    /** 발급된 request_id 를 실은 정상 SUCCEEDED 본문. */
    private byte[] successBody(Seed seed) throws Exception {
        return bodyOf(seed, seed.requestId());
    }

    /** 발급되지 않은 request_id 를 실은 본문 — 우회 시도용(통과해도 서비스 게이트가 401). */
    private byte[] forgedBody(Seed seed) throws Exception {
        return bodyOf(seed, "BYPASS-K-FORGED-" + UUID.randomUUID());
    }

    private byte[] bodyOf(Seed seed, String requestId) throws Exception {
        GenAiCallbackRequest payload = new GenAiCallbackRequest(
                requestId, seed.jobId(), "SUCCEEDED", 100, "COMPLETED", "2026-07-27T10:00:00Z",
                List.of(new GenAiCallbackRequest.ResultItem(
                        "gen-1", "IMAGE", "/storage/genai/bypass/001_gen.jpg", null)),
                null, null);
        return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
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
    @DisplayName("퍼센트인코딩_경로_genai_callback_은_2xx_가_아니며_DB_무변경")
    void percentEncodedPath_isUnauthorized_andNoDbChange() throws Exception {
        Seed seed = seedPendingAug("PCT");
        byte[] body = forgedBody(seed);

        MvcResult result = mockMvc.perform(post(URI.create("/v1/%67enai/callback"))
                        .with(remoteAddr("198.18.9.51"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("퍼센트 인코딩 변형도 가드 필터가 적용되고 발급 게이트에서 막혀야 한다 (E-ISSUE-01)")
                .matches(st -> st < 200 || st > 299);
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
        byte[] body = forgedBody(seed);

        MvcResult result = mockMvc.perform(post(URI.create("/v1/%2567enai/callback"))
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
        byte[] body = forgedBody(seed);

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
    @DisplayName("발급된_request_id_면_정상_통과하고_증강_상태가_전이")
    void issuedRequestId_passesAndTransitions() throws Exception {
        Seed seed = seedPendingAug("OK");
        byte[] body = successBody(seed);

        mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.54"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        assertThat(augStatus(seed.dataAugSn()))
                .as("전 job 종결(단일 job) 이므로 증강행이 전이돼야 한다")
                .isNotEqualTo(LsDataAug.STTS_PENDING);
    }

    @Test
    @DisplayName("같은_웹훅이_두번_도착해도_200_멱등흡수이며_결과가_중복_생성되지_않는다")
    void duplicateCallback_isAbsorbed() throws Exception {
        Seed seed = seedPendingAug("RPL");
        byte[] body = successBody(seed);

        mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.55"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        // 외부는 전송 실패 시 재시도한다 — 중복 수신이 정상 시나리오이므로 401/409 가 아니라
        // 200 + applied=false 로 흡수해야 벤더 재시도가 오류로 오인되지 않는다.
        mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.55"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));

        assertThat(derivedCount(seed.parentRawSn()))
                .as("중복 수신에도 파생 영상은 1건")
                .isEqualTo(1L);
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
                                + "\",\"status\":\"failed\",\"error\":\"추론 실패: m\"}"))
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
    @DisplayName("인코딩_경로변형으로_재전송해도_증강결과가_중복_확정되지_않는다")
    void replayViaEncodedPathVariant_isBlocked() throws Exception {
        Seed seed = seedPendingAug("ENC");
        byte[] body = successBody(seed);

        mockMvc.perform(post(CALLBACK_PATH)
                        .with(remoteAddr("198.18.9.31"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 인코딩 변형은 필터/인터셉터가 걸러 컨트롤러에 도달하지 못하거나, 도달하더라도 서비스
        // 멱등 앵커(job 종결 + 증강행 non-PENDING)가 중복 확정을 막아야 한다.
        for (String variant : List.of("/v1/%67enai/callback", "/v1/g%65nai/callback",
                "/v1/gena%69/callback", "/v1/genai/%63allback")) {
            mockMvc.perform(post(URI.create(variant))
                            .with(remoteAddr("198.18.9.31"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
        }

        assertThat(derivedCount(seed.parentRawSn()))
                .as("경로 변형 재전송으로 파생 영상이 증식하면 인증 후 증폭이 된다")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("StrictHttpFirewall_기본값이_유지되어_이중슬래시_세미콜론_인코딩슬래시가_400_으로_선차단됨")
    void strictHttpFirewall_defaultsRetained() throws Exception {
        // 구 단정(isIn(400,401,403,404))은 firewall 을 완화해도 요청이 필터에 잡혀 401 이 되면 GREEN
        // 이었다 — "완화가 들어오면 깨진다" 는 주석이 거짓이었다(DEV_FIX M-6).
        // firewall 선차단(400)과 필터 인증실패(401)를 명확히 구분해 단정한다.
        for (String hostile : List.of("/v1//genai/callback", "/v1/genai;a=b/callback",
                "/v1/genai%2fcallback")) {
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
                + "\",\"status\":\"failed\",\"error\":\"추론 실패: m\"}";
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
