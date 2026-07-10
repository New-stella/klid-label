// 라벨링 페이지 우측 사이드패널에 삽입되는 접이식 시계열 메타 패널.
//
// useMeta / useUpdateMeta 훅 재사용(Phase 1에서 구현).
// 보안: React 자동 escape로 XSS 방어. 입력 maxLength는 TimeseriesTextPanel 내부에서 제한.

import { useEffect, useState } from 'react';

import { TimeseriesTextPanel } from '@/features/auto/components/TimeseriesTextPanel';
import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';
import { useUiStore } from '@/stores/useUiStore';

export interface TimeseriesSidePanelProps {
  srcSn: number | undefined;
}

/**
 * 라벨링 RightPanel 하단 접이식 시계열 메타 편집 패널.
 *
 * - 토글 버튼으로 펼침/접기
 * - useMeta(srcSn) 로 조회한 vlmText 표시
 * - 수정 후 저장 버튼으로 useUpdateMeta(srcSn) 호출
 * - dirty 체크: 원본 값과 다를 때만 저장 활성화
 */
export function TimeseriesSidePanel({ srcSn }: TimeseriesSidePanelProps) {
  const [open, setOpen] = useState(true);
  const pushToast = useUiStore((s) => s.pushToast);
  const { data } = useMeta(srcSn);
  const updateMutation = useUpdateMeta(srcSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '시계열 메타가 저장되었습니다.' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다.' }),
  });

  const [vlmText, setVlmText] = useState('');

  // data 변경 시 로컬 상태 동기화
  useEffect(() => {
    setVlmText(data?.vlmText ?? '');
  }, [data?.vlmText]);

  const dirty = vlmText !== (data?.vlmText ?? '');

  const handleSave = () => {
    // BE 는 기존 metaKey 값만 수정 — 원본 items 키 보존 (단일 항목이면 편집 텍스트 반영).
    const sourceItems = data?.items ?? [];
    if (sourceItems.length === 0) {
      return;
    }
    const items =
      sourceItems.length === 1
        ? [{ metaKey: sourceItems[0].metaKey, metaVal: vlmText }]
        : sourceItems.map((it) => ({ metaKey: it.metaKey, metaVal: it.metaVal }));
    updateMutation.mutate({ items });
  };

  return (
    <div className="border-t border-gray-700">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="w-full flex items-center justify-between px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide hover:bg-gray-700/50 transition-colors"
      >
        <span>시계열 메타</span>
        <span className="text-gray-500">{open ? '▾' : '▸'}</span>
      </button>

      {open && (
        <div className="px-2 pb-2 space-y-2">
          <TimeseriesTextPanel
            vlmText={vlmText}
            onChange={setVlmText}
            disabled={updateMutation.isPending}
            className="border-gray-600 bg-gray-800 text-gray-100"
          />
          <button
            type="button"
            onClick={handleSave}
            disabled={!dirty || updateMutation.isPending}
            className="w-full rounded bg-primary-600 text-white text-sm py-1.5 disabled:bg-gray-500 disabled:cursor-not-allowed hover:bg-primary-500 transition-colors"
          >
            {updateMutation.isPending ? '저장 중...' : '저장'}
          </button>
        </div>
      )}
    </div>
  );
}
