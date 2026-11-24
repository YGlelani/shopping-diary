package za.co.shoppingdiary.ingestion.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import za.co.shoppingdiary.ingestion.automation.GroceryAutomation;

@Service
@RequiredArgsConstructor
public class LoyaltyHubService {
    private final GroceryAutomation automation;

    public void syncSearchResults(String searchQuery) {
        automation.price("LOYALTYHUB",searchQuery,false);
    }
}
