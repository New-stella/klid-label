package kr.co.cudo.authoring.transfer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.stream.Stream;

/**
 * 저장소에 함께 둔 <b>실물 산출물 표본</b>을 찾는다.
 *
 * <p>이 형식은 우리가 만든 것이 아니라 받는 것이다. 손으로 지어낸 표본으로 고정하면 지어낼 때의
 * 짐작이 그대로 기대값이 되어, 실제 산출물이 다를 때 시험만 통과하고 적재가 깨진다.
 *
 * <p>디렉터리 이름이 한글이라 파일시스템마다 자모 결합 형태가 다르다(맥은 분리형으로 저장한다).
 * 문자열을 이어 붙이면 플랫폼에 따라 못 찾으므로 부모를 훑어 정규화 후 비교한다.
 */
final class ImportSampleFolder {

    /** 표본 산출물 폴더 이름. */
    static final String FOLDER_NAME = "00000073";

    /** 표본이 만드는 이관 영상 식별자 — 폴더 이름 + 문서가 선언한 데이터셋 식별자. */
    static final String VMS_CLIP_ID = "IMPORT-00000073-590";

    /** 실제 파일 기준 프레임 수(문서 5건 + 짝 없는 이미지 1장). 문서 선언값 140 이 아니다. */
    static final int FRAME_COUNT = 6;

    /** 문서마다 도형 1건 — 실제 적재되는 라벨 수. */
    static final int LABEL_COUNT = 5;

    /** 도형이 쓴 외부 분류 식별 문자열. */
    static final String LABEL_CATEGORY = "asphalt";

    /** 영상이 가리키는 이벤트 분류 — 산출물은 이벤트를 이름으로만 준다. */
    static final String EVENT_CATEGORY = "도로침수";

    private ImportSampleFolder() {
    }

    static Path path() {
        Path docs = Paths.get("..", "docs");
        try (Stream<Path> entries = Files.list(docs)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> "1차어노테이션".equals(
                            Normalizer.normalize(p.getFileName().toString(), Normalizer.Form.NFC)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "표본 산출물 폴더를 찾을 수 없다. 이 시험은 실물에 고정돼 있다."))
                    .resolve(FOLDER_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
