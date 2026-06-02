package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.label.entity.LsLabel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 라벨 조회/수정 결과.
 * items[].autoLblYn 가 'Y' 면 자동, 'N' 면 사용자 입력.
 *
 * <p>siblings 는 동일 영상(rawSn=videoId)에 속한 모든 프레임의 (srcSn, frameNo) 목록.
 * FRAME_NO 오름차순 정렬. FE 의 프레임 타임라인/이전·다음 이동을 위해 함께 반환된다.
 * 다른 프레임의 라벨은 포함하지 않으며, 필요 시 별도 호출한다.
 *
 * <p>lockSttsCd 는 영상(LS_DATA_RAW)의 잠금 상태 코드.
 * null/빈 문자열 = 잠금 없음, "LOCKED_FOR_REDEIDENT" = 비식별 재처리 중(라벨 저장 차단).
 * FE 는 이 값으로 라벨링 화면 진입 시점에 UI 비활성화를 결정한다 (다중 세션 일관성).
 */
public record LabelResponse(
        Long srcSn,
        Integer frameNo,
        Long videoId,
        String frameImageType,
        String lockSttsCd,
        List<SiblingFrame> siblings,
        List<Item> items
) {

    /** 동일 영상 내 형제 프레임 식별자. */
    public record SiblingFrame(Long srcSn, Integer frameNo) {
        public static SiblingFrame from(LsDataSrc src) {
            return new SiblingFrame(src.getSrcSn(), src.getFrameNo());
        }
    }

    public record Item(
            Long id,
            String lblTypeCd,
            String label,
            Long labelId,
            String labelName,
            String color,
            List<List<Double>> points,
            String autoLblYn,
            BigDecimal confScore,
            String trackId,
            String lblSrcCd
    ) {
        /**
         * Phase 2 — LS_DATA_LBL + LS_DATA_LBL_AI_INFO + LS_LABEL 결합 응답.
         * <p>{@code aiInfo == null} 이면 수동 라벨로 간주: {@code autoLblYn='N'}, {@code confScore=null}, {@code lblSrcCd=null}.
         * <p>{@code lsLabel == null} 이면 (V32 마이그 매칭 실패 등) {@code labelId/labelName/color} 모두 null.
         * <p>{@code label} 필드(LS_DATA_LBL.LABEL 텍스트)는 호환 위해 그대로 노출 — FE 는 labelName/color 우선 사용.
         */
        public static Item from(LsDataLbl entity, LsDataLblAiInfo aiInfo, LsLabel lsLabel, ObjectMapper objectMapper) {
            List<Point> parsed = LabelPointSerializer.fromJson(entity.getPointCn(), objectMapper);
            List<List<Double>> nested = new ArrayList<>(parsed.size());
            for (Point p : parsed) {
                nested.add(List.of(p.x(), p.y()));
            }
            return new Item(
                    entity.getLblSn(),
                    entity.getLblTypeCd(),
                    entity.getLabelNm(),
                    entity.getLabelId(),
                    lsLabel != null ? lsLabel.getLabelNm() : null,
                    lsLabel != null ? lsLabel.getColrVl() : null,
                    nested,
                    aiInfo != null ? aiInfo.getAutoLblYn() : LsDataLbl.AUTO_NO,
                    aiInfo != null ? aiInfo.getConfScore() : null,
                    entity.getTrackId(),
                    aiInfo != null ? aiInfo.getLblSrcCd() : null
            );
        }
    }

    /**
     * 컨텍스트(현재 프레임 + 영상 + 형제 프레임) 포함 응답 빌드.
     *
     * @param current    현재 프레임 (srcSn, frameNo, rawSn 추출)
     * @param siblings   동일 영상의 모든 프레임 (FRAME_NO ASC 정렬 권장)
     * @param entities   현재 프레임의 라벨 엔티티
     */
    /** 기본 frameImageType('DEID'), lockSttsCd=null 빌드 — 기존 호출자 호환. */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, "DEID", null, objectMapper);
    }

    /** Phase 3 — frameImageType 명시 빌드 (lockSttsCd=null). 기존 호출자 호환. */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, frameImageType, null, objectMapper);
    }

    /**
     * Phase 3 보강 — frameImageType + 영상 잠금 상태 코드 명시 빌드.
     * lockSttsCd 는 LS_DATA_RAW.LOCK_STTS_CD 그대로 전달 (null/"LOCKED_FOR_REDEIDENT").
     *
     * <p>AI Info 빈 맵으로 위임 (Phase 6 호환). 수동 라벨 응답으로 간주됨.
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, frameImageType, lockSttsCd, Map.of(), objectMapper);
    }

    /**
     * Phase 6 — LS_DATA_LBL + LS_DATA_LBL_AI_INFO 결합 응답.
     * <p>{@code aiInfoMap} 키: {@code dataLblSn}. row 없으면 수동 라벨로 간주.
     * <p>LabelService 가 한 번의 일괄 lookup ({@code findByDataLblSnIn})으로 맵을 구성하여 전달 — N+1 회피.
     * <p>Phase 2 (V32) 호환 — LS_LABEL 마스터 맵 없이 호출되는 경로 (labelId/labelName/color 모두 null).
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   Map<Long, LsDataLblAiInfo> aiInfoMap,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, frameImageType, lockSttsCd, aiInfoMap, Map.of(), objectMapper);
    }

    /**
     * Phase 2 — LS_DATA_LBL + LS_DATA_LBL_AI_INFO + LS_LABEL 결합 응답.
     * <p>{@code lsLabelMap} 키: {@code LS_LABEL.labelId}. 라벨 마스터 매칭 안 되는 엔티티는 labelName/color=null.
     * <p>LabelService 가 한 번의 일괄 lookup({@code findAllById})으로 맵을 구성하여 전달 — N+1 회피.
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   Map<Long, LsDataLblAiInfo> aiInfoMap,
                                   Map<Long, LsLabel> lsLabelMap,
                                   ObjectMapper objectMapper) {
        List<SiblingFrame> siblingDtos = new ArrayList<>(siblings.size());
        for (LsDataSrc s : siblings) {
            siblingDtos.add(SiblingFrame.from(s));
        }
        Map<Long, LsDataLblAiInfo> safeAiMap = aiInfoMap == null ? Map.of() : aiInfoMap;
        Map<Long, LsLabel> safeLabelMap = lsLabelMap == null ? Map.of() : lsLabelMap;
        List<Item> items = entities.stream()
                .map(e -> Item.from(
                        e,
                        safeAiMap.get(e.getLblSn()),
                        e.getLabelId() != null ? safeLabelMap.get(e.getLabelId()) : null,
                        objectMapper))
                .toList();
        return new LabelResponse(
                current.getSrcSn(),
                current.getFrameNo(),
                current.getRawSn(),
                frameImageType,
                lockSttsCd,
                siblingDtos,
                items
        );
    }

    /**
     * 라벨 엔티티만으로 빌드 (컨텍스트 없음 — 기존 호출자 호환).
     * 검수 조회 등 다른 프레임/영상 컨텍스트가 필요 없는 경로에서 사용.
     */
    public static LabelResponse of(List<LsDataLbl> entities, ObjectMapper objectMapper) {
        return new LabelResponse(
                null,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                entities.stream().map(e -> Item.from(e, null, null, objectMapper)).toList()
        );
    }
}
