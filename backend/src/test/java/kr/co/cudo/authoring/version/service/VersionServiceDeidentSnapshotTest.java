package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 (R1 v1.14) — VersionService.snapshotDeidentReport 단위 테스트 (Mockito).
 *
 * <p>비식별 신고 시 영상 전체 라벨을 ACTIVE_YN='N', SAVE_REASON='DEIDENT_REPORT' 로 적재하고,
 * 라벨 0건이면 스냅샷을 만들지 않는지 검증한다.
 */
class VersionServiceDeidentSnapshotTest {

    private LsLabelVersionRepository labelVersionRepository;
    private LabelAccessGuard accessGuard;
    private VideoRepository videoRepository;
    private WorkLockService workLockService;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository labelRepository;
    private VersionService service;

    private TokenClaims actor;

    @BeforeEach
    void setUp() {
        labelVersionRepository = mock(LsLabelVersionRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        videoRepository = mock(VideoRepository.class);
        workLockService = mock(WorkLockService.class);
        srcRepository = mock(LsDataSrcRepository.class);
        labelRepository = mock(LsDataLblRepository.class);
        service = new VersionService(labelVersionRepository, accessGuard, videoRepository,
                workLockService, srcRepository, labelRepository, new ObjectMapper());
        actor = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private LsDataLbl lbl(long lblSn, long srcSn) {
        LsDataLbl l = LsDataLbl.createManual(srcSn, "BBOX", null, "person", "[[0,0],[1,1]]", 100L);
        setField(l, "lblSn", lblSn);
        return l;
    }

    @Test
    @DisplayName("라벨_존재시_ACTIVE_N_DEIDENT_REPORT_스냅샷_저장_+_true")
    void snapshotSavesInactiveDeidentReportVersion() {
        when(labelRepository.findAllByRawSn(5000L)).thenReturn(List.of(lbl(1L, 10L), lbl(2L, 10L)));
        when(labelVersionRepository.findFirstByDataRawSnOrderByVersionNoDesc(5000L))
                .thenReturn(Optional.empty());

        boolean result = service.snapshotDeidentReport(5000L, actor);

        assertThat(result).isTrue();
        ArgumentCaptor<LsLabelVersion> cap = ArgumentCaptor.forClass(LsLabelVersion.class);
        verify(labelVersionRepository).save(cap.capture());
        LsLabelVersion saved = cap.getValue();
        assertThat(saved.getDataRawSn()).isEqualTo(5000L);
        assertThat(saved.getDataSrcSn()).isNull();
        assertThat(saved.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_NO);
        assertThat(saved.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_DEIDENT_REPORT);
        assertThat(saved.getVersionHash()).isNotBlank();
        assertThat(saved.getLabelPayload()).isNotBlank();
        assertThat(saved.getVersionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("라벨_0건이면_스냅샷_미생성_+_false")
    void snapshotSkipsWhenNoLabels() {
        when(labelRepository.findAllByRawSn(5001L)).thenReturn(List.of());

        boolean result = service.snapshotDeidentReport(5001L, actor);

        assertThat(result).isFalse();
        verify(labelVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("기존_버전_있으면_versionNo_증가")
    void snapshotIncrementsVersionNo() {
        LsLabelVersion prev = LsLabelVersion.createInactiveRawSnapshot(
                5002L, "h", "p", 7, LsLabelVersion.SAVE_REASON_DEIDENT_REPORT, "x");
        when(labelRepository.findAllByRawSn(5002L)).thenReturn(List.of(lbl(3L, 11L)));
        when(labelVersionRepository.findFirstByDataRawSnOrderByVersionNoDesc(5002L))
                .thenReturn(Optional.of(prev));

        service.snapshotDeidentReport(5002L, actor);

        ArgumentCaptor<LsLabelVersion> cap = ArgumentCaptor.forClass(LsLabelVersion.class);
        verify(labelVersionRepository).save(cap.capture());
        assertThat(cap.getValue().getVersionNo()).isEqualTo(8);
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
}
