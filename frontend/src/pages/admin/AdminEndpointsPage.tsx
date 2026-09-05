import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';
import { AiServerListCard } from '@/features/aiServer/components/AiServerListCard';
import { IntegrationEndpointsCard } from '@/features/sysconfig/components/IntegrationEndpointsCard';
import { useConfigStrings } from '@/features/sysconfig/hooks/useConfigStrings';

/**
 * 연동 서버 주소 관리 화면(`/admin/endpoints`). [@design SCREEN-042] [@design API-069]
 *
 * <h3>왜 시스템 설정에서 떼어 냈나</h3>
 * 이 주소들은 잘못 바꾸면 학습데이터가 통째로 다른 서버로 나갈 수 있는 값이다. 검수자가 늘 쓰는
 * 배치·추론·정밀도·비식별 설정과 같은 화면에 두면, 같은 화면 안에서 요구되는 것이 항목마다 달라
 * 어느 것이 왜 잠겼는지 알기 어렵다. 관리자 페이지로 옮겨 <b>진입 자체에 관리자 확인</b>을 건다.
 *
 * <h3>조회와 저장의 요건이 다르다</h3>
 * 현재 값을 보는 것은 검수자 권한만으로 되고, 저장에만 관리자 유효창이 <b>가산</b>된다. 그래서
 * 유효창이 끝나도 화면 밖으로 쫓아내지 않는다 — 값은 그대로 보이고 저장만 막힌다.
 *
 * <p>⚠ 데이터베이스 접속정보는 이 화면의 대상이 아니다. 여기서 찾다가 없다고 판단하는 일이
 * 없도록 화면에서 그 사실을 밝힌다.
 *
 * <h3>주소 한 칸인 것과 장비 목록인 것이 한 화면에 있다</h3>
 * 비식별 서버·관제 통지 수신처는 <b>한 칸 그대로</b>다. 반면 AI 추론과 외부 시계열 분석은 장비를
 * 여러 대 두고 부하에 따라 나눠 보내는 축이라 <b>목록</b>으로 다룬다(ADR-046). 위 카드의 그 두 칸은
 * 사라지지 않는다 — 원장이 비어 있을 때 최초 1회 씨앗으로 쓰이기 때문이다.
 */
export function AdminEndpointsPage() {
  // 연동 주소는 문자열이라 숫자 변환 맵(`useConfigs`)에서 걸러진다 — 문자열 select 를 쓴다.
  const { data: stringConfigs, isLoading, error } = useConfigStrings();

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        title="연동 서버 주소"
        description="비식별 서버 · AI 추론 서버 · 외부 시계열 분석 벤더 · 관제 통지 수신처"
      />

      <p className="text-caption text-gray-600">
        데이터베이스 접속정보는 이 화면에서 다루지 않습니다.
      </p>

      {error && <ErrorState title="연동 서버 주소를 불러올 수 없습니다" />}

      <div className="max-w-3xl">
        {isLoading || !stringConfigs ? (
          <div className="flex justify-center py-6">
            <Spinner label="연동 주소 로딩" />
          </div>
        ) : (
          <IntegrationEndpointsCard configs={stringConfigs} />
        )}
      </div>

      {/*
        AI 장비는 «한 칸»이 아니라 «목록»이다 — 유형마다 여러 대를 두고 부하에 따라 나눠 보내는
        축이라, 주소 칸 하나로는 두 번째 장비를 넣을 방법이 없다(ADR-046 · ADR-057).
        ⚠ 위 카드의 AI 추론 서버·외부 시계열 분석 벤더 칸은 **씨앗**으로 계속 쓰이므로 지우지 말 것 —
          원장이 비어 있을 때 최초 1회만 반영된다.
      */}
      <AiServerListCard />
    </section>
  );
}
