package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.parser.MarkingWarning;

import java.nio.file.Path;
import java.util.List;

/**
 * 마킹 문서 한 건에 대한 <b>판정 결과</b> — 검사 응답과 적재 대상 선정이 같은 값을 본다.
 *
 * <h3>왜 하나로 모으는가</h3>
 * <p>검사는 사람이 보고 결정하는 자리이고 적재는 그 결정을 실행하는 자리다. 두 자리가 판정을 각자
 * 하면 <b>사람이 본 목록과 실제로 적재되는 목록이 갈린다</b> — 화면에는 적재 가능으로 보였는데
 * 건너뛰거나, 반대로 막힌 것으로 보였는데 들어간다. 같은 판정기가 만든 같은 값을 둘 다 쓴다.
 *
 * @param markingPath     마킹 문서의 실경로
 * @param markingFileName 마킹 문서의 이름
 * @param videoPath       짝지은 영상의 실경로. 짝을 찾지 못했으면 {@code null}
 * @param videoFileName   마킹 문서가 가리키는 영상 파일 이름
 * @param clipId          영상 파일 이름에서 확장자를 뗀 값 — 적재 시 영상 식별자
 * @param segmentCount    마킹 문서가 담은 이벤트 구간 수
 * @param markCount       정리한 시점 수
 * @param declaredFps     문서에서 역산한 프레임 재생 속도
 * @param probedFps       영상에서 읽은 프레임 재생 속도
 * @param videoFrameCount 영상의 전체 프레임 수
 * @param importable      지금 상태로 적재할 수 있는가 — <b>판정은 이 값 하나가 한다</b>
 * @param warnings        알려야 하는 사항(적재를 막는 것과 막지 않는 것이 섞여 있다)
 * @design DOMAIN-017
 * @design API-216
 * @design API-217
 */
public record MarkingCandidate(
        Path markingPath,
        String markingFileName,
        Path videoPath,
        String videoFileName,
        String clipId,
        int segmentCount,
        int markCount,
        Double declaredFps,
        Double probedFps,
        Integer videoFrameCount,
        boolean importable,
        List<MarkingWarning> warnings) {

    public MarkingCandidate {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 짝을 찾았는가 — 같은 이름이 둘 이상이면 찾지 못한 것으로 다룬다. */
    public boolean videoFound() {
        return videoPath != null;
    }
}
