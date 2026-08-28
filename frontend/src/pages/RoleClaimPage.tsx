import { useMemo, useState, type FormEvent } from 'react';

import { Alert } from '@/components/common/Alert';
import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Card, CardContent } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { useClaimRole } from '@/features/auth/hooks/useClaimRole';
import { resolveHandoffUser } from '@/features/auth/tokenIngress';
import { ApiError } from '@/lib/api/errors';

/**
 * 이 창구가 부여하는 역할 — **관리자 고정**이다. [@design ADR-055] [@design ROLE-004]
 *
 * 서버가 요청 바디의 역할 값을 읽지 않고 관리자를 부여하지만, 그 필드는 여전히 필수라 생략할 수
 * 없다. 그래서 **실제 부여될 역할과 같은 값**을 싣는다 — 다른 값을 실으면 서버 로그에 남는
 * 요청 역할과 실제 부여 역할이 갈려 감사 추적이 어긋난다.
 */
const BOOTSTRAP_ROLE = 'ADMIN' as const;

/**
 * 관리자 부트스트랩 화면 (`/role-claim`).
 *
 * 인증은 되었으나 role 클레임이 비어 있는 사용자가 관리자 공유 패스워드로 **본인을 최초 관리자로
 * 등록**한다. 사용자 마스터 행이 없으면 이 시점에 자동등록된다(관제 인계 표시 정보 동봉).
 *
 * <h3>★역할을 고르지 않는다 — 고를 것이 없다</h3>
 * 이 창구는 **관리자가 0명일 때만** 열리고 부여 역할은 관리자 고정이다. 관리자가 한 명이라도
 * 생기면 닫히며, 그 뒤의 역할 부여는 관리자가 사용자 관리 화면에서 한다.
 *
 * ⚠ **구 사양 폐기(2026-08-28 · ADR-055) — 되살리지 말 것**:
 * <ul>
 *   <li>*"작업자 / 검수자 중 하나를 고른다"* · *"2026-08-04 사용자 확정 — REVIEWER 자가부여
 *       개방(되돌리지 말 것)"* — 그 확정은 관리자 역할이 없던 시절의 것이고 ADR-055 가
 *       대체했다. 선택지를 되살리면 **사용자가 「작업자」를 골랐는데 관리자가 부여되어** 화면이
 *       거짓을 말한다(서버가 요청 값을 읽지 않는다).</li>
 *   <li>*"이 시스템에 ADMIN 역할은 존재하지 않으며 관리 권한은 전부 REVIEWER 에 통합돼 있다"* —
 *       **사실이 아니다.** 역할은 관리자·검수자·작업자·포털 회원 4종이고 관리자가 검수자
 *       권한을 계층으로 물려받는다.</li>
 *   <li>*"검수자에게 받은 패스워드로 역할을 부여받으세요"* — 이 창구가 열려 있는 시점에는
 *       **아직 관리자도 검수자도 없을 수 있다.** 패스워드는 배포 시 설정되는 공유 값이다.</li>
 * </ul>
 *
 * 보안 정책 (security.md / component.md):
 * - 패스워드 입력 필드는 `type="password"`, `autoComplete="new-password"` 로
 *   브라우저 자동완성/캐싱을 회피한다 (관리자 공유 패스워드의 단말 잔류 방지).
 * - `dangerouslySetInnerHTML` / `eval` 등 동적 코드 실행 일체 사용 안 함.
 * - 평문 패스워드를 절대 `console.log` 또는 sessionStorage 에 기록하지 않는다.
 */
export function RoleClaimPage() {
  const [adminPassword, setAdminPassword] = useState('');
  const [error, setError] = useState<ClaimErrorNotice | null>(null);

  const mutation = useClaimRole({
    onError: (err) => {
      setError(toUserNotice(err));
    },
    onSuccess: () => {
      // 성공 시 훅 내부에서 navigate. 추가 후처리 없음.
    },
  });

  const submitDisabled = useMemo(
    () => adminPassword.trim().length === 0 || mutation.isPending,
    [adminPassword, mutation.isPending],
  );

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (adminPassword.length === 0) return;
    setError(null);
    // 관제서버가 localStorage 로 인계한 표시용 사용자 정보를 동봉한다 — BE 가 사용자 마스터에
    // 자동등록한다. 인계값이 없으면 필드를 생략하며(선택 필드) 기존 값이 보존된다.
    mutation.mutate({ role: BOOTSTRAP_ROLE, adminPassword, ...resolveHandoffUser() });
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-50 px-4 py-10">
      <Card className="w-full max-w-md">
        <CardContent>
          <header className="mb-6">
            <h1 className="text-title-lg font-bold text-gray-900">관리자 등록</h1>
            {/*
              ⚠ 아래 필드 라벨 '관리자 패스워드'·`aria-label`·API 필드 `adminPassword` 는 역할
              호칭이 아니라 "공유 부트스트랩 패스워드"라는 계약·변수 축이므로 함께 바꾸지 말 것.

              ⚠ 구 문구 폐기(2026-08-28) — *"검수자에게 받은 패스워드로 역할을 부여받으세요."*
                이 창구가 열려 있다는 것은 **관리자가 아직 0명**이라는 뜻이고, 그 시점에는 안내해
                줄 검수자조차 없을 수 있다. 그 앞의 구 문구 *"관리자에게 받은…"* 도 같은 이유로
                한 번 폐기된 바 있는데, 그때 근거였던 *"이 시스템에 ADMIN 역할이 없다"* 는 서술이
                이번에 **거짓이 됐다**(역할 4종 · 관리자가 검수자를 계층으로 물려받는다).
            */}
            <p className="mt-1 text-body-md text-gray-600">
              이 시스템에는 아직 관리자가 없습니다. 배포 시 설정한 공유 패스워드로 본인을 최초
              관리자로 등록하세요.
            </p>
          </header>

          {/*
            ★역할을 고르는 자리가 없다 — 고를 것이 없기 때문이다. 부여 역할은 관리자 고정이고
            서버가 요청 값을 읽지 않는다. 선택지를 되살리면 사용자가 고른 값과 실제 부여 역할이
            갈려 화면이 거짓을 말한다. 대신 무슨 일이 일어나는지를 명시한다.
          */}
          {/* 아이콘은 `Alert` 가 variant 에서 도출해 스스로 그린다 — 여기서 따로 넣지 않는다
              (아이콘 종수 상한 가드가 있고, `ShieldAlert` 는 과거 `AlertTriangle` 로 통일하며
              걷어낸 아이콘이라 되들이면 그 가드가 죽는다). */}
          <Alert variant="info" title="관리자 권한이 부여됩니다">
            등록을 마치면 이 창구는 닫힙니다. 이후 다른 사용자의 역할은 관리자가 사용자 관리
            화면에서 지정합니다.
          </Alert>

          <form className="mt-5 space-y-5" onSubmit={handleSubmit} noValidate>
            <Field>
              <FieldLabel>관리자 패스워드</FieldLabel>
              <Input
                type="password"
                autoComplete="new-password"
                placeholder="배포 시 설정한 공유 패스워드를 입력하세요"
                value={adminPassword}
                onChange={(e) => setAdminPassword(e.target.value)}
                disabled={mutation.isPending}
                aria-label="관리자 패스워드"
              />
              {/*
                ⚠ 구 문구 폐기(2026-08-28) — *"검수자가 안내한 공유 패스워드입니다."* 안내해 줄
                  검수자가 아직 없을 수 있다(위 헤더 주석과 같은 이유). 값의 성격(역할과 무관한
                  단일 공유 패스워드)은 그대로다.
              */}
              <FieldDescription>
                배포 시 설정된 공유 패스워드입니다. 화면에는 표시되지 않습니다.
              </FieldDescription>
            </Field>

            {error && (
              <Alert variant="error" title={error.title}>
                {error.description}
              </Alert>
            )}

            <Button type="submit" fullWidth loading={mutation.isPending} disabled={submitDisabled}>
              관리자로 등록
            </Button>
          </form>
        </CardContent>
      </Card>
    </main>
  );
}

/**
 * 오류 안내 — 분류(제목) + 상세(본문). 시안 SCREEN-002 의 `.alert-title` / `.alert-text` 짝이다.
 *
 * 제목은 "무엇이 일어났는가", 본문은 "무엇을 하면 되는가"를 말한다. 한 줄로 합쳐 두면 사용자가
 * 원인과 행동을 같은 무게로 읽게 되어 어느 쪽이 지시인지 드러나지 않는다.
 */
interface ClaimErrorNotice {
  readonly title: string;
  readonly description: string;
}

/**
 * ApiError status 별 사용자 안내 매핑.
 * - 401: 관리자 패스워드 불일치
 * - 403: 부여 역할이 자가부여 화이트리스트 밖 (설정 이상 — 정상 동선에는 없다)
 * - 409: **창구가 닫혔다** — 서버 사유를 그대로 싣는다
 * - 429: rate limit 초과
 * - 400/기타: BE userMessage 또는 일반 메시지
 *
 * ★서버가 내려준 메시지(`err.userMessage`)는 <b>본문에 그대로</b> 싣는다. 상태코드로 분류할 수
 * 없는 사유는 서버만 알고 있으므로, 고정 문구로 덮으면 사용자가 실제 원인을 볼 수 없게 된다.
 *
 * 보안: 안내에는 스택트레이스/내부 경로/DB 스키마를 노출하지 않는다.
 */
function toUserNotice(err: unknown): ClaimErrorNotice {
  if (err instanceof ApiError) {
    if (err.status === 401) {
      // 시안 SCREEN-002 ③ 원문.
      return {
        title: '패스워드가 일치하지 않습니다',
        description: '관리자에게 받은 패스워드를 다시 확인해주세요.',
      };
    }
    if (err.status === 403) {
      // 부여 역할이 자가부여 화이트리스트에서 빠진 상태 — 정상 동선에서는 오지 않는다
      // (화면이 고르는 값이 아니라 서버가 고정한 부여 역할을 검사한다).
      return {
        title: '자가 부여할 수 없는 역할입니다',
        description: err.userMessage || '관리자에게 권한 부여를 요청하세요.',
      };
    }
    if (err.status === 409) {
      // ★사유를 화면이 지어내지 않는다. 이 코드에는 서로 다른 두 사유가 온다 —
      //   ①관리자가 이미 있어 창구가 닫혔다 ②내부 채널이 아니다. 상태코드만으로는 구분되지
      //   않으므로 서버가 보낸 문장을 그대로 싣는다.
      //   ⚠ 구 문구 폐기(2026-08-28) — *"이미 권한이 부여된 사용자입니다 / 새로고침 해주세요."*
      //     그 분기(역할 보유자 거절)는 서버에서 **제거**됐고, 지금 이 코드가 뜻하는 것은
      //     「창이 닫혔다」다. 고정 문구로 덮으면 사용자는 새로고침만 반복하게 되고 실제 사유
      //     (*"관리자에게 역할 부여를 요청하세요"*)를 영영 보지 못한다.
      return {
        title: '자가 등록 창구가 닫혀 있습니다',
        description:
          err.userMessage ||
          '이미 관리자가 등록되어 있습니다. 관리자에게 역할 부여를 요청하세요.',
      };
    }
    if (err.status === 429) {
      return {
        title: '시도 횟수가 제한을 초과했습니다',
        description: '잠시 후 다시 시도해주세요.',
      };
    }
    if (err.userMessage) {
      return { title: '권한 부여에 실패했습니다', description: err.userMessage };
    }
  }
  return {
    title: '권한 부여에 실패했습니다',
    description: '잠시 후 다시 시도해주세요.',
  };
}
