package kr.co.cudo.authoring.project.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.project.dto.ProjectResponse;
import kr.co.cudo.authoring.project.dto.ProjectSummaryResponse;
import kr.co.cudo.authoring.project.entity.LsPjt;
import kr.co.cudo.authoring.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectResponse getProject(Long pjtId) {
        LsPjt project = projectRepository.findById(pjtId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
        return ProjectResponse.from(project);
    }

    public Page<ProjectSummaryResponse> listActiveProjects(Pageable pageable) {
        return projectRepository.findByUseYn("Y", pageable).map(ProjectSummaryResponse::from);
    }
}
