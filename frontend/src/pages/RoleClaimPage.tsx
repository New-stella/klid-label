import { useMemo, useState, type FormEvent, type ReactNode } from 'react';

import { Alert } from '@/components/common/Alert';
import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Card, CardContent } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { Spinner } from '@/components/common/Spinner';
import { useClaimRole } from '@/features/auth/hooks/useClaimRole';
import { useRoleClaimAvailability } from '@/features/auth/hooks/useRoleClaimAvailability';
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
 * 관리자 등록 화면 (`/role-claim`). [@design SCREEN-002] [@design API-245] [@design AC-1098]
 *
 * 역할이 아직 비어 있는 채로 진입한 내부 채널 사용자를 받아, **관리자 등록 창구가 열려 있는지에
 * 따라 두 갈래로** 안내한다. 사용자 마스터 행이 없으면 이 시점에 자동등록된다(관제 인계 표시
 * 정보 동봉).
 *
 * <h3>★개폐를 <b>진입 시점에 먼저 묻는다</b> — 제출해 봐야 아는 것이 아니다</h3>
 * 진입하면 {@link useRoleClaimAvailability} 로 창구 개폐를 조회하고 그 답으로 두 모습 중 하나를
 * **처음부터** 그린다.
 * <ul>
 *   <li><b>열림</b>(관리자 0명) — 안내 헤더 + 부여 권한 안내 + 패스워드 폼. *"이 시스템에는 아직
 *       관리자가 없습니다"* 는 <b>이 상태에서만</b> 나타난다.</li>
 *   <li><b>닫힘</b>(관리자 실재) — 권한 요청 안내만. <b>패스워드 입력칸도 등록 버튼도 두지
 *       않는다</b> — 눌러도 409 만 돌아오는 자리는 안내가 아니라 함정이다.</li>
 * </ul>
 *
 * ⚠ **구 사양 폐기(2026-09-07 · SCREEN-002 v32) — 되살리지 말 것**: *"개폐는 제출 응답으로
 * 가른다"*. 그러면 관리자가 이미 있는 시스템에서도 *"아직 관리자가 없습니다"* 가 **무조건**
 * 렌더되어 화면이 거짓을 말하고, 사용자는 패스워드를 넣어 제출한 뒤에야 거절을 받는다(246 실측
 * 2026-09-07 — 관리자 9001 이 실재하는데 이 화면이 열려 있었다).
 *
 * <h3>★그래도 409 갈래는 남는다 — 사전 조회가 그것을 대신하지 못한다</h3>
 * 개폐는 **순간의 상태**다. 조회와 제출 사이에 다른 사람이 최초 관리자가 되면 창은 그 사이에
 * 닫히고 제출은 409 로 거절된다. 사전 조회는 그 갈래를 **없애는 수단이 아니라** 첫 화면이 사실과
 * 다른 안내를 하지 않게 하는 수단이다. 409 를 받으면 화면은 권한 요청 안내로 **전환**한다.
 *
 * <h3>★조회가 실패하면 이 화면(열림 모습)으로 떨어지지 않는다</h3>
 * 인증이 유효하지 않으면(401) 세션 만료를 알리고 상위 시스템 로그인으로 되돌아가며(HTTP 계층의
 * 401 처리가 그 이동을 수행한다), 그 밖의 조회 장애는 오류로 알린다. 어느 쪽도 *"아직 관리자가
 * 없습니다"* 로 바꾸지 않는다 — 그러면 오류가 사양으로 위장된다.
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
  /**
   * 제출이 「창 닫힘」으로 거절되어 화면이 권한 요청 안내로 전환된 상태. [@design SCREEN-002]
   *
   * ★사전 조회가 열림이라 답한 뒤에도 이 갈래가 온다(경합). 값은 **서버가 보낸 사유**를 담는다 —
   * 409 에는 서로 다른 두 사유가 오므로(창 닫힘 / 내부 채널 아님) 화면이 원인을 지어내지 않는다.
   */
  const [conflictNotice, setConflictNotice] = useState<ClaimErrorNotice | null>(null);

  const availability = useRoleClaimAvailability();

  const mutation = useClaimRole({
    onError: (err) => {
      const notice = toUserNotice(err);
      // ★409 = 창이 닫혔다. 폼을 남겨 두면 눌러도 거절만 돌아오는 자리가 된다 — 안내로 전환한다.
      if (err instanceof ApiError && err.status === 409) {
        setError(null);
        setConflictNotice(notice);
        return;
      }
      setError(notice);
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

  // ── 개폐 조회가 끝나기 전에는 어느 모습도 그리지 않는다 ──────────────────────────
  //  ★열림 모습을 먼저 그려 두고 닫힘이면 지우는 방식은 쓰지 않는다. 그 한 프레임 동안 화면이
  //    *"아직 관리자가 없습니다"* 라고 말하게 되는데, 그것이 바로 이 화면이 고치려는 결함이다.
  if (availability.isPending) {
    return (
      <Shell>
        <div
          role="status"
          aria-live="polite"
          className="flex flex-col items-center gap-3 py-6 text-center"
        >
          <div aria-hidden="true">
            <Spinner size="lg" />
          </div>
          <p className="text-body-lg text-gray-900">등록 창구 상태를 확인하는 중</p>
        </div>
      </Shell>
    );
  }

  // ── 조회 실패: 인증 실패든 조회 장애든 「관리자가 없습니다」로 바꾸지 않는다 ──────────
  if (availability.isError) {
    const notice = toAvailabilityNotice(availability.error);
    return (
      <Shell>
        <Alert variant="error" aria-live="assertive" title={notice.title}>
          {notice.description}
        </Alert>
      </Shell>
    );
  }

  // ── 닫힘: 권한 요청 안내만. 등록 수단을 두지 않는다 ────────────────────────────
  const closed = conflictNotice !== null || availability.data?.available !== true;
  if (closed) {
    return (
      <Shell>
        <ClosedNotice notice={conflictNotice} />
      </Shell>
    );
  }

  // ── 열림: 최초 관리자 등록 폼 ────────────────────────────────────────────
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
 * 세 모습(로딩·오류·닫힘·열림)이 공유하는 카드 껍데기.
 *
 * 껍데기를 공유해야 상태가 바뀔 때 화면이 통째로 튀지 않는다. 열림 모습만 이 컴포넌트를 쓰지
 * 않는데, 그쪽은 폼까지 포함한 기존 마크업을 한 글자도 건드리지 않기 위해서다.
 */
function Shell({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-50 px-4 py-10">
      <Card className="w-full max-w-md">
        <CardContent>{children}</CardContent>
      </Card>
    </main>
  );
}

/**
 * 창구가 닫혀 있을 때 보이는 「권한 요청 안내」. [@design SCREEN-002]
 *
 * ★<b>패스워드 입력칸도 등록 버튼도 두지 않는다.</b> 최초 관리자 등록은 이미 성립하지 않아
 * 제출해도 거절만 돌아오기 때문이다 — 닫힌 상태에 등록 수단을 남겨 두면 안내가 아니라 함정이다.
 *
 * @param notice 제출이 409 로 거절되어 이 안내로 전환된 경우의 <b>서버가 보낸 사유</b>.
 *   진입 시점 조회로 닫힘을 안 경우에는 `null` 이며, 그때는 사유를 우리가 안다(관리자가 실재한다).
 *   ★409 에는 서로 다른 두 사유가 오므로(창 닫힘 / 내부 채널 아님) 그 경우에는 원인을 지어내지
 *   않고 서버 문장을 그대로 싣는다.
 */
function ClosedNotice({ notice }: { notice: ClaimErrorNotice | null }) {
  const title = notice?.title ?? '이미 관리자가 있어 최초 관리자 등록 창구가 닫혀 있습니다';
  const description =
    notice?.description ??
    '아직 권한이 없는 계정입니다. 관리자에게 권한을 요청하세요. 권한이 부여되면 다시 진입할 수 있습니다.';
  return (
    <>
      <header className="mb-6">
        <h1 className="text-title-lg font-bold text-gray-900">권한 요청 안내</h1>
      </header>
      {/* 강조 수준은 경고가 아니라 정보다 — 막는 자리가 아니라 알리는 자리다(SCREEN-002). */}
      <Alert variant="info" aria-live="polite" title={title}>
        {description}
      </Alert>
      <p className="mt-4 text-body-md text-gray-600">
        역할 부여는 관리자가 사용자 관리 화면에서 지정합니다.
      </p>
    </>
  );
}

/**
 * 개폐 조회 실패의 사용자 안내. [@design SCREEN-002] [@design SEQ-034]
 *
 * ★두 갈래를 갈라야 한다 — <b>실패를 「역할 없음」으로 뭉개면 오류가 사양으로 위장된다</b>.
 * - <b>401</b>: 세션이 유효하지 않다. HTTP 계층의 401 처리가 토큰을 비우고 상위 시스템 로그인으로
 *   되돌린다(`lib/api/client`) — 여기서는 그 사실을 사람에게 알리기만 한다. 이동을 여기서 한 번
 *   더 걸면 그 처리와 경쟁해 어느 쪽이 이기는지 정해지지 않는다.
 * - <b>그 밖</b>(5xx·네트워크): 조회 장애다. 서버 문장이 있으면 그대로 싣는다.
 */
function toAvailabilityNotice(err: unknown): ClaimErrorNotice {
  if (err instanceof ApiError && err.status === 401) {
    return {
      title: '세션이 만료되었습니다',
      description: '상위 시스템에서 다시 접근해주세요.',
    };
  }
  const serverMessage = err instanceof ApiError ? err.userMessage : '';
  return {
    title: '등록 창구 상태를 확인할 수 없습니다',
    description: serverMessage || '잠시 후 다시 시도해주세요.',
  };
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
        description: '배포 시 설정된 공유 패스워드를 다시 확인해주세요.',
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
      //
      // ⚠ ★<b>제목은 상수라 ②에서는 엄밀히 참이 아니다</b> — 그런데도 그대로 두는 이유가 있다.
      //   ㉠ 이 문구는 `SCREEN-002` v32 사양이 소유한다. 코드가 임의로 바꾸면 사양과 갈린다.
      //   ㉡ <b>②의 도달 경로가 두 겹으로 닫혀 있다</b>(실측):
      //      · 진입 시 개폐 조회(`GET /v1/auth/role-claim/availability`)가 내부 채널이 아니면
      //        **먼저 409** 로 막는다 → 이 화면은 조회 실패 모습으로 떨어져 <b>폼 자체가 그려지지
      //        않는다</b>. 폼이 없으니 이 제출 갈래에 닿을 수 없다.
      //      · `router/guards.tsx` 의 `RoleGuard` 도 `claims.channel === 'INTERNAL'` 일 때만
      //        `/role-claim` 으로 보낸다.
      //   즉 지금 형상에서 이 자리에 실제로 오는 409 는 ①뿐이라 제목이 거짓이 되지 않는다.
      //
      // ★<b>그 전제가 깨지면 제목이 곧바로 거짓이 된다.</b> 위 두 게이트 중 <b>하나라도</b> 열면
      //   (개폐 조회의 채널 게이트 완화 · 가드의 채널 조건 제거 · 포털 채널에서 이 화면 노출)
      //   채널 불일치 사용자가 폼을 보게 되고, 그때 화면은 *"자가 등록 창구가 닫혀 있습니다"* 라고
      //   <b>원인을 지어낸다</b>(본문의 서버 문장과도 어긋난다). 게이트를 여는 사람이 이 자리를
      //   함께 고쳐야 한다 — 제목도 서버 문장에서 받거나 사유별로 갈라야 한다.
      //   ⚠ 그 변경은 `SCREEN-002` 사양 수정이 <b>먼저</b>다(코드가 앞서면 진실원이 무너진다).
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
