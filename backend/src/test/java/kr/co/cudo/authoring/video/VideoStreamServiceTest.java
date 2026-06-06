package kr.co.cudo.authoring.video;

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

    private VideoStreamService videoStreamService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        videoStreamService = new VideoStreamService(videoRepository, streamUrlSigner);
        ReflectionTestUtils.setField(videoStreamService, "storageRawPath", tempDir.toString());
    }

    private LsDataRaw stubRaw(Long rawSn, String relativePath) {
        return LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, relativePath,
                LocalDateTime.now(), 60);
    }

    @Test
    @DisplayName("영상_스트리밍_정상_200_Content_Type_video")
    void streamVideo_normal_200() throws IOException {
        // given
        Long rawSn = 1L;
        String relativePath = "clip_1.mp4";
        Files.write(tempDir.resolve(relativePath), new byte[1024]); // dummy video file

        LsDataRaw raw = stubRaw(rawSn, relativePath);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        HttpHeaders headers = new HttpHeaders();

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("video");
    }

    @Test
    @DisplayName("영상_미존재시_NOT_FOUND")
    void streamVideo_notFound() {
        // given
        Long rawSn = 999L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.empty());

        HttpHeaders headers = new HttpHeaders();

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("Path_Traversal_시도시_FORBIDDEN")
    void streamVideo_pathTraversal_forbidden() {
        // given
        Long rawSn = 3L;
        String maliciousPath = "../../../etc/passwd";
        LsDataRaw raw = stubRaw(rawSn, maliciousPath);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        HttpHeaders headers = new HttpHeaders();

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("파일_미존재시_NOT_FOUND")
    void streamVideo_fileMissing_notFound() {
        // given
        Long rawSn = 4L;
        String nonExistentPath = "nonexistent.mp4";
        LsDataRaw raw = stubRaw(rawSn, nonExistentPath);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        HttpHeaders headers = new HttpHeaders();

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("Range_헤더_있으면_206_Partial_Content")
    void streamVideo_rangeHeader_206() throws IOException {
        // given
        Long rawSn = 5L;
        String relativePath = "clip_5.mp4";
        byte[] content = new byte[10_000];
        Files.write(tempDir.resolve(relativePath), content);

        LsDataRaw raw = stubRaw(rawSn, relativePath);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-999");

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getBody()).isNotNull();
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
