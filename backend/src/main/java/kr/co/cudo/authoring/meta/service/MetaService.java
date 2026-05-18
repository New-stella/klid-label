package kr.co.cudo.authoring.meta.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaResponse;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
