package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalUploadLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUploadLabelResponse;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.service.PortalUploadLabelService;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLabelRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 4 — 포털 업로드 라벨 CRUD/export/다운로드 서비스 단위 테스트.
 * HIGH 시나리오(동시 PUT 락, delete+insert 원자성, 배열 상한 DoS, 타입 allowlist, IDOR 403,
 * READY 가드 409, Content-Disposition 인젝션, 데이터마트 무접촉) 방어를 Mockito + {@link TempDir} 로 검증.
 */
class PortalUploadLabelServiceTest {

    private static final String ALICE = "alice";
    private static final long ULD_SN = 100L;
    private static final long FRME_SN = 500L;

    @TempDir Path storageDir;

    private PortalUploadAssetRepository assetRepository;
    private PortalUploadFrameRepository frmeRepository;
    private PortalUploadLabelRepository lblRepository;
    private PortalUploadLabelService service;
    // Spring 부트 ObjectMapper 와 동일하게 JavaTime(LocalDateTime) 직렬화 모듈을 등록한다.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        assetRepository = mock(PortalUploadAssetRepository.class);
        frmeRepository = mock(PortalUploadFrameRepository.class);
        lblRepository = mock(PortalUploadLabelRepository.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg", "jpeg", "png"), 1024L, 3, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        service = new PortalUploadLabelService(assetRepository, frmeRepository, lblRepository, props, objectMapper);
    }

    // ---------------- fixtures ----------------

    /** 자산 스냅샷 — 흡수 뒤 자산은 <읽기 모델>이라 그대로 만든다. */
    private PortalUploadAsset uld(String status, String mime, String orgnlFileNm, String filePath) {
        return new PortalUploadAsset(ULD_SN, ALICE, PortalUploadLedger.assetTypeOf(mime),
                orgnlFileNm, filePath, 100L, mime, status, null, null, 1, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    }

    private LsDataSrc frame() {
        LsDataSrc f = LsDataSrc.create(ULD_SN, 0L, storageDir.resolve("f0.png").toString(), null);
        setField(f, "srcSn", FRME_SN);
        return f;
    }

    /** frame 락 조회 + READY uld 조회 stub (정상 PUT 경로). saveAll 은 입력 그대로 반환. */
    @SuppressWarnings("unchecked")
    private void stubHappyPutPath() {
        when(frmeRepository.findByOwnerForUpdate(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE))
                .thenReturn(Optional.of(frame()));
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png", "a.png")));
        when(lblRepository.saveAll(anyList())).thenAnswer(inv -> new ArrayList<>((List<LsDataLbl>) inv.getArgument(0)));
    }

    private PortalUploadLabelRequest bbox(double x1, double y1, double x2, double y2) {
        return new PortalUploadLabelRequest("BBOX", "car", List.of(List.of(x1, y1), List.of(x2, y2)));
    }

    // ======================== 전체교체(PUT) 정상 ========================

    @Test
    @DisplayName("라벨_전체교체_저장은_멱등")
    void replaceIsIdempotent() {
        stubHappyPutPath();
        List<PortalUploadLabelRequest> body = List.of(bbox(1, 2, 3, 4));

        List<PortalUploadLabelResponse> first = service.replaceLabels(FRME_SN, ALICE, body);
        List<PortalUploadLabelResponse> second = service.replaceLabels(FRME_SN, ALICE, body);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0).lblTypeCd()).isEqualTo("BBOX");
        assertThat(first.get(0).points()).isEqualTo(second.get(0).points());
        // 매 PUT 마다 전체 삭제 후 저장 (전체교체 시맨틱).
        verify(lblRepository, times(2)).deleteAllByFrameAndOwner(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE);
        verify(lblRepository, times(2)).saveAll(anyList());
    }

    @Test
    @DisplayName("빈_배열_PUT은_전체_삭제")
    void emptyArrayDeletesAll() {
        stubHappyPutPath();

        List<PortalUploadLabelResponse> res = service.replaceLabels(FRME_SN, ALICE, List.of());

        assertThat(res).isEmpty();
        verify(lblRepository).deleteAllByFrameAndOwner(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE);
        verify(lblRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("동시성_PUT은_비관적락_조회를_사용")
    void replaceUsesPessimisticLockLookup() {
        stubHappyPutPath();

        service.replaceLabels(FRME_SN, ALICE, List.of(bbox(1, 2, 3, 4)));

        // 락 조회(ForUpdate)를 사용해야 하며, 비-락 조회는 진입점에서 사용하지 않는다.
        verify(frmeRepository).findByOwnerForUpdate(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE);
        verify(frmeRepository, never()).findByOwner(anyLong(), anyString(), anyString());
    }

    // ======================== 입력 검증 400 (DELETE 이전) ========================

    @Test
    @DisplayName("BBOX_POLYGON_외_타입_400")
    void invalidTypeRejected() {
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("CIRCLE", "x", List.of(List.of(1.0, 2.0)))));
    }

    @Test
    @DisplayName("BBOX_점2개_아니면_400")
    void bboxMustHaveTwoPoints() {
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("BBOX", "car", List.of(List.of(1.0, 2.0)))));
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("BBOX", "car",
                        List.of(List.of(1.0, 2.0), List.of(3.0, 4.0), List.of(5.0, 6.0)))));
    }

    @Test
    @DisplayName("POLYGON_3점미만_또는_200점초과_400")
    void polygonPointBounds() {
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("POLYGON", "p", List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)))));
        List<List<Double>> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(List.of((double) i, (double) i));
        }
        assertBadRequestNoDelete(List.of(new PortalUploadLabelRequest("POLYGON", "p", tooMany)));
    }

    @Test
    @DisplayName("라벨_500개_초과_400")
    void tooManyLabelsRejected() {
        List<PortalUploadLabelRequest> body = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            body.add(bbox(1, 2, 3, 4));
        }
        assertBadRequestNoDelete(body);
    }

    @Test
    @DisplayName("label_80자초과_400")
    void labelTooLongRejected() {
        String tooLong = "x".repeat(81);
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("BBOX", tooLong, List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)))));
    }

    @Test
    @DisplayName("좌표_원소가_2개가_아니거나_null이면_400")
    void malformedPointTupleRejected() {
        // 원소 크기 3
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("BBOX", "c", List.of(List.of(1.0, 2.0, 3.0), List.of(3.0, 4.0)))));
        // 원소 내 null
        List<Double> nullElem = new ArrayList<>();
        nullElem.add(null);
        nullElem.add(2.0);
        List<List<Double>> pts = new ArrayList<>();
        pts.add(nullElem);
        pts.add(List.of(3.0, 4.0));
        assertBadRequestNoDelete(List.of(new PortalUploadLabelRequest("BBOX", "c", pts)));
    }

    @Test
    @DisplayName("좌표_Infinity면_400")
    void infiniteCoordinateRejected() {
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("BBOX", "c",
                        List.of(List.of(Double.POSITIVE_INFINITY, 2.0), List.of(3.0, 4.0)))));
    }

    @Test
    @DisplayName("소문자_타입도_정규화되어_저장")
    void lowercaseTypeNormalized() {
        stubHappyPutPath();

        List<PortalUploadLabelResponse> res = service.replaceLabels(FRME_SN, ALICE,
                List.of(new PortalUploadLabelRequest("bbox", "car", List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)))));

        assertThat(res).hasSize(1);
        assertThat(res.get(0).lblTypeCd()).isEqualTo("BBOX");
    }

    @Test
    @DisplayName("검증_실패시_기존_라벨_유지")
    void validationFailureKeepsExisting() {
        // 불량 좌표(NaN) — DELETE 미실행이어야 기존 라벨이 보존된다.
        assertBadRequestNoDelete(List.of(
                new PortalUploadLabelRequest("BBOX", "car",
                        List.of(List.of(Double.NaN, 2.0), List.of(3.0, 4.0)))));
    }

    private void assertBadRequestNoDelete(List<PortalUploadLabelRequest> body) {
        assertThatThrownBy(() -> service.replaceLabels(FRME_SN, ALICE, body))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        // 검증은 DELETE 이전 — 리포지토리 접촉 없음(기존 라벨 유지 + 프레임 락 미획득).
        verifyNoInteractions(lblRepository);
        verify(frmeRepository, never()).findByOwnerForUpdate(anyLong(), anyString(), anyString());
    }

    // ======================== IDOR 403 ========================

    @Test
    @DisplayName("타사용자_프레임_라벨_PUT시_403")
    void putIdorForbidden() {
        when(frmeRepository.findByOwnerForUpdate(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.replaceLabels(FRME_SN, ALICE, List.of(bbox(1, 2, 3, 4))));
        verify(lblRepository, never()).deleteAllByFrameAndOwner(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("타사용자_라벨_GET_403")
    void getIdorForbidden() {
        when(frmeRepository.findByOwner(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.listLabels(FRME_SN, ALICE));
    }

    @Test
    @DisplayName("타사용자_export_403")
    void exportIdorForbidden() {
        when(assetRepository.findByOwner(ULD_SN, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.exportLabels(ULD_SN, ALICE));
    }

    @Test
    @DisplayName("타사용자_원본_다운로드_403")
    void downloadIdorForbidden() {
        when(assetRepository.findByOwner(ULD_SN, ALICE)).thenReturn(Optional.empty());
        assertForbidden(() -> service.downloadFile(ULD_SN, ALICE));
    }

    // ======================== READY 가드 409 ========================

    @Test
    @DisplayName("READY_아닌_자산_라벨링_409")
    void nonReadyAssetConflict() {
        for (String status : List.of(PortalUploadLedger.STATUS_PROCESSING, PortalUploadLedger.STATUS_FAILED, PortalUploadLedger.STATUS_UPLOADED)) {
            when(frmeRepository.findByOwnerForUpdate(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(Optional.of(frame()));
            when(assetRepository.findByOwner(ULD_SN, ALICE))
                    .thenReturn(Optional.of(uld(status, "image/png", "a.png", "a.png")));

            assertThatThrownBy(() -> service.replaceLabels(FRME_SN, ALICE, List.of(bbox(1, 2, 3, 4))))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
        }
        // 409 는 DELETE 이전 — 어떤 상태에서도 삭제 미실행.
        verify(lblRepository, never()).deleteAllByFrameAndOwner(anyLong(), anyString(), anyString());
    }

    // ======================== export 구성 ========================

    @Test
    @DisplayName("export_JSON에_자산메타_프레임_라벨_모두_포함")
    void exportContainsMetaFramesLabels() throws IOException {
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "myphoto.png", "a.png")));
        when(frmeRepository.findAllByRawSnOrderByFrameNoAsc(ULD_SN)).thenReturn(List.of(frame()));
        LsDataLbl lbl = LsDataLbl.createManual(FRME_SN, "BBOX", null, "car", "[[1.0,2.0],[3.0,4.0]]", ALICE);
        setField(lbl, "lblSn", 9L);
        when(lblRepository.findAllByAssetAndOwner(ULD_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(List.of(lbl));

        ResponseEntity<byte[]> res = service.exportLabels(ULD_SN, ALICE);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"portal-upload-100-labels.json\"");
        String json = new String(res.getBody(), StandardCharsets.UTF_8);
        assertThat(json).contains("myphoto.png");   // 자산 메타
        assertThat(json).contains("\"frmeNo\" : 0"); // 프레임(pretty — 필드 구분자 " : ")
        assertThat(json).contains("car");            // 라벨명
        assertThat(json).contains("1.0").contains("4.0"); // 좌표
        assertThat(json).contains("\n").contains("\n  "); // pretty(개행+들여쓰기)
    }

    @Test
    @DisplayName("export_JSON_구조_검증_프레임_라벨_좌표_중첩배열")
    void exportJsonStructure() throws IOException {
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "myphoto.png", "a.png")));
        when(frmeRepository.findAllByRawSnOrderByFrameNoAsc(ULD_SN)).thenReturn(List.of(frame()));
        LsDataLbl lbl = LsDataLbl.createManual(FRME_SN, "BBOX", null, "car", "[[1.0,2.0],[3.0,4.0]]", ALICE);
        setField(lbl, "lblSn", 9L);
        when(lblRepository.findAllByAssetAndOwner(ULD_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(List.of(lbl));

        ResponseEntity<byte[]> res = service.exportLabels(ULD_SN, ALICE);

        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(res.getBody());
        assertThat(root.path("uldSn").asLong()).isEqualTo(ULD_SN);
        assertThat(root.path("orgnlFileNm").asText()).isEqualTo("myphoto.png");
        assertThat(root.path("frames").isArray()).isTrue();
        com.fasterxml.jackson.databind.JsonNode frame0 = root.path("frames").get(0);
        assertThat(frame0.path("frmeNo").asInt()).isZero();
        com.fasterxml.jackson.databind.JsonNode label0 = frame0.path("labels").get(0);
        assertThat(label0.path("lblTypeCd").asText()).isEqualTo("BBOX");
        assertThat(label0.path("label").asText()).isEqualTo("car");
        com.fasterxml.jackson.databind.JsonNode points = label0.path("points");
        assertThat(points.isArray()).isTrue();
        assertThat(points.size()).isEqualTo(2);
        assertThat(points.get(0).isArray()).isTrue();
        assertThat(points.get(0).get(0).asDouble()).isEqualTo(1.0);
        assertThat(points.get(0).get(1).asDouble()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("프레임_0건이면_export_frames_빈배열")
    void exportZeroFrames() throws IOException {
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png", "a.png")));
        when(frmeRepository.findAllByRawSnOrderByFrameNoAsc(ULD_SN)).thenReturn(List.of());
        when(lblRepository.findAllByAssetAndOwner(ULD_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(List.of());

        ResponseEntity<byte[]> res = service.exportLabels(ULD_SN, ALICE);

        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(res.getBody());
        assertThat(root.path("frames").isArray()).isTrue();
        assertThat(root.path("frames").size()).isZero();
    }

    @Test
    @DisplayName("손상된_POINT_CN은_export시_빈_좌표배열_fail_secure")
    void exportCorruptPointsFailSecure() throws IOException {
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png", "a.png")));
        when(frmeRepository.findAllByRawSnOrderByFrameNoAsc(ULD_SN)).thenReturn(List.of(frame()));
        LsDataLbl corrupt = LsDataLbl.createManual(FRME_SN, "BBOX", null, "car", "{not-json", ALICE);
        when(lblRepository.findAllByAssetAndOwner(ULD_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(List.of(corrupt));

        ResponseEntity<byte[]> res = service.exportLabels(ULD_SN, ALICE);

        com.fasterxml.jackson.databind.JsonNode points = objectMapper.readTree(res.getBody())
                .path("frames").get(0).path("labels").get(0).path("points");
        assertThat(points.isArray()).isTrue();
        assertThat(points.size()).isZero(); // 렌더 크래시 없이 빈 배열
    }

    @Test
    @DisplayName("손상된_POINT_CN은_조회시_빈_좌표배열_fail_secure")
    void listCorruptPointsFailSecure() {
        when(frmeRepository.findByOwner(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(Optional.of(frame()));
        LsDataLbl corrupt = LsDataLbl.createManual(FRME_SN, "BBOX", null, "car", "not-json-at-all", ALICE);
        when(lblRepository.findAllByFrameAndOwner(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(List.of(corrupt));

        List<PortalUploadLabelResponse> res = service.listLabels(FRME_SN, ALICE);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).points()).isEmpty();
    }

    @Test
    @DisplayName("export는_포털_리포지토리만_사용")
    void exportUsesOnlyPortalRepositories() {
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png", "a.png")));
        when(frmeRepository.findAllByRawSnOrderByFrameNoAsc(ULD_SN)).thenReturn(List.of(frame()));
        when(lblRepository.findAllByAssetAndOwner(ULD_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(List.of());

        service.exportLabels(ULD_SN, ALICE);

        // 포털 3종 리포지토리만 사용 — 라벨은 업로드 단위 1회 일괄 조회(프레임별 N+1 금지).
        verify(assetRepository).findByOwner(ULD_SN, ALICE);
        verify(frmeRepository).findAllByRawSnOrderByFrameNoAsc(ULD_SN);
        verify(lblRepository, times(1)).findAllByAssetAndOwner(ULD_SN, ALICE, PortalUploadLedger.SRC_TYPE);
        verify(lblRepository, never()).findAllByFrameAndOwner(anyLong(), anyString(), anyString());
    }

    // ======================== 원본 다운로드 ========================

    @Test
    @DisplayName("원본_다운로드_Content_Disposition에_개행_포함_파일명_무해화")
    void downloadSanitizesContentDisposition() throws IOException {
        Path file = storageDir.resolve("stored.png");
        Files.write(file, new byte[]{1, 2, 3});
        // 원본명에 CRLF 헤더 인젝션 시도 포함.
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png",
                        "evil\r\nSet-Cookie: x=1.png", file.toString())));

        ResponseEntity<Resource> res = service.downloadFile(ULD_SN, ALICE);

        String cd = res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(cd).doesNotContain("\r").doesNotContain("\n");
        assertThat(cd).startsWith("attachment;");
        assertThat(cd).contains("filename=\"download.png\"");   // ASCII fallback 고정명
        assertThat(cd).contains("filename*=UTF-8''");           // RFC 5987
        // CRLF 제거로 헤더 분리 불가 — 위험한 활성 헤더 형태(콜론+공백)가 재현되지 않는다.
        // (filename* 는 URL 인코딩되어 ':' '공백'이 %3A %20 으로 치환되므로 활성 헤더가 될 수 없다.)
        assertThat(cd).doesNotContain("Set-Cookie: ");
    }

    @Test
    @DisplayName("원본_다운로드_nosniff와_DB_MIME")
    void downloadHasNosniffAndDbMime() throws IOException {
        Path file = storageDir.resolve("stored.png");
        Files.write(file, new byte[]{1, 2, 3});
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png", file.toString())));

        ResponseEntity<Resource> res = service.downloadFile(ULD_SN, ALICE);

        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("JPEG_MIME는_확장자없어도_jpg_fallback")
    void downloadJpegMimeFallback() throws IOException {
        Path file = storageDir.resolve("stored.bin");
        Files.write(file, new byte[]{1, 2, 3});
        // 확장자 없는 원본명 + image/jpeg → MIME 매핑으로 jpg.
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/jpeg", "photo", file.toString())));

        ResponseEntity<Resource> res = service.downloadFile(ULD_SN, ALICE);

        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename=\"download.jpg\"");
    }

    @Test
    @DisplayName("알수없는_MIME은_octet_stream과_bin_확장자")
    void downloadUnknownMimeOctetStream() throws IOException {
        Path file = storageDir.resolve("stored2");
        Files.write(file, new byte[]{1, 2, 3});
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/webp", "mystery", file.toString())));

        ResponseEntity<Resource> res = service.downloadFile(ULD_SN, ALICE);

        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename=\"download.bin\"");
    }

    @Test
    @DisplayName("파일명이_전부_제어문자면_download_확장자_고정명")
    void downloadAllControlCharFileName() throws IOException {
        Path file = storageDir.resolve("stored3.png");
        Files.write(file, new byte[]{1, 2, 3});
        // 제어문자만으로 구성된 원본명 → sanitize 후 blank → download.{ext}.
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "\r\n\t ", file.toString())));

        ResponseEntity<Resource> res = service.downloadFile(ULD_SN, ALICE);

        String cd = res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(cd).doesNotContain("\r").doesNotContain("\n");
        assertThat(cd).contains("filename=\"download.png\"");
    }

    @Test
    @DisplayName("원본_파일_부재시_404")
    void downloadMissingFileNotFound() {
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png",
                        storageDir.resolve("nope.png").toString())));

        assertThatThrownBy(() -> service.downloadFile(ULD_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("원본_파일경로_없으면_404")
    void downloadNullFilePathNotFound() {
        // 추출 미완료/이상 자산 — FILE_PATH_NM 이 null. Paths.get(null) NPE 없이 404 로 안전 처리되어야 한다.
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png", null)));

        assertThatThrownBy(() -> service.downloadFile(ULD_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("원본_다운로드_경로탐색_차단")
    void downloadPathTraversalBlocked() {
        // baseDir(storageDir) 밖으로 벗어나는 상대경로가 저장돼 있어도 resolveSafe 가 403 으로 차단해야 한다.
        when(assetRepository.findByOwner(ULD_SN, ALICE))
                .thenReturn(Optional.of(uld(PortalUploadLedger.STATUS_READY, "image/png", "a.png",
                        "../../../../etc/passwd")));

        assertForbidden(() -> service.downloadFile(ULD_SN, ALICE));
    }

    // ======================== 라벨 조회 ========================

    @Test
    @DisplayName("라벨_조회는_소유자_스코프_프레임_검증후_반환")
    void listLabelsOwnerScoped() {
        when(frmeRepository.findByOwner(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(Optional.of(frame()));
        LsDataLbl lbl = LsDataLbl.createManual(FRME_SN, "POLYGON", null, "person", "[[0.0,0.0],[1.0,0.0],[1.0,1.0]]", ALICE);
        when(lblRepository.findAllByFrameAndOwner(FRME_SN, ALICE, PortalUploadLedger.SRC_TYPE)).thenReturn(List.of(lbl));

        List<PortalUploadLabelResponse> res = service.listLabels(FRME_SN, ALICE);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).lblTypeCd()).isEqualTo("POLYGON");
        assertThat(res.get(0).points()).hasSize(3);
    }

    // ---------------- helpers ----------------

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
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
