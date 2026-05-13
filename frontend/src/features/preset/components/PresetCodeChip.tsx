import { Trash2 } from 'lucide-react';
import { useId } from 'react';

import type { LabelCodeOption } from '../types';

export interface PresetCodeChipProps {
  option: LabelCodeOption;
  onToggleBbox: () => void;
  onTogglePolygon: () => void;
  onRemove: () => void;
}

const BASE_CLASS =
  'inline-flex items-center gap-2 rounded-full border bg-white px-3 py-1.5 text-xs font-medium';
const VALID_CLASS = 'border-primary-300 text-primary-700';
const INVALID_CLASS = 'border-red-500 text-red-700';

/**
 * 프리셋 라벨 코드 chip — 코드 + BBOX/POLYGON 체크박스 + 삭제 버튼.
 *
 * 둘 다 off 인 경우 빨간 테두리로 시각 경고.
 */
export function PresetCodeChip({
  option,
  onToggleBbox,
  onTogglePolygon,
  onRemove,
}: PresetCodeChipProps) {
  const bboxId = useId();
  const polyId = useId();
  const valid = option.bboxEnabled || option.polygonEnabled;

  return (
    <div
      data-testid={`preset-chip-${option.code}`}
      className={[BASE_CLASS, valid ? VALID_CLASS : INVALID_CLASS].join(' ')}
    >
      <span className="font-semibold">{option.code}</span>
      <div className="flex items-center gap-2 text-[11px] text-gray-600">
        <label
          htmlFor={bboxId}
          className="inline-flex items-center gap-1 cursor-pointer select-none"
        >
          <input
            id={bboxId}
            type="checkbox"
            checked={option.bboxEnabled}
            onChange={onToggleBbox}
            aria-label={`${option.code} BBOX`}
            className="h-3 w-3 rounded border-gray-300 text-primary-500 focus:ring-primary-400"
          />
          BBOX
        </label>
        <label
          htmlFor={polyId}
          className="inline-flex items-center gap-1 cursor-pointer select-none"
        >
          <input
            id={polyId}
            type="checkbox"
            checked={option.polygonEnabled}
            onChange={onTogglePolygon}
            aria-label={`${option.code} POLYGON`}
            className="h-3 w-3 rounded border-gray-300 text-primary-500 focus:ring-primary-400"
          />
          POLYGON
        </label>
      </div>
      <button
        type="button"
        onClick={onRemove}
        className="ml-1 text-gray-400 transition-colors hover:text-red-500"
        aria-label={`${option.code} 삭제`}
      >
        <Trash2 className="h-3 w-3" aria-hidden />
      </button>
    </div>
  );
}
