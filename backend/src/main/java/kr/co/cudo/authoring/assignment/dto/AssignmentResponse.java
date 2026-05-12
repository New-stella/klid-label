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
            LocalDateTime assignedAt,
            // FE TaskListPage — 영상별 REVIEWER 배정자 (없으면 null)
            Long reviewerId
    ) {
        /**
         * 기본 변환 — REVIEWER 정보 없이 사용한다.
         * 서비스 레이어에서 REVIEWER 배정 lookup 후 {@link #from(LsPjtUserAuthrt, Long)} 사용 권장.
         */
        public static Item from(LsPjtUserAuthrt e) {
            return from(e, null);
        }

        /** REVIEWER 배정 lookup 결과를 함께 주입 (서비스에서 N+1 회피 후 호출). */
        public static Item from(LsPjtUserAuthrt e, Long reviewerId) {
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
                    e.getRegDt(),
                    reviewerId
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
