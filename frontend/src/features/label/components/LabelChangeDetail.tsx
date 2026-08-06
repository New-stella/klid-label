// SCR-LABEL — 저장 이벤트 펼침 시 라벨 단위 변경 상세(diff) 렌더.
//
// LabelHistoryPanel 카드를 펼치면 노출되는 changes 목록. 라벨별로 변경종류 뱃지 +
// 라벨명 + before→after 필드 요약을 보여준다.
//   - ADDED   : 새 값만 표시
//   - DELETED : 이전 값만 표시
//   - UPDATED : 바뀐 필드만 `이전값 → 새값`
// 좌표(pointCn)는 길면 점 개수 등으로 요약한다.
//
// 보안: 라벨명/좌표 요약은 React 기본 escape(XSS 방지) — dangerouslySetInnerHTML 미사용.

import { Plus, Pencil, Trash2 } from 'lucide-react';

import { cn } from '@/lib/cn';

import type { LabelChangeKind, LabelChangeView, LabelSnapshotView } from '../api';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';

/** 변경종류 → 한글 라벨 + 아이콘 + 색상. LabelHistoryPanel 과 동일 팔레트. */
export const KIND_META: Record<
  LabelChangeKind,
  { label: string; Icon: typeof Plus; cls: string }
> = {
  ADDED: { label: '추가', Icon: Plus, cls: 'bg-emerald-50 text-emerald-700' },
  UPDATED: { label: '수정', Icon: Pencil, cls: 'bg-blue-50 text-blue-700' },
  DELETED: { label: '삭제', Icon: Trash2, cls: 'bg-red-50 text-red-700' },
};

/** 좌표 원문(JSON 문자열) → 요약. 점 배열이면 "점 N개", 아니면 길이 제한 표시. */
function summarizePoints(pointCn: string | null): string | null {
  if (!pointCn) return null;
  try {
    const parsed: unknown = JSON.parse(pointCn);
    if (Array.isArray(parsed)) return `점 ${parsed.length}개`;
  } catch {
    // JSON 이 아니면 원문을 길이 제한하여 노출.
  }
  return pointCn.length > 20 ? `${pointCn.slice(0, 20)}…` : pointCn;
}

interface FieldRow {
  label: string;
  before: string | null;
  after: string | null;
}

/** 스냅샷 필드 추출기 — 타입/라벨/좌표 3종. */
const FIELDS: Array<{ label: string; get: (s: LabelSnapshotView | null) => string | null }> = [
  { label: '타입', get: (s) => s?.lblTypeCd ?? null },
  { label: '라벨', get: (s) => s?.labelNm ?? null },
  { label: '좌표', get: (s) => summarizePoints(s?.pointCn ?? null) },
];

/** 변경종류에 맞춰 표시할 필드 행을 구성한다. */
function buildFieldRows(change: LabelChangeView): FieldRow[] {
  const rows: FieldRow[] = [];
  for (const f of FIELDS) {
    const before = f.get(change.before);
    const after = f.get(change.after);
    if (change.changeKind === 'ADDED') {
      if (after !== null) rows.push({ label: f.label, before: null, after });
    } else if (change.changeKind === 'DELETED') {
      if (before !== null) rows.push({ label: f.label, before, after: null });
    } else if (before !== after) {
      // UPDATED — 바뀐 필드만.
      rows.push({ label: f.label, before, after });
    }
  }
  return rows;
}

interface LabelChangeDetailProps {
  changes: LabelChangeView[];
}

const MUTED_TEXT = 'text-gray-500';
const STRONG_TEXT = 'text-gray-800';

export function LabelChangeDetail({ changes }: LabelChangeDetailProps) {
  if (changes.length === 0) {
    return <p className={cn('px-2 py-1 text-caption', MUTED_TEXT)}>변경 상세가 없습니다.</p>;
  }

  return (
    <ul aria-label="변경 상세 목록" className="flex flex-col gap-2 px-2 py-2 text-gray-700">
      {changes.map((change, idx) => {
        const meta = KIND_META[change.changeKind];
        const KindIcon = meta.Icon;
        const rows = buildFieldRows(change);
        return (
          <li
            key={change.lblSn ?? `${change.changeKind}-${idx}`}
            className="flex items-start gap-2 text-caption"
          >
            <span
              className={cn(
                'inline-flex shrink-0 items-center gap-1 rounded px-1.5 py-0.5 font-medium',
                meta.cls,
              )}
            >
              <KindIcon size={11} aria-hidden="true" />
              {meta.label}
            </span>
            <div className="min-w-0 flex-1">
              <p className={cn('truncate font-medium', STRONG_TEXT)}>
                {/* 표시명은 공용 함수 — 이력에 기록된 마스터 등록명(labelName) 그대로. */}
                {change.labelName != null
                  ? resolveLabelDisplayName(change.labelName)
                  : '(삭제된 라벨)'}
              </p>
              {rows.length > 0 && (
                <ul className="mt-0.5 flex flex-col gap-0.5">
                  {rows.map((row) => (
                    <li key={row.label} className={cn('truncate', MUTED_TEXT)}>
                      <span className="mr-1">{row.label}:</span>
                      {change.changeKind === 'UPDATED' ? (
                        <span>{`${row.before ?? '없음'} → ${row.after ?? '없음'}`}</span>
                      ) : (
                        <span>{change.changeKind === 'DELETED' ? row.before : row.after}</span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
