package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
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
import static org.mockito.Mockito.lenient;
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
        // S6 — 구 위치(deidDir) + 신 위치(co-locate) 2-way allowlist. nasRoot 를 허용 마운트 루트로 둔다.
        // S7-STREAM — 신고 게이트는 판정 단일 원천(DeidentReportGate)을 그대로 끼운다(판정 복제 금지).
        //   videoRepository 스텁(findDeIdntfYnByRawSn)이 곧 게이트 판정이 된다.
        videoStreamService = new VideoStreamService(videoRepository, streamUrlSigner, procLogRepository,
                ArtifactRootTestSupport.coLocate(tempDir, tempDir.resolve("raw"), deidDir),
                new DeidentReportGate(videoRepository));
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
    @DisplayName("비식별_경로_Path_Traversal_시도시_거부 — 구·신 어느 base 에도 없으면 NOT_FOUND")
    void streamVideo_pathTraversal_refused() {
        // given — procLog 의 deid 경로가 허용 base 밖을 가리킴 (변조/손상)
        Long rawSn = 3L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, "../../../etc/passwd")));

        HttpHeaders headers = new HttpHeaders();

        // when / then — 거부한다. 존재/권한을 응답으로 구분해주지 않도록 NOT_FOUND 로 정규화(S7).
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
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
    @DisplayName("스트림_200응답이_재검증없는_장기캐시를_두지_않음 — 신고 게이트 클라이언트 우회 차단(CWE-359/525)")
    void streamVideo_200_hasNoLongLivedCache() throws IOException {
        // given — 정상 비식별 영상(게이트 통과 상태)
        Long rawSn = 91L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_91_deid.mp4");
        Files.write(deidFile, new byte[2048]);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        // when — Range 없는 전체 요청(200)
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, new HttpHeaders());

        // then — 응답을 브라우저가 재사용하면 신고('F') 이후에도 마스킹 실패 영상이 서버에 오지 않고
        //        재생된다. 비식별 프레임 이미지 경로(serveDeidentified)와 동일한 no-store 여야 한다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cacheControl = response.getHeaders().getCacheControl();
        assertThat(cacheControl).isEqualTo("no-store");
        assertThat(cacheControl).doesNotContain("max-age");
    }

    @Test
    @DisplayName("스트림_206부분응답도_장기캐시를_두지_않음 — 캐시된 청크 재생으로 게이트 우회 방지")
    void streamVideo_206_hasNoLongLivedCache() throws IOException {
        // given — 정상 비식별 영상 + Range 요청(브라우저 시크가 만드는 실제 형태)
        Long rawSn = 92L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_92_deid.mp4");
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
        String cacheControl = response.getHeaders().getCacheControl();
        assertThat(cacheControl).isEqualTo("no-store");
        assertThat(cacheControl).doesNotContain("max-age");
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

    // ---------- S7-STREAM — 신고 게이트 판정 범위(자기 행 전용, 2026-07-29 확정 정책) ----------

    /** 신고 게이트 판정 스텁 — 그 영상 행의 {@code DE_IDNTF_YN} 하나가 곧 판정값이다. */
    private void stubGateDeidentYn(Long rawSn, String deIdntfYn) {
        when(videoRepository.findDeIdntfYnByRawSn(rawSn)).thenReturn(Optional.ofNullable(deIdntfYn));
    }

    @Test
    @DisplayName("★원본이_신고중이어도_파생영상_stream은_200 — 파생은 신고 체계 바깥(확정 정책)")
    void streamVideo_originReported_derivativeStillServes() throws IOException {
        // given — 해상도/증강 파생본은 부모의 비식별 영상 파일 사본이라 자기 행은 'Y'(확정)로 남는다.
        //         부모(80)에 비식별 누락 신고가 들어가 'F' 가 된 상태.
        //         2026-07-29 사용자 확정: 파생본은 원본 신고와 무관하게 계속 서빙된다(감수된 함의).
        Long derivativeSn = 81L;
        Files.createDirectories(deidDir);
        Path deidFile = deidDir.resolve("clip_81_deid.mp4");
        Files.write(deidFile, new byte[256]);
        stubGateDeidentYn(derivativeSn, "Y");
        when(videoRepository.findById(derivativeSn))
                .thenReturn(Optional.of(deidReadyRaw(derivativeSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(derivativeSn))
                .thenReturn(Optional.of(stubDeidLog(derivativeSn, deidFile.toString())));

        // when
        ResponseEntity<ResourceRegion> res = videoStreamService.stream(derivativeSn, new HttpHeaders());

        // then — 파생 행 자체가 'F' 가 아니면 막지 않는다. 부모 행은 조회조차 하지 않는다.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(videoRepository, org.mockito.Mockito.never())
                .findDeIdntfYnByRawSn(80L);
    }

    @Test
    @DisplayName("★원본이_신고중이어도_파생영상_서명URL은_발급된다 — 자기행 판정")
    void issueSignedUrl_originReported_derivativeStillIssued() {
        // given — 파생본 자기 행은 'Y'(정상), 부모(82)만 신고 'F'.
        Long derivativeSn = 83L;
        String nonce = "0123456789abcdef0123456789abcdef";
        when(videoRepository.findById(derivativeSn)).thenReturn(Optional.of(deidReadyRaw(derivativeSn)));
        stubGateDeidentYn(derivativeSn, "Y");
        when(streamUrlSigner.isConfigured()).thenReturn(true);
        when(streamUrlSigner.sign(derivativeSn, "1", nonce))
                .thenReturn(new StreamUrlSigner.SignedParams(1_700_000_000L, "deadbeef", 60L));

        // when / then — 발급된다(구 조상 체인 차단 정책 철회분 회귀 고정).
        assertThat(videoStreamService.issueSignedUrl(derivativeSn, "1", nonce).url())
                .contains("&u=1&sig=deadbeef");
    }

    @Test
    @DisplayName("자기행이_F면_stream은_NOT_FOUND — 캐시 앞 게이트(CWE-525)")
    void streamVideo_selfReported_notFound() throws IOException {
        // given — 이 영상 자체가 신고 구간('F'). 게이트가 stream-meta 캐시 조회 앞에서 끊는다.
        Long rawSn = 85L;
        stubGateDeidentYn(rawSn, "F");

        // when / then — 응답 코드는 이 엔드포인트 기존 규약(비식별 무효 = 404)과 동일하다.
        HttpHeaders headers = new HttpHeaders();
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        // 게이트에서 끝나므로 캐시/메타 경로에 진입하지 않는다.
        org.mockito.Mockito.verify(videoRepository, org.mockito.Mockito.never()).findById(rawSn);
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
        assertThatThrownBy(() -> videoStreamService.issueSignedUrl(rawSn, "1", "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("서명URL_발급_userNo와_nonce_바인딩_URL에는_u만_포함")
    void issueSignedUrl_bindsUserNoAndNonce_includesUInUrl() {
        // given: 비식별 유효('Y') + 시크릿 설정 + userNo='1' + 클라이언트 바인딩 nonce
        Long rawSn = 11L;
        String nonce = "0123456789abcdef0123456789abcdef";
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(streamUrlSigner.isConfigured()).thenReturn(true);
        when(streamUrlSigner.sign(rawSn, "1", nonce))
                .thenReturn(new StreamUrlSigner.SignedParams(1_700_000_000L, "deadbeef", 60L));

        // when
        var resp = videoStreamService.issueSignedUrl(rawSn, "1", nonce);

        // then: 서명 입력에 userNo + nonce 가 바인딩되고, URL 에는 u 만 노출된다(nonce 는 쿠키로만 전달).
        org.mockito.Mockito.verify(streamUrlSigner).sign(rawSn, "1", nonce);
        assertThat(resp.url()).contains("&u=1&sig=deadbeef");
        assertThat(resp.url()).doesNotContain(nonce);
    }

    // ===================== S6/S8 — 구·신 위치 혼재 + 외부 산출물명 =====================

    /**
     * 허용 마운트 루트(tempDir) 하위에 원본 영상이 있는 비식별 완료 영상 stub.
     * co-locate base 는 이 원본의 디렉터리에서 도출된다.
     */
    private LsDataRaw coLocateReadyRaw(Long rawSn) throws IOException {
        Path nasDir = tempDir.resolve("nas");
        Files.createDirectories(nasDir);
        Path original = nasDir.resolve("clip_" + rawSn + ".mp4");
        Files.write(original, new byte[16]);
        LsDataRaw raw = stubRaw(rawSn, original.toString());
        // co-locate base 도출에 rawSn 이 필요하다(적재 후 PK 부여 상황 모사).
        ReflectionTestUtils.setField(raw, "rawSn", rawSn);
        raw.markDeidentified("Y");
        return raw;
    }

    /** co-locate 비식별 디렉터리({@code dirname(원본)/{rawSn}/deid/})에 지정한 <b>파일명</b>으로 산출물을 만든다. */
    private Path writeColocateDeidFile(LsDataRaw raw, String fileName) throws IOException {
        Path dir = Path.of(raw.getRawFilePathNm()).getParent()
                .resolve(String.valueOf(raw.getRawSn()))
                .resolve("deid");
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.write(file, new byte[2048]);
        return file;
    }

    private void stubStreamable(LsDataRaw raw, Path deidFile) {
        when(videoRepository.findById(raw.getRawSn())).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(raw.getRawSn()))
                .thenReturn(Optional.of(stubDeidLog(raw.getRawSn(), deidFile.toString())));
    }

    @Test
    @DisplayName("S6_구위치_비식별영상이_그대로_스트리밍된다 — 배포 전 산출물 회귀")
    void stream_legacyLocation_succeeds() throws IOException {
        // given — 구 위치({deid_base}/videos/{rawSn}/) 산출물
        Long rawSn = 60L;
        Path legacyDir = deidDir.resolve("videos").resolve(String.valueOf(rawSn));
        Files.createDirectories(legacyDir);
        Path legacyFile = legacyDir.resolve("deidentified.mp4");
        Files.write(legacyFile, new byte[2048]);
        stubStreamable(coLocateReadyRaw(rawSn), legacyFile);

        // when / then
        assertThat(videoStreamService.stream(rawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("S6_신위치_co_locate_비식별영상이_스트리밍된다")
    void stream_coLocateLocation_succeeds() throws IOException {
        // given — 신 위치(dirname(원본)/{rawSn}/deid/) 산출물
        Long rawSn = 61L;
        LsDataRaw raw = coLocateReadyRaw(rawSn);
        stubStreamable(raw, writeColocateDeidFile(raw, "deidentified.mp4"));

        // when / then
        assertThat(videoStreamService.stream(rawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("S6_구위치_행과_신위치_행이_동시에_있어도_둘_다_스트리밍된다")
    void stream_legacyAndColocateRows_bothSucceed() throws IOException {
        // given — 영상 A 는 구 위치, 영상 B 는 신 위치(전환 전후 행 혼재)
        Long legacySn = 62L;
        Path legacyDir = deidDir.resolve("videos").resolve(String.valueOf(legacySn));
        Files.createDirectories(legacyDir);
        Path legacyFile = legacyDir.resolve("deidentified.mp4");
        Files.write(legacyFile, new byte[2048]);
        stubStreamable(coLocateReadyRaw(legacySn), legacyFile);

        Long coLocateSn = 63L;
        LsDataRaw coLocateRaw = coLocateReadyRaw(coLocateSn);
        stubStreamable(coLocateRaw, writeColocateDeidFile(coLocateRaw, "clip_63-mask.mp4"));

        // when / then — 둘 다 200 (한쪽만 허용되는 단일 base 가드였다면 하나가 NOT_FOUND)
        assertThat(videoStreamService.stream(legacySn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(videoStreamService.stream(coLocateSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("S6_구위치도_신위치도_아닌_제3의_경로는_차단된다")
    void stream_thirdLocation_refused() throws IOException {
        // given — 허용 base 어디에도 속하지 않는 실재 파일(파일이 있어도 허용되면 안 된다)
        Long rawSn = 64L;
        Path rogueDir = tempDir.resolve("nas").resolve(String.valueOf(rawSn)).resolve("rogue");
        Files.createDirectories(rogueDir);
        Path rogue = rogueDir.resolve("deidentified.mp4");
        Files.write(rogue, new byte[2048]);
        stubStreamable(coLocateReadyRaw(rawSn), rogue);

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("비식별_영상_경로는_파일명을_조합하지_않고_LS_DEIDENT_PROC_LOG_값을_사용한다")
    void deidPathComesFromProcLogNotFromNamingRule() throws IOException {
        // given — 어떤 명명 규칙(mock 'deidentified.mp4' / KPST '{stem}-mask.mp4')으로도 유도되지 않는 이름
        Long rawSn = 65L;
        LsDataRaw raw = coLocateReadyRaw(rawSn);
        Path unpredictable = writeColocateDeidFile(raw, "vendor-output-20260727-x9.mp4");
        stubStreamable(raw, unpredictable);

        // when
        String resolved = videoStreamService.resolveDeidPath(rawSn);

        // then — procLog 적재값 그대로. 규칙 조합이었다면 이 이름이 나올 수 없다.
        assertThat(resolved).isEqualTo(unpredictable.toString());
        assertThat(videoStreamService.stream(rawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("KPST_산출물명이_원본stem_mask_형태여도_스트리밍이_동작한다")
    void stream_kpstMaskFileName_succeeds() throws IOException {
        // given — KPST 실연동 산출명({원본stem}-mask{ext}) — 영상마다 이름이 다르다
        Long rawSn = 66L;
        LsDataRaw raw = coLocateReadyRaw(rawSn);
        Path masked = writeColocateDeidFile(raw, "clip_66-mask.mp4");
        stubStreamable(raw, masked);

        // when / then
        assertThat(videoStreamService.resolveDeidPath(rawSn)).endsWith("clip_66-mask.mp4");
        assertThat(videoStreamService.stream(rawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("mock명과_KPST명이_섞여도_둘_다_동작한다")
    void stream_mockAndKpstNamesCoexist() throws IOException {
        // given — 같은 배포 안에 mock 산출(고정명)과 KPST 산출(파생명)이 공존
        Long mockSn = 67L;
        LsDataRaw mockRaw = coLocateReadyRaw(mockSn);
        stubStreamable(mockRaw, writeColocateDeidFile(mockRaw, "deidentified.mp4"));

        Long kpstSn = 68L;
        LsDataRaw kpstRaw = coLocateReadyRaw(kpstSn);
        stubStreamable(kpstRaw, writeColocateDeidFile(kpstRaw, "clip_68-mask.mp4"));

        // when / then
        assertThat(videoStreamService.stream(mockSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(videoStreamService.stream(kpstSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("S8_롤백전략_labeling_root_에서도_구행_신행_모두_조회된다 — 적재 경로 재계산 금지")
    void stream_rollbackStrategy_readsBothLocations() throws IOException {
        // given — 롤백 전략(labeling-root)으로 전환된 인스턴스. 신규 산출 base 는 구 위치로 돌아가지만,
        //         이미 적재된 co-locate 절대경로(DE_IDNTF_FILE_PATH_NM)는 재계산하지 않고 그대로 읽어야 한다.
        videoStreamService = new VideoStreamService(videoRepository, streamUrlSigner, procLogRepository,
                ArtifactRootTestSupport.labelingRootWithAllowedRoot(
                        tempDir, tempDir.resolve("labeling"), deidDir),
                new DeidentReportGate(videoRepository));
        ReflectionTestUtils.setField(videoStreamService, "storageRawPath", tempDir.resolve("raw").toString());
        ReflectionTestUtils.setField(videoStreamService, "deidentifiedPath", deidDir.toString());

        Long legacySn = 69L;
        Path legacyDir = deidDir.resolve("videos").resolve(String.valueOf(legacySn));
        Files.createDirectories(legacyDir);
        Path legacyFile = legacyDir.resolve("deidentified.mp4");
        Files.write(legacyFile, new byte[2048]);
        stubStreamable(coLocateReadyRaw(legacySn), legacyFile);

        Long coLocateSn = 70L;
        LsDataRaw coLocateRaw = coLocateReadyRaw(coLocateSn);
        stubStreamable(coLocateRaw, writeColocateDeidFile(coLocateRaw, "clip_70-mask.mp4"));

        // when / then — 전략을 되돌려도 양쪽 행이 모두 조회된다.
        assertThat(videoStreamService.stream(legacySn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(videoStreamService.stream(coLocateSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
