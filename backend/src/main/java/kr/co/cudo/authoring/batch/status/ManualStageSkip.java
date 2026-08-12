package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REVIEWER 가 <b>손으로 누른</b> 작업 묶음 스킵/해제 표식의 상수·판정 단일 원천.
 * [@design API-198] [@design API-200]
 *
 * <h3>왜 기존 {@code SKIP_REASON_*} 을 재사용하면 안 되나 (Critical)</h3>
 * <p>{@code VlmWithheldResumeRunner} 는 {@code VlmTimeseriesStep.RESUMABLE_SKIP_REASONS} 에 속한
 * 사유로 남은 {@code SKIPPED} 행을 보고 <b>재위탁</b>한다. 사람이 누른 스킵을 그 축에 쓰면, 훗날
 * 비식별 신고 해소 이벤트가 그 행을 집어 <b>사람의 결정을 몰래 뒤집고 외부 벤더로 재위탁</b>한다.
 * 그래서 이 표식은 전용 {@code ERR_CD} 를 쓰고, 사유 문자열에는 {@link #REASON_PREFIX} 를 강제로
 * 붙여 <b>어떤 사용자 입력도 재개 사유 상수와 정확 일치할 수 없게</b> 만든다
 * (재개 판정이 {@code ERR_MSG_CN} 정확 일치이므로 접두만으로 충돌이 구조적으로 불가능해진다).
 *
 * <h3>★저장 축 — 한 행이 한 묶음의 결정을 담는다 (부분 상태 불가)</h3>
 * <p>표식은 {@code LS_BATCH_PROC_LOG} 에 {@code PROC_STTS_CD='SKIPPED'} 감사 행으로 <b>append-only</b>
 * 로 쌓이며, {@code PROC_STEP_CD} 에는 개별 단계가 아니라 <b>묶음 코드</b>
 * ({@link BatchStageBundle#name()} — {@code VLM} / {@code AUTOLABEL})가 들어간다.
 *
 * <p>오토라벨 3단계에 표식을 <b>각각</b> 남기는 방식은 채택하지 않았다. 그러면 "3건 모두 서 있어야
 * 묶음 스킵"이라는 판정이 생기고, <b>2건만 있는 상태가 표현 가능</b>해진다 — 그 상태에서 게이트가 어떻게
 * 동작할지는 아무도 정의하지 않았고, 훗날 한 단계에만 표식을 남기는 경로가 하나만 생겨도 판정이
 * 조용히 흔들린다. 묶음 코드 한 행이면 <b>부분 상태가 표현 자체로 불가능</b>하고, 기록·해제가 단일
 * INSERT 라 원자성을 위해 따로 애쓸 것이 없다. 감사 측면에서도 "누가 언제 왜 오토라벨을 껐다"가 한
 * 줄로 읽힌다(3줄을 사람이 재조립하지 않는다).
 *
 * <p><b>{@code PROC_STEP_CD} 에 실재하지 않는 단계값을 넣어도 안전한 근거</b>: 이 컬럼을 단계로 해석하는
 * 유일한 지점은 {@code BatchStatusService.currentStage}({@code BatchStage.valueOf})와
 * {@code stagesFor}/{@code failureReasonFor} 인데, 셋 다 {@code latestProgressLog}
 * (={@code findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc}, {@code SKIPPED} 제외)를 통해서만 행을
 * 읽는다. 표식 행은 {@code SKIPPED} 라 <b>그 조회에 애초에 걸리지 않는다</b>. 컬럼은
 * {@code VARCHAR(30)} 이고 CHECK 제약이 없으며 이 테이블을 읽는 뷰도 없다. 이 성질을
 * {@code ManualSkipMarkerIsolationTest} 가 고정한다 — 깨지면 화면 단계 표시가 표식에 오염된다.
 *
 * <p><b>현재 상태 판정</b>은 "(영상 × 묶음) 의 마지막 표식 행이 {@link #ERR_CD_SKIPPED} 인가" 다.
 * 해제는 표식을 지우는 대신 {@link #ERR_CD_CLEARED} 행을 덧붙이므로 <b>누가·언제·왜가 모두 남고</b>,
 * 스킵→해제→재스킵이 몇 번 반복돼도 "마지막 행"이라는 판정 규칙은 흔들리지 않는다.
 */
public final class ManualStageSkip {

    /** 수동 스킵 표식 행의 {@code ERR_CD}. 컬럼 폭 {@code VARCHAR(50)} 이내. */
    public static final String ERR_CD_SKIPPED = "MANUAL_SKIP";

    /** 수동 스킵 <b>해제</b> 표식 행의 {@code ERR_CD}. */
    public static final String ERR_CD_CLEARED = "MANUAL_SKIP_CLEARED";

    /** 표식 행을 고르는 {@code ERR_CD} 집합 — 상태 판정 쿼리의 입력. */
    public static final List<String> MARKER_ERR_CDS = List.of(ERR_CD_SKIPPED, ERR_CD_CLEARED);

    /**
     * 사용자 사유에 강제로 붙는 접두 — 재개 사유 상수와의 정확 일치를 구조적으로 차단한다.
     * 이 접두를 떼면 사용자가 재개 사유 문자열을 그대로 입력해 재위탁을 유발할 수 있다.
     */
    public static final String REASON_PREFIX = "[수동 스킵] ";

    /** 해제 행의 사유 접두 — 해제도 사유 컬럼에 흔적을 남긴다(감사). */
    public static final String CLEARED_REASON_PREFIX = "[수동 스킵 해제] ";

    /** 사용자 입력 사유의 최대 길이(문자). {@code ERR_MSG_CN} 폭(4000)보다 훨씬 작게 잡는다. */
    public static final int REASON_MAX_LENGTH = 500;

    private ManualStageSkip() {
    }

    /**
     * 허용 묶음 이름을 사용자 안내용으로 나열한다.
     *
     * <p>순서는 {@link BatchStageBundle} 선언 순서(파이프라인 순서와 같다) 고정이다 — 실행마다 흔들리면
     * 안내 문구가 요청마다 달라진다. 이름만 싣고 내부 구조(어느 단계로 구성되는지)는 싣지 않는다.
     */
    public static String skippableBundleNames() {
        return java.util.Arrays.stream(BatchStageBundle.values())
                .map(BatchStageBundle::name)
                .collect(Collectors.joining(", "));
    }
}
