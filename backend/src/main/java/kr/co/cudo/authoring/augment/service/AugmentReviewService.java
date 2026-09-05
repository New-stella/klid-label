package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentJobResponse;
import kr.co.cudo.authoring.augment.dto.AugmentJobStatus;
import kr.co.cudo.authoring.augment.dto.AugmentSummaryResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.dto.CctvDisplayNamePolicy;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 9 — 데이터 증강 검수 (V1.5 SFR-07).
 *
 * <p>외부 SFR-07 시스템이 생성한 증강 결과를 REVIEWER 가 검수(accept/reject)한다.
 * 결과는 외부 시스템에도 best-effort 동기화된다.
 *
 * <p>신규 외부 콜백은 단일 종류({@code AUGMENT})로 수신한다(RESOLUTION은 RQ-SFR-06-03에 따라
 * 저작도구 내부 기능으로 분리됨). 단, 백필하지 않은 기존 데이터(구 3종·레거시 RESOLUTION)의
 * 조회·정렬·검수를 위해 {@code AUG_ORDER}는 그 값들의 정렬도 함께 유지한다.
 *
 * <p>RBAC: 모든 결정 메서드는 REVIEWER 만 호출 가능 (Service 이중 검증).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentReviewService {

    /**
     * UI 표시용 정렬 순서 — 검수 대상 증강이 앞, 해상도 파생 프리셋(고해상도→저해상도)이 뒤다.
     *
     * <p><b>현행 대표값 {@link LsDataAug#AUG_AUGMENT} 가 반드시 맨 앞이다</b>
     * (API-059 · ADR-059). 이 항목이 빠져 있으면
     * {@code getOrDefault(t, 99)} 로 떨어져 <b>현행 값이 구 3종과 해상도 프리셋 전부보다 뒤로</b>
     * 밀린다 — 2026-09-02 실측으로 확인된 결함이며 의도된 동작이 아니었다.
     *
     * <p>구 3종(WINTER/NIGHT/RAIN)과 레거시 단일 {@link LsDataAug#AUG_RESOLUTION} 은 <b>백필하지
     * 않은 기존 데이터의 정렬용</b>으로 남긴다 — 빼면 그 파생본들이 99 로 몰려 뒤섞인다.
     *
     * <p>⚠ {@code Map.of} 는 항목 10개가 상한이다. 더 늘리려면 {@code Map.ofEntries} 로 바꿀 것.
     *
     * @design API-059
     */
    private static final Map<String, Integer> AUG_ORDER = Map.of(
            LsDataAug.AUG_AUGMENT, 1,
            LsDataAug.AUG_WINTER, 2,
            LsDataAug.AUG_NIGHT, 3,
            LsDataAug.AUG_RAIN, 4,
            LsDataAug.AUG_RESOLUTION, 5,
            LsDataAug.AUG_RESL_1080P, 6,
            LsDataAug.AUG_RESL_720P, 7,
            LsDataAug.AUG_RESL_480P, 8
    );

    private final LsDataAugRepository repository;
    private final LsDataAugRvwRepository reviewRepository;
    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final ExternalAugmentClient externalClient;
    /** Phase 7 — 반려 시 폐기 표식(소프트 삭제) 기록. 실삭제 집행은 스윕이 담당한다. */
    private final AugmentDiscardService discardService;

    /**
     * 증강 잡 카드(영상 단위 그룹) 전체 페이징 조회 — FE {@code AugmentJob} 계약 정합.
     *
     * <p>(1) distinct 대표프레임 SRC_SN 을 MIN(REG_DT) 최신순으로 페이징 → (2) 해당 SRC_SN 들의
     * 증강 row 를 일괄 로드 → (3) 영상 단위(SRC_SN 그룹)로 재구성한다. 페이지 전체가 소수의
     * 일괄 조회(row/rawSn/cctv/review)로 처리되어 N+1 이 발생하지 않는다(성능 규칙 준수).
     */
    public Page<AugmentJobResponse> listAll(Pageable pageable) {
        Page<Long> srcPage = repository.findDistinctSrcSnGroupsOrderByMinRegDtDesc(pageable);
        List<Long> orderedSrcSns = srcPage.getContent();
        if (orderedSrcSns.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, srcPage.getTotalElements());
        }
        List<AugmentJobResponse> jobs = buildJobs(orderedSrcSns, repository.findBySrcSnIn(orderedSrcSns));
        return new PageImpl<>(jobs, pageable, srcPage.getTotalElements());
    }

    /**
     * 원본 영상(srcSn) 필터 — 해당 영상의 잡 카드만 Page 로 반환(FE {@code AugmentJob} 계약 정합).
     * 매핑되는 증강 row 가 없으면 빈 Page.
     *
     * <p><b>도메인 주의:</b> {@code srcSn} 은 리터럴 LS_DATA_SRC.SRC_SN(대표프레임 ID)이며,
     * 응답 {@link AugmentJobResponse#videoId()}(=원본 RAW_SN)와 다른 도메인 값이다.
     * job.videoId(RAW_SN)를 이 파라미터로 넘기면 조용히 다른 영상이 조회되므로 혼용 금지.
     */
    public Page<AugmentJobResponse> findBySource(Long srcSn) {
        List<LsDataAug> rows = repository.findBySrcSnOrderByAugTypeCd(srcSn);
        if (rows.isEmpty()) {
            return new PageImpl<>(List.of(), PageRequest.of(0, 1), 0);
        }
        List<AugmentJobResponse> jobs = buildJobs(List.of(srcSn), rows);
        return new PageImpl<>(jobs, PageRequest.of(0, Math.max(jobs.size(), 1)), jobs.size());
    }

    /**
     * 증강 결과 상태(/{jobId}/result) 집계 — FE 결과 화면의 실제 상태 표시용.
     *
     * <p>{@code jobId} 는 잡 카드의 videoId(=원본 RAW_SN, {@link #toJob} 참조)다. 해당 원본 영상의
     * 증강 row 를 조회해 {@link #aggregateStatus} 로 집계한 뒤, FE 결과 화면 3값 계약
     * ({@code COMPLETED|FAILED|PROCESSING})으로 매핑한다: terminal 전부→COMPLETED, dead-letter→FAILED,
     * 그 외(REQUESTED/IN_PROGRESS)→PROCESSING. RAW_SN 매핑 부재로 jobId 가 SRC_SN 인 구 경로도
     * {@code findBySrcSnIn} 폴백으로 동일하게 집계한다. 집계 대상 row 가 전무하면 PROCESSING(대기)로 본다.
     *
     * @return "COMPLETED" | "FAILED" | "PROCESSING"
     */
    public String aggregateResultStatus(Long jobId) {
        if (jobId == null) {
            return "PROCESSING";
        }
        List<LsDataAug> group = repository.findByOriginalRawSn(jobId);
        if (group.isEmpty()) {
            // RAW_SN 매핑이 없어 jobId 가 SRC_SN 그대로 노출된 구 경로 폴백.
            group = repository.findBySrcSnIn(List.of(jobId));
        }
        if (group.isEmpty()) {
            return "PROCESSING";
        }
        return switch (aggregateStatus(group)) {
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case REQUESTED, IN_PROGRESS -> "PROCESSING";
        };
    }

    // ============================================================
    // 증강 잡 카드 그룹핑 (영상 단위 = 대표프레임 SRC_SN 그룹)
    // ============================================================

    /**
     * 대표프레임 SRC_SN 그룹 순서를 유지하며 잡 카드 목록을 구성한다.
     *
     * @param orderedSrcSns 표시 순서가 확정된 SRC_SN 목록 (1차 페이징 결과)
     * @param rows          orderedSrcSns 에 속하는 전체 증강 row (2차 일괄 조회 결과)
     */
    private List<AugmentJobResponse> buildJobs(List<Long> orderedSrcSns, List<LsDataAug> rows) {
        Map<Long, List<LsDataAug>> bySrc = new LinkedHashMap<>();
        for (LsDataAug row : rows) {
            bySrc.computeIfAbsent(row.getSrcSn(), k -> new ArrayList<>()).add(row);
        }

        // SRC_SN → RAW_SN 역매핑 (매핑 부재 시 폴백 없음 — 아래 videoId 산출에서 SRC_SN 폴백)
        Map<Long, Long> rawBySrc = loadRawSnBySrcSn(bySrc.keySet());
        // RAW_SN → CCTV 명 (매핑 부재/시드 없음 시 null)
        Map<Long, String> cctvByRaw = loadCctvNames(rawBySrc.values());
        // DATA_AUG_SN → 검수 완료 일시 (completedAt 산출용)
        Map<Long, LocalDateTime> reviewDtByAug = loadReviewDates(rows);

        List<AugmentJobResponse> jobs = new ArrayList<>(orderedSrcSns.size());
        for (Long srcSn : orderedSrcSns) {
            List<LsDataAug> group = bySrc.get(srcSn);
            if (group == null || group.isEmpty()) {
                continue;
            }
            jobs.add(toJob(srcSn, group, rawBySrc, cctvByRaw, reviewDtByAug));
        }
        return jobs;
    }

    private AugmentJobResponse toJob(Long srcSn, List<LsDataAug> group,
                                     Map<Long, Long> rawBySrc, Map<Long, String> cctvByRaw,
                                     Map<Long, LocalDateTime> reviewDtByAug) {
        // videoId/jobId = 원본 RAW_SN (매핑 부재 시 SRC_SN 폴백 — 데이터 유실 방지). jobId == videoId.
        Long rawSn = rawBySrc.get(srcSn);
        Long videoId = rawSn != null ? rawSn : srcSn;
        // FE AugmentJob.cctvName 은 비-옵셔널 String. RAW_SN 매핑/CCTV 시드 부재 시에도
        // null 을 반환하면 FE 검색/정렬의 null.toLowerCase() 크래시 위험 → 항상 non-null 이어야 한다.
        //
        // 폴백 판정은 CctvDisplayNamePolicy 단독 소유다. 구 상수 "(이름 없음)" 은 폐기 — 여러 행이
        // 전부 같은 문구가 되어 어느 영상인지 <b>식별조차 되지 않았고</b>, 같은 영상이 작업목록에서는
        // "영상 #N" 으로 보여 표기가 갈렸다. 폴백 근거는 화면이 실제로 쓰는 식별자(videoId)다 —
        // RAW_SN 매핑이 없으면 videoId 가 SRC_SN 폴백이라 그 값이 곧 사용자가 클릭·조회하는 키다.
        String cctvName = CctvDisplayNamePolicy.resolve(
                rawSn != null ? cctvByRaw.get(rawSn) : null, null, videoId);

        // 표시 타입은 검수 대상 증강(WINTER/NIGHT/RAIN 등)과 해상도 파생(RESL_ 접두)을 분리한다.
        // 해상도 파생은 검수 대상이 아니므로 resolutionTypes 로 별도 노출해 FE 가 accept/reject 버튼을
        // 숨기고 "해상도"로 라벨링한다. 단, 상태 집계에는 해상도 파생을 포함한다(아래 aggregateStatus 참조).
        List<String> types = sortedDistinctTypes(group.stream()
                .filter(a -> !isResolutionDerivative(a.getAugTypeCd()))
                .toList());
        List<String> resolutionTypes = sortedDistinctTypes(group.stream()
                .filter(a -> isResolutionDerivative(a.getAugTypeCd()))
                .toList());

        AugmentJobStatus status = aggregateStatus(group);

        LocalDateTime requestedAt = resolveRequestedAt(group);

        LocalDateTime completedAt = null;
        if (status == AugmentJobStatus.COMPLETED) {
            completedAt = group.stream()
                    .map(a -> reviewDtByAug.get(a.getDataAugSn()))
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(requestedAt); // 검수 일시 유실 시 요청 일시로 폴백(null 회피)
        }

        return new AugmentJobResponse(videoId, videoId, cctvName, types, resolutionTypes,
                status.name(), requestedAt, completedAt, 1);
    }

    /**
     * 영상(그룹)의 <b>요청일시</b> — 그 영상 증강 행들의 {@code MIN(REG_DT)}. 증강 행이 없거나 전부
     * 등록일시가 비어 있으면 {@code null}(지어내지 않는다).
     *
     * <p><b>판정 단일 원천이다.</b> 잡 카드 목록({@link AugmentJobResponse#requestedAt()})과 결과 조회
     * ({@code AugmentResultViewService} → {@code AugmentResultResponse.requestedAt})가 <b>같은 축</b>을
     * 말해야 하므로 이 메서드를 재사용한다 — 산출식을 복제하면 두 번째 진실원이 생겨 두 화면이 다른
     * 시각을 표시하게 된다.
     *
     * <p><b>결정 시각({@code decidedAt})과는 다른 축</b>이다. 요청일시는 "언제 만들어 달라고 했는가",
     * 결정 시각은 "REVIEWER 가 언제 채택·반려했는가" 이며 서로를 대체할 수 없다.
     *
     * <p>해상도 파생({@code RESL_*}) 행도 포함한다 — 목록의 집계 축과 동일하게 그 영상에 대한 모든
     * 증강·파생 행을 한 그룹으로 본다.
     *
     * @param group 한 영상(그룹)에 속한 증강 행 전체. {@code null}·빈 목록이면 {@code null} 을 돌려준다.
     */
    public static LocalDateTime resolveRequestedAt(List<LsDataAug> group) {
        if (group == null || group.isEmpty()) {
            return null;
        }
        return group.stream()
                .map(LsDataAug::getRegDt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** 증강 row 목록 → AUG_ORDER 순 distinct AUG_TYPE_CD 목록. */
    private List<String> sortedDistinctTypes(List<LsDataAug> rows) {
        return rows.stream()
                .map(LsDataAug::getAugTypeCd)
                .distinct()
                .sorted(Comparator.comparingInt(t -> AUG_ORDER.getOrDefault(t, 99)))
                .toList();
    }

    /** 해상도 파생 여부 — RESL_ 접두(검수 대상 아님, 저작도구 내부 생성물). */
    private boolean isResolutionDerivative(String augTypeCd) {
        return augTypeCd != null && augTypeCd.startsWith(LsDataAug.RESL_PREFIX);
    }

    /**
     * 그룹(영상)의 상태 집계 — {@link AugmentJobStatus} 규칙:
     * 처리 실패 존재 → FAILED, 전부 종료 → COMPLETED, 일부 종료 → IN_PROGRESS, 전부 PENDING → REQUESTED.
     *
     * <h3>실패 판정 축 (E-06 파생, Phase 8-B)</h3>
     * <p>{@code AUG_PROC_STTS_CD}(PENDING/ACCEPTED/REJECTED)는 <b>검수 결과 축</b>이고,
     * {@code LS_DATA_AUG_JOB.JOB_STTS_CD}(RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED)는 <b>외부 처리
     * 축</b>이다. 두 축은 서로 다른 코드 공간이므로 섞지 않는다. 그래서 실패 판정에 {@code REJECTED}
     * 문자열을 쓰지 않는다 — REVIEWER 의 정상 반려와 처리 실패 롤업이 같은 값이라 구분이 불가능하고,
     * 구 구현은 그 결과 <b>롤업으로 REJECTED 된 증강이 "전부 종료" 규칙에 걸려 COMPLETED 로 집계</b>됐다
     * (만료 스윕이 실제로 돌기 시작하면 실패한 증강이 화면·통계에서 "완료" 로 보인다).
     *
     * <p>대신 <b>처리 실패 전용 마커</b>인 {@code DEAD_LETTER_AT}({@link LsDataAug#isProcessingFailed()})
     * 하나를 축으로 삼는다. 이 마커는 {@code AugmentResultService} 의 실패 인계(웹훅 롤업·만료 스윕
     * 롤업·위탁 0건 롤업)에서만 찍히므로, 검수 반려는 COMPLETED 로 남고 처리 실패는 FAILED 로 드러난다.
     *
     * <p><b>입력은 그룹의 전체 row(RESL_ 해상도 파생 포함)</b> 이다. 해상도 변경 파생도 증강 집계/통계
     * 카운트에 포함한다(운영 결정 — 통계에 해상도 반영). <b>해상도 파생 aug 상태는 파생 생성 라이프사이클과
     * 일치</b>한다: 예약~확정 사이 in-flight 창에서는 PENDING(생성 중, non-terminal)이라 COMPLETED 로 세지
     * 않고 REQUESTED/IN_PROGRESS 로 집계된다. finalize 성공 확정 시에만
     * ({@code LsDataAug.markResolutionGenerated}) ACCEPTED(terminal)로 전이되어 COMPLETED 근거가 된다.
     * 확정에 실패한 파생은 {@code releaseReservedAug} 로 예약 aug 행이 삭제되므로 집계에서 사라진다.
     * 따라서 ACCEPTED 로 남는 RESL_ 행은 실제 생성 완료된 파생뿐이며, 해상도 파생만 존재하는 그룹은 전부
     * 확정된 뒤에야 COMPLETED(파생 생성 완료)로 집계된다.
     *
     * <p>화면 구분은 별개로 유지된다 — {@link AugmentJobResponse#resolutionTypes()} 로 해상도 파생을
     * 분리 노출해 FE 가 accept/reject 를 숨긴다({@link #loadOrThrow} 가 RESL_ 검수 진입도 차단).
     */
    private AugmentJobStatus aggregateStatus(List<LsDataAug> group) {
        boolean anyFailed = group.stream().anyMatch(LsDataAug::isProcessingFailed);
        if (anyFailed) {
            return AugmentJobStatus.FAILED;
        }
        long terminal = group.stream().filter(a -> isTerminal(a.getAugProcSttsCd())).count();
        if (terminal == group.size()) {
            return AugmentJobStatus.COMPLETED;
        }
        if (terminal > 0) {
            return AugmentJobStatus.IN_PROGRESS;
        }
        return AugmentJobStatus.REQUESTED;
    }

    /**
     * 종료 상태 여부 — 판정은 {@link LsDataAug#isTerminalStatus(String)} <b>단일 원천</b>에 위임한다.
     *
     * <p>구 구현은 여기서 문자열 두 개를 직접 비교했다. 그 상태로 {@code CANCELED}(2026-07-31 신설)를
     * 추가하면 <b>취소된 증강이 든 영상 그룹이 영원히 REQUESTED/IN_PROGRESS 로 표시</b>된다 —
     * "전부 종결 → COMPLETED" 규칙에 취소가 종결로 안 잡히기 때문이다. 집계 <b>규칙</b>은 그대로 두고
     * (새 집계 로직 없음, {@code AugmentJobStatus} 확장 없음) 종결 판정만 한 곳으로 모은다.
     */
    private boolean isTerminal(String augProcSttsCd) {
        return LsDataAug.isTerminalStatus(augProcSttsCd);
    }

    /** SRC_SN → RAW_SN 역매핑 일괄 조회. */
    private Map<Long, Long> loadRawSnBySrcSn(Collection<Long> srcSns) {
        Map<Long, Long> result = new HashMap<>();
        if (srcSns.isEmpty()) {
            return result;
        }
        for (Object[] row : srcRepository.findRawSnBySrcSnIn(srcSns)) {
            result.put((Long) row[0], (Long) row[1]);
        }
        return result;
    }

    /**
     * RAW_SN → 표시명 일괄 조회. 1·2순위 접기도 {@link CctvDisplayNamePolicy} 가 판정한다(복제 금지).
     * 조회되지 않은 영상만 키가 비고, 그 경우도 {@link #toJob} 에서 같은 판정기가 처리한다.
     */
    private Map<Long, String> loadCctvNames(Collection<Long> rawSns) {
        Map<Long, String> result = new HashMap<>();
        Set<Long> distinct = new java.util.HashSet<>(rawSns);
        if (distinct.isEmpty()) {
            return result;
        }
        for (Object[] row : videoRepository.findCctvNamesByRawSns(distinct)) {
            Long rawSn = (Long) row[0];
            String cctvNm = (String) row[1];
            String vmsCctvId = (String) row[2];
            result.put(rawSn, CctvDisplayNamePolicy.resolve(cctvNm, vmsCctvId, rawSn));
        }
        return result;
    }

    /** DATA_AUG_SN → 최신 검수 일시(RVW_DT) 일괄 조회 (completedAt 산출용). */
    private Map<Long, LocalDateTime> loadReviewDates(List<LsDataAug> rows) {
        Map<Long, LocalDateTime> result = new HashMap<>();
        List<Long> augSns = rows.stream().map(LsDataAug::getDataAugSn).toList();
        if (augSns.isEmpty()) {
            return result;
        }
        for (LsDataAugRvw rvw : reviewRepository.findByDataAugSnIn(augSns)) {
            LocalDateTime rvwDt = rvw.getRvwDt();
            if (rvwDt == null) {
                continue;
            }
            result.merge(rvw.getDataAugSn(), rvwDt,
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        return result;
    }

    /**
     * REVIEWER 가 증강 결과를 <b>사용</b>하기로 결정한다 — {@code LS_DATA_AUG_RVW.RVW_STTS_CD}
     * PENDING → ACCEPTED.
     *
     * <h3>★ 생성 결과 컬럼({@code AUG_PROC_STTS_CD})은 건드리지 않는다 (2026-07-31 확정)</h3>
     * <p>구 구현은 여기서도 {@code aug.applyReviewStatus(...)} 를 불러 <b>한 컬럼에 두 주체</b>가 썼다.
     * 그 컬럼은 PENDING 에서만 전이를 허용하는데 외부 증강은 <b>웹훅이 항상 먼저</b> 도착해 PENDING 을
     * 소진하므로, REVIEWER 의 승인·반려는 그 뒤에 <b>영구히 409</b> 로 막혔다(사용/폐기 워크플로가
     * 도달 불가). 지금은 축이 갈라져 있다 — 생성 결과는 생성기(웹훅/finalize)가, <b>사람의 사용/폐기
     * 결정은 검수 행이 단독으로</b> 소유한다. 두 축을 다시 합치지 말 것
     * ({@link LsDataAug#applyGenerationResult(String)} javadoc 참조).
     *
     * <p>재결정 차단(멱등)은 검수 행 자신의 {@code ensurePending} 가드가 계속 담당한다 — 이미 결정된
     * 증강을 다시 승인/반려하면 여전히 409 다.
     *
     * <p><b>사전조건</b>: 결정은 <b>결과물이 실재할 때만</b> 가능하다({@link #requireGeneratedResult}).
     * 축 분리로 사라졌던 "생성 전에는 결정 불가" 불변식을 명시 가드로 되세운 것이며, 이것이 없으면
     * 등재 게이트가 무력화된다.
     */
    @Transactional("controlTransactionManager")
    public AugmentSummaryResponse accept(Long dataAugSn, TokenClaims actor) {
        requireReviewer(actor);
        LsDataAug aug = loadOrThrow(dataAugSn);
        LsDataAugRvw review = loadOrCreateReview(aug, actor.sub());
        review.accept(actor.sub(), LocalDateTime.now());
        // 외부 통보 (best-effort — 실패해도 본 트랜잭션 영향 없음)
        try {
            externalClient.syncDecision(dataAugSn, LsDataAug.STTS_ACCEPTED, null);
        } catch (Exception e) {
            log.warn("[Augment] external sync failed dataAugSn={} decision=ACCEPTED err={}",
                    dataAugSn, sanitize(e.getMessage()));
        }
        log.info("[Augment] accepted dataAugSn={} actor={}", dataAugSn, sanitize(actor.sub()));
        return AugmentSummaryResponse.from(aug, review);
    }

    /**
     * REVIEWER 가 증강 결과를 <b>폐기</b>하기로 결정한다 — {@code LS_DATA_AUG_RVW.RVW_STTS_CD}
     * PENDING → REJECTED. 사유 필수.
     *
     * <p>{@link #accept} 와 동일하게 생성 결과 컬럼({@code AUG_PROC_STTS_CD})은 건드리지 않는다
     * (축 분리 — 위 javadoc 참조).
     *
     * <h3>Phase 7 — 반려는 <b>폐기 표식</b>을 남긴다</h3>
     * <p>반려 즉시 파생영상이 목록·배정에서 빠지는 것은 등재 게이트가 이미 보장한다. 여기서 추가로
     * {@code LS_DATA_AUG_DSCD} 에 표식을 남겨 <b>유예(기본 7일) 후 실삭제</b>와 <b>유예 내 복구</b>가
     * 추적되게 한다. 표식 기록은 같은 트랜잭션에 참여하므로 반려가 롤백되면 표식도 남지 않는다.
     */
    @Transactional("controlTransactionManager")
    public AugmentSummaryResponse reject(Long dataAugSn, String reason, TokenClaims actor) {
        requireReviewer(actor);
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        LsDataAug aug = loadOrThrow(dataAugSn);
        LsDataAugRvw review = loadOrCreateReview(aug, actor.sub());
        review.reject(reason, actor.sub(), LocalDateTime.now());
        discardService.markDiscarded(aug, reason, actor.sub());
        try {
            externalClient.syncDecision(dataAugSn, LsDataAug.STTS_REJECTED, reason);
        } catch (Exception e) {
            log.warn("[Augment] external sync failed dataAugSn={} decision=REJECTED err={}",
                    dataAugSn, sanitize(e.getMessage()));
        }
        log.info("[Augment] rejected dataAugSn={} actor={} reasonLen={}",
                dataAugSn, sanitize(actor.sub()), reason.length());
        return AugmentSummaryResponse.from(aug, review);
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }

    private LsDataAugRvw loadOrCreateReview(LsDataAug aug, String actorId) {
        // 기존 검수 row 가 있으면 그 row 의 DATA_RAW_SN 은 이미 유효하다(FK 통과분) → 역해석 불필요.
        return reviewRepository.findLatestByDataAugSn(aug.getDataAugSn())
                .orElseGet(() -> reviewRepository.save(
                        LsDataAugRvw.pending(aug.getDataAugSn(), resolveRawSnOrThrow(aug), aug.getSrcSn(),
                                aug.getLblIntgrtPct(), actorId)));
    }

    /**
     * 검수 이력({@code LS_DATA_AUG_RVW.DATA_RAW_SN})에 적을 원본 영상 RAW_SN 을 대표프레임
     * (SRC_SN)에서 역해석한다. <b>해석 실패는 센티널({@code 0L}) 저장이 아니라 명시 거부</b>다.
     *
     * <p>왜 거부인가: 이 컬럼은 DB {@code NOT NULL} 이고 V146 부터 {@code LS_DATA_RAW} FK 를 갖는다.
     * 구 구현은 하드코딩 {@code 0L} 을 넣어 "존재하지 않는 영상" 을 참조했다 — FK 이전에는 어느 영상의
     * 검수 이력인지 알 수 없는 행이 조용히 쌓였고, FK 이후에는 INSERT 거부로 accept/reject 가 500 이 된다.
     *
     * <p>도달 경로: {@code LS_DATA_AUG.SRC_SN} 에는 프레임 FK 가 없어(본 이슈 범위 밖) 영상·프레임이
     * 사라진 뒤에도 증강 행이 남아 SRC_SN 이 붕 뜬다. 이는 사용자 입력 오류가 아니라 <b>데이터 정합
     * 충돌</b>이므로 409 CONFLICT 로 알린다(내부 경로·스택은 노출하지 않는다 — CWE-209).
     */
    private Long resolveRawSnOrThrow(LsDataAug aug) {
        Long srcSn = aug.getSrcSn();
        Long rawSn = srcSn == null ? null : loadRawSnBySrcSn(List.of(srcSn)).get(srcSn);
        if (rawSn == null) {
            log.warn("[Augment] review blocked — rawSn unresolved dataAugSn={} srcSn={}",
                    aug.getDataAugSn(), srcSn);
            throw new CustomException(ErrorCode.CONFLICT,
                    "증강 결과의 원본 영상 정보를 확인할 수 없어 검수를 기록할 수 없습니다.");
        }
        return rawSn;
    }

    /**
     * 결정 대상 증강 행을 <b>행 잠금(FOR UPDATE)</b>으로 읽는다 — accept/reject 직렬화 (DEV_FIX MEDIUM).
     *
     * <h3>왜 잠가야 하는가</h3>
     * <p>결정 기록은 {@code findLatestByDataAugSn} → (없으면) {@code save} 의 <b>read-then-act</b> 다.
     * 그런데 {@code LS_DATA_AUG_RVW} 에는 {@code DATA_AUG_SN} 유니크가 <b>없다</b>(V25 는 비유니크
     * 인덱스뿐). 그래서 같은 증강에 accept 와 reject 가 동시에 들어오면 둘 다 "검수 행 없음" 을
     * 관측해 <b>각자 새 행을 INSERT</b> 하고 {@code ensurePending} 가드도 각자 통과한다. 그 결과:
     * <ul>
     *   <li>등재 게이트({@code DerivativeWorkEligibility})는 {@code EXISTS(RVW_STTS_CD='ACCEPTED')}
     *       라 <b>등재</b>로 보고,</li>
     *   <li>결과 화면은 최신 1행({@code REG_DT DESC})만 읽어 <b>거부됨</b>으로 보인다.</li>
     * </ul>
     * "사람은 거부했는데 파생이 작업목록에 올라간다" 가 성립하고, 재결정은 409 로 막혀 화면에서
     * 되돌릴 수단도 없다.
     *
     * <p><b>왜 유니크 인덱스가 아니라 행 잠금인가</b>: 유니크 추가는 <b>기존 중복 행 선정리</b>를
     * 전제하는데 이 프로젝트는 백필·데이터 정리 마이그레이션을 금지한다. 잠금은 스키마 변경 없이
     * 같은 직렬화를 얻고, 웹훅 경로가 <b>이미 쓰는 잠금</b>({@code findByDataAugSnForUpdate})을 그대로
     * 재사용하므로 락 순서(증강 행 → 부모 RAW)도 유지된다(데드락 없음).
     */
    private LsDataAug loadOrThrow(Long dataAugSn) {
        LsDataAug aug = repository.findByDataAugSnForUpdate(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));
        // 해상도 파생(RESL_ 접두)은 저작도구 내부 생성물로, 예약 시 PENDING(생성 중)으로 적재되고
        // finalize 성공 확정 시 ACCEPTED(생성 완료)로 전이되는 내부 라이프사이클을 가지며 외부 검수 대상이 아니다.
        // accept/reject 진입 자체를 명시 차단(CONFLICT 보다 명확한 INVALID_INPUT — 화면 오조작 방어).
        if (aug.getAugTypeCd() != null && aug.getAugTypeCd().startsWith(LsDataAug.RESL_PREFIX)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "해상도 파생 결과는 검수 대상이 아닙니다.");
        }
        requireGeneratedResult(aug);
        return aug;
    }

    /**
     * <b>결과물이 실재할 때만 결정할 수 있다</b> — accept/reject 공통 사전조건 (2026-07-31 DEV_FIX HIGH).
     *
     * <h3>이 가드가 없으면 게이트가 무력화된다</h3>
     * <p>파생영상 등재 게이트({@code DerivativeWorkEligibility})는 "그 증강 행에 {@code ACCEPTED}
     * 검수가 있는가" 만 본다. 그런데 결정을 <b>결과물보다 먼저</b> 내릴 수 있으면 순서가 이렇게 된다:
     * 요청 → (결과물 0건 상태에서) 승인 → 콜백 도착 → 파생영상 생성 → 게이트 통과. 사람이 본 것은
     * "요청" 뿐인데 파생영상이 작업목록에 올라가고 배정된다. 상위 요구("증강 결과에서 <b>이미지를
     * 비교해 보고</b> 사용 여부를 선택해야 작업목록에 올라간다")를 정면으로 깨뜨린다.
     *
     * <p>구 구현에서는 {@code applyReviewStatus} 의 PENDING 요구가 이 역할을 <b>부수적으로</b>
     * 수행했다. 축을 가르며 그 호출을 걷어냈으므로 <b>대체 가드를 명시적으로</b> 세운다. 판정은
     * {@link LsDataAug#isGenerationSucceeded()} 단일 원천에 위임한다 — 화면의 {@code reviewable}
     * 도 같은 판정을 쓰므로 "버튼은 보이는데 누르면 거부" 가 구조적으로 생기지 않는다
     * ({@code AugmentResultViewService.toExternalItems}).
     *
     * <h3>사유를 구분해 알린다 (새 에러코드 신설 없음)</h3>
     * <p>둘 다 "지금 상태에서는 그 전이가 불가" 이므로 {@link ErrorCode#CONFLICT}(409) 계열을
     * 재사용한다(이미 결정된 증강의 재결정 409 와 같은 가족). 다만 메시지로 사유를 가른다 —
     * <b>아직 생성 중</b>이면 기다렸다 다시 시도하면 되고, <b>실패/취소로 종결</b>됐으면 기다려도
     * 결과물이 생기지 않아 재요청이 유일한 동선이기 때문이다.
     */
    private void requireGeneratedResult(LsDataAug aug) {
        if (aug.isGenerationSucceeded()) {
            return;
        }
        // 실패 축(dead-letter)을 먼저 본다 — 확정이 영구 실패한 행은 상태가 ACCEPTED/PENDING 으로
        // 남아 있을 수 있어(AugmentExtractPersist.markAugProcessingFailed 는 상태를 건드리지 않는다)
        // 상태만 보면 "곧 생성됩니다" 라는 사실과 다른 안내가 나간다.
        if (!aug.isProcessingFailed() && aug.isGenerationInProgress()) {
            log.info("[Augment] decision blocked — result not generated yet dataAugSn={}",
                    aug.getDataAugSn());
            throw new CustomException(ErrorCode.CONFLICT,
                    "증강 결과가 아직 생성되지 않았습니다. 생성이 완료된 뒤에 사용 여부를 결정할 수 있습니다.");
        }
        log.info("[Augment] decision blocked — generation not successful dataAugSn={} status={}",
                aug.getDataAugSn(), sanitize(aug.getAugProcSttsCd()));
        throw new CustomException(ErrorCode.CONFLICT,
                "생성에 실패했거나 취소된 증강은 사용 여부 결정 대상이 아닙니다. 필요하면 다시 요청해 주세요.");
    }

    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // 「검수자 전용」은 「검수자 이상」이다 — 관리자가 계층으로 물려받아 증강 결과 채택/반려에 들어간다.
        // Spring 의 RoleHierarchy 는 권한(authority) 축에만 걸리므로 여기서 역할을 동등 비교하면
        // 관리자가 이 창구에서만 403 이 되어 계층이 반쪽만 성립한다.
        // [design: ADR-055] [design: ROLE-004] [design: AC-125]
        if (!actor.hasRole(Role.REVIEWER)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }
}
