package com.circleguard.identity.service;

import com.circleguard.identity.model.IdentityMapping;
import com.circleguard.identity.repository.IdentityMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityVaultServiceTest {

    @Mock
    private IdentityMappingRepository repository;

    @InjectMocks
    private IdentityVaultService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "hashSalt", "test-salt");
    }

    @Test
    void getOrCreateAnonymousId_returnsSameUuidForSameInput() {
        UUID existingId = UUID.randomUUID();
        IdentityMapping existing = IdentityMapping.builder()
                .anonymousId(existingId)
                .identityHash("irrelevant")
                .salt("salt")
                .build();
        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.of(existing));

        UUID first = service.getOrCreateAnonymousId("juan.perez@test.edu");
        UUID second = service.getOrCreateAnonymousId("juan.perez@test.edu");

        assertEquals(existingId, first);
        assertEquals(first, second);
        verify(repository, never()).save(any());
    }

    @Test
    void getOrCreateAnonymousId_returnsDifferentUuidsForDifferentInputs() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        IdentityMapping mappingA = IdentityMapping.builder().anonymousId(idA).identityHash("hA").salt("s").build();
        IdentityMapping mappingB = IdentityMapping.builder().anonymousId(idB).identityHash("hB").salt("s").build();

        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenReturn(mappingA).thenReturn(mappingB);

        UUID first = service.getOrCreateAnonymousId("alice@test.edu");
        UUID second = service.getOrCreateAnonymousId("bob@test.edu");

        assertNotEquals(first, second);
    }

    @Test
    void resolveRealIdentity_throwsNotFoundForUnknownId() {
        UUID unknownId = UUID.randomUUID();
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resolveRealIdentity(unknownId));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void getOrCreateAnonymousId_persistsNewMappingOnFirstCall() {
        IdentityMapping saved = IdentityMapping.builder()
                .anonymousId(UUID.randomUUID())
                .identityHash("hash")
                .salt("salt")
                .build();
        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenReturn(saved);

        UUID result = service.getOrCreateAnonymousId("newuser@test.edu");

        assertNotNull(result);
        verify(repository).save(any(IdentityMapping.class));
    }
}
