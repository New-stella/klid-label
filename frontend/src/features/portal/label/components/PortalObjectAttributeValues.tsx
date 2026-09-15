// 포털 라벨링 — 고른 객체의 속성 값(라벨 관리에서 정의한 속성).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 속성값 칸(`ObjectAttributeSection`)과 같은 훅 · 같은 규칙이다 —
//   · 정의는 라벨 마스터 id 로, 값은 저장된 라벨 id(serverId)로 불러온다
//   · 드롭다운 · 라디오 · 체크박스는 고르는 즉시, 입력칸은 칸을 벗어날 때 **바뀐 것만** 저장한다
//   · 저장 전 객체는 입력을 잠그고, 저장값을 못 불러온 객체도 잠근다(모르는 값을 덮지 않는다)
//   · 편집 차단 중에 확정한 값은 되돌리지 않고 「저장되지 않았다」만 남긴다
//   · 편집 중인 칸은 다른 칸 저장 뒤의 재조회로 덮이지 않는다
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 「속성 값」 판)과 같다 — 층 4 도구 판 안에 면 없는
// 필터 판(세로 쌓기)으로 칸이 선다. 값을 못 불러온 사정은 칸 위 안내 띠다.

import { useEffect, useMemo, useRef, useState } from 'react';
import { Checkbox, TextInput } from 'krds-react';

import { Alert, Dropdown, EmptyState, FilterPanel, RadioRow } from '@portal/components/custom';
import { ToolPanel } from '@portal/pages/workspace/authoring/ToolPanel';
import type { LabelAttrDef } from '@/features/label/api/labelAttr';
import { parseValues } from '@/features/label/components/LabelAttrFormModal';
import { useBlockNotice } from '@/features/label/hooks/useBlockNotice';
import { busyRejectedMessage } from '@/features/label/hooks/useBusyTask';
import { useLabelAttrs } from '@/features/label/hooks/useLabelAttrs';
import { useLabelAttrValues } from '@/features/label/hooks/useLabelAttrValues';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { isEditBlockedNow, useLabelStore } from '@/stores/useLabelStore';

/** 차단으로 저장하지 못한 칸에 붙는 한 줄 — 색만이 아니라 글로 알린다. */
const UNSAVED_NOTE = '저장되지 않았습니다. 진행 중 작업이 끝난 뒤 다시 저장해 주세요.';

export function PortalObjectAttributeValues({
  classId,
  serverId,
  editBlocked,
}: {
  /** 라벨 마스터 id — 정의 조회. */
  classId: number;
  /** 저장된 라벨 id — 값 조회·저장. 저장 전 객체면 undefined. */
  serverId?: number;
  editBlocked: boolean;
}) {
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
  const valuesLoadFailed = persisted && valuesError;

  const effective = useMemo(() => {
    const m: Record<number, string> = {};
    for (const d of defs) m[d.attrId] = valueMap[d.attrId] ?? d.defaultVal ?? '';
    return m;
  }, [defs, valueMap]);

  // 편집 초안 — 서버 값이 바뀌면 다시 채우되, 편집 중인 칸은 덮지 않는다(원본 규칙).
  const [draft, setDraft] = useState<Record<number, string>>({});
  const dirtyRef = useRef<Set<number>>(new Set());
  useEffect(() => {
    setDraft((prev) => {
      const next: Record<number, string> = {};
      for (const key of Object.keys(effective)) {
        const id = Number(key);
        const serverVal = effective[id];
        if (dirtyRef.current.has(id) && id in prev && prev[id] !== serverVal) {
          next[id] = prev[id];
        } else {
          dirtyRef.current.delete(id);
          next[id] = serverVal;
        }
      }
      return next;
    });
  }, [effective]);

  const [unsavedAttrIds, setUnsavedAttrIds] = useState<number[]>([]);
  const pushBlockNotice = useBlockNotice();

  function setDraftValue(attrId: number, value: string) {
    dirtyRef.current.add(attrId);
    setDraft((prev) => ({ ...prev, [attrId]: value }));
  }

  function commit(attrId: number, value: string) {
    if (!persisted || valuesLoadFailed) return;
    if (value === effective[attrId]) return;
    if (editBlocked || isEditBlockedNow()) {
      // 적은 값은 되돌리지 않는다 — 「저장되지 않았다」만 남긴다(원본 규칙).
      setUnsavedAttrIds((prev) => (prev.includes(attrId) ? prev : [...prev, attrId]));
      pushBlockNotice(busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null));
      return;
    }
    setUnsavedAttrIds((prev) => prev.filter((id) => id !== attrId));
    save([{ attrId, value }]);
  }

  const body = (() => {
    if (defsQuery.isLoading) {
      return <EmptyState size="xs" busy title="속성 정의를 불러오고 있습니다." />;
    }
    // 정의 조회 실패 — 「정의 없음」(정상 빈 상태)과 가른다.
    if (defsQuery.isError) {
      return (
        <Alert tone="danger">속성 정의를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</Alert>
      );
    }
    if (defs.length === 0) {
      return <EmptyState size="xs" title="정의된 속성이 없습니다." />;
    }
    return (
      <>
        {!persisted && (
          <Alert tone="info" live="status">
            저장 후 속성 입력이 가능합니다.
          </Alert>
        )}
        {valuesLoadFailed && (
          <Alert tone="warning" live="none">
            저장된 속성값을 불러오지 못했습니다. 값 확인 전에는 수정할 수 없습니다.
          </Alert>
        )}
        <FilterPanel surface="bare" layout="stack" aria-label="속성 값">
          {defs.map((def) => (
            <AttrField
              key={def.attrId}
              def={def}
              value={draft[def.attrId] ?? ''}
              disabled={editBlocked || !persisted || valuesLoadFailed || def.mutable === 'N'}
              unsaved={
                unsavedAttrIds.includes(def.attrId) &&
                (draft[def.attrId] ?? '') !== effective[def.attrId]
              }
              onDraft={(v) => setDraftValue(def.attrId, v)}
              onCommit={(v) => commit(def.attrId, v)}
            />
          ))}
        </FilterPanel>
        {saveError != null && (
          <Alert tone="danger">{resolveApiMessage(saveError, '속성값 저장에 실패했습니다.')}</Alert>
        )}
      </>
    );
  })();

  return (
    <ToolPanel level={4} title="속성 값" surface={false}>
      {body}
    </ToolPanel>
  );
}

function AttrField({
  def,
  value,
  disabled,
  unsaved,
  onDraft,
  onCommit,
}: {
  def: LabelAttrDef;
  value: string;
  disabled: boolean;
  unsaved: boolean;
  onDraft: (value: string) => void;
  onCommit: (value: string) => void;
}) {
  const choices = parseValues(def.valuesJson);
  const desc = unsaved ? UNSAVED_NOTE : undefined;

  if (def.inputType === 'SELECT') {
    return (
      <FilterPanel.Field label={def.name} desc={desc}>
        <Dropdown
          size="small"
          aria-label={def.name}
          disabled={disabled}
          value={value}
          // 「선택 안 함」은 고를 수 있는 빈 값이라 실제 선택지로 넣는다(원본 규칙).
          options={[{ value: '', label: '선택 안 함' }, ...choices.map((c) => ({ value: c, label: c }))]}
          onChange={(v) => {
            onDraft(v);
            onCommit(v);
          }}
        />
      </FilterPanel.Field>
    );
  }

  if (def.inputType === 'RADIO') {
    return (
      <FilterPanel.Field label={def.name} desc={desc}>
        <RadioRow
          label={def.name}
          name={`attr-${def.attrId}`}
          value={value}
          onChange={(v) => {
            onDraft(v);
            onCommit(v);
          }}
          options={choices.map((c) => ({ value: c, label: c, disabled }))}
        />
      </FilterPanel.Field>
    );
  }

  if (def.inputType === 'CHECKBOX') {
    // 다중 선택 값은 JSON 배열 문자열로 주고받는다(정의와 같은 꼴).
    const selected = parseValues(value);
    const toggle = (choice: string, checked: boolean) => {
      const next = checked ? [...selected, choice] : selected.filter((x) => x !== choice);
      const serialized = JSON.stringify(next);
      onDraft(serialized);
      onCommit(serialized);
    };
    return (
      <FilterPanel.Field label={def.name} desc={desc}>
        <div className="klid-labeling-checks">
          {choices.map((c) => (
            <Checkbox
              key={c}
              label={c}
              value={c}
              disabled={disabled}
              checked={selected.includes(c)}
              onChange={(e) => toggle(c, e.currentTarget.checked)}
            />
          ))}
        </div>
      </FilterPanel.Field>
    );
  }

  // 숫자 · 글 — 칸을 벗어날 때 바뀐 것만 저장한다. 1차 길이 방어(255).
  return (
    <FilterPanel.Field label={def.name} desc={desc}>
      <TextInput
        size="small"
        type={def.inputType === 'NUMBER' ? 'number' : 'text'}
        aria-label={def.name}
        maxLength={255}
        disabled={disabled}
        value={value}
        onChange={onDraft}
        onBlur={(e) => onCommit(e.currentTarget.value)}
      />
    </FilterPanel.Field>
  );
}
