package za.co.shoppingdiary.retailer.controller;

import lombok.RequiredArgsConstructor;
import za.co.shoppingdiary.retailer.entity.Retailer;
import za.co.shoppingdiary.retailer.repository.RetailerRepository;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/retailers")
@RequiredArgsConstructor
public class RetailerController {

    private final RetailerRepository repository;

    @GetMapping
    public List<Retailer> list() {
        return repository.findAll(Sort.by("name").ascending());
    }
}