import { Lock } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

const ROLE_LABEL: Record<string, string> = {
  REVIEWER: '검수자',
  WORKER: '작업자',
  PORTAL_USER: '포털',
};

// KRDS 예외: 범주 구분색(역할 구분, 데이터시각화 성격) — 토큰 획일화 제외(의도적 유지).
const ROLE_COLOR: Record<string, string> = {
  REVIEWER: 'bg-cyan-100 text-cyan-700',
  WORKER: 'bg-blue-100 text-blue-700',
  PORTAL_USER: 'bg-emerald-100 text-emerald-700',
};

/**
 * 접근 거부 안내 화면 (`/forbidden`).
 *
 * @design SCREEN-003 — 역할 또는 채널 기준 접근 제어를 통과하지 못했을 때 표시되는 공개 화면.
 * 서버를 호출하지 않고 클라이언트 상태의 `claims.role` 만 읽어 표시한다.
 */
export function ForbiddenPage() {
  const navigate = useNavigate();
  const role = useAuthStore((s) => s.claims?.role) ?? Role.WORKER;

  return (
    <main
      role="alert"
      className="flex min-h-full flex-col items-center justify-center px-6 py-20"
    >
      {/*
        @design SCREEN-003 `.auth-card` — 480px 카드 안에 내용을 담는다.
        구 구현은 카드 없이 페이지 한가운데 요소만 흩어 놓아 ①안내가 어디까지인지 경계가 없고
        ②버튼이 내용 폭과 무관한 auto 폭이었다.
      */}
      <div className="flex w-full max-w-[480px] flex-col items-center gap-6 rounded-lg border border-gray-200 bg-white p-8 text-center shadow-sm">
        <div className="flex flex-col items-center gap-4">
          {/* 잠금 배지 — 시안 `.lock-badge`(72px, danger-50 배경 / danger-200 테두리 /
              danger-600 아이콘). 구 구현의 `bg-danger/10`·`border-danger/20` 같은 불투명도
              합성 대신 팔레트 단계를 직접 쓴다(배경색이 달라져도 톤이 흔들리지 않는다). */}
          <div className="flex h-[72px] w-[72px] items-center justify-center rounded-full border border-danger-200 bg-danger-50">
            <Lock size={32} className="text-danger-600" />
          </div>
          {/* 제목 = ladder `title-lg`(22px/w700) — 구 `text-xl font-bold` 와 동일 크기·weight. */}
          <h1 className="text-title-lg font-bold text-gray-900">이 화면에 접근할 수 없습니다</h1>
          {/* 안내 본문 = ladder `body-md`(17px). 구 `max-w-xs`(320px)는 카드 폭보다 좁아
              한 줄이면 되는 문장을 2줄로 접었다 — 카드가 폭을 정하므로 상한을 두지 않는다. */}
          <p className="text-body-md text-gray-600">현재 역할로는 이 페이지에 접근 권한이 없습니다.</p>
        </div>

        <div className="flex w-full flex-col items-center gap-6">
          {/* '현재 역할' 은 테두리 상자 안에 둔다(시안 `.role-row`) — 안내문과 같은 평면에 두면
              읽는 사람이 그것도 안내 문장의 일부로 흘려보낸다. */}
          <p className="flex w-full items-center justify-center gap-2 rounded-md border border-gray-200 bg-gray-50 p-4">
            {/* 항목명 = ladder `caption`(14px), 역할 배지 = ladder `label`(14px). */}
            <span className="text-caption text-gray-700">현재 역할:</span>
            <span
              className={[
                'text-label font-semibold px-2.5 py-1 rounded-full',
                ROLE_COLOR[role] ?? 'bg-gray-100 text-gray-600',
              ].join(' ')}
            >
              {ROLE_LABEL[role] ?? role}
            </span>
          </p>
          {/* 카드 전폭 버튼(시안 `.btn-block`) — 이 화면의 유일한 액션이라 폭을 줄일 이유가 없다. */}
          <Button variant="primary" fullWidth onClick={() => navigate('/')}>
            대시보드로
          </Button>
        </div>
      </div>
    </main>
  );
}
