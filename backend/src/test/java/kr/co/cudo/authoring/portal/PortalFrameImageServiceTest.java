package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.when;

/**
 * R16 — 포털 프레임 이미지 서빙 (PortalLabelService.serveFrameImage).
 *
 * <p>정책:
 *  - PORTAL_USER 전용 (채널/역할 가드는 SecurityConfig).
 *  - 데이터마트 노출(검수 완료, DATA_STTS_CD='APPROVED') 영상 프레임만 서빙.
 *  - 미승인 영상 프레임은 403 (FORBIDDEN).
 *  - 프레임 미존재 404, Path Traversal/경로부재 가드(CWE-22) 재사용.
 *  - 비식별 경로 우선 (deid 우선, 없으면 원본 폴백 금지 — 포털은 데이터마트 비식별본만).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PortalFrameImageServiceTest {

    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsPortalUserLabelRepository userLabelRepository;
    @Mock LsRawDataStatusRepository rawDataStatusRepository;

    private PortalLabelService service;

    @TempDir Path tempDir;

    private final TokenClaims alice = new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL,
            Instant.now().plusSeconds(3600));

    @BeforeEach
    void setUp() {
        // DEV_FIX-A(S7) — 비식별 신고 게이트(LabelAccessGuard)는 배선 검증용 IT
        //   (DeidentReportGateCoverageIT) 에서 실 경로로 확인한다. 여기서는 no-op mock 으로 기존 검증에 집중.
        var deidentGate = org.mockito.Mockito.mock(
                kr.co.cudo.authoring.label.service.LabelAccessGuard.class);
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository,
                rawDataStatusRepository, null, deidentGate,
                new kr.co.cudo.authoring.portal.service.PortalRetentionPolicy(
                        org.mockito.Mockito.mock(kr.co.cudo.authoring.sysconfig.service.SystemConfigService.class)),
                new com.fasterxml.jackson.databind.ObjectMapper(), null,
                org.mockito.Mockito.mock(kr.co.cudo.authoring.portal.repository.PortalUserWorkRepository.class),
                new kr.co.cudo.authoring.portal.service.PortalWorkableVideoPolicy(rawDataStatusRepository, org.mockito.Mockito.mock(kr.co.cudo.authoring.video.repository.VideoRepository.class)));
        ReflectionTestUtils.setField(service, "storageRawPath", tempDir.toString());
        // R17 이슈1 — 비식별 프레임 base 경로는 deidentified-path. 기본은 raw 와 동일 tempDir
        // (개별 테스트에서 deidentified 전용 디렉터리로 override).
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", tempDir.toString());
    }

    private LsDataSrc src(Long srcSn, Long rawSn, String filePath, String deidPath) {
        LsDataSrc s = LsDataSrc.create(rawSn, 0, filePath, LocalDateTime.now());
        setField(s, "srcSn", srcSn);
        setField(s, "rawSn", rawSn);
        if (deidPath != null) s.attachDeidPath(deidPath);
        return s;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignore) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private void approve(Long rawSn) {
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        setField(st, "dataSttsCd", LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findAllById(java.util.List.of(rawSn))).thenReturn(java.util.List.of(st));
    }

    /**
     * 비식별 프레임을 <b>규약 서브트리</b>({@code frames/deid/{rawSn}/…})에 만든다.
     *
     * <p>구 픽스처는 base 바로 아래에 파일을 뒀는데, 그건 실제 산출 규약
     * ({@code DeidentFrameAttacher}/{@code FfmpegFrameExtractor})과 다르고
     * 단일 판정기({@code StorageSubtreePolicy.verifyDeidentifiedFile})가 요구하는 위치도 아니다.
     * 기대값을 낮추는 게 아니라 <b>픽스처를 실제 산출 규약에 맞춘다</b>.
     */
    private Path createDeidFrame(Path deidBase, long rawSn, String fileName) throws Exception {
        Path dir = deidBase.resolve(StorageSubtreePolicy.deidFramesDir(rawSn));
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.write(file, new byte[]{1, 2, 3});
        return file;
    }

    @Test
    @DisplayName("포털_프레임_이미지_검수완료_영상_비식별경로_200")
    void serveFrameImage_approvedVideo_returnsDeidImage() throws Exception {
        String rel = StorageSubtreePolicy.deidFramesDir(100L) + "/frame0_deid.jpg";
        createDeidFrame(tempDir, 100L, "frame0_deid.jpg");
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", rel);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        ResponseEntity<Resource> resp = service.serveFrameImage(10L, alice);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(3);
    }

    @Test
    @DisplayName("R17_포털_프레임_이미지_deidentified경로_절대경로_파일_200_403회귀차단")
    void serveFrameImage_absoluteDeidPath_underDeidentifiedDir_returns200() throws Exception {
        // given: deid 프레임은 deidentified-path(raw-path 와 다른 디렉터리) 기준 절대경로로 저장됨.
        //  - storageRawPath 는 그대로 tempDir, storageDeidentifiedPath 는 별도 deidDir 로 분리.
        //  - 구버전은 baseDir 를 raw-path 로 잡아 startsWith 검증 실패 → 전 프레임 403 회귀.
        Path deidDir = Files.createDirectory(tempDir.resolve("deidentified"));
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", deidDir.toString());
        Path deidFile = createDeidFrame(deidDir, 100L, "frame0_deid.jpg");
        // FfmpegFrameExtractor 와 동일하게 절대경로를 deidFilePath 로 저장
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", deidFile.toAbsolutePath().toString());
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        // when
        ResponseEntity<Resource> resp = service.serveFrameImage(10L, alice);

        // then: 비식별 디렉터리 base 로 검증 통과 → 200 (403 회귀 차단)
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    // ---------- 비식별 서브트리 강제 · 심링크 우회 차단 (CWE-59/367/22/359) ----------

    @Test
    @DisplayName("포털_프레임_이미지_비식별_서브트리_밖_원본프레임_경로면_403")
    void serveFrameImage_rawFrameSubtree_forbidden() throws Exception {
        // given: 운영 형상은 raw base == deid base(/nas-storage) 라, DB 의 deid 컬럼에
        //  frames/raw/** 경로가 들어 있으면 lexical startsWith 검사만으로는 <b>그대로 통과</b>한다.
        //  (마스킹 전 원본 프레임이 외부 채널로 200 서빙되던 fail-open)
        Path rawDir = tempDir.resolve("frames/raw/100");
        Files.createDirectories(rawDir);
        Path rawFrame = rawDir.resolve("frame0.jpg");
        Files.write(rawFrame, new byte[]{9, 9, 9});
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", "frames/raw/100/frame0.jpg");
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        // when / then: 비식별 전용 서브트리(frames/deid·videos) 밖이므로 거부 — 기존 계약(403) 유지
        assertThatThrownBy(() -> service.serveFrameImage(10L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("포털_프레임_이미지_비식별경로가_원본프레임을_가리키는_심링크면_403")
    void serveFrameImage_symlinkToRawFrame_forbidden() throws Exception {
        // given: frames/deid/{rawSn}/f.jpg → ../../raw/{rawSn}/f.jpg 심링크.
        //  두 base 가 같은 디렉터리인 운영에서는 ①lexical 서브트리 ②startsWith(base) 를 모두 통과하므로
        //  <b>실경로(toRealPath) 기준</b> 판정이 없으면 원본 픽셀이 "비식별본"으로 나간다(CWE-59/359).
        Path rawDir = tempDir.resolve("frames/raw/100");
        Files.createDirectories(rawDir);
        Path rawFrame = rawDir.resolve("frame0.jpg");
        Files.write(rawFrame, new byte[]{9, 9, 9});

        Path deidDir = tempDir.resolve(StorageSubtreePolicy.deidFramesDir(100L));
        Files.createDirectories(deidDir);
        Path link = deidDir.resolve("frame0.jpg");
        try {
            Files.createSymbolicLink(link, rawFrame);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeThat(false).as("심링크 미지원 파일시스템 — 이 가드는 검증 대상 외").isTrue();
            return;
        }

        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg",
                StorageSubtreePolicy.deidFramesDir(100L) + "/frame0.jpg");
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        // when / then: 실경로가 frames/raw/** 라 거부(403). 통과하면 원본 PII 픽셀이 외부로 나간다.
        assertThatThrownBy(() -> service.serveFrameImage(10L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("포털_프레임_이미지_base_밖_traversal_경로면_403")
    void serveFrameImage_traversalOutsideBase_forbidden() {
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", "../../etc/passwd");
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        assertThatThrownBy(() -> service.serveFrameImage(10L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("포털_프레임_이미지_경로는_있으나_파일이_없으면_404_계약유지")
    void serveFrameImage_fileMissing_notFound() {
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg",
                StorageSubtreePolicy.deidFramesDir(100L) + "/absent.jpg");
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        assertThatThrownBy(() -> service.serveFrameImage(10L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("포털_프레임_이미지_미승인_영상_403")
    void serveFrameImage_notApproved_forbidden() {
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", "frame0_deid.jpg");
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        when(rawDataStatusRepository.findAllById(java.util.List.of(100L))).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.serveFrameImage(10L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("포털_프레임_이미지_프레임_없으면_404")
    void serveFrameImage_frameMissing_notFound() {
        when(srcRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.serveFrameImage(999L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("포털_프레임_이미지_비식별경로_없으면_404_원본노출금지")
    void serveFrameImage_noDeidPath_notFound() {
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", null);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        assertThatThrownBy(() -> service.serveFrameImage(10L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("NOT_FOUND");
    }
}
