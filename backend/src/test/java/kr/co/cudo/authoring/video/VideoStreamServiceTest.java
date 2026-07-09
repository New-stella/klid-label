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

    /**
     * 비식별 유효('Y') 상태의 영상 stub — 정상 마킹 스트림 재생 전제(비식별 완료 시 DE_IDNTF_YN='Y').
     * 스트림 게이트가 'Y' 를 요구하므로, 정상 흐름 테스트는 이 stub 을 findById 로 반환한다.
     */
    private LsDataRaw deidReadyRaw(Long rawSn) {
        LsDataRaw raw = stubRaw(rawSn, "clip_" + rawSn + ".mp4");
        raw.markDeidentified("Y");
        return raw;
    }

    /** 신고로 DE_IDNTF_YN='F' 마킹된 영상 stub — 재비식별 완료 전 스트림 노출 차단 검증용. */
    private LsDataRaw reportedRaw(Long rawSn) {
        LsDataRaw raw = stubRaw(rawSn, "clip_" + rawSn + ".mp4");
        raw.markDeidentified("F");
        return raw;
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

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
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
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
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
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
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
        when(videoRepository.findById(rawSn)).thenReturn(Optional.empty());

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
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
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

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
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
    @DisplayName("열린범위_요청시_region길이가_새청크상한_8MB로_상한됨 — 1MB 아님(4배속 버벅임 수정)")
    void streamVideo_openRange_cappedAtEightMb() throws IOException {
        // given — 파일이 청크 상한(8MB)보다 큼(9MB)
        Long rawSn = 30L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_30_deid.mp4");
        Files.write(deidFile, new byte[9 * 1024 * 1024]);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-"); // 열린 범위: 브라우저 재생 시작 패턴

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 반환 region 은 새 청크 상한(8MB)으로 상한, 구 1MB 가 아니어야 한다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCount()).isEqualTo(8_388_608L);
        assertThat(response.getBody().getCount()).isGreaterThan(1_048_576L);
    }

    @Test
    @DisplayName("파일이_청크보다_작으면_파일크기만큼만_반환")
    void streamVideo_openRange_smallFileReturnsFullSize() throws IOException {
        // given — 파일(500KB)이 청크 상한(8MB)보다 작음
        Long rawSn = 31L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_31_deid.mp4");
        long fileSize = 500 * 1024;
        Files.write(deidFile, new byte[(int) fileSize]);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-");

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 파일 크기만큼만 반환(상한에 걸리지 않음)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getBody().getCount()).isEqualTo(fileSize);
    }

    @Test
    @DisplayName("설정된_chunkSize값이_Range반환에_반영됨 — 2MB 설정")
    void streamVideo_configuredChunkSize_applied() throws IOException {
        // given — chunk-size 를 2MB 로 설정, 파일은 9MB
        Long rawSn = 32L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_32_deid.mp4");
        Files.write(deidFile, new byte[9 * 1024 * 1024]);
        ReflectionTestUtils.setField(videoStreamService, "streamChunkSize", 2 * 1024 * 1024L);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-");

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 설정된 2MB 상한이 반영되어야 한다
        assertThat(response.getBody().getCount()).isEqualTo(2 * 1024 * 1024L);
    }

    @Test
    @DisplayName("잘못된_chunkSize설정_0이하면_안전기본값_8MB로_폴백")
    void streamVideo_invalidChunkSize_fallsBackToDefault() throws IOException {
        // given — chunk-size 를 0(미설정/불량) 으로, 파일은 9MB
        Long rawSn = 33L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_33_deid.mp4");
        Files.write(deidFile, new byte[9 * 1024 * 1024]);
        ReflectionTestUtils.setField(videoStreamService, "streamChunkSize", 0L);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-");

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 음수/0 은 fail-safe 기본값(8MB)으로 폴백
        assertThat(response.getBody().getCount()).isEqualTo(8_388_608L);
    }

    @Test
    @DisplayName("음수_chunkSize설정이면_안전기본값_8MB로_폴백")
    void streamVideo_negativeChunkSize_fallsBackToDefault() throws IOException {
        // given — chunk-size 를 음수(-1) 로, 파일은 9MB
        Long rawSn = 37L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_37_deid.mp4");
        Files.write(deidFile, new byte[9 * 1024 * 1024]);
        ReflectionTestUtils.setField(videoStreamService, "streamChunkSize", -1L);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-");

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 음수는 하한 미만이므로 fail-safe 기본값(8MB)으로 폴백(재요청 폭증 방지)
        assertThat(response.getBody().getCount()).isEqualTo(8_388_608L);
    }

    @Test
    @DisplayName("effectiveChunkSize가_상한64MB로_클램프됨 — 비정상 대형 설정값")
    void effectiveChunkSize_clampedToMax() {
        // given — chunk-size 를 상한(64MB) 초과 대형 값으로 설정
        ReflectionTestUtils.setField(videoStreamService, "streamChunkSize", 200L * 1024 * 1024);

        // when — private 유효 청크 계산
        Object chunk = ReflectionTestUtils.invokeMethod(videoStreamService, "effectiveChunkSize");

        // then — 64MB(67_108_864) 로 클램프
        assertThat(chunk).isEqualTo(67_108_864L);
    }

    @Test
    @DisplayName("비정상_대형_chunkSize에도_long오버플로_없이_안전서빙 — start>0에서 음수 count 방지(CWE-190)")
    void streamVideo_hugeChunkSize_noOverflow() throws IOException {
        // given — chunk-size 를 Long.MAX_VALUE 로(오버플로 유발 시도), 파일 10000바이트, start=5000
        Long rawSn = 38L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_38_deid.mp4");
        Files.write(deidFile, new byte[10_000]);
        ReflectionTestUtils.setField(videoStreamService, "streamChunkSize", Long.MAX_VALUE);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=5000-");

        // when — 클램프(64MB)로 start(5000)+chunk-1 이 오버플로하지 않아 정상 206
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 음수 count/500 없이 파일 끝까지(5000..9999, 5000바이트) 서빙
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getBody().getCount()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("Range_범위밖_start가_total이상이면_416")
    void streamVideo_rangeOutOfBounds_416() throws IOException {
        // given — 1000 바이트 파일에 2000-3000 요청
        Long rawSn = 34L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_34_deid.mp4");
        Files.write(deidFile, new byte[1000]);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=2000-3000");

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — fail-secure 416 + Content-Range: bytes */total
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */1000");
    }

    @Test
    @DisplayName("역전Range_bytes999-0이면_416")
    void streamVideo_reversedRange_416() throws IOException {
        // given
        Long rawSn = 35L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_35_deid.mp4");
        Files.write(deidFile, new byte[10_000]);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=999-0"); // 역전 문법 → IllegalArgumentException → 416

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — fail-secure 416
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }

    @Test
    @DisplayName("Range_없으면_200_전체길이_반환")
    void streamVideo_noRange_200FullLength() throws IOException {
        // given
        Long rawSn = 36L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_36_deid.mp4");
        Files.write(deidFile, new byte[3000]);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders(); // Range 없음

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, headers);

        // then — 200 + 전체 길이
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCount()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("resolveDeidPath_비식별유효Y_성공procLog면_경로반환")
    void resolveDeidPath_completed_returnsPath() {
        // given — 비식별 유효('Y') + 성공 procLog 존재
        Long rawSn = 20L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, "/deid/clip_20.mp4")));

        // when
        String path = videoStreamService.resolveDeidPath(rawSn);

        // then — 게이트 통과 후 procLog 경로 반환(상위 stream-meta 캐시가 이 결과를 캐싱)
        assertThat(path).isEqualTo("/deid/clip_20.mp4");
    }

    @Test
    @DisplayName("resolveDeidPath_성공procLog없으면_null — 상위 NOT_FOUND")
    void resolveDeidPath_notCompleted_returnsNull() {
        // given — 비식별 유효('Y') 이나 성공 procLog 없음(경로 미확정)
        Long rawSn = 21L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.empty());

        // when
        String path = videoStreamService.resolveDeidPath(rawSn);

        // then — 경로 미확정이면 null(상위가 NOT_FOUND). null 은 stream-meta 에 캐시되지 않는다.
        assertThat(path).isNull();
    }

    @Test
    @DisplayName("신고로_DE_IDNTF_YN이_F면_성공procLog가_있어도_stream_NOT_FOUND — 노출본 차단(privacy)")
    void streamVideo_deidentFlagF_notFound() {
        // given — 신고로 'F' 마킹되었으나 옛 성공 procLog 는 남아있음(취약 시나리오)
        Long rawSn = 40L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(reportedRaw(rawSn)));
        // procLog 는 게이트에서 막혀 조회되지 않으므로 stub 하지 않는다(strict stubs).

        HttpHeaders headers = new HttpHeaders();

        // when / then — DE_IDNTF_YN != 'Y' 이면 재비식별 완료 전까지 노출본을 서빙하지 않는다
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("resolveDeidPath_DE_IDNTF_YN이_F면_null — procLog 미조회 게이트")
    void resolveDeidPath_flagF_returnsNull() {
        // given — 'F'(신고) 상태
        Long rawSn = 41L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(reportedRaw(rawSn)));

        // when
        String path = videoStreamService.resolveDeidPath(rawSn);

        // then — 게이트에서 null 반환(procLog 조회 없이 서빙 거부)
        assertThat(path).isNull();
    }

    @Test
    @DisplayName("서명URL_시크릿_미설정시_503_SERVICE_UNAVAILABLE")
    void issueSignedUrl_secretNotConfigured_serviceUnavailable() {
        // given: 비식별 유효('Y') 영상이나 서명 시크릿 미설정(서버 설정 오류)
        Long rawSn = 10L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
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
        // given: 비식별 유효('Y') + 시크릿 설정 + userNo='1' 로 서명
        Long rawSn = 11L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
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
