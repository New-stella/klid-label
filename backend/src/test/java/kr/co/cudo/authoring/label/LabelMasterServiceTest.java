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
        return new LabelMasterRequest(name, color, type, sortNo, null);
    }

    private static LabelMasterRequest req(String name, String color, String type, Integer sortNo,
                                          String dtctTypeCd) {
        return new LabelMasterRequest(name, color, type, sortNo, dtctTypeCd);
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

    // ─── V100 — AI 검출 매핑 (COCO명 → DTCT_TYPE_CD → LABEL_ID 조회, HIGH#7) ───

    /** helper — labelId 가 부여된 LsLabel 생성. */
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
    @DisplayName("findLabelIdByDtctType_COCO매핑_존재시_labelId_반환")
    void findLabelIdByDtctType_매칭() {
        when(repository.findByDtctTypeCdAndUseYn("person", "Y"))
                .thenReturn(Optional.of(labelWithId(1L, "사람")));

        Optional<Long> result = service.findLabelIdByDtctType("person");

        assertThat(result).contains(1L);
        verify(repository).findByDtctTypeCdAndUseYn("person", "Y");
    }

    @Test
    @DisplayName("findLabelIdByDtctType_앞뒤_공백_trim_후_매칭")
    void findLabelIdByDtctType_공백_trim() {
        when(repository.findByDtctTypeCdAndUseYn("person", "Y"))
                .thenReturn(Optional.of(labelWithId(1L, "사람")));

        Optional<Long> result = service.findLabelIdByDtctType("  person  ");

        assertThat(result).contains(1L);
        verify(repository).findByDtctTypeCdAndUseYn("person", "Y");
    }

    @Test
    @DisplayName("findLabelIdByDtctType_미매핑_COCO명이면_Optional_empty")
    void findLabelIdByDtctType_미매핑() {
        when(repository.findByDtctTypeCdAndUseYn("car", "Y")).thenReturn(Optional.empty());

        assertThat(service.findLabelIdByDtctType("car")).isEmpty();
    }

    @Test
    @DisplayName("findLabelIdByDtctType_null_빈문자열이면_repository_미호출_empty")
    void findLabelIdByDtctType_null_빈() {
        assertThat(service.findLabelIdByDtctType(null)).isEmpty();
        assertThat(service.findLabelIdByDtctType("   ")).isEmpty();
        verify(repository, never()).findByDtctTypeCdAndUseYn(any(), any());
    }

    @Test
    @DisplayName("findLabelIdByDtctType_use_yn_Y_만_조회")
    void findLabelIdByDtctType_활성만() {
        when(repository.findByDtctTypeCdAndUseYn("person", "Y")).thenReturn(Optional.empty());

        service.findLabelIdByDtctType("person");

        verify(repository).findByDtctTypeCdAndUseYn("person", "Y");
    }

    // ─── V100 — COCO 매핑 저장/검증 (allowlist 400, 중복 409) ───

    @Test
    @DisplayName("생성시_유효한_COCO매핑은_dtctTypeCd로_저장된다")
    void create_COCO매핑_저장() {
        when(repository.existsActiveByNormalizedName("사람", "Y")).thenReturn(false);
        when(repository.existsActiveByDtctType("person", "Y")).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelMasterResponse res = service.create(req("사람", "#E74C3C", "BBOX", 1, "person"), "1001");

        ArgumentCaptor<LsLabel> captor = ArgumentCaptor.forClass(LsLabel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDtctTypeCd()).isEqualTo("person");
        assertThat(res.dtctTypeCd()).isEqualTo("person");
    }

    @Test
    @DisplayName("생성시_미지원_COCO값이면_INVALID_INPUT_400_이고_저장안함")
    void create_미지원_COCO_400() {
        when(repository.existsActiveByNormalizedName(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(req("사람", "#E74C3C", "BBOX", 1, "not-a-coco"), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("INVALID_INPUT"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("생성시_이미_사용중인_COCO매핑이면_CONFLICT_409")
    void create_중복_COCO매핑_409() {
        when(repository.existsActiveByNormalizedName("사람", "Y")).thenReturn(false);
        when(repository.existsActiveByDtctType("person", "Y")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req("사람", "#E74C3C", "BBOX", 1, "person"), "1001"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("생성시_COCO매핑_null이면_검증_생략_저장_성공_미매핑")
    void create_매핑없음_성공() {
        when(repository.existsActiveByNormalizedName("사람", "Y")).thenReturn(false);
        when(repository.save(any(LsLabel.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelMasterResponse res = service.create(req("사람", "#E74C3C", "BBOX", 1, null), "1001");

        assertThat(res.dtctTypeCd()).isNull();
        // 미매핑이면 중복 매핑 검사 미수행
        verify(repository, never()).existsActiveByDtctType(any(), any());
    }

    @Test
    @DisplayName("수정시_다른_라벨과_COCO매핑_중복이면_CONFLICT_409_자기제외")
    void update_중복_COCO매핑_409() {
        LsLabel label = LsLabel.create("사람", "#E74C3C", "BBOX", 1, "seed");
        when(repository.findById(10L)).thenReturn(Optional.of(label));
        when(repository.existsActiveByNormalizedNameExcludingId("사람", "Y", 10L)).thenReturn(false);
        when(repository.existsActiveByDtctTypeExcludingId("person", "Y", 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(10L, req("사람", "#E74C3C", "BBOX", 1, "person"), "1002"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));
    }

    @Test
    @DisplayName("mappedDetectClasses_활성_매핑_COCO집합_반환")
    void mappedDetectClasses_반환() {
        when(repository.findMappedDtctTypeCds("Y")).thenReturn(List.of("person", "car"));

        assertThat(service.mappedDetectClasses()).containsExactlyInAnyOrder("person", "car");
        verify(repository).findMappedDtctTypeCds("Y");
    }

    @Test
    @DisplayName("listDetectCandidates_활성라벨_매핑여부_함께_반환")
    void listDetectCandidates_매핑여부() {
        LsLabel mapped = LsLabel.create("사람", "#E74C3C", "BBOX", 1, "person", "seed");
        LsLabel unmapped = LsLabel.create("커스텀", "#3498DB", "BBOX", 2, "seed");
        when(repository.findByUseYnOrderBySortSeqAsc("Y")).thenReturn(List.of(mapped, unmapped));

        var result = service.listDetectCandidates();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).dtctTypeCd()).isEqualTo("person");
        assertThat(result.get(0).mapped()).isTrue();
        assertThat(result.get(1).dtctTypeCd()).isNull();
        assertThat(result.get(1).mapped()).isFalse();
    }
}
