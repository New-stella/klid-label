// 포털 라벨링 — 「메타」 탭의 메타 목록(촬영환경 · 개인정보 · 프레임 설명 · 시계열 메타 · 참고 정보 · 영상 기술 정보).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 포털 메타 판(`PortalMetaPanel`)과 같은 창구 · 같은 규칙이다 —
//   · 포털 전용 창구만 부른다(원본 · 데이터마트를 고치지 않는다)
//   · 항목 식별은 (축, 메타 키) 쌍이다 — 개인정보 세 항목이 영상 축과 프레임 축에 같은 이름으로 있다
//   · 사용자가 **손댄 항목만** 초안에 담고 그것만 보낸다. 프레임이 바뀌면 초안을 버린다
//   · 배치 순서 · 어느 칸에 담길지는 `splitEditableItems` 가 정한다(응답에 없는 항목은 만들지 않는다)
//   · 시계열 메타는 표준 열쇠 자리가 응답에 없을 때만 「새로 더할 자리」를 잇는다
//   · 「내가 고침」 표시는 덮은 항목에만 붙인다
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 메타 탭)과 같다 — 구역마다 접고 펴는 줄, 적는 구역은
// 펼친 채 · 고칠 수 없는 두 구역은 접힌 채 시작한다. 저장 걸음은 묶음 끝에 안내 글과 함께 선다.
// ★ 선택지 값(날씨 · 시간대 · 계절)은 원본 코드 그대로다 — 저장되는 값이라 포털 화면의 견본 이름표로 바꾸지 않는다.

import { useEffect, useMemo, useState } from 'react';
import { Button, Textarea } from 'krds-react';

import {
  Alert,
  Dropdown,
  EmptyState,
  FilterPanel,
  KeyValueList,
  type DropdownOption,
} from '@portal/components/custom';
import { NO_VALUE_MARK } from '@/features/label/components/MetaSection';
import { UNSELECTED_LABEL } from '@/features/label/utils/shootingEnvironmentOptions';
import { editableMetaLabel, MANUAL_TIMESERIES_META_KEY } from '@/features/auto/metaKeys';
import { usePortalFrameMeta, usePortalSaveFrameMeta } from '@/features/portal/work/hooks/usePortalFrameMeta';
import {
  axisKeyOf,
  axisKeyOfItem,
  META_VALUE_MAX_LENGTH,
  overriddenBadge,
  readOnlyMetaLabel,
  splitEditableItems,
  type PortalMetaControl,
} from '@/features/portal/work/metaFields';
import {
  buildMetaSavePayload,
  hasMetaChanges,
  toFormValue,
} from '@/features/portal/work/metaSavePayload';
import type { PortalMetaItem } from '@/features/portal/work/types';
import { portalWorkErrorMessage } from '@/features/portal/work/workError';
import { useUiStore } from '@/stores/useUiStore';

/** 예 · 아니오 — 「판정하지 않음」 과 「아니오」 는 달라 빈 값을 맨 위에 둔다(원본 규칙). */
const YN_OPTIONS: DropdownOption[] = [
  { value: '', label: UNSELECTED_LABEL },
  { value: 'Y', label: '예' },
  { value: 'N', label: '아니오' },
];

/** 항목 이름 중 포털 화면 문구가 다른 자리(띄어쓰기) — 원본 정의는 관제판도 쓰므로 여기서만 바꿔 보인다. */
const PORTAL_FIELD_LABEL: Record<string, string> = {
  익명여부: '익명 여부',
  가명여부: '가명 여부',
  '개인정보 포함여부': '개인정보 포함 여부',
};

/** 저장해도 원본에 안 간다는 사정 — 누르기 전에 읽혀야 해서 걸음 바로 위에 선다. */
const META_SAVE_NOTE =
  '원본과 같은 값은 저장해도 내 작업물로 남지 않습니다. 저장한 값은 이 화면에서만 쓰이며 원본과 데이터마트에는 반영되지 않습니다.';

function MetaControl({
  label,
  control,
  value,
  disabled,
  onChange,
}: {
  label: string;
  control: PortalMetaControl;
  value: string;
  disabled: boolean;
  onChange: (next: string) => void;
}) {
  if (control.kind === 'text') {
    return (
      <Textarea
        aria-label={label}
        value={value}
        disabled={disabled}
        // 폭은 좁은 쪽이다 — 넓게 받으면 다 쓰고 나서 창구에 거부당한다(원본 규칙).
        maxLength={control.maxLength}
        showCount
        countTotal={control.maxLength}
        onChange={onChange}
      />
    );
  }
  const options: DropdownOption[] =
    control.kind === 'yn'
      ? YN_OPTIONS
      : [
          { value: '', label: UNSELECTED_LABEL },
          ...control.options.map((opt) =>
            typeof opt === 'string' ? { value: opt, label: opt } : { value: opt.code, label: opt.label },
          ),
        ];
  return (
    <Dropdown
      size="small"
      aria-label={label}
      disabled={disabled}
      value={value}
      options={options}
      onChange={onChange}
    />
  );
}

export function PortalMetaSections({ srcSn }: { srcSn: number | undefined }) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { data, isLoading, isError, error } = usePortalFrameMeta(srcSn);

  // 손댄 항목만 담는다((축, 키) → 값). 서버 값을 통째로 복사하면 「손댔는가」를 알 수 없다.
  const [draft, setDraft] = useState<Record<string, string>>({});
  useEffect(() => setDraft({}), [srcSn]);

  const save = usePortalSaveFrameMeta(srcSn, {
    onSuccess: () => {
      setDraft({});
      pushToast({ variant: 'success', message: '메타를 저장했습니다.' });
    },
    onError: (err) => pushToast({ variant: 'error', message: portalWorkErrorMessage(err) }),
  });

  const items = useMemo(() => data?.items ?? [], [data]);
  const { sections, timeseries } = useMemo(() => splitEditableItems(items), [items]);

  // 시계열 메타를 새로 더할 자리 — 표준 열쇠가 응답에 이미 있으면 그 행이 곧 편집 자리다(중복 열쇠 금지).
  const manualSlot: PortalMetaItem | null = useMemo(() => {
    const slotAxis = axisKeyOf('video', MANUAL_TIMESERIES_META_KEY);
    if (items.some((item) => axisKeyOfItem(item) === slotAxis)) return null;
    return {
      metaKey: MANUAL_TIMESERIES_META_KEY,
      metaVl: null,
      scope: 'video',
      overridden: false,
      source: 'NONE',
    };
  }, [items]);

  const payloadSource = useMemo(
    () => (manualSlot === null ? items : [...items, manualSlot]),
    [items, manualSlot],
  );

  const busy = isLoading || save.isPending;
  const dirty = hasMetaChanges(payloadSource, draft);
  const canSave = srcSn !== undefined && dirty && !save.isPending;

  const valueOf = (item: PortalMetaItem) => draft[axisKeyOfItem(item)] ?? toFormValue(item.metaVl);
  const setValue = (item: PortalMetaItem, next: string) =>
    setDraft((prev) => ({ ...prev, [axisKeyOfItem(item)]: next }));

  const handleSave = () => {
    if (!canSave) return;
    const payload = buildMetaSavePayload(payloadSource, draft);
    if (payload.length === 0) return;
    save.mutate(payload);
  };

  if (srcSn === undefined) {
    return <EmptyState size="xs" title="프레임을 선택하면 메타를 표시합니다." />;
  }

  if (isError) {
    // 「없다」와 「남의 것이다」를 가르지 않는다 — 판정은 workError 한 곳이 한다.
    return (
      <Alert tone="danger">
        <span data-testid="portal-meta-error">{portalWorkErrorMessage(error)}</span>
      </Alert>
    );
  }

  const readOnly = data?.readOnlyMeta ?? [];
  const technical = data?.technicalMeta ?? [];

  return (
    <>
      <FilterPanel surface="bare" layout="stack" aria-label="메타" className="klid-labeling-meta">
        {sections.map((section) => (
          <FilterPanel.Disclosure key={section.title} label={section.title} defaultOpen>
            {section.rows.length === 0 ? (
              <EmptyState size="xs" title="표시할 항목이 없습니다." />
            ) : (
              section.rows.map(({ field, item }) => {
                const label = PORTAL_FIELD_LABEL[field.label] ?? field.label;
                return (
                  <FilterPanel.Field
                    key={axisKeyOf(field.scope, field.metaKey)}
                    label={label}
                    hint={overriddenBadge(item) ?? undefined}
                  >
                    <MetaControl
                      label={label}
                      control={field.control}
                      value={valueOf(item)}
                      disabled={busy}
                      onChange={(next) => setValue(item, next)}
                    />
                  </FilterPanel.Field>
                );
              })
            )}
          </FilterPanel.Disclosure>
        ))}

        <FilterPanel.Disclosure label="시계열 메타" defaultOpen>
          {(manualSlot === null ? timeseries : [...timeseries, manualSlot]).map((item) => {
            const label = editableMetaLabel(item.metaKey);
            return (
              <FilterPanel.Field
                key={axisKeyOfItem(item)}
                label={label}
                hint={overriddenBadge(item) ?? undefined}
              >
                <MetaControl
                  label={label}
                  control={{ kind: 'text', maxLength: META_VALUE_MAX_LENGTH }}
                  value={valueOf(item)}
                  disabled={busy}
                  onChange={(next) => setValue(item, next)}
                />
              </FilterPanel.Field>
            );
          })}
        </FilterPanel.Disclosure>

        {/* 고칠 수 없는 값 둘은 접어 둔다. 입력을 잠그는 게 아니라 아예 세우지 않는다(원본 규칙) */}
        {readOnly.length > 0 && (
          <FilterPanel.Disclosure label="참고 정보(수정 불가)">
            <KeyValueList
              ariaLabel="참고 정보"
              layout="stack"
              items={readOnly.map((item) => ({
                label: readOnlyMetaLabel(item.metaKey),
                value: item.metaVl != null && item.metaVl !== '' ? item.metaVl : NO_VALUE_MARK,
              }))}
            />
          </FilterPanel.Disclosure>
        )}
        {technical.length > 0 && (
          <FilterPanel.Disclosure label="영상 기술 정보(수정 불가)">
            <KeyValueList
              ariaLabel="영상 기술 정보"
              layout="stack"
              items={technical.map((item) => ({
                label: readOnlyMetaLabel(item.metaKey),
                value: item.metaVl != null && item.metaVl !== '' ? item.metaVl : NO_VALUE_MARK,
              }))}
            />
          </FilterPanel.Disclosure>
        )}
      </FilterPanel>

      <div className="klid-labeling-meta-save">
        <p className="klid-tool-panel-note">{META_SAVE_NOTE}</p>
        <Button
          size="small"
          variant="secondary"
          disabled={!canSave}
          className={save.isPending ? 'klid-btn-busy' : undefined}
          aria-busy={save.isPending || undefined}
          onClick={handleSave}
        >
          메타 저장
        </Button>
      </div>
    </>
  );
}
