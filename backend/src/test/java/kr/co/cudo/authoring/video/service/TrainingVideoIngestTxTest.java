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
 * <p>관제 실제 스키마 정합 후: CLIP_ID 멱등키 + FILE_PATH(NAS 절대경로) 적재 + FILE_PATH 비공백
 * 가드 + 가드 제거 후 VideoIngestedEvent 정상 발행(이중 멱등: 조회 skip + UK 충돌 skip)을 검증한다.
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

    /** 관제 실제 스키마 기반 클립 생성 — 복합키(EVNT_ID, CLIP_TYPE_CD) + CLIP_ID/FILE_PATH 등. */
    private MngClipMaster clip(String evntId, String clipId, String filePath) {
        MngClipMaster clip = newClip();
        ReflectionTestUtils.setField(clip, "evntId", evntId);
        ReflectionTestUtils.setField(clip, "clipTypeCd", "ORIGINAL");
        ReflectionTestUtils.setField(clip, "clipId", clipId);
        ReflectionTestUtils.setField(clip, "vmsCctvId", "CCTV-" + evntId);
        ReflectionTestUtils.setField(clip, "lclgvCd", "11110");
        ReflectionTestUtils.setField(clip, "filePath", filePath);
        ReflectionTestUtils.setField(clip, "fileFmt", "mp4");
        ReflectionTestUtils.setField(clip, "vdoLenSec", 602000);
        ReflectionTestUtils.setField(clip, "clipSttsCd", "mediainfo_complete");
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

    /** save() 가 rawSn 을 채운 영속 엔티티를 반환하도록 stub (IDENTITY 생성 모사). */
    private void stubSaveAssigningRawSn(long rawSn) {
        lenient().when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw raw = inv.getArgument(0);
            ReflectionTestUtils.setField(raw, "rawSn", rawSn);
            return raw;
        });
    }

    @Test
    @DisplayName("관제_작업요청_클립을_FILE_PATH로_적재한다")
    void ingestsClipWithRealFilePath() {
        // given — 실제 NAS 절대경로 FILE_PATH 를 가진 작업요청 클립.
        String filePath = "/nas-storage/data/clip/gov/preview/uuid-1/clip-1.mp4";
        MngClipMaster clip = clip("EVT-1", "CLIP-UUID-1", filePath);
        when(videoRepository.findByVmsClipId("CLIP-UUID-1")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(1000L);

        // when
        boolean ingested = tx.ingestOne(clip);

        // then — CLIP_ID 가 멱등키(VMS_CLIP_ID), FILE_PATH 가 rawFilePathNm 으로 적재.
        assertThat(ingested).isTrue();
        ArgumentCaptor<LsDataRaw> captor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(captor.capture());
        LsDataRaw saved = captor.getValue();
        assertThat(saved.getVmsClipId()).isEqualTo("CLIP-UUID-1");
        assertThat(saved.getVmsCctvId()).isEqualTo("CCTV-EVT-1");
        assertThat(saved.getLclgvCd()).isEqualTo("11110");
        assertThat(saved.getRawFilePathNm()).isEqualTo(filePath);
        assertThat(saved.getDurationSec()).isEqualTo(602000);
        assertThat(saved.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
    }

    @Test
    @DisplayName("적재시_VideoIngestedEvent가_발행된다")
    void publishesEventOnIngest() {
        // given — 가드 제거 후 정상 FILE_PATH 면 비식별 선두 트리거 이벤트가 발행된다.
        String filePath = "/nas-storage/data/clip/gov/preview/uuid-2/clip-2.mp4";
        MngClipMaster clip = clip("EVT-2", "CLIP-UUID-2", filePath);
        when(videoRepository.findByVmsClipId("CLIP-UUID-2")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(2000L);

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isTrue();
        ArgumentCaptor<VideoIngestedEvent> captor = ArgumentCaptor.forClass(VideoIngestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().rawSn()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("이미_적재된_클립은_CLIP_ID기준_중복_적재하지_않는다")
    void skipsAlreadyIngestedClipByClipId() {
        // given — 동일 CLIP_ID(VMS_CLIP_ID) 가 이미 존재.
        MngClipMaster clip = clip("EVT-DUP", "CLIP-UUID-DUP", "/nas/x.mp4");
        when(videoRepository.findByVmsClipId("CLIP-UUID-DUP"))
                .thenReturn(Optional.of(LsDataRaw.createFromIngest(
                        "CLIP-UUID-DUP", "CCTV-X", null, null, "ANONY", "/nas/x.mp4", null, null)));

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
        MngClipMaster clip = clip("EVT-RACE", "CLIP-UUID-RACE", "/nas/race.mp4");
        when(videoRepository.findByVmsClipId("CLIP-UUID-RACE")).thenReturn(Optional.empty());
        when(videoRepository.save(any(LsDataRaw.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key VMS_CLIP_ID"));

        // when — 예외를 던지지 않고 false 로 정상 skip.
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("CLIP_ID가_null이면_적재하지_않고_skip한다")
    void skipsClipWithNullClipId() {
        // given
        MngClipMaster clip = clip("EVT-NULL", null, "/nas/n.mp4");

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).findByVmsClipId(anyString());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("vmsCctvId가_null이면_적재하지_않고_skip한다")
    void skipsClipWithNullVmsCctvId() {
        // given — 관제는 VMS_CCTV_ID nullable 이나 LS_DATA_RAW.VMS_CCTV_ID 는 NOT NULL.
        //         가드 없으면 save 시 DataIntegrityViolationException 이 중복 race 로 오인됨.
        MngClipMaster clip = clip("EVT-NULLCCTV", "CLIP-UUID-NULLCCTV", "/nas/c.mp4");
        ReflectionTestUtils.setField(clip, "vmsCctvId", null);

        // when
        boolean ingested = tx.ingestOne(clip);

        // then — findByVmsClipId 도 호출하지 않고 사전 skip(중복 race 와 구분).
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).findByVmsClipId(anyString());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("vmsCctvId가_공백이면_적재하지_않고_skip한다")
    void skipsClipWithBlankVmsCctvId() {
        // given — VMS_CCTV_ID 가 공백 문자열인 경우도 NOT NULL 제약 전에 사전 skip.
        MngClipMaster clip = clip("EVT-BLANKCCTV", "CLIP-UUID-BLANKCCTV", "/nas/c.mp4");
        ReflectionTestUtils.setField(clip, "vmsCctvId", "   ");

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("FILE_PATH가_없는_클립은_적재하지_않고_skip한다")
    void skipsClipWithBlankFilePath() {
        // given — FILE_PATH 가 공백이면 깨진 적재 방지를 위해 skip(WARN).
        MngClipMaster clip = clip("EVT-NOPATH", "CLIP-UUID-NOPATH", "   ");

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("FILE_PATH가_null인_클립은_적재하지_않고_skip한다")
    void skipsClipWithNullFilePath() {
        // given
        MngClipMaster clip = clip("EVT-NULLPATH", "CLIP-UUID-NULLPATH", null);

        // when
        boolean ingested = tx.ingestOne(clip);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }
}
