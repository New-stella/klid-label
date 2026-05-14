package kr.co.cudo.authoring.external.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.external.dto.DatasetResponse;
import kr.co.cudo.authoring.external.dto.FrameLabelInfo;
import kr.co.cudo.authoring.external.dto.LabelInfo;
import kr.co.cudo.authoring.external.dto.VideoInfo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 — 외부 학습데이터 API 빌더 서비스.
 *
 * <p>{@link #build(Long, boolean)} 은 영상(rawSn) 1건에 대해 다음 데이터를 조립한다.
 * <ul>
 *   <li>{@link VideoInfo} — LS_DATA_RAW 컬럼 중 시스템 식별·객관 메타만 (사용자 식별자 제외)</li>
 *   <li>메타 — 기존 {@code LS_DATA_META} 값을 조회</li>
 *   <li>라벨 — 기존 {@code LS_DATA_SRC} 프레임 + 해당 프레임의 {@code LS_DATA_LBL} 좌표 1벌</li>
 * </ul>
 *
 * <p>병합 정책: 같은 META_KEY 가 RAW 와 DEID 양쪽에 존재하면 RAW 가 우선한다 (외부 분석 시
 * 원본 기준 메타가 권위 있는 것으로 간주).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class ExportApiService {

    private static final TypeReference<List<List<Double>>> POINTS_TYPE = new TypeReference<>() {};

    private final VideoRepository videoRepository;
    private final LsDataMetaRepository metaRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final ObjectMapper objectMapper;

    public DatasetResponse build(Long rawSn, boolean merge) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + rawSn));

        VideoInfo video = VideoInfo.from(raw);

        Map<String, String> rawMeta = toMap(metaRepository.findByRawSn(rawSn));
        Map<String, String> deidMeta = Collections.emptyMap();

        List<FrameLabelInfo> frames = buildFrames(rawSn);

        log.info("[ExportApi] dataset built rawSn={}, frames={}, merge={}",
                rawSn, frames.size(), merge);

        if (merge) {
            Map<String, String> merged = mergeMeta(rawMeta, deidMeta);
            return DatasetResponse.merged(video, merged, frames);
        }
        return DatasetResponse.separated(video, rawMeta, deidMeta, frames);
    }

    /**
     * 영상의 RAW 프레임 + 각 프레임의 라벨을 frameNo 오름차순으로 조립.
     * <p>N+1 회피: 영상 전체 라벨을 단일 IN 쿼리로 조회 후 srcSn → labels 맵으로 그룹핑.
     */
    private List<FrameLabelInfo> buildFrames(Long rawSn) {
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        if (frames.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> srcSns = frames.stream().map(LsDataSrc::getSrcSn).toList();
        List<LsDataLbl> labels = lblRepository.findBySrcSnIn(srcSns);

        Map<Long, List<LabelInfo>> bySrc = new LinkedHashMap<>();
        for (LsDataLbl lbl : labels) {
            bySrc.computeIfAbsent(lbl.getSrcSn(), k -> new ArrayList<>())
                    .add(toLabelInfo(lbl));
        }
        return frames.stream()
                .map(s -> new FrameLabelInfo(
                        s.getFrameNo(),
                        s.getFilePath(),
                        bySrc.getOrDefault(s.getSrcSn(), Collections.emptyList())))
                .toList();
    }

    private LabelInfo toLabelInfo(LsDataLbl lbl) {
        return new LabelInfo(
                lbl.getLblTypeCd(),
                lbl.getLabel(),
                parsePoints(lbl.getPointsJson()),
                LsDataLbl.AUTO_YES.equals(lbl.getAutoLblYn()),
                lbl.getConfScore(),
                lbl.getTrackId()
        );
    }

    private List<List<Double>> parsePoints(String pointsJson) {
        if (pointsJson == null || pointsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(pointsJson, POINTS_TYPE);
        } catch (Exception e) {
            // 손상된 POINTS_JSON 은 외부 API 응답을 깨뜨리지 않도록 빈 배열로 안전 처리.
            log.warn("[ExportApi] points_json parse failed; returning empty array");
            return Collections.emptyList();
        }
    }

    private static Map<String, String> toMap(List<LsDataMeta> rows) {
        Map<String, String> m = new LinkedHashMap<>();
        for (LsDataMeta r : rows) {
            m.put(r.getMetaKey(), r.getMetaVal());
        }
        return m;
    }

    /**
     * 메타 병합 — RAW 우선. 같은 key 가 양쪽에 있으면 RAW 의 값을 유지.
     * 결과 Map 순서는 RAW 먼저 → DEID 단독 key 추가 (LinkedHashMap).
     */
    private static Map<String, String> mergeMeta(Map<String, String> rawMeta,
                                                 Map<String, String> deidMeta) {
        Map<String, String> merged = new LinkedHashMap<>(rawMeta);
        for (Map.Entry<String, String> e : deidMeta.entrySet()) {
            merged.putIfAbsent(e.getKey(), e.getValue());
        }
        return merged;
    }
}
