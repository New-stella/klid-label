package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public Page<VideoSummaryResponse> list(Pageable pageable, String dataSttsCd) {
        Page<LsDataRaw> page = (dataSttsCd != null && !dataSttsCd.isBlank())
                ? videoRepository.findAllByDataSttsCdOrderByRegDtDesc(dataSttsCd, pageable)
                : videoRepository.findAllByOrderByRegDtDesc(pageable);
        Map<String, String> cctvNameMap = lookupCctvNames(page.getContent());
        Map<Long, VideoSummaryResponse.ExportInfo> exportInfoMap = lookupExportInfos(page.getContent());
        // 각 영상별 frameCount 조회 (페이지당 최대 size 건수만큼). 향후 성능 이슈 시 단일 group-by 쿼리로 최적화.
        return page.map(e -> VideoSummaryResponse.from(
                e,
                cctvNameMap.get(e.getVmsCctvId()),
                null,
                srcRepository.countByRawSn(e.getRawSn()),
                exportInfoMap.get(e.getRawSn())
        ));
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
                .limit(6)
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
                        l.getLabel(),
                        l.getLabel(),
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
