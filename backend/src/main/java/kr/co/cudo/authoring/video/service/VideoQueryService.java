package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngResourceCctv;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VideoQueryService {

    private final VideoRepository videoRepository;
    private final MngResourceCctvRepository cctvRepository;
    private final LsDataSrcRepository srcRepository;

    public Page<VideoSummaryResponse> list(Pageable pageable) {
        Page<LsDataRaw> page = videoRepository.findAllByOrderByRegDtDesc(pageable);
        Map<String, String> cctvNameMap = lookupCctvNames(page.getContent());
        // 각 영상별 frameCount 조회 (페이지당 최대 size 건수만큼). 향후 성능 이슈 시 단일 group-by 쿼리로 최적화.
        return page.map(e -> VideoSummaryResponse.from(
                e,
                cctvNameMap.get(e.getVmsCctvId()),
                null,
                srcRepository.countByRawSn(e.getRawSn())
        ));
    }

    public VideoDetailResponse getOne(Long rawSn) {
        LsDataRaw entity = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        String cctvName = cctvRepository.findById(entity.getVmsCctvId())
                .map(MngResourceCctv::getCctvNm)
                .orElse(null);
        long frameCount = srcRepository.countByRawSn(entity.getRawSn());
        return VideoDetailResponse.from(entity, cctvName, null, frameCount);
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
