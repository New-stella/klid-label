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
import { AlertTriangle, Info, RefreshCw } from 'lucide-react';

import { Badge } from '@/components/common/Badge';
import { Button } from '@/components/common/Button';
import { bundleLabel, stageLabel } from '@/components/common/BatchStageIndicator';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

import { useRerunBatchStage, useRetryBatch, useSkipBatchStage } from '../hooks/useBatchRecovery';
import {
  STAGE_BUNDLES,
  STAGE_BUNDLE_MEMBERS,
  bundleRerunsInterpolation,
  isBatchFailed,
  isBatchProcessing,
  type StageBundle,
  type VideoDetail,
} from '../types';


import { BatchStageSkipModal } from './BatchStageSkipModal';
import { BundleTargetRow } from './BundleTargetRow';

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

/**
 * 패널 표면 골격 — 확정 시안 `.failure-panel` 정합. [@design SCREEN-009]
 *
 * 시안은 <b>흰 배경 + 1px 테두리 + 좌측 4px 강조바 + 전방향 16px 패딩 + 16px gap</b> 이다.
 *
 * ⚠ 구 틴트 배경(`bg-danger/5`·`bg-gray-50`)을 되살리지 말 것 — 그 위에 흰 카드(작업 묶음 행)를
 *   얹으면 위험 틴트 위에 카드가 떠 보인다. 패널 배경·강조바·행 표면은 <b>한 묶음으로</b> 움직인다.
 * ⚠ 자식은 각자 `mt-*` 를 갖지 않는다 — 간격은 이 컨테이너의 `gap-4` 하나가 정한다(값이 두 곳으로
 *   갈리면 한쪽만 갱신된다).
 */
const PANEL_SURFACE = 'flex flex-col gap-4 rounded-lg border border-l-4 bg-white p-4';

/** 패널 톤 — 문구가 이미 상태를 말하므로 색은 보조다(테두리·좌측 강조바에만 쓴다). */
export type BatchPanelTone = 'error' | 'neutral' | 'info' | 'warn';

const TONE_SURFACE: Record<BatchPanelTone, string> = {
  error: 'border-danger-200 border-l-danger-500',
  info: 'border-info-200 border-l-info-500',
  // 시안 `.failure-panel[data-tone="warn"]` = 테두리 --w-2 / 좌측 강조바 --w-5.
  warn: 'border-warning-200 border-l-warning-500',
  neutral: 'border-border border-l-gray-400',
};

/**
 * 모드 → 표면 톤. 시안 `.failure-panel[data-tone]` 과 같은 축이다.
 *
 * ★ 실패가 아니라 <b>건너뜀·해제만</b> 있는 상태는 시안대로 `warn`(노랑)이다. 시안 CSS 가 "행
 *   배경을 물들이면 같은 색 계열 배지가 배경에 묻혀 알약 형태를 잃는다"고 적어 <b>패널 warn 톤과
 *   배지 warn/info 색이 한 묶음</b>인데, 공용 배지(UI-111)에 두 variant 가 생겨 이번에 그 묶음을
 *   통째로 옮겼다. 한쪽만 되돌리지 말 것(노란 패널에 회색 배지가 남는다).
 *
 * ⚠ <b>[폐기]</b> 구 서술 「배지 variant 가 생기면 아래 넷을 함께 warn 으로 바꾼다」 — 그 「넷」은
 *   당시 neutral 이던 키를 센 것이고 <b>시안 근거로 warn 인 것은 둘</b>(skipped·cleared)뿐이다.
 *   `lastFailure`·`bundleFailure` 는 <b>실패</b> 계열이라 시안의 건너뜀 변형(②③⑤)에 해당하지
 *   않으며, 시안에 대응 변형 자체가 없어 지금은 근거 없이 옮기지 않는다(neutral 유지).
 */
const MODE_TONE: Record<BatchPanelMode, BatchPanelTone> = {
  processing: 'info',
  failure: 'error',
  lastFailure: 'neutral',
  bundleFailure: 'neutral',
  // 시안 ③⑤ 「건너뛰기 해제됨」 · ② 「건너뛴 작업 있음」 = data-tone="warn".
  cleared: 'warn',
  skipped: 'warn',
};

/**
 * 작업 묶음 행의 상태 — 시안 `.group-row[data-state]`. 표면은 <b>왼쪽 강조선 + 배지</b>가 알린다.
 * 행 배경을 물들이지 않는 이유는 위 {@link MODE_TONE} 주석과 같다.
 */
type GroupRowState = 'fail' | 'skipped' | 'released';

const ROW_SURFACE: Record<GroupRowState, string> = {
  fail: 'border-danger-200 border-l-danger-500',
  skipped: 'border-warning-200 border-l-warning-500',
  released: 'border-primary-200 border-l-primary-500',
};

/** 행 부제 색 — 상태를 색만으로 말하지 않으므로 보조다(문구는 항상 같은 자리에 있다). */
const ROW_SUB_TONE: Record<GroupRowState, string> = {
  fail: 'text-danger-700',
  skipped: 'text-warning-700',
  released: 'text-gray-600',
};

/**
 * 행 골격 — 시안 `.group-row` 그리드. 데스크톱(≥1280)은 <b>이름 · 배지 · 조작</b> 3열이고 조작이
 * <b>우측 정렬</b>이며, 그 아래에서는 2열로 접히고 조작이 다음 줄 좌측으로 내려간다.
 * ⚠ 브레이크포인트는 설정에 실재하는 `xl` 하나다(`md` 밖의 접두어는 죽은 클래스가 된다).
 */
const ROW_BASE =
  'grid grid-cols-[1fr_auto] items-center gap-4 rounded-md border border-l-4 bg-white px-4 py-2' +
  ' xl:grid-cols-[minmax(180px,1.4fr)_auto_minmax(0,1fr)]';

/**
 * 묶음 부제 — 이름 아래 한 줄로 <b>그 묶음이 무엇을 하는지</b>를 병기한다. [@design SCREEN-009]
 *
 * ★ 묶음 <b>이름</b>을 멤버 나열로 되돌리는 것이 아니다 — 이름은 「오토라벨링」 그대로 두고 부제만
 *   더한다(구 이름 'AI 탐지 · AI 분할 · 보간' 을 되살리지 말 것. `bundleLabel` 주석 참조).
 * ★ 오토라벨 부제는 <b>멤버 표에서 파생</b>한다. 손으로 적으면 묶음 구성이 바뀔 때 부제가 따라오지
 *   않아 화면이 없는 단계를 계속 열거한다.
 */
const BUNDLE_SUBTITLE: Record<StageBundle, string> = {
  // 멤버가 자기 자신 하나뿐이라 나열하면 이름과 같아진다 — 그래서 무엇을 만드는지를 적는다.
  VLM: '영상 서술 생성',
  AUTOLABEL: bundleMemberNames('AUTOLABEL'),
};

/**
 * 묶음이 품는 작업의 노출명 나열 — <b>멤버 표에서 파생</b>한다. [@design SCREEN-009]
 *
 * 목록 행의 부제와 건너뛰기 모달의 대상 칩이 <b>같은 문자열</b>을 쓰게 하는 단일 지점이다. 어느
 * 한쪽을 손으로 적으면 묶음 구성이나 단계 표시명이 바뀔 때 한쪽만 낡아, 화면이 없는 단계를 계속
 * 열거하거나 같은 묶음을 두 이름으로 부른다.
 */
function bundleMemberNames(bundle: StageBundle): string {
  return (STAGE_BUNDLE_MEMBERS[bundle] as readonly string[])
    .map((name) => stageLabel(name))
    .join(' · ');
}

/**
 * 멤버 나열을 <b>부제로 쓸 값</b> — 멤버가 둘 이상일 때만 있다.
 *
 * 멤버가 자기 자신 하나뿐인 묶음은 나열이 묶음 이름과 같아져 줄만 늘어난다(정보가 0). 그래서
 * 값을 넘기지 않아 줄 자체가 없게 한다 — `BatchStageSkipModal.bundleMembers` 의 규약과 같다.
 */
function bundleMemberSubtitle(bundle: StageBundle): string | undefined {
  return (STAGE_BUNDLE_MEMBERS[bundle] as readonly string[]).length > 1
    ? bundleMemberNames(bundle)
    : undefined;
}

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
 * 재수행 확인 창의 문구 — <b>묶음마다 다르다</b>. [@design SCREEN-009] [@design API-201]
 *
 * ★ <b>두 묶음 모두 확인을 거친다.</b> 시계열은 확정된 라벨을 건드리지 않아 파괴적이지 않지만,
 *   재수행이 곧 <b>외부 벤더로의 재위탁</b>이라 비용·시간이 들고 동시 처리 한도를 먹는다.
 *   여기서 확인을 받는 근거는 「되돌릴 수 없다」가 아니라 <b>「공짜가 아니다」</b>다.
 * ⚠ 구 동작 폐기: 시계열은 <b>클릭이 곧 요청</b>이었다(보간을 다시 만드는 묶음일 때만 확인).
 *   되살리지 말 것 — 시안은 두 묶음 모두 확인 창을 거치게 한다(`#dialog-rerun-vlm`).
 * ⚠ 경고 박스(`warning`)와 위험 계열 확정 버튼은 <b>보간을 다시 만드는 묶음에만</b> 붙인다.
 *   시안도 시계열 확인 창에는 `.dlg-warn` 상자를 두지 않고 확정 버튼을 `btn-primary` 로 둔다.
 *   없는 위험을 경고 상자로 알리면 그 상자가 형태로만 남아 정작 파괴적인 쪽의 경고까지 가벼워진다.
 * ⚠ 이 창이 <b>고지 수단을 대신하지 않는다</b> — 보간 재계산 경고 문단은 누르기 전에 이미 화면에 있다.
 */
interface RerunConfirmCopy {
  title: string;
  /** 본문 문단들 — 시안 `.dlg-desc` 가 <b>층으로 나눈 것</b>이라 한 문단으로 뭉치지 않는다. */
  descriptions: readonly string[];
  /** 무엇을 잃는가 — 값이 있는 묶음만 경고 상자를 얻는다(`ConfirmDialog.warning` 은 opt-in). */
  warning?: string;
  variant: 'primary' | 'danger';
}

const RERUN_CONFIRM: Record<StageBundle, RerunConfirmCopy> = {
  // 문구는 시안 `#dialog-rerun-vlm` 에서 그대로 가져왔다 — 여기서 새로 쓰지 말 것.
  VLM: {
    title: '시계열 묶음을 다시 수행할까요?',
    descriptions: [
      '이 묶음만 수행하고 다른 묶음은 건드리지 않습니다. 시계열은 확정된 라벨을 건드리지 않고 영상 서술만 새로 받아 옵니다.',
      '건너뛴 상태였다면 이 조작이 함께 해제합니다 — 따로 해제할 필요가 없습니다. 접수까지만 즉시 확인되고 실행은 뒤에서 이어집니다.',
    ],
    variant: 'primary',
  },
  AUTOLABEL: {
    title: `${bundleLabel('AUTOLABEL')} 작업 재수행`,
    descriptions: ['이 작업을 통째로 다시 수행합니다.'],
    // 되돌릴 수 없다는 사실은 설명 문단이 아니라 **경고 박스**로 알린다(시안 `.dlg-warn`).
    // ⚠ 문구는 나누기만 했고 새로 쓰지 않았다 — 「무엇을 하는가」와 「무엇을 잃는가」를 층으로 가른다.
    warning:
      '트랙 보간까지 다시 만들어져 사람이 손댄 보간 라벨은 지워지고 새로 계산된 값으로 바뀝니다. 되돌릴 수 없습니다.',
    variant: 'danger',
  },
};

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
  /** 확인 창에 실을 문구 — 대상이 없으면(창이 닫혀 있으면) null 이다. */
  const rerunConfirm = rerunConfirmTarget ? RERUN_CONFIRM[rerunConfirmTarget] : null;

  // 조작 행을 그릴 묶음 — 서버가 실패로 판정했거나 건너뛴 적이 있는 묶음(건너뜀·해제 모두).
  const actionableBundles = STAGE_BUNDLES.filter(
    (bundle) =>
      skipped.includes(bundle) || cleared.includes(bundle) || failedBundles.includes(bundle),
  );

  // ★ 파생영상은 조작이 노출되지 않는 알림 전용이라 톤도 중립이다(시안 data-tone="neutral").
  const tone: BatchPanelTone = isDerivative ? 'neutral' : MODE_TONE[mode];
  // 이 영역을 누가 보는가 — 시안 `.fp-head` 우측 배지.
  //   ⚠ 시안의 「검수자 전용」은 secondary 톤이지만 공용 배지(UI-111)에 그 variant 가 없어 중립으로
  //     둔다. 파생영상 배지와 색이 같아지지만 두 배지는 배타라 한 화면에서 겹치지 않는다.
  const audienceLabel = isDerivative ? '파생영상' : everApproved ? '검수 완료' : '검수자 전용';
  const audienceVariant = !isDerivative && everApproved ? 'success' : 'neutral';
  // 파생영상에는 전체 재기동을 두지 않는다(구 구현이 파생 분기에서 조작 전체를 감췄던 것과 같다).
  const showRetry = canShowRetry && !isDerivative;
  // 서버 문구임을 드러내는 표식(시안 `.fp-verbatim`)은 **단계 칩이 없을 때**만 둔다 — 칩이 있으면
  // 사유가 어디서 왔는지가 이미 분명하고, 늘 붙이면 표식이 배경 소음이 된다.
  const showVerbatimNote = failed && (!failedStage || isDerivative);

  return (
    <section
      aria-labelledby="batch-failure-heading"
      data-testid="batch-failure-panel"
      // 세 모드는 표면색이 다르지만 **판정은 색이 아니라 제목 문구**가 한다(아래 h4).
      data-mode={mode}
      // 시안 `.failure-panel[data-tone]` 과 같은 축 — 표면 규칙이 되돌려지면 여기서 먼저 드러난다.
      data-tone={tone}
      className={`${PANEL_SURFACE} ${TONE_SURFACE[tone]}`}
    >
      {/* 헤드 — 제목 + 이 영역을 누가 보는가(시안 `.fp-head`). */}
      <div className="flex flex-wrap items-center gap-2">
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
          className="flex items-center gap-1.5 text-title-sm font-semibold text-gray-950"
        >
          {!processing && <AlertTriangle size={16} aria-hidden />}
          {MODE_HEADING[mode]}
        </h4>
        {/* 시안 `.fp-spacer` — 배지를 헤드 우측 끝으로 민다. */}
        <span className="flex-1" aria-hidden />
        <Badge
          variant={audienceVariant}
          label={audienceLabel}
          data-testid="batch-failure-audience"
        />
      </div>

      {/* 처리 중임을 **말로** 알린다 — 접수됐고 순서를 기다리는 중일 수 있다는 뜻이 전달돼야 한다.
          ⚠ 내부 실행기·큐·풀 같은 구현 용어는 노출하지 않는다(사용자에게 의미 없고 CWE-209). */}
      {processing && (
        <p className="text-body-md text-gray-700" data-testid="batch-processing-note">
          요청이 접수되어 배치 처리 중입니다. 앞선 작업이 있으면 순서를 기다린 뒤 시작합니다. 진행
          상황은 처리 단계에서 확인하세요.
        </p>
      )}

      {/* 실패 사유 영역은 **실패 기록이 있을 때만** 둔다. 건너뛰기만 남은 영상에는 보여줄 사유가
          없으므로 빈 자리를 만들지 않고 통째로 생략한다(없는 사유를 '없음'으로 채워 넣지 않는다).
          ★ 지금 실패 상태가 아니면(처리 중이거나 이미 완주) 기록은 지우지 않고 「직전」으로 이름만
             바꿔 보여준다 — 무엇 때문에 실패했는지가 사라지면 안 되고, 그렇다고 현재 실패로 읽혀도
             안 된다. 판정은 로그(`failed`)가 아니라 **영상 상태**(`currentlyFailed`)가 한다.
          ★ 표면은 시안 `.alert.alert-error` — 경고 아이콘 + 위험 계열 박스다. 구 평문 `<dl>` 로
             되돌리지 말 것(사유가 본문과 같은 무게로 읽혀 눈에 걸리지 않았다).
          ⚠ 사유에는 **이름표를 붙이지 않는다**(시안에 그 라벨이 없다). 「직전」인지는 위 제목과
             아래 단계 이름표가 말한다. */}
      {failed && (
        <div
          className="flex items-start gap-2 rounded-md border border-danger-200 bg-danger-50 p-4"
          data-testid="batch-failure-alert"
        >
          <AlertTriangle
            size={20}
            className="mt-0.5 shrink-0 text-danger-700"
            aria-hidden
          />
          <div className="flex min-w-0 flex-col gap-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-label text-danger-700">
                {currentlyFailed ? '실패 단계' : '직전 실패 단계'}
              </span>
              {failedStage ? (
                // 시안 `.fp-stage-chip` — 단계를 본문에서 떼어 알약으로 세운다.
                <span
                  className="inline-flex items-center gap-1.5 rounded-sm bg-danger-100 px-2.5 py-0.5 text-label text-danger-700"
                  data-testid="batch-failure-stage"
                >
                  <span
                    className="h-1.5 w-1.5 shrink-0 rounded-full bg-danger-500"
                    aria-hidden
                  />
                  {stageLabel(failedStage.name)}
                </span>
              ) : (
                <span className="text-body-md text-danger-700" data-testid="batch-failure-stage">
                  확인 불가 — 단계를 특정할 수 없는 실패입니다.
                </span>
              )}
            </div>
            {/* 서버 문구 그대로. React 자동 escape 로 렌더된다(dangerouslySetInnerHTML 미사용). */}
            <p
              className="whitespace-pre-wrap text-body-md text-danger-800"
              data-testid="batch-failure-reason"
            >
              {video.batchFailureReason ?? '기록된 사유가 없습니다.'}
            </p>
            {showVerbatimNote && (
              <p
                className="flex items-center gap-1 text-caption text-danger-700"
                data-testid="batch-failure-verbatim"
              >
                <Info size={16} className="shrink-0" aria-hidden />
                문구는 서버가 보낸 그대로입니다.
              </p>
            )}
          </div>
        </div>
      )}

      {/* 건너뛴 묶음이 남아 있다는 사실은 여기서만 드러난다 — 표시기는 그 단계들을 완료로 그린다. */}
      {skipped.length > 0 && (
        <p className="text-body-md text-gray-700" data-testid="batch-skipped-note">
          아래 작업은 건너뛰도록 기록되어 있어 배치를 다시 실행해도 수행하지 않습니다. 재수행하면 그
          작업만 다시 수행하고 건너뛰기 기록도 함께 풀립니다.
        </p>
      )}

      {/* 작업 묶음 목록 — 조작의 단위다. 파생영상에는 조작이 없으므로 목록 자체를 두지 않는다
          (그 사유는 아래 푸터가 말한다). */}
      {!isDerivative && actionableBundles.length > 0 && (
        <div>
          {/* 시안 `.fp-group-head` — 사유와 푸터 사이의 이 덩어리에 이름을 준다. 없으면 패널이
              「사유 → (이름 없는 목록) → 버튼」이 되어 가운데가 무엇인지 말하지 않는다. */}
          <h5 className="text-label text-gray-800">작업 묶음</h5>
          <ul className="mt-2 flex flex-col gap-2">
            {actionableBundles.map((bundle) => {
              const label = bundleLabel(bundle);
              const isSkipped = skipped.includes(bundle);
              const isCleared = cleared.includes(bundle);
              // ★ 서버가 이 묶음을 실패로 판정했는가 — 건너뛰기 노출의 **단일 근거**다.
              const isBundleFailed = failedBundles.includes(bundle);
              // ★ 재수행 창구는 **건너뛴 적이 있는 묶음** 전부에 둔다 — 서버가 건너뛴 상태도
              //   직접 수락하므로 해제를 먼저 부를 필요가 없다(ADR-050).
              const canRerun = isSkipped || isCleared;
              // 행 상태 — 배지 노출 순서와 **같은 우선순위**로 정한다(둘이 갈리면 왼쪽 강조선과
              // 배지가 서로 다른 상태를 말한다). 해제와 실패는 공존할 수 있고 그때는 실패가 이긴다.
              const rowState: GroupRowState = isSkipped
                ? 'skipped'
                : isBundleFailed
                  ? 'fail'
                  : 'released';
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
                <li
                  key={bundle}
                  data-state={rowState}
                  data-testid={`batch-stage-row-${bundle}`}
                  className={`${ROW_BASE} ${ROW_SURFACE[rowState]}`}
                >
                  {/* 시안 `.group-main` — 이름 + 부제. 부제는 이름이 무엇을 묶은 것인지 말한다. */}
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="text-title-sm text-gray-950">{label}</span>
                    <span
                      className={`text-caption ${ROW_SUB_TONE[rowState]}`}
                      data-testid={`batch-stage-subtitle-${bundle}`}
                    >
                      {BUNDLE_SUBTITLE[bundle]}
                    </span>
                  </div>

                  {/* 상태 표식 열 — 해제와 실패는 공존할 수 있어 한 칸에 둘이 설 수 있다. */}
                  <div className="flex flex-wrap items-center gap-2">
                    {/* ⚠ 「건너뛰기 해제」 버튼은 두지 않는다(ADR-050) — 되살릴 창구는 재수행 하나다.
                        ★ 세 표식은 **공용 배지(UI-111)** 로 그린다. 이 자리에서 흰 배경 + 회색
                          테두리 알약을 따로 만들면 같은 행의 보조 버튼(`Button` secondary sm)과
                          형태가 같아져 사용자가 상태 표시를 조작 버튼으로 **오인한다**(실제 신고).
                          공용 배지는 테두리 없는 톤 배경 + 완전 둥근 모서리라 버튼과 형태로 갈린다.
                        ⚠ `StatusBadge`(UI-014)를 쓰지 말 것 — 그쪽은 워크플로 코드 고정 매핑 축이고
                          건너뜀·해제됨·실패는 그 목록에 없다(매핑이 두 곳으로 갈린다).
                        ⚠ 표시 **조건**은 바뀌지 않았다 — 바뀐 것은 생김새뿐이다.
                        ★ 시안대로 건너뜀=warn · 해제됨=info 다. 공용 배지(UI-111)에 두 variant 가
                          생겨 패널 톤(위 {@link MODE_TONE})과 **함께** 옮겼다 — 배지만 먼저 옮기거나
                          패널 톤만 먼저 옮기지 말라는 구 주의는 그 둘이 한 묶음이라는 뜻이었고,
                          이번에 그 묶음을 통째로 옮긴 것이다. 중립으로 되돌리지 말 것. */}
                    {isSkipped && (
                      <Badge
                        variant="warn"
                        label="건너뜀"
                        data-testid={`batch-stage-skipped-${bundle}`}
                      />
                    )}

                    {/* 건너뛰기가 해제된 묶음 — 상태는 색이 아니라 이 표식과 아래 문단이 말한다.
                        ⚠ 지금 건너뛴 상태이면 그쪽 표식이 앞선다(두 표식을 함께 달면 서로 반대되는
                          말을 한다 — 서버도 두 목록에 같은 묶음을 함께 담지 않는다). */}
                    {isCleared && !isSkipped && (
                      <Badge
                        variant="info"
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
                  </div>

                  {/* 조작 열 — 시안은 데스크톱에서 **우측 정렬**이고 좁은 폭에서는 다음 줄 좌측이다. */}
                  <div className="col-span-full flex flex-wrap items-center justify-start gap-2 xl:col-span-1 xl:justify-end">
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
                        ★ **두 묶음 모두 확인 창을 거친다**(시안). 시계열은 파괴적이지 않지만 재수행이
                          곧 외부 벤더 재위탁이라 비용·시간이 든다 — 확인의 근거는 「되돌릴 수 없다」가
                          아니라 「공짜가 아니다」다({@link RERUN_CONFIRM} 주석).
                        ⚠ **[폐기]** 구 동작 «보간을 다시 만드는 묶음일 때만 확인, 그 밖에는 클릭이 곧
                          요청» — 되살리지 말 것. 확인이 형식이 되는 것은 **경고 상자**를 없는 위험에
                          붙일 때이지 확인 창 자체를 두는 것이 아니라, 위험의 층은 `warning` 이 가른다.
                        ⚠ 시안에서 이 행의 **주 행동**이라 primary 다 — 같은 행의 건너뛰기(보조)와
                          위계가 갈려야 무엇을 먼저 누를지가 형태로 읽힌다. */}
                    {canRerun && (
                      <Button
                        variant="primary"
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
                        onClick={() => setRerunConfirmTarget(bundle)}
                        aria-label={`${label} 작업 재수행`}
                      >
                        재수행
                      </Button>
                    )}
                  </div>

                  {/* ★ 고르는 시점에 알린다 — 누른 뒤에 뜨는 확인 창은 취소 수단이지 고지 수단이
                        아니다. 이 문단은 클릭 전에 이미 화면에 있고 버튼이 이를 가리킨다.
                      ⚠ 보간을 다시 만드는 묶음에만 붙인다(시계열에 붙이면 오정보다).
                      ⚠ 행이 그리드라 안내 문단은 행 전체 폭을 차지해야 한다(`col-span-full`). */}
                  {canRerun && destructive && (
                    <p
                      id={warningId}
                      className="col-span-full text-caption text-gray-700"
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
                      className="col-span-full text-caption text-gray-700"
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
                      className="col-span-full text-caption text-gray-600"
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
        </div>
      )}

      {/* 푸터 — 시안 `.fp-foot`. 구분선 위에 **좌측 안내 · 우측 조작**을 둔다.
          ★ 전체 재기동은 묶음 목록 **위**가 아니라 여기 맨 아래다 — 묶음별 조치를 먼저 읽고 마지막
            수단으로 전체를 다시 돌리는 순서가 되어야 한다.
          재실행은 **실패 상태인 영상만** 대상이다(서버도 그 상태에서만 선점한다). 건너뛰기만
          남은 영상이나 **완주한 영상**에 버튼을 두면 누를 때마다 막히는 동선이 된다 — 후자가
          바로 묶음 재수행이 실패해 원상 복구된 영상이며, 그 화면의 이 버튼은 **항상** 막혔다.
          ★ 같은 이유로 **처리 중에는 비활성**이다 — 그때 누르면 서버가 반드시 막는다(이미
            선점됨). 비활성 사유는 좌측 안내가 말하고, 버튼이 `aria-describedby` 로 그것을
            가리킨다(왜 못 누르는지가 보조기술에도 전달돼야 한다). */}
      {(showRetry || isDerivative) && (
        <div
          className="flex flex-wrap items-center justify-between gap-4 border-t border-border pt-2"
          data-testid="batch-failure-foot"
        >
          {isDerivative ? (
            <p
              className="max-w-[560px] text-caption text-gray-600"
              data-testid="batch-failure-derivative-note"
            >
              이 영상은 원본에서 만들어진 파생영상이라 배치 파이프라인을 타지 않습니다. 이 화면에서는
              재실행이나 작업 건너뛰기를 할 수 없습니다.
            </p>
          ) : (
            <p
              id={RETRY_HINT_ID}
              className="max-w-[560px] text-caption text-gray-600"
              data-testid="batch-retry-hint"
            >
              {processing
                ? '이미 처리 중이라 지금은 다시 요청할 수 없습니다. 진행 중인 처리가 끝난 뒤 결과를 확인하세요.'
                : '요청을 접수하면 실패한 단계부터 이어서 진행합니다. 이미 성공한 단계는 다시 수행하지 않습니다. 진행 상황은 처리 단계에서 확인하세요.'}
            </p>
          )}
          {showRetry && (
            <div className="flex items-center gap-2">
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
            </div>
          )}
        </div>
      )}

      {/* 묶음 재수행 확인 — **두 묶음 모두** 거친다. 문구·경고 상자·확정 버튼 계열은 묶음마다
          다르며 그 판정은 {@link RERUN_CONFIRM} 한 곳이 소유한다. [@design SCREEN-009] [@design API-201]
          ⚠ 이것이 고지 수단을 대신하지 않는다(위 경고 문단은 클릭 전에 이미 떠 있다). */}
      {rerunConfirm && (
        <ConfirmDialog
          open
          title={rerunConfirm.title}
          description={
            /* 시안은 본문을 두 문단으로 나눈다(`.dlg-desc` ×2). `Modal` 이 설명을 `<p>` 로 감싸므로
               문단을 `<p>` 로 겹치지 않고 **블록 span** 으로 그린다(중첩 `<p>` 는 잘못된 마크업이다). */
            rerunConfirm.descriptions.map((text, i) => (
              <span key={text} className={i === 0 ? 'block' : 'mt-2 block text-body-sm'}>
                {text}
              </span>
            ))
          }
          {...(rerunConfirm.warning !== undefined ? { warning: rerunConfirm.warning } : {})}
          confirmLabel="재수행"
          variant={rerunConfirm.variant}
          loading={rerun.isPending}
          onCancel={() => {
            if (rerun.isPending) return;
            setRerunConfirmTarget(null);
          }}
          onConfirm={() => {
            if (!rerunConfirmTarget) return;
            rerun.mutate(rerunConfirmTarget);
          }}
        >
          {/* 대상 칩 행 — 시안 `.dlg-target` 은 확인 창 **첫 줄**이다(설명보다 위). 표시명은
              `bundleLabel`, 부제는 {@link BUNDLE_SUBTITLE} 로 **이미 있는 단일 원천을 그대로
              재사용**한다 — 여기서 문자열을 새로 적으면 목록 행의 부제와 갈린다.
              ⚠ 부제 규칙이 건너뛰기 모달과 **다른 것이 의도**다: 건너뛰기는 «멤버 나열»
              (`bundleMemberSubtitle`)이라 멤버가 하나뿐인 시계열에는 줄이 없고, 재수행은
              «그 묶음이 무엇을 만드는가»라 시계열에도 「영상 서술 생성」이 붙는다. 시안의 네
              확인 창(`#dialog-skip-*` · `#dialog-rerun-*`)이 정확히 그렇게 갈려 있다. */}
          {rerunConfirmTarget && (
            <BundleTargetRow
              label={bundleLabel(rerunConfirmTarget)}
              subtitle={BUNDLE_SUBTITLE[rerunConfirmTarget]}
            />
          )}
        </ConfirmDialog>
      )}

      <BatchStageSkipModal
        open={skipTarget !== null}
        bundleLabel={skipTarget ? bundleLabel(skipTarget) : ''}
        bundleMembers={skipTarget ? bundleMemberSubtitle(skipTarget) : undefined}
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
