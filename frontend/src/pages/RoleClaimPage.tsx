import { useMemo, useState, type FormEvent } from 'react';

import { Alert } from '@/components/common/Alert';
import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Card, CardContent } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { RadioCard } from '@/components/common/RadioCard';
import { useClaimRole } from '@/features/auth/hooks/useClaimRole';
import { resolveHandoffUser } from '@/features/auth/tokenIngress';
import { ApiError } from '@/lib/api/errors';

/**
 * 자가 부여 가능한 역할 — BE `RoleClaimService.allowedClaimRoles()` 화이트리스트와 1:1.
 *
 * ★2026-08-04 사용자 확정 — REVIEWER 자가부여 개방(되돌리지 말 것). 구 정책은 WORKER 단일이었고
 * "기존 검수자가 REVIEWER 를 부여한다"를 전제했는데, 신규 설치에는 그 검수자가 없어 최초 부트스트랩
 * 경로가 없었다. 잔여 위험(관리자 패스워드 유출 = 전권)은 사용자가 인지·수용했다.
 */
type ClaimableRole = 'WORKER' | 'REVIEWER';

/**
 * 권한 자가 부여 화면 (`/role-claim`).
 *
 * 인증은 되었으나 role 클레임이 비어 있는 사용자가 관리자 공유 패스워드와 함께
 * 본인에게 WORKER 또는 REVIEWER 역할을 부여한다. 사용자 마스터 행이 없으면 이 시점에
 * 자동등록된다(관제 인계 표시 정보 동봉).
 *
 * 보안 정책 (security.md / component.md):
 * - 패스워드 입력 필드는 `type="password"`, `autoComplete="new-password"` 로
 *   브라우저 자동완성/캐싱을 회피한다 (관리자 공유 패스워드의 단말 잔류 방지).
 * - `dangerouslySetInnerHTML` / `eval` 등 동적 코드 실행 일체 사용 안 함.
 * - 평문 패스워드를 절대 `console.log` 또는 sessionStorage 에 기록하지 않는다.
 */
export function RoleClaimPage() {
  const [role, setRole] = useState<ClaimableRole | ''>('');
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
    () => role === '' || adminPassword.trim().length === 0 || mutation.isPending,
    [role, adminPassword, mutation.isPending],
  );

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (role === '' || adminPassword.length === 0) return;
    setError(null);
    // 관제서버가 localStorage 로 인계한 표시용 사용자 정보를 동봉한다 — BE 가 사용자 마스터에
    // 자동등록한다. 인계값이 없으면 필드를 생략하며(선택 필드) 기존 값이 보존된다.
    mutation.mutate({ role, adminPassword, ...resolveHandoffUser() });
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-50 px-4 py-10">
      <Card className="w-full max-w-md">
        <CardContent>
          <header className="mb-6">
            <h1 className="text-title-lg font-bold text-gray-900">권한 부여 필요</h1>
            {/*
              ★역할 호칭은 '검수자'다(@design SCREEN-002 본문 원문).
              이 시스템에 ADMIN 역할은 존재하지 않으며 관리 권한은 전부 REVIEWER 에 통합돼 있다 —
              구 문구 '관리자에게 받은…'은 화면에 없는 역할을 가리켜, 사용자가 누구에게 요청해야
              할지 알 수 없게 만들었다.
              ⚠ 아래 필드 라벨 '관리자 패스워드'·`aria-label`·API 필드 `adminPassword` 는 역할
              호칭이 아니라 "공유 부트스트랩 패스워드"라는 계약·변수 축이므로 함께 바꾸지 말 것.
            */}
            <p className="mt-1 text-body-md text-gray-600">
              검수자에게 받은 패스워드로 역할을 부여받으세요.
            </p>
          </header>

          <form className="space-y-5" onSubmit={handleSubmit} noValidate>
            <fieldset className="space-y-2">
              <legend className="text-body font-medium text-gray-700">역할 선택</legend>
              {/*
                ★화면에 보이는 문구는 한글 호칭뿐이다(@design SCREEN-002). 서버 코드값
                (WORKER / REVIEWER)은 `value` 와 전송 payload 에만 있고 사람에게 노출하지 않는다 —
                코드값은 내부 계약이라 화면 문구로 새면 사용자가 읽을 이유가 없는 정보가 된다.
                ⚠ `value` 와 `setRole` 인자는 서버 계약이므로 절대 한글로 바꾸지 말 것.

                각 옵션은 설명을 곁들인 선택 카드다(확정 시안 `.radio-card`) — 구 구현은 테두리
                상자 안의 라디오 2줄이라 역할별 책임 차이를 화면에서 알 수 없었고, 그 설명을
                묶음 아래 한 문장으로만 두어 어느 역할의 이야기인지 이어지지 않았다.
              */}
              <div role="radiogroup" aria-label="역할" className="flex flex-col gap-2">
                <RadioCard
                  id="role-claim-worker"
                  name="role"
                  value="WORKER"
                  title="작업자"
                  description="영상에 라벨을 만들고 수정해 검수를 요청합니다."
                  checked={role === 'WORKER'}
                  onChange={() => setRole('WORKER')}
                  disabled={mutation.isPending}
                />
                <RadioCard
                  id="role-claim-reviewer"
                  name="role"
                  value="REVIEWER"
                  title="검수자"
                  description="작업 배정과 검수 승인·반려, 사용자·시스템 설정을 담당합니다."
                  checked={role === 'REVIEWER'}
                  onChange={() => setRole('REVIEWER')}
                  disabled={mutation.isPending}
                />
              </div>
            </fieldset>

            <Field>
              <FieldLabel>관리자 패스워드</FieldLabel>
              <Input
                type="password"
                autoComplete="new-password"
                placeholder="검수자에게 받은 패스워드를 입력하세요"
                value={adminPassword}
                onChange={(e) => setAdminPassword(e.target.value)}
                disabled={mutation.isPending}
                aria-label="관리자 패스워드"
              />
              {/*
                시안의 패스워드 도움말 자리. 시안 원문은 "역할별로 관리자가 따로 안내한
                패스워드입니다"였으나 그대로 쓰지 않는다 — ①이 값은 **역할과 무관한 단일 공유
                패스워드**이고(사양 본문 "관리자 공유 패스워드") ②'관리자'는 이 시스템에 없는
                역할이다. 두 사실에 맞춰 옮겨 적었다.
              */}
              <FieldDescription>
                검수자가 안내한 공유 패스워드입니다. 화면에는 표시되지 않습니다.
              </FieldDescription>
            </Field>

            {error && (
              <Alert variant="error" title={error.title}>
                {error.description}
              </Alert>
            )}

            <Button type="submit" fullWidth loading={mutation.isPending} disabled={submitDisabled}>
              권한 부여 확인
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
 * - 403: 화이트리스트 밖 역할
 * - 409: 이미 권한 보유 (새로고침 유도)
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
      // 화이트리스트 밖 역할 — 현재 UI 는 WORKER/REVIEWER 만 노출하므로 정상 동선에서는 오지 않는다.
      return {
        title: '자가 부여할 수 없는 역할입니다',
        description: '검수자에게 권한 부여를 요청하세요.',
      };
    }
    if (err.status === 409) {
      return {
        title: '이미 권한이 부여된 사용자입니다',
        description: '새로고침 해주세요.',
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
