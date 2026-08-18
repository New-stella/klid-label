package kr.co.cudo.authoring.batch.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프레임 추출 프로세스의 <b>대기 상한</b> 회귀 가드.
 *
 * <h3>왜 상한이 없으면 안 되나</h3>
 * <p>{@code Process#waitFor()}(무상한)로 기다리면 ffmpeg 이 멈췄을 때 호출 스레드가 영원히 붙잡힌다.
 * 그 스레드가 포털 프레임 추출이면 「무갱신 경과」 하트비트를 <b>한 번도 치지 못해</b>, 멈춘 처리와
 * 정상 처리를 구분하지 못한 채 자산이 방치로 판정되고 실패 보존기간 뒤 비가역 삭제된다
 * ({@code PortalFrameExtractRunner} 의 「남은 창」 — 하트비트는 프레임 «사이»에서만 칠 수 있으므로
 * 한 장의 비용에 상한이 없으면 그 창도 무한하다).
 *
 * <p>여기서는 OS·ffmpeg 설치에 의존하지 않도록 대기 seam({@code awaitExit})을 가짜 {@link Process} 로
 * 검증한다 — 조건부 건너뛰기(테스트가 통과처럼 보이는 형태)를 만들지 않기 위해서다.
 */
class BrampFfmpegFrameWriterTimeoutTest {

    /** 지정한 시간 안에 끝나지 않는(또는 끝나는) 가짜 프로세스. */
    private static final class FakeProcess extends Process {
        private final boolean exitsInTime;
        private final AtomicBoolean destroyed = new AtomicBoolean(false);

        private FakeProcess(boolean exitsInTime) {
            this.exitsInTime = exitsInTime;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return exitsInTime;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed.set(true); }
    }

    @Test
    @DisplayName("대기_상한을_넘긴_프레임_추출_프로세스는_강제_종료되고_실패로_보고된다")
    void timedOutProcessIsKilledAndReported() throws Exception {
        FakeProcess stuck = new FakeProcess(false);

        boolean exited = BrampFfmpegFrameWriter.awaitExit(stuck, 1L);

        assertThat(exited)
                .as("상한을 넘겼는데 true 를 돌려주면 호출부가 정상 종료로 오인한다")
                .isFalse();
        assertThat(stuck.destroyed)
                .as("상한을 넘긴 프로세스를 남겨 두면 좀비가 되어 자원을 계속 잡는다")
                .isTrue();
    }

    @Test
    @DisplayName("상한_안에_끝난_프로세스는_강제_종료되지_않는다")
    void normalProcessIsNotKilled() throws Exception {
        FakeProcess quick = new FakeProcess(true);

        assertThat(BrampFfmpegFrameWriter.awaitExit(quick, 1L)).isTrue();
        assertThat(quick.destroyed).isFalse();
    }

    @Test
    @DisplayName("대기_상한_설정이_0이하면_기본값으로_폴백해_무한_대기로_두지_않는다")
    void invalidTimeoutFallsBackToDefault() {
        // 이 값은 <보호 장치>라 이상하다고 꺼 버리면 막으려던 위험이 그대로 열린다.
        // (「이상하면 그 회차를 건너뛴다」는 고착 스윕 규칙과 방향이 반대인 것은 의도 —
        //  그쪽은 폴백이 곧 데이터 손실이고, 이쪽은 폴백이 곧 안전이다.)
        assertThat(new BrampFfmpegFrameWriter("ffmpeg", 0L).frameTimeoutSec())
                .isEqualTo(BrampFfmpegFrameWriter.DEFAULT_FRAME_TIMEOUT_SEC);
        assertThat(new BrampFfmpegFrameWriter("ffmpeg", -1L).frameTimeoutSec())
                .isEqualTo(BrampFfmpegFrameWriter.DEFAULT_FRAME_TIMEOUT_SEC);
        assertThat(new BrampFfmpegFrameWriter("ffmpeg", 42L).frameTimeoutSec()).isEqualTo(42L);
    }
}
