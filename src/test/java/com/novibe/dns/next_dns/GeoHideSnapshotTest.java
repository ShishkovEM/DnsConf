package com.novibe.dns.next_dns;

import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import com.novibe.dns.next_dns.service.NextDnsRewriteService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeoHideSnapshotTest {
    @Test
    void migratesPopulatedProfileAndSecondRunMakesNoChanges() {
        var snapshot = GeoHideSnapshot.read();
        var api = mock(NextDnsRewriteClient.class);
        var service = new NextDnsRewriteService(api, mock());
        var profile = new ArrayList<RewriteDto>();
        Stream.concat(snapshot.retired().stream(), snapshot.active().values().stream())
                .limit(500).forEach(pair -> profile.add(new RewriteDto("id" + profile.size(), pair.name(), pair.content())));
        when(api.fetchRewrites()).thenAnswer(call -> List.copyOf(profile));
        doAnswer(call -> {
            Set<String> ids = new HashSet<>(call.<List<String>>getArgument(0));
            profile.removeIf(record -> ids.contains(record.id()));
            return null;
        }).when(api).deleteRewritesByIds(any());

        var missing = service.prepareCompact(snapshot.active(), snapshot.retired(), snapshot.names());
        missing.forEach(pair -> profile.add(new RewriteDto("new" + profile.size(), pair.name(), pair.content())));
        assertEquals(snapshot.active().size(), profile.size());
        for (var row : profile) {
            assertEquals(new CreateRewriteDto(row.name(), row.content()), snapshot.active().get(row.name()));
        }
        clearInvocations(api);
        assertTrue(service.prepareCompact(snapshot.active(), snapshot.retired(), snapshot.names()).isEmpty());
        verify(api, never()).deleteRewritesByIds(any());
    }
}
