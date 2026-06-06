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
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository,
                rawDataStatusRepository, null, new com.fasterxml.jackson.databind.ObjectMapper());
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
        when(rawDataStatusRepository.findById(rawSn)).thenReturn(java.util.Optional.of(st));
    }

    @Test
    @DisplayName("포털_프레임_이미지_검수완료_영상_비식별경로_200")
    void serveFrameImage_approvedVideo_returnsDeidImage() throws Exception {
        Path deid = Files.createFile(tempDir.resolve("frame0_deid.jpg"));
        Files.write(deid, new byte[]{1, 2, 3});
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", "frame0_deid.jpg");
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);

        ResponseEntity<Resource> resp = service.serveFrameImage(10L, alice);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    @DisplayName("R17_포털_프레임_이미지_deidentified경로_절대경로_파일_200_403회귀차단")
    void serveFrameImage_absoluteDeidPath_underDeidentifiedDir_returns200() throws Exception {
        // given: deid 프레임은 deidentified-path(raw-path 와 다른 디렉터리) 기준 절대경로로 저장됨.
        //  - storageRawPath 는 그대로 tempDir, storageDeidentifiedPath 는 별도 deidDir 로 분리.
        //  - 구버전은 baseDir 를 raw-path 로 잡아 startsWith 검증 실패 → 전 프레임 403 회귀.
        Path deidDir = Files.createDirectory(tempDir.resolve("deidentified"));
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", deidDir.toString());
        Path deidFile = Files.createFile(deidDir.resolve("frame0_deid.jpg"));
        Files.write(deidFile, new byte[]{1, 2, 3});
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

    @Test
    @DisplayName("포털_프레임_이미지_미승인_영상_403")
    void serveFrameImage_notApproved_forbidden() {
        LsDataSrc s = src(10L, 100L, "frame0_raw.jpg", "frame0_deid.jpg");
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        when(rawDataStatusRepository.findById(100L)).thenReturn(Optional.empty());

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
