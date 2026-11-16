package za.co.shoppingdiary.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import za.co.shoppingdiary.store.entity.Store;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {
    Optional<Store> findByRetailerIdAndStoreCode(Long retailerId, String storeCode);
}