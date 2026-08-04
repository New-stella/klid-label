package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.UserUpdateRequest;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final LsUserRoleRepository lsUserRoleRepository;
    private final UserRoleResolver userRoleResolver;

    public UserProfileResponse getProfile(TokenClaims claims) {
        Long userNo = parseUserNo(claims.sub());
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        // Phase 3 — LS 미배정 INTERNAL 사용자는 role 이 null 일 수 있어 null-safe roleName() 사용(NPE 방어).
        return UserProfileResponse.of(user, claims.roleName(), claims.channel().name());
    }

    /**
     * 사용자 프로필 단건 조회 (REVIEWER 의 관리 화면).
     *
     * <p>버그 수정(역할 분리 Phase 3 DEV_FIX) — 응답 역할/채널을 호출자({@code claims})가 아니라
     * <b>피조회 사용자</b>의 실제 LS 역할로 채운다. 이전 구현은 호출자(REVIEWER)의 역할/채널을 그대로
     * 응답에 넣어 관리 화면에서 모든 사용자가 호출자 역할로 오표시되는 결함이 있었다.
     *
     * <p>역할 출처는 {@code searchUsers} 와 동일하게 {@link LsUserRoleRepository#findByUserNo(Long)} 다.
     * 미배정이면 fail-closed 로 null(미배정). 채널은 피조회 사용자의 컨텍스트가 없어 빈 값으로 둔다
     * ({@code update} 응답과 동일 컨벤션).
     */
    public UserProfileResponse getById(Long userNo, TokenClaims claims) {
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        String role = lsUserRoleRepository.findByUserNo(userNo)
                .map(LsUserRole::getRoleCd)
                .orElse(null);
        return UserProfileResponse.of(user, role, "");
    }

    public List<WorkerSummaryResponse> listWorkersWithTaskCount() {
        return userRepository.findAllWorkersWithTaskCount().stream()
                .map(WorkerSummaryResponse::from)
                .toList();
    }

    /**
     * 사용자 마스터 페이징 검색 (/v1/users) — REVIEWER 의 사용자 관리 화면.
     * 권한 코드는 N+1 방지를 위해 페이지 결과의 userNo 목록을 한 번에 LS_USER_ROLE 에서 조회한 뒤 매핑한다.
     *
     * <p>role 필터(선택): null/blank 이면 모두 반환, 값이 있으면 해당 역할만 반환.
     * <p>정책 — LS 역할이 없는 사용자는 미배정(null)으로 매핑한다. 기본 WORKER 부여를 하지 않으므로
     * 인가(무권한)와 정합한다. role 필터가 지정되면 미배정(null) 사용자는 자연히 제외된다.
     * <p>메모리 필터 — 정확한 페이징을 위해선 SQL JOIN 필터가 이상적이나 작업자 모수가 적어 실용상 OK.
     * Controller 단계에서 @Pattern 화이트리스트로 사전 검증된 값만 도달 (SQL Injection 차단).
     */
    public Page<UserSummaryResponse> searchUsers(String keyword, String role, Pageable pageable) {
        Page<MngAcctUser> page = userRepository.searchByKeyword(keyword, pageable);
        List<Long> userNos = page.getContent().stream().map(MngAcctUser::getUserNo).toList();
        Map<Long, String> roleByUserNo = new HashMap<>();
        if (!userNos.isEmpty()) {
            for (LsUserRole r : lsUserRoleRepository.findByUserNoIn(userNos)) {
                roleByUserNo.put(r.getUserNo(), r.getRoleCd());
            }
        }

        Stream<MngAcctUser> filtered = page.getContent().stream();
        if (role != null && !role.isBlank()) {
            // 미배정(null) 사용자는 어떤 역할 필터와도 매칭되지 않아 제외된다.
            filtered = filtered.filter(u -> role.equals(roleByUserNo.get(u.getUserNo())));
        }
        List<UserSummaryResponse> items = filtered
                .map(u -> UserSummaryResponse.from(u, roleByUserNo.get(u.getUserNo())))
                .toList();

        // 형제 서비스(PortalLabelService/ReviewService) 컨벤션 — totalElements 는 원본 페이지 합계를 사용.
        // 주의: role 필터는 페이지 컨텐츠에 인메모리로 적용되므로 필터 시 totalElements 가 실제 매칭 수보다
        // 클 수 있다(작업자 모수가 적어 실용상 허용). 근본 해결(SQL JOIN 필터)은 후속.
        return new PageImpl<>(items, pageable, page.getTotalElements());
    }

    /**
     * 사용자 역할 변경 (REVIEWER 의 사용자 관리 화면).
     *
     * <p>역할 분리 리팩토링 Phase 2 — 저작도구 소유 {@code LS_USER_ROLE} 만 갱신한다. 구 구조에서
     * 쓰던 관제 소유 권한 매핑 테이블(delete/insert) 및 {@code MNG_ACCT_USER.USE_YN}(UPDATE) 쓰기는
     * 제거됐다. 구 권한 테이블 2종은 참조가 0 이 된 뒤 V165 로 스키마에서도 삭제됐으므로,
     * 저작도구 인가 역할의 단일 진실원은 {@code LS_USER_ROLE} 하나다.
     *
     * <ul>
     *   <li>{@code role}: 제공된 경우만 변경. {@code LsUserRoleRepository.upsertRole} 원자 upsert 로
     *       동시성(PK race) 안전하게 부여/변경한다.</li>
     *   <li>{@code role} 미제공: 기존 LS 역할(없으면 null 미배정)을 그대로 응답한다.</li>
     * </ul>
     *
     * <p>보안: role 은 Controller/DTO {@code @Pattern} 화이트리스트(REVIEWER|WORKER|PORTAL_USER)로
     * 사전 검증된 값만 도달하며 native upsert 는 파라미터 바인딩만 사용한다 (SQL Injection 차단).
     * 역할 변경(특히 강등)은 감사 추적을 위해 변경 전/후 역할을 info 로그로 남긴다 (PII·토큰 미출력).
     */
    @Transactional("controlTransactionManager")
    public UserProfileResponse update(Long userNo, UserUpdateRequest req) {
        // 존재 여부 확인 — 갱신 전 검증으로 404 분기 및 응답 빌드용 baseline 확보.
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        String currentRole = lsUserRoleRepository.findByUserNo(userNo)
                .map(LsUserRole::getRoleCd)
                .orElse(null);

        String nextRole = currentRole;
        // 동일 역할 재적용은 skip — 불필요한 UPD_DT 갱신/Phase3 캐시 churn 방지(멱등).
        // LS 행이 없으면(currentRole=null) 신규 부여는 진행한다.
        if (req.role() != null && !req.role().equals(currentRole)) {
            lsUserRoleRepository.upsertRole(userNo, req.role());
            nextRole = req.role();
            // Phase 3 — 인가 역할 캐시 무효화. 커밋 후(AFTER_COMMIT)에 evict 하여 강등이 즉시(≤다음요청)
            // 반영되게 한다. 커밋 전 evict 는 동시 요청이 old value 를 재캐싱할 수 있어 금지.
            evictRoleCacheAfterCommit(userNo);
            // 감사 로그 — 강등 포함 변경 추적. userNo 는 Long, 역할은 화이트리스트 코드값(PII·토큰 없음).
            log.info("[User] role changed userNo={}, before={}, after={}", userNo, currentRole, nextRole);
        }

        // 응답 — 갱신된 role 반영. channel 은 변경 대상 사용자의 컨텍스트 정보가 없어 빈 값.
        return new UserProfileResponse(
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmail(),
                nextRole,
                ""
        );
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }

    /**
     * 역할 변경 후 인가 역할 캐시(userRole)를 트랜잭션 커밋 후 무효화한다.
     * 트랜잭션 동기화가 없는(테스트 등) 경우 즉시 evict 하여 fallback.
     */
    private void evictRoleCacheAfterCommit(Long userNo) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    userRoleResolver.evict(userNo);
                }
            });
        } else {
            userRoleResolver.evict(userNo);
        }
    }
}
