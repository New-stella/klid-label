package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.common.util.KeypointSkeleton;
import kr.co.cudo.authoring.label.entity.LsLabel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 사용된 {@link LsLabel} 집합 → {@link NiaCategory} 리스트 순수 매퍼.
 *
 * <p>{@code LBL_TYPE_CD} 소문자 매핑: BBOX→bbox, POLYGON/SEGMENT→polygon,
 * POINT/SKELETON→keypoints, TRACK→bbox. keypoints 타입에는 COCO-17 관절명/스켈레톤
 * 엣지({@link KeypointSkeleton})를 주입한다.
 */
@Component
public class CategoryMapper {

    private static final String T_BBOX = "bbox";
    private static final String T_POLYGON = "polygon";
    private static final String T_KEYPOINTS = "keypoints";

    /** 라벨 집합 → 카테고리 리스트 (입력 순서 보존). */
    public List<NiaCategory> toCategories(Collection<LsLabel> labels) {
        List<NiaCategory> result = new ArrayList<>();
        if (labels == null) {
            return result;
        }
        for (LsLabel label : labels) {
            if (label == null) {
                continue;
            }
            result.add(toCategory(label));
        }
        return result;
    }

    private NiaCategory toCategory(LsLabel label) {
        String type = mapType(label.getLabelTypeCd());
        boolean isKeypoint = T_KEYPOINTS.equals(type);
        String id = (label.getLabelId() == null) ? null : String.valueOf(label.getLabelId());
        return new NiaCategory(
                id,
                label.getLabelNm(),
                type,
                null, // supercategory (미보유)
                isKeypoint ? KeypointSkeleton.KEYPOINT_NAMES : null,
                isKeypoint ? KeypointSkeleton.SKELETON_EDGES : null
        );
    }

    /** LS_LABEL.LBL_TYPE_CD → COCO category type 소문자. 미상 타입은 원본 소문자 그대로. */
    private static String mapType(String labelTypeCd) {
        if (labelTypeCd == null) {
            return null;
        }
        return switch (labelTypeCd.toUpperCase()) {
            case "BBOX", "TRACK" -> T_BBOX;
            case "POLYGON", "SEGMENT" -> T_POLYGON;
            case "POINT", "SKELETON" -> T_KEYPOINTS;
            default -> labelTypeCd.toLowerCase();
        };
    }
}
