import { useMemo, useState, type FormEvent } from 'react';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { Radio } from '@/components/common/Radio';
import { useClaimRole } from '@/features/auth/hooks/useClaimRole';
import { ApiError } from '@/lib/api/errors';

/**
 * 자가 부여 가능한 역할 — BE `RoleClaimService.allowedClaimRoles()` 화이트리스트와 1:1.
 * REVIEWER(사실상 관리자)는 공유 패스워드만으로 부여될 수 없으며 기존 검수자가 부여한다(A-ISSUE-17).
 */
type ClaimableRole = 'WORKER';

/**
 * 권한 자가 부여 화면 (`/role-claim`).
 *
 * 인증은 되었으나 role 클레임이 비어 있는 사용자가 관리자 공유 패스워드와 함께
 * 본인에게 WORKER 역할을 부여한다.
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
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const mutation = useClaimRole({
    onError: (err) => {
      setErrorMessage(toUserMessage(err));
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
    setErrorMessage(null);
    mutation.mutate({ role, adminPassword });
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-50 px-4 py-10">
      <Card className="w-full max-w-md" padding="lg">
        <header className="mb-6">
          <h1 className="text-xl font-bold text-gray-900">권한 부여 필요</h1>
          <p className="mt-1 text-sm text-gray-500">
            관리자에게 받은 패스워드로 역할을 부여받으세요.
          </p>
        </header>

        <form className="space-y-5" onSubmit={handleSubmit} noValidate>
          <fieldset className="space-y-2">
            <legend className="text-body font-medium text-gray-700">역할 선택</legend>
            <div
              role="radiogroup"
              aria-label="역할"
              className="flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-3"
            >
              <Radio
                name="role"
                value="WORKER"
                label="작업자 (WORKER)"
                checked={role === 'WORKER'}
                onChange={() => setRole('WORKER')}
                disabled={mutation.isPending}
              />
            </div>
            <p className="text-sub text-gray-500">
              검수자 권한은 자가 부여할 수 없습니다. 기존 검수자에게 권한 부여를 요청하세요.
            </p>
          </fieldset>

          <Input
            label="관리자 패스워드"
            type="password"
            autoComplete="new-password"
            placeholder="관리자에게 받은 패스워드를 입력하세요"
            value={adminPassword}
            onChange={(e) => setAdminPassword(e.target.value)}
            disabled={mutation.isPending}
            aria-label="관리자 패스워드"
          />

          {errorMessage && (
            <p role="alert" className="text-sub text-danger">
              {errorMessage}
            </p>
          )}

          <Button
            type="submit"
            fullWidth
            loading={mutation.isPending}
            disabled={submitDisabled}
          >
            권한 부여 확인
          </Button>
        </form>
      </Card>
    </main>
  );
}

/**
 * ApiError status 별 사용자 메시지 매핑.
 * - 401: 관리자 패스워드 불일치
 * - 409: 이미 권한 보유 (새로고침 유도)
 * - 429: rate limit 초과
 * - 400/기타: BE userMessage 또는 일반 메시지
 *
 * 보안: 에러 메시지에는 스택트레이스/내부 경로/DB 스키마를 노출하지 않는다.
 */
function toUserMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) {
      return '관리자 패스워드가 일치하지 않습니다.';
    }
    if (err.status === 403) {
      return '해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요.';
    }
    if (err.status === 409) {
      return '이미 권한이 부여된 사용자입니다. 새로고침 해주세요.';
    }
    if (err.status === 429) {
      return '시도 횟수가 제한을 초과했습니다. 잠시 후 다시 시도해주세요.';
    }
    if (err.userMessage) return err.userMessage;
  }
  return '권한 부여에 실패했습니다. 잠시 후 다시 시도해주세요.';
}
