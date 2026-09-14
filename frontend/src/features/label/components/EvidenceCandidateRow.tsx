// 근거 후보(c1..cn) 한 건 — 근거 문장 + 프레임 번호 + 객체 세 필드. [@design UI-107]
//
// 값·onChange·onRemove·담기 동작을 props 로 받는 순수 표현 컴포넌트. 상태 미보유.
//
// ★「화면에서 지정」은 이 행이 <b>요청만</b> 한다 — 창을 숨기고 띠를 띄우는 것은 창의 일이라
//   여기서는 어느 후보를 지정하는지만 올려보낸다.
// ★입력 파싱 규칙(콤마 구분 · 박스 좌표는 한 줄에 하나)은 바뀌지 않았다. 도움말이 그 규칙을
//   말로 풀어 주며, 규칙 자체는 eventAnnotationForm 의 파서가 소유한다.

import { Textarea } from '@/components/common/Textarea';

import { candidateName } from '../annotationSummary';

import { AnnotationField } from './AnnotationField';
import {
  EVIDENCE_FIELD_LABEL,
  FIELD_HELP,
  FIELD_PLACEHOLDER,
  PICK_BUTTON_HELP,
  PICK_BUTTON_LABEL,
} from './annotationWording';
import { INPUT_CLASS, MAX_ID, MAX_TEXT, type EvidenceRow } from './eventAnnotationShared';

export interface EvidenceCandidateRowProps {
  row: EvidenceRow;
  helpVisible?: boolean;
  /** 현재 프레임 SRC_SN — 정의됐을 때만 '현재 프레임' 담기 버튼을 노출한다. */
  currentSrcSn?: number;
  /** 캔버스에 선택된 라벨이 있는지 — false 면 '선택 객체 추가' 버튼 비활성. */
  hasSelectedObject?: boolean;
  /** 방금 「화면에서 지정」으로 담은 값 안내(저장 전임을 알린다). */
  pickedNotice?: string | null;
  onRemove: (key: string) => void;
  onFieldChange: (
    key: string,
    field: 'evidenceText' | 'frameId' | 'objId' | 'objBbox' | 'objLabel',
    value: string,
  ) => void;
  /** 현재 프레임 번호를 프레임 칸에 append. `currentSrcSn` 과 함께 있을 때만 버튼이 선다. */
  onAppendCurrentFrame?: (key: string) => void;
  /**
   * 캔버스 선택 라벨의 객체 값을 이 행에 append.
   *
   * ★<b>선택</b>이다 — 캔버스 선택과 이어지지 않는 화면에서는 넘기지 않으며, 그때는 버튼을
   * 비활성으로 두지 않고 <b>렌더 자체를 하지 않는다</b>. 눌리는 모양인데 영원히 반응이 없으면
   * 사용자가 고장으로 읽는다.
   */
  onAppendSelectedObject?: (key: string) => void;
  /** 「화면에서 지정」 요청 — 넘기지 않으면 버튼을 그리지 않는다(창 밖에서 쓰는 경우). */
  onRequestPick?: (key: string) => void;
}

export function EvidenceCandidateRow({
  row,
  helpVisible = true,
  currentSrcSn,
  hasSelectedObject = false,
  pickedNotice,
  onRemove,
  onFieldChange,
  onAppendCurrentFrame,
  onAppendSelectedObject,
  onRequestPick,
}: EvidenceCandidateRowProps) {
  const name = candidateName('근거', row.key);
  return (
    <div className="mb-2.5 rounded-md border border-gray-200 p-2.5">
      <div className="mb-2 flex items-start justify-between gap-2">
        <span className="pt-1 text-caption font-semibold text-gray-600">
          {name}
          <span className="ml-1.5 font-normal text-gray-400">{row.key}</span>
        </span>
        <span className="flex items-start gap-2">
          {onRequestPick !== undefined && (
            <span className="flex flex-col items-end">
              <button
                type="button"
                data-testid={`ea-evidence-pick-${row.key}`}
                onClick={() => onRequestPick(row.key)}
                className="text-caption font-semibold text-primary-600 hover:text-primary-700"
                aria-label={`${name} ${PICK_BUTTON_LABEL}`}
              >
                {PICK_BUTTON_LABEL}
              </button>
              {helpVisible && (
                <small className="text-caption text-gray-500">{PICK_BUTTON_HELP}</small>
              )}
            </span>
          )}
          <button
            type="button"
            data-testid={`ea-del-evidence-${row.key}`}
            onClick={() => onRemove(row.key)}
            className="pt-0.5 text-caption text-danger hover:text-red-700"
            aria-label={`${name} 삭제`}
          >
            삭제
          </button>
        </span>
      </div>

      {/* 시안 `.ev { display:grid; grid-template-columns:1fr 1fr }` — 근거 문장만 전폭이고
          나머지 네 칸은 2열로 접는다. 세로로 쌓으면 근거 하나가 화면 한 장을 넘겨 후보가
          여럿일 때 목록을 훑을 수 없다. 창 폭이 좁아 칸이 쌓이면 이 그리드도 1열로 내린다. */}
      <div className="grid grid-cols-1 gap-x-2 md:grid-cols-2">
      <AnnotationField
        label={EVIDENCE_FIELD_LABEL.evidenceText}
        help={FIELD_HELP.evidenceText}
        helpVisible={helpVisible}
        className="md:col-span-2"
      >
        <Textarea
          data-testid={`ea-evidence-text-${row.key}`}
          value={row.evidenceText}
          onChange={(e) => onFieldChange(row.key, 'evidenceText', e.target.value)}
          maxLength={MAX_TEXT}
          aria-label={`${name} 문장`}
          placeholder={FIELD_PLACEHOLDER.evidenceText}
          className="min-h-11 resize-y text-body-md"
        />
      </AnnotationField>

      <AnnotationField
        label={EVIDENCE_FIELD_LABEL.frameId}
        help={FIELD_HELP.frameIdEditable}
        helpVisible={helpVisible}
      >
        <div className="flex items-center gap-2">
          <input
            type="text"
            data-testid={`ea-evidence-frameid-${row.key}`}
            value={row.frameId}
            onChange={(e) => onFieldChange(row.key, 'frameId', e.target.value)}
            aria-label={`${name} ${EVIDENCE_FIELD_LABEL.frameId}`}
            className={INPUT_CLASS}
          />
          {currentSrcSn !== undefined && onAppendCurrentFrame !== undefined && (
            <button
              type="button"
              data-testid={`ea-evidence-frameid-current-${row.key}`}
              onClick={() => onAppendCurrentFrame(row.key)}
              className="shrink-0 whitespace-nowrap text-caption text-primary-600 hover:text-primary-700"
            >
              현재 프레임
            </button>
          )}
        </div>
      </AnnotationField>

      <AnnotationField label={EVIDENCE_FIELD_LABEL.objId} help={FIELD_HELP.objId} helpVisible={helpVisible}>
        <div className="flex items-center gap-2">
          <input
            type="text"
            data-testid={`ea-evidence-objid-${row.key}`}
            value={row.objId}
            onChange={(e) => onFieldChange(row.key, 'objId', e.target.value)}
            aria-label={`${name} ${EVIDENCE_FIELD_LABEL.objId}`}
            className={INPUT_CLASS}
          />
          {/* 캔버스 선택 객체를 객체 세 필드 + 프레임 번호에 자동 append. 선택이 없으면 비활성.
              캔버스 선택과 이어지지 않는 화면에서는 핸들러가 없어 버튼 자체를 두지 않는다. */}
          {onAppendSelectedObject !== undefined && (
            <button
              type="button"
              data-testid={`ea-evidence-add-selected-${row.key}`}
              onClick={() => onAppendSelectedObject(row.key)}
              disabled={!hasSelectedObject}
              aria-label={`선택 객체를 ${name}에 추가`}
              className="shrink-0 whitespace-nowrap text-caption text-primary-600 hover:text-primary-700 disabled:cursor-not-allowed disabled:text-gray-400"
            >
              선택 객체 추가
            </button>
          )}
        </div>
        <p className="mt-0.5 text-caption text-gray-500">원소당 최대 {MAX_ID}자</p>
      </AnnotationField>

      <AnnotationField
        label={EVIDENCE_FIELD_LABEL.objLabel}
        help={FIELD_HELP.objLabel}
        helpVisible={helpVisible}
      >
        <input
          type="text"
          data-testid={`ea-evidence-objlabel-${row.key}`}
          value={row.objLabel}
          onChange={(e) => onFieldChange(row.key, 'objLabel', e.target.value)}
          aria-label={`${name} ${EVIDENCE_FIELD_LABEL.objLabel}`}
          className={INPUT_CLASS}
        />
      </AnnotationField>

      <AnnotationField
        label={EVIDENCE_FIELD_LABEL.objBbox}
        help={FIELD_HELP.objBboxEditable}
        helpVisible={helpVisible}
      >
        <Textarea
          data-testid={`ea-evidence-objbbox-${row.key}`}
          value={row.objBbox}
          onChange={(e) => onFieldChange(row.key, 'objBbox', e.target.value)}
          aria-label={`${name} ${EVIDENCE_FIELD_LABEL.objBbox}`}
          className="min-h-11 resize-y text-body-md"
        />
      </AnnotationField>
      </div>

      {pickedNotice !== null && pickedNotice !== undefined && (
        <p
          data-testid={`ea-evidence-picked-notice-${row.key}`}
          className="mt-1 text-caption text-gray-600"
          role="status"
        >
          {pickedNotice}
        </p>
      )}
    </div>
  );
}
