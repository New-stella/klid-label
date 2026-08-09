// Phase 4 — 근거 후보(c1..cn) 단일 행 렌더. EventAnnotationPanel 에서 추출(component.md 400줄 규칙).
//
// 값·onChange·onRemove·현재프레임 추가를 props 로 받는 순수 표현 컴포넌트. 상태 미보유.

import { Textarea } from '@/components/common/Textarea';

import { INPUT_CLASS, MAX_ID, MAX_TEXT, type EvidenceRow } from './eventAnnotationShared';

/** obj_id·obj_label 원소당 최대 길이 힌트(콤마 다중값이라 필드 전체 maxLength 는 걸지 않음). */
const ID_HINT_CLASS = 'text-[10px] text-gray-500';

export interface EvidenceCandidateRowProps {
  row: EvidenceRow;
  /** 현재 프레임 SRC_SN — 정의됐을 때만 '현재 프레임' 추가 버튼 노출. */
  currentSrcSn?: number;
  /** 캔버스에 선택된 라벨이 있는지 — false 면 '선택 객체 추가' 버튼 비활성. */
  hasSelectedObject: boolean;
  onRemove: (key: string) => void;
  onFieldChange: (
    key: string,
    field: 'evidenceText' | 'frameId' | 'objId' | 'objBbox' | 'objLabel',
    value: string,
  ) => void;
  onAppendCurrentFrame: (key: string) => void;
  /** 캔버스 선택 라벨의 obj_* 값을 이 행에 append. */
  onAppendSelectedObject: (key: string) => void;
}

/** 근거 후보 1건(evidence_text + frame_id/obj_id/obj_bbox/obj_label) 입력 행. */
export function EvidenceCandidateRow({
  row,
  currentSrcSn,
  hasSelectedObject,
  onRemove,
  onFieldChange,
  onAppendCurrentFrame,
  onAppendSelectedObject,
}: EvidenceCandidateRowProps) {
  return (
    <div className="mt-2 rounded border border-gray-200 p-2 space-y-1">
      <div className="flex items-center justify-between">
        <span className="text-[10px] text-gray-500">{row.key}</span>
        <button
          type="button"
          data-testid={`ea-del-evidence-${row.key}`}
          onClick={() => onRemove(row.key)}
          className="text-[11px] text-danger hover:text-red-700"
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
            className="shrink-0 text-[11px] text-primary-600 hover:text-primary-700 whitespace-nowrap"
          >
            현재 프레임
          </button>
        )}
      </div>
      {/* 캔버스 선택 객체를 obj_id/obj_label/obj_bbox/frame_id 에 자동 append. 선택 없으면 비활성. */}
      <button
        type="button"
        data-testid={`ea-evidence-add-selected-${row.key}`}
        onClick={() => onAppendSelectedObject(row.key)}
        disabled={!hasSelectedObject}
        aria-label={`선택 객체를 근거 후보 ${row.key} 에 추가`}
        className="text-[11px] text-primary-600 hover:text-primary-700 disabled:cursor-not-allowed disabled:text-gray-400"
      >
        + 선택 객체 추가
      </button>
      <input
        type="text"
        data-testid={`ea-evidence-objid-${row.key}`}
        value={row.objId}
        onChange={(e) => onFieldChange(row.key, 'objId', e.target.value)}
        aria-label={`객체 ID ${row.key}`}
        placeholder="obj_id (콤마 구분)"
        className={INPUT_CLASS}
      />
      <p className={ID_HINT_CLASS}>원소당 최대 {MAX_ID}자</p>
      <input
        type="text"
        data-testid={`ea-evidence-objlabel-${row.key}`}
        value={row.objLabel}
        onChange={(e) => onFieldChange(row.key, 'objLabel', e.target.value)}
        aria-label={`객체 라벨 ${row.key}`}
        placeholder="obj_label (콤마 구분)"
        className={INPUT_CLASS}
      />
      <p className={ID_HINT_CLASS}>원소당 최대 {MAX_ID}자</p>
      <Textarea
        data-testid={`ea-evidence-objbbox-${row.key}`}
        value={row.objBbox}
        onChange={(e) => onFieldChange(row.key, 'objBbox', e.target.value)}
        aria-label={`객체 bbox ${row.key}`}
        placeholder="obj_bbox (한 줄에 하나: x1,y1,x2,y2)"
        className="min-h-[72px] resize-y text-body-md"
      />
    </div>
  );
}
