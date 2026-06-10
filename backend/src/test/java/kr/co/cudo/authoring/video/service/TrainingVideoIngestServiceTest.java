package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관제 학습용 영상 픽업 스캔 서비스 단위 테스트.
 *
 * <p>{@link TrainingVideoIngestService#scanAndIngest()} 는 조회(READ)만 담당하고, 클립별 적재는
 * {@link TrainingVideoIngestTx#ingestOne}(REQUIRES_NEW)에 위임한다. 본 테스트는 스캔 조율/부분
 * 실패 격리(한 클립 트랜잭션 롤백이 다른 클립 적재를 막지 않음)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrainingVideoIngestServiceTest {

    @Mock
    private MngClipMasterRepository clipMasterRepository;

    @Mock
    private TrainingVideoIngestTx ingestTx;

    private TrainingVideoIngestService service;

    @BeforeEach
    void setUp() {
        service = new TrainingVideoIngestService(clipMasterRepository, ingestTx);
    }

    private MngClipMaster clip(String evntId, String clipId) {
        MngClipMaster clip = newClip();
        ReflectionTestUtils.setField(clip, "evntId", evntId);
        ReflectionTestUtils.setField(clip, "clipTypeCd", "ORIGINAL");
        ReflectionTestUtils.setField(clip, "clipId", clipId);
        ReflectionTestUtils.setField(clip, "vmsCctvId", "CCTV-" + evntId);
        ReflectionTestUtils.setField(clip, "filePath", "/nas-storage/data/clip/" + clipId + ".mp4");
        ReflectionTestUtils.setField(clip, "jobDmndYn", "Y");
        ReflectionTestUtils.setField(clip, "crtDt", LocalDateTime.of(2026, 6, 1, 10, 0));
        return clip;
    }

    private static MngClipMaster newClip() {
        try {
            var ctor = MngClipMaster.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("관제_작업수요_설정_클립을_픽업해_적재_위임한다")
    void delegatesIngestForTrainingDesignatedClip() {
        // given
        MngClipMaster clip = clip("EVT-1", "CLIP-1");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn("Y")).thenReturn(List.of(clip));
        when(ingestTx.ingestOne(clip)).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isEqualTo(1);
        verify(ingestTx).ingestOne(clip);
    }

    @Test
    @DisplayName("위임_적재가_skip되면_적재건수에_포함되지_않는다")
    void skippedClipNotCounted() {
        // given — 중복/스킵 클립은 ingestOne 이 false 반환.
        MngClipMaster clip = clip("EVT-DUP", "CLIP-DUP");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn("Y")).thenReturn(List.of(clip));
        when(ingestTx.ingestOne(clip)).thenReturn(false);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
    }

    @Test
    @DisplayName("작업수요_미설정_클립은_조회되지_않아_위임하지_않는다")
    void doesNotDelegateForNonTrainingClips() {
        // given — repository 가 'Y' 만 조회하므로 빈 결과.
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn("Y")).thenReturn(List.of());

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
        verify(ingestTx, never()).ingestOne(any(MngClipMaster.class));
    }

    @Test
    @DisplayName("한_클립_REQUIRES_NEW_트랜잭션_예외가_다른_클립_적재를_막지_않는다")
    void partialFailureDoesNotBlockOtherClips() {
        // given — 첫 클립 적재 트랜잭션이 JPA 예외로 롤백돼도(REQUIRES_NEW 격리),
        // 둘째 클립 적재 커밋은 영향받지 않아야 한다.
        MngClipMaster bad = clip("EVT-BAD", "CLIP-BAD");
        MngClipMaster good = clip("EVT-GOOD", "CLIP-GOOD");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn("Y")).thenReturn(List.of(bad, good));
        when(ingestTx.ingestOne(bad))
                .thenThrow(new DataIntegrityViolationException("rollback-only marked tx"));
        when(ingestTx.ingestOne(good)).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then — 둘째 클립은 적재 성공으로 집계.
        assertThat(ingested).isEqualTo(1);
        verify(ingestTx).ingestOne(good);
    }

    @Test
    @DisplayName("여러_클립_중_일부_skip_일부_적재가_정확히_집계된다")
    void mixedResultsCountedCorrectly() {
        // given
        MngClipMaster a = clip("EVT-A", "CLIP-A");
        MngClipMaster b = clip("EVT-B", "CLIP-B");
        MngClipMaster c = clip("EVT-C", "CLIP-C");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn("Y")).thenReturn(List.of(a, b, c));
        when(ingestTx.ingestOne(a)).thenReturn(true);
        when(ingestTx.ingestOne(b)).thenReturn(false);
        when(ingestTx.ingestOne(c)).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isEqualTo(2);
        verify(ingestTx, times(3)).ingestOne(any(MngClipMaster.class));
    }

    @Test
    @DisplayName("스캔결과가_null이면_안전하게_0건_처리한다")
    void nullScanResultIsHandledSafely() {
        // given — repository 가 null 을 반환해도 NPE 없이 0 건 처리.
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn("Y")).thenReturn(null);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
        verify(ingestTx, never()).ingestOne(any(MngClipMaster.class));
    }
}
