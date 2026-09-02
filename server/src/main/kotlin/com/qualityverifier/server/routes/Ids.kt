package com.qualityverifier.server.routes

import java.util.UUID

/**
 * Whether this could be an id at all.
 *
 * Every id in this schema is a uuid and every query casts with `?::uuid`, which throws
 * inside Postgres on anything malformed — so without this check a junk id in a path is a
 * 500 rather than a 404. Shared between the phone's routes and the portal's because both
 * take ids from a URL and neither should answer differently.
 */
internal fun isUuid(value: String): Boolean =
    runCatching { UUID.fromString(value) }.isSuccess
