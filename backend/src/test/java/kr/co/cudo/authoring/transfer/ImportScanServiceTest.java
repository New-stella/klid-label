package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.transfer.dto.ImportScanRequest;
import kr.co.cudo.authoring.transfer.dto.ImportScanResponse;
import kr.co.cudo.authoring.transfer.parser.FirstAnnotationParser;
import kr.co.cudo.authoring.transfer.parser.ImportWarningCode;
import kr.co.cudo.authoring.transfer.repository.LsOtsdCtgryMpngRepository;
import kr.co.cudo.authoring.transfer.service.CategoryMatchSuggester;
import kr.co.cudo.authoring.transfer.service.ImportScanService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 검사가 <b>상한과 결손 상황에서 어떻게 답하는지</b>를 고정한다.
 *
 * <h3>왜 통합 시험과 따로 두는가</h3>
 * <p>여기서 확인하는 것은 실물 표본으로는 만들 수 없는 상황이다 — 파일이 상한을 넘는 폴더, 프레임이
 * 하나도 없는 폴더, 이미 가져온 산출물. 표본을 그렇게 만들려면 저장소에 인공 폴더를 늘려야 하고
 * 그 폴더가 곧 또 하나의 짐작이 된다.
 *
 * @design DOMAIN-017
 * @design API-205
 * @design AC-044
 */
class ImportScanServiceTest {

    /** 허용 범위 밖 문서에만 있는 값 — 응답에서 이 문자열이 보이면 링크를 따라간 것이다. */
    private static final String OUTSIDE_VIDEO_MARKER = "OUTSIDE-ONLY-VIDEO.mp4";
    private static final String OUTSIDE_CATEGORY_MARKER = "OUTSIDE-ONLY-CATEGORY";

    @TempDir
    Path root;

    /** 허용 루트 <b>밖</b>. 바로가기가 여기를 가리켜도 내용이 읽히면 안 된다. */
    @TempDir
    Path outside;

    private LsOtsdCtgryMpngRepository mappingRepository;
    private VideoRepository videoRepository;
    private ImportScanService service;

    @BeforeEach
    void setUp() {
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                root.toString(), "", root.toString(), root.toString(), root.toString(), "co-locate");
        mappingRepository = mock(LsOtsdCtgryMpngRepository.class);
        videoRepository = mock(VideoRepository.class);
        service = new ImportScanService(
                new ImportSourcePolicy(resolver),
                new FirstAnnotationParser(new ObjectMapper()),
                mappingRepository,
                mock(CategoryMatchSuggester.class),
                videoRepository);
        ReflectionTestUtils.setField(service, "maxEntries", 3);
    }

    private ImportScanResponse scan(Path folder) {
        return service.scan(new ImportScanRequest(folder.toString(), null));
    }

    @Test
    @DisplayName("파일_수가_상한을_넘으면_조용히_자르지_않고_알린_뒤_적재를_막는다")
    void 파일_수가_상한을_넘으면_조용히_자르지_않고_알린_뒤_적재를_막는다() throws IOException {
        Path folder = Files.createDirectory(root.resolve("huge"));
        for (int i = 0; i < 4; i++) {
            Files.createFile(folder.resolve("0000000" + i + ".jpg"));
        }

        ImportScanResponse response = scan(folder);

        assertThat(response.warnings()).extracting(ImportScanResponse.Warning::code)
                .containsExactly(ImportWarningCode.SCAN_LIMIT_EXCEEDED);
        // 일부만 담아 돌려주면 "덜 들어온 것"과 "원래 그만큼인 것"이 구분되지 않는다.
        assertThat(response.frameCount()).isZero();
        assertThat(response.importable()).isFalse();
    }

    @Test
    @DisplayName("프레임이_하나도_없으면_적재할_것이_없다고_알린다")
    void 프레임이_하나도_없으면_적재할_것이_없다고_알린다() throws IOException {
        Path folder = Files.createDirectory(root.resolve("empty"));
        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.empty());

        ImportScanResponse response = scan(folder);

        assertThat(response.frameCount()).isZero();
        assertThat(response.warnings()).extracting(ImportScanResponse.Warning::code)
                .contains(ImportWarningCode.NO_FRAME_FOUND);
        assertThat(response.importable()).isFalse();
    }

    @Test
    @DisplayName("이미_가져온_산출물이면_그_영상을_알려_주고_적재를_막는다")
    void 이미_가져온_산출물이면_그_영상을_알려_주고_적재를_막는다() throws IOException {
        Path folder = Files.createDirectory(root.resolve("dup"));
        Files.writeString(folder.resolve("00000001.json"), sampleDocument());
        Files.createFile(folder.resolve("00000001.jpg"));

        LsDataRaw existing = mock(LsDataRaw.class);
        when(existing.getRawSn()).thenReturn(4242L);
        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.of(existing));

        ImportScanResponse response = scan(folder);

        assertThat(response.frameCount()).isEqualTo(1);
        assertThat(response.duplicate()).isNotNull();
        assertThat(response.duplicate().rawSn()).isEqualTo(4242L);
        // 같은 산출물을 두 번 가져오면 이미 검수 중이거나 승인된 내용이 덮인다(AC-044).
        assertThat(response.importable()).isFalse();
    }

    @Test
    @DisplayName("폴더_안의_바로가기는_따라가지_않고_그_내용이_응답에_새지_않는다")
    void 폴더_안의_바로가기는_따라가지_않고_그_내용이_응답에_새지_않는다() throws IOException {
        // 허용 범위 밖에 둔 문서·이미지 — 이 내용이 응답에 나타나면 경로 판정이 뚫린 것이다.
        Path outsideDocument = outside.resolve("secret.json");
        Files.writeString(outsideDocument, outsideDocument());
        Path outsideImage = outside.resolve("secret.jpg");
        Files.createFile(outsideImage);

        Path folder = Files.createDirectory(root.resolve("linked"));
        Files.writeString(folder.resolve("00000001.json"), sampleDocument());
        Files.createFile(folder.resolve("00000001.jpg"));
        // 산출물 문서·이미지처럼 이름 붙인 바로가기 — 폴더 판정은 이 한 겹을 보지 못한다.
        Files.createSymbolicLink(folder.resolve("00000002.json"), outsideDocument);
        Files.createSymbolicLink(folder.resolve("00000002.jpg"), outsideImage);
        ReflectionTestUtils.setField(service, "maxEntries", 10);
        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.empty());

        ImportScanResponse response = scan(folder);

        // 응답 어디에도 밖의 값이 없어야 한다 — 필드를 하나씩 훑으면 새로 생긴 자리를 놓친다.
        assertThat(asJson(response))
                .doesNotContain(OUTSIDE_VIDEO_MARKER)
                .doesNotContain(OUTSIDE_CATEGORY_MARKER);
        // 링크를 따라갔다면 밖의 문서가 프레임이 되고 밖의 분류가 미확정 목록에 실린다.
        assertThat(response.frameCount()).isEqualTo(1);
        assertThat(response.videoFileName()).isEqualTo("clip.mp4");
        assertThat(response.unmappedCategories()).isEmpty();
        // 건너뛴 사실은 알린다 — 조용히 빼면 "덜 들어온 것"이 "원래 그만큼인 것"으로 읽힌다.
        assertThat(response.warnings())
                .filteredOn(w -> ImportWarningCode.SYMBOLIC_LINK_SKIPPED.equals(w.code()))
                .hasSize(2);
    }

    private static String asJson(ImportScanResponse response) {
        try {
            return new ObjectMapper().writeValueAsString(response);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * 허용 범위 밖 문서 — 새어 나오면 곧바로 눈에 띄도록 고유한 값만 담는다.
     *
     * <p>일부러 <b>미확정 분류</b>를 하나 넣는다. 그래야 링크를 따라간 경우 그 값이 응답의 미확정 분류
     * 목록에 실려, 프레임 수만 보는 것보다 넓은 자리에서 새는 것을 잡는다.
     */
    private static String outsideDocument() {
        return """
                {
                  "dataset": {"identifier": "999", "total_count": 1},
                  "video": {"file_name": "%s"},
                  "image": {"file_name": "00000002.jpg", "frame_num": 2},
                  "categories": [
                    {"id": "%s", "name": "%s", "type": "POLYGON"}
                  ],
                  "annotations": [
                    {"id": "1", "category_id": "%s",
                     "polygon": [[0.0, 0.0, 10.0, 0.0, 10.0, 10.0]]}
                  ]
                }
                """.formatted(OUTSIDE_VIDEO_MARKER,
                OUTSIDE_CATEGORY_MARKER, OUTSIDE_CATEGORY_MARKER, OUTSIDE_CATEGORY_MARKER);
    }

    /** 분류가 하나도 없는 최소 문서 — 미확정 분류 때문에 막히는 것과 구분해서 보기 위한 표본. */
    private static String sampleDocument() {
        return """
                {
                  "dataset": {"identifier": "590", "total_count": 1},
                  "video": {"file_name": "clip.mp4"},
                  "image": {"file_name": "00000001.jpg", "frame_num": 1},
                  "annotations": [],
                  "categories": []
                }
                """;
    }
}
