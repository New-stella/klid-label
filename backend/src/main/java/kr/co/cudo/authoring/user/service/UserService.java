package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.UserUpdateRequest;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.entity.MngAcctUserAuthrt;
import kr.co.cudo.authoring.user.repository.MngAcctUserAuthrtRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final MngAcctUserAuthrtRepository userAuthrtRepository;

    public UserProfileResponse getProfile(TokenClaims claims) {
        Long userNo = parseUserNo(claims.sub());
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        return UserProfileResponse.of(user, claims.role().name(), claims.channel().name());
    }

    public UserProfileResponse getById(Long userNo, TokenClaims claims) {
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        return UserProfileResponse.of(user, claims.role().name(), claims.channel().name());
    }

    public List<WorkerSummaryResponse> listWorkersWithTaskCount() {
        return userRepository.findAllWorkersWithTaskCount().stream()
                .map(WorkerSummaryResponse::from)
                .toList();
    }

    /**
     * 사용자 마스터 페이징 검색 (/v1/users) — REVIEWER 의 사용자 관리 화면.
     * 권한 코드는 N+1 방지를 위해 페이지 결과의 userNo 목록을 한 번에 조회한 뒤 매핑한다.
     *
     * <p>role 필터(선택): null/blank 이면 모두 반환, 값이 있으면 해당 역할만 반환.
     * 메모리 필터 — 정확한 페이징을 위해선 SQL JOIN 필터가 이상적이나 작업자 모수가 적어 실용상 OK.
     * Controller 단계에서 @Pattern 화이트리스트로 사전 검증된 값만 도달 (SQL Injection 차단).
     */
    public Page<UserSummaryResponse> searchUsers(String keyword, String role, Pageable pageable) {
        Page<MngAcctUser> page = userRepository.searchByKeyword(keyword, pageable);
        List<Long> userNos = page.getContent().stream().map(MngAcctUser::getUserNo).toList();
        Map<Long, String> roleByUserNo = new HashMap<>();
        if (!userNos.isEmpty()) {
            // 우선순위 정렬되어 반환되므로 putIfAbsent 로 첫 매핑 유지
            for (MngAcctUserAuthrt ua : userRepository.findAuthrtsByUserNos(userNos)) {
                roleByUserNo.putIfAbsent(ua.getId().getUserNo(), ua.getId().getAuthrtCd());
            }
        }

        Stream<MngAcctUser> filtered = page.getContent().stream();
        if (role != null && !role.isBlank()) {
            filtered = filtered.filter(u -> role.equals(roleByUserNo.getOrDefault(u.getUserNo(), "WORKER")));
        }
        List<UserSummaryResponse> items = filtered
                .map(u -> UserSummaryResponse.from(u, roleByUserNo.getOrDefault(u.getUserNo(), "WORKER")))
                .toList();

        return new PageImpl<>(items, pageable, items.size());
    }

    /**
     * 사용자 활성/비활성 + 역할 변경 (REVIEWER 의 사용자 관리 화면).
     *
     * <p>두 필드 모두 선택 — null 이면 해당 필드는 변경하지 않는다.
     * <ul>
     *   <li>{@code useYn}: {@code @Modifying} UPDATE — 엔티티가 {@code @Immutable} 이므로 dirty checking 불가.</li>
     *   <li>{@code role}: 권한 매핑 테이블({@code MNG_ACCT_USER_AUTHRT})을 delete → insert 로 교체.</li>
     * </ul>
     *
     * <p>보안: useYn/role 은 Controller 단계에서 {@code @Pattern} 화이트리스트(Y|N, REVIEWER|WORKER|PORTAL_USER)로
     * 사전 검증된 값만 도달한다. 네이티브 INSERT 도 파라미터 바인딩만 사용 (SQL Injection 차단).
     *
     * <p>본인(actor) 이 본인의 활성/역할을 변경하는 경우 — 현재는 허용. 정책 결정 시 사전 차단 추가 가능.
     */
    @Transactional("controlTransactionManager")
    public UserProfileResponse update(Long userNo, UserUpdateRequest req) {
        // 존재 여부 확인 — 갱신 전 검증으로 404 분기 및 응답 빌드용 baseline 확보.
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        // 1. 활성/비활성 상태 변경 (useYn 제공된 경우만).
        String nextUseYn = user.getUseYn();
        if (req.useYn() != null) {
            int affected = userRepository.updateUseYn(userNo, req.useYn());
            if (affected == 0) {
                throw new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다.");
            }
            nextUseYn = req.useYn();
            log.info("[User] useYn updated userNo={}, useYn={}", userNo, req.useYn());
        }

        // 2. 역할 변경 (role 제공된 경우만) — 기존 매핑 전체 삭제 후 단일 매핑 insert.
        String nextRole = null;
        if (req.role() != null) {
            userAuthrtRepository.deleteByUserNo(userNo);
            userAuthrtRepository.insertAuthrt(userNo, req.role(), LocalDateTime.now());
            nextRole = req.role();
            log.info("[User] role updated userNo={}, role={}", userNo, req.role());
        } else {
            // role 변경이 없으면 기존 매핑 중 우선순위 최상위 1건을 조회 (응답 채우기 용).
            List<MngAcctUserAuthrt> existing = userRepository.findAuthrtsByUserNos(List.of(userNo));
            nextRole = existing.isEmpty() ? "WORKER" : existing.get(0).getId().getAuthrtCd();
        }

        // 응답 — 갱신된 useYn/role 반영. channel 은 변경 대상 사용자의 컨텍스트 정보가 없어 빈 값.
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
}
