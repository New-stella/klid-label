import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';
import { BatchConfigCard } from '@/features/sysconfig/components/BatchConfigCard';
import { DeidentConfigCard } from '@/features/sysconfig/components/DeidentConfigCard';
import { HealthStatusList } from '@/features/sysconfig/components/HealthStatusList';
import { PrecisionConfigCard } from '@/features/sysconfig/components/PrecisionConfigCard';
import { YoloConfigCard } from '@/features/sysconfig/components/YoloConfigCard';
import { useConfigs } from '@/features/sysconfig/hooks/useConfigs';
import { useConfigStrings } from '@/features/sysconfig/hooks/useConfigStrings';

/**
 * SCR-MANAGE-SETTINGS 시스템 설정 (V1.x mock 시각 정합).
 *
 * UI/UX §4-16 — 2섹션 분리:
 *   ① 편집 가능 — DB 영속화 (Batch[주기·동시 처리 수·시계열 위탁 전체 건너뛰기] + YOLO + 라벨링 정밀도 + 비식별 옵션 — 독립 저장 카드 4종)
 *   ② 실시간 모니터링 — Health (read-only, 5초 폴링)
 *
 * <p>★<b>연동 서버 주소와 위험 액션은 이 화면에서 빠졌다</b> — 관리자 페이지(`/admin/endpoints` ·
 * `/admin/maintenance`)로 옮겨갔다. 여기 남은 설정들은 검수자가 늘 쓰는 값이라 <b>유효창 없이</b>
 * 역할 권한만으로 저장된다. 두 축이 한 화면에 섞여 있으면 같은 화면 안에서 요구되는 것이 항목마다
 * 달라 어느 것이 왜 잠겼는지 알기 어렵다.
 * <p>⚠ <b>외부 연동 헬스 모니터링은 남는다</b> — 옮겨간 것은 연동 서버 «주소를 바꾸는 일»이고
 * «연동 상태를 보는 일»이 아니다. 두 축을 혼동해 함께 옮기지 말 것.
 *
 * [design: SCREEN-025] **배치**: ① 은 본문에 남고 ② 는 본문 흐름에서 빠져나와 **고정(sticky)
 * 사이드바**가 된다. 설정을 만지는 동안 연동 상태가 계속 보여야 하기 때문이다.
 *  · 바뀐 것은 배치뿐이다 — `HealthStatusList` 자체(5초 폴링 read-only)는 손대지 않는다.
 *  · 좁은 화면(태블릿·모바일)에서는 2열이 성립하지 않아 **한 열로 쌓이고** sticky 도 켜지지
 *    않는다(고정된 패널이 좁은 화면을 잡아먹지 않게).
 *  · 브레이크포인트는 `md:` 다 — 이 저장소 Tailwind `screens` 는 `md`/`xl` 두 개만 정의하므로
 *    `sm:`·`lg:` 는 한 번도 적용되지 않는 죽은 접두사다.
 *  · 사이드바는 `<aside>`(complementary 랜드마크) + 섹션 제목을 접근 가능한 이름으로 물려
 *    스크린리더가 본문과 구분하게 한다. 섹션 제목은 유지한다 — 없으면 그 영역이 무엇인지
 *    알 수 없다.
 *
 * 보안: REVIEWER만 진입 (RoleGuard).
 */
/** 세 섹션 제목이 공유하는 글자 축 — 한 곳에서만 정한다(복제하면 한쪽만 갱신돼 어긋난다). */
const SECTION_TITLE_CLASS = 'text-sub font-semibold uppercase tracking-wide text-gray-600';

/** 사이드바(`<aside>`)의 접근 가능한 이름을 물릴 제목 id. */
const HEALTH_SECTION_TITLE_ID = 'system-settings-health-title';

export function SystemSettingsPage() {
  const { data: configs, isLoading, error } = useConfigs();
  // 시계열 위탁 전체 건너뛰기(BOOLEAN·STRING)는 숫자 변환 맵(`useConfigs`)에서 걸러진다. 같은
  // queryKey 를 쓰므로 요청은 한 번만 나가고 select 만 다르다.
  const { data: stringConfigs } = useConfigStrings();

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        // 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다.
        title="시스템 설정"
        description="배치 파라미터 · AI 추론 · 라벨링 정밀도 · 비식별 옵션 · 외부 연동 헬스"
      />

      {error && <ErrorState title="시스템 설정을 불러올 수 없습니다" />}

      {/* [design: SCREEN-025] 본문(①) + 우측 사이드바(②) 2열.
          좁은 화면에서는 `grid-cols-1` 이 남아 한 열로 쌓인다. */}
      <div
        data-testid="system-settings-layout"
        className="grid grid-cols-1 items-start gap-6 md:grid-cols-[minmax(0,1fr)_320px]"
      >
        <div className="flex min-w-0 flex-col gap-6">
          {/* ① 편집 가능 — DB 영속화 */}
          <div className="flex flex-col gap-3">
            <h2 className={SECTION_TITLE_CLASS}>편집 가능 — DB 영속화</h2>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              {isLoading || !configs ? (
                <div className="col-span-full flex justify-center py-6">
                  <Spinner label="설정 로딩" />
                </div>
              ) : (
                <>
                  {/* 시계열 전체 건너뛰기(BOOLEAN·STRING)는 숫자 맵에서 걸러지므로 문자열 맵도
                      함께 넘긴다 — 같은 응답을 서로 다른 select 로 본 것이라 요청은 한 번뿐이다. */}
                  <BatchConfigCard configs={configs} configStrings={stringConfigs ?? {}} />
                  <YoloConfigCard configs={configs} />
                  <PrecisionConfigCard configs={configs} />
                  <DeidentConfigCard configs={configs} />
                </>
              )}
            </div>
          </div>
        </div>

        {/* ② 실시간 모니터링 — read-only. 스크롤을 따라오는 고정 사이드바(md 이상).
            `top-6` 은 페이지 상단 여백과 같은 축이다. */}
        <aside
          aria-labelledby={HEALTH_SECTION_TITLE_ID}
          className="flex min-w-0 flex-col gap-3 md:sticky md:top-6"
        >
          <h2 id={HEALTH_SECTION_TITLE_ID} className={SECTION_TITLE_CLASS}>
            실시간 모니터링
          </h2>
          <HealthStatusList />
        </aside>
      </div>
    </section>
  );
}
