import { Link } from 'react-router-dom';
import { Video } from 'lucide-react';

import { cn } from '@/lib/cn';
import { CurrentRoleBadge } from '@/components/common/CurrentRoleBadge';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * mock 정합 GNB (h-14, fixed top).
 * 좌측: 로고 + 제목, 우측: 역할 라벨 + 사용자 아바타.
 *
 * SSO 채널이라 역할 변경은 불가 — mock의 RoleSwitcher 대신 read-only 표시.
 *
 * ★역할 배지는 `CurrentRoleBadge` 가 그린다 — 접근 거부 화면과 **같은 컴포넌트**다.
 *   구 구현은 역할이 없으면 `?? Role.WORKER` 로 작업자를 채워, 역할을 아직 받지 못한 사람에게
 *   사실과 다른 역할을 보여줬다(같은 결함이 접근 거부 화면에도 그대로 복제돼 있었다).
 *   지금은 역할이 없으면 **미배정**으로 보인다. [@design SHELL-001]
 */
export function Gnb() {
  const claims = useAuthStore((s) => s.claims);
  // ★이름의 <진실원은 서버 응답>(`GET /v1/me`)이고 인계 토큰의 이름 클레임은 보조다
  //   (@design SHELL-001 · @design UI-035). 진입 처리가 서버 이름을 `claims.name` 에 주입하므로
  //   여기서 읽는 값이 곧 「서버가 아는 이름 → 토큰 이름」 순서의 결과다.
  //
  //   ⚠ 대체 표기('사용자')는 **남긴다** — 두 조달원 모두에서 이름을 얻지 못하는 경우가 실제로
  //     있다. 고친 것은 「서버가 아는데도 대체 표기로 떨어지던」 원인(주입 누락)이지 이 폴백이
  //     아니다. 폴백을 지우면 이름 없는 세션에서 빈 헤더가 된다.
  const name = claims?.name ?? '사용자';
  const initials = name.slice(0, 1);

  return (
    <header className="fixed top-0 left-0 right-0 z-40 h-14 bg-white border-b border-gray-200 flex items-center px-4">
      {/* Left: Logo */}
      <div className="flex items-center gap-2.5 w-60 shrink-0">
        <Link to="/dashboard" className={cn('flex items-center gap-2.5 rounded-md', KRDS_FOCUS)}>
          <span className="flex items-center justify-center w-8 h-8 rounded-lg bg-primary-600">
            <Video size={16} className="text-white" aria-hidden />
          </span>
          {/* 서비스명 = ladder `title-sm`(17px). 크기는 구 `text-sm` 과 동일하고 weight 는 font-bold 가 유지. */}
          <span className="font-bold text-gray-900 text-title-sm leading-tight">
            학습데이터 저작도구
          </span>
        </Link>
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Right: Role label + User */}
      <div className="flex items-center gap-3">
        {/* 역할 배지 = ladder `label`(14px). 크기는 구 `text-xs` 와 동일. */}
        <CurrentRoleBadge role={claims?.role} />
        <div className="flex items-center gap-2 pl-3 border-l border-gray-200">
          {/* 이니셜 모노그램 = 제목도 라벨도 아니라 크기를 보존하는 `body-md`(17px). weight 는 font-bold 유지. */}
          <div className="flex items-center justify-center w-8 h-8 rounded-full bg-primary-100 text-primary-700 text-body-md font-bold shrink-0">
            {initials}
          </div>
          <div className="flex flex-col leading-tight">
            {/* 사용자명 = ladder `body-md`(17px). 크기는 구 `text-sm` 과 동일. */}
            <span className="text-body-md font-medium text-gray-700">{name}</span>
          </div>
        </div>
      </div>
    </header>
  );
}
