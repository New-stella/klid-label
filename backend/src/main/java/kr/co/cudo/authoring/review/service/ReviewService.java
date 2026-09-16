package kr.co.cudo.authoring.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.domain.ReviewClaim;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.ReviewClaimSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.review.dto.FrameDetailResponse;
import kr.co.cudo.authoring.review.dto.FrameListResponse;
import kr.co.cudo.authoring.review.dto.IssueResponse;
import kr.co.cudo.authoring.review.dto.ApproveRequest;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.review.dto.ReviewClaimConflict;
import kr.co.cudo.authoring.review.dto.ReviewListPage;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.review.dto.ReviewSearchCondition;
import kr.co.cudo.authoring.review.dto.ReviewSummaryResponse;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.repository.ReviewQueryRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.dto.CctvDisplayNamePolicy;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.controlnotify.debounce.ControlNotifyDebounceStore;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.meta.service.MetaService;
import kr.co.cudo.authoring.version.service.VersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import kr.co.cudo.authoring.common.config.PublicApiPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 7 — 검수 워크플로우 (REVIEWER 승인/반려).
 *
 * <p>RBAC:
 * <ul>
 *   <li>submit / cancelSubmit            : <b>WORKER 전용</b> (본인 배정 영상만) — 관리자·검수자도 못 한다</li>
 *   <li>startReview / approve / reject   : REVIEWER 이상 (관리자 포함 · 현재 정책: 모든 영상 가능)</li>
 * </ul>
 *
 * <p><b>역할 판정은 동등 비교가 아니라 {@link TokenClaims#hasRole(Role)} 이다.</b>
 * Spring 의 {@code RoleHierarchy} 는 권한(authority) 축에만 걸리므로, 이 서비스가 역할을 그대로
 * 동등 비교하면 관리자가 검수 목록·상세·승인·반려에서 거부되어 계층이 반쪽만 성립한다. 창구의
 * 「검수자 전용」은 「검수자 이상」으로 읽는다. [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <p>⚠ <b>예외 — 검수 제출({@link #verifyAssignedWorker})은 작업자 전용이라 계층을 타지 않는다.</b>
 * 그 자리는 동등 비교를 <b>의도적으로 유지</b>한다(자세한 근거는 해당 메서드 javadoc).
 *
 * <p>동시성: LS_RAW_DATA_STATUS 의 {@code @Version} 컬럼으로 낙관적 잠금. 동시 두 REVIEWER 가
 * 같은 영상을 승인 시도할 때 1건만 성공 → 다른 1건은 {@link ErrorCode#CONFLICT}.
 *
 * <p><b>비식별 누락 신고 게이트는 {@link #approve} 에만 적용한다</b>(412, 역할 무관). 승인은 학습데이터를
 * <b>확정</b>해 동결·산출·관제 통지로 내보내는 지점이라 신고 구간에 통과시키면 마스킹 실패분이 검수
 * 통과분으로 나간다. 반면 {@code submit}/{@code startReview}/{@code reject} 는 내보내는 행위가 아니라
 * 워크플로 되돌림·진행이라 막으면 신고 구간 영상이 검수 큐에 고착된다 — 의도적으로 열어 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class ReviewService {

    /**
     * 브라우저가 우리 API 를 부를 때 쓰는 경로 접두어 — 해석은 {@link PublicApiPath} 한 곳이 소유한다.
     *
     * <p>기본은 <b>WAR 웹 컨텍스트에서 자동 도출</b>이라 배포마다 설정을 넣지 않아도 맞는다.
     * ⚠ 초기값을 지우지 말 것 — 단위 시험은 이 서비스를 생성자로 직접 만들어 주입이 없다.
     */
    @Autowired(required = false)
    private PublicApiPath publicApiPath = PublicApiPath.ofDefault();

    private final ReviewRepository reviewRepository;
    /** 검수목록 검색/정렬/집계 — 목록·count·KPI 가 단일 조건 조립기를 공유한다. */
    private final ReviewQueryRepository reviewQueryRepository;
    private final IssueRepository issueRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    private final LsTaskEventLogRepository taskEventLogRepository;
    private final ReviewStateMachine stateMachine;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final VideoRepository videoRepository;
    private final UserNameResolver userNameResolver;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    /** 검수 승인 시점에 영상 전체 학습데이터 버전 스냅샷(LS_LABEL_VERSION, SAVE_REASON=APPROVED)을 생성. */
    private final VersionService versionService;
    /** 검수 승인 시점에 영상 메타를 통합 테이블(LS_DATASET_VIDEO_META)에 동결 적재(포털향 materialize). */
    private final DatasetVideoMetaSnapshotService datasetVideoMetaSnapshotService;
    /** 검수 승인 시점에 해당 영상의 event_annotation 검토를 자동 확정(APPROVED)해 export 동결 누락을 막는다. */
    private final EvntAnnoReviewService evntAnnoReviewService;
    /** 검수 승인 시점에 해당 영상의 시계열 메타 검토행(LS_DATA_META_REVIEW)을 자동 확정해 V_COMPLETED_META 누락을 막는다. */
    private final MetaService metaService;
    /**
     * 비식별 누락 신고 구간 차단(412) 관용구 — 판정은 {@code DeidentReportGate} 단일 원천에 위임한다
     * ({@link LabelAccessGuard#requireNotUnderDeidentReport}). 이 서비스의 인가는 {@link #requireReviewer} 등이
     * 따로 수행하므로 가드의 인가 메서드는 쓰지 않는다.
     */
    private final LabelAccessGuard accessGuard;
    /**
     * Phase 7a-2b — 재승인 폴백 판정 전용. 재검토 표시(REVLT_YN='Y')가 선 뒤 재승인이 들어왔을 때,
     * 그 표시로 축적된 디바운스 윈도우가 실제로 있는지 확인한다({@link #approve(Long, ApproveRequest, TokenClaims)}).
     */
    private final ControlNotifyDebounceStore controlNotifyDebounceStore;
    /**
     * 검수 점유 조회 <b>단일 창구</b> — 「지금 누가 이 영상을 보고 있는가」의 판정은 이 창구가 소유한다.
     *
     * <p>★판정 규칙을 이 서비스에 복제하지 않는다. 복제하면 <b>목록 표시</b>와 <b>검수 시작 거절</b>이
     * 서로 다른 답을 내어, 화면에는 비어 보이는데 눌러 보면 남이 잡고 있다고 하는 상태가 된다.
     * 유예 설정값과 판정 기준 시각도 그 창구가 가진다({@code ADR-067}).
     */
    private final ReviewClaimSupport reviewClaimSupport;
    /** 일괄 승인 건수 상한 — 목록 응답이 화면에 미리 알려 주는 값의 <b>단일 소유자</b>. */
    private final ReviewBatchApprovePolicy batchApprovePolicy;

    /**
     * 검수 워크플로우 목록 (REVIEWER 의 검수 목록 화면용) — 상태/검색어 필터 + 정렬.
     *
     * <p>필터·정렬·페이징은 {@link ReviewQueryRepository} 가 <b>DB 단계에서</b> 처리한다(목록/count 동일
     * 조건). 본 메서드는 그 결과 페이지를 화면 표시용으로 enrich 하는 책임만 갖는다 — 여기서 검색어를
     * 후처리하면 반환 건수와 {@code totalElements} 가 동시에 깨진다(HIGH-1).
     *
     * <p>{@code status} 가 null/빈 문자열이면 화이트리스트 전체, 화이트리스트 밖 값이면 빈 결과다(R8).
     *
     * <h3>점유·최근 승인자·일괄 승인 자격 (ADR-067 — 표시 전용 추가)</h3>
     * 세 축을 더하되 <b>조건 조립에는 손대지 않는다</b> — 검수 목록은 배정과 무관하게 검수 대기 전체를
     * 보여주며, 「내가 검수 중인 것만 보기」 같은 사용자 축 필터를 두지 않는다. 점유는 <b>표시이지
     * 필터가 아니다</b>.
     *
     * <p>★조달은 <b>페이지 단위 일괄 조회</b>다. 행마다 점유를 뒤지면 한 화면에 수십 건이 뜨는 이
     * 목록에서 그대로 N+1 이 된다 — 그 성질은 쿼리 수 계측으로 고정돼 있다.
     *
     * @design API-008
     */
    public ReviewListPage list(ReviewSearchCondition condition, Pageable pageable, TokenClaims actor) {
        requireReviewer(actor);
        ReviewSearchCondition effective = (condition != null) ? condition : ReviewSearchCondition.defaults();
        Page<LsRawDataStatus> page = reviewQueryRepository.search(effective, pageable);
        List<LsRawDataStatus> rows = page.getContent();
        int bulkApproveLimit = batchApprovePolicy.limit();
        if (rows.isEmpty()) {
            // 행이 없어도 상한은 싣는다 — 화면이 전건 필터로 비운 뒤 다시 채웠을 때 값을 잃지 않게.
            return new ReviewListPage(Collections.emptyList(), pageable,
                    page.getTotalElements(), bulkApproveLimit);
        }

        // 페이지의 영상 ID 집합 (N+1 회피용 batch lookup 키)
        List<Long> videoIds = rows.stream()
                .map(LsRawDataStatus::getRawDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        // 1) CCTV 명 lookup — LS_DATA_RAW ← 관제 인입 평면값(단일 native 쿼리)
        Map<Long, String> cctvNameMap = lookupCctvNames(videoIds);

        // 2) LABELER 배정 lookup — REG_DT DESC, rawDataId → userNo (단일 IN 쿼리)
        Map<Long, Long> workerIdMap = lookupLabelerByVideo(videoIds);

        // 3) 영상별 라벨 총개수 lookup — LS_DATA_LBL JOIN LS_DATA_SRC GROUP BY rawSn (단일 IN 쿼리)
        Map<Long, Long> labelCountMap = lookupLabelCountByVideo(videoIds);

        // 4) 영상별 이벤트 메타 lookup — LS_DATA_RAW.EVNT_TYPE_CD (단일 IN 쿼리)
        Map<Long, String[]> eventInfoMap = lookupEventByVideo(videoIds);

        // 5) 검수 점유 lookup — 페이지 전체를 <b>조회 한 번</b>에 판정한다(ADR-067).
        //    ★행마다 claimOf 를 부르면 그 순간 N+1 이다. 회귀 가드는 ReviewClaimBatchApproveIT 의
        //      쿼리 수 계측(ThreadScopedQueryProbe)이며, 페이지 크기를 키워도 쿼리가 늘지 않음을 고정한다.
        Map<Long, ReviewClaim> claimMap = reviewClaimSupport.claimsOf(videoIds);

        // 6) 최근 승인자 lookup — 영상별 마지막 승인 이벤트 1건씩 (단일 쿼리)
        Map<Long, LsTaskEventLog> lastApprovalMap = lookupLastApprovalByVideo(videoIds);

        // 7) 사용자 이름 lookup — 작업자·점유자·승인자를 <b>한 번에</b> 모아 단일 IN 쿼리로 해석한다.
        //    세 축을 따로 부르면 이름 조회만 3회가 된다.
        UserNameResolver.UserNames userNames = lookupUserNames(
                collectUserNos(workerIdMap.values(), claimMap, lastApprovalMap));

        // 「이 요청을 보낸 사람」 — 일괄 승인 자격이 요청자에 따라 갈리는 유일한 축이다.
        //   ⚠ 조회 경로라 사번을 해석하지 못해도 예외를 던지지 않는다(자격만 false 로 떨어진다).
        Long selfNo = parseUserNoOrNull(actor.sub());

        List<ReviewResponse> content = rows.stream()
                .map(stts -> {
                    Long videoId = stts.getRawDataId();
                    String cctvName = cctvNameMap.get(videoId);
                    Long workerId = workerIdMap.get(videoId);
                    String workerName = userNames.nameOf(workerId);
                    Long labelCount = labelCountMap.getOrDefault(videoId, 0L);
                    String[] eventInfo = eventInfoMap.get(videoId);
                    String eventName = (eventInfo != null) ? eventInfo[0] : null;
                    String eventTypeCd = (eventInfo != null) ? eventInfo[1] : null;
                    ReviewClaim claim = claimMap.get(videoId);
                    return ReviewResponse.from(stts, cctvName, workerId, workerName, labelCount,
                            eventName, eventTypeCd,
                            toReviewing(claim, userNames),
                            toLastApproval(lastApprovalMap.get(videoId), userNames),
                            isBulkApprovable(stts, claim, selfNo));
                })
                .toList();
        return new ReviewListPage(content, pageable, page.getTotalElements(), bulkApproveLimit);
    }

    /**
     * <b>일괄 승인 자격</b> — ①유효 점유의 주인이 나이고 ②단건 승인이 그 상태를 받아들이는가.
     *
     * <p>②의 판정은 {@link ReviewStateMachine#allowsApproval} 에 위임한다. 여기서 상태를 직접 비교하면
     * 「단건은 되는데 일괄에서는 안 보이는」 두 번째 진실원이 생긴다. 특히 <b>재검수 건은 승인 상태에
     * 머물러 있으므로</b> 「검수 진행 중인 것만」으로 좁히면 그 건이 영영 담기지 않는다.
     *
     * <p>이 값은 화면이 체크칸을 켤지 끌지에 쓰는 <b>편의 표시</b>다 — 인가 판정이 아니며, 자격 없는
     * 건을 담아 보내도 일괄 승인 창구가 그 건만 실패로 돌려준다.
     *
     * @design API-008
     * @design API-250
     */
    private boolean isBulkApprovable(LsRawDataStatus stts, ReviewClaim claim, Long selfNo) {
        if (claim == null || !claim.ownedBy(selfNo)) {
            return false;
        }
        return stateMachine.allowsApproval(stts.getDataSttsCd(), stts.needsRecheck());
    }

    /** 점유 판정 결과 → 응답 축. 점유가 없거나 만료면 세 값이 모두 빈 {@code NONE} 이다. */
    private static ReviewResponse.Reviewing toReviewing(ReviewClaim claim,
                                                        UserNameResolver.UserNames userNames) {
        if (claim == null) {
            return ReviewResponse.Reviewing.NONE;
        }
        return new ReviewResponse.Reviewing(
                claim.ownerUserNo(), userNames.nameOf(claim.ownerUserNo()), claim.startedAt());
    }

    /**
     * 마지막 승인 이벤트 → 응답 축.
     *
     * <p>역할은 <b>그 이벤트에 박힌 값</b>을 그대로 쓴다 — 지금 그 사람의 역할을 다시 읽지 않는다.
     * 역할을 남기지 않던 시기의 기록이면 사람·시각은 있는데 역할만 비며, 그것을 지어내 채우지 않는다.
     */
    private static ReviewResponse.LastApproval toLastApproval(LsTaskEventLog approval,
                                                              UserNameResolver.UserNames userNames) {
        if (approval == null) {
            return ReviewResponse.LastApproval.NONE;
        }
        return new ReviewResponse.LastApproval(
                approval.getActorUserNo(),
                userNames.nameOf(approval.getActorUserNo()),
                approval.getActorRoleCd(),
                approval.getOcrnDt());
    }

    /** 이름을 해석해야 하는 사번 전부(작업자·점유자·승인자)를 중복 없이 모은다. */
    private static Set<Long> collectUserNos(java.util.Collection<Long> workerNos,
                                            Map<Long, ReviewClaim> claims,
                                            Map<Long, LsTaskEventLog> approvals) {
        Set<Long> userNos = new LinkedHashSet<>(workerNos);
        claims.values().forEach(claim -> userNos.add(claim.ownerUserNo()));
        approvals.values().forEach(approval -> userNos.add(approval.getActorUserNo()));
        userNos.remove(null);
        return userNos;
    }

    /**
     * 영상별 <b>마지막 승인 이벤트</b>를 쿼리 한 번으로 조회한다.
     *
     * <p>점유 조회와 같은 창구를 쓰되 후보 종류가 승인 하나라, 영상마다 「가장 최근 승인」 1건이 돌아온다
     * (반려·검수 시작이 그 뒤에 있어도 승인 이력 자체는 남아 있어야 하므로 후보를 넓히지 않는다).
     */
    private Map<Long, LsTaskEventLog> lookupLastApprovalByVideo(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, LsTaskEventLog> map = new HashMap<>();
        for (LsTaskEventLog event : taskEventLogRepository.findLatestOfTypesByRawDataIds(
                videoIds, List.of(LsTaskEventLog.EVENT_APPROVE))) {
            if (event == null || event.getRawDataId() == null) continue;
            map.put(event.getRawDataId(), event);
        }
        return map;
    }

    /**
     * 검수목록 KPI 카드 집계 — 현재 페이지가 아니라 <b>필터 결과 전체</b>를 기준으로 센다.
     *
     * <p>필터는 {@link #list} 와 동일하되 <b>{@code status} 만 제외</b>한다 — KPI 카드 자체가 status
     * 선택지이므로 이미 좁혀진 집합 위에서 4종을 세면 1개 카드만 non-zero 가 된다(작업목록 summary 가
     * {@code workStatus} 축만 제외하는 것과 대칭). 검수 워크플로 화이트리스트는 목록과 동일하게 상시
     * 적용되므로 배치/작업 상태는 어느 버킷에도 합산되지 않는다(HIGH-5).
     *
     * <p>★<b>제외분은 4종 버킷에서 빠지고 「제외됨 건수」로 따로 실린다</b>(ADR-069). 목록이 제외분을
     * 빼므로 집계도 같이 줄어야 한다 — 갈리면 화면의 카드 숫자와 그 카드를 눌러 얻는 목록이 어긋난다.
     * 그 건수는 <b>4종 합의 항이 아니다</b>(제외분은 {@code total} 에서도 이미 빠져 있다).
     *
     * <p>목록과 별도 요청이라 두 호출 사이의 상태 전이로 미세하게 어긋날 수 있으며, 반환값은
     * <b>조회 시점 스냅샷</b>이다(대시보드성 KPI 라 강한 정합성은 요구하지 않는다).
     *
     * @design API-138
     */
    public ReviewSummaryResponse summarize(ReviewSearchCondition condition, TokenClaims actor) {
        requireReviewer(actor);
        ReviewSearchCondition effective = (condition != null) ? condition : ReviewSearchCondition.defaults();
        // 두 집계가 <같은 조건 객체>를 본다 — 하나만 따로 만들면 검색어 정규화가 한쪽에만 적용돼
        // 카드 숫자와 「제외됨 N건」의 필터 범위가 갈라진다. [design: ADR-069] [design: API-138]
        ReviewSearchCondition searchOnly = effective.searchOnly();
        return ReviewSummaryResponse.of(
                reviewQueryRepository.countByStatus(searchOnly),
                reviewQueryRepository.countExcluded(searchOnly));
    }

    /**
     * 페이지의 영상 ID 들에 대해 (rawSn → 표시명) 매핑을 단일 native 쿼리로 조회.
     *
     * <p>판정은 {@link CctvDisplayNamePolicy} 단독 소유다(작업목록·영상목록과 같은 판정기).
     * 조회되지 않은 영상만 키가 비고, 그 경우도 {@code ReviewResponse.from} 이 같은 판정기로 처리한다.
     */
    private Map<Long, String> lookupCctvNames(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : videoRepository.findCctvNamesByRawSns(videoIds)) {
            if (row == null || row.length < 3 || row[0] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            String cctvNm = row[1] != null ? row[1].toString() : null;
            String vmsCctvId = row[2] != null ? row[2].toString() : null;
            String resolved = CctvDisplayNamePolicy.resolve(cctvNm, vmsCctvId, rawSn);
            if (resolved != null) {
                map.put(rawSn, resolved);
            }
        }
        return map;
    }

    /**
     * 페이지의 영상 ID 들에 대해 LABELER 배정 (rawDataId → 작업자 userNo) 매핑을 단일 IN 쿼리로 조회.
     * 동일 영상에 여러 LABELER 배정(재배정 누적)이 있으면 <b>가장 최근 1건</b>만 유지한다.
     *
     * <p>"가장 최근" = {@code REG_DT DESC} → {@code ASSIGNMENT_ID DESC}. REG_DT 가 동일한 배정이 여러
     * 건일 때 DB 반환 순서에 의존하면(구 {@code putIfAbsent}) 표시되는 작업자가 비결정적이 되어, 같은
     * 기준으로 최신 1건을 지목하는 <b>작업자명 검색</b>({@code ReviewQueryRepository.latestLabelerMatches})
     * 과 결과가 어긋난다(검색한 작업자와 다른 작업자가 표시). 여기서도 동일 tie-break 를 적용한다.
     */
    private Map<Long, Long> lookupLabelerByVideo(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<LsTaskAssignment> labelers = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsTaskAssignment.TASK_LABELER, videoIds);
        Map<Long, LsTaskAssignment> latest = new HashMap<>();
        for (LsTaskAssignment a : labelers) {
            if (a.getRawDataId() == null) continue;
            latest.merge(a.getRawDataId(), a, ReviewService::laterAssignment);
        }
        Map<Long, Long> map = new HashMap<>();
        for (Map.Entry<Long, LsTaskAssignment> entry : latest.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getUserNo());
        }
        return map;
    }

    /** (REG_DT, ASSIGNMENT_ID) 가 더 큰 배정을 최신으로 본다. */
    private static LsTaskAssignment laterAssignment(LsTaskAssignment current, LsTaskAssignment candidate) {
        if (current.getRegDt() == null) return candidate;
        if (candidate.getRegDt() == null) return current;
        int byRegDt = candidate.getRegDt().compareTo(current.getRegDt());
        if (byRegDt != 0) return byRegDt > 0 ? candidate : current;
        Long currentId = current.getAssignmentId();
        Long candidateId = candidate.getAssignmentId();
        if (currentId == null || candidateId == null) return current;
        return candidateId > currentId ? candidate : current;
    }

    /**
     * 사용자 번호 집합에 대해 (userNo → userNm) 매핑을 단일 IN 쿼리로 조회.
     * null 제외·중복 제거·빈 입력 시 쿼리 미수행 계약은 {@link UserNameResolver} 가 소유한다.
     */
    private UserNameResolver.UserNames lookupUserNames(java.util.Collection<Long> userNos) {
        return userNameResolver.resolveAllByNo(userNos);
    }

    /**
     * 페이지의 영상 ID 들에 대해 (rawSn → 라벨 총개수) 매핑을 단일 IN 쿼리로 조회.
     * LS_DATA_LBL JOIN LS_DATA_SRC GROUP BY rawSn — 라벨이 없는 영상은 키가 존재하지 않아
     * 호출 측 {@code getOrDefault(id, 0L)} 로 0 폴백 처리.
     */
    private Map<Long, Long> lookupLabelCountByVideo(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : labelRepository.countLabelsByRawSnIn(videoIds)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            Long count = ((Number) row[1]).longValue();
            map.put(rawSn, count);
        }
        return map;
    }

    /**
     * 페이지의 영상 ID 들에 대해 (rawSn → [eventName, eventTypeCd]) 매핑을 단일 native 쿼리로 조회.
     * 현 단계에서는 EVNT_TYPE_CD 값을 eventName/eventTypeCd 양쪽에 동일하게 반환 (코드값 fallback).
     * 영상 메타가 없거나 EVNT_TYPE_CD 가 null 이면 키 자체를 넣지 않아 호출 측에서 null 폴백 처리.
     */
    private Map<Long, String[]> lookupEventByVideo(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String[]> map = new HashMap<>();
        for (Object[] row : videoRepository.findEventInfoByRawSns(videoIds)) {
            if (row == null || row.length < 3 || row[0] == null) continue;
            Long rawSn = ((Number) row[0]).longValue();
            String eventName = row[1] != null ? row[1].toString() : null;
            String eventTypeCd = row[2] != null ? row[2].toString() : null;
            if (eventName == null && eventTypeCd == null) continue;
            map.put(rawSn, new String[]{eventName, eventTypeCd});
        }
        return map;
    }

    /**
     * 검수 단건 상세 조회 — REVIEWER(전체) 또는 본인 LABELER 배정 WORKER 만.
     * <p>WORKER 는 라벨링 화면의 검수제출 가드(작업 상태 조회)용으로 본인 배정 영상만 조회 가능 (CWE-639 IDOR 방어).
     * 다른 작업자에게 배정된 영상은 WORKER 에게 403.
     */
    public ReviewResponse getDetail(Long videoId, TokenClaims actor) {
        requireAssignedOrReviewer(videoId, actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        return enrichOne(stts, actor);
    }

    /**
     * 단건 응답 enrich — list() 의 batch helper 를 {@code List.of(videoId)} 단건 인자로 재사용해
     * cctvName/workerId/workerName/labelCount 와 점유·최근 승인자·일괄 승인 자격을 채운다.
     * <p>모두 readOnly 조회이므로 쓰기 트랜잭션 내부(approve/reject)에서도 호출 가능.
     * 변경된 stts 상태를 응답에 반영하려면 flush 이후에 호출할 것.
     *
     * <p><b>승인·반려 뒤에 불리면 점유가 비어 나오는 것이 정상</b>이다 — 그 행위가 자기 이벤트를
     * 남겨 점유 판정의 조건 ①(뒤에 종결 이벤트가 없을 것)에 걸리기 때문이다. 점유를 푸는 별도
     * 동작이 없는 것은 그래서다({@code ADR-067}).
     */
    private ReviewResponse enrichOne(LsRawDataStatus stts, TokenClaims actor) {
        Long videoId = stts.getRawDataId();
        if (videoId == null) {
            return ReviewResponse.from(stts);
        }
        List<Long> ids = List.of(videoId);
        String cctvName = lookupCctvNames(ids).get(videoId);
        Map<Long, Long> workerIdMap = lookupLabelerByVideo(ids);
        Long workerId = workerIdMap.get(videoId);
        Long labelCount = lookupLabelCountByVideo(ids).getOrDefault(videoId, 0L);
        String[] eventInfo = lookupEventByVideo(ids).get(videoId);
        String eventName = (eventInfo != null) ? eventInfo[0] : null;
        String eventTypeCd = (eventInfo != null) ? eventInfo[1] : null;
        ReviewClaim claim = reviewClaimSupport.claimOf(videoId).orElse(null);
        Map<Long, LsTaskEventLog> lastApprovalMap = lookupLastApprovalByVideo(ids);
        // 작업자·점유자·승인자 이름을 한 번에 해석한다(단건이어도 조회 3회로 쪼개지 않는다).
        UserNameResolver.UserNames userNames = lookupUserNames(collectUserNos(
                (workerId != null) ? List.of(workerId) : Collections.emptyList(),
                (claim != null) ? Map.of(videoId, claim) : Collections.emptyMap(),
                lastApprovalMap));
        String workerName = userNames.nameOf(workerId);
        Long selfNo = parseUserNoOrNull(actor != null ? actor.sub() : null);
        return ReviewResponse.from(stts, cctvName, workerId, workerName, labelCount,
                eventName, eventTypeCd,
                toReviewing(claim, userNames),
                toLastApproval(lastApprovalMap.get(videoId), userNames),
                isBulkApprovable(stts, claim, selfNo));
    }

    /**
     * SCR-REVIEW-002 — 영상의 모든 프레임 + 라벨 일괄 응답 (REVIEWER).
     *
     * <p>N+1 회피: 프레임 1회 (findByRawSn..) + 라벨 1회 (findBySrcSnIn) — 총 2회 쿼리.
     */
    public FrameListResponse listFrames(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        // 영상 존재 확인 — 미존재 시 404
        videoRepository.findById(videoId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 1) 프레임 일괄 조회
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(videoId);
        if (frames.isEmpty()) {
            return new FrameListResponse(videoId, 0, Collections.emptyList());
        }

        // 2) 라벨 IN-쿼리 — N+1 회피
        List<Long> srcSns = frames.stream().map(LsDataSrc::getSrcSn).toList();
        List<LsDataLbl> allLabels = labelRepository.findBySrcSnIn(srcSns);

        // 3) srcSn -> 라벨 목록 매핑
        Map<Long, List<LabelResponse.Item>> labelMap = new HashMap<>();
        for (LsDataLbl entity : allLabels) {
            labelMap.computeIfAbsent(entity.getSrcSn(), k -> new ArrayList<>())
                    .add(LabelResponse.Item.from(entity, null, objectMapper));
        }

        // 4) 프레임 DTO 매핑 (frameNo 순서 보장)
        List<FrameDetailResponse> details = new ArrayList<>(frames.size());
        for (LsDataSrc src : frames) {
            // FE 가 imageUrl 을 그대로 absolute path 로 쓴다. 그 접두어는 «앞단 웹서버가
            // 우리에게 넘겨주는 경로»라 배포 향마다 다르므로 설정에서 받는다(미설정이면 /api/v1).
            // ⚠ 구 주석의 "server.servlet.context-path=/api 적용 시" 는 사실이 아니다 —
            //   WAR 배포에서는 그 설정이 적용되지 않는다(ServletInitializer javadoc).
            String imageUrl = publicApiPath.of(
                    "/videos/" + videoId + "/frames/" + src.getFrameNo() + "/image");
            details.add(new FrameDetailResponse(
                    src.getSrcSn(),
                    Math.toIntExact(src.getFrameNo()),
                    imageUrl,
                    labelMap.getOrDefault(src.getSrcSn(), Collections.emptyList())
            ));
        }
        return new FrameListResponse(videoId, details.size(), details);
    }

    /**
     * 검수 이슈(반려 사유) 목록 조회 (REVIEWER).
     * LS_DATA_ISSUE 가 비어 있으면 빈 배열 반환.
     */
    public List<IssueResponse> listIssues(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        return issueRepository.findByDataRawSnOrderByRegDtDesc(videoId).stream()
                .map(IssueResponse::from)
                .toList();
    }

    /**
     * 작업자가 라벨링 완료 후 검수 제출. 본인에게 LABELER 로 배정된 영상만 가능.
     * 상태: ASSIGNED → PENDING / REJECTED → PENDING(재제출) / APPROVED → PENDING(재검수 재제출).
     *
     * <p>재검수(APPROVED → PENDING): 검수완료 후 수정→재검수→재승인을 허용한다(CLAUDE.md 가 SoT).
     * 동일 작업 ID(=RAW_SN)를 유지하며 버전업이 아니다. 재승인 시 {@code VersionService.commitApproved}
     * 가 변경분에 대해 새 APPROVED 스냅샷을 적층한다. 본인 배정 WORKER 가드는 동일하게 적용된다.
     * 검수 이슈(LS_DATA_ISSUE)는 append-only 이력(REJECTION=RESOLVED 고정)이라 새 사이클 진입 시 초기화하지 않는다.
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse submit(Long videoId, TokenClaims actor) {
        verifyAssignedWorker(videoId, actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_PENDING);
        stts.transitionTo(LsRawDataStatus.STTS_PENDING);
        // 통합 이벤트 로그 (SCR-TASK-003): 검수 제출 이벤트 기록
        Long workerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsTaskEventLog.submit(
                stts.getRawDataId(), workerUserNo));
        log.info("[Review] submitted videoId={} actor={}", videoId, actor.sub());
        return enrichOne(stts, actor);
    }

    /**
     * 작업자가 검수 제출을 취소. 본인에게 LABELER 로 배정된 영상만 가능하며 <b>검수 시작 전(PENDING)</b>에만 허용.
     * 상태: PENDING → ASSIGNED(작업 상태 복귀).
     *
     * <p>가드:
     * <ul>
     *   <li>본인 배정 WORKER 검증 ({@link #verifyAssignedWorker}) — 타인/미배정 영상은 403 (CWE-639 IDOR).</li>
     *   <li>PENDING 이 아니면 {@code stateMachine.verify} 가 거부 — IN_REVIEW/REJECTED/ASSIGNED 는 400,
     *       APPROVED 는 409(CONFLICT). 검수 시작·승인·반려 뒤엔 취소 불가.</li>
     *   <li>낙관적 잠금(@Version) — REVIEWER 검수시작(PENDING→IN_REVIEW)과 동시 경합 시 한쪽만 성공,
     *       패자는 409(CONFLICT). flush 로 커밋 전 충돌을 결정적으로 표면화한다.</li>
     * </ul>
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse cancelSubmit(Long videoId, TokenClaims actor) {
        verifyAssignedWorker(videoId, actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_ASSIGNED);
        stts.transitionTo(LsRawDataStatus.STTS_ASSIGNED);
        // 통합 이벤트 로그 (SCR-TASK-003): 제출 취소 이벤트 기록 (PII 미포함)
        Long workerUserNo = parseUserNo(actor.sub());
        taskEventLogRepository.save(LsTaskEventLog.cancelSubmit(
                stts.getRawDataId(), workerUserNo));
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Review] optimistic lock conflict on cancelSubmit videoId={} actor={}", videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "이미 검수가 시작되었거나 상태가 변경되었습니다.");
        }
        log.info("[Review] cancelSubmit videoId={} actor={}", videoId, actor.sub());
        return enrichOne(stts, actor);
    }

    /**
     * 검수 시작 — <b>그 영상의 점유를 세운다</b>. 배정 여부는 자격 조건이 아니다({@code ADR-067}).
     *
     * <h3>다섯 갈래 — 점유 판정이 상태 판정보다 앞선다</h3>
     * <ol>
     *   <li><b>남이 점유 중</b> → 상태와 무관하게 409. 응답 본문에 점유자의 <b>표시 이름만</b> 싣는다.</li>
     *   <li><b>내가 이미 주인</b> → 상태를 바꾸지 않고 성공(멱등 재진입). 이때 「검수 시작」을 <b>새로
     *       남겨</b> 점유 시각을 갱신한다 — 갱신하지 않으면 보던 도중에 만료돼 남에게 넘어간다.
     *       ★이미 쌓인 이벤트를 고치거나 지우지 않으므로 <b>최초 시작 시각은 앞선 행에 그대로 남는다</b>.</li>
     *   <li><b>점유 없음 + 검수 대기</b> → 검수 진행으로 전이하고 점유. <b>상태가 바뀌는 유일한 갈래</b>다.</li>
     *   <li><b>점유 없음 + 이미 검수 진행</b>(유예가 지나 점유가 풀린 영상) → 전이 없이 새 점유자가 된다.</li>
     *   <li><b>점유 없음 + 승인 + 재검토 표시</b>(수정 뒤 재검수 대기) → 전이 없이 점유만 세운다.</li>
     * </ol>
     * 그 밖의 상태는 종전대로 전이 검증이 거절한다(재검토 표시가 없는 승인 영상 포함).
     *
     * <h3>★★재검수 건의 상태를 되돌리지 않는 이유</h3>
     * 관제 조회 데이터마트 뷰가 <b>라이브 승인 상태</b>로 행을 거른다. 검수 시작이 그 상태를 검수
     * 진행으로 내리면 <b>이미 완료로 통지한 영상의 행이 관제에서 예고 없이 사라진다</b> — 「검수 완료·
     * 통지 건에 대한 관제 접근은 무조건 보장한다」는 구속 규칙의 정면 위반이다. 같은 이유로 이 경우를
     * 위한 <b>새 상태값도 만들지 않는다</b>(새 값은 그 뷰의 조건이 알지 못해 결국 같은 일이 일어난다).
     *
     * <h3>동시 시작 경합</h3>
     * 둘이 동시에 눌러 갈래 3 에 함께 들어오면 상태 원장의 {@code @Version} 이 한 명만 통과시키고
     * 패자는 409 다 — 승인·반려가 같은 상황에서 쓰는 것과 <b>같은 방식</b>이며, 안내 없이 커밋 시점
     * 예외로 새지 않는다(그것이 이 라운드 이전의 동작이었다).
     *
     * <p>★<b>점유는 잠금이 아니다.</b> 만료가 있어 영구 잠금이 되지 않고, 승인 시점의 낙관적 잠금이
     * 실제 방어로 그대로 남는다. 점유가 생겼다는 이유로 그 잠금을 걷어내지 말 것.
     *
     * @design API-013
     * @design ADR-067
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse startReview(Long videoId, TokenClaims actor) {
        requireReviewer(actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        Long selfNo = parseUserNo(actor.sub());

        // ① 점유 판정이 먼저다 — 남이 보고 있으면 상태가 무엇이든 시작하지 않는다.
        Optional<ReviewClaim> existing = reviewClaimSupport.claimOf(videoId);
        if (existing.isPresent() && !existing.get().ownedBy(selfNo)) {
            throw claimConflict(videoId, existing.get());
        }
        boolean reentry = existing.isPresent();

        if (!reentry) {
            String from = stts.getDataSttsCd();
            if (LsRawDataStatus.STTS_PENDING.equals(from)) {
                // 갈래 3 — 검수 대기 영상을 집어간다. 상태가 바뀌는 유일한 자리.
                stateMachine.verify(from, LsRawDataStatus.STTS_IN_REVIEW);
                stts.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
            } else if (!LsRawDataStatus.STTS_IN_REVIEW.equals(from)
                    && !stateMachine.isReapproval(from, stts.needsRecheck())) {
                // 갈래 4·5 가 아니면 종전 거절을 그대로 쓴다 — 전이 검증이 400/409 를 가른다.
                //   (전이 불가를 여기서 새로 판정하면 오류 계약이 갈린다.)
                stateMachine.verify(from, LsRawDataStatus.STTS_IN_REVIEW);
            }
            // 갈래 4(유예 만료된 검수 진행) · 갈래 5(재검수 대기 승인 영상)는 전이 없이 점유만 선다.
        }

        // 갈래 2·3·4·5 공통 — 「검수 시작」을 남겨 점유를 세우거나 갱신한다.
        //   점유를 <b>푸는</b> 이벤트는 만들지 않는다(승인·반려가 자기 이벤트로 푼다).
        taskEventLogRepository.save(LsTaskEventLog.startReview(videoId, selfNo, actor.role()));
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            // 동시 시작 경합 — 승인·반려와 같은 방식으로 409 를 돌려준다.
            log.warn("[Review] optimistic lock conflict on startReview videoId={} actor={}",
                    videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 검수를 시작했습니다.");
        }
        log.info("[Review] startReview videoId={} actor={} reentry={}", videoId, actor.sub(), reentry);
        return enrichOne(stts, actor);
    }

    /**
     * 남의 점유로 검수 시작이 거절될 때의 409 — 누가 보고 있는지를 <b>표시 이름만</b> 담아 알린다.
     *
     * <p>사용자 식별자·역할·소속은 요청자가 알 필요가 없다(CWE-359). 이름을 해석하지 못하면 사람을
     * 특정하지 않는 문구로 내려간다 — 그 경우에도 거절 자체는 유지된다(fail-closed).
     *
     * @design API-013
     */
    private CustomException claimConflict(Long videoId, ReviewClaim claim) {
        Long ownerNo = claim.ownerUserNo();
        String ownerName = (ownerNo != null)
                ? lookupUserNames(List.of(ownerNo)).nameOf(ownerNo)
                : null;
        log.warn("[Review] startReview blocked — already claimed videoId={}", videoId);
        String message = (ownerName != null && !ownerName.isBlank())
                ? ownerName + " 님이 검수 중입니다."
                : "다른 검수자가 검수 중입니다.";
        return new CustomException(ErrorCode.CONFLICT, message, new ReviewClaimConflict(ownerName));
    }

    /**
     * REVIEWER 승인. IN_REVIEW → APPROVED. 동시 승인 시도 시 OptimisticLockException → 409.
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse approve(Long videoId, TokenClaims actor) {
        return approve(videoId, null, actor);
    }

    /**
     * REVIEWER 승인 (바디 포함) — negative sample(라벨 0건) 은 검수자의 명시 확인이 있어야 통과한다.
     *
     * @param req 선택 바디. null 이면 확인 없음(기존 동작).
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse approve(Long videoId, ApproveRequest req, TokenClaims actor) {
        requireReviewer(actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        // 비식별 누락 신고 구간(DE_IDNTF_YN='F')이면 승인 차단(412) — 인가(requireReviewer)·존재(404) 이후
        //   평가되는 프리컨디션이며 역할 무관이다(REVIEWER 도 막힌다).
        //   <b>승인은 이 게이트 축에서 가장 민감한 지점</b>이다: APPROVED 전이가 라벨 전체 스냅샷 동결
        //   (VersionService.commitApproved) · 메타 동결(materialize) · ReviewApprovedEvent(→ export·관제 통지)를
        //   한 트랜잭션에서 확정한다. 신고는 "이 영상의 비식별이 잘못됐다"고 알려진 구간이므로, 그 위에서
        //   확정하면 마스킹 실패가 남은 산출물이 검수 통과분으로 관제에 나간다(CWE-359).
        //   전이 이전에 평가하므로 거부 시 기존 상태·스냅샷·통지가 전혀 생기지 않는다.
        //   판정은 DeidentReportGate 단일 원천에 위임한다("F" 비교를 여기서 재구현하지 않는다).
        accessGuard.requireNotUnderDeidentReport(stts.getRawDataId());

        // V14 — 비식별화완료여부(DE_IDNTF_CMPTN_YN)가 미완료면 승인 차단(412). 외부 산출물을
        //   <b>원본이라고 지정해</b> 이관한 영상만 미완료로 시작하며(기본값 'Y'), 그 영상은 비식별이
        //   끝나기 전까지 승인이 막힌다(ADR-048).
        //   ★ 이 값은 <검수 승인 하나만> 막는다 — 라벨 조회·프레임 이미지·영상 스트리밍·산출물
        //     생성을 닫지 않는다. 그 통로들을 함께 닫는 것은 바로 위 비식별 누락 신고이며 <별개 축>이다.
        //     두 축을 합치면 이관 경로에는 라벨과 프레임이 이미 들어와 있어 그것을 못 보게 되고
        //     검수 자체가 성립하지 않는다.
        //   ★ 위 신고 게이트와 같은 자리(상태 전이·라벨 게이트 <이전>)에 둔다 — 거부 시 상태 전이·
        //     버전 스냅샷·export·통지가 하나도 생기지 않아야 하기 때문이다.
        //   ⚠ 두 조건은 <동시에 성립하지 않는다>. 신고는 비식별을 한 번이라도 수행한 영상만 접수하는데
        //     이 값이 미완료인 영상은 아직 비식별을 수행하지 않았다. 그래서 문구가 갈려도 응답이 다른
        //     조건의 성립 여부를 알려주는 오라클이 되지 않는다(CWE-209).
        if (!stts.isDeidentCompleted()) {
            log.warn("[Review] approve blocked — deident not completed rawSn={}", stts.getRawDataId());
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별이 완료되지 않은 영상입니다. 비식별 완료 후 다시 시도해 주세요.");
        }

        // Phase 7a-2 — 재검토 표시(REVLT_YN='Y', V177)가 선 APPROVED 영상은 상태 전이 없이(멱등) 재승인을
        //   허용한다. "표시가 섰던 사실"은 상태 전이/이벤트 발행 분기 둘 다에 쓰이므로 어떤 경로로 여기 왔든
        //   (아래 short-circuit 경로든, 기존 legacy APPROVED→PENDING→IN_REVIEW→APPROVED 전체 재검수
        //   사이클이든) 진입 시점 값을 <b>먼저</b> 읽어 둔다 — legacy 경로는 이 값이 true 로 stale 남아 있을
        //   수 있고(WORKER submit()이 표시를 지우지 않는다), 그 경우도 아래에서 함께 정리해 다음 수정
        //   통지가 영구히 보류되는 사고를 막는다.
        String currentStatus = stts.getDataSttsCd();
        boolean hadRecheckFlag = stts.needsRecheck();
        // ★ 재승인 갈래 판정은 {@link ReviewStateMachine} 단독 소유다 — 여기서 상태를 직접 비교하면
        //   일괄 승인 자격(allowsApproval)이 보는 규칙과 갈려 「단건은 되는데 일괄에는 안 보이는」
        //   두 번째 진실원이 생긴다(ADR-067 · API-250).
        boolean isReapproval = stateMachine.isReapproval(currentStatus, hadRecheckFlag);
        if (!isReapproval) {
            // 상태 전이 유효성이 먼저다 — 잘못된 전이(예: PENDING→APPROVED)는 라벨 유무와 무관하게 기존대로
            //   400 을 유지한다(라벨 게이트가 기존 오류 계약을 덮어쓰지 않도록 순서 고정).
            stateMachine.verify(currentStatus, LsRawDataStatus.STTS_APPROVED);
        }
        // D-ISSUE-04 — 라벨(=학습데이터 본문)이 0건인 영상은 원칙적으로 승인 차단(409). 단 검수자가
        //   "라벨 없음"을 명시 확인하면 통과시킨다(negative sample). 상태 전이 <b>이전</b>에 판정하므로
        //   거부 시 기존 상태가 그대로 유지된다(부분 전이·빈 스냅샷·빈 export 없음). 재승인도 동일 규칙을
        //   적용한다 — 재검토 구간에 라벨이 전량 삭제됐다면 사람이 다시 확인해야 한다.
        boolean confirmedNoLabel = (req != null) && req.confirmedNoLabel();
        boolean approvedWithoutLabel = resolveNoLabelApproval(stts.getRawDataId(), confirmedNoLabel);
        if (!isReapproval) {
            stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        }
        if (hadRecheckFlag) {
            // 표시 해제 — short-circuit 재승인·legacy 전체 재검수 사이클 양쪽 모두에서 정리한다(위 설명).
            stts.clearNeedsRecheck();
        }
        // 통합 이벤트 로그 (SCR-TASK-003): 승인 이벤트 기록.
        //   H6 — negative sample 승인은 <b>같은 이벤트 로그에 사유를 남겨</b> 감사 가능하게 한다
        //   (누가·언제·어떤 영상을 라벨 없음 확인으로 승인했는지 — 신규 테이블/메커니즘 없이 재사용).
        Long reviewerUserNo = parseUserNo(actor.sub());
        // 역할은 <행위 시점의 실제 역할>이다 — actor.role() 은 계층으로 승격되지 않은 값이라
        // 관리자가 승인하면 ADMIN 이 그대로 남는다(ADR-067 · ERD-014). REVIEWER 로 내려 적지 말 것.
        taskEventLogRepository.save(approvedWithoutLabel
                ? LsTaskEventLog.approveWithoutLabel(stts.getRawDataId(), reviewerUserNo, actor.role())
                : LsTaskEventLog.approve(stts.getRawDataId(), reviewerUserNo, actor.role()));
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Review] optimistic lock conflict on approve videoId={} actor={}", videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 처리했습니다.");
        }
        // SFR-08 — 검수 승인 확정 후 같은 트랜잭션에서 영상 전체 학습데이터 버전 스냅샷 생성.
        // APPROVED 전이/스냅샷이 함께 커밋되거나 함께 롤백되어 정합성을 유지한다. 재승인(isReapproval)도
        // 동일하게 호출한다 — 내용이 실제로 바뀐 프레임만 새 스냅샷을 적층한다(해시 기반 skip, 재사용).
        VersionService.CommitResult commit = versionService.commitApproved(stts.getRawDataId(), actor);
        // M-2 — 라벨이 있는데도 직렬화/크기 초과로 스냅샷이 누락된 프레임이 있으면 운영자가 인지할 수 있도록
        // WARN 으로 가시화한다(승인 자체는 기존대로 성공 처리). 라벨 본문/PII 는 출력하지 않는다(CWE-209/359).
        if (commit.hasSkips()) {
            log.warn("[Review] approved with snapshot skips videoId={} skippedFrames={} actor={}",
                    videoId, commit.skipped(), actor.sub());
        }
        // F — 영상 검수 승인 시 event_annotation 을 자동 확정(APPROVED)해 export 의 event_annotation 이
        // 항상 동결되게 한다(별도 메타 승인 단계 불필요). REJECTED 는 제외(반려 존중), 이미 APPROVED 면 멱등 skip,
        // event_annotation 이 없으면 no-op. 같은 승인 트랜잭션에서 전이·flush → 이어지는 materialize 가
        // APPROVED event_annotation 을 EVNT_ANNO_CN 에 동결한다(materialize 조회 전 상태 반영 보장).
        evntAnnoReviewService.autoApproveOnVideoApproval(stts.getRawDataId(), actor);
        // F 완성 — 시계열 메타 검토행(LS_DATA_META_REVIEW)도 같은 승인 트랜잭션에서 자동 확정(APPROVED)
        // → V_COMPLETED_META(RVW_STTS_CD='APPROVED'만 노출) 누락 방지. REJECTED 는 제외(반려 존중),
        // 이미 APPROVED 면 멱등 skip, 검토행 없으면 no-op. 전이·flush 후 이어지는 materialize 가 이를 관측한다.
        metaService.autoApproveOnVideoApproval(stts.getRawDataId(), actor);
        // 포털향 통합 메타 동결 — LS_LABEL_VERSION 스냅샷 직후, 같은 승인 트랜잭션에서 materialize.
        // APPROVED 전이·버전 스냅샷·통합 메타 동결·outbox 가 원자적으로 함께 커밋/롤백된다(정합성 우선).
        datasetVideoMetaSnapshotService.materialize(stts.getRawDataId());
        log.info("[Review] approved videoId={} actor={} reapproval={}", videoId, actor.sub(), isReapproval);
        // Phase 7a-2(EVT-006) — 최초 승인만 즉시 export+TASK_COMPLETED 를 트리거한다(ReviewApprovedEvent).
        //   재승인(isReapproval)은 <b>정상 경로라면</b> 이 이벤트를 발행하지 않는다 — 재검토 표시가 서
        //   있는 동안 이미 ControlNotifyDebouncer 윈도우에 축적돼 있던 변경분이(TaskModifiedAccumulateListener
        //   가 모든 TaskModifiedEvent 를 무조건 축적하므로, needsRecheck=true 를 세운 바로 그 이벤트가 함께
        //   쌓아 둔다) 방금 해제한 표시 덕에 <b>다음 flush tick</b>에서 그대로 풀려 TASK_MODIFIED(축적된
        //   변경 프레임 포함, exportRegenerated 였다면 force 재생성 후) 로 나간다 — "지금 코드는 재승인에도
        //   TASK_COMPLETED 를 보내고 관제 409 자기치유로 우연히 TASK_MODIFIED 가 되는" 문제를, 아예
        //   TASK_COMPLETED 를 보내지 않는 명시적 분기로 없앤다. 자기치유(dispatchCompleted/dispatchModified)
        //   자체는 다른 실패 모드(관제 상태 불일치)의 안전망이라 손대지 않는다.
        //   Phase 7a-2b — 단 이 "정상 경로라면"이 깨질 수 있다(리스너 실패·클레임 경합 등으로 표시와
        //   윈도우 짝이 어긋남). 그 경우를 감지해 폴백하는 분기가 아래 else-if 다.
        if (!isReapproval) {
            eventPublisher.publishEvent(new ReviewApprovedEvent(
                    stts.getRawDataId(), reviewerUserNo, java.time.Instant.now()));
        } else if (!controlNotifyDebounceStore.hasOpenWindow(stts.getRawDataId())) {
            // Phase 7a-2b — 구멍2 폴백: 표시(REVLT_YN)는 섰었는데 축적 윈도우가 없다. 정상 경로라면
            //   표시를 세우는 리스너(ReviewRecheckMarkListener)와 축적 리스너(TaskModifiedAccumulateListener)가
            //   같은 TaskModifiedEvent 의 형제 AFTER_COMMIT 리스너라 항상 짝이 맞는다. 짝이 깨지면(리스너
            //   실패·클레임 경합 등) 표시만 지워지고 재산출·통지가 영구히 나가지 않는다 — 방치 감지 없이는
            //   아무도 모르는 채로 관제가 구 버전에 고착된다. 최초 승인과 동일한 경로로 강제 재생성 + 통지를
            //   발행해 최소한 뭔가는 나가게 한다(TASK_COMPLETED 로 나가도 수용 — 관제가 이미 등록된 job 이면
            //   409 를 주고 기존 자기치유가 TASK_MODIFIED 로 치환한다. 아무것도 안 나가는 것보다 낫다).
            log.warn("[Review] reapproval fallback — needsRecheck flag was set but no open debounce window "
                            + "found; forcing export+notify via ReviewApprovedEvent videoId={} actor={}",
                    videoId, actor.sub());
            eventPublisher.publishEvent(new ReviewApprovedEvent(
                    stts.getRawDataId(), reviewerUserNo, java.time.Instant.now()));
        }
        return enrichOne(stts, actor);
    }

    /**
     * D-ISSUE-04 — 검수 승인 사전 게이트: 라벨이 1건도 없는 영상의 승인을 409 로 차단한다.
     *
     * <h3>왜 필요한가 (실측)</h3>
     * 승인에는 라벨/프레임 존재 검증이 없어, 프레임 1건·라벨 0건 영상도 APPROVED 로 확정됐다. 그 결과
     * ①{@code VersionService.commitApproved} 가 {@code created=0} 인 빈 스냅샷을 만들고 ②AFTER_COMMIT
     * export 는 {@code nothing produced — marked FAILED} 로 끝나며 ③데이터마트 뷰
     * {@code V_COMPLETED_VIDEO} 에 뷰 출력 {@code OUTPUT_PATH_NM}/{@code FRME_CNT} 가 NULL 인 빈 행이 노출됐다.
     * export 는 승인 트랜잭션 <b>밖</b>(AFTER_COMMIT)이라 롤백되지도 않는다.
     *
     * <h3>범위 (오탐 방지)</h3>
     * 프레임이 0건인 영상은 라벨도 0건이므로 이 판정 하나가 "프레임 부재"까지 덮는다. 라벨이 1건 이상이면
     * 기존과 완전히 동일하게 승인된다(정상 플로우 무변경). 이미 APPROVED 인 기존 데이터는 대상이 아니며,
     * 게이트는 <b>신규 승인 시점</b>에만 적용된다.
     *
     * <h3>DEV_FIX(H6) — negative sample 탈출구</h3>
     * 객체가 실제로 없는 정상 영상(negative sample, 실측 rawSn=4·7·9·10·12)까지 막으면 검수자는 반려밖에
     * 못 해 <b>더미 라벨 입력을 유도</b>하게 되어 학습데이터가 오염된다. 그래서 검수자가 명시적으로
     * "라벨 없음"을 확인한 요청({@code noLabelConfirmed=true})만 통과시키고, 그 사실을 통합 이벤트 로그에
     * 남긴다. 확인 플래그의 상시화를 막기 위해 <b>라벨이 있는 영상에 확인을 보내면 400</b> 으로 거부한다.
     *
     * @return 라벨 0건인데 검수자 확인으로 승인되는가(=감사 로그에 사유를 남겨야 하는가)
     */
    private boolean resolveNoLabelApproval(Long rawSn, boolean confirmedNoLabel) {
        boolean hasLabel = labelRepository.existsAnyByRawSn(rawSn);
        if (hasLabel) {
            if (confirmedNoLabel) {
                // H6 — 확인 플래그를 "항상 붙이는" 클라이언트를 차단한다. 라벨이 있는데 '라벨 없음 확인'을
                //   보냈다는 것은 화면이 본 상태와 서버 상태가 다르거나(=확인의 근거가 무효) 플래그를
                //   습관적으로 보내고 있다는 뜻이다. 통과시키면 게이트가 상시 무력화된다.
                log.warn("[Review] approve rejected — noLabelConfirmed on labeled video rawSn={}", rawSn);
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "라벨이 있는 영상입니다. 최신 상태를 다시 확인한 뒤 승인하세요.");
            }
            return false;
        }
        if (!confirmedNoLabel) {
            log.warn("[Review] approve blocked — no labels rawSn={}", rawSn);
            // DEV_FIX H6 — 전용 errorCode. 같은 409 인 동시 승인 충돌/상태 전이 불가와 화면이 구분할 수
            //   있어야 한다(문자열 매칭 금지). 상태코드(409)와 메시지는 종전과 동일해 하위호환.
            throw new CustomException(ErrorCode.REVIEW_NO_LABEL,
                    "라벨이 없는 영상입니다. 객체가 없는 영상이 맞다면 '라벨 없음' 확인 후 승인하세요.");
        }
        log.warn("[Review] approved without labels (reviewer confirmed) rawSn={}", rawSn);
        return true;
    }

    /**
     * REVIEWER 반려. IN_REVIEW → REJECTED. LS_DATA_ISSUE 신규 INSERT (사유 필수).
     */
    @Transactional("controlTransactionManager")
    public ReviewResponse reject(Long videoId, RejectRequest req, TokenClaims actor) {
        requireReviewer(actor);
        LsRawDataStatus stts = loadByVideoId(videoId);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_REJECTED);

        // 직전 반려가 있으면 계층 연결 (UP_DATA_ISSUE_SN)
        Long parentIssueSn = issueRepository.findByDataRawSnOrderByRegDtDesc(videoId).stream()
                .findFirst()
                .map(LsDataIssue::getDataIssueSn)
                .orElse(null);
        LsDataIssue issue = (parentIssueSn == null)
                ? LsDataIssue.create(videoId, req.reason(), actor.sub())
                : LsDataIssue.createWithParent(videoId, req.reason(), actor.sub(), parentIssueSn);
        issueRepository.save(issue);

        stts.transitionTo(LsRawDataStatus.STTS_REJECTED);
        // 통합 이벤트 로그 (SCR-TASK-003): 반려 이벤트 기록
        Long reviewerUserNo = parseUserNo(actor.sub());
        // 역할은 <행위 시점의 실제 역할>이다 — actor.role() 은 계층으로 승격되지 않은 값이라
        // 관리자가 반려하면 ADMIN 이 그대로 남는다(ADR-067 · ERD-014).
        taskEventLogRepository.save(LsTaskEventLog.reject(
                stts.getRawDataId(), reviewerUserNo, req.reason(), actor.role()));
        try {
            reviewRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Review] optimistic lock conflict on reject videoId={} actor={}", videoId, actor.sub());
            throw new CustomException(ErrorCode.CONFLICT, "다른 검수자가 먼저 처리했습니다.");
        }
        log.info("[Review] rejected videoId={} actor={} parentIssueSn={}", videoId, actor.sub(), parentIssueSn);
        return enrichOne(stts, actor);
    }

    /**
     * VIDEO_ID(=RAW_SN) 단위 LS_RAW_DATA_STATUS 조회.
     * V34 이후 RAW_DATA_ID 는 단일 PK 이므로 단건 조회 후 미존재 시 404.
     */
    private LsRawDataStatus loadByVideoId(Long videoId) {
        return reviewRepository.findByRawDataId(videoId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "검수 대상 영상을 찾을 수 없습니다."));
    }

    /**
     * 작업자 본인이 LABELER 로 배정된 영상인지 검증 (CWE-639 IDOR 방어).
     * REVIEWER 가 호출하면 권한 부족으로 차단 (submit 은 WORKER 전용).
     *
     * <p><b>★ 이 자리의 동등 비교는 남긴다 — 빠뜨린 곳이 아니다.</b> 검수 제출·제출취소는
     * <b>작업자 전용</b> 자리이며 역할 계층은 작업자를 열지 않는다({@code ADMIN.satisfies(WORKER)}
     * 는 거짓). 이 서비스의 다른 세 지점과 달리 여기에는 <b>짝을 이루는 검수자 분기가 없어</b>
     * 계층 판정으로 바꿔서 얻는 것이 없고, 잘못 넓히면 관리자·검수자가 작업자 자리에 흘러들어
     * 「작업자가 제출하고 검수자가 검수한다」는 워크플로 자체가 무너진다.
     * [design: ADR-055] [design: AC-125]
     *
     * <p>회귀 가드: {@code ReviewRoleHierarchyTest} 가 관리자·검수자의 제출 거부를 고정한다.
     */
    private void verifyAssignedWorker(Long videoId, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // [design: AC-125] 작업자 전용 자리 — 계층이 열지 않는다. hasRole 로 바꾸지 말 것.
        if (actor.role() != Role.WORKER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "WORKER 권한이 필요합니다.");
        }
        Long selfNo = parseUserNo(actor.sub());
        boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                selfNo, LsTaskAssignment.TASK_LABELER, videoId);
        if (!assigned) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
        }
    }

    /**
     * 단건 상세 조회 접근 제어 — REVIEWER 이상(관리자 포함)은 전체, WORKER 는 본인 LABELER 배정
     * 영상만 (CWE-639 IDOR 방어). 그 외 역할/미인증/타인 배정 영상은 거부.
     * approve/reject/list 등 다른 액션 권한은 변경하지 않는다. [design: ADR-055]
     */
    private void requireAssignedOrReviewer(Long videoId, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // [design: ADR-055] 계층 반영 — 관리자는 검수자에게 열린 이 자리를 그대로 통과한다.
        //   동등 비교로 두면 관리자가 두 분기 어디에도 안 걸려 마지막 FORBIDDEN 으로 떨어진다.
        if (actor.hasRole(Role.REVIEWER)) {
            return;
        }
        if (actor.hasRole(Role.WORKER)) {
            Long selfNo = parseUserNo(actor.sub());
            boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                    selfNo, LsTaskAssignment.TASK_LABELER, videoId);
            if (assigned) {
                return;
            }
            throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "검수 상세 조회 권한이 없습니다.");
    }

    /**
     * 검수 창구 인가 — 창구의 「검수자 전용」은 <b>「검수자 이상」</b>으로 읽는다(관리자 포함).
     * [design: ADR-055] [design: ROLE-004]
     */
    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (!actor.hasRole(Role.REVIEWER)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }

    /**
     * 조회 경로 전용 사번 해석 — 해석하지 못하면 <b>예외 대신 {@code null}</b> 이다.
     *
     * <p>목록·상세는 이 라운드 이전에 사번을 전혀 보지 않았다. 여기서 {@link #parseUserNo} 를 쓰면
     * 표시용 값 하나(일괄 승인 자격) 때문에 <b>200 이던 조회가 401 로 바뀐다</b> — 되던 화면이 죽는다.
     * 해석 실패의 결과는 「자격 없음」일 뿐이며, 실제 승인 자격은 쓰기 경로가 따로 판정한다.
     */
    private static Long parseUserNoOrNull(String sub) {
        if (sub == null) {
            return null;
        }
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
