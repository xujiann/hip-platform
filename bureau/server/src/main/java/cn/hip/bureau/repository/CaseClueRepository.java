package cn.hip.bureau.repository;

import cn.hip.bureau.entity.CaseClue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseClueRepository extends JpaRepository<CaseClue, Long> {

    List<CaseClue> findTop200ByOrderByIdDesc();

    List<CaseClue> findByStatusOrderByIdDesc(String status);

    long countByClueNoStartingWith(String prefix);
}
