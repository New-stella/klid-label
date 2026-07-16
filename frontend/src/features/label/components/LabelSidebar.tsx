// Phase 7 — 라벨 마스터 사이드바.
//
// 좌측 DarkToolbar(w-14) 우측에 위치. BE /v1/manage/labels 응답을 sortNo asc 로 렌더링.
// - 색상 박스 + 한글 이름 + sortNo<=9 단축키 번호
// - 클릭 → useLabelStore.setActiveLabelId
// - 현재 activeLabelId 라벨 강조 (aria-pressed=true)
//
// 보안: 색상은 BE 검증된 #RRGGBB 만 통과 (label 정규화 단계에서 fallback).
// CSS-in-JS 가 아닌 inline style backgroundColor 사용 — Tailwind 동적 클래스 회피.

import { useMemo } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../hooks/useLabelMasters';
import { KeypointGuide } from './KeypointGuide';

interface LabelSidebarProps {
  /**
   * KEYPOINT 순차 배치 진행 인덱스(0~16). 배치 중일 때만 하단에 키포인트 가이드 렌더.
   * 미진행/완료 시 null → 가이드 미표시.
   */
  keypointPlacingIndex?: number | null;
}

export function LabelSidebar({ keypointPlacingIndex = null }: LabelSidebarProps) {
  const { data, isLoading, isError } = useLabelMasters();
  const activeLabelId = useLabelStore((s) => s.activeLabelId);
  const setActiveLabelId = useLabelStore((s) => s.setActiveLabelId);

  const sorted = useMemo(() => {
    if (!data) return [];
    return [...data]
      .filter((m) => m.useYn === 'Y')
      .sort((a, b) => {
        if (a.sortNo !== b.sortNo) return a.sortNo - b.sortNo;
        return a.labelId - b.labelId;
      });
  }, [data]);

  return (
    <aside
      className="flex flex-col items-stretch gap-0.5 p-2 bg-gray-800 border-r border-gray-700 w-56 shrink-0 overflow-y-auto"
      role="navigation"
      aria-label="라벨 마스터"
    >
      <div className="px-1 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide">
        라벨
      </div>

      {isLoading && (
        <p className="px-2 py-1 text-xs text-gray-400" role="status">
          로딩 중...
        </p>
      )}

      {isError && (
        <p className="px-2 py-1 text-xs text-red-400" role="alert">
          라벨 조회 실패
        </p>
      )}

      {!isLoading && !isError && sorted.length === 0 && (
        <p className="px-2 py-1 text-xs text-gray-500">등록된 라벨이 없습니다</p>
      )}

      {sorted.map((m, idx) => {
        const isActive = activeLabelId === m.labelId;
        // sortNo 가 1~9 일 때만 단축키 번호 표시 (10 이상은 키보드 매핑 불가)
        const shortcut = idx < 9 ? String(idx + 1) : null;
        return (
          <button
            key={m.labelId}
            type="button"
            onClick={() => setActiveLabelId(m.labelId)}
            aria-pressed={isActive}
            aria-label={`라벨 ${m.name}${shortcut ? ` (단축키 ${shortcut})` : ''}`}
            title={shortcut ? `${m.name} (${shortcut})` : m.name}
            className={
              'flex items-center gap-2 px-2 py-1.5 rounded text-xs text-left transition-colors ' +
              (isActive
                ? 'bg-primary-600 text-white'
                : 'text-gray-200 hover:bg-gray-700 hover:text-white')
            }
          >
            <span
              data-testid="label-sidebar-color"
              aria-hidden="true"
              className="w-3 h-3 rounded-sm shrink-0 border border-black/30"
              style={{ backgroundColor: m.color }}
            />
            <span className="flex-1 truncate">{m.name}</span>
            {shortcut && (
              <span
                aria-hidden="true"
                className={
                  'text-[10px] px-1 rounded shrink-0 ' +
                  (isActive ? 'bg-white/20 text-white' : 'bg-gray-700 text-gray-400')
                }
              >
                {shortcut}
              </span>
            )}
          </button>
        );
      })}

      {/* 키포인트(COCO-17) 순차 배치 가이드 — 배치 중일 때만 라벨 목록 아래에 표시.
          과거 캔버스 오버레이는 좁은 폭에서 잘려 폐기 → 항상-보이는 좌측 패널로 이동. */}
      <KeypointGuide placingIndex={keypointPlacingIndex} />
    </aside>
  );
}
