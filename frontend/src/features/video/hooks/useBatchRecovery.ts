// 배치 실패 관리 뮤테이션 훅 — 재실행 / 단계 스킵 / 건너뛴 적이 있는 단계 재수행 / 일괄 재시작
// / 시계열 묶음 일괄 건너뛰기·재수행.
// [@design API-167] [@design API-198] [@design API-199] [@design API-201]
// [@design API-212] [@design API-214] [@design ADR-050]
//
// ★ **건너뛰기 해제 훅(구 `useUnskipBatchStage`·`useBulkClearBatchStageSkip`)은 폐기됐다** —
//   되살리지 말 것. 재수행이 건너뛴 상태를 직접 수락하고 해제 표식까지 함께 남기므로, 화면이
//   해제를 따로 부를 이유가 없다(해제만 하고 재수행을 안 한 영상이 생기던 중간 상태도 사라진다).
//   서버의 해제 엔드포인트와 그 API 클라이언트(`api.unskipBatchStage`·`clearBatchStageSkipBulk`)는
//   계약으로 남아 있으나 **어떤 화면도 호출하지 않는다**.
//
// ★ 재기동(단건·일괄) 응답은 **접수 결과**다 — 파이프라인이 끝났다는 뜻이 아니다.
//   서버는 실패 상태를 선점하는 것까지만 요청 안에서 처리하고 실제 실행은 비동기로 넘긴다.
//   따라서 호출부는 성공 응답을 "완료"로 읽는 문구를 쓰면 안 되고, 진행은 영상 상세의 단계 표시로
//   확인시킨다. (응답 스키마는 그대로이며 바뀐 것은 값의 의미와 사용자 문구뿐이다.)
//
// 캐시 정책: 네 조작 모두 **게이트가 닫히는 변화가 아니므로** `invalidateQueries` 를 쓴다.
//   (`removeQueries` 는 비식별 신고 접수처럼 "이후 요청을 서버가 막게 되는" 축 전용이다 —
//    여기서 캐시를 버리면 화면이 잠깐 비었다가 다시 그려질 뿐 얻는 것이 없다.)

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import {
  rerunBatchStage,
  rerunBatchStageBulk,
  retryBatch,
  retryBatchBulk,
  skipBatchStage,
  skipBatchStageBulk,
} from '../api';
import type {
  BatchBulkRetryResult,
  BatchRetryResult,
  BatchStageRerunResult,
  BatchStageSkipResult,
  StageBundle,
} from '../types';

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/**
 * 배치 재실행 **접수** (POST /videos/{rawSn}/batch/retry).
 *
 * 성공 시 `VIDEO_KEYS.all` 을 무효화한다 — 재기동은 **상세의 단계 표시뿐 아니라 목록의 처리 단계
 * 배지**도 바꾸므로 상세만 갱신하면 목록이 실패 상태로 남아 사용자가 같은 영상을 다시 고른다.
 *
 * ⚠ 이 무효화는 **첫 갱신**을 유도할 뿐이다. 접수 직후에는 진행 로그가 아직 갱신되지 않아 그
 * 한 번의 재조회가 옛 실패 상태를 그대로 볼 수 있다 — 그 뒤를 따라가는 것은 상세 화면의 폴링
 * 유예 창(`useVideoDetail` 의 `pollUntil`)이며, 호출부가 접수 시각을 그쪽에 넘겨야 성립한다.
 */
export function useRetryBatch(rawSn: number, options: MutationOptions<BatchRetryResult> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => retryBatch(rawSn),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 작업 묶음 수동 스킵 (POST /videos/{rawSn}/batch/stages/{stage}/skip).
 *
 * 스킵은 진행 축(`stages`)을 바꾸지 않고 `skippedStages` 만 바꾸므로 상세만 무효화한다.
 */
export function useSkipBatchStage(
  rawSn: number,
  options: MutationOptions<BatchStageSkipResult> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { bundle: StageBundle; reason: string }) =>
      skipBatchStage(rawSn, vars.bundle, vars.reason),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.detail(rawSn) });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 건너뛴 적이 있는 작업 묶음 **재수행 접수** (POST /videos/{rawSn}/batch/stages/{stage}/rerun).
 * [@design API-201] [@design ADR-050]
 *
 * ★ **건너뛴 상태도 직접 수락한다** — 해제를 먼저 부를 필요가 없다.
 *
 * 전체 재기동(`useRetryBatch`)과 **다른 요청**이다 — 그쪽은 파이프라인을 통째로 순회해 보간까지
 * 다시 돌리므로 완주 영상에 쓰면 사람이 손댄 보간 라벨이 전량 지워진다. 이쪽은 문제가 생긴 그
 * 묶음만 지목한다.
 *
 * ⚠ **범위 인자를 두지 않는다 — 묶음이 곧 범위다**(구 `scope` 폐지). 되살리면 보간을 뺀 부분 수행이
 * 다시 가능해진다.
 *
 * 재수행은 영상 상태를 처리 중으로 선점하므로 **목록의 처리 단계 배지도 바뀐다** → `VIDEO_KEYS.all`
 * 을 무효화한다(재실행과 같은 이유·같은 범위).
 */
export function useRerunBatchStage(
  rawSn: number,
  options: MutationOptions<BatchStageRerunResult> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (bundle: StageBundle) => rerunBatchStage(rawSn, bundle),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 배치 일괄 재시작 **접수** (POST /videos/batch/retry).
 *
 * ★ `onSuccess` 가 불렸다고 전부 접수된 것이 아니다 — 한 건도 접수되지 못해도 200 이다.
 * 호출부는 반드시 `results` 로 판정해 건별 성패와 사유를 사용자에게 보여야 한다.
 * ★ 건별 `success` 는 **접수 여부**이지 파이프라인 완료가 아니다(위 파일 머리말 참조).
 *
 * 접수된 건이 하나라도 있으면 목록·상세 캐시를 갱신한다. 한 건도 접수되지 못했으면 서버 상태가
 * 그대로이므로 재조회하지 않는다(의미 없는 왕복 + 화면 깜빡임 방지).
 */
export function useBulkRetryBatch(options: MutationOptions<BatchBulkRetryResult> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (rawSns: number[]) => retryBatchBulk(rawSns),
    onSuccess: (data) => {
      if (data.successCount > 0) {
        qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      }
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 시계열 묶음 **일괄 건너뛰기** (POST /videos/batch/stages/VLM/skip). [@design API-212]
 *
 * ★ `onSuccess` 가 불렸다고 전부 처리된 것이 아니다 — 한 건도 처리되지 못해도 200 이다.
 * 호출부는 반드시 `results` 로 판정해 건별 성패와 사유를 사용자에게 보여야 한다.
 *
 * ★ **사유는 요청당 하나**다(대상 전건에 같은 값으로 기록된다). 영상마다 다른 사유를 받게 만들면
 * 일괄로 처리할 이유가 사라진다.
 *
 * 캐시: 일괄 축은 **목록 화면에서** 여러 영상을 골라 실행하므로 `VIDEO_KEYS.all` 을 무효화한다
 * (그 prefix 가 목록·상세를 함께 덮는다 — 상세만 갱신하면 목록이 옛 상태로 남는다).
 * 한 건도 처리되지 못했으면 서버 상태가 그대로이므로 재조회하지 않는다(의미 없는 왕복 + 깜빡임 방지).
 */
export function useBulkSkipBatchStage(options: MutationOptions<BatchBulkRetryResult> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { rawSns: number[]; reason: string }) =>
      skipBatchStageBulk(vars.rawSns, vars.reason),
    onSuccess: (data) => {
      if (data.successCount > 0) {
        qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      }
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 건너뛴 적이 있는 시계열 묶음 **일괄 재수행 접수** (POST /videos/batch/stages/VLM/rerun).
 * [@design API-214] [@design ADR-050]
 *
 * ★ 건별 `success` 는 **접수 여부**이지 파이프라인 완료가 아니다(실행은 비동기). 호출부는 완료를
 * 알리는 문구를 쓰면 안 되고, 진행은 목록·상세의 처리 단계 표시로 확인시킨다.
 *
 * ★ 수락 대상은 **그 영상에서 건너뛴 적이 있는 묶음**(건너뜀·해제 모두)이다 — 그 외는 건별 실패로
 * 돌아온다. 해제를 먼저 부를 필요는 없다(재수행이 건너뛴 상태를 직접 수락하고 표식도 함께 푼다).
 *
 * 캐시 정책은 {@link useBulkSkipBatchStage} 와 같다 — 재수행은 영상 상태를 처리 중으로 선점하므로
 * 목록의 처리 단계 배지도 함께 바뀐다.
 */
export function useBulkRerunBatchStage(options: MutationOptions<BatchBulkRetryResult> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (rawSns: number[]) => rerunBatchStageBulk(rawSns),
    onSuccess: (data) => {
      if (data.successCount > 0) {
        qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      }
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
