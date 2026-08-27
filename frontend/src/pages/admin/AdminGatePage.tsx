import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate } from 'react-router-dom';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { PageHeader } from '@/components/common/PageHeader';
import {
  ADMIN_SESSION_TTL_MINUTES_HINT,
  useAdminSessionWindow,
} from '@/features/adminSession/hooks/useAdminSessionWindow';
import { adminSessionSchema, type AdminSessionForm } from '@/features/adminSession/schema';
import { KRDS_FOCUS } from '@/lib/focusRing';
import {
  ADMIN_DEFAULT_PATH,
  type AdminGateLocationState,
} from '@/router/adminSessionGuard';

/**
 * 관리자 페이지 진입 화면(`/admin`). [@design SCREEN-040] [@design API-194] [@design ADR-046]
 *
 * <h3>이 화면은 확인만 맡는다</h3>
 * 관리 기능 자체는 담지 않는다 — 사용자 관리·연동 서버 주소·파일 업로드·패스워드 교체·위험
 * 액션이 그 기능을 담는다. 유효창 없이 그런 화면을 열려고 하면 그 화면 대신 이 화면이 뜨고,
 * 확인을 통과하면 <b>원래 가려던 화면으로 되돌려 보낸다</b>. 되돌아갈 곳을 알 수 없을 때만
 * 관리자 페이지 기본 화면으로 보낸다.
 *
 * <h3>여는 것은 역할이 아니라 창이다</h3>
 * 확인이 여는 것은 그 사람에게 잠깐 열리는 유효창이며 역할을 승격시키지 않는다. 검수자 권한은
 * 그대로 필요하고, 유효창은 인가를 대체하지 않고 가산된다. 조회만 하는 경로는 이 확인을 요구
 * 하지 않는다.
 *
 * <h3>실패 사유를 가르지 않는다</h3>
 * 패스워드 불일치·권한 없음·시도 초과를 구분해 알리면 그 응답이 공격자에게 상태를 알려주는
 * 신호가 된다. 실패 문구는 하나로 두고, 시도가 잦으면 제한된다는 사실만 덧붙인다 — 남은 시도
 * 횟수나 제한 기준은 알리지 않는다.
 *
 * <p>보안: 입력은 가려진 입력(`type="password"`)이고 자동완성 저장을 유도하지 않는다
 * (`autoComplete="off"`). 제출 직후 폼에서 값을 지우며 화면·기록 어디에도 남기지 않는다.
 */
export function AdminGatePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const session = useAdminSessionWindow();

  const from = (location.state as AdminGateLocationState | null)?.from;
  /**
   * 되돌아갈 곳. 열려던 관리 화면이 있으면 그곳, 없으면 관리자 페이지 기본 화면이다.
   *
   * ⚠ 진입 화면 자신으로는 되돌려 보내지 않는다 — 확인을 통과했는데 다시 확인 화면이 뜬다.
   */
  const returnTo = from && from !== '/admin' ? from : ADMIN_DEFAULT_PATH;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isValid },
  } = useForm<AdminSessionForm>({
    resolver: zodResolver(adminSessionSchema),
    defaultValues: { adminPassword: '' },
    mode: 'onChange',
  });

  // 이미 열려 있는 창을 들고 이 화면에 오면(주소 직접 입력 등) 곧바로 되돌려 보낸다 —
  // 열려 있는데 또 물어보면 사용자는 앞선 확인이 실패한 줄 안다.
  useEffect(() => {
    if (session.unlocked) {
      navigate(returnTo, { replace: true });
    }
  }, [session.unlocked, navigate, returnTo]);

  const submit = handleSubmit(async (values) => {
    const ok = await session.open(values.adminPassword);
    // 성공이든 실패든 입력값을 즉시 버린다 — 메모리에 남겨 둘 이유가 없다.
    reset({ adminPassword: '' });
    if (ok) {
      navigate(returnTo, { replace: true });
    }
  });

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        title="관리자 확인"
        description="관리 기능에 들어가기 전에 관리자 패스워드를 확인합니다."
      />

      <div className="flex max-w-2xl flex-col gap-4">
        {/* ① 진입 안내 — 무엇이 열리고 무엇이 열리지 않는지 폼보다 먼저 알린다. */}
        <div className="flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-5">
          {/* 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트가 이미 뜻을 다 말한다. */}
          <h2 className="text-title-sm font-semibold text-gray-800">
            이 확인으로 무엇이 열리나
          </h2>
          <p className="text-body text-gray-700">
            사용자 관리 · 연동 서버 주소 · 파일 업로드 · 관리자 패스워드 교체 · 위험 액션 같은
            관리 화면에 들어갈 수 있습니다. 조회만 하는 경로는 이 확인을 요구하지 않습니다.
          </p>
          <p className="text-caption text-gray-600">
            확인으로 열리는 것은 {ADMIN_SESSION_TTL_MINUTES_HINT}분 동안만 열리는 유효창이며
            역할이 바뀌는 것이 아닙니다. 검수자 권한은 그대로 필요합니다.
          </p>
          <p className="text-caption text-gray-600">
            돌아갈 화면은 이 화면으로 오기 직전에 열려던 관리 화면입니다. 그것을 알 수 없을 때만
            관리자 페이지의 기본 화면으로 보냅니다.
          </p>
        </div>

        {/* ② 관리자 패스워드 확인 */}
        <form
          onSubmit={submit}
          noValidate
          className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-5"
        >
          <div className="space-y-2">
            <label
              className="block text-label font-medium text-gray-700"
              htmlFor="admin-gate-password"
            >
              관리자 패스워드
            </label>
            <input
              id="admin-gate-password"
              type="password"
              autoComplete="off"
              aria-invalid={errors.adminPassword ? 'true' : 'false'}
              className={`w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-body ${KRDS_FOCUS}`}
              {...register('adminPassword')}
            />
            {errors.adminPassword && (
              <p className="flex items-center gap-1 text-caption text-danger" role="alert">
                <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                {errors.adminPassword.message}
              </p>
            )}
          </div>

          {/* ③ 확인 결과 안내 — 사유를 가르지 않는다. */}
          {session.error && (
            <Alert variant="error" title="관리자 확인에 실패했습니다">
              패스워드를 다시 확인해 주세요. 시도가 잦으면 잠시 동안 제한될 수 있습니다.
            </Alert>
          )}

          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => navigate(-1)}
              disabled={session.isOpening}
            >
              취소
            </Button>
            <Button
              type="submit"
              variant="primary"
              size="sm"
              loading={session.isOpening}
              disabled={!isValid || session.isOpening}
            >
              확인
            </Button>
          </div>
        </form>
      </div>
    </section>
  );
}
