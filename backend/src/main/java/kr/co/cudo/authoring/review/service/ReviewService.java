package kr.co.cudo.authoring.review.service;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 7 — 검수 워크플로우 (REVIEWER 승인/반려).
 *
 * <p>RBAC:
 * <ul>
 *   <li>submit (PENDING 으로 제출)       : WORKER (본인 배정 영상만)</li>
 *   <li>startReview / approve / reject   : REVIEWER 만 (현재 정책: 모든 영상 가능)</li>
 * </ul>
 *
 * <p>동시성: LS_PJT_DATA_STTS 의 {@code @Version} 컬럼으로 낙관적 잠금. 동시 두 REVIEWER 가
 * 같은 영상을 승인 시도할 때 1건만 성공 → 다른 1건은 {@link ErrorCode#CONFLICT}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final IssueRepository issueRepository;
    private final LsPjtUserAuthrtRepository authrtRepository;
    private final ReviewStateMachine stateMachine;

    /**
     * 검수 워크플로우 상태별 페이징 목록 (REVIEWER 의 검수 목록 화면용).
     * status 가 null/빈 문자열이면 전체.
     */
    public Page<ReviewResponse> list(String status, Pageable pageable, TokenClaims actor) {
        requireReviewer(actor);
        return reviewRepository.searchByStatus(status, pageable)
                .map(ReviewResponse::from);
    }

    /**
     * 작업자가 라벨링 완료 후 검수 제출. 본인에게 LABELER 로 배정된 영상만 가능.
     * 상태: ASSIGNED → PENDING (또는 REJECTED → PENDING 재제출).
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse submit(Long videoId, TokenClaims actor) {
        verifyAssignedWorker(videoId, actor);
        LsPjtDataStts stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsPjtDataStts.STTS_PENDING);
        stts.transitionTo(LsPjtDataStts.STTS_PENDING);
        log.info("[Review] submitted videoId={} actor={}", videoId, actor.sub());
        return ReviewResponse.from(stts);
    }

    /**
     * REVIEWER 가 검수 시작. PENDING → IN_REVIEW.
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse startReview(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        LsPjtDataStts stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsPjtDataStts.STTS_IN_REVIEW);
        stts.transitionTo(LsPjtDataStts.STTS_IN_REVIEW);
        log.info("[Review] startReview videoId={} actor={}", videoId, actor.sub());
        return ReviewResponse.from(stts);
    }

    /**
     * REVIEWER 승인. IN_REVIEW → APPROVED. 동시 승인 시도 시 OptimisticLockException → 409.
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse approve(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        LsPjtDataStts stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsPjtDataStts.STTS_APPROVED);
        stts.transitionTo(LsPjtDataStts.STTS_APPROVED);
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Review] optimistic lock conflict on approve videoId={} actor={}", videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 처리했습니다.");
        }
        log.info("[Review] approved videoId={} actor={}", videoId, actor.sub());
        return ReviewResponse.from(stts);
    }

    /**
     * REVIEWER 반려. IN_REVIEW → REJECTED. LS_DATA_ISSUE 신규 INSERT (사유 필수).
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse reject(Long videoId, RejectRequest req, TokenClaims actor) {
        requireReviewer(actor);
        LsPjtDataStts stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsPjtDataStts.STTS_REJECTED);

        // 직전 반려가 있으면 계층 연결 (UP_DATA_ISSUE_SN)
        Long parentIssueSn = issueRepository.findByVideoIdOrderByRegisteredAtDesc(videoId).stream()
                .findFirst()
                .map(LsDataIssue::getDataIssueSn)
                .orElse(null);
        LsDataIssue issue = (parentIssueSn == null)
                ? LsDataIssue.create(videoId, req.reason(), actor.sub())
                : LsDataIssue.createWithParent(videoId, req.reason(), actor.sub(), parentIssueSn);
        issueRepository.save(issue);

        stts.transitionTo(LsPjtDataStts.STTS_REJECTED);
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Review] optimistic lock conflict on reject videoId={} actor={}", videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 처리했습니다.");
        }
        log.info("[Review] rejected videoId={} actor={} parentIssueSn={}", videoId, actor.sub(), parentIssueSn);
        return ReviewResponse.from(stts);
    }

    /**
     * VIDEO_ID(=RAW_SN) 단위 LS_PJT_DATA_STTS 조회. 동일 RAW_DATA_ID 가 여러 PJT 매핑된 경우 단건 보장 깨짐 → 409.
     */
    private LsPjtDataStts loadByVideoId(Long videoId) {
        List<LsPjtDataStts> list = reviewRepository.findByIdRawDataId(videoId);
        if (list.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "검수 대상 영상을 찾을 수 없습니다.");
        }
        if (list.size() > 1) {
            throw new CustomException(ErrorCode.CONFLICT, "동일 영상이 다수 프로젝트에 매핑되어 있습니다.");
        }
        return list.get(0);
    }

    /**
     * 작업자 본인이 LABELER 로 배정된 영상인지 검증 (CWE-639 IDOR 방어).
     * REVIEWER 가 호출하면 권한 부족으로 차단 (submit 은 WORKER 전용).
     */
    private void verifyAssignedWorker(Long videoId, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.WORKER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "WORKER 권한이 필요합니다.");
        }
        Long selfNo = parseUserNo(actor.sub());
        boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                selfNo, LsPjtUserAuthrt.TASK_LABELER, videoId);
        if (!assigned) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
        }
    }

    private void requireReviewer(TokenClaims actor) {
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
