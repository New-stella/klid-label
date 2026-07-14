// blocker#2 Phase 3 — 프레임 설명(NIA image.description) 입력 패널.
//
// 라벨링 RightPanel 에 삽입되는 접이식 설명 편집 패널.
// - useFrameDescription(srcSn) 로 기존 설명 로드 → textarea 바인딩
// - 수정 후 저장 버튼으로 useUpdateFrameDescription(srcSn) 호출
// - dirty 체크: 원본과 다를 때만 저장 활성
//
// 보안(저장형 XSS 방어): 설명은 textarea value 로만 바인딩 — React 기본 escape.
//   dangerouslySetInnerHTML 미사용. maxLength 1000(BE @Size 정합).
// a11y: <label htmlFor> ↔ textarea id 연결.

import { useEffect, useState } from 'react';

import {
  useFrameDescription,
  useUpdateFrameDescription,
} from '../hooks/useFrameDescription';

export interface FrameDescriptionPanelProps {
  srcSn: number | undefined;
}

const TEXTAREA_ID = 'frame-description-input';
const MAX_LEN = 1000;

export function FrameDescriptionPanel({ srcSn }: FrameDescriptionPanelProps) {
  const [open, setOpen] = useState(true);
  const { data, isLoading } = useFrameDescription(srcSn);
  const update = useUpdateFrameDescription(srcSn);

  const [text, setText] = useState('');

  // 프레임 전환(data 변경) 시 로컬 입력 상태 동기화.
  useEffect(() => {
    setText(data?.description ?? '');
  }, [data?.description, srcSn]);

  const original = data?.description ?? '';
  const dirty = text !== original;
  const canSave = srcSn !== undefined && dirty && !update.isPending;

  const handleSave = () => {
    if (!canSave) return;
    // 빈 문자열은 설명 삭제(null) 로 전송 — BE 가 blank→null 정규화.
    const payload = text.trim() === '' ? null : text;
    update.mutate(payload);
  };

  return (
    <div className="border-t border-gray-700">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="w-full flex items-center justify-between px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide hover:bg-gray-700/50 transition-colors"
        aria-expanded={open}
      >
        <span>프레임 설명</span>
        <span className="text-gray-500" aria-hidden="true">
          {open ? '▾' : '▸'}
        </span>
      </button>

      {open && (
        <div className="px-2 pb-2 space-y-2">
          <label htmlFor={TEXTAREA_ID} className="sr-only">
            프레임 설명 입력
          </label>
          <textarea
            id={TEXTAREA_ID}
            value={text}
            onChange={(e) => setText(e.target.value)}
            disabled={srcSn === undefined || isLoading || update.isPending}
            maxLength={MAX_LEN}
            rows={4}
            aria-label="프레임 설명 입력"
            placeholder="이 프레임의 상황을 자연어로 설명하세요"
            className="w-full resize-y rounded border border-gray-600 bg-gray-800 text-gray-100 text-sm p-2 placeholder-gray-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:opacity-60"
          />

          {update.isError && (
            <p className="text-xs text-red-400" role="alert">
              설명 저장에 실패했습니다. 다시 시도해 주세요.
            </p>
          )}

          <button
            type="button"
            onClick={handleSave}
            disabled={!canSave}
            className="w-full rounded bg-primary-600 text-white text-sm py-1.5 disabled:bg-gray-500 disabled:cursor-not-allowed hover:bg-primary-500 transition-colors"
          >
            {update.isPending ? '저장 중...' : '저장'}
          </button>
        </div>
      )}
    </div>
  );
}
