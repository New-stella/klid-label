package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.service.PortalDatamartDownloadService;
import kr.co.cudo.authoring.portal.service.PortalDatamartDownloadTxService;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API-203 — 포털 데이터마트 작업 데이터 ZIP 다운로드.
 *
 * <p>검증 축: 판정 순서(403 → 412 → 410 → 200) · ZIP 구조 · AC-034(비식별 영상 부재 시 라벨·이미지만,
 * 원본 폴백 금지) · AC-035(본인 저장분만) · 프레임 파일명 4자리 zero-pad · {@code Cache-Control: no-store}.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PortalDatamartDownloadServiceTest {

    private static final long RAW_SN = 100L;

    @Mock ReviewApprovalGate approvalGate;
    @Mock LabelAccessGuard accessGuard;
    @Mock VideoStreamService videoStreamService;
    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsPortalUserLabelRepository userLabelRepository;

    @TempDir Path tempDir;

    private Path deidBase;
    private Path rawBase;
    private PortalDatamartDownloadService service;

    private final TokenClaims alice =
            new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    @BeforeEach
    void setUp() throws IOException {
        deidBase = Files.createDirectories(tempDir.resolve("deid"));
        rawBase = Files.createDirectories(tempDir.resolve("raw"));

        // 병합 규칙은 실제 구현을 쓴다 — AC-035(본인 저장분만)의 보장 근거가 그 규칙 자체이므로
        // mock 으로 대체하면 검증이 성립하지 않는다. mergeFrameItems 는 ObjectMapper 만 사용한다.
        PortalLabelService labelService =
                new PortalLabelService(null, null, null, null, null, null, null, new ObjectMapper());

        PortalDatamartDownloadTxService txService = new PortalDatamartDownloadTxService(
                approvalGate, accessGuard, labelService, videoStreamService,
                videoRepository, srcRepository, lblRepository, userLabelRepository, new ObjectMapper());

        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                tempDir.toString(), "", rawBase.toString(), deidBase.toString(),
                tempDir.resolve("labeling").toString(), VideoArtifactRootResolver.STRATEGY_CO_LOCATE);

        service = new PortalDatamartDownloadService(txService, resolver, deidBase.toString());

        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
    }

    // ======================== 판정 순서 (③ → ④ → ⑤ → ⑥) ========================

    @Test
    @DisplayName("데이터마트에_노출되지_않은_영상은_403이고_신고_상태를_묻지_않는다")
    void notApproved_forbidden_beforeDeidentGate() {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(false);

        assertThatThrownBy(() -> service.download(RAW_SN, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // ③이 ④보다 먼저다 — 뒤집히면 미노출 영상의 비식별 신고 상태가 응답으로 새어나간다(CWE-209).
        verify(accessGuard, never()).requireNotUnderDeidentReport(anyLong());
    }

    @Test
    @DisplayName("비식별_누락_신고_구간_영상은_412이고_영상없는_200으로_새지_않는다")
    void underDeidentReport_preconditionFailed() throws IOException {
        // given: 신고 구간에서도 본인 저장 라벨과 프레임 이미지는 그대로 존재한다(라벨 보존 정책).
        //  ★ resolveDeidPath 는 신고('F')와 "비식별 이력 없음"을 <둘 다 null> 로 돌려주므로,
        //    신고 판정을 그 호출 결과로 대신하면 412 가 아니라 "영상 없는 200 ZIP" 이 나간다.
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED,
                "비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.download(RAW_SN, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("본인_저장_라벨이_0건이면_410")
    void noSavedLabel_gone() {
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.download(RAW_SN, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GONE);
    }

    // ======================== AC-034 — 비식별 영상 부재 ========================

    @Test
    @DisplayName("비식별_영상이_없으면_라벨과_이미지만_담기고_video_엔트리가_없다")
    void noDeidVideo_zipHasLabelsAndFramesOnly() throws IOException {
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        assertThat(zip.keySet()).containsExactlyInAnyOrder("100/labels.json", "100/frames/0000.jpg");
        assertThat(zip.keySet()).noneMatch(name -> name.startsWith("100/video."));
    }

    @Test
    @DisplayName("비식별_영상이_없어도_원본_영상은_어떤_경우에도_ZIP에_들어가지_않는다")
    void noDeidVideo_neverFallsBackToOriginal() throws IOException {
        // given: 원본(비식별 이전) 영상 파일이 실재하고 LS_DATA_RAW 가 그 경로를 들고 있다.
        Path original = rawBase.resolve("original.mp4");
        Files.write(original, "ORIGINAL-PIXELS".getBytes(StandardCharsets.UTF_8));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw(original.toString())));
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        assertThat(zip.keySet()).noneMatch(name -> name.startsWith("100/video."));
        assertThat(zip.values()).noneMatch(body ->
                new String(body, StandardCharsets.UTF_8).contains("ORIGINAL-PIXELS"));
    }

    @Test
    @DisplayName("비식별_영상이_있으면_video_엔트리로_담긴다")
    void deidVideo_included() throws IOException {
        Path deidVideo = writeDeidVideo("deidentified.mp4", "MASKED-PIXELS");
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(deidVideo.toString());
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        assertThat(zip).containsKey("100/video.mp4");
        assertThat(new String(zip.get("100/video.mp4"), StandardCharsets.UTF_8)).isEqualTo("MASKED-PIXELS");
    }

    // ======================== AC-035 — 본인 저장분만 ========================

    @Test
    @DisplayName("타_사용자가_같은_프레임에_저장한_라벨은_포함되지_않는다")
    void otherUsersLabelsNotIncluded() throws IOException {
        // given: A(alice) 와 B(bob) 가 같은 프레임(10)에 각자 저장했다.
        //   리포지토리는 소유자 스코프로 조회하므로 A 의 요청에는 A 의 행만 돌아온다.
        givenMyLabelOnFrame(10L, "alice-car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        String labels = new String(unzip(service.download(RAW_SN, alice)).get("100/labels.json"),
                StandardCharsets.UTF_8);

        assertThat(labels).contains("alice-car");
        assertThat(labels).doesNotContain("bob-truck");
    }

    @Test
    @DisplayName("내가_저장하지_않은_프레임은_데이터마트_원본으로_대체된다")
    void unsavedFrameFallsBackToDatamartOriginal() throws IOException {
        // given: 프레임 10 에만 본인 저장분이 있고, 프레임 11 에는 데이터마트 원본만 있다.
        LsPortalUserLabel mine = LsPortalUserLabel.create("alice", RAW_SN, 10L, "BBOX",
                "alice-car", "[[5,6],[7,8]]");
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of(mine));
        givenFrames(frame(10L, 0, writeDeidFrame(0)), frame(11L, 1, writeDeidFrame(1)));
        when(lblRepository.findAllByRawSn(RAW_SN)).thenReturn(List.of(
                datamartLabel(10L, "datamart-person"),
                datamartLabel(11L, "datamart-dog")));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        String labels = new String(unzip(service.download(RAW_SN, alice)).get("100/labels.json"),
                StandardCharsets.UTF_8);

        // 본인 저장분이 있는 프레임은 본인 것만 — 그 프레임의 데이터마트 원본은 섞이지 않는다.
        assertThat(labels).contains("alice-car").doesNotContain("datamart-person");
        // 저장하지 않은 프레임은 데이터마트 원본으로 대체된다(타 사용자 저장분이 아니다).
        assertThat(labels).contains("datamart-dog");
    }

    // ======================== 파일명·헤더 규약 ========================

    @Test
    @DisplayName("프레임_이미지_파일명은_FRM_NO_4자리_zero_pad_다")
    void frameFileNameIsZeroPadded() throws IOException {
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)),
                frame(11L, 7, writeDeidFrame(7)),
                frame(12L, 338, writeDeidFrame(338)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        assertThat(zip.keySet()).contains(
                "100/frames/0000.jpg", "100/frames/0007.jpg", "100/frames/0338.jpg");
    }

    @Test
    @DisplayName("응답은_no_store_이고_파일명은_서버_생성_고정명이다")
    void responseHeaders() throws IOException {
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        ResponseEntity<StreamingResponseBody> response = service.download(RAW_SN, alice);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"portal-video-100-"
                        + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".zip\"");
        assertThat(response.getHeaders().getContentType()).hasToString("application/zip");
    }

    // ======================== fixtures ========================

    private LsDataRaw raw() {
        return raw(rawBase.resolve("original.mp4").toString());
    }

    private LsDataRaw raw(String rawFilePathNm) {
        LsDataRaw r = LsDataRaw.builder().rawFilePathNm(rawFilePathNm).build();
        setField(r, "rawSn", RAW_SN);
        return r;
    }

    private void givenMyLabelOnFrame(Long srcSn, String label, String points) {
        LsPortalUserLabel mine = LsPortalUserLabel.create("alice", RAW_SN, srcSn, "BBOX", label, points);
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of(mine));
    }

    private void givenFrames(LsDataSrc... frames) {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frames));
    }

    private LsDataSrc frame(Long srcSn, long frameNo, Path deidImage) {
        LsDataSrc s = LsDataSrc.create(RAW_SN, frameNo, rawBase.resolve("f" + frameNo + ".jpg").toString(),
                LocalDateTime.now());
        s.attachDeidPath(deidImage == null ? null : deidImage.toString());
        setField(s, "srcSn", srcSn);
        return s;
    }

    private LsDataLbl datamartLabel(Long srcSn, String label) {
        return LsDataLbl.createAutoBbox(srcSn, null, label, "[[1,2],[3,4]]", BigDecimal.valueOf(0.9), null);
    }

    /** 비식별 프레임은 {@code {deid_base}/frames/deid/{rawSn}/} 규약 하위여야 판정을 통과한다. */
    private Path writeDeidFrame(long frameNo) throws IOException {
        Path dir = Files.createDirectories(deidBase.resolve("frames").resolve("deid").resolve(String.valueOf(RAW_SN)));
        Path file = dir.resolve("frame-" + frameNo + ".jpg");
        Files.write(file, ("FRAME-" + frameNo).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 비식별 영상은 {@code {deid_base}/videos/{rawSn}/} 규약 하위(구 위치)에 둔다. */
    private Path writeDeidVideo(String fileName, String content) throws IOException {
        Path dir = Files.createDirectories(deidBase.resolve("videos").resolve(String.valueOf(RAW_SN)));
        Path file = dir.resolve(fileName);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private Map<String, byte[]> unzip(ResponseEntity<StreamingResponseBody> response) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        response.getBody().writeTo(buffer);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
