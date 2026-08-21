import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { useEventTypeAdminList } from '@/features/eventType/adminHooks';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';

import { MappingKind, type ImportMappingCreateItem, type UnmappedCategory } from '../types';

/** 표 헤더 셀 — DS-001 표 표면 관례(14px/600 토큰 + 대문자화). th 에 직접 건다. */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

const KIND_LABEL: Record<MappingKind, string> = {
  [MappingKind.LABEL]: '라벨',
  [MappingKind.EVNT_TYPE]: '이벤트 유형',
};

/** 대응 대상 후보 1건 — 두 축을 같은 모양으로 다룬다. */
interface TargetOption {
  value: string;
  label: string;
}

/** 행 키 — 종류와 외부 코드의 짝이 유일하다(서버의 유일 제약과 같은 축). */
function rowKeyOf(category: UnmappedCategory): string {
  return `${category.kind}:${category.externalCode}`;
}

export interface UnmappedCategorySectionProps {
  categories: UnmappedCategory[];
  saving: boolean;
  onConfirm: (items: ImportMappingCreateItem[]) => void;
}

/**
 * 분류 대응 확정 — 처음 보는 분류를 저작도구 라벨·이벤트 유형에 연결한다.
 *
 * ★추천은 후보 제시까지다. 서버가 이름이 비슷한 후보를 하나 골라 주면 미리 선택해 두지만
 * **사람이 확인 칸을 켠 행만** 확정 요청에 실린다. 후보는 **비어 있을 수 있고**(후보 0건이거나
 * 유일하지 않은 경우) 그때는 사람이 목록에서 직접 고른다 — 빈 후보는 오류가 아니다.
 *
 * ★표는 「외부 분류 코드」와 「외부 표시 이름」을 두 열로 유지한다. 라벨 축은 산출물이 영문
 * 코드와 표시 이름을 따로 주므로 두 값이 서로 다르고, 이벤트 축은 코드가 없어 이름이 곧 코드
 * 자리에 들어가 두 열이 같게 보인다. 그것은 그 축의 사실이며 열을 합치면 라벨 축이 손해를 본다.
 *
 * 이 구획의 선택 상태는 검사 결과에 딸린 것이라, 새로 검사하면 부모가 `key` 를 갈아 초기화한다.
 *
 * @design SCREEN-039
 * @design API-209
 * @design API-210
 */
export function UnmappedCategorySection({
  categories,
  saving,
  onConfirm,
}: UnmappedCategorySectionProps) {
  const { data: labelMasters } = useLabelMasters();
  const { data: eventTypes } = useEventTypeAdminList();

  /** 행별 선택한 대응 대상. 추천 후보가 유일하면 그 값으로 시작한다. */
  const [targets, setTargets] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const c of categories) {
      const suggestion = c.suggestions[0];
      if (c.suggestions.length === 1 && suggestion) initial[rowKeyOf(c)] = suggestion.targetId;
    }
    return initial;
  });
  /** 사람이 확인한 행. 추천이 있어도 켜지지 않은 채로 시작한다. */
  const [confirmed, setConfirmed] = useState<Record<string, boolean>>({});

  const labelOptions: TargetOption[] = (labelMasters ?? [])
    .filter((m) => m.useYn === 'Y')
    .map((m) => ({ value: String(m.labelId), label: m.name }));
  const eventOptions: TargetOption[] = (eventTypes ?? []).map((e) => ({
    value: e.evntTypeCd,
    label: e.dsplNm,
  }));

  const optionsFor = (kind: MappingKind): TargetOption[] =>
    kind === MappingKind.LABEL ? labelOptions : eventOptions;

  /** 확정 대상 — 확인 칸이 켜졌고 대상이 골라진 행만. */
  const readyItems: ImportMappingCreateItem[] = categories
    .filter((c) => confirmed[rowKeyOf(c)] && targets[rowKeyOf(c)])
    .map((c) => {
      const target = targets[rowKeyOf(c)] as string;
      return c.kind === MappingKind.LABEL
        ? {
            kind: c.kind,
            externalCode: c.externalCode,
            externalName: c.externalName,
            labelId: Number(target),
          }
        : {
            kind: c.kind,
            externalCode: c.externalCode,
            externalName: c.externalName,
            evntTypeCd: target,
          };
    });

  if (categories.length === 0) return null;

  return (
    <section
      aria-labelledby="import-unmapped-heading"
      data-testid="import-unmapped-section"
      className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="import-unmapped-heading" className="text-title-sm text-gray-900">
        처음 보는 분류
      </h2>
      <p className="text-body-sm text-gray-700">
        연결하지 않은 분류가 남아 있으면 적재할 수 없습니다. 이름만 보고 자동으로 정하지 않는 것은,
        짐작으로 연결하면 다른 분류로 저장되고 나중에 구분할 수 없기 때문입니다.
      </p>

      <div className="overflow-hidden rounded-lg border border-gray-200">
        <table className="w-full text-body-md" data-testid="import-unmapped-table">
          <thead>
            <tr className="border-b border-gray-200 bg-secondary-50">
              <th className={TH_CLASS}>종류</th>
              <th className={TH_CLASS}>외부 분류 코드</th>
              <th className={TH_CLASS}>외부 표시 이름</th>
              <th className={TH_CLASS}>연결할 대상</th>
              <th className={TH_CLASS}>확인</th>
            </tr>
          </thead>
          <tbody>
            {categories.map((c) => {
              const key = rowKeyOf(c);
              const options = optionsFor(c.kind);
              return (
                <tr
                  key={key}
                  data-testid={`import-unmapped-row-${key}`}
                  className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                >
                  <td className="px-3 py-2 text-gray-700">{KIND_LABEL[c.kind]}</td>
                  <td className="px-3 py-2 text-gray-700">
                    <span className="font-mono text-mono">{c.externalCode}</span>
                  </td>
                  <td className="px-3 py-2 text-gray-700">{c.externalName ?? '-'}</td>
                  <td className="px-3 py-2">
                    <Select
                      value={targets[key] ?? ''}
                      onValueChange={(v) => setTargets((prev) => ({ ...prev, [key]: v }))}
                    >
                      <SelectTrigger aria-label={`${c.externalCode} 연결할 대상`}>
                        <SelectValue placeholder="고르지 않음" />
                      </SelectTrigger>
                      <SelectContent>
                        {options.length === 0 && (
                          <SelectItem value="__empty__" disabled>
                            고를 수 있는 대상이 없습니다
                          </SelectItem>
                        )}
                        {options.map((o) => (
                          <SelectItem key={o.value} value={o.value}>
                            {o.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </td>
                  <td className="px-3 py-2">
                    <Checkbox
                      aria-label={`${c.externalCode} 대응 확인`}
                      data-testid={`import-unmapped-confirm-${key}`}
                      checked={confirmed[key] ?? false}
                      onCheckedChange={(v) =>
                        setConfirmed((prev) => ({ ...prev, [key]: v === true }))
                      }
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-end gap-3">
        <span className="text-caption text-gray-600">확인한 분류 {readyItems.length}건</span>
        <Button
          variant="secondary"
          data-testid="import-mapping-confirm-button"
          loading={saving}
          disabled={readyItems.length === 0}
          onClick={() => onConfirm(readyItems)}
        >
          대응 확정
        </Button>
      </div>
    </section>
  );
}
