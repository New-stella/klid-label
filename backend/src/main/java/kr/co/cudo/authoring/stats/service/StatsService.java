package kr.co.cudo.authoring.stats.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
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
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.WorkerLabelCountRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.WorkerStatRow;
import kr.co.cudo.authoring.user.service.UserNameResolver;
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
    private final UserNameResolver userNameResolver;
    private final EventTypeService eventTypeService;

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

        // 검수완료(APPROVED) 한정 — "이미지/영상 학습데이터" 카드용.
        // 영상 건수는 신규 쿼리를 추가하지 않고 위에서 이미 계산한 completedCount(=APPROVED 상태
        // 카운트)를 재사용한다. 같은 수치를 두 경로로 계산하면 드리프트가 생긴다.
        long approvedVideoCount = completedCount;
        long approvedImageCount = statsQueryRepository.countFramesByDataSttsCd(LsRawDataStatus.STTS_APPROVED);
        // 분포는 전체 기준과 동일한 buildDistribution 헬퍼를 재사용한다 — 카테고리 구성·순서가
        // 같아야 FE 가 두 분포를 같은 그리드에 렌더할 수 있다(전용 헬퍼를 만들지 말 것).
        List<EventDistributionItem> approvedDistribution = buildDistribution(
                toMap(statsQueryRepository.countVideoByEventTypeAndStatus(LsRawDataStatus.STTS_APPROVED)));
        List<EventDistributionItem> approvedImageDistribution = buildDistribution(
                toMap(statsQueryRepository.countFrameByEventTypeAndStatus(LsRawDataStatus.STTS_APPROVED)));

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
                notices,
                approvedImageCount,
                approvedVideoCount,
                approvedDistribution,
                approvedImageDistribution
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
     * 이벤트 분포 그리드를 관제 코드 체계 기준으로 집계한다 (Phase 3 재설계).
     *
     * <p>입력 {@code rawByEvCode} 의 키는 {@code LS_DATA_RAW.EVNT_TYPE_CD} 실제 저장값인
     * 관제 EV-코드(예 {@code EV02000201}), 값은 카운트다.
     *
     * <p>{@link EventTypeService#filterOptions()} 가 반환하는 각 카테고리(침수/산사태/화재/쓰러짐/
     * 파손/교통사고/싸움/흉기소지/납치 등)에 대해, 그 카테고리의 {@code memberCodes}(상세 EV-코드들)
     * 카운트를 raw 에서 합산한다. raw 에 없는 코드는 0. 카테고리 순서는 filterOptions 의 안정 순서
     * (categoryKey 오름차순)를 그대로 유지해 그리드 칸 순서를 고정한다.
     *
     * <p><b>비수집 코드 제외 정책</b>: 비수집(CLCT_YN='N') 코드(예 기타 상황 EV07000201)와 ignore
     * 대분류('08')는 filterOptions 에 포함되지 않으므로 자연히 그리드에서 제외된다 — {@link EventTypeService}
     * 의 필터 옵션 정책과 일관(그 옵션 개수 자체가 등록 유형·표시명 그룹핑·제외 대분류 설정에 따라
     * 달라지는 값이라 고정 개수가 아니다). raw 에 비수집 코드 카운트가 있어도 어떤 카테고리에도 합산되지 않는다.
     *
     * <p>반환 {@link EventDistributionItem} 의 첫 필드({@code eventTypeCd})는 UI 약어 코드가 아닌
     * categoryKey(예 {@code "020002"})를 담는다 (DTO 구조 유지, 값 의미만 변경).
     */
    private List<EventDistributionItem> buildDistribution(Map<String, Long> rawByEvCode) {
        return eventTypeService.filterOptions().stream()
                .map(opt -> new EventDistributionItem(
                        opt.categoryKey(),
                        opt.label(),
                        sumMemberCounts(rawByEvCode, opt.memberCodes())))
                .toList();
    }

    /** 카테고리 소속 EV-코드들의 raw 카운트를 합산한다. 미존재 코드는 0. */
    private long sumMemberCounts(Map<String, Long> rawByEvCode, List<String> memberCodes) {
        long sum = 0L;
        for (String code : memberCodes) {
            sum += rawByEvCode.getOrDefault(code, 0L);
        }
        return sum;
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

        // 표시명 해석은 단일 헬퍼가 담당 (마스터 미존재는 예외가 아니라 이름 null — 통계는 그대로 응답).
        String workerName = userNameResolver.resolveOneByNo(targetUserNo);

        // 1) 상태별 카운트 (completed/rejected) + 진행 중 카운트
        Map<String, Long> taskCounts = toMap(statsQueryRepository.countWorkerTaskByStatus(targetUserNo));
        long completed = sumOf(taskCounts, LsRawDataStatus.STTS_APPROVED);
        long rejected = sumOf(taskCounts, LsRawDataStatus.STTS_REJECTED);
        // inProgress 는 여기서 상태를 더해 만들지 않는다 — 전체 구축 현황(SCR-STAT-002)과 같은
        // 판정 조각(StatsQueryRepository.IN_PROGRESS_PREDICATE)을 쓰는 쿼리에 위임한다. 구 방식
        // (ASSIGNED + IN_REVIEW 열거)은 반려·배치 상태를 완료에도 진행에도 넣지 않아 두 화면의
        // 같은 작업자 숫자가 갈렸다.
        long inProgress = statsQueryRepository.countInProgressForWorker(targetUserNo);

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

        // 검수완료(APPROVED) 한정 — 영상 건수는 위 processing.approved 와 동일 원천을 재사용한다.
        long approvedVideoCount = processing.approved();
        long approvedImageCount = statsQueryRepository.countFramesByDataSttsCd(LsRawDataStatus.STTS_APPROVED);
        List<EventDistributionItem> approvedDistribution = buildDistribution(
                toMap(statsQueryRepository.countVideoByEventTypeAndStatus(LsRawDataStatus.STTS_APPROVED)));

        return new OverallStatSummaryResponse(
                cumulativeImageCount,
                cumulativeVideoCount,
                processing,
                distribution,
                workers,
                approvedImageCount,
                approvedVideoCount,
                approvedDistribution,
                buildOverallDailyCounts()
        );
    }

    /**
     * SCR-STAT-002 "일별 작업량(최근 30일)" — 전체(모든 작업자) 일별 검수 완료 건수.
     *
     * <p><b>0-fill</b>: 오늘 포함 30일치 날짜 키를 먼저 0 으로 깔고 조회 결과를 그 위에 더한다.
     * 따라서 반환 길이는 <b>항상 30</b>이고 날짜는 오름차순이다 — 막대차트 X축이 날짜 연속으로
     * 그려지려면 작업이 없던 날도 항목이 있어야 한다. 스켈레톤에 없는 키(윈도우 밖·미래 일자)는
     * 무시되므로 경계 밖 행이 섞여도 길이가 흔들리지 않는다.
     *
     * <p>작업자 통계({@link #getWorkerSummary})의 일별 데이터는 <b>sparse 로 유지</b>한다 —
     * 그쪽 응답 형태를 바꾸면 SCR-STAT-001 FE 계약 변경이 된다.
     */
    private List<WorkerStatSummaryResponse.DailyCompletion> buildOverallDailyCounts() {
        LocalDate from = LocalDate.now().minusDays(DAILY_WINDOW_DAYS - 1L);

        // 날짜 오름차순 0-fill 스켈레톤 (LinkedHashMap = 삽입 순서 = 날짜 ASC).
        Map<String, Long> byDate = new LinkedHashMap<>(DAILY_WINDOW_DAYS * 2);
        for (int i = 0; i < DAILY_WINDOW_DAYS; i++) {
            byDate.put(from.plusDays(i).format(DAILY_KEY_FMT), 0L);
        }

        for (DailyRawRow r : statsQueryRepository.findDailyCompletionAll(from.atStartOfDay())) {
            if (r.getUpdDt() == null) continue;
            // 윈도우 밖 키는 스켈레톤에 없으므로 자동 제외 (항상 30건 보장).
            byDate.computeIfPresent(r.getUpdDt().toLocalDate().format(DAILY_KEY_FMT), (k, v) -> v + 1L);
        }

        List<WorkerStatSummaryResponse.DailyCompletion> daily = new ArrayList<>(byDate.size());
        for (Map.Entry<String, Long> e : byDate.entrySet()) {
            daily.add(new WorkerStatSummaryResponse.DailyCompletion(e.getKey(), e.getValue()));
        }
        return daily;
    }

    /**
     * SCR-STAT-002 작업자별 통계 행 조립.
     *
     * <p>LABELER 배정 + 상태별 카운트({@code labeled}/{@code inProgress}/승인·반려)는 JPQL 한 번에
     * GROUP BY 로 가져오고(N+1 회피), REVIEWER 배정 수와 라벨 수(총/자동)는 각각 GROUP BY 쿼리
     * 1 회씩 추가해 Map 으로 합산한다 — 작업자 수와 무관하게 총 <b>쿼리 3회</b>다.
     *
     * <p><b>라벨 집계를 같은 쿼리에 넣지 않는 이유</b>: 상태 행과 라벨을 한 GROUP BY 에 조인하면
     * 카티전 곱으로 상태 카운트가 라벨 수만큼 부풀어 {@code labeled}·{@code approvalRate} 가 통째로
     * 틀어진다. 축이 다른 집계는 따로 세어 Map 으로 합친다.
     *
     * <p>두 비율({@code approvalRate}·{@code autoLabelRate})은 모두 <b>백분율(0~100)</b>이며
     * 분모가 0 이면 0 이다(0 으로 나눠 NaN/Infinity 가 JSON 에 실리지 않게 한다).
     */
    private List<OverallStatSummaryResponse.WorkerRow> buildWorkerRows() {
        List<WorkerStatRow> rows = statsQueryRepository.findWorkerStats();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> reviewedMap = toUserCountMap(statsQueryRepository.countReviewerByUser());
        Map<Long, WorkerLabelCountRow> labelMap = toLabelCountMap(statsQueryRepository.countLabelsByWorker());
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
                            approvalRate,
                            r.getInProgress(),
                            autoLabelRateOf(labelMap.get(r.getUserId()))
                    );
                })
                .toList();
    }

    /**
     * 자동 생성 라벨 비율(백분율). 라벨이 한 건도 없는 작업자(집계 행 자체가 없음)는 0 —
     * 분모 0 을 그대로 나누면 {@code NaN} 이 JSON 에 실려 화면이 "NaN%" 를 그린다.
     */
    private double autoLabelRateOf(WorkerLabelCountRow row) {
        if (row == null || row.getTotalCnt() == 0L) {
            return 0.0;
        }
        return row.getAutoCnt() * 100.0 / row.getTotalCnt();
    }

    private Map<Long, WorkerLabelCountRow> toLabelCountMap(List<WorkerLabelCountRow> rows) {
        Map<Long, WorkerLabelCountRow> m = new HashMap<>();
        for (WorkerLabelCountRow r : rows) {
            if (r.getCode() != null) {
                m.put(r.getCode(), r);
            }
        }
        return m;
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
