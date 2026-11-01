/* SPDX-License-Identifier: Apache-2.0 */
package io.github.miner7222.gamespace

import android.content.ContentResolver
import android.provider.Settings

object GameSplitAspectRatio {
    private const val SETTING = "game_split_keep_ratio_packages"

    fun isEnabled(resolver: ContentResolver, packageName: String): Boolean =
        packageName in readPackages(resolver)

    fun setEnabled(resolver: ContentResolver, packageName: String, enabled: Boolean): Boolean {
        val packages = readPackages(resolver)
        if (enabled) packages.add(packageName) else packages.remove(packageName)
        return Settings.Secure.putString(resolver, SETTING, packages.joinToString(","))
    }

    private fun readPackages(resolver: ContentResolver): MutableSet<String> =
        Settings.Secure.getString(resolver, SETTING)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toMutableSet() ?: mutableSetOf()
}
