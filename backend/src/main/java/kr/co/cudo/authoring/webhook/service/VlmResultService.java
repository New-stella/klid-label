package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepositoryCustom;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.VlmResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 외부 VLM <b>verify</b> 결과 인계 처리 서비스 (콜백 수신부). [req: R4]
 *
 * <p>{@code POST /v1/vlm/callback} 진입 후 호출된다.
 * 적재: {@link LsDataMeta} (K/V) + {@link LsDataMetaReview} 검수 큐(PENDING).
 *
 * <h3>★ 창구는 둘이고 결과가 가는 자리가 다르다</h3>
 * <p>콜백 바디에는 <b>창구 구분자가 없다</b>. 위탁 시 등록한 채널로 되짚어 갈라 적재한다.
 * <table border="1">
 *   <caption>창구별 적재 대상</caption>
 *   <tr><th>채널</th><th>창구</th><th>적재</th><th>검수큐</th></tr>
 *   <tr><td>{@code VLM}</td><td>묘사</td><td>{@code vlm.description} 시계열 서술 전문(≤2000)</td><td><b>진입</b></td></tr>
 *   <tr><td>{@code VLM_SUB}</td><td>추가 질문</td><td>이벤트 어노테이션 질의응답 축 초안
 *       ({@link TimeseriesSubResultApplier})</td><td>미진입 — 그 도메인이 소유</td></tr>
 *   <tr><td>(레거시) {@code 0-8}·{@code 8-16} …</td><td>구 구간 서술</td><td>기존 행</td><td>기존 유지 — <b>보존</b></td></tr>
 * </table>
 *
 * <p><b>판정 항목은 오지 않는다</b> — 발생 여부·일치도는 우리가 연동하지 않는 판정 창구 전용이라
 * 적재 대상 자체가 없다. 그 키를 되살리지 말 것.
 *
 * <p>레거시 구간 키 행은 <b>삭제·마이그레이션하지 않는다</b>. metaKey 가 달라 {@code (RAW_SN, META_KEY)} UK
 * 충돌이 없으므로 신규 키가 그대로 추가된다.
 *
 * <p>검수큐 진입은 <b>화이트리스트</b>다 — {@code vlm.description} 만 검토행을 만든다. "description 이 아닌
 * 건 전부 제외"가 fail-closed 라 향후 {@code vlm.*} 키가 늘어도 검수큐/데이터마트 뷰로 새지 않는다.
 *
 * <h3>트랜잭션 원자성 (DEV_FIX C-1)</h3>
 * <p>콜백 처리 전체(원장 락 조회 → META upsert → 검수큐 → 마킹 전이 → 원장 PROCESSED 마킹)를
 * <b>단일 트랜잭션</b>으로 수행한다. {@link WebhookIdempotencyLedger#markProcessedInTx}(REQUIRED)로
 * 원장 마킹을 outer 트랜잭션에 참여시켜, 처리 중 어떤 단계가 실패하면 원장 PROCESSED 전이도 함께
 * 롤백된다 → 벤더 재전송으로 복구 가능(데이터 유실 차단). 중간 예외는 catch 하지 않고 전파한다.
 *
 * <h3>동시성 (DEV_FIX #1/#3, CWE-362)</h3>
 * <p>동시 콜백은 <b>두 축</b>이며 방어가 서로 다르다. 무엇을 막고 무엇은 막지 않는지 아래대로다.
 * <table border="1">
 *   <caption>동시 콜백 방어 범위</caption>
 *   <tr><th>축</th><th>방어</th></tr>
 *   <tr>
 *     <td><b>동일 request_id</b> 동시 콜백</td>
 *     <td>{@link WebhookIdempotencyLedger#lookupForProcessing}(비관적 락)으로 직렬화. 두 번째 콜백은
 *         첫 콜백 커밋(PROCESSED) 후 락을 얻어 <b>멱등 스킵</b>된다.</td>
 *   </tr>
 *   <tr>
 *     <td><b>재위탁으로 request_id 가 다른</b> 동시 콜백<br>(스위퍼 재위탁 + 옛 위탁의 지각 콜백)</td>
 *     <td>원장 락이 <b>걸리지 않는다</b>. 그래서 ①값 적재는 find-then-save 가 아니라
 *         {@link LsDataMetaRepositoryCustom#upsertMetaReturning}(PostgreSQL {@code ON CONFLICT})
 *         <b>원자 upsert</b> 로 하고, ②<b>"신규인가" 판정도 그 upsert 문의 {@code RETURNING} 값</b>으로
 *         한다 — 두 트랜잭션 중 정확히 한 쪽만 {@code inserted=true} 를 받으므로 검수큐
 *         ({@link LsDataMetaReview}) 행이 <b>중복 생성되지 않는다</b>.</td>
 *   </tr>
 * </table>
 *
 * <p>⚠ <b>판정을 upsert 앞의 별도 SELECT 로 되돌리지 말 것</b>: READ COMMITTED 에서 두 트랜잭션이
 * 각자 "없음"을 관측해 둘 다 신규로 오판하면 검수큐에 PENDING 행이 2건 남는다. 그 2건은 영상 승인 시
 * ({@code MetaService.autoApproveOnVideoApproval} 이 rawSn 의 리뷰행을 전부 승인) 모두 APPROVED 가 되고,
 * 리뷰행을 조인하는 <b>메타 단위</b> 뷰 {@code V_COMPLETED_META} 를 통해 같은 메타가 관제에 2건 나간다.
 *
 * <p><b>막지 않는 것(수용)</b>: request_id 가 다른 두 콜백의 <b>값</b> 중 어느 것이 최종으로 남는지는
 * 커밋 순서에 달렸다(last-write-wins). 서로 다른 위탁의 결과라 어느 쪽도 오류가 아니며, 승인 완료 영상이면
 * {@link #recheckIfApproved}(R13)가 재검수를 강제하므로 REVIEWER 확인 없이 관제로 나가지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VlmResultService {

    /** 검증 서술 — 검수큐(LS_DATA_META_REVIEW) 진입 대상이자 데이터마트 노출 축. */
    public static final String META_KEY_DESCRIPTION = "vlm.description";

    /**
     * 일치도 메타 키 — <b>과거 적재분 전용</b>이다. 이 서비스는 더 이상 이 키를 쓰지 않는다.
     *
     * <p>판정 창구를 연동하지 않게 되어 새 값이 생기지 않지만, 이미 적재된 행은 지우지 않고
     * 그 값을 읽어 내보내던 경로도 그대로 둔다. 그 행을 <b>읽기 전용으로 분류</b>하고 산출물
     * 조달에서 제외하는 판정이 이 키를 이름으로 가리키므로 상수를 존치한다 — 지우면 그
     * 분류가 문자열 리터럴로 흩어지고, 기존 행이 편집 대상으로 승격될 위험이 생긴다.
     */
    public static final String META_KEY_ACCURACY = "vlm.accuracy";

    /** 일치도(0~1) — <b>화면 전용</b>. 검수큐 미진입이라 {@code V_COMPLETED_META} 에 도달하지 않는다(R12, 의도된 설계). */

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final LsDataMetaRepository metaRepository;
    private final LsDataMetaReviewRepository reviewRepository;
    private final VideoRepository videoRepository;
    private final WebhookIdempotencyLedger ledger;
    private final LsMarkingRepository markingRepository;
    /** 검수 완료(APPROVED) 여부 판정용 영상 상태 조회 — 재검수·통지 게이트(R13). */
    private final ReviewApprovalGate approvalGate;
    private final ApplicationEventPublisher eventPublisher;
    /** 추가 질문 결과의 반영 위임처 — 이벤트 어노테이션 시맨틱은 그 도메인이 소유한다. */
    private final TimeseriesSubResultApplier subResultApplier;

    /**
     * verify 콜백 처리 — 벤더 확정 계약(v2.0.1) 정합.
     *
     * <p>보안: 콜백 진입은 HMAC 무인증(벤더 규격)이므로 발급 게이트(isIssued)로 무단 주입을 차단한다.
     *
     * @return true = 신규 처리(적재 또는 failed 기록) / false = 멱등 스킵
     */
    @Transactional("controlTransactionManager")
    public boolean handle(VlmResultRequest req) {
        String requestId = req.requestId();

        // 1) 원장 단일 조회(비관적 락) — 발급 게이트 + 멱등 + rawSn 역조회를 1회 SELECT 로 통합(DB-HIGH).
        //    동일 request_id 동시 콜백은 여기서 직렬화된다(CWE-362).
        WebhookIdempotencyLedger.Entry entry = ledger.lookupForProcessing(requestId)
                .orElseThrow(() -> {
                    log.warn("[Webhook][Vlm] unknown request_id={}", safe(requestId));
                    return new CustomException(ErrorCode.UNAUTHORIZED,
                            "발급되지 않은 request_id 입니다.");
                });

        // 2) 멱등 재수신 스킵
        if (entry.state() == WebhookIdempotencyLedger.State.PROCESSED) {
            log.info("[Webhook][Vlm] duplicate result skipped request_id={}", safe(requestId));
            return false;
        }

        // 3) request_id → rawSn (콜백 바디에 rawSn 없음)
        Long rawSn = entry.rawSn();
        if (rawSn == null) {
            log.warn("[Webhook][Vlm] no rawSn mapping request_id={}", safe(requestId));
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "request_id 에 매핑된 rawSn 이 없습니다.");
        }

        // 4) status 화이트리스트 엄격 검증 (DEV_FIX #3) — completed|failed 만 허용.
        //    DTO @Pattern 이 1차 차단하나, 서비스 분기도 "failed 외 전부 completed" 로 두면 미지 status
        //    (오타·빈 의미값)가 completed 로 오처리되고 조기 PROCESSED 마킹으로 후속 정상 콜백이 멱등 스킵
        //    → 데이터 유실. 미지 status 는 INVALID_INPUT 으로 거부하고 멱등 마킹을 하지 않아 재전송을 허용한다.
        String status = req.status();
        boolean failed = "failed".equals(status);
        boolean completed = "completed".equals(status);
        if (!failed && !completed) {
            log.warn("[Webhook][Vlm] unsupported status={} request_id={} (거부 — 재전송 허용)",
                    safe(status), safe(requestId));
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 status 입니다(completed|failed 만 허용).");
        }

        // 5) failed → 적재 없이 error 기록 + 마킹 고착 해제(VLM_FAILED) + 멱등 마킹 (원자적)
        if (failed) {
            handleFailed(req, requestId, rawSn, entry.issuedAt());
            ledger.markProcessedInTx(requestId, requestId);
            return true;
        }

        // 6) completed → 영상 존재 검증 (사용자 대면 메시지는 일반화, rawSn 은 서버 로그만 — F-6)
        if (!videoRepository.existsById(rawSn)) {
            log.warn("[Webhook][Vlm] target video not found rawSn={} request_id={}",
                    rawSn, safe(requestId));
            throw new CustomException(ErrorCode.NOT_FOUND, "대상 영상을 찾을 수 없습니다.");
        }

        // 7) 창구별 적재 — 콜백 바디에 창구 구분자가 없으므로 위탁 시 등록한 채널로 되짚는다.
        //    채널을 모르는 레거시 행(채널 값이 비어 있는 과거 위탁)은 묘사 축으로 본다 — 구 단일
        //    위탁이 채우던 자리가 그 축이라 그렇게 해야 과거 콜백이 종전대로 처리된다.
        boolean descriptionChanged;
        if (LsWebhookIdempotency.CHANNEL_VLM_SUB.equals(entry.channel())) {
            boolean drafted = subResultApplier.applySubDescription(rawSn, req.results().description());
            log.info("[Webhook][Vlm] sub result routed to event annotation draft rawSn={} drafted={}",
                    rawSn, drafted);
            descriptionChanged = false;
        } else {
            descriptionChanged = applyResults(rawSn, req.results());
        }

        // 8) 마킹 상태 VLM_COMPLETED 전이
        //  ★ 조회 범위는 ACTIVE_STATUSES(PENDING + VLM_REQUESTED) 다 — VLM_REQUESTED 단독이 아니다.
        //    제출이 논블로킹이 되면서 콜백이 ACK 보다 먼저 커밋될 수 있는데, 그때 마킹이 아직 PENDING
        //    이면 여기서 0건 전이로 끝나고 이후 스텝이 PENDING→VLM_REQUESTED 로 올려 <b>영구 고착</b>된다
        //    (mock/저지연 벤더에서 현실적). 스텝의 선커밋과 함께 <b>양단 방어</b>를 이룬다.
        //    종결 상태는 포함하지 않으므로 이미 VLM_COMPLETED 면 0건 = no-op(멱등).
        List<LsMarking> markings = markingsInScope(rawSn, entry.issuedAt());
        for (LsMarking m : markings) {
            m.markVlmCompleted();
        }

        // 9) 멱등 마킹 — 마지막에 outer 트랜잭션 안에서 수행(원자성, C-1)
        ledger.markProcessedInTx(requestId, requestId);

        log.info("[Webhook][Vlm] result applied request_id={} rawSn={} descriptionChanged={} markingsTransitioned={}",
                safe(requestId), rawSn, descriptionChanged, markings.size());
        return true;
    }

    /**
     * 묘사 결과 적재 — {@code vlm.description} 원자 upsert. [req: R4]
     *
     * <p>신규 서술이면 검수큐(PENDING)에 넣고, 기존 서술이 <b>실제로 바뀐</b> 경우에만 재검수·통지를 건다
     * ({@link #recheckIfApproved}). 값이 같으면 멱등 upsert 만 하고 아무 부수효과도 만들지 않는다.
     *
     * <p><b>"신규인가"의 단일 원천은 upsert 문의 {@code RETURNING} 값</b>이다(선행 SELECT 아님) —
     * 근거는 클래스 javadoc 의 동시성 표. 선행 SELECT 는 R13 의 "값이 실제로 바뀌었는가" 판정에만 쓴다.
     *
     * @return 기존 서술이 실제로 갱신됐으면 true (로그·관측용)
     */
    private boolean applyResults(Long rawSn, VlmResultRequest.Results results) {
        String description = results.description();

        // upsert 이전 값 — R13 의 "실제로 바뀌었는가" 판정에만 쓴다.
        // ⚠ "신규인가"(검수행 생성 여부) 판정에는 쓰지 않는다 — 아래 upsert 반환값이 그 단일 원천이다.
        Optional<LsDataMeta> before = metaRepository.findByRawSnAndMetaKey(rawSn, META_KEY_DESCRIPTION);

        // 원자 upsert — 재위탁 동시 콜백은 request_id 가 달라 원장 락이 걸리지 않으므로
        // find-then-save 로는 UNIQUE 위반/값 유실이 난다(CWE-362). 삽입/갱신 판정과 대상 PK 도
        // 같은 문장의 RETURNING 으로 받아, 두 트랜잭션 중 정확히 한 쪽만 "삽입"이 되게 한다.
        LsDataMetaRepositoryCustom.MetaUpsertOutcome outcome =
                metaRepository.upsertMetaReturning(rawSn, META_KEY_DESCRIPTION, description);

        if (outcome.inserted()) {
            // 검수큐는 description 행만(화이트리스트) — 이 접두 아래 키가 늘어도 새지 않는 fail-closed 다.
            // metaSn 은 upsert 가 돌려준 값이다(재조회 없음 — 재조회는 다른 트랜잭션의 행을 볼 수 있다).
            reviewRepository.save(LsDataMetaReview.createAuto(
                    outcome.metaSn(), rawSn, null,
                    LsDataMetaReview.META_TYPE_VLM,
                    LsDataMetaReview.SRC_AI_SERVER,
                    LsDataMetaReview.STTS_PENDING));
            return false;
        }

        // 갱신 경로 — 값이 실제로 바뀐 경우에만 재검수·통지(R13).
        // ⚠ 경합으로 이전 값을 모르면(선행 SELECT 는 empty 인데 upsert 는 갱신 = 다른 트랜잭션이 먼저
        //   INSERT 함) "바뀐 것으로" 본다. 재검토 1회가, 승인 없이 새 서술이 관제로 나가는 것보다 안전하다
        //   (fail-safe 방향).
        boolean changed = before
                .map(prev -> !description.equals(prev.getMetaVl()))
                .orElse(true);
        if (changed) {
            recheckIfApproved(rawSn, outcome.metaSn());
        }
        return changed;
    }

    /**
     * 검수 완료(APPROVED) 영상의 서술이 갱신되면 <b>재검수 + 통지</b>를 강제한다. [req: R13]
     *
     * <p>근거: 데이터마트 뷰 {@code V_COMPLETED_META} 는 라이브 {@code LS_DATA_META} 를 조인하므로,
     * 값만 갱신하고 검토상태를 APPROVED 로 두면 <b>REVIEWER 승인 없이 새 서술이 관제로 나간다</b>. 또한
     * CLAUDE.md 의 "검수 완료 후 수정 시마다 {@code TASK_MODIFIED} 통지" 규칙도 이 경로만 위반하고 있었다.
     *
     * <p>발행 방식은 {@code MetaService.update} 와 동일하다 — 같은 이벤트({@link TaskModifiedEvent}),
     * 같은 변경종류({@link ChangeType#META_UPDATED}). 소비는 {@code TaskModifiedAccumulateListener}
     * (AFTER_COMMIT)가 하므로 이 트랜잭션이 롤백되면 통지도 발생하지 않는다.
     *
     * <h3>★ {@code exportRegenerated=true} — "저장은 됐는데 산출물이 안 바뀐다" 차단 (@req R10)</h3>
     * <p>서술은 이제 export JSON 의 {@code video.vd_description} <b>입력</b>이다
     * ({@code VlmDescriptionPolicy}). 구 구현의 {@code false}(디스크 무변경 전제)를 유지하면 통지만
     * 나가고 산출 폴더는 <b>옛 서술로 고착</b>된다 — 그 사이 관제가 픽업하는 파일이 화면 값과 어긋난다.
     * {@code true} 로 발행하면 디바운스 flush 가 {@code AsyncDatasetExportRunner#runReExportThenNotify}
     * 로 위임해 <b>export 전량 재생성 → 통지</b> 순으로 직렬화한다.
     *
     * <p>이 트리거는 콘텐츠 해시 편입({@code LabelContentHasher} {@code VDSC} 블록)과 <b>정합 세트</b>다.
     * 다만 <b>현재 배포 형상에서 실제로 일하는 쪽은 이 트리거 하나</b>다 — 해시가 게이트하는 지점은
     * {@code DatasetExportService.export} 의 {@code forceRegenerate=false} 분기뿐인데 <b>그 값으로
     * 진입하는 프로덕션 경로가 0건</b>이기 때문이다(유일한 후보 {@code DatasetExportBridge.onReExport}
     * 가 소비하는 {@code DatasetReExportEvent} 는 발행처가 없는 휴면 리스너이고, 나머지 재산출 경로는
     * 전부 {@code force=true}). 즉 <b>해시는 기록만 되고 아무것도 막지 않는다</b> — 상세는
     * {@code LabelContentHasher.appendVdDescription} javadoc.
     *
     * <p>그럼에도 둘을 세트로 두는 이유는 {@code force=false} 경로가 되살아나는 순간 해시가 없으면
     * <b>저장은 바뀌었는데 산출 파일은 옛 서술로 고착</b>되기 때문이다(CLAUDE.md 「개인정보 보호」의
     * 동일 교훈). 반대로 해시만 있고 이 트리거가 없으면 재산출 자체가 시작되지 않는다.
     *
     * <p>값이 실제로 바뀐 경우에만 여기 도달하므로({@code applyResults} 의 {@code changed} 가드)
     * 무변경 재수신이 재생성을 폭주시키지 않는다.
     *
     * <p><b>미승인 영상은 대상이 아니다</b> — 아직 검토행이 PENDING 이라 되돌릴 것이 없고, 검수 전 갱신은
     * 통지 대상이 아니다(라벨·메타 경로 공통 가드).
     *
     * <p>수정자 번호는 {@code null} 이다 — 외부 콜백에는 행위자가 없다(소비처는 이 값을 쓰지 않는다).
     */
    private void recheckIfApproved(Long rawSn, Long descriptionMetaSn) {
        if (!approvalGate.isApproved(rawSn)) {
            return;
        }
        long reopened = reviewRepository.findByDataMetaSnIn(List.of(descriptionMetaSn)).stream()
                .filter(LsDataMetaReview::reopenForRecheck)
                .count();
        // Phase 7a-1 — exclude: R13 은 항목 단위(LsDataMetaReview) 재검토 축을 이미 갖고 있어
        //   영상 단위 재검토 표시(needsRecheck) 대상이 아니다(기본값 false 유지).
        eventPublisher.publishEvent(new TaskModifiedEvent(
                rawSn, null, ChangeType.META_UPDATED, null, true));
        log.info("[Webhook][Vlm] approved video timeseries updated — recheck required rawSn={} reopened={}",
                rawSn, reopened);
    }

    /**
     * 이 콜백이 전이해도 되는 마킹 — <b>이 위탁보다 나중에 생긴 마킹은 제외</b>한다 (L6).
     *
     * <h3>무엇을 막는가</h3>
     * <p>콜백 선행 레이스를 닫기 위해 조회 범위를 {@code ACTIVE_STATUSES}(PENDING 포함)로 넓힌 부작용:
     * 앞선 위탁이 {@code VLM_FAILED} 로 종결된 뒤 작업자가 <b>다시 마킹</b>하면 그 새 마킹(PENDING)이
     * 활성 상태로 존재하는데, 이때 옛 request 의 지각 콜백이 도착하면 <b>한 번도 위탁된 적 없는</b> 새
     * 마킹을 {@code VLM_COMPLETED} 로 올려버린다(그 마킹의 시계열 분석은 실제로 수행되지 않았다).
     *
     * <h3>판정</h3>
     * <p>{@code VLM_REQUESTED} 는 이 위탁으로 올라간 상태이므로 항상 대상이다. {@code PENDING} 은
     * <b>위탁 발급 시각(원장 REG_DT) 이후에 생성된 것만</b> 제외한다 — 콜백 선행 레이스의 마킹은 위탁
     * <b>전에</b> 이미 존재하므로 그대로 전이되어 레이스 해소는 유지된다. 발급 시각을 알 수 없으면
     * (구 원장 행 등) 종전과 동일하게 전부 대상으로 둔다(안전한 기본값 — 고착 방지 우선).
     */
    private List<LsMarking> markingsInScope(Long rawSn, java.time.LocalDateTime issuedAt) {
        List<LsMarking> markings = markingRepository.findByRawSnAndSttsCdIn(
                rawSn, LsMarking.ACTIVE_STATUSES);
        if (issuedAt == null) {
            return markings;
        }
        return markings.stream()
                .filter(m -> !isCreatedAfterSubmit(m, issuedAt))
                .toList();
    }

    /** 위탁 발급 이후에 새로 생성된 미위탁(PENDING) 마킹인가 — 지각 콜백의 오전이 대상. */
    private boolean isCreatedAfterSubmit(LsMarking m, java.time.LocalDateTime issuedAt) {
        if (!LsMarking.STATUS_PENDING.equals(m.getSttsCd()) || m.getRegDt() == null) {
            return false;
        }
        boolean after = m.getRegDt().isAfter(issuedAt);
        if (after) {
            log.warn("[Webhook][Vlm] skip marking transition — created after submit markingSn={} rawSn={}",
                    m.getMarkingSn(), m.getRawSn());
        }
        return after;
    }

    /** failed 콜백 — error 기록 + VLM_REQUESTED 마킹을 VLM_FAILED 로 전이(고착 해제, #5). */
    private void handleFailed(VlmResultRequest req, String requestId, Long rawSn,
                              java.time.LocalDateTime issuedAt) {
        // 규격 §2.7 상 error 는 객체가 아니라 <b>문자열</b>이다. 외부 유래 값이라 로그 전 sanitize(CWE-117).
        log.warn("[Webhook][Vlm] analysis failed request_id={} rawSn={} error={}",
                safe(requestId), rawSn, safe(req.error()));

        // completed 경로와 동일하게 ACTIVE_STATUSES 로 조회한다(콜백 선행 레이스 대칭 — PENDING 인
        // 마킹도 실패 콜백으로 종결시켜야 고착되지 않는다). 위탁 이후 새로 생긴 마킹 제외도 동일(L6) —
        // 실패 콜백이 새 마킹을 VLM_FAILED 로 만들면 아직 위탁도 안 된 작업이 실패로 보인다.
        List<LsMarking> markings = markingsInScope(rawSn, issuedAt);
        for (LsMarking m : markings) {
            m.markVlmFailed();
        }
        if (!markings.isEmpty()) {
            // VLM_FAILED 는 VLM_REQUESTED 고착(dead-lock)을 해제하는 종결 실패 상태다.
            // 이 상태를 읽어 자동 재요청/복구하는 잡은 아직 미구현 — 수동/후속 재처리 대상이다(DEV_FIX 2차 #3).
            log.warn("[Webhook][Vlm] {} marking(s) transitioned VLM_REQUESTED->VLM_FAILED rawSn={} " +
                    "(종결 실패 상태 — 자동 복구 잡 미구현, 수동/후속 재처리 대상)", markings.size(), rawSn);
        }
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
