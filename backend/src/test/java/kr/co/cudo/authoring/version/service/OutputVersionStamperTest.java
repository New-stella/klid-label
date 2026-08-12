package kr.co.cudo.authoring.version.service;

import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 산출 회차 확정 — <b>번호 채번</b>과 <b>회차↔스냅샷 매핑</b>이 산출 마감 트랜잭션에서 함께 일어나는지
 * 고정한다.
 *
 * <h3>왜 둘 다인가</h3>
 * {@code VER_NO} 는 값이 하나뿐이라 "한 스냅샷이 여러 회차의 내용"(1:N)을 담지 못한다. 채번이
 * <b>미채번 행만</b> 대상인 것은 그 컬럼의 의미상 옳지만, 그래서 <b>롤백으로 옛 스냅샷이 다시 ACTIVE 가
 * 된 회차</b>의 대응이 어디에도 남지 않는다. 그 회차를 시작 버전으로 고르면 번호 기반 규칙이 그 사이
 * 회차의 <b>비활성</b> 스냅샷을 골라 존재한 적 없는 내용으로 되돌린다(조용한 오복원). 매핑은
 * <b>ACTIVE 전량</b>(이미 번호가 찍힌 행 포함)을 기록해 그 정보를 남긴다.
 *
 * @design D5
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class OutputVersionStamperTest {

    private static final long RAW_SN = 9L;

    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LsOutputVerSnpshRepository outputVerSnpshRepository;

    private OutputVersionStamper stamper;

    @BeforeEach
    void setUp() {
        stamper = new OutputVersionStamper(labelVersionRepository, outputVerSnpshRepository);
    }

    @Test
    @DisplayName("회차_확정_시_미채번_ACTIVE_행에_번호를_찍고_ACTIVE_전량의_회차_매핑을_남긴다")
    void 번호를_찍고_회차_매핑을_남긴다() {
        when(labelVersionRepository.stampOutputVersionNo(anyLong(), anyInt(), anyString())).thenReturn(1);
        when(outputVerSnpshRepository.recordActiveSnapshots(anyLong(), anyInt(), anyString())).thenReturn(3);

        stamper.stamp(RAW_SN, 2);

        verify(labelVersionRepository).stampOutputVersionNo(RAW_SN, 2, LsLabelVersion.ACTIVE_YES);
        verify(outputVerSnpshRepository).recordActiveSnapshots(RAW_SN, 2, LsLabelVersion.ACTIVE_YES);
    }

    @Test
    @DisplayName("이번_회차에_바뀐_프레임이_없어_채번이_0건이어도_회차_매핑은_남긴다")
    void 채번이_0건이어도_회차_매핑은_남긴다() {
        // given — 내용 무변경(멱등 skip) 회차. 번호를 찍을 대상은 없지만
        //   "이 회차의 내용은 그 스냅샷들이었다"는 사실은 여전히 참이고, 그것이 번호만으로는 잃던 정보다.
        when(labelVersionRepository.stampOutputVersionNo(anyLong(), anyInt(), anyString())).thenReturn(0);
        when(outputVerSnpshRepository.recordActiveSnapshots(anyLong(), anyInt(), anyString())).thenReturn(2);

        stamper.stamp(RAW_SN, 5);

        verify(outputVerSnpshRepository).recordActiveSnapshots(RAW_SN, 5, LsLabelVersion.ACTIVE_YES);
    }
}
