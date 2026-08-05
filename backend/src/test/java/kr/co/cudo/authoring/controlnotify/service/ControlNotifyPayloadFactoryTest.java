package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
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
    private IngestSourceRepository ingestSourceRepository;
    private ControlNotifyMetrics metrics;
    private ControlNotifyPayloadFactory factory;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        metrics = mock(ControlNotifyMetrics.class);
        factory = new ControlNotifyPayloadFactory(
                videoRepository, srcRepository, ingestSourceRepository, metrics);
    }

    private LsDataRaw raw(String evntTypeCd, String lclgvCd, Integer durationSec) {
        return raw(evntTypeCd, lclgvCd, durationSec, "ORIGINAL");
    }

    /** {@code srcType} 이 gen_ai_yn 판정축이다(규격서 §3-2). */
    private LsDataRaw raw(String evntTypeCd, String lclgvCd, Integer durationSec, String srcType) {
        LsDataRaw entity = LsDataRaw.createFromIngest(
                "CLIP-1", "CCTV-1", evntTypeCd, lclgvCd,
                LsDataRaw.PRVC_TYPE_ANONY, "/nas/raw/clip.mp4",
                LocalDateTime.now(), durationSec, srcType);
        setField(entity, "rawSn", RAW_SN);
        return entity;
    }

    /**
     * 관제 인입 평면값 스텁 — 지역명(지자체명)만 관심사다.
     * 구 헬퍼는 관제 공유 마스터 엔티티({@code MngExLocalGov})를 리플렉션으로 만들었으나 V167 로
     * 그 엔티티가 제거됐고, 조달처가 인입 평면값 1필드({@code LCLGV_NM})로 바뀌었다.
     */
    private IngestSourceRow sourceRow(String lclgvNm) {
        return sourceRow(lclgvNm, null, null);
    }

    /** 인입 평면값 스텁 — 지자체명 + 이벤트 분류/카테고리 코드. */
    private IngestSourceRow sourceRow(String lclgvNm, String evntClsfCd, String evntCtgryCd) {
        return new IngestSourceRow() {
            @Override public String getCctvNm() {
                return null;
            }

            @Override public String getLclgvNm() {
                return lclgvNm;
            }

            @Override public String getEvntClsfCd() {
                return evntClsfCd;
            }

            @Override public String getEvntCtgryCd() {
                return evntCtgryCd;
            }

            @Override public String getSrcAnonyInclYn() {
                return null;
            }

            @Override public String getSrcPsdoInclYn() {
                return null;
            }

            @Override public String getSrcPrvcInclYn() {
                return null;
            }
        };
    }

    /** 완료 통지 조립에 필요한 3개 조회를 한 번에 스텁한다. */
    private void stubCompleted(LsDataRaw entity, long imageCount, IngestSourceRow source) {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(entity));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(imageCount);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(source);
    }

    @Test
    @DisplayName("image_count_가_export_완료여부와_무관하게_실측_프레임수를_싣는다")
    void imageCountComesFromFrameCount() {
        // given — export 행이 아직 없어도(비동기 @Async) 프레임 수는 조회 가능하다.
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(16L);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(sourceRow("서울특별시 강남구"));

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
        stubCompleted(raw("INTRUSION", "11680", 45, LsDataRaw.SRC_TYPE_GENERATED), 338L,
                sourceRow("서울특별시 강남구", "01", "0101"));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 계약 9필드가 모두 DB 값에서 왔고 상수(0/null) 가 없다.
        assertThat(payload.jobId()).isEqualTo("26");
        assertThat(payload.eventTypeCd()).isEqualTo("INTRUSION");
        assertThat(payload.evntClsCd()).isEqualTo("01");
        assertThat(payload.evntCtgryCd()).isEqualTo("0101");
        assertThat(payload.lclgvCd()).isEqualTo("11680");
        assertThat(payload.lclgvNm()).isEqualTo("서울특별시 강남구");
        assertThat(payload.durationSec()).isEqualTo(45);
        assertThat(payload.imageCount()).isEqualTo(338);
        assertThat(payload.genAiYn()).isEqualTo("Y");
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
        String longRgnNm = "가".repeat(80) + " " + "나".repeat(80);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(sourceRow(longRgnNm));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then
        assertThat(payload.lclgvNm()).hasSize(ControlNotifyPayloadFactory.LCLGV_NM_MAX_LENGTH);
    }

    @Test
    @DisplayName("관제가_지자체명을_안_보내면_값을_지어내지_않고_null_을_싣는다")
    void unknownLocalGovYieldsNull() {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "99999", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(sourceRow(null));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then
        assertThat(payload.lclgvCd()).isEqualTo("99999");
        assertThat(payload.lclgvNm()).isNull();
    }

    @Test
    @DisplayName("지자체값이_없어도_통지가_예외없이_생성된다")
    void blankLocalGovNameYieldsNullWithoutFailure() {
        // given — 관제가 지역명을 공백으로 보냈다(미송신과 같은 취급).
        //   구 테스트 '폐지된_지자체_코드는_이름을_싣지_않는다'(USE_YN='N' 게이팅)는 폐기됐다 —
        //   인입 평면값에는 활성 축이 없고, 폐지 판정은 관제가 송신 시점에 할 일이다(V167).
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(sourceRow("   "));

        // when — 조회 실패로 예외를 던지면 통지 전체가 폴백 큐로 밀린다. 값 결손은 실패가 아니다.
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 코드는 그대로 싣되 이름은 지어내지 않는다.
        assertThat(payload.lclgvCd()).isEqualTo("11680");
        assertThat(payload.lclgvNm()).isNull();
    }

    @Test
    @DisplayName("관제통지_lclgv_nm_이_인입_지자체값에서_생성된다")
    void localGovNameComesFromIngestRegionName() {
        // given — 조달처는 관제 인입 평면값 LS_DATA_INGEST.LCLGV_NM 하나다(V167 — 구 공유 마스터 제거).
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(sourceRow("경기도 성남시 분당구"));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 우리가 시도/시군구로 쪼개거나 재조립하지 않고 관제가 준 값을 그대로 싣는다
        assertThat(payload.lclgvNm()).isEqualTo("경기도 성남시 분당구");
    }

    @Test
    @DisplayName("인입행이_없는_영상도_통지가_예외없이_생성된다")
    void missingIngestRowYieldsNullLocalGovName() {
        // given — findSourceMeta 는 영상 행만 있으면 전 필드 null 인 행을 준다. 이론상 null 도 방어.
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw("FIRE", "11680", 30)));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        // when / then
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);
        assertThat(payload.lclgvNm()).isNull();
    }

    // ---------------------------------------------------------------- gen_ai_yn (규격서 §3-2)

    @Test
    @DisplayName("GENERATED_원본의_gen_ai_yn_이_Y")
    void generatedOriginYieldsGenAiY() {
        // given — 관제가 AI 로 제작해 인입한 원본.
        stubCompleted(raw("FIRE", "11680", 30, LsDataRaw.SRC_TYPE_GENERATED), 1L, sourceRow(null));

        // when / then
        assertThat(factory.buildCompleted(RAW_SN).genAiYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("AUGMENTED_파생영상의_gen_ai_yn_이_Y")
    void augmentedDerivativeYieldsGenAiY() {
        // given — 저작도구가 만든 증강(WINTER/NIGHT/RAIN)·해상도 파생본.
        stubCompleted(raw("FIRE", "11680", 30, LsDataRaw.SRC_TYPE_AUGMENTED), 1L, sourceRow(null));

        // when / then — 판정축은 <자기 행>의 SRC_TYPE 이다. 인입 조인(부모 폴백)을 타면 부모의
        //   출처유형(ORIGINAL)을 보게 되어 오답이 된다.
        assertThat(factory.buildCompleted(RAW_SN).genAiYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("일반_원본의_gen_ai_yn_이_N")
    void ordinaryOriginYieldsGenAiN() {
        // given — ORIGINAL·RELAY·USER_ULD 는 생성형 AI 산출물이 아니다.
        stubCompleted(raw("FIRE", "11680", 30, "ORIGINAL"), 1L, sourceRow(null));

        // when / then
        assertThat(factory.buildCompleted(RAW_SN).genAiYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("SRC_TYPE_이_null_이어도_gen_ai_yn_은_N")
    void unknownSrcTypeYieldsGenAiNNotNull() {
        // given — 백필 전 레거시 행은 SRC_TYPE 이 null 이다.
        stubCompleted(raw("FIRE", "11680", 30, null), 1L, sourceRow(null));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 관제 계약상 required 라 null 을 실을 수 없다. "생성형 AI 산출물이라는 근거가 없으면
        //   아니다" 라 판정식이 null 에서도 성립하므로 값을 지어내는 것이 아니다.
        assertThat(payload.genAiYn()).isEqualTo("N");
        assertThat(payload.genAiYn()).isNotNull();
    }

    // ------------------------------------------------- 인입 이벤트 분류/카테고리 코드 (D1)

    @Test
    @DisplayName("완료통지의_evnt_cls_cd_evnt_ctgry_cd_가_인입_평면값에서_조달된다")
    void ingestEventCodesComeFromIngestRow() {
        // given — LS_DATA_RAW 에는 이 두 컬럼이 없다. 조회 시점에 인입 행을 조인해 가져온다(D1).
        stubCompleted(raw("FIRE", "11680", 30), 1L, sourceRow("서울특별시 강남구", "01", "0101"));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 코드는 <절단하지 않는다>. 이름과 달리 코드를 자르면 다른 코드가 된다.
        assertThat(payload.evntClsCd()).isEqualTo("01");
        assertThat(payload.evntCtgryCd()).isEqualTo("0101");
    }

    @Test
    @DisplayName("관제가_이벤트분류_카테고리를_안_보내면_값을_지어내지_않고_null_을_싣는다")
    void absentIngestEventCodesYieldNull() {
        // given — 관제는 현재 이 두 코드를 보내지 않는다(dev 실측 40행 전량 NULL). 공백 문자열도
        //   "미송신"과 같은 취급이다.
        stubCompleted(raw("FIRE", "11680", 30), 1L, sourceRow("서울특별시 강남구", null, "   "));

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 상수 self-fill 금지(D-ISSUE-41)
        assertThat(payload.evntClsCd()).isNull();
        assertThat(payload.evntCtgryCd()).isNull();
    }

    @Test
    @DisplayName("인입행이_없어도_이벤트분류_카테고리가_null_로_조립되고_예외가_없다")
    void missingIngestRowYieldsNullEventCodes() {
        // given
        stubCompleted(raw("FIRE", "11680", 30), 1L, null);

        // when
        TaskCompletedPayload payload = factory.buildCompleted(RAW_SN);

        // then — 값 결손은 실패가 아니다(예외를 던지면 통지 전체가 폴백 큐로 밀린다).
        assertThat(payload.evntClsCd()).isNull();
        assertThat(payload.evntCtgryCd()).isNull();
        assertThat(payload.jobId()).isEqualTo("26");
    }

    @Test
    @DisplayName("인입_평면값_조회는_완료통지_1건당_1회만_수행된다")
    void ingestSourceIsQueriedOnlyOncePerCompletedPayload() {
        // given — 지자체명·이벤트 분류·카테고리 3필드가 같은 행에서 온다. 필드마다 조회하면
        //   통지 1건에 동일 쿼리가 3번 나간다.
        stubCompleted(raw("FIRE", "11680", 30), 1L, sourceRow("서울특별시 강남구", "01", "0101"));

        // when
        factory.buildCompleted(RAW_SN);

        // then
        verify(ingestSourceRepository, org.mockito.Mockito.times(1)).findSourceMeta(RAW_SN);
    }

    // ---------------------------------------------------------------- ver_expln

    @Test
    @DisplayName("수정통지_ver_expln_은_호출부가_판정한_문구를_그대로_싣는다")
    void verExplnIsCarriedThroughFromCaller() {
        // given — 발송 계기를 아는 주체는 호출부(ControlNotifyService)다. 팩토리는 재유도하지 않는다.
        when(srcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any()))
                .thenReturn(List.<Object[]>of(new Object[]{5001L, 7L}));
        when(srcRepository.findExportableFrameNosByRawSn(RAW_SN)).thenReturn(List.of(0L));

        // when / then
        assertThat(factory.buildModified(RAW_SN, List.of(5001L), VersionExplanationPolicy.META_MODIFIED)
                .verExpln()).isEqualTo("메타데이터 수정");
        assertThat(factory.buildModifiedForAllFrames(RAW_SN, "라벨 수정 3건").verExpln())
                .isEqualTo("라벨 수정 3건");
    }

    @Test
    @DisplayName("요청한_srcSn_전부가_미해결이면_changed_items_가_비고_전체건수가_메트릭에_기록된다")
    void allUnresolvedSrcSnsYieldEmptyChangedItemsAndMetric() {
        // given — 요청 프레임이 모두 해석되지 않는다(삭제된 프레임 등). 사일런트 드롭 금지(S10).
        when(srcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any())).thenReturn(List.of());

        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, List.of(9001L, 9002L), VersionExplanationPolicy.META_MODIFIED);

        // then — 빈 changed_items 로 나가되 미해결 건수가 메트릭에 그대로 기록된다.
        assertThat(payload.changedItems().images()).isEmpty();
        assertThat(payload.changedItems().jsons()).isEmpty();
        verify(metrics).incrementUnresolvedFrame(2);
    }

    @Test
    @DisplayName("changed_items_파일명이_SRC_SN_이_아니라_FRM_NO_4자리_zero_pad_형식이다")
    void changedItemsUseZeroPaddedFrameNo() {
        // given — srcSn 5001 → frmNo 7, srcSn 5002 → frmNo 338
        when(srcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any()))
                .thenReturn(List.<Object[]>of(new Object[]{5001L, 7L}, new Object[]{5002L, 338L}));

        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, List.of(5001L, 5002L), VersionExplanationPolicy.META_MODIFIED);

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
        when(srcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any()))
                .thenReturn(List.<Object[]>of(new Object[]{5001L, 7L}));

        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, List.of(5001L, 5002L), VersionExplanationPolicy.META_MODIFIED);

        // then — 나머지는 정상 전송 + 미해석 건수 메트릭 기록(사일런트 드롭 금지)
        assertThat(payload.changedItems().jsons()).containsExactly("0007.json");
        verify(metrics).incrementUnresolvedFrame(1);
    }

    @Test
    @DisplayName("srcSn_이_null_이어도_changed_items_에_frame_null_이_섞이지_않는다")
    void nullSrcSnNeverProducesGarbageFileName() {
        // given — 영상 단위 변경(srcSn=null)만 있는 경우
        // when
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, java.util.Arrays.asList((Long) null), VersionExplanationPolicy.META_MODIFIED);

        // then — "null" 문자열이 파일명에 섞이면 관제가 존재하지 않는 파일을 픽업한다.
        assertThat(payload.changedItems().jsons()).isEmpty();
        assertThat(payload.changedItems().images()).isEmpty();
        assertThat(payload.jobId()).isEqualTo("26");
        verify(metrics, org.mockito.Mockito.never()).incrementUnresolvedFrame(anyInt());
    }

    @Test
    @DisplayName("MED1_한쪽_벌만_보유한_프레임은_buildModified의_changed_items에서_제외된다")
    void singleVelFramesExcludedFromBuildModified() {
        // given — 증강 파생 등으로 비식별(또는 원본) 벌 하나만 보유한 프레임(예: 5002)은 both-벌 쿼리에
        //   걸리지 않아 반환되지 않는다. changed_items 파일명 1건은 원본/비식별 두 벌을 모두 가리키므로
        //   한쪽만 있는 프레임을 실으면 관제가 없는 벌을 픽업해 404 가 난다(MED-1). 두 벌을 다 가진
        //   프레임(5001)만 both-벌 쿼리로 조회된다.
        when(srcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn(eq(RAW_SN), any()))
                .thenReturn(List.<Object[]>of(new Object[]{5001L, 7L}));

        // when — 5001(양 벌 보유), 5002(한쪽 벌만) 를 changed 로 요청
        TaskModifiedPayload payload = factory.buildModified(RAW_SN, List.of(5001L, 5002L), VersionExplanationPolicy.META_MODIFIED);

        // then — 양 벌 프레임만 실리고, 한쪽 벌만인 5002 는 제외(미해석 1건 메트릭). 통지 자체는 나간다.
        assertThat(payload.changedItems().jsons()).containsExactly("0007.json");
        assertThat(payload.changedItems().images()).isEmpty();
        verify(metrics).incrementUnresolvedFrame(1);
    }

    @Test
    @DisplayName("재승인_전량_재산출_통지는_모든_프레임의_이미지와_JSON_을_싣는다")
    void reapprovalIncludesAllFrames() {
        // given
        when(srcRepository.findExportableFrameNosByRawSn(RAW_SN)).thenReturn(List.of(0L, 1L, 2L));

        // when
        TaskModifiedPayload payload = factory.buildModifiedForAllFrames(RAW_SN, VersionExplanationPolicy.REVIEW_COMPLETED);

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
        TaskModifiedPayload payload = factory.buildModifiedForAllFrames(RAW_SN, VersionExplanationPolicy.REVIEW_COMPLETED);

        // then
        assertThat(payload.changedItems().images()).containsExactly("0000.jpg", "0002.jpg");
        assertThat(payload.changedItems().jsons()).containsExactly("0000.json", "0002.json");
        assertThat(payload.changedItems().images()).doesNotContain("0001.jpg");
    }

    // --- 테스트 헬퍼 (엔티티가 setter 를 제공하지 않으므로 리플렉션으로 시드) ---

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
