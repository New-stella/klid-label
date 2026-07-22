package kr.co.cudo.authoring.evntanno.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.event.DatasetReExportEvent;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 2 — event_annotation 검수(승인/반려) 서비스. REVIEWER 전용.
 *
 * <p>검토 테이블({@code LS_EVNT_ANNO_REVIEW})에는 RAW_SN 이 없으므로
 * {@code RAW_SN → LS_EVNT_ANNO(evntAnnoSn) → LS_EVNT_ANNO_REVIEW} 2단 조회로 검토 row 를 해석한다.
 * 상태 전이(PENDING/AUTO_GENERATED → APPROVED/REJECTED)는 엔티티가 소유하며, 이미 완료된 검토를
 * 재전이하면 {@link LsEvntAnnoReview} 가 CONFLICT(409)를 던진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class EvntAnnoReviewService {

    private final LsEvntAnnoRepository annoRepository;
    private final LsEvntAnnoReviewRepository reviewRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final LsDatasetVideoMetaRepository videoMetaRepository;
    /** event_annotation 지연 승인 시 동결 스냅샷을 재동결(materialize)하기 위한 재사용 어댑터. */
    private final DatasetVideoMetaSnapshotService snapshotService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * REVIEWER 가 영상의 event_annotation 검토를 승인. PENDING/AUTO_GENERATED 에서만 가능.
     * 동시 두 REVIEWER 가 같은 검토를 승인 시도하면 낙관적 잠금(@Version)으로 1건만 성공하고
     * 다른 1건은 OptimisticLockException → CONFLICT(409)로 거부된다(CWE-362).
     *
     * <p>승인이 확정되면, 이 영상이 <b>이미 검수 승인(APPROVED, 활성 동결 스냅샷 존재)</b> 상태인 경우
     * 방금 승인된 event_annotation 을 동결본에 반영하기 위해 재동결·export 재생성을 트리거한다
     * ({@link #triggerReFreezeIfAlreadyApproved}). 영상이 아직 미승인이면 트리거하지 않는다 —
     * 이후 영상 승인의 materialize 가 이미 승인된 event_annotation 을 정상 캡처하므로 중복이 없다.
     */
    @Transactional("controlTransactionManager")
    public void approve(Long rawSn, TokenClaims reviewer) {
        ensureReviewer(reviewer);
        LsEvntAnnoReview review = resolveReview(rawSn);
        review.approve(reviewer.sub());
        flushOrConflict(rawSn, "approve", reviewer);
        log.info("[EvntAnno] review approved rawSn={} rvwSn={} actor={}",
                rawSn, review.getRvwSn(), parseActor(reviewer));
        triggerReFreezeIfAlreadyApproved(rawSn, reviewer);
    }

    /** REVIEWER 가 영상의 event_annotation 검토를 반려. 사유 필수. 동시 전이 시 409(위 approve 동일). */
    @Transactional("controlTransactionManager")
    public void reject(Long rawSn, String reason, TokenClaims reviewer) {
        ensureReviewer(reviewer);
        LsEvntAnnoReview review = resolveReview(rawSn);
        review.reject(reviewer.sub(), reason);
        flushOrConflict(rawSn, "reject", reviewer);
        log.info("[EvntAnno] review rejected rawSn={} rvwSn={} actor={}",
                rawSn, review.getRvwSn(), parseActor(reviewer));
    }

    /**
     * event_annotation <b>지연 승인</b> 처리 — HIGH 결함 수정(export 영구 누락 방지).
     *
     * <p>영상 검수 승인({@code ReviewService.approve})이 먼저 일어난 경우, 그 시점 materialize 는
     * event_annotation 이 아직 PENDING 이라 {@code EVNT_ANNO_CN=null} 로 동결한다. 이후 이 메서드로
     * event_annotation 이 뒤늦게 승인되면 활성 스냅샷은 이미 존재해 백필 대상에서도 제외되므로,
     * 재동결 경로가 없으면 export JSON 의 {@code event_annotation} 이 <b>영구 null</b>로 조용히 누락된다.
     *
     * <p>따라서 승인 확정 후 영상이 <b>이미 APPROVED + 활성 스냅샷 존재</b>면 재동결한다:
     * <ol>
     *   <li>{@link DatasetVideoMetaSnapshotService#materialize(Long)} 재실행 — 방금 승인된 event_annotation 이
     *       동결본에 포함되어(해시에 EVNT_ANNO_CN 반영) 내용 변경 시 새 active 스냅샷 버전이 append 된다.</li>
     *   <li>{@link DatasetReExportEvent} 발행 — AFTER_COMMIT 로 export 재생성만 트리거(TASK_COMPLETED 재발행 없음).</li>
     *   <li>{@link TaskModifiedEvent}(META_UPDATED) 발행 — 완료된 작업의 후속 수정 통지(CLAUDE.md 통지 정책,
     *       {@code EvntAnnoService.upsert} 발행 패턴과 동일).</li>
     * </ol>
     *
     * <p>영상이 아직 미승인이면 트리거하지 않는다 — 이후 영상 승인의 materialize 가 이미 승인된
     * event_annotation 을 정상 캡처하므로 중복 재동결이 없다(역순 무회귀). 재동결/이벤트 로그는 rawSn
     * 식별자만 남긴다(payload/PII 미노출, CWE-359/117).
     */
    private void triggerReFreezeIfAlreadyApproved(Long rawSn, TokenClaims reviewer) {
        boolean videoApproved = rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
        if (!videoApproved) {
            // 영상 미승인 — 이후 영상 승인 시 materialize 가 승인된 event_annotation 을 정상 캡처(중복 방지).
            return;
        }
        boolean hasActiveSnapshot = !videoMetaRepository
                .findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES).isEmpty();
        if (!hasActiveSnapshot) {
            // APPROVED 인데 활성 스냅샷 부재(백필 미완 등 이례) — 재동결 대상 아님(fail-safe skip).
            log.warn("[EvntAnno] late-approval re-freeze skipped — no active snapshot rawSn={}", rawSn);
            return;
        }
        // 재동결: 방금 승인된 event_annotation 을 동결본에 반영(내용 변경 시 새 active 스냅샷 버전 append).
        snapshotService.materialize(rawSn);
        // export 재생성(TASK_COMPLETED 재발행 없이 export 만 갱신) + 완료 작업 수정 통지(TASK_MODIFIED).
        eventPublisher.publishEvent(new DatasetReExportEvent(rawSn));
        eventPublisher.publishEvent(new TaskModifiedEvent(
                rawSn, null, ChangeType.META_UPDATED, parseActor(reviewer)));
        log.info("[EvntAnno] late-approval re-freeze triggered rawSn={}", rawSn);
    }

    /**
     * 상태전이 flush 를 강제해 낙관적 잠금 충돌을 트랜잭션 커밋 전에 표면화한다. 충돌 시 409 로 매핑
     * (형제 {@code ReviewService.approve} 선례와 동일 — GlobalExceptionHandler 의존 없이 서비스에서 변환).
     */
    private void flushOrConflict(Long rawSn, String op, TokenClaims reviewer) {
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[EvntAnno] optimistic lock conflict on {} rawSn={} actor={}",
                    op, rawSn, parseActor(reviewer));
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 처리했습니다.");
        }
    }

    /** 로그 인젝션(CWE-117) 방어 — 토큰 subject 를 숫자 사용자번호로 정규화해 로깅한다. */
    private Long parseActor(TokenClaims reviewer) {
        try {
            return Long.parseLong(reviewer.sub());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** RAW_SN → event_annotation → 검토 row 2단 해석. */
    private LsEvntAnnoReview resolveReview(Long rawSn) {
        LsEvntAnno anno = annoRepository.findByRawSn(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "event_annotation 이 존재하지 않습니다."));
        return reviewRepository.findByEvntAnnoSn(anno.getEvntAnnoSn()).stream()
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "event_annotation 검토가 존재하지 않습니다."));
    }

    private void ensureReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }
}
