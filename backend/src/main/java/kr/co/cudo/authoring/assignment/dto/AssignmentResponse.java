package kr.co.cudo.authoring.assignment.dto;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;

import java.time.LocalDateTime;
import java.util.List;

public record AssignmentResponse(
        List<Item> items
) {
    public record Item(
            Long id,
            Long authrtSeq,
            Long pjtId,
            Long workerId,
            Long videoId,
            String videoTitle,
            String workerName,
            String taskType,
            Long rawDataId,
            String taskTypeCd,
            Long regUserNo,
            LocalDateTime regDt,
            LocalDateTime assignedAt
    ) {
        public static Item from(LsPjtUserAuthrt e) {
            return new Item(
                    e.getAuthrtSeq(),
                    e.getAuthrtSeq(),
                    e.getPjtId(),
                    e.getUserNo(),
                    e.getRawDataId(),
                    e.getRawDataId() != null ? "video #" + e.getRawDataId() : null,
                    e.getUserNo() != null ? "user #" + e.getUserNo() : "",
                    e.getTaskTypeCd(),
                    e.getRawDataId(),
                    e.getTaskTypeCd(),
                    e.getRegUserNo(),
                    e.getRegDt(),
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
