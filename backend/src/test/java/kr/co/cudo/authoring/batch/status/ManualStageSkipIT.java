package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REVIEWER 수동 <b>작업 묶음</b> 스킵 표식의 저장·판정 통합 테스트 (실 DB). [@design API-198] [@design API-200]
 *
 * <p>표식은 개별 단계가 아니라 묶음 코드({@code VLM}/{@code AUTOLABEL})로 저장되며, 한 행이 한 묶음의
 * 결정을 통째로 담아 <b>부분 상태가 표현 자체로 불가능</b>하다.
 *
 * <p>여기서 지키는 것은 셋이다.
 * <ol>
 *   <li><b>사람의 결정이 이벤트에 뒤집히지 않는다</b> — 수동 스킵 사유가 재개 판정
 *       ({@code RESUMABLE_SKIP_REASONS})에 걸리면 안 된다. 사용자가 재개 사유 문자열을
 *       <b>그대로 입력해도</b> 걸리지 않아야 한다(접두 강제).</li>
 *   <li><b>화면 단계 표시가 깨지지 않는다</b> — 표식 행이 진행 축의 "최신 행"이 되어 단계 표시를
 *       바꿔버리면 안 된다(해제 행 때문에 그 단계가 "진행 중"으로 보이는 결함).</li>
 *   <li><b>스킵→해제→재스킵이 반복돼도 판정이 흔들리지 않는다.</b></li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class ManualStageSkipIT {

    @Autowired private BatchStatusService statusService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long seededRawSn;

    @AfterEach
    void cleanSeededVideo() {
        if (seededRawSn != null) {
            RawVideoFixture.deleteRaws(jdbcTemplate, seededRawSn);
            seededRawSn = null;
        }
    }

    private Long newRaw() {
        seededRawSn = RawVideoFixture.newRaw(jdbcTemplate);
        return seededRawSn;
    }

    @Test
    @DisplayName("수동_스킵_표식이_서면_그_묶음이_스킵으로_판정된다")
    void skipMarkerMakesBundleSkipped() {
        // given
        Long rawSn = newRaw();
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.VLM)).isFalse();

        // when
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");

        // then
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.VLM)).isTrue();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isTrue();
        // 다른 묶음은 영향받지 않는다.
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.AUTOLABEL)).isFalse();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.YOLO)).isFalse();
    }

    @Test
    @DisplayName("★★오토라벨_표식_한_행이_구성_단계_셋을_모두_건너뛰게_한다_보간_포함")
    void oneAutolabelMarkerCoversAllThreeStages() {
        // 표식은 한 행뿐인데 게이트는 세 단계에서 참이어야 한다 — 그래야 부분 상태가 생기지 않는다.
        //   특히 보간이 포함돼야 어떤 재수행에서도 보간이 무조건 도는 사고가 구조적으로 사라진다.
        Long rawSn = newRaw();

        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "AI 서버 점검", "1");

        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.YOLO)).isTrue();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.SAM2)).isTrue();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.INTERPOLATE)).isTrue();
        // 전제 단계·다른 묶음은 건너뛸 수 없다.
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.FRAME_EXTRACT)).isFalse();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.MARKING)).isFalse();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isFalse();
    }

    @Test
    @DisplayName("★★오토라벨_해제도_한_행이라_구성_단계_셋이_함께_풀린다_일부만_남지_않는다")
    void oneAutolabelClearReleasesAllThreeStages() {
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "AI 서버 점검", "1");

        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "1");

        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.YOLO)).isFalse();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.SAM2)).isFalse();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.INTERPOLATE)).isFalse();
    }

    @Test
    @DisplayName("★★묶음_표식은_화면_단계표시와_현재단계_판정을_오염시키지_않는다_실재하지_않는_단계코드여도")
    void bundleMarkerNeverReachesProgressAxis() {
        // 저장 축의 안전 근거를 실 DB 로 고정한다 — PROC_STEP_CD 에 BatchStage 에 없는 값(AUTOLABEL)이
        //   들어가지만, 진행 조회가 PROC_STTS_CD='SKIPPED' 를 제외하므로 currentStage 의
        //   BatchStage.valueOf 에 도달하지 않는다. 이 성질이 깨지면 영상 상세가 통째로 500 이 된다.
        Long rawSn = newRaw();
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);

        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "AI 서버 점검", "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "1");

        assertThat(statusService.currentStage(rawSn)).isEqualTo(BatchStage.FRAME_EXTRACT);
        assertThat(statusService.stagesFor(rawSn, false)).isNotEmpty();
        // 실패가 아니므로 실패 사유도 생기지 않는다(표식이 사유 축으로도 새지 않는다).
        assertThat(statusService.failureReasonFor(rawSn)).isNull();
    }

    @Test
    @DisplayName("스킵_해제_재스킵을_반복해도_마지막_표식이_판정을_결정한다")
    void skipClearSkipCycleIsStable() {
        // given
        Long rawSn = newRaw();

        // when / then — 스킵 → 해제 → 재스킵 → 재해제
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "1차", "1");
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.AUTOLABEL)).isTrue();

        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "1");
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.AUTOLABEL)).isFalse();

        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "2차", "2");
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.AUTOLABEL)).isTrue();

        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "2");
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.AUTOLABEL)).isFalse();
    }

    @Test
    @DisplayName("★수동_스킵_표식은_화면_단계표시를_바꾸지_않는다")
    void markerDoesNotDisturbStageProgress() {
        // given — 파이프라인이 FRAME_EXTRACT 진행 중
        Long rawSn = newRaw();
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);

        // when — 그 뒤에 스킵·해제 표식이 연달아 적재된다(표식이 "가장 최신 행"이 되는 상황)
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "분할 생략", "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "1");

        // then — 진행 단계·단계 표시는 FRAME_EXTRACT 그대로다(표식이 진행 축을 오염시키지 않는다).
        assertThat(statusService.currentStage(rawSn)).isEqualTo(BatchStage.FRAME_EXTRACT);
        assertThat(statusService.stagesFor(rawSn, false))
                .anySatisfy(s -> {
                    assertThat(s.name()).isEqualTo(BatchStage.FRAME_EXTRACT.name());
                    assertThat(s.status()).isEqualTo(BatchStageProgressMapper.PROGRESS);
                })
                .noneSatisfy(s -> {
                    assertThat(s.name()).isEqualTo(BatchStage.SAM2.name());
                    assertThat(s.status()).isEqualTo(BatchStageProgressMapper.PROGRESS);
                });
    }

    @Test
    @DisplayName("★수동_스킵은_VLM_재개판정에_걸리지_않는다_사용자가_재개사유를_그대로_입력해도")
    void manualSkipIsNeverResumable() {
        // given — 악의적·우연한 입력: 사용자가 재개 사유 상수를 그대로 사유에 적는다.
        Long rawSn = newRaw();
        String hostileReason = VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT;

        // when — 서비스가 접두를 강제로 붙여 저장한다(BatchStageSkipService 와 같은 규약).
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + hostileReason, "1");

        // then — 재개 판정(ERR_MSG_CN 정확 일치)에 걸리지 않는다.
        //   걸렸다면 비식별 신고 해소 이벤트가 이 행을 집어 사람의 결정을 뒤집고 외부로 재위탁한다.
        assertThat(statusService.isStageSkippedWithAnyReason(
                rawSn, BatchStage.VLM, VlmTimeseriesStep.RESUMABLE_SKIP_REASONS))
                .as("수동 스킵 행이 재개 대상으로 잡히면 안 된다")
                .isFalse();
        // 반대로 수동 스킵 판정은 정상 동작해야 한다.
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isTrue();
    }

    @Test
    @DisplayName("★수동_스킵_전용_ERR_CD는_재개대상_사유목록에_포함되지_않는다")
    void manualMarkerConstantsAreNotResumable() {
        // 정적 가드 — 누군가 상수를 재개 목록에 끼워 넣으면 즉시 실패한다.
        assertThat(VlmTimeseriesStep.RESUMABLE_SKIP_REASONS)
                .doesNotContain(ManualStageSkip.ERR_CD_SKIPPED, ManualStageSkip.ERR_CD_CLEARED);
        assertThat(VlmTimeseriesStep.RESUMABLE_SKIP_REASONS)
                .as("어떤 재개 사유도 수동 스킵 접두로 시작하면 안 된다(접두 강제가 무력화된다)")
                .noneMatch(reason -> reason.startsWith(ManualStageSkip.REASON_PREFIX));
    }

    @Test
    @DisplayName("스킵_목록은_없으면_빈_배열이고_스킵하면_선언_순서로_담긴다")
    void skippedBundleListIsOrderedAndEmptyByDefault() {
        // given
        Long rawSn = newRaw();
        assertThat(statusService.manuallySkippedBundles(rawSn)).isEmpty();

        // when — 일부러 선언 역순으로 스킵한다(반환 순서가 적재 순서에 끌려가면 안 된다).
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "오토라벨 생략", "1");
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");

        // then — 고정 순서(VLM → AUTOLABEL)
        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("VLM", "AUTOLABEL");
    }

    @Test
    @DisplayName("★해제한_묶음은_스킵_목록에서_빠진다")
    void clearedBundleLeavesTheList() {
        // given
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "오토라벨 생략", "1");
        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("VLM", "AUTOLABEL");

        // when
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "1");

        // then — 해제 행이 append 됐을 뿐인데도 목록에서 빠져야 한다(마지막 표식 판정).
        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("AUTOLABEL");
    }

    @Test
    @DisplayName("★표식이_여러_번_쌓인_묶음이_있어도_다른_묶음의_마지막_표식이_밀려나지_않는다")
    void heavyTogglingDoesNotHideOtherBundles() {
        // given — 오토라벨을 먼저 스킵해 두고, 시계열을 여러 번 토글해 표식 행을 잔뜩 쌓는다.
        //   "최근 N건"으로 잘라 읽는 구현이었다면 오토라벨의 마지막 표식이 목록 밖으로 밀려 사라진다.
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "오토라벨 생략", "1");
        for (int i = 0; i < 12; i++) {
            statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                    ManualStageSkip.REASON_PREFIX + "토글 " + i, "1");
            statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.VLM,
                    ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "1");
        }

        // when / then — 시계열은 마지막이 해제라 빠지고, 오토라벨은 그대로 남는다.
        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("AUTOLABEL");
    }

    @Test
    @DisplayName("★긴_행위자_식별자도_컬럼_폭을_넘지_않아_저장에_실패하지_않는다")
    void longActorFitsColumn() {
        // given — REG_ID 는 VARCHAR(30) 이다. 30자를 넘는 행위자 값이 들어와도 INSERT 가 터지면 안 된다.
        //   (서비스가 정제 후 하드 절단하므로 여기서는 컬럼 폭 자체를 실 DB 로 고정한다.)
        Long rawSn = newRaw();
        String thirtyChars = "a".repeat(30);

        // when
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "사유", thirtyChars);

        // then
        assertThat(statusService.latestManualSkipMarker(rawSn, BatchStageBundle.AUTOLABEL))
                .hasValueSatisfying(l -> assertThat(l.getRegId()).isEqualTo(thirtyChars));
    }

    // ────────────────────────────────────────────────────────────────────────
    // ★ 건너뛴 이력 · 해제 목록 판정 — 묶음 지목 재수행의 수락 전제
    //   [@design API-201] [@design API-043] [@design ADR-050]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★스킵_이력이_없으면_재수행을_수락하지_않고_해제_목록도_비어_있다")
    void noSkipHistoryMeansNothingRestored() {
        Long rawSn = newRaw();
        assertThat(statusService.hasManualSkipHistory(rawSn, BatchStageBundle.VLM)).isFalse();
        assertThat(statusService.clearedBundles(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("★★스킵만_서_있어도_재수행을_수락한다_해제_2단계_폐지")
    void skippedButNotClearedIsStillAccepted() {
        // 구 판정은 <마지막 표식이 해제>인 묶음만 수락해 「해제 → 재수행」 두 번을 누르게 했다.
        //   되살리려는 사람에게 그 둘은 한 가지 일이고, 나누어 두면 해제만 하고 재수행을 잊었을 때
        //   그 영상이 「건너뛰지도 수행하지도 않은」 상태로 남는다. (구 동작 폐기 — ADR-050)
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");

        assertThat(statusService.hasManualSkipHistory(rawSn, BatchStageBundle.VLM)).isTrue();
        // 다만 「지금 건너뛴 상태」와 「해제됨」은 여전히 갈린다 — 두 목록은 서로의 뒷면이다.
        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("VLM");
        assertThat(statusService.clearedBundles(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("★스킵을_해제하면_해제_목록으로_옮겨간다_스킵_목록에서는_빠진다")
    void clearedSkipMovesToClearedList() {
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.MANUAL_CLEARED_REASON, "1");

        assertThat(statusService.hasManualSkipHistory(rawSn, BatchStageBundle.VLM)).isTrue();
        // 같은 축의 앞뒷면 — 해제된 묶음은 스킵 목록에서 빠지고 해제 목록에 들어간다.
        assertThat(statusService.manuallySkippedBundles(rawSn)).isEmpty();
        assertThat(statusService.clearedBundles(rawSn)).containsExactly("VLM");
    }

    @Test
    @DisplayName("★재수행이_자동으로_푼_해제도_같은_해제_목록에_담긴다_사유_본문으로만_갈린다")
    void rerunAutoClearIsAlsoCleared() {
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.RERUN_AUTO_CLEARED_REASON, "SYSTEM_RERUN");

        assertThat(statusService.clearedBundles(rawSn)).containsExactly("VLM");
        // 코드값은 사람이 누른 해제와 같다(상태 판정의 키라 새로 만들지 않는다) — 갈리는 것은 사유뿐.
        assertThat(statusService.latestManualSkipMarker(rawSn, BatchStageBundle.VLM))
                .hasValueSatisfying(l -> {
                    assertThat(l.getErrorCd()).isEqualTo(ManualStageSkip.ERR_CD_CLEARED);
                    assertThat(l.getErrorMsg()).isEqualTo(ManualStageSkip.RERUN_AUTO_CLEARED_REASON);
                });
    }

    @Test
    @DisplayName("★재스킵하면_해제_목록에서_빠진다_마지막_표식이_판정을_결정한다")
    void reSkippingRevokesRestoredState() {
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "1차", "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.MANUAL_CLEARED_REASON, "1");
        assertThat(statusService.clearedBundles(rawSn)).containsExactly("AUTOLABEL");

        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "2차", "1");

        assertThat(statusService.clearedBundles(rawSn)).isEmpty();
        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("AUTOLABEL");
        // 재수행 수락은 그대로다 — 넓어진 판정은 「건너뛴 적이 있는가」이므로 재스킵에도 흔들리지 않는다.
        assertThat(statusService.hasManualSkipHistory(rawSn, BatchStageBundle.AUTOLABEL)).isTrue();
    }

    @Test
    @DisplayName("★판정은_묶음별로_독립이다_한_묶음의_표식이_다른_묶음의_재수행을_열어주지_않는다")
    void restoredJudgementIsPerBundle() {
        // 요청이 대상 묶음을 자유롭게 고르지 못하게 하는 장치다 — "아무 묶음이나 하나 건드리면
        //   다른 묶음도 재수행할 수 있다"가 되면 앞 작업을 건너뛰도록 요청이 강제할 수 있다.
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.MANUAL_CLEARED_REASON, "1");

        assertThat(statusService.hasManualSkipHistory(rawSn, BatchStageBundle.VLM)).isTrue();
        assertThat(statusService.hasManualSkipHistory(rawSn, BatchStageBundle.AUTOLABEL)).isFalse();
        assertThat(statusService.clearedBundles(rawSn)).containsExactly("VLM");
        assertThat(statusService.manuallySkippedBundles(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("★두_목록은_묶음_선언_순서로_내려온다_적재_순서에_끌려가지_않는다")
    void bothListsKeepDeclarationOrder() {
        Long rawSn = newRaw();
        // 선언 역순으로 적재한다 — 반환 순서가 적재 순서에 끌려가면 화면이 깜빡인다.
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.REASON_PREFIX + "오토라벨 생략", "1");
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("VLM", "AUTOLABEL");

        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.AUTOLABEL,
                ManualStageSkip.MANUAL_CLEARED_REASON, "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.RERUN_AUTO_CLEARED_REASON, "SYSTEM_RERUN");
        assertThat(statusService.clearedBundles(rawSn)).containsExactly("VLM", "AUTOLABEL");
        assertThat(statusService.manuallySkippedBundles(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("수동_스킵_표식은_사유와_행위자를_함께_남긴다")
    void markerRecordsReasonAndActor() {
        // given / when
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "reviewer-7");

        // then
        assertThat(statusService.latestManualSkipMarker(rawSn, BatchStageBundle.VLM))
                .hasValueSatisfying(l -> {
                    assertThat(l.getErrorCd()).isEqualTo(ManualStageSkip.ERR_CD_SKIPPED);
                    assertThat(l.getErrMsg()).contains("벤더 장애");
                    assertThat(l.getRegId()).isEqualTo("reviewer-7");
                });
    }
}
