package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.util.SeedImageGenerator;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 — {@code GET /v1/frames/{srcSn}/deid-image} (비식별 프레임 이미지 서빙).
 *
 * <p>해상도 파생 프레임은 원본 픽셀이 실재하지 않아 {@code SRC_FILE_PATH_NM} 이 null 이고
 * 리스케일 이미지는 {@code DE_IDNTF_SRC_FILE_PATH_NM} 에만 존재한다. 기존
 * {@code /image} 엔드포인트로는 이 프레임을 서빙할 수 없어 전용 sub-resource 를 신설한다.
 *
 * <p>HIGH 시나리오 커버리지:
 * <ul>
 *   <li>H1 IDOR(CWE-639) — 타 WORKER 403</li>
 *   <li>H2 비식별 누락 신고 구간(CWE-359) — 412. <b>부모(원본) 영상이 신고 중이면 그 해상도 파생
 *       프레임도 412</b> — 파생 프레임은 부모 비식별 프레임의 리스케일 사본이라 마스킹 실패 픽셀이
 *       그대로 남는다(적대검증 반증분). resolve 시 자동 재개방</li>
 *   <li>H3 원본 폴백 금지 — deid 경로 null 이면 raw 파일이 있어도 404</li>
 *   <li>H4 deid base 검증 — 상이 base 설정에서 정상 200 (동일 base 는 별도 컨텍스트 테스트)</li>
 *   <li>H5 심링크 우회(CWE-59/22) — 403</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FrameDeidImageControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerNotAssignedToken;
    private String portalToken;

    private Long rawSn;
    private Long srcSn;
    private Path rawBase;
    private Path deidBase;
    private Path deidFramePath;

    @BeforeEach
    void setup() throws IOException {
        reviewerToken          = JwtTestSupport.token(secret, "1",   "REVIEWER",    "INTERNAL", issuer, 60);
        workerAssignedToken    = JwtTestSupport.token(secret, "100", "WORKER",      "INTERNAL", issuer, 60);
        workerNotAssignedToken = JwtTestSupport.token(secret, "101", "WORKER",      "INTERNAL", issuer, 60);
        portalToken            = JwtTestSupport.token(secret, "200", "PORTAL_USER", "PORTAL",   issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-DEIDIMG-001", "CCTV-DEID", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        rawBase = Paths.get(storageRawPath).toAbsolutePath().normalize();
        deidBase = Paths.get(storageDeidPath).toAbsolutePath().normalize();

        // 비식별 프레임은 규약상 frames/deid/{rawSn}/ 하위에 저장된다.
        String deidRel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/frame_1.jpg";
        deidFramePath = deidBase.resolve(deidRel).normalize();
        Files.createDirectories(deidFramePath.getParent());
        SeedImageGenerator.generate(deidFramePath, "EVT_FALL", "CCTV-DEID", 1, LocalDateTime.now());

        // 해상도 파생 프레임과 동일 형상: SRC_FILE_PATH_NM = null, 비식별 경로만 존재
        LsDataSrc src = LsDataSrc.create(rawSn, 1L, 1L, null, deidRel, LocalDateTime.now());
        srcSn = srcRepository.save(src).getSrcSn();

        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private String url(Long sn) {
        return "/v1/frames/" + sn + "/deid-image";
    }

    // ---------------------------------------------------------------- 정상 경로

    @Test
    @DisplayName("DeidImage_REVIEWER_비식별프레임_200_jpeg_nosniff_응답바이트가_비식별파일과_동일")
    void reviewerCanFetchDeidImage() throws Exception {
        MvcResult result = mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        // 상태코드/헤더만 보면 "다른 파일을 서빙해도 통과"하므로 바이트 동일성까지 단언한다.
        assertThat(result.getResponse().getContentAsByteArray())
                .isEqualTo(Files.readAllBytes(deidFramePath));
    }

    @Test
    @DisplayName("DeidImage_응답은_no_store_로_캐시되지_않는다")
    void responseIsNotCached() throws Exception {
        // 신고 시 412 로 바뀌어도 브라우저 캐시가 마스킹 실패 이미지를 재노출하면 게이트가 무력화된다.
        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("DeidImage_DB에_절대경로가_저장된_프레임도_200_바이트_동일")
    void absolutePathFixtureServed() throws Exception {
        // 실제 저장 형태는 절대경로다(ResolutionPersistService.insertFrames → dst().toString()).
        String absRel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/frame_20.jpg";
        Path absFile = deidBase.resolve(absRel).normalize();
        Files.createDirectories(absFile.getParent());
        SeedImageGenerator.generate(absFile, "EVT_FALL", "CCTV-DEID", 20, LocalDateTime.now());

        LsDataSrc abs = srcRepository.save(LsDataSrc.create(
                rawSn, 20L, 20L, null, absFile.toString(), LocalDateTime.now()));

        MvcResult result = mockMvc.perform(get(url(abs.getSrcSn()))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray())
                .isEqualTo(Files.readAllBytes(absFile));
    }

    @Test
    @DisplayName("DeidImage_png_확장자면_200_image_png")
    void pngServed() throws Exception {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x21};
        Long pngSrcSn = seedDeidFrame("frame_21.png", 21L, bytes);

        MvcResult result = mockMvc.perform(get(url(pngSrcSn))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("DeidImage_webp_확장자면_200_image_webp")
    void webpServed() throws Exception {
        byte[] bytes = {'R', 'I', 'F', 'F', 0x22, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};
        Long webpSrcSn = seedDeidFrame("frame_22.webp", 22L, bytes);

        MvcResult result = mockMvc.perform(get(url(webpSrcSn))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("DeidImage_파일_열기_실패시_500이_아니라_404_내부정보_미노출")
    void unreadableFileReturns404() throws Exception {
        // given — 검증은 통과하지만(존재·정규파일·서브트리) open 단계에서 IOException 이 나는 상태.
        Files.setPosixFilePermissions(deidFramePath, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(deidFramePath), "권한 제거가 무의미한 실행 계정(root) — 검증 생략");
        try {
            MvcResult result = mockMvc.perform(get(url(srcSn))
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isNotFound())
                    .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(deidBase.toString())
                    .doesNotContain("AccessDenied");
        } finally {
            Files.setPosixFilePermissions(deidFramePath, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @DisplayName("DeidImage_원본경로가_null인_해상도파생프레임도_200")
    void derivativeFrameWithoutRawPathServed() throws Exception {
        // given — setup 의 프레임은 SRC_FILE_PATH_NM = null (파생 프레임 정책 A)
        assertThat(srcRepository.findById(srcSn).orElseThrow().getSrcFilePathNm()).isNull();

        // when/then — deid-image 는 200, 기존 image 는 404(원본 픽셀 부재)
        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/frames/" + srcSn + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DeidImage_WORKER_본인_배정_프레임_200")
    void assignedWorkerCanFetch() throws Exception {
        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    // ---------------------------------------------------------------- H1 인증/인가

    @Test
    @DisplayName("DeidImage_비인증_요청시_401")
    void unauthorizedRejected() throws Exception {
        mockMvc.perform(get(url(srcSn)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("H1_DeidImage_WORKER_타인배정_프레임이면_403_IDOR_방어")
    void notAssignedWorkerForbidden() throws Exception {
        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DeidImage_PORTAL_USER_채널은_내부_프레임_접근_403")
    void portalUserForbidden() throws Exception {
        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- H2 신고 구간

    @Test
    @DisplayName("H2_DeidImage_비식별_누락_신고_구간이면_412")
    void underDeidentReportBlocked() throws Exception {
        LsDataRaw raw = rawRepository.findById(rawSn).orElseThrow();
        raw.markDeidentified("F");
        rawRepository.save(raw);

        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    @DisplayName("H2_DeidImage_부모영상이_신고중이면_해상도파생_프레임도_412")
    void parentUnderReportBlocksDerivativeFrame() throws Exception {
        // given — 부모(P) → 파생(D, ORGNL_RAW_SN=P) 2행 + 파생 프레임(부모 비식별 프레임의 리스케일 사본)
        Long derivativeSrcSn = seedResolutionDerivativeFrame();

        // 부모만 비식별 누락 신고 — 파생행은 그대로 'Y' 다.
        LsDataRaw parent = rawRepository.findById(rawSn).orElseThrow();
        parent.markDeidentified("F");
        rawRepository.save(parent);

        // when/then — 자기 행만 보던 게이트는 200 + 이미지 바이트를 내보냈다(실제 PII 유출).
        MvcResult result = mockMvc.perform(get(url(derivativeSrcSn))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPreconditionFailed())
                .andReturn();
        assertThat(result.getResponse().getContentType()).doesNotContain("image/");
    }

    @Test
    @DisplayName("H2_DeidImage_부모영상_신고가_해제되면_파생_프레임이_다시_200")
    void derivativeFrameReopensAfterParentResolved() throws Exception {
        Long derivativeSrcSn = seedResolutionDerivativeFrame();

        LsDataRaw parent = rawRepository.findById(rawSn).orElseThrow();
        parent.markDeidentified("F");
        rawRepository.save(parent);
        mockMvc.perform(get(url(derivativeSrcSn)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPreconditionFailed());

        // when — resolve 로 'F'→'Y' 복원
        parent = rawRepository.findById(rawSn).orElseThrow();
        parent.markDeidentified("Y");
        rawRepository.save(parent);

        // then — 별도 복원 절차 없이 자동 재개방
        mockMvc.perform(get(url(derivativeSrcSn)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    @DisplayName("H2_DeidImage_신고_구간_미배정_WORKER는_412가_아니라_403")
    void deidentReportGateDoesNotBypassAuthorization() throws Exception {
        LsDataRaw raw = rawRepository.findById(rawSn).orElseThrow();
        raw.markDeidentified("F");
        rawRepository.save(raw);

        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- H3 원본 폴백 금지

    @Test
    @DisplayName("H3_DeidImage_비식별경로_null이면_원본이_있어도_404")
    void noRawFallbackWhenDeidPathMissing() throws Exception {
        // given — 원본 프레임 파일은 실재하지만 비식별 경로는 null
        String rawRel = StorageSubtreePolicy.SEG_FRAMES + "/" + StorageSubtreePolicy.SEG_RAW
                + "/" + rawSn + "/frame_7.jpg";
        Path rawFrame = rawBase.resolve(rawRel).normalize();
        Files.createDirectories(rawFrame.getParent());
        SeedImageGenerator.generate(rawFrame, "EVT_FALL", "CCTV-DEID", 7, LocalDateTime.now());

        LsDataSrc rawOnly = srcRepository.save(
                LsDataSrc.create(rawSn, 7L, rawRel, LocalDateTime.now()));

        // when/then — 404 이고 이미지 바이트가 절대 나가지 않는다
        MvcResult result = mockMvc.perform(get(url(rawOnly.getSrcSn()))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getResponse().getContentType()).doesNotContain("image/");
        assertThat(result.getResponse().getContentAsByteArray())
                .isNotEqualTo(Files.readAllBytes(rawFrame));
    }

    @Test
    @DisplayName("H3_DeidImage_원본과_비식별_파일이_모두_있으면_비식별_바이트만_나간다")
    void servesDeidBytesNeverRawBytes() throws Exception {
        // given — 같은 프레임의 원본/비식별 파일을 <b>서로 다른 내용</b>으로 만든다.
        //         내용이 같으면 "원본을 서빙해도 통과"해 폴백 금지 단언이 공허해진다.
        String rawRel = StorageSubtreePolicy.SEG_FRAMES + "/" + StorageSubtreePolicy.SEG_RAW
                + "/" + rawSn + "/frame_30.jpg";
        Path rawFrame = rawBase.resolve(rawRel).normalize();
        Files.createDirectories(rawFrame.getParent());
        SeedImageGenerator.generate(rawFrame, "EVT_VIOLENCE", "CCTV-RAW-30", 30, LocalDateTime.now());

        String deidRel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/frame_30.jpg";
        Path deidFrame = deidBase.resolve(deidRel).normalize();
        Files.createDirectories(deidFrame.getParent());
        SeedImageGenerator.generate(deidFrame, "EVT_FLOOD", "CCTV-DEID-30", 30, LocalDateTime.now());
        assertThat(Files.readAllBytes(deidFrame)).isNotEqualTo(Files.readAllBytes(rawFrame));

        LsDataSrc both = srcRepository.save(LsDataSrc.create(
                rawSn, 30L, 30L, rawRel, deidRel, LocalDateTime.now()));

        // when/then — 비식별 바이트와 정확히 일치하고, 원본 바이트와는 다르다.
        MvcResult result = mockMvc.perform(get(url(both.getSrcSn()))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(Files.readAllBytes(deidFrame));
        assertThat(body).isNotEqualTo(Files.readAllBytes(rawFrame));
    }

    @Test
    @DisplayName("H3_DeidImage_비식별경로_공백이면_404")
    void blankDeidPathReturns404() throws Exception {
        LsDataSrc blank = srcRepository.save(
                LsDataSrc.create(rawSn, 8L, 8L, null, "   ", LocalDateTime.now()));

        mockMvc.perform(get(url(blank.getSrcSn())).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- H5 심링크 / 경로순회

    @Test
    @DisplayName("H5_DeidImage_비식별경로가_원본프레임_심볼릭링크면_403")
    void symlinkToRawFrameForbidden() throws Exception {
        // given — frames/raw 아래 실제 원본 프레임 + frames/deid 아래 그를 가리키는 심링크
        Path realRaw = rawBase.resolve(StorageSubtreePolicy.SEG_FRAMES + "/"
                + StorageSubtreePolicy.SEG_RAW + "/" + rawSn + "/frame_9.jpg").normalize();
        Files.createDirectories(realRaw.getParent());
        SeedImageGenerator.generate(realRaw, "EVT_FALL", "CCTV-DEID", 9, LocalDateTime.now());

        String linkRel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/frame_9.jpg";
        Path link = deidBase.resolve(linkRel).normalize();
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, realRaw);

        LsDataSrc linked = srcRepository.save(
                LsDataSrc.create(rawSn, 9L, 9L, null, linkRel, LocalDateTime.now()));

        mockMvc.perform(get(url(linked.getSrcSn())).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("H5_DeidImage_PathTraversal_시도시_403")
    void pathTraversalBlocked() throws Exception {
        LsDataSrc evil = srcRepository.save(
                LsDataSrc.create(rawSn, 10L, 10L, null, "../../../../etc/passwd", LocalDateTime.now()));

        mockMvc.perform(get(url(evil.getSrcSn())).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DeidImage_비식별_서브트리_밖_경로면_403")
    void outsideDeidSubtreeForbidden() throws Exception {
        // deid base 안이지만 frames/deid·videos 서브트리 밖 — 격리 규약 위반
        String rel = StorageSubtreePolicy.SEG_FRAMES + "/" + StorageSubtreePolicy.SEG_RAW
                + "/" + rawSn + "/frame_11.jpg";
        Path outside = deidBase.resolve(rel).normalize();
        Files.createDirectories(outside.getParent());
        SeedImageGenerator.generate(outside, "EVT_FALL", "CCTV-DEID", 11, LocalDateTime.now());

        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(rawSn, 11L, 11L, null, rel, LocalDateTime.now()));

        mockMvc.perform(get(url(src.getSrcSn())).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- MEDIUM

    @Test
    @DisplayName("DeidImage_파일_부재시_404_내부경로_미노출")
    void missingFileReturns404() throws Exception {
        Files.deleteIfExists(deidFramePath);

        MvcResult result = mockMvc.perform(get(url(srcSn))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(deidBase.toString())
                .doesNotContain("frames/deid");
    }

    @Test
    @DisplayName("DeidImage_허용외_확장자면_403")
    void disallowedExtensionRejected() throws Exception {
        String rel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/exploit.exe";
        Path evilFile = deidBase.resolve(rel).normalize();
        Files.createDirectories(evilFile.getParent());
        Files.writeString(evilFile, "fake");

        LsDataSrc badExt = srcRepository.save(
                LsDataSrc.create(rawSn, 12L, 12L, null, rel, LocalDateTime.now()));

        mockMvc.perform(get(url(badExt.getSrcSn())).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DeidImage_미존재_프레임이면_404")
    void unknownFrameReturns404() throws Exception {
        mockMvc.perform(get(url(999_999_999L)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DeidImage_음수_srcSn이면_404")
    void negativeSrcSnReturns404() throws Exception {
        mockMvc.perform(get(url(-1L)).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 픽스처 헬퍼

    /** 비식별 서브트리에 파일 + 프레임 row 를 시드하고 srcSn 을 반환한다. */
    private Long seedDeidFrame(String fileName, long frameNo, byte[] bytes) throws IOException {
        String rel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/" + fileName;
        Path file = deidBase.resolve(rel).normalize();
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return srcRepository.save(
                LsDataSrc.create(rawSn, frameNo, frameNo, null, rel, LocalDateTime.now())).getSrcSn();
    }

    /**
     * 해상도 파생영상 2행 구조({@code 부모 → ORGNL_RAW_SN 파생})와 파생 프레임을 시드한다.
     *
     * <p>파생 프레임 이미지는 부모의 비식별 프레임을 리스케일 복사한 것이므로, 부모의 마스킹이
     * 실패하면 파생본에도 같은 PII 가 남는다 — 게이트가 조상까지 봐야 하는 이유다.
     *
     * @return 파생 프레임 SRC_SN
     */
    private Long seedResolutionDerivativeFrame() throws IOException {
        LsDataRaw parent = rawRepository.findById(rawSn).orElseThrow();
        LsDataRaw derivative = rawRepository.save(LsDataRaw.createFromResolution(
                parent, "videos/resolution/" + rawSn + "/derivative.mp4", "RESL_720P"));
        Long derivativeRawSn = derivative.getRawSn();
        assertThat(derivative.getOrgnlRawSn()).isEqualTo(rawSn);
        assertThat(derivative.getDeIdntfYn()).isNotEqualTo("F"); // 파생행 자체는 신고 상태가 아니다

        String rel = StorageSubtreePolicy.deidFramesDir(derivativeRawSn) + "/frame_1.jpg";
        Path file = deidBase.resolve(rel).normalize();
        Files.createDirectories(file.getParent());
        SeedImageGenerator.generate(file, "EVT_FALL", "CCTV-DEID", 1, LocalDateTime.now());

        return srcRepository.save(
                LsDataSrc.create(derivativeRawSn, 1L, 1L, null, rel, LocalDateTime.now())).getSrcSn();
    }
}
