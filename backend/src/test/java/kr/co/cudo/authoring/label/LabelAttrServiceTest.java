package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.label.dto.LabelAttrRequest;
import kr.co.cudo.authoring.label.dto.LabelAttrResponse;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelAttrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 라벨 속성 정의 응용 서비스 단위 테스트 (Mockito).
 *
 * <p>CVAT-Like 라벨 풀 포팅 Phase 3.
 */
class LabelAttrServiceTest {

    private static final Long LABEL_ID = 10L;

    private LsLabelAttrRepository attrRepository;
    private LsLabelRepository labelRepository;
    private LabelAttrService service;

    @BeforeEach
    void setUp() {
        attrRepository = mock(LsLabelAttrRepository.class);
        labelRepository = mock(LsLabelRepository.class);
        service = new LabelAttrService(attrRepository, labelRepository);

        // 기본: 라벨 존재.
        when(labelRepository.existsById(LABEL_ID)).thenReturn(true);
    }

    private static LabelAttrRequest req(String name, String inputType, String valuesJson,
                                        String defaultVal, String mutable, Integer sortNo) {
        return new LabelAttrRequest(name, inputType, valuesJson, defaultVal, mutable, sortNo);
    }

    @Test
    @DisplayName("생성_정상_시_save_호출")
    void create_정상() {
        when(attrRepository.existsByLabelIdAndAttrNm(LABEL_ID, "occluded")).thenReturn(false);
        when(attrRepository.save(any(LsLabelAttr.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelAttrResponse res = service.create(LABEL_ID,
                req("occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 1), "1001");

        ArgumentCaptor<LsLabelAttr> captor = ArgumentCaptor.forClass(LsLabelAttr.class);
        verify(attrRepository).save(captor.capture());
        LsLabelAttr saved = captor.getValue();
        assertThat(saved.getLabelId()).isEqualTo(LABEL_ID);
        assertThat(saved.getAttrNm()).isEqualTo("occluded");
        assertThat(saved.getInputTypeCd()).isEqualTo("SELECT");
        assertThat(saved.getValuesCn()).isEqualTo("[\"yes\",\"no\"]");
        assertThat(saved.getDfltVl()).isEqualTo("no");
        assertThat(saved.getMutableYn()).isEqualTo("Y");
        assertThat(saved.getSortSeq()).isEqualTo(1);
        assertThat(saved.getUseYn()).isEqualTo("Y");
        assertThat(saved.getRegId()).isEqualTo("1001");
        assertThat(res.name()).isEqualTo("occluded");
    }

    @Test
    @DisplayName("생성_SELECT_타입_valuesJson_누락_시_INVALID_INPUT")
    void create_SELECT_valuesJson누락_INVALID_INPUT() {
        assertThatThrownBy(() -> service.create(LABEL_ID,
                req("occluded", "SELECT", null, null, "Y", 0), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("INVALID_INPUT"));

        verify(attrRepository, never()).save(any());
    }

    @Test
    @DisplayName("생성_CHECKBOX_타입_valuesJson_빈문자열_시_INVALID_INPUT")
    void create_CHECKBOX_valuesJson빈문자열_INVALID_INPUT() {
        assertThatThrownBy(() -> service.create(LABEL_ID,
                req("flags", "CHECKBOX", "  ", null, "Y", 0), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("INVALID_INPUT"));
    }

    @Test
    @DisplayName("생성_TEXT_타입_valuesJson_null_허용")
    void create_TEXT_valuesJson_null_허용() {
        when(attrRepository.existsByLabelIdAndAttrNm(LABEL_ID, "note")).thenReturn(false);
        when(attrRepository.save(any(LsLabelAttr.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelAttrResponse res = service.create(LABEL_ID,
                req("note", "TEXT", null, null, "Y", 0), "1001");

        assertThat(res.inputType()).isEqualTo("TEXT");
        assertThat(res.valuesJson()).isNull();
    }

    @Test
    @DisplayName("생성_NUMBER_타입_valuesJson_null_허용")
    void create_NUMBER_valuesJson_null_허용() {
        when(attrRepository.existsByLabelIdAndAttrNm(LABEL_ID, "count")).thenReturn(false);
        when(attrRepository.save(any(LsLabelAttr.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelAttrResponse res = service.create(LABEL_ID,
                req("count", "NUMBER", null, "0", "Y", 0), "1001");

        assertThat(res.inputType()).isEqualTo("NUMBER");
    }

    @Test
    @DisplayName("생성_중복_name_시_CONFLICT")
    void create_중복name_CONFLICT() {
        when(attrRepository.existsByLabelIdAndAttrNm(LABEL_ID, "occluded")).thenReturn(true);

        assertThatThrownBy(() -> service.create(LABEL_ID,
                req("occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));

        verify(attrRepository, never()).save(any());
    }

    @Test
    @DisplayName("생성_라벨_미존재_시_NOT_FOUND")
    void create_라벨미존재_NOT_FOUND() {
        when(labelRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(99L,
                req("occluded", "TEXT", null, null, "Y", 0), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("NOT_FOUND"));
    }

    @Test
    @DisplayName("목록_USE_YN_Y_만_sort_no_ASC_반환")
    void list_활성만_정렬() {
        LsLabelAttr a = LsLabelAttr.create(LABEL_ID, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 1, "seed");
        LsLabelAttr b = LsLabelAttr.create(LABEL_ID, "direction", "RADIO", "[\"N\",\"S\",\"E\",\"W\"]", null, "Y", 2, "seed");
        when(attrRepository.findByLabelIdAndUseYnOrderBySortSeqAsc(LABEL_ID, "Y")).thenReturn(List.of(a, b));

        List<LabelAttrResponse> result = service.list(LABEL_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("occluded");
        assertThat(result.get(1).name()).isEqualTo("direction");
        verify(attrRepository).findByLabelIdAndUseYnOrderBySortSeqAsc(LABEL_ID, "Y");
    }

    @Test
    @DisplayName("삭제_시_USE_YN_N_으로_soft_delete")
    void delete_soft() {
        LsLabelAttr attr = LsLabelAttr.create(LABEL_ID, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 1, "seed");
        when(attrRepository.findById(20L)).thenReturn(Optional.of(attr));

        service.delete(LABEL_ID, 20L, "1001");

        assertThat(attr.getUseYn()).isEqualTo("N");
        verify(attrRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("수정_정상_시_이름_타입_등_변경")
    void update_정상() {
        LsLabelAttr attr = LsLabelAttr.create(LABEL_ID, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 1, "seed");
        when(attrRepository.findById(20L)).thenReturn(Optional.of(attr));
        when(attrRepository.existsByLabelIdAndAttrNmAndAttrIdNot(LABEL_ID, "occluded-v2", 20L)).thenReturn(false);

        service.update(LABEL_ID, 20L,
                req("occluded-v2", "RADIO", "[\"yes\",\"no\",\"unknown\"]", "unknown", "N", 3), "1002");

        assertThat(attr.getAttrNm()).isEqualTo("occluded-v2");
        assertThat(attr.getInputTypeCd()).isEqualTo("RADIO");
        assertThat(attr.getMutableYn()).isEqualTo("N");
        assertThat(attr.getSortSeq()).isEqualTo(3);
        assertThat(attr.getMdfcnId()).isEqualTo("1002");
    }

    @Test
    @DisplayName("수정_시_다른_라벨의_속성이면_NOT_FOUND")
    void update_다른라벨속성_NOT_FOUND() {
        LsLabelAttr attr = LsLabelAttr.create(999L, "x", "TEXT", null, null, "Y", 0, "seed");
        when(attrRepository.findById(20L)).thenReturn(Optional.of(attr));

        assertThatThrownBy(() -> service.update(LABEL_ID, 20L,
                req("x", "TEXT", null, null, "Y", 0), "1002"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("NOT_FOUND"));
    }
}
