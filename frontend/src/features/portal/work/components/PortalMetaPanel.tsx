// 포털 라벨링 화면 '메타' 탭의 메타 목록 — 촬영환경·프레임 설명·개인정보 판정·시계열 메타.
//
// @design SCREEN-029
// @design API-234
// @design API-235
// @design AC-1068
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
// <h3>모습은 포털 부품으로 짠다 (2026-09-16)</h3>
// 구획은 접이식 조건판({@code FilterPanel.Disclosure}), 고르는 칸은 포털 드롭다운, 적는 칸은
// KRDS 입력칸, 고칠 수 없는 값은 이름·값 목록, 거부 안내는 포털 띠다. 관제 축 부품(`MetaSection`·
// 관제 `Select`/`Textarea`/`Button`)은 이 채널에서 쓰지 않는다 — 토큰과 루트 글꼴 전제가 갈린다.
// ⚠ 바뀐 것은 <b>모습뿐</b>이다. 조회·전송·승격 방지 판정은 한 줄도 건드리지 않았다.
//
// 보안(저장형 XSS 방어): 값은 드롭다운 값 / textarea value 로만 바인딩 — React 기본 escape.
//   dangerouslySetInnerHTML 미사용. 폭 상한은 입력 단계에서 강제한다.

import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Button, Textarea } from 'krds-react';

import {
  Alert,
  Dropdown,
  FilterPanel,
  KeyValueList,
  type DropdownOption,
} from '@/components/portal/kit';
import { StatusText } from '@/components/portal/authoring';
import { NO_VALUE_MARK } from '@/features/label/components/MetaSection';
import { UNSELECTED_LABEL } from '@/features/label/utils/shootingEnvironmentOptions';
import { editableMetaLabel, MANUAL_TIMESERIES_META_KEY } from '@/features/auto/metaKeys';
import { useUiStore } from '@/stores/useUiStore';

import {
  axisKeyOf,
  axisKeyOfItem,
  META_VALUE_MAX_LENGTH,
  overriddenBadge,
  readOnlyMetaLabel,
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

/** 고르지 않은 자리. 값이 빈 문자열인 <b>실재하는 선택지</b>라 목록 맨 위에 함께 선다. */
const UNSELECTED_OPTION: DropdownOption = { value: '', label: UNSELECTED_LABEL };

/** HTML id 로 쓸 수 있게 다듬은 식별자. 값 자체는 (축, 키) 쌍이다. */
function controlIdOf(scope: string, metaKey: string): string {
  return `portal-meta-${scope}-${metaKey.replace(/[^a-zA-Z0-9]/g, '-')}`;
}

/** 고르는 칸의 선택지 — 정의 순서를 그대로 따른다(표시 순서 변경 금지). */
function optionsOf(control: PortalMetaControl): DropdownOption[] {
  const source =
    control.kind === 'yn'
      ? YN_OPTIONS
      : control.kind === 'select'
        ? control.options
        : [];
  return [
    UNSELECTED_OPTION,
    ...source.map((opt) =>
      typeof opt === 'string' ? { value: opt, label: opt } : { value: opt.code, label: opt.label },
    ),
  ];
}

/**
 * 「내가 덮었는가」 표시. <b>이 패널이 그리는 배지는 이것 하나뿐이다.</b>
 *
 * ★★{@code item.source}(원본 쪽 값의 출처)는 <b>화면에 그리지 않는다</b> — 내부 화면도 같은 값을
 * 표시하지 않고 전송 판정에만 쓰므로 포털만 다르게 할 근거가 없다. 그렇다고 두 축이 하나가 된 것은
 * 아니다: 이 배지는 «내 값이 덮었는가»이고 출처는 «덮인 쪽이 무엇이었는가»라 여전히 다른 것을
 * 말한다. 출처는 {@code buildMetaSavePayload} 가 「사용자가 실제로 정한 값인가」를 가리는 데 쓰며,
 * 표시하지 않는다는 이유로 그 필드를 걷어내면 자동 계산값이 사람의 판정으로 승격되는 것을 막는
 * 방어가 사라진다(화면상 증상은 없다).
 */
function OverriddenBadge({ item }: { item: PortalMetaItem }) {
  const mine = overriddenBadge(item);
  if (mine === null) return null;
  return (
    <span className="hint" data-testid={`portal-meta-overridden-${axisKeyOfItem(item)}`}>
      {mine}
    </span>
  );
}

/**
 * 메타 한 칸 — 킷 {@code FilterPanel.Field} 와 <b>같은 짜임</b>(이름표 위·컨트롤 아래)에 두 가지를
 * 더한 것이다. 킷 Field 는 이름표를 문자열로만 받아 「내가 고침」 배지를 끼울 수 없고, (축, 키)
 * 표식을 받을 자리도 없다. 킷 부품은 고치지 않는 것이 규칙이라 여기에 한 겹을 둔다.
 *
 * ★이름표가 {@code <label>} 이 되는 것은 <b>적는 칸일 때뿐</b>이다 — 고르는 칸(드롭다운)은 트리거가
 *   버튼이라 {@code htmlFor} 로 이어지지 않는다. 그쪽 이름은 드롭다운의 {@code aria-label} 이 잇는다.
 */
function MetaField({
  label,
  htmlFor,
  badge,
  testId,
  children,
}: {
  label: string;
  htmlFor?: string;
  badge?: ReactNode;
  testId: string;
  children: ReactNode;
}) {
  const name = (
    <>
      {label}
      {badge}
    </>
  );
  return (
    <div className="klid-filter-field" data-testid={testId}>
      {htmlFor ? (
        <label className="label" htmlFor={htmlFor}>
          {name}
        </label>
      ) : (
        <span className="label">{name}</span>
      )}
      <div className="control">{children}</div>
    </div>
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
      <Textarea
        id={id}
        value={value}
        onChange={onChange}
        disabled={disabled}
        // ★폭은 좁은 쪽이다 — 넓은 쪽을 허용하면 사용자가 다 쓰고 나서 창구에 거부당한다.
        maxLength={control.maxLength}
        // 글자수는 상한과 <b>같은 값</b>으로 센다. 킷 기본값(100)을 그대로 두면 「0 / 100」 이 떠
        // 실제 상한을 거짓으로 알린다.
        showCount
        countTotal={control.maxLength}
        aria-label={label}
      />
    );
  }

  return (
    <Dropdown
      size="small"
      aria-label={label}
      value={value}
      onChange={onChange}
      disabled={disabled}
      options={optionsOf(control)}
    />
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
    <MetaField
      label={field.label}
      htmlFor={field.control.kind === 'text' ? id : undefined}
      badge={<OverriddenBadge item={item} />}
      testId={`portal-meta-row-${axisKeyOf(field.scope, field.metaKey)}`}
    >
      <MetaValueControl
        id={id}
        label={field.label}
        control={field.control}
        value={value}
        disabled={disabled}
        onChange={onChange}
      />
    </MetaField>
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
    // 상태 한 줄은 제 {@code role="status"} 를 갖는다 — 프레임을 고르면 이 자리가 목록으로 바뀐다.
    return <StatusText tone="info">프레임을 선택하면 메타를 표시합니다</StatusText>;
  }

  if (isError) {
    return (
      // ★「없다」와 「남의 것이다」를 가르지 않는다 — 판정은 workError 한 곳이 한다.
      //   띠가 성격(danger)에서 role="alert" 를 스스로 정한다.
      <div data-testid="portal-meta-error">
        <Alert tone="danger">{portalWorkErrorMessage(error)}</Alert>
      </div>
    );
  }

  return (
    <div data-testid="portal-meta-panel" className="klid-labeling-panel-group">
      {/* 사양 고정 순서: 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보 → 시계열 메타.
          적는 구역은 펼친 채 서고, 고칠 수 없는 두 구역만 접힌 채 시작한다. */}
      <FilterPanel surface="bare" layout="stack" aria-label="메타">
        {sections.map((section) => (
          <FilterPanel.Disclosure key={section.title} label={section.title} defaultOpen>
            {section.rows.length === 0 ? (
              <p className="klid-tool-panel-note">표시할 항목이 없습니다.</p>
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
          </FilterPanel.Disclosure>
        ))}

        <FilterPanel.Disclosure label="시계열 메타" defaultOpen>
          {/* 응답이 준 항목 뒤에 «새로 더할 자리»를 잇는다(표준 열쇠가 이미 있으면 자리는 없다). */}
          {(manualSlot === null ? timeseries : [...timeseries, manualSlot]).map((item) => {
            const id = controlIdOf(item.scope, item.metaKey);
            const label = editableMetaLabel(item.metaKey);
            return (
              <MetaField
                key={axisKeyOfItem(item)}
                label={label}
                htmlFor={id}
                badge={<OverriddenBadge item={item} />}
                testId={`portal-meta-row-${axisKeyOfItem(item)}`}
              >
                <MetaValueControl
                  id={id}
                  label={label}
                  control={{ kind: 'text', maxLength: META_VALUE_MAX_LENGTH }}
                  value={valueOf(item)}
                  disabled={busy}
                  onChange={(next) => setValue(item, next)}
                />
              </MetaField>
            );
          })}
        </FilterPanel.Disclosure>

        {(data?.readOnlyMeta.length ?? 0) > 0 && (
          // ★표시만 하고 고칠 수 없다 — 비활성 컨트롤을 두지 않고 <b>입력 자체를 렌더하지 않는다</b>
          //   (눌리는 모양인데 반응이 없으면 사용자가 고장으로 읽는다). 창구도 수정 요청을 거부한다.
          //   이름·값 목록은 면 없이 쌓는 꼴이라 이미 흰 면인 옆 칸 위에 상자를 겹치지 않는다.
          <FilterPanel.Disclosure label="참고 정보(수정 불가)">
            <div data-testid="portal-meta-readonly">
              <KeyValueList
                ariaLabel="참고 정보"
                layout="stack"
                items={(data?.readOnlyMeta ?? []).map((item) => ({
                  label: readOnlyMetaLabel(item.metaKey),
                  value: item.metaVl != null && item.metaVl !== '' ? item.metaVl : NO_VALUE_MARK,
                }))}
              />
            </div>
          </FilterPanel.Disclosure>
        )}

        {(data?.technicalMeta.length ?? 0) > 0 && (
          <FilterPanel.Disclosure label="영상 기술 정보(수정 불가)">
            <div data-testid="portal-meta-technical">
              <KeyValueList
                ariaLabel="영상 기술 정보"
                layout="stack"
                items={(data?.technicalMeta ?? []).map((item) => ({
                  // ★이름을 정하지 못한 열쇠는 버리지 않고 원문 그대로 쓴다(조용한 손실 금지).
                  label: readOnlyMetaLabel(item.metaKey),
                  value: item.metaVl != null && item.metaVl !== '' ? item.metaVl : NO_VALUE_MARK,
                }))}
              />
            </div>
          </FilterPanel.Disclosure>
        )}
      </FilterPanel>

      <div className="klid-labeling-meta-save">
        {/*
          ⚠ 무변경 저장 안내 — 문구는 <b>확정된 것이 없다</b>(사양에도 요건만 있다). 사실만 평이하게
            적는다: 원본과 같은 값은 창구가 「내 작업물」로 남기지 않는다. 「저장됨」이라고만 알리면
            사용자는 자기 확정이 남았다고 믿는다. 누르기 전에 읽혀야 하므로 걸음 <b>위</b>에 둔다.
        */}
        <p className="klid-tool-panel-note">
          원본과 같은 값은 저장해도 내 작업물로 남지 않습니다. 저장한 값은 이 화면에서만 쓰이며
          원본과 데이터마트에는 반영되지 않습니다.
        </p>
        {/*
          ★킷 버튼에는 기다림 상태가 없다 — 포털이 쓰는 대로 도는 고리를 클래스로 얹는다
            (`krds-theme.css` 의 `.krds-btn.klid-btn-busy`). 걸음이 칸 폭을 다 쓰는 것은
            `.klid-labeling-meta-save > .krds-btn` 이 맡는다.
          ★여기서는 `disabled` 를 쓴다 — 못 누르는 사유를 따로 알릴 것이 없는 자리다(고친 것이
            없으면 저장할 것도 없다). 사유가 있는 잠금만 `aria-disabled` 로 초점을 남긴다.
        */}
        <Button
          size="small"
          variant="secondary"
          className={save.isPending ? 'klid-btn-busy' : undefined}
          aria-busy={save.isPending || undefined}
          onClick={handleSave}
          disabled={!canSave}
        >
          메타 저장
        </Button>
      </div>
    </div>
  );
}
