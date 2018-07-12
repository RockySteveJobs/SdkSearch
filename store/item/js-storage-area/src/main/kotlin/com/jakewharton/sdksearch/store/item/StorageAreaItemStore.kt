package com.jakewharton.sdksearch.store.item

import com.chrome.platform.storage.StorageArea
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.js.json

private const val KEY = "items"

// TODO replace with https://github.com/Kotlin/kotlinx.serialization/issues/116
private fun List<Item>.toJsArray(): dynamic {
  val array = js("[]")
  for (item in this) {
    val js = js("{}")
    js.id = item.id.toInt()
    js.packageName = item.packageName
    js.className = item.className
    js.deprecated = item.deprecated
    js.link = item.link
    array.push(js)
  }
  return array
}

// TODO replace with https://github.com/Kotlin/kotlinx.serialization/issues/116
private fun fromJsArray(value: dynamic): List<Item> {
  val list = mutableListOf<Item>()
  for (item in value) {
    list.add(Item.Impl(
        item.id.unsafeCast<Int>().toLong(),
        item.packageName,
        item.className,
        item.deprecated,
        item.link
    ))
  }
  return list
}

class StorageAreaItemStore(private val storage: StorageArea) : ItemStore {
  private var currentJob: Job? = null

  private suspend fun <T> serially(body: suspend () -> T): T {
    // TODO replace with actor once available in JS.

    while (true) {
      currentJob?.join() ?: break
    }
    val job = async {
      try {
        body()
      } finally {
        currentJob = null
      }
    }
    currentJob = job
    return job.await()
  }

  override suspend fun updateItems(items: List<Item>) {
    serially {
      suspendCoroutine<Unit> { continuation ->
        storage.get(KEY) {
          val existingItems = it[KEY]?.let(::fromJsArray) ?: emptyList()
          // TODO something not n^2? persist map of fqcn to item?
          val oldItems = existingItems.filter { existing ->
            items.any { it.packageName == existing.packageName && it.className == existing.className }
          }
          val newItems = oldItems + items
          storage.set(json(KEY to newItems.toJsArray())) {
            continuation.resume(Unit)
          }
        }
      }
    }
  }

  override fun queryItems(term: String): ReceiveChannel<List<Item>> {
    // TODO use onChanged listener to update this value

    val channel = ConflatedBroadcastChannel<List<Item>>()
    storage.get(KEY) {
      val allItems = it[KEY]?.let(::fromJsArray) ?: emptyList()

      val items = allItems
          .filter { it.className.contains(term, ignoreCase = true) }
          .sortedWith(compareBy {
            val name = it.className
            when {
              name.equals(term, ignoreCase = true) -> 1
              name.startsWith(term, ignoreCase = true) && name.indexOf('.') == -1 -> 2
              name.endsWith(".$term", ignoreCase = true) -> 3
              name.startsWith(term, ignoreCase = true) -> 4
              name.contains(".$term", ignoreCase = true) -> 5
              else -> 6
            }
          })
      channel.offer(items)
    }
    return channel.openSubscription()
  }

  override fun count(): ReceiveChannel<Long> {
    // TODO use onChanged listener to update this value

    val channel = ConflatedBroadcastChannel<Long>()
    storage.get(KEY) {
      val allItems = it[KEY]?.let(::fromJsArray) ?: emptyList()
      channel.offer(allItems.size.toLong())
    }
    return channel.openSubscription()
  }
}
