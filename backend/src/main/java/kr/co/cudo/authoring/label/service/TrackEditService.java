package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LabelSnapshot;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ReviewApprovalGate approvalGate;
    private final ApplicationEventPublisher eventPublisher;
    /** DEV_FIX — 트랙 삭제 시 LS_DATA_LBL_HSTRY DELETED 이력 기록(삭제 감사 완결성, bulkUpsert 와 동일 레포). */
    private final LsDataLblHstryRepository lblHstryRepository;
    /**
     * C-ISSUE-21 — 트랙 편집도 프레임 라벨셋을 바꾸므로 해당 프레임들의 라벨셋 버전을 +1 한다.
     * 올리지 않으면 라벨링 화면이 보유한 버전이 유효한 채로 남아, 트랙 삭제/분할로 사라진 라벨을
     * 낡은 세트가 되살리거나(full-replace) 반대로 새 라벨을 지우는 lost update 가 다시 열린다.
     */
    private final LsDataSrcRepository srcRepository;

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
        // DEV_FIX(H4) — 프레임 락을 트랜잭션 <b>맨 앞에서 1회</b>, SRC_SN 오름차순으로 선점한다.
        //   자세한 근거는 lockFramesForRaw Javadoc 참조. 이후의 bump 들은 이미 보유한 행만 건드린다.
        lockFramesForRaw(rawSn);

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

        // DEV_FIX(삭제 감사 완결성) — 라벨 row 삭제 직전, 삭제 대상별로 LS_DATA_LBL_HSTRY DELETED 이력을
        // saveAll 1회로 원자 기록한다(같은 @Transactional — 부분 실패 시 삭제·이력 함께 롤백, CWE-362).
        // srcSn 은 라벨별 프레임(트랙이 여러 프레임에 걸치므로 각 라벨의 srcSn 을 정확히 사용).
        // regId 는 행위자 ID 수준만 저장(bulkUpsert 와 동일 String.valueOf(actorNo)) — 토큰/PII 미저장(CWE-359).
        recordDeletionHistory(targets, actorNo);

        // DEV_FIX(H2① 락 순서) — 라벨 행을 지우기 <b>전에</b> 대상 프레임의 라벨셋 버전을 +1 한다.
        //   bump 는 프레임 행 쓰기 락을 잡으므로, 삭제(=라벨 행 락) 뒤에 두면 "프레임 락 → 라벨 락" 으로
        //   도는 라벨 저장 경로와 역순이 되어 ABBA 데드락(PG 40P01)이 열린다. 규약: 프레임 락을 항상 먼저.
        //   (재보간이 추가로 건드린 프레임은 아래에서 한 번 더 bump 한다 — 버전은 단조 증가라 중복 +1 무해.)
        bumpLabelVersions(changedFrames);

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

        // C-ISSUE-21 — 재보간이 추가로 건드린 프레임의 버전 +1 (삭제 대상 프레임은 위에서 이미 올렸다).
        bumpLabelVersions(reInterp.touchedSrcSns());

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

    /**
     * C-ISSUE-21 — 변경된 프레임들의 라벨셋 버전을 단일 UPDATE 로 +1 한다(N+1 금지). 빈 집합이면 no-op.
     * 같은 트랜잭션에서 수행되어 라벨 변경과 함께 커밋/롤백된다.
     */
    private void bumpLabelVersions(Set<Long> changedFrames) {
        if (changedFrames == null || changedFrames.isEmpty()) {
            return;
        }
        srcRepository.bumpLabelVersionIn(changedFrames);
    }

    /**
     * DEV_FIX(H4) — 트랜잭션당 <b>단 한 번</b>의 프레임 락 획득 지점.
     *
     * <h3>고친 결함</h3>
     * <p>트랙 편집 트랜잭션은 프레임 락을 2회 이상 잡았다: ① 변경 대상 프레임 bump ② 재보간이 건드린
     * 프레임 bump(+ {@code interpolateSingleTrackTouched} 내부의 stale bump). 두 집합은 서로 다르므로,
     * 같은 영상에 다른 편집/배치 보간이 동시에 들어오면
     * ({@code T1: {5,9} → {3,5}}, {@code T2: {3} → {5,7}}) 교차 대기해 PostgreSQL 40P01(deadlock
     * detected) → 500 이 발생할 수 있었다. "프레임 락 → 라벨 락" 규약은 <b>한 문장 내</b> 순서만 보장할 뿐
     * 문장 <b>사이</b>의 교차는 막지 못한다.
     *
     * <h3>왜 이 형태인가</h3>
     * <ul>
     *   <li><b>선점 집합은 영상 전 프레임</b> — 이후 bump 집합(변경 프레임 ∪ stale 보간 프레임 ∪ 신규
     *       보간 프레임)의 상위집합이어야 하는데, 신규 보간이 어느 프레임에 생길지는 보간을 돌려 봐야
     *       알 수 있다. 상위집합을 잡아야 "이후 bump 가 새 락을 전혀 얻지 않는다"가 성립한다.</li>
     *   <li><b>버전(LBL_VER)은 올리지 않는다</b> — 락만 선점한다. 실제 변경 프레임만 +1 하는 기존
     *       범위(H11 수정)를 그대로 보존해, 손대지 않은 프레임을 편집 중인 작업자를 409 로 밀어내는
     *       과잉 무효화를 되살리지 않는다.</li>
     *   <li><b>{@code FOR NO KEY UPDATE} + {@code ORDER BY SRC_SN}</b> — bump 가 실제로 잡는 락 모드와
     *       동일하여 기존 동시성 동작이 변하지 않고, 획득 순서가 결정적이다.</li>
     * </ul>
     * 이 선점은 아무 락도 쥐지 않은 상태에서 대기하므로 순환 대기의 구성원이 될 수 없다.
     * 트랙 편집은 이미 {@link WorkLockService#lockRawExclusiveInNewTx} 로 영상 배타 락을 쥐고 있어
     * 편집끼리는 상호 배제되며, 이 선점은 배타 락을 타지 않는 배치 보간과의 교차까지 막는다.
     */
    private void lockFramesForRaw(Long rawSn) {
        srcRepository.lockFramesByRawSn(rawSn);
    }

    private TrackSplitResponse doSplit(Long rawSn, String trackId, int atFrameNo, Long actorNo) {
        // DEV_FIX(H4) — 삭제 경로와 동일하게 프레임 락을 트랜잭션 맨 앞에서 1회·결정적 순서로 선점한다.
        lockFramesForRaw(rawSn);

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
        // DEV_FIX(H2① 락 순서) — 라벨 행을 UPDATE(트랙 재지정) 하기 <b>전에</b> 프레임 버전을 +1 해
        //   프레임 락을 먼저 잡는다(삭제와 동일 규약 — 라벨 락 → 프레임 락 역전 금지).
        bumpLabelVersions(changedFrames);
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

        // C-ISSUE-21 — 재보간이 추가로 건드린 프레임의 버전 +1 (재지정 대상 프레임은 위에서 이미 올렸다).
        Set<Long> reInterpFrames = new TreeSet<>(interpNew.touchedSrcSns());
        reInterpFrames.addAll(interpOrig.touchedSrcSns());
        bumpLabelVersions(reInterpFrames);

        log.info("[TrackEdit] split rawSn={} trackId={} -> newTrackId={} atFrameNo={} moved={} reInterp(new={},orig={})",
                rawSn, LogSanitizer.sanitize(trackId), newTrackId, atFrameNo, movable.size(),
                interpNew.newRowCount(), interpOrig.newRowCount());

        notifyIfApproved(rawSn, changedFrames, actorNo, ChangeType.LABEL_UPDATED);
        return new TrackSplitResponse(rawSn, trackId, newTrackId, atFrameNo, movable.size());
    }

    /**
     * 삭제 대상 라벨들의 DELETED 이력을 LS_DATA_LBL_HSTRY 에 <b>프레임(srcSn) 단위 저장 이벤트</b>로 기록(saveAll).
     * <p>V114 재구조화: 트랙은 여러 프레임에 걸치므로 srcSn 으로 group by 하여 프레임당 이벤트 1건
     * (해당 프레임 삭제 라벨의 DELETED changes 묶음, delCnt = 프레임 내 삭제 건수) 을 남긴다.
     * <p>regId 는 행위자 ID 수준만 저장한다(신고 경로와 달리 배정 검증을 통과한 명시적 편집 — 회귀 없음).
     */
    private void recordDeletionHistory(List<LsDataLbl> targets, Long actorNo) {
        String actorId = String.valueOf(actorNo);
        Map<Long, List<LsDataLbl>> bySrc = targets.stream()
                .collect(Collectors.groupingBy(LsDataLbl::getSrcSn, LinkedHashMap::new, Collectors.toList()));
        List<LsDataLblHstry> events = new ArrayList<>();
        bySrc.forEach((srcSn, labels) -> {
            List<LabelChange> changes = labels.stream()
                    .map(l -> LabelChange.deleted(l.getLblSn(), l.getLabelNm(),
                            new LabelSnapshot(l.getLblTypeCd(), l.getLabelId(), l.getLabelNm(), l.getPointCn())))
                    .toList();
            events.add(LsDataLblHstry.recordSaveEvent(srcSn, actorId, changes));
        });
        lblHstryRepository.saveAll(events);
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
        if (!approvalGate.isApproved(rawSn)) {
            return;
        }
        try {
            int published = 0;
            for (Long srcSn : changedFrames) {
                if (published >= MAX_NOTIFY_FRAMES) {
                    break;
                }
                // C-1(Phase 5C) — 트랙 편집은 라벨 좌표를 바꾼다. 라벨 본문은 데이터마트 뷰가 없어(V114 제거)
                //   파일 재생성만이 동기화 수단이므로 승인 후 수정은 exportRegenerated=true 로 발행한다.
                // Phase 7a-1 — needsRecheck=true (사람이 콘텐츠를 고치는 경로): 재검토 표시만 세운다.
                eventPublisher.publishEvent(new TaskModifiedEvent(rawSn, srcSn, changeType, actorNo, true, true));
                published++;
            }
        } catch (RuntimeException ex) {
            log.warn("[TrackEdit] task-modified notify failed rawSn={} reason={}", rawSn, ex.getMessage());
        }
    }
}
