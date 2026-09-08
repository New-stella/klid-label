/**
 * 포털 채널 구역 머리 — 제목 · 한 줄 설명 · 건수 · 구역 조작.
 *
 * <h3>왜 페이지 제목(`h1`)이 아닌가</h3>
 * 포털 채널의 저작도구 화면은 Host 화면 **안**에서 실행된다. 서비스 이름은 Host 머리 영역이,
 * 화면 이름은 본문 상단 이동 탭의 활성 항목이 이미 말한다 — 여기서 다시 쓰면 같은 말을 두
 * 번 하게 되고, 이동 탭과 제목이 서로를 흉내 내는 것으로 읽힌다. 그래서 이 부품은 **구역
 * 제목(`h2`)** 만 갖는다. [@design SHELL-002]
 *
 * <h3>설명 한 줄은 장식이 아니다</h3>
 * 목록만 놓인 화면은 «여기 실리는 것이 무엇인지» 를 스스로 말하지 못한다. 특히 내 작업 목록은
 * **두 출처가 한 목록에 섞여** 있어(내가 올린 자산 · 내가 손댄 데이터마트 영상) 그 사실을 한 줄로
 * 알려 두지 않으면 이용자가 «왜 이 영상이 여기 있지» 를 행마다 되묻는다.
 *
 * <h3>건수와 조작은 다른 축이라 자리를 나눈다</h3>
 * `count` 는 제목을 보조하는 **글**(조작 아님)이고 `action` 은 구역 전체에 걸리는 **조작 하나**다.
 * 행별 조작은 행이 갖는다 — 여기 올리면 어느 행에 걸리는지 알 수 없다.
 *
 * @design DS-002
 * @design SCREEN-028
 * @design SCREEN-044
 */

import type { ReactNode } from 'react';

import { cn } from '@/lib/cn';

interface PortalSectionHeadProps {
  /** `aria-labelledby` 로 구역과 잇는 제목 id. */
  id: string;
  title: string;
  /** 여기 실리는 것이 무엇인지 한 줄. */
  lead?: ReactNode;
  /** 건수·범위 등 제목을 보조하는 글. */
  count?: ReactNode;
  /** 구역 전체에 걸리는 조작 하나. */
  action?: ReactNode;
  className?: string;
}

export function PortalSectionHead({
  id,
  title,
  lead,
  count,
  action,
  className,
}: PortalSectionHeadProps) {
  return (
    <div className={cn('flex flex-wrap items-start justify-between gap-in-component', className)}>
      <div className="flex min-w-0 flex-col gap-tight">
        <div className="flex flex-wrap items-baseline gap-inline">
          {/* 굵기 상한 600 — 위계는 크기와 색이 만든다. */}
          <h2 id={id} className="text-title-md text-gray-900">
            {title}
          </h2>
          {count !== undefined && (
            <span className="text-body-sm tabular-nums text-gray-500">{count}</span>
          )}
        </div>
        {lead !== undefined && (
          <p className="max-w-prose text-pretty text-body-sm text-gray-600">{lead}</p>
        )}
      </div>
      {action}
    </div>
  );
}
