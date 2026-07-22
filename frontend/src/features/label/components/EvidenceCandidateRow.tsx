// Phase 4 — 근거 후보(c1..cn) 단일 행 렌더. EventAnnotationPanel 에서 추출(component.md 400줄 규칙).
//
// 값·onChange·onRemove·현재프레임 추가를 props 로 받는 순수 표현 컴포넌트. 상태 미보유.

import { META_TEXTAREA_CLASS } from './MetaSection';
import {
  INPUT_CLASS,
  MAX_TEXT,
  type EvidenceRow,
} from './eventAnnotationShared';

export interface EvidenceCandidateRowProps {
  row: EvidenceRow;
  /** 현재 프레임 SRC_SN — 정의됐을 때만 '현재 프레임' 추가 버튼 노출. */
  currentSrcSn?: number;
  onRemove: (key: string) => void;
  onFieldChange: (
    key: string,
    field: 'evidenceText' | 'frameId' | 'objId' | 'objBbox' | 'objLabel',
    value: string,
  ) => void;
  onAppendCurrentFrame: (key: string) => void;
}

/** 근거 후보 1건(evidence_text + frame_id/obj_id/obj_bbox/obj_label) 입력 행. */
export function EvidenceCandidateRow({
  row,
  currentSrcSn,
  onRemove,
  onFieldChange,
  onAppendCurrentFrame,
}: EvidenceCandidateRowProps) {
  return (
    <div className="mt-2 rounded border border-gray-700 p-2 space-y-1">
      <div className="flex items-center justify-between">
        <span className="text-[10px] text-gray-500">{row.key}</span>
        <button
          type="button"
          data-testid={`ea-del-evidence-${row.key}`}
          onClick={() => onRemove(row.key)}
          className="text-[11px] text-red-400 hover:text-red-300"
          aria-label={`근거 후보 ${row.key} 삭제`}
        >
          삭제
        </button>
      </div>
      <input
        type="text"
        data-testid={`ea-evidence-text-${row.key}`}
        value={row.evidenceText}
        onChange={(e) => onFieldChange(row.key, 'evidenceText', e.target.value)}
        maxLength={MAX_TEXT}
        aria-label={`근거 텍스트 ${row.key}`}
        placeholder="evidence_text"
        className={INPUT_CLASS}
      />
      <div className="flex items-center gap-1">
        <input
          type="text"
          data-testid={`ea-evidence-frameid-${row.key}`}
          value={row.frameId}
          onChange={(e) => onFieldChange(row.key, 'frameId', e.target.value)}
          aria-label={`프레임 ID ${row.key}`}
          placeholder="frame_id (콤마 구분, 정수)"
          className={INPUT_CLASS}
        />
        {currentSrcSn !== undefined && (
          <button
            type="button"
            data-testid={`ea-evidence-frameid-current-${row.key}`}
            onClick={() => onAppendCurrentFrame(row.key)}
            className="shrink-0 text-[11px] text-primary-400 hover:text-primary-300 whitespace-nowrap"
          >
            현재 프레임
          </button>
        )}
      </div>
      <input
        type="text"
        data-testid={`ea-evidence-objid-${row.key}`}
        value={row.objId}
        onChange={(e) => onFieldChange(row.key, 'objId', e.target.value)}
        aria-label={`객체 ID ${row.key}`}
        placeholder="obj_id (콤마 구분)"
        className={INPUT_CLASS}
      />
      <input
        type="text"
        data-testid={`ea-evidence-objlabel-${row.key}`}
        value={row.objLabel}
        onChange={(e) => onFieldChange(row.key, 'objLabel', e.target.value)}
        aria-label={`객체 라벨 ${row.key}`}
        placeholder="obj_label (콤마 구분)"
        className={INPUT_CLASS}
      />
      <textarea
        data-testid={`ea-evidence-objbbox-${row.key}`}
        value={row.objBbox}
        onChange={(e) => onFieldChange(row.key, 'objBbox', e.target.value)}
        rows={2}
        aria-label={`객체 bbox ${row.key}`}
        placeholder="obj_bbox (한 줄에 하나: x1,y1,x2,y2)"
        className={META_TEXTAREA_CLASS}
      />
    </div>
  );
}
