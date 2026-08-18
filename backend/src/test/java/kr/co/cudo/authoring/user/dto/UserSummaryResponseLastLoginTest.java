package kr.co.cudo.authoring.user.dto;

import kr.co.cudo.authoring.user.entity.LsAcntUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 사용자 목록 응답의 최종로그인일시 노출 계약.
 *
 * <p>이 화면의 결함은 "데이터가 없는데 등록일을 최근 로그인으로 보여준 것"이었다. 그 폴백을
 * <b>BE 응답 축에서도</b> 만들지 않는다 — 값이 없으면 null 을 그대로 내려보내고, 표기는 FE 가
 * 미접속으로 그린다.
 *
 * @design SCREEN-024
 */
class UserSummaryResponseLastLoginTest {

    private LsAcntUser user(LocalDateTime regDt, LocalDateTime lastLgnDt) {
        LsAcntUser u = mock(LsAcntUser.class);
        lenient().when(u.getUserNo()).thenReturn(7L);
        lenient().when(u.getUserId()).thenReturn("hong");
        lenient().when(u.getUserNm()).thenReturn("홍길동");
        lenient().when(u.getUserEmlAddr()).thenReturn("hong@example.com");
        lenient().when(u.getUseYn()).thenReturn("Y");
        lenient().when(u.getRegDt()).thenReturn(regDt);
        lenient().when(u.getLastLgnDt()).thenReturn(lastLgnDt);
        return u;
    }

    @Test
    @DisplayName("사용자_목록_응답에_최신_로그인_시각이_실린다")
    void 사용자_목록_응답에_최신_로그인_시각이_실린다() {
        // given
        LocalDateTime regDt = LocalDateTime.of(2026, 5, 1, 9, 0, 0);
        LocalDateTime lastLgnDt = LocalDateTime.of(2026, 8, 16, 14, 30, 0);

        // when
        UserSummaryResponse res = UserSummaryResponse.from(user(regDt, lastLgnDt), "WORKER");

        // then — FE alias 와 BE 원본 컬럼명 두 축 모두 노출한다(기존 alias 규약).
        assertThat(res.lastLoginAt()).isEqualTo(lastLgnDt);
        assertThat(res.lastLgnDt()).isEqualTo(lastLgnDt);
        // 등록일과 최신 로그인은 별개 축이다 — 서로를 덮지 않는다.
        assertThat(res.createdAt()).isEqualTo(regDt);
        assertThat(res.regDt()).isEqualTo(regDt);
    }

    @Test
    @DisplayName("한_번도_접속하지_않은_계정의_최신_로그인은_null_이다")
    void 한_번도_접속하지_않은_계정의_최신_로그인은_null_이다() {
        // given — 최종로그인일시가 없는 계정(신규 등록 직후·레거시 행).
        LocalDateTime regDt = LocalDateTime.of(2026, 5, 1, 9, 0, 0);

        // when
        UserSummaryResponse res = UserSummaryResponse.from(user(regDt, null), "WORKER");

        // then — 등록일로 대체하지 않는다. 기본값을 넣으면 "접속한 적 있음"을 날조한다.
        assertThat(res.lastLoginAt()).isNull();
        assertThat(res.lastLgnDt()).isNull();
        assertThat(res.createdAt()).isEqualTo(regDt);
    }
}
