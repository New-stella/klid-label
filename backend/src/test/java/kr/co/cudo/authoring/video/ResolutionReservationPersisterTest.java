package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생영상 예약/생성 트랜잭션 단위 테스트 — Phase 1 (증강 저장모델 통합).
 * 부모 PII 게이트 + LS_DATA_AUG(RES_*) PENDING 예약(생성 라이프사이클 정합) + 새 RAW(PENDING) 생성
 * + AFTER_COMMIT 트리거를 검증한다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ResolutionReservationPersisterTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock AsyncResolutionRunner asyncResolutionRunner;

    ResolutionReservationPersister persister;

    @BeforeEach
    void setup() {
        persister = new ResolutionReservationPersister(videoRepository, augRepository, asyncResolutionRunner);
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
        when(augRepository.save(any())).thenAnswer(inv -> {
            LsDataAug a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "dataAugSn", 9L);
            return a;
        });
        when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "rawSn", 600L);
            return r;
        });

        ResolutionReservationPersister.Reservation reservation =
                persister.reserveAndCreate(parent, ResolutionPreset.RESL_720P, 42L, "rev1");

        assertThat(reservation.newRawSn()).isEqualTo(600L);
        assertThat(reservation.dataAugSn()).isEqualTo(9L);

        // LS_DATA_AUG 예약행 — SRC_SN=대표프레임(#3), AUG_TYPE_CD=RESL_720P, PENDING(생성 중), IDMP/OTSD null.
        // 예약 시점엔 파생 RAW 가 아직 PENDING 이므로 aug 도 PENDING — finalize 성공 시에만 ACCEPTED 로 전이한다.
        ArgumentCaptor<LsDataAug> augCaptor = ArgumentCaptor.forClass(LsDataAug.class);
        verify(augRepository).save(augCaptor.capture());
        LsDataAug aug = augCaptor.getValue();
        assertThat(aug.getSrcSn()).isEqualTo(42L);
        assertThat(aug.getAugTypeCd()).isEqualTo("RESL_720P");
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(aug.getIdempotencyKey()).isNull();
        assertThat(aug.getExternalJobId()).isNull();
        assertThat(aug.getRetryCount()).isZero();

        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        LsDataRaw newRaw = rawCaptor.getValue();
        assertThat(newRaw.getOrgnlRawSn()).isEqualTo(200L);
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N"); // 확정 전까지 미완료
        assertThat(newRaw.getVmsClipId()).contains("_RESL_RESL_720P_");

        // AFTER_COMMIT 트리거 — 트랜잭션 동기화 미활성(단위)이면 직접 호출된다.
        verify(asyncResolutionRunner).runAsync(eq(600L), eq(200L), eq(9L), eq(ResolutionPreset.RESL_720P));
    }

    @Test
    @DisplayName("srcSn_null이면_LS_DATA_AUG_INSERT전에_INVALID_INPUT을_던진다")
    void nullSrcSnRejectedBeforeInsert() {
        LsDataRaw parent = parent(205L, "Y");
        when(videoRepository.findByRawSnForUpdate(205L)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> persister.reserveAndCreate(parent, ResolutionPreset.RESL_720P, null, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(augRepository, never()).save(any());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncResolutionRunner, never()).runAsync(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("부모가_비식별신고로_F전이되면_동기게이트에서_파생생성이_거부된다")
    void parentNotDeidentifiedBlocked() {
        LsDataRaw parent = parent(201L, "F"); // 비식별 신고로 되돌려짐
        when(videoRepository.findByRawSnForUpdate(201L)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> persister.reserveAndCreate(parent, ResolutionPreset.RESL_720P, 42L, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(augRepository, never()).save(any());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncResolutionRunner, never()).runAsync(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("동일_parent_preset_예약이_UK위반이면_CONFLICT로_거부된다")
    void ukViolationConflict() {
        LsDataRaw parent = parent(202L, "Y");
        when(videoRepository.findByRawSnForUpdate(202L)).thenReturn(Optional.of(parent));
        when(augRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk"));

        assertThatThrownBy(() -> persister.reserveAndCreate(parent, ResolutionPreset.RESL_720P, 42L, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncResolutionRunner, never()).runAsync(anyLong(), anyLong(), anyLong(), any());
    }
}
