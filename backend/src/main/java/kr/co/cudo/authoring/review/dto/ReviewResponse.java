package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.video.dto.CctvDisplayNamePolicy;

import java.time.LocalDateTime;

/**
 * 검수 워크플로우 단건 응답.
 *
 * <p>BE 원본 컬럼(dataSttsCd/version/updDt) 외에 FE 호환 alias 필드를 함께 노출해
 * 화면 측 필드명 매핑을 단순화한다.
 * <ul>
 *   <li>{@code id}            = {@code videoId} (FE 라우팅 PK)</li>
 *   <li>{@code cctvName}      = video lookup 결과(미조인 시 {@code 영상 #N} 폴백 —
 *       판정 단일 원천 {@link CctvDisplayNamePolicy})</li>
 *   <li>{@code workerId}      = LS_TASK_ALTMNT lookup (LABELER) — 없으면 null</li>
 *   <li>{@code workerName}    = LS_ACNT_USER lookup — 없으면 ""</li>
 *   <li>{@code submittedAt}   = {@code updDt}</li>
 *   <li>{@code labelCount}    = LS_DATA_LBL count by srcSn for this rawSn — 없으면 0</li>
 *   <li>{@code status}        = FE ReviewStatus 코드 (BE dataSttsCd → FE 코드 매핑)</li>
 *   <li>{@code eventName}     = video lookup 결과 EVNT_TYPE_CD — 없으면 null</li>
 *   <li>{@code eventTypeCd}   = video lookup 결과 EVNT_TYPE_CD — 없으면 null</li>
 *   <li>{@code needsRecheck}  = {@code LsRawDataStatus.REVLT_YN}(V177) — 검수 승인 이후 라벨/메타가
 *       수정되어 재검토가 필요한가(Phase 7b, API-008/API-014). {@code false} 가 기본값이며 승인 상태가
 *       아닌 영상도 항상 값을 갖는다(재검토 축은 승인 여부와 별개로 조회 가능).</li>
 * </ul>
 * BE 원본 필드(videoId/dataSttsCd/version/updDt)는 backward-compat 유지.
 * {@code needsRecheck} 는 <b>추가 필드</b>다 — 기존 필드·타입·상태코드는 변경하지 않는다.
 *
 * <h3>검수 점유·최근 승인자·일괄 승인 자격 (ADR-067 — 추가만)</h3>
 * <ul>
 *   <li>{@code reviewingUserId}/{@code reviewingUserName}/{@code reviewStartedAt} — <b>지금 누가
 *       이 영상을 보고 있는가</b>. 어딘가에 저장해 둔 값이 아니라 작업 이력 원장에서 도출하는 파생
 *       판정이며({@code ReviewClaim}), 점유가 없거나 유예가 지나 만료됐으면 <b>세 값이 모두 null</b>
 *       이다. 점유는 표시이지 조회·검수 자격이 아니다 — 남이 보고 있어도 목록·상세는 열린다.</li>
 *   <li>{@code lastApproverId}/{@code lastApproverName}/{@code lastApproverRole}/{@code lastApprovedAt}
 *       — <b>마지막으로 누가 승인했는가</b>. 역할은 <b>그 행위 시점에 기록된 값</b>이라 관리자가 승인한
 *       건은 나중에 그 사람의 역할이 바뀌어도 관리자로 남는다(볼 때마다 현재 역할을 다시 읽지 않는다).
 *       승인 이력이 없으면 네 값이 모두 null 이고, 역할을 남기지 않던 시기의 옛 기록은 <b>사람과 시각은
 *       있는데 역할만 비어</b> 있을 수 있다 — 복원할 수 없으므로 지어내지 않는다.</li>
 *   <li>{@code bulkApprovable} — <b>이 응답을 받는 사람</b>이 그 건을 일괄 승인에 담을 수 있는가.
 *       요청자에 따라 값이 달라지는 유일한 항목이며 화면이 체크칸을 켤지 끌지에 쓴다. <b>편의 표시이지
 *       인가 판정이 아니다</b> — 자격 없는 건을 담아 보내도 일괄 승인 창구가 그 건만 실패로 돌려준다.</li>
 * </ul>
 *
 * @design API-008
 * @design API-009
 * @design ADR-067
 */
public record ReviewResponse(
        // FE 호환 alias
        Long id,
        String cctvName,
        Long workerId,
        String workerName,
        LocalDateTime submittedAt,
        Long labelCount,
        String status,
        // BE 원본 필드 (호환 유지)
        Long videoId,
        String dataSttsCd,
        Long version,
        LocalDateTime updDt,
        // 이벤트 메타 (Phase 1 enrich) — 마지막에 추가하여 backward-compat 유지
        String eventName,
        String eventTypeCd,
        // Phase 7b — 재검토 필요 표시(V177 REVLT_YN). 마지막에 추가하여 backward-compat 유지.
        boolean needsRecheck,
        // ADR-067 — 검수 점유(파생 판정). 점유 없음·만료면 셋 다 null. 마지막에 추가하여 backward-compat 유지.
        Long reviewingUserId,
        String reviewingUserName,
        LocalDateTime reviewStartedAt,
        // ADR-067 — 최근 승인자. 승인 이력이 없으면 넷 다 null, 옛 기록은 역할만 null 일 수 있다.
        Long lastApproverId,
        String lastApproverName,
        String lastApproverRole,
        LocalDateTime lastApprovedAt,
        // ADR-067 — 이 요청자가 그 건을 일괄 승인에 담을 수 있는가(편의 표시, 인가 판정 아님).
        boolean bulkApprovable
) {

    /**
     * 점유 조회 결과를 응답 축으로 옮기는 운반체 — 값이 하나라도 있으면 <b>점유가 유효하다</b>는 뜻이다.
     *
     * <p>세 값을 따로 넘기면 호출부가 「점유 없음」을 표현할 때 일부만 null 로 남기기 쉽다. 하나로
     * 묶어 {@link #NONE} 을 쓰게 하면 그 실수가 구조적으로 막힌다.
     */
    public record Reviewing(Long userId, String userName, LocalDateTime startedAt) {
        /** 점유 없음 또는 유예 만료 — 세 값이 모두 비어 나간다. */
        public static final Reviewing NONE = new Reviewing(null, null, null);
    }

    /**
     * 최근 승인 이력을 응답 축으로 옮기는 운반체.
     *
     * @param role 그 승인을 한 <b>시점의</b> 역할. 역할을 남기지 않던 시기의 기록이면 null 이다
     */
    public record LastApproval(Long userId, String userName, String role, LocalDateTime at) {
        /** 승인 이력 없음 — 네 값이 모두 비어 나간다. */
        public static final LastApproval NONE = new LastApproval(null, null, null, null);
    }

    /** 단순 매핑 — lookup 인자 없이 status alias 만 변환. */
    public static ReviewResponse from(LsRawDataStatus stts) {
        return from(stts, null, null, null, 0L, null, null);
    }

    /**
     * 보강 매핑 (eventName 미주입 호환) — 서비스 레이어에서 cctvName/workerId/workerName/labelCount 만 주입.
     * eventName/eventTypeCd 는 null 폴백.
     */
    public static ReviewResponse from(LsRawDataStatus stts,
                                      String cctvName,
                                      Long workerId,
                                      String workerName,
                                      Long labelCount) {
        return from(stts, cctvName, workerId, workerName, labelCount, null, null);
    }

    /**
     * 보강 매핑 (정식 — Phase 1) — 서비스 레이어에서
     * cctvName/workerId/workerName/labelCount/eventName/eventTypeCd 를 함께 주입.
     */
    public static ReviewResponse from(LsRawDataStatus stts,
                                      String cctvName,
                                      Long workerId,
                                      String workerName,
                                      Long labelCount,
                                      String eventName,
                                      String eventTypeCd) {
        return from(stts, cctvName, workerId, workerName, labelCount, eventName, eventTypeCd,
                Reviewing.NONE, LastApproval.NONE, false);
    }

    /**
     * 보강 매핑 (정식 — ADR-067) — 위 인자에 <b>검수 점유·최근 승인자·일괄 승인 자격</b>을 더한다.
     *
     * <p>세 축은 모두 <b>표시 전용 추가</b>이며 기존 필드·타입·의미를 바꾸지 않는다. 점유·승인자는
     * 목록 경로에서 <b>페이지 전체를 한 번에</b> 조회한 결과를 행마다 옮겨 담은 것이라 이 팩토리는
     * 조회를 하지 않는다 — 여기서 조회하면 그 자리가 곧 N+1 이 된다.
     *
     * @param reviewing      점유 — 없거나 만료면 {@link Reviewing#NONE}
     * @param lastApproval   최근 승인 — 이력이 없으면 {@link LastApproval#NONE}
     * @param bulkApprovable 이 응답을 받는 사람이 그 건을 일괄 승인에 담을 수 있는가
     * @design API-008
     * @design API-009
     */
    public static ReviewResponse from(LsRawDataStatus stts,
                                      String cctvName,
                                      Long workerId,
                                      String workerName,
                                      Long labelCount,
                                      String eventName,
                                      String eventTypeCd,
                                      Reviewing reviewing,
                                      LastApproval lastApproval,
                                      boolean bulkApprovable) {
        Reviewing resolvedReviewing = (reviewing != null) ? reviewing : Reviewing.NONE;
        LastApproval resolvedApproval = (lastApproval != null) ? lastApproval : LastApproval.NONE;
        Long videoId = stts.getRawDataId();
        // 표시명 폴백은 CctvDisplayNamePolicy 단독 판정이다. 구 표기 "video #N" 은 폐기 —
        // 같은 영상이 검수목록에서만 다른 이름으로 보였다(작업목록·영상목록은 "영상 #N").
        String resolvedCctv = CctvDisplayNamePolicy.resolve(cctvName, null, videoId);
        String resolvedWorkerName = workerName == null ? "" : workerName;
        Long resolvedLabelCount = labelCount == null ? 0L : labelCount;
        String feStatus = mapToFeStatus(stts.getDataSttsCd());
        return new ReviewResponse(
                videoId,
                resolvedCctv,
                workerId,
                resolvedWorkerName,
                stts.getUpdDt(),
                resolvedLabelCount,
                feStatus,
                videoId,
                stts.getDataSttsCd(),
                stts.getVersion(),
                stts.getUpdDt(),
                eventName,
                eventTypeCd,
                stts.needsRecheck(),
                resolvedReviewing.userId(),
                resolvedReviewing.userName(),
                resolvedReviewing.startedAt(),
                resolvedApproval.userId(),
                resolvedApproval.userName(),
                resolvedApproval.role(),
                resolvedApproval.at(),
                bulkApprovable
        );
    }

    /**
     * BE dataSttsCd → FE ReviewStatus 코드 매핑.
     *
     * <ul>
     *   <li>PENDING   → REVIEW_PENDING</li>
     *   <li>IN_REVIEW → REVIEWING</li>
     *   <li>APPROVED  → COMPLETED</li>
     *   <li>REJECTED  → REJECTED</li>
     *   <li>그 외 (ASSIGNED 등) → 원본 그대로 (StatusBadge 가 폴백 처리)</li>
     * </ul>
     */
    private static String mapToFeStatus(String dataSttsCd) {
        if (dataSttsCd == null) return null;
        return switch (dataSttsCd) {
            case "PENDING"   -> "REVIEW_PENDING";
            case "IN_REVIEW" -> "REVIEWING";
            case "APPROVED"  -> "COMPLETED";
            case "REJECTED"  -> "REJECTED";
            default          -> dataSttsCd;
        };
    }
}
