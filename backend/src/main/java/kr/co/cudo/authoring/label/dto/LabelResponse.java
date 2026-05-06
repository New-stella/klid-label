package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 라벨 조회/수정 결과.
 * items[].autoLblYn 가 'Y' 면 자동, 'N' 면 사용자 입력.
 */
public record LabelResponse(List<Item> items) {

    public record Item(
            Long id,
            String lblTypeCd,
            String label,
            List<List<Double>> points,
            String autoLblYn,
            BigDecimal confScore
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
                    entity.getConfScore()
            );
        }
    }

    public static LabelResponse of(List<LsDataLbl> entities, ObjectMapper objectMapper) {
        return new LabelResponse(entities.stream().map(e -> Item.from(e, objectMapper)).toList());
    }
}
