package com.novibe.dns.next_dns;

import com.novibe.common.exception.ProfileSetupException;
import com.novibe.common.util.DataParser;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record GeoHideSnapshot(Map<String, CreateRewriteDto> active, Set<CreateRewriteDto> retired, Set<String> names) {
    static final String FILE = "hosts/geohide-eu.hosts";

    static GeoHideSnapshot read() {
        Map<String, CreateRewriteDto> active = new HashMap<>();
        Set<CreateRewriteDto> retired = new HashSet<>();
        Set<String> names = new HashSet<>();
        try {
            for (String line : Files.readAllLines(Path.of(FILE))) {
                boolean old = line.startsWith("# retire ");
                if (line.isBlank() || (line.startsWith("#") && !old)) continue;
                var entry = DataParser.parseHostsLine(old ? line.substring("# retire ".length()) : line);
                if (entry == null || !entry.hasIpAndDomain()) {
                    throw new ProfileSetupException("Invalid GeoHide hosts entry: " + line);
                }
                var pair = new CreateRewriteDto(entry.domain(), entry.ip());
                if (old) {
                    retired.add(pair);
                } else {
                    if (active.putIfAbsent(pair.name(), pair) != null) {
                        throw new ProfileSetupException("Duplicate GeoHide rule: " + pair.name());
                    }
                    names.add(pair.name());
                    String[] parts = line.split(" # covers ", 2);
                    if (parts.length == 2) names.addAll(List.of(parts[1].split(" ")));
                }
            }
        } catch (IOException e) {
            throw new ProfileSetupException("Cannot read " + FILE, e);
        }
        if (active.isEmpty() || active.size() > 500) {
            throw new ProfileSetupException("Invalid GeoHide rewrite count: " + active.size());
        }
        return new GeoHideSnapshot(active, retired, names);
    }
}
