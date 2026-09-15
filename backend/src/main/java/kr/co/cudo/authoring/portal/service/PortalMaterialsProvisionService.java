package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.dto.PortalMaterialsState;
import kr.co.cudo.authoring.portal.dto.PortalMaterialsStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * 조달의 <b>착수와 상태</b>를 소유한다 — 실제 수행은 {@link PortalMaterialsProvisionRunner} 가 한다.
 *
 * <h3>★ 착수는 접수까지다</h3>
 * <p>배포 압축본이 기가바이트급이라 요청 안에서 복사·해제를 끝내면 타임아웃이 난다. 그래서 착수는
 * <b>선점하고 일을 맡기는 데까지</b>이고, 진행은 상태 조회로 본다.
 *
 * <h3>★ 멱등 — 같은 대상은 새로 시작하지 않는다</h3>
 * <ul>
 *   <li>이미 준비 완료면 <b>다시 풀지 않는다</b>. 사용자가 그 해제본을 물고 저작하는 중일 수 있다.</li>
 *   <li>이미 진행 중이면 그 상태를 답한다.</li>
 * </ul>
 * <p>판정 순서는 <b>준비 완료 → 진행 중</b>이다. 뒤집으면 방금 끝난 건이 잠깐 「진행 중」으로 보인다.
 *
 * <h3>★ 큐가 차면 접수하지 않는다</h3>
 * <p>맡길 자리가 없으면 <b>선점을 되돌리고</b> 거부한다. 선점을 남긴 채 거부하면 그 데이터셋은
 * 아무도 일하지 않는데 「진행 중」으로 굳어 재기동 전까지 조달할 수 없다.
 *
 * @design INT-014
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalMaterialsProvisionService {

    private final PortalMaterialsWorkspace workspace;
    private final PortalMaterialsProvisionState state;
    private final PortalMaterialsProvisionRunner runner;

    /**
     * 조달을 착수한다 — 이미 준비됐거나 진행 중이면 <b>새로 시작하지 않고</b> 현재 상태를 답한다.
     *
     * @throws CustomException 데이터셋 식별자가 유효하지 않거나(400) 지금은 받을 수 없을 때(503)
     */
    public PortalMaterialsStatusResponse start(long datasetId) {
        requireValidId(datasetId);
        if (workspace.isReady(datasetId)) {
            return status(datasetId);
        }
        if (!state.claim(datasetId)) {
            return status(datasetId);
        }
        try {
            runner.runAsync(datasetId);
        } catch (TaskRejectedException e) {
            // 선점을 반드시 되돌린다 — 남기면 아무도 일하지 않는데 「진행 중」으로 굳는다.
            state.release(datasetId);
            log.warn("[PortalMaterials] 조달 작업을 접수하지 못했습니다(대기열 포화) datasetId={}", datasetId);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE,
                    "조달 작업이 밀려 있습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException e) {
            state.release(datasetId);
            throw e;
        }
        return PortalMaterialsStatusResponse.of(datasetId, PortalMaterialsState.IN_PROGRESS);
    }

    /** 현재 상태를 답한다. */
    public PortalMaterialsStatusResponse status(long datasetId) {
        requireValidId(datasetId);
        if (workspace.isReady(datasetId)) {
            return PortalMaterialsStatusResponse.ready(datasetId, workspace.readSummary(datasetId));
        }
        if (state.inProgress(datasetId)) {
            return PortalMaterialsStatusResponse.of(datasetId, PortalMaterialsState.IN_PROGRESS);
        }
        PortalMaterialsProvisionState.Failure failure = state.lastFailure(datasetId);
        if (failure != null) {
            return PortalMaterialsStatusResponse.failed(datasetId, failure.reason());
        }
        return PortalMaterialsStatusResponse.of(datasetId, PortalMaterialsState.NOT_PROVISIONED);
    }

    /**
     * 데이터셋 식별자 검증 — <b>양수만</b>.
     *
     * <p>이 값은 경로 세그먼트가 되므로 음수·0 을 그대로 두면 {@code -1} 같은 디렉터리 이름이 생긴다.
     * 포털도 숫자가 아니면 없음으로 답하므로 여기서 먼저 막는 편이 왕복 한 번을 아낀다.
     */
    private static void requireValidId(long datasetId) {
        if (datasetId <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "데이터셋 식별자가 유효하지 않습니다.");
        }
    }
}
