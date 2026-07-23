package kr.co.cudo.authoring.meta.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaResponse;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 5 — V1.7 정책: 외부 시스템이 생성한 시계열 메타의 검토·수정만 제공.
 *  - 메타 자동 생성 엔드포인트 없음 (외부 시스템 책임).
 *  - VLM/외부 메타에 대한 검토 상태 (LS_DATA_META_REVIEW) approve/reject API 제공.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class MetaService {

    private final LsDataMetaRepository metaRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    private final LsDataMetaReviewRepository metaReviewRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 검수 완료(APPROVED) 여부 판정용 영상 상태 조회. */
    private final LsRawDataStatusRepository rawDataStatusRepository;

    public MetaResponse getByFrame(Long srcSn, TokenClaims actor) {
        LsDataSrc src = verifyAccess(srcSn, actor);
        List<LsDataMeta> metas = metaRepository.findByRawSn(src.getRawSn());
        return toResponse(metas);
    }

    /**
     * 메타 목록에 검토상태를 조인해 응답 생성. metaSn 집합으로 검토행을 배치 조회(N+1 금지)한다.
     * 메타가 0건이면 검토행 조회조차 생략한다. 검토행 없는 메타는 검토 필드 null.
     */
    private MetaResponse toResponse(List<LsDataMeta> metas) {
        if (metas.isEmpty()) {
            return MetaResponse.of(metas);
        }
        List<Long> metaSns = metas.stream().map(LsDataMeta::getMetaSn).toList();
        Map<Long, LsDataMetaReview> reviewByMetaSn = metaReviewRepository.findByDataMetaSnIn(metaSns).stream()
                .collect(Collectors.toMap(LsDataMetaReview::getDataMetaSn, r -> r, (a, b) -> a));
        return MetaResponse.of(metas, reviewByMetaSn);
    }

    @Transactional("controlTransactionManager")
    public MetaResponse update(Long srcSn, MetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = verifyAccess(srcSn, actor);
        Long rawSn = src.getRawSn();

        for (MetaUpdateRequest.Item item : req.items()) {
            upsertItem(rawSn, item);
        }
        log.info("[Meta] upserted rawSn={} count={}", rawSn, req.items().size());
        // TASK_MODIFIED 통지는 검수 완료(APPROVED) 후 수정 시에만 발행한다(CLAUDE.md 작업 단위 통지 정책).
        // 검수 전 저장은 일반 작업이므로 통지 미발행 (라벨 경로와 동일 가드).
        if (isReviewApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, srcSn, ChangeType.META_UPDATED, parseUserNo(actor.sub())));
        }
        return toResponse(metaRepository.findByRawSn(rawSn));
    }

    /**
     * (rawSn, metaKey) upsert — 기존이면 값만 수정, 없으면 신규 등록 + 검토 큐(PENDING) 진입.
     *
     * <p>원자적 {@code ON CONFLICT} upsert({@link LsDataMetaRepository#upsertMeta})로 동일
     * (rawSn, metaKey) 동시 INSERT race(CWE-362)에도 UNIQUE 위반 크래시 없이 멱등하게 동작한다.
     * 신규 판정은 upsert 직전 조회로 하고, upsert 후 재조회로 신규 metaSn 을 얻어 검토행을 만든다.
     */
    private void upsertItem(Long rawSn, MetaUpdateRequest.Item item) {
        boolean isNew = metaRepository.findByRawSnAndMetaKey(rawSn, item.metaKey()).isEmpty();
        metaRepository.upsertMeta(rawSn, item.metaKey(), item.metaVal());
        if (isNew) {
            LsDataMeta saved = metaRepository.findByRawSnAndMetaKey(rawSn, item.metaKey())
                    .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_ERROR,
                            "메타 저장에 실패했습니다."));
            ensureReviewRow(saved, rawSn);
        }
    }

    /**
     * 신규 메타는 VLM 경로와 동일하게 PENDING 검토 큐(LS_DATA_META_REVIEW)에 진입시킨다.
     * 수동 입력 메타는 VLM 세그먼트가 아니므로 유형은 EXTERNAL 로 분류한다.
     * 이미 검토행이 있으면(경합/재저장) 재사용하여 단일 검토행 중복 생성을 막는다.
     */
    private void ensureReviewRow(LsDataMeta meta, Long rawSn) {
        if (metaReviewRepository.existsByDataMetaSn(meta.getMetaSn())) {
            return;
        }
        metaReviewRepository.save(LsDataMetaReview.createAuto(
                meta.getMetaSn(), rawSn, null,
                LsDataMetaReview.META_TYPE_EXTERNAL, null,
                LsDataMetaReview.STTS_PENDING));
    }

    /** REVIEWER 가 자동/외부 메타 검토 승인. */
    @Transactional("controlTransactionManager")
    public void approveReview(Long metaReviewSn, TokenClaims actor) {
        ensureReviewer(actor);
        LsDataMetaReview review = metaReviewRepository.findById(metaReviewSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "메타 검토를 찾을 수 없습니다: " + metaReviewSn));
        review.approve(actor.sub(), LocalDateTime.now());
        log.info("[Meta] review approved metaReviewSn={} actor={}", metaReviewSn, actor.sub());
    }

    /**
     * 영상 검수 승인({@code ReviewService.approve}) 시점에 해당 영상(rawSn)의 시계열 메타 검토행
     * (LS_DATA_META_REVIEW)을 <b>자동 확정(APPROVED)</b>한다 — 별도 메타 승인 단계 없이도 export/
     * 데이터마트 뷰 {@code V_COMPLETED_META}(RVW_STTS_CD='APPROVED'만 노출)에 시계열 메타가 누락되지
     * 않도록 하기 위함(버그 F 완성). event_annotation 자동 확정
     * ({@code EvntAnnoReviewService.autoApproveOnVideoApproval})과 동일한 전이 규칙을 미러링한다.
     *
     * <p>전이 규칙(검토행별):
     * <ul>
     *   <li>{@code AUTO_GENERATED} 또는 {@code PENDING} → {@code APPROVED} (자동 확정).</li>
     *   <li>{@code REJECTED}: REVIEWER 가 명시 반려한 메타는 자동 승인하지 않는다(반려 존중, 동결 제외).</li>
     *   <li>{@code APPROVED}: 이미 승인 — 멱등 skip(재전이 없음).</li>
     *   <li>검토행이 없는 영상: no-op(정상 승인).</li>
     * </ul>
     *
     * <p>{@link LsDataMetaReview#approve}는 내부 {@code ensureReviewable()}이 APPROVED/REJECTED 면
     * CONFLICT(409)를 던지므로, <b>반드시 상태 필터링을 먼저</b> 하고 AUTO_GENERATED/PENDING 에만 approve 를
     * 호출한다(예외를 삼키지 않음). 하나라도 전이하면 {@code flush()}로 영속성 컨텍스트 APPROVED 상태를
     * 즉시 반영해, 이어지는 {@code materialize}의 조회가 이를 관측하도록 flush 순서를 보장한다.
     *
     * <p>인가: 이 메서드는 {@code ReviewService.approve}(이미 {@code requireReviewer} 로 REVIEWER 게이트
     * 통과) 에서만 호출되므로 별도 role 검증 없이 {@code reviewer.sub()} 만 전이 rvwId 로 사용한다
     * (actor null 방어만 최소 수행). 로그는 rawSn/건수만 남긴다(본문/PII 미노출, CWE-359/117).
     *
     * @param rawSn    검수 승인된 영상 PK
     * @param reviewer 승인한 REVIEWER(전이 rvwId/로그 식별자)
     */
    @Transactional("controlTransactionManager")
    public void autoApproveOnVideoApproval(Long rawSn, TokenClaims reviewer) {
        if (reviewer == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        List<LsDataMetaReview> reviews = metaReviewRepository.findAllByDataRawSn(rawSn);
        if (reviews.isEmpty()) {
            // 시계열 메타 검토행 없는 영상 — 정상 no-op(확정 대상 없음).
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int approved = 0;
        for (LsDataMetaReview review : reviews) {
            String status = review.getRvwSttsCd();
            if (LsDataMetaReview.STTS_APPROVED.equals(status)) {
                // 이미 승인 — 멱등 skip(재전이 없이 이어지는 materialize 가 동결).
                continue;
            }
            if (LsDataMetaReview.STTS_REJECTED.equals(status)) {
                // 명시 반려 존중 — 자동 승인 제외(동결 안 됨).
                log.info("[Meta] auto-approve skipped — rejected meta rawSn={} metaReviewSn={}",
                        rawSn, review.getDataMetaReviewSn());
                continue;
            }
            // AUTO_GENERATED / PENDING → APPROVED.
            review.approve(reviewer.sub(), now);
            approved++;
        }
        if (approved > 0) {
            // flush 로 materialize 조회 전 APPROVED 상태 반영 보장.
            metaReviewRepository.flush();
            log.info("[Meta] auto-approved timeseries meta on video approval rawSn={} count={}",
                    rawSn, approved);
        }
    }

    /** REVIEWER 가 자동/외부 메타 검토 반려. 사유 필수. */
    @Transactional("controlTransactionManager")
    public void rejectReview(Long metaReviewSn, String reason, TokenClaims actor) {
        ensureReviewer(actor);
        LsDataMetaReview review = metaReviewRepository.findById(metaReviewSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "메타 검토를 찾을 수 없습니다: " + metaReviewSn));
        review.reject(reason, actor.sub(), LocalDateTime.now());
        log.info("[Meta] review rejected metaReviewSn={} actor={}", metaReviewSn, actor.sub());
    }

    /**
     * 영상(rawSn) 의 검수 상태가 APPROVED(검수 완료) 인지 판정.
     * 상태 row 가 없으면 미검수로 간주하여 false. 매직스트링 금지 — {@link LsRawDataStatus#STTS_APPROVED} 상수 비교.
     */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }

    private LsDataSrc verifyAccess(Long srcSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        LsDataSrc src = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        if (actor.role() == Role.REVIEWER) {
            return src;
        }
        if (actor.role() == Role.WORKER) {
            Long selfNo = parseUserNo(actor.sub());
            boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                    selfNo, LsTaskAssignment.TASK_LABELER, src.getRawSn());
            if (!assigned) {
                throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
            }
            return src;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "메타 접근 권한이 없습니다.");
    }

    private void ensureReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
