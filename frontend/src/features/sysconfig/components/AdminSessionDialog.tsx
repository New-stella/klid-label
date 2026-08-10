import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { adminSessionSchema, type AdminSessionForm } from '../schemas';

interface Props {
  open: boolean;
  onClose: () => void;
  /** 인증 시도. 성공하면 true 를 돌려준다(그때 다이얼로그를 닫는다). */
  onSubmit: (adminPassword: string) => Promise<boolean>;
  isSubmitting: boolean;
  /** 서버가 준 실패 사유. */
  error?: string;
  /** 유효창 길이 안내(분). */
  ttlMinutesHint: number;
}

/**
 * R11 — 관리자 패스워드 재확인 다이얼로그.
 *
 * <h3>패스워드를 화면에 되돌려 보여주지 않는다</h3>
 * 입력은 `type="password"` 이고, 성공하든 실패하든 **닫힐 때 값을 지운다**. 실패 안내에도 입력값을
 * 싣지 않는다("입력하신 XXX 가 틀렸습니다" 같은 문구를 만들지 않는다).
 *
 * <h3>자동완성 저장을 유도하지 않는다</h3>
 * `autoComplete="off"` — 공유 패스워드를 개인 브라우저 자격증명 저장소에 남기지 않는다.
 */
export function AdminSessionDialog({
  open,
  onClose,
  onSubmit,
  isSubmitting,
  error,
  ttlMinutesHint,
}: Props) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AdminSessionForm>({
    resolver: zodResolver(adminSessionSchema),
    defaultValues: { adminPassword: '' },
  });

  // 열고 닫을 때마다 입력값을 지운다 — 메모리에 남겨 둘 이유가 없다.
  useEffect(() => {
    reset({ adminPassword: '' });
  }, [open, reset]);

  const submit = handleSubmit(async (values) => {
    const ok = await onSubmit(values.adminPassword);
    reset({ adminPassword: '' });
    if (ok) {
      onClose();
    }
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="sm"
      title="관리자 인증"
      description={`인증하면 ${ttlMinutesHint}분 동안 연동 서버 주소를 수정할 수 있습니다.`}
    >
      <form onSubmit={submit} noValidate className="space-y-4">
        <div className="space-y-2">
          <label
            className="block text-label font-medium text-gray-700"
            htmlFor="admin-session-password"
          >
            관리자 패스워드
          </label>
          <input
            id="admin-session-password"
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
          {error && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {error}
            </p>
          )}
        </div>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" size="sm" onClick={onClose}>
            취소
          </Button>
          <Button type="submit" variant="primary" size="sm" loading={isSubmitting}>
            인증
          </Button>
        </div>
      </form>
    </Modal>
  );
}
