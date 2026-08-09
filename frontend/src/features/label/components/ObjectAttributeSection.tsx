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

import { Field, FieldLabel } from '@/components/common/Field';
import { Checkbox } from '@/components/common/Checkbox';
import { Radio } from '@/components/common/Radio';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { isEditBlockedNow, useLabelStore } from '@/stores/useLabelStore';

import type { LabelAttrDef } from '../api/labelAttr';
import { useBlockNotice } from '../hooks/useBlockNotice';
import { busyRejectedMessage } from '../hooks/useBusyTask';
import { useLabelAttrs } from '../hooks/useLabelAttrs';
import { useLabelAttrValues } from '../hooks/useLabelAttrValues';

import { parseValues } from './LabelAttrFormModal';

const INPUT_CLASS =
  'rounded border border-gray-300 bg-white px-2 py-1 text-sub text-gray-900 focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60';

/**
 * 밀집한 우측 속성 패널용 `Select` 크기·여백 override — <b>색은 넣지 않는다</b>(공통 라이트 기본이 정답).
 *
 * 크기는 DS-001 ladder `caption`(14px/w400). 공통 `Select` 기본값은 `text-body`(17px)라
 * 밀집 패널(w-72)에서는 <b>명시 override 가 필요</b>하다.
 *
 * ⚠ [구 주석 → 폐기] "커스텀 토큰(text-sub)은 twMerge 가 색으로 오인해 지우므로 표준 스케일
 *   (text-xs)로 적는다" 는 <b>더 이상 사실이 아니다</b> — `src/lib/cn.ts` 가 커스텀 토큰을
 *   `font-size` 그룹에 등록해(2026-08-06) 크기와 색이 공존한다. 따라서 원시 스케일을 쓸 이유가
 *   없어졌고, ladder step 으로 적는 것이 정답이다(계약: __tests__/CommonControlFontSize.test.tsx).
 */
const DENSE_SELECT_CLASS = 'rounded px-2 text-caption';

export interface ObjectAttributeSectionProps {
  /** 라벨 마스터 id (LABEL_ID) — 정의 조회. */
  classId: number;
  /** 영속 라벨 id (LS_DATA_LBL, lblSn) — 값 조회/저장. 미저장 객체면 undefined. */
  serverId?: number;
  /**
   * 장시간 작업(저장/AI 실행) 진행 중 여부(렌더 값). 속성값 커밋은 즉시 서버 쓰기(PUT)라,
   * 차단 구간에 나가면 진행 중 작업의 결과 병합과 겹쳐 같은 라벨이 두 축에서 갈린다.
   */
  editBlocked?: boolean;
}

/**
 * 선택 객체 라벨의 속성 정의를 입력 UI로 렌더하고 값을 로드/저장.
 * target 이 존재할 때만 마운트되므로 여기의 훅은 조건부 마운트로 안전하게 실행된다.
 */
export function ObjectAttributeSection({
  classId,
  serverId,
  editBlocked = false,
}: ObjectAttributeSectionProps) {
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

  // 차단으로 저장하지 못한 속성 — 사용자가 적은 값은 그대로 두고 "미저장"만 표시한다(P-2).
  const [unsavedAttrIds, setUnsavedAttrIds] = useState<number[]>([]);
  // 차단 안내는 화면 공통 dedupe 정책 하나를 쓴다(P-1) — 여러 필드가 연달아 blur 돼도 같은 사유는 1회.
  const pushBlockNotice = useBlockNotice();

  function setDraftValue(attrId: number, value: string) {
    dirtyRef.current.add(attrId);
    setDraft((prev) => ({ ...prev, [attrId]: value }));
  }

  function commit(attrId: number, value: string) {
    if (!persisted || valuesLoadFailed) return;
    if (value === effective[attrId]) return; // 변경 없음 → skip
    // 이중 방어 — 입력 disabled 만으로는 "선택한 뒤 busy 가 시작된" 찰나가 남는다. 렌더 값이
    // 낡았을 수 있으므로 실시간 store 값도 함께 본다(fail-closed).
    if (editBlocked || isEditBlockedNow()) {
      // ⚠ **draft 를 서버값으로 되돌리지 않는다**(P-2). 진행 오버레이의 취소 버튼처럼 포커스를
      //   뺏지 않는 busy 트리거가 생기면서 이 경로가 실제로 열렸다 — 되돌리면 사용자가 입력 중이던
      //   텍스트가 통째로 사라진다(R9/AC10: 차단은 작업 결과를 잃지 않는다).
      //   대신 "저장되지 않았다"를 화면에 남겨 서버값으로 오인하지 않게 한다.
      setUnsavedAttrIds((prev) => (prev.includes(attrId) ? prev : [...prev, attrId]));
      pushBlockNotice(busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null));
      return;
    }
    setUnsavedAttrIds((prev) => prev.filter((id) => id !== attrId));
    save([{ attrId, value }]);
  }

  if (defsQuery.isLoading) {
    return (
      <Section>
        <p className="text-caption text-gray-500">속성 정의를 불러오는 중…</p>
      </Section>
    );
  }

  // 정의 조회 실패 — "정의 없음"(정상 빈 상태)과 구분해 에러로 명시(fail-open 방지).
  if (defsQuery.isError) {
    return (
      <Section>
        <p role="alert" className="text-caption text-danger">
          속성 정의를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      </Section>
    );
  }

  if (defs.length === 0) {
    return (
      <Section>
        <p className="text-caption text-gray-500">정의된 속성이 없습니다.</p>
      </Section>
    );
  }

  return (
    <Section>
      {!persisted && (
        <p role="status" className="text-caption text-amber-700">
          저장 후 속성 입력이 가능합니다.
        </p>
      )}
      {valuesLoadFailed && (
        <p role="alert" className="text-caption text-danger">
          저장된 속성값을 불러오지 못했습니다. 값 확인 전에는 수정할 수 없습니다.
        </p>
      )}
      {defs.map((def) => (
        <AttrInput
          key={def.attrId}
          def={def}
          value={draft[def.attrId] ?? ''}
          disabled={editBlocked || !persisted || valuesLoadFailed || def.mutable === 'N'}
          // 표식은 "아직 서버값과 다른 동안"만 유효하다 — 저장이 반영되면(서버가 따라잡으면)
          // 별도 정리 없이 자동으로 사라진다(스테일 표식 방지).
          unsaved={
            unsavedAttrIds.includes(def.attrId) &&
            (draft[def.attrId] ?? '') !== effective[def.attrId]
          }
          onDraft={(v) => setDraftValue(def.attrId, v)}
          onCommit={(v) => commit(def.attrId, v)}
        />
      ))}
      {saveError != null && (
        <p role="alert" className="text-caption text-danger">
          {resolveApiMessage(saveError, '속성값 저장에 실패했습니다.')}
        </p>
      )}
    </Section>
  );
}

function Section({ children }: { children: React.ReactNode }) {
  return (
    <section aria-label="객체 속성값" className="flex flex-col gap-2 border-t border-gray-200 pt-2">
      <h4 className="text-label font-semibold text-gray-700">속성</h4>
      {children}
    </section>
  );
}

interface AttrInputProps {
  def: LabelAttrDef;
  value: string;
  disabled: boolean;
  /** 차단으로 저장되지 못한 입력값(P-2) — 값은 보존하되 저장 안 됐음을 알린다. */
  unsaved?: boolean;
  onDraft: (value: string) => void;
  onCommit: (value: string) => void;
}

/** 미저장 표식 — 색상만으로 정보를 전달하지 않도록 텍스트로 명시한다(a11y). */
function UnsavedMark({ attrId }: { attrId: number }) {
  return (
    <span
      role="status"
      data-testid={`attr-unsaved-${attrId}`}
      className="text-caption text-amber-700"
    >
      저장되지 않음 — 진행 중 작업이 끝난 뒤 다시 저장하세요.
    </span>
  );
}

/** 정의(inputType)에 맞는 입력 컨트롤. label 연결 + 색상 단독 정보전달 금지(텍스트 병기). */
function AttrInput({ def, value, disabled, unsaved = false, onDraft, onCommit }: AttrInputProps) {
  const choices = parseValues(def.valuesJson);
  const legendId = `attr-legend-${def.attrId}`;
  const fieldId = `attr-input-${def.attrId}`;

  if (def.inputType === 'SELECT') {
    return (
      // 공통 Select 는 래퍼 <div> 를 렌더하므로 <label> 로 감싸지 않고 htmlFor 로 연결한다.
      <div className="flex flex-col gap-0.5">
        <label htmlFor={fieldId} className="text-label text-gray-500">
          {def.name}
        </label>
        {unsaved && <UnsavedMark attrId={def.attrId} />}
        <Select
          value={value}
          disabled={disabled}
          onValueChange={(v) => {
            onDraft(v);
            onCommit(v);
          }}
        >
          <SelectTrigger id={fieldId} aria-label={def.name} className={DENSE_SELECT_CLASS}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {/* 옵션 순서·값은 정의(valuesJson) 순서 그대로. '선택 안 함'은 <b>선택 가능한</b> 빈
                값이라 공통 Select 의 placeholder(disabled·hidden)가 아니라 실제 옵션으로 넣는다. */}
            <SelectItem value="">선택 안 함</SelectItem>
            {choices.map((c) => (
              <SelectItem key={c} value={c}>
                {c}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    );
  }

  if (def.inputType === 'RADIO') {
    return (
      <fieldset disabled={disabled} className="flex flex-col gap-1" aria-labelledby={legendId}>
        <legend id={legendId} className="text-label text-gray-500">
          {def.name}
        </legend>
        {unsaved && <UnsavedMark attrId={def.attrId} />}
        {choices.map((c) => (
          <Radio
            key={c}
            name={`attr-${def.attrId}`}
            value={c}
            label={c}
            checked={value === c}
            onChange={() => {
              onDraft(c);
              onCommit(c);
            }}
          />
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
        <legend id={legendId} className="text-label text-gray-500">
          {def.name}
        </legend>
        {unsaved && <UnsavedMark attrId={def.attrId} />}
        {choices.map((c) => (
          <Field key={c} orientation="horizontal">
            <Checkbox
              value={c}
              checked={selected.includes(c)}
              onCheckedChange={(v) => toggle(c, v === true)}
            />
            <FieldLabel>{c}</FieldLabel>
          </Field>
        ))}
      </fieldset>
    );
  }

  // NUMBER / TEXT — onBlur 로 확정 저장(변경분만). 1차 길이 방어(maxLength).
  return (
    <label className="flex flex-col gap-0.5">
      <span className="text-caption text-gray-500">{def.name}</span>
      {unsaved && <UnsavedMark attrId={def.attrId} />}
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
