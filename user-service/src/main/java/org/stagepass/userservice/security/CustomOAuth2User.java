package org.stagepass.userservice.security;

import org.jspecify.annotations.NullMarked;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.stagepass.userservice.entity.User;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@NullMarked
public class CustomOAuth2User implements OAuth2User {

    @Getter
    private final User user;
    private final Map<String, Object> attributes;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomOAuth2User(User user,
                            Map<String, Object> attributes,
                            Collection<? extends GrantedAuthority> authorities) {
        this.user = user;
        this.attributes = Collections.unmodifiableMap(attributes);
        this.authorities = Collections.unmodifiableCollection(authorities);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return user.getEmail();
    }
}


