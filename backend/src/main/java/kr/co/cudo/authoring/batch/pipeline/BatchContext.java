package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.util.List;

/**
 * 배치 파이프라인 단계 간 데이터 운반 컨텍스트 (가변).
 *
 * <p>선언적 파이프라인 ({@link BatchPipeline}) 의 각 단계 ({@link BatchStep}) 가
 * 순차로 읽고/쓰는 단일 운반 객체다. 기존 {@code BatchOrchestrator.process()} 가
 * 지역 변수(markings, marks, hints)로 단계 간 전달하던 것을 본 컨텍스트로 치환한다.
 *
 * <h3>불변/가변 분리</h3>
 * <ul>
 *   <li>{@code rawSn}, {@code raw} 는 생성자 주입 후 불변 — 파이프라인 시작 시 확정.</li>
 *   <li>{@code markings}, {@code marks}, {@code hints} 는 각 단계가 채운다(setter).</li>
 * </ul>
 *
 * <h3>null 금지</h3>
 * 컬렉션 필드는 항상 비어있는 리스트로 초기화되며, setter 에 null 이 전달되면
 * 빈 리스트로 정규화한다 — NPE 를 구조적으로 차단(동작 보존: 기존 orchestrator 도
 * markings 는 repository 가 빈 리스트 반환, marks/hints 도 빈 리스트 기본).
 */
public class BatchContext {

    private final Long rawSn;
    private final LsDataRaw raw;

    /** MARKING 단계가 채움 — findByRawSnOrderByRegDtDesc 결과 (최신 먼저). */
    private List<LsMarking> markings = List.of();
    /** MARKING 단계가 채움 — 최신 마킹의 markCn 을 파싱한 MarkItem 목록. */
    private List<MarkItem> marks = List.of();
    /** YOLO 단계가 채움 — SAM2 단계로 전달할 인메모리 BBOX 힌트. */
    private List<BbHint> hints = List.of();

    public BatchContext(Long rawSn, LsDataRaw raw) {
        this.rawSn = rawSn;
        this.raw = raw;
    }

    public Long getRawSn() {
        return rawSn;
    }

    public LsDataRaw getRaw() {
        return raw;
    }

    public List<LsMarking> getMarkings() {
        return markings;
    }

    public void setMarkings(List<LsMarking> markings) {
        this.markings = markings == null ? List.of() : markings;
    }

    public List<MarkItem> getMarks() {
        return marks;
    }

    public void setMarks(List<MarkItem> marks) {
        this.marks = marks == null ? List.of() : marks;
    }

    public List<BbHint> getHints() {
        return hints;
    }

    public void setHints(List<BbHint> hints) {
        this.hints = hints == null ? List.of() : hints;
    }
}
