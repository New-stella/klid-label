// 라벨링 페이지 우측 '메타' 탭에 삽입되는 접이식 시계열 메타 패널.
//
// useMeta / useUpdateMeta 훅 재사용(Phase 1에서 구현).
// GUI 통일(R3): 프레임 설명 패널과 동일한 MetaSection 래퍼 + 공통 textarea/저장 버튼 스타일 사용.
// 보안: React 자동 escape로 XSS 방어. dangerouslySetInnerHTML 미사용. maxLength 로 입력 크기 제한.

import { useEffect, useState } from 'react';

import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';
import { useUiStore } from '@/stores/useUiStore';

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
const MAX_LEN = 5000;

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
        disabled={!dirty || updateMutation.isPending}
        className={META_SAVE_BUTTON_CLASS}
      >
        {updateMutation.isPending ? '저장 중...' : '저장'}
      </button>
    </MetaSection>
  );
}
