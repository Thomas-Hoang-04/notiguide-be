package com.thomas.notiguide.domain.store.request

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSetter

class UpdateStoreRequest {
    var name: String? = null

    @JsonIgnore
    var addressProvided: Boolean = false
        private set

    var address: String? = null
        @JsonSetter("address")
        set(value) {
            field = value
            addressProvided = true
        }

    var isActive: Boolean? = null
}
