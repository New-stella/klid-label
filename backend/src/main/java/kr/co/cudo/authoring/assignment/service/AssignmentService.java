package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrtHstry;
import kr.co.cudo.authoring.assignment.repository.LsPjtDataSttsRepository;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtHstryRepository;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AssignmentService {

    private final LsPjtUserAuthrtRepository authrtRepository;
    private final LsPjtUserAuthrtHstryRepository hstryRepository;
    private final LsPjtDataSttsRepository dataSttsRepository;
    private final UserRepository userRepository;

    @Transactional("controlTransactionManager")
    public AssignmentResponse assign(AssignmentCreateRequest req, TokenClaims actor) {
        requireReviewer(actor);
        Long actorNo = parseUserNo(actor.sub());

        if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
        }

        List<LsPjtUserAuthrt> created = new ArrayList<>();
        try {
            for (Long rawDataId : req.rawDataIds()) {
                LsPjtUserAuthrt entity = authrtRepository.save(
                        LsPjtUserAuthrt.createLabeler(req.pjtId(), rawDataId, req.workerId(), actorNo)
                );
                LsPjtDataStts stts = upsertDataStts(req.pjtId(), rawDataId);
                stts.markAssigned();
                created.add(entity);
            }
            authrtRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("[Assignment] duplicate assignment detected pjtId={} workerId={}", req.pjtId(), req.workerId());
            throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
        }
        log.info("[Assignment] created actor={} workerId={} count={}", actorNo, req.workerId(), created.size());
        return AssignmentResponse.of(created);
    }

    @Transactional("controlTransactionManager")
    public AssignmentResponse reassign(Long assignmentId, ReassignRequest req, TokenClaims actor) {
        requireReviewer(actor);
        Long actorNo = parseUserNo(actor.sub());

        LsPjtUserAuthrt prev = authrtRepository.findById(assignmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "배정을 찾을 수 없습니다."));

        if (userRepository.findByUserNo(req.newWorkerId()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
        }
        if (prev.getUserNo().equals(req.newWorkerId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "현재 배정된 작업자와 동일합니다.");
        }

        hstryRepository.save(LsPjtUserAuthrtHstry.record(prev, req.newWorkerId(), actorNo));
        prev.reassignTo(req.newWorkerId());
        try {
            authrtRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, "재배정 대상 작업자에게 이미 배정된 영상입니다.");
        }
        log.info("[Assignment] reassigned actor={} authrtSeq={} newWorker={}", actorNo, assignmentId, req.newWorkerId());
        return AssignmentResponse.single(prev);
    }

    /**
     * 단일 배정의 변경 이력 조회 — `LS_PJT_USER_AUTHRT_HSTRY` 시간순(ASC) 정렬.
     *
     * <p>각 row 의 prev/new userNo 를 한 번에 모아 {@code MNG_ACCT_USER} 를 일괄 조회하여 N+1 회피.
     * ASSIGN 이벤트는 {@link LsPjtUserAuthrt} 본체의 {@code REG_DT} 를 기준으로 합성하여 첫 행으로 포함하고,
     * 이후 HSTRY rows 를 REASSIGN 으로 추가한다.
     * 변경 사유(reason) 는 현재 스키마에 컬럼이 없으므로 null 로 반환 (V1.x — 추후 확장 시 컬럼 추가 필요).
     */
    public List<AssignmentHistoryResponse> getHistory(Long assignmentId) {
        if (assignmentId == null || assignmentId <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 배정 ID 입니다.");
        }
        LsPjtUserAuthrt authrt = authrtRepository.findById(assignmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "배정을 찾을 수 없습니다."));
        List<LsPjtUserAuthrtHstry> rows = hstryRepository.findByAuthrtSeqOrderByChgDtAsc(assignmentId);

        // 최초 배정 작업자(originalWorkerNo) 도출:
        // - HSTRY 가 비어있으면 현재 userNo 가 곧 최초 배정 작업자
        // - HSTRY 가 있으면 가장 오래된 row 의 PREV_USER_NO 가 최초 배정 작업자
        Long originalWorkerNo = rows.isEmpty()
                ? authrt.getUserNo()
                : rows.get(0).getPrevUserNo();

        Set<Long> userNos = new HashSet<>();
        if (originalWorkerNo != null) userNos.add(originalWorkerNo);
        for (LsPjtUserAuthrtHstry r : rows) {
            if (r.getPrevUserNo() != null) userNos.add(r.getPrevUserNo());
            if (r.getNewUserNo() != null) userNos.add(r.getNewUserNo());
        }
        Map<Long, String> nameByUserNo = new HashMap<>();
        for (Long no : userNos) {
            userRepository.findByUserNo(no)
                    .map(MngAcctUser::getUserNm)
                    .ifPresent(name -> nameByUserNo.put(no, name));
        }

        List<AssignmentHistoryResponse> out = new ArrayList<>(rows.size() + 1);
        // 1) ASSIGN 합성: 본체 REG_DT 를 기준으로 첫 행에 추가.
        //    hstrySn 은 HSTRY 실제 PK 와 충돌하지 않도록 음수로 부여(FE 는 key 로만 사용).
        out.add(new AssignmentHistoryResponse(
                -authrt.getAuthrtSeq(),
                "ASSIGN",
                null,
                null,
                originalWorkerNo,
                originalWorkerNo != null ? nameByUserNo.get(originalWorkerNo) : null,
                null,
                authrt.getRegDt()
        ));
        // 2) REASSIGN rows: HSTRY 본체 (CHG_DT ASC 정렬 유지)
        for (LsPjtUserAuthrtHstry r : rows) {
            out.add(new AssignmentHistoryResponse(
                    r.getHstrySeq(),
                    "REASSIGN",
                    r.getPrevUserNo(),
                    r.getPrevUserNo() != null ? nameByUserNo.get(r.getPrevUserNo()) : null,
                    r.getNewUserNo(),
                    r.getNewUserNo() != null ? nameByUserNo.get(r.getNewUserNo()) : null,
                    null,
                    r.getChgDt()
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
        return page.map(e -> AssignmentResponse.Item.from(e, reviewerByVideo.get(e.getRawDataId())));
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
