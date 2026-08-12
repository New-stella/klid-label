package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchBulkRetryRequest;
import kr.co.cudo.authoring.batch.dto.BatchBulkRetryResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 배치 <b>일괄</b> 재시작 서비스 — REVIEWER 전용. [@design API-199]
 *
 * <h3>부분 성공 (all-or-nothing 아님)</h3>
 * <p>되는 것만 재기동하고 거부분은 건별 사유로 돌려준다. 한 건도 성공하지 못해도 200 이며 판정은
 * 결과 목록으로 한다 — 근거는 {@link BatchBulkRetryResponse} 주석 참조.
 *
 * <h3>★건별 결과는 "접수 여부"다 (파이프라인 완료가 아니다)</h3>
 * <p>단건 경로가 상태 선점까지만 요청 안에서 처리하고 실행을 비동기로 넘기므로, 여기서 모으는 건별
 * 성공/실패도 <b>그 건을 접수했는지</b>를 뜻한다. 접수된 영상의 실제 진행은 각 영상 상세의 단계 표시로
 * 확인한다. 선점 결과가 곧 건별 결과이므로 부분 성공 정책·응답 스키마는 종전과 같다.
 *
 * <h3>한 건의 실패가 다른 건을 말아먹지 않는다 (건별 경계)</h3>
 * <p>이 클래스에는 <b>{@code @Transactional} 을 붙이지 않는다</b>. 붙이면 모든 건이 하나의 트랜잭션에
 * 묶여, 5번째 건에서 터진 런타임 예외가 앞 4건의 클레임 전이까지 롤백시킨다(게다가 그 4건은 이미
 * 파이프라인을 기동한 뒤라 DB 만 되돌아가 상태가 어긋난다). 각 건의 트랜잭션 경계는 아래에서 부르는
 * {@link BatchReprocessService#retry(Long)} 가 내부적으로 쓰는 {@code BatchTransitionService} ·
 * 오케스트레이터가 각자 소유한다.
 *
 * <h3>동시성 (CWE-362)</h3>
 * <p>각 건은 단건 재기동과 <b>같은 경로·같은 원자 클레임</b>({@code FAILED→PROCESSING} 조건부 UPDATE)을
 * 그대로 탄다 — 여기서 상태 판정을 재구현하지 않는다. 클레임에 실패한 건은 그 건만 실패로 기록된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchBulkRetryService {

    /** 예상 못 한 예외의 사용자 문구 — 예외 메시지·클래스명을 그대로 노출하지 않는다(CWE-209). */
    static final String UNEXPECTED_FAILURE_REASON = "재기동 중 오류가 발생했습니다.";

    /** 파생영상 거부 사유 — 파생은 배치 파이프라인을 타지 않아 재기동으로 복구되지 않는다. */
    static final String DERIVATIVE_REASON = "다른 영상에서 파생된 영상은 배치를 재기동할 수 없습니다.";

    private final VideoRepository videoRepository;
    private final BatchReprocessService batchReprocessService;

    /**
     * 여러 영상의 배치를 재기동한다.
     *
     * @param request 대상 목록(1~100건, 중복은 1건 취급). 상한·빈 목록 검증은 {@code @Valid} 가 400 처리
     * @return 건별 결과 + 성공/실패 집계
     */
    public BatchBulkRetryResponse retryAll(BatchBulkRetryRequest request) {
        if (request == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "요청 본문이 필요합니다.");
        }
        List<Long> targets = request.distinctRawSns();
        if (targets.isEmpty()) {
            // null 원소만 담긴 목록 등 — @NotEmpty 를 통과하지만 실제 대상이 0건인 경우.
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSns 는 1건 이상이어야 합니다.");
        }

        List<BatchBulkRetryResponse.Item> results = new ArrayList<>(targets.size());
        for (Long rawSn : targets) {
            results.add(retryOne(rawSn));
        }
        BatchBulkRetryResponse response = BatchBulkRetryResponse.of(results);
        log.info("[BatchBulkRetry] done requested={} success={} failure={}",
                targets.size(), response.successCount(), response.failureCount());
        return response;
    }

    /**
     * 1건 재기동 — 어떤 예외도 이 메서드 밖으로 나가지 않는다(건별 격리의 실행 지점).
     *
     * <p>{@link CustomException} 의 메시지는 이미 사용자 문구(예: "배치가 실패(FAILED)한 영상만…")이므로
     * 그대로 전달한다. 그 외 예외는 유형·메시지를 <b>버리고</b> 고정 문구로 대체한다 — 스택트레이스·DB
     * 제약명·경로가 응답에 실리면 안 된다.
     */
    private BatchBulkRetryResponse.Item retryOne(Long rawSn) {
        try {
            if (isDerivative(rawSn)) {
                return BatchBulkRetryResponse.Item.fail(rawSn, DERIVATIVE_REASON);
            }
            batchReprocessService.retry(rawSn);
            return BatchBulkRetryResponse.Item.ok(rawSn);
        } catch (CustomException e) {
            return BatchBulkRetryResponse.Item.fail(rawSn, e.getMessage());
        } catch (RuntimeException e) {
            // 원인은 서버 로그에만 남긴다(유형까지만 — 메시지는 내부 정보를 담을 수 있다).
            log.warn("[BatchBulkRetry] unexpected failure rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return BatchBulkRetryResponse.Item.fail(rawSn, UNEXPECTED_FAILURE_REASON);
        }
    }

    /**
     * 파생영상 여부 — 존재하지 않는 영상은 {@code false} 로 흘려보내 단건 경로의 404 판정에 맡긴다
     * (여기서 "없음"을 따로 판정하면 같은 사실을 두 곳에서 말하게 된다).
     */
    private boolean isDerivative(Long rawSn) {
        return videoRepository.findById(rawSn).map(LsDataRaw::isDerivative).orElse(false);
    }
}
