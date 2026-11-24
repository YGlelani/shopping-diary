package za.co.shoppingdiary.list.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import za.co.shoppingdiary.budget.GroceryBudgetService;
import za.co.shoppingdiary.list.dto.*;
import za.co.shoppingdiary.list.dto.OptimizationPlan.MissingItem;
import za.co.shoppingdiary.product.entity.Product;
import za.co.shoppingdiary.product.repository.ProductRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BasketOptimizerService {
    private static final int SPLIT_CANDIDATES = 24;
    private final JdbcClient jdbc;
    private final GroceryBudgetService budgets;
    private final ProductRepository products;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxPriceAgeDays;

    record Branch(long id, long retailerId, String name, String retailer, String address,
                  double lat, double lon, double distanceKm) {}
    record Entry(long id, Long productId, String name, BigDecimal quantity, String unit, Long substituteProductId) {}
    record Offer(long branchId, long itemId, long productId, String name, String sizeText, String imageUrl,
                 BigDecimal total, long observationId, OffsetDateTime observedAt, String verificationStatus,
                 String sourceType, LocalDate promotionEndsOn, String priceType, boolean alternatives,
                 Long promotionId,String promotionTerms,BigDecimal shelfTotal) {}
    record Candidate(Branch branch, Map<Long, Offer> offers) {}
    record Choice(List<Branch> stores, Map<Long, Offer> assignments, BigDecimal grocery, BigDecimal transport,
                  double roundTripKm, int substitutions, int unverified, boolean preferred) {
        BigDecimal knownCost() {
            return transport == null ? grocery : grocery.add(transport);
        }
    }

    @Transactional(readOnly = true, timeout = 15)
    public OptimizeResponse optimize(Long userId, Long listId, OptimizeRequest request) {
        var list = jdbc.sql("""
            SELECT revision,budget_amount FROM shopping_lists WHERE id=:list AND user_id=:owner
            """).param("owner",userId).param("list",listId).query(ListSettings.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"List not found"));
        var budget = money(budget(userId,listId,request,list.budgetAmount()));
        var entries = jdbc.sql("""
            SELECT li.id,li.product_id,coalesce(p.canonical_name,li.free_text,'Unlinked item') AS name,
                   li.quantity,li.unit,CASE WHEN li.flexible_brand THEN li.substitute_product_id END AS substitute_product_id
            FROM shopping_list_items li LEFT JOIN products p ON p.id=li.product_id
            WHERE li.list_id=:list AND NOT li.purchased ORDER BY li.id
            """).param("list",listId).query(Entry.class).list();
        if (entries.size() > 500) throw new IllegalArgumentException("Optimise up to 500 remaining list entries at a time");

        var point = new MapSqlParameterSource("lat",request.getLat()).addValue("lon",request.getLon())
                .addValue("radius",request.getRadiusKm()*1000);
        var directory = jdbc.sql("""
            WITH origin AS (SELECT ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography AS point)
            SELECT s.id,s.retailer_id,s.name,r.name as retailer,concat_ws(', ',s.address,s.city,s.province) AS address,
                   ST_Y(s.location) AS lat,ST_X(s.location) AS lon,
                   ST_Distance(s.location::geography,origin.point)/1000 AS distance_km
            FROM stores s JOIN retailers r ON r.id=s.retailer_id CROSS JOIN origin
            WHERE s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
              AND ST_DWithin(s.location::geography,origin.point,:radius)
            ORDER BY distance_km,s.id
            """).paramSource(point).query(Branch.class).list();
        var eligible = directory.stream().filter(branch -> !request.getExcludedRetailers().contains(branch.retailerId())).toList();
        var permitted = compatibleAlternatives(entries,request);
        var offers = eligible.isEmpty() || entries.isEmpty() ? List.<Offer>of()
                : offers(listId,eligible,permitted,request);
        var evidence = missingEvidence(entries,offers,eligible);
        var exact = candidates(eligible,offers,false);
        var flexible = candidates(eligible,offers,true);
        var split = search(exact,entries,request,request.getMaxStores());
        var single = split.get(1);
        var alternatives = request.isAllowAlternatives() ? search(flexible,entries,request,request.getMaxStores()) : Map.<Integer,Choice>of();
        var splitChoice = best(split.values().stream().filter(choice -> choice.stores().size()>1).toList());
        var flexibleChoice = best(alternatives.values());
        var one = plan("ONE_STORE",single,entries,exact,budget,null,evidence);
        var two = plan("SPLIT",split.get(2),entries,exact,budget,null,evidence);
        var three = plan("SPLIT",split.get(3),entries,exact,budget,null,evidence);
        var multi = plan("SPLIT",splitChoice,entries,exact,budget,null,evidence);
        var alternative = plan("ALTERNATIVES",flexibleChoice,entries,flexible,budget,exact,evidence);
        var bestExact = best(split.values());
        var winner = best(java.util.stream.Stream.of(bestExact,flexibleChoice).filter(Objects::nonNull).toList());
        var recommendation = winner == null ? plan("NO_PLAN",null,entries,exact,budget,null,evidence)
                : winner == flexibleChoice && flexibleChoice.substitutions()>0 ? alternative
                : winner.stores().size()==1 ? one : multi;
        BigDecimal baseline = one.getGrandTotal();
        BigDecimal combined = recommendation.getGrandTotal();
        BigDecimal savings = baseline == null || combined == null ? null : baseline.subtract(combined).max(BigDecimal.ZERO);
        BigDecimal percent = savings == null || baseline.signum()==0 ? null
                : savings.multiply(BigDecimal.valueOf(100)).divide(baseline,1,RoundingMode.HALF_UP);
        var response = new OptimizeResponse(recommendation,one,two,three,savings,percent,
                request.getCostPerKm()==null ? 0 : request.getCostPerKm().doubleValue(),request.getRadiusKm(),
                entries.size(),(int) entries.stream().filter(entry -> entry.productId()==null).count());
        response.setBestSplit(multi);
        response.setBestAlternatives(alternative);
        response.setBudgetAmount(budget);
        response.setSearchLimited(request.getMaxStores()>1 && Math.max(exact.size(),flexible.size())>SPLIT_CANDIDATES);
        response.setBranchesSearched(eligible.size());
        response.setListRevision(list.revision());
        response.setCalculatedAt(OffsetDateTime.now());
        response.setBranches(eligible.stream().map(branch -> branchView(branch,null)).toList());
        response.setAssumptions(request);
        return response;
    }

    private record ListSettings(long revision,BigDecimal budgetAmount) {}

    private BigDecimal budget(long owner,long list,OptimizeRequest request,BigDecimal legacy) {
        if (request.getBudgetId()==null)
            return request.getBudgetAmount()==null ? legacy : request.getBudgetAmount();
        if (request.getBudgetAmount()!=null) throw new IllegalArgumentException("Choose a saved budget or enter an amount");
        return budgets.availableFunds(owner,list,request.getBudgetId()).stream().findFirst()
                .map(GroceryBudgetService.AvailableFunds::remaining)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Active budget is not linked to this list"));
    }

    private List<Long> compatibleAlternatives(List<Entry> entries,OptimizeRequest request) {
        if (!request.isAllowAlternatives()) return List.of();
        var flexible = entries.stream().filter(entry -> entry.productId()!=null && entry.substituteProductId()!=null
                && !request.getRejectedAlternatives().contains(entry.id())).toList();
        if (flexible.isEmpty()) return List.of();
        var ids = new HashSet<Long>();
        flexible.forEach(entry -> {
            ids.add(entry.productId());
            ids.add(entry.substituteProductId());
        });
        var catalogue = new HashMap<Long,Product>();
        products.findAllById(ids).forEach(product -> catalogue.put(product.getId(),product));
        return flexible.stream().filter(entry -> ShoppingListService.comparable(
                        catalogue.get(entry.productId()),catalogue.get(entry.substituteProductId())))
                .map(Entry::id).toList();
    }

    private List<Offer> offers(long list,List<Branch> branches,List<Long> permitted,OptimizeRequest request) {
        return jdbc.sql("""
            SELECT s.id AS branch_id,li.id AS item_id,p.id AS product_id,p.canonical_name AS name,
                   p.size_text,p.image_url,offer.total,ob.id AS observation_id,price.observed_at,
                   price.verification_status,price.source_type,offer.promotion_ends_on,offer.price_type,mode.alternatives,
                   offer.promotion_id,coalesce(pr.description,
                       CASE pr.promo_type
                         WHEN 'MULTIBUY' THEN concat('Buy ',pr.min_quantity,' for R',pr.promo_price)
                         WHEN 'BOGOF' THEN concat('Buy ',pr.min_quantity,', get ',pr.free_quantity,' free')
                         WHEN 'PERCENT' THEN concat(pr.percent_off,' percent off')
                         WHEN 'MEMBER' THEN concat(pr.loyalty_program,' required')
                         WHEN 'DISCOUNT' THEN concat('Minimum ',coalesce(pr.min_quantity,1),' package(s)')
                       END) AS promotion_terms,offer.shelf_total
            FROM shopping_list_items li
            CROSS JOIN stores s
            CROSS JOIN (VALUES (false),(true)) mode(alternatives)
            CROSS JOIN LATERAL shopping_list_item_quote(
                CASE WHEN mode.alternatives AND li.id IN (:permitted)
                     THEN li ELSE jsonb_populate_record(li,'{"substitute_product_id":null}'::jsonb) END,
                s.id,:age,s.retailer_id IN (:loyalty),:ignorePromotions) offer
            JOIN products p ON p.id=offer.product_id
            CROSS JOIN LATERAL eligible_branch_observation(p.id,s.id,:age) price
            CROSS JOIN LATERAL (SELECT price.id) ob
            LEFT JOIN promotions pr ON pr.id=offer.promotion_id
            WHERE li.list_id=:list AND NOT li.purchased AND li.product_id IS NOT NULL AND s.id IN (:branches)
              AND (:flexible OR NOT mode.alternatives)
            """).param("list",list).param("age",maxPriceAgeDays).param("flexible",request.isAllowAlternatives()).param("ignorePromotions",request.isIgnorePromotions())
                .param("loyalty",request.getConfirmedLoyaltyRetailers().isEmpty() ? Set.of(-1L) : request.getConfirmedLoyaltyRetailers())
                .param("branches",branches.stream().map(Branch::id).toList())
                .param("permitted",permitted.isEmpty() ? List.of(-1L) : permitted)
                .query(Offer.class).list();
    }

    private record Evidence(long itemId,String reason) {}

    private Map<Long,String> missingEvidence(List<Entry> entries,List<Offer> offers,List<Branch> branches) {
        var offered = new HashSet<Long>();
        offers.forEach(offer -> offered.add(offer.itemId()));
        var unknown = entries.stream().filter(entry -> entry.productId()!=null && !offered.contains(entry.id()))
                .map(Entry::id).toList();
        if (unknown.isEmpty()) return Map.of();
        if (branches.isEmpty()) {
            return unknown.stream().collect(java.util.stream.Collectors.toMap(id -> id,id -> "NO_NEARBY_BRANCHES"));
        }
        var rows = jdbc.sql("""
            SELECT li.id AS item_id,
                   CASE WHEN bool_or(current_observation AND verification_status NOT IN ('UNVERIFIED','VERIFIED'))
                             THEN 'REJECTED_OBSERVATION'
                        WHEN bool_or(current_observation AND stock_status='OUT_OF_STOCK')
                             THEN 'OUT_OF_STOCK_OBSERVATION'
                        WHEN bool_or(current_observation AND stock_status IS DISTINCT FROM 'IN_STOCK')
                             THEN 'UNKNOWN_STOCK_OBSERVATION'
                        WHEN bool_or(NOT current_observation) THEN 'STALE_OBSERVATION'
                        ELSE 'NO_ELIGIBLE_OFFER' END AS reason
            FROM shopping_list_items li CROSS JOIN stores s
            CROSS JOIN LATERAL (
                SELECT po.verification_status,po.stock_status,po.observed_at>=CURRENT_TIMESTAMP-make_interval(days =>
                           least(:age,CASE WHEN po.source_type IN ('COMMUNITY','RECEIPT','BROCHURE') THEN 3 ELSE :age END))
                       AS current_observation
                FROM price_observations po
                WHERE po.product_id=li.product_id AND po.store_id=s.id AND po.observed_at<=CURRENT_TIMESTAMP
                ORDER BY po.observed_at DESC,po.id DESC LIMIT 1
            ) latest
            WHERE li.id IN (:items) AND s.id IN (:branches)
            GROUP BY li.id
            """).param("items",unknown).param("age",maxPriceAgeDays)
                .param("branches",branches.stream().map(Branch::id).toList()).query(Evidence.class).list();
        return rows.stream().collect(java.util.stream.Collectors.toMap(Evidence::itemId,Evidence::reason));
    }

    private static List<Candidate> candidates(List<Branch> branches,List<Offer> offers,boolean alternatives) {
        var byBranch = new HashMap<Long,Map<Long,Offer>>();
        for (Offer offer : offers) if (offer.alternatives()==alternatives) {
            byBranch.computeIfAbsent(offer.branchId(),id -> new HashMap<>()).put(offer.itemId(),offer);
        }
        return branches.stream().filter(branch -> byBranch.containsKey(branch.id()))
                .map(branch -> new Candidate(branch,byBranch.get(branch.id()))).toList();
    }

    private static final Comparator<Choice> RANK = Comparator
            .comparingInt((Choice choice) -> choice.assignments().size()).reversed()
            .thenComparing(Choice::knownCost)
            .thenComparingInt(choice -> choice.stores().size())
            .thenComparingInt(Choice::substitutions)
            .thenComparing(Choice::preferred,Comparator.reverseOrder())
            .thenComparingInt(Choice::unverified)
            .thenComparingDouble(Choice::roundTripKm)
            .thenComparing(choice -> choice.stores().stream().map(Branch::id).toList().toString());

    private static Choice best(Collection<Choice> choices) {
        return choices.stream().min(RANK).orElse(null);
    }

    static Map<Integer,Choice> search(List<Candidate> candidates,List<Entry> entries,OptimizeRequest request,int max) {
        var best = new HashMap<Integer,Choice>();
        var singles = new ArrayList<Map.Entry<Candidate,Choice>>();
        for (Candidate candidate : candidates) {
            var option = evaluate(List.of(candidate),entries,request);
            keep(best,option);
            singles.add(Map.entry(candidate,option));
        }
        if (max==1) return best;
        // Rank the split shortlist by standalone coverage and known cost.
        singles.sort(Map.Entry.comparingByValue(RANK));
        var selected = new LinkedHashSet<Candidate>();
        var covered = new HashSet<Long>();
        for (var single : singles) {
            var candidate = single.getKey();
            if (!covered.containsAll(candidate.offers().keySet())) {
                selected.add(candidate);
                covered.addAll(candidate.offers().keySet());
                if (selected.size()==SPLIT_CANDIDATES) break;
            }
        }
        for (var single : singles) {
            if (selected.size()==SPLIT_CANDIDATES) break;
            selected.add(single.getKey());
        }
        var shortlist = List.copyOf(selected);
        for (int i=0;i<shortlist.size();i++) {
            for (int j=i+1;j<shortlist.size();j++) {
                keep(best,evaluate(List.of(shortlist.get(i),shortlist.get(j)),entries,request));
                if (max==3) for (int k=j+1;k<shortlist.size();k++) {
                    keep(best,evaluate(List.of(shortlist.get(i),shortlist.get(j),shortlist.get(k)),entries,request));
                }
            }
        }
        return best;
    }

    private static void keep(Map<Integer,Choice> best,Choice choice) {
        best.merge(choice.stores().size(),choice,(previous,current) -> RANK.compare(previous,current)<=0 ? previous : current);
    }

    private static final Comparator<Offer> OFFER_ORDER = Comparator.comparing(Offer::total)
            .thenComparing(offer -> !"VERIFIED".equals(offer.verificationStatus()))
            .thenComparing(Offer::observedAt,Comparator.reverseOrder());

    private static Choice evaluate(List<Candidate> subset,List<Entry> entries,OptimizeRequest request) {
        var picked = new LinkedHashMap<Long,Offer>();
        var used = new HashSet<Long>();
        var cost = BigDecimal.ZERO;
        int substitutes=0;
        int unverified=0;
        for (Entry entry : entries) {
            Offer best = null;
            for (Candidate candidate : subset) {
                Offer offer = candidate.offers().get(entry.id());
                if (offer!=null && (best==null || OFFER_ORDER.compare(offer,best)<0)) best=offer;
            }
            if (best==null) continue;
            picked.put(entry.id(),best);
            used.add(best.branchId());
            cost=cost.add(best.total());
            if (best.productId()!=entry.productId()) substitutes++;
            if (!"VERIFIED".equals(best.verificationStatus())) unverified++;
        }
        var stores = subset.stream().map(Candidate::branch).filter(branch -> used.contains(branch.id())).toList();
        double km = route(stores,request);
        var transport = switch (request.getTravelMode()) {
            case WALK -> BigDecimal.ZERO;
            case FIXED, PUBLIC_TRANSPORT -> request.getTripCost();
            case DRIVE -> request.getCostPerKm()==null ? null : BigDecimal.valueOf(km).multiply(request.getCostPerKm()).setScale(2,RoundingMode.HALF_UP);
            case UNKNOWN -> request.getCostPerKm()==null ? null : BigDecimal.valueOf(km).multiply(request.getCostPerKm()).setScale(2,RoundingMode.HALF_UP);
        };
        return new Choice(stores,picked,cost,money(transport),km,substitutes,unverified,
                stores.stream().anyMatch(branch -> Objects.equals(branch.retailerId(),request.getPreferredRetailer())));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount==null ? null : amount.setScale(2,RoundingMode.HALF_UP);
    }

    private static double route(List<Branch> stores,OptimizeRequest request) {
        if (stores.isEmpty()) return 0;
        var origin = new Branch(0,0,"","","",request.getLat(),request.getLon(),0);
        return routeFrom(origin,origin,stores);
    }

    private static double routeFrom(Branch origin,Branch at,List<Branch> remaining) {
        if (remaining.isEmpty()) return distance(at,origin);
        double shortest = Double.POSITIVE_INFINITY;
        for (Branch next : remaining) {
            var rest = remaining.stream().filter(branch -> branch.id()!=next.id()).toList();
            shortest = Math.min(shortest,distance(at,next)+routeFrom(origin,next,rest));
        }
        return shortest;
    }

    private static double distance(Branch a,Branch b) {
        double h = Math.pow(Math.sin(Math.toRadians(b.lat()-a.lat())/2),2)
                + Math.cos(Math.toRadians(a.lat()))*Math.cos(Math.toRadians(b.lat()))
                * Math.pow(Math.sin(Math.toRadians(b.lon()-a.lon())/2),2);
        return 12742*Math.asin(Math.sqrt(Math.min(1,h)));
    }

    private static PlanStore branchView(Branch branch,BigDecimal subtotal) {
        var store = new PlanStore(branch.id(),branch.name(),branch.retailer(),Math.round(branch.distanceKm()*100)/100.0,null,subtotal);
        store.setAddress(branch.address());
        return store;
    }

    private static OptimizationPlan plan(String strategy,Choice choice,List<Entry> entries,List<Candidate> candidates,
                                         BigDecimal budget,List<Candidate> exact,Map<Long,String> evidence) {
        var missing = new ArrayList<MissingItem>();
        var items = new ArrayList<PlanItem>();
        var subtotals = new LinkedHashMap<Long,BigDecimal>();
        var stores = new ArrayList<PlanStore>();
        var quoted = new HashSet<Long>();
        for (Candidate candidate : candidates) quoted.addAll(candidate.offers().keySet());
        for (Entry entry : entries) {
            Offer offer = choice==null ? null : choice.assignments().get(entry.id());
            if (offer==null) {
                String reason = entry.productId()==null ? "UNLINKED_ITEM" : quoted.contains(entry.id())
                        ? "OUTSIDE_SELECTED_BRANCHES" : evidence.getOrDefault(entry.id(),"NO_ELIGIBLE_OFFER");
                missing.add(new MissingItem(entry.id(),entry.name(),entry.quantity(),entry.unit(),reason));
                continue;
            }
            var branch = choice.stores().stream().filter(store -> store.id()==offer.branchId()).findFirst().orElseThrow();
            subtotals.merge(branch.id(),offer.total(),BigDecimal::add);
            items.add(line(entry,offer,branch,exact));
        }
        if (choice!=null) choice.stores().forEach(branch -> stores.add(branchView(branch,subtotals.get(branch.id()))));
        var grocery = items.isEmpty() ? null : choice.grocery();
        var travel = items.isEmpty() ? null : choice.transport();
        var known = items.isEmpty() ? null : choice.knownCost();
        var combined = missing.isEmpty() && travel!=null ? known : null;
        var balance = budget==null || known==null ? null : budget.subtract(known);
        String status;
        if (entries.isEmpty()) status="EMPTY";
        else if (items.isEmpty()) status="NO_FEASIBLE_PLAN";
        else if (balance!=null && balance.signum()<0) status=combined==null ? "KNOWN_COSTS_OVER_BUDGET" : "OVER_BUDGET";
        else if (!missing.isEmpty()) status="INCOMPLETE";
        else if (travel==null) status="TRANSPORT_UNKNOWN";
        else status=budget==null ? "NO_BUDGET" : "WITHIN_BUDGET";
        var result = new OptimizationPlan(stores,items,grocery,travel,combined,missing.size());
        result.setStrategy(strategy);
        result.setBudgetStatus(status);
        result.setKnownCost(known);
        result.setBudgetDifference(balance);
        result.setUnresolved(missing);
        if (choice!=null) result.setRoundTripKm(Math.round(choice.roundTripKm()*100)/100.0);
        return result;
    }

    private static PlanItem line(Entry entry,Offer offer,Branch branch,List<Candidate> exact) {
        var line = new PlanItem(offer.productId(),offer.name(),entry.quantity(),branch.id(),branch.name(),
                offer.total().divide(entry.quantity(),2,RoundingMode.HALF_UP),offer.total());
        line.setItemId(entry.id());
        line.setRequestedProductId(entry.productId());
        line.setSizeText(offer.sizeText());
        line.setImageUrl(offer.imageUrl());
        line.setObservationId(offer.observationId());
        line.setObservedAt(offer.observedAt());
        line.setVerificationStatus(offer.verificationStatus());
        line.setSourceType(offer.sourceType());
        line.setPromotionEndsOn(offer.promotionEndsOn());
        line.setPriceType(offer.priceType());
        line.setPromotionId(offer.promotionId());
        line.setPromotionTerms(offer.promotionTerms());
        line.setShelfLineTotal(offer.shelfTotal());
        if (entry.productId()!=offer.productId()) {
            line.setSubstitutedFor(entry.name());
            if (exact!=null) exact.stream().filter(store -> store.branch().id()==branch.id())
                    .map(store -> store.offers().get(entry.id())).filter(Objects::nonNull).findFirst()
                    .ifPresent(original -> line.setExactLineTotal(original.total()));
        }
        return line;
    }

}
