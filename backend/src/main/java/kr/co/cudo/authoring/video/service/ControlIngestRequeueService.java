package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkRequest;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkResponse;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueResponse;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관제 인입 <b>종결(FAILED) 재큐</b> 서비스 — REVIEWER 전용 운영 진입점 (설계 §6-0-1-b).
 *
 * <h3>왜 이 진입점이 이번 범위인가</h3>
 * <p>설계 §6-0-1 은 "①(미도착 대기 상한)과 ②(재큐)는 <b>반드시 함께</b> 들어간다"를 구속으로 뒀다.
 * 통로({@link LsDataIngestRepository#requeueFailedForRetry})만 만들고 운영 진입점을 미루면 상한이
 * {@code FAILED} 생성률만 올리고 회수는 여전히 수동 SQL 이라, 그 축에서는 <b>도입 전보다 나빠진다</b>.
 *
 * <h3>단건 + 일괄을 함께 제공하는 이유</h3>
 * <p>실사용 시나리오가 <b>대량 오설정 회수</b>다 — {@code authoring.storage.raw-mount-roots} 가 관제 NAS
 * 실경로와 어긋나면 tick 당 상한(100)만큼 {@code FAILED} 가 양산된다. 단건만 있으면 운영자는 결국
 * 수동 SQL 로 돌아간다.
 *
 * <h3>자동 재큐 배치는 두지 않는다</h3>
 * <p>종결 사유(경로 오설정·NAS 미마운트)는 <b>사람이 고쳐야</b> 사라진다. 자동 재큐는 원인이 그대로인
 * 채 {@code FAILED} ↔ {@code PENDING} 무한 왕복만 만든다(로그·부하만 늘고 회수는 안 된다).
 *
 * <h3>안전 규약</h3>
 * <ul>
 *   <li><b>인가</b>: 컨트롤러 {@code @PreAuthorize("hasRole('REVIEWER')")} (CWE-862/863).</li>
 *   <li><b>상태 조건 고정</b>: 되살리는 대상은 {@code FAILED} 뿐이다. 처리 중({@code PROCESSING})
 *       탈취·성공 종결({@code DONE}) 되살리기는 리포지토리 술어가 막는다(중복 적재 차단, CWE-362).</li>
 *   <li><b>상한 필수</b>: 일괄은 요청 상한({@code <= 500})을 검증해 넘긴다(CWE-770).</li>
 *   <li><b>감사 로그</b>: 누가·몇 건을 남긴다. 관제 자유텍스트는 로그에 넣지 않으므로 정제 대상 값이
 *       없다 — 남기는 값은 인입 PK(수치)·건수·행위자 식별자뿐이다(CWE-117/359).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlIngestRequeueService {

    /** 감사 로그 행위자 표기 길이 상한 — 토큰 subject 는 짧다(비정상 값의 로그 폭주 방지). */
    private static final int ACTOR_LOG_MAX_LENGTH = 64;

    private final LsDataIngestRepository ingestRepository;

    /**
     * 인입 1건 재큐 — {@code FAILED} → {@code PENDING}(대기 예산 리셋 포함).
     *
     * @param rcptnSn 대상 인입 행 PK
     * @param actor   감사 로그에 남길 행위자 식별자(인증 주체) — 표시용이며 인가 판정에 쓰지 않는다
     * @return 재큐 결과(재큐 건수 + 남은 FAILED 건수)
     * @throws CustomException {@link ErrorCode#INVALID_INPUT} 식별자 누락 /
     *                         {@link ErrorCode#NOT_FOUND} 인입 행 없음 /
     *                         {@link ErrorCode#CONFLICT} 종결(FAILED) 상태가 아님
     */
    @Transactional("controlTransactionManager")
    public ControlIngestRequeueResponse requeue(Long rcptnSn, String actor) {
        if (rcptnSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rcptnSn 은 필수입니다.");
        }
        // 존재 검증(404) — "없는 행"과 "상태가 달라 못 되살림"(409)을 구분해야 운영자가 원인을 안다.
        if (!ingestRepository.existsById(rcptnSn)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "인입 행을 찾을 수 없습니다.");
        }
        int requeued = ingestRepository.requeueFailedForRetry(rcptnSn);
        if (requeued != 1) {
            // 조건부 UPDATE 가 0행 — 이미 재큐됐거나(PENDING) 처리 중이거나 성공 종결(DONE)이다.
            throw new CustomException(ErrorCode.CONFLICT,
                    "종결(FAILED)된 인입 행만 재큐할 수 있습니다.");
        }
        long remaining = ingestRepository.countByPrcsSttsCd(LsDataIngest.PRCS_STTS_FAILED);
        log.info("[ControlIngestRequeue] single requeue actor={} rcptnSn={} remainingFailed={}",
                auditActor(actor), rcptnSn, remaining);
        return new ControlIngestRequeueResponse(rcptnSn, requeued, remaining);
    }

    /**
     * 인입 일괄 재큐 — {@code FAILED} 행을 <b>오래된 수신일시 순</b>으로 최대 {@code limit} 건 되살린다.
     *
     * <p>0건이어도 <b>예외가 아니다</b> — "되살릴 게 없다"는 정상 결과이며 응답의 {@code requeued=0}
     * 으로 드러난다(일괄은 대상 집합이 비어 있을 수 있는 관리 작업이다).
     *
     * @param request 상한(미지정 시 기본값). 대상 조건은 상태 {@code FAILED} 고정이다.
     * @param actor   감사 로그에 남길 행위자 식별자
     */
    @Transactional("controlTransactionManager")
    public ControlIngestRequeueBulkResponse requeueBatch(ControlIngestRequeueBulkRequest request, String actor) {
        int limit = (request == null) ? ControlIngestRequeueBulkRequest.DEFAULT_LIMIT : request.effectiveLimit();
        // 방어적 clamp — 컨트롤러 @Valid 를 우회한 직접 호출(테스트·후속 내부 호출)에서도 상한을 지킨다.
        if (limit < 1 || limit > ControlIngestRequeueBulkRequest.MAX_LIMIT) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "limit 은 1 이상 " + ControlIngestRequeueBulkRequest.MAX_LIMIT + " 이하여야 합니다.");
        }
        int requeued = ingestRepository.requeueFailedBatch(limit);
        long remaining = ingestRepository.countByPrcsSttsCd(LsDataIngest.PRCS_STTS_FAILED);
        log.info("[ControlIngestRequeue] bulk requeue actor={} limit={} requeued={} remainingFailed={}",
                auditActor(actor), limit, requeued, remaining);
        return new ControlIngestRequeueBulkResponse(requeued, limit, remaining);
    }

    /**
     * 감사 로그용 행위자 표기 — 토큰 subject 를 정제해 남긴다(CWE-117).
     *
     * <p>서명 검증을 통과한 토큰의 클레임이지만 <b>값 자체는 우리가 만든 문자열이 아니다</b>. 개행이
     * 섞이면 감사 로그에 가짜 라인을 끼워 넣을 수 있으므로 로그 경계에서 정제한다(길이도 제한).
     */
    private static String auditActor(String actor) {
        return LogSanitizer.sanitize(actor, ACTOR_LOG_MAX_LENGTH);
    }
}
