package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver.Origin;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 자산 출처 판정과 403/404 축 — 포털 작업 창구 전체의 인가 진입점.
 *
 * @design API-234, API-235, API-236, API-237
 */
class PortalWorkTargetResolverTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final long RAW_SN = 10L;
    private static final long SRC_SN = 100L;

    private LsDataSrcRepository srcRepository;
    private VideoRepository videoRepository;
    private LsRawDataStatusRepository statusRepository;
    private PortalWorkTargetResolver resolver;

    @BeforeEach
    void setUp() {
        srcRepository = mock(LsDataSrcRepository.class);
        videoRepository = mock(VideoRepository.class);
        statusRepository = mock(LsRawDataStatusRepository.class);
        resolver = new PortalWorkTargetResolver(srcRepository, videoRepository,
                new kr.co.cudo.authoring.portal.service.PortalWorkableVideoPolicy(statusRepository, videoRepository));
    }

    // ---------------- fixtures ----------------

    private LsDataRaw datamartVideo() {
        return LsDataRaw.createFromIngest("CLIP-1", "cctv", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30);
    }

    private LsDataRaw uploadVideo(String owner) {
        return LsDataRaw.createPortalUpload(owner, "/portal/v.mp4");
    }

    private void givenFrame(LsDataRaw raw) {
        LsDataSrc frame = LsDataSrc.create(RAW_SN, 0L, "/f.png", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(frame));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
    }

    /**
     * 작업 상태를 직접 세운다 — 상태 기계의 전이 규칙(PENDING 에서 곧바로 APPROVED 로 못 간다)을
     * 우회해 <b>이 판정기가 보는 값</b>만 고정하기 위함이다. 여기서 검증하는 것은 전이 규칙이 아니라
     * 「그 값이 APPROVED 인가」다.
     */
    private void givenApproved(boolean approved) {
        LsRawDataStatus status = mock(LsRawDataStatus.class);
        when(status.getRawDataId()).thenReturn(RAW_SN);
        when(status.getDataSttsCd())
                .thenReturn(approved ? LsRawDataStatus.STTS_APPROVED : LsRawDataStatus.STTS_PENDING);
        // 단건 판정도 <일괄 판정>에 위임하므로 일괄 조회를 세운다 — 판정 리터럴이 한 곳에만 있게
        // 하려고 단건이 일괄을 부르도록 합쳤고(API-225 목록이 같은 규칙을 물어야 한다), 그 결과
        // 이 시험이 보는 조회 창구가 바뀌었다. 판정 내용은 그대로다.
        //
        // ★ 인자와 무관하게 같은 값을 돌려주지 <않는다>. 그렇게 두면 판정기가 <엉뚱한 식별자>로
        //   물어도 시험이 통과해, 「요청한 것을 묻는가」가 검증되지 않는다. 요청 집합에 그 식별자가
        //   있을 때만 행을 돌려주도록 해 그 축을 시험이 실제로 물게 한다.
        when(statusRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> requested = inv.getArgument(0);
            for (Long id : requested) {
                if (id != null && id == RAW_SN) {
                    return List.of(status);
                }
            }
            return List.of();
        });
    }

    // ---------------- 자산 출처 ----------------

    @Test
    @DisplayName("검수_승인된_데이터마트_영상은_데이터마트_출처로_판정된다")
    void approvedVideoResolvesToDatamart() {
        givenFrame(datamartVideo());
        givenApproved(true);

        PortalWorkTargetResolver.Target target = resolver.resolveByFrame(SRC_SN, ALICE);

        assertThat(target.origin()).isEqualTo(Origin.DATAMART);
        assertThat(target.isUpload()).isFalse();
        assertThat(target.srcSn()).isEqualTo(SRC_SN);
    }

    /**
     * ★ 이 판정이 갈리면 저장처가 통째로 갈린다. 출처 판별자를 무시하고 전부 데이터마트로 읽으면
     * 본인 업로드 자산의 메타가 오버레이에만 쌓여 원장을 읽는 이후 경로가 그것을 못 본다.
     */
    @Test
    @DisplayName("★본인이_올린_영상은_업로드_출처로_판정된다")
    void ownUploadResolvesToUploadOrigin() {
        LsDataRaw raw = uploadVideo(ALICE);
        givenFrame(raw);

        PortalWorkTargetResolver.Target target = resolver.resolveByFrame(SRC_SN, ALICE);

        assertThat(target.origin()).isEqualTo(Origin.PORTAL_UPLOAD);
        assertThat(target.isUpload()).isTrue();
    }

    @Test
    @DisplayName("업로드_출처_판정에는_검수_승인_상태를_보지_않는다")
    void uploadOriginDoesNotConsultApproval() {
        givenFrame(uploadVideo(ALICE));
        // statusRepository 를 stub 하지 않았다 — 부르면 Optional.empty 라 403 이 됐을 것이다.

        assertThat(resolver.resolveByFrame(SRC_SN, ALICE).isUpload()).isTrue();
    }

    // ---------------- IDOR ----------------

    @Test
    @DisplayName("★남의_업로드_자산은_403이며_부재와_같은_문구다")
    void otherUsersUploadIsForbiddenWithIndistinguishableMessage() {
        givenFrame(uploadVideo(BOB));

        assertThatThrownBy(() -> resolver.resolveByFrame(SRC_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessage("본인 작업 대상이 아니거나 존재하지 않습니다.");
    }

    /**
     * ★ 남의 자산인지 미승인 영상인지 <b>본문으로 구분되면 안 된다</b> — 구분되면 응답이 실재 여부·
     * 상태를 알려주는 오라클이 된다(CWE-209).
     */
    @Test
    @DisplayName("★남의_자산_거부와_미승인_영상_거부의_문구가_완전히_같다")
    void forbiddenMessagesAreIdenticalAcrossReasons() {
        givenFrame(uploadVideo(BOB));
        String other = catchMessage(() -> resolver.resolveByFrame(SRC_SN, ALICE));

        givenFrame(datamartVideo());
        givenApproved(false);
        String unapproved = catchMessage(() -> resolver.resolveByFrame(SRC_SN, ALICE));

        assertThat(other).isEqualTo(unapproved);
    }

    @Test
    @DisplayName("데이터마트에_노출되지_않은_영상은_403이다")
    void unapprovedVideoIsForbidden() {
        givenFrame(datamartVideo());
        givenApproved(false);

        assertThatThrownBy(() -> resolver.resolveByFrame(SRC_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("작업_상태_행이_없는_영상도_403이다_fail_closed")
    void missingStatusRowIsForbidden() {
        givenFrame(datamartVideo());
        when(statusRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveByFrame(SRC_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    // ---------------- 진입 허용 일괄 판정 ----------------

    /**
     * ★ 일괄 창구가 <b>요청한 식별자에 대해서만</b> 답한다.
     *
     * <p>돌려받은 것을 그대로 「노출됨」으로 읽으면, 조회가 다른 행을 섞어 주는 순간 <b>남의 영상이
     * 승인이라는 이유로</b> 진입이 열린다. 목록은 이 집합으로 「이어서 작업」 링크를 그리므로 그 결과가
     * 화면까지 간다.
     */
    @Test
    @DisplayName("★일괄_판정은_요청하지_않은_식별자를_노출됨으로_돌려주지_않는다")
    void batchExposureAnswersOnlyForRequestedIds() {
        givenApproved(true);   // RAW_SN 만 승인 상태다

        assertThat(resolver.workableVideos(List.of(RAW_SN)))
                .as("요청한 것은 그대로 답한다").containsExactly(RAW_SN);
        assertThat(resolver.workableVideos(List.of(RAW_SN + 1)))
                .as("★요청하지 않은 식별자는 결과에 없다").isEmpty();
    }

    /** 빈 요청에는 조회를 태우지 않는다(빈 IN 절 회피) — 결과는 빈 집합이다. */
    @Test
    @DisplayName("빈_요청은_빈_집합이다")
    void emptyRequestYieldsEmptySet() {
        assertThat(resolver.workableVideos(List.of())).isEmpty();
        assertThat(resolver.workableVideos(null)).isEmpty();
    }

    // ---------------- 404 축 ----------------

    @Test
    @DisplayName("행_자체가_없으면_404다_소유와_무관한_사실이라_감출_것이_없다")
    void absentRowIsNotFound() {
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveByFrame(SRC_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);

        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolveByVideo(RAW_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("토큰_주체가_없으면_401이다")
    void missingOwnerIsUnauthorized() {
        assertThatThrownBy(() -> resolver.resolveByFrame(SRC_SN, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> resolver.resolveByVideo(RAW_SN, "  "))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("영상_축_진입은_프레임_참조가_비어있다")
    void videoScopedEntryHasNoFrameReference() {
        LsDataRaw raw = uploadVideo(ALICE);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));

        assertThat(resolver.resolveByVideo(RAW_SN, ALICE).srcSn()).isNull();
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("거부되지 않았다");
        } catch (CustomException e) {
            return e.getMessage();
        }
    }
}
