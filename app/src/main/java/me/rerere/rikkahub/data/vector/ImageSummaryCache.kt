package me.rerere.rikkahub.data.vector

import android.content.Context
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import java.io.File

/**
 * What a vision model said about each image, remembered between rounds.
 *
 * Not an optimisation. A round walks the library again every few minutes and reads up to
 * [WorkspaceLibraryRules.MAX_IMAGES_PER_ROUND] images each time, so without this the same
 * photographs would be sent to a provider - and paid for - over and over for as long as the library
 * exists. With it, an image is described once, and described again only when the file changes, the
 * model changes, or the question does (see [ImageSummaryRules.cacheKeyOf]).
 *
 * On disk rather than in memory because the rounds are separated by minutes and by the app being
 * backgrounded: a cache that empties on every launch would re-describe the same images every
 * launch. The entries are small - a paragraph each - and the store is a single JSON file under
 * `cacheDir`, which the system is free to reclaim.
 */
class ImageSummaryCache(
    context: Context,
    capacity: Int = DEFAULT_CAPACITY,
) {

    private val cache: LruCache<String, String> by lazy {
        LruCache(
            capacity = capacity,
            store = SingleFileCacheStore(
                file = File(context.cacheDir, FILE_NAME),
                keySerializer = String.serializer(),
                valueSerializer = String.serializer(),
                json = Json { prettyPrint = false; ignoreUnknownKeys = true },
            ),
            deleteOnEvict = true,
            preloadFromStore = true,
        )
    }

    fun get(key: String): String? = cache.get(key)

    fun put(key: String, value: String) {
        cache.put(key, value)
    }

    companion object {
        /**
         * Room for a large library's worth of descriptions. Deliberately not tiny: the round walks
         * in path order, so a cache smaller than the library would evict exactly the entries the
         * next round is about to ask for, which is the one arrangement that re-describes everything
         * forever.
         */
        const val DEFAULT_CAPACITY = 2_048

        const val FILE_NAME = "image_summary_cache.json"
    }
}
