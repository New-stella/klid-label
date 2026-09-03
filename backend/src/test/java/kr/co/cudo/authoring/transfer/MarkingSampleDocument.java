package kr.co.cudo.authoring.transfer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 저장소에 함께 둔 <b>실물 마킹 문서 표본</b>을 찾는다.
 *
 * <p>이 형식은 우리가 만든 것이 아니라 받는 것이다. 손으로 지어낸 표본으로 고정하면 지어낼 때의
 * 짐작이 그대로 기대값이 되어, 실제 산출물이 다를 때 시험만 통과하고 적재가 깨진다.
 *
 * <p>⚠ 이 표본은 <b>이벤트 구간이 하나뿐</b>이다. 여러 구간을 합치는 축은 합성 표본으로 따로 덮는다 —
 * 실물만 쓰면 그 축이 검증 대상 0 으로 남는다.
 */
final class MarkingSampleDocument {

    /** 표본 파일 이름. */
    static final String FILE_NAME = "org_REPORT_20260101025100_6756.json";

    /** 표본이 가리키는 영상 파일 이름. */
    static final String VIDEO_FILE_NAME = "org_REPORT_20260101025100_6756.mp4";

    /** 그 이름에서 확장자를 뗀 값 — 적재 시 영상 식별자가 된다. */
    static final String CLIP_ID = "org_REPORT_20260101025100_6756";

    private MarkingSampleDocument() {
    }

    static Path path() {
        Path sample = Paths.get("..", "docs", FILE_NAME);
        if (!Files.isRegularFile(sample)) {
            throw new AssertionError("표본 마킹 문서를 찾을 수 없다. 이 시험은 실물에 고정돼 있다.");
        }
        return sample;
    }
}
