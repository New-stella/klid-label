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
            Long reviewerId,
            // FE TaskListPage — 영상별 REVIEWER 실제 이름 (없으면 null)
            String reviewerName,
            // FE LabelingPage — 해당 영상의 첫 프레임 SRC_SN. 프레임 미생성 시 null.
            // WORKER 가 "작업" 버튼 클릭 시 /label/{firstSrcSn} 으로 navigate 하는 데 사용.
            Long firstSrcSn
    ) {
        /**
         * 기본 변환 — REVIEWER 정보 없이 사용한다.
         * 서비스 레이어에서 REVIEWER 배정 lookup 후 {@link #from(LsPjtUserAuthrt, Long)} 사용 권장.
         */
        public static Item from(LsPjtUserAuthrt e) {
            return from(e, null, null, null, null);
        }

        /** REVIEWER 배정 lookup 결과를 함께 주입 (서비스에서 N+1 회피 후 호출). */
        public static Item from(LsPjtUserAuthrt e, Long reviewerId) {
            return from(e, reviewerId, null, null, null);
        }

        /**
         * Service 레이어에서 user 이름 일괄 조회 후 호출 (N+1 회피).
         * workerName/reviewerName 이 null 이면 폴백("user #N") 적용.
         */
        public static Item from(LsPjtUserAuthrt e, Long reviewerId,
                                String workerName, String reviewerName) {
            return from(e, reviewerId, workerName, reviewerName, null);
        }

        /**
         * 전체 인자 변환 — 영상의 firstSrcSn 까지 한 번에 주입한다.
         * 호출 측에서 LsDataSrcRepository.findFirstSrcSnGroupedByRawSn() 결과를 lookup 한 뒤 전달한다.
         */
        public static Item from(LsPjtUserAuthrt e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn) {
            return new Item(
                    e.getAuthrtSeq(),
                    e.getAuthrtSeq(),
                    e.getPjtId(),
                    e.getUserNo(),
                    e.getRawDataId(),
                    e.getRawDataId() != null ? "video #" + e.getRawDataId() : null,
                    workerName != null ? workerName
                            : (e.getUserNo() != null ? "user #" + e.getUserNo() : ""),
                    e.getTaskTypeCd(),
                    e.getRawDataId(),
                    e.getTaskTypeCd(),
                    e.getRegUserNo(),
                    e.getRegDt(),
                    e.getRegDt(),
                    reviewerId,
                    reviewerName,
                    firstSrcSn
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
