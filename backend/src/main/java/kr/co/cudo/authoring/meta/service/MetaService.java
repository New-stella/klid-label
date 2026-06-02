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
        return MetaResponse.of(metas);
    }

    @Transactional("controlTransactionManager")
    public MetaResponse update(Long srcSn, MetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = verifyAccess(srcSn, actor);
        Long rawSn = src.getRawSn();

        for (MetaUpdateRequest.Item item : req.items()) {
            LsDataMeta meta = metaRepository.findByRawSnAndMetaKey(rawSn, item.metaKey())
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                            "메타 키가 존재하지 않습니다: " + item.metaKey()));
            meta.updateValue(item.metaVal());
        }
        log.info("[Meta] updated rawSn={} count={}", rawSn, req.items().size());
        // TASK_MODIFIED 통지는 검수 완료(APPROVED) 후 수정 시에만 발행한다(CLAUDE.md 작업 단위 통지 정책).
        // 검수 전 저장은 일반 작업이므로 통지 미발행 (라벨 경로와 동일 가드).
        if (isReviewApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, srcSn, "META", parseUserNo(actor.sub())));
        }
        return MetaResponse.of(metaRepository.findByRawSn(rawSn));
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
