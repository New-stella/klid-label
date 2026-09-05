// 포털 라벨링 화면 '메타' 탭의 메타 목록 — 촬영환경·프레임 설명·개인정보 판정·시계열 메타.
//
// @design SCREEN-029
// @design API-234
// @design API-235
//
// <h3>★ 포털 전용 창구만 부른다</h3>
// 같은 값을 고치는 내부 창구를 부르면 원장 컬럼 쓰기·재검토 표시·관제 통지·동결본 재동결이 함께
// 일어나 「원본·데이터마트를 수정하지 않는다(단방향)」는 최상위 불변이 한 번에 깨진다. 이 패널이
// 쓰는 훅은 포털 창구만 부른다.
//
// <h3>★★ 원소 식별은 (축, 메타 키) 쌍이다</h3>
// 개인정보 세 항목이 영상 축과 프레임 축 양쪽에 같은 이름으로 있다. 상태 맵·React key·전송 대상
// 판정이 전부 {@code axisKeyOf} 를 거친다 — 키만 쓰면 두 축이 한 자리로 접혀 함께 움직인다.
//
// <h3>★ 사용자가 직접 고치지 않은 항목은 전송하지 않는다</h3>
// 판정은 {@code buildMetaSavePayload} 가 소유하며 그 안에서 내부 화면과 <b>같은 함수</b>를 부른다.
//
// 보안(저장형 XSS 방어): 값은 select value / textarea value 로만 바인딩 — React 기본 escape.
//   dangerouslySetInnerHTML 미사용. 폭 상한은 입력 단계에서 강제한다.

import { useEffect, useMemo, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import {
  MetaCharCount,
  MetaReadonlyField,
  MetaSection,
} from '@/features/label/components/MetaSection';
import { UNSELECTED_LABEL } from '@/features/label/utils/shootingEnvironmentOptions';
import { editableMetaLabel, MANUAL_TIMESERIES_META_KEY } from '@/features/auto/metaKeys';
import { useUiStore } from '@/stores/useUiStore';

import {
  axisKeyOf,
  axisKeyOfItem,
  META_VALUE_MAX_LENGTH,
  overriddenBadge,
  readOnlyMetaLabel,
  sourceBadge,
  splitEditableItems,
  type PortalMetaControl,
  type PortalMetaFieldDef,
} from '../metaFields';
import { buildMetaSavePayload, hasMetaChanges, toFormValue } from '../metaSavePayload';
import { usePortalFrameMeta, usePortalSaveFrameMeta } from '../hooks/usePortalFrameMeta';
import type { PortalMetaItem } from '../types';
import { portalWorkErrorMessage } from '../workError';

export interface PortalMetaPanelProps {
  /** 프레임 PK. 없으면 조회하지 않고 안내만 둔다. */
  srcSn: number | undefined;
}

/** 예/아니오 판정의 선택지 — 「판정하지 않음」과 「아니오」는 다르므로 빈 값을 함께 둔다. */
const YN_OPTIONS = [
  { code: 'Y', label: '예' },
  { code: 'N', label: '아니오' },
] as const;

const FIELD_LABEL_CLASS = 'block text-caption text-gray-500 mb-1';
const BADGE_CLASS = 'text-[10px] text-gray-500';

/** HTML id 로 쓸 수 있게 다듬은 식별자. 값 자체는 (축, 키) 쌍이다. */
function controlIdOf(scope: string, metaKey: string): string {
  return `portal-meta-${scope}-${metaKey.replace(/[^a-zA-Z0-9]/g, '-')}`;
}

/**
 * 값의 두 표시 — 「내가 덮었는가」와 「덮인 쪽이 무엇이었는가」.
 *
 * ★★두 배지를 하나로 합치지 말 것(서로 다른 것을 말한다). 덮었고 그 아래가 자동 계산값이면
 * 두 배지가 함께 서서 「자동으로 채워져 있던 값을 내가 바꿨다」가 된다.
 */
function MetaBadges({ item }: { item: PortalMetaItem }) {
  const mine = overriddenBadge(item);
  const origin = sourceBadge(item);
  if (mine === null && origin === null) return null;
  return (
    <span className="ml-1 inline-flex gap-1" data-testid={`portal-meta-badges-${axisKeyOfItem(item)}`}>
      {mine !== null && <span className={BADGE_CLASS}>{mine}</span>}
      {origin !== null && <span className={BADGE_CLASS}>{origin}</span>}
    </span>
  );
}

interface ControlProps {
  id: string;
  label: string;
  control: PortalMetaControl;
  value: string;
  disabled: boolean;
  onChange: (next: string) => void;
}

function MetaValueControl({ id, label, control, value, disabled, onChange }: ControlProps) {
  if (control.kind === 'text') {
    return (
      <>
        <Textarea
          id={id}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          // ★폭은 좁은 쪽이다 — 넓은 쪽을 허용하면 사용자가 다 쓰고 나서 창구에 거부당한다.
          maxLength={control.maxLength}
          aria-label={label}
          className="min-h-[96px] resize-y text-body-md"
        />
        <MetaCharCount current={value.length} max={control.maxLength} />
      </>
    );
  }

  const options =
    control.kind === 'yn'
      ? YN_OPTIONS
      : (control.options as readonly { code: string; label: string }[] | readonly string[]);

  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger id={id} className="text-body-md">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {/* 옵션 순서는 정의 순서를 그대로 따른다(표시 순서 변경 금지). */}
        <SelectItem value="">{UNSELECTED_LABEL}</SelectItem>
        {options.map((opt) =>
          typeof opt === 'string' ? (
            <SelectItem key={opt} value={opt}>
              {opt}
            </SelectItem>
          ) : (
            <SelectItem key={opt.code} value={opt.code}>
              {opt.label}
            </SelectItem>
          ),
        )}
      </SelectContent>
    </Select>
  );
}

function EditableRow({
  field,
  item,
  value,
  disabled,
  onChange,
}: {
  field: PortalMetaFieldDef;
  item: PortalMetaItem;
  value: string;
  disabled: boolean;
  onChange: (next: string) => void;
}) {
  const id = controlIdOf(field.scope, field.metaKey);
  return (
    <div data-testid={`portal-meta-row-${axisKeyOf(field.scope, field.metaKey)}`}>
      <label htmlFor={id} className={FIELD_LABEL_CLASS}>
        {field.label}
        <MetaBadges item={item} />
      </label>
      <MetaValueControl
        id={id}
        label={field.label}
        control={field.control}
        value={value}
        disabled={disabled}
        onChange={onChange}
      />
    </div>
  );
}

export function PortalMetaPanel({ srcSn }: PortalMetaPanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { data, isLoading, isError, error } = usePortalFrameMeta(srcSn);

  /**
   * 사용자가 <b>손댄 항목만</b> 담는다((축, 키) 쌍 → 값). 손대지 않은 항목은 여기 없고 화면은
   * 서버 값을 그대로 그린다 — 서버 값을 통째로 복사해 두면 「손댔는가」를 알 수 없게 된다.
   */
  const [draft, setDraft] = useState<Record<string, string>>({});
  // 프레임이 바뀌면 이전 프레임의 편집 흔적을 남기지 않는다.
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

  /**
   * 시계열 메타를 <b>새로 더할</b> 자리.
   *
   * ★기존 항목이 있어도 둔다 — 사양은 다섯 축 전부에 「확인·수정·<b>추가</b>」를 요구한다.
   *   구 동작(편집 가능한 시계열 항목이 <b>하나도 없을 때만</b> 둔다)은 서버가 항목을 하나라도
   *   내려주는 순간 추가를 통째로 막았다.
   * ★그런데 <b>중복 열쇠는 만들지 않는다</b> — 표준 열쇠가 이미 응답에 있으면 그 행이 곧 편집
   *   자리다. 빈 자리를 따로 두면 같은 열쇠가 두 자리에 서고, 창구는 같은 열쇠를 덮어쓰므로
   *   한쪽에 쓴 값이 다른 쪽 값에 조용히 지워진다.
   * ★열쇠는 내부 화면과 같은 <b>표준 열쇠</b>를 쓴다 — 사용자가 임의 열쇠를 만들게 하지 않는다
   *   (사양에 없다).
   */
  const manualSlot: PortalMetaItem | null = useMemo(() => {
    // 메타 원장은 (영상, 키) 단위라 이 축은 영상이다.
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

  /** 전송 대상 판정의 모집단 — 응답 원소 + (있다면) 새로 더하는 자리. */
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
    // 보낼 것이 없으면 요청하지 않는다 — 빈 목록은 창구가 거부한다(그리고 보낼 이유도 없다).
    if (payload.length === 0) return;
    save.mutate(payload);
  };

  if (srcSn === undefined) {
    return (
      <MetaSection title="메타">
        <p className="text-caption text-gray-500" role="status">
          프레임을 선택하면 메타를 표시합니다.
        </p>
      </MetaSection>
    );
  }

  if (isError) {
    return (
      <MetaSection title="메타">
        {/* ★「없다」와 「남의 것이다」를 가르지 않는다 — 판정은 workError 한 곳이 한다. */}
        <p className="text-caption text-danger" role="alert" data-testid="portal-meta-error">
          {portalWorkErrorMessage(error)}
        </p>
      </MetaSection>
    );
  }

  return (
    <div data-testid="portal-meta-panel">
      {/* 사양 고정 순서: 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보 → 시계열 메타. */}
      {sections.map((section) => (
        <MetaSection key={section.title} title={section.title}>
          {section.rows.length === 0 ? (
            <p className="text-caption text-gray-500">표시할 항목이 없습니다.</p>
          ) : (
            section.rows.map(({ field, item }) => (
              <EditableRow
                key={axisKeyOf(field.scope, field.metaKey)}
                field={field}
                item={item}
                value={valueOf(item)}
                disabled={busy}
                onChange={(next) => setValue(item, next)}
              />
            ))
          )}
        </MetaSection>
      ))}

      <MetaSection title="시계열 메타">
        {/* 응답이 준 항목 뒤에 «새로 더할 자리»를 잇는다(표준 열쇠가 이미 있으면 자리는 없다). */}
        {(manualSlot === null ? timeseries : [...timeseries, manualSlot]).map(
          (item) => {
            const id = controlIdOf(item.scope, item.metaKey);
            const label = editableMetaLabel(item.metaKey);
            return (
              <div key={axisKeyOfItem(item)} data-testid={`portal-meta-row-${axisKeyOfItem(item)}`}>
                <label htmlFor={id} className={FIELD_LABEL_CLASS}>
                  {label}
                  <MetaBadges item={item} />
                </label>
                <MetaValueControl
                  id={id}
                  label={label}
                  control={{ kind: 'text', maxLength: META_VALUE_MAX_LENGTH }}
                  value={valueOf(item)}
                  disabled={busy}
                  onChange={(next) => setValue(item, next)}
                />
              </div>
            );
          },
        )}
      </MetaSection>

      {(data?.readOnlyMeta.length ?? 0) > 0 && (
        // ★표시만 하고 고칠 수 없다 — 비활성 컨트롤을 두지 않고 <b>입력 자체를 렌더하지 않는다</b>
        //   (눌리는 모양인데 반응이 없으면 사용자가 고장으로 읽는다). 창구도 수정 요청을 거부한다.
        <MetaSection title="참고 정보(수정 불가)" defaultOpen={false}>
          <div data-testid="portal-meta-readonly">
            {data?.readOnlyMeta.map((item) => (
              <MetaReadonlyField
                key={axisKeyOfItem(item)}
                label={readOnlyMetaLabel(item.metaKey)}
                value={item.metaVl}
              />
            ))}
          </div>
        </MetaSection>
      )}

      {(data?.technicalMeta.length ?? 0) > 0 && (
        <MetaSection title="영상 기술 정보(수정 불가)" defaultOpen={false}>
          <div data-testid="portal-meta-technical">
            {data?.technicalMeta.map((item) => (
              <MetaReadonlyField
                key={axisKeyOfItem(item)}
                // ★이름을 정하지 못한 열쇠는 버리지 않고 원문 그대로 쓴다(조용한 손실 금지).
                label={readOnlyMetaLabel(item.metaKey)}
                value={item.metaVl}
              />
            ))}
          </div>
        </MetaSection>
      )}

      <div className="space-y-1.5 px-2 pb-3">
        {/*
          ⚠ 무변경 저장 안내 — 문구는 <b>확정된 것이 없다</b>(사양에도 요건만 있다). 사실만 평이하게
            적는다: 원본과 같은 값은 창구가 「내 작업물」로 남기지 않는다. 「저장됨」이라고만 알리면
            사용자는 자기 확정이 남았다고 믿는다.
        */}
        <p className="text-[11px] leading-snug text-gray-500">
          원본과 같은 값은 저장해도 내 작업물로 남지 않습니다. 저장한 값은 이 화면에서만 쓰이며
          원본과 데이터마트에는 반영되지 않습니다.
        </p>
        <Button
          size="sm"
          fullWidth
          onClick={handleSave}
          disabled={!canSave}
          loading={save.isPending}
        >
          메타 저장
        </Button>
      </div>
    </div>
  );
}
