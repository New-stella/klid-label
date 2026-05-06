package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.VlmVerifyRequest;
import kr.co.cudo.authoring.common.client.dto.VlmVerifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VLM 객체 검증 단계 (Phase 5 — VLM_VERIFY, V1.7 — 객체 검증 한정).
 * <p>
 * 시계열 메타 자동 추출(자연어 설명 등 광범위 VLM)은 외부 시스템 책임이므로 본 단계는
 * YOLO/SAM2 가 검출한 객체에 대한 검증만 수행한다.
 *  - 검증 결과 verified=true → 기존 confScore × verification confidence 로 가중 평균.
 *  - verified=false → confScore 를 0.0 으로 강제하지 않고, response confidence 로 갱신
 *    (라벨 자체는 검수 단계에서 사람이 판정).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmObjectVerifyStep {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        int updated = 0;
        for (LsDataSrc src : frames) {
            List<LsDataLbl> autoLabels = lblRepository.findBySrcSnAndAutoLblYn(src.getSrcSn(), LsDataLbl.AUTO_YES);
            if (autoLabels.isEmpty()) {
                continue;
            }
            // BBOX 만 검증 대상 (POLYGON/SEGMENT 는 SAM2 산출물).
            List<LsDataLbl> bboxes = autoLabels.stream()
                    .filter(l -> LsDataLbl.TYPE_BBOX.equals(l.getLblTypeCd()))
                    .toList();
            if (bboxes.isEmpty()) {
                continue;
            }

            List<VlmVerifyRequest.ObjectToVerify> objects = new ArrayList<>(bboxes.size());
            Map<String, LsDataLbl> byObjId = new HashMap<>();
            for (LsDataLbl lbl : bboxes) {
                String objId = "lbl-" + lbl.getLblSn();
                byObjId.put(objId, lbl);
                objects.add(new VlmVerifyRequest.ObjectToVerify(objId, lbl.getLabel(), List.of()));
            }

            String imageRef = src.getDeidFilePath() != null ? src.getDeidFilePath() : src.getFilePath();
            VlmVerifyResponse resp;
            try {
                resp = aiServerClient.verifyObjects(new VlmVerifyRequest(imageRef, objects))
                        .block(Duration.ofSeconds(70));
            } catch (RuntimeException e) {
                log.error("[Batch][Vlm] failed srcSn={} err={}", src.getSrcSn(), e.getMessage());
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "VLM 호출 실패", e);
            }
            if (resp == null || resp.results() == null) {
                continue;
            }
            for (VlmVerifyResponse.ObjectVerification v : resp.results()) {
                LsDataLbl lbl = byObjId.get(v.objId());
                if (lbl == null) continue;
                BigDecimal newScore = BigDecimal.valueOf(v.confidence()).setScale(4, RoundingMode.HALF_UP);
                lbl.updateConfScore(newScore);
                updated++;
            }
        }
        log.info("[Batch][Vlm] updated confScore rawSn={} count={}", rawSn, updated);
        return updated;
    }
}
