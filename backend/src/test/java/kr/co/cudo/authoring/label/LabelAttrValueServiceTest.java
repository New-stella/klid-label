package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelAttrValueResponse;
import kr.co.cudo.authoring.label.dto.LabelAttrValueUpsertRequest;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelAttrValueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 객체별 속성값 응용 서비스 단위 테스트 (Mockito).
 *
 * <p>CVAT-Like 라벨 풀 포팅 Phase 3.
 */
class LabelAttrValueServiceTest {

    private static final Long LBL_SN = 100L;
    private static final Long LABEL_ID = 10L;
    /** 라벨 객체가 속한 프레임의 영상 PK — 신고 게이트 판정 대상. */
    private static final Long RAW_SN = 7L;

    private LsDataLblAttrValRepository valueRepository;
    private LsLabelAttrRepository attrRepository;
    private LsDataLblRepository labelRowRepository;
    private LabelAccessGuard accessGuard;
    private LabelAttrValueService service;

    /** 인가 검사 대상 actor — 가드는 목이므로 역할값 자체는 판정에 쓰이지 않는다. */
    private static final TokenClaims ACTOR =
            new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));

    @BeforeEach
    void setUp() {
        valueRepository = mock(LsDataLblAttrValRepository.class);
        attrRepository = mock(LsLabelAttrRepository.class);
        labelRowRepository = mock(LsDataLblRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        service = new LabelAttrValueService(valueRepository, attrRepository, labelRowRepository, accessGuard);
        // 인가 가드는 조회한 프레임을 돌려준다 — 서비스는 그 프레임의 rawSn 으로 신고 게이트를 호출한다
        // (게이트 때문에 프레임을 다시 조회하지 않는다는 계약).
        when(accessGuard.verifyAndGet(anyLong(), any()))
                .thenReturn(LsDataSrc.create(RAW_SN, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
    }

    /** LABEL_ID 가 연결된 BBOX 라벨 row 모킹. */
    private static LsDataLbl bboxWithLabelId(Long labelId) {
        return LsDataLbl.builder()
                .srcSn(1L)
                .lblTypeCd("BBOX")
                .labelId(labelId)
                .label("person")
                .pointsJson("[[0,0],[10,10]]")
                .autoLblYn("N")
                .build();
    }

    private static LabelAttrValueUpsertRequest.Entry entry(Long attrId, String value) {
        return new LabelAttrValueUpsertRequest.Entry(attrId, value);
    }

    @Test
    @DisplayName("upsert_라벨과_속성의_labelId_불일치_시_INVALID_INPUT")
    void upsert_labelId불일치_INVALID_INPUT() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        // 속성은 다른 라벨(999) 소속.
        LsLabelAttr attr = LsLabelAttr.create(999L, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0, "seed");
        when(attrRepository.findById(11L)).thenReturn(Optional.of(attr));

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("INVALID_INPUT"));

        verify(valueRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert_신규_attrId_INSERT")
    void upsert_신규_INSERT() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        LsLabelAttr attr = LsLabelAttr.create(LABEL_ID, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0, "seed");
        when(attrRepository.findById(11L)).thenReturn(Optional.of(attr));
        when(valueRepository.findByLblSnAndAttrId(LBL_SN, 11L)).thenReturn(Optional.empty());
        when(valueRepository.save(any(LsDataLblAttrVal.class))).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR);

        ArgumentCaptor<LsDataLblAttrVal> captor = ArgumentCaptor.forClass(LsDataLblAttrVal.class);
        verify(valueRepository).save(captor.capture());
        assertThat(captor.getValue().getLblSn()).isEqualTo(LBL_SN);
        assertThat(captor.getValue().getAttrId()).isEqualTo(11L);
        assertThat(captor.getValue().getValue()).isEqualTo("yes");
    }

    @Test
    @DisplayName("upsert_기존_attrId_UPDATE_value")
    void upsert_기존_UPDATE() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        LsLabelAttr attr = LsLabelAttr.create(LABEL_ID, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0, "seed");
        when(attrRepository.findById(11L)).thenReturn(Optional.of(attr));

        LsDataLblAttrVal existing = LsDataLblAttrVal.create(LBL_SN, 11L, "no");
        when(valueRepository.findByLblSnAndAttrId(LBL_SN, 11L)).thenReturn(Optional.of(existing));

        service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR);

        // 기존 row 의 value 만 업데이트 — save 신규 호출 X.
        assertThat(existing.getValue()).isEqualTo("yes");
        verify(valueRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert_USE_YN_N_속성_시_INVALID_INPUT")
    void upsert_비활성속성_INVALID_INPUT() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        LsLabelAttr attr = LsLabelAttr.create(LABEL_ID, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0, "seed");
        attr.softDelete("seed"); // USE_YN='N'
        when(attrRepository.findById(11L)).thenReturn(Optional.of(attr));

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("INVALID_INPUT"));

        verify(valueRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert_LBL_SN_미존재_시_NOT_FOUND")
    void upsert_lblSn미존재_NOT_FOUND() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("NOT_FOUND"));
    }

    @Test
    @DisplayName("upsert_LABEL_ID_미연결_객체_시_INVALID_INPUT")
    void upsert_labelId미연결_INVALID_INPUT() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(null)));

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("INVALID_INPUT"));
    }

    @Test
    @DisplayName("find_시_attrId_ASC_정렬_및_name_inputType_동봉")
    void find_정렬_및_조인() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));

        LsDataLblAttrVal v1 = LsDataLblAttrVal.create(LBL_SN, 11L, "yes");
        LsDataLblAttrVal v2 = LsDataLblAttrVal.create(LBL_SN, 12L, "N");
        when(valueRepository.findByLblSnOrderByAttrIdAsc(LBL_SN)).thenReturn(List.of(v1, v2));

        // attrId 는 IDENTITY — Mockito 매처에서 정확 매칭은 같은 List 라도 attr 본체의 attrId 가 null 인 한
        // 결과 map 키가 null 이 되어 어설션이 실패한다. 따라서 spied attr 로 attrId 를 강제 주입.
        LsLabelAttr a1 = spy(LsLabelAttr.create(LABEL_ID, "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0, "seed"));
        LsLabelAttr a2 = spy(LsLabelAttr.create(LABEL_ID, "direction", "RADIO", "[\"N\",\"S\",\"E\",\"W\"]", null, "Y", 1, "seed"));
        when(a1.getAttrId()).thenReturn(11L);
        when(a2.getAttrId()).thenReturn(12L);
        when(attrRepository.findAllById(anyIterable())).thenReturn(List.of(a1, a2));

        List<LabelAttrValueResponse> result = service.findByLblSn(LBL_SN, ACTOR);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).attrId()).isEqualTo(11L);
        assertThat(result.get(0).name()).isEqualTo("occluded");
        assertThat(result.get(0).inputType()).isEqualTo("SELECT");
        assertThat(result.get(0).value()).isEqualTo("yes");
        assertThat(result.get(1).attrId()).isEqualTo(12L);
        assertThat(result.get(1).name()).isEqualTo("direction");
        assertThat(result.get(1).value()).isEqualTo("N");
    }

    @Test
    @DisplayName("find_LBL_SN_미존재_시_NOT_FOUND")
    void find_lblSn미존재_NOT_FOUND() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByLblSn(LBL_SN, ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("NOT_FOUND"));
    }

    @Test
    @DisplayName("find_빈_결과_시_빈_리스트")
    void find_빈결과() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        when(valueRepository.findByLblSnOrderByAttrIdAsc(LBL_SN)).thenReturn(List.of());

        assertThat(service.findByLblSn(LBL_SN, ACTOR)).isEmpty();
    }

    // ────────────── 영상 단위 인가 (CWE-639 IDOR) ──────────────

    @Test
    @DisplayName("조회_시_라벨_객체가_속한_프레임으로_영상_단위_인가를_검사한다")
    void find_영상단위_인가_위임() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        when(valueRepository.findByLblSnOrderByAttrIdAsc(LBL_SN)).thenReturn(List.of());

        service.findByLblSn(LBL_SN, ACTOR);

        // bboxWithLabelId 의 srcSn = 1L — lblSn 이 아니라 프레임으로 판정해야 한다.
        verify(accessGuard).verifyAndGet(1L, ACTOR);
    }

    @Test
    @DisplayName("조회_시_본인_배정이_아니면_FORBIDDEN_이고_속성값을_읽지_않는다")
    void find_미배정_FORBIDDEN() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        when(accessGuard.verifyAndGet(anyLong(), any()))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."));

        assertThatThrownBy(() -> service.findByLblSn(LBL_SN, ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("FORBIDDEN"));

        verify(valueRepository, never()).findByLblSnOrderByAttrIdAsc(anyLong());
    }

    @Test
    @DisplayName("저장_시_본인_배정이_아니면_FORBIDDEN_이고_값이_저장되지_않는다")
    void upsert_미배정_FORBIDDEN() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        when(accessGuard.verifyAndGet(anyLong(), any()))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."));

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("FORBIDDEN"));

        verify(valueRepository, never()).save(any());
        verify(valueRepository, never()).findByLblSnAndAttrId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("미존재_라벨_객체는_인가_검사_전에_NOT_FOUND_로_끝난다_존재_오라클_방지")
    void 미존재는_인가_이전에_NOT_FOUND() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.findByLblSn(LBL_SN, ACTOR))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("NOT_FOUND"));

        verify(accessGuard, never()).verifyAndGet(anyLong(), any());
    }

    // ────────────── 비식별 누락 신고 게이트 (CWE-359) ──────────────

    /** 이 프레임의 영상을 신고 구간으로 만든다 — 게이트가 412 를 던지도록 스텁. */
    private void openDeidentReport() {
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED,
                "비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);
    }

    @Test
    @DisplayName("게이트는_인가가_해석한_프레임의_영상PK로_판정한다_재조회하지_않는다")
    void 게이트는_인가가_해석한_rawSn으로_판정한다() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        when(valueRepository.findByLblSnOrderByAttrIdAsc(LBL_SN)).thenReturn(List.of());

        service.findByLblSn(LBL_SN, ACTOR);

        verify(accessGuard).requireNotUnderDeidentReport(RAW_SN);
    }

    @Test
    @DisplayName("신고_구간이면_속성값_조회가_412_이고_속성값을_읽지_않는다")
    void find_신고구간_PRECONDITION_FAILED() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        openDeidentReport();

        assertThatThrownBy(() -> service.findByLblSn(LBL_SN, ACTOR))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verify(valueRepository, never()).findByLblSnOrderByAttrIdAsc(anyLong());
    }

    @Test
    @DisplayName("신고_구간이면_속성값_저장이_412_이고_값이_저장되지_않는다")
    void upsert_신고구간_PRECONDITION_FAILED() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        openDeidentReport();

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verify(valueRepository, never()).save(any());
        verify(valueRepository, never()).findByLblSnAndAttrId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("미배정_WORKER_는_게이트_이전에_403_으로_끝난다_인가가_먼저다")
    void 인가가_게이트보다_먼저() {
        when(labelRowRepository.findById(LBL_SN)).thenReturn(Optional.of(bboxWithLabelId(LABEL_ID)));
        when(accessGuard.verifyAndGet(anyLong(), any()))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."));

        assertThatThrownBy(() -> service.upsert(LBL_SN, List.of(entry(11L, "yes")), ACTOR))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(accessGuard, never()).requireNotUnderDeidentReport(anyLong());
    }
}
