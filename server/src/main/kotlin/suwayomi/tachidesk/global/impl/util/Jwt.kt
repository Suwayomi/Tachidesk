package suwayomi.tachidesk.global.impl.util

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.global.model.table.UserPermissionsTable
import suwayomi.tachidesk.global.model.table.UserRolesTable
import suwayomi.tachidesk.server.user.Permissions
import suwayomi.tachidesk.server.user.UserType
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

object Jwt {
    private const val ALGORITHM = "HmacSHA256"
    private val accessTokenExpiry = 1.hours
    private val refreshTokenExpiry = 60.days
    private const val ISSUER = "tachidesk"
    private const val AUDIENCE = "" // todo audience

    @OptIn(ExperimentalEncodingApi::class)
    fun generateSecret(): String {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)

        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)

        return Base64.encode(secretKey.encoded)
    }

    private val algorithm: Algorithm = Algorithm.HMAC256(generateSecret()) // todo store secret
    private val verifier: JWTVerifier = JWT.require(algorithm).build()

    class JwtTokens(
        val accessToken: String,
        val refreshToken: String,
    )

    fun generateJwt(userId: Int): JwtTokens {
        val accessToken = createAccessToken(userId)
        val refreshToken = createRefreshToken(userId)

        return JwtTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
    }

    fun refreshJwt(refreshToken: String): JwtTokens {
        val jwt = verifier.verify(refreshToken)
        require(jwt.getClaim("token_type").asString() == "refresh") {
            "Cannot use access token to refresh"
        }
        return generateJwt(jwt.subject.toInt())
    }

    fun verifyJwt(jwt: String): UserType {
        try {
            val decodedJWT = verifier.verify(jwt)

            require(decodedJWT.getClaim("token_type").asString() == "access") {
                "Cannot use refresh token to access"
            }

            val user = decodedJWT.subject.toInt()
            val roles: List<String> = decodedJWT.getClaim("roles").asList(String::class.java)
            val permissions: List<String> = decodedJWT.getClaim("permissions").asList(String::class.java)

            return if (roles.any { it.equals("admin", ignoreCase = true) }) {
                UserType.Admin(user)
            } else {
                UserType.User(
                    id = user,
                    permissions =
                        permissions.mapNotNull { permission ->
                            Permissions.entries.find { it.name == permission }
                        },
                )
            }
        } catch (e: JWTVerificationException) {
            return UserType.Visitor
        }
    }

    private fun createAccessToken(userId: Int): String {
        val jwt =
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(userId.toString())
                .withClaim("token_type", "access")
                .withExpiresAt(Instant.now().plusSeconds(accessTokenExpiry.inWholeSeconds))

        val roles =
            transaction {
                UserRolesTable
                    .selectAll()
                    .where { UserRolesTable.user eq userId }
                    .toList()
                    .map { it[UserRolesTable.role] }
            }
        val permissions =
            transaction {
                UserPermissionsTable
                    .selectAll()
                    .where { UserPermissionsTable.user eq userId }
                    .toList()
                    .map { it[UserPermissionsTable.permission] }
            }

        jwt.withClaim("roles", roles)

        jwt.withClaim("permissions", permissions)

        return jwt.sign(algorithm)
    }

    private fun createRefreshToken(userId: Int): String =
        JWT
            .create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(userId.toString())
            .withClaim("token_type", "refresh")
            .withExpiresAt(Instant.now().plusSeconds(refreshTokenExpiry.inWholeSeconds))
            .sign(algorithm)
}
