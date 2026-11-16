package za.co.shoppingdiary.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import za.co.shoppingdiary.product.entity.Product;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByBarcode(String barcode);
    Optional<Product> findByCanonicalNameIgnoreCase(String canonicalName);
}