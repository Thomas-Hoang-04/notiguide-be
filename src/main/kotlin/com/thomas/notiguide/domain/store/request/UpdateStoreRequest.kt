package com.thomas.notiguide.domain.store.request

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSetter
import jakarta.validation.constraints.Size

class UpdateStoreRequest {
    @field:Size(max = 255)
    var name: String? = null

    @JsonIgnore
    var addressProvided: Boolean = false
        private set

    @field:Size(max = 1000)
    var address: String? = null
        @JsonSetter("address")
        set(value) {
            field = value
            addressProvided = true
        }

    var isActive: Boolean? = null
}
