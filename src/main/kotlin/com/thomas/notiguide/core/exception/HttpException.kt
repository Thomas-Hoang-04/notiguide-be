package com.thomas.notiguide.core.exception

import org.springframework.http.HttpStatus

open class HttpException(
    val status: HttpStatus,
    override val message: String
) : RuntimeException(message)

class NotFoundException(entity: String, field: String, value: String)
    : HttpException(HttpStatus.NOT_FOUND, "$entity with $field '$value' not found")

class ConflictException(message: String)
    : HttpException(HttpStatus.CONFLICT, message)

class ForbiddenException(message: String)
    : HttpException(HttpStatus.FORBIDDEN, message)

class UnverifiedAdminException
    : HttpException(HttpStatus.FORBIDDEN, "Admin account has not been verified by an elevated admin")
