package kr.co.cudo.authoring.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
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
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
 * <p>동시성: LS_RAW_DATA_STATUS 의 {@code @Version} 컬럼으로 낙관적 잠금. 동시 두 REVIEWER 가
 * 같은 영상을 승인 시도할 때 1건만 성공 → 다른 1건은 {@link ErrorCode#CONFLICT}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final IssueRepository issueRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    private final LsTaskEventLogRepository taskEventLogRepository;
    private final ReviewStateMachine stateMachine;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 검수 워크플로우 상태별 페이징 목록 (REVIEWER 의 검수 목록 화면용).
     * status 가 null/빈 문자열이면 전체.
     */
    public Page<ReviewResponse> list(String status, Pageable pageable, TokenClaims actor) {
        requireReviewer(actor);
        Page<LsRawDataStatus> page = reviewRepository.searchByStatus(status, pageable);
        List<LsRawDataStatus> rows = page.getContent();
        if (rows.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, page.getTotalElements());
        }

        // 페이지의 영상 ID 집합 (N+1 회피용 batch lookup 키)
        List<Long> videoIds = rows.stream()
                .map(LsRawDataStatus::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        // 1) CCTV 명 lookup — LS_DATA_RAW LEFT JOIN MNG_RESOURCE_CCTV (단일 native 쿼리)
        Map<Long, String> cctvNameMap = lookupCctvNames(videoIds);

        // 2) LABELER 배정 lookup — REG_DT DESC, rawDataId → userNo (단일 IN 쿼리)
        Map<Long, Long> workerIdMap = lookupLabelerByVideo(videoIds);

        // 3) 사용자 이름 lookup — userNo → userNm (단일 IN 쿼리)
        Map<Long, String> userNameMap = lookupUserNames(workerIdMap.values());

        // 4) 영상별 라벨 총개수 lookup — LS_DATA_LBL JOIN LS_DATA_SRC GROUP BY rawSn (단일 IN 쿼리)
        Map<Long, Long> labelCountMap = lookupLabelCountByVideo(videoIds);

        // 5) 영상별 이벤트 메타 lookup — LS_DATA_RAW.EVNT_TYPE_CD (단일 IN 쿼리)
        Map<Long, String[]> eventInfoMap = lookupEventByVideo(videoIds);

        List<ReviewResponse> content = rows.stream()
                .map(stts -> {
                    Long videoId = stts.getRawDataId();
                    String cctvName = cctvNameMap.get(videoId);
                    Long workerId = workerIdMap.get(videoId);
                    String workerName = (workerId != null) ? userNameMap.get(workerId) : null;
                    Long labelCount = labelCountMap.getOrDefault(videoId, 0L);
                    String[] eventInfo = eventInfoMap.get(videoId);
                    String eventName = (eventInfo != null) ? eventInfo[0] : null;
                    String eventTypeCd = (eventInfo != null) ? eventInfo[1] : null;
                    return ReviewResponse.from(stts, cctvName, workerId, workerName, labelCount,
                            eventName, eventTypeCd);
                })
                .toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /**
     * 페이지의 영상 ID 들에 대해 (rawSn → cctvNm) 매핑을 단일 native 쿼리로 조회.
     * cctvNm 이 비어 있으면 VMS_CCTV_ID 폴백을 사용한다 (AssignmentService 와 동일 정책).
     * 둘 다 비어 있으면 키 자체를 넣지 않아 ReviewResponse.from 의 "video #N" 폴백이 작동한다.
     */
    private Map<Long, String> lookupCctvNames(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : videoRepository.findCctvNamesByRawSns(videoIds)) {
            if (row == null || row.length < 3 || row[0] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            String cctvNm = row[1] != null ? row[1].toString() : null;
            String vmsCctvId = row[2] != null ? row[2].toString() : null;
            String resolved = (cctvNm != null && !cctvNm.isBlank())
                    ? cctvNm
                    : (vmsCctvId != null && !vmsCctvId.isBlank() ? vmsCctvId : null);
            if (resolved != null) {
                map.put(rawSn, resolved);
            }
        }
        return map;
    }

    /**
     * 페이지의 영상 ID 들에 대해 LABELER 배정 (rawDataId → 작업자 userNo) 매핑을 단일 IN 쿼리로 조회.
     * 동일 영상에 여러 LABELER 배정이 있으면 REG_DT DESC 첫 1건(가장 최근)만 유지.
     */
    private Map<Long, Long> lookupLabelerByVideo(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<LsTaskAssignment> labelers = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsTaskAssignment.TASK_LABELER, videoIds);
        Map<Long, Long> map = new HashMap<>();
        for (LsTaskAssignment a : labelers) {
            map.putIfAbsent(a.getRawDataId(), a.getUserNo());
        }
        return map;
    }

    /**
     * 사용자 번호 집합에 대해 (userNo → userNm) 매핑을 단일 IN 쿼리로 조회.
     */
    private Map<Long, String> lookupUserNames(java.util.Collection<Long> userNos) {
        if (userNos == null || userNos.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinct = userNos.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>();
        for (MngAcctUser u : userRepository.findByUserNoIn(distinct)) {
            map.put(u.getUserNo(), u.getUserNm());
        }
        return map;
    }

    /**
     * 페이지의 영상 ID 들에 대해 (rawSn → 라벨 총개수) 매핑을 단일 IN 쿼리로 조회.
     * LS_DATA_LBL JOIN LS_DATA_SRC GROUP BY rawSn — 라벨이 없는 영상은 키가 존재하지 않아
     * 호출 측 {@code getOrDefault(id, 0L)} 로 0 폴백 처리.
     */
    private Map<Long, Long> lookupLabelCountByVideo(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : labelRepository.countLabelsByRawSnIn(videoIds)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            Long count = ((Number) row[1]).longValue();
            map.put(rawSn, count);
        }
        return map;
    }

    /**
     * 페이지의 영상 ID 들에 대해 (rawSn → [eventName, eventTypeCd]) 매핑을 단일 native 쿼리로 조회.
     * 현 단계에서는 EVNT_TYPE_CD 값을 eventName/eventTypeCd 양쪽에 동일하게 반환 (코드값 fallback).
     * 영상 메타가 없거나 EVNT_TYPE_CD 가 null 이면 키 자체를 넣지 않아 호출 측에서 null 폴백 처리.
     */
    private Map<Long, String[]> lookupEventByVideo(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String[]> map = new HashMap<>();
        for (Object[] row : videoRepository.findEventInfoByRawSns(videoIds)) {
            if (row == null || row.length < 3 || row[0] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            String eventName = row[1] != null ? row[1].toString() : null;
            String eventTypeCd = row[2] != null ? row[2].toString() : null;
            if (eventName == null && eventTypeCd == null) continue;
            map.put(rawSn, new String[]{eventName, eventTypeCd});
        }
        return map;
    }

    /**
     * 검수 단건 상세 조회 (REVIEWER) — 검수 상세 화면 진입 시.
     */
    public ReviewResponse getDetail(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        return enrichOne(stts);
    }

    /**
     * 단건 응답 enrich — list() 의 batch helper 를 {@code List.of(videoId)} 단건 인자로 재사용해
     * cctvName/workerId/workerName/labelCount 를 채운다.
     * <p>모두 readOnly 조회이므로 쓰기 트랜잭션 내부(approve/reject)에서도 호출 가능.
     * 변경된 stts 상태를 응답에 반영하려면 flush 이후에 호출할 것.
     */
    private ReviewResponse enrichOne(LsRawDataStatus stts) {
        Long videoId = stts.getRawDataId();
        if (videoId == null) {
            return ReviewResponse.from(stts);
        }
        List<Long> ids = List.of(videoId);
        String cctvName = lookupCctvNames(ids).get(videoId);
        Map<Long, Long> workerIdMap = lookupLabelerByVideo(ids);
        Long workerId = workerIdMap.get(videoId);
        String workerName = (workerId != null)
                ? lookupUserNames(List.of(workerId)).get(workerId)
                : null;
        Long labelCount = lookupLabelCountByVideo(ids).getOrDefault(videoId, 0L);
        String[] eventInfo = lookupEventByVideo(ids).get(videoId);
        String eventName = (eventInfo != null) ? eventInfo[0] : null;
        String eventTypeCd = (eventInfo != null) ? eventInfo[1] : null;
        return ReviewResponse.from(stts, cctvName, workerId, workerName, labelCount,
                eventName, eventTypeCd);
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
            // server.servlet.context-path=/api 적용 시 실제 호출 경로는 /api/v1/... 이다.
            // FE 가 imageUrl 을 그대로 absolute path 로 사용할 수 있도록 /api prefix 포함.
            String imageUrl = "/api/v1/videos/" + videoId + "/frames/" + src.getFrameNo() + "/image";
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
        LsRawDataStatus stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_PENDING);
        stts.transitionTo(LsRawDataStatus.STTS_PENDING);
        // 통합 이벤트 로그 (SCR-TASK-003): 검수 제출 이벤트 기록
        Long workerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsTaskEventLog.submit(
                stts.getRawDataId(), workerUserNo));
        log.info("[Review] submitted videoId={} actor={}", videoId, actor.sub());
        return enrichOne(stts);
    }

    /**
     * REVIEWER 가 검수 시작. PENDING → IN_REVIEW.
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse startReview(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_IN_REVIEW);
        stts.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
        log.info("[Review] startReview videoId={} actor={}", videoId, actor.sub());
        return enrichOne(stts);
    }

    /**
     * REVIEWER 승인. IN_REVIEW → APPROVED. 동시 승인 시도 시 OptimisticLockException → 409.
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse approve(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_APPROVED);
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        // 통합 이벤트 로그 (SCR-TASK-003): 승인 이벤트 기록
        Long reviewerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsTaskEventLog.approve(
                stts.getRawDataId(), reviewerUserNo));
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Review] optimistic lock conflict on approve videoId={} actor={}", videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 처리했습니다.");
        }
        log.info("[Review] approved videoId={} actor={}", videoId, actor.sub());
        eventPublisher.publishEvent(new ReviewApprovedEvent(
                stts.getRawDataId(), reviewerUserNo, java.time.Instant.now()));
        return enrichOne(stts);
    }

    /**
     * REVIEWER 반려. IN_REVIEW → REJECTED. LS_DATA_ISSUE 신규 INSERT (사유 필수).
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse reject(Long videoId, RejectRequest req, TokenClaims actor) {
        requireReviewer(actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_REJECTED);

        // 직전 반려가 있으면 계층 연결 (UP_DATA_ISSUE_SN)
        Long parentIssueSn = issueRepository.findByVideoIdOrderByRegisteredAtDesc(videoId).stream()
                .findFirst()
                .map(LsDataIssue::getDataIssueSn)
                .orElse(null);
        LsDataIssue issue = (parentIssueSn == null)
                ? LsDataIssue.create(videoId, req.reason(), actor.sub())
                : LsDataIssue.createWithParent(videoId, req.reason(), actor.sub(), parentIssueSn);
        issueRepository.save(issue);

        stts.transitionTo(LsRawDataStatus.STTS_REJECTED);
        // 통합 이벤트 로그 (SCR-TASK-003): 반려 이벤트 기록
        Long reviewerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsTaskEventLog.reject(
                stts.getRawDataId(), reviewerUserNo, req.reason()));
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Review] optimistic lock conflict on reject videoId={} actor={}", videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 처리했습니다.");
        }
        log.info("[Review] rejected videoId={} actor={} parentIssueSn={}", videoId, actor.sub(), parentIssueSn);
        return enrichOne(stts);
    }

    /**
     * VIDEO_ID(=RAW_SN) 단위 LS_RAW_DATA_STATUS 조회.
     * V34 이후 RAW_DATA_ID 는 단일 PK 이므로 단건 조회 후 미존재 시 404.
     */
    private LsRawDataStatus loadByVideoId(Long videoId) {
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
                selfNo, LsTaskAssignment.TASK_LABELER, videoId);
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
