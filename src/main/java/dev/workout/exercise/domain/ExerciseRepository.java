package dev.workout.exercise.domain;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ExerciseRepository extends JpaRepository<Exercise, Long> {
  @Query(
      "select e from Exercise e where (e.userId is null or e.userId = :uid) and lower(e.name) like lower(concat('%',:q,'%')) order by e.category,e.name")
  List<Exercise> search(@Param("uid") long uid, @Param("q") String q, Pageable page);

  boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);
}
