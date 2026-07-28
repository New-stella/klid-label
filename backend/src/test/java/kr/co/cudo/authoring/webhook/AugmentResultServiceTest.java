package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.runner.AsyncVideoMetaRunner;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.service.AugmentOutcome;
import kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AugmentResultService 단위 테스트 — Phase 11 계약 반영.
 *
 * <p><b>동기 계약</b>: handle 트랜잭션은 부모 안전 판정(잠금·게이트·멱등)을 수행하고, 성공 시 신규
 * 증강 RAW 를 <b>PENDING·deIdntfYn='N'</b> 으로만 커밋한 뒤 {@link AsyncAugmentFrameRunner} 를
 * 커밋 후 트리거한다. 프레임/라벨/메타/procLog/MARKING_READY 는 이 동기 경로에서 관측되지 않는다
 * (async 성공 후 별도 커밋에서만 확정 — {@code AugmentFrameExtractionServiceTest} 가 검증).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentResultServiceTest {

    @Mock LsDataAugRepository augRepository;
    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock AsyncAugmentFrameRunner asyncAugmentFrameRunner;
    @Mock AsyncVideoMetaRunner asyncVideoMetaRunner;

    private AugmentResultService service;
    private final java.util.concurrent.atomic.AtomicLong rawSnSeq = new java.util.concurrent.atomic.AtomicLong(9000);

    /**
     * 적재 시점 경로 검증(B-2)용 리졸버 — 테스트 픽스처 경로({@code /storage/...})가 통과하도록
     * 마운트 루트를 {@code /storage} 로 잡는다. 실 배포에서는 NAS 마운트 루트가 들어온다.
     */
    private static VideoArtifactRootResolver allowedStorageResolver() {
        return new VideoArtifactRootResolver(
                "/storage", "", "/storage/raw", "/storage/deidentified", "/storage/labeling",
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
    }

    @BeforeEach
    void setup() {
        service = new AugmentResultService(augRepository, videoRepository, srcRepository,
                asyncAugmentFrameRunner, asyncVideoMetaRunner, allowedStorageResolver());
        when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw r = inv.getArgument(0);
            setField(r, "rawSn", rawSnSeq.incrementAndGet());
            return r;
        });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("증강결과_적용_미존재_dataAugSn_시_404")
    void unknownDataAugSn_throws404() {
        when(augRepository.findByDataAugSnForUpdate(1L)).thenReturn(Optional.empty());
        AugmentOutcome req = new AugmentOutcome(
                1L, "aug_001", true, "/storage/augment/1.mp4");

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
    }

    @Test
    @DisplayName("AugmentResultService_적재_시_LS_DATA_AUG_상태_ACCEPTED_갱신")
    void appliesAcceptedStatus() throws Exception {
        LsDataAug aug = newAug(10L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(10L)).thenReturn(Optional.of(aug));

        AugmentOutcome req = new AugmentOutcome(
                10L, "aug_010", true, "/storage/augment/10.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(aug.getExternalJobId()).isEqualTo("aug_010");
    }

    @Test
    @DisplayName("AugmentResultService_FAILED_status_시_LS_DATA_AUG_REJECTED")
    void failedStatus_mapsToRejected() throws Exception {
        LsDataAug aug = newAug(11L, "NIGHT", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(11L)).thenReturn(Optional.of(aug));

        AugmentOutcome req = new AugmentOutcome(
                11L, "aug_011", false, null);

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
    }

    @Test
    @DisplayName("증강결과_종결행_재전송_시_멱등스킵")
    void replay_returnsFalse() throws Exception {
        LsDataAug aug = newAug(12L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(12L)).thenReturn(Optional.of(aug));

        AugmentOutcome req = new AugmentOutcome(
                12L, "aug_012", true, null);

        assertThat(service.handle(req)).isTrue();
        assertThat(service.handle(req)).isFalse();
    }

    @Test
    @DisplayName("동일_otsdJobId_재전송_시_두번째_영상은_생성되지_않는다")
    void duplicateOtsdJobId_doesNotCreateSecondVideo() throws Exception {
        LsDataRaw parentRaw = newRaw(150L);
        LsDataSrc originSrc = newSrc(250L, 150L, 0);
        LsDataAug aug = newAugWithSrc(60L, 250L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(60L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(250L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(150L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(150L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                60L, "aug_060", true, "/storage/augment/60.mp4");

        assertThat(service.handle(req)).isTrue();
        assertThat(service.handle(req)).isFalse();

        verify(videoRepository, times(1)).save(any(LsDataRaw.class));
        // 신규 영상 1회만 생성 → 프레임 재추출 러너도 1회만 트리거
        verify(asyncAugmentFrameRunner, times(1)).runAsync(anyLong(), eq(60L));
    }

    /**
     * DEV_FIX LOW — otsd_job_id 선점 충돌은 <b>409 로 종결</b>한다(구 "재조회 후 멱등 흡수" 폐기).
     *
     * <p>본 서비스는 {@code GenAiCallbackService.handle} 트랜잭션에 조인돼 실행된다. UNIQUE 위반은
     * 그 트랜잭션을 rollback-only 로 만들므로 여기서 정상 반환(흡수)하면 컨트롤러가 200 을 만들고
     * 커밋 단계에서 {@code UnexpectedRollbackException}(500) 이 터진다 — 응답과 실제 결과가 어긋난다.
     * 게다가 PostgreSQL 은 위반 이후 같은 트랜잭션의 후속 조회를 거부(25P02)하므로 승자 재조회 자체가
     * 성립하지 않는다. 재전송 멱등은 1차 앵커(non-PENDING skip)가 담당한다.
     */
    @Test
    @DisplayName("동시_콜백_otsd_job_id_선점_충돌은_409로_종결된다")
    void concurrentWebhookRace_endsAsConflict() throws Exception {
        LsDataAug target = newAug(50L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(50L)).thenReturn(Optional.of(target));

        when(augRepository.save(any(LsDataAug.class)))
                .thenThrow(new DataIntegrityViolationException("UNIQUE violation"));

        AugmentOutcome req = new AugmentOutcome(
                50L, "aug_050", true, "/storage/augment/50.mp4");

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 롤백 트랜잭션에서 후속 조회를 시도하지 않는다(PG 25P02 회피).
        verify(augRepository, never()).findByExternalJobId(anyString());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    // ─── Phase 11: 증강 = 새 영상(동기) + 프레임 재추출(async) ───

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        // 부모는 비식별 완료 영상 — createAugmentedVideo 가 콜백 처리 시점에 부모 DE_IDNTF_YN='Y' 를 재확인.
        raw.markDeidentified("Y");
        return raw;
    }

    private LsDataRaw newNonDeidentRaw(Long rawSn, String deIdntfYn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        raw.markDeidentified(deIdntfYn);
        return raw;
    }

    private LsDataSrc newSrc(Long srcSn, Long rawSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, rawSn + "/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataAug newAugWithSrc(Long augSn, Long srcSn, String type) throws Exception {
        LsDataAug aug = LsDataAug.createPending(srcSn, type, BigDecimal.valueOf(0.95), "registrar");
        setField(aug, "dataAugSn", augSn);
        return aug;
    }

    @Test
    @DisplayName("Phase11_증강_SUCCESS_시_새_RAW는_PENDING_deIdntfYn_N으로만_커밋되고_프레임러너_트리거")
    void successCreatesPendingVideoAndTriggersFrameRunner() throws Exception {
        LsDataRaw parentRaw = newRaw(100L);
        LsDataSrc originSrc = newSrc(200L, 100L, 0);
        LsDataAug aug = newAugWithSrc(20L, 200L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(20L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(200L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(100L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                20L, "aug_020", true, "/storage/augment/winter.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        LsDataRaw newRaw = rawCaptor.getValue();
        assertThat(newRaw.getOrgnlRawSn()).isEqualTo(100L);
        // Phase 11 — 동기 커밋 직후엔 PENDING·deIdntfYn='N' (비식별 완료 불변식은 async 성공 후에만).
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N");
        assertThat(newRaw.getRawFilePathNm()).isEqualTo("/storage/augment/winter.mp4");

        // 신규 RAW_SN + dataAugSn 으로 프레임 재추출 러너 1회 트리거.
        ArgumentCaptor<Long> rawSnCaptor = ArgumentCaptor.forClass(Long.class);
        verify(asyncAugmentFrameRunner, times(1)).runAsync(rawSnCaptor.capture(), eq(20L));
        assertThat(rawSnCaptor.getValue()).isNotEqualTo(100L); // 부모가 아닌 신규 영상 SN
    }

    @Test
    @DisplayName("증강_콜백의_rawFilePathNm_이_허용루트_밖이면_콜백이_거부된다")
    void rawFilePathOutsideAllowedRoots_isRejectedAtIngest() {
        // given — Phase 5A 이후 이 값은 산출물 쓰기 base 다. 허용 마운트 루트 밖이면 적재 자체를 막는다.
        AugmentOutcome req = new AugmentOutcome(
                90L, "aug_090", true, "/etc/cron.d/payload.mp4");

        // when / then — 400(INVALID_INPUT). 대상 행 조회조차 하지 않는다(입구 차단).
        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(augRepository, never()).findByDataAugSnForUpdate(anyLong());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
    }

    @Test
    @DisplayName("증강_콜백의_rawFilePathNm_이_상위경로순회로_허용루트를_벗어나도_거부된다")
    void rawFilePathTraversalOutsideAllowedRoots_isRejectedAtIngest() {
        // given — normalize 후 /storage 밖으로 떨어지는 경로
        AugmentOutcome req = new AugmentOutcome(
                91L, "aug_091", true, "/storage/../etc/passwd.mp4");

        // when / then
        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(augRepository, never()).findByDataAugSnForUpdate(anyLong());
    }

    @Test
    @DisplayName("증강_콜백의_rawFilePathNm_이_허용루트_하위면_정상_적재된다")
    void rawFilePathInsideAllowedRoots_isAccepted() throws Exception {
        // given — 허용 마운트 루트(/storage) 하위 경로(양성 케이스)
        LsDataRaw parentRaw = newRaw(190L);
        LsDataSrc originSrc = newSrc(750L, 190L, 0);
        LsDataAug aug = newAugWithSrc(75L, 750L, "WINTER");
        when(augRepository.findByDataAugSnForUpdate(75L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(750L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(190L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(190L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                75L, "aug_075", true, "/storage/augment/75.mp4");

        // when
        boolean applied = service.handle(req);

        // then
        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        assertThat(rawCaptor.getValue().getRawFilePathNm()).isEqualTo("/storage/augment/75.mp4");
    }

    @Test
    @DisplayName("공백_rawFilePathNm_이면_부모_원본경로로_폴백된다")
    void blankRawFilePathNm_fallsBackToParentPath() throws Exception {
        // given — 외부 시스템이 공백(" ") 경로를 보냄. != null 판정으로는 폴백이 안 돼 죽은 행이 남는다.
        LsDataRaw parentRaw = newRaw(195L); // 부모 경로 = /storage/raw/195.mp4
        LsDataSrc originSrc = newSrc(770L, 195L, 0);
        LsDataAug aug = newAugWithSrc(77L, 770L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(77L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(770L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(195L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(195L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                77L, "aug_077", true, " ");

        // when
        boolean applied = service.handle(req);

        // then — 공백은 부모(원본) 경로로 폴백되어 저장된다(공백 base 죽은 행 방지).
        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        assertThat(rawCaptor.getValue().getRawFilePathNm())
                .isEqualTo(parentRaw.getRawFilePathNm());
    }

    @Test
    @DisplayName("증강콜백_처리시_증강행과_부모RAW를_비관적락으로_조회한다")
    void handle_usesPessimisticLockFinders() throws Exception {
        LsDataRaw parentRaw = newRaw(180L);
        LsDataSrc originSrc = newSrc(740L, 180L, 0);
        LsDataAug aug = newAugWithSrc(74L, 740L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(74L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(740L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(180L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(180L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                74L, "aug_074", true, "/storage/augment/74.mp4");

        service.handle(req);

        verify(augRepository, times(1)).findByDataAugSnForUpdate(74L);
        verify(videoRepository, times(1)).findByRawSnForUpdate(180L);
        // 잠금 없는 findById 는 사용하지 않는다(락 우회 회귀 가드).
        verify(augRepository, never()).findById(any());
        verify(videoRepository, never()).findById(any());
    }

    @Test
    @DisplayName("부모_deIdntfYn_F면_증강본_생성보류_영상과_프레임러너_미트리거")
    void parentDeidentReported_blocksAugmentedVideo() throws Exception {
        LsDataRaw parentRaw = newNonDeidentRaw(140L, "F");
        LsDataSrc originSrc = newSrc(710L, 140L, 0);
        LsDataAug aug = newAugWithSrc(71L, 710L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(71L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(710L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(140L)).thenReturn(Optional.of(parentRaw));

        AugmentOutcome req = new AugmentOutcome(
                71L, "aug_071", true, "/storage/augment/71.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue(); // 콜백 자체는 처리(상태 전이)됨
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
        verify(asyncVideoMetaRunner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("증강본_생성후_프레임러너와_메타추출러너가_새_RAW_SN으로_트리거된다")
    void success_triggersFrameAndMetaRunners() throws Exception {
        LsDataRaw parentRaw = newRaw(160L);
        LsDataSrc originSrc = newSrc(720L, 160L, 0);
        LsDataAug aug = newAugWithSrc(72L, 720L, "RAIN");

        when(augRepository.findByDataAugSnForUpdate(72L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(720L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(160L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(160L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                72L, "aug_072", true, "/storage/augment/72.mp4");

        service.handle(req);

        verify(asyncAugmentFrameRunner, times(1)).runAsync(anyLong(), eq(72L));
        ArgumentCaptor<Long> metaCaptor = ArgumentCaptor.forClass(Long.class);
        verify(asyncVideoMetaRunner, times(1)).runAsync(metaCaptor.capture());
        assertThat(metaCaptor.getValue()).isNotEqualTo(160L); // 부모가 아닌 신규 영상 SN
    }

    @Test
    @DisplayName("부모_프레임이_없으면_증강본_생성보류_프레임러너_미트리거")
    void parentNoFrames_blocksAugmentedVideo() throws Exception {
        LsDataRaw parentRaw = newRaw(170L);
        LsDataSrc originSrc = newSrc(730L, 170L, 0);
        LsDataAug aug = newAugWithSrc(73L, 730L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(73L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(730L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(170L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(170L)).thenReturn(List.of()); // 프레임 없음

        AugmentOutcome req = new AugmentOutcome(
                73L, "aug_073", true, "/storage/augment/73.mp4");

        service.handle(req);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Phase11_증강_FAILED_시_새_영상_미생성_프레임러너_미트리거")
    void failedDoesNotCreateNewVideo() throws Exception {
        LsDataAug aug = newAug(22L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(22L)).thenReturn(Optional.of(aug));

        AugmentOutcome req = new AugmentOutcome(
                22L, "aug_022", false, null);

        service.handle(req);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Phase11_증강_SUCCESS_originSrc_미존재_시_신규영상_미생성")
    void successButOriginSrcNotFound_skipsVideoCreation() throws Exception {
        LsDataAug aug = newAugWithSrc(30L, 9999L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(30L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(9999L)).thenReturn(Optional.empty());

        AugmentOutcome req = new AugmentOutcome(
                30L, "aug_030", true, "/storage/augment/winter.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Phase11_증강_SUCCESS_parentRaw_미존재_시_신규영상_미생성")
    void successButParentRawNotFound_skipsVideoCreation() throws Exception {
        LsDataSrc originSrc = newSrc(400L, 8888L, 0);
        LsDataAug aug = newAugWithSrc(31L, 400L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(31L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(400L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(8888L)).thenReturn(Optional.empty());

        AugmentOutcome req = new AugmentOutcome(
                31L, "aug_031", true, "/storage/augment/night.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    @Test
    @DisplayName("멱등_재전송_스킵_시_프레임러너는_한번만_트리거된다")
    void idempotentReplay_triggersRunnerOnce() throws Exception {
        LsDataRaw parentRaw = newRaw(190L);
        LsDataSrc originSrc = newSrc(760L, 190L, 0);
        LsDataAug aug = newAugWithSrc(76L, 760L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(76L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(760L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(190L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(190L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                76L, "aug_076", true, "/storage/augment/76.mp4");

        assertThat(service.handle(req)).isTrue();
        assertThat(service.handle(req)).isFalse(); // 종결 행 재전송 → 멱등 스킵

        verify(asyncAugmentFrameRunner, times(1)).runAsync(anyLong(), anyLong());
    }

    private LsDataAug newAug(Long sn, String type, String status) throws Exception {
        LsDataAug aug = LsDataAug.createPending(1L, type, BigDecimal.valueOf(0.95), "registrar");
        Field f = LsDataAug.class.getDeclaredField("dataAugSn");
        f.setAccessible(true);
        f.set(aug, sn);
        if (!LsDataAug.STTS_PENDING.equals(status)) {
            Field s = LsDataAug.class.getDeclaredField("augProcSttsCd");
            s.setAccessible(true);
            s.set(aug, status);
        }
        return aug;
    }
}
