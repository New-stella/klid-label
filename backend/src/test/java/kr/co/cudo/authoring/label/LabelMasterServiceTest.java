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
        when(repository.existsActiveByNormalizedName("person", "Y")).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelMasterResponse res = service.create(req("person", "#E74C3C", "BBOX", 1), "1001");

        ArgumentCaptor<LsLabel> captor = ArgumentCaptor.forClass(LsLabel.class);
        verify(repository).save(captor.capture());
        LsLabel saved = captor.getValue();
        assertThat(saved.getLabelNm()).isEqualTo("person");
        assertThat(saved.getColrVl()).isEqualTo("#E74C3C");
        assertThat(saved.getLabelTypeCd()).isEqualTo("BBOX");
        assertThat(saved.getSortSeq()).isEqualTo(1);
        assertThat(saved.getUseYn()).isEqualTo("Y");
        assertThat(saved.getRegId()).isEqualTo("1001");

        assertThat(res.name()).isEqualTo("person");
        assertThat(res.useYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("SKELETON_타입_마스터_등록_성공")
    void create_SKELETON타입_성공() {
        when(repository.existsActiveByNormalizedName("human-pose", "Y")).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelMasterResponse res = service.create(req("human-pose", "#2ECC71", "SKELETON", 3), "1001");

        ArgumentCaptor<LsLabel> captor = ArgumentCaptor.forClass(LsLabel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLabelTypeCd()).isEqualTo("SKELETON");
        assertThat(res.type()).isEqualTo("SKELETON");
        assertThat(res.name()).isEqualTo("human-pose");
    }

    @Test
    @DisplayName("기존_BBOX_POLYGON_POINT_마스터_등록_회귀없음")
    void create_기존타입_회귀없음() {
        when(repository.existsActiveByNormalizedName(any(), any())).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(req("box", "#E74C3C", "BBOX", 1), "1001").type()).isEqualTo("BBOX");
        assertThat(service.create(req("poly", "#3498DB", "POLYGON", 2), "1001").type()).isEqualTo("POLYGON");
        assertThat(service.create(req("pt", "#F1C40F", "POINT", 3), "1001").type()).isEqualTo("POINT");
    }

    @Test
    @DisplayName("생성_중복_이름_시_CONFLICT_예외")
    void create_중복이름_CONFLICT() {
        when(repository.existsActiveByNormalizedName("person", "Y")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req("person", "#E74C3C", "BBOX", 1), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("라벨명_생성시_앞뒤공백은_trim되어_저장된다")
    void create_앞뒤공백_trim_저장() {
        // given — 정규화 이름("person")으로 중복검사가 이루어지고, 저장값도 trim 되어야 한다
        when(repository.existsActiveByNormalizedName("person", "Y")).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        LabelMasterResponse res = service.create(req("  person  ", "#E74C3C", "BBOX", 1), "1001");

        // then — 저장된 엔티티 이름이 trim 됨 + 중복검사 인자도 trim 됨
        ArgumentCaptor<LsLabel> captor = ArgumentCaptor.forClass(LsLabel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLabelNm()).isEqualTo("person");
        assertThat(res.name()).isEqualTo("person");
        verify(repository).existsActiveByNormalizedName("person", "Y");
    }

    @Test
    @DisplayName("대소문자만_다른_활성_라벨_생성시_409")
    void create_대소문자_근사중복_CONFLICT() {
        // given — 활성 'person' 존재를 정규화 검사가 감지 (요청은 'Person')
        when(repository.existsActiveByNormalizedName("Person", "Y")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.create(req("Person", "#E74C3C", "BBOX", 1), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("공백만_다른_활성_라벨_생성시_409")
    void create_공백_근사중복_CONFLICT() {
        // given — 요청 'person '(후행공백) → trim 'person', 활성 근사중복 감지
        when(repository.existsActiveByNormalizedName("person", "Y")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.create(req("person ", "#E74C3C", "BBOX", 1), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("soft_delete된_동일이름은_활성중복검사_통과시_저장경로_진입한다_서비스라우팅")
    void create_soft_delete된_이름_활성검사통과시_저장() {
        // given — 활성 중복만 검사하므로 soft-delete 된 동일 이름은 존재로 보지 않는다(false)
        //   주의: 이 단위 테스트는 repository mock 이라 서비스 라우팅(활성검사 false → save 진입)만 검증한다.
        //   실제 DB all-rows exact 제약 제거 후 동일 exact 이름 재사용 성공은
        //   LabelNameCiUniqueMigrationIT#soft_delete후_동일_exact_이름_재사용_허용() 가 실 DB 로 실증한다.
        when(repository.existsActiveByNormalizedName("person", "Y")).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        LabelMasterResponse res = service.create(req("person", "#E74C3C", "BBOX", 1), "1001");

        // then — 저장 경로 진입 성공
        assertThat(res.name()).isEqualTo("person");
        verify(repository).save(any(LsLabel.class));
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
        when(repository.findByUseYnOrderBySortSeqAsc("Y")).thenReturn(List.of(a, b));

        List<LabelMasterResponse> result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("person");
        assertThat(result.get(1).name()).isEqualTo("car");
        verify(repository).findByUseYnOrderBySortSeqAsc("Y");
    }

    @Test
    @DisplayName("수정_정상_시_이름_색상_타입_변경_및_mdfcnId_기록")
    void update_정상() {
        LsLabel label = LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");
        when(repository.findById(10L)).thenReturn(Optional.of(label));
        when(repository.existsActiveByNormalizedNameExcludingId("person-v2", "Y", 10L)).thenReturn(false);

        LabelMasterResponse res = service.update(10L, req("person-v2", "#AABBCC", "POLYGON", 5), "1002");

        assertThat(res.name()).isEqualTo("person-v2");
        assertThat(label.getLabelNm()).isEqualTo("person-v2");
        assertThat(label.getColrVl()).isEqualTo("#AABBCC");
        assertThat(label.getLabelTypeCd()).isEqualTo("POLYGON");
        assertThat(label.getSortSeq()).isEqualTo(5);
        assertThat(label.getMdfcnId()).isEqualTo("1002");
    }

    @Test
    @DisplayName("수정_시_다른_라벨과_이름_중복이면_CONFLICT")
    void update_이름중복_CONFLICT() {
        LsLabel label = LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");
        when(repository.findById(10L)).thenReturn(Optional.of(label));
        when(repository.existsActiveByNormalizedNameExcludingId("car", "Y", 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(10L, req("car", "#3498DB", "BBOX", 2), "1002"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));
    }

    @Test
    @DisplayName("수정시_자기자신은_중복검사에서_제외된다")
    void update_자기자신_중복검사_제외() {
        // given — 동일 이름으로 수정(자기 자신). 자기 제외 검사가 false 여야 통과.
        LsLabel label = LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");
        when(repository.findById(10L)).thenReturn(Optional.of(label));
        when(repository.existsActiveByNormalizedNameExcludingId("person", "Y", 10L)).thenReturn(false);

        // when — 색상만 바꾸고 이름은 그대로
        LabelMasterResponse res = service.update(10L, req("person", "#AABBCC", "BBOX", 1), "1002");

        // then — 자기 자신 제외 검사가 호출되고 CONFLICT 없이 성공
        assertThat(res.name()).isEqualTo("person");
        assertThat(label.getColrVl()).isEqualTo("#AABBCC");
        verify(repository).existsActiveByNormalizedNameExcludingId("person", "Y", 10L);
    }

    // ─── Phase 6 — AutoLabel preset 매핑 (LS_LABEL.NAME → LABEL_ID 조회) ───

    /** Phase 6 helper — labelId 가 부여된 LsLabel 생성. */
    private static LsLabel labelWithId(Long id, String name) {
        LsLabel label = LsLabel.create(name, "#E74C3C", "BBOX", 1, "seed");
        try {
            java.lang.reflect.Field f = LsLabel.class.getDeclaredField("labelId");
            f.setAccessible(true);
            f.set(label, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return label;
    }

    @Test
    @DisplayName("findLabelIdByName_정확한_이름_매칭_시_labelId_반환")
    void findLabelIdByName_정확한_이름_매칭() {
        when(repository.findByLabelNmIgnoreCaseAndUseYn("person", "Y"))
                .thenReturn(Optional.of(labelWithId(1L, "person")));

        Optional<Long> result = service.findLabelIdByName("person");

        assertThat(result).contains(1L);
        verify(repository).findByLabelNmIgnoreCaseAndUseYn("person", "Y");
    }

    @Test
    @DisplayName("findLabelIdByName_대소문자_무시_매칭")
    void findLabelIdByName_대소문자_무시() {
        // 구현은 trim 후 입력값 그대로 repository 에 전달 — IgnoreCase 검색은 repository 가 처리
        when(repository.findByLabelNmIgnoreCaseAndUseYn("PERSON", "Y"))
                .thenReturn(Optional.of(labelWithId(1L, "person")));

        Optional<Long> result = service.findLabelIdByName("PERSON");

        assertThat(result).contains(1L);
    }

    @Test
    @DisplayName("findLabelIdByName_앞뒤_공백_trim_후_매칭")
    void findLabelIdByName_공백_trim() {
        when(repository.findByLabelNmIgnoreCaseAndUseYn("person", "Y"))
                .thenReturn(Optional.of(labelWithId(1L, "person")));

        Optional<Long> result = service.findLabelIdByName("  person  ");

        assertThat(result).contains(1L);
        verify(repository).findByLabelNmIgnoreCaseAndUseYn("person", "Y");
    }

    @Test
    @DisplayName("findLabelIdByName_미매칭_시_Optional_empty")
    void findLabelIdByName_미매칭() {
        when(repository.findByLabelNmIgnoreCaseAndUseYn("unknown", "Y"))
                .thenReturn(Optional.empty());

        Optional<Long> result = service.findLabelIdByName("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findLabelIdByName_null_입력_시_Optional_empty")
    void findLabelIdByName_null_입력() {
        Optional<Long> result = service.findLabelIdByName(null);

        assertThat(result).isEmpty();
        // null 입력은 repository 호출 없이 즉시 empty 반환
        verify(repository, never()).findByLabelNmIgnoreCaseAndUseYn(any(), any());
    }

    @Test
    @DisplayName("findLabelIdByName_빈문자열_입력_시_Optional_empty")
    void findLabelIdByName_빈문자열() {
        Optional<Long> result = service.findLabelIdByName("   ");

        assertThat(result).isEmpty();
        verify(repository, never()).findByLabelNmIgnoreCaseAndUseYn(any(), any());
    }

    @Test
    @DisplayName("findLabelIdByName_use_yn_N_라벨은_제외")
    void findLabelIdByName_useYn_Y_만_조회() {
        // useYn='Y' 인 라벨만 검색되도록 repository 인자에 "Y" 가 들어가는지 검증
        when(repository.findByLabelNmIgnoreCaseAndUseYn("person", "Y"))
                .thenReturn(Optional.empty());

        Optional<Long> result = service.findLabelIdByName("person");

        assertThat(result).isEmpty();
        // 두번째 인자가 "Y" 인지 검증 (use_yn='N' 라벨이 매칭되지 않도록)
        verify(repository).findByLabelNmIgnoreCaseAndUseYn("person", "Y");
    }
}
