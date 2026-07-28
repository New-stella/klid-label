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
import kr.co.cudo.authoring.common.security.HmacSigner;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.webhook.dto.GenAiCallbackRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 증강 콜백 충실 플로우 — Phase 3 통합 검증.
 *
 * <p>요청 측(LS_DATA_AUG PENDING + LS_DATA_AUG_JOB 선기록) 사전조건을 갖춘 뒤,
 * 무서명 생성형 AI 웹훅(SUCCEEDED) 을 실제 콜백 엔드포인트
 * {@code POST /v1/genai/callback}(context-path /api 정합) 로 전송해 필터 → 컨트롤러 → handle
 * 경로 전체가 신규 영상을 생성하는지 단언한다.
 *
 * <h3>Phase 11 / 7-D — 프레임 확정 비동기 + 외부 산출물 반입</h3>
 * <p>동기 콜백 커밋 직후 신규 RAW 는 PENDING·deIdntfYn='N' 이며, 프레임/라벨/COMPLETED(배치 마감)/procLog
 * 는 async 러너({@link kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner}) 성공 후에만
 * 관측된다({@link Awaitility} 로 대기). 프레임 픽셀은 부모 영상 재추출이 아니라 <b>외부 생성형 AI 가
 * 반환한 산출 이미지</b>를 반입한 것이므로(Phase 7-D), 픽스처는 실제 이미지 파일을 임시 저장소에 만들고
 * 반입 결과 바이트가 외부 산출물과 동일한지까지 단언한다(ffmpeg 의존 없음).
 *
 * <h3>검증 (HIGH 폐쇄)</h3>
 * <ul>
 *   <li>신규 LsDataRaw 생성: ORGNL_RAW_SN=원본 rawSn</li>
 *   <li>async 성공 후 신규 영상 COMPLETED(배치 마감) + DE_IDNTF_YN='Y' + SUCCESS procLog</li>
 *   <li>증강 파일에서 재추출된 프레임에 원본 라벨이 좌표 그대로 복사(건수·pointCn 일치)</li>
 *   <li>파생 영상은 처리 현황(GET /v1/videos)에서 제외됨(R1) — 증강 이력/작업 목록에서만 노출</li>
 *   <li>멱등: 동일 idempotencyKey 재수신 → 200 + 신규 영상 중복 생성 없음</li>
 *   <li>잘못된 서명 → 401</li>
 *   <li>CALLBACK_PATH 단일 출처(WebhookProtectedPaths.PATH_GENAI_CALLBACK) 회귀 가드</li>
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
        "webhook.hmac.secret.augment=augment-it-secret-32bytes-min-len-aa!!"
})
class AugmentCallbackFlowIntegrationTest {

    /**
     * 실파일 기반 검증용 임시 저장소 — Phase 7-D 는 외부 산출 <b>이미지 파일</b>을 실제로 반입하므로
     * (해상도 실측 포함) 픽스처도 실파일이어야 한다. 부모 비식별 프레임·외부 산출물을 여기 만든다.
     */
    private static final Path TMP_ROOT = createTempRoot();
    private static final Path DEID_BASE = TMP_ROOT.resolve("deid");
    private static final Path EXT_BASE = TMP_ROOT.resolve("genai");

    /** 픽스처 프레임 해상도 — 외부 산출물도 동일해야 라벨 좌표 그대로 복사가 성립한다. */
    private static final int FRAME_W = 64;
    private static final int FRAME_H = 48;

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("augcb-store");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        // 파생(비식별 계열) 산출물 base — 증강 프레임은 frames/deid/{rawSn} 아래로 반입된다.
        registry.add("authoring.storage.deidentified-path", DEID_BASE::toString);
        // B-2 — 외부가 준 경로는 고정 allowlist(마운트 루트) 하위여야 한다. 픽스처 영상 경로(/storage/...)와
        //   실제 산출 파일이 있는 임시 루트를 함께 허용한다.
        registry.add("authoring.storage.raw-mount-roots", () -> "/storage," + TMP_ROOT);
    }

    /** 콜백 경로 — WebhookProtectedPaths 단일 출처 정합(무서명 생성형 AI 웹훅). */
    private static final String CALLBACK_PATH =
            kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths.PATH_GENAI_CALLBACK;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private LsDataMetaReviewRepository metaReviewRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository augJobRepository;
    @Autowired private LsDataAugJobFileRepository augJobFileRepository;
    @Autowired private kr.co.cudo.authoring.video.service.VideoStreamService videoStreamService;

    @Value("${authoring.jwt.secret}") private String jwtSecret;
    @Value("${authoring.jwt.issuer}") private String jwtIssuer;

    private String reviewerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(jwtSecret, "1", "REVIEWER", "INTERNAL", jwtIssuer, 60);
    }

    /** 지정 해상도의 실제 이미지 파일을 만든다(ImageIO 판독 가능). */
    private static Path writeImage(Path dst, Color color) {
        try {
            Files.createDirectories(dst.getParent());
            BufferedImage img = new BufferedImage(FRAME_W, FRAME_H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(color);
                g.fillRect(0, 0, FRAME_W, FRAME_H);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "jpg", dst.toFile());
            return dst;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
     * 콜백 사전조건(발급 게이트)은 {@code LS_DATA_AUG_JOB.IDMP_KEY} 선기록으로 갖춘다.
     */
    private Seed seedOriginWithAug(String clipSuffix, String augType,
                                   String idempotencyKey, String externalJobId) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGCB-" + clipSuffix, "CCTV-AUGCB", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/AUGCB-" + clipSuffix + ".mp4",
                LocalDateTime.now(), 30));
        // R8 — 부모는 비식별 완료 영상. createAugmentedVideo 가 콜백 처리 시점에 부모 DE_IDNTF_YN='Y' 를
        // 재확인하므로(PII 노출 차단), 정상 파생 시드는 부모를 비식별 완료로 둔다.
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        // Phase 11 — 라벨 재매핑은 videoFrameNo(디코더 프레임 번호) 기준이므로 부모 프레임에 실제 번호를 부여.
        // Phase 7-D — 외부에 위탁했던 입력이자 해상도 기준인 <비식별 프레임>을 실파일로 만든다.
        Path deid0 = writeImage(
                DEID_BASE.resolve("frames/deid/" + parent.getRawSn() + "/frame-0.jpg"), Color.GRAY);
        Path deid1 = writeImage(
                DEID_BASE.resolve("frames/deid/" + parent.getRawSn() + "/frame-1.jpg"), Color.GRAY);
        LsDataSrc frame0 = srcRepository.save(LsDataSrc.create(
                parent.getRawSn(), 0, 0L, parent.getRawSn() + "/f0.jpg", deid0.toString(), null));
        LsDataSrc frame1 = srcRepository.save(LsDataSrc.create(
                parent.getRawSn(), 1, 30L, parent.getRawSn() + "/f1.jpg", deid1.toString(), null));
        LsDataLbl label = lblRepository.save(LsDataLbl.createAutoBbox(
                frame0.getSrcSn(), null, "person", "[10,20,30,40]",
                BigDecimal.valueOf(0.9), null));
        LsDataMeta meta = metaRepository.save(
                LsDataMeta.create(parent.getRawSn(), "weather", "sunny"));

        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame0.getSrcSn(), augType, "1", idempotencyKey, externalJobId));

        // 콜백 사전조건 — 위탁 job 선기록(요청측 AugmentJobSubmitService 동등).
        //   새 계약의 request_id 발급 게이트는 LS_DATA_AUG_JOB.IDMP_KEY 다.
        LsDataAugJob job = LsDataAugJob.createIssued(aug.getDataAugSn(), 1, idempotencyKey, 2);
        job.markAccepted(externalJobId);
        job = augJobRepository.save(job);
        // Phase 7-D — 위탁 시점의 순서↔프레임 대응(결과 경로는 콜백이 채운다).
        augJobFileRepository.save(LsDataAugJobFile.issued(job.getAugJobSn(), 1, frame0.getSrcSn()));
        augJobFileRepository.save(LsDataAugJobFile.issued(job.getAugJobSn(), 2, frame1.getSrcSn()));
        // 외부가 산출한 증강 이미지(부모와 다른 픽셀, 동일 해상도) 실파일.
        writeImage(EXT_BASE.resolve(externalJobId + "/001_gen.jpg"), Color.BLUE);
        writeImage(EXT_BASE.resolve(externalJobId + "/002_gen.jpg"), Color.BLUE);
        // 구 LS_WEBHOOK_IDEMPOTENCY(AUGMENT) 선기록은 하지 않는다 — 발급 게이트가 아니며(수신부는
        // LS_DATA_AUG_JOB.IDMP_KEY 를 본다) 요청측 write 도 제거됐다(DEV_FIX MEDIUM).
        return new Seed(parent, frame0, frame1, label, meta, aug);
    }

    /** 웹훅 페이로드를 JSON 직렬화한 본문 바이트. */
    private byte[] bodyBytes(GenAiCallbackRequest payload) throws Exception {
        return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    }

    /** SUCCEEDED 웹훅 1건 — 산출물 2건(위탁 건수·부모 프레임 수와 동일). */
    private GenAiCallbackRequest succeeded(String requestId, String jobId) {
        return new GenAiCallbackRequest(requestId, jobId, "SUCCEEDED", 100, "COMPLETED",
                "2026-07-27T10:00:00Z",
                List.of(new GenAiCallbackRequest.ResultItem(
                                "gen-" + requestId + "-0", "IMAGE",
                                EXT_BASE.resolve(jobId + "/001_gen.jpg").toString(), null),
                        new GenAiCallbackRequest.ResultItem(
                                "gen-" + requestId + "-1", "IMAGE",
                                EXT_BASE.resolve(jobId + "/002_gen.jpg").toString(), null)),
                null, null);
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
    @DisplayName("콜백수신시_신규영상이_ORGNL_RAW_SN원본_COMPLETED로_생성된다")
    void callbackCreatesCompletedChildVideo() throws Exception {
        Seed s = seedOriginWithAug("NEW", "WINTER", "AUGCB-K-NEW", "AUGCB-J-NEW");
        GenAiCallbackRequest payload = succeeded("AUGCB-K-NEW", "AUGCB-J-NEW");
        byte[] body = bodyBytes(payload);

        mockMvc.perform(post(CALLBACK_PATH)
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
        // 생성형 AI 는 이미지-to-이미지라 영상 파일을 돌려주지 않는다 → 부모(비식별) 영상 경로로 폴백한다.
        assertThat(child.getRawFilePathNm()).isEqualTo(s.parentRaw().getRawFilePathNm());

        // HIGH-1 — 마킹 스트림이 가능하도록 SUCCESS procLog 가 async 성공 커밋에 남아야 한다.
        // resolveDeidPath 가 null 이면(procLog 부재) 스트리밍이 NOT_FOUND 로 거부돼 마킹 불가.
        assertThat(videoStreamService.resolveDeidPath(child.getRawSn()))
                .as("증강본 마킹 스트림을 위한 비식별 결과 경로가 도출되어야 함")
                .isEqualTo(s.parentRaw().getRawFilePathNm());
    }

    @Test
    @DisplayName("신규영상에_원본_프레임과_라벨이_좌표그대로_복사된다")
    void framesAndLabelsCopiedWithCoordinates() throws Exception {
        Seed s = seedOriginWithAug("COPY", "NIGHT", "AUGCB-K-COPY", "AUGCB-J-COPY");
        GenAiCallbackRequest payload = succeeded("AUGCB-K-COPY", "AUGCB-J-COPY");
        byte[] body = bodyBytes(payload);

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Phase 11 — async 재추출 완료 대기 후 신규 영상의 프레임/라벨을 관측.
        LsDataRaw child = awaitFinalizedChild(s.parentRaw().getRawSn());
        assertThat(child).isNotNull();

        // 프레임 반입 — 원본 2건(videoFrameNo 0,30) → 외부 산출물 2건이 파생 프레임으로 신규 2건
        List<LsDataSrc> childFrames = srcRepository.findByRawSnOrderByFrameNoAsc(child.getRawSn());
        assertThat(childFrames).hasSize(2);
        // ★ Phase 7-D — 파생 프레임의 바이트가 <외부 산출물>과 같아야 한다(부모 사본이면 증강 효과 0).
        for (int i = 0; i < childFrames.size(); i++) {
            LsDataSrc cf = childFrames.get(i);
            assertThat(cf.getSrcFilePathNm()).as("파생영상은 원본 픽셀이 실재하지 않는다").isNull();
            Path stored = Path.of(cf.getDeidFilePath());
            assertThat(stored.toString().replace('\\', '/'))
                    .contains("/frames/deid/" + child.getRawSn() + "/");
            Path external = EXT_BASE.resolve("AUGCB-J-COPY/00" + (i + 1) + "_gen.jpg");
            assertThat(Files.readAllBytes(stored))
                    .as("프레임 %d 는 외부 산출본이어야 한다", i)
                    .isEqualTo(Files.readAllBytes(external));
            assertThat(Files.readAllBytes(stored))
                    .isNotEqualTo(Files.readAllBytes(
                            Path.of(i == 0 ? s.frame0().getDeidFilePath() : s.frame1().getDeidFilePath())));
        }
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

    /**
     * Phase 7 검증 갭 폐쇄 — <b>영속 → 계획 → 반입</b> 전 구간 관통 고정.
     *
     * <p>기존 테스트들은 구간을 나눠 검증했다: 콜백이 {@code output_file_path} 를
     * {@code LS_DATA_AUG_JOB_FILE} 에 적재하는지(단위), 산출물이 프레임으로 반입되는지(위 IT).
     * <b>중간 연결고리</b> — {@code AugmentExtractSnapshot} 이 그 <b>영속된 값</b>을 읽어 프레임 스펙을
     * 만드는지 — 는 어느 테스트도 직접 고정하지 않았다. 그래서 여기서는 픽스처 상수가 아니라
     * <b>DB 에 적재된 경로를 읽어</b> 그 바이트가 파생 프레임과 동일한지 단언한다. 스냅샷이 영속 경로를
     * 무시하고 부모 재추출로 폴백하면(=연결고리 단절) 즉시 실패한다.
     */
    @Test
    @DisplayName("외부_산출_경로가_영속에서_프레임_반입까지_관통한다")
    void externalOutputPathFlowsFromPersistenceToFrameImport() throws Exception {
        Seed s = seedOriginWithAug("CHAIN", "WINTER", "AUGCB-K-CHAIN", "AUGCB-J-CHAIN");
        byte[] body = bodyBytes(succeeded("AUGCB-K-CHAIN", "AUGCB-J-CHAIN"));

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        LsDataRaw child = awaitFinalizedChild(s.parentRaw().getRawSn());
        assertThat(child).isNotNull();

        // ① 영속 — 콜백이 준 output_file_path 가 위탁 항목(JOB_SEQ→FILE_SEQ 순)에 되붙었다.
        List<LsDataAugJobFile> mappings =
                augJobFileRepository.findByDataAugSnOrderByJobAndFileSeq(s.aug().getDataAugSn());
        assertThat(mappings).hasSize(2);
        assertThat(mappings).extracting(LsDataAugJobFile::getSrcSn)
                .containsExactly(s.frame0().getSrcSn(), s.frame1().getSrcSn());
        assertThat(mappings).allSatisfy(m ->
                assertThat(m.getResultFilePathNm()).as("산출 경로가 영속돼야 한다").isNotBlank());

        // ② 계획 → ③ 반입 — 파생 프레임의 바이트가 <DB 에 적재된 그 경로>의 파일과 같아야 한다.
        //    부모 재추출 폴백이면 부모 프레임 바이트가 되어 실패한다.
        List<LsDataSrc> childFrames = srcRepository.findByRawSnOrderByFrameNoAsc(child.getRawSn());
        assertThat(childFrames).hasSize(2);
        for (int i = 0; i < childFrames.size(); i++) {
            Path persistedExternal = Path.of(mappings.get(i).getResultFilePathNm());
            Path importedFrame = Path.of(childFrames.get(i).getDeidFilePath());
            assertThat(Files.readAllBytes(importedFrame))
                    .as("프레임 %d 는 DB 에 적재된 외부 산출 경로의 바이트여야 한다(연결고리 관통)", i)
                    .isEqualTo(Files.readAllBytes(persistedExternal));
        }
        // 프레임↔산출물 대응이 위탁 매핑(srcSn) 순서 그대로여야 한다 — 재정렬 오염 금지.
        assertThat(childFrames).extracting(LsDataSrc::getVideoFrameNo).containsExactly(0L, 30L);
    }

    @Test
    @DisplayName("생성된_증강영상은_영상처리현황_조회에서_제외된다_R1_파생제외")
    void childVideoExcludedFromProcessingList() throws Exception {
        Seed s = seedOriginWithAug("LIST", "RAIN", "AUGCB-K-LIST", "AUGCB-J-LIST");
        GenAiCallbackRequest payload = succeeded("AUGCB-K-LIST", "AUGCB-J-LIST");
        byte[] body = bodyBytes(payload);

        mockMvc.perform(post(CALLBACK_PATH)
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

        GenAiCallbackRequest payload = succeeded("AUGCB-K-META", "AUGCB-J-META");
        byte[] body = bodyBytes(payload);
        mockMvc.perform(post(CALLBACK_PATH)
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
        GenAiCallbackRequest payload = succeeded("AUGCB-K-DUP", "AUGCB-J-DUP");
        byte[] body = bodyBytes(payload);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        assertThat(countByParent(s.parentRaw().getRawSn())).isEqualTo(1L);

        // 동일 idempotencyKey 재수신 — 200 + 멱등 스킵, 신규 영상 중복 없음
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));

        assertThat(countByParent(s.parentRaw().getRawSn()))
                .as("멱등 재수신 시 신규 영상은 중복 생성되지 않아야 함")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("results_개수가_위탁_건수와_다르면_증강이_실패처리되어_신규영상이_생기지_않는다")
    void resultCountMismatchFailsClosed() throws Exception {
        Seed s = seedOriginWithAug("COUNT", "WINTER", "AUGCB-K-COUNT", "AUGCB-J-COUNT");
        // 위탁은 2건인데 결과가 1건만 도착 — 순서 대응이 성립하지 않는다.
        GenAiCallbackRequest payload = new GenAiCallbackRequest(
                "AUGCB-K-COUNT", "AUGCB-J-COUNT", "SUCCEEDED", 100, "COMPLETED", "2026-07-27T10:00:00Z",
                List.of(new GenAiCallbackRequest.ResultItem("gen-only", "IMAGE",
                        EXT_BASE.resolve("AUGCB-J-COUNT/001_gen.jpg").toString(), null)),
                null, null);

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBytes(payload)))
                .andExpect(status().isOk());

        // 성공으로 둔갑하지 않는다 — 증강은 REJECTED, 파생영상 미생성.
        LsDataAug reloaded = augRepository.findById(s.aug().getDataAugSn()).orElseThrow();
        assertThat(reloaded.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(countByParent(s.parentRaw().getRawSn())).isZero();
    }

    @Test
    @DisplayName("우리가_발급하지_않은_request_id_웹훅은_401_이며_신규영상을_만들지_않는다")
    void unknownRequestIdRejectedWith401() throws Exception {
        Seed s = seedOriginWithAug("BADID", "NIGHT", "AUGCB-K-BADID", "AUGCB-J-BADID");
        // 발급 원장(LS_DATA_AUG_JOB.IDMP_KEY)에 없는 request_id — 무단 주입 시나리오.
        GenAiCallbackRequest payload = succeeded("AUGCB-K-FORGED", "AUGCB-J-BADID");

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBytes(payload)))
                .andExpect(status().isUnauthorized());

        assertThat(countByParent(s.parentRaw().getRawSn())).isZero();
    }

    @Test
    @DisplayName("CALLBACK_PATH가_요청측과_수신측에서_동일한_genai_경로다")
    void callbackPathSingleSourceOfTruth() {
        // Phase 7-A2 — 요청측 callback_url 과 수신 컨트롤러 매핑이 같은 상수를 참조한다.
        //   구 /v1/aug/callback (HMAC) 은 컨트롤러·DTO·시뮬레이터와 함께 제거됐다.
        assertThat(kr.co.cudo.authoring.augment.service.AugmentRequestService.CALLBACK_PATH)
                .isEqualTo(kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths
                        .PATH_GENAI_CALLBACK)
                .isEqualTo("/v1/genai/callback")
                .isEqualTo(CALLBACK_PATH);
    }
}
