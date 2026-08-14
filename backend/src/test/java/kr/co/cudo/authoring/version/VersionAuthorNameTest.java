package kr.co.cudo.authoring.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.dto.VersionResponse;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
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
 * 버전 목록·롤백 응답의 작성자 <b>표시명</b> 노출.
 *
 * <p>배경: {@code VersionItem.authorName} 은 <b>필드명과 달리</b> 사번({@code REG_ID})을 담고 있어
 * 화면에 "2001" 같은 내부 번호가 이름 자리에 찍혔다. 이제 {@code authorName} 은 실제 표시명
 * ({@code LS_ACNT_USER.USER_NM})이고 사번은 {@code authorNo} 로 분리해 함께 내려간다(FE 폴백용).
 * 롤백 응답({@code VersionResponse.Item})은 {@code registeredUserNo}(사번)를 그대로 두고
 * {@code registeredUserName} 을 추가한다.
 *
 * <p>고정하는 계약: N+1 금지(목록 1회 조회) · 비숫자/미존재 사번은 예외 아닌 {@code null} ·
 * 사번 필드는 하위호환으로 사번을 유지.
 */
class VersionAuthorNameTest {

    private static final Long SRC_SN = 4L;
    private static final Long RAW_SN = 8L;

    private LsLabelVersionRepository labelVersionRepository;
    private LabelAccessGuard accessGuard;
    private UserRepository userRepository;
    private VersionService versionService;

    private static final TokenClaims REVIEWER =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));

    @BeforeEach
    void setUp() {
        labelVersionRepository = mock(LsLabelVersionRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        userRepository = mock(UserRepository.class);

        // 이름 해석은 실제 헬퍼를 쓴다 — N+1 단언이 리포지토리 호출 횟수에 걸려야 가드로 유효하다.
        versionService = new VersionService(
                labelVersionRepository, accessGuard, mock(VideoRepository.class),
                mock(WorkLockService.class), mock(LsDataSrcRepository.class),
                mock(LsDataLblRepository.class), new ObjectMapper(),
                mock(ApplicationEventPublisher.class), mock(ReviewApprovalGate.class),
                mock(LsDataLblAttrValRepository.class),
                mock(LsDataLblHstryRepository.class), new UserNameResolver(userRepository));
        lenient().when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
    }

    /** ACTIVE 버전 행 — 작성자 사번만 다르게. */
    private static LsLabelVersion version(String hash, String regId) {
        return LsLabelVersion.create(RAW_SN, SRC_SN, hash, "{\"items\":[]}", 1,
                LsLabelVersion.SAVE_REASON_APPROVED, regId);
    }

    private static LsAcntUser user(Long userNo, String userNm) {
        LsAcntUser u = mock(LsAcntUser.class);
        lenient().when(u.getUserNo()).thenReturn(userNo);
        lenient().when(u.getUserNm()).thenReturn(userNm);
        return u;
    }

    private void givenVersions(LsLabelVersion... rows) {
        when(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(SRC_SN)).thenReturn(List.of(rows));
    }

    // ------------------------------------------------- GET /frames/{srcSn}/versions

    @Test
    @DisplayName("버전목록_authorName은_이름이고_사번은_authorNo로_분리된다")
    void authorNameIsNameAndUserNoIsSeparate() {
        // given
        givenVersions(version("aaa111", "1"));
        // (mock 픽스처는 when(...) 밖에서 먼저 만든다 — 중첩 스터빙 금지)
        List<LsAcntUser> users = List.of(user(1L, "검수자1"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        List<VersionItem> items = versionService.listVersions(SRC_SN, REVIEWER);

        // then — 이름 자리에 사번이 찍히던 결함의 회귀 가드
        assertThat(items).hasSize(1);
        assertThat(items.get(0).authorName()).isEqualTo("검수자1");
        assertThat(items.get(0).authorNo()).isEqualTo("1");
    }

    @Test
    @DisplayName("작성자가_여럿_섞여도_사용자_조회는_1회다")
    void userLookupIsBatchedIntoSingleQuery() {
        // given — 버전 5건(작성자 3명, 중복 포함)
        givenVersions(version("a", "1"), version("b", "100"), version("c", "1"),
                version("d", "101"), version("e", "100"));
        List<LsAcntUser> users =
                List.of(user(1L, "검수자1"), user(100L, "작업자100"), user(101L, "작업자101"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        List<VersionItem> items = versionService.listVersions(SRC_SN, REVIEWER);

        // then — 조회 1회 · 중복 제거된 사번 3개만 전달 (행마다 조회하면 실패)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository, times(1)).findByUserNoIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 100L, 101L);
        verify(userRepository, never()).findByUserNo(any());
        assertThat(items).extracting(VersionItem::authorName)
                .containsExactly("검수자1", "작업자100", "검수자1", "작업자101", "작업자100");
    }

    @Test
    @DisplayName("비숫자_사번은_예외없이_이름이_null이다")
    void nonNumericUserNoYieldsNullName() {
        // given — REG_ID 는 VARCHAR 라 숫자가 아닐 수 있다
        givenVersions(version("aaa111", "rev01"));

        // when
        List<VersionItem> items = versionService.listVersions(SRC_SN, REVIEWER);

        // then
        assertThat(items.get(0).authorName()).isNull();
        assertThat(items.get(0).authorNo()).isEqualTo("rev01");
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("마스터에_없는_사번은_이름이_null이고_조회는_200이다")
    void unknownUserNoYieldsNullNameButStillReturns() {
        // given
        givenVersions(version("aaa111", "999"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when / then — 예외 없이 목록 반환(=200), 이름만 null
        List<VersionItem> items = versionService.listVersions(SRC_SN, REVIEWER);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).authorName()).isNull();
        assertThat(items.get(0).authorNo()).isEqualTo("999");
    }

    @Test
    @DisplayName("시스템_버전행은_사번도_이름도_null이다")
    void systemRowHasNullAuthor() {
        // given — REG_ID 미기록 행
        givenVersions(version("aaa111", null));

        // when
        List<VersionItem> items = versionService.listVersions(SRC_SN, REVIEWER);

        // then
        assertThat(items.get(0).authorNo()).isNull();
        assertThat(items.get(0).authorName()).isNull();
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    // ------------------------------------------------- POST /versions/{hash}/rollback

    @Test
    @DisplayName("롤백응답_registeredUserNo는_사번을_유지하고_이름은_별도필드로_내려간다")
    void rollbackItemKeepsUserNoAndAddsName() {
        // given — 컨트롤러가 롤백 결과 엔티티 + 해석된 표시명으로 응답을 만든다
        LsLabelVersion rolledBack = version("aaa111", "100");
        Optional<LsAcntUser> found = Optional.of(user(100L, "작업자100"));
        when(userRepository.findByUserNo(100L)).thenReturn(found);

        // when
        String name = versionService.resolveActorName(rolledBack.getRegId());
        VersionResponse.Item item = VersionResponse.Item.from(rolledBack, name);

        // then — 사번 필드는 하위호환으로 사번 유지
        assertThat(item.registeredUserName()).isEqualTo("작업자100");
        assertThat(item.registeredUserNo()).isEqualTo("100");
    }

    @Test
    @DisplayName("롤백응답_비숫자_사번과_미존재_사번은_이름이_null이다")
    void rollbackItemNullNameForUnresolvableUserNo() {
        // given / when — 비숫자 사번은 조회 자체를 하지 않는다
        String nonNumeric = versionService.resolveActorName("rev01");

        // 마스터에 없는 사번은 조회하되 null
        when(userRepository.findByUserNo(999L)).thenReturn(Optional.empty());
        String unknown = versionService.resolveActorName("999");

        // then — 어느 쪽도 예외를 던지지 않는다(롤백 응답은 계속 200)
        assertThat(nonNumeric).isNull();
        assertThat(unknown).isNull();
        // 비숫자 사번은 파싱 단계에서 걸러져 DB 조회로 내려가지 않는다 — 999 한 건만 조회됐다.
        verify(userRepository, times(1)).findByUserNo(any());
        verify(userRepository, times(1)).findByUserNo(999L);
    }
}
