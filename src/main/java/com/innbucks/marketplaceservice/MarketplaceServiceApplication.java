package com.innbucks.marketplaceservice;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link UserDetailsServiceAutoConfiguration} is excluded because this service
 * has no username/password login at all: every caller is authenticated by the
 * fleet JWT (security/JwtFilter), and SecurityConfig enables neither HTTP Basic
 * nor form login. With no UserDetailsService bean of our own, Boot created a
 * default in-memory "user" anyway and logged its random password at WARN on
 * every boot ("Using generated security password: ..."). Nothing could log in
 * with it, but a credential-shaped string in production logs is noise at best
 * and gets copied into tickets and chats. Excluding it by class, not by a
 * property string, makes a rename in a Boot upgrade fail the build instead of
 * silently bringing the default user back.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class MarketplaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceServiceApplication.class, args);
    }
}
