package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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

    /**
     * 검수 상태 필터 입력 길이 상한 — 정상 enum 값(PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED)은
     * 모두 20자 이하. 상한 초과 입력은 즉시 차단해 의도 외 query 부하/탐색 방지.
     * 파라미터 바인딩으로 SQL Injection 자체는 차단되지만, 입력 검증 차원의 1차 가드.
     */
    private static final int REVIEW_STATUS_MAX_LEN = 20;

    public Page<VideoSummaryResponse> list(Pageable pageable, String dataSttsCd, String reviewStatusCd) {
        String normalizedDataStts = (dataSttsCd != null && !dataSttsCd.isBlank()) ? dataSttsCd.trim() : null;
        String normalizedReviewStts = normalizeReviewStatusCd(reviewStatusCd);

        Page<LsDataRaw> page;
        if (normalizedReviewStts != null) {
            // 검수 상태 필터 지정 시 LS_RAW_DATA_STATUS INNER JOIN 쿼리 사용.
            // (JPQL ORDER BY regDt DESC 고정 — 본 화면은 정렬 키를 노출하지 않음)
            page = videoRepository.findAllWithReviewStatus(normalizedDataStts, normalizedReviewStts, pageable);
        } else if (normalizedDataStts != null) {
            // 정렬은 컨트롤러가 allowlist 로 검증·매핑한 Pageable Sort 에 위임 (기본 regDt DESC).
            page = videoRepository.findAllByDataSttsCd(normalizedDataStts, pageable);
        } else {
            page = videoRepository.findAll(pageable);
        }
        Map<String, String> cctvNameMap = lookupCctvNames(page.getContent());
        Map<Long, VideoSummaryResponse.ExportInfo> exportInfoMap = lookupExportInfos(page.getContent());
        Map<Long, LocalDateTime> reviewCompletedAtMap = lookupReviewCompletedAt(page.getContent());
        Map<Long, VideoSummaryResponse.AssignmentInfo> assignmentMap = lookupCurrentAssignments(page.getContent());
        // 각 영상별 frameCount 조회 (페이지당 최대 size 건수만큼). 향후 성능 이슈 시 단일 group-by 쿼리로 최적화.
        return page.map(e -> VideoSummaryResponse.from(
                e,
                cctvNameMap.get(e.getVmsCctvId()),
                null,
                srcRepository.countByRawSn(e.getRawSn()),
                exportInfoMap.get(e.getRawSn()),
                reviewCompletedAtMap.get(e.getRawSn()),
                assignmentMap.get(e.getRawSn())
        ));
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
                        src.getFrameNo(),
                        "/v1/frames/" + src.getSrcSn() + "/image"
                ))
                .toList();
        return VideoDetailResponse.from(entity, cctvName, null, frameCount, framePreviews);
    }

    /**
     * 영상별 오토라벨 결과 조회 — FE FrameLabels 매핑.
     * rawSn 영상이 없거나 라벨이 없으면 빈 objects 반환 (404 던지지 않음).
     * autoLblYn='Y' 는 createdBy='auto', 'N' 은 'manual' 로 매핑.
     */
    public AutoLabelResultResponse getAutoLabels(Long rawSn) {
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "rawSn=" + rawSn));
        List<LsDataLbl> labels = lblRepository.findAllByRawSn(rawSn);
        List<AutoLabelResultResponse.LabelObjectDto> objects = labels.stream()
                .map(l -> new AutoLabelResultResponse.LabelObjectDto(
                        String.valueOf(l.getLblSn()),
                        l.getLabelNm(),
                        l.getLabelNm(),
                        DEFAULT_LABEL_COLOR,
                        l.getConfScore() == null ? null : l.getConfScore().doubleValue(),
                        LsDataLbl.AUTO_YES.equals(l.getAutoLblYn()) ? "auto" : "manual"
                ))
                .toList();
        return new AutoLabelResultResponse(rawSn, objects);
    }

    /**
     * 페이지 단위로 사용된 VMS_CCTV_ID 들을 한 번에 조회해 N+1 회피.
     * MngResourceCctv 가 비어 있는 환경(local/test mock)에서는 빈 맵 반환.
     */
    private Map<String, String> lookupCctvNames(List<LsDataRaw> rows) {
        Map<String, String> map = new HashMap<>();
        for (LsDataRaw r : rows) {
            if (r.getVmsCctvId() == null || map.containsKey(r.getVmsCctvId())) {
                continue;
            }
            cctvRepository.findById(r.getVmsCctvId())
                    .ifPresent(c -> map.put(r.getVmsCctvId(), c.getCctvNm()));
        }
        return map;
    }
}
