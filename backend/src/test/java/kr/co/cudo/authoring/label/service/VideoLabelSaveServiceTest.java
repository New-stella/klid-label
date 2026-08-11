package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.version.config.StartVersionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API-196 진입점 — <b>트랜잭션 밖에서 하는 일</b>의 단위 테스트.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>자원 상한(CWE-770)을 <b>트랜잭션 진입 이전</b>에 판정한다 — 초과 요청은 워밍도 쓰기도 하지 않는다.</li>
 *   <li>좌표 경계 기준값을 <b>트랜잭션 밖에서</b> 일괄 확보해 넘긴다 — 쓰기 트랜잭션이 프레임마다
 *       이미지를 디코딩하며 프레임 행 락·DB 커넥션을 쥐고 있지 않게 한다(항목 ④).</li>
 *   <li>측정 불가 프레임도 <b>키를 남긴다</b> — 값 {@code null} 과 키 부재를 구분하지 못하면 쓰기 쪽이
 *       다시 파일을 연다.</li>
 * </ul>
 *
 * <p>게이트·잠금·저장 자체는 {@link VideoLabelSaveTxService} 가 소유하므로 여기서는 <b>위임 여부</b>만
 * 검증한다(같은 판정을 두 곳에서 재구현하지 않았다는 뜻).
 *
 * @design API-196
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class VideoLabelSaveServiceTest {

    private static final Long RAW_SN = 9L;
    private static final Long SRC_A = 51L;
    private static final Long SRC_B = 52L;

    private static final int MAX_FRAMES = 3;

    @Mock private LabelAccessGuard accessGuard;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private FrameBoundsResolver frameBoundsResolver;
    @Mock private VideoLabelSaveTxService txService;

    private VideoLabelSaveService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new VideoLabelSaveService(accessGuard, srcRepository, frameBoundsResolver, txService,
                new StartVersionProperties(MAX_FRAMES));
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("인증_토큰이_없으면_401")
    void 인증_토큰이_없으면_401() {
        assertThatThrownBy(() -> service.save(RAW_SN, request(frameReq(SRC_A, 1L)), null))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(txService, never()).saveInTx(any(), any(), any(), any());
    }

    @Test
    @DisplayName("프레임_목록이_비어_있으면_400")
    void 프레임_목록이_비어있으면_400() {
        assertThatThrownBy(() -> service.save(RAW_SN,
                new VideoLabelSaveRequest(3, List.of(), List.of()), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(txService, never()).saveInTx(any(), any(), any(), any());
    }

    @Test
    @DisplayName("프레임_상한을_넘으면_트랜잭션에_들어가기_전에_400_으로_거부한다")
    void 프레임_상한을_넘으면_트랜잭션_진입_전에_거부한다() {
        assertThatThrownBy(() -> service.save(RAW_SN, request(
                frameReq(SRC_A, 1L), frameReq(SRC_B, 1L), frameReq(53L, 1L), frameReq(54L, 1L)),
                reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        // ★ 상한 초과 요청은 프레임 조회·이미지 디코딩·쓰기 어느 것도 하지 않는다(CWE-770).
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
        verify(frameBoundsResolver, never()).resolve(any());
        verify(txService, never()).saveInTx(any(), any(), any(), any());
    }

    @Test
    @DisplayName("인가에_실패하면_프레임_이미지를_열지_않는다")
    void 인가에_실패하면_프레임_이미지를_열지_않는다() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());

        assertThatThrownBy(() -> service.save(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        // ★ 워밍은 피해 영상의 <b>전 프레임</b> 이미지를 디코딩한다 — 비용이 요청 크기가 아니라 피해
        //   영상의 프레임 수에 비례하는 비대칭 증폭이라, 인가 이전에 돌면 안 된다(F-04).
        verify(frameBoundsResolver, never()).resolve(any());
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
        verify(txService, never()).saveInTx(any(), any(), any(), any());
    }

    @Test
    @DisplayName("비식별_신고_구간이면_프레임_이미지를_열지_않는다")
    void 신고_구간이면_프레임_이미지를_열지_않는다() {
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.save(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(frameBoundsResolver, never()).resolve(any());
        verify(txService, never()).saveInTx(any(), any(), any(), any());
    }

    @Test
    @DisplayName("좌표_경계_기준값을_트랜잭션_밖에서_확보한_뒤_쓰기에_위임한다")
    void 좌표_경계_기준값을_트랜잭션_밖에서_확보한다() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.of(new int[] {1920, 1080}));

        service.save(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer);

        // ★ 해석(=ImageIO 디코딩)이 쓰기 트랜잭션 <b>이전에</b> 끝나야 한다. 순서가 뒤집히면 프레임 행
        //   락과 커넥션을 쥔 채 NAS I/O 를 하게 된다(FrameImageServingHardeningTest 와 같은 축).
        InOrder order = inOrder(frameBoundsResolver, txService);
        order.verify(frameBoundsResolver, org.mockito.Mockito.atLeastOnce()).resolve(any());
        order.verify(txService).saveInTx(any(), any(), any(), any());
    }

    @Test
    @DisplayName("요청에_없는_프레임까지_영상_전체_기준값을_확보한다")
    void 영상_전체_기준값을_확보한다() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.of(new int[] {640, 480}));

        service.save(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer);

        ArgumentCaptor<Map<Long, int[]>> bounds = captorOfMap();
        verify(txService).saveInTx(any(), any(), any(), bounds.capture());
        assertThat(bounds.getValue()).containsOnlyKeys(SRC_A, SRC_B);
    }

    @Test
    @DisplayName("측정_불가_프레임도_키를_남긴다 — 값_null_과_키_부재를_구분해야_쓰기가_다시_파일을_열지_않는다")
    void 측정_불가_프레임도_키를_남긴다() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.empty());

        service.save(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer);

        ArgumentCaptor<Map<Long, int[]>> bounds = captorOfMap();
        verify(txService).saveInTx(any(), any(), any(), bounds.capture());
        assertThat(bounds.getValue()).containsKey(SRC_A);
        assertThat(bounds.getValue().get(SRC_A)).isNull();
    }

    // ---------- 헬퍼 ----------

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<Long, int[]>> captorOfMap() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private VideoLabelSaveRequest request(VideoLabelSaveRequest.FrameVersion... versions) {
        return new VideoLabelSaveRequest(3, List.of(versions), List.of());
    }

    private VideoLabelSaveRequest.FrameVersion frameReq(Long srcSn, Long lblVer) {
        return new VideoLabelSaveRequest.FrameVersion(srcSn, lblVer);
    }

    private LsDataSrc frame(Long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, frameNo, "/raw/" + frameNo + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }
}
