package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyPayloadFactory;
import kr.co.cudo.authoring.controlnotify.service.VersionExplanationPolicy;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.json.CategoryMapper;
import kr.co.cudo.authoring.dataset.export.json.LabelToAnnotationMapper;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.dataset.export.json.VideoMetaMapper;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>A-3 / A-1 교차 검증</b> — 프레임 파일명이 <b>세 지점</b>에서 모두 같은지 한 테스트에서 단언한다.
 *
 * <ol>
 *   <li>관제 수정 통지 {@code changed_items} 가 싣는 파일명</li>
 *   <li>export writer 가 실제로 디스크에 만든 파일명</li>
 *   <li>그 JSON <b>내부</b>의 {@code image.file_name}</li>
 * </ol>
 *
 * <p>구 버전은 ①②만 보고 {@link NiaJsonBuilder} 를 mock 으로 가려 ③을 검증하지 않았고, 그 결과
 * {@code 0338.json} 안의 {@code file_name} 이 같은 폴더에 없는 {@code frame-338.jpg} 를 가리키는
 * 유일한 결함이 GREEN 인 채로 통과했다. 그래서 여기서는 <b>실제 빌더</b>를 쓴다.
 */
class ExportNamingContractTest {

    private static final long RAW_SN = 7L;

    @TempDir
    Path labelingRoot;
    @TempDir
    Path srcDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FrameContext frameCtx(long frameNo) {
        return new FrameContext(
                LsDataSrc.create(RAW_SN, frameNo, "/nas/frames/raw/7/src.jpg", null), List.of());
    }

    private VideoExportContext context(NiaJsonBuilder builder) {
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(RAW_SN)
                .rawFilePathNm("/nas/raw/7/original.mp4")
                .shtDt(LocalDateTime.of(2026, 3, 3, 10, 0))
                .vdoWdth(1920)
                .vdoHgt(1080)
                .prvcYn("N")
                .build();
        LsDataRaw raw = LsDataRaw.builder()
                .vmsClipId("clip-7")
                .rawFilePathNm("/nas/raw/7/original.mp4")
                .shtDt(LocalDateTime.of(2026, 3, 3, 10, 0))
                .build();
        return builder.prepareContext(meta, raw, List.of());
    }

    @Test
    @DisplayName("통지_changed_items와_디스크_파일명과_JSON내부_file_name이_모두_일치한다")
    void notifyFileNamesMatchExportedFileNamesAndJsonFileName() throws IOException {
        // given — 동일한 프레임 집합(FRM_NO 0 / 7 / 338)에 대해 통지 페이로드와 산출물을 각각 만든다.
        List<Long> frameNos = List.of(0L, 7L, 338L);

        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        when(srcRepository.findExportableFrameNosByRawSn(RAW_SN)).thenReturn(frameNos);
        ControlNotifyPayloadFactory payloadFactory = new ControlNotifyPayloadFactory(
                mock(VideoRepository.class), srcRepository,
                mock(IngestSourceRepository.class), mock(LsDatasetExportRepository.class),
                mock(ControlNotifyMetrics.class));

        FrameSource frameSource = mock(FrameSource.class);
        // ★ mock 금지 — JSON 내부 file_name 까지 검증해야 하므로 실제 빌더를 쓴다.
        NiaJsonBuilder niaJsonBuilder = new NiaJsonBuilder(
                new LabelToAnnotationMapper(objectMapper), new VideoMetaMapper(), new CategoryMapper());
        Path image = Files.writeString(srcDir.resolve("s.jpg"), "img");
        when(frameSource.resolveImage(anyLong(), any(), any())).thenReturn(Optional.of(image));
        DatasetExportWriter writer = new DatasetExportWriter(
                new DatasetExportPathResolver(ArtifactRootTestSupport.labelingRoot(labelingRoot)),
                frameSource, niaJsonBuilder, objectMapper);

        // when — ① 관제 통지 페이로드 ② 실제 export 산출
        TaskModifiedPayload payload = payloadFactory.buildModifiedForAllFrames(
                RAW_SN, VersionExplanationPolicy.REVIEW_COMPLETED);
        ExportResult result = writer.write(RAW_SN, null, ExportKind.ORIGINAL, 1,
                context(niaJsonBuilder), frameNos.stream().map(this::frameCtx).toList());

        // then — ① 통지가 알린 모든 파일명이 산출 디렉토리에 실재해야 한다.
        assertThat(payload.changedItems().images()).containsExactly("0000.jpg", "0007.jpg", "0338.jpg");
        assertThat(payload.changedItems().jsons()).containsExactly("0000.json", "0007.json", "0338.json");
        for (String name : payload.changedItems().images()) {
            assertThat(result.dir().resolve(name))
                    .as("통지가 알린 이미지 파일이 산출물에 존재해야 한다: %s", name)
                    .exists();
        }
        for (String name : payload.changedItems().jsons()) {
            assertThat(result.dir().resolve(name))
                    .as("통지가 알린 JSON 파일이 산출물에 존재해야 한다: %s", name)
                    .exists();
        }

        // ③ JSON 내부 image.file_name 이 "같은 폴더에 실재하는" 이미지 파일을 가리켜야 한다(A-1).
        for (int i = 0; i < frameNos.size(); i++) {
            String jsonName = payload.changedItems().jsons().get(i);
            String expectedImage = payload.changedItems().images().get(i);
            JsonNode doc = objectMapper.readTree(result.dir().resolve(jsonName).toFile());
            String fileNameInJson = doc.get("image").get("file_name").asText();
            assertThat(fileNameInJson)
                    .as("%s 내부 file_name 이 통지·디스크 파일명과 같아야 한다", jsonName)
                    .isEqualTo(expectedImage);
            assertThat(result.dir().resolve(fileNameInJson))
                    .as("JSON 이 가리키는 이미지가 같은 폴더에 실재해야 한다: %s", fileNameInJson)
                    .exists();
        }

        // 역방향 — 산출물에 통지가 모르는 파일명(구 frame-{n} 형식 등)이 섞여 있으면 안 된다.
        try (var entries = Files.list(result.dir())) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .allMatch(name -> payload.changedItems().images().contains(name)
                            || payload.changedItems().jsons().contains(name));
        }
    }
}
