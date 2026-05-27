package kr.co.cudo.authoring.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.dto.MarkingResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarkingService 단위 테스트 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class MarkingServiceTest {

    @Mock
    private LsMarkingRepository markingRepository;

    @Mock
    private VideoRepository videoRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MarkingService markingService;

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private TokenClaims worker() {
        return new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private LsDataRaw stubRaw(Long rawSn, int durationSec) {
        return LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip_" + rawSn + ".mp4",
                LocalDateTime.now(), durationSec);
    }

    @Test
    @DisplayName("자동모드_마킹_생성_intervalSec_기반_marks_자동생성")
    void createAutoMode() {
        // given
        Long rawSn = 1L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 10, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then
        assertThat(result.markingMode()).isEqualTo("AUTO");
        assertThat(result.eventName()).isEqualTo("화재");
        assertThat(result.intervalSec()).isEqualTo(10);
        // 30초 / 10초 간격 = 0, 10, 20, 30 → 4개 마크
        assertThat(result.marks()).hasSize(4);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.status()).isEqualTo(LsMarking.STATUS_PENDING);
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("수동모드_마킹_생성_marks_배열_저장")
    void createManualMode() {
        // given
        Long rawSn = 2L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        List<MarkItem> marks = List.of(
                new MarkItem(10, "00:05"),
                new MarkItem(50, "00:10")
        );
        MarkingRequest req = new MarkingRequest("침입", "MANUAL", null, marks);

        // when
        MarkingResponse result = markingService.create(rawSn, req, worker());

        // then
        assertThat(result.markingMode()).isEqualTo("MANUAL");
        assertThat(result.eventName()).isEqualTo("침입");
        assertThat(result.intervalSec()).isNull();
        assertThat(result.marks()).hasSize(2);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(10);
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(50);
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("마킹_조회_정상_영상별_목록")
    void listMarkings() {
        // given
        Long rawSn = 1L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        LsMarking m1 = LsMarking.createAuto(rawSn, "화재", 5, "/path", "[]", 1L);
        LsMarking m2 = LsMarking.createManual(rawSn, "침입", "/path", "[]", 1L);
        when(markingRepository.findByRawSnOrderByCreatedAtDesc(rawSn)).thenReturn(List.of(m1, m2));

        // when
        List<MarkingResponse> result = markingService.list(rawSn);

        // then
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("마킹_단건_조회_정상")
    void getSingleMarking() {
        // given
        Long rawSn = 1L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(rawSn, "화재", 5, "/path", "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when
        MarkingResponse result = markingService.get(rawSn, markingSn);

        // then
        assertThat(result.eventName()).isEqualTo("화재");
        assertThat(result.rawSn()).isEqualTo(rawSn);
    }

    @Test
    @DisplayName("마킹_단건_조회_다른영상_마킹이면_FORBIDDEN")
    void getMarkingDifferentVideoForbidden() {
        // given
        Long rawSn = 1L;
        Long otherRawSn = 999L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(otherRawSn, "화재", 5, "/path", "[]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when / then
        assertThatThrownBy(() -> markingService.get(rawSn, markingSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("미존재_영상_마킹생성시_NOT_FOUND")
    void createNonExistentVideoNotFound() {
        // given
        Long rawSn = 999L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.empty());
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 5, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("미존재_마킹_조회시_NOT_FOUND")
    void getNonExistentMarkingNotFound() {
        // given
        Long rawSn = 1L;
        Long markingSn = 999L;
        when(markingRepository.findById(markingSn)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> markingService.get(rawSn, markingSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("마킹_삭제_정상")
    void deleteMarking() {
        // given
        Long rawSn = 1L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(rawSn, "화재", 5, "/path", "[]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when
        markingService.delete(rawSn, markingSn);

        // then
        verify(markingRepository).delete(marking);
    }

    @Test
    @DisplayName("마킹_삭제_다른영상_마킹이면_FORBIDDEN")
    void deleteMarkingDifferentVideoForbidden() {
        // given
        Long rawSn = 1L;
        Long otherRawSn = 999L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(otherRawSn, "화재", 5, "/path", "[]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when / then
        assertThatThrownBy(() -> markingService.delete(rawSn, markingSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("마킹_생성시_MarkingCompletedEvent_발행")
    void createPublishesMarkingCompletedEvent() {
        // given
        Long rawSn = 10L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 10, null);

        // when
        markingService.create(rawSn, req, reviewer());

        // then
        verify(eventPublisher).publishEvent(any(MarkingCompletedEvent.class));
    }
}
