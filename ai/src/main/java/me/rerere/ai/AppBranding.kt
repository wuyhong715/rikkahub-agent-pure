package me.rerere.ai

/**
 * Product identity for the `:ai` module.
 *
 * `:ai` is a plain library with no product flavor of its own, so the flavour's
 * `BRAND_NAME` BuildConfig field does not reach it. The application sets [name]
 * once at startup (see `RikkaHubApp.onCreate`). The default keeps library-only
 * consumers and unit tests on the historical value.
 */
object AppBranding {
    /** "RikkaHub" on the pure build, "Moxw" on the moxw build. */
    @Volatile
    var name: String = "RikkaHub"
}
