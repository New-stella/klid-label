package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 사번 → 표시명 해석 <b>단일 헬퍼</b>({@link UserNameResolver}) 계약 가드.
 *
 * <p>이 판정이 서비스마다 복붙되면 한쪽만 고쳐져 갈라지므로 헬퍼 한 곳에 두고, 여기서 계약을 고정한다:
 * 배치 1회 조회(N+1 금지) · 비숫자/미존재/null 사번은 <b>예외가 아니라 이름 null</b>.
 */
class UserNameResolverTest {

    private UserRepository userRepository;
    private UserNameResolver resolver;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resolver = new UserNameResolver(userRepository);
        lenient().when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
    }

    private static LsAcntUser user(Long userNo, String userNm) {
        LsAcntUser u = mock(LsAcntUser.class);
        lenient().when(u.getUserNo()).thenReturn(userNo);
        lenient().when(u.getUserNm()).thenReturn(userNm);
        return u;
    }

    @Test
    @DisplayName("사번이_여럿_섞여도_사용자_조회는_1회다")
    void resolveAllIssuesSingleQuery() {
        // given — 중복 포함 사번 5개(고유 3개)
        List<LsAcntUser> users =
                List.of(user(1L, "검수자1"), user(100L, "작업자100"), user(101L, "작업자101"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        UserNameResolver.UserNames names =
                resolver.resolveAll(List.of("1", "100", "1", "101", "100"));

        // then — 조회 1회 · 중복 제거된 사번만 전달 (행마다 조회하면 실패)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository, times(1)).findByUserNoIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 100L, 101L);
        verify(userRepository, never()).findByUserNo(any());
        assertThat(names.nameOf("1")).isEqualTo("검수자1");
        assertThat(names.nameOf("101")).isEqualTo("작업자101");
    }

    @Test
    @DisplayName("비숫자_사번은_예외없이_이름이_null이고_조회대상에서_빠진다")
    void nonNumericIsSkippedNotThrown() {
        // given / when — REG_ID 는 VARCHAR 라 숫자가 아닐 수 있다
        UserNameResolver.UserNames names = resolver.resolveAll(List.of("wkr01", "  ", "rev-1"));

        // then — NumberFormatException 으로 호출부 조회 전체가 죽으면 안 된다
        assertThat(names.nameOf("wkr01")).isNull();
        // 파싱 가능한 사번이 하나도 없으면 조회 자체를 하지 않는다(불필요 쿼리 제거)
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("마스터에_없는_사번은_예외없이_이름이_null이다")
    void unknownUserNoYieldsNullName() {
        // given — 퇴사·삭제로 마스터에 없음
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when / then
        assertThat(resolver.resolveAll(List.of("999")).nameOf("999")).isNull();
    }

    @Test
    @DisplayName("사번이_null이면_이름도_null이고_조회도_하지_않는다")
    void nullUserNoYieldsNullName() {
        // given — 시스템 이력 행(REG_ID 미기록)
        UserNameResolver.UserNames names = resolver.resolveAll(Arrays.asList((String) null));

        // when / then — nameOf 는 두 축 오버로드라 리터럴 null 은 캐스팅해 축을 지정한다.
        assertThat(names.nameOf((String) null)).isNull();
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("빈_입력이면_조회하지_않고_빈_결과를_돌려준다")
    void emptyInputSkipsQuery() {
        assertThat(resolver.resolveAll(List.of()).nameOf("1")).isNull();
        assertThat(resolver.resolveAll(null).nameOf("1")).isNull();
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("단건조회는_비숫자면_DB로_내려가지_않고_미존재면_null이다")
    void resolveOneContract() {
        // given
        Optional<LsAcntUser> found = Optional.of(user(100L, "작업자100"));
        when(userRepository.findByUserNo(100L)).thenReturn(found);
        when(userRepository.findByUserNo(999L)).thenReturn(Optional.empty());

        // when / then
        assertThat(resolver.resolveOne("100")).isEqualTo("작업자100");
        assertThat(resolver.resolveOne("999")).isNull();
        assertThat(resolver.resolveOne("rev01")).isNull();
        assertThat(resolver.resolveOne(null)).isNull();
        // 비숫자·null 은 파싱 단계에서 걸러져 DB 조회로 내려가지 않는다(100, 999 두 건만).
        verify(userRepository, times(2)).findByUserNo(any());
    }

    // ---------- USER_NO(Long) 축 ----------
    // 이미 숫자 FK 로 보관된 컬럼(USER_NO·ACTOR_USER_NO·RPRT_USER_NO 등) 전용. 파싱이 없을 뿐
    // 조회·폴백 계약은 문자열 축과 같아야 한다 — 두 축이 갈리면 화면마다 이름 표시가 달라진다.

    @Test
    @DisplayName("사번목록이_여럿이어도_조회는_1회다")
    void resolveAllByNoIssuesSingleQuery() {
        // given — 중복 포함 사번 5개(고유 3개)
        List<LsAcntUser> users =
                List.of(user(1L, "검수자1"), user(100L, "작업자100"), user(101L, "작업자101"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        UserNameResolver.UserNames names =
                resolver.resolveAllByNo(List.of(1L, 100L, 1L, 101L, 100L));

        // then — 조회 1회 · 중복 제거된 사번만 전달 (행마다 조회하면 실패)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository, times(1)).findByUserNoIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 100L, 101L);
        verify(userRepository, never()).findByUserNo(any());
        assertThat(names.nameOf(1L)).isEqualTo("검수자1");
        assertThat(names.nameOf(101L)).isEqualTo("작업자101");
    }

    @Test
    @DisplayName("빈_목록은_쿼리를_수행하지_않는다")
    void emptyNoListSkipsQuery() {
        // given / when — 빈 페이지·배정 없는 목록 (null 만 담긴 경우 포함)
        assertThat(resolver.resolveAllByNo(List.of()).nameOf(1L)).isNull();
        assertThat(resolver.resolveAllByNo(null).nameOf(1L)).isNull();
        assertThat(resolver.resolveAllByNo(Arrays.asList((Long) null, null)).nameOf(1L)).isNull();

        // then — 조회할 사번이 없는데 IN 쿼리를 쏘면 안 된다
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("마스터에_없는_사번은_null이다")
    void unknownNoYieldsNullName() {
        // given — 퇴사·삭제로 마스터에 없음. 목록 조회 자체는 200 으로 살아 있어야 한다.
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when / then
        assertThat(resolver.resolveAllByNo(List.of(999L)).nameOf(999L)).isNull();
        assertThat(resolver.resolveOneByNo(999L)).isNull();
    }

    @Test
    @DisplayName("null_사번은_null이다")
    void nullNoYieldsNullName() {
        // given — 배정자 미지정(USER_NO null) 행
        List<LsAcntUser> users = List.of(user(1L, "검수자1"));   // when(...) 인자 안에서 목을 만들면 중첩 스터빙
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);
        UserNameResolver.UserNames names = resolver.resolveAllByNo(Arrays.asList(1L, null));

        // when / then — null 은 이름 null 이고, 단건 경로는 DB 까지 내려가지도 않는다
        assertThat(names.nameOf((Long) null)).isNull();
        assertThat(names.nameOf(1L)).isEqualTo("검수자1");
        assertThat(resolver.resolveOneByNo(null)).isNull();
        verify(userRepository, never()).findByUserNo(any());
    }

    @Test
    @DisplayName("표시명이_null인_행도_예외없이_담긴다")
    void nullDisplayNameDoesNotBreakLookup() {
        // given — USER_NM 이 비어 있는 행. Collectors.toMap 이면 NPE 로 목록 전체가 죽는다.
        List<LsAcntUser> users = List.of(user(7L, null));       // 위와 동일 — 목 생성은 when(...) 밖에서
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when / then
        assertThat(resolver.resolveAllByNo(List.of(7L)).nameOf(7L)).isNull();
    }
}
