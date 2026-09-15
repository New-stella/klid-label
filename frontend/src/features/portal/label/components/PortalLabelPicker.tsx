// 포털 라벨링 — 라벨 선택 창. 그리기 도구를 켜기 전에 무엇을 그릴지 고른다.
//
// <h3>흐름은 원본 그대로</h3>
// 원본 라벨 선택 창(`LabelPickerModal`)과 같은 규칙이다 — 목록은 라벨 마스터 전체(활성만,
// 정렬 sortNo → labelId), 이름 검색, 1~9 는 위에서부터 그 줄을 고르는 키(검색칸에 적는 중에는 듣지 않는다),
// 줄을 누르면 곧바로 확정, 「취소」·X 는 도구를 이전 상태로 되돌린다(`useToolLabelPicker`).
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 라벨 선택 창)과 같다 — 작은 창(sm) 안에 설명 글 ·
// 검색칸 · 도구 목록 모양의 라벨 줄(색 칩 · 이름 · 숫자 키). 메인 걸음이 없다(줄이 곧 확정이다).
//
// 보안: 검색어는 필터 값으로만 쓴다. 마스터 색은 `safeHexColor` 로 검증한 값만 칩에 넘긴다.

import { useEffect, useMemo, useState } from 'react';
import { TextInput } from 'krds-react';

import { Alert, EmptyState, Modal } from '@portal/components/custom';
import { ToolList } from '@portal/pages/workspace/authoring/ToolList';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import { safeHexColor } from '@/features/label/utils/labelColor';
import { resolveLabelDisplayName } from '@/features/label/utils/labelDisplayName';
import { useLabelStore } from '@/stores/useLabelStore';

/** 1~9 로 고를 수 있는 최대 개수(원본과 같다). */
const DIGIT_SLOTS = 9;

export function PortalLabelPicker({
  open,
  toolName,
  onSelect,
  onCancel,
}: {
  open: boolean;
  /** 창을 띄운 도구의 이름 — 설명 글에만 쓴다. */
  toolName?: string;
  onSelect: (labelId: number) => void;
  onCancel: () => void;
}) {
  const { data, isLoading, isError } = useLabelMasters();
  const activeLabelId = useLabelStore((s) => s.activeLabelId);
  const [keyword, setKeyword] = useState('');

  // 다시 열 때 지난 검색어가 남아 목록이 비어 보이지 않게 비운다.
  useEffect(() => {
    if (open) setKeyword('');
  }, [open]);

  const items = useMemo(() => {
    if (!data) return [];
    return data
      .filter((m) => m.useYn === 'Y')
      .map((m) => ({
        labelId: m.labelId,
        displayName: resolveLabelDisplayName(m.name),
        color: safeHexColor(m.color),
        sortNo: m.sortNo,
      }))
      .sort((a, b) => (a.sortNo !== b.sortNo ? a.sortNo - b.sortNo : a.labelId - b.labelId));
  }, [data]);

  const filtered = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    if (q.length === 0) return items;
    return items.filter((m) => m.displayName.toLowerCase().includes(q));
  }, [items, keyword]);

  // 1~9 — 지금 걸러진 목록의 순번으로 고른다. 입력칸에 적는 중에는 무시한다.
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
      size="sm"
      open={open}
      onOpenChange={(next) => !next && onCancel()}
      title="라벨 선택"
      sub={{ label: '취소', close: true }}
    >
      <p className="klid-labeling-picker-desc">
        {toolName ? `${toolName} 도구로 ` : ''}그릴 라벨을 선택해 주세요. 선택한 라벨은 다음 선택 전까지
        유지됩니다.
      </p>
      <TextInput
        size="small"
        label="라벨 이름 검색"
        placeholder="예: 사람"
        autoComplete="off"
        value={keyword}
        onChange={setKeyword}
      />
      {isLoading ? (
        <EmptyState size="xs" busy title="라벨 목록을 불러오고 있습니다." />
      ) : isError ? (
        <Alert tone="danger" title="라벨 조회 실패">
          잠시 후 다시 시도해 주세요.
        </Alert>
      ) : items.length === 0 ? (
        <EmptyState
          size="xs"
          title="등록된 라벨이 없습니다."
          desc="라벨 관리에서 라벨을 먼저 등록해 주세요."
        />
      ) : filtered.length === 0 ? (
        <EmptyState size="xs" title="검색 결과가 없습니다." />
      ) : (
        <ToolList
          className="klid-labeling-picker-list"
          label="라벨 목록"
          value={activeLabelId === null ? '' : String(activeLabelId)}
          items={filtered.map((m, i) => ({
            value: String(m.labelId),
            label: m.displayName,
            swatch: m.color,
            shortcut: i < DIGIT_SLOTS ? String(i + 1) : undefined,
          }))}
          onChange={(v) => onSelect(Number(v))}
        />
      )}
    </Modal>
  );
}
