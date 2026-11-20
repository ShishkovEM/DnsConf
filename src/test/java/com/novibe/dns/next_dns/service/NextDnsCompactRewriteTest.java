package com.novibe.dns.next_dns.service;

import com.novibe.common.exception.ProfileSetupException;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NextDnsCompactRewriteTest {
    final NextDnsRewriteClient api = mock();
    final NextDnsRewriteService service = new NextDnsRewriteService(api, mock(ExcludeRedirectCheckService.class));
    final CreateRewriteDto parent = new CreateRewriteDto("example.test", "1.1.1.1");
    final CreateRewriteDto oldChild = new CreateRewriteDto("api.example.test", "2.2.2.2");

    @Test
    void retiresOnlyMatchingPairsAndKeepsOtherRules() {
        when(api.fetchRewrites()).thenReturn(List.of(
                new RewriteDto("old", oldChild.name(), oldChild.content()),
                new RewriteDto("manual", "home.test", "192.168.1.1")));
        assertEquals(List.of(parent), service.prepareCompact(Map.of(parent.name(), parent), Set.of(oldChild), Set.of(parent.name(), oldChild.name())));
        verify(api).deleteRewritesByIds(List.of("old"));
    }

    @Test
    void stopsOnConflictingManualOverrideBeforeDeletingAnything() {
        when(api.fetchRewrites()).thenReturn(List.of(new RewriteDto("manual", oldChild.name(), "192.168.1.1")));
        assertThrows(ProfileSetupException.class,
                () -> service.prepareCompact(Map.of(parent.name(), parent), Set.of(oldChild), Set.of(parent.name(), oldChild.name())));
        verify(api, never()).deleteRewritesByIds(any());
    }

    @Test
    void checksCapacityBeforeDeletingAnything() {
        var existing = new ArrayList<RewriteDto>();
        for (int i = 0; i < 500; i++) existing.add(new RewriteDto("id" + i, "manual" + i + ".test", "1.1.1.1"));
        existing.add(new RewriteDto("old", oldChild.name(), oldChild.content()));
        when(api.fetchRewrites()).thenReturn(existing);
        assertThrows(ProfileSetupException.class,
                () -> service.prepareCompact(Map.of(parent.name(), parent), Set.of(oldChild), Set.of(parent.name(), oldChild.name())));
        verify(api, never()).deleteRewritesByIds(any());
    }
}
