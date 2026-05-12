package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.assignment.entity.LsPjtTaskEventLog;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrtHstry;
import kr.co.cudo.authoring.assignment.repository.LsPjtDataSttsRepository;
import kr.co.cudo.authoring.assignment.repository.LsPjtTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtHstryRepository;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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

    private final LsPjtUserAuthrtRepository authrtRepository;
    private final LsPjtUserAuthrtHstryRepository hstryRepository;
    private final LsPjtDataSttsRepository dataSttsRepository;
    private final LsPjtTaskEventLogRepository taskEventLogRepository;
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

        List<LsPjtUserAuthrt> created = new ArrayList<>();
        try {
            for (Long rawDataId : req.rawDataIds()) {
                LsPjtUserAuthrt entity = authrtRepository.save(
                        LsPjtUserAuthrt.createLabeler(req.pjtId(), rawDataId, req.workerId(), actorNo)
                );
                LsPjtDataStts stts = upsertDataStts(req.pjtId(), rawDataId);
                stts.markAssigned();
                // 통합 이벤트 로그 (SCR-TASK-003): 배정 이벤트 기록
                taskEventLogRepository.save(
                        LsPjtTaskEventLog.assign(req.pjtId(), rawDataId, actorNo, req.workerId())
                );
                created.add(entity);
            }
            authrtRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("[Assignment] duplicate assignment detected pjtId={} workerId={}", req.pjtId(), req.workerId());
            throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
        }

        // 옵셔널 — REVIEWER 동시 등록. worker 배정과 동일 트랜잭션 내에서 수행하되,
        // UK 충돌(이미 동일 reviewer 가 동일 영상에 등록되어 있음) 은 정상 흐름으로 간주하여 skip.
        if (req.reviewerId() != null) {
            assignReviewers(req.pjtId(), req.rawDataIds(), req.reviewerId(), actorNo);
        }

        log.info("[Assignment] created actor={} workerId={} count={} reviewerAttached={}",
                actorNo, req.workerId(), created.size(), req.reviewerId() != null);
        return AssignmentResponse.of(created);
    }

    /**
     * REVIEWER 배정 — 각 rawDataId 에 대해 (PJT_ID, RAW_DATA_ID, USER_NO=reviewerId, TASK_TYPE_CD='REVIEWER')
     * row 가 이미 존재하는지 확인 후 없을 때만 INSERT.
     * UK 충돌이 발생해도 worker 배정 결과는 보존되어야 하므로 별도 try-catch 로 격리하고
     * 충돌은 WARN 로깅 후 무시 (이미 등록된 상태이므로 결과적으로 동일).
     * 이벤트 로그는 별도 이벤트 타입 도입 전까지 기록하지 않는다 (V1.x 정책).
     */
    private void assignReviewers(Long pjtId, List<Long> rawDataIds, Long reviewerId, Long actorNo) {
        // 페이지 단위로 기존 REVIEWER 배정을 한 번에 batch 조회 (N+1 회피)
        List<LsPjtUserAuthrt> existing = rawDataIds.isEmpty()
                ? List.of()
                : authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                        LsPjtUserAuthrt.TASK_REVIEWER, rawDataIds);
        Set<Long> alreadyAssignedRawIds = existing.stream()
                .filter(r -> r.getUserNo() != null && r.getUserNo().equals(reviewerId))
                .map(LsPjtUserAuthrt::getRawDataId)
                .collect(Collectors.toSet());

        for (Long rawDataId : rawDataIds) {
            if (alreadyAssignedRawIds.contains(rawDataId)) {
                continue;
            }
            try {
                authrtRepository.save(
                        LsPjtUserAuthrt.createReviewer(pjtId, rawDataId, reviewerId, actorNo)
                );
                authrtRepository.flush();
            } catch (DataIntegrityViolationException e) {
                // 동시성 등으로 인한 UK 충돌은 worker 배정에 영향 주지 않도록 격리.
                log.warn("[Assignment] reviewer assign skipped (already exists) pjtId={} rawDataId={}",
                        pjtId, rawDataId);
            }
        }
    }

    @Transactional("controlTransactionManager")
    public AssignmentResponse reassign(Long assignmentId, ReassignRequest req, TokenClaims actor) {
        requireReviewer(actor);
        Long actorNo = parseUserNo(actor.sub());

        LsPjtUserAuthrt prev = authrtRepository.findById(assignmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "배정을 찾을 수 없습니다."));

        if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
        }
        if (prev.getUserNo().equals(req.workerId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "현재 배정된 작업자와 동일합니다.");
        }

        // 사전 검증: 새 작업자가 이미 동일 영상에 LABELER 로 다른 row 를 갖고 있는지 확인.
        // UK(PJT_ID, RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 충돌을 flush 시점이 아닌
        // 사전에 명확한 메시지로 차단한다. (자기 자신 row 는 제외)
        boolean alreadyAssigned = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                        LsPjtUserAuthrt.TASK_LABELER, List.of(prev.getRawDataId()))
                .stream()
                .anyMatch(r -> r.getUserNo() != null
                        && r.getUserNo().equals(req.workerId())
                        && !r.getAuthrtSeq().equals(assignmentId));
        if (alreadyAssigned) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "선택한 작업자는 이미 해당 영상에 배정되어 있습니다.");
        }

        Long prevWorkerNo = prev.getUserNo();
        hstryRepository.save(LsPjtUserAuthrtHstry.record(prev, req.workerId(), actorNo));
        // 통합 이벤트 로그 (SCR-TASK-003): 재배정 이벤트 기록
        taskEventLogRepository.save(LsPjtTaskEventLog.reassign(
                prev.getPjtId(), prev.getRawDataId(), actorNo, req.workerId(), prevWorkerNo));
        prev.reassignTo(req.workerId());
        try {
            authrtRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 사전 체크 후에도 동시성으로 UK 충돌이 발생할 수 있으므로 동일 메시지로 통일.
            throw new CustomException(ErrorCode.CONFLICT,
                    "선택한 작업자는 이미 해당 영상에 배정되어 있습니다.");
        }
        log.info("[Assignment] reassigned actor={} authrtSeq={} newWorker={}", actorNo, assignmentId, req.workerId());
        return AssignmentResponse.single(prev);
    }

    /**
     * 영상 단위 통합 이벤트 이력 조회 — SCR-TASK-003 작업 이력 화면용.
     *
     * <p>배정/재배정/검수 제출/승인/반려를 시간순으로 통합 반환한다.
     * {@code assignmentId} 로부터 (PJT_ID, RAW_DATA_ID) 를 도출하여
     * {@link LsPjtTaskEventLog} 를 OCCURRED_AT ASC 로 조회하고,
     * actor/subject/prev userNo 를 한 번에 모아 {@code MNG_ACCT_USER} 를 일괄 조회하여 N+1 회피.
     *
     * <p><b>IDOR 방어 (CWE-639)</b>: actor 가 WORKER 인 경우, 본인이 배정된 이력만 조회 가능하다.
     * 본인 배정이 아니면 {@code ErrorCode.FORBIDDEN} 으로 거부한다. REVIEWER 는 제한 없음.
     */
    public List<AssignmentHistoryResponse> getHistory(Long assignmentId, TokenClaims actor) {
        if (assignmentId == null || assignmentId <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 배정 ID 입니다.");
        }
        LsPjtUserAuthrt authrt = authrtRepository.findById(assignmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "배정을 찾을 수 없습니다."));

        // IDOR 방어 — WORKER 는 본인 배정 이력만 조회 가능.
        if (actor != null && actor.role() == Role.WORKER) {
            Long selfNo = parseUserNo(actor.sub());
            if (authrt.getUserNo() == null || !authrt.getUserNo().equals(selfNo)) {
                throw new CustomException(ErrorCode.FORBIDDEN, "본인 배정 이력만 조회할 수 있습니다.");
            }
        }

        List<LsPjtTaskEventLog> events = taskEventLogRepository
                .findByPjtIdAndRawDataIdOrderByOccurredAtAsc(authrt.getPjtId(), authrt.getRawDataId());

        // userNo batch lookup (N+1 회피)
        Set<Long> userNos = new HashSet<>();
        for (LsPjtTaskEventLog e : events) {
            if (e.getActorUserNo() != null) userNos.add(e.getActorUserNo());
            if (e.getSubjectUserNo() != null) userNos.add(e.getSubjectUserNo());
            if (e.getPrevUserNo() != null) userNos.add(e.getPrevUserNo());
        }
        Map<Long, String> nameByUserNo = userNos.isEmpty()
                ? Collections.emptyMap()
                : userRepository.findByUserNoIn(userNos).stream()
                        .collect(Collectors.toMap(MngAcctUser::getUserNo, MngAcctUser::getUserNm));

        List<AssignmentHistoryResponse> out = new ArrayList<>(events.size());
        for (LsPjtTaskEventLog e : events) {
            out.add(new AssignmentHistoryResponse(
                    e.getEventSeq(),
                    e.getEventTypeCd(),
                    e.getActorUserNo(),
                    e.getActorUserNo() != null ? nameByUserNo.get(e.getActorUserNo()) : null,
                    e.getSubjectUserNo(),
                    e.getSubjectUserNo() != null ? nameByUserNo.get(e.getSubjectUserNo()) : null,
                    e.getPrevUserNo(),
                    e.getPrevUserNo() != null ? nameByUserNo.get(e.getPrevUserNo()) : null,
                    e.getReason(),
                    e.getOccurredAt()
            ));
        }
        return out;
    }

    public Page<AssignmentResponse.Item> listAssignments(Long workerIdParam, TokenClaims actor, Pageable pageable) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        Page<LsPjtUserAuthrt> page;
        if (actor.role() == Role.WORKER) {
            // IDOR 방어: WORKER 는 본인 배정만 조회 가능. workerId 파라미터는 무시하거나 본인 sub 강제.
            Long selfNo = parseUserNo(actor.sub());
            page = authrtRepository.findByUserNoAndTaskTypeCd(selfNo, LsPjtUserAuthrt.TASK_LABELER, pageable);
        } else if (actor.role() == Role.REVIEWER) {
            if (workerIdParam != null) {
                page = authrtRepository.findByUserNoAndTaskTypeCd(workerIdParam, LsPjtUserAuthrt.TASK_LABELER, pageable);
            } else {
                page = authrtRepository.findByTaskTypeCd(LsPjtUserAuthrt.TASK_LABELER, pageable);
            }
        } else {
            throw new CustomException(ErrorCode.FORBIDDEN, "조회 권한이 없습니다.");
        }
        Map<Long, Long> reviewerByVideo = lookupReviewerByVideo(page.getContent());
        Map<Long, Long> firstSrcSnByVideo = lookupFirstSrcSnByVideo(page.getContent());
        Map<Long, String> cctvNameByVideo = lookupCctvNameByVideo(page.getContent());

        // worker + reviewer userNo 를 한 Set 에 모아 1회 batch 조회 (N+1 회피).
        Set<Long> userNos = new HashSet<>();
        for (LsPjtUserAuthrt e : page.getContent()) {
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

        return page.map(e -> {
            Long reviewerId = reviewerByVideo.get(e.getRawDataId());
            String workerName = e.getUserNo() != null ? nameByUserNo.get(e.getUserNo()) : null;
            String reviewerName = reviewerId != null ? nameByUserNo.get(reviewerId) : null;
            Long firstSrcSn = firstSrcSnByVideo.get(e.getRawDataId());
            String cctvName = cctvNameByVideo.get(e.getRawDataId());
            return AssignmentResponse.Item.from(e, reviewerId, workerName, reviewerName, firstSrcSn, cctvName);
        });
    }

    /**
     * 페이지 단위로 영상별 CCTV 명을 한 번에 조회하여 매핑 (N+1 회피).
     * LS_DATA_RAW LEFT JOIN MNG_RESOURCE_CCTV — 마스터 매핑이 없으면 VMS_CCTV_ID 폴백을 사용한다.
     * 둘 다 null/blank 면 키 자체를 넣지 않아 호출 측 {@code map.get(rawSn)} 이 null 을 반환한다.
     */
    private Map<Long, String> lookupCctvNameByVideo(List<LsPjtUserAuthrt> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsPjtUserAuthrt::getRawDataId)
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
     * 페이지 단위로 REVIEWER 배정 (TASK_TYPE_CD='REVIEWER') 을 한 번에 조회해
     * rawDataId → reviewer userNo 매핑을 만든다 (N+1 회피).
     * 동일 영상에 여러 REVIEWER 배정이 있으면 REG_DT DESC 첫 1건만 사용.
     */
    private Map<Long, Long> lookupReviewerByVideo(List<LsPjtUserAuthrt> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsPjtUserAuthrt::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<LsPjtUserAuthrt> reviewers = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsPjtUserAuthrt.TASK_REVIEWER, rawDataIds);
        Map<Long, Long> map = new HashMap<>();
        for (LsPjtUserAuthrt r : reviewers) {
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
    private Map<Long, Long> lookupFirstSrcSnByVideo(List<LsPjtUserAuthrt> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawDataIds = rows.stream()
                .map(LsPjtUserAuthrt::getRawDataId)
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
     * LS_PJT_DATA_STTS upsert. PK(pjtId, rawDataId)가 이미 존재하면 그대로 반환,
     * 없으면 새로 생성. 동시 두 트랜잭션이 같은 PK 로 INSERT 시도해도 PK 제약으로
     * DataIntegrityViolationException 발생 → outer try-catch 가 CONFLICT 로 처리하여
     * 사용자는 재시도 가능. (낙관적 동시성 — 충돌 빈도 낮은 시나리오에 적합)
     */
    private LsPjtDataStts upsertDataStts(Long pjtId, Long rawDataId) {
        LsPjtDataStts.Pk pk = LsPjtDataStts.Pk.of(pjtId, rawDataId);
        return dataSttsRepository.findById(pk)
                .orElseGet(() -> dataSttsRepository.save(LsPjtDataStts.initial(pjtId, rawDataId)));
    }

    private void requireReviewer(TokenClaims actor) {
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
