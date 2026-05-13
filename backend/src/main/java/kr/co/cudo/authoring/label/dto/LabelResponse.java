package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 라벨 조회/수정 결과.
 * items[].autoLblYn 가 'Y' 면 자동, 'N' 면 사용자 입력.
 *
 * <p>siblings 는 동일 영상(rawSn=videoId)에 속한 모든 프레임의 (srcSn, frameNo) 목록.
 * FRAME_NO 오름차순 정렬. FE 의 프레임 타임라인/이전·다음 이동을 위해 함께 반환된다.
 * 다른 프레임의 라벨은 포함하지 않으며, 필요 시 별도 호출한다.
 */
public record LabelResponse(
        Long srcSn,
        Integer frameNo,
        Long videoId,
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
            List<List<Double>> points,
            String autoLblYn,
            BigDecimal confScore,
            String trackId
    ) {
        public static Item from(LsDataLbl entity, ObjectMapper objectMapper) {
            List<Point> parsed = LabelPointSerializer.fromJson(entity.getPointsJson(), objectMapper);
            List<List<Double>> nested = new ArrayList<>(parsed.size());
            for (Point p : parsed) {
                nested.add(List.of(p.x(), p.y()));
            }
            return new Item(
                    entity.getLblSn(),
                    entity.getLblTypeCd(),
                    entity.getLabel(),
                    nested,
                    entity.getAutoLblYn(),
                    entity.getConfScore(),
                    entity.getTrackId()
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
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   ObjectMapper objectMapper) {
        List<SiblingFrame> siblingDtos = new ArrayList<>(siblings.size());
        for (LsDataSrc s : siblings) {
            siblingDtos.add(SiblingFrame.from(s));
        }
        List<Item> items = entities.stream().map(e -> Item.from(e, objectMapper)).toList();
        return new LabelResponse(
                current.getSrcSn(),
                current.getFrameNo(),
                current.getRawSn(),
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
                Collections.emptyList(),
                entities.stream().map(e -> Item.from(e, objectMapper)).toList()
        );
    }
}
