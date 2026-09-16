package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.UserUpdateRequest;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService 단위 테스트.
 *
 * <p>역할 부여/변경/조회가 저작도구 소유 {@code LS_USER_ROLE} 기준으로 동작하고, 사용자 마스터
 * 쓰기 경로(활성여부 UPDATE 등)를 전혀 호출하지 않음을 검증한다. 구 관제 권한 테이블 2종은
 * V165 로, 관제 사용자 마스터는 V169 로 삭제됐다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private LsUserRoleRepository lsUserRoleRepository;
    @Mock
    private kr.co.cudo.authoring.common.security.UserRoleResolver userRoleResolver;
    @InjectMocks
    private UserService userService;

    private LsAcntUser user(long userNo) {
        LsAcntUser u = mock(LsAcntUser.class);
        lenient().when(u.getUserNo()).thenReturn(userNo);
        lenient().when(u.getUserId()).thenReturn("user" + userNo);
        lenient().when(u.getUserNm()).thenReturn("이름" + userNo);
        lenient().when(u.getUserEmlAddr()).thenReturn("u" + userNo + "@example.com");
        lenient().when(u.getUseYn()).thenReturn("Y");
        return u;
    }

    @Test
    @DisplayName("역할_변경시_LS_USER_ROLE만_갱신되고_관제_계정테이블은_쓰지_않는다")
    void roleChangeWritesOnlyLsUserRole() {
        // given — 기존 WORKER 역할 보유 사용자
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // when — REVIEWER 로 변경
        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("REVIEWER", null));

        // then — LS_USER_ROLE 원자 upsert 1회, 응답 역할 REVIEWER
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(userNo), eq("REVIEWER"), isNull());
        assertThat(res.role()).isEqualTo("REVIEWER");
        // userRepository 에는 어떤 쓰기도 발생하지 않는다 (조회만).
        verify(userRepository, times(1)).findByUserNo(userNo);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("role_미제공시_기존_LS_역할을_유지하고_upsert를_호출하지_않는다")
    void noRoleProvidedKeepsExistingRole() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest(null, null));

        assertThat(res.role()).isEqualTo("WORKER");
        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("역할변경_존재하지않는userNo_404예외")
    void roleChangeMissingUserThrows404() {
        // given — MNG 마스터에 없는 userNo
        long userNo = 9999L;
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.empty());

        // when/then — NOT_FOUND, upsert 미호출
        assertThatThrownBy(() -> userService.update(userNo, new UserUpdateRequest("WORKER", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("역할변경_LS역할없는사용자에게_신규부여_성공")
    void roleAssignToUnassignedUserSucceeds() {
        // given — MNG 마스터엔 있으나 LS 역할 미배정(currentRole=null)
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo)).thenReturn(Optional.empty());

        // when — WORKER 신규 부여
        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("WORKER", null));

        // then — upsert 1회 호출 + 응답 role 반영
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(userNo), eq("WORKER"), isNull());
        assertThat(res.role()).isEqualTo("WORKER");
    }

    @Test
    @DisplayName("동일역할_재PATCH시_upsert_미호출")
    void sameRoleReapplySkipsUpsert() {
        // given — 이미 WORKER 인 사용자에게 다시 WORKER 요청
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // when — 동일 역할(WORKER) 재적용
        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("WORKER", null));

        // then — 불필요한 UPD_DT 갱신 방지: upsert 미호출, 응답은 기존 역할 유지
        assertThat(res.role()).isEqualTo("WORKER");
        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("LS_역할없는_사용자는_역할이_null_미배정으로_반환된다")
    void unassignedUserHasNullRole() {
        // given — LS_USER_ROLE 에 행이 없는 사용자 1건의 페이지
        long userNo = 5001L;
        Pageable pageable = PageRequest.of(0, 20);
        Page<LsAcntUser> page = new PageImpl<>(List.of(user(userNo)), pageable, 1);
        when(userRepository.searchByKeywordAndRole(eq(null), eq(null), eq(pageable))).thenReturn(page);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when — 역할 필터 없이 검색
        Page<UserSummaryResponse> result = userService.searchUsers(null, null, pageable);

        // then — 기본 WORKER 부여 없이 role=null (미배정)
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).role()).isNull();
    }

    @Test
    @DisplayName("★role_필터는_조회로_내려간다 — 서비스가_페이지_안에서_거르지_않는다")
    void roleFilterIsDelegatedToQuery() {
        // 구 구현은 페이지를 먼저 가져온 뒤 인메모리로 걸러, 1페이지 밖의 해당 역할 사용자에게
        //   도달할 수 없었다. 이제 필터 값이 그대로 조회로 내려가야 한다.
        Pageable pageable = PageRequest.of(0, 20);
        Page<LsAcntUser> page = new PageImpl<>(List.of(user(100L)), pageable, 1);
        when(userRepository.searchByKeywordAndRole(eq("kw"), eq("WORKER"), eq(pageable))).thenReturn(page);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection()))
                .thenReturn(List.of(LsUserRole.of(100L, "WORKER")));

        Page<UserSummaryResponse> result = userService.searchUsers("kw", "WORKER", pageable);

        // 조회에 role 이 실려 나갔다 — 이 verify 가 인메모리 필터 회귀를 막는다.
        verify(userRepository, times(1)).searchByKeywordAndRole(eq("kw"), eq("WORKER"), eq(pageable));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).userNo()).isEqualTo(100L);
        assertThat(result.getContent().get(0).role()).isEqualTo("WORKER");
    }

    @Test
    @DisplayName("★totalElements는_조회_결과_그대로다 — 필터_이전_합계로_되돌리지_않는다")
    void totalElementsComesFromQuery() {
        // 구 구현은 「필터 적용 후 목록 + 필터 이전 합계」를 섞어 "1건 표시 / 총 87건" 을 만들었다.
        Pageable pageable = PageRequest.of(0, 20);
        Page<LsAcntUser> page = new PageImpl<>(List.of(user(100L)), pageable, 1);
        when(userRepository.searchByKeywordAndRole(eq(null), eq("REVIEWER"), eq(pageable))).thenReturn(page);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection()))
                .thenReturn(List.of(LsUserRole.of(100L, "REVIEWER")));

        Page<UserSummaryResponse> result = userService.searchUsers(null, "REVIEWER", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("역할_조회는_페이지당_1회다 — N+1_방지가_유지된다")
    void roleLookupIsOnePerPage() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<LsAcntUser> page = new PageImpl<>(List.of(user(100L), user(101L), user(102L)), pageable, 3);
        when(userRepository.searchByKeywordAndRole(eq(null), eq(null), eq(pageable))).thenReturn(page);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection()))
                .thenReturn(List.of(LsUserRole.of(100L, "WORKER"), LsUserRole.of(101L, "REVIEWER")));

        Page<UserSummaryResponse> result = userService.searchUsers(null, null, pageable);

        verify(lsUserRoleRepository, times(1)).findByUserNoIn(anyCollection());
        verify(lsUserRoleRepository, never()).findByUserNo(org.mockito.ArgumentMatchers.anyLong());
        // 역할 행이 없는 102 는 미배정(null) — 기본 역할을 지어내지 않는다.
        assertThat(result.getContent()).extracting(UserSummaryResponse::role)
                .containsExactly("WORKER", "REVIEWER", null);
    }

    @Test
    @DisplayName("getById_피조회사용자의_LS역할을_반환한다")
    void getByIdReturnsTargetLsRole() {
        // given — 호출자는 REVIEWER, 피조회 사용자(2001)는 LS 상 WORKER
        long targetNo = 2001L;
        LsAcntUser u = user(targetNo);
        when(userRepository.findByUserNo(targetNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(targetNo))
                .thenReturn(Optional.of(LsUserRole.of(targetNo, "WORKER")));
        TokenClaims caller = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));

        // when
        UserProfileResponse res = userService.getById(targetNo, caller);

        // then — 호출자(REVIEWER)가 아니라 피조회자(WORKER)의 실제 역할이 응답된다
        assertThat(res.role()).isEqualTo("WORKER");
        assertThat(res.channel()).isEmpty();
    }

    @Test
    @DisplayName("getById_LS역할없는사용자는_역할_null")
    void getByIdUnassignedRoleNull() {
        // given — 피조회 사용자가 LS_USER_ROLE 미배정
        long targetNo = 3001L;
        LsAcntUser u = user(targetNo);
        when(userRepository.findByUserNo(targetNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(targetNo)).thenReturn(Optional.empty());
        TokenClaims caller = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));

        // when
        UserProfileResponse res = userService.getById(targetNo, caller);

        // then — fail-closed: 미배정은 null (호출자 역할로 채우지 않는다)
        assertThat(res.role()).isNull();
    }

    @Test
    @DisplayName("역할변경시_evict_호출_verify")
    void roleChangeEvictsCache() {
        // given — 기존 WORKER → REVIEWER 로 실제 변경 (no-tx: AFTER_COMMIT 동기화 미활성 → 즉시 evict)
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // when
        userService.update(userNo, new UserUpdateRequest("REVIEWER", null));

        // then — 인가 역할 캐시 무효화 1회
        verify(userRoleResolver, times(1)).evict(userNo);
    }

    // ------------------------------------------------------------------------
    // 역할 변경 주체(MDFR_ID) — @design AC-1018
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("★역할_변경시_바꾼_사람이_upsert에_실린다")
    void roleChangeCarriesActor() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);   // 목 생성을 when(...) 인자 안에서 하면 stubbing 이 겹친다
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        userService.update(userNo, new UserUpdateRequest("REVIEWER", null), "969600001");

        // 역할과 주체가 <같은 문장>에서 쓰인다 — 따로 쓰면 그 사이 실패가 「주체 없는 변경」을 남긴다.
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(userNo), eq("REVIEWER"), eq("969600001"));
    }

    @Test
    @DisplayName("★주체_문자열의_제어문자는_제거된다 — 로그_위조_차단(CWE-117)")
    void actorControlCharactersAreStripped() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);   // 목 생성을 when(...) 인자 안에서 하면 stubbing 이 겹친다
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        userService.update(userNo, new UserUpdateRequest("REVIEWER", null), "12\r\n[User] forged=1");

        verify(lsUserRoleRepository, times(1))
                .upsertRole(eq(userNo), eq("REVIEWER"), eq("12[User] forged=1"));
    }

    @Test
    @DisplayName("★주체가_컬럼_폭을_넘으면_잘린다 — DB오류(500)로_새지_않는다")
    void actorIsTruncatedToColumnWidth() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);   // 목 생성을 when(...) 인자 안에서 하면 stubbing 이 겹친다
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));
        String tooLong = "9".repeat(40);

        userService.update(userNo, new UserUpdateRequest("REVIEWER", null), tooLong);

        // MDFR_ID 는 표준도메인 식별자V30 = varchar(30)
        verify(lsUserRoleRepository, times(1))
                .upsertRole(eq(userNo), eq("REVIEWER"), eq("9".repeat(30)));
    }

    @Test
    @DisplayName("주체를_모르면_null이_남는다 — 지어내지_않는다")
    void unknownActorStaysNull() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);   // 목 생성을 when(...) 인자 안에서 하면 stubbing 이 겹친다
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        userService.update(userNo, new UserUpdateRequest("REVIEWER", null), "   ");

        verify(lsUserRoleRepository, times(1)).upsertRole(eq(userNo), eq("REVIEWER"), isNull());
    }

    @Test
    @DisplayName("★UserUpdateRequest의_쓰기_축은_role과_userNm_둘뿐이다 — useYn·주체_필드가_없다")
    void userUpdateRequestExposesOnlyRoleAndUserNm() {
        UserUpdateRequest req = new UserUpdateRequest("WORKER", "홍길동");
        assertThat(req.role()).isEqualTo("WORKER");
        assertThat(req.userNm()).isEqualTo("홍길동");
        // 계정 활성 여부(useYn)는 외부(관제) 소유 읽기 전용이고, 주체(actor/mdfrId)는 바디 값이라
        //   위조 가능해 받지 않는다. 그 필드가 늘면 여기서 걸린다.
        assertThat(java.util.Arrays.stream(UserUpdateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("role", "userNm");
    }

    // ------------------------------------------------------------------------
    // 표시 이름 축 — 역할 축과 독립  [@design API-004] [@design AC-1018] [@design AC-1019]
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("★이름을_보내면_공백·제어문자를_걷어낸_값이_저장되고_응답에_실린다")
    void displayNameIsNormalizedBeforeWrite() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest(null, "  홍\t길동\n  "));

        verify(userRepository, times(1)).updateUserNm(eq(userNo), eq("홍길동"));
        // 응답은 엔티티 스냅샷이 아니라 실제로 쓴 값을 실어야 한다 — native UPDATE 는 영속성
        //   컨텍스트를 우회하므로 엔티티에서 읽으면 옛 이름이 나간다.
        assertThat(res.userNm()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("이름_미전송이면_이름_쓰기가_아예_없다 — 보내지_않은_축은_바꾸지_않는다")
    void absentDisplayNameIsNotWritten() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("REVIEWER", null));

        verify(userRepository, never()).updateUserNm(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(res.userNm()).isEqualTo("이름" + userNo);
    }

    @Test
    @DisplayName("★정규화_후_남는_것이_없으면_400이고_쓰기가_일어나지_않는다")
    void blankDisplayNameIsRejected() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        // 이름 검증이 역할 조회보다 앞서 거절하므로 이 조회는 돌지 않는다 — lenient 로 둔다.
        lenient().when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // 공백뿐 · 제어문자뿐 — 원문을 보는 선언적 검증은 뒤엣것을 통과시킨다.
        for (String blank : List.of("   ", "\t\n", " \t ")) {
            assertThatThrownBy(() -> userService.update(userNo, new UserUpdateRequest(null, blank)))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_INPUT));
        }
        verify(userRepository, never()).updateUserNm(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * ★{@code trim()}/{@code strip()} 이 놓치는 구멍 — 판정 축은 whitespace 가 아니라 문자 카테고리다.
     *
     * <p>{@code trim()} 은 {@code U+0020} 이하만 털고, {@code strip()} 이 쓰는
     * {@code Character.isWhitespace} 는 non-breaking 공백({@code U+00A0}·{@code U+2007}·{@code U+202F})을
     * 공백으로 보지 않는다. 그래서 둘 중 무엇을 써도 이 입력들이 "빈 값" 판정을 빠져나가 그대로
     * 저장된다 — 화면 쪽 입구는 JS {@code trim()} 이 털어내므로 두 입구의 판정이 갈린다.
     */
    @Test
    @DisplayName("★보이지_않는_공백만_보내면_400이다 — NBSP·전각공백은_trim도_strip도_못_턴다")
    void invisibleUnicodeSpaceOnlyNameIsRejected() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        // 이름 검증이 역할 조회보다 앞서 거절하므로 이 조회는 돌지 않는다 — lenient 로 둔다.
        lenient().when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        for (String invisible : List.of(
                " ",   // NBSP — strip() 도 남긴다
                "　",   // IDEOGRAPHIC SPACE — trim() 이 남긴다
                " ",   // FIGURE SPACE — strip() 도 남긴다
                " ",   // NARROW NO-BREAK SPACE — strip() 도 남긴다
                "​",   // ZERO WIDTH SPACE
                "﻿",   // BOM
                " 　 \t")) {
            assertThatThrownBy(() -> userService.update(userNo, new UserUpdateRequest(null, invisible)))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_INPUT));
        }
        verify(userRepository, never()).updateUserNm(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("★가장자리_유니코드_공백은_걷히고_안쪽_공백은_보존된다 — 지우지_않고_일반_공백으로_바꾼다")
    void edgeUnicodeSpaceIsStrippedWhileInnerSpaceSurvives() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // 가장자리 NBSP·전각공백은 걷힌다 — 이름 자체는 살아남는다(fail-closed 가 정상 이름을 막지 않는다).
        userService.update(userNo, new UserUpdateRequest(null, " 홍 길동　"));
        verify(userRepository, times(1)).updateUserNm(eq(userNo), eq("홍 길동"));

        // 안쪽 NBSP 는 단어 구분 의미를 가지므로 지우지 않고 일반 공백으로 바꾼다 — 지우면 붙어버린다.
        userService.update(userNo, new UserUpdateRequest(null, "홍 길동"));
        verify(userRepository, times(2)).updateUserNm(eq(userNo), eq("홍 길동"));
    }

    @Test
    @DisplayName("★저장_폭을_넘으면_400이다 — 잘라서_저장하지_않는다")
    void overlongDisplayNameIsRejectedNotTruncated() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        // 이름 검증이 역할 조회보다 앞서 거절하므로 이 조회는 돌지 않는다 — lenient 로 둔다.
        lenient().when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));
        String tooLong = "가".repeat(UserDisplayNames.MAX_USER_NM_LENGTH + 1);

        assertThatThrownBy(() -> userService.update(userNo, new UserUpdateRequest(null, tooLong)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));

        // 정규화기는 상한을 넘으면 <자른다>. 그 절단값이 그대로 저장되면 다른 사람 이름이 된다.
        verify(userRepository, never()).updateUserNm(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("★정확히_저장_폭과_같은_길이는_통과한다 — 절단값으로_판정하면_여기서_깨진다")
    void exactlyMaxLengthDisplayNameIsAccepted() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));
        String exact = "가".repeat(UserDisplayNames.MAX_USER_NM_LENGTH);

        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest(null, exact));

        verify(userRepository, times(1)).updateUserNm(eq(userNo), eq(exact));
        assertThat(res.userNm()).hasSize(UserDisplayNames.MAX_USER_NM_LENGTH);
    }

    @Test
    @DisplayName("★같은_값_판정은_문장_안에_있다 — 서비스가_미리_비교해_거르지_않는다")
    void sameNameStillGoesThroughTheConditionalStatement() {
        // 미리 비교해 거르면 2노드가 같은 옛 값을 읽고 각각 써 수정일시가 두 번 밀린다.
        //   실제 무변경 보장(수정일시 미갱신)은 UserControllerTest 의 DB 시험이 증명한다.
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        userService.update(userNo, new UserUpdateRequest(null, "이름" + userNo));

        verify(userRepository, times(1)).updateUserNm(eq(userNo), eq("이름" + userNo));
    }

    @Test
    @DisplayName("이름만_보낸_요청은_역할_축을_건드리지_않는다")
    void nameOnlyRequestDoesNotTouchRole() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "ADMIN")));

        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest(null, "새이름"));

        // 역할 upsert·캐시 무효화·관리자 행 잠금 어느 것도 일어나지 않는다.
        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(lsUserRoleRepository, never()).lockUserNosByRoleCd(org.mockito.ArgumentMatchers.anyString());
        verify(userRoleResolver, never()).evict(org.mockito.ArgumentMatchers.anyLong());
        assertThat(res.role()).isEqualTo("ADMIN");
    }

    /**
     * ★<b>검증이 역할 쓰기보다 앞에 있다는 것을 구조로 고정한다 — 롤백에 기대지 않는다.</b>
     *
     * <p>이 시험이 목 기반인 것은 <b>의도</b>다. 같은 단정을 DB 시험으로 쓰면 변이를 잡지 못한다 —
     * 검증을 역할 분기 뒤로 되돌려도 {@code CustomException} 이 트랜잭션을 롤백해 <b>저장소에서
     * 되읽은 역할은 여전히 그대로</b>이기 때문이다. 즉 DB 되읽기는 "결과가 안전한가" 만 보고
     * "역할 쓰기가 <i>일어났는가</i>" 는 보지 못한다. 트랜잭션이 없는 이 자리에서만 그 차이가 드러난다.
     *
     * <p>짝이 되는 종단 시험은 {@code UserControllerTest} 가 갖는다(400 + 저장소 무변경).
     */
    @Test
    @DisplayName("★역할이_유효해도_이름이_무효면_역할_쓰기가_아예_일어나지_않는다 — 롤백에_기대지_않는다")
    void invalidNameBlocksRoleWriteBeforeItEverHappens() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        // 검증이 앞서면 이 조회는 아예 돌지 않는다 — 변이(검증을 뒤로)에서만 쓰이므로 lenient.
        lenient().when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        assertThatThrownBy(() -> userService.update(userNo, new UserUpdateRequest("REVIEWER", "   ")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));

        // 역할 축은 손도 대지 않았다 — 쓰기·잠금·캐시 무효화 어느 것도 없다.
        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(lsUserRoleRepository, never()).lockUserNosByRoleCd(org.mockito.ArgumentMatchers.anyString());
        verify(userRoleResolver, never()).evict(org.mockito.ArgumentMatchers.anyLong());
        verify(userRepository, never()).updateUserNm(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("역할과_이름을_함께_보내면_둘_다_반영된다")
    void roleAndNameCanChangeTogether() {
        long userNo = 1001L;
        LsAcntUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("REVIEWER", "새이름"), "969600001");

        verify(lsUserRoleRepository, times(1)).upsertRole(eq(userNo), eq("REVIEWER"), eq("969600001"));
        verify(userRepository, times(1)).updateUserNm(eq(userNo), eq("새이름"));
        assertThat(res.role()).isEqualTo("REVIEWER");
        assertThat(res.userNm()).isEqualTo("새이름");
    }
}
