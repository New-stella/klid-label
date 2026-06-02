package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.util.LabelCoordinateScaler;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 해상도 변경 새 영상 DB 적재 — Phase 3.
 *
 * <p>{@link VideoResolutionService} 가 ffprobe/ffmpeg(트랜잭션 밖) 를 마친 후 호출한다.
 * 별도 빈으로 분리하여 Spring 프록시가 {@code REQUIRES_NEW} 트랜잭션 경계를 실제 적용하도록 한다
 * (self-invocation 시 트랜잭션 미적용 함정 회피).
 *
 * <p>LsDataRaw + 프레임 + 라벨(좌표 스케일) + 속성값 + 메타 INSERT 전체를 단일 트랜잭션으로 묶어
 * 라벨 루프 중 예외 시 전체 롤백한다 (HIGH-④ 부분실패 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoResolutionPersister {

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsDataLblAttrValRepository attrValRepository;
    private final LsDataMetaRepository metaRepository;
    private final LabelCoordinateScaler labelCoordinateScaler;

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public ResolutionChangeResponse persist(LsDataRaw parent, ResolutionPreset preset, String dstPath,
                                            double factor, int srcW, int srcH, int targetW, int targetH) {
        LsDataRaw newRaw = videoRepository.save(
                LsDataRaw.createFromResolution(parent, dstPath, preset.name()));

        // 프레임 일괄 복사 + srcSnMap (원본 srcSn → 신규 srcSn)
        List<LsDataSrc> parentFrames = srcRepository.findByRawSnOrderByFrameNoAsc(parent.getRawSn());
        List<LsDataSrc> newFrames = parentFrames.stream()
                .map(f -> LsDataSrc.create(newRaw.getRawSn(), f.getFrameNo(), f.getSrcFilePathNm(), f.getShtDt()))
                .toList();
        List<LsDataSrc> savedFrames = srcRepository.saveAll(newFrames);

        Map<Long, Long> srcSnMap = new HashMap<>();
        for (int i = 0; i < parentFrames.size(); i++) {
            srcSnMap.put(parentFrames.get(i).getSrcSn(), savedFrames.get(i).getSrcSn());
        }

        // 라벨 일괄 조회 → 좌표 스케일 → 복사 (원본 lblSn → 신규 lblSn 매핑 수집)
        int copiedLabels = 0;
        Map<Long, Long> lblSnMap = new HashMap<>();
        if (!srcSnMap.isEmpty()) {
            List<LsDataLbl> originLabels = lblRepository.findBySrcSnIn(srcSnMap.keySet());
            List<LsDataLbl> copies = new ArrayList<>(originLabels.size());
            for (LsDataLbl origin : originLabels) {
                String scaled = labelCoordinateScaler.scale(
                        origin.getPointCn(), origin.getLblTypeCd(), factor, targetW, targetH);
                copies.add(LsDataLbl.copyForNewSrcScaled(srcSnMap.get(origin.getSrcSn()), origin, scaled));
            }
            List<LsDataLbl> savedLabels = lblRepository.saveAll(copies);
            for (int i = 0; i < originLabels.size(); i++) {
                lblSnMap.put(originLabels.get(i).getLblSn(), savedLabels.get(i).getLblSn());
            }
            copiedLabels = savedLabels.size();
        }

        // 라벨 속성값(LS_DATA_LBL_ATTR_VAL) 복사 (HIGH-⑤) — 라벨 무결성 보장
        copyAttrValues(lblSnMap);

        // 메타 일괄 복사
        List<LsDataMeta> parentMetas = metaRepository.findByRawSn(parent.getRawSn());
        List<LsDataMeta> copiedMetas = parentMetas.stream()
                .map(m -> LsDataMeta.create(newRaw.getRawSn(), m.getMetaKey(), m.getMetaVl()))
                .toList();
        metaRepository.saveAll(copiedMetas);

        log.info("[Video][Resolution] new video created rawSn={} parentRawSn={} preset={} factor={} " +
                        "src={}x{} target={}x{} frames={} labels={} metas={}",
                newRaw.getRawSn(), parent.getRawSn(), preset.name(), factor,
                srcW, srcH, targetW, targetH, parentFrames.size(), copiedLabels, parentMetas.size());

        return new ResolutionChangeResponse(
                newRaw.getRawSn(), srcW, srcH, targetW, targetH, factor,
                parentFrames.size(), copiedLabels, parentMetas.size());
    }

    /** 원본 lblSn → 신규 lblSn 매핑으로 LS_DATA_LBL_ATTR_VAL 속성값을 일괄 복사한다. */
    private void copyAttrValues(Map<Long, Long> lblSnMap) {
        if (lblSnMap.isEmpty()) {
            return;
        }
        List<LsDataLblAttrVal> originAttrs = attrValRepository.findByLblSnIn(lblSnMap.keySet());
        List<LsDataLblAttrVal> copies = originAttrs.stream()
                .filter(a -> lblSnMap.containsKey(a.getLblSn()))
                .map(a -> LsDataLblAttrVal.create(lblSnMap.get(a.getLblSn()), a.getAttrId(), a.getValue()))
                .toList();
        if (!copies.isEmpty()) {
            attrValRepository.saveAll(copies);
        }
    }
}
