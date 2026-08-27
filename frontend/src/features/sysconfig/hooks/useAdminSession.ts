/**
 * 관리자 단기 유효창 — **구 진입점**. 실제 구현은 관리자 유효창 모듈이 소유한다.
 *
 * 유효창의 적용 축이 「연동 서버 주소 전용」에서 **관리 기능 공통**으로 넓어지면서(ADR-046),
 * 상태를 컴포넌트가 아니라 공용 스토어가 들게 됐다. 진입 화면(`/admin`)이 연 창을 사용자 관리·
 * 연동 주소·업로드·패스워드 교체 화면이 함께 써야 하기 때문이다.
 *
 * ⚠ 여기에 판정을 복제하지 않는다 — 재노출뿐이다. 두 벌이 되면 한쪽만 갱신돼 어긋난다.
 */
export {
  useAdminSessionWindow as useAdminSession,
  ADMIN_SESSION_TTL_MINUTES_HINT,
  type UseAdminSessionResult,
} from '@/features/adminSession/hooks/useAdminSessionWindow';
