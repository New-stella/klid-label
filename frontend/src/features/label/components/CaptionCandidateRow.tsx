// Phase 4 — 캡션 후보(c1..cn) 단일 행 렌더. EventAnnotationPanel 에서 추출(component.md 400줄 규칙).
//
// 값·onChange·onRemove 를 props 로 받는 순수 표현 컴포넌트. 상태를 보유하지 않으며
// 불변 갱신은 상위(EventAnnotationPanel)에서 수행한다.

import {
  COT_LABELS,
  INPUT_CLASS,
  MAX_COT_STEP,
  MAX_TEXT,
  type CaptionRow,
} from './eventAnnotationShared';

export interface CaptionCandidateRowProps {
  row: CaptionRow;
  onRemove: (key: string) => void;
  onCaptionTextChange: (key: string, value: string) => void;
  onCotChange: (key: string, idx: number, value: string) => void;
}

/** 캡션 후보 1건(caption_text + CoT 3단계) 입력 행. */
export function CaptionCandidateRow({
  row,
  onRemove,
  onCaptionTextChange,
  onCotChange,
}: CaptionCandidateRowProps) {
  return (
    <div className="mt-2 rounded border border-gray-200 p-2 space-y-1">
      <div className="flex items-center justify-between">
        <span className="text-[10px] text-gray-500">{row.key}</span>
        <button
          type="button"
          data-testid={`ea-del-caption-${row.key}`}
          onClick={() => onRemove(row.key)}
          className="text-[11px] text-danger hover:text-red-700"
          aria-label={`캡션 후보 ${row.key} 삭제`}
        >
          삭제
        </button>
      </div>
      <input
        type="text"
        data-testid={`ea-caption-text-${row.key}`}
        value={row.captionText}
        onChange={(e) => onCaptionTextChange(row.key, e.target.value)}
        maxLength={MAX_TEXT}
        aria-label={`캡션 텍스트 ${row.key}`}
        placeholder="caption_text"
        className={INPUT_CLASS}
      />
      {row.cot.map((step, i) => (
        <input
          key={i}
          type="text"
          data-testid={`ea-caption-cot-${row.key}-${i}`}
          value={step}
          onChange={(e) => onCotChange(row.key, i, e.target.value)}
          maxLength={MAX_COT_STEP}
          aria-label={`추론 ${COT_LABELS[i]} ${row.key}`}
          placeholder={`CoT ${COT_LABELS[i]}`}
          className={INPUT_CLASS}
        />
      ))}
    </div>
  );
}
