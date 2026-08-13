package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.util.Optional;

/**
 * 수동 재기동·재수행이 <b>선점 직전에 어떤 상태였는가</b> — 회수 복구 목표값의 단일 원천.
 *
 * <h3>★왜 이 구분이 데이터 파괴를 가르는가 (Critical)</h3>
 * <p>선점 출발 상태는 둘이고, 회수를 <b>무조건 {@code FAILED} 로</b> 되돌리면 완주 영상이 실패로
 * 뒤집힌다. 그러면 전체 재기동({@code BatchReprocessService} — 실패 영상 전용) 경로가 열리고, 그
 * 경로는 파이프라인을 통째로 순회하므로 {@code TrackInterpolationStep} 이 사람이 손댄
 * {@code lblSrcCd='INTERPOLATE'} 라벨을 <b>전량 삭제한 뒤 재생성</b>한다. 삭제 이력도 승인 스냅샷도
 * 없어 복구 지점이 0 인 파괴다.
 *
 * <table>
 *   <caption>선점 출발 상태별 복구 목표</caption>
 *   <tr><th>출발</th><th>요청</th><th>배치 단계 복구</th><th>작업 상태 복구</th></tr>
 *   <tr><td>{@code FAILED}</td><td>전체 재기동(실패 영상 전용)</td>
 *       <td>{@code FAILED}</td><td>{@code FAILED}</td></tr>
 *   <tr><td>{@code COMPLETED}</td><td>작업 묶음 재수행(완주·라벨링된 영상)</td>
 *       <td>{@code COMPLETED}</td><td>{@code ASSIGNED}</td></tr>
 * </table>
 *
 * <h3>작업 상태 복구값이 배치 단계와 같지 않은 이유</h3>
 * <p>완주 영상의 작업 상태는 원래 {@code ASSIGNED} 다({@code BatchTransitionService.markRawDataCompleted}
 * 성공 경로와 같은 값) — 재수행이 성공했든 실패했든 사람이 이어서 라벨링·검수할 수 있어야 한다.
 * {@code COMPLETED} 는 <b>검수 승인</b> 시점의 종결 상태라 배치가 점프시키면 검수 제출이 상태 머신에서
 * 막힌다. 실패 축은 두 컬럼이 함께 {@code FAILED} 인 것이 정상 형상이다.
 *
 * <h3>저장·해석</h3>
 * <p>선점 시점에 {@code LS_BATCH_PROC_LOG} 표식 행의 {@code ERR_MSG_CN} 에 {@link #name()} 이 그대로
 * 적재되고({@link ReprocessClaimMarker}), 회수 스윕이 {@link #parse(String)} 로 되읽는다. <b>모르는
 * 값·빈 값은 {@code Optional.empty()}</b> 이며 호출자는 회수를 포기해야 한다(fail-closed) — 추측해서
 * 되돌리는 것보다 고착을 남기는 편이 안전하다.
 */
public enum ReprocessClaimOrigin {

    /** 전체 재기동(실패 영상 전용)이 선점한 경우. */
    FAILED(LsDataRaw.DATA_STTS_FAILED, LsRawDataStatus.STTS_FAILED),

    /** 작업 묶음 지목 재수행(완주 영상)이 선점한 경우. */
    COMPLETED(LsDataRaw.DATA_STTS_COMPLETED, LsRawDataStatus.STTS_ASSIGNED);

    private final String stageStatus;
    private final String workStatus;

    ReprocessClaimOrigin(String stageStatus, String workStatus) {
        this.stageStatus = stageStatus;
        this.workStatus = workStatus;
    }

    /** 복구할 배치 단계 상태({@code LS_DATA_RAW.DATA_STTS_CD}). */
    public String stageStatus() {
        return stageStatus;
    }

    /** 복구할 작업(검수 워크플로우) 상태({@code LS_RAW_DATA_STATUS.DATA_STTS_CD}). */
    public String workStatus() {
        return workStatus;
    }

    /**
     * 선점 직전 <b>배치 단계 상태 코드</b>로부터 출발 축을 고른다 — 선점 서비스가 표식을 남길 때 쓴다.
     *
     * <p>두 서비스가 이미 {@code LsDataRaw.DATA_STTS_FAILED}/{@code DATA_STTS_COMPLETED} 를 보상
     * 롤백 목표값으로 들고 있으므로, 그 값을 그대로 받아 축으로 환산한다(호출부가 enum 상수를 따로
     * 고르게 하면 두 곳이 갈릴 수 있다).
     */
    public static Optional<ReprocessClaimOrigin> fromStageStatus(String stageStatus) {
        if (LsDataRaw.DATA_STTS_FAILED.equals(stageStatus)) {
            return Optional.of(FAILED);
        }
        if (LsDataRaw.DATA_STTS_COMPLETED.equals(stageStatus)) {
            return Optional.of(COMPLETED);
        }
        return Optional.empty();
    }

    /**
     * 표식 행에 적재된 문자열을 되읽는다 — <b>모르는 값이면 비어 있다</b>(fail-closed).
     *
     * <p>{@code valueOf} 를 직접 쓰면 표식이 손상됐을 때 {@link IllegalArgumentException} 이 스윕
     * 스레드로 올라간다. 회수는 "알 수 없으면 하지 않는다" 가 계약이므로 예외가 아니라 빈 값이다.
     */
    public static Optional<ReprocessClaimOrigin> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        for (ReprocessClaimOrigin origin : values()) {
            if (origin.name().equals(stored)) {
                return Optional.of(origin);
            }
        }
        return Optional.empty();
    }
}
