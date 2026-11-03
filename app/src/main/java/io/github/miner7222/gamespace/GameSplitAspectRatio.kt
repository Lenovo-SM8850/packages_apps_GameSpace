/* SPDX-License-Identifier: Apache-2.0 */
package io.github.miner7222.gamespace

import android.content.ContentResolver
import android.provider.Settings

object GameSplitAspectRatio {
    private const val SETTING = "game_split_keep_ratio_packages"

    fun isEnabled(resolver: ContentResolver, packageName: String): Boolean =
        packageName in readPackages(resolver, resolver.userId)

    internal fun readPackages(resolver: ContentResolver, userId: Int): Set<String> =
        Settings.Secure.getStringForUser(resolver, SETTING, userId)
            .orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    internal fun publish(resolver: ContentResolver, userId: Int, packages: Set<String>): Boolean {
        if (packages == readPackages(resolver, userId)) return true
        return Settings.Secure.putStringForUser(resolver, SETTING, packages.sorted().joinToString(","), userId)
    }
}
