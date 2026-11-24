package za.co.shoppingdiary.ingestion.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import za.co.shoppingdiary.product.entity.Product;
import za.co.shoppingdiary.product.repository.ProductRepository;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenFoodFactsServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final HttpClient http = mock(HttpClient.class);
    private final OpenFoodFactsService service = new OpenFoodFactsService(products, http);

    @BeforeEach
    void configureProvider() {
        ReflectionTestUtils.setField(service, "contactEmail", "catalogue@example.test");
    }

    @Test
    void cachesCandidatesAndOnlyWritesOnConfirmedImport() throws Exception {
        respond(200, """
            {"products": [
                {"code":"0012345678901","product_name":"Long Grain Rice","brands":"First Brand","quantity":"1 kg",
                 "countries_tags":["en:south-africa"],"categories_tags":["en:rices"],
                 "image_front_url":"https://images.openfoodfacts.org/test.jpg"},
                {"code":"0012345678902","product_name":"Long Grain Rice","brands":"Second Brand","quantity":"2 kg",
                 "countries_tags":["en:south-africa"],"image_front_url":"https://example.test/unrelated.jpg"}
            ]}
            """);
        var matches = service.search("rice", 20);
        assertEquals(2, matches.size());
        assertEquals("Pantry essentials", matches.getFirst().department());
        assertNull(matches.getLast().imageUrl());
        var first = service.previewBarcode("0012345678901");
        assertEquals(matches.getFirst(), first);
        assertEquals(matches, service.search(" RICE ", 20));
        verify(http, times(1)).send(any(HttpRequest.class), any());

        var barcodes = List.of("0012345678901", "0012345678902");
        var preview = service.bulkImport(new OpenFoodFactsService.BulkRequest(barcodes, true, false));
        assertEquals(2, preview.ready());
        verify(products, never()).saveAndFlush(any());

        var confirmed = new OpenFoodFactsService.BulkRequest(barcodes, false, true);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.bulkImport(confirmed)).getStatusCode().value());
        ReflectionTestUtils.setField(service, "writeEnabled", true);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.bulkImport(new OpenFoodFactsService.BulkRequest(barcodes, false, false)))
                .getStatusCode().value());
        when(products.saveAndFlush(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(Long.valueOf(product.getBarcode()));
            return product;
        });
        assertEquals(2, service.bulkImport(confirmed).imported());
        verify(products).saveAndFlush(argThat(product ->
                first.sourceUrl().equals(product.getCatalogueSourceUrl())
                && first.sourceUrl().equals(product.getImageSourceUrl())
                && "1 kg".equals(product.getSizeText())));
    }

    @Test
    void pausesNewProviderRequestsAfterAServiceFailure() throws Exception {
        respond(503, "");
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.search("rice", 10)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.previewBarcode("0012345678901")).getStatusCode().value());
        verify(http, times(1)).send(any(HttpRequest.class), any());
        verifyNoInteractions(products);
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
}
