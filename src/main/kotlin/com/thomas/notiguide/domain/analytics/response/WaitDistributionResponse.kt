package com.thomas.notiguide.domain.analytics.response

import com.thomas.notiguide.domain.analytics.model.WaitBucket

data class WaitDistributionResponse(
    val period: String,
    val buckets: List<WaitBucket>
)