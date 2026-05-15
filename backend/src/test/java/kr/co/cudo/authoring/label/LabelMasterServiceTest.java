package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.label.dto.LabelMasterRequest;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelMasterService;
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
 * 라벨 마스터 응용 서비스 단위 테스트 (Mockito).
 *
 * <p>CVAT-Like 라벨 풀 포팅 Phase 1. V34 이후 PJT_ID 제거됨.
 */
class LabelMasterServiceTest {

    private LsLabelRepository repository;
    private LabelMasterService service;

    @BeforeEach
    void setUp() {
        repository = mock(LsLabelRepository.class);
        service = new LabelMasterService(repository);
    }

    private static LabelMasterRequest req(String name, String color, String type, Integer sortNo) {
        return new LabelMasterRequest(name, color, type, sortNo);
    }

    @Test
    @DisplayName("생성_정상_시_LsLabel_save_호출_및_응답_반환")
    void create_정상() {
        when(repository.existsByName("person")).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelMasterResponse res = service.create(req("person", "#E74C3C", "BBOX", 1), "1001");

        ArgumentCaptor<LsLabel> captor = ArgumentCaptor.forClass(LsLabel.class);
        verify(repository).save(captor.capture());
        LsLabel saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("person");
        assertThat(saved.getColor()).isEqualTo("#E74C3C");
        assertThat(saved.getType()).isEqualTo("BBOX");
        assertThat(saved.getSortNo()).isEqualTo(1);
        assertThat(saved.getUseYn()).isEqualTo("Y");
        assertThat(saved.getRegId()).isEqualTo("1001");

        assertThat(res.name()).isEqualTo("person");
        assertThat(res.useYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("생성_중복_이름_시_CONFLICT_예외")
    void create_중복이름_CONFLICT() {
        when(repository.existsByName("person")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req("person", "#E74C3C", "BBOX", 1), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("수정_존재하지_않는_id_시_NOT_FOUND")
    void update_없는라벨_NOT_FOUND() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, req("person", "#E74C3C", "BBOX", 1), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("NOT_FOUND"));
    }

    @Test
    @DisplayName("삭제_시_USE_YN_N_으로_soft_delete")
    void delete_soft() {
        LsLabel label = LsLabel.create("person", "#E74C3C", "BBOX", 1, "1001");
        when(repository.findById(10L)).thenReturn(Optional.of(label));

        service.delete(10L, "1001");

        assertThat(label.getUseYn()).isEqualTo("N");
        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("목록_조회_시_USE_YN_Y_만_sort_no_ASC_반환")
    void list_활성만_정렬() {
        LsLabel a = LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");
        LsLabel b = LsLabel.create("car", "#3498DB", "BBOX", 2, "seed");
        when(repository.findByUseYnOrderBySortNoAsc("Y")).thenReturn(List.of(a, b));

        List<LabelMasterResponse> result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("person");
        assertThat(result.get(1).name()).isEqualTo("car");
        verify(repository).findByUseYnOrderBySortNoAsc("Y");
    }

    @Test
    @DisplayName("수정_정상_시_이름_색상_타입_변경_및_mdfcnId_기록")
    void update_정상() {
        LsLabel label = LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");
        when(repository.findById(10L)).thenReturn(Optional.of(label));
        when(repository.existsByNameAndLabelIdNot("person-v2", 10L)).thenReturn(false);

        LabelMasterResponse res = service.update(10L, req("person-v2", "#AABBCC", "POLYGON", 5), "1002");

        assertThat(res.name()).isEqualTo("person-v2");
        assertThat(label.getName()).isEqualTo("person-v2");
        assertThat(label.getColor()).isEqualTo("#AABBCC");
        assertThat(label.getType()).isEqualTo("POLYGON");
        assertThat(label.getSortNo()).isEqualTo(5);
        assertThat(label.getMdfcnId()).isEqualTo("1002");
    }

    @Test
    @DisplayName("수정_시_다른_라벨과_이름_중복이면_CONFLICT")
    void update_이름중복_CONFLICT() {
        LsLabel label = LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");
        when(repository.findById(10L)).thenReturn(Optional.of(label));
        when(repository.existsByNameAndLabelIdNot("car", 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(10L, req("car", "#3498DB", "BBOX", 2), "1002"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));
    }
}
