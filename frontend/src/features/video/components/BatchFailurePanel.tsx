// BatchFailurePanel — 배치 실패 사유 + 조치 (REVIEWER 전용).
// [@design SCREEN-009] [@design API-043] [@design API-167] [@design API-198] [@design API-200]
// [@design API-201] [@design UI-111] [@design AC-051]
//
// ★ 이 패널은 BatchStageIndicator 안이 아니라 **바깥**에 산다.
//   그 표시기는 «어느 단계까지 왔는가»만 말하고 조작(건너뛰기·재수행·재실행)은 그 바깥의 관심사다.
//   확정 사양(SCREEN-009 sections[2])이 조작 버튼을 표시기 안에 두는 것을 명시적으로 금지한다.
//   ⚠ **[폐기]** 구 근거 「그 표시기는 마킹 화면(MarkingPage)과 공유하므로 조작 버튼을 표시기 안에
//   넣으면 마킹 화면에도 그대로 나타난다」 — 마킹 화면이 표시기를 더 쓰지 않아 사실이 아니다.
//   근거 하나가 사라졌을 뿐 **금지는 그대로**다. 사용처가 영상 상세 한 곳이 됐다는 사실은 이 패널을
//   표시기 안으로 옮길 근거가 **아니다**(두 관심사를 합치지 말 것).
//
// ★ 조작 단위는 개별 단계가 아니라 **작업 묶음**이다 — 시계열(VLM) · 오토라벨(AI 탐지·AI 분할·보간).
//   뒤 작업이 앞 결과를 이어받고 보간이 그 산출물을 재계산하므로 일부만 수행하면 산출물끼리 어긋난다.
//   무엇보다 보간을 묶음 밖에 두면 **어떤 재수행에서도 보간이 무조건 돌아** 사람이 손댄 보간 라벨을
//   지운다 — 묶음이 그 사고를 구조적으로 없앤다. 진행 축(`stages` 7단계)은 그대로 단계 단위로 그린다.
//
// 표시 규칙(사양):
//   - 노출 조건은 **실패했거나 건너뛴 묶음이 하나라도 있으면**이다. 실패했을 때만 노출하면 건너뛴 뒤
//     재기동이 성공한 영상에서 이 영역이 통째로 사라져 해제할 창구가 없어지고, 그 묶음은 이후 모든
//     재기동에서 조용히 건너뛰어진 채 화면에는 완료로 보인다(막는 조작에는 되돌리는 길을 함께 둔다).
//   - 사유는 서버가 사용자 문구로 변환해 내려준 값을 **그대로** 보여준다. 화면이 재해석하지 않는다.
//   - 단계를 특정할 수 없는 실패(= stages 가 빈 배열)도 사유만은 보여주고, 단계 자리에는 확인
//     불가임을 알린다. dev 실측에서 실패 영상 3건 중 1건이 이 경우였다 — 이 분기가 빠지면 그
//     영상에서는 화면이 아무것도 보여주지 못한다.
//   - 건너뛰기/재수행은 두 묶음에서만 노출한다(비식별·마킹·프레임추출은 어느 묶음에도 없다).
//   - **건너뛰기 버튼은 그 묶음의 단계가 실패했을 때만** 노출한다(사양이 "실패 시 건너뛴다"이므로
//     넓히지 않는다).
//   - **건너뛴 적이 있는 묶음(건너뜀·해제 모두)에 재수행 버튼을 하나** 둔다 — 범위를 고르지 않는다
//     (묶음이 곧 범위다). 전체 재기동을 완주 영상에 쓰면 파이프라인이 통째로 돌아 사람이 손댄 보간
//     라벨이 전량 지워진다 — 그래서 **문제가 생긴 곳부터** 재시도한다.
//   - **「건너뛰기 해제」 버튼은 두지 않는다**(ADR-050). 재수행이 건너뛴 상태를 직접 수락하고 해제
//     표식까지 함께 남기므로, 해제와 재수행을 두 번 돌게 하면 회수 동선만 길어지고 중간에 멈춘
//     영상(해제만 하고 재수행을 안 한 상태)이 생긴다. 「막는 조작에는 되돌리는 길을 함께 둔다」는
//     원칙은 그대로이며, 그 길을 **재수행 하나**가 진다. 서버의 해제 API 자체는 남아 있으나 이
//     화면은 부르지 않는다 — 되살리지 말 것.
//   - 파생영상에는 조작을 노출하지 않는다 — 파생은 배치 파이프라인을 타지 않아 재실행으로
//     복구되지 않는다(사유는 그대로 보여준다).
//   - **검수가 완료된 적 있는 영상은 재수행이 묶음별로 갈린다** — 시계열은 그대로 누르고 오토라벨만
//     비활성 + 사유다. 서버가 이미 그렇게 막지만, 되돌릴 수 없는 조건이라 누르기 전에 알린다.
//   - **「배치 재실행」(전체 재기동)은 영상이 실제로 실패 상태일 때만 둔다** — 서버가 받는 조건이
//     그것 하나이기 때문이다. 묶음 재수행이 실패하면 서버는 영상을 완주로 원상 복구하되 로그에는
//     실패를 남기므로, 사유 문자열만 보고 버튼을 열면 그 버튼은 **항상** 막힌다. 사유는 계속
//     보여주되 「직전 실패」로 이름을 바꿔 지금 상태와 구분한다.
//   - **처리 중인 영상은 실패로 말하지 않는다**(아래 batchPanelMode). 재기동은 접수만 확정되고
//     실행은 비동기로 넘어가므로, 접수 직후~순서 대기 구간의 `stages`·`batchFailureReason` 은
//     **직전 실행의 기록 그대로**다. 그것을 근거로 "배치 처리 실패"를 계속 보여주면 사용자는
//     접수된 사실을 모른 채 재실행을 다시 누르고, 서버는 이미 처리 중이라 그 요청을 매번 막는다.

import { useState } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';

import { Badge } from '@/components/common/Badge';
import { Button } from '@/components/common/Button';
import { bundleLabel, stageLabel } from '@/components/common/BatchStageIndicator';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

import { useRerunBatchStage, useRetryBatch, useSkipBatchStage } from '../hooks/useBatchRecovery';
import {
  STAGE_BUNDLES,
  bundleRerunsInterpolation,
  isBatchFailed,
  isBatchProcessing,
  type StageBundle,
  type VideoDetail,
} from '../types';


import { BatchStageSkipModal } from './BatchStageSkipModal';

/** 패널 판정에 필요한 최소 필드 — 전체 상세를 요구하지 않아 호출부·테스트가 가벼워진다. */
type BatchAttentionFields = Pick<
  VideoDetail,
  'batchFailureReason' | 'stages' | 'skippedStages' | 'clearedStages' | 'failedStages'
>;

/**
 * 이 영상에 조치가 필요한 배치 **실패**가 있는가.
 *
 * 두 신호를 모두 본다. 사유만 있고 단계를 특정할 수 없는 실패가 실재하고(그때 `stages` 는 빈
 * 배열), 반대로 사유를 못 내리는 구 응답에서도 `stages` 의 FAIL 은 남기 때문이다. 한쪽만 보면
 * 각각의 경우에 실패가 없는 것으로 판정된다.
 */
export function hasBatchFailure(video: Pick<VideoDetail, 'batchFailureReason' | 'stages'>): boolean {
  if (video.batchFailureReason && video.batchFailureReason.trim().length > 0) return true;
  return (video.stages ?? []).some((s) => s.status === 'FAIL');
}

/**
 * 서버가 판정한 **지금 실패 상태인 작업 묶음** — 화면이 실패를 재유도하지 않는 유일한 창구.
 * [@design ADR-050]
 *
 * <p>「건너뛰기를 허용할지」를 정하는 서버 판정과 <b>같은 결과</b>다. `stages` 의 `FAIL` 이나
 * `batchFailureReason` 문자열에서 다시 유도하지 않는다 — 소비자가 생산자의 성공 조건을 재유도하면
 * 서버가 조건을 바꿀 때 조용히 어긋난다(이 결함이 정확히 그렇게 났다).
 */
export function failedBundlesOf(video: Pick<VideoDetail, 'failedStages'>): StageBundle[] {
  return video.failedStages ?? [];
}

/**
 * 패널을 노출해야 하는가 — **노출의 단일 판정**. [@design SCREEN-009] [@design ADR-050]
 *
 * ★ 실패 여부만 보면 안 된다. 스킵 표식은 영구라 ①단계를 건너뛰고 ②재기동이 성공하면 ③실패가
 * 사라져 패널이 통째로 없어지고 ④그 단계는 이후 모든 재기동에서 조용히 건너뛰어지는데 화면에는
 * 완료로 보인다. 해제할 진입점이 어디에도 남지 않는다.
 *
 * ★★ 그리고 <b>묶음 실패 축(`failedStages`)</b>이 없으면 「실패 후 판단」 입구가 닫힌다 — 시계열
 * 위탁 실패는 파이프라인을 멈추지 않아 배치 상태도 진행 축도 실패로 서지 않는다. 그 영상에서
 * 조치 영역이 통째로 사라져 <b>건너뛰기를 요청할 창구 자체가 없었다</b>. 과대 노출은 서버의 건별
 * 거부가 보정하지만 <b>과소 노출은 보정되지 않는다</b>.
 */
export function needsBatchAttention(video: BatchAttentionFields): boolean {
  return (
    hasBatchFailure(video) ||
    (video.skippedStages ?? []).length > 0 ||
    // ★ 해제된 묶음도 노출 근거다 — 그 영상은 실패도 스킵도 없지만 **재수행 창구**가 남아야 한다
    //   (재수행이 실패해 원상 복구된 영상이 정확히 이 상태다).
    (video.clearedStages ?? []).length > 0 ||
    // ★ 실패한 묶음도 노출 근거다 — 위 두 축 어디에도 걸리지 않는 시계열 위탁 실패가 여기로 들어온다.
    failedBundlesOf(video).length > 0
  );
}

/** 패널이 말하는 상태 — 축들은 서로를 지우지 않는다(아래 {@link batchPanelMode} 주석). */
export type BatchPanelMode =
  | 'processing'
  | 'failure'
  | 'lastFailure'
  | 'bundleFailure'
  | 'cleared'
  | 'skipped';

/** 모드 판정 입력 — 주의(실패∪스킵) 축 + 처리 중 축. */
type BatchPanelFields = BatchAttentionFields & Pick<VideoDetail, 'status'>;

/**
 * 패널이 **무엇을 말할 것인가** — 노출 여부(`needsBatchAttention`)와는 다른 축이다.
 * [@design SCREEN-009] [@design API-167]
 *
 * <p>세 상태가 서로를 잡아먹지 않도록 <b>판정을 이 함수 하나로 모은다</b>. 컴포넌트가 조건을
 * 여기저기서 다시 조립하면 한 축을 고칠 때 다른 축이 조용히 사라진다.
 * <ul>
 *   <li><b>processing</b> — 영상이 처리 중이다. 실패 기록이 남아 있어도 그것은 <b>직전 실행의
 *       기록</b>이므로 "실패"로 말하지 않는다. 그래서 실패보다 <b>앞선다</b>.</li>
 *   <li><b>failure</b> — 처리 중이 아니고, 실패 신호가 있으며, <b>영상이 지금 실패 상태</b>다.</li>
 *   <li><b>lastFailure</b> — 실패 기록은 있는데 영상은 실패 상태가 아니다(대개 <b>완주</b>). 묶음
 *       재수행이 실패하면 서버가 영상을 원상 복구하되 로그에는 실패를 남기므로 이 조합이 실재한다.
 *       그때 "지금 실패"로 말하면 <b>전 단계 DONE 인데 실패</b>라는 모순을 화면이 내밀게 된다.</li>
 *   <li><b>cleared</b> — 실패도 스킵도 없고 <b>건너뛰기가 해제된 묶음만</b> 남았다.</li>
 *   <li><b>skipped</b> — 실패 기록도 처리 중도 아닌데 패널이 떠 있다면 남은 이유는 스킵 표식뿐이다.</li>
 * </ul>
 *
 * ⚠ 이번에 가른 것은 <b>「실패 사유가 있다」(`hasBatchFailure`)와 「지금 실패 상태다」
 * (`isBatchFailed`)</b> 둘뿐이다 — 처리 중·스킵만 분기의 조건은 <b>글자 그대로 그대로</b>다.
 * `hasBatchFailure` 가 거짓이면 이 함수는 상태와 무관하게 예전처럼 `skipped` 를 돌려준다.
 *
 * ⚠ 처리 중이 실패를 <b>가리는 것</b>과 <b>지우는 것</b>은 다르다 — `hasBatchFailure` 는 그대로
 * 유지되며 실패 기록은 「직전 실패」로 계속 보인다(`lastFailure` 도 같은 어휘를 쓴다). 마찬가지로
 * 스킵 목록·재수행 창구는 모드와 무관하게 항상 렌더된다(스킵 축이 다른 축에 먹히면 되살릴 창구가
 * 또 사라진다 — 이미 한 번 난 결함).
 */
export function batchPanelMode(video: BatchPanelFields): BatchPanelMode {
  if (isBatchProcessing(video)) return 'processing';
  if (hasBatchFailure(video)) return isBatchFailed(video) ? 'failure' : 'lastFailure';
  // ★ 여기까지 오면 배치 축에는 아무 실패 신호가 없다. 그래도 **묶음 하나가 실패해 있을 수 있다** —
  //   시계열 위탁 실패가 정확히 그 경우다(파이프라인을 멈추지 않아 배치 축이 조용하다). 이 분기가
  //   없으면 그 영상의 패널 제목이 「건너뛴 작업 있음」이 되어, 건너뛴 것이 하나도 없는데 그렇게 말한다.
  if (failedBundlesOf(video).length > 0) return 'bundleFailure';
  // ★ 여기까지 오면 실패 신호가 배치 축에도 묶음 축에도 없다. 그런데 **건너뛴 묶음도 없이 해제된
  //   묶음만** 남아 있을 수 있다(건너뛰기를 걸었다가 풀었고 아직 그 묶음의 산출물이 없는 영상).
  //   이 분기가 없으면 그 영상의 패널 제목이 「건너뛴 작업 있음」이 되어, 건너뛴 것이 하나도 없는데
  //   그렇게 말한다 — 바로 위 `bundleFailure` 를 가른 것과 **같은 원칙의 연장**이며 새 정책이 아니다.
  //   화면 자신이 이미 자기모순을 드러내고 있었다: 스킵 안내 문단(`batch-skipped-note`)은
  //   `skipped.length > 0` 일 때만 렌더돼 나오지 않는데 **제목만** 스킵을 주장했다.
  //   ⚠ 스킵과 해제가 **동시에** 있는 상태는 실재한다(시계열은 스킵, 오토라벨은 해제). 그때는
  //     아래 `skipped` 가 이긴다 — 스킵이 남아 있는데 「해제됨」이라 말하면 **정반대 방향의 같은
  //     거짓**이 된다. 그래서 이 분기는 `skippedStages` 가 비었을 때로 좁힌다.
  //   ⚠ 이 분기는 **제목만** 가른다. 행·배지·버튼의 노출 조건은 모드를 보지 않는다(아래 렌더).
  if ((video.skippedStages ?? []).length === 0 && (video.clearedStages ?? []).length > 0) {
    return 'cleared';
  }
  return 'skipped';
}

/**
 * 제목 문구 — 상태는 색이 아니라 이 문구가 말한다(grayscale·색각 이상에서도 구분된다).
 *
 * ⚠ `lastFailure` 는 아래 사유 영역이 이미 쓰던 <b>「직전 실패」어휘를 그대로</b> 재사용한다 —
 * 같은 사실을 가리키는 표현을 화면마다 새로 만들면 둘이 같은 뜻인지 사용자가 알 수 없다.
 */
const MODE_HEADING: Record<BatchPanelMode, string> = {
  processing: '배치 처리 중',
  failure: '배치 처리 실패',
  lastFailure: '직전 배치 처리 실패',
  // ★ 배치 전체는 완주했는데 **그 작업만** 실패한 상태다 — 「배치 처리 실패」로 말하면 전 단계가
  //   완료인 화면과 모순되고, 「건너뛴 작업 있음」으로 말하면 건너뛴 적이 없는데 그렇게 말한다.
  bundleFailure: '실패한 작업 있음',
  // ★ 건너뛴 것이 하나도 없으므로 「건너뛴 작업 있음」이라 말하지 않는다 — 그 영상에 실제로 있었던
  //   일은 「건너뛰기를 걸었다가 풀었고 아직 그 작업의 산출물이 없다」이며 이 문구가 그것을 말한다.
  cleared: '건너뛰기 해제됨',
  skipped: '건너뛴 작업 있음',
};

/** 표면 톤 — 문구가 이미 상태를 말하므로 색은 보조다. */
const MODE_SURFACE: Record<BatchPanelMode, string> = {
  processing: 'rounded-lg border border-info/40 bg-info/5 px-4 py-3',
  failure: 'rounded-lg border border-danger/40 bg-danger/5 px-4 py-3',
  // 지금 실패 상태가 아니므로 위험 톤을 쓰지 않는다 — 사실 관계는 제목 문구가 말한다.
  lastFailure: 'rounded-lg border border-gray-300 bg-gray-50 px-4 py-3',
  // 영상 자체는 완주 상태라 위험 톤을 쓰지 않는다 — 무엇이 실패했는지는 제목 문구와 묶음 표식이 말한다.
  bundleFailure: 'rounded-lg border border-gray-300 bg-gray-50 px-4 py-3',
  // 실패도 스킵도 아니므로 위험 톤을 쓰지 않는다 — 상태는 제목 문구가 말한다(스킵과 같은 톤).
  cleared: 'rounded-lg border border-gray-300 bg-gray-50 px-4 py-3',
  skipped: 'rounded-lg border border-gray-300 bg-gray-50 px-4 py-3',
};

/** 재실행 버튼 설명 — 버튼이 비활성일 때 **왜 비활성인지**를 사람이 읽을 수 있어야 한다. */
const RETRY_HINT_ID = 'batch-retry-hint';

/**
 * 재수행 파괴 경고 id — **묶음마다 유일**해야 한다. [@design API-201]
 *
 * 이 문단은 버튼을 <b>누르기 전에</b> 이미 화면에 있고, 그 묶음의 재수행 버튼이 `aria-describedby`
 * 로 가리킨다. 사양이 요구하는 것은 "고르는 시점에 알린다"이므로 누른 뒤에 뜨는 확인 창만으로는
 * 부족하다(확인 창은 취소 수단이지 고지 수단이 아니다 — 둘 다 둔다).
 *
 * ⚠ 이 문단은 <b>보간을 다시 만드는 묶음에만</b> 붙인다({@link bundleRerunsInterpolation}). 시계열
 * 묶음에 붙이면 경고가 의미를 잃고, 보조기술 사용자에게는 없는 위험을 알리는 오정보가 된다.
 */
const rerunWarningId = (bundle: StageBundle) => `batch-rerun-warning-${bundle}`;

/** 재수행 버튼이 **왜 비활성인지**(처리 중) 설명하는 문단 id. */
const rerunBusyHintId = (bundle: StageBundle) => `batch-rerun-busy-hint-${bundle}`;

/**
 * 재수행 버튼이 **왜 비활성인지**(검수 완료 이력) 설명하는 문단 id. [@design API-201]
 *
 * 처리 중과 <b>다른 축</b>이라 문단을 따로 둔다 — 처리 중은 기다리면 풀리지만 승인 이력은
 * 되돌릴 수 없는 영구 조건이고, 둘을 한 문단으로 합치면 "잠시 뒤 다시"라는 잘못된 기대를 준다.
 */
const rerunApprovedLockId = (bundle: StageBundle) => `batch-rerun-approved-lock-${bundle}`;

/**
 * 승인 이력 때문에 이 묶음의 재수행이 잠기는가 — <b>이 판정의 단일 지점</b>. [@design API-201]
 *
 * <p>시계열은 확정된 라벨을 건드리지 않고 서술만 더하므로 승인 이력이 있어도 그대로 수행한다.
 * 오토라벨은 라벨을 다시 만들어 승인 시점 스냅샷과 어긋나므로 막는다.
 *
 * ⚠ 판정 축을 {@link bundleRerunsInterpolation}(보간 재계산 여부)에 얹지 않는다 — 지금은 두 값이
 * 우연히 같지만 <b>뜻이 다르다</b>(그쪽은 "사람이 손댄 보간 라벨이 지워지는가", 이쪽은 "승인
 * 스냅샷과 어긋나는가"). 묶음 구성이 바뀌면 둘은 갈라지고, 그때 조용히 틀린 쪽이 따라온다.
 */
function rerunLockedByApproval(bundle: StageBundle, everApproved: boolean): boolean {
  return everApproved && bundle === 'AUTOLABEL';
}

/**
 * ⚠ 구 `RevertedBundles`(이 화면 세션에서 건너뛰기를 해제한 묶음의 **로컬 기억**)는 **폐기**됐다 —
 * 되살리지 말 것. [@design ADR-050]
 *
 * 그것은 서버 응답에 해제 목록이 없던 시절의 우회였고, 그래서 새로고침·다른 화면 경유 후 재진입하면
 * 재수행 창구가 통째로 사라졌다(다시 건너뛰었다가 해제하는 우회밖에 없었다). 응답이
 * `clearedStages` 를 내려주는 지금은 <b>서버가 그 사실을 기억</b>하므로 화면이 기억할 이유가 없다.
 */

/** 서버가 준 사용자 문구를 그대로 쓰고, 그것이 없을 때만 일반 문구로 폴백한다. */
function errorMessageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError && err.userMessage ? err.userMessage : fallback;
}

export interface BatchFailurePanelProps {
  video: VideoDetail;
}

/**
 * ⚠ 구 `onRetryAccepted` 콜백은 **폐지**했다 — 되살리지 말 것. [@design API-167]
 *
 * 그것은 상세 화면에 "지금 접수했으니 잠깐 따라가라"는 시간 창을 열어 주는 배선이었는데,
 * ①버튼을 누른 사람에게만 창이 열려 일괄 재시작 후 상세로 들어온 사람은 아무것도 따라가지 못했고
 * ②창이 닫히면 실제로는 순서를 기다리는 중인데도 화면이 멈췄다.
 * 지금은 <b>영상 상태</b>가 판정한다 — 서버가 접수 시점에 이미 처리 중으로 커밋하므로, 아래
 * 무효화(`useRetryBatch`)로 재조회된 상태가 곧 신호다(상세 화면이 그 상태를 보고 추적한다).
 */
export function BatchFailurePanel({ video }: BatchFailurePanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  const [skipTarget, setSkipTarget] = useState<StageBundle | null>(null);
  /** 재수행 확인 대상 — null 이면 확인 창이 닫혀 있다(= 아직 아무것도 보내지 않았다). */
  const [rerunConfirmTarget, setRerunConfirmTarget] = useState<StageBundle | null>(null);

  const retry = useRetryBatch(video.id, {
    onSuccess: () => {
      // ★ "재실행을 시작했다"가 아니다 — 서버는 접수만 확정하고 실행은 비동기로 넘긴다.
      //   완료로 읽히는 문구를 쓰면 사용자가 결과를 다 본 것으로 오해한다.
      pushToast({
        variant: 'success',
        message: '배치 재실행 요청을 접수했습니다. 진행 상황은 처리 단계에서 확인하세요.',
      });
    },
    onError: (err) =>
      pushToast({
        variant: 'error',
        message: errorMessageOf(err, '배치 재실행 요청을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      }),
  });

  const skip = useSkipBatchStage(video.id, {
    onSuccess: (data) => {
      pushToast({
        variant: 'success',
        message: `${bundleLabel(data.stage)} 작업을 건너뛰도록 기록했습니다.`,
      });
      setSkipTarget(null);
    },
    onError: (err) => {
      pushToast({
        variant: 'error',
        message: errorMessageOf(err, '건너뛰기를 기록하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      });
      setSkipTarget(null);
    },
  });

  const rerun = useRerunBatchStage(video.id, {
    onSuccess: (data) => {
      // ★ 재실행(API-167)과 같은 시맨틱 — 접수까지만 확정되고 파이프라인은 뒤에서 이어 돈다.
      pushToast({
        variant: 'success',
        message: `${bundleLabel(data.stage)} 작업 재수행 요청을 접수했습니다. 진행 상황은 처리 단계에서 확인하세요.`,
      });
      setRerunConfirmTarget(null);
    },
    onError: (err) => {
      pushToast({
        variant: 'error',
        message: errorMessageOf(err, '재수행 요청을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      });
      setRerunConfirmTarget(null);
    },
  });

  const skipped = video.skippedStages ?? [];
  // ★ 건너뛰기가 **해제된** 묶음 — 서버가 내려주는 영구 상태다(구 로컬 기억 폐지, ADR-050).
  const cleared = video.clearedStages ?? [];

  // ★ 해제된 직후에는 실패도 스킵도 없을 수 있다(건너뛴 채 완주한 영상을 되살린 경우). 그때 패널이
  //   사라지면 재수행 창구가 같이 사라진다 — `needsBatchAttention` 이 해제 축까지 본다.
  if (!needsBatchAttention(video)) return null;

  const stages = video.stages ?? [];
  const mode = batchPanelMode(video);
  const processing = mode === 'processing';
  // ★ "실패 기록이 있는가"(축)와 "실패로 말할 것인가"(모드)를 분리한다 — 처리 중에도 기록은 남는다.
  const failed = hasBatchFailure(video);
  // ★ 그리고 "지금 실패 상태인가"는 **또 다른 축**이다. `failed` 는 진행 로그(서버가 남긴 기록)에서
  //   오고, 이 값은 **영상 상태**에서 온다. 묶음 재수행이 실패하면 서버는 영상을 완주로 원상 복구하되
  //   로그에는 실패를 남기므로 두 값이 갈린다 — 그 구간에서 둘을 같은 것으로 다루면 화면이 "전 단계
  //   완료"와 "배치 처리 실패"를 동시에 내밀고, 그때의 전체 재기동 버튼은 누를 때마다 막힌다.
  const currentlyFailed = isBatchFailed(video);
  // 전체 재기동(배치 재실행) 창구를 둘 것인가 — 서버가 받는 조건은 **영상 상태가 실패**뿐이다.
  //   ⚠ 처리 중은 그 실패 상태를 **방금 선점한 같은 흐름**이라 버튼을 지우지 않고 남긴다(비활성 +
  //     사유 문구). 여기서 지우면 접수 직후 버튼이 사라졌다가 다시 나타나 사용자가 무엇이 일어났는지
  //     알 수 없다. 반대로 완주(COMPLETED)한 영상에는 두지 않는다 — 서버가 반드시 막는다.
  //   ⚠ 실패 기록 자체가 없으면(건너뛴 채 완주한 영상 등) 예전처럼 두지 않는다 — 조건을 넓히지 않는다.
  const canShowRetry = failed && (currentlyFailed || processing);
  // 실패 **단계**는 사유 영역의 표시에만 쓴다 — 조작 축(묶음)은 아래 `failedBundles` 가 소유한다.
  const failedStage = stages.find((s) => s.status === 'FAIL') ?? null;
  // ★ 건너뛰기를 열 **묶음**은 서버 판정(`failedStages`)이 정한다 — 화면이 `stages` 에서 재유도하지
  //   않는다. 구 구현은 진행 축의 FAIL 을 묶음으로 역해석했는데, 시계열 위탁 실패는 그 축에 아무
  //   흔적을 남기지 않아 **버튼이 영영 뜨지 않았다**(ADR-050 의 두 입구 중 하나가 닫혀 있었다).
  const failedBundles = failedBundlesOf(video);
  const isDerivative = video.derivative === true;
  // ★ 승인 이력 축 — **서버가 내려준 값**을 그대로 쓴다(`VideoDetailResponse.everApproved`).
  //   화면이 상태·검수 이력에서 재유도하면 서버 판정과 갈리고, 그때 열리는 쪽이 서버가 막는 버튼이다.
  const everApproved = video.everApproved === true;
  const busy = retry.isPending || skip.isPending || rerun.isPending;

  // 조작 행을 그릴 묶음 — 서버가 실패로 판정했거나 건너뛴 적이 있는 묶음(건너뜀·해제 모두).
  const actionableBundles = STAGE_BUNDLES.filter(
    (bundle) =>
      skipped.includes(bundle) || cleared.includes(bundle) || failedBundles.includes(bundle),
  );

  return (
    <section
      aria-labelledby="batch-failure-heading"
      data-testid="batch-failure-panel"
      // 세 모드는 표면색이 다르지만 **판정은 색이 아니라 제목 문구**가 한다(아래 h4).
      data-mode={mode}
      className={MODE_SURFACE[mode]}
    >
      {/* 상태는 색만으로 전달하지 않는다 — 제목 문구가 단독으로 상태를 말한다.
          ⚠ 경고 아이콘은 **처리 중이 아닌 모든 모드**에 붙인다(실패·직전 실패·묶음 실패·해제·스킵).
             정상 진행 중인 영상에만 붙이지 않는다 — 그때 경고 글리프를 달면 문구와 아이콘이 서로
             다른 말을 한다(아이콘은 장식이고 문구가 정보다).
          ⚠ **[폐기]** 구 서술 「실패·스킵에만 붙인다」 — 그 열거는 `cleared` 모드가 생기기 전의
             것이라 낡았다. 해제 모드는 실패도 스킵도 아니지만 아이콘이 붙으며 **그것이 맞다**:
             확정 시안이 「건너뛴 작업 있음」과 「건너뛰기 해제됨」에 같은 경고 톤을 주고 이 화면도
             두 모드에 같은 표면을 쓴다. 틀렸던 것은 열거뿐이고 조건은 손대지 않았다. */}
      <h4
        id="batch-failure-heading"
        className="flex items-center gap-1.5 text-title-sm font-semibold text-gray-800"
      >
        {!processing && <AlertTriangle size={16} aria-hidden />}
        {MODE_HEADING[mode]}
      </h4>

      {/* 처리 중임을 **말로** 알린다 — 접수됐고 순서를 기다리는 중일 수 있다는 뜻이 전달돼야 한다.
          ⚠ 내부 실행기·큐·풀 같은 구현 용어는 노출하지 않는다(사용자에게 의미 없고 CWE-209). */}
      {processing && (
        <p className="mt-2 text-body-md text-gray-700" data-testid="batch-processing-note">
          요청이 접수되어 배치 처리 중입니다. 앞선 작업이 있으면 순서를 기다린 뒤 시작합니다. 진행
          상황은 처리 단계에서 확인하세요.
        </p>
      )}

      {/* 실패 사유 영역은 **실패 기록이 있을 때만** 둔다. 건너뛰기만 남은 영상에는 보여줄 사유가
          없으므로 빈 자리를 만들지 않고 통째로 생략한다(없는 사유를 '없음'으로 채워 넣지 않는다).
          ★ 지금 실패 상태가 아니면(처리 중이거나 이미 완주) 기록은 지우지 않고 「직전」으로 이름만
             바꿔 보여준다 — 무엇 때문에 실패했는지가 사라지면 안 되고, 그렇다고 현재 실패로 읽혀도
             안 된다. 판정은 로그(`failed`)가 아니라 **영상 상태**(`currentlyFailed`)가 한다. */}
      {failed && (
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-body-md">
          <dt className="text-gray-600">{currentlyFailed ? '실패 단계' : '직전 실패 단계'}</dt>
          <dd className="font-medium text-gray-800" data-testid="batch-failure-stage">
            {failedStage ? (
              stageLabel(failedStage.name)
            ) : (
              <span className="text-gray-600">확인 불가 — 단계를 특정할 수 없는 실패입니다.</span>
            )}
          </dd>
          <dt className="text-gray-600">{currentlyFailed ? '실패 사유' : '직전 실패 사유'}</dt>
          {/* 서버 문구 그대로. React 자동 escape 로 렌더된다(dangerouslySetInnerHTML 미사용). */}
          <dd className="text-gray-800 whitespace-pre-wrap" data-testid="batch-failure-reason">
            {video.batchFailureReason ?? '기록된 사유가 없습니다.'}
          </dd>
        </dl>
      )}

      {/* 건너뛴 묶음이 남아 있다는 사실은 여기서만 드러난다 — 표시기는 그 단계들을 완료로 그린다. */}
      {skipped.length > 0 && (
        <p className="mt-2 text-body-md text-gray-700" data-testid="batch-skipped-note">
          아래 작업은 건너뛰도록 기록되어 있어 배치를 다시 실행해도 수행하지 않습니다. 재수행하면 그
          작업만 다시 수행하고 건너뛰기 기록도 함께 풀립니다.
        </p>
      )}

      {isDerivative ? (
        <p className="mt-3 text-body-md text-gray-600" data-testid="batch-failure-derivative-note">
          이 영상은 원본에서 만들어진 파생영상이라 배치 파이프라인을 타지 않습니다. 이 화면에서는
          재실행이나 작업 건너뛰기를 할 수 없습니다.
        </p>
      ) : (
        <div className="mt-3 flex flex-col gap-2">
          {/* 재실행은 **실패 상태인 영상만** 대상이다(서버도 그 상태에서만 선점한다). 건너뛰기만
              남은 영상이나 **완주한 영상**에 버튼을 두면 누를 때마다 막히는 동선이 된다 — 후자가
              바로 묶음 재수행이 실패해 원상 복구된 영상이며, 그 화면의 이 버튼은 **항상** 막혔다.
              ★ 같은 이유로 **처리 중에는 비활성**이다 — 그때 누르면 서버가 반드시 막는다(이미
                선점됨). 비활성 사유는 아래 문단이 말하고, 버튼이 `aria-describedby` 로 그것을
                가리킨다(왜 못 누르는지가 보조기술에도 전달돼야 한다). */}
          {canShowRetry && (
            <div>
              <Button
                variant="primary"
                size="sm"
                leftIcon={RefreshCw}
                disabled={busy || processing}
                loading={retry.isPending}
                aria-describedby={RETRY_HINT_ID}
                onClick={() => retry.mutate()}
              >
                배치 재실행
              </Button>
              <p
                id={RETRY_HINT_ID}
                className="mt-1 text-caption text-gray-600"
                data-testid="batch-retry-hint"
              >
                {processing
                  ? '이미 처리 중이라 지금은 다시 요청할 수 없습니다. 진행 중인 처리가 끝난 뒤 결과를 확인하세요.'
                  : '요청을 접수하면 실패한 단계부터 이어서 진행합니다. 이미 성공한 단계는 다시 수행하지 않습니다. 진행 상황은 처리 단계에서 확인하세요.'}
              </p>
            </div>
          )}

          {actionableBundles.length > 0 && (
            <ul className="flex flex-col gap-2">
              {actionableBundles.map((bundle) => {
                const label = bundleLabel(bundle);
                const isSkipped = skipped.includes(bundle);
                const isCleared = cleared.includes(bundle);
                // ★ 서버가 이 묶음을 실패로 판정했는가 — 건너뛰기 노출의 **단일 근거**다.
                const isBundleFailed = failedBundles.includes(bundle);
                // ★ 재수행 창구는 **건너뛴 적이 있는 묶음** 전부에 둔다 — 서버가 건너뛴 상태도
                //   직접 수락하므로 해제를 먼저 부를 필요가 없다(ADR-050).
                const canRerun = isSkipped || isCleared;
                // ★ 이 묶음을 재수행하면 보간이 다시 만들어지는가 — 경고·확인의 단일 판정이다.
                //   묶음 구성에서 파생하므로 구성이 바뀌면 경고가 자동으로 따라온다.
                const destructive = bundleRerunsInterpolation(bundle);
                // ★ 검수가 완료된 적 있는 영상에서 이 묶음의 재수행이 잠기는가(오토라벨만).
                const approvedLocked = rerunLockedByApproval(bundle, everApproved);
                const warningId = rerunWarningId(bundle);
                const busyHintId = rerunBusyHintId(bundle);
                const approvedLockHintId = rerunApprovedLockId(bundle);
                const rerunDescribedBy =
                  [
                    destructive ? warningId : null,
                    approvedLocked ? approvedLockHintId : null,
                    processing ? busyHintId : null,
                  ]
                    .filter(Boolean)
                    .join(' ') || undefined;
                return (
                  <li key={bundle} className="flex flex-col gap-1">
                    <div className="flex flex-wrap items-center gap-2 text-body-md">
                      <span className="text-gray-800">{label}</span>
                      {/* ⚠ 「건너뛰기 해제」 버튼은 두지 않는다(ADR-050) — 되살릴 창구는 재수행 하나다.
                          ★ 세 표식은 **공용 배지(UI-111)** 로 그린다. 이 자리에서 흰 배경 + 회색
                            테두리 알약을 따로 만들면 같은 행의 보조 버튼(`Button` secondary sm)과
                            형태가 같아져 사용자가 상태 표시를 조작 버튼으로 **오인한다**(실제 신고).
                            공용 배지는 테두리 없는 톤 배경 + 완전 둥근 모서리라 버튼과 형태로 갈린다.
                          ⚠ `StatusBadge`(UI-014)를 쓰지 말 것 — 그쪽은 워크플로 코드 고정 매핑 축이고
                            건너뜀·해제됨·실패는 그 목록에 없다(매핑이 두 곳으로 갈린다).
                          ⚠ 표시 **조건**은 바뀌지 않았다 — 바뀐 것은 생김새뿐이다. */}
                      {isSkipped && (
                        <Badge
                          variant="neutral"
                          label="건너뜀"
                          data-testid={`batch-stage-skipped-${bundle}`}
                        />
                      )}

                      {/* 건너뛰기가 해제된 묶음 — 상태는 색이 아니라 이 표식과 아래 문단이 말한다.
                          ⚠ 지금 건너뛴 상태이면 그쪽 표식이 앞선다(두 표식을 함께 달면 서로 반대되는
                            말을 한다 — 서버도 두 목록에 같은 묶음을 함께 담지 않는다). */}
                      {isCleared && !isSkipped && (
                        <Badge
                          variant="neutral"
                          label="해제됨"
                          data-testid={`batch-stage-reverted-${bundle}`}
                        />
                      )}

                      {/* 실패 표식 — 이 행이 왜 떠 있는지를 말한다. 건너뜀·해제 표식과 같은 축이며,
                          배치 전체가 완주한 영상에서는 이것이 유일한 실패 단서다(사유 영역이 없다).
                          ★ 여기만 위험 계열(`error`)이다 — 다른 두 표식과 **다른 것을 말하므로**
                            같은 중립 톤으로 뭉뚱그리지 않는다. 다만 색만으로 전달하지 않도록
                            「실패」 문구를 항상 함께 둔다(UI-111 의 `label` 필수 계약). */}
                      {isBundleFailed && !isSkipped && (
                        <Badge
                          variant="error"
                          label="실패"
                          data-testid={`batch-stage-failed-${bundle}`}
                        />
                      )}

                      {!isSkipped && isBundleFailed && (
                        <Button
                          variant="secondary"
                          size="sm"
                          disabled={busy}
                          onClick={() => setSkipTarget(bundle)}
                          aria-label={`${label} 작업 건너뛰기`}
                        >
                          건너뛰기
                        </Button>
                      )}

                      {/* ★ 재수행 버튼은 **하나**다 — 범위를 고르지 않는다(묶음이 곧 범위다).
                            보간을 다시 만드는 묶음일 때만 확인 창을 거치고, 그렇지 않으면 곧바로
                            접수한다(없는 위험에 확인을 받으면 확인이 형식이 되어 무시된다). */}
                      {canRerun && (
                        <Button
                          variant="secondary"
                          size="sm"
                          // ★ 기존 조건(진행 중·처리 중)을 **대체하지 않고 더한다** — 승인 이력이
                          //   없어도 처리 중이면 여전히 비활성이어야 한다.
                          disabled={busy || processing || approvedLocked}
                          aria-describedby={rerunDescribedBy}
                          title={
                            approvedLocked
                              ? '검수가 완료된 영상은 오토라벨을 다시 만들 수 없습니다 — 승인 시점 라벨과 어긋납니다.'
                              : undefined
                          }
                          onClick={() =>
                            destructive ? setRerunConfirmTarget(bundle) : rerun.mutate(bundle)
                          }
                          aria-label={`${label} 작업 재수행`}
                        >
                          재수행
                        </Button>
                      )}
                    </div>

                    {/* ★ 고르는 시점에 알린다 — 누른 뒤에 뜨는 확인 창은 취소 수단이지 고지 수단이
                          아니다. 이 문단은 클릭 전에 이미 화면에 있고 버튼이 이를 가리킨다.
                          ⚠ 보간을 다시 만드는 묶음에만 붙인다(시계열에 붙이면 오정보다). */}
                    {canRerun && destructive && (
                      <p
                        id={warningId}
                        className="text-caption text-gray-700"
                        data-testid={`batch-rerun-warning-${bundle}`}
                      >
                        이 작업을 재수행하면 트랙 보간까지 다시 만들어집니다. 사람이 손댄 보간 라벨은
                        지워지고 새로 계산된 값으로 바뀝니다.
                      </p>
                    )}
                    {/* ★ 되돌릴 수 없는 조건이라 **누르기 전에** 알린다 — 파생영상 차단과 같은
                          관례다. 비활성 버튼의 `title` 은 키보드·보조기술에 닿지 않으므로 문단을
                          함께 두고 버튼이 `aria-describedby` 로 가리킨다. */}
                    {canRerun && approvedLocked && (
                      <p
                        id={approvedLockHintId}
                        className="text-caption text-gray-700"
                        data-testid={`batch-rerun-approved-lock-${bundle}`}
                      >
                        검수가 완료된 적 있는 영상에서는 시계열만 다시 수행할 수 있습니다 — 시계열은
                        확정된 라벨을 건드리지 않고 서술만 더하지만, 오토라벨은 라벨을 다시 만들어
                        승인 시점과 어긋납니다. 되돌릴 수 없는 조건이라 눌러 보기 전에 알립니다.
                      </p>
                    )}
                    {canRerun && processing && (
                      <p
                        id={busyHintId}
                        className="text-caption text-gray-600"
                        data-testid={`batch-rerun-busy-hint-${bundle}`}
                      >
                        이미 처리 중이라 지금은 재수행을 요청할 수 없습니다. 진행 중인 처리가 끝난 뒤
                        다시 시도하세요.
                      </p>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {/* 보간을 다시 만드는 묶음의 재수행 확인 — 되돌릴 수 없는 조작이라 취소 기회를 준다.
          [@design API-201] ⚠ 이것이 고지 수단을 대신하지 않는다(위 경고 문단은 클릭 전에 이미 떠 있다). */}
      <ConfirmDialog
        open={rerunConfirmTarget !== null}
        title={`${rerunConfirmTarget ? bundleLabel(rerunConfirmTarget) : ''} 작업 재수행`}
        description="이 작업을 통째로 다시 수행합니다. 트랙 보간까지 다시 만들어져 사람이 손댄 보간 라벨은 지워지고 새로 계산된 값으로 바뀝니다. 되돌릴 수 없습니다."
        confirmLabel="재수행"
        variant="danger"
        loading={rerun.isPending}
        onCancel={() => {
          if (rerun.isPending) return;
          setRerunConfirmTarget(null);
        }}
        onConfirm={() => {
          if (!rerunConfirmTarget) return;
          rerun.mutate(rerunConfirmTarget);
        }}
      />

      <BatchStageSkipModal
        open={skipTarget !== null}
        bundleLabel={skipTarget ? bundleLabel(skipTarget) : ''}
        loading={skip.isPending}
        onClose={() => setSkipTarget(null)}
        onConfirm={(reason) => {
          if (!skipTarget) return;
          skip.mutate({ bundle: skipTarget, reason });
        }}
      />
    </section>
  );
}
