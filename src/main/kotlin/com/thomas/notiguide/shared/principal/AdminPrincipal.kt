package com.thomas.notiguide.shared.principal

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.UUID

class AdminPrincipal(
    private val _id: UUID,
    private val _username: String,
    private val _password: String,
    private val _authorities: Collection<GrantedAuthority>,
    val storeId: UUID?,
    val isVerified: Boolean
) : UserDetails {

    val id: UUID get() = _id

    override fun getAuthorities(): Collection<GrantedAuthority> = _authorities
    override fun getPassword(): String = _password
    override fun getUsername(): String = _username
    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = isVerified
}
