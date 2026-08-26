package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「지금 조치가 필요한 묶음」 판정({@link BatchStatusService#clearedBundlesNeedingAction})의 통합 검증.
 * [@design API-043] [@design SCREEN-009] [@design AC-051] [@design ADR-050]
 *
 * <h3>이 시험이 막는 것</h3>
 * <p>표식은 <b>append-only 감사 행</b>이라 지워지지 않는다. 재수행이 성공해도 재수행 자신이 남긴 해제
 * 표식이 계속 마지막이므로, 표식 축을 그대로 화면에 실으면 <b>재수행에 성공한 영상마다</b> 배너가
 * 영구히 잔존한다(화면은 조치가 필요하다는데 실제로는 끝나 있다).
 *
 * <h3>동시에 지키는 반대 방향</h3>
 * <p>산출물이 <b>없는</b> 해제 묶음은 그대로 남아야 한다 — 재수행을 아직 하지 않았거나 실패해 원상
 * 복구된 영상에서 <b>다시 누를 창구가 사라지면 안 된다</b>. 배너를 없애는 방향의 과잉 수정이 이 축의
 * 가장 큰 리스크다.
 *
 * <p>그리고 <b>감사 축은 변하지 않는다</b> — {@code clearedBundles}·{@code manuallySkippedBundles} 의
 * 답이 함께 좁아지면 "표식이 어떻게 서 있나"를 물을 수단 자체가 사라진다.
 *
 * <h3>제외 판정은 두 축의 OR 이며 둘 다 필요하다</h3>
 * <p><b>산출물 보유</b>는 최초 배치에서 이미 만들어진 경우를 잡고, <b>해제 이후 배치 종결</b>은
 * <b>재수행이 완주한 경우(검출 0건 포함)</b>를 잡는다. 두 번째 축이 없으면 「AI 가 정상 수행했는데
 * 아무것도 검출하지 못한 영상」이 자동 라벨 0건이라 영구히 조치 필요로 남는다 — 검출 0건은 정상
 * 결과이지 실패가 아니다. 여기서는 <b>두 축이 서로 독립으로 동작함</b>을 각각 보인다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ClearedBundleActionIT {

    @Autowired private BatchStatusService statusService;
    @Autowired private BundleArtifactPresenceRegistry artifactPresenceRegistry;
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

    /** 시계열 <b>산출물</b>(서술 메타) 1건 — 위탁이 실제로 결과를 남긴 상태. */
    private void seedTimeseriesMeta(Long rawSn) {
        jdbcTemplate.update("""
                INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, REG_DT)
                VALUES (?, 'vlm.description', '영상 서술', CURRENT_TIMESTAMP)
                """, rawSn);
    }

    /** 기술메타 — 배치 직후 대다수 영상이 갖는 상태. <b>시계열 산출물이 아니다.</b> */
    private void seedTechnicalMeta(Long rawSn) {
        jdbcTemplate.update("""
                INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, REG_DT)
                VALUES (?, 'video.resolution', '1920x1080', CURRENT_TIMESTAMP)
                """, rawSn);
    }

    private void skipThenClear(Long rawSn, BatchStageBundle bundle) {
        statusService.recordManualStageSkip(rawSn, bundle,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        statusService.recordManualStageSkipCleared(rawSn, bundle,
                ManualStageSkip.RERUN_AUTO_CLEARED_REASON, "SYSTEM_RERUN");
    }

    /**
     * 파이프라인이 <b>종결</b>까지 갔음을 진행 행에 남긴다 — 재수행 완주의 유일한 흔적이다.
     *
     * <p>★ 진행 행을 <b>먼저 세우고</b> 종결시킨다. {@code markCompleted} 는 진행 행이 없으면
     * {@code LsBatchProcLog.create} 로 새 행을 만드는데 그 팩토리는 처리상태를 {@code 'STARTED'} 로
     * 두므로(단계값만 COMPLETED) <b>종결로 판정되지 않는다</b>. 실제 파이프라인은 항상 앞 단계를
     * 거치며 행을 만들어 두고 마지막에 갱신하므로, 여기서도 그 순서를 그대로 재현한다 — 안 그러면
     * 이 시험이 <b>운영에 없는 상태</b>를 검증하게 된다.
     */
    private void markPipelineCompleted(Long rawSn) {
        statusService.markStage(rawSn, BatchStage.DEIDENTIFY);   // 진행 행 생성(STARTED)
        statusService.markCompleted(rawSn);                      // 같은 행을 COMPLETED 로 갱신
    }

    /**
     * 재수행이 <b>실패</b>로 끝났음을 진행 행에 남긴다 — {@code PROC_STTS_CD='FAILED'} + 새 시각.
     *
     * @param failedStage 실패한 단계. 묶음 소속 단계면 {@code failedStages} 가 구제해 주지만,
     *                    {@code MARKING} 처럼 <b>어느 묶음에도 속하지 않는</b> 단계면 구제받지 못한다.
     */
    private void markPipelineFailedAt(Long rawSn, BatchStage failedStage) {
        statusService.markStage(rawSn, failedStage);             // 진행 행 생성/갱신(STARTED)
        statusService.markFailed(rawSn, new IllegalStateException("rerun boom"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 조치 필요 판정 — 산출물이 있으면 빠지고, 없으면 남는다
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ★★이 라운드의 핵심 — 사용자가 신고한 화면이 정확히 이 상태였다(시계열은 실제로 완주했는데
     * 배너가 「조치 필요」를 주장했다).
     */
    @Test
    @DisplayName("★★시계열_메타를_보유하면_해제된_VLM_묶음이_조치_필요_목록에서_빠진다")
    void clearedBundleWithArtifactIsNotActionable() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.VLM);
        seedTimeseriesMeta(rawSn);

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).isEmpty();
    }

    /**
     * ★반대 방향 — 재수행을 아직 하지 않았거나 실패한 영상에서 <b>다시 누를 창구가 사라지면 안 된다</b>.
     * 이 단언이 배너를 없애는 방향의 과잉 수정을 막는다.
     */
    @Test
    @DisplayName("★산출물이_없으면_해제된_묶음은_그대로_남는다_재수행_창구_보존")
    void clearedBundleWithoutArtifactStaysActionable() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.VLM);

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("VLM");
    }

    /**
     * ★기술메타({@code video.*})는 시계열 산출물이 아니다 — 전체 메타 카운트로 판정하면 배치 직후
     * 대다수 영상이 "산출물 있음"으로 오산입돼 조치가 필요한 영상이 통째로 숨는다.
     */
    @Test
    @DisplayName("★기술메타만_있으면_산출물로_치지_않는다_전체_카운트_판정_금지")
    void technicalMetaIsNotATimeseriesArtifact() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.VLM);
        seedTechnicalMeta(rawSn);

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("VLM");
    }

    @Test
    @DisplayName("표식이_없으면_조치_필요_목록도_비어_있다")
    void noMarkerMeansNothingActionable() {
        Long rawSn = newRaw();

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 종결 축 — 재수행이 완주했으면 산출물이 0건이어도 조치가 끝난 것이다
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ★★<b>이번 확장의 존재 이유</b> — AI 가 정상 수행했는데 아무것도 검출하지 못한 영상이다.
     * 검출 0건은 <b>정상 결과</b>이므로 실패처럼 취급하면 안 되는데, 산출물 축만으로 판정하면 자동
     * 라벨이 0건이라 그 영상이 영구히 「조치 필요」로 남는다.
     */
    @Test
    @DisplayName("★★산출물이_0건이어도_해제_이후_배치가_종결했으면_조치_대상에서_빠진다_검출0건_정상완주")
    void terminatedAfterClearIsFinishedEvenWithZeroArtifacts() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);
        // 산출물(자동 생성 라벨)은 한 건도 만들지 않는다 — 검출 0건으로 완주한 상태.
        markPipelineCompleted(rawSn);

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).isEmpty();
    }

    /**
     * ★음성 케이스 — 두 축 <b>모두</b> 아니면 남는다. 이 단언이 배너를 없애는 방향의 과잉 수정을
     * 막는다(재수행을 아직 하지 않았거나 시작조차 못 한 영상의 창구).
     */
    @Test
    @DisplayName("★산출물도_없고_해제_이후_종결도_없으면_그대로_남는다_재수행_창구_보존")
    void neitherAxisMeansStillActionable() {
        Long rawSn = newRaw();
        // 해제보다 <먼저> 종결한 배치는 그 해제에 대한 조치가 아니다.
        markPipelineCompleted(rawSn);
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("AUTOLABEL");
    }

    /**
     * ★두 축의 독립 — dev rawSn=1 이 정확히 이 형태다(시계열 메타는 있고 해제 이후 종결은 없다).
     * 종결 축이 잡지 못하는 것을 산출물 축이 잡는다.
     */
    @Test
    @DisplayName("★해제_이후_종결이_없어도_산출물을_보유하면_빠진다_두_축_독립")
    void artifactAxisWorksWithoutTermination() {
        Long rawSn = newRaw();
        markPipelineCompleted(rawSn);
        skipThenClear(rawSn, BatchStageBundle.VLM);
        seedTimeseriesMeta(rawSn);

        // 종결은 해제보다 이전이라 종결 축은 false 인데, 산출물 축이 단독으로 제외시킨다.
        assertThat(statusService.progressTerminatedAfter(rawSn, LocalDateTime.now())).isFalse();
        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).isEmpty();
    }

    /** 실행 중({@code STARTED})은 종결이 아니다 — 재수행이 아직 끝나지 않았으면 창구를 남긴다. */
    @Test
    @DisplayName("★실행_중인_배치는_종결이_아니라_조치_대상으로_남는다")
    void runningPipelineIsNotFinished() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);
        statusService.markStage(rawSn, BatchStage.YOLO);   // STARTED — 종결 아님

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("AUTOLABEL");
    }

    /**
     * ⚠ <b>인지·수용한 부정확성</b> — 진행 행은 영상 단위 1행이라 어느 묶음이 재수행됐는지 구분하지
     * 못한다. 그래서 두 묶음이 동시에 해제된 상태에서 하나만 재수행해 완주하면 <b>나머지도 함께
     * 제외</b>된다. 이 시험은 그 동작을 <b>결함이 아니라 확정 사양으로 고정</b>한다 — 다음 사람이
     * 결함으로 오인해 되돌리거나, 반대로 묶음별 종결 기록을 이 화면 하나 때문에 신설하지 않도록.
     */
    @Test
    @DisplayName("⚠동기완결_묶음이_여럿이면_한쪽_완주가_나머지도_빼간다_인지수용된_부정확성")
    void completionAxisCannotTellBundlesApart() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);
        markPipelineCompleted(rawSn);

        // 진행 행은 어느 묶음이 재수행됐는지 구분해 담지 못한다 — 지금은 동기완결 묶음이 하나뿐이라
        //   실제 오적용이 일어나지 않지만, 동기완결 묶음이 둘 이상 되는 순간 드러난다.
        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).isEmpty();
        // 감사 축에는 그대로 남아 사후 추적이 가능하다(데이터 손실이 아니다).
        assertThat(statusService.clearedBundles(rawSn)).containsExactly("AUTOLABEL");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 종결 축 한정 ① — 완주만 센다. 실패는 「다시 눌러야 하는 상태」다
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ★★<b>DEV_FIX ①</b> — 재수행이 <b>실패</b>했는데 조치 완료로 세면 그 영상이 화면에서 사라져
     * 다시 누를 창구를 잃는다. 계약이 이 상태를 명시적으로 보호 대상으로 지목한다 —
     * <i>"재수행이 실패해 원상 복구된 영상이 정확히 이 상태이므로, 이 목록이 없으면 그 영상을 화면에서
     * 다시 재수행할 수 없다."</i>
     *
     * <p>구 구현은 {@code progressTerminatedAfter}(완주 ∪ <b>실패</b>)를 써서 실패까지 조치 완료로
     * 셌다. 그 메서드는 회수 스윕의 <i>"에피소드가 끝났는가"</i> 판정이라 실패 포함이 정당했고,
     * 그것을 <i>"조치가 끝났는가"</i> 로 재사용하며 의미가 어긋난 것이다.
     */
    @Test
    @DisplayName("★★재수행이_실패하면_조치_대상으로_남는다_실패는_완주가_아니다")
    void failedRerunStaysActionable() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);
        markPipelineFailedAt(rawSn, BatchStage.YOLO);

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("AUTOLABEL");
        // 실패 축이 완주 축과 갈린다는 사실 자체를 못 박는다 — 종결 판정은 참인데 완주 판정은 거짓이다.
        LocalDateTime beforeFailure = LocalDateTime.now().minusMinutes(1);
        assertThat(statusService.progressTerminatedAfter(rawSn, beforeFailure)).isTrue();
        assertThat(statusService.progressCompletedAfter(rawSn, beforeFailure)).isFalse();
    }

    /**
     * ★★<b>DEV_FIX ①-2</b> — 실패 단계가 <b>어느 묶음에도 속하지 않을 때</b>도 남아야 한다.
     *
     * <p>{@code MARKING} 은 컨텍스트 적재기라 어떤 묶음 재수행에서도 꺼지지 않고, 동시에 어느 묶음에도
     * 속하지 않는다. 그래서 거기서 실패하면 {@code failedStages} 가 그 묶음을 잡아 주지 <b>못한다</b> —
     * 이 목록마저 비면 그 영상은 건너뜀·해제·실패 <b>세 목록 어디에도 없어</b> 배너 행과 재수행 버튼이
     * 통째로 사라진다. <b>이 시험이 없어서 결함이 뚫렸다.</b>
     */
    @Test
    @DisplayName("★★실패_단계가_묶음_밖이어도_남는다_failedStages가_못_잡는_구간")
    void failureOutsideAnyBundleStillStaysActionable() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);
        markPipelineFailedAt(rawSn, BatchStage.MARKING);   // 어느 묶음에도 속하지 않는 단계

        // 이 목록이 유일한 구제 수단이다.
        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("AUTOLABEL");
        // 전제 확인 — 실패 단계가 묶음 밖이라 묶음 실패 판정이 서지 않는다.
        assertThat(statusService.isBundleProgressFailed(rawSn, BatchStageBundle.AUTOLABEL)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 종결 축 한정 ② — 논블로킹 위탁 묶음(VLM)에는 붙이지 않는다
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ★★<b>DEV_FIX ②</b> — 시계열 위탁은 <b>논블로킹 제출</b>이라 파이프라인 완주는 「보냈다」일 뿐이다.
     * 종결 축을 붙이면 <b>벤더가 답하기 전에</b> 조치 완료를 주장하게 된다 — 「완주 시점에 성공 표식을
     * 남기는 안」이 기각된 근거가 정확히 그것이며, 종결 축의 무차별 적용이 그 동작을 되살린다.
     */
    @Test
    @DisplayName("★★VLM은_완주해도_시계열_메타가_없으면_남는다_벤더_응답_전_성공주장_금지")
    void vlmIgnoresCompletionAxisUntilResultArrives() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.VLM);
        markPipelineCompleted(rawSn);   // 제출까지 끝났을 뿐 벤더 결과는 아직 없다

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("VLM");
        // 종결 축 자체는 참이다 — 그것을 VLM 에 적용하지 않는 것이 이 시험의 요지다.
        assertThat(statusService.progressCompletedAfter(rawSn, LocalDateTime.now().minusMinutes(1))).isTrue();
    }

    /** 벤더 결과가 도착하면(시계열 메타 적재) 산출물 축 단독으로 제외된다 — VLM 의 유일한 성공 신호다. */
    @Test
    @DisplayName("★VLM은_시계열_메타가_도착해야_빠진다_산출물_축_단독")
    void vlmDropsOutOnlyWhenTimeseriesArrives() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.VLM);
        markPipelineCompleted(rawSn);
        seedTimeseriesMeta(rawSn);

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).isEmpty();
    }

    /**
     * ★두 묶음이 같은 조건(완주 + 산출물 0건)인데 <b>서로 다르게 판정</b>된다 — 종결 축이 묶음별
     * 선언을 따른다는 증거다. 호출부의 묶음 분기가 아니라 묶음 자신의 선언이 이 차이를 만든다.
     */
    @Test
    @DisplayName("★★같은_완주_상태에서_오토라벨은_빠지고_VLM은_남는다_묶음별_선언")
    void completionAxisAppliesPerBundleDeclaration() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.VLM);
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);
        markPipelineCompleted(rawSn);   // 둘 다 산출물은 0건

        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("VLM");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 감사 축·건너뜀 축 불변 — 산출물 필터가 새어 나가면 안 된다
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ★감사 축 불변 — {@code clearedBundles} 는 표식이 어떻게 서 있는지를 그대로 답한다.
     * 두 축이 같아지면 "표식 상태"를 물을 수단 자체가 사라진다.
     */
    @Test
    @DisplayName("★산출물이_있어도_감사_축_clearedBundles는_변하지_않는다")
    void auditAxisIsUnaffectedByArtifact() {
        Long rawSn = newRaw();
        skipThenClear(rawSn, BatchStageBundle.VLM);
        seedTimeseriesMeta(rawSn);

        // 화면 축은 비었는데(위 시험) 감사 축은 그대로 표식을 말한다.
        assertThat(statusService.clearedBundles(rawSn)).containsExactly("VLM");
        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).isEmpty();
    }

    /**
     * ★★건너뜀 축은 의도된 비대칭 — 건너뛴 상태는 <b>사람이 그렇게 결정한 상태</b>라, 산출물이 있다는
     * 이유로 시스템이 그 결정을 지우면 사람의 판단을 시스템이 뒤집는 것이 된다.
     */
    @Test
    @DisplayName("★★건너뜀_축에는_산출물_필터가_걸리지_않는다_의도된_비대칭")
    void skippedAxisNeverFiltersByArtifact() {
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        seedTimeseriesMeta(rawSn);

        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("VLM");
    }

    /** 스킵과 해제가 동시에 서는 상태(묶음이 갈린다)에서도 두 축이 서로를 침범하지 않는다. */
    @Test
    @DisplayName("스킵_묶음과_해제_묶음이_공존해도_각_축이_자기_것만_답한다")
    void twoBundlesInDifferentStatesStaySeparate() {
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        skipThenClear(rawSn, BatchStageBundle.AUTOLABEL);

        assertThat(statusService.manuallySkippedBundles(rawSn)).containsExactly("VLM");
        assertThat(statusService.clearedBundles(rawSn)).containsExactly("AUTOLABEL");
        // 오토라벨 산출물(자동 생성 라벨)이 없으므로 조치 필요 목록에 남는다.
        assertThat(statusService.clearedBundlesNeedingAction(rawSn)).containsExactly("AUTOLABEL");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 배선 — 실제 스프링 컨텍스트에서 모든 묶음이 판정기를 갖는가
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ★<b>실제 배선</b> 확인 — 단위 시험은 판정기를 손으로 넣어 주므로 {@code @Component} 누락을
     * 잡지 못한다. 여기서만 "스프링이 실제로 모아 준 판정기"를 본다. 이 단언이 실패하면 그 묶음은
     * 조치 목록에서 영영 걸러지지 않는다(배너가 사라지지 않는 결함으로 되돌아간다).
     */
    @Test
    @DisplayName("★모든_작업_묶음이_실제_컨텍스트에서_산출물_판정기를_갖는다")
    void everyBundleIsWiredInRealContext() {
        assertThat(artifactPresenceRegistry.unwiredBundles()).isEmpty();
    }
}
