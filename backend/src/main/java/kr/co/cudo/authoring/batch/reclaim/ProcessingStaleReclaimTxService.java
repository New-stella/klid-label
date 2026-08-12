package kr.co.cudo.authoring.batch.reclaim;

import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.status.LsBatchProcLog;
import kr.co.cudo.authoring.batch.status.ReprocessClaimOrigin;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「처리 중」 고착 회수의 <b>후보 선별 · 판정 · 되돌리기</b> 트랜잭션 경계.
 *
 * <h3>왜 별도 빈인가</h3>
 * <p>스윕은 전용 데몬 스레드에서 돌아 활성 트랜잭션이 없다. 트랜잭션 경계를 세우려면 Spring AOP
 * 프록시를 거쳐야 하므로 스케줄러 빈과 분리한다(같은 빈 안에서 부르면 자기호출로
 * {@code @Transactional} 이 조용히 사라진다 — 이 리포의 실사고 이력).
 *
 * <h3>★판정 축은 "경과 시간"이 아니라 "진행이 멈췄는가"다</h3>
 * <p>「{@code PROCESSING} 인 지 N 시간」으로 판정하면 <b>실제로 돌고 있는 파이프라인</b>을 죽인다.
 * 2노드 Active-Active 라 다른 노드가 돌고 있을 수도 있다. 그래서 기준 시각을 두 개 합쳐 쓴다.
 * <ul>
 *   <li><b>진행 로그 마지막 갱신</b>({@code LS_BATCH_PROC_LOG} 진행 행의 {@code MDFCN_DT}) — 오케스트레이터가
 *       단계마다 {@code markStage} 를 호출해 갱신하므로, 살아 있는 파이프라인은 단계가 넘어갈 때마다
 *       값이 앞으로 간다.</li>
 *   <li><b>선점 표식 시각</b>({@code REG_DT}) — 큐에서 <b>대기 중</b>인 재기동은 아직 한 단계도 실행하지
 *       않아 진행 로그가 <b>직전 실행 때의 옛 시각</b>에 멈춰 있다. 진행 로그만 보면 방금 접수된 정상
 *       요청이 즉시 "멈춤" 으로 판정된다. 선점 시각이 그 구간을 덮는다.</li>
 * </ul>
 * <p>둘 중 <b>더 나중</b>이 임계보다 오래됐을 때만 고착으로 본다.
 *
 * <h3>★표식은 <b>이번 에피소드</b>의 것이어야 한다</h3>
 * <p>표식이 열려 있다는 사실만으로는 부족하다. 닫힘 행 1건이 유실되면(러너 {@code finally} 의 기록
 * 실패·프로세스 사망) 그 표식은 <b>이미 끝난 옛 에피소드</b>의 잔재로 남고, 이후 아무 {@code PROCESSING}
 * 에피소드에나 옛 출발 축이 적용된다. 그래서 표식이 열린 <b>이후에 종결 기록이 있으면</b> 회수하지 않는다
 * ({@link BatchStatusService#progressTerminatedAfter} — 근거는 그 메서드 Javadoc).
 *
 * <h3>★알 수 없으면 회수하지 않는다 (fail-closed)</h3>
 * <p>선점 표식이 없거나 값이 해석되지 않거나 잔재로 보이면 <b>회수하지 않고 보류</b>한다. 추측해서
 * 되돌리는 것보다 고착을 남기는 편이 안전하다 — 완주 영상을 {@code FAILED} 로 되돌리면 전체 재기동
 * 경로가 열려 사람이 손댄 보간 라벨이 전량 삭제·재생성된다({@link ReprocessClaimOrigin} 참조).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingStaleReclaimTxService {

    /** 회수 감사 표식의 사유 문구 접두 — 고정 상수(사용자 입력·경로·PII 비포함, CWE-117/532). */
    public static final String RECLAIM_DETAIL_PREFIX = "선점 후 진행 없음으로 회수 — 복구 상태 ";

    private final VideoRepository videoRepository;
    private final BatchStatusService batchStatusService;
    private final BatchTransitionService transitionService;

    /**
     * 1차 후보 — 배치 단계가 {@code PROCESSING} 인 채 {@code cutoff} 이전에 마지막으로 갱신된 영상 중
     * {@code afterRawSn} 보다 뒤에 있는 것.
     *
     * <p>여기 걸린 것이 곧 고착은 아니다(정상 실행 중일 수 있다). 최종 판정은 {@link #decide}.
     * {@code afterRawSn} 회전 커서의 근거는
     * {@link VideoRepository#findStaleProcessingRawSns} Javadoc 참조.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public List<Long> findStaleCandidates(LocalDateTime cutoff, long afterRawSn, int limit) {
        if (cutoff == null || limit < 1 || afterRawSn < 0) {
            return List.of();
        }
        return videoRepository.findStaleProcessingRawSns(
                LsDataRaw.DATA_STTS_PROCESSING, cutoff, afterRawSn, PageRequest.of(0, limit));
    }

    /**
     * 이 영상이 <b>회수해도 되는 고착</b>인지 판정한다.
     *
     * <p>세 갈래로 돌려준다({@link ReclaimDecision}) — 「회수한다」/「아직 살아 있다」/「판정 불가로
     * 보류한다」. 보류 사유의 <b>로그 수위와 집계는 스윕이 정한다</b>(건별 WARN 을 여기서 찍으면 tick 마다
     * 같은 후보가 반복 경고를 남겨 진짜 회수 경고가 묻힌다).
     *
     * @param cutoff 이 시각 이전이어야 "진행이 멈췄다" 로 본다
     */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public ReclaimDecision decide(Long rawSn, LocalDateTime cutoff) {
        if (rawSn == null || cutoff == null) {
            return ReclaimDecision.alive();
        }
        LsBatchProcLog marker = batchStatusService.openReprocessClaimMarker(rawSn).orElse(null);
        if (marker == null) {
            // 선점 직전 상태를 기록하지 않는 경로(마킹 브리지·자동 재시도 잡·dev 트리거)로 진입했거나,
            // 이미 닫힌 선점이다. 되돌릴 목표를 알 수 없으므로 손대지 않는다.
            return ReclaimDecision.withheld(ReclaimDecision.WithholdReason.NO_CLAIM_MARKER);
        }
        ReprocessClaimOrigin origin = ReprocessClaimOrigin.parse(marker.getErrMsg()).orElse(null);
        if (origin == null) {
            // 표식은 열려 있는데 값이 해석되지 않는다(손상·미지 값). 추측 복구 금지.
            return ReclaimDecision.withheld(ReclaimDecision.WithholdReason.UNREADABLE_ORIGIN);
        }
        LocalDateTime claimedAt = marker.getRegDt();
        LocalDateTime lastActivity = latestActivity(rawSn, claimedAt);
        if (lastActivity == null || lastActivity.isAfter(cutoff)) {
            // 아직 진행 중이거나 방금 선점됐다 — 살아 있는 파이프라인을 뺏지 않는다.
            return ReclaimDecision.alive();
        }
        // ★ 에피소드 결속 — 표식이 열린 뒤 배치가 종결까지 갔다면 이 표식은 옛 에피소드의 잔재다.
        //   그 출발 축으로 되돌리면 완주 영상이 FAILED 로 강등될 수 있으므로 회수하지 않는다.
        if (claimedAt != null && batchStatusService.progressTerminatedAfter(rawSn, claimedAt)) {
            return ReclaimDecision.withheld(ReclaimDecision.WithholdReason.STALE_EPISODE);
        }
        return ReclaimDecision.reclaim(origin);
    }

    /**
     * <b>되돌리기와 표식 닫기를 한 트랜잭션으로</b> 수행한다 — 회수의 유일한 쓰기 진입점.
     *
     * <h3>왜 한 트랜잭션이어야 하나</h3>
     * <p>둘을 따로 커밋하면 사이에서 죽거나 뒤가 던질 때 <b>상태는 되돌아갔는데 표식이 열린 채</b> 남는다.
     * 그 표식은 나중에 <b>다른 진입 경로</b>(마킹 브리지·자동 재시도 잡·dev 트리거)로 고착된 같은 영상을
     * 옛 출발 상태로 되돌리게 만들고, 「전체 재기동(FAILED) → 완주 → 다른 경로로 고착」 형상에서는
     * <b>완주 영상이 {@code FAILED} 로 강등</b>돼 전체 재기동 경로가 열린다 — 그 경로가 사람이 손댄
     * 보간 라벨을 전량 삭제·재생성한다(복구 지점 0).
     *
     * <p><b>원자 클레임 성질은 유지된다</b>(CWE-362) — 조건부 UPDATE 가 0행이면(다른 노드가 이미
     * 회수했거나 파이프라인이 스스로 마감했다) 표식도 닫지 않고 그대로 빠져나간다. 두 협력자는 모두
     * {@code REQUIRED} 라 이 경계에 참여한다(자기호출이 아니라 프록시 경유이므로 경계가 유실되지 않는다).
     *
     * @return {@code true} = 이번 호출이 회수에 성공했다
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean reclaimAndClose(Long rawSn, ReprocessClaimOrigin origin) {
        if (rawSn == null || origin == null) {
            return false;
        }
        if (!transitionService.reclaimStuckProcessing(
                rawSn, origin.stageStatus(), origin.workStatus())) {
            return false;
        }
        // 승자만 감사 표식을 남긴다. 이 행이 선점 표식을 <b>닫는</b> 역할도 겸한다.
        batchStatusService.recordReprocessClaimReclaimed(
                rawSn, RECLAIM_DETAIL_PREFIX + origin.stageStatus());
        return true;
    }

    /**
     * "마지막으로 무언가 움직인 시각" — 선점 시각과 진행 로그 갱신 시각 중 <b>더 나중</b>.
     *
     * <p>둘 중 하나만 보면 안 되는 이유는 클래스 Javadoc 참조. {@code null} 은 판정 불가로 취급해
     * 호출자가 회수를 포기한다(fail-closed).
     */
    private LocalDateTime latestActivity(Long rawSn, LocalDateTime claimedAt) {
        LocalDateTime progressAt = batchStatusService.latestProgressUpdatedAt(rawSn).orElse(null);
        if (claimedAt == null) {
            return progressAt;
        }
        if (progressAt == null) {
            return claimedAt;
        }
        return progressAt.isAfter(claimedAt) ? progressAt : claimedAt;
    }
}
