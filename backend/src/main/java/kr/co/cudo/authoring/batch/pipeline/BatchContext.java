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

    /** MARKING 단계가 채움 — findByRawSnOrderByRegDtDescMarkingSnDesc 결과 (최신 먼저). */
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

    /**
     * <b>보류 표식</b> — 단계가 "실패가 아닌 조기 중단"을 선언한 지점. {@code null} 이면 보류 없음.
     *
     * <p>{@code CO-009} 에서 신설. 이 컨텍스트를 도는 오케스트레이터는 매 단계 실행 직후 이 값을 확인해
     * 세워져 있으면 <b>이후 단계를 실행하지 않고</b> 파이프라인을 빠져나간다.
     *
     * <h3>왜 예외가 아닌가 (Critical — 되돌리지 말 것)</h3>
     * <p>단계가 {@code RuntimeException} 을 던지면 오케스트레이터가 {@code markRawDataFailed} + 자동
     * 재시도 큐 등록으로 마감한다. 보류는 <b>실패가 아니다</b> — 사람이 설정(프리셋 등)을 채우면 그때
     * 재개되어야 하는 상태라, 예외로 표현하면 ①작업 상태가 {@code FAILED} 로 역행하고 ②자동 재시도가
     * 같은 조건에서 무한히 재시도하며 ③재시도 상한을 소진한다. 반대로 아무 표식 없이 조용히 return 하면
     * 하류 단계가 계속 돌아 <b>산출물만 비어 있는 무증상 성공</b>이 된다(그 경로의 위험은
     * {@code YoloAutolabelStep} javadoc 이 이미 경고한다). 이 표식이 그 둘 사이의 제3의 종결이다.
     *
     * <p>보류 <b>사실의 영속</b>은 이 표식이 담당하지 않는다 — 단계가 {@code LS_BATCH_PROC_LOG} 에
     * SKIPPED 감사 행을 직접 적재하며, 재개 판정도 그 행을 읽는다(인메모리인 이 표식은 프로세스를
     * 넘지 못한다).
     */
    private BatchStage withheldStage;

    /** 보류 사유 — {@code LS_BATCH_PROC_LOG} 에 적재한 사유와 같은 문자열. 로깅·판독용. */
    private String withheldReason;

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

    /**
     * 단계가 <b>보류</b>를 선언한다 — 이 시점 이후 단계는 실행되지 않는다.
     *
     * <p>이미 보류가 세워져 있으면 <b>덮어쓰지 않는다</b>(먼저 선언한 단계가 이긴다). 오케스트레이터가
     * 보류 직후 루프를 빠져나가므로 통상 두 번 불릴 일은 없으나, 한 단계가 내부에서 여러 판정을 하는
     * 경우에 첫 사유가 유지되는 편이 판독에 낫다.
     *
     * @param stage  보류를 선언한 단계. {@code null} 이면 no-op(표식이 서지 않는다)
     * @param reason 보류 사유 — {@code LS_BATCH_PROC_LOG} 에 적재한 값과 같은 문자열
     */
    public void withhold(BatchStage stage, String reason) {
        if (stage == null || withheldStage != null) {
            return;
        }
        this.withheldStage = stage;
        this.withheldReason = reason;
    }

    /** 보류 표식이 서 있는가 — 오케스트레이터가 매 단계 실행 직후 확인한다. */
    public boolean isWithheld() {
        return withheldStage != null;
    }

    /** 보류를 선언한 단계. 보류가 없으면 {@code null}. */
    public BatchStage getWithheldStage() {
        return withheldStage;
    }

    /** 보류 사유. 보류가 없으면 {@code null}. */
    public String getWithheldReason() {
        return withheldReason;
    }
}
