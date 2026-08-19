// BatchFailurePanel — 배치 실패 사유 + 조치 (REVIEWER 전용).
// [@design SCREEN-009] [@design API-043] [@design API-167] [@design API-198] [@design API-200]
// [@design API-201]
//
// ★ 이 패널은 BatchStageIndicator 안이 아니라 **바깥**에 산다.
//   그 표시기는 마킹 화면(MarkingPage)과 공유하므로 조작 버튼을 표시기 안에 넣으면 마킹 화면에도
//   그대로 나타난다. 확정 사양(SCREEN-009 sections[2])이 이를 명시적으로 금지한다.
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
//   - 건너뛰기/건너뛰기 해제는 두 묶음에서만 노출한다(비식별·마킹·프레임추출은 어느 묶음에도 없다).
//   - **건너뛰기 버튼은 그 묶음의 단계가 실패했을 때만** 노출한다(사양이 "실패 시 건너뛴다"이므로
//     넓히지 않는다). 반대로 **건너뛰기 해제는 실패 여부와 무관**하게 건너뛴 묶음 전부에 노출한다.
//   - **건너뛰기를 해제한 묶음에는 재수행 버튼을 하나** 둔다 — 범위를 고르지 않는다(묶음이 곧 범위다).
//     전체 재기동을 완주 영상에 쓰면 파이프라인이 통째로 돌아 사람이 손댄 보간 라벨이 전량 지워진다
//     — 그래서 **문제가 생긴 곳부터** 재시도한다.
//   - 파생영상에는 조작을 노출하지 않는다 — 파생은 배치 파이프라인을 타지 않아 재실행으로
//     복구되지 않는다(사유는 그대로 보여준다).
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

import { Button } from '@/components/common/Button';
import { bundleLabel, stageLabel } from '@/components/common/BatchStageIndicator';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

import {
  useRerunBatchStage,
  useRetryBatch,
  useSkipBatchStage,
  useUnskipBatchStage,
} from '../hooks/useBatchRecovery';
import {
  STAGE_BUNDLES,
  bundleOfStage,
  bundleRerunsInterpolation,
  isBatchFailed,
  isBatchProcessing,
  type StageBundle,
  type VideoDetail,
} from '../types';


import { BatchStageSkipModal } from './BatchStageSkipModal';

/** 패널 판정에 필요한 최소 필드 — 전체 상세를 요구하지 않아 호출부·테스트가 가벼워진다. */
type BatchAttentionFields = Pick<VideoDetail, 'batchFailureReason' | 'stages' | 'skippedStages'>;

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
 * 패널을 노출해야 하는가 — **노출의 단일 판정**. [@design SCREEN-009]
 *
 * ★ 실패 여부만 보면 안 된다. 스킵 표식은 영구라 ①단계를 건너뛰고 ②재기동이 성공하면 ③실패가
 * 사라져 패널이 통째로 없어지고 ④그 단계는 이후 모든 재기동에서 조용히 건너뛰어지는데 화면에는
 * 완료로 보인다. 해제할 진입점이 어디에도 남지 않는다.
 */
export function needsBatchAttention(video: BatchAttentionFields): boolean {
  return hasBatchFailure(video) || (video.skippedStages ?? []).length > 0;
}

/** 패널이 말하는 상태 — 축들은 서로를 지우지 않는다(아래 {@link batchPanelMode} 주석). */
export type BatchPanelMode = 'processing' | 'failure' | 'lastFailure' | 'skipped';

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
 *   <li><b>skipped</b> — 실패 기록도 처리 중도 아닌데 패널이 떠 있다면 남은 이유는 스킵 표식뿐이다.</li>
 * </ul>
 *
 * ⚠ 이번에 가른 것은 <b>「실패 사유가 있다」(`hasBatchFailure`)와 「지금 실패 상태다」
 * (`isBatchFailed`)</b> 둘뿐이다 — 처리 중·스킵만 분기의 조건은 <b>글자 그대로 그대로</b>다.
 * `hasBatchFailure` 가 거짓이면 이 함수는 상태와 무관하게 예전처럼 `skipped` 를 돌려준다.
 *
 * ⚠ 처리 중이 실패를 <b>가리는 것</b>과 <b>지우는 것</b>은 다르다 — `hasBatchFailure` 는 그대로
 * 유지되며 실패 기록은 「직전 실패」로 계속 보인다(`lastFailure` 도 같은 어휘를 쓴다). 마찬가지로
 * 스킵 목록·건너뛰기 해제는 모드와 무관하게 항상 렌더된다(스킵 축이 다른 축에 먹히면 해제할 창구가
 * 또 사라진다 — 이미 한 번 난 결함).
 */
export function batchPanelMode(video: BatchPanelFields): BatchPanelMode {
  if (isBatchProcessing(video)) return 'processing';
  if (hasBatchFailure(video)) return isBatchFailed(video) ? 'failure' : 'lastFailure';
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
  skipped: '건너뛴 작업 있음',
};

/** 표면 톤 — 문구가 이미 상태를 말하므로 색은 보조다. */
const MODE_SURFACE: Record<BatchPanelMode, string> = {
  processing: 'rounded-lg border border-info/40 bg-info/5 px-4 py-3',
  failure: 'rounded-lg border border-danger/40 bg-danger/5 px-4 py-3',
  // 지금 실패 상태가 아니므로 위험 톤을 쓰지 않는다 — 사실 관계는 제목 문구가 말한다.
  lastFailure: 'rounded-lg border border-gray-300 bg-gray-50 px-4 py-3',
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
 * 이 화면 세션에서 **건너뛰기를 해제한** 작업 묶음 — 재수행 버튼 노출의 근거. [@design API-201]
 *
 * ★ 서버 응답에는 이 목록이 없다. `skippedStages`(API-043)는 <b>지금 건너뛴 상태</b>인 묶음만
 * 담으므로, 해제하는 순간 그 묶음은 목록에서 빠지고 "해제했다"는 사실은 어디에도 남지 않는다.
 * 서버에 해제 이력 자체는 있으나(스킵 해제 시 표식 행을 남긴다) 응답 계약에 노출되지 않으며,
 * 없는 필드를 추정해 만들지 않는다 — 그래서 <b>건너뛰기 해제가 성공한 직후의 로컬 상태</b>를 근거로 쓴다.
 *
 * ⚠ 이 근거의 한계(의도적으로 감수): 새로고침·다른 화면 경유 후 재진입하면 이 상태가 사라져
 * 재수행 버튼이 보이지 않는다. 그때는 다시 건너뛰었다가 해제하는 우회밖에 없다. 화면을 떠나도
 * 남게 하려면 응답이 건너뛰기를 해제한 묶음을 내려줘야 하며 그것은 서버 계약 변경이다.
 *
 * `rawSn` 을 함께 들고 다니는 이유는 <b>다른 영상으로 이동해도 컴포넌트가 재마운트되지 않을 수</b>
 * 있기 때문이다 — 영상이 바뀌면 이 상태는 통째로 무효다.
 */
interface RevertedBundles {
  rawSn: number;
  bundles: StageBundle[];
}

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
  const [reverted, setReverted] = useState<RevertedBundles>({ rawSn: video.id, bundles: [] });

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
      // 다시 건너뛴 묶음은 더 이상 "건너뛰기를 해제한 묶음"이 아니다 — 재수행 버튼을 남기면 서버가 400 으로
      // 막는 버튼이 화면에 남는다(대상은 실제로 건너뛰기를 해제한 묶음뿐이다).
      setReverted((prev) => ({
        rawSn: video.id,
        bundles: prev.rawSn === video.id ? prev.bundles.filter((b) => b !== data.stage) : [],
      }));
    },
    onError: (err) => {
      pushToast({
        variant: 'error',
        message: errorMessageOf(err, '건너뛰기를 기록하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      });
      setSkipTarget(null);
    },
  });

  const unskip = useUnskipBatchStage(video.id, {
    onSuccess: (bundle) => {
      pushToast({
        variant: 'success',
        message: '건너뛰기를 해제했습니다. 이 작업을 다시 수행하려면 아래 재수행을 사용하세요.',
      });
      // ★ 여기가 「건너뛰기를 해제한 묶음」을 아는 유일한 지점이다(위 RevertedBundles 주석) — 서버 응답에는
      //   해제 목록이 없고, `skippedStages` 는 이 성공과 동시에 그 묶음을 빼 버린다.
      setReverted((prev) => ({
        rawSn: video.id,
        bundles:
          prev.rawSn === video.id
            ? prev.bundles.includes(bundle)
              ? prev.bundles
              : [...prev.bundles, bundle]
            : [bundle],
      }));
    },
    onError: (err) =>
      pushToast({
        variant: 'error',
        message: errorMessageOf(err, '건너뛰기를 해제하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      }),
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
  // 건너뛰기를 해제한 묶음 — 다른 영상의 잔재는 버리고, 그 사이 다시 건너뛴 묶음도 제외한다(서버가 진실원인
  // 축을 로컬 기억이 이기지 않게 한다).
  const revertedBundles =
    reverted.rawSn === video.id ? reverted.bundles.filter((b) => !skipped.includes(b)) : [];

  // ★ 해제한 직후에는 실패도 스킵도 없을 수 있다(건너뛴 채 완주한 영상을 해제한 경우). 그때 패널이
  //   사라지면 방금 만든 재수행 창구가 같이 사라진다.
  if (!needsBatchAttention(video) && revertedBundles.length === 0) return null;

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
  //   ⚠ 실패 기록 자체가 없으면(건너뛰기 해제만 한 영상 등) 예전처럼 두지 않는다 — 조건을 넓히지 않는다.
  const canShowRetry = failed && (currentlyFailed || processing);
  const failedStage = stages.find((s) => s.status === 'FAIL') ?? null;
  // 실패한 **단계**가 속한 **묶음** — 진행 축(단계)과 조작 축(묶음)을 잇는 유일한 해석 지점이다.
  // 어느 묶음에도 없는 단계(비식별·마킹·프레임추출)가 실패하면 null 이라 건너뛰기 버튼이 생기지 않는다.
  const failedBundle = failedStage ? bundleOfStage(failedStage.name) : null;
  const isDerivative = video.derivative === true;
  const busy = retry.isPending || skip.isPending || unskip.isPending || rerun.isPending;

  // 조작 행을 그릴 묶음 — 건너뛸 수 있거나(그 묶음의 단계가 실패) 이미 건너뛴 묶음 또는 건너뛰기를 해제한 묶음.
  const actionableBundles = STAGE_BUNDLES.filter(
    (bundle) =>
      skipped.includes(bundle) || revertedBundles.includes(bundle) || failedBundle === bundle,
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
          ⚠ 경고 아이콘은 실패·스킵에만 붙인다 — 정상 진행 중인 영상에 경고 글리프를 붙이면
             문구와 아이콘이 서로 다른 말을 한다(아이콘은 장식이고 문구가 정보다). */}
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
          아래 작업은 건너뛰도록 기록되어 있어 배치를 다시 실행해도 수행하지 않습니다. 해제하면 이후
          실행에서 다시 수행합니다.
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
                const isReverted = revertedBundles.includes(bundle);
                // ★ 이 묶음을 재수행하면 보간이 다시 만들어지는가 — 경고·확인의 단일 판정이다.
                //   묶음 구성에서 파생하므로 구성이 바뀌면 경고가 자동으로 따라온다.
                const destructive = bundleRerunsInterpolation(bundle);
                const warningId = rerunWarningId(bundle);
                const busyHintId = rerunBusyHintId(bundle);
                const rerunDescribedBy =
                  [destructive ? warningId : null, processing ? busyHintId : null]
                    .filter(Boolean)
                    .join(' ') || undefined;
                return (
                  <li key={bundle} className="flex flex-col gap-1">
                    <div className="flex flex-wrap items-center gap-2 text-body-md">
                      <span className="text-gray-800">{label}</span>
                      {isSkipped && (
                        <>
                          <span
                            className="rounded border border-gray-300 bg-white px-1.5 py-0.5 text-caption text-gray-700"
                            data-testid={`batch-stage-skipped-${bundle}`}
                          >
                            건너뜀
                          </span>
                          <Button
                            variant="secondary"
                            size="sm"
                            disabled={busy}
                            onClick={() => unskip.mutate(bundle)}
                            aria-label={`${label} 작업 건너뛰기 해제`}
                          >
                            건너뛰기 해제
                          </Button>
                        </>
                      )}

                      {/* 건너뛰기를 해제한 묶음 — 상태는 색이 아니라 이 표식과 아래 문단이 말한다. */}
                      {isReverted && (
                        <span
                          className="rounded border border-gray-300 bg-white px-1.5 py-0.5 text-caption text-gray-700"
                          data-testid={`batch-stage-reverted-${bundle}`}
                        >
                          해제됨
                        </span>
                      )}

                      {!isSkipped && failedBundle === bundle && (
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
                      {isReverted && !isSkipped && (
                        <Button
                          variant="secondary"
                          size="sm"
                          disabled={busy || processing}
                          aria-describedby={rerunDescribedBy}
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
                    {isReverted && !isSkipped && destructive && (
                      <p
                        id={warningId}
                        className="text-caption text-gray-700"
                        data-testid={`batch-rerun-warning-${bundle}`}
                      >
                        이 작업을 재수행하면 트랙 보간까지 다시 만들어집니다. 사람이 손댄 보간 라벨은
                        지워지고 새로 계산된 값으로 바뀝니다.
                      </p>
                    )}
                    {isReverted && !isSkipped && processing && (
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
