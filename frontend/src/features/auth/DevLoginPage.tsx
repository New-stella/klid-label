import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { ApiError } from '@/lib/api/errors';
import { Channel, Role } from '@/lib/api/types';

import { LOCAL_STORAGE_TOKEN_KEY } from './tokenIngress';

/**
 * DEV 빌드 전용 로컬 로그인 페이지.
 *
 * 운영 시나리오:
 *   관제서버 → localStorage[`klid-jwt-token`] = JWT → 저작도구 진입 (tokenIngress 가 인계)
 *
 * 로컬/개발 시나리오 (이 페이지):
 *   사용자 → role 선택 → BE `POST /api/v1/dev/tokens` 호출 →
 *   응답 토큰을 같은 localStorage 키에 저장 → /ingress 로 이동 →
 *   기존 SessionIngressPage 흐름 그대로 동작 (운영 시나리오와 1:1 재현)
 *
 * 보안:
 *   - 라우터(index.tsx)가 `import.meta.env.DEV` 가드로 prod 빌드에서 마운트 자체를 차단.
 *   - BE `/dev/tokens` 도 `@Profile("!prd")` 격리됨.
 *   - 응답 토큰 형식 검증은 SessionIngressPage 가 수행 — 여기서는 raw 저장만.
 */

type DevRole = Extract<Role, 'REVIEWER' | 'WORKER' | 'PORTAL_USER'>;

interface RolePreset {
  readonly role: DevRole;
  readonly channel: Channel;
  readonly defaultUserNo: string;
  readonly defaultName: string;
  readonly label: string;
}

// DevTokenService 기본값과 1:1 매핑 (사양 명시값)
const ROLE_PRESETS: readonly RolePreset[] = [
  {
    role: Role.REVIEWER,
    channel: Channel.INTERNAL,
    defaultUserNo: '1001',
    defaultName: '김검수',
    label: 'REVIEWER (1001, 김검수)',
  },
  {
    role: Role.WORKER,
    channel: Channel.INTERNAL,
    defaultUserNo: '2001',
    defaultName: '최라벨',
    label: 'WORKER (2001, 최라벨)',
  },
  {
    role: Role.PORTAL_USER,
    channel: Channel.PORTAL,
    defaultUserNo: '3001',
    defaultName: '홍길동',
    label: 'PORTAL_USER (3001, 홍길동)',
  },
];

interface DevTokenRequest {
  readonly role: DevRole;
  readonly channel: Channel;
  readonly userNo?: string;
  readonly name?: string;
  readonly expSeconds?: number;
}

interface DevTokenClaims {
  readonly sub?: string;
  readonly role?: string;
  readonly channel?: string;
  readonly name?: string;
}

interface DevTokenResponse {
  readonly token: string;
  readonly tokenType?: string;
  readonly expiresAt?: string;
  readonly claims?: DevTokenClaims;
  readonly authorizationHeader?: string;
}

const DEFAULT_EXP_SECONDS = 3600;

function presetFor(role: DevRole): RolePreset {
  const found = ROLE_PRESETS.find((p) => p.role === role);
  // ROLE_PRESETS 는 모든 DevRole 을 포함 — 타입상 unreachable
  if (!found) {
    throw new Error(`unknown role: ${role as string}`);
  }
  return found;
}

/**
 * 관제서버 stub 재현: 응답 claims 의 일부 필드를 localStorage 에 추가 저장.
 * 운영에서 관제서버가 같은 origin 에 두는 값들과 동등하게 맞춰 화면 회귀 폭을 줄인다.
 */
function persistControlServerStub(claims: DevTokenClaims | undefined): void {
  if (!claims) return;
  try {
    if (typeof claims.sub === 'string' && claims.sub.length > 0) {
      localStorage.setItem('klid-user-id', claims.sub);
    }
    if (typeof claims.name === 'string' && claims.name.length > 0) {
      localStorage.setItem('klid-user-nm', claims.name);
    }
    if (typeof claims.role === 'string' && claims.role.length > 0) {
      localStorage.setItem('klid-authority', claims.role);
    }
  } catch {
    // localStorage 비활성/쿼터 초과 등 — DEV 페이지의 부수 효과이므로 silently 무시
  }
}

export function DevLoginPage() {
  const navigate = useNavigate();
  const [role, setRole] = useState<DevRole>(Role.REVIEWER);
  const [userNo, setUserNo] = useState<string>('');
  const [expSeconds, setExpSeconds] = useState<string>(String(DEFAULT_EXP_SECONDS));
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const preset = presetFor(role);

  async function handleSubmit(e: FormEvent<HTMLFormElement>): Promise<void> {
    e.preventDefault();
    setErrorMessage(null);
    setSubmitting(true);

    const trimmedUserNo = userNo.trim();
    const parsedExp = Number.parseInt(expSeconds, 10);
    const expValid = Number.isFinite(parsedExp) && parsedExp > 0;

    const body: DevTokenRequest = {
      role,
      channel: preset.channel,
      ...(trimmedUserNo ? { userNo: trimmedUserNo } : {}),
      ...(expValid ? { expSeconds: parsedExp } : {}),
    };

    try {
      const res = await apiClient.post<DevTokenResponse>('/dev/tokens', body);
      const data = res.data;
      if (!data || typeof data.token !== 'string' || data.token.length === 0) {
        setErrorMessage('토큰 응답이 비어 있습니다.');
        return;
      }

      // 운영 시나리오와 동일 경로: 같은 origin 의 localStorage 에 저장
      try {
        localStorage.setItem(LOCAL_STORAGE_TOKEN_KEY, data.token);
      } catch {
        setErrorMessage('localStorage 에 토큰을 저장할 수 없습니다.');
        return;
      }
      persistControlServerStub(data.claims);

      // SessionIngressPage 가 localStorage 인계 흐름으로 토큰 검증·라우팅 수행
      navigate('/ingress', { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message ?? '토큰 발급 실패');
      } else if (err instanceof Error) {
        setErrorMessage(`BE 서버를 확인하세요 (${err.message})`);
      } else {
        setErrorMessage('알 수 없는 오류가 발생했습니다.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-neutral-50 p-4">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-md rounded-lg border border-neutral-200 bg-white p-6 shadow-sm"
      >
        <header className="mb-4">
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-semibold text-neutral-900">Dev Login</h1>
            <span className="rounded bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-800">
              DEV 빌드 전용
            </span>
          </div>
          <p className="mt-2 text-sm text-neutral-600">
            로컬·개발 환경에서 관제서버 없이 토큰을 발급합니다. 운영 배포에는 포함되지 않습니다.
          </p>
        </header>

        <fieldset className="mb-4">
          <legend className="mb-2 text-sm font-medium text-neutral-800">역할 선택</legend>
          <div className="flex flex-col gap-2">
            {ROLE_PRESETS.map((p) => (
              <label
                key={p.role}
                className="flex cursor-pointer items-center gap-2 rounded border border-neutral-200 px-3 py-2 hover:bg-neutral-50"
              >
                <input
                  type="radio"
                  name="role"
                  value={p.role}
                  checked={role === p.role}
                  onChange={() => setRole(p.role)}
                />
                <span className="text-sm text-neutral-900">{p.label}</span>
                <span className="ml-auto text-xs text-neutral-500">{p.channel}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="mb-4">
          <label
            htmlFor="dev-login-user-no"
            className="mb-1 block text-sm font-medium text-neutral-800"
          >
            userNo <span className="text-xs text-neutral-500">(선택)</span>
          </label>
          <input
            id="dev-login-user-no"
            type="text"
            value={userNo}
            onChange={(e) => setUserNo(e.target.value)}
            placeholder={preset.defaultUserNo}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
            autoComplete="off"
          />
          <p className="mt-1 text-xs text-neutral-500">
            비워두면 BE 기본값({preset.defaultUserNo}, {preset.defaultName})을 사용합니다.
          </p>
        </div>

        <div className="mb-4">
          <label
            htmlFor="dev-login-exp"
            className="mb-1 block text-sm font-medium text-neutral-800"
          >
            expSeconds <span className="text-xs text-neutral-500">(선택)</span>
          </label>
          <input
            id="dev-login-exp"
            type="number"
            min={1}
            value={expSeconds}
            onChange={(e) => setExpSeconds(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>

        {errorMessage !== null && (
          <div
            role="alert"
            aria-live="assertive"
            className="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
          >
            {errorMessage}
          </div>
        )}

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded bg-primary px-4 py-2 text-sm font-medium text-white shadow-sm disabled:cursor-not-allowed disabled:opacity-60"
        >
          {submitting ? '발급 중…' : '토큰 발급 + 진입'}
        </button>
      </form>
    </div>
  );
}
