package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.LsResolutionExport;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.runner.AsyncResolutionRunner;
import kr.co.cudo.authoring.video.service.ResolutionReservationPersister;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생영상 예약/생성 트랜잭션 단위 테스트 — Phase 2.
 * 부모 PII 게이트 + UK 예약 + 새 RAW(PENDING) 생성 + AFTER_COMMIT 트리거를 검증한다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ResolutionReservationPersisterTest {

    @Mock VideoRepository videoRepository;
    @Mock LsResolutionExportRepository exportRepository;
    @Mock AsyncResolutionRunner asyncResolutionRunner;

    ResolutionReservationPersister persister;

    @BeforeEach
    void setup() {
        persister = new ResolutionReservationPersister(videoRepository, exportRepository, asyncResolutionRunner);
        ReflectionTestUtils.setField(persister, "storageRawPath", "/tmp/klid-res-test");
    }

    private LsDataRaw parent(Long rawSn, String deIdntfYn) {
        LsDataRaw p = LsDataRaw.createFromIngest("CLIP-" + rawSn, "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/tmp/klid-res-test/videos/orig.mp4", LocalDateTime.now(), 30);
        p.markDeidentified(deIdntfYn);
        ReflectionTestUtils.setField(p, "rawSn", rawSn);
        return p;
    }

    @Test
    @DisplayName("해상도변경시_새_RAW_SN이_생성되고_ORGNL_RAW_SN이_원본이며_PENDING이다")
    void createsPendingDerivativeRawWithOrgnlRawSn() {
        LsDataRaw parent = parent(200L, "Y");
        when(videoRepository.findByRawSnForUpdate(200L)).thenReturn(Optional.of(parent));
        when(exportRepository.save(any())).thenAnswer(inv -> {
            LsResolutionExport e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "resExportSn", 9L);
            return e;
        });
        when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "rawSn", 600L);
            return r;
        });

        ResolutionReservationPersister.Reservation reservation =
                persister.reserveAndCreate(parent, ResolutionPreset.RES_720P, 1920, 1080, 1280, 720, "rev1");

        assertThat(reservation.newRawSn()).isEqualTo(600L);
        assertThat(reservation.resExportSn()).isEqualTo(9L);

        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        LsDataRaw newRaw = rawCaptor.getValue();
        assertThat(newRaw.getOrgnlRawSn()).isEqualTo(200L);
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N"); // 확정 전까지 미완료
        assertThat(newRaw.getVmsClipId()).contains("_RES_RES_720P_");

        // AFTER_COMMIT 트리거 — 트랜잭션 동기화 미활성(단위)이면 직접 호출된다.
        verify(asyncResolutionRunner).runAsync(eq(600L), eq(200L), eq(9L), eq(ResolutionPreset.RES_720P));
    }

    @Test
    @DisplayName("부모가_비식별신고로_F전이되면_동기게이트에서_파생생성이_거부된다")
    void parentNotDeidentifiedBlocked() {
        LsDataRaw parent = parent(201L, "F"); // 비식별 신고로 되돌려짐
        when(videoRepository.findByRawSnForUpdate(201L)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> persister.reserveAndCreate(parent, ResolutionPreset.RES_720P, 1920, 1080, 1280, 720, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(exportRepository, never()).save(any());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncResolutionRunner, never()).runAsync(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("동일_parent_preset_예약이_UK위반이면_CONFLICT로_거부된다")
    void ukViolationConflict() {
        LsDataRaw parent = parent(202L, "Y");
        when(videoRepository.findByRawSnForUpdate(202L)).thenReturn(Optional.of(parent));
        when(exportRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk"));

        assertThatThrownBy(() -> persister.reserveAndCreate(parent, ResolutionPreset.RES_720P, 1920, 1080, 1280, 720, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncResolutionRunner, never()).runAsync(anyLong(), anyLong(), anyLong(), any());
    }
}
