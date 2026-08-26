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
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.NiaExportContext;
import kr.co.cudo.authoring.dataset.export.NiaExportContextAssembler;
import kr.co.cudo.authoring.dataset.export.SourcePrivacyMeta;
import kr.co.cudo.authoring.dataset.export.json.CategoryMapper;
import kr.co.cudo.authoring.dataset.export.json.LabelToAnnotationMapper;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.VideoMetaMapper;
import kr.co.cudo.authoring.portal.service.PortalNiaDocumentFactory;
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
import static org.mockito.ArgumentMatchers.any;
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
    @Mock NiaExportContextAssembler niaExportContextAssembler;

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
                new PortalLabelService(null, null, null, null, null, null, null, new ObjectMapper(), null);

        // 어노테이션 문서는 <실제 빌더>로 만든다 — 산출 종류 고정(AC-034)과 좌표 표현이 검증 대상이라
        // mock 으로 대체하면 그 보장 근거가 사라진다.
        ObjectMapper mapper = new ObjectMapper();
        NiaJsonBuilder niaJsonBuilder = new NiaJsonBuilder(
                new LabelToAnnotationMapper(mapper), new VideoMetaMapper(), new CategoryMapper());
        PortalNiaDocumentFactory documentFactory = new PortalNiaDocumentFactory(niaJsonBuilder);

        PortalDatamartDownloadTxService txService = new PortalDatamartDownloadTxService(
                approvalGate, accessGuard, labelService, videoStreamService,
                niaExportContextAssembler, documentFactory,
                videoRepository, srcRepository, lblRepository, userLabelRepository, mapper);

        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                tempDir.toString(), "", rawBase.toString(), deidBase.toString(),
                tempDir.resolve("labeling").toString(), VideoArtifactRootResolver.STRATEGY_CO_LOCATE);

        service = new PortalDatamartDownloadService(txService, resolver, deidBase.toString());

        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        givenNiaContext(null);
    }

    /**
     * 승인 시점 동결 메타 스냅샷 + 비식별 영상 경로로 어노테이션 문서 컨텍스트를 준비한다.
     * 조달 규칙 자체는 공유 조립기 소관이라 여기서는 <b>그 결과</b>만 넘긴다.
     */
    private void givenNiaContext(String deidVideoPath) {
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(RAW_SN)
                .rawFilePathNm(rawBase.resolve("original.mp4").toString())
                .evntNm("화재")
                .vdoWdth(1920)
                .vdoHgt(1080)
                .build();
        NiaJsonBuilder builder = new NiaJsonBuilder(
                new LabelToAnnotationMapper(new ObjectMapper()), new VideoMetaMapper(), new CategoryMapper());
        NiaExportContext ctx = new NiaExportContext(
                meta, null,
                builder.prepareContext(meta, null, List.of(), null, deidVideoPath,
                        SourcePrivacyMeta.NONE, null, null),
                SourcePrivacyMeta.NONE, null, deidVideoPath);
        when(niaExportContextAssembler.assemble(anyLong(), any())).thenReturn(Optional.of(ctx));
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

    @Test
    @DisplayName("신고_구간이면_본인_저장_라벨이_0건이어도_412이고_라벨을_묻지_않는다")
    void underDeidentReport_precedesGoneGate() {
        // ★ ④가 ⑤보다 <먼저>다. 두 조건이 동시에 참인 상태를 만들어야 순서가 실제로 구속된다 —
        //   412 케이스가 라벨을 심어 두고 410 케이스가 신고를 열어 두지 않으면, 둘을 뒤집어도
        //   두 테스트 모두 통과한다(실제로 그 상태였다: 생존 변이).
        //   뒤집히면 신고 구간 영상이 410 으로 응답해 «작업 데이터 유무» 가 새어나간다(CWE-209).
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of());
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED,
                "비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.download(RAW_SN, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // ⑤ 판정을 위한 <조회조차> 하지 않는다 — 403↔412 가드와 같은 골격.
        verify(userLabelRepository, never())
                .findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(any(), anyLong());
    }

    @Test
    @DisplayName("활성_영상_메타가_없으면_라벨없는_ZIP이_아니라_500이고_스트리밍이_시작되지_않는다")
    void missingActiveVideoMetaFailsClosed() throws IOException {
        // 어노테이션 문서의 메타 블록 조달처가 없으면 <같은 구조의 문서를 만들 근거 자체가 없다>.
        // 조용히 라벨 없는 ZIP 을 내보내면 사용자는 «작업이 사라졌다» 로 읽는다(fail-closed 유지).
        // ⚠ 이 분기는 다른 케이스가 조립기를 «항상 존재» 로 심어 두어 한 번도 실행되지 않았다.
        when(niaExportContextAssembler.assemble(anyLong(), any())).thenReturn(Optional.empty());
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        // 응답 자체가 만들어지지 않는다 — 200 + StreamingResponseBody 로 새면 이미 커밋된 뒤라
        // 사용자에게는 «내용이 빈 성공» 으로 보인다.
        assertThatThrownBy(() -> service.download(RAW_SN, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    // ======================== AC-034 — 비식별 영상 부재 ========================

    @Test
    @DisplayName("비식별_영상이_없으면_라벨과_이미지만_담기고_video_엔트리가_없다")
    void noDeidVideo_zipHasLabelsAndFramesOnly() throws IOException {
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        assertThat(zip.keySet())
                .containsExactlyInAnyOrder("100/frames/0000.json", "100/frames/0000.jpg");
        assertThat(zip.keySet()).noneMatch(name -> name.startsWith("100/video."));
        // 자체 shape 단일 라벨 문서는 두지 않는다(두 형태 병존 금지).
        assertThat(zip.keySet()).doesNotContain("100/labels.json");
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
        givenNiaContext(deidVideo.toString());
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
        // given: A(alice) 와 B(bob) 가 같은 프레임(10)에 각자 저장했고, 그 프레임에는 데이터마트
        //   원본 라벨도 있다. 리포지토리는 소유자 스코프로 조회하므로 A 의 요청에는 A 의 행만 돌아온다.
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of(myLabel(10L, "alice-car", "[[5,6],[7,8]]", 1001L)));
        when(lblRepository.findAllByRawSn(RAW_SN))
                .thenReturn(List.of(datamartLabel(10L, "datamart-person", 2001L)));
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        String doc = new String(unzip(service.download(RAW_SN, alice)).get("100/frames/0000.json"),
                StandardCharsets.UTF_8);

        // 어노테이션 식별자는 <그 라벨을 담고 있는 행의 식별자>다 — 본인 저장분이 이긴 프레임이므로
        // 포털 작업 저장소의 식별자만 실리고 데이터마트 라벨 식별자는 실리지 않는다.
        assertThat(doc).contains("\"id\" : 1001").doesNotContain("\"id\" : 2001");
        // 좌표도 본인 저장분 기준이다([[5,6],[7,8]] → bbox [5,6,2,2]).
        assertThat(doc).contains("\"bbox\"").contains("5.0").contains("6.0");
    }

    @Test
    @DisplayName("내가_저장하지_않은_프레임은_데이터마트_원본으로_대체된다")
    void unsavedFrameFallsBackToDatamartOriginal() throws IOException {
        // given: 프레임 10 에만 본인 저장분이 있고, 프레임 11 에는 데이터마트 원본만 있다.
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of(myLabel(10L, "alice-car", "[[5,6],[7,8]]", 1001L)));
        givenFrames(frame(10L, 0, writeDeidFrame(0)), frame(11L, 1, writeDeidFrame(1)));
        when(lblRepository.findAllByRawSn(RAW_SN)).thenReturn(List.of(
                datamartLabel(10L, "datamart-person", 2001L),
                datamartLabel(11L, "datamart-dog", 2002L)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));
        String saved = new String(zip.get("100/frames/0000.json"), StandardCharsets.UTF_8);
        String fallback = new String(zip.get("100/frames/0001.json"), StandardCharsets.UTF_8);

        // ★ 병합 입도는 <프레임 단위>다 — 한 영상 안에서 어떤 프레임은 본인 것, 어떤 프레임은 원본이
        //   되며 프레임별 문서로 바뀐 뒤에는 그 입도가 문서마다 드러난다.
        //   본인 저장분이 있는 프레임(srcSn=10, FRM_NO=0)은 본인 것만 — 그 프레임의 원본은 섞이지 않는다.
        assertThat(saved).contains("\"id\" : 1001").doesNotContain("\"id\" : 2001");
        // 저장하지 않은 프레임(srcSn=11, FRM_NO=1)은 데이터마트 원본으로 대체된다.
        assertThat(fallback).contains("\"id\" : 2002");
    }

    // ======================== 마스터 연결·트랙 연결 (ERD-018) ========================

    @Test
    @DisplayName("본인_저장분의_마스터연결과_트랙연결이_어노테이션_문서에_실린다")
    void ownLabelLinksReachTheAnnotationDocument() throws IOException {
        givenMyLabelOnFrame(10L, "alice-car", "[[5,6],[7,8]]");   // labelId=77, trackId=trk-9
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        String doc = new String(unzip(service.download(RAW_SN, alice)).get("100/frames/0000.json"),
                StandardCharsets.UTF_8);

        assertThat(doc).contains("\"category_id\" : \"77\"");
        assertThat(doc).contains("\"track_id\" : \"trk-9\"");
    }

    @Test
    @DisplayName("컬럼_신설_이전_저장분은_두_값이_비어도_500이_아니라_정상_산출된다")
    void legacyRowWithoutLinksStillProducesADocument() throws IOException {
        // 기존 행은 백필 대상이 아니다 — null 로 남고 그 필드만 빈다.
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of(LsPortalUserLabel.create(
                        "alice", RAW_SN, 10L, "BBOX", "legacy", "[[5,6],[7,8]]")));
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        String doc = new String(unzip(service.download(RAW_SN, alice)).get("100/frames/0000.json"),
                StandardCharsets.UTF_8);

        assertThat(doc).contains("\"category_id\" : null");
        assertThat(doc).contains("\"track_id\" : null");
    }

    // ======================== 산출 구조 (API-203) ========================

    @Test
    @DisplayName("어노테이션_문서는_검수_승인_산출물과_같은_최상위_9키를_갖는다")
    void annotationDocumentHasTheSameNineTopLevelKeys() throws IOException {
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, writeDeidFrame(0)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        byte[] body = unzip(service.download(RAW_SN, alice)).get("100/frames/0000.json");

        assertThat(new ObjectMapper().readTree(body).fieldNames()).toIterable()
                .containsExactly("info", "dataset", "licences", "video", "event",
                        "image", "annotations", "categories", "type");
    }

    @Test
    @DisplayName("비식별_이미지가_없는_프레임은_어노테이션_문서도_담기지_않는다")
    void frameWithoutDeidImageIsSkippedEntirely() throws IOException {
        // 이미지 부재는 원본으로 대체하지 않는다(AC-034). 그 프레임은 <통째로> 산출에서 빠진다 —
        // 검수 승인 산출 경로(DatasetExportWriter)가 원천 이미지 부재 프레임을 같은 방식으로 건너뛴다.
        // ⚠ 구 동작(문서만 남김)은 폐기 — export 와 통일(2026-08-26 사용자 확정). 되돌리지 말 것.
        //    이미지 없는 문서만 남으면 «같은 자리에 이름만 다른 짝» 이라는 구조 규약이 프레임마다 깨진다.
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, null));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        assertThat(zip.keySet()).isEmpty();
    }

    @Test
    @DisplayName("비식별_이미지_경로가_규약_밖이면_그_프레임은_문서까지_함께_빠진다")
    void frameWithRejectedDeidImageIsSkippedEntirely() throws IOException {
        // 경로 판정 실패도 «담을 이미지가 없다» 와 같은 결말이다 — 적재값 부재만 막고 검증 실패를
        // 열어 두면 같은 결함이 다른 문으로 들어온다.
        Path outside = Files.write(tempDir.resolve("outside.jpg"), "X".getBytes(StandardCharsets.UTF_8));
        givenMyLabelOnFrame(10L, "car", "[[5,6],[7,8]]");
        givenFrames(frame(10L, 0, outside), frame(11L, 1, writeDeidFrame(1)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        assertThat(zip.keySet())
                .containsExactlyInAnyOrder("100/frames/0001.json", "100/frames/0001.jpg");
    }

    // ======================== 폐기 프레임 (밖으로 나가는 산출물) ========================

    @Test
    @DisplayName("폐기된_프레임은_ZIP에_들어가지_않는다")
    void discardedFrameNeverReachesTheZip() throws IOException {
        // given: 폐기 프레임(FRM_NO=0)과 정상 프레임(FRM_NO=1)이 있고, 두 조회가 서로 다른 목록을 준다.
        //   폐기 포함 조회로 되돌리면 폐기 프레임이 그대로 담겨 이 단언이 깨진다.
        LsDataSrc discarded = frame(10L, 0, writeDeidFrame(0));
        discarded.discard();
        LsDataSrc kept = frame(11L, 1, writeDeidFrame(1));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(discarded, kept));
        when(srcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(kept));
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of(myLabel(10L, "car", "[[5,6],[7,8]]", 1001L),
                        myLabel(11L, "dog", "[[1,2],[3,4]]", 1002L)));
        when(videoStreamService.resolveDeidPath(RAW_SN)).thenReturn(null);

        Map<String, byte[]> zip = unzip(service.download(RAW_SN, alice));

        // 이 ZIP 은 «밖으로 나가는 산출물» 이라 검수 승인 산출 경로와 같은 조회를 써야 한다.
        assertThat(zip.keySet())
                .containsExactlyInAnyOrder("100/frames/0001.json", "100/frames/0001.jpg");
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
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
                "100/frames/0000.jpg", "100/frames/0007.jpg", "100/frames/0338.jpg",
                // 문서는 이미지와 <같은 자리에 이름만 다른 짝>이다.
                "100/frames/0000.json", "100/frames/0007.json", "100/frames/0338.json");
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
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", RAW_SN))
                .thenReturn(List.of(myLabel(srcSn, label, points, null)));
    }

    private LsPortalUserLabel myLabel(Long srcSn, String label, String points, Long userLblSn) {
        LsPortalUserLabel mine = LsPortalUserLabel.create(
                "alice", RAW_SN, srcSn, "BBOX", label, points, 77L, "trk-9");
        if (userLblSn != null) {
            setField(mine, "userLblSn", userLblSn);
        }
        return mine;
    }

    /**
     * 프레임 목록 — 산출 경로가 쓰는 <b>폐기 제외 조회</b>에만 심는다. 폐기 포함 조회로 되돌리면 이
     * 목록이 비어 ZIP 에 프레임이 한 건도 담기지 않으므로 전 케이스가 함께 무너진다.
     */
    private void givenFrames(LsDataSrc... frames) {
        when(srcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frames));
    }

    private LsDataSrc frame(Long srcSn, long frameNo, Path deidImage) {
        LsDataSrc s = LsDataSrc.create(RAW_SN, frameNo, rawBase.resolve("f" + frameNo + ".jpg").toString(),
                LocalDateTime.now());
        s.attachDeidPath(deidImage == null ? null : deidImage.toString());
        setField(s, "srcSn", srcSn);
        return s;
    }

    private LsDataLbl datamartLabel(Long srcSn, String label) {
        return datamartLabel(srcSn, label, null);
    }

    private LsDataLbl datamartLabel(Long srcSn, String label, Long lblSn) {
        LsDataLbl l = LsDataLbl.createAutoBbox(
                srcSn, null, label, "[[1,2],[3,4]]", BigDecimal.valueOf(0.9), null);
        if (lblSn != null) {
            setField(l, "lblSn", lblSn);
        }
        return l;
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
