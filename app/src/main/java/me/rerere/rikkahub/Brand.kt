package me.rerere.rikkahub

/**
 * Flavor-scoped product identity.
 *
 * The two shipping lines (see cold-memory M21) differ only in *identity*: the
 * `pure` flavor is "RikkaHub", the `moxw` flavor is "Moxw". Everything that used
 * to hardcode the brand — on-disk folders under /sdcard, the built-in User-Agent,
 * OAuth landing pages, notification/recovery prose — reads it from here instead of
 * a literal, so one source tree ships both products.
 */
object Brand {
    /** "RikkaHub" on the pure flavor, "Moxw" on the moxw flavor. */
    val NAME: String = BuildConfig.BRAND_NAME
}
