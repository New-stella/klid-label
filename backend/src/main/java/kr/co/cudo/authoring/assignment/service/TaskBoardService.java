package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SCR-TASK-001 REVIEWER 통합 작업 목록 — 처리 완료 영상을 BE 페이징으로 응답하고
 * LABELER/REVIEWER 배정을 LEFT JOIN 방식으로 enrich 한다.
 *
 * <p>보안:
 * <ul>
 *   <li>requireReviewer 이중 가드 (Controller @PreAuthorize + Service 레이어)</li>
 *   <li>미배정 영상도 노출되므로 IDOR 차단을 위해 REVIEWER 권한자만 접근 허용 (CWE-862/863)</li>
 * </ul>
 *
 * <p>N+1 회피: 페이지의 rawSn 집합에 대해 cctvName / eventInfo / labeler / reviewer / firstSrcSn /
 * status / user 이름을 각 1회 IN 쿼리로 batch lookup 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class TaskBoardService {

    private final VideoRepository videoRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    private final LsRawDataStatusRepository dataSttsRepository;
    private final LsDataSrcRepository dataSrcRepository;
    private final UserRepository userRepository;

    public Page<TaskBoardItemResponse> listBoard(String status, TokenClaims actor, Pageable pageable) {
        requireReviewer(actor);

        String effectiveStatus = (status != null && !status.isBlank()) ? status : "COMPLETED";
        Page<LsDataRaw> page = videoRepository.findAllByDataSttsCdOrderByRegDtDesc(effectiveStatus, pageable);
        List<LsDataRaw> rows = page.getContent();
        if (rows.isEmpty()) {
            return page.map(r -> toResponse(r, null, null, null, null, Collections.emptyMap(), null, 0L, null));
        }

        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();

        Map<Long, String> cctvByVideo = lookupCctvNameByVideo(rawSns);
        Map<Long, String[]> eventByVideo = lookupEventInfoByVideo(rawSns);
        Map<Long, LsTaskAssignment> labelerByVideo = lookupLatestAssignmentByVideo(rawSns, LsTaskAssignment.TASK_LABELER);
        Map<Long, LsTaskAssignment> reviewerByVideo = lookupLatestAssignmentByVideo(rawSns, LsTaskAssignment.TASK_REVIEWER);
        Map<Long, Long> firstSrcSnByVideo = lookupFirstSrcSnByVideo(rawSns);
        Map<Long, Long> frameCountByVideo = lookupFrameCountByVideo(rawSns);
        Map<Long, String> dataSttsByVideo = lookupDataSttsByVideo(rawSns);

        Set<Long> userNos = new HashSet<>();
        for (LsTaskAssignment a : labelerByVideo.values()) {
            if (a.getUserNo() != null) userNos.add(a.getUserNo());
        }
        for (LsTaskAssignment a : reviewerByVideo.values()) {
            if (a.getUserNo() != null) userNos.add(a.getUserNo());
        }
        Map<Long, String> nameByUserNo = userNos.isEmpty()
                ? Collections.emptyMap()
                : userRepository.findByUserNoIn(userNos).stream()
                        .collect(java.util.stream.Collectors.toMap(MngAcctUser::getUserNo, MngAcctUser::getUserNm));

        return page.map(r -> {
            Long rawSn = r.getRawSn();
            String cctvName = cctvByVideo.get(rawSn);
            String[] eventInfo = eventByVideo.get(rawSn);
            LsTaskAssignment labeler = labelerByVideo.get(rawSn);
            LsTaskAssignment reviewer = reviewerByVideo.get(rawSn);
            Long firstSrcSn = firstSrcSnByVideo.get(rawSn);
            String dataSttsCd = dataSttsByVideo.get(rawSn);
            long frameCount = frameCountByVideo.getOrDefault(rawSn, 0L);
            return toResponse(r, cctvName, eventInfo, labeler, reviewer, nameByUserNo, dataSttsCd, frameCount, firstSrcSn);
        });
    }

    private TaskBoardItemResponse toResponse(LsDataRaw r, String cctvName, String[] eventInfo,
                                              LsTaskAssignment labeler, LsTaskAssignment reviewer,
                                              Map<Long, String> nameByUserNo,
                                              String dataSttsCd, long frameCount, Long firstSrcSn) {
        String eventName = eventInfo != null ? eventInfo[0] : null;
        String eventTypeCd = eventInfo != null ? eventInfo[1] : null;
        Long workerId = labeler != null ? labeler.getUserNo() : null;
        String workerName = (workerId != null && nameByUserNo != null) ? nameByUserNo.get(workerId) : null;
        Long reviewerId = reviewer != null ? reviewer.getUserNo() : null;
        String reviewerName = (reviewerId != null && nameByUserNo != null) ? nameByUserNo.get(reviewerId) : null;
        String mappedStatus = mapBoardStatus(dataSttsCd, labeler != null);
        return new TaskBoardItemResponse(
                r.getRawSn(),
                resolveCctvName(cctvName, r.getVmsCctvId()),
                eventName,
                eventTypeCd,
                frameCount,
                r.getCapturedAt(),
                r.getDataSttsCd(),
                mappedStatus,
                labeler != null ? labeler.getAssignmentId() : null,
                workerId,
                workerName,
                labeler != null ? labeler.getRegDt() : null,
                firstSrcSn,
                reviewerId,
                reviewerName
        );
    }

    private static String resolveCctvName(String cctvName, String vmsCctvId) {
        if (cctvName != null && !cctvName.isBlank()) return cctvName;
        if (vmsCctvId != null && !vmsCctvId.isBlank()) return vmsCctvId;
        return null;
    }

    /**
     * LS_RAW_DATA_STATUS.DATA_STTS_CD + 배정 여부 = FE Task.status 매핑.
     * UNASSIGNED 는 task 없을 때만 사용한다. 배정이 있으면 STATUS row 없어도 PENDING 폴백.
     */
    private static String mapBoardStatus(String dataSttsCd, boolean hasLabeler) {
        if (!hasLabeler) {
            return "UNASSIGNED";
        }
        if (dataSttsCd == null) return "PENDING";
        return switch (dataSttsCd) {
            case "ASSIGNED"  -> "PENDING";
            case "PENDING"   -> "REVIEW_PENDING";
            case "IN_REVIEW" -> "REVIEW_PENDING";
            case "APPROVED"  -> "COMPLETED";
            case "REJECTED"  -> "REJECTED";
            default          -> "PENDING";
        };
    }

    private Map<Long, String> lookupCctvNameByVideo(List<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : videoRepository.findCctvNamesByRawSns(rawSns)) {
            if (row == null || row.length < 3 || row[0] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            String cctvNm = row[1] != null ? row[1].toString() : null;
            String vmsCctvId = row[2] != null ? row[2].toString() : null;
            String resolved = (cctvNm != null && !cctvNm.isBlank())
                    ? cctvNm
                    : (vmsCctvId != null && !vmsCctvId.isBlank() ? vmsCctvId : null);
            if (resolved != null) map.put(rawSn, resolved);
        }
        return map;
    }

    private Map<Long, String[]> lookupEventInfoByVideo(List<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) return Collections.emptyMap();
        Map<Long, String[]> map = new HashMap<>();
        for (Object[] row : videoRepository.findEventInfoByRawSns(rawSns)) {
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
     * TASK_TYPE_CD 별 rawDataId 에서 가장 최근 LsTaskAssignment 매핑.
     * REG_DT DESC 정렬 결과의 첫 매칭만 유지 (putIfAbsent).
     */
    private Map<Long, LsTaskAssignment> lookupLatestAssignmentByVideo(List<Long> rawSns, String taskTypeCd) {
        if (rawSns == null || rawSns.isEmpty()) return Collections.emptyMap();
        List<LsTaskAssignment> all = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(taskTypeCd, rawSns);
        Map<Long, LsTaskAssignment> map = new HashMap<>();
        for (LsTaskAssignment a : all) {
            if (a.getRawDataId() == null) continue;
            map.putIfAbsent(a.getRawDataId(), a);
        }
        return map;
    }

    private Map<Long, Long> lookupFirstSrcSnByVideo(List<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) return Collections.emptyMap();
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : dataSrcRepository.findFirstSrcSnGroupedByRawSn(rawSns)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            Long firstSrcSn = ((Number) row[1]).longValue();
            map.put(rawSn, firstSrcSn);
        }
        return map;
    }

    /**
     * 영상별 프레임 개수 batch lookup — page.map 내부 N+1 회피 (페이지당 size 회 → 1회).
     * 프레임이 0건인 영상은 결과에 없으므로 caller 가 getOrDefault(rawSn, 0L) 으로 폴백한다.
     */
    private Map<Long, Long> lookupFrameCountByVideo(List<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) return Collections.emptyMap();
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : dataSrcRepository.countByRawSnsGrouped(rawSns)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            Long count = ((Number) row[1]).longValue();
            map.put(rawSn, count);
        }
        return map;
    }

    private Map<Long, String> lookupDataSttsByVideo(List<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        for (LsRawDataStatus s : dataSttsRepository.findAllById(rawSns)) {
            if (s == null || s.getRawDataId() == null) continue;
            map.put(s.getRawDataId(), s.getDataSttsCd());
        }
        return map;
    }

    private void requireReviewer(TokenClaims actor) {
        // CWE-476: null actor 사전 가드 (인증 필터 누락 / SecurityContext 미주입 방어)
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }
}
