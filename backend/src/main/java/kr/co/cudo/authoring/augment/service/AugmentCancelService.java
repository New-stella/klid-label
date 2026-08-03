package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentCancelResponse;
import kr.co.cudo.authoring.augment.dto.AugmentProgressUnavailableReason;
import kr.co.cudo.authoring.augment.integration.AugmentCancelCommand;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.VisibleTextNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 증강 취소 — {@code POST /v1/augments/{id}/cancel} (REVIEWER 전용).
 *
 * <h3>3단계 구조</h3>
 * <ol>
 *   <li><b>클레임</b>({@link AugmentCancelTxService#claim}) — 증강 행 {@code FOR UPDATE} 안에서
 *       {@code PENDING → CANCELED} 전이 + 취소 대상 청크 확정. 동시 요청은 여기서 직렬화된다(S4).</li>
 *   <li><b>외부 취소</b>(트랜잭션 밖) — 청크마다 §4.6 {@code POST /jobs/{job_id}/cancel}.
 *       <b>모든</b> 비종결 청크를 순회한다(S2 — 한 건만 보내면 나머지가 계속 처리된다).</li>
 *   <li><b>확정</b>({@link AugmentCancelTxService#markJobsCanceled}) — 성립한 청크만 로컬 종결(S12).</li>
 * </ol>
 *
 * <h3>부분 실패는 숨기지 않는다 (S2)</h3>
 * <p>chunk1 성공 · chunk2 이미 SUCCEEDED · chunk3 네트워크 실패 같은 혼재가 정상 시나리오다.
 * 실패한 청크 순번을 응답에 드러낸다. 남은 비종결 청크는 만료 스윕이 회수하므로 <b>영구 대기는
 * 없다</b>(증강 자체는 이미 {@code CANCELED} 로 종결됐다).
 *
 * <h3>★ 안내는 <b>실제 수행 가능한 동선</b>만 말한다 (DEV_FIX MED-7)</h3>
 * <p>구 문구는 부분 실패에 "다시 시도해 주세요" 를 넣었는데, 재클릭하면 증강이 이미 {@code CANCELED}
 * 라 클레임이 실패해 <b>"이미 종결된 증강이라 취소할 수 없습니다"</b> 가 뜬다 — 수행 불가능한 안내다
 * (CLAUDE.md 구속조항). 재취소 진입점이 없는 이상 사용자가 할 수 있는 일은 없으므로, <b>무엇이
 * 일어나는지</b>만 알린다.
 *
 * <p>덧붙여 벤더의 <b>결정적 4xx</b>(404 {@code JOB_NOT_FOUND} · 409 {@code STATE_CONFLICT})는 애초에
 * 실패가 아니다 — "외부에 취소할 대상이 남아 있지 않다" 는 뜻이라 취소 관점에서는 성공이다.
 * 구 구현은 이것을 타임아웃과 구분하지 못해({@code AugmentExternalProbe} 가 모든 예외를
 * {@code TRANSIENT_ERROR} 로 뭉갰다) 실패로 세고 있었다.
 *
 * <h3>블로킹 규약 (S3) + 요청 예산 (DEV_FIX HIGH-1)</h3>
 * <p>외부 호출은 {@link AugmentExternalProbe} 를 통해 <b>요청 스레드에서만</b> 블로킹하며 개별 상한
 * 타임아웃이 걸린다. 그런데 취소는 비종결 청크 <b>전량</b>을 순회하므로 개별 상한만으로는 부족하다 —
 * 20청크 × 15초면 요청 하나가 280초를 점유한다. 그래서 진행률 조회와 <b>같은 요청 단위 데드라인</b>
 * ({@link AugmentRequestBudget})을 건다. 예산이 마르면 남은 청크는 외부로 보내지 않고 응답에 드러낸다
 * (만료 스윕이 회수하므로 영구 대기는 없다).
 */
@Slf4j
@Service
public class AugmentCancelService {

    private static final String MSG_ALREADY_TERMINAL =
            "이미 종결된 증강이라 취소할 수 없습니다.";
    private static final String MSG_FULLY_CANCELED =
            "증강 요청을 취소했습니다.";
    /**
     * 부분 취소 안내 — <b>재시도를 권하지 않는다</b>(MED-7). 증강은 이미 종결돼 같은 요청을 다시
     * 취소할 수 없고, 사용자가 취할 수 있는 조치도 없다. 대신 "결과가 반영되지 않는다" 는 사실을
     * 알려 불필요한 대기·문의를 막는다.
     */
    private static final String MSG_PARTIALLY_CANCELED =
            "증강 요청을 취소했습니다. 다만 일부 작업은 외부 시스템에 취소가 전달되지 않아 "
                    + "외부에서 계속 처리될 수 있습니다. 그 결과는 취소된 요청이라 반영되지 않으며 "
                    + "별도 조치는 필요하지 않습니다.";

    /** 요청 1회의 외부 취소 호출 총 시간 예산(초) 기본값 — 진행률 조회와 같은 값. */
    static final int DEFAULT_REQUEST_BUDGET_SECONDS =
            AugmentProgressService.DEFAULT_REQUEST_BUDGET_SECONDS;

    private final AugmentCancelTxService cancelTxService;
    private final ExternalAugmentClient externalClient;
    private final AugmentExternalProbe probe;
    private final Duration requestBudget;

    public AugmentCancelService(
            AugmentCancelTxService cancelTxService,
            ExternalAugmentClient externalClient,
            AugmentExternalProbe probe,
            @Value("${authoring.augment.external.request-budget-seconds:"
                    + DEFAULT_REQUEST_BUDGET_SECONDS + "}") long requestBudgetSeconds) {
        this.cancelTxService = cancelTxService;
        this.externalClient = externalClient;
        this.probe = probe;
        this.requestBudget = Duration.ofSeconds(Math.max(1, requestBudgetSeconds));
    }

    /**
     * 증강 요청을 취소한다.
     *
     * @param dataAugSn 증강 결과 PK
     * @param reason    취소 사유(선택, ≤500 — DTO 에서 검증됨). 외부 전송 전
     *                  {@link VisibleTextNormalizer} 로 정규화된다(아래 참조)
     * @param actor     인증 주체(REVIEWER)
     * @throws CustomException 401 미인증, 403 REVIEWER 아님, 404 없는 id, 400 해상도 파생(RESL_*)
     */
    public AugmentCancelResponse cancel(Long dataAugSn, String reason, TokenClaims actor) {
        requireReviewer(actor);
        // 정규화 정책 통일(DEV_FIX LOW) — prompt 5필드는 VisibleTextNormalizer 를 거치는데 취소 사유만
        // 원문이 그대로 외부로 나가고 DB(ERR_MSG_CN)에 적재됐다. 개행이 남으면 로그 위조(CWE-117),
        // U+0000 은 PgJDBC 가 거부해 적재가 500 이 된다 — 같은 성격의 자유 입력에 같은 규칙을 쓴다.
        String normalizedReason = VisibleTextNormalizer.normalizeOrNull(reason);

        AugmentCancelTxService.CancelClaim claim = cancelTxService.claim(dataAugSn);
        if (!claim.claimed()) {
            // 멱등 — 동시 요청의 패자이거나 이미 종결된 증강이다. 409 를 노출하지 않는다(S4).
            return new AugmentCancelResponse(dataAugSn, claim.augTypeCd(), claim.status(),
                    false, false, 0, 0, List.of(), MSG_ALREADY_TERMINAL);
        }

        // 요청 단위 데드라인 — 청크 수만큼 외부 왕복이 곱해지므로 개별 상한만으로는 부족하다(HIGH-1).
        AugmentRequestBudget budget = AugmentRequestBudget.start(requestBudget);
        List<Long> canceled = new ArrayList<>();
        List<Integer> failedJobSeqs = new ArrayList<>();
        for (AugmentCancelTxService.CancelTarget target : claim.targets()) {
            if (cancelOne(dataAugSn, target, normalizedReason, actor, budget)) {
                canceled.add(target.augJobSn());
            } else {
                failedJobSeqs.add(target.jobSeq());
            }
        }

        int transitioned = cancelTxService.markJobsCanceled(dataAugSn, canceled);
        boolean fullyCanceled = failedJobSeqs.isEmpty();
        if (!fullyCanceled) {
            log.warn("[Augment][Cancel] 부분 취소 — 외부 취소 실패 청크 잔존 dataAugSn={} failedJobSeqs={}",
                    dataAugSn, failedJobSeqs);
        }
        log.info("[Augment][Cancel] done dataAugSn={} actor={} target={} canceled={} failed={}",
                dataAugSn, sanitize(actor.sub()), claim.targets().size(), transitioned,
                failedJobSeqs.size());

        return new AugmentCancelResponse(dataAugSn, claim.augTypeCd(), claim.status(),
                true, fullyCanceled, claim.targets().size(), transitioned, failedJobSeqs,
                fullyCanceled ? MSG_FULLY_CANCELED : MSG_PARTIALLY_CANCELED);
    }

    /**
     * 청크 1건의 외부 취소.
     *
     * <h3>{@code externalJobId} 가 없으면 (202 ACK 유실) — 실패로 세지 않는다</h3>
     * <p>보낼 대상이 없으므로 외부 취소는 성립할 수 없지만, 이것을 "취소 실패" 로 표시하면 사용자에게
     * 수행 불가능한 재시도를 권하게 된다(다시 눌러도 job_id 는 생기지 않는다). 대신 <b>로컬만 취소
     * 종결</b>시켜 비종결 행이 남지 않게 한다 — 벤더가 실제로 접수했다면 뒤늦은 웹훅이 도착하지만
     * 그 시점에 job 이 이미 종결이라 멱등 흡수된다.
     *
     * <h3>벤더 404/409 는 <b>실패가 아니다</b> (DEV_FIX MED-7)</h3>
     * <p>404({@code JOB_NOT_FOUND})·409({@code STATE_CONFLICT})는 "그 job 은 외부에서 이미 종결됐거나
     * 존재하지 않는다" 는 결정적 응답이라, 취소 관점에서는 <b>보낼 것이 남아 있지 않음 = 성공</b>이다.
     * Phase 2 가 이 둘을 "정상 흐름의 결정적 응답" 으로 분류해 서킷 집계에서 제외한 것과 같은 판단이다.
     * 실패로 세면 응답에 {@code failedJobSeqs} 가 실려 사용자에게 <b>수행 불가능한 재시도</b>를 권하게 된다.
     *
     * @return 로컬 취소 종결 대상으로 삼을지 여부
     */
    private boolean cancelOne(Long dataAugSn, AugmentCancelTxService.CancelTarget target,
                              String reason, TokenClaims actor, AugmentRequestBudget budget) {
        if (!target.hasExternalJob()) {
            log.info("[Augment][Cancel] 외부 job_id 미수신 청크 — 로컬만 취소 종결 dataAugSn={} jobSeq={}",
                    dataAugSn, target.jobSeq());
            return true;
        }
        if (!budget.hasSlice()) {
            // 요청 예산 소진 — 외부 호출을 <개시하지 않는다>. 남은 청크는 만료 스윕이 회수한다.
            log.warn("[Augment][Cancel] 요청 예산 소진 — 외부 취소 미전송 dataAugSn={} jobSeq={}",
                    dataAugSn, target.jobSeq());
            return false;
        }
        // localTerminal=false — 클레임 시점에 비종결로 확정된 청크만 넘어온다. 이 값은 서버가 정하며
        // 요청 바디로 조작할 수 없다(S8 — AugmentCancelRequest 는 reason 만 받는다).
        // (구 구현의 "result.reason()==null → LOCAL_TERMINAL 스킵" 분기는 제거했다 — localTerminal 을
        //  항상 false 로 넘기므로 그 스킵은 발생할 수 없는 사문 코드였고, 도달 불가 분기를 남기면
        //  "여기서도 처리한다" 는 잘못된 안전 신호를 준다.)
        AugmentCancelCommand command =
                new AugmentCancelCommand(target.externalJobId(), actor, reason, false);
        AugmentExternalProbe.Probe<GenAiCancelResponse> result =
                probe.query(externalClient.cancelJob(command), "취소", dataAugSn, budget.remaining());
        if (result.isSuccess()) {
            return true;
        }
        if (result.isAlreadyTerminalAtVendor()) {
            log.info("[Augment][Cancel] 외부가 이미 종결(status={}) — 로컬만 취소 종결 dataAugSn={} jobSeq={}",
                    result.externalStatusCode(), dataAugSn, target.jobSeq());
            return true;
        }
        if (result.reason() == AugmentProgressUnavailableReason.NOOP) {
            // 외부 미연동(mode=noop) — 보낼 곳이 없다. 로컬 취소는 성립하므로 실패로 세지 않는다.
            log.info("[Augment][Cancel] 외부 미연동(noop) — 로컬만 취소 종결 dataAugSn={} jobSeq={}",
                    dataAugSn, target.jobSeq());
            return true;
        }
        // 그 외 — 서킷 open·타임아웃·5xx·그 밖의 4xx. 벤더가 계속 처리할 수 있으므로 드러낸다.
        return false;
    }

    /** RBAC 이중 검증 — 컨트롤러 {@code @PreAuthorize("hasRole('REVIEWER')")} 와 같은 규칙. */
    private static void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }
}
