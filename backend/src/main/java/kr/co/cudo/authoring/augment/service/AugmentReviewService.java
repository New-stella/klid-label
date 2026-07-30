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
 * <p>신규 외부 콜백은 3종(WINTER/NIGHT/RAIN)만 수신한다(RESOLUTION은 RQ-SFR-06-03에 따라
 * 저작도구 내부 기능으로 분리됨). 단, 기존에 적재된 RESOLUTION 데이터의 조회·정렬·검수를 위해
 * {@code AUG_ORDER}는 4종 정렬을 유지한다.
 *
 * <p>RBAC: 모든 결정 메서드는 REVIEWER 만 호출 가능 (Service 이중 검증).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentReviewService {

    /**
     * UI 표시용 정렬 순서. 검수 대상 증강 3종(WINTER/NIGHT/RAIN) 뒤에 해상도 파생 프리셋
     * (RESL_1080P/720P/480P, 고해상도→저해상도)을 배치한다. 레거시 단일 RESOLUTION 은 기존 데이터 정렬용.
     */
    private static final Map<String, Integer> AUG_ORDER = Map.of(
            LsDataAug.AUG_WINTER, 1,
            LsDataAug.AUG_NIGHT, 2,
            LsDataAug.AUG_RAIN, 3,
            LsDataAug.AUG_RESOLUTION, 4,
            LsDataAug.AUG_RESL_1080P, 5,
            LsDataAug.AUG_RESL_720P, 6,
            LsDataAug.AUG_RESL_480P, 7
    );

    private final LsDataAugRepository repository;
    private final LsDataAugRvwRepository reviewRepository;
    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final ExternalAugmentClient externalClient;

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
        // null 을 반환하면 FE 검색/정렬의 null.toLowerCase() 크래시 위험 → 안전 폴백으로 항상 non-null.
        String cctvName = resolveCctvName(rawSn, cctvByRaw);

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

        LocalDateTime requestedAt = group.stream()
                .map(LsDataAug::getRegDt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

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

    /** 종료(검수 완료) 상태 여부 — AUG_PROC_STTS_CD = ACCEPTED/REJECTED. */
    private boolean isTerminal(String augProcSttsCd) {
        return LsDataAug.STTS_ACCEPTED.equals(augProcSttsCd)
                || LsDataAug.STTS_REJECTED.equals(augProcSttsCd);
    }

    /** CCTV 명 폴백값 — RAW_SN 매핑/CCTV 시드 부재 시 FE 비-옵셔널 계약 보호용. */
    private static final String CCTV_NAME_FALLBACK = "(이름 없음)";

    /** CCTV 명 산출 — 매핑/시드 부재 또는 blank 시 항상 non-null 폴백 반환. */
    private String resolveCctvName(Long rawSn, Map<Long, String> cctvByRaw) {
        String name = rawSn != null ? cctvByRaw.get(rawSn) : null;
        return (name != null && !name.isBlank()) ? name : CCTV_NAME_FALLBACK;
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

    /** RAW_SN → CCTV 명 일괄 조회 (cctvNm null/blank 시 vmsCctvId 폴백). */
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
            String name = (cctvNm != null && !cctvNm.isBlank()) ? cctvNm : vmsCctvId;
            result.put(rawSn, name);
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
     * REVIEWER 가 증강 결과를 승인. PENDING → ACCEPTED.
     * LS_DATA_AUG_RVW row INSERT/갱신 + LsDataAug.augProcSttsCd 동기 갱신 (DB 설계서 라인 162-169 호환).
     */
    @Transactional("controlTransactionManager")
    public AugmentSummaryResponse accept(Long dataAugSn, TokenClaims actor) {
        requireReviewer(actor);
        LsDataAug aug = loadOrThrow(dataAugSn);
        // 1) LsDataAug.augProcSttsCd 갱신 (DB 설계서 호환 — PENDING 이외면 CONFLICT)
        aug.applyReviewStatus(LsDataAug.STTS_ACCEPTED);
        // 2) LS_DATA_AUG_RVW row INSERT/갱신
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
     * REVIEWER 가 증강 결과를 반려. PENDING → REJECTED. 사유 필수.
     * LS_DATA_AUG_RVW row INSERT/갱신 + LsDataAug.augProcSttsCd 동기 갱신.
     */
    @Transactional("controlTransactionManager")
    public AugmentSummaryResponse reject(Long dataAugSn, String reason, TokenClaims actor) {
        requireReviewer(actor);
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        LsDataAug aug = loadOrThrow(dataAugSn);
        // 1) LsDataAug.augProcSttsCd 갱신 (DB 설계서 호환 — PENDING 이외면 CONFLICT)
        aug.applyReviewStatus(LsDataAug.STTS_REJECTED);
        // 2) LS_DATA_AUG_RVW row INSERT/갱신
        LsDataAugRvw review = loadOrCreateReview(aug, actor.sub());
        review.reject(reason, actor.sub(), LocalDateTime.now());
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

    private LsDataAug loadOrThrow(Long dataAugSn) {
        LsDataAug aug = repository.findById(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));
        // 해상도 파생(RESL_ 접두)은 저작도구 내부 생성물로, 예약 시 PENDING(생성 중)으로 적재되고
        // finalize 성공 확정 시 ACCEPTED(생성 완료)로 전이되는 내부 라이프사이클을 가지며 외부 검수 대상이 아니다.
        // accept/reject 진입 자체를 명시 차단(CONFLICT 보다 명확한 INVALID_INPUT — 화면 오조작 방어).
        if (aug.getAugTypeCd() != null && aug.getAugTypeCd().startsWith(LsDataAug.RESL_PREFIX)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "해상도 파생 결과는 검수 대상이 아닙니다.");
        }
        return aug;
    }

    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }
}
