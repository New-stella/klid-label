package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 클립 1건 적재 트랜잭션 경계 빈({@link TrainingVideoIngestTx}) 단위 테스트.
 *
 * <p>이중 멱등(조회 skip + UK 충돌 skip), vmsClipId null/blank 가드, 파일경로 미해결 시
 * VideoIngestedEvent 발행 보류(HIGH-3)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrainingVideoIngestTxTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private TrainingVideoIngestTx tx;

    @BeforeEach
    void setUp() {
        tx = new TrainingVideoIngestTx(videoRepository, eventPublisher);
    }

    private MngClipMaster clip(long clipSn, String vmsClipId, String vmsCctvId) {
        MngClipMaster clip = newClip();
        ReflectionTestUtils.setField(clip, "clipSn", clipSn);
        ReflectionTestUtils.setField(clip, "vmsClipId", vmsClipId);
        ReflectionTestUtils.setField(clip, "vmsCctvId", vmsCctvId);
        ReflectionTestUtils.setField(clip, "jobDmndYn", "Y");
        ReflectionTestUtils.setField(clip, "regDt", LocalDateTime.of(2026, 6, 1, 10, 0));
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

    /** save() 가 rawSn 을 채운 영속 엔티티를 반환하도록 stub (IDENTITY 생성 모사). */
    private void stubSaveAssigningRawSn(long rawSn) {
        lenient().when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw raw = inv.getArgument(0);
            ReflectionTestUtils.setField(raw, "rawSn", rawSn);
            return raw;
        });
    }

    @Test
    @DisplayName("미적재_클립을_LS_DATA_RAW로_적재한다")
    void ingestsNewClip() {
        // given
        MngClipMaster clip = clip(1L, "VMS-CLIP-1", "CCTV-1");
        when(videoRepository.findByVmsClipId("VMS-CLIP-1")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(1000L);

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isTrue();
        ArgumentCaptor<LsDataRaw> captor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(captor.capture());
        LsDataRaw saved = captor.getValue();
        assertThat(saved.getVmsClipId()).isEqualTo("VMS-CLIP-1");
        assertThat(saved.getVmsCctvId()).isEqualTo("CCTV-1");
        assertThat(saved.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
    }

    @Test
    @DisplayName("이미_적재된_클립은_조회_skip으로_중복_적재하지_않는다")
    void skipsAlreadyIngestedClip() {
        // given — 동일 VMS_CLIP_ID 가 이미 존재.
        MngClipMaster clip = clip(1L, "VMS-CLIP-DUP", "CCTV-1");
        when(videoRepository.findByVmsClipId("VMS-CLIP-DUP"))
                .thenReturn(Optional.of(LsDataRaw.createFromIngest(
                        "VMS-CLIP-DUP", "CCTV-1", null, null, "ANONY", "/x", null, null)));

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("UK충돌_DataIntegrityViolationException은_중복_skip으로_처리된다")
    void treatsUniqueViolationAsDuplicateSkip() {
        // given — 조회 skip 을 통과한 뒤(동시 race) save 에서 UK 위반 발생.
        MngClipMaster clip = clip(1L, "VMS-CLIP-RACE", "CCTV-1");
        when(videoRepository.findByVmsClipId("VMS-CLIP-RACE")).thenReturn(Optional.empty());
        when(videoRepository.save(any(LsDataRaw.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key VMS_CLIP_ID"));

        // when — 예외를 던지지 않고 false 로 정상 skip.
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("vmsClipId가_null이면_적재하지_않고_skip한다")
    void skipsClipWithNullVmsClipId() {
        // given
        MngClipMaster clip = clip(1L, null, "CCTV-1");

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).findByVmsClipId(anyString());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("vmsClipId가_공백이면_적재하지_않고_skip한다")
    void skipsClipWithBlankVmsClipId() {
        // given
        MngClipMaster clip = clip(1L, "   ", "CCTV-1");

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("파일경로_미해결_PENDING_클립은_적재되나_VideoIngestedEvent를_발행하지_않는다")
    void doesNotPublishEventWhenFilePathUnresolved() {
        // given — 현 단계는 파일경로 컬럼이 없어 PENDING 플레이스홀더로 적재된다(HIGH-3).
        MngClipMaster clip = clip(1L, "VMS-CLIP-1", "CCTV-1");
        when(videoRepository.findByVmsClipId("VMS-CLIP-1")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(1000L);

        // when
        boolean ingested = tx.ingestOne(clip);

        // then — 적재는 되지만 비식별 트리거 이벤트는 보류.
        assertThat(ingested).isTrue();
        verify(videoRepository).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any(VideoIngestedEvent.class));
    }

    @Test
    @DisplayName("파일경로_정상_적재시_VideoIngestedEvent가_발행된다")
    void publishesEventWhenFilePathResolved() {
        // given — save 가 정상 파일경로로 영속된 엔티티를 반환(매핑 확정 후 시나리오 모사).
        MngClipMaster clip = clip(1L, "VMS-CLIP-1", "CCTV-1");
        when(videoRepository.findByVmsClipId("VMS-CLIP-1")).thenReturn(Optional.empty());
        lenient().when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw raw = inv.getArgument(0);
            ReflectionTestUtils.setField(raw, "rawSn", 1000L);
            ReflectionTestUtils.setField(raw, "rawFilePathNm", "/storage/raw/clip-1.mp4");
            return raw;
        });

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isTrue();
        ArgumentCaptor<VideoIngestedEvent> captor = ArgumentCaptor.forClass(VideoIngestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().rawSn()).isEqualTo(1000L);
    }
}
