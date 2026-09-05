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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        resolver = new PortalWorkTargetResolver(srcRepository, videoRepository, statusRepository);
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
        when(status.getDataSttsCd())
                .thenReturn(approved ? LsRawDataStatus.STTS_APPROVED : LsRawDataStatus.STTS_PENDING);
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(status));
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
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveByFrame(SRC_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
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
