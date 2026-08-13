package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.version.dto.SnapshotVersionRef;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 회차 스냅샷 <b>읽기</b>의 단일 진실원 — 회차↔스냅샷 해석 규칙 + 본문 파싱.
 *
 * <p>불러오기(API-195)와 확정 저장(API-196)이 이 규칙을 <b>공유</b>한다. 두 곳이 각자 해석하면
 * 화면에 보인 것과 저장된 것이 갈려, 사용자가 확인하지 않은 내용이 확정된다.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>프레임마다 <b>회차 ≤ N 중 최대</b>를 고른다. 판정 원천은 매핑({@code LS_OUTPUT_VER_SNPSH})이고
 *       {@code LS_LABEL_VERSION.VER_NO} 가 <b>아니다</b>(한 스냅샷이 여러 회차의 내용일 수 있다).</li>
 *   <li>손상 스냅샷은 빈 결과가 아니라 <b>400</b>. {@code blank} 는 손상이 아니라 라벨 0건이다.</li>
 *   <li>스냅샷은 {@code labelId}·{@code trackId}·자동라벨 여부·신뢰도·출처를 <b>모두</b> 담고 있다 —
 *       확정 저장의 복원이 이 사실에 의존한다.</li>
 *   <li><b>읽기 전용</b> — 활성 표식·회차 매핑을 쓰지 않는다.</li>
 * </ul>
 *
 * @design API-195
 * @design API-196
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class VersionSnapshotReaderTest {

    private static final Long RAW_SN = 9L;
    private static final Long SRC_A = 51L;
    private static final Long SRC_B = 52L;

    /** 폐기 축 + 생산이력 + trackId 를 담은 스냅샷(D5 이후 형식). */
    private static final String PAYLOAD_FULL = """
            {"srcSn":51,"frameNo":0,"dscdYn":"Y","items":[
              {"id":9001,"lblTypeCd":"BBOX","label":"사람","labelId":12,"labelName":"사람",
               "color":"#EF4444","points":[[120.0,80.0],[260.0,400.0]],"autoLblYn":"Y",
               "confScore":0.87,"trackId":"7","lblSrcCd":"AUTO_YOLO"}]}""";
    /** 폐기 축이 없는 옛 형식 — 읽는 쪽이 "폐기 아님"으로 해석해야 한다. */
    private static final String PAYLOAD_LEGACY = """
            {"srcSn":52,"frameNo":1,"items":[]}""";

    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LsOutputVerSnpshRepository outputVerSnpshRepository;

    private VersionSnapshotReader reader;

    @BeforeEach
    void setUp() {
        reader = new VersionSnapshotReader(labelVersionRepository, outputVerSnpshRepository,
                new ObjectMapper());
    }

    // ---------- 회차↔스냅샷 해석 ----------

    @Test
    @DisplayName("프레임마다_요청회차_이하_중_가장_큰_회차의_스냅샷을_고른다")
    void 프레임마다_요청회차_이하_중_가장_큰_회차를_고른다() {
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 3)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, 1, 1001L),
                new SnapshotVersionRef(SRC_A, 3, 1003L),
                new SnapshotVersionRef(SRC_B, 1, 1002L)));

        Map<Long, Long> targets = reader.resolveTargets(RAW_SN, 3);

        assertThat(targets).containsEntry(SRC_A, 1003L).containsEntry(SRC_B, 1002L);
    }

    @Test
    @DisplayName("롤백된_회차는_그_회차의_실제_내용을_고른다 — 판정_원천은_매핑이지_VER_NO_가_아니다")
    void 롤백된_회차는_그_회차의_실제_내용을_고른다() {
        // v1=내용A(1001) / v2=내용B(1002) / v3=롤백으로 다시 A(1001).
        //   1002 는 비활성이지만 VER_NO=2 를 단 채 남아, 번호 기반 규칙은 이것을 골랐다(조용한 오복원).
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 3)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, 1, 1001L),
                new SnapshotVersionRef(SRC_A, 2, 1002L),
                new SnapshotVersionRef(SRC_A, 3, 1001L)));

        assertThat(reader.resolveTargets(RAW_SN, 3)).containsEntry(SRC_A, 1001L);
    }

    @Test
    @DisplayName("회차번호가_결측인_참조는_후보에서_제외한다")
    void 회차번호가_결측인_참조는_제외한다() {
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 2)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, null, 9999L),
                new SnapshotVersionRef(SRC_A, 1, 1001L)));

        assertThat(reader.resolveTargets(RAW_SN, 2)).containsEntry(SRC_A, 1001L);
    }

    @Test
    @DisplayName("매핑이_없는_프레임은_비어있음을_돌려준다 — 없는_과거를_지어내지_않는다")
    void 매핑이_없는_프레임은_비어있음을_돌려준다() {
        assertThat(reader.readFrame(Map.of(), SRC_A)).isEmpty();
        verify(labelVersionRepository, never()).findPayloadById(org.mockito.ArgumentMatchers.anyLong());
    }

    // ---------- 본문 파싱 ----------

    @Test
    @DisplayName("스냅샷은_생산이력과_추적_식별자를_모두_담고_있다 — 확정_저장의_복원_원천")
    void 스냅샷은_생산이력과_추적_식별자를_담는다() {
        when(labelVersionRepository.findPayloadById(1001L)).thenReturn(Optional.of(PAYLOAD_FULL));

        VersionSnapshotReader.FrameSnapshot frame =
                reader.readFrame(Map.of(SRC_A, 1001L), SRC_A).orElseThrow();

        assertThat(frame.dscdYn()).isEqualTo("Y");
        assertThat(frame.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(9001L);
            // ★ labelId 를 잃으면 저장 후 라벨 마스터 조인이 끊긴다(색·라벨명·속성 정의 동반 소실).
            assertThat(item.labelId()).isEqualTo(12L);
            // ★ 이 셋이 있어야 "사람이 그린 것인지 자동으로 붙은 것인지"를 복원할 수 있다.
            assertThat(item.autoLblYn()).isEqualTo("Y");
            assertThat(item.confScore()).isEqualByComparingTo("0.87");
            assertThat(item.lblSrcCd()).isEqualTo("AUTO_YOLO");
            assertThat(item.trackId()).isEqualTo("7");
        });
    }

    @Test
    @DisplayName("폐기여부가_없는_옛_스냅샷은_폐기_아님으로_읽는다 — 과도기_호환")
    void 옛_스냅샷은_폐기_아님으로_읽는다() {
        when(labelVersionRepository.findPayloadById(1002L)).thenReturn(Optional.of(PAYLOAD_LEGACY));

        VersionSnapshotReader.FrameSnapshot frame =
                reader.readFrame(Map.of(SRC_B, 1002L), SRC_B).orElseThrow();

        assertThat(frame.dscdYn()).isEqualTo("N");
        assertThat(frame.items()).isEmpty();
    }

    @Test
    @DisplayName("payload_가_비어있는_것은_손상이_아니라_라벨_0건이다")
    void payload_가_비어있으면_라벨_0건() {
        when(labelVersionRepository.findPayloadById(1001L)).thenReturn(Optional.of(""));

        assertThat(reader.readFrame(Map.of(SRC_A, 1001L), SRC_A).orElseThrow().items()).isEmpty();
    }

    @Test
    @DisplayName("items_키가_없는_손상_스냅샷은_빈_결과가_아니라_400_이다")
    void items_키가_없으면_400() {
        when(labelVersionRepository.findPayloadById(1001L)).thenReturn(Optional.of("{\"srcSn\":51}"));

        assertThatThrownBy(() -> reader.readFrame(Map.of(SRC_A, 1001L), SRC_A))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("items_가_null_인_스냅샷도_손상이라_400_이다")
    void items_가_null_이면_400() {
        when(labelVersionRepository.findPayloadById(1001L)).thenReturn(Optional.of("{\"items\":null}"));

        assertThatThrownBy(() -> reader.readFrame(Map.of(SRC_A, 1001L), SRC_A))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ---------- 읽기 전용 ----------

    @Test
    @DisplayName("해석은_회차_기록을_쓰지_않는다 — 활성_표식_변경_금지")
    void 해석은_회차_기록을_쓰지_않는다() {
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));

        reader.resolveTargets(RAW_SN, 1);

        // 각 회차는 서로 간섭해선 안 된다 — 확정 저장은 덮어쓰기가 아니라 새로 저장이다.
        verify(outputVerSnpshRepository, never())
                .recordActiveSnapshots(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyString());
        verify(labelVersionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("본문만_스칼라로_읽는다 — 엔티티를_영속성_컨텍스트에_쌓지_않는다")
    void 본문만_스칼라로_읽는다() {
        when(labelVersionRepository.findPayloadById(1001L)).thenReturn(Optional.of(PAYLOAD_LEGACY));

        reader.readFrame(Map.of(SRC_A, 1001L), SRC_A);

        // 프레임마다 엔티티를 올리면 payload(프레임당 최대 10MB)가 트랜잭션 내내 힙에 고정된다.
        //   EntityManager.clear() 로 풀 수 없다 — 이 경로는 dirty checking 으로 커밋한다.
        verify(labelVersionRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }
}
