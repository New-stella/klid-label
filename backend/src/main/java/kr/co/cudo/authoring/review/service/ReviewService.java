package kr.co.cudo.authoring.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.assignment.entity.LsPjtTaskEventLog;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.review.dto.FrameDetailResponse;
import kr.co.cudo.authoring.review.dto.FrameListResponse;
import kr.co.cudo.authoring.review.dto.IssueResponse;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final LsPjtTaskEventLogRepository taskEventLogRepository;
    private final ReviewStateMachine stateMachine;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;

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
     * 검수 단건 상세 조회 (REVIEWER) — 검수 상세 화면 진입 시.
     */
    public ReviewResponse getDetail(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        LsPjtDataStts stts = loadByVideoId(videoId);
        return ReviewResponse.from(stts);
    }

    /**
     * SCR-REVIEW-002 — 영상의 모든 프레임 + 라벨 일괄 응답 (REVIEWER).
     *
     * <p>N+1 회피: 프레임 1회 (findByRawSn..) + 라벨 1회 (findBySrcSnIn) — 총 2회 쿼리.
     */
    public FrameListResponse listFrames(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        // 영상 존재 확인 — 미존재 시 404
        videoRepository.findById(videoId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 1) 프레임 일괄 조회
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(videoId);
        if (frames.isEmpty()) {
            return new FrameListResponse(videoId, 0, Collections.emptyList());
        }

        // 2) 라벨 IN-쿼리 — N+1 회피
        List<Long> srcSns = frames.stream().map(LsDataSrc::getSrcSn).toList();
        List<LsDataLbl> allLabels = labelRepository.findBySrcSnIn(srcSns);

        // 3) srcSn -> 라벨 목록 매핑
        Map<Long, List<LabelResponse.Item>> labelMap = new HashMap<>();
        for (LsDataLbl entity : allLabels) {
            labelMap.computeIfAbsent(entity.getSrcSn(), k -> new ArrayList<>())
                    .add(LabelResponse.Item.from(entity, objectMapper));
        }

        // 4) 프레임 DTO 매핑 (frameNo 순서 보장)
        List<FrameDetailResponse> details = new ArrayList<>(frames.size());
        for (LsDataSrc src : frames) {
            String imageUrl = "/v1/videos/" + videoId + "/frames/" + src.getFrameNo() + "/image";
            details.add(new FrameDetailResponse(
                    src.getSrcSn(),
                    src.getFrameNo(),
                    imageUrl,
                    labelMap.getOrDefault(src.getSrcSn(), Collections.emptyList())
            ));
        }
        return new FrameListResponse(videoId, details.size(), details);
    }

    /**
     * 검수 이슈(반려 사유) 목록 조회 (REVIEWER).
     * LS_DATA_ISSUE 가 비어 있으면 빈 배열 반환.
     */
    public List<IssueResponse> listIssues(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        return issueRepository.findByVideoIdOrderByRegisteredAtDesc(videoId).stream()
                .map(IssueResponse::from)
                .toList();
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
        // 통합 이벤트 로그 (SCR-TASK-003): 검수 제출 이벤트 기록
        Long workerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsPjtTaskEventLog.submit(
                stts.getRawDataId(), workerUserNo));
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
        // 통합 이벤트 로그 (SCR-TASK-003): 승인 이벤트 기록
        Long reviewerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsPjtTaskEventLog.approve(
                stts.getRawDataId(), reviewerUserNo));
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
        // 통합 이벤트 로그 (SCR-TASK-003): 반려 이벤트 기록
        Long reviewerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsPjtTaskEventLog.reject(
                stts.getRawDataId(), reviewerUserNo, req.reason()));
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
     * VIDEO_ID(=RAW_SN) 단위 LS_PJT_DATA_STTS 조회.
     * V34 이후 RAW_DATA_ID 는 단일 PK 이므로 단건 조회 후 미존재 시 404.
     */
    private LsPjtDataStts loadByVideoId(Long videoId) {
        return reviewRepository.findByRawDataId(videoId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "검수 대상 영상을 찾을 수 없습니다."));
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
