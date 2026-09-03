package kr.co.cudo.authoring.quality.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.quality.dto.QualityCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (키포인트) — QualityCheckService 가 SKELETON(삼중값) 라벨을 섞여 있어도 파싱 예외 없이
 * skip 하는지 검증. 키포인트는 IoU 충돌 검출 대상이 아니다.
 */
class QualityCheckServiceKeypointTest {

    private LsDataLblRepository labelRepository;
    private QualityCheckService service;

    @BeforeEach
    void setUp() {
        labelRepository = mock(LsDataLblRepository.class);
        service = new QualityCheckService(labelRepository, new ObjectMapper());
    }

    private LsDataLbl skeleton() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(i).append(',').append(i).append(",2]");
        }
        sb.append(']');
        return LsDataLbl.createManual(10L, LsDataLbl.TYPE_SKELETON, null, "person", sb.toString(), "1");
    }

    private LsDataLbl bbox(String autoYn) {
        LsDataLbl l = LsDataLbl.createManual(10L, LsDataLbl.TYPE_BBOX, null, "car",
                "[[0,0],[10,10]]", "1");
        // autoLblYn 은 transient — createManual 은 'N'. auto 라벨 재현이 필요 없으면 그대로 사용.
        return l;
    }

    @Test
    @DisplayName("QualityCheck_SKELETON_섞여도_예외없이_skip")
    void skeletonSkippedWithoutException() {
        when(labelRepository.findBySrcSn(500L)).thenReturn(List.of(skeleton(), bbox("N")));

        assertThatCode(() -> {
            QualityCheckResult result = service.check(500L);
            assertThat(result).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("QualityCheck_SKELETON_단독_프레임도_예외없이_처리")
    void skeletonOnlyFrame() {
        when(labelRepository.findBySrcSn(501L)).thenReturn(List.of(skeleton()));

        assertThatCode(() -> service.check(501L)).doesNotThrowAnyException();
    }
}
