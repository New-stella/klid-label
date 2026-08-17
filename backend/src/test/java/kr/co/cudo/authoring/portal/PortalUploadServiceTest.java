package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalUploadResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.dto.PortalUploadDetailResponse;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldLblRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private LsPortalUldRepository uldRepository;
    private LsPortalUldFrmeRepository frmeRepository;
    private LsPortalUldLblRepository lblRepository;
    private SystemConfigService systemConfigService;
    private PortalUploadService service;
    private final AtomicLong uldSeq = new AtomicLong(100);
    private final AtomicLong frmeSeq = new AtomicLong(500);

    @BeforeEach
    void setUp() {
        uldRepository = mock(LsPortalUldRepository.class);
        frmeRepository = mock(LsPortalUldFrmeRepository.class);
        lblRepository = mock(LsPortalUldLblRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4", "mov", "avi"), storageDir.toString(),
                List.of("jpg", "jpeg", "png"), 1024L, 3, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        service = new PortalUploadService(uldRepository, frmeRepository, props, lblRepository,
                new PortalRetentionPolicy(systemConfigService),
                new kr.co.cudo.authoring.portal.service.PortalStoragePathGuard(props));

        when(uldRepository.save(any(LsPortalUld.class))).thenAnswer(inv -> {
            LsPortalUld u = inv.getArgument(0);
            setField(u, "uldSn", uldSeq.incrementAndGet());
            return u;
        });
        when(frmeRepository.save(any(LsPortalUldFrme.class))).thenAnswer(inv -> {
            LsPortalUldFrme f = inv.getArgument(0);
            setField(f, "uldFrmeSn", frmeSeq.incrementAndGet());
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
            assertThat(r.uldSttsCd()).isEqualTo(LsPortalUld.STTS_READY);
            assertThat(r.uldTypeCd()).isEqualTo(LsPortalUld.TYPE_IMAGE);
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
        verify(uldRepository, never()).save(any());
        verify(frmeRepository, never()).save(any());
        assertThat(regularFilesUnder(storageDir)).isEmpty();
    }

    // ======================== 고아 파일 방지 (#4) ========================

    @Test
    @DisplayName("DB_저장_실패시_기록된_파일이_남지_않음")
    void orphanFileRemovedOnDbFailure() {
        when(frmeRepository.save(any(LsPortalUldFrme.class)))
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
        LsPortalUld uld = LsPortalUld.createVideo(ALICE, "clip.mp4", "/x", 8L, "video/mp4");
        uld.markFailed("프레임 추출 실패: RuntimeException");

        PortalUploadResponse res = PortalUploadResponse.from(uld);

        assertThat(res.uldSttsCd()).isEqualTo(LsPortalUld.STTS_FAILED);
        assertThat(res.failRsnCn()).isEqualTo("프레임 추출 실패: RuntimeException");
    }

    @Test
    @DisplayName("READY_자산_응답의_실패사유는_null")
    void readyResponseHasNoFailReason() {
        LsPortalUld uld = LsPortalUld.createImage(ALICE, "a.jpg", "/x", 8L, "image/jpeg");
        uld.markReady(null, null, 1);

        assertThat(PortalUploadResponse.from(uld).failRsnCn()).isNull();
    }

    // ======================== IDOR (#7) ========================

    @Test
    @DisplayName("타사용자_자산_상세_조회시_403")
    void detailForbiddenForNonOwner() {
        when(uldRepository.findByUldSnAndPortalUserNo(9L, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.getUpload(9L, ALICE));
    }

    @Test
    @DisplayName("타사용자_프레임_이미지_조회시_403")
    void frameImageForbiddenForNonOwner() {
        when(frmeRepository.findByUldFrmeSnAndOwner(9L, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.serveFrameImage(9L, ALICE));
    }

    @Test
    @DisplayName("타사용자_삭제시_403")
    void deleteForbiddenForNonOwner() {
        when(uldRepository.findByUldSnAndPortalUserNo(9L, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.deleteUpload(9L, ALICE));
        verify(uldRepository, never()).delete(any());
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

        LsPortalUld uld = LsPortalUld.createImage(ALICE, "del.jpg", file.toString(), 8L, "image/jpeg");
        setField(uld, "uldSn", 42L);
        LsPortalUldFrme frme = LsPortalUldFrme.create(42L, 0, file.toString());
        when(uldRepository.findByUldSnAndPortalUserNo(42L, ALICE)).thenReturn(Optional.of(uld));
        when(frmeRepository.findAllByUldSnOrderByFrmeNo(42L)).thenReturn(List.of(frme));

        // when
        service.deleteUpload(42L, ALICE);

        // then — 파일 삭제 + DB 행 삭제 호출.
        assertThat(Files.exists(file)).isFalse();
        verify(uldRepository).delete(uld);
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

        LsPortalUld uld = LsPortalUld.createImage(ALICE, "x.jpg", nonEmptyDir.toString(), 8L, "image/jpeg");
        setField(uld, "uldSn", 77L);
        LsPortalUldFrme frme = LsPortalUldFrme.create(77L, 0, nonEmptyDir.toString());
        when(uldRepository.findByUldSnAndPortalUserNo(77L, ALICE)).thenReturn(Optional.of(uld));
        when(frmeRepository.findAllByUldSnOrderByFrmeNo(77L)).thenReturn(List.of(frme));

        // when/then — 파일 삭제 실패 → 5xx + DB 행 보존(delete 미호출).
        assertThatThrownBy(() -> service.deleteUpload(77L, ALICE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        verify(uldRepository, never()).delete(any());
    }

    // ======================== 서빙 파일 부재 (#6) ========================

    @Test
    @DisplayName("프레임파일_디스크_부재시_이미지서빙_404")
    void serveMissingFileReturns404() {
        // given — DB 행은 있으나 물리 파일 없음.
        Path ghost = storageDir.resolve("images").resolve("ghost.png");
        LsPortalUld uld = LsPortalUld.createImage(ALICE, "g.png", ghost.toString(), 8L, "image/png");
        setField(uld, "uldSn", 55L);
        LsPortalUldFrme frme = LsPortalUldFrme.create(55L, 0, ghost.toString());
        setField(frme, "uldFrmeSn", 555L);
        when(frmeRepository.findByUldFrmeSnAndOwner(555L, ALICE)).thenReturn(Optional.of(frme));
        when(uldRepository.findByUldSnAndPortalUserNo(55L, ALICE)).thenReturn(Optional.of(uld));

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
        Page<LsPortalUld> empty = new PageImpl<>(List.of(), pageable, 0);
        when(uldRepository.findAllByPortalUserNoAndUldTypeCd(eq(ALICE), any(), any())).thenReturn(empty);

        service.listUploads(ALICE, "image", pageable); // 소문자 → 대문자 정규화.

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(uldRepository).findAllByPortalUserNoAndUldTypeCd(eq(ALICE), typeCaptor.capture(), eq(pageable));
        assertThat(typeCaptor.getValue()).isEqualTo("IMAGE");
        verify(uldRepository, never()).findAllByPortalUserNo(any(), any());
    }

    @Test
    @DisplayName("미지원_type_필터시_400")
    void listUploadsUnknownTypeRejected() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThatThrownBy(() -> service.listUploads(ALICE, "EXE", pageable))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(uldRepository, never()).findAllByPortalUserNoAndUldTypeCd(any(), any(), any());
        verify(uldRepository, never()).findAllByPortalUserNo(any(), any());
    }

    // ======================== 보존기간 만료 예정 시각 (AC-033 / AC-037 / DFEAT-055) ========================

    private static final LocalDateTime REG_DT = LocalDateTime.of(2026, 8, 1, 10, 0);

    /** 상태·시각을 지정한 자산 1건 — 엔티티 팩토리는 시각을 now 로 박으므로 리플렉션으로 고정한다. */
    private LsPortalUld asset(long uldSn, String status, LocalDateTime regDt, LocalDateTime mdfcnDt) {
        LsPortalUld uld = LsPortalUld.createImage(ALICE, "a.jpg", "/x", 8L, "image/jpeg");
        setField(uld, "uldSn", uldSn);
        setField(uld, "uldSttsCd", status);
        setField(uld, "regDt", regDt);
        setField(uld, "mdfcnDt", mdfcnDt);
        return uld;
    }

    private void stubRetentionDays(Integer ready, Integer failed) {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(ready);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(failed);
    }

    /** 라벨 마지막 저장일 집계 결과 stub — 인자로 준 자산만 라벨을 가진다. */
    private void stubLastLabelSavedAt(Object... uldSnThenTime) {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < uldSnThenTime.length; i += 2) {
            rows.add(new Object[]{uldSnThenTime[i], uldSnThenTime[i + 1]});
        }
        when(lblRepository.findMaxRegDtGroupedByUldSn(eq(ALICE), any())).thenReturn(rows);
    }

    private List<PortalUploadResponse> listAll(LsPortalUld... assets) {
        Pageable pageable = PageRequest.of(0, 20);
        when(uldRepository.findAllByPortalUserNo(eq(ALICE), any()))
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
        List<PortalUploadResponse> res = listAll(asset(1L, LsPortalUld.STTS_READY, REG_DT, REG_DT));

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
        List<PortalUploadResponse> res = listAll(asset(1L, LsPortalUld.STTS_READY, REG_DT, REG_DT));

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
        List<PortalUploadResponse> res = listAll(asset(1L, LsPortalUld.STTS_READY, REG_DT, REG_DT));

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
                asset(1L, LsPortalUld.STTS_READY, REG_DT, REG_DT),
                asset(2L, LsPortalUld.STTS_FAILED, REG_DT, failedAt));

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
                asset(1L, LsPortalUld.STTS_PROCESSING, REG_DT, REG_DT),
                asset(2L, LsPortalUld.STTS_UPLOADED, REG_DT, REG_DT));

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
        LsPortalUld ready = asset(1L, LsPortalUld.STTS_READY, REG_DT, REG_DT);
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
        List<PortalUploadResponse> res = listAll(asset(1L, LsPortalUld.STTS_READY, REG_DT, REG_DT));

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
                asset(1L, LsPortalUld.STTS_READY, REG_DT, REG_DT),
                asset(2L, LsPortalUld.STTS_READY, REG_DT, REG_DT),
                asset(3L, LsPortalUld.STTS_FAILED, REG_DT, REG_DT));

        // then: 행 수와 무관하게 집계 1회 + 설정 키별 1회
        assertThat(res).hasSize(3);
        verify(lblRepository, times(1)).findMaxRegDtGroupedByUldSn(eq(ALICE), any());
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
        verify(lblRepository, never()).findMaxRegDtGroupedByUldSn(any(), any());
        verify(systemConfigService, never()).getInt(any());
    }

    @Test
    @DisplayName("자산_상세응답에도_목록과_동일한_만료예정시각이_실린다")
    void detailCarriesExpiry() {
        // given
        stubRetentionDays(7, 1);
        LocalDateTime labelSavedAt = REG_DT.plusDays(2);
        stubLastLabelSavedAt(42L, labelSavedAt);
        LsPortalUld uld = asset(42L, LsPortalUld.STTS_READY, REG_DT, REG_DT);
        when(uldRepository.findByUldSnAndPortalUserNo(42L, ALICE)).thenReturn(Optional.of(uld));
        when(frmeRepository.findAllByUldSnOrderByFrmeNo(42L)).thenReturn(List.of());

        // when
        PortalUploadDetailResponse res = service.getUpload(42L, ALICE);

        // then
        assertThat(res.expiresAt()).isEqualTo(labelSavedAt.plusDays(7));
        verify(lblRepository, times(1)).findMaxRegDtGroupedByUldSn(eq(ALICE), any());
    }

    // ======================== 프레임 목록 IDOR (#7) ========================

    @Test
    @DisplayName("타사용자_프레임목록_조회시_403")
    void listFramesForbiddenForNonOwner() {
        Pageable pageable = PageRequest.of(0, 20);
        when(uldRepository.findByUldSnAndPortalUserNo(9L, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.listFrames(9L, ALICE, pageable));
        verify(frmeRepository, never()).findAllByUldSnAndOwnerOrderByFrmeNo(any(), any(), any());
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
