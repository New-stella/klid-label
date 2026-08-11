package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.config.StartVersionProperties;
import kr.co.cudo.authoring.version.dto.SnapshotVersionRef;
import kr.co.cudo.authoring.version.dto.VersionLabelsResponse;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * API-195 — 산출 회차 <b>불러오기</b>(읽기 전용) 단위 테스트.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li><b>서버에 아무것도 쓰지 않는다</b> — 감사 이력·상태 전이·통지·롤백 호출이 하나도 없다.
 *       구 {@code PUT /start-version}(즉시 적용)이 폐기됐다는 사실을 이 축이 지킨다.</li>
 *   <li>조회 규칙 — 프레임마다 <b>회차↔스냅샷 매핑</b>({@code LS_OUTPUT_VER_SNPSH})에서
 *       「회차 ≤ N 중 최대」를 고른다. {@code LS_LABEL_VERSION.VER_NO} 는 판정 원천이 <b>아니다</b>.</li>
 *   <li>{@code labelId} 를 응답에 실어 보낸다 — 이 값이 빠지면 확정 저장 후 라벨 마스터 조인이 끊겨
 *       표시 색상·라벨명·속성 정의가 함께 사라진다(실사고 이력).</li>
 *   <li>요청 회차가 그 영상에 실재하지 않으면 조용한 빈 결과가 아니라 <b>404</b>.</li>
 *   <li>손상 스냅샷은 빈 결과가 아니라 <b>400</b>(그 위에서 저장하면 라벨이 통째로 지워진다).</li>
 *   <li>매핑이 없는 프레임은 없는 과거를 추측하지 않고 <b>현재 작업본</b>을 싣고 {@code resolved=false}.</li>
 *   <li>비식별 신고 구간은 <b>412</b> — 라벨 좌표가 개인정보 위치를 특정하는 정보이기 때문.</li>
 *   <li>자원 상한 — 프레임 수 초과는 로드 이전에 <b>400</b>(CWE-770).</li>
 * </ul>
 *
 * @design API-195
 * @design D4
 * @design D5
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class StartVersionServiceTest {

    private static final Long RAW_SN = 9L;
    private static final Long SRC_A = 51L;
    private static final Long SRC_B = 52L;
    private static final Long SRC_C = 53L;

    private static final int MAX_FRAMES = 3;

    /** 폐기 축을 담은 새 형식 스냅샷(D5 이후) + 라벨 1건. */
    private static final String PAYLOAD_DISCARDED = """
            {"srcSn":51,"frameNo":0,"dscdYn":"Y","items":[
              {"id":9001,"lblTypeCd":"BBOX","label":"사람","labelId":12,"labelName":"사람",
               "color":"#EF4444","points":[[120.0,80.0],[260.0,400.0]],"autoLblYn":"N",
               "confScore":null,"trackId":"7","lblSrcCd":null}]}""";
    /** 폐기 축이 없는 옛 형식 스냅샷(D5 이전) — 읽는 쪽이 "폐기 아님"으로 해석해야 한다. */
    private static final String PAYLOAD_LEGACY = """
            {"srcSn":52,"frameNo":1,"items":[]}""";

    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository labelRepository;
    @Mock private LsDataLblAiInfoRepository aiInfoRepository;
    @Mock private LsLabelRepository lsLabelRepository;
    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LsOutputVerSnpshRepository outputVerSnpshRepository;

    private StartVersionService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new StartVersionService(accessGuard, videoRepository, srcRepository,
                labelRepository, aiInfoRepository, lsLabelRepository, labelVersionRepository,
                outputVerSnpshRepository, new StartVersionProperties(MAX_FRAMES), new ObjectMapper());
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    // ---------- 입력·인가·게이트 ----------

    @Test
    @DisplayName("인증_토큰이_없으면_401")
    void 인증_토큰이_없으면_401() {
        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 1, null))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("버전번호가_1미만이면_400")
    void 버전번호가_1미만이면_400() {
        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 0, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("본인_배정이_아닌_영상_불러오기_시_403")
    void 본인_배정이_아닌_영상_불러오기_시_403() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());

        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        // 인가 이전에 라벨 본문을 읽지 않는다.
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("비식별_신고_구간이면_불러오기도_412 — 라벨_좌표는_개인정보_위치_정보다")
    void 비식별_신고_구간이면_불러오기도_412() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("그_영상에_실재하지_않는_버전번호는_404_로_거부한다")
    void 그_영상에_실재하지_않는_버전번호는_404_로_거부한다() {
        stubGatesOpen();
        when(labelVersionRepository.existsByDataRawSnAndVersionNo(RAW_SN, 7)).thenReturn(false);

        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 7, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("프레임_상한을_넘으면_엔티티를_로드하기_전에_400_으로_거부한다")
    void 프레임_상한을_넘으면_거부한다() {
        stubGatesOpen();
        when(labelVersionRepository.existsByDataRawSnAndVersionNo(RAW_SN, 1)).thenReturn(true);
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(4L);

        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        // ★ 상한은 <로드 이전>에 작동해야 한다(CWE-770).
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    // ---------- 읽기 전용 (이 재설계의 핵심) ----------

    @Test
    @DisplayName("API_195_는_서버에_아무것도_쓰지_않는다")
    void API_195_는_서버에_아무것도_쓰지_않는다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_DISCARDED, 1);

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 1, reviewer);

        assertThat(res.frames()).hasSize(1);
        // 폐기 상태 전이 · 라벨 교체 · 감사 · 통지 어느 것도 없다. 상태를 바꾸는 협력자가 아예
        //   주입되지 않으므로(생성자에 없다) 쓰기가 구조적으로 불가능하다 — 아래는 남은 쓰기 표면 점검.
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
        verify(srcRepository, never()).bumpLabelVersionIn(anyCollection());
        verify(srcRepository, never()).lockAndReadLabelVersion(anyLong());
        verify(labelVersionRepository, never()).save(any());
        verify(labelVersionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("API_195_는_회차_이하_최대_스냅샷을_해석해_돌려준다")
    void API_195_는_회차_이하_최대_스냅샷을_해석해_돌려준다() {
        stubGatesOpen();
        stubVersionExists(3);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 3)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, 1, 1001L),
                new SnapshotVersionRef(SRC_A, 3, 1003L),
                new SnapshotVersionRef(SRC_B, 1, 1002L)));
        stubSnapshot(1003L, SRC_A, PAYLOAD_DISCARDED, 3);
        stubSnapshot(1002L, SRC_B, PAYLOAD_LEGACY, 1);

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 3, reviewer);

        assertThat(res.version()).isEqualTo(3);
        assertThat(res.frames()).extracting(VersionLabelsResponse.Frame::srcSn)
                .containsExactly(SRC_A, SRC_B);
        // 회차 1(1001)이 아니라 회차 3(1003)의 내용이어야 한다 — 그 프레임에서 폐기 축이 'Y' 다.
        assertThat(res.frames().get(0).dscdYn()).isEqualTo("Y");
        assertThat(res.frames().get(0).resolved()).isTrue();
        // 폐기 축이 없는 옛 형식은 "폐기 아님"으로 읽는다(과도기 호환).
        assertThat(res.frames().get(1).dscdYn()).isEqualTo("N");
        verify(labelVersionRepository, never()).findById(1001L);
    }

    @Test
    @DisplayName("롤백된_회차는_그_회차의_실제_내용을_돌려준다 — 판정_원천은_매핑이지_VER_NO_가_아니다")
    void 롤백된_회차는_그_회차의_실제_내용을_돌려준다() {
        stubGatesOpen();
        stubVersionExists(3);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 3)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, 1, 1001L),
                new SnapshotVersionRef(SRC_A, 2, 1002L),
                new SnapshotVersionRef(SRC_A, 3, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_DISCARDED, 1);

        service.loadVersionLabels(RAW_SN, 3, reviewer);

        // v3 의 내용은 1001 이다 — 그 사이 회차의 비활성 스냅샷(1002)을 읽으면 조용한 오복원이다.
        verify(labelVersionRepository).findById(1001L);
        verify(labelVersionRepository, never()).findById(1002L);
    }

    @Test
    @DisplayName("API_195_는_labelId_를_응답에_실어보낸다")
    void API_195_는_labelId_를_응답에_실어보낸다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_DISCARDED, 1);

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 1, reviewer);

        assertThat(res.frames().get(0).items()).singleElement()
                .satisfies(item -> {
                    // ★ labelId 가 빠지면 확정 저장 후 마스터 조인이 끊겨 색상·라벨명·속성 정의가 함께
                    //   사라진다(저장 전에는 정상으로 보여 발견이 늦는 실사고 유형).
                    assertThat(item.labelId()).isEqualTo(12L);
                    assertThat(item.id()).isEqualTo(9001L);
                    assertThat(item.trackId()).isEqualTo("7");
                    assertThat(item.points()).containsExactly(List.of(120.0, 80.0), List.of(260.0, 400.0));
                });
    }

    @Test
    @DisplayName("불러오기는_판번호를_함께_내려준다 — 확정_저장의_전수_검증_입력")
    void 불러오기는_판번호를_함께_내려준다() {
        stubGatesOpen();
        stubVersionExists(1);
        LsDataSrc a = frame(SRC_A, 0);
        ReflectionTestUtils.setField(a, "lblVer", 12L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(a));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_LEGACY, 1);

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 1, reviewer);

        assertThat(res.frames().get(0).lblVer()).isEqualTo(12L);
    }

    @Test
    @DisplayName("요청회차_이하_매핑이_없는_프레임은_현재_작업본을_싣고_미해결로_표시한다")
    void 요청회차_이하_매핑이_없는_프레임은_현재_작업본을_싣는다() {
        stubGatesOpen();
        stubVersionExists(2);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_C, 2)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 2))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 2, 1003L)));
        stubSnapshot(1003L, SRC_A, PAYLOAD_DISCARDED, 2);
        when(labelRepository.findBySrcSnIn(List.of(SRC_A, SRC_C)))
                .thenReturn(List.of(workingLabel(7001L, SRC_C, "차량", 33L)));

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 2, reviewer);

        VersionLabelsResponse.Frame unresolved = res.frames().get(1);
        assertThat(unresolved.resolved()).isFalse();
        // 라벨 0건으로 내려주면 그 위에서 저장할 때 남아 있던 라벨이 통째로 지워진다.
        assertThat(unresolved.items()).singleElement()
                .satisfies(item -> assertThat(item.labelId()).isEqualTo(33L));
        assertThat(unresolved.dscdYn()).isEqualTo("N");
    }

    // ---------- 손상 스냅샷 (fail-closed) ----------

    @Test
    @DisplayName("손상된_스냅샷은_빈_결과가_아니라_400_이다")
    void 손상된_스냅샷은_400() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, "{\"srcSn\":51}", 1);

        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("items_가_null_인_스냅샷도_손상이라_400_이다")
    void items_가_null_인_스냅샷도_400() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, "{\"srcSn\":51,\"items\":null}", 1);

        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("payload_가_비어있는_것은_손상이_아니라_라벨_0건이다")
    void payload_가_비어있으면_라벨_0건() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, "", 1);

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 1, reviewer);

        assertThat(res.frames().get(0).items()).isEmpty();
        assertThat(res.frames().get(0).resolved()).isTrue();
    }

    @Test
    @DisplayName("전_프레임이_해석되면_작업본_조회를_아예_하지_않는다 — 불필요한_쿼리_금지")
    void 전_프레임이_해석되면_작업본_조회를_하지_않는다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_LEGACY, 1);

        service.loadVersionLabels(RAW_SN, 1, reviewer);

        verifyNoInteractions(labelRepository);
    }

    // ---------- 고정 스텁 ----------

    private void stubGatesOpen() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
    }

    private void stubVersionExists(int versionNo) {
        when(labelVersionRepository.existsByDataRawSnAndVersionNo(RAW_SN, versionNo)).thenReturn(true);
    }

    private void stubSnapshot(Long labelVersionSn, Long srcSn, String payload, int versionNo) {
        when(labelVersionRepository.findById(labelVersionSn))
                .thenReturn(Optional.of(snapshot(labelVersionSn, srcSn, payload, versionNo)));
    }

    private LsLabelVersion snapshot(Long labelVersionSn, Long srcSn, String payload, int versionNo) {
        LsLabelVersion v = LsLabelVersion.create(RAW_SN, srcSn, "h" + labelVersionSn, payload,
                versionNo, LsLabelVersion.SAVE_REASON_APPROVED, "1");
        ReflectionTestUtils.setField(v, "labelVersionSn", labelVersionSn);
        return v;
    }

    private LsDataSrc frame(Long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, frameNo, "/raw/" + frameNo + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataLbl workingLabel(Long lblSn, Long srcSn, String label, Long labelId) {
        LsDataLbl lbl = LsDataLbl.createManual(srcSn, "BBOX", labelId, label,
                "[[1.0,2.0],[3.0,4.0]]", 1L);
        ReflectionTestUtils.setField(lbl, "lblSn", lblSn);
        return lbl;
    }

    private LsDataRaw raw() {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-SV", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/clip.mp4", LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        return raw;
    }
}
