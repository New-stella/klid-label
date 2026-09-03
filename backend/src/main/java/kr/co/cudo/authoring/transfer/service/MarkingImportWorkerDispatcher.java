package kr.co.cudo.authoring.transfer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/**
 * 일꾼을 띄운다 — <b>몇 명을 띄우는가가 곧 동시 처리 수 상한</b>이다.
 *
 * <h3>왜 항목이 아니라 일꾼을 띄우는가</h3>
 * <p>항목마다 하나씩 던지면 백 건이 한꺼번에 실행 대기열에 눕고, 그 대기열은 <b>노드가 다시 뜨면
 * 통째로 사라진다</b>. 일꾼은 자기가 처리할 항목을 원장에서 직접 집어 가므로 대기열이 메모리가 아니라
 * <b>행</b>에 있다. 노드가 다시 떠도 남은 항목은 그대로 있고, 일꾼만 다시 띄우면 이어진다.
 *
 * <h3>거부는 지연이지 유실이 아니다</h3>
 * <p>자리가 꽉 차 거부되면 그 사실만 남긴다. 할 일은 이미 행으로 있고 되돌리기 잡이 다음 순번에
 * 그 작업을 집는다. 다만 <b>한 명도 띄우지 못한 경우</b>는 그때까지 아무 일도 일어나지 않으므로
 * 알아볼 수 있게 남긴다.
 *
 * @design DOMAIN-017
 * @design API-217
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingImportWorkerDispatcher {

    private final MarkingImportWorker worker;

    /**
     * 일꾼을 최대 {@code count} 명 띄운다.
     *
     * @return 실제로 띄운 수 — 0이면 이 순간에는 아무도 처리하지 않는다(되돌리기 잡이 뒤에 집는다)
     */
    public int dispatch(long jobSn, int count) {
        int started = 0;
        for (int i = 0; i < count; i++) {
            try {
                worker.work(jobSn);
                started++;
            } catch (RejectedExecutionException e) {
                break;
            }
        }
        if (started == 0) {
            log.warn("[MarkingImport] no worker started jobSn={} — 되돌리기 잡이 뒤에 집는다", jobSn);
        }
        return started;
    }
}
