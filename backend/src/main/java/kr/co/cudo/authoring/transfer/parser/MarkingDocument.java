package kr.co.cudo.authoring.transfer.parser;

import kr.co.cudo.authoring.marking.dto.MarkItem;

import java.util.List;

/**
 * 마킹 문서 한 건을 읽어 낸 결과 — 외부가 준 <b>이벤트 구간 목록</b>을 저작도구가 쓰는 모양으로 옮긴다.
 *
 * <h3>이 기록이 담지 <b>않는</b> 것도 의도다</h3>
 * <ul>
 *   <li><b>이벤트 유형</b> — 문서의 비고 항목은 유형 코드가 아니라 사람이 적은 일반 문장이다
 *       (표본 값이 그냥 「이벤트」다). 유형은 사람이 화면에서 지정하며 여기서 파싱하지 않는다.</li>
 *   <li><b>프레임 이미지 파일</b> — 문서가 이미지 이름을 함께 주지만 적재하지 않고 <b>시점 위치
 *       정보로만</b> 쓴다(ADR-052). 그 이미지는 비식별 이전 원본이고, 프레임 추출 단계가 같은
 *       시점에서 원본·비식별본 두 벌을 다시 만든다.</li>
 * </ul>
 *
 * @param videoFileName 문서가 가리키는 영상 파일 이름 — <b>파일 이름만 남도록 정제</b>한 값.
 *                      정제할 수 없으면 {@code null}
 * @param rawVideoPath  문서가 함께 담은 원래 경로 <b>원문</b>. 다른 체계에서 만들어진 값이라
 *                      위치로 쓰지 않고 보관만 한다(SEQ-030). 없으면 {@code null}
 * @param clipId        {@link #videoFileName} 에서 확장자를 뗀 값 — 적재 시 영상 식별자가 된다.
 *                      쓸 수 없으면 {@code null}
 * @param segmentCount  문서가 담은 이벤트 구간 수
 * @param marks         구간을 합쳐 정리한 시점 — <b>프레임 번호 오름차순·중복 제거</b>
 * @param declaredFps   문서의 프레임 번호와 시각으로 역산한 프레임 재생 속도. 역산 불가 시 {@code null}
 * @param warnings      읽는 도중 알려야 하는 사항
 * @design DOMAIN-017
 * @design ADR-052
 * @design API-216
 * @design SEQ-030
 */
public record MarkingDocument(
        String videoFileName,
        String rawVideoPath,
        String clipId,
        int segmentCount,
        List<MarkItem> marks,
        Double declaredFps,
        List<MarkingWarning> warnings) {

    public MarkingDocument {
        marks = marks == null ? List.of() : List.copyOf(marks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 구간을 합쳐 정리한 시점 수 — 정렬하고 중복을 없앤 뒤의 값이다(API-216). */
    public int markCount() {
        return marks.size();
    }

    /** 짝을 찾을 축(영상 파일 이름)과 적재할 축(식별자·시점)이 모두 있는가. */
    public boolean hasUsableContent() {
        return videoFileName != null && clipId != null && !marks.isEmpty();
    }
}
