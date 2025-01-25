package suwayomi.tachidesk.server.user

sealed class UserType {
    class Admin(
        val id: Int,
    ) : UserType()

    class User(
        val id: Int,
        val permissions: List<Permissions>,
    ) : UserType()

    data object Visitor : UserType()
}

fun UserType.requireUser(): Int =
    when (this) {
        is UserType.Admin -> id
        is UserType.User -> id
        UserType.Visitor -> throw UnauthorizedException()
    }

fun UserType.requirePermissions(vararg permissions: Permissions) {
    when (this) {
        is UserType.Admin -> Unit
        is UserType.User -> {
            val userPermissions = this.permissions
            if (!permissions.all { it in userPermissions }) {
                throw ForbiddenException()
            }
        }
        UserType.Visitor -> throw UnauthorizedException()
    }
}

class UnauthorizedException : IllegalStateException("Unauthorized")

class ForbiddenException : IllegalStateException("Forbidden")
