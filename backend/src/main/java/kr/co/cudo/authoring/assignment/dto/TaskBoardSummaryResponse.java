package kr.co.cudo.authoring.assignment.dto;

import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;

import java.util.Map;

/**
 * 작업목록(SCR-TASK-001) KPI 카드 집계 — {@code GET /v1/tasks/board/summary} 응답.
 *
 * <p><b>필드 ↔ {@link BoardWorkStatus} 대응</b> (필드명과 enum 이름이 하나 어긋나므로 주의):
 * <table>
 *   <caption>KPI 필드 매핑</caption>
 *   <tr><th>필드</th><th>화면 카드</th><th>BoardWorkStatus</th></tr>
 *   <tr><td>{@code unassigned}</td><td>미배정</td><td>{@link BoardWorkStatus#UNASSIGNED}</td></tr>
 *   <tr><td>{@code inProgress}</td><td>작업중</td><td><b>{@link BoardWorkStatus#PENDING}</b></td></tr>
 *   <tr><td>{@code reviewPending}</td><td>검수요청</td><td>{@link BoardWorkStatus#REVIEW_PENDING}</td></tr>
 *   <tr><td>{@code completed}</td><td>(카드 없음)</td><td>{@link BoardWorkStatus#COMPLETED}</td></tr>
 *   <tr><td>{@code rejected}</td><td>반려</td><td>{@link BoardWorkStatus#REJECTED}</td></tr>
 * </table>
 *
 * <p>★ {@code inProgress} 는 {@code BoardWorkStatus.IN_PROGRESS} 가 아니라
 * <b>{@link BoardWorkStatus#PENDING}</b>(배정됨 · 검수 미제출) 를 센다. {@code BoardWorkStatus} 와
 * {@code TaskBoardService.mapBoardStatus} 는 {@code IN_PROGRESS} 라는 값을 <b>아예 반환하지 않는다</b>
 * (워크플로 {@code ASSIGNED} → {@code PENDING}). 필드명이 {@code inProgress} 인 것은 화면 카드 라벨이
 * "작업중"이기 때문이며, <b>집계 기준은 {@code PENDING}</b> 이다.
 *
 * <p><b>불변식</b>: {@code total == unassigned + inProgress + reviewPending + completed + rejected}.
 * 화면 카드는 5종(전체/미배정/작업중/검수요청/반려)만 쓰지만 {@code completed} 를 응답에서 빼면
 * 카드 합과 총건수가 어긋나 "숫자가 틀렸다"는 오인을 부르므로 함께 반환한다.
 *
 * <p>★<b>{@code excludedCount} 는 이 합의 항이 아니다</b> — 제외분은 {@code total} 에서도 이미 빠져
 * 있어 <b>별개 축</b>이다. 합에 더하면 불변식이 깨진다. [@design ADR-069] [@design API-136]
 *
 * <p>★<b>「제외됨 N건」의 진실원은 이 집계 창구 하나다</b> — 목록 응답({@code GET /v1/tasks/board})에는
 * 그 키를 두지 않는다. 같은 숫자를 두 창구가 각각 계산하면 한쪽만 조건이 바뀌어도 드러나지 않는다.
 *
 * <p><b>스냅샷 의미</b>: 목록({@code GET /v1/tasks/board})과 별도 요청이므로 두 호출 사이에 배정/검수가
 * 바뀌면 카드와 목록이 미세하게 어긋날 수 있다. 대시보드성 KPI 라 강한 정합성을 요구하지 않으며,
 * 각 값은 <b>조회 시점 스냅샷</b>이다.
 *
 * @param total         필터 결과 총건수 (5종 합계와 동일)
 * @param unassigned    미배정 — LABELER 배정 없음
 * @param inProgress    작업중 — {@link BoardWorkStatus#PENDING} (배정됨, 검수 미제출)
 * @param reviewPending 검수요청 — 검수 제출/검수 진행중
 * @param completed     완료 — 검수 승인
 * @param rejected      반려 — 검수 반려
 * @param excludedCount 제외됨 — 화면 목록에서 뺀 영상 건수. <b>위 합의 항이 아니다</b>
 */
public record TaskBoardSummaryResponse(
        long total,
        long unassigned,
        long inProgress,
        long reviewPending,
        long completed,
        long rejected,
        long excludedCount
) {

    /**
     * 상태별 집계 결과에서 응답을 조립한다 — {@code total} 은 5종 합으로 파생돼 불변식이 구조적으로 성립한다.
     *
     * <p>★{@code excludedCount} 는 <b>버킷이 아니라 별개 축</b>이라 {@code total} 과 5종 합 어디에도
     * 들어가지 않는다({@code total} 은 제외분을 이미 뺀 집합의 크기다). 그래서 별도 인자로 받으며,
     * 이 값을 합에 더하는 순간 「버킷 합 = 전체 건수」 불변식이 깨진다. [@design ADR-069] [@design API-136]
     *
     * @param excludedCount 같은 필터 범위 안에서 센 제외 건수 (0건이어도 응답에 실린다)
     */
    public static TaskBoardSummaryResponse of(Map<BoardWorkStatus, Long> counts, long excludedCount) {
        long unassigned = count(counts, BoardWorkStatus.UNASSIGNED);
        long inProgress = count(counts, BoardWorkStatus.PENDING);
        long reviewPending = count(counts, BoardWorkStatus.REVIEW_PENDING);
        long completed = count(counts, BoardWorkStatus.COMPLETED);
        long rejected = count(counts, BoardWorkStatus.REJECTED);
        long total = unassigned + inProgress + reviewPending + completed + rejected;
        return new TaskBoardSummaryResponse(
                total, unassigned, inProgress, reviewPending, completed, rejected, excludedCount);
    }

    /**
     * 집계 결과에 없는 버킷은 0 — 리포지토리는 조건부 집계로 5종을 항상 채우지만, 이 메서드는
     * 맵이 비거나 일부 키가 없는 호출(테스트·향후 다른 집계 경로)에도 6개 필드가 모두 응답에
     * 실리도록 방어한다(HIGH-9).
     */
    private static long count(Map<BoardWorkStatus, Long> counts, BoardWorkStatus status) {
        if (counts == null) {
            return 0L;
        }
        Long value = counts.get(status);
        return value != null ? value : 0L;
    }
}
