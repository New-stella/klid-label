package kr.co.cudo.authoring.review.service;

import kr.co.cudo.authoring.assignment.domain.ReviewClaim;
import kr.co.cudo.authoring.assignment.service.ReviewClaimSupport;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.BatchApproveRequest;
import kr.co.cudo.authoring.review.dto.BatchApproveResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 검수 <b>일괄 승인</b> — 목록에서 고른 여러 영상을 한 번에 승인한다.
 *
 * <h3>★단건 승인 로직을 복제하지 않는다 — 같은 경로를 반복 호출한다</h3>
 * 승인 한 건은 라벨 전량 스냅샷 동결 · 이벤트 어노테이션 자동 확정 · 시계열 메타 자동 확정 · 통합 메타
 * 동결 · 산출 폴더 재생성 · 관제 통지 · 재검토 표시 해제를 <b>연쇄로</b> 일으킨다. 여기서 그 절차를
 * 다시 쓰면 그중 하나가 조용히 빠지고, 빠진 사실은 한참 뒤 관제가 구 버전에 고착된 뒤에야 드러난다.
 * 그래서 이 클래스는 <b>줄 세우고 결과를 모으는 일만</b> 하고 승인 자체는 단건 창구에 넘긴다.
 *
 * <h3>★건별 트랜잭션 경계 — 이 클래스에 {@code @Transactional} 을 붙이지 말 것</h3>
 * 붙이는 순간 {@code ReviewService.approve} 의 {@code REQUIRED} 가 이 트랜잭션에 <b>합류</b>해 경계가
 * 하나가 된다. 그러면 한 건의 실패가 앞서 성공한 건까지 되돌리고, 커밋이 끝까지 미뤄져 승인 뒤
 * 연쇄(AFTER_COMMIT)가 전부 마지막에 한꺼번에 터진다. 같은 이유로 <b>{@code ReviewService} 를 주입해
 * 프록시를 거쳐</b> 부른다 — 자기 호출로 부르면 트랜잭션 경계가 아예 생기지 않는다.
 *
 * <h3>자격 — 두 조건이고 각각 주인이 다르다</h3>
 * <ul>
 *   <li><b>①유효 점유의 주인이 나</b> — 이 클래스가 본다. 단건 승인은 점유를 자격으로 삼지 않으므로
 *       (그 비대칭은 의도된 것이다) 여기서만 걸러야 한다. ★이 제약이 이 창구의 안전장치다 —
 *       <b>한 번도 열어 보지 않은 영상을 무더기로 승인하는 길</b>이 열리지 않는다.</li>
 *   <li><b>②단건 승인이 허용하는 상태</b> — {@code ReviewService.approve} 가 그대로 판정한다. 여기서
 *       미리 가르지 않는 이유는 그 순간 두 번째 진실원이 되기 때문이다. 상태·게이트 위반은 그 호출이
 *       던지는 오류를 그대로 그 건의 실패 사유로 옮긴다.</li>
 * </ul>
 *
 * <h3>연쇄를 동시에 터뜨리지 않는다</h3>
 * 승인마다 발행되는 산출 작업은 경계가 있는 기존 배치 풀에서 실행된다 — 건수가 늘어도 동시 실행은 그
 * 풀의 상한을 넘지 않고 나머지는 큐에서 기다린다. 그래서 <b>전용 실행기를 새로 만들지 않는다</b>.
 * 한 요청에 담을 수 있는 건수 상한({@link ReviewBatchApprovePolicy})이 그 앞단 제동이다.
 *
 * @design API-250
 * @design ADR-067
 * @design AC-1113
 * @design AC-1114
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewBatchApproveService {

    /** ★프록시 경유 호출 — 건마다 독립 트랜잭션이 열린다(자기 호출로 바꾸면 경계가 사라진다). */
    private final ReviewService reviewService;
    /** 점유 판정 단일 창구 — 자격 ①. 판정 규칙을 여기에 옮겨 적지 않는다. */
    private final ReviewClaimSupport reviewClaimSupport;
    /** 건수 상한 — 목록 응답이 화면에 알려 주는 값과 <b>같은 소유자</b>에서 읽는다. */
    private final ReviewBatchApprovePolicy policy;

    /**
     * 요청한 영상들을 건별로 승인하고 <b>전건</b>의 결과를 돌려준다.
     *
     * <p>목록이 비었거나 건수 상한을 넘으면 요청 자체를 거부하고 <b>한 건도 처리하지 않는다</b>(400).
     * 반면 개별 영상의 상태·게이트 위반은 요청을 거부하지 않고 그 건의 실패 사유로 담긴다 — 두 축은
     * 다르다. 한 건도 승인되지 못해도 응답 자체는 성공이며 판정은 결과 목록으로 한다.
     */
    public BatchApproveResponse approveAll(BatchApproveRequest request, TokenClaims actor) {
        List<Long> videoIds = distinctVideoIds(request);
        if (videoIds.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "승인할 영상을 하나 이상 선택해야 합니다.");
        }
        int limit = policy.limit();
        if (videoIds.size() > limit) {
            // 상한 숫자는 배포 설정값이라 안내 문구에서만 노출한다 — 화면은 목록 응답이 내려준 값을 쓴다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "한 번에 승인할 수 있는 영상은 최대 " + limit + "건입니다.");
        }

        List<BatchApproveResponse.Item> results = new ArrayList<>(videoIds.size());
        for (Long videoId : videoIds) {
            results.add(approveOne(videoId, actor));
        }
        BatchApproveResponse response = BatchApproveResponse.of(results);
        log.info("[Review] batchApprove requested={} success={} failure={} actor={}",
                videoIds.size(), response.successCount(), response.failureCount(), actor.sub());
        return response;
    }

    /**
     * 한 건 — 자격 ①을 확인하고 단건 승인 창구에 넘긴다. <b>어떤 실패도 이 건 밖으로 번지지 않는다.</b>
     */
    private BatchApproveResponse.Item approveOne(Long videoId, TokenClaims actor) {
        Optional<ReviewClaim> claim = reviewClaimSupport.claimOf(videoId);
        if (claim.isEmpty()) {
            return BatchApproveResponse.Item.failed(videoId, ErrorCode.CONFLICT.name(),
                    "검수를 시작하지 않은 영상입니다. 먼저 검수를 시작한 뒤 선택해 주세요.");
        }
        if (!claim.get().ownedBy(userNoOf(actor))) {
            return BatchApproveResponse.Item.failed(videoId, ErrorCode.CONFLICT.name(),
                    "다른 검수자가 검수 중인 영상입니다.");
        }
        try {
            // ★단건 승인 그대로 — 부수효과가 하나도 빠지지 않는다. 재검수 건이면 이 호출이
            //   재승인 갈래를 그대로 타 상태 전이 없이 표시만 해제하고 수정 통지로 이어진다.
            reviewService.approve(videoId, null, actor);
            return BatchApproveResponse.Item.succeeded(videoId);
        } catch (CustomException e) {
            // 상태 전이 불가 · 비식별 신고 · 비식별 미완료 · 라벨 0건 · 동시 처리 충돌 — 전부 이 건의 사유다.
            return BatchApproveResponse.Item.failed(videoId, e.getErrorCode().name(), e.getMessage());
        } catch (DataAccessException e) {
            // 낙관적 잠금 등 영속 계층 충돌 — 단건 경로가 못 감싼 형태로 올라와도 다른 건을 말리지 않는다.
            log.warn("[Review] batchApprove item failed (persistence) videoId={} cause={}",
                    videoId, e.getClass().getSimpleName());
            return BatchApproveResponse.Item.failed(videoId, ErrorCode.CONFLICT.name(),
                    "다른 검수자가 먼저 처리했습니다.");
        } catch (RuntimeException e) {
            // 예상 못한 실패도 이 건에 가둔다 — 원인은 클래스명만 남긴다(경로 원문·PII 미노출).
            log.error("[Review] batchApprove item failed videoId={} cause={}",
                    videoId, e.getClass().getSimpleName());
            return BatchApproveResponse.Item.failed(videoId, ErrorCode.INTERNAL_ERROR.name(),
                    "승인 처리 중 오류가 발생했습니다.");
        }
    }

    /** 중복 식별자는 한 건으로 취급하고 빈 값은 버린다 — 요청 순서는 유지한다. */
    private static List<Long> distinctVideoIds(BatchApproveRequest request) {
        if (request == null || request.videoIds() == null) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>(request.videoIds());
        distinct.remove(null);
        return List.copyOf(distinct);
    }

    /** 요청자의 사번 — 해석하지 못하면 {@code null} 이고 자격 ①이 성립하지 않는다(fail-closed). */
    private static Long userNoOf(TokenClaims actor) {
        Objects.requireNonNull(actor, "actor");
        try {
            return Long.parseLong(actor.sub());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
