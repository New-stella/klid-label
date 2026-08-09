/**
 * 공공 웹 표준(KRDS) 필수 Footer — 근거법령·운영기관·문의처 노출.
 * 인증 앱(AppLayout) · 포털(PortalLayout) 양쪽에서 동일 콘텐츠로 렌더한다.
 *
 * 문안은 표준 초안 + placeholder 이며 실제 문안은 후속 확정 시 교체한다.
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
