package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.Icd10;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface Icd10Repository extends JpaRepository<Icd10, String> {

    @Query("""
            from Icd10 i where i.name like concat('%', :kw, '%')
              or i.code like concat(:kw, '%')
              or upper(i.pinyin) like upper(concat(:kw, '%'))
            order by i.code
            """)
    List<Icd10> search(@Param("kw") String keyword, Pageable pageable);
}
