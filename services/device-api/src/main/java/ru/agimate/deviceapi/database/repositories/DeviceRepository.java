package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    List<Device> findByUserPubId(UUID userPubId);

    Optional<Device> findByDeviceIdAndUserPubId(String deviceId, UUID userPubId);

    @Query("SELECT d FROM Device d WHERE d.deviceId = :deviceId AND d.deviceAuthKey.id = :deviceAuthKeyId")
    Optional<Device> findByDeviceIdAndDeviceAuthKeyId(@Param("deviceId") String deviceId, @Param("deviceAuthKeyId") Long deviceAuthKeyId);
}
