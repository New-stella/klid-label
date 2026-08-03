// 라벨 선택 모달 — 도형 도구를 클릭/전환하는 시점에 뜬다 (2026-08-03 사용자 확정).
//
// 배경: 좌측 상시 라벨 패널(구 LabelSidebar)을 폐지하고, "도구 클릭 → 라벨 선택 → 드로잉" 순으로
//       조작 흐름을 바꿨다. 라벨을 고르기 전에는 캔버스 드로잉이 시작되지 않는다.
//
// 목록 출처는 **라벨 마스터 전체**(GET /v1/manage/labels, useLabelMasters).
//   ⚠ 프리셋(LS_LABEL_PRESET_CODE / features/preset)은 **오토라벨링 전용**이라 여기 소스로 쓰지 않는다.
// 정렬은 sortNo asc → labelId asc(구 좌측 패널 규칙 유지), 활성(useYn='Y')만 노출.
// 마스터 전체라 건수가 많을 수 있어 이름 검색을 둔다(표시명·원문 모두 매칭).
//
// 단축키: 폐지된 좌측 패널의 1~9 선택을 이 모달로 이전. 검색창 입력 중에는 발화하지 않는다.
//
// 보안:
//  - 검색어는 React 텍스트 노드/필터 값으로만 쓰고 DOM 에 주입하지 않는다(dangerouslySetInnerHTML 없음).
//  - 마스터 색상은 safeHexColor 로 '#RRGGBB' 검증 후에만 inline style 로 넘긴다(미검증 문자열 차단).
// 접근성: 시맨틱 button + aria-pressed(현재 선택) + 색상칩과 이름 텍스트 병행(색상만으로 구분 금지).

import { useEffect, useMemo, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../hooks/useLabelMasters';
import { safeHexColor } from '../utils/labelColor';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';

export interface LabelPickerModalProps {
  open: boolean;
  /** 모달을 띄운 도구의 사람 표기 — 안내 문구에만 사용. */
  toolName?: string;
  /** 라벨 확정 — 호출부가 activeLabelId 를 갱신하고 도구를 활성화한다. */
  onSelect: (labelId: number) => void;
  /** 취소(ESC/닫기/취소 버튼) — 호출부가 도구를 이전 상태로 되돌린다. */
  onCancel: () => void;
}

/** 1~9 로 고를 수 있는 최대 개수(구 좌측 패널과 동일). */
const DIGIT_SLOTS = 9;

export function LabelPickerModal({ open, toolName, onSelect, onCancel }: LabelPickerModalProps) {
  const { data, isLoading, isError } = useLabelMasters();
  const activeLabelId = useLabelStore((s) => s.activeLabelId);
  const [keyword, setKeyword] = useState('');

  // 모달을 다시 열 때 이전 검색어가 남아 목록이 비어 보이지 않도록 초기화.
  useEffect(() => {
    if (open) setKeyword('');
  }, [open]);

  const items = useMemo(() => {
    if (!data) return [];
    return data
      .filter((m) => m.useYn === 'Y')
      .map((m) => ({
        labelId: m.labelId,
        name: m.name,
        displayName: resolveLabelDisplayName(m.name, m.dtctTypeCd),
        color: safeHexColor(m.color),
        sortNo: m.sortNo,
      }))
      .sort((a, b) => (a.sortNo !== b.sortNo ? a.sortNo - b.sortNo : a.labelId - b.labelId));
  }, [data]);

  const filtered = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    if (q.length === 0) return items;
    return items.filter(
      (m) => m.displayName.toLowerCase().includes(q) || m.name.toLowerCase().includes(q),
    );
  }, [items, keyword]);

  // 1~9 — 현재 필터된 목록의 순번 라벨 선택. 입력 필드 포커스 중에는 무시(검색어 입력 보호).
  useEffect(() => {
    if (!open) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const target = e.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      ) {
        return;
      }
      if (!/^[1-9]$/.test(e.key)) return;
      const picked = filtered[Number(e.key) - 1];
      if (!picked) return;
      e.preventDefault();
      onSelect(picked.labelId);
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, filtered, onSelect]);

  return (
    <Modal
      open={open}
      onClose={onCancel}
      title="라벨 선택"
      description={
        toolName
          ? `${toolName} 도구로 그릴 라벨을 선택하세요. 선택한 라벨은 다음 선택 전까지 유지됩니다.`
          : '그릴 라벨을 선택하세요. 선택한 라벨은 다음 선택 전까지 유지됩니다.'
      }
      size="sm"
      footer={
        <Button type="button" variant="outline" onClick={onCancel}>
          취소
        </Button>
      }
    >
      <label className="mb-3 flex flex-col gap-1" htmlFor="label-picker-search">
        <span className="text-sub font-semibold text-gray-700">라벨 이름 검색</span>
        <input
          id="label-picker-search"
          type="text"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder="예: 사람"
          autoComplete="off"
          className="rounded-md border border-gray-300 px-2 py-1.5 text-body text-gray-900 focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
        />
      </label>

      {isLoading && (
        <p className="px-1 py-3 text-sub text-gray-500" role="status">
          라벨 목록을 불러오는 중입니다…
        </p>
      )}

      {isError && (
        <p className="px-1 py-3 text-sub text-danger" role="alert">
          라벨 조회 실패 — 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {!isLoading && !isError && items.length === 0 && (
        <p className="px-1 py-3 text-sub text-gray-500">
          등록된 라벨이 없습니다. 라벨 관리에서 라벨을 먼저 등록해 주세요.
        </p>
      )}

      {!isLoading && !isError && items.length > 0 && filtered.length === 0 && (
        <p className="px-1 py-3 text-sub text-gray-500" aria-live="polite">
          검색 결과가 없습니다.
        </p>
      )}

      {filtered.length > 0 && (
        <ul className="flex max-h-72 flex-col gap-1 overflow-y-auto" aria-label="라벨 목록">
          {filtered.map((m, idx) => {
            const isActive = activeLabelId === m.labelId;
            const digit = idx < DIGIT_SLOTS ? String(idx + 1) : null;
            const showOriginal = m.displayName !== m.name;
            return (
              <li key={m.labelId}>
                <button
                  type="button"
                  onClick={() => onSelect(m.labelId)}
                  aria-pressed={isActive}
                  className={
                    'flex w-full items-center gap-2 rounded-md px-2 py-2 text-left text-body transition-colors ' +
                    (isActive
                      ? 'bg-primary-600 text-white'
                      : 'text-gray-900 hover:bg-gray-100')
                  }
                >
                  <span
                    data-testid="label-picker-color"
                    aria-hidden="true"
                    className="h-3 w-3 shrink-0 rounded-sm border border-black/20"
                    style={{ backgroundColor: m.color }}
                  />
                  <span className="flex-1 truncate">{m.displayName}</span>
                  {showOriginal && (
                    <span
                      className={
                        'shrink-0 text-[11px] ' + (isActive ? 'text-white/70' : 'text-gray-400')
                      }
                    >
                      {m.name}
                    </span>
                  )}
                  {digit && (
                    <span
                      aria-hidden="true"
                      className={
                        'shrink-0 rounded px-1 text-[10px] ' +
                        (isActive ? 'bg-white/20 text-white' : 'bg-gray-100 text-gray-500')
                      }
                    >
                      {digit}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </Modal>
  );
}
