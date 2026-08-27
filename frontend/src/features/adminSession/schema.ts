import { z } from 'zod';

/**
 * 관리자 패스워드 입력 검증 — 유효창 개시·교체 폼이 공유한다. [@design API-194] [@design API-223]
 *
 * 길이 범위는 BE `AdminSessionRequest` 의 `@Size(min = 4, max = 100)` 과 같다. 화면이 먼저
 * 걸러도 서버가 다시 본다(이중 방어).
 */
export const adminSessionSchema = z.object({
  adminPassword: z
    .string()
    .min(4, '관리자 패스워드를 입력해주세요')
    .max(100, '관리자 패스워드는 100자를 초과할 수 없습니다'),
});

export type AdminSessionForm = z.infer<typeof adminSessionSchema>;

/**
 * 패스워드 교체 폼 검증. [@design SCREEN-041] [@design API-223]
 *
 * <p>새 값을 **두 번 받는다** — 잘못 친 값으로 바뀌면 아무도 다시 진입하지 못한다. 서버는 확인
 * 칸을 받지 않으므로 이 대조는 화면이 소유한다.
 *
 * <p>새 값이 현재 값과 같으면 화면에서 먼저 막는다. 서버도 400 으로 거부하지만, 보내고 나서
 * 거부되면 그 왕복 동안 사용자는 「바뀐 줄」 알게 된다.
 *
 * <p>새 값의 하한 8자는 BE `AdminPasswordChangeRequest` 의 `@Size(min = 8, max = 100)` 과 같다
 * — 현재 값(4자)과 다른 것은 의도다(옛 자격이 짧아도 받아야 교체 자체가 가능하다).
 */
export const adminPasswordChangeSchema = z
  .object({
    currentPassword: z
      .string()
      .min(4, '현재 패스워드를 입력해주세요')
      .max(100, '패스워드는 100자를 초과할 수 없습니다'),
    newPassword: z
      .string()
      .min(8, '새 패스워드는 8자 이상이어야 합니다')
      .max(100, '패스워드는 100자를 초과할 수 없습니다'),
    newPasswordConfirm: z.string().min(1, '새 패스워드를 한 번 더 입력해주세요'),
  })
  .refine((v) => v.newPassword === v.newPasswordConfirm, {
    path: ['newPasswordConfirm'],
    message: '새 패스워드가 서로 다릅니다',
  })
  .refine((v) => v.currentPassword !== v.newPassword, {
    path: ['newPassword'],
    message: '현재 패스워드와 다른 값을 입력해주세요',
  });

export type AdminPasswordChangeForm = z.infer<typeof adminPasswordChangeSchema>;
