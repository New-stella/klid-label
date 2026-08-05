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

import java.time.LocalDateTime;
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

    /**
     * 영상 검수 승인({@code ReviewService.approve}) 시점에 해당 영상(rawSn)의 event_annotation 검토를
     * <b>자동 확정(APPROVED)</b>한다 — 별도 메타 승인 단계 없이도 export 의 {@code event_annotation} 이
     * 항상 동결되도록 하기 위함(F: export event_annotation null 해소).
     *
     * <p>전이 규칙:
     * <ul>
     *   <li>{@code AUTO_GENERATED} 또는 {@code PENDING} → {@code APPROVED} (자동 확정).</li>
     *   <li>{@code REJECTED}: REVIEWER 가 명시 반려한 메타는 자동 승인하지 않는다(반려 존중, 동결 제외).</li>
     *   <li>{@code APPROVED}: 이미 승인 — 멱등 skip(재전이 없음).</li>
     *   <li>event_annotation 자체가 없는 영상: no-op(정상 승인).</li>
     * </ul>
     *
     * <p>이 경로는 {@code ReviewService.approve} 의 승인 트랜잭션(REQUIRED)에 편승하며, 바로 뒤에서
     * 같은 트랜잭션의 {@code materialize} 가 APPROVED event_annotation 을 EVNT_ANNO_CN 에 동결한다.
     * 따라서 역순 지연승인용 재동결({@link #triggerReFreezeIfAlreadyApproved})은 <b>호출하지 않는다</b>
     * (같은 트랜잭션의 후속 materialize 와 중복되기 때문).
     *
     * <p>전이 후 {@link #flushOrConflict}({@code reviewRepository.flush()})로 영속성 컨텍스트의 APPROVED
     * 상태를 즉시 반영해, 이어지는 materialize 의 승인 상태 조회가 이를 관측하도록 flush 순서를 보장한다.
     * 동시 전이(WORKER 재저장/REVIEWER 개별 승인 경합)는 낙관적 잠금(@Version)으로 정확히 1건만 성공하고
     * 충돌 1건은 {@link ErrorCode#CONFLICT}(409)로 거부되어 승인 트랜잭션이 함께 롤백된다(CWE-362).
     *
     * @param rawSn    검수 승인된 영상 PK
     * @param reviewer 승인한 REVIEWER(전이 rvwId/로그 식별자)
     */
    @Transactional("controlTransactionManager")
    public void autoApproveOnVideoApproval(Long rawSn, TokenClaims reviewer) {
        LsEvntAnnoReview review = resolveReviewOrNull(rawSn);
        if (review == null) {
            // event_annotation 없는 영상 — 정상 no-op(동결 대상 없음).
            return;
        }
        String status = review.getRvwSttsCd();
        if (LsEvntAnnoReview.STTS_APPROVED.equals(status)) {
            // 이미 승인 — 멱등 skip(재전이 없이 이어지는 materialize 가 동결).
            return;
        }
        if (LsEvntAnnoReview.STTS_REJECTED.equals(status)) {
            // 명시 반려 존중 — 자동 승인 제외(동결 안 됨).
            log.info("[EvntAnno] auto-approve skipped — rejected meta rawSn={}", rawSn);
            return;
        }
        // AUTO_GENERATED / PENDING → APPROVED. flush 로 materialize 조회 전 상태 반영 보장.
        review.approve(reviewer.sub());
        flushOrConflict(rawSn, "autoApprove", reviewer);
        log.info("[EvntAnno] auto-approved on video approval rawSn={} rvwSn={} actor={}",
                rawSn, review.getRvwSn(), parseActor(reviewer));
    }

    /** 시스템 치유 액터 — 지연 동결 event_annotation 백필/치유의 rvwId(무인 배치, 사람 검수자 아님). */
    private static final String HEAL_ACTOR = "SYSTEM";

    /**
     * event_annotation 지연 동결 <b>치유</b>(HIGH — 사용자 지목 rawSn 24) — 이미 검수 승인(APPROVED)됐고
     * 활성 스냅샷은 있으나 {@code EVNT_ANNO_CN=NULL} 로 동결돼 export 에서 event_annotation 이 영구 누락된
     * 영상 1건을 치유한다. 배치({@link kr.co.cudo.authoring.dataset.service.DatasetVideoMetaBackfillService})가
     * 대상 rawSn 을 넘겨 호출한다.
     *
     * <p>치유 동작(같은 트랜잭션 원자 실행):
     * <ol>
     *   <li>event_annotation/검토 부재 → no-op(치유 대상 아님, 정상 null 유지).</li>
     *   <li>최신 검토가 {@code REJECTED} → skip(명시 반려 존중).</li>
     *   <li>{@code AUTO_GENERATED}/{@code PENDING} → {@code APPROVED} 로 자동 확정 후 flush
     *       (이어지는 {@code materialize} 의 승인 상태 조회 반영 보장).</li>
     *   <li>{@code APPROVED}(이미 승인) → 전이 없이 통과.</li>
     *   <li>{@link DatasetVideoMetaSnapshotService#materialize(Long)} — 승인된 event_annotation 을 활성 스냅샷
     *       {@code EVNT_ANNO_CN} 에 재동결(내용 변경 → 새 active 버전 append). 이미 채워진 건은 애초에 대상
     *       쿼리에서 빠지므로 이 경로가 멱등 skip 을 이룬다.</li>
     * </ol>
     *
     * @return 실제 재동결을 수행하면 {@code true}, 대상 아님(부재/반려)이면 {@code false}
     */
    @Transactional("controlTransactionManager")
    public boolean healLateFrozenEventAnnotation(Long rawSn) {
        LsEvntAnnoReview review = resolveReviewOrNull(rawSn);
        if (review == null) {
            // event_annotation/검토 부재 — 치유 대상 아님(정상 null 유지).
            return false;
        }
        String status = review.getRvwSttsCd();
        if (LsEvntAnnoReview.STTS_REJECTED.equals(status)) {
            // 명시 반려 존중 — 치유 제외(동결 안 됨).
            log.info("[EvntAnno] heal skipped — rejected meta rawSn={}", rawSn);
            return false;
        }
        if (!LsEvntAnnoReview.STTS_APPROVED.equals(status)) {
            // AUTO_GENERATED / PENDING → APPROVED. flush 로 materialize 승인 상태 조회 전 반영 보장.
            review.approve(HEAL_ACTOR);
            reviewRepository.flush();
        }
        // 재동결: 활성 스냅샷 EVNT_ANNO_CN 을 승인된 event_annotation 으로 채운다(내용 변경 → 새 active 버전).
        // 검수 완료 일시는 기존 활성 스냅샷 값을 승계한다(치유 실행 시각으로 덮지 않음 — 아래 A 결함 동일).
        snapshotService.materialize(rawSn, frozenReviewCompletedAt(rawSn));
        log.info("[EvntAnno] heal re-froze event_annotation rawSn={}", rawSn);
        return true;
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
     *   <li>{@link DatasetVideoMetaSnapshotService#materialize(Long, LocalDateTime)} 재실행 — 방금 승인된
     *       event_annotation 이 동결본에 포함되어(해시에 EVNT_ANNO_CN 반영) 내용 변경 시 새 active 스냅샷
     *       버전이 append 된다. 이때 <b>검수 완료 일시(RVW_CMPL_DT)는 기존 활성 스냅샷 값을 승계</b>한다 —
     *       1-arg 오버로드(=now())로 호출하면 새 행의 승인 시각이 <b>지연 승인 시각</b>으로 덮여
     *       {@code V_COMPLETED_VIDEO.RVW_CMPTN_DT}·포털 복제본이 오염된다(TASK_COMPLETED 계약 위반).</li>
     *   <li>{@link TaskModifiedEvent}(META_UPDATED, {@code exportRegenerated=true}) 발행 — 완료된 작업의 후속
     *       수정 통지(CLAUDE.md 통지 정책). MED-F(Phase 5C): 이 한 축이 export 전량 재생성 → 통지를 직렬화한다.
     *       구 {@code DatasetReExportEvent} 병행 발행은 이중 export(유령 버전 폴더)를 만들어 제거했다.</li>
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
        // 검수 완료 일시는 기존 활성 스냅샷 값을 승계한다(지연 승인 시각으로 덮지 않음 — A 결함 방어).
        snapshotService.materialize(rawSn, frozenReviewCompletedAt(rawSn));
        // MED-F(Phase 5C) — 재산출 트리거는 TaskModifiedEvent(exportRegenerated=true) 한 축으로만 한다.
        //   구 구현은 DatasetReExportEvent(→ 항상활성 DatasetExportBridge export) 와 TaskModifiedEvent(regen=true)
        //   (→ 디바운서 flush 가 runReExportThenNotify 로 또 export)를 둘 다 발행해 전량 재생성이 2회 일어나고
        //   유령 버전 폴더가 1개 append 됐다(전 버전 보존이라 삭제 안 됨). HIGH-E 재배선으로 TaskModifiedEvent
        //   (regen=true)가 export(전량 재생성) → 통지를 단일 경로로 직렬화하므로 DatasetReExportEvent 는 제거한다.
        //   exportRegenerated=true 로 프레임 이미지·JSON 이 전량 재생성되고, 통지도 전 프레임을 실어 관제가
        //   새 산출물을 재픽업한다(A-2). materialize(재동결) 자체는 그대로 유지한다.
        eventPublisher.publishEvent(new TaskModifiedEvent(
                rawSn, null, ChangeType.META_UPDATED, parseActor(reviewer), true));
        log.info("[EvntAnno] late-approval re-freeze triggered rawSn={}", rawSn);
    }

    /**
     * 재동결 시 승계할 <b>검수 완료 일시</b> — 기존 활성 스냅샷의 {@code RVW_CMPL_DT} 를 반환한다.
     * 재동결은 승인 시각이 아니라 event_annotation 지연 승인·치유 시각에 일어나므로, 이 값을
     * {@code materialize(rawSn, at)} 2-arg 로 넘겨 최초 검수 완료 시각을 보존한다(호출 전 활성 스냅샷 존재
     * 확인됨 — 부재/일시 null 이면 그대로 null 을 넘겨 materialize 가 now() 로 폴백한다).
     */
    private LocalDateTime frozenReviewCompletedAt(Long rawSn) {
        return videoMetaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES).stream()
                .findFirst()
                .map(LsDatasetVideoMeta::getRvwCmplDt)
                .orElse(null);
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

    /**
     * RAW_SN → event_annotation → 검토 row 2단 해석(자동 승인용). 부재 시 예외 대신 {@code null} 반환 —
     * event_annotation 이 없는 영상의 검수 승인은 정상 no-op 이어야 하기 때문(자동 승인 경로 전용).
     */
    private LsEvntAnnoReview resolveReviewOrNull(Long rawSn) {
        LsEvntAnno anno = annoRepository.findByRawSn(rawSn).orElse(null);
        if (anno == null) {
            return null;
        }
        return reviewRepository.findByEvntAnnoSn(anno.getEvntAnnoSn()).stream()
                .findFirst()
                .orElse(null);
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
