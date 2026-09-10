/**
 * JWT payload 의 **원형 클레임**을 꺼낸다 — 서명 검증은 하지 않는다(그건 BE 책임).
 *
 * 두 곳이 쓴다:
 *  - `stores/useAuthStore` — 표시·라우팅용 `TokenClaims` 로 좁히기 전 단계
 *  - `features/auth/controlSession` — 관제 저장 형식(`tokenInfo`)을 채울 원형 클레임
 *    (`authority`·`userSido`·`sessionExpAlarm` 등은 `TokenClaims` 에 없다)
 *
 * ⚠ 해독 규칙을 두 벌로 두지 않으려고 한 곳에 모았다. UTF-8 다중 바이트(한글 이름 클레임)를
 *   `atob` 결과 그대로 쓰면 깨지므로 `TextDecoder` 로 복원한다.
 *
 * @returns 객체 클레임. 형식이 어긋나면 `null`.
 */
export function decodeJwtPayloadRaw(token: string): Record<string, unknown> | null {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const padded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padding = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
    const binary = atob(padded + padding);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const json =
      typeof TextDecoder !== 'undefined'
        ? new TextDecoder('utf-8').decode(bytes)
        : decodeURIComponent(
            Array.from(binary)
              .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
              .join(''),
          );
    const raw: unknown = JSON.parse(json);
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
    return raw as Record<string, unknown>;
  } catch {
    return null;
  }
}
