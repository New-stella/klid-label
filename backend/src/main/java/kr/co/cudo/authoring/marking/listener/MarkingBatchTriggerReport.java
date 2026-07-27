package kr.co.cudo.authoring.marking.listener;

/**
 * 마킹 완료 → 배치 트리거 결과를 <b>같은 요청 스레드</b>로 되돌려 주는 스레드 로컬 리포트 (DEV_FIX H11).
 *
 * <h3>왜 필요한가 — "201 인데 아무 일도 안 일어남" 제거</h3>
 * <p>{@link MarkingBatchBridge} 는 {@code @TransactionalEventListener(AFTER_COMMIT)} 라 마킹 커밋 직후
 * <b>같은 스레드에서 동기 실행</b>된다. 그런데 브리지가 배치를 트리거하지 못하는 경로(비식별 미완료·
 * 배치 단계 역전·검수 소유 작업 상태·중복 클레임)는 모두 {@code log.info} 만 남기고 끝났다. 마킹 API 는
 * 여전히 <b>201 Created</b> 를 반환하므로 사용자는 "마킹이 제출되었고 배치가 시작됐다"고 믿지만 실제로는
 * 아무 단계도 돌지 않는다. 무음 실패다.
 *
 * <p>브리지가 결과를 여기에 적고, 마킹 오케스트레이션({@code MarkingService.create})이 트랜잭션 커밋
 * 이후에 소비해 응답 DTO({@code MarkingResponse.batchTriggered/batchSkipReason})에 실어 보낸다. 즉
 * <b>상태 코드는 그대로 201</b>(마킹 자체는 정상 저장됨)이되, 배치가 시작되지 않았다는 사실과 그 사유가
 * 응답에 드러난다.
 *
 * <h3>스레드 안전</h3>
 * <p>AFTER_COMMIT 리스너는 커밋을 수행한 스레드에서 실행되므로 요청 스레드와 동일하다. 값은
 * {@link #consume()} 에서 반드시 제거하며, 오케스트레이션이 {@link #begin()} 으로 진입 시 잔여값을
 * 비워 스레드 풀 재사용에 따른 오염을 막는다.
 *
 * <p>보안: 사유 문구는 <b>고정 상수</b>만 사용한다(DB/외부 유래 문자열·경로·PII 미포함 — CWE-209/117).
 */
public final class MarkingBatchTriggerReport {

    /** 마킹 저장은 성공했으나 배치가 시작되지 않은 사유(사용자 노출 문구 — 고정 상수만). */
    public static final String REASON_VIDEO_NOT_FOUND = "영상 정보를 찾을 수 없어 배치를 시작하지 못했습니다.";
    public static final String REASON_STAGE_ALREADY_RUN = "이미 배치가 진행 중이거나 완료된 영상이라 배치를 다시 시작하지 않았습니다.";
    public static final String REASON_NOT_DEIDENTIFIED = "비식별이 완료되지 않아 배치를 시작하지 못했습니다.";
    public static final String REASON_ALREADY_CLAIMED = "검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다.";

    /** 트리거 결과. {@code triggered=true} 면 {@code reason} 은 null. */
    public record Outcome(boolean triggered, String reason) {
    }

    private static final ThreadLocal<Outcome> HOLDER = new ThreadLocal<>();

    private MarkingBatchTriggerReport() {
    }

    /** 요청 시작 시 잔여값 제거(스레드 풀 재사용 오염 방지). */
    public static void begin() {
        HOLDER.remove();
    }

    /** 배치 트리거 성공 기록. */
    public static void triggered() {
        HOLDER.set(new Outcome(true, null));
    }

    /** 배치 미트리거 기록 — 사유는 본 클래스의 고정 상수만 전달한다. */
    public static void skipped(String reason) {
        HOLDER.set(new Outcome(false, reason));
    }

    /**
     * 결과를 읽고 즉시 제거한다.
     *
     * @return 브리지가 실행되지 않았으면 {@code null}(= 판정 불가 — 응답 필드도 null 로 둔다)
     */
    public static Outcome consume() {
        Outcome outcome = HOLDER.get();
        HOLDER.remove();
        return outcome;
    }
}
