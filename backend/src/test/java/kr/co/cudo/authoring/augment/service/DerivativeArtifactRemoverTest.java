package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파생 산출물 파일 삭제기의 <b>경로 방어</b> 테스트 (H6 · CWE-59/22/367) — 실 파일시스템 사용.
 *
 * <p>운영은 원본/비식별 저장소 base 가 같은 디렉터리({@code /nas-storage})라, 파생 디렉터리 안의
 * 심링크가 {@code frames/raw/**}(원본 프레임)를 가리켜도 문자열 접두 검사는 통과한다. "지워지는가"
 * 보다 <b>"원본이 살아남는가"</b> 를 고정한다.
 *
 * <p><b>결과는 3값이다</b>(FIX-3) — 심링크·비정규 항목은 여전히 지우지 않지만, 그 잔존을
 * "다음에 다시 해보면 될지도" 로 두지 않고 {@link DerivativeArtifactRemover.Outcome#UNRESOLVABLE}
 * (사람 개입 필요)로 <b>구분</b>한다. 구 테스트는 {@code complete=false} 만 단언해 <b>영원히 수렴하지
 * 않는 상태를 사양으로 못박고</b> 있었다.
 */
class DerivativeArtifactRemoverTest {

    private static final long PARENT = 7001L;
    private static final long DERIVATIVE = 7002L;

    private DerivativeArtifactRemover remover(Path base) {
        return new DerivativeArtifactRemover(base.toString());
    }

    private Path deidFrames(Path base, long rawSn) {
        return base.resolve("frames/deid/" + rawSn);
    }

    private Path rawFrames(Path base, long rawSn) {
        return base.resolve("frames/raw/" + rawSn);
    }

    private Path derivativeVideo(Path base, long parent, long derivative) {
        return base.resolve("videos/augment/" + parent + "/" + derivative + "/WINTER.mp4");
    }

    @Test
    @DisplayName("파생_프레임과_비디오만_삭제되고_원본_프레임은_남는다")
    void deletesOnlyDerivativeArtifacts(@TempDir Path base) throws IOException {
        // given
        Path frame = deidFrames(base, DERIVATIVE).resolve("frame-0.jpg");
        Files.createDirectories(frame.getParent());
        Files.writeString(frame, "d");
        Path original = rawFrames(base, PARENT).resolve("frame-0.jpg");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "o");
        Path video = derivativeVideo(base, PARENT, DERIVATIVE);
        Files.createDirectories(video.getParent());
        Files.writeString(video, "v");

        // when
        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE, video.toString());

        // then
        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.COMPLETE);
        assertThat(Files.exists(frame)).isFalse();
        assertThat(Files.exists(deidFrames(base, DERIVATIVE))).isFalse();
        assertThat(Files.exists(video)).isFalse();
        assertThat(Files.exists(original)).isTrue(); // 원본 프레임은 대상이 아니다
    }

    @Test
    @DisplayName("허용루트_밖_경로는_파일삭제를_수행하지_않는다")
    void symlinkToOriginalFrameIsNeverFollowed(@TempDir Path base) throws IOException {
        // given: 파생 프레임 디렉터리 안에 원본 프레임을 가리키는 심링크를 심는다
        Path original = rawFrames(base, PARENT).resolve("frame-0.jpg");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "original-pixels");
        Path derivativeDir = deidFrames(base, DERIVATIVE);
        Files.createDirectories(derivativeDir);
        Path link = derivativeDir.resolve("link.jpg");
        Files.createSymbolicLink(link, original);

        // when
        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE, null);

        // then: 원본은 온전히 살아있고, <재시도 대상이 아니라> 사람 개입 필요로 분류된다.
        //       (재시도로 분류하면 이 비석이 재시도 큐 앞자리를 영구 점유해 뒤의 정리를 전면 정지시킨다)
        assertThat(Files.exists(original)).isTrue();
        assertThat(Files.readString(original)).isEqualTo("original-pixels");
        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.UNRESOLVABLE);
    }

    @Test
    @DisplayName("파생_전용_위치가_아닌_비디오_경로는_삭제하지_않는다")
    void refusesVideoOutsideDerivativeLayout(@TempDir Path base) throws IOException {
        // given: 부모의 비식별 영상(videos/{rawSn}/…) — 파생 전용 규약이 아니다
        liveStorage(base); // 미완료 사유를 <비디오 경로 판정> 하나로 한정한다(FIX-B 축 배제)
        Path parentVideo = base.resolve("videos/" + PARENT + "/deidentified.mp4");
        Files.createDirectories(parentVideo.getParent());
        Files.writeString(parentVideo, "parent");

        // when: 파생 RAW 의 경로 값이 (오적재로) 부모 비식별본을 가리키는 상황
        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE, parentVideo.toString());

        // then: 지우지 않는다 + 재시도해도 같은 판정이므로 사람 개입 축
        assertThat(Files.exists(parentVideo)).isTrue();
        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.UNRESOLVABLE);
    }

    @Test
    @DisplayName("다른_파생의_비디오_경로는_삭제하지_않는다")
    void refusesVideoOfAnotherDerivative(@TempDir Path base) throws IOException {
        liveStorage(base); // 미완료 사유를 <비디오 경로 판정> 하나로 한정한다(FIX-B 축 배제)
        Path otherVideo = derivativeVideo(base, PARENT, 9999L);
        Files.createDirectories(otherVideo.getParent());
        Files.writeString(otherVideo, "other");

        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE, otherVideo.toString());

        assertThat(Files.exists(otherVideo)).isTrue();
        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.UNRESOLVABLE);
    }

    @Test
    @DisplayName("이미_없는_산출물은_저장소가_보이면_멱등하게_성공_처리된다")
    void missingArtifactsAreIdempotent(@TempDir Path base) throws IOException {
        // given: 정상 삭제 이후의 저장소 — 파생별 디렉터리만 사라지고 그 <부모>는 그대로 남아 있다
        //        (우리는 videos/{kind}/{parent}/{rawSn} 과 frames/deid/{rawSn} 까지만 지운다)
        liveStorage(base);

        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE,
                base.resolve("videos/augment/" + PARENT + "/" + DERIVATIVE + "/WINTER.mp4").toString());

        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.COMPLETE);
    }

    /**
     * ★ FIX-B — <b>마운트가 빠진 저장소를 "정리 완료" 로 닫지 않는다.</b>
     *
     * <p>마운트포인트 스텁은 디렉터리로는 존재하되 하위가 비어 있어 {@code base.toRealPath()} 는
     * 성공하고 대상 파일은 전부 "없음" 으로 관측된다. 구 구현은 그것을 멱등 성공으로 읽어 <b>아무것도
     * 지우지 않은 채</b> 비석에 파일 삭제 완료를 찍었다 — 마운트가 돌아오면 파일은 그대로인데 재시도
     * 큐에도 데드레터에도 없어 사람이 알 근거가 0이다(데드레터보다 나쁘다).
     */
    @Test
    @DisplayName("마운트가_빠진_저장소는_이미없음으로_단정하지_않고_재시도_대상이다")
    void unmountedStorageIsNotReportedAsCleaned(@TempDir Path base) {
        // given: base 는 디렉터리로 존재하지만(스텁) 하위 규약 디렉터리가 보이지 않는다
        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE,
                base.resolve("videos/augment/" + PARENT + "/" + DERIVATIVE + "/WINTER.mp4").toString());

        // then: "이미 지워졌다" 가 아니라 "확인하지 못했다" — 다음 tick 이 다시 본다
        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);
    }

    /**
     * ★ FIX-A + FIX-B (비디오 축 단독) — 이 축이 <b>단일 판정기를 통과하는지</b> 고정한다.
     *
     * <p>프레임 축은 정상(부모가 보이고 대상은 이미 없음 = 성공)이라 결과가 <b>비디오 축 판정만</b>
     * 반영한다. 구 구현처럼 비디오 축이 {@code MISSING} 을 무조건 성공으로 읽으면 여기서 깨진다.
     */
    @Test
    @DisplayName("비디오_부모_디렉터리만_사라지면_비디오_축이_재시도로_남는다")
    void videoAxisAloneKeepsRetryWhenItsParentIsGone(@TempDir Path base) throws IOException {
        // given: 프레임 축 부모(frames/deid)만 보이고 비디오 축 부모(videos)는 보이지 않는다
        Files.createDirectories(base.resolve("frames/deid"));

        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE,
                base.resolve("videos/augment/" + PARENT + "/" + DERIVATIVE + "/WINTER.mp4").toString());

        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);
    }

    /** ★ FIX-B — 두 축이 각자 자기 부모 위치로 판정한다(한 축만 보이면 나머지는 여전히 미확인). */
    @Test
    @DisplayName("프레임_부모_디렉터리만_사라지면_프레임_축이_재시도로_남는다")
    void frameAxisAloneKeepsRetryWhenItsParentIsGone(@TempDir Path base) throws IOException {
        // given: 비디오 축 부모(videos)만 보이고 프레임 축 부모(frames/deid)는 보이지 않는다
        Files.createDirectories(base.resolve("videos"));

        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE,
                base.resolve("videos/augment/" + PARENT + "/" + DERIVATIVE + "/WINTER.mp4").toString());

        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);
    }

    /**
     * ★ FIX-A — <b>한 원인은 두 축에서 같은 결과여야 한다.</b>
     *
     * <p>구 구현은 비디오 축이 {@code MISSING} 외 모든 판정을 {@code UNRESOLVABLE} 로 접어 넣어
     * <b>일시적</b> 실패인 {@code REALPATH_FAILED}(권한 순단·NFS ESTALE)까지 시도 1회 만에 데드레터로
     * 종결했는데, 프레임 축은 같은 {@code IOException} 을 {@code RETRYABLE} 로 봤다. 매핑을 한 표로
     * 모았으므로 여기서 표 전체를 못박는다(새 판정 코드가 추가되면 switch 가 컴파일 단계에서 잡는다).
     */
    @Test
    @DisplayName("판정코드_결과_매핑은_두_축이_같은_표를_쓴다")
    void verdictMappingIsShared() {
        // 일시적(환경이 나아지면 달라진다) → 재시도 축. I/O 예외도 같은 칸이다.
        assertThat(DerivativeArtifactRemover.classify(
                StorageSubtreePolicy.Verdict.REALPATH_FAILED, true))
                .isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);
        assertThat(DerivativeArtifactRemover.classifyIoFailure(true))
                .isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);
        assertThat(DerivativeArtifactRemover.classifyIoFailure(false))
                .isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);

        // 구조적(재시도해도 같다) → 사람 개입 축. 관측성과 무관하다.
        for (boolean observable : new boolean[]{true, false}) {
            for (StorageSubtreePolicy.Verdict verdict : List.of(
                    StorageSubtreePolicy.Verdict.BLANK,
                    StorageSubtreePolicy.Verdict.OUTSIDE_BASE,
                    StorageSubtreePolicy.Verdict.NOT_REGULAR_FILE,
                    StorageSubtreePolicy.Verdict.OUTSIDE_DEID_SUBTREE)) {
                assertThat(DerivativeArtifactRemover.classify(verdict, observable))
                        .as("%s (observable=%s)", verdict, observable)
                        .isEqualTo(DerivativeArtifactRemover.Outcome.UNRESOLVABLE);
            }
        }

        // 부재는 <저장소가 보일 때만> 성공이다(FIX-B).
        assertThat(DerivativeArtifactRemover.classify(StorageSubtreePolicy.Verdict.MISSING, true))
                .isEqualTo(DerivativeArtifactRemover.Outcome.COMPLETE);
        assertThat(DerivativeArtifactRemover.classify(StorageSubtreePolicy.Verdict.MISSING, false))
                .isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);
        assertThat(DerivativeArtifactRemover.classify(StorageSubtreePolicy.Verdict.OK, false))
                .isEqualTo(DerivativeArtifactRemover.Outcome.COMPLETE);
    }

    /** 정상 저장소(두 축의 부모 디렉터리가 보이는 상태). */
    private void liveStorage(Path base) throws IOException {
        Files.createDirectories(base.resolve("frames/deid"));
        Files.createDirectories(base.resolve("videos"));
    }

    @Test
    @DisplayName("프레임_디렉터리_자체가_심링크면_삭제하지_않는다")
    void refusesWhenFrameDirIsSymlink(@TempDir Path base) throws IOException {
        Path original = rawFrames(base, PARENT);
        Files.createDirectories(original);
        Files.writeString(original.resolve("frame-0.jpg"), "original-pixels");
        Files.createDirectories(base.resolve("frames/deid"));
        Files.createSymbolicLink(deidFrames(base, DERIVATIVE), original);

        DerivativeArtifactRemover.Outcome outcome = remover(base).remove(DERIVATIVE, null);

        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.UNRESOLVABLE);
        assertThat(Files.exists(original.resolve("frame-0.jpg"))).isTrue();
    }

    @Test
    @DisplayName("저장소_base가_없으면_재시도_대상으로_분류된다")
    void missingStorageBaseIsRetryable(@TempDir Path base) {
        // given: NAS 마운트 순단 재현 — base 자체가 존재하지 않는다(실경로 해석 실패)
        DerivativeArtifactRemover remover =
                new DerivativeArtifactRemover(base.resolve("not-mounted").toString());

        // when
        DerivativeArtifactRemover.Outcome outcome = remover.remove(DERIVATIVE, null);

        // then: 마운트가 돌아오면 풀리는 실패이므로 사람 개입이 아니라 재시도 축
        assertThat(outcome).isEqualTo(DerivativeArtifactRemover.Outcome.RETRYABLE);
    }
}
