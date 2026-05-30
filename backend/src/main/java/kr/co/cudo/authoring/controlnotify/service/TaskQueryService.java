package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.dto.TaskLabelsResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskMetaResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskSummaryResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 4 -- 관제서버 조회 API 비즈니스 로직.
 *
 * <p>조회 전용 서비스 — 상태 변경 없음.
 * CWE-89 방어: 모든 쿼리는 JPA 파라미터 바인딩만 사용.
 */
@Service
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class TaskQueryService {

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsDataMetaRepository metaRepository;

    /**
     * 영상별 요약: 프레임 수, 라벨 수(전체/라벨이 있는 프레임 수), 메타 수, 상태, 최종 수정일.
     */
    public TaskSummaryResponse getSummary(Long rawSn) {
        LsDataRaw raw = findRawOrThrow(rawSn);

        long totalFrames = srcRepository.countByRawSn(rawSn);

        // srcSn 목록으로 라벨 일괄 조회 (N+1 방지)
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        Set<Long> srcSns = frames.stream()
                .map(LsDataSrc::getSrcSn)
                .collect(Collectors.toSet());

        List<LsDataLbl> allLabels = srcSns.isEmpty()
                ? List.of()
                : lblRepository.findBySrcSnIn(srcSns);

        long labeledFrames = allLabels.stream()
                .map(LsDataLbl::getSrcSn)
                .distinct()
                .count();
        long totalLabels = allLabels.size();

        List<LsDataMeta> metas = metaRepository.findByRawSn(rawSn);
        long totalMeta = metas.size();

        // lastModifiedAt: updDt 우선, 없으면 regDt
        var lastModified = raw.getMdfcnDt() != null ? raw.getMdfcnDt() : raw.getRegDt();
        var lastModifiedInstant = lastModified != null
                ? lastModified.atZone(ZoneId.systemDefault()).toInstant()
                : null;

        return new TaskSummaryResponse(
                raw.getRawSn(),
                raw.getDataSttsCd(),
                totalFrames,
                labeledFrames,
                totalLabels,
                totalMeta,
                lastModifiedInstant
        );
    }

    /**
     * 영상별 라벨 목록 (선택적 프레임 필터).
     *
     * @param rawSn    영상 PK
     * @param frameIds 필터할 프레임 srcSn 목록. null 또는 빈 리스트이면 전체 프레임 반환.
     */
    public List<TaskLabelsResponse> getLabels(Long rawSn, List<Long> frameIds) {
        findRawOrThrow(rawSn);

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);

        // frameIds 필터 적용
        if (frameIds != null && !frameIds.isEmpty()) {
            Set<Long> filter = new HashSet<>(frameIds);
            frames = frames.stream()
                    .filter(f -> filter.contains(f.getSrcSn()))
                    .toList();
        }

        // srcSn 목록으로 라벨 일괄 조회 (N+1 방지)
        Set<Long> srcSns = frames.stream()
                .map(LsDataSrc::getSrcSn)
                .collect(Collectors.toSet());

        List<LsDataLbl> labels = srcSns.isEmpty()
                ? List.of()
                : lblRepository.findBySrcSnIn(srcSns);

        Map<Long, List<LsDataLbl>> bySrc = labels.stream()
                .collect(Collectors.groupingBy(LsDataLbl::getSrcSn));

        return frames.stream()
                .map(f -> toLabelsResponse(f, bySrc.getOrDefault(f.getSrcSn(), List.of())))
                .toList();
    }

    /**
     * 영상별 메타데이터.
     */
    public TaskMetaResponse getMeta(Long rawSn) {
        findRawOrThrow(rawSn);

        List<LsDataMeta> metas = metaRepository.findByRawSn(rawSn);

        List<TaskMetaResponse.MetaItem> items = metas.stream()
                .map(m -> new TaskMetaResponse.MetaItem(
                        m.getMetaSn(),
                        m.getMetaKey(),
                        m.getMetaVl()
                ))
                .toList();

        return new TaskMetaResponse(rawSn, items);
    }

    // ==================== Private Helpers ====================

    private LsDataRaw findRawOrThrow(Long rawSn) {
        return videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
    }

    /**
     * 프레임 + 라벨 목록을 응답 DTO로 변환.
     * <p>CWE-359 Privacy: 파일 경로 필드(filePath, srcBkupFilePath) 미포함 — record 구조로 강제.
     */
    private TaskLabelsResponse toLabelsResponse(LsDataSrc frame, List<LsDataLbl> labels) {
        List<TaskLabelsResponse.LabelItem> items = labels.stream()
                .map(l -> new TaskLabelsResponse.LabelItem(
                        l.getLblSn(),
                        l.getLblTypeCd(),
                        l.getLabelNm(),
                        l.getPointCn()
                ))
                .toList();

        return new TaskLabelsResponse(frame.getSrcSn(), frame.getFrameNo(), items);
    }
}
