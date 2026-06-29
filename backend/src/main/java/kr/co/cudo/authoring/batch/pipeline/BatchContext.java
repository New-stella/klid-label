package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.util.List;
import java.util.Map;

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
    /**
     * stage 토글 (Phase 3 — 조건부 step). 키는 {@link BatchStage#name()}, 값은 enabled 여부.
     * 비어있거나 키 미존재면 해당 stage 는 enabled(true) — 프로덕션 경로 무영향.
     */
    private final Map<String, Boolean> stageToggles;

    /** MARKING 단계가 채움 — findByRawSnOrderByRegDtDesc 결과 (최신 먼저). */
    private List<LsMarking> markings = List.of();
    /** MARKING 단계가 채움 — 최신 마킹의 markCn 을 파싱한 MarkItem 목록. */
    private List<MarkItem> marks = List.of();
    /** YOLO 단계가 채움 — SAM2 단계로 전달할 인메모리 BBOX 힌트. */
    private List<BbHint> hints = List.of();
    /**
     * DEIDENTIFY 단계가 채움 — 비식별이 <b>동기 완료(mock, DE_IDNTF_YN='Y')</b> 되었는지 여부.
     *
     * <p>기본 {@code false}(미완료/지연). 외부 위탁(KPST)은 제출만 하고 지연되므로 false 로 남고,
     * 완료(MARKING_READY 전이)는 폴링 잡이 단일 지점에서 수행한다. 선두 비식별 러너
     * ({@code AsyncDeidentifyRunner})는 이 값이 {@code true} 일 때만 MARKING_READY 로 전이한다 —
     * 비식별 미완료 상태의 dataSttsCd 조기 전이(NOT_FOUND 스트림)를 차단한다.
     */
    private boolean deidentCompleted = false;

    /** 프로덕션 기본 — 토글 없음(전 stage enabled). 기존 호출부 100% 보존. */
    public BatchContext(Long rawSn, LsDataRaw raw) {
        this(rawSn, raw, null);
    }

    /**
     * stage 토글을 주입하는 오버로드 (Phase 3 — dev 토글 경로 전용).
     * {@code stageToggles == null} 이면 빈 맵으로 정규화 — 전 stage enabled.
     */
    public BatchContext(Long rawSn, LsDataRaw raw, Map<String, Boolean> stageToggles) {
        this.rawSn = rawSn;
        this.raw = raw;
        // null 값 항목도 허용하도록 방어 복사(Map.copyOf 는 null 값 거부). null 값은 getOrDefault 가
        // null 을 반환하므로 isStageEnabled 에서 별도 null 처리.
        this.stageToggles = stageToggles == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(stageToggles));
    }

    /**
     * 주어진 stage 가 enabled 인지 여부. 토글 맵에 키가 없으면 기본 {@code true}.
     */
    public boolean isStageEnabled(BatchStage stage) {
        if (stage == null) {
            return true;
        }
        Boolean enabled = stageToggles.get(stage.name());
        // 키 미존재(null) 또는 명시값 null → 기본 enabled.
        return enabled == null ? true : enabled;
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

    /** 비식별이 동기 완료(mock)되었는지 — 선두 비식별 러너의 조건부 MARKING_READY 전이 판단용. */
    public boolean isDeidentCompleted() {
        return deidentCompleted;
    }

    /** DEIDENTIFY 단계가 run() 결과(completed)를 기록한다. */
    public void markDeidentCompleted(boolean deidentCompleted) {
        this.deidentCompleted = deidentCompleted;
    }
}
