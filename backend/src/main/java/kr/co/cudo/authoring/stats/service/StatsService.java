package kr.co.cudo.authoring.stats.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.eventtype.EvntType;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.DailyRawRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.LabelTimestampRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.MonthlyRawRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.UserCountRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.WorkerStatRow;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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
     * UI 6 종 이벤트 코드 → SoT 한글 라벨 (frontend types.ts EventTypeCd 와 동기).
     * 순서가 보장되어야 하므로 (UI 그리드 6 칸 고정 순서) 불변 List 로 보관한다.
     * Map.copyOf 는 hash 순서로 변환되어 순서가 깨진다 — 사용 금지.
     *
     * <p>약어 코드는 외부 API 계약(FE 통계 그리드 코드)이라 유지하되, 한글 라벨은
     * {@link EvntType#getLabel()} SoT 값을 그대로 사용해 일관성을 보장한다.
     */
    private static final List<EventLabel> EVENT_LABELS = List.of(
            new EventLabel("FALL", EvntType.EVT_FALL.getLabel()),
            new EventLabel("VIOLENCE", EvntType.EVT_VIOLENCE.getLabel()),
            new EventLabel("TRAFFIC_ACCIDENT", EvntType.EVT_ACCIDENT.getLabel()),
            new EventLabel("ABNORMAL_BEHAVIOR", EvntType.EVT_ABNORMAL.getLabel()),
            new EventLabel("FLOOD", EvntType.EVT_FLOOD.getLabel()),
            new EventLabel("WILDFIRE", EvntType.EVT_FIRE.getLabel())
    );

    /**
     * UI 코드(FE EventTypeCd) → DB 시드 코드(EVT_ 접두사) 매핑.
     * LS_DATA_RAW.EVNT_TYPE_CD 가 실제 'EVT_FALL' 등으로 저장되므로 카운트 조회 시 변환 필요.
     */
    private static final Map<String, String> UI_CODE_TO_DB_CODE = Map.of(
            "FALL", EvntType.EVT_FALL.getCode(),
            "VIOLENCE", EvntType.EVT_VIOLENCE.getCode(),
            "TRAFFIC_ACCIDENT", EvntType.EVT_ACCIDENT.getCode(),
            "ABNORMAL_BEHAVIOR", EvntType.EVT_ABNORMAL.getCode(),
            "FLOOD", EvntType.EVT_FLOOD.getCode(),
            "WILDFIRE", EvntType.EVT_FIRE.getCode()
    );

    private record EventLabel(String code, String label) {
    }

    /** SCR-STAT-001 일별 차트 윈도우. */
    private static final int DAILY_WINDOW_DAYS = 30;
    /** SCR-STAT-001 월별 표 윈도우. */
    private static final int MONTHLY_WINDOW_MONTHS = 12;
    /**
     * SCR-STAT-001 일별/월별 키 포맷. JPQL TO_CHAR 가 MariaDB 미지원이라
     * 서비스 레이어에서 dialect 무관하게 문자열 키를 생성한다.
     */
    private static final DateTimeFormatter DAILY_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTHLY_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final StatsQueryRepository statsQueryRepository;
    private final VideoRepository videoRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    private final UserRepository userRepository;

    /**
     * 대시보드 요약을 조립한다. actor 가 null 이거나 인증 정보가 없는 경우(테스트 등)는
     * myTask 부분만 0 으로 폴백한다 (NPE 방지).
     */
    public DashboardSummaryResponse getSummary(TokenClaims actor) {
        Map<String, Long> sttsCounts = toMap(statsQueryRepository.countByDataSttsCd());

        long pendingCount   = sumOf(sttsCounts, LsRawDataStatus.STTS_PENDING);
        long completedCount = sumOf(sttsCounts, LsRawDataStatus.STTS_APPROVED);
        long rejectedCount  = sumOf(sttsCounts, LsRawDataStatus.STTS_REJECTED);

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
                sumOf(mine, LsRawDataStatus.STTS_PENDING),
                sumOf(mine, LsRawDataStatus.STTS_ASSIGNED),
                sumOf(mine, LsRawDataStatus.STTS_IN_REVIEW),
                sumOf(mine, LsRawDataStatus.STTS_REJECTED)
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
     * SCR-STAT-001 작업자 통계.
     *
     * <p>권한 정책 (CWE-639 IDOR 차단):
     * <ul>
     *   <li>WORKER 는 본인 통계만 — workerId 가 자기 자신이 아니면 403.</li>
     *   <li>REVIEWER 는 workerId 로 임의 작업자 통계 조회 가능. 미지정 시 본인.</li>
     * </ul>
     *
     * @param actor    인증된 사용자 (필수)
     * @param workerId 조회 대상 (REVIEWER 만 의미 있음). null → actor 본인.
     * @return 통계 응답. 데이터 없는 사용자는 모든 카운트 0, 빈 배열로 안전 응답.
     */
    public WorkerStatSummaryResponse getWorkerSummary(TokenClaims actor, Long workerId) {
        Long actorUserNo = parseUserNo(actor);
        if (actorUserNo == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Long targetUserNo = (workerId != null) ? workerId : actorUserNo;

        // CWE-639 IDOR — WORKER 는 본인만 조회 가능.
        if (actor.role() == Role.WORKER && !actorUserNo.equals(targetUserNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        String workerName = userRepository.findByUserNo(targetUserNo)
                .map(MngAcctUser::getUserNm)
                .orElse(null);

        // 1) 상태별 카운트 (completed/inProgress/rejected)
        Map<String, Long> taskCounts = toMap(statsQueryRepository.countWorkerTaskByStatus(targetUserNo));
        long completed = sumOf(taskCounts, LsRawDataStatus.STTS_APPROVED);
        long inProgress = sumOf(taskCounts, LsRawDataStatus.STTS_ASSIGNED)
                + sumOf(taskCounts, LsRawDataStatus.STTS_IN_REVIEW);
        long rejected = sumOf(taskCounts, LsRawDataStatus.STTS_REJECTED);

        // 2) 라벨 수 + 오토라벨 비율
        long labelCount = statsQueryRepository.countLabelsForWorker(targetUserNo);
        long autoLabelCount = statsQueryRepository.countAutoLabelsForWorker(targetUserNo);
        double autoLabelRate = (labelCount == 0) ? 0.0 : (double) autoLabelCount / (double) labelCount;

        // 3) 반려율
        long rejectDenom = completed + rejected;
        double rejectRate = (rejectDenom == 0) ? 0.0 : (double) rejected / (double) rejectDenom;

        // 4) 일별 30일 — JPQL TO_CHAR 대신 raw 행 받아 Java 측 'YYYY-MM-DD' 키로 그룹.
        //    데이터량: 단일 사용자/30일 윈도 → 최대 수십~수백 행. 메모리 부담 없음.
        LocalDateTime dailySince = LocalDate.now().minusDays(DAILY_WINDOW_DAYS - 1L).atStartOfDay();
        List<DailyRawRow> dailyRows = statsQueryRepository.findDailyCompletionForWorker(targetUserNo, dailySince);
        // TreeMap 으로 키(YYYY-MM-DD 문자열) 자연 정렬 = 날짜 ASC.
        Map<String, Long> dailyMap = new TreeMap<>();
        for (DailyRawRow r : dailyRows) {
            if (r.getUpdDt() == null) continue;
            String key = r.getUpdDt().toLocalDate().format(DAILY_KEY_FMT);
            dailyMap.merge(key, 1L, Long::sum);
        }
        List<WorkerStatSummaryResponse.DailyCompletion> daily = new ArrayList<>(dailyMap.size());
        for (Map.Entry<String, Long> e : dailyMap.entrySet()) {
            daily.add(new WorkerStatSummaryResponse.DailyCompletion(e.getKey(), e.getValue()));
        }

        // 5) 월별 12개월 — completed/rejected 는 LS_RAW_DATA_STATUS.UPD_DT 기준,
        //    labelCount 는 LS_DATA_LBL.REG_DT 기준으로 별도 쿼리 후 동일 'YYYY-MM' 키로 매핑.
        //    JPQL TO_CHAR 대신 raw 행 받아 Java 측 키 생성 → dialect 무관.
        LocalDateTime monthlySince = LocalDate.now()
                .minusMonths(MONTHLY_WINDOW_MONTHS - 1L)
                .withDayOfMonth(1)
                .atStartOfDay();
        List<MonthlyRawRow> monthlyRows = statsQueryRepository.findMonthlyForWorker(targetUserNo, monthlySince);
        // TreeMap → 월 키 자연 정렬 ASC. 값은 long[2] = {completed, rejected}.
        Map<String, long[]> monthlyMap = new TreeMap<>();
        for (MonthlyRawRow r : monthlyRows) {
            if (r.getUpdDt() == null || r.getDataSttsCd() == null) continue;
            String key = r.getUpdDt().toLocalDate().format(MONTHLY_KEY_FMT);
            long[] acc = monthlyMap.computeIfAbsent(key, k -> new long[2]);
            if (LsRawDataStatus.STTS_APPROVED.equals(r.getDataSttsCd())) {
                acc[0]++;
            } else if (LsRawDataStatus.STTS_REJECTED.equals(r.getDataSttsCd())) {
                acc[1]++;
            }
        }
        // 월별 라벨 수 — countLabelsForWorker 와 동일 기준 (자동+수동, LABELER 배정 raw 의 모든 라벨).
        List<LabelTimestampRow> labelRows =
                statsQueryRepository.findMonthlyLabelTimestampsForWorker(targetUserNo, monthlySince);
        Map<String, Long> labelMonthlyMap = new HashMap<>();
        for (LabelTimestampRow r : labelRows) {
            if (r.getRegDt() == null) continue;
            String key = r.getRegDt().toLocalDate().format(MONTHLY_KEY_FMT);
            labelMonthlyMap.merge(key, 1L, Long::sum);
            // 라벨만 있고 completed/rejected 가 없는 월도 monthly 행에 노출되도록 키 보강.
            monthlyMap.computeIfAbsent(key, k -> new long[2]);
        }
        List<WorkerStatSummaryResponse.MonthlyRow> monthly = new ArrayList<>(monthlyMap.size());
        for (Map.Entry<String, long[]> e : monthlyMap.entrySet()) {
            monthly.add(new WorkerStatSummaryResponse.MonthlyRow(
                    e.getKey(),
                    e.getValue()[0],
                    e.getValue()[1],
                    labelMonthlyMap.getOrDefault(e.getKey(), 0L)
            ));
        }

        return new WorkerStatSummaryResponse(
                String.valueOf(targetUserNo),
                workerName,
                completed,
                inProgress,
                rejected,
                labelCount,
                autoLabelRate,
                rejectRate,
                daily,
                monthly
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
                sumOf(sttsCounts, LsRawDataStatus.STTS_PENDING),
                sumOf(sttsCounts, LsRawDataStatus.STTS_ASSIGNED),
                sumOf(sttsCounts, LsRawDataStatus.STTS_IN_REVIEW),
                sumOf(sttsCounts, LsRawDataStatus.STTS_APPROVED),
                sumOf(sttsCounts, LsRawDataStatus.STTS_REJECTED)
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
