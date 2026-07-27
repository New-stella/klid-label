package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngExLocalGov;
import kr.co.cudo.authoring.video.repository.MngExLocalGovRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ControlNotifyPayloadFactory 단위 테스트 — 관제 계약 필드의 <b>DB 실측 조달</b> 검증.
 *
 * <p>D-ISSUE-41(상수 self-fill) · N-6(image_count 조달처) · S10(FRM_NO 해석 실패) 대응.
 */
class ControlNotifyPayloadFactoryTest {

    private static final Long RAW_SN = 26L;

    private VideoRepository videoRepository;
    private LsDataSrcRepository srcRepository;
    private MngExLocalGovRepository localGovRepository;
    private ControlNotifyMetrics metrics;
    private ControlNotifyPayloadFactory factory;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        localGovRepository = mock(MngExLocalGovRepository.class);
        metrics = mock(ControlNotifyMetrics.class);
        factory = new ControlNotifyPayloadFactory(
                videoRepository, srcRepository, localGovRepository, metrics);
    }

    private LsDataRaw raw(String evntTypeCd, String lclgvCd, Integer durationSec) {
        LsDataRaw entity = LsDataRaw.createFromIngest(
                "CLIP-1", "CCTV-1", evntTypeCd, lclgvCd,
                LsDataRaw.PRVC_TYPE_ANONY, "/nas/raw/clip.mp4",
                LocalDateTime.now(), durationSec);
        setField(entity, "rawSn", RAW_SN);
        return entity;
    }

    private MngExLocalGov localGov(String sido, String sgg) {
        return localGov(sido, sgg, "Y");
    }

    private MngExLocalGov localGov(String sido, String sgg, String useYn) {
        MngExLocalGov gov = newInstance(MngExLocalGov.class);
        setField(gov, "sidoNm", sido);
        setField(gov, "sggNm", sgg);
        setField(gov, "useYn", useYn);
        return gov;
    }

    @Test
    @DisplayName("image_count_가_export_완료여부와_무관하게_실측_프레임수를_싣는다")
    void imageCountComesFromFrameCount() {
        // given — export 행이 아직 없어도(비동기 @Async) 프레임 수는 조회 가능하다.
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(16L);
        when(localGovRepository.findById("11680")).thenReturn(Optional.of(localGov("서울특별시", "강남구")));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 상수 0 이 아니라 LS_DATA_SRC 실측 COUNT
        assertThat(payload.imageCount()).isEqualTo(16);
        verify(srcRepository).countByRawSn(RAW_SN);
    }

    @Test
    @DisplayName("TASK_COMPLETED_페이로드의_프레임수_라벨수가_DB_실측값과_일치")
    void completedPayloadHasNoHardcodedConstants() {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("INTRUSION", "11680", 45)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(338L);
        when(localGovRepository.findById("11680")).thenReturn(Optional.of(localGov("서울특별시", "강남구")));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 전 필드가 DB 값에서 왔고 상수(0/null) 가 없다.
        assertThat(payload.jobId()).isEqualTo("26");
        assertThat(payload.eventTypeCd()).isEqualTo("INTRUSION");
        assertThat(payload.lclgvCd()).isEqualTo("11680");
        assertThat(payload.lclgvNm()).isEqualTo("서울특별시 강남구");
        assertThat(payload.durationSec()).isEqualTo(45);
        assertThat(payload.imageCount()).isEqualTo(338);
    }

    @Test
    @DisplayName("영상행이_없으면_0으로_대체하지_않고_예외로_실패시킨다")
    void missingRawFailsClosed() {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        // when / then — fail-closed: 상수로 채워 보내면 관제 데이터마트가 오염된다.
        assertThatThrownBy(() -> factory.buildCompleted(RAW_SN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("지자체명이_100자를_넘으면_절단된다")
    void localGovNameIsTruncatedToContractLength() {
        // given — 관제 datasets.lclgv_nm 은 varchar(100)
        String longSido = "가".repeat(80);
        String longSgg = "나".repeat(80);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(localGovRepository.findById("11680")).thenReturn(Optional.of(localGov(longSido, longSgg)));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then
        assertThat(payload.lclgvNm()).hasSize(ControlNotifyPayloadFactory.LCLGV_NM_MAX_LENGTH);
    }

    @Test
    @DisplayName("지자체_마스터에_없으면_값을_지어내지_않고_null_을_싣는다")
    void unknownLocalGovYieldsNull() {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "99999", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(localGovRepository.findById("99999")).thenReturn(Optional.empty());

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then
        assertThat(payload.lclgvCd()).isEqualTo("99999");
        assertThat(payload.lclgvNm()).isNull();
    }

    @Test
    @DisplayName("폐지된_지자체_코드는_이름을_싣지_않는다")
    void inactiveLocalGovYieldsNull() {
        // given — USE_YN='N' (폐지). 폐지 명칭을 실어 보내면 관제가 폐지 지자체로 데이터셋을 등록한다.
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(localGovRepository.findById("11680"))
                .thenReturn(Optional.of(localGov("옛시도", "옛시군구", "N")));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 코드는 그대로 싣되 이름은 지어내지 않는다.
        assertThat(payload.lclgvCd()).isEqualTo("11680");
        assertThat(payload.lclgvNm()).isNull();
    }

    @Test
    @DisplayName("요청한_srcSn_전부가_미해결이면_changed_items_가_비고_전체건수가_메트릭에_기록된다")
    void allUnresolvedSrcSnsYieldEmptyChangedItemsAndMetric() {
        // given — 요청 프레임이 모두 해석되지 않는다(삭제된 프레임 등). 사일런트 드롭 금지(S10).
        when(srcRepository.findExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any())).thenReturn(List.of());

        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, List.of(9001L, 9002L));

        // then — 빈 changed_items 로 나가되 미해결 건수가 메트릭에 그대로 기록된다.
        assertThat(payload.changedItems().images()).isEmpty();
        assertThat(payload.changedItems().jsons()).isEmpty();
        verify(metrics).incrementUnresolvedFrame(2);
    }

    @Test
    @DisplayName("changed_items_파일명이_SRC_SN_이_아니라_FRM_NO_4자리_zero_pad_형식이다")
    void changedItemsUseZeroPaddedFrameNo() {
        // given — srcSn 5001 → frmNo 7, srcSn 5002 → frmNo 338
        when(srcRepository.findExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any()))
                .thenReturn(List.<Object[]>of(new Object[]{5001L, 7L}, new Object[]{5002L, 338L}));

        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, List.of(5001L, 5002L));

        // then — 내부 식별자(SRC_SN)가 아니라 산출 파일명
        assertThat(payload.jobId()).isEqualTo("26");
        assertThat(payload.changedItems().jsons()).containsExactly("0007.json", "0338.json");
        assertThat(payload.changedItems().jsons()).noneMatch(name -> name.contains("5001"));
        // 라벨/메타 변경은 JSON 만 바뀐다 — 이미지는 변경 대상이 아니다.
        assertThat(payload.changedItems().images()).isEmpty();
    }

    @Test
    @DisplayName("존재하지_않는_srcSn_이_섞여도_나머지_프레임이_전송되고_경고가_남는다")
    void unresolvedSrcSnIsCountedNotSilentlyDropped() {
        // given — 5002 는 삭제된 프레임이라 조회되지 않는다.
        when(srcRepository.findExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any()))
                .thenReturn(List.<Object[]>of(new Object[]{5001L, 7L}));

        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, List.of(5001L, 5002L));

        // then — 나머지는 정상 전송 + 미해석 건수 메트릭 기록(사일런트 드롭 금지)
        assertThat(payload.changedItems().jsons()).containsExactly("0007.json");
        verify(metrics).incrementUnresolvedFrame(1);
    }

    @Test
    @DisplayName("srcSn_이_null_이어도_changed_items_에_frame_null_이_섞이지_않는다")
    void nullSrcSnNeverProducesGarbageFileName() {
        // given — 영상 단위 변경(srcSn=null)만 있는 경우
        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, java.util.Arrays.asList((Long) null));

        // then — "null" 문자열이 파일명에 섞이면 관제가 존재하지 않는 파일을 픽업한다.
        assertThat(payload.changedItems().jsons()).isEmpty();
        assertThat(payload.changedItems().images()).isEmpty();
        assertThat(payload.jobId()).isEqualTo("26");
        verify(metrics, org.mockito.Mockito.never()).incrementUnresolvedFrame(anyInt());
    }

    @Test
    @DisplayName("재승인_전량_재산출_통지는_모든_프레임의_이미지와_JSON_을_싣는다")
    void reapprovalIncludesAllFrames() {
        // given
        when(srcRepository.findExportableFrameNosByRawSn(RAW_SN)).thenReturn(List.of(0L, 1L, 2L));

        // when
        TaskModifiedPayload payload = factory.buildModifiedForAllFrames(RAW_SN);

        // then — 새 버전 폴더가 통째로 재생성되므로 이미지도 변경 대상이다.
        assertThat(payload.changedItems().images())
                .containsExactly("0000.jpg", "0001.jpg", "0002.jpg");
        assertThat(payload.changedItems().jsons())
                .containsExactly("0000.json", "0001.json", "0002.json");
    }

    @Test
    @DisplayName("원천이미지가_없어_산출되지_않는_프레임은_changed_items_에서_제외된다")
    void framesWithoutSourceImageAreExcluded() {
        // given — 리포지토리 쿼리가 "원천 경로 보유" 프레임만 돌려준다(B-1). export writer 가
        //         건너뛴 프레임의 파일명을 실으면 관제가 없는 파일을 픽업해 404 를 맞는다.
        //         LS_DATA_SRC 에는 0/1/2 가 있지만 1 은 원본·비식별 경로가 모두 비어 있는 상태.
        when(srcRepository.findExportableFrameNosByRawSn(RAW_SN)).thenReturn(List.of(0L, 2L));

        // when
        TaskModifiedPayload payload = factory.buildModifiedForAllFrames(RAW_SN);

        // then
        assertThat(payload.changedItems().images()).containsExactly("0000.jpg", "0002.jpg");
        assertThat(payload.changedItems().jsons()).containsExactly("0000.json", "0002.json");
        assertThat(payload.changedItems().images()).doesNotContain("0001.jpg");
    }

    // --- 테스트 헬퍼 (엔티티가 setter 를 제공하지 않으므로 리플렉션으로 시드) ---

    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
