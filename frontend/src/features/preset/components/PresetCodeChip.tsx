import { TYPE_LABEL } from '@/features/label/constants/labelTypes';

import type { PresetCode } from '../types';

export interface PresetCodeChipProps {
  code: PresetCode;
}

const BASE_CLASS =
  'inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-label font-medium';
const LINKED_CLASS = 'border-primary-300 bg-primary-50 text-primary-700';
const UNLINKED_CLASS = 'border-amber-300 bg-amber-50 text-amber-700';

/**
 * 프리셋 라벨 코드 chip — 마스터 라벨명 + 형태(읽기 전용) 표시.
 *
 * - 연결(linked)된 코드: 마스터 라벨명 + 형태 배지.
 * - 미연결(linked=false) 코드: legacy 라벨명 + '미연결' 배지(마스터에서 재선택 유도).
 *
 * 라벨명은 텍스트로만 렌더한다(XSS 방어 — dangerouslySetInnerHTML 미사용).
 */
export function PresetCodeChip({ code }: PresetCodeChipProps) {
  const shape = code.labelType ? TYPE_LABEL[code.labelType] : null;

  return (
    <span
      data-testid={`preset-chip-${code.labelId ?? code.code ?? code.labelName}`}
      className={[BASE_CLASS, code.linked ? LINKED_CLASS : UNLINKED_CLASS].join(' ')}
    >
      <span className="font-semibold">{code.labelName}</span>
      {code.linked && shape && (
        <span className="rounded bg-white/70 px-1.5 py-0.5 text-[10px] font-normal text-gray-600">
          {shape}
        </span>
      )}
      {!code.linked && (
        <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800">
          미연결
        </span>
      )}
    </span>
  );
}
