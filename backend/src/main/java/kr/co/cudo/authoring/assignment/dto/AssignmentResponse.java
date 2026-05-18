package kr.co.cudo.authoring.assignment.dto;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;

import java.time.LocalDateTime;
import java.util.List;

public record AssignmentResponse(
        List<Item> items
) {
    public record Item(
            Long id,
            Long authrtSeq,
            Long workerId,
            Long videoId,
            String videoTitle,
            // FE TaskListPage 영상명 컬럼 — MNG_RESOURCE_CCTV.CCTV_NM (예: "CCTV-강남구-001").
            // 마스터 매핑이 없거나 시드 미적재 시 vmsCctvId 폴백 또는 null.
            String cctvName,
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
         * 서비스 레이어에서 REVIEWER 배정 lookup 후 {@link #from(LsTaskAssignment, Long)} 사용 권장.
         */
        public static Item from(LsTaskAssignment e) {
            return from(e, null, null, null, null, null);
        }

        /** REVIEWER 배정 lookup 결과를 함께 주입 (서비스에서 N+1 회피 후 호출). */
        public static Item from(LsTaskAssignment e, Long reviewerId) {
            return from(e, reviewerId, null, null, null, null);
        }

        /**
         * Service 레이어에서 user 이름 일괄 조회 후 호출 (N+1 회피).
         * workerName/reviewerName 이 null 이면 폴백("user #N") 적용.
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName) {
            return from(e, reviewerId, workerName, reviewerName, null, null);
        }

        /**
         * firstSrcSn 까지 주입 (cctvName 미주입 호환 오버로드).
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn) {
            return from(e, reviewerId, workerName, reviewerName, firstSrcSn, null);
        }

        /**
         * 전체 인자 변환 — 영상의 cctvName 까지 한 번에 주입한다.
         * 호출 측에서 {@code VideoRepository#findCctvNamesByRawSns} 결과를 lookup 한 뒤 전달한다.
         * cctvName 이 null 인 경우 videoTitle 은 기존 "video #N" 폴백을 유지한다 (FE 호환).
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn,
                                String cctvName) {
            String videoTitle = cctvName != null && !cctvName.isBlank()
                    ? cctvName
                    : (e.getRawDataId() != null ? "video #" + e.getRawDataId() : null);
            return new Item(
                    e.getAssignmentId(),
                    e.getAssignmentId(),
                    e.getUserNo(),
                    e.getRawDataId(),
                    videoTitle,
                    cctvName,
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

    public static AssignmentResponse of(List<LsTaskAssignment> entities) {
        return new AssignmentResponse(entities.stream().map(Item::from).toList());
    }

    public static AssignmentResponse single(LsTaskAssignment e) {
        return new AssignmentResponse(List.of(Item.from(e)));
    }
}
