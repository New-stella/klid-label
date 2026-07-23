// 라벨링 페이지 우측 '메타' 탭에 삽입되는 접이식 시계열 메타 패널.
//
// useMeta / useUpdateMeta 훅 재사용(Phase 1에서 구현).
// GUI 통일(R3): 프레임 설명 패널과 동일한 MetaSection 래퍼 + 공통 textarea/저장 버튼 스타일 사용.
// 보안: React 자동 escape로 XSS 방어. dangerouslySetInnerHTML 미사용. maxLength 로 입력 크기 제한.

import { useEffect, useState } from 'react';

import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

import { useMetaReview } from '../hooks/useMetaReview';

import {
  MetaCharCount,
  MetaSection,
  META_SAVE_BUTTON_CLASS,
  META_TEXTAREA_CLASS,
} from './MetaSection';

export interface TimeseriesSidePanelProps {
  srcSn: number | undefined;
}

const TEXTAREA_ID = 'timeseries-meta-input';
// BE LS_DATA_META.META_VL 길이(2000)와 정합 — 초과 시 BE 400. FE 에서 미리 입력 제한.
const MAX_LEN = 2000;

/**
 * 수동 시계열 메타의 표준 metaKey (BE↔FE 공유 상수).
 *
 * 기존 메타가 0건인 영상에서 신규 등록 시 사용하는 결정적 키. BE 어댑터(toFrameMeta)가
 * 단일 item 의 metaVal 을 그대로 vlmText 로 표시하므로 저장→재조회 round-trip 이 성립한다.
 * VLM 세그먼트 키(start_sec-end_sec)와 충돌하지 않는다.
 */
export const MANUAL_TIMESERIES_META_KEY = 'manual-timeseries';

/** 검토 상태 → 중립 한글 라벨(기술 모델명 노출 금지, EventAnnotationPanel 과 일관). */
const REVIEW_STATUS_LABEL: Record<string, string> = {
  AUTO_GENERATED: '검토 대기',
  PENDING: '검토 대기',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
};

/** 검토(승인/반려) 가능한 상태 — 그 외(APPROVED/REJECTED)엔 버튼을 노출하지 않는다. */
const REVIEWABLE_STATUSES = new Set(['AUTO_GENERATED', 'PENDING']);

/**
 * 라벨링 RightPanel 메타 탭 접이식 시계열 메타 편집 패널.
 *
 * - MetaSection 토글로 펼침/접기
 * - useMeta(srcSn) 로 조회한 vlmText 표시
 * - 수정 후 저장 버튼으로 useUpdateMeta(srcSn) 호출
 * - dirty 체크: 원본 값과 다를 때만 저장 활성화
 */
export function TimeseriesSidePanel({ srcSn }: TimeseriesSidePanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  const role = useAuthStore((s) => s.claims?.role);
  const channel = useAuthStore((s) => s.claims?.channel);
  const isReviewer = role === Role.REVIEWER && channel === 'INTERNAL';

  const { data } = useMeta(srcSn);
  const updateMutation = useUpdateMeta(srcSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '시계열 메타가 저장되었습니다.' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다.' }),
  });
  const review = useMetaReview(srcSn, {
    onApproveSuccess: () =>
      pushToast({ variant: 'success', message: '검토를 승인했습니다.' }),
    onApproveError: () =>
      pushToast({ variant: 'error', message: '승인에 실패했습니다.' }),
    onRejectSuccess: () => {
      setRejectReasons({});
      pushToast({ variant: 'success', message: '검토를 반려했습니다.' });
    },
    onRejectError: () =>
      pushToast({ variant: 'error', message: '반려에 실패했습니다.' }),
  });

  const [vlmText, setVlmText] = useState('');
  // 검토행별 반려 사유(metaReviewSn → reason). 불변 갱신(spread).
  const [rejectReasons, setRejectReasons] = useState<Record<number, string>>({});

  // data 변경 시 로컬 상태 동기화
  useEffect(() => {
    setVlmText(data?.vlmText ?? '');
  }, [data?.vlmText]);

  const dirty = vlmText !== (data?.vlmText ?? '');
  // 공백만인 텍스트 저장은 무의미 — 저장 비활성(기존 동작 존중).
  const canSave = dirty && vlmText.trim().length > 0;

  // 검토행이 있는 시계열 메타 항목만 검수 표면 대상 (검토행 없으면 배지/버튼 없음).
  const reviewItems = (data?.items ?? []).filter((it) => it.dataMetaReviewSn != null);
  const reviewBusy = review.approve.isPending || review.reject.isPending;

  const setReason = (sn: number, value: string) =>
    setRejectReasons((prev) => ({ ...prev, [sn]: value }));

  const handleReject = (sn: number) => {
    const reason = (rejectReasons[sn] ?? '').trim();
    if (reason === '' || reviewBusy) {
      return;
    }
    review.reject.mutate({ metaReviewSn: sn, reason });
  };

  const handleSave = () => {
    if (!canSave) {
      return;
    }
    const sourceItems = data?.items ?? [];
    // 기존 메타 0건 → 표준 metaKey 로 신규 등록. 있으면 원본 metaKey 보존(수정).
    const items =
      sourceItems.length === 0
        ? [{ metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: vlmText }]
        : sourceItems.length === 1
          ? [{ metaKey: sourceItems[0].metaKey, metaVal: vlmText }]
          : sourceItems.map((it) => ({ metaKey: it.metaKey, metaVal: it.metaVal }));
    updateMutation.mutate({ items });
  };

  return (
    <MetaSection title="시계열 메타">
      <label htmlFor={TEXTAREA_ID} className="sr-only">
        VLM 시계열 메타 입력
      </label>
      <textarea
        id={TEXTAREA_ID}
        value={vlmText}
        onChange={(e) => setVlmText(e.target.value)}
        disabled={updateMutation.isPending}
        maxLength={MAX_LEN}
        rows={8}
        aria-label="VLM 시계열 메타 입력"
        placeholder="외부 VLM 이 자동 생성한 시계열 정보입니다. 검토 후 수정할 수 있습니다."
        className={META_TEXTAREA_CLASS}
      />
      <MetaCharCount current={vlmText.length} max={MAX_LEN} />

      <button
        type="button"
        onClick={handleSave}
        disabled={!canSave || updateMutation.isPending}
        className={META_SAVE_BUTTON_CLASS}
      >
        {updateMutation.isPending ? '저장 중...' : '저장'}
      </button>

      {/* 검토행이 있는 시계열 메타의 검수 표면 — 상태 배지는 모든 역할, 승인/반려는 REVIEWER(내부)만. */}
      {reviewItems.length > 0 && (
        <div
          data-testid="ts-review-actions"
          className="mt-3 space-y-2 border-t border-gray-700 pt-2"
        >
          <span className="block text-[11px] font-semibold text-gray-400 uppercase">
            검토
          </span>
          {reviewItems.map((it) => {
            const sn = it.dataMetaReviewSn as number;
            const status = it.reviewStatus ?? '';
            const canReview = isReviewer && REVIEWABLE_STATUSES.has(status);
            const reason = rejectReasons[sn] ?? '';
            return (
              <div key={sn} className="space-y-1">
                <div className="flex items-center gap-1 text-[11px]">
                  <span className="text-gray-400">검토 상태</span>
                  <span
                    data-testid={`ts-review-status-${sn}`}
                    className="rounded bg-gray-700 px-1.5 py-0.5 text-gray-200"
                  >
                    {REVIEW_STATUS_LABEL[status] ?? status}
                  </span>
                </div>
                {canReview && (
                  <>
                    <label htmlFor={`ts-reject-reason-${sn}`} className="sr-only">
                      반려 사유
                    </label>
                    <textarea
                      id={`ts-reject-reason-${sn}`}
                      data-testid={`ts-reject-reason-${sn}`}
                      value={reason}
                      onChange={(e) => setReason(sn, e.target.value)}
                      maxLength={1000}
                      rows={2}
                      aria-label="반려 사유"
                      placeholder="반려 사유(반려 시 필수)"
                      className={META_TEXTAREA_CLASS}
                    />
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        data-testid={`ts-approve-${sn}`}
                        onClick={() => review.approve.mutate(sn)}
                        disabled={reviewBusy}
                        className="flex-1 rounded bg-primary-600 px-2 py-1.5 text-sm text-white hover:bg-primary-500 disabled:opacity-50"
                      >
                        {review.approve.isPending ? '승인 중...' : '승인'}
                      </button>
                      <button
                        type="button"
                        data-testid={`ts-reject-${sn}`}
                        onClick={() => handleReject(sn)}
                        disabled={reason.trim() === '' || reviewBusy}
                        className="flex-1 rounded border border-red-500 px-2 py-1.5 text-sm text-red-300 hover:bg-red-900/30 disabled:opacity-50"
                      >
                        {review.reject.isPending ? '반려 중...' : '반려'}
                      </button>
                    </div>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}
    </MetaSection>
  );
}
