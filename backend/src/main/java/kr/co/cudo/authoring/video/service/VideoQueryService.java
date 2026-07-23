package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.AutoLabelInfoProjection;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngResourceCctv;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoExportProjection;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VideoQueryService {

    private static final String DEFAULT_LABEL_COLOR = "#3B82F6";

    private final VideoRepository videoRepository;
    private final MngResourceCctvRepository cctvRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final LsTaskAssignmentRepository taskAssignmentRepository;
    private final UserRepository userRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final BatchStatusService batchStatusService;

    /**
     * 검수 상태 필터 입력 길이 상한 — 정상 enum 값(PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED)은
     * 모두 20자 이하. 상한 초과 입력은 즉시 차단해 의도 외 query 부하/탐색 방지.
     * 파라미터 바인딩으로 SQL Injection 자체는 차단되지만, 입력 검증 차원의 1차 가드.
     */
    private static final int REVIEW_STATUS_MAX_LEN = 20;

    public Page<VideoSummaryResponse> list(Pageable pageable, String dataSttsCd, String reviewStatusCd) {
        String normalizedDataStts = (dataSttsCd != null && !dataSttsCd.isBlank()) ? dataSttsCd.trim() : null;
        String normalizedReviewStts = normalizeReviewStatusCd(reviewStatusCd);

        // R1 — 영상 처리 현황은 원본 RAW 만 노출한다(파생 RAW=ORGNL_RAW_SN NOT NULL 제외).
        // 파생물은 증강 이력 화면에서만 보이며, 작업 목록(TaskBoardService)에는 여전히 포함된다(R2, 분리 유지).
        Page<LsDataRaw> page;
        if (normalizedReviewStts != null) {
            // 검수 상태 필터 지정 시 LS_RAW_DATA_STATUS INNER JOIN 쿼리 사용 (원본전용).
            // (JPQL ORDER BY regDt DESC 고정 — 본 화면은 정렬 키를 노출하지 않음)
            page = videoRepository.findOriginalsWithReviewStatus(normalizedDataStts, normalizedReviewStts, pageable);
        } else if (normalizedDataStts != null) {
            // 정렬은 컨트롤러가 allowlist 로 검증·매핑한 Pageable Sort 에 위임 (기본 regDt DESC).
            page = videoRepository.findAllByDataSttsCdAndOrgnlRawSnIsNull(normalizedDataStts, pageable);
        } else {
            page = videoRepository.findAllByOrgnlRawSnIsNull(pageable);
        }
        Map<String, String> cctvNameMap = lookupCctvNames(page.getContent());
        Map<Long, Long> frameCountMap = lookupFrameCounts(page.getContent());
        Map<Long, VideoSummaryResponse.ExportInfo> exportInfoMap = lookupExportInfos(page.getContent());
        Map<Long, LocalDateTime> reviewCompletedAtMap = lookupReviewCompletedAt(page.getContent());
        Map<Long, VideoSummaryResponse.AssignmentInfo> assignmentMap = lookupCurrentAssignments(page.getContent());
        Map<Long, VideoSummaryResponse.DeidentInfo> deidentMap = lookupDeidentInfos(page.getContent());
        return page.map(e -> VideoSummaryResponse.from(
                e,
                cctvNameMap.get(e.getVmsCctvId()),
                null,
                frameCountMap.getOrDefault(e.getRawSn(), 0L),
                exportInfoMap.get(e.getRawSn()),
                reviewCompletedAtMap.get(e.getRawSn()),
                assignmentMap.get(e.getRawSn()),
                deidentMap.get(e.getRawSn())
        ));
    }

    /**
     * 페이지 단위로 rawSn 들의 프레임 개수를 단일 GROUP BY 쿼리로 batch 조회 (N+1 회피).
     *
     * <p>{@link LsDataSrcRepository#countByRawSnsGrouped}({@code [rawSn, frameCount]} Object 배열) 결과를
     * Map 으로 변환한다. 프레임이 0건인 영상은 결과에 포함되지 않으므로 caller 가 0L 폴백 처리한다.
     */
    private Map<Long, Long> lookupFrameCounts(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : srcRepository.countByRawSnsGrouped(rawSns)) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    /**
     * 페이지 단위로 rawSn 들의 비식별 상태를 한 번의 batch 조회로 파생한다 (N+1 회피).
     *
     * <p>각 rawSn 의 최신 LS_DEIDENT_PROC_LOG 1행을 IN 절 1회로 조회({@code findLatestByDataRawSnIn})하고,
     * LS_DATA_RAW.DE_IDENT_YN 과 결합해 deidentStatus 를 파생한다. 우선순위는 {@link #deriveDeidentStatus} 참조.
     */
    private Map<Long, VideoSummaryResponse.DeidentInfo> lookupDeidentInfos(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        Map<Long, LsDeidentProcLog> latestByRaw = deidentProcLogRepository.findLatestByDataRawSnIn(rawSns).stream()
                .collect(Collectors.toMap(LsDeidentProcLog::getDataRawSn, p -> p, (a, b) -> a));
        Map<Long, VideoSummaryResponse.DeidentInfo> map = new HashMap<>();
        for (LsDataRaw raw : rows) {
            String status = deriveDeidentStatus(raw, latestByRaw.get(raw.getRawSn()));
            map.put(raw.getRawSn(), new VideoSummaryResponse.DeidentInfo(raw.getDeIdntfYn(), status));
        }
        return map;
    }

    /**
     * 영상 1건의 비식별 상태 파생 (우선순위 — 'Y' 최우선):
     * <ol>
     *   <li>deIdntfYn=='Y' → DONE (완료, 마킹 진입 가능)</li>
     *   <li>deIdntfYn=='F' 또는 최신 procLog FAILED → FAILED</li>
     *   <li>최신 procLog 진행중(REQUESTED / POLL WAITING|POLLING) → IN_PROGRESS</li>
     *   <li>그 외(procLog 없음 & deIdntfYn=='N') → NONE</li>
     * </ol>
     *
     * <p>SUCCEEDED/DOWNLOADED procLog + deIdntfYn='N' 비정상 상태도 NONE 에 해당하나, 정상
     * 트랜잭션(markDeidentified('Y')+procLog.succeed() 동일 커밋)에서는 발생하지 않는다.
     */
    private String deriveDeidentStatus(LsDataRaw raw, LsDeidentProcLog latestLog) {
        String yn = raw.getDeIdntfYn();
        if ("Y".equals(yn)) {
            return VideoSummaryResponse.DeidentStatus.DONE;
        }
        if ("F".equals(yn) || (latestLog != null && LsDeidentProcLog.FAILED.equals(latestLog.getProcSttsCd()))) {
            return VideoSummaryResponse.DeidentStatus.FAILED;
        }
        if (latestLog != null && isDeidentInProgress(latestLog)) {
            return VideoSummaryResponse.DeidentStatus.IN_PROGRESS;
        }
        return VideoSummaryResponse.DeidentStatus.NONE;
    }

    /** 최신 procLog 가 진행 중인지: PROC_STTS=REQUESTED 또는 POLL_STTS=WAITING/POLLING. */
    private boolean isDeidentInProgress(LsDeidentProcLog log) {
        if (LsDeidentProcLog.REQUESTED.equals(log.getProcSttsCd())) {
            return true;
        }
        String poll = log.getPollSttsCd();
        return LsDeidentProcLog.POLL_WAITING.equals(poll) || LsDeidentProcLog.POLL_POLLING.equals(poll);
    }

    /**
     * 페이지 단위로 rawSn 들의 현재 활성 LABELER 배정을 batch 조회 (N+1 회피).
     *
     * <p>산출 기준은 작업 목록(TaskBoardService)과 100% 동일하다:
     * TASK_TYPE_CD='LABELER' 배정을 REG_DT DESC 정렬로 IN 절 1회 조회하고, rawDataId 별 첫 매칭
     * (= 가장 최근 REG_DT, putIfAbsent)만 현재 배정으로 채택한다 — 재배정 시 최신 배정자가 반영된다.
     * 배정자 이름은 userNo 집합에 대해 1회 IN 쿼리(findByUserNoIn)로 batch lookup 한다.
     *
     * <p>LABELER 배정이 없는 영상은 Map 에서 누락 → DTO 의 배정 필드는 모두 null.
     */
    private Map<Long, VideoSummaryResponse.AssignmentInfo> lookupCurrentAssignments(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        List<LsTaskAssignment> all = taskAssignmentRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsTaskAssignment.TASK_LABELER, rawSns);
        if (all.isEmpty()) {
            return Collections.emptyMap();
        }
        // rawDataId 별 가장 최근(REG_DT DESC 첫 매칭) 1건만 현재 배정으로 채택.
        Map<Long, LsTaskAssignment> latestByRaw = new HashMap<>();
        Set<Long> userNos = new HashSet<>();
        for (LsTaskAssignment a : all) {
            if (a.getRawDataId() == null) {
                continue;
            }
            if (latestByRaw.putIfAbsent(a.getRawDataId(), a) == null && a.getUserNo() != null) {
                userNos.add(a.getUserNo());
            }
        }
        Map<Long, String> nameByUserNo = userNos.isEmpty()
                ? Collections.emptyMap()
                : userRepository.findByUserNoIn(userNos).stream()
                        .collect(Collectors.toMap(MngAcctUser::getUserNo, MngAcctUser::getUserNm));
        Map<Long, VideoSummaryResponse.AssignmentInfo> map = new HashMap<>();
        for (Map.Entry<Long, LsTaskAssignment> entry : latestByRaw.entrySet()) {
            LsTaskAssignment a = entry.getValue();
            String workerName = a.getUserNo() != null ? nameByUserNo.get(a.getUserNo()) : null;
            map.put(entry.getKey(), new VideoSummaryResponse.AssignmentInfo(
                    a.getAssignmentId(), a.getUserNo(), workerName, a.getRegDt()));
        }
        return map;
    }

    /**
     * 페이지 단위로 rawSn 들의 검수 완료 시각을 한 번에 조회 (N+1 회피).
     *
     * <p>LS_RAW_DATA_STATUS row 가 있고 dataSttsCd='APPROVED' 인 영상만 매핑한다.
     * 그 외 상태(PENDING/ASSIGNED/IN_REVIEW/REJECTED)나 row 부재 시 Map 에서 누락 →
     * DTO 의 reviewCompletedAt 은 null (정확성 — "검수 완료 일시"의 의미 보존).
     */
    private Map<Long, LocalDateTime> lookupReviewCompletedAt(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        List<LsRawDataStatus> statuses = rawDataStatusRepository.findByRawDataIdIn(rawSns);
        Map<Long, LocalDateTime> map = new HashMap<>();
        for (LsRawDataStatus s : statuses) {
            if (LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd())) {
                map.put(s.getRawDataId(), s.getUpdDt());
            }
        }
        return map;
    }

    /**
     * 검수 상태 필터 정규화 — null/blank → null, 길이 상한 초과 → 매칭되지 않을 sentinel.
     *
     * <p>길이 상한 초과 (예: SQL injection 시도 페이로드) 시 예외를 던지지 않고
     * 매칭되지 않는 sentinel 값으로 변환해 빈 페이지를 반환한다 (정보 노출 회피 + 보안 fail-secure).
     */
    private String normalizeReviewStatusCd(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > REVIEW_STATUS_MAX_LEN) {
            return "__INVALID_REVIEW_STATUS__";
        }
        return trimmed;
    }

    /**
     * 페이지 단위로 rawSn 들의 최신 export 요약을 한 번의 native 쿼리로 조회 (N+1 회피).
     * 매핑된 export 가 없으면 해당 rawSn 은 Map 에서 누락 → DTO 의 export 필드는 null.
     */
    private Map<Long, VideoSummaryResponse.ExportInfo> lookupExportInfos(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        List<VideoExportProjection> projections = videoRepository.findLatestExportsByRawSns(rawSns);
        return projections.stream().collect(Collectors.toMap(
                VideoExportProjection::getRawSn,
                p -> new VideoSummaryResponse.ExportInfo(
                        p.getExportSttsCd(),
                        p.getExportedAt(),
                        p.getErrorMessage()
                ),
                // 안전망: 동일 rawSn 중복 시 첫 값 유지 (쿼리상 보장되지만 방어적 병합).
                (a, b) -> a
        ));
    }

    public VideoDetailResponse getOne(Long rawSn) {
        LsDataRaw entity = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        String cctvName = cctvRepository.findById(entity.getVmsCctvId())
                .map(MngResourceCctv::getCctvNm)
                .orElse(null);
        long frameCount = srcRepository.countByRawSn(entity.getRawSn());
        List<VideoDetailResponse.FramePreviewDto> framePreviews = srcRepository
                .findByRawSnOrderByFrameNoAsc(entity.getRawSn())
                .stream()
                .map(src -> new VideoDetailResponse.FramePreviewDto(
                        src.getSrcSn(),
                        Math.toIntExact(src.getFrameNo()),
                        "/v1/frames/" + src.getSrcSn() + "/image"
                ))
                .toList();
        // 검수 상태(reviewSttsCd) = LS_RAW_DATA_STATUS.DATA_STTS_CD (진실원).
        // status(=배치단계 LS_DATA_RAW.DATA_STTS_CD)와 출처가 다르므로 별도 조회해 노출한다.
        // 상태 row 가 없으면 미검수로 간주(null). ApprovedRedeidentService.isReviewApproved 와 동일 조회 패턴.
        String reviewSttsCd = rawDataStatusRepository.findByRawDataIdIn(List.of(entity.getRawSn())).stream()
                .findFirst()
                .map(LsRawDataStatus::getDataSttsCd)
                .orElse(null);
        // 배치 파이프라인 단계별 진행 상태(이슈1). 최신 LS_BATCH_PROC_LOG 1행 기준으로 canonical 순서 구성.
        // 로그 없으면 빈 리스트 → FE 배지 폴백(하위호환). 단계 코드/상태만 노출(PII/경로/스택 없음).
        boolean videoCompleted = LsDataRaw.DATA_STTS_COMPLETED.equals(entity.getDataSttsCd());
        List<VideoDetailResponse.StageStatusDto> stages = batchStatusService
                .stagesFor(entity.getRawSn(), videoCompleted)
                .stream()
                .map(s -> new VideoDetailResponse.StageStatusDto(s.name(), s.status(), s.progress()))
                .toList();
        return VideoDetailResponse.from(entity, cctvName, null, frameCount, framePreviews, reviewSttsCd, stages);
    }

    /**
     * 영상별 오토라벨 결과 조회 — FE FrameLabels 매핑.
     * rawSn 영상이 없거나 라벨이 없으면 빈 objects 반환 (404 던지지 않음).
     *
     * <p>auto/manual 구분과 신뢰도는 {@code LS_DATA_LBL} 본체가 아닌 {@code LS_DATA_LBL_AI_INFO}
     * 에 저장된다(LsDataLbl 의 autoLblYn/confScore 는 {@code @Transient} 라 DB 조회 시 항상 null).
     * 따라서 라벨과 AI 메타를 단일 LEFT JOIN 쿼리(N+1 금지)로 함께 조회해, AUTO_LBL_YN='Y' 인
     * AI_INFO 가 있는 라벨은 createdBy='auto' + 실제 conf_score, 그 외는 'manual' 로 매핑한다.
     */
    public AutoLabelResultResponse getAutoLabels(Long rawSn) {
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "rawSn=" + rawSn));
        List<AutoLabelInfoProjection> labels = lblRepository.findAutoLabelInfoByRawSn(rawSn);
        List<AutoLabelResultResponse.LabelObjectDto> objects = labels.stream()
                .map(l -> {
                    boolean isAuto = LsDataLbl.AUTO_YES.equals(l.getAutoLblYn());
                    return new AutoLabelResultResponse.LabelObjectDto(
                            String.valueOf(l.getLblSn()),
                            l.getLabelNm(),
                            l.getLabelNm(),
                            DEFAULT_LABEL_COLOR,
                            l.getConfScore() == null ? null : l.getConfScore().doubleValue(),
                            isAuto ? "auto" : "manual"
                    );
                })
                .toList();
        return new AutoLabelResultResponse(rawSn, objects);
    }

    /**
     * 페이지 단위로 사용된 VMS_CCTV_ID 들을 단일 IN 쿼리(findAllById)로 batch 조회해 N+1 회피.
     *
     * <p>존재하는 CCTV 만 매핑하며, CCTV_NM 이 null 이어도 그대로 map 에 담는다 — DTO 변환 시
     * cctvName 이 null/blank 이면 vmsCctvId 로 폴백하므로 기존 동작과 동일하다.
     * MngResourceCctv 가 비어 있는 환경(local/test mock)에서는 빈 맵 반환.
     */
    private Map<String, String> lookupCctvNames(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> ids = rows.stream()
                .map(LsDataRaw::getVmsCctvId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>();
        for (MngResourceCctv c : cctvRepository.findAllById(ids)) {
            map.put(c.getVmsCctvId(), c.getCctvNm());
        }
        return map;
    }
}
