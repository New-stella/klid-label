package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.meta.service.DerivedMetaCopier;
import kr.co.cudo.authoring.video.service.ResolutionPersistService;
import kr.co.cudo.authoring.video.service.ResolutionSnapshot;
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

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C(재검증·영속) 단위 테스트 — 락-I/O 분리 리팩터의 <b>고유 게이트</b>를 결정적으로 검증한다.
 *
 * <p>통합 테스트({@code ResolutionDerivativeFlowIntegrationTest})는 A~C 를 러너로 관통하지만, Phase A 가
 * 부모 상태를 재스냅샷하므로 무잠금 I/O 창 이후에만 성립하는 Phase C 고유 게이트(H-1 stale 창)에는
 * 도달하지 못했다. 본 테스트는 스냅샷을 직접 주입해 Phase C 만의 재검증 분기를 커버한다:
 * ①부모 {@code deIdntfYn!='Y'} → CONFLICT ②stale 창(신고/경로 불일치) → CONFLICT
 * ③releaseReservedAug 가드 ④isAlreadyFinalized.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolutionPersistServiceTest {

    private static final long PARENT = 200L;
    private static final long NEW_RAW = 9100L;
    private static final long DATA_AUG = 42L;

    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataAugLblMapRepository lblMapRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock LsDeidentReportRepository deidentReportRepository;
    @Mock DerivedMetaCopier derivedMetaCopier;

    ResolutionPersistService service;

    @TempDir Path base;

    @BeforeEach
    void setup() {
        service = new ResolutionPersistService(videoRepository, srcRepository, lblRepository,
                lblMapRepository, augRepository, deidentProcLogRepository, deidentReportRepository,
                derivedMetaCopier);
        lenient().when(derivedMetaCopier.copyMetaAndReviews(anyLong(), anyLong()))
                .thenReturn(new DerivedMetaCopier.CopyResult(0, 0));
        ReflectionTestUtils.setField(service, "storageRawPath", base.toString());
    }

    /** 비식별 비디오 원본 경로 = base/videos/deid.mp4 (스냅샷 기준 경로). */
    private Path deidVideoSrc() {
        return base.resolve("videos/deid.mp4");
    }

    private ResolutionSnapshot snap(Instant capturedAt, Path deidVideoSrc) {
        return new ResolutionSnapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P,
                1920, 1080, 1280, 720, 1280d / 1920d, 720d / 1080d, "rev1",
                deidVideoSrc, base.resolve("resolution/" + NEW_RAW + "/video/RESL_720P.mp4"),
                capturedAt, List.of());
    }

    private LsDataRaw parentMock(String deIdntfYn) {
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn(deIdntfYn);
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        return parent;
    }

    private void stubStaleGatePasses() {
        // 신고 없음 + 최신 SUCCESS procLog 경로 == 스냅샷 경로 → stale 게이트 통과.
        when(deidentReportRepository.findAllByDataRawSnOrderByReportDtDesc(PARENT)).thenReturn(List.of());
        LsDeidentProcLog procLog = mock(LsDeidentProcLog.class);
        when(procLog.getDeIdntfFilePathNm()).thenReturn(deidVideoSrc().toString());
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(PARENT)).thenReturn(Optional.of(procLog));
    }

    @Test
    @DisplayName("부모가_비식별미완료(F)면_PII최종게이트에서_CONFLICT로_abort한다")
    void abortsWhenParentNotDeidentified() {
        parentMock("F");

        assertThatThrownBy(() -> service.persist(snap(Instant.now(), deidVideoSrc())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(videoRepository, never()).findById(any());
    }

    @Test
    @DisplayName("stale창_capturedAt이후_비식별신고가_있으면_CONFLICT로_abort한다(H-1_②신고게이트)")
    void abortsWhenReReportedAfterCapture() {
        Instant capturedAt = Instant.now().minusSeconds(60);
        parentMock("Y");
        LsDeidentReport report = mock(LsDeidentReport.class);
        when(report.getReportDt()).thenReturn(LocalDateTime.now()); // capturedAt 이후 신고
        when(deidentReportRepository.findAllByDataRawSnOrderByReportDtDesc(PARENT))
                .thenReturn(List.of(report));

        assertThatThrownBy(() -> service.persist(snap(capturedAt, deidVideoSrc())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        // 신고 게이트에서 막혀 CAS 재조회(newRaw)까지 가지 않는다.
        verify(videoRepository, never()).findByRawSnForUpdate(NEW_RAW);
    }

    @Test
    @DisplayName("stale창_최신비식별procLog경로가_스냅샷과_다르면_CONFLICT로_abort한다(H-1_①경로게이트)")
    void abortsWhenLatestDeidentPathChanged() {
        Instant capturedAt = Instant.now();
        parentMock("Y");
        when(deidentReportRepository.findAllByDataRawSnOrderByReportDtDesc(PARENT)).thenReturn(List.of());
        LsDeidentProcLog procLog = mock(LsDeidentProcLog.class);
        // 재비식별로 신규 경로 산출 — 스냅샷 경로와 불일치.
        when(procLog.getDeIdntfFilePathNm()).thenReturn(base.resolve("videos/RE-deid.mp4").toString());
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(PARENT)).thenReturn(Optional.of(procLog));

        assertThatThrownBy(() -> service.persist(snap(capturedAt, deidVideoSrc())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(videoRepository, never()).findByRawSnForUpdate(NEW_RAW);
    }

    @Test
    @DisplayName("stale게이트_통과시_오탐없이_진행하며_동시finalize승자가_이미확정(Y)이면_SKIPPED다")
    void staleGatePassesAndCasSkipsWhenWinnerAlreadyFinalized() {
        parentMock("Y");
        stubStaleGatePasses();
        LsDataRaw newRaw = mock(LsDataRaw.class);
        when(newRaw.getDeIdntfYn()).thenReturn("Y"); // 승자가 이미 확정 → 프레임 재삽입 없이 SKIPPED
        when(videoRepository.findByRawSnForUpdate(NEW_RAW)).thenReturn(Optional.of(newRaw));

        ResolutionPersistService.Result result = service.persist(snap(Instant.now(), deidVideoSrc()));

        assertThat(result).isEqualTo(ResolutionPersistService.Result.SKIPPED);
        verify(srcRepository, never()).save(any()); // 프레임 미삽입(승자 보호)
    }

    @Test
    @DisplayName("finalize성공_PERSISTED시_파생RAW를_markCompleted로_마감하고_markMarkingReady는_호출하지않는다")
    void persistMarksCompletedNotMarkingReady() {
        parentMock("Y");
        stubStaleGatePasses();
        LsDataRaw newRaw = mock(LsDataRaw.class);
        when(newRaw.getDeIdntfYn()).thenReturn("N"); // 아직 미확정 → 정상 확정 경로
        when(newRaw.getRawFilePathNm()).thenReturn(base.resolve("resolution/" + NEW_RAW + "/video/RESL_720P.mp4").toString());
        when(newRaw.getRawSn()).thenReturn(NEW_RAW);
        when(videoRepository.findByRawSnForUpdate(NEW_RAW)).thenReturn(Optional.of(newRaw));

        ResolutionPersistService.Result result = service.persist(snap(Instant.now(), deidVideoSrc()));

        assertThat(result).isEqualTo(ResolutionPersistService.Result.PERSISTED);
        // 배치 단계 상태 COMPLETED 로 마감(작업보드 노출) — MARKING_READY 로 두지 않는다.
        verify(newRaw).markCompleted();
        verify(newRaw, never()).markMarkingReady();
        verify(newRaw).markDeidentified("Y");
        // SUCCESS procLog 저장(비식별 결과 경로 기록).
        verify(deidentProcLogRepository).save(any(LsDeidentProcLog.class));
    }

    // ---------- isAlreadyFinalized ----------

    @Test
    @DisplayName("isAlreadyFinalized_null이면_false다")
    void isAlreadyFinalizedNull() {
        assertThat(service.isAlreadyFinalized(null)).isFalse();
    }

    @Test
    @DisplayName("isAlreadyFinalized_deIdntfYn_Y면_true_COMPLETED면_true_그외_false다")
    void isAlreadyFinalizedByStatus() {
        LsDataRaw yRaw = mock(LsDataRaw.class);
        when(yRaw.getDeIdntfYn()).thenReturn("Y");
        when(videoRepository.findByRawSnForUpdate(1L)).thenReturn(Optional.of(yRaw));

        LsDataRaw completed = mock(LsDataRaw.class);
        when(completed.getDeIdntfYn()).thenReturn("N");
        when(completed.getDataSttsCd()).thenReturn(LsDataRaw.DATA_STTS_COMPLETED);
        when(videoRepository.findByRawSnForUpdate(2L)).thenReturn(Optional.of(completed));

        LsDataRaw pending = mock(LsDataRaw.class);
        when(pending.getDeIdntfYn()).thenReturn("N");
        when(pending.getDataSttsCd()).thenReturn("PENDING");
        when(videoRepository.findByRawSnForUpdate(3L)).thenReturn(Optional.of(pending));

        when(videoRepository.findByRawSnForUpdate(4L)).thenReturn(Optional.empty());

        assertThat(service.isAlreadyFinalized(1L)).isTrue();
        assertThat(service.isAlreadyFinalized(2L)).isTrue();
        assertThat(service.isAlreadyFinalized(3L)).isFalse();
        assertThat(service.isAlreadyFinalized(4L)).isFalse();

        // M-1 회귀 못박기: 무잠금 findById 가 아닌 잠금(FOR UPDATE) 재조회로 승자 Phase C 와 직렬화됨을 검증.
        verify(videoRepository).findByRawSnForUpdate(1L);
        verify(videoRepository, never()).findById(anyLong());
    }

    // ---------- releaseReservedAug 가드 ----------

    @Test
    @DisplayName("releaseReservedAug_null이면_아무것도_하지않는다")
    void releaseReservedAugNull() {
        service.releaseReservedAug(null);
        verify(augRepository, never()).delete(any());
    }

    @Test
    @DisplayName("releaseReservedAug_라벨맵이_참조중이면_삭제하지않는다(승자참조_보호)")
    void releaseReservedAugSkipsWhenReferencedByLabelMap() {
        when(lblMapRepository.findAllByDataAugSn(DATA_AUG))
                .thenReturn(List.of(mock(LsDataAugLblMap.class)));

        service.releaseReservedAug(DATA_AUG);

        verify(augRepository, never()).delete(any());
        verify(augRepository, never()).findById(any());
    }

    @Test
    @DisplayName("releaseReservedAug_RESL_접두_예약행만_삭제한다")
    void releaseReservedAugDeletesReslPrefixed() {
        when(lblMapRepository.findAllByDataAugSn(DATA_AUG)).thenReturn(List.of());
        LsDataAug aug = mock(LsDataAug.class);
        when(aug.getAugTypeCd()).thenReturn(LsDataAug.AUG_RESL_720P);
        when(augRepository.findById(DATA_AUG)).thenReturn(Optional.of(aug));

        service.releaseReservedAug(DATA_AUG);

        verify(augRepository).delete(aug);
    }

    @Test
    @DisplayName("releaseReservedAug_RESL_접두가_아니면_삭제하지않는다(오배송_방어)")
    void releaseReservedAugSkipsNonResl() {
        when(lblMapRepository.findAllByDataAugSn(DATA_AUG)).thenReturn(List.of());
        LsDataAug aug = mock(LsDataAug.class);
        when(aug.getAugTypeCd()).thenReturn("WINTER");
        when(augRepository.findById(DATA_AUG)).thenReturn(Optional.of(aug));

        service.releaseReservedAug(DATA_AUG);

        verify(augRepository, never()).delete(any());
    }
}
