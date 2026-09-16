package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.config.PublicApiPath;
import kr.co.cudo.authoring.common.config.PublicApiPathDefaults;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
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

    /**
     * 구 위치 규약({@code {deid_base}/videos/{rawSn}/})의 비식별 산출물 경로를 만든다(디렉터리까지 생성).
     *
     * <p>구 픽스처는 {@code {deid_base}} <b>바로 아래</b>에 파일을 두었는데, 그런 산출 경로는 코드
     * 어디에도 없다({@code DeidentifyStep}/{@code KpstDeidentService} 는 {@code videos/{rawSn}/} 로,
     * co-locate 는 {@code dirname(원본)/{rawSn}/deid/} 로 쓴다). 읽기 허용 base 를 비식별 영상 규약
     * 서브트리로 좁히면서(B-ISSUE-41) 그 비현실적 위치가 더 이상 허용되지 않으므로, 각 테스트의 의도를
     * 유지한 채 <b>실제 산출 위치</b>로 픽스처를 옮긴다.
     */
    private Path legacyDeidFile(Long rawSn, String fileName) throws IOException {
        Path dir = deidDir.resolve("videos").resolve(String.valueOf(rawSn));
        Files.createDirectories(dir);
        return dir.resolve(fileName);
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
        Path deidFile = legacyDeidFile(rawSn, "clip_1_deid.mp4");
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
    void streamVideo_deidFileMissing_notFound() throws IOException {
        // given — procLog 는 있으나 파일이 디스크에 없음
        Long rawSn = 6L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, legacyDeidFile(rawSn, "missing.mp4").toString())));

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
        Path deidFile = legacyDeidFile(rawSn, "clip_5_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_91_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_92_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_30_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_31_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_32_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_33_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_37_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_38_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_34_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_35_deid.mp4");
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
        Path deidFile = legacyDeidFile(rawSn, "clip_36_deid.mp4");
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
        Path deidFile = legacyDeidFile(derivativeSn, "clip_81_deid.mp4");
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

    // ------------------------------------------------------------------
    // B-ISSUE-81 — 심링크 치환(CWE-59/367/359) 회귀 가드
    //
    // 허용 base 안의 "비식별 산출물" 파일을 원본(비식별 이전) 영상 심링크로 바꾸면, lexical
    // normalize()+startsWith 만으로는 통과하고 실제로는 원본 바이트가 200 으로 서빙된다.
    // (실측 exploit — 정상 비식별본 50854B 대신 원본 20590B 가 그대로 나갔다.)
    // 형제 소비자(FfmpegFrameExtractor)는 이미 실경로 재검증으로 막고 있었으므로, 같은 정적 판정기를
    // 스트리밍에도 적용해 "가드가 갈라져 하나씩 샌다"를 닫는다.
    // ------------------------------------------------------------------

    /** 심링크를 만들 수 없는 파일시스템/권한 환경이면 해당 케이스를 건너뛴다(윈도우 등). */
    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "심링크 생성 불가 환경 — 케이스 skip");
        }
    }

    @Test
    @DisplayName("비식별파일이_원본영상_심링크면_NOT_FOUND — 원본 PII 서빙 차단(CWE-59/359)")
    void stream_deidFileIsSymlinkToOriginal_notFound() throws IOException {
        // given — 원본(비식별 이전) 영상은 허용 deid base 밖에 있다.
        Long rawSn = 81L;
        Path originalDir = tempDir.resolve("nas");
        Files.createDirectories(originalDir);
        Path original = originalDir.resolve("clip_81_original.mp4");
        Files.write(original, new byte[4096]);

        // 허용 base 안의 "비식별 산출물" 경로가 그 원본을 가리키는 심링크로 치환됐다(lexical 경로는 불변).
        Path deidLink = legacyDeidFile(rawSn, "clip_81_deid.mp4");
        createSymlinkOrSkip(deidLink, original);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidLink.toString())));

        // when / then — 실경로가 base 밖이므로 거부한다(원본 노출 금지 규약대로 NOT_FOUND 정규화).
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("co_locate_비식별파일이_원본영상_심링크여도_NOT_FOUND — 벤더 공유 마운트 신뢰경계")
    void stream_coLocateDeidFileIsSymlinkToOriginal_notFound() throws IOException {
        // given — co-locate deid 디렉터리(외부 비식별 벤더가 직접 쓰는 영역) 안의 산출물이
        //         같은 트리의 원본 영상을 가리키는 심링크로 치환됐다.
        Long rawSn = 82L;
        LsDataRaw raw = coLocateReadyRaw(rawSn);
        Path original = Path.of(raw.getRawFilePathNm());
        Path deidDirForRaw = original.getParent().resolve(String.valueOf(rawSn)).resolve("deid");
        Files.createDirectories(deidDirForRaw);
        Path deidLink = deidDirForRaw.resolve("clip_82-mask.mp4");
        createSymlinkOrSkip(deidLink, original);
        stubStreamable(raw, deidLink);

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("중간_디렉터리_세그먼트가_심링크로_base밖을_가리켜도_NOT_FOUND")
    void stream_intermediateSegmentSymlink_notFound() throws IOException {
        // given — base 밖 디렉터리에 원본을 두고, 비식별 산출 디렉터리({deid}/videos/{rawSn})
        //         <자체>를 그쪽 심링크로 바꾼다. 허용 base 계산(resolveUnder)이 이 우회를
        //         비식별 저장소 base 기준 실경로 검증으로 거부해 후보에서 탈락시켜야 한다.
        Long rawSn = 83L;
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path original = outside.resolve("clip_83_original.mp4");
        Files.write(original, new byte[4096]);

        Files.createDirectories(deidDir.resolve("videos"));
        Path linkedDir = deidDir.resolve("videos").resolve(String.valueOf(rawSn));
        createSymlinkOrSkip(linkedDir, outside);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn,
                        linkedDir.resolve("clip_83_original.mp4").toString())));

        // when / then — lexical 로는 base 하위지만 실경로는 base 밖이다.
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // B-ISSUE-81 잔여 — 캐시 히트 경로의 판정 우회(CWE-367/59/359)
    //
    // resolveStreamMeta 의 판정(realpath)은 stream-meta 캐시(TTL 5분) 뒤에 있다. 캐시가 채워진 뒤
    // 그 실경로 파일을 원본 영상 심링크로 치환하면, 후속 요청은 판정 메서드에 도달하지 않고 캐시된
    // 경로를 그대로 열어 마스킹 전 원본을 200/206 으로 서빙한다 — 창이 마이크로초가 아니라 TTL(5분)이다.
    // 아래 테스트는 self(프록시) 스텁으로 "캐시 히트"를 모사한다(단위 테스트에는 실제 캐시가 없다).
    // ------------------------------------------------------------------

    /**
     * stream-meta 캐시 히트 모사 — 이후 {@code stream()} 호출은 재해석 없이 이 메타를 그대로 받는다.
     * (운영에서는 {@code @Cacheable} 프록시가 같은 역할을 한다.)
     */
    private void simulateCacheHit(Long rawSn, VideoStreamService.StreamMeta cached) throws IOException {
        VideoStreamService cacheProxy = org.mockito.Mockito.mock(VideoStreamService.class);
        when(cacheProxy.resolveStreamMeta(rawSn)).thenReturn(cached);
        ReflectionTestUtils.setField(videoStreamService, "self", cacheProxy);
    }

    @Test
    @DisplayName("★캐시히트후_비식별파일이_원본심링크로_치환되면_NOT_FOUND — TTL 5분 유출창 차단(CWE-367/59/359)")
    void stream_cacheHit_thenFileSwappedToSymlink_notFound() throws IOException {
        // given — ① 정상 비식별본으로 1회 해석해 캐시(메타)를 채운다
        Long rawSn = 86L;
        Path originalDir = tempDir.resolve("nas");
        Files.createDirectories(originalDir);
        Path original = originalDir.resolve("clip_86_original.mp4");
        Files.write(original, "ORIGINAL-PII-BYTES".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Path deidFile = legacyDeidFile(rawSn, "clip_86_deid.mp4");
        Files.write(deidFile, "MASKED".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        lenient().when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        lenient().when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        VideoStreamService.StreamMeta cached = videoStreamService.resolveStreamMeta(rawSn);
        simulateCacheHit(rawSn, cached);

        // ② 캐시된 실경로 파일을 원본(마스킹 전) 영상 심링크로 치환한다(공유 마운트 공격면)
        Files.delete(deidFile);
        createSymlinkOrSkip(deidFile, original);

        // when / then — ③ 캐시 TTL 내 후속 요청도 원본을 서빙하지 않는다
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("★캐시히트후_Range요청도_심링크_치환되면_차단된다 — 시크 재요청 경로")
    void stream_cacheHit_rangeRequest_afterSymlinkSwap_notFound() throws IOException {
        // given — 캐시를 채운 뒤 파일을 원본 심링크로 치환
        Long rawSn = 87L;
        Path originalDir = tempDir.resolve("nas");
        Files.createDirectories(originalDir);
        Path original = originalDir.resolve("clip_87_original.mp4");
        Files.write(original, new byte[8192]);

        Path deidFile = legacyDeidFile(rawSn, "clip_87_deid.mp4");
        Files.write(deidFile, new byte[8192]);

        lenient().when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        lenient().when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        simulateCacheHit(rawSn, videoStreamService.resolveStreamMeta(rawSn));
        Files.delete(deidFile);
        createSymlinkOrSkip(deidFile, original);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-1023");

        // when / then — 206 으로도 새지 않는다
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, headers))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("스트리밍_Resource는_심링크를_따라_열지_않는다 — FrameImageService.openNoFollow 와 동일 규약")
    void noFollowFileResource_refusesToOpenSymlink() throws IOException {
        // given — 재검증을 통과했더라도(판정~open 사이 치환) open 자체가 링크를 거부해야 한다.
        Path originalDir = tempDir.resolve("nas");
        Files.createDirectories(originalDir);
        Path original = originalDir.resolve("clip_89_original.mp4");
        Files.write(original, new byte[64]);
        Files.createDirectories(deidDir);
        Path link = deidDir.resolve("clip_89_deid.mp4");
        createSymlinkOrSkip(link, original);

        var resource = new VideoStreamService.NoFollowFileResource(link, 64);

        // when / then — 링크면 바이트가 한 개도 나가지 않는다(fail-closed)
        assertThatThrownBy(resource::getInputStream).isInstanceOf(IOException.class);
        assertThat(resource.exists()).isFalse();

        // 정상 파일은 그대로 열린다(무회귀)
        Path regular = deidDir.resolve("clip_89_regular.mp4");
        Files.write(regular, new byte[64]);
        var ok = new VideoStreamService.NoFollowFileResource(regular, 64);
        assertThat(ok.exists()).isTrue();
        try (java.io.InputStream in = ok.getInputStream()) {
            assertThat(in.readAllBytes()).hasSize(64);
        }
    }

    @Test
    @DisplayName("캐시히트_정상파일은_그대로_200이고_본문은_비식별본이다 — 성능/무회귀")
    void stream_cacheHit_regularFile_stillServesDeidBytes() throws IOException {
        // given — 캐시 히트 상태(재해석 없음) + 파일은 정상 비식별본 그대로
        Long rawSn = 88L;
        Path deidFile = legacyDeidFile(rawSn, "clip_88_deid.mp4");
        byte[] masked = "MASKED-DEID-CONTENT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(deidFile, masked);

        lenient().when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        lenient().when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        simulateCacheHit(rawSn, videoStreamService.resolveStreamMeta(rawSn));

        // when
        ResponseEntity<ResourceRegion> response = videoStreamService.stream(rawSn, new HttpHeaders());

        // then — 200 + 실제로 열리는 바이트가 비식별본이어야 한다(판정 대상 == 응답 대상)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        try (java.io.InputStream in = response.getBody().getResource().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(masked);
        }
        assertThat(response.getBody().getResource().contentLength()).isEqualTo(masked.length);
    }

    @Test
    @DisplayName("206_부분응답_본문이_비식별본의_해당구간과_동일하다 — NOFOLLOW Resource 의 Range write 무회귀")
    void stream_range_bodyBytesMatchDeidSlice() throws IOException {
        // given — 식별 가능한 패턴을 가진 비식별본
        Long rawSn = 90L;
        Path deidFile = legacyDeidFile(rawSn, "clip_90_deid.mp4");
        byte[] content = new byte[4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        Files.write(deidFile, content);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=1000-1999");

        // when — 응답 region 을 ResourceRegionHttpMessageConverter 와 동일한 방식으로 write 한다
        ResourceRegion region = videoStreamService.stream(rawSn, headers).getBody();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = region.getResource().getInputStream()) {
            org.springframework.util.StreamUtils.copyRange(in, out,
                    region.getPosition(), region.getPosition() + region.getCount() - 1);
        }

        // then — 요청 구간 바이트가 정확히(그리고 비식별본에서) 나온다
        assertThat(out.toByteArray()).isEqualTo(java.util.Arrays.copyOfRange(content, 1000, 2000));
        assertThat(region.getResource().contentLength()).isEqualTo(content.length);
    }

    // ------------------------------------------------------------------
    // B-ISSUE-41 — 두 저장소 base 가 같은 온프렘 형상(/nas-storage)에서의 광역 base 축소
    //
    // STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH 는 <의도된 정상 형상>이다(CLAUDE.md).
    // 이때 읽기 허용 base 가 비식별 저장소 <전체>이면, 그 안의 원본 산출물(frames/raw/**·원본 영상)을
    // 가리키는 심링크가 lexical·realpath 판정을 모두 통과해 마스킹 전 픽셀이 200 으로 나간다.
    // 판정기(resolveRealPathUnder)는 그대로 두고, 판정기에 넘기는 <허용 범위>만 좁힌다.
    // ------------------------------------------------------------------

    /** 온프렘 정상 형상 — raw/deid 저장소 base 를 같은 디렉터리로 설정한 서비스 인스턴스. */
    private VideoStreamService sharedBaseService(Path nas) {
        VideoStreamService service = new VideoStreamService(videoRepository, streamUrlSigner,
                procLogRepository,
                ArtifactRootTestSupport.coLocate(nas, nas, nas),
                new DeidentReportGate(videoRepository));
        ReflectionTestUtils.setField(service, "storageRawPath", nas.toString());
        ReflectionTestUtils.setField(service, "deidentifiedPath", nas.toString());
        return service;
    }

    /** 공유 base 형상의 비식별 완료 영상 stub — 원본 영상은 마운트 루트(nas) 하위 절대경로. */
    private LsDataRaw sharedBaseRaw(Path nas, Long rawSn, String rawFilePathNm) {
        LsDataRaw raw = stubRaw(rawSn, rawFilePathNm);
        ReflectionTestUtils.setField(raw, "rawSn", rawSn);
        raw.markDeidentified("Y");
        return raw;
    }

    /** 지정 경로에 파일을 쓰고(상위 디렉터리 자동 생성) 그 경로를 돌려준다. */
    private static Path writeFile(Path file, int size) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
        return file;
    }

    @Test
    @DisplayName("★RAW_PATH와_DEID_PATH가_동일할_때_비식별_디렉터리_내_원본_심링크는_404다 — B-ISSUE-41")
    void stream_sharedBase_symlinkToRawArtifact_notFound() throws IOException {
        // given — 두 저장소 base 가 같은 디렉터리(/nas-storage 모사)
        Long rawSn = 41L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);

        // 같은 base 안에 원본 프레임(마스킹 전 픽셀)이 있다.
        Path rawFrame = writeFile(nas.resolve("frames/raw/41/frame-0.jpg"), 4096);
        // 비식별 영상 자리가 그 원본을 가리키는 심링크로 치환됐다(공유 마운트 공격면).
        Path deidLink = nas.resolve("videos/41/deidentified.mp4");
        Files.createDirectories(deidLink.getParent());
        createSymlinkOrSkip(deidLink, rawFrame);

        LsDataRaw raw = sharedBaseRaw(nas, rawSn, nas.resolve("clip_41.mp4").toString());
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidLink.toString())));

        // when / then — 실경로가 원본 서브트리라 허용 base 밖이다(원본 노출 금지 규약대로 404).
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("RAW_PATH와_DEID_PATH가_동일해도_정상_비식별_영상은_계속_200으로_서빙된다")
    void stream_sharedBase_normalDeidVideo_stillOk() throws IOException {
        // given — 심링크 없는 정상 비식별 산출물({deid}/videos/{rawSn}/)
        Long rawSn = 42L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);
        Path deidFile = writeFile(nas.resolve("videos/42/clip_42-mask.mp4"), 2048);

        LsDataRaw raw = sharedBaseRaw(nas, rawSn, nas.resolve("clip_42.mp4").toString());
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        // when / then
        assertThat(videoStreamService.stream(rawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("증강_파생본_경로도_동일_설정에서_정상_서빙된다 — {deid}/videos/augment/{부모}/{파생}/")
    void stream_sharedBase_augmentDerivative_ok() throws IOException {
        // given — 증강 파생본은 부모의 비식별 영상을 복사한 자기 사본을 서빙한다.
        Long parentSn = 43L;
        Long derivativeSn = 1043L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);
        Path deidFile = writeFile(
                nas.resolve(StorageSubtreePolicy.augmentVideoFile(parentSn, derivativeSn, "WINTER")), 2048);

        LsDataRaw raw = sharedBaseRaw(nas, derivativeSn, deidFile.toString());
        when(videoRepository.findById(derivativeSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(derivativeSn))
                .thenReturn(Optional.of(stubDeidLog(derivativeSn, deidFile.toString())));

        // when / then
        assertThat(videoStreamService.stream(derivativeSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("해상도파생본_경로도_동일_설정에서_정상_서빙된다 — {deid}/videos/resolution/{부모}/{파생}/")
    void stream_sharedBase_resolutionDerivative_ok() throws IOException {
        // given
        Long parentSn = 44L;
        Long derivativeSn = 1044L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);
        Path deidFile = writeFile(
                nas.resolve(StorageSubtreePolicy.resolutionVideoFile(parentSn, derivativeSn, "RESL_720P")),
                2048);

        LsDataRaw raw = sharedBaseRaw(nas, derivativeSn, deidFile.toString());
        when(videoRepository.findById(derivativeSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(derivativeSn))
                .thenReturn(Optional.of(stubDeidLog(derivativeSn, deidFile.toString())));

        // when / then
        assertThat(videoStreamService.stream(derivativeSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("파생본_구_규약_경로도_동일_설정에서_정상_서빙된다 — 파생 RAW_SN 키 도입 전 잔존 행")
    void stream_sharedBase_legacyDerivativePath_ok() throws IOException {
        // given — 구 규약({deid}/videos/{augment|resolution}/{부모}/{프리셋}.mp4) 잔존 행
        Long derivativeSn = 1045L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);
        Path deidFile = writeFile(nas.resolve("videos/resolution/45/RESL_480P.mp4"), 2048);

        LsDataRaw raw = sharedBaseRaw(nas, derivativeSn, deidFile.toString());
        when(videoRepository.findById(derivativeSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(derivativeSn))
                .thenReturn(Optional.of(stubDeidLog(derivativeSn, deidFile.toString())));

        // when / then
        assertThat(videoStreamService.stream(derivativeSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // B-ISSUE-41 CRITICAL 보강 — <b>중간 디렉터리 자체</b>가 base <b>안의 다른 위치</b>를 가리키는 심링크
    // (CWE-59/706, security-reviewer --deep)
    //
    // 위 좁히기(광역 base 제거)는 취약점을 <축소>했을 뿐 해소하지 못했다. 허용 base 를 만드는
    // resolveUnder 의 내부 판정이 여전히 realOrNearest(target).startsWith(realOrNearest(base)) 이고
    // 그 base 가 광역 deidentifiedBase 이기 때문이다. STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH
    // (온프렘 정상 형상)에서는 {deid}/videos/{rawSn} <디렉터리 자체>를 같은 base 안의
    // frames/raw/** 로 향하는 심링크로 바꿔도 실경로가 여전히 base 하위라 판정을 통과한다 —
    // target 과 base 가 <같은 심링크>를 거쳐 접히므로 startsWith 가 자기참조(rubber-stamp)가 된다.
    // 이후 resolveSafe → resolveRealPathUnder 도 같은 이유로 항등식처럼 통과한다.
    //
    // 위협 현실성: KPST(외부 비식별 벤더)가 공유 마운트의 videos/{rawSn}/ 에 <직접> 산출한다 —
    // 바로 그 지점이 신뢰 경계이며, 이 디렉터리가 교체되면 마스킹 전 원본이 200 으로 서빙된다.
    //
    // 판정 축을 "base 대비 startsWith" 에서 "실경로의 base 기준 상대경로가 기대 세그먼트 시퀀스와
    // <정확히> 일치하는가"(StorageSubtreePolicy.isExactSegmentPath)로 바꿔 닫는다.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★동일경로_형상에서_rawSn_디렉터리_자체가_심링크로_같은_base_안_원본프레임을_가리키면_거부된다")
    void stream_sharedBase_rawSnDirItselfIsSymlinkInsideBase_notFound() throws IOException {
        // given — 온프렘 정상 형상(raw==deid=/nas-storage)
        Long rawSn = 45L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);

        // 같은 base 안의 원본 프레임 디렉터리(마스킹 전 픽셀) — 심링크가 가리킬 표적
        Path rawFrameDir = Files.createDirectories(nas.resolve("frames/raw/945"));
        writeFile(rawFrameDir.resolve("deidentified.mp4"), 4096);

        // 비식별 영상 <디렉터리 자체>가 그 원본 서브트리를 가리키는 심링크로 교체됐다.
        //   (파일 1개가 아니라 KPST 산출 디렉터리 통째 — 공유 마운트 신뢰경계 지점)
        Files.createDirectories(nas.resolve("videos"));
        Path linkedDir = nas.resolve("videos").resolve(String.valueOf(rawSn));
        createSymlinkOrSkip(linkedDir, rawFrameDir);

        LsDataRaw raw = sharedBaseRaw(nas, rawSn, nas.resolve("clip_45.mp4").toString());
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn,
                        linkedDir.resolve("deidentified.mp4").toString())));

        // when / then — 실경로가 videos/{rawSn} 이 아니라 frames/raw/945 다 → 세그먼트 불일치로 거부.
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("★동일경로_형상에서_videos_augment_세그먼트_자체가_심링크면_거부된다")
    void stream_sharedBase_augmentSegmentItselfIsSymlinkInsideBase_notFound() throws IOException {
        // given — 파생 서브트리 세그먼트도 구조적으로 동일한 취약점 클래스를 공유한다.
        Long derivativeSn = 1046L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);

        Path rawFrameDir = Files.createDirectories(nas.resolve("frames/raw/946"));
        writeFile(rawFrameDir.resolve("WINTER.mp4"), 4096);

        Files.createDirectories(nas.resolve("videos"));
        Path linkedDir = nas.resolve("videos").resolve(StorageSubtreePolicy.SEG_AUGMENT);
        createSymlinkOrSkip(linkedDir, rawFrameDir);

        LsDataRaw raw = sharedBaseRaw(nas, derivativeSn, nas.resolve("clip_46.mp4").toString());
        when(videoRepository.findById(derivativeSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(derivativeSn))
                .thenReturn(Optional.of(stubDeidLog(derivativeSn,
                        linkedDir.resolve("WINTER.mp4").toString())));

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(derivativeSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("★동일경로_형상에서_videos_resolution_세그먼트_자체가_심링크면_거부된다")
    void stream_sharedBase_resolutionSegmentItselfIsSymlinkInsideBase_notFound() throws IOException {
        // given
        Long derivativeSn = 1047L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);

        Path rawFrameDir = Files.createDirectories(nas.resolve("frames/raw/947"));
        writeFile(rawFrameDir.resolve("RESL_720P.mp4"), 4096);

        Files.createDirectories(nas.resolve("videos"));
        Path linkedDir = nas.resolve("videos").resolve(StorageSubtreePolicy.SEG_RESOLUTION);
        createSymlinkOrSkip(linkedDir, rawFrameDir);

        LsDataRaw raw = sharedBaseRaw(nas, derivativeSn, nas.resolve("clip_47.mp4").toString());
        when(videoRepository.findById(derivativeSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(derivativeSn))
                .thenReturn(Optional.of(stubDeidLog(derivativeSn,
                        linkedDir.resolve("RESL_720P.mp4").toString())));

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(derivativeSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("★동일경로_형상에서_co_locate_rawSn_디렉터리_자체가_심링크면_거부된다")
    void stream_sharedBase_coLocateRootDirItselfIsSymlinkInsideBase_notFound() throws IOException {
        // given — co-locate 축도 동일하다. {dirname(원본)}/{rawSn} 이 광역 base(dirname=nas) 기준
        //         startsWith 로만 검증되므로, 그 디렉터리를 같은 base 안 원본 서브트리로 돌려도 통과했다.
        Long rawSn = 48L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);

        Path rawFrameDir = Files.createDirectories(nas.resolve("frames/raw/948"));
        Files.createDirectories(rawFrameDir.resolve(kr.co.cudo.authoring.common.storage
                .VideoArtifactRootResolver.SEG_DEID));
        writeFile(rawFrameDir.resolve("deid/clip_48-mask.mp4"), 4096);

        Path linkedRoot = nas.resolve(String.valueOf(rawSn));
        createSymlinkOrSkip(linkedRoot, rawFrameDir);

        LsDataRaw raw = sharedBaseRaw(nas, rawSn, nas.resolve("clip_48.mp4").toString());
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn,
                        linkedRoot.resolve("deid/clip_48-mask.mp4").toString())));

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("★동일경로_형상에서_co_locate_deid_진입점_자체가_심링크면_거부된다")
    void stream_sharedBase_coLocateDeidEntryIsSymlinkInsideBase_notFound() throws IOException {
        // given — {rawSn}/deid 진입점(외부 벤더가 직접 쓰는 디렉터리)만 교체한 변형.
        Long rawSn = 49L;
        Path nas = Files.createDirectories(tempDir.resolve("nas-storage"));
        videoStreamService = sharedBaseService(nas);

        Path rawFrameDir = Files.createDirectories(nas.resolve("frames/raw/949"));
        writeFile(rawFrameDir.resolve("clip_49-mask.mp4"), 4096);

        Path videoRoot = Files.createDirectories(nas.resolve(String.valueOf(rawSn)));
        Path linkedDeid = videoRoot.resolve(kr.co.cudo.authoring.common.storage
                .VideoArtifactRootResolver.SEG_DEID);
        createSymlinkOrSkip(linkedDeid, rawFrameDir);

        LsDataRaw raw = sharedBaseRaw(nas, rawSn, nas.resolve("clip_49.mp4").toString());
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn,
                        linkedDeid.resolve("clip_49-mask.mp4").toString())));

        // when / then
        assertThatThrownBy(() -> videoStreamService.stream(rawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("정상_비식별파일은_심링크가드_도입후에도_그대로_200 — 무회귀")
    void stream_regularDeidFile_stillOk_afterSymlinkGuard() throws IOException {
        // given — 심링크가 아닌 실파일(정상 산출물)
        Long rawSn = 84L;
        Path deidFile = legacyDeidFile(rawSn, "clip_84_deid.mp4");
        Files.write(deidFile, new byte[4096]);

        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(stubDeidLog(rawSn, deidFile.toString())));

        // when / then — 200 + 실제로 연 경로는 판정을 통과한 실경로여야 한다(판정대상==사용대상).
        assertThat(videoStreamService.stream(rawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(videoStreamService.resolveStreamMeta(rawSn).path())
                .isEqualTo(deidFile.toRealPath());
    }

    // ===================== API-114 — 스트림 주소는 배포 접두를 포함하지 않는다 =====================

    @Test
    @DisplayName("스트림주소는_배포접두_없는_API_기준경로로_시작한다(API-114)")
    void issueSignedUrl_startsWithApiBasePath() {
        Long rawSn = 12L;
        String nonce = "0123456789abcdef0123456789abcdef";
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(streamUrlSigner.isConfigured()).thenReturn(true);
        when(streamUrlSigner.sign(rawSn, "1", nonce))
                .thenReturn(new StreamUrlSigner.SignedParams(1_700_000_000L, "deadbeef", 60L));

        assertThat(videoStreamService.issueSignedUrl(rawSn, "1", nonce).url())
                .isEqualTo("/api/v1/videos/12/stream?exp=1700000000&u=1&sig=deadbeef");
    }

    /**
     * ★회귀 — 이미지 주소용 접두({@link PublicApiPath})가 WAR 컨텍스트를 포함한 값({@code /label-studio/api/v1})을
     * 내는 배포에서도 스트림 주소는 접두 없는 {@code /api/v1/...} 여야 한다. 화면이 배포 접두를 한 번 더 붙이므로,
     * 서버가 컨텍스트를 붙이면 {@code /label-studio/label-studio/...} 이중 접두가 된다(온프렘 실측 결함).
     *
     * <p>생성자 직접 인스턴스화로는 스프링 주입 경로(필드 {@code @Autowired})를 재현할 수 없으므로, 실제 빈 두 개를
     * 작은 컨텍스트에 올려 <b>주입이 일어나는 형상</b>에서 확인한다 — 누군가 {@code PublicApiPath} 주입을 되살리면
     * 이 시험이 잡는다.
     */
    @Test
    @DisplayName("★컨텍스트_포함_접두가_설정된_배포에서도_스트림주소는_api_v1로_시작한다(API-114 이중접두 회귀)")
    void issueSignedUrl_ignoresContextIncludingPublicApiPath() {
        Long rawSn = 13L;
        String nonce = "0123456789abcdef0123456789abcdef";
        VideoRepository repo = org.mockito.Mockito.mock(VideoRepository.class);
        StreamUrlSigner signer = org.mockito.Mockito.mock(StreamUrlSigner.class);
        when(repo.findById(rawSn)).thenReturn(Optional.of(deidReadyRaw(rawSn)));
        when(signer.isConfigured()).thenReturn(true);
        when(signer.sign(rawSn, "1", nonce))
                .thenReturn(new StreamUrlSigner.SignedParams(1_700_000_000L, "deadbeef", 60L));

        new ApplicationContextRunner()
                .withPropertyValues(PublicApiPathDefaults.PROPERTY_KEY + "=/label-studio/api/v1")
                .withBean(PublicApiPath.class)
                .withBean(VideoRepository.class, () -> repo)
                .withBean(StreamUrlSigner.class, () -> signer)
                .withBean(LsDeidentProcLogRepository.class,
                        () -> org.mockito.Mockito.mock(LsDeidentProcLogRepository.class))
                .withBean(VideoArtifactRootResolver.class,
                        () -> org.mockito.Mockito.mock(VideoArtifactRootResolver.class))
                .withBean(DeidentReportGate.class, () -> new DeidentReportGate(repo))
                .withBean(VideoStreamService.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // 전제 확인 — 이 배포의 이미지용 접두는 컨텍스트를 포함한다(시험이 공허하지 않음).
                    assertThat(ctx.getBean(PublicApiPath.class).prefix()).isEqualTo("/label-studio/api/v1");

                    String url = ctx.getBean(VideoStreamService.class)
                            .issueSignedUrl(rawSn, "1", nonce).url();
                    assertThat(url).startsWith("/api/v1/videos/13/stream?");
                    assertThat(url).doesNotContain("label-studio");
                });
    }
}
