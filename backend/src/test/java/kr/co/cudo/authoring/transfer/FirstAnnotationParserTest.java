package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.transfer.parser.FirstAnnotationParser;
import kr.co.cudo.authoring.transfer.parser.ImportShootingEnvironment;
import kr.co.cudo.authoring.transfer.parser.ImportWarningCode;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1차 어노테이션 파서 — <b>실물 산출물 하나에 고정</b>한 단위 시험.
 *
 * <h3>왜 실물인가</h3>
 * <p>이 형식은 우리가 만든 것이 아니라 <b>받는</b> 것이다. 손으로 지어낸 표본으로 고정하면 지어낼 때의
 * 짐작이 그대로 기대값이 되어, 실제 산출물이 그 짐작과 다를 때 시험은 통과하고 적재만 깨진다.
 * 그래서 저장소에 함께 둔 산출물 폴더를 그대로 읽는다.
 *
 * <h3>표본이 마침 담고 있는 것</h3>
 * <p>이 폴더는 문서 5건과 이미지 6장이라 <b>짝 문서가 없는 이미지</b>가 하나 있고, 문서가 선언한
 * 건수(140)도 실제 파일 수와 다르다. 둘 다 적재를 막지 않고 경고로 알려야 하는 상황이라(AC-047)
 * 그 규칙을 실물로 검증할 수 있다.
 *
 * @design DOMAIN-017
 * @design AC-047
 */
class FirstAnnotationParserTest {

    private final FirstAnnotationParser parser = new FirstAnnotationParser(new ObjectMapper());

    /**
     * 표본 폴더 경로.
     *
     * <p>디렉터리 이름이 한글이라 파일시스템마다 자모 결합 형태가 다르다(맥은 분리형으로 저장한다).
     * 문자열을 그대로 이어 붙이면 플랫폼에 따라 못 찾으므로, 부모를 훑어 <b>정규화 후 비교</b>한다.
     */
    private static Path sampleFolder() {
        Path docs = Paths.get("..", "docs");
        try (Stream<Path> entries = Files.list(docs)) {
            Path root = entries
                    .filter(Files::isDirectory)
                    .filter(p -> "1차어노테이션".equals(
                            Normalizer.normalize(p.getFileName().toString(), Normalizer.Form.NFC)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "표본 산출물 폴더를 찾을 수 없다. 이 시험은 실물에 고정돼 있어 표본이 없으면 성립하지 않는다."));
            return root.resolve("00000073");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("표본_폴더를_읽으면_문서_5건이_프레임으로_들어오고_짝_없는_이미지_1장도_라벨_없는_프레임이_된다")
    void 표본_폴더를_읽으면_문서_5건이_프레임으로_들어오고_짝_없는_이미지_1장도_라벨_없는_프레임이_된다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());

        // 문서 짝이 있는 프레임 5건 — 산출물 문서 수와 같다.
        assertThat(dataset.documentedFrameCount()).isEqualTo(5);
        // 짝 문서가 없는 이미지도 프레임이다(AC-047) — 그래서 전체는 6건이다.
        assertThat(dataset.frames()).hasSize(6);

        ImportedDataset.Frame unpaired = dataset.frames().get(5);
        assertThat(unpaired.hasDocument()).isFalse();
        assertThat(unpaired.hasImage()).isTrue();
        assertThat(unpaired.imageFileName()).isEqualTo("00000006.jpg");
        assertThat(unpaired.shapes()).isEmpty();
    }

    @Test
    @DisplayName("짝이_없거나_선언_건수가_달라도_거부하지_않고_경고만_남긴다")
    void 짝이_없거나_선언_건수가_달라도_거부하지_않고_경고만_남긴다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());

        assertThat(dataset.warnings()).extracting(ImportedDataset.Warning::code)
                .contains(ImportWarningCode.UNPAIRED_IMAGE, ImportWarningCode.DECLARED_COUNT_MISMATCH);
        // 선언값(140)이 아니라 실제 파일 수를 기준으로 담는다.
        assertThat(dataset.info().totalCount()).isEqualTo(140);
        assertThat(dataset.frames()).hasSize(6);
    }

    @Test
    @DisplayName("도형_라벨과_텍스트_3종이_같은_배열에_섞여_와도_갈라서_담긴다")
    void 도형_라벨과_텍스트_3종이_같은_배열에_섞여_와도_갈라서_담긴다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());
        ImportedDataset.Frame first = dataset.frames().get(0);

        // 표본의 첫 문서에는 도형 1건 + 텍스트 3건이 한 배열에 섞여 있다.
        assertThat(first.shapes()).hasSize(1);
        ImportedDataset.Shape shape = first.shapes().get(0);
        assertThat(shape.categoryId()).isEqualTo("asphalt");
        assertThat(shape.categoryName()).isEqualTo("도로");
        assertThat(shape.shapeType()).isEqualTo("POLYGON");
        // 빈 문자열 추적 아이디는 "없음"으로 읽는다.
        assertThat(shape.trackId()).isNull();

        ImportedDataset.Texts texts = first.texts();
        assertThat(texts.isEmpty()).isFalse();
        assertThat(texts.imageDescription()).isEqualTo("상가 부근에 있는 인도및 어린이보호구역 도로 침수 발생");
        assertThat(texts.privacyIncluded()).isEqualTo("N");
        assertThat(texts.deIdentification()).isEqualTo("Y");
        assertThat(texts.others()).isEmpty();
    }

    @Test
    @DisplayName("평면으로_나열된_다각형_좌표가_저작도구_정규_형식의_좌표쌍으로_바뀐다")
    void 평면으로_나열된_다각형_좌표가_저작도구_정규_형식의_좌표쌍으로_바뀐다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());
        ImportedDataset.Shape shape = dataset.frames().get(0).shapes().get(0);

        // 산출물은 링 배열 안에 좌표를 평면으로 나열한다(218개 = 109쌍).
        assertThat(shape.polygonRings()).hasSize(1);
        List<Point> ring = shape.polygonRings().get(0);
        assertThat(ring).hasSize(109);
        assertThat(ring.get(0).x()).isEqualTo(650.0877160704122);
        assertThat(ring.get(0).y()).isEqualTo(75.71215968838072);
        assertThat(ring.get(1).x()).isEqualTo(620.6977422273003);

        // 저장 형식으로 직렬화하면 정규 형식이 된다 — 소수점은 잘리지 않는다.
        String pointCn = LabelPointSerializer.toJson(ring, new ObjectMapper());
        assertThat(pointCn).startsWith("[[650.0877160704122,75.71215968838072],[620.6977422273003,");
    }

    @Test
    @DisplayName("프레임_번호는_영상_내_실제_위치로_읽고_추출_순번과_섞지_않는다")
    void 프레임_번호는_영상_내_실제_위치로_읽고_추출_순번과_섞지_않는다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());

        // 표본은 600프레임 간격으로 뽑혀 있다 — 300, 900, 1500, 2100, 2700.
        assertThat(dataset.frames().subList(0, 5))
                .extracting(ImportedDataset.Frame::videoFrameNo)
                .containsExactly(300L, 900L, 1500L, 2100L, 2700L);
    }

    @Test
    @DisplayName("영상_블록의_선언_프레임_수는_총_프레임이_아니라_라벨링_대상_프레임_수다")
    void 영상_블록의_선언_프레임_수는_총_프레임이_아니라_라벨링_대상_프레임_수다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());
        ImportedDataset.VideoBlock video = dataset.video();

        // 재생 길이 4201810ms 에 20fps 면 총 프레임은 8만대다. 선언값 140 은 그 수가 아니다.
        assertThat(video.lengthMillis()).isEqualTo(4_201_810L);
        assertThat(video.fps()).isEqualTo(20.0);
        assertThat(video.labeledFrames()).isEqualTo(140);
        assertThat(video.durationSec()).isEqualTo(4201);
        long totalFramesOfVideo = Math.round(video.lengthMillis() / 1000.0 * video.fps());
        assertThat(totalFramesOfVideo).isGreaterThan(80_000L);
        assertThat(video.labeledFrames()).isLessThan((int) totalFramesOfVideo);
    }

    @Test
    @DisplayName("영상_메타는_저작도구에_대응_컬럼이_없는_값까지_버리지_않고_담는다")
    void 영상_메타는_저작도구에_대응_컬럼이_없는_값까지_버리지_않고_담는다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());
        ImportedDataset.VideoBlock video = dataset.video();

        assertThat(video.externalVideoId()).isEqualTo("590");
        assertThat(video.fileName()).isEqualTo("은평구51.mp4");
        assertThat(video.stdgCd()).isEqualTo("1138000000");
        assertThat(video.width()).isEqualTo(1280);
        assertThat(video.height()).isEqualTo(720);
        assertThat(video.bitRate()).isEqualTo(1_572_991L);
        assertThat(video.privacyIncluded()).isEqualTo("Y");
        assertThat(video.aiGenerated()).isEqualTo("N");
        assertThat(video.eventLevel1Name()).isEqualTo("자연재난");
        assertThat(video.eventLevel2Name()).isEqualTo("침수(범람)");
        assertThat(video.eventLevel3Name()).isEqualTo("도로침수");
        // 대응 컬럼이 없어 메타로 보관해야 하는 값들 — 버리면 되돌릴 수 없다.
        assertThat(video.coordinates()).isEqualTo("37.6159028, 126.9327926");
        assertThat(video.location()).isEqualTo("서울특별시 은평구");
        assertThat(video.eventLog()).isEqualTo("서울특별시 은평구 도로침수");
        assertThat(video.cctvName()).isEqualTo("은평구51");
        // 단위가 확인되지 않은 값은 숫자로 해석하지 않고 원문으로 둔다.
        assertThat(video.fileSizeRaw()).isEqualTo("793");
    }

    @Test
    @DisplayName("촬영환경은_정규값으로_담기고_빈_날씨는_경고_없이_비운다")
    void 촬영환경은_정규값으로_담기고_빈_날씨는_경고_없이_비운다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());
        ImportedDataset.VideoBlock video = dataset.video();

        assertThat(video.timeOfDay()).isEqualTo("DAY");
        assertThat(video.season()).isEqualTo("SUMMER");
        // 표본의 날씨는 빈 값이다 — "적지 않았다"이지 "모르는 값"이 아니므로 경고 대상이 아니다.
        assertThat(video.weather()).isNull();
        assertThat(dataset.warnings()).extracting(ImportedDataset.Warning::code)
                .doesNotContain(ImportWarningCode.UNKNOWN_WEATHER);
    }

    @Test
    @DisplayName("확인되지_않은_경계상자와_키포인트는_없으므로_해당_경고도_뜨지_않는다")
    void 확인되지_않은_경계상자와_키포인트는_없으므로_해당_경고도_뜨지_않는다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());

        assertThat(dataset.frames().get(0).shapes().get(0).bbox()).isNull();
        assertThat(dataset.frames().get(0).shapes().get(0).keypointsRaw()).isNull();
        assertThat(dataset.warnings()).extracting(ImportedDataset.Warning::code)
                .doesNotContain(ImportWarningCode.UNRESOLVED_BBOX, ImportWarningCode.UNRESOLVED_KEYPOINTS);
    }

    @Test
    @DisplayName("시간대와_계절_별칭은_정규값으로_옮기고_모르는_표기는_지어내지_않는다")
    void 시간대와_계절_별칭은_정규값으로_옮기고_모르는_표기는_지어내지_않는다() {
        assertThat(ImportShootingEnvironment.timeOfDay("NIGHT")).isEqualTo("NGT");
        assertThat(ImportShootingEnvironment.timeOfDay(" night ")).isEqualTo("NGT");
        assertThat(ImportShootingEnvironment.timeOfDay("DAY")).isEqualTo("DAY");
        assertThat(ImportShootingEnvironment.season("AUTUMN")).isEqualTo("FALL");
        assertThat(ImportShootingEnvironment.season("fall")).isEqualTo("FALL");
        assertThat(ImportShootingEnvironment.season("SUMMER")).isEqualTo("SUMMER");

        // 모르는 표기는 비운다 — 짐작해 옮기면 그 짐작이 학습데이터에 실린다.
        assertThat(ImportShootingEnvironment.timeOfDay("EVENING")).isNull();
        assertThat(ImportShootingEnvironment.season("RAINY")).isNull();
        // 날씨는 별칭표가 없다 — 허용값과 정확히 같을 때만 통과한다.
        assertThat(ImportShootingEnvironment.weather("맑음")).isEqualTo("맑음");
        assertThat(ImportShootingEnvironment.weather("CLEAR")).isNull();
    }

    @Test
    @DisplayName("외부_파일명은_마지막_요소만_취해_경로가_되지_못하게_한다")
    void 외부_파일명은_마지막_요소만_취해_경로가_되지_못하게_한다() {
        assertThat(ExternalNameSanitizer.fileName("../../etc/passwd", 200)).isEqualTo("passwd");
        assertThat(ExternalNameSanitizer.fileName("C:\\\\temp\\\\a.jpg", 200)).isEqualTo("a.jpg");
        assertThat(ExternalNameSanitizer.fileName("..", 200)).isNull();
        assertThat(ExternalNameSanitizer.fileName("   ", 200)).isNull();
        // 개행이 섞인 값이 로그 줄을 위조하지 못하게 한다(CWE-117).
        assertThat(ExternalNameSanitizer.fileName("a\nb.jpg", 200)).isEqualTo("ab.jpg");
        // 식별자는 조립 안전이 우선이라 허용 문자만 남는다.
        assertThat(ExternalNameSanitizer.identifier("00000073", 50)).isEqualTo("00000073");
        assertThat(ExternalNameSanitizer.identifier("../590", 50)).isEqualTo("___590");
    }

    @Test
    @DisplayName("이관_식별자와_저장_경로가_확정_형식으로_조립된다")
    void 이관_식별자와_저장_경로가_확정_형식으로_조립된다() {
        String clipId = ImportPathPolicy.vmsClipId("00000073", "590");
        assertThat(clipId).isEqualTo("IMPORT-00000073-590");

        // 마지막 이름은 산출물이 준 원본 파일명이다 — 학습데이터 산출물의 영상 파일명이 여기서 나온다.
        assertThat(ImportPathPolicy.rawFilePath("/nas-storage/raw", clipId, "은평구51.mp4"))
                .isEqualTo("/nas-storage/raw/imports/IMPORT-00000073-590/은평구51.mp4");
        // 기준경로 끝의 구분자 유무로 경로가 갈리지 않는다.
        assertThat(ImportPathPolicy.rawFilePath("/nas-storage/raw/", clipId, "은평구51.mp4"))
                .isEqualTo("/nas-storage/raw/imports/IMPORT-00000073-590/은평구51.mp4");
    }

    @Test
    @DisplayName("미리보기가_세는_라벨_수는_실제로_적재될_도형만이다_경계상자는_빠진다")
    void 미리보기가_세는_라벨_수는_실제로_적재될_도형만이다_경계상자는_빠진다() throws Exception {
        // 표본에는 경계상자가 없다(폴리곤뿐) — 그래서 이 어긋남은 실물만으로는 드러나지 않는다.
        //   미리보기가 「적재될 라벨 수」라고 약속한 값이 실제 적재량보다 커지는 상황을 고정한다.
        Path folder = Files.createTempDirectory("import-bbox-count");
        Files.writeString(folder.resolve("0001.json"), """
                {"dataset": {"identifier": "D1", "total_count": 1},
                 "video": {"id": "V1", "file_name": "a.mp4"},
                 "image": {"id": "IMG1", "file_name": "0001.jpg", "frame_num": 0},
                 "categories": [{"id": "c1", "name": "가", "type": "BBOX"},
                                {"id": "c2", "name": "나", "type": "POLYGON"}],
                 "annotations": [
                   {"id": "a1", "image_id": "IMG1", "category_id": "c1", "bbox": [1, 2, 3, 4]},
                   {"id": "a2", "image_id": "IMG1", "category_id": "c2",
                    "polygon": [[10, 10, 20, 10, 20, 20]]}
                 ]}
                """);

        ImportedDataset dataset = parser.parseFolder(folder);

        // 도형은 둘이지만 좌표가 있는 것은 하나다.
        assertThat(dataset.frames().get(0).shapes()).hasSize(2);
        assertThat(dataset.loadableLabelCount())
                .as("경계상자는 해석 규칙이 확정되지 않아 라벨이 되지 않으므로 미리보기도 세지 않는다")
                .isEqualTo(1);
        assertThat(dataset.warnings()).extracting(ImportedDataset.Warning::code)
                .contains(ImportWarningCode.UNRESOLVED_BBOX);
    }

    @Test
    @DisplayName("적재_가능_판정은_Shape_한_곳이라_좌표가_없으면_거짓이다")
    void 적재_가능_판정은_Shape_한_곳이라_좌표가_없으면_거짓이다() {
        ImportedDataset dataset = parser.parseFolder(sampleFolder());

        // 표본의 도형은 전부 폴리곤이라 모두 적재 대상이고, 그 수가 곧 적재될 라벨 수다.
        assertThat(dataset.frames().stream().flatMap(f -> f.shapes().stream()))
                .allMatch(ImportedDataset.Shape::loadableAsLabel);
        assertThat(dataset.loadableLabelCount()).isEqualTo(5);
    }
}
