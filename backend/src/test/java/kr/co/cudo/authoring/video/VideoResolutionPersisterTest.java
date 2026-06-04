package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.LsResolutionExport;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.service.VideoResolutionPersister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Persister 단위 테스트 — LS_RESOLUTION_EXPORT 1행만 INSERT, 라벨/메타/신규영상 미생성을 구조로 보장.
 */
@ExtendWith(MockitoExtension.class)
class VideoResolutionPersisterTest {

    @Mock LsResolutionExportRepository exportRepository;

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("persist_는_EXPORT_1행만_저장하고_원본_타겟_해상도_프레임수_반영")
    void persistSavesSingleExportRow() {
        VideoResolutionPersister persister = new VideoResolutionPersister(exportRepository);

        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-1", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "1.mp4", null, 60);
        setField(parent, "rawSn", 1L);

        when(exportRepository.save(any(LsResolutionExport.class))).thenAnswer(inv -> {
            LsResolutionExport e = inv.getArgument(0);
            setField(e, "resExportSn", 555L);
            return e;
        });

        ResolutionChangeResponse res = persister.persist(parent, ResolutionPreset.RES_720P,
                "/storage/raw/resolution/1/RES_720P", 1920, 1080, 1280, 720, 3, "reviewer-1");

        ArgumentCaptor<LsResolutionExport> cap = ArgumentCaptor.forClass(LsResolutionExport.class);
        verify(exportRepository).save(cap.capture());
        LsResolutionExport saved = cap.getValue();
        assertThat(saved.getDataRawSn()).isEqualTo(1L);
        assertThat(saved.getTargetResCd()).isEqualTo("RES_720P");
        assertThat(saved.getOrgnlW()).isEqualTo(1920);
        assertThat(saved.getOrgnlH()).isEqualTo(1080);
        assertThat(saved.getTargetW()).isEqualTo(1280);
        assertThat(saved.getTargetH()).isEqualTo(720);
        assertThat(saved.getFrameCnt()).isEqualTo(3);
        assertThat(saved.getRegId()).isEqualTo("reviewer-1");

        assertThat(res.exportSn()).isEqualTo(555L);
        assertThat(res.frameCount()).isEqualTo(3);
        assertThat(res.targetW()).isEqualTo(1280);
    }
}
