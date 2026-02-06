package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Device;

import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    @Query("SELECT d FROM Device d WHERE d.deviceId = :deviceId AND d.deviceAuthKey.id = :deviceAuthKeyId")
    Optional<Device> findByDeviceIdAndDeviceAuthKeyId(@Param("deviceId") String deviceId, @Param("deviceAuthKeyId") Long deviceAuthKeyId);
}
