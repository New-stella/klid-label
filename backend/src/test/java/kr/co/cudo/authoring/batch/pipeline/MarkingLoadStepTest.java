package kr.co.cudo.authoring.batch.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MarkingLoadStep 단위 테스트 — 마킹 로드 + marks 파싱 책임 검증.
 *
 * <p>기존 orchestrator 의 markings 조회 + parseMarks(ObjectMapper TypeReference) 책임을
 * 본 단계로 이동한 뒤에도 동작이 동일함을 보장한다.
 */
class MarkingLoadStepTest {

    private LsMarkingRepository markingRepository;
    private MarkingLoadStep step;

    @BeforeEach
    void setUp() {
        markingRepository = mock(LsMarkingRepository.class);
        step = new MarkingLoadStep(markingRepository, new ObjectMapper());
    }

    private LsMarking markingWith(String markCn) {
        return LsMarking.createAuto(7L, "fire", 5, "raw/path.mp4", markCn, 1L);
    }

    @Test
    @DisplayName("stage_는_MARKING")
    void stageIsMarking() {
        assertThat(step.stage()).isEqualTo(BatchStage.MARKING);
    }

    @Test
    @DisplayName("마킹_있으면_markings_로드_+_최신_마킹의_markCn_을_MarkItem_으로_파싱")
    void loadsMarkingsAndParsesMarks() {
        LsMarking latest = markingWith(
                "[{\"frameIndex\":0,\"timestamp\":\"00:00\"},{\"frameIndex\":150,\"timestamp\":\"00:05\"}]");
        LsMarking older = markingWith("[{\"frameIndex\":999,\"timestamp\":\"00:33\"}]");
        when(markingRepository.findByRawSnOrderByRegDtDesc(7L)).thenReturn(List.of(latest, older));

        BatchContext ctx = new BatchContext(7L, null);
        step.execute(ctx);

        assertThat(ctx.getMarkings()).containsExactly(latest, older);
        // 최신(첫 번째) 마킹만 파싱되어야 함
        assertThat(ctx.getMarks()).hasSize(2);
        assertThat(ctx.getMarks().get(0).frameIndex()).isEqualTo(0);
        assertThat(ctx.getMarks().get(1).frameIndex()).isEqualTo(150);
    }

    @Test
    @DisplayName("마킹_없으면_markings_marks_모두_빈_리스트")
    void emptyMarkingsLeavesMarksEmpty() {
        when(markingRepository.findByRawSnOrderByRegDtDesc(8L)).thenReturn(Collections.emptyList());

        BatchContext ctx = new BatchContext(8L, null);
        step.execute(ctx);

        assertThat(ctx.getMarkings()).isEmpty();
        assertThat(ctx.getMarks()).isEmpty();
    }

    @Test
    @DisplayName("markCn_이_잘못된_JSON_이면_INTERNAL_ERROR")
    void invalidMarkCnThrowsInternalError() {
        when(markingRepository.findByRawSnOrderByRegDtDesc(9L))
                .thenReturn(List.of(markingWith("not-a-json")));

        BatchContext ctx = new BatchContext(9L, null);
        assertThatThrownBy(() -> step.execute(ctx))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode().name()).isEqualTo("INTERNAL_ERROR"));
    }
}
