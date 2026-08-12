package kr.co.cudo.authoring.batch.status;

import java.util.List;

/**
 * 수동 재기동·재수행 <b>선점 표식</b>의 상수 단일 원천 — 고착 회수의 유일한 판정 근거.
 *
 * <h3>왜 필요한가 (이게 없으면 회수 자체가 불가능하다)</h3>
 * <p>재기동·재수행은 요청 안에서 배치 단계를 {@code PROCESSING} 으로 <b>선점</b>하고 실행은 전용 풀
 * ({@code batchReprocessExecutor} — core 2 / 큐 4)로 넘긴다. 그 <b>대기 중에 노드가 재시작·재배포되면
 * 큐가 통째로 사라지는데 선점 표시는 DB 에 남는다</b>. 그 영상은 이후 모든 재기동이 409 로 막히고
 * 애플리케이션 안에 복구 수단이 0 이 된다(DB 직접 수정만).
 *
 * <p>그런데 회수하려면 <b>선점 직전 상태</b>를 알아야 한다({@link ReprocessClaimOrigin} 참조 —
 * 완주 영상을 {@code FAILED} 로 되돌리면 전체 재기동 경로가 열려 사람이 손댄 보간 라벨이 파괴된다).
 * 그 정보는 지금까지 <b>호출 스레드의 인자</b>({@code AsyncBatchReprocessRunner#runAsync} 의
 * {@code claimOriginStatus})로만 존재했고 <b>노드가 죽으면 함께 사라졌다</b>. 이 표식이 그 값을 DB 에
 * 남기는 유일한 통로다.
 *
 * <h3>★저장 축 — 신규 컬럼·테이블 없이 기존 {@code LS_BATCH_PROC_LOG} 감사 축을 재사용한다</h3>
 * <p>{@code ManualStageSkip}(수동 스킵 표식)이 이미 확립한 관례를 그대로 따른다.
 * <ul>
 *   <li>{@code PROC_STTS_CD='SKIPPED'} — 진행 조회
 *       ({@code findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc})가 이 값을 제외하므로 화면 단계
 *       표시({@code BatchStageProgressMapper})가 표식에 오염되지 않는다.</li>
 *   <li>{@code PROC_STEP_CD}={@link #PROC_STEP_CD} — 개별 단계도 묶음도 아닌 <b>전용 네임스페이스</b>다.
 *       {@code BatchStatusService.isStageSkippedWithReason}/{@code ...WithAnyReason} 는 모두
 *       {@code PROC_STEP_CD} 를 함께 조건에 거므로({@code 'VLM'} 등) 이 행이 VLM 재개 판정에 걸릴 수
 *       없고, {@code ManualStageSkip.MARKER_ERR_CDS} 로 거르는 수동 스킵 조회에도 걸리지 않는다.</li>
 *   <li>{@code ERR_MSG_CN} — {@link ReprocessClaimOrigin#name()} 원문. 자유 서술 컬럼이라 스키마 추가가
 *       필요 없다({@code createSkipped} 가 사유를 담는 방식과 동일).</li>
 *   <li>{@code REG_ID} — 시스템 기록 주체 고정값. 사용자 토큰 subject 를 옮겨 담지 않는다(PII·CWE-117
 *       노출면을 늘리지 않으며, 누가 눌렀는지는 요청 로그·인가 계층이 이미 남긴다).</li>
 * </ul>
 *
 * <h3>★열림/닫힘 — "지금 선점을 들고 있는가" 는 마지막 표식 행이 정한다</h3>
 * <p>표식은 <b>append-only</b> 로 쌓이고, 판정은 "(영상) 의 마지막 표식 행이 {@link #ERR_CD_OPEN} 인가"
 * 하나다({@code ManualStageSkip} 의 스킵/해제 판정과 같은 골격).
 * <ul>
 *   <li>{@link #ERR_CD_OPEN} — 선점 직후 기록. 이 뒤로 닫힘 행이 없으면 그 선점이 아직 살아 있다.</li>
 *   <li>{@link #ERR_CD_CLOSED} — 실행이 <b>어떤 결과로든 끝났거나</b> 접수 자체가 거부(디스패치 거부)돼
 *       선점을 되돌렸을 때 기록.</li>
 *   <li>{@link #ERR_CD_RECLAIMED} — 회수 스윕이 고착을 되돌렸을 때 기록(감사).</li>
 * </ul>
 *
 * <p><b>닫힘 행이 왜 반드시 필요한가</b>: 표식이 열린 채 남으면, 나중에 <b>다른 경로</b>(마킹 브리지·
 * 자동 재시도 잡·dev 트리거 — 진입 가드 {@code claimForProcessing} 이 선점하는 경로들)로 고착된 같은
 * 영상을 스윕이 <b>옛 표식의 출발 상태로</b> 되돌린다. 예컨대 「전체 재기동(FAILED) 성공 → 완주 →
 * 이후 다른 경로로 고착」 이면 완주한 영상이 {@code FAILED} 로 강등돼 이 설계가 막으려던 파괴가
 * 그대로 열린다. 닫힘 행이 있으면 그런 영상은 "마지막 표식이 열림이 아님" 으로 걸러져 회수 대상에서
 * 빠진다(=회수 못 함 — 의도된 fail-closed다. 그 경로들은 선점 직전 상태를 애초에 기록하지 않는다).
 */
public final class ReprocessClaimMarker {

    /**
     * 표식 행의 {@code PROC_STEP_CD} — 전용 네임스페이스. 컬럼 폭 {@code VARCHAR(30)} 이내.
     *
     * <p>{@code BatchStage}·{@code BatchStageBundle} 어느 상수와도 겹치지 않아야 한다. 겹치면 그 단계의
     * 재개·스킵 판정 쿼리가 이 행을 함께 집는다.
     */
    public static final String PROC_STEP_CD = "REPROCESS_CLAIM";

    /** 선점 표식({@code ERR_CD}). 컬럼 폭 {@code VARCHAR(50)} 이내. */
    public static final String ERR_CD_OPEN = "REPROCESS_CLAIM_OPEN";

    /** 선점 종료 표식 — 정상 종료 또는 접수 거부 보상. */
    public static final String ERR_CD_CLOSED = "REPROCESS_CLAIM_CLOSED";

    /** 회수 스윕이 고착 선점을 되돌렸다는 표식(감사). 닫힘 축의 일종이다. */
    public static final String ERR_CD_RECLAIMED = "REPROCESS_CLAIM_RECLAIMED";

    /** 표식 행을 고르는 {@code ERR_CD} 집합 — "마지막 표식" 조회의 입력. */
    public static final List<String> MARKER_ERR_CDS =
            List.of(ERR_CD_OPEN, ERR_CD_CLOSED, ERR_CD_RECLAIMED);

    /** 표식 행의 {@code REG_ID} 고정값 — 시스템 기록 주체. 컬럼 폭 {@code VARCHAR(30)} 이내. */
    public static final String REG_ID = "batch-reprocess-claim";

    /** 회수 스윕이 남기는 표식 행의 {@code REG_ID} 고정값. */
    public static final String RECLAIM_REG_ID = "batch-stuck-reclaim";

    private ReprocessClaimMarker() {
    }
}
