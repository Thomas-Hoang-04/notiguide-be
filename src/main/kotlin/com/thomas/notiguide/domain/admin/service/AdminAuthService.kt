package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.domain.admin.repository.AdminRepository
import kotlinx.coroutines.reactor.mono
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class AdminAuthService(
    private val adminRepository: AdminRepository
) : ReactiveUserDetailsService {

    override fun findByUsername(username: String): Mono<UserDetails> = mono {
        val admin = adminRepository.findByUsername(username.lowercase())
            ?: throw UsernameNotFoundException("Admin '$username' not found")
        admin.toPrincipal()
    }
}
