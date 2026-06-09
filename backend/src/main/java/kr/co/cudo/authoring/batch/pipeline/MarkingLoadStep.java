package kr.co.cudo.authoring.batch.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 마킹 로드 단계 (MARKING) — 선언적 파이프라인 추출 단계.
 *
 * <p>기존 {@code BatchOrchestrator.process()} 에 흩어져 있던 두 가지 책임을 한 단계로 모은다:
 * <ol>
 *   <li>{@link LsMarkingRepository#findByRawSnOrderByRegDtDesc} 로 마킹 로드 → {@code ctx.setMarkings}.</li>
 *   <li>마킹이 있으면 최신 마킹(첫 항목)의 {@code markCn} 을 {@link MarkItem} 목록으로 파싱
 *       → {@code ctx.setMarks} (기존 orchestrator 의 {@code parseMarks} 책임 이동).</li>
 * </ol>
 *
 * <p>마킹이 비어있으면 marks 는 빈 리스트로 둔다. "마킹 없음" 차단(INVALID_INPUT)은
 * 기존과 동일하게 FRAME_EXTRACT 단계({@link kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor})
 * 에서 marks 비었음 검증으로 수행된다 — 단계 순서·예외 타입(ErrorCode.INVALID_INPUT) 동일 보존.
 *
 * <p>marks 파싱을 본 단계가 수행하는 이유: VLM 단계는 marks 가 아니라 마킹 첫 항목(eventName/markCn)
 * 만 사용하므로, 파싱 결과(MarkItem 목록)는 FRAME_EXTRACT 만 소비한다. 따라서 로드 시점에 한 번
 * 파싱해 컨텍스트에 적재하는 것이 단계 응집도가 높다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingLoadStep implements BatchStep {

    private final LsMarkingRepository markingRepository;
    private final ObjectMapper objectMapper;

    @Override
    public BatchStage stage() {
        return BatchStage.MARKING;
    }

    @Override
    public void execute(BatchContext ctx) {
        Long rawSn = ctx.getRawSn();
        List<LsMarking> markings = markingRepository.findByRawSnOrderByRegDtDesc(rawSn);
        log.info("[BatchOrchestrator] marking check rawSn={} count={}", rawSn, markings.size());
        ctx.setMarkings(markings);
        if (!markings.isEmpty()) {
            ctx.setMarks(parseMarks(markings.get(0).getMarkCn()));
        }
    }

    private List<MarkItem> parseMarks(String marksJson) {
        try {
            return objectMapper.readValue(marksJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "마킹 데이터 파싱 실패", e);
        }
    }
}
