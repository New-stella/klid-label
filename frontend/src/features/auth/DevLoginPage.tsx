import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, Info } from 'lucide-react';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';
import { RadioCard } from '@/components/common/RadioCard';
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
  /** 선택 카드의 설명 줄 — @design SCREEN-004 `.radio-desc` 원문. */
  readonly description: string;
}

// DevTokenService 기본값과 1:1 매핑 (사양 명시값)
const ROLE_PRESETS: readonly RolePreset[] = [
  {
    role: Role.REVIEWER,
    channel: Channel.INTERNAL,
    defaultUserNo: '1001',
    defaultName: '김검수',
    label: 'REVIEWER (1001, 김검수)',
    description: '배정·검수 승인과 관리 화면을 담당하는 역할입니다.',
  },
  {
    role: Role.WORKER,
    channel: Channel.INTERNAL,
    defaultUserNo: '2001',
    defaultName: '최라벨',
    label: 'WORKER (2001, 최라벨)',
    description: '영상에 라벨을 만들고 수정해 검수를 요청하는 역할입니다.',
  },
  {
    role: Role.PORTAL_USER,
    channel: Channel.PORTAL,
    defaultUserNo: '3001',
    defaultName: '홍길동',
    label: 'PORTAL_USER (3001, 홍길동)',
    description: '포털에서 들어오는 외부 사용자 역할입니다.',
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

/**
 * 오류 안내 — 분류(제목) + 상세(본문). 시안 SCREEN-004 ③ 의 `.alert-title` / `.alert-text` 짝이다.
 *
 * 제목은 이 화면이 판단할 수 있는 <b>분류</b>이고, 본문은 서버·브라우저가 돌려준 <b>실제 원인</b>이다.
 * 원인을 고정 문구로 덮으면 개발자가 무엇 때문에 실패했는지 볼 수 없게 된다(이 화면의 존재 이유가 그 진단이다).
 */
interface DevLoginErrorNotice {
  readonly title: string;
  readonly description: string;
}

/** 시안 SCREEN-004 ③ 제목 원문. */
const ERROR_TITLE_ISSUE = '토큰을 발급하지 못했습니다';
/** 발급은 됐으나 브라우저 저장에서 막힌 경우 — 발급 실패로 뭉뚱그리면 원인 추적이 어긋난다. */
const ERROR_TITLE_PERSIST = '토큰을 저장하지 못했습니다';

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
  // 사양(SCREEN-004): expSeconds 는 선택 입력이며 placeholder=3600 — 값을 미리 채워두면
  // 사용자가 손대지 않아도 매 요청에 expSeconds=3600 이 명시 전송돼 "비워두면 BE 기본값"이라는
  // userNo 와 동일한 선택 입력 계약이 깨진다.
  const [expSeconds, setExpSeconds] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<DevLoginErrorNotice | null>(null);

  const preset = presetFor(role);

  async function handleSubmit(e: FormEvent<HTMLFormElement>): Promise<void> {
    e.preventDefault();
    setError(null);
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
        setError({ title: ERROR_TITLE_ISSUE, description: '토큰 응답이 비어 있습니다.' });
        return;
      }

      // 운영 시나리오와 동일 경로: 같은 origin 의 localStorage 에 저장
      try {
        localStorage.setItem(LOCAL_STORAGE_TOKEN_KEY, data.token);
      } catch {
        setError({
          title: ERROR_TITLE_PERSIST,
          description: 'localStorage 에 토큰을 저장할 수 없습니다.',
        });
        return;
      }
      persistControlServerStub(data.claims);

      // SessionIngressPage 가 localStorage 인계 흐름으로 토큰 검증·라우팅 수행
      navigate('/ingress', { replace: true });
    } catch (err) {
      // 서버·브라우저가 준 원인 문자열은 그대로 본문에 싣는다(제목만 분류를 말한다).
      if (err instanceof ApiError) {
        setError({ title: ERROR_TITLE_ISSUE, description: err.message ?? '토큰 발급 실패' });
      } else if (err instanceof Error) {
        setError({
          title: ERROR_TITLE_ISSUE,
          description: `BE 서버를 확인하세요 (${err.message})`,
        });
      } else {
        setError({ title: ERROR_TITLE_ISSUE, description: '알 수 없는 오류가 발생했습니다.' });
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
        <header className="mb-4 flex flex-col gap-3">
          <div className="flex items-center gap-2">
            <h1 className="text-title-lg font-semibold text-neutral-900">Dev Login</h1>
            {/* 경고 배지 — 시안 `.badge-warn`(warning-50 배경 + warning-700 글자). 구 구현의
                `bg-warning/10` 은 반투명이라 배경에 따라 톤이 흔들렸다. */}
            <span className="inline-flex items-center gap-1 rounded bg-warning-50 px-2 py-0.5 text-label font-semibold text-warning-700">
              <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              DEV 빌드 전용
            </span>
          </div>
          {/*
            @design SCREEN-004 `.dev-note` — 노란 정보 콜아웃. 구 구현은 평범한 회색 문단이라
            "이 화면은 운영에 없다"는 경고가 본문 안내와 같은 무게로 읽혔다.
          */}
          <div className="flex items-start gap-2 rounded-md border border-warning-200 bg-warning-50 p-4">
            <Info className="mt-0.5 h-4 w-4 shrink-0 text-warning-700" aria-hidden="true" />
            <p className="text-body-sm text-gray-800">
              로컬·개발 환경에서 관제서버 없이 토큰을 발급합니다. 운영 배포에는 포함되지 않습니다.
            </p>
          </div>
        </header>

        <fieldset className="mb-4">
          <legend className="mb-2 text-label font-medium text-neutral-800">역할 선택</legend>
          {/*
            @design SCREEN-004 `.radio-card` — 역할마다 설명 줄과 채널 칩을 갖는 선택 카드.
            구 구현은 한 줄짜리 라디오라 ①역할별 설명이 없고 ②선택 강조가 라디오 점뿐이었으며
            ③채널이 평문 회색 텍스트라 값인지 설명인지 구분되지 않았다.
            RadioCard 는 SCREEN-002(역할 클레임)와 공유하는 골격이다.
          */}
          <div className="flex flex-col gap-2">
            {ROLE_PRESETS.map((p) => (
              <RadioCard
                key={p.role}
                id={`dev-login-role-${p.role}`}
                name="role"
                value={p.role}
                title={p.label}
                description={p.description}
                checked={role === p.role}
                onChange={() => setRole(p.role)}
                trailing={
                  // 채널 칩 — INTERNAL 은 secondary 톤, PORTAL 은 중립 톤(시안 `.channel-chip`).
                  <span
                    className={[
                      'inline-flex items-center rounded px-2.5 py-0.5 text-label font-semibold',
                      p.channel === Channel.PORTAL
                        ? 'bg-gray-100 text-gray-800'
                        : 'bg-secondary-50 text-secondary-700',
                    ].join(' ')}
                  >
                    {p.channel}
                  </span>
                }
              />
            ))}
          </div>
        </fieldset>

        <div className="mb-4">
          <label
            htmlFor="dev-login-user-no"
            className="mb-1 block text-label font-medium text-neutral-800"
          >
            userNo <span className="text-caption text-neutral-500">(선택)</span>
          </label>
          <Input
            id="dev-login-user-no"
            type="text"
            value={userNo}
            onChange={(e) => setUserNo(e.target.value)}
            placeholder={preset.defaultUserNo}
            autoComplete="off"
          />
          <p className="mt-1 text-caption text-neutral-500">
            비워두면 BE 기본값({preset.defaultUserNo}, {preset.defaultName})을 사용합니다.
          </p>
        </div>

        <div className="mb-4">
          <label
            htmlFor="dev-login-exp"
            className="mb-1 block text-label font-medium text-neutral-800"
          >
            expSeconds <span className="text-caption text-neutral-500">(선택)</span>
          </label>
          <Input
            id="dev-login-exp"
            type="number"
            min={1}
            value={expSeconds}
            onChange={(e) => setExpSeconds(e.target.value)}
            placeholder={String(DEFAULT_EXP_SECONDS)}
          />
          <p className="mt-1 text-caption text-neutral-500">
            비워두면 BE 기본값({DEFAULT_EXP_SECONDS}초)을 사용합니다.
          </p>
        </div>

        {error !== null && (
          <Alert variant="error" aria-live="assertive" title={error.title} className="mb-4">
            {error.description}
          </Alert>
        )}

        {/* loading prop 대신 disabled 를 쓴다 — 진행 문구('발급 중…')를 그대로 유지하기 위함. */}
        <Button type="submit" variant="primary" fullWidth disabled={submitting}>
          {submitting ? '발급 중…' : '토큰 발급 + 진입'}
        </Button>
      </form>
    </div>
  );
}
