package suwayomi.tachidesk.graphql.mutations

import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.global.impl.util.Bcrypt
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.global.model.table.UserTable
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.manga.impl.util.lang.isNotEmpty
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.Permissions
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.requirePermissions
import suwayomi.tachidesk.server.user.requireUser

class UserMutation {
    data class LoginInput(
        val clientMutationId: String? = null,
        val username: String,
        val password: String,
    )

    data class LoginPayload(
        val clientMutationId: String?,
        val accessToken: String,
        val refreshToken: String,
    )

    fun login(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LoginInput,
    ): LoginPayload {
        if (dataFetchingEnvironment.getAttribute(Attribute.TachideskUser) !is UserType.Visitor) {
            throw IllegalArgumentException("Cannot login while already logged-in")
        }
        val user =
            transaction {
                UserTable
                    .selectAll()
                    .where { UserTable.username.lowerCase() eq input.username.lowercase() }
                    .firstOrNull()
            }
        if (user != null && Bcrypt.verify(user[UserTable.password], input.password)) {
            val jwt = Jwt.generateJwt(user[UserTable.id].value)
            return LoginPayload(
                clientMutationId = input.clientMutationId,
                accessToken = jwt.accessToken,
                refreshToken = jwt.refreshToken,
            )
        } else {
            throw Exception("Incorrect username or password.")
        }
    }

    data class RefreshTokenInput(
        val clientMutationId: String? = null,
        val refreshToken: String,
    )

    data class RefreshTokenPayload(
        val clientMutationId: String?,
        val accessToken: String,
        val refreshToken: String,
    )

    fun refreshToken(input: RefreshTokenInput): RefreshTokenPayload {
        val jwt = Jwt.refreshJwt(input.refreshToken)

        return RefreshTokenPayload(
            clientMutationId = input.clientMutationId,
            accessToken = jwt.accessToken,
            refreshToken = jwt.refreshToken,
        )
    }

    data class RegisterInput(
        val clientMutationId: String? = null,
        val username: String,
        val password: String,
    )

    data class RegisterPayload(
        val clientMutationId: String?,
    )

    fun register(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RegisterInput,
    ): RegisterPayload {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requirePermissions(Permissions.CREATE_USER)

        val (clientMutationId, username, password) = input
        transaction {
            val userExists =
                UserTable
                    .selectAll()
                    .where { UserTable.username.lowerCase() eq username.lowercase() }
                    .isNotEmpty()
            if (userExists) {
                throw Exception("Username already exists")
            } else {
                UserTable.insert {
                    it[UserTable.username] = username
                    it[UserTable.password] = Bcrypt.encryptPassword(password)
                }
            }
        }

        return RegisterPayload(
            clientMutationId = clientMutationId,
        )
    }

    data class SetPasswordInput(
        val clientMutationId: String? = null,
        val password: String,
    )

    data class SetPasswordPayload(
        val clientMutationId: String?,
    )

    fun setPassword(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetPasswordInput,
    ): SetPasswordPayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        val (clientMutationId, password) = input
        transaction {
            UserTable.update({ UserTable.id eq userId }) {
                it[UserTable.password] = Bcrypt.encryptPassword(password)
            }
        }

        return SetPasswordPayload(
            clientMutationId = clientMutationId,
        )
    }
}
