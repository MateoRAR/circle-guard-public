package com.circleguard.auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DualChainAuthProviderTest {

    @Mock private LdapAuthenticationProvider ldapProvider;
    @Mock private DaoAuthenticationProvider localProvider;

    private DualChainAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DualChainAuthenticationProvider(ldapProvider, localProvider);
    }

    @Test
    void authenticate_usesLdapFirst_andDoesNotCallLocal() {
        Authentication input = new UsernamePasswordAuthenticationToken("user", "pass");
        Authentication expected = mock(Authentication.class);
        when(ldapProvider.authenticate(input)).thenReturn(expected);

        Authentication result = provider.authenticate(input);

        assertEquals(expected, result);
        verify(localProvider, never()).authenticate(any());
    }

    @Test
    void authenticate_fallsBackToLocalDbWhenLdapFails() {
        Authentication input = new UsernamePasswordAuthenticationToken("user", "pass");
        Authentication expected = mock(Authentication.class);
        when(ldapProvider.authenticate(input)).thenThrow(new BadCredentialsException("LDAP unavailable"));
        when(localProvider.authenticate(input)).thenReturn(expected);

        Authentication result = provider.authenticate(input);

        assertEquals(expected, result);
        verify(localProvider).authenticate(input);
    }

    @Test
    void authenticate_throwsBadCredentialsWhenBothFail() {
        Authentication input = new UsernamePasswordAuthenticationToken("user", "wrong");
        when(ldapProvider.authenticate(input)).thenThrow(new BadCredentialsException("LDAP fail"));
        when(localProvider.authenticate(input)).thenThrow(new BadCredentialsException("DB fail"));

        assertThrows(BadCredentialsException.class, () -> provider.authenticate(input));
    }

    @Test
    void supports_usernamePasswordTokenClass_returnsTrue() {
        assertTrue(provider.supports(UsernamePasswordAuthenticationToken.class));
    }
}
