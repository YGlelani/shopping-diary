package za.co.shoppingdiary.retailer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import za.co.shoppingdiary.retailer.entity.Retailer;

import java.util.Optional;

public interface RetailerRepository extends JpaRepository<Retailer, Long> {
    Optional<Retailer> findBySlug(String slug);
}