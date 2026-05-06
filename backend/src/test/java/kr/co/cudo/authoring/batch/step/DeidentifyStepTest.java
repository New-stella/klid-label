package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeidentifyStepTest {

    @TempDir
    Path tmp;

    private DeidentifyClient deidentifyClient;
    private LsDataSrcRepository srcRepository;
    private LsDataSrcHstryRepository hstryRepository;
    private VideoRepository videoRepository;
    private DeidentifyStep step;
    private Path baseDeid;

    @BeforeEach
    void setUp() throws Exception {
        deidentifyClient = mock(DeidentifyClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        hstryRepository = mock(LsDataSrcHstryRepository.class);
        videoRepository = mock(VideoRepository.class);

        baseDeid = tmp.resolve("deid");
        step = new DeidentifyStep(deidentifyClient, srcRepository, hstryRepository, videoRepository);
        setField(step, "deidPath", baseDeid.toString());
        invoke(step, "initBasePath");
    }

    private LsDataRaw newRaw(String prvc) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    private LsDataSrc newSrc(long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(9001L, frameNo, "/var/raw/frames/9001/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    @Test
    @DisplayName("정상_경로_응답이면_attachDeidPath_호출")
    void normalPath_attaches() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        LsDataSrc src = newSrc(1L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("frames").resolve("9001").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        step.run(raw);

        assertThat(src.getDeidFilePath()).isEqualTo(safeReturn.toString());
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("응답_resultPath가_baseDeidentifiedPath_밖이면_INVALID_INPUT_그리고_DE_IDNTF_YN_F_마킹")
    void escapingResultPath_rejected() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        LsDataSrc src = newSrc(2L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // 외부 응답이 base 밖 경로 (path traversal 시도)
        Path escaping = tmp.resolve("other").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", escaping.toString())));

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("EXTERNAL_API_ERROR"); // catch 블록에서 EXTERNAL_API_ERROR 로 래핑

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(hstryRepository, never()).save(any());
        assertThat(src.getDeidFilePath()).isNull();
    }

    @Test
    @DisplayName("ANONY_영상이면_skip_되어_비식별_API_미호출")
    void anonySkipped() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);
        step.run(raw);
        verify(deidentifyClient, never()).deidentify(any());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void invoke(Object target, String method) {
        try {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
