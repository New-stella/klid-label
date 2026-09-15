// ★ 토큰·킷 CSS 를 **맨 먼저** 불러온다 — 포털 부품 CSS 가 그보다 앞서 실리면 같은 무게의 규칙에서
//   킷 기본값에 져서 창 안 여백 등이 스토리북과 달라진다(스토리북도 킷 → 테마 → 부품 차례다).
import '@/styles/portalLook';

import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';

import { SectionTabs } from '@portal/components/custom';
import { PORTAL_CONTENT_TABS, resolveActivePortalTab } from '@/lib/portalNav';

import { usePortalFrameFit } from './usePortalFrameFit';

import '@portal/pages/workspace/authoring/AuthoringPage.css';
import './PortalFrameLayout.css';

/**
 * 포털 채널 틀 — 포털 저작도구 화면(KLID_Portal `AuthoringPage`)의 영역 안 짜임을 그대로 선다.
 *
 * 포털이 흰 카드 자리에 이 화면을 iframe 으로 띄운다. 머리글 · 좌측 메뉴는 포털 것이라 여기서 그리지 않는다.
 *   · 목록 면(내 작업 · 내 업로드 · 증강) — 위에 탭 줄, 아래 판
 *   · 편집 면(마킹 · 라벨링) — 탭 줄 없이 영역 전체를 쓴다
 * 폭은 포털 콘텐츠 폭(1200)으로 묶어 가운데 세운다 — 포털 카드 안(iframe)은 그보다 좁아 카드 폭을 그대로 쓰고,
 * 단독으로 넓은 창에 열었을 때만 묶음이 걸린다(디자인 입히기 전 원본과 같은 폭). 라벨링 편집기는 원래대로 창 전체를 쓴다.
 * 탭 목적지 목록은 `@/lib/portalNav` 가 단일 진실원이다.
 */
export function PortalFrameLayout() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const active = resolveActivePortalTab(pathname);
  const view = active ? 'tabs' : pathname.endsWith('/marking') ? 'marking' : 'labeling';
  const [slot, setSlot] = useState<HTMLDivElement | null>(null);

  // 포털 카드 높이 — 목록 면 · 편집 면 모두 이 화면 높이만큼 (2026-09-15 사용자 결정 · 처음엔 편집 면만 카드를 꽉 채웠다)
  usePortalFrameFit(slot, 'content', pathname);

  // 면을 옮기면 맨 위에서 시작한다 — 목록 아래쪽 줄에서 마킹 · 라벨링을 열면 스크롤이 내려간 채로 열려
  // 편집 면의 머리 줄 · 도구 줄이 위로 밀려 안 보였다. 같은 면 안의 주소 변화(쪽 넘김 등)는 건드리지 않는다.
  // 포털 카드 안에서 목록 면이 제 높이로 설 때는 iframe 이 스크롤하지 않는다 — 그때는 포털이 페이지를 올린다.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);

  return (
    <div ref={setSlot} className="klid-authoring-slot" data-state="mounted" data-view={view}>
      <div className="klid-authoring-frame" data-view={view}>
        {active ? (
          <SectionTabs
            items={PORTAL_CONTENT_TABS.map(({ key, label }) => ({ key, label }))}
            currentKey={active.key}
            onSelect={(key) => {
              const next = PORTAL_CONTENT_TABS.find((t) => t.key === key);
              if (next) navigate(next.path);
            }}
          >
            <Outlet />
          </SectionTabs>
        ) : (
          <Outlet />
        )}
      </div>
    </div>
  );
}
