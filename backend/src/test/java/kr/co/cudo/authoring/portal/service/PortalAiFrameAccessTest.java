package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.AiFrameAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 AI 입력 경계({@link PortalAiFrameAccess}) — 인가 · 출처별 입력 이미지 · 치수 · 차단 판정 없음.
 *
 * @design API-254, API-255, API-257
 */
class PortalAiFrameAccessTest {

    private static final String ALICE = "alice";

    @TempDir Path dir;

    private PortalWorkTargetResolver resolver;
    private PortalLabelService datamartFrames;
    private PortalUploadService uploadFrames;
    private AiFrameAccess access;

    @BeforeEach
    void setUp() {
        resolver = mock(PortalWorkTargetResolver.class);
        datamartFrames = mock(PortalLabelService.class);
        uploadFrames = mock(PortalUploadService.class);
        access = new PortalAiFrameAccessFactory(resolver, datamartFrames, uploadFrames)
                .forActor(new TokenClaims(ALICE, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600)));
    }

    private static LsDataSrc frame(long srcSn, long rawSn) {
        LsDataSrc f = LsDataSrc.create(rawSn, 0L, "/x/0.png", LocalDateTime.now());
        setField(f, "srcSn", srcSn);
        return f;
    }

    private void judged(LsDataSrc f, PortalWorkTargetResolver.Origin origin) {
        when(resolver.resolveFrame(eq(f.getSrcSn()), eq(ALICE))).thenReturn(new PortalWorkTargetResolver.FrameTarget(
                new PortalWorkTargetResolver.Target(f.getRawSn(), f.getSrcSn(), origin), f));
    }

    private Path png(String name, int w, int h) throws Exception {
        Path p = dir.resolve(name);
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    @Test
    @DisplayName("인가는_포털_작업_대상_판정에_행위자_subject로_위임하고_판정이_조회한_프레임을_돌려준다")
    void authorizeDelegatesToWorkTargetResolver() {
        LsDataSrc f = frame(10L, 1L);
        judged(f, PortalWorkTargetResolver.Origin.PORTAL_UPLOAD);

        assertThat(access.authorize(10L)).isSameAs(f);
        verify(resolver).resolveFrame(10L, ALICE);
    }

    @Test
    @DisplayName("인가_실패는_판정기의_코드_그대로_전파된다_403")
    void authorizeFailurePropagates() {
        when(resolver.resolveFrame(eq(11L), eq(ALICE)))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인 작업 대상이 아니거나 존재하지 않습니다."));

        assertThatThrownBy(() -> access.authorize(11L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("같은_요청에서_같은_프레임의_인가는_한_번만_조회한다")
    void sameFrameIsJudgedOnce() {
        LsDataSrc f = frame(12L, 1L);
        judged(f, PortalWorkTargetResolver.Origin.DATAMART);

        access.authorize(12L);
        access.authorize(12L);

        verify(resolver, times(1)).resolveFrame(12L, ALICE);
    }

    @Test
    @DisplayName("★본인_업로드_프레임은_업로드_서빙과_같은_함수로_입력_이미지를_해석한다")
    void uploadFrameUsesUploadServingResolution() throws Exception {
        LsDataSrc f = frame(13L, 2L);
        judged(f, PortalWorkTargetResolver.Origin.PORTAL_UPLOAD);
        Path img = png("u.png", 4, 3);
        when(uploadFrames.resolveUploadFrameFile(f)).thenReturn(img);

        assertThat(access.resolveImage(f)).isEqualTo(img);
        verify(datamartFrames, never()).resolveDatamartFrameFile(any());
    }

    @Test
    @DisplayName("★데이터마트_프레임은_데이터마트_서빙과_같은_함수로_입력_이미지를_해석한다")
    void datamartFrameUsesDatamartServingResolution() throws Exception {
        LsDataSrc f = frame(14L, 3L);
        judged(f, PortalWorkTargetResolver.Origin.DATAMART);
        Path img = png("d.png", 4, 3);
        when(datamartFrames.resolveDatamartFrameFile(f)).thenReturn(img);

        assertThat(access.resolveImage(f)).isEqualTo(img);
        verify(uploadFrames, never()).resolveUploadFrameFile(any());
    }

    @Test
    @DisplayName("★인가를_거치지_않은_프레임이_이미지_해석으로_오면_그_자리에서_인가부터_한다")
    void unauthorizedFrameIsJudgedBeforeImageResolution() {
        LsDataSrc f = frame(15L, 4L);
        when(resolver.resolveFrame(eq(15L), eq(ALICE)))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인 작업 대상이 아니거나 존재하지 않습니다."));

        assertThatThrownBy(() -> access.resolveImage(f)).isInstanceOf(CustomException.class);
        verify(uploadFrames, never()).resolveUploadFrameFile(any());
        verify(datamartFrames, never()).resolveDatamartFrameFile(any());
    }

    @Test
    @DisplayName("인코딩은_해석한_그_파일의_바이트다")
    void encodeReadsResolvedFile() throws Exception {
        LsDataSrc f = frame(16L, 5L);
        judged(f, PortalWorkTargetResolver.Origin.PORTAL_UPLOAD);
        Path img = png("e.png", 2, 2);
        when(uploadFrames.resolveUploadFrameFile(f)).thenReturn(img);

        assertThat(access.encodeImage(f)).isEqualTo(Base64.getEncoder().encodeToString(Files.readAllBytes(img)));
    }

    @Test
    @DisplayName("★인코딩은_링크를_따라가지_않는다_해석~읽기_사이_링크_교체는_404")
    void encodeDoesNotFollowSymlink() throws Exception {
        LsDataSrc f = frame(17L, 6L);
        judged(f, PortalWorkTargetResolver.Origin.PORTAL_UPLOAD);
        Path target = png("secret.png", 2, 2);
        Path link = Files.createSymbolicLink(dir.resolve("link.png"), target);
        when(uploadFrames.resolveUploadFrameFile(f)).thenReturn(link);

        assertThatThrownBy(() -> access.encodeImage(f))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("★좌표_상한은_추론_입력과_같은_파일의_실측_치수다")
    void boundsAreMeasuredFromTheSameFile() throws Exception {
        LsDataSrc f = frame(18L, 7L);
        judged(f, PortalWorkTargetResolver.Origin.DATAMART);
        Path img = png("b.png", 640, 360);
        when(datamartFrames.resolveDatamartFrameFile(f)).thenReturn(img);

        assertThat(access.resolveBounds(f)).hasValueSatisfying(b -> assertThat(b).containsExactly(640, 360));
    }

    @Test
    @DisplayName("치수를_잴_수_없으면_빈_값이다_상한_clamp만_생략")
    void unmeasurableBoundsAreEmpty() throws Exception {
        LsDataSrc f = frame(19L, 8L);
        judged(f, PortalWorkTargetResolver.Origin.PORTAL_UPLOAD);
        Path junk = Files.write(dir.resolve("junk.png"), new byte[]{1, 2, 3});
        when(uploadFrames.resolveUploadFrameFile(f)).thenReturn(junk);

        assertThat(access.resolveBounds(f)).isEmpty();
    }

    @Test
    @DisplayName("★차단_판정을_두지_않는다_비식별_신고_구간이어도_통과")
    void noBlockingCheck() {
        // 포털 AI 경로에는 비식별 누락 신고(412)·재비식별 작업락(409) 판정이 없다(2026-09-15 사용자 확정).
        // 이 입력 경계는 그 판정기(LabelAccessGuard·DeidentReportGate)를 협력자로 갖지 않는다.
        access.requireNotBlocked(1L);
        access.requireNotBlocked(null);
        assertThat(PortalAiFrameAccess.class.getDeclaredFields())
                .extracting(fld -> fld.getType().getSimpleName())
                .doesNotContain("LabelAccessGuard", "DeidentReportGate", "FrameImageEncoder", "FrameBoundsResolver");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException(name);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
