package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ExternalUrlValidator;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.VlmResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 외부 VLM 시계열 메타 결과 인계 처리 서비스 — Phase 2.
 *
 * <p>{@code POST /v1/vlm/result} 의 인증 통과 후 호출된다.
 * 적재: {@link LsDataMeta} (K/V) + {@link LsDataMetaReview} 검수 큐(PENDING).
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

    /**
     * @return true = 신규 적재 / false = 멱등 스킵
     */
    @Transactional("controlTransactionManager")
    public boolean handle(VlmResultRequest req) {
        // 1) allowlist
        if (!ledger.isIssued(req.idempotencyKey())) {
            log.warn("[Webhook][Vlm] unknown idempotencyKey externalJobId={}",
                    safe(req.externalJobId()));
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "발급되지 않은 idempotencyKey 입니다.");
        }
        // 2) idempotent replay
        if (ledger.isProcessed(req.idempotencyKey())) {
            log.info("[Webhook][Vlm] duplicate result skipped externalJobId={}",
                    safe(req.externalJobId()));
            return false;
        }
        // 3) SSRF — resultFilePath
        validateFilePath(req.resultFilePath());

        // 4) 영상 존재 검증
        LsDataRaw raw = videoRepository.findById(req.rawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + req.rawSn()));

        // FAILED 상태는 적재 없이 멱등 마킹만 (Phase 4 에서 dead-letter 큐 도입 시 분기)
        if (!"SUCCESS".equals(req.status())) {
            ledger.markProcessed(req.idempotencyKey(), req.externalJobId());
            log.info("[Webhook][Vlm] non-success status={} skipped append externalJobId={}",
                    safe(req.status()), safe(req.externalJobId()));
            return true;
        }

        // 5) LS_DATA_META 적재 (upsert by metaKey)
        //    DEV_FIX 2차 H-3 race: (rawSn, metaKey) UNIQUE 제약. 동시 webhook 인계 시
        //    findByRawSnAndMetaKey empty → 두 트랜잭션이 동시에 신규 save 시도 →
        //    한쪽 DataIntegrityViolationException. 재조회 후 멱등 흡수.
        int appended = 0;
        for (VlmResultRequest.MetaItem item : req.vlmMetaItems()) {
            LsDataMeta saved;
            try {
                LsDataMeta meta = metaRepository.findByRawSnAndMetaKey(raw.getRawSn(), item.metaKey())
                        .map(existing -> {
                            existing.updateValue(item.metaVal());
                            return existing;
                        })
                        .orElseGet(() -> LsDataMeta.create(raw.getRawSn(), item.metaKey(), item.metaVal()));
                saved = metaRepository.save(meta);
            } catch (DataIntegrityViolationException e) {
                // 동시 인계 race 흡수 — 재조회 후 값 갱신
                LsDataMeta existing = metaRepository
                        .findByRawSnAndMetaKey(raw.getRawSn(), item.metaKey())
                        .orElseThrow(() -> new IllegalStateException("UNIQUE 위반 후 재조회 실패", e));
                existing.updateValue(item.metaVal());
                saved = metaRepository.save(existing);
                log.warn("[Webhook][Vlm] race 감지 후 멱등 흡수 rawSn={} metaKey={}",
                        raw.getRawSn(), safe(item.metaKey()));
            }

            // 검수 큐 진입 — PENDING (외부 시스템 결과는 REVIEWER 승인 필요)
            LsDataMetaReview review = LsDataMetaReview.createAuto(
                    saved.getMetaSn(),
                    null,
                    raw.getRawSn(),
                    null,
                    LsDataMetaReview.META_TYPE_VLM,
                    LsDataMetaReview.SRC_AI_SERVER,
                    LsDataMetaReview.STTS_PENDING);
            reviewRepository.save(review);
            appended++;
        }

        // 6) 멱등 마킹
        ledger.markProcessed(req.idempotencyKey(), req.externalJobId());

        log.info("[Webhook][Vlm] result applied rawSn={} appended={} externalJobId={}",
                raw.getRawSn(), appended, safe(req.externalJobId()));
        return true;
    }

    private void validateFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        if (filePath.contains("://")) {
            try {
                ExternalUrlValidator.validate(filePath, false, "resultFilePath");
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "resultFilePath SSRF 차단: " + e.getMessage());
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
