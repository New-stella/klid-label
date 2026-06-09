package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.StreamUrlSigner;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * VideoStreamService 단위 테스트 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class VideoStreamServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private StreamUrlSigner streamUrlSigner;

    @Mock
    private LsDeidentProcLogRepository procLogRepository;

    private VideoStreamService videoStreamService;

    @TempDir
    Path tempDir;

    /** 비식별 영상 저장 베이스 디렉토리. */
    private Path deidDir;

    @BeforeEach
    void setUp() {
        deidDir = tempDir.resolve("deidentified");
        videoStreamService = new VideoStreamService(videoRepository, streamUrlSigner, procLogRepository);
        ReflectionTestUtils.setField(videoStreamService, "storageRawPath", tempDir.resolve("raw").toString());
        ReflectionTestUtils.setField(videoStreamService, "deidentifiedPath", deidDir.toString());
    }

    private LsDataRaw stubRaw(Long rawSn, String relativePath) {
        return LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, relativePath,
                LocalDateTime.now(), 60);
    }

    /** 비식별 성공 procLog stub (deidPath 경로를 결과로 보유). */
    private LsDeidentProcLog stubDeidLog(Long rawSn, String deidPath) {
        LsDeidentProcLog log = LsDeidentProcLog.request(rawSn, null, "raw/orig.mp4", "batch");
        log.succeed(deidPath);
        return log;
    }

    @Test
    @DisplayName("마킹스트림이_비식별영상을_스트리밍 — 200 video")
    void streamVideo_deidentified_200() throws IOException {
        // given — 비식별 완료: procLog 에 deid 경로, 파일은 deidDir 하위에 존재
        Long rawSn = 1L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_1_deid.mp4");
        Files.write(deidFile, new byte[1024]);

        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 비식별 영상이 스트리밍되어야 한다 (원본 노출 금지)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("video");
    }

    @Test
    @DisplayName("비식별_미완료시_NOT_FOUND — 원본 노출 금지(privacy)")
    void streamVideo_deidentNotReady_notFound() {
        // given — 비식별 procLog 없음 (미완료)
        Long rawSn = 2L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.empty());

        HttpHeaders headers = new HttpHeaders();

        // when / then — 원본을 절대 노출하지 않고 NOT_FOUND
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("비식별_파일이_물리적으로_없으면_NOT_FOUND")
    void streamVideo_deidFileMissing_notFound() {
        // given — procLog 는 있으나 파일이 디스크에 없음
        Long rawSn = 6L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidDir.resolve("missing.mp4").toString())));

        HttpHeaders headers = new HttpHeaders();

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("영상_미존재시_NOT_FOUND")
    void streamVideo_notFound() {
        // given
        Long rawSn = 999L;
        when(videoRepository.existsById(rawSn)).thenReturn(false);

        HttpHeaders headers = new HttpHeaders();

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("비식별_경로_Path_Traversal_시도시_FORBIDDEN")
    void streamVideo_pathTraversal_forbidden() {
        // given — procLog 의 deid 경로가 base 밖을 가리킴 (변조 시도)
        Long rawSn = 3L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, "../../../etc/passwd")));

        HttpHeaders headers = new HttpHeaders();

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("Range_헤더_있으면_206_Partial_Content")
    void streamVideo_rangeHeader_206() throws IOException {
        // given — 비식별 영상
        Long rawSn = 5L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_5_deid.mp4");
        Files.write(deidFile, new byte[10_000]);

        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-999");

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("resolveDeidPath_비식별완료면_경로반환 — 캐시 대상(non-null)")
    void resolveDeidPath_completed_returnsPath() {
        // given — 비식별 완료
        Long rawSn = 20L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, "/deid/clip_20.mp4")));

        // when
        String path = videoStreamService.resolveDeidPath(rawSn);

        // then — non-null 경로만 @Cacheable(unless="#result==null") 캐시 대상
        assertThat(path).isEqualTo("/deid/clip_20.mp4");
    }

    @Test
    @DisplayName("resolveDeidPath_비식별미완료면_null — 캐시 미적용(stale NOT_FOUND 방지)")
    void resolveDeidPath_notCompleted_returnsNull() {
        // given — 비식별 미완료(async 진행 중)
        Long rawSn = 21L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.empty());

        // when
        String path = videoStreamService.resolveDeidPath(rawSn);

        // then — null 은 캐시하지 않아야 한다(완료 후 stale NOT_FOUND 방지). 메서드 자체는 null 반환.
        assertThat(path).isNull();
    }

    @Test
    @DisplayName("서명URL_시크릿_미설정시_503_SERVICE_UNAVAILABLE")
    void issueSignedUrl_secretNotConfigured_serviceUnavailable() {
        // given: 영상은 존재하나 서명 시크릿 미설정(서버 설정 오류)
        Long rawSn = 10L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(stubRaw(rawSn, "clip_10.mp4")));
        when(streamUrlSigner.isConfigured()).thenReturn(false);

        // when / then: 권한 거부(403)가 아닌 503 SERVICE_UNAVAILABLE 로 매핑
        assertThatThrownBy(() -> videoStreamService.issueSignedUrl(rawSn, "1"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("서명URL_발급_userNo바인딩_URL에_u포함")
    void issueSignedUrl_bindsUserNo_includesUInUrl() {
        // given: 시크릿 설정 + userNo='1' 로 서명
        Long rawSn = 11L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(stubRaw(rawSn, "clip_11.mp4")));
        when(streamUrlSigner.isConfigured()).thenReturn(true);
        when(streamUrlSigner.sign(rawSn, "1"))
                .thenReturn(new StreamUrlSigner.SignedParams(1_700_000_000L, "deadbeef", 60L));

        // when
        var resp = videoStreamService.issueSignedUrl(rawSn, "1");

        // then: 서명 입력에 userNo 가 바인딩되고 URL 쿼리에 u=1 이 포함된다
        org.mockito.Mockito.verify(streamUrlSigner).sign(rawSn, "1");
        assertThat(resp.url()).contains("&u=1&sig=deadbeef");
    }
}
