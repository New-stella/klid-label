package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.EventTypeOptionsResponse;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignHistory;
import kr.co.cudo.authoring.assignment.repository.AssignmentQueryRepository;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignHistoryRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AssignmentService {

    /**
     * 이벤트유형 옵션 반환 상한 — 페이징 없는 목록이라 상한이 유일한 자원 고갈 방어다(OWASP API4).
     * 값은 REVIEWER 쪽 {@code TaskBoardService.MAX_EVENT_TYPE_OPTIONS} 와 동일하게 맞춘다 —
     * 같은 셀렉트 컴포넌트가 두 경로를 다루므로 상한이 다르면 화면 동작이 경로마다 달라진다.
     */
    private static final int MAX_EVENT_TYPE_OPTIONS = 500;

    private final LsTaskAssignmentRepository authrtRepository;
    private final AssignmentQueryRepository assignmentQueryRepository;
    private final LsTaskAssignHistoryRepository hstryRepository;
    private final LsRawDataStatusRepository dataSttsRepository;
    private final LsTaskEventLogRepository taskEventLogRepository;
    private final UserRepository userRepository;
    private final LsDataSrcRepository dataSrcRepository;
    private final VideoRepository videoRepository;

    @Transactional("controlTransactionManager")
    public AssignmentResponse assign(AssignmentCreateRequest req, TokenClaims actor) {
        requireReviewer(actor);
        Long actorNo = parseUserNo(actor.sub());

        if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
        }
        if (req.reviewerId() != null && userRepository.findByUserNo(req.reviewerId()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 검수자입니다.");
        }

        rejectApprovedTargets(req.rawDataIds());

        List<LsTaskAssignment> created = new ArrayList<>();
        try {
            for (Long rawDataId : req.rawDataIds()) {
                LsTaskAssignment entity = authrtRepository.save(
                        LsTaskAssignment.createLabeler(rawDataId, req.workerId(), actorNo)
                );
                LsRawDataStatus stts = upsertDataStts(rawDataId);
                stts.markAssigned();
                // 통합 이벤트 로그 (SCR-TASK-003): 배정 이벤트 기록
                taskEventLogRepository.save(
                        LsTaskEventLog.assign(rawDataId, actorNo, req.workerId())
                );
                created.add(entity);
            }
            authrtRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
            throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
        } catch (OptimisticLockingFailureException e) {
            // H4-a — assign 은 rejectApprovedTargets 가 읽은 상태 row 를 그대로 markAssigned 하므로,
            // 그 사이 다른 트랜잭션이 approve(=@Version 증가)하면 flush 가 낙관적 잠금 실패로 롤백된다.
            // 데이터는 안전하지만 전역 핸들러에 OptimisticLockingFailureException 매핑이 없어 500 이 나갔다.
            // 재배정 경로와 동일하게 409 로 통일한다(전역 매핑은 다른 경로 동작을 바꾸므로 국소 catch 로 한정).
            log.warn("[Assignment] optimistic lock conflict on assign workerId={} actor={}",
                    req.workerId(), actorNo);
            throw new CustomException(ErrorCode.CONFLICT,
                    "다른 사용자가 먼저 해당 영상의 상태를 변경했습니다. 새로고침 후 다시 시도해주세요.");
        }

        // 옵셔널 — REVIEWER 동시 등록. worker 배정과 동일 트랜잭션 내에서 수행하되,
        // UK 충돌(이미 동일 reviewer 가 동일 영상에 등록되어 있음) 은 정상 흐름으로 간주하여 skip.
        if (req.reviewerId() != null) {
            assignReviewers(req.rawDataIds(), req.reviewerId(), actorNo);
        }

        log.info("[Assignment] created actor={} workerId={} count={} reviewerAttached={}",
                actorNo, req.workerId(), created.size(), req.reviewerId() != null);
        // 요청에 reviewerId 가 포함되면 응답 Item 에도 그대로 반영 (요청-응답 정합).
        // 단일-인자 Item.from 은 reviewerId 를 항상 null 로 채우므로, reviewerId 주입 오버로드를 사용한다.
        List<AssignmentResponse.Item> items = created.stream()
                .map(e -> AssignmentResponse.Item.from(e, req.reviewerId()))
                .toList();
        return new AssignmentResponse(items);
    }

    /**
     * 신규 배정 대상 중 <b>검수 승인(APPROVED)</b> 영상이 있으면 배정 자체를 거부한다 (D-ISSUE-01).
     *
     * <p>기존엔 {@code markAssigned()}(검증 없는 상태 setter)를 그대로 호출해 APPROVED 영상이
     * ASSIGNED 로 무검증 강등됐다. 그 결과 {@code V_COMPLETED_VIDEO} 에서 검수완료 영상이 사라지는데
     * {@code LS_LABEL_VERSION} 스냅샷과 {@code LS_DATASET_VIDEO_META} 동결분은 남아 뷰/스냅샷이
     * 불일치했다. 재배정({@link #reassign})에는 이미 동일 가드가 있으므로 같은 에러코드
     * ({@link ErrorCode#ASSIGNMENT_ALREADY_COMPLETED}, 409)를 재사용해 두 경로의 응답을 통일한다.
     *
     * <p><b>부분성공 금지 — 전체 실패 정책</b>: 복수 rawDataIds 중 1건이라도 APPROVED 면 어떤 영상도
     * 배정하지 않는다. 부분성공을 허용하면 호출자(FE)가 "어느 영상이 배정되지 않았는지" 알 수 없어
     * 모호해지고, 기존 중복 배정(UK 충돌) 경로도 이미 전체 롤백이라 정책이 일관되지 않는다.
     * 상태 조회는 단일 IN 쿼리 1회로 수행한다(N+1 회피).
     */
    private void rejectApprovedTargets(List<Long> rawDataIds) {
        if (rawDataIds == null || rawDataIds.isEmpty()) {
            return;
        }
        boolean anyApproved = dataSttsRepository.findByRawDataIdIn(rawDataIds).stream()
                .anyMatch(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()));
        if (anyApproved) {
            log.warn("[Assignment] assign rejected — approved video included count={}", rawDataIds.size());
            throw new CustomException(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED,
                    "검수 완료된 영상은 배정할 수 없습니다.");
        }
    }

    /**
     * REVIEWER 배정 — 각 rawDataId 에 대해 (RAW_DATA_ID, USER_NO=reviewerId, TASK_TYPE_CD='REVIEWER')
     * row 가 이미 존재하는지 확인 후 없을 때만 INSERT.
     * UK 충돌이 발생해도 worker 배정 결과는 보존되어야 하므로 별도 try-catch 로 격리하고
     * 충돌은 WARN 로깅 후 무시 (이미 등록된 상태이므로 결과적으로 동일).
     * 이벤트 로그는 별도 이벤트 타입 도입 전까지 기록하지 않는다 (V1.x 정책).
     */
    private void assignReviewers(List<Long> rawDataIds, Long reviewerId, Long actorNo) {
        // 페이지 단위로 기존 REVIEWER 배정을 한 번에 batch 조회 (N+1 회피)
        List<LsTaskAssignment> existing = rawDataIds.isEmpty()
                ? List.of()
                : authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                        LsTaskAssignment.TASK_REVIEWER, rawDataIds);
        Set<Long> alreadyAssignedRawIds = existing.stream()
                .filter(r -> r.getUserNo() != null && r.getUserNo().equals(reviewerId))
                .map(LsTaskAssignment::getRawDataId)
                .collect(Collectors.toSet());

        for (Long rawDataId : rawDataIds) {
            if (alreadyAssignedRawIds.contains(rawDataId)) {
                continue;
            }
            try {
                authrtRepository.save(
                        LsTaskAssignment.createReviewer(rawDataId, reviewerId, actorNo)
                );
                authrtRepository.flush();
            } catch (DataIntegrityViolationException e) {
                // 동시성 등으로 인한 UK 충돌은 worker 배정에 영향 주지 않도록 격리.
                log.warn("[Assignment] reviewer assign skipped (already exists) rawDataId={}", rawDataId);
            }
        }
    }

    @Transactional("controlTransactionManager")
    public AssignmentResponse reassign(Long assignmentId, ReassignRequest req, TokenClaims actor) {
        requireReviewer(actor);
        Long actorNo = parseUserNo(actor.sub());

        LsTaskAssignment prev = authrtRepository.findById(assignmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "배정을 찾을 수 없습니다."));

        // 비즈니스 로직 가드 (CWE-840): 검수 승인 완료된 배정은 재배정 불가.
        // FE 버튼은 이미 가려지지만 API 직접 호출/동시성으로 우회 가능하므로 서버에서 최종 차단.
        // H4-b — 상태 row 를 공유 잠금(FOR SHARE)으로 읽어 트랜잭션 종료까지 보유한다. 단순 read 면
        // "가드 통과 → 다른 tx 가 approve 커밋 → reassign 커밋" 순서로 승인된 영상이 재배정된다
        // (두 row 가 잠금을 공유하지 않고, LS_TASK_ASSIGNMENT 의 @Version 은 이 창을 닫지 못한다).
        dataSttsRepository.findByRawDataIdForShare(prev.getRawDataId()).ifPresent(stts -> {
            if (LsRawDataStatus.STTS_APPROVED.equals(stts.getDataSttsCd())) {
                throw new CustomException(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED,
                        "완료된 작업은 재배정할 수 없습니다.");
            }
        });

        if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
        }
        if (prev.getUserNo().equals(req.workerId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "현재 배정된 작업자와 동일합니다.");
        }

        // 사전 검증: 새 작업자가 이미 동일 영상에 LABELER 로 다른 row 를 갖고 있는지 확인.
        // UK(RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 충돌을 flush 시점이 아닌
        // 사전에 명확한 메시지로 차단한다. (자기 자신 row 는 제외)
        boolean alreadyAssigned = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                        LsTaskAssignment.TASK_LABELER, List.of(prev.getRawDataId()))
                .stream()
                .anyMatch(r -> r.getUserNo() != null
                        && r.getUserNo().equals(req.workerId())
                        && !r.getAssignmentId().equals(assignmentId));
        if (alreadyAssigned) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "선택한 작업자는 이미 해당 영상에 배정되어 있습니다.");
        }

        Long prevWorkerNo = prev.getUserNo();
        hstryRepository.save(LsTaskAssignHistory.record(prev, req.workerId(), actorNo));
        // 통합 이벤트 로그 (SCR-TASK-003): 재배정 이벤트 기록
        taskEventLogRepository.save(LsTaskEventLog.reassign(
                prev.getRawDataId(), actorNo, req.workerId(), prevWorkerNo));
        prev.reassignTo(req.workerId());
        try {
            // flush 로 UPDATE 를 커밋 전에 강제 실행한다. 낙관적 잠금 충돌은 flush 시점에 표면화되므로
            // 명시 flush 없이는 커밋 단계까지 미뤄져 아래 catch 를 벗어난다(D-ISSUE-02).
            authrtRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 사전 체크 후에도 동시성으로 UK 충돌이 발생할 수 있으므로 동일 메시지로 통일.
            throw new CustomException(ErrorCode.CONFLICT,
                    "선택한 작업자는 이미 해당 영상에 배정되어 있습니다.");
        } catch (OptimisticLockingFailureException e) {
            // D-ISSUE-02: 동시 재배정 직렬화. 재배정은 기존 row UPDATE 라 UK 충돌이 나지 않아
            // 위 방어가 발화하지 않는다. @Version 으로 패자를 결정적으로 거부해 이력·이벤트 로그
            // 중복 적재를 차단한다(트랜잭션 전체 롤백 → HSTRY/EVENT_LOG 도 남지 않음).
            log.warn("[Assignment] optimistic lock conflict on reassign authrtSeq={} actor={}",
                    assignmentId, actorNo);
            throw new CustomException(ErrorCode.CONFLICT,
                    "다른 사용자가 먼저 재배정했습니다. 새로고침 후 다시 시도해주세요.");
        }
        log.info("[Assignment] reassigned actor={} authrtSeq={} newWorker={}", actorNo, assignmentId, req.workerId());
        return AssignmentResponse.single(prev);
    }

    /**
     * 영상 단위 통합 이벤트 이력 조회 — SCR-TASK-003 작업 이력 화면용.
     *
     * <p>배정/재배정/검수 제출/승인/반려를 시간순으로 통합 반환한다.
     * {@code assignmentId} 로부터 RAW_DATA_ID 를 도출하여
     * {@link LsTaskEventLog} 를 OCRN_DT ASC 로 조회하고,
     * actor/subject/prev userNo 를 한 번에 모아 {@code MNG_ACCT_USER} 를 일괄 조회하여 N+1 회피.
     *
     * <p><b>IDOR 방어 (CWE-639)</b>: actor 가 WORKER 인 경우, 본인이 배정된 이력만 조회 가능하다.
     * 본인 배정이 아니면 {@code ErrorCode.FORBIDDEN} 으로 거부한다. REVIEWER 는 제한 없음.
     */
    public List<AssignmentHistoryResponse> getHistory(Long assignmentId, TokenClaims actor) {
        if (assignmentId == null || assignmentId <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 배정 ID 입니다.");
        }
        LsTaskAssignment authrt = authrtRepository.findById(assignmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "배정을 찾을 수 없습니다."));

        // IDOR 방어 — WORKER 는 본인 배정 이력만 조회 가능.
        if (actor != null && actor.role() == Role.WORKER) {
            Long selfNo = parseUserNo(actor.sub());
            if (authrt.getUserNo() == null || !authrt.getUserNo().equals(selfNo)) {
                throw new CustomException(ErrorCode.FORBIDDEN, "본인 배정 이력만 조회할 수 있습니다.");
            }
        }

        List<LsTaskEventLog> events = taskEventLogRepository
                .findByRawDataIdOrderByOcrnDtAsc(authrt.getRawDataId());

        // userNo batch lookup (N+1 회피)
        Set<Long> userNos = new HashSet<>();
        for (LsTaskEventLog e : events) {
            if (e.getActorUserNo() != null) userNos.add(e.getActorUserNo());
            if (e.getSubjectUserNo() != null) userNos.add(e.getSubjectUserNo());
            if (e.getPrevUserNo() != null) userNos.add(e.getPrevUserNo());
        }
        Map<Long, String> nameByUserNo = userNos.isEmpty()
                ? Collections.emptyMap()
                : userRepository.findByUserNoIn(userNos).stream()
                        .collect(Collectors.toMap(MngAcctUser::getUserNo, MngAcctUser::getUserNm));

        List<AssignmentHistoryResponse> out = new ArrayList<>(events.size());
        for (LsTaskEventLog e : events) {
            out.add(new AssignmentHistoryResponse(
                    e.getEventSeq(),
                    e.getEventTypeCd(),
                    e.getActorUserNo(),
                    e.getActorUserNo() != null ? nameByUserNo.get(e.getActorUserNo()) : null,
                    e.getSubjectUserNo(),
                    e.getSubjectUserNo() != null ? nameByUserNo.get(e.getSubjectUserNo()) : null,
                    e.getPrevUserNo(),
                    e.getPrevUserNo() != null ? nameByUserNo.get(e.getPrevUserNo()) : null,
                    e.getRsn(),
                    e.getOcrnDt()
            ));
        }
        return out;
    }

    /**
     * 배정 목록 조회 — 검색·필터는 <b>전체 데이터셋 기준</b>으로 쿼리 계층에서 적용된다(R1/R2).
     *
     * <p><b>IDOR 방어 (CWE-639, R6)</b>: WORKER 요청이면 요청이 보낸 {@code workerId} 를
     * <b>참조하지 않고</b> 토큰 subject 로 조회 범위를 고정한다
     * ({@link AssignmentSearchCondition#scopedToSelf}). 조건 객체는 인가 축이 채워지는 순간 필터 축을
     * 스스로 지우므로, 이후 어떤 필터가 추가돼도 두 축이 섞일 수 없다. 기존 계약대로 403 이 아니라
     * <b>무시</b>다(FE 가 REVIEWER/WORKER 공용 쿼리스트링을 그대로 보낸다).
     *
     * <p>목록 조회 자체는 쿼리 1 + count 1 이고, 화면 표시용 부가 정보(cctvName/이벤트/상태/파생 여부)는
     * 페이지 단위 batch lookup 으로 채운다(N+1 회피). '작업중' 판정 근거인 라벨 저장 이력 존재 여부는
     * 목록 쿼리의 프로젝션으로 이미 실려 오므로 <b>여기서 다시 조회하지 않는다</b> — 재조회하면 필터와
     * 표시가 서로 다른 근거를 쓰게 된다(HIGH-3).
     */
    public Page<AssignmentResponse.Item> listAssignments(AssignmentSearchCondition condition,
                                                         TokenClaims actor, Pageable pageable) {
        AssignmentSearchCondition scoped = scopeForActor(condition, actor);

        Page<AssignmentQueryRepository.AssignmentRow> page = assignmentQueryRepository.search(scoped, pageable);
        List<LsTaskAssignment> rows = page.getContent().stream()
                .map(AssignmentQueryRepository.AssignmentRow::assignment)
                .toList();

        Map<Long, Long> reviewerByVideo = lookupReviewerByVideo(rows);
        Map<Long, Long> firstSrcSnByVideo = lookupFirstSrcSnByVideo(rows);
        Map<Long, String> cctvNameByVideo = lookupCctvNameByVideo(rows);
        Map<Long, String[]> eventInfoByVideo = lookupEventInfoByVideo(rows);
        // FE Task.status 정합 — LS_RAW_DATA_STATUS.DATA_STTS_CD 를 단일 IN 쿼리로 일괄 lookup (N+1 회피).
        Map<Long, String> dataSttsByVideo = lookupDataSttsByVideo(rows);
        // R3 — 파생 영상 여부/증강 종류 파생을 위한 LS_DATA_RAW batch lookup (N+1 회피).
        Map<Long, LsDataRaw> videoByRaw = lookupVideoByRaw(rows);

        // worker + reviewer userNo 를 한 Set 에 모아 1회 batch 조회 (N+1 회피).
        Set<Long> userNos = new HashSet<>();
        for (LsTaskAssignment e : rows) {
            if (e.getUserNo() != null) userNos.add(e.getUserNo());
            Long reviewerNo = reviewerByVideo.get(e.getRawDataId());
            if (reviewerNo != null) userNos.add(reviewerNo);
        }
        Map<Long, String> nameByUserNo = new HashMap<>();
        if (!userNos.isEmpty()) {
            for (MngAcctUser u : userRepository.findByUserNoIn(userNos)) {
                nameByUserNo.put(u.getUserNo(), u.getUserNm());
            }
        }

        return page.map(row -> {
            LsTaskAssignment e = row.assignment();
            Long reviewerId = reviewerByVideo.get(e.getRawDataId());
            String workerName = e.getUserNo() != null ? nameByUserNo.get(e.getUserNo()) : null;
            String reviewerName = reviewerId != null ? nameByUserNo.get(reviewerId) : null;
            Long firstSrcSn = firstSrcSnByVideo.get(e.getRawDataId());
            String cctvName = cctvNameByVideo.get(e.getRawDataId());
            String[] eventInfo = eventInfoByVideo.get(e.getRawDataId());
            String eventName = eventInfo != null ? eventInfo[0] : null;
            String eventTypeCd = eventInfo != null ? eventInfo[1] : null;
            String dataSttsCd = dataSttsByVideo.get(e.getRawDataId());
            LsDataRaw video = videoByRaw.get(e.getRawDataId());
            boolean augmented = video != null && video.getOrgnlRawSn() != null;
            // R3 — 증강 종류는 파생 자신이 보유한 AUG_TYPE_CD 컬럼(V148/V149)이 단일 원천이다.
            //      계약 밖 값(레거시 'RESOLUTION'·미지 코드)은 노출하지 않는다(LsDataAug.isContractAugType).
            String augType = (augmented && LsDataAug.isContractAugType(video.getAugTypeCd()))
                    ? video.getAugTypeCd() : null;
            return AssignmentResponse.Item.from(
                    e, reviewerId, workerName, reviewerName, firstSrcSn, cctvName, eventName, eventTypeCd,
                    dataSttsCd, augmented, augType, row.hasSaveHistory());
        });
    }

    /**
     * 이벤트유형 셀렉트 옵션 조회 — <b>현재 페이지가 아니라 조회 가능한 배정 전체</b> 기준(R4).
     *
     * <p><b>인가는 목록과 완전히 동일</b>하다 — 같은 {@link #scopeForActor} 를 통과하므로 WORKER 는
     * 본인 배정으로 고정되고(요청 {@code workerId} 는 읽지 않는다), REVIEWER 만 작업자 필터를 쓸 수
     * 있으며 그 외 역할은 403 이다. 옵션 API 는 "그 값이 존재한다" 는 사실 자체가 정보이므로 목록보다
     * 느슨할 이유가 없다(CWE-639).
     *
     * <p>다른 필터(검색어·워크플로 상태)는 반영하고 <b>이벤트유형 축만 제외</b>한다 — 리포지토리가
     * 목록과 같은 조건 조립을 공유하므로 "옵션엔 있는데 고르면 0건" 이 생길 수 없다.
     *
     * <p><b>페이징 없음 + 개수 상한({@value #MAX_EVENT_TYPE_OPTIONS})</b> — 코드값 select-option 이라
     * 화면이 전량을 한 번에 받아야 해서 페이징을 두지 않는다(목록 전체조회 금지 규칙의 예외). 대신
     * 상한을 두어 코드 오염 등으로 카디널리티가 폭증해도 응답이 무제한으로 커지지 않게 하고
     * (OWASP API4 — 자원 고갈), 잘렸다는 사실은 {@code truncated=true} 로 알린다. 상한값과 응답 형태는
     * REVIEWER 쪽 {@code GET /v1/tasks/board/event-types} 와 동일하게 맞춰 FE 가 한 컴포넌트로 다룬다.
     */
    public EventTypeOptionsResponse listEventTypeOptions(AssignmentSearchCondition condition, TokenClaims actor) {
        AssignmentSearchCondition scoped = scopeForActor(condition, actor);

        List<String> options = assignmentQueryRepository
                .findDistinctEventTypes(scoped, MAX_EVENT_TYPE_OPTIONS + 1);
        if (options.size() > MAX_EVENT_TYPE_OPTIONS) {
            // 카디널리티 이상(코드 오염 등) — 응답을 무제한으로 키우지 않고 잘라 낸다(OWASP API4).
            log.warn("[Assignment] eventTypeOptionsTruncated limit={}", MAX_EVENT_TYPE_OPTIONS);
            return EventTypeOptionsResponse.truncated(options.subList(0, MAX_EVENT_TYPE_OPTIONS));
        }
        return EventTypeOptionsResponse.of(options);
    }

    /**
     * 조회 범위 확정 — <b>인가 판정 단일 지점</b>(목록·이벤트유형 옵션 공용).
     *
     * <p>엔드포인트마다 조건을 조립하면 한쪽만 self 강제가 빠져 IDOR 이 생긴다(CWE-639). WORKER 는
     * 요청이 보낸 {@code workerId} 를 <b>읽지 않고</b> 토큰 subject 로 고정하며(기존 계약대로 403 이
     * 아니라 무시), REVIEWER 만 작업자 필터를 유지한다. 그 외 역할은 여기서 403 으로 막는다 —
     * SecurityConfig 의 역할 게이트와 이중 방어다.
     */
    private AssignmentSearchCondition scopeForActor(AssignmentSearchCondition condition, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        AssignmentSearchCondition requested = condition != null ? condition : AssignmentSearchCondition.none();
        if (actor.role() == Role.WORKER) {
            return requested.scopedToSelf(parseUserNo(actor.sub()));
        }
        if (actor.role() == Role.REVIEWER) {
            return requested;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "조회 권한이 없습니다.");
    }

    /**
     * 페이지 단위로 영상(LS_DATA_RAW)을 단일 IN 쿼리(findAllById)로 batch 조회하여 매핑한다 (N+1 회피).
     * ORGNL_RAW_SN(파생 여부) + VMS_CLIP_ID(증강 종류 파싱) 도출용. 영상이 없으면 키 부재 → 원본 취급.
     */
    private Map<Long, LsDataRaw> lookupVideoByRaw(List<LsTaskAssignment> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsTaskAssignment::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, LsDataRaw> map = new HashMap<>();
        for (LsDataRaw v : videoRepository.findAllById(rawDataIds)) {
            if (v != null && v.getRawSn() != null) {
                map.put(v.getRawSn(), v);
            }
        }
        return map;
    }

    /**
     * 페이지 단위로 영상별 이벤트 정보 (eventName/eventTypeCd) 를 한 번에 조회하여 매핑 (N+1 회피).
     *
     * <p>현 단계는 {@link kr.co.cudo.authoring.video.repository.VideoRepository#findEventInfoByRawSns}
     * 가 LS_DATA_RAW.EVNT_TYPE_CD 값을 양쪽에 동일하게 반환한다 (VideoSummaryResponse 와 동일 정책).
     * 영상 메타가 없거나 EVNT_TYPE_CD 가 null 인 영상은 키 자체를 넣지 않아 호출 측 lookup 이
     * null 을 반환하고, FE 가 "-" 로 폴백 표시한다.
     */
    private Map<Long, String[]> lookupEventInfoByVideo(List<LsTaskAssignment> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsTaskAssignment::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String[]> map = new HashMap<>();
        for (Object[] row : videoRepository.findEventInfoByRawSns(rawDataIds)) {
            // row[0]=rawSn, row[1]=eventName, row[2]=eventTypeCd
            if (row == null || row.length < 3 || row[0] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            String eventName = row[1] != null ? row[1].toString() : null;
            String eventTypeCd = row[2] != null ? row[2].toString() : null;
            if (eventName != null || eventTypeCd != null) {
                map.put(rawSn, new String[] { eventName, eventTypeCd });
            }
        }
        return map;
    }

    /**
     * 페이지 단위로 영상별 CCTV 명을 한 번에 조회하여 매핑 (N+1 회피).
     * LS_DATA_RAW LEFT JOIN MNG_RESOURCE_CCTV — 마스터 매핑이 없으면 VMS_CCTV_ID 폴백을 사용한다.
     * 둘 다 null/blank 면 키 자체를 넣지 않아 호출 측 {@code map.get(rawSn)} 이 null 을 반환한다.
     */
    private Map<Long, String> lookupCctvNameByVideo(List<LsTaskAssignment> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsTaskAssignment::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : videoRepository.findCctvNamesByRawSns(rawDataIds)) {
            // row[0]=rawSn, row[1]=cctvNm (nullable), row[2]=vmsCctvId
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
     * 페이지 단위로 LS_RAW_DATA_STATUS.DATA_STTS_CD 를 한 번에 조회하여 매핑 (N+1 회피).
     * status row 가 없는 영상은 키 자체를 넣지 않아 {@code map.get(rawSn)} 이 null 을 반환하고,
     * {@link AssignmentResponse.Item} 에서 'PENDING' 으로 폴백 매핑된다.
     */
    private Map<Long, String> lookupDataSttsByVideo(List<LsTaskAssignment> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsTaskAssignment::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>();
        for (LsRawDataStatus s : dataSttsRepository.findAllById(rawDataIds)) {
            if (s == null || s.getRawDataId() == null) continue;
            map.put(s.getRawDataId(), s.getDataSttsCd());
        }
        return map;
    }

    /**
     * 페이지 단위로 REVIEWER 배정 (TASK_TYPE_CD='REVIEWER') 을 한 번에 조회해
     * rawDataId → reviewer userNo 매핑을 만든다 (N+1 회피).
     * 동일 영상에 여러 REVIEWER 배정이 있으면 REG_DT DESC 첫 1건만 사용.
     */
    private Map<Long, Long> lookupReviewerByVideo(List<LsTaskAssignment> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsTaskAssignment::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<LsTaskAssignment> reviewers = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsTaskAssignment.TASK_REVIEWER, rawDataIds);
        Map<Long, Long> map = new HashMap<>();
        for (LsTaskAssignment r : reviewers) {
            // REG_DT DESC 정렬되어 있으므로 첫 매핑(가장 최근)만 유지.
            map.putIfAbsent(r.getRawDataId(), r.getUserNo());
        }
        return map;
    }

    /**
     * 페이지 단위로 영상별 첫 프레임 SRC_SN 을 한 번에 조회하여 매핑한다 (N+1 회피).
     * 프레임이 아직 생성되지 않은 영상은 결과 map 에 키가 존재하지 않으므로
     * {@code map.get(rawDataId)} 는 null 을 반환한다.
     */
    private Map<Long, Long> lookupFirstSrcSnByVideo(List<LsTaskAssignment> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsTaskAssignment::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : dataSrcRepository.findFirstSrcSnGroupedByRawSn(rawDataIds)) {
            // row[0]=rawSn, row[1]=firstSrcSn (both Long via JPQL projection)
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            Long firstSrcSn = ((Number) row[1]).longValue();
            map.put(rawSn, firstSrcSn);
        }
        return map;
    }

    /**
     * LS_RAW_DATA_STATUS upsert. PK(rawDataId)가 이미 존재하면 그대로 반환,
     * 없으면 새로 생성. 동시 두 트랜잭션이 같은 PK 로 INSERT 시도해도 PK 제약으로
     * DataIntegrityViolationException 발생 → outer try-catch 가 CONFLICT 로 처리하여
     * 사용자는 재시도 가능. (낙관적 동시성 — 충돌 빈도 낮은 시나리오에 적합)
     */
    private LsRawDataStatus upsertDataStts(Long rawDataId) {
        return dataSttsRepository.findById(rawDataId)
                .orElseGet(() -> dataSttsRepository.save(LsRawDataStatus.initial(rawDataId)));
    }

    private void requireReviewer(TokenClaims actor) {
        // CWE-476: null actor (인증 토큰 누락 또는 SecurityContext 미주입) 진입 시
        // actor.role() 에서 NPE 가 발생하지 않도록 사전 가드.
        // ReviewService.requireReviewer 와 동일한 패턴으로 일관성 유지.
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
