package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.entity.MngAcctUserAuthrt;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class UserService {

    private final UserRepository userRepository;

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
     */
    public Page<UserSummaryResponse> searchUsers(String keyword, Pageable pageable) {
        Page<MngAcctUser> page = userRepository.searchByKeyword(keyword, pageable);
        List<Long> userNos = page.getContent().stream().map(MngAcctUser::getUserNo).toList();
        Map<Long, String> roleByUserNo = new HashMap<>();
        if (!userNos.isEmpty()) {
            // 우선순위 정렬되어 반환되므로 putIfAbsent 로 첫 매핑 유지
            for (MngAcctUserAuthrt ua : userRepository.findAuthrtsByUserNos(userNos)) {
                roleByUserNo.putIfAbsent(ua.getId().getUserNo(), ua.getId().getAuthrtCd());
            }
        }
        return page.map(u -> UserSummaryResponse.from(u, roleByUserNo.getOrDefault(u.getUserNo(), "WORKER")));
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
