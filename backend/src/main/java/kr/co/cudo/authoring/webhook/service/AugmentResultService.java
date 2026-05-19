package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ExternalUrlValidator;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 외부 생성형 AI 증강 결과 인계 처리 서비스 — Phase 2.
 *
 * <p>{@code POST /v1/augments/result} 의 인증 통과 후 호출된다.
 * {@link LsDataAug} 의 PENDING 상태 행을 {@code ACCEPTED}/{@code REJECTED} 로 전이한다.
 *
 * <p>실제 검수(REVIEWER)는 별도 AugmentReviewService 의 화면 흐름을 따른다. 본 서비스는
 * <b>외부 결과 수신</b> 단계만 책임지고 검수 큐로 진입시키는 것이 목적이다.
 * 본 Phase 에서는 ENTRY 단계로 상태 전이를 ACCEPTED 까지 진행하되, augType 일관성 검증을 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentResultService {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final LsDataAugRepository augRepository;
    private final WebhookIdempotencyLedger ledger;

    /**
     * @return true = 신규 적재 / false = 멱등 스킵
     */
    @Transactional("controlTransactionManager")
    public boolean handle(AugmentResultRequest req) {
        // 1) allowlist
        if (!ledger.isIssued(req.idempotencyKey())) {
            log.warn("[Webhook][Augment] unknown idempotencyKey externalJobId={}",
                    safe(req.externalJobId()));
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "발급되지 않은 idempotencyKey 입니다.");
        }
        // 2) idempotent replay
        if (ledger.isProcessed(req.idempotencyKey())) {
            log.info("[Webhook][Augment] duplicate result skipped externalJobId={}",
                    safe(req.externalJobId()));
            return false;
        }
        // 3) SSRF — resultFilePath
        validateFilePath(req.resultFilePath());

        // 4) 증강 행 검증
        LsDataAug aug = augRepository.findById(req.originAugSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: originAugSn=" + req.originAugSn()));

        // augType 불일치 차단 — 외부 시스템이 다른 행에 잘못 인계하는 사고 방지
        if (!req.augType().equals(aug.getAugTypeCd())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "augType 불일치: row=" + aug.getAugTypeCd() + " request=" + req.augType());
        }

        // 5) 상태 전이 + 비동기 표준 컬럼 적재 (Phase 4 — Deident/Vlm 와 동일 race 흡수 패턴).
        //    UNIQUE(IDEMPOTENCY_KEY) 위반 시 재조회 후 멱등 흡수 (CWE-362 차단).
        String newStatus = "SUCCESS".equals(req.status())
                ? LsDataAug.STTS_ACCEPTED
                : LsDataAug.STTS_REJECTED;
        try {
            // 멱등 키가 이미 적재된 경우 동일 row 갱신, 아니면 인계 대상 row 갱신.
            LsDataAug target = augRepository.findByIdempotencyKey(req.idempotencyKey())
                    .orElse(aug);
            applyAugStateAndAsyncColumns(target, req, newStatus);
            augRepository.save(target);
        } catch (DataIntegrityViolationException e) {
            // 동시 인계 race 흡수 — UNIQUE(IDEMPOTENCY_KEY) 위반 후 재조회 후 갱신.
            LsDataAug existing = augRepository.findByIdempotencyKey(req.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("UNIQUE 위반 후 재조회 실패", e));
            applyAugStateAndAsyncColumns(existing, req, newStatus);
            augRepository.save(existing);
            log.warn("[Webhook][Augment] race 감지 후 멱등 흡수 idempotencyKey={}",
                    safe(req.idempotencyKey()));
        }

        // 6) 멱등 마킹
        ledger.markProcessed(req.idempotencyKey(), req.externalJobId());

        log.info("[Webhook][Augment] result applied augSn={} status={} externalJobId={}",
                req.originAugSn(), safe(req.status()), safe(req.externalJobId()));
        return true;
    }

    /** PENDING 상태일 때만 상태 전이 + 비동기 표준 컬럼 적재. */
    private static void applyAugStateAndAsyncColumns(LsDataAug target, AugmentResultRequest req,
                                                     String newStatus) {
        if (LsDataAug.STTS_PENDING.equals(target.getAugProcSttsCd())) {
            target.applyReviewStatus(newStatus);
        }
        // Phase 4 비동기 표준 컬럼 적재 — 이미 채워져 있어도 동일 값 재할당으로 무영향.
        if (target.getIdempotencyKey() == null) {
            target.assignIdempotencyKey(req.idempotencyKey());
        }
        if (target.getExternalJobId() == null) {
            target.assignExternalJobId(req.externalJobId());
        }
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
