/**
 * 공공 웹 표준(KRDS) Footer — 근거법령·운영기관·문의처.
 *
 * ★현재 **어느 레이아웃에도 마운트돼 있지 않다** (2026-08-18 사용자 확정 — 사양 SHELL-001·SHELL-002
 *   `footer.enabled=false`). 노출 여부와 문안이 아직 확정되지 않았고, 확정 전에 자리표시 문구를
 *   내보내면 그 값이 실제 정보인 것처럼 읽히기 때문이다.
 *
 * ★그래도 **삭제하지 않는다** — 문안이 확정되면 AppLayout·PortalLayout 에 다시 마운트해 재노출한다.
 *   참조 0건인 것은 방치가 아니라 이 결정의 결과이므로 "죽은 코드"로 정리하지 말 것.
 */
export function Footer() {
  return (
    <footer className="border-t border-gray-200 bg-white px-6 py-4 text-sub text-gray-500">
      <div className="mx-auto flex max-w-6xl flex-col gap-2">
        <dl className="flex flex-col gap-1 md:flex-row md:flex-wrap md:gap-x-6 md:gap-y-1">
          <div className="flex gap-1.5">
            <dt className="font-medium text-gray-600">근거법령</dt>
            {/* TODO: 실제 문안 확정 — 지능형 CCTV 관제 근거 법령 최종 검토 후 교체 */}
            <dd>「개인정보 보호법」·「개인정보 보호법 시행령」 등</dd>
          </div>
          <div className="flex gap-1.5">
            <dt className="font-medium text-gray-600">운영기관</dt>
            {/* TODO: 실제 문안 확정 */}
            <dd>[운영기관명]</dd>
          </div>
          <div className="flex gap-1.5">
            <dt className="font-medium text-gray-600">문의처</dt>
            {/* TODO: 실제 문안 확정 */}
            <dd>[담당부서] · 전화 [000-0000-0000] · 이메일 [example@example.go.kr]</dd>
          </div>
        </dl>
        <div className="flex flex-col gap-1 text-gray-400 md:flex-row md:items-center md:justify-between">
          <span>AI 학습데이터 저작도구 v0.1.0</span>
          <span>발주: 지방자치단체 (CCTV 관제지원)</span>
        </div>
      </div>
    </footer>
  );
}
