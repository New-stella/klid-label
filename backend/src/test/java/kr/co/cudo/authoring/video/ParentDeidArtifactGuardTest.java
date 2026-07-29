package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.service.ParentDeidArtifactGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 부모 비식별 산출물 실재 게이트 단위 테스트 — 201 후 조용한 소멸 차단(적대검증 MEDIUM-1).
 *
 * <p>{@code 'F'} 는 ①비식별 누락 신고(산출물 존재)와 ②비식별 API 실패(산출물 부재)를 구분하지 못한다.
 * ②를 통과시키면 예약은 201 인데 async 확정이 반드시 실패하고 cleanup 이 흔적을 지운다. 이 게이트는
 * <b>요청 시점 동기</b>로 ②만 4xx 로 거른다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParentDeidArtifactGuardTest {

    @Mock LsDeidentProcLogRepository deidentProcLogRepository;

    @TempDir Path tempDir;

    private ParentDeidArtifactGuard guard;
    private Path deidBase;

    @BeforeEach
    void setup() throws IOException {
        deidBase = tempDir.resolve("deid");
        Files.createDirectories(deidBase);
        // artifactRootResolver=null → co-locate 판정 없이 비식별 저장소 서브트리 규약만으로 검증한다.
        guard = new ParentDeidArtifactGuard(deidentProcLogRepository, null);
        ReflectionTestUtils.setField(guard, "storageDeidentifiedPath", deidBase.toString());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LsDataRaw parent(long rawSn, String deIdntfYn) {
        LsDataRaw r = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/nas/raw/" + rawSn + ".mp4", null, 60);
        setField(r, "rawSn", rawSn);
        r.markDeidentified(deIdntfYn);
        return r;
    }

    /** 최신 SUCCESS 비식별 procLog 가 주어진 경로를 가리키도록 스텁. */
    private void givenProcLogPath(long rawSn, String path) {
        LsDeidentProcLog log = mock(LsDeidentProcLog.class);
        when(log.getDeIdntfFilePathNm()).thenReturn(path);
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.of(log));
    }

    @Test
    @DisplayName("신고F여도_비식별_산출물_파일이_실재하면_통과한다")
    void reportedButArtifactPresent_passes() throws IOException {
        // given: 'F'(신고) + videos/ 서브트리에 실제 비식별 영상 파일 존재
        Path video = deidBase.resolve("videos/10/deidentified.mp4");
        Files.createDirectories(video.getParent());
        Files.writeString(video, "x");
        givenProcLogPath(10L, video.toString());

        // when/then
        assertThatCode(() -> guard.requireParentDeidVideoPresent(parent(10L, "F")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("F가_비식별API실패인_경우_산출물_파일이_없어_동기_CONFLICT로_거부된다")
    void reportedButArtifactMissingOnDisk_rejected() {
        // given: procLog 경로는 적재돼 있으나 파일이 실재하지 않는다(비식별 API 실패 케이스).
        givenProcLogPath(11L, deidBase.resolve("videos/11/deidentified.mp4").toString());

        // when/then: 예약(201) 이전에 동기 4xx 로 거부.
        assertThatThrownBy(() -> guard.requireParentDeidVideoPresent(parent(11L, "F")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("SUCCESS_비식별_이력이_없으면_동기_CONFLICT로_거부된다")
    void noSuccessProcLog_rejected() {
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireParentDeidVideoPresent(parent(12L, "F")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("비식별_미수행_N_은_산출물_조회_이전에_CONFLICT로_거부된다")
    void notDeidentified_rejected() {
        assertThatThrownBy(() -> guard.requireParentDeidVideoPresent(parent(13L, "N")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("적재된_경로가_허용_비식별_저장경로_밖이면_거부된다_CWE22")
    void pathOutsideDeidSubtree_rejected() throws IOException {
        // given: 파일은 실재하지만 비식별 전용 서브트리(videos/**, frames/deid/**) 밖이다.
        Path outside = tempDir.resolve("elsewhere/leak.mp4");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "x");
        givenProcLogPath(14L, outside.toString());

        assertThatThrownBy(() -> guard.requireParentDeidVideoPresent(parent(14L, "Y")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
