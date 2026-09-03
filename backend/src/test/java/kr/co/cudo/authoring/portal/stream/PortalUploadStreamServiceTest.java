package kr.co.cudo.authoring.portal.stream;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalStreamUrlResponse;
import kr.co.cudo.authoring.portal.service.PortalStoragePathGuard;
import kr.co.cudo.authoring.portal.service.PortalStreamUrlSigner;
import kr.co.cudo.authoring.portal.service.PortalUploadStreamService;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 포털 업로드 영상 재생 — 소유자 판정·구간 요청·캐시 금지.
 *
 * <p>★ 이 경로는 <b>사용자가 올린 원본</b>을 서빙한다. 내부 채널의 「가려진 사본만 서빙하고 없으면
 * 감춘다」 규칙을 옮겨 오면 이 경로에는 그 사본이 없어 <b>정상 자산이 전부 감춰진다</b>.
 */
class PortalUploadStreamServiceTest {

    private static final long ULD_SN = 501L;
    private static final String OWNER = "portal-user-1";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path storageDir;

    private PortalUploadAssetRepository assetRepository;
    private PortalUploadStreamService service;
    private Path video;

    @BeforeEach
    void setUp() throws Exception {
        assetRepository = mock(PortalUploadAssetRepository.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        service = new PortalUploadStreamService(assetRepository,
                new PortalStoragePathGuard(props), new PortalStreamUrlSigner(SECRET, 120L));
        video = Files.write(storageDir.resolve("v.mp4"), new byte[4096]);
    }

    private PortalUploadAsset asset(String type, String path) {
        return new PortalUploadAsset(ULD_SN, OWNER, type, "v.mp4", path, 4096L, "video/mp4",
                PortalUploadLedger.STATUS_UPLOADED, 60.0, 30.0, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private void given(PortalUploadAsset asset) {
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.of(asset));
    }

    // ======================== 인가 ========================

    @Test
    @DisplayName("★남의_자산과_없는_자산이_같은_코드로_거절된다")
    void foreignAndMissingShareTheSameCode() {
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stream(ULD_SN, OWNER, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.issueSignedUrl(ULD_SN, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("영상이_아닌_자산은_400")
    void nonVideoIsInvalidInput() {
        given(asset(PortalUploadLedger.TYPE_IMAGE, video.toString()));

        assertThatThrownBy(() -> service.stream(ULD_SN, OWNER, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ======================== 스트리밍 ========================

    /**
     * ★ 상태를 묻지 않는다 — 마킹이 추출보다 앞서므로 <b>추출 전에도</b> 재생할 수 있어야 한다.
     * 자산 상태는 마킹 대기(= 추출 전)로 두었다.
     */
    @Test
    @DisplayName("★추출_전_자산도_재생된다 — 상태를_조건으로_걸지_않는다")
    void streamsBeforeExtraction() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, video.toString()));

        ResponseEntity<ResourceRegion> res = service.stream(ULD_SN, OWNER, new HttpHeaders());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        // 본인 자산이라도 중간 저장소에 남기지 않는다.
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getCount()).isEqualTo(4096L);
    }

    @Test
    @DisplayName("구간_요청은_206으로_그_구간만_내려보낸다")
    void rangeRequestReturnsPartialContent() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, video.toString()));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=100-199");

        ResponseEntity<ResourceRegion> res = service.stream(ULD_SN, OWNER, headers);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getPosition()).isEqualTo(100L);
        assertThat(res.getBody().getCount()).isEqualTo(100L);
    }

    @Test
    @DisplayName("범위_밖_구간_요청은_416")
    void outOfBoundsRangeIs416() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, video.toString()));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=999999-");

        ResponseEntity<ResourceRegion> res = service.stream(ULD_SN, OWNER, headers);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }

    @Test
    @DisplayName("파일이_아직_없으면_404 — 재생을_막는_것은_이_경우_하나뿐이다")
    void missingFileIsNotFound() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, storageDir.resolve("gone.mp4").toString()));

        assertThatThrownBy(() -> service.stream(ULD_SN, OWNER, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("저장_경로_밖을_가리키는_자산은_같은_403으로_거절된다")
    void pathOutsideBaseIsForbidden() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, "/etc/passwd"));

        assertThatThrownBy(() -> service.stream(ULD_SN, OWNER, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ======================== 서명 주소 발급 ========================

    @Test
    @DisplayName("서명_주소는_그_자산의_스트림_창구를_가리키고_만료_정보를_함께_준다")
    void issuesSignedUrl() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, video.toString()));

        PortalStreamUrlResponse res = service.issueSignedUrl(ULD_SN, OWNER);

        assertThat(res.url()).contains("/v1/portal/uploads/" + ULD_SN + "/stream");
        assertThat(res.url()).contains("sig=").contains("exp=").contains("u=");
        assertThat(res.ttlSeconds()).isPositive();
        assertThat(res.expiresAt()).isPositive();
    }

    /**
     * ★ 설정이 없으면 서명 없이 내주지 않는다 — 없는 채로 열면 아무나 재생할 수 있는 주소가 나간다.
     */
    @Test
    @DisplayName("★시크릿_미설정이면_503 — 서명_없는_주소를_내주지_않는다")
    void unconfiguredSignerIsServiceUnavailable() {
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        PortalUploadStreamService unset = new PortalUploadStreamService(assetRepository,
                new PortalStoragePathGuard(props), new PortalStreamUrlSigner("", 120L));
        given(asset(PortalUploadLedger.TYPE_VIDEO, video.toString()));

        assertThatThrownBy(() -> unset.issueSignedUrl(ULD_SN, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("재생할_파일이_없으면_주소를_내주지_않는다 — 화면이_만료로_오인해_재발급을_되풀이한다")
    void refusesUrlWhenFileMissing() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, storageDir.resolve("gone.mp4").toString()));

        assertThatThrownBy(() -> service.issueSignedUrl(ULD_SN, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
