package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.VlmResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 외부 VLM 시계열 메타 결과 인계 처리 서비스 — Phase 1 (콜백 수신부).
 *
 * <p>{@code POST /v1/vlm/callback} 의 진입 후 호출된다.
 * 적재: {@link LsDataMeta} (K/V) + {@link LsDataMetaReview} 검수 큐(PENDING).
 *
 * <h3>트랜잭션 원자성 (DEV_FIX C-1)</h3>
 * <p>콜백 처리 전체(원장 락 조회 → META upsert → 검수큐 → 마킹 전이 → 원장 PROCESSED 마킹)를
 * <b>단일 트랜잭션</b>으로 수행한다. {@link WebhookIdempotencyLedger#markProcessedInTx}(REQUIRED)로
 * 원장 마킹을 outer 트랜잭션에 참여시켜, 처리 중 어떤 단계가 실패하면 원장 PROCESSED 전이도 함께
 * 롤백된다 → 벤더 재전송으로 복구 가능(데이터 유실 차단). 중간 예외는 catch 하지 않고 전파한다.
 *
 * <h3>동시성 (DEV_FIX #1/#3, CWE-362)</h3>
 * <p>진입 시 {@link WebhookIdempotencyLedger#lookupForProcessing}(비관적 락)으로 동일 request_id
 * 동시 콜백을 직렬화한다. 두 번째 콜백은 첫 콜백 커밋(PROCESSED) 후 락을 얻어 멱등 스킵되므로
 * 검수큐 중복 적재/META race 가 발생하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VlmResultService {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final LsDataMetaRepository metaRepository;
    private final LsDataMetaReviewRepository reviewRepository;
    private final VideoRepository videoRepository;
    private final WebhookIdempotencyLedger ledger;
    private final LsMarkingRepository markingRepository;

    /**
     * describe 콜백 처리 — 벤더 확정 계약(v2.0.1) 정합.
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
            handleFailed(req, requestId, rawSn);
            ledger.markProcessedInTx(requestId, requestId);
            return true;
        }

        // 6) completed → 영상 존재 검증 (사용자 대면 메시지는 일반화, rawSn 은 서버 로그만 — F-6)
        if (!videoRepository.existsById(rawSn)) {
            log.warn("[Webhook][Vlm] target video not found rawSn={} request_id={}",
                    rawSn, safe(requestId));
            throw new CustomException(ErrorCode.NOT_FOUND, "대상 영상을 찾을 수 없습니다.");
        }

        // 7) 한 콜백 내 중복 구간(metaKey) 거부 — 조용한 덮어쓰기 방지(#4).
        Map<String, VlmResultRequest.Segment> byKey = dedupSegments(req.results(), requestId);

        // 8) LS_DATA_META 배치 upsert — (rawSn, metaKey) UNIQUE. IN 조회 1회 + saveAll(DB-MEDIUM).
        //    신규 metaKey 만 검수큐(LS_DATA_META_REVIEW) 진입, 기존은 값 갱신만(중복 검수행 방지, #3).
        Map<String, LsDataMeta> existing = metaRepository
                .findByRawSnAndMetaKeyIn(rawSn, byKey.keySet()).stream()
                .collect(Collectors.toMap(LsDataMeta::getMetaKey, m -> m, (a, b) -> a));

        List<LsDataMeta> toSave = new ArrayList<>(byKey.size());
        List<LsDataMeta> newMetas = new ArrayList<>();
        for (VlmResultRequest.Segment seg : byKey.values()) {
            LsDataMeta m = existing.get(seg.metaKey());
            if (m != null) {
                m.updateValue(seg.description());
                toSave.add(m);
            } else {
                LsDataMeta created = LsDataMeta.create(rawSn, seg.metaKey(), seg.description());
                toSave.add(created);
                newMetas.add(created);
            }
        }
        metaRepository.saveAll(toSave); // 신규 metaSn 은 동일 인스턴스(newMetas)에 반영됨

        // 9) 검수 큐 진입 — 신규 meta 만 PENDING (외부 시스템 결과는 REVIEWER 승인 필요)
        List<LsDataMetaReview> reviews = newMetas.stream()
                .map(m -> LsDataMetaReview.createAuto(
                        m.getMetaSn(), rawSn, null,
                        LsDataMetaReview.META_TYPE_VLM,
                        LsDataMetaReview.SRC_AI_SERVER,
                        LsDataMetaReview.STTS_PENDING))
                .toList();
        reviewRepository.saveAll(reviews);

        // 10) 마킹 상태 VLM_COMPLETED 전이
        List<LsMarking> markings = markingRepository.findByRawSnAndSttsCd(
                rawSn, LsMarking.STATUS_VLM_REQUESTED);
        for (LsMarking m : markings) {
            m.markVlmCompleted();
        }

        // 11) 멱등 마킹 — 마지막에 outer 트랜잭션 안에서 수행(원자성, C-1)
        ledger.markProcessedInTx(requestId, requestId);

        log.info("[Webhook][Vlm] result applied request_id={} rawSn={} new={} updated={} markingsTransitioned={}",
                safe(requestId), rawSn, newMetas.size(), toSave.size() - newMetas.size(), markings.size());
        return true;
    }

    /** failed 콜백 — error 기록 + VLM_REQUESTED 마킹을 VLM_FAILED 로 전이(고착 해제, #5). */
    private void handleFailed(VlmResultRequest req, String requestId, Long rawSn) {
        VlmResultRequest.VlmError err = req.error();
        log.warn("[Webhook][Vlm] describe failed request_id={} rawSn={} code={} message={}",
                safe(requestId), rawSn,
                safe(err == null ? null : err.code()),
                safe(err == null ? null : err.message()));

        List<LsMarking> markings = markingRepository.findByRawSnAndSttsCd(
                rawSn, LsMarking.STATUS_VLM_REQUESTED);
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

    /**
     * 한 콜백 내 results 를 metaKey 기준으로 정리 — 중복 metaKey 는 거부(400).
     * 삽입 순서 보존(LinkedHashMap)으로 적재/로그 순서를 안정화한다.
     */
    private Map<String, VlmResultRequest.Segment> dedupSegments(
            List<VlmResultRequest.Segment> results, String requestId) {
        Map<String, VlmResultRequest.Segment> byKey = new LinkedHashMap<>();
        for (VlmResultRequest.Segment seg : results) {
            if (byKey.putIfAbsent(seg.metaKey(), seg) != null) {
                log.warn("[Webhook][Vlm] duplicate segment metaKey={} request_id={}",
                        safe(seg.metaKey()), safe(requestId));
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "한 콜백 내 중복 구간(start_sec-end_sec)은 허용되지 않습니다.");
            }
        }
        return byKey;
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
