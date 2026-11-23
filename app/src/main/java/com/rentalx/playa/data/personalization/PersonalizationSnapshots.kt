package com.rentalx.playa.data.personalization

import com.rentalx.playa.data.model.HomeShelf
import com.rentalx.playa.data.model.HomeSource

data class ProviderTasteSnapshot(
    val signals: List<TasteSignal> = emptyList(),
    val collections: List<HomeShelf> = emptyList(),
    val incomplete: Boolean = false,
)

internal data class TasteRequest(val source: HomeSource, val account: String, val generation: Long)

/** Account switches and refreshes invalidate both cached observations and every outstanding response. */
internal class PersonalizationSnapshots {
    private var accounts = emptyMap<HomeSource, String>()
    private val generations = mutableMapOf<HomeSource, Long>()
    private val values = mutableMapOf<HomeSource, ProviderTasteSnapshot>()

    @Synchronized
    fun select(next: Map<HomeSource, String>): Set<HomeSource> {
        val changed = (accounts.keys + next.keys).filterTo(linkedSetOf()) { accounts[it] != next[it] }
        changed.forEach { source ->
            generations[source] = (generations[source] ?: 0L) + 1L
            values.remove(source)
        }
        accounts = next.toMap()
        return changed
    }

    @Synchronized
    fun begin(source: HomeSource): TasteRequest? {
        val account = accounts[source] ?: return null
        val generation = (generations[source] ?: 0L) + 1L
        generations[source] = generation
        return TasteRequest(source, account, generation)
    }

    @Synchronized
    fun has(source: HomeSource): Boolean = source in values

    @Synchronized
    fun isCurrent(request: TasteRequest): Boolean =
        accounts[request.source] == request.account && generations[request.source] == request.generation

    @Synchronized
    fun publish(request: TasteRequest, value: ProviderTasteSnapshot): Boolean {
        if (!isCurrent(request)) return false
        values[request.source] = value
        return true
    }

    @Synchronized
    fun snapshot(): Map<HomeSource, ProviderTasteSnapshot> = values.toMap()

    @Synchronized
    fun accountSnapshot(): Map<HomeSource, String> = accounts.toMap()
}
