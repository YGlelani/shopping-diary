package za.co.shoppingdiary.product;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import za.co.shoppingdiary.product.service.ProductSearchService.SearchTerms;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchTermsTest {
    @ParameterizedTest
    @CsvSource(value = {
        "Albany bread 700g|albany bread|null|700g",
        "FULL cream milk 2 L|full cream milk|null|2 l",
        "rice 2,5 kg|rice|null|2,5 kg",
        "0012345678901|''|0012345678901|null",
        "sugar-free|sugar free|null|null",
        "750ml|''|null|750ml"
    }, delimiter = '|', nullValues = "null")
    void recognisesBarcodeAndPackageTerms(String query, String text, String barcode, String size) {
        assertEquals(new SearchTerms(text, barcode, size), SearchTerms.parse(query));
    }
}
