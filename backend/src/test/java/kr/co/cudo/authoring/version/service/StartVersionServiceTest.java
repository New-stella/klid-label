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
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.config.StartVersionProperties;
import kr.co.cudo.authoring.version.dto.VersionLabelsResponse;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
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
import java.util.Map;
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
 *   <li><b>서버에 아무것도 쓰지 않는다</b> — 상태 전이·감사·통지·회차 기록이 하나도 없다.
 *       구 {@code PUT /start-version}(즉시 적용)이 폐기됐다는 사실을 이 축이 지킨다.</li>
 *   <li>게이트 순서 — 인증(401) → 입력(400) → 인가(403) → 신고(412) → 회차 대조(404) → 상한(400).</li>
 *   <li>{@code labelId} 를 응답에 실어 보낸다 — 빠지면 확정 저장 후 라벨 마스터 조인이 끊겨
 *       표시 색상·라벨명·속성 정의가 함께 사라진다(실사고 이력).</li>
 *   <li>확정 저장에 되돌려 보낼 <b>판번호</b>를 함께 내려준다(전수 검증의 입력).</li>
 *   <li>그 회차 이하 스냅샷이 없는 프레임은 없는 과거를 추측하지 않고 <b>현재 작업본</b>을 싣고
 *       {@code resolved=false} 로 드러낸다.</li>
 * </ul>
 *
 * <p>회차↔스냅샷 해석 규칙(「회차 ≤ N 중 최대」)과 손상 payload 판정은
 * {@link VersionSnapshotReader} 가 소유하므로 {@code VersionSnapshotReaderTest} 가 고정한다 —
 * 같은 규칙을 두 곳에서 재구현하지 않았다는 뜻이다.
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
    private static final Long SRC_C = 53L;

    private static final int MAX_FRAMES = 3;

    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository labelRepository;
    @Mock private LsDataLblAiInfoRepository aiInfoRepository;
    @Mock private LsLabelRepository lsLabelRepository;
    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private VersionSnapshotReader snapshotReader;

    private StartVersionService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new StartVersionService(accessGuard, videoRepository, srcRepository,
                labelRepository, aiInfoRepository, lsLabelRepository, labelVersionRepository,
                snapshotReader, new StartVersionProperties(MAX_FRAMES), new ObjectMapper());
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
        when(snapshotReader.versionExists(RAW_SN, 7)).thenReturn(false);

        assertThatThrownBy(() -> service.loadVersionLabels(RAW_SN, 7, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("프레임_상한을_넘으면_엔티티를_로드하기_전에_400_으로_거부한다")
    void 프레임_상한을_넘으면_거부한다() {
        stubGatesOpen();
        when(snapshotReader.versionExists(RAW_SN, 1)).thenReturn(true);
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
        stubFrame(SRC_A, "Y", item(9001L, 12L, "7"));

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 1, reviewer);

        assertThat(res.frames()).hasSize(1);
        // 상태 전이·라벨 교체·감사·통지·회차 기록 어느 것도 없다.
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
        verify(srcRepository, never()).bumpLabelVersionIn(anyCollection());
        verify(srcRepository, never()).lockAndReadLabelVersion(anyLong());
        verify(labelVersionRepository, never()).save(any());
        verify(labelVersionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("API_195_는_labelId_를_응답에_실어보낸다")
    void API_195_는_labelId_를_응답에_실어보낸다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubFrame(SRC_A, "N", item(9001L, 12L, "7"));

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 1, reviewer);

        assertThat(res.frames().get(0).items()).singleElement().satisfies(item -> {
            // ★ labelId 가 빠지면 확정 저장 후 마스터 조인이 끊겨 색상·라벨명·속성 정의가 함께
            //   사라진다(저장 전에는 정상으로 보여 발견이 늦는 실사고 유형).
            assertThat(item.labelId()).isEqualTo(12L);
            assertThat(item.id()).isEqualTo(9001L);
            assertThat(item.trackId()).isEqualTo("7");
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
        stubFrame(SRC_A, "N");

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 1, reviewer);

        assertThat(res.frames().get(0).lblVer()).isEqualTo(12L);
    }

    @Test
    @DisplayName("그_회차_시점의_폐기여부를_그대로_돌려준다")
    void 폐기여부를_그대로_돌려준다() {
        stubGatesOpen();
        stubVersionExists(2);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubFrame(SRC_A, "Y");

        VersionLabelsResponse res = service.loadVersionLabels(RAW_SN, 2, reviewer);

        assertThat(res.frames().get(0).dscdYn()).isEqualTo("Y");
        assertThat(res.frames().get(0).resolved()).isTrue();
    }

    @Test
    @DisplayName("요청회차_이하_매핑이_없는_프레임은_현재_작업본을_싣고_미해결로_표시한다")
    void 매핑이_없는_프레임은_현재_작업본을_싣는다() {
        stubGatesOpen();
        stubVersionExists(2);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_C, 2)));
        stubFrame(SRC_A, "N");
        stubUnresolved(SRC_C);
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

    @Test
    @DisplayName("전_프레임이_해석되면_작업본_조회를_아예_하지_않는다 — 불필요한_쿼리_금지")
    void 전_프레임이_해석되면_작업본_조회를_하지_않는다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubFrame(SRC_A, "N");

        service.loadVersionLabels(RAW_SN, 1, reviewer);

        verifyNoInteractions(labelRepository);
    }

    // ---------- 고정 스텁 ----------

    private void stubGatesOpen() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
    }

    private void stubVersionExists(int versionNo) {
        when(snapshotReader.versionExists(RAW_SN, versionNo)).thenReturn(true);
    }

    /** 해석 결과 스텁 — 해석 규칙 자체는 {@code VersionSnapshotReaderTest} 소관이다. */
    private void stubFrame(Long srcSn, String dscdYn, LabelResponse.Item... items) {
        when(snapshotReader.readFrame(any(), eq(srcSn)))
                .thenReturn(Optional.of(new VersionSnapshotReader.FrameSnapshot(dscdYn, List.of(items))));
    }

    private void stubUnresolved(Long srcSn) {
        when(snapshotReader.readFrame(any(), eq(srcSn))).thenReturn(Optional.empty());
    }

    private LabelResponse.Item item(Long id, Long labelId, String trackId) {
        return new LabelResponse.Item(id, "BBOX", "사람", labelId, "사람", "#EF4444",
                List.of(List.of(120.0, 80.0), List.of(260.0, 400.0)), "N", null, trackId, null);
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
