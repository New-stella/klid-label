import { useState } from 'react';
import { Trash2, Pencil, Check, X } from 'lucide-react';
import type { ReviewIssue } from '../../types/review';

interface Props {
  issues: ReviewIssue[];
  frameNo: number;
  isIssueMode: boolean;
  onToggleIssueMode: () => void;
  onRemoveIssue: (index: number) => void;
  onEditIssue: (index: number, comment: string) => void;
  onGoToFrame: (frameNo: number) => void;
  overallComment: string;
  onOverallCommentChange: (val: string) => void;
  readOnly?: boolean;
}

const MAX_OVERALL = 200;

export function ReviewNotePanel({
  issues,
  frameNo,
  isIssueMode,
  onToggleIssueMode,
  onRemoveIssue,
  onEditIssue,
  onGoToFrame,
  overallComment,
  onOverallCommentChange,
  readOnly = false,
}: Props) {
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [editValue, setEditValue] = useState('');

  const startEdit = (idx: number, current: string) => {
    setEditingIdx(idx);
    setEditValue(current);
  };

  const commitEdit = () => {
    if (editingIdx !== null) {
      onEditIssue(editingIdx, editValue.trim() || '(코멘트 없음)');
      setEditingIdx(null);
    }
  };

  const cancelEdit = () => setEditingIdx(null);

  return (
    <div className="flex flex-col h-full bg-gray-800 text-gray-200 text-sm">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-gray-700 shrink-0">
        <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">검수 메모</span>
        {!readOnly && (
          <button
            onClick={onToggleIssueMode}
            className={[
              'flex items-center gap-1 px-2 py-1 rounded text-xs font-medium transition-colors',
              isIssueMode
                ? 'bg-red-600 text-white hover:bg-red-700'
                : 'bg-gray-700 text-gray-300 hover:bg-gray-600',
            ].join(' ')}
            title="이슈 추가 모드 토글"
          >
            🚩 {isIssueMode ? '마킹 중...' : '이슈 추가 모드'}
          </button>
        )}
      </div>

      {/* Issue mode hint */}
      {isIssueMode && !readOnly && (
        <div className="mx-3 mt-2 px-2 py-1.5 bg-red-900/40 border border-red-700/50 rounded text-xs text-red-300 shrink-0">
          캔버스를 클릭하여 이슈 위치를 마킹하세요
        </div>
      )}

      {/* Issue list */}
      <div className="flex-1 overflow-y-auto px-3 py-2 space-y-2">
        {issues.length === 0 ? (
          <p className="text-xs text-gray-500 text-center mt-6">등록된 이슈가 없습니다</p>
        ) : (
          issues.map((iss, idx) => (
            <div
              key={iss.id}
              className={[
                'rounded-lg border p-2 space-y-1 cursor-pointer transition-colors',
                iss.frameNo === frameNo
                  ? 'border-red-500/60 bg-red-900/20'
                  : 'border-gray-600 bg-gray-700/40 hover:border-gray-500',
              ].join(' ')}
              onClick={() => onGoToFrame(iss.frameNo)}
            >
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-gray-300">
                  🚩 프레임 {iss.frameNo}
                </span>
                {!readOnly && (
                  <div className="flex items-center gap-1">
                    <button
                      onClick={(e) => { e.stopPropagation(); startEdit(idx, iss.comment); }}
                      className="p-0.5 rounded text-gray-400 hover:text-blue-400 transition-colors"
                      aria-label="이슈 수정"
                    >
                      <Pencil size={12} />
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); onRemoveIssue(idx); }}
                      className="p-0.5 rounded text-gray-400 hover:text-red-400 transition-colors"
                      aria-label="이슈 삭제"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                )}
              </div>

              {editingIdx === idx ? (
                <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                  <input
                    autoFocus
                    value={editValue}
                    onChange={(e) => setEditValue(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') commitEdit(); if (e.key === 'Escape') cancelEdit(); }}
                    className="flex-1 bg-gray-600 border border-gray-500 rounded px-1.5 py-0.5 text-xs text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
                  />
                  <button onClick={commitEdit} className="text-green-400 hover:text-green-300"><Check size={12} /></button>
                  <button onClick={cancelEdit} className="text-gray-400 hover:text-gray-300"><X size={12} /></button>
                </div>
              ) : (
                <p className="text-xs text-gray-300 leading-relaxed">{iss.comment}</p>
              )}
            </div>
          ))
        )}
      </div>

      {/* Divider */}
      <div className="border-t border-gray-700 shrink-0" />

      {/* Attachments placeholder */}
      <div className="px-3 py-2 shrink-0">
        <p className="text-xs text-gray-500 font-medium mb-1">첨부파일</p>
        <div className="h-10 rounded border border-dashed border-gray-600 flex items-center justify-center">
          <span className="text-xs text-gray-600">첨부파일 기능 준비 중</span>
        </div>
      </div>

      {/* Overall comment */}
      <div className="px-3 pb-3 shrink-0">
        <div className="flex items-center justify-between mb-1">
          <p className="text-xs text-gray-400 font-medium">검수 의견</p>
          <span className="text-xs text-gray-500">{overallComment.length}/{MAX_OVERALL}</span>
        </div>
        <textarea
          value={overallComment}
          onChange={(e) => onOverallCommentChange(e.target.value.slice(0, MAX_OVERALL))}
          disabled={readOnly}
          rows={3}
          placeholder="전체 검수 의견을 입력하세요"
          className={[
            'w-full rounded border px-2 py-1.5 text-xs resize-none focus:outline-none focus:ring-1 focus:ring-blue-500',
            readOnly
              ? 'bg-gray-700/50 border-gray-600/50 text-gray-300 cursor-not-allowed opacity-70'
              : 'bg-gray-700 border-gray-600 text-white',
          ].join(' ')}
        />
      </div>
    </div>
  );
}
