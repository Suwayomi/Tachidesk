package suwayomi.tachidesk.graphql.queries

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.SettingsType
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser

class SettingsQuery {
    fun settings(dataFetchingEnvironment: DataFetchingEnvironment): SettingsType {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        return SettingsType()
    }
}
