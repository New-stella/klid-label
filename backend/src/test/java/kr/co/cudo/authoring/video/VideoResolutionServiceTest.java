package kr.co.cudo.authoring.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LabelCoordinateScaler;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoResolutionPersister;
import kr.co.cudo.authoring.video.service.VideoResolutionService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoResizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 변경 서비스 단위 테스트 — VideoProbe/VideoResizer 는 stub/mock 주입(바이너리 비의존).
 * 실제 DB 트랜잭션 프록시는 단위 테스트 범위 외이므로 Persister 도 mock 으로 동작 검증한다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class VideoResolutionServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock LsRawDataStatusRepository statusRepository;
    @Mock VideoProbe videoProbe;
    @Mock VideoResizer videoResizer;
    @Mock VideoResolutionPersister persister;

    VideoResolutionService service;

    @BeforeEach
    void setup() {
        service = new VideoResolutionService(videoRepository, statusRepository, videoProbe, videoResizer, persister);
        ReflectionTestUtils.setField(service, "storageRawPath", "./storage/raw");
        ReflectionTestUtils.setField(service, "resizeMaxConcurrent", 2);
        ReflectionTestUtils.setField(service, "resizeAcquireTimeoutSec", 5L);

        // 기본: 원본 1920x1080, APPROVED, 비-증강본, 중복 없음
        when(videoProbe.probe(any(Path.class))).thenReturn(new VideoProbe.Dimensions(1920, 1080));
        when(videoRepository.findByVmsClipId(any())).thenReturn(Optional.empty());
        when(persister.persist(any(), any(), any(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ResolutionChangeResponse(999L, 1920, 1080, 960, 540, 0.5, 3, 2, 1));
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

    private LsDataRaw raw(Long rawSn, Long parentRawSn) {
        // src 경로는 storageRawPath base(./storage/raw) 하위 상대경로로 — MEDIUM-1 base 검증 통과용
        LsDataRaw r = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, rawSn + ".mp4", null, 60);
        setField(r, "rawSn", rawSn);
        if (parentRawSn != null) {
            setField(r, "parentRawSn", parentRawSn);
        }
        return r;
    }

    private void approved(Long rawSn) {
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        st.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(statusRepository.findByRawDataIdIn(any())).thenReturn(List.of(st));
    }

    private ResolutionChangeRequest req(ResolutionPreset p) {
        return new ResolutionChangeRequest(p);
    }

    @Test
    @DisplayName("검수완료_아닌_영상_해상도변경_요청시_409")
    void notApproved_409() {
        // given
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        LsRawDataStatus st = LsRawDataStatus.initial(1L);
        st.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
        when(statusRepository.findByRawDataIdIn(any())).thenReturn(List.of(st));

        // when / then
        assertThatThrownBy(() -> service.changeResolution(1L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(persister, never()).persist(any(), any(), any(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("영상_미존재_시_404")
    void notFound_404() {
        when(videoRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeResolution(404L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("P50_변환시_새영상_PENDING_PARENT_RAW_SN_생성")
    void p50_createsPendingChild() {
        // given
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);

        // when
        ResolutionChangeResponse res = service.changeResolution(1L, req(ResolutionPreset.P50));

        // then — persister 호출 + 응답 반영
        assertThat(res.newRawSn()).isEqualTo(999L);
        verify(persister).persist(any(LsDataRaw.class), any(), any(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("ffprobe_원본WH_기반_targetWH_even_계산_검증")
    void targetEvenCalculation() {
        // given — 홀수가 나오도록 1921x1081, P50 → 960x540 (짝수 내림)
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        when(videoProbe.probe(any(Path.class))).thenReturn(new VideoProbe.Dimensions(1921, 1081));

        // when
        service.changeResolution(1L, req(ResolutionPreset.P50));

        // then
        ArgumentCaptor<Integer> wCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> hCap = ArgumentCaptor.forClass(Integer.class);
        verify(videoResizer).resize(any(), any(), anyDouble(), wCap.capture(), hCap.capture());
        assertThat(wCap.getValue()).isEqualTo(960);   // floor(1921*0.5)=960
        assertThat(hCap.getValue()).isEqualTo(540);   // floor(1081*0.5)=540
        assertThat(wCap.getValue() % 2).isZero();
        assertThat(hCap.getValue() % 2).isZero();
    }

    @Test
    @DisplayName("ffprobe_video_stream_없음_또는_WH_0_이면_400")
    void zeroDimensions_400() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        when(videoProbe.probe(any(Path.class))).thenReturn(new VideoProbe.Dimensions(0, 0));

        assertThatThrownBy(() -> service.changeResolution(1L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(videoResizer, never()).resize(any(), any(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("targetWH_2미만_과소축소_400")
    void underScale_400() {
        // given — 2x2 원본 + P25 → floor(0.5)=0 → <2
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        when(videoProbe.probe(any(Path.class))).thenReturn(new VideoProbe.Dimensions(2, 2));

        assertThatThrownBy(() -> service.changeResolution(1L, req(ResolutionPreset.P25)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(videoResizer, never()).resize(any(), any(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("이미_증강본(PARENT_RAW_SN_not_null)_요청시_400")
    void nestedAugment_400() {
        when(videoRepository.findById(2L)).thenReturn(Optional.of(raw(2L, 1L)));

        assertThatThrownBy(() -> service.changeResolution(2L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(videoProbe, never()).probe(any());
    }

    @Test
    @DisplayName("동일_parent_preset_중복요청_409")
    void duplicateChild_409() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        // 동일 clipId 자식 이미 존재
        when(videoRepository.findByVmsClipId("clip-1_RES_P50"))
                .thenReturn(Optional.of(raw(50L, 1L)));

        assertThatThrownBy(() -> service.changeResolution(1L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(videoResizer, never()).resize(any(), any(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("ffmpeg성공후_DB저장_실패시_출력파일_정리됨")
    void dbFailure_cleansOrphanFile() throws Exception {
        // given — resizer 가 실제 더미 파일을 생성하도록 stub, persister 는 예외
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);

        AtomicLong createdCheck = new AtomicLong();
        // resize 는 void → doAnswer 로 더미 파일 작성
        org.mockito.Mockito.doAnswer(inv -> {
            Path dst = inv.getArgument(1);
            Files.createDirectories(dst.getParent());
            Files.write(dst, new byte[]{1, 2, 3});
            createdCheck.set(1);
            return null;
        }).when(videoResizer).resize(any(), any(), anyDouble(), anyInt(), anyInt());

        when(persister.persist(any(), any(), any(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "DB 적재 실패"));

        // when / then
        assertThatThrownBy(() -> service.changeResolution(1L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class);

        // 더미 파일이 생성되었고, 정리되어 더 이상 존재하지 않아야 함
        assertThat(createdCheck.get()).isEqualTo(1);
        Path expected = Path.of("./storage/raw").toAbsolutePath().normalize()
                .resolve("resolution").resolve("1").resolve("P50").resolve("1.mp4");
        assertThat(Files.exists(expected)).isFalse();
    }

    // ─── MEDIUM-1: src(원본) 경로 normalize + base 검증 (CWE-22) ───

    @Test
    @DisplayName("src경로_storage_base_벗어나면_거부_ffprobe_resize_미실행")
    void srcPathOutsideBase_rejected() {
        // given — DB 의 원본 경로가 storage base 밖(절대경로 /etc/passwd)
        LsDataRaw r = LsDataRaw.createFromIngest(
                "clip-1", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/etc/passwd", null, 60);
        setField(r, "rawSn", 1L);
        when(videoRepository.findById(1L)).thenReturn(Optional.of(r));
        approved(1L);

        // when / then — 거부(추상 메시지), 외부 프로세스 미실행
        assertThatThrownBy(() -> service.changeResolution(1L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class);
        verify(videoProbe, never()).probe(any());
        verify(videoResizer, never()).resize(any(), any(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("정상_src경로는_통과_회귀")
    void srcPathInsideBase_passes() {
        // given — base 내부 경로(./storage/raw/1.mp4)
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);

        // when
        ResolutionChangeResponse res = service.changeResolution(1L, req(ResolutionPreset.P50));

        // then — 정상 처리(ffprobe/resize 실행)
        assertThat(res.newRawSn()).isEqualTo(999L);
        verify(videoProbe).probe(any());
        verify(videoResizer).resize(any(), any(), anyDouble(), anyInt(), anyInt());
    }

    // ─── MEDIUM-2: ffmpeg 동시 실행 Semaphore 상한 (DoS, API4:2023) ───

    @Test
    @DisplayName("동시실행_상한_초과시_거부_429")
    void concurrencyLimitExceeded_rejected() {
        // given — permit 0(상한 0) 으로 설정하여 즉시 획득 실패 유도
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        ReflectionTestUtils.setField(service, "resizeMaxConcurrent", 0);
        ReflectionTestUtils.setField(service, "resizeAcquireTimeoutSec", 0L);
        ReflectionTestUtils.setField(service, "resizeSemaphore", new java.util.concurrent.Semaphore(0, true));

        // when / then — 획득 실패 → 거부, resize 미실행
        assertThatThrownBy(() -> service.changeResolution(1L, req(ResolutionPreset.P50)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        verify(videoResizer, never()).resize(any(), any(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("동시실행_상한_내에서는_정상_처리_그리고_permit_복원")
    void concurrencyWithinLimit_passes() {
        // given — 상한 1
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(1, true);
        ReflectionTestUtils.setField(service, "resizeMaxConcurrent", 1);
        ReflectionTestUtils.setField(service, "resizeSemaphore", sem);

        // when
        ResolutionChangeResponse res = service.changeResolution(1L, req(ResolutionPreset.P50));

        // then — 정상 처리 + finally 에서 permit 복원
        assertThat(res.newRawSn()).isEqualTo(999L);
        verify(videoResizer).resize(any(), any(), anyDouble(), anyInt(), anyInt());
        assertThat(sem.availablePermits()).isEqualTo(1);
    }

    // ─── Persister 직접 단위 테스트 (좌표 스케일·속성값 복사 검증) ───

    @Test
    @DisplayName("라벨_좌표가_factor로_스케일되어_복사됨_그리고_라벨_속성값도_복사됨_프레임_메타_건수_반영")
    void persisterScalesLabelsCopiesAttrsAndMeta() {
        // given — 실제 Persister + 실제 LabelCoordinateScaler + mock repos
        LsDataSrcRepository srcRepo = org.mockito.Mockito.mock(LsDataSrcRepository.class);
        LsDataLblRepository lblRepo = org.mockito.Mockito.mock(LsDataLblRepository.class);
        LsDataLblAttrValRepository attrRepo = org.mockito.Mockito.mock(LsDataLblAttrValRepository.class);
        LsDataMetaRepository metaRepo = org.mockito.Mockito.mock(LsDataMetaRepository.class);
        VideoRepository videoRepo = org.mockito.Mockito.mock(VideoRepository.class);
        LabelCoordinateScaler scaler = new LabelCoordinateScaler(new ObjectMapper());

        VideoResolutionPersister p = new VideoResolutionPersister(
                videoRepo, srcRepo, lblRepo, attrRepo, metaRepo, scaler);

        LsDataRaw parent = raw(1L, null);
        AtomicLong rawSeq = new AtomicLong(900);
        AtomicLong srcSeq = new AtomicLong(700);
        AtomicLong lblSeq = new AtomicLong(500);

        when(videoRepo.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw r = inv.getArgument(0);
            setField(r, "rawSn", rawSeq.incrementAndGet());
            return r;
        });

        // 원본 프레임 1개 (srcSn=700)
        LsDataSrc f0 = LsDataSrc.create(1L, 0, "/raw/1/f0.jpg", null);
        setField(f0, "srcSn", 700L);
        when(srcRepo.findByRawSnOrderByFrameNoAsc(1L)).thenReturn(List.of(f0));
        when(srcRepo.saveAll(any())).thenAnswer(inv -> {
            List<LsDataSrc> result = new java.util.ArrayList<>();
            for (LsDataSrc s : (Iterable<LsDataSrc>) inv.getArgument(0)) {
                setField(s, "srcSn", srcSeq.incrementAndGet());
                result.add(s);
            }
            return result;
        });

        // 원본 라벨 1개 — flat BBOX [100,200,300,400], srcSn=700
        LsDataLbl lbl = LsDataLbl.createAutoBbox(700L, null, "person", "[100,200,300,400]",
                java.math.BigDecimal.valueOf(0.9), null);
        setField(lbl, "lblSn", 800L);
        when(lblRepo.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl));
        when(lblRepo.saveAll(any())).thenAnswer(inv -> {
            List<LsDataLbl> result = new java.util.ArrayList<>();
            for (LsDataLbl l : (Iterable<LsDataLbl>) inv.getArgument(0)) {
                setField(l, "lblSn", lblSeq.incrementAndGet());
                result.add(l);
            }
            return result;
        });

        // 원본 속성값 1개 (lblSn=800)
        LsDataLblAttrVal attr = LsDataLblAttrVal.create(800L, 10L, "red");
        when(attrRepo.findByLblSnIn(anyCollection())).thenReturn(List.of(attr));
        when(attrRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // 메타 1개
        LsDataMeta meta = LsDataMeta.create(1L, "weather", "rain");
        when(metaRepo.findByRawSn(1L)).thenReturn(List.of(meta));
        when(metaRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // when — P50 (factor 0.5), target 960x540
        ResolutionChangeResponse res = p.persist(parent, ResolutionPreset.P50, "/raw/resolution/1/P50/1.mp4",
                0.5, 1920, 1080, 960, 540);

        // then — 좌표 스케일 0.5배 검증
        ArgumentCaptor<List<LsDataLbl>> lblCap = ArgumentCaptor.forClass(List.class);
        verify(lblRepo).saveAll(lblCap.capture());
        LsDataLbl copied = lblCap.getValue().get(0);
        assertThat(copied.getPointCn()).isEqualTo("[50,100,150,200]"); // [100,200,300,400] * 0.5
        assertThat(copied.getSrcSn()).isEqualTo(701L); // 신규 srcSn

        // 속성값 복사 — 신규 lblSn 으로 매핑
        ArgumentCaptor<List<LsDataLblAttrVal>> attrCap = ArgumentCaptor.forClass(List.class);
        verify(attrRepo).saveAll(attrCap.capture());
        assertThat(attrCap.getValue()).hasSize(1);
        assertThat(attrCap.getValue().get(0).getLblSn()).isEqualTo(501L); // 신규 lblSn
        assertThat(attrCap.getValue().get(0).getValue()).isEqualTo("red");

        // 메타 1건 복사
        verify(metaRepo, times(1)).saveAll(any());

        // 응답 건수 반영
        assertThat(res.copiedFrames()).isEqualTo(1);
        assertThat(res.copiedLabels()).isEqualTo(1);
        assertThat(res.copiedMetas()).isEqualTo(1);
        assertThat(res.srcW()).isEqualTo(1920);
        assertThat(res.targetW()).isEqualTo(960);
    }
}
