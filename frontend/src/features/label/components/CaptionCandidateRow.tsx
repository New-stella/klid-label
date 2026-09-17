// 캡션 후보(c1..cn) 한 건 — 캡션 문장 + 사고 단계 3칸. [@design UI-107]
//
// 값·onChange·onRemove 를 props 로 받는 순수 표현 컴포넌트. 상태를 보유하지 않으며
// 불변 갱신은 상위(EventAnnotationPanel)에서 수행한다.
//
// ★후보 이름은 「캡션 1」이고 옆에 저장 키 c1 을 흐리게 병기한다 — 번호는 <b>저장 키의 숫자</b>이며
//   목록 순번으로 다시 매기지 않는다(c1 을 지운 뒤 남은 c2 는 그대로 「캡션 2」다).
// ★캡션 문장은 여러 줄 입력이다 — 실제 외부 분석 응답이 수백 자 서술이라 한 줄 입력으로는
//   읽으면서 고칠 수 없다.

import { Textarea } from '@/components/common/Textarea';

import { candidateName } from '../annotationSummary';

import { AnnotationField } from './AnnotationField';
import { FIELD_HELP, FIELD_PLACEHOLDER } from './annotationWording';
import {
  COT_LABELS,
  MAX_COT_STEP,
  MAX_TEXT,
  type CaptionRow,
} from './eventAnnotationShared';

export interface CaptionCandidateRowProps {
  row: CaptionRow;
  helpVisible?: boolean;
  onRemove: (key: string) => void;
  onCaptionTextChange: (key: string, value: string) => void;
  onCotChange: (key: string, idx: number, value: string) => void;
}

export function CaptionCandidateRow({
  row,
  helpVisible = true,
  onRemove,
  onCaptionTextChange,
  onCotChange,
}: CaptionCandidateRowProps) {
  const name = candidateName('캡션', row.key);
  return (
    <div className="mb-2.5 rounded-md border border-gray-200 p-2.5">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-caption font-semibold text-gray-600">
          {name}
          <span className="ml-1.5 font-normal text-gray-400">{row.key}</span>
        </span>
        <button
          type="button"
          data-testid={`ea-del-caption-${row.key}`}
          onClick={() => onRemove(row.key)}
          className="text-caption text-danger hover:text-red-700"
          aria-label={`${name} 삭제`}
        >
          삭제
        </button>
      </div>

      <AnnotationField
        label="캡션 문장"
        help={FIELD_HELP.captionText}
        helpVisible={helpVisible}
        after={<p className="mt-1 text-caption text-gray-500">{FIELD_HELP.captionMarkdownNote}</p>}
      >
        <Textarea
          data-testid={`ea-caption-text-${row.key}`}
          value={row.captionText}
          onChange={(e) => onCaptionTextChange(row.key, e.target.value)}
          maxLength={MAX_TEXT}
          aria-label={`${name} 문장`}
          placeholder={FIELD_PLACEHOLDER.captionText}
          className="min-h-[140px] resize-y text-body-md"
        />
      </AnnotationField>

      <AnnotationField label="사고 단계" help={FIELD_HELP.cot} helpVisible={helpVisible}>
        <div className="space-y-1.5">
          {row.cot.map((step, i) => (
            <div key={i} className="flex items-start gap-2">
              <span className="w-12 shrink-0 pt-2 text-caption text-gray-600">
                {COT_LABELS[i]}
              </span>
              <Textarea
                data-testid={`ea-caption-cot-${row.key}-${i}`}
                value={step}
                onChange={(e) => onCotChange(row.key, i, e.target.value)}
                maxLength={MAX_COT_STEP}
                aria-label={`${name} 사고 ${COT_LABELS[i]}`}
                placeholder={FIELD_PLACEHOLDER.cot[i]}
                className="min-h-11 resize-y text-body-md"
              />
            </div>
          ))}
        </div>
      </AnnotationField>
    </div>
  );
}
