package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.SheetRow;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SheetRowRepository extends JpaRepository<SheetRow, UUID> {

    List<SheetRow> findBySheetIdAndIdIn(UUID sheetId, Collection<UUID> ids);

    long deleteBySheetIdAndIdIn(UUID sheetId, Collection<UUID> ids);

    long countBySheetId(UUID sheetId);

    /** Row counts for all of an agent's sheets at once — otherwise the listing is N+1. */
    @Query("select r.sheetId, count(r) from SheetRow r where r.sheetId in :sheetIds group by r.sheetId")
    List<Object[]> countRowsBySheetIds(@Param("sheetIds") Collection<UUID> sheetIds);
}
