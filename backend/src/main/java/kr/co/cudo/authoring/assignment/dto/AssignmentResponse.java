package kr.co.cudo.authoring.assignment.dto;

import kr.co.cudo.authoring.assignment.domain.AssignmentWorkStatus;
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
            Long firstSrcSn,
            // FE TaskListPage 이벤트 컬럼 — LS_DATA_RAW.EVNT_TYPE_CD 기반. 영상 메타 없으면 null.
            // 현 단계는 eventName/eventTypeCd 동일 코드값 (Phase 1 정책, VideoSummaryResponse 와 일치).
            String eventName,
            String eventTypeCd,
            // FE Task.status (AssignmentStatus) — LS_RAW_DATA_STATUS.DATA_STTS_CD 를 FE 코드로 매핑.
            // BE→FE 매핑:
            //   ASSIGNED  → PENDING        (배정만 됨, 작업 시작 전)
            //   PENDING   → REVIEW_PENDING (작업자 검수 제출 완료, 검수 대기)
            //   IN_REVIEW → REVIEW_PENDING (검수 진행 중도 동일 묶음)
            //   APPROVED  → COMPLETED      (승인 완료)
            //   REJECTED  → REJECTED       (반려)
            //   null/그 외 → PENDING       (LS_RAW_DATA_STATUS row 미생성 시 폴백)
            // FE STATUS_BADGE_MAP 의 키 집합과 정확히 일치하여 화면 깨짐 방지.
            String status,
            // 증강/해상도 파생 영상 여부 — LS_DATA_RAW.ORGNL_RAW_SN != null (R3). 원본이면 false.
            boolean augmented,
            // 증강 종류(정규화 WINTER|NIGHT|RAIN|RESL_1080P|RESL_720P|RESL_480P) — 원본/파싱실패 시 null.
            String augType
    ) {
        /**
         * 기본 변환 — REVIEWER 정보 없이 사용한다.
         * 서비스 레이어에서 REVIEWER 배정 lookup 후 {@link #from(LsTaskAssignment, Long)} 사용 권장.
         */
        public static Item from(LsTaskAssignment e) {
            return from(e, null, null, null, null, null, null, null, null);
        }

        /** REVIEWER 배정 lookup 결과를 함께 주입 (서비스에서 N+1 회피 후 호출). */
        public static Item from(LsTaskAssignment e, Long reviewerId) {
            return from(e, reviewerId, null, null, null, null, null, null, null);
        }

        /**
         * Service 레이어에서 user 이름 일괄 조회 후 호출 (N+1 회피).
         * workerName/reviewerName 이 null 이면 폴백("user #N") 적용.
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName) {
            return from(e, reviewerId, workerName, reviewerName, null, null, null, null, null);
        }

        /**
         * firstSrcSn 까지 주입 (cctvName 미주입 호환 오버로드).
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn) {
            return from(e, reviewerId, workerName, reviewerName, firstSrcSn, null, null, null, null);
        }

        /**
         * cctvName 까지 주입 (eventName/eventTypeCd 미주입 호환 오버로드).
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn,
                                String cctvName) {
            return from(e, reviewerId, workerName, reviewerName, firstSrcSn, cctvName, null, null, null);
        }

        /**
         * eventName/eventTypeCd 까지 주입 (status 미주입 호환 오버로드).
         * status 는 null → fallback 'PENDING' 매핑.
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn,
                                String cctvName, String eventName, String eventTypeCd) {
            return from(e, reviewerId, workerName, reviewerName, firstSrcSn, cctvName,
                    eventName, eventTypeCd, null);
        }

        /**
         * 전체 인자 변환 — 영상의 cctvName / eventName / eventTypeCd / status 까지 한 번에 주입한다.
         * 호출 측에서 {@code VideoRepository#findCctvNamesByRawSns},
         * {@code VideoRepository#findEventInfoByRawSns},
         * {@code LsRawDataStatusRepository.findAllById} 결과를 lookup 한 뒤 전달한다.
         * cctvName 이 null 인 경우 videoTitle 은 기존 "video #N" 폴백을 유지한다 (FE 호환).
         * eventName/eventTypeCd 는 null 폴백 — FE 가 "-" 로 표시한다.
         * dataSttsCd 는 FE AssignmentStatus 코드로 매핑되어 직렬화되며,
         * row 가 없거나 알 수 없는 코드면 'PENDING' 으로 폴백.
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn,
                                String cctvName, String eventName, String eventTypeCd,
                                String dataSttsCd) {
            return from(e, reviewerId, workerName, reviewerName, firstSrcSn, cctvName,
                    eventName, eventTypeCd, dataSttsCd, false, null);
        }

        /**
         * 전체 인자 변환 + 증강 파생 정보(augmented/augType) 주입 (R3).
         * 호출 측에서 영상(LS_DATA_RAW)을 batch lookup 해 ORGNL_RAW_SN != null 여부와
         * <b>AUG_TYPE_CD 컬럼값</b>을 전달한다. 원본이면 augmented=false, augType=null.
         *
         * <p>구 구현은 augType 을 {@code VMS_CLIP_ID} 마커 역파싱으로 도출했으나, 판별 단일 원천을
         * 컬럼으로 옮기면서 파서를 제거했다 — 문자열 마커를 다시 읽는 경로를 되살리지 않는다.
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn,
                                String cctvName, String eventName, String eventTypeCd,
                                String dataSttsCd, boolean augmented, String augType) {
            return from(e, reviewerId, workerName, reviewerName, firstSrcSn, cctvName,
                    eventName, eventTypeCd, dataSttsCd, augmented, augType, false);
        }

        /**
         * 전체 인자 변환 + <b>라벨 저장 이력 존재 여부</b> 주입 (R5/R8).
         *
         * <p>{@code hasSaveHistory} 는 목록 쿼리가 {@code workStatus} 필터에 쓴 것과 <b>같은 표현식</b>의
         * 프로젝션 결과다 — 여기서 다시 조회하면 필터와 표시가 갈라진다(HIGH-3). 그 값을 모르는
         * 경로(단건 응답 등)는 {@code false} 로 위임하는 오버로드를 쓰며, 이때 표시 상태는 변경 전과
         * 동일하다(ASSIGNED → PENDING).
         */
        public static Item from(LsTaskAssignment e, Long reviewerId,
                                String workerName, String reviewerName, Long firstSrcSn,
                                String cctvName, String eventName, String eventTypeCd,
                                String dataSttsCd, boolean augmented, String augType,
                                boolean hasSaveHistory) {
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
                    firstSrcSn,
                    eventName,
                    eventTypeCd,
                    mapToFeStatus(dataSttsCd, hasSaveHistory),
                    augmented,
                    augType
            );
        }

        /**
         * (BE {@code LS_RAW_DATA_STATUS.DATA_STTS_CD}, 라벨 저장 이력 존재 여부) → FE AssignmentStatus 매핑.
         *
         * <p>FE 의 {@code AssignmentStatus} 유니온
         * ({@code PENDING | IN_PROGRESS | REVIEW_PENDING | COMPLETED | REJECTED})
         * 과 정확히 일치하는 값만 반환하여 STATUS_BADGE_MAP/STATUS_LABEL lookup 안전성을 보장한다.
         *
         * <p>매핑 정책:
         * <ul>
         *   <li>ASSIGNED  → PENDING / IN_PROGRESS (사용자 라벨 저장 이력이 있으면 작업중, R5)</li>
         *   <li>PENDING   → REVIEW_PENDING (작업자 검수 제출 완료, 검수 대기)</li>
         *   <li>IN_REVIEW → REVIEW_PENDING (검수 진행 중도 동일 묶음 — FE 별도 코드 없음)</li>
         *   <li>APPROVED  → COMPLETED      (승인 완료 — 최종 상태)</li>
         *   <li>REJECTED  → REJECTED       (반려 — 작업자 재제출 가능)</li>
         *   <li>null/그 외 → PENDING / IN_PROGRESS (상태 row 미생성 시 안전 폴백, 판정 축은 동일)</li>
         * </ul>
         *
         * <p>판정은 {@link AssignmentWorkStatus} 단일 원천에 위임한다 — 목록 필터({@code workStatus})의
         * WHERE 절도 같은 enum 을 읽으므로 "작업중으로 필터했는데 목록엔 대기로 표시"되는 모순이
         * 구조적으로 생길 수 없다(R8).
         */
        private static String mapToFeStatus(String dataSttsCd, boolean hasSaveHistory) {
            return AssignmentWorkStatus.of(dataSttsCd, hasSaveHistory).name();
        }
    }

    public static AssignmentResponse of(List<LsTaskAssignment> entities) {
        return new AssignmentResponse(entities.stream().map(Item::from).toList());
    }

    public static AssignmentResponse single(LsTaskAssignment e) {
        return new AssignmentResponse(List.of(Item.from(e)));
    }
}
