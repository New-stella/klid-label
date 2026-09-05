package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalUploadResponse;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.dto.PortalUploadDetailResponse;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V107 포털 업로드 서비스 단위 테스트 — HIGH 시나리오(all-or-nothing, 고아 파일 방지, IDOR, 삭제 순서,
 * 경로조작, 매직바이트/확장자 정합) 방어를 리포지토리 Mockito + {@link TempDir} 실디스크로 결정적 검증.
 */
class PortalUploadServiceTest {

    private static final String ALICE = "alice";
    /** SOI(FF D8 FF) + APP0 + EOI(FF D9) — 시그니처와 파일끝 EOI 모두 유효한 최소 JPEG. */
    private static final byte[] JPEG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, (byte) 0xFF, (byte) 0xD9};
    /** 8바이트 시그니처 + IHDR 청크(길이 13 + "IHDR") — 시그니처와 IHDR 모두 유효한 최소 PNG. */
    private static final byte[] PNG_HEAD = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @TempDir Path storageDir;

    private PortalUploadAssetRepository assetRepository;
    private PortalUploadFrameRepository frmeRepository;
    private SystemConfigService systemConfigService;
    private PortalUploadService service;
    private final AtomicLong uldSeq = new AtomicLong(100);
    private final AtomicLong frmeSeq = new AtomicLong(500);
    /** 적재된 자산 스냅샷 — 흡수 뒤 적재는 식별자만 돌려주므로 조회 stub 이 이 표를 읽는다. */
    private final Map<Long, PortalUploadAsset> inserted = new HashMap<>();

    @BeforeEach
    void setUp() {
        assetRepository = mock(PortalUploadAssetRepository.class);
        frmeRepository = mock(PortalUploadFrameRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4", "mov", "avi"), storageDir.toString(),
                List.of("jpg", "jpeg", "png"), 1024L, 3, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        service = new PortalUploadService(assetRepository, frmeRepository, props,
                new PortalRetentionPolicy(systemConfigService),
                new kr.co.cudo.authoring.portal.service.PortalStoragePathGuard(props));

        // 적재는 <식별자>만 돌려준다 — 자산은 영상 원장 + 메타 원장에 흩어져 앉기 때문이다.
        when(assetRepository.insertUploaded(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            long sn = uldSeq.incrementAndGet();
            String owner = inv.getArgument(0);
            String path = inv.getArgument(1);
            String orgnlNm = inv.getArgument(2);
            String mime = inv.getArgument(3);
            Long size = inv.getArgument(4);
            inserted.put(sn, new PortalUploadAsset(sn, owner,
                    PortalUploadLedger.assetTypeOf(mime), orgnlNm, path, size, mime,
                    PortalUploadLedger.STATUS_READY, null, null, 1, null,
                    LocalDateTime.now(), LocalDateTime.now()));
            return sn;
        });
        when(assetRepository.findByOwner(anyLong(), eq(ALICE)))
                .thenAnswer(inv -> Optional.ofNullable(inserted.get((Long) inv.getArgument(0))));
        when(frmeRepository.save(any(LsDataSrc.class))).thenAnswer(inv -> {
            LsDataSrc f = inv.getArgument(0);
            setField(f, "srcSn", frmeSeq.incrementAndGet());
            return f;
        });
    }

    private MockMultipartFile img(String name, byte[] head) {
        return new MockMultipartFile("files", name, "image/jpeg", head);
    }

    // ======================== 업로드 정상 ========================

    @Test
    @DisplayName("이미지_다중_업로드_성공시_READY상태와_파일기록")
    void multiUploadSuccess() {
        List<MultipartFile> files = List.of(
                img("a.jpg", JPEG_HEAD), img("b.png", PNG_HEAD));

        List<PortalUploadResponse> res = service.uploadImages(ALICE, files);

        assertThat(res).hasSize(2);
        assertThat(res).allSatisfy(r -> {
            assertThat(r.uldSttsCd()).isEqualTo(PortalUploadLedger.STATUS_READY);
            assertThat(r.uldTypeCd()).isEqualTo(PortalUploadLedger.TYPE_IMAGE);
            assertThat(r.frmeCnt()).isEqualTo(1);
            assertThat(r.frmeSn()).isNotNull();
        });
        assertThat(res.get(0).mimeTypeNm()).isEqualTo("image/jpeg");
        assertThat(res.get(1).mimeTypeNm()).isEqualTo("image/png");
        // 저장 파일 2건 존재 (UUID 파일명).
        assertThat(regularFilesUnder(storageDir)).hasSize(2);
    }

    // ======================== all-or-nothing / 검증 400 ========================

    @Test
    @DisplayName("허용되지_않은_확장자_업로드시_저장_0건")
    void disallowedExtensionRejected() {
        assertBadRequestNoSave(List.of(img("a.txt", JPEG_HEAD)));
    }

    @Test
    @DisplayName("확장자는_png인데_매직바이트가_JPEG면_저장_0건")
    void extensionMagicMismatchRejected() {
        assertBadRequestNoSave(List.of(img("a.png", JPEG_HEAD)));
    }

    @Test
    @DisplayName("SVG_시그니처_파일은_png_확장자여도_저장_0건")
    void svgSignatureRejected() {
        assertBadRequestNoSave(List.of(img("evil.png", "<svg xmlns=".getBytes())));
    }

    @Test
    @DisplayName("크기_제한_초과시_저장_0건")
    void oversizeRejected() {
        byte[] big = new byte[2048]; // > maxImageSizeBytes(1024)
        System.arraycopy(PNG_HEAD, 0, big, 0, PNG_HEAD.length);
        assertBadRequestNoSave(List.of(img("big.png", big)));
    }

    @Test
    @DisplayName("요청당_개수_초과시_저장_0건")
    void tooManyImagesRejected() {
        List<MultipartFile> files = Stream.of("a", "b", "c", "d")
                .map(n -> (MultipartFile) img(n + ".jpg", JPEG_HEAD)).toList();
        assertBadRequestNoSave(files);
    }

    @Test
    @DisplayName("빈_목록_업로드시_400")
    void emptyListRejected() {
        assertBadRequestNoSave(List.of());
    }

    @Test
    @DisplayName("빈_파일_0바이트_업로드시_저장_0건")
    void emptyFileRejected() {
        assertBadRequestNoSave(List.of(img("a.jpg", new byte[0])));
    }

    @Test
    @DisplayName("다중_업로드_중_1건_불량시_전체_거부되고_저장_0건")
    void oneBadRejectsAll() {
        List<MultipartFile> files = List.of(
                img("good.jpg", JPEG_HEAD), img("bad.txt", JPEG_HEAD));
        assertBadRequestNoSave(files);
    }

    private void assertBadRequestNoSave(List<MultipartFile> files) {
        assertThatThrownBy(() -> service.uploadImages(ALICE, files))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(assetRepository, never()).insertUploaded(any(), any(), any(), any(), any());
        verify(frmeRepository, never()).save(any());
        assertThat(regularFilesUnder(storageDir)).isEmpty();
    }

    // ======================== 고아 파일 방지 (#4) ========================

    @Test
    @DisplayName("DB_저장_실패시_기록된_파일이_남지_않음")
    void orphanFileRemovedOnDbFailure() {
        when(frmeRepository.save(any(LsDataSrc.class)))
                .thenThrow(new RuntimeException("insert failed"));
        List<MultipartFile> files = List.of(img("a.jpg", JPEG_HEAD));

        assertThatThrownBy(() -> service.uploadImages(ALICE, files))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // write 후 INSERT 실패 → 보상 삭제로 고아 파일 0.
        assertThat(regularFilesUnder(storageDir)).isEmpty();
    }

    // ======================== FAILED 사유 노출 (DTO 매핑) ========================

    @Test
    @DisplayName("FAILED_자산_목록응답에_실패사유_노출")
    void listResponseCarriesFailReason() {
        PortalUploadAsset uld = assetOf(1L, PortalUploadLedger.STATUS_FAILED, REG_DT, REG_DT,
                "video/mp4", "프레임 추출 실패: RuntimeException");

        PortalUploadResponse res = PortalUploadResponse.from(uld);

        assertThat(res.uldSttsCd()).isEqualTo(PortalUploadLedger.STATUS_FAILED);
        assertThat(res.failRsnCn()).isEqualTo("프레임 추출 실패: RuntimeException");
    }

    @Test
    @DisplayName("READY_자산_응답의_실패사유는_null")
    void readyResponseHasNoFailReason() {
        PortalUploadAsset uld = asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT);

        assertThat(PortalUploadResponse.from(uld).failRsnCn()).isNull();
    }

    // ======================== IDOR (#7) ========================

    @Test
    @DisplayName("타사용자_자산_상세_조회시_403")
    void detailForbiddenForNonOwner() {
        when(assetRepository.findByOwner(9L, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.getUpload(9L, ALICE));
    }

    @Test
    @DisplayName("타사용자_프레임_이미지_조회시_403")
    void frameImageForbiddenForNonOwner() {
        when(frmeRepository.findByOwner(9L, ALICE, PortalUploadLedger.SRC_TYPE))
                .thenReturn(Optional.empty());
        assertForbidden(() -> service.serveFrameImage(9L, ALICE));
    }

    @Test
    @DisplayName("타사용자_삭제시_403")
    void deleteForbiddenForNonOwner() {
        when(assetRepository.findByOwner(9L, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.deleteUpload(9L, ALICE));
        verify(assetRepository, never()).deleteOwned(anyLong(), anyString());
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ======================== 삭제 순서 (#8) ========================

    @Test
    @DisplayName("삭제시_파일과_DB행이_함께_제거됨")
    void deleteRemovesFileAndRow() throws Exception {
        // given — 실제 파일 + 소유 자산/프레임 mock.
        Path imagesDir = storageDir.resolve("images");
        Files.createDirectories(imagesDir);
        Path file = imagesDir.resolve("del.jpg");
        Files.write(file, JPEG_HEAD);

        seedOwnedAsset(42L, file, "image/jpeg");

        // when
        service.deleteUpload(42L, ALICE);

        // then — 파일 삭제 + 자산·자식 행 삭제 호출(소유자 조건이 실행문에 걸린다).
        assertThat(Files.exists(file)).isFalse();
        verify(assetRepository).deleteOwned(42L, ALICE);
    }

    @Test
    @DisplayName("파일삭제_실패시_DB행_보존")
    void deleteFileFailureKeepsDbRow() throws Exception {
        // given — 프레임 파일 경로를 "비어있지 않은 디렉토리"로 지정 → deleteIfExists 가 IOException.
        Path imagesDir = storageDir.resolve("images");
        Files.createDirectories(imagesDir);
        Path nonEmptyDir = imagesDir.resolve("busy");
        Files.createDirectories(nonEmptyDir);
        Files.write(nonEmptyDir.resolve("child.bin"), new byte[]{1}); // 자식 있어 삭제 불가.

        seedOwnedAsset(77L, nonEmptyDir, "image/jpeg");

        // when/then — 파일 삭제 실패 → 5xx + DB 행 보존(삭제 미호출).
        assertThatThrownBy(() -> service.deleteUpload(77L, ALICE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        verify(assetRepository, never()).deleteOwned(anyLong(), anyString());
    }

    // ======================== 서빙 파일 부재 (#6) ========================

    @Test
    @DisplayName("프레임파일_디스크_부재시_이미지서빙_404")
    void serveMissingFileReturns404() {
        // given — DB 행은 있으나 물리 파일 없음.
        Path ghost = storageDir.resolve("images").resolve("ghost.png");
        seedOwnedAsset(55L, ghost, "image/png");
        LsDataSrc frme = LsDataSrc.create(55L, 0L, ghost.toString(), null);
        setField(frme, "srcSn", 555L);
        when(frmeRepository.findByOwner(555L, ALICE, PortalUploadLedger.SRC_TYPE))
                .thenReturn(Optional.of(frme));

        // when/then — 500 이 아닌 NOT_FOUND.
        assertThatThrownBy(() -> service.serveFrameImage(555L, ALICE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ======================== 타입 필터 (#입력검증) ========================

    @Test
    @DisplayName("목록조회_type필터_IMAGE만_반환")
    void listUploadsTypeFilterImage() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<PortalUploadAsset> empty = new PageImpl<>(List.of(), pageable, 0);
        when(assetRepository.findPageByOwner(eq(ALICE), any(), any())).thenReturn(empty);

        service.listUploads(ALICE, "image", pageable); // 소문자 → 대문자 정규화.

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(assetRepository).findPageByOwner(eq(ALICE), typeCaptor.capture(), eq(pageable));
        assertThat(typeCaptor.getValue()).isEqualTo("IMAGE");
    }

    @Test
    @DisplayName("미지원_type_필터시_400")
    void listUploadsUnknownTypeRejected() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThatThrownBy(() -> service.listUploads(ALICE, "EXE", pageable))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(assetRepository, never()).findPageByOwner(any(), any(), any());
    }

    // ======================== 보존기간 만료 예정 시각 (AC-033 / AC-037 / DFEAT-055) ========================

    private static final LocalDateTime REG_DT = LocalDateTime.of(2026, 8, 1, 10, 0);

    /** 상태·시각을 지정한 자산 1건. 흡수 뒤 자산은 <읽기 모델>이라 그대로 만들면 된다. */
    private PortalUploadAsset asset(long uldSn, String status, LocalDateTime regDt,
                                    LocalDateTime sttsChgDt) {
        return assetOf(uldSn, status, regDt, sttsChgDt, "image/jpeg", null);
    }

    private PortalUploadAsset assetOf(long uldSn, String status, LocalDateTime regDt,
                                      LocalDateTime sttsChgDt, String mime, String failReason) {
        return new PortalUploadAsset(uldSn, ALICE, PortalUploadLedger.assetTypeOf(mime),
                "a.jpg", "/x", 8L, mime, status, null, null, 1, failReason, regDt, sttsChgDt);
    }

    /** 소유 자산 1건 + 그 자산의 파일 경로 — 삭제·서빙 시나리오 공통 픽스처. */
    private void seedOwnedAsset(long uldSn, Path filePath, String mime) {
        PortalUploadAsset asset = new PortalUploadAsset(uldSn, ALICE,
                PortalUploadLedger.assetTypeOf(mime), "x", filePath.toString(), 8L, mime,
                PortalUploadLedger.STATUS_READY, null, null, 1, null,
                LocalDateTime.now(), LocalDateTime.now());
        inserted.put(uldSn, asset);
        // 경로 수집(프레임 + 원본, 중복 제거)은 리포지토리가 소유한다.
        when(assetRepository.findFilePaths(uldSn)).thenReturn(List.of(filePath.toString()));
    }

    private void stubRetentionDays(Integer ready, Integer failed) {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(ready);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(failed);
    }

    /** 라벨 마지막 저장일 집계 결과 stub — 인자로 준 자산만 라벨을 가진다. */
    private void stubLastLabelSavedAt(Object... uldSnThenTime) {
        Map<Long, LocalDateTime> rows = new HashMap<>();
        for (int i = 0; i < uldSnThenTime.length; i += 2) {
            rows.put((Long) uldSnThenTime[i], (LocalDateTime) uldSnThenTime[i + 1]);
        }
        when(assetRepository.findLastLabelSavedAt(eq(ALICE), any())).thenReturn(rows);
    }

    private List<PortalUploadResponse> listAll(PortalUploadAsset... assets) {
        Pageable pageable = PageRequest.of(0, 20);
        when(assetRepository.findPageByOwner(eq(ALICE), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(assets), pageable, assets.length));
        return service.listUploads(ALICE, null, pageable).getContent();
    }

    @Test
    @DisplayName("READY_자산_라벨이_없으면_등록일_기준으로_만료예정시각이_계산된다")
    void readyExpiryFromRegDtWhenNoLabel() {
        // given: READY, 라벨 0건, 보존기간 7일
        stubRetentionDays(7, 1);
        stubLastLabelSavedAt();

        // when
        List<PortalUploadResponse> res = listAll(asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT));

        // then: 등록일 + 7일
        assertThat(res.get(0).expiresAt()).isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("READY_자산_라벨_저장일이_등록일보다_늦으면_라벨_저장일이_기준이_된다")
    void readyExpiryUsesLaterLabelSavedAt() {
        // given: 등록 후 3일 뒤 라벨 저장
        stubRetentionDays(7, 1);
        LocalDateTime labelSavedAt = REG_DT.plusDays(3);
        stubLastLabelSavedAt(1L, labelSavedAt);

        // when
        List<PortalUploadResponse> res = listAll(asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT));

        // then: 늦은 쪽(라벨 저장일) + 7일 — 작업 중이면 만료가 계속 밀린다
        assertThat(res.get(0).expiresAt()).isEqualTo(labelSavedAt.plusDays(7));
    }

    @Test
    @DisplayName("READY_자산_라벨_저장일이_등록일보다_이르면_등록일이_기준이_된다")
    void readyExpiryUsesLaterRegDt() {
        // given: 라벨 저장일이 등록일보다 과거(데이터 이관 등) — 늦은 쪽은 등록일
        stubRetentionDays(7, 1);
        stubLastLabelSavedAt(1L, REG_DT.minusDays(2));

        // when
        List<PortalUploadResponse> res = listAll(asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT));

        // then
        assertThat(res.get(0).expiresAt()).isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("FAILED_자산은_전이시각_기준_단축_보존기간이며_같은시각_READY_자산과_독립_판정된다")
    void failedExpiryIsIndependentFromReady() {
        // given: 같은 시각 등록. failed 는 mdfcnDt(전이 시각)이 기준 — AC-037 and_examples[1]
        stubRetentionDays(7, 1);
        stubLastLabelSavedAt();
        LocalDateTime failedAt = REG_DT.plusHours(5);

        // when
        List<PortalUploadResponse> res = listAll(
                asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT),
                asset(2L, PortalUploadLedger.STATUS_FAILED, REG_DT, failedAt));

        // then: 두 축이 서로 다른 기준점·기간으로 계산된다
        assertThat(res.get(0).expiresAt()).isEqualTo(REG_DT.plusDays(7));
        assertThat(res.get(1).expiresAt()).isEqualTo(failedAt.plusDays(1));
    }

    @Test
    @DisplayName("PROCESSING과_UPLOADED_자산은_삭제_대상이_아니므로_만료예정시각이_null이다")
    void nonDeletableStatusesHaveNoExpiry() {
        // given
        stubRetentionDays(7, 1);
        stubLastLabelSavedAt();

        // when
        List<PortalUploadResponse> res = listAll(
                asset(1L, PortalUploadLedger.STATUS_PROCESSING, REG_DT, REG_DT),
                asset(2L, PortalUploadLedger.STATUS_UPLOADED, REG_DT, REG_DT));

        // then
        assertThat(res.get(0).expiresAt()).isNull();
        assertThat(res.get(1).expiresAt()).isNull();
    }

    @Test
    @DisplayName("보존기간_설정을_7에서_14로_바꾸고_다시_조회하면_만료예정시각이_갱신된다_AC033")
    void expiryIsRecomputedWhenRetentionSettingChanges() {
        // given: 7일로 한 번 조회
        stubRetentionDays(7, 1);
        stubLastLabelSavedAt();
        PortalUploadAsset ready = asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT);
        assertThat(listAll(ready).get(0).expiresAt()).isEqualTo(REG_DT.plusDays(7));

        // when: 설정만 14일로 변경 후 같은 자산을 재조회 (저장된 값이 아니라 파생값이어야 한다)
        stubRetentionDays(14, 1);
        List<PortalUploadResponse> res = listAll(ready);

        // then: 캐시 고착 없이 새 설정값으로 재계산
        assertThat(res.get(0).expiresAt()).isEqualTo(REG_DT.plusDays(14));
    }

    @Test
    @DisplayName("보존기간_설정이_없으면_만료예정시각만_null이고_목록_조회는_성공한다")
    void missingRetentionConfigNullsOnlyTheField() {
        // given: 설정 행 부재 — getInt 가 예외를 던진다(이 3키는 폴백하지 않는다)
        when(systemConfigService.getInt(any()))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 없음"));
        stubLastLabelSavedAt();

        // when
        List<PortalUploadResponse> res = listAll(asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT));

        // then: 500 으로 깨지지 않고 그 필드만 비운다
        assertThat(res).hasSize(1);
        assertThat(res.get(0).uldSn()).isEqualTo(1L);
        assertThat(res.get(0).expiresAt()).isNull();
    }

    @Test
    @DisplayName("목록_3건이어도_라벨_집계쿼리와_설정조회는_각_1회다_N플러스1_부재")
    void listDoesNotIssuePerRowQueries() {
        // given: 자산 3건
        stubRetentionDays(7, 1);
        stubLastLabelSavedAt(1L, REG_DT.plusDays(1));

        // when
        List<PortalUploadResponse> res = listAll(
                asset(1L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT),
                asset(2L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT),
                asset(3L, PortalUploadLedger.STATUS_FAILED, REG_DT, REG_DT));

        // then: 행 수와 무관하게 집계 1회 + 설정 키별 1회
        assertThat(res).hasSize(3);
        verify(assetRepository, times(1)).findLastLabelSavedAt(eq(ALICE), any());
        verify(systemConfigService, times(1)).getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS);
        verify(systemConfigService, times(1)).getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS);
    }

    @Test
    @DisplayName("빈_목록이면_라벨_집계쿼리도_설정조회도_하지_않는다")
    void emptyListSkipsLookups() {
        // given / when
        List<PortalUploadResponse> res = listAll();

        // then
        assertThat(res).isEmpty();
        verify(assetRepository, never()).findLastLabelSavedAt(any(), any());
        verify(systemConfigService, never()).getInt(any());
    }

    @Test
    @DisplayName("자산_상세응답에도_목록과_동일한_만료예정시각이_실린다")
    void detailCarriesExpiry() {
        // given
        stubRetentionDays(7, 1);
        LocalDateTime labelSavedAt = REG_DT.plusDays(2);
        stubLastLabelSavedAt(42L, labelSavedAt);
        PortalUploadAsset uld = asset(42L, PortalUploadLedger.STATUS_READY, REG_DT, REG_DT);
        when(assetRepository.findByOwner(42L, ALICE)).thenReturn(Optional.of(uld));
        when(frmeRepository.findAllByRawSnOrderByFrameNoAsc(42L)).thenReturn(List.of());

        // when
        PortalUploadDetailResponse res = service.getUpload(42L, ALICE);

        // then
        assertThat(res.expiresAt()).isEqualTo(labelSavedAt.plusDays(7));
        verify(assetRepository, times(1)).findLastLabelSavedAt(eq(ALICE), any());
    }

    // ======================== 프레임 목록 IDOR (#7) ========================

    @Test
    @DisplayName("타사용자_프레임목록_조회시_403")
    void listFramesForbiddenForNonOwner() {
        Pageable pageable = PageRequest.of(0, 20);
        when(assetRepository.findByOwner(9L, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.listFrames(9L, ALICE, pageable));
        verify(frmeRepository, never()).findPageByAssetAndOwner(any(), any(), any(), any());
    }

    // ======================== 다건 롤백 (#4/#5) ========================

    @Test
    @DisplayName("다건_배치중_후행_write실패시_선행파일도_롤백")
    void laterWriteFailureRollsBackEarlierFiles() {
        // 1번째는 정상, 2번째는 write 시점(getInputStream 3번째 호출)에 IOException.
        MultipartFile good = img("first.jpg", JPEG_HEAD);
        MultipartFile bad = new FailOnWriteMultipartFile("second.jpg", JPEG_HEAD, 2);

        assertThatThrownBy(() -> service.uploadImages(ALICE, List.of(good, bad)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // 선행(first) 파일도 보상 삭제되어 디스크에 남은 파일 0.
        assertThat(regularFilesUnder(storageDir)).isEmpty();
    }

    // ======================== 매직바이트 truncated (#6) ========================

    @Test
    @DisplayName("헤더만_유효한_truncated_파일_400")
    void headerOnlyTruncatedRejected() {
        // JPEG: SOI 는 있으나 EOI(FF D9) 없이 잘린 파일.
        byte[] truncatedJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertBadRequestNoSave(List.of(img("trunc.jpg", truncatedJpeg)));

        // PNG: 8바이트 시그니처만 있고 IHDR 없이 잘린 파일.
        byte[] truncatedPng = {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x00, 0x00, 0x00, 0x00};
        assertBadRequestNoSave(List.of(img("trunc.png", truncatedPng)));
    }

    /** write 단계(getInputStream N+1 번째 호출)에서 IOException 을 던지는 MultipartFile 테스트 더블. */
    private static final class FailOnWriteMultipartFile extends MockMultipartFile {
        private int calls = 0;
        private final int failAfter;

        FailOnWriteMultipartFile(String name, byte[] content, int failAfter) {
            super("files", name, "image/jpeg", content);
            this.failAfter = failAfter;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            calls++;
            if (calls > failAfter) {
                throw new IOException("disk full (test double)");
            }
            return super.getInputStream();
        }
    }

    // ======================== 경로조작 저장 (CWE-22) ========================

    @Test
    @DisplayName("경로조작_파일명_업로드시_저장경로가_베이스_밖으로_나가지_않음")
    void pathTraversalFilenameStaysInsideBase() {
        List<MultipartFile> files = List.of(img("../../../etc/passwd.png", PNG_HEAD));

        service.uploadImages(ALICE, files);

        Path base = storageDir.toAbsolutePath().normalize();
        List<Path> written = regularFilesUnder(storageDir);
        assertThat(written).hasSize(1);
        assertThat(written.get(0).normalize().startsWith(base)).isTrue();
        assertThat(written.get(0).toString()).doesNotContain("etc/passwd");
    }

    // ======================== 헬퍼 ========================

    private static List<Path> regularFilesUnder(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
