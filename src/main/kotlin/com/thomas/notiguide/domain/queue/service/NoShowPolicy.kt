package com.thomas.notiguide.domain.queue.service

import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.entity.StoreSettings

internal fun resolveApplicableNoShowSettings(
    store: Store?,
    settings: StoreSettings?
): StoreSettings? = settings?.takeIf { store?.allowNoShow == true }
