package kr.co.cudo.authoring.augment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.common.security.HmacSigner;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.awaitility.Awaitility;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 증강 콜백 충실 플로우 — Phase 3 통합 검증.
 *
 * <p>요청 측(LS_DATA_AUG PENDING + ledger.recordIssued) 사전조건을 갖춘 뒤,
 * {@link HmacSigner} 로 서명한 {@link AugmentResultRequest}(SUCCESS) 를 실제 콜백 엔드포인트
 * {@code POST /v1/aug/callback}(context-path /api 정합) 로 전송해 필터 → 컨트롤러 → handle
 * 경로 전체가 신규 영상을 생성하는지 단언한다.
 *
 * <h3>Phase 11 — 프레임 재추출 비동기 전환</h3>
 * <p>증강 프레임은 이제 부모 프레임을 <b>복사</b>하지 않고 증강 파일에서 <b>재추출</b>(비동기)한다.
 * 따라서 동기 콜백 커밋 직후 신규 RAW 는 PENDING·deIdntfYn='N' 이며, 프레임/라벨/COMPLETED(배치 마감)/procLog
 * 는 async 러너({@link kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner}) 성공 후에만
 * 관측된다. 본 IT 는 ffmpeg 바이너리 의존을 격리하기 위해 {@link FfmpegFrameExtractor.FrameWriter}
 * 를 @MockBean 으로 대체(재추출 성공 시뮬)하고, {@link Awaitility} 로 async 완료를 기다린 뒤 단언한다.
 *
 * <h3>검증 (HIGH 폐쇄)</h3>
 * <ul>
 *   <li>신규 LsDataRaw 생성: ORGNL_RAW_SN=원본 rawSn</li>
 *   <li>async 성공 후 신규 영상 COMPLETED(배치 마감) + DE_IDNTF_YN='Y' + SUCCESS procLog</li>
 *   <li>증강 파일에서 재추출된 프레임에 원본 라벨이 좌표 그대로 복사(건수·pointCn 일치)</li>
 *   <li>파생 영상은 처리 현황(GET /v1/videos)에서 제외됨(R1) — 증강 이력/작업 목록에서만 노출</li>
 *   <li>멱등: 동일 idempotencyKey 재수신 → 200 + 신규 영상 중복 생성 없음</li>
 *   <li>잘못된 서명 → 401</li>
 *   <li>CALLBACK_PATH 단일 출처(필터 PATH_AUGMENT) 회귀 가드</li>
 * </ul>
 *
 * <h3>테스트 격리</h3>
 * <p>공유 Testcontainers PG 를 사용하므로 시드 영상은 {@code AUGCB-} 고유 clipId 로 만들고,
 * 단언은 시드한 rawSn / parentRawSn 으로만 좁혀 다른 통합테스트 데이터 오염을 일으키지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "webhook.hmac.secret.augment=augment-it-secret-32bytes-min-len-aa!!",
        // B-2 — 콜백의 raw_file_path_nm 은 적재 시점에 고정 allowlist(마운트 루트) 하위인지 검증된다.
        //   픽스처 경로(/storage/augment/*.mp4)가 통과하도록 마운트 루트를 명시한다.
        "authoring.storage.raw-mount-roots=/storage"
})
class AugmentCallbackFlowIntegrationTest {

    /**
     * ffmpeg 바이너리 격리 — 증강 파일에서의 frame-exact 재추출을 성공 시뮬한다.
     * sourceExists=true, writeFrameByNumber=no-op(예외 없음) → AugmentFrameProducer 의 프레임 생성이
     * 전량 성공한다.
     */
    @MockBean private FfmpegFrameExtractor.FrameWriter frameWriter;

    /** 테스트 전용 HMAC 시크릿 값 — @TestPropertySource 와 동일 값을 공유한다. */
    private static final String HMAC_KEY_VALUE = "augment-it-secret-32bytes-min-len-aa!!";
    /** 콜백 경로 — 필터 PATH_AUGMENT 단일 출처 정합. */
    private static final String CALLBACK_PATH = HmacWebhookFilter.PATH_AUGMENT;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private LsDataMetaReviewRepository metaReviewRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private WebhookIdempotencyLedger ledger;
    @Autowired private kr.co.cudo.authoring.video.service.VideoStreamService videoStreamService;

    @Value("${authoring.jwt.secret}") private String jwtSecret;
    @Value("${authoring.jwt.issuer}") private String jwtIssuer;

    private String reviewerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(jwtSecret, "1", "REVIEWER", "INTERNAL", jwtIssuer, 60);
        // 증강 파일 재추출 성공 시뮬 — 소스 존재 true, 프레임 쓰기는 no-op(예외 없음).
        when(frameWriter.sourceExists(any())).thenReturn(true);
    }

    /** async 프레임 재추출이 완료되어 신규 RAW 가 COMPLETED(배치 마감) + DE_IDNTF_YN='Y' 로 확정될 때까지 대기. */
    private LsDataRaw awaitFinalizedChild(Long parentRawSn) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    LsDataRaw c = findChildOf(parentRawSn);
                    return c != null
                            && LsDataRaw.DATA_STTS_COMPLETED.equals(c.getDataSttsCd())
                            && "Y".equals(c.getDeIdntfYn());
                });
        return findChildOf(parentRawSn);
    }

    // ─── 시드 헬퍼 ─────────────────────────────────────────────

    private record Seed(LsDataRaw parentRaw, LsDataSrc frame0, LsDataSrc frame1,
                        LsDataLbl label, LsDataMeta meta, LsDataAug aug) {
    }

    /**
     * 원본 영상 + 프레임 2건 + 라벨 1건 + 메타 1건 + LS_DATA_AUG(PENDING) 시드.
     * ledger.recordIssued 로 콜백 사전조건(allowlist)도 등록한다.
     */
    private Seed seedOriginWithAug(String clipSuffix, String augType,
                                   String idempotencyKey, String externalJobId) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGCB-" + clipSuffix, "CCTV-AUGCB", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/AUGCB-" + clipSuffix + ".mp4",
                LocalDateTime.now(), 30));
        // R8 — 부모는 비식별 완료 영상. createAugmentedVideo 가 콜백 처리 시점에 부모 DE_IDNTF_YN='Y' 를
        // 재확인하므로(PII 노출 차단), 정상 파생 시드는 부모를 비식별 완료로 둔다.
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        // Phase 11 — 재추출은 videoFrameNo(디코더 프레임 번호) 기준이므로 부모 프레임에 실제 프레임 번호를 부여.
        LsDataSrc frame0 = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, 0L, parent.getRawSn() + "/f0.jpg", null));
        LsDataSrc frame1 = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 1, 30L, parent.getRawSn() + "/f1.jpg", null));
        LsDataLbl label = lblRepository.save(LsDataLbl.createAutoBbox(
                frame0.getSrcSn(), null, "person", "[10,20,30,40]",
                BigDecimal.valueOf(0.9), null));
        LsDataMeta meta = metaRepository.save(
                LsDataMeta.create(parent.getRawSn(), "weather", "sunny"));

        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame0.getSrcSn(), augType, "1", idempotencyKey, externalJobId));

        // 콜백 사전조건 — allowlist 등록 (요청측 AFTER_COMMIT 브리지 동등)
        ledger.recordIssued(idempotencyKey, LsWebhookIdempotency.CHANNEL_AUGMENT, externalJobId);
        return new Seed(parent, frame0, frame1, label, meta, aug);
    }

    /** AugmentResultRequest 를 JSON 직렬화한 본문 바이트(서명 대상=전송본문 동일). */
    private byte[] bodyBytes(AugmentResultRequest payload) throws Exception {
        return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    }

    /** HmacSigner 규칙(hex(secret, ts + "." + body))으로 서명 헤더 값 생성. */
    private String sign(String timestamp, byte[] body) {
        String canonical = timestamp + "." + new String(body, StandardCharsets.UTF_8);
        return HmacWebhookFilter.SIGNATURE_PREFIX + HmacSigner.hex(HMAC_KEY_VALUE, canonical);
    }

    private long countByParent(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .count();
    }

    private LsDataRaw findChildOf(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .findFirst()
                .orElse(null);
    }

    // ─── 테스트 ─────────────────────────────────────────────

    @Test
    @DisplayName("서명된_콜백수신시_신규영상이_ORGNL_RAW_SN원본_COMPLETED로_생성된다")
    void signedCallbackCreatesCompletedChildVideo() throws Exception {
        Seed s = seedOriginWithAug("NEW", "WINTER", "AUGCB-K-NEW", "AUGCB-J-NEW");
        AugmentResultRequest payload = new AugmentResultRequest(
                s.aug().getDataAugSn(), "AUGCB-J-NEW", "WINTER", "SUCCESS",
                "/storage/augment/AUGCB-NEW.mp4");
        byte[] body = bodyBytes(payload);
        String ts = Long.toString(System.currentTimeMillis());

        mockMvc.perform(post(CALLBACK_PATH)
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, sign(ts, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.applied").value(true));

        // Phase 11 — 동기 커밋 직후엔 PENDING·deIdntfYn='N'. async 재추출 성공 후에만 COMPLETED(배치 마감)+Y 확정.
        LsDataRaw child = awaitFinalizedChild(s.parentRaw().getRawSn());
        assertThat(child).as("신규 증강 영상이 생성되어야 함").isNotNull();
        assertThat(child.getOrgnlRawSn()).isEqualTo(s.parentRaw().getRawSn());
        assertThat(child.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        assertThat(child.getDeIdntfYn()).isEqualTo("Y");
        assertThat(child.getRawFilePathNm()).isEqualTo("/storage/augment/AUGCB-NEW.mp4");

        // HIGH-1 — 마킹 스트림이 가능하도록 SUCCESS procLog 가 async 성공 커밋에 남아야 한다.
        // resolveDeidPath 가 null 이면(procLog 부재) 스트리밍이 NOT_FOUND 로 거부돼 마킹 불가.
        assertThat(videoStreamService.resolveDeidPath(child.getRawSn()))
                .as("증강본 마킹 스트림을 위한 비식별 결과 경로가 도출되어야 함")
                .isEqualTo("/storage/augment/AUGCB-NEW.mp4");
    }

    @Test
    @DisplayName("신규영상에_원본_프레임과_라벨이_좌표그대로_복사된다")
    void framesAndLabelsCopiedWithCoordinates() throws Exception {
        Seed s = seedOriginWithAug("COPY", "NIGHT", "AUGCB-K-COPY", "AUGCB-J-COPY");
        AugmentResultRequest payload = new AugmentResultRequest(
                s.aug().getDataAugSn(), "AUGCB-J-COPY", "NIGHT", "SUCCESS",
                "/storage/augment/AUGCB-COPY.mp4");
        byte[] body = bodyBytes(payload);
        String ts = Long.toString(System.currentTimeMillis());

        mockMvc.perform(post(CALLBACK_PATH)
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, sign(ts, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Phase 11 — async 재추출 완료 대기 후 신규 영상의 프레임/라벨을 관측.
        LsDataRaw child = awaitFinalizedChild(s.parentRaw().getRawSn());
        assertThat(child).isNotNull();

        // 프레임 재추출 — 원본 2건(videoFrameNo 0,30) → 증강 파일에서 신규 2건
        List<LsDataSrc> childFrames = srcRepository.findByRawSnOrderByFrameNoAsc(child.getRawSn());
        assertThat(childFrames).hasSize(2);
        assertThat(childFrames).extracting(LsDataSrc::getFrameNo)
                .containsExactly(0L, 1L);
        // videoFrameNo 는 부모와 동일하게 실려야 재매핑 정합(0, 30).
        assertThat(childFrames).extracting(LsDataSrc::getVideoFrameNo)
                .containsExactly(0L, 30L);

        // 라벨 복사 — 원본 frame0(videoFrameNo 0)의 라벨 1건이 신규 frame0 으로 좌표 그대로 복사
        LsDataSrc childFrame0 = childFrames.get(0);
        List<LsDataLbl> childLabels = lblRepository.findBySrcSn(childFrame0.getSrcSn());
        assertThat(childLabels).hasSize(1);
        LsDataLbl copied = childLabels.get(0);
        assertThat(copied.getPointCn()).isEqualTo(s.label().getPointCn());
        assertThat(copied.getLabelNm()).isEqualTo(s.label().getLabelNm());
        // 복사본은 신규 lblSn 을 받음 (원본과 다름)
        assertThat(copied.getLblSn()).isNotEqualTo(s.label().getLblSn());
    }

    @Test
    @DisplayName("생성된_증강영상은_영상처리현황_조회에서_제외된다_R1_파생제외")
    void childVideoExcludedFromProcessingList() throws Exception {
        Seed s = seedOriginWithAug("LIST", "RAIN", "AUGCB-K-LIST", "AUGCB-J-LIST");
        AugmentResultRequest payload = new AugmentResultRequest(
                s.aug().getDataAugSn(), "AUGCB-J-LIST", "RAIN", "SUCCESS",
                "/storage/augment/AUGCB-LIST.mp4");
        byte[] body = bodyBytes(payload);
        String ts = Long.toString(System.currentTimeMillis());

        mockMvc.perform(post(CALLBACK_PATH)
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, sign(ts, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Phase 11 — async 재추출 완료 후 COMPLETED(배치 마감) 로 확정됨(파생영상 자체는 정상 생성).
        LsDataRaw child = awaitFinalizedChild(s.parentRaw().getRawSn());
        assertThat(child).isNotNull();
        assertThat(child.getOrgnlRawSn()).isEqualTo(s.parentRaw().getRawSn());

        // R1 — 영상 처리 현황(GET /v1/videos)은 파생 RAW(ORGNL_RAW_SN NOT NULL)를 제외한다.
        // 파생 영상은 증강 이력/작업 목록에서만 노출되며 처리 현황 목록에는 나타나지 않는다.
        mockMvc.perform(get("/v1/videos?dataSttsCd=COMPLETED&page=0&size=100")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[?(@.id == " + child.getRawSn() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("증강_finalize_실DB에서_부모메타_전건복사_VLM검수행만_PENDING신규_이면서_파생RAW는_확정유지된다(순서계약_HIGH4)")
    void finalizeCopiesParentMetaAndCreatesPendingReviewWhileStayingFinalized() throws Exception {
        Seed s = seedOriginWithAug("METAORDER", "WINTER", "AUGCB-K-META", "AUGCB-J-META");
        Long parentRawSn = s.parentRaw().getRawSn();

        // 부모 메타 보강: VLM 세그(검수행 APPROVED 보유) + video.* 기술메타(검수행 없음). 시드의 weather 는 검수행 없음.
        LsDataMeta vlm = metaRepository.save(LsDataMeta.create(parentRawSn, "vlm.seg.0", "a person walking"));
        metaRepository.save(LsDataMeta.create(parentRawSn, "video.fps", "30"));
        metaReviewRepository.save(LsDataMetaReview.createAuto(
                vlm.getMetaSn(), parentRawSn, null,
                LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                LsDataMetaReview.STTS_APPROVED));

        AugmentResultRequest payload = new AugmentResultRequest(
                s.aug().getDataAugSn(), "AUGCB-J-META", "WINTER", "SUCCESS",
                "/storage/augment/AUGCB-META.mp4");
        byte[] body = bodyBytes(payload);
        String ts = Long.toString(System.currentTimeMillis());
        mockMvc.perform(post(CALLBACK_PATH)
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, sign(ts, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // ① 파생 RAW 가 확정 유지 — COMPLETED + deIdntfYn='Y'. 메타 복사가 확정블록보다 앞서면(순서 위반)
        //    배치 upsert 의 clear 로 이 dirty 확정이 유실돼 여기서 깨진다(HIGH#4 회귀 가드).
        LsDataRaw child = awaitFinalizedChild(parentRawSn);
        assertThat(child.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        assertThat(child.getDeIdntfYn()).isEqualTo("Y");

        // ② 부모 메타가 파생 RAW 로 전건 복사(video.* + weather 포함).
        List<LsDataMeta> childMetas = metaRepository.findByRawSn(child.getRawSn());
        assertThat(childMetas).extracting(LsDataMeta::getMetaKey)
                .contains("vlm.seg.0", "video.fps", "weather");

        // ③ 부모 검수행이 있던 VLM 메타에만 미검수(PENDING) 검수행 신규 생성.
        List<LsDataMetaReview> childReviews = metaReviewRepository.findAllByDataRawSn(child.getRawSn());
        assertThat(childReviews).hasSize(1);
        Long derivedVlmMetaSn = childMetas.stream()
                .filter(m -> "vlm.seg.0".equals(m.getMetaKey())).findFirst().orElseThrow().getMetaSn();
        assertThat(childReviews.get(0).getDataMetaSn()).isEqualTo(derivedVlmMetaSn);
        assertThat(childReviews.get(0).getMetaTypeCd()).isEqualTo(LsDataMetaReview.META_TYPE_VLM);
        assertThat(childReviews.get(0).getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);
    }

    @Test
    @DisplayName("동일_idempotencyKey_콜백_재수신시_중복생성되지_않는다")
    void duplicateIdempotencyKeyDoesNotCreateDuplicate() throws Exception {
        Seed s = seedOriginWithAug("DUP", "WINTER", "AUGCB-K-DUP", "AUGCB-J-DUP");
        AugmentResultRequest payload = new AugmentResultRequest(
                s.aug().getDataAugSn(), "AUGCB-J-DUP", "WINTER", "SUCCESS",
                "/storage/augment/AUGCB-DUP.mp4");
        byte[] body = bodyBytes(payload);

        String ts1 = Long.toString(System.currentTimeMillis());
        mockMvc.perform(post(CALLBACK_PATH)
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts1)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, sign(ts1, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        assertThat(countByParent(s.parentRaw().getRawSn())).isEqualTo(1L);

        // 동일 idempotencyKey 재수신 — 200 + 멱등 스킵, 신규 영상 중복 없음
        String ts2 = Long.toString(System.currentTimeMillis());
        mockMvc.perform(post(CALLBACK_PATH)
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts2)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER, sign(ts2, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));

        assertThat(countByParent(s.parentRaw().getRawSn()))
                .as("멱등 재수신 시 신규 영상은 중복 생성되지 않아야 함")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("잘못된_서명_콜백은_401로_거부된다")
    void invalidSignatureRejectedWith401() throws Exception {
        Seed s = seedOriginWithAug("BADSIG", "NIGHT", "AUGCB-K-BAD", "AUGCB-J-BAD");
        AugmentResultRequest payload = new AugmentResultRequest(
                s.aug().getDataAugSn(), "AUGCB-J-BAD", "NIGHT", "SUCCESS", null);
        byte[] body = bodyBytes(payload);
        String ts = Long.toString(System.currentTimeMillis());

        mockMvc.perform(post(CALLBACK_PATH)
                        .header(HmacWebhookFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacWebhookFilter.SIGNATURE_HEADER,
                                HmacWebhookFilter.SIGNATURE_PREFIX + "deadbeefnotmatching")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        // 거부된 콜백은 신규 영상을 만들지 않아야 함
        assertThat(countByParent(s.parentRaw().getRawSn())).isZero();
    }

    @Test
    @DisplayName("CALLBACK_PATH가_필터_요청측_시뮬_3곳에서_동일하다")
    void callbackPathSingleSourceOfTruth() {
        // 필터 PATH_AUGMENT 단일 출처. 요청측/시뮬은 이 값을 참조하거나 동일 리터럴이어야 한다.
        assertThat(HmacWebhookFilter.PATH_AUGMENT).isEqualTo("/v1/aug/callback");
        assertThat(kr.co.cudo.authoring.augment.service.AugmentRequestService.CALLBACK_PATH)
                .isEqualTo(HmacWebhookFilter.PATH_AUGMENT);
        assertThat(kr.co.cudo.authoring.augment.dev.DevAugmentCallbackSimulator.CALLBACK_PATH)
                .isEqualTo(HmacWebhookFilter.PATH_AUGMENT);
    }
}
