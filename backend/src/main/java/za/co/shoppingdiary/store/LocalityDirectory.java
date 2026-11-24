package za.co.shoppingdiary.store;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class LocalityDirectory {
    private final List<Entry> entries;

    public LocalityDirectory() throws IOException {
        try (var input = new BufferedReader(new InputStreamReader(
                new ClassPathResource("geography/za-localities.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            entries = input.lines().map(Entry::parse).toList();
        }
    }

    public List<Place> find(String query, String province) {
        String search = normalize(query);
        if (search.length() < 2 || search.length() > 100) return List.of();
        boolean postcode = search.matches("[0-9]{2,4}");
        return entries.stream()
                .filter(entry -> postcode == entry.place.kind.equals("POSTAL"))
                .filter(entry -> province == null || entry.place.province.equalsIgnoreCase(province))
                .filter(entry -> postcode ? entry.place.area.equals(search) : entry.search.contains(search))
                .sorted(Comparator.<Entry>comparingInt(entry -> entry.name.equals(search) ? 0 :
                                entry.name.startsWith(search) ? 1 : 2)
                        .thenComparing(Comparator.comparingLong(Entry::population).reversed())
                        .thenComparing(Entry::name).thenComparing(entry -> entry.place.id))
                .limit(20).map(Entry::place).toList();
    }

    public List<String> provinces() {
        return entries.stream().filter(entry -> entry.place.kind.equals("ADM1"))
                .map(entry -> entry.place.province).distinct().sorted().toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    public record Place(String id, String city, String province, String area, double lat, double lon, String kind) {}

    private record Entry(Place place, String name, String search, long population) {
        static Entry parse(String row) {
            String[] field = row.split("\t", -1);
            var place = new Place(field[0], field[1], field[2], field[3],
                    Double.parseDouble(field[4]), Double.parseDouble(field[5]), field[6]);
            return new Entry(place, normalize(place.city),
                    normalize(place.city + " " + place.province + " " + place.area + " " + field[8]),
                    Long.parseLong(field[7].isBlank() ? "0" : field[7]));
        }
    }
}
