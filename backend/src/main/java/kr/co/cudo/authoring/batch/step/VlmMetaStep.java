package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.VlmMetaRequest;
import kr.co.cudo.authoring.common.client.dto.VlmMetaResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * VLM 영상 단위 메타 추출 단계 (V2 Phase 1 신규 + Phase 5 검토 분리).
 * <p>
 * V2 파이프라인의 첫 단계로, 영상 1건에 대한 K/V 메타를 LS_DATA_META 에 저장한다.
 *  - META_KEY 는 {@code "VLM_META." + key} 형식으로 통일 (메타 출처 식별).
 *  - 동일 (rawSn, key) 가 이미 존재하면 UPDATE (UK 충돌 방지).
 *  - 외부 호출 실패 시 예외 전파 → BatchOrchestrator 가 FAILED 처리 및 재시도 큐 등록.
 *  - Phase 5: 신규 저장된 메타에 대해 LS_DATA_META_REVIEW row 동시 INSERT
 *    (META_TYPE_CD='VLM', SRC_SYS_CD='AI_SERVER', RVW_STTS_CD='AUTO_GENERATED' — REVIEWER 후속 검토 대상).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmMetaStep {

    private static final String META_KEY_PREFIX = "VLM_META.";

    private final AiServerClient aiServerClient;
    private final LsDataMetaRepository metaRepository;
    private final LsDataMetaReviewRepository metaReviewRepository;
    private final VideoRepository videoRepository;

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다 rawSn=" + rawSn));

        log.info("[Batch][VlmMeta] started rawSn={}", rawSn);
        VlmMetaResponse resp;
        try {
            resp = aiServerClient.extractVideoMeta(new VlmMetaRequest(rawSn, raw.getFilePath()))
                    .block(Duration.ofSeconds(70));
        } catch (RuntimeException e) {
            log.error("[Batch][VlmMeta] failed rawSn={} err={}", rawSn, e.getMessage());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "VLM 메타 호출 실패", e);
        }
        if (resp == null || resp.metaPairs() == null) {
            log.warn("[Batch][VlmMeta] empty response rawSn={}", rawSn);
            return 0;
        }
        int saved = 0;
        for (Map.Entry<String, String> entry : resp.metaPairs().entrySet()) {
            String metaKey = META_KEY_PREFIX + entry.getKey();
            String metaVal = entry.getValue();
            Optional<LsDataMeta> existing = metaRepository.findByRawSnAndMetaKey(rawSn, metaKey);
            LsDataMeta persisted;
            if (existing.isPresent()) {
                existing.get().updateValue(metaVal);
                persisted = existing.get();
                // 영속 객체 — dirty checking
            } else {
                persisted = metaRepository.save(LsDataMeta.create(rawSn, metaKey, metaVal));
            }
            // Phase 5: 신규 메타에 한해 검토 row 생성 (이미 검토 row 가 있으면 중복 생성 회피)
            if (persisted.getMetaSn() != null
                    && !metaReviewRepository.existsByDataMetaSn(persisted.getMetaSn())) {
                metaReviewRepository.save(LsDataMetaReview.createAuto(
                        persisted.getMetaSn(),
                        null,            // PJT_SN — VLM 영상 단위는 아직 미상 (Phase 6+ 보강)
                        rawSn,
                        null,            // DATA_SRC_SN — 영상 단위
                        LsDataMetaReview.META_TYPE_VLM,
                        LsDataMetaReview.SRC_AI_SERVER,
                        LsDataMetaReview.STTS_AUTO_GENERATED));
            }
            saved++;
        }
        log.info("[Batch][VlmMeta] completed rawSn={} count={}", rawSn, saved);
        return saved;
    }
}
