// PortalLayout — 외부 사용자(포털 채널) 전용 단순 레이아웃.
// - GNB 단순화: 제목 + 사용자 메뉴만 (포털 채널 빌드에서는 그것마저 숨긴다 — 아래 ★★)
// - LNB 없음
// - 본문 상단 이동 탭으로 목적지를 오간다 (아래 ★★★)
// - 모바일 친화 (Tailwind md:* 분기, WCAG 2.1 AA)

import { Link, Outlet } from 'react-router-dom';

import { PortalContentTabs } from '@/components/layout/PortalContentTabs';
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { portalShellAlign } from '@/lib/portalShellLayout';
import { useAuthStore } from '@/stores/useAuthStore';

// ★하단 푸터는 두지 않는다 (2026-08-18 사용자 확정 — 사양 SHELL-001·SHELL-002 `footer.enabled=false`).
//   노출 여부와 문안(근거법령·운영기관·문의처)이 아직 확정되지 않았고, 확정 전에 자리표시 문구
//   ([운영기관명]·[000-0000-0000]·[example@example.go.kr])를 내보내면 그 값이 실제 정보인 것처럼
//   읽힌다. `Footer` 컴포넌트는 재노출을 위해 **삭제하지 않고 남겨 두며**, 문안이 확정되면 여기에
//   다시 마운트한다. **푸터 부재는 결손이 아니라 이 결정의 결과다** — 정합 점검에서 누락으로
//   보고하지 말 것(회귀 가드: AppLayout.test.tsx · PortalLayout.test.tsx 의 푸터 미노출 단언).
//
// ★★머리 영역(`<header>`)은 포털 채널(`VITE_BUILD_CHANNEL=portal`) 빌드에서만 숨긴다
//   (2026-08-26, Module Federation 임베드 준비). 그 채널은 포털 Host 셸 안에 Remote 로
//   마운트될 산출물이라 Host 가 이미 자기 헤더(제목·사용자 메뉴)를 갖고 있고, 여기서도
//   렌더하면 한 화면에 머리 영역이 두 벌 겹친다. **`<header>` 마크업은 삭제하지 않는다** —
//   이 채널 분리 결정은 뒤집힐 수 있고, 되돌릴 때 포커스 표시(`KRDS_FOCUS`)·링크 접근성
//   처리까지 그대로 재현돼야 한다. 위 Footer 주석과 같은 관례("삭제 대신 조건부 렌더")를
//   따른다. 기본값(`control`, 환경변수 미설정)에서는 지금처럼 렌더된다 — 지금 동작 불변.
//
// ★★★좌측 주 메뉴를 두지 않는다 — Host 가 머리 영역과 좌측 주 메뉴를 **둘 다** 소유한다
//   (2026-09-02 사용자 확정, 구속 · 사양 SHELL-002 `sidenav.enabled=false`). 우리가 레일을
//   그리면 한 화면에 왼쪽 레일이 두 벌이 되어 Host 화면과 부딪힌다. 대신 목적지 셋을 **본문
//   상단 가로 탭**(`PortalContentTabs`)으로 오간다.
//   ⚠⚠ **이 축은 두 번 뒤집혔고 지금이 세 번째 확정이다.** 중간에 「저작도구가 좌측 메뉴를
//   그린다」로 뒤집힌 적이 있으나 그 결정은 설계에만 있었고 코드에 닿은 적이 없다. 지금 상태가
//   확정 사양과 같으므로 **레일을 만들지 말 것** — 부재는 결손이 아니라 이 결정의 결과다
//   (회귀 가드: PortalLayout.test.tsx 의 좌측 레일 미노출 단언).
//   ⚠ 로고·사용자 신원·역할 배지도 두지 않는다 — Host 머리 영역이 이미 보여 준다.
//
// ★★★★디자인 시스템은 DS-002(포털 채널)다 — 관제 채널(DS-001)과 **토큰 이름이 같고 값이
//   다르다**. 값은 산출 시점에 채널이 정하므로(`design-tokens/channel.js`) 이 파일은 이름만
//   참조한다. 이 셸이 책임지는 DS-002 축은 넷이다:
//     · 페이지 바탕 = 캔버스 도메인 토큰(#f4f6fa)
//     · 본문 자간 -0.5px 를 뿌리에 한 번
//     · 콘텐츠 최대폭 1200 + 좌우 거터 24 — **탭과 본문이 같은 정렬선을 공유한다**
//     · 세로 리듬 — 탭줄 다음 32, 페이지 아래 80
//   ★★**가로 정렬선은 채널마다 갈린다**(2026-09-15) — 임베드에서는 Host 슬롯이 이미 자기
//     여백을 갖고 있어 위 「최대폭 1200 + 거터 24」를 한 번 더 세우면 여백이 두 벌로 겹친다.
//     판정과 클래스 문자열의 단일 진실원은 `@/lib/portalShellLayout` 이다 — 이 파일도 탭도
//     그 함수에서 받아 간다(두 곳이 따로 들면 시작선이 어긋난다).
//   ⚠ 각 화면이 자기 최대폭을 따로 들고 있던 자리는 2026-09-15 에 걷어냈다 — 셸이 이미
//     정렬선을 세우므로 화면 뿌리는 `w-full` 만 갖는다. 화면에 `max-w-*` 를 다시 두지 말 것.
//     ★특히 **rem 기반 `max-w-*` 는 임베드에서 조용히 줄어든다** — Host 문서의 루트 글꼴이
//     10px(62.5%)이라 `max-w-6xl`(72rem)이 1152 가 아니라 **720px** 로 해석된다(실측). 그것이
//     업로드 화면만 좁게 가운데로 몰려 보이던 원인이었다. 폭이 필요하면 px 리터럴 토큰
//     (`max-w-wrap`)을 쓴다.
//
// [@design DS-002]

export function PortalLayout() {
  const claims = useAuthStore((s) => s.claims);
  const embedded = isPortalEmbedChannel();

  return (
    // 페이지 바탕은 DS-002 캔버스 토큰(#f4f6fa) — slate 스케일 밖의 도메인 토큰이라 별도 키다.
    // 본문 자간 -0.5px 는 이 뿌리에 **한 번만** 준다(사다리 각 칸에 되풀이하면 두 번 적용된다).
    // ★본문 글자색을 뿌리에서 명시한다 — `styles/global.css` 의 `body` 규칙이 관제 축 값
    //   (KRDS 웜 그레이)을 물고 있고 그 파일은 정적이라 채널로 갈리지 않는다. 명시하지 않으면
    //   포털 산출물의 글자색만 웜톤으로 남아 **다른 축의 값이 조용히 섞인다**.
    //   DS-002 색 사다리: 제목 900 / **본문 800** / 표 셀 700 / 설명 600 / 메타·라벨 500.
    <div
      className={cn(
        'flex flex-col text-gray-800 tracking-body',
        embedded
          ? // 임베드 — Host 카드(흰 바탕) 위에 얹히므로 «바탕을 흰색으로 맞춘다».
            // 높이도 세우지 않는다(`min-h-screen` 은 카드 안에서 화면 한 장을 강제해
            // 내용이 짧을 때 빈 띠를 만든다) — 높이는 내용이 정한다.
            'bg-white'
          : 'min-h-screen bg-canvas',
      )}
    >
      {!embedded && (
        <header className="sticky top-0 z-40 flex h-14 items-center justify-between border-b border-gray-200 bg-white px-4 md:px-6">
          <Link
            to="/portal"
            className={cn(
              // ⚠ 굵기 상한 600 — 이 시스템은 700 이상을 쓰지 않고 위계를 크기와 색으로 만든다.
              'rounded-md text-section-title font-semibold text-gray-900 hover:text-primary-600 transition-colors',
              KRDS_FOCUS,
            )}
          >
            AI 학습데이터 포털
          </Link>
          {/* ★포털 채널의 이름 조달원은 <인계 토큰 클레임 하나뿐>이다 — 이 채널은 `GET /v1/me`
              를 부르지 않는다(포털 토큰은 발급 시점에 역할이 확정돼 서버에 다시 묻지 않는다).
              그래서 관제 채널의 서버 이름 주입(@design SHELL-001)은 여기에 닿지 않으며, 그것이
              결함이 아니라 채널 차이다. 대체 표기도 그대로 남긴다. */}
          <span className="text-btn-label text-gray-700">{claims?.name ?? '사용자'}</span>
        </header>
      )}
      <main className="flex-1">
        {/* 본문 상단 이동 탭 — Host 가 좌측 주 메뉴를 소유하므로 목적지 이동은 여기서 한다.
            목적지 목록은 이 파일이 갖지 않는다(`@/lib/portalNav` 가 단일 진실원).
            몰입 편집 화면에서는 컴포넌트가 스스로 아무것도 그리지 않는다. [@design SHELL-002]
            ★탭은 아래 본문과 **같은 정렬선**(`portalShellAlign()`)을 스스로 받는다 — 그래야
            좌측 시작선이 어긋나지 않는다(그래서 본문 래퍼 «안»에 넣지 않는다). */}
        <PortalContentTabs />
        {/* 가로 정렬선은 `portalShellAlign()` 이 채널에 맞춰 준다(위 ★★ 참조) — 독립 앱은
            최대폭 1200 + 거터 24(좁은 폭에서는 16), 임베드는 슬롯 안쪽 폭을 그대로 채운다.
            여기서 더하는 것은 세로 리듬뿐 — 위 32(탭줄 다음) · 아래 80. */}
        <div className={cn(portalShellAlign(), 'pb-page-section pt-section')}>
          <Outlet />
        </div>
      </main>
    </div>
  );
}
