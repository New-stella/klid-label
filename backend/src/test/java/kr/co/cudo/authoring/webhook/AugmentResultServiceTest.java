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
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentJobIdOwnerLookup;
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
    /** E-ISSUE-05 — UNIQUE 위반 이후 소유자 판별은 <b>독립 트랜잭션</b>에서만 한다(PG 25P02 회피). */
    @Mock AugmentJobIdOwnerLookup jobIdOwnerLookup;
    /**
     * 조상 체인 신고 판정 — 기본 stub 은 "신고 없음"(false)이라 기존 케이스는 부모 행의
     * {@code deIdntfYn} 판정만으로 종전과 동일하게 동작한다.
     */
    @Mock kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate;

    /** 파생 비디오(비식별 사본) 출력 base — 산출 경로 기대값 계산에 함께 쓴다. */
    private static final String DEID_BASE = "/storage/deidentified";

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

    /** 파생영상의 확정 산출 경로 — {@code AugmentExtractSnapshot.videoDst} 와 동일 계산식이어야 한다. */
    private static String expectedDerivativeVideoPath(long parentRawSn, long newRawSn, String augType) {
        return java.nio.file.Paths.get(DEID_BASE).toAbsolutePath().normalize()
                .resolve("videos/augment/" + parentRawSn + "/" + newRawSn + "/" + augType + ".mp4")
                .toString();
    }

    @BeforeEach
    void setup() {
        service = new AugmentResultService(augRepository, videoRepository, srcRepository,
                asyncAugmentFrameRunner, allowedStorageResolver(), jobIdOwnerLookup, deidentReportGate);
        // 기본값: 해당 job_id 를 선점한 다른 증강이 없다.
        when(augRepository.findByExternalJobId(anyString())).thenReturn(Optional.empty());
        when(jobIdOwnerLookup.findOwnerDataAugSn(anyString())).thenReturn(Optional.empty());
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "storageDeidentifiedPath", DEID_BASE);
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
        LsDataRaw parentRaw = newRaw(110L);
        LsDataSrc originSrc = newSrc(210L, 110L, 0);
        LsDataAug aug = newAugWithSrc(10L, 210L, "WINTER");
        when(augRepository.findByDataAugSnForUpdate(10L)).thenReturn(Optional.of(aug));
        when(srcRepository.findById(210L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(110L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(110L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(
                10L, "aug_010", true, "/storage/augment/10.mp4");

        boolean applied = service.handle(req).applied();

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(aug.getExternalJobId()).isEqualTo("aug_010");
        assertThat(aug.getDeadLetterAt())
                .as("성공 인계에는 dead-letter 를 찍지 않는다")
                .isNull();
    }

    /**
     * E-ISSUE-06 회귀 가드 — 실패 인계가 <b>배선 경로를 통해</b> dead-letter 를 찍는지 본다
     * (픽스처가 {@code markDeadLetter()} 를 직접 부르는 위양성이 아니다).
     */
    @Test
    @DisplayName("AugmentResultService_FAILED_status_시_REJECTED_와_DEAD_LETTER_AT_기록")
    void failedStatus_mapsToRejected() throws Exception {
        LsDataAug aug = newAug(11L, "NIGHT", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(11L)).thenReturn(Optional.of(aug));

        AugmentOutcome req = new AugmentOutcome(
                11L, "aug_011", false, null);

        boolean applied = service.handle(req).applied();

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.getDeadLetterAt())
                .as("dead-letter 가 없으면 집계가 이 실패를 COMPLETED 로 오분류한다")
                .isNotNull();
        assertThat(aug.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("증강결과_종결행_재전송_시_멱등스킵")
    void replay_returnsFalse() throws Exception {
        LsDataAug aug = newAug(12L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(12L)).thenReturn(Optional.of(aug));

        AugmentOutcome req = new AugmentOutcome(
                12L, "aug_012", true, null);

        assertThat(service.handle(req).applied()).isTrue();
        assertThat(service.handle(req).applied()).isFalse();
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

        assertThat(service.handle(req).applied()).isTrue();
        assertThat(service.handle(req).applied()).isFalse();

        verify(videoRepository, times(1)).save(any(LsDataRaw.class));
        // 신규 영상 1회만 생성 → 프레임 재추출 러너도 1회만 트리거
        verify(asyncAugmentFrameRunner, times(1)).runAsync(anyLong(), eq(60L));
    }

    // ─── E-ISSUE-05: 재수신(200 no-op) vs 진짜 선점 충돌(409) ───

    /**
     * 재수신은 <b>정상 시나리오</b>다 — 외부/목업은 웹훅을 최대 2회 재시도한다. 같은 증강이 같은
     * job_id 로 다시 오면 상태를 바꾸지 않고 흡수해야 하며(200), 오류(409)로 회신하면 외부가 인계
     * 실패로 오해한다.
     */
    @Test
    @DisplayName("같은_증강의_콜백_재수신은_200_no_op_이다")
    void sameAugmentRedelivery_isAbsorbedAsNoOp() throws Exception {
        LsDataRaw parentRaw = newRaw(160L);
        LsDataSrc originSrc = newSrc(260L, 160L, 0);
        LsDataAug aug = newAugWithSrc(70L, 260L, "WINTER");
        when(augRepository.findByDataAugSnForUpdate(70L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(260L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(160L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(160L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(70L, "aug_070", true, "/storage/augment/70.mp4");
        assertThat(service.handle(req)).isEqualTo(AugmentApplyResult.APPLIED);

        // 재수신 — 이제 대상 행 자신이 그 job_id 를 보유한다(자기 소유는 충돌이 아니다).
        when(augRepository.findByExternalJobId("aug_070")).thenReturn(Optional.of(aug));

        AugmentApplyResult second = service.handle(req);

        assertThat(second).isEqualTo(AugmentApplyResult.DUPLICATE);
        assertThat(second.applied()).isFalse();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository, times(1)).save(any(LsDataRaw.class));
    }

    /**
     * 진짜 충돌 — <b>다른 증강</b>이 이미 그 job_id 를 보유한 오배송이다. 이때만 409 다.
     *
     * <p>판별은 <b>쓰기 이전</b> 선점 검사로 한다 — UNIQUE 위반을 일으킨 뒤 판별하려 하면 트랜잭션이
     * abort(PG 25P02) 되어 재조회 자체가 불가능하다.
     */
    @Test
    @DisplayName("다른_증강이_같은_job_id_를_선점하면_409")
    void otherAugmentOwnsJobId_endsAsConflict() throws Exception {
        LsDataAug target = newAug(71L, "WINTER", LsDataAug.STTS_PENDING);
        LsDataAug owner = newAug(72L, "WINTER", LsDataAug.STTS_ACCEPTED);
        when(augRepository.findByDataAugSnForUpdate(71L)).thenReturn(Optional.of(target));
        when(augRepository.findByExternalJobId("aug_071")).thenReturn(Optional.of(owner));

        AugmentOutcome req = new AugmentOutcome(71L, "aug_071", true, "/storage/augment/71.mp4");

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 쓰기 이전에 종결한다 — 트랜잭션을 오염(rollback-only)시키지 않는다.
        verify(augRepository, never()).save(any(LsDataAug.class));
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        assertThat(target.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
    }

    /**
     * 선점 검사를 통과한 뒤에도 <b>동시</b> 다른 트랜잭션이 먼저 커밋하면 UNIQUE 위반이 난다. 이때
     * 남은 잔여 경로에서 <b>같은 트랜잭션 재조회를 절대 하지 않는다</b>(PG 25P02 회귀 가드) — 소유자
     * 판별은 REQUIRES_NEW 독립 트랜잭션({@link AugmentJobIdOwnerLookup})에서만 한다.
     */
    @Test
    @DisplayName("UNIQUE_충돌_후에도_같은_트랜잭션의_후속_쿼리가_실패하지_않는다")
    void uniqueViolation_doesNotRequeryInPoisonedTransaction() throws Exception {
        LsDataAug target = newAug(50L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(50L)).thenReturn(Optional.of(target));
        when(augRepository.save(any(LsDataAug.class)))
                .thenThrow(new DataIntegrityViolationException("UNIQUE violation"));
        when(jobIdOwnerLookup.findOwnerDataAugSn("aug_050")).thenReturn(Optional.of(51L));

        AugmentOutcome req = new AugmentOutcome(
                50L, "aug_050", true, "/storage/augment/50.mp4");

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 조회는 쓰기 이전 선점 검사 1회뿐 — 위반 이후 재조회는 독립 트랜잭션 컴포넌트로만 나간다.
        verify(augRepository, times(1)).findByExternalJobId("aug_050");
        verify(jobIdOwnerLookup, times(1)).findOwnerDataAugSn("aug_050");
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

        boolean applied = service.handle(req).applied();

        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        LsDataRaw newRaw = rawCaptor.getValue();
        assertThat(newRaw.getOrgnlRawSn()).isEqualTo(100L);
        // Phase 11 — 동기 커밋 직후엔 PENDING·deIdntfYn='N' (비식별 완료 불변식은 async 성공 후에만).
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N");
        // 파생영상의 경로는 <파생 자신의 비식별 사본> 경로다(외부가 준 경로도, 부모 원본도 아니다).
        assertThat(newRaw.getRawFilePathNm())
                .isEqualTo(expectedDerivativeVideoPath(100L, newRaw.getRawSn(), "WINTER"));

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
    @DisplayName("증강_콜백의_rawFilePathNm_이_허용루트_하위면_정상_처리된다")
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
        boolean applied = service.handle(req).applied();

        // then
        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        // 외부가 준 경로는 검증만 통과하고 쓰기 base 로 승격되지 않는다 — 파생 자신의 비식별 사본 경로가 적재된다.
        assertThat(rawCaptor.getValue().getRawFilePathNm())
                .isEqualTo(expectedDerivativeVideoPath(190L, rawCaptor.getValue().getRawSn(), "WINTER"));
    }

    @Test
    @DisplayName("파생영상에_부모의_비식별_이전_원본경로가_기록되지_않는다")
    void derivativeNeverRecordsParentOriginalPath() throws Exception {
        // given — 외부 시스템이 공백(" ") 경로를 보내는 통상 케이스(증강 AI 는 영상을 재생성하지 않는다).
        //         구 구현은 이때 부모의 RAW_FILE_PATH_NM(비식별 이전 원본 NAS 경로)으로 폴백했다.
        LsDataRaw parentRaw = newRaw(195L); // 부모 원본 경로 = /storage/raw/195.mp4
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
        boolean applied = service.handle(req).applied();

        // then — 부모 원본 경로는 절대 실리지 않고(CWE-359), 파생 자신의 비식별 사본 경로가 적재된다.
        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        LsDataRaw newRaw = rawCaptor.getValue();
        assertThat(newRaw.getRawFilePathNm()).isNotEqualTo(parentRaw.getRawFilePathNm());
        assertThat(newRaw.getRawFilePathNm())
                .isEqualTo(expectedDerivativeVideoPath(195L, newRaw.getRawSn(), "WINTER"));
        // 산출물 co-locate base(dirname)도 파생 전용 디렉터리라 export 가 파생 트리에 생성된다(B 요구).
        assertThat(newRaw.getRawFilePathNm()).contains("/videos/augment/195/" + newRaw.getRawSn() + "/");
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

    /**
     * E-ISSUE-11 회귀 가드 — PII 게이트는 <b>보류</b>다. 상태를 종결시키면 재콜백이 멱등 스킵되어
     * 그 증강이 영구 유실된다(구 구현: ACCEPTED + applied=true + 신규 영상 0건).
     */
    @Test
    @DisplayName("PII_보류된_증강은_ACCEPTED_로_종결되지_않는다")
    void parentDeidentReported_withholdsWithoutTerminatingState() throws Exception {
        LsDataRaw parentRaw = newNonDeidentRaw(140L, "F");
        LsDataSrc originSrc = newSrc(710L, 140L, 0);
        LsDataAug aug = newAugWithSrc(71L, 710L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(71L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(710L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(140L)).thenReturn(Optional.of(parentRaw));

        AugmentOutcome req = new AugmentOutcome(
                71L, "aug_071", true, "/storage/augment/71.mp4");

        AugmentApplyResult result = service.handle(req);

        assertThat(result).isEqualTo(AugmentApplyResult.WITHHELD_PARENT_NOT_DEIDENTIFIED);
        assertThat(result.applied()).isFalse();
        assertThat(result.withheld()).isTrue();
        assertThat(aug.getAugProcSttsCd())
                .as("보류는 종결이 아니다 — PENDING 을 유지해야 신고 해소 시 재개될 수 있다")
                .isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(aug.getDeadLetterAt())
                .as("정책 보류는 실패가 아니므로 dead-letter 를 찍지 않는다")
                .isNull();
        verify(augRepository, never()).save(any(LsDataAug.class));
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    /**
     * 조상 체인 회귀 가드 — 부모가 <b>파생영상</b>이고 그 <b>조상</b>이 신고 중인 경우.
     *
     * <p>부모 행 자체는 {@code 'Y'} 라 구 판정(자기 행만 확인)은 통과시켰고, 그 결과 조상의 마스킹 실패
     * 픽셀을 담은 부모 비식별 프레임으로 <b>새 증강 산출물이 디스크에 생성</b>됐다(CWE-359 fail-open).
     * 판정을 조상 체인 단일 원천에 위임해 보류로 전환한다.
     */
    @Test
    @DisplayName("부모는_Y_라도_조상이_신고중이면_증강본을_만들지_않고_보류한다")
    void ancestorUnderReport_withholdsEvenWhenParentFlagIsY() throws Exception {
        LsDataRaw parentRaw = newRaw(142L); // 파생영상 — 자기 행은 'Y'
        LsDataSrc originSrc = newSrc(712L, 142L, 0);
        LsDataAug aug = newAugWithSrc(80L, 712L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(80L)).thenReturn(Optional.of(aug));
        when(srcRepository.findById(712L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(142L)).thenReturn(Optional.of(parentRaw));
        // 조상(원본)이 아직 'F' — 체인 판정 단일 원천이 차단을 알린다.
        when(deidentReportGate.isUnderDeidentReportLocked(142L)).thenReturn(true);

        AugmentApplyResult result = service.handle(
                new AugmentOutcome(80L, "aug_080", true, "/storage/augment/80.mp4"));

        assertThat(result).isEqualTo(AugmentApplyResult.WITHHELD_PARENT_NOT_DEIDENTIFIED);
        assertThat(aug.getAugProcSttsCd())
                .as("보류는 종결이 아니다 — 조상 해소 팬아웃 이벤트로 재개된다")
                .isEqualTo(LsDataAug.STTS_PENDING);
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    @Test
    @DisplayName("PII_보류시_응답이_applied_false_와_사유를_담는다")
    void withheldResult_carriesAppliedFalseAndReason() throws Exception {
        LsDataRaw parentRaw = newNonDeidentRaw(141L, "F");
        LsDataSrc originSrc = newSrc(711L, 141L, 0);
        LsDataAug aug = newAugWithSrc(79L, 711L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(79L)).thenReturn(Optional.of(aug));
        when(srcRepository.findById(711L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(141L)).thenReturn(Optional.of(parentRaw));

        AugmentApplyResult result = service.handle(
                new AugmentOutcome(79L, "aug_079", true, null));

        assertThat(result.applied()).isFalse();
        assertThat(result.reasonCode())
                .as("외부가 '정상 인계' 로 오해하지 않도록 사유 코드를 회신해야 한다")
                .isEqualTo("WITHHELD_PARENT_NOT_DEIDENTIFIED");
        // 사유는 고정 코드값만 — 내부 경로/식별정보가 새면 CWE-209.
        assertThat(result.reasonCode()).doesNotContain("/");
    }

    @Test
    @DisplayName("증강본_생성후_프레임러너가_새_RAW_SN으로_트리거된다")
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

        ArgumentCaptor<Long> frameCaptor = ArgumentCaptor.forClass(Long.class);
        verify(asyncAugmentFrameRunner, times(1)).runAsync(frameCaptor.capture(), eq(72L));
        assertThat(frameCaptor.getValue()).isNotEqualTo(160L); // 부모가 아닌 신규 영상 SN
    }

    @Test
    @DisplayName("사본_생성_전에는_probe_하지_않는다_메타러너는_콜백에서_기동되지_않는다")
    void handle_doesNotStartVideoMetaRunnerInParallel() throws Exception {
        // given — 정상 성공 콜백
        LsDataRaw parentRaw = newRaw(165L);
        LsDataSrc originSrc = newSrc(725L, 165L, 0);
        LsDataAug aug = newAugWithSrc(78L, 725L, "RAIN");

        when(augRepository.findByDataAugSnForUpdate(78L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(725L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(165L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(165L)).thenReturn(List.of(originSrc));

        AugmentOutcome req = new AugmentOutcome(78L, "aug_078", true, " ");

        // when
        service.handle(req);

        // then — 기술메타 러너는 이 서비스가 알지도 못한다(의존성 자체 제거). 사본을 만드는 프레임 러너만
        //        기동되고, 메타 추출은 그 러너가 확정 성공 후에 트리거한다(레이스 제거).
        assertThat(java.util.Arrays.stream(AugmentResultService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .doesNotContain(AsyncVideoMetaRunner.class.getSimpleName());
        verify(asyncAugmentFrameRunner, times(1)).runAsync(anyLong(), eq(78L));
    }

    /**
     * 부모 프레임 0건은 <b>재개 트리거가 없는 데이터 이상</b>이다. 보류로 두면 아무도 깨우지 못하는
     * PENDING 고착이 되므로 실패로 확정해 집계(FAILED)에 드러낸다(Phase 8-B).
     */
    @Test
    @DisplayName("부모_프레임이_없으면_실패로_확정되고_영상과_프레임러너_미트리거")
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

        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.getDeadLetterAt()).isNotNull();
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
    @DisplayName("Phase11_증강_SUCCESS_originSrc_미존재_시_실패확정되고_신규영상_미생성")
    void successButOriginSrcNotFound_skipsVideoCreation() throws Exception {
        LsDataAug aug = newAugWithSrc(30L, 9999L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(30L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(9999L)).thenReturn(Optional.empty());

        AugmentOutcome req = new AugmentOutcome(
                30L, "aug_030", true, "/storage/augment/winter.mp4");

        boolean applied = service.handle(req).applied();

        assertThat(applied).isTrue();
        // 구 구현은 ACCEPTED 로 종결해 "영상 없는 성공" 을 만들었다. 재개 트리거가 없는 데이터 이상이므로
        // 실패로 확정해 집계(FAILED)에 드러낸다.
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.getDeadLetterAt()).isNotNull();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(asyncAugmentFrameRunner, never()).runAsync(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Phase11_증강_SUCCESS_parentRaw_미존재_시_실패확정되고_신규영상_미생성")
    void successButParentRawNotFound_skipsVideoCreation() throws Exception {
        LsDataSrc originSrc = newSrc(400L, 8888L, 0);
        LsDataAug aug = newAugWithSrc(31L, 400L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(31L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(400L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(8888L)).thenReturn(Optional.empty());

        AugmentOutcome req = new AugmentOutcome(
                31L, "aug_031", true, "/storage/augment/night.mp4");

        boolean applied = service.handle(req).applied();

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.getDeadLetterAt()).isNotNull();
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

        assertThat(service.handle(req).applied()).isTrue();
        assertThat(service.handle(req).applied()).isFalse(); // 종결 행 재전송 → 멱등 스킵

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
