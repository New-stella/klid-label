import { useMemo } from 'react';
import { Bot, Link2 } from 'lucide-react';

import { cn } from '@/lib/cn';
import { useLabelStore } from '@/stores/useLabelStore';

import type { Label } from '../types';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';
import { trackIdToColor } from '../utils/trackColor';

interface LabelPanelProps {
  labels: Label[];
}

/**
 * 라벨 트리 좌측 패널 — 클래스별 그룹 + 객체 카운트.
 *
 * Phase 5: 라벨 행에 좌측 4px 컬러 바(trackId 해시 기반) + trackId 가 있으면 `#{id}` 텍스트 표시.
 */
export function LabelPanel({ labels }: LabelPanelProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);

  const grouped = useMemo(() => {
    const map = new Map<string, Label[]>();
    for (const l of labels) {
      const arr = map.get(l.className) ?? [];
      arr.push(l);
      map.set(l.className, arr);
    }
    return Array.from(map.entries());
  }, [labels]);

  return (
    <aside
      className="flex h-full w-60 flex-col gap-2 overflow-y-auto border-r border-border bg-white p-3"
      aria-label="라벨 트리"
    >
      <h3 className="text-sub font-semibold text-primary">라벨</h3>
      {grouped.length === 0 && <p className="text-sub text-neutral">라벨 없음</p>}
      {grouped.map(([cls, items]) => (
        <div key={cls} className="flex flex-col gap-1">
          <div className="flex items-center justify-between text-sub text-primary">
            {/* 표시명은 공용 함수 — 마스터 등록명(cls) 그대로. */}
            <span>{resolveLabelDisplayName(cls)}</span>
            <span className="text-caption text-neutral">({items.length})</span>
          </div>
          <ul className="flex flex-col gap-0.5 pl-3">
            {items.map((item) => {
              const barColor = trackIdToColor(item.trackId);
              return (
                <li key={item.id} className="flex items-stretch gap-1">
                  <span
                    data-testid="label-color-bar"
                    aria-hidden="true"
                    className="inline-block w-1 shrink-0 rounded-sm"
                    style={{ backgroundColor: barColor }}
                  />
                  <button
                    type="button"
                    onClick={() => selectLabel(item.id)}
                    className={cn(
                      'w-full rounded px-2 py-0.5 text-left text-caption',
                      selectedId === item.id
                        ? 'bg-primary text-white'
                        : 'text-neutral hover:bg-bgLight',
                    )}
                  >
                    #{String(item.id ?? '').slice(0, 8)} · {item.shape?.type ?? '-'}
                    {/* 라벨 출처 표식 — 아이콘 라이브러리(보간=링크, 자동=봇).
                        낭독 내용을 유지하려고 각각 이름을 준다. 보간은 기존 `aria-label="보간 라벨"`
                        을 그대로 두고, 자동은 이모지였을 때 버튼 이름에 섞여 읽히던 몫을 대신한다. */}
                    {item.lblSrcCd === 'INTERPOLATED' ? (
                      <span className="ml-1 inline-flex align-middle" role="img" aria-label="보간 라벨">
                        <Link2 className="h-3 w-3" aria-hidden />
                      </span>
                    ) : (
                      item.source !== 'MANUAL' && (
                        <span className="ml-1 inline-flex align-middle" role="img" aria-label="자동 생성 라벨">
                          <Bot className="h-3 w-3" aria-hidden />
                        </span>
                      )
                    )}
                    {/* 트랙 번호 색은 **부모 버튼의 상태를 따라간다**.
                        · 비선택: 60단 — 이 버튼은 hover 에서 bg-bgLight(= 중립 50단)가 깔리는데
                          50단은 그 위에서 AA 미달이다(4.13:1). DS-001 do_rules(v8).
                        · 선택: 배경이 bg-primary(#256EF4)로 진해지므로 60단(#58616A)을 그대로 두면
                          파란 배경 위 회색 글씨(1.32:1)가 된다. 부모와 같은 text-white 로 간다.
                        ⚠ 자식이 색을 **강제**하기 때문에 생기는 문제다 — 부모가 상태별로 전경색을
                          바꾸는 곳에서 자식이 무조건 회색을 박으면 한쪽 상태가 반드시 깨진다. */}
                    {item.trackId && (
                      <span
                        data-testid="label-track-id"
                        className={cn(
                          'ml-1 text-[10px]',
                          selectedId === item.id ? 'text-white' : 'text-gray-600',
                        )}
                      >
                        #{item.trackId}
                      </span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </aside>
  );
}
