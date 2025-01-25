package suwayomi.tachidesk.global.impl.util

import at.favre.lib.crypto.bcrypt.BCrypt

object Bcrypt {
    private val hasher = BCrypt.with(BCrypt.Version.VERSION_2B)
    private val verifyer = BCrypt.verifyer(BCrypt.Version.VERSION_2B)

    fun encryptPassword(password: String): String = hasher.hashToString(12, password.toCharArray())

    fun verify(
        hash: String,
        password: String,
    ): Boolean = verifyer.verify(password.toCharArray(), hash.toCharArray()).verified
}
