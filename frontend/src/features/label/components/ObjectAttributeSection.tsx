// SCR-LABEL-002 — 오브젝트 속성값 입력 섹션.
//
// 라벨 관리(/manage/labels)에서 정의한 속성(LabelAttrDef)을 라벨링 캔버스 선택 객체의
// 입력 UI로 노출하고, 영속 객체(serverId=lblSn)의 값을 로드/저장한다.
//
// BE 계약(불변):
//   GET /v1/manage/labels/{labelId}/attrs  → 정의 (useLabelAttrs 재사용)
//   GET /v1/labels/{lblSn}/attrs           → 현재 저장값 (useLabelAttrValues)
//   PUT /v1/labels/{lblSn}/attrs           ← { values: [{attrId, value}] } (변경분만)
//
// 보안:
//  - 값 upsert 본문은 {attrId,value} 만 (Mass Assignment 방어).
//  - 선택지/값은 React 기본 escape 로 렌더(XSS 방어), CHECKBOX 직렬화 파싱은 try/catch 안전 폴백.
//  - 저장 실패는 resolveApiMessage 로 사용자 문구만 노출(스택/내부경로 차단).

import { useEffect, useMemo, useRef, useState } from 'react';

import { resolveApiMessage } from '@/lib/api/resolveApiMessage';

import type { LabelAttrDef } from '../api/labelAttr';
import { useLabelAttrs } from '../hooks/useLabelAttrs';
import { useLabelAttrValues } from '../hooks/useLabelAttrValues';

import { parseValues } from './LabelAttrFormModal';

const INPUT_CLASS =
  'rounded border border-gray-600 bg-gray-700 px-2 py-1 text-sub text-white focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60';

export interface ObjectAttributeSectionProps {
  /** 라벨 마스터 id (LABEL_ID) — 정의 조회. */
  classId: number;
  /** 영속 라벨 id (LS_DATA_LBL, lblSn) — 값 조회/저장. 미저장 객체면 undefined. */
  serverId?: number;
}

/**
 * 선택 객체 라벨의 속성 정의를 입력 UI로 렌더하고 값을 로드/저장.
 * target 이 존재할 때만 마운트되므로 여기의 훅은 조건부 마운트로 안전하게 실행된다.
 */
export function ObjectAttributeSection({ classId, serverId }: ObjectAttributeSectionProps) {
  const defsQuery = useLabelAttrs(classId);
  const defs = useMemo(
    () =>
      (defsQuery.data ?? [])
        .filter((d) => d.useYn === 'Y')
        .slice()
        .sort((a, b) => a.sortNo - b.sortNo),
    [defsQuery.data],
  );

  const { valueMap, save, saveError, isError: valuesError } = useLabelAttrValues(serverId);
  const persisted = serverId != null && serverId > 0;
  // 저장값 조회가 실패한 영속 객체 — 실제 저장값을 알 수 없으므로 편집/저장을 막는다(fail-open 방지).
  const valuesLoadFailed = persisted && valuesError;

  // 서버 저장값(valueMap) ?? 정의 기본값(defaultVal) 으로 각 속성의 유효 현재값 산출.
  const effective = useMemo(() => {
    const m: Record<number, string> = {};
    for (const d of defs) m[d.attrId] = valueMap[d.attrId] ?? d.defaultVal ?? '';
    return m;
  }, [defs, valueMap]);

  // 편집 draft — 서버 유효값이 바뀌면(로드/저장 후 무효화) 재시드하되, 사용자가 편집 중(dirty)인
  // 필드는 서버값으로 덮지 않는다. 한 속성 저장→invalidate→값 재조회로 effective 참조가 새로 생겨도
  // 다른 필드의 미커밋 입력(예: 타이핑 중인 TEXT)이 유실되는 경합(이슈2)을 방지한다.
  const [draft, setDraft] = useState<Record<number, string>>({});
  const dirtyRef = useRef<Set<number>>(new Set());
  useEffect(() => {
    setDraft((prev) => {
      const next: Record<number, string> = {};
      for (const key of Object.keys(effective)) {
        const id = Number(key);
        const serverVal = effective[id];
        if (dirtyRef.current.has(id) && id in prev && prev[id] !== serverVal) {
          // 편집 중 & 서버 미반영 — 사용자 입력 보존.
          next[id] = prev[id];
        } else {
          // 비편집 또는 서버가 편집값을 따라잡음 — 서버값 채택 + dirty 해제.
          dirtyRef.current.delete(id);
          next[id] = serverVal;
        }
      }
      return next;
    });
  }, [effective]);

  function setDraftValue(attrId: number, value: string) {
    dirtyRef.current.add(attrId);
    setDraft((prev) => ({ ...prev, [attrId]: value }));
  }

  function commit(attrId: number, value: string) {
    if (!persisted || valuesLoadFailed) return;
    if (value === effective[attrId]) return; // 변경 없음 → skip
    save([{ attrId, value }]);
  }

  if (defsQuery.isLoading) {
    return (
      <Section>
        <p className="text-xs text-gray-400">속성 정의를 불러오는 중…</p>
      </Section>
    );
  }

  // 정의 조회 실패 — "정의 없음"(정상 빈 상태)과 구분해 에러로 명시(fail-open 방지).
  if (defsQuery.isError) {
    return (
      <Section>
        <p role="alert" className="text-xs text-danger">
          속성 정의를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      </Section>
    );
  }

  if (defs.length === 0) {
    return (
      <Section>
        <p className="text-xs text-gray-400">정의된 속성이 없습니다.</p>
      </Section>
    );
  }

  return (
    <Section>
      {!persisted && (
        <p role="status" className="text-xs text-amber-300">
          저장 후 속성 입력이 가능합니다.
        </p>
      )}
      {valuesLoadFailed && (
        <p role="alert" className="text-xs text-danger">
          저장된 속성값을 불러오지 못했습니다. 값 확인 전에는 수정할 수 없습니다.
        </p>
      )}
      {defs.map((def) => (
        <AttrInput
          key={def.attrId}
          def={def}
          value={draft[def.attrId] ?? ''}
          disabled={!persisted || valuesLoadFailed || def.mutable === 'N'}
          onDraft={(v) => setDraftValue(def.attrId, v)}
          onCommit={(v) => commit(def.attrId, v)}
        />
      ))}
      {saveError != null && (
        <p role="alert" className="text-xs text-danger">
          {resolveApiMessage(saveError, '속성값 저장에 실패했습니다.')}
        </p>
      )}
    </Section>
  );
}

function Section({ children }: { children: React.ReactNode }) {
  return (
    <section
      aria-label="객체 속성값"
      className="flex flex-col gap-2 border-t border-gray-700 pt-2"
    >
      <h4 className="text-xs font-semibold text-gray-300">속성</h4>
      {children}
    </section>
  );
}

interface AttrInputProps {
  def: LabelAttrDef;
  value: string;
  disabled: boolean;
  onDraft: (value: string) => void;
  onCommit: (value: string) => void;
}

/** 정의(inputType)에 맞는 입력 컨트롤. label 연결 + 색상 단독 정보전달 금지(텍스트 병기). */
function AttrInput({ def, value, disabled, onDraft, onCommit }: AttrInputProps) {
  const choices = parseValues(def.valuesJson);
  const legendId = `attr-legend-${def.attrId}`;

  if (def.inputType === 'SELECT') {
    return (
      <label className="flex flex-col gap-0.5">
        <span className="text-xs text-gray-400">{def.name}</span>
        <select
          aria-label={def.name}
          value={value}
          disabled={disabled}
          onChange={(e) => {
            onDraft(e.target.value);
            onCommit(e.target.value);
          }}
          className={INPUT_CLASS}
        >
          <option value="">선택 안 함</option>
          {choices.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </label>
    );
  }

  if (def.inputType === 'RADIO') {
    return (
      <fieldset disabled={disabled} className="flex flex-col gap-1" aria-labelledby={legendId}>
        <legend id={legendId} className="text-xs text-gray-400">
          {def.name}
        </legend>
        {choices.map((c) => (
          <label key={c} className="flex items-center gap-2 text-sub text-gray-100">
            <input
              type="radio"
              name={`attr-${def.attrId}`}
              value={c}
              checked={value === c}
              onChange={() => {
                onDraft(c);
                onCommit(c);
              }}
            />
            <span>{c}</span>
          </label>
        ))}
      </fieldset>
    );
  }

  if (def.inputType === 'CHECKBOX') {
    // 다중선택 값은 JSON 배열 문자열로 직렬화/역직렬화 — 정의(valuesJson)와 정합.
    const selected = parseValues(value);
    const toggle = (choice: string, checked: boolean) => {
      const next = checked ? [...selected, choice] : selected.filter((x) => x !== choice);
      const serialized = JSON.stringify(next);
      onDraft(serialized);
      onCommit(serialized);
    };
    return (
      <fieldset disabled={disabled} className="flex flex-col gap-1" aria-labelledby={legendId}>
        <legend id={legendId} className="text-xs text-gray-400">
          {def.name}
        </legend>
        {choices.map((c) => (
          <label key={c} className="flex items-center gap-2 text-sub text-gray-100">
            <input
              type="checkbox"
              value={c}
              checked={selected.includes(c)}
              onChange={(e) => toggle(c, e.target.checked)}
            />
            <span>{c}</span>
          </label>
        ))}
      </fieldset>
    );
  }

  // NUMBER / TEXT — onBlur 로 확정 저장(변경분만). 1차 길이 방어(maxLength).
  return (
    <label className="flex flex-col gap-0.5">
      <span className="text-xs text-gray-400">{def.name}</span>
      <input
        type={def.inputType === 'NUMBER' ? 'number' : 'text'}
        aria-label={def.name}
        value={value}
        disabled={disabled}
        maxLength={255}
        onChange={(e) => onDraft(e.target.value)}
        onBlur={(e) => onCommit(e.target.value)}
        className={INPUT_CLASS}
      />
    </label>
  );
}
