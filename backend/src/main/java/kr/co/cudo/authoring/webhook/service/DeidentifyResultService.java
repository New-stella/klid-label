package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ExternalUrlValidator;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.DeidentifyResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 외부 Deidentify SW 결과 인계 처리 서비스 — Phase 2.
 *
 * <p>{@code POST /v1/deidentify/result} 의 인증 통과 후 호출된다.
 * idempotency allowlist + replay 멱등 + SSRF 검증 + 도메인 적재를 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeidentifyResultService {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final WebhookIdempotencyLedger ledger;

    /**
     * 결과 인계 처리.
     *
     * @return true = 신규 적재 / false = 멱등 스킵 (이미 처리된 idempotencyKey)
     */
    @Transactional("controlTransactionManager")
    public boolean handle(DeidentifyResultRequest req) {
        // 1) allowlist — 발급되지 않은 idempotencyKey 는 401 (UNAUTHORIZED).
        //    (S-1 보강: HMAC 통과 후에도 idempotencyKey 사전 등록만 수락)
        if (!ledger.isIssued(req.idempotencyKey())) {
            log.warn("[Webhook][Deidentify] unknown idempotencyKey externalJobId={}",
                    safe(req.externalJobId()));
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "발급되지 않은 idempotencyKey 입니다.");
        }

        // 2) idempotent replay — 이미 처리된 키는 스킵 (200 OK)
        if (ledger.isProcessed(req.idempotencyKey())) {
            log.info("[Webhook][Deidentify] duplicate result skipped externalJobId={}",
                    safe(req.externalJobId()));
            return false;
        }

        // 3) SSRF — resultFilePath 가 URL 이면 사설망/메타데이터 IP 차단
        validateFilePath(req.deidentifiedFilePath());

        // 4) 영상 존재 검증 + 비식별 토글 갱신
        LsDataRaw raw = videoRepository.findById(req.rawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + req.rawSn()));

        String code = "SUCCESS".equals(req.status()) ? "Y" : "F";
        raw.markDeidentified(code);

        // 5) 처리 로그 — LS_DEIDENT_PROC_LOG upsert (Phase 2 보강 H-3)
        //   externalJobId UNIQUE 제약을 활용해 동일 ID 재인계 시 단일 row 만 갱신.
        //   DEV_FIX 2차 H-3 race: 동시 webhook 인계 시 findByExternalJobId empty → 두 트랜잭션이
        //   동시에 신규 save 시도 → 한쪽이 DataIntegrityViolationException. 재조회 후 멱등 흡수.
        try {
            LsDeidentProcLog procLog = procLogRepository
                    .findByExternalJobId(req.externalJobId())
                    .orElseGet(() -> LsDeidentProcLog.request(
                            raw.getRawSn(), req.externalJobId(),
                            raw.getRawFilePathNm(), "webhook", req.externalJobId()));
            applyProcLogStatus(procLog, req);
            procLogRepository.save(procLog);
        } catch (DataIntegrityViolationException e) {
            // 동시 인계 race 흡수 — 재조회 후 갱신
            LsDeidentProcLog existing = procLogRepository.findByExternalJobId(req.externalJobId())
                    .orElseThrow(() -> new IllegalStateException("UNIQUE 위반 후 재조회 실패", e));
            applyProcLogStatus(existing, req);
            procLogRepository.save(existing);
            log.warn("[Webhook][Deidentify] race 감지 후 멱등 흡수 externalJobId={}",
                    safe(req.externalJobId()));
        }

        // 6) 멱등 마킹
        ledger.markProcessed(req.idempotencyKey(), req.externalJobId());

        log.info("[Webhook][Deidentify] result applied rawSn={} status={} externalJobId={}",
                req.rawSn(), safe(req.status()), safe(req.externalJobId()));
        return true;
    }

    private static void applyProcLogStatus(LsDeidentProcLog procLog, DeidentifyResultRequest req) {
        if ("SUCCESS".equals(req.status())) {
            procLog.succeed(req.deidentifiedFilePath());
        } else {
            procLog.fail(req.status(), "EXTERNAL_RESULT_" + req.status());
        }
    }

    private void validateFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        // URL 형식인 경우만 SSRF 검증 (절대 경로는 통과)
        if (filePath.contains("://")) {
            try {
                ExternalUrlValidator.validate(filePath, false, "deidentifiedFilePath");
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "deidentifiedFilePath SSRF 차단: " + e.getMessage());
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
