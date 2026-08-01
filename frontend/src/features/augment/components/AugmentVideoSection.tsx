import { useEffect, useMemo, useRef, useState } from 'react';

import { Tabs } from '@/components/common/Tabs';

import { buildItemTabLabels } from '../resultView';
import type { AugmentResult } from '../types';

import { AugmentResultPanel } from './AugmentResultPanel';

export interface AugmentVideoSectionProps {
  videoId: number;
  cctvName: string;
  results: AugmentResult[];
  /**
   * 항목 id → **잡 전체 항목 순번**(1-based). 탭 번호(#n)의 근거이며 출처는 응답의
   * `itemPage`/`itemSize` 다. 없으면(구 서버) 화면 순서대로 번호를 매기고 안내 문구가 그렇게 말한다.
   */
  itemOrdinals?: ReadonlyMap<number, number> | null;
  framePage: number;
  onFramePageChange: (page: number) => void;
}

/**
 * 영상 1건의 결과 섹션 — 항목 탭 + 선택 항목 패널.
 *
 * <h3>탭은 **항목 PK(id)** 로 키잉한다 (Critical)</h3>
 * 구 구현은 `type` 으로 키잉하고 `results.find(r => r.type === activeType)` 로 첫 건만 꺼냈다.
 * 같은 종류가 여러 건이면(중복 요청 허용 이후 정상 형상) 오래된 항목만 렌더되어 **최신 PENDING
 * 항목의 생성 조건도, 채택/반려 버튼도 도달할 수 없었다**. 게다가 같은 value 가 여러 탭에 쓰여
 * DOM id 가 중복되고 `aria-selected=true` 가 복수로 생겨 WCAG 2.1 AA 위반이었다.
 */
export function AugmentVideoSection({
  videoId,
  cctvName,
  results,
  itemOrdinals,
  framePage,
  onFramePageChange,
}: AugmentVideoSectionProps) {
  const tabItems = useMemo(() => {
    const labels = buildItemTabLabels(results, itemOrdinals);
    return results.map((r, i) => ({ value: String(r.id), label: labels[i] }));
  }, [results, itemOrdinals]);

  const [activeId, setActiveId] = useState<string>(() => String(results[0]?.id ?? ''));

  // 결과 목록이 바뀌어(항목 페이지 전환 등) 선택 항목이 사라지면 첫 항목으로 되돌린다.
  useEffect(() => {
    if (!results.some((r) => String(r.id) === activeId)) {
      setActiveId(String(results[0]?.id ?? ''));
    }
  }, [results, activeId]);

  // 항목이 실제로 바뀌면 프레임 페이지를 첫 페이지로 되돌린다.
  // 항목마다 총 쌍 수가 다르므로(예 1080P=15, 480P=10) 페이지를 유지한 채 총량이 작은 항목으로
  // 옮기면 BE 가 빈 슬라이스를 내려 그리드가 0장이 되고, 페이저 표시 조건(총량 > 12)도 거짓이라
  // 되돌아갈 컨트롤조차 없어 갇힌다. 마운트 시점에는 리셋하지 않는다(다른 영상 섹션이 보고 있는
  // 페이지를 "더 보기" 확장만으로 되돌리지 않기 위해 직전 값과 비교한다).
  const prevIdRef = useRef(activeId);
  useEffect(() => {
    if (prevIdRef.current === activeId) return;
    prevIdRef.current = activeId;
    onFramePageChange(0);
  }, [activeId, onFramePageChange]);

  const active = results.find((r) => String(r.id) === activeId);
  const hasDuplicateType =
    new Set(results.map((r) => r.type)).size !== results.length;

  return (
    <section
      aria-label={`영상 ${cctvName} 증강 결과`}
      data-testid={`augment-result-video-${videoId}`}
      className="rounded border border-border bg-white p-4"
    >
      <h2 className="mb-3 text-section-title text-primary">{cctvName}</h2>
      {/* "가장 최근" 의 근거는 번호가 아니라 BE 정렬(최신순)이므로 순서로 말한다.
          번호(#n)의 의미는 **근거가 있을 때만** 잡 전체 순번이라고 말한다 — 응답이 항목 축
          페이지 정보를 주지 않으면 잡 전체 순번을 계산할 수 없으므로 화면 순서라고 밝힌다. */}
      {hasDuplicateType && (
        <p
          className="mb-2 text-sub text-gray-500"
          data-testid={`augment-item-tab-note-${videoId}`}
        >
          같은 종류를 여러 번 요청한 결과는 각각 다른 탭입니다(왼쪽일수록 최근 요청).{' '}
          {itemOrdinals
            ? '번호(#)는 최근 요청순으로 매긴 전체 항목 순번입니다.'
            : '번호(#)는 화면에 보이는 순서대로 매긴 번호입니다.'}
        </p>
      )}
      <Tabs
        items={tabItems}
        value={activeId}
        onChange={setActiveId}
        ariaLabel="증강 결과 항목"
      >
        {active && (
          <AugmentResultPanel
            result={active}
            framePage={framePage}
            onFramePageChange={onFramePageChange}
          />
        )}
      </Tabs>
    </section>
  );
}
