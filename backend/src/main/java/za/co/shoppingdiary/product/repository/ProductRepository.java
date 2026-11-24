package za.co.shoppingdiary.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import za.co.shoppingdiary.product.entity.Product;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByBarcode(String barcode);
    Optional<Product> findFirstByBarcodeIsNullAndCanonicalNameIgnoreCaseAndBrandIgnoreCaseAndSizeTextIgnoreCase(
            String canonicalName, String brand, String sizeText);

    List<Product> findTop2ByBarcodeIsNullAndCanonicalNameIgnoreCaseAndBrandIgnoreCaseAndSizeTextIgnoreCase(
            String canonicalName, String brand, String sizeText);

    @Query(value = """
        SELECT min(id) FROM categories WHERE parent_id IS NULL AND lower(name) = lower(:name)
        """, nativeQuery = true)
    Optional<Long> findDepartmentId(@Param("name") String name);

    @Modifying
    @Query("""
        UPDATE Product p SET p.imageUrl = :image, p.imageSourceUrl = :source, p.updatedAt = CURRENT_TIMESTAMP
        WHERE p.id = :id AND (p.imageUrl IS NULL OR trim(p.imageUrl) = '')
        """)
    int addImageIfMissing(@Param("id") long id, @Param("image") String image, @Param("source") String source);
}