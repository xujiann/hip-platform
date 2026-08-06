package cn.hip.bureau.repository;

import cn.hip.bureau.entity.CaseFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseFileRepository extends JpaRepository<CaseFile, Long> {

    List<CaseFile> findTop200ByOrderByIdDesc();

    List<CaseFile> findByStatusOrderByIdDesc(String status);

    long countByCaseNoStartingWith(String prefix);
}
