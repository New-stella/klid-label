package kr.co.cudo.authoring.stats.service;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.stats.dto.DashboardSummaryResponse;
import kr.co.cudo.authoring.stats.dto.EventDistributionItem;
import kr.co.cudo.authoring.stats.dto.MyTaskBreakdown;
import kr.co.cudo.authoring.stats.dto.NoticeItem;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse;
import kr.co.cudo.authoring.stats.dto.WorkerStatSummaryResponse;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.CountRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.UserCountRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.WorkerStatRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SCR-DASH-001 메인 대시보드 통계 조회 서비스.
 *
 * <p>읽기 전용. ApplicationService 역할 — Repository 호출 + DTO 조립만 담당하고 비즈니스 규칙은 없다.
 *
 * <p>역할별 정책:
 * <ul>
 *   <li>WORKER  — myTaskCount / myTask 분해 채워서 반환.</li>
 *   <li>REVIEWER — myTask 는 항상 0 (UI 가 사용하지 않음).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {

    /**
     * UI 6 종 이벤트 코드 → 한글 라벨 (frontend types.ts EventTypeCd 와 동기).
     * 순서가 보장되어야 하므로 (UI 그리드 6 칸 고정 순서) 불변 List 로 보관한다.
     * Map.copyOf 는 hash 순서로 변환되어 순서가 깨진다 — 사용 금지.
     */
    private static final List<EventLabel> EVENT_LABELS = List.of(
            new EventLabel("FALL", "쓰러짐"),
            new EventLabel("VIOLENCE", "폭력"),
            new EventLabel("TRAFFIC_ACCIDENT", "교통사고"),
            new EventLabel("ABNORMAL_BEHAVIOR", "이상행동(유괴)"),
            new EventLabel("FLOOD", "침수"),
            new EventLabel("WILDFIRE", "산불")
    );

    /**
     * UI 코드(FE EventTypeCd) → DB 시드 코드(EVT_ 접두사) 매핑.
     * LS_DATA_RAW.EVNT_TYPE_CD 가 실제 'EVT_FALL' 등으로 저장되므로 카운트 조회 시 변환 필요.
     */
    private static final Map<String, String> UI_CODE_TO_DB_CODE = Map.of(
            "FALL", "EVT_FALL",
            "VIOLENCE", "EVT_VIOLENCE",
            "TRAFFIC_ACCIDENT", "EVT_ACCIDENT",
            "ABNORMAL_BEHAVIOR", "EVT_ABNORMAL",
            "FLOOD", "EVT_FLOOD",
            "WILDFIRE", "EVT_FIRE"
    );

    private record EventLabel(String code, String label) {
    }

    private final StatsQueryRepository statsQueryRepository;
    private final VideoRepository videoRepository;
    private final LsPjtUserAuthrtRepository authrtRepository;

    /**
     * 대시보드 요약을 조립한다. actor 가 null 이거나 인증 정보가 없는 경우(테스트 등)는
     * myTask 부분만 0 으로 폴백한다 (NPE 방지).
     */
    public DashboardSummaryResponse getSummary(TokenClaims actor) {
        Map<String, Long> sttsCounts = toMap(statsQueryRepository.countByDataSttsCd());

        long pendingCount   = sumOf(sttsCounts, LsPjtDataStts.STTS_PENDING);
        long completedCount = sumOf(sttsCounts, LsPjtDataStts.STTS_APPROVED);
        long rejectedCount  = sumOf(sttsCounts, LsPjtDataStts.STTS_REJECTED);

        long cumulativeImageCount = statsQueryRepository.countCumulativeFrames();
        long cumulativeVideoCount = videoRepository.count();

        List<EventDistributionItem> distribution = buildDistribution(
                toMap(statsQueryRepository.countVideoByEventType()));
        List<EventDistributionItem> imageDistribution = buildDistribution(
                toMap(statsQueryRepository.countFrameByEventType()));

        Long userNo = parseUserNo(actor);
        boolean isWorker = actor != null && actor.role() == Role.WORKER && userNo != null;

        long myTaskCount = isWorker ? authrtRepository.countActiveTasksByUserNo(userNo) : 0L;
        MyTaskBreakdown myTask = isWorker ? buildMyTask(userNo) : MyTaskBreakdown.empty();

        // V1.x 시점 공지 도메인 미구현 — 빈 배열로 응답 (FE 호환).
        List<NoticeItem> notices = List.of();

        return new DashboardSummaryResponse(
                pendingCount,
                completedCount,
                myTaskCount,
                rejectedCount,
                cumulativeImageCount,
                cumulativeVideoCount,
                distribution,
                imageDistribution,
                myTask,
                notices
        );
    }

    private MyTaskBreakdown buildMyTask(Long userNo) {
        Map<String, Long> mine = toMap(statsQueryRepository.countMyTaskByStatus(userNo));
        return new MyTaskBreakdown(
                sumOf(mine, LsPjtDataStts.STTS_PENDING),
                sumOf(mine, LsPjtDataStts.STTS_ASSIGNED),
                sumOf(mine, LsPjtDataStts.STTS_IN_REVIEW),
                sumOf(mine, LsPjtDataStts.STTS_REJECTED)
        );
    }

    /**
     * 6 종 이벤트 라벨을 항상 모두 포함시켜 반환한다 (없으면 0).
     * UI 가 그리드 6 칸 고정이므로 누락 코드가 있어도 0 카운트로 채워준다.
     */
    private List<EventDistributionItem> buildDistribution(Map<String, Long> raw) {
        return EVENT_LABELS.stream()
                .map(e -> new EventDistributionItem(
                        e.code(),
                        e.label(),
                        raw.getOrDefault(UI_CODE_TO_DB_CODE.getOrDefault(e.code(), e.code()), 0L)))
                .toList();
    }

    private Map<String, Long> toMap(List<CountRow> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (CountRow r : rows) {
            if (r.getCode() != null) {
                m.put(r.getCode(), r.getCnt());
            }
        }
        return m;
    }

    private long sumOf(Map<String, Long> m, String key) {
        Long v = m.get(key);
        return v == null ? 0L : v;
    }

    /**
     * SCR-STAT-001 작업자 통계 placeholder.
     *
     * <p>현재는 6 종 이벤트 라벨만 보장된 빈 응답을 반환한다 (FE 그리드 안전 표시).
     * 실제 집계 (라벨/검수 카운트, 일별/월별 표) 는 후속 Phase 에서 채운다.
     */
    public WorkerStatSummaryResponse getWorkerSummary(TokenClaims actor, String period) {
        // period 는 FE 가 allowlist 검증 (WEEK/MONTH/QUARTER/YEAR) — BE 는 추가 분기 없이 동일 응답.
        return new WorkerStatSummaryResponse(
                0L,
                0L,
                0.0,
                0L,
                List.of(),
                buildDistribution(Map.of()),
                List.of()
        );
    }

    /**
     * SCR-STAT-002 전체 구축 현황 placeholder (REVIEWER 전용).
     *
     * <p>누적 카운트만 실제 집계 사용, 처리 현황/작업자별 표는 0 / 빈 배열.
     */
    public OverallStatSummaryResponse getOverallSummary() {
        long cumulativeImageCount = statsQueryRepository.countCumulativeFrames();
        long cumulativeVideoCount = videoRepository.count();
        Map<String, Long> sttsCounts = toMap(statsQueryRepository.countByDataSttsCd());
        OverallStatSummaryResponse.Processing processing = new OverallStatSummaryResponse.Processing(
                sumOf(sttsCounts, LsPjtDataStts.STTS_PENDING),
                sumOf(sttsCounts, LsPjtDataStts.STTS_ASSIGNED),
                sumOf(sttsCounts, LsPjtDataStts.STTS_IN_REVIEW),
                sumOf(sttsCounts, LsPjtDataStts.STTS_APPROVED),
                sumOf(sttsCounts, LsPjtDataStts.STTS_REJECTED)
        );
        List<EventDistributionItem> distribution = buildDistribution(
                toMap(statsQueryRepository.countVideoByEventType()));
        List<OverallStatSummaryResponse.WorkerRow> workers = buildWorkerRows();
        return new OverallStatSummaryResponse(
                cumulativeImageCount,
                cumulativeVideoCount,
                processing,
                distribution,
                workers
        );
    }

    /**
     * SCR-STAT-002 작업자별 통계 행 조립.
     *
     * <p>LABELER 배정 + 상태별 카운트는 JPQL 한 번에 GROUP BY 로 가져오고
     * (N+1 회피), REVIEWER 배정 수는 별도 GROUP BY 쿼리 1 회 추가 후
     * Map 으로 합산한다. approvalRate 는 분모 0 일 때 0.0.
     */
    private List<OverallStatSummaryResponse.WorkerRow> buildWorkerRows() {
        List<WorkerStatRow> rows = statsQueryRepository.findWorkerStats();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> reviewedMap = toUserCountMap(statsQueryRepository.countReviewerByUser());
        return rows.stream()
                .map(r -> {
                    long approved = r.getApprovedCount();
                    long rejected = r.getRejectedCount();
                    long denom = approved + rejected;
                    double approvalRate = (denom == 0) ? 0.0 : (approved * 100.0 / denom);
                    long reviewed = reviewedMap.getOrDefault(r.getUserId(), 0L);
                    return new OverallStatSummaryResponse.WorkerRow(
                            r.getUserId() == null ? 0L : r.getUserId(),
                            r.getName(),
                            r.getLabeled(),
                            reviewed,
                            approvalRate
                    );
                })
                .toList();
    }

    private Map<Long, Long> toUserCountMap(List<UserCountRow> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (UserCountRow r : rows) {
            if (r.getCode() != null) {
                m.put(r.getCode(), r.getCnt());
            }
        }
        return m;
    }

    private Long parseUserNo(TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(actor.sub());
        } catch (NumberFormatException e) {
            // 숫자 sub 가 아닌 토큰(테스트 등) — myTask 는 0 으로 폴백.
            return null;
        }
    }
}
