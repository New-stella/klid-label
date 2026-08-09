package kr.co.cudo.authoring.review.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.IssueCommentRequest;
import kr.co.cudo.authoring.review.dto.IssueCommentResponse;
import kr.co.cudo.authoring.review.dto.IssueCreateRequest;
import kr.co.cudo.authoring.review.dto.IssueThreadResponse;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import kr.co.cudo.authoring.review.repository.IssueCommentRepository;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 1 — 이슈 스레드 서비스 (검수자↔작업자 양방향 소통).
 *
 * <p>WORKER 는 본인 배정 영상에 문의(INQUIRY)를 등록하고, REVIEWER 가 댓글로 답변·해소한다.
 * 검수 반려(REJECTION) 이력과 문의가 통합 스레드로 조회된다.
 *
 * <p>보안:
 * <ul>
 *   <li><b>인가 (CWE-285/639 IDOR)</b>: createInquiry/listThreads 는 영상(rawSn) 단위 배정·작성자 소유
 *       검증. addComment/resolve 는 issueSn 으로 이슈 조회 후 {@code issue.getDataRawSn()} 기준 재검증
 *       (URL 에 rawSn 없음 — DeidentReportService.resolveManually 패턴).</li>
 *   <li><b>Mass Assignment (CWE-915)</b>: 작성자(AUTHOR_NO)/역할(AUTHOR_ROLE_CD)은 요청 DTO 가 아닌
 *       {@code actor.sub()}/{@code actor.role()} 에서만 도출한다.</li>
 *   <li><b>동시성 (CWE-362)</b>: LsDataIssue 의 {@code @Version} — resolve↔addComment 동시 충돌 시
 *       1건만 성공, 나머지는 409 CONFLICT.</li>
 *   <li><b>SQL Injection (CWE-89)</b>: 모든 조회는 Spring Data 파생 쿼리/JPQL 파라미터 바인딩.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class IssueThreadService {

    private final IssueRepository issueRepository;
    private final IssueCommentRepository commentRepository;
    private final LsTaskAssignmentRepository assignmentRepository;
    private final LsDataSrcRepository srcRepository;
    /** 사번 → 표시명 해석 단일 헬퍼 — 파싱·폴백·N+1 계약을 이 서비스가 다시 구현하지 않는다. */
    private final UserNameResolver userNameResolver;
    /** 사번 → 역할 코드 해석. 스레드에는 작성 시점 역할 컬럼이 없어 조회 시점 매핑을 배치로 읽는다. */
    private final LsUserRoleRepository lsUserRoleRepository;

    /**
     * 문의 등록. WORKER 는 본인 배정 영상만(TOCTOU — 검증·저장 동일 트랜잭션), REVIEWER 는 전체 허용.
     */
    @Transactional("controlTransactionManager")
    public IssueThreadResponse createInquiry(Long rawSn, IssueCreateRequest req, TokenClaims actor) {
        requireAuthenticated(actor);
        if (actor.role() == Role.WORKER) {
            verifyAssignedWorker(rawSn, actor);
        } else if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "문의 등록 권한이 없습니다.");
        }
        // CWE-639 — srcSn 이 지정되면 해당 프레임이 이 영상(rawSn) 소속인지 검증 (교차 영상 PK 주입 차단).
        verifyFrameBelongsToVideo(rawSn, req.srcSn());
        LsDataIssue issue = issueRepository.save(
                LsDataIssue.createInquiry(rawSn, req.content(), actor.sub(), req.srcSn()));
        log.info("[Issue] inquiry created issueSn={} rawSn={} actorRole={}",
                issue.getDataIssueSn(), rawSn, actor.role());
        // 작성자가 곧 호출자라 역할은 이미 손에 있다 — 방금 쓴 행을 되읽지 않는다.
        // (actor.role() 은 위 분기에서 WORKER/REVIEWER 로 좁혀져 non-null 이며, 그 출처도
        //  listThreads 가 배치로 읽는 LS_USER_ROLE 과 같은 매핑이다.)
        return IssueThreadResponse.from(
                issue, userNameResolver.resolveOne(actor.sub()), actor.role().name(), List.of());
    }

    /**
     * 영상의 이슈 스레드 목록 (반려 + 문의 통합, REG_DT 오름차순). 각 스레드에 댓글(시간순) 포함.
     * REVIEWER 는 전체, WORKER 는 현재 배정 또는 본인 작성 이슈가 있는 영상만 열람 가능.
     */
    public List<IssueThreadResponse> listThreads(Long rawSn, TokenClaims actor) {
        requireAuthenticated(actor);
        List<LsDataIssue> issues = issueRepository.findByDataRawSnOrderByRegDtAsc(rawSn);

        if (actor.role() == Role.WORKER) {
            verifyWorkerThreadAccess(rawSn, actor, issues);
        } else if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "스레드 조회 권한이 없습니다.");
        }

        if (issues.isEmpty()) {
            return List.of();
        }

        // N+1 회피 — 댓글을 단일 IN 쿼리로 모아 issueSn 별 매핑.
        List<Long> issueSns = issues.stream().map(LsDataIssue::getDataIssueSn).toList();
        List<LsIssueComment> comments = commentRepository.findByDataIssueSnInOrderByRegDtAsc(issueSns);

        // 작성자 이름 — 스레드/댓글 작성자 사번을 모아 단일 IN 쿼리 1회로 해석 (N+1 금지).
        UserNameResolver.UserNames names = resolveNames(issues, comments);
        // 작성자 역할 — 스레드 작성자 사번을 모아 역할 매핑도 단일 IN 쿼리 1회로 해석 (N+1 금지).
        Map<Long, String> reporterRoles = resolveReporterRoles(issues);

        Map<Long, List<IssueCommentResponse>> commentMap = new HashMap<>();
        for (LsIssueComment c : comments) {
            commentMap.computeIfAbsent(c.getDataIssueSn(), k -> new ArrayList<>())
                    .add(IssueCommentResponse.from(c, names.nameOf(c.getAuthorNo())));
        }

        return issues.stream()
                .map(i -> IssueThreadResponse.from(
                        i,
                        names.nameOf(i.getReportedUserNo()),
                        roleOf(reporterRoles, i.getReportedUserNo()),
                        commentMap.getOrDefault(i.getDataIssueSn(), List.of())))
                .toList();
    }

    /**
     * 이슈에 댓글 작성. issueSn → 이슈 조회 → {@code rawSn} 역참조 후 권한 재검증(IDOR 방어).
     * REVIEWER 댓글은 OPEN 문의를 ANSWERED 로 자동 전이. RESOLVED 문의는 409.
     */
    @Transactional("controlTransactionManager")
    public IssueCommentResponse addComment(Long issueSn, IssueCommentRequest req, TokenClaims actor) {
        requireAuthenticated(actor);
        LsDataIssue issue = issueRepository.findById(issueSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "이슈를 찾을 수 없습니다."));

        // IDOR — issueSn 의 영상(rawSn) 기준 재검증. WORKER 는 현재 배정 또는 이슈 작성자 본인만.
        if (actor.role() == Role.WORKER) {
            verifyWorkerIssueAccess(issue, actor);
        } else if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "댓글 작성 권한이 없습니다.");
        }

        // RESOLVED INQUIRY 댓글 차단(409). REJECTION 은 상태와 무관하게 허용.
        issue.assertCommentable();

        LsIssueComment comment = commentRepository.save(
                LsIssueComment.create(issueSn, actor.sub(), actor.role().name(), req.content()));

        // REVIEWER 답변 시 OPEN → ANSWERED 자동 전이. WORKER 댓글은 전이 없음.
        if (actor.role() == Role.REVIEWER) {
            issue.markAnswered();
        }

        try {
            issueRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Issue] optimistic lock conflict on addComment issueSn={}", issueSn);
            throw new CustomException(ErrorCode.CONFLICT, "다른 사용자가 먼저 처리했습니다.");
        }
        log.info("[Issue] comment added commentSn={} issueSn={} authorRole={}",
                comment.getIssueCommentSn(), issueSn, actor.role());
        return IssueCommentResponse.from(comment, userNameResolver.resolveOne(actor.sub()));
    }

    /**
     * 이슈 해소 (REVIEWER 전용). OPEN/ANSWERED → RESOLVED. 이미 RESOLVED 면 멱등(예외 없음).
     */
    @Transactional("controlTransactionManager")
    public void resolve(Long issueSn, TokenClaims actor) {
        requireAuthenticated(actor);
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "해소 권한은 REVIEWER 만 가집니다.");
        }
        LsDataIssue issue = issueRepository.findById(issueSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "이슈를 찾을 수 없습니다."));
        issue.resolve();
        try {
            issueRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Issue] optimistic lock conflict on resolve issueSn={}", issueSn);
            throw new CustomException(ErrorCode.CONFLICT, "다른 사용자가 먼저 처리했습니다.");
        }
        log.info("[Issue] resolved issueSn={} actor={}", issueSn, actor.sub());
    }

    // ---------- 작성자 이름 해석 ----------

    /**
     * 스레드/댓글 작성자 사번을 모아 사용자 마스터를 <b>한 번</b>에 조회한다 (N+1 금지).
     *
     * <p>사번({@code AUTHOR_NO}/{@code RPRT_USER_NO})은 VARCHAR 컬럼이라 숫자가 아닐 수 있다.
     * 파싱 실패는 <b>예외가 아니라 제외</b>로 처리하고 이름을 null 로 남긴다 — 과거 작성자가
     * 삭제·변경돼도 이슈 스레드 조회 자체는 살아 있어야 하기 때문이다. 이 규칙은
     * {@link UserNameResolver} 한 곳에 있으며 여기서 다시 구현하지 않는다.
     */
    private UserNameResolver.UserNames resolveNames(List<LsDataIssue> issues, List<LsIssueComment> comments) {
        List<String> rawUserNos = new ArrayList<>(issues.size() + comments.size());
        for (LsDataIssue i : issues) {
            rawUserNos.add(i.getReportedUserNo());
        }
        for (LsIssueComment c : comments) {
            rawUserNos.add(c.getAuthorNo());
        }
        return userNameResolver.resolveAll(rawUserNos);
    }

    /**
     * 스레드 작성자 사번을 모아 역할 매핑({@code LS_USER_ROLE})을 <b>한 번</b>에 조회한다 (N+1 금지).
     *
     * <p>이름 축({@link #resolveNames})과 <b>같은 형태</b>로 배치한다 — 행마다 별도 조회를 돌리면
     * 스레드 수만큼 쿼리가 늘어난다. 두 축은 조회 대상 테이블이 달라 쿼리를 합칠 수 없고
     * (표시명 {@code LS_ACNT_USER} / 역할 {@code LS_USER_ROLE}), 각각 IN 쿼리 1회로 끝난다.
     *
     * <p><b>댓글은 대상이 아니다</b> — 댓글은 작성 시점 역할을 {@code AUTHOR_ROLE_CD} 컬럼에 이미
     * 들고 있어 조회할 것이 없다.
     *
     * <p>사번 파싱은 {@link UserNameResolver#toUserNo(String)} 단일 규칙을 재사용한다(비숫자·공백·
     * null 은 예외가 아니라 제외). 쓸 수 있는 사번이 하나도 없으면 조회 자체를 하지 않는다.
     */
    private Map<Long, String> resolveReporterRoles(List<LsDataIssue> issues) {
        Set<Long> userNos = new LinkedHashSet<>();
        for (LsDataIssue i : issues) {
            Long parsed = UserNameResolver.toUserNo(i.getReportedUserNo());
            if (parsed != null) {
                userNos.add(parsed);
            }
        }
        if (userNos.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> roles = new HashMap<>(userNos.size() * 2);
        for (LsUserRole r : lsUserRoleRepository.findByUserNoIn(userNos)) {
            // 값이 null 인 행도 그대로 담아야 하므로 Collectors.toMap 이 아니라 put 이다(NPE 방지).
            roles.put(r.getUserNo(), r.getRoleCd());
        }
        return roles;
    }

    /** 역할 매핑이 없는 작성자(퇴사·미배정·비숫자 사번)는 {@code null} — 지어내지 않는다. */
    private static String roleOf(Map<Long, String> roles, String rawUserNo) {
        Long parsed = UserNameResolver.toUserNo(rawUserNo);
        return parsed == null ? null : roles.get(parsed);
    }

    // ---------- 내부 ----------

    private void requireAuthenticated(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
    }

    /**
     * srcSn(프레임)이 대상 영상(rawSn) 소속인지 검증 (CWE-639 교차 영상 참조 방어).
     *
     * <p>DeidentReportService 의 srcSn→rawSn 정합 검증 패턴 차용. srcSn 이 null 이면 프레임 미지정
     * 문의이므로 검증을 건너뛴다. 존재하지 않는 프레임 → 404, 타 영상 프레임 → 400.
     */
    private void verifyFrameBelongsToVideo(Long rawSn, Long srcSn) {
        if (srcSn == null) {
            return;
        }
        LsDataSrc frame = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        if (!rawSn.equals(frame.getRawSn())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "해당 영상에 속하지 않은 프레임입니다.");
        }
    }

    /** WORKER 본인이 LABELER 로 현재 배정된 영상인지 검증 (CWE-639). */
    private void verifyAssignedWorker(Long rawSn, TokenClaims actor) {
        Long selfNo = parseUserNo(actor.sub());
        boolean assigned = assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                selfNo, LsTaskAssignment.TASK_LABELER, rawSn);
        if (!assigned) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
        }
    }

    /**
     * WORKER 스레드 목록 접근 — 현재 배정 영상 또는 본인이 작성한 이슈가 있는 영상만 허용.
     * 재배정돼도 본인 문의 스레드는 계속 열람 가능(시나리오 #5).
     */
    private void verifyWorkerThreadAccess(Long rawSn, TokenClaims actor, List<LsDataIssue> issues) {
        Long selfNo = parseUserNo(actor.sub());
        boolean assigned = assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                selfNo, LsTaskAssignment.TASK_LABELER, rawSn);
        if (assigned) {
            return;
        }
        boolean authored = issues.stream()
                .anyMatch(i -> actor.sub().equals(i.getReportedUserNo()));
        if (!authored) {
            throw new CustomException(ErrorCode.FORBIDDEN, "조회 권한이 없는 영상입니다.");
        }
    }

    /**
     * WORKER 댓글 접근 — 이슈 영상에 현재 배정 또는 해당 이슈 작성자 본인만 허용 (IDOR 방어, CWE-863).
     *
     * <p><b>REJECTION 이슈 인가 정책 (PM 확정)</b>: 반려(REJECTION) 스레드는 상태가 RESOLVED(이력 고정)
     * 여도 댓글을 허용한다({@link LsDataIssue#assertCommentable()} 가 INQUIRY 만 차단). 허용 대상은
     * "현재 배정 WORKER + 이슈 작성자 + REVIEWER" 이며, 본 메서드의 {@code assigned || authored}
     * 조건이 그 중 WORKER 경계를 강제한다. 핵심 의도는 <b>반려 후 재배정된 새 작업자</b>가 반려
     * 사유에 질문할 수 있어야 한다는 점이다(현재 배정자이므로 {@code assigned} 로 통과). 반면
     * <b>배정 해제된 이전 작업자</b>는 작성자가 아닌 한 {@code !assigned && !authored} 로 403 거부된다.
     */
    private void verifyWorkerIssueAccess(LsDataIssue issue, TokenClaims actor) {
        Long selfNo = parseUserNo(actor.sub());
        boolean assigned = assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                selfNo, LsTaskAssignment.TASK_LABELER, issue.getDataRawSn());
        boolean authored = actor.sub().equals(issue.getReportedUserNo());
        if (!assigned && !authored) {
            throw new CustomException(ErrorCode.FORBIDDEN, "댓글 작성 권한이 없는 이슈입니다.");
        }
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
