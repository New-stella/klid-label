package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep.TouchedFrames;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.TrackDeleteResponse;
import kr.co.cudo.authoring.label.dto.TrackSplitResponse;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Phase 3(트랙 관리 확장) — 트랙 삭제(R4) / 트랙 split(R5) 서비스.
 *
 * <p>{@code TrackMergeService} 와 동형 패턴: 진입부 IDOR({@link LabelAccessGuard#verifyRawAccess}) →
 * {@link WorkLockService#lockRawExclusiveInNewTx} 배타 락(REQUIRES_NEW 즉시 커밋으로 동시 편집 차단) →
 * 라벨 조작 + {@link TrackInterpolationStep#interpolateSingleTrack} 재보간(같은 tx = 원자성) →
 * APPROVED 영상이면 {@link TaskModifiedEvent} 통지(요약만, 실패 격리). try-finally 로
 * {@link WorkLockService#releaseRawInNewTx} 를 보장(락 누수 방지).
 *
 * <h2>방어(HIGH 시나리오)</h2>
 * <ol>
 *   <li><b>IDOR(CWE-639)</b>: 진입부 verifyRawAccess — 미인증 401, 타인 배정 403.</li>
 *   <li><b>동시성 락(CWE-362)</b>: rawSn 배타 락으로 동시 편집/삭제/분할 상호 차단(409).</li>
 *   <li><b>FK 고아 방지</b>: 삭제는 자식(LS_DATA_LBL_AI_INFO) → 부모(LS_DATA_LBL) 순서.</li>
 *   <li><b>보간 정합</b>: 삭제/분할 후 영향 트랙을 재보간(interpolateSingleTrack)해 stale 보간 산출물
 *       (경계 넘어 남는 유령 라벨)을 제거·재생성. 같은 tx 라 실패 시 삭제/분할까지 롤백(원자성).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class TrackEditService {

    /** APPROVED 편집 시 개별 프레임 통지 상한(폭주 방지 — CLAUDE.md 통지 본문 미포함·요약 원칙). */
    private static final int MAX_NOTIFY_FRAMES = 50;

    private final LsDataLblRepository labelRepository;
    private final LsDataLblAiInfoRepository aiInfoRepository;
    private final LsDataLblAttrValRepository attrValRepository;
    private final LsDataAugLblMapRepository augLblMapRepository;
    private final LabelAccessGuard accessGuard;
    private final WorkLockService workLockService;
    private final TrackInterpolationStep trackInterpolationStep;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * R4 — 트랙 삭제. {@code fromFrameNo} 이후(포함) 프레임의 {@code trackId} 라벨을 전부 삭제한다.
     * 이전 프레임·타 트랙은 불변. 삭제 후 남은 키프레임으로 해당 트랙을 재보간해 stale 보간 산출물을 정리한다.
     *
     * @throws CustomException 401/403(IDOR), 404(트랙 부재), 409(락 충돌)
     */
    @Transactional("controlTransactionManager")
    public TrackDeleteResponse deleteTrackFrom(Long rawSn, String trackId, int fromFrameNo, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        Long actorNo = accessGuard.parseUserNo(actor.sub());

        workLockService.lockRawExclusiveInNewTx(rawSn, actor.sub());
        try {
            return doDelete(rawSn, trackId, fromFrameNo, actorNo);
        } finally {
            workLockService.releaseRawInNewTx(rawSn, actor.sub(), LsAuthWorkLock.REASON_MERGE);
        }
    }

    private TrackDeleteResponse doDelete(Long rawSn, String trackId, int fromFrameNo, Long actorNo) {
        // 트랙 존재성 — 영상 내 해당 trackId 라벨이 하나도 없으면 404(진짜 부재).
        if (labelRepository.findByRawSnAndTrackId(rawSn, trackId).isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "삭제할 트랙을 찾을 수 없습니다.");
        }

        List<LsDataLbl> targets = labelRepository.findByRawSnAndTrackIdFromFrameNo(
                rawSn, trackId, (long) fromFrameNo);
        if (targets.isEmpty()) {
            // 트랙은 존재하나 삭제 범위(fromFrameNo 이상)에 라벨 없음 — 변경 없는 정상 no-op(200, deletedCount=0).
            return new TrackDeleteResponse(rawSn, trackId, fromFrameNo, 0);
        }

        Set<Long> changedFrames = targets.stream().map(LsDataLbl::getSrcSn).collect(Collectors.toCollection(TreeSet::new));
        List<Long> lblSns = targets.stream().map(LsDataLbl::getLblSn).toList();

        // FK 고아 방지 — 자식(ATTR_VAL) → 자식(AI_INFO) → 부모(LBL) 순서. ATTR_VAL 은 실 FK
        // (FK_LS_DATA_LBL_ATTR_LBL, ON DELETE 없음)라 먼저 지우지 않으면 부모 삭제가 FK 위반 500 →
        // 속성값 붙은 트랙은 삭제 영구 불가(DeidentReportService.deleteAllVideoLabels 와 동일 순서).
        attrValRepository.deleteByLblSnIn(lblSns);
        aiInfoRepository.deleteByDataLblSnIn(lblSns);
        // FK 없는 증강 매핑 고아 정리(증강 영상 경로) — 증강 아니면 no-op.
        augLblMapRepository.deleteByLabelReferencesIn(lblSns);
        labelRepository.deleteAllByIdInBatch(lblSns);

        // 보간 정합 — 삭제로 끊긴 트랙의 stale 보간 산출물 정리 + 남은 키프레임 재보간(같은 tx = 원자성).
        // 재보간이 건드린 프레임(삭제 stale ∪ 신규)도 변경 프레임에 union(TASK_MODIFIED 계약 정합).
        TouchedFrames reInterp = trackInterpolationStep.interpolateSingleTrackTouched(rawSn, trackId, trackId);
        changedFrames.addAll(reInterp.touchedSrcSns());

        log.info("[TrackEdit] deleted rawSn={} trackId={} fromFrameNo={} deleted={} reInterp={}",
                rawSn, LogSanitizer.sanitize(trackId), fromFrameNo, targets.size(), reInterp.newRowCount());

        notifyIfApproved(rawSn, changedFrames, actorNo, ChangeType.LABEL_DELETED);
        return new TrackDeleteResponse(rawSn, trackId, fromFrameNo, targets.size());
    }

    /**
     * R5 — 트랙 split(분할). {@code atFrameNo} 이후(포함) 프레임의 {@code trackId} 키프레임을 새 트랙 ID
     * (영상 내 max 정수 트랙ID + 1)로 재지정한다. 좌표는 불변. 분할 후 두 트랙을 각각 재보간한다.
     *
     * @throws CustomException 401/403(IDOR), 404(트랙 부재), 409(락 충돌)
     */
    @Transactional("controlTransactionManager")
    public TrackSplitResponse splitTrack(Long rawSn, String trackId, int atFrameNo, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        Long actorNo = accessGuard.parseUserNo(actor.sub());

        workLockService.lockRawExclusiveInNewTx(rawSn, actor.sub());
        try {
            return doSplit(rawSn, trackId, atFrameNo, actorNo);
        } finally {
            workLockService.releaseRawInNewTx(rawSn, actor.sub(), LsAuthWorkLock.REASON_MERGE);
        }
    }

    private TrackSplitResponse doSplit(Long rawSn, String trackId, int atFrameNo, Long actorNo) {
        if (labelRepository.findByRawSnAndTrackId(rawSn, trackId).isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "분할할 트랙을 찾을 수 없습니다.");
        }

        // 보간 산출물은 이동 대상에서 제외 — 원 키프레임만 이관하고, 보간 row 는 재보간 정리 단계가 삭제/재생성한다.
        Set<Long> interpolatedLblSns = Set.copyOf(labelRepository.findInterpolatedLblSnsByRawSn(rawSn));
        List<LsDataLbl> movable = labelRepository.findByRawSnAndTrackIdFromFrameNo(rawSn, trackId, (long) atFrameNo)
                .stream()
                .filter(l -> !interpolatedLblSns.contains(l.getLblSn()))
                .toList();

        String newTrackId = nextTrackId(rawSn);
        if (movable.isEmpty()) {
            // 경계 밖(atFrameNo 이상 키프레임 없음) — 변경 없는 정상 no-op(200, movedCount=0).
            return new TrackSplitResponse(rawSn, trackId, newTrackId, atFrameNo, 0);
        }

        Set<Long> changedFrames = movable.stream().map(LsDataLbl::getSrcSn).collect(Collectors.toCollection(TreeSet::new));
        for (LsDataLbl l : movable) {
            l.reassignTrack(newTrackId);
        }
        labelRepository.saveAll(movable);

        // 보간 정합 — 두 트랙 모두 재보간. 1차: stale 정리(from+to 양쪽) + newTrackId 재보간,
        // 2차: 원 trackId 재보간(1차에서 stale 이미 제거됨). 같은 tx 라 실패 시 split 까지 롤백(원자성).
        // 재보간이 건드린 프레임도 변경 프레임에 union(TASK_MODIFIED 계약 정합).
        TouchedFrames interpNew = trackInterpolationStep.interpolateSingleTrackTouched(rawSn, newTrackId, trackId);
        TouchedFrames interpOrig = trackInterpolationStep.interpolateSingleTrackTouched(rawSn, trackId, trackId);
        changedFrames.addAll(interpNew.touchedSrcSns());
        changedFrames.addAll(interpOrig.touchedSrcSns());

        log.info("[TrackEdit] split rawSn={} trackId={} -> newTrackId={} atFrameNo={} moved={} reInterp(new={},orig={})",
                rawSn, LogSanitizer.sanitize(trackId), newTrackId, atFrameNo, movable.size(),
                interpNew.newRowCount(), interpOrig.newRowCount());

        notifyIfApproved(rawSn, changedFrames, actorNo, ChangeType.LABEL_UPDATED);
        return new TrackSplitResponse(rawSn, trackId, newTrackId, atFrameNo, movable.size());
    }

    /**
     * 영상 내 유니크한 새 트랙 ID 채번 — 사용 중 트랙 ID 중 정수 파싱 가능한 값의 최댓값 + 1(문자열).
     * 정수 트랙이 없으면 "1". 비정수 트랙 ID 는 무시(트랙 ID 는 문자열 직렬화 정수 규약).
     */
    private String nextTrackId(Long rawSn) {
        long max = 0L;
        for (String id : labelRepository.findDistinctTrackIdsByRawSn(rawSn)) {
            try {
                max = Math.max(max, Long.parseLong(id.trim()));
            } catch (NumberFormatException ignore) {
                // 비정수 트랙 ID — 채번 대상 아님.
            }
        }
        return String.valueOf(max + 1);
    }

    /**
     * 검수 완료(APPROVED) 영상의 편집 시 변경 프레임에 대해 TASK_MODIFIED 이벤트를 발행한다.
     * <p>AFTER_COMMIT + dead-letter 비동기이며, 통지 실패는 편집을 롤백하지 않는다(try-catch 격리).
     * 개별 프레임 통지는 {@value #MAX_NOTIFY_FRAMES} 로 상한(폭주 방지).
     */
    private void notifyIfApproved(Long rawSn, Set<Long> changedFrames, Long actorNo, String changeType) {
        if (!isReviewApproved(rawSn)) {
            return;
        }
        try {
            int published = 0;
            for (Long srcSn : changedFrames) {
                if (published >= MAX_NOTIFY_FRAMES) {
                    break;
                }
                eventPublisher.publishEvent(new TaskModifiedEvent(rawSn, srcSn, changeType, actorNo));
                published++;
            }
        } catch (RuntimeException ex) {
            log.warn("[TrackEdit] task-modified notify failed rawSn={} reason={}", rawSn, ex.getMessage());
        }
    }

    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }
}
