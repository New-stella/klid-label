package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;
import kr.co.cudo.authoring.assignment.dto.EventTypeOptionsResponse;
import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSummaryResponse;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.TaskBoardQueryRepository;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
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
 * SCR-TASK-001 REVIEWER 통합 작업 목록 — 영상을 BE 페이징(+서버 필터)으로 응답하고
 * LABELER/REVIEWER 배정을 LEFT JOIN 방식으로 enrich 한다.
 *
 * <p>검색·필터·정렬은 {@link TaskBoardQueryRepository} 가 단일 조건으로 처리하고(목록/count 동일 조건),
 * 본 서비스는 그 결과 페이지를 화면 표시용으로 enrich 하는 책임만 갖는다.
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

    /** 이벤트유형 옵션 반환 상한 — 셀렉트박스가 감당할 수 있는 규모를 넘으면 잘라 내고 WARN(OWASP API4). */
    private static final int MAX_EVENT_TYPE_OPTIONS = 500;

    private final VideoRepository videoRepository;
    private final TaskBoardQueryRepository taskBoardQueryRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    private final LsRawDataStatusRepository dataSttsRepository;
    private final LsDataSrcRepository dataSrcRepository;
    private final UserRepository userRepository;

    /**
     * 작업목록 조회 — 배치 상태/워크플로 상태/검색어/이벤트유형/작업자 필터를 서버에서 처리한다.
     *
     * <p>정렬은 시간축 단일(기본 {@code REG_DT DESC} + {@code RAW_SN DESC} tie-break)이며 상태
     * 우선순위 정렬은 적용하지 않는다(R1). 우선순위는 {@code workStatus} 필터로 표현한다.
     * 신규 필터가 하나도 없으면 조건이 걸리지 않아 변경 전과 동일한 결과 집합을 반환한다(R8).
     */
    public Page<TaskBoardItemResponse> listBoard(TaskBoardSearchCondition condition, TokenClaims actor,
                                                 Pageable pageable) {
        requireReviewer(actor);

        TaskBoardSearchCondition effective = (condition != null) ? condition : TaskBoardSearchCondition.defaults();
        Page<LsDataRaw> page = taskBoardQueryRepository.search(effective, pageable);
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
                        .collect(java.util.stream.Collectors.toMap(LsAcntUser::getUserNo, LsAcntUser::getUserNm));

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

    /**
     * 작업목록 KPI 카드 집계 — 현재 페이지가 아니라 <b>필터 결과 전체</b>를 기준으로 센다.
     *
     * <p>필터는 {@link #listBoard} 와 동일하되 {@code workStatus} 만 제외한다 — KPI 카드 자체가
     * workStatus 선택지이므로 이미 좁혀진 집합 위에서 5종을 세면 1개 카드만 non-zero 가 된다.
     * {@code status=UNASSIGNED}(가상 status)가 걸린 경우에는 목록과 동일하게 <b>배치 상태 무관 ·
     * LABELER 미배정 전체</b>가 기준이 되므로 결과적으로 미배정 카드만 값을 갖는다.
     *
     * <p>목록과 별도 요청이라 두 호출 사이의 배정/검수 변경으로 미세하게 어긋날 수 있으며, 반환값은
     * <b>조회 시점 스냅샷</b>이다(대시보드성 KPI 라 강한 정합성은 요구하지 않는다).
     */
    public TaskBoardSummaryResponse summarizeBoard(TaskBoardSearchCondition condition, TokenClaims actor) {
        requireReviewer(actor);

        TaskBoardSearchCondition effective = (condition != null) ? condition : TaskBoardSearchCondition.defaults();
        return TaskBoardSummaryResponse.of(taskBoardQueryRepository.countByWorkStatus(effective));
    }

    /**
     * 이벤트유형 셀렉트 옵션 — 배치 상태 축({@code status})만 반영한 distinct 코드 목록(오름차순).
     *
     * <p>이벤트 마스터 테이블이 없어 코드값이 곧 표시명이다. 검색어/작업자/이벤트유형/워크플로 상태
     * 필터는 <b>반영하지 않는다</b> — 사용자가 필터를 건 뒤 옵션이 사라지면 되돌아갈 수 없기 때문이다.
     *
     * <p>상한({@value #MAX_EVENT_TYPE_OPTIONS}) 초과 시 잘라 내되 <b>그 사실을 응답에 담는다</b>
     * ({@link EventTypeOptionsResponse#truncated()}). 서버 WARN 로그만 남기고 배열만 반환하면 절단이
     * 사용자에게 보이지 않아, 잘린 코드의 영상이 "존재하지 않는다"고 오인된다.
     */
    public EventTypeOptionsResponse listEventTypeOptions(TaskBoardSearchCondition condition, TokenClaims actor) {
        requireReviewer(actor);

        TaskBoardSearchCondition effective = (condition != null) ? condition : TaskBoardSearchCondition.defaults();
        List<String> options = taskBoardQueryRepository
                .findDistinctEventTypes(effective, MAX_EVENT_TYPE_OPTIONS + 1);
        if (options.size() > MAX_EVENT_TYPE_OPTIONS) {
            // 카디널리티 이상(코드 오염 등) — 응답을 무제한으로 키우지 않고 잘라 낸다(OWASP API4).
            log.warn("[TaskBoard] eventTypeOptionsTruncated limit={}, status={}",
                    MAX_EVENT_TYPE_OPTIONS, effective.status());
            return EventTypeOptionsResponse.truncated(options.subList(0, MAX_EVENT_TYPE_OPTIONS));
        }
        return EventTypeOptionsResponse.of(options);
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
        // R3 — 파생 영상 여부(ORGNL_RAW_SN != null) + 증강 종류(AUG_TYPE_CD 컬럼, V148/V149).
        //      원본이면 augmented=false, augType=null. 계약 밖 값(레거시 'RESOLUTION'·미지 코드)도 노출하지 않는다.
        boolean augmented = r.getOrgnlRawSn() != null;
        String augType = (augmented && LsDataAug.isContractAugType(r.getAugTypeCd()))
                ? r.getAugTypeCd() : null;
        return new TaskBoardItemResponse(
                r.getRawSn(),
                resolveCctvName(cctvName, r.getVmsCctvId()),
                eventName,
                eventTypeCd,
                frameCount,
                r.getShtDt(),
                r.getDataSttsCd(),
                mappedStatus,
                labeler != null ? labeler.getAssignmentId() : null,
                workerId,
                workerName,
                labeler != null ? labeler.getRegDt() : null,
                firstSrcSn,
                reviewerId,
                reviewerName,
                augmented,
                augType
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
     *
     * <p>매핑 규칙은 {@link BoardWorkStatus} 가 단일 원천으로 소유한다 — 같은 enum 이 서버 필터
     * ({@code workStatus}) 의 WHERE 조건도 생성하므로 표시 상태와 필터 결과가 구조적으로 일치한다(HIGH-3).
     */
    private static String mapBoardStatus(String dataSttsCd, boolean hasLabeler) {
        return BoardWorkStatus.of(dataSttsCd, hasLabeler).name();
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
     *
     * <p>"가장 최근" = {@code REG_DT DESC} → {@code ASSIGNMENT_ID DESC}. REG_DT 가 동일한 배정이
     * 여러 건일 때 DB 반환 순서에 의존하면(구 {@code putIfAbsent}) 표시되는 작업자가 비결정적이 되어,
     * 같은 기준(REG_DT, ASSIGNMENT_ID)으로 최신 1건을 지목하는 서버 <b>작업자 필터</b>
     * ({@code TaskBoardQueryRepository.latestLabelerMatches}) 와 결과가 어긋날 수 있다.
     * 여기서도 동일 tie-break 를 적용해 필터 ↔ 표시를 일치시킨다.
     */
    private Map<Long, LsTaskAssignment> lookupLatestAssignmentByVideo(List<Long> rawSns, String taskTypeCd) {
        if (rawSns == null || rawSns.isEmpty()) return Collections.emptyMap();
        List<LsTaskAssignment> all = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(taskTypeCd, rawSns);
        Map<Long, LsTaskAssignment> map = new HashMap<>();
        for (LsTaskAssignment a : all) {
            if (a.getRawDataId() == null) continue;
            map.merge(a.getRawDataId(), a, TaskBoardService::laterAssignment);
        }
        return map;
    }

    /** (REG_DT, ASSIGNMENT_ID) 가 더 큰 배정을 최신으로 본다. */
    private static LsTaskAssignment laterAssignment(LsTaskAssignment current, LsTaskAssignment candidate) {
        if (current.getRegDt() == null) return candidate;
        if (candidate.getRegDt() == null) return current;
        int byRegDt = candidate.getRegDt().compareTo(current.getRegDt());
        if (byRegDt != 0) return byRegDt > 0 ? candidate : current;
        Long currentId = current.getAssignmentId();
        Long candidateId = candidate.getAssignmentId();
        if (currentId == null || candidateId == null) return current;
        return candidateId > currentId ? candidate : current;
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
