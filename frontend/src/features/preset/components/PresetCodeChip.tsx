import { TYPE_LABEL } from '@/features/label/constants/labelTypes';

import type { PresetCode } from '../types';

export interface PresetCodeChipProps {
  code: PresetCode;
}

const BASE_CLASS =
  'inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-label font-semibold';
/** 연결된 코드 — primary 톤(대비 primary-700 on primary-50 = 9.42:1 AAA). */
const LINKED_CLASS = 'border-primary-100 bg-primary-50 text-primary-700';
/**
 * 미연결 코드 — 중립 톤(gray-600 on gray-50 = 5.60:1 AA).
 *
 * 경고색(amber)이 아니라 중립인 이유: '미연결'은 오류가 아니라 마스터에 더 이상 없는 레거시
 * 코드를 사실대로 표시한 것이고, 재선택 유도는 편집 모달의 경고 배너가 담당한다.
 */
const UNLINKED_CLASS = 'border-gray-200 bg-gray-50 text-gray-600';

/**
 * 프리셋 라벨 코드 chip (UI-092) — 마스터 라벨명 + 형태(읽기 전용) 표시.
 *
 * - 연결(linked)된 코드: 마스터 라벨명 + 형태 배지.
 * - 미연결(linked=false) 코드: legacy 라벨명 + '미연결' 배지(마스터에서 재선택 유도).
 *
 * 이름과 형태는 흰 표면의 작은 태그로 형태를 겹쳐 **한 칩에서 "이름+형태" 2단으로 읽히게**
 * 구성한다. 글자 크기는 DS-001 ladder step(`label`/`caption`)만 쓴다 — ladder 밖 px 금지.
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
      <span className="truncate">{code.labelName}</span>
      {code.linked && shape && (
        <span className="shrink-0 rounded-sm bg-white px-1 text-caption font-semibold text-primary-600">
          {shape}
        </span>
      )}
      {!code.linked && (
        <span className="shrink-0 rounded-sm bg-gray-100 px-1 text-caption font-semibold text-gray-700">
          미연결
        </span>
      )}
    </span>
  );
}
