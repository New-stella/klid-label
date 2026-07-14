package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.TrackMergeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Phase 4 — 트랙 병합 서비스.
 *
 * <p>{@code fromTrackId} 의 원 키프레임(보간 산출물 제외)을 {@code toTrackId} 로 재지정한 뒤 영상
 * 전체를 재보간하여 트랙 두 개를 하나로 합친다.
 *
 * <h2>방어(HIGH 시나리오)</h2>
 * <ol>
 *   <li><b>IDOR(CWE-639)</b>: 진입부 {@link LabelAccessGuard#verifyRawAccess} 최우선 — 미인증 401,
 *       타인 배정 403.</li>
 *   <li><b>self/존재X</b>: from==to → 400, fromTrack 라벨 0건 → 404.</li>
 *   <li><b>동시성 락(CWE-362)</b>: {@link WorkLockService#lockRawExclusiveInNewTx} 로 rawSn 배타 락을
 *       <b>REQUIRES_NEW 즉시 커밋</b>해 가시화 → 병합 tx 진행 중에도 동시 병합·병합 중 {@code bulkUpsert}
 *       가 LOCKED 를 관측해 상호 차단(409). try-finally + {@link WorkLockService#releaseRawInNewTx}(REQUIRES_NEW)
 *       로 병합 롤백 시에도 락 해제 보장(락 누수 방지).</li>
 *   <li><b>겹침 frame 충돌</b>: from·to 원 키프레임이 같은 frame(srcSn)에 공존하면 보간기 "뒤 값 우선"
 *       침묵 덮어쓰기 위험 → 병합 전 교집합 계산해 겹치면 409(겹침 frame 목록 메시지).</li>
 *   <li><b>보간 산출물 제외</b>: fromTrack 라벨 중 INTERPOLATE 산출물은 재지정 대상에서 제외(원 키프레임만
 *       이관). 기존 보간 row 는 재보간 idempotency 정리 단계가 삭제한다.</li>
 *   <li><b>원자성</b>: trackId UPDATE + 재보간을 <b>같은 트랜잭션</b>으로 실행({@link TrackInterpolationStep#interpolate}
 *       는 caller tx 참여) → 재보간 실패 시 병합까지 롤백. 통지(TASK_MODIFIED) 실패는 병합을 롤백하지
 *       않는다(AFTER_COMMIT + try-catch, dead-letter 재사용).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class TrackMergeService {

    /** APPROVED 병합 시 개별 프레임 통지 상한(폭주 방지 — CLAUDE.md 통지 본문 미포함·요약 원칙). */
    private static final int MAX_NOTIFY_FRAMES = 50;

    private final LsDataLblRepository labelRepository;
    private final LabelAccessGuard accessGuard;
    private final WorkLockService workLockService;
    private final TrackInterpolationStep trackInterpolationStep;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 트랙 병합 — fromTrackId 를 toTrackId 로 합친다.
     *
     * @throws CustomException 401/403(IDOR), 400(from==to), 404(fromTrack 없음), 409(락 충돌/겹침 frame)
     */
    @Transactional("controlTransactionManager")
    public TrackMergeResponse merge(Long rawSn, String fromTrackId, String toTrackId, TokenClaims actor) {
        // [1] IDOR 최우선 — 미인증 401 / 타인 배정 403. 락 선점 이전에 인가부터 통과시킨다.
        accessGuard.verifyRawAccess(rawSn, actor);

        // [7] self 병합 방지 — 400.
        if (fromTrackId != null && fromTrackId.equals(toTrackId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "fromTrackId 와 toTrackId 가 동일합니다.");
        }
        Long actorNo = accessGuard.parseUserNo(actor.sub());

        // [5] 배타 락 선점 — REQUIRES_NEW 로 즉시 커밋해 LOCKED 를 가시화한다(핵심). 병합 tx 가 진행 중이어도
        // 동시 편집(bulkUpsert)/동시 병합이 isRawLocked=true 를 관측해 409 로 실제 차단된다. finally 의 해제도
        // REQUIRES_NEW 로 독립 커밋 → 재보간 실패로 병합 tx 가 롤백돼도 락은 확실히 해제된다(락 누수 방지).
        workLockService.lockRawExclusiveInNewTx(rawSn, actor.sub());
        try {
            return doMerge(rawSn, fromTrackId, toTrackId, actorNo);
        } finally {
            workLockService.releaseRawInNewTx(rawSn, actor.sub(), LsAuthWorkLock.REASON_MERGE);
        }
    }

    private TrackMergeResponse doMerge(Long rawSn, String fromTrackId, String toTrackId, Long actorNo) {
        List<LsDataLbl> fromLabels = labelRepository.findByRawSnAndTrackId(rawSn, fromTrackId);
        // [7] 존재하지 않는 fromTrack → 404 (수동/자동 무관 전 타입 조회이므로 진짜 부재만 걸린다).
        if (fromLabels.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "병합할 원본 트랙을 찾을 수 없습니다.");
        }
        List<LsDataLbl> toLabels = labelRepository.findByRawSnAndTrackId(rawSn, toTrackId);

        // [2] 보간 산출물 식별 — 재지정/겹침 계산에서 원 키프레임만 사용.
        Set<Long> interpolatedLblSns = new HashSet<>(labelRepository.findInterpolatedLblSnsByRawSn(rawSn));
        List<LsDataLbl> fromKeyframes = fromLabels.stream()
                .filter(l -> !interpolatedLblSns.contains(l.getLblSn()))
                .toList();
        List<LsDataLbl> toKeyframes = toLabels.stream()
                .filter(l -> !interpolatedLblSns.contains(l.getLblSn()))
                .toList();
        if (fromKeyframes.isEmpty()) {
            // fromTrack 이 보간 산출물만 있는 비정상 상태 — 원 키프레임 없이는 병합 의미 없음.
            throw new CustomException(ErrorCode.NOT_FOUND, "병합할 원본 트랙의 키프레임이 없습니다.");
        }

        // [1-HIGH] 겹침 frame 충돌 — from·to 원 키프레임이 같은 frame(srcSn)에 공존하면 침묵 덮어쓰기 위험 → 409.
        Set<Long> fromFrames = fromKeyframes.stream().map(LsDataLbl::getSrcSn).collect(Collectors.toSet());
        Set<Long> toFrames = toKeyframes.stream().map(LsDataLbl::getSrcSn).collect(Collectors.toSet());
        Set<Long> overlap = new TreeSet<>(fromFrames);
        overlap.retainAll(toFrames);
        if (!overlap.isEmpty()) {
            log.warn("[TrackMerge] overlap conflict rawSn={} from={} to={} frames={}",
                    rawSn, fromTrackId, toTrackId, overlap);
            throw new CustomException(ErrorCode.CONFLICT,
                    "겹치는 프레임이 있어 병합할 수 없습니다: " + overlap);
        }

        // [2] 원 키프레임만 trackId 재지정 (보간 산출물은 재보간 정리 단계가 삭제하므로 건드리지 않음).
        for (LsDataLbl l : fromKeyframes) {
            l.reassignTrack(toTrackId);
        }
        labelRepository.saveAll(fromKeyframes);

        // [3] interpolationApplied — 재지정 후 자동 BBOX/POLYGON 트랙만 재보간 대상. 수동/SEGMENT/SKELETON 이면 false.
        // (findAutoBboxWithTrackId 는 auto+BBOX/POLYGON+trackId 한정. Hibernate auto-flush 로 위 UPDATE 관측.)
        boolean interpolationApplied = labelRepository.findAutoBboxWithTrackId(rawSn).stream()
                .anyMatch(l -> toTrackId.equals(l.getTrackId()));

        // [4] 원자성 — 같은 트랜잭션에서 재보간. 실패 시 예외 전파 → 병합(UPDATE)까지 롤백.
        // [MED 범위] 현재는 rawSn 의 <b>영상 전체 트랙</b>을 재보간한다(interpolate(rawSn)) — 병합된
        // toTrackId 만 재보간하는 최적화는 idempotency 삭제/후보 조회가 raw 단위로 설계된 공용 경로라
        // 부담이 커 보류(무리한 리팩터 금지). 락 유지 시간은 전체 재보간에 비례하며, 트랙 수가 많은
        // 영상에서 길어질 수 있다(응답 interpolatedRowCount·아래 로그로 관측). 후속: .claude-plan.md.
        int interpolatedRows = trackInterpolationStep.interpolate(rawSn);

        log.info("[TrackMerge] merged rawSn={} from={} to={} reassigned={} interpApplied={} interpRows={}",
                rawSn, fromTrackId, toTrackId, fromKeyframes.size(), interpolationApplied, interpolatedRows);

        // [MED] APPROVED 병합 시 TASK_MODIFIED 통지(요약만). 통지 실패는 병합을 롤백하지 않는다.
        notifyIfApproved(rawSn, fromFrames, actorNo);

        return new TrackMergeResponse(rawSn, fromTrackId, toTrackId,
                fromKeyframes.size(), interpolationApplied, interpolatedRows);
    }

    /**
     * 검수 완료(APPROVED) 영상의 병합 시 변경 프레임에 대해 TASK_MODIFIED 이벤트를 발행한다.
     * <p>이벤트는 AFTER_COMMIT 리스너 + dead-letter 로 비동기 전송되므로 여기서 실패해도 병합은
     * 유지된다. 방어적으로 try-catch 로 감싸 동기 리스너 예외까지 병합 롤백에서 격리한다.
     * 개별 프레임 통지는 {@value #MAX_NOTIFY_FRAMES} 로 상한(폭주 방지).
     */
    private void notifyIfApproved(Long rawSn, Set<Long> changedFrames, Long actorNo) {
        if (!isReviewApproved(rawSn)) {
            return;
        }
        try {
            int published = 0;
            for (Long srcSn : new TreeSet<>(changedFrames)) {
                if (published >= MAX_NOTIFY_FRAMES) {
                    break;
                }
                eventPublisher.publishEvent(
                        new TaskModifiedEvent(rawSn, srcSn, ChangeType.LABEL_UPDATED, actorNo));
                published++;
            }
        } catch (RuntimeException ex) {
            // 통지 실패는 병합 롤백 사유 아님 — 로깅 후 흡수(dead-letter/재등록은 통지 파이프라인 책임).
            log.warn("[TrackMerge] task-modified notify failed rawSn={} reason={}", rawSn, ex.getMessage());
        }
    }

    /** 영상(rawSn) 검수 상태가 APPROVED 인지 판정 (LabelService 와 동일 가드 패턴). */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }
}
