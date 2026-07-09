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
import kr.co.cudo.authoring.user.entity.MngAcctUser;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService 단위 테스트 — 역할 분리 리팩토링 Phase 2.
 *
 * <p>역할 부여/변경/조회가 저작도구 소유 {@code LS_USER_ROLE} 기준으로 동작하고
 * 관제 소유 쓰기 경로(MNG_ACCT_USER_AUTHRT/USE_YN)를 전혀 호출하지 않음을 검증한다.
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

    private MngAcctUser user(long userNo) {
        MngAcctUser u = mock(MngAcctUser.class);
        lenient().when(u.getUserNo()).thenReturn(userNo);
        lenient().when(u.getUserId()).thenReturn("user" + userNo);
        lenient().when(u.getUserNm()).thenReturn("이름" + userNo);
        lenient().when(u.getUserEmail()).thenReturn("u" + userNo + "@example.com");
        lenient().when(u.getUseYn()).thenReturn("Y");
        return u;
    }

    @Test
    @DisplayName("역할_변경시_LS_USER_ROLE만_갱신되고_MNG_ACCT_USER_AUTHRT는_쓰지_않는다")
    void roleChangeWritesOnlyLsUserRole() {
        // given — 기존 WORKER 역할 보유 사용자
        long userNo = 1001L;
        MngAcctUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // when — REVIEWER 로 변경
        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("REVIEWER"));

        // then — LS_USER_ROLE 원자 upsert 1회, 응답 역할 REVIEWER
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(userNo), eq("REVIEWER"));
        assertThat(res.role()).isEqualTo("REVIEWER");
        // userRepository 에는 어떤 쓰기도 발생하지 않는다 (조회만).
        verify(userRepository, times(1)).findByUserNo(userNo);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("role_미제공시_기존_LS_역할을_유지하고_upsert를_호출하지_않는다")
    void noRoleProvidedKeepsExistingRole() {
        long userNo = 1001L;
        MngAcctUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest(null));

        assertThat(res.role()).isEqualTo("WORKER");
        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("역할변경_존재하지않는userNo_404예외")
    void roleChangeMissingUserThrows404() {
        // given — MNG 마스터에 없는 userNo
        long userNo = 9999L;
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.empty());

        // when/then — NOT_FOUND, upsert 미호출
        assertThatThrownBy(() -> userService.update(userNo, new UserUpdateRequest("WORKER")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("역할변경_LS역할없는사용자에게_신규부여_성공")
    void roleAssignToUnassignedUserSucceeds() {
        // given — MNG 마스터엔 있으나 LS 역할 미배정(currentRole=null)
        long userNo = 1001L;
        MngAcctUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo)).thenReturn(Optional.empty());

        // when — WORKER 신규 부여
        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("WORKER"));

        // then — upsert 1회 호출 + 응답 role 반영
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(userNo), eq("WORKER"));
        assertThat(res.role()).isEqualTo("WORKER");
    }

    @Test
    @DisplayName("동일역할_재PATCH시_upsert_미호출")
    void sameRoleReapplySkipsUpsert() {
        // given — 이미 WORKER 인 사용자에게 다시 WORKER 요청
        long userNo = 1001L;
        MngAcctUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // when — 동일 역할(WORKER) 재적용
        UserProfileResponse res = userService.update(userNo, new UserUpdateRequest("WORKER"));

        // then — 불필요한 UPD_DT 갱신 방지: upsert 미호출, 응답은 기존 역할 유지
        assertThat(res.role()).isEqualTo("WORKER");
        verify(lsUserRoleRepository, never()).upsertRole(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("LS_역할없는_사용자는_역할이_null_미배정으로_반환된다")
    void unassignedUserHasNullRole() {
        // given — LS_USER_ROLE 에 행이 없는 사용자 1건의 페이지
        long userNo = 5001L;
        Pageable pageable = PageRequest.of(0, 20);
        Page<MngAcctUser> page = new PageImpl<>(List.of(user(userNo)), pageable, 1);
        when(userRepository.searchByKeyword(eq(null), eq(pageable))).thenReturn(page);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when — 역할 필터 없이 검색
        Page<UserSummaryResponse> result = userService.searchUsers(null, null, pageable);

        // then — 기본 WORKER 부여 없이 role=null (미배정)
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).role()).isNull();
    }

    @Test
    @DisplayName("role_필터_지정시_미배정_사용자는_제외된다")
    void roleFilterExcludesUnassigned() {
        Pageable pageable = PageRequest.of(0, 20);
        MngAcctUser worker = user(100L);
        MngAcctUser unassigned = user(101L);
        Page<MngAcctUser> page = new PageImpl<>(List.of(worker, unassigned), pageable, 2);
        when(userRepository.searchByKeyword(eq(null), eq(pageable))).thenReturn(page);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection()))
                .thenReturn(List.of(LsUserRole.of(100L, "WORKER")));

        Page<UserSummaryResponse> result = userService.searchUsers(null, "WORKER", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).userNo()).isEqualTo(100L);
    }

    @Test
    @DisplayName("getById_피조회사용자의_LS역할을_반환한다")
    void getByIdReturnsTargetLsRole() {
        // given — 호출자는 REVIEWER, 피조회 사용자(2001)는 LS 상 WORKER
        long targetNo = 2001L;
        MngAcctUser u = user(targetNo);
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
        MngAcctUser u = user(targetNo);
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
        MngAcctUser u = user(userNo);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(u));
        when(lsUserRoleRepository.findByUserNo(userNo))
                .thenReturn(Optional.of(LsUserRole.of(userNo, "WORKER")));

        // when
        userService.update(userNo, new UserUpdateRequest("REVIEWER"));

        // then — 인가 역할 캐시 무효화 1회
        verify(userRoleResolver, times(1)).evict(userNo);
    }

    @Test
    @DisplayName("UserUpdateRequest에_useYn_필드가_없다")
    void userUpdateRequestHasNoUseYn() {
        // 단일 컴포넌트(role)만 — useYn 접근자 부재는 컴파일로 강제됨.
        UserUpdateRequest req = new UserUpdateRequest("WORKER");
        assertThat(req.role()).isEqualTo("WORKER");
        assertThat(UserUpdateRequest.class.getRecordComponents()).hasSize(1);
        assertThat(UserUpdateRequest.class.getRecordComponents()[0].getName()).isEqualTo("role");
    }
}
