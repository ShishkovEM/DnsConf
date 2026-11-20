package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.exception.ProfileSetupException;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.nonNull;

@Service
@RequiredArgsConstructor
public class NextDnsRewriteService {

    private final NextDnsRewriteClient nextDnsRewriteClient;
    private final ExcludeRedirectCheckService excludeRedirectCheckService;

    public Map<String, CreateRewriteDto> buildNewRewrites(List<BypassRoute> overrides) {
        Map<String, CreateRewriteDto> rewriteDtos = new HashMap<>();
        overrides.forEach(route -> rewriteDtos.putIfAbsent(route.website(), new CreateRewriteDto(route.website(), route.ip())));
        return rewriteDtos;
    }

    public List<CreateRewriteDto> cleanupOutdatedAndExcluded(Map<String, CreateRewriteDto> newRewriteRequests) {
        List<RewriteDto> existingRewrites = getExistingRewrites();

        List<String> outdatedIds = new ArrayList<>();
        List<String> ignoredIds = new ArrayList<>();

        for (RewriteDto existingRewrite : existingRewrites) {
            String domain = existingRewrite.name();
            String oldIp = existingRewrite.content();
            if (excludeRedirectCheckService.shouldExclude(domain)) {
                ignoredIds.add(existingRewrite.id());
                newRewriteRequests.remove(domain);
                continue;
            }
            CreateRewriteDto request = newRewriteRequests.get(domain);
            if (nonNull(request) && !request.content().equals(oldIp)) {
                outdatedIds.add(existingRewrite.id());
            } else {
                newRewriteRequests.remove(domain);
            }
        }
        newRewriteRequests.keySet().removeIf(excludeRedirectCheckService::shouldExclude);

        if (!outdatedIds.isEmpty()) {
            Log.io("Removing %s outdated rewrites from NextDNS".formatted(outdatedIds.size()));
            nextDnsRewriteClient.deleteRewritesByIds(outdatedIds);
        }
        if (!ignoredIds.isEmpty()) {
            Log.io("Removing %s excluded rewrites from NextDNS".formatted(ignoredIds.size()));
            nextDnsRewriteClient.deleteRewritesByIds(ignoredIds);
        }
        return List.copyOf(newRewriteRequests.values());
    }

    public List<RewriteDto> getExistingRewrites() {
        Log.io("Fetching existing rewrites from NextDNS");
        return nextDnsRewriteClient.fetchRewrites();
    }

    public List<CreateRewriteDto> prepareCompact(Map<String, CreateRewriteDto> active, Set<CreateRewriteDto> retired,
                                                Set<String> managedNames) {
        var existing = getExistingRewrites();
        var missing = new HashMap<>(active);
        List<String> obsolete = new ArrayList<>();
        Map<String, Set<String>> custom = new HashMap<>();
        for (var rule : existing) {
            var pair = new CreateRewriteDto(rule.name(), rule.content());
            if (pair.equals(active.get(rule.name()))) {
                missing.remove(rule.name());
            } else if (retired.contains(pair)) {
                obsolete.add(rule.id());
            } else {
                custom.computeIfAbsent(rule.name(), ignored -> new HashSet<>()).add(rule.content());
            }
        }

        // A more specific manual rewrite can mask an inherited GeoHide rule.
        for (String name : managedNames) {
            String domain = name;
            String override = null;
            while (true) {
                if (override == null && custom.containsKey(domain)) override = domain;
                var root = active.get(domain);
                if (root != null) {
                    if (override != null && !custom.get(override).equals(Set.of(root.content()))) {
                        throw new ProfileSetupException("Conflicting manual rewrite: " + override);
                    }
                    break;
                }
                int dot = domain.indexOf('.');
                if (dot < 0) throw new ProfileSetupException("Missing GeoHide parent for " + name);
                domain = domain.substring(dot + 1);
            }
        }
        int projected = existing.size() - obsolete.size() + missing.size();
        if (projected > 500) {
            throw new ProfileSetupException("GeoHide and retained rewrites need " + projected + " slots (limit 500)");
        }
        Log.io("GeoHide: %d existing, %d to retire, %d to add, %d after sync"
                .formatted(existing.size(), obsolete.size(), missing.size(), projected));
        if (!obsolete.isEmpty()) nextDnsRewriteClient.deleteRewritesByIds(obsolete);
        return List.copyOf(missing.values());
    }

    public void saveRewrites(List<CreateRewriteDto> createRewriteDtos) {
        Log.io("Saving %s new rewrites to NextDNS...".formatted(createRewriteDtos.size()));
        nextDnsRewriteClient.saveRewrites(createRewriteDtos);
    }

    public void removeAll() {
        Log.io("Fetching existing rewrites from NextDNS");
        List<RewriteDto> list = nextDnsRewriteClient.fetchRewrites();
        List<String> ids = list.stream().map(RewriteDto::id).toList();
        Log.io("Removing rewrites from NextDNS");
        nextDnsRewriteClient.deleteRewritesByIds(ids);
    }

}
