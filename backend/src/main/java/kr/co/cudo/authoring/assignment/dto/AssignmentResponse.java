package kr.co.cudo.authoring.assignment.dto;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;

import java.time.LocalDateTime;
import java.util.List;

public record AssignmentResponse(
        List<Item> items
) {
    public record Item(
            Long authrtSeq,
            Long pjtId,
            Long workerId,
            Long rawDataId,
            String taskTypeCd,
            Long regUserNo,
            LocalDateTime regDt
    ) {
        public static Item from(LsPjtUserAuthrt e) {
            return new Item(
                    e.getAuthrtSeq(),
                    e.getPjtId(),
                    e.getUserNo(),
                    e.getRawDataId(),
                    e.getTaskTypeCd(),
                    e.getRegUserNo(),
                    e.getRegDt()
            );
        }
    }

    public static AssignmentResponse of(List<LsPjtUserAuthrt> entities) {
        return new AssignmentResponse(entities.stream().map(Item::from).toList());
    }

    public static AssignmentResponse single(LsPjtUserAuthrt e) {
        return new AssignmentResponse(List.of(Item.from(e)));
    }
}
