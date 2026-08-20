package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchStageBulkRequest;
import kr.co.cudo.authoring.batch.dto.BatchStageBulkResponse;
import kr.co.cudo.authoring.batch.dto.BatchStageSkipBulkRequest;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 작업 묶음 <b>일괄</b> 스킵 · 해제 · 재수행 서비스 — REVIEWER 전용.
 * [@design API-212] [@design API-213] [@design API-214]
 *
 * <h3>무엇을 하는가 (그리고 하지 않는가)</h3>
 * <p>대상 목록을 풀어 <b>단건 서비스를 그대로 호출</b>하고 건별 결과를 모은다. 이 클래스에는
 * 스킵 가능 여부 · 건너뛰기를 해제한 묶음 여부 · 승인 이력 · 파생영상 · 영상 존재 같은 <b>판정이 하나도 없다</b> —
 * 전부 {@link BatchStageSkipService} · {@link BatchStageRerunService} 가 소유한다. 판정을 복제하면 같은
 * 조건이 단건과 일괄에서 다르게 해석되고, 그 갈림은 이 저장소가 반복해서 겪은 결함이다.
 *
 * <h3>★대상 묶음은 시계열({@code VLM}) 하나다 — 여기가 그 판정의 유일한 지점</h3>
 * <p>오토라벨 묶음은 산출물이 <b>라벨</b>이라, 대량으로 건너뛸 수 있게 열면 산출물 품질 축이 조용히
 * 느슨해진다. 그래서 일괄 축에서만 좁히고 <b>단건 경로는 종전대로 두 묶음을 모두 받는다</b>(단건
 * 서비스를 건드리지 않았다). 거부는 400 이며 <b>요청 값을 메시지에 되비추지 않는다</b>(반사 XSS·로그
 * 오염 차단 — 기존 {@code requireSkippableBundle} 관례, CWE-79/117).
 *
 * <h3>★★「이미 스킵됨」·「스킵 상태 아님」은 실패가 아니라 성공이다 (설계 example 과 다름)</h3>
 * <p>{@code API-212}/{@code API-213} 의 <b>example</b> 은 실패 사유로 "이미 건너뛴 작업 묶음입니다." ·
 * "건너뛴 상태가 아닙니다."를 보여준다. 그러나 두 ITEM 의 <b>본문 규범</b>은 "단건 스킵과 판정·기록
 * 규칙이 같다" / "단건 해제와 같다" 이고, 실제 단건 동작은 그 example 과 다르다:
 * <ul>
 *   <li>{@link BatchStageSkipService#skip} — <b>append-only 로 항상 성공</b>. 사유가 바뀌었을 수 있고
 *       그 변경 이력 자체가 감사 대상이다.</li>
 *   <li>{@link BatchStageSkipService#clearSkip} — 스킵 상태가 아니면 <b>멱등 no-op 성공</b>. 그렇지
 *       않으면 해제 버튼을 두 번 누른 것만으로 의미 없는 감사 행이 쌓인다.</li>
 * </ul>
 * <p><b>규범을 따르고 example 을 드리프트로 판정했다.</b> example 대로 사전 조회 가드를 넣으면 같은
 * 조건에서 단건과 일괄이 다르게 동작하는 비대칭이 <b>새로</b> 생긴다. ⇒ 여기서는 "이미/아님" 을 보지
 * 않으며, 건별 실패는 <b>영상 없음(404) · 파생영상(400) · 예상 밖 오류</b>에서만 난다.
 * <b>이 코드가 틀린 것이 아니므로 example 을 근거로 되돌리지 말 것</b>(설계 쪽 example 정정이 후속이다).
 *
 * <h3>★건별로 갈릴 수 없는 것은 루프 <u>전에</u> 판정한다 (요청 단위 400)</h3>
 * <p>스킵 사유는 <b>요청당 하나</b>라 영상마다 결과가 달라질 수 없다. 그런데 그 검증을 건별 호출 안에
 * 두면 {@link CustomException} 이 {@code Item.fail} 로 삼켜져, <b>단건이 400 으로 막는 입력이 일괄에서는
 * 200 + 전건 실패</b>가 된다(위 「건별 실패는 …에서만 난다」 단언도 깨진다). 그래서 사유 정제·판정만은
 * 루프 진입 전에 1회 수행해 요청 전체를 400 으로 거부한다 — 판정 규칙은 단건 서비스의 것을
 * <b>재사용</b>하며 복제하지 않는다.
 *
 * <h3>부분 성공 (all-or-nothing 아님)</h3>
 * <p>되는 것만 처리하고 거부분은 건별 사유로 돌려준다. <b>한 건도 성공하지 못해도 200</b> 이며 판정은
 * 결과 목록으로 한다 — 건별 거부 사유를 상태 코드로 올리면 응답이 영상 상태를 알려주는 오라클이
 * 된다(CWE-209).
 *
 * <h3>한 건의 실패가 다른 건을 말아먹지 않는다 (건별 경계)</h3>
 * <p>이 클래스에는 <b>{@code @Transactional} 을 붙이지 않는다</b>. 붙이면 모든 건이 하나의 트랜잭션에
 * 묶여, 뒤쪽 건에서 터진 런타임 예외가 앞 건들의 표식 INSERT·상태 선점까지 롤백시킨다(게다가 재수행은
 * 이미 비동기 실행을 디스패치한 뒤라 DB 만 되돌아가 상태가 어긋난다). 각 건의 트랜잭션 경계는 단건
 * 서비스가 소유한다.
 *
 * <h3>동시성 (CWE-362)</h3>
 * <p>각 건은 단건과 <b>같은 경로·같은 원자 클레임</b>을 그대로 탄다 — 여기서 상태를 미리 읽어 판정하지
 * 않는다. 클레임에 실패한 건은 그 건만 실패로 기록된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchStageBulkService {

    /**
     * 일괄 축이 받는 유일한 작업 묶음 — 시계열.
     *
     * <p>여기를 넓히면 오토라벨을 대량으로 건너뛸 수 있게 된다. 넓히기 전에 「산출물 품질 축을 사람이
     * 대량으로 우회할 수 있는가」를 먼저 답해야 한다.
     */
    static final BatchStageBundle BULK_BUNDLE = BatchStageBundle.VLM;

    /**
     * 미지원 묶음 거부 문구 — <b>미지의 값</b>과 <b>오토라벨</b>에 같은 문구를 쓴다.
     *
     * <p>요청 값을 되비추지 않는다(CWE-79/117). 허용 값은 상수라 문구에 담아도 상태를 드러내지 않는다.
     */
    static final String UNSUPPORTED_BUNDLE_REASON =
            "일괄 처리는 시계열(" + BULK_BUNDLE.name() + ") 작업 묶음만 지원합니다.";

    /** 예상 못 한 예외의 사용자 문구 — 예외 메시지·클래스명을 그대로 노출하지 않는다(CWE-209). */
    static final String UNEXPECTED_FAILURE_REASON = "처리 중 오류가 발생했습니다.";

    private final BatchStageSkipService skipService;
    private final BatchStageRerunService rerunService;

    /**
     * 여러 영상의 작업 묶음을 한 번에 건너뛴다. [@design API-212]
     *
     * <p>사유는 <b>요청당 하나</b>이며 대상 전건에 같은 값으로 기록된다 — 영상마다 다른 사유를 받으면
     * 일괄로 처리할 이유가 사라진다.
     */
    public BatchStageBulkResponse skipAll(String bundleName, BatchStageSkipBulkRequest request) {
        requireBody(request);
        BatchStageBundle bundle = requireBulkBundle(bundleName);
        String reason = request.reason();
        // ★ 사유는 <요청당 하나>다 — 건별로 갈릴 수 없으므로 루프에 들어가기 <전에> 1회 검증한다.
        //   안 하면 단건이 400 으로 막는 입력(제어문자만으로 이루어진 사유 등)이 일괄에서는 건별 실패로
        //   삼켜져 <200 + 전건 실패>가 된다 — 같은 조건에 단건과 일괄이 다르게 답하는 비대칭이고,
        //   덤으로 확정적으로 실패할 100건의 DB 왕복을 돈다. 판정은 단건과 <같은 메서드>를 재사용한다.
        BatchStageSkipService.sanitizeReason(reason);
        return apply("skip", bundle, request.distinctRawSns(),
                rawSn -> skipService.skip(rawSn, bundle.name(), reason));
    }

    /** 여러 영상의 작업 묶음 스킵을 한 번에 해제한다 — 표식만 지우고 작업을 실행하지 않는다. [@design API-213] */
    public BatchStageBulkResponse clearAll(String bundleName, BatchStageBulkRequest request) {
        requireBody(request);
        BatchStageBundle bundle = requireBulkBundle(bundleName);
        return apply("clear", bundle, request.distinctRawSns(),
                rawSn -> skipService.clearSkip(rawSn, bundle.name()));
    }

    /**
     * 건너뛰기를 해제한 작업 묶음을 여러 영상에 대해 한 번에 다시 수행한다 — <b>접수</b>까지다. [@design API-214]
     *
     * <p>수락 조건(건너뛰기를 해제한 묶음인가 · 승인 이력 · 완주 상태 · 클레임)은 전부 단건 서비스가 판정한다.
     */
    public BatchStageBulkResponse rerunAll(String bundleName, BatchStageBulkRequest request) {
        requireBody(request);
        BatchStageBundle bundle = requireBulkBundle(bundleName);
        return apply("rerun", bundle, request.distinctRawSns(),
                rawSn -> rerunService.rerun(rawSn, bundle.name()));
    }

    /**
     * 공통 실행 — 건별 격리 + 결과 수집.
     *
     * <p>어떤 예외도 루프 밖으로 나가지 않는다. {@link CustomException} 의 메시지는 이미 사용자 문구이므로
     * 그대로 전달하고, 그 외 예외는 유형·메시지를 <b>버리고</b> 고정 문구로 대체한다 — 스택트레이스·DB
     * 제약명·경로가 응답에 실리면 안 된다(CWE-209).
     */
    private BatchStageBulkResponse apply(String action, BatchStageBundle bundle,
                                         List<Long> targets, Consumer<Long> operation) {
        if (targets.isEmpty()) {
            // null 원소만 담긴 목록 등 — @NotEmpty 를 통과하지만 실제 대상이 0건인 경우.
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSns 는 1건 이상이어야 합니다.");
        }

        List<BatchStageBulkResponse.Item> results = new ArrayList<>(targets.size());
        for (Long rawSn : targets) {
            results.add(applyOne(action, rawSn, operation));
        }
        BatchStageBulkResponse response = BatchStageBulkResponse.of(results);
        log.info("[BatchStageBulk] {} done bundle={} requested={} success={} failure={}",
                action, bundle, targets.size(), response.successCount(), response.failureCount());
        return response;
    }

    /** 1건 처리 — 건별 격리의 실행 지점. 어떤 예외도 이 메서드 밖으로 나가지 않는다. */
    private BatchStageBulkResponse.Item applyOne(String action, Long rawSn, Consumer<Long> operation) {
        try {
            operation.accept(rawSn);
            return BatchStageBulkResponse.Item.ok(rawSn);
        } catch (CustomException e) {
            return BatchStageBulkResponse.Item.fail(rawSn, e.getMessage());
        } catch (RuntimeException e) {
            // 원인은 서버 로그에만 남긴다(유형까지만 — 메시지는 내부 정보를 담을 수 있다).
            log.warn("[BatchStageBulk] {} unexpected failure rawSn={} cause={}",
                    action, rawSn, e.getClass().getSimpleName());
            return BatchStageBulkResponse.Item.fail(rawSn, UNEXPECTED_FAILURE_REASON);
        }
    }

    /**
     * ★일괄 축의 대상 묶음 판정 — <b>이 메서드가 유일한 판정 지점</b>이다(복제 금지).
     *
     * <p>{@link BatchStageBundle#parse}(미지 값 → {@code null})를 그대로 쓰고 결과가 시계열이 아니면
     * 400 이다. 미지의 값과 오토라벨을 <b>같은 문구</b>로 거부해 응답이 "무엇이 존재하는 묶음인지"를
     * 알려주지 않게 한다.
     */
    private static BatchStageBundle requireBulkBundle(String bundleName) {
        BatchStageBundle bundle = BatchStageBundle.parse(bundleName);
        if (bundle != BULK_BUNDLE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, UNSUPPORTED_BUNDLE_REASON);
        }
        return bundle;
    }

    /** 본문 필수 — {@code @Valid} 가 1차로 막지만 서비스 직접 호출 경로에서도 fail-closed 로 둔다. */
    private static void requireBody(Object request) {
        if (request == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "요청 본문이 필요합니다.");
        }
    }
}
