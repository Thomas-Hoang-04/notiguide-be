package com.thomas.notiguide.domain.store.service

object SlugValidator {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 128

    // Alphanumeric segments joined by single hyphens; no leading/trailing/double hyphen.
    private val PATTERN = Regex("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")

    // Compared case-insensitively. Extend with brand/profanity lists as needed.
    private val RESERVED: Set<String> = setOf(
        "api", "admin", "public", "store", "stores", "queue", "queues",
        "dashboard", "login", "logout", "auth", "www", "app", "assets",
        "static", "_next", "health", "actuator", "favicon", "robots", "sitemap"
    )

    /** @throws IllegalArgumentException with a user-facing message on any violation. */
    fun validate(slug: String) {
        require(slug.length in MIN_LENGTH..MAX_LENGTH) {
            "Slug must be between $MIN_LENGTH and $MAX_LENGTH characters"
        }
        require(PATTERN.matches(slug)) {
            "Slug may contain only letters, digits, and single hyphens (no leading, trailing, or repeated hyphens)"
        }
        require(slug.lowercase() !in RESERVED) {
            "This slug is reserved and cannot be used"
        }
    }
}
